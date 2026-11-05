//! Test-only gates for the untouched default science at actual export line lengths.
//! A passing allocation-plan test is not numerical admission. These admission
//! tests deliberately fail if the original guard rejects the requested defaults.
//! No test here constructs an 8192 x 6144 image or writes its image planes.
use super::*;

const WIDTH: u32 = 8192;
const HEIGHT: u32 = 6144;
// Explicit physical fixture: a 36 mm full-frame long edge, not a 20 um test pitch.
// RuntimeParams itself remains untouched, including its 35 mm format default.
const PITCH_UM: f32 = 36_000. / WIDTH as f32;

fn default_optics(width: u32, height: u32) -> StreamPlan {
    let params = RuntimeParams::default();
    let h = &params.film_render.halation;
    assert_eq!(PITCH_UM, 4.39453125);
    assert_eq!(params.camera.film_format_mm, 35.);
    assert_eq!(params.camera.pixel_size_um_override, None);
    assert_eq!(params.camera.lens_blur_um, 0.);
    assert!(h.active && h.halation_renormalize);
    assert_eq!(h.scatter_amount, 1.);
    assert_eq!(h.scatter_spatial_scale, 1.);
    assert_eq!(h.scatter_core_um, [2.2, 2.0, 1.6]);
    assert_eq!(h.scatter_tail_um, [9.3, 9.7, 9.1]);
    assert_eq!(h.scatter_tail_weight, [0.78, 0.65, 0.67]);
    assert_eq!(h.halation_amount, 1.);
    assert_eq!(h.halation_spatial_scale, 1.);
    assert_eq!(h.halation_strength, [0.05, 0.015, 0.]);
    assert_eq!(h.halation_first_sigma_um, [65.; 3]);
    assert_eq!(h.halation_n_bounces, 3);
    assert_eq!(h.halation_bounce_decay, 0.5);
    let plan = StreamPlan::new(&params, PITCH_UM, width, height).unwrap();
    for (ch, c) in plan.channels.iter().enumerate() {
        assert_eq!(c.lens_sigma, params.camera.lens_blur_um / PITCH_UM);
        let s = c.scatter.as_ref().expect("default scatter remains active");
        assert_eq!(s.sigma, (h.scatter_core_um[ch] / PITCH_UM as f64) as f32);
        assert_eq!(s.decay, (h.scatter_tail_um[ch] / PITCH_UM as f64) as f32);
        assert_eq!(s.amount, h.scatter_amount);
        assert_eq!(s.tail_weight, h.scatter_tail_weight[ch]);
        let bounces: Vec<f32> = if ch < 2 {
            (1..=3)
                .map(|k| (65. / PITCH_UM as f64 * (k as f64).sqrt()) as f32)
                .collect()
        } else {
            vec![]
        };
        assert_eq!(c.bounce_sigmas, bounces);
        assert_eq!(c.bounce_weights, if ch < 2 { vec![4. / 7., 2. / 7., 1. / 7.] } else { vec![] });
        assert_eq!(c.strength, h.halation_strength[ch]);
        assert_eq!(c.divisor, 1. + h.halation_strength[ch]);
    }
    plan
}

fn print_optics(plan: &StreamPlan) {
    eprintln!("default optics: dimensions={}x{}, pixels={}, explicit_full_frame_long_edge_mm=36, pitch_um={PITCH_UM:?}, unchanged_runtime_format_mm=35", plan.width, plan.height, u64::from(plan.width) * u64::from(plan.height));
    for (ch, c) in plan.channels.iter().enumerate() {
        eprintln!("channel={ch}, complete_scientific_parameters={c:?}, all_filter_sigmas_px={:?}", c.sigmas());
    }
}

