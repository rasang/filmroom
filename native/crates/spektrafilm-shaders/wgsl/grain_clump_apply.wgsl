// Multiply density before density_min removal, matching the model order.
struct Params {
    n_pixels: u32,
    _pad0: u32,
    _pad1: u32,
    _pad2: u32,
    offset: vec4<f32>, // .xyz used; pre-divided by 100 by caller
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> glare_amount: array<f32>;
@group(0) @binding(2) var<storage, read_write> image: array<f32>;

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let idx = gid.x;
    if idx >= params.n_pixels { return; }
    let base = idx * 3u;
    for (var ch = 0u; ch < 3u; ch = ch + 1u) {
        let dmin = params.offset[ch];
        image[base + ch] = (image[base + ch] + dmin) * glare_amount[base + ch] - dmin;
    }
}
