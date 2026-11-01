use crate::{error::{Result, err}, tiff::Ifd};
use super::inherited;
use super::color_err;
use crate::dng::Warning;
pub(crate) fn profile_metadata(root: &Ifd, raw: &Ifd, warnings: &mut Vec<Warning>) -> Result<()> {
        // This matrix-based basic RAW development omits the file's DNG
        // HueSatMap color correction (distinct from ProfileLookTable).
        // Validate descriptor shape/bounds only, not the skipped float payload.
        // Colors may differ from camera rendering or other DNG software.
        if [50937, 50938, 50939]
            .iter()
            .any(|&tag| inherited(root, raw, tag).is_some())
        {
            let dims = inherited(root, raw, 50937).ok_or_else(color_err)?;
            if dims.typ != 4 || dims.count != 3 {
                return Err(color_err());
            }
            let dims = dims.uints()?;
            if dims[0] == 0 || dims[1] < 2 || dims[2] == 0 {
                return Err(color_err());
            }
            let count = dims
                .iter()
                .try_fold(3u64, |n, &d| n.checked_mul(d as u64))
                .ok_or_else(color_err)?;
            if count > u32::MAX as u64 || inherited(root, raw, 50938).is_none() {
                return Err(color_err());
            }
            for tag in [50938, 50939] {
                if let Some(data) = inherited(root, raw, tag) {
                    if data.typ != 11 || data.count as u64 != count {
                        return Err(color_err());
                    }
                }
            }
            warnings.push(Warning {
                code: "DNG_CAMERA_HSV_LOOK_NOT_APPLIED",
                message: "已进行基础 RAW 显色，未应用文件附带的色相饱和度校正；颜色可能与相机或其他软件不同",
            });
        }
        // This basic development also omits the profile tone curve before
        // applying the film pipeline. Validate its points, and report omission;
        // this is not full DNG camera-profile rendering.
        if let Some(curve) = inherited(root, raw, 50940) {
            if curve.typ != 11 || curve.count < 4 || curve.count % 2 != 0 || curve.count > 4096 {
                return Err(color_err());
            }
            let points: Vec<f32> = curve
                .bytes
                .chunks_exact(4)
                .map(|b| f32::from_bits(curve.endian.u32(b)))
                .collect();
            if points.iter().any(|x| !x.is_finite() || *x < 0. || *x > 1.)
                || points[0] != 0.
                || points[1] != 0.
                || points[points.len() - 2] != 1.
                || points
                    .chunks_exact(2)
                    .collect::<Vec<_>>()
                    .windows(2)
                    .any(|p| p[1][0] <= p[0][0] || p[1][1] < p[0][1])
            {
                return Err(color_err());
            }
            // Keep the shared SDR rule strict except for the bounded vivo
            // compatibility policy. All curve safety checks above still apply.
            let endpoint = points[points.len() - 1];
            if endpoint != 1.
                && !super::brands::vivo::allows_subunity_tone_endpoint(root, raw, endpoint, points[points.len() - 3])
            {
                return Err(color_err());
            }
            warnings.push(Warning {
                code: "DNG_CAMERA_TONE_CURVE_NOT_APPLIED",
                message: "已读取原始传感器数据，未套用相机自带显示曲线；将使用所选胶片效果",
            });
        }
        if let Some(policy) = inherited(root, raw, 50941) {
            let value = policy.uints()?;
            if policy.typ != 4 || value.len() != 1 || value[0] > 3 {
                return Err(err("INVALID_INPUT", "probe"));
            }
        }
        // Original-size hint and digest describe provenance/integrity, not a
        // transform to the current pixels. We do not claim digest verification.
        if let Some(size) = inherited(root, raw, 51089) {
            if size.typ != 4 || size.uints()?.len() != 2 {
                return Err(err("INVALID_INPUT", "probe"));
            }
        }
        if let Some(digest) = inherited(root, raw, 51111) {
            if digest.typ != 1 || digest.count != 16 {
                return Err(err("INVALID_INPUT", "probe"));
            }
        }
        Ok(())
}

