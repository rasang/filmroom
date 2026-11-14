//! Shared per-operation selection for original camera RAW development.
//! Vignetting changes the source camera RGB before any interpolation. Distortion
//! and lateral CA share one destination-to-source lookup and one RGB resampling.
use super::*;
use crate::embedded_optics::State;

/// A mathematically valid optional TCA can still need an enormous shared RGB
/// source window. Certify its worst 128x32 destination tile using continuous
/// Jacobian/factor bounds. Rejected TCA leaves every other operation intact.
pub(super) fn qualify(d: &mut Development) {
    let State::Ready(tca)=&d.lens_extras.tca else {return;};
    if tca.is_identity() {return;}
    let certified=(|| {
        let (distortion,distortion_zoom,distortion_applied)=match &d.lens {
            LensStatus::Ready(model)=>(model.sampling_jacobian_bound()?,model.autofill_zoom(),true),
            LensStatus::Missing|LensStatus::Unsupported=>(1.0,1.0,false),
            // Current original-CR3 profiles never also select manufacturer CA.
            LensStatus::Profile(_)=>return None,
        };
        let (minimum,maximum,tca_jacobian)=tca.sampling_bounds(distortion_applied)?;
        let zoom=distortion_zoom*tca.autofill_zoom();
        let jacobian=distortion*tca_jacobian/zoom;
        let reference=d.lens_reference;
        if reference.w<5 || reference.h<5 {return None;}
        let diagonal=((reference.w.min(128)-1) as f64).hypot((reference.h.min(32)-1) as f64);
        let separation=maximum-minimum;
        let local=jacobian*diagonal;
        let width=(local+reference.w as f64*0.5*separation).ceil()+5.0;
        let height=(local+reference.h as f64*0.5*separation).ceil()+5.0;
        let bytes=width.min(d.active.w as f64)*height.min(d.active.h as f64)*12.0;
        (jacobian.is_finite() && jacobian>0.0 && separation>=0.0
            && bytes.is_finite() && bytes<=8.0*1024.0*1024.0).then_some(())
    })().is_some();
    if !certified {d.lens_extras.tca=State::Unsupported;}
}

pub(super) fn tca_active(d: &Development) -> bool {
    match &d.lens_extras.tca {
        State::Ready(model) => !model.is_identity(),
        State::Unsupported => false,
        State::Missing => matches!(&d.lens, LensStatus::Profile(p) if p.has_tca()),
    }
}

pub(super) fn vignette_active(d: &Development) -> bool {
    match &d.lens_extras.vignette {
        State::Ready(model) => !model.is_identity(),
        State::Unsupported => false,
        State::Missing => matches!(&d.lens, LensStatus::Profile(p) if p.has_vignetting()),
    }
}

pub(super) fn apply_source_vignette(
    d: &Development, y: usize, pixels: &mut rawler::pixarray::Color2D<f32, 3>,
    cancel: &AtomicBool,
) -> Result<()> {
    if !vignette_active(d) { return Ok(()); }
    if pixels.width != d.active.w || pixels.data.len() != pixels.width * pixels.height {
        return Err(err("INVALID_INPUT", "lens_vignetting"));
    }
    let reference = d.lens_reference;
    for (row, values) in pixels.data.chunks_exact_mut(pixels.width).enumerate() {
        if cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
        let sy = y as f64 + row as f64 - reference.y as f64;
        for (column, pixel) in values.iter_mut().enumerate() {
            let sx = d.active.x as f64 + column as f64 - reference.x as f64;
            let gain = match &d.lens_extras.vignette {
                State::Ready(model) => model.source_gain(reference.w, reference.h, sx, sy)?,
                State::Missing => match &d.lens {
                    LensStatus::Profile(profile) => profile.source_gain(reference.w, reference.h, sx, sy)?,
                    _ => return Err(err("INVALID_INPUT", "lens_vignetting")),
                },
                State::Unsupported => return Err(err("INVALID_INPUT", "lens_vignetting")),
            };
            // Validated models certify their complete radial domain. This guard
            // also prevents nonfinite pixels from being published on any path.
            if !gain.is_finite() || gain <= 0.0 || gain > 64.0 {
                return Err(err("INVALID_INPUT", "lens_vignetting"));
            }
            for value in pixel {
                *value = (f64::from(*value) * gain) as f32;
                if !value.is_finite() { return Err(err("INVALID_COLOR_METADATA", "develop")); }
            }
        }
    }
    Ok(())
}

pub(super) fn source_position(
    d: &Development, x: usize, y: usize, channel: usize,
) -> Result<[f64; 2]> {
    let reference = d.lens_reference;
    let (width, height) = (reference.w, reference.h);
    if width == 0 || height == 0 || x >= width || y >= height || channel >= 3 {
        return Err(err("INVALID_INPUT", "lens_geometry"));
    }
    let embedded_tca = match &d.lens_extras.tca {
        State::Ready(model) if !model.is_identity() => Some(model),
        _ => None,
    };
    let extra_zoom = embedded_tca.map_or(1.0, |model| model.autofill_zoom());
    let mut position = match &d.lens {
        LensStatus::Ready(model) => {
            if embedded_tca.is_some() {
                model.source_position_with_zoom(width, height, x, y, model.autofill_zoom() * extra_zoom)?
            } else {
                model.source_position_autofill(width, height, x, y)?
            }
        },
        LensStatus::Profile(profile) => profile.source_position_for_correction(
            width, height, x, y, channel,
            matches!(d.lens_extras.tca, State::Missing) && profile.has_tca(), extra_zoom,
        )?,
        LensStatus::Missing | LensStatus::Unsupported => {
            let center = [width as f64 * 0.5, height as f64 * 0.5];
            [center[0] + (x as f64 - center[0]) / extra_zoom,
             center[1] + (y as f64 - center[1]) / extra_zoom]
        },
    };
    if let Some(model) = embedded_tca {
        position = model.source_position_after_distortion(
            width, height, position[0], position[1], channel,
            matches!(d.lens, LensStatus::Ready(_)),
        )?;
    }
    // Autofill is certified by continuous model bounds. Clamp only the tiny
    // numerical tolerance at the last pixel, never an out-of-frame colour band.
    for (value, limit) in position.iter_mut().zip([(width - 1) as f64, (height - 1) as f64]) {
        if !value.is_finite() || *value < -1e-7 || *value > limit + 1e-7 {
            return Err(err("INVALID_INPUT", "lens_geometry"));
        }
        *value = value.clamp(0.0, limit);
    }
    Ok(position)
}
