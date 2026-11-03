//! Panasonic RW2 container admission; no extension or per-model dispatch.
use super::*;
pub(super) fn sensor(t:&Tiff)->Result<&crate::tiff::Ifd> {
    let mut candidates=t.ifds.iter().filter(|i|i.contains_key(&280));
    let sensor=candidates.next().ok_or_else(unsupported)?;
    if candidates.next().is_some() {return Err(unsupported());}
    let version=uint(sensor,45,None)?;
    if !matches!(version,3..=8) {return Err(unsupported());}
    let width=uint(sensor,2,None)?;let height=uint(sensor,3,None)?;
    crate::validate_dimensions(width,height)?;
    if width<8 || height<8 || width%2!=0 || height%2!=0 || uint(sensor,8,Some(1))?!=1
        // Newer RW2 omits legacy compression tag 11; RawFormat remains authoritative.
        || uint(sensor,11,Some(34316))?!=34316 || uint(sensor,289,Some(0))?==65536 {
        return Err(unsupported());
    }
    let bits=uint(sensor,10,None)?;
    // Length/layout validation is shared with the decoder, before either dummy or pixels.
    // CF2 validates its typed entropy/strip plan inside metadata-only rawler decode.
    if version==8 {
        if !matches!(bits,12|14|16) {return Err(unsupported());}
        let offsets=sensor.get(&280).ok_or_else(unsupported)?.uints()?;
        if offsets.len()!=1 || offsets[0]<8 || u64::from(offsets[0])>=t.size {return Err(err("INVALID_INPUT","probe"));}
        return Ok(sensor);
    }
    let needed=rawler::decoders::rw2::bounded_payload_bytes(width as usize,height as usize,version as u16,bits)
        .map_err(|_|unsupported())?;
    let offsets=sensor.get(&280).ok_or_else(unsupported)?.uints()?;
    if offsets.len()!=1 || offsets[0]<8 || u64::from(offsets[0]).checked_add(needed as u64).is_none_or(|n|n>t.size) {
        return Err(err("INVALID_INPUT","probe"));
    }
    Ok(sensor)
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn checked_geometry_rejects_unsupported_and_extreme() {
        use rawler::decoders::rw2::bounded_payload_bytes as plan;
        assert!(plan(8448,5632,6,14).is_ok());
        assert!(plan(usize::MAX,2,6,14).is_err());
        assert!(plan(0,2,6,14).is_err());
        assert!(plan(100,100,5,14).is_err());
        assert!(plan(6008,4008,8,14).is_err());
        assert!(plan(6048,4016,7,12).is_err());
        assert!(plan(6072,4016,6,16).is_err());
        assert!(plan(6074,4016,6,14).is_err());
    }
    #[test] fn budget_charges_input_sensor_and_tile_without_padding_clone() {
        // Same validated 47MP S1R sensor/crop; no whole-f32-RGB allocation.
        let d=Development { width:8448,height:5632,cpp:1,orientation:1,
            active:Rect{x:12,y:24,w:8368,h:5584},crop:Rect{x:12,y:24,w:8368,h:5584},
            cfa:"GBRG".into(),wb:[1.;4],matrix:vec![1.,0.,0.,0.,1.,0.,0.,0.,1.],
            baseline_exposure_ev:0.,gain_maps:&[] };
        let c=CameraRaw {he:None,development:d,format:"RW2",input_bytes:69873664,jpeg_blocks:vec![],decoder_extra_bytes:0,source_ifd_offset:24,arw1_offset:None,arw6:None};
        let b=Budget {memory_budget_mi_b:256,..Default::default()};
        let a=c.admit(&b).unwrap();assert!(a.estimated_peak_bytes<224*MIB);
        assert!(a.estimated_peak_bytes>=BASE+69873664+8448*5632*2);
        assert!(a.estimated_peak_bytes>=BASE+8448*5632*2+8368*(a.strip_rows as u64+16)*96);
        assert!(c.admit(&Budget::default()).is_err());
    }
}
