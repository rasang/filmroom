//! Bounded structural firewall before the upstream ISO-BMFF metadata parser.
use super::*;
const DESC: [u8; 16] = [
    0x85, 0xc0, 0xb6, 0x87, 0x82, 0x0f, 0x11, 0xe0, 0x81, 0x11, 0xf4, 0xce, 0x46, 0x2b, 0x6a, 0x48,
];
struct Scan {
    boxes: usize,
    copied: u64,
    ftyp: usize,
    moov: usize,
    mdat: usize,
    desc: usize,
}
#[track_caller]
fn bad() -> crate::error::CodecError {
    err("INVALID_INPUT", "probe")
}
fn read_at(f: &mut File, at: u64, n: usize) -> Result<Vec<u8>> {
    f.seek(SeekFrom::Start(at))
        .map_err(|e| io_error(e, "probe"))?;
    let mut b = vec![0; n];
    f.read_exact(&mut b).map_err(|e| io_error(e, "probe"))?;
    Ok(b)
}
fn u32be(b: &[u8], at: usize) -> u32 {
    u32::from_be_bytes(b[at..at + 4].try_into().unwrap())
}
impl Scan {
    fn region(&mut self, f: &mut File, start: u64, end: u64, depth: usize) -> Result<usize> {
        if depth > 12 {
            return Err(bad());
        }
        let mut at = start;
        let mut count = 0;
        let mut unique = std::collections::BTreeSet::new();
        while at < end {
            if end - at < 8 || self.boxes >= 512 {
                return Err(bad());
            }
            self.boxes += 1;
            count += 1;
            let h = read_at(f, at, 8)?;
            let typ: &[u8] = &h[4..8];
            let mut size = u32be(&h, 0) as u64;
            let mut header = 8;
            if size == 1 {
                if end - at < 16 {
                    return Err(bad());
                }
                size = u64::from_be_bytes(read_at(f, at + 8, 8)?.try_into().unwrap());
                header = 16;
            }
            // Zero-sized boxes and extended ftyp are not used by admitted still CR3 files.
            if size < header || size == 0 || at.checked_add(size).is_none_or(|n| n > end) {
                return Err(bad());
            }
            let stop = at + size;
            let mut payload = at + header;
            let mut is_desc = false;
            if typ == b"uuid" {
                if stop - payload < 16 {
                    return Err(bad());
                }
                is_desc = read_at(f, payload, 16)?.as_slice() == DESC;
                payload += 16;
            }
            let bytes = stop - payload;
            if matches!(
                typ,
                b"ftyp"
                    | b"moov"
                    | b"mdat"
                    | b"CNCV"
                    | b"CCTP"
                    | b"CTBO"
                    | b"CMT1"
                    | b"CMT2"
                    | b"CMT3"
                    | b"CMT4"
                    | b"stsd"
                    | b"stsc"
                    | b"stsz"
                    | b"co64"
                    | b"stts"
                    | b"CMP1"
                    | b"CDI1"
                    | b"IAD1"
            ) && !unique.insert(typ.to_vec())
            {
                return Err(bad());
            }
            let minimum = match typ {
                b"CNCV" => 30,
                b"CCDT" => 16,
                b"JPEG" | b"hdlr" | b"tkhd" => 4,
                b"vmhd" => 12,
                _ => 0,
            };
            if bytes < minimum {
                return Err(bad());
            }
            if matches!(typ, b"mvhd" | b"mdhd") {
                if bytes < 4 {
                    return Err(bad());
                }
                let version = read_at(f, payload, 1)?[0];
                let minimum = match (typ, version) {
                    (b"mvhd", 0) => 20,
                    (b"mvhd", 1) => 32,
                    (b"mdhd", 0) => 22,
                    (b"mdhd", 1) => 34,
                    _ => return Err(bad()),
                };
                if bytes < minimum {
                    return Err(bad());
                }
            }
            if typ == b"IAD1" {
                if bytes < 16 {
                    return Err(bad());
                }
                let v = read_at(f, payload, 16)?;
                let kind = u16::from_be_bytes([v[10], v[11]]);
                if !matches!((kind, bytes), (0, 32) | (2, 48)) {
                    return Err(bad());
                }
            }
            let child_skip = match typ {
                b"moov" | b"trak" | b"mdia" | b"minf" | b"stbl" => Some(0),
                b"stsd" => Some(8),
                b"CRAW" => Some(82),
                b"CDI1" => Some(4),
                b"CCTP" => Some(12),
                b"uuid" if is_desc => Some(0),
                _ => None,
            };
            if depth == 0 {
                match typ {
                    b"ftyp" => {
                        self.ftyp += 1;
                        if at != 0
                            || header != 8
                            || !(8..=256).contains(&bytes)
                            || bytes % 4 != 0
                            || read_at(f, payload, 4)?.as_slice() != b"crx "
                        {
                            return Err(bad());
                        }
                    }
                    b"moov" => self.moov += 1,
                    b"mdat" => {
                        self.mdat += 1;
                        if bytes == 0 {
                            return Err(bad());
                        }
                    }
                    _ => {}
                }
            }
            if is_desc {
                self.desc += 1;
            }
            if matches!(typ, b"CMT1" | b"CMT2" | b"CMT3" | b"CMT4" | b"CNOP") {
                self.copied = self.copied.checked_add(bytes).ok_or_else(|| bad())?;
                if bytes > 1024 * 1024 || self.copied > 4 * 1024 * 1024 {
                    return Err(err("MEMORY_BUDGET", "probe"));
                }
                if typ != b"CNOP" && bytes < 8 {
                    return Err(bad());
                }
            }
            if matches!(typ, b"stts" | b"stsc" | b"co64" | b"stsz") {
                let fixed = if typ == b"stsz" { 12 } else { 8 };
                if bytes < fixed {
                    return Err(bad());
                }
                let v = read_at(f, payload, fixed as usize)?;
                let n = u32be(&v, if typ == b"stsz" { 8 } else { 4 }) as u64;
                if n == 0 || n > 4096 {
                    return Err(bad());
                }
                let entry = match typ {
                    b"stsc" => 12,
                    b"co64" | b"stts" => 8,
                    b"stsz" if u32be(&v, 4) == 0 => 4,
                    _ => 0,
                };
                if fixed + n * entry != bytes {
                    return Err(bad());
                }
            }
            if typ == b"CTMD" {
                if bytes < 12 {
                    return Err(bad());
                }
                let n = u32be(&read_at(f, payload, 12)?, 8) as u64;
                if n == 0 || n > 64 || 12 + n * 8 != bytes {
                    return Err(bad());
                }
            }
            if typ == b"CMP1" {
                if bytes < 36 || bytes > 256 {
                    return Err(bad());
                }
                let v = read_at(f, payload, 36)?;
                if u32be(&v, 32) & 0x80000000 != 0 && v[25] >> 4 == 4 {
                    if bytes < 60 {
                        return Err(bad());
                    }
                    if size > 84
                        && u32be(&read_at(f, payload + 56, 4)?, 0) & 0x40000000 != 0
                        && bytes < 85
                    {
                        return Err(bad());
                    }
                }
                crate::validate_dimensions(u32be(&v, 8), u32be(&v, 12))?;
                let width = u32be(&v, 8);
                let height = u32be(&v, 12);
                let tw = u32be(&v, 16);
                let th = u32be(&v, 20);
                if width % 2 != 0
                    || height % 2 != 0
                    || tw < 8
                    || th < 8
                    || tw > width
                    || th > height
                    || tw % 2 != 0
                    || th % 2 != 0
                {
                    return Err(bad());
                }
                if width.div_ceil(tw) as u64 * height.div_ceil(th) as u64 > 64 {
                    return Err(unsupported());
                }
            }
            if let Some(skip) = child_skip {
                if bytes < skip {
                    return Err(bad());
                }
                let expected = if typ == b"stsd" || typ == b"CCTP" {
                    let n = if typ == b"stsd" {
                        u32be(&read_at(f, payload, 8)?, 4)
                    } else {
                        u32be(&read_at(f, payload, 12)?, 8)
                    } as usize;
                    if n == 0 || n > 16 {
                        return Err(bad());
                    }
                    Some(n)
                } else {
                    None
                };
                let children = self.region(f, payload + skip, stop, depth + 1)?;
                if expected.is_some_and(|n| n != children) {
                    return Err(bad());
                }
            }
            at = stop;
        }
        Ok(count)
    }
}
pub(super) fn validate(file: &mut File, size: u64) -> Result<()> {
    let mut scan = Scan {
        boxes: 0,
        copied: 0,
        ftyp: 0,
        moov: 0,
        mdat: 0,
        desc: 0,
    };
    scan.region(file, 0, size, 0)?;
    if (scan.ftyp, scan.moov, scan.mdat, scan.desc) != (1, 1, 1, 1) {
        return Err(bad());
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn box_of(typ: &[u8; 4], p: &[u8]) -> Vec<u8> {
        let mut b = ((p.len() + 8) as u32).to_be_bytes().to_vec();
        b.extend(typ);
        b.extend(p);
        b
    }
    fn check(data: &[u8]) -> Result<()> {
        let p = std::env::temp_dir().join(format!(
            "filmroom-cr3-boxes-{}-{}",
            std::process::id(),
            data.len()
        ));
        let mut f = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&p)
            .unwrap();
        std::fs::remove_file(p).unwrap();
        use std::io::Write;
        f.write_all(data).unwrap();
        validate(&mut f, data.len() as u64)
    }
    #[test]
    fn malformed_box_sizes_rejected() {
        assert!(check(&[0; 16]).is_err());
        let mut b = box_of(b"ftyp", b"crx \0\0\0\0crx ");
        b.extend([
            0, 0, 0, 1, b'm', b'o', b'o', b'v', 255, 255, 255, 255, 255, 255, 255, 255,
        ]);
        assert!(check(&b).is_err());
    }
    #[test]
    fn huge_stsz_count_rejected_before_allocation() {
        let mut b = box_of(b"ftyp", b"crx \0\0\0\0crx ");
        b.extend(box_of(
            b"moov",
            &box_of(b"stsz", &[0, 0, 0, 0, 0, 0, 0, 1, 255, 255, 255, 255]),
        ));
        assert!(check(&b).is_err());
    }
}
