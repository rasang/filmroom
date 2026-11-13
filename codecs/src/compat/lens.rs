use super::inherited;
use crate::dng::{Rect, Warning};
use crate::{
    error::{Result, err},
    tiff::Ifd,
};
pub(crate) fn lens_opcodes(
    root: &Ifd,
    raw: &Ifd,
    width: usize,
    height: usize,
    cfa_mode: bool,
    allow_geometry: bool,
    active: Rect,
    warnings: &mut Vec<Warning>,
) -> Result<(
    Vec<crate::gainmap::GainMap>,
    Vec<crate::cfa_gainmap::CfaGainMap>,
    Vec<crate::opcodes::Stage1Opcode>,
    Option<crate::dng_warp::WarpPipeline>,
)> {
    let mut gain_maps = Vec::new();
    let mut cfa_maps = Vec::new();
    let mut stage1 = Vec::new();
    let mut warp = None;
    for tag in [51008, 51009, 51022] {
        if let Some(v) = inherited(root, raw, tag) {
            if !matches!(v.typ, 1 | 7) {
                return Err(err("INVALID_INPUT", "probe"));
            }
            let skipped = if tag == 51008 && cfa_mode {
                let (ops, skipped) = crate::opcodes::parse_stage1(&v.bytes, width, height)?;
                stage1 = ops;
                skipped
            } else if tag == 51009 && cfa_mode {
                let (maps, skipped) = crate::cfa_gainmap::parse(&v.bytes, active.w, active.h)?;
                cfa_maps = maps;
                skipped
            } else if tag == 51022 {
                let (maps, geometry, skipped) = crate::opcodes::parse_stage3(
                    &v.bytes, active.w, active.h, allow_geometry,
                )?;
                gain_maps = maps;
                warp = geometry;
                skipped
            } else {
                crate::opcodes::optional_only(&v.bytes)?
            };
            if skipped
                && !warnings
                    .iter()
                    .any(|w| w.code == "DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED")
            {
                warnings.push(Warning{code:"DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED",message:"部分可选镜头校正未应用，可能保留暗角或畸变"});
            }
        }
    }
    Ok((gain_maps, cfa_maps, stage1, warp))
}
