// Exact CPU sublayer grain model (`apply_grain_layers`) on density CMY.
//
// CPU equivalent: `spektrafilm_model::grain::apply_grain_layers` →
// `layer_particle_model` per (channel, sublayer), sampling via numpy's exact
// `rk_poisson` / `rk_binomial`. This GPU port reproduces the per-sublayer
// curve interpolation and particle model exactly (same Poisson/Binomial
// distributions as `grain.wgsl`), which is what the CPU export/preview uses
// by default (`grain.sublayers_active == true`).
//
// Dye-cloud spread is approximated by the combined post-blur. Active
// microstructure is applied by separate coordinate-stable field passes.
// This is an f32/statistical model, not a pixel-identical NumPy port.

struct Params {
    n_pixels: u32,
    base_seed: u32,
    // 1 = B&W monochrome grain: all lanes share the channel-0 RNG stream.
    monochrome: u32,
    n_points: u32,
    // +1.0 for negative film, -1.0 for positive (reversal) film.
    sign: f32,
    _pad0: f32,
    _pad1: f32,
    _pad2: f32,
    density_min: vec4<f32>,            // .xyz used
    grain_uniformity: vec4<f32>,       // .xyz used
    // Global pixel index of the first pixel of this dispatch's buffer.
    // Banded (tiled) export processes the image in horizontal bands whose
    // `idx` is band-LOCAL; seeds must use the GLOBAL index so the noise
    // field is continuous across bands. 0 for whole-frame dispatches.
    pixel_offset: u32,
    tile_width: u32,
    full_width: u32,
}

@group(0) @binding(0) var<uniform> params: Params;
// Per curve point, xyz = channel: `sign * normalized_density_curve[ch]`.
@group(0) @binding(1) var<storage, read> axis: array<vec4<f32>>;
// Per curve point & sublayer, xyz = channel: `layers[point*3+sl][ch]`.
@group(0) @binding(2) var<storage, read> layers: array<vec4<f32>>;
// Per (channel*3+sublayer): (dmax, od_particle, particles, dmin).
@group(0) @binding(3) var<storage, read> sl_params: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read_write> density_cmy: array<f32>;

// PCG XSH-RR style 32-bit hash. Cheap to evaluate, well-distributed.
fn pcg(state: u32) -> u32 {
    var s: u32 = state * 747796405u + 2891336453u;
    let word: u32 = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (word >> 22u) ^ word;
}

fn unit_f32(x: u32) -> f32 {
    // 1 / 2^32 → maps u32 uniformly into [0, 1).
    return f32(x) * (1.0 / 4294967296.0);
}

// SplitMix64 finalizer truncated to u32. Used to derive a per-pixel,
// per-channel, per-sublayer seed from the user `base_seed`.
fn splitmix32(x: u32) -> u32 {
    var z: u32 = x;
    z = (z ^ (z >> 16u)) * 0x85ebca6bu;
    z = (z ^ (z >> 13u)) * 0xc2b2ae35u;
    return z ^ (z >> 16u);
}

fn next_u32(state: ptr<function, u32>) -> u32 {
    let s = pcg(*state);
    *state = s;
    return s;
}

// Draw a uniform in [0, 1) from the per-pixel stream.
fn next_unit(state: ptr<function, u32>) -> f32 {
    return unit_f32(next_u32(state));
}

// Box-Muller standard normal. log(0) guarded with a small epsilon.
fn standard_normal(state: ptr<function, u32>) -> f32 {
    let u1 = next_unit(state);
    let u2 = next_unit(state);
    let r = sqrt(-2.0 * log(max(u1, 1e-7)));
    return r * cos(6.28318530717958647 * u2);
}

// Exact Poisson(λ) for small λ via multiplication of uniforms — the same
// algorithm numpy's `rk_poisson_mult` uses for λ < 10. λ ≥ 30 falls back to
// the normal approximation (Poisson(30+) is within ~0.18 skew of Gaussian).
fn poisson_mult(state: ptr<function, u32>, lam: f32) -> u32 {
    if lam <= 0.0 { return 0u; }
    let enlam = exp(-lam);
    var x = 0u;
    var prod = 1.0;
    loop {
        prod = prod * next_unit(state);
        if prod > enlam {
            x = x + 1u;
        } else {
            break;
        }
        // Defensive cap — λ < 30 keeps the expected count ≈ λ, so this is
        // never reached in practice.
        if x >= 4096u { break; }
    }
    return x;
}

fn poisson_sample(state: ptr<function, u32>, lam: f32) -> u32 {
    if lam < 30.0 {
        return poisson_mult(state, lam);
    }
    let z = standard_normal(state);
    return u32(max(0.0, round(lam + sqrt(lam) * z)));
}

