//! Tests compile the actual production CF2 source against real Rawler types.
pub use rawler::{alloc_image_ok,bits,formats,pixarray,pumps,rawsource,RawlerError};
pub mod decoders {pub use rawler::Result;pub use rawler::decoders::rw2;}
macro_rules! fetch_tiff_tag {($ifd:expr,$tag:expr)=>{$ifd.get_entry($tag).map(|entry|&entry.value).ok_or(format!("Missing {}",stringify!($tag)))?};}
mod cf2 {
include!("../vendor/rawler-0.7.2/src/decoders/rw2/v8decompressor.rs");
#[test] fn serial_vertical_and_two_axis_strips_cover_sensor_without_aliasing() {
 for (nh,nv) in [(1,2),(2,2)] {
  let n=nh*nv;let w=4/nh;let h=4/nv;
  let p=CF2Params{strip_height:0,strip_width:0,gamma_point:vec![65536;6],gamma_slope:vec![0;6],gamma_clip_val:65535,
   huf_init_val0:100,huf_init_val1:100,huf_init_val2:100,huf_init_val3:100,huf_table:(0..17).map(|i|(5,i)).collect(),huf_shift_down:vec![0;17],
   num_of_strips_h:nh as u16,num_of_strips_v:nv as u16,strip_byte_offsets:(0..n).map(|i|8+i as u32*8).collect(),
   strip_line_offsets:(0..n).map(|i|StripLineOffset{cols:((i%nh)*w) as u16,rows:((i/nh)*h) as u16}).collect(),
   strip_data_size:vec![64;n],strip_widths:vec![w as u16;n],strip_heights:vec![h as u16;n]};
  p.validate(4,4,8+n*8,16).unwrap();let mut out=PixU16::new(4,4);
  for i in 0..n {decode_strip(&[0;8],&p,i,&mut out,&||false).unwrap();}
  assert_eq!(out.data,vec![100;16]);
 }
}
}
