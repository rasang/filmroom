//! Strip adaptation of pinned RawDevelop, with no full-image RGB allocation.
//! Rawler retains its own LGPL-2.1 license; no SRgb transfer step is applied.
use crate::{
    dng::{Dng, Probe, Rect},
    error::{Result, err, io_error},
};
use rawler::{
    CFA, RawImage, RawImageData,
    decoders::Camera,
    imgop::{
        develop::{Intermediate, ProcessingStep, RawDevelop},
        xyz::Illuminant,
    },
    rawimage::{BlackLevel, CFAConfig, RawPhotometricInterpretation, WhiteLevel},
};
use serde::{Deserialize, Serialize};
use std::{
    fs::{File, OpenOptions},
    io,
    os::unix::fs::{FileExt, OpenOptionsExt},
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
};
const MIB: u64 = 1024 * 1024;
const BASE: u64 = 32 * MIB;
const HALO: usize = 8;
#[path="dng_geometry_develop.rs"]
mod geometry;
#[path="camera_geometry.rs"]
mod camera_geometry;
pub(crate) use camera_geometry::{CameraRgbSink, admit_geometry};
#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Budget {
    pub memory_budget_mi_b: u64,
    pub disk_budget_bytes: u64,
    #[serde(default)]
    pub raw_batch: crate::raw_batch::RawBatch,
}
impl Default for Budget {
    fn default() -> Self {
        Self {
            memory_budget_mi_b: 128,
            disk_budget_bytes: 1024 * MIB,
            raw_batch: crate::raw_batch::RawBatch::Auto,
        }
    }
}
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Admission {
    pub memory_budget_bytes: u64,
    pub estimated_peak_bytes: u64,
    pub required_disk_bytes: u64,
    pub strip_rows: u32,
}
pub fn admit(d: &Dng, b: &Budget) -> Result<Admission> {
    let memory = b
        .memory_budget_mi_b
        .checked_mul(MIB)
        .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
    if memory > 4096 * MIB
        || memory < BASE
        || (d.compression == 52546 && memory > crate::jxl::MAX_BUDGET)
    {
        return Err(err("MEMORY_BUDGET", "admission"));
    }
    let base = BASE
        .checked_add(d.decode_scratch_bytes())
        .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
    if memory < base {
        return Err(err("MEMORY_BUDGET", "admission"));
    }
    let rows = ((memory - base) / (d.active.w as u64 * 96)) as usize;
    let capacity = rows.saturating_sub(HALO * 2);
    let classic = capacity.min(128);
    // Automatic growth is validated for Bayer DNG. LinearRaw retains its classic
    // default; explicit Large may still request a larger memory-bounded batch.
    let rows = if d.cpp == 1 {
        b.raw_batch.dng_rows(classic, capacity)
    } else {
        b.raw_batch.camera_rows(classic, capacity, 256)
    };
    if rows < 8 {
        return Err(err("MEMORY_BUDGET", "admission"));
    }
    let disk = d.probe().output_bytes
        + d.warp.as_ref().map_or(0, |pipeline| (d.active.w*d.active.h*12) as u64 * pipeline.backing_count())
        + if d.compression == 52546 {
            crate::jxl::spool_bytes(d)
        } else if d.cfa_spool() {
            (d.width * d.height * 2) as u64
        } else {
            0
        };
    if b.disk_budget_bytes < disk {
        return Err(err("DISK_BUDGET", "admission"));
    }
    Ok(Admission {
        memory_budget_bytes: memory,
        estimated_peak_bytes: base + d.active.w as u64 * (rows + HALO * 2) as u64 * 96,
        required_disk_bytes: disk,
        strip_rows: rows as u32,
    })
}
/// Format-neutral, already validated sensor-to-linear development contract.
/// Storage decoding and black/white normalization belong to the input adapter.
#[derive(Clone, Debug)]
pub struct Development<'a> {
    pub width: usize,
    pub height: usize,
    pub cpp: usize,
    pub orientation: u8,
    pub active: crate::dng::Rect,
    pub crop: crate::dng::Rect,
    pub cfa: String,
    pub wb: [f32; 4],
    pub matrix: Vec<f32>,
    pub baseline_exposure_ev: f32,
    pub gain_maps: &'a [crate::gainmap::GainMap],
    /// Exactly one validated distortion source: embedded metadata or a qualified
    /// database fallback. Its reference rectangle is explicit below.
    pub lens: crate::embedded_lens::LensStatus,
    /// Optical normalization rectangle, independent of subsequent output crop.
    pub lens_reference: Rect,
}
impl Development<'_> {
    pub fn oriented_dim(&self) -> (usize, usize) {
        if self.orientation >= 5 {
            (self.crop.h, self.crop.w)
        } else {
            (self.crop.w, self.crop.h)
        }
    }
}
impl<'a> From<&'a Dng> for Development<'a> {
    fn from(d: &'a Dng) -> Self {
        Self {
            width: d.width,
            height: d.height,
            cpp: d.cpp,
            orientation: d.orientation,
            active: d.active,
            crop: d.crop,
            cfa: d.cfa.clone(),
            wb: d.wb,
            matrix: d.matrix.clone(),
            baseline_exposure_ev: d.baseline_exposure_ev,
            gain_maps: &d.gain_maps,
            lens: crate::embedded_lens::LensStatus::Missing,
            lens_reference: d.active,
        }
    }
}
fn tile_raw(d: &Development, y: usize, h: usize, data: Vec<f32>) -> RawImage {
    let mut cam = Camera::default();
    cam.real_bps = 16;
    cam.cfa = if d.cpp == 1 {
        CFA::new(&d.cfa).shift(0, y - d.active.y)
    } else {
        CFA::default()
    };
    if !d.matrix.is_empty() { cam.color_matrix.insert(Illuminant::D65, d.matrix.clone()); }
    let photo = if d.cpp == 1 {
        RawPhotometricInterpretation::Cfa(CFAConfig::new_from_camera(&cam))
    } else {
        RawPhotometricInterpretation::LinearRaw
    };
    RawImage::new_with_data(
        cam,
        RawImageData::Float(data),
        d.active.w * d.cpp,
        h,
        d.cpp,
        d.wb,
        photo,
        Some(BlackLevel::zero(1, 1, d.cpp)),
        Some(WhiteLevel::new(vec![1; d.cpp])),
        false,
    )
}
pub fn develop_tile(
    d: &Dng,
    y: usize,
    h: usize,
    data: Vec<f32>,
) -> Result<rawler::pixarray::Color2D<f32, 3>> {
    develop_dng_tile_cancel(
        d,
        &Development::from(d),
        y,
        h,
        data,
        &AtomicBool::new(false),
    )
}
fn develop_dng_tile_cancel(
    d: &Dng,
    development: &Development,
    y: usize,
    h: usize,
    data: Vec<f32>,
    cancel: &AtomicBool,
) -> Result<rawler::pixarray::Color2D<f32, 3>> {
    if d.warp.is_some() { return Err(err("UNSUPPORTED_DNG_OPCODE","develop")); }
    if d.cfa_color.is_some() {
        let camera=camera_tile(development,y,h,data)?;
        return calibrate_camera(d,camera,cancel);
    }
    if let Some(profile) = &d.profile {
        profile.develop(d.active, y, h, data, d.baseline_exposure_ev, cancel)
    } else {
        develop_tile_cancel(development, y, h, data, cancel)
    }
}

