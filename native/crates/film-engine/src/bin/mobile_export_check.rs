//! Own-cloud validation harness; no network, no image decoding or upload.
use std::{env,fs,path::Path};
fn copy_dir(src:&Path,dst:&Path)->std::io::Result<()>{fs::create_dir_all(dst)?;for e in fs::read_dir(src)?{let e=e?;let to=dst.join(e.file_name());if e.file_type()?.is_dir(){copy_dir(&e.path(),&to)?;}else{fs::copy(e.path(),to)?;}}Ok(())}
fn main()->Result<(),Box<dyn std::error::Error>>{
 let a:Vec<String>=env::args().collect();if a.len()!=8{return Err("DATA INPUT_F32 PARAMS_JSON WIDTH HEIGHT NEW_PRIVATE_ROOT METRICS_JSON".into());}
 let root=Path::new(&a[6]);fs::create_dir(root)?;let data=root.join("files/engine-data");copy_dir(Path::new(&a[1]),&data)?;let cache=root.join("cache");fs::create_dir(&cache)?;let input=cache.join("input.f32");if fs::hard_link(&a[2],&input).is_err(){fs::copy(&a[2],&input)?;}
 let p=film_engine::mobile::MobileParams::parse(&fs::read_to_string(&a[3])?)?;let id=film_engine::mobile::create(data.to_str().unwrap())?;let engine=film_engine::mobile::get(id)?;let out=cache.join("output.f32");let result=film_engine::mobile::export(&engine,0,input.to_str().unwrap(),out.to_str().unwrap(),a[4].parse()?,a[5].parse()?,&p);film_engine::mobile::destroy(id);
 match result{Ok(m)=>{fs::write(&a[7],serde_json::to_vec_pretty(&m)?)?;println!("{}",serde_json::to_string(&m)?);Ok(())},Err(e)=>{fs::write(&a[7],serde_json::to_vec_pretty(&e)?)?;Err(e.into())}}
}
