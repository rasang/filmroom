use super::*;
use rawler::{decoders::Camera, rawimage::{RawImageData, RawPhotometricInterpretation, WhiteLevel}};
use std::io::Cursor;

#[derive(Clone)]
struct Field(u16, u16, u32, Vec<u8>);
fn ascii(tag: u16, s: &str) -> Field { Field(tag, 2, s.len() as u32 + 1, [s.as_bytes(), &[0]].concat()) }
fn short(tag: u16, values: &[u16]) -> Field { Field(tag, 3, values.len() as u32, values.iter().flat_map(|v| v.to_le_bytes()).collect()) }
fn longs(tag: u16, values: &[u32]) -> Field { Field(tag, 4, values.len() as u32, values.iter().flat_map(|v| v.to_le_bytes()).collect()) }
fn rat(tag: u16, n: u32, d: u32) -> Field { Field(tag, 5, 1, [n.to_le_bytes(), d.to_le_bytes()].concat()) }
fn tiff(mut fields: Vec<Field>) -> Vec<u8> {
    fields.sort_by_key(|f| f.0);
    let mut b = vec![0; 8 + 2 + fields.len()*12 + 4];
    b[..8].copy_from_slice(b"II\x2a\0\x08\0\0\0");
    b[8..10].copy_from_slice(&(fields.len() as u16).to_le_bytes());
    for (i, f) in fields.into_iter().enumerate() {
        let at = 10+i*12;
        b[at..at+2].copy_from_slice(&f.0.to_le_bytes());
        b[at+2..at+4].copy_from_slice(&f.1.to_le_bytes());
        b[at+4..at+8].copy_from_slice(&f.2.to_le_bytes());
        if f.3.len() <= 4 { b[at+8..at+8+f.3.len()].copy_from_slice(&f.3); }
        else { let offset = b.len() as u32; b[at+8..at+12].copy_from_slice(&offset.to_le_bytes()); b.extend(f.3); }
    }
    b
}
fn atom(kind: &[u8; 4], payload: &[u8]) -> Vec<u8> {
    [(payload.len() as u32 + 8).to_be_bytes().to_vec(), kind.to_vec(), payload.to_vec()].concat()
}
fn cr3(id: u16, duplicate: bool) -> Vec<u8> {
    let mut settings = vec![0; 49]; settings[3]=7; settings[22..26].copy_from_slice(&[61182,16,16,1]);
    let mut info = vec![0; 71]; info[61]=id; info[20]=199; info[21]=150;
    let mut opaque = ascii(0x95, "RF16mm F2.8 STM"); opaque.3.resize(138, 0xaa); opaque.2=138;
    let cmt1 = atom(b"CMT1", &tiff(vec![ascii(271,"Canon"),ascii(272,"Canon EOS R"),short(274,&[1])]));
    let cmt2 = atom(b"CMT2", &tiff(vec![rat(0x920a,16,1),ascii(0xa434,"RF16mm F2.8 STM")]));
    let cmt3 = atom(b"CMT3", &tiff(vec![short(1,&settings),short(2,&[0,16,0,0]),short(0x93,&info),opaque,
        longs(0x9a,&[0,6720,4480,0,0])]));
    let mut payload = super::super::CANON_DESC.to_vec(); payload.extend(&cmt1); payload.extend(&cmt2); payload.extend(&cmt3);
    if duplicate { payload.extend(&cmt2); }
    [atom(b"ftyp",b"crx \0\0\0\0"),atom(b"moov",&atom(b"uuid",&payload)),atom(b"mdat",&[0])].concat()
}
fn raw(make: &str, model: &str) -> RawImage {
    let camera = Camera { make: make.into(), model: model.into(), clean_make: make.into(), clean_model: model.into(),
        ..Default::default() };
    RawImage::new_with_data(camera, RawImageData::Integer(Vec::new()), 32, 24, 1, [1.0;4],
        RawPhotometricInterpretation::BlackIsZero, None, Some(WhiteLevel::new(vec![65535])), true)
}
fn parsed(bytes: &[u8], format: &str, raw: &RawImage, md: Option<&RawMetadata>) -> CaptureMetadata {
    let mut input = Cursor::new(bytes); let mut r=Reader::new(&mut input, bytes.len() as u64);
    let e=SourceEvidence {format:format.into(), ..Default::default()};
    match source_tags(&mut r,format) { Ok(tags)=>assemble(tags,raw,md,e), Err(f)=>CaptureMetadata::failed(f.status,f.reason,e) }
}
fn fields() -> Vec<Field> {
    vec![ascii(271,"FUJIFILM"),ascii(272,"X-T20"),ascii(0xa433,"FUJIFILM"),ascii(0xa434,"XF35mmF2 R WR"),rat(0x920a,35,1)]
}

