//! Offline validation harness, not a production entry point.
use film_codecs::{camera_raw::CameraRaw,develop::Budget};
use rawler::{rawsource::RawSource,RawImageData};
use std::{path::Path,fs::File,io::{Write,BufWriter}};
fn rss(stage:&str){let s=std::fs::read_to_string("/proc/self/status").unwrap();println!("STAGE {stage} {}",s.lines().filter(|s|s.starts_with("VmRSS:")||s.starts_with("VmHWM:")).collect::<Vec<_>>().join("; "));}
fn main(){let a:Vec<_>=std::env::args().collect();let p=Path::new(&a[1]);rss("start");
let c=match CameraRaw::read(p){Ok(c)=>c,Err(e)=>{println!("{}",serde_json::json!({"ok":false,"error":e}));return;}};rss("probe");
let b=Budget{memory_budget_mi_b:256,..Default::default()};
let source=RawSource::new(p).unwrap();let raw=film_codecs::camera_raw::CameraRaw::read(p).unwrap();let meta=rawler::decode_dummy(&source).unwrap();
println!("{}",serde_json::json!({"ok":true,"make":meta.make,"model":meta.model,"black":meta.blacklevel.as_bayer_array(),"white":meta.whitelevel.as_bayer_array(),"wb":&meta.wb_coeffs[..3],"matrix":raw.development.matrix,"cfa":raw.development.cfa,"probe":c.probe(),"admission":c.admit(&b).unwrap()}));drop(meta);drop(raw);drop(c);
if a.len()>2 {let raw=rawler::decode(&source,&Default::default()).unwrap();drop(source);rss("sensor");let RawImageData::Integer(v)=raw.data else{panic!()};let mut f=BufWriter::new(File::create(&a[2]).unwrap());for x in v {f.write_all(&x.to_le_bytes()).unwrap();}f.flush().unwrap();}
rss("end");}
