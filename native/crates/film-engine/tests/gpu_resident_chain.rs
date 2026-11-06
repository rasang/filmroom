#![cfg(all(feature="adreno-gpu",feature="precision-f64"))]
//! End-to-end: the wgpu resident film chain (GPU f32 preview route) vs the
//! exact CPU pipeline. Runs on lavapipe; skipped when no Vulkan adapter.

use std::path::{Path, PathBuf};

use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name};
use spektrafilm_gpu::{ComputeBackend, cpu_backend::CpuBackend, wgpu_backend::WgpuBackend};
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
    // Exercise the approximate spatial stages on the GPU chain too.
    p.film_render.grain.active = true;
    p.film_render.halation.active = true;
    p
}

#[test]
fn resident_chain_matches_cpu_within_preview_tolerance() {
    let Some(gpu) = WgpuBackend::new() else {
        eprintln!("no Vulkan adapter — skipping resident-chain e2e");
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU adapter unavailable");
        return;
    };
    let (w, h) = (96u32, 64u32);
    let values: Vec<f32> = (0..(w * h * 3) as usize)
        .map(|i| {
            let x = (i / 3) % w as usize;
            let y = (i / 3) / w as usize;
            ((x * 13 + y * 7 + (i % 3) * 29) % 251) as f32 / 250.0
        })
        .collect();
    let img = ImageBuf::from_data(
        w,
        h,
        values.iter().map(|&x| from_f64(x as f64)).collect(),
    );
    let pipe = Pipeline::new_with_spectral(
        load_profile_by_name(&data(), "kodak_portra_400").unwrap(),
        load_profile_by_name(&data(), "kodak_portra_endura").unwrap(),
        runtime(),
        &data(),
    )
    .unwrap();

    let cpu = pipe.process(img.clone(), &CpuBackend);
    let submits_before=gpu.thread_submits();
    let gpu_out = pipe.process_resident_borrowed(&img, &gpu).expect("Resident GPU returned no output; CPU fallback is not GPU success");
    assert!(gpu.thread_submits()>submits_before);

    assert_eq!(cpu.width, gpu_out.width);
    assert_eq!(cpu.height, gpu_out.height);
    let mut max_abs = 0.0f64;
    let mut sum_sq = 0.0f64;
    for (&c, &g) in cpu.data.iter().zip(gpu_out.data.iter()) {
        let c = c as f64;
        let g = g as f64;
        max_abs = max_abs.max((c - g).abs());
        sum_sq += (c - g) * (c - g);
    }
    let rms = (sum_sq / cpu.data.len() as f64).sqrt();
    eprintln!("resident chain vs CPU: max_abs={max_abs:.5} rms={rms:.6}");
    // Preview tolerance: f32 GPU chain + approximate spatial stages.
    assert!(max_abs < 0.15, "max abs diff {max_abs} too large");
    assert!(rms < 0.02, "rms diff {rms} too large");
    // Sane output: finite, in [0,1], and actually film-like (not constant).
    assert!(gpu_out.data.iter().all(|v| v.is_finite()));
    let min = gpu_out.data.iter().fold(f64::INFINITY, |a, &b| a.min(b as f64));
    let max = gpu_out.data.iter().fold(f64::NEG_INFINITY, |a, &b| a.max(b as f64));
    assert!(min >= 0.0 && max <= 1.0, "output range [{min}, {max}]");
    assert!(max - min > 0.05, "output looks flat: [{min}, {max}]");
}
