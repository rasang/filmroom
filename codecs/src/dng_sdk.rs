//! DNG decoding through the Adobe DNG SDK (see cpp/dng_sdk/bridge.cpp).
//!
//! This product includes DNG technology under license by Adobe.
//!
//! Every content-identified DNG is parsed, linearized, demosaiced and colour
//! rendered by the SDK. For native-firmware camera CFA DNGs without embedded
//! opcodes, Filmroom's certified offline Lensfun profile runs in Stage 3
//! camera RGB before `dng_render`. Output keeps Filmroom's private contract:
//! headerless little-endian float32 RGB, final orientation and default crop
//! applied, scene-linear sRGB primaries, BaselineExposure applied once, no tone curve.
use crate::{
    develop::{Admission, Budget, Development, optics_source_position},
    dng::{Probe, Rect, Warning},
    error::{Result, err, io_error},
    lensfun_metadata::{CaptureStatus, capture_identity},
};
use rawler::{rawimage::RawPhotometricInterpretation, rawsource::RawSource};
use std::{
    ffi::{CString, c_char, c_void},
    fs::{File, OpenOptions},
    os::{
        fd::AsRawFd,
        unix::{ffi::OsStrExt, fs::OpenOptionsExt},
    },
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
};

const MIB: u64 = 1024 * 1024;
/// Fixed native overhead outside SDK pixel buffers (tables, tiles, XMP, libjxl).
const BASE: u64 = 32 * MIB;
const THREADS: u32 = 2;
pub const DEVELOPMENT_POLICY: &str = "Adobe DNG SDK 1.7.1 build 2802: stage1-3 + dng_render camera profile, \
BaselineExposure; linear sRGB float, no tone curve, no shadow ramp";
pub const DEVELOPMENT_POLICY_LENSFUN: &str = "Adobe DNG SDK 1.7.1 build 2802 + lensfun-v3: stage1-3 + dng_render \
camera profile, BaselineExposure; linear sRGB float, no tone curve, no shadow ramp";
pub const DEVELOPMENT_POLICY_UNCORRECTED: &str = "Adobe DNG SDK 1.7.1 build 2802: stage1-3 (profileCorrections=off) + \
dng_render camera profile, BaselineExposure; linear sRGB float, no tone curve, no shadow ramp";

// Must match `OpticalFlags` in cpp/dng_sdk/bridge.cpp.
const FLAG_HAS_OPCODE_TAG: u32 = 1 << 0;
const FLAG_EMBEDDED_DISTORTION: u32 = 1 << 1;
const FLAG_EMBEDDED_TCA: u32 = 1 << 2;
const FLAG_EMBEDDED_VIGNETTING: u32 = 1 << 3;
const FLAG_OPTIONAL_SKIPPED: u32 = 1 << 4;
const FLAG_STRIPPED_TONE_CURVE: u32 = 1 << 5;
const FLAG_HAS_PROFILE_GAIN_TABLE: u32 = 1 << 6;
const FLAG_NONUNIT_SCALE: u32 = 1 << 7;
const FLAG_STAGE1_WARP: u32 = 1 << 8;
const FLAG_STAGE2_WARP: u32 = 1 << 9;
const FLAG_STAGE3_WARP: u32 = 1 << 10;

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
    optical_flags: u32,
    baseline_exposure: f64,
    heap_peak_bytes: u64,
    disk_peak_bytes: u64,
    message: [c_char; 160],
}

/// Must match `FilmLensfunHooks` in cpp/dng_sdk/bridge.cpp.
#[repr(C)]
struct LensfunHooks {
    ctx: *const c_void,
    has_vignette: u32,
    has_warp: u32,
    vignette_row: Option<unsafe extern "C" fn(*const c_void, u32, u32, u32, *mut f32) -> i32>,
    warp_row: Option<unsafe extern "C" fn(*const c_void, u32, u32, u32, *mut f64) -> i32>,
}

