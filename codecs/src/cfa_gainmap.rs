//! Standard List2 GainMap execution on normalized single-plane CFA samples.
//! Adobe DNG SDK pixel centers reference the full stage image, not tile/phase extents.
//! Narrow plan admission: four disjoint 2x2 phases, full image, one sample plane.
use crate::error::{Result, err};
use std::sync::atomic::{AtomicBool, Ordering};
#[derive(Clone, Debug)]
pub struct CfaGainMap {
    phase: [usize; 2],
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
pub fn parse_four_phase(bytes: &[u8], width: usize, height: usize) -> Result<Vec<CfaGainMap>> {
    let mut r = Read { b: bytes, at: 0 };
    if r.u32()? != 4 {
        return Err(unsupported());
    }
    let mut maps = Vec::new();
    let mut phases = [false; 4];
    let mut total = 0usize;
    for _ in 0..4 {
        let id = r.u32()?;
        let version = r.u32()?;
        let flags = r.u32()?;
        let size = r.u32()? as usize;
        let payload = r.take(size)?;
        if id == 0 || version == 0 || flags & !3 != 0 {
            return Err(invalid());
        }
        if id != 9 || version != 0x01030000 || flags & !1 != 0 {
            return Err(unsupported());
        }
        let mut p = Read { b: payload, at: 0 };
        let mut area = [0u32; 8];
        for v in &mut area {
            *v = p.u32()?
        }
        if area[0] > 1 || area[1] > 1 || area[2..] != [height as u32, width as u32, 0, 1, 2, 2] {
            return Err(unsupported());
        }
        let phase = [area[0] as usize, area[1] as usize];
        let phase_index = phase[0] * 2 + phase[1];
        if phases[phase_index] {
            return Err(invalid());
        }
        phases[phase_index] = true;
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
        if size
            != 76usize
                .checked_add(values.checked_mul(4).ok_or_else(invalid)?)
                .ok_or_else(invalid)?
        {
            return Err(invalid());
        }
        let mut gains = Vec::with_capacity(values);
        for _ in 0..values {
            let g = f32::from_bits(p.u32()?);
            if !g.is_finite() || g <= 0. {
                return Err(invalid());
            }
            if g > 64. {
                return Err(unsupported());
            }
            gains.push(g)
        }
        if p.at != size {
            return Err(invalid());
        }
        maps.push(CfaGainMap {
            phase,
            rows,
            cols,
            spacing,
            origin,
            gains,
        });
    }
    if r.at != bytes.len() || !phases.iter().all(|p| *p) {
        return Err(invalid());
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
    if maps.is_empty() {
        return Ok(());
    }
    if maps.len() != 4
        || width == 0
        || height == 0
        || pixels.len() % width != 0
        || y.checked_add(pixels.len() / width)
            .filter(|n| *n <= height)
            .is_none()
    {
        return Err(err("INVALID_INPUT", "develop"));
    }
    for (row, line) in pixels.chunks_exact_mut(width).enumerate() {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "develop"));
        }
        let sy = y + row;
        for (x, value) in line.iter_mut().enumerate() {
            let map = maps
                .iter()
                .find(|m| m.phase == [sy % 2, x % 2])
                .ok_or_else(|| err("INVALID_INPUT", "develop"))?;
            let value_out = value.clamp(0., 1.) * map.gain(x, sy, width, height);
            if !value.is_finite() || !value_out.is_finite() {
                return Err(err("INVALID_INPUT", "develop"));
            }
            *value = value_out.min(1.)
        }
    }
    Ok(())
}
