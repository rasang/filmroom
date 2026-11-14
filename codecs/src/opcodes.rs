//! Bounded standard DNG opcode envelopes and strict structural identity checks.
//! Required unsupported processing always fails closed; optional skips are explicit.
use crate::error::{Result, err};
#[path = "opcode_stage1.rs"]
mod stage1;
pub use stage1::{Stage1Opcode, apply_stage1_sample, apply_stage1_neighborhood, parse_stage1};
#[track_caller]
pub(crate) fn invalid() -> crate::error::CodecError {
    err("INVALID_INPUT", "probe")
}
#[track_caller]
pub(crate) fn unsupported() -> crate::error::CodecError {
    err("UNSUPPORTED_DNG_OPCODE", "probe")
}
pub(crate) fn supported_version(version: u32) -> bool {
    matches!(version, 0x01030000 | 0x01040000)
}
pub(crate) struct Cursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}
impl<'a> Cursor<'a> {
    pub(crate) fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }
    pub(crate) fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|&e| e <= self.bytes.len())
            .ok_or_else(|| invalid())?;
        let out = &self.bytes[self.pos..end];
        self.pos = end;
        Ok(out)
    }
    pub(crate) fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    pub(crate) fn f64(&mut self) -> Result<f64> {
        Ok(f64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
    pub(crate) fn finished(&self) -> bool {
        self.pos == self.bytes.len()
    }
}
pub(crate) struct Record<'a> {
    pub id: u32,
    pub version: u32,
    pub flags: u32,
    pub payload: &'a [u8],
}
pub(crate) fn records(bytes: &[u8]) -> Result<Vec<Record<'_>>> {
    let mut c = Cursor::new(bytes);
    let count = c.u32()?;
    if count > 64 {
        return Err(unsupported());
    }
    let mut result = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let id = c.u32()?;
        let version = c.u32()?;
        let flags = c.u32()?;
        let size = c.u32()? as usize;
        if id == 0 || version == 0 || flags & !3 != 0 {
            return Err(invalid());
        }
        result.push(Record {
            id,
            version,
            flags,
            payload: c.take(size)?,
        });
    }
    if !c.finished() {
        return Err(invalid());
    }
    Ok(result)
}

/// List3 uses camera RGB coordinates after demosaic and ActiveArea removal.
/// Maps-only files retain their established streaming path/optional-map policy.
/// A geometric list retains all admitted operations in the original order.
pub fn parse_stage3(
    bytes: &[u8], width: usize, height: usize, allow_geometry: bool,
) -> Result<(Vec<crate::gainmap::GainMap>, Option<crate::dng_warp::WarpPipeline>, bool)> {
    use crate::dng_warp::{Stage3Opcode, WarpPipeline};
    let records = records(bytes)?;
    let mut decoded = vec![None; records.len()];
    let mut budget = Stage3Budget::default();
    let mut skipped = false;

    // Reserve the bounded capacity for every required operation first. Earlier
    // optional records must not force a later required operation over budget.
    for (index, record) in records.iter().enumerate().filter(|(_, r)| r.flags & 1 == 0) {
        if let Some(step) = decode_stage3_record(record, width, height, allow_geometry)? {
            if !budget.admit(&step) { return Err(unsupported()); }
            decoded[index] = Some(step);
        }
    }
    // Recognize optional warps before deciding whether the ordered path is
    // needed. Unsupported versions/envelopes remain explicit optional skips.
    for (index, record) in records.iter().enumerate().filter(|(_, r)| r.flags & 1 != 0 && matches!(r.id, 1 | 2)) {
        match decode_stage3_record(record, width, height, allow_geometry) {
            Ok(Some(step)) if budget.admit(&step) => decoded[index] = Some(step),
            Ok(None) => {},
            Ok(Some(_)) => skipped = true,
            Err(e) if e.code == "UNSUPPORTED_DNG_OPCODE" => skipped = true,
            Err(e) => return Err(e),
        }
    }
    let has_geometry = budget.warps != 0;
    for (index, record) in records.iter().enumerate().filter(|(_, r)| r.flags & 1 != 0 && !matches!(r.id, 1 | 2)) {
        if record.id != 9 || !has_geometry {
            // In particular, do not interpret optional WarpRectilinear2 (14)
            // as WarpRectilinear. Since 14 was not applied, its immediately
            // following legacy warp remains the usable baseline fallback.
            skipped = true;
            continue;
        }
        match decode_stage3_record(record, width, height, allow_geometry) {
            Ok(Some(step)) if budget.admit(&step) => decoded[index] = Some(step),
            Ok(None) => {},
            Ok(Some(_)) => skipped = true,
            Err(e) if e.code == "UNSUPPORTED_DNG_OPCODE" => skipped = true,
            Err(e) => return Err(e),
        }
    }
    let steps: Vec<_> = decoded.into_iter().flatten().collect();
    if has_geometry {
        Ok((Vec::new(), Some(WarpPipeline { steps }), skipped))
    } else {
        Ok((steps.into_iter().filter_map(|step| match step {
            Stage3Opcode::GainMap(map) => Some(map),
            Stage3Opcode::Warp(_) => None,
        }).collect(), None, skipped))
    }
}

