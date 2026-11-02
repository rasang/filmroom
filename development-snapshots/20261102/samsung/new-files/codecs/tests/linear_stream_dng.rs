use film_codecs::develop::{decode,Budget};use std::{fs,path::PathBuf,sync::atomic::AtomicBool};
fn tiff(packed:bool,orientation:u32)->Vec<u8>{
 let(w,h)=(18u16,20u16);let mut tags:Vec<(u16,u16,u32,Vec<u8>)>=Vec::new();
 let mut long=|tag,v:u32|tags.push((tag,4,1,v.to_le_bytes().to_vec()));
 for(t,v)in[(254,0),(256,w as u32),(257,h as u32),(258,if packed{12}else{16}),(259,if packed{7}else{1}),(262,34892),(273,0),(274,orientation),(277,3),(278,h as u32),(279,0),(284,1),(50717,4095),(50778,21)]{long(t,v)}
 tags.push((50706,1,4,vec![1,6,0,0]));tags.push((50707,1,4,vec![1,1,0,0]));
 tags.push((50728,5,3,[1u32,1,1,1,1,1].into_iter().flat_map(u32::to_le_bytes).collect()));
 // identity XYZ-to-camera is nonsingular; exact same development in both inputs.
 tags.push((50721,10,9,[1i32,0,0,0,1,0,0,0,1].into_iter().flat_map(|x|[x,1].into_iter().flat_map(i32::to_le_bytes)).collect()));
 tags.push((50829,4,4,[1u32,1,h as u32-1,w as u32-1].into_iter().flat_map(u32::to_le_bytes).collect()));
 tags.push((50719,4,2,[1u32,2].into_iter().flat_map(u32::to_le_bytes).collect()));tags.push((50720,4,2,[12u32,13].into_iter().flat_map(u32::to_le_bytes).collect()));
 let data=if packed{let mut b=vec![255,216,255,195,0,17,12];b.extend(h.to_be_bytes());b.extend(w.to_be_bytes());b.extend([3,0,17,0,1,17,0,2,17,0]);b.extend([255,196,0,20,0,1]);b.extend([0;15]);b.push(0);b.extend([255,221,0,4]);b.extend(w.to_be_bytes());b.extend([255,218,0,12,3,0,0,1,0,2,0,1,0,0]);for y in 0..h{b.extend([0;6]);b.push(3);if y+1<h{b.extend([255,208+(y%8) as u8]);}}b.extend([255,217]);b}else{std::iter::repeat_n(2048u16,w as usize*h as usize*3).flat_map(u16::to_le_bytes).collect()};
 tags.sort_by_key(|x|x.0);let base=8+2+tags.len()*12+4;let mut payload:Vec<u8>=Vec::new();let mut entries:Vec<u8>=Vec::new();
 for (tag,ty,n,v)in &tags{entries.extend(tag.to_le_bytes());entries.extend(ty.to_le_bytes());entries.extend(n.to_le_bytes());if v.len()<=4{entries.extend(v);entries.resize(entries.len()+4-v.len(),0)}else{entries.extend(((base+payload.len())as u32).to_le_bytes());payload.extend(v);}}
 let offset=(base+payload.len())as u32;for(i,(tag,_,_,_))in tags.iter().enumerate(){if *tag==273{entries[i*12+8..i*12+12].copy_from_slice(&offset.to_le_bytes());}if *tag==279{entries[i*12+8..i*12+12].copy_from_slice(&(data.len()as u32).to_le_bytes());}}
 let mut b=vec![73,73,42,0,8,0,0,0];b.extend((tags.len()as u16).to_le_bytes());b.extend(entries);b.extend([0;4]);b.extend(payload);b.extend(data);b
}
fn path(s:&str)->PathBuf{std::env::temp_dir().join(format!("linear-stream-{}-{s}",std::process::id()))}
#[test]fn streaming_no_halo_matches_old_linear_all_orientations_and_crop(){for o in 1..=8{let mut outputs=Vec::new();for packed in [false,true]{let input=path(&format!("{o}-{packed}.dng"));let output=path(&format!("{o}-{packed}.f32"));fs::write(&input,tiff(packed,o)).unwrap();decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();outputs.push(fs::read(&output).unwrap());fs::remove_file(input).unwrap();fs::remove_file(output).unwrap();}assert_eq!(outputs[0],outputs[1]);}}
#[test]
fn streaming_failure_leaves_no_partial_output_or_overwrites() {
    let input=path("faults.dng"); let output=path("faults.f32");
    let good=tiff(true,1);
    for cut in [1usize,2,3,12,30] {
        fs::write(&input,&good[..good.len()-cut]).unwrap();
        assert!(decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).is_err());
        assert!(!output.exists(),"partial output after truncation {cut}");
    }
    fs::write(&input,&good).unwrap();
    assert!(decode(&input,&output,&Budget::default(),&AtomicBool::new(true)).is_err());
    assert!(!output.exists());
    let mut tiny=Budget::default();tiny.memory_budget_mi_b=1;
    assert!(decode(&input,&output,&tiny,&AtomicBool::new(false)).is_err());
    assert!(!output.exists());
    fs::write(&output,b"existing output must survive").unwrap();
    assert!(decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).is_err());
    assert_eq!(fs::read(&output).unwrap(),b"existing output must survive");
    fs::remove_file(&output).unwrap();
    decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();
    fs::remove_file(input).unwrap();fs::remove_file(output).unwrap();
}
