//! Bounded source metadata for Lensfun fallback.
//!
//! `capture_identity` is the production cross-format entrance. Original names
//! and capture parameters are read independently; database-derived identities
//! retain provenance, candidate ambiguity and on-file conflict checks.
//! Adapters separately qualify original CFA data, geometry and per-item
//! embedded correction priority. Identity is not proof of processing state.
//!
//! The old exact EOS R / RF16 reader below is compiled only in tests, retaining
//! the initial admission rule as a regression oracle, not a runtime whitelist.
//! Canon FileInfo[61] and its infinity/distance definitions were checked against
//! ExifTool Canon.pm at 1da714924f67763b6a8a7013dd2aa1983a16f87b.

use std::{
    collections::{BTreeMap, BTreeSet},
    io::{Read, Seek, SeekFrom},
};
#[cfg(test)]
use std::fs::File;

#[path = "lensfun_metadata/capture.rs"]
mod capture;
pub(crate) use capture::{
    capture_identity, CaptureIdentity, CaptureMetadata, CaptureStatus,
    LensIdentityProvenance, SourceEvidence,
};

const MAX_INPUT_BYTES: u64 = 512 * 1024 * 1024;
const MAX_READ_BYTES: usize = 32 * 1024;
const MAX_SINGLE_READ: usize = 4096;
const MAX_BOXES: usize = 256;
const MAX_CMT_BYTES: u64 = 64 * 1024;
#[cfg(test)]
const MAX_IFD_ENTRIES: usize = 128;
#[cfg(test)]
const MAX_VALUE_BYTES: usize = 256;
const CANON_DESC: [u8; 16] = [
    0x85, 0xc0, 0xb6, 0x87, 0x82, 0x0f, 0x11, 0xe0,
    0x81, 0x11, 0xf4, 0xce, 0x46, 0x2b, 0x6a, 0x48,
];
#[cfg(test)]
const MAKE: &str = "Canon";
#[cfg(test)]
const MODEL: &str = "Canon EOS R";
#[cfg(test)]
const LENS: &str = "RF16mm F2.8 STM";
#[cfg(test)]
const MODEL_ID: u32 = 0x8000_0424;

/// Every field has been checked against on-file metadata, not a fuzzy lookup.
/// `aspect_crop` is EXIF/image space [left, top, width, height], not a sensor
/// rectangle. Sensor-space admission belongs to the camera adapter.
#[derive(Clone, Copy, Debug, PartialEq)]
#[cfg(test)]
pub(crate) struct CanonRf16Identity {
    pub make: &'static str,
    pub model: &'static str,
    pub exif_lens_model: &'static str,
    pub makernote_lens_model: &'static str,
    pub focal_length_mm: f64,
    pub lens_type: u16,
    pub rf_lens_type: u16,
    pub model_id: u32,
    pub raw_quality: u16,
    pub orientation: u16,
    pub exif_size: [u32; 2],
    pub aspect_crop: [u32; 4],
    /// Actual capture FNumber. Missing/invalid data disables vignetting only.
    pub aperture: Option<f64>,
    /// Canon FileInfo [near, far] metres; 0xffff explicitly denotes infinity.
    pub focus_range_m: Option<[f64; 2]>,
}

/// Optional metadata: malformed, conflicting, unknown and over-budget inputs
/// return None and must not prevent RAW decoding. The caller's cursor is kept.
#[cfg(test)]
pub(crate) fn canon_rf16_identity(
    file: &mut File,
    input_bytes: u64,
) -> Option<CanonRf16Identity> {
    if !(16..=MAX_INPUT_BYTES).contains(&input_bytes)
        || file.metadata().ok()?.len() != input_bytes
    {
        return None;
    }
    let position = file.stream_position().ok()?;
    let result = Reader::new(file, input_bytes).identity();
    file.seek(SeekFrom::Start(position)).ok()?;
    result
}

#[derive(Clone, Copy)]
enum Endian {
    Little,
    Big,
}

impl Endian {
    fn u16(self, b: &[u8]) -> u16 {
        let a = [b[0], b[1]];
        match self {
            Self::Little => u16::from_le_bytes(a),
            Self::Big => u16::from_be_bytes(a),
        }
    }

