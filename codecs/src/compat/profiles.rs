use crate::{error::{Result, err, Reason}, tiff::Ifd};
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
            let dims = inherited(root, raw, 50937).ok_or_else(|| color_err())?;
            if dims.typ != 4 || dims.count != 3 {
                return Err(color_err().detail(Reason::ProfileDimensions, None));
            }
            let dims = dims.uints()?;
            if dims[0] == 0 || dims[1] < 2 || dims[2] == 0 {
                return Err(color_err().detail(Reason::ProfileDimensions, None));
            }
            let count = dims
                .iter()
                .try_fold(3u64, |n, &d| n.checked_mul(d as u64))
                .ok_or_else(|| color_err())?;
            if count > u32::MAX as u64 || inherited(root, raw, 50938).is_none() {
                return Err(color_err().detail(Reason::ProfileDimensions, None));
            }
            for tag in [50938, 50939] {
                if let Some(data) = inherited(root, raw, tag) {
                    if data.typ != 11 || data.count as u64 != count {
                        return Err(color_err().detail(Reason::ProfileDimensions, None));
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
        // this is not full DNG camera-profile rendering. The standard orders
        // points by x, not y; a legal non-monotonic display curve is still omitted.
        if let Some(curve) = inherited(root, raw, 50940) {
            if curve.typ != 11 || curve.count < 4 || curve.count % 2 != 0 || curve.count > 4096
                || curve.bytes.len() != curve.count as usize * 4 {
                return Err(color_err().detail(Reason::ProfileCurve, Some(50940)));
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
                    .any(|p| p[1][0] <= p[0][0])
            {
                return Err(color_err().detail(Reason::ProfileCurve, Some(50940)));
            }
            // Use the same bounded compatibility policy for every camera make.
            // This is nonstandard-file tolerance, not a change to the SDR specification.
            let endpoint = points[points.len() - 1];
            if endpoint != 1.
                && !super::brands::vivo::allows_subunity_tone_endpoint(root, raw, endpoint, points[points.len() - 3])
            {
                return Err(color_err().detail(Reason::ProfileEndpoint, Some(50940)));
            }
            if endpoint != 1. {
                warnings.push(Warning {
                    code: "DNG_NONSTANDARD_TONE_ENDPOINT",
                    message: "相机显示曲线白端略低于标准值；按基础 RAW 显色处理，不修改原文件或套用该曲线",
                });
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
            if !matches!(size.typ, 3 | 4) || size.uints()?.len() != 2 {
                return Err(err("INVALID_INPUT", "probe"));
            }
        }
        if let Some(digest) = inherited(root, raw, 51111) {
            if digest.typ != 1 || digest.count != 16 || digest.bytes.len() != 16 {
                return Err(err("INVALID_INPUT", "probe"));
            }
        }
        Ok(())
}



/// Admit a no-op DNG look table only after validating its complete payload.
/// This does not implement arbitrary look transforms or relax the TIFF reader's
/// 64 KiB per-tag / 512 KiB aggregate metadata limits.
pub(super) fn identity_look_table(root: &Ifd, raw: &Ifd) -> Result<()> {
    const TAGS: [u16; 3] = [50981, 50982, 51108];
    if !std::ptr::eq(root, raw) && TAGS.iter().any(|tag| raw.contains_key(tag)) {
        return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
    }
    if !TAGS.iter().any(|tag| root.contains_key(tag)) {
        return Ok(());
    }
    let dims = root.get(&50981).ok_or_else(|| color_err())?;
    let data = root.get(&50982).ok_or_else(|| color_err())?;
    if dims.typ != 4 || dims.count != 3 || dims.bytes.len() != 12 {
        return Err(color_err());
    }
    let dims = dims.uints()?;
    if dims[0] == 0 || dims[1] < 2 || dims[2] == 0 {
        return Err(color_err());
    }
    let count = dims.iter().try_fold(3u64, |n, &v| n.checked_mul(v as u64))
        .ok_or_else(|| color_err())?;
    if count > 65536 / 4 || data.typ != 11 || data.count as u64 != count
        || data.bytes.len() as u64 != count * 4 {
        return Err(color_err());
    }
    if let Some(encoding) = root.get(&51108) {
        if encoding.typ != 4 || encoding.count != 1 || encoding.bytes.len() != 4 {
            return Err(color_err());
        }
        // Encoding 0 is linear and is also the DNG default. Keep other
        // encodings closed until separately verified, even for a no-op table.
        if encoding.uints()? != [0] {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
    }
    for triple in data.bytes.chunks_exact(12) {
        let hue = f32::from_bits(data.endian.u32(&triple[0..4]));
        let saturation = f32::from_bits(data.endian.u32(&triple[4..8]));
        let value = f32::from_bits(data.endian.u32(&triple[8..12]));
        if !hue.is_finite() || !saturation.is_finite() || !value.is_finite() {
            return Err(color_err());
        }
        if hue != 0.0 || saturation != 1.0 || value != 1.0 {
            return Err(err("UNSUPPORTED_DNG_CALIBRATION", "probe"));
        }
    }
    Ok(())
}

#[cfg(test)]
mod phone_tone_tests {
    use super::*;
    use crate::tiff::{Value,Endian};
    fn root(points:&[f32])->Ifd {
        let mut r=Ifd::new();
        r.insert(271,Value{typ:2,count:7,bytes:b"Xiaomi\0".to_vec(),endian:Endian::Le});
        r.insert(50940,Value{typ:11,count:points.len() as u32,bytes:points.iter().flat_map(|x|x.to_le_bytes()).collect(),endian:Endian::Le});r
    }
    #[test]
    fn nonstandard_endpoint_has_explicit_warning_and_no_metadata_mutation() {
        let r=root(&[0.,0.,0.5,0.48,1.,0.997156024]);
        let before=r[&50940].bytes.clone();let mut warnings=vec![];
        profile_metadata(&r,&r,&mut warnings).unwrap();
        assert_eq!(r[&50940].bytes,before);
        assert!(warnings.iter().any(|w|w.code=="DNG_NONSTANDARD_TONE_ENDPOINT"));
        assert!(warnings.iter().any(|w|w.code=="DNG_CAMERA_TONE_CURVE_NOT_APPLIED"));
        let standard=root(&[0.,0.,1.,1.]);warnings.clear();
        profile_metadata(&standard,&standard,&mut warnings).unwrap();
        assert!(!warnings.iter().any(|w|w.code=="DNG_NONSTANDARD_TONE_ENDPOINT"));
    }
    #[test]
    fn compatibility_does_not_bypass_curve_validation() {
        for points in [vec![0.,0.,1.,0.996],vec![0.,0.,0.5,0.8,1.,0.7],vec![0.,0.,0.,0.3,1.,0.998],vec![0.,0.,1.,f32::NAN],vec![0.,0.,1.,f32::INFINITY],vec![0.,0.,0.9,0.998],vec![0.,0.1,1.,0.998]] {
            let r=root(&points);assert!(profile_metadata(&r,&r,&mut vec![]).is_err());
        }
        let mut r=root(&[0.,0.,1.,0.998]);r.get_mut(&50940).unwrap().typ=12;
        assert!(profile_metadata(&r,&r,&mut vec![]).is_err());
    }
}

#[cfg(test)]
mod standard_shape_tests {
    use super::*;
    use crate::tiff::{Endian, Value};
    #[test]
    fn original_size_accepts_both_standard_integer_types() {
        for (typ, bytes) in [(3,[6000u16,4000].into_iter().flat_map(u16::to_le_bytes).collect()),
            (4,[6000u32,4000].into_iter().flat_map(u32::to_le_bytes).collect())] {
            let mut root = Ifd::new();
            root.insert(51089,Value { typ,count:2,bytes,endian:Endian::Le });
            profile_metadata(&root,&root,&mut vec![]).unwrap();
            root.get_mut(&51089).unwrap().bytes.pop();
            assert!(profile_metadata(&root,&root,&mut vec![]).is_err());
        }
    }
    #[test]
    fn standard_curve_need_not_be_monotonic_in_y() {
        let mut root=Ifd::new();
        let points=[0f32,0.,0.25,0.7,0.5,0.4,1.,1.];
        root.insert(50940,Value { typ:11,count:8,bytes:points.into_iter().flat_map(f32::to_le_bytes).collect(),endian:Endian::Le });
        let mut warnings=vec![];
        profile_metadata(&root,&root,&mut warnings).unwrap();
        assert!(warnings.iter().any(|w|w.code=="DNG_CAMERA_TONE_CURVE_NOT_APPLIED"));
    }
    #[test]
    fn declared_curve_count_must_match_loaded_bytes() {
        for bytes in [vec![],vec![0;4],vec![0;15],vec![0;20]] {
            let mut root = Ifd::new();
            root.insert(50940,Value { typ:11,count:4,bytes,endian:Endian::Le });
            let error=profile_metadata(&root,&root,&mut vec![]).unwrap_err();
            assert_eq!(error.code,"INVALID_COLOR_METADATA");
            assert_eq!(error.tag,Some(50940));
        }
    }
}