#[test]
fn original_identity_does_not_require_aperture_distance_or_database_resolution() {
    let v=parsed(&tiff(fields()),"ARW",&raw("FUJIFILM","X-T20"),None);
    assert_eq!(v.status,CaptureStatus::Ready,"{v:?}");
    let i=v.identity.unwrap(); assert_eq!(i.focal_length_mm,35.0);
    assert_eq!(i.lens_model.as_deref(),Some("XF35mmF2 R WR"));
    assert_eq!(i.provenance,LensIdentityProvenance::OriginalExif);
    assert_eq!((i.aperture,i.focus_range_m,i.mount),(None,None,None));
}

#[test]
fn duplicate_focal_and_lens_name_are_not_resolved_by_first_wins() {
    for f in [rat(0x920a,36,1),ascii(0xa434,"XF23mmF2 R WR")] {
        let mut fields=fields(); fields.push(f);
        assert_eq!(parsed(&tiff(fields),"ARW",&raw("FUJIFILM","X-T20"),None).status,CaptureStatus::Conflict);
    }
}

#[test]
fn invalid_optional_parameters_never_reappear_from_decoded_metadata() {
    let mut f=fields(); f.extend([rat(0x829d,2,1),rat(0x829d,8,1),short(0x9206,&[100])]);
    let md=RawMetadata { make:"FUJIFILM".into(), model:"X-T20".into(), ..Default::default() };
    let mut md=md; md.exif.fnumber=Some(rawler::formats::tiff::Rational{n:2,d:1});
    let v=parsed(&tiff(f),"ARW",&raw("FUJIFILM","X-T20"),Some(&md));
    assert_eq!(v.status,CaptureStatus::Ready,"{v:?}");
    assert!(v.evidence.aperture_invalid && v.evidence.focus_invalid);
    assert_eq!(v.identity.unwrap().aperture,None);
}

#[test]
fn no_actual_focal_is_missing_and_camera_focal_disagreements_are_conflicts() {
    let mut f=fields(); f.retain(|f| f.0!=0x920a);
    assert_eq!(parsed(&tiff(f),"ARW",&raw("FUJIFILM","X-T20"),None).status,CaptureStatus::Missing);
    assert_eq!(parsed(&tiff(fields()),"ARW",&raw("FUJIFILM","X-T4"),None).status,CaptureStatus::Conflict);
    let mut md=RawMetadata{ make:"FUJIFILM".into(),model:"X-T20".into(),..Default::default()};
    md.exif.focal_length=Some(rawler::formats::tiff::Rational{n:36,d:1});
    assert_eq!(parsed(&tiff(fields()),"ARW",&raw("FUJIFILM","X-T20"),Some(&md)).status,CaptureStatus::Conflict);
}

#[test]
fn canon_wrong_rf_subid_cannot_bypass_the_original_admission() {
    let lens=rawler::lens::get_lenses().iter().find(|l| l.identifiers.id==Some((61182,288))).unwrap();
    let md=RawMetadata{make:"Canon".into(),model:"Canon EOS R".into(),lens:Some(lens.clone()),..Default::default()};
    for md in [None,Some(&md)] {
        let good=parsed(&cr3(288,false),"CR3",&raw("Canon","Canon EOS R"),md);
        assert_eq!(good.status,CaptureStatus::Ready,"{good:?}");
        assert_eq!(good.evidence.original_lens_model.as_deref(),Some("RF16mm F2.8 STM"));
        assert_eq!(good.identity.as_ref().unwrap().focus_range_m,Some([1.5,1.99]));
        let wrong=parsed(&cr3(289,false),"CR3",&raw("Canon","Canon EOS R"),md);
        assert_eq!(wrong.status,CaptureStatus::Conflict,"{wrong:?}");
    }
}