#[test]
fn default_fifty_megapixel_optics_numerical_admission_gate() {
    let plan = default_optics(WIDTH, HEIGHT);
    assert_eq!(u64::from(plan.width) * u64::from(plan.height), 50_331_648);
    assert_eq!(plan.combined_peak_bytes, 260 * spatial::MIB + 65536);
    assert_eq!(
        FilmOpticsPlan::new(&RuntimeParams::default(), PITCH_UM, WIDTH, HEIGHT).unwrap_err(),
        "GPU_OPTICS_MEMORY_LIMIT"
    );
    print_optics(&plan);
    // This invokes the real StreamPlan guard with 8192 and 6144, not the
    // constructor's 1x1 semantic plan. It allocates only individual test lines.
    let result = plan.numerical_preflight(&mut || false);
    eprintln!("default 50MP optics real-dimension numerical_preflight Result={result:?}");
    assert_eq!(result, Ok(()), "default 50MP optics is NOT admitted if the unchanged numerical guard rejects it");
}

fn profile_data() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")
}

fn calibrated_default_pipeline(prepared_luts: bool) -> spektrafilm_core::pipeline::Pipeline {
    use spektrafilm_core::{pipeline::Pipeline, profile::load_profile_by_name};
    let data = profile_data();
    let mut params = RuntimeParams::default();
    if prepared_luts {
        // These API prerequisites are NOT the pure RuntimeParams default.
        // Keep the original default resolution and all scientific controls.
        params.settings.use_enlarger_lut = true;
        params.settings.use_scanner_lut = true;
    }
    assert_eq!(params.settings.lut_resolution, 17);
    Pipeline::new_with_spectral(
        load_profile_by_name(&data, "kodak_portra_400").unwrap(),
        load_profile_by_name(&data, "kodak_portra_endura").unwrap(),
        params,
        &data,
    )
    .unwrap()
}

#[test]
fn default_fifty_megapixel_per_sigma_diagnostic_admission_gate() {
    use crate::research_streaming_dir::StreamDirPlan;
    let optics = default_optics(WIDTH, HEIGHT);
    let pipeline = calibrated_default_pipeline(false);
    let dir = StreamDirPlan::new(&pipeline, PITCH_UM, WIDTH, HEIGHT, None).unwrap();
    let p = dir.parameters();
    let groups = [
        ("default optics", optics.channels.iter().flat_map(ChannelPlan::sigmas).collect::<Vec<_>>()),
        ("default Pipeline DIR", std::iter::once(p.core_sigma)
            .chain(spatial::FIT.into_iter().map(|(_, r)| (r * p.tail_decay as f64) as f32))
            .collect::<Vec<_>>()),
    ];
    let mut rejected = Vec::new();
    for (stage, sigmas) in groups {
        let mut seen = Vec::new();
        for sigma in sigmas {
            if seen.contains(&sigma.to_bits()) { continue; }
            seen.push(sigma.to_bits());
            // Exact original function, true export geometry and one unchanged
            // sigma per call. No arithmetic reimplementation or relaxed guard.
            let result = spatial::numerical_preflight_for_sigmas(
                WIDTH, HEIGHT, [sigma], &mut || false,
            );
            eprintln!("per-sigma actual guard: stage={stage}, geometry={WIDTH}x{HEIGHT}, pitch_um={PITCH_UM:?}, sigma_px={sigma:?}, sigma_f32_bits={:#010x}, Result={result:?}", sigma.to_bits());
            if let Err(error) = result { rejected.push((stage, sigma, error)); }
        }
    }
    // Print every result before failing so the first rejected radius cannot
    // obscure any later default radius. A rejection is never a passing gate.
    assert!(rejected.is_empty(), "default 50MP per-sigma admission failures: {rejected:?}");
}

