// Halation, optical diffusion, and blur effects.

use spektrafilm_gpu::ComputeBackend;
use spektrafilm_math::image::ImageBuf;
use spektrafilm_math::precision::{Scalar, ZERO, from_f32, from_f64};

/// Apply unsharp mask to an image. `backend` provides the Gaussian blur
/// implementation (CPU rayon or wgpu compute shader).
pub fn apply_unsharp_mask(
    image: &ImageBuf,
    sigma: f32,
    amount: f32,
    backend: &dyn ComputeBackend,
) -> ImageBuf {
    if sigma <= 0.0 || amount <= 0.0 {
        return image.clone();
    }
    let blurred = backend.gaussian_blur(image, sigma);
    let amount_s = from_f32(amount);
    let mut result = image.clone();
    for (r, (o, b)) in result
        .data
        .iter_mut()
        .zip(image.data.iter().zip(blurred.data.iter()))
    {
        *r = o + amount_s * (o - b);
    }
    result
}

/// Apply Gaussian blur in physical units (micrometers).
pub fn apply_gaussian_blur_um(
    image: &ImageBuf,
    sigma_um: f32,
    pixel_size_um: f32,
    backend: &dyn ComputeBackend,
) -> ImageBuf {
    let sigma_px = sigma_um / pixel_size_um;
    if sigma_px > 0.0 {
        backend.gaussian_blur(image, sigma_px)
    } else {
        image.clone()
    }
}

/// Apply in-emulsion scatter and back-reflection halation.
///
/// Port of Python `apply_halation_um`.
///
/// Ordering: scatter → halation.
/// Scatter: energy-preserving mixture of Gaussian core + exponential tail.
/// Halation: additive multi-bounce sum of Gaussians with sqrt(k)-spaced widths.
#[allow(clippy::too_many_arguments)]
pub fn apply_halation_um(
    raw: &ImageBuf,
    pixel_size_um: f32,
    scatter_amount: f64,
    scatter_spatial_scale: f64,
    scatter_core_um: [f64; 3],
    scatter_tail_um: [f64; 3],
    scatter_tail_weight: [f64; 3],
    halation_amount: f64,
    halation_spatial_scale: f64,
    halation_strength: [f64; 3],
    halation_first_sigma_um: [f64; 3],
    halation_n_bounces: u32,
    halation_bounce_decay: f64,
    halation_renormalize: bool,
    _backend: &dyn ComputeBackend,
) -> ImageBuf {
    // Per-channel implementation matching Python's `apply_halation_um`.
    // The `_backend` argument is retained for API compat with the GPU
    // resident path; on the CPU export path we operate channel-by-
    // channel using `gaussian_blur_channel` and
    // `exponential_filter_channel` so that:
    //   * scatter blur σ is per-channel (Python passes a length-3
    //     `sigma_c_px` array to `fast_gaussian_filter`).
    //   * scatter tail is the proper exponential filter (3-Gaussian
    //     mixture, matching Python `fast_exponential_filter`).
    //   * halation σ is per-channel.
    //   * halation strength is per-channel.
    //   * tail weight is per-channel.
    // i.e. every place Python takes a length-3 array, we treat it as a
    // length-3 array, not as `(sum / 3.0)`.
    use spektrafilm_math::gaussian::{exponential_filter_channel, gaussian_blur_channel};

    let w = raw.width;
    let h = raw.height;
    let n_pix = (w as usize) * (h as usize);
    let mut channels: [Vec<Scalar>; 3] = [
        raw.extract_channel(0),
        raw.extract_channel(1),
        raw.extract_channel(2),
    ];

    // 1. Scatter pass — per-channel core gaussian + tail exponential.
    // f64 sigmas/lambdas — Python casts these via
    // `np.asarray(..., dtype=np.float64) * s_scale / pixel_size_um`.
    let pix_um_f64 = pixel_size_um as f64;
    if scatter_amount > 0.0 {
        for c in 0..3 {
            let sigma_c_px_f64 = scatter_core_um[c] * scatter_spatial_scale / pix_um_f64;
            let lambda_t_px_f64 = scatter_tail_um[c] * scatter_spatial_scale / pix_um_f64;
            if sigma_c_px_f64 <= 0.0 && lambda_t_px_f64 <= 0.0 {
                continue;
            }
            // gaussian_blur/exponential_filter take f32 sigma — narrow at the
            // boundary (the kernel itself produces the same bits for any
            // f32-representable sigma, this is just so we don't break the API).
            let sigma_c_px = sigma_c_px_f64.max(1e-6) as f32;
            let lambda_t_px = lambda_t_px_f64.max(1e-6) as f32;
            let core = gaussian_blur_channel(&channels[c], w, h, sigma_c_px);
            let tail = exponential_filter_channel(&channels[c], w, h, lambda_t_px);
            let one = from_f64(1.0);
            let wt = from_f64(scatter_tail_weight[c]);
            let sa = from_f64(scatter_amount);
            for i in 0..n_pix {
                let scattered = (one - wt) * core[i] + wt * tail[i];
                channels[c][i] = (one - sa) * channels[c][i] + sa * scattered;
            }
        }
    }

    // 2. Halation pass — per-channel σ + per-channel strength.
    let a_tot: [f64; 3] = [
        halation_strength[0] * halation_amount,
        halation_strength[1] * halation_amount,
        halation_strength[2] * halation_amount,
    ];

    if halation_n_bounces >= 1 && (a_tot[0] > 0.0 || a_tot[1] > 0.0 || a_tot[2] > 0.0) {
        let n_bounces = halation_n_bounces as usize;
        // Decay computed in f64 to match Python's `rho ** (k - 1)` and
        // subsequent normalize-by-sum at full f64 precision.
        let mut decay = vec![0.0f64; n_bounces];
        for (k, slot) in decay.iter_mut().enumerate() {
            *slot = halation_bounce_decay.powi(k as i32);
        }
        let decay_sum: f64 = decay.iter().sum();
        for d in &mut decay {
            *d /= decay_sum;
        }

        for c in 0..3 {
            if a_tot[c] == 0.0 {
                continue;
            }
            let sigma_first_px_f64 =
                halation_first_sigma_um[c] * halation_spatial_scale / pix_um_f64;
            if sigma_first_px_f64 <= 0.0 {
                continue;
            }
            let mut hb = vec![ZERO; n_pix];
            for (k, &wk) in decay.iter().enumerate() {
                let sigma_k = (sigma_first_px_f64 * ((k as f64) + 1.0).sqrt()).max(1e-6) as f32;
                let blurred = gaussian_blur_channel(&channels[c], w, h, sigma_k);
                let wk_s = from_f64(wk);
                for i in 0..n_pix {
                    hb[i] += wk_s * blurred[i];
                }
            }
            let a_c = from_f64(a_tot[c]);
            for i in 0..n_pix {
                channels[c][i] += a_c * hb[i];
            }
        }

        if halation_renormalize {
            let one = from_f64(1.0);
            for c in 0..3 {
                let denom = one + from_f64(a_tot[c]);
                for v in channels[c].iter_mut() {
                    *v /= denom;
                }
            }
        }
    }

    // Pack channels back into a single interleaved ImageBuf.
    let mut out = ImageBuf::new(w, h);
    for c in 0..3 {
        out.write_channel(c, &channels[c]);
    }
    out
}

