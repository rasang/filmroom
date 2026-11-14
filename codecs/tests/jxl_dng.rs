use film_codecs::develop::{decode,Budget};use std::{fs,path::PathBuf,sync::atomic::AtomicBool};
fn tiff(packed:bool,orientation:u32)->Vec<u8>{tiff_layout(packed,orientation,false)}
fn tiff_layout(packed:bool,orientation:u32,tiled:bool)->Vec<u8>{
 let(w,h)=(18u16,20u16);let mut tags:Vec<(u16,u16,u32,Vec<u8>)>=Vec::new();
 let mut long=|tag,v:u32|tags.push((tag,4,1,v.to_le_bytes().to_vec()));
 for(t,v)in[(254,0),(256,w as u32),(257,h as u32),(258,16),(259,if packed{52546}else{1}),(262,34892),(273,0),(274,orientation),(277,3),(278,h as u32),(279,0),(284,1),(50717,65535)]{long(t,v)}
 tags.push((50706,1,4,vec![1,7,0,0]));tags.push((50707,1,4,vec![1,1,0,0]));
 tags.push((50728,5,3,[1u32,1,1,1,1,1].into_iter().flat_map(u32::to_le_bytes).collect()));
 // identity XYZ-to-camera is nonsingular; exact same development in both inputs.
 tags.push((50721,10,9,[1i32,0,0,0,1,0,0,0,1].into_iter().flat_map(|x|[x,1].into_iter().flat_map(i32::to_le_bytes)).collect()));
 // Both encodings share the new normative calibrated developer via identity PGT.
 for tag in [50722,50964,50965]{tags.push((tag,10,9,[1i32,0,0,0,1,0,0,0,1].into_iter().flat_map(|x|[x,1].into_iter().flat_map(i32::to_le_bytes)).collect()));}
 for(tag,value)in[(50778,21u16),(50779,17u16)]{tags.push((tag,3,1,value.to_le_bytes().to_vec()));}
 let mut map=Vec::new();for n in [1u32,1]{map.extend(n.to_le_bytes());}for x in [1f64,1.,0.,0.]{map.extend(x.to_le_bytes());}map.extend(2u32.to_le_bytes());for x in [1f32,0.,0.,0.,0.,1.,1.]{map.extend(x.to_le_bytes());}tags.push((52525,7,map.len()as u32,map));
 tags.push((50829,4,4,[1u32,1,h as u32-1,w as u32-1].into_iter().flat_map(u32::to_le_bytes).collect()));
 tags.push((50719,4,2,[1u32,2].into_iter().flat_map(u32::to_le_bytes).collect()));tags.push((50720,4,2,[12u32,13].into_iter().flat_map(u32::to_le_bytes).collect()));
 let data=if packed {include_bytes!("fixtures/linear-u16-18x20.jxl").to_vec()}else{(0..w as usize*h as usize*3).flat_map(|i|[0u16,1,255,256,32767,32768,65534,65535][i%8].to_le_bytes()).collect()};
 if tiled{for(tag,_,_,_)in &mut tags{*tag=match *tag{273=>324,279=>325,278=>323,x=>x};}tags.push((322,4,1,(w as u32).to_le_bytes().to_vec()));}
 tags.sort_by_key(|x|x.0);let base=8+2+tags.len()*12+4;let mut payload:Vec<u8>=Vec::new();let mut entries:Vec<u8>=Vec::new();
 for (tag,ty,n,v)in &tags{entries.extend(tag.to_le_bytes());entries.extend(ty.to_le_bytes());entries.extend(n.to_le_bytes());if v.len()<=4{entries.extend(v);entries.resize(entries.len()+4-v.len(),0)}else{entries.extend(((base+payload.len())as u32).to_le_bytes());payload.extend(v);}}
 let offset=(base+payload.len())as u32;for(i,(tag,_,_,_))in tags.iter().enumerate(){if *tag==273||*tag==324{entries[i*12+8..i*12+12].copy_from_slice(&offset.to_le_bytes());}if *tag==279||*tag==325{entries[i*12+8..i*12+12].copy_from_slice(&(data.len()as u32).to_le_bytes());}}
 let mut b=vec![73,73,42,0,8,0,0,0];b.extend((tags.len()as u16).to_le_bytes());b.extend(entries);b.extend([0;4]);b.extend(payload);b.extend(data);b
}
fn path(s:&str)->PathBuf{std::env::temp_dir().join(format!("jxl-test-{}-{s}",std::process::id()))}

