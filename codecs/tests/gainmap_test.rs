#[path = "support/gainmap_oracle.rs"]
mod gainmap_oracle;
use film_codecs::{
    develop::{Budget, decode, develop_tile},
    dng::Dng,
    gainmap,
};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};
fn payload(w: u32, h: u32, rows: u32, cols: u32, gains: &[f32]) -> Vec<u8> {
    let mut b = Vec::new();
    for x in [0, 0, h, w, 0, 3, 1, 1, rows, cols] {
        b.extend(x.to_be_bytes());
    }
    for x in [
        1. / (rows.max(2) - 1) as f64,
        1. / (cols.max(2) - 1) as f64,
        0.,
        0.,
    ] {
        b.extend(x.to_be_bytes());
    }
    b.extend(3u32.to_be_bytes());
    for x in gains {
        b.extend(x.to_be_bytes());
    }
    b
}
fn record(id: u32, p: &[u8]) -> Vec<u8> {
    let mut b = Vec::new();
    for x in [id, 0x01030000, 0, p.len() as u32] {
        b.extend(x.to_be_bytes());
    }
    b.extend(p);
    b
}
fn list(records: &[Vec<u8>]) -> Vec<u8> {
    let mut b = (records.len() as u32).to_be_bytes().to_vec();
    for r in records {
        b.extend(r);
    }
    b
}
fn warp() -> Vec<u8> {
    let mut b = 3u32.to_be_bytes().to_vec();
    for _ in 0..3 {
        for x in [1f64, 0., 0., 0., 0., 0.] {
            b.extend(x.to_be_bytes());
        }
    }
    for x in [0.5f64, 0.5] {
        b.extend(x.to_be_bytes());
    }
    record(1, &b)
}
#[test]
fn pixel_center_independent_affine_oracle_and_strip_invariance() {
    let (w, h) = (19, 23);
    let mut g = vec![];
    for y in 0..3 {
        for x in 0..4 {
            for c in 0..3 {
                g.push(1. + y as f32 / 2. + x as f32 / 3. + c as f32);
            }
        }
    }
    let maps = gainmap::parse(
        &list(&[record(9, &payload(w, h, 3, 4, &g)), warp()]),
        w as usize,
        h as usize,
    )
    .unwrap()
    .0;
    let mut all = vec![[0.1; 3]; (w * h) as usize];
    gainmap::apply(
        &maps,
        &mut all,
        w as usize,
        h as usize,
        0,
        &AtomicBool::new(false),
    )
    .unwrap();
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                let expected = 0.1
                    * (1. + (y as f64 + 0.5) / h as f64 + (x as f64 + 0.5) / w as f64 + c as f64);
                assert!((all[(y * w + x) as usize][c] as f64 - expected).abs() < 1e-7);
            }
        }
    }
    for strip in [1, 2, 7, 13] {
        let mut other = vec![[0.1; 3]; all.len()];
        for (start, rows) in other.chunks_mut(w as usize * strip).enumerate() {
            gainmap::apply(
                &maps,
                rows,
                w as usize,
                h as usize,
                start * strip,
                &AtomicBool::new(false),
            )
            .unwrap();
        }
        assert_eq!(all, other);
    }
}
#[test]
fn edge_replication_and_single_sample() {
    let mut p = payload(
        2,
        2,
        2,
        2,
        &[2., 3., 4., 4., 5., 6., 6., 7., 8., 8., 9., 10.],
    );
    // Shift origin so all pixels are above/left of map. No extrapolation.
    p[56..64].copy_from_slice(&1f64.to_be_bytes());
    p[64..72].copy_from_slice(&1f64.to_be_bytes());
    let m = gainmap::parse(&list(&[record(9, &p)]), 2, 2).unwrap().0;
    let mut a = vec![[0.1; 3]; 4];
    gainmap::apply(&m, &mut a, 2, 2, 0, &AtomicBool::new(false)).unwrap();
    assert!(a.iter().all(|p| *p == [0.2, 0.3, 0.4]));
    let m = gainmap::parse(
        &list(&[record(9, &payload(2, 2, 1, 1, &[2., 3., 4.]))]),
        2,
        2,
    )
    .unwrap()
    .0;
    let mut b = vec![[0.1; 3]; 4];
    gainmap::apply(&m, &mut b, 2, 2, 0, &AtomicBool::new(false)).unwrap();
    assert_eq!(a, b);
}
#[test]
fn order_clip_and_identity_warp() {
    let m = gainmap::parse(
        &list(&[
            record(9, &payload(2, 2, 1, 1, &[2.; 3])),
            warp(),
            record(9, &payload(2, 2, 1, 1, &[0.5; 3])),
        ]),
        2,
        2,
    )
    .unwrap()
    .0;
    let mut p = vec![[0.8; 3]; 4];
    gainmap::apply(&m, &mut p, 2, 2, 0, &AtomicBool::new(false)).unwrap();
    assert!(p.iter().all(|x| *x == [0.5; 3]));
    let m = gainmap::parse(&list(&[warp()]), 2, 2).unwrap().0;
    assert!(m.is_empty());
}
#[test]
fn malformed_and_unsupported_are_not_ignored() {
    let valid = list(&[record(9, &payload(2, 2, 1, 1, &[1.; 3]))]);
    for n in 0..valid.len() {
        assert!(gainmap::parse(&valid[..n], 2, 2).is_err(), "prefix{n}");
    }
    let mut b = valid.clone();
    b.push(0);
    assert!(gainmap::parse(&b, 2, 2).is_err());
    for (off, word) in [
        (0, 65u32),
        (4, 999),
        (8, 0x01050000),
        (12, 4),
        (20 + 4, 2),
        (20 + 16, 1),
        (20 + 20, 4),
        (20 + 24, 0),
        (20 + 32, u32::MAX),
        (20 + 72, 4),
    ] {
        let mut b = valid.clone();
        b[off..off + 4].copy_from_slice(&word.to_be_bytes());
        assert!(gainmap::parse(&b, 2, 2).is_err(), "offset{off}");
    }
    for v in [f32::NAN, f32::INFINITY, -1., 0., 65.] {
        let mut b = valid.clone();
        b[96..100].copy_from_slice(&v.to_be_bytes());
        assert!(gainmap::parse(&b, 2, 2).is_err());
    }
    for v in [f64::NAN, f64::INFINITY, 0., -1., 1e-300] {
        let mut b = valid.clone();
        b[60..68].copy_from_slice(&v.to_be_bytes());
        assert!(gainmap::parse(&b, 2, 2).is_err());
    }
    let mut w = warp();
    w[20..28].copy_from_slice(&0.999f64.to_be_bytes());
    assert!(gainmap::parse(&list(&[w]), 2, 2).is_err());
    // Mandatory unknown still rejects, even if earlier map would be supported.
    assert!(
        gainmap::parse(
            &list(&[record(9, &payload(2, 2, 1, 1, &[1.; 3])), record(77, &[])]),
            2,
            2
        )
        .is_err()
    );
}
#[test]
fn optional_policy_and_cancel() {
    let mut r = record(77, &[1, 2, 3]);
    r[8..12].copy_from_slice(&1u32.to_be_bytes());
    let (m, skip) = gainmap::parse(&list(&[r]), 2, 2).unwrap();
    assert!(m.is_empty() && skip);
    let m = gainmap::parse(&list(&[record(9, &payload(2, 2, 1, 1, &[1.; 3]))]), 2, 2)
        .unwrap()
        .0;
    let mut p = vec![[0.1; 3]; 4];
    assert_eq!(
        gainmap::apply(&m, &mut p, 2, 2, 0, &AtomicBool::new(true))
            .unwrap_err()
            .code,
        "CANCELLED"
    );
    assert_eq!(p, vec![[0.1; 3]; 4]);
}
fn fixture(n: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(n)
}
fn temp(n: &str) -> PathBuf {
    std::env::temp_dir().join(format!("gainmap-{}-{n}", std::process::id()))
}
/// Append replacement IFD; offsets to original payloads remain exact.
fn with_list(source: &[u8], ops: &[u8]) -> Vec<u8> {
    let mut b = source.to_vec();
    let old = u32::from_le_bytes(b[4..8].try_into().unwrap()) as usize;
    let n = u16::from_le_bytes(b[old..old + 2].try_into().unwrap()) as usize;
    let mut entries: Vec<Vec<u8>> = (0..n)
        .map(|i| b[old + 2 + 12 * i..old + 14 + 12 * i].to_vec())
        .collect();
    entries.retain(|v| u16::from_le_bytes(v[..2].try_into().unwrap()) != 51022);
    let ptr = b.len() as u32;
    b.extend(ops);
    let mut e = 51022u16.to_le_bytes().to_vec();
    e.extend(7u16.to_le_bytes());
    e.extend((ops.len() as u32).to_le_bytes());
    e.extend(ptr.to_le_bytes());
    entries.push(e);
    entries.sort_by_key(|e| u16::from_le_bytes(e[..2].try_into().unwrap()));
    let new = b.len() as u32;
    b.extend((entries.len() as u16).to_le_bytes());
    for e in entries {
        b.extend(e);
    }
    b.extend(0u32.to_le_bytes());
    b[4..8].copy_from_slice(&new.to_le_bytes());
    b
}
#[test]
fn camera_stage_before_calibration_and_whitebalance_once() {
    let input = fixture("RGGB.dng");
    let mut f = fs::File::open(&input).unwrap();
    let mut d = Dng::read(&mut f).unwrap();
    d.matrix = vec![0.7, 0.2, 0.1, 0.1, 0.9, 0.1, 0.05, 0.1, 0.8];
    d.wb = [1.3, 0.9, 1.1, 1.];
    let data = d.read_tile(&mut f, 0, d.height).unwrap();
    let plain = develop_tile(&d, 0, d.height, data.clone()).unwrap();
    // Independent rawler demosaic and subsequent calibration, with known constant
    // camera-plane gains, rather than multiplying already-converted output RGB.
    let mut raw = gainmap_oracle::normalize_standard_black(rawler::decode_file(&input).unwrap());
    raw.color_matrix.clear();
    raw.color_matrix
        .insert(rawler::imgop::xyz::Illuminant::D65, d.matrix.clone());
    raw.wb_coeffs = d.wb;
    let dm = rawler::imgop::develop::RawDevelop {
        steps: vec![rawler::imgop::develop::ProcessingStep::Demosaic],
    };
    let mut camera = match dm.develop_intermediate(&raw).unwrap() {
        rawler::imgop::develop::Intermediate::ThreeColor(p) => p,
        _ => panic!(),
    };
    let gains = [1.2, 0.7, 1.4];
    for p in &mut camera.data {
        for c in 0..3 {
            p[c] = (p[c] * gains[c]).min(1.);
        }
    }
    raw.data = rawler::RawImageData::Float(camera.data.into_iter().flatten().collect());
    raw.cpp = 3;
    raw.photometric = rawler::rawimage::RawPhotometricInterpretation::LinearRaw;
    let calibration = rawler::imgop::develop::RawDevelop {
        steps: vec![
            rawler::imgop::develop::ProcessingStep::WhiteBalance,
            rawler::imgop::develop::ProcessingStep::Calibrate,
        ],
    };
    let reference = match calibration.develop_intermediate(&raw).unwrap() {
        rawler::imgop::develop::Intermediate::ThreeColor(p) => p,
        _ => panic!(),
    };
    d.gain_maps = gainmap::parse(
        &list(&[record(
            9,
            &payload(d.width as u32, d.height as u32, 1, 1, &gains),
        )]),
        d.width,
        d.height,
    )
    .unwrap()
    .0;
    let actual = develop_tile(&d, 0, d.height, data.clone()).unwrap();
    let mut max = 0f32;
    let mut differs = false;
    for ((a, r), p) in actual.data.iter().zip(&reference.data).zip(&plain.data) {
        for c in 0..3 {
            max = max.max((a[c] - r[c]).abs());
            differs |= (a[c] - p[c] * gains[c]).abs() > 1e-4;
        }
    }
    assert!(max < 2e-6, "{max}");
    assert!(differs, "must apply before noncommuting color matrix");
    d.baseline_exposure_ev = 2.;
    let exposed = develop_tile(&d, 0, d.height, data).unwrap();
    for (a, b) in actual.data.iter().zip(exposed.data) {
        for c in 0..3 {
            assert_eq!(b[c], a[c] * 4.);
        }
    }
}
#[test]
fn dng_parser_decode_strips_and_cancel_cleanup() {
    let source = fs::read(fixture("RGGB.dng")).unwrap();
    let d = Dng::read(&mut fs::File::open(fixture("RGGB.dng")).unwrap()).unwrap();
    let mut gains = vec![];
    for y in 0..3 {
        for x in 0..4 {
            for c in 0..3 {
                gains.push(0.6 + y as f32 * 0.1 + x as f32 * 0.1 + c as f32 * 0.1);
            }
        }
    }
    let ops = list(&[
        record(9, &payload(d.width as u32, d.height as u32, 3, 4, &gains)),
        warp(),
    ]);
    let input = temp("strip.dng");
    fs::write(&input, with_list(&source, &ops)).unwrap();
    let mut f = fs::File::open(&input).unwrap();
    let parsed = Dng::read(&mut f).unwrap();
    let data = parsed.read_tile(&mut f, 0, parsed.height).unwrap();
    let full = develop_tile(&parsed, 0, parsed.height, data).unwrap();
    // Explicit strips: integer-MiB admission for this narrow synthetic image
    // otherwise selects the same128-row cap for every budget.
    for strip in [8usize, 19, 63, 128] {
        let mut stitched = Vec::new();
        for first in (0..parsed.height).step_by(strip) {
            let last = (first + strip).min(parsed.height);
            let top = first.saturating_sub(8);
            let end = (last + 8).min(parsed.height);
            let raw = parsed.read_tile(&mut f, top, end - top).unwrap();
            let tile = develop_tile(&parsed, top, end - top, raw).unwrap();
            stitched.extend_from_slice(
                &tile.data[(first - top) * parsed.width..(last - top) * parsed.width],
            );
        }
        assert_eq!(
            stitched, full.data,
            "global coordinates and halo, strip {strip}"
        );
    }
    for mem in [33, 34, 128] {
        let out = temp(&format!("strip-{mem}.f32"));
        let _ = fs::remove_file(&out);
        decode(
            &input,
            &out,
            &Budget {
                raw_batch: film_codecs::raw_batch::RawBatch::Standard,
                memory_budget_mi_b: mem,
                disk_budget_bytes: 64 * 1024 * 1024,
            },
            &AtomicBool::new(false),
        )
        .unwrap();
        let bytes = fs::read(&out).unwrap();
        let mut max = 0f32;
        for (a, b) in full.data.iter().flatten().zip(bytes.chunks_exact(4)) {
            max = max.max((*a - f32::from_le_bytes(b.try_into().unwrap())).abs());
        }
        assert!(max < 2e-6, "mem{mem}:{max}");
        fs::remove_file(out).unwrap();
    }
    let out = temp("cancel.f32");
    let _ = fs::remove_file(&out);
    assert_eq!(
        decode(&input, &out, &Budget::default(), &AtomicBool::new(true))
            .unwrap_err()
            .code,
        "CANCELLED"
    );
    assert!(!out.exists());
    fs::remove_file(input).unwrap();
}

