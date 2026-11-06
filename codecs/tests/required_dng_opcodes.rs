use film_codecs::{
    cfa_gainmap,
    develop::{Budget, decode},
    dng::Dng,
    gainmap, opcodes,
};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};
const W: usize = 70;
const H: usize = 384;
fn record(id: u32, payload: &[u8]) -> Vec<u8> {
    [id, 0x01040000, 0, payload.len() as u32]
        .into_iter()
        .flat_map(u32::to_be_bytes)
        .chain(payload.iter().copied())
        .collect()
}
fn list(records: &[Vec<u8>]) -> Vec<u8> {
    (records.len() as u32)
        .to_be_bytes()
        .into_iter()
        .chain(records.iter().flatten().copied())
        .collect()
}
fn vignette() -> Vec<u8> {
    list(&[record(
        3,
        &[
            0.3993975f64,
            0.4299544,
            -0.9918177,
            1.948477,
            -0.9721328,
            0.5,
            0.5,
        ]
        .into_iter()
        .flat_map(f64::to_be_bytes)
        .collect::<Vec<_>>(),
    )])
}
fn gain(area: [u32; 8], planes: u32, value: f32) -> Vec<u8> {
    let mut p: Vec<u8> = area
        .into_iter()
        .chain([2, 2])
        .flat_map(u32::to_be_bytes)
        .collect();
    p.extend([1f64, 1., 0., 0.].into_iter().flat_map(f64::to_be_bytes));
    p.extend(planes.to_be_bytes());
    p.extend((0..4 * planes).flat_map(|_| value.to_be_bytes()));
    record(9, &p)
}
fn warp(planes: u32, coefficient: f64) -> Vec<u8> {
    let mut p = planes.to_be_bytes().to_vec();
    for _ in 0..planes {
        p.extend(
            [coefficient, 0., 0., 0., 0., 0.]
                .into_iter()
                .flat_map(f64::to_be_bytes),
        );
    }
    p.extend([0.5f64, 0.5].into_iter().flat_map(f64::to_be_bytes));
    record(1, &p)
}
fn sample(x: usize, y: usize) -> u16 {
    (1600 + x * 7 + y * 3) as u16
}
fn fixture(ops: &[(u16, Vec<u8>)], materialize: bool, bits: u32, orientation: u32) -> Vec<u8> {
    fixture_color(ops,materialize,bits,orientation,false)
}
fn fixture_color(ops: &[(u16, Vec<u8>)], materialize: bool, bits: u32, orientation: u32,color:bool) -> Vec<u8> {
    let mut tags = Vec::<(u16, u16, u32, Vec<u8>)>::new();
    for (tag, value) in [
        (254, 0),
        (256, W as u32),
        (257, H as u32),
        (258, bits),
        (259, 1),
        (262, 32803),
        (273, 0),
        (274, orientation),
        (277, 1),
        (278, H as u32),
        (279, 0),
        (50717, 4095),
        (50778, 21),
    ] {
        tags.push((tag, 4, 1, value.to_le_bytes().to_vec()));
    }
    tags.push((50706, 1, 4, vec![1, 4, 0, 0]));
    tags.push((33422, 1, 4, vec![0, 1, 1, 2]));
    tags.push((271, 2, 16, b"Generic fixture\0".to_vec()));
    for (tag, values) in [
        (33421, vec![2u32, 2]),
        (50713, vec![2, 2]),
        (50714, vec![100, 120, 140, 160]),
        (50829, vec![2, 4, H as u32 - 2, W as u32 - 2]),
        (50719, vec![1, 3]),
        (50720, vec![59, H as u32 - 12]),
    ] {
        tags.push((
            tag,
            4,
            values.len() as u32,
            values.into_iter().flat_map(u32::to_le_bytes).collect(),
        ));
    }
    tags.push((
        50728,
        5,
        3,
        [1u32, 2, 1, 1, 2, 3]
            .into_iter()
            .flat_map(u32::to_le_bytes)
            .collect(),
    ));
    tags.push((
        50721,
        10,
        9,
        [1u32, 0, 0, 0, 1, 0, 0, 0, 1]
            .into_iter()
            .flat_map(|v| [v, 1])
            .flat_map(u32::to_le_bytes)
            .collect(),
    ));
    for (tag, value) in ops {
        tags.push((*tag, 7, value.len() as u32, value.clone()));
    }
    if color {
        tags.retain(|t|![50721,50728,50778].contains(&t.0));
        for (tag,values) in [(50721,[12587i32,-5232,-1496,-3610,10841,277,-911,1674,2070]),(50722,[8389,-3198,-1019,-3834,10222,661,-1122,1900,3415])] {
            tags.push((tag,10,9,values.into_iter().flat_map(|v|[v.to_le_bytes(),10000i32.to_le_bytes()].concat()).collect()));
        }
        tags.push((50728,5,3,[1024u32,2090,1024,1024,1024,1524].into_iter().flat_map(u32::to_le_bytes).collect()));
        tags.push((50778,3,1,17u16.to_le_bytes().to_vec()));tags.push((50779,3,1,23u16.to_le_bytes().to_vec()));
    }
    tags.sort_by_key(|t| t.0);
    let mut b = vec![b'I', b'I', 42, 0, 8, 0, 0, 0];
    b.extend((tags.len() as u16).to_le_bytes());
    b.resize(8 + 2 + tags.len() * 12 + 4, 0);
    let mut offset_entry = 0;
    let mut count_entry = 0;
    for (i, (tag, typ, count, p)) in tags.into_iter().enumerate() {
        let entry = 10 + i * 12;
        b[entry..entry + 2].copy_from_slice(&tag.to_le_bytes());
        b[entry + 2..entry + 4].copy_from_slice(&typ.to_le_bytes());
        b[entry + 4..entry + 8].copy_from_slice(&count.to_le_bytes());
        if p.len() <= 4 {
            b[entry + 8..entry + 8 + p.len()].copy_from_slice(&p);
        } else {
            let offset = b.len() as u32;
            b[entry + 8..entry + 12].copy_from_slice(&offset.to_le_bytes());
            b.extend(p);
        }
        if tag == 273 {
            offset_entry = entry + 8;
        }
        if tag == 279 {
            count_entry = entry + 8;
        }
    }
    let op = opcodes::parse_stage1(&vignette(), W, H).unwrap().0;
    let stride = (W * bits as usize).div_ceil(8);
    let mut pixels = vec![0; stride * H];
    for y in 0..H {
        for x in 0..W {
            let v = if materialize {
                opcodes::apply_stage1_sample(&op, sample(x, y), x, y).unwrap()
            } else {
                sample(x, y)
            };
            if bits == 16 {
                pixels[y * stride + x * 2..y * stride + x * 2 + 2]
                    .copy_from_slice(&v.to_le_bytes());
            } else {
                for k in 0..bits as usize {
                    let bit = x * bits as usize + k;
                    pixels[y * stride + bit / 8] |=
                        (((v >> (bits as usize - 1 - k)) & 1) as u8) << (7 - bit % 8);
                }
            }
        }
    }
    let offset = b.len() as u32;
    b[offset_entry..offset_entry + 4].copy_from_slice(&offset.to_le_bytes());
    b[count_entry..count_entry + 4].copy_from_slice(&(pixels.len() as u32).to_le_bytes());
    b.extend(pixels);
    b
}
fn path(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!("required-opcode-{}-{name}", std::process::id()))
}
#[test]
fn stage1_integer_order_crop_orientation_and_batch_equivalence() {
    for (bits, orientation) in [(12, 1), (16, 6)] {
        let a = path(&format!("{bits}-ops.dng"));
        let b = path(&format!("{bits}-materialized.dng"));
        let ao = path(&format!("{bits}-ops.f32"));
        let bo = path(&format!("{bits}-reference.f32"));
        fs::write(
            &a,
            fixture(&[(51008, vignette())], false, bits, orientation),
        )
        .unwrap();
        fs::write(&b, fixture(&[], true, 16, orientation)).unwrap();
        let d = Dng::read(&mut fs::File::open(&a).unwrap()).unwrap();
        assert_eq!(d.stage1_opcodes.len(), 1);
        for (input, output) in [(&a, &ao), (&b, &bo)] {
            decode(input, output, &Budget::default(), &AtomicBool::new(false)).unwrap();
        }
        assert_eq!(fs::read(&ao).unwrap(), fs::read(&bo).unwrap());
        let mut file = fs::File::open(&a).unwrap();
        assert_eq!(
            d.read_tile_cancellable(&mut file, d.active.y, 8, &AtomicBool::new(true))
                .unwrap_err()
                .code,
            "CANCELLED"
        );
        for p in [a, b, ao, bo] {
            fs::remove_file(p).unwrap();
        }
    }
}
#[test]
fn unit_gain_is_executed_with_partial_area_and_stage_coordinates() {
    let p = path("stage2.dng");
    let aw = W - 6;
    let ah = H - 4;
    let op = list(&[gain(
        [3, 5, ah as u32 - 3, aw as u32 - 5, 0, 1, 2, 3],
        1,
        1.,
    )]);
    fs::write(&p, fixture(&[(51009, op)], false, 12, 1)).unwrap();
    let d = Dng::read(&mut fs::File::open(&p).unwrap()).unwrap();
    let maps = d.cfa_gain_maps;
    let mut whole = vec![1.5; aw * ah];
    cfa_gainmap::apply_region(&maps, &mut whole, aw, ah, 0, 0, aw, &AtomicBool::new(false))
        .unwrap();
    for y in 0..ah {
        for x in 0..aw {
            let applies = (3..ah - 3).contains(&y)
                && (5..aw - 5).contains(&x)
                && (y - 3) % 2 == 0
                && (x - 5) % 3 == 0;
            assert_eq!(whole[y * aw + x], if applies { 1. } else { 1.5 });
        }
    }
    let mut region = vec![1.5; 19 * 29];
    cfa_gainmap::apply_region(
        &maps,
        &mut region,
        aw,
        ah,
        7,
        9,
        19,
        &AtomicBool::new(false),
    )
    .unwrap();
    for y in 0..29 {
        assert_eq!(
            &region[y * 19..(y + 1) * 19],
            &whole[(y + 9) * aw + 7..(y + 9) * aw + 26]
        );
    }
    fs::remove_file(p).unwrap();
}
#[test]
fn strict_warp_identity_version_preview_flags_and_rectangular_list3() {
    for planes in [1, 3] {
        assert!(
            gainmap::parse(&list(&[warp(planes, 1.)]), 20, 24)
                .unwrap()
                .0
                .is_empty()
        );
    }
    assert!(gainmap::parse(&list(&[warp(3, 1.0000001)]), 20, 24).is_err());
    let mut map = gain([2, 3, 20, 17, 0, 3, 1, 1], 3, 1.);
    map[8..12].copy_from_slice(&2u32.to_be_bytes()); // Preview-only is still required for our full-quality output.
    let maps = gainmap::parse(&list(&[warp(1, 1.), map]), 20, 24)
        .unwrap()
        .0;
    let mut data = vec![[1.5; 3]; 480];
    gainmap::apply(&maps, &mut data, 20, 24, 0, &AtomicBool::new(false)).unwrap();
    for y in 0..24 {
        for x in 0..20 {
            assert_eq!(
                data[y * 20 + x],
                [if (2..20).contains(&y) && (3..17).contains(&x) {
                    1.
                } else {
                    1.5
                }; 3]
            );
        }
    }
}
#[test]
fn required_unknown_badpixel_nonidentity_and_resource_boundaries_failclosed() {
    for record in [record(4, &[0; 7]), warp(1, 1.1), record(77, &[])] {
        assert!(opcodes::parse_stage1(&list(&[record]), W, H).is_err());
    }
    let raw = vignette();
    let op = raw[4..].to_vec();
    assert!(opcodes::parse_stage1(&list(&vec![op; 5]), W, H).is_err());
    let op = gain([0, 0, 24, 20, 0, 1, 1, 1], 1, 1.);
    assert!(cfa_gainmap::parse(&list(&vec![op; 5]), 20, 24).is_err());
    let mut unsupported = vignette();
    unsupported[8..12].copy_from_slice(&0x01050000u32.to_be_bytes());
    assert!(opcodes::parse_stage1(&unsupported, W, H).is_err());
    let p = path("cancel.dng");
    let o = path("cancel.f32");
    fs::write(&p, fixture(&[(51008, vignette())], false, 12, 1)).unwrap();
    assert_eq!(
        decode(&p, &o, &Budget::default(), &AtomicBool::new(true))
            .unwrap_err()
            .code,
        "CANCELLED"
    );
    assert!(!o.exists());
    fs::remove_file(p).unwrap();
}