#[test]
fn duplicate_cmt_and_budget_failures_never_fall_back_to_database_names() {
    assert_eq!(parsed(&cr3(288,true),"CR3",&raw("Canon","Canon EOS R"),None).status,CaptureStatus::Unsupported);
    let b=tiff(fields()); let mut input=Cursor::new(&b); let mut reader=Reader::new(&mut input,b.len() as u64);
    reader.read_limit=24;
    assert!(source_tags(&mut reader,"ARW").is_err()); assert!(reader.bytes_read<=24);
    let mut b=tiff(fields()); b[8..10].copy_from_slice(&257u16.to_le_bytes());
    assert_eq!(parsed(&b,"ARW",&raw("FUJIFILM","X-T20"),None).status,CaptureStatus::Unsupported);
}

#[test]
fn original_dng_is_evidence_not_automatic_processing_permission() {
    let mut f=vec![ascii(271,"RICOH"),ascii(272,"GR III"),rat(0x920a,183,10),Field(50706,1,4,vec![1,4,0,0])];
    let v=parsed(&tiff(f.clone()),"DNG",&raw("RICOH","GR III"),None);
    assert_eq!(v.status,CaptureStatus::Ready,"{v:?}");
    assert!(v.evidence.is_dng); assert_eq!(v.identity.unwrap().lens_model,None);
    assert_eq!(parsed(&tiff(f.clone()),"PEF",&raw("RICOH","GR III"),None).status,CaptureStatus::Conflict);
    f.retain(|f|f.0!=50706);
    assert_eq!(parsed(&tiff(f),"DNG",&raw("RICOH","GR III"),None).status,CaptureStatus::Conflict);
}

#[test]
fn ambiguous_database_identifier_is_preserved_until_original_name_disambiguates() {
    let db=rawler::lens::get_lenses();
    let pair=db.iter().filter(|l|matches!(l.mount.as_str(),"a-mount"|"e-mount")&&l.identifiers.id.is_some()).find_map(|a| {
        let ra=lens_focal_range(a)?;
        db.iter().filter(|b|b.mount==a.mount&&b.identifiers.id==a.identifiers.id&&!same_database_lens(a,b)).find_map(|b| {
            let rb=lens_focal_range(b)?; let lo=ra[0].max(rb[0]); let hi=ra[1].min(rb[1]);
            (lo<=hi).then_some((a,(lo+hi)/2.0))
        })
    }).expect("test needs a real ambiguous Sony ID in pinned database");
    let (lens,focal)=pair;
    let md=RawMetadata{make:"SONY".into(),model:"identity test".into(),lens:Some(lens.clone()),..Default::default()};
    let mut f=vec![ascii(271,"SONY"),ascii(272,"identity test"),rat(0x920a,(focal*1000.0).round() as u32,1000)];
    let v=parsed(&tiff(f.clone()),"ARW",&raw("SONY","identity test"),Some(&md));
    assert_eq!(v.status,CaptureStatus::Ambiguous,"{v:?}"); assert!(v.evidence.database_candidate_count>1);
    f.push(ascii(0xa434,&lens.lens_model));
    let v=parsed(&tiff(f),"ARW",&raw("SONY","identity test"),Some(&md));
    assert_eq!(v.status,CaptureStatus::Ready,"{v:?}");
}