#[test]
fn default_fifty_megapixel_pipeline_dir_numerical_admission_gate() {
    use crate::{research_dir::DirPlan, research_streaming_dir::StreamDirPlan};
    // Use the original fully calibrated constructor, including its film-specific
    // DIR calibration, with unchanged RuntimeParams::default() as its input.
    let pipeline = calibrated_default_pipeline(false);
    assert!(!pipeline.params.settings.use_enlarger_lut);
    assert!(!pipeline.params.settings.use_scanner_lut);
    let d = &pipeline.params.film_render.dir_couplers;
    assert!(d.active);
    assert_eq!(d.diffusion_size_um, 20.);
    assert_eq!(d.diffusion_tail_um, 200.);
    assert_eq!(d.diffusion_tail_weight, 0.06);
    let plan = StreamDirPlan::new(&pipeline, PITCH_UM, WIDTH, HEIGHT, None).unwrap();
    let mut original_descriptor = DirPlan::new(&pipeline, PITCH_UM, 1, 1).unwrap();
    original_descriptor.width = WIDTH;
    original_descriptor.height = HEIGHT;
    // Compare every original curve, reciprocal, matrix and scalar. Only the
    // geometry differs from the genuine resident-compatible semantic fixture.
    assert_eq!(plan.parameters(), &original_descriptor);
    let p = plan.parameters();
    assert_eq!(p.core_sigma, (20. / PITCH_UM as f64) as f32);
    assert_eq!(p.tail_decay, (200. / PITCH_UM as f64) as f32);
    assert_eq!(p.tail_weight, 0.06);
    assert_eq!(p.validate().unwrap_err(), "GPU_OPTICS_MEMORY_LIMIT");
    let sigmas: Vec<_> = std::iter::once(p.core_sigma)
        .chain(spatial::FIT.into_iter().map(|(_, r)| (r * p.tail_decay as f64) as f32))
        .collect();
    eprintln!("default calibrated Pipeline DIR: dimensions={}x{}, pitch_um={PITCH_UM:?}, parameters={d:?}, matrix={:?}, density_max={:?}, initial_knots={}, before_dir_knots={}, all_filter_sigmas_px={sigmas:?}, memory={:?}", p.width, p.height, p.matrix, p.density_max, p.initial.axis.len(), p.before_dir.axis.len(), plan.memory().unwrap());
    let result = plan.numerical_preflight(&mut || false);
    eprintln!("default 50MP Pipeline DIR real-dimension numerical_preflight Result={result:?}");
    assert_eq!(result, Ok(()), "default 50MP DIR is NOT admitted if the unchanged numerical guard rejects it");
}

#[cfg(feature = "diffusion-research")]
#[test]
fn untouched_default_prepared_finish_is_unsupported_without_spectral_luts() {
    use spektrafilm_core::{pipeline::Pipeline, profile::load_profile_by_name};
    use spektrafilm_gpu::cpu_backend::CpuBackend;
    let data = profile_data();
    let params = RuntimeParams::default();
    assert!(!params.settings.use_enlarger_lut && !params.settings.use_scanner_lut);
    // The uncalibrated constructor is sufficient for this API prerequisite
    // regression. It is deliberately NOT the descriptor used by the gate below.
    let pipeline = Pipeline::new(
        load_profile_by_name(&data, "kodak_portra_400").unwrap(),
        load_profile_by_name(&data, "kodak_portra_endura").unwrap(),
        params,
    );
    let result = pipeline.prepare_streamed_finish_spatial(&CpuBackend).map(|_| ());
    eprintln!("pure RuntimeParams::default prepared-finish construction Result={result:?}; this regression documents an API rejection, not 50MP admission");
    assert_eq!(result, Err("Prepared printing requires its spectral LUT".into()));
}

