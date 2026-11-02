//! Private-fixture cancellation/cleanup diagnostic; not a production entrypoint.
use std::{path::Path,sync::{Arc,atomic::{AtomicBool,Ordering}},time::Duration};
fn main() {
 let a:Vec<_>=std::env::args().collect();let cancel=Arc::new(AtomicBool::new(false));let c=cancel.clone();
 let ms:u64=a[3].parse().unwrap();
 let t=std::thread::spawn(move||{std::thread::sleep(Duration::from_millis(ms));c.store(true,Ordering::Relaxed);});
 let result=film_codecs::raw::decode(Path::new(&a[1]),Path::new(&a[2]),&Default::default(),&cancel);
 t.join().unwrap();
 let code=result.as_ref().err().map(|e|e.code);
 let partial=Path::new(&a[2]).exists();
 let leftovers=std::fs::read_dir(Path::new(&a[2]).parent().unwrap()).unwrap().filter_map(|e|e.ok()).filter(|e|e.file_name().to_string_lossy().starts_with(".canon-")).count();
 println!("{}",serde_json::json!({"code":code,"delay_ms":ms,"partial_output":partial,"named_temps":leftovers}));
 assert_eq!(code,Some("CANCELLED"));assert!(!partial);assert_eq!(leftovers,0);
}
