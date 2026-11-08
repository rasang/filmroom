// Omitted-film grain: preserve the unmodified log-RGB image, synthesize grain
// on a constant donor reference, then apply ONLY its density residual.
// Constant reference avoids spatial blur of the source image. Baseline is the
// matching clean donor expectation supplied by the core; no donor colour
// transform or density curve is applied to the source.
struct Params {
    n_pixels: u32,
    restore: u32,
    _pad0: u32,
    _pad1: u32,
    density: vec4<f32>,
    baseline: vec4<f32>,
    amount: f32,
    _pad2: f32,
    _pad3: f32,
    _pad4: f32,
}
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read_write> original_log_rgb: array<f32>;
@group(0) @binding(2) var<storage, read_write> image: array<f32>;

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let i=gid.x;
    if (i >= params.n_pixels) { return; }
    for (var c=0u; c<3u; c=c+1u) {
        let k=i*3u+c;
        if (params.restore == 0u) {
            original_log_rgb[k]=image[k];
            image[k]=params.density[c];
        } else {
            image[k]=original_log_rgb[k]-clamp((image[k]-params.baseline[c])*params.amount,-2.0,2.0);
        }
    }
}
