// Mirrors vendor streaming unit tests through the public dependency API.
#[cfg(test)]
mod tests {
  use rawler::decompressors::ljpeg::LjpegDecompressor;
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