fn camera_tile(d:&Development,y:usize,h:usize,data:Vec<f32>)->Result<rawler::pixarray::Color2D<f32,3>> {
    let raw=tile_raw(d,y,h,data);
    let dev=RawDevelop {steps:vec![ProcessingStep::Demosaic]};
    match dev.develop_intermediate(&raw).map_err(|_|err("DECODE_FAILED","develop"))? {
        Intermediate::ThreeColor(p)=>pix_finite(p),
        _=>Err(err("UNSUPPORTED_DNG_VARIANT","develop")),
    }
}
/// Calibrate an already demosaiced camera RGB tile without repeating geometry,
/// black/white normalization, cropping, or the exposure baseline.
fn calibrate_camera_tile(d:&Development,camera:rawler::pixarray::Color2D<f32,3>,cancel:&AtomicBool)->Result<rawler::pixarray::Color2D<f32,3>> {
    let mut linear=d.clone();
    linear.cpp=3;linear.width=camera.width;linear.height=camera.height;
    linear.active=Rect{x:0,y:0,w:camera.width,h:camera.height};
    linear.crop=linear.active;linear.gain_maps=&[];
    linear.lens=crate::embedded_lens::LensStatus::Missing;
    linear.lens_reference=linear.active;
    develop_tile_cancel(&linear,0,camera.height,camera.data.into_iter().flatten().collect(),cancel)
}
fn calibrate_camera(d:&Dng,mut camera:rawler::pixarray::Color2D<f32,3>,cancel:&AtomicBool)->Result<rawler::pixarray::Color2D<f32,3>> {
    if let Some(color)=&d.cfa_color {
        let xyz_to_rgb=rawler::imgop::xyz::XYZ_TO_SRGB_D50.map(|r|r.map(|x|x as f64));
        let transform=crate::dng_color_spec::product(xyz_to_rgb,color.camera_to_pcs);
        let exposure=d.baseline_exposure_ev.exp2() as f64;
        for (i,p) in camera.data.iter_mut().enumerate() {
            if i%1024==0 && cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","develop"));}
            let input=p.map(|x|x.clamp(0.,1.)as f64);
            *p=crate::dng_color_spec::apply(transform,input).map(|x|(x*exposure) as f32);
        }
        pix_finite(camera)
    } else {
        calibrate_camera_tile(&Development::from(d),camera,cancel)
    }
}

