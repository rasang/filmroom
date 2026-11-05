#![cfg(all(feature="adreno-gpu",feature="precision-f64"))]
//! End-to-end: the BANDED wgpu film chain (the GPU export route for frames
//! too large for a single storage-buffer binding) vs the exact CPU pipeline
//! and vs the whole-frame GPU-resident chain. Runs on lavapipe; skipped when
//! no Vulkan adapter is available.

use std::path::{Path, PathBuf};

use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name};
use spektrafilm_gpu::{cpu_backend::CpuBackend, wgpu_backend::WgpuBackend};
use spektrafilm_math::{image::ImageBuf, precision::from_f64};

fn data() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")
}

fn runtime() -> RuntimeParams {
    let mut p = RuntimeParams::default();
    p.camera.auto_exposure = false;
    p.io.input_cctf_decoding = false;
    p.io.output_cctf_encoding = false;
    p.scanner.unsharp_mask = [0.7, 0.5];
    p.settings.use_enlarger_lut = true;
    p.settings.use_scanner_lut = true;
    p.settings.lut_resolution = 33;
    // Exercise the spatial + stochastic stages across band boundaries.
    p.film_render.grain.active = true;
    p.film_render.halation.active = true;
    p.print_render.glare.active = true;
    // Diffusion filters are global downsampled effects and NOT bandable.
    p.camera.diffusion_filter.active = false;
    p.enlarger.diffusion_filter.active = false;
    p
}

fn test_image(w: u32, h: u32) -> Vec<f32> {
    (0..(w * h * 3) as usize)
        .map(|i| {
            let x = (i / 3) % w as usize;
            let y = (i / 3) / w as usize;
            ((x * 13 + y * 7 + (i % 3) * 29) % 251) as f32 / 250.0
        })
        .collect()
}

fn pipeline(params: RuntimeParams) -> Pipeline {
    Pipeline::new_with_spectral(
        load_profile_by_name(&data(), "kodak_portra_400").unwrap(),
        load_profile_by_name(&data(), "kodak_portra_endura").unwrap(),
        params,
        &data(),
    )
    .unwrap()
}

fn diff_stats(a: &ImageBuf, b: &ImageBuf) -> (f64, f64) {
    assert_eq!(a.width, b.width);
    assert_eq!(a.height, b.height);
    let mut max_abs = 0.0f64;
    let mut sum_sq = 0.0f64;
    for (&x, &y) in a.data.iter().zip(b.data.iter()) {
        let (x, y) = (x as f64, y as f64);
        max_abs = max_abs.max((x - y).abs());
        sum_sq += (x - y) * (x - y);
    }
    (max_abs, (sum_sq / a.data.len() as f64).sqrt())
}

fn assert_sane(img: &ImageBuf) {
    assert!(img.data.iter().all(|v| v.is_finite()));
    let min = img.data.iter().fold(f64::INFINITY, |a, &b| a.min(b as f64));
    let max = img.data.iter().fold(f64::NEG_INFINITY, |a, &b| a.max(b as f64));
    assert!(min >= 0.0 && max <= 1.0, "output range [{min}, {max}]");
    assert!(max - min > 0.05, "output looks flat: [{min}, {max}]");
}

#[test]
fn banded_chain_matches_cpu_and_whole_frame_gpu() {
    let Some(gpu) = WgpuBackend::new() else {
        eprintln!("no Vulkan adapter — skipping banded-chain e2e");
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU adapter unavailable");
        return;
    };
    // TALL image (h > w): the per-band physical pixel pitch must stay pinned
    // to the full-image value (derived from max(w, h)); a naive per-band
    // pitch computation would shrink it and desync halation/grain/DIR.
    let (w, h) = (64u32, 99u32);
    let values = test_image(w, h);
    let img = ImageBuf::from_data(
        w,
        h,
        values.iter().map(|&x| from_f64(x as f64)).collect(),
    );
    let pipe = pipeline(runtime());

    // References: the exact CPU chain and the whole-frame GPU-resident chain.
    let cpu = pipe.process(img.clone(), &CpuBackend);
    let gpu_full = pipe
        .process_gpu_resident_cpu_gamut_f32(&values, w, h, &gpu)
        .expect("whole-frame GPU-resident chain produced no output");

    // Banded path: 16-row core bands over 96 rows → 6 bands, each processed
    // with a halo covering the blur kernels and stitched core rows.
    let band_rows = 16u32;
    assert!(band_rows < h, "test must exercise MULTIPLE bands");
    let banded = pipe
        .process_gpu_resident_banded_f32(&values, w, h, band_rows, &gpu)
        .expect("banded GPU chain produced no output");
    assert_eq!(banded.width, w);
    assert_eq!(banded.height, h);

    // 1) Banded vs exact CPU — the export preview tolerance (f32 GPU chain +
    //    approximate spatial stages), same as the whole-frame resident chain.
    let (max_abs, rms) = diff_stats(&cpu, &banded);
    eprintln!("banded chain vs CPU: max_abs={max_abs:.5} rms={rms:.6}");
    assert!(max_abs < 0.15, "max abs diff {max_abs} too large");
    assert!(rms < 0.02, "rms diff {rms} too large");

    // Diagnostic: the CPU delta above is entirely a property of the
    // whole-frame GPU export path (CPU-gamut route) for this image — the
    // banding error is measured independently, as shown next.
    let (max_abs_ref, rms_ref) = diff_stats(&cpu, &gpu_full);
    eprintln!("whole-frame GPU vs CPU (reference): max_abs={max_abs_ref:.5} rms={rms_ref:.6}");

    // 2) Banded vs whole-frame GPU — must be (near-)identical: same kernels,
    //    same per-pixel seeds (pixel_offset), same exposure. A seed or halo
    //    bug shows up as a band-boundary discontinuity here.
    let (max_abs_gpu, rms_gpu) = diff_stats(&gpu_full, &banded);
    eprintln!("banded chain vs whole-frame GPU: max_abs={max_abs_gpu:.6} rms={rms_gpu:.8}");
    assert!(
        max_abs_gpu < 0.01,
        "band discontinuity vs whole-frame GPU {max_abs_gpu}"
    );

    assert!(gpu.thread_submits()>0);
    assert_sane(&banded);
}

