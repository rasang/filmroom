//! Experimental preview-owned exact same-working-grid detail API and isolated JNI. No shipping/export integration.
//! One active worker per context; callers retain Arc ownership through worker exit.
mod fft;
mod global;
#[cfg(feature = "adreno-gpu")]
mod gpu;
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
    time::Instant,
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
    GpuCompletionUncertain,
    GpuExecutionFailed,
    InvalidGeometry(String),
    BudgetExceeded { required: usize, budget: usize },
    StorageBudgetExceeded { required: u64, budget: u64 },
    MemoryPlanExceeded { required: usize, budget: usize },
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
    /// Operational admission only; never rewrites scientific params.use_gpu.
    pub gpu_attempt: bool,
    pub region_output: bool,
    /// Demand-only proof-tile hint in this working grid; excluded from science identity.
    pub initial_region: Option<Rect>,
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
    pub region_output: bool,
    pub gpu_attempt: bool,
    pub gpu_execution_budget_bytes: usize,
    pub gpu_plan_reason: &'static str,
    pub cpu_peak_reserved_bytes: usize,
    pub peak_reserved_bytes: usize,
    pub disk_reserved_bytes: u64,
    pub stage_cache_bytes: usize,
    pub spectrum_cache_bytes: usize,
    pub max_dimension: u32,
}
/// Actual execution identity. GPU uses the separately versioned resident approximation;
/// CPU remains the exact full-working-grid implementation. Never infer this from useGpu.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DetailBackendInfo {
    pub actual_backend: &'static str,
    pub requested_gpu: bool,
    pub cpu_fallback: bool,
    pub reason: &'static str,
    pub accelerated_stage: &'static str,
    pub gpu_batches: u64,
}
impl DetailBackendInfo {
    pub fn quality(&self) -> &'static str {
        if self.actual_backend == "wgpu" {"ApproximateSameWorkingGrid"} else {"ExactSameWorkingGrid"}
    }
    pub fn for_requested_gpu(requested_gpu: bool) -> Self {
        Self {
            actual_backend: "cpu",
            requested_gpu,
            cpu_fallback: requested_gpu,
            reason: if requested_gpu {"gpu_not_built"} else {"disabled_by_user"},
            accelerated_stage: "none", gpu_batches: 0,
        }
    }
}
/// Per-operation diagnostics only. No source identity or geometry is exposed.
/// All phase times are elapsed milliseconds. Region counters/times describe only
/// this call (including its misses), never a difference from the last successful call.
/// Absent phases were not measured; errors/cancellation never publish success metrics.
#[derive(Clone, Debug, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DetailPerformance {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tile_cache_hits: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tile_cache_misses: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gpu_submit_delta: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_read_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chain_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result_copy_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub profile_prepare_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pipeline_prepare_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub global_fields_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub proof_tile_ms: Option<f64>,
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
// Tight output is used only when it fits one bounded FFT call and removes at
// least half of the old-cell output. Larger demands retain canonical samples:
// this preserves their cache/halo shapes rather than inventing many tail FFTs.
fn tight_region(core:Rect, sample:Rect, w:usize, h:usize)->bool {
    if sample.w>CELL+2 || sample.h>CELL+2 {return false;}
    let mut legacy_pixels=0;
    for y in core.y/CELL..=(core.y+core.h-1)/CELL {
        for x in core.x/CELL..=(core.x+core.w-1)/CELL {
            let cell=Rect{x:x*CELL,y:y*CELL,w:CELL.min(w-x*CELL),h:CELL.min(h-y*CELL)}.expand(1,w,h);
            legacy_pixels+=cell.w*cell.h;
        }
    }
    sample.w*sample.h*2<=legacy_pixels
}
#[cfg(test)]
#[derive(Debug,Default)]
struct RegionWorkStats { stage_hits:u64, stage_misses:u64, samples:BTreeMap<(usize,usize),u64> }
struct Worker {
    blocks: BTreeMap<(usize, usize), Block>,
    bytes: usize,
    clock: u64,
    fft: fft::ValidFft,
    #[cfg(test)] stats:RegionWorkStats,
}
pub struct DetailContext {
    identity: DetailIdentity,
    backend: DetailBackendInfo,
    plan: ResourcePlan,
    pipe: Pipeline,
    prepared: Option<PreparedStreamedFinish>,
    planes: Option<global::PregrainPlanes>,
    #[cfg(feature = "adreno-gpu")]
    gpu: Option<gpu::GpuDetail>,
    kernel: Option<ExactDiffusionKernel>,
    support: usize,
    brightness: f64,
    render_guard: Mutex<()>,
    worker: Mutex<Worker>,
    _root: PrivateRoot,
}
/// Native output stays outside the execution envelope and persistent caches.
fn gpu_output_bytes(config: &DetailConfig) -> usize {
    if config.region_output {config.width as usize * config.height as usize * 16 + 128 * 1024}
    else {(CELL+2)*(CELL+2)*16 + 128 * 1024}
}
/// Same diffusion model/grid as Pipeline::gpu_diffusion_working_bytes. Stock
/// calibration changes halation; its opted-in kernel reserve is included too.
/// Use actual downsampled grids rather than pessimistically charging 192*n for
/// both filters when they are off, no-ops, direct-scan, or downsampled.
fn planned_gpu_field_bytes(config: &DetailConfig) -> Option<usize> {
    let p=config.params.runtime();
    let pitch=p.camera.film_format_mm*1000./config.width.max(config.height) as f32;
    let mut bytes=std::mem::size_of::<spektrafilm_core::pipeline::DiffusionScatteredFields>();
    for (active,filter) in [
        (p.camera.diffusion_filter.active,&p.camera.diffusion_filter),
        (!p.io.scan_film && p.enlarger.diffusion_filter.active,&p.enlarger.diffusion_filter),
    ] {
        if !active {continue;}
        if let Some(plan)=spektrafilm_model::diffusion::diffusion_gpu_plan(
            &filter.to_model(),pitch as f64,config.width,config.height) {
            bytes=bytes.checked_add((plan.small_w as usize).checked_mul(plan.small_h as usize)?.checked_mul(192)?)?;
        }
    }
    // The only current physical-PSF opt-in is this exact product stock.
    // Preflight has no profile yet, but geometry does not depend on its curves.
    let h=&p.film_render.halation;
    if config.params.film_stock=="cinestill_800t" && h.active && h.halation_amount>0. && h.base_thickness_um>0. {
        let kernels=spektrafilm_gpu::back_reflection_working_bytes(h.base_thickness_um,
            h.halation_spatial_scale,h.halation_amount,pitch as f64)?;
        bytes=bytes.checked_add(usize::try_from(kernels).ok()?)?;
    }
    Some(bytes)
}
/// Prepay an honest GPU execution envelope, bounded by the request cap. The
/// whole-grid 256*n bound covers every possible composed halo; actual execution
/// still selects budget/binding-admissible tiles. A low-cap request retains the
/// ORIGINAL CPU plan, rather than failing merely because GPU was requested.
fn planned_execution_reservation(config: &DetailConfig, cpu_peak: usize)
    -> Result<(usize,usize,&'static str),DetailError> {
    let cpu=|reason|Ok((cpu_peak,0,reason));
    if !config.params.use_gpu {return cpu("disabled_by_user");}
    if !config.gpu_attempt {return cpu("gpu_admission_cpu_attempt");}
    if !cfg!(feature="adreno-gpu") {return cpu("gpu_not_built");}
    if config.params.auto_exposure && config.params.preview_metering.is_none() {
        return cpu("shared_preview_metering_required");
    }
    let overflow=||DetailError::InvalidGeometry("GPU reservation overflow".into());
    let field_bytes=planned_gpu_field_bytes(config).ok_or_else(overflow)?;
    let outside=gpu_output_bytes(config).checked_add(config.stage_cache_bytes)
        .and_then(|n|n.checked_add(config.spectrum_cache_bytes)).ok_or_else(overflow)?;
    let Some(available)=config.memory_budget_bytes.checked_sub(outside) else {return cpu("gpu_detail_memory_budget");};
    // The shared tile planner accepts 128..512 MiB execution envelopes. Round
    // DOWN the cap and UP the requested bound, never borrow unreserved fractions.
    let available_mib=(available/MIB).min(512);
    let n=config.width as usize*config.height as usize;
    let whole=96usize.checked_mul(MIB).and_then(|v|v.checked_add(n.checked_mul(256)?))
        .and_then(|v|v.checked_add(field_bytes)).ok_or_else(overflow)?;
    let desired_mib=whole.checked_add(MIB-1).ok_or_else(overflow)?.checked_div(MIB).unwrap().max(128);
    let execution=desired_mib.min(available_mib)*MIB;
    // Reserve 96 MiB fixed even if a completed GPU attempt later enters the CPU
    // path. An operational CPU-only attempt never initializes GPU and needs no
    // extra 32 MiB beyond its established CPU plan.
    let cpu_after_gpu=cpu_peak.checked_add(32*MIB).ok_or_else(overflow)?;
    if execution<128*MIB || execution<=96*MIB+field_bytes || cpu_after_gpu>config.memory_budget_bytes {
        return cpu("gpu_detail_memory_budget");
    }
    let peak=outside.checked_add(execution).ok_or_else(overflow)?.max(cpu_after_gpu);
    if peak>config.memory_budget_bytes {return cpu("gpu_detail_memory_budget");}
    Ok((peak,execution,"gpu_prepaid_execution"))
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
        if config.params.pre_imaging.is_some() {return Err(DetailError::Unsupported("Pre-imaging composite is not supported by detail ROI ownership".into()));}
        config
            .params
            .validate()
            .map_err(|e| DetailError::Processing(e.to_string()))?;
        let (w, h) = (config.width as usize, config.height as usize);
        if w == 0 || h == 0 || w.max(h) > 1536 {
            return Err(DetailError::InvalidGeometry("Explicit working grid must be nonempty and <=1536; no implicit original-size allocation".into()));
        }
        if config.initial_region.is_some_and(|r| !r.valid(w,h)) {
            return Err(DetailError::InvalidGeometry("Initial region outside working grid".into()));
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
        // Region JNI streams chunks directly into the caller's float buffer.
        // Java converts using bounded 16-row staging: float buffer + Bitmap = 16 B/pixel.
        // Legacy cells retain their original estimate and can be chosen on ROI admission refusal.
        let region_extra=if config.region_output {n*16+128*1024}else{0};
        let scratch = n * 24 + region_extra + fft_side * fft_side * 64 + 64 * MIB + if print_fft {256*1024} else {0};
        let cpu_peak = scratch
            .checked_add(config.stage_cache_bytes)
            .and_then(|n| n.checked_add(config.spectrum_cache_bytes))
            .ok_or_else(|| DetailError::InvalidGeometry("Budget overflow".into()))?;
        if cpu_peak > config.memory_budget_bytes {
            return Err(DetailError::MemoryPlanExceeded {
                required: cpu_peak, budget: config.memory_budget_bytes,
            });
        }
        let (peak, gpu_execution_budget_bytes, gpu_plan_reason) =
            planned_execution_reservation(config, cpu_peak)?;
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
            return Err(DetailError::StorageBudgetExceeded { required: disk, budget: config.disk_budget_bytes });
        }
        Ok(ResourcePlan {
            region_output: config.region_output,
            gpu_attempt: gpu_execution_budget_bytes > 0,
            gpu_execution_budget_bytes,
            gpu_plan_reason,
            cpu_peak_reserved_bytes: cpu_peak,
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
        crate::mobile::gpu_completion_guard().map_err(|_|DetailError::GpuCompletionUncertain)?;
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
        let profile_started=Instant::now();
        let film = load_profile_by_name(data_dir, &config.params.film_stock)
            .map_err(|e| DetailError::Processing(e.to_string()))?;
        let paper = load_profile_by_name(data_dir, &config.params.paper_stock)
            .map_err(|e| DetailError::Processing(e.to_string()))?;
        let _profile_prepare_ms=profile_started.elapsed().as_secs_f64()*1000.;
        if let Some(m)=&config.params.preview_metering {
            if !m.matches_detail_mapping(&config.mapping_revision) {
                return Err(DetailError::InvalidGeometry("Metering source revision differs from detail source".into()));
            }
        }
        let mut params = config.params.preview_runtime_for_film(&film);
        let pitch = params.camera.film_format_mm * 1000. / config.width.max(config.height) as f32;
        params.camera.pixel_size_um_override = Some(pitch);
        params.film_render.grain.coordinate_origin = Some([0, 0]);
        let pipeline_started=Instant::now();
        let pipe = Pipeline::new_with_spectral(film, paper, params, data_dir)
            .map_err(|e| DetailError::Processing(e.to_string()))?;
        cancel.check()?;
        let _pipeline_prepare_ms=pipeline_started.elapsed().as_secs_f64()*1000.;
        #[allow(unused_mut)]
        let mut identity = DetailIdentity {
            source_revision: config.source_revision.clone(),
            mapping_revision: config.mapping_revision.clone(),
            data_revision: config.data_revision.clone(),
            parameters: serde_json::to_string(&config.params)
                .map_err(|e| DetailError::Processing(e.to_string()))?,
            width: config.width, height: config.height,
            algorithm: "owned-detail-v1-f64-fixed256-gutter1",
        };
        #[allow(unused_mut)]
        let mut backend = DetailBackendInfo::for_requested_gpu(config.params.use_gpu);
        if config.params.use_gpu && !plan.gpu_attempt {backend.reason = plan.gpu_plan_reason;}
        #[cfg(feature = "adreno-gpu")]
        if plan.gpu_attempt {
            let attempt = std::panic::catch_unwind(std::panic::AssertUnwindSafe(||
                gpu::GpuDetail::build(&pipe, &owned, &config, &plan, cancel)));
            gpu::check_completion(cancel)?;
            match attempt {
                Ok(Ok(mut gpu)) => {
                    gpu.performance.profile_prepare_ms=Some(_profile_prepare_ms);
                    gpu.performance.pipeline_prepare_ms=Some(_pipeline_prepare_ms);
                    identity.algorithm = gpu::ALGORITHM;
                    backend = DetailBackendInfo {actual_backend: "wgpu", requested_gpu: true,
                        cpu_fallback: false, reason: "approximate_resident_detail",
                        accelerated_stage: "full-resident-detail-chain", gpu_batches: gpu.completed_submits()};
                    return Ok(Arc::new(Self {
                        identity, backend, plan: plan.clone(), pipe,
                        prepared: None, planes: None, gpu: Some(gpu), kernel: None,
                        support: 0, brightness: 2f64.powf(config.params.brightness_ev),
                        render_guard: Mutex::new(()), worker: Mutex::new(Worker {
                            blocks: BTreeMap::new(), bytes: 0, clock: 0,
                            fft: fft::ValidFft::new(0),
                            #[cfg(test)] stats: RegionWorkStats::default(),
                        }), _root: root,
                    }));
                }
                Ok(Err(gpu::BuildFailure::Fatal(e))) => return Err(e),
                Ok(Err(gpu::BuildFailure::Fallback(reason))) => backend.reason = reason,
                Err(_) => backend.reason = "gpu_setup_panicked",
            }
            // Failed attempt and all of its fields/cache have dropped here. The
            // private source still belongs to root and is needed by CPU setup.
        }
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
            backend,
            pipe,
            prepared: Some(prepared),
            planes: Some(planes),
            #[cfg(feature = "adreno-gpu")]
            gpu: None,
            kernel,
            support,
            brightness: 2f64.powf(config.params.brightness_ev),
            render_guard: Mutex::new(()),
            worker: Mutex::new(Worker {
                blocks: BTreeMap::new(),
                bytes: 0,
                clock: 0,
                fft,
                #[cfg(test)] stats:RegionWorkStats::default(),
            }),
            plan,
            _root: root,
        }))
    }
    pub fn backend_info(&self) -> DetailBackendInfo {
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu)=&self.gpu {return DetailBackendInfo {gpu_batches:gpu.completed_submits(),..self.backend};}
        self.backend
    }
    pub fn build_performance(&self) -> Option<DetailPerformance> {
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu)=&self.gpu {return Some(gpu.performance.clone());}
        None
    }
    pub fn pregrain_metrics(&self) -> &PregrainMetrics {
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu) = &self.gpu {return &gpu.metrics;}
        &self.planes.as_ref().expect("CPU planes").metrics
    }
    pub fn metered_ev(&self) -> Option<f32> {
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu) = &self.gpu {return gpu.metered_ev;}
        self.planes.as_ref().expect("CPU planes").metered_ev
    }
    pub fn pixel_pitch_um(&self) -> f32 {
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu) = &self.gpu {return gpu.pitch;}
        self.planes.as_ref().expect("CPU planes").pitch
    }
    pub fn retained_disk_bytes(&self) -> u64 {
        #[cfg(feature = "adreno-gpu")]
        if self.gpu.is_some() {return self.identity.width as u64 * self.identity.height as u64 * 12;}
        self.planes.as_ref().expect("CPU planes").disk_bytes()
    }
    pub fn identity(&self) -> &DetailIdentity {
        &self.identity
    }
    pub fn resource_plan(&self) -> &ResourcePlan {
        &self.plan
    }
    pub fn clear_cache(&self) -> Result<(), DetailError> {
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu) = &self.gpu {return gpu.clear_cache();}
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
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu) = &self.gpu {return gpu.cache_bytes();}
        let w = self
            .worker
            .lock()
            .map_err(|_| DetailError::Processing("Worker lock poisoned".into()))?;
        Ok(w.bytes)
    }
    /// One visible-region result. Internal samples remain <=258 per axis so FFT
    /// scratch does not grow with viewport size. Large views reuse full canonical
    /// cell samples (including offscreen parts); only the ROI is published.
    pub fn render_region(
        self: &Arc<Self>, core: Rect, cancel: &CancellationToken,
    ) -> Result<OwnedDetailTile, DetailError> {
        crate::mobile::gpu_completion_guard().map_err(|_|DetailError::GpuCompletionUncertain)?;
        cancel.check()?;
        if !self.plan.region_output {return Err(DetailError::Unsupported("Context uses legacy cell output budget".into()));}
        let (w,h)=(self.identity.width as usize,self.identity.height as usize);
        if !core.valid(w,h) { return Err(DetailError::InvalidGeometry("Region outside working grid".into())); }
        let sample=core.expand(1,w,h);
        let mut rgb=vec![0f32;sample.w*sample.h*3];
        self.render_region_into(core,&mut rgb,cancel)?;
        Ok(OwnedDetailTile{identity:self.identity.clone(),backend:self.backend_info(),core_rect:core,sample_rect:sample,rgb})
    }
    /// Caller owns a full, exclusive output slice through return. Cancellation may
    /// leave partial bytes, which must never be published as a completed region.
    pub fn render_region_into(self:&Arc<Self>,core:Rect,rgb:&mut[f32],cancel:&CancellationToken)->Result<(),DetailError> {
        self.render_region_into_measured(core,rgb,cancel).map(|_|())
    }
    pub(super) fn render_region_into_measured(self:&Arc<Self>,core:Rect,rgb:&mut[f32],cancel:&CancellationToken)
        -> Result<Option<DetailPerformance>,DetailError> {
        crate::mobile::gpu_completion_guard().map_err(|_|DetailError::GpuCompletionUncertain)?;
        cancel.check()?;
        let _render_guard=self.render_guard.try_lock().map_err(|_|DetailError::Processing("Context already has an active worker".into()))?;
        if !self.plan.region_output {return Err(DetailError::Unsupported("Context uses legacy cell output budget".into()));}
        let (w,h)=(self.identity.width as usize,self.identity.height as usize);
        if !core.valid(w,h) {return Err(DetailError::InvalidGeometry("Region outside working grid".into()));}
        let sample=core.expand(1,w,h);
        if rgb.len()!=sample.w*sample.h*3 {return Err(DetailError::InvalidGeometry("Region output shape".into()));}
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu) = &self.gpu {
            let _interactive=crate::export_execution::interactive_work();
            return gpu.render_into(&self.pipe, sample, rgb, cancel).map(Some);
        }
        let mut copy_sample=|part:Rect,owned:Rect|->Result<(),DetailError>{
            cancel.check()?;
            let output=self.render_sample(part,part,cancel)?;
            let left=sample.x.max(owned.x);let top=sample.y.max(owned.y);
            let right=(sample.x+sample.w).min(owned.x+owned.w);
            let bottom=(sample.y+sample.h).min(owned.y+owned.h);
            for y in top..bottom {
                cancel.check()?;
                let dst=((y-sample.y)*sample.w+left-sample.x)*3;
                let src=((y-part.y)*part.w+left-part.x)*3;
                let n=(right-left)*3;
                rgb[dst..dst+n].copy_from_slice(&output.rgb[src..src+n]);
            }
            Ok(())
        };
        if tight_region(core,sample,w,h) {copy_sample(sample,sample)?;} else {
            // Same canonical sample order/shapes as legacy cells. Keep one atomic
            // ROI externally; no public render_cell recursion under render_guard.
            for y in core.y/CELL..=(core.y+core.h-1)/CELL {
                for x in core.x/CELL..=(core.x+core.w-1)/CELL {
                    let cell=Rect{x:x*CELL,y:y*CELL,w:CELL.min(w-x*CELL),h:CELL.min(h-y*CELL)};
                    // Preserve canonical core ownership at seams: a neighbour's
                    // gutter must not overwrite this cell's visible core. Only
                    // outer ROI gutters are owned by the boundary cell sample.
                    let left=if x==core.x/CELL {sample.x}else{cell.x};
                    let top=if y==core.y/CELL {sample.y}else{cell.y};
                    let right=if x==(core.x+core.w-1)/CELL {sample.x+sample.w}else{cell.x+cell.w};
                    let bottom=if y==(core.y+core.h-1)/CELL {sample.y+sample.h}else{cell.y+cell.h};
                    copy_sample(cell.expand(1,w,h),Rect{x:left,y:top,w:right-left,h:bottom-top})?;
                }
            }
        }
        cancel.check()?;Ok(None)
    }
    pub fn render_cell(
        self: &Arc<Self>,
        cell_x: usize,
        cell_y: usize,
        cancel: &CancellationToken,
    ) -> Result<OwnedDetailTile, DetailError> {
        crate::mobile::gpu_completion_guard().map_err(|_|DetailError::GpuCompletionUncertain)?;
        let _interactive=crate::export_execution::interactive_work();
        cancel.check()?;
        let _render_guard=self.render_guard.try_lock().map_err(|_|DetailError::Processing("Context already has an active worker".into()))?;
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
        self.render_sample(core,sample,cancel)
    }
    fn render_sample(self: &Arc<Self>, core: Rect, sample: Rect, cancel: &CancellationToken) -> Result<OwnedDetailTile, DetailError> {
        let _interactive=crate::export_execution::interactive_work();
        let (w,h)=(self.identity.width as usize,self.identity.height as usize);
        #[cfg(feature = "adreno-gpu")]
        if let Some(gpu) = &self.gpu {
            let mut rgb=vec![0.; sample.w*sample.h*3];
            gpu.render_into(&self.pipe, sample, &mut rgb, cancel)?;
            return Ok(OwnedDetailTile {identity:self.identity.clone(), backend:self.backend_info(),
                core_rect:core, sample_rect:sample, rgb});
        }
        let needed = sample.expand(self.kernel.as_ref().map_or(0, |k| k.radius), w, h);
        let mut worker = self
            .worker
            .try_lock()
            .map_err(|_| DetailError::Processing("Context already has an active worker".into()))?;
        cancel.check()?;
        #[cfg(test)] { *worker.stats.samples.entry((sample.w,sample.h)).or_default()+=1; }
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
                #[cfg(test)] {if worker.blocks.contains_key(&(bx,by)){worker.stats.stage_hits+=1;}else{worker.stats.stage_misses+=1;}}
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
                self.prepared.as_ref().expect("CPU finish")
                    .process_density_chunk_unclipped(&chunk, &CpuBackend)
            } else {
                let paper = self
                    .prepared.as_ref().expect("CPU finish")
                    .develop_print_raw_chunk(&chunk, &CpuBackend)
                    .map_err(DetailError::Processing)?;
                self.prepared.as_ref().expect("CPU finish")
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
            backend: self.backend_info(),
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
                let pre = self.planes.as_ref().expect("CPU planes").read_rect(input, cancel)?;
                let mut params = self.pipe.params.clone();
                params.film_render.grain.coordinate_origin = Some([input.x as u32, input.y as u32]);
                let grain = filming::grain_density(pre, &self.pipe.film, &params, &CpuBackend);
                cancel.check()?;
                let valid = crop(&grain, input, part, cancel)?;
                let raw = if self.pipe.params.io.scan_film {
                    valid
                } else {
                    self.prepared.as_ref().expect("CPU finish")
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
        DetailConfig{gpu_attempt:true,region_output:true,initial_region:None,width:w,height:h,params:MobileParams::parse(r#"{"paperStock":"kodak_2383","useGpu":false,"autoExposure":true,"metering":"partial","diffusionFilter":"black_pro_mist","printDiffusionFilter":"black_pro_mist","brightnessEv":0.859999895}"#).unwrap(),source_revision:"fixture-v1".into(),mapping_revision:"oriented-v1".into(),data_revision:"bundled-v1".into(),memory_budget_bytes:512*MIB,disk_budget_bytes:1024*1024*1024,stage_cache_bytes:CELL*CELL*24,spectrum_cache_bytes:64*MIB}
    }
    #[test]
    fn cinestill_detail_kernel_preflight_matches_resolved_pipeline() {
        for (stock,amount) in [("cinestill_800t",1.),("cinestill_800t",0.),("kodak_portra_400",1.)] {
            let mut c=config(512,256);c.params.film_stock=stock.into();c.params.halation_amount=amount;
            let film=load_profile_by_name(&data(),stock).unwrap();
            let paper=load_profile_by_name(&data(),&c.params.paper_stock).unwrap();
            let mut params=c.params.preview_runtime_for_film(&film);
            params.camera.pixel_size_um_override=Some(params.camera.film_format_mm*1000./512.);
            let pipe=Pipeline::new_with_spectral(film,paper,params,&data()).unwrap();
            let expected=pipe.gpu_diffusion_working_bytes(c.width,c.height).unwrap()
                +std::mem::size_of::<spektrafilm_core::pipeline::DiffusionScatteredFields>() as u64;
            assert_eq!(planned_gpu_field_bytes(&c).unwrap() as u64,expected,"{stock} amount={amount}");
            assert_eq!(pipe.gpu_halation_working_bytes(c.width,c.height).unwrap()>0,stock=="cinestill_800t" && amount>0.);
        }
    }

    #[test]
    fn initial_region_is_geometry_checked_and_does_not_change_admission() {
        let mut c=config(383,271);
        let original=serde_json::to_value(DetailContext::preflight(&c).unwrap()).unwrap();
        c.initial_region=Some(Rect{x:260,y:260,w:123,h:11});
        assert_eq!(serde_json::to_value(DetailContext::preflight(&c).unwrap()).unwrap(),original);
        for r in [Rect{x:0,y:0,w:0,h:1},Rect{x:0,y:271,w:1,h:1},
            Rect{x:382,y:0,w:2,h:1},Rect{x:usize::MAX,y:0,w:2,h:1}] {
            c.initial_region=Some(r);
            assert!(matches!(DetailContext::preflight(&c),Err(DetailError::InvalidGeometry(_))));
        }
    }
    #[test]
    fn initial_region_cpu_fallback_keeps_identity_pixels_and_precancel() {
        let dir=root();let (source,_,original)=input(&dir,16,12);
        let mut c=config(16,12);c.params.use_gpu=true;c.gpu_attempt=false;
        let token=CancellationToken::new();
        let baseline=DetailContext::build(c.clone(),&data(),&source,&dir,&token).unwrap();
        let r=Rect{x:4,y:3,w:8,h:6};c.initial_region=Some(r);
        let hinted=DetailContext::build(c.clone(),&data(),&source,&dir,&token).unwrap();
        assert_eq!(hinted.identity(),baseline.identity());
        assert_eq!(hinted.backend_info(),baseline.backend_info());
        assert!(hinted.backend_info().cpu_fallback);
        assert_eq!(hinted.render_region(r,&token).unwrap().rgb,baseline.render_region(r,&token).unwrap().rgb);
        assert!(hinted.build_performance().is_none());
        let sample=r.expand(1,16,12);let mut pixels=vec![0.;sample.w*sample.h*3];
        assert!(hinted.render_region_into_measured(r,&mut pixels,&token).unwrap().is_none());
        drop(hinted);drop(baseline);
        let cancelled=CancellationToken::new();cancelled.cancel();
        assert!(matches!(DetailContext::build(c,&data(),&source,&dir,&cancelled),Err(DetailError::Cancelled)));
        assert_eq!(fs::read(&source).unwrap(),original);
        assert_eq!(fs::read_dir(&dir).unwrap().count(),1);fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn performance_serialization_has_only_fixed_optional_numeric_fields() {
        assert_eq!(serde_json::to_value(DetailPerformance::default()).unwrap(),serde_json::json!({}));
        let m=DetailPerformance{tile_cache_hits:Some(2),tile_cache_misses:Some(0),gpu_submit_delta:Some(0),
            source_read_ms:Some(0.),chain_ms:Some(0.),result_copy_ms:Some(0.125),..Default::default()};
        assert_eq!(serde_json::to_value(m).unwrap(),serde_json::json!({"tileCacheHits":2,"tileCacheMisses":0,
            "gpuSubmitDelta":0,"sourceReadMs":0.0,"chainMs":0.0,"resultCopyMs":0.125}));
    }
    #[test]
    fn operational_cpu_admission_preserves_requested_gpu_and_original_peak() {
        let mut c=config(383,271);c.params.auto_exposure=false;
        let cpu=DetailContext::preflight(&c).unwrap();
        c.params.use_gpu=true;c.gpu_attempt=false;
        c.memory_budget_bytes=cpu.peak_reserved_bytes;
        let requested=serde_json::to_string(&c.params).unwrap();
        let operational=DetailContext::preflight(&c).unwrap();
        assert!(!operational.gpu_attempt);assert_eq!(operational.gpu_execution_budget_bytes,0);
        assert_eq!(operational.gpu_plan_reason,"gpu_admission_cpu_attempt");
        assert_eq!(operational.peak_reserved_bytes,cpu.peak_reserved_bytes);
        assert_eq!(operational.cpu_peak_reserved_bytes,cpu.peak_reserved_bytes);
        assert!(c.params.use_gpu);assert_eq!(serde_json::to_string(&c.params).unwrap(),requested);
        // Automatic GPU planning at this same tight cap must also keep the
        // admissible CPU route, without requesting the unavailable GPU reserve.
        c.gpu_attempt=true;
        let bounded=DetailContext::preflight(&c).unwrap();
        assert!(!bounded.gpu_attempt);assert_eq!(bounded.peak_reserved_bytes,cpu.peak_reserved_bytes);
        c.memory_budget_bytes=cpu.peak_reserved_bytes-1;
        assert!(matches!(DetailContext::preflight(&c),Err(DetailError::MemoryPlanExceeded{..})));
    }
    #[test]
    fn operational_cpu_build_reports_fallback_without_changing_science_identity() {
        let dir=root();let (source,_,original)=input(&dir,16,12);
        let mut c=config(16,12);c.params.use_gpu=true;c.gpu_attempt=false;
        let expected=serde_json::to_string(&c.params).unwrap();
        let ctx=DetailContext::build(c,&data(),&source,&dir,&CancellationToken::new()).unwrap();
        assert_eq!(ctx.identity().parameters,expected);
        assert_eq!(ctx.backend_info().actual_backend,"cpu");assert!(ctx.backend_info().requested_gpu);
        assert!(ctx.backend_info().cpu_fallback);assert_eq!(ctx.backend_info().gpu_batches,0);
        assert_eq!(ctx.backend_info().reason,"gpu_admission_cpu_attempt");
        drop(ctx);assert_eq!(fs::read(&source).unwrap(),original);
        assert_eq!(fs::read_dir(&dir).unwrap().count(),1);fs::remove_dir_all(dir).unwrap();
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
    #[ignore = "host-only timing probe; run explicitly under the shared heavy-work slot"]
    fn visible_region_cost_probe() {
        use std::time::Instant;
        let root=root();let (w,h)=(1024,768);let (path,_,_)=input(&root,w,h);
        let mut c=config(w,h);c.stage_cache_bytes=16*MIB;c.spectrum_cache_bytes=32*MIB;
        let token=CancellationToken::new();let started=Instant::now();
        let ctx=DetailContext::build(c,&data(),&path,&root,&token).unwrap();
        eprintln!("REGION_COST context_ms={:.3} grid={}x{}",started.elapsed().as_secs_f64()*1000.,w,h);
        for (name,core) in [("boundary20",Rect{x:250,y:250,w:20,h:20}),("viewport512x384",Rect{x:256,y:256,w:512,h:384}),("wide768x576",Rect{x:128,y:96,w:768,h:576})] {
            for round in 0..2 {
                for region in if round==0 {[false,true]}else{[true,false]} {
                    ctx.clear_cache().unwrap();ctx.worker.lock().unwrap().stats=RegionWorkStats::default();
                    let before=ctx.print_cache_stats().unwrap();let start=Instant::now();let mut output_pixels=0usize;let mut calls=0;
                    if region {let out=ctx.render_region(core,&token).unwrap();output_pixels=out.rgb.len()/3;calls=1;}
                    else {for y in core.y/CELL..=(core.y+core.h-1)/CELL {for x in core.x/CELL..=(core.x+core.w-1)/CELL {
                        let out=ctx.render_cell(x,y,&token).unwrap();output_pixels+=out.rgb.len()/3;calls+=1;
                    }}}
                    eprintln!("REGION_COST name={name} round={round} mode={} ms={:.3} output_pixels={output_pixels} calls={calls} stage_bytes={}",if region{"region"}else{"cells"},start.elapsed().as_secs_f64()*1000.,ctx.cache_bytes().unwrap());
                    let after=ctx.print_cache_stats().unwrap();
                    eprintln!("REGION_STATS name={name} round={round} region={region} {:?} fft_builds={} fft_ram_hits={} fft_disk_hits={} fft_evictions={}",ctx.worker.lock().unwrap().stats,after.kernel_builds-before.kernel_builds,after.ram_hits-before.ram_hits,after.disk_hits-before.disk_hits,after.ram_evictions-before.ram_evictions);

                }
            }
        }
        drop(ctx);fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn region_partition_policy_uses_single_tight_call_only_for_large_output_savings(){
        let (w,h)=(1024,768);
        for (core,expected) in [
            (Rect{x:250,y:250,w:20,h:20},true),
            (Rect{x:10,y:10,w:20,h:20},true),
            (Rect{x:256,y:256,w:512,h:384},false),
            (Rect{x:128,y:96,w:768,h:576},false),
            (Rect{x:2,y:2,w:250,h:250},false),
            (Rect{x:250,y:250,w:256,h:256},true),
        ] {assert_eq!(tight_region(core,core.expand(1,w,h),w,h),expected,"{core:?}");}
    }
    #[test]
    fn visible_region_crosses_four_cells_matches_reference_and_cancels() {
        let root=root();let (w,h)=(273,269);
        let c=config(w,h);let (path,image,bytes)=input(&root,w,h);
        let token=CancellationToken::new();
        let ctx=DetailContext::build(c,&data(),&path,&root,&token).unwrap();
        let mut reference=ctx.pipe.process(image,&CpuBackend);
        for v in &mut reference.data {*v*=from_f64(ctx.brightness);}
        for core in [Rect{x:249,y:247,w:18,h:17},Rect{x:0,y:0,w:12,h:19},Rect{x:260,y:260,w:13,h:9},Rect{x:7,y:9,w:266,h:260},Rect{x:0,y:0,w:w as usize,h:h as usize}] {
            let output=ctx.render_region(core,&token).unwrap();
            let mut borrowed=vec![-123f32;output.rgb.len()];
            ctx.render_region_into(core,&mut borrowed,&token).unwrap();assert_eq!(borrowed,output.rgb);
            assert_eq!(output.core_rect,core);assert_eq!(output.sample_rect,core.expand(1,w as usize,h as usize));
            let expected=crop(&reference,Rect{x:0,y:0,w:w as usize,h:h as usize},output.sample_rect,&token).unwrap();
            let max=output.rgb.iter().zip(&expected.data).map(|(a,b)|(*a-*b as f32).abs()).fold(0f32,f32::max);
            assert!(max<1e-5,"region same-grid max error {max}");
            let mut changed_srgb8=0usize;let mut compared=0usize;let mut old_cell_max=0f32;
            let srgb8=|v:f32| {let l=(v as f64).clamp(0.,1.);let e=if l<=0.0031308{12.92*l}else{1.055*l.powf(1./2.4)-0.055};(e*255.).round() as i32};
            for by in output.sample_rect.y/CELL..=(output.sample_rect.y+output.sample_rect.h-1)/CELL {
                for bx in output.sample_rect.x/CELL..=(output.sample_rect.x+output.sample_rect.w-1)/CELL {
                    let old=ctx.render_cell(bx,by,&token).unwrap();
                    let sx=output.sample_rect.x.max(old.core_rect.x);let sy=output.sample_rect.y.max(old.core_rect.y);
                    let ex=(output.sample_rect.x+output.sample_rect.w).min(old.core_rect.x+old.core_rect.w);
                    let ey=(output.sample_rect.y+output.sample_rect.h).min(old.core_rect.y+old.core_rect.h);
                    for y in sy..ey {for x in sx..ex {for ch in 0..3 {
                        let a=output.rgb[((y-output.sample_rect.y)*output.sample_rect.w+x-output.sample_rect.x)*3+ch];
                        let b=old.rgb[((y-old.sample_rect.y)*old.sample_rect.w+x-old.sample_rect.x)*3+ch];
                        assert!((a-b).abs()<1e-5,"new region vs prior cell {a} {b}");
                        if !tight_region(core,output.sample_rect,w as usize,h as usize) && x>=core.x && x<core.x+core.w && y>=core.y && y<core.y+core.h {
                            assert_eq!(a.to_bits(),b.to_bits(),"Canonical cell core ownership at {x},{y},{ch}");
                        }
                        old_cell_max=old_cell_max.max((a-b).abs());compared+=1;if srgb8(a)!=srgb8(b){changed_srgb8+=1;}
                    }}}
                }
            }
            eprintln!("REGION_PARITY core={core:?} reference_max={max} old_cell_max={old_cell_max} srgb8_changed={changed_srgb8}/{compared}");
            let first=output.rgb;ctx.clear_cache().unwrap();
            assert_eq!(first,ctx.render_region(core,&token).unwrap().rgb);
        }
        assert!(matches!(ctx.render_region(Rect{x:usize::MAX,y:0,w:1,h:1},&token),Err(DetailError::InvalidGeometry(_))));
        let cancelled=CancellationToken::new();cancelled.cancel_after_checks(20);
        assert!(matches!(ctx.render_region(Rect{x:0,y:0,w:273,h:269},&cancelled),Err(DetailError::Cancelled)));
        assert!(ctx.render_region(Rect{x:250,y:250,w:15,h:15},&token).is_ok());
        assert_eq!(fs::read(path).unwrap(),bytes);drop(ctx);fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn legacy_output_keeps_original_budget_when_region_plan_does_not_fit() {
        let mut c=config(1024,768);c.region_output=false;
        let legacy=DetailContext::preflight(&c).unwrap();
        assert!(!legacy.region_output);
        c.region_output=true;let region=DetailContext::preflight(&c).unwrap();
        assert_eq!(region.peak_reserved_bytes-legacy.peak_reserved_bytes,1024*768*16+128*1024);
        c.memory_budget_bytes=legacy.peak_reserved_bytes;
        assert!(matches!(DetailContext::preflight(&c),Err(DetailError::MemoryPlanExceeded{..})));
        c.region_output=false;assert_eq!(DetailContext::preflight(&c).unwrap().peak_reserved_bytes,legacy.peak_reserved_bytes);
        let root=root();let (path,_,_)=input(&root,32,24);let mut small=config(32,24);small.region_output=false;
        let token=CancellationToken::new();let ctx=DetailContext::build(small,&data(),&path,&root,&token).unwrap();
        assert!(ctx.render_cell(0,0,&token).is_ok());let mut output=vec![123f32;32*24*3];
        assert!(matches!(ctx.render_region_into(Rect{x:0,y:0,w:32,h:24},&mut output,&token),Err(DetailError::Unsupported(_))));
        assert!(output.iter().all(|&v|v==123.));drop(ctx);fs::remove_dir_all(root).unwrap();
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
        assert!(matches!(DetailContext::preflight(&c),Err(DetailError::StorageBudgetExceeded{..})));
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
            Err(DetailError::MemoryPlanExceeded { .. })
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
        assert_eq!(fallback.backend_info().actual_backend, "cpu");
        assert!(fallback.backend_info().cpu_fallback);
        assert!(!fallback.backend_info().reason.is_empty());
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
    fn all_catalog_stock_profiles_are_rendered_or_explicitly_unsupported() {
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
        assert_eq!(rendered + unsupported, crate::profile_catalog::FILMS.len());
        assert_eq!((rendered, unsupported), (17, 5));
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
