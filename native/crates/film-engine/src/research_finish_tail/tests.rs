use super::*;
use std::{
    fs,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
const ID: [[f64; 3]; 3] = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
pub(super) fn root() -> PathBuf {
    static NEXT: AtomicU64 = AtomicU64::new(1);
    let p = std::env::temp_dir().join(format!(
        "tail-tests-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    fs::create_dir(&p).unwrap();
    p.canonicalize().unwrap()
}
fn descriptor() -> GpuScanTailDescriptor<'static> {
    GpuScanTailDescriptor {
        adapt: ID,
        xyz_to_rgb: ID,
        xyz_remap: None,
        glare_rgb_offset: [1., 0.99, 1.01],
        gamut: None,
        rgb_identity: ID,
        output_cctf_encoding: false,
    }
}
pub(super) fn plan(w: u32, h: u32, lens: f32, sharp: f32, glare: bool) -> TailPlan {
    TailPlan::new(
        &descriptor(),
        w,
        h,
        TailOptions {
            glare: glare.then_some(GlarePlan {
                percent: 0.3,
                roughness: 0.7,
                blur: lens,
            }),
            lens_sigma: lens,
            sharp_radius: sharp,
            sharp_amount: 0.3,
            brightness_ev: 0.5,
            finalize_cctf: !glare && lens == 0. && sharp == 0.,
            preview_output_space: None,
        },
    )
    .unwrap()
}
pub(super) fn logs(w: u32, h: u32) -> Vec<[f32; 3]> {
    (0..w * h)
        .map(|i| {
            let x = -2.5 + (i % 31) as f32 / 13.;
            [x, x + 0.025, x - 0.01]
        })
        .collect()
}
pub(super) fn glare_values(w: u32, h: u32) -> Vec<f32> {
    let mut sample = spektrafilm_model::glare::GlareSamples::new(0.3, 0.7, 42).unwrap();
    (0..w * h).map(|_| sample.sample() as f32).collect()
}
pub(super) fn sources(root: &Path, w: u32, h: u32, id: u64) -> (PathBuf, PathBuf) {
    let s = root.join(format!("logs-{id}"));
    let g = root.join(format!("glare-{id}"));
    fs::write(
        &s,
        logs(w, h)
            .into_iter()
            .flatten()
            .flat_map(|x| (x as f64).to_le_bytes())
            .collect::<Vec<_>>(),
    )
    .unwrap();
    fs::write(
        &g,
        glare_values(w, h)
            .into_iter()
            .flat_map(|x| (x as f64).to_le_bytes())
            .collect::<Vec<_>>(),
    )
    .unwrap();
    (s, g)
}
#[test]
fn composed_shader_validates_all_modes() {
    let s = shader();
    let module =
        naga::front::wgsl::parse_str(&s).unwrap_or_else(|e| panic!("{}", e.emit_to_string(&s)));
    naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::empty(),
    )
    .validate(&module)
    .unwrap();
}
#[test]
fn composed_numeric_guards_fail_closed_on_shared_shader_drift() {
    for old in [
        " if(op==0u || op==1u){",
        "sum=sum+get_value(p.u[1].z,index)*coefficient;",
        "let v=a*get_value(p.u[1].z,index)+b1*s1+b2*s2+b3*s3;",
        "let v=a*get_value(p.u[1].w,index)+b1*s1+b2*s2+b3*s3;",
    ] {
        let changed =
            research_spatial::SHADER.replace(old, "/* incompatible upstream arithmetic */");
        assert_eq!(
            shader_checked(&changed).unwrap_err(),
            "GPU_TAIL_SHADER_VERSION"
        );
        let doubled = format!("{}\n{old}", research_spatial::SHADER);
        assert_eq!(
            shader_checked(&doubled).unwrap_err(),
            "GPU_TAIL_SHADER_VERSION"
        );
    }
    let s = shader();
    assert!(s.contains("@binding(7)"));
    assert!(s.contains("atomicOr(&tail_numeric_error.value,1u)"));
    assert!(!SHADER_APPEND.contains("tail_nan"));
    assert!(!SHADER_APPEND.contains("finite_value"));
    assert!(!glare::SHADER.contains("finite_value"));
    assert!(SHADER_APPEND.contains("logxyz>vec3<f32>(15.0)"));
    assert!(SHADER_APPEND.contains("any(abs(d)>=vec3<f32>(400.0))"));
}
#[test]
fn original_branch_encoding_is_not_moved_after_spatial_passes() {
    let mut p = RuntimeParams::default();
    p.io.scan_film = false;
    p.print_render.glare.active = false;
    p.scanner.lens_blur = 0.;
    p.scanner.unsharp_mask = [0., 0.];
    p.enlarger.diffusion_filter.active = false;
    assert!(TailOptions::from_params(&p, 0.).finalize_cctf);
    p.print_render.glare.active = true;
    p.print_render.glare.percent = 0.2;
    assert!(!TailOptions::from_params(&p, 0.).finalize_cctf);
    p.print_render.glare.active = false;
    p.scanner.unsharp_mask = [1., 0.3];
    assert!(!TailOptions::from_params(&p, 0.).finalize_cctf);
    p.scanner.unsharp_mask = [0., 0.];
    p.enlarger.diffusion_filter.active = true;
    assert!(!TailOptions::from_params(&p, 0.).finalize_cctf);
    p.io.scan_film = true;
    p.film_render.glare.active = false;
    assert!(TailOptions::from_params(&p, 0.).finalize_cctf);
}
#[test]
fn memory_shapes_nonfinite_and_encoding_contracts_fail_closed() {
    assert!(
        TailPlan::new(
            &descriptor(),
            40000,
            40000,
            plan(1, 1, 0., 0., false).options
        )
        .is_err()
    );
    let mut p = plan(1, 1, 0., 0., false);
    p.combined_peak_bytes -= 1;
    assert!(p.validate().is_err());
    let mut p = plan(1, 1, 0., 0., false);
    p.adapt[0][0] = f64::NAN;
    assert!(p.validate().is_err());
    let mut p = plan(1, 1, 0., 0., false);
    p.options.brightness_ev = 10000.;
    assert!(p.validate().is_err());
    let p = plan(1, 1, 0., 0., false);
    for x in [f32::NAN, f32::INFINITY, 50.] {
        assert!(color_mirror(&p, [x, 0., 0.], 0.).is_err());
    }
    assert!(color_mirror(&p, [0.; 3], f32::NAN).is_err());
    let mut p = plan(1, 1, 0., 0., false);
    p.options.glare = Some(GlarePlan {
        percent: 1.,
        roughness: 0.,
        blur: 0.,
    });
    assert!(p.validate().is_err());
}
#[test]
fn bw_remap_separate_matrices_and_clip_before_brightness() {
    let mut p = plan(1, 1, 0., 0., false);
    p.xyz_remap = Some((0.7, 0.03));
    p.adapt = [[1.1, 0.2, 0.], [0., 0.9, 0.1], [0.1, 0., 1.]];
    p.xyz_to_rgb = [[0.7, -0.1, 0.], [0.1, 0.8, 0.], [0., 0.1, 1.1]];
    let input = [-0.3, -0.5, -0.7];
    let xyz = input.map(|x| 10f32.powf(x));
    let scale = (0.7 * xyz[1] + 0.03) / (xyz[1] + 1e-10);
    let expected = mat(&p.xyz_to_rgb, mat(&p.adapt, xyz.map(|x| x * scale)));
    assert_eq!(color_mirror(&p, input, 0.).unwrap(), expected);
    let p = plan(1, 1, 0., 0., false);
    let out = image_mirror(&p, &[[1., 0., -1.]], None, &mut || false).unwrap();
    assert_eq!(out[0][0], 2f64.powf(0.5) as f32);
    assert!(out[0][0] > 1.);
}
#[test]
fn original_all_gamut_modes_preserved_with_full_tables() {
    use spektrafilm_core::{
        gamut_compression::OutputGamutCompress, params::OutputGamutCompressParams,
    };
    for algorithm in ["off", "aces_rgc", "oklch", "oklrab", "cam16ucs"] {
        let params = OutputGamutCompressParams {
            algorithm: algorithm.into(),
            ..Default::default()
        };
        let original = OutputGamutCompress::build(&params, "sRGB");
        let mut d = descriptor();
        d.gamut = original.gpu_params();
        let p = TailPlan::new(&d, 1, 1, plan(1, 1, 0., 0., false).options).unwrap();
        if algorithm == "off" {
            assert!(p.gamut.is_none());
            continue;
        }
        let g = p.gamut.as_ref().unwrap();
        assert_eq!(
            g.cmax.len(),
            if algorithm == "aces_rgc" { 0 } else { 64 * 720 }
        );
        for rgb in [
            [0., 0., 0.],
            [0.001, 0.002, 0.003],
            [0.2, 0.18, 0.21],
            [1.4, 0.3, 0.1],
            [-0.08, 0.4, 0.8],
            [2., 2., 2.],
            [0.95, 0.02, 0.6],
        ] {
            let expected = original.compress(rgb);
            let actual = gamut::compress(g, rgb.map(|v| v as f32));
            for (a, b) in actual.into_iter().zip(expected) {
                if b.is_finite() {
                    assert!(
                        a.is_finite() && (a as f64 - b).abs() < 2e-4 * b.abs().max(1.),
                        "{algorithm} {rgb:?}: {a} {b}"
                    );
                }
            }
        }
    }
}
#[test]
fn complete_stage_keeps_originals_and_validates_output() {
    let root = root();
    let (s, g) = sources(&root, 17, 13, 1);
    let before = fs::read(&s).unwrap();
    let gb = fs::read(&g).unwrap();
    let p = plan(17, 13, 0.7, 0.8, true);
    let dir = root.join("gpu-stage-1");
    let prepared = prepare_stage(
        &s,
        Some(&g),
        &dir,
        1,
        17,
        13,
        &descriptor(),
        p.options,
        || false,
    )
    .unwrap();
    assert_eq!(prepared.operation, "fullScanTail");
    let report =
        full_stage::run_stage_cpu_mirror_for_test(&dir, 1, &prepared.manifest_sha256, || false)
            .unwrap();
    assert_eq!(report["gpuUsed"], false);
    assert_eq!(report["glareGenerationGpu"], false);
    let output = validate_output(&dir, 1, &prepared.manifest_sha256, || false).unwrap();
    assert_eq!(fs::metadata(&output).unwrap().len(), 17 * 13 * 12);
    assert_eq!(fs::read(&s).unwrap(), before);
    assert_eq!(fs::read(&g).unwrap(), gb);
    fs::write(output, vec![0; 17 * 13 * 12]).unwrap();
    assert!(validate_output(&dir, 1, &prepared.manifest_sha256, || false).is_err());
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn cancellation_budget_collision_and_bad_inputs_never_publish() {
    let root = root();
    let (s, g) = sources(&root, 9, 7, 1);
    let d = descriptor();
    let p = plan(9, 7, 0., 0., false);
    let dir = root.join("gpu-stage-2");
    assert_eq!(
        prepare_stage(&s, None, &dir, 2, 9, 7, &d, p.options.clone(), || true).unwrap_err(),
        "CANCELLED"
    );
    assert!(!dir.exists());
    let prepared = prepare_stage(&s, None, &dir, 2, 9, 7, &d, p.options.clone(), || false).unwrap();
    assert!(prepare_stage(&s, None, &dir, 2, 9, 7, &d, p.options, || false).is_err());
    assert_eq!(
        run_stage(&dir, 2, 0, &prepared.manifest_sha256, || false).unwrap_err(),
        "INSUFFICIENT_GPU_TAIL_MEMORY"
    );
    assert!(!dir.join("candidate").exists());
    let mut calls = 0;
    let result =
        full_stage::run_stage_cpu_mirror_for_test(&dir, 2, &prepared.manifest_sha256, || {
            calls += 1;
            calls > 3
        });
    assert_eq!(result.unwrap_err(), "CANCELLED");
    assert!(!dir.join("candidate").exists());
    let dir = root.join("gpu-stage-3");
    assert!(
        prepare_stage(
            &s,
            Some(&g),
            &dir,
            3,
            9,
            7,
            &d,
            plan(9, 7, 0., 0., false).options,
            || false
        )
        .is_err()
    );
    assert!(!dir.exists());
    assert!(s.exists() && g.exists());
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn full_line_blurs_and_sharpen_keep_tiny_edges_and_global_rng_order() {
    for (w, h) in [(1, 1), (1, 13), (17, 1), (19, 13)] {
        for sigma in [0., 0.7, 2.99, 3., 4.] {
            let p = plan(w, h, sigma, sigma, true);
            let a =
                image_mirror(&p, &logs(w, h), Some(&glare_values(w, h)), &mut || false).unwrap();
            assert_eq!(a.len(), (w * h) as usize);
            assert!(a.iter().flatten().all(|x| x.is_finite()));
            let mut calls = 0;
            assert_eq!(
                image_mirror(&p, &logs(w, h), Some(&glare_values(w, h)), &mut || {
                    calls += 1;
                    calls > 1
                })
                .unwrap_err(),
                "CANCELLED"
            );
        }
    }
}
#[cfg(feature = "precision-f64")]
#[test]
fn real_prepared_scanner_descriptor_matches_all_output_spaces_bw_and_gamut() {
    use spektrafilm_core::{pipeline::Pipeline, profile::load_profile_by_name};
    use spektrafilm_gpu::cpu_backend::CpuBackend;
    use spektrafilm_math::{image::ImageBuf, precision::from_f64};
    let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    for (space, algorithm, encode, slide) in [
        ("sRGB", "off", false, false),
        ("sRGB", "aces_rgc", true, false),
        ("sRGB", "oklch", false, false),
        ("sRGB", "oklrab", false, true),
        ("sRGB", "cam16ucs", false, true),
        ("Display P3", "off", false, false),
        ("Rec. 2020", "off", false, true),
        ("Adobe RGB (1998)", "off", false, false),
        ("ProPhoto RGB", "off", false, true),
        ("ACES2065-1", "off", false, false),
    ] {
        let film = load_profile_by_name(
            &data,
            if slide {
                "kodak_ektachrome_100"
            } else {
                "kodak_portra_400"
            },
        )
        .unwrap();
        let paper = load_profile_by_name(&data, "kodak_portra_endura").unwrap();
        let mut params = RuntimeParams::default();
        params.settings.use_enlarger_lut = true;
        params.settings.use_scanner_lut = true;
        params.settings.lut_resolution = 5;
        params.io.output_color_space = space.into();
        params.io.output_gamut_compress.algorithm = algorithm.into();
        params.io.output_cctf_encoding = encode;
        params.io.scan_film = slide;
        params.scanner.white_correction = true;
        params.scanner.black_correction = true;
        params.scanner.lens_blur = 0.;
        params.scanner.unsharp_mask = [0., 0.];
        params.print_render.glare.active = false;
        params.film_render.glare.active = false;
        params.enlarger.diffusion_filter.active = false;
        let pipe = Pipeline::new_with_spectral(film, paper, params.clone(), &data).unwrap();
        let prepared = pipe.prepare_streamed_finish_spatial(&CpuBackend).unwrap();
        let descriptor = prepared.gpu_scan_tail_descriptor();
        let p = TailPlan::new(&descriptor, 17, 3, TailOptions::from_params(&params, 0.)).unwrap();
        let x = logs(17, 3);
        let image = ImageBuf::from_data(
            17,
            3,
            x.iter().flatten().map(|&v| from_f64(v as f64)).collect(),
        );
        let expected = prepared.finish_scan_log_xyz_chunk(&image, true).unwrap();
        let actual = image_mirror(&p, &x, None, &mut || false).unwrap();
        for (a, b) in actual.into_iter().flatten().zip(expected.data) {
            assert!(
                (a as f64 - b as f64).abs() < 5e-4,
                "{space} {algorithm} {slide} {a} {b}"
            );
        }
    }
}

#[test]
fn gpu_glare_packet_owns_no_cpu_field_and_exposes_exact_witness_counts() {
    let root = root();
    let (s, _) = sources(&root, 17, 13, 99);
    let options = plan(17, 13, 0.7, 0.8, true).options;
    let dir = root.join("gpu-stage-99");
    let p = prepare_stage(&s, None, &dir, 99, 17, 13, &descriptor(), options, || false).unwrap();
    assert!(!dir.join("source.glare.f64le").exists());
    assert_eq!(p.pass_count, 7);
    assert_eq!(p.sample_count, 7 * 30 + 24);
    full_stage::run_stage_cpu_mirror_for_test(&dir, 99, &p.manifest_sha256, || false).unwrap();
    validate_output(&dir, 99, &p.manifest_sha256, || false).unwrap();
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn production_scan_tail_plan_contract_for_android() {
    let Some(destination) = std::env::var_os("FILM_GPU_CONTRACT_RESULTS") else {
        return;
    };
    let root = root();
    let (source, _) = sources(&root, 17, 13, 42);
    let dir = root.join("gpu-stage-42");
    let prepared = prepare_stage(
        &source,
        None,
        &dir,
        42,
        17,
        13,
        &descriptor(),
        plan(17, 13, 0.7, 0.8, true).options,
        || false,
    )
    .unwrap();
    assert_eq!(plan_stage(&dir, 42).unwrap(), prepared);
    let destination = PathBuf::from(destination);
    fs::create_dir_all(&destination).unwrap();
    fs::write(destination.join("scan-tail-plan.json"), serde_json::to_vec_pretty(
        &serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuScanTailStage","plan":prepared})
    ).unwrap()).unwrap();
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn preview_output_space_conversion_is_after_clip_before_brightness() {
    for space in ["sRGB", "Display P3", "Adobe RGB (1998)", "ProPhoto RGB"] {
        let mut params = RuntimeParams::default();
        params.io.output_color_space = space.into();
        params.print_render.glare.active = false;
        params.scanner.lens_blur = 0.;
        params.scanner.unsharp_mask = [0., 0.];
        params.enlarger.diffusion_filter.active = false;
        let options = TailOptions::for_preview(&params, 0.7);
        let p = TailPlan::new(&descriptor(), 1, 1, options).unwrap();
        let input = [0.4, -1.0, -0.3];
        let actual = image_mirror(&p, &[input], None, &mut || false).unwrap()[0];
        let clipped = input.map(|x| 10f64.powf(x as f64).clamp(0., 1.));
        let expected = spektrafilm_core::output_color::to_linear_srgb(clipped, space)
            .map(|x| x * 2f64.powf(0.7));
        for (a, b) in actual.into_iter().zip(expected) {
            assert!((a as f64 - b).abs() < 2e-6, "{space}: {a} {b}");
        }
        let export =
            TailPlan::new(&descriptor(), 1, 1, TailOptions::from_params(&params, 0.7)).unwrap();
        let ordinary = image_mirror(&export, &[input], None, &mut || false).unwrap()[0];
        if matches!(space, "Display P3" | "Adobe RGB (1998)") {
            assert_ne!(actual, ordinary);
        } else {
            assert_eq!(actual, ordinary);
        }
    }
}