#[test]
fn banded_chain_glare_seed_continuity() {
    let Some(gpu) = WgpuBackend::new() else {
        eprintln!("no Vulkan adapter — skipping banded-chain glare e2e");
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU adapter unavailable");
        return;
    };
    // Crank the (random) glare field up so a per-band reseed would produce a
    // large, detectable seam against the whole-frame GPU run.
    let mut params = runtime();
    params.print_render.glare.percent = 1.0;
    params.print_render.glare.blur = 2.0;
    let (w, h) = (64u32, 99u32);
    let values = test_image(w, h);
    let pipe = pipeline(params);

    let gpu_full = pipe
        .process_gpu_resident_cpu_gamut_f32(&values, w, h, &gpu)
        .expect("whole-frame GPU-resident chain produced no output");
    let banded = pipe
        .process_gpu_resident_banded_f32(&values, w, h, 16, &gpu)
        .expect("banded GPU chain produced no output");

    let (max_abs_gpu, rms_gpu) = diff_stats(&gpu_full, &banded);
    eprintln!("glare banded vs whole-frame GPU: max_abs={max_abs_gpu:.6} rms={rms_gpu:.8}");
    assert!(
        max_abs_gpu < 0.01,
        "glare seed discontinuity vs whole-frame GPU {max_abs_gpu}"
    );
    assert!(gpu.thread_submits()>0);
    assert_sane(&banded);
}

/// The STREAMING single-band entry ([`Pipeline::process_gpu_resident_band_f32`])
/// stitched core-row-by-core-row must reproduce the whole-frame banded export
/// ([`Pipeline::process_gpu_resident_banded_f32`]) exactly — same halo math,
/// same per-band pixel offsets, same post-scan. This is the path the memory-
/// gated 50 MP export falls back to.
#[test]
fn streaming_single_band_matches_whole_frame_banded() {
    let Some(gpu) = WgpuBackend::new() else {
        eprintln!("no Vulkan adapter — skipping streaming single-band e2e");
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU adapter unavailable");
        return;
    };
    let params = runtime();
    let (w, h) = (64u32, 99u32);
    let band_rows = 16u32;
    let values = test_image(w, h);
    // Pre-pin the full-frame pitch exactly like the streaming exporter.
    let mut pinned = params.clone();
    let pitch = pinned
        .camera
        .pixel_size_um_override
        .unwrap_or(pinned.camera.film_format_mm * 1000.0 / w.max(h) as f32);
    pinned.camera.pixel_size_um_override = Some(pitch);
    let pipe = pipeline(pinned);

    let reference = pipe
        .process_gpu_resident_banded_f32(&values, w, h, band_rows, &gpu)
        .expect("whole-frame banded GPU chain produced no output");

    // Mirror the exporter's band sizing: halo from the actual kernels, bands
    // of `band_rows` core rows + halo on both sides, clipped to the frame.
    let halo = pipe.banded_halo_pixels(pitch);
    let mut stitched = ImageBuf::from_data(w, h, vec![from_f64(0.0); (w * h * 3) as usize]);
    let mut y0 = 0u32;
    while y0 < h {
        let y1 = (y0 + band_rows).min(h);
        let hy0 = y0.saturating_sub(halo);
        let hy1 = (y1 + halo).min(h);
        let bh = hy1 - hy0;
        // Slice the (core+halo) rows exactly like the streaming spool read.
        let mut band = vec![0f32; (w * bh * 3) as usize];
        let src = (hy0 * w * 3) as usize;
        band.copy_from_slice(&values[src..src + (w * bh * 3) as usize]);
        let band_out = pipe
            .process_gpu_resident_band_f32(&band, w, bh, h, hy0 * w, &gpu, None)
            .expect("single-band GPU chain produced no output");
        let core_top = (y0 - hy0) as usize * w as usize * 3;
        let core_rows = (y1 - y0) as usize;
        for r in 0..core_rows {
            let s = core_top + r * w as usize * 3;
            let d = (y0 as usize + r) * w as usize * 3;
            stitched.data[d..d + w as usize * 3]
                .copy_from_slice(&band_out.data[s..s + w as usize * 3]);
        }
        y0 = y1;
    }

    let (max_abs, rms) = diff_stats(&reference, &stitched);
    eprintln!("streaming single-band vs whole-frame banded: max_abs={max_abs:.8} rms={rms:.10}");
    assert!(max_abs < 1e-4, "streaming band mismatch {max_abs}");
    assert!(rms < 1e-5, "streaming band rms {rms}");
    assert!(gpu.thread_submits()>0);
    assert_sane(&stitched);
}

