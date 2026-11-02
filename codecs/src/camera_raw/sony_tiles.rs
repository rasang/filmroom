//! Independent Sony LinearRaw tiles: bounded full-quality decode, no whole RGB image.
use super::*;
use rawler::decompressors::ljpeg::LjpegDecompressor;
use rawler::imgop::yuv::{interpolate_yuv, ycbcr_to_rgb};

pub(super) const TILE: usize = 512;
pub(super) const SCRATCH: u64 = (TILE * TILE * 96) as u64;

pub(super) fn decode(
    camera: &CameraRaw,
    input: &Path,
    out: &File,
    cancel: &AtomicBool,
) -> Result<()> {
    let source = RawSource::new(input).map_err(|e| io_error(e, "decode"))?;
    let raw = bounded("probe", || rawler::decode_dummy(&source))?;
    let d = descriptor(&raw, camera.development.orientation)?;
    let rect_tuple = |r: Rect| (r.x, r.y, r.w, r.h);
    if raw.source_ifd_offset != Some(camera.source_ifd_offset)
        || d.cpp != 3
        || d.width != camera.development.width
        || d.height != camera.development.height
        || rect_tuple(d.active) != rect_tuple(camera.development.active)
        || rect_tuple(d.crop) != rect_tuple(camera.development.crop)
        || raw.blacklevel.cpp != 3
        || (raw.blacklevel.width, raw.blacklevel.height) != (1, 1)
        || raw.blacklevel.levels.len() != 3
        || raw.whitelevel.0.len() != 3
    {
        return Err(err("INVALID_COLOR_METADATA", "decode"));
    }
    let black = raw.blacklevel.as_vec();
    let white = raw.whitelevel.as_vec();
    if black
        .iter()
        .zip(&white)
        .any(|(b, w)| !b.is_finite() || *b < 0. || !w.is_finite() || w <= b)
    {
        return Err(err("INVALID_COLOR_METADATA", "decode"));
    }
    drop(raw);
    let columns = d.width / TILE;
    if columns == 0
        || d.width % TILE != 0
        || d.height % TILE != 0
        || camera.jpeg_blocks.len() != columns * (d.height / TILE)
    {
        return Err(err("INVALID_INPUT", "decode"));
    }
    // Sequential tiles, even inside a two-worker pool: no parallel full-tile live sets.
    for (index, jpeg) in camera.jpeg_blocks.iter().enumerate() {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        let bytes = source
            .subview(jpeg.offset, jpeg.bytes)
            .map_err(|e| io_error(e, "decode"))?;
        let bytes = crate::lossless::sony_aligned_jpeg(bytes)?;
        crate::lossless::validate_camera(bytes, jpeg.width, jpeg.height, cancel)?;
        let decoder = LjpegDecompressor::new(bytes).map_err(|_| err("DECODE_FAILED", "decode"))?;
        if decoder.width() != TILE * 3
            || decoder.height() != TILE
            || decoder.components() != 3
            || decoder.super_h() != 2
            || !matches!(decoder.super_v(), 1 | 2)
        {
            return Err(err("INVALID_INPUT", "decode"));
        }
        let mut pixels = vec![0u16; TILE * TILE * 3];
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            decoder.decode_sony(&mut pixels, 0, TILE * 3, TILE * 3, TILE, false)
        }))
        .map_err(|_| err("INVALID_INPUT", "decode"))?
        .map_err(|_| err("DECODE_FAILED", "decode"))?;
        // Interpolate the entire encoded tile before any crop. This is identical
        // to vendor decode_ljpeg's per-tile operation, including its edge policy.
        interpolate_yuv(
            decoder.super_h(),
            decoder.super_v(),
            TILE * 3,
            TILE,
            &mut pixels,
        );
        ycbcr_to_rgb(&mut pixels);
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        let normalized: Vec<f32> = pixels
            .iter()
            .enumerate()
            .map(|(i, v)| {
                let c = i % 3;
                (*v as f32 - black[c]).max(0.) / (white[c] - black[c])
            })
            .collect();
        drop(pixels);
        let tile = Rect {
            x: (index % columns) * TILE,
            y: (index / columns) * TILE,
            w: TILE,
            h: TILE,
        };
        let mut tile_d = d.clone();
        tile_d.active = tile;
        let developed =
            crate::develop::develop_tile_cancel(&tile_d, tile.y, TILE, normalized, cancel)?;
        crate::develop::emit_region(&d, out, &developed, tile, cancel)?;
    }
    Ok(())
}
