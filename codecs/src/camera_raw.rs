//! Camera RAW adapters. Admitted full sensor integers or bounded Nikon row spool; striped RGB.
//! No preview decoding, resize, filename routing, or camera-model special cases here.
use crate::{
    develop::{Admission, Budget, Development},
    dng::{Probe, Rect, Warning},
    error::{Result, err, io_error},
    tiff::{Tiff, uint},
};
use rawler::{
    RawImage, RawImageData, imgop::xyz::Illuminant, rawimage::RawPhotometricInterpretation,
    rawsource::RawSource,
};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom},
    os::unix::fs::OpenOptionsExt,
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
};
const MIB: u64 = 1024 * 1024;
pub(crate) const CAMERA_RAW_MAX_MEMORY_MIB:u64=256;
// Preserve the historical working-memory allowance and classic (Auto) row plan.
// Explicit preferences may change row count only within this same allowance.
// No discontinuity when the integer-domain base approaches 128 MiB.
fn generic_strip_capacity(memory:u64,base:u64,width:usize)->usize {
    let legacy=(128*MIB).saturating_sub(base);
    let minimum=(8+2*HALO) as u64*width as u64*96;
    let tile_cap=if legacy>=minimum {legacy}else{128*MIB-BASE};
    ((memory.saturating_sub(base).min(tile_cap)/(width as u64*96))
        .saturating_sub((2*HALO) as u64)) as usize
}

fn generic_strip_rows(memory:u64,base:u64,width:usize)->usize {generic_strip_capacity(memory,base,width).min(128)}