#[test]
fn explicit_distance_infinity_and_empty_lens_make_are_not_invented() {
    let mut f=fields(); f.retain(|v|v.0!=0xa433); f.extend([ascii(0xa433,""),rat(0x9206,u32::MAX,1)]);
    let v=parsed(&tiff(f),"ARW",&raw("FUJIFILM","X-T20"),None);
    assert_eq!(v.status,CaptureStatus::Ready,"{v:?}");
    assert_eq!(v.identity.unwrap().focus_range_m,Some([f64::INFINITY,f64::INFINITY]));
    assert_eq!(lensfun_mount("MFT-mount").as_deref(),Some("Micro 4/3 System"));
    assert_eq!(lensfun_mount("Z-mount").as_deref(),Some("Nikon Z"));
    assert_eq!(lensfun_mount("F-mount"),None);
}

#[test]
fn explicit_optical_attachment_is_never_reduced_to_a_base_lens() {
    let lens=rawler::lens::get_lenses().iter().find(|l| l.mount=="e-mount"
        && l.lens_model=="E 16-55mm F2.8 G").unwrap();
    let md=RawMetadata{make:"SONY".into(),model:"identity test".into(),lens:Some(lens.clone()),..Default::default()};
    let name=format!("{} + 1.4x Teleconverter",lens.lens_model);
    let f=vec![ascii(271,"SONY"),ascii(272,"identity test"),ascii(0xa434,&name),rat(0x920a,35,1)];
    let v=parsed(&tiff(f.clone()),"ARW",&raw("SONY","identity test"),Some(&md));
    assert_eq!(v.status,CaptureStatus::Unsupported,"{v:?}");
    let v=parsed(&tiff(f),"ARW",&raw("SONY","identity test"),None);
    assert_eq!(v.status,CaptureStatus::Ready,"{v:?}");
    let i=v.identity.unwrap(); assert_eq!(i.lens_model,Some(name));
    assert!(i.lens_aliases.iter().all(|s|has_attachment(s)));
}

#[test]
fn canon_numeric_extender_blocks_base_lens_even_when_focal_is_in_range() {
    let b=cr3(288,false); let mut input=Cursor::new(&b); let mut reader=Reader::new(&mut input,b.len() as u64);
    let mut tags=source_tags(&mut reader,"CR3").unwrap();
    tags.maker.get_mut(&0x93).unwrap().bytes[52..54].copy_from_slice(&5u16.to_le_bytes());
    let e=SourceEvidence{format:"CR3".into(),..Default::default()};
    let blocked=assemble(tags,&raw("Canon","Canon EOS R"),None,e);
    assert_eq!(blocked.status,CaptureStatus::Unsupported,"{blocked:?}");
    assert_eq!(blocked.evidence.canon_extender,Some(5));
}

#[test]
fn supported_tiff_headers_and_raf_exif_offsets_share_the_bounded_reader() {
    for (format, magic) in [("NEF",42u16),("ARW",42),("PEF",42),("ORF",0x4f52),("RW2",0x55)] {
        let mut bytes=tiff(fields()); bytes[2..4].copy_from_slice(&magic.to_le_bytes());
        assert_eq!(parsed(&bytes,format,&raw("FUJIFILM","X-T20"),None).status,CaptureStatus::Ready, "{format}");
    }
    let exif=tiff(fields()); let mut raf=vec![0;140]; raf[..16].copy_from_slice(b"FUJIFILMCCD-RAW ");
    raf[84..88].copy_from_slice(&128u32.to_be_bytes());
    raf[88..92].copy_from_slice(&(exif.len() as u32+12).to_be_bytes()); raf.extend(exif);
    assert_eq!(parsed(&raf,"RAF",&raw("FUJIFILM","X-T20"),None).status,CaptureStatus::Ready);
    raf[84..88].copy_from_slice(&u32::MAX.to_be_bytes());
    assert_eq!(parsed(&raf,"RAF",&raw("FUJIFILM","X-T20"),None).status,CaptureStatus::Unsupported);
}

