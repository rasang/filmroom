//! Shared DNG capability checks plus narrowly scoped brand compatibility policies.
//! Brand matching never bypasses required opcodes or safety/resource checks.
mod calibration;
mod profiles;
mod lens;
mod brands;
pub(crate) use calibration::{camera_calibration, forward_matrices, baseline_exposure};
pub(crate) use profiles::profile_metadata;
pub(crate) use lens::lens_opcodes;
use crate::{error::{Result, err}, tiff::Ifd};

fn inherited<'a>(root: &'a Ifd, raw: &'a Ifd, tag: u16) -> Option<&'a crate::tiff::Value> {
    raw.get(&tag).or_else(|| root.get(&tag))
}
fn color_err() -> crate::error::CodecError { err("INVALID_COLOR_METADATA", "probe") }

pub(crate) fn unsupported_processing(root: &Ifd, raw: &Ifd) -> Result<()> {
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

        // These require additional processing not implemented by the pinned developer. Never silently ignore.
        for tag in [
            50715, 50716, 50729, 50942, 52530, 52532, 52547, 50966, 50967, 50968,
            50969, 50970, 50971, 50972, 50973, 50974, 50975, 51107, 51108, 51109, 51112,
            51113, 51114, 51115, 51116, 51117, 52536, 52537,
        ] {
            if inherited(root, raw, tag).is_some() {
                return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
            }
        }
        Ok(())
}

pub(crate) fn unit_geometry(root: &Ifd, raw: &Ifd) -> Result<()> {
        for tag in [50718, 50727] {
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

#[cfg(test)] mod black_render_location_tests {
 use super::*;use crate::tiff::{Value,Endian};
 fn value(v:u32)->Value{Value{typ:4,count:1,bytes:v.to_le_bytes().to_vec(),endian:Endian::Le}}
 #[test] fn ifd0_only_and_no_raw_override(){
  let mut root=Ifd::new();let mut raw=Ifd::new();root.insert(51110,value(1));assert!(unsupported_processing(&root,&root).is_ok());assert!(unsupported_processing(&root,&raw).is_ok());raw.insert(51110,value(1));assert_eq!(unsupported_processing(&root,&raw).unwrap_err().code,"UNSUPPORTED_DNG_VARIANT");root.insert(51110,value(0));assert!(unsupported_processing(&root,&raw).is_err());root.clear();assert!(unsupported_processing(&root,&raw).is_err());
 }
}