#[test]
fn required_badpixel_neighborhood_matches_materialized_sensor_and_strips() {
    let marker=sample(12,130);
    let payload=[u32::from(marker),0].into_iter().flat_map(u32::to_be_bytes).collect::<Vec<_>>();
    let op=list(&[record(4,&payload)]);
    assert!(opcodes::parse_stage1(&list(&[record(4,&payload),record(4,&payload)]),W,H).is_err());
    let input=path("badpixel-neighbor.dng");
    fs::write(&input,fixture(&[(51008,op)],false,16,1)).unwrap();
    let mut f=fs::File::open(&input).unwrap();let d=Dng::read(&mut f).unwrap();
    let full=d.read_tile(&mut f,d.active.y,d.active.h).unwrap();
    let repair=film_codecs::dng_bad_pixels::BadPixelsConstant::parse(&payload).unwrap();
    for y in d.active.y..d.active.y+d.active.h {for x in d.active.x..d.active.x+d.active.w {
        let raw=repair.sample(W,H,x,y,|xx,yy|Ok(sample(xx,yy))).unwrap();
        let ch=(y%2)*2+x%2;
        let expected=(raw as f32-d.black[ch]).max(0.)/(d.white[ch]-d.black[ch]);
        assert_eq!(full[(y-d.active.y)*d.active.w+x-d.active.x],expected);
    }}
    for y in (d.active.y..d.active.y+d.active.h).step_by(7) {
        let h=7.min(d.active.y+d.active.h-y);let tile=d.read_tile(&mut f,y,h).unwrap();
        assert_eq!(tile,&full[(y-d.active.y)*d.active.w..(y-d.active.y+h)*d.active.w]);
    }
    fs::remove_file(input).unwrap();
}
#[test]
fn required_warp_disk_admission_and_batch_crop_orientation_equivalence() {
    for orientation in [1,6] {
        let input=path(&format!("warp-{orientation}.dng"));
        fs::write(&input,fixture(&[(51022,list(&[warp(3,0.97)]))],false,16,orientation)).unwrap();
        let d=Dng::read(&mut fs::File::open(&input).unwrap()).unwrap();assert!(d.warp.is_some());
        let mut budget=Budget::default();let a=film_codecs::develop::admit(&d,&budget).unwrap();
        assert_eq!(a.required_disk_bytes,d.probe().output_bytes+(d.active.w*d.active.h*12)as u64);
        let reject=path(&format!("warp-{orientation}-reject.rgb"));budget.disk_budget_bytes=a.required_disk_bytes-1;
        assert_eq!(decode(&input,&reject,&budget,&AtomicBool::new(false)).unwrap_err().code,"DISK_BUDGET");assert!(!reject.exists());
        budget=Budget::default();let one=path(&format!("warp-{orientation}-one.rgb"));let two=path(&format!("warp-{orientation}-two.rgb"));
        decode(&input,&one,&budget,&AtomicBool::new(false)).unwrap();
        budget.raw_batch=film_codecs::raw_batch::RawBatch::Small;
        decode(&input,&two,&budget,&AtomicBool::new(false)).unwrap();
        let output=fs::read(&one).unwrap();assert_eq!(output.len()as u64,d.probe().output_bytes);assert_eq!(output,fs::read(&two).unwrap());
        assert!(output.chunks_exact(4).all(|b|f32::from_le_bytes(b.try_into().unwrap()).is_finite()));
        for p in [input,one,two]{fs::remove_file(p).unwrap();}
    }
}
#[test]
#[ignore="Real public Q2 input and output paths must be supplied explicitly"]
fn real_q2_required_operations_complete() {
    let input=PathBuf::from(std::env::var("FILM_Q2_SOURCE").unwrap());
    let output=PathBuf::from(std::env::var("FILM_Q2_OUTPUT").unwrap());
    let mut budget=Budget::default();budget.disk_budget_bytes=2*1024*1024*1024;
    let (probe,admission)=decode(&input,&output,&budget,&AtomicBool::new(false)).unwrap();
    println!("Q2 {}x{} estimated_peak={} disk={} rows={}",probe.width,probe.height,admission.estimated_peak_bytes,admission.required_disk_bytes,admission.strip_rows);
    assert_eq!(probe.width,8368);assert_eq!(probe.height,5584);
    assert_eq!(fs::metadata(&output).unwrap().len(),probe.output_bytes);
}

