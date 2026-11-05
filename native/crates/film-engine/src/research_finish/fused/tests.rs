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

#[test]
fn pointwise_selection_validates_plan_and_preserves_effect_routes() {
    let mut o = options();
    o.glare = None;
    o.lens_sigma = 0.;
    o.sharp_radius = 0.;
    o.sharp_amount = 0.;
    let p = TailPlan::new(&tail_descriptor(), 384, 512, o).unwrap();
    assert!(pointwise_shader(&p).unwrap().is_some());
    let mut bad = p.clone();
    bad.options.lens_sigma = f32::NAN;
    assert!(pointwise_shader(&bad).is_err());
    let mut bad = p.clone();
    bad.device_bytes += 4;
    assert!(pointwise_shader(&bad).is_err());
    let mut effect = p.clone();
    effect.options.glare = options().glare;
    let effect = TailPlan::new(&tail_descriptor(), 384, 512, effect.options).unwrap();
    assert!(pointwise_shader(&effect).unwrap().is_none());
    let mut effect = p.clone();
    effect.options.lens_sigma = 1.;
    let effect = TailPlan::new(&tail_descriptor(), 384, 512, effect.options).unwrap();
    assert!(pointwise_shader(&effect).unwrap().is_none());
    let mut effect = p.clone();
    effect.options.sharp_radius = 2.;
    effect.options.sharp_amount = 0.3;
    let effect = TailPlan::new(&tail_descriptor(), 384, 512, effect.options).unwrap();
    assert!(pointwise_shader(&effect).unwrap().is_none());
    // A zero component disables sharpening in the original host schedule too.
    let mut effect = effect;
    effect.options.sharp_amount = 0.;
    let effect = TailPlan::new(&tail_descriptor(), 384, 512, effect.options).unwrap();
    assert!(pointwise_shader(&effect).unwrap().is_some());
    for name in ["sRGB", "Display P3", "Adobe RGB (1998)"] {
        let mut variant = p.clone();
        variant.options.preview_output_space = Some(name.into());
        assert!(pointwise_shader(&variant).unwrap().is_some());
    }
    let mut export = p;
    export.options.preview_output_space = None;
    export.options.finalize_cctf = true;
    export.encode = true;
    assert!(pointwise_shader(&export).unwrap().is_some());
}

