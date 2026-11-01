use std::{env,fs,path::Path};
fn main()->Result<(),Box<dyn std::error::Error>> {
    let a:Vec<_>=env::args().collect();
    if a.len()!=9{return Err("tile_reference DATA PARAMS INPUT_F32 OUTPUT_F32 WIDTH HEIGHT TILE METRICS".into());}
    fs::write(&a[8],r#"{"status":"running","outputValid":false}"#)?;
    let text=fs::read_to_string(&a[2])?;let v:serde_json::Value=serde_json::from_str(&text)?;
    let p=serde_json::from_str(&text)?;
    let film=v["film_profile"].as_str().ok_or("missing film_profile")?;
    let paper=v["print_profile"].as_str().ok_or("missing print_profile")?;
    let metrics=film_engine::tiled::export_file(Path::new(&a[1]),film,paper,p,Path::new(&a[3]),Path::new(&a[4]),a[5].parse()?,a[6].parse()?,a[7].parse()?,2_097_152,0.,||false)?;
    let mut value=serde_json::to_value(&metrics)?;value["status"]=serde_json::json!("rendered_not_yet_compared");value["outputValid"]=serde_json::json!(true);
    let json=serde_json::to_string_pretty(&value)?;fs::write(&a[8],&json)?;println!("{json}");Ok(())
}
