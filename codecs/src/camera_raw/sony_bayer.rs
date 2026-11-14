//! Sony lossless Bayer: exact packed RGGB tile decode to anonymous full sensor FD.
use super::sony_arw6::codec_error;
use super::*;
use arw6_bounded_prototype::storage::Scratch;
use rawler::decompressors::ljpeg::LjpegDecompressor;
use std::{os::unix::fs::FileExt, sync::Arc};
const TILE: usize = 512;
// u16 tile (512KiB), two output rows, entropy tables/decoder headroom.
pub(super) const SCRATCH: u64 = 2 * MIB;
pub(super) fn matches(camera: &CameraRaw) -> bool {
    camera.format == "ARW" && camera.development.cpp == 1 && !camera.jpeg_blocks.is_empty()
}
pub(super) fn decode(
    camera: &CameraRaw,
    input: &Path,
    output: &Path,
    b: &Budget,
    admission: &Admission,
    cancel: &AtomicBool,
) -> Result<(Probe, Admission)> {
    let file = File::open(input).map_err(|e| io_error(e, "decode"))?;
    if file.metadata().map_err(|e| io_error(e, "decode"))?.len() != camera.input_bytes {
        return Err(err("INVALID_INPUT", "decode"));
    }
    // Reparse the block table from the FD used for metadata and tile reads.
    // An equal-length path replacement must not reuse the first probe's offsets.
    let rebound =
        CameraRaw::read_file(input, file.try_clone().map_err(|e| io_error(e, "decode"))?)?;
    if !matches(&rebound)
        || rebound.jpeg_blocks != camera.jpeg_blocks
        || rebound.source_ifd_offset != camera.source_ifd_offset
    {
        return Err(err("INVALID_INPUT", "decode"));
    }
    drop(rebound);
    let (d, black, white) = {
        // Metadata and subsequent tile reads are bound to the same opened inode.
        let source = RawSource::new_from_file(&file, input).map_err(|e| io_error(e, "decode"))?;
        let raw = bounded("decode", || rawler::decode_dummy(&source))?;
        let mut d = descriptor(&raw, camera.development.orientation)?;
        configure_profile(&source,&file,"ARW",&raw,&mut d);
        let rect = |r: Rect| (r.x, r.y, r.w, r.h);
        if raw.source_ifd_offset != Some(camera.source_ifd_offset)
            || d.cpp != 1
            || d.lens != camera.development.lens
        || d.lens_extras != camera.development.lens_extras
        || d.lens_profile != camera.development.lens_profile
            || d.width != camera.development.width
            || d.height != camera.development.height
            || rect(d.active) != rect(camera.development.active)
            || rect(d.crop) != rect(camera.development.crop)
            || raw.blacklevel.cpp != 1
            || !matches!(
                (raw.blacklevel.width, raw.blacklevel.height),
                (1, 1) | (2, 2)
            )
            || raw.blacklevel.levels.len() != raw.blacklevel.width * raw.blacklevel.height
            || !matches!(raw.whitelevel.0.len(), 1 | 4)
        {
            return Err(err("INVALID_COLOR_METADATA", "decode"));
        }
        let black = raw.blacklevel.as_bayer_array();
        let white = raw.whitelevel.as_bayer_array();
        if black
            .iter()
            .zip(white)
            .any(|(b, w)| !b.is_finite() || *b < 0. || !w.is_finite() || w <= *b)
        {
            return Err(err("INVALID_COLOR_METADATA", "decode"));
        }
        (d, black, white)
    }; // Drop metadata and its input map before reading compressed tiles.
    let cols = d.width / TILE;
    if cols == 0
        || d.width % TILE != 0
        || d.height % TILE != 0
        || camera.jpeg_blocks.len() != cols * (d.height / TILE)
    {
        return Err(err("INVALID_INPUT", "decode"));
    }
    let directory = output
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let scratch = Scratch::new(
        directory,
        b.disk_budget_bytes.min(1024 * MIB),
        Arc::new(AtomicBool::new(false)),
    )
    .map_err(|e| codec_error(e, cancel))?;
    let mosaic = decode_mosaic(camera, &file, &d, &scratch, cancel)?;
    drop(file);
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "decode"));
    }
    super::integer_spool::develop(camera, output, admission, cancel, &mosaic, &d, black, white)
}
fn decode_mosaic(
    camera: &CameraRaw,
    file: &File,
    d: &Development,
    scratch: &Scratch,
    cancel: &AtomicBool,
) -> Result<arw6_bounded_prototype::storage::AnonymousFile> {
    let cols = d.width / TILE;
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "decode"));
    }
    let mosaic = scratch
        .reserve((d.width * d.height * 2) as u64)
        .map_err(|e| codec_error(e, cancel))?;
    for (index, jpeg) in camera.jpeg_blocks.iter().enumerate() {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        let mut encoded =
            vec![0u8; usize::try_from(jpeg.bytes).map_err(|_| err("MEMORY_BUDGET", "decode"))?];
        file.read_exact_at(&mut encoded, jpeg.offset)
            .map_err(|e| io_error(e, "decode"))?;
        let bytes = crate::lossless::sony_aligned_jpeg(&encoded)?;
        crate::lossless::validate_camera(bytes, jpeg.width, jpeg.height, cancel)?;
        let decoder = LjpegDecompressor::new(bytes).map_err(|_| err("DECODE_FAILED", "decode"))?;
        if decoder.width() != 1024
            || decoder.height() != 256
            || decoder.components() != 4
            || decoder.super_h() != 1
            || decoder.super_v() != 1
        {
            return Err(err("INVALID_INPUT", "decode"));
        }
        let mut packed = vec![0u16; TILE * TILE];
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            decoder.decode(&mut packed, 0, 1024, 1024, 256, false)
        }))
        .map_err(|_| err("INVALID_INPUT", "decode"))?
        .map_err(|_| err("DECODE_FAILED", "decode"))?;
        let x = (index % cols) * TILE;
        let y = (index / cols) * TILE;
        let mut rows = [[0u8; TILE * 2]; 2];
        for (row, line) in packed.chunks_exact(1024).enumerate() {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "decode"));
            }
            unpack(line, &mut rows);
            for (dy, data) in rows.iter().enumerate() {
                mosaic
                    .write((((y + row * 2 + dy) * d.width + x) * 2) as u64, data)
                    .map_err(|e| codec_error(e, cancel))?;
            }
        }
    }
    Ok(mosaic)
}
fn unpack(line: &[u16], rows: &mut [[u8; TILE * 2]; 2]) {
    for (i, quad) in line.chunks_exact(4).enumerate() {
        for dy in 0..2 {
            for dx in 0..2 {
                let at = (i * 2 + dx) * 2;
                rows[dy][at..at + 2].copy_from_slice(&quad[dy * 2 + dx].to_le_bytes());
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    /// Offline full-image integer oracle; never part of application admission.
    #[test]
    #[ignore]
    fn sony_bayer_integer_oracle() {
        let input = std::env::var("SONY_BAYER_FIXTURE").unwrap();
        let directory = std::env::var("SONY_BAYER_SCRATCH").unwrap();
        let camera = CameraRaw::read(Path::new(&input)).unwrap();
        assert!(matches(&camera));
        let cancel = AtomicBool::new(false);
        let file = File::open(&input).unwrap();
        let scratch = Scratch::new(
            Path::new(&directory),
            1024 * MIB,
            Arc::new(AtomicBool::new(false)),
        )
        .unwrap();
        let mosaic = decode_mosaic(&camera, &file, &camera.development, &scratch, &cancel).unwrap();
        let source = RawSource::new_from_file(&file, Path::new(&input)).unwrap();
        let raw = rawler::decode(&source, &rawler::decoders::RawDecodeParams::default()).unwrap();
        let dummy=rawler::decode_dummy(&source).unwrap();
        assert_eq!(raw.blacklevel,dummy.blacklevel,"dummy must preserve exact black metadata");
        assert_eq!(raw.whitelevel,dummy.whitelevel);
        assert_eq!(raw.crop_area,dummy.crop_area);
        assert_eq!(raw.active_area,dummy.active_area);
        assert_eq!(&raw.wb_coeffs[..3],&dummy.wb_coeffs[..3]);
        let RawImageData::Integer(pixels) = raw.data else {
            panic!("integer required")
        };
        let mut row = vec![0u8; raw.width * 2];
        for (y, expected) in pixels.chunks_exact(raw.width).enumerate() {
            mosaic.read((y * row.len()) as u64, &mut row).unwrap();
            for (x, (bytes, value)) in row.chunks_exact(2).zip(expected).enumerate() {
                assert_eq!(
                    u16::from_le_bytes(bytes.try_into().unwrap()),
                    *value,
                    "x={x} y={y}"
                );
            }
        }
        println!("integer_samples_equal={}", pixels.len());
    }
    #[test]
    fn packed_rggb_rows_preserve_all_samples() {
        let line: Vec<u16> = (0..1024).collect();
        let mut rows = [[0u8; 1024]; 2];
        unpack(&line, &mut rows);
        for x in 0..512 {
            for y in 0..2 {
                assert_eq!(
                    u16::from_le_bytes(rows[y][x * 2..x * 2 + 2].try_into().unwrap()),
                    ((x / 2) * 4 + y * 2 + x % 2) as u16
                );
            }
        }
    }
}