pub(crate) fn develop_tile_cancel(
    d: &Development,
    y: usize,
    h: usize,
    data: Vec<f32>,
    cancel: &AtomicBool,
) -> Result<rawler::pixarray::Color2D<f32, 3>> {
    let mut raw = tile_raw(d, y, h, data);
    let mut dev = RawDevelop::default();
    dev.steps.retain(|s| {
        !matches!(
            s,
            ProcessingStep::SRgb
                | ProcessingStep::Rescale
                | ProcessingStep::CropActiveArea
                | ProcessingStep::CropDefault
        )
    });
    if !d.gain_maps.is_empty() {
        let mut demosaic = dev.clone();
        demosaic
            .steps
            .retain(|s| !matches!(s, ProcessingStep::Calibrate | ProcessingStep::WhiteBalance));
        let mut camera = match demosaic
            .develop_intermediate(&raw)
            .map_err(|_| err("DECODE_FAILED", "develop"))?
        {
            Intermediate::ThreeColor(p) => p,
            _ => return Err(err("UNSUPPORTED_DNG_OPCODE", "develop")),
        };
        crate::gainmap::apply(&d.gain_maps, &mut camera.data, d.width, d.height, y, cancel)?;
        raw.data = RawImageData::Float(camera.data.into_iter().flatten().collect());
        raw.cpp = 3;
        raw.photometric = RawPhotometricInterpretation::LinearRaw;
        dev.steps = vec![ProcessingStep::WhiteBalance, ProcessingStep::Calibrate];
    }
    let pix = dev
        .develop_intermediate(&raw)
        .map_err(|_| err("DECODE_FAILED", "develop"))?;
    match pix {
        Intermediate::ThreeColor(mut p) => {
            // RawImage above is built from sensor/color fields, without file
            // exposure metadata. Apply the DNG exposure baseline exactly once
            // in linear RGB, before the separate user/film exposure controls.
            let gain = d.baseline_exposure_ev.exp2();
            if gain != 1. {
                for pixel in &mut p.data {
                    for value in pixel {
                        *value *= gain;
                    }
                }
            }
            Ok(pix_finite(p)?)
        }
        _ => Err(err("UNSUPPORTED_DNG_VARIANT", "develop")),
    }
}
fn pix_finite(p: rawler::pixarray::Color2D<f32, 3>) -> Result<rawler::pixarray::Color2D<f32, 3>> {
    if p.data.iter().flatten().any(|v| !v.is_finite()) {
        return Err(err("INVALID_COLOR_METADATA", "develop"));
    }
    Ok(p)
}
fn write_at_all(f: &File, mut buf: &[u8], mut offset: u64) -> io::Result<()> {
    while !buf.is_empty() {
        let n = f.write_at(buf, offset)?;
        if n == 0 {
            return Err(io::ErrorKind::WriteZero.into());
        }
        buf = &buf[n..];
        offset += n as u64;
    }
    Ok(())
}
pub fn mapped(x: usize, y: usize, w: usize, h: usize, o: u8) -> (usize, usize) {
    match o {
        1 => (x, y),
        2 => (w - 1 - x, y),
        3 => (w - 1 - x, h - 1 - y),
        4 => (x, h - 1 - y),
        5 => (y, x),
        6 => (h - 1 - y, x),
        7 => (h - 1 - y, w - 1 - x),
        8 => (y, w - 1 - x),
        _ => unreachable!(),
    }
}
pub(crate) fn emit(
    d: &Development,
    out: &File,
    p: &rawler::pixarray::Color2D<f32, 3>,
    tile_y: usize,
    first: usize,
    last: usize,
    cancel: &AtomicBool,
) -> Result<()> {
    let crop = d.crop;
    let (ow, _) = d.oriented_dim();
    let get = |x: usize, y: usize| p.data[(y - tile_y) * d.active.w + (x - d.active.x)];
    if d.orientation <= 4 {
        let mut row = Vec::with_capacity(crop.w * 12);
        for y in first..last {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "spool"));
            }
            row.clear();
            for x in 0..crop.w {
                let sx = if d.orientation == 2 || d.orientation == 3 {
                    crop.w - 1 - x
                } else {
                    x
                };
                for c in get(crop.x + sx, y) {
                    row.extend_from_slice(&c.to_le_bytes());
                }
            }
            let (_, oy) = mapped(0, y - crop.y, crop.w, crop.h, d.orientation);
            write_at_all(out, &row, (oy * ow * 12) as u64).map_err(|e| io_error(e, "spool"))?;
        }
    } else {
        let mut line = Vec::with_capacity((last - first) * 12);
        for x in 0..crop.w {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "spool"));
            }
            line.clear();
            let reverse = d.orientation == 6 || d.orientation == 7;
            for i in 0..last - first {
                let y = if reverse { last - 1 - i } else { first + i };
                for c in get(crop.x + x, y) {
                    line.extend_from_slice(&c.to_le_bytes());
                }
            }
            let sy = if reverse { last - 1 } else { first };
            let (ox, oy) = mapped(x, sy - crop.y, crop.w, crop.h, d.orientation);
            write_at_all(out, &line, ((oy * ow + ox) * 12) as u64)
                .map_err(|e| io_error(e, "spool"))?;
        }
    }
    Ok(())
}
struct OutputGuard<'a> {
    path: &'a Path,
    keep: bool,
}
impl Drop for OutputGuard<'_> {
    fn drop(&mut self) {
        if !self.keep {
            let _ = std::fs::remove_file(self.path);
        }
    }
}
pub fn decode(
    input: &Path,
    output: &Path,
    b: &Budget,
    cancel: &AtomicBool,
) -> Result<(Probe, Admission)> {
    decode_with_reserve(input, output, b, cancel, |out, size| {
        use std::os::fd::AsRawFd;
        let rc = unsafe { libc::posix_fallocate(out.as_raw_fd(), 0, size as libc::off_t) };
        if rc == 0 {
            Ok(())
        } else {
            Err(io::Error::from_raw_os_error(rc))
        }
    })
}
fn decode_with_reserve(
    input: &Path,
    output: &Path,
    b: &Budget,
    cancel: &AtomicBool,
    reserve: impl FnOnce(&File, u64) -> io::Result<()>,
) -> Result<(Probe, Admission)> {
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "probe"));
    }
    let mut file = File::open(input).map_err(|e| io_error(e, "probe"))?;
    let d = Dng::read(&mut file)?;
    let admission = admit(&d, b)?;
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "admission"));
    }
    let out = OpenOptions::new()
        .write(true)
        .create_new(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(output)
        .map_err(|e| io_error(e, "spool"))?;
    let mut guard = OutputGuard {
        path: output,
        keep: false,
    };
    // Reserve real blocks, not just a sparse file. Errors occur before demosaic.
    reserve(&out, d.probe().output_bytes).map_err(|e| io_error(e, "spool"))?;
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .stack_size(1024 * 1024)
        .build()
        .map_err(|_| err("MEMORY_BUDGET", "develop"))?;
    // JPEG XL output arrives in group order; full-strip CFA JPEG also needs
    // random access for overlapping demosaic halos. Decode exact samples to
    // anonymous disk, then use the existing bounded normalization/developer.
    let sensor_storage = if d.compression == 52546 {
        Some(crate::jxl::decode_to_spool(&d, &file, output, cancel)?)
    } else if d.cfa_spool() {
        Some(crate::dng::cfa_storage::decode_to_spool(
            &d, &mut file, output, cancel,
        )?)
    } else {
        None
    };
    let storage_dng = if let Some(raw) = sensor_storage {
        file = raw;
        let mut storage = d.clone();
        storage.compression = 1;
        storage.sample_bits = 16;
        storage.endian = crate::tiff::Endian::Le;
        storage.offsets = vec![0];
        storage.counts = vec![(d.width * d.height * d.cpp * 2) as u32];
        storage.rows_per_strip = d.height;
        storage.tile_height = d.height;
        Some(storage)
    } else {
        None
    };
    let storage_reader = storage_dng.as_ref().unwrap_or(&d);
    let development = Development::from(&d);
    if d.warp.is_some() {
        pool.install(|| geometry::decode(&d,storage_reader,&mut file,&out,output,&admission,&pool,cancel))?;
    } else if d.streaming_linear {
        let mut batch = Vec::with_capacity(d.active.w * admission.strip_rows as usize * 3);
        let mut batch_y = d.active.y;
        let mut expected_y = 0usize;
        let flush = |batch: &mut Vec<f32>, y: usize| -> Result<()> {
            let h = batch.len() / (d.active.w * 3);
            let first = y.max(d.crop.y);
            let last = (y + h).min(d.crop.y + d.crop.h);
            if first < last {
                let data = std::mem::take(batch);
                let pix =
                    pool.install(|| develop_dng_tile_cancel(&d, &development, y, h, data, cancel))?;
                emit(&development, &out, &pix, y, first, last, cancel)?;
            } else {
                batch.clear();
            }
            Ok(())
        };
        for (strip, (&offset, &bytes)) in d.offsets.iter().zip(&d.counts).enumerate() {
            let start_y = strip * d.rows_per_strip;
            let h = d.rows_per_strip.min(d.height - start_y);
            crate::linear_jpeg::rows(
                &mut file,
                offset as u64,
                bytes as u64,
                d.width,
                h,
                d.sample_bits,
                cancel,
                |local_y, row| {
                    let y = start_y + local_y;
                    if y != expected_y || row.len() != d.width * 3 {
                        return Err(err("INVALID_INPUT", "decode"));
                    }
                    expected_y += 1;
                    if y >= d.active.y && y < d.active.y + d.active.h {
                        if batch.is_empty() {
                            batch_y = y;
                        }
                        for pixel in
                            row[d.active.x * 3..(d.active.x + d.active.w) * 3].chunks_exact(3)
                        {
                            for c in 0..3 {
                                let value = d
                                    .linearization
                                    .as_ref()
                                    .map_or(pixel[c], |t| t.apply(pixel[c]))
                                    as f32;
                                batch
                                    .push((value - d.black[c]).max(0.) / (d.white[c] - d.black[c]));
                            }
                        }
                        if batch.len() == d.active.w * admission.strip_rows as usize * 3 {
                            flush(&mut batch, batch_y)?;
                        }
                    }
                    Ok(())
                },
            )?;
        }
        if expected_y != d.height {
            return Err(err("INVALID_INPUT", "decode"));
        }
        if !batch.is_empty() {
            flush(&mut batch, batch_y)?;
        }
    } else {
        let mut first = d.crop.y;
        while first < d.crop.y + d.crop.h {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "decode"));
            }
            let last = (first + admission.strip_rows as usize).min(d.crop.y + d.crop.h);
            let tile_y = first.saturating_sub(HALO).max(d.active.y);
            let tile_end = (last + HALO).min(d.active.y + d.active.h);
            let data = storage_reader.read_tile_cancellable(
                &mut file,
                tile_y,
                tile_end - tile_y,
                cancel,
            )?;
            let p = pool.install(|| {
                develop_dng_tile_cancel(&d, &development, tile_y, tile_end - tile_y, data, cancel)
            })?;
            emit(&development, &out, &p, tile_y, first, last, cancel)?;
            first = last;
        }
    }
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "spool"));
    }
    out.sync_all().map_err(|e| io_error(e, "spool"))?;
    guard.keep = true;
    Ok((d.probe(), admission))
}

