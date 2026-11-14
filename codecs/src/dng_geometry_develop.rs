//! Disk-backed camera-space geometry shared by DNG and vendor RAW adapters.
//! Each DNG stage completes before the next starts; no full-frame RGB allocation.
use super::*;
use std::os::fd::AsRawFd;
use std::sync::atomic::AtomicU64;

pub(super) fn backing_file(output: &Path, bytes: u64) -> Result<File> {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let parent = output.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or(Path::new("."));
    for _ in 0..16 {
        let path = parent.join(format!(".raw-camera-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        let file = match OpenOptions::new().read(true).write(true).create_new(true).mode(0o600)
            .custom_flags(libc::O_NOFOLLOW).open(&path) {
            Ok(file) => file,
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(io_error(e, "spool")),
        };
        if let Err(e) = std::fs::remove_file(&path) {
            drop(file); let _ = std::fs::remove_file(&path); return Err(io_error(e, "spool"));
        }
        let rc = unsafe { libc::posix_fallocate(file.as_raw_fd(), 0, bytes as libc::off_t) };
        if rc != 0 { return Err(io_error(io::Error::from_raw_os_error(rc), "spool")); }
        return Ok(file);
    }
    Err(err("OUTPUT_EXISTS", "spool"))
}

/// Write only the valid, non-halo part of a tile into an active-image backing.
/// Tile and bounds origins are in the same uncropped sensor coordinate system.
pub(super) fn write_region(
    file: &File, bounds: Rect, pixels: &rawler::pixarray::Color2D<f32, 3>,
    tile: Rect, rows: std::ops::Range<usize>, cancel: &AtomicBool,
) -> Result<()> {
    if pixels.width != tile.w || pixels.height != tile.h || pixels.data.len() != tile.w * tile.h {
        return Err(err("INVALID_INPUT", "develop"));
    }
    let left = tile.x.max(bounds.x);
    let right = (tile.x + tile.w).min(bounds.x + bounds.w);
    let first = tile.y.max(bounds.y).max(rows.start);
    let last = (tile.y + tile.h).min(bounds.y + bounds.h).min(rows.end);
    if left >= right || first >= last { return Ok(()); }
    let mut row = Vec::with_capacity((right - left) * 12);
    for y in first..last {
        if cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
        row.clear();
        for p in &pixels.data[(y - tile.y) * tile.w + left - tile.x..(y - tile.y) * tile.w + right - tile.x] {
            for value in p {
                if !value.is_finite() { return Err(err("INVALID_COLOR_METADATA", "develop")); }
                row.extend_from_slice(&value.to_le_bytes());
            }
        }
        write_at_all(file, &row, (((y - bounds.y) * bounds.w + left - bounds.x) * 12) as u64)
            .map_err(|e| io_error(e, "spool"))?;
    }
    Ok(())
}

pub(super) fn camera_backing(d: &Dng, reader: &Dng, input: &mut File, output: &Path, admission: &Admission,
    pool: &rayon::ThreadPool, cancel: &AtomicBool) -> Result<File> {
    let development = Development::from(d);
    let camera = backing_file(output, (d.active.w * d.active.h * 12) as u64)?;
    let mut first = d.active.y;
    while first < d.active.y + d.active.h {
        if cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
        let last = (first + admission.strip_rows as usize).min(d.active.y + d.active.h);
        let start = first.saturating_sub(HALO).max(d.active.y);
        let end = (last + HALO).min(d.active.y + d.active.h);
        let data = reader.read_tile_cancellable(input, start, end - start, cancel)?;
        let mut pixels = pool.install(|| camera_tile(&development, start, end - start, data))?;
        // Legacy pure List3 GainMap is stored separately from warp pipelines.
        // It still belongs in camera space before DefaultScale/color.
        if !d.gain_maps.is_empty(){crate::gainmap::apply(&d.gain_maps,&mut pixels.data,d.width,d.height,start,cancel)?;}
        write_region(&camera, d.active, &pixels,
            Rect { x: d.active.x, y: start, w: d.active.w, h: end - start }, first..last, cancel)?;
        first = last;
    }
    Ok(camera)
}

/// Bounded destination tiles enumerate every source footprint before reading.
/// Cache positions to avoid recalculating nonlinear transforms during sampling.
pub(super) fn resample(
    camera: &File, width: usize, height: usize,
    position: impl Fn(usize, usize, usize) -> Result<[f64; 2]>,
    identity: bool, clip_unit: bool, cancel: &AtomicBool,
    emit_tile: impl FnMut(Rect, rawler::pixarray::Color2D<f32, 3>) -> Result<()>,
) -> Result<()> {
    resample_region(camera, width, height, Rect { x: 0, y: 0, w: width, h: height },
        position, identity, clip_unit, cancel, emit_tile)
}

pub(super) fn resample_region(
    camera: &File, width: usize, height: usize, destination: Rect,
    position: impl Fn(usize, usize, usize) -> Result<[f64; 2]>,
    identity: bool, clip_unit: bool, cancel: &AtomicBool,
    mut emit_tile: impl FnMut(Rect, rawler::pixarray::Color2D<f32, 3>) -> Result<()>,
) -> Result<()> {
    if width < 5 || height < 5 { return Err(err("INVALID_INPUT", "develop")); }
    if destination.w == 0 || destination.h == 0
        || destination.x.checked_add(destination.w).is_none_or(|v| v > width)
        || destination.y.checked_add(destination.h).is_none_or(|v| v > height) {
        return Err(err("INVALID_INPUT", "develop"));
    }
    let kernel = crate::dng_warp::CubicWeights::new();
    for y in (destination.y..destination.y + destination.h).step_by(32) {
        for x in (destination.x..destination.x + destination.w).step_by(128) {
        if cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
        let tw = 128.min(destination.x + destination.w - x);
        let th = 32.min(destination.y + destination.h - y);
        let mut left = width; let mut top = height; let mut right = 0; let mut bottom = 0;
        let mut positions = Vec::with_capacity(tw * th);
        for yy in y..y + th {
            if cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
            for xx in x..x + tw {
                let mut p = [[0.; 2]; 3];
                for (channel, point) in p.iter_mut().enumerate() {
                    *point = if identity { [xx as f64, yy as f64] } else { position(xx, yy, channel)? };
                    let [sx, sy] = *point;
                    if !sx.is_finite() || !sy.is_finite() || sx < 0. || sy < 0.
                        || sx > (width - 1) as f64 || sy > (height - 1) as f64 {
                        return Err(err("INVALID_INPUT", "develop"));
                    }
                    let (ax, ay, taps) = if identity { (xx, yy, 1) } else {
                        ((sx.floor() as isize - 1).clamp(0, width as isize - 5) as usize,
                         (sy.floor() as isize - 1).clamp(0, height as isize - 5) as usize, 4)
                    };
                    left = left.min(ax); top = top.min(ay); right = right.max(ax + taps); bottom = bottom.max(ay + taps);
                }
                positions.push(p);
            }
        }
        let sw = right - left; let sh = bottom - top;
        let bytes = sw.checked_mul(sh).and_then(|n| n.checked_mul(12)).filter(|n| *n <= 8 * 1024 * 1024)
            .ok_or_else(|| err("MEMORY_BUDGET", "develop"))?;
        let mut window = vec![0u8; bytes];
        for yy in top..bottom {
            if cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
            camera.read_exact_at(&mut window[(yy - top) * sw * 12..(yy - top + 1) * sw * 12], ((yy * width + left) * 12) as u64)
                .map_err(|e| io_error(e, "develop"))?;
        }
        let source = |sx: usize, sy: usize, channel: usize| -> Result<f32> {
            if sx < left || sx >= right || sy < top || sy >= bottom { return Err(err("INVALID_INPUT", "develop")); }
            let i = ((sy - top) * sw + sx - left) * 12 + channel * 4;
            let value = f32::from_le_bytes(window[i..i + 4].try_into().unwrap());
            if !value.is_finite() { return Err(err("INVALID_COLOR_METADATA", "develop")); }
            Ok(value)
        };
        let mut pixels = Vec::with_capacity(tw * th);
        for (i, positions) in positions.into_iter().enumerate() {
            if i % tw == 0 && cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
            let mut pixel = [0f32; 3];
            for channel in 0..3 {
                pixel[channel] = if identity { source(x + i % tw, y + i / tw, channel)? } else {
                    crate::dng_warp::sample_position(&kernel, width, height, positions[channel], channel, clip_unit, &source)?
                };
            }
            pixels.push(pixel);
        }
        drop(window);
        emit_tile(Rect { x, y, w: tw, h: th }, rawler::pixarray::Color2D::new_with(pixels, tw, th))?;
    }}
    Ok(())
}

pub(super) fn map_stage(source: &File, width: usize, height: usize, map: &crate::gainmap::GainMap, cancel: &AtomicBool,
    mut emit_tile: impl FnMut(Rect, rawler::pixarray::Color2D<f32, 3>) -> Result<()>) -> Result<()> {
    for y in (0..height).step_by(32) { for x in (0..width).step_by(128) {
        if cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
        let tw = 128.min(width - x); let th = 32.min(height - y);
        let mut bytes = vec![0; tw * th * 12];
        for row in 0..th {
            source.read_exact_at(&mut bytes[row * tw * 12..(row + 1) * tw * 12], (((y + row) * width + x) * 12) as u64)
                .map_err(|e| io_error(e, "develop"))?;
        }
        let mut pixels: Vec<[f32; 3]> = bytes.chunks_exact(12).map(|p| std::array::from_fn(|c| f32::from_le_bytes(p[c * 4..c * 4 + 4].try_into().unwrap()))).collect();
        drop(bytes);
        map.apply_region(&mut pixels, width, height, x, y, tw, cancel)?;
        emit_tile(Rect { x, y, w: tw, h: th }, rawler::pixarray::Color2D::new_with(pixels, tw, th))?;
    }}
    Ok(())
}

pub(super) fn decode(d: &Dng, reader: &Dng, input: &mut File, out: &File, output: &Path,
    admission: &Admission, pool: &rayon::ThreadPool, cancel: &AtomicBool) -> Result<()> {
    let pipeline = d.warp.as_ref().ok_or_else(|| err("UNSUPPORTED_DNG_OPCODE", "develop"))?;
    if pipeline.steps.is_empty() { return Err(err("UNSUPPORTED_DNG_OPCODE", "develop")); }
    let development = Development::from(d);
    let mut camera = camera_backing(d, reader, input, output, admission, pool, cancel)?;
    let bounds = Rect { x: 0, y: 0, w: d.active.w, h: d.active.h };
    let mut spare = if pipeline.backing_count() > 1 { Some(backing_file(output, (bounds.w * bounds.h * 12) as u64)?) } else { None };
    for (index, stage) in pipeline.steps.iter().enumerate() {
        let last = index + 1 == pipeline.steps.len();
        let emit_stage = |tile: Rect, pixels: rawler::pixarray::Color2D<f32, 3>| -> Result<()> {
            if last {
                let pixels = calibrate_camera(d, pixels, cancel)?;
                emit_region(&development, out, &pixels,
                    Rect { x: tile.x + d.active.x, y: tile.y + d.active.y, ..tile }, cancel)
            } else {
                write_region(spare.as_ref().ok_or_else(|| err("INVALID_INPUT", "develop"))?, bounds,
                    &pixels, tile, tile.y..tile.y + tile.h, cancel)
            }
        };
        match stage {
            crate::dng_warp::Stage3Opcode::Warp(warp) => resample(&camera, bounds.w, bounds.h,
                |x, y, channel| warp.source_position(bounds.w, bounds.h, x, y, channel),
                warp.is_identity(), true, cancel, emit_stage)?,
            crate::dng_warp::Stage3Opcode::GainMap(map) => map_stage(&camera, bounds.w, bounds.h, map, cancel, emit_stage)?,
        }
        if !last { std::mem::swap(&mut camera, spare.as_mut().unwrap()); }
    }
    Ok(())
}

#[cfg(test)]mod real_tests {
    use super::*;
    #[test]
    #[ignore="Real public Q2 reference export requires explicit paths"]
    fn real_q2_export_camera_reference() {
        let input=std::path::PathBuf::from(std::env::var("FILM_Q2_SOURCE").unwrap());
        let output=std::path::PathBuf::from(std::env::var("FILM_Q2_CAMERA").unwrap());
        let mut source=File::open(&input).unwrap();let d=Dng::read(&mut source).unwrap();
        let mut budget=Budget::default();budget.disk_budget_bytes=2*1024*1024*1024;
        let admission=admit(&d,&budget).unwrap();
        let pool=rayon::ThreadPoolBuilder::new().num_threads(2).build().unwrap();
        let mut file=camera_backing(&d,&d,&mut source,&output,&admission,&pool,&AtomicBool::new(false)).unwrap();
        let mut dest=OpenOptions::new().write(true).create_new(true).open(&output).unwrap();
        std::io::Seek::seek(&mut file,std::io::SeekFrom::Start(0)).unwrap();
        assert_eq!(std::io::copy(&mut file,&mut dest).unwrap(),(d.active.w*d.active.h*12)as u64);
        println!("camera reference {}x{}",d.active.w,d.active.h);
    }
}
