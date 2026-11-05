//! Numeric equivalence: the five GPU point-op shaders vs the CPU reference.
//! Runs on lavapipe (software Vulkan) in CI/sandbox; skipped silently when
//! no Vulkan adapter exists.

use spektrafilm_gpu::{ComputeBackend, Lut3D, cpu_backend::CpuBackend, wgpu_backend::WgpuBackend};
use spektrafilm_math::image::ImageBuf;
use spektrafilm_math::precision::{Scalar, from_f32, to_f32};

fn make_img(w: u32, h: u32, seed: u32) -> ImageBuf {
    // Deterministic pseudo-random pixels in [-0.05, 1.2] — includes
    // out-of-gamut and sub-threshold values to exercise both CCTF branches.
    let mut state = seed.max(1);
    let mut next = || {
        state = state.wrapping_mul(1664525).wrapping_add(1013904223);
        ((state >> 8) as f32 / (1u32 << 24) as f32) * 1.25 - 0.05
    };
    let data: Vec<Scalar> = (0..(w * h * 3)).map(|_| from_f32(next())).collect();
    ImageBuf::from_data(w, h, data)
}

fn assert_close(name: &str, gpu: &ImageBuf, cpu: &ImageBuf, abs: f32) {
    assert_eq!(gpu.width, cpu.width, "{name}: width mismatch");
    assert_eq!(gpu.height, cpu.height, "{name}: height mismatch");
    assert_eq!(gpu.data.len(), cpu.data.len(), "{name}: len mismatch");
    let mut worst = 0.0f32;
    let mut worst_i = 0usize;
    for (i, (&g, &c)) in gpu.data.iter().zip(cpu.data.iter()).enumerate() {
        let d = (to_f32(g) - to_f32(c)).abs();
        if d > worst {
            worst = d;
            worst_i = i;
        }
    }
    assert!(
        worst <= abs,
        "{name}: max abs diff {worst} at index {worst_i} exceeds {abs}"
    );
}

fn gpu_or_skip() -> Option<WgpuBackend> {
    let gpu = WgpuBackend::new();
    if gpu.is_none() {
        eprintln!("no Vulkan adapter — skipping GPU numeric test");
    }
    gpu
}

#[test]
fn colorspace_convert_matches_cpu() {
    let Some(gpu) = gpu_or_skip() else { return };
    let img = make_img(37, 23, 7);
    // sRGB -> XYZ D65 matrix (row-major).
    let m = [
        [0.4124564, 0.3575761, 0.1804375],
        [0.2126729, 0.7151522, 0.0721750],
        [0.0193339, 0.1191920, 0.9503041],
    ];
    assert_close(
        "colorspace_convert",
        &gpu.colorspace_convert(&img, &m),
        &CpuBackend.colorspace_convert(&img, &m),
        1e-5,
    );
}

#[test]
fn cctf_roundtrip_and_points_match_cpu() {
    let Some(gpu) = gpu_or_skip() else { return };
    let img = make_img(31, 17, 11);
    assert_close(
        "cctf_encode_srgb",
        &gpu.cctf_encode_srgb(&img),
        &CpuBackend.cctf_encode_srgb(&img),
        1e-5,
    );
    assert_close(
        "cctf_decode_srgb",
        &gpu.cctf_decode_srgb(&img),
        &CpuBackend.cctf_decode_srgb(&img),
        1e-5,
    );
    // Roundtrip: decode(encode(x)) ≈ x for in-range values.
    let pos = make_img(16, 16, 3);
    let rt = gpu.cctf_decode_srgb(&gpu.cctf_encode_srgb(&pos));
    assert_close("cctf_roundtrip", &rt, &pos, 1e-4);
}

#[test]
fn table_lookup_matches_cpu() {
    let Some(gpu) = gpu_or_skip() else { return };
    let img = make_img(29, 19, 13);
    // Non-uniform monotone axis + per-channel curves.
    let table_x: Vec<f32> = vec![-0.2, 0.0, 0.13, 0.5, 0.77, 0.9, 1.0, 1.3];
    let table_y: Vec<[f32; 3]> = table_x
        .iter()
        .enumerate()
        .map(|(i, &x)| [x * x, (i as f32) * 0.1, 1.0 - x])
        .collect();
    assert_close(
        "table_lookup",
        &gpu.table_lookup(&img, &table_x, &table_y),
        &CpuBackend.table_lookup(&img, &table_x, &table_y),
        1e-5,
    );
}

#[test]
fn lut3d_interp_matches_cpu() {
    let Some(gpu) = gpu_or_skip() else { return };
    let img = make_img(23, 21, 17);
    // 4^3 LUT with a smooth but non-trivial mapping.
    let size = 4usize;
    let mut data = Vec::with_capacity(size * size * size * 3);
    for r in 0..size {
        for g in 0..size {
            for b in 0..size {
                let rf = r as f32 / (size - 1) as f32;
                let gf = g as f32 / (size - 1) as f32;
                let bf = b as f32 / (size - 1) as f32;
                data.push((rf * 0.9 + bf * 0.1).min(1.0));
                data.push((gf * gf).min(1.0));
                data.push((1.0 - rf * 0.5).clamp(0.0, 1.0));
            }
        }
    }
    let lut = Lut3D {
        size: size as u32,
        data,
    };
    assert_close(
        "lut3d_interp",
        &gpu.lut3d_interp(&img, &lut),
        &CpuBackend.lut3d_interp(&img, &lut),
        1e-5,
    );
}
