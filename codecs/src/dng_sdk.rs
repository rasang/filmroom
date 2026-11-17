//! DNG decoding through the Adobe DNG SDK (see cpp/dng_sdk/bridge.cpp).
//!
//! This product includes DNG technology under license by Adobe.
//!
//! Every content-identified DNG is parsed, linearized, demosaiced and colour
//! rendered by the SDK. Output keeps Filmroom's private contract: headerless
//! little-endian float32 RGB, final orientation and default crop applied,
//! scene-linear sRGB primaries, BaselineExposure applied once, no tone curve.
use crate::{
    develop::{Admission, Budget},
    dng::{Probe, Rect},
    error::{Result, err, io_error},
};
use std::{
    ffi::{CString, c_char},
    fs::{File, OpenOptions},
    os::{
        fd::AsRawFd,
        unix::{ffi::OsStrExt, fs::OpenOptionsExt},
    },
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
};

const MIB: u64 = 1024 * 1024;
/// Fixed native overhead outside SDK pixel buffers (tables, tiles, XMP, libjxl).
const BASE: u64 = 32 * MIB;
const THREADS: u32 = 2;
pub const DEVELOPMENT_POLICY: &str = "Adobe DNG SDK 1.7.1 build 2802: stage1-3 + dng_render camera profile, \
BaselineExposure; linear sRGB float, no tone curve, no shadow ramp";

/// Must match `FilmDngInfo` in cpp/dng_sdk/bridge.cpp.
#[repr(C)]
#[derive(Clone, Copy)]
struct Info {
    encoded_width: u32,
    encoded_height: u32,
    width: u32,
    height: u32,
    active_x: u32,
    active_y: u32,
    active_w: u32,
    active_h: u32,
    crop_x: u32,
    crop_y: u32,
    crop_w: u32,
    crop_h: u32,
    orientation: u32,
    color_planes: u32,
    is_cfa: u32,
    compression: u32,
    bits_per_sample: u32,
    sample_format: u32,
    has_forward_matrix: u32,
    dng_version: u32,
    baseline_exposure: f64,
    heap_peak_bytes: u64,
    disk_peak_bytes: u64,
    message: [c_char; 160],
}

unsafe extern "C" {
    fn film_dng_sdk_probe(path: *const c_char, out: *mut Info) -> i32;
    fn film_dng_sdk_decode(
        path: *const c_char,
        out_fd: i32,
        scratch_dir: *const c_char,
        heap_budget: u64,
        disk_budget: u64,
        cancel: *const u8,
        threads: u32,
        out: *mut Info,
    ) -> i32;
}

fn status(code: i32, stage: &'static str) -> Result<()> {
    match code {
        0 => Ok(()),
        1 => Err(err("CANCELLED", stage)),
        2 => Err(err("INVALID_INPUT", stage)),
        3 => Err(err("UNSUPPORTED_DNG_VARIANT", stage)),
        4 => Err(err("MEMORY_BUDGET", stage)),
        5 => Err(err("DISK_BUDGET", stage)),
        6 => Err(err("IO_ERROR", stage)),
        7 => Err(err("NO_SPACE", stage)),
        _ => Err(err("DECODE_FAILED", stage)),
    }
}

fn c_path(p: &Path) -> Result<CString> {
    CString::new(p.as_os_str().as_bytes()).map_err(|_| err("INVALID_PATH", "probe"))
}

fn blank() -> Info {
    // SAFETY: Info is plain old data; all-zero is a valid value.
    unsafe { std::mem::zeroed() }
}

fn read_info(input: &Path) -> Result<Info> {
    let path = c_path(input)?;
    let mut info = blank();
    // SAFETY: valid NUL-terminated path and a writable Info; no exceptions escape.
    status(unsafe { film_dng_sdk_probe(path.as_ptr(), &mut info) }, "probe")?;
    if !(1..=8).contains(&info.orientation) {
        return Err(err("INVALID_INPUT", "probe"));
    }
    crate::validate_dimensions(info.width, info.height)?;
    Ok(info)
}