// Exact Binomial(n, p) for small n·p via inverse-CDF inversion — the same
// algorithm numpy's `rk_binomial_inversion` uses. Only invoked when
// n·min(p,1−p) ≤ 30, so the PMF walk is short and `qn = (1−p)^n` stays
// representable in f32.
fn binomial_inversion(state: ptr<function, u32>, n: u32, p: f32) -> u32 {
    let q = 1.0 - p;
    let nf = f32(n);
    let qn = exp(nf * log(q));
    let np = nf * p;
    let bound = u32(min(nf, np + 10.0 * sqrt(np * q + 1.0)));
    var x = 0u;
    var px = qn;
    var u = next_unit(state);
    var guard = 0u;
    loop {
        if u <= px { break; }
        x = x + 1u;
        if x > bound {
            // Rare tail miss (floating-point PMF sum < u): restart.
            x = 0u;
            px = qn;
            u = next_unit(state);
        } else {
            u = u - px;
            px = (f32(n - x + 1u) * p * px) / (f32(x) * q);
        }
        guard = guard + 1u;
        if guard >= 8192u { break; }  // defensive
    }
    return x;
}

// Normal-approximation Binomial for large n·p: N(np, √(np(1−p))), rounded and
// clamped to [0, n].
fn binomial_normal(state: ptr<function, u32>, n: u32, p: f32) -> u32 {
    let nf = f32(n);
    let mean = nf * p;
    let variance = nf * p * (1.0 - p);
    if variance <= 0.0 {
        return u32(clamp(round(mean), 0.0, nf));
    }
    let z = standard_normal(state);
    let v = round(mean + sqrt(variance) * z);
    return u32(clamp(v, 0.0, nf));
}

fn binomial_sample(state: ptr<function, u32>, n: u32, p: f32) -> u32 {
    let nf = f32(n);
    if p <= 0.5 {
        if nf * p <= 30.0 {
            return binomial_inversion(state, n, p);
        }
        return binomial_normal(state, n, p);
    }
    // p > 0.5: CPU symmetry — sample the (1−p) tail and reflect, so the
    // highlight-side discrete shape stays exact.
    let q = 1.0 - p;
    if nf * q <= 30.0 {
        return n - binomial_inversion(state, n, q);
    }
    return binomial_normal(state, n, p);
}

// Interpolate `layers[:, sl, ch]` over `axis[:, ch]` at `q`, matching the CPU
// `partition_point` + linear-lerp exactly (clamped at both ends).
fn interp_layer(q: f32, ch: u32, sl: u32) -> f32 {
    let np = params.n_points;
    let a_first = axis[0u][ch];
    let a_last = axis[np - 1u][ch];
    if q <= a_first {
        return layers[sl][ch];
    }
    if q >= a_last {
        return layers[(np - 1u) * 3u + sl][ch];
    }
    // hi = partition_point(axis <= q), clamped to np-1; lo = hi-1.
    var lo = 0u;
    var hi = np;
    while lo < hi {
        let mid = (lo + hi) / 2u;
        if axis[mid][ch] <= q {
            lo = mid + 1u;
        } else {
            hi = mid;
        }
    }
    let h = min(lo, np - 1u);
    let l = h - 1u;
    let ax_lo = axis[l][ch];
    let ax_hi = axis[h][ch];
    let dx = ax_hi - ax_lo;
    var t = 0.0;
    if dx != 0.0 {
        t = (q - ax_lo) / dx;
    }
    let v_lo = layers[l * 3u + sl][ch];
    let v_hi = layers[h * 3u + sl][ch];
    return v_lo + t * (v_hi - v_lo);
}

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let idx = gid.x;
    if idx >= params.n_pixels {
        return;
    }
    let base = idx * 3u;
    // Seed from the GLOBAL pixel index (band-local idx + band offset) so
    // banded exports reproduce the whole-frame noise field exactly;
    // buffer addressing above stays band-local.
    let seed_idx = params.pixel_offset + (idx / params.tile_width) * params.full_width + idx % params.tile_width;
    for (var ch = 0u; ch < 3u; ch = ch + 1u) {
        let d_in = density_cmy[base + ch];
        let q = params.sign * d_in;
        let gu = params.grain_uniformity[ch];
        var sum = 0.0;
        for (var sl = 0u; sl < 3u; sl = sl + 1u) {
            let sp = sl_params[ch * 3u + sl];
            let dmax = sp.x;
            let od_particle = sp.y;
            let particles = sp.z;
            let dmin_sl = sp.w;
            let value = interp_layer(q, ch, sl) + dmin_sl;
            let p = clamp(value / dmax, 1e-6, 1.0 - 1e-6);
            let saturation = 1.0 - p * gu * (1.0 - 1e-6);
            let lambda = particles / saturation;
            var seed_ch = ch;
            if params.monochrome != 0u {
                seed_ch = 0u;
            }
            let layer_seed = seed_ch + sl * 10u + params.base_seed;
            var rng = splitmix32(layer_seed) ^ splitmix32(seed_idx);
            let n_seeds = poisson_sample(&rng, lambda);
            let developed = binomial_sample(&rng, n_seeds, p);
            sum = sum + f32(developed) * od_particle * saturation;
        }
        density_cmy[base + ch] = sum - params.density_min[ch];
    }
}
