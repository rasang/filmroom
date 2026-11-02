//! Private host validation harness, not an Android API or production configuration channel.
use film_codecs::{develop::Budget,raw,raw_batch::RawBatch};
use std::{path::Path,sync::{Arc,atomic::{AtomicBool,Ordering}}};
fn main(){
 let a:Vec<String>=std::env::args().collect();assert!(a.len()>=6,"probe|decode input output memoryMiB auto|small|standard|large [cancelMs]");
 let mode:RawBatch=serde_json::from_value(serde_json::Value::String(a[5].clone())).unwrap();
 let b=Budget{memory_budget_mi_b:a[4].parse().unwrap(),disk_budget_bytes:1024*1024*1024,raw_batch:mode};
 let cancel=Arc::new(AtomicBool::new(false));
 if let Some(ms)=a.get(6){let ms:u64=ms.parse().unwrap();let signal=cancel.clone();std::thread::spawn(move||{std::thread::sleep(std::time::Duration::from_millis(ms));signal.store(true,Ordering::Relaxed);});}
 let result=if a[1]=="probe" {raw::probe(Path::new(&a[2]),&b,false).map(|(p,b)|serde_json::json!({"ok":true,"probe":p,"admission":b}))}
 else {raw::decode(Path::new(&a[2]),Path::new(&a[3]),&b,&cancel).map(|(p,b)|serde_json::json!({"ok":true,"probe":p,"admission":b}))};
 match result {Ok(v)=>println!("{v}"),Err(e)=>{println!("{}",serde_json::json!({"ok":false,"error":e}));std::process::exit(1)}}
}
