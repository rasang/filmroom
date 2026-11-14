//! Exact 8-bit quantization accelerator for the existing SDR transfer functions.
//!
//! Build the 255 output-code boundaries with the SAME f32 encoder used by the
//! reference. A small float-exponent/mantissa table selects the starting code;
//! comparisons finish the lookup. Values near a rounding boundary use the
//! reference directly, so libm's last-bit behaviour is not approximated there.
//! This is not a 12/16-bit input LUT: no input precision is discarded.
use crate::output_color::OutputColorSpace;

pub(crate) struct Sdr8Encoder {
    space: OutputColorSpace,
    boundaries: [u32; 255],
    coarse: Vec<u8>,
}
impl Sdr8Encoder {
    pub(crate) fn new(space: OutputColorSpace) -> Self {
        let reference = |bits| (space.encode(f32::from_bits(bits)) * 255.).round() as u8;
        let boundaries = std::array::from_fn(|i| {
            let wanted = (i + 1) as u8;
            let (mut lo, mut hi) = (0u32, 1f32.to_bits());
            while lo < hi {
                let mid = lo + (hi - lo) / 2;
                if reference(mid) < wanted { lo = mid + 1; } else { hi = mid; }
            }
            lo
        });
        let mut code = 0usize;
        let coarse = (0..=(1f32.to_bits() >> 16)).map(|bucket| {
            while code < 255 && boundaries[code] <= bucket << 16 { code += 1; }
            code as u8
        }).collect();
        Self { space, boundaries, coarse }
    }
    #[inline]
    pub(crate) fn encode(&self, value: f32) -> u8 {
        // Caller rejects NaN/nonfinite input. Keep the old clipping contract.
        if value <= 0. { return 0; }
        if value >= 1. { return 255; }
        if !value.is_finite() { return (self.space.encode(value) * 255.).round() as u8; }
        let bits = value.to_bits();
        let mut code = self.coarse[(bits >> 16) as usize] as usize;
        while code < 255 && bits >= self.boundaries[code] { code += 1; }
        // libm/target-specific rounding close to a code boundary remains exact.
        if (code > 0 && bits.abs_diff(self.boundaries[code-1]) <= 32)
            || (code < 255 && bits.abs_diff(self.boundaries[code]) <= 32) {
            return (self.space.encode(value) * 255.).round() as u8;
        }
        code as u8
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn reference(space: OutputColorSpace, v: f32) -> u8 { (space.encode(v)*255.).round() as u8 }
    #[test]
    fn all_rounding_boundaries_and_neighbours_match_reference() {
        for space in [OutputColorSpace::Srgb, OutputColorSpace::DisplayP3, OutputColorSpace::AdobeRgb] {
            let fast = Sdr8Encoder::new(space);
            for &boundary in &fast.boundaries {
                for bits in boundary.saturating_sub(1024)..=boundary+1024 {
                    let value=f32::from_bits(bits);
                    assert_eq!(fast.encode(value),reference(space,value),"{space:?} {bits:08x}");
                }
            }
        }
    }
    #[test]
    fn representative_float_bits_and_linear_ramp_match_reference() {
        for space in [OutputColorSpace::Srgb, OutputColorSpace::DisplayP3, OutputColorSpace::AdobeRgb] {
            let fast=Sdr8Encoder::new(space);let mut state=0x61784539u32;
            for i in 0..1_000_000u32 {
                state=state.wrapping_mul(1664525).wrapping_add(1013904223);
                for v in [f32::from_bits(state%1f32.to_bits()), i as f32/999_999.] {
                    assert_eq!(fast.encode(v),reference(space,v),"{space:?} {v}");
                }
            }
            for v in [-100.,-0.,0.,f32::MIN_POSITIVE,0.0031308,0.18,0.5,1.,100.] {
                assert_eq!(fast.encode(v),reference(space,v));
            }
        }
    }
    #[test]
    #[ignore = "host microbenchmark; not Android/device or end-to-end export performance"]
    fn benchmark_rgb24mp_quantization() {
        use std::{hint::black_box,time::Instant};
        let fast=Sdr8Encoder::new(OutputColorSpace::Srgb);
        let values:Vec<f32>=(0..65536u32).map(|i|i.wrapping_mul(1664525).wrapping_add(1013904223) as f32/u32::MAX as f32).collect();
        for optimized in [false,true] {
            let start=Instant::now();let mut sum=0u64;
            for i in 0..72_000_000usize {
                let v=black_box(values[i%values.len()]);
                sum+=if optimized {fast.encode(v)} else {reference(OutputColorSpace::Srgb,v)} as u64;
            }
            eprintln!("quantize24mp optimized={optimized} ms={} checksum={}",start.elapsed().as_millis(),black_box(sum));
        }
    }
}
