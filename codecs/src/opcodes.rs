//! Bounded DNG opcode envelope parser. Only explicitly OPTIONAL records may be
//! skipped. AOSP's DngUtils emits these with flags bit0 set; this is not a claim
//! to implement GainMap, WarpRectilinear or bad-pixel correction.
use crate::error::{Result, err};
pub fn optional_only(bytes: &[u8]) -> Result<bool> {
    if bytes.len() < 4 {
        return Err(err("INVALID_INPUT", "probe"));
    }
    let n = u32::from_be_bytes(bytes[0..4].try_into().unwrap()) as usize;
    if n > 64 {
        return Err(err("UNSUPPORTED_DNG_OPCODE", "probe"));
    }
    let mut cursor = 4usize;
    for _ in 0..n {
        let end = cursor
            .checked_add(16)
            .filter(|&e| e <= bytes.len())
            .ok_or_else(|| err("INVALID_INPUT", "probe"))?;
        let header = &bytes[cursor..end];
        let id = u32::from_be_bytes(header[0..4].try_into().unwrap());
        let version = u32::from_be_bytes(header[4..8].try_into().unwrap());
        let flags = u32::from_be_bytes(header[8..12].try_into().unwrap());
        let size = u32::from_be_bytes(header[12..16].try_into().unwrap()) as usize;
        if id == 0 || version == 0 || flags & !3 != 0 {
            return Err(err("INVALID_INPUT", "probe"));
        }
        cursor = end
            .checked_add(size)
            .filter(|&e| e <= bytes.len())
            .ok_or_else(|| err("INVALID_INPUT", "probe"))?;
        if flags & 1 == 0 {
            return Err(err("UNSUPPORTED_DNG_OPCODE", "probe"));
        }
    }
    if cursor != bytes.len() {
        return Err(err("INVALID_INPUT", "probe"));
    }
    Ok(n > 0)
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
        assert_eq!(
            optional_only(&record(0)).unwrap_err().code,
            "UNSUPPORTED_DNG_OPCODE"
        );
        assert_eq!(
            optional_only(&record(2)).unwrap_err().code,
            "UNSUPPORTED_DNG_OPCODE"
        );
        assert_eq!(
            optional_only(&record(1)[..20]).unwrap_err().code,
            "INVALID_INPUT"
        );
        assert!(!optional_only(&[0, 0, 0, 0]).unwrap());
    }
}
