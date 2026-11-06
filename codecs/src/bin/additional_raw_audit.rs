//! Controlled public-fixture audit only. Not callable through application JNI.
use rawler::{RawImageData, rawsource::RawSource};
use std::{
    io::{Seek, SeekFrom, Write},
    path::Path,
};
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let src = RawSource::new(Path::new(&a[1])).unwrap();
    let raw = if a.get(2).is_some_and(|s| s == "--pixels") {
        rawler::decode(&src, &Default::default())
    } else {
        rawler::decode_dummy(&src)
    }
    .unwrap();
    if a.get(2)
        .is_some_and(|s| s == "--cancel-sensor" || s == "--sink-error")
    {
        let decoder = rawler::get_decoder(&src).unwrap();
        let calls = std::cell::Cell::new(0usize);
        let cancel = std::cell::Cell::new(false);
        let sink_error = a[2] == "--sink-error";
        let result = decoder.bounded_bayer_segments(
            &src,
            &mut |_, _, _| {
                calls.set(calls.get() + 1);
                if calls.get() == 2 {
                    if sink_error {
                        return Err("Controlled audit sink failure".into());
                    }
                    cancel.set(true);
                }
                Ok(())
            },
            &|| cancel.get(),
        );
        assert!(result.is_err());
        assert_eq!(calls.get(), 2, "Decoder continued after stop request");
        println!(
            "{}",
            serde_json::json!({"ok":true,"case":a[2],"completed_sensor_segments":calls.get(),"stopped_without_next_segment":true})
        );
        return;
    }
    if a.get(2).is_some_and(|s| s == "--plan") {
        let d = rawler::get_decoder(&src).unwrap();
        eprintln!("{:?}", d.bounded_bayer_plan(&src));
    }
    if a.get(2).is_some_and(|s| s == "--bounded") {
        let d = rawler::get_decoder(&src).unwrap();
        let plan = d.bounded_bayer_plan(&src).unwrap().unwrap();
        let mut file = std::fs::File::create(a.get(3).expect("output")).unwrap();
        file.set_len((plan.width * plan.height * 2) as u64).unwrap();
        let mut next = vec![0usize; plan.height];
        d.bounded_bayer_segments(
            &src,
            &mut |x, y, row| {
                assert!(y < plan.height && x == next[y] && x + row.len() <= plan.width);
                file.seek(SeekFrom::Start(((y * plan.width + x) * 2) as u64))?;
                let bytes: Vec<u8> = row.iter().flat_map(|v| v.to_le_bytes()).collect();
                file.write_all(&bytes)?;
                next[y] += row.len();
                Ok(())
            },
            &|| false,
        )
        .unwrap();
        assert!(next.iter().all(|x| *x == plan.width));
    } else if let Some(out) = a.get(3) {
        let RawImageData::Integer(v) = &raw.data else {
            panic!("not integer")
        };
        let mut f = std::io::BufWriter::new(std::fs::File::create(out).unwrap());
        for x in v {
            f.write_all(&x.to_le_bytes()).unwrap();
        }
    }
    println!(
        "{}",
        serde_json::json!({"width":raw.width,"height":raw.height,"cpp":raw.cpp,"black":raw.blacklevel.as_vec(),"white":raw.whitelevel.as_vec(),"wb":raw.wb_coeffs.iter().map(|x|if x.is_finite(){Some(*x)}else{None}).collect::<Vec<_>>(),"bps":raw.bps,"crop":format!("{:?}",raw.crop_area),"active":format!("{:?}",raw.active_area),"cfa":format!("{:?}",raw.photometric),"orientation":format!("{:?}",raw.orientation),"sourceIfdOffset":raw.source_ifd_offset})
    );
}
