// sRGB EOTF (gamma-encoded -> linear), applied per sample.
//
// CPU equivalent: `spektrafilm_math::precision::srgb_decode` —
//   x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055)^2.4
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

fn srgb_decode(x: f32) -> f32 {
    if x <= 0.04045 {
        return x / 12.92;
    }
    return pow((x + 0.055) / 1.055, 2.4);
}

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let idx = gid.x;
    if idx >= params.n_pixels {
        return;
    }
    let base = idx * 3u;
    dst[base] = srgb_decode(src[base]);
    dst[base + 1u] = srgb_decode(src[base + 1u]);
    dst[base + 2u] = srgb_decode(src[base + 2u]);
}
