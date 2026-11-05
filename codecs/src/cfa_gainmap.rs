//! Standard List2 GainMap execution on normalized single-plane CFA samples.
//! Adobe DNG SDK pixel centers reference the full stage image, not tile/phase extents.
//! Generic bounded area/pitch maps on one sample plane, in declared list order.
use crate::error::{Result, err};
use std::sync::atomic::{AtomicBool, Ordering};
#[derive(Clone, Debug)]
pub struct CfaGainMap {
    area: [usize; 4],
    pitch: [usize; 2],
    rows: usize,
    cols: usize,
    spacing: [f64; 2],
    origin: [f64; 2],
    gains: Vec<f32>,
}
fn invalid() -> crate::error::CodecError {
    err("INVALID_INPUT", "probe")
}
fn unsupported() -> crate::error::CodecError {
    err("UNSUPPORTED_DNG_OPCODE", "probe")
}
struct Read<'a> {
    b: &'a [u8],
    at: usize,
}
impl<'a> Read<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self
            .at
            .checked_add(n)
            .filter(|v| *v <= self.b.len())
            .ok_or_else(invalid)?;
        let s = &self.b[self.at..end];
        self.at = end;
        Ok(s)
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn f64(&mut self) -> Result<f64> {
        Ok(f64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
}
/// Generic single-plane List2 GainMaps. Area/pitch selects sensor positions in
/// the active-area stage image; it is not a camera-brand capability gate.
pub fn parse(bytes: &[u8], width: usize, height: usize) -> Result<(Vec<CfaGainMap>, bool)> {
    let records = crate::opcodes::records(bytes)?;
    let mut maps = Vec::new();
    let mut skipped = false;
    let mut total = 0usize;
    for record in records {
        if record.id != 9 || !crate::opcodes::supported_version(record.version) {
            if record.flags & 1 != 0 {
                skipped = true;
                continue;
            }
            return Err(unsupported());
        }
        if maps.len() >= 4 {
            return Err(unsupported());
        }
        let payload = record.payload;
        let mut p = Read { b: payload, at: 0 };
        let mut area = [0u32; 8];
        for value in &mut area {
            *value = p.u32()?;
        }
        if area[0] >= area[2] || area[1] >= area[3] || area[6] == 0 || area[7] == 0 {
            return Err(invalid());
        }
        if area[2] > height as u32
            || area[3] > width as u32
            || area[4] != 0
            || area[5] != 1
            || area[6] > 32768
            || area[7] > 32768
        {
            return Err(unsupported());
        }
        let rows = p.u32()? as usize;
        let cols = p.u32()? as usize;
        let spacing = [p.f64()?, p.f64()?];
        let origin = [p.f64()?, p.f64()?];
        let planes = p.u32()?;
        if rows == 0 || cols == 0 || planes != 1 {
            return Err(unsupported());
        }
        if spacing.iter().any(|x| !x.is_finite() || *x <= 0.)
            || origin.iter().any(|x| !x.is_finite())
        {
            return Err(invalid());
        }
        if spacing.iter().any(|x| *x < 1e-12 || *x > 1e12) || origin.iter().any(|x| x.abs() > 1e12)
        {
            return Err(unsupported());
        }
        let values = rows.checked_mul(cols).ok_or_else(invalid)?;
        total = total.checked_add(values).ok_or_else(invalid)?;
        if total > 262144 {
            return Err(unsupported());
        }
        if payload.len()
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
        maps.push(CfaGainMap {
            area: [
                area[0] as usize,
                area[1] as usize,
                area[2] as usize,
                area[3] as usize,
            ],
            pitch: [area[6] as usize, area[7] as usize],
            rows,
            cols,
            spacing,
            origin,
            gains,
        });
    }
    Ok((maps, skipped))
}
/// Compatibility entry point for callers explicitly testing the original
/// four-disjoint-phase plan. Main DNG admission uses generic parse().
pub fn parse_four_phase(bytes: &[u8], width: usize, height: usize) -> Result<Vec<CfaGainMap>> {
    let (maps, skipped) = parse(bytes, width, height)?;
    if maps.len() != 4 || skipped {
        return Err(unsupported());
    }
    let mut phases = [false; 4];
    for map in &maps {
        if map.area[0] > 1
            || map.area[1] > 1
            || map.area[2..] != [height, width]
            || map.pitch != [2, 2]
        {
            return Err(unsupported());
        }
        let phase = map.area[0] * 2 + map.area[1];
        if phases[phase] {
            return Err(invalid());
        }
        phases[phase] = true;
    }
    Ok(maps)
}
impl CfaGainMap {
    pub fn storage_bytes(&self) -> u64 {
        (self.gains.len() * 4 + std::mem::size_of::<Self>()) as u64
    }
    fn gain(&self, x: usize, y: usize, width: usize, height: usize) -> f32 {
        let u = (((x as f64 + 0.5) / width as f64 - self.origin[1]) / self.spacing[1])
            .clamp(0., (self.cols - 1) as f64);
        let v = (((y as f64 + 0.5) / height as f64 - self.origin[0]) / self.spacing[0])
            .clamp(0., (self.rows - 1) as f64);
        let x0 = u.floor() as usize;
        let y0 = v.floor() as usize;
        let x1 = (x0 + 1).min(self.cols - 1);
        let y1 = (y0 + 1).min(self.rows - 1);
        let fy = (v - y0 as f64) as f32;
        let a = self.gains[y0 * self.cols + x0] * (1. - fy) + self.gains[y1 * self.cols + x0] * fy;
        let b = self.gains[y0 * self.cols + x1] * (1. - fy) + self.gains[y1 * self.cols + x1] * fy;
        (a as f64 + (b as f64 - a as f64) * (u - x0 as f64)) as f32
    }
}
pub fn apply(
    maps: &[CfaGainMap],
    pixels: &mut [f32],
    width: usize,
    height: usize,
    y: usize,
    cancel: &AtomicBool,
) -> Result<()> {
    apply_region(maps, pixels, width, height, 0, y, width, cancel)
}
/// Coordinates are local to the stage-2 active image, before DefaultCrop and
/// orientation. Every overlapping map runs in list order, including unit maps:
/// a unit map still clips above-white values, and must not be dropped as a NOP.
pub fn apply_region(
    maps: &[CfaGainMap],
    pixels: &mut [f32],
    width: usize,
    height: usize,
    x: usize,
    y: usize,
    region_width: usize,
    cancel: &AtomicBool,
) -> Result<()> {
    if maps.is_empty() {
        return Ok(());
    }
    if width == 0
        || height == 0
        || region_width == 0
        || pixels.len() % region_width != 0
        || x.checked_add(region_width).is_none_or(|n| n > width)
        || y.checked_add(pixels.len() / region_width)
            .is_none_or(|n| n > height)
    {
        return Err(err("INVALID_INPUT", "develop"));
    }
    for map in maps {
        for (row, line) in pixels.chunks_exact_mut(region_width).enumerate() {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "develop"));
            }
            let sy = y + row;
            if sy < map.area[0] || sy >= map.area[2] || (sy - map.area[0]) % map.pitch[0] != 0 {
                continue;
            }
            for (column, value) in line.iter_mut().enumerate() {
                let sx = x + column;
                if sx < map.area[1] || sx >= map.area[3] || (sx - map.area[1]) % map.pitch[1] != 0 {
                    continue;
                }
                let value_out = value.clamp(0., 1.) * map.gain(sx, sy, width, height);
                if !value.is_finite() || !value_out.is_finite() {
                    return Err(err("INVALID_INPUT", "develop"));
                }
                *value = value_out.min(1.);
            }
        }
    }
    Ok(())
}