    fn u32(self, b: &[u8]) -> u32 {
        let a = [b[0], b[1], b[2], b[3]];
        match self {
            Self::Little => u32::from_le_bytes(a),
            Self::Big => u32::from_be_bytes(a),
        }
    }
}

#[derive(Clone, Copy)]
struct Extent {
    start: u64,
    end: u64,
}

#[derive(Clone, Copy, PartialEq)]
enum Region {
    Top,
    Moov,
    Descriptor,
}

#[derive(Clone, Copy)]
#[cfg(test)]
enum DirectoryKind {
    Camera,
    Exif,
    Maker,
}

struct Value {
    typ: u16,
    count: usize,
    bytes: Vec<u8>,
    endian: Endian,
}

impl Value {
    fn short_at(&self, index: usize) -> Option<u16> {
        (self.typ == 3 && index < self.count)
            .then(|| self.endian.u16(&self.bytes[index * 2..]))
    }

    fn long_at(&self, index: usize) -> Option<u32> {
        (self.typ == 4 && index < self.count)
            .then(|| self.endian.u32(&self.bytes[index * 4..]))
    }

    fn uint(&self) -> Option<u32> {
        if self.count != 1 {
            return None;
        }
        self.short_at(0).map(u32::from).or_else(|| self.long_at(0))
    }

    #[cfg(test)]
    fn rational_is(&self, index: usize, integer: u32) -> bool {
        if self.typ != 5 || index >= self.count {
            return false;
        }
        let n = self.endian.u32(&self.bytes[index * 8..]) as u64;
        let d = self.endian.u32(&self.bytes[index * 8 + 4..]) as u64;
        d != 0 && n == u64::from(integer) * d
    }

    fn positive_rational(&self) -> Option<f64> {
        if self.typ != 5 || self.count != 1 { return None; }
        let n = self.endian.u32(&self.bytes) as f64;
        let d = self.endian.u32(&self.bytes[4..]) as f64;
        if n == 0.0 || d == 0.0 { return None; }
        let value = n / d;
        value.is_finite().then_some(value)
    }

    #[cfg(test)]
    fn ascii(&self, opaque_tail: bool) -> Option<&str> {
        if self.typ != 2 {
            return None;
        }
        let end = self.bytes.iter().position(|&v| v == 0)?;
        if end == 0 || end > 64 || !self.bytes[..end].is_ascii() {
            return None;
        }
        // Canon MakerNote LensModel has a 138-byte allocation with opaque data
        // after its first NUL in both audited files. Only its first string is
        // meaningful. Ordinary EXIF identity strings must have zero padding.
        if !opaque_tail && self.bytes[end..].iter().any(|&v| v != 0) {
            return None;
        }
        std::str::from_utf8(&self.bytes[..end]).ok()
    }
}

type Directory = BTreeMap<u16, Value>;

struct Reader<'a, R> {
    input: &'a mut R,
    size: u64,
    bytes_read: usize,
    read_limit: usize,
    boxes: usize,
    ftyp: usize,
    moov: usize,
    mdat: usize,
    descriptor: usize,
    cmt: [Option<Extent>; 3],
}

impl<'a, R: Read + Seek> Reader<'a, R> {
    fn new(input: &'a mut R, size: u64) -> Self {
        Self {
            input,
            size,
            bytes_read: 0,
            read_limit: MAX_READ_BYTES,
            boxes: 0,
            ftyp: 0,
            moov: 0,
            mdat: 0,
            descriptor: 0,
            cmt: [None; 3],
        }
    }

    fn read(&mut self, at: u64, len: usize) -> Option<Vec<u8>> {
        let charged = self.bytes_read.checked_add(len)?;
        if len > MAX_SINGLE_READ || charged > self.read_limit
            || at.checked_add(len as u64)? > self.size
        {
            return None;
        }
        self.bytes_read = charged;
        let mut bytes = vec![0; len];
        self.input.seek(SeekFrom::Start(at)).ok()?;
        self.input.read_exact(&mut bytes).ok()?;
        Some(bytes)
    }