#[test]
fn bounded_map_count_and_dynamic_cancel_in_operation() {
    let record = record(9, &payload(1024, 1024, 1, 1, &[2.; 3]));
    assert!(gainmap::parse(&list(&vec![record.clone(); 5]), 1024, 1024).is_err());
    let maps = gainmap::parse(&list(&[record]), 1024, 1024).unwrap().0;
    let flag = std::sync::Arc::new(AtomicBool::new(false));
    let other = flag.clone();
    let t = std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(10));
        other.store(true, std::sync::atomic::Ordering::Release);
    });
    let mut pixels = vec![[0.1; 3]; 1024 * 1024];
    let result = gainmap::apply(&maps, &mut pixels, 1024, 1024, 0, &flag);
    t.join().unwrap();
    assert_eq!(result.unwrap_err().code, "CANCELLED");
    assert_eq!(*pixels.last().unwrap(), [0.1; 3]);
}

fn area_payload(area: [u32; 8], rows: u32, cols: u32, map_planes: u32, gains: &[f32]) -> Vec<u8> {
    let mut p = payload(area[3], area[2], rows, cols, &[]);
    for (i, value) in area.iter().enumerate() {
        p[i * 4..i * 4 + 4].copy_from_slice(&value.to_be_bytes());
    }
    p[72..76].copy_from_slice(&map_planes.to_be_bytes());
    for value in gains { p.extend(value.to_be_bytes()); }
    p
}

