//! Shared DNG capability checks and bounded, brand-independent compatibility.
//! Compatibility never bypasses required opcodes or safety/resource checks.
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
    // Auto is the DNG default when this optional rendering hint is absent.
    // Accept explicit Auto identically; neither value bypasses sensor BlackLevel.
    if !std::ptr::eq(root, raw) && raw.contains_key(&51110) {
        return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
    }
    if let Some(value) = root.get(&51110) {
        if value.typ != 4 || value.count != 1 || value.uints()?.first().is_none_or(|v| *v > 1) {
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

    descriptive_metadata(root, raw)?;

    // These require additional processing not implemented by the pinned developer. Never silently ignore.
    for tag in [
        52530, 52532, 52547, 50974, 50975, 51107, 51109, 51112, 51113, 51114, 51115, 51116, 51117, 52536,
        52537,
    ] {
        if inherited(root, raw, tag).is_some() {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe").detail(crate::error::Reason::UnsupportedValue, Some(tag)));
        }
    }
    Ok(())
}

/// Descriptor/digest fields do not request pixel processing. Validate their
/// loaded byte shape without logging their contents or claiming digest verification.
fn descriptive_metadata(root: &Ifd, raw: &Ifd) -> Result<()> {
    for ifd in [root, raw] {
        for tag in [50942, 50966, 50967, 50968, 50971] {
            if let Some(v) = ifd.get(&tag) {
                let type_ok = if tag == 50971 { v.typ == 2 } else { matches!(v.typ, 1 | 2) };
                if !type_ok || v.count == 0 || v.bytes.len() != v.count as usize
                    || v.bytes.last() != Some(&0)
                    || std::str::from_utf8(&v.bytes[..v.bytes.len() - 1]).is_err() {
                    return Err(err("INVALID_INPUT", "probe").detail(crate::error::Reason::TagShape, Some(tag)));
                }
            }
        }
        for tag in [50969, 50972, 50973] {
            if let Some(v) = ifd.get(&tag) {
                if v.typ != 1 || v.count != 16 || v.bytes.len() != 16 {
                    return Err(err("INVALID_INPUT", "probe").detail(crate::error::Reason::TagShape, Some(tag)));
                }
            }
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
        for tag in [52530, 52532, 52547] {
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

#[cfg(test)]
mod standard_metadata_tests {
    use super::*;
    use crate::tiff::{Endian, Value};
    fn bytes(typ: u16, data: &[u8]) -> Value {
        Value { typ, count: data.len() as u32, bytes: data.to_vec(), endian: Endian::Le }
    }
    #[test]
    fn descriptive_fields_and_digests_do_not_request_processing() {
        let mut root = Ifd::new();
        for tag in [50942, 50966, 50967, 50968] {
            for typ in [1, 2] {
                root.insert(tag, bytes(typ, "示例\0".as_bytes()));
                assert!(unsupported_processing(&root, &root).is_ok());
            }
        }
        root.insert(50971, bytes(2, b"2026-11-14T08:00:00Z\0"));
        for tag in [50969, 50972, 50973] { root.insert(tag, bytes(1, &[7;16])); }
        assert!(unsupported_processing(&root, &root).is_ok());
        assert!(unsupported_processing(&root, &Ifd::new()).is_ok());
        assert!(unsupported_processing(&Ifd::new(), &root).is_ok());
    }
    #[test]
    fn malformed_descriptors_do_not_panic_or_pass() {
        for value in [bytes(2, b"unterminated"), bytes(2, b""), bytes(7, b"str\0"), bytes(2, &[255,0]),
            Value { typ:2, count:99, bytes:b"str\0".to_vec(), endian:Endian::Le }] {
            let mut root = Ifd::new(); root.insert(50942, value);
            assert_eq!(unsupported_processing(&root, &root).unwrap_err().code, "INVALID_INPUT");
        }
        for value in [bytes(1, &[0;15]), bytes(7, &[0;16]),
            Value { typ:1, count:16, bytes:vec![], endian:Endian::Le }] {
            let mut root = Ifd::new(); root.insert(50972,value);
            assert_eq!(unsupported_processing(&root,&root).unwrap_err().code,"INVALID_INPUT");
        }
    }
    #[test]
    fn explicit_auto_equals_default_black_render() {
        let mut root = Ifd::new();
        assert!(unsupported_processing(&root,&root).is_ok());
        for value in [0u32,1] {
            root.insert(51110,Value { typ:4,count:1,bytes:value.to_le_bytes().to_vec(),endian:Endian::Le });
            assert!(unsupported_processing(&root,&root).is_ok());
        }
        root.get_mut(&51110).unwrap().bytes=2u32.to_le_bytes().to_vec();
        assert!(unsupported_processing(&root,&root).is_err());
        root.get_mut(&51110).unwrap().bytes.clear();
        assert!(unsupported_processing(&root,&root).is_err());
    }
}
