//! Narrow DNG 1.3 List3 support, in camera RGB after demosaic, before calibration.
//! Coordinate convention verified against Adobe SDK dng_gain_map.cpp (AOSP
//! blob 87d7e6f26ba3996eb1265339fe8d10406ac2d69f): pixel centers / image extent,
//! edge replication, bilinear gains, and upper clipping to 1 after each map.
//! This independent implementation does not support nonidentity geometric warps.
use crate::error::{Result, err};
use std::sync::atomic::{AtomicBool, Ordering};
#[derive(Clone, Debug)]
pub struct GainMap {
    area: [usize; 4],
    rows: usize,
    cols: usize,
    spacing: [f64; 2],
    origin: [f64; 2],
    gains: Vec<f32>,
}
const MAX_VALUES: usize = 262144; // cumulative 1 MiB retained gain samples
fn invalid() -> crate::error::CodecError {
    err("INVALID_INPUT", "probe")
}
fn unsupported() -> crate::error::CodecError {
    err("UNSUPPORTED_DNG_OPCODE", "probe")
}
struct Cursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}
impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|&x| x <= self.bytes.len())
            .ok_or_else(invalid)?;
        let s = &self.bytes[self.pos..end];
        self.pos = end;
        Ok(s)
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn f64(&mut self) -> Result<f64> {
        Ok(f64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
}
/// Returns ordered supported maps and whether an explicitly optional record was skipped.
/// Admit bounded rectangular, all-three-plane, unit-pitch List3 maps. No silent
/// approximation of CFA/List1/List2, cropped stage images, or geometric transforms.
pub fn parse(bytes: &[u8], width: usize, height: usize) -> Result<(Vec<GainMap>, bool)> {
    let mut c = Cursor { bytes, pos: 0 };
    let count = c.u32()?;
    if count > 64 {
        return Err(unsupported());
    }
    let mut maps = Vec::new();
    let mut skipped = false;
    let mut total = 0usize;
    for _ in 0..count {
        let id = c.u32()?;
        let version = c.u32()?;
        let flags = c.u32()?;
        let size = c.u32()? as usize;
        if id == 0 || version == 0 || flags & !3 != 0 {
            return Err(invalid());
        }
        let payload = c.take(size)?;
        // Preserve the established optional-opcode policy; never ignore required records.
        if flags & 1 != 0 {
            skipped = true;
            continue;
        }
        if !crate::opcodes::supported_version(version) {
            return Err(unsupported());
        }
        let mut p = Cursor {
            bytes: payload,
            pos: 0,
        };
        match id {
            9 => {
                if maps.len() >= 4 {
                    return Err(unsupported());
                }
                let mut area = [0; 8];
                for x in &mut area {
                    *x = p.u32()?;
                }
                if area[0] >= area[2] || area[1] >= area[3] {
                    return Err(invalid());
                }
                if area[2] > height as u32 || area[3] > width as u32 || area[4..] != [0, 3, 1, 1] {
                    return Err(unsupported());
                }
                let rows = p.u32()? as usize;
                let cols = p.u32()? as usize;
                let spacing = [p.f64()?, p.f64()?];
                let origin = [p.f64()?, p.f64()?];
                let planes = p.u32()?;
                if rows == 0 || cols == 0 || planes != 3 {
                    return Err(unsupported());
                }
                if spacing.iter().any(|v| !v.is_finite() || *v <= 0.)
                    || origin.iter().any(|v| !v.is_finite())
                {
                    return Err(invalid());
                }
                // Keep coordinate arithmetic finite even for hostile subnormal spacings.
                if spacing.iter().any(|v| *v < 1e-12 || *v > 1e12)
                    || origin.iter().any(|v| v.abs() > 1e12)
                {
                    return Err(unsupported());
                }
                let values = rows
                    .checked_mul(cols)
                    .and_then(|v| v.checked_mul(3))
                    .ok_or_else(invalid)?;
                total = total.checked_add(values).ok_or_else(invalid)?;
                if total > MAX_VALUES {
                    return Err(unsupported());
                }
                if size
                    != 76usize
                        .checked_add(values.checked_mul(4).ok_or_else(invalid)?)
                        .ok_or_else(invalid)?
                {
                    return Err(invalid());
                }
                let mut gains = Vec::with_capacity(values);
                for _ in 0..values {
                    let gain = f32::from_bits(p.u32()?);
                    if !gain.is_finite() || gain <= 0. {
                        return Err(invalid());
                    }
                    if gain > 64. {
                        return Err(unsupported());
                    }
                    gains.push(gain);
                }
                maps.push(GainMap {
                    area: [
                        area[0] as usize,
                        area[1] as usize,
                        area[2] as usize,
                        area[3] as usize,
                    ],
                    rows,
                    cols,
                    spacing,
                    origin,
                    gains,
                });
            }
            1 => {
                crate::opcodes::identity_warp(payload, 3)?;
                p.pos = size;
            }
            _ => return Err(unsupported()),
        }
        if p.pos != size {
            return Err(invalid());
        }
    }
    if c.pos != bytes.len() {
        return Err(invalid());
    }
    Ok((maps, skipped))
}
impl GainMap {
    pub fn storage_bytes(&self) -> u64 {
        (self.gains.len() * 4 + std::mem::size_of::<Self>()) as u64
    }
    fn sample(&self, x: usize, y: usize, channel: usize, width: usize, height: usize) -> f32 {
        let u = (((x as f64 + 0.5) / width as f64 - self.origin[1]) / self.spacing[1])
            .clamp(0., (self.cols - 1) as f64);
        let v = (((y as f64 + 0.5) / height as f64 - self.origin[0]) / self.spacing[0])
            .clamp(0., (self.rows - 1) as f64);
        let x0 = u.floor() as usize;
        let y0 = v.floor() as usize;
        let x1 = (x0 + 1).min(self.cols - 1);
        let y1 = (y0 + 1).min(self.rows - 1);
        let fy = (v - y0 as f64) as f32;
        let at = |r, c| self.gains[(r * self.cols + c) * 3 + channel];
        let a = at(y0, x0) * (1. - fy) + at(y1, x0) * fy;
        let b = at(y0, x1) * (1. - fy) + at(y1, x1) * fy;
        (a as f64 + (b as f64 - a as f64) * (u - x0 as f64)) as f32
    }
}
pub fn apply(
    maps: &[GainMap],
    pixels: &mut [[f32; 3]],
    width: usize,
    height: usize,
    y: usize,
    cancel: &AtomicBool,
) -> Result<()> {
    if width == 0
        || height == 0
        || pixels.len() % width != 0
        || y.checked_add(pixels.len() / width)
            .filter(|v| *v <= height)
            .is_none()
    {
        return Err(err("INVALID_INPUT", "develop"));
    }
    for map in maps {
        for (row, line) in pixels.chunks_exact_mut(width).enumerate() {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "develop"));
            }
            let sy = y + row;
            if sy < map.area[0] || sy >= map.area[2] {
                continue;
            }
            for (x, pixel) in line.iter_mut().enumerate() {
                if x < map.area[1] || x >= map.area[3] {
                    continue;
                }
                for (ch, value) in pixel.iter_mut().enumerate() {
                    if !value.is_finite() {
                        return Err(err("INVALID_INPUT", "develop"));
                    }
                    let result = *value * map.sample(x, y + row, ch, width, height);
                    if !result.is_finite() {
                        return Err(err("INVALID_INPUT", "develop"));
                    }
                    *value = result.min(1.);
                }
            }
        }
    }
    Ok(())
}
