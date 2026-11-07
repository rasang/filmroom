// CineStill highlight headroom: legacy user boost first, then an RGB-shared
// gain gated by both scene-relative and middle-gray-relative exposure.
// One invocation owns a whole pixel, avoiding cross-channel read/write races.
struct Params {
    n_values: u32,
    boost_ev: f32,
    boost_range: f32,
    protect_ev: f32,
    headroom_ev: f32,
    headroom_from_ev: f32,
    headroom_to_ev: f32,
    _pad: f32,
}
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> max_raw_buf: array<f32>;
@group(0) @binding(2) var<storage, read_write> data: array<f32>;

fn boost(xv: f32, max_raw: f32) -> f32 {
    if params.boost_ev == 0.0 { return xv; }
    if max_raw == 0.0 { return 0.0; }
    let raw_x0 = clamp(0.184 * exp2(params.protect_ev), 0.0, max_raw);
    if raw_x0 == max_raw { return xv; }
    let a = pow(28.0, 1.0 - params.boost_range);
    let span = 1.0 - raw_x0 / max_raw;
    let denom = exp(a * span) - a * span - 1.0;
    if denom <= 0.0 || xv <= raw_x0 { return xv; }
    let k = (exp2(params.boost_ev) - 1.0) / denom;
    let dx = (xv - raw_x0) / max_raw;
    return xv + k * max_raw * (exp(a * dx) - a * dx - 1.0);
}

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    if gid.x >= params.n_values / 3u { return; }
    let base = gid.x * 3u;
    let original_max = max_raw_buf[0];
    let rgb = vec3<f32>(boost(data[base], original_max),
                       boost(data[base + 1u], original_max),
                       boost(data[base + 2u], original_max));
    let frame_max = boost(original_max, original_max);
    let pixel_max = max(rgb.x, max(rgb.y, rgb.z));
    var gain = 1.0;
    if pixel_max > 0.0 && frame_max > 0.0 {
        let relative_gate = smoothstep(-1.0, -0.25, log2(pixel_max / frame_max));
        let absolute_gate = smoothstep(params.headroom_from_ev, params.headroom_to_ev, log2(pixel_max / 0.184));
        gain = exp2(params.headroom_ev * relative_gate * absolute_gate);
    }
    data[base] = rgb.x * gain;
    data[base + 1u] = rgb.y * gain;
    data[base + 2u] = rgb.z * gain;
}
