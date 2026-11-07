//! Content-identified RAW facade. DNG retains its existing decoder and policy.
use crate::{
    camera_raw::CameraRaw,
    develop::{Admission, Budget},
    dng::{Dng, Probe},
    error::{Result, io_error},
};
use std::{fs::File, path::Path, sync::atomic::AtomicBool};
fn is_dng(input: &Path) -> Result<bool> {
    use crate::error::err;
    use std::io::{Read, Seek, SeekFrom};
    let mut f = File::open(input).map_err(|e| io_error(e, "probe"))?;
    let mut h = [0; 8];
    f.read_exact(&mut h).map_err(|e| io_error(e, "probe"))?;
    if h == *b"FUJIFILM" { return Ok(false); }
    if crate::camera_raw::additional::orf_magic(&h){return Ok(false);}
    // CR3 has a BMFF container; full box/track validation follows before decoding.
    if &h[4..8]==b"ftyp" { return Ok(false); }
    let e = match &h[..2] {
        b"II" => crate::tiff::Endian::Le,
        b"MM" => crate::tiff::Endian::Be,
        _ => return Err(err("UNSUPPORTED_FORMAT", "probe")),
    };
    let is_rw2 = &h[..4] == b"II\x55\0";
    if e.u16(&h[2..]) != 42 && !is_rw2 {
        return Err(err("UNSUPPORTED_FORMAT", "probe"));
    }
    let at = e.u32(&h[4..]);
    if at < 8 {
        return Err(err("INVALID_INPUT", "probe"));
    }
    if is_rw2 { return Ok(false); }
    f.seek(SeekFrom::Start(at as u64))
        .map_err(|e| io_error(e, "probe"))?;
    let mut count = [0; 2];
    f.read_exact(&mut count).map_err(|e| io_error(e, "probe"))?;
    let count = e.u16(&count) as usize;
    if count > 256 {
        return Err(err("INVALID_INPUT", "probe"));
    }
    let mut entries = vec![0; count * 12];
    f.read_exact(&mut entries)
        .map_err(|e| io_error(e, "probe"))?;
    Ok(entries.chunks_exact(12).any(|entry| e.u16(entry) == 50706))
}
pub fn probe(input: &Path, b: &Budget, metadata_only: bool) -> Result<(Probe, Option<Admission>)> {
    if is_dng(input)? {
        let d = Dng::read(&mut File::open(input).map_err(|e| io_error(e, "probe"))?)?;
        let admission = if metadata_only {
            None
        } else {
            Some(crate::develop::admit(&d, b)?)
        };
        Ok((d.probe(), admission))
    } else if let Some(d)=crate::camera_raw::fuji::FujiRaw::read(input)? {
        let admission=if metadata_only {None}else{Some(d.admit(b)?)};
        Ok((d.probe(),admission))
    } else if let Some(d)=crate::camera_raw::additional::AdditionalRaw::read(input)? {
        let admission=if metadata_only {None}else{Some(d.admit(b)?)};
        Ok((d.probe(),admission))
    } else if let Some(d) = crate::nikon_snef::Snef::read(input)? {
        let admission=if metadata_only {None}else{Some(d.admit(b)?)};
        Ok((d.probe(),admission))
    } else {
        let d = CameraRaw::read(input)?;
        let admission = if metadata_only {
            None
        } else {
            Some(d.admit(b)?)
        };
        Ok((d.probe(), admission))
    }
}
pub fn decode(
    input: &Path,
    output: &Path,
    b: &Budget,
    cancel: &AtomicBool,
) -> Result<(Probe, Admission)> {
    if cancel.load(std::sync::atomic::Ordering::Relaxed) {
        return Err(crate::error::err("CANCELLED", "probe"));
    }
    if is_dng(input)? {
        crate::develop::decode(input, output, b, cancel)
    } else if let Some(d)=crate::camera_raw::fuji::FujiRaw::read(input)? {
        d.decode(output,b,cancel)
    } else if let Some(d)=crate::camera_raw::additional::AdditionalRaw::read(input)? {
        d.decode(output,b,cancel)
    } else if let Some(d) = crate::nikon_snef::Snef::read(input)? {
        d.decode(output,b,cancel)
    } else {
        crate::camera_raw::decode(input, output, b, cancel)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dng_facade_preserves_probe_and_pixels() {
        let input = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/RGGB.dng");
        let d = Dng::read(&mut File::open(&input).unwrap()).unwrap();
        let (p, _) = probe(&input, &Budget::default(), false).unwrap();
        assert_eq!(
            serde_json::to_value(p).unwrap(),
            serde_json::to_value(d.probe()).unwrap()
        );
        let a = std::env::temp_dir().join(format!("camera-facade-{}-a", std::process::id()));
        let b = std::env::temp_dir().join(format!("camera-facade-{}-b", std::process::id()));
        let _ = std::fs::remove_file(&a);
        let _ = std::fs::remove_file(&b);
        let cancel = AtomicBool::new(false);
        decode(&input, &a, &Budget::default(), &cancel).unwrap();
        crate::develop::decode(&input, &b, &Budget::default(), &cancel).unwrap();
        assert_eq!(std::fs::read(&a).unwrap(), std::fs::read(&b).unwrap());
        std::fs::remove_file(a).unwrap();
        std::fs::remove_file(b).unwrap();
    }
}