#[test]
fn new_color_route_executes_and_rejects_unimplemented_required_mix() {
    for with_warp in [false,true] {
        let input=path(&format!("dual-color-{with_warp}.dng"));let output=path(&format!("dual-color-{with_warp}.rgb"));
        let ops=if with_warp {vec![(51022,list(&[warp(3,0.97)]))]}else{vec![]};
        fs::write(&input,fixture_color(&ops,false,16,1,true)).unwrap();
        let d=Dng::read(&mut fs::File::open(&input).unwrap()).unwrap();assert!(d.cfa_color.is_some());assert!(d.matrix.is_empty());
        decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();
        assert!(fs::read(&output).unwrap().chunks_exact(4).all(|b|f32::from_le_bytes(b.try_into().unwrap()).is_finite()));
        for p in [input,output]{fs::remove_file(p).unwrap();}
    }
    let input=path("dual-color-required-gain.dng");
    fs::write(&input,fixture_color(&[(51022,list(&[gain([0,0,(H-4)as u32,(W-6)as u32,0,3,1,1],3,1.2)]))],false,16,1,true)).unwrap();
    assert_eq!(Dng::read(&mut fs::File::open(&input).unwrap()).unwrap_err().code,"UNSUPPORTED_DNG_OPCODE");fs::remove_file(input).unwrap();
}

