//! Offline diagnostic, never used by the import path. Does not write a replacement image.
use rawler::{RawImageData, rawsource::RawSource};
use std::path::Path;
fn main() {
    let a: Vec<_> = std::env::args().collect();
    let source = RawSource::new(Path::new(&a[1])).unwrap();
    if a.get(2).is_some_and(|v| v == "--yuv") {
        // Controlled Canon fixture audit only: inspect its raw IFD's JPEG stream.
        let b = source.buf();
        assert_eq!(&b[..2], b"II");
        assert_eq!(&b[8..12], b"CR\x02\0");
        let u16at = |o| u16::from_le_bytes(b[o..o + 2].try_into().unwrap()) as usize;
        let u32at = |o| u32::from_le_bytes(b[o..o + 4].try_into().unwrap()) as usize;
        let ifd = u32at(12);
        let count = u16at(ifd);
        let mut offset = None;
        for i in 0..count {
            let e = ifd + 2 + 12 * i;
            if u16at(e) == 273 {
                offset = Some(u32at(e + 8));
            }
        }
        let ljpeg =
            rawler::decompressors::ljpeg::LjpegDecompressor::new(&b[offset.unwrap()..]).unwrap();
        let w = ljpeg.width();
        let h = ljpeg.height();
        assert!(w * h < 52_000_000);
        let mut pixels = vec![0u16; w * h];
        ljpeg.decode(&mut pixels, 0, w, w, h, false).unwrap();
        let mut samples = vec![Vec::new(); 3];
        for row in (0..h).step_by(ljpeg.super_v() * 8) {
            for col in (0..w).step_by(6 * 8) {
                for c in 0..3 {
                    samples[c].push(pixels[row * w + col + c]);
                }
            }
        }
        let ranges: Vec<_> = samples
            .iter_mut()
            .map(|v| {
                v.sort_unstable();
                [0., 0.01, 0.1, 0.5, 0.9, 0.99, 1.].map(|q| v[((v.len() - 1) as f64 * q) as usize])
            })
            .collect();
        eprintln!(
            "{}",
            serde_json::json!({"stage":"pre_unslice_pre_interpolation_YCbCr","expanded_width":w,"height":h,"sampling":[ljpeg.super_h(),ljpeg.super_v()],"quantiles":ranges})
        );
    }
    let raw = rawler::decode(&source, &Default::default()).unwrap();
    let RawImageData::Integer(data) = &raw.data else {
        panic!("not integer");
    };
    let mut channels = vec![Vec::new(); raw.cpp];
    let step = (raw.width * raw.height / 65536).max(1);
    for pixel in data.chunks_exact(raw.cpp).step_by(step) {
        for c in 0..raw.cpp {
            channels[c].push(pixel[c]);
        }
    }
    let quantiles: Vec<_> = channels
        .iter_mut()
        .map(|v| {
            v.sort_unstable();
            [0., 0.01, 0.1, 0.5, 0.9, 0.99, 1.].map(|q| v[((v.len() - 1) as f64 * q) as usize])
        })
        .collect();
    println!(
        "{}",
        serde_json::json!({"width":raw.width,"height":raw.height,"cpp":raw.cpp,
        "black":raw.blacklevel.as_vec(),"white":raw.whitelevel.as_vec(),"wb":&raw.wb_coeffs[..3],
        "quantiles_0_1_10_50_90_99_100_percent":quantiles,"active":format!("{:?}",raw.active_area),
        "crop":format!("{:?}",raw.crop_area)})
    );
}