// ===========================================================================
// Lens diffusion filter (Black Pro Mist family).
//
// Faithful port of `spektrafilm/model/diffusion.py::apply_diffusion_filter_um`
// (v0.3.2). The PSF is a per-channel sum of 2D isotropic exponentials in
// three groups {core, halo, bloom}; the halo is colour-tinted by an
// energy-conserving "warmth" redistribution across its sub-components. The
// effect is the energy-conserving convex combination
//     E_out = (1 - p_s) * E_in + p_s * (K_s * E_in)
// with p_s the deflected-photon fraction from strength+family. Applied via
// FFT convolution (`fft_conv::convolve2d_reflect`) to reproduce Python's
// reflect-pad + fftconvolve('same') exactly.
// ===========================================================================

use spektrafilm_math::fft_conv::convolve2d_reflect;

#[derive(Clone, Copy)]
struct GroupCfg {
    lambda_um: f64,
    spread: f64,
    n_components: usize,
    /// Power-law tail exponent; only meaningful for the bloom group.
    alpha: f64,
}

#[derive(Clone, Copy)]
struct FamilyShape {
    core: GroupCfg,
    halo: GroupCfg,
    bloom: GroupCfg,
    w_c: f64,
    w_h: f64,
    w_b: f64,
    halo_warmth_base: f64,
    /// Per-family scaling on the shared strength→scatter saturation table.
    total_gain: f64,
}

/// Borrowed diffusion-filter parameters (mirrors core's `DiffusionFilterParams`
/// without the dependency-inverting type coupling). All values f64.
pub struct DiffusionFilter<'a> {
    pub family: &'a str,
    pub strength: f64,
    pub spatial_scale: f64,
    pub halo_warmth: f64,
    pub core_intensity: f64,
    pub core_size: f64,
    pub halo_intensity: f64,
    pub halo_size: f64,
    pub bloom_intensity: f64,
    pub bloom_size: f64,
}

/// Per-channel warmth axis: warmth>0 pushes warm light (R, slight G) to the
/// outer halo and cool (B) to the inner. Matches `_HALO_CHANNEL_WARMTH_AXIS`.
const HALO_CHANNEL_WARMTH_AXIS: [f64; 3] = [1.30, 0.15, -1.45];

/// Strength→deflected-fraction table (commercial filter stops), log2-interpolated.
const STRENGTH_BREAKPOINTS: [f64; 5] = [0.125, 0.25, 0.5, 1.0, 2.0];
const STRENGTH_TOTAL_FRACTION: [f64; 5] = [0.10, 0.20, 0.35, 0.55, 0.75];

fn family_shape(family: &str) -> Option<FamilyShape> {
    let s = match family {
        "glimmerglass" => FamilyShape {
            core: GroupCfg {
                lambda_um: 10.0,
                spread: 1.5,
                n_components: 2,
                alpha: 0.0,
            },
            halo: GroupCfg {
                lambda_um: 50.0,
                spread: 2.0,
                n_components: 3,
                alpha: 0.0,
            },
            bloom: GroupCfg {
                lambda_um: 260.0,
                spread: 2.5,
                n_components: 4,
                alpha: 3.2,
            },
            w_c: 0.60,
            w_h: 0.30,
            w_b: 0.10,
            halo_warmth_base: 0.0,
            total_gain: 0.65,
        },
        "black_pro_mist" => FamilyShape {
            core: GroupCfg {
                lambda_um: 16.0,
                spread: 1.5,
                n_components: 2,
                alpha: 0.0,
            },
            halo: GroupCfg {
                lambda_um: 95.0,
                spread: 2.0,
                n_components: 3,
                alpha: 0.0,
            },
            bloom: GroupCfg {
                lambda_um: 380.0,
                spread: 2.5,
                n_components: 4,
                alpha: 3.5,
            },
            w_c: 0.40,
            w_h: 0.47,
            w_b: 0.13,
            halo_warmth_base: 0.65,
            total_gain: 0.75,
        },
        "pro_mist" => FamilyShape {
            core: GroupCfg {
                lambda_um: 14.0,
                spread: 1.5,
                n_components: 2,
                alpha: 0.0,
            },
            halo: GroupCfg {
                lambda_um: 150.0,
                spread: 2.0,
                n_components: 3,
                alpha: 0.0,
            },
            bloom: GroupCfg {
                lambda_um: 650.0,
                spread: 2.5,
                n_components: 4,
                alpha: 2.9,
            },
            w_c: 0.28,
            w_h: 0.42,
            w_b: 0.30,
            halo_warmth_base: 0.40,
            total_gain: 1.05,
        },
        "cinebloom" => FamilyShape {
            core: GroupCfg {
                lambda_um: 20.0,
                spread: 1.5,
                n_components: 2,
                alpha: 0.0,
            },
            halo: GroupCfg {
                lambda_um: 200.0,
                spread: 2.0,
                n_components: 3,
                alpha: 0.0,
            },
            bloom: GroupCfg {
                lambda_um: 1000.0,
                spread: 2.5,
                n_components: 4,
                alpha: 2.5,
            },
            w_c: 0.22,
            w_h: 0.30,
            w_b: 0.48,
            halo_warmth_base: 0.85,
            total_gain: 1.00,
        },
        _ => return None,
    };
    Some(s)
}

/// Apply per-group intensity (weight) and size (lambda) multipliers, then
/// renormalise the group weights to sum to 1. Mirrors `_resolve_family_cfg`.
fn resolve_family_cfg(mut s: FamilyShape, df: &DiffusionFilter) -> FamilyShape {
    let (ci, hi, bi) = (df.core_intensity, df.halo_intensity, df.bloom_intensity);
    let (cs, hs, bs) = (df.core_size, df.halo_size, df.bloom_size);
    if ci == 1.0 && hi == 1.0 && bi == 1.0 && cs == 1.0 && hs == 1.0 && bs == 1.0 {
        return s;
    }
    let w_c = s.w_c * ci.max(0.0);
    let w_h = s.w_h * hi.max(0.0);
    let w_b = s.w_b * bi.max(0.0);
    let total = w_c + w_h + w_b;
    if total <= 0.0 {
        return s;
    }
    s.core.lambda_um *= cs.max(1e-6);
    s.halo.lambda_um *= hs.max(1e-6);
    s.bloom.lambda_um *= bs.max(1e-6);
    s.w_c = w_c / total;
    s.w_h = w_h / total;
    s.w_b = w_b / total;
    s
}

