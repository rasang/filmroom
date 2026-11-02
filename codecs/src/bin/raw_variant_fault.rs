//! Private controlled-fixture cancellation/budget oracle; never used by app routing.
use std::{
    io::Read,
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
fn main() {
    let a: Vec<_> = std::env::args().collect();
    let cancel = Arc::new(AtomicBool::new(false));
    let done = Arc::new(AtomicBool::new(false));
    let c = cancel.clone();
    let stop = done.clone();
    let mode = a[3].clone();
    let output = a[2].clone();
    let thread = std::thread::spawn(move || {
        let start = Instant::now();
        loop {
            if stop.load(Ordering::Relaxed) {
                return false;
            }
            let trigger = if mode == "output" {
                let mut row = [0u8; 128];
                std::fs::File::open(&output)
                    .and_then(|mut f| f.read_exact(&mut row))
                    .is_ok()
                    && row.iter().any(|v| *v != 0)
            } else if let Ok(ms) = mode.parse::<u64>() {
                start.elapsed() >= Duration::from_millis(ms)
            } else {
                false
            };
            if trigger {
                c.store(true, Ordering::Relaxed);
                return true;
            }
            if start.elapsed() > Duration::from_secs(240) {
                return false;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    });
    let mut budget = film_codecs::develop::Budget::default();
    if let Some(v) = a.get(4) {
        budget.memory_budget_mi_b = v.parse().unwrap();
    }
    if let Some(v) = a.get(5) {
        budget.disk_budget_bytes = v.parse().unwrap();
    }
    let fd_before = std::fs::read_dir("/proc/self/fd").map(|v| v.count()).ok();
    let result = film_codecs::raw::decode(Path::new(&a[1]), Path::new(&a[2]), &budget, &cancel);
    done.store(true, Ordering::Relaxed);
    let triggered = thread.join().unwrap();
    let fd_after = std::fs::read_dir("/proc/self/fd").map(|v| v.count()).ok();
    let directory = Path::new(&a[2]).parent().unwrap();
    let leftovers: Vec<_> = std::fs::read_dir(directory)
        .unwrap()
        .filter_map(|e| e.ok())
        .filter(|e| {
            let n = e.file_name();
            let n = n.to_string_lossy();
            n.starts_with(".canon-") || n.starts_with(".film-arw6-")
        })
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .collect();
    println!(
        "{}",
        serde_json::json!({"ok":result.is_ok(),"fd_before":fd_before,"fd_after":fd_after,"error":result.err(),"cancel_triggered":triggered,"partial_output":Path::new(&a[2]).exists(),"named_temps":leftovers})
    );
}
