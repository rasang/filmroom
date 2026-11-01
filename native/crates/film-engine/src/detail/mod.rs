//! Experimental preview-owned exact same-working-grid detail API and isolated JNI. No shipping/export integration.
//! One active worker per context; callers retain Arc ownership through worker exit.
mod fft;
mod global;
use crate::mobile::MobileParams;
pub use fft::CacheStats as DetailPrintCacheStats;
pub use global::PregrainMetrics;
use spektrafilm_core::{
    pipeline::{Pipeline, PreparedStreamedFinish},
    profile::load_profile_by_name,
    stages::filming,
};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use spektrafilm_model::diffusion::ExactDiffusionKernel;
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};
const CELL: usize = 256;
const PRINT_SPECTRA_DISK_BYTES: u64 = 192 * 1024 * 1024;
const MIB: usize = 1024 * 1024;
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DetailError {
    Cancelled,
    InvalidGeometry(String),
    BudgetExceeded { required: usize, budget: usize },
    Unsupported(String),
    Processing(String),
}
impl std::fmt::Display for DetailError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}
impl std::error::Error for DetailError {}
impl From<std::io::Error> for DetailError {
    fn from(e: std::io::Error) -> Self {
        if e.kind() == std::io::ErrorKind::Interrupted {
            Self::Cancelled
        } else {
            Self::Processing(e.to_string())
        }
    }
}
#[derive(Clone, Default)]
pub struct CancellationToken(
    Arc<AtomicBool>,
    // Deterministic unit-test cancellation injection; absent from production/JNI builds.
    #[cfg(test)] Arc<std::sync::atomic::AtomicUsize>,
);
impl CancellationToken {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn cancel(&self) {
        self.0.store(true, Ordering::Release)
    }
    pub fn cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
    #[cfg(test)]
    fn cancel_after_checks(&self, count: usize) { self.1.store(count, Ordering::Release); }
    pub fn check(&self) -> Result<(), DetailError> {
        #[cfg(test)]
        if self.1.fetch_update(Ordering::AcqRel, Ordering::Acquire, |v| v.checked_sub(1)).ok() == Some(1) { self.cancel(); }
        if self.cancelled() {
            Err(DetailError::Cancelled)
        } else {
            Ok(())
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Rect {
    pub x: usize,
    pub y: usize,
    pub w: usize,
    pub h: usize,
}
impl Rect {
    pub fn expand(self, r: usize, w: usize, h: usize) -> Self {
        let x = self.x.saturating_sub(r);
        let y = self.y.saturating_sub(r);
        Self {
            x,
            y,
            w: self
                .x
                .saturating_add(self.w)
                .saturating_add(r)
                .min(w)
                .saturating_sub(x),
            h: self
                .y
                .saturating_add(self.h)
                .saturating_add(r)
                .min(h)
                .saturating_sub(y),
        }
    }
    pub fn valid(self, w: usize, h: usize) -> bool {
        self.w > 0
            && self.h > 0
            && self.x.checked_add(self.w).is_some_and(|v| v <= w)
            && self.y.checked_add(self.h).is_some_and(|v| v <= h)
    }
}
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DetailIdentity {
    pub source_revision: String,
    pub mapping_revision: String,
    pub data_revision: String,
    pub parameters: String,
    pub width: u32,
    pub height: u32,
    pub algorithm: &'static str,
}
#[derive(Clone, Debug)]
pub struct DetailConfig {
    pub width: u32,
    pub height: u32,
    pub params: MobileParams,
    pub source_revision: String,
    pub mapping_revision: String,
    pub data_revision: String,
    pub memory_budget_bytes: usize,
    pub disk_budget_bytes: u64,
    pub stage_cache_bytes: usize,
    pub spectrum_cache_bytes: usize,
}
#[derive(Clone, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResourcePlan {
    pub peak_reserved_bytes: usize,
    pub disk_reserved_bytes: u64,
    pub stage_cache_bytes: usize,
    pub spectrum_cache_bytes: usize,
    pub max_dimension: u32,
}
/// The detail renderer is currently CPU-only. `useGpu` remains an automatic
/// attempt preference in identity; true requests explicitly fall back to CPU.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DetailBackendInfo {
    pub actual_backend: &'static str,
    pub requested_gpu: bool,
    pub cpu_fallback: bool,
}
impl DetailBackendInfo {
    pub fn for_requested_gpu(requested_gpu: bool) -> Self {
        Self {
            actual_backend: "cpu",
            requested_gpu,
            cpu_fallback: requested_gpu,
        }
    }
}
#[derive(Debug)]
pub struct OwnedDetailTile {
    pub identity: DetailIdentity,
    pub backend: DetailBackendInfo,
    pub core_rect: Rect,
    pub sample_rect: Rect,
    pub rgb: Vec<f32>,
}
struct PrivateRoot(PathBuf);
impl Drop for PrivateRoot {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
struct Block {
    pixels: ImageBuf,
    touch: u64,
}
struct Worker {
    blocks: BTreeMap<(usize, usize), Block>,
    bytes: usize,
    clock: u64,
    fft: fft::ValidFft,
}
pub struct DetailContext {
    identity: DetailIdentity,
    backend: DetailBackendInfo,
    plan: ResourcePlan,
    pipe: Pipeline,
    prepared: PreparedStreamedFinish,
    planes: global::PregrainPlanes,
    kernel: Option<ExactDiffusionKernel>,
    support: usize,
    brightness: f64,
    worker: Mutex<Worker>,
    _root: PrivateRoot,
}
impl DetailContext {
    pub fn preflight(config: &DetailConfig) -> Result<ResourcePlan, DetailError> {
        // The catalog deliberately forbids positive/reversal stock -> print.
        // Do not silently enable a route merely because detail is CPU-only.
        if config.params.output_route == "print"
            && crate::profile_catalog::is_reversal(&config.params.film_stock)
        {
            return Err(DetailError::Unsupported(
                "Reversal stocks require their direct route, not print".into(),
            ));
        }
        config
            .params
            .validate()
            .map_err(|e| DetailError::Processing(e.to_string()))?;
        let (w, h) = (config.width as usize, config.height as usize);
        if w == 0 || h == 0 || w.max(h) > 1536 {
            return Err(DetailError::InvalidGeometry("Explicit working grid must be nonempty and <=1536; no implicit original-size allocation".into()));
        }
        if config.source_revision.is_empty()
            || config.mapping_revision.is_empty()
            || config.data_revision.is_empty()
            || config.source_revision.len() > 1024
            || config.mapping_revision.len() > 1024
            || config.data_revision.len() > 1024
        {
            return Err(DetailError::InvalidGeometry(
                "Source, mapping and data revisions are required".into(),
            ));
        }
        let p = &config.params;
        let print_fft = p.output_route == "print" && p.print_diffusion_filter != "off" && p.print_diffusion_strength > 0.;
        if p.checkpoint_directory.as_ref().map_or(false, |v|v.len()>1024) {
            return Err(DetailError::InvalidGeometry("Excessive detail metadata".into()));
        }
        if (p.output_route != "print"
            && !(p.output_route == "direct" && crate::profile_catalog::is_reversal(&p.film_stock)))
            || p.scan_blur != 0.
            || p.scan_sharpen_amount != 0.
            || p.scan_glare_percent != 0.
        {
            return Err(DetailError::Unsupported(
                "Route/scanner/glare requires exact global-stage escalation".into(),
            ));
        }
        // Bounded descriptor/scope/path/entry metadata; no spectrum payload
        // minimum is needed because oversized spectra stream from private disk.
        if print_fft && config.spectrum_cache_bytes < 128 * 1024 {
            return Err(DetailError::BudgetExceeded{required:128*1024,budget:config.spectrum_cache_bytes});
        }
        if config.stage_cache_bytes < CELL * CELL * 3 * 8 {
            return Err(DetailError::BudgetExceeded {
                required: CELL * CELL * 3 * 8,
                budget: config.stage_cache_bytes,
            });
        }
        // Conservative full-grid dependency + local grain intermediates, FFT transpose/scratch,
        // profiles/LUT setup, and BOTH persistent caches, never just returned bitmap bytes.
        let n = w * h;
        let fft_side = (258 + w.min(h)) * 11 / 10 + 16;
        let scratch = n * 24 + fft_side * fft_side * 64 + 64 * MIB + if print_fft {256*1024} else {0};
        let peak = scratch
            .checked_add(config.stage_cache_bytes)
            .and_then(|n| n.checked_add(config.spectrum_cache_bytes))
            .ok_or_else(|| DetailError::InvalidGeometry("Budget overflow".into()))?;
        if peak > config.memory_budget_bytes {
            return Err(DetailError::BudgetExceeded {
                required: peak,
                budget: config.memory_budget_bytes,
            });
        }
        let base_disk = (n as u64) * 128 + 64 * MIB as u64;
        let radius = (w.min(h) / 2).saturating_sub(1).max(1);
        let camera_disk = if p.diffusion_filter != "off" && p.diffusion_strength > 0. {
            24 * n as u64
                + crate::disk_diffusion::estimated_extra_bytes(config.width, config.height, radius)
                + 64 * MIB as u64
        } else {
            0
        };
        let disk = base_disk.max(camera_disk) + 12 * n as u64 + if print_fft {PRINT_SPECTRA_DISK_BYTES} else {0};
        if disk > config.disk_budget_bytes {
            return Err(DetailError::BudgetExceeded {
                required: disk as usize,
                budget: config.disk_budget_bytes.min(usize::MAX as u64) as usize,
            });
        }
        Ok(ResourcePlan {
            peak_reserved_bytes: peak,
            disk_reserved_bytes: disk,
            stage_cache_bytes: config.stage_cache_bytes,
            spectrum_cache_bytes: config.spectrum_cache_bytes,
            max_dimension: 1536,
        })
    }
    pub fn build(
        config: DetailConfig,
        data_dir: &Path,
        source: &Path,
        private_parent: &Path,
        cancel: &CancellationToken,
    ) -> Result<Arc<Self>, DetailError> {
        cancel.check()?;
        let _interactive=crate::export_execution::interactive_work();
        let plan = Self::preflight(&config)?;
        static SEQ: AtomicU64 = AtomicU64::new(0);
        let root_path = private_parent.join(format!(
            "detail-{}-{}",
            std::process::id(),
            SEQ.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root_path)?;
        let root = PrivateRoot(root_path);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&root.0, fs::Permissions::from_mode(0o700))?;
        }
        let owned = root.0.join("source.f32le");
        let mut input = File::open(source)?;
        let expected = config.width as u64 * config.height as u64 * 12;
        if input.metadata()?.len() != expected {
            return Err(DetailError::InvalidGeometry(
                "Source byte length does not match virtual grid".into(),
            ));
        }
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&owned)?;
        let mut left = expected;
        let mut buf = vec![0u8; 32768 * 12];
        while left > 0 {
            cancel.check()?;
            let len = left.min(buf.len() as u64) as usize;
            input.read_exact(&mut buf[..len])?;
            if buf[..len]
                .chunks_exact(4)
                .any(|v| !f32::from_le_bytes([v[0], v[1], v[2], v[3]]).is_finite())
            {
                return Err(DetailError::Processing("Nonfinite source".into()));
            }
            output.write_all(&buf[..len])?;
            left -= len as u64;
        }
        drop(output);
        drop(input);
        cancel.check()?;
        let film = load_profile_by_name(data_dir, &config.params.film_stock)
            .map_err(|e| DetailError::Processing(e.to_string()))?;
        let paper = load_profile_by_name(data_dir, &config.params.paper_stock)
            .map_err(|e| DetailError::Processing(e.to_string()))?;
        let mut params = config.params.runtime_for_film(&film);
        let pitch = params.camera.film_format_mm * 1000. / config.width.max(config.height) as f32;
        params.camera.pixel_size_um_override = Some(pitch);
        params.film_render.grain.coordinate_origin = Some([0, 0]);
        let pipe = Pipeline::new_with_spectral(film, paper, params, data_dir)
            .map_err(|e| DetailError::Processing(e.to_string()))?;
        cancel.check()?;
        let support = global::grain_support(&pipe, pitch)?;
        let prepared = pipe
            .prepare_streamed_finish_spatial(&CpuBackend)
            .map_err(DetailError::Processing)?;
        cancel.check()?;
        let kernel = if !pipe.params.io.scan_film && pipe.params.enlarger.diffusion_filter.active {
            ExactDiffusionKernel::new_cancellable(
                &pipe.params.enlarger.diffusion_filter.to_model(),
                pitch as f64,
                config.width as usize,
                config.height as usize,
                || cancel.cancelled(),
            )
            .map_err(|_| DetailError::Cancelled)?
        } else {
            None
        };
        let planes = global::build_pregrain_with_correction(
            &pipe,
            &owned,
            config.width,
            config.height,
            &root.0.join("pregrain"),
            prepared.filming_exposure_correction,
            cancel,
        )?;
        fs::remove_file(owned)?;
        let identity = DetailIdentity {
            source_revision: config.source_revision,
            mapping_revision: config.mapping_revision,
            data_revision: config.data_revision,
            parameters: serde_json::to_string(&config.params)
                .map_err(|e| DetailError::Processing(e.to_string()))?,
            width: config.width,
            height: config.height,
            algorithm: "owned-detail-v1-f64-fixed256-gutter1",
        };
        cancel.check()?;
        let fft = if kernel.is_some() {
            let side = (258 + config.width.min(config.height) as usize) * 11 / 10 + 16;
            fft::ValidFft::with_disk_cache(
                plan.spectrum_cache_bytes, &root.0.join("print-spectra"),
                PRINT_SPECTRA_DISK_BYTES,
                serde_json::to_vec(&identity).map_err(|e|DetailError::Processing(e.to_string()))?,
                side * side * 64 + 256 * 1024,
            )?
        } else { fft::ValidFft::new(plan.spectrum_cache_bytes) };
        cancel.check()?;
        Ok(Arc::new(Self {
            identity,
            backend: DetailBackendInfo::for_requested_gpu(config.params.use_gpu),
            pipe,
            prepared,
            planes,
            kernel,
            support,
            brightness: 2f64.powf(config.params.brightness_ev),
            worker: Mutex::new(Worker {
                blocks: BTreeMap::new(),
                bytes: 0,
                clock: 0,
                fft,
            }),
            plan,
            _root: root,
        }))
    }
    pub fn backend_info(&self) -> DetailBackendInfo {
        self.backend
    }
    pub fn pregrain_metrics(&self) -> &PregrainMetrics {
        &self.planes.metrics
    }
    pub fn metered_ev(&self) -> Option<f32> {
        self.planes.metered_ev
    }
    pub fn pixel_pitch_um(&self) -> f32 {
        self.planes.pitch
    }
    pub fn retained_disk_bytes(&self) -> u64 {
        self.planes.disk_bytes()
    }
    pub fn identity(&self) -> &DetailIdentity {
        &self.identity
    }
    pub fn resource_plan(&self) -> &ResourcePlan {
        &self.plan
    }
    pub fn clear_cache(&self) -> Result<(), DetailError> {
        let mut w = self
            .worker
            .lock()
            .map_err(|_| DetailError::Processing("Worker lock poisoned".into()))?;
        w.blocks.clear();
        w.bytes = 0;
        w.fft.clear()?;
        Ok(())
    }
    pub fn print_cache_stats(&self) -> Result<DetailPrintCacheStats, DetailError> {
        let w=self.worker.lock().map_err(|_|DetailError::Processing("Worker lock poisoned".into()))?;
        Ok(w.fft.stats())
    }
    pub fn cache_bytes(&self) -> Result<usize, DetailError> {
        let w = self
            .worker
            .lock()
            .map_err(|_| DetailError::Processing("Worker lock poisoned".into()))?;
        Ok(w.bytes)
    }
    pub fn render_cell(
        self: &Arc<Self>,
        cell_x: usize,
        cell_y: usize,
        cancel: &CancellationToken,
    ) -> Result<OwnedDetailTile, DetailError> {
        let _interactive=crate::export_execution::interactive_work();
        cancel.check()?;
        let (w, h) = (self.identity.width as usize, self.identity.height as usize);
        let x = cell_x
            .checked_mul(CELL)
            .ok_or_else(|| DetailError::InvalidGeometry("Cell overflow".into()))?;
        let y = cell_y
            .checked_mul(CELL)
            .ok_or_else(|| DetailError::InvalidGeometry("Cell overflow".into()))?;
        if x >= w || y >= h {
            return Err(DetailError::InvalidGeometry(
                "Cell outside virtual image".into(),
            ));
        }
        let core = Rect {
            x,
            y,
            w: CELL.min(w - x),
            h: CELL.min(h - y),
        };
        let sample = core.expand(1, w, h);
        let needed = sample.expand(self.kernel.as_ref().map_or(0, |k| k.radius), w, h);
        let mut worker = self
            .worker
            .try_lock()
            .map_err(|_| DetailError::Processing("Context already has an active worker".into()))?;
        cancel.check()?;
        let mut raw = ImageBuf::new(needed.w as u32, needed.h as u32);
        for by in needed.y / CELL..=(needed.y + needed.h - 1) / CELL {
            for bx in needed.x / CELL..=(needed.x + needed.w - 1) / CELL {
                cancel.check()?;
                worker.clock = worker.clock.wrapping_add(1);
                let touch = worker.clock;
                let rect = Rect {
                    x: bx * CELL,
                    y: by * CELL,
                    w: CELL.min(w - bx * CELL),
                    h: CELL.min(h - by * CELL),
                };
                if !worker.blocks.contains_key(&(bx, by)) {
                    let pixels = self.make_block(rect, cancel)?;
                    let bytes = pixels.data.len() * 8;
                    while worker.bytes + bytes > self.plan.stage_cache_bytes {
                        let key = worker
                            .blocks
                            .iter()
                            .min_by_key(|(_, b)| b.touch)
                            .map(|(k, _)| *k)
                            .ok_or_else(|| DetailError::BudgetExceeded {
                                required: bytes,
                                budget: self.plan.stage_cache_bytes,
                            })?;
                        if let Some(old) = worker.blocks.remove(&key) {
                            worker.bytes -= old.pixels.data.len() * 8;
                        }
                    }
                    worker.bytes += bytes;
                    worker.blocks.insert((bx, by), Block { pixels, touch });
                }
                let block = worker
                    .blocks
                    .get_mut(&(bx, by))
                    .ok_or_else(|| DetailError::Processing("Missing block".into()))?;
                block.touch = touch;
                let ix = rect.x.max(needed.x);
                let iy = rect.y.max(needed.y);
                let ex = (rect.x + rect.w).min(needed.x + needed.w);
                let ey = (rect.y + rect.h).min(needed.y + needed.h);
                for row in iy..ey {
                    cancel.check()?;
                    let a = ((row - needed.y) * needed.w + ix - needed.x) * 3;
                    let b = ((row - rect.y) * rect.w + ix - rect.x) * 3;
                    raw.data[a..a + (ex - ix) * 3]
                        .copy_from_slice(&block.pixels.data[b..b + (ex - ix) * 3]);
                }
            }
        }
        let filtered = if let Some(k) = &self.kernel {
            worker.fft.apply(&raw, needed, sample, (w, h), k, cancel)?
        } else {
            crop(&raw, needed, sample, cancel)?
        };
        drop(raw);
        let mut rgb = Vec::with_capacity(sample.w * sample.h * 3);
        for yy in (0..sample.h).step_by(16) {
            cancel.check()?;
            let r = Rect {
                x: sample.x,
                y: sample.y + yy,
                w: sample.w,
                h: 16.min(sample.h - yy),
            };
            let chunk = crop(&filtered, sample, r, cancel)?;
            let scanned = if self.pipe.params.io.scan_film {
                // Direct reversal route: the cache contains grained film density,
                // never paper irradiance. Prepared finish has no printing stage.
                self.prepared
                    .process_density_chunk_unclipped(&chunk, &CpuBackend)
            } else {
                let paper = self
                    .prepared
                    .develop_print_raw_chunk(&chunk, &CpuBackend)
                    .map_err(DetailError::Processing)?;
                self.prepared
                    .scan_print_density_chunk_unclipped(&paper)
                    .map_err(DetailError::Processing)?
            };
            for pixel in scanned.data.chunks_exact(3) {
                let linear = spektrafilm_core::output_color::to_linear_srgb(
                    [
                        pixel[0].clamp(from_f64(0.), from_f64(1.)) as f64,
                        pixel[1].clamp(from_f64(0.), from_f64(1.)) as f64,
                        pixel[2].clamp(from_f64(0.), from_f64(1.)) as f64,
                    ],
                    &self.pipe.params.io.output_color_space,
                );
                for v in linear {
                    let v = (v * self.brightness) as f32;
                    if !v.is_finite() {
                        return Err(DetailError::Processing("Nonfinite output".into()));
                    }
                    rgb.push(v);
                }
            }
        }
        cancel.check()?;
        Ok(OwnedDetailTile {
            identity: self.identity.clone(),
            backend: self.backend,
            core_rect: core,
            sample_rect: sample,
            rgb,
        })
    }
    fn make_block(&self, rect: Rect, cancel: &CancellationToken) -> Result<ImageBuf, DetailError> {
        let mut output = ImageBuf::new(rect.w as u32, rect.h as u32);
        let (w, h) = (self.identity.width as usize, self.identity.height as usize);
        for yy in (0..rect.h).step_by(64) {
            for xx in (0..rect.w).step_by(64) {
                cancel.check()?;
                let part = Rect {
                    x: rect.x + xx,
                    y: rect.y + yy,
                    w: 64.min(rect.w - xx),
                    h: 64.min(rect.h - yy),
                };
                let input = part.expand(self.support, w, h);
                let pre = self.planes.read_rect(input, cancel)?;
                let mut params = self.pipe.params.clone();
                params.film_render.grain.coordinate_origin = Some([input.x as u32, input.y as u32]);
                let grain = filming::grain_density(pre, &self.pipe.film, &params, &CpuBackend);
                cancel.check()?;
                let valid = crop(&grain, input, part, cancel)?;
                let raw = if self.pipe.params.io.scan_film {
                    valid
                } else {
                    self.prepared
                        .expose_print_raw_chunk(&valid)
                        .map_err(DetailError::Processing)?
                };
                for y in 0..part.h {
                    let a = ((yy + y) * rect.w + xx) * 3;
                    output.data[a..a + part.w * 3]
                        .copy_from_slice(&raw.data[y * part.w * 3..(y + 1) * part.w * 3]);
                }
            }
        }
        Ok(output)
    }
}
fn crop(
    image: &ImageBuf,
    src: Rect,
    dst: Rect,
    c: &CancellationToken,
) -> Result<ImageBuf, DetailError> {
    if !dst.valid(src.x + src.w, src.y + src.h) || dst.x < src.x || dst.y < src.y {
        return Err(DetailError::InvalidGeometry("Crop outside source".into()));
    }
    let mut v = Vec::with_capacity(dst.w * dst.h * 3);
    for y in dst.y..dst.y + dst.h {
        c.check()?;
        let a = ((y - src.y) * src.w + dst.x - src.x) * 3;
        v.extend_from_slice(&image.data[a..a + dst.w * 3]);
    }
    Ok(ImageBuf::from_data(dst.w as u32, dst.h as u32, v))
}
#[cfg(test)]
mod tests {
    use super::*;
    fn config(w: u32, h: u32) -> DetailConfig {
        DetailConfig{width:w,height:h,params:MobileParams::parse(r#"{"paperStock":"kodak_2383","useGpu":false,"autoExposure":true,"metering":"partial","diffusionFilter":"black_pro_mist","printDiffusionFilter":"black_pro_mist","brightnessEv":0.859999895}"#).unwrap(),source_revision:"fixture-v1".into(),mapping_revision:"oriented-v1".into(),data_revision:"bundled-v1".into(),memory_budget_bytes:512*MIB,disk_budget_bytes:1024*1024*1024,stage_cache_bytes:CELL*CELL*24,spectrum_cache_bytes:64*MIB}
    }
    fn root() -> PathBuf {
        static N: AtomicU64 = AtomicU64::new(0);
        let p = std::env::temp_dir().join(format!(
            "owned-detail-tests-{}-{}",
            std::process::id(),
            N.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&p).unwrap();
        p
    }
    fn data() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data")
    }
    fn input(root: &Path, w: u32, h: u32) -> (PathBuf, ImageBuf, Vec<u8>) {
        let pixels: Vec<f32> = (0..w as usize * h as usize * 3)
            .map(|i| {
                if i < 12 {
                    100.
                } else {
                    ((i * 37) % 251) as f32 / 150.
                }
            })
            .collect();
        let bytes: Vec<u8> = pixels.iter().flat_map(|v| v.to_le_bytes()).collect();
        let path = root.join("input.f32le");
        fs::write(&path, &bytes).unwrap();
        (
            path,
            ImageBuf::from_data(w, h, pixels.iter().map(|v| from_f64(*v as f64)).collect()),
            bytes,
        )
    }
    #[test]
    fn print_disk_quota_preserves_1536_cap_and_conservative_memory_plan() {
        let mut c=config(1152,1536);
        c.params.diffusion_filter="off".into();c.params.print_diffusion_filter="off".into();
        let off=DetailContext::preflight(&c).unwrap();
        c.params.print_diffusion_filter="black_pro_mist".into();
        let on=DetailContext::preflight(&c).unwrap();
        assert_eq!(on.disk_reserved_bytes-off.disk_reserved_bytes,PRINT_SPECTRA_DISK_BYTES);
        assert_eq!(on.peak_reserved_bytes-off.peak_reserved_bytes,256*1024);
        assert_eq!(on.max_dimension,1536);
        let original_cache=c.spectrum_cache_bytes;c.spectrum_cache_bytes=127*1024;
        assert!(matches!(DetailContext::preflight(&c),Err(DetailError::BudgetExceeded{..})));
        c.spectrum_cache_bytes=original_cache;
        c.disk_budget_bytes=on.disk_reserved_bytes-1;
        assert!(matches!(DetailContext::preflight(&c),Err(DetailError::BudgetExceeded{..})));
        c.disk_budget_bytes=1024*1024*1024;c.params.print_diffusion_filter="off".into();c.height=2048;
        assert!(matches!(DetailContext::preflight(&c),Err(DetailError::InvalidGeometry(_))));
        c.height=1536;c.source_revision="x".repeat(1025);
        assert!(matches!(DetailContext::preflight(&c),Err(DetailError::InvalidGeometry(_))));
    }
    #[test]
    fn malformed_geometry_budget_and_unsupported() {
        let mut c = config(0, 10);
        assert!(matches!(
            DetailContext::preflight(&c),
            Err(DetailError::InvalidGeometry(_))
        ));
        c.width = 4608;
        assert!(DetailContext::preflight(&c).is_err());
        c.width = 300;
        c.memory_budget_bytes = 1;
        assert!(matches!(
            DetailContext::preflight(&c),
            Err(DetailError::BudgetExceeded { .. })
        ));
        c.memory_budget_bytes = 512 * MIB;
        c.params.scan_blur = 1.;
        assert!(matches!(
            DetailContext::preflight(&c),
            Err(DetailError::Unsupported(_))
        ));
        c.params.scan_blur = 0.;
        c.params.brightness_ev = f64::NAN;
        assert!(DetailContext::preflight(&c).is_err());
        let p = config(1536, 64);
        assert!(DetailContext::preflight(&p).unwrap().disk_reserved_bytes > 120 * 1024 * 1024);
    }
    #[test]
    fn pre_cancel_does_not_create_context() {
        let root = root();
        let c = config(10, 10);
        let token = CancellationToken::new();
        token.cancel();
        let result = DetailContext::build(c, &data(), &root.join("absent"), &root, &token);
        assert!(matches!(result, Err(DetailError::Cancelled)));
        assert_eq!(fs::read_dir(&root).unwrap().count(), 0);
        fs::remove_dir(root).unwrap();
    }
    #[test]
    fn exact_gutter_edges_hdr_cache_churn_and_source_immutable() {
        let root = root();
        let (w, h) = (521, 517);
        let c = config(w, h);
        let (path, image, bytes) = input(&root, w, h);
        let token = CancellationToken::new();
        let ctx = DetailContext::build(c, &data(), &path, &root, &token).unwrap();
        let mut reference = ctx.pipe.process(image, &CpuBackend);
        for v in &mut reference.data {
            *v *= from_f64(ctx.brightness)
        }
        let mut first = None;
        for (cx, cy) in [(0, 0), (1, 0), (0, 1), (1, 1), (2, 2), (0, 0)] {
            let tile = ctx.render_cell(cx, cy, &token).unwrap();
            assert_eq!(tile.core_rect.x, cx * 256);
            assert_eq!(
                tile.sample_rect,
                tile.core_rect.expand(1, w as usize, h as usize)
            );
            let expected = crop(
                &reference,
                Rect {
                    x: 0,
                    y: 0,
                    w: w as usize,
                    h: h as usize,
                },
                tile.sample_rect,
                &token,
            )
            .unwrap();
            let max = tile
                .rgb
                .iter()
                .zip(&expected.data)
                .map(|(a, b)| (*a - *b as f32).abs())
                .fold(0f32, f32::max);
            assert!(max < 1e-5, "same-grid max error {max}");
            assert!(ctx.cache_bytes().unwrap() <= ctx.plan.stage_cache_bytes);
            if cx == 0 && cy == 0 {
                if let Some(v) = &first {
                    assert_eq!(v, &tile.rgb)
                } else {
                    first = Some(tile.rgb)
                }
            }
        }
        ctx.clear_cache().unwrap();
        assert_eq!(ctx.cache_bytes().unwrap(), 0);
        assert_eq!(first.unwrap(), ctx.render_cell(0, 0, &token).unwrap().rgb);
        assert!(matches!(
            ctx.render_cell(usize::MAX, 0, &token),
            Err(DetailError::InvalidGeometry(_))
        ));
        assert_eq!(fs::read(path).unwrap(), bytes);
        let weak = Arc::downgrade(&ctx);
        let worker = ctx.clone();
        drop(ctx);
        assert!(weak.upgrade().is_some());
        drop(worker);
        assert!(weak.upgrade().is_none());
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn running_worker_retains_context_until_cancel_exit() {
        let root = root();
        let (path, _, _) = input(&root, 273, 267);
        let token = CancellationToken::new();
        let ctx = DetailContext::build(config(273, 267), &data(), &path, &root, &token).unwrap();
        let weak = Arc::downgrade(&ctx);
        let worker = ctx.clone();
        let ct = token.clone();
        // Never probe the render mutex: that can make the real worker return BUSY.
        // Start ownership is rendezvoused independently, then cancel deterministically
        // after multiple read/grain/crop chunks have executed.
        token.cancel_after_checks(500);
        let start = Arc::new(std::sync::Barrier::new(2));
        let entered = start.clone();
        let join = std::thread::spawn(move || { entered.wait(); worker.render_cell(0, 0, &ct) });
        drop(ctx);
        assert!(weak.upgrade().is_some());
        start.wait();
        assert!(matches!(join.join().unwrap(), Err(DetailError::Cancelled)));
        assert!(weak.upgrade().is_none());
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn invalid_source_and_cancelled_workers_clean_private_files() {
        let root = root();
        let c = config(8, 9);
        let (path, _, _) = input(&root, 8, 9);
        fs::write(&path, vec![0u8; 4]).unwrap();
        assert!(
            DetailContext::build(c.clone(), &data(), &path, &root, &CancellationToken::new())
                .is_err()
        );
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        let (path, _, _) = input(&root, 8, 9);
        for _ in 0..3 {
            let token = CancellationToken::new();
            let ctx = DetailContext::build(c.clone(), &data(), &path, &root, &token).unwrap();
            token.cancel();
            assert!(matches!(
                ctx.render_cell(0, 0, &token),
                Err(DetailError::Cancelled)
            ));
            drop(ctx);
            assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        }
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn requested_gpu_uses_explicit_cpu_fallback_without_identity_rewrite() {
        let root = root();
        let (path, _, _) = input(&root, 32, 24);
        let token = CancellationToken::new();
        let mut c = config(32, 24);
        let cpu = DetailContext::build(c.clone(), &data(), &path, &root, &token).unwrap();
        c.params.use_gpu = true;
        let fallback = DetailContext::build(c, &data(), &path, &root, &token).unwrap();
        assert_eq!(
            cpu.backend_info(),
            DetailBackendInfo::for_requested_gpu(false)
        );
        assert_eq!(
            fallback.backend_info(),
            DetailBackendInfo::for_requested_gpu(true)
        );
        let a = cpu.render_cell(0, 0, &token).unwrap();
        let b = fallback.render_cell(0, 0, &token).unwrap();
        assert_eq!(a.rgb, b.rgb);
        assert_eq!(a.backend, cpu.backend_info());
        assert_eq!(b.backend, fallback.backend_info());
        let requested: serde_json::Value =
            serde_json::from_str(&fallback.identity.parameters).unwrap();
        assert_eq!(requested["useGpu"], true);
        assert_ne!(cpu.identity.parameters, fallback.identity.parameters);
        drop(cpu);
        drop(fallback);
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn all_twenty_stock_profiles_are_rendered_or_explicitly_unsupported() {
        let root = root();
        let (path, image, original) = input(&root, 32, 24);
        let token = CancellationToken::new();
        let mut rendered = 0;
        let mut unsupported = 0;
        for film in crate::profile_catalog::FILMS {
            let mut c = config(32, 24);
            c.params.film_stock = (*film).into();
            c.params.use_gpu = true; // Android default automatic-attempt preference.
            if crate::profile_catalog::is_reversal(film) {
                assert!(
                    matches!(
                        DetailContext::preflight(&c),
                        Err(DetailError::Unsupported(_))
                    ),
                    "{film}"
                );
                c.params.output_route = "direct".into();
                assert!(DetailContext::preflight(&c).is_ok(), "{film}");
                unsupported += 1;
                println!(
                    "PROFILE {film}: unsupported reversal print; direct route separately validated"
                );
                continue;
            }
            let ctx = DetailContext::build(c, &data(), &path, &root, &token)
                .unwrap_or_else(|e| panic!("{film}: {e}"));
            let result = ctx.render_cell(0, 0, &token).unwrap();
            let reference = ctx.pipe.process(image.clone(), &CpuBackend);
            let max = result
                .rgb
                .iter()
                .zip(&reference.data)
                .map(|(a, b)| (*a - (*b as f64 * ctx.brightness) as f32).abs())
                .fold(0f32, f32::max);
            assert!(max < 1e-5, "{film}: same-grid maximum error {max}");
            assert_eq!(result.backend.actual_backend, "cpu");
            assert!(result.backend.cpu_fallback);
            println!("PROFILE {film}: print cpu-fallback PASS maxAbs={max}");
            rendered += 1;
            drop(ctx);
        }
        assert_eq!((rendered, unsupported), (16, 4));
        assert_eq!(fs::read(path).unwrap(), original);
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn reversal_direct_routes_match_reference_with_corrections_gutters_and_rebuild() {
        let root = root();
        let (w, h) = (521, 517);
        let (path, image, original) = input(&root, w, h);
        let token = CancellationToken::new();
        for film in [
            "fujifilm_provia_100f",
            "fujifilm_velvia_100",
            "kodak_ektachrome_100",
            "kodak_kodachrome_64",
        ] {
            let mut without_correction = None;
            for correction in [false, true] {
                let mut c = config(w, h);
                c.params.film_stock = film.into();
                c.params.output_route = "direct".into();
                c.params.use_gpu = true;
                c.params.scan_white_correction = correction;
                c.params.scan_black_correction = correction;
                let ctx = DetailContext::build(c, &data(), &path, &root, &token)
                    .unwrap_or_else(|e| panic!("{film}: {e}"));
                assert!(ctx.pipe.params.io.scan_film);
                assert!(
                    ctx.kernel.is_none(),
                    "direct scan must not construct a print PSF"
                );
                let reference = ctx.pipe.process(image.clone(), &CpuBackend);
                let mut maximum = 0f32;
                let mut interior = None;
                for (x, y) in [(0, 0), (1, 1), (2, 2)] {
                    let tile = ctx.render_cell(x, y, &token).unwrap();
                    if x == 1 {
                        assert_eq!((tile.sample_rect.w, tile.sample_rect.h), (258, 258));
                    }
                    let expected = crop(
                        &reference,
                        Rect {
                            x: 0,
                            y: 0,
                            w: w as usize,
                            h: h as usize,
                        },
                        tile.sample_rect,
                        &token,
                    )
                    .unwrap();
                    let max = tile
                        .rgb
                        .iter()
                        .zip(&expected.data)
                        .map(|(a, b)| (*a - (*b as f64 * ctx.brightness) as f32).abs())
                        .fold(0f32, f32::max);
                    maximum = maximum.max(max);
                    assert!(
                        max < 1e-5,
                        "{film} corrections={correction} {x},{y} maxAbs={max}"
                    );
                    assert!(ctx.cache_bytes().unwrap() <= ctx.plan.stage_cache_bytes);
                    if x == 1 {
                        interior = Some(tile.rgb);
                    }
                }
                ctx.clear_cache().unwrap();
                let rebuilt = ctx.render_cell(1, 1, &token).unwrap();
                assert_eq!(interior.unwrap(), rebuilt.rgb);
                let cancelled = CancellationToken::new();
                cancelled.cancel();
                assert!(matches!(
                    ctx.render_cell(1, 1, &cancelled),
                    Err(DetailError::Cancelled)
                ));
                if correction {
                    assert_ne!(
                        without_correction.as_ref().unwrap(),
                        &rebuilt.rgb,
                        "{film} correction test must exercise a change"
                    );
                } else {
                    without_correction = Some(rebuilt.rgb);
                }
                println!(
                    "DIRECT {film} correction={correction} HDR/gutter/rebuild PASS maxAbs={maximum}"
                );
                drop(ctx);
            }
        }
        // Do not broaden negative-film direct scans or scanner spatial effects.
        let mut rejected = config(32, 24);
        rejected.params.output_route = "direct".into();
        assert!(matches!(
            DetailContext::preflight(&rejected),
            Err(DetailError::Unsupported(_))
        ));
        rejected.params.film_stock = "fujifilm_provia_100f".into();
        rejected.params.scan_glare_percent = 1.;
        assert!(matches!(
            DetailContext::preflight(&rejected),
            Err(DetailError::Unsupported(_))
        ));
        assert_eq!(fs::read(path).unwrap(), original);
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn running_direct_worker_cancellation_retains_owned_context_until_exit() {
        let root = root();
        let (path, _, _) = input(&root, 521, 517);
        let token = CancellationToken::new();
        let mut c = config(521, 517);
        c.params.film_stock = "fujifilm_provia_100f".into();
        c.params.output_route = "direct".into();
        let ctx = DetailContext::build(c, &data(), &path, &root, &token).unwrap();
        let weak = Arc::downgrade(&ctx);
        let worker = ctx.clone();
        let ct = token.clone();
        // Never probe the render mutex: that can make the real worker return BUSY.
        // Start ownership is rendezvoused independently, then cancel deterministically
        // after multiple read/grain/crop chunks have executed.
        token.cancel_after_checks(500);
        let start = Arc::new(std::sync::Barrier::new(2));
        let entered = start.clone();
        let join = std::thread::spawn(move || { entered.wait(); worker.render_cell(1, 1, &ct) });
        drop(ctx);
        assert!(weak.upgrade().is_some());
        start.wait();
        assert!(matches!(join.join().unwrap(), Err(DetailError::Cancelled)));
        assert!(weak.upgrade().is_none());
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
}

// Isolated owned detail JNI; no legacy bridge exports are changed.
pub mod bridge;
mod jni;
