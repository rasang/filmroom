//! Offline public-fixture audit, never an application import fallback.
//! Emits the ACTUAL bounded adapter's normalized active-area samples for an
//! independent LibRaw comparison, and records the exact metadata contract.
use film_codecs::{
    develop::{Budget, admit},
    dng::Dng,
};
use std::{
    fs::{File, OpenOptions},
    io::Write,
    path::Path,
    sync::atomic::AtomicBool,
};
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let mut input = File::open(&a[1]).unwrap();
    let d = Dng::read(&mut input).unwrap();
    assert_eq!(d.cpp, 1);
    if a.get(3).is_some_and(|v| v == "--scan-only") {
        let mut decoded_rows = 0usize;
        let result = film_codecs::dng::cfa_storage::rows(
            &mut input,
            d.offsets[0] as u64,
            d.counts[0] as u64,
            d.width,
            d.height,
            d.sample_bits,
            &AtomicBool::new(false),
            |_, _| {
                decoded_rows += 1;
                Ok(())
            },
        );
        println!(
            "{}",
            serde_json::json!({"rows":decoded_rows,"height":d.height,"result":result.err().map(|e|e.code)})
        );
        return;
    }
    let admission = admit(&d, &Budget::default()).unwrap();
    let cancel = AtomicBool::new(false);
    let mut storage = d.clone();
    if d.cfa_spool() {
        input = film_codecs::dng::cfa_storage::decode_to_spool(
            &d,
            &mut input,
            Path::new(&a[2]),
            &cancel,
        )
        .unwrap();
        storage.compression = 1;
        storage.sample_bits = 16;
        storage.endian = film_codecs::tiff::Endian::Le;
        storage.offsets = vec![0];
        storage.counts = vec![(d.width * d.height * 2) as u32];
        storage.rows_per_strip = d.height;
        storage.tile_height = d.height;
    }
    let mut out = std::io::BufWriter::new(
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&a[2])
            .unwrap(),
    );
    for y in (d.active.y..d.active.y + d.active.h).step_by(64) {
        let h = 64.min(d.active.y + d.active.h - y);
        let data = storage
            .read_tile_cancellable(&mut input, y, h, &cancel)
            .unwrap();
        for value in data {
            out.write_all(&value.to_le_bytes()).unwrap();
        }
    }
    out.flush().unwrap();
    println!(
        "{}",
        serde_json::json!({"probe":d.probe(),"admission":admission,"black":d.black,"white":d.white,"wb":d.wb,"matrix":d.matrix,"cfa":d.cfa,"sampleBits":d.sample_bits})
    );
}