#[derive(Default)]
struct Stage3Budget { warps: usize, maps: usize, values: usize }
impl Stage3Budget {
    fn admit(&mut self, step: &crate::dng_warp::Stage3Opcode) -> bool {
        use crate::dng_warp::Stage3Opcode;
        match step {
            Stage3Opcode::Warp(_) => {
                if self.warps >= crate::dng_warp::MAX_WARPS { return false; }
                self.warps += 1;
            }
            Stage3Opcode::GainMap(map) => {
                let values = self.values + map.value_count(); // each map already bounded
                if self.maps >= crate::gainmap::MAX_MAPS || values > crate::gainmap::MAX_VALUES { return false; }
                self.maps += 1;
                self.values = values;
            }
        }
        true
    }
}

fn decode_stage3_record(
    record: &Record<'_>, width: usize, height: usize, allow_geometry: bool,
) -> Result<Option<crate::dng_warp::Stage3Opcode>> {
    use crate::dng_warp::{Stage3Opcode, Warp, WarpFisheye, WarpRectilinear};
    if !supported_version(record.version) { return Err(unsupported()); }
    let warp = match record.id {
        1 => Warp::Rectilinear(WarpRectilinear::parse(record.payload)?),
        2 => Warp::Fisheye(WarpFisheye::parse(record.payload)?),
        9 => return Ok(Some(Stage3Opcode::GainMap(crate::gainmap::parse_opcode(record.payload, width, height)?))),
        _ => return Err(unsupported()),
    };
    if warp.is_identity() { return Ok(None); }
    if !allow_geometry || width < 5 || height < 5 { return Err(unsupported()); }
    Ok(Some(Stage3Opcode::Warp(warp)))
}
/// A single set or one per image plane is standard. Every coefficient must be
/// exact identity, and the entire payload including finite center is validated.
pub(crate) fn identity_warp(payload: &[u8], planes: u32) -> Result<()> {
    let mut c = Cursor::new(payload);
    let n = c.u32()?;
    if n != 1 && n != planes {
        return Err(unsupported());
    }
    if payload.len() != 20 + n as usize * 48 {
        return Err(invalid());
    }
    for _ in 0..n {
        for expected in [1., 0., 0., 0., 0., 0.] {
            let value = c.f64()?;
            if !value.is_finite() {
                return Err(invalid());
            }
            if value != expected {
                return Err(unsupported());
            }
        }
    }
    for _ in 0..2 {
        let center = c.f64()?;
        if !center.is_finite() || !(0.0..=1.0).contains(&center) {
            return Err(invalid());
        }
    }
    if !c.finished() {
        return Err(invalid());
    }
    Ok(())
}
pub fn optional_only(bytes: &[u8]) -> Result<bool> {
    let records = records(bytes)?;
    if records.iter().any(|r| r.flags & 1 == 0) {
        return Err(unsupported());
    }
    Ok(!records.is_empty())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn record(flags: u32) -> Vec<u8> {
        [1u32, 9, 0x01030000, flags, 4, 0]
            .iter()
            .flat_map(|v| v.to_be_bytes())
            .collect()
    }
    #[test]
    fn optional_not_preview_only_or_mandatory() {
        assert!(optional_only(&record(1)).unwrap());
        for f in [0, 2] {
            assert_eq!(
                optional_only(&record(f)).unwrap_err().code,
                "UNSUPPORTED_DNG_OPCODE"
            );
        }
        assert_eq!(
            optional_only(&record(1)[..20]).unwrap_err().code,
            "INVALID_INPUT"
        );
        assert!(!optional_only(&[0, 0, 0, 0]).unwrap());
    }
}
