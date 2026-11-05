// Per-channel 1D table lookup with linear interpolation.
//
// CPU equivalent: `cpu_backend::CpuBackend::table_lookup` →
// `spektrafilm_math::interp::interp_1d` per channel —
//   xq <= x[0]   → y[0]
//   xq >= x[k-1] → y[k-1]
//   else bisect interval + lerp (searchsorted-right - 1 semantics; exact
//   knots land on t=0 of the upper interval, same value as the CPU's
//   binary-binary_search direct hit).
//
// table_y is row-major [k][3]: row i holds that knot's three channel values.
// `n` is the total number of pixels (not elements).

struct Params {
    n_pixels: u32,
    k: u32,
    _pad: vec2<u32>,
}

@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> src: array<f32>;      // [N*3]
@group(0) @binding(2) var<storage, read> table_x: array<f32>;  // [K]
@group(0) @binding(3) var<storage, read> table_y: array<f32>;  // [K*3]
@group(0) @binding(4) var<storage, read_write> dst: array<f32>; // [N*3]

fn interp_channel(xq: f32, channel: u32) -> f32 {
    let k = params.k;
    if k == 0u {
        return xq;
    }
    if k == 1u {
        return table_y[channel];
    }
    if xq <= table_x[0] {
        return table_y[channel];
    }
    if xq >= table_x[k - 1u] {
        return table_y[(k - 1u) * 3u + channel];
    }
    // Bisect — largest lo with table_x[lo] <= xq (searchsorted right - 1).
    var lo = 0u;
    var hi = k;
    loop {
        if lo + 1u >= hi {
            break;
        }
        let mid = (lo + hi) / 2u;
        if table_x[mid] <= xq {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    let xa_lo = table_x[lo];
    let xa_hi = table_x[lo + 1u];
    let dx = xa_hi - xa_lo;
    let frac = select(0.0, (xq - xa_lo) / dx, dx != 0.0);
    let y0 = table_y[lo * 3u + channel];
    let y1 = table_y[(lo + 1u) * 3u + channel];
    return y0 + frac * (y1 - y0);
}

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let idx = gid.x;
    if idx >= params.n_pixels {
        return;
    }
    let base = idx * 3u;
    dst[base] = interp_channel(src[base], 0u);
    dst[base + 1u] = interp_channel(src[base + 1u], 1u);
    dst[base + 2u] = interp_channel(src[base + 2u], 2u);
}