/// Largest lambda in the (resolved) bloom progression, image-plane μm.
fn bloom_max_lambda_um(cfg: &FamilyShape) -> f64 {
    cfg.bloom.lambda_um * cfg.bloom.spread
}

/// Deflected-photon fraction p_s for a strength + family. Mirrors `_strength_to_scatter`.
fn strength_to_scatter(strength: f64, gain: f64) -> f64 {
    if strength <= 0.0 {
        return 0.0;
    }
    let log_strength = strength.max(1e-6).log2();
    let log_breaks: Vec<f64> = STRENGTH_BREAKPOINTS.iter().map(|b| b.log2()).collect();
    let base_total = interp(log_strength, &log_breaks, &STRENGTH_TOTAL_FRACTION);
    (base_total * gain).clamp(0.0, 0.99)
}

/// numpy.interp: clamped piecewise-linear interpolation on a sorted table.
fn interp(x: f64, xs: &[f64], ys: &[f64]) -> f64 {
    if x <= xs[0] {
        return ys[0];
    }
    let last = xs.len() - 1;
    if x >= xs[last] {
        return ys[last];
    }
    for i in 1..xs.len() {
        if x <= xs[i] {
            let t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
            return ys[i - 1] + t * (ys[i] - ys[i - 1]);
        }
    }
    ys[last]
}

/// `n` evenly spaced points in `[a, b]` inclusive (numpy.linspace).
fn linspace(a: f64, b: f64, n: usize) -> Vec<f64> {
    if n == 1 {
        return vec![a];
    }
    (0..n)
        .map(|i| a + (b - a) * (i as f64) / ((n - 1) as f64))
        .collect()
}

/// Expand a {core|halo|bloom} group into (sub-component lambdas_um, weights
/// summing to 1). Mirrors `_expand_group`.
fn expand_group(g: &GroupCfg, is_bloom: bool) -> (Vec<f64>, Vec<f64>) {
    let n = g.n_components.max(1);
    if n == 1 || g.spread <= 1.0 {
        return (vec![g.lambda_um], vec![1.0]);
    }
    let log_lo = (g.lambda_um / g.spread).ln();
    let log_hi = (g.lambda_um * g.spread).ln();
    let lambdas: Vec<f64> = linspace(log_lo, log_hi, n)
        .iter()
        .map(|x| x.exp())
        .collect();
    let mut weights: Vec<f64> = if is_bloom {
        lambdas.iter().map(|&l| l.powf(2.0 - g.alpha)).collect()
    } else {
        vec![1.0; n]
    };
    let s: f64 = weights.iter().sum();
    for w in &mut weights {
        *w /= s;
    }
    (lambdas, weights)
}

/// Energy-conserving per-channel halo weight redistribution. Returns one
/// weight vector per channel (R, G, B). Mirrors `_halo_channel_weights`.
fn halo_channel_weights(weights: &[f64], warmth: f64) -> [Vec<f64>; 3] {
    let n = weights.len();
    if n < 2 {
        return [weights.to_vec(), weights.to_vec(), weights.to_vec()];
    }
    let warmth = warmth.clamp(-1.5, 1.5);
    let mut g = linspace(-1.0, 1.0, n);
    let wsum: f64 = weights.iter().sum();
    let g_mean: f64 = g.iter().zip(weights).map(|(gi, wi)| gi * wi).sum::<f64>() / wsum;
    for gi in &mut g {
        *gi -= g_mean;
    }
    let target_total = wsum;
    let mut out = [vec![0.0; n], vec![0.0; n], vec![0.0; n]];
    for c in 0..3 {
        let raw: Vec<f64> = (0..n)
            .map(|k| (weights[k] * (1.0 + warmth * HALO_CHANNEL_WARMTH_AXIS[c] * g[k])).max(0.0))
            .collect();
        let s: f64 = raw.iter().sum();
        if s > 0.0 {
            for k in 0..n {
                out[c][k] = raw[k] * (target_total / s);
            }
        } else {
            out[c].copy_from_slice(weights);
        }
    }
    out
}

/// Σ_k weight_k · exp(-r/λ_k) / (2π λ_k²) over a radius grid.
fn exp_sum(r: &[f64], lambdas_px: &[f64], weights: &[f64]) -> Vec<f64> {
    let mut total = vec![0.0f64; r.len()];
    for (&wk, &lk0) in weights.iter().zip(lambdas_px) {
        let lk = lk0.max(1e-6);
        let denom = 2.0 * std::f64::consts::PI * lk * lk;
        for (t, &ri) in total.iter_mut().zip(r) {
            *t += wk * (-ri / lk).exp() / denom;
        }
    }
    total
}

/// Core / per-channel halo / bloom radial contributions (family weights
/// folded in). Mirrors `_radial_components`. `halo_warmth` is the effective
/// warmth (family base already added by the caller).
fn radial_components(
    r: &[f64],
    cfg: &FamilyShape,
    spatial_scale: f64,
    pixel_size_um: f64,
    halo_warmth: f64,
) -> (Vec<f64>, [Vec<f64>; 3], Vec<f64>) {
    let spatial_scale = spatial_scale.max(1e-6);
    let (core_l, core_w) = expand_group(&cfg.core, false);
    let (halo_l, halo_w) = expand_group(&cfg.halo, false);
    let (bloom_l, bloom_w) = expand_group(&cfg.bloom, true);
    let halo_per_ch = halo_channel_weights(&halo_w, halo_warmth);

    let to_px = |ls: &[f64]| -> Vec<f64> {
        ls.iter()
            .map(|&l| l * spatial_scale / pixel_size_um)
            .collect()
    };
    let core_px = to_px(&core_l);
    let halo_px = to_px(&halo_l);
    let bloom_px = to_px(&bloom_l);

    let mut core = exp_sum(r, &core_px, &core_w);
    for v in &mut core {
        *v *= cfg.w_c;
    }
    let mut bloom = exp_sum(r, &bloom_px, &bloom_w);
    for v in &mut bloom {
        *v *= cfg.w_b;
    }
    let halo = std::array::from_fn(|c| {
        let mut h = exp_sum(r, &halo_px, &halo_per_ch[c]);
        for v in &mut h {
            *v *= cfg.w_h;
        }
        h
    });
    (core, halo, bloom)
}

