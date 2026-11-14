//! Bounded SOF3 validation in front of pinned rawler. In particular its bit
//! pump pads exhausted input, so validate every required entropy symbol first.
use crate::error::{Result, err};
use rawler::decompressors::ljpeg::LjpegDecompressor;
use std::sync::atomic::{AtomicBool, Ordering};

pub const MAX_ENCODED: usize = 8 * 1024 * 1024;
pub const MAX_SAMPLES: usize = 4 * 1024 * 1024;
pub const OVERHEAD: u64 = 4 * 1024 * 1024;
#[track_caller]
fn invalid() -> crate::error::CodecError {
    err("INVALID_INPUT", "decode")
}
#[track_caller]
fn unsupported() -> crate::error::CodecError {
    err("UNSUPPORTED_COMPRESSION", "decode")
}
#[derive(Clone)]
struct Table {
    first: [u32; 16],
    count: [u32; 16],
    index: [usize; 16],
    values: Vec<u8>,
}
struct Bits<'a> {
    bytes: &'a [u8],
    pos: usize,
    byte: u8,
    left: u8,
}
impl Bits<'_> {
    fn bit(&mut self) -> Result<u32> {
        if self.left == 0 {
            self.byte = *self.bytes.get(self.pos).ok_or_else(|| invalid())?;
            self.pos += 1;
            if self.byte == 255 {
                if self.bytes.get(self.pos) != Some(&0) {
                    return Err(invalid());
                }
                self.pos += 1;
            }
            self.left = 8;
        }
        self.left -= 1;
        Ok(((self.byte >> self.left) & 1) as u32)
    }
    fn finish(&self) -> Result<()> {
        // JPEG scan padding is at most seven one bits, then EOI. No restart
        // or extra scan is supported, and none may be silently skipped.
        if self.left > 0 && self.byte & ((1u8 << self.left) - 1) != (1u8 << self.left) - 1 {
            return Err(invalid());
        }
        if self.bytes.get(self.pos..) != Some(&[255, 217][..]) {
            return Err(invalid());
        }
        Ok(())
    }
}
fn validate(
    src: &[u8],
    width: usize,
    height: usize,
    mut checkpoint: impl FnMut(usize) -> Result<()>,
) -> Result<()> {
    validate_limits(
        src,
        width,
        height,
        &mut checkpoint,
        MAX_ENCODED,
        MAX_SAMPLES,
        false,
    )
}
fn validate_limits(
    src: &[u8],
    width: usize,
    height: usize,
    checkpoint: &mut impl FnMut(usize) -> Result<()>,
    max_encoded: usize,
    max_samples: usize,
    camera: bool,
) -> Result<()> {
    checkpoint(0)?;
    if src.len() > max_encoded
        || width == 0
        || height == 0
        || width
            .checked_mul(height)
            .filter(|n| *n <= max_samples)
            .is_none()
    {
        return Err(err("MEMORY_BUDGET", "decode"));
    }
    if src.get(..2) != Some(&[255, 216][..]) {
        return Err(invalid());
    }
    let mut p = 2usize;
    let mut components: Vec<u8> = Vec::new();
    let mut tables: [Option<Table>; 4] = [None, None, None, None];
    let mut frame = false;
    let mut sampling = (1usize, 1usize);
    loop {
        if src.get(p) != Some(&255) {
            return Err(invalid());
        }
        let marker = *src.get(p + 1).ok_or_else(|| invalid())?;
        let len_bytes = src.get(p + 2..p + 4).ok_or_else(|| invalid())?;
        let len = u16::from_be_bytes([len_bytes[0], len_bytes[1]]) as usize;
        if len < 2 {
            return Err(invalid());
        }
        let end = p.checked_add(2 + len).ok_or_else(|| invalid())?;
        let s = src.get(p + 4..end).ok_or_else(|| invalid())?;
        p = end;
        match marker {
            0xc3 => {
                if frame || s.len() < 6 {
                    return Err(invalid());
                }
                let n = s[5] as usize;
                if !(1..=4).contains(&n) || s.len() != 6 + 3 * n {
                    return Err(invalid());
                }
                let w = u16::from_be_bytes([s[3], s[4]]) as usize;
                let h = u16::from_be_bytes([s[1], s[2]]) as usize;
                if (if camera {
                    !matches!(s[0], 12 | 14 | 15 | 16)
                } else {
                    s[0] != 16
                }) || w * n != width
                    || h != height
                {
                    return Err(unsupported());
                }
                let subsampled = camera && n == 3 && matches!(s[7], 0x21 | 0x22);
                if w.checked_mul(h).and_then(|p|p.checked_mul(if subsampled {1} else {n})).is_none_or(|p|p > crate::MAX_PIXELS as usize) {
                    return Err(err("PIXEL_LIMIT", "decode"));
                }
                for (i, c) in s[6..].chunks_exact(3).enumerate() {
                    if c[2] != 0
                        || components.contains(&c[0])
                        || !(c[1] == 0x11
                            || (camera && n == 3 && i == 0 && matches!(c[1], 0x21 | 0x22)))
                    {
                        return Err(unsupported());
                    }
                    if i == 0 {
                        sampling = ((c[1] >> 4) as usize, (c[1] & 15) as usize);
                    }
                    components.push(c[0]);
                }
                if w % sampling.0 != 0
                    || h % sampling.1 != 0
                    || (camera && matches!(s[0], 15 | 16) && sampling == (1, 1))
                {
                    return Err(unsupported());
                }
                frame = true;
            }
            0xc4 => {
                let mut q = 0;
                while q < s.len() {
                    let id = *s.get(q).ok_or_else(|| invalid())? as usize;
                    if id > 3 || tables[id].is_some() {
                        return Err(unsupported());
                    }
                    let counts = s.get(q + 1..q + 17).ok_or_else(|| invalid())?;
                    let count: usize = counts.iter().map(|x| *x as usize).sum();
                    if count == 0 || count > 256 {
                        return Err(invalid());
                    }
                    let vals = s.get(q + 17..q + 17 + count).ok_or_else(|| invalid())?;
                    let mut code = 0u32;
                    let mut at = 0;
                    let mut first = [0; 16];
                    let mut lengths = [0; 16];
                    let mut index = [0; 16];
                    for (i, n) in counts.iter().enumerate() {
                        let bits = (i + 1) as u8;
                        first[i] = code;
                        lengths[i] = *n as u32;
                        index[i] = at;
                        for _ in 0..*n {
                            // All-one code is reserved for JPEG pad bits.
                            if code >= (1u32 << bits) - 1 || vals[at] > 16 {
                                return Err(invalid());
                            }
                            code += 1;
                            at += 1;
                        }
                        code <<= 1;
                    }
                    tables[id] = Some(Table {
                        first,
                        count: lengths,
                        index,
                        values: vals.to_vec(),
                    });
                    q += 17 + count;
                }
            }
            0xda => {
                if !frame
                    || s.first().copied() != Some(components.len() as u8)
                    || s.len() != 1 + 2 * components.len() + 3
                {
                    return Err(invalid());
                }
                let n = components.len();
                let mut selected = Vec::new();
                for i in 0..n {
                    if s[1 + i * 2] != components[i] || s[2 + i * 2] & 15 != 0 {
                        return Err(unsupported());
                    }
                    let id = (s[2 + i * 2] >> 4) as usize;
                    selected.push(
                        tables
                            .get(id)
                            .and_then(|t| t.as_ref())
                            .ok_or_else(|| invalid())?,
                    );
                }
                if !(1..=7).contains(&s[1 + 2 * n])
                    || (sampling != (1, 1) && s[1 + 2 * n] != 1)
                    || s[2 + 2 * n] != 0
                    || s[3 + 2 * n] != 0
                {
                    return Err(unsupported());
                }
                let mut bits = Bits {
                    bytes: &src[p..],
                    pos: 0,
                    byte: 0,
                    left: 0,
                };
                // Each interleaved MCU emits every Y sample before Cb/Cr.
                // Expanded RGB storage is not the entropy symbol count.
                let y_samples = sampling.0 * sampling.1;
                let order: Vec<usize> = if y_samples == 1 {
                    (0..n).collect()
                } else {
                    std::iter::repeat_n(0, y_samples).chain([1, 2]).collect()
                };
                let symbols = width * height / (n * y_samples) * order.len();
                for i in 0..symbols {
                    if i % 4096 == 0 {
                        checkpoint(i)?;
                    }
                    let table = selected[order[i % order.len()]];
                    let mut code = 0;
                    let mut category = None;
                    for length in 0..16 {
                        code = (code << 1) | bits.bit()?;
                        if code >= table.first[length] {
                            let offset = code - table.first[length];
                            if offset < table.count[length] {
                                category =
                                    Some(table.values[table.index[length] + offset as usize]);
                                break;
                            }
                        }
                    }
                    let category = category.ok_or_else(|| invalid())?;
                    // DNG's lossless JPEG category16 represents -32768, no payload.
                    if category != 16 {
                        for _ in 0..category {
                            bits.bit()?;
                        }
                    }
                }
                return bits.finish();
            }
            _ => return Err(unsupported()),
        }
    }
}
/// Sony's tiled ARW lossless blocks use 16-byte container alignment. Only
/// 0..15 FF fill bytes after EOI are permitted; the returned JPEG still goes
/// through full entropy validation, including exact sample count and scan end.
pub(crate) fn sony_aligned_jpeg(src: &[u8]) -> Result<&[u8]> {
    let fill = src.iter().rev().take_while(|&&b| b == 255).take(16).count();
    if fill > 15 || (fill != 0 && src.len() % 16 != 0) {
        return Err(invalid());
    }
    let jpeg = &src[..src.len() - fill];
    if !jpeg.ends_with(&[255, 217]) {
        return Err(invalid());
    }
    Ok(jpeg)
}

