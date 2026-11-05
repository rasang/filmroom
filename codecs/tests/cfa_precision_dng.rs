use film_codecs::{
    develop::{Budget, admit, decode},
    dng::Dng,
};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};
const W: usize = 34;
const H: usize = 384;
fn sample(x: usize, y: usize) -> u16 {
    (1000 + x * 13 + y * 5 + (x % 2) * 20) as u16
}
fn entropy(bits: &mut Vec<u8>, value: u32, n: usize) {
    for shift in (0..n).rev() {
        bits.push(((value >> shift) & 1) as u8);
    }
}
fn jpeg(precision: u8) -> Vec<u8> {
    jpeg_region(precision, W, H, 0, 0)
}
fn jpeg_region(precision: u8, w: usize, h: usize, x0: usize, y0: usize) -> Vec<u8> {
    let mut b = vec![255, 216, 255, 195, 0, 14, precision];
    b.extend((h as u16).to_be_bytes());
    b.extend((w as u16 / 2).to_be_bytes());
    b.extend([2, 0, 17, 0, 1, 17, 0]);
    b.extend([255, 196, 0, 36, 0]);
    let mut counts = [0u8; 16];
    counts[4] = 17;
    b.extend(counts);
    b.extend(0u8..=16);
    b.extend([
        255, 224, 0, 4, 0, 0, 255, 218, 0, 10, 2, 0, 0, 1, 0, 1, 0, 0,
    ]);
    let mut bits = Vec::new();
    for y in 0..h {
        for x in 0..w {
            let pred = if x < 2 {
                if y == 0 {
                    1i32 << (precision - 1)
                } else {
                    sample(x0 + x, y0 + y - 1) as i32
                }
            } else {
                sample(x0 + x - 2, y0 + y) as i32
            };
            let diff = sample(x0 + x, y0 + y) as i32 - pred;
            let n = (32 - diff.unsigned_abs().leading_zeros()) as usize;
            entropy(&mut bits, n as u32, 5);
            if n > 0 {
                let payload = if diff < 0 {
                    (diff + (1i32 << n) - 1) as u32
                } else {
                    diff as u32
                };
                entropy(&mut bits, payload, n);
            }
        }
    }
    while bits.len() % 8 != 0 {
        bits.push(1);
    }
    for chunk in bits.chunks(8) {
        let byte = chunk.iter().fold(0u8, |v, b| (v << 1) | b);
        b.push(byte);
        if byte == 255 {
            b.push(0);
        }
    }
    b.extend([255, 217]);
    b
}
fn fixture(bits: u8, compressed: bool, orientation: u32, pattern: [u8; 4], big: bool) -> Vec<u8> {
    fixture_options(bits, compressed, orientation, pattern, big, false)
}
fn fixture_options(bits: u8, compressed: bool, orientation: u32, pattern: [u8; 4], big: bool, companded: bool) -> Vec<u8> {
    // Directory endian deliberately varies independently of packed sample order.
    let u16bytes = |n: u16| {
        if big {
            n.to_be_bytes()
        } else {
            n.to_le_bytes()
        }
    };
    let u32bytes = |n: u32| {
        if big {
            n.to_be_bytes()
        } else {
            n.to_le_bytes()
        }
    };
    let mut tags: Vec<(u16, u16, u32, Vec<u8>)> = Vec::new();
    for (tag, n) in [
        (254, 0),
        (256, W as u32),
        (257, H as u32),
        (258, bits as u32),
        (259, if compressed { 7 } else { 1 }),
        (262, 32803),
        (266, 1),
        (273, 0),
        (274, orientation),
        (277, 1),
        (278, H as u32),
        (279, 0),
        (284, 1),
        (50717, 4095),
        (50778, 21),
    ] {
        tags.push((tag, 4, 1, u32bytes(n).to_vec()));
    }
    tags.push((50706, 1, 4, vec![1, 4, 0, 0]));
    tags.push((33422, 1, 4, pattern.to_vec()));
    for (tag, values) in [
        (33421, vec![2u32, 2]),
        (50713, vec![2, 2]),
        (50714, vec![11, 23, 37, 53]),
        (50829, vec![2, 4, H as u32 - 2, W as u32 - 2]),
        (50719, vec![1, 3]),
        (50720, vec![23, H as u32 - 13]),
    ] {
        tags.push((
            tag,
            4,
            values.len() as u32,
            values.into_iter().flat_map(u32bytes).collect(),
        ));
    }
    tags.push((
        50728,
        5,
        3,
        [1u32, 2, 1, 1, 2, 3]
            .into_iter()
            .flat_map(u32bytes)
            .collect(),
    ));
    tags.push((
        50721,
        10,
        9,
        [1u32, 0, 0, 0, 1, 0, 0, 0, 1]
            .into_iter()
            .flat_map(|v| [v, 1].into_iter().flat_map(u32bytes))
            .collect(),
    ));
    if companded && bits == 8 {
        tags.push((50712, 3, 256, (0u16..256).flat_map(|v| u16bytes(v * v / 4)).collect()));
    }
    let data = if compressed {
        jpeg(bits)
    } else if bits == 16 {
        (0..H)
            .flat_map(|y| (0..W).flat_map(move |x| u16bytes(if companded { let v = sample(x,y) & 255; v*v/4 } else { sample(x,y) })))
            .collect()
    } else {
        let stride = (W * bits as usize).div_ceil(8);
        let mut b = vec![0u8; stride * H];
        for y in 0..H {
            for x in 0..W {
                for k in 0..bits as usize {
                    let p = x * bits as usize + k;
                    b[y * stride + p / 8] |=
                        (((sample(x, y) >> (bits as usize - 1 - k)) & 1) as u8) << (7 - p % 8);
                }
            }
        }
        b
    };
    tags.sort_by_key(|v| v.0);
    let base = 8 + 2 + tags.len() * 12 + 4;
    let mut entries: Vec<u8> = Vec::new();
    let mut payload: Vec<u8> = Vec::new();
    for (tag, ty, count, value) in &tags {
        entries.extend(u16bytes(*tag));
        entries.extend(u16bytes(*ty));
        entries.extend(u32bytes(*count));
        if value.len() <= 4 {
            entries.extend(value);
            entries.resize(entries.len() + 4 - value.len(), 0);
        } else {
            entries.extend(u32bytes((base + payload.len()) as u32));
            payload.extend(value);
        }
    }
    let offset = (base + payload.len()) as u32;
    for (i, (tag, _, _, _)) in tags.iter().enumerate() {
        if *tag == 273 {
            entries[i * 12 + 8..i * 12 + 12].copy_from_slice(&u32bytes(offset));
        }
        if *tag == 279 {
            entries[i * 12 + 8..i * 12 + 12].copy_from_slice(&u32bytes(data.len() as u32));
        }
    }
    let mut b = if big {
        vec![77, 77, 0, 42, 0, 0, 0, 8]
    } else {
        vec![73, 73, 42, 0, 8, 0, 0, 0]
    };
    b.extend(u16bytes(tags.len() as u16));
    b.extend(entries);
    b.extend([0; 4]);
    b.extend(payload);
    b.extend(data);
    b
}
fn path(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!("cfa-precision-{}-{name}", std::process::id()))
}
#[test]
fn packed_and_jpeg_match_u16_development_all_patterns_crops_orientations_and_endians() {
    for pattern in [[0, 1, 1, 2], [2, 1, 1, 0], [1, 0, 2, 1], [1, 2, 0, 1]] {
        for orientation in 1..=8 {
            let mut reference = None;
            for (bits, jpeg, big) in [
                (16, false, false),
                (16, false, true),
                (12, false, false),
                (12, false, true),
                (14, false, false),
                (14, false, true),
                (12, true, false),
                (14, true, true),
            ] {
                let input = path("equivalence.dng");
                let output = path("equivalence.f32");
                fs::write(&input, fixture(bits, jpeg, orientation, pattern, big)).unwrap();
                let (probe, admission) =
                    decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).unwrap();
                assert_eq!(
                    (probe.width, probe.height),
                    if orientation >= 5 {
                        ((H - 13) as u32, 23)
                    } else {
                        (23, (H - 13) as u32)
                    }
                );
                assert!(admission.estimated_peak_bytes <= 128 * 1024 * 1024);
                assert_eq!(
                    admission.required_disk_bytes,
                    probe.output_bytes + if jpeg { (W * H * 2) as u64 } else { 0 }
                );
                let actual = fs::read(&output).unwrap();
                if let Some(expected) = &reference {
                    assert_eq!(
                        &actual, expected,
                        "bits={bits} jpeg={jpeg} big={big} orientation={orientation}"
                    );
                } else {
                    reference = Some(actual);
                }
                fs::remove_file(input).unwrap();
                fs::remove_file(output).unwrap();
            }
        }
    }
}
#[test]
fn spool_budget_cancel_corrupt_entropy_and_existing_outputs_fail_closed() {
    let input = path("fault.dng");
    let output = path("fault.f32");
    let good = fixture(14, true, 1, [0, 1, 1, 2], false);
    fs::write(&input, &good).unwrap();
    let d = Dng::read(&mut fs::File::open(&input).unwrap()).unwrap();
    let mut budget = Budget::default();
    budget.disk_budget_bytes = d.probe().output_bytes;
    assert_eq!(admit(&d, &budget).unwrap_err().code, "DISK_BUDGET");
    assert!(decode(&input, &output, &budget, &AtomicBool::new(false)).is_err());
    assert!(!output.exists());
    assert_eq!(
        decode(&input, &output, &Budget::default(), &AtomicBool::new(true))
            .unwrap_err()
            .code,
        "CANCELLED"
    );
    assert!(!output.exists());
    for n in [1usize, 2, 3, 20] {
        fs::write(&input, &good[..good.len() - n]).unwrap();
        assert!(decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).is_err());
        assert!(!output.exists());
    }
    let mut malformed = good.clone();
    let len = malformed.len();
    malformed[len - 1] = 0;
    fs::write(&input, &malformed).unwrap();
    assert!(decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).is_err());
    assert!(!output.exists());
    fs::write(&input, &good).unwrap();
    fs::write(&output, b"keep existing").unwrap();
    assert!(decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).is_err());
    assert_eq!(fs::read(&output).unwrap(), b"keep existing");
    fs::remove_file(input).unwrap();
    fs::remove_file(output).unwrap();
}
#[test]
fn unsupported_fill_order_mono_tiles_and_sensor_limit_rejected_at_probe() {
    let input = path("unsupported.dng");
    for (tag, value, expected) in [
        (266, 2, "UNSUPPORTED_DNG_VARIANT"),
        (262, 34892, "UNSUPPORTED_DNG_VARIANT"),
        (256, 32769, "PIXEL_LIMIT"),
        (257, 1, "PIXEL_LIMIT"),
    ] {
        let mut b = fixture(12, true, 1, [0, 1, 1, 2], false);
        let n = u16::from_le_bytes([b[8], b[9]]) as usize;
        let i = (0..n)
            .find(|&i| u16::from_le_bytes([b[10 + i * 12], b[11 + i * 12]]) == tag)
            .unwrap();
        b[18 + i * 12..22 + i * 12].copy_from_slice(&(value as u32).to_le_bytes());
        fs::write(&input, b).unwrap();
        assert_eq!(
            Dng::read(&mut fs::File::open(&input).unwrap())
                .unwrap_err()
                .code,
            expected
        );
    }
    let mut b = fixture(12, true, 1, [0, 1, 1, 2], false);
    let n = u16::from_le_bytes([b[8], b[9]]) as usize;
    let i = (0..n)
        .find(|&i| u16::from_le_bytes([b[10 + i * 12], b[11 + i * 12]]) == 273)
        .unwrap();
    b[10 + i * 12..12 + i * 12].copy_from_slice(&324u16.to_le_bytes());
    fs::write(&input, b).unwrap();
    assert_eq!(
        Dng::read(&mut fs::File::open(&input).unwrap())
            .unwrap_err()
            .code,
        "UNSUPPORTED_DNG_VARIANT"
    );
    fs::remove_file(input).unwrap();
}
fn tiled_fixture(bits: u8, compressed: bool) -> Vec<u8> {
    let original = fixture(bits, compressed, 6, [1, 0, 2, 1], false);
    let count = u16::from_le_bytes([original[8], original[9]]) as usize;
    let mut tags: Vec<(u16, u16, u32, Vec<u8>)> = Vec::new();
    let (tw, th) = (18usize, 70usize);
    let mut blocks = Vec::new();
    for by in 0..H.div_ceil(th) {
        for bx in 0..W.div_ceil(tw) {
            let data = if compressed {
                jpeg_region(bits, tw, th, bx * tw, by * th)
            } else {
                let stride = (tw * bits as usize).div_ceil(8);
                let mut b = vec![0u8; stride * th];
                for y in 0..th {
                    for x in 0..tw {
                        for k in 0..bits as usize {
                            let p = x * bits as usize + k;
                            b[y * stride + p / 8] |= (((sample(bx * tw + x, by * th + y)
                                >> (bits as usize - 1 - k))
                                & 1) as u8)
                                << (7 - p % 8);
                        }
                    }
                }
                b
            };
            blocks.push(data);
        }
    }
    for i in 0..count {
        let e = &original[10 + i * 12..22 + i * 12];
        let tag = u16::from_le_bytes(e[..2].try_into().unwrap());
        let ty = u16::from_le_bytes(e[2..4].try_into().unwrap());
        let n = u32::from_le_bytes(e[4..8].try_into().unwrap());
        let size = n as usize
            * match ty {
                1 => 1,
                3 => 2,
                4 => 4,
                5 | 10 => 8,
                _ => panic!(),
            };
        let at = if size <= 4 {
            10 + i * 12 + 8
        } else {
            u32::from_le_bytes(e[8..12].try_into().unwrap()) as usize
        };
        let value = original[at..at + size].to_vec();
        match tag {
            273 => tags.push((324, 4, blocks.len() as u32, vec![0; blocks.len() * 4])),
            279 => tags.push((
                325,
                4,
                blocks.len() as u32,
                blocks
                    .iter()
                    .flat_map(|b| (b.len() as u32).to_le_bytes())
                    .collect(),
            )),
            278 => tags.push((323, 4, 1, (th as u32).to_le_bytes().to_vec())),
            _ => tags.push((tag, ty, n, value)),
        }
    }
    tags.push((322, 4, 1, (tw as u32).to_le_bytes().to_vec()));
    tags.sort_by_key(|t| t.0);
    let base = 8 + 2 + tags.len() * 12 + 4;
    let extra: usize = tags
        .iter()
        .filter(|t| t.3.len() > 4)
        .map(|t| t.3.len())
        .sum();
    let mut off = base + extra;
    let offsets: Vec<u8> = blocks
        .iter()
        .flat_map(|b| {
            let here = off;
            off += b.len();
            (here as u32).to_le_bytes()
        })
        .collect();
    tags.iter_mut().find(|t| t.0 == 324).unwrap().3 = offsets;
    let mut entries = Vec::new();
    let mut payload: Vec<u8> = Vec::new();
    for (tag, ty, n, v) in tags {
        entries.extend(tag.to_le_bytes());
        entries.extend(ty.to_le_bytes());
        entries.extend(n.to_le_bytes());
        if v.len() <= 4 {
            entries.extend(&v);
            entries.resize(entries.len() + 4 - v.len(), 0);
        } else {
            entries.extend(((base + payload.len()) as u32).to_le_bytes());
            payload.extend(v);
        }
    }
    let mut b = vec![73, 73, 42, 0, 8, 0, 0, 0];
    b.extend((((base - 14) / 12) as u16).to_le_bytes());
    b.extend(entries);
    b.extend([0; 4]);
    b.extend(payload);
    for block in blocks {
        b.extend(block);
    }
    b
}
#[test]
fn new_precision_tiles_clip_padded_edges_and_preserve_halos() {
    let input = path("tiles.dng");
    let output = path("tiles.f32");
    fs::write(&input, fixture(16, false, 6, [1, 0, 2, 1], false)).unwrap();
    decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).unwrap();
    let expected = fs::read(&output).unwrap();
    fs::remove_file(&output).unwrap();
    for bits in [12, 14] {
        for compressed in [false, true] {
            fs::write(&input, tiled_fixture(bits, compressed)).unwrap();
            let (p, a) =
                decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).unwrap();
            assert!(p.variant.ends_with("TILES"));
            assert_eq!(a.required_disk_bytes, p.output_bytes + (W * H * 2) as u64);
            assert_eq!(fs::read(&output).unwrap(), expected);
            fs::remove_file(&output).unwrap();
        }
    }
    fs::remove_file(input).unwrap();
}
#[test]
fn repeated_tile_offsets_cannot_amplify_padding_work_beyond_limit() {
    let input = path("tile-work-limit.dng");
    for bits in [12, 14] {
        let mut b = tiled_fixture(bits, true);
        let count = u16::from_le_bytes([b[8], b[9]]) as usize;
        let field = |tag| {
            (0..count)
                .find(|&i| u16::from_le_bytes([b[10 + i * 12], b[11 + i * 12]]) == tag)
                .map(|i| 18 + i * 12)
                .unwrap()
        };
        let height_at = field(257);
        let tile_width_at = field(322);
        let tile_height_at = field(323);
        let offsets_at = u32::from_le_bytes(b[field(324)..field(324) + 4].try_into().unwrap()) as usize;
        let counts_at = u32::from_le_bytes(b[field(325)..field(325) + 4].try_into().unwrap()) as usize;
        // The logical sensor is only 571,200 pixels and each tile is below
        // 52 MP, but twelve 32768x1400 padded tiles would decode 550,502,400
        // samples. A single shared compressed extent must not bypass the cap.
        b[height_at..height_at + 4].copy_from_slice(&16_800u32.to_le_bytes());
        b[tile_width_at..tile_width_at + 4].copy_from_slice(&32_768u32.to_le_bytes());
        b[tile_height_at..tile_height_at + 4].copy_from_slice(&1_400u32.to_le_bytes());
        let first_offset = b[offsets_at..offsets_at + 4].to_vec();
        let first_count = b[counts_at..counts_at + 4].to_vec();
        for i in 0..12 {
            b[offsets_at + i * 4..offsets_at + i * 4 + 4].copy_from_slice(&first_offset);
            b[counts_at + i * 4..counts_at + i * 4 + 4].copy_from_slice(&first_count);
        }
        fs::write(&input, b).unwrap();
        assert_eq!(
            Dng::read(&mut fs::File::open(&input).unwrap()).unwrap_err().code,
            "PIXEL_LIMIT"
        );
    }
    fs::remove_file(input).unwrap();
}
#[test]
fn packed_strip_padding_is_not_sensor_data_and_short_extent_is_rejected() {
    let input = path("padding.dng");
    let output = path("padding.f32");
    let base = fixture(12, false, 1, [0, 1, 1, 2], false);
    fs::write(&input, &base).unwrap();
    decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).unwrap();
    let expected = fs::read(&output).unwrap();
    fs::remove_file(&output).unwrap();
    let n = u16::from_le_bytes([base[8], base[9]]) as usize;
    let i = (0..n)
        .find(|&i| u16::from_le_bytes([base[10 + i * 12], base[11 + i * 12]]) == 279)
        .unwrap();
    let at = 18 + i * 12;
    let original = u32::from_le_bytes(base[at..at + 4].try_into().unwrap());
    for pad in [12u32, 224, 4096] {
        let mut b = base.clone();
        b[at..at + 4].copy_from_slice(&(original + pad).to_le_bytes());
        b.extend(std::iter::repeat_n(0xa5, pad as usize));
        fs::write(&input, b).unwrap();
        decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).unwrap();
        assert_eq!(fs::read(&output).unwrap(), expected);
        fs::remove_file(&output).unwrap();
    }
    let mut short = base;
    short[at..at + 4].copy_from_slice(&(original - 1).to_le_bytes());
    fs::write(&input, short).unwrap();
    assert_eq!(
        Dng::read(&mut fs::File::open(&input).unwrap())
            .unwrap_err()
            .code,
        "INVALID_INPUT"
    );
    fs::remove_file(input).unwrap();
}

