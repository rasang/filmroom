//! External profiles for original camera CFA DNGs without an opcode contract.
//! Converted/linear DNGs and every existing standard-opcode path retain their
//! own ordered processing. This never infers absence from an unsupported tag.
use super::*;
use crate::{develop::Development,lensfun_metadata::{capture_identity,CaptureStatus}};
use rawler::{rawsource::RawSource,rawimage::RawPhotometricInterpretation};
use std::path::Path;

fn normalized(value:&str)->String {
    value.chars().flat_map(char::to_lowercase).filter(|c|c.is_alphanumeric()).collect()
}

fn native_firmware(model:&str,software:Option<&str>)->bool {
    let model=normalized(model);
    let Some(software)=software else {return false;};
    let software=normalized(software);
    // Positive evidence: the file's software identifies the camera model and
    // firmware. Merely omitting a known converter's name is not enough.
    let Some(version)=software.strip_prefix(&model) else {return false;};
    let version=["version","firmware","ver"].iter().find_map(|prefix|version.strip_prefix(prefix));
    model.len()>=3 && version.is_some_and(|value|value.starts_with(|c:char|c.is_ascii_digit()))
}

pub(super) fn configure(d:&mut Dng,t:&Tiff,file:&mut File) {
    if d.cpp!=1 || d.profile.is_some() || d.warp.is_some()
        || !d.gain_maps.is_empty() || !d.cfa_gain_maps.is_empty() || !d.stage1_opcodes.is_empty()
        || t.ifds.iter().any(|ifd|[51008,51009,51022].iter().any(|tag|ifd.contains_key(tag)))
        || t.ifds.iter().filter_map(|ifd|ifd.get(&50718)).any(|v|v.nums().ok().is_none_or(|n|n.as_slice()!=[1.0,1.0]))
    {return;}
    let Ok(source)=RawSource::new_from_file(file,Path::new("original-camera.dng")) else {return;};
    let Ok((raw,metadata))=crate::camera_raw::bounded("probe",|| {
        let decoder=rawler::get_decoder(&source)?;
        let raw=decoder.raw_image(&source,&Default::default(),true)?;
        let metadata=decoder.raw_metadata(&source,&Default::default()).ok();
        Ok((raw,metadata))
    }) else {return;};
    let rect=|r:Rect|[r.x,r.y,r.w,r.h];
    let raw_rect=|r:rawler::imgop::Rect|[r.p.x,r.p.y,r.d.w,r.d.h];
    let active=raw.active_area.map(raw_rect).unwrap_or([0,0,raw.width,raw.height]);
    let crop=raw.crop_area.map(raw_rect).unwrap_or(active);
    if raw.cpp!=1 || [raw.width,raw.height]!=[d.width,d.height]
        || active!=rect(d.active) || crop!=rect(d.crop)
        || !matches!(&raw.photometric,RawPhotometricInterpretation::Cfa(_)) {return;}
    let capture=capture_identity(file,d.input_bytes,"DNG",&raw,metadata.as_ref());
    if capture.status!=CaptureStatus::Ready || !capture.evidence.is_dng || capture.evidence.converted_dng_marker
        || capture.evidence.orientation.is_some_and(|orientation|orientation!=u16::from(d.orientation))
        || !native_firmware(capture.evidence.original_model.as_deref().unwrap_or(""),capture.evidence.software.as_deref())
    {return;}
    let Some(identity)=capture.identity else {return;};
    let mut development=Development::from(&*d);
    crate::lensfun::configure_capture("DNG",&identity,&mut development);
    let mut warnings=Vec::new();
    crate::camera_raw::append_optics_warnings(&development,&mut warnings);
    let profile=development.lens_profile.take();
    drop(development);
    d.lens_profile=profile;
    d.warnings.extend(warnings);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_firmware_is_positive_evidence_not_a_converter_blacklist() {
        assert!(native_firmware("RICOH GR III",Some("RICOH GR III Ver. 1.00")));
        assert!(native_firmware("RICOH GR IIIx",Some("RICOH GR IIIx Ver. 1.02")));
        for software in [None,Some(""),Some("Adobe DNG Converter"),Some("Camera Raw 17"),
            Some("RICOH GR IIIx Ver. 1.02"),Some("RICOH GR III"),
            Some("RICOH GR III verbatim copy"),Some("RICOH GR III firmware")] {
            assert!(!native_firmware("RICOH GR III",software),"{software:?}");
        }
    }
}