/// Per-channel 2D PSF (k×k each), sum-normalised per channel. Mirrors
/// `diffusion_filter_psf`; `halo_warmth` is the user knob (family base added here).
fn diffusion_filter_psf(
    k: usize,
    cfg: &FamilyShape,
    spatial_scale: f64,
    pixel_size_um: f64,
    halo_warmth: f64,
) -> [Vec<f64>; 3] {
    let center = (k / 2) as f64;
    let mut r = vec![0.0f64; k * k];
    for y in 0..k {
        for x in 0..k {
            let dx = x as f64 - center;
            let dy = y as f64 - center;
            r[y * k + x] = (dx * dx + dy * dy).sqrt();
        }
    }
    let effective_warmth = cfg.halo_warmth_base + halo_warmth;
    let (core, halo, bloom) =
        radial_components(&r, cfg, spatial_scale, pixel_size_um, effective_warmth);

    std::array::from_fn(|c| {
        let mut psf: Vec<f64> = (0..k * k)
            .map(|p| core[p] + halo[c][p] + bloom[p])
            .collect();
        let s: f64 = psf.iter().sum();
        if s > 0.0 {
            for v in &mut psf {
                *v /= s;
            }
        }
        psf
    })
}

/// Apply a lens diffusion-filter PSF to an RGB image. Port of Python's
/// `apply_diffusion_filter_um`. Returns the input unchanged when the filter
/// is effectively a no-op (strength/spatial_scale ≤ 0, p_s ≤ 0, or an
/// unknown family). `pixel_size_um` is the image-plane sampling pitch.
pub fn apply_diffusion_filter_um(
    image: &ImageBuf,
    df: &DiffusionFilter,
    pixel_size_um: f64,
) -> ImageBuf {
    if df.strength <= 0.0 || df.spatial_scale <= 0.0 {
        return image.clone();
    }
    let Some(base) = family_shape(df.family) else {
        return image.clone();
    };
    let p_s = strength_to_scatter(df.strength, base.total_gain);
    if p_s <= 0.0 {
        return image.clone();
    }
    let cfg = resolve_family_cfg(base, df);

    let w = image.width as usize;
    let h = image.height as usize;

    // Kernel radius: 8·λ_max captures 99.95% of a 2D exponential's energy.
    let bloom_max_px = bloom_max_lambda_um(&cfg) * df.spatial_scale / pixel_size_um;
    let mut radius = (8.0 * bloom_max_px).max(5.0).ceil() as usize;
    let cap = (h.min(w) / 2).saturating_sub(1).max(1);
    radius = radius.min(cap);
    let k = 2 * radius + 1;

    let psf = diffusion_filter_psf(k, &cfg, df.spatial_scale, pixel_size_um, df.halo_warmth);

    let mut out = ImageBuf::new(image.width, image.height);
    for c in 0..3 {
        let chan_s = image.extract_channel(c);
        let chan: Vec<f64> = chan_s.iter().map(|&v| v as f64).collect();
        let blurred = convolve2d_reflect(&chan, h, w, &psf[c], k);
        let mixed: Vec<Scalar> = (0..h * w)
            .map(|i| from_f64((1.0 - p_s) * chan[i] + p_s * blurred[i]))
            .collect();
        out.write_channel(c, &mixed);
    }
    out
}

/// Highlight-boost tone curve. Port of Python `boost_highlights`
/// (numba_boost_hightlights.py): reconstructs pre-clip highlight irradiance
/// before scatter/halation. Identity below `midgray·2^protect_ev`; above it
/// adds `boost_scale·(exp(a·dx) − a·dx − 1)`, normalised so the brightest
/// pixel gains exactly `2^boost_ev`. `midgray` is fixed at 0.184 (the value
/// the filming stage uses). A no-op when `boost_ev == 0`.
pub fn boost_highlights(
    image: &ImageBuf,
    boost_ev: f64,
    boost_range: f64,
    protect_ev: f64,
) -> ImageBuf {
    use rayon::prelude::*;
    const MIDGRAY: f64 = 0.184;
    if boost_ev == 0.0 {
        return image.clone();
    }
    let max_raw = image
        .data
        .iter()
        .map(|&v| v as f64)
        .fold(f64::NEG_INFINITY, f64::max);
    if max_raw == 0.0 {
        return ImageBuf::new(image.width, image.height);
    }
    let raw_x0 = (MIDGRAY * 2f64.powf(protect_ev)).clamp(0.0, max_raw);
    if raw_x0 == max_raw {
        return image.clone();
    }
    let a = 28f64.powf(1.0 - boost_range);
    let x0 = raw_x0 / max_raw;
    let denom = (a * (1.0 - x0)).exp() - a * (1.0 - x0) - 1.0;
    if denom <= 0.0 {
        return image.clone();
    }
    let k = (2f64.powf(boost_ev) - 1.0) / denom;
    let inv_max_raw = 1.0 / max_raw;
    let boost_scale = k * max_raw;

    let mut out = image.clone();
    out.data.par_iter_mut().for_each(|v| {
        let xv = *v as f64;
        let nv = if xv <= raw_x0 {
            xv
        } else {
            let dx = (xv - raw_x0) * inv_max_raw;
            xv + boost_scale * ((a * dx).exp() - a * dx - 1.0)
        };
        *v = from_f64(nv);
    });
    out
}

/// Downsample an image by an integer factor via box (area) averaging.
fn downsample_area(img: &ImageBuf, d: usize) -> ImageBuf {
    use rayon::prelude::*;
    let w = img.width as usize;
    let h = img.height as usize;
    let sw = w.div_ceil(d);
    let sh = h.div_ceil(d);
    let mut out = vec![ZERO; sw * sh * 3];
    out.par_chunks_mut(sw * 3)
        .enumerate()
        .for_each(|(sy, row)| {
            for sx in 0..sw {
                let mut acc = [0.0f64; 3];
                let mut cnt = 0.0f64;
                for yy in (sy * d)..((sy + 1) * d).min(h) {
                    for xx in (sx * d)..((sx + 1) * d).min(w) {
                        let i = (yy * w + xx) * 3;
                        acc[0] += img.data[i] as f64;
                        acc[1] += img.data[i + 1] as f64;
                        acc[2] += img.data[i + 2] as f64;
                        cnt += 1.0;
                    }
                }
                for c in 0..3 {
                    row[sx * 3 + c] = from_f64(acc[c] / cnt);
                }
            }
        });
    ImageBuf::from_data(sw as u32, sh as u32, out)
}

