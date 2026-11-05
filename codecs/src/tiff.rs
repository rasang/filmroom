//! Bounded classic-TIFF metadata reader. Unknown metadata is never copied or logged.
use crate::error::{Result, err, io_error};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::File,
    io::{Read, Seek, SeekFrom},
};
#[derive(Clone, Copy, Debug)]
pub enum Endian {
    Le,
    Be,
}
impl Endian {
    pub fn u16(self, b: &[u8]) -> u16 {
        match self {
            Self::Le => u16::from_le_bytes([b[0], b[1]]),
            Self::Be => u16::from_be_bytes([b[0], b[1]]),
        }
    }
    pub fn u32(self, b: &[u8]) -> u32 {
        match self {
            Self::Le => u32::from_le_bytes(b[..4].try_into().unwrap()),
            Self::Be => u32::from_be_bytes(b[..4].try_into().unwrap()),
        }
    }
}
#[derive(Clone, Debug)]
pub struct Value {
    pub typ: u16,
    pub count: u32,
    pub bytes: Vec<u8>,
    pub endian: Endian,
}
impl Value {
    pub fn nums(&self) -> Result<Vec<f64>> {
        // A descriptor-only value must never be used as a numeric payload.
        let width = match self.typ {
            1 | 7 => 1,
            3 => 2,
            4 | 9 => 4,
            5 | 10 => 8,
            _ => return Err(err("UNSUPPORTED_DNG_VARIANT", "probe")),
        };
        if (self.count as usize).checked_mul(width) != Some(self.bytes.len()) {
            return Err(err("INVALID_INPUT", "probe"));
        }
        let mut out = Vec::with_capacity(self.count as usize);
        let e = self.endian;
        for i in 0..self.count as usize {
            out.push(match self.typ {
                1 | 7 => self.bytes[i] as f64,
                3 => e.u16(&self.bytes[i * 2..]) as f64,
                4 => e.u32(&self.bytes[i * 4..]) as f64,
                9 => e.u32(&self.bytes[i * 4..]) as i32 as f64,
                5 | 10 => {
                    let a = e.u32(&self.bytes[i * 8..]);
                    let b = e.u32(&self.bytes[i * 8 + 4..]);
                    if b == 0 {
                        return Err(err("INVALID_INPUT", "probe"));
                    }
                    if self.typ == 10 {
                        (a as i32 as f64) / (b as i32 as f64)
                    } else {
                        a as f64 / b as f64
                    }
                }
                _ => return Err(err("UNSUPPORTED_DNG_VARIANT", "probe")),
            });
        }
        Ok(out)
    }
    pub fn uints(&self) -> Result<Vec<u32>> {
        self.nums()?
            .iter()
            .map(|&x| {
                if x.is_finite() && x >= 0. && x <= u32::MAX as f64 && x.fract() == 0. {
                    Ok(x as u32)
                } else {
                    Err(err("INVALID_INPUT", "probe"))
                }
            })
            .collect()
    }
}
pub type Ifd = BTreeMap<u16, Value>;
pub struct Tiff {
    pub endian: Endian,
    pub ifds: Vec<Ifd>,
    pub ifd_offsets: Vec<u32>,
    pub size: u64,
}
const SELECTED: &[u16] = &[
    254, 256, 257, 258, 259, 262, 266, 271, 273, 274, 277, 278, 279, 284, 317, 322, 323, 324, 325, 330,
    339, 33421, 33422, 50706, 50707, 50710, 50711, 50712, 50713, 50714, 50715, 50716, 50717, 50718,
    50719, 50720, 50721, 50722, 50723, 50724, 50727, 50728, 50729, 50730, 50778, 50779, 50829,
    50830, 50931, 50932, 50981, 50982, 50983, 52529, 52531, 52544, 52548, 52549, 52550, 50937, 50938, 50939, 50940, 50941, 50942, 52525, 52530, 52532, 52547, 50964, 50965,
    50966, 50967, 50968, 50969, 50970, 50971, 50972, 50973, 50974, 50975, 51008, 51009, 51022,
    51041, 51089, 51107, 51108, 51109, 51110, 51111, 51112, 51113, 51114, 51115, 51116, 51117,
    52536, 52537,
];
impl Tiff {
    pub fn read(file: &mut File) -> Result<Self> {
        Self::read_mode(file, false)
    }
    pub(crate) fn read_camera(file: &mut File) -> Result<Self> {
        Self::read_mode(file, true)
    }
    fn read_mode(file: &mut File, camera: bool) -> Result<Self> {
        let size = file.metadata().map_err(|e| io_error(e, "probe"))?.len();
        if !(8..=512 * 1024 * 1024).contains(&size) {
            return Err(err("INVALID_INPUT", "probe"));
        }
        let mut h = [0; 8];
        file.read_exact(&mut h).map_err(|e| io_error(e, "probe"))?;
        let endian = match &h[..2] {
            b"II" => Endian::Le,
            b"MM" => Endian::Be,
            _ => return Err(err("UNSUPPORTED_FORMAT", "probe")),
        };
        if endian.u16(&h[2..]) != 42 && !(camera && (&h[..4] == b"II\x55\0" || crate::camera_raw::additional::orf_magic(&h))) {
            return Err(err("UNSUPPORTED_FORMAT", "probe"));
        }
        let panasonic = camera && &h[..4] == b"II\x55\0";
        let mut todo = vec![endian.u32(&h[4..])];
        let mut seen = BTreeSet::new();
        let mut ifds = Vec::new();
        let mut ifd_offsets = Vec::new();
        let mut bytes_total = 0usize;
        while let Some(off) = todo.pop() {
            if off == 0 {
                continue;
            }
            if !seen.insert(off) || seen.len() > 16 {
                return Err(err("INVALID_INPUT", "probe"));
            }
            if off as u64 + 2 > size {
                return Err(err("INVALID_INPUT", "probe"));
            }
            file.seek(SeekFrom::Start(off as u64))
                .map_err(|e| io_error(e, "probe"))?;
            let mut b = [0; 2];
            file.read_exact(&mut b).map_err(|e| io_error(e, "probe"))?;
            let n = endian.u16(&b) as usize;
            if n > 256 || off as u64 + 6 + n as u64 * 12 > size {
                return Err(err("INVALID_INPUT", "probe"));
            }
            let mut entries = vec![0; n * 12 + 4];
            file.read_exact(&mut entries)
                .map_err(|e| io_error(e, "probe"))?;
            todo.push(endian.u32(&entries[n * 12..]));
            let mut ifd = Ifd::new();
            let mut tags = BTreeSet::new();
            for ent in entries[..n * 12].chunks_exact(12) {
                let tag = endian.u16(ent);
                if !tags.insert(tag) {
                    return Err(err("INVALID_INPUT", "probe"));
                }
                // ProfileDynamicRange is not implemented. Keep presence only to
                // exclude the vivo default-SDR exception, without reading an
                // untrusted payload or changing legacy metadata admission.
                // This marker must never be interpreted through nums()/uints().
                if tag == 52551 {
                    ifd.insert(
                        tag,
                        Value {
                            typ: endian.u16(&ent[2..]),
                            count: endian.u32(&ent[4..]),
                            bytes: Vec::new(),
                            endian,
                        },
                    );
                    continue;
                }
                if !SELECTED.contains(&tag)
                    && !(panasonic && matches!(tag, 2..=11 | 45 | 280 | 289))
                    && !(camera
                        && matches!(
                            tag,
                            272 | 50740
                                | 0x7010
                                | 0x7300
                                | 0x7310
                                | 33424
                                | 34665
                                | 34675
                                | 34853
                                | 37500
                                | 50648
                                | 50752
                        ))
                {
                    continue;
                }
                let typ = endian.u16(&ent[2..]);
                let count = endian.u32(&ent[4..]);
                let ts = match typ {
                    1 | 2 | 6 | 7 => 1,
                    3 | 8 => 2,
                    4 | 9 | 11 => 4,
                    5 | 10 | 12 => 8,
                    _ => return Err(err("INVALID_INPUT", "probe")),
                };
                let len = (count as usize)
                    .checked_mul(ts)
                    .ok_or_else(|| err("INVALID_INPUT", "probe"))?;
                // Our matrix-based RAW development omits DNG HueSatMap color
                // correction. Keep only bounded descriptors, never allocate
                // its payload or claim the skipped float values were validated.
                if matches!(tag, 50938 | 50939) {
                    let at = endian.u32(&ent[8..]) as u64;
                    if typ != 11
                        || count == 0
                        || len <= 4
                        || at.checked_add(len as u64).is_none_or(|end| end > size)
                    {
                        return Err(err("INVALID_INPUT", "probe"));
                    }
                    ifd.insert(
                        tag,
                        Value {
                            typ,
                            count,
                            bytes: Vec::new(),
                            endian,
                        },
                    );
                    continue;
                }
                // Explicit typed capabilities, not a general metadata-limit bypass.
                // Product and finiteness checks are completed by each table parser.
                if tag==50712 && (typ!=3 || count==0 || count>65536) {
                    return Err(err("INVALID_COLOR_METADATA", "probe"));
                }
                if tag==52525 && (typ!=7 || len<68 || len>crate::profile_gain_table::MAX_BYTES) {
                    return Err(err("INVALID_COLOR_METADATA", "probe"));
                }
                if len
                    > (if tag==50712 {128*1024} else if tag==52525 {crate::profile_gain_table::MAX_BYTES} else if camera && tag == 37500 {
                        512 * 1024
                    } else {
                        65536
                    })
                    || bytes_total + len > 512 * 1024
                {
                    return Err(err("INVALID_INPUT", "probe"));
                }
                bytes_total += len;
                let mut bytes = vec![0; len];
                if len <= 4 {
                    bytes.copy_from_slice(&ent[8..8 + len])
                } else {
                    let at = endian.u32(&ent[8..]) as u64;
                    if at.checked_add(len as u64).is_none_or(|x| x > size) {
                        return Err(err("INVALID_INPUT", "probe"));
                    }
                    file.seek(SeekFrom::Start(at))
                        .map_err(|e| io_error(e, "probe"))?;
                    file.read_exact(&mut bytes)
                        .map_err(|e| io_error(e, "probe"))?;
                }
                let v = Value {
                    typ,
                    count,
                    bytes,
                    endian,
                };
                if camera && matches!(tag, 33424 | 34665 | 34675 | 34853) {
                    if count > 8 {
                        return Err(err("INVALID_INPUT", "probe"));
                    }
                    todo.extend(v.uints()?.into_iter().rev());
                }
                ifd.insert(tag, v);
            }
            if let Some(sub) = ifd.get(&330) {
                if sub.count > 8 {
                    return Err(err("INVALID_INPUT", "probe"));
                }
                // The A100 transitional container uses SubIFDs as an entropy offset.
                let legacy_a100 = camera
                    && ifd.get(&271).is_some_and(|v| {
                        v.typ == 2
                            && v.bytes
                                .strip_suffix(&[0])
                                .unwrap_or(&v.bytes)
                                .trim_ascii_end()
                                == b"SONY"
                    })
                    && ifd
                        .get(&272)
                        .is_some_and(|v| v.typ == 2 && v.bytes == b"DSLR-A100\0");
                if !legacy_a100 {
                    todo.extend(sub.uints()?.into_iter().rev());
                }
            }
            ifd_offsets.push(off);
            ifds.push(ifd);
        }
        if ifds.is_empty() {
            return Err(err("INVALID_INPUT", "probe"));
        }
        Ok(Self {
            endian,
            ifds,
            ifd_offsets,
            size,
        })
    }
}
pub fn uint(ifd: &Ifd, tag: u16, default: Option<u32>) -> Result<u32> {
    if let Some(v) = ifd.get(&tag) {
        let n = v.uints()?;
        if n.len() != 1 {
            return Err(err("INVALID_INPUT", "probe"));
        }
        Ok(n[0])
    } else {
        default.ok_or_else(|| err("INVALID_INPUT", "probe"))
    }
}
pub fn nums(ifd: &Ifd, tag: u16) -> Result<Option<Vec<f64>>> {
    ifd.get(&tag).map(Value::nums).transpose()
}