/// Actual production Runner, not the host mirror. The original prepared CPU
/// print/scanner pipeline supplies every expected pixel; no giant general GPU
/// pipeline is compiled by this test. Includes both sides of the chunk seam.
#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "Requires Vulkan; full pointwise production output versus original CPU in six mode/routes"]
fn actual_pointwise_three_modes_preview_export_match_original_cpu_full_output() {
    use spektrafilm_core::{
        params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name,
    };
    use spektrafilm_gpu::cpu_backend::CpuBackend;
    use spektrafilm_math::{
        image::ImageBuf,
        precision::{from_f64, srgb_encode},
    };
    let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let root = super::super::tests::temp().canonicalize().unwrap();
    let (w, h) = (257u32, 129u32);
    let n = (w * h) as usize;
    assert!(n > tail::CHUNK_PIXELS);
    for (mode_index, mode) in ["directFilm", "printFromDensity", "printFromRaw"]
        .into_iter()
        .enumerate()
    {
        for preview in [true, false] {
            let token = 80 + mode_index as u64 * 2 + u64::from(preview);
            let mut params = RuntimeParams::default();
            params.settings.use_enlarger_lut = true;
            params.settings.use_scanner_lut = true;
            params.settings.lut_resolution = 5;
            params.io.scan_film = mode == "directFilm";
            params.io.output_color_space = if preview { "Display P3" } else { "sRGB" }.into();
            params.io.output_cctf_encoding = !preview;
            params.io.output_gamut_compress.algorithm = "aces_rgc".into();
            params.scanner.lens_blur = 0.;
            params.scanner.unsharp_mask = [0., 0.];
            params.enlarger.diffusion_filter.active = false;
            for g in [
                &mut params.film_render.glare,
                &mut params.print_render.glare,
            ] {
                g.active = false;
                g.percent = 0.;
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
            let tail_desc = prepared.gpu_scan_tail_descriptor();
            let image = ImageBuf::from_data(
                w,
                h,
                (0..n)
                    .flat_map(|i| {
                        (0..3)
                            .map(move |ch| from_f64(0.05 + ((i * 7 + ch * 11) % 101) as f64 / 100.))
                    })
                    .collect(),
            );
            let sources: [PathBuf; 3] = std::array::from_fn(|ch| {
                let path = root.join(format!("input-{token}-{ch}"));
                let mut file = File::create(&path).unwrap();
                for x in image.data.chunks_exact(3) {
                    file.write_all(&(x[ch] as f64).to_le_bytes()).unwrap();
                }
                path
            });
            let options = if preview {
                TailOptions::for_preview(&params, 0.5)
            } else {
                TailOptions::from_params(&params, 0.5)
            };
            let dir = root.join(format!("gpu-stage-{token}"));
            let plan = prepare_stage(
                [&sources[0], &sources[1], &sources[2]],
                &dir,
                token,
                w,
                h,
                &desc,
                &tail_desc,
                options,
                || false,
            )
            .unwrap();
            assert_eq!(plan.mode, mode);
            let (manifest, _) = read_manifest(&dir, token).unwrap();
            assert!(pointwise_shader(&manifest.tail).unwrap().is_some());
            assert_eq!(manifest.tail.encode, !preview);
            let mut prefix =
                FinishPrefix::load(&prefix_dir(&dir, token), token, &mut || false).unwrap();
            let output = root.join(format!("actual-{token}.rgb"));
            let report = tail::gpu::run_files_with_prefix_probe(
                &manifest.tail,
                &output,
                &mut || false,
                &mut prefix,
            )
            .unwrap();
            assert_eq!(report["gpuUsed"], true);
            assert_eq!(report["privateNumericFlag"], true);
            assert_eq!(report["finishPipelineCount"], 1);
            let chunks = (n as u64).div_ceil(tail::CHUNK_PIXELS as u64);
            assert_eq!(report["dispatches"], chunks * 3 + 5);
            assert_eq!(report["passCount"], 0);

            let bytes = fs::read(output).unwrap();
            assert_eq!(bytes.len(), n * 12);
            let actual: Vec<f64> = bytes
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()) as f64)
                .collect();
            let rgb = if mode == "printFromRaw" {
                let density = prepared
                    .develop_print_raw_chunk(&image, &CpuBackend)
                    .unwrap();
                prepared
                    .scan_print_density_chunk_unclipped(&density)
                    .unwrap()
            } else {
                prepared.process_density_chunk_unclipped(&image, &CpuBackend)
            };
            let expected: Vec<f64> = rgb
                .data
                .chunks_exact(3)
                .flat_map(|x| {
                    let mut v = [x[0] as f64, x[1] as f64, x[2] as f64];
                    if preview {
                        v = v.map(|x| x.clamp(0., 1.));
                        v = spektrafilm_core::output_color::to_linear_srgb(v, "Display P3");
                    } else {
                        // Exact original scanner finalization ordering: identity
                        // matrix round-trip, clip, original scalar sRGB encoding.
                        v = tail_desc
                            .rgb_identity
                            .map(|r| r[0] * v[0] + r[1] * v[1] + r[2] * v[2]);
                        v = v.map(|x| {
                            srgb_encode(from_f64(x).clamp(from_f64(0.), from_f64(1.))) as f64
                        });
                    }
                    v.map(|x| x * 2f64.powf(0.5))
                })
                .collect();
            assert_eq!(actual.len(), expected.len());
            let mut max = 0f64;
            let mut square = 0.;
            for (&a, &b) in actual.iter().zip(&expected) {
                assert!(a.is_finite() && b.is_finite());
                let e = (a - b).abs();
                max = max.max(e);
                square += e * e;
            }
            let rms = (square / actual.len() as f64).sqrt();
            eprintln!(
                "ACTUAL_POINTWISE_CPU_REFERENCE mode={mode} preview={preview} width={w} height={h} scalars={} max_abs={max:.9} rms={rms:.9} adapter={} dispatches={}",
                actual.len(),
                report["adapter"],
                report["dispatches"]
            );
            assert!(
                max < 2e-3 && rms < 5e-4,
                "{mode} preview={preview}: max={max} rms={rms}"
            );
        }
    }
    fs::remove_dir_all(root).unwrap();
}

