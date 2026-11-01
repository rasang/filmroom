//! Failure-only numeric TIFF diagnostics: no text, EXIF/GPS or pixel offsets.
use crate::tiff::Endian;
use serde_json::{Map, Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::File,
    io::{Read, Seek, SeekFrom},
    path::Path,
};
const MAX_READ: usize = 64 * 1024;
type Fields = Map<String, Value>;
struct Reader {
    file: File,
    size: u64,
    remaining: usize,
}
impl Reader {
    fn read(&mut self, offset: u64, len: usize) -> Result<Vec<u8>, u8> {
        if len > self.remaining {
            return Err(2);
        }
        if offset
            .checked_add(len as u64)
            .filter(|&end| end <= self.size)
            .is_none()
        {
            return Err(1);
        }
        self.remaining -= len;
        self.file.seek(SeekFrom::Start(offset)).map_err(|_| 1u8)?;
        let mut b = vec![0; len];
        self.file.read_exact(&mut b).map_err(|_| 1u8)?;
        Ok(b)
    }
}
fn put(m: &mut Fields, key: &str, value: u64) {
    m.insert(key.into(), json!(value));
}
/// Status: 0 complete, 1 malformed/unsupported, 2 budget. Never decodes pixels.
pub fn probe(path: &Path) -> Value {
    let mut fields = Fields::new();
    let status = (|| -> Result<(), u8> {
        let file = File::open(path).map_err(|_| 1u8)?;
        let size = file.metadata().map_err(|_| 1u8)?.len();
        if size > 512 * 1024 * 1024 {
            return Err(2);
        }
        put(&mut fields, "rawFileBytes", size);
        let mut r = Reader {
            file,
            size,
            remaining: MAX_READ,
        };
        scan(&mut r, &mut fields)
    })()
    .err()
    .unwrap_or(0);
    put(&mut fields, "rawProbeStatus", status as u64);
    Value::Object(fields)
}
fn scan(r: &mut Reader, out: &mut Fields) -> Result<(), u8> {
    let h = r.read(0, 8)?;
    let e = match &h[..2] {
        b"II" => Endian::Le,
        b"MM" => Endian::Be,
        _ => return Err(1),
    };
    if e.u16(&h[2..]) != 42 {
        return Err(1);
    }
    let mut todo = vec![e.u32(&h[4..])];
    let mut seen = BTreeSet::new();
    let mut best = None;
    let mut root_opcodes = BTreeMap::<u16, Fields>::new();
    let mut root_calibration = 0u64;
    while let Some(off) = todo.pop() {
        if off == 0 {
            continue;
        }
        if seen.contains(&off) {
            return Err(1);
        }
        if seen.len() >= 16 {
            return Err(2);
        }
        seen.insert(off);
        let n = e.u16(&r.read(off as u64, 2)?) as usize;
        if n > 256 {
            return Err(2);
        }
        let entries = r.read(off as u64 + 2, n * 12 + 4)?;
        todo.push(e.u32(&entries[n * 12..]));
        let mut tags = BTreeSet::new();
        let mut candidate = Fields::new();
        let mut opcode_lists = root_opcodes.clone();
        let mut subfile_type = 0u32;
        if root_calibration != 0 {
            put(&mut candidate, "rawCalibrationMask", root_calibration);
        }
        put(&mut candidate, "rawIfdEntries", n as u64);
        let parsed = (|| -> Result<(), u8> {
            for ent in entries[..n * 12].chunks_exact(12) {
                let tag = e.u16(ent);
                if !tags.insert(tag) {
                    return Err(1);
                }
                if matches!(tag, 50723 | 50724) {
                    let mask = candidate
                        .get("rawCalibrationMask")
                        .and_then(Value::as_u64)
                        .unwrap_or(0);
                    put(
                        &mut candidate,
                        "rawCalibrationMask",
                        mask | if tag == 50723 { 1 } else { 2 },
                    );
                    continue;
                }
                if matches!(tag, 51008 | 51009 | 51022) {
                    let mut summary = Fields::new();
                    let result = opcode(r, e, ent, &mut summary);
                    opcode_lists.insert(tag, summary);
                    result?;
                    continue;
                }
                let max_count = match tag {
                    254 | 256 | 257 | 259 | 262 | 277 | 278 | 322 | 323 => 1,
                    258 => 4,
                    33421 => 2,
                    50706 => 4,
                    330 => 16,
                    279 | 325 => 4096,
                    _ => continue,
                };
                let typ = e.u16(&ent[2..]);
                let count = e.u32(&ent[4..]) as usize;
                if matches!(tag, 279 | 325) {
                    put(&mut candidate, "rawBlockCount", count as u64);
                }
                if count == 0 {
                    return Err(1);
                }
                if count > max_count {
                    return Err(2);
                }
                let unit = match typ {
                    1 => 1,
                    3 => 2,
                    4 => 4,
                    _ => return Err(1),
                };
                let len = count * unit;
                let b = if len <= 4 {
                    ent[8..8 + len].to_vec()
                } else {
                    r.read(e.u32(&ent[8..]) as u64, len)?
                };
                let nums: Vec<u32> = b
                    .chunks_exact(unit)
                    .map(|b| match unit {
                        1 => b[0] as u32,
                        2 => e.u16(b) as u32,
                        _ => e.u32(b),
                    })
                    .collect();
                match tag {
                    254 => {
                        subfile_type = nums[0];
                    }
                    330 => {
                        if todo.len() + nums.len() > 32 {
                            return Err(2);
                        }
                        todo.extend(nums);
                    }
                    50706 => {
                        if typ != 1 || count != 4 {
                            return Err(1);
                        }
                        if seen.len() == 1 {
                            put(
                                out,
                                "rawDngVersion",
                                u32::from_be_bytes([b[0], b[1], b[2], b[3]]) as u64,
                            );
                        }
                    }
                    258 => {
                        put(
                            &mut candidate,
                            "rawBitsMin",
                            *nums.iter().min().unwrap() as u64,
                        );
                        put(
                            &mut candidate,
                            "rawBitsMax",
                            *nums.iter().max().unwrap() as u64,
                        );
                    }
                    33421 => {
                        if count != 2 {
                            return Err(1);
                        }
                        put(&mut candidate, "rawCfaRows", nums[0] as u64);
                        put(&mut candidate, "rawCfaCols", nums[1] as u64);
                    }
                    279 | 325 => {
                        put(
                            &mut candidate,
                            "rawMaxBlockBytes",
                            *nums.iter().max().unwrap() as u64,
                        );
                        put(&mut candidate, "rawBlockCount", count as u64);
                    }
                    _ => {
                        let key = match tag {
                            256 => "rawWidth",
                            257 => "rawHeight",
                            259 => "rawCompression",
                            262 => "rawPhotometric",
                            277 => "rawSamplesPerPixel",
                            278 => "rawRowsPerStrip",
                            322 => "rawTileWidth",
                            323 => "rawTileHeight",
                            _ => unreachable!(),
                        };
                        put(&mut candidate, key, nums[0] as u64);
                    }
                }
            }
            Ok(())
        })();
        if seen.len() == 1 {
            root_opcodes = opcode_lists.clone();
            root_calibration = candidate
                .get("rawCalibrationMask")
                .and_then(Value::as_u64)
                .unwrap_or(0);
        }
        for summary in opcode_lists.values() {
            for key in ["rawOpcodeBytes", "rawOpcodeCount"] {
                if let Some(n) = summary.get(key).and_then(Value::as_u64) {
                    let previous = candidate.get(key).and_then(Value::as_u64).unwrap_or(0);
                    put(&mut candidate, key, previous + n);
                }
            }
            if !candidate.contains_key("rawRequiredOpcodeId") {
                for key in [
                    "rawRequiredOpcodeId",
                    "rawRequiredOpcodeVersion",
                    "rawRequiredOpcodeFlags",
                ] {
                    if let Some(v) = summary.get(key) {
                        candidate.insert(key.into(), v.clone());
                    }
                }
            }
        }
        let get = |key: &str| candidate.get(key).and_then(Value::as_u64).unwrap_or(0);
        let score = (
            matches!(get("rawPhotometric"), 32803 | 34892) && subfile_type & 1 == 0,
            get("rawWidth") * get("rawHeight"),
        );
        if best.is_none_or(|previous| score > previous) {
            best = Some(score);
            out.retain(|k, _| matches!(k.as_str(), "rawFileBytes" | "rawDngVersion"));
            out.extend(candidate);
        }
        parsed?;
    }
    Ok(())
}

