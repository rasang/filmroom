//! Early display-only physical film simulation. It is not the full-resolution
//! export's density tap. Reads authoritative linear RGB, never the display JPEG.
//! The exporter must reserve PREVIEW_WORKSPACE_BYTES and must never join us.
use spektrafilm_core::pipeline::Pipeline;
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use std::{fs::File, io::{self, Read, Seek, SeekFrom}, path::Path,
    sync::{Arc, atomic::{AtomicBool, AtomicU64, Ordering}}};

pub(crate) const MAX_EDGE: u32 = 128;
/// Conservative admission estimate for 128-edge images, capped diffusion FFT,
/// one Rayon worker (2 MiB stack), launcher stack and source row. Not measured
/// RSS or an allocator-enforced ceiling. Shared Pipeline storage is excluded;
/// callers that deep-clone it must separately charge the profiles and TC LUT.
pub(crate) const PREVIEW_WORKSPACE_BYTES: u64 = 32 * 1024 * 1024;
const READ_BUDGET_BYTES: u64 = 24 * 1024 * 1024;
/// A process-wide passive lease, not a second export worker slot.
static ACTIVE_BYTES: AtomicU64 = AtomicU64::new(0);
pub(crate) fn active_reservation_bytes() -> u64 { ACTIVE_BYTES.load(Ordering::Acquire) }
fn acquire_reservation(bytes:u64) -> bool {
    bytes>0 && ACTIVE_BYTES.compare_exchange(0,bytes,Ordering::AcqRel,Ordering::Acquire).is_ok()
}
/// Cancellation requests do not imply memory has been released. Keep the
/// workspace reservation until `running.load(Ordering::Acquire)` is false.
pub(crate) struct PreviewHandle {
    pub running: Arc<AtomicBool>,
    pub cancel: Arc<AtomicBool>,
    pub reserved_bytes: u64,
}

