use super::*;
const ID: [[f64; 3]; 3] = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
fn tail_descriptor() -> GpuScanTailDescriptor<'static> {
    GpuScanTailDescriptor {
        adapt: ID,
        xyz_to_rgb: ID,
        xyz_remap: None,
        glare_rgb_offset: [1.; 3],
        gamut: None,
        rgb_identity: ID,
        output_cctf_encoding: false,
    }
}
fn options() -> TailOptions {
    TailOptions {
        glare: Some(tail::GlarePlan {
            percent: 0.3,
            roughness: 0.7,
            blur: 1.,
        }),
        lens_sigma: 1.,
        sharp_radius: 2.,
        sharp_amount: 0.3,
        brightness_ev: 0.5,
        finalize_cctf: false,
        preview_output_space: Some("Display P3".into()),
    }
}
#[test]
fn fused_shader_validates_with_eight_storage_buffers() {
    let source = fused_shader(&tail::shader()).unwrap();
    let module = naga::front::wgsl::parse_str(&source).unwrap();
    naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::empty(),
    )
    .validate(&module)
    .unwrap();
    assert_eq!(
        module
            .global_variables
            .iter()
            .filter(|(_, v)| matches!(v.space, naga::AddressSpace::Storage { .. }))
            .count(),
        8
    );
    assert!(source.contains("if(op==40u){finish_main(i);return;}"));
    assert!(fused_shader("drift").is_err());
}
#[test]
fn fused_packet_mirror_preserves_complete_tail_and_rejects_tampering() {
    let root = super::super::tests::temp().canonicalize().unwrap();
    let (src, lut) = super::super::tests::fixture(&root, 17, 3);
    let d = GpuFinishDescriptor {
        input: GpuFinishInput::FilmDensity,
        printing: None,
        scanner: &lut,
        scanner_min: [0.; 3],
        scanner_max: [1.; 3],
    };
    let dir = root.join("gpu-stage-37");
    let p = prepare_stage(
        [&src[0], &src[1], &src[2]],
        &dir,
        37,
        17,
        3,
        &d,
        &tail_descriptor(),
        options(),
        || false,
    )
    .unwrap();
    if let Some(root) = std::env::var_os("FILM_GPU_CONTRACT_RESULTS") {
        let root = PathBuf::from(root);
        fs::create_dir_all(&root).unwrap();
        let fixture = serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFinishTailStage","plan":plan_stage(&dir,37).unwrap()});
        fs::write(
            root.join("fusion-plan.json"),
            serde_json::to_vec_pretty(&fixture).unwrap(),
        )
        .unwrap();
    }
    assert_eq!(p.disk_bytes, 36 * 51 + 8 * p.table_scalars + 3 * MIB);
    assert_eq!(
        p.combined_peak_bytes,
        p.device_bytes + p.host_bytes + p.driver_allowance_bytes + p.helper_allowance_bytes
    );
    assert_eq!(plan_stage(&dir, 37).unwrap(), p);
    assert!(run_inner(&dir, 37, 0, &p.manifest_sha256, || false, true).is_err());
    assert!(!dir.join("candidate").exists());
    let r = run_inner(
        &dir,
        37,
        p.combined_peak_bytes,
        &p.manifest_sha256,
        || false,
        true,
    )
    .unwrap();
    assert_eq!(r["gpuUsed"], false);
    let out = validate_output(&dir, 37, &p.manifest_sha256, || false).unwrap();
    assert_eq!(fs::metadata(&out).unwrap().len(), 51 * 12);
    assert!(!dir.join("source.logxyz.f64le").exists());
    let mut bytes = fs::read(&out).unwrap();
    bytes[0..4].copy_from_slice(&f32::NAN.to_le_bytes());
    fs::write(&out, bytes).unwrap();
    assert!(validate_output(&dir, 37, &p.manifest_sha256, || false).is_err());
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn prefix_remainder_witness_and_cancellation_are_closed() {
    let root = super::super::tests::temp().canonicalize().unwrap();
    let (src, lut) = super::super::tests::fixture(&root, 7, 1);
    let d = GpuFinishDescriptor {
        input: GpuFinishInput::FilmDensity,
        printing: None,
        scanner: &lut,
        scanner_min: [0.; 3],
        scanner_max: [1.; 3],
    };
    let dir = root.join("gpu-stage-38");
    assert!(
        prepare_stage(
            [&src[0], &src[1], &src[2]],
            &dir,
            38,
            7,
            1,
            &d,
            &tail_descriptor(),
            options(),
            || true
        )
        .is_err()
    );
    assert!(!dir.exists());
    prepare_stage(
        [&src[0], &src[1], &src[2]],
        &dir,
        38,
        7,
        1,
        &d,
        &tail_descriptor(),
        options(),
        || false,
    )
    .unwrap();
    let mut prefix = FinishPrefix::load(&prefix_dir(&dir, 38), 38, &mut || false).unwrap();
    assert!(prefix.complete().is_err());
    let values = prefix.read_chunk(7).unwrap();
    assert_eq!(values.len(), 21);
    assert!(prefix.read_chunk(1).is_err());
    prefix.complete().unwrap();
    let expected = super::super::mirror(
        &prefix.storage[64..],
        &prefix.manifest,
        [values[0], values[1], values[2]],
    );
    prefix.witness(0, expected).unwrap();
    assert!(prefix.witness(0, [f32::NAN; 3]).is_err());
    assert!(prefix.witness(0, [100.; 3]).is_err());
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn both_print_ingress_modes_keep_original_reference_and_exposure_only_rejects() {
    let root = super::super::tests::temp().canonicalize().unwrap();
    let (src, lut) = super::super::tests::fixture(&root, 5, 2);
    let axis = [-10., -1., 0., 1., 10.];
    let curves = [[0.; 3], [0.2; 3], [0.5; 3], [0.7; 3], [1.; 3]];
    for (token, input) in [
        (41, GpuFinishInput::FilmDensity),
        (42, GpuFinishInput::PrintRaw),
        (43, GpuFinishInput::PrintExposure),
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
        let plan = prepare_stage(
            [&src[0], &src[1], &src[2]],
            &dir,
            token,
            5,
            2,
            &d,
            &tail_descriptor(),
            options(),
            || false,
        );
        if token == 43 {
            assert!(plan.is_err());
            assert!(!dir.exists());
            continue;
        }
        let p = plan.unwrap();
        assert_eq!(
            p.mode,
            if token == 41 {
                "printFromDensity"
            } else {
                "printFromRaw"
            }
        );
        let mut prefix =
            FinishPrefix::load(&prefix_dir(&dir, token), token, &mut || false).unwrap();
        let v = prefix.read_chunk(10).unwrap();
        for i in 0..10 {
            let actual = super::super::mirror(
                &prefix.storage[64..],
                &prefix.manifest,
                [v[3 * i], v[3 * i + 1], v[3 * i + 2]],
            );
            prefix.witness(i, actual).unwrap();
        }
        run_inner(
            &dir,
            token,
            p.combined_peak_bytes,
            &p.manifest_sha256,
            || false,
            true,
        )
        .unwrap();
        validate_output(&dir, token, &p.manifest_sha256, || false).unwrap();
    }
    fs::remove_dir_all(root).unwrap();
}
/// Independent original CPU pipeline, not tail::image_mirror, supplies expected
/// pixels. Dimensions cross the 32768-pixel ingress boundary and exercise IIR.
#[test]
fn real_three_mode_full_images_match_original_cpu_across_chunk_boundary() {
    use spektrafilm_core::{
        params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name,
    };
    use spektrafilm_gpu::cpu_backend::CpuBackend;
    use spektrafilm_math::{gaussian, image::ImageBuf, precision::from_f64};
    let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let root = super::super::tests::temp().canonicalize().unwrap();
    let (w, h) = (257u32, 129u32);
    let n = (w * h) as usize;
    for (mode, token) in [
        ("directFilm", 51),
        ("printFromDensity", 52),
        ("printFromRaw", 53),
    ] {
        let mut params = RuntimeParams::default();
        params.settings.use_enlarger_lut = true;
        params.settings.use_scanner_lut = true;
        params.settings.lut_resolution = 5;
        params.io.scan_film = mode == "directFilm";
        params.io.output_color_space = "Display P3".into();
        params.io.output_cctf_encoding = false;
        params.io.output_gamut_compress.algorithm = "aces_rgc".into();
        params.scanner.lens_blur = 4.;
        params.scanner.unsharp_mask = [1.2, 0.3];
        params.enlarger.diffusion_filter.active = false;
        for g in [
            &mut params.film_render.glare,
            &mut params.print_render.glare,
        ] {
            g.active = true;
            g.percent = 0.3;
            g.roughness = 0.7;
            g.blur = 4.;
        }
        let film = load_profile_by_name(
            &data,
            if params.io.scan_film {
                "kodak_ektachrome_100"
            } else {
                "kodak_portra_400"
            },
        )
        .unwrap();
        let paper = load_profile_by_name(&data, "kodak_portra_endura").unwrap();
        let pipeline = Pipeline::new_with_spectral(film, paper, params.clone(), &data).unwrap();
        let prepared = pipeline
            .prepare_streamed_finish_spatial(&CpuBackend)
            .unwrap();
        let input = if mode == "printFromRaw" {
            GpuFinishInput::PrintRaw
        } else {
            GpuFinishInput::FilmDensity
        };
        let desc = prepared.gpu_finish_descriptor(input).unwrap();
        let input_values = (0..n)
            .flat_map(|i| {
                (0..3).map(move |ch| from_f64(0.05 + ((i * 7 + ch * 11) % 101) as f64 / 100.))
            })
            .collect::<Vec<_>>();
        let image = ImageBuf::from_data(w, h, input_values);
        let sources: [PathBuf; 3] = std::array::from_fn(|ch| {
            let path = root.join(format!("original-{token}-{ch}"));
            let mut f = File::create(&path).unwrap();
            for x in image.data.chunks_exact(3) {
                f.write_all(&(x[ch] as f64).to_le_bytes()).unwrap();
            }
            path
        });
        let directory = root.join(format!("gpu-stage-{token}"));
        let plan = prepare_stage(
            [&sources[0], &sources[1], &sources[2]],
            &directory,
            token,
            w,
            h,
            &desc,
            &prepared.gpu_scan_tail_descriptor(),
            TailOptions::for_preview(&params, 0.5),
            || false,
        )
        .unwrap();
        assert_eq!(plan.mode, mode);
        run_inner(
            &directory,
            token,
            plan.combined_peak_bytes,
            &plan.manifest_sha256,
            || false,
            true,
        )
        .unwrap();
        let output = validate_output(&directory, token, &plan.manifest_sha256, || false).unwrap();
        let bytes = fs::read(output).unwrap();
        let actual = bytes
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes(b.try_into().unwrap()) as f64)
            .collect::<Vec<_>>();
        let glare = spektrafilm_model::glare::compute_random_glare_amount(w, h, 0.3, 0.7, 4., 42);
        let rgb = if mode == "printFromRaw" {
            let density = prepared
                .develop_print_raw_chunk(&image, &CpuBackend)
                .unwrap();
            prepared
                .scan_print_density_chunk_unclipped_with_glare(&density, &glare)
                .unwrap()
        } else {
            prepared
                .process_density_chunk_unclipped_with_glare(&image, &glare, &CpuBackend)
                .unwrap()
        };
        let mut rgb = gaussian::gaussian_blur(&rgb, 4.);
        let blurred = gaussian::gaussian_blur(&rgb, 1.2);
        for (v, b) in rgb.data.iter_mut().zip(blurred.data) {
            *v = (*v + from_f64(0.3) * (*v - b)).clamp(from_f64(0.), from_f64(1.));
        }
        let expected = rgb
            .data
            .chunks_exact(3)
            .flat_map(|x| {
                spektrafilm_core::output_color::to_linear_srgb(
                    [x[0] as f64, x[1] as f64, x[2] as f64],
                    "Display P3",
                )
                .map(|v| v * 2f64.powf(0.5))
            })
            .collect::<Vec<_>>();
        let mut max = 0f64;
        let mut square = 0.;
        for (a, b) in actual.iter().zip(&expected) {
            let e = (a - b).abs();
            assert!(a.is_finite() && b.is_finite());
            max = max.max(e);
            square += e * e;
        }
        let rms = (square / actual.len() as f64).sqrt();
        eprintln!(
            "FUSED_CPU_REFERENCE mode={mode} pixels={n} max_abs={max:.9} rms={rms:.9} original_glare_iir_lens_fir_sharpen_preview_brightness=true"
        );
        assert!(max < 2e-3 && rms < 5e-4, "{mode}: max={max} rms={rms}");
    }
    fs::remove_dir_all(root).unwrap();
}
#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "Requires a Vulkan device; actual resident prefix and unsampled sticky failure"]
fn actual_fused_unsampled_prefix_domain_failure_never_leaves_output() {
    let root = super::super::tests::temp().canonicalize().unwrap();
    let (w, h) = (17u32, 13u32);
    let n = (w * h) as usize;
    let src: [PathBuf; 3] = std::array::from_fn(|ch| {
        let p = root.join(format!("source-{ch}"));
        let mut f = File::create(&p).unwrap();
        for i in 0..n {
            let x = if i == 123 { 0.99f64 } else { 0.1 };
            f.write_all(&x.to_le_bytes()).unwrap();
        }
        p
    });
    let mut values = Vec::new();
    for i in 0..4 {
        for j in 0..4 {
            for k in 0..4 {
                values.extend([i, j, k].map(|v| -3. + 23. * v as f64 / 3.));
            }
        }
    }
    let lut = spektrafilm_math::pchip3d::prepare_pchip_3d(values, 4);
    let desc = GpuFinishDescriptor {
        input: GpuFinishInput::FilmDensity,
        printing: None,
        scanner: &lut,
        scanner_min: [0.; 3],
        scanner_max: [1.; 3],
    };
    let dir = root.join("gpu-stage-61");
    prepare_stage(
        [&src[0], &src[1], &src[2]],
        &dir,
        61,
        w,
        h,
        &desc,
        &tail_descriptor(),
        options(),
        || false,
    )
    .unwrap();
    let (m, _) = read_manifest(&dir, 61).unwrap();
    let mut prefix = FinishPrefix::load(&prefix_dir(&dir, 61), 61, &mut || false).unwrap();
    let output = root.join("must-not-exist.rgb");
    assert_eq!(
        tail::gpu::run_files_with_prefix_probe(&m.tail, &output, &mut || false, &mut prefix)
            .unwrap_err(),
        "GPU_TAIL_NUMERIC_DOMAIN"
    );
    assert!(!output.exists());
    assert!(!dir.join("candidate/complete.json").exists());
    fs::remove_dir_all(root).unwrap();
}