    // The only descended path is top-level -> moov -> Canon descriptor UUID.
    // mdat, previews, CTMD and arbitrary nested trees are never copied.
    fn scan(&mut self, extent: Extent, region: Region) -> Option<()> {
        let mut at = extent.start;
        while at < extent.end {
            if extent.end - at < 8 || self.boxes >= MAX_BOXES {
                return None;
            }
            self.boxes += 1;
            let header = self.read(at, 8)?;
            let typ: [u8; 4] = header[4..8].try_into().ok()?;
            let mut size = u32::from_be_bytes(header[..4].try_into().ok()?) as u64;
            let mut header_len = 8;
            if size == 1 {
                if extent.end - at < 16 {
                    return None;
                }
                size = u64::from_be_bytes(self.read(at + 8, 8)?.try_into().ok()?);
                header_len = 16;
            }
            let end = at.checked_add(size)?;
            if size < header_len || end > extent.end {
                return None;
            }
            let payload = Extent { start: at + header_len, end };
            match &typ {
                b"ftyp" => {
                    self.ftyp += 1;
                    let len = payload.end - payload.start;
                    if region != Region::Top || at != 0 || header_len != 8
                        || self.ftyp != 1 || !(8..=256).contains(&len) || len % 4 != 0
                        || &self.read(payload.start, 4)?[..] != b"crx "
                    {
                        return None;
                    }
                }
                b"moov" => {
                    self.moov += 1;
                    if region != Region::Top || self.moov != 1 {
                        return None;
                    }
                    self.scan(payload, Region::Moov)?;
                }
                b"mdat" => {
                    self.mdat += 1;
                    if region != Region::Top || self.mdat != 1 || payload.start == payload.end {
                        return None;
                    }
                }
                b"uuid" => {
                    if payload.end - payload.start < 16 {
                        return None;
                    }
                    if self.read(payload.start, 16)?.as_slice() == CANON_DESC {
                        self.descriptor += 1;
                        if region != Region::Moov || self.descriptor != 1 {
                            return None;
                        }
                        self.scan(Extent { start: payload.start + 16, end }, Region::Descriptor)?;
                    }
                }
                b"CMT1" | b"CMT2" | b"CMT3" => {
                    let index = usize::from(typ[3] - b'1');
                    if region != Region::Descriptor || self.cmt[index].is_some()
                        || !(8..=MAX_CMT_BYTES).contains(&(payload.end - payload.start))
                    {
                        return None;
                    }
                    self.cmt[index] = Some(payload);
                }
                _ => {}
            }
            at = end;
        }
        Some(())
    }

    #[cfg(test)]
    fn directory(&mut self, extent: Extent, kind: DirectoryKind) -> Option<Directory> {
        let h = self.read(extent.start, 8)?;
        let endian = match &h[..2] {
            b"II" => Endian::Little,
            b"MM" => Endian::Big,
            _ => return None,
        };
        if endian.u16(&h[2..]) != 42 {
            return None;
        }
        let relative = endian.u32(&h[4..]) as u64;
        let at = extent.start.checked_add(relative)?;
        if relative < 8 || at.checked_add(2)? > extent.end {
            return None;
        }
        let count = endian.u16(&self.read(at, 2)?) as usize;
        let table_bytes = count.checked_mul(12)?.checked_add(4)?;
        let table_end = at.checked_add(2 + table_bytes as u64)?;
        if count == 0 || count > MAX_IFD_ENTRIES || table_end > extent.end {
            return None;
        }
        let table = self.read(at + 2, table_bytes)?;
        if endian.u32(&table[count * 12..]) != 0 {
            return None;
        }
        let mut seen = BTreeSet::new();
        let mut invalid_optional = BTreeSet::new();
        let mut result = BTreeMap::new();
        for entry in table[..count * 12].chunks_exact(12) {
            let tag = endian.u16(entry);
            let optional = matches!(kind, DirectoryKind::Exif) && tag == 0x829d;
            if !seen.insert(tag) {
                if optional {
                    invalid_optional.insert(tag);
                    result.remove(&tag);
                    continue;
                }
                return None;
            }
            if matches!(tag, 50706 | 50707) { return None; }
            let selected = match kind {
                DirectoryKind::Camera => matches!(tag, 271 | 272 | 274),
                DirectoryKind::Exif => matches!(tag, 0x829d | 0x920a | 0xa002 | 0xa003 | 0xa432 | 0xa434),
                DirectoryKind::Maker => matches!(tag, 0x0001 | 0x0002 | 0x0010 | 0x0093 | 0x0095 | 0x009a),
            };
            if !selected || invalid_optional.contains(&tag) {
                continue;
            }
            let value = (|| {
            let typ = endian.u16(&entry[2..]);
            let count = endian.u32(&entry[4..]) as usize;
            if optional && (typ != 5 || count != 1) { return None; }
            let width = match typ {
                2 => 1,
                3 => 2,
                4 => 4,
                5 => 8,
                _ => return None,
            };
            let len = count.checked_mul(width)?;
            if len == 0 || len > MAX_VALUE_BYTES {
                return None;
            }
            let bytes = if len <= 4 {
                entry[8..8 + len].to_vec()
            } else {
                let offset = endian.u32(&entry[8..]) as u64;
                let value_start = extent.start.checked_add(offset)?;
                let value_end = value_start.checked_add(len as u64)?;
                if offset < 8 || value_end > extent.end
                    || (value_start < table_end && value_end > at)
                {
                    return None;
                }
                self.read(value_start, len)?
            };
            Some(Value { typ, count, bytes, endian })
            })();
            if let Some(value) = value { result.insert(tag, value); }
            else if !optional { return None; }
        }
        Some(result)
    }

