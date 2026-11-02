// SPDX-License-Identifier: LGPL-2.1
//! Canon subsampled predictor-1 row decoder. The caller must validate the entire
//! entropy stream first: BitPumpJPEG intentionally pads exhausted input, as in
//! the original whole-image decoder. No restart-marker support is implied.
use super::LjpegDecompressor;
use crate::pumps::BitPumpJPEG;

impl LjpegDecompressor<'_> {
  /// Emit expanded YCbCr in original JPEG raster order, one MCU row at a time.
  /// The callback receives the first raster row and either one (4:2:2) or two
  /// (4:2:0) rows. Storage is O(width), not O(width * height).
  /// This is Canon's chained four-Y predictor, NOT Sony's two-row predictor.
  pub fn decode_canon_rows(&self, mut emit: impl FnMut(usize, &[u16]) -> Result<(), String>) -> Result<(), String> {
    let width = self.width();
    let height = self.height();
    let rows = self.super_v();
    if self.components() != 3 || self.super_h() != 2 || !matches!(rows, 1 | 2)
      || self.sof.components[1..].iter().any(|c| c.super_h != 1 || c.super_v != 1)
      || self.predictor != 1 || self.point_transform != 0
      || width == 0 || width % 6 != 0 || height == 0 || height % rows != 0 {
      return Err("Invalid Canon streaming JPEG layout".into());
    }
    let tables = [0, 1, 2].map(|c| &self.dhts[self.sof.components[c].dc_tbl_num]);
    let mut pump = BitPumpJPEG::new(self.buffer);
    let base = 1i32 << (self.sof.precision - self.point_transform - 1);
    let mut previous_first = [base; 3];
    let mut pixels = vec![0u16; width * rows];
    for row in (0..height).step_by(rows) {
      let mut previous = previous_first;
      for col in (0..width).step_by(6) {
        let y1 = previous[0] + tables[0].huff_decode(&mut pump)?;
        let y2 = y1 + tables[0].huff_decode(&mut pump)?;
        let (y3, y4) = if rows == 2 {
          let y3 = y2 + tables[0].huff_decode(&mut pump)?;
          (y3, y3 + tables[0].huff_decode(&mut pump)?)
        } else { (0, 0) };
        let cb = previous[1] + tables[1].huff_decode(&mut pump)?;
        let cr = previous[2] + tables[2].huff_decode(&mut pump)?;
        // Cast at the same MCU boundaries as the full reference decoder.
        if col == 0 { previous_first = [y1 as u16 as i32, cb as u16 as i32, cr as u16 as i32]; }
        pixels[col..col + 6].copy_from_slice(&[y1 as u16, cb as u16, cr as u16, y2 as u16, cb as u16, cr as u16]);
        if rows == 2 {
          pixels[width + col..width + col + 6].copy_from_slice(&[y3 as u16, cb as u16, cr as u16, y4 as u16, cb as u16, cr as u16]);
        }
        previous = [if rows == 2 { y4 as u16 as i32 } else { y2 as u16 as i32 }, cb as u16 as i32, cr as u16 as i32];
      }
      emit(row, &pixels)?;
    }
    Ok(())
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  fn jpeg(w: u16, h: u16, v: u8) -> Vec<u8> {
    let mut b = vec![255,216,255,195,0,17,14];
    b.extend(h.to_be_bytes()); b.extend(w.to_be_bytes());
    b.extend([3,0,0x20|v,0,1,0x11,0,2,0x11,0]);
    // Codes 00 -> zero difference, 01 -> one-bit signed difference.
    b.extend([255,196,0,21,0,0,2]); b.extend([0;14]); b.extend([0,1]);
    b.extend([255,218,0,12,3,0,0,1,0,2,0,1,0,0]);
    let symbols = usize::from(w / 2) * usize::from(h / u16::from(v)) * (usize::from(v)*2+2);
    let mut bits = Vec::new();
    for i in 0..symbols { if i%3==0 { bits.extend([0,0]); } else { bits.extend([0,1,(i%2) as u8]); } }
    while bits.len()%8 != 0 { bits.push(1); }
    for byte in bits.chunks(8) { let n=byte.iter().fold(0,|a,x| (a<<1)|x); b.push(n); if n==255 {b.push(0);} }
    b.extend([255,217]); b
  }
  #[test]
  fn rows_match_original_u16_for_both_canon_sampling_modes() {
    for v in [1,2] { for w in [2,4,18,130] { for h in [2,6,12] {
      let bytes=jpeg(w,h,v); let d=LjpegDecompressor::new(&bytes).unwrap();
      let mut expected=vec![0;d.width()*d.height()];
      d.decode(&mut expected,0,d.width(),d.width(),d.height(),false).unwrap();
      let mut actual=Vec::new();
      d.decode_canon_rows(|row,p| { assert_eq!(row*d.width(),actual.len()); actual.extend_from_slice(p); Ok(()) }).unwrap();
      assert_eq!(actual,expected,"w={w} h={h} v={v}");
    } } }
  }
  #[test]
  fn unsupported_predictor_or_transform_rejected_before_callback() {
    for (index,value) in [(11,2),(13,1)] {
      let mut bytes=jpeg(18,12,2);
      let sos=bytes.windows(2).position(|p|p==[255,218]).unwrap();
      bytes[sos+index]=value;
      let d=LjpegDecompressor::new(&bytes).unwrap();
      let mut called=false;
      assert!(d.decode_canon_rows(|_,_| {called=true;Ok(())}).is_err());
      assert!(!called);
    }
  }
  #[test]
  fn callback_failure_stops_without_later_rows() {
    let bytes=jpeg(18,12,2); let d=LjpegDecompressor::new(&bytes).unwrap();
    let mut calls=0; assert_eq!(d.decode_canon_rows(|_,_| {calls+=1; Err("cancelled".into())}),Err("cancelled".into()));
    assert_eq!(calls,1);
  }
}
