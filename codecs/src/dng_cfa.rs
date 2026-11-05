//! Bounded two-component SOF3 CFA strips and MSB-first packed CFA samples.
//! The JPEG components represent adjacent sensor samples, not color channels.
//! Only predictor 1, no point transform, and row-aligned restarts are admitted.
//! Complete entropy and EOI must pass before development. Only uniform final
//! fill bits and at most 4095 zero TIFF alignment bytes after EOI are accepted.
use crate::error::{Result, err, io_error};
use std::{
    fs::File,
    io::{BufReader, Read, Seek, SeekFrom, Take},
    sync::atomic::{AtomicBool, Ordering},
};
fn invalid() -> crate::error::CodecError {
    err("INVALID_INPUT", "decode")
}
fn unsupported() -> crate::error::CodecError {
    err("UNSUPPORTED_COMPRESSION", "decode")
}
struct Input<'a> {
    r: BufReader<Take<&'a mut File>>,
    byte: u8,
    left: u8,
}
impl Input<'_> {
    fn byte(&mut self) -> Result<u8> {
        let mut b = [0];
        self.r.read_exact(&mut b).map_err(|e| {
            if e.kind() == std::io::ErrorKind::UnexpectedEof {
                invalid()
            } else {
                io_error(e, "decode")
            }
        })?;
        Ok(b[0])
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes([self.byte()?, self.byte()?]))
    }
    fn marker(&mut self) -> Result<u8> {
        if self.byte()? != 255 {
            return Err(invalid());
        }
        self.byte()
    }
    fn bits(&mut self, n: u8) -> Result<u32> {
        let mut v = 0;
        for _ in 0..n {
            if self.left == 0 {
                self.byte = self.byte()?;
                if self.byte == 255 && self.byte()? != 0 {
                    return Err(invalid());
                }
                self.left = 8;
            }
            self.left -= 1;
            v = (v << 1) | u32::from((self.byte >> self.left) & 1);
        }
        Ok(v)
    }
    fn align(&mut self) -> Result<()> {
        if self.left > 0 {
            let mask = (1u8 << self.left) - 1;
            let fill = self.byte & mask;
            // Camera writers use both zero and one alignment bits. These are
            // checked only after the exact declared sample count, never used
            // to invent missing entropy samples or a missing marker.
            if fill != 0 && fill != mask {
                return Err(invalid());
            }
        }
        self.left = 0;
        Ok(())
    }
}
#[derive(Clone)]
struct Huff {
    first: [u32; 16],
    count: [u32; 16],
    index: [usize; 16],
    values: Vec<u8>,
}
impl Huff {
    fn parse(s: &[u8]) -> Result<(Self, usize)> {
        if s.len() < 16 {
            return Err(invalid());
        }
        let n = s[..16].iter().map(|&x| usize::from(x)).sum::<usize>();
        if n == 0 || n > 17 || s.len() < 16 + n {
            return Err(invalid());
        }
        let mut code = 0u32;
        let mut at = 0;
        let mut first = [0; 16];
        let mut count = [0; 16];
        let mut index = [0; 16];
        for i in 0..16 {
            first[i] = code;
            count[i] = s[i] as u32;
            index[i] = at;
            for _ in 0..s[i] {
                // Some camera DNGs use a complete tree, including an all-ones
                // code. Exact sample count and bounded entropy input make this
                // unambiguous; reject only oversubscribed prefix trees.
                if code >= (1u32 << (i + 1)) || s[16 + at] > 16 {
                    return Err(invalid());
                }
                at += 1;
                code += 1;
            }
            code <<= 1;
        }
        Ok((
            Self {
                first,
                count,
                index,
                values: s[16..16 + n].to_vec(),
            },
            16 + n,
        ))
    }
    fn diff(&self, input: &mut Input) -> Result<i32> {
        let mut code = 0u32;
        for i in 0..16 {
            code = (code << 1) | input.bits(1)?;
            if code >= self.first[i] && code - self.first[i] < self.count[i] {
                let n = self.values[self.index[i] + (code - self.first[i]) as usize];
                if n == 0 {
                    return Ok(0);
                }
                if n == 16 {
                    return Ok(-32768);
                }
                let v = input.bits(n)? as i32;
                return Ok(if v < (1 << (n - 1)) {
                    v - ((1 << n) - 1)
                } else {
                    v
                });
            }
        }
        Err(invalid())
    }
}
/// Calls sink only for complete rows. Failure invalidates ALL previously emitted
/// rows; the caller must retain its output guard until EOI and exact extent pass.
pub fn rows(
    file: &mut File,
    offset: u64,
    bytes: u64,
    width: usize,
    height: usize,
    precision: u8,
    cancel: &AtomicBool,
    mut sink: impl FnMut(usize, &[u16]) -> Result<()>,
) -> Result<()> {
    if width == 0
        || width % 2 != 0
        || height == 0
        || width > 32768
        || height > 32768
        || width
            .checked_mul(height)
            .is_none_or(|n| n > crate::MAX_PIXELS as usize)
        || !matches!(precision, 12 | 14 | 16)
    {
        return Err(unsupported());
    }
    if offset
        .checked_add(bytes)
        .is_none_or(|e| file.metadata().map(|m| e > m.len()).unwrap_or(true))
    {
        return Err(invalid());
    }
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| io_error(e, "decode"))?;
    let mut input = Input {
        r: BufReader::with_capacity(65536, file.take(bytes)),
        byte: 0,
        left: 0,
    };
    if input.marker()? != 0xd8 {
        return Err(invalid());
    }
    let mut tables: [Option<Huff>; 4] = [None, None, None, None];
    let mut ids = Vec::new();
    let mut selectors = [0usize; 2];
    let mut restart = 0usize;
    let mut saw_restart = false;
    let mut jpeg_precision = None;
    let mut header_bytes = 2usize;
    loop {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        let marker = input.marker()?;
        let len = input.u16()? as usize;
        if len < 2 {
            return Err(invalid());
        }
        header_bytes = header_bytes.checked_add(len + 2).ok_or_else(invalid)?;
        if header_bytes > 65536 {
            return Err(invalid());
        }
        let mut s = vec![0; len - 2];
        input.r.read_exact(&mut s).map_err(|_| invalid())?;
        match marker {
            0xc3 => {
                if !ids.is_empty()
                    || s.len() != 12
                    || !(s[0] == precision
                        || (precision == 16 && matches!(s[0], 12 | 14)))
                    || u16::from_be_bytes([s[1], s[2]]) as usize != height
                    || u16::from_be_bytes([s[3], s[4]]) as usize * 2 != width
                    || s[5] != 2
                {
                    return Err(unsupported());
                }
                // The TIFF container's 16-bit storage width does not set the
                // SOF3 predictor midpoint: that comes from the scan precision.
                jpeg_precision = Some(s[0]);
                for c in s[6..].chunks_exact(3) {
                    if c[1] != 0x11 || c[2] != 0 || ids.contains(&c[0]) {
                        return Err(unsupported());
                    }
                    ids.push(c[0]);
                }
            }
            0xc4 => {
                let mut p = 0;
                while p < s.len() {
                    let id = s[p] as usize;
                    p += 1;
                    if id > 3 || tables[id].is_some() {
                        return Err(unsupported());
                    }
                    let (h, n) = Huff::parse(&s[p..])?;
                    tables[id] = Some(h);
                    p += n;
                }
            }
            0xdd => {
                if saw_restart || s.len() != 2 {
                    return Err(invalid());
                }
                saw_restart = true;
                restart = u16::from_be_bytes([s[0], s[1]]) as usize;
                if restart != 0 && restart % (width / 2) != 0 {
                    return Err(unsupported());
                }
            }
            0xda => {
                if ids.len() != 2
                    || s.len() != 8
                    || s[0] != 2
                    || s[5] != 1
                    || s[6] != 0
                    || s[7] != 0
                {
                    return Err(unsupported());
                }
                for c in 0..2 {
                    if s[1 + c * 2] != ids[c] || s[2 + c * 2] & 15 != 0 {
                        return Err(unsupported());
                    }
                    let id = (s[2 + c * 2] >> 4) as usize;
                    if id > 3 || tables[id].is_none() {
                        return Err(invalid());
                    }
                    selectors[c] = id;
                }
                break;
            }
            0xe0..=0xef => {} // APP metadata is bounded by the cumulative header limit.
            _ => return Err(unsupported()),
        }
    }
    let precision = jpeg_precision.ok_or_else(invalid)?;
    let mut row = vec![0u16; width];
    let mut first = [1i32 << (precision - 1); 2];
    let mut rst = 0u8;
    for y in 0..height {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        if restart != 0 && y * (width / 2) % restart == 0 {
            if y != 0 {
                input.align()?;
                let marker = input.marker()?;
                if marker != 0xd0 + rst {
                    return Err(invalid());
                }
                rst = (rst + 1) & 7;
            }
            first = [1i32 << (precision - 1); 2];
        }
        let mut left = first;
        for x in 0..width / 2 {
            if x % 1024 == 0 && cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "decode"));
            }
            for c in 0..2 {
                let diff = tables[selectors[c]].as_ref().unwrap().diff(&mut input)?;
                let value = left[c] + diff; // DNG lossless JPEG reconstructs uint16 values, retaining over-white codes.
                let value = value as u16;
                row[x * 2 + c] = value;
                left[c] = i32::from(value);
            }
            if x == 0 {
                first = left;
            }
        }
        sink(y, &row)?;
    }
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "decode"));
    }
    input.align()?;
    if input.marker()? != 0xd9 {
        return Err(invalid());
    }
    let mut tail = [0u8; 256];
    let mut padding = 0usize;
    loop {
        let count = input.r.read(&mut tail).map_err(|e| io_error(e, "decode"))?;
        if count == 0 {
            break;
        }
        padding += count;
        // Some TIFF tile counts include a zero-filled alignment tail. It is
        // not sensor data. Keep the accepted tail bounded and reject any
        // second codestream, arbitrary bytes, or excessive zero payload.
        if padding > 4095 || tail[..count].iter().any(|v| *v != 0) {
            return Err(invalid());
        }
    }
    Ok(())
}

