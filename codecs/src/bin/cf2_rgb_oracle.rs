// Test-only high-memory development oracle. Independent LibRaw sensor is required; no entropy decode here.
use rawler::{RawImageData,rawsource::RawSource,imgop::develop::{RawDevelop,ProcessingStep,Intermediate}};
use std::{fs::File,io::{Write,BufWriter},path::Path};
fn rss(stage:&str){let s=std::fs::read_to_string("/proc/self/status").unwrap();println!("STAGE {stage} {}",s.lines().filter(|s|s.starts_with("VmRSS:")||s.starts_with("VmHWM:")).collect::<Vec<_>>().join("; "));}
fn main(){let a:Vec<_>=std::env::args().collect();rss("start");let source=RawSource::new(Path::new(&a[1])).unwrap();assert_eq!(a.len(),5);let mut raw=rawler::decode_dummy(&source).unwrap();drop(source);rss("sensor");

if let Some(path)=a.get(4) {let bytes=std::fs::read(path).unwrap();assert_eq!(bytes.len(),raw.width*raw.height*2);raw.data=RawImageData::Integer(bytes.chunks_exact(2).map(|v|u16::from_le_bytes([v[0],v[1]])).collect());}
if a.len()>3 {let mut dev=RawDevelop::default();dev.steps.retain(|s|*s!=ProcessingStep::SRgb);let Intermediate::ThreeColor(p)=dev.develop_intermediate(&raw).unwrap() else{panic!()};rss("full_rgb");let mut f=BufWriter::new(File::create(&a[3]).unwrap());for pixel in &p.data{for v in pixel {assert!(v.is_finite());f.write_all(&v.to_le_bytes()).unwrap();}}f.flush().unwrap();println!("OUTPUT {} {}",p.width,p.height);}
rss("end");}
