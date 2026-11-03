use super::*;
use spektrafilm_math::pchip3d::prepare_pchip_3d;
fn lut() -> PreparedPchip3d {
    let n = 4;
    let mut v = Vec::new();
    for i in 0..n {
        for j in 0..n {
            for k in 0..n {
                v.extend([i as f64 / 3., j as f64 / 3., k as f64 / 3.]);
            }
        }
    }
    prepare_pchip_3d(v, n)
}
pub(super) fn temp() -> PathBuf {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
    let p = std::env::temp_dir().join(format!(
        "finish-tests-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    ));
    fs::create_dir(&p).unwrap();
    p
}
pub(super) fn fixture(root: &Path, w: u32, h: u32) -> ([PathBuf; 3], PreparedPchip3d) {
    let src = std::array::from_fn(|c| {
        let p = root.join(format!("original-{c}"));
        let mut f = File::create(&p).unwrap();
        for i in 0..w * h {
            f.write_all(&((i % 11) as f64 / 10. + c as f64 * 0.01).to_le_bytes())
                .unwrap()
        }
        p
    });
    (src, lut())
}
#[test]
fn shader_validates() {
    let m = naga::front::wgsl::parse_str(SHADER).unwrap();
    naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::empty(),
    )
    .validate(&m)
    .unwrap();
}
#[test]
fn f32_pchip_preserves_reference_tensor_and_clamp() {
    let p = lut();
    let s = spec(&p, [0.; 3], [1.; 3], 0).unwrap();
    let flat: Vec<_> = arrays(&p)
        .into_iter()
        .flatten()
        .map(|&v| v as f32)
        .collect();
    for x in [-10., 0., 0.1, 0.333333, 0.7, 1., 10.] {
        for y in [-2., 0.25, 0.9, 4.] {
            let input = [x, y, 0.54];
            let a = eval64(&p, &s, input);
            let b = eval32(&flat, &s, input.map(|v| v as f32));
            for c in 0..3 {
                assert!((a[c] - b[c] as f64).abs() < 2e-6);
                assert!((0.0..=1.0).contains(&b[c]));
            }
        }
    }
}
#[test]
fn direct_stage_complete_finite_atomic_and_collision_safe() {
    let root = temp();
    let (src, lut) = fixture(&root, 17, 3);
    let d = GpuFinishDescriptor {
        input: GpuFinishInput::FilmDensity,
        printing: None,
        scanner: &lut,
        scanner_min: [0.; 3],
        scanner_max: [1.; 3],
    };
    let dir = root.join("gpu-stage-7");
    let p = prepare_stage([&src[0], &src[1], &src[2]], &dir, 7, 17, 3, &d, || false).unwrap();
    assert_eq!(p.disk_bytes, 48 * 51 + 8 * p.table_scalars + 65536);
    if let Some(root) = std::env::var_os("FILM_GPU_CONTRACT_RESULTS") {
        let root = PathBuf::from(root);
        fs::create_dir_all(&root).unwrap();
        let envelope = serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuPrintScan","plan":plan_stage(&dir,7).unwrap()});
        fs::write(
            root.join("scanner-plan.json"),
            serde_json::to_vec_pretty(&envelope).unwrap(),
        )
        .unwrap();
    }

    let r = run_stage_cpu_mirror_for_test(&dir, 7, &p.manifest_sha256, || false).unwrap();
    assert_eq!(r["gpuUsed"], false);
    assert_eq!(r["sampleCount"], 24);
    let out = validate_output(&dir, 7, &p.manifest_sha256, || false).unwrap();
    assert_eq!(fs::metadata(out).unwrap().len(), 51 * 24);
    assert!(prepare_stage([&src[0], &src[1], &src[2]], &dir, 7, 17, 3, &d, || false).is_err());
    assert!(dir.join("candidate/complete.json").exists());
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn missing_third_source_cancellation_and_tampered_output_fail_closed() {
    let root = temp();
    let (src, lut) = fixture(&root, 3, 1);
    let d = GpuFinishDescriptor {
        input: GpuFinishInput::FilmDensity,
        printing: None,
        scanner: &lut,
        scanner_min: [0.; 3],
        scanner_max: [1.; 3],
    };
    let dir = root.join("gpu-stage-8");
    assert!(
        prepare_stage(
            [&src[0], &src[1], &root.join("missing")],
            &dir,
            8,
            3,
            1,
            &d,
            || false
        )
        .is_err()
    );
    assert!(!dir.exists());
    assert_eq!(
        prepare_stage([&src[0], &src[1], &src[2]], &dir, 8, 3, 1, &d, || true).unwrap_err(),
        "CANCELLED"
    );
    let p = prepare_stage([&src[0], &src[1], &src[2]], &dir, 8, 3, 1, &d, || false).unwrap();
    run_stage_cpu_mirror_for_test(&dir, 8, &p.manifest_sha256, || false).unwrap();
    fs::write(dir.join("candidate/candidate.f64le"), vec![0; 72]).unwrap();
    assert!(validate_output(&dir, 8, &p.manifest_sha256, || false).is_err());
    assert!(src.iter().all(|p| p.exists()));
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn print_modes_keep_original_curves_exposure_and_bounds() {
    let root = temp();
    let (src, lut) = fixture(&root, 5, 2);
    let axis = [-10., -1., 0., 1., 10.];
    let curves = [[0.; 3], [0.2; 3], [0.5; 3], [0.7; 3], [1.; 3]];
    for (token, input) in [
        (10, GpuFinishInput::FilmDensity),
        (11, GpuFinishInput::PrintRaw),
        (12, GpuFinishInput::PrintExposure),
    ] {
        let printing = spektrafilm_core::pipeline::GpuPrintingDescriptor {
            lut: &lut,
            data_min: [0.; 3],
            data_max: [1.; 3],
            print_exposure: 0.75,
            bw: 1.2,
            log_exposure: &axis,
            curves: &curves,
            gamma: 1.1,
            external_diffusion_required: false,
        };
        let d = GpuFinishDescriptor {
            input,
            printing: Some(printing),
            scanner: &lut,
            scanner_min: [0.; 3],
            scanner_max: [1.; 3],
        };
        let dir = root.join(format!("gpu-stage-{token}"));
        let p =
            prepare_stage([&src[0], &src[1], &src[2]], &dir, token, 5, 2, &d, || false).unwrap();
        let result =
            run_stage_cpu_mirror_for_test(&dir, token, &p.manifest_sha256, || false).unwrap();
        assert_eq!(result["sampleCount"], 24);
        let output = validate_output(&dir, token, &p.manifest_sha256, || false).unwrap();
        if input == GpuFinishInput::PrintExposure {
            if let Some(results)=std::env::var_os("FILM_GPU_CONTRACT_RESULTS"){let results=PathBuf::from(results);fs::create_dir_all(&results).unwrap();assert_eq!(p,plan_stage(&dir,token).unwrap());fs::write(results.join("print-exposure-plan.json"),serde_json::to_vec_pretty(&serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuPrintExposureStage","plan":p})).unwrap()).unwrap();}
            let data = fs::read(output).unwrap();
            for (index, bytes) in data.chunks_exact(8).enumerate() {
                let pixel=index/3; let channel=index%3;
                let v=(pixel%11) as f64/10. + channel as f64*0.01;
                let expected=10f64.powf(v.clamp(0.,1.))*0.75*1.2;
                let actual=f64::from_le_bytes(bytes.try_into().unwrap());
                assert!((actual-expected).abs()<2e-5, "print exposure must not develop paper or scan: {actual} {expected}");
            }
        }
    }
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn budget_checked_before_gpu_and_candidate_creation() {
    let root = temp();
    let (src, lut) = fixture(&root, 1, 1);
    let d = GpuFinishDescriptor {
        input: GpuFinishInput::FilmDensity,
        printing: None,
        scanner: &lut,
        scanner_min: [0.; 3],
        scanner_max: [1.; 3],
    };
    let dir = root.join("gpu-stage-12");
    let p = prepare_stage([&src[0], &src[1], &src[2]], &dir, 12, 1, 1, &d, || false).unwrap();
    assert_eq!(
        run_inner(&dir, 12, 0, &p.manifest_sha256, || false, true).unwrap_err(),
        "INSUFFICIENT_GPU_FINISH_MEMORY"
    );
    assert!(!dir.join("candidate").exists());
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn intermediate_f32_overflow_cannot_hide_behind_later_clamp() {
    let mut p = lut();
    for x in &mut p.lut {
        *x = 50.;
    }
    p = spektrafilm_math::pchip3d::prepare_pchip_3d(p.lut, p.size);
    let scanner = lut();
    let axis = [-10., 10.];
    let curves = [[0.; 3], [1.; 3]];
    let printing = spektrafilm_core::pipeline::GpuPrintingDescriptor {
        lut: &p,
        data_min: [0.; 3],
        data_max: [1.; 3],
        print_exposure: -1.,
        bw: 1.,
        log_exposure: &axis,
        curves: &curves,
        gamma: 1.,
        external_diffusion_required: false,
    };
    let d = GpuFinishDescriptor {
        input: GpuFinishInput::FilmDensity,
        printing: Some(printing),
        scanner: &scanner,
        scanner_min: [0.; 3],
        scanner_max: [1.; 3],
    };
    let m = descriptor_manifest(&d, 1, 1, 1).unwrap();
    let tables = Tables {
        printing: Some(p),
        scanner,
        axis: axis.to_vec(),
        curves: curves.to_vec(),
    };
    assert!(tables.reference(&m, [0.5; 3]).iter().all(|v| v.is_finite()));
    assert!(
        mirror(&tables.flat32(), &m, [0.5; 3])
            .iter()
            .all(|v| v.is_nan())
    );
    let p = lut();
    let s = spec(&p, [-1.; 3], [1.; 3], 0).unwrap();
    let flat: Vec<_> = arrays(&p)
        .into_iter()
        .flatten()
        .map(|&v| v as f32)
        .collect();
    assert!(eval32(&flat, &s, [f32::MAX; 3]).iter().all(|v| v.is_nan()));
}