/// Strict single-scan camera lossless JPEG validation, including explicit YUV sampling.
/// Scans every symbol before invoking rawler's padded entropy reader.
pub(crate) fn validate_camera(
    src: &[u8],
    width: usize,
    height: usize,
    cancel: &AtomicBool,
) -> Result<()> {
    validate_limits(
        src,
        width,
        height,
        &mut |_| {
            if cancel.load(Ordering::Relaxed) {
                Err(err("CANCELLED", "decode"))
            } else {
                Ok(())
            }
        },
        128 * 1024 * 1024,
        crate::MAX_PIXELS as usize * 3,
        true,
    )
}
pub fn decode(src: &[u8], width: usize, height: usize, cancel: &AtomicBool) -> Result<Vec<u16>> {
    let check = || {
        if cancel.load(Ordering::Relaxed) {
            Err(err("CANCELLED", "decode"))
        } else {
            Ok(())
        }
    };
    validate(src, width, height, |_| check())?;
    check()?;
    let mut output = Vec::new();
    output
        .try_reserve_exact(width * height)
        .map_err(|_| err("MEMORY_BUDGET", "decode"))?;
    output.resize(width * height, 0);
    // Pinned third-party parser uses indexing; corrupt input must stay a fixed
    // safe error rather than unwind across JNI. All size allocation is bounded.
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let decoder = LjpegDecompressor::new(src).map_err(|_| invalid())?;
        if decoder.width() != width
            || decoder.height() != height
            || decoder.super_h() != 1
            || decoder.super_v() != 1
        {
            return Err(unsupported());
        }
        decoder
            .decode(&mut output, 0, width, width, height, false)
            .map_err(|_| invalid())
    }))
    .map_err(|_| invalid())??;
    check()?;
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn yuv_zero_jpeg(sampling: u8, precision: u8, w: u16, h: u16) -> Vec<u8> {
        let mut v = vec![255, 216, 255, 195, 0, 17, precision];
        v.extend(h.to_be_bytes());
        v.extend(w.to_be_bytes());
        v.extend([3, 1, sampling, 0, 2, 17, 0, 3, 17, 0]);
        v.extend([255, 196, 0, 20, 0, 1]);
        v.extend([0; 15]);
        v.push(0);
        v.extend([255, 218, 0, 12, 3, 1, 0, 2, 0, 3, 0, 1, 0, 0]);
        let n = (w as usize) * (h as usize) / ((sampling >> 4) as usize * (sampling & 15) as usize);
        let bits = n * (((sampling >> 4) as usize * (sampling & 15) as usize) + 2);
        v.extend(std::iter::repeat_n(0, bits / 8));
        if bits % 8 != 0 {
            v.push((1 << (8 - bits % 8)) - 1);
        }
        v.extend([255, 217]);
        v
    }
    #[test]
    fn camera_yuv_mcu_entropy_exact_and_truncation_rejected() {
        let cancel = AtomicBool::new(false);
        for (sampling, precision) in [(0x21, 15), (0x22, 15), (0x21, 16), (0x22, 16)] {
            let mut v = yuv_zero_jpeg(sampling, precision, 8, 8);
            assert!(validate_camera(&v, 24, 8, &cancel).is_ok());
            v.remove(v.len() - 3); // drop a real entropy byte, not just EOI
            assert!(validate_camera(&v, 24, 8, &cancel).is_err());
        }
        let mut bad_predictor = yuv_zero_jpeg(0x21, 16, 8, 8);
        let sos = bad_predictor
            .windows(2)
            .position(|v| v == [255, 218])
            .unwrap();
        bad_predictor[sos + 11] = 2;
        assert!(validate_camera(&bad_predictor, 24, 8, &cancel).is_err());
        let v = yuv_zero_jpeg(0x21, 16, 8, 8);
        assert!(validate(&v, 24, 8, |_| Ok(())).is_err()); // DNG path is unchanged
    }
    #[test]
    fn cancels_during_entropy_not_just_before_block() {
        let mut source = vec![
            255, 216, 255, 195, 0, 11, 16, 0, 64, 0, 128, 1, 0, 17, 0, 255, 196, 0, 20, 0, 1,
        ];
        source.extend([0; 15]);
        source.push(0);
        source.extend([255, 218, 0, 8, 1, 0, 0, 1, 0, 0]);
        source.extend([0; 1024]);
        source.extend([255, 217]);
        let result = validate(&source, 128, 64, |index| {
            if index >= 4096 {
                Err(err("CANCELLED", "decode"))
            } else {
                Ok(())
            }
        });
        assert_eq!(result.unwrap_err().code, "CANCELLED");
        assert!(validate(&source, 128, 64, |_| Ok(())).is_ok());
    }
}

