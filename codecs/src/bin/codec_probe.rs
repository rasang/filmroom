use film_codecs::{develop::Budget, raw::decode};
use std::{path::Path, sync::atomic::AtomicBool};
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let result = if a.len() >= 3 && a[1] == "probe" {
        film_codecs::raw::probe(
            Path::new(&a[2]),
            &Budget::default(),
            a.get(3).is_some_and(|v| v == "metadata"),
        )
        .map(|(p, b)| serde_json::json!({"ok":true,"probe":p,"admission":b}))
    } else if a.len() >= 4 && a[1] == "decode" {
        let mut b = Budget::default();
        if a.len() > 4 {
            b.memory_budget_mi_b = a[4].parse().unwrap_or(128)
        }
        decode(
            Path::new(&a[2]),
            Path::new(&a[3]),
            &b,
            &AtomicBool::new(false),
        )
        .map(|(p, b)| serde_json::json!({"ok":true,"probe":p,"admission":b}))
    } else if a.len() >= 7 && a[1] == "encode" {
        let p = film_codecs::export::ExportParams {
            width: a[5].parse().unwrap(),
            height: a[6].parse().unwrap(),
            format: a[4].clone(),
            quality: Some(95),
            output_color_space: a.get(7).cloned().unwrap_or_else(|| "sRGB".into()),
            budget: Budget::default(),
        };
        film_codecs::export::encode(
            Path::new(&a[2]),
            Path::new(&a[3]),
            &p,
            &AtomicBool::new(false),
        )
        .map(|r| serde_json::json!({"ok":true,"report":r}))
    } else {
        return;
    };
    match result {
        Ok(v) => println!("{}", v),
        Err(e) => {
            println!("{}", serde_json::json!({"ok":false,"error":e}));
            std::process::exit(1)
        }
    }
}
