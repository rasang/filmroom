// Banded-chain diffusion mix: bilinear-upsample the band's row window from a
// PRECOMPUTED whole-frame scattered field (at 1/d resolution) and mix it into
// the running image, `img = (1-p_s)·img + p_s·scattered`. Each thread handles
// one band pixel (all 3 channels).
//
// This is the local half of the diffusion filter for the banded resident
// chain — the global half (downsample → multi-Gaussian blur) was done once
// for the whole frame by the pre-pass, so the banded chain stays exact
// without banding the (non-bandable) large-radius blur. Mirrors the CPU
// `upsample_bilinear` mapping (sample centre at `(x + 0.5)/d − 0.5` in
// small-pixel coords) and the `(1-p_s)·img + p_s·up` mix.

struct Params {
    band_w: u32,   // running image width  (== full-frame width)
    band_h: u32,   // running image height (this band, halo rows included)
    small_w: u32,  // scattered field width
    small_h: u32,  // scattered field height
    d: u32,        // downsample factor the field was computed at
    band_y0: u32,  // global row index of this band's row 0 (halo included)
    p_s: f32,      // scatter mix strength
    inv_d: f32,    // 1.0 / d
    band_x0: u32,
    crop_x: u32,
    crop_y: u32,
    crop_w: u32,
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> scattered: array<f32>;
@group(0) @binding(2) var<storage, read_write> img: array<f32>;

fn sample_small(sx: u32, sy: u32, c: u32) -> f32 {
    return scattered[((sy - params.crop_y) * params.crop_w + sx - params.crop_x) * 3u + c];
}

@compute @workgroup_size(16, 16)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let x = gid.x;
    let y = gid.y;
    if x >= params.band_w || y >= params.band_h {
        return;
    }
    // Global (whole-frame) row of this band pixel.
    let gy = params.band_y0 + y;
    let max_x = f32(params.small_w - 1u);
    let max_y = f32(params.small_h - 1u);
    let fx = clamp((f32(params.band_x0 + x) + 0.5) * params.inv_d - 0.5, 0.0, max_x);
    let fy = clamp((f32(gy) + 0.5) * params.inv_d - 0.5, 0.0, max_y);
    let x0 = u32(floor(fx));
    let y0 = u32(floor(fy));
    let x1 = min(x0 + 1u, params.small_w - 1u);
    let y1 = min(y0 + 1u, params.small_h - 1u);
    let wx = fx - floor(fx);
    let wy = fy - floor(fy);

    let o = (y * params.band_w + x) * 3u;
    let keep = 1.0 - params.p_s;
    for (var c = 0u; c < 3u; c++) {
        let top = sample_small(x0, y0, c) * (1.0 - wx) + sample_small(x1, y0, c) * wx;
        let bot = sample_small(x0, y1, c) * (1.0 - wx) + sample_small(x1, y1, c) * wx;
        let s = top * (1.0 - wy) + bot * wy;
        img[o + c] = keep * img[o + c] + params.p_s * s;
    }
}
