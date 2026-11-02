#[path="../vendor/rawler-0.7.2/src/decoders/cr2/bayer_black.rs"]
mod black;
use rawler::{RawImage,CFA,rawimage::BlackLevel,imgop::{Rect,Point,Dim2}};
#[test]
fn black_fd_order_matches_original_f32_accumulator_with_overlapping_areas() {
 let (w,h)=(258,130);let pixels:Vec<u16>=(0..w*h).map(|i|((i*7349+61531)%65536) as u16).collect();
 for name in ["RGGB","BGGR","GRBG","GBRG"] {
  let cfa=CFA::new(name);
  let tuples=[(3,1,251,126),(0,0,258,9),(1,2,7,126)];
  let areas:Vec<_>=tuples.iter().map(|&(x,y,w,h)|Rect::new(Point::new(x,y),Dim2::new(w,h))).collect();
  let expected=RawImage::calc_black_levels(&cfa,&areas,w,h,&pixels).unwrap();
  let mut reads=0;
  let levels=black::masked_black_levels(w,h,2,2,&tuples,|at,row|{reads+=1;assert!(row.len()<=w);row.copy_from_slice(&pixels[at..at+row.len()]);Ok(())}).unwrap();
  assert_eq!(BlackLevel::new(&levels,2,2,1).as_vec(),expected.as_vec());assert_eq!(reads,126+9+126);
 }
}
#[test]
fn invalid_black_area_and_read_failure_propagate() {
 assert!(black::masked_black_levels(8,8,2,2,&[(7,0,2,1)],|_,_|panic!("must not read")).is_err());
 assert!(black::masked_black_levels(8,8,2,2,&[(usize::MAX,0,2,1)],|_,_|panic!("must not read")).is_err());
 assert_eq!(black::masked_black_levels(8,8,2,2,&[(0,0,2,2)],|_,_|Err("cancelled".into())),Err("cancelled".into()));
}