    #[cfg(test)]
    fn identity(&mut self) -> Option<CanonRf16Identity> {
        if !(16..=MAX_INPUT_BYTES).contains(&self.size) {
            return None;
        }
        self.scan(Extent { start: 0, end: self.size }, Region::Top)?;
        if (self.ftyp, self.moov, self.mdat, self.descriptor) != (1, 1, 1, 1) {
            return None;
        }
        let camera = self.directory(self.cmt[0]?, DirectoryKind::Camera)?;
        let exif = self.directory(self.cmt[1]?, DirectoryKind::Exif)?;
        let maker = self.directory(self.cmt[2]?, DirectoryKind::Maker)?;
        let orientation = camera.get(&274)?.uint()?;
        let exif_size = [exif.get(&0xa002)?.uint()?, exif.get(&0xa003)?.uint()?];
        let focal = exif.get(&0x920a)?;
        let specification = exif.get(&0xa432)?;
        let settings = maker.get(&0x0001)?;
        let raw_focal = maker.get(&0x0002)?;
        let file_info = maker.get(&0x0093)?;
        let aspect = maker.get(&0x009a)?;
        let raw_quality = settings.short_at(3)?;
        let lens_type = settings.short_at(22)?;
        let rf_lens_type = file_info.short_at(61)?;
        let units = u32::from(settings.short_at(25)?);
        let model_id = maker.get(&0x0010)?.uint()?;
        let aspect_crop = [aspect.long_at(3)?, aspect.long_at(4)?,
            aspect.long_at(1)?, aspect.long_at(2)?];
        if camera.get(&271)?.ascii(false)? != MAKE
            || camera.get(&272)?.ascii(false)? != MODEL
            || exif.get(&0xa434)?.ascii(false)? != LENS
            || maker.get(&0x0095)?.ascii(true)? != LENS
            || !(1..=8).contains(&orientation)
            || model_id != MODEL_ID || lens_type != 61182 || rf_lens_type != 288
            || !matches!(raw_quality, 4 | 7)
            || focal.count != 1 || !focal.rational_is(0, 16)
            || specification.count != 4 || !specification.rational_is(0, 16)
            || !specification.rational_is(1, 16)
            || units == 0 || u32::from(settings.short_at(23)?) != 16 * units
            || u32::from(settings.short_at(24)?) != 16 * units
            || raw_focal.count != 4 || raw_focal.short_at(1)? != 16
            || exif_size != [6720, 4480]
            || aspect.count != 5 || aspect.long_at(0)? != 0
            || aspect_crop != [0, 0, 6720, 4480]
        {
            return None;
        }
        Some(CanonRf16Identity {
            make: MAKE, model: MODEL, exif_lens_model: LENS,
            makernote_lens_model: LENS, focal_length_mm: 16.0,
            lens_type, rf_lens_type, model_id, raw_quality,
            orientation: orientation as u16, exif_size, aspect_crop,
            aperture: exif.get(&0x829d).and_then(Value::positive_rational)
                .filter(|v| (0.7..=128.0).contains(v)),
            focus_range_m: focus_range(file_info),
        })
    }
}

