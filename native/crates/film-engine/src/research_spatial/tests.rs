use super::*;
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};
static NEXT: AtomicU64 = AtomicU64::new(1);
struct Root(PathBuf);
impl Root {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "film-optics-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&p).unwrap();
        Self(p)
    }
}
impl Drop for Root {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn plan(w: u32, h: u32) -> FilmOpticsPlan {
    let mut p = RuntimeParams::default();
    p.camera.lens_blur_um = 7.;
    FilmOpticsPlan::new(&p, 20., w, h).unwrap()
}
fn source(w: u32, h: u32) -> Vec<f64> {
    (0..w * h)
        .map(|i| {
            let x = i % w;
            let y = i / w;
            ((x * 17 + y * 31) % 113) as f64 / 19. - 0.8
                + if x == w / 3 && y == h / 2 { 17. } else { 0. }
        })
        .collect()
}
fn files(root: &Root, w: u32, h: u32) -> [PathBuf; 3] {
    let paths = std::array::from_fn(|ch| root.0.join(format!("input-{ch}")));
    for (ch, p) in paths.iter().enumerate() {
        let mut f = File::create(p).unwrap();
        for x in source(w, h) {
            f.write_all(&(x * (ch + 1) as f64).to_le_bytes()).unwrap();
        }
    }
    paths
}
#[test]
fn original_fir_boundary_and_dispatch() {
    assert_eq!(reflect(-1, 4), 0);
    assert_eq!(reflect(4, 4), 3);
    assert_eq!(reflect(-5, 4), 3);
    assert_eq!(reflect(10, 1), 0);
    assert!(matches!(Filter::new(2.999).unwrap(), Filter::Fir(_)));
    assert!(matches!(Filter::new(3.).unwrap(), Filter::Iir(_)));
    let Filter::Fir(k) = Filter::new(0.5).unwrap() else {
        panic!()
    };
    assert_eq!(k.len(), 5);
    assert!((k.iter().sum::<f64>() - 1.).abs() < 1e-15);
}
#[test]
fn arithmetic_mirror_covers_asymmetric_small_long_lines() {
    for n in [1, 2, 3, 19, 71, 8193] {
        for sigma in [0., 0.1, 0.5, 2.999, 3., 4., 7.] {
            let src: Vec<f64> = (0..n)
                .map(|i| {
                    if i == 0 {
                        20.
                    } else {
                        ((i * 43) % 41) as f64 / 3. - 2.
                    }
                })
                .collect();
            let f = Filter::new(sigma).unwrap();
            let a = f.line_f64(&src);
            let b = f.line_f32(&src.iter().map(|&x| x as f32).collect::<Vec<_>>());
            compare_lines(&a, &b).unwrap();
        }
    }
}
#[test]
fn preflight_rejects_unstable_large_sigma_not_radius_reduction() {
    let mut p = plan(40000, 1);
    for ch in &mut p.channels {
        ch.lens_sigma = 128.;
        ch.scatter = None;
        ch.bounce_sigmas.clear();
        ch.bounce_weights.clear();
        ch.divisor = 1.;
    }
    assert_eq!(
        p.numerical_preflight(&mut || false).unwrap_err(),
        "GPU_OPTICS_NUMERIC_CHECK"
    );
    assert_eq!(p.channels[0].lens_sigma, 128.);
    let mut checks = 0;
    assert_eq!(
        p.numerical_preflight(&mut || {
            checks += 1;
            checks > 0
        })
        .unwrap_err(),
        "CANCELLED"
    );
}
#[test]
fn admission_rejects_full_plane_limits_and_tampering() {
    let params = RuntimeParams::default();
    assert!(FilmOpticsPlan::new(&params, 20., 40000, 40000).is_err());
    assert!(FilmOpticsPlan::new(&params, 20., 0, 9).is_err());
    let mut p = plan(13, 29);
    p.device_bytes -= 4;
    assert!(p.validate().is_err());
    let mut p = plan(13, 29);
    p.channels[0].bounce_weights.push(1.);
    assert!(p.validate().is_err());
    let mut params = params;
    params.film_render.halation.active = false;
    params.camera.lens_blur_um = 0.;
    let p = FilmOpticsPlan::new(&params, 20., 13, 29).unwrap();
    assert!(!p.has_work());
}
#[cfg(feature = "precision-f64")]
fn original_channel(input: &[f64], w: u32, h: u32, p: &ChannelPlan) -> Vec<f64> {
    use spektrafilm_math::gaussian::gaussian_blur_channel;
    let mut src = input.to_vec();
    if p.lens_sigma > 0. {
        src = gaussian_blur_channel(&src, w, h, p.lens_sigma);
    }
    if let Some(s) = &p.scatter {
        let core = gaussian_blur_channel(&src, w, h, s.sigma);
        let mut tail = vec![0.; src.len()];
        for (amp, ratio) in FIT {
            let b = gaussian_blur_channel(&src, w, h, (ratio * s.decay as f64) as f32);
            for (i, x) in tail.iter_mut().enumerate() {
                *x += amp * b[i];
            }
        }
        for i in 0..src.len() {
            src[i] = (1. - s.amount) * src[i]
                + s.amount * ((1. - s.tail_weight) * core[i] + s.tail_weight * tail[i]);
        }
    }
    if !p.bounce_sigmas.is_empty() {
        let mut sum = vec![0.; src.len()];
        for (&sigma, &weight) in p.bounce_sigmas.iter().zip(&p.bounce_weights) {
            let b = gaussian_blur_channel(&src, w, h, sigma);
            for (i, x) in sum.iter_mut().enumerate() {
                *x += weight * b[i];
            }
        }
        for i in 0..src.len() {
            src[i] += p.strength * sum[i];
        }
    }
    if p.divisor != 1. {
        for x in &mut src {
            *x /= p.divisor;
        }
    }
    src
}
#[test]
#[cfg(feature = "precision-f64")]
fn f64_line_oracle_matches_original_math_bitwise() {
    use spektrafilm_math::gaussian::gaussian_blur_rows;
    for n in [1, 2, 17, 519] {
        for sigma in [0., 0.1, 0.5, 2.999, 3., 7., 32., 128.] {
            let src = source(n, 1);
            let mut want = vec![0.; n as usize];
            gaussian_blur_rows(&src, &mut want, n as usize, sigma);
            let have = Filter::new(sigma).unwrap().line_f64(&src);
            assert_eq!(
                have.iter().map(|x| x.to_bits()).collect::<Vec<_>>(),
                want.iter().map(|x| x.to_bits()).collect::<Vec<_>>(),
                "sigma {sigma} n{n}"
            );
        }
    }
}
#[test]
#[cfg(feature = "precision-f64")]
fn full_graph_mirror_vs_original_hdr_negative_edges() {
    for (w, h) in [(1, 1), (1, 59), (67, 1), (71, 37), (257, 19)] {
        let p = plan(w, h);
        let src = source(w, h);
        for ch in 0..3 {
            let reference = original_channel(&src, w, h, &p.channels[ch]);
            let actual = channel_cpu_mirror(&src, w, h, &p.channels[ch], &mut || false).unwrap();
            for (a, b) in reference.iter().zip(&actual) {
                assert!(
                    (a - b).abs() < 1e-3 * a.abs().max(1.),
                    "{w} {h} ch{ch} {a} {b}"
                );
            }
        }
    }
}
#[test]
fn final_renormalization_matches_original_skipped_channel_rule() {
    let mut params = RuntimeParams::default();
    params.film_render.halation.scatter_amount = 0.;
    params.film_render.halation.halation_first_sigma_um[1] = 0.;
    let p = FilmOpticsPlan::new(&params, 20., 17, 3).unwrap();
    let ch = &p.channels[1];
    assert!(ch.bounce_sigmas.is_empty());
    assert_eq!(
        ch.divisor,
        1. + params.film_render.halation.halation_strength[1]
            * params.film_render.halation.halation_amount
    );
    let src = vec![2.; 51];
    let actual = channel_cpu_mirror(&src, 17, 3, ch, &mut || false).unwrap();
    assert_eq!(actual[0], (2f32 / ch.divisor as f32) as f64);
}
#[test]
fn stage_roundtrip_atomic_candidate_and_source_immutable() {
    let root = Root::new();
    let (w, h) = (73, 29);
    let inputs = files(&root, w, h);
    let before: Vec<_> = inputs.iter().map(|p| fs::read(p).unwrap()).collect();
    let dir = root.0.join("gpu-stage-42");
    let p = plan(w, h);
    let staged = full_stage::prepare_stage(
        inputs.each_ref().map(|p| p.as_path()),
        &dir,
        42,
        w,
        h,
        &p,
        || false,
    )
    .unwrap();
    assert_eq!(staged, full_stage::plan_stage(&dir, 42).unwrap());
    assert!(staged.pass_count > 0);
    assert!(full_stage::validate_outputs(&dir, 42, &staged.manifest_sha256, || false).is_err());
    let result =
        full_stage::run_stage_cpu_mirror_for_test(&dir, 42, &staged.manifest_sha256, || false)
            .unwrap();
    assert_eq!(result["gpuUsed"], false);
    let outputs =
        full_stage::validate_outputs(&dir, 42, &staged.manifest_sha256, || false).unwrap();
    for (ch, path) in outputs.iter().enumerate() {
        assert_eq!(fs::metadata(path).unwrap().len(), w as u64 * h as u64 * 8);
        assert_eq!(fs::read(&inputs[ch]).unwrap(), before[ch]);
    }
    OpenOptions::new()
        .write(true)
        .open(&outputs[2])
        .unwrap()
        .write_all(&f64::NAN.to_le_bytes())
        .unwrap();
    assert!(full_stage::validate_outputs(&dir, 42, &staged.manifest_sha256, || false).is_err());
}
#[test]
fn stage_cancellation_and_bad_third_plane_leave_originals() {
    let root = Root::new();
    let (w, h) = (67, 31);
    let inputs = files(&root, w, h);
    let before: Vec<_> = inputs.iter().map(|p| fs::read(p).unwrap()).collect();
    let dir = root.0.join("gpu-stage-3");
    let p = plan(w, h);
    let mut calls = 0;
    assert_eq!(
        full_stage::prepare_stage(
            inputs.each_ref().map(|p| p.as_path()),
            &dir,
            3,
            w,
            h,
            &p,
            || {
                calls += 1;
                calls > 2
            }
        )
        .unwrap_err(),
        "CANCELLED"
    );
    assert!(!dir.exists());
    let stage = full_stage::prepare_stage(
        inputs.each_ref().map(|p| p.as_path()),
        &dir,
        3,
        w,
        h,
        &p,
        || false,
    )
    .unwrap();
    let mut calls = 0;
    assert_eq!(
        full_stage::run_stage_cpu_mirror_for_test(&dir, 3, &stage.manifest_sha256, || {
            calls += 1;
            calls > 6
        })
        .unwrap_err(),
        "CANCELLED"
    );
    assert!(!dir.join("candidate").exists());
    for ch in 0..3 {
        assert_eq!(fs::read(&inputs[ch]).unwrap(), before[ch]);
    }
    OpenOptions::new()
        .write(true)
        .open(&inputs[2])
        .unwrap()
        .write_all(&f64::NAN.to_le_bytes())
        .unwrap();
    assert_eq!(
        full_stage::run_stage_cpu_mirror_for_test(&dir, 3, &stage.manifest_sha256, || false)
            .unwrap_err(),
        "NONFINITE_OPTICS_INPUT"
    );
    assert!(!dir.join("candidate").exists());
}
#[test]
fn manifest_hash_shape_and_ownership_fail_closed() {
    let root = Root::new();
    let (w, h) = (11, 7);
    let inputs = files(&root, w, h);
    let p = plan(w, h);
    assert!(
        full_stage::prepare_stage(
            inputs.each_ref().map(|p| p.as_path()),
            &root.0.join("wrong"),
            1,
            w,
            h,
            &p,
            || false
        )
        .is_err()
    );
    let dir = root.0.join("gpu-stage-1");
    let stage = full_stage::prepare_stage(
        inputs.each_ref().map(|p| p.as_path()),
        &dir,
        1,
        w,
        h,
        &p,
        || false,
    )
    .unwrap();
    assert!(
        full_stage::run_stage(
            &dir,
            1,
            stage.combined_peak_bytes - 1,
            &stage.manifest_sha256,
            || false
        )
        .is_err()
    );
    assert!(
        full_stage::run_stage(&dir, 1, stage.combined_peak_bytes, &"0".repeat(64), || {
            false
        })
        .is_err()
    );
    let mut manifest: serde_json::Value =
        serde_json::from_slice(&fs::read(dir.join("manifest.json")).unwrap()).unwrap();
    manifest["optics"]["height"] = serde_json::json!(h + 1);
    fs::write(
        dir.join("manifest.json"),
        serde_json::to_vec(&manifest).unwrap(),
    )
    .unwrap();
    assert!(full_stage::plan_stage(&dir, 1).is_err());
}
#[test]
fn shader_parses_and_validates() {
    let module = naga::front::wgsl::parse_str(SHADER).unwrap();
    naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::empty(),
    )
    .validate(&module)
    .unwrap();
}

/// Cross-language contract fixture, emitted only into an explicit host test sink.
/// This is the real prepared production StagePlan, never a hand-maintained JSON shape.
#[test]
fn emit_optical_plan_contract_when_requested() {
    let Some(destination) = std::env::var_os("FILM_GPU_CONTRACT_RESULTS") else {
        return;
    };
    let root = Root::new();
    let (w, h) = (17, 11);
    let inputs = files(&root, w, h);
    let p = plan(w, h);
    let dir = root.0.join("gpu-stage-42");
    let staged = full_stage::prepare_stage(
        inputs.each_ref().map(|p| p.as_path()),
        &dir,
        42,
        w,
        h,
        &p,
        || false,
    )
    .unwrap();
    let envelope = serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmOpticsStage","plan":staged});
    let destination = PathBuf::from(destination);
    fs::create_dir_all(&destination).unwrap();
    fs::write(
        destination.join("optical-plan.json"),
        serde_json::to_vec_pretty(&envelope).unwrap(),
    )
    .unwrap();
}
