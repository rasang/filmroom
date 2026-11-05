// Trilinear 3D LUT interpolation.
//
// CPU equivalent: `spektrafilm_math::lut::trilinear_3d` —
//   rgb clamped to [0, 1], scaled by (size-1); 8-corner trilinear blend.
// LUT layout matches the CPU exactly:
//   idx(r, g, b, c) = (r * size * size + g * size + b) * 3 + c
//
// `n` is the total number of pixels (not elements).

struct Params {
    n_pixels: u32,
    size: u32,
    _pad: vec2<u32>,
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> src: array<f32>;      // [N*3]
@group(0) @binding(2) var<storage, read> lut: array<f32>;      // [size^3*3]
@group(0) @binding(3) var<storage, read_write> dst: array<f32>; // [N*3]

fn lut_at(r: u32, g: u32, b: u32, c: u32) -> f32 {
    let s = params.size;
    return lut[(r * s * s + g * s + b) * 3u + c];
}

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let idx = gid.x;
    if idx >= params.n_pixels {
        return;
    }
    let base = idx * 3u;
    let s = params.size;
    let max_idx = f32(s - 1u);

    let rf = min(clamp(src[base], 0.0, 1.0) * max_idx, max_idx);
    let gf = min(clamp(src[base + 1u], 0.0, 1.0) * max_idx, max_idx);
    let bf = min(clamp(src[base + 2u], 0.0, 1.0) * max_idx, max_idx);

    let r0 = u32(rf);
    let g0 = u32(gf);
    let b0 = u32(bf);
    let r1 = min(r0 + 1u, s - 1u);
    let g1 = min(g0 + 1u, s - 1u);
    let b1 = min(b0 + 1u, s - 1u);

    let fr = rf - f32(r0);
    let fg = gf - f32(g0);
    let fb = bf - f32(b0);

    for (var c = 0u; c < 3u; c = c + 1u) {
        let c000 = lut_at(r0, g0, b0, c);
        let c100 = lut_at(r1, g0, b0, c);
        let c010 = lut_at(r0, g1, b0, c);
        let c110 = lut_at(r1, g1, b0, c);
        let c001 = lut_at(r0, g0, b1, c);
        let c101 = lut_at(r1, g0, b1, c);
        let c011 = lut_at(r0, g1, b1, c);
        let c111 = lut_at(r1, g1, b1, c);

        let c00 = c000 + fr * (c100 - c000);
        let c10 = c010 + fr * (c110 - c010);
        let c01 = c001 + fr * (c101 - c001);
        let c11 = c011 + fr * (c111 - c011);

        let c0 = c00 + fg * (c10 - c00);
        let c1 = c01 + fg * (c11 - c01);

        dst[base + c] = c0 + fb * (c1 - c0);
    }
}