fn variant(info: &Info) -> &'static str {
    macro_rules! table {
        ($layout:literal) => {
            match info.compression {
                1 => concat!("DNG_SDK_", $layout, "_UNCOMPRESSED"),
                7 => concat!("DNG_SDK_", $layout, "_LOSSLESS_JPEG"),
                8 => concat!("DNG_SDK_", $layout, "_DEFLATE"),
                34892 => concat!("DNG_SDK_", $layout, "_LOSSY_JPEG"),
                52546 => concat!("DNG_SDK_", $layout, "_JPEG_XL"),
                _ => concat!("DNG_SDK_", $layout, "_OTHER"),
            }
        };
    }
    if info.is_cfa != 0 {
        table!("CFA")
    } else if info.color_planes == 1 {
        table!("MONOCHROME")
    } else {
        table!("LINEAR_RAW")
    }
}

fn probe_of(info: &Info, input_bytes: u64) -> Probe {
    Probe {
        format: "DNG",
        variant: variant(info),
        encoded_width: info.encoded_width,
        encoded_height: info.encoded_height,
        width: info.width,
        height: info.height,
        source_orientation: info.orientation as u8,
        orientation_applied: true,
        active_area: Rect {
            x: info.active_x as usize,
            y: info.active_y as usize,
            w: info.active_w as usize,
            h: info.active_h as usize,
        },
        default_crop: Rect {
            x: info.crop_x as usize,
            y: info.crop_y as usize,
            w: info.crop_w as usize,
            h: info.crop_h as usize,
        },
        output_orientation: 1,
        input_bytes,
        output_bytes: info.width as u64 * info.height as u64 * 12,
        max_pixels: crate::MAX_PIXELS,
        warnings: Vec::new(),
        has_alternate_forward_matrix: info.has_forward_matrix != 0,
        development_policy: DEVELOPMENT_POLICY,
        baseline_exposure_applied_ev: info.baseline_exposure as f32,
    }
}

fn padded_buf(w: u32, h: u32, planes: u64, pix: u64) -> u64 {
    if w == 0 || h == 0 || planes == 0 {
        return 0;
    }
    let pw = ((w as u64) + 15) & !15;
    pw * (h as u64) * planes * pix + 4096
}

/// Simulates the SDK's sequential Stage 1 -> Stage 2 -> Stage 3 -> Render
/// full-frame allocations against `BudgetAllocator` (blocks >= 4 MiB either fit
/// wholly in the remaining heap share or spill to a cache file).
/// Returns `(peak_heap_used, peak_disk_spill)`.
fn plan_buffers(info: &Info, heap: u64) -> (u64, u64) {
    let float = info.sample_format == 3;
    let pix = if float { 4 } else { 2 };
    let raw_planes = if info.is_cfa != 0 { 1 } else { info.color_planes.max(1) as u64 };
    let s1_pix = if float || info.bits_per_sample > 16 { 4 } else { 2 };
    let s1 = padded_buf(info.encoded_width, info.encoded_height, raw_planes, s1_pix);
    let s2 = padded_buf(info.encoded_width, info.encoded_height, raw_planes, pix);
    let s3_planes = info.color_planes.max(if info.is_cfa != 0 { 3 } else { 1 }) as u64;
    let s3 = padded_buf(info.active_w, info.active_h, s3_planes, pix);
    let (unrot_w, unrot_h) = if matches!(info.orientation, 5..=8) {
        (info.height, info.width)
    } else {
        (info.width, info.height)
    };
    let resample = if info.crop_w != unrot_w || info.crop_h != unrot_h {
        padded_buf(unrot_w, unrot_h, s3_planes, pix)
    } else {
        0
    };

    let place = |size: u64, rem_heap: u64| -> (u64, u64) {
        if size == 0 {
            (0, 0)
        } else if size < 4 * MIB || size <= rem_heap {
            (size.min(rem_heap), 0)
        } else {
            (0, size)
        }
    };

    let (h1, d1) = place(s1, heap);
    let (h2, d2) = place(s2, heap - h1);
    // Stage 1 is freed before Stage 3 is built.
    let (h3, d3) = place(s3, heap - h2);
    // Stage 2 is freed inside BuildStage3Image before Render allocates tempImage.
    let (hr, dr) = place(resample, heap - h3);

    let peak_heap = (h1 + h2).max(h2 + h3).max(h3 + hr);
    let peak_spill = (d1 + d2).max(d2 + d3).max(d3 + dr);
    (peak_heap, peak_spill)
}

