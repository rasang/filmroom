//! Bounded integer-stage DNG 1.3/1.4 point operations.
//!
//! FixVignetteRadial runs on original sensor codes before LinearizationTable and
//! black subtraction. Geometry uses full encoded-image pixel centers. The fixed
//! coordinate and gain-table quantization is matched to Adobe SDK 1.7.1's scalar
//! implementation; no SDK code or binary is linked into the product.
use super::{Cursor, invalid, supported_version, unsupported};
use crate::error::Result;
use std::sync::Arc;

#[derive(Clone, Debug)]
pub enum Stage1Opcode {
    FixVignetteRadial(Vignette),
}
#[derive(Clone, Debug)]
pub struct Vignette {
    width: usize,
    height: usize,
    origin: [i64; 2],
    step: i64,
    scale: f32,
    table: Arc<[u16]>,
}
impl Stage1Opcode {
    pub fn storage_bytes(&self) -> u64 {
        match self {
            Self::FixVignetteRadial(v) => (v.table.len() * 2 + std::mem::size_of::<Self>()) as u64,
        }
    }
}
fn polynomial(k: &[f64; 5], r2: f64) -> f64 {
    1. + k.iter().rev().fold(0., |s, &k| r2 * (k + s))
}
impl Vignette {
    fn parse(payload: &[u8], width: usize, height: usize) -> Result<Option<Self>> {
        if payload.len() != 56 || width == 0 || height == 0 || width > 32768 || height > 32768 {
            return Err(invalid());
        }
        let mut c = Cursor::new(payload);
        let mut k = [0.; 5];
        for value in &mut k {
            *value = c.f64()?;
        }
        let center = [c.f64()?, c.f64()?];
        if k.iter().any(|v| !v.is_finite())
            || center
                .iter()
                .any(|v| !v.is_finite() || !(0.0..=1.0).contains(v))
        {
            return Err(invalid());
        }
        if k.iter().all(|v| *v == 0.) {
            return Ok(None);
        }
        // A bounded supported numeric subset; no implicit repair of extreme or
        // negative gain curves. Validate every table point before retaining it.
        if k.iter().any(|v| v.abs() > 64.) {
            return Err(unsupported());
        }
        let coarse: Vec<f32> = (0..=4096)
            .map(|i| polynomial(&k, i as f64 / 4096.) as f32)
            .collect();
        if coarse.iter().any(|v| !v.is_finite() || *v < 0. || *v > 64.) {
            return Err(unsupported());
        }
        let max_endpoint = coarse[0].max(coarse[4096]);
        let mut output_bits = 15;
        while output_bits > 0 && (1u32 << output_bits) as f32 * max_endpoint > 65535. {
            output_bits -= 1;
        }
        let quantize = (1u32 << output_bits) as f32;
        let mut table = Vec::with_capacity(65537);
        for i in 0..=65536usize {
            let lower = (i / 16).min(4096);
            let upper = (lower + 1).min(4096);
            let f = (i % 16) as f32 / 16.;
            let gain = coarse[lower] * (1. - f) + coarse[upper] * f;
            let q = (gain * quantize + 0.5).floor();
            if !(0.0..=65535.0).contains(&q) {
                return Err(unsupported());
            }
            table.push(q as u16);
        }
        let cx = center[0] * width as f64;
        let cy = center[1] * height as f64;
        let radius = cx.max(width as f64 - cx).hypot(cy.max(height as f64 - cy));
        let fixed = |v: f64| (v * 4294967296.).round() as i64;
        let step = fixed(1. / radius);
        let origin = [
            fixed(-cx / radius) + (step >> 1),
            fixed(-cy / radius) + (step >> 1),
        ];
        Ok(Some(Self {
            width,
            height,
            origin,
            step,
            scale: 1. / quantize,
            table: table.into(),
        }))
    }
    fn apply(&self, sample: u16, x: usize, y: usize) -> Result<u16> {
        if x >= self.width || y >= self.height {
            return Err(crate::error::err("INVALID_INPUT", "decode"));
        }
        // 32.32 coordinates, rounded to 16.16 before the squared-radius lookup.
        let dx = (self.origin[0] + self.step * x as i64 + 32768) >> 16;
        let dy = (self.origin[1] + self.step * y as i64 + 32768) >> 16;
        let index = ((dx * dx + dy * dy + 32768) >> 16).min(65536) as usize;
        let input = sample as f32 * (1. / 65535.);
        let output = (input * (self.table[index] as f32 * self.scale)).min(1.);
        if !output.is_finite() {
            return Err(crate::error::err("INVALID_INPUT", "decode"));
        }
        Ok((output * 65535. + 0.5) as u16)
    }
}
/// Supported required List1 operations execute in list order. Unknown optional
/// records are skipped with the caller's existing warning policy.
pub fn parse_stage1(
    bytes: &[u8],
    width: usize,
    height: usize,
) -> Result<(Vec<Stage1Opcode>, bool)> {
    let records = super::records(bytes)?;
    let mut result = Vec::new();
    let mut skipped = false;
    for record in records {
        if record.flags & 1 != 0 {
            skipped = true;
            continue;
        }
        if !supported_version(record.version) {
            return Err(unsupported());
        }
        match record.id {
            1 => super::identity_warp(record.payload, 1)?,
            3 => {
                if result.len() == 4 {
                    return Err(unsupported());
                }
                if let Some(vignette) = Vignette::parse(record.payload, width, height)? {
                    result.push(Stage1Opcode::FixVignetteRadial(vignette));
                }
            }
            // Bad-pixel repair and nonidentity resampling require additional
            // neighbor/halo semantics, and MUST continue to fail closed.
            _ => return Err(unsupported()),
        }
    }
    Ok((result, skipped))
}
pub fn apply_stage1_sample(
    ops: &[Stage1Opcode],
    mut sample: u16,
    x: usize,
    y: usize,
) -> Result<u16> {
    for op in ops {
        sample = match op {
            Stage1Opcode::FixVignetteRadial(v) => v.apply(sample, x, y)?,
        };
    }
    Ok(sample)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn list(coefficients: [f64; 5], center: [f64; 2]) -> Vec<u8> {
        [1u32, 3, 0x01030000, 0, 56]
            .into_iter()
            .flat_map(u32::to_be_bytes)
            .chain(
                coefficients
                    .into_iter()
                    .chain(center)
                    .flat_map(f64::to_be_bytes),
            )
            .collect()
    }
    #[test]
    fn identity_and_required_rejection() {
        assert!(
            parse_stage1(&list([0.; 5], [0.5; 2]), 64, 128)
                .unwrap()
                .0
                .is_empty()
        );
        for offset in [4usize, 8] {
            let mut bad = list([0.; 5], [0.5; 2]);
            bad[offset..offset + 4].copy_from_slice(&99u32.to_be_bytes());
            assert!(parse_stage1(&bad, 64, 128).is_err());
        }
    }
    #[test]
    fn geometry_crop_tiling_and_order() {
        let bytes = list([0.5, 0., 0., 0., 0.], [0.3, 0.6]);
        let ops = parse_stage1(&bytes, 70, 134).unwrap().0;
        let full: Vec<u16> = (0..134)
            .flat_map(|y| (0..70).map(move |x| (x, y)))
            .map(|(x, y)| apply_stage1_sample(&ops, 32000, x, y).unwrap())
            .collect();
        for y0 in [0usize, 1, 8, 99] {
            for y in y0..(y0 + 35).min(134) {
                for x in 6..66 {
                    assert_eq!(
                        apply_stage1_sample(&ops, 32000, x, y).unwrap(),
                        full[y * 70 + x]
                    );
                    let r2 = ((x as f64 + 0.5 - 21.).powi(2) + (y as f64 + 0.5 - 80.4).powi(2))
                        / (49f64.powi(2) + 80.4f64.powi(2));
                    let ideal = 32000. * (1. + 0.5 * r2);
                    assert!((full[y * 70 + x] as f64 - ideal).abs() <= 2.);
                }
            }
        }
        assert!(apply_stage1_sample(&ops, 1, 70, 0).is_err());
        assert_eq!(apply_stage1_sample(&ops, 65535, 0, 0).unwrap(), 65535);
        let mut twice = bytes.clone();
        twice[..4].copy_from_slice(&2u32.to_be_bytes());
        twice.extend_from_slice(&bytes[4..]);
        let two = parse_stage1(&twice, 70, 134).unwrap().0;
        assert_eq!(
            apply_stage1_sample(&two, 10000, 0, 0).unwrap(),
            apply_stage1_sample(&ops, apply_stage1_sample(&ops, 10000, 0, 0).unwrap(), 0, 0)
                .unwrap()
        );
    }
    #[test]
    fn malformed_and_numeric_boundaries() {
        let bytes = list([0.2, 0., 0., 0., 0.], [0.5; 2]);
        for n in 0..bytes.len() {
            assert!(parse_stage1(&bytes[..n], 64, 128).is_err());
        }
        for coeff in [f64::NAN, f64::INFINITY, 1e9, -2.] {
            assert!(parse_stage1(&list([coeff, 0., 0., 0., 0.], [0.5; 2]), 64, 128).is_err());
        }
        for center in [f64::NAN, -0.1, 1.1] {
            assert!(parse_stage1(&list([0.; 5], [center, 0.5]), 64, 128).is_err());
        }
    }
}
