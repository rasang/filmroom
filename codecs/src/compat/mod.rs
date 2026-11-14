//! Shared DNG capability checks plus narrowly scoped brand compatibility policies.
//! Brand matching never bypasses required opcodes or safety/resource checks.
mod brands;
mod calibration;
mod lens;
mod profiles;
#[cfg(test)]
mod identity_look_tests;
use crate::{
    error::{Result, err},
    tiff::Ifd,
};
pub(crate) use calibration::{baseline_exposure, camera_calibration, forward_matrices};
pub(crate) use lens::lens_opcodes;
pub(crate) use profiles::profile_metadata;

fn inherited<'a>(root: &'a Ifd, raw: &'a Ifd, tag: u16) -> Option<&'a crate::tiff::Value> {
    raw.get(&tag).or_else(|| root.get(&tag))
}
#[track_caller]
fn color_err() -> crate::error::CodecError {
    err("INVALID_COLOR_METADATA", "probe")
}

pub(crate) fn unsupported_processing(root: &Ifd, raw: &Ifd) -> Result<()> {
    profiles::identity_look_table(root, raw)?;
    // DNG DefaultBlackRender None is a profile rendering hint: no extra black
    // subtraction before a look table. It does not disable sensor BlackLevel.
    // This linear pipeline has no extra flare/renderer black subtraction.
    // Admit only the exact verified LONG/count1/None form; Auto/unknown remain closed.
    if !std::ptr::eq(root, raw) && raw.contains_key(&51110) {
        return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
    }
    if let Some(value) = root.get(&51110) {
        if value.typ != 4 || value.count != 1 || value.uints()? != [1] {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
    }

    // PreviewColorSpace describes the embedded preview, not sensor color.
    // Validate its standard LONG enum without applying it to RAW pixels.
    for ifd in [root, raw] {
        if let Some(value) = ifd.get(&50970) {
            if value.typ != 4
                || value.count != 1
                || value.uints()?.as_slice().first().is_none_or(|v| *v > 4)
            {
                return Err(err("INVALID_INPUT", "probe"));
            }
        }
    }

    // These require additional processing not implemented by the pinned developer. Never silently ignore.
    for tag in [
        50715, 50716, 50729, 50942, 52530, 52532, 52547, 50966, 50967, 50968, 50969, 50971, 50972,
        50973, 50974, 50975, 51107, 51109, 51112, 51113, 51114, 51115, 51116, 51117, 52536,
        52537,
    ] {
        if inherited(root, raw, tag).is_some() {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
    }
    Ok(())
}

pub(crate) fn unit_geometry(root: &Ifd, raw: &Ifd, calibrated_analog_balance: bool) -> Result<()> {
    for tag in [50718, 50727] {
        // Non-unit AnalogBalance is admitted only after a color path has
        // validated and incorporated it. DefaultScale stays strictly unit.
        if tag == 50727 && calibrated_analog_balance { continue; }
        if let Some(v) = inherited(root, raw, tag) {
            let values = v.nums()?;
            let expected = if tag == 50718 { 2 } else { 3 };
            if values.len() != expected
                || values
                    .iter()
                    .any(|&x| !x.is_finite() || (x - 1.).abs() > 1e-8)
            {
                return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod black_render_location_tests {
    use super::*;
    use crate::tiff::{Endian, Value};
    fn value(v: u32) -> Value {
        Value {
            typ: 4,
            count: 1,
            bytes: v.to_le_bytes().to_vec(),
            endian: Endian::Le,
        }
    }
    #[test]
    fn ifd0_only_and_no_raw_override() {
        let mut root = Ifd::new();
        let mut raw = Ifd::new();
        root.insert(51110, value(1));
        assert!(unsupported_processing(&root, &root).is_ok());
        assert!(unsupported_processing(&root, &raw).is_ok());
        raw.insert(51110, value(1));
        assert_eq!(
            unsupported_processing(&root, &raw).unwrap_err().code,
            "UNSUPPORTED_DNG_VARIANT"
        );
        root.insert(51110, value(0));
        assert!(unsupported_processing(&root, &raw).is_err());
        root.clear();
        assert!(unsupported_processing(&root, &raw).is_err());
    }
}

#[cfg(test)]
mod preview_color_tests {
    use super::*;
    use crate::tiff::{Endian, Value};
    fn value(n: u32) -> Value {
        Value {
            typ: 4,
            count: 1,
            bytes: n.to_le_bytes().to_vec(),
            endian: Endian::Le,
        }
    }
    #[test]
    fn preview_color_is_metadata_but_processing_tags_remain_rejected() {
        for n in 0..=4 {
            let mut ifd = Ifd::new();
            ifd.insert(50970, value(n));
            assert!(unsupported_processing(&ifd, &ifd).is_ok());
        }
        let mut bad = Ifd::new();
        bad.insert(50970, value(5));
        assert_eq!(
            unsupported_processing(&bad, &bad).unwrap_err().code,
            "INVALID_INPUT"
        );
        bad.get_mut(&50970).unwrap().typ = 9;
        assert!(unsupported_processing(&bad, &bad).is_err());
        for tag in [50715, 50716, 50729, 50942, 52530, 52532, 52547] {
            let mut ifd = Ifd::new();
            ifd.insert(50970, value(2));
            ifd.insert(tag, value(0));
            assert_eq!(
                unsupported_processing(&ifd, &ifd).unwrap_err().code,
                "UNSUPPORTED_DNG_VARIANT"
            );
        }
    }
}
