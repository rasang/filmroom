use film_codecs::{dng::Dng, develop::{decode,Budget}};
use std::{fs, path::PathBuf,sync::atomic::AtomicBool};
fn path(s:&str)->PathBuf {std::env::temp_dir().join(format!("dng-standard-{}-{s}",std::process::id()))}
fn base(name:&str)->Vec<u8> {fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)).unwrap()}
fn replace(mut b:Vec<u8>,tag:u16,typ:u16,count:u32,data:Vec<u8>)->Vec<u8>{
 let off=u32::from_le_bytes(b[4..8].try_into().unwrap())as usize;
 let n=u16::from_le_bytes(b[off..off+2].try_into().unwrap())as usize;
 let mut entries:Vec<Vec<u8>>=(0..n).map(|i|b[off+2+i*12..off+14+i*12].to_vec()).filter(|e|u16::from_le_bytes(e[..2].try_into().unwrap())!=tag).collect();
 let mut e=Vec::new();e.extend(tag.to_le_bytes());e.extend(typ.to_le_bytes());e.extend(count.to_le_bytes());
 if data.len()<=4 {let mut v=[0;4];v[..data.len()].copy_from_slice(&data);e.extend(v);}else {e.extend((b.len()as u32).to_le_bytes());b.extend(data);}
 entries.push(e);entries.sort_by_key(|e|u16::from_le_bytes(e[..2].try_into().unwrap()));
 let off=b.len()as u32;b[4..8].copy_from_slice(&off.to_le_bytes());b.extend((entries.len()as u16).to_le_bytes());for e in entries {b.extend(e);}b.extend(0u32.to_le_bytes());b
}
fn rational(v:&[(u32,u32)])->Vec<u8>{v.iter().flat_map(|(a,b)|a.to_le_bytes().into_iter().chain(b.to_le_bytes())).collect()}
#[test]fn harmless_standard_metadata_preserves_developed_pixels(){
 let a=path("metadata-base");let b=path("metadata-rich");let oa=path("metadata-a.f32");let ob=path("metadata-b.f32");
 let mut bytes=base("RGGB.dng");
 for (tag,typ,count,data) in [(50942,2,5,b"test\0".to_vec()),(50966,2,4,b"app\0".to_vec()),(50972,1,16,vec![0;16]),(51089,3,2,vec![16,0,16,0]),(51110,4,1,0u32.to_le_bytes().to_vec())] {bytes=replace(bytes,tag,typ,count,data);}
 fs::write(&a,base("RGGB.dng")).unwrap();fs::write(&b,bytes).unwrap();
 for (i,o) in [(&a,&oa),(&b,&ob)] {decode(i,o,&Budget::default(),&AtomicBool::new(false)).unwrap();}
 assert_eq!(fs::read(&oa).unwrap(),fs::read(&ob).unwrap());
 for p in [a,b,oa,ob] {fs::remove_file(p).unwrap();}
}
#[test]fn subpixel_sized_crop_is_unsupported_not_corrupt_and_no_output(){
 let p=path("fractional");let out=path("fractional-out");
 fs::write(&p,replace(base("RGGB.dng"),50720,5,2,rational(&[(49,100),(12,1)]))).unwrap();
 let e=decode(&p,&out,&Budget::default(),&AtomicBool::new(false)).unwrap_err();
 assert_eq!(e.code,"UNSUPPORTED_DNG_VARIANT");assert_eq!(e.tag,Some(50720));assert_eq!(serde_json::to_value(&e).unwrap()["reason"],"UNSUPPORTED_VALUE");assert!(!out.exists());fs::remove_file(p).unwrap();
}
#[test]fn file_resource_limit_is_not_damage(){
 let p=path("large");let f=fs::File::create(&p).unwrap();f.set_len(512*1024*1024+1).unwrap();drop(f);
 let e=Dng::read(&mut fs::File::open(&p).unwrap()).unwrap_err();assert_eq!(e.code,"RESOURCE_LIMIT");fs::remove_file(p).unwrap();
}
#[test]fn linear_analog_balance_uses_full_calibration_not_old_matrix(){
 let mut b=base("linear.dng");
 let matrix:Vec<u8>=[1i32,0,0,0,1,0,0,0,1].iter().flat_map(|x|x.to_le_bytes().into_iter().chain(1i32.to_le_bytes())).collect();
 for t in [50721,50722,50964,50965] {b=replace(b,t,10,9,matrix.clone());}
 for (t,i) in [(50778,21u16),(50779,17u16)] {b=replace(b,t,3,1,i.to_le_bytes().to_vec());}
 b=replace(b,50727,5,3,rational(&[(2,1),(1,1),(1,1)]));
 let p=path("linear-ab");let out=path("linear-ab.f32");fs::write(&p,b).unwrap();
 let d=Dng::read(&mut fs::File::open(&p).unwrap()).unwrap();assert!(d.profile.is_some());assert!(d.matrix.is_empty());
 decode(&p,&out,&Budget::default(),&AtomicBool::new(false)).unwrap();
 assert!(fs::read(&out).unwrap().chunks_exact(4).all(|v|f32::from_le_bytes(v.try_into().unwrap()).is_finite()));
 fs::remove_file(p).unwrap();fs::remove_file(out).unwrap();
}
fn rename(mut b:Vec<u8>,old:u16,new:u16)->Vec<u8>{
 let off=u32::from_le_bytes(b[4..8].try_into().unwrap())as usize;let n=u16::from_le_bytes(b[off..off+2].try_into().unwrap())as usize;
 for i in 0..n {let p=off+2+i*12;if u16::from_le_bytes(b[p..p+2].try_into().unwrap())==old {b[p..p+2].copy_from_slice(&new.to_le_bytes());}}b
}
#[test]fn explicit_xy_and_equivalent_camera_neutral_develop_identically(){
 for name in ["linear.dng","RGGB.dng"] {
 let mut b=base(name);
 let matrix:Vec<u8>=[1i32,0,0,0,1,0,0,0,1].iter().flat_map(|x|x.to_le_bytes().into_iter().chain(1i32.to_le_bytes())).collect();
 for t in [50721,50722,50964,50965] {b=replace(b,t,10,9,matrix.clone());}
 for (t,i) in [(50778,21u16),(50779,17u16)] {b=replace(b,t,3,1,i.to_le_bytes().to_vec());}
 b=replace(b,50727,5,3,rational(&[(2,1),(1,1),(1,1)]));
 // Non-diagonal CC genuinely mixes channels; explicit XY must imply the
 // same camera neutral AB * CC * CM * XYZ, not merely select a different route.
 let cc:Vec<u8>=[10i32,1,0,0,10,0,0,0,10].iter().flat_map(|x|x.to_le_bytes().into_iter().chain(10i32.to_le_bytes())).collect();
 for t in [50723,50724] {b=replace(b,t,10,9,cc.clone());}
 b=replace(b,50728,5,3,rational(&[(22,10),(1,1),(1,1)]));
 let xy=replace(rename(b.clone(),50728,65000),50729,5,2,rational(&[(1,3),(1,3)]));
 let mut outputs=vec![];
 for (label,bytes) in [("neutral",b.clone()),("xy",xy)] {
 let p=path(&format!("{name}-{label}"));let out=path(&format!("{name}-{label}.f32"));fs::write(&p,bytes).unwrap();
 decode(&p,&out,&Budget::default(),&AtomicBool::new(false)).unwrap();
 outputs.push(fs::read(&out).unwrap());fs::remove_file(p).unwrap();fs::remove_file(out).unwrap();
 }
 let max=outputs[0].chunks_exact(4).zip(outputs[1].chunks_exact(4)).map(|(a,b)|(f32::from_le_bytes(a.try_into().unwrap())-f32::from_le_bytes(b.try_into().unwrap())).abs()).fold(0f32,f32::max);
 assert!(max<1e-5,"{name}: {max}");
 let conflict=replace(b,50729,5,2,rational(&[(1,3),(1,3)]));let p=path(&format!("{name}-conflict"));fs::write(&p,conflict).unwrap();
 assert_eq!(Dng::read(&mut fs::File::open(&p).unwrap()).unwrap_err().code,"INVALID_COLOR_METADATA");fs::remove_file(p).unwrap();
 }
}
#[test]fn short_neutral_is_equivalent_to_rational_in_legacy_development(){
 let p=path("short-neutral");let o=path("short-neutral.f32");let mut outputs=vec![];
 for (typ,data) in [(3,vec![1,0,1,0,1,0]),(5,rational(&[(1,1),(1,1),(1,1)]))]{
 fs::write(&p,replace(base("RGGB.dng"),50728,typ,3,data)).unwrap();decode(&p,&o,&Budget::default(),&AtomicBool::new(false)).unwrap();outputs.push(fs::read(&o).unwrap());fs::remove_file(&o).unwrap();
 }
 assert_eq!(outputs[0],outputs[1]);fs::remove_file(p).unwrap();
}
#[test]
fn single_standard_illuminant_and_scaled_neutral_decode_both_storage_models(){
 for name in ["linear.dng","RGGB.dng"] {
  for illuminant in [17u16,23,24,12,13,14,15,16] {
   let mut b=base(name);
   for (tag,unused) in [(50722,65001),(50779,65002),(50964,65003),(50965,65004),(50724,65005)] {b=rename(b,tag,unused);}
   let matrix:Vec<u8>=[1i32,0,0,0,1,0,0,0,1].iter().flat_map(|x|x.to_le_bytes().into_iter().chain(1i32.to_le_bytes())).collect();
   b=replace(b,50721,10,9,matrix);b=replace(b,50778,3,1,illuminant.to_le_bytes().to_vec());
   let mut pixels=vec![];
   for scale in [1,1000] {
    let p=path(&format!("single-{name}-{illuminant}-{scale}"));let o=p.with_extension("f32");
    fs::write(&p,replace(b.clone(),50728,5,3,rational(&[(scale,1),(scale,1),(scale,1)]))).unwrap();
    let d=Dng::read(&mut fs::File::open(&p).unwrap()).unwrap();assert!(d.cfa_color.is_some()||d.profile.is_some());
    decode(&p,&o,&Budget::default(),&AtomicBool::new(false)).unwrap();pixels.push(fs::read(&o).unwrap());fs::remove_file(p).unwrap();fs::remove_file(o).unwrap();
   }
   assert_eq!(pixels[0],pixels[1],"{name} illuminant {illuminant}");
  }
 }
}
#[test]
fn single_forward_matrix_develops_and_partial_dual_fm_fails_closed(){
 for name in ["linear.dng","RGGB.dng"] {
  let mut b=base(name);
  for (tag,unused) in [(50722,65001),(50779,65002),(50965,65004),(50724,65005)] {b=rename(b,tag,unused);}
  let matrix:Vec<u8>=[1i32,0,0,0,1,0,0,0,1].iter().flat_map(|x|x.to_le_bytes().into_iter().chain(1i32.to_le_bytes())).collect();
  for tag in [50721,50964]{b=replace(b,tag,10,9,matrix.clone());}
  b=replace(b,50778,3,1,23u16.to_le_bytes().to_vec());
  let p=path(&format!("single-fm-{name}"));let o=p.with_extension("f32");fs::write(&p,b.clone()).unwrap();
  decode(&p,&o,&Budget::default(),&AtomicBool::new(false)).unwrap();assert!(fs::read(&o).unwrap().chunks_exact(4).all(|v|f32::from_le_bytes(v.try_into().unwrap()).is_finite()));fs::remove_file(o).unwrap();
  b=replace(b,50722,10,9,matrix);b=replace(b,50779,3,1,17u16.to_le_bytes().to_vec());fs::write(&p,b).unwrap();
  assert_eq!(Dng::read(&mut fs::File::open(&p).unwrap()).unwrap_err().code,"UNSUPPORTED_DNG_CALIBRATION");fs::remove_file(p).unwrap();
 }
}