#[test] fn jxl_preserves_u16_and_all_dng_orientation_crop_profile_pixels(){
 for o in 1..=8{let mut outputs=Vec::new();for packed in [false,true]{let input=path(&format!("{o}-{packed}.dng"));let output=path(&format!("{o}-{packed}.f32"));fs::write(&input,tiff(packed,o)).unwrap();decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();outputs.push(fs::read(&output).unwrap());fs::remove_file(input).unwrap();fs::remove_file(output).unwrap();}assert_eq!(outputs[0],outputs[1],"orientation {o}");}
}
#[test]fn jxl_rejects_faults_and_never_leaves_output(){
 let input=path("faults.dng");let output=path("faults.f32");let good=tiff(true,1);
 for cut in [1usize,2,8,24,48]{fs::write(&input,&good[..good.len()-cut]).unwrap();assert!(decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).is_err());assert!(!output.exists());}
 fs::write(&input,&good).unwrap();assert_eq!(decode(&input,&output,&Budget::default(),&AtomicBool::new(true)).unwrap_err().code,"CANCELLED");assert!(!output.exists());
 for(memory,disk,expected)in[(32,1024*1024,"MEMORY_BUDGET"),(512,1024*1024,"MEMORY_BUDGET"),(128,1,"DISK_BUDGET")]{let mut b=Budget::default();b.memory_budget_mi_b=memory;b.disk_budget_bytes=disk;assert_eq!(decode(&input,&output,&b,&AtomicBool::new(false)).unwrap_err().code,expected);assert!(!output.exists());}
 fs::write(&output,b"keep existing").unwrap();assert_eq!(decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap_err().code,"OUTPUT_EXISTS");assert_eq!(fs::read(&output).unwrap(),b"keep existing");fs::remove_file(&output).unwrap();
 // Corrupt only compressed data but keep the valid TIFF envelope/byte count.
 let mut bad=good.clone();let at=bad.len()-10;bad[at]^=0xff;fs::write(&input,bad).unwrap();assert!(decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).is_err());assert!(!output.exists());
 fs::remove_file(input).unwrap();
}
#[test]fn jxl_rejects_mismatched_dimensions_and_tiles(){
 let input=path("mismatch.dng");let mut bytes=tiff(true,1);let n=u16::from_le_bytes([bytes[8],bytes[9]])as usize;for i in 0..n{let o=10+12*i;let tag=u16::from_le_bytes([bytes[o],bytes[o+1]]);if tag==256{bytes[o+8..o+12].copy_from_slice(&20u32.to_le_bytes());}}
 fs::write(&input,bytes).unwrap();assert_eq!(film_codecs::raw::probe(&input,&Budget::default(),true).unwrap_err().code,"INVALID_INPUT");
 fs::write(&input,tiff_layout(true,1,true)).unwrap();assert_eq!(film_codecs::raw::probe(&input,&Budget::default(),true).unwrap_err().code,"UNSUPPORTED_DNG_VARIANT");fs::remove_file(input).unwrap();
}
fn two_strips()->Vec<u8>{
 let mut b=tiff(true,1);let n=u16::from_le_bytes([b[8],b[9]])as usize;let data=include_bytes!("fixtures/linear-u16-18x20.jxl");let first=b.len()-data.len();let second=b.len();b.extend(data);
 let offsets=b.len();b.extend((first as u32).to_le_bytes());b.extend((second as u32).to_le_bytes());let counts=b.len();b.extend((data.len()as u32).to_le_bytes());b.extend((data.len()as u32).to_le_bytes());
 for i in 0..n{let o=10+12*i;let tag=u16::from_le_bytes([b[o],b[o+1]]);match tag{257=>b[o+8..o+12].copy_from_slice(&40u32.to_le_bytes()),273|279=>{b[o+4..o+8].copy_from_slice(&2u32.to_le_bytes());b[o+8..o+12].copy_from_slice(&(if tag==273{offsets}else{counts}as u32).to_le_bytes());},_=>{}}}
 b
}
#[test]fn multiple_strips_are_each_validated_and_decoded(){
 let a=path("one-strip.dng");let b=path("two-strips.dng");let out_a=path("one-strip.f32");let out_b=path("two-strips.f32");fs::write(&a,tiff(true,1)).unwrap();fs::write(&b,two_strips()).unwrap();
 decode(&a,&out_a,&Budget::default(),&AtomicBool::new(false)).unwrap();decode(&b,&out_b,&Budget::default(),&AtomicBool::new(false)).unwrap();assert_eq!(fs::read(&out_a).unwrap(),fs::read(&out_b).unwrap());fs::remove_file(&out_b).unwrap();
 let mut bad=two_strips();let n=bad.len();let size=include_bytes!("fixtures/linear-u16-18x20.jxl").len();bad[n-16-size+5]^=0x77;fs::write(&b,bad).unwrap();assert!(decode(&b,&out_b,&Budget::default(),&AtomicBool::new(false)).is_err());assert!(!out_b.exists());
 for p in[a,b,out_a]{fs::remove_file(p).unwrap();}
}

#[path="common/scale.rs"] mod scale_common;
#[test] fn scaled_storage_matches_plain_without_pgt(){
 for tiled in [false] {for orientation in 1..=8u32 {
  let mut outputs=vec![];
  for packed in [false,true] {
   let bytes=scale_common::replace(scale_common::replace(scale_common::rename(tiff_layout(packed,orientation,tiled),52525,65001),50727,5,3,scale_common::rational(&[(2,1),(1,1),(1,1)])),50718,5,2,scale_common::rational(&[(3,2),(2,3)]));
   let p=path(&format!("scale-{packed}-{tiled}-{orientation}"));let o=path(&format!("scale-{packed}-{tiled}-{orientation}.f32"));
   fs::write(&p,bytes).unwrap();decode(&p,&o,&Budget::default(),&AtomicBool::new(false)).unwrap();outputs.push(fs::read(&o).unwrap());fs::remove_file(p).unwrap();fs::remove_file(o).unwrap();
  }
  assert_eq!(outputs[0],outputs[1],"tiled={tiled} orientation={orientation}");
 }}
}
#[test]fn scaled_pgt_is_explicitly_unsupported(){
 let p=path("scaled-pgt");let o=path("scaled-pgt.f32");
 fs::write(&p,scale_common::replace(tiff(false,1),50718,5,2,scale_common::rational(&[(2,1),(2,1)]))).unwrap();
 let e=decode(&p,&o,&Budget::default(),&AtomicBool::new(false)).unwrap_err();assert_eq!(e.code,"UNSUPPORTED_DNG_CALIBRATION");assert_eq!(e.tag,Some(50718));assert!(!o.exists());fs::remove_file(p).unwrap();
}
