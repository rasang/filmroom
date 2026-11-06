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
    active: Rect,
    warnings: &mut Vec<Warning>,
) -> Result<(
    Vec<crate::gainmap::GainMap>,
    Vec<crate::cfa_gainmap::CfaGainMap>,
    Vec<crate::opcodes::Stage1Opcode>,
    Option<crate::dng_warp::WarpRectilinear>,
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
                let records=crate::opcodes::records(&v.bytes)?;
                let nonidentity=records.iter().filter(|r|r.flags&1==0 && r.id==1)
                    .map(|r|crate::dng_warp::WarpRectilinear::parse(r.payload))
                    .collect::<Result<Vec<_>>>()?.iter().any(|w|!w.is_identity());
                if nonidentity {
                    // A single required warp is the verified initial envelope.
                    // Mixing maps/other required stages must not reorder them.
                    let required:Vec<_>=records.iter().filter(|r|r.flags&1==0).collect();
                    if !cfa_mode || required.len()!=1 || required[0].id!=1
                        || !crate::opcodes::supported_version(required[0].version) {
                        return Err(err("UNSUPPORTED_DNG_OPCODE","probe"));
                    }
                    warp=Some(crate::dng_warp::WarpRectilinear::parse(required[0].payload)?);
                    records.iter().any(|r|r.flags&1!=0)
                } else {
                    let (maps, skipped) = crate::gainmap::parse(&v.bytes, active.w, active.h)?;
                    gain_maps = maps;
                    skipped
                }
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
    Ok((gain_maps, cfa_maps, stage1, warp))
}
