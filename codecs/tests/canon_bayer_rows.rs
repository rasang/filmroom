//! Public API compared independently to old full raster decoder and encoder input.
use rawler::{decompressors::ljpeg::LjpegDecompressor,ljpeg92::LjpegCompressor};
#[test]
fn all_predictors_components_and_edges_equal_original_samples() {
 for predictor in 1..=7 {for cpp in 1..=4 {for w in [1,2,17] {for h in [1,2,9] {for bits in [12,14] {
  let input:Vec<u16>=(0..w*h*cpp).map(|i|((i*977+i*i*31/3)% (1usize<<bits)) as u16).collect();
  let encoded=LjpegCompressor::new(&input,w,h,cpp,bits,predictor,0,0).unwrap().encode().unwrap();
  let jpeg=LjpegDecompressor::new(&encoded).unwrap();
  let mut expected=vec![0;input.len()];jpeg.decode(&mut expected,0,w*cpp,w*cpp,h,false).unwrap();
  let mut actual=Vec::new();jpeg.decode_raster_rows(|row,p|{assert_eq!(actual.len(),row*w*cpp);actual.extend_from_slice(p);Ok(())}).unwrap();
  assert_eq!(actual,expected,"predictor={predictor} cpp={cpp} {w}x{h}");assert_eq!(actual,input);
 }}}}}
}
#[test]
fn callback_failure_stops_immediately() {
 let input=vec![500u16;32*8*4];let encoded=LjpegCompressor::new(&input,32,8,4,14,1,0,0).unwrap().encode().unwrap();
 let jpeg=LjpegDecompressor::new(&encoded).unwrap();let mut count=0;
 assert_eq!(jpeg.decode_raster_rows(|_,_|{count+=1;Err("cancelled".into())}),Err("cancelled".into()));assert_eq!(count,1);
}

#[test]
fn signed_odd_predictors_and_u16_wrap_match_original() {
 for predictor in 1..=7 {
  let mut b=vec![255,216,255,195,0,20,14,0,2,0,2,4];
  for c in 0..4 {b.extend([c,0x11,0]);}
  b.extend([255,196,0,21,0,0,2]);b.extend([0;14]);b.extend([0,14]);
  b.extend([255,218,0,14,4]);for c in 0..4 {b.extend([c,0]);}b.extend([predictor,0,0]);
  let differences=[-8193,8192,-8192,0,8193,-8193,0,8192,8192,0,-8193,8193,-8193,8193,8192,0];
  let mut bits=Vec::new();for diff in differences {
   if diff==0 {bits.extend([0,0]);} else {
    bits.extend([0,1]);let value=if diff<0 {diff+16383} else {diff};
    for bit in (0..14).rev(){bits.push(((value>>bit)&1) as u8);}
   }
  }
  while bits.len()%8!=0 {bits.push(1);}
  for bits in bits.chunks_exact(8){let v=bits.iter().fold(0u8,|a,b|(a<<1)|b);b.push(v);if v==255 {b.push(0);}}
  b.extend([255,217]);let jpeg=LjpegDecompressor::new(&b).unwrap();
  let mut expected=vec![0;16];jpeg.decode(&mut expected,0,8,8,2,false).unwrap();
  assert_eq!(expected[0],65535); // negative first reconstruction wraps at u16 boundary
  let mut actual=Vec::new();jpeg.decode_raster_rows(|_,p|{actual.extend_from_slice(p);Ok(())}).unwrap();assert_eq!(actual,expected);
 }
}
