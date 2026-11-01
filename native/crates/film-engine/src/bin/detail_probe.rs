//! Host-only owned API integration probe. Input is immutable linear RGB f32le.
#[cfg(feature = "precision-f64")]
fn main() {
    if let Err(e) = run() {
        eprintln!("{e}");
        std::process::exit(1)
    }
}
#[cfg(not(feature = "precision-f64"))]
fn main() {
    eprintln!("detail_probe requires precision-f64");
    std::process::exit(2)
}
#[cfg(feature = "precision-f64")]
fn run() -> Result<(), Box<dyn std::error::Error>> {
    use film_engine::{
        detail::{CancellationToken, DetailConfig, DetailContext},
        mobile::MobileParams,
    };
    use std::{fs, path::Path, time::Instant};
    let a: Vec<String> = std::env::args().collect();
    if a.len() != 8 {
        return Err(
            "usage: detail_probe DATA INPUT WIDTH HEIGHT PARAMS_JSON OUTPUT_DIR PRIVATE_PARENT"
                .into(),
        );
    }
    let params = MobileParams::parse(&fs::read_to_string(&a[5])?)?;
    let config = DetailConfig {
        width: a[3].parse()?,
        height: a[4].parse()?,
        params,
        source_revision: "cli-input-v1".into(),
        mapping_revision: "oriented-grid-v1".into(),
        data_revision: "bundled-v1".into(),
        memory_budget_bytes: 512 * 1024 * 1024,
        disk_budget_bytes: 2 * 1024 * 1024 * 1024,
        stage_cache_bytes: 16 * 1024 * 1024,
        spectrum_cache_bytes: 64 * 1024 * 1024,
    };
    let plan = DetailContext::preflight(&config)?;
    println!("plan {plan:?}");
    fs::create_dir_all(&a[6])?;
    fs::create_dir_all(&a[7])?;
    let token = CancellationToken::new();
    let t = Instant::now();
    let ctx = DetailContext::build(
        config,
        Path::new(&a[1]),
        Path::new(&a[2]),
        Path::new(&a[7]),
        &token,
    )?;
    println!(
        "context_seconds {} metrics {:?} retained_disk {}",
        t.elapsed().as_secs_f64(),
        ctx.pregrain_metrics(),
        ctx.retained_disk_bytes()
    );
    let nx = (ctx.identity().width as usize + 255) / 256;
    let ny = (ctx.identity().height as usize + 255) / 256;
    for (label, x, y) in [
        ("first", 0, 0),
        ("interior", (nx / 2).min(nx - 1), (ny / 2).min(ny - 1)),
        ("pan", nx - 1, ny - 1),
        ("warm", nx - 1, ny - 1),
        ("return", 0, 0),
    ] {
        if label == "return" {
            ctx.clear_cache()?;
        }
        let t = Instant::now();
        let tile = ctx.render_cell(x, y, &token)?;
        fs::write(
            Path::new(&a[6]).join(format!("{label}.f32")),
            tile.rgb
                .iter()
                .flat_map(|x| x.to_le_bytes())
                .collect::<Vec<_>>(),
        )?;
        println!(
            "tile {label} {:?} {:?} seconds={} stage_bytes={}",
            tile.core_rect,
            tile.sample_rect,
            t.elapsed().as_secs_f64(),
            ctx.cache_bytes()?
        );
    }
    drop(ctx);
    Ok(())
}
