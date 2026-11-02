//! Research harness only; no JNI/App entry point. Explicit real resource/cancel injection.
use film_codecs::{develop::Budget,raw};
use std::{path::Path,sync::{Arc,atomic::{AtomicBool,Ordering}},time::Duration};
fn main(){
 let a:Vec<_>=std::env::args().collect();if a.len()!=4{std::process::exit(2)}
 let fd_before=std::fs::read_dir("/proc/self/fd").unwrap().count();
 let mut budget=Budget::default();let cancel=Arc::new(AtomicBool::new(false));
 if a[1]=="disk"{budget.disk_budget_bytes=1;}
 if a[1]=="file-limit"{unsafe{libc::signal(libc::SIGXFSZ,libc::SIG_IGN);let lim=libc::rlimit{rlim_cur:8192,rlim_max:8192};if libc::setrlimit(libc::RLIMIT_FSIZE,&lim)!=0{std::process::exit(3)}}}
 let delay=match a[1].as_str(){"cancel-before"=>{cancel.store(true,Ordering::Relaxed);None},"cancel-active"=>Some(25),"cancel-later"=>Some(1000),"retry-after-cancel"=>Some(25),_=>None};
 let worker=delay.map(|ms|{let c=cancel.clone();std::thread::spawn(move||{std::thread::sleep(Duration::from_millis(ms));c.store(true,Ordering::Relaxed);})});
 let mut result=raw::decode(Path::new(&a[2]),Path::new(&a[3]),&budget,&cancel);
 if let Some(w)=worker{w.join().unwrap();}
 if a[1]=="retry-after-cancel"{
  if result.as_ref().err().map(|e|e.code)!=Some("CANCELLED")||Path::new(&a[3]).exists(){std::process::exit(4)}
  cancel.store(false,Ordering::Relaxed);result=raw::decode(Path::new(&a[2]),Path::new(&a[3]),&budget,&cancel);
 }
 let fd_after=std::fs::read_dir("/proc/self/fd").unwrap().count();if fd_after!=fd_before{eprintln!("descriptor leak before={fd_before} after={fd_after}");std::process::exit(5)}
 match result{Ok((probe,admission))=>println!("{}",serde_json::json!({"ok":true,"probe":probe,"admission":admission,"fdBefore":fd_before,"fdAfter":fd_after})),Err(error)=>{println!("{}",serde_json::json!({"ok":false,"error":error,"fdBefore":fd_before,"fdAfter":fd_after}));std::process::exit(1)}}
}
