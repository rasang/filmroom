//! Independent test-only List2 oracle. Does not call production TIFF/GainMap
//! parsing or application. Sensor decoding and final development use
//! pinned Rawler; map interpolation independently follows the DNG SDK scalar precision order.
use rawler::{
    RawImage, RawImageData,
    imgop::{Dim2, Point, Rect},
};
use std::{
    collections::{BTreeSet, VecDeque},
    fs,
    path::Path,
};
struct Map {
    area: [usize; 8],
    rows: usize,
    cols: usize,
    spacing: [f64; 2],
    origin: [f64; 2],
    gains: Vec<f64>,
}
fn maps(path: &Path) -> Vec<Map> {
    let b = fs::read(path).unwrap();
    let be = &b[..2] == b"MM";
    let u16at = |p: usize| {
        if be {
            u16::from_be_bytes(b[p..p + 2].try_into().unwrap())
        } else {
            u16::from_le_bytes(b[p..p + 2].try_into().unwrap())
        }
    };
    let u32at = |p: usize| {
        if be {
            u32::from_be_bytes(b[p..p + 4].try_into().unwrap())
        } else {
            u32::from_le_bytes(b[p..p + 4].try_into().unwrap())
        }
    };
    let first = u32at(4) as usize;
    let mut queue = VecDeque::from([first]);
    let mut seen = BTreeSet::new();
    let mut root_list = None;
    let mut raw_list = None;
    while let Some(off) = queue.pop_front() {
        if off == 0 || !seen.insert(off) {
            continue;
        }
        assert!(seen.len() < 32);
        let n = u16at(off) as usize;
        let mut photo = 0;
        let mut list = None;
        for i in 0..n {
            let p = off + 2 + i * 12;
            let tag = u16at(p);
            let ty = u16at(p + 2);
            let count = u32at(p + 4) as usize;
            let size = count
                * match ty {
                    1 | 2 | 7 => 1,
                    3 => 2,
                    4 => 4,
                    5 | 10 => 8,
                    11 => 4,
                    12 => 8,
                    _ => continue,
                };
            let data = if size <= 4 {
                p + 8
            } else {
                u32at(p + 8) as usize
            };
            assert!(data + size <= b.len());
            if tag == 262 {
                photo = if ty == 3 {
                    u16at(data) as u32
                } else {
                    u32at(data)
                };
            }
            if tag == 330 {
                assert_eq!(ty, 4);
                for j in 0..count {
                    queue.push_back(u32at(data + j * 4) as usize);
                }
            }
            if tag == 51009 {
                assert!(ty == 1 || ty == 7);
                list = Some(b[data..data + size].to_vec());
            }
        }
        if off == first {
            root_list = list.clone();
        }
        if photo == 32803 {
            raw_list = list;
        }
        queue.push_back(u32at(off + 2 + n * 12) as usize);
    }
    let Some(bytes) = raw_list.or(root_list) else {
        return vec![];
    };
    let n = u32::from_be_bytes(bytes[..4].try_into().unwrap()) as usize;
    let mut at = 4;
    let mut result = Vec::new();
    for _ in 0..n {
        let id = u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap());
        let flags = u32::from_be_bytes(bytes[at + 8..at + 12].try_into().unwrap());
        let size = u32::from_be_bytes(bytes[at + 12..at + 16].try_into().unwrap()) as usize;
        at += 16;
        let p = &bytes[at..at + size];
        at += size;
        if id != 9 {
            assert_ne!(flags & 1, 0);
            continue;
        }
        let u = |o: usize| u32::from_be_bytes(p[o..o + 4].try_into().unwrap()) as usize;
        let d = |o: usize| f64::from_be_bytes(p[o..o + 8].try_into().unwrap());
        let area = std::array::from_fn(|i| u(i * 4));
        let rows = u(32);
        let cols = u(36);
        assert_eq!(area[4..6], [0, 1]);
        assert_eq!(u(72), 1);
        assert_eq!(size, 76 + rows * cols * 4);
        result.push(Map {
            area,
            rows,
            cols,
            spacing: [d(40), d(48)],
            origin: [d(56), d(64)],
            gains: p[76..]
                .chunks_exact(4)
                .map(|v| f32::from_be_bytes(v.try_into().unwrap()) as f64)
                .collect(),
        });
    }
    assert_eq!(at, bytes.len());
    result
}
/// Independent scalar normalization for these no-Delta fixtures. Rawler's
/// apply_scaling uses a separate denominator for each CFA phase, contrary to
/// DNG 1.7.1 linearization: the maximum black is per sample plane (one for CFA).
/// Spatial Delta fixtures are independently covered by the SDK comparison and
/// dng_spatial_black; this helper intentionally does not use BlackModel.
pub fn normalize_standard_black(mut raw: RawImage) -> RawImage {
    let black = &raw.blacklevel;
    assert_eq!(black.cpp, raw.cpp);
    let base: Vec<f64> = black.levels.iter().map(|v| v.n as f64 / v.d as f64).collect();
    let white = raw.whitelevel.as_vec();
    let active = raw.active_area.unwrap_or(Rect {
        p: Point { x: 0, y: 0 },
        d: Dim2 { w: raw.width, h: raw.height },
    });
    let mut maximum = vec![f64::NEG_INFINITY; raw.cpp];
    // Exhaustive active-image scan is intentionally unlike the production
    // residue-class maximum algorithm, so it remains an independent oracle.
    for y in 0..active.d.h {
        for x in 0..active.d.w {
            for c in 0..raw.cpp {
                let b = base[((y % black.height) * black.width + x % black.width) * raw.cpp + c];
                maximum[c] = maximum[c].max(b);
            }
        }
    }
    let uniform = base.chunks_exact(raw.cpp).all(|b| b == &base[..raw.cpp]);
    let mut pixels = raw.data.as_f32().into_owned();
    for y in 0..active.d.h {
        for x in 0..active.d.w {
            for c in 0..raw.cpp {
                let b = base[((y % black.height) * black.width + x % black.width) * raw.cpp + c];
                let w = white[if white.len() == 1 { 0 } else { c }];
                let i = ((y + active.p.y) * raw.width + x + active.p.x) * raw.cpp + c;
                pixels[i] = if uniform {
                    (pixels[i] - b as f32).max(0.) / (w - b as f32)
                } else {
                    ((pixels[i] as f64 - b).max(0.) / (w as f64 - maximum[c])) as f32
                };
            }
        }
    }
    raw.data = RawImageData::Float(pixels);
    for b in &mut raw.blacklevel.levels { b.n = 0; b.d = 1; }
    raw.whitelevel.0.fill(1);
    raw
}

