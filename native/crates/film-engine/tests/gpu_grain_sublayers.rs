#![cfg(all(feature="adreno-gpu",feature="precision-f64"))]
//! GPU sublayer grain (the v41 approximate sublayer model) vs the CPU
//! pipeline: for a flat field the output noise is pure grain, so the grain
//! *statistics* (mean + variance) must match even though the per-pixel noise
//! realisations differ (GPU PCG hash vs CPU MT19937). Guards the fix for
//! "GPU grain visibly heavier than CPU in mid-tones".

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
    // Grain is the only spatial-noise source for a flat field (convolution
    // stages preserve DC), so this isolates the grain model.
    p.film_render.grain.active = true;
    p.film_render.halation.active = false;
    p
}

fn channel_stats(img: &ImageBuf, ch: usize) -> (f64, f64) {
    let vals: Vec<f64> = img.data.chunks_exact(3).map(|p| p[ch] as f64).collect();
    let mean = vals.iter().sum::<f64>() / vals.len() as f64;
    let var = vals.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / vals.len() as f64;
    (mean, var)
}

#[test]
fn gpu_sublayer_grain_matches_cpu_statistics() {
    let Some(gpu) = WgpuBackend::new() else {
        eprintln!("no Vulkan adapter — skipping sublayer-grain statistics");
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU adapter unavailable");
        return;
    };
    // Flat mid-tone field, large enough that grain variance is well measured.
    let (w, h) = (384u32, 384u32);
    let n = (w * h) as usize;
    let img = ImageBuf::from_data(w, h, vec![from_f64(0.4); n * 3]);
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
    assert_eq!(cpu.data.len(), gpu_out.data.len());

    for ch in 0..3 {
        let (cm, cv) = channel_stats(&cpu, ch);
        let (gm, gv) = channel_stats(&gpu_out, ch);
        eprintln!("ch{ch}: cpu mean={cm:.5} var={cv:.3e} | gpu mean={gm:.5} var={gv:.3e}");
        // Mean (film density) is deterministic and must match closely.
        assert!((cm - gm).abs() < 0.05, "ch{ch} mean cpu={cm} gpu={gm}");
        // Grain is present and its variance matches within ~25%.
        assert!(cv > 0.0, "ch{ch} CPU grain variance should be positive");
        assert!(gv.is_finite() && gv > 0.0, "ch{ch} GPU grain variance should be positive");
        let ratio = gv / cv;
        assert!(
            (0.75..=1.33).contains(&ratio),
            "ch{ch} grain variance ratio {ratio} out of range (cpu={cv} gpu={gv})"
        );
    }
}