// DNG opcode headers are always big endian. Payloads are skipped, not read.
fn opcode(r: &mut Reader, e: Endian, ent: &[u8], out: &mut Fields) -> Result<(), u8> {
    if !matches!(e.u16(&ent[2..]), 1 | 7) {
        return Err(1);
    }
    let len = e.u32(&ent[4..]) as u64;
    if len < 4 {
        return Err(1);
    }
    let start = if len == 4 { 0 } else { e.u32(&ent[8..]) as u64 };
    if len > r.size || (len > 4 && start.checked_add(len).filter(|&v| v <= r.size).is_none()) {
        return Err(1);
    }
    let count = if len == 4 {
        Endian::Be.u32(&ent[8..])
    } else {
        Endian::Be.u32(&r.read(start, 4)?)
    };
    if count > 64 {
        return Err(2);
    }
    let total = out
        .get("rawOpcodeBytes")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        + len;
    put(out, "rawOpcodeBytes", total);
    let total = out
        .get("rawOpcodeCount")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        + count as u64;
    put(out, "rawOpcodeCount", total);
    let mut pos = 4u64;
    for _ in 0..count {
        if pos + 16 > len {
            return Err(1);
        }
        let header = r.read(start + pos, 16)?;
        let id = Endian::Be.u32(&header);
        let version = Endian::Be.u32(&header[4..]);
        let flags = Endian::Be.u32(&header[8..]);
        let bytes = Endian::Be.u32(&header[12..]) as u64;
        if id == 0 || version == 0 || flags & !3 != 0 {
            return Err(1);
        }
        if flags & 1 == 0 && !out.contains_key("rawRequiredOpcodeId") {
            put(out, "rawRequiredOpcodeId", id as u64);
            put(out, "rawRequiredOpcodeVersion", version as u64);
            put(out, "rawRequiredOpcodeFlags", flags as u64);
        }
        pos += 16 + bytes;
        if pos > len {
            return Err(1);
        }
    }
    if pos != len {
        return Err(1);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    fn tiff(tags: &[(u16, u16, u32, u32)]) -> Vec<u8> {
        let mut b = b"II\x2a\x00\x08\x00\x00\x00".to_vec();
        b.extend((tags.len() as u16).to_le_bytes());
        for &(tag, typ, count, value) in tags {
            b.extend(tag.to_le_bytes());
            b.extend(typ.to_le_bytes());
            b.extend(count.to_le_bytes());
            b.extend(value.to_le_bytes());
        }
        b.extend(0u32.to_le_bytes());
        b
    }
    fn run(bytes: &[u8]) -> Value {
        let p = std::env::temp_dir().join(format!(
            "film-technical-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&p, bytes).unwrap();
        let v = probe(&p);
        std::fs::remove_file(p).unwrap();
        v
    }
    #[test]
    fn fifty_mp_large_strip_and_privacy() {
        let mut b = tiff(&[
            (256, 4, 1, 8192),
            (257, 4, 1, 6144),
            (258, 3, 1, 16),
            (259, 3, 1, 7),
            (262, 3, 1, 32803),
            (278, 4, 1, 6144),
            (279, 4, 1, 50_600_000),
            (33421, 3, 2, 0x00020002),
            // Invalid pointers and huge textual counts must not even be followed.
            (271, 2, u32::MAX, u32::MAX),
            (272, 2, u32::MAX, u32::MAX),
            (34853, 4, 1, u32::MAX),
            (34665, 4, 1, u32::MAX),
            (50721, 10, u32::MAX, u32::MAX),
        ]);
        b.extend(b"PRIVATE_NAME GPS COORDINATES CAMERA_MODEL");
        let v = run(&b);
        assert_eq!(v["rawProbeStatus"], 0);
        assert_eq!(v["rawWidth"], 8192);
        assert_eq!(v["rawHeight"], 6144);
        assert_eq!(v["rawMaxBlockBytes"], 50_600_000);
        assert_eq!(v["rawCfaRows"], 2);
        assert!(v.as_object().unwrap().values().all(Value::is_u64));
        assert!(!v.to_string().contains("PRIVATE"));
        assert!(!v.to_string().contains("GPS"));
    }
    #[test]
    fn malformed_and_bounded() {
        assert_eq!(run(&[])["rawProbeStatus"], 1);
        assert_eq!(run(&tiff(&[(258, 3, u32::MAX, 0)]))["rawProbeStatus"], 2);
        assert_eq!(run(&tiff(&[(279, 4, 2, u32::MAX)]))["rawProbeStatus"], 1);
        assert_eq!(
            run(&tiff(&[(256, 4, 1, 1), (256, 4, 1, 2)]))["rawProbeStatus"],
            1
        );
        assert_eq!(run(&tiff(&[(330, 4, 1, 8)]))["rawProbeStatus"], 1);
        let mut b = tiff(&[]);
        b[8..10].copy_from_slice(&257u16.to_le_bytes());
        assert_eq!(run(&b)["rawProbeStatus"], 2);
    }
    #[test]
    fn required_opcode_headers_only() {
        let tags = [(256, 4, 1, 8192), (257, 4, 1, 6144), (51008, 7, 24, 50)];
        let mut b = tiff(&tags);
        assert_eq!(b.len(), 50);
        for n in [1u32, 9, 0x01030000, 0, 4] {
            b.extend(n.to_be_bytes());
        }
        b.extend(b"GPS!");
        let v = run(&b);
        assert_eq!(v["rawProbeStatus"], 0);
        assert_eq!(v["rawRequiredOpcodeId"], 9);
        assert_eq!(v["rawRequiredOpcodeVersion"], 0x01030000u32);
        assert_eq!(v["rawOpcodeBytes"], 24);
        assert!(!v.to_string().contains("GPS"));
        b[66..70].copy_from_slice(&u32::MAX.to_be_bytes());
        let malformed = run(&b);
        assert_eq!(malformed["rawProbeStatus"], 1);
        assert_eq!(malformed["rawWidth"], 8192);
    }
    #[test]
    fn aggregate_read_budget() {
        let p = std::env::temp_dir().join(format!("film-readbudget-{}", std::process::id()));
        std::fs::write(&p, vec![0u8; MAX_READ + 1]).unwrap();
        let mut r = Reader {
            file: File::open(&p).unwrap(),
            size: (MAX_READ + 1) as u64,
            remaining: MAX_READ,
        };
        assert!(r.read(0, MAX_READ).is_ok());
        assert_eq!(r.read(0, 1).unwrap_err(), 2);
        std::fs::remove_file(p).unwrap();
    }
    #[test]
    fn root_opcode_and_calibration_inherit_but_raw_list_overrides() {
        for override_root in [false, true] {
            let mut raw_tags = vec![(256, 4, 1, 8192), (257, 4, 1, 6144), (262, 3, 1, 32803)];
            if override_root {
                raw_tags.push((51008, 7, 4, 0));
            }
            let raw = tiff(&raw_tags);
            let offset = 50 + (raw.len() - 8) as u32;
            let mut b = tiff(&[
                (330, 4, 1, 50),
                (50723, 10, 9, u32::MAX),
                (51008, 7, 24, offset),
            ]);
            b.extend(&raw[8..]);
            for n in [1u32, 9, 0x01030000, 0, 4] {
                b.extend(n.to_be_bytes());
            }
            b.extend(b"GPS!");
            let v = run(&b);
            assert_eq!(v["rawProbeStatus"], 0);
            assert_eq!(v["rawWidth"], 8192);
            assert_eq!(v["rawCalibrationMask"], 1);
            if override_root {
                assert!(v.get("rawRequiredOpcodeId").is_none());
                assert_eq!(v["rawOpcodeCount"], 0);
            } else {
                assert_eq!(v["rawRequiredOpcodeId"], 9);
                assert_eq!(v["rawOpcodeBytes"], 24);
            }
        }
    }
    #[test]
    fn big_endian_tiff() {
        let b = [
            b'M', b'M', 0, 42, 0, 0, 0, 8, 0, 1, 1, 0, 0, 4, 0, 0, 0, 1, 0, 0, 32, 0, 0, 0, 0, 0,
        ];
        let v = run(&b);
        assert_eq!(v["rawProbeStatus"], 0);
        assert_eq!(v["rawWidth"], 8192);
    }
}
