use crate::{error::{Result, err}, tiff::Ifd};
use super::inherited;
use crate::dng::Warning;
pub(crate) fn lens_opcodes(root: &Ifd, raw: &Ifd, width: usize, height: usize, cfa_mode: bool, warnings: &mut Vec<Warning>) -> Result<(Vec<crate::gainmap::GainMap>, Vec<crate::cfa_gainmap::CfaGainMap>)> {
        let mut gain_maps = Vec::new();
        let mut cfa_maps = Vec::new();
        for tag in [51008, 51009, 51022] {
            if let Some(v) = inherited(root, raw, tag) {
                if !matches!(v.typ, 1 | 7) {
                    return Err(err("INVALID_INPUT", "probe"));
                }
                let skipped = if tag == 51009 && cfa_mode && super::brands::xiaomi::matches(root) {
                    let (maps, skipped) = super::brands::xiaomi::list2(&v.bytes, width, height)?;
                    cfa_maps = maps;
                    skipped
                } else if tag == 51022 {
                    let (maps, skipped) = crate::gainmap::parse(&v.bytes, width, height)?;
                    gain_maps = maps;
                    skipped
                } else {
                    crate::opcodes::optional_only(&v.bytes)?
                };
                if skipped
                    && !warnings
                        .iter()
                        .any(|w| w.code == "DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED")
                {
                    warnings.push(Warning{code:"DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED",message:"部分可选镜头校正未应用，可能保留暗角或畸变；当前采用原始 RawDevelop 色彩流程"});
                }
            }
        }
        Ok((gain_maps, cfa_maps))
}