// ExifTool Canon.pm FileInfo indexes 20/21, at the already pinned source
// revision cited above: raw unsigned centimetres, 65535 is the infinity marker.
// Both bounds must be meaningful; zero/contradictory data never invents focus.
fn focus_range(file_info: &Value) -> Option<[f64; 2]> {
    let far = file_info.short_at(20)?;
    let near = file_info.short_at(21)?;
    if far == 0 || near == 0 || far < near { return None; }
    let metres = |value| if value == u16::MAX { f64::INFINITY } else { f64::from(value) / 100.0 };
    Some([metres(near), metres(far)])
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[derive(Clone)]
    struct Field(u16, u16, u32, Vec<u8>);

    fn ascii(tag: u16, text: &str) -> Field {
        let mut bytes = text.as_bytes().to_vec();
        bytes.push(0);
        Field(tag, 2, bytes.len() as u32, bytes)
    }

    fn shorts(tag: u16, values: &[u16]) -> Field {
        Field(tag, 3, values.len() as u32, values.iter().flat_map(|v| v.to_le_bytes()).collect())
    }

    fn longs(tag: u16, values: &[u32]) -> Field {
        Field(tag, 4, values.len() as u32, values.iter().flat_map(|v| v.to_le_bytes()).collect())
    }

    fn rationals(tag: u16, values: &[(u32, u32)]) -> Field {
        Field(tag, 5, values.len() as u32, values.iter()
            .flat_map(|&(n, d)| [n.to_le_bytes(), d.to_le_bytes()].concat()).collect())
    }

    // Anonymous synthetic metadata only: no serial, date, location, thumbnail
    // or sensor pixels from either public RAW is copied into the test fixture.
    fn fields() -> [Vec<Field>; 3] {
        let mut settings = vec![0; 49];
        settings[3] = 7;
        settings[22..26].copy_from_slice(&[61182, 16, 16, 1]);
        let mut file_info = vec![0; 71];
        file_info[61] = 288;
        file_info[20] = 199;
        file_info[21] = 150;
        let mut maker_name = ascii(0x0095, LENS);
        maker_name.3.resize(138, 0xaa);
        maker_name.2 = 138;
        [
            vec![ascii(271, MAKE), ascii(272, MODEL), shorts(274, &[1])],
            vec![rationals(0x829d, &[(28, 10)]), rationals(0x920a, &[(16, 1)]), shorts(0xa002, &[6720]),
                shorts(0xa003, &[4480]), rationals(0xa432, &[(16, 1), (16, 1), (0, 1), (0, 1)]),
                ascii(0xa434, LENS)],
            vec![shorts(1, &settings), shorts(2, &[0, 16, 0, 64833]), longs(0x10, &[MODEL_ID]),
                shorts(0x93, &file_info), maker_name, longs(0x9a, &[0, 6720, 4480, 0, 0])],
        ]
    }

    fn tiff(mut fields: Vec<Field>) -> Vec<u8> {
        fields.sort_by_key(|f| f.0);
        let fixed = 8 + 2 + fields.len() * 12 + 4;
        let mut data = vec![0; fixed];
        data[..8].copy_from_slice(b"II\x2a\0\x08\0\0\0");
        data[8..10].copy_from_slice(&(fields.len() as u16).to_le_bytes());
        for (i, f) in fields.iter().enumerate() {
            let at = 10 + i * 12;
            data[at..at + 2].copy_from_slice(&f.0.to_le_bytes());
            data[at + 2..at + 4].copy_from_slice(&f.1.to_le_bytes());
            data[at + 4..at + 8].copy_from_slice(&f.2.to_le_bytes());
            if f.3.len() <= 4 {
                data[at + 8..at + 8 + f.3.len()].copy_from_slice(&f.3);
            } else {
                let offset = data.len() as u32;
                data[at + 8..at + 12].copy_from_slice(&offset.to_le_bytes());
                data.extend_from_slice(&f.3);
            }
        }
        data
    }

    fn atom(typ: &[u8; 4], bytes: &[u8]) -> Vec<u8> {
        [(bytes.len() as u32 + 8).to_be_bytes().to_vec(), typ.to_vec(), bytes.to_vec()].concat()
    }

    fn fixture_with_cmt(cmt: Vec<Vec<u8>>) -> Vec<u8> {
        let descriptor = [CANON_DESC.to_vec(), cmt.concat()].concat();
        [atom(b"ftyp", b"crx \0\0\0\0"), atom(b"moov", &atom(b"uuid", &descriptor)),
            atom(b"mdat", &[0])].concat()
    }

    fn fixture(fields: [Vec<Field>; 3]) -> Vec<u8> {
        fixture_with_cmt(fields.into_iter().enumerate().map(|(i, f)| {
            atom(&[b'C', b'M', b'T', b'1' + i as u8], &tiff(f))
        }).collect())
    }

    fn parse(bytes: &[u8]) -> Option<CanonRf16Identity> {
        Reader::new(&mut Cursor::new(bytes), bytes.len() as u64).identity()
    }

    fn replace(fields: &mut [Vec<Field>; 3], directory: usize, field: Field) {
        let index = fields[directory].iter().position(|f| f.0 == field.0).unwrap();
        fields[directory][index] = field;
    }

    #[test]
    fn exact_identity_accepts_opaque_makernote_padding_and_raw_variants() {
        for quality in [4_u16, 7] {
            let mut f = fields();
            let setting = f[2].iter_mut().find(|f| f.0 == 1).unwrap();
            setting.3[6..8].copy_from_slice(&quality.to_le_bytes());
            let identity = parse(&fixture(f)).unwrap();
            assert_eq!(identity.make, MAKE);
            assert_eq!(identity.model, MODEL);
            assert_eq!(identity.exif_lens_model, LENS);
            assert_eq!(identity.makernote_lens_model, LENS);
            assert_eq!(identity.focal_length_mm, 16.0);
            assert_eq!((identity.lens_type, identity.rf_lens_type), (61182, 288));
            assert_eq!(identity.aspect_crop, [0, 0, 6720, 4480]);
            assert_eq!(identity.raw_quality, quality);
            assert_eq!(identity.aperture, Some(2.8));
            assert_eq!(identity.focus_range_m, Some([1.5, 1.99]));
        }
    }

    #[test]
    fn actual_aperture_is_optional_and_malformed_values_do_not_erase_identity() {
        for field in [rationals(0x829d, &[(28, 0)]), rationals(0x829d, &[(0, 10)]),
            rationals(0x829d, &[(10000, 1)]), shorts(0x829d, &[28]),
            Field(0x829d, 5, u32::MAX, vec![])] {
            let mut f = fields();
            replace(&mut f, 1, field);
            let identity = parse(&fixture(f)).unwrap();
            assert_eq!(identity.aperture, None);
            assert_eq!(identity.focus_range_m, Some([1.5, 1.99]));
        }
        let mut f = fields(); f[1].retain(|v| v.0 != 0x829d);
        assert_eq!(parse(&fixture(f)).unwrap().aperture, None);
        let mut f = fields(); f[1].push(rationals(0x829d, &[(40, 10)]));
        assert_eq!(parse(&fixture(f)).unwrap().aperture, None);
    }

    #[test]
    fn focus_bounds_require_actual_nonzero_ordered_values_and_preserve_infinity() {
        for (far, near, expected) in [
            (0, 150, None), (199, 0, None), (150, 199, None),
            (65535, 598, Some([5.98, f64::INFINITY])),
            (65535, 65535, Some([f64::INFINITY, f64::INFINITY])),
        ] {
            let mut f = fields();
            let field = f[2].iter_mut().find(|v| v.0 == 0x93).unwrap();
            field.3[40..42].copy_from_slice(&(far as u16).to_le_bytes());
            field.3[42..44].copy_from_slice(&(near as u16).to_le_bytes());
            assert_eq!(parse(&fixture(f)).unwrap().focus_range_m, expected);
        }
    }

    #[test]
    fn rejects_duplicate_cmt_and_duplicate_selected_tag() {
        let cmt = fields().into_iter().enumerate().map(|(i, f)| {
            atom(&[b'C', b'M', b'T', b'1' + i as u8], &tiff(f))
        }).collect::<Vec<_>>();
        let mut duplicate = cmt.clone();
        duplicate.push(cmt[1].clone());
        assert!(parse(&fixture_with_cmt(duplicate)).is_none());
        let mut f = fields();
        f[1].push(ascii(0xa434, LENS));
        assert!(parse(&fixture(f)).is_none());
    }

    #[test]
    fn rejects_short_arrays_missing_fields_wrong_types_and_zero_denominator() {
        for (dir, field) in [
            (2, shorts(1, &[0; 22])), (2, shorts(0x93, &[0; 61])),
            (2, longs(0x93, &[288; 62])), (1, rationals(0x920a, &[(16, 0)])),
        ] {
            let mut f = fields();
            replace(&mut f, dir, field);
            assert!(parse(&fixture(f)).is_none());
        }
        let mut f = fields();
        f[1].retain(|f| f.0 != 0xa434);
        assert!(parse(&fixture(f)).is_none());
    }

    #[test]
    fn rejects_conflicting_names_ids_cameras_and_focal_lengths() {
        for (dir, field) in [
            (0, ascii(272, "Canon EOS RP")), (2, longs(0x10, &[MODEL_ID + 1])),
            (1, ascii(0xa434, "RF24mm F1.8 MACRO IS STM")),
            (2, ascii(0x95, "RF24mm F1.8 MACRO IS STM")),
            (1, rationals(0x920a, &[(17, 1)])),
            (1, rationals(0xa432, &[(16, 1), (35, 1), (0, 1), (0, 1)])),
            (2, shorts(2, &[0, 24, 0, 64833])),
        ] {
            let mut f = fields();
            replace(&mut f, dir, field);
            assert!(parse(&fixture(f)).is_none());
        }
        for (tag, index, value) in [(1, 22, 65535_u16), (0x93, 61, 287), (1, 23, 35), (1, 25, 0), (1, 3, 3)] {
            let mut f = fields();
            let field = f[2].iter_mut().find(|f| f.0 == tag).unwrap();
            field.3[index * 2..index * 2 + 2].copy_from_slice(&value.to_le_bytes());
            assert!(parse(&fixture(f)).is_none());
        }
    }

    #[test]
    fn rejects_other_crops_dimensions_and_dng_metadata() {
        for (dir, field) in [
            (1, shorts(0xa002, &[6000])), (1, shorts(0xa003, &[4000])),
            (2, longs(0x9a, &[0, 6720, 4480, 1, 0])),
            (2, longs(0x9a, &[1, 6720, 4480, 0, 0])),
            (2, longs(0x9a, &[0, 6000, 4000, 0, 0])),
        ] {
            let mut f = fields();
            replace(&mut f, dir, field);
            assert!(parse(&fixture(f)).is_none());
        }
        let mut f = fields();
        f[0].push(Field(50706, 1, 4, vec![1, 4, 0, 0]));
        assert!(parse(&fixture(f)).is_none());
        assert!(parse(&tiff(fields()[0].clone())).is_none());
    }

    #[test]
    fn strings_require_a_bounded_first_name_not_a_hidden_later_match() {
        let mut f = fields();
        replace(&mut f, 2, ascii(0x95, "other\0RF16mm F2.8 STM"));
        assert!(parse(&fixture(f)).is_none());
        let mut f = fields();
        replace(&mut f, 1, ascii(0xa434, "RF16mm F2.8 STM\0other"));
        assert!(parse(&fixture(f)).is_none());
        let mut f = fields();
        replace(&mut f, 2, Field(0x95, 2, LENS.len() as u32, LENS.as_bytes().to_vec()));
        assert!(parse(&fixture(f)).is_none());
        let mut f = fields();
        replace(&mut f, 2, ascii(0x95, &"x".repeat(65)));
        assert!(parse(&fixture(f)).is_none());
    }

    #[test]
    fn rejects_truncation_extent_escape_and_unbounded_values() {
        let bytes = fixture(fields());
        for end in [0, 7, 15, bytes.len() - 1] {
            assert!(parse(&bytes[..end]).is_none());
        }
        let mut fields = fields();
        replace(&mut fields, 1, Field(0xa434, 2, u32::MAX, LENS.as_bytes().to_vec()));
        assert!(parse(&fixture(fields)).is_none());
        let mut bad = bytes;
        let cmt_at = bad.windows(4).position(|p| p == b"CMT2").unwrap() - 4;
        let root = cmt_at + 8;
        // The root IFD pointer must stay inside its CMT, even when another
        // following box has enough bytes to satisfy an unchecked read.
        bad[root + 4..root + 8].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(parse(&bad).is_none());
    }

    #[test]
    fn a_valid_lens_name_in_another_cmt_cannot_satisfy_exif_identity() {
        let mut bytes = fixture(fields());
        let cmt_at = bytes.windows(4).position(|p| p == b"CMT2").unwrap() - 4;
        let root = cmt_at + 8;
        let maker_name = bytes.windows(LENS.len()).rposition(|p| p == LENS.as_bytes()).unwrap();
        let count = u16::from_le_bytes(bytes[root + 8..root + 10].try_into().unwrap()) as usize;
        let entry = (0..count).map(|i| root + 10 + i * 12).find(|&at| {
            u16::from_le_bytes(bytes[at..at + 2].try_into().unwrap()) == 0xa434
        }).unwrap();
        // Same correct string, valid file offset, wrong containing TIFF box.
        bytes[entry + 8..entry + 12].copy_from_slice(&((maker_name - root) as u32).to_le_bytes());
        assert!(parse(&bytes).is_none());
    }

    #[test]
    fn reading_and_box_budgets_are_enforced_before_io() {
        let bytes = fixture(fields());
        let mut cursor = Cursor::new(bytes.as_slice());
        let mut reader = Reader::new(&mut cursor, bytes.len() as u64);
        reader.read_limit = 16;
        assert!(reader.identity().is_none());
        assert!(reader.bytes_read <= 16);
        let mut cursor = Cursor::new(vec![0; MAX_READ_BYTES + 1]);
        let mut reader = Reader::new(&mut cursor, (MAX_READ_BYTES + 1) as u64);
        for _ in 0..MAX_READ_BYTES / MAX_SINGLE_READ {
            assert!(reader.read(0, MAX_SINGLE_READ).is_some());
        }
        assert!(reader.read(0, 1).is_none());
        assert_eq!(reader.bytes_read, MAX_READ_BYTES);
        let crowded = [bytes, atom(b"free", &[]).repeat(MAX_BOXES)].concat();
        assert!(parse(&crowded).is_none());
    }

    #[test]
    fn file_api_preserves_cursor_and_checks_actual_file_length() {
        use std::io::Write;
        let path = std::env::temp_dir().join(format!("filmroom-rf16-metadata-{}.cr3", std::process::id()));
        let bytes = fixture(fields());
        {
            let mut output = File::create(&path).unwrap();
            output.write_all(&bytes).unwrap();
        }
        let mut input = File::open(&path).unwrap();
        input.seek(SeekFrom::Start(9)).unwrap();
        assert!(canon_rf16_identity(&mut input, bytes.len() as u64).is_some());
        assert_eq!(input.stream_position().unwrap(), 9);
        assert!(canon_rf16_identity(&mut input, bytes.len() as u64 - 1).is_none());
        assert_eq!(input.stream_position().unwrap(), 9);
        {
            let mut output = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
            output.write_all(&[0xff]).unwrap();
        }
        assert!(canon_rf16_identity(&mut input, bytes.len() as u64).is_none());
        assert_eq!(input.stream_position().unwrap(), 9);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    #[ignore = "Requires FILMROOM_RF16_CR3_DIR pointing to the two public Fotopolis CR3 files"]
    fn public_eos_r_rf16_samples_have_exact_on_file_identity() {
        let dir = std::path::PathBuf::from(std::env::var_os("FILMROOM_RF16_CR3_DIR").expect("fixture directory"));
        for name in ["7D6A9423.CR3", "7D6A9561.CR3"] {
            let mut file = File::open(dir.join(name)).unwrap();
            let size = file.metadata().unwrap().len();
            let identity = canon_rf16_identity(&mut file, size).expect(name);
            assert_eq!(identity.raw_quality, 7);
            assert_eq!(identity.exif_size, [6720, 4480]);
            assert_eq!(identity.aperture, Some(2.8));
            assert_eq!(identity.focus_range_m, Some(if name == "7D6A9423.CR3" {
                [5.98, f64::INFINITY]
            } else { [1.5, 1.99] }));
            let mut reader = Reader::new(&mut file, size);
            assert_eq!(reader.identity(), Some(identity));
            assert!(reader.bytes_read < 4096);
            eprintln!("{name}: {} metadata bytes, {} boxes; {identity:?}", reader.bytes_read, reader.boxes);
        }
    }
}