pub fn prepare(path: &Path) -> RawImage {
    let tables = maps(path);
    let mut raw = normalize_standard_black(rawler::decode_file(path).unwrap());
    assert_eq!(raw.cpp, 1);
    let active = raw.active_area.unwrap_or(Rect {
        p: Point { x: 0, y: 0 },
        d: Dim2 {
            w: raw.width,
            h: raw.height,
        },
    });
    let RawImageData::Float(pixels) = &mut raw.data else {
        panic!("expected normalized float sensor")
    };
    for table in tables {
        for y in (table.area[0]..table.area[2]).step_by(table.area[6]) {
            for x in (table.area[1]..table.area[3]).step_by(table.area[7]) {
                assert!(x < active.d.w && y < active.d.h);
                let column = (((x as f64 + 0.5) / active.d.w as f64 - table.origin[1])
                    / table.spacing[1])
                    .clamp(0., (table.cols - 1) as f64);
                let row = (((y as f64 + 0.5) / active.d.h as f64 - table.origin[0])
                    / table.spacing[0])
                    .clamp(0., (table.rows - 1) as f64);
                let c0 = column.floor() as usize;
                let r0 = row.floor() as usize;
                let c1 = (c0 + 1).min(table.cols - 1);
                let r1 = (r0 + 1).min(table.rows - 1);
                let fx = column - c0 as f64;
                let fy = (row - r0 as f64) as f32;
                let value = |r, c| table.gains[r * table.cols + c];
                // SDK dng_gain_map_interpolator::InterpolateEntry uses real32
                // vertical interpolation, then ResetColumn uses real64 for
                // horizontal interpolation and casts its result to real32.
                // Preserving these rounding points matters: a one-ULP sensor
                // change can change PPG's directional decision discontinuously.
                let left = value(r0, c0) as f32 * (1. - fy) + value(r1, c0) as f32 * fy;
                let right = value(r0, c1) as f32 * (1. - fy) + value(r1, c1) as f32 * fy;
                let gain = (left as f64 + (right as f64 - left as f64) * fx) as f32;
                let index = (active.p.y + y) * raw.width + active.p.x + x;
                pixels[index] = (pixels[index].clamp(0., 1.) * gain).min(1.);
            }
        }
    }
    raw
}