#[test]
fn short_required_rf_arrays_and_zero_focal_do_not_recover_from_other_sources() {
    let b=cr3(288,false); let mut input=Cursor::new(&b); let mut reader=Reader::new(&mut input,b.len() as u64);
    let mut tags=source_tags(&mut reader,"CR3").unwrap();
    let info=tags.maker.get_mut(&0x93).unwrap(); info.count=61; info.bytes.truncate(122);
    let e=SourceEvidence{format:"CR3".into(),..Default::default()};
    assert_eq!(assemble(tags,&raw("Canon","Canon EOS R"),None,e).status,CaptureStatus::Missing);
    let mut f=fields(); f.retain(|v|v.0!=0x920a); f.push(rat(0x920a,0,1));
    let mut md=RawMetadata{make:"FUJIFILM".into(),model:"X-T20".into(),..Default::default()};
    md.exif.focal_length=Some(rawler::formats::tiff::Rational{n:35,d:1});
    assert_eq!(parsed(&tiff(f),"ARW",&raw("FUJIFILM","X-T20"),Some(&md)).status,CaptureStatus::Missing);
}

#[test]
fn cr2_canon_makernote_offsets_are_relative_to_original_tiff() {
    let lens=rawler::lens::get_lenses().iter().find(|l|l.mount=="ef-mount"&&l.identifiers.id==Some((198,0))).unwrap();
    let mut settings=vec![0;26]; settings[3]=4; settings[22..26].copy_from_slice(&[198,50,50,1]);
    let maker=tiff(vec![short(1,&settings),short(2,&[0,50,0,0]),ascii(0x95,&lens.lens_model)]);
    let mut bytes=tiff(vec![ascii(271,"Canon"),ascii(272,"Canon EOS 50D"),ascii(0xa434,&lens.lens_model),
        rat(0x920a,50,1),Field(0x927c,7,(maker.len()-8) as u32,maker[8..].to_vec())]);
    let root_count=u16::from_le_bytes(bytes[8..10].try_into().unwrap()) as usize;
    let maker_at=(0..root_count).find_map(|i|{
        let at=10+i*12; (u16::from_le_bytes(bytes[at..at+2].try_into().unwrap())==0x927c)
            .then(||u32::from_le_bytes(bytes[at+8..at+12].try_into().unwrap()) as usize)
    }).unwrap();
    let count=u16::from_le_bytes(bytes[maker_at..maker_at+2].try_into().unwrap()) as usize;
    for i in 0..count {
        let at=maker_at+2+i*12;
        let typ=u16::from_le_bytes(bytes[at+2..at+4].try_into().unwrap());
        let count=u32::from_le_bytes(bytes[at+4..at+8].try_into().unwrap()) as usize;
        if count * if typ==3 {2}else{1} > 4 {
            let old=u32::from_le_bytes(bytes[at+8..at+12].try_into().unwrap()) as usize;
            bytes[at+8..at+12].copy_from_slice(&((maker_at+old-8) as u32).to_le_bytes());
        }
    }
    let result=parsed(&bytes,"CR2",&raw("Canon","Canon EOS 50D"),None);
    assert_eq!(result.status,CaptureStatus::Ready,"{result:?}");
    assert_eq!(result.evidence.canon_lens_id,Some([198,0]));
    assert_eq!(result.identity.unwrap().lens_model.as_deref(),Some(lens.lens_model.as_str()));
}

fn dng_fields() -> Vec<Field> {
    vec![ascii(271,"RICOH"),ascii(272,"GR III"),rat(0x920a,183,10),Field(50706,1,4,vec![1,4,0,0])]
}

#[test]
fn original_raw_markers_need_only_presence_even_for_unreadable_giant_payloads() {
    for tag in [50827,50828] {
        // Deliberately impossible length/offset. Marker presence is already
        // sufficient to exclude correction; no payload read should be tried.
        let mut f=dng_fields(); f.push(Field(tag,7,u32::MAX,vec![0xff;4]));
        let v=parsed(&tiff(f),"DNG",&raw("RICOH","GR III"),None);
        assert_eq!(v.status,CaptureStatus::Ready,"{v:?}");
        assert!(v.evidence.converted_dng_marker);
        assert_eq!(v.evidence.original_raw_file_name_present,tag==50827);
        assert_eq!(v.evidence.original_raw_file_data_present,tag==50828);
    }
}

