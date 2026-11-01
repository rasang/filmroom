//! Emits the authoritative native defaults for cross-language contract checks.
fn main(){println!("{}",serde_json::to_string_pretty(&film_engine::mobile::MobileParams::default()).unwrap());}