unsafe extern "C" {
    fn film_dng_sdk_probe(path: *const c_char, apply_profile_corrections: u32, out: *mut Info) -> i32;
    fn film_dng_sdk_decode(
        path: *const c_char,
        out_fd: i32,
        scratch_dir: *const c_char,
        heap_budget: u64,
        disk_budget: u64,
        cancel: *const u8,
        threads: u32,
        apply_profile_corrections: u32,
        lensfun: *const LensfunHooks,
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
        9 => Err(err("UNSUPPORTED_DNG_OPCODE", stage)),
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

fn read_info(input: &Path, apply_profile_corrections: bool) -> Result<Info> {
    let path = c_path(input)?;
    let mut info = blank();
    // SAFETY: valid NUL-terminated path and a writable Info; no exceptions escape.
    status(
        unsafe { film_dng_sdk_probe(path.as_ptr(), u32::from(apply_profile_corrections), &mut info) },
        "probe",
    )?;
    if !(1..=8).contains(&info.orientation)
        || info.active_w == 0
        || info.active_h == 0
        || info.crop_w == 0
        || info.crop_h == 0
        || info.active_x.checked_add(info.active_w).is_none_or(|r| r > info.encoded_width)
        || info.active_y.checked_add(info.active_h).is_none_or(|b| b > info.encoded_height)
        || info.crop_x.checked_add(info.crop_w).is_none_or(|r| r > info.active_w)
        || info.crop_y.checked_add(info.crop_h).is_none_or(|b| b > info.active_h)
    {
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

fn embedded_warnings(flags: u32) -> Vec<Warning> {
    let mut warnings = Vec::new();
    if flags & FLAG_EMBEDDED_DISTORTION != 0 {
        warnings.push(Warning {
            code: "EMBEDDED_LENS_DISTORTION_APPLIED",
            message: "已应用 DNG 内嵌的镜头畸变校正",
        });
    }
    if flags & FLAG_EMBEDDED_TCA != 0 {
        warnings.push(Warning {
            code: "EMBEDDED_LENS_TCA_APPLIED",
            message: "已应用内嵌镜头色差校正",
        });
    }
    if flags & FLAG_EMBEDDED_VIGNETTING != 0 {
        warnings.push(Warning {
            code: "EMBEDDED_LENS_VIGNETTING_APPLIED",
            message: "已应用内嵌镜头暗角校正",
        });
    }
    if flags & FLAG_OPTIONAL_SKIPPED != 0 {
        warnings.push(Warning {
            code: "DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED",
            message: "部分可选镜头校正未应用，可能保留暗角或畸变",
        });
    }
    if flags & FLAG_STRIPPED_TONE_CURVE != 0 {
        warnings.push(Warning {
            code: "DNG_CAMERA_TONE_CURVE_NOT_APPLIED",
            message: "已读取原始传感器数据，未套用相机自带显示曲线；将使用所选胶片效果",
        });
    }
    warnings
}

struct LensfunPlan {
    development: Development<'static>,
    crop_rel_x: usize,
    crop_rel_y: usize,
    warnings: Vec<Warning>,
    has_vignette: bool,
    has_warp: bool,
    applied: bool,
}

/// Qualifies native-firmware original camera CFA DNGs without an opcode contract
/// for Stage 3 offline Lensfun calibration, preserving every exclusion gate in
/// `dng_lensfun.rs` (including empty OpcodeList tags and converter markers).
fn qualify_lensfun(input: &Path, info: &Info, input_bytes: u64) -> Option<LensfunPlan> {
    const BLOCKING: u32 = FLAG_HAS_OPCODE_TAG | FLAG_HAS_PROFILE_GAIN_TABLE | FLAG_NONUNIT_SCALE;
    if info.is_cfa == 0
        || info.color_planes != 3
        || info.optical_flags & BLOCKING != 0
        || info.crop_w < 5
        || info.crop_h < 5
    {
        return None;
    }
    let mut file = File::open(input).ok()?;
    let t = crate::tiff::Tiff::read(&mut file).ok()?;
    if t.ifds.iter().any(|ifd| [51008, 51009, 51022].iter().any(|tag| ifd.contains_key(tag)))
        || t.ifds
            .iter()
            .filter_map(|ifd| ifd.get(&50718))
            .any(|v| v.nums().ok().is_none_or(|n| n.as_slice() != [1.0, 1.0]))
    {
        return None;
    }
    let source = RawSource::new_from_file(&file, Path::new("original-camera.dng")).ok()?;
    let (raw, metadata) = crate::camera_raw::bounded("probe", || {
        let decoder = rawler::get_decoder(&source)?;
        let raw = decoder.raw_image(&source, &Default::default(), true)?;
        let metadata = decoder.raw_metadata(&source, &Default::default()).ok();
        Ok((raw, metadata))
    })
    .ok()?;
    let active_rect = Rect {
        x: info.active_x as usize,
        y: info.active_y as usize,
        w: info.active_w as usize,
        h: info.active_h as usize,
    };
    let crop_rect = Rect {
        x: active_rect.x + info.crop_x as usize,
        y: active_rect.y + info.crop_y as usize,
        w: info.crop_w as usize,
        h: info.crop_h as usize,
    };
    let rect_arr = |r: Rect| [r.x, r.y, r.w, r.h];
    let raw_rect = |r: rawler::imgop::Rect| [r.p.x, r.p.y, r.d.w, r.d.h];
    let raw_active = raw.active_area.map(raw_rect).unwrap_or([0, 0, raw.width, raw.height]);
    let raw_crop = raw.crop_area.map(raw_rect).unwrap_or(raw_active);
    if raw.cpp != 1
        || [raw.width, raw.height] != [info.encoded_width as usize, info.encoded_height as usize]
        || raw_active != rect_arr(active_rect)
        || raw_crop != rect_arr(crop_rect)
        || !matches!(&raw.photometric, RawPhotometricInterpretation::Cfa(_))
    {
        return None;
    }
    let capture = capture_identity(&mut file, input_bytes, "DNG", &raw, metadata.as_ref());
    if capture.status != CaptureStatus::Ready
        || !capture.evidence.is_dng
        || capture.evidence.converted_dng_marker
        || capture.evidence.orientation.is_some_and(|o| o != info.orientation as u16)
        || !crate::dng::native_firmware(
            capture.evidence.original_model.as_deref().unwrap_or(""),
            capture.evidence.software.as_deref(),
        )
    {
        return None;
    }
    let identity = capture.identity?;
    let mut development = Development {
        width: info.encoded_width as usize,
        height: info.encoded_height as usize,
        cpp: 1,
        orientation: info.orientation as u8,
        active: active_rect,
        crop: crop_rect,
        cfa: String::new(),
        wb: [1.0; 4],
        matrix: Vec::new(),
        baseline_exposure_ev: info.baseline_exposure as f32,
        gain_maps: &[],
        lens: crate::embedded_lens::LensStatus::Missing,
        lens_extras: crate::embedded_optics::LensExtras::default(),
        lens_profile: None,
        lens_reference: crop_rect,
    };
    crate::lensfun::configure_capture("DNG", &identity, &mut development);
    let profile = development.lens_profile.as_ref()?;
    let has_vignette = profile.has_vignetting();
    let has_warp = profile.has_distortion() || profile.has_tca();
    let applied = profile.has_applied();
    let mut warnings = Vec::new();
    crate::camera_raw::append_optics_warnings(&development, &mut warnings);
    Some(LensfunPlan {
        development,
        crop_rel_x: info.crop_x as usize,
        crop_rel_y: info.crop_y as usize,
        warnings,
        has_vignette,
        has_warp,
        applied,
    })
}

struct LensfunContext<'a> {
    plan: &'a LensfunPlan,
    active_w: usize,
    active_h: usize,
}

unsafe extern "C" fn lensfun_vignette_row(
    ctx: *const c_void,
    row: u32,
    col0: u32,
    cols: u32,
    gains_out: *mut f32,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        if ctx.is_null() || gains_out.is_null() {
            return 8;
        }
        // SAFETY: `ctx` points to a live `LensfunContext` and `gains_out` has `cols` elements.
        let context = unsafe { &*(ctx as *const LensfunContext<'_>) };
        let out = unsafe { std::slice::from_raw_parts_mut(gains_out, cols as usize) };
        let row = row as usize;
        let col0 = col0 as usize;
        if row >= context.active_h || col0.checked_add(out.len()).is_none_or(|end| end > context.active_w) {
            return 2;
        }
        let Some(profile) = context.plan.development.lens_profile.as_ref() else { return 2; };
        let Some(vignette) = profile.vignette.as_ref() else { return 2; };
        let reference = profile.reference();
        let active = context.plan.development.active;
        let sy = (active.y + row) as f64 - reference.y as f64;
        for (i, slot) in out.iter_mut().enumerate() {
            let sx = (active.x + col0 + i) as f64 - reference.x as f64;
            let Ok(gain) = vignette.source_gain(sx, sy) else { return 2; };
            if !gain.is_finite() || gain <= 0.0 || gain > 64.0 {
                return 2;
            }
            *slot = gain as f32;
        }
        0
    }))
    .unwrap_or(8)
}

unsafe extern "C" fn lensfun_warp_row(
    ctx: *const c_void,
    crop_row: u32,
    crop_col0: u32,
    cols: u32,
    src_xy_out: *mut f64,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        if ctx.is_null() || src_xy_out.is_null() {
            return 8;
        }
        // SAFETY: `ctx` points to a live `LensfunContext` and `src_xy_out` has `cols * 6` elements.
        let context = unsafe { &*(ctx as *const LensfunContext<'_>) };
        let len = (cols as usize).checked_mul(6).unwrap_or(0);
        let out = unsafe { std::slice::from_raw_parts_mut(src_xy_out, len) };
        let d = &context.plan.development;
        let y = crop_row as usize;
        let x0 = crop_col0 as usize;
        if y >= d.crop.h || x0.checked_add(cols as usize).is_none_or(|end| end > d.crop.w) {
            return 2;
        }
        let max_x = (context.active_w - 1) as f64;
        let max_y = (context.active_h - 1) as f64;
        let ox = context.plan.crop_rel_x as f64;
        let oy = context.plan.crop_rel_y as f64;
        for col in 0..(cols as usize) {
            let x = x0 + col;
            for ch in 0..3 {
                let Ok([sx, sy]) = optics_source_position(d, x, y, ch) else { return 2; };
                let stage_x = (sx + ox).clamp(0.0, max_x);
                let stage_y = (sy + oy).clamp(0.0, max_y);
                if !stage_x.is_finite() || !stage_y.is_finite() {
                    return 2;
                }
                let idx = (col * 3 + ch) * 2;
                out[idx] = stage_x;
                out[idx + 1] = stage_y;
            }
        }
        0
    }))
    .unwrap_or(8)
}

fn probe_of(info: &Info, input_bytes: u64, apply_profile_corrections: bool, lensfun: Option<&LensfunPlan>) -> Probe {
    let mut warnings = embedded_warnings(info.optical_flags);
    let mut policy = if apply_profile_corrections {
        DEVELOPMENT_POLICY
    } else {
        DEVELOPMENT_POLICY_UNCORRECTED
    };
    if let Some(plan) = lensfun {
        warnings.extend(plan.warnings.iter().cloned());
        if plan.applied {
            policy = DEVELOPMENT_POLICY_LENSFUN;
        }
    }
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
            x: info.active_x as usize + info.crop_x as usize,
            y: info.active_y as usize + info.crop_y as usize,
            w: info.crop_w as usize,
            h: info.crop_h as usize,
        },
        output_orientation: 1,
        input_bytes,
        output_bytes: info.width as u64 * info.height as u64 * 12,
        max_pixels: crate::MAX_PIXELS,
        warnings,
        has_alternate_forward_matrix: info.has_forward_matrix != 0,
        development_policy: policy,
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
/// full-frame allocations (including Stage 1/2/3 warp destination buffers)
/// against `BudgetAllocator` (blocks >= 4 MiB either fit wholly in the
/// remaining heap share or spill to a cache file).
/// Returns `(peak_heap_used, peak_disk_spill)`.
fn plan_buffers(info: &Info, lensfun_warp: bool, heap: u64) -> (u64, u64) {
    let float = info.sample_format == 3;
    let pix = if float { 4 } else { 2 };
    let raw_planes = if info.is_cfa != 0 { 1 } else { info.color_planes.max(1) as u64 };
    let s1_pix = if float || info.bits_per_sample > 16 { 4 } else { 2 };
    let s1 = padded_buf(info.encoded_width, info.encoded_height, raw_planes, s1_pix);
    let s2 = padded_buf(info.active_w, info.active_h, raw_planes, pix);
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

    let mut peak_heap = 0u64;
    let mut peak_spill = 0u64;
    let mut note = |h: u64, d: u64| {
        peak_heap = peak_heap.max(h);
        peak_spill = peak_spill.max(d);
    };

    // Stage 1 read (+ optional Stage 1 full-frame filter/warp).
    let (mut h1, mut d1) = place(s1, heap);
    note(h1, d1);
    if info.optical_flags & FLAG_STAGE1_WARP != 0 {
        let (hw, dw) = place(s1, heap - h1);
        note(h1 + hw, d1 + dw);
        (h1, d1) = (hw, dw);
    }

    // BuildStage2Image runs while fStage1Image is still live.
    let (mut h2, mut d2) = place(s2, heap - h1);
    note(h1 + h2, d1 + d2);
    if info.optical_flags & FLAG_STAGE2_WARP != 0 {
        let (hw, dw) = place(s2, heap - h1 - h2);
        note(h1 + h2 + hw, d1 + d2 + dw);
        (h2, d2) = (hw, dw);
    }

    // ClearStage1Image() runs before BuildStage3Image().
    // For non-CFA negatives, DoBuildStage3 moves fStage2Image into fStage3Image.
    let (mut h3, mut d3) = if info.is_cfa != 0 {
        let (h3, d3) = place(s3, heap - h2);
        note(h2 + h3, d2 + d3);
        (h3, d3)
    } else {
        (h2, d2)
    };

    // BuildStage3Image releases fStage2Image before ApplyOpcodeList(fOpcodeList3).
    if info.optical_flags & FLAG_STAGE3_WARP != 0 || lensfun_warp {
        let (hw, dw) = place(s3, heap - h3);
        // Lensfun warp may also hold up to one certified <=8.5 MiB tile buffer per worker.
        let (ht1, dt1) = if lensfun_warp { place(9 * MIB, heap - h3 - hw) } else { (0, 0) };
        let (ht2, dt2) = if lensfun_warp && THREADS > 1 {
            place(9 * MIB, heap - h3 - hw - ht1)
        } else {
            (0, 0)
        };
        note(h3 + hw + ht1 + ht2, d3 + dw + dt1 + dt2);
        (h3, d3) = (hw, dw);
    }

    // Render optional resample tempImage while fStage3Image is live.
    let (hr, dr) = place(resample, heap - h3);
    note(h3 + hr, d3 + dr);

    (peak_heap, peak_spill)
}

fn admit(info: &Info, probe: &Probe, lensfun_warp: bool, b: &Budget) -> Result<Admission> {
    let memory = b
        .memory_budget_mi_b
        .checked_mul(MIB)
        .filter(|m| (BASE..=4096 * MIB).contains(m))
        .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
    let heap = memory - BASE;
    let (peak_heap, spill) = plan_buffers(info, lensfun_warp, heap);
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
    let info = read_info(input, b.profile_corrections)?;
    let bytes = input_len(input)?;
    let lensfun = b.profile_corrections.then(|| qualify_lensfun(input, &info, bytes)).flatten();
    let lensfun_warp = lensfun.as_ref().is_some_and(|p| p.has_warp);
    let probe = probe_of(&info, bytes, b.profile_corrections, lensfun.as_ref());
    let admission = if metadata_only {
        None
    } else {
        Some(admit(&info, &probe, lensfun_warp, b)?)
    };
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
    let info = read_info(input, b.profile_corrections)?;
    let bytes = input_len(input)?;
    let lensfun = b.profile_corrections.then(|| qualify_lensfun(input, &info, bytes)).flatten();
    let lensfun_warp = lensfun.as_ref().is_some_and(|p| p.has_warp);
    let probe = probe_of(&info, bytes, b.profile_corrections, lensfun.as_ref());
    let admission = admit(&info, &probe, lensfun_warp, b)?;
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

    let lf_context = lensfun.as_ref().filter(|p| p.has_vignette || p.has_warp).map(|plan| LensfunContext {
        plan,
        active_w: info.active_w as usize,
        active_h: info.active_h as usize,
    });
    let lf_hooks = lf_context.as_ref().map(|ctx| LensfunHooks {
        ctx: (ctx as *const LensfunContext<'_>).cast::<c_void>(),
        has_vignette: u32::from(ctx.plan.has_vignette),
        has_warp: u32::from(ctx.plan.has_warp),
        vignette_row: ctx.plan.has_vignette.then_some(lensfun_vignette_row),
        warp_row: ctx.plan.has_warp.then_some(lensfun_warp_row),
    });
    let hooks_ptr = lf_hooks.as_ref().map_or(std::ptr::null(), std::ptr::from_ref);

    // SAFETY: all pointers are valid for the call; `lf_context`, `lf_hooks`, and
    // the cancel flag outlive it, and AtomicBool has the same layout as u8.
    let code = unsafe {
        film_dng_sdk_decode(
            path.as_ptr(),
            out.as_raw_fd(),
            dir.as_ptr(),
            heap,
            disk,
            cancel.as_ptr() as *const u8,
            THREADS,
            u32::from(b.profile_corrections),
            hooks_ptr,
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