#[test]
fn adobe_private_marker_reads_only_six_bytes_of_large_data() {
    let mut payload=vec![0;64*1024]; payload[..6].copy_from_slice(b"Adobe\0");
    let mut f=dng_fields();f.push(Field(50740,1,payload.len() as u32,payload));
    let bytes=tiff(f);let mut cursor=Cursor::new(&bytes);let mut reader=Reader::new(&mut cursor,bytes.len() as u64);
    reader.read_limit=1024;
    let tags=source_tags(&mut reader,"DNG").unwrap();assert!(reader.bytes_read<1024);
    let e=SourceEvidence{format:"DNG".into(),..Default::default()};
    let result=assemble(tags,&raw("RICOH","GR III"),None,e);
    assert!(result.evidence.adobe_private_data && result.evidence.dng_private_data_present
        && result.evidence.converted_dng_marker);
}

#[test]
fn unreadable_private_signature_never_becomes_a_ready_identity_without_a_marker() {
    for field in [Field(50740,1,u32::MAX,vec![0xff;4]),Field(50740,1,5,b"Adobe".to_vec())] {
        let mut f=dng_fields();f.push(field);
        let result=parsed(&tiff(f),"DNG",&raw("RICOH","GR III"),None);
        assert_eq!(result.status,CaptureStatus::Unsupported,"{result:?}");assert!(result.identity.is_none());
    }
    let mut f=dng_fields();f.push(Field(50740,1,6,b"RICOH\0".to_vec()));
    let result=parsed(&tiff(f),"DNG",&raw("RICOH","GR III"),None);
    assert_eq!(result.status,CaptureStatus::Ready,"{result:?}");
    assert!(result.evidence.dng_private_data_present);assert!(!result.evidence.converted_dng_marker);
}

#[test]
fn conversion_markers_in_subifds_are_not_silently_ignored() {
    let mut f=dng_fields();f.push(longs(330,&[0]));
    let mut bytes=tiff(f);let child_at=bytes.len() as u32;
    let child=tiff(vec![Field(50828,7,u32::MAX,vec![0xff;4])]);bytes.extend_from_slice(&child[8..]);
    let count=u16::from_le_bytes(bytes[8..10].try_into().unwrap()) as usize;
    let pointer=(0..count).map(|i|10+i*12).find(|at|u16::from_le_bytes(bytes[*at..*at+2].try_into().unwrap())==330).unwrap()+8;
    bytes[pointer..pointer+4].copy_from_slice(&child_at.to_le_bytes());
    let result=parsed(&bytes,"DNG",&raw("RICOH","GR III"),None);
    assert_eq!(result.status,CaptureStatus::Ready,"{result:?}");assert!(result.evidence.original_raw_file_data_present);
    bytes[pointer..pointer+4].copy_from_slice(&u32::MAX.to_le_bytes());
    let invalid=parsed(&bytes,"DNG",&raw("RICOH","GR III"),None);
    assert_eq!(invalid.status,CaptureStatus::Unsupported);assert!(invalid.identity.is_none());
}

#[test]
fn incomplete_or_cyclic_marker_walk_is_not_treated_as_marker_absence() {
    let mut bytes=tiff(dng_fields());let count=u16::from_le_bytes(bytes[8..10].try_into().unwrap()) as usize;
    let next=10+count*12;bytes[next..next+4].copy_from_slice(&8u32.to_le_bytes());
    let result=parsed(&bytes,"DNG",&raw("RICOH","GR III"),None);
    assert_eq!(result.status,CaptureStatus::Unsupported);assert!(result.identity.is_none());
    let bytes=tiff(dng_fields());let mut cursor=Cursor::new(&bytes);let mut reader=Reader::new(&mut cursor,bytes.len() as u64);
    // Enough for the first root read, insufficient for complete marker walk.
    reader.read_limit=100;
    assert!(source_tags(&mut reader,"DNG").is_err());assert!(reader.bytes_read<=100);
}
