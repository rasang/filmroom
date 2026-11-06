// sRGB OETF (linear -> gamma-encoded), applied per sample.
//
// CPU equivalent: `spektrafilm_math::precision::srgb_encode` —
//   x <= 0.0031308 ? 12.92 * x : 1.055 * x^(1/2.4) - 0.055
//
// Unlike the resident-chain post_scan pass this does NOT clamp to [0, 1]:
// the CPU reference encodes the raw value as-is, and callers rely on that
// (e.g. HDR linear values above 1.0 round-trip through decode).
//
// `n` is the total number of pixels (not elements). Each thread processes
// the 3 channels of one pixel.

struct Params {
    n_pixels: u32,
    _pad0: u32,
    _pad1: u32,
    _pad2: u32,
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> src: array<f32>;
@group(0) @binding(2) var<storage, read_write> dst: array<f32>;

fn srgb_encode(x: f32) -> f32 {
    if x <= 0.0031308 {
        return 12.92 * x;
    }
    return 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let idx = gid.x;
    if idx >= params.n_pixels {
        return;
    }
    let base = idx * 3u;
    dst[base] = srgb_encode(src[base]);
    dst[base + 1u] = srgb_encode(src[base + 1u]);
    dst[base + 2u] = srgb_encode(src[base + 2u]);
}