#[test]
fn fractional_crop_sdk_rounding_matches_integer_decode_all_orientations() {
 for fixture in ["RGGB.dng","linear.dng"] {
  let p=path(&format!("crop-source-{fixture}"));
  fs::write(&p,base(fixture)).unwrap();
  let d=Dng::read(&mut fs::File::open(&p).unwrap()).unwrap();
  let (ax,ay,aw,ah)=(2usize,2usize,(d.width-4)&!1,(d.height-4)&!1);
  let mut source=replace(base(fixture),50829,4,4,[ay,ax,ay+ah,ax+aw].iter().flat_map(|v|(*v as u32).to_le_bytes()).collect());
  // Fractional 0.5 + (bound - 0.5) independently rounds to 1 + bound,
  // then slides back to 0. The interior case rounds origin and size separately.
  for orientation in 1..=8u16 {
   source=replace(source,274,3,1,orientation.to_le_bytes().to_vec());
   for (case,origin,size,rounded) in [
    ("edge",[(1,2),(1,2)],[((aw*2-1)as u32,2),((ah*2-1)as u32,2)],[0,0,aw,ah]),
    ("interior",[(51,100),(49,100)],[(25,2),(23,2)],[1,0,13,12])
   ] {
    let mut outputs=vec![];
    for fractional in [true,false] {
     let ori=if fractional {origin}else{[(rounded[0]as u32,1),(rounded[1]as u32,1)]};
     let sz=if fractional {size}else{[(rounded[2]as u32,1),(rounded[3]as u32,1)]};
     let bytes=replace(replace(source.clone(),50719,5,2,rational(&ori)),50720,5,2,rational(&sz));
     fs::write(&p,bytes).unwrap();
     let parsed=Dng::read(&mut fs::File::open(&p).unwrap()).unwrap();
     assert_eq!((parsed.crop.x,parsed.crop.y,parsed.crop.w,parsed.crop.h),(ax+rounded[0],ay+rounded[1],rounded[2],rounded[3]));
     let expected=if orientation>=5 {(rounded[3],rounded[2])}else{(rounded[2],rounded[3])};
     assert_eq!(parsed.oriented_dim(),expected);
     let probe=parsed.probe();
     assert_eq!((probe.width as usize,probe.height as usize),expected);
     assert_eq!(probe.output_bytes,(expected.0*expected.1*12) as u64);
     let output=path(&format!("crop-{fixture}-{orientation}-{case}-{fractional}.f32"));
     decode(&p,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();
     let pixels=fs::read(&output).unwrap();
     assert_eq!(pixels.len(),expected.0*expected.1*12);
     outputs.push(pixels);fs::remove_file(output).unwrap();
    }
    assert_eq!(outputs[0],outputs[1],"{fixture}/{orientation}/{case}");
   }
  }
  fs::remove_file(p).unwrap();
 }
}

#[test]
fn fractional_crop_does_not_repair_original_out_of_bounds_or_accept_zero_scale() {
 let p=path("crop-invalid"); let output=path("crop-invalid-out");
 for (tag,data) in [(50719,rational(&[(u32::MAX,1),(0,1)])),
                    (50720,rational(&[(0,1),(12,1)])),
                    (50718,rational(&[(0,1),(1,1)]))] {
  fs::write(&p,replace(base("RGGB.dng"),tag,5,2,data)).unwrap();
  assert!(decode(&p,&output,&Budget::default(),&AtomicBool::new(false)).is_err());
  assert!(!output.exists());
 }
 fs::remove_file(p).unwrap();
}
