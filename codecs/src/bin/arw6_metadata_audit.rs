//! Controlled offline metadata audit; no replacement image is emitted.
use film_codecs::{camera_raw::CameraRaw, develop::Budget};
use rawler::rawsource::RawSource;
fn main() {
    let a: Vec<_> = std::env::args().collect();
    let path = std::path::Path::new(&a[1]);
    let source = RawSource::new(path).unwrap();
    let raw = rawler::decode_dummy(&source).unwrap();
    let camera = CameraRaw::read(path).unwrap();
    println!(
        "{}",
        serde_json::json!({"make":raw.make,"model":raw.model,"source_ifd":raw.source_ifd_offset,"width":raw.width,"height":raw.height,"cpp":raw.cpp,"black":raw.blacklevel.as_bayer_array(),"white":raw.whitelevel.as_bayer_array(),"wb":&raw.wb_coeffs[..3],"wb_bits":raw.wb_coeffs[..3].iter().map(|v|v.to_bits()).collect::<Vec<_>>(),"matrix":camera.development.matrix,"active":format!("{:?}",raw.active_area),"crop":format!("{:?}",raw.crop_area),"cfa":camera.development.cfa,"probe":camera.probe(),"admission":camera.admit(&Budget::default()).unwrap()})
    );
}