fn admit(info: &Info, probe: &Probe, b: &Budget) -> Result<Admission> {
    let memory = b
        .memory_budget_mi_b
        .checked_mul(MIB)
        .filter(|m| (BASE..=4096 * MIB).contains(m))
        .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
    let heap = memory - BASE;
    let (peak_heap, spill) = plan_buffers(info, heap);
    let disk = probe.output_bytes + spill;
    if b.disk_budget_bytes < disk {
        return Err(err("DISK_BUDGET", "admission"));
    }
    Ok(Admission {
        memory_budget_bytes: memory,
        estimated_peak_bytes: BASE + peak_heap,
        required_disk_bytes: disk,
        strip_rows: 64,
    })
}

fn input_len(input: &Path) -> Result<u64> {
    Ok(std::fs::metadata(input).map_err(|e| io_error(e, "probe"))?.len())
}

pub fn probe(input: &Path, b: &Budget, metadata_only: bool) -> Result<(Probe, Option<Admission>)> {
    let info = read_info(input)?;
    let probe = probe_of(&info, input_len(input)?);
    let admission = if metadata_only { None } else { Some(admit(&info, &probe, b)?) };
    Ok((probe, admission))
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
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "probe"));
    }
    let info = read_info(input)?;
    let probe = probe_of(&info, input_len(input)?);
    let admission = admit(&info, &probe, b)?;
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "admission"));
    }
    let scratch = output
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    let out: File = OpenOptions::new()
        .write(true)
        .create_new(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(output)
        .map_err(|e| io_error(e, "spool"))?;
    let mut guard = OutputGuard { path: output, keep: false };
    // Reserve real blocks for the output before any pixel work.
    let rc = unsafe { libc::posix_fallocate(out.as_raw_fd(), 0, probe.output_bytes as libc::off_t) };
    if rc != 0 {
        return Err(io_error(std::io::Error::from_raw_os_error(rc), "spool"));
    }
    let path = c_path(input)?;
    let dir = c_path(scratch)?;
    let mut result = blank();
    let heap = admission.memory_budget_bytes - BASE;
    let disk = b.disk_budget_bytes - probe.output_bytes;
    // SAFETY: all pointers are valid for the call; the cancel flag outlives it
    // and AtomicBool has the same in-memory representation as u8.
    let code = unsafe {
        film_dng_sdk_decode(
            path.as_ptr(),
            out.as_raw_fd(),
            dir.as_ptr(),
            heap,
            disk,
            cancel.as_ptr() as *const u8,
            THREADS,
            &mut result,
        )
    };
    status(code, "develop")?;
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "develop"));
    }
    out.sync_data().map_err(|e| io_error(e, "spool"))?;
    guard.keep = true;
    Ok((probe, admission))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture(name: &str) -> std::path::PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)
    }
    #[test]
    fn decodes_fixture_to_linear_float_rgb() {
        let input = fixture("RGGB.dng");
        let (p, a) = probe(&input, &Budget::default(), false).unwrap();
        assert_eq!(p.format, "DNG");
        assert!(a.is_some());
        let out = std::env::temp_dir().join(format!("dng-sdk-{}", std::process::id()));
        let _ = std::fs::remove_file(&out);
        let (q, _) = decode(&input, &out, &Budget::default(), &AtomicBool::new(false)).unwrap();
        let bytes = std::fs::read(&out).unwrap();
        assert_eq!(bytes.len() as u64, q.output_bytes);
        let finite = bytes
            .chunks_exact(4)
            .map(|c| f32::from_le_bytes(c.try_into().unwrap()))
            .all(|v| v.is_finite() && (0.0..=1.0).contains(&v));
        assert!(finite);
        std::fs::remove_file(out).unwrap();
    }
    #[test]
    fn cancelled_decode_leaves_no_output() {
        let input = fixture("RGGB.dng");
        let out = std::env::temp_dir().join(format!("dng-sdk-cancel-{}", std::process::id()));
        let _ = std::fs::remove_file(&out);
        let e = decode(&input, &out, &Budget::default(), &AtomicBool::new(true)).unwrap_err();
        assert_eq!(e.code, "CANCELLED");
        assert!(!out.exists());
    }
    #[test]
    fn rejects_non_dng_tiff_as_invalid() {
        let bad = std::env::temp_dir().join(format!("dng-sdk-bad-{}", std::process::id()));
        std::fs::write(&bad, b"II*\0\x08\0\0\0\0\0\0\0\0\0").unwrap();
        assert!(probe(&bad, &Budget::default(), true).is_err());
        std::fs::remove_file(bad).unwrap();
    }
}