/// Reserve the retained pipeline as well as worker scratch, since CPU
/// fallback may construct a replacement while cancellation is still draining.
pub(crate) fn required_workspace_bytes(pipe: &Pipeline) -> Option<u64> {
    fn vector(v: &Vec<f64>) -> u64 { v.capacity() as u64 * 8 }
    fn matrix(v: &Vec<Vec<f64>>) -> u64 {
        v.capacity() as u64 * std::mem::size_of::<Vec<f64>>() as u64 + v.iter().map(vector).sum::<u64>()
    }
    fn profile(p: &spektrafilm_core::profile::Profile) -> u64 {
        let d = &p.data; let i = &p.info; let m = &p.metadata;
        let mut n = std::mem::size_of_val(p) as u64;
        for v in [&d.wavelengths, &d.hanatos2025_adaptation_window_params, &d.base_density,
            &d.midscale_neutral_density, &d.log_exposure, &d.development_time] { n += vector(v); }
        for v in [&d.log_sensitivity, &d.hanatos2025_adaptation_surface_params, &d.channel_density,
            &d.base_density_rows, &d.density_curves] { n += matrix(v); }
        n += d.density_curves_layers.capacity() as u64 * std::mem::size_of::<Vec<Vec<f64>>>() as u64;
        n += d.density_curves_layers.iter().map(matrix).sum::<u64>();
        if let Some(model) = &d.density_curves_model {
            n += matrix(&model.centers) + matrix(&model.amplitudes) + matrix(&model.sigmas) + model.model_type.capacity() as u64;
        }
        for text in [&m.version,&m.copyright,&m.created,&m.license,&m.citation,&m.datasource,
            &i.film_type,&i.support,&i.stage,&i.usage,&i.antihalation,&i.channel_model,&i.densitometer,
            &i.reference_illuminant,&i.viewing_illuminant] { n += text.capacity() as u64; }
        for text in [&i.stock,&i.name,&i.target_print].into_iter().flatten() { n += text.capacity() as u64; }
        n
    }
    let p = &pipe.params;
    let strings = [&p.camera.auto_exposure_method, &p.camera.diffusion_filter.filter_family,
        &p.enlarger.illuminant, &p.enlarger.diffusion_filter.filter_family, &p.io.input_color_space,
        &p.io.output_color_space, &p.io.input_gamut_compress.algorithm,
        &p.io.output_gamut_compress.algorithm, &p.settings.rgb_to_raw_method]
        .iter().map(|s| s.capacity() as u64).sum::<u64>();
    let lut = pipe.tc_lut().map_or(0, |lut| lut.data.capacity() as u64 * 8);
    PREVIEW_WORKSPACE_BYTES.checked_add(lut)?.checked_add(profile(&pipe.film))?
        .checked_add(profile(&pipe.print))?.checked_add(strings)?.checked_add(1024 * 1024)
}
struct Running(Arc<AtomicBool>);
impl Drop for Running { fn drop(&mut self) { self.0.store(false, Ordering::Release); ACTIVE_BYTES.store(0, Ordering::Release); } }
fn invalid(message: &str) -> io::Error { io::Error::new(io::ErrorKind::InvalidData, message) }
fn active(cancel: &mut impl FnMut() -> bool) -> io::Result<()> {
    if cancel() { Err(io::Error::new(io::ErrorKind::Interrupted, "Early negative cancelled")) } else { Ok(()) }
}
fn shape(width: u32, height: u32, cap: u32) -> io::Result<(u32, u32)> {
    shape_with_read_budget(width, height, cap, READ_BUDGET_BYTES)
}
fn shape_with_read_budget(width: u32, height: u32, cap: u32, read_budget: u64) -> io::Result<(u32, u32)> {
    if width == 0 || height == 0 || width > 40_000 || height > 40_000 || cap == 0 || cap > 256 { return Err(invalid("Invalid source dimensions")); }
    // Two source rows per output row; cap bytes actually read, not only RAM.
    let edge = cap.min((read_budget / (u64::from(width) * 24)).max(1) as u32).min(width.max(height));
    Ok(((u64::from(width) * u64::from(edge) / u64::from(width.max(height))).max(1) as u32,
        (u64::from(height) * u64::from(edge) / u64::from(width.max(height))).max(1) as u32))
}
fn sample_input(file: &mut File, width: u32, height: u32, cap: u32, cancel: &mut impl FnMut() -> bool) -> io::Result<ImageBuf> {
    sample_input_with_read_budget(file, width, height, cap, READ_BUDGET_BYTES, cancel)
}
fn sample_input_with_read_budget(file: &mut File, width: u32, height: u32, cap: u32, read_budget: u64, cancel: &mut impl FnMut() -> bool) -> io::Result<ImageBuf> {
    active(cancel)?;
    if file.metadata()?.len() != u64::from(width) * u64::from(height) * 12 { return Err(invalid("Invalid linear RGB spool size")); }
    let (w, h) = shape_with_read_budget(width, height, cap, read_budget)?;
    let mut row = vec![0u8; width as usize * 12];
    let mut values = vec![from_f64(0.); w as usize * h as usize * 3];
    for y in 0..h {
        for sy in 0..2 {
            active(cancel)?;
            let iy = crate::negative_preview::sample_coordinate(y, sy, height, h);
            file.seek(SeekFrom::Start(u64::from(iy) * u64::from(width) * 12))?;
            file.read_exact(&mut row)?;
            for x in 0..w { for sx in 0..2 {
                let ix = crate::negative_preview::sample_coordinate(x, sx, width, w) as usize;
                for c in 0..3 {
                    let at = ix * 12 + c * 4;
                    let v = f32::from_le_bytes(row[at..at+4].try_into().unwrap());
                    if !v.is_finite() { return Err(invalid("Nonfinite linear source sample")); }
                    values[((y*w+x)*3) as usize+c] += from_f64(f64::from(v) * 0.25);
                }
            }}
        }
    }
    Ok(ImageBuf::from_data(w, h, values))
}
/// Refuse extreme CPU kernels rather than silently reducing selected effects.
/// This admission guard bounds Gaussian support/work; it does not alter params.
fn bounded_settings(pipe: &Pipeline, width: u32, height: u32, w: u32, h: u32) -> bool {
    let p = &pipe.params;
    if pipe.film.data.log_exposure.len() > 4096 || pipe.film.data.density_curves.len() > 4096
        || pipe.film.data.density_curves.iter().any(|v| v.len() != 3) { return false; }
    let filter = &p.camera.diffusion_filter;
    if filter.active {
        if !matches!(filter.filter_family.as_str(), "glimmerglass" | "black_pro_mist" | "pro_mist" | "cinebloom")
            || ![filter.strength, filter.spatial_scale, filter.halo_warmth, filter.core_intensity,
                filter.core_size, filter.halo_intensity, filter.halo_size, filter.bloom_intensity, filter.bloom_size]
                .iter().all(|v| v.is_finite()) { return false; }
        // The physical CPU model caps radius <= min(w,h)/2 - 1. With
        // <=128 edges, padded FFTs are <=384x384. Three simultaneous complex
        // arrays (image, kernel, transpose) occupy <=7,077,888 bytes.
        // No selected strength/scale or PSF parameter is reduced here.
        if filter.strength > 0. && filter.spatial_scale > 0. && w.max(h) > 128 { return false; }
    }
    let full_pitch = p.camera.pixel_size_um_override.unwrap_or(p.camera.film_format_mm * 1000. / width.max(height) as f32);
    let pitch = f64::from(full_pitch) * f64::from(width.max(height)) / f64::from(w.max(h));
    if !pitch.is_finite() || pitch <= 0. { return false; }
    let sigma_ok = |um: f64| um.is_finite() && um >= 0. && um / pitch <= 32.;
    if !sigma_ok(f64::from(p.camera.lens_blur_um)) { return false; }
    let hal = &p.film_render.halation;
    if hal.active && (hal.halation_n_bounces > 16
        || !hal.scatter_core_um.iter().chain(hal.scatter_tail_um.iter()).all(|v| sigma_ok(*v * hal.scatter_spatial_scale))
        || !hal.halation_first_sigma_um.iter().all(|v| sigma_ok(*v * hal.halation_spatial_scale * f64::from(hal.halation_n_bounces.max(1)).sqrt()))) { return false; }
    let dir = &p.film_render.dir_couplers;
    !dir.active || (sigma_ok(dir.diffusion_size_um) && sigma_ok(dir.diffusion_tail_um * 2.7684))
}
fn lower_priority() {
    #[cfg(target_os = "android")]
    unsafe {
        unsafe extern "C" { fn setpriority(which: i32, who: u32, priority: i32) -> i32; }
        let _ = setpriority(0, 0, 19);
    }
}
/// Starts at most one worker process-wide. `None` means unsupported, busy, or
/// no budget; caller may use its authoritative density-tap fallback. Requires
/// actual export AE resolved in pipe.params, separate from manual exposure.
/// Share the existing Pipeline via Arc to avoid a duplicate TC LUT/profile.
/// Input FD is opened before spawn so ordinary export cleanup cannot unlink
/// the source out from under us. Attempt token is the sole publication owner.
pub(crate) fn spawn(input: &Path, dir: &Path, pipe: Arc<Pipeline>, width: u32, height: u32,
    available_preview_bytes: u64) -> io::Result<Option<PreviewHandle>> {
    let attempt = match std::fs::read_to_string(dir.join("scan-preview-attempt")) {
        Ok(value) => value,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e),
    };
    if !crate::negative_preview::valid_attempt(&attempt) { return Err(invalid("Invalid preview attempt")); }
    let result = spawn_for_attempt(input, dir, pipe, width, height, available_preview_bytes, &attempt);
    if result.is_err() && crate::negative_preview::same_attempt(dir, &attempt) {
        let _ = crate::negative_preview::write_early_attempt_status(dir, &attempt, "failed");
    }
    result
}
fn spawn_for_attempt(input: &Path, dir: &Path, pipe: Arc<Pipeline>, width: u32, height: u32,
    available_preview_bytes: u64, attempt: &str) -> io::Result<Option<PreviewHandle>> {
    let attempt = attempt.to_owned();
    if !crate::negative_preview::valid_attempt(&attempt) { return Err(invalid("Invalid preview attempt")); }
    let cap = MAX_EDGE;
    let (w, h) = shape(width, height, cap)?;
    let required_bytes = required_workspace_bytes(&pipe).unwrap_or(u64::MAX);
    if available_preview_bytes < required_bytes {
        crate::negative_preview::write_early_attempt_status(dir, &attempt, "skipped_budget")?;
        return Ok(None);
    }
    if pipe.film.info.film_type != "negative" || (pipe.params.camera.auto_exposure && !pipe.params.camera.preview_metered_ev.is_some_and(|ev|ev.is_finite() && 2.0f32.powf(ev).is_finite())) || pipe.params.film_render.halation.boost_ev != 0.
        || !bounded_settings(&pipe, width, height, w, h) {
        crate::negative_preview::write_early_attempt_status(dir, &attempt, "failed")?;
        return Ok(None);
    }
    let mut file = File::open(input)?;
    if file.metadata()?.len() != u64::from(width) * u64::from(height) * 12 { return Err(invalid("Invalid linear RGB spool size")); }
    if !acquire_reservation(required_bytes) {
        crate::negative_preview::write_early_attempt_status(dir, &attempt, "skipped_budget")?;
        return Ok(None);
    }
    let handle = PreviewHandle { running: Arc::new(AtomicBool::new(true)), cancel: Arc::new(AtomicBool::new(false)), reserved_bytes: required_bytes };
    let running = Running(Arc::clone(&handle.running));
    let requested_cancel = Arc::clone(&handle.cancel);
    crate::negative_preview::write_early_attempt_status(dir, &attempt, "pending")?;
    let dir = dir.to_path_buf(); let failure_dir = dir.clone(); let failure_attempt = attempt.clone();
    let launched = std::thread::Builder::new().name("film-early-negative".into()).stack_size(512*1024).spawn(move || {
        let _running = running;
        lower_priority();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> io::Result<()> {
            let mut cancel = || requested_cancel.load(Ordering::Acquire) || !crate::negative_preview::same_attempt(&dir, &attempt);
            active(&mut cancel)?;
            let pool = rayon::ThreadPoolBuilder::new().num_threads(1).stack_size(2*1024*1024)
                .thread_name(|_| "film-early-negative-cpu".into()).start_handler(|_| lower_priority())
                .build().map_err(|e| invalid(&e.to_string()))?;
            let image = sample_input(&mut file, width, height, cap, &mut cancel)?;
            drop(file);
            let density = pool.install(|| pipe.low_resolution_pre_grain_density_cpu(&image, width, height, &mut || !cancel()))
                .map_err(|e| invalid(&e))?;
            drop(image); drop(pool);
            active(&mut cancel)?;
            // One density per low-resolution pixel, not a claim of four-point
            // full-resolution sampling. The light-table evaluates it once.
            let mut samples = Vec::with_capacity(density.data.len()/3);
            for row in density.data.chunks_exact(density.width as usize*3) {
                active(&mut cancel)?;
                for px in row.chunks_exact(3) {
                    let v = [px[0] as f32, px[1] as f32, px[2] as f32];
                    samples.push(v);
                }
            }
            active(&mut cancel)?;
            crate::negative_preview::publish_early_negative_for_attempt(&dir, &attempt, &pipe.film, density.width, density.height, &samples, &mut cancel)?;
            active(&mut cancel)
        }));
        if !matches!(result, Ok(Ok(()))) && crate::negative_preview::same_attempt(&dir, &attempt) {
            let status=if requested_cancel.load(Ordering::Acquire){"cancelled"}else{"failed"};
            let _ = crate::negative_preview::write_early_attempt_status(&dir, &attempt, status);
        }
        // A CPU fallback can construct a replacement Pipeline. Release this
        // retained Arc before the guard advertises that its reservation is free.
        drop(pipe);
        drop(_running);
    });
    match launched {
        Ok(_) => Ok(Some(handle)),
        Err(error) => {
            if crate::negative_preview::same_attempt(&failure_dir, &failure_attempt) {
                let _ = crate::negative_preview::write_early_attempt_status(&failure_dir, &failure_attempt, "failed");
            }
            Err(error)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn process_lease_is_exclusive_and_counted_once_for_next_export() {
        let params=crate::mobile::MobileParams::default();
        let before=crate::mobile::plan_export(100,100,&params).unwrap();
        assert!(acquire_reservation(17*1024*1024));
        let guard=Running(Arc::new(AtomicBool::new(true)));
        assert!(!acquire_reservation(1));
        let during=crate::mobile::plan_export(100,100,&params).unwrap();
        assert_eq!(during.background_preview_lease_bytes,17*1024*1024);
        assert_eq!(during.estimated_native_working_bytes,before.estimated_native_working_bytes+17*1024*1024);
        drop(guard);
        assert_eq!(active_reservation_bytes(),0);
        let after=crate::mobile::plan_export(100,100,&params).unwrap();
        assert_eq!(after.estimated_native_working_bytes,before.estimated_native_working_bytes);
    }
    #[test] fn low_budget_and_existing_worker_skip_auxiliary_without_starting_another() {
        let root=std::env::temp_dir().join(format!("early-admission-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&root).unwrap();
        let token="12345678-1234-1234-1234-123456789abc";
        std::fs::write(root.join("scan-preview-attempt"),token).unwrap();
        let input=root.join("input.rgb");std::fs::write(&input,[0u8;12]).unwrap();
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let film=spektrafilm_core::profile::load_profile_by_name(&data,"kodak_portra_400").unwrap();
        let paper=spektrafilm_core::profile::load_profile_by_name(&data,"kodak_portra_endura").unwrap();
        let mut params=spektrafilm_core::params::RuntimeParams::default();params.camera.auto_exposure=false;params.film_render.halation.boost_ev=0.;
        let pipe=Arc::new(Pipeline::new_with_spectral(film,paper,params,&data).unwrap());
        assert!(spawn(&input,&root,Arc::clone(&pipe),1,1,0).unwrap().is_none());
        assert_eq!(std::fs::read_to_string(root.join(format!("negative-preview-early-{token}.status"))).unwrap(),"skipped_budget");
        assert!(acquire_reservation(17*1024*1024));let guard=Running(Arc::new(AtomicBool::new(true)));
        assert!(spawn(&input,&root,Arc::clone(&pipe),1,1,required_workspace_bytes(&pipe).unwrap()).unwrap().is_none());
        assert_eq!(active_reservation_bytes(),17*1024*1024);
        drop(guard);std::fs::remove_dir_all(root).unwrap();
    }
    /// Run each edge in a FRESH process so VmHWM is not inherited from another
    /// variant. Fixture JSON: input, data_dir, width, height, film, paper, params.
    /// params must contain the actual export AE already folded in. Harness
    /// explicitly lifts source-read cap; production admission stays unchanged.
    #[test]
    #[ignore = "Manual genuine-fixture visual/timing/memory review; requires resource clearance"]
    fn review_early_negative_fixture() {
        use std::time::Instant;
        let manifest: serde_json::Value = serde_json::from_slice(&std::fs::read(std::env::var("FILMROOM_EARLY_FIXTURE_JSON").unwrap()).unwrap()).unwrap();
        let edge: u32 = std::env::var("FILMROOM_EARLY_PREVIEW_EDGE").unwrap().parse().unwrap();
        assert!([64,128,256].contains(&edge));
        let output = std::path::PathBuf::from(std::env::var("FILMROOM_EARLY_PREVIEW_OUTPUT").unwrap());
        let data = Path::new(manifest["data_dir"].as_str().unwrap());
        let film = spektrafilm_core::profile::load_profile_by_name(data,manifest["film"].as_str().unwrap()).unwrap();
        let paper = spektrafilm_core::profile::load_profile_by_name(data,manifest["paper"].as_str().unwrap()).unwrap();
        let params = serde_json::from_value(manifest["params"].clone()).unwrap();
        let pipe = Pipeline::new_with_spectral(film,paper,params,data).unwrap();
        assert!(!pipe.params.camera.auto_exposure,"Fixture needs actual metered export exposure");
        let (width,height) = (manifest["width"].as_u64().unwrap() as u32,manifest["height"].as_u64().unwrap() as u32);
        let read_cap = u64::from(width)*24*u64::from(edge);
        let mut file = File::open(manifest["input"].as_str().unwrap()).unwrap();
        let rss_before = std::fs::read_to_string("/proc/self/status").unwrap_or_default();
        let start = Instant::now();
        let image = sample_input_with_read_budget(&mut file,width,height,edge,read_cap,&mut ||false).unwrap();
        let sample_ms = start.elapsed().as_secs_f64()*1000.;
        let pool = rayon::ThreadPoolBuilder::new().num_threads(1).stack_size(2*1024*1024).build().unwrap();
        let density = pool.install(|| pipe.low_resolution_pre_grain_density_cpu(&image,width,height,&mut ||true)).unwrap();
        let density_ms = start.elapsed().as_secs_f64()*1000.-sample_ms;
        let mut samples = Vec::with_capacity(density.data.len()/3);
        for px in density.data.chunks_exact(3) { samples.push([px[0] as f32,px[1] as f32,px[2] as f32]); }
        let png = crate::negative_preview::render_early_negative(&pipe.film,density.width,density.height,&samples,&mut ||false).unwrap().unwrap();
        let total_ms = start.elapsed().as_secs_f64()*1000.;
        std::fs::write(&output,png).unwrap();
        let rss_after = std::fs::read_to_string("/proc/self/status").unwrap_or_default();
        let memory_lines = |s:&str| s.lines().filter(|l|l.starts_with("VmRSS:")||l.starts_with("VmHWM:")).collect::<Vec<_>>().join("\n");
        let metrics = serde_json::json!({"requested_edge":edge,"actual_width":density.width,"actual_height":density.height,
            "read_cap_bytes":read_cap,"actual_row_read_bytes":u64::from(width)*24*u64::from(image.height),
            "sample_ms":sample_ms,"density_ms":density_ms,"total_ms":total_ms,
            "fft_three_arrays_upper_bytes":3u64*(u64::from(edge)*3).pow(2)*16,
            "recommended_worker_workspace_bytes":if edge==256 {64*1024*1024} else {32*1024*1024},
            "production_retained_pipeline_plus_workspace_bytes":required_workspace_bytes(&pipe),
            "rss_before":memory_lines(&rss_before),"rss_after":memory_lines(&rss_after),
            "note":"Separate low-resolution physical simulation. VmHWM includes process/pipeline; use fresh process per edge. Production limits unchanged."});
        std::fs::write(output.with_extension("metrics.json"),serde_json::to_vec_pretty(&metrics).unwrap()).unwrap();
        eprintln!("EARLY_NEGATIVE_REVIEW {metrics}");
    }
    #[test] fn shape_and_io_are_bounded() {
        assert_eq!(shape(6144,8192,MAX_EDGE).unwrap(),(96,128));
        for (w,h) in [(1,1),(40_000,40_000),(40_000,1),(1,40_000),(8192,6144)] {
            let (sw,sh) = shape(w,h,MAX_EDGE).unwrap();
            assert!(sw > 0 && sh > 0 && sw <= MAX_EDGE && sh <= MAX_EDGE);
            assert!(u64::from(w)*12*u64::from(sh)*2 <= READ_BUDGET_BYTES);
        }
        assert!(shape(0,1,MAX_EDGE).is_err()); assert!(shape(40_001,1,MAX_EDGE).is_err());
    }
    #[test] fn source_samples_preserve_linear_values_and_cancel() {
        let path = std::env::temp_dir().join(format!("early-negative-{}-{}.rgb", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        let source: Vec<f32> = (0..12).map(|i| i as f32 * 0.125).collect();
        std::fs::write(&path, source.iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
        let mut file = File::open(&path).unwrap();
        let image = sample_input(&mut file,2,2,MAX_EDGE,&mut ||false).unwrap();
        assert_eq!(image.data.iter().map(|v| *v as f32).collect::<Vec<_>>(),source);
        assert_eq!(sample_input(&mut file,2,2,MAX_EDGE,&mut ||true).unwrap_err().kind(),io::ErrorKind::Interrupted);
        std::fs::remove_file(path).unwrap();
    }
}
