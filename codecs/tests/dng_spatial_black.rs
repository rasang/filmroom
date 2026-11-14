use film_codecs::{dng::Dng,develop::{decode,Budget}};
use std::{fs,fs::File,path::PathBuf,sync::atomic::AtomicBool};
fn temp(name:&str)->PathBuf {std::env::temp_dir().join(format!("film-black-{}-{name}",std::process::id()))}
fn fixture(name:&str)->Vec<u8> {fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)).unwrap()}
fn replace(b:&mut Vec<u8>,tag:u16,typ:u16,count:u32,payload:Vec<u8>) {
 assert_eq!(&b[..2],b"II");let off=u32::from_le_bytes(b[4..8].try_into().unwrap())as usize;
 let n=u16::from_le_bytes(b[off..off+2].try_into().unwrap())as usize;
 let mut entries:Vec<Vec<u8>>=(0..n).map(|i|b[off+2+i*12..off+14+i*12].to_vec()).filter(|e|u16::from_le_bytes(e[..2].try_into().unwrap())!=tag).collect();
 let mut e=vec![];e.extend(tag.to_le_bytes());e.extend(typ.to_le_bytes());e.extend(count.to_le_bytes());
 if payload.len()<=4 {let mut p=[0u8;4];p[..payload.len()].copy_from_slice(&payload);e.extend(p);}else{e.extend((b.len()as u32).to_le_bytes());b.extend(payload);}
 entries.push(e);entries.sort_by_key(|e|u16::from_le_bytes(e[..2].try_into().unwrap()));
 let at=b.len()as u32;b[4..8].copy_from_slice(&at.to_le_bytes());b.extend((entries.len()as u16).to_le_bytes());for e in entries {b.extend(e);}b.extend(0u32.to_le_bytes());
}
fn short(b:&mut Vec<u8>,tag:u16,a:&[u16]) {replace(b,tag,3,a.len()as u32,a.iter().flat_map(|v|v.to_le_bytes()).collect());}
fn long(b:&mut Vec<u8>,tag:u16,a:&[u32]) {replace(b,tag,4,a.len()as u32,a.iter().flat_map(|v|v.to_le_bytes()).collect());}
fn signed(b:&mut Vec<u8>,tag:u16,a:&[i32]) {replace(b,tag,10,a.len()as u32,a.iter().flat_map(|v|[*v,1].into_iter().flat_map(i32::to_le_bytes)).collect());}
#[test]
fn spatial_black_real_decode_matches_uniform_reference_and_strip_reads() {
 for (id,name) in [(0,"RGGB.dng"),(1,"linear.dng")] {
  let input=temp(&format!("input{id}.dng"));let reference=temp(&format!("ref{id}.dng"));let output=temp(&format!("out{id}.f32"));let refout=temp(&format!("ref{id}.f32"));
  let mut b=fixture(name);fs::write(&input,&b).unwrap();let d=Dng::read(&mut File::open(&input).unwrap()).unwrap();
  assert_eq!(d.compression,1);assert_eq!(d.sample_bits,16);
  let (w,h)=(d.width-4,d.height-4);let cpp=d.cpp;
  long(&mut b,50829,&[2,2,(h+2)as u32,(w+2)as u32]);long(&mut b,50719,&[0,0]);long(&mut b,50720,&[w as u32,h as u32]);
  let mut r=b.clone();
  let base:Vec<u16>=(0..6*cpp).map(|i|10+i as u16*3).collect();
  let dh:Vec<i32>=(0..w).map(|x|(x%5)as i32-2).collect();let dv:Vec<i32>=(0..h).map(|y|(y%7)as i32-3).collect();
  let black=|x:usize,y:usize,c:usize|base[((y%2)*3+x%3)*cpp+c]as i32+dh[x]+dv[y];
  let mut maxima=vec![i32::MIN;cpp];for y in 0..h {for x in 0..w {for c in 0..cpp {maxima[c]=maxima[c].max(black(x,y,c));}}}
  short(&mut b,50713,&[2,3]);short(&mut b,50714,&base);signed(&mut b,50715,&dh);signed(&mut b,50716,&dv);
  long(&mut b,50717,&maxima.iter().map(|v|(*v+1000)as u32).collect::<Vec<_>>());
  short(&mut r,50713,&[1,1]);short(&mut r,50714,&vec![0;cpp]);long(&mut r,50717,&vec![1000;cpp]);
  for sy in 0..d.height {for sx in 0..d.width {for c in 0..cpp {
   let at=d.offsets[sy/d.rows_per_strip]as usize+((sy%d.rows_per_strip)*d.width*cpp+sx*cpp+c)*2;
   let v=if (2..w+2).contains(&sx)&&(2..h+2).contains(&sy) {black(sx-2,sy-2,c)+500}else{500};
   b[at..at+2].copy_from_slice(&(v as u16).to_le_bytes());r[at..at+2].copy_from_slice(&500u16.to_le_bytes());
  }}}
  fs::write(&input,b).unwrap();fs::write(&reference,r).unwrap();
  let parsed=Dng::read(&mut File::open(&input).unwrap()).unwrap();
  for strip in [1,3,7] {for y in (2..h+2).step_by(strip) {
   let p=parsed.read_tile(&mut File::open(&input).unwrap(),y,strip.min(h+2-y)).unwrap();assert!(p.iter().all(|v|*v==0.5));
  }}
  for (i,o) in [(&input,&output),(&reference,&refout)] {decode(i,o,&Budget::default(),&AtomicBool::new(false)).unwrap();}
  assert_eq!(fs::read(&output).unwrap(),fs::read(&refout).unwrap());
  for p in [input,reference,output,refout] {fs::remove_file(p).unwrap();}
 }
}