#[test]
fn plane_subset_uses_absolute_sdk_plane_and_replicates_last_map_plane() {
    // Adobe SDK dng_opcode_GainMap::ProcessArea uses min(imagePlane,
    // MapPlanes-1), not a plane index relative to the area Plane field.
    for (first, planes, map_planes, gains, expected) in [
        (1, 1, 3, vec![2., 3., 4.], [0.1, 0.3, 0.1]),
        (1, 2, 2, vec![2., 3.], [0.1, 0.3, 0.3]),
        (0, 3, 1, vec![2.], [0.2, 0.2, 0.2]),
        (2, 1, 1, vec![4.], [0.1, 0.1, 0.4]),
    ] {
        let p = area_payload([0, 0, 2, 3, first, planes, 1, 1], 1, 1, map_planes, &gains);
        let maps = gainmap::parse(&list(&[record(9, &p)]), 3, 2).unwrap().0;
        let mut pixels = vec![[0.1; 3]; 6];
        gainmap::apply(&maps, &mut pixels, 3, 2, 0, &AtomicBool::new(false)).unwrap();
        assert!(pixels.iter().all(|p| *p == expected));
    }
}

#[test]
fn pitched_subarea_affine_oracle_and_non_aligned_tiles() {
    let (w, h) = (13usize, 17usize);
    let area = [1, 2, 16, 12, 1, 2, 3, 2];
    let mut gains = vec![];
    for row in 0..3 {
        for col in 0..4 {
            // Two map planes: R unused, G and B both use the second.
            gains.extend([1., 1. + row as f32 / 2. + col as f32 / 3.]);
        }
    }
    let maps = gainmap::parse(&list(&[record(9, &area_payload(area, 3, 4, 2, &gains))]), w, h).unwrap().0;
    let cancel = AtomicBool::new(false);
    let mut full = vec![[0.1; 3]; w*h];
    gainmap::apply(&maps, &mut full, w, h, 0, &cancel).unwrap();
    for y in 0..h {
        for x in 0..w {
            let active = (1..16).contains(&y) && (2..12).contains(&x)
                && (y-1)%3 == 0 && (x-2)%2 == 0;
            let expected = if active {
                (0.1 * (1. + (y as f64+0.5)/h as f64 + (x as f64+0.5)/w as f64)) as f32
            } else {0.1};
            assert_eq!(full[y*w+x][0], 0.1);
            assert!((full[y*w+x][1] - expected).abs() < 1e-7);
            assert_eq!(full[y*w+x][1], full[y*w+x][2]);
        }
    }
    // Crop the work tiles, not the stage image. Pitch stays anchored to the
    // original opcode area even when tile origins have different remainders.
    let mut stitched = vec![[0.; 3]; w*h];
    for top in (0..h).step_by(4) {
        for left in (0..w).step_by(5) {
            let tw = 5.min(w-left); let th = 4.min(h-top);
            let mut tile = vec![[0.1; 3]; tw*th];
            maps[0].apply_region(&mut tile, w, h, left, top, tw, &cancel).unwrap();
            for row in 0..th {
                stitched[(top+row)*w+left..(top+row)*w+left+tw]
                    .copy_from_slice(&tile[row*tw..(row+1)*tw]);
            }
        }
    }
    assert_eq!(full, stitched);
}

#[test]
fn generalized_map_rejects_empty_or_out_of_bounds_planes_and_zero_pitch() {
    let valid = area_payload([0, 0, 2, 2, 0, 3, 1, 1], 1, 1, 1, &[1.]);
    for (offset, value) in [(16, 3u32), (20, 0), (20, u32::MAX), (24, 0), (28, 0), (72, 0), (72, u32::MAX)] {
        let mut bad = valid.clone();
        bad[offset..offset+4].copy_from_slice(&value.to_be_bytes());
        assert!(gainmap::parse(&list(&[record(9, &bad)]), 2, 2).is_err());
    }
    // Each positive pitch remains legal even if only its first sample falls in
    // the area. Large values do not overflow the modulo-based traversal.
    let p = area_payload([0, 0, 2, 2, 0, 3, u32::MAX, u32::MAX], 1, 1, 1, &[2.]);
    let maps = gainmap::parse(&list(&[record(9, &p)]), 2, 2).unwrap().0;
    let mut pixels = vec![[0.1; 3]; 4];
    gainmap::apply(&maps, &mut pixels, 2, 2, 0, &AtomicBool::new(false)).unwrap();
    assert_eq!(pixels, vec![[0.2;3],[0.1;3],[0.1;3],[0.1;3]]);
}
