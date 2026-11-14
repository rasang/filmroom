//! Owned, bounded capture identity. This module selects no optical profile and
//! makes no assertion that a RAW is uncorrected. The adapter must separately
//! qualify original CFA data, source geometry and each embedded correction.

use super::{Directory, Endian, Extent, Reader, Region, Value, MAX_INPUT_BYTES};
use rawler::{decoders::RawMetadata, lens::LensDescription, rawimage::RawImage};
use std::{collections::{BTreeMap, BTreeSet}, fs::File, io::{Read, Seek, SeekFrom}};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CaptureStatus { Ready, Missing, Unsupported, Ambiguous, Conflict }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LensIdentityProvenance {
    OriginalExif, OriginalMakerNote, RawlerDatabase, CameraDatabase, CameraOnly,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct CaptureIdentity {
    pub make: String,
    pub model: String,
    pub lens_make: Option<String>,
    pub lens_model: Option<String>,
    pub lens_aliases: Vec<String>,
    /// Lensfun mount spelling, only when a unique rawler lens establishes it.
    pub mount: Option<String>,
    pub focal_length_mm: f64,
    pub aperture: Option<f64>,
    /// Canon's near/far interval, or [nominal, nominal] for EXIF SubjectDistance.
    /// Infinity is preserved only when explicitly encoded. Unknown stays None.
    pub focus_range_m: Option<[f64; 2]>,
    pub provenance: LensIdentityProvenance,
}

/// Source binding and audit evidence, deliberately excluding serial numbers,
/// author, timestamps, GPS, previews and other unrelated personal metadata.
#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct SourceEvidence {
    pub format: String,
    pub input_bytes: u64,
    pub metadata_read_bytes: usize,
    pub original_make: Option<String>,
    pub original_model: Option<String>,
    pub original_lens_make: Option<String>,
    pub original_lens_model: Option<String>,
    pub makernote_lens_model: Option<String>,
    pub original_focal_length_mm: Option<f64>,
    pub original_lens_focal_range: Option<[f64; 2]>,
    pub canon_lens_id: Option<[u32; 2]>,
    pub canon_model_id: Option<u32>,
    pub canon_extender: Option<u16>,
    pub canon_focal_length_mm: Option<f64>,
    pub canon_lens_focal_range: Option<[f64; 2]>,
    pub canon_raw_quality: Option<u16>,
    pub canon_aspect_crop: Option<[u32; 4]>,
    pub canon_aspect_mode: Option<u32>,
    pub exif_size: Option<[u32; 2]>,
    pub orientation: Option<u16>,
    pub decoded_orientation: u16,
    pub sensor_size: [usize; 2],
    pub active_area: Option<[usize; 4]>,
    pub crop_area: Option<[usize; 4]>,
    pub source_ifd_offset: Option<u32>,
    pub source_is_cfa: bool,
    pub is_dng: bool,
    pub converted_dng_marker: bool,
    pub original_raw_file_name_present: bool,
    pub original_raw_file_data_present: bool,
    pub dng_private_data_present: bool,
    pub adobe_private_data: bool,
    pub software: Option<String>,
    pub unique_camera_model: Option<String>,
    pub database_lens_name: Option<String>,
    pub database_lens_id: Option<[u32; 2]>,
    pub database_nikon_id: Option<String>,
    pub database_olympus_id: Option<String>,
    pub database_candidate_count: usize,
    pub database_candidates: Vec<String>,
    /// Explicit converter/extender text, kept intact instead of reducing a
    /// combination to its base lens. Absence does not prove no accessory.
    pub attachment_identity: Option<String>,
    pub fixed_lens_key: Option<String>,
    pub aperture_invalid: bool,
    pub focus_invalid: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct CaptureMetadata {
    pub status: CaptureStatus,
    pub reason: &'static str,
    pub identity: Option<CaptureIdentity>,
    pub evidence: SourceEvidence,
}

impl CaptureMetadata {
    fn failed(status: CaptureStatus, reason: &'static str, evidence: SourceEvidence) -> Self {
        Self { status, reason, identity: None, evidence }
    }
}

/// At most 32 KiB is read through the bounded reader (4 KiB per read, at most
/// 256 boxes, plus at most 16 DNG directories for conversion markers).
/// No image/preview/MakerNote blob is
/// copied. The file cursor and ordinary decoding are unaffected by failure.
///
/// `metadata` should be obtained with decoder.raw_metadata inside the adapter's
/// existing metadata-budget/catch-unwind scope, using this very RAW source.
/// rawler overwrites EXIF lens names from its database; they are never labelled
/// original here. Aperture and subject distance are independently optional.
pub(crate) fn capture_identity(
    file: &mut File,
    input_bytes: u64,
    format: &str,
    raw: &RawImage,
    metadata: Option<&RawMetadata>,
) -> CaptureMetadata {
    let mut evidence = SourceEvidence {
        format: format.to_owned(), input_bytes,
        sensor_size: [raw.width, raw.height],
        active_area: raw.active_area.map(|r| [r.p.x, r.p.y, r.d.w, r.d.h]),
        crop_area: raw.crop_area.map(|r| [r.p.x, r.p.y, r.d.w, r.d.h]),
        source_ifd_offset: raw.source_ifd_offset,
        source_is_cfa: raw.cpp == 1 && matches!(raw.photometric,
            rawler::rawimage::RawPhotometricInterpretation::Cfa(_)),
        decoded_orientation: raw.orientation.to_u16(),
        fixed_lens_key: raw.camera.param_str("fixed_lens_key").and_then(clean_string),
        ..Default::default()
    };
    if !(16..=MAX_INPUT_BYTES).contains(&input_bytes)
        || file.metadata().map(|m| m.len()).ok() != Some(input_bytes)
    {
        return CaptureMetadata::failed(CaptureStatus::Unsupported, "input size changed or exceeds metadata limit", evidence);
    }
    let position = match file.stream_position() {
        Ok(p) => p,
        Err(_) => return CaptureMetadata::failed(CaptureStatus::Unsupported, "metadata source is not seekable", evidence),
    };
    let mut reader = Reader::new(file, input_bytes);
    let tags = source_tags(&mut reader, format);
    evidence.metadata_read_bytes = reader.bytes_read;
    if reader.input.seek(SeekFrom::Start(position)).is_err() {
        return CaptureMetadata::failed(CaptureStatus::Unsupported, "could not restore metadata cursor", evidence);
    }
    let tags = match tags {
        Ok(v) => v,
        Err(f) => return CaptureMetadata::failed(f.status, f.reason, evidence),
    };
    assemble(tags, raw, metadata, evidence)
}

#[derive(Clone, Copy, Debug)]
struct Failure { status: CaptureStatus, reason: &'static str }
type Parsed<T> = Result<T, Failure>;
fn unsupported(reason: &'static str) -> Failure { Failure { status: CaptureStatus::Unsupported, reason } }
fn conflict(reason: &'static str) -> Failure { Failure { status: CaptureStatus::Conflict, reason } }

#[derive(Default)]
struct SourceTags {
    standard: Directory,
    maker: Directory,
    invalid_optional: BTreeSet<u16>,
    is_dng: bool,
    dng_markers: DngMarkers,
}

#[derive(Clone, Copy, Default)]
struct DngMarkers {
    original_name: bool,
    original_data: bool,
    private_data: bool,
    adobe: bool,
}
impl DngMarkers {
    fn merge(&mut self, other: Self) {
        self.original_name |= other.original_name;
        self.original_data |= other.original_data;
        self.private_data |= other.private_data;
        self.adobe |= other.adobe;
    }
}

#[derive(Clone, Copy)]
enum Kind { Standard, Canon, DngMarkers }

struct TiffDirectory {
    values: Directory,
    maker_extent: Option<Extent>,
    invalid_optional: BTreeSet<u16>,
    dng_markers: DngMarkers,
    next_ifd: u32,
}

fn read<R: Read + Seek>(r: &mut Reader<'_, R>, at: u64, len: usize) -> Parsed<Vec<u8>> {
    r.read(at, len).ok_or_else(|| unsupported("metadata read is out of bounds or over budget"))
}

fn tiff_header<R: Read + Seek>(r: &mut Reader<'_, R>, extent: Extent, allow_raw_magic: bool)
    -> Parsed<(Endian, u64)>
{
    if extent.end.saturating_sub(extent.start) < 8 { return Err(unsupported("short TIFF header")); }
    let h = read(r, extent.start, 8)?;
    let endian = match &h[..2] {
        b"II" => Endian::Little, b"MM" => Endian::Big,
        _ => return Err(unsupported("unknown TIFF byte order")),
    };
    let magic = endian.u16(&h[2..]);
    if magic != 42 && !(allow_raw_magic && matches!(magic, 0x4f52 | 0x5352 | 0x55)) {
        return Err(unsupported("unsupported TIFF identity layout"));
    }
    let relative = u64::from(endian.u32(&h[4..]));
    let at = extent.start.checked_add(relative).ok_or_else(|| unsupported("IFD offset overflow"))?;
    if relative < 8 || at >= extent.end { return Err(unsupported("invalid first IFD offset")); }
    Ok((endian, at))
}

fn directory<R: Read + Seek>(
    r: &mut Reader<'_, R>, extent: Extent, endian: Endian, at: u64, kind: Kind,
) -> Parsed<TiffDirectory> {
    if at < extent.start || at.checked_add(2).filter(|v| *v <= extent.end).is_none() {
        return Err(unsupported("IFD lies outside metadata extent"));
    }
    let count = endian.u16(&read(r, at, 2)?) as usize;
    if count > 256 { return Err(unsupported("IFD entry budget exceeded")); }
    let table_len = count * 12 + 4;
    let table_end = at.checked_add(2 + table_len as u64).ok_or_else(|| unsupported("IFD length overflow"))?;
    if table_end > extent.end { return Err(unsupported("truncated IFD")); }
    let table = read(r, at + 2, table_len)?;
    let mut output = TiffDirectory { values: BTreeMap::new(), maker_extent: None, invalid_optional: BTreeSet::new(),
        dng_markers: DngMarkers::default(), next_ifd: endian.u32(&table[count * 12..]) };
    let mut seen = BTreeSet::new();
    for (entry_index, entry) in table[..count * 12].chunks_exact(12).enumerate() {
        let tag = endian.u16(entry);
        let selected = match kind {
            Kind::Standard => matches!(tag, 271 | 272 | 274 | 305 | 0x8769 | 0x927c
                | 0x829d | 0x920a | 0x9206 | 0xa002 | 0xa003 | 0xa432 | 0xa433 | 0xa434
                | 50706 | 50707 | 50708 | 50736 | 50740 | 50827 | 50828),
            Kind::Canon => matches!(tag, 1 | 2 | 0x10 | 0x93 | 0x95 | 0x9a),
            Kind::DngMarkers => matches!(tag, 330 | 0x8769 | 50740 | 50827 | 50828),
        };
        if !selected { continue; }
        let optional = matches!(kind, Kind::Standard) && matches!(tag, 0x829d | 0x9206);
        if !seen.insert(tag) {
            if optional {
                output.values.remove(&tag); output.invalid_optional.insert(tag); continue;
            }
            return Err(conflict("duplicate selected identity tag"));
        }
        if output.invalid_optional.contains(&tag) { continue; }
        // Presence alone excludes fallback. Never read a filename or a large
        // embedded original RAW, and never turn an unreadable payload into
        // "marker missing". Even an empty/malformed declared value is a marker.
        if !matches!(kind, Kind::Canon) && matches!(tag, 50827 | 50828) {
            output.dng_markers.original_name |= tag == 50827;
            output.dng_markers.original_data |= tag == 50828;
            continue;
        }
        let value = (|| -> Parsed<Option<Value>> {
            let typ = endian.u16(&entry[2..]);
            let count = endian.u32(&entry[4..]) as usize;
            let width = match typ { 1 | 2 | 7 => 1, 3 => 2, 4 => 4, 5 => 8,
                _ => return Err(unsupported("selected identity tag has unsupported type")) };
            let len = count.checked_mul(width).ok_or_else(|| unsupported("metadata value length overflow"))?;
            if len == 0 { return Err(unsupported("empty selected identity value")); }
            if optional && (typ != 5 || count != 1) {
                return Err(unsupported("invalid optional capture parameter"));
            }
            let start = if len <= 4 { at + 2 + (entry_index * 12) as u64 + 8 }
                else { extent.start.checked_add(u64::from(endian.u32(&entry[8..])))
                    .ok_or_else(|| unsupported("metadata offset overflow"))? };
            let end = start.checked_add(len as u64).ok_or_else(|| unsupported("metadata extent overflow"))?;
            if start < extent.start || end > extent.end || (len > 4 && start < table_end && end > at) {
                return Err(unsupported("selected metadata value is outside its extent"));
            }
            if tag == 50740 && !matches!(kind, Kind::Canon) {
                if !matches!(typ, 1 | 7) { return Err(unsupported("unsupported DNGPrivateData type")); }
                output.dng_markers.private_data = true;
                let copied = len.min(6);
                let prefix = if len <= 4 { entry[8..8 + copied].to_vec() } else { read(r, start, copied)? };
                if prefix.len() < 6 && b"Adobe\0".starts_with(&prefix) {
                    return Err(unsupported("truncated Adobe private-data signature"));
                }
                output.dng_markers.adobe |= prefix.as_slice() == b"Adobe\0";
                return Ok(None);
            }
            if tag == 0x927c && matches!(kind, Kind::Standard) {
                if !matches!(typ, 1 | 7) || len < 2 { return Err(unsupported("unsupported MakerNote pointer")); }
                output.maker_extent = Some(Extent { start, end });
                return Ok(None);
            }
            // Canon arrays may grow in new firmware. Only their established
            // prefix is needed; validate the whole span but never copy it.
            let stored_count = if matches!(kind, Kind::Canon) && matches!(tag, 1 | 2 | 0x93) && typ == 3 {
                if count > 4096 { return Err(unsupported("Canon metadata array limit exceeded")); }
                count.min(128)
            } else {
                if len > 256 { return Err(unsupported("identity string/value limit exceeded")); }
                count
            };
            let copied = stored_count * width;
            let bytes = if copied <= 4 { entry[8..8 + copied].to_vec() } else { read(r, start, copied)? };
            Ok(Some(Value { typ, count: stored_count, bytes, endian }))
        })();
        match value {
            Ok(Some(v)) => { output.values.insert(tag, v); },
            Ok(None) => {},
            Err(_) if optional => { output.invalid_optional.insert(tag); },
            Err(f) => return Err(f),
        }
    }
    Ok(output)
}

fn source_tags<R: Read + Seek>(r: &mut Reader<'_, R>, format: &str) -> Parsed<SourceTags> {
    if !matches!(format, "CR3" | "CR2" | "NEF" | "ARW" | "RAF" | "ORF" | "PEF" | "RW2" | "DNG") {
        return Err(unsupported("RAW format has no qualified metadata entrance"));
    }
    if format == "CR3" {
        r.scan(Extent { start: 0, end: r.size }, Region::Top)
            .ok_or_else(|| unsupported("invalid or duplicate CR3 metadata containers"))?;
        if (r.ftyp, r.moov, r.mdat, r.descriptor) != (1, 1, 1, 1) {
            return Err(unsupported("CR3 metadata container is incomplete"));
        }
        let mut output = SourceTags::default();
        for (index, kind) in [Kind::Standard, Kind::Standard, Kind::Canon].into_iter().enumerate() {
            let extent = r.cmt[index].ok_or_else(|| unsupported("missing CR3 metadata directory"))?;
            let (endian, at) = tiff_header(r, extent, false)?;
            let parsed = directory(r, extent, endian, at, kind)?;
            if index == 2 { output.maker = parsed.values; }
            else { merge_standard(&mut output, parsed)?; }
        }
        output.is_dng = output.standard.contains_key(&50706) || output.standard.contains_key(&50707);
        if output.is_dng { return Err(conflict("native CR3 contains DNG identity tags")); }
        return Ok(output);
    }
    let base = if format == "RAF" {
        let h = read(r, 0, 92)?;
        if !h.starts_with(b"FUJIFILMCCD-RAW ") { return Err(unsupported("invalid RAF signature")); }
        let jpeg = u64::from(u32::from_be_bytes(h[84..88].try_into().unwrap()));
        let length = u64::from(u32::from_be_bytes(h[88..92].try_into().unwrap()));
        if jpeg < 92 || length < 20 || jpeg.checked_add(length).filter(|v| *v <= r.size).is_none() {
            return Err(unsupported("invalid RAF EXIF extent"));
        }
        Extent { start: jpeg + 12, end: jpeg + length }
    } else { Extent { start: 0, end: r.size } };
    let (endian, at) = tiff_header(r, base, matches!(format, "ORF" | "RW2"))?;
    let root = directory(r, base, endian, at, Kind::Standard)?;
    let exif_at = root.values.get(&0x8769).map(|v| v.uint()
        .and_then(|v| base.start.checked_add(u64::from(v)))
        .ok_or_else(|| unsupported("invalid EXIF directory pointer"))).transpose()?;
    let mut maker_extent = root.maker_extent;
    let mut output = SourceTags::default();
    merge_standard(&mut output, root)?;
    if let Some(exif_at) = exif_at {
        if exif_at == at { return Err(conflict("EXIF pointer aliases root directory")); }
        let exif = directory(r, base, endian, exif_at, Kind::Standard)?;
        if maker_extent.is_some() && exif.maker_extent.is_some() { return Err(conflict("multiple MakerNote pointers")); }
        maker_extent = exif.maker_extent.or(maker_extent);
        merge_standard(&mut output, exif)?;
    }
    if format == "CR2" {
        if let Some(maker) = maker_extent {
            // Canon CR2 offsets are TIFF-root-relative, even inside MakerNotes.
            // Its IFD table must fit in the MakerNote span; values must fit in
            // the original TIFF. No generic MakerNote format is guessed.
            let count = endian.u16(&read(r, maker.start, 2)?) as u64;
            if count > 256 || maker.start + 2 + count * 12 + 4 > maker.end {
                return Err(unsupported("invalid Canon MakerNote IFD"));
            }
            output.maker = directory(r, base, endian, maker.start, Kind::Canon)?.values;
        }
    }
    output.is_dng = output.standard.contains_key(&50706) || output.standard.contains_key(&50707);
    if (format == "DNG") != output.is_dng { return Err(conflict("RAW format and DNG tags disagree")); }
    if format == "DNG" { scan_dng_markers(r, base, endian, at, &mut output.dng_markers)?; }
    Ok(output)
}

/// OriginalRawFile* and DNGPrivateData are normally IFD0 tags. Scan the bounded
/// reachable IFD tree as well, so moving a marker into the raw/preview IFD does
/// not make it disappear. Only pointers and marker prefixes are materialized.
fn scan_dng_markers<R: Read + Seek>(r: &mut Reader<'_, R>, extent: Extent, endian: Endian,
    root: u64, markers: &mut DngMarkers) -> Parsed<()>
{
    let mut pending = vec![root];
    let mut seen = BTreeSet::new();
    while let Some(at) = pending.pop() {
        if seen.len() >= 16 || !seen.insert(at) {
            return Err(unsupported("DNG marker IFD graph is cyclic, repeated or over budget"));
        }
        let parsed = directory(r, extent, endian, at, Kind::DngMarkers)?;
        markers.merge(parsed.dng_markers);
        let mut relative = Vec::new();
        if parsed.next_ifd != 0 { relative.push(parsed.next_ifd); }
        if let Some(exif) = parsed.values.get(&0x8769) {
            relative.push(exif.uint().ok_or_else(|| unsupported("invalid DNG EXIF pointer"))?);
        }
        if let Some(sub) = parsed.values.get(&330) {
            if sub.typ != 4 || sub.count > 16 { return Err(unsupported("DNG SubIFD pointer budget exceeded")); }
            for i in 0..sub.count { relative.push(sub.long_at(i).ok_or_else(|| unsupported("invalid DNG SubIFD pointer"))?); }
        }
        if seen.len() + pending.len() + relative.len() > 16 { return Err(unsupported("DNG marker directory budget exceeded")); }
        for offset in relative {
            if offset < 8 { return Err(unsupported("invalid DNG marker IFD offset")); }
            let at = extent.start.checked_add(u64::from(offset)).filter(|v| *v < extent.end)
                .ok_or_else(|| unsupported("DNG marker IFD lies outside file"))?;
            pending.push(at);
        }
    }
    Ok(())
}

fn merge_standard(output: &mut SourceTags, parsed: TiffDirectory) -> Parsed<()> {
    output.dng_markers.merge(parsed.dng_markers);
    output.invalid_optional.extend(parsed.invalid_optional);
    for (tag, value) in parsed.values {
        if output.standard.contains_key(&tag) {
            if matches!(tag, 0x829d | 0x9206) { output.invalid_optional.insert(tag); output.standard.remove(&tag); continue; }
            return Err(conflict("identity tag occurs in multiple metadata directories"));
        }
        if !output.invalid_optional.contains(&tag) { output.standard.insert(tag, value); }
    }
    for tag in &output.invalid_optional { output.standard.remove(tag); }
    Ok(())
}

fn clean_string(value: &str) -> Option<String> {
    let text = value.trim_matches([' ', '\0']);
    (!text.is_empty() && text.len() <= 256 && !text.chars().any(char::is_control)).then(|| text.to_owned())
}

fn string(value: &Value, opaque_tail: bool) -> Option<String> {
    if value.typ != 2 || value.bytes.is_empty() || value.bytes.len() > 256 { return None; }
    let end = value.bytes.iter().position(|v| *v == 0).unwrap_or(value.bytes.len());
    if !opaque_tail && value.bytes[end..].iter().any(|v| *v != 0) { return None; }
    std::str::from_utf8(&value.bytes[..end]).ok().and_then(clean_string)
}

fn rational(value: rawler::formats::tiff::Rational) -> Option<f64> {
    if value.n == 0 || value.d == 0 { return None; }
    let v = f64::from(value.n) / f64::from(value.d);
    v.is_finite().then_some(v)
}

fn nominal_distance(value: &Value) -> Option<[f64; 2]> {
    if value.typ != 5 || value.count != 1 { return None; }
    let n = value.endian.u32(&value.bytes); let d = value.endian.u32(&value.bytes[4..]);
    if n == 0 || d == 0 { return None; }
    let metres = if n == u32::MAX { f64::INFINITY } else { f64::from(n) / f64::from(d) };
    Some([metres, metres])
}

fn value_range(value: &Value) -> Option<[f64; 2]> {
    if value.typ != 5 || value.count != 4 { return None; }
    let mut result = [0.0; 2];
    for (i, out) in result.iter_mut().enumerate() {
        *out = rational(rawler::formats::tiff::Rational { n: value.endian.u32(&value.bytes[i*8..]),
            d: value.endian.u32(&value.bytes[i*8+4..]) })?;
    }
    (result[0] <= result[1]).then_some(result)
}

fn key(value: &str) -> String { value.chars().filter(char::is_ascii_alphanumeric).flat_map(char::to_lowercase).collect() }
fn lens_key(value: &str, make: &str) -> String {
    let name = key(value); let make = key(make);
    if !make.is_empty() { name.strip_prefix(&make).unwrap_or(&name).to_owned() } else { name }
}
fn same_lens_name(value: &str, lens: &LensDescription) -> bool {
    let k = lens_key(value, &lens.lens_make);
    k == lens_key(&lens.lens_model, &lens.lens_make)
        || k == lens_key(&lens.lens_name, &lens.lens_make)
        || lens.identifiers.name.as_ref().is_some_and(|s| k == lens_key(s, &lens.lens_make))
}
fn focal_in_range(focal: f64, range: [f64; 2]) -> bool {
    // EXIF focal values and lens endpoint names may be rounded independently.
    // This is consistency tolerance, never a choice between nearby lenses.
    let tolerance = (focal * 0.02).max(0.05);
    focal + tolerance >= range[0] && focal - tolerance <= range[1]
}
fn lens_focal_range(lens: &LensDescription) -> Option<[f64; 2]> {
    Some([rational(lens.focal_range[0])?, rational(lens.focal_range[1])?])
}
fn has_attachment(name: &str) -> bool {
    let n = name.to_lowercase().replace('×', "x");
    n.contains('+') || ["teleconverter", "extender", "converter", "speed booster",
        "speedbooster", "tc-", "mc-14", "mc-20", "wcl-", "tcl-", "sel14tc", "sel20tc"]
        .iter().any(|s| n.contains(s))
}
fn same_database_lens(a: &LensDescription, b: &LensDescription) -> bool {
    key(&a.lens_make) == key(&b.lens_make) && key(&a.lens_model) == key(&b.lens_model) && a.mount == b.mount
}

/// Rawler and Lensfun use different mount identifiers. Unknown or more generic
/// mounts stay None; camera compatibility is still checked by Lensfun matching.
fn lensfun_mount(mount: &str) -> Option<String> {
    Some(match mount {
        "rf-mount" => "Canon RF", "ef-mount" => "Canon EF", "ef-m-mount" => "Canon EF-M",
        "e-mount" => "Sony E", "a-mount" => "Sony Alpha", "Z-mount" => "Nikon Z",
        "MFT-mount" => "Micro 4/3 System", "FT-mount" => "4/3 System",
        // A generic F/K identifier does not establish the AF/AI or KAF subtype.
        _ => return None,
    }.to_owned())
}

fn assemble(tags: SourceTags, raw: &RawImage, metadata: Option<&RawMetadata>, mut e: SourceEvidence) -> CaptureMetadata {
    let s = &tags.standard;
    macro_rules! fail { ($status:ident, $reason:literal) => { return CaptureMetadata::failed(CaptureStatus::$status, $reason, e) }; }
    let mut malformed_string = false;
    for (tag, destination) in [
        (271, &mut e.original_make), (272, &mut e.original_model),
        (0xa433, &mut e.original_lens_make), (0xa434, &mut e.original_lens_model),
        (305, &mut e.software), (50708, &mut e.unique_camera_model),
    ] {
        if let Some(v) = s.get(&tag) {
            *destination = string(v, false);
            let blank_optional = matches!(tag, 0xa433 | 0xa434 | 305)
                && v.typ == 2 && v.bytes.iter().all(|b| matches!(*b, 0 | b' '));
            malformed_string |= destination.is_none() && !blank_optional;
        }
    }
    if malformed_string { fail!(Unsupported, "malformed original identity string"); }
    if let Some(v) = tags.maker.get(&0x95) {
        e.makernote_lens_model = string(v, true);
        // Canon allocates an opaque tail after the first NUL, including when
        // no electronic lens name is present. Empty is missing, not a name.
        if e.makernote_lens_model.is_none() && !(v.typ == 2 && v.bytes.first() == Some(&0)) {
            fail!(Unsupported, "malformed Canon lens name");
        }
    }
    e.is_dng = tags.is_dng;
    e.original_raw_file_name_present = tags.dng_markers.original_name;
    e.original_raw_file_data_present = tags.dng_markers.original_data;
    e.dng_private_data_present = tags.dng_markers.private_data;
    e.adobe_private_data = tags.dng_markers.adobe;
    e.converted_dng_marker = e.original_raw_file_name_present || e.original_raw_file_data_present || e.adobe_private_data;
    e.orientation = s.get(&274).and_then(Value::uint).and_then(|v| u16::try_from(v).ok());
    if e.orientation.is_some_and(|v| !(1..=8).contains(&v)) { fail!(Unsupported, "invalid original orientation"); }
    e.exif_size = s.get(&0xa002).and_then(Value::uint).zip(s.get(&0xa003).and_then(Value::uint)).map(|(w,h)| [w,h]);
    e.original_lens_focal_range = s.get(&0xa432).or_else(|| s.get(&50736)).and_then(value_range);
    e.original_focal_length_mm = s.get(&0x920a).and_then(Value::positive_rational);
    if let Some(v) = s.get(&0x920a) {
        let explicit_unknown = v.typ == 5 && v.count == 1 && v.endian.u32(&v.bytes) == 0
            && v.endian.u32(&v.bytes[4..]) != 0;
        if e.original_focal_length_mm.is_none() && !explicit_unknown { fail!(Unsupported, "invalid actual focal length"); }
    }
    if e.original_make.as_ref().is_some_and(|v| key(v) != key(&raw.make) && key(v) != key(&raw.clean_make))
        || e.original_model.as_ref().is_some_and(|v| key(v) != key(&raw.model) && key(v) != key(&raw.clean_model))
    { fail!(Conflict, "on-file camera identity disagrees with decoded source"); }
    if let Some(md) = metadata {
        if key(&md.make) != key(&raw.clean_make) || key(&md.model) != key(&raw.clean_model) {
            fail!(Conflict, "raw metadata belongs to a different camera");
        }
    }
    let make = clean_string(&raw.clean_make).or_else(|| clean_string(&raw.make));
    let model = clean_string(&raw.clean_model).or_else(|| clean_string(&raw.model));
    let (Some(make), Some(model)) = (make, model) else { fail!(Missing, "camera identity is missing"); };
    let metadata_focal = metadata.and_then(|m| m.exif.focal_length).and_then(rational);
    if let (Some(a), Some(b)) = (e.original_focal_length_mm, metadata_focal) {
        if (a-b).abs() > 1e-6 * a.max(b) { fail!(Conflict, "original and decoded focal lengths disagree"); }
    }
    let Some(focal) = e.original_focal_length_mm.or_else(|| (!s.contains_key(&0x920a)).then_some(metadata_focal).flatten())
        .filter(|v| (0.1..=20000.0).contains(v))
        else { fail!(Missing, "actual focal length is missing"); };
    if e.original_lens_focal_range.is_some_and(|r| !focal_in_range(focal, r)) {
        fail!(Conflict, "capture focal length is outside original lens range");
    }
    e.aperture_invalid = tags.invalid_optional.contains(&0x829d);
    e.focus_invalid = tags.invalid_optional.contains(&0x9206);
    let aperture = if e.aperture_invalid { None } else {
        match s.get(&0x829d) {
            Some(v) => v.positive_rational(),
            None => metadata.and_then(|m| m.exif.fnumber).and_then(rational),
        }.filter(|v| (0.1..=256.0).contains(v))
    };
    let focus_exif = if e.focus_invalid { None } else { s.get(&0x9206).and_then(nominal_distance) };
    let mut focus = focus_exif;
    let mut canon_id = None;
    if matches!(e.format.as_str(), "CR2" | "CR3") {
        let settings = tags.maker.get(&1);
        let file_info = tags.maker.get(&0x93);
        let id = settings.and_then(|v| v.short_at(22));
        let rf = file_info.and_then(|v| v.short_at(61));
        // Pinned ExifTool Canon.pm FileInfo[26], explicitly restricted there to
        // EOS R bodies. 5/6 identify an RF extender, even with adapted EF lenses
        // whose reported focal length may remain unscaled. 0 is not evidence
        // that no accessory exists; only explicit positive IDs are acted on.
        if lens_key(&model, "Canon").starts_with("eosr") {
            e.canon_extender = file_info.and_then(|v| v.short_at(26));
        }
        e.canon_raw_quality = settings.and_then(|v| v.short_at(3));
        e.canon_model_id = tags.maker.get(&0x10).and_then(Value::uint);
        e.canon_focal_length_mm = tags.maker.get(&2).and_then(|v| v.short_at(1))
            .filter(|v| *v != 0).map(f64::from);
        if e.canon_focal_length_mm.is_some_and(|v| (v - focal).abs() > 0.5) {
            fail!(Conflict, "Canon MakerNote and EXIF capture focal lengths disagree");
        }
        if let Some(settings) = settings {
            if let (Some(long), Some(short), Some(units)) = (settings.short_at(23), settings.short_at(24), settings.short_at(25)) {
                if units != 0 && short != 0 && long >= short {
                    let range = [f64::from(short) / f64::from(units), f64::from(long) / f64::from(units)];
                    e.canon_lens_focal_range = Some(range);
                    if !focal_in_range(focal, range) { fail!(Conflict, "Canon lens range and capture focal length disagree"); }
                }
            }
        }
        if let Some(id) = id {
            if id == 61182 && rf.filter(|v| *v != 0).is_none() { fail!(Missing, "RF shared lens ID has no RF sub-ID"); }
            canon_id = Some((u32::from(id), if id == 61182 { u32::from(rf.unwrap()) } else { 0 }));
            e.canon_lens_id = canon_id.map(|(a,b)| [a,b]);
        } else if e.format == "CR3" { fail!(Missing, "Canon source lens ID is missing"); }
        if let Some(info) = file_info { focus = super::focus_range(info).or(focus); }
        if let Some(aspect) = tags.maker.get(&0x9a) {
            e.canon_aspect_mode = aspect.long_at(0);
            e.canon_aspect_crop = aspect.long_at(3).zip(aspect.long_at(4)).zip(aspect.long_at(1).zip(aspect.long_at(2)))
                .map(|((x,y),(w,h))| [x,y,w,h]);
            if e.canon_aspect_crop.is_none() { fail!(Unsupported, "invalid Canon aspect crop"); }
        }
        if let (Some(exif), Some(maker)) = (&e.original_lens_model, &e.makernote_lens_model) {
            if lens_key(exif, "Canon") != lens_key(maker, "Canon") { fail!(Conflict, "Canon EXIF and MakerNote lens names disagree"); }
        }
        // A native raw quality marker describing JPEG/sRAW must never be
        // silently reinterpreted as an ordinary original full CFA capture.
        if e.canon_raw_quality.is_some_and(|v| !matches!(v, 4 | 7)) {
            fail!(Unsupported, "Canon capture quality is not a qualified RAW mode");
        }
    }
    let original_names: Vec<&str> = [&e.original_lens_model, &e.makernote_lens_model].into_iter().filter_map(|s| s.as_deref()).collect();
    let database = rawler::lens::get_lenses();
    let resolved = metadata.and_then(|m| m.lens.as_ref());
    if let Some(lens) = resolved {
        e.database_lens_name = clean_string(&lens.lens_name);
        e.database_lens_id = lens.identifiers.id.map(|(a,b)| [a,b]);
        e.database_nikon_id = lens.identifiers.nikon_id.clone();
        e.database_olympus_id = lens.identifiers.olympus_id.clone();
    }
    let fixed_key = e.fixed_lens_key.as_deref();
    let mut candidates: Vec<&LensDescription> = if let Some(id) = canon_id {
        database.iter().filter(|l| l.identifiers.id == Some(id)
            && matches!(l.mount.as_str(), "rf-mount" | "ef-mount" | "cn-mount")).collect()
    } else if let Some(name) = fixed_key {
        database.iter().filter(|l| l.identifiers.name.as_deref() == Some(name)).collect()
    } else if let Some(lens) = resolved {
        database.iter().filter(|l| {
            if l.mount != lens.mount { return false; }
            match e.format.as_str() {
                "NEF" if lens.identifiers.nikon_id.is_some() => l.identifiers.nikon_id == lens.identifiers.nikon_id,
                "ORF" | "RW2" if lens.identifiers.olympus_id.is_some() => l.identifiers.olympus_id == lens.identifiers.olympus_id,
                _ if lens.identifiers.id.is_some() => l.identifiers.id == lens.identifiers.id,
                _ => lens.identifiers.name.is_some() && l.identifiers.name == lens.identifiers.name,
            }
        }).collect()
    } else { Vec::new() };
    // Name-first resolution in rawler must not override a conflicting Canon ID.
    // Check all known original names against the actual on-file ID, even when
    // raw_metadata() returned None or a different database candidate.
    if let Some(id) = canon_id {
        for name in &original_names {
            let named: Vec<_> = database.iter().filter(|l| same_lens_name(name, l)
                && matches!(l.mount.as_str(), "rf-mount" | "ef-mount" | "cn-mount")).collect();
            if !named.is_empty() && !named.iter().any(|l| l.identifiers.id == Some(id)) {
                fail!(Conflict, "Canon original lens name conflicts with on-file lens ID");
            }
        }
        if id.0 == 61182 && candidates.is_empty() { fail!(Unsupported, "RF sub-ID has no independently known identity"); }
    }
    if !candidates.is_empty() {
        candidates.retain(|l| lens_focal_range(l).is_some_and(|range| focal_in_range(focal, range)));
        if candidates.is_empty() { fail!(Conflict, "source lens ID and actual focal length disagree"); }
        for name in &original_names {
            let matching: Vec<_> = candidates.iter().copied().filter(|l| same_lens_name(name, l)).collect();
            if !matching.is_empty() { candidates = matching; }
            else if database.iter().any(|l| same_lens_name(name, l) && !candidates.iter().any(|c| same_database_lens(c, l))) {
                fail!(Conflict, "original lens name conflicts with database-resolved lens ID");
            }
        }
    }
    let mut distinct: Vec<&LensDescription> = Vec::new();
    for lens in candidates {
        if !distinct.iter().any(|p| same_database_lens(p, lens)) { distinct.push(lens); }
    }
    e.database_candidate_count = distinct.len();
    e.database_candidates = distinct.iter().take(32).map(|l| l.lens_name.clone()).collect();
    if distinct.len() > 1 { fail!(Ambiguous, "source lens ID resolves to multiple optical models"); }
    let selected = distinct.first().copied();
    if let (Some(a), Some(b)) = (selected, resolved) {
        if !same_database_lens(a, b) { fail!(Conflict, "decoder lens resolution disagrees with original identity"); }
    }
    e.attachment_identity = original_names.iter().find(|name| has_attachment(name)).map(|s| (*s).to_owned())
        .or_else(|| selected.filter(|l| has_attachment(&l.lens_model)).map(|l| l.lens_model.clone()));
    if matches!(e.canon_extender, Some(5 | 6)) {
        let factor = if e.canon_extender == Some(5) { "1.4x" } else { "2x" };
        if !e.attachment_identity.as_ref().is_some_and(|n| n.to_lowercase().replace('×', "x").contains(factor)) {
            e.attachment_identity = Some(format!("Canon Extender RF{factor}"));
            fail!(Unsupported, "Canon extender has no complete matching combination identity");
        }
    }
    if let (Some(attachment), Some(lens)) = (&e.attachment_identity, selected) {
        if !has_attachment(&lens.lens_model) || !same_lens_name(attachment, lens) {
            fail!(Unsupported, "explicit optical attachment is not represented by resolved lens identity");
        }
    }
    let mut aliases = BTreeSet::new();
    for name in &original_names { if let Some(s) = clean_string(name) { aliases.insert(s); } }
    let (lens_make, lens_model, mount, provenance) = if let Some(lens) = selected {
        if let Some(raw_make) = &e.original_lens_make {
            if key(raw_make) != key(&lens.lens_make) { fail!(Conflict, "original lens make disagrees with resolved identity"); }
        }
        for name in [&lens.lens_model, &lens.lens_name] { if let Some(s) = clean_string(name) { aliases.insert(s); } }
        if let Some(s) = lens.identifiers.name.as_ref().and_then(|s| clean_string(s)) { aliases.insert(s); }
        (clean_string(&lens.lens_make), clean_string(&lens.lens_model), lensfun_mount(&lens.mount),
            if fixed_key.is_some() { LensIdentityProvenance::CameraDatabase } else { LensIdentityProvenance::RawlerDatabase })
    } else {
        (e.original_lens_make.clone(), e.original_lens_model.clone().or(e.makernote_lens_model.clone()), None,
            if e.original_lens_model.is_some() { LensIdentityProvenance::OriginalExif }
            else if e.makernote_lens_model.is_some() { LensIdentityProvenance::OriginalMakerNote }
            else { LensIdentityProvenance::CameraOnly })
    };
    if e.attachment_identity.is_some() { aliases.retain(|name| has_attachment(name)); }
    CaptureMetadata { status: CaptureStatus::Ready, reason: "bounded capture identity is consistent",
        identity: Some(CaptureIdentity { make, model, lens_make, lens_model,
            lens_aliases: aliases.into_iter().collect(), mount, focal_length_mm: focal,
            aperture, focus_range_m: focus, provenance }), evidence: e }
}

#[cfg(test)]
#[path = "capture_tests.rs"]
mod tests;