#[cfg(test)]
mod failure_tests {
    use super::*;
    #[test]
    fn enospc_and_cancel_after_create_remove_partial_output() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR"));
        let input = root.join("tests/fixtures/RGGB.dng");
        for kind in [0, 1] {
            let output =
                std::env::temp_dir().join(format!("codec-fault-{}-{kind}", std::process::id()));
            let _ = std::fs::remove_file(&output);
            let cancel = AtomicBool::new(false);
            let result =
                decode_with_reserve(&input, &output, &Budget::default(), &cancel, |f, _| {
                    f.set_len(1024)?;
                    if kind == 0 {
                        Err(io::Error::from_raw_os_error(libc::ENOSPC))
                    } else {
                        cancel.store(true, Ordering::Release);
                        Ok(())
                    }
                });
            assert_eq!(
                result.unwrap_err().code,
                if kind == 0 { "NO_SPACE" } else { "CANCELLED" }
            );
            assert!(!output.exists());
        }
    }
}

/// Emit a fully developed rectangle into the global oriented crop. Used only
/// when independent LinearRaw tiles need no demosaic halo across their edges.
pub(crate) fn emit_region(
    d: &Development,
    out: &File,
    p: &rawler::pixarray::Color2D<f32, 3>,
    tile: Rect,
    cancel: &AtomicBool,
) -> Result<()> {
    // All dimensions originate from the admitted <=52MP descriptor. Recheck
    // rectangular storage here so this emitter remains safe if reused later.
    if p.width != tile.w
        || p.height != tile.h
        || tile.w.checked_mul(tile.h) != Some(p.data.len())
        || tile.x.checked_add(tile.w).is_none_or(|v| v > d.width)
        || tile.y.checked_add(tile.h).is_none_or(|v| v > d.height)
        || !(1..=8).contains(&d.orientation)
        || d.crop.x.checked_add(d.crop.w).is_none_or(|v| v > d.width)
        || d.crop.y.checked_add(d.crop.h).is_none_or(|v| v > d.height)
        || (d.width as u64)
            .checked_mul(d.height as u64)
            .is_none_or(|v| v > crate::MAX_PIXELS)
    {
        return Err(err("INVALID_INPUT", "spool"));
    }
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "spool"));
    }
    let crop = d.crop;
    let left = tile.x.max(crop.x);
    let right = (tile.x + tile.w).min(crop.x + crop.w);
    let first = tile.y.max(crop.y);
    let last = (tile.y + tile.h).min(crop.y + crop.h);
    if left >= right || first >= last {
        return Ok(());
    }
    let (ow, _) = d.oriented_dim();
    let get = |x: usize, y: usize| p.data[(y - tile.y) * tile.w + x - tile.x];
    if d.orientation <= 4 {
        let reverse = matches!(d.orientation, 2 | 3);
        let mut row = Vec::with_capacity((right - left) * 12);
        for y in first..last {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "spool"));
            }
            row.clear();
            for i in 0..right - left {
                let x = if reverse { right - 1 - i } else { left + i };
                for c in get(x, y) {
                    row.extend_from_slice(&c.to_le_bytes());
                }
            }
            let x = if reverse { right - 1 } else { left };
            let (ox, oy) = mapped(x - crop.x, y - crop.y, crop.w, crop.h, d.orientation);
            write_at_all(out, &row, ((oy * ow + ox) * 12) as u64)
                .map_err(|e| io_error(e, "spool"))?;
        }
    } else {
        let reverse = matches!(d.orientation, 6 | 7);
        let mut line = Vec::with_capacity((last - first) * 12);
        for x in left..right {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "spool"));
            }
            line.clear();
            for i in 0..last - first {
                let y = if reverse { last - 1 - i } else { first + i };
                for c in get(x, y) {
                    line.extend_from_slice(&c.to_le_bytes());
                }
            }
            let y = if reverse { last - 1 } else { first };
            let (ox, oy) = mapped(x - crop.x, y - crop.y, crop.w, crop.h, d.orientation);
            write_at_all(out, &line, ((oy * ow + ox) * 12) as u64)
                .map_err(|e| io_error(e, "spool"))?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod rectangle_emit_tests {
    use super::*;
    #[test]
    fn independent_tiles_match_global_crop_all_orientations() {
        for orientation in 1..=8 {
            let d = Development {
                width: 17,
                height: 13,
                cpp: 3,
                orientation,
                active: Rect {
                    x: 0,
                    y: 0,
                    w: 17,
                    h: 13,
                },
                crop: Rect {
                    x: 3,
                    y: 2,
                    w: 11,
                    h: 9,
                },
                cfa: String::new(),
                wb: [1.; 4],
                matrix: vec![1.; 9],
                baseline_exposure_ev: 0.,
                gain_maps: &[],
                lens: crate::embedded_lens::LensStatus::Missing,
                lens_reference: Rect { x: 0, y: 0, w: 17, h: 13 },
            };
            let path = std::env::temp_dir().join(format!(
                "raw-tile-emit-{}-{orientation}",
                std::process::id()
            ));
            let _ = std::fs::remove_file(&path);
            let out = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)
                .unwrap();
            let pixel = |x: usize, y: usize| [(x + 100 * y) as f32, x as f32, y as f32];
            for y in (0..13).step_by(4) {
                for x in (0..17).step_by(5) {
                    let tile = Rect {
                        x,
                        y,
                        w: 5.min(17 - x),
                        h: 4.min(13 - y),
                    };
                    let mut data = Vec::new();
                    for yy in y..y + tile.h {
                        for xx in x..x + tile.w {
                            data.push(pixel(xx, yy));
                        }
                    }
                    let p = rawler::pixarray::Color2D::new_with(data, tile.w, tile.h);
                    emit_region(&d, &out, &p, tile, &AtomicBool::new(false)).unwrap();
                }
            }
            drop(out);
            let got = std::fs::read(&path).unwrap();
            let (ow, oh) = d.oriented_dim();
            assert_eq!(got.len(), ow * oh * 12);
            for y in 0..d.crop.h {
                for x in 0..d.crop.w {
                    let (ox, oy) = mapped(x, y, d.crop.w, d.crop.h, orientation);
                    let at = (oy * ow + ox) * 12;
                    for (c, v) in pixel(x + d.crop.x, y + d.crop.y).into_iter().enumerate() {
                        assert_eq!(&got[at + c * 4..at + (c + 1) * 4], &v.to_le_bytes());
                    }
                }
            }
            std::fs::remove_file(path).unwrap();
        }
    }
}
