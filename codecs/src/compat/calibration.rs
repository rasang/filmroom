use crate::{error::{Result, err, Reason}, tiff::Ifd};
use super::inherited;
use super::color_err;
pub(crate) fn camera_calibration(root: &Ifd, raw: &Ifd) -> Result<()> {
        // Positive diagonal camera calibration is row scaling of XYZ-to-camera.
        // The pinned RawDevelop row-normalizes this matrix, so that scaling
        // cancels algebraically. Apply it explicitly below; reject channel mixing.
        for tag in [50723, 50724] {
            if let Some(v) = inherited(root, raw, tag) {
                let m = v.nums().map_err(|e| e.detail(Reason::TagValue, Some(tag)))?;
                if m.len() != 9
                    || m.iter().enumerate().any(|(i, x)| {
                        !x.is_finite()
                            || if i % 4 == 0 {
                                *x <= 0.01 || *x > 100.
                            } else {
                                x.abs() > 1e-8
                            }
                    })
                {
                    return Err(err("UNSUPPORTED_DNG_CALIBRATION", "probe"));
                }
            }
        }
        Ok(())
}

pub(crate) fn forward_matrices(root: &Ifd, raw: &Ifd) -> Result<bool> {
        let mut has_forward_matrix = false;
        for tag in [50964, 50965] {
            if let Some(v) = inherited(root, raw, tag) {
                let m = v.nums().map_err(|e| e.detail(Reason::TagValue, Some(tag)))?;
                if m.len() != 9 || m.iter().any(|v| !v.is_finite() || v.abs() > 10.) {
                    return Err(color_err().detail(Reason::MatrixRange, Some(tag)));
                }
                let det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6])
                    + m[2] * (m[3] * m[7] - m[4] * m[6]);
                if det.abs() < 1e-8 {
                    return Err(color_err().detail(Reason::MatrixSingular, Some(tag)));
                }
                has_forward_matrix = true;
            }
        }
        Ok(has_forward_matrix)
}

pub(crate) fn baseline_exposure(root: &Ifd, raw: &Ifd) -> Result<f32> {
        let baseline_exposure_ev = if let Some(v) = inherited(root, raw, 50730) {
            let values = v.nums().map_err(|e| e.detail(Reason::TagValue, Some(50730)))?;
            if v.typ != 10 || values.len() != 1 || !values[0].is_finite() || values[0].abs() > 16. {
                return Err(color_err().detail(Reason::TagValue, Some(50730)));
            }
            values[0] as f32
        } else {
            0.
        };
        Ok(baseline_exposure_ev)
}