/// Bilinear upsample a small interleaved-RGB f64 buffer to `out_w × out_h`,
/// inverting the `downsample_area` block mapping (sample centre at
/// `(x + 0.5)/d − 0.5` in small-pixel coords).
fn upsample_bilinear(
    small: &[f64],
    sw: usize,
    sh: usize,
    out_w: usize,
    out_h: usize,
    d: usize,
) -> Vec<f64> {
    use rayon::prelude::*;
    let sample = |sx: usize, sy: usize, c: usize| small[(sy * sw + sx) * 3 + c];
    let mut out = vec![0.0f64; out_w * out_h * 3];
    out.par_chunks_mut(out_w * 3)
        .enumerate()
        .for_each(|(y, row)| {
            let fy = ((y as f64 + 0.5) / d as f64 - 0.5).clamp(0.0, (sh - 1) as f64);
            let y0 = fy.floor() as usize;
            let y1 = (y0 + 1).min(sh - 1);
            let wy = fy - y0 as f64;
            for x in 0..out_w {
                let fx = ((x as f64 + 0.5) / d as f64 - 0.5).clamp(0.0, (sw - 1) as f64);
                let x0 = fx.floor() as usize;
                let x1 = (x0 + 1).min(sw - 1);
                let wx = fx - x0 as f64;
                for c in 0..3 {
                    let top = sample(x0, y0, c) * (1.0 - wx) + sample(x1, y0, c) * wx;
                    let bot = sample(x0, y1, c) * (1.0 - wx) + sample(x1, y1, c) * wx;
                    row[x * 3 + c] = top * (1.0 - wy) + bot * wy;
                }
            }
        });
    out
}

/// Max working-resolution σ for the sum-of-Gaussians path; the downsample
/// factor is `ceil(σ_max / SIGMA_CAP)` so every blur stays at or below it.
/// Raised 32 → 64 so the GPU export downsamples less aggressively and keeps
/// the sharper halo/core structure closer to the CPU exact-FFT reference
/// (at the cost of larger blur kernels on the GPU).
const DIFFUSION_SIGMA_CAP: f64 = 64.0;

/// 6-Gaussian fit of a 2D isotropic exponential PSF `exp(-r/λ)/(2πλ²)`,
/// fitted against its MTF `(1+u²)^(-3/2)` (amplitude, σ/λ). The CPU export
/// convolves the exact exponential via FFT; the previous 3-term fit
/// (`_EXPONENTIAL_GAUSSIAN_FITS[3]`, shared with `exponential_filter_channel`)
/// approximates that MTF to ~1.6e-2 max error, while this 6-term fit reaches
/// ~2.3e-4 (≈70× closer) so the GPU halo shape tracks the CPU FFT reference.
/// Amplitudes sum to ≈1 (energy-preserving); do not renormalize.
const DIFFUSION_EXP_FIT: [(f64, f64); 6] = [
    (0.007123, 0.201227),
    (0.049170, 0.467520),
    (0.176079, 0.843033),
    (0.359777, 1.367643),
    (0.331874, 2.091448),
    (0.075958, 3.113154),
];

/// Decompose the (resolved) diffusion PSF into Gaussian blur components.
/// Returns parallel vectors of (full-resolution σ in px, per-channel
/// coefficient); each PSF sub-component exponential contributes 6 Gaussians.
/// Shared by the CPU-blur and GPU-resident-plan paths.
fn diffusion_components(
    cfg: &FamilyShape,
    df: &DiffusionFilter,
    pixel_size_um: f64,
) -> (Vec<f64>, Vec<[f64; 3]>) {
    let spatial_scale = df.spatial_scale.max(1e-6);
    let (core_l, core_w) = expand_group(&cfg.core, false);
    let (halo_l, halo_w) = expand_group(&cfg.halo, false);
    let (bloom_l, bloom_w) = expand_group(&cfg.bloom, true);
    let effective_warmth = cfg.halo_warmth_base + df.halo_warmth;
    let halo_per_ch = halo_channel_weights(&halo_w, effective_warmth);

    let to_px = |l: f64| l * spatial_scale / pixel_size_um;
    let mut sigmas: Vec<f64> = Vec::new();
    let mut coeffs: Vec<[f64; 3]> = Vec::new();
    let mut push = |lambda: f64, cc: [f64; 3]| {
        let lpx = to_px(lambda);
        for (amp, ratio) in DIFFUSION_EXP_FIT {
            sigmas.push(ratio * lpx);
            coeffs.push([cc[0] * amp, cc[1] * amp, cc[2] * amp]);
        }
    };
    for (k, &lam) in core_l.iter().enumerate() {
        let c = cfg.w_c * core_w[k];
        push(lam, [c, c, c]);
    }
    for (k, &lam) in bloom_l.iter().enumerate() {
        let c = cfg.w_b * bloom_w[k];
        push(lam, [c, c, c]);
    }
    for (k, &lam) in halo_l.iter().enumerate() {
        push(
            lam,
            [
                cfg.w_h * halo_per_ch[0][k],
                cfg.w_h * halo_per_ch[1][k],
                cfg.w_h * halo_per_ch[2][k],
            ],
        );
    }
    (sigmas, coeffs)
}

/// Build the GPU-resident diffusion plan (downsample factor, working dims,
/// p_s, and the working-resolution σ + per-channel coefficient lists) for a
/// `width × height` image. Returns `None` when the filter is a no-op
/// (inactive / p_s ≤ 0 / unknown family). The GPU resident chain consumes
/// this; the math mirrors `apply_diffusion_filter_blur` exactly.
pub fn diffusion_gpu_plan(
    df: &DiffusionFilter,
    pixel_size_um: f64,
    width: u32,
    height: u32,
) -> Option<spektrafilm_gpu::DiffusionGpuPlan> {
    if df.strength <= 0.0 || df.spatial_scale <= 0.0 {
        return None;
    }
    let base = family_shape(df.family)?;
    let p_s = strength_to_scatter(df.strength, base.total_gain);
    if p_s <= 0.0 {
        return None;
    }
    let cfg = resolve_family_cfg(base, df);
    let (sigmas_full, coeffs) = diffusion_components(&cfg, df, pixel_size_um);
    let sigma_max = sigmas_full.iter().cloned().fold(0.0f64, f64::max);
    let d = ((sigma_max / DIFFUSION_SIGMA_CAP).ceil() as usize).max(1);

    Some(spektrafilm_gpu::DiffusionGpuPlan {
        d: d as u32,
        small_w: (width as usize).div_ceil(d) as u32,
        small_h: (height as usize).div_ceil(d) as u32,
        p_s: p_s as f32,
        sigmas: sigmas_full.iter().map(|&s| (s / d as f64) as f32).collect(),
        coeffs: coeffs
            .iter()
            .map(|c| [c[0] as f32, c[1] as f32, c[2] as f32])
            .collect(),
    })
}