#[test]
fn byte_lut_cfa_matches_expanded_u16_all_patterns_orientations_and_endians() {
    let input = path("byte-lut.dng");
    let output = path("byte-lut.f32");
    for pattern in [[0,1,1,2],[2,1,1,0],[1,0,2,1],[1,2,0,1]] {
        for orientation in 1..=8 {
            for big in [false,true] {
                let mut expected = None;
                for bits in [16,8] {
                    fs::write(&input,fixture_options(bits,false,orientation,pattern,big,true)).unwrap();
                    let (probe, admission)=decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();
                    if bits==8 {
                        assert_eq!(probe.variant,"DNG_BAYER_U8_LINEARIZED_UNCOMPRESSED_STRIPS");
                        assert_eq!(&fs::read(&output).unwrap(),expected.as_ref().unwrap());
                        assert_eq!(admission.required_disk_bytes,probe.output_bytes);
                    } else {expected=Some(fs::read(&output).unwrap());}
                    fs::remove_file(&output).unwrap();
                }
            }
        }
    }
    fs::remove_file(input).unwrap();
}

#[test]
fn byte_cfa_requires_complete_short_lut_and_explicit_white() {
    let input=path("invalid-byte-lut.dng");
    for (tag, field, replacement) in [(50712,2,4u32),(50712,4,255),(50712,0,65000),(50717,0,65000),(259,8,7)] {
        let mut b=fixture_options(8,false,1,[0,1,1,2],false,true);
        let n=u16::from_le_bytes([b[8],b[9]]) as usize;
        let at=(0..n).find(|&i|u16::from_le_bytes([b[10+i*12],b[11+i*12]])==tag).unwrap()*12+10+field;
        if field==0 || field==2 {b[at..at+2].copy_from_slice(&(replacement as u16).to_le_bytes());}
        else {b[at..at+4].copy_from_slice(&replacement.to_le_bytes());}
        fs::write(&input,b).unwrap();
        assert!(Dng::read(&mut fs::File::open(&input).unwrap()).is_err(),"tag={tag},field={field}");
    }
    fs::remove_file(input).unwrap();
}

