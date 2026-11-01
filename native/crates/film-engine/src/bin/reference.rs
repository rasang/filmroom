use std::{env, fs, path::Path, time::Instant};
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf, precision::from_f64};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = env::args().collect();
    if args.len() != 8 {
        return Err("usage: reference DATA_DIR PARAMS_JSON INPUT_F64LE OUTPUT_F64LE WIDTH HEIGHT METRICS_JSON".into());
    }
    // Invalidate prior success metadata before any fallible work: a failed rerun
    // must never be compared using an old output/metrics pair.
    fs::write(&args[7], "{\"status\":\"running\"}\n")?;
    let data_dir = Path::new(&args[1]);
    let raw_params = fs::read_to_string(&args[2])?;
    let v: serde_json::Value = serde_json::from_str(&raw_params)?;
    let params: RuntimeParams = serde_json::from_str(&raw_params)?;
    let film_stock = v["film_profile"].as_str().filter(|s| !s.is_empty()).ok_or("film_profile must be explicitly specified")?;
    let print_stock = v["print_profile"].as_str().filter(|s| !s.is_empty()).ok_or("print_profile must be explicitly specified")?;
    let width: u32 = args[5].parse()?;
    let height: u32 = args[6].parse()?;
    if width == 0 || height == 0 { return Err("dimensions must be positive".into()); }
    let n = (width as usize).checked_mul(height as usize).and_then(|n| n.checked_mul(3)).ok_or("dimensions overflow")?;
    let bytes = fs::read(&args[3])?;
    let expected_bytes = n.checked_mul(8).ok_or("fixture byte count overflow")?;
    if bytes.len() != expected_bytes { return Err("fixture byte length does not match dimensions".into()); }
    let pixels: Vec<_> = bytes.chunks_exact(8).map(|p| from_f64(f64::from_le_bytes(p.try_into().unwrap()))).collect();
    if !pixels.iter().all(|x| x.is_finite()) { return Err("input contains nonfinite samples".into()); }
    let image = ImageBuf::from_data(width, height, pixels);
    let film = load_profile_by_name(data_dir, film_stock)?;
    let print = load_profile_by_name(data_dir, print_stock)?;
    let start = Instant::now();
    let pipe = Pipeline::new_with_spectral(film, print, params, data_dir)?;
    let setup_ms = start.elapsed().as_secs_f64() * 1000.;
    let start = Instant::now();
    let out = pipe.process(image, &CpuBackend);
    let render_ms = start.elapsed().as_secs_f64() * 1000.;
    if out.width != width || out.height != height || out.data.len() != n {
        return Err("output shape differs from declared reference dimensions".into());
    }
    if !out.data.iter().all(|x| x.is_finite()) { return Err("output contains nonfinite samples".into()); }
    let mut output_bytes = Vec::with_capacity(expected_bytes);
    for &x in &out.data { output_bytes.extend_from_slice(&(x as f64).to_le_bytes()); }
    fs::write(&args[4], output_bytes)?;
    let metrics = serde_json::json!({
        "status":"rendered_not_yet_compared", "backend":"portable-cpu", "precision": if cfg!(feature="precision-f64") {"f64"} else {"f32"},
        "width":width,"height":height,"input":args[3],"output":args[4],
        "setupMs":setup_ms,"renderMs":render_ms,"finite":out.data.iter().all(|x| x.is_finite()),
        "filmStock":film_stock,"paperStock":print_stock,
        "pythonReference":film_engine::PYTHON_REFERENCE,"rustReference":film_engine::RUST_REFERENCE,
        "paramsEffective":pipe.params,
        "note":"Tiny synthetic fixture timing, not Android performance evidence."
    });
    fs::write(&args[7],serde_json::to_string_pretty(&metrics)?)?;
    println!("{}",serde_json::to_string(&metrics)?);
    Ok(())
}
