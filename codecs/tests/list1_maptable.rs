use film_codecs::{opcodes, dng::Dng, develop::{decode, Budget}};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};
fn payload(area: [u32;8], values: &[u16]) -> Vec<u8> {
    area.into_iter().chain([values.len() as u32]).flat_map(u32::to_be_bytes)
        .chain(values.iter().copied().flat_map(u16::to_be_bytes)).collect()
}
fn record(id:u32,flags:u32,p:&[u8])->Vec<u8> {
    [id,0x01030000,flags,p.len() as u32].into_iter().flat_map(u32::to_be_bytes).chain(p.iter().copied()).collect()
}
fn list(records:&[Vec<u8>])->Vec<u8> {
    (records.len() as u32).to_be_bytes().into_iter().chain(records.iter().flatten().copied()).collect()
}
#[test]
fn integer_table_pitch_bounds_and_full_u16_range() {
    let table:Vec<u16>=(0..=u16::MAX).map(|x|u16::MAX-x).collect();
    let bytes=list(&[record(7,0,&payload([1,2,8,11,0,1,2,3],&table))]);
    let (ops,skipped)=opcodes::parse_stage1(&bytes,13,9).unwrap();assert!(!skipped);
    for y in 0..9 {for x in 0..13 {for sample in [0,1,1234,32768,65535] {
        let applies=(1..8).contains(&y)&&(2..11).contains(&x)&&(y-1)%2==0&&(x-2)%3==0;
        let expected=if applies {65535-sample}else{sample};
        assert_eq!(opcodes::apply_stage1_sample(&ops,sample,x,y).unwrap(),expected);
    }}}
    assert!(opcodes::apply_stage1_sample(&ops,0,13,0).is_err());
    assert!(opcodes::apply_stage1_sample(&ops,0,0,9).is_err());
}
#[test]
fn list_order_and_short_table_tail_match_sdk() {
    let a=record(7,0,&payload([0,0,2,2,0,1,1,1],&[2,3,1,0]));
    let b=record(7,0,&payload([0,0,2,2,0,1,1,1],&[10,20,30,40]));
    let (ops,_)=opcodes::parse_stage1(&list(&[a.clone(),b.clone()]),2,2).unwrap();
    for (i,expected) in [30,40,20,10].into_iter().enumerate() {
        assert_eq!(opcodes::apply_stage1_sample(&ops,i as u16,1,1).unwrap(),expected);
    }
    // First table extends its final value 0; the second then maps 0 to 10.
    assert_eq!(opcodes::apply_stage1_sample(&ops,4,1,1).unwrap(),10);
    assert_eq!(opcodes::apply_stage1_sample(&ops,65535,1,1).unwrap(),10);
    assert!(opcodes::parse_stage1(&list(&vec![a;5]),2,2).is_err());
    let (ops,_)=opcodes::parse_stage1(&list(&[record(7,0,&payload([0,0,0,0,0,1,1,1],&[91]))]),2,2).unwrap();
    assert_eq!(opcodes::apply_stage1_sample(&ops,0,1,1).unwrap(),91);
    let optional=record(7,1,&payload([0,0,2,2,0,1,1,1],&[10]));
    let (ops,skipped)=opcodes::parse_stage1(&list(&[optional]),2,2).unwrap();
    assert!(ops.is_empty()&&skipped);
}
#[test]
fn malformed_required_records_never_skip() {
    let p=payload([0,0,2,2,0,1,1,1],&[0,1,2]);
    let bytes=list(&[record(7,0,&p)]);
    for n in 0..bytes.len() {assert!(opcodes::parse_stage1(&bytes[..n],2,2).is_err(),"{n}");}
    for (offset,value) in [(0,2),(8,0),(16,1),(20,0),(24,0),(28,0),(24,3),(32,0),(32,65537),(32,u32::MAX)] {
        let mut bad=p.clone();bad[offset..offset+4].copy_from_slice(&value.to_be_bytes());
        assert!(opcodes::parse_stage1(&list(&[record(7,0,&bad)]),2,2).is_err(),"{offset}:{value}");
    }
    let mut bad=p.clone();bad.push(0);assert!(opcodes::parse_stage1(&list(&[record(7,0,&bad)]),2,2).is_err());
    assert_eq!(opcodes::parse_stage1(&list(&[record(77,0,&[])]),2,2).unwrap_err().code,"UNSUPPORTED_DNG_OPCODE");
}
fn fixture()->PathBuf {PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/RGGB.dng")}
fn tmp(name:&str)->PathBuf {std::env::temp_dir().join(format!("maptable-{}-{name}",std::process::id()))}
fn with_list(source:&[u8],ops:&[u8])->Vec<u8> {
    let mut b=source.to_vec();let old=u32::from_le_bytes(b[4..8].try_into().unwrap())as usize;
    let n=u16::from_le_bytes(b[old..old+2].try_into().unwrap())as usize;
    let mut entries:Vec<Vec<u8>>=(0..n).map(|i|b[old+2+i*12..old+14+i*12].to_vec()).collect();
    entries.retain(|v|u16::from_le_bytes(v[..2].try_into().unwrap())!=51008);
    let ptr=b.len()as u32;b.extend(ops);
    let mut e=51008u16.to_le_bytes().to_vec();e.extend(7u16.to_le_bytes());e.extend((ops.len()as u32).to_le_bytes());e.extend(ptr.to_le_bytes());entries.push(e);
    entries.sort_by_key(|e|u16::from_le_bytes(e[..2].try_into().unwrap()));
    let start=b.len()as u32;b.extend((entries.len()as u16).to_le_bytes());for e in entries {b.extend(e);}b.extend(0u32.to_le_bytes());b[4..8].copy_from_slice(&start.to_le_bytes());b
}
#[test]
fn full_table_runs_before_black_subtraction_and_required_failure_removes_output() {
    let source=fs::read(fixture()).unwrap();let d=Dng::read(&mut fs::File::open(fixture()).unwrap()).unwrap();
    let area=[0,0,d.height as u32,d.width as u32,0,1,1,1];
    let input=tmp("full.dng");
    fs::write(&input,with_list(&source,&list(&[record(7,0,&payload(area,&vec![1234;65536]))]))).unwrap();
    let mut file=fs::File::open(&input).unwrap();let mapped=Dng::read(&mut file).unwrap();
    for y in [0,8,32] {
        let rows=mapped.read_tile(&mut file,y,8).unwrap();
        for (i,value) in rows.iter().enumerate() {
            let sy=y+i/mapped.active.w;let sx=mapped.active.x+i%mapped.active.w;
            let ch=((sy-mapped.active.y)%2)*2+(sx-mapped.active.x)%2;
            let maximum=mapped.black.iter().map(|v|*v as f64).fold(f64::NEG_INFINITY,f64::max);
            let expected=((1234.-mapped.black[ch] as f64).max(0.)/(mapped.white[0] as f64-maximum)) as f32;
            assert_eq!(*value,expected);
        }
    }
    let short=tmp("short.dng");let output=tmp("short.f32");let _=fs::remove_file(&output);
    fs::write(&short,with_list(&source,&list(&[record(7,0,&payload(area,&[0])),record(77,0,&[])]))).unwrap();
    let e=decode(&short,&output,&Budget::default(),&AtomicBool::new(false)).unwrap_err();
    assert_eq!(e.code,"UNSUPPORTED_DNG_OPCODE");assert!(!output.exists());
    let _=fs::remove_file(input);let _=fs::remove_file(short);
}
