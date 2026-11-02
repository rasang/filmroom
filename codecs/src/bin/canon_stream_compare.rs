//! Offline high-memory golden oracle. NEVER an import fallback; private fixtures only.
use rawler::{RawImageData, rawsource::RawSource, decoders::cr2::CanonSrawStorage};
use std::{path::Path,fs::OpenOptions,os::unix::fs::{OpenOptionsExt,FileExt}};
struct Disk(std::fs::File, bool);
impl CanonSrawStorage for Disk {
 fn write(&mut self,at:usize,p:&[u16])->rawler::Result<()> {let b:Vec<_>=p.iter().flat_map(|p|p.to_le_bytes()).collect();self.0.write_all_at(&b,at as u64*2)?;Ok(())}
 fn read(&mut self,at:usize,p:&mut[u16])->rawler::Result<()> {
  if !self.1 {
   self.1=true;
   if let Some(path)=std::env::var_os("CANON_QA_YUV_SAVE") {
    // Private offline QA only: snapshot unsliced YCbCr before RGB overwrite.
    let mut output=OpenOptions::new().write(true).create_new(true).mode(0o600).open(path)?;
    let mut input=self.0.try_clone()?;
    std::io::copy(&mut input,&mut output)?;
   }
  }
  let mut b=vec![0;p.len()*2];self.0.read_exact_at(&mut b,at as u64*2)?;for(p,b)in p.iter_mut().zip(b.chunks_exact(2)){*p=u16::from_le_bytes([b[0],b[1]]);}Ok(())}
}
fn main(){
 let a:Vec<_>=std::env::args().collect();
 let source=RawSource::new(Path::new(&a[1])).unwrap();
 let b=source.buf(); assert_eq!(&b[..2],b"II"); assert_eq!(&b[8..12],b"CR\x02\0");
 let u16at=|o|u16::from_le_bytes(b[o..o+2].try_into().unwrap()) as usize;
 let u32at=|o|u32::from_le_bytes(b[o..o+4].try_into().unwrap()) as usize;
 let ifd=u32at(12);let count=u16at(ifd);let mut offset=None;
 for i in 0..count {let e=ifd+2+12*i;if u16at(e)==273 {offset=Some(u32at(e+8));}}
 let jpeg=rawler::decompressors::ljpeg::LjpegDecompressor::new(&b[offset.unwrap()..]).unwrap();
 let w=jpeg.width();let h=jpeg.height();let mut expected=vec![0;w*h];
 jpeg.decode(&mut expected,0,w,w,h,false).unwrap();
 let compare=|r:usize,p:&[u16]|{assert_eq!(p,&expected[r*w..r*w+p.len()],"JPEG row {r}");Ok(())};
 if jpeg.super_h()==2 {jpeg.decode_canon_rows(compare).unwrap();} else {jpeg.decode_raster_rows(compare).unwrap();}
 drop(expected);
 let file=OpenOptions::new().read(true).write(true).create_new(true).mode(0o600).open(&a[2]).unwrap();
 std::fs::remove_file(&a[2]).unwrap();file.set_len((w*h*2) as u64).unwrap();let mut disk=Disk(file,false);
 let decoder=rawler::get_decoder(&source).unwrap();
 let streamed=if jpeg.super_h()==2 {decoder.canon_sraw_stream(&source,&mut disk).unwrap()} else {decoder.canon_bayer_stream(&source,&mut disk).unwrap()};
 let full=rawler::decode(&source,&Default::default()).unwrap();
 assert_eq!((streamed.width,streamed.height,streamed.cpp),(full.width,full.height,full.cpp));
 assert_eq!(streamed.blacklevel.as_vec(),full.blacklevel.as_vec());assert_eq!(streamed.whitelevel.as_vec(),full.whitelevel.as_vec());
 assert_eq!(&streamed.wb_coeffs[..3],&full.wb_coeffs[..3]);assert_eq!(streamed.source_ifd_offset,full.source_ifd_offset);
 assert_eq!(streamed.crop_area,full.crop_area);assert_eq!(streamed.active_area,full.active_area);assert_eq!(streamed.orientation,full.orientation);
 assert_eq!(streamed.color_matrix,full.color_matrix);
 let RawImageData::Integer(expected)=&full.data else {panic!("not integer")};
 let mut row=vec![0u16;full.width*full.cpp];
 for (i,p)in expected.chunks_exact(row.len()).enumerate(){disk.read(i*row.len(),&mut row).unwrap();assert_eq!(p,&row,"RGB row {i}");}
 println!("{}",serde_json::json!({"jpeg_u16_exact":true,"sensor_u16_exact":true,"rgb_u16_exact":(full.cpp==3).then_some(true),"cpp":full.cpp,"metadata_exact":true,"width":full.width,"height":full.height,"jpeg_width":w,"jpeg_height":h,"sampling":[jpeg.super_h(),jpeg.super_v()]}));
}
