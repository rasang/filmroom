// Accumulate one wavelength-specific PSF component. Other channels are zero
// weighted; the first dispatch clears all channels before accumulation.
struct Params {
    n_pixels: u32,
    clear_first: u32,
    _pad0: u32,
    _pad1: u32,
    scale: vec4<f32>,
}
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> src: array<f32>;
@group(0) @binding(2) var<storage, read_write> dst: array<f32>;
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    if gid.x >= params.n_pixels { return; }
    let base = gid.x * 3u;
    for (var c = 0u; c < 3u; c++) {
        let value = params.scale[c] * src[base + c];
        if params.clear_first != 0u { dst[base + c] = value; }
        else { dst[base + c] += value; }
    }
}