#[test]
fn oversized_u16_cfa_jpeg_uses_spool_but_does_not_ignore_invalid_tail() {
    let input=path("u16-large-strip.dng");
    let output=path("u16-large-strip.f32");
    let mut b=fixture(14,true,1,[0,1,1,2],false);
    let n=u16::from_le_bytes([b[8],b[9]]) as usize;
    let at=|tag|18+(0..n).find(|&i|u16::from_le_bytes([b[10+i*12],b[11+i*12]])==tag).unwrap()*12;
    let bits_at=at(258);let count_at=at(279);
    let old=u32::from_le_bytes(b[count_at..count_at+4].try_into().unwrap()) as usize;
    b[bits_at..bits_at+4].copy_from_slice(&16u32.to_le_bytes());
    let count=8*1024*1024+1; // Existing legacy per-block encoded cap.
    b[count_at..count_at+4].copy_from_slice(&(count as u32).to_le_bytes());
    b.resize(b.len()+count-old,0);
    fs::write(&input,b).unwrap();
    let d=Dng::read(&mut fs::File::open(&input).unwrap()).unwrap();
    assert!(d.cfa_spool());assert!(d.streaming_cfa);
    let a=admit(&d,&Budget::default()).unwrap();
    assert_eq!(a.required_disk_bytes,d.probe().output_bytes+(W*H*2)as u64);
    assert_eq!(d.probe().variant,"DNG_BAYER_U16_LOSSLESS_JPEG_STREAM");
    assert!(decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).is_err());
    assert!(!output.exists());fs::remove_file(input).unwrap();
}

