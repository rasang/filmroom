use film_codecs::{
    develop::Budget,
    export::{ExportParams, encode, srgb},
};
use std::{fs, io::Write, path::PathBuf, sync::atomic::AtomicBool};
fn temp(s: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("codec-export-{}-{s}", std::process::id()));
    let _ = fs::remove_file(&p);
    p
}
fn input(name: &str) -> PathBuf {
    let p = temp(name);
    let mut f = fs::File::create(&p).unwrap();
    for _ in 0..(128 * 128) {
        for v in [0.18f32, 0.4, 0.7] {
            f.write_all(&v.to_le_bytes()).unwrap();
        }
    }
    p
}
fn params(format: &str) -> ExportParams {
    ExportParams {
        width: 128,
        height: 128,
        format: format.into(),
        quality: Some(95),
        output_color_space: "sRGB".into(),
        budget: Budget::default(),
    }
}
#[test]
fn tiff16_numeric_roundtrip_and_icc() {
    let i = input("tiff-input");
    let o = temp("out.tif");
    let r = encode(&i, &o, &params("TIFF16"), &AtomicBool::new(false)).unwrap();
    assert_eq!(r.bit_depth, 16);
    let mut d = ::tiff::decoder::Decoder::new(fs::File::open(&o).unwrap()).unwrap();
    assert_eq!(d.dimensions().unwrap(), (128, 128));
    let icc = d.get_tag_u8_vec(::tiff::tags::Tag::IccProfile).unwrap();
    assert!(moxcms::ColorProfile::new_from_slice(&icc).is_ok());
    let data = match d.read_image().unwrap() {
        ::tiff::decoder::DecodingResult::U16(p) => p,
        _ => panic!(),
    };
    for p in data.chunks_exact(3) {
        for (a, b) in p.iter().zip([0.18, 0.4, 0.7]) {
            assert!((*a as f32 / 65535. - srgb(b)).abs() < 2. / 65535.);
        }
    }
    fs::remove_file(i).unwrap();
    fs::remove_file(o).unwrap();
}
#[test]
fn jpeg_is_baseline_dimensions_color_and_icc() {
    let i = input("jpeg-input");
    let o = temp("out.jpg");
    encode(&i, &o, &params("JPEG"), &AtomicBool::new(false)).unwrap();
    let bytes = fs::read(&o).unwrap();
    assert!(bytes.windows(2).any(|x| x == [0xff, 0xc0]));
    assert!(!bytes.windows(2).any(|x| x == [0xff, 0xc2]));
    assert!(bytes.windows(12).any(|x| x == b"ICC_PROFILE\0"));
    let image = rawler_image_decode(&bytes);
    assert_eq!(image.len(), 128 * 128 * 3);
    for p in image.chunks_exact(3) {
        for (a, b) in p.iter().zip([0.18, 0.4, 0.7]) {
            assert!((*a as f32 - srgb(b) * 255.).abs() < 4.);
        }
    }
    fs::remove_file(i).unwrap();
    fs::remove_file(o).unwrap();
}
fn rawler_image_decode(bytes: &[u8]) -> Vec<u8> {
    image::load_from_memory_with_format(bytes, image::ImageFormat::Jpeg)
        .unwrap()
        .to_rgb8()
        .into_raw()
}
#[test]
fn cancelled_invalid_float_and_exact_input_length_delete_output() {
    let i = input("bad-input");
    let o = temp("bad-output");
    assert_eq!(
        encode(&i, &o, &params("JPEG"), &AtomicBool::new(true))
            .unwrap_err()
            .code,
        "CANCELLED"
    );
    assert!(!o.exists());
    let mut b = fs::read(&i).unwrap();
    b[0..4].copy_from_slice(&f32::NAN.to_le_bytes());
    fs::write(&i, &b).unwrap();
    for format in ["JPEG", "TIFF16"] {
        assert_eq!(
            encode(&i, &o, &params(format), &AtomicBool::new(false))
                .unwrap_err()
                .code,
            "INVALID_INPUT"
        );
        assert!(!o.exists());
    }
    fs::write(&i, &b[..b.len() - 4]).unwrap();
    assert_eq!(
        encode(&i, &o, &params("JPEG"), &AtomicBool::new(false))
            .unwrap_err()
            .code,
        "INVALID_INPUT"
    );
    assert!(!o.exists());
    fs::remove_file(i).unwrap();
}
#[test]
fn json_params_exact() {
    let p:ExportParams=serde_json::from_str(r#"{"width":128,"height":128,"format":"JPEG","quality":94,"memoryBudgetMiB":128,"diskBudgetBytes":1073741824}"#).unwrap();
    assert_eq!(p.quality, Some(94));
}

#[test]
fn cancel_inflight_jpeg_and_tiff_removes_partial_preserves_input() {
    use std::{
        io::Read,
        sync::{Arc, atomic::Ordering},
        thread,
        time::{Duration, Instant},
    };
    let input = temp("midcancel-input");
    let f = fs::File::create(&input).unwrap();
    f.set_len(2048 * 4096 * 12).unwrap();
    drop(f);
    for format in ["JPEG", "TIFF16"] {
        let output = temp(&format!("midcancel-{format}"));
        let cancelled = Arc::new(AtomicBool::new(false));
        let (i, o, c) = (input.clone(), output.clone(), cancelled.clone());
        let mut p = params(format);
        p.width = 2048;
        p.height = 4096;
        let job = thread::spawn(move || encode(&i, &o, &p, &c));
        let deadline = Instant::now() + Duration::from_secs(20);
        while fs::metadata(&output)
            .map(|m| m.len() < 4096)
            .unwrap_or(true)
            && !job.is_finished()
            && Instant::now() < deadline
        {
            thread::sleep(Duration::from_millis(1));
        }
        assert!(
            fs::metadata(&output)
                .map(|m| m.len() >= 4096)
                .unwrap_or(false),
            "encoder did not start writing"
        );
        cancelled.store(true, Ordering::Release);
        assert_eq!(job.join().unwrap().unwrap_err().code, "CANCELLED");
        assert!(!output.exists());
    }
    assert_eq!(fs::metadata(&input).unwrap().len(), 2048 * 4096 * 12);
    let mut f = fs::File::open(&input).unwrap();
    let mut block = [0u8; 65536];
    loop {
        let n = f.read(&mut block).unwrap();
        if n == 0 {
            break;
        }
        assert!(block[..n].iter().all(|b| *b == 0));
    }
    fs::remove_file(input).unwrap();
}

#[test]
fn export_only_84mp_limit_keeps_import_52mp_and_checks_budgets_before_io() {
    assert_eq!(film_codecs::MAX_PIXELS, 52_000_000);
    assert!(film_codecs::validate_dimensions(5311, 9791).is_err()); // 52,000,001
    let missing=temp("never-created");let output=temp("limit-reject.jpg");
    for format in ["JPEG","TIFF16"] {
        let mut p=params(format);p.width=12000;p.height=7001;
        assert_eq!(encode(&missing,&output,&p,&AtomicBool::new(false)).unwrap_err().code,"PIXEL_LIMIT");
        p.width=u32::MAX;p.height=u32::MAX;
        assert_eq!(encode(&missing,&output,&p,&AtomicBool::new(false)).unwrap_err().code,"PIXEL_LIMIT");
        p.width=12000;p.height=7000;p.budget.memory_budget_mi_b=1;
        assert_eq!(encode(&missing,&output,&p,&AtomicBool::new(false)).unwrap_err().code,"MEMORY_BUDGET");
        p.budget.memory_budget_mi_b=128;p.budget.disk_budget_bytes=1;
        assert_eq!(encode(&missing,&output,&p,&AtomicBool::new(false)).unwrap_err().code,"DISK_BUDGET");
        assert!(!output.exists());
    }
}