#[test]
fn asymmetric_warp_nonzero_active_origin_all_orientations() {
    let mut payload=3u32.to_be_bytes().to_vec();
    for c in [[0.97f64,0.02,0.,0.,0.002,-0.001],[0.98,-0.01,0.,0.,0.,0.],[0.99,0.,0.005,0.,-0.001,0.003]] {
        payload.extend(c.into_iter().flat_map(f64::to_be_bytes));
    }
    payload.extend([0.43f64,0.57].into_iter().flat_map(f64::to_be_bytes));
    let mut canonical=Vec::new();let w=59usize;let h=H-12;
    for orientation in 1..=8 {
        let input=path(&format!("asymmetric-{orientation}.dng"));let output=path(&format!("asymmetric-{orientation}.rgb"));
        fs::write(&input,fixture(&[(51022,list(&[record(1,&payload)]))],false,16,orientation)).unwrap();
        decode(&input,&output,&Budget::default(),&AtomicBool::new(false)).unwrap();let result=fs::read(&output).unwrap();
        if orientation==1 {canonical=result.clone();}
        let ow=if orientation>=5 {h}else{w};
        for y in 0..h {for x in 0..w {
            let (xx,yy)=match orientation {1=>(x,y),2=>(w-1-x,y),3=>(w-1-x,h-1-y),4=>(x,h-1-y),5=>(y,x),6=>(h-1-y,x),7=>(h-1-y,w-1-x),8=>(y,w-1-x),_=>unreachable!()};
            assert_eq!(&canonical[(y*w+x)*12..(y*w+x+1)*12],&result[(yy*ow+xx)*12..(yy*ow+xx+1)*12]);
        }}
        for p in [input,output]{fs::remove_file(p).unwrap();}
    }
}