/// GPU-friendly diffusion filter via sum-of-Gaussians, computed at a
/// downsampled working resolution so the blur kernels stay small.
///
/// Each PSF sub-component exponential is approximated by the same
/// 3-Gaussian fit `exponential_filter_channel` uses, making the per-channel
/// kernel a weighted sum of Gaussian blurs. Because the halo/bloom are
/// low-frequency, the whole scattered field `K_s * img` is computed on an
/// image downsampled by `d = ceil(σ_max / 32)` (so every blur σ ≤ ~32 px →
/// bounded FIR kernels and tiny buffers), then bilinearly upsampled and
/// mixed `(1−p_s)·img + p_s·upsample(K_s*img_small)`. Visually matches the
/// exact-FFT CPU path for the dominant glow; the only loss is a slight
/// softening of the (small-weight) sharp core inside the scattered
/// fraction when `d > 1`. Used for the live GPU preview; CPU export keeps
/// the exact FFT path.
pub fn apply_diffusion_filter_blur(
    image: &ImageBuf,
    df: &DiffusionFilter,
    pixel_size_um: f64,
    backend: &dyn ComputeBackend,
) -> ImageBuf {
    use rayon::prelude::*;
    if df.strength <= 0.0 || df.spatial_scale <= 0.0 {
        return image.clone();
    }
    let Some(base) = family_shape(df.family) else {
        return image.clone();
    };
    let p_s = strength_to_scatter(df.strength, base.total_gain);
    if p_s <= 0.0 {
        return image.clone();
    }
    let cfg = resolve_family_cfg(base, df);
    let (sigmas_full, coeffs) = diffusion_components(&cfg, df, pixel_size_um);

    // Downsample factor so the largest σ at working resolution ≤ the cap.
    let sigma_max = sigmas_full.iter().cloned().fold(0.0f64, f64::max);
    let d = ((sigma_max / DIFFUSION_SIGMA_CAP).ceil() as usize).max(1);

    let work = if d > 1 {
        downsample_area(image, d)
    } else {
        image.clone()
    };
    let sigmas_work: Vec<f32> = sigmas_full.iter().map(|&s| (s / d as f64) as f32).collect();
    let blurs = backend.gaussian_blur_multi(&work, &sigmas_work);

    // Accumulate the scattered field at working resolution.
    let nwork = work.data.len();
    let mut acc = vec![0.0f64; nwork];
    for (blur, cf) in blurs.iter().zip(coeffs.iter()) {
        acc.par_chunks_mut(3)
            .zip(blur.data.par_chunks(3))
            .for_each(|(a, b)| {
                a[0] += cf[0] * (b[0] as f64);
                a[1] += cf[1] * (b[1] as f64);
                a[2] += cf[2] * (b[2] as f64);
            });
    }

    let w = image.width as usize;
    let h = image.height as usize;
    let scattered = if d > 1 {
        upsample_bilinear(&acc, work.width as usize, work.height as usize, w, h, d)
    } else {
        acc
    };

    let mut out = image.clone();
    out.data
        .par_iter_mut()
        .zip(image.data.par_iter())
        .zip(scattered.par_iter())
        .for_each(|((o, &orig), &s)| {
            *o = from_f64((1.0 - p_s) * (orig as f64) + p_s * s);
        });
    out
}

/// Whole-frame downsample accumulator for the banded diffusion pre-pass.
///
/// The banded GPU chain cannot run a diffusion filter's large-radius blur
/// directly (it is a global, non-bandable effect), so the export taps the
/// chain state at the filter's input point band by band and accumulates the
/// 1/d box downsample here — the exact `downsample_area` semantics (edge
/// blocks are partial), assembled from per-band core rows. At 1/d resolution
/// the whole frame is a few MB, so the subsequent multi-Gaussian blur runs
/// as one small whole-frame operation.
pub struct DownsampleAccumulator {
    sums: Vec<f64>,
    small_w: usize,
    small_h: usize,
    d: usize,
    full_w: usize,
    full_h: usize,
}

impl DownsampleAccumulator {
    /// Zeroed accumulator for a `full_w × full_h` frame downsampled by `d`.
    pub fn new(full_w: usize, full_h: usize, d: usize) -> Self {
        let d = d.max(1);
        let small_w = full_w.div_ceil(d);
        let small_h = full_h.div_ceil(d);
        Self {
            sums: vec![0.0; small_w * small_h * 3],
            small_w,
            small_h,
            d,
            full_w,
            full_h,
        }
    }

    /// Accumulate the core rows of one band tap. `tap` holds global rows
    /// `[tap_y0, tap_y0 + tap.height)`; only `[core_y0, core_y1)` are summed
    /// (the halo rows belong to the neighbouring bands). Core row ranges
    /// across the bands must partition `[0, full_h)` and align to `d` (the
    /// last band may be short), so every small row is fully inside one band.
    pub fn add_band(&mut self, tap: &ImageBuf, tap_y0: usize, core_y0: usize, core_y1: usize) {
        let w = self.full_w;
        let d = self.d;
        let sw = self.small_w;
        for gy in core_y0..core_y1 {
            let sy = gy / d;
            let local = gy - tap_y0;
            debug_assert!(local < tap.height as usize, "tap band does not cover core row");
            let src_row = &tap.data[local * w * 3..(local + 1) * w * 3];
            let dst_row = &mut self.sums[sy * sw * 3..(sy + 1) * sw * 3];
            for x in 0..w {
                let sx = x / d;
                let s = src_row[x * 3..x * 3 + 3].as_ref();
                let dst = &mut dst_row[sx * 3..sx * 3 + 3];
                dst[0] += s[0] as f64;
                dst[1] += s[1] as f64;
                dst[2] += s[2] as f64;
            }
        }
    }

    /// Add a rectangular core [x0,y0,x1,y1] in global coordinates.
    /// Cores must partition the full image; cell boundaries need not align.
    pub fn add_tile(&mut self, tap: &ImageBuf, tap_x: usize, tap_y: usize,
        core: [usize; 4]) -> Option<()> {
        let [x0, y0, x1, y1] = core;
        let tw = tap.width as usize;
        let th = tap.height as usize;
        if x0 > x1 || y0 > y1 || x0 < tap_x || y0 < tap_y
            || x1 > self.full_w || y1 > self.full_h
            || x1 > tap_x.checked_add(tw)? || y1 > tap_y.checked_add(th)?
            || tap.data.len() != tw.checked_mul(th)?.checked_mul(3)? { return None; }
        for y in y0..y1 {
            for x in x0..x1 {
                let src = ((y - tap_y) * tw + x - tap_x) * 3;
                let dst = ((y / self.d) * self.small_w + x / self.d) * 3;
                for c in 0..3 { self.sums[dst + c] += tap.data[src + c] as f64; }
            }
        }
        Some(())
    }

