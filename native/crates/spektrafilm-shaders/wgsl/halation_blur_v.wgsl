// Match CPU boundary convention: FIR σ<3 reflects; wider IIR replicates.
// Separable Gaussian blur — vertical pass.
// Pairs with `gaussian_blur_h.wgsl`. Reads a column, writes one pixel.

struct Params {
    width: u32,
    height: u32,
    radius: u32,
    flags: u32, // bit 0: reflect; bits 1..2: selected RGB channel
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> input: array<f32>;
@group(0) @binding(2) var<storage, read> kernel_buf: array<f32>;
@group(0) @binding(3) var<storage, read_write> output: array<f32>;

fn reflect_index(index: i32, length: i32) -> u32 {
    let period = length * 2i;
    var folded = index % period;
    if folded < 0i { folded += period; }
    if folded >= length { return u32(period - 1i - folded); }
    return u32(folded);
}

@compute @workgroup_size(16, 16)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let x = gid.x;
    let y = gid.y;
    if x >= params.width || y >= params.height {
        return;
    }

    let channel = params.flags >> 1u;
    var sum = 0.0;
    let kernel_size = 2u * params.radius + 1u;
    let h_i32 = i32(params.height);
    for (var k = 0u; k < kernel_size; k++) {
        let dy = i32(k) - i32(params.radius);
        let sy_signed = i32(y) + dy;
        var sy = u32(clamp(sy_signed, 0i, h_i32 - 1i));
        if (params.flags & 1u) != 0u { sy = reflect_index(sy_signed, h_i32); }
        let idx = (sy * params.width + x) * 3u;
        let w = kernel_buf[k];
        sum += w * input[idx + channel];
    }

    let out_idx = (y * params.width + x) * 3u;
    // Other channels are intentionally untouched; consumers select this channel.
    output[out_idx + channel] = sum;
}