#[cfg(test)]
mod camera_metadata_tests {
    use super::*;
    #[test]
    fn camera_makernote_can_exceed_legacy_per_tag_limit_but_not_total_bound() {
        for (index, size) in [122_574usize, 600_000].into_iter().enumerate() {
            let path = std::env::temp_dir()
                .join(format!("camera-maker-bound-{}-{index}", std::process::id()));
            let mut bytes = vec![b'I', b'I', 42, 0, 8, 0, 0, 0, 1, 0];
            bytes.extend_from_slice(&37500u16.to_le_bytes());
            bytes.extend_from_slice(&7u16.to_le_bytes());
            bytes.extend_from_slice(&(size as u32).to_le_bytes());
            bytes.extend_from_slice(&26u32.to_le_bytes());
            bytes.extend_from_slice(&0u32.to_le_bytes());
            bytes.resize(26 + size, 0);
            std::fs::write(&path, bytes).unwrap();
            let camera = Tiff::read_camera(&mut File::open(&path).unwrap());
            assert_eq!(camera.is_ok(), size <= 512 * 1024);
            // DNG's legacy scan still ignores MakerNote payload rather than loading it.
            let legacy = Tiff::read(&mut File::open(&path).unwrap()).unwrap();
            assert!(!legacy.ifds[0].contains_key(&37500));
            std::fs::remove_file(path).unwrap();
        }
    }
}
