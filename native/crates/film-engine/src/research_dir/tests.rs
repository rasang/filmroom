use super::*;
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
static NEXT: AtomicU64 = AtomicU64::new(1);
struct Root(PathBuf);
impl Root {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "film-dir-test-{}-{}",
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
fn fixture(w: u32, h: u32, positive: bool) -> DirPlan {
    let axis = vec![-3., -1.25, -0.5, 0.2, 1.7, 3.];
    let values = vec![
        [0., 0.1, 0.2],
        [0.4, 0.7, 0.5],
        [0.9, 0.8, 0.6],
        [1.1, 1.3, 1.4],
        [1.8, 1.6, 1.5],
        [2.2, 2.1, 2.],
    ];
    DirPlan {
        width: w,
        height: h,
        dir_active: true,
        positive,
        core_sigma: 3.5,
        tail_decay: 2.1,
        tail_weight: 0.3,
        matrix: [[0.09, 0.03, 0.02], [0.07, 0.06, 0.01], [0.04, 0.05, 0.08]],
        density_max: [2.2, 2.1, 2.],
        initial: Curve::new(axis.clone(), values.clone(), 0.8).unwrap(),
        before_dir: Curve::new(axis, values, 1.2).unwrap(),
    }
}
fn logs(w: u32, h: u32) -> [Vec<f64>; 3] {
    std::array::from_fn(|ch| {
        (0..w * h)
            .map(|i| ((i * 17 + (i / w) * 31 + ch as u32 * 23) % 101) as f64 / 13. - 3.8)
            .collect()
    })
}
fn write_inputs(root: &Root, values: &[Vec<f64>; 3]) -> [PathBuf; 3] {
    let paths = std::array::from_fn(|ch| root.0.join(format!("source-{ch}")));
    for ch in 0..3 {
        let mut f = File::create(&paths[ch]).unwrap();
        for x in &values[ch] {
            f.write_all(&x.to_le_bytes()).unwrap();
        }
    }
    paths
}
#[test]
fn nonuniform_curve_knots_clamp_and_f32_collapse_guard() {
    let c = fixture(11, 7, false).initial;
    for ch in 0..3 {
        assert_eq!(c.at_f64(ch, -100.), c.values[0][ch]);
        assert_eq!(c.at_f64(ch, 100.), c.values.last().unwrap()[ch]);
        for (i, &x) in c.axis.iter().enumerate() {
            assert_eq!(c.at_f64(ch, x), c.values[i][ch]);
        }
    }
    assert!(Curve::new(vec![1., 1. + 1e-10], vec![[0.; 3], [1.; 3]], 1.).is_err());
}
#[test]
#[cfg(feature = "precision-f64")]
fn original_density_interpolation_oracle_bit_parity_gamma() {
    use spektrafilm_math::image::ImageBuf;
    use spektrafilm_model::density_curves::interpolate_exposure_to_density_f64;
    let axes = vec![-3., -1.3, -0.2, 0.7, 2.1, 3.];
    let values = fixture(7, 1, false).initial.values;
    for gamma in [0.7, 1., 1.3] {
        let c = Curve::new(axes.clone(), values.clone(), gamma).unwrap();
        let v = vec![-7., -2., -0.7, 0., 0.9, 2.5, 9.];
        let input = ImageBuf::from_data(
            7,
            1,
            v.iter().flat_map(|&x| [x, x + 0.01, x - 0.02]).collect(),
        );
        let expected = interpolate_exposure_to_density_f64(&input, &values, &axes, gamma);
        for (i, px) in input.data.chunks_exact(3).enumerate() {
            for ch in 0..3 {
                assert_eq!(
                    c.at_f64(ch, px[ch]).to_bits(),
                    expected.data[i * 3 + ch].to_bits()
                );
            }
        }
    }
}
#[test]
fn matrix_donor_receiver_orientation_and_positive_silver() {
    for positive in [false, true] {
        let p = fixture(3, 2, positive);
        let input = [0.1, 0.2, 0.3];
        let mut d = std::array::from_fn::<_, 3, _>(|ch| p.initial.at_f64(ch, input[ch]));
        if positive {
            for ch in 0..3 {
                d[ch] = p.density_max[ch] - d[ch];
            }
        }
        for ch in 0..3 {
            assert_eq!(
                p.initial_f64(ch, input),
                d[0] * p.matrix[0][ch] + d[1] * p.matrix[1][ch] + d[2] * p.matrix[2][ch]
            );
        }
    }
}
#[test]
fn auxiliary_layout_and_chunk_bounds() {
    let p = fixture(257, 257, false);
    p.validate().unwrap();
    let tables = 5 * (p.initial.axis.len() + p.before_dir.axis.len());
    assert!(2 * 40000 * 4 < TABLE_BASE * 4);
    assert!(TABLE_BASE as usize + tables < CHUNK_BASE as usize);
    assert!(CHUNK_BASE as u64 * 4 + CHUNK_PIXELS as u64 * 12 <= spatial::TRANSFER_BYTES);
    assert_eq!(p.chunk_pixels(), 65536);
    assert_eq!(p.chunk_count(), 2);
    assert_eq!(sample_indices(1), vec![0]);
    assert_eq!(sample_indices(7).len(), 7);
    assert_eq!(sample_indices(65536).last(), Some(&65535));
    assert_eq!(p.pass_count(), 12);
}
#[test]
fn inactive_dir_computes_density_across_multiple_chunks() {
    let mut p = fixture(257, 257, false);
    p.dir_active = false;
    let input = logs(p.width, p.height);
    let output = cpu_mirror(&p, &input, &mut || false).unwrap();
    assert_eq!(p.pass_count(), 0);
    for ch in 0..3 {
        for i in [0, 65535, 65536, input[ch].len() - 1] {
            assert_eq!(
                output[ch][i],
                p.initial.at_f32(ch, input[ch][i] as f32) as f64
            );
        }
        assert_ne!(output[ch], input[ch]);
    }
}
#[test]
fn zero_diffusion_still_subtracts_and_redevelops() {
    let mut p = fixture(19, 7, true);
    p.core_sigma = 0.;
    let input = logs(p.width, p.height);
    let output = cpu_mirror(&p, &input, &mut || false).unwrap();
    for ch in 0..3 {
        for i in 0..input[0].len() {
            let correction = p.initial_f32(ch, std::array::from_fn(|j| input[j][i] as f32));
            let expected = p.before_dir.at_f32(ch, input[ch][i] as f32 - correction);
            assert_eq!(output[ch][i], expected as f64);
        }
    }
    assert_eq!(p.pass_count(), 0);
}
#[cfg(feature = "precision-f64")]
fn f64_reference(p: &DirPlan, input: &[Vec<f64>; 3]) -> [Vec<f64>; 3] {
    use spektrafilm_math::gaussian::gaussian_blur_channel;
    std::array::from_fn(|ch| {
        let n = input[0].len();
        let mut corr: Vec<f64> = (0..n)
            .map(|i| p.initial_f64(ch, std::array::from_fn(|j| input[j][i])))
            .collect();
        if p.dir_active && p.core_sigma > 0. {
            let core = gaussian_blur_channel(&corr, p.width, p.height, p.core_sigma);
            let mut tail = vec![0.; n];
            for (amp, ratio) in FIT {
                let b = gaussian_blur_channel(
                    &corr,
                    p.width,
                    p.height,
                    (ratio * p.tail_decay as f64) as f32,
                );
                for i in 0..n {
                    tail[i] += amp * b[i];
                }
            }
            for i in 0..n {
                corr[i] = (1. - p.tail_weight) * core[i] + p.tail_weight * tail[i];
            }
        }
        if p.dir_active {
            for i in 0..n {
                corr[i] = p.before_dir.at_f64(ch, input[ch][i] - corr[i]);
            }
        }
        corr
    })
}
#[test]
#[cfg(feature = "precision-f64")]
fn full_original_f64_graph_vs_f32_asymmetric_edges() {
    for (w, h) in [(1, 1), (1, 37), (71, 1), (71, 37)] {
        for positive in [false, true] {
            let p = fixture(w, h, positive);
            let input = logs(w, h);
            let reference = f64_reference(&p, &input);
            let actual = cpu_mirror(&p, &input, &mut || false).unwrap();
            for ch in 0..3 {
                for (&a, &b) in reference[ch].iter().zip(&actual[ch]) {
                    assert!(
                        (a - b).abs() < 1e-3 * a.abs().max(1.),
                        "{w} {h} ch{ch}: {a} {b}"
                    );
                }
            }
        }
    }
}
#[test]
fn original_profile_calibration_provenance() {
    use spektrafilm_core::profile::load_profile_by_name;
    let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    for stock in ["kodak_portra_400", "kodak_ektachrome_100"] {
        let mut params = RuntimeParams::default();
        params.film_render.density_curve_gamma = 1.3;
        let pipe = Pipeline::new(
            load_profile_by_name(&data, stock).unwrap(),
            load_profile_by_name(&data, "kodak_portra_endura").unwrap(),
            params,
        );
        let p = DirPlan::new(&pipe, 20., 17, 11).unwrap();
        let d = &pipe.params.film_render.dir_couplers;
        let norm = spektrafilm_model::density_curves::normalize_density_curves(
            &pipe.film.density_curves_f32(),
        );
        let mut matrix = spektrafilm_model::couplers::compute_dir_couplers_matrix(
            d.gamma_samelayer_rgb,
            d.gamma_interlayer_r_to_gb,
            d.gamma_interlayer_g_to_rb,
            d.gamma_interlayer_b_to_rg,
            d.inhibition_samelayer,
            d.inhibition_interlayer,
        );
        for row in &mut matrix {
            for x in row {
                *x *= d.amount;
            }
        }
        let original = spektrafilm_model::couplers::compute_curves_before_dir(
            &norm,
            &pipe.film.log_exposure_f32(),
            &matrix.map(|r| r.map(|x| x as f32)),
            pipe.film.is_positive(),
        );
        assert_eq!(p.matrix, matrix);
        assert_eq!(
            p.before_dir.values,
            original
                .iter()
                .map(|r| r.map(|x| x as f64))
                .collect::<Vec<_>>()
        );
        assert_eq!(
            p.density_max,
            spektrafilm_model::density_curves::max_density(&norm).map(|x| x as f64)
        );
    }
}
#[test]
fn stage_candidate_roundtrip_hash_and_cancellation() {
    let root = Root::new();
    let p = fixture(23, 17, true);
    let values = logs(p.width, p.height);
    let inputs = write_inputs(&root, &values);
    let before: Vec<_> = inputs.iter().map(|p| fs::read(p).unwrap()).collect();
    let dir = root.0.join("gpu-stage-9");
    let stage = full_stage::prepare_stage(
        inputs.each_ref().map(|x| x.as_path()),
        &dir,
        9,
        p.width,
        p.height,
        &p,
        || false,
    )
    .unwrap();
    assert_eq!(full_stage::plan_stage(&dir, 9).unwrap(), stage);
    let mut checks = 0;
    assert_eq!(
        full_stage::run_stage_cpu_mirror_for_test(&dir, 9, &stage.manifest_sha256, || {
            checks += 1;
            checks > 5
        })
        .unwrap_err(),
        "CANCELLED"
    );
    assert!(!dir.join("candidate").exists());
    full_stage::run_stage_cpu_mirror_for_test(&dir, 9, &stage.manifest_sha256, || false).unwrap();
    let outputs = full_stage::validate_outputs(&dir, 9, &stage.manifest_sha256, || false).unwrap();
    for ch in 0..3 {
        assert_eq!(fs::read(&inputs[ch]).unwrap(), before[ch]);
    }
    OpenOptions::new()
        .write(true)
        .open(&outputs[2])
        .unwrap()
        .write_all(&f64::NAN.to_le_bytes())
        .unwrap();
    assert!(full_stage::validate_outputs(&dir, 9, &stage.manifest_sha256, || false).is_err());
}
#[test]
fn bad_third_input_and_large_sigma_fail_closed() {
    let root = Root::new();
    let p = fixture(13, 17, false);
    let mut values = logs(p.width, p.height);
    values[2][4] = f64::NAN;
    let inputs = write_inputs(&root, &values);
    let dir = root.0.join("gpu-stage-4");
    let stage = full_stage::prepare_stage(
        inputs.each_ref().map(|p| p.as_path()),
        &dir,
        4,
        p.width,
        p.height,
        &p,
        || false,
    )
    .unwrap();
    assert_eq!(
        full_stage::run_stage_cpu_mirror_for_test(&dir, 4, &stage.manifest_sha256, || false)
            .unwrap_err(),
        "NONFINITE_DIR_INPUT"
    );
    assert!(!dir.join("candidate").exists());
    let mut p = fixture(40000, 1, false);
    p.core_sigma = 128.;
    assert!(p.numerical_preflight(&mut || false).is_err());
}
#[test]
fn emit_dir_plan_contract_when_requested() {
    let Some(destination) = std::env::var_os("FILM_GPU_CONTRACT_RESULTS") else {
        return;
    };
    let root = Root::new();
    let p = fixture(17, 11, false);
    let values = logs(p.width, p.height);
    let inputs = write_inputs(&root, &values);
    let stage = full_stage::prepare_stage(
        inputs.each_ref().map(|p| p.as_path()),
        &root.0.join("gpu-stage-42"),
        42,
        p.width,
        p.height,
        &p,
        || false,
    )
    .unwrap();
    assert_eq!(stage.driver_allowance_bytes,104*crate::research_spatial::MIB);
    assert_eq!(stage.combined_peak_bytes,stage.device_bytes+stage.host_bytes+stage.driver_allowance_bytes+stage.helper_allowance_bytes);
    let envelope = serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmDirStage","plan":stage});
    let destination = PathBuf::from(destination);
    fs::create_dir_all(&destination).unwrap();
    fs::write(
        destination.join("dir-plan.json"),
        serde_json::to_vec_pretty(&envelope).unwrap(),
    )
    .unwrap();
}

#[test]
fn calibration_packet_preserves_all_f64_bits() {
    let mut p = fixture(17, 11, false);
    for gamma in [0.7, 1., 1.3] {
        p.initial = Curve::new(
            vec![-3., -1.3, -0.2, 0.7, 2.1, 3.],
            p.initial.values.clone(),
            gamma,
        )
        .unwrap();
        let encoded = serde_json::to_vec(&p).unwrap();
        let decoded: DirPlan = serde_json::from_slice(&encoded).unwrap();
        decoded.validate().unwrap();
        assert_eq!(decoded.initial, p.initial);
        assert_eq!(decoded.before_dir, p.before_dir);
        assert_eq!(decoded, p);
    }
}

#[test]
fn nonfinite_correction_cannot_hide_in_curve_endpoint_clamp() {
    let p = fixture(3, 2, false);
    for x in [f32::INFINITY, f32::NEG_INFINITY, f32::NAN] {
        assert!(p.before_dir.at_f32(0, x).is_nan());
        assert!(p.before_dir.at_f32(0, 0.5 - x).is_nan());
    }
    for x in [f64::INFINITY, f64::NEG_INFINITY, f64::NAN] {
        assert!(p.before_dir.at_f64(0, x).is_nan());
    }
}