#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "Requires a Vulkan device; actual resident prefix and unsampled sticky failure"]
fn actual_pointwise_unsampled_second_chunk_failure_never_leaves_output() {
    let root = super::super::tests::temp().canonicalize().unwrap();
    let (w, h) = (257u32, 129u32);
    let n = (w * h) as usize;
    let src: [PathBuf; 3] = std::array::from_fn(|ch| {
        let p = root.join(format!("source-{ch}"));
        let mut f = File::create(&p).unwrap();
        for i in 0..n {
            let x = if i == tail::CHUNK_PIXELS + 123 {
                0.99f64
            } else {
                0.1
            };
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
        TailOptions {
            glare: None,
            lens_sigma: 0.,
            sharp_radius: 0.,
            sharp_amount: 0.,
            brightness_ev: 0.5,
            finalize_cctf: true,
            preview_output_space: Some("sRGB".into()),
        },
        || false,
    )
    .unwrap();
    let (m, _) = read_manifest(&dir, 61).unwrap();
    assert!(pointwise_shader(&m.tail).unwrap().is_some());
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

#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "Requires Vulkan; exact guard transport parity, not mobile timing"]
fn actual_private_numeric_flag_matches_atomic_guards_and_stays_sticky() {
    use crate::research_spatial::{FilmOpticsPlan, gpu::Runner};
    use spektrafilm_core::params::RuntimeParams;
    let declarations = crate::research_spatial::SHADER
        .split_once("fn reflect_index(")
        .unwrap()
        .0;
    // Keep the actual production guards. Only the small test entry is new.
    let original = format!(
        "{declarations}{}\n{}",
        tail::SHADER_GUARD,
        r#"@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>){
 let i=gid.x;
 if(i>=p.u[1].x){return;}
 let x=probe.values[524288u+i];
 if(i==p.u[1].y){probe.values[i]=tfail();return;}
 if(p.u[0].z==61u){probe.values[i]=tdiv(x,0.0);return;}
 if(p.u[0].z==62u){probe.values[i]=tmul(x,2.0);return;}
 probe.values[i]=tbound(x);
}
"#
    );
    let private = pointwise::private_numeric_flag(&original).unwrap();
    for source in [&original, &private] {
        let module = naga::front::wgsl::parse_str(source).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap();
    }
    let finite = [0.0f32, -0.0, 1.0, -1.0, 1e14, -1e14, 1e15, -1e15];
    let over_bound = [1e30f32, -1e30, 0.0, -0.0, 1., -1., 1e15, -1e15];
    let run = |source: &str| {
        let plan = FilmOpticsPlan::new(&RuntimeParams::default(), 20., 8, 1).unwrap();
        let mut outcomes = Vec::new();
        for failing_case in [
            (finite, 63, 7),
            (over_bound, 63, u32::MAX),
            (finite, 62, u32::MAX),
            (finite, 61, u32::MAX),
        ] {
            // Fresh request-wide flag for EACH error type, then test persistence.
            let (runner, mut metrics) =
                Runner::new_with_shader_error_explicit_layout(&plan, true, source).unwrap();
            for (values, operation, early_index) in
                [(finite, 63, u32::MAX), failing_case, (finite, 63, u32::MAX)]
            {
                runner
                    .upload_aux(
                        524288 * 4,
                        bytemuck::cast_slice(&values),
                        &mut || false,
                        &mut metrics,
                    )
                    .unwrap();
                let mut params = [0u32; 40];
                params[0] = 8;
                params[1] = 1;
                params[2] = operation;
                params[4] = 8;
                params[5] = early_index;
                runner
                    .dispatch(params, &mut || false, &mut metrics)
                    .unwrap();
                let bytes = runner
                    .read_probe(8 * 4, &mut || false, &mut metrics)
                    .unwrap();
                let flag = runner.read_error_flag(&mut || false, &mut metrics).unwrap();
                outcomes.push((bytes, flag));
            }
        }
        outcomes
    };
    let expected = run(&original);
    let actual = run(&private);
    assert_eq!(
        expected, actual,
        "guard values and sticky bit must match exactly"
    );
    assert_eq!(actual.len(), 12);
    for group in actual.chunks_exact(3) {
        assert_eq!(group[0].0, bytemuck::cast_slice::<f32, u8>(&finite));
        assert_eq!(group[0].1, 0);
        assert_eq!(group[1].1, 1);
        assert_eq!(group[2].1, 1);
        assert_eq!(group[2].0, group[0].0);
    }
    assert_eq!(&actual[1].0[28..32], &0.0f32.to_le_bytes());
    assert!(
        actual[10]
            .0
            .chunks_exact(4)
            .all(|x| x == 0.0f32.to_le_bytes())
    );
}

#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "Requires Vulkan; complete fused full-effects parity, not mobile speed"]
fn actual_full_effects_fused_matches_original_host_mirror() {
    let root = super::super::tests::temp().canonicalize().unwrap();
    for (case, (w, h, sigma)) in [(17, 13, 0.7), (23, 19, 3.1)].into_iter().enumerate() {
        let token = 210 + case as u64;
        let (src, lut) = super::super::tests::fixture(&root, w, h);
        let d = GpuFinishDescriptor {
            input: GpuFinishInput::FilmDensity,
            printing: None,
            scanner: &lut,
            scanner_min: [0.; 3],
            scanner_max: [1.; 3],
        };
        let mut opts = options();
        opts.lens_sigma = sigma;
        opts.sharp_radius = sigma;
        let dir = root.join(format!("gpu-stage-{token}"));
        let plan = prepare_stage(
            [&src[0], &src[1], &src[2]],
            &dir,
            token,
            w,
            h,
            &d,
            &tail_descriptor(),
            opts,
            || false,
        )
        .unwrap();
        assert_eq!(plan.driver_allowance_bytes, 320 * MIB);
        let (manifest, _) = read_manifest(&dir, token).unwrap();
        assert!(pointwise_shader(&manifest.tail).unwrap().is_none());
        let want = root.join(format!("expected-{case}"));
        let mut reference =
            FinishPrefix::load(&prefix_dir(&dir, token), token, &mut || false).unwrap();
        host_mirror(&manifest.tail, &mut reference, &want, &mut || false).unwrap();
        let out = root.join(format!("actual-{case}"));
        let mut prefix =
            FinishPrefix::load(&prefix_dir(&dir, token), token, &mut || false).unwrap();
        let report = tail::gpu::run_files_with_prefix_probe(
            &manifest.tail,
            &out,
            &mut || false,
            &mut prefix,
        )
        .unwrap();
        assert_eq!(report["privateNumericFlag"], true);
        assert_eq!(report["glareGenerationGpu"], true);
        let actual = fs::read(out).unwrap();
        let expected = fs::read(want).unwrap();
        assert_eq!(actual.len(), expected.len());
        let mut max = 0f64;
        let mut sum = 0f64;
        for (a, b) in actual.chunks_exact(4).zip(expected.chunks_exact(4)) {
            let a = f32::from_le_bytes(a.try_into().unwrap()) as f64;
            let b = f32::from_le_bytes(b.try_into().unwrap()) as f64;
            assert!(a.is_finite() && b.is_finite());
            let e = (a - b).abs();
            max = max.max(e);
            sum += e * e;
        }
        let rms = (sum / (actual.len() / 4) as f64).sqrt();
        eprintln!(
            "FULL_EFFECTS_GPU_MIRROR width={w} height={h} sigma={sigma} max_abs={max} rms={rms}"
        );
        assert!(max < 2e-3 && rms < 5e-4);
    }
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn full_tail_memory_policy_matches_selected_shader_route() {
    let mut opts = options();
    opts.glare = None;
    opts.lens_sigma = 0.;
    opts.sharp_radius = 0.;
    opts.sharp_amount = 0.;
    let p = TailPlan::new(&tail_descriptor(), 17, 13, opts.clone()).unwrap();
    assert_eq!(p.driver_allowance_bytes, 64 * MIB);
    let standalone = standalone_pointwise_shader(&p).unwrap().unwrap();
    assert!(!standalone.contains("finish_main"));
    assert!(standalone.contains("if(op>=21u && op<=25u)"));
    assert!(standalone.contains(" if(op==21u){"));
    assert!(!standalone.contains("glare_main"));
    assert!(!standalone.contains("reflect_index"));
    let module = naga::front::wgsl::parse_str(&standalone).unwrap();
    naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::empty(),
    )
    .validate(&module)
    .unwrap();
    opts.lens_sigma = 0.7;
    let mut full = TailPlan::new(&tail_descriptor(), 17, 13, opts).unwrap();
    assert_eq!(full.driver_allowance_bytes, 320 * MIB);
    assert!(standalone_pointwise_shader(&full).unwrap().is_none());
    full.driver_allowance_bytes = 64 * MIB;
    full.combined_peak_bytes -= 256 * MIB;
    assert!(full.validate().is_err());
}