const BASE: u64 = 32 * MIB;
const HALO: usize = 8;
mod sony_arw6;
mod sony_bayer;
mod integer_spool;
mod sony_tiles;
mod canon_stream;
mod panasonic;
fn unsupported() -> crate::error::CodecError {
    err("UNSUPPORTED_CAMERA_RAW_VARIANT", "probe")
}
pub(crate) fn bounded<T>(stage: &'static str, f: impl FnOnce() -> rawler::Result<T>) -> Result<T> {
    let (result, exceeded) = rawler::metadata_budget::bounded(|| {
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(f))
    });
    if exceeded {
        return Err(err("MEMORY_BUDGET", stage));
    }
    result
        .map_err(|_| err("INVALID_INPUT", stage))?
        .map_err(|e| match e {
            rawler::RawlerError::RowAllocationFailed => err("MEMORY_BUDGET", stage),
            rawler::RawlerError::Unsupported { .. } => err("UNSUPPORTED_CAMERA", stage),
            _ if stage == "decode" => err("DECODE_FAILED", stage),
            _ => unsupported(),
        })
}
#[derive(Clone, PartialEq, Eq)]
struct JpegBlock {
    components: u8,
    sampling: u8,
    offset: u64,
    bytes: u64,
    width: usize,
    height: usize,
}
#[derive(Copy,Clone)]
pub(crate) enum NikonStream { He(u16), OrdinaryRows }
pub(crate) struct HeInfo { pub offset:u64,pub bytes:u64,pub kind:NikonStream,pub black:[f32;4],pub white:[f32;4],pub source:Option<RawSource>,pub file:File,pub identity:crate::nikon_he::Identity }
pub struct CameraRaw {
    pub(crate) he: Option<HeInfo>,
    pub development: Development<'static>,
    pub format: &'static str,
    pub input_bytes: u64,
    jpeg_blocks: Vec<JpegBlock>,
    decoder_extra_bytes: u64,
    source_ifd_offset: u32,
    arw1_offset: Option<u64>,
    arw6: Option<(u64, u64, u64)>,
}
fn blocks(
    t: &Tiff,
    i: &crate::tiff::Ifd,
    offset_tag: u16,
    count_tag: u16,
) -> Result<Vec<(u64, u64)>> {
    let offsets = i.get(&offset_tag).ok_or_else(unsupported)?.uints()?;
    let counts = i.get(&count_tag).ok_or_else(unsupported)?.uints()?;
    if offsets.is_empty() || offsets.len() != counts.len() || offsets.len() > 4096 {
        return Err(unsupported());
    }
    offsets
        .into_iter()
        .zip(counts)
        .map(|(o, n)| {
            if n == 0 || o as u64 + n as u64 > t.size {
                Err(err("INVALID_INPUT", "probe"))
            } else {
                Ok((o as u64, n as u64))
            }
        })
        .collect()
}
fn jpeg_shape(file: &mut File, offset: u64, bytes: u64) -> Result<JpegBlock> {
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| io_error(e, "probe"))?;
    let mut b = vec![0; bytes.min(65536) as usize];
    file.read_exact(&mut b).map_err(|e| io_error(e, "probe"))?;
    if b.get(..2) != Some(&[255, 216]) {
        return Err(unsupported());
    }
    let mut p = 2;
    while p + 4 <= b.len() {
        if b[p] != 255 {
            return Err(unsupported());
        }
        let marker = b[p + 1];
        let n = u16::from_be_bytes([b[p + 2], b[p + 3]]) as usize;
        if n < 2 || p + 2 + n > b.len() {
            return Err(unsupported());
        }
        let v = &b[p + 4..p + 2 + n];
        if marker == 0xc3 {
            if v.len() < 6
                || !matches!(v[0], 12 | 14 | 15 | 16)
                || !(1..=4).contains(&v[5])
                || v.len() != 6 + v[5] as usize * 3
                || v[6..].chunks_exact(3).enumerate().any(|(i, c)| {
                    c[2] != 0
                        || !(c[1] == 0x11 || (i == 0 && v[5] == 3 && matches!(c[1], 0x21 | 0x22)))
                })
                || (matches!(v[0], 15 | 16) && (v[5] != 3 || !matches!(v[7], 0x21 | 0x22)))
            {
                return Err(unsupported());
            }
            let width = u16::from_be_bytes([v[3], v[4]]) as usize * v[5] as usize;
            let height = u16::from_be_bytes([v[1], v[2]]) as usize;
            // Subsampled RGB frame width is measured in samples; keep the same
            // 52 MP physical-pixel cap rather than accidentally imposing 17 MP.
            let pixel_width = if v[5] == 3 && matches!(v[7], 0x21 | 0x22) { width / 3 } else { width };
            crate::validate_dimensions(pixel_width as u32, height as u32)?;
            return Ok(JpegBlock {
                components: v[5],
                sampling: v[7],
                offset,
                bytes,
                width,
                height,
            });
        }
        p += 2 + n;
    }
    Err(unsupported())
}
fn rect(r: rawler::imgop::Rect) -> Rect {
    Rect {
        x: r.p.x,
        y: r.p.y,
        w: r.d.w,
        h: r.d.h,
    }
}
fn contains(outer: Rect, inner: Rect) -> bool {
    inner.w >= 8
        && inner.h >= 8
        && inner.x >= outer.x
        && inner.y >= outer.y
        && inner
            .x
            .checked_add(inner.w)
            .zip(outer.x.checked_add(outer.w))
            .is_some_and(|(a, b)| a <= b)
        && inner
            .y
            .checked_add(inner.h)
            .zip(outer.y.checked_add(outer.h))
            .is_some_and(|(a, b)| a <= b)
}
fn descriptor(raw: &RawImage, orientation: u8) -> Result<Development<'static>> {
    crate::validate_dimensions(
        raw.width.try_into().map_err(|_| unsupported())?,
        raw.height.try_into().map_err(|_| unsupported())?,
    )?;
    let cfa_config = match (&raw.photometric, raw.cpp) {
        (RawPhotometricInterpretation::Cfa(config), 1)
            if raw.width % 2 == 0
                && raw.height % 2 == 0
                && config.cfa.width == 2
                && config.cfa.height == 2
                && matches!(config.cfa.name.as_str(), "RGGB" | "BGGR" | "GRBG" | "GBRG") =>
        {
            Some(config)
        }
        (RawPhotometricInterpretation::LinearRaw, 3) => None,
        _ => return Err(unsupported()),
    };
    let full = Rect {
        x: 0,
        y: 0,
        w: raw.width,
        h: raw.height,
    };
    let active = raw.active_area.map(rect).unwrap_or(full);
    let crop = raw.crop_area.map(rect).unwrap_or(active);
    if !contains(full, active) || !contains(active, crop) {
        return Err(unsupported());
    }
    let matrix = raw
        .color_matrix
        .get(&Illuminant::D65)
        .ok_or_else(|| err("INVALID_COLOR_METADATA", "probe"))?
        .clone();
    if matrix.len() != 9 || matrix.iter().any(|v| !v.is_finite()) {
        return Err(err("INVALID_COLOR_METADATA", "probe"));
    }
    let det = matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7])
        - matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6])
        + matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6]);
    if !det.is_finite()
        || det.abs() < 1e-8
        || raw.wb_coeffs[..3]
            .iter()
            .any(|v| !v.is_finite() || *v <= 0. || *v > 64.)
    {
        return Err(err("INVALID_COLOR_METADATA", "probe"));
    }
    // Development's CFA origin is the active rectangle, not the encoded sensor.
    let cfa = cfa_config
        .map(|config| config.cfa.shift(active.x, active.y).name)
        .unwrap_or_default();
    Ok(Development {
        width: raw.width,
        height: raw.height,
        cpp: raw.cpp,
        orientation,
        active,
        crop,
        cfa,
        wb: raw.wb_coeffs,
        matrix,
        baseline_exposure_ev: 0.,
        gain_maps: &[],
    })
}
fn sony_version(t: &Tiff) -> Result<u8> {
    let v = t
        .ifds
        .iter()
        .find_map(|i| i.get(&37500))
        .ok_or_else(unsupported)?;
    let b = &v.bytes;
    let start = if b.starts_with(b"SONY DSC ") { 12 } else { 0 };
    let n = b
        .get(start..start + 2)
        .map(|x| t.endian.u16(x) as usize)
        .ok_or_else(unsupported)?;
    if n > 256 || start + 2 + n * 12 > b.len() {
        return Err(unsupported());
    }
    for e in b[start + 2..start + 2 + n * 12].chunks_exact(12) {
        if t.endian.u16(e) == 0xb000 {
            if t.endian.u16(&e[2..]) != 1 || t.endian.u32(&e[4..]) != 4 {
                return Err(unsupported());
            }
            return Ok(e[8]);
        }
    }
    Err(unsupported())
}
impl CameraRaw {
    pub fn read(input: &Path) -> Result<Self> {
        let file = File::open(input).map_err(|e| io_error(e, "probe"))?;
        Self::read_file(input, file)
    }
    fn read_file(input: &Path, mut file: File) -> Result<Self> {
        file.seek(SeekFrom::Start(0)).map_err(|e|io_error(e,"probe"))?;
        let identity=crate::nikon_he::Identity::read(&file)?;
        let mut header = [0; 16];
        file.read_exact(&mut header)
            .map_err(|e| io_error(e, "probe"))?;
        file.seek(SeekFrom::Start(0))
            .map_err(|e| io_error(e, "probe"))?;
        let t = Tiff::read_camera(&mut file).map_err(|e| {
            if e.code == "INVALID_INPUT" {
                unsupported()
            } else {
                e
            }
        })?;
        if t.ifds[0].contains_key(&50706) {
            return Err(unsupported());
        }
        let make = t.ifds[0]
            .get(&271)
            .filter(|v| v.typ == 2)
            .map(|v| v.bytes.as_slice())
            .ok_or_else(|| err("UNSUPPORTED_FORMAT", "probe"))?;
        let make = make.strip_suffix(&[0]).unwrap_or(make).trim_ascii_end();
        let format = if make == b"Canon" && &header[8..12] == b"CR\x02\0" {
            "CR2"
        } else if make.starts_with(b"NIKON") {
            "NEF"
        } else if make == b"Panasonic" && &header[..4] == b"II\x55\0" {
            "RW2"
        } else if make == b"SONY" {
            "ARW"
        } else {
            return Err(err("UNSUPPORTED_FORMAT", "probe"));
        };
        let legacy_a100 = format == "ARW"
            && t.ifds[0]
                .get(&272)
                .is_some_and(|v| v.typ == 2 && v.bytes == b"DSLR-A100\0");
        if format == "ARW" && !legacy_a100 && !matches!(sony_version(&t)?, 3 | 4 | 5 | 6) {
            return Err(unsupported());
        }
        let mut jpeg_blocks = Vec::new();
        let mut decoder_extra_bytes = 0;
        let mut expected_dimensions = None;
        let selected;
        let mut arw1_offset = None;
        let mut arw6 = None;
        if legacy_a100 {
            selected = &t.ifds[0];
            if t.ifds.iter().any(|i| i.contains_key(&273)) {
                return Err(unsupported());
            }
            let offsets = selected.get(&330).ok_or_else(unsupported)?.uints()?;
            if offsets.len() != 1 || offsets[0] < 8 || offsets[0] as u64 >= t.size {
                return Err(unsupported());
            }
            let private = selected.get(&50740).ok_or_else(unsupported)?;
            if private.typ != 1 || private.bytes.len() != 4 {
                return Err(unsupported());
            }
            let private_offset = u32::from_le_bytes(private.bytes[..4].try_into().unwrap()) as u64;
            if private_offset < 8 || private_offset + 28 > offsets[0] as u64 {
                return Err(unsupported());
            }
            arw1_offset = Some(offsets[0] as u64);
            expected_dimensions = Some((3880, 2608));
        } else if format == "RW2" {
            selected = panasonic::sensor(&t)?;
            if uint(selected,45,None)?==8 {
                // CF2: one line pair, 65536-entry Huffman cache and bounded metadata.
                // Conservative 1MiB charge exceeds all capped plan/cache capacities.
                decoder_extra_bytes=1024*1024;
            }
            expected_dimensions = Some((uint(selected, 2, None)? as usize, uint(selected, 3, None)? as usize));
        } else if format == "CR2" {
            let mut candidates = t.ifds.iter().filter(|i| i.contains_key(&50752));
            let sensor = candidates.next().ok_or_else(unsupported)?;
            selected = sensor;
            if !sensor.contains_key(&50648)
                || candidates.next().is_some()
                || t.ifds
                    .iter()
                    .any(|i| i.contains_key(&50648) && !std::ptr::eq(i, sensor))
            {
                return Err(unsupported());
            }
            if sensor.contains_key(&256) || sensor.contains_key(&257) {
                let width = uint(sensor, 256, None)?;
                let height = uint(sensor, 257, None)?;
                crate::validate_dimensions(width, height)?;
                expected_dimensions = Some((width as usize, height as usize));
            }
            let ranges = blocks(&t, sensor, 273, 279)?;
            if ranges.len() != 1 {
                return Err(unsupported());
            }
            jpeg_blocks.push(jpeg_shape(&mut file, ranges[0].0, ranges[0].1)?);
        } else {
            let sensor = t
                .ifds
                .iter()
                .find(|i| {
                    (uint(i, 262, Some(0)).ok() == Some(32803)
                        || (format == "ARW"
                            && uint(i, 262, Some(0)).ok() == Some(6)
                            && uint(i, 277, Some(1)).ok() == Some(3)
                            && uint(i, 259, Some(0)).ok() == Some(7)))
                        && uint(i, 254, Some(0)).ok().is_some_and(|n| n & 1 == 0)
                })
                .ok_or_else(unsupported)?;
            let cpp = uint(sensor, 277, Some(1))?;
            if cpp != 1 && !(format == "ARW" && cpp == 3) {
                return Err(unsupported());
            }
            selected = sensor;
            let width = uint(sensor, 256, None)?;
            let height = uint(sensor, 257, None)?;
            expected_dimensions = Some((width as usize, height as usize));
            crate::validate_dimensions(width, height)?;
            let compression = uint(sensor, 259, None)?;
            if (format == "NEF" && !matches!(compression, 1 | 34713))
                || (format == "ARW" && !matches!(compression, 1 | 7 | 32766 | 32767))
            {
                return Err(unsupported());
            }
            if compression == 7 {
                let tw = uint(sensor, 322, None)?;
                let th = uint(sensor, 323, None)?;
                if tw != 512 || th != 512 || width % tw != 0 || height % th != 0 {
                    return Err(unsupported());
                }
                let ranges = blocks(&t, sensor, 324, 325)?;
                if ranges.len() as u64 != u64::from(width / tw) * u64::from(height / th) {
                    return Err(unsupported());
                }
                for (o, n) in ranges {
                    let j = jpeg_shape(&mut file, o, n)?;
                    if (cpp == 1
                        && (j.width != 1024
                            || j.height != 256
                            || j.components != 4
                            || j.sampling != 0x11))
                        || (cpp == 3
                            && (j.width != 1536
                                || j.height != 512
                                || j.components != 3
                                || !matches!(j.sampling, 0x21 | 0x22)))
                    {
                        return Err(unsupported());
                    }
                    jpeg_blocks.push(j);
                }
            } else {
                let ranges = blocks(&t, sensor, 273, 279)?;
                let rows = uint(sensor, 278, Some(height))?;
                if rows == 0 || (height as u64).div_ceil(rows as u64) != ranges.len() as u64 {
                    return Err(unsupported());
                }
                // Pinned NEF joins strips: only exact contiguous storage is allowed.
                if format == "NEF" && ranges.windows(2).any(|r| r[0].0 + r[0].1 != r[1].0) {
                    return Err(unsupported());
                }
                if format == "ARW" && ranges.len() != 1 {
                    return Err(unsupported());
                }
                if format == "ARW" && compression == 32766 {
                    if sony_version(&t)? != 6
                        || cpp != 1
                        || !matches!(uint(sensor, 258, None)?, 14 | 16)
                    {
                        return Err(unsupported());
                    }
                    let black = selected
                        .get(&0x7310)
                        .or_else(|| selected.get(&0x7300))
                        .ok_or_else(unsupported)?
                        .uints()?;
                    let white = selected.get(&50717).ok_or_else(unsupported)?.uints()?;
                    if !matches!(black.len(), 1 | 4)
                        || white.len() != 1
                        || white[0] == 0
                        || white[0] > 32767
                        || black.iter().any(|v| *v >= white[0])
                    {
                        return Err(err("INVALID_COLOR_METADATA", "probe"));
                    }
                    arw6 = Some((ranges[0].0, ranges[0].1, 0));
                }
                if format == "ARW"
                    && compression == 32767
                    && ranges[0].1 == u64::from(width) * u64::from(height)
                {
                    // ARW2's stored 8-bit deltas require four ordered curve knees.
                    // Validate before rawler's indexed metadata access (also in dummy).
                    let curve = sensor.get(&0x7010).ok_or_else(unsupported)?.uints()?;
                    if curve.len() != 4 {
                        return Err(unsupported());
                    }
                    let knees: Vec<_> = curve.iter().map(|v| (v >> 2) & 0xfff).collect();
                    if knees.windows(2).any(|v| v[0] > v[1]) {
                        return Err(unsupported());
                    }
                }
                if format == "NEF" {
                    let total = ranges.iter().map(|r| r.1).sum::<u64>();
                    if ranges[0].0 + total + 16 > t.size {
                        decoder_extra_bytes = total + 16;
                    }
                }
            }
        }
        // The patched metadata-only path neither reserves sensor pixels nor copies encoded blocks.
        // The bounded TIFF and Sony metadata budgets apply on this same thread.
        let source = RawSource::new_from_file(&file,input).map_err(|e| io_error(e, "probe"))?;
        let raw = bounded("probe", || rawler::decode_dummy(&source))?;
        if !matches!(&raw.data, RawImageData::Integer(v) if v.is_empty()) {
            return Err(unsupported());
        }
        let orientation = uint(&t.ifds[0], 274, Some(1))?;
        if !(1..=8).contains(&orientation) {
            return Err(unsupported());
        }
        let source_ifd_offset = t.ifd_offsets[t
            .ifds
            .iter()
            .position(|i| std::ptr::eq(i, selected))
            .ok_or_else(unsupported)?];
        if raw.source_ifd_offset != Some(source_ifd_offset) {
            return Err(unsupported());
        }
        let development = descriptor(&raw, orientation as u8)?;
        if expected_dimensions.is_some_and(|dim| dim != (raw.width, raw.height)) {
            return Err(unsupported());
        }
        if !matches!(format,"CR2"|"RW2") && !legacy_a100 {
            let compression = uint(selected, 259, None)?;
            let ranges = if compression == 7 {
                vec![]
            } else {
                blocks(&t, selected, 273, 279)?
            };
            if format == "ARW" && !matches!(compression, 7 | 32766) {
                let samples = (raw.width * raw.height) as u64;
                let expected = if compression == 1 {
                    samples * 2
                } else {
                    let bits = raw
                        .camera
                        .bps
                        .unwrap_or(uint(selected, 258, None)? as usize);
                    if !matches!(bits, 8 | 12) {
                        return Err(unsupported());
                    }
                    samples * bits as u64 / 8
                };
                if ranges[0].1 != expected {
                    return Err(unsupported());
                }
            }
            if format == "NEF" && compression == 1 {
                let bits = uint(selected, 258, None)?;
                // Verified packed 12-bit rows or unpacked 14/16-bit words only.
                // Other padding/layouts must gain explicit fixture evidence first.
                let row_bytes = match bits {
                    12 => (raw.width as u64 * 12).div_ceil(8),
                    14 | 16 => raw.width as u64 * 2,
                    _ => return Err(unsupported()),
                };
                let rows = uint(selected, 278, Some(raw.height as u32))? as usize;
                for (index, (_, bytes)) in ranges.iter().enumerate() {
                    let n = rows.min(raw.height - index * rows);
                    if *bytes != row_bytes * n as u64 {
                        return Err(unsupported());
                    }
                }
            }
        }
        if format == "CR2" {
            // Reject malformed Bayer slice geometry at metadata admission, before
            // scanning a large entropy stream or allocating any spool files.
            if raw.cpp == 1 {
                let slices = selected.get(&50752).ok_or_else(unsupported)?.uints()?;
                if slices.len() != 3 || slices[0] > 4095
                    || (slices[0] != 0 && (slices[1] == 0 || slices[2] == 0
                        || u64::from(slices[0]) * u64::from(slices[1]) + u64::from(slices[2]) != raw.width as u64)) {
                    return Err(unsupported());
                }
            }
            let j = &jpeg_blocks[0];
            if j.width * j.height != raw.width * raw.height * raw.cpp {
                return Err(unsupported());
            }
            decoder_extra_bytes = if raw.cpp == 3 {
                // Vendor RGB unslicing is in-place with one visited bit per sample.
                (raw.width * raw.height * 3).div_ceil(8) as u64
            } else {
                (raw.width * raw.height * 2) as u64
            };
        }
        if let Some((offset, count, _)) = arw6 {
            let encoded_black = selected
                .get(&0x7310)
                .or_else(|| selected.get(&0x7300))
                .ok_or_else(unsupported)?
                .uints()?;
            let encoded_white = uint(selected, 50717, None)?;
            let expected_black: Vec<f32> = (0..4)
                .map(|i| (encoded_black[i % encoded_black.len()] * 2) as f32)
                .collect();
            if raw.blacklevel.as_bayer_array().as_slice() != expected_black.as_slice()
                || raw.whitelevel.as_bayer_array() != [encoded_white as f32 * 2.; 4]
            {
                return Err(err("INVALID_COLOR_METADATA", "probe"));
            }

            let bytes = source
                .subview(offset, count)
                .map_err(|e| io_error(e, "probe"))?;
            let peak =
                arw6_bounded_prototype::bounded::plan_with_memory_limit(bytes, development.width, development.height, CAMERA_RAW_MAX_MEMORY_MIB*MIB)
                    .map_err(|e| {
                        if e.to_string().contains("memory budget") {
                            err("MEMORY_BUDGET", "admission")
                        } else {
                            err("INVALID_INPUT", "probe")
                        }
                    })?;
            arw6 = Some((offset, count, peak));
        }
        let ordinary_rows = format == "NEF" && raw.nikon_he_mode.is_none() && bounded("probe",|| {
            rawler::get_decoder(&source)?.bayer_rows_supported(&source)
        })?;
        let he = if raw.nikon_he_mode.is_some() || ordinary_rows {
            let mode=raw.nikon_he_mode.unwrap_or(0);

            if !ordinary_rows && (format != "NEF" || !matches!(mode,13|14) || uint(selected,259,None)? != 34713 || uint(selected,258,None)? != 14) {
                return Err(unsupported());
            }
            let ranges=blocks(&t,selected,273,279)?;
            if ranges.len()!=1 || raw.blacklevel.cpp!=1 || !matches!((raw.blacklevel.width,raw.blacklevel.height),(1,1)|(2,2)) || raw.blacklevel.levels.len()!=raw.blacklevel.width*raw.blacklevel.height || !matches!(raw.whitelevel.0.len(),1|4) {return Err(unsupported());}
            let black=raw.blacklevel.as_bayer_array();let white=raw.whitelevel.as_bayer_array();
            if black.iter().zip(white).any(|(b,w)|!b.is_finite()||*b<0.||!w.is_finite()||w<=*b){return Err(err("INVALID_COLOR_METADATA","probe"));}
            let bytes=source.subview(ranges[0].0,ranges[0].1).map_err(|e|io_error(e,"probe"))?;
            if !ordinary_rows {crate::nikon_he::validate_header(bytes,raw.width as u32,raw.height as u32)?;}
            decoder_extra_bytes=0;
            if identity!=crate::nikon_he::Identity::read(&file)? || identity.bytes!=t.size {return Err(err("INVALID_INPUT","probe"));}
            Some(HeInfo{offset:ranges[0].0,bytes:ranges[0].1,kind:if ordinary_rows {NikonStream::OrdinaryRows}else{NikonStream::He(mode)},black,white,source:Some(source),file,identity})
        } else { None };
        Ok(Self {
            he,
            development,
            format,
            input_bytes: t.size,
            jpeg_blocks,
            decoder_extra_bytes,
            source_ifd_offset,
            arw1_offset,
            arw6,
        })
    }
    pub fn probe(&self) -> Probe {
        let d = &self.development;
        let (w, h) = d.oriented_dim();
        Probe {
            format: self.format,
            variant: if d.cpp == 3 {
                "CAMERA_LINEAR_INTEGER"
            } else {
                "CAMERA_BAYER_INTEGER"
            },
            encoded_width: d.width as u32,
            encoded_height: d.height as u32,
            width: w as u32,
            height: h as u32,
            source_orientation: d.orientation,
            orientation_applied: true,
            active_area: d.active,
            default_crop: d.crop,
            output_orientation: 1,
            input_bytes: self.input_bytes,
            output_bytes: (w * h * 12) as u64,
            max_pixels: crate::MAX_PIXELS,
            warnings: vec![Warning {
                code: "CAMERA_RAW_BASIC_DEVELOPMENT",
                message: "基础矩阵显色，不复刻相机风格或全部镜头校正",
            }],
            has_alternate_forward_matrix: false,
            development_policy: "camera-matrix-linear-srgb-v1",
            baseline_exposure_applied_ev: 0.,
        }
    }
    pub fn admit(&self, b: &Budget) -> Result<Admission> {
        if self.he.is_some(){return crate::nikon_he::admit(self,b);}
        let memory = b
            .memory_budget_mi_b
            .checked_mul(MIB)
            .filter(|n| *n <= CAMERA_RAW_MAX_MEMORY_MIB * MIB && *n >= BASE)
            .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
        let d = &self.development;
        if let Some((_, _, coefficient_peak)) = self.arw6 {
            let decoder_peak = BASE
                .checked_add(self.input_bytes)
                .and_then(|v| v.checked_add(d.width as u64 * 256))
                .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
            let capacity = ((memory.min(128*MIB) - BASE) / (d.active.w as u64 * 96)).saturating_sub((HALO * 2) as u64) as usize;
            let rows = b.raw_batch.camera_rows(capacity.min(128), capacity, 256);
            if decoder_peak > memory || rows < 8 {
                return Err(err("MEMORY_BUDGET", "admission"));
            }
            let disk =
                coefficient_peak.max((d.width * d.height * 2) as u64 + self.probe().output_bytes);
            if disk > b.disk_budget_bytes.min(1024 * MIB) {
                return Err(err("DISK_BUDGET", "admission"));
            }
            return Ok(Admission {
                memory_budget_bytes: memory,
                estimated_peak_bytes: decoder_peak
                    .max(BASE + d.active.w as u64 * (rows + HALO * 2) as u64 * 96),
                required_disk_bytes: disk,
                strip_rows: rows as u32,
            });
        }
        if sony_bayer::matches(self) {
            // Metadata map is conservatively charged as the entire input. Decode
            // subsequently reads one compressed tile, one 512² u16 tile and two rows.
            let tile_bytes = self.jpeg_blocks.iter().map(|j| j.bytes).max().unwrap_or(0);
            let decoder_peak = BASE.checked_add(self.input_bytes)
                .and_then(|v| v.checked_add(tile_bytes))
                .and_then(|v| v.checked_add(sony_bayer::SCRATCH))
                .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
            let capacity = ((memory.min(128*MIB)-BASE)/(d.active.w as u64*96)).saturating_sub((HALO*2) as u64) as usize;
            let rows = b.raw_batch.camera_rows(capacity.min(128), capacity, 256);
            if decoder_peak > memory || rows < 8 { return Err(err("MEMORY_BUDGET", "admission")); }
            let disk = self.probe().output_bytes.checked_add((d.width*d.height*2) as u64)
                .ok_or_else(||err("DISK_BUDGET","admission"))?;
            if disk > b.disk_budget_bytes.min(1024*MIB) { return Err(err("DISK_BUDGET","admission")); }
            return Ok(Admission { memory_budget_bytes: memory,
                estimated_peak_bytes: decoder_peak.max(BASE+d.active.w as u64*(rows+HALO*2) as u64*96),
                required_disk_bytes: disk, strip_rows: rows as u32 });
        }
        if self.format == "CR2" && matches!(d.cpp, 1 | 3) {
            let row_scratch = self.jpeg_blocks.iter().map(|j| j.width as u64 * 12).max().unwrap_or(0).max(d.width as u64 * 96);
            let decoder_peak = BASE.checked_add(self.input_bytes)
                .and_then(|v| v.checked_add(row_scratch))
                .ok_or_else(||err("MEMORY_BUDGET","admission"))?;
            let capacity=((memory.min(128*MIB)-BASE)/(d.active.w as u64*96)).saturating_sub((HALO*2) as u64) as usize;
            let rows=b.raw_batch.camera_rows(capacity.min(128),capacity,256);
            if decoder_peak>memory || rows<8 {return Err(err("MEMORY_BUDGET","admission"));}
            let disk=self.probe().output_bytes.checked_add((d.width*d.height*d.cpp*2) as u64).ok_or_else(||err("DISK_BUDGET","admission"))?;
            if disk>b.disk_budget_bytes || disk>1024*MIB {return Err(err("DISK_BUDGET","admission"));}
            return Ok(Admission {memory_budget_bytes:memory,estimated_peak_bytes:decoder_peak.max(BASE+d.active.w as u64*(rows+HALO*2) as u64*96),required_disk_bytes:disk,strip_rows:rows as u32});
        }
        if self.format == "ARW" && d.cpp == 3 {
            // Integer tile1.5MiB + normalized float3MiB + RawDevelop clone/
            // ThreeColor/calibration live sets <=24MiB. No whole RGB allocation.
            let peak = BASE
                .checked_add(self.input_bytes)
                .and_then(|v| v.checked_add(sony_tiles::SCRATCH))
                .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
            if peak > memory {
                return Err(err("MEMORY_BUDGET", "admission"));
            }
            let disk = self.probe().output_bytes;
            if disk > b.disk_budget_bytes {
                return Err(err("DISK_BUDGET", "admission"));
            }
            return Ok(Admission {
                memory_budget_bytes: memory,
                estimated_peak_bytes: peak,
                required_disk_bytes: disk,
                strip_rows: sony_tiles::TILE as u32,
            });
        }
        let pixels = (d.width * d.height * d.cpp) as u64;
        // Stage-specific live allocations: one input mapping and one integer sensor.
        // CR2 unslicing adds a second sensor; NEF may copy only its EOF padded block.
        // Sony's borrowed tiled input has no whole-file copy. BASE retains decoder,
        // metadata, tile scratch, allocator and two worker-stack headroom.
        let decoder_peak = BASE
            .checked_add(self.input_bytes)
            .and_then(|n| n.checked_add(pixels * 2))
            .and_then(|n| n.checked_add(self.decoder_extra_bytes))
            .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
        let base = BASE + pixels * 2;
        if memory < decoder_peak || memory < base {
            return Err(err("MEMORY_BUDGET", "admission"));
        }
        let classic = generic_strip_rows(memory,base,d.active.w);
        let rows = b.raw_batch.camera_rows(classic,generic_strip_capacity(memory,base,d.active.w),256);
        if rows < 8 {
            return Err(err("MEMORY_BUDGET", "admission"));
        }
        let disk = self.probe().output_bytes;
        if disk > b.disk_budget_bytes {
            return Err(err("DISK_BUDGET", "admission"));
        }
        Ok(Admission {
            memory_budget_bytes: memory,
            estimated_peak_bytes: decoder_peak
                .max(base + d.active.w as u64 * (rows + HALO * 2) as u64 * 96),
            required_disk_bytes: disk,
            strip_rows: rows as u32,
        })
    }
}
fn normalized_tile(
    raw: &RawImage,
    d: &Development,
    y: usize,
    h: usize,
    cancel: &AtomicBool,
) -> Result<Vec<f32>> {
    let RawImageData::Integer(data) = &raw.data else {
        return Err(unsupported());
    };
    if d.cpp == 3 {
        if data.len() != d.width * d.height * 3
            || raw.blacklevel.cpp != 3
            || (raw.blacklevel.width, raw.blacklevel.height) != (1, 1)
            || raw.blacklevel.levels.len() != 3
            || raw.whitelevel.0.len() != 3
        {
            return Err(err("INVALID_COLOR_METADATA", "develop"));
        }
        let black = raw.blacklevel.as_vec();
        let white = raw.whitelevel.as_vec();
        if black
            .iter()
            .zip(&white)
            .any(|(b, w)| !b.is_finite() || *b < 0. || !w.is_finite() || w <= b)
        {
            return Err(err("INVALID_COLOR_METADATA", "develop"));
        }
        let mut out = Vec::with_capacity(d.active.w * h * 3);
        for sy in y..y + h {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "decode"));
            }
            for sx in d.active.x..d.active.x + d.active.w {
                for ch in 0..3 {
                    out.push(
                        (data[(sy * d.width + sx) * 3 + ch] as f32 - black[ch]).max(0.)
                            / (white[ch] - black[ch]),
                    );
                }
            }
        }
        return Ok(out);
    }
    if data.len() != d.width * d.height
        || raw.blacklevel.cpp != 1
        || !matches!(
            (raw.blacklevel.width, raw.blacklevel.height),
            (1, 1) | (2, 2)
        )
        || raw.blacklevel.levels.len() != raw.blacklevel.width * raw.blacklevel.height
        || !matches!(raw.whitelevel.0.len(), 1 | 4)
    {
        return Err(err("INVALID_COLOR_METADATA", "develop"));
    }
    let black = raw.blacklevel.as_bayer_array();
    let white = raw.whitelevel.as_bayer_array();
    if black
        .iter()
        .zip(white)
        .any(|(b, w)| !b.is_finite() || *b < 0. || !w.is_finite() || w <= *b)
    {
        return Err(err("INVALID_COLOR_METADATA", "develop"));
    }
    let mut out = Vec::with_capacity(d.active.w * h);
    for sy in y..y + h {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        for sx in d.active.x..d.active.x + d.active.w {
            let ch = (sy % 2) * 2 + sx % 2;
            out.push(
                (data[sy * d.width + sx] as f32 - black[ch]).max(0.) / (white[ch] - black[ch]),
            );
        }
    }
    Ok(out)
}
// Validate the exact variable-length ARW1 symbol stream before the padded vendor pump.
fn validate_arw1(bytes: &[u8], width: usize, height: usize, cancel: &AtomicBool) -> Result<()> {
    use rawler::pumps::{BitPump, BitPumpMSB};
    let mut pump = BitPumpMSB::new(bytes);
    let total = bytes
        .len()
        .checked_mul(8)
        .ok_or_else(|| err("INVALID_INPUT", "decode"))?;
    let mut consumed = 0usize;
    for i in 0..width * height {
        if i % 4096 == 0 && cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        let mut read = |n: u32| -> Result<u32> {
            consumed = consumed
                .checked_add(n as usize)
                .ok_or_else(|| err("INVALID_INPUT", "decode"))?;
            if consumed > total {
                return Err(err("INVALID_INPUT", "decode"));
            }
            Ok(pump.get_bits(n))
        };
        let mut len = 4 - read(2)?;
        if len == 3 && read(1)? != 0 {
            len = 0;
        } else if len == 4 {
            // Unary extension is capped at13 bits by the ARW1 decoder.
            let mut zeros = 0;
            while zeros < 13 {
                if read(1)? != 0 {
                    break;
                }
                zeros += 1;
            }
            len += zeros;
        }
        read(len)?;
    }
    Ok(())
}
struct OutputGuard<'a>(&'a Path, bool);
impl Drop for OutputGuard<'_> {
    fn drop(&mut self) {
        if !self.1 {
            let _ = std::fs::remove_file(self.0);
        }
    }
}
pub fn decode(
    input: &Path,
    output: &Path,
    b: &Budget,
    cancel: &AtomicBool,
) -> Result<(Probe, Admission)> {
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "probe"));
    }
    // Bind metadata and generic integer decode to one descriptor, not a reopened path.
    let input_file=File::open(input).map_err(|e|io_error(e,"probe"))?;
    let input_identity=crate::nikon_he::Identity::read(&input_file)?;
    let camera = CameraRaw::read_file(input,input_file.try_clone().map_err(|e|io_error(e,"probe"))?)?;
    let admission = camera.admit(b)?;
    if camera.he.is_some(){return crate::nikon_he::decode(output,camera,admission,cancel);}
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "admission"));
    }
    if camera.arw6.is_some() {
        return sony_arw6::decode(&camera, input, output, b, &admission, cancel);
    }
    if sony_bayer::matches(&camera) {
        return sony_bayer::decode(&camera, input, output, b, &admission, cancel);
    }
    let out = OpenOptions::new()
        .write(true)
        .create_new(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(output)
        .map_err(|e| io_error(e, "spool"))?;
    let mut guard = OutputGuard(output, false);
    use std::os::fd::AsRawFd;
    let rc = unsafe {
        libc::posix_fallocate(
            out.as_raw_fd(),
            0,
            camera.probe().output_bytes as libc::off_t,
        )
    };
    if rc != 0 {
        return Err(io_error(std::io::Error::from_raw_os_error(rc), "spool"));
    }
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .stack_size(1024 * 1024)
        .build()
        .map_err(|_| err("MEMORY_BUDGET", "decode"))?;
    if camera.format == "CR2" && matches!(camera.development.cpp, 1 | 3) {
        pool.install(|| canon_stream::decode(&camera,input,output,&out,&admission,cancel))?;
        if cancel.load(Ordering::Relaxed) {return Err(err("CANCELLED","spool"));}
        out.sync_all().map_err(|e|io_error(e,"spool"))?;
        guard.1=true;
        return Ok((camera.probe(),admission));
    }
    if camera.format == "ARW" && camera.development.cpp == 3 {
        pool.install(|| sony_tiles::decode(&camera, input, &out, cancel))?;
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "spool"));
        }
        out.sync_all().map_err(|e| io_error(e, "spool"))?;
        guard.1 = true;
        return Ok((camera.probe(), admission));
    }
    let raw = pool.install(|| {
        if input_identity!=crate::nikon_he::Identity::read(&input_file)? {return Err(err("INVALID_INPUT","decode"));}
        let source = RawSource::new_from_file(&input_file,input).map_err(|e| io_error(e, "decode"))?;
        if let Some(offset) = camera.arw1_offset {
            let bytes = source
                .subview_until_eof(offset)
                .map_err(|e| io_error(e, "decode"))?;
            validate_arw1(
                bytes,
                camera.development.width,
                camera.development.height,
                cancel,
            )?;
        }
        for j in &camera.jpeg_blocks {
            let bytes = source
                .subview(j.offset, j.bytes)
                .map_err(|e| io_error(e, "decode"))?;
            let bytes = if camera.format == "ARW" {
                crate::lossless::sony_aligned_jpeg(bytes)?
            } else {
                bytes
            };
            crate::lossless::validate_camera(bytes, j.width, j.height, cancel)?;
        }
        let decoded=bounded("decode", || {
            if camera.format=="RW2" {
                let decoder=rawler::get_decoder(&source)?;
                decoder.raw_image_cancel(&source,&Default::default(),&||cancel.load(Ordering::Relaxed))
            } else {rawler::decode(&source,&Default::default())}
        });
        if cancel.load(Ordering::Relaxed) {return Err(err("CANCELLED","decode"));}
        decoded
    })?; // source mapping, TIFF decoder and padded buffers are dropped before development.
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "decode"));
    }
    if input_identity!=crate::nikon_he::Identity::read(&input_file)? {return Err(err("INVALID_INPUT","decode"));}
    if raw.source_ifd_offset != Some(camera.source_ifd_offset) {
        return Err(err("INVALID_INPUT", "decode"));
    }
    let d = descriptor(&raw, camera.development.orientation)?;
    if d.cpp != camera.development.cpp
        || d.width != camera.development.width
        || d.height != camera.development.height
        || d.active.x != camera.development.active.x
        || d.active.y != camera.development.active.y
        || d.active.w != camera.development.active.w
        || d.active.h != camera.development.active.h
        || d.crop.x != camera.development.crop.x
        || d.crop.y != camera.development.crop.y
        || d.crop.w != camera.development.crop.w
        || d.crop.h != camera.development.crop.h
    {
        return Err(err("INVALID_INPUT", "decode"));
    }
    let mut first = d.crop.y;
    while first < d.crop.y + d.crop.h {
        let last = (first + admission.strip_rows as usize).min(d.crop.y + d.crop.h);
        let y = first.saturating_sub(HALO).max(d.active.y);
        let end = (last + HALO).min(d.active.y + d.active.h);
        let data = normalized_tile(&raw, &d, y, end - y, cancel)?;
        let p =
            pool.install(|| crate::develop::develop_tile_cancel(&d, y, end - y, data, cancel))?;
        crate::develop::emit(&d, &out, &p, y, first, last, cancel)?;
        first = last;
    }
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "spool"));
    }
    out.sync_all().map_err(|e| io_error(e, "spool"))?;
    guard.1 = true;
    Ok((camera.probe(), admission))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rawler::{
        CFA,
        decoders::Camera,
        rawimage::{BlackLevel, CFAConfig, WhiteLevel},
    };
    /// Offline controlled-fixture oracle: normal in-memory integer RawImage + the
    /// already-tested strip development. Never reachable from application APIs.
    #[test]
    #[ignore]
    fn arw6_file_reference_linear() {
        let input = std::env::var("ARW6_FIXTURE").unwrap();
        let integer = std::env::var("ARW6_REFERENCE_U16").unwrap();
        let output = std::env::var("ARW6_REFERENCE_LINEAR").unwrap();
        let source = RawSource::new(Path::new(&input)).unwrap();
        let mut raw = rawler::decode_dummy(&source).unwrap();
        drop(source);
        let bytes = std::fs::read(integer).unwrap();
        assert_eq!(bytes.len(), raw.width * raw.height * 2);
        raw.data = RawImageData::Integer(
            bytes
                .chunks_exact(2)
                .map(|v| u16::from_le_bytes(v.try_into().unwrap()))
                .collect(),
        );
        drop(bytes);
        let camera = CameraRaw::read(Path::new(&input)).unwrap();
        let d = descriptor(&raw, camera.development.orientation).unwrap();
        let cancel = AtomicBool::new(false);
        let out = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(output)
            .unwrap();
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(2)
            .stack_size(1024 * 1024)
            .build()
            .unwrap();
        let mut first = d.crop.y;
        while first < d.crop.y + d.crop.h {
            let last = (first + 128).min(d.crop.y + d.crop.h);
            let y = first.saturating_sub(HALO).max(d.active.y);
            let end = (last + HALO).min(d.active.y + d.active.h);
            let data = normalized_tile(&raw, &d, y, end - y, &cancel).unwrap();
            let image = pool
                .install(|| crate::develop::develop_tile_cancel(&d, y, end - y, data, &cancel))
                .unwrap();
            crate::develop::emit(&d, &out, &image, y, first, last, &cancel).unwrap();
            first = last;
        }
        out.sync_all().unwrap();
    }
    #[test]
    #[ignore]
    fn sony_bayer_reference_linear() {
        let input = std::env::var("SONY_BAYER_FIXTURE").unwrap();
        let output = std::env::var("SONY_BAYER_REFERENCE_LINEAR").unwrap();
        let source = RawSource::new(Path::new(&input)).unwrap();
        let raw = rawler::decode(&source, &rawler::decoders::RawDecodeParams::default()).unwrap();
        drop(source);
        let camera = CameraRaw::read(Path::new(&input)).unwrap();
        let d = descriptor(&raw, camera.development.orientation).unwrap();
        let cancel = AtomicBool::new(false);
        let out = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(output)
            .unwrap();
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(2)
            .stack_size(1024 * 1024)
            .build()
            .unwrap();
        let mut first = d.crop.y;
        while first < d.crop.y + d.crop.h {
            let last = (first + 128).min(d.crop.y + d.crop.h);
            let y = first.saturating_sub(HALO).max(d.active.y);
            let end = (last + HALO).min(d.active.y + d.active.h);
            let data = normalized_tile(&raw, &d, y, end - y, &cancel).unwrap();
            let image = pool
                .install(|| crate::develop::develop_tile_cancel(&d, y, end - y, data, &cancel))
                .unwrap();
            crate::develop::emit(&d, &out, &image, y, first, last, &cancel).unwrap();
            first = last;
        }
        out.sync_all().unwrap();
    }
    fn synthetic(pattern: &str, odd: bool) -> RawImage {
        let mut camera = Camera::default();
        camera.real_bps = 12;
        camera.cfa = CFA::new(pattern);
        camera.color_matrix.insert(
            Illuminant::D65,
            vec![0.7, -0.1, -0.1, -0.2, 1.1, 0.1, -0.1, 0.2, 0.7],
        );
        let photo = RawPhotometricInterpretation::Cfa(CFAConfig::new_from_camera(&camera));
        let pixels = (0..64 * 320)
            .map(|i| 100 + (i * 73 % 3500) as u16)
            .collect();
        let mut raw = RawImage::new_with_data(
            camera,
            RawImageData::Integer(pixels),
            64,
            320,
            1,
            [2., 1., 1.5, f32::NAN],
            photo,
            Some(BlackLevel::new(&[64u16, 65, 66, 67], 2, 2, 1)),
            Some(WhiteLevel::new(vec![4095])),
            false,
        );
        use rawler::imgop::{Dim2, Point};
        raw.active_area = Some(rawler::imgop::Rect::new(
            Point::new(if odd { 3 } else { 2 }, if odd { 5 } else { 4 }),
            Dim2::new(56, 304),
        ));
        raw.crop_area = Some(rawler::imgop::Rect::new(
            Point::new(7, 9),
            Dim2::new(48, 296),
        ));
        raw
    }
    #[test]
    fn strip_matches_full_development_all_bayer_phases_and_odd_active_origin() {
        let cancel = AtomicBool::new(false);
        for pattern in ["RGGB", "BGGR", "GRBG", "GBRG"] {
            for odd in [false, true] {
                let raw = synthetic(pattern, odd);
                let d = descriptor(&raw, 1).unwrap();
                let whole = normalized_tile(&raw, &d, d.active.y, d.active.h, &cancel).unwrap();
                let reference =
                    crate::develop::develop_tile_cancel(&d, d.active.y, d.active.h, whole, &cancel)
                        .unwrap();
                for rows in [17, 128] {
                    let mut first = d.crop.y;
                    while first < d.crop.y + d.crop.h {
                        let last = (first + rows).min(d.crop.y + d.crop.h);
                        let y = first.saturating_sub(HALO).max(d.active.y);
                        let end = (last + HALO).min(d.active.y + d.active.h);
                        let tile = normalized_tile(&raw, &d, y, end - y, &cancel).unwrap();
                        let got =
                            crate::develop::develop_tile_cancel(&d, y, end - y, tile, &cancel)
                                .unwrap();
                        for sy in first..last {
                            for sx in d.crop.x..d.crop.x + d.crop.w {
                                let a = got.data[(sy - y) * d.active.w + sx - d.active.x];
                                let b = reference.data
                                    [(sy - d.active.y) * d.active.w + sx - d.active.x];
                                assert_eq!(
                                    a.map(f32::to_bits),
                                    b.map(f32::to_bits),
                                    "{pattern} odd={odd} ({sx},{sy})"
                                );
                            }
                        }
                        first = last;
                    }
                }
            }
        }
    }
    fn synthetic_linear() -> RawImage {
        let mut camera = synthetic("RGGB", false).camera;
        camera.cfa = CFA::default();
        let pixels = (0..63 * 319 * 3).map(|i| (i * 97 % 65536) as u16).collect();
        let mut raw = RawImage::new_with_data(
            camera,
            RawImageData::Integer(pixels),
            63 * 3,
            319,
            3,
            [1.2, 1., 1.7, f32::NAN],
            RawPhotometricInterpretation::LinearRaw,
            Some(BlackLevel::new(&[64u16, 128, 256], 1, 1, 3)),
            Some(WhiteLevel::new(vec![12000, 14000, 16000])),
            false,
        );
        use rawler::imgop::{Dim2, Point};
        raw.active_area = Some(rawler::imgop::Rect::new(
            Point::new(3, 5),
            Dim2::new(57, 310),
        ));
        raw.crop_area = Some(rawler::imgop::Rect::new(
            Point::new(7, 9),
            Dim2::new(49, 300),
        ));
        raw
    }
    #[test]
    fn linear_strips_match_full_rawdevelop_with_channel_levels_and_highlights() {
        use rawler::imgop::develop::{Intermediate, ProcessingStep, RawDevelop};
        let cancel = AtomicBool::new(false);
        let raw = synthetic_linear();
        let mut dev = RawDevelop::default();
        dev.steps.retain(|s| {
            !matches!(
                s,
                ProcessingStep::SRgb | ProcessingStep::CropActiveArea | ProcessingStep::CropDefault
            )
        });
        let Intermediate::ThreeColor(reference) = dev.develop_intermediate(&raw).unwrap() else {
            panic!()
        };
        for orientation in 1..=8 {
            let d = descriptor(&raw, orientation).unwrap();
            assert_eq!(d.cpp, 3);
            assert!(d.cfa.is_empty());
            for rows in [17, 128] {
                let mut first = d.crop.y;
                while first < d.crop.y + d.crop.h {
                    let last = (first + rows).min(d.crop.y + d.crop.h);
                    let tile = normalized_tile(&raw, &d, first, last - first, &cancel).unwrap();
                    assert!(tile.iter().any(|v| *v > 1.)); // no premature upper clamp
                    let got =
                        crate::develop::develop_tile_cancel(&d, first, last - first, tile, &cancel)
                            .unwrap();
                    for sy in first..last {
                        for sx in d.crop.x..d.crop.x + d.crop.w {
                            let a = got.data[(sy - first) * d.active.w + sx - d.active.x];
                            let b = reference.data[sy * d.width + sx];
                            for (a, b) in a.into_iter().zip(b) {
                                assert!((a - b).abs() < 0.00001);
                            }
                        }
                    }
                    first = last;
                }
            }
        }
    }
    #[test]
    fn linear_invalid_level_vectors_and_cpp_are_rejected() {
        let mut raw = synthetic_linear();
        let d = descriptor(&raw, 1).unwrap();
        raw.whitelevel.0 = vec![16383];
        assert_eq!(
            normalized_tile(&raw, &d, 5, 10, &AtomicBool::new(false))
                .unwrap_err()
                .code,
            "INVALID_COLOR_METADATA"
        );
        raw.cpp = 1;
        assert!(descriptor(&raw, 1).is_err());
    }
    #[test]
    fn canon_bayer_stream_admits_large_sensor_without_full_integer_live_set() {
        for (width,height) in [(6384,4224),(6880,4544),(10000,5200)] {
            let mut raw=synthetic("RGGB",false);
            raw.width=width;raw.height=height;raw.data=RawImageData::Integer(vec![]);
            raw.active_area=None;raw.crop_area=None;
            let d=descriptor(&raw,1).unwrap();
            let camera=CameraRaw {he:None,development:d,format:"CR2",input_bytes:38_000_000,
                jpeg_blocks:vec![JpegBlock{components:4,sampling:0x11,offset:0,bytes:1,width:width*2,height:height/2}],
                decoder_extra_bytes:(width*height*2) as u64,source_ifd_offset:0,arw1_offset:None,arw6:None};
            let admitted=camera.admit(&Budget::default()).unwrap();
            assert!(BASE+camera.input_bytes+(width*height*4) as u64>128*MIB);
            assert!(admitted.estimated_peak_bytes<=128*MIB);
            assert_eq!(admitted.required_disk_bytes,(width*height*14) as u64);
            assert!(admitted.strip_rows>=8 && admitted.strip_rows<=128);
            assert_eq!(camera.admit(&Budget{raw_batch: crate::raw_batch::RawBatch::Standard,
                memory_budget_mi_b:257,..Budget::default()}).unwrap_err().code,"MEMORY_BUDGET");
            assert_eq!(camera.admit(&Budget{disk_budget_bytes:admitted.required_disk_bytes-1,..Budget::default()}).unwrap_err().code,"DISK_BUDGET");
        }
    }

    #[test]
    fn camera_admission_respects_requested_budget_and_256_ceiling() {
        let d = descriptor(&synthetic("RGGB", false), 1).unwrap();
        let mut c = CameraRaw {
            he: None,
            development: d,
            format: "NEF",
            input_bytes: 1024,
            jpeg_blocks: vec![],
            decoder_extra_bytes: 0,
            source_ifd_offset: 0,
            arw1_offset: None,
            arw6: None,
        };
        assert!(c.admit(&Budget::default()).unwrap().estimated_peak_bytes <= 128 * MIB);
        assert_eq!(
            c.admit(&Budget {
                raw_batch: crate::raw_batch::RawBatch::Standard,
                memory_budget_mi_b: 257,
                ..Budget::default()
            })
            .unwrap_err()
            .code,
            "MEMORY_BUDGET"
        );
        c.input_bytes = 128 * MIB;
        assert_eq!(
            c.admit(&Budget::default()).unwrap_err().code,
            "MEMORY_BUDGET"
        );
        for requested in [192,256] {
            let a=c.admit(&Budget{raw_batch: crate::raw_batch::RawBatch::Standard,
                memory_budget_mi_b:requested,..Budget::default()}).unwrap();
            assert_eq!(a.memory_budget_bytes,requested*MIB);
            assert!(a.estimated_peak_bytes<=a.memory_budget_bytes);
            assert_eq!(a.required_disk_bytes,c.probe().output_bytes);
        }
        assert_eq!(c.admit(&Budget{raw_batch: crate::raw_batch::RawBatch::Standard,
                memory_budget_mi_b:u64::MAX,..Budget::default()}).unwrap_err().code,"MEMORY_BUDGET");
        assert_eq!(CAMERA_RAW_MAX_MEMORY_MIB*MIB,arw6_bounded_prototype::bounded::MAX_MEMORY_BYTES);
    }
    #[test]
    fn sony_bayer_spool_admission_counts_integer_disk_not_ram() {
        let d=descriptor(&synthetic("RGGB",false),1).unwrap();
        let mut c=CameraRaw {he:None,development:d,format:"ARW",input_bytes:48*MIB,
            jpeg_blocks:vec![JpegBlock{components:4,sampling:0x11,offset:0,bytes:MIB,width:1024,height:256}],
            decoder_extra_bytes:0,source_ifd_offset:0,arw1_offset:None,arw6:None};
        assert!(sony_bayer::matches(&c));
        let a=c.admit(&Budget::default()).unwrap();
        assert_eq!(a.required_disk_bytes,c.probe().output_bytes+(c.development.width*c.development.height*2) as u64);
        assert_eq!(a.estimated_peak_bytes,BASE+48*MIB+MIB+sony_bayer::SCRATCH);
        assert_eq!(c.admit(&Budget{disk_budget_bytes:a.required_disk_bytes-1,..Budget::default()}).unwrap_err().code,"DISK_BUDGET");
        c.jpeg_blocks.clear(); assert!(!sony_bayer::matches(&c));
        c.arw6=Some((0,100,100)); assert!(!sony_bayer::matches(&c));
    }
    #[test]
    fn dummy_pixels_have_no_capacity_even_for_hostile_dimensions() {
        let pixels = rawler::pixarray::PixU16::new_uninit(usize::MAX, usize::MAX);
        assert_eq!(pixels.data.len(), 0);
        assert_eq!(pixels.data.capacity(), 0);
    }
    #[test]
    fn a100_entropy_zeros_truncation_and_cancellation() {
        let bytes = [0x6d, 0xb6, 0xdb, 0x6d, 0xb6, 0xdb]; //16 zero differences: 011 each
        assert!(validate_arw1(&bytes, 4, 4, &AtomicBool::new(false)).is_ok());
        assert!(validate_arw1(&bytes[..5], 4, 4, &AtomicBool::new(false)).is_err());
        assert_eq!(
            validate_arw1(&bytes, 4, 4, &AtomicBool::new(true))
                .unwrap_err()
                .code,
            "CANCELLED"
        );
    }
    #[test]
    fn entropy_lookahead_is_not_counted_as_consumed_padding() {
        use rawler::pumps::{BitPump, BitPumpMSB};
        let bytes = [0xab, 0xcd, 0xef, 0x12, 0, 0, 0, 0];
        let mut pump = BitPumpMSB::new(&bytes);
        assert_eq!(pump.remaining_bits(), 64);
        assert_eq!(pump.peek_bits(16), 0xabcd);
        assert_eq!(pump.remaining_bits(), 64);
        pump.consume_bits(5);
        assert_eq!(pump.remaining_bits(), 59);
        pump.get_bits(27);
        assert_eq!(pump.remaining_bits(), 32);
        pump.peek_bits(16); // Legal lookahead into padding; consumed stays at 32.
        assert_eq!(bytes.len() * 8 - pump.remaining_bits(), 32);
        pump.consume_bits(1);
        assert!(bytes.len() * 8 - pump.remaining_bits() > 4 * 8);
    }

    #[test]
    fn entropy_remaining_bits_handles_partial_refill_words() {
        use rawler::pumps::{BitPump, BitPumpMSB};
        assert_eq!(BitPumpMSB::new(&[]).remaining_bits(), 0);
        let three = [0xab, 0xcd, 0xef];
        let mut pump = BitPumpMSB::new(&three);
        assert_eq!(pump.peek_bits(8), 0xab);
        assert_eq!(pump.remaining_bits(), 24);
        pump.consume_bits(7);
        assert_eq!(pump.remaining_bits(), 17);
        pump.peek_bits(16);
        assert_eq!(pump.remaining_bits(), 17);
        pump.consume_bits(17);
        assert_eq!(pump.remaining_bits(), 0);
        let five = [0xab, 0xcd, 0xef, 0x12, 0x34];
        let mut pump = BitPumpMSB::new(&five);
        pump.get_bits(24);
        assert_eq!(pump.remaining_bits(), 16);
        assert_eq!(pump.peek_bits(16), 0x1234);
        assert_eq!(pump.remaining_bits(), 16);
        pump.consume_bits(9);
        assert_eq!(pump.remaining_bits(), 7);
        pump.get_bits(7);
        assert_eq!(pump.remaining_bits(), 0);
    }

    #[test]
    fn metadata_budget_is_sticky_when_tiff_parser_tolerates_tag_error() {
        let mut bytes = vec![b'I', b'I', 42, 0, 8, 0, 0, 0, 1, 0];
        bytes.extend_from_slice(&271u16.to_le_bytes());
        bytes.extend_from_slice(&2u16.to_le_bytes());
        bytes.extend_from_slice(&(8u32 * 1024 * 1024).to_le_bytes());
        bytes.extend_from_slice(&26u32.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        let (_, exceeded) = rawler::metadata_budget::bounded(|| {
            rawler::formats::tiff::GenericTiffReader::new(
                &mut std::io::Cursor::new(bytes),
                0,
                0,
                None,
                &[],
            )
        });
        assert!(exceeded);
        let (_, exceeded) = rawler::metadata_budget::bounded(|| ());
        assert!(!exceeded);
    }
    // Explicit ignored integration oracle: set paths, then run under the exclusive heavy slot.
    #[test]
    #[ignore]
    fn he_independent_cfa_full_linear_reference() {
        use std::os::unix::fs::DirBuilderExt;
        let input=std::path::PathBuf::from(std::env::var_os("HE_REFERENCE_NEF").expect("NEF path"));
        let reference=std::path::PathBuf::from(std::env::var_os("HE_REFERENCE_CFA").expect("independent CFA path"));
        // Host-only ignored oracle gate pins both inputs itself; env cannot silently substitute another source.
        for (path,expected) in [(&input,"4111fa11f86a4b3f595999ecf035078944a834ef4283df625b2bb0a968e78946"),(&reference,"eb1546abec8a945d1dc5113083bd8e64d3cdecdcca1e9b476e63ac3aa487c384")] {
            let hash=std::process::Command::new("sha256sum").arg(path).output().expect("host sha256sum");
            assert!(hash.status.success());let text=String::from_utf8(hash.stdout).unwrap();assert_eq!(text.split_whitespace().next(),Some(expected));
            println!("oracle_input_sha256={expected}");
        }
        let dir=std::env::temp_dir().join(format!("film-he-linear-reference-{}",std::process::id()));
        std::fs::DirBuilder::new().mode(0o700).create(&dir).unwrap();
        struct Cleanup(std::path::PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
        let _cleanup=Cleanup(dir.clone());let actual=dir.join("actual.linear");let expected=dir.join("expected.linear");
        let cancel=AtomicBool::new(false);
        let (probe,admission)=decode(&input,&actual,&Budget::default(),&cancel).unwrap();
        assert!(admission.estimated_peak_bytes<=128*1024*1024);
        let mut raw={let source=RawSource::new(&input).unwrap();bounded("probe",||rawler::decode_dummy(&source)).unwrap()};
        assert!(raw.nikon_he_mode.is_some());let d=descriptor(&raw,probe.source_orientation).unwrap();
        let mut f=File::open(reference).unwrap();assert_eq!(f.metadata().unwrap().len(),(d.width*d.height*2)as u64);
        let mut samples=Vec::with_capacity(d.width*d.height);let mut bytes=vec![0u8;65536];
        while samples.len()<d.width*d.height {let n=(d.width*d.height-samples.len()).min(bytes.len()/2);f.read_exact(&mut bytes[..n*2]).unwrap();samples.extend(bytes[..n*2].chunks_exact(2).map(|b|u16::from_le_bytes([b[0],b[1]])));}
        raw.data=RawImageData::Integer(samples);
        let out=OpenOptions::new().write(true).create_new(true).open(&expected).unwrap();out.set_len(probe.output_bytes).unwrap();
        let pool=rayon::ThreadPoolBuilder::new().num_threads(2).build().unwrap();let mut first=d.crop.y;
        while first<d.crop.y+d.crop.h {let last=(first+admission.strip_rows as usize).min(d.crop.y+d.crop.h);let y=first.saturating_sub(HALO).max(d.active.y);let end=(last+HALO).min(d.active.y+d.active.h);
            let normalized=normalized_tile(&raw,&d,y,end-y,&cancel).unwrap();
            let developed=pool.install(||crate::develop::develop_tile_cancel(&d,y,end-y,normalized,&cancel)).unwrap();
            crate::develop::emit(&d,&out,&developed,y,first,last,&cancel).unwrap();first=last;}
        out.sync_all().unwrap();drop(out);drop(raw);
        let mut a=File::open(actual).unwrap();let mut b=File::open(expected).unwrap();assert_eq!(a.metadata().unwrap().len(),b.metadata().unwrap().len());
        let mut left=vec![0u8;65536];let mut right=vec![0u8;65536];let mut compared=0u64;
        loop{let n=a.read(&mut left).unwrap();if n==0{break}b.read_exact(&mut right[..n]).unwrap();assert_eq!(&left[..n],&right[..n],"linear bytes differ at {compared}");compared+=n as u64;}
        assert_eq!(compared,probe.output_bytes);
    }

    // Reference intentionally uses the legacy complete Vec only in this ignored
    // host test (512 MiB test limit); production remains 128 MiB row-spooled.
    #[test]
    #[ignore]
    fn ordinary_nef_rows_full_reference() {
        use std::os::unix::fs::DirBuilderExt;
        let manifest=std::path::PathBuf::from(std::env::var_os("NEF_ROW_MANIFEST").expect("manifest"));
        let inputs:serde_json::Value=serde_json::from_slice(&std::fs::read(manifest).unwrap()).unwrap();
        for fixture in inputs.as_array().unwrap() {
            let input=Path::new(fixture["path"].as_str().unwrap());
            let hash=std::process::Command::new("sha256sum").arg(input).output().unwrap();assert!(hash.status.success());
            assert_eq!(String::from_utf8(hash.stdout).unwrap().split_whitespace().next(),fixture["sha256"].as_str());
            let dir=std::env::temp_dir().join(format!("film-nef-row-reference-{}-{}",std::process::id(),fixture["id"]));
            std::fs::DirBuilder::new().mode(0o700).create(&dir).unwrap();
            struct Cleanup(std::path::PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
            let _cleanup=Cleanup(dir.clone());let actual=dir.join("actual.linear");let expected=dir.join("expected.linear");
            let cancel=AtomicBool::new(false);
            let camera=CameraRaw::read(input).unwrap();assert!(matches!(camera.he.as_ref().unwrap().kind,NikonStream::OrdinaryRows));drop(camera);
            let (probe,admission)=decode(input,&actual,&Budget::default(),&cancel).unwrap();
            assert!(admission.estimated_peak_bytes<=128*1024*1024);
            let raw={let source=RawSource::new(input).unwrap();bounded("decode",||rawler::decode(&source,&Default::default())).unwrap()};
            let d=descriptor(&raw,probe.source_orientation).unwrap();
            let RawImageData::Integer(pixels)=&raw.data else {panic!("integer expected")};
            {let source=RawSource::new(input).unwrap();let decoder=bounded("decode",||rawler::get_decoder(&source)).unwrap();
             let mut rows=0;
             decoder.bayer_rows(&source,&mut |row,data| {assert_eq!(row,rows);assert_eq!(data,&pixels[row*d.width..(row+1)*d.width]);rows+=1;Ok(())},&||false).unwrap();assert_eq!(rows,d.height);
            }
            let out=OpenOptions::new().write(true).create_new(true).open(&expected).unwrap();out.set_len(probe.output_bytes).unwrap();
            let pool=rayon::ThreadPoolBuilder::new().num_threads(2).build().unwrap();let mut first=d.crop.y;
            while first<d.crop.y+d.crop.h {let last=(first+admission.strip_rows as usize).min(d.crop.y+d.crop.h);let y=first.saturating_sub(HALO).max(d.active.y);let end=(last+HALO).min(d.active.y+d.active.h);
                let normalized=normalized_tile(&raw,&d,y,end-y,&cancel).unwrap();
                let developed=pool.install(||crate::develop::develop_tile_cancel(&d,y,end-y,normalized,&cancel)).unwrap();
                crate::develop::emit(&d,&out,&developed,y,first,last,&cancel).unwrap();first=last;}
            out.sync_all().unwrap();drop(out);drop(raw);
            let mut a=File::open(actual).unwrap();let mut b=File::open(expected).unwrap();assert_eq!(a.metadata().unwrap().len(),b.metadata().unwrap().len());
            let mut left=vec![0u8;65536];let mut right=vec![0u8;65536];let mut compared=0u64;
            loop{let n=a.read(&mut left).unwrap();if n==0{break}b.read_exact(&mut right[..n]).unwrap();assert_eq!(&left[..n],&right[..n],"linear bytes differ at {compared}");compared+=n as u64;}
            assert_eq!(compared,probe.output_bytes);
            println!("NEF_ROW_REFERENCE id={} sensor={}x{} crop={},{},{},{} u16_samples={} linear_bytes={} PASS",fixture["id"],d.width,d.height,d.crop.x,d.crop.y,d.crop.w,d.crop.h,d.width*d.height,compared);
        }
    }

    #[test]
    #[ignore]
    fn ordinary_nef_rows_cancellation_and_sink_faults() {
        let manifest=std::path::PathBuf::from(std::env::var_os("NEF_ROW_MANIFEST").expect("manifest"));
        let fixtures:serde_json::Value=serde_json::from_slice(&std::fs::read(manifest).unwrap()).unwrap();
        let input=Path::new(fixtures[0]["path"].as_str().unwrap());
        let hash=std::process::Command::new("sha256sum").arg(input).output().unwrap();assert!(hash.status.success());assert_eq!(String::from_utf8(hash.stdout).unwrap().split_whitespace().next(),fixtures[0]["sha256"].as_str());
        let source=RawSource::new(input).unwrap();let decoder=bounded("decode",||rawler::get_decoder(&source)).unwrap();
        assert!(decoder.bayer_rows_supported(&source).unwrap());
        let mut rows=0;
        assert!(decoder.bayer_rows(&source,&mut |_,_| {rows+=1;Ok(())},&||true).is_err());assert_eq!(rows,0);
        let stop=AtomicBool::new(false);
        assert!(decoder.bayer_rows(&source,&mut |row,_| {assert_eq!(row,rows);rows+=1;if rows==2 {stop.store(true,Ordering::Relaxed);}Ok(())},&||stop.load(Ordering::Relaxed)).is_err());assert_eq!(rows,2);
        rows=0;
        assert!(decoder.bayer_rows(&source,&mut |row,_| {assert_eq!(row,rows);rows+=1;Err(rawler::RawlerError::DecoderFailed("test sink".into()))},&||false).is_err());assert_eq!(rows,1);
        let metadata=bounded("probe",||rawler::decode_dummy(&source)).unwrap();
        rows=0;decoder.bayer_rows(&source,&mut |row,data| {assert_eq!(row,rows);assert_eq!(data.len(),metadata.width);rows+=1;Ok(())},&||false).unwrap();assert_eq!(rows,metadata.height);
        use std::os::unix::fs::DirBuilderExt;
        let dir=std::env::temp_dir().join(format!("film-nef-row-fault-{}",std::process::id()));std::fs::DirBuilder::new().mode(0o700).create(&dir).unwrap();
        struct Cleanup(std::path::PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}let _cleanup=Cleanup(dir.clone());
        let mut spool=crate::he_spool::RawSpool::create_bayer(&dir.join("raw"),metadata.width as u32,metadata.height as u32).unwrap();
        assert!(spool.normalized(0,0,2,2,[0.;4],[16383.;4],&AtomicBool::new(false)).is_err());
        assert_eq!(spool.decode_rows(&*decoder,&source,&AtomicBool::new(true)).unwrap_err().kind(),std::io::ErrorKind::Interrupted);
        assert!(spool.normalized(0,0,2,2,[0.;4],[16383.;4],&AtomicBool::new(false)).is_err());
        assert_eq!(spool.decode_rows(&*decoder,&source,&AtomicBool::new(false)).unwrap_err().kind(),std::io::ErrorKind::InvalidInput);
        assert!(!dir.join("raw").exists());
        println!("NEF_ROW_FAULTS cancelled-before=0 cancelled-after=2 sink-failure=1 retry=complete invalid-spool-state=refused PASS");
    }

}

#[cfg(test)]
mod canon_bayer_stream_reference {
    use super::*;
    #[test]
    #[ignore = "offline private full-integer reference; needs CANON_BAYER_FIXTURE/STREAM_OUTPUT/REFERENCE_OUTPUT"]
    fn full_integer_strip_develop_equals_streamed_float_output() {
        let input=std::env::var("CANON_BAYER_FIXTURE").unwrap();
        let streamed=std::env::var("CANON_STREAM_OUTPUT").unwrap();
        let reference=std::env::var("CANON_REFERENCE_OUTPUT").unwrap();
        let camera=CameraRaw::read(Path::new(&input)).unwrap();
        let admission=camera.admit(&Budget::default()).unwrap();
        let raw={let source=RawSource::new(Path::new(&input)).unwrap();rawler::decode(&source,&Default::default()).unwrap()};
        let d=descriptor(&raw,camera.development.orientation).unwrap();
        assert_eq!(d.cpp,1);assert_eq!(raw.source_ifd_offset,Some(camera.source_ifd_offset));
        let out=OpenOptions::new().write(true).create_new(true).open(&reference).unwrap();
        let cancel=AtomicBool::new(false);
        let pool=rayon::ThreadPoolBuilder::new().num_threads(2).build().unwrap();
        let mut first=d.crop.y;
        while first<d.crop.y+d.crop.h {
            let last=(first+admission.strip_rows as usize).min(d.crop.y+d.crop.h);
            let y=first.saturating_sub(HALO).max(d.active.y);let end=(last+HALO).min(d.active.y+d.active.h);
            let data=normalized_tile(&raw,&d,y,end-y,&cancel).unwrap();
            let developed=pool.install(||crate::develop::develop_tile_cancel(&d,y,end-y,data,&cancel)).unwrap();
            crate::develop::emit(&d,&out,&developed,y,first,last,&cancel).unwrap();first=last;
        }
        drop(out);drop(raw);
        let mut a=File::open(&reference).unwrap();let mut b=File::open(&streamed).unwrap();
        assert_eq!(a.metadata().unwrap().len(),b.metadata().unwrap().len());
        let mut count=a.metadata().unwrap().len();let mut left=vec![0u8;1024*1024];let mut right=left.clone();
        while count>0 {let n=(count as usize).min(left.len());a.read_exact(&mut left[..n]).unwrap();b.read_exact(&mut right[..n]).unwrap();assert_eq!(&left[..n],&right[..n]);count-=n as u64;}
        std::fs::remove_file(reference).unwrap();
    }
}

#[cfg(test)] mod camera_budget_boundary_tests {
 use super::*;
 #[test] fn generic_integer_base_near_old_cap_is_monotonic_at_256() {
  for base in [127*MIB,128*MIB,129*MIB] {
   let rows=generic_strip_rows(256*MIB,base,6000);
   assert!(rows>=8 && rows<=128);
   assert!(base+(rows+2*HALO) as u64*6000*96<=256*MIB);
   assert!(generic_strip_rows(128*MIB,base,6000)<8);
  }
 }
 #[test] fn old_admitted_strip_sizes_do_not_grow() {
  for base in [32*MIB,64*MIB,96*MIB] {
   let old=generic_strip_rows(128*MIB,base,6000);assert!(old>=8);
   assert_eq!(generic_strip_rows(256*MIB,base,6000),old);
  }
 }
}
