//! Presentation only: retain the legacy Kotlin Float -> Double sRGB formula.
//! No LUT, approximation, film processing, or full-image staging allocation.
use crate::mobile::{EngineError, Result};

fn encode_finite(v: f32) -> u8 {
    let x = v.clamp(0.0, 1.0) as f64;
    let encoded = if x <= 0.0031308 {
        x * 12.92
    } else {
        1.055 * x.powf(1.0 / 2.4) - 0.055
    };
    ((encoded * 255.0 + 0.5) as i32).clamp(0, 255) as u8
}

/// Writes candidate output directly into the caller-owned buffer, with O(1)
/// scratch memory. On error it may be partly written and MUST NOT be published.
/// Only success plus the caller's current-generation check permits publication.
/// `check` runs before validation, for every validation/encoding row, and after
/// the last write. Finite validation finishes before the first byte is written.
pub(crate) fn pack_rgba8(
    pixels: &[f32],
    output: &mut [u8],
    width: usize,
    mut check: impl FnMut() -> Result<()>,
) -> Result<()> {
    check()?;
    let row = width
        .checked_mul(3)
        .filter(|&v| v > 0)
        .ok_or_else(|| EngineError::new("INVALID_OUTPUT_SHAPE", "Invalid display width"))?;
    let bytes = (pixels.len() / 3)
        .checked_mul(4)
        .ok_or_else(|| EngineError::new("INVALID_OUTPUT_SHAPE", "Display size overflow"))?;
    if pixels.is_empty() || pixels.len() % row != 0 || output.len() != bytes {
        return Err(EngineError::new(
            "INVALID_OUTPUT_SHAPE",
            "Display buffer shape mismatch",
        ));
    }
    for values in pixels.chunks_exact(row) {
        check()?;
        if values.iter().any(|v| !v.is_finite()) {
            return Err(EngineError::new(
                "NONFINITE_OUTPUT",
                "Display sample is not finite",
            ));
        }
    }
    for (values, bytes) in pixels
        .chunks_exact(row)
        .zip(output.chunks_exact_mut(width * 4))
    {
        check()?;
        for (rgb, rgba) in values.chunks_exact(3).zip(bytes.chunks_exact_mut(4)) {
            rgba[0] = encode_finite(rgb[0]);
            rgba[1] = encode_finite(rgb[1]);
            rgba[2] = encode_finite(rgb[2]);
            rgba[3] = 255;
        }
    }
    check()
}

#[cfg(test)]
mod tests {
    use super::*;

    // Independent expression of the old Kotlin source. Host coverage does not
    // establish libm/JVM parity; the Android byte comparison is the release gate.
    fn legacy(v: f32) -> u8 {
        let x = (if v < 0.0 {
            0.0
        } else if v > 1.0 {
            1.0
        } else {
            v
        }) as f64;
        let y = if x <= 0.0031308 {
            12.92 * x
        } else {
            1.055 * f64::powf(x, 1.0 / 2.4) - 0.055
        };
        (y * 255.0 + 0.5).floor().clamp(0.0, 255.0) as u8
    }

    #[test]
    fn every_code_boundary_neighbors_and_dense_samples_match_legacy() {
        let mut samples = vec![-f32::MAX, -1.0, -0.0, 0.0, 1.0, f32::MAX];
        for code in 0..255 {
            let encoded = (code as f64 + 0.5) / 255.0;
            let linear = if encoded <= 0.04045 {
                encoded / 12.92
            } else {
                ((encoded + 0.055) / 1.055).powf(2.4)
            } as f32;
            for offset in -8i64..=8 {
                samples.push(f32::from_bits((linear.to_bits() as i64 + offset) as u32));
            }
        }
        for center in [0.0031308f32, 1.0] {
            for offset in -8i64..=8 {
                samples.push(f32::from_bits((center.to_bits() as i64 + offset) as u32));
            }
        }
        samples.extend((0..=65535).map(|i| i as f32 / 65535.0));
        let pixels: Vec<f32> = samples.iter().flat_map(|&v| [v, v, v]).collect();
        let mut bytes = vec![0; samples.len() * 4];
        pack_rgba8(&pixels, &mut bytes, 1, || Ok(())).unwrap();
        for (&sample, rgba) in samples.iter().zip(bytes.chunks_exact(4)) {
            assert_eq!(
                rgba,
                &[legacy(sample), legacy(sample), legacy(sample), 255],
                "bits={:08x}",
                sample.to_bits()
            );
        }
    }

    #[test]
    fn nonfinite_anywhere_is_rejected_before_writes() {
        for invalid in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
            let mut bytes = [77; 8];
            let error = pack_rgba8(
                &[0.0, 1.0, 0.5, 1.0, invalid, 0.0],
                &mut bytes,
                1,
                || Ok(()),
            )
            .unwrap_err();
            assert_eq!(error.code, "NONFINITE_OUTPUT");
            assert_eq!(bytes, [77; 8]);
        }
    }

    #[test]
    fn cancellation_before_each_row_and_after_commit_never_reports_success() {
        // initial + 2 validation rows + 2 encoding rows + final check
        for stop in 1..=6 {
            let mut calls = 0;
            let mut bytes = [77; 8];
            let result = pack_rgba8(&[1.0; 6], &mut bytes, 1, || {
                calls += 1;
                if calls == stop {
                    Err(EngineError::new("CANCELLED", "test"))
                } else {
                    Ok(())
                }
            });
            assert_eq!(result.unwrap_err().code, "CANCELLED");
            assert_eq!(calls, stop);
            if stop <= 4 {
                assert_eq!(bytes, [77; 8]);
            }
            if stop == 5 {
                assert_eq!(bytes, [255, 255, 255, 255, 77, 77, 77, 77]);
            }
        }
    }

    #[test]
    fn shape_and_rgba_order() {
        let mut bytes = [0; 12];
        pack_rgba8(&[1., 0., 0., 0., 1., 0., 0., 0., 1.], &mut bytes, 3, || {
            Ok(())
        })
        .unwrap();
        assert_eq!(bytes, [255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255]);
        assert!(pack_rgba8(&[0.; 3], &mut bytes, 1, || Ok(())).is_err());
        assert!(pack_rgba8(&[0.; 3], &mut [], 0, || Ok(())).is_err());
    }
}