#[cfg(test)]
mod category16_tests {
    use super::*;
    #[test]
    fn dng_category16_has_no_additional_bits() {
        let mut source = vec![
            255, 216, 255, 195, 0, 11, 16, 0, 8, 0, 8, 1, 0, 17, 0, 255, 196, 0, 20, 0, 1,
        ];
        source.extend([0; 15]);
        source.push(16);
        source.extend([255, 218, 0, 8, 1, 0, 0, 1, 0, 0]);
        source.extend([0; 8]);
        source.extend([255, 217]);
        let decoded = decode(&source, 8, 8, &AtomicBool::new(false)).unwrap();
        for y in 0..8 {
            for x in 0..8 {
                assert_eq!(decoded[y * 8 + x], (((x + y) % 2) * 32768) as u16);
            }
        }
    }
}

#[cfg(test)]
mod sony_alignment_tests {
    use super::*;
    #[test]
    fn sony_fill_is_bounded_and_does_not_relax_other_formats() {
        let mut jpeg = vec![
            255, 216, 255, 195, 0, 11, 14, 0, 8, 0, 8, 1, 0, 17, 0, 255, 196, 0, 20, 0, 1,
        ];
        jpeg.extend([0; 15]);
        jpeg.push(0);
        jpeg.extend([255, 218, 0, 8, 1, 0, 0, 1, 0, 0]);
        jpeg.extend([0; 8]);
        jpeg.extend([255, 217]);
        let cancel = AtomicBool::new(false);
        assert!(validate_camera(&jpeg, 8, 8, &cancel).is_ok());
        let mut aligned = jpeg.clone();
        aligned.resize((jpeg.len() + 15) / 16 * 16, 255);
        assert!(aligned.len() > jpeg.len());
        assert!(validate_camera(&aligned, 8, 8, &cancel).is_err());
        assert_eq!(sony_aligned_jpeg(&aligned).unwrap(), jpeg);
        assert!(validate_camera(sony_aligned_jpeg(&aligned).unwrap(), 8, 8, &cancel).is_ok());
        assert_eq!(sony_aligned_jpeg(&jpeg).unwrap(), jpeg);
        assert!(sony_aligned_jpeg(&aligned[..aligned.len() - 1]).is_err());
        let mut non_ff = aligned.clone();
        *non_ff.last_mut().unwrap() = 0;
        assert!(sony_aligned_jpeg(&non_ff).is_err());
        let mut excessive = aligned.clone();
        excessive.extend([255; 16]);
        assert!(sony_aligned_jpeg(&excessive).is_err());
        let mut missing = aligned.clone();
        missing[jpeg.len() - 1] = 255;
        assert!(sony_aligned_jpeg(&missing).is_err());
        let mut entropy = aligned.clone();
        entropy[47] = 255;
        assert!(validate_camera(sony_aligned_jpeg(&entropy).unwrap(), 8, 8, &cancel).is_err());
    }
}
