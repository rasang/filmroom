//! Strip adaptation of pinned RawDevelop, with no full-image RGB allocation.
//! Rawler retains its own LGPL-2.1 license; no SRgb transfer step is applied.
use crate::{
    dng::{Dng, Probe},
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
#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Budget {
    pub memory_budget_mi_b: u64,
    pub disk_budget_bytes: u64,
}
impl Default for Budget {
    fn default() -> Self {
        Self {
            memory_budget_mi_b: 128,
            disk_budget_bytes: 1024 * MIB,
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
    if memory > 4096 * MIB || memory < BASE {
        return Err(err("MEMORY_BUDGET", "admission"));
    }
    let base = BASE
        .checked_add(d.decode_scratch_bytes())
        .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
    if memory < base {
        return Err(err("MEMORY_BUDGET", "admission"));
    }
    let rows = ((memory - base) / (d.active.w as u64 * 96)) as usize;
    let rows = rows.saturating_sub(HALO * 2).min(128);
    if rows < 8 {
        return Err(err("MEMORY_BUDGET", "admission"));
    }
    let disk = d.probe().output_bytes;
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
fn tile_raw(d: &Dng, y: usize, h: usize, data: Vec<f32>) -> RawImage {
    let mut cam = Camera::default();
    cam.real_bps = 16;
    cam.cfa = if d.cpp == 1 {
        CFA::new(&d.cfa).shift(0, y - d.active.y)
    } else {
        CFA::default()
    };
    cam.color_matrix.insert(Illuminant::D65, d.matrix.clone());
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
    develop_tile_cancel(d, y, h, data, &AtomicBool::new(false))
}
fn develop_tile_cancel(
    d: &Dng,
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
fn emit(
    d: &Dng,
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
    reserve(&out, admission.required_disk_bytes).map_err(|e| io_error(e, "spool"))?;
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .stack_size(1024 * 1024)
        .build()
        .map_err(|_| err("MEMORY_BUDGET", "develop"))?;
    let mut first = d.crop.y;
    while first < d.crop.y + d.crop.h {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "decode"));
        }
        let last = (first + admission.strip_rows as usize).min(d.crop.y + d.crop.h);
        let tile_y = first.saturating_sub(HALO).max(d.active.y);
        let tile_end = (last + HALO).min(d.active.y + d.active.h);
        let data = d.read_tile_cancellable(&mut file, tile_y, tile_end - tile_y, cancel)?;
        let p =
            pool.install(|| develop_tile_cancel(&d, tile_y, tile_end - tile_y, data, cancel))?;
        emit(&d, &out, &p, tile_y, first, last, cancel)?;
        first = last;
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
