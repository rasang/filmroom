use film_codecs::{dng::Dng,develop::{decode,Budget}};
use std::{fs,path::PathBuf,sync::atomic::AtomicBool};
#[path="common/scale.rs"] mod common;
use common::*;
fn path(s:&str)->PathBuf{std::env::temp_dir().join(format!("scale-{}-{s}",std::process::id()))}
fn fixture(name:&str)->Vec<u8>{fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)).unwrap()}
fn export(bytes:Vec<u8>,name:&str,b:&Budget)->(Dng,Vec<u8>){
 let p=path(name);let o=path(&format!("{name}.f32"));fs::write(&p,bytes).unwrap();
 let d=Dng::read(&mut fs::File::open(&p).unwrap()).unwrap();decode(&p,&o,b,&AtomicBool::new(false)).unwrap();
 let data=fs::read(&o).unwrap();assert_eq!(data.len()as u64,d.probe().output_bytes);
 assert!(data.chunks_exact(4).all(|c|f32::from_le_bytes(c.try_into().unwrap()).is_finite()));
 fs::remove_file(p).unwrap();fs::remove_file(o).unwrap();(d,data)
}
#[test]fn scale_identity_and_lossless_storage_equivalence(){
 for name in ["RGGB.dng","BGGR.dng","GRBG.dng","GBRG.dng","linear.dng"]{
  let (_,old)=export(fixture(name),&format!("{name}-old"),&Budget::default());
  let (_,explicit)=export(replace(fixture(name),50718,5,2,rational(&[(1,1),(1,1)])),&format!("{name}-unit"),&Budget::default());assert_eq!(old,explicit);
 }
 for name in ["baseline","crop","tiles","strips","edge"]{
  for scale in [[(2,1),(3,2)],[(1,2),(2,3)]]{
   let mut result=vec![];
   for suffix in ["","-plain"]{let n=format!("lossless-{name}{suffix}.dng");let (_,out)=export(replace(fixture(&n),50718,5,2,rational(&scale)),&format!("{n}-{}",scale[0].0),&Budget::default());result.push(out);}
   assert_eq!(result[0],result[1],"{name} {scale:?}");
  }
 }
}
#[test]fn target_uses_unrounded_crop_size_and_orientation_once(){
 let b=replace(replace(fixture("RGGB.dng"),50720,5,2,rational(&[(33,4),(37,4)])),50718,5,2,rational(&[(3,2),(3,2)]));
 let mut canonical=vec![];
 for orientation in 1..=8u16 {
  let bytes=replace(b.clone(),274,3,1,orientation.to_le_bytes().to_vec());
  let(d,out)=export(bytes,&format!("orientation-{orientation}"),&Budget::default());
  assert_eq!([d.crop.w,d.crop.h],[8,9]);assert_eq!(d.scaled_size,Some([12,14]));
  let (w,h)=d.oriented_dim();assert_eq!((w,h),if orientation>=5{(14,12)}else{(12,14)});
  let px:Vec<_>=out.chunks_exact(12).collect();
  if orientation==1{canonical=px.iter().map(|p|p.to_vec()).collect();}else{
   for y in 0..14{for x in 0..12{
    let (ox,oy)=match orientation{2=>(11-x,y),3=>(11-x,13-y),4=>(x,13-y),5=>(y,x),6=>(13-y,x),7=>(13-y,11-x),8=>(y,11-x),_=>(x,y)};
    assert_eq!(px[oy*w+ox],canonical[y*12+x]);
   }}
  }
 }
}
#[test]fn insufficient_budget_and_cancel_leave_no_output(){
 let p=path("budget");let o=path("budget.f32");fs::write(&p,replace(fixture("RGGB.dng"),50718,5,2,rational(&[(2,1),(2,1)]))).unwrap();
 let mut b=Budget::default();b.disk_budget_bytes=1;assert_eq!(decode(&p,&o,&b,&AtomicBool::new(false)).unwrap_err().code,"DISK_BUDGET");assert!(!o.exists());
 assert_eq!(decode(&p,&o,&Budget::default(),&AtomicBool::new(true)).unwrap_err().code,"CANCELLED");assert!(!o.exists());fs::remove_file(p).unwrap();
}
#[test]fn oversized_target_is_resource_error_not_corruption(){
 for (s,expected) in [(100000u32,"EDGE_LIMIT"),(1000,"PIXEL_LIMIT")] {
  let p=path(&format!("oversize-{s}"));let o=path(&format!("oversize-{s}.f32"));
  fs::write(&p,replace(replace(fixture("RGGB.dng"),50720,5,2,rational(&[(16,1),(16,1)])),50718,5,2,rational(&[(s,1),(s,1)]))).unwrap();
  let e=decode(&p,&o,&Budget::default(),&AtomicBool::new(false)).unwrap_err();assert_eq!(e.code,expected);assert!(!o.exists());fs::remove_file(p).unwrap();
 }
}