    /// Box-average the sums into the small image, mirroring `downsample_area`
    /// (each small pixel is the mean of its `d×d` block; edge blocks are
    /// partial). Must only be called after every frame row was accumulated
    /// exactly once.
    pub fn finish(&self) -> ImageBuf {
        use rayon::prelude::*;
        let d = self.d;
        let sw = self.small_w;
        let mut out = vec![ZERO; self.small_w * self.small_h * 3];
        out.par_chunks_mut(sw * 3)
            .enumerate()
            .for_each(|(sy, row)| {
                let rows = ((sy + 1) * d).min(self.full_h) - sy * d;
                for sx in 0..sw {
                    let cols = ((sx + 1) * d).min(self.full_w) - sx * d;
                    let cnt = (rows * cols) as f64;
                    let i = (sy * sw + sx) * 3;
                    row[sx * 3] = from_f64(self.sums[i] / cnt);
                    row[sx * 3 + 1] = from_f64(self.sums[i + 1] / cnt);
                    row[sx * 3 + 2] = from_f64(self.sums[i + 2] / cnt);
                }
            });
        ImageBuf::from_data(sw as u32, self.small_h as u32, out)
    }
}

/// Blur the downsampled whole-frame image per the plan and accumulate the
/// weighted scattered field — the `small_acc` of the GPU-resident chain,
/// computed here from the banded pre-pass taps. Returns `small_w*small_h*3`
/// interleaved f32, ready to upload as a `DiffusionScatteredGpu` field.
pub fn blur_scattered_field(
    backend: &dyn ComputeBackend,
    small: &ImageBuf,
    plan: &spektrafilm_gpu::DiffusionGpuPlan,
) -> Vec<f32> {
    blur_scattered_field_controlled(backend, small, plan, || true).expect("uncancelled diffusion")
}

/// Bounded prepass accumulation: releases each component before dispatching
/// the next and honors cooperative cancellation at every safe boundary.
pub fn blur_scattered_field_controlled(
    backend: &dyn ComputeBackend,
    small: &ImageBuf,
    plan: &spektrafilm_gpu::DiffusionGpuPlan,
    mut checkpoint: impl FnMut() -> bool,
) -> Option<Vec<f32>> {
    use rayon::prelude::*;
    if plan.sigmas.len() != plan.coeffs.len() || !checkpoint() { return None; }
    let mut acc = vec![0.0f64; small.data.len()];
    for (&sigma, cf) in plan.sigmas.iter().zip(plan.coeffs.iter()) {
        if !checkpoint() { return None; }
        let blur = backend.gaussian_blur(small, sigma);
        acc.par_chunks_mut(3)
            .zip(blur.data.par_chunks(3))
            .for_each(|(a, b)| {
                a[0] += cf[0] as f64 * (b[0] as f64);
                a[1] += cf[1] as f64 * (b[1] as f64);
                a[2] += cf[2] as f64 * (b[2] as f64);
            });
    }
    if !checkpoint() { return None; }
    Some(acc.iter().map(|&v| v as f32).collect())
}

/// Exact, row-generated PSF for out-of-core convolution. Normalization follows
/// the same row-major f64 sum as `diffusion_filter_psf`; no Gaussian fit or resize.
/// Exact, collision-free identity for the row generator, not a sampled/hash key.
/// Float fields are represented by their complete IEEE754 bits. Version covers
/// fixed numerical algorithms/constants; caller additionally scopes by data revision.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExactDiffusionKernelDescriptor {
    algorithm: &'static str,
    geometry: [usize;2],
    family: Box<str>,
    constructor_bits: [u64;9],
    groups: [[u64;4];3],
    family_bits: [u64;5],
    radius: usize,
    scalar_bits: [u64;4],
    normalization_bits: [u64;3],
}
impl ExactDiffusionKernelDescriptor {
    pub fn storage_bytes(&self)->usize {std::mem::size_of::<Self>()+self.family.len()}
    pub fn geometry(&self)->[usize;2] {self.geometry}
}
pub struct ExactDiffusionKernel {
    pub radius: usize,
    pub scatter: f64,
    cfg: FamilyShape,
    spatial_scale: f64,
    pixel_size_um: f64,
    effective_warmth: f64,
    sums: [f64; 3],
    identity_family: Box<str>,
    identity_parameters: [u64;9],
    identity_geometry: [usize;2],
}
impl ExactDiffusionKernel {
    pub fn new(df: &DiffusionFilter, pixel_size_um: f64, width: usize, height: usize) -> Option<Self> {
        Self::new_cancellable(df,pixel_size_um,width,height,||false).ok().flatten()
    }
    pub fn new_cancellable(df: &DiffusionFilter, pixel_size_um: f64, width: usize, height: usize, mut cancelled:impl FnMut()->bool) -> Result<Option<Self>, &'static str> {
        if width == 0 || height == 0 || !pixel_size_um.is_finite() || pixel_size_um <= 0. || df.strength <= 0. || df.spatial_scale <= 0. { return Ok(None); }
        let Some(base) = family_shape(df.family) else { return Ok(None); };
        let scatter = strength_to_scatter(df.strength, base.total_gain);
        if scatter <= 0. { return Ok(None); }
        let cfg = resolve_family_cfg(base, df);
        let radius = ((8. * bloom_max_lambda_um(&cfg) * df.spatial_scale / pixel_size_um).max(5.).ceil() as usize)
            .min((width.min(height) / 2).saturating_sub(1).max(1));
        let mut result = Self { radius, scatter, cfg, spatial_scale: df.spatial_scale, pixel_size_um,
            effective_warmth: cfg.halo_warmth_base + df.halo_warmth, sums: [0.;3],
            identity_family: df.family.into(), identity_geometry:[width,height],
            identity_parameters:[df.strength,df.spatial_scale,df.halo_warmth,df.core_intensity,df.core_size,df.halo_intensity,df.halo_size,df.bloom_intensity,df.bloom_size].map(f64::to_bits) };
        for y in 0..result.side() {
            if cancelled() { return Err("CANCELLED"); }
            let rows = result.raw_rows(y);
            for ch in 0..3 { for &value in &rows[ch] { result.sums[ch] += value; } }
        }
        Ok(Some(result))
    }
    pub fn descriptor(&self)->ExactDiffusionKernelDescriptor {
        let group=|g:GroupCfg| [g.lambda_um.to_bits(),g.spread.to_bits(),g.n_components as u64,g.alpha.to_bits()];
        ExactDiffusionKernelDescriptor {
            algorithm:"exact-diffusion-row-f64-v1",geometry:self.identity_geometry,
            family:self.identity_family.clone(),constructor_bits:self.identity_parameters,
            groups:[group(self.cfg.core),group(self.cfg.halo),group(self.cfg.bloom)],
            family_bits:[self.cfg.w_c,self.cfg.w_h,self.cfg.w_b,self.cfg.halo_warmth_base,self.cfg.total_gain].map(f64::to_bits),
            radius:self.radius,scalar_bits:[self.spatial_scale,self.pixel_size_um,self.scatter,self.effective_warmth].map(f64::to_bits),
            normalization_bits:self.sums.map(f64::to_bits),
        }
    }
    pub fn side(&self) -> usize { 2 * self.radius + 1 }
    fn raw_rows(&self, y: usize) -> [Vec<f64>;3] {
        let center = self.radius as f64;
        let r: Vec<f64> = (0..self.side()).map(|x| { let dx=x as f64-center;let dy=y as f64-center;(dx*dx+dy*dy).sqrt() }).collect();
        let (core,halo,bloom)=radial_components(&r,&self.cfg,self.spatial_scale,self.pixel_size_um,self.effective_warmth);
        std::array::from_fn(|ch| (0..self.side()).map(|x|core[x]+halo[ch][x]+bloom[x]).collect())
    }
    pub fn row(&self, channel: usize, y: usize) -> Vec<f64> {
        assert!(channel<3 && y<self.side());
        let mut row=self.raw_rows(y)[channel].clone();
        if self.sums[channel]>0. { for value in &mut row { *value/=self.sums[channel]; } }
        row
    }
}