/// Diffusion filters through the BANDED chain: the whole-frame scattered
/// fields are precomputed once ([`Pipeline::diffusion_scattered_fields_gpu`],
/// tapping the chain at Front / PrintLinear per band), then each band mixes
/// its upsampled window. The stitched result must match the whole-frame
/// GPU-resident chain, which runs the full downsample→blur→upsample→mix
/// plan in one pass.
#[test]
fn banded_chain_diffusion_matches_whole_frame_gpu() {
    let Some(gpu) = WgpuBackend::new() else {
        eprintln!("no Vulkan adapter — skipping banded-chain diffusion e2e");
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU adapter unavailable");
        return;
    };
    let mut params = runtime();
    // Camera + enlarger diffusion active. A tiny pixel pitch inflates the
    // Gaussian sigmas in pixels so the plan downsamples (d > 1) — otherwise
    // the pre-pass degenerates to the trivial full-resolution path.
    params.camera.diffusion_filter.active = true;
    params.camera.diffusion_filter.filter_family = "glimmerglass".to_string();
    params.camera.diffusion_filter.strength = 1.0;
    params.camera.diffusion_filter.spatial_scale = 1.0;
    params.enlarger.diffusion_filter.active = true;
    params.enlarger.diffusion_filter.filter_family = "glimmerglass".to_string();
    params.enlarger.diffusion_filter.strength = 1.0;
    params.enlarger.diffusion_filter.spatial_scale = 1.0;
    let (w, h) = (64u32, 99u32);
    let band_rows = 16u32;
    let values = test_image(w, h);
    let pitch = 4.0f32; // µm/px — bloom sigma ≈ 180 px → d = 3
    params.camera.pixel_size_um_override = Some(pitch);
    let pipe = pipeline(params);

    // Reference: the whole-frame GPU-resident chain (full diffusion plan).
    let gpu_full = pipe
        .process_gpu_resident_cpu_gamut_f32(&values, w, h, &gpu)
        .expect("whole-frame GPU-resident chain produced no output");

    // Pre-pass: whole-frame scattered fields at 1/d, from band taps.
    let fields = pipe
        .diffusion_scattered_fields_gpu(w, h, band_rows, |y0, rows| {
            let src = (y0 as usize) * (w as usize) * 3;
            values[src..src + (rows as usize) * (w as usize) * 3].to_vec()
        }, &gpu)
        .expect("diffusion pre-pass produced no scattered fields");
    let cam = fields.camera.as_ref().expect("camera field missing");
    let enl = fields.enlarger.as_ref().expect("enlarger field missing");
    assert!(cam.d > 1, "test must exercise the downsampled pre-pass (d={})", cam.d);
    assert!(enl.d > 1, "test must exercise the downsampled pre-pass (d={})", enl.d);

    // Stitch the streaming band loop exactly like the exporter.
    let halo = pipe.banded_halo_pixels(pitch);
    let mut stitched = ImageBuf::from_data(w, h, vec![from_f64(0.0); (w * h * 3) as usize]);
    let mut y0 = 0u32;
    while y0 < h {
        let y1 = (y0 + band_rows).min(h);
        let hy0 = y0.saturating_sub(halo);
        let hy1 = (y1 + halo).min(h);
        let bh = hy1 - hy0;
        let mut band = vec![0f32; (w * bh * 3) as usize];
        let src = (hy0 * w * 3) as usize;
        band.copy_from_slice(&values[src..src + (w * bh * 3) as usize]);
        let band_out = pipe
            .process_gpu_resident_band_f32(&band, w, bh, h, hy0 * w, &gpu, Some(&fields))
            .expect("single-band GPU chain produced no output");
        let core_top = (y0 - hy0) as usize * w as usize * 3;
        let core_rows = (y1 - y0) as usize;
        for r in 0..core_rows {
            let s = core_top + r * w as usize * 3;
            let d = (y0 as usize + r) * w as usize * 3;
            stitched.data[d..d + w as usize * 3]
                .copy_from_slice(&band_out.data[s..s + w as usize * 3]);
        }
        y0 = y1;
    }

    let (max_abs, rms) = diff_stats(&gpu_full, &stitched);
    eprintln!("banded diffusion vs whole-frame GPU: max_abs={max_abs:.6} rms={rms:.8}");
    assert!(max_abs < 0.02, "diffusion band mismatch {max_abs}");
    assert!(rms < 0.002, "diffusion band rms {rms}");
    assert!(gpu.thread_submits()>0);
    assert_sane(&stitched);
}
