use film_codecs::{develop::{Budget,admit},dng::{Dng,Rect},raw_batch::RawBatch};
use std::{fs::File,path::Path};
const MIB:u64=1024*1024;
fn descriptor(w:usize,h:usize,cpp:usize)->Dng {
    let fixture=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/RGGB.dng");
    let mut d=Dng::read(&mut File::open(fixture).unwrap()).unwrap();
    d.width=w;d.height=h;d.cpp=cpp;d.active=Rect{x:0,y:0,w,h};d.crop=d.active;d.orientation=1;
    d
}
#[test] fn large_dimensions_and_linear_default_use_existing_budget_accounting() {
 for (w,h) in [(4080,3072),(10000,5000),(10000,5200),(12000,4000),(32768,1586)] {
  for cpp in [1,3] { for memory in [32,33,64,128] {
   let d=descriptor(w,h,cpp);let mut b=Budget{memory_budget_mi_b:memory,disk_budget_bytes:1024*MIB,raw_batch:RawBatch::Standard};
   let classic=admit(&d,&b);
   for mode in [RawBatch::Auto,RawBatch::Small,RawBatch::Standard,RawBatch::Large] {
    b.raw_batch=mode;let got=admit(&d,&b);
    match &classic {
     Err(e)=>assert_eq!(got.unwrap_err().code,e.code),
     Ok(c)=>{let a=got.unwrap();assert!(a.strip_rows>=8 && a.strip_rows<=256);assert!(a.estimated_peak_bytes<=memory*MIB);assert_eq!(a.required_disk_bytes,c.required_disk_bytes);
      if mode==RawBatch::Standard || (mode==RawBatch::Auto&&cpp==3){assert_eq!(a.strip_rows,c.strip_rows);}
      if mode==RawBatch::Auto&&cpp==1{assert!(a.strip_rows>=c.strip_rows);}
     }
    }
   }
  }}
 }
}
#[test] fn compressed_scratch_is_not_removed_to_force_a_large_preference() {
 let mut d=descriptor(12000,4000,1);d.compression=7;d.tile_width=4096;d.tile_height=512;d.counts=vec![8*1024*1024];
 let mut b=Budget::default();b.raw_batch=RawBatch::Standard;let c=admit(&d,&b).unwrap();
 b.raw_batch=RawBatch::Large;let large=admit(&d,&b).unwrap();
 assert_eq!(large.strip_rows,c.strip_rows);assert!(large.estimated_peak_bytes<=128*MIB);
 b.disk_budget_bytes=large.required_disk_bytes-1;assert_eq!(admit(&d,&b).unwrap_err().code,"DISK_BUDGET");
}
#[test] fn invalid_memory_cannot_be_laundered_by_a_small_preference() {
 let d=descriptor(4096,3072,1);
 for mode in [RawBatch::Auto,RawBatch::Small,RawBatch::Standard,RawBatch::Large] {
  let b=Budget{memory_budget_mi_b:u64::MAX,disk_budget_bytes:1024*MIB,raw_batch:mode};
  assert_eq!(admit(&d,&b).unwrap_err().code,"MEMORY_BUDGET");
 }
}
