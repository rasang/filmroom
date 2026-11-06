// Poisson-binomial film grain on density CMY.
//
// CPU equivalent: `spektrafilm_model::grain::apply_grain_to_density` →
// `layer_particle_model`, which samples via numpy's exact `rk_poisson` /
// `rk_binomial` (`spektrafilm_math::numpy_rng`). This GPU port matches the
// CPU *distribution* exactly in the regimes where a normal approximation is
// visibly wrong, and keeps the cheap normal approximation where it is
// statistically indistinguishable:
//
//   * Poisson(λ):  λ < 30  → exact multiplication-of-uniforms (same algorithm
//                  the CPU uses for λ < 10; the CPU's λ ≥ 10 PTRS is also an
//                  exact Poisson, so the distributions agree). λ ≥ 30 →
//                  N(λ, √λ) normal approx (Poisson skewness 1/√λ ≤ 0.18).
//   * Binomial(n,p): n·min(p,1−p) ≤ 30 → exact inverse-CDF (same as CPU);
//                  otherwise N(np, np(1−p)) normal approx. The CPU's p>0.5
//                  symmetry (n − Binomial(n, 1−p)) is mirrored so highlights
//                  keep the correct left-skewed discrete shape.
//
// Per-pixel deterministic RNG via PCG-hashed seeds; same `base_seed` + same
// image dimensions reproduce the same noise. The per-pixel stream differs
// from the CPU's sequential MT19937 (which draws all Poisson then all
// Binomial), so the noise is not bit-identical — but it is drawn from the
// same Poisson-Binomial distribution, so grain granularity, histogram and
// spatial statistics match the CPU export.
//
// Reads `density_cmy` and writes the post-grain density back to the
// same buffer (read_write). Caller is responsible for the optional
// `grain_blur` post-pass.

struct Params {
    n_pixels: u32,
    base_seed: u32,
    n_sub_layers: u32,
    // 1 = B&W monochrome grain: all lanes share the channel-0 RNG stream,
    // producing one noise field (upstream n_channels==1 has a single
    // emulsion). 0 = independent per-channel streams (colour).
    monochrome: u32,
    density_min: vec4<f32>,            // .xyz used
    density_max: vec4<f32>,            // .xyz used (already includes density_min)
    n_particles_per_pixel: vec4<f32>,  // .xyz used (already divided by n_sub_layers)
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
@group(0) @binding(1) var<storage, read_write> density_cmy: array<f32>;

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

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let idx = gid.x;
    if idx >= params.n_pixels { return; }
    let base = idx * 3u;
    // Seed from the GLOBAL pixel index (band-local idx + band offset) so
    // banded exports reproduce the whole-frame noise field exactly;
    // buffer addressing above stays band-local.
    let seed_idx = params.pixel_offset + (idx / params.tile_width) * params.full_width + idx % params.tile_width;

    let n_sl_f = f32(params.n_sub_layers);
    let n_sl = i32(params.n_sub_layers);

    for (var ch_i: i32 = 0; ch_i < 3; ch_i++) {
        let ch = u32(ch_i);
        let dmin = params.density_min[ch];
        let dmax = params.density_max[ch];
        let npp = params.n_particles_per_pixel[ch];
        let gu = params.grain_uniformity[ch];
        let od_particle = dmax / npp;

        // CPU adds density_min before grain calc, subtracts after.
        let d_in = density_cmy[base + ch] + dmin;
        let p_raw = d_in / dmax;
        let p = clamp(p_raw, 1e-6, 1.0 - 1e-6);
        let saturation = 1.0 - p * gu * (1.0 - 1e-6);
        let lambda = npp / saturation;

        var sum: f32 = 0.0;
        for (var sl: i32 = 0; sl < n_sl; sl++) {
            // Matches the CPU stream split: seed = ch + sl*10, then mix
            // with pixel index. Use SplitMix to decorrelate small ints
            // before xor-combining. Monochrome (B&W) shares channel 0's
            // stream across all lanes.
            var seed_ch = u32(ch_i);
            if params.monochrome != 0u {
                seed_ch = 0u;
            }
            let layer_seed = seed_ch + u32(sl) * 10u + params.base_seed;
            var rng = splitmix32(layer_seed) ^ splitmix32(seed_idx);

            // Poisson(λ) particle count, then Binomial(n, p) developed count —
            // exact samplers in the small-λ / small-n·p regime so the grain
            // statistics match the CPU export.
            let n_seeds = poisson_sample(&rng, lambda);
            let developed = binomial_sample(&rng, n_seeds, p);
            sum = sum + f32(developed) * od_particle * saturation;
        }

        density_cmy[base + ch] = sum / n_sl_f - dmin;
    }
}