fn split_byte_strips(mut b:Vec<u8>,rows:usize)->Vec<u8> {
    let n=u16::from_le_bytes([b[8],b[9]]) as usize;
    let at=|tag|10+(0..n).find(|&i|u16::from_le_bytes([b[10+i*12],b[11+i*12]])==tag).unwrap()*12;
    let offsets=at(273);let counts=at(279);let rows_at=at(278);
    let first=u32::from_le_bytes(b[offsets+8..offsets+12].try_into().unwrap()) as usize;
    let count=H.div_ceil(rows);
    for (tag_at,values) in [(offsets,(0..count).map(|i|(first+i*rows*W)as u32).collect::<Vec<_>>()),(counts,(0..count).map(|i|(rows.min(H-i*rows)*W)as u32).collect::<Vec<_>>())] {
        b[tag_at+4..tag_at+8].copy_from_slice(&(count as u32).to_le_bytes());
        let address=if count==1 {values[0]} else {let address=b.len()as u32;b.extend(values.iter().flat_map(|v|v.to_le_bytes()));address};
        b[tag_at+8..tag_at+12].copy_from_slice(&address.to_le_bytes());
    }
    b[rows_at+8..rows_at+12].copy_from_slice(&(rows as u32).to_le_bytes());b
}
#[test]
fn byte_lut_partial_final_strips_and_failure_cleanup() {
    let input=path("byte-lut-strips.dng");let output=path("byte-lut-strips.f32");
    let base=fixture_options(8,false,1,[0,1,1,2],false,true);
    let mut reference=None;
    for rows in [H,17,1] {
        fs::write(&input,split_byte_strips(base.clone(),rows)).unwrap();
        decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();
        let got=fs::read(&output).unwrap();if let Some(want)=&reference {assert_eq!(&got,want);} else {reference=Some(got);}
        // Never overwrite an existing output, including cancellation requests.
        let original=fs::read(&output).unwrap();
        assert!(decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).is_err());
        assert_eq!(fs::read(&output).unwrap(),original);fs::remove_file(&output).unwrap();
        assert_eq!(decode(&input,&output,&Budget::default(),&AtomicBool::new(true)).unwrap_err().code,"CANCELLED");assert!(!output.exists());
    }
    let mut short=base;short.pop();fs::write(&input,short).unwrap();
    assert!(Dng::read(&mut fs::File::open(&input).unwrap()).is_err());fs::remove_file(input).unwrap();
}