#[cfg(feature = "diffusion-research")]
#[test]
fn default_science_prepared_luts_fifty_megapixel_finish_numerical_admission_gate() {
    use crate::{
        research_finish_tail::{TailOptions, TailPlan},
        research_streaming_finish::StreamFinishPlan,
    };
    use spektrafilm_core::pipeline::GpuFinishInput;
    use spektrafilm_gpu::cpu_backend::CpuBackend;
    use spektrafilm_math::pchip3d::PreparedPchip3d;

    let pipeline = calibrated_default_pipeline(true);
    assert!(pipeline.params.settings.use_enlarger_lut);
    assert!(pipeline.params.settings.use_scanner_lut);
    assert_eq!(pipeline.params.settings.lut_resolution, 17);
    let prepared = pipeline.prepare_streamed_finish_spatial(&CpuBackend).unwrap();
    let descriptor = prepared.gpu_scan_tail_descriptor();
    let options = TailOptions::from_params(&pipeline.params, 0.);
    assert_eq!(options, TailOptions::from_params(&RuntimeParams::default(), 0.));
    assert_eq!(options.lens_sigma, 0.);
    assert_eq!((options.sharp_radius, options.sharp_amount), (0.7, 0.7));
    let glare = options.glare.as_ref().expect("default scanner glare retained");
    assert_eq!((glare.percent, glare.roughness, glare.blur), (0.03, 0.7, 0.5));
    assert!(!options.finalize_cctf);
    assert!(descriptor.output_cctf_encoding);
    let gamut = descriptor.gamut.as_ref().expect("default CAM16-UCS table retained");
    assert_eq!(gamut.mode, 3);
    assert_eq!((gamut.n_l, gamut.n_h), (64, 720));
    assert_eq!(gamut.cmax.len(), 64 * 720);
    let semantic = TailPlan::new(&descriptor, 1, 1, options.clone()).unwrap();
    assert_eq!(semantic.gamut.as_ref().unwrap().cmax.as_slice(), gamut.cmax);
    assert_eq!(
        TailPlan::new(&descriptor, WIDTH, HEIGHT, options.clone()).unwrap_err(),
        "GPU_OPTICS_MEMORY_LIMIT"
    );

    // Count the original borrowed PCHIP arrays and sensitometry, not synthetic
    // size-5 LUTs. No image files or flattened table copies are needed here.
    let prefix = prepared.gpu_finish_descriptor(GpuFinishInput::FilmDensity).unwrap();
    let count = |lut: &PreparedPchip3d| -> u64 {
        assert_eq!(lut.size, 17);
        [&lut.lut, &lut.slope_x, &lut.slope_y, &lut.slope_z, &lut.cell_min, &lut.cell_max]
            .iter().map(|v| v.len() as u64).sum()
    };
    let printing = prefix.printing.as_ref().expect("default print route retained");
    assert!(!printing.external_diffusion_required);
    assert_eq!(printing.log_exposure.len(), printing.curves.len());
    let table_scalars = count(prefix.scanner) + count(printing.lut)
        + printing.log_exposure.len() as u64 + 3 * printing.curves.len() as u64;
    eprintln!("finish fixture: default calibrated science with ONLY prepared API flags use_enlarger_lut=true/use_scanner_lut=true; lut_resolution=17, geometry={WIDTH}x{HEIGHT}, physical_pitch_um={PITCH_UM:?} (scanner sigmas are already pixels), options={options:?}, adapt={:?}, xyz_to_rgb={:?}, xyz_remap={:?}, glare_rgb_offset={:?}, rgb_identity={:?}, output_cctf_encoding={}, gamut_mode={}, gamut_shape={}x{}, original_prefix_table_scalars={table_scalars}", descriptor.adapt, descriptor.xyz_to_rgb, descriptor.xyz_remap, descriptor.glare_rgb_offset, descriptor.rgb_identity, descriptor.output_cctf_encoding, gamut.mode, gamut.n_l, gamut.n_h);
    for prefix_table_scalars in [None, Some(table_scalars)] {
        let plan = StreamFinishPlan::new(
            &descriptor, WIDTH, HEIGHT, options.clone(), prefix_table_scalars, false,
        ).unwrap();
        assert_eq!((plan.width(), plan.height()), (WIDTH, HEIGHT));
        assert_eq!(plan.parameters(), &semantic);
        assert_eq!(plan.parameters().table32(), semantic.table32());
        let result = plan.numerical_preflight(&mut || false);
        eprintln!("default-science 50MP finish: prefix_table_scalars={prefix_table_scalars:?}, all_filter_sigmas_px={:?}, memory={:?}, real-dimension numerical_preflight Result={result:?}", [options.lens_sigma, options.sharp_radius, glare.blur], plan.memory().unwrap());
        assert_eq!(result, Ok(()), "default-science 50MP finish is NOT admitted if its unchanged guard rejects it");
    }
}

