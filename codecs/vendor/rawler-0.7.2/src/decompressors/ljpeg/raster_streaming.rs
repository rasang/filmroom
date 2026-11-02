// SPDX-License-Identifier: LGPL-2.1
//! Full-width non-subsampled SOF3 row decoder, bounded to two sample rows.
//! As with decode_canon_rows, callers MUST first run the strict entropy validator
//! because the existing BitPumpJPEG pads exhausted input. Restart streams are
//! not admitted; no new entropy/control acceptance is implied by this API.
use super::LjpegDecompressor;
use crate::pumps::BitPumpJPEG;

impl LjpegDecompressor<'_> {
  pub fn decode_raster_rows(&self, mut emit: impl FnMut(usize, &[u16]) -> Result<(), String>) -> Result<(), String> {
    let width=self.width();let height=self.height();let components=self.components();
    if width==0 || height==0 || !(1..=4).contains(&components) || width%components!=0
      || self.sof.components.iter().any(|c|c.super_h!=1 || c.super_v!=1)
      || !(1..=7).contains(&self.predictor) || self.point_transform!=0 {
      return Err("Invalid raster streaming JPEG layout".into());
    }
    let base=1i32<<(self.sof.precision-1);
    let mut pump=BitPumpJPEG::new(self.buffer);
    let mut previous=vec![0u16;width];let mut current=vec![0u16;width];
    for row in 0..height {
      for col in (0..width).step_by(components) {
        for c in 0..components {
          let prediction=if col==0 {
            if row==0 {base} else {previous[c] as i32}
          } else {
            let a=current[col-components+c] as i32;
            if row==0 {a} else {
              let b=previous[col+c] as i32;
              let diagonal=previous[col-components+c] as i32;
              match self.predictor {1=>a,2=>b,3=>diagonal,4=>a+b-diagonal,5=>a+((b-diagonal)>>1),6=>b+((a-diagonal)>>1),7=>(a+b)>>1,_=>unreachable!()}
            }
          };
          let table=&self.dhts[self.sof.components[c].dc_tbl_num];
          current[col+c]=(prediction+table.huff_decode(&mut pump)?) as u16;
        }
      }
      emit(row,&current)?;
      std::mem::swap(&mut current,&mut previous);
    }
    Ok(())
  }
}