#[test]
fn large_sample_count_u16_sof14_strip_develops_like_uncompressed_control() {
    let input=path("u16-stream-success.dng");let output=path("u16-stream-success.f32");
    let (width,height)=(2048usize,2050usize); // Just beyond 4 Mi-sample legacy cap.
    let mut base=fixture(16,false,1,[0,1,1,2],false);
    let n=u16::from_le_bytes([base[8],base[9]])as usize;
    let at=|tag|18+(0..n).find(|&i|u16::from_le_bytes([base[10+i*12],base[11+i*12]])==tag).unwrap()*12;
    let width_at=at(256);let height_at=at(257);let compression_at=at(259);let offset_at=at(273);let count_at=at(279);let rows_at=at(278);
    // Retain the small shifted active/crop region to test halo coordinates.
    base[width_at..width_at+4].copy_from_slice(&(width as u32).to_le_bytes());
    base[height_at..height_at+4].copy_from_slice(&(height as u32).to_le_bytes());
    base[rows_at..rows_at+4].copy_from_slice(&(height as u32).to_le_bytes());
    let data_at=u32::from_le_bytes(base[offset_at..offset_at+4].try_into().unwrap())as usize;base.truncate(data_at);
    let mut jpeg=jpeg_region(14,2,1,0,0); // Reuse the legal two-component header.
    let sos=jpeg.windows(2).position(|p|p==[255,218]).unwrap();jpeg.truncate(sos+12);
    jpeg[7..9].copy_from_slice(&(height as u16).to_be_bytes());jpeg[9..11].copy_from_slice(&((width/2)as u16).to_be_bytes());
    let bit_count=width*height*5; // All category-zero codes under the 5-bit tree.
    jpeg.extend(std::iter::repeat_n(0u8,bit_count/8));
    if bit_count%8!=0 {jpeg.push((1u8<<(8-bit_count%8))-1);}
    jpeg.extend([255,217]);
    let mut expected=None;
    for compressed in [false,true] {
        let mut b=base.clone();let data=if compressed {jpeg.clone()} else {(0..width*height).flat_map(|_|8192u16.to_le_bytes()).collect()};
        b[compression_at..compression_at+4].copy_from_slice(&(if compressed {7u32}else{1}).to_le_bytes());
        b[count_at..count_at+4].copy_from_slice(&(data.len()as u32).to_le_bytes());b.extend(data);fs::write(&input,b).unwrap();
        let d=Dng::read(&mut fs::File::open(&input).unwrap()).unwrap();assert_eq!(d.streaming_cfa,compressed);
        if compressed {
            let mut budget=Budget::default();budget.disk_budget_bytes=d.probe().output_bytes;
            assert_eq!(admit(&d,&budget).unwrap_err().code,"DISK_BUDGET");
            assert_eq!(decode(&input,&output,&Budget::default(),&AtomicBool::new(true)).unwrap_err().code,"CANCELLED");assert!(!output.exists());
        }
        decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();let got=fs::read(&output).unwrap();
        if let Some(want)=&expected {assert_eq!(&got,want);}else{expected=Some(got);}fs::remove_file(&output).unwrap();
    }
    fs::write(&input,fixture(16,true,1,[0,1,1,2],false)).unwrap();assert!(!Dng::read(&mut fs::File::open(&input).unwrap()).unwrap().streaming_cfa);
    fs::remove_file(input).unwrap();
}
