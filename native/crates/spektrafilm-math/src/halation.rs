//! CineStill back-reflection fit and highlight headroom shared by all paths.
//!
//! Fit and model from SpektraLab 02d82b56, engine/src/core/halation_psf.hpp
//! (GPL-3.0); preserve these fitted constants instead of hand-tuning a halo.
//! https://github.com/JamesQiu2005/SpektraLab/blob/02d82b56cf65f0f8e802a708612edc4e9a4790af/engine/src/core/halation_psf.hpp

pub const BACK_REFLECTION_STRENGTH: [f64; 3] = [0.30, 0.03, 0.003];
pub const BACK_REFLECTION_SIGMA_OVER_BASE: [[f64; 3]; 4] = [
    [0.184586, 0.178469, 0.170237],
    [1.121207, 1.000078, 0.546805],
    [1.651048, 1.581389, 0.808738],
    [4.677567, 9.448921, 1.340598],
];
pub const BACK_REFLECTION_WEIGHT: [[f64; 3]; 4] = [
    [0.041510, 0.106789, 0.202791],
    [-0.500000, -0.500000, 0.156578],
    [1.034500, 1.384389, -0.500000],
    [0.423990, 0.008822, 1.140631],
];

#[derive(Debug, Clone, Copy)]
pub struct HalationComponent {
    pub weight: [f64; 3],
    pub sigma_px: [f64; 3],
}

/// Only the CineStill model treats amount as an area multiplier. Historical
/// Gaussian models retain their original amount and radius semantics.
pub fn back_reflection_components(
    base_thickness_um: f64,
    spatial_scale: f64,
    amount: f64,
    pixel_size_um: f64,
) -> [HalationComponent; 4] {
    let d = base_thickness_um * spatial_scale * amount.max(0.0).sqrt() / pixel_size_um;
    std::array::from_fn(|k| HalationComponent {
        weight: BACK_REFLECTION_WEIGHT[k],
        sigma_px: BACK_REFLECTION_SIGMA_OVER_BASE[k].map(|s| (s * d).max(1e-6)),
    })
}

fn smoothstep(x: f64) -> f64 {
    let x = x.clamp(0.0, 1.0);
    x * x * (3.0 - 2.0 * x)
}

/// Restore lost scene energy only near the full frame's brightest exposure
/// AND above a mid-gray-relative gate. Use one gain for all channels so the
/// source hue is preserved. This is separate from the legacy highlight boost.
pub fn headroom_gain(
    max_channel: f64,
    frame_max: f64,
    headroom_ev: f64,
    from_ev: f64,
    to_ev: f64,
) -> f64 {
    if max_channel <= 0.0 || frame_max <= 0.0 || !frame_max.is_finite()
        || headroom_ev <= 0.0 || to_ev <= from_ev {
        return 1.0;
    }
    let log = max_channel.log2();
    let clip_gate = smoothstep((log - frame_max.log2() + 1.0) / 0.75);
    let scene_gate = smoothstep((log - 0.184_f64.log2() - from_ev) / (to_ev - from_ev));
    2.0_f64.powf(headroom_ev * clip_gate * scene_gate)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fit_has_unit_mass_and_distinct_channel_widths() {
        let a = back_reflection_components(120., 1., 1., 10.);
        for c in 0..3 {
            assert!((a.iter().map(|x| x.weight[c]).sum::<f64>() - 1.).abs() < 1e-12);
        }
        assert_eq!(a[3].sigma_px[0], 4.677567 * 12.);
        assert_ne!(a[2].sigma_px[0], a[2].sigma_px[1]);
        let b = back_reflection_components(120., 1., 4., 10.);
        for k in 0..4 { for c in 0..3 { assert_eq!(b[k].sigma_px[c], 2. * a[k].sigma_px[c]); }}
    }
    #[test]
    fn headroom_protects_dark_frames_and_non_peak_midtones() {
        assert_eq!(headroom_gain(0.184, 0.184, 4., 1.5, 2.5), 1.);
        assert_eq!(headroom_gain(0.5, 0.5, 4., 1.5, 2.5), 1.);
        assert_eq!(headroom_gain(1., 8., 4., 1.5, 2.5), 1.);
        assert_eq!(headroom_gain(8., 8., 4., 1.5, 2.5), 16.);
        assert_eq!(headroom_gain(8., 8., 0., 1.5, 2.5), 1.);
        assert_eq!(headroom_gain(0., 0., 4., 1.5, 2.5), 1.);
    }
}
