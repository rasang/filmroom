// Per-pixel 3x3 color-space matrix multiply: rgb_out = M * rgb_in.
//
// CPU equivalent: `cpu_backend::CpuBackend::colorspace_convert` —
//   out[c] = m[c][0]*r + m[c][1]*g + m[c][2]*b   (row-major M)
//
// `n` is the total number of pixels (not elements). Each thread processes
// the 3 channels of one pixel — keeps workgroup count under the 65535 limit
// for large images.

struct Params {
    n_pixels: u32,
    _pad0: u32,
    _pad1: u32,
    _pad2: u32,
    // Row-major matrix [3][3], packed as three vec4s (.w padding to
    // satisfy std140 alignment).
    m_row0: vec4<f32>,
    m_row1: vec4<f32>,
    m_row2: vec4<f32>,
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> src: array<f32>;
@group(0) @binding(2) var<storage, read_write> dst: array<f32>;

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let idx = gid.x;
    if idx >= params.n_pixels {
        return;
    }
    let base = idx * 3u;
    let r = src[base];
    let g = src[base + 1u];
    let b = src[base + 2u];
    dst[base] = params.m_row0.x * r + params.m_row0.y * g + params.m_row0.z * b;
    dst[base + 1u] = params.m_row1.x * r + params.m_row1.y * g + params.m_row1.z * b;
    dst[base + 2u] = params.m_row2.x * r + params.m_row2.y * g + params.m_row2.z * b;
}