#[cfg(feature = "gpu-diffusion-research")]
mod actual_vulkan {
    use super::*;
    use std::{fs, path::PathBuf, sync::atomic::{AtomicU64, Ordering}};

    struct TempRoot(PathBuf);
    impl TempRoot {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(1);
            let path = std::env::temp_dir().join(format!(
                "film-default-optics-thin-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed),
            ));
            fs::create_dir(&path).unwrap();
            Self(path.canonicalize().unwrap())
        }
    }
    impl Drop for TempRoot {
        fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); }
    }

    #[test]
    #[ignore = "explicit actual Vulkan default-sigma 8192/6144 complete-line gate; no 50MP allocation; not Adreno performance evidence"]
    fn default_sigma_full_export_line_lengths_actual_vulkan_admission_and_mirror_gate() {
        // Three lines force three separate complete-line batches; recurrences
        // may not restart within the original 8192- or 6144-sample line.
        // Pitch stays tied to the ORIGINAL 8192x6144 image on both thin fixtures.
        for (width, height) in [(WIDTH, 3), (3, HEIGHT)] {
            let plan = default_optics(width, height);
            print_optics(&plan);
            let result = plan.numerical_preflight(&mut || false);
            eprintln!("actual Vulkan default-sigma {width}x{height} preflight Result={result:?}; a rejection means Vulkan execution has NOT started");
            assert_eq!(result, Ok(()), "unchanged default-sigma admission guard must pass before actual Vulkan execution");

            let root = TempRoot::new();
            let n = width as usize * height as usize;
            assert!(n <= WIDTH as usize * 3);
            let source: Vec<f64> = (0..n)
                .map(|i| (i * 37 % 101) as f64 / 13. - 0.7 + if i == n / 3 { 17. } else { 0. })
                .collect();
            let inputs: [PathBuf; 3] = std::array::from_fn(|ch| root.0.join(format!("input-{ch}.f64le")));
            let outputs: [PathBuf; 3] = std::array::from_fn(|ch| root.0.join(format!("output-{ch}.f64le")));
            let encoded: Vec<u8> = source.iter().flat_map(|x| x.to_le_bytes()).collect();
            for path in &inputs { fs::write(path, &encoded).unwrap(); }
            drop(encoded);
            let scratch = root.0.join("scratch");
            let mut gpu = gpu::Gpu::new(&plan, true).unwrap();
            files::run(
                &plan, inputs.each_ref().map(|p| p.as_path()), outputs.each_ref().map(|p| p.as_path()),
                &scratch, width.max(height) + 1, &mut gpu, &mut || false, &mut |_, _| {},
            ).unwrap();
            for (ch, path) in outputs.iter().enumerate() {
                let expected = spatial::channel_cpu_mirror(
                    &source, width, height, &plan.channels[ch], &mut || false,
                ).unwrap();
                let bytes = fs::read(path).unwrap();
                assert_eq!(bytes.len(), n * 8);
                for (i, (actual, expected)) in bytes.chunks_exact(8)
                    .map(|b| f64::from_le_bytes(b.try_into().unwrap()))
                    .zip(expected).enumerate()
                {
                    assert!(actual.is_finite() && expected.is_finite());
                    assert!((actual - expected).abs() <= 2e-4 * expected.abs().max(1.),
                        "default-sigma {width}x{height}, channel={ch}, sample={i}: actual={actual}, mirror={expected}");
                }
            }
            assert!(!scratch.exists());
            let metrics = gpu.metrics();
            assert!(metrics.dispatches > 0 && metrics.pass_count > 0);
            assert_eq!(metrics.sample_count, metrics.pass_count * u64::from(width + height));
            eprintln!("completed actual Vulkan default-sigma thin-image graph: {width}x{height}, metrics={metrics:?}; not a full 50MP render or physical-Adreno speed claim");
        }
    }
}
