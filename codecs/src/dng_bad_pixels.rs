//! Required FixBadPixelsConstant on original integer CFA codes.
//! Original implementation of the DNG operation, checked against an external
//! SDK oracle. Source pixels are immutable: repairs never feed other repairs.
use crate::error::{err, Result};

#[derive(Clone, Debug)]
pub struct BadPixelsConstant {
    pub constant: u16,
    pub phase: u8,
}
impl BadPixelsConstant {
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != 8 { return Err(err("INVALID_INPUT", "probe")); }
        let constant = u32::from_be_bytes(bytes[..4].try_into().unwrap());
        let phase = u32::from_be_bytes(bytes[4..].try_into().unwrap());
        if constant > u16::MAX as u32 || phase > 3 {
            return Err(err("UNSUPPORTED_DNG_OPCODE", "probe"));
        }
        Ok(Self { constant: constant as u16, phase: phase as u8 })
    }

    /// `source` supplies immutable pre-operation sensor samples. The repeat-2
    /// edge extension preserves CFA parity, including in the image corners.
    pub fn sample(
        &self, width: usize, height: usize, x: usize, y: usize,
        mut source: impl FnMut(usize, usize) -> Result<u16>,
    ) -> Result<u16> {
        if width < 2 || height < 2 || width > 32768 || height > 32768 || x >= width || y >= height {
            return Err(err("INVALID_INPUT", "decode"));
        }
        let value = source(x, y)?;
        if value != self.constant { return Ok(value); }
        let green = (x + y + self.phase as usize + (self.phase as usize >> 1)) & 1 == 0;
        let offsets = if green { [(-1,-1),(1,-1),(-1,1),(1,1)] }
            else { [(0,-2),(0,2),(-2,0),(2,0)] };
        let mut sum = 0u32;
        let mut count = 0u32;
        for (dx,dy) in offsets {
            let v = source(repeat2(x as isize + dx, width), repeat2(y as isize + dy, height))?;
            if v != self.constant { sum += u32::from(v); count += 1; }
        }
        Ok(if count == 0 { value } else { ((sum + count / 2) / count) as u16 })
    }
}
fn repeat2(p: isize, extent: usize) -> usize {
    if p < 0 { p.rem_euclid(2) as usize }
    else if p >= extent as isize { extent - 2 + ((p - extent as isize).rem_euclid(2) as usize) }
    else { p as usize }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn repairs_use_original_same_color_neighbors() {
        let op=BadPixelsConstant{constant:0,phase:0};
        let mut a=vec![100u16;64]; a[3*8+3]=0;
        for (x,y,v) in [(2,2,20),(4,2,40),(2,4,60),(4,4,80)] { a[y*8+x]=v; }
        assert_eq!(op.sample(8,8,3,3,|x,y|Ok(a[y*8+x])).unwrap(),50);
        a[2*8+2]=0;
        assert_eq!(op.sample(8,8,3,3,|x,y|Ok(a[y*8+x])).unwrap(),60);
    }
    #[test] fn all_bad_unchanged_and_nonbad_untouched() {
        let op=BadPixelsConstant{constant:0,phase:2};
        assert_eq!(op.sample(8,8,0,0,|_,_|Ok(0)).unwrap(),0);
        let mut calls=0;
        assert_eq!(op.sample(8,8,3,3,|_,_|{calls+=1;Ok(123)}).unwrap(),123);
        assert_eq!(calls,1);
    }
    #[test] fn edge_extension_retains_mosaic_parity() {
        for n in [8,9] { for p in -2..n as isize+2 {
            let q=repeat2(p,n); assert!(q<n); assert_eq!(q%2,p.rem_euclid(2) as usize);
        }}
    }
    #[test] fn bad_parameters_fail_closed() {
        for (c,p) in [(65536,0),(0,4)] {
            let b=[(c as u32).to_be_bytes(),(p as u32).to_be_bytes()].concat();
            assert!(BadPixelsConstant::parse(&b).is_err());
        }
        assert!(BadPixelsConstant::parse(&[0;7]).is_err());
    }
}