/// TIFF packed integer samples are most-significant-bit first regardless of
/// TIFF directory byte order. Each scanline starts at a byte boundary.
pub fn packed_sample(row: &[u8], index: usize, bits: u8) -> u16 {
    let bit = index * bits as usize;
    let mut result = 0u16;
    for k in 0..bits as usize {
        result = (result << 1) | u16::from((row[(bit + k) / 8] >> (7 - (bit + k) % 8)) & 1);
    }
    result
}

/// Unlinked, preallocated backing store keeps sensor memory bounded and is
/// reclaimed automatically on every success, error, cancellation or panic.
pub fn decode_to_spool(
    d: &super::Dng,
    input: &mut File,
    output: &std::path::Path,
    cancel: &AtomicBool,
) -> Result<File> {
    use std::{
        fs::OpenOptions,
        os::{fd::AsRawFd, unix::fs::OpenOptionsExt},
        sync::atomic::AtomicU64,
    };
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let parent = output
        .parent()
        .ok_or_else(|| err("INVALID_PATH", "spool"))?;
    let mut result = None;
    for _ in 0..16 {
        let path = parent.join(format!(
            ".dng-cfa-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        match OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&path)
        {
            Ok(f) => {
                if let Err(e) = std::fs::remove_file(&path) {
                    drop(f);
                    let _ = std::fs::remove_file(&path);
                    return Err(io_error(e, "spool"));
                }
                result = Some(f);
                break;
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(io_error(e, "spool")),
        }
    }
    let raw = result.ok_or_else(|| err("OUTPUT_EXISTS", "spool"))?;
    let bytes = (d.width * d.height * 2) as libc::off_t;
    let rc = unsafe { libc::posix_fallocate(raw.as_raw_fd(), 0, bytes) };
    if rc != 0 {
        return Err(io_error(std::io::Error::from_raw_os_error(rc), "spool"));
    }
    use std::os::unix::fs::FileExt;
    let tiled = d.rows_per_strip == 0;
    let across = if tiled {
        d.width.div_ceil(d.tile_width)
    } else {
        1
    };
    let mut encoded = vec![0u8; d.tile_width * 2];
    for (i, (&offset, &length)) in d.offsets.iter().zip(&d.counts).enumerate() {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        let x = (i % across) * d.tile_width;
        let y = (i / across) * d.tile_height;
        let h = if tiled {
            d.tile_height
        } else {
            d.tile_height.min(d.height - y)
        };
        let copy_width = d.tile_width.min(d.width - x);
        let mut sink = |local_y: usize, row: &[u16]| -> Result<()> {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "decode"));
            }
            // Decode padded edge rows too: JPEG entropy/EOI must be complete.
            // Only the encoded image rectangle is written into the sensor store.
            if y + local_y >= d.height {
                return Ok(());
            }
            for (sample, out) in row[..copy_width].iter().zip(encoded.chunks_exact_mut(2)) {
                out.copy_from_slice(&sample.to_le_bytes());
            }
            raw.write_all_at(
                &encoded[..copy_width * 2],
                (((y + local_y) * d.width + x) * 2) as u64,
            )
            .map_err(|e| io_error(e, "spool"))
        };
        if d.compression == 7 {
            rows(
                input,
                offset as u64,
                length as u64,
                d.tile_width,
                h,
                d.sample_bits,
                cancel,
                &mut sink,
            )?;
        } else {
            let row_bytes = (d.tile_width * d.sample_bits as usize).div_ceil(8);
            let mut packed = vec![0u8; row_bytes];
            let mut row = vec![0u16; d.tile_width];
            input
                .seek(SeekFrom::Start(offset as u64))
                .map_err(|e| io_error(e, "decode"))?;
            for local_y in 0..h {
                if cancel.load(Ordering::Relaxed) {
                    return Err(err("CANCELLED", "decode"));
                }
                input
                    .read_exact(&mut packed)
                    .map_err(|e| io_error(e, "decode"))?;
                for (x, sample) in row.iter_mut().enumerate() {
                    *sample = packed_sample(&packed, x, d.sample_bits);
                }
                sink(local_y, &row)?;
            }
        }
    }
    Ok(raw)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, io::Write};
    fn fixture(precision: u8, restart_rows: usize, category: u8) -> Vec<u8> {
        let mut b = vec![
            255, 216, 255, 195, 0, 14, precision, 0, 5, 0, 9, 2, 0, 17, 0, 1, 17, 0,
        ];
        b.extend([255, 196, 0, 20, 0, 1]);
        b.extend([0; 15]);
        b.push(category);
        b.extend([255, 224, 0, 4, 0, 0]);
        if restart_rows != 0 {
            b.extend([255, 221, 0, 4]);
            b.extend(((9 * restart_rows) as u16).to_be_bytes());
        }
        b.extend([255, 218, 0, 10, 2, 0, 0, 1, 0, 1, 0, 0]);
        let mut bits = Vec::new();
        let mut rst = 0;
        for y in 0..5 {
            for _ in 0..18 {
                bits.push(0u8);
                if category != 16 {
                    bits.extend(std::iter::repeat_n(1u8, category as usize));
                }
            }
            if y == 4 || (restart_rows != 0 && (y + 1) % restart_rows == 0) {
                while bits.len() % 8 != 0 {
                    bits.push(1);
                }
                for chunk in bits.chunks(8) {
                    let v = chunk.iter().fold(0u8, |v, b| (v << 1) | b);
                    b.push(v);
                    if v == 255 {
                        b.push(0);
                    }
                }
                bits.clear();
                if y != 4 {
                    b.extend([255, 208 + rst]);
                    rst = (rst + 1) & 7;
                }
            }
        }
        b.extend([255, 217]);
        b
    }
    fn run(
        b: &[u8],
        precision: u8,
        cancel: &AtomicBool,
        sink: impl FnMut(usize, &[u16]) -> Result<()>,
    ) -> Result<()> {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let p = std::env::temp_dir().join(format!(
            "cfa-jpeg-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let mut f = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&p)
            .unwrap();
        fs::remove_file(p).unwrap();
        f.write_all(b).unwrap();
        rows(&mut f, 0, b.len() as u64, 18, 5, precision, cancel, sink)
    }
    #[test]
    fn u16_container_uses_actual_scan_precision_without_bit_shifting() {
        for precision in [12u8,14,16] {
            for restart in [0usize,1,2] {
                let b=fixture(precision,restart,1);
                let mut exact=Vec::new();
                run(&b,precision,&AtomicBool::new(false),|_,row|{exact.extend_from_slice(row);Ok(())}).unwrap();
                let mut wide=Vec::new();
                run(&b,16,&AtomicBool::new(false),|_,row|{wide.extend_from_slice(row);Ok(())}).unwrap();
                assert_eq!(wide,exact);
            }
        }
        for actual in [8,10,15,17] {
            let mut b=fixture(14,0,1);b[6]=actual;
            assert!(run(&b,16,&AtomicBool::new(false),|_,_|panic!("unsupported precision emitted rows")).is_err());
        }
        assert!(run(&fixture(16,0,1),14,&AtomicBool::new(false),|_,_|panic!()).is_err());
    }
    #[test]
    fn predictors_component_mapping_precision_wrap_and_restarts() {
        for precision in [12u8, 14] {
            for restart in [0usize, 1, 2] {
                for category in [0u8, 1, 12, 16] {
                    let b = fixture(precision, restart, category);
                    let diff = if category == 16 {
                        -32768
                    } else {
                        (1i32 << category) - 1
                    };
                    let mut count = 0;
                    run(&b, precision, &AtomicBool::new(false), |y, row| {
                        let ry = if restart == 0 { y } else { y % restart };
                        for x in 0..9 {
                            let expected =
                                ((1i32 << (precision - 1)) + (ry + x + 1) as i32 * diff) as u16;
                            assert_eq!(&row[x * 2..x * 2 + 2], &[expected; 2]);
                        }
                        count += 1;
                        Ok(())
                    })
                    .unwrap();
                    assert_eq!(count, 5);
                }
            }
        }
    }
    #[test]
    fn truncation_trailing_bytes_headers_restart_and_cancel_fail_closed() {
        let b = fixture(14, 1, 1);
        for n in 0..b.len() {
            assert!(
                run(&b[..n], 14, &AtomicBool::new(false), |_, _| Ok(())).is_err(),
                "cut {n}"
            );
        }
        let mut extra = b.clone();
        extra.push(1);
        assert!(run(&extra, 14, &AtomicBool::new(false), |_, _| Ok(())).is_err());
        for (marker, delta, value) in [(195, 4, 12), (195, 9, 1), (218, 9, 2), (218, 11, 1)] {
            let mut bad = b.clone();
            let i = bad.windows(2).position(|v| v == [255, marker]).unwrap();
            bad[i + delta] = value;
            assert!(run(&bad, 14, &AtomicBool::new(false), |_, _| Ok(())).is_err());
        }
        let mut bad = b.clone();
        let i = bad.windows(2).position(|v| v == [255, 208]).unwrap();
        bad[i + 1] = 209;
        assert!(run(&bad, 14, &AtomicBool::new(false), |_, _| Ok(())).is_err());
        assert_eq!(
            run(&b, 14, &AtomicBool::new(true), |_, _| panic!())
                .unwrap_err()
                .code,
            "CANCELLED"
        );
        let cancel = AtomicBool::new(false);
        let mut count = 0;
        assert_eq!(
            run(&b, 14, &cancel, |_, _| {
                count += 1;
                cancel.store(true, Ordering::Relaxed);
                Ok(())
            })
            .unwrap_err()
            .code,
            "CANCELLED"
        );
        assert_eq!(count, 1);
        assert_eq!(
            run(&b, 14, &AtomicBool::new(false), |_, _| Err(err(
                "NO_SPACE", "spool"
            )))
            .unwrap_err()
            .code,
            "NO_SPACE"
        );
    }
    #[test]
    fn camera_zero_fill_bits_and_bounded_tiff_alignment_are_not_pixels() {
        let mut zero = fixture(14, 0, 1);
        // 90 two-bit samples leave four padding bits before EOI.
        let last = zero.len() - 3;
        zero[last] &= 0xf0;
        let mut count = 0;
        run(&zero, 14, &AtomicBool::new(false), |_, _| {
            count += 1;
            Ok(())
        })
        .unwrap();
        assert_eq!(count, 5);
        let mut mixed = zero.clone();
        mixed[last] |= 5;
        assert!(run(&mixed, 14, &AtomicBool::new(false), |_, _| Ok(())).is_err());
        let mut aligned = zero.clone();
        aligned.extend([0; 4095]);
        run(&aligned, 14, &AtomicBool::new(false), |_, _| Ok(())).unwrap();
        aligned.push(0);
        assert!(run(&aligned, 14, &AtomicBool::new(false), |_, _| Ok(())).is_err());
        // Alignment never stands in for EOI or truncated sample bits.
        zero.truncate(zero.len() - 2);
        zero.extend([0; 972]);
        assert!(run(&zero, 14, &AtomicBool::new(false), |_, _| Ok(())).is_err());
    }
    #[test]
    fn packed_msb_samples_cross_byte_boundaries() {
        for bits in [12u8, 14] {
            let values = [0, 1, 2, 31, 127, 2048, (1u16 << bits) - 1];
            let mut row = vec![0u8; (values.len() * bits as usize).div_ceil(8)];
            for (i, &value) in values.iter().enumerate() {
                for k in 0..bits as usize {
                    let p = i * bits as usize + k;
                    row[p / 8] |= (((value >> (bits as usize - 1 - k)) & 1) as u8) << (7 - p % 8);
                }
            }
            for (i, &value) in values.iter().enumerate() {
                assert_eq!(packed_sample(&row, i, bits), value);
            }
        }
    }
    #[test]
    fn complete_huffman_tree_decodes_all_ones_code_without_padding_samples() {
        let mut b = fixture(12, 0, 1);
        let dht = b.windows(2).position(|v| v == [255, 196]).unwrap();
        b[dht + 3] = 21;
        b[dht + 5] = 2;
        b.insert(dht + 21, 0);
        let sos = b.windows(2).position(|v| v == [255, 218]).unwrap();
        b.truncate(sos + 12);
        // Ninety samples: all-ones Huffman code 1 (category1), payload1.
        // Four final pad bits do not become fictitious extra samples.
        for _ in 0..23 {
            b.extend([255, 0]);
        }
        b.extend([255, 217]);
        let mut count = 0;
        run(&b, 12, &AtomicBool::new(false), |y, row| {
            for x in 0..9 {
                assert_eq!(&row[x * 2..x * 2 + 2], &[(2048 + y + x + 1) as u16; 2]);
            }
            count += 1;
            Ok(())
        })
        .unwrap();
        assert_eq!(count, 5);
        // A truly oversubscribed tree is still rejected before entropy.
        let mut bad = b.clone();
        bad[dht + 5] = 3;
        assert!(run(&bad, 12, &AtomicBool::new(false), |_, _| panic!()).is_err());
        // Missing a stuffed entropy byte must not be padded by the decoder.
        let n = b.len();
        b.drain(n - 4..n - 2);
        assert!(run(&b, 12, &AtomicBool::new(false), |_, _| Ok(())).is_err());
    }
}