#[cfg(test)]
mod exact_descriptor_tests {
    use super::*;
    fn config()->DiffusionFilter<'static>{DiffusionFilter{family:"black_pro_mist",strength:0.5,spatial_scale:1.,halo_warmth:0.,core_intensity:1.,core_size:1.,halo_intensity:1.,halo_size:1.,bloom_intensity:1.,bloom_size:1.}}
    #[test]
    fn descriptor_covers_every_constructor_parameter_and_geometry() {
        let baseline=ExactDiffusionKernel::new(&config(),10.,47,29).unwrap().descriptor();
        assert!(baseline.storage_bytes()<1024);
        for field in 0..10 {
            let mut d=config();match field {0=>d.family="pro_mist",1=>d.strength=0.7,2=>d.spatial_scale=1.2,3=>d.halo_warmth=0.2,4=>d.core_intensity=1.2,5=>d.core_size=1.2,6=>d.halo_intensity=1.2,7=>d.halo_size=1.2,8=>d.bloom_intensity=1.2,_=>d.bloom_size=1.2};
            assert_ne!(baseline,ExactDiffusionKernel::new(&d,10.,47,29).unwrap().descriptor());
        }
        assert_ne!(baseline,ExactDiffusionKernel::new(&config(),11.,47,29).unwrap().descriptor());
        assert_ne!(baseline,ExactDiffusionKernel::new(&config(),10.,48,29).unwrap().descriptor());
        assert_eq!(baseline,ExactDiffusionKernel::new(&config(),10.,47,29).unwrap().descriptor());
    }
}

#[cfg(test)]
mod resident_prepass_tests {
    use super::*;
    use spektrafilm_gpu::cpu_backend::CpuBackend;

    #[test]
    fn irregular_bands_accumulate_partial_cells_without_budget_roundup() {
        for (w, h, d, step) in [(7, 11, 4, 1), (1, 9, 3, 2), (9, 1, 4, 1), (13, 17, 5, 3)] {
            let image = ImageBuf::from_data(w, h, (0..w * h * 3).map(|v| from_f64(v as f64 * 0.125)).collect());
            let mut acc = DownsampleAccumulator::new(w as usize, h as usize, d);
            let mut y = 0usize;
            while y < h as usize {
                let end = (y + step).min(h as usize);
                let tap = ImageBuf::from_data(w, (end-y) as u32, image.data[y*w as usize*3..end*w as usize*3].to_vec());
                acc.add_band(&tap, y, y, end);
                y = end;
            }
            let out = acc.finish();
            for sy in 0..h as usize / d + usize::from(h as usize % d != 0) {
                for sx in 0..w as usize / d + usize::from(w as usize % d != 0) {
                    for ch in 0..3 {
                        let mut sum=0.0; let mut count=0;
                        for y in sy*d..((sy+1)*d).min(h as usize) { for x in sx*d..((sx+1)*d).min(w as usize) {
                            sum += image.data[(y*w as usize+x)*3+ch] as f64; count += 1;
                        }}
                        assert!((out.data[(sy*out.width as usize+sx)*3+ch] as f64-sum/count as f64).abs()<1e-6);
                    }
                }
            }
        }
    }

    #[test]
    fn rectangular_cores_partition_partial_cells_in_both_axes() {
        let (w,h,d)=(13usize,17usize,5usize);
        let image=ImageBuf::from_data(w as u32,h as u32,
            (0..w*h*3).map(|i|from_f64(i as f64*0.125)).collect());
        let mut reference=DownsampleAccumulator::new(w,h,d);
        reference.add_band(&image,0,0,h);
        let mut actual=DownsampleAccumulator::new(w,h,d);
        for y in (0..h).step_by(3) { for x in (0..w).step_by(4) {
            let x1=(x+4).min(w);let y1=(y+3).min(h);
            let tx=x.saturating_sub(1);let ty=y.saturating_sub(2);
            let tw=(x1+1).min(w)-tx;let th=(y1+2).min(h)-ty;
            let mut values=Vec::new();
            for row in ty..ty+th {values.extend_from_slice(&image.data[(row*w+tx)*3..(row*w+tx+tw)*3]);}
            let tap=ImageBuf::from_data(tw as u32,th as u32,values);
            actual.add_tile(&tap,tx,ty,[x,y,x1,y1]).unwrap();
        }}
        assert_eq!(actual.finish().data,reference.finish().data);
        assert!(actual.add_tile(&image,1,0,[0,0,w,h]).is_none());
    }

    #[test]
    fn scattered_components_cancel_at_safe_boundaries() {
        let small=ImageBuf::from_data(3, 2, vec![from_f64(0.5); 18]);
        let plan=spektrafilm_gpu::DiffusionGpuPlan { d:1,small_w:3,small_h:2,p_s:0.2,sigmas:vec![1.,2.],coeffs:vec![[0.5;3],[0.5;3]] };
        assert!(blur_scattered_field_controlled(&CpuBackend,&small,&plan,||false).is_none());
        let mut checks=0;
        assert!(blur_scattered_field_controlled(&CpuBackend,&small,&plan,||{checks+=1;checks<3}).is_none());
        assert_eq!(checks,3);
        let out=blur_scattered_field_controlled(&CpuBackend,&small,&plan,||true).unwrap();
        assert_eq!(out.len(),18);
        assert!(out.iter().all(|v|(*v-0.5).abs()<1e-6));
    }
}
