//! Bounded standard DNG opcode envelopes and strict structural identity checks.
//! Required unsupported processing always fails closed; optional skips are explicit.
use crate::error::{Result, err};
#[path = "opcode_stage1.rs"]
mod stage1;
pub use stage1::{Stage1Opcode, apply_stage1_sample, parse_stage1};
pub(crate) fn invalid() -> crate::error::CodecError {
    err("INVALID_INPUT", "probe")
}
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
            .ok_or_else(invalid)?;
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
