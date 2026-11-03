//! Optional partial GPU acceleration: density interpolation only. All spectral,
//! spatial, grain and calibration algorithms remain the exact CPU implementations.
//! Android Vulkan + Qualcomm Adreno only; errors and numerical mismatch disable
//! GPU for the operation. Host production builds deliberately stay on CPU.
use serde::Serialize;
use spektrafilm_gpu::{ComputeBackend, Lut3D, cpu_backend::CpuBackend};
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use std::sync::{
    Mutex,
    atomic::{AtomicU64, Ordering},
};

pub const MAX_BATCH_PIXELS: usize = 32768;
pub const MAX_KNOTS: usize = 4096;
/// The app holds this process-lifetime passive admission reservation BEFORE
/// enabling reuse. Covers fixed buffer payload, key metadata and bookkeeping;
/// driver-private overhead remains in the existing GPU operation allowance.
pub const GPU_RESOURCE_REUSE_RESERVE_BYTES: usize = 2 * 1024 * 1024;
pub const GPU_RESOURCE_REUSE_BUFFER_BYTES: usize =
    MAX_BATCH_PIXELS * 3 * 4 * 3 + MAX_KNOTS * 4 * 4 + 16;
pub const ABS_TOLERANCE: f64 = 2e-5;
pub const REL_TOLERANCE: f64 = 2e-5;
pub const SHADER: &str = include_str!("shaders/adreno_density.wgsl");
/// Safe scalar evidence for this density-only startup experiment. None means
/// not measured, not failure. This is never a whole-render GPU benchmark.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DensityCalibration {
    pub fixture_pixels: u32,
    pub curve_knots: u32,
    pub samples: u32,
    pub required_time_ratio: f64,
    pub cpu_median_ms: Option<f64>,
    pub original_gpu_median_ms: Option<f64>,
    pub original_gate_passed: Option<bool>,
    pub candidate_gpu_median_ms: Option<f64>,
    pub candidate_samples: u32,
    pub candidate_validation_ms: Option<f64>,
    pub candidate_gate_passed: Option<bool>,
    pub candidate_exact_vs_original_gpu: Option<bool>,
    pub candidate_cpu_max_abs: Option<f64>,
    pub candidate_cpu_rms: Option<f64>,
    pub candidate_cpu_bit_equal: Option<bool>,
    pub numeric_self_check_passed: Option<bool>,
    /// Adapter advertisement only. We do NOT request/use f64 shaders.
    pub shader_f64_advertised: Option<bool>,
    pub candidate_status: &'static str,
}
impl Default for DensityCalibration {
    fn default() -> Self {
        Self {
            fixture_pixels: MAX_BATCH_PIXELS as u32,
            curve_knots: 256,
            samples: 0,
            required_time_ratio: 0.85,
            cpu_median_ms: None,
            original_gpu_median_ms: None,
            original_gate_passed: None,
            candidate_gpu_median_ms: None,
            candidate_samples: 0,
            candidate_validation_ms: None,
            candidate_gate_passed: None,
            candidate_exact_vs_original_gpu: None,
            candidate_cpu_max_abs: None,
            candidate_cpu_rms: None,
            candidate_cpu_bit_equal: None,
            numeric_self_check_passed: None,
            shader_f64_advertised: None,
            candidate_status: "not_requested_at_initialization",
        }
    }
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GpuDiagnostics {
    pub requested: bool,
    pub active: bool,
    pub available: bool,
    pub adapter: String,
    pub backend: &'static str,
    pub accelerated_stage: &'static str,
    pub gpu_batches: u64,
    pub cpu_fallbacks: u64,
    pub reason: String,
    pub startup_self_test_passed: bool,
    pub density_calibration: Option<DensityCalibration>,
    /// Experimental allocation reuse, independent of the shipped GPU math policy.
    pub resource_reuse_requested: bool,
    pub resource_reuse_batches: u64,
    pub resource_reuse_exact_checks: u64,
    pub resource_reuse_reason: String,
}
pub struct AdrenoDensityBackend {
    #[cfg(feature = "adreno-gpu")]
    gpu: std::sync::Arc<Mutex<Option<gpu::Handle>>>,
    status: Mutex<GpuDiagnostics>,
    batches: AtomicU64,
    fallbacks: AtomicU64,
}
impl Default for AdrenoDensityBackend {
    fn default() -> Self {
        Self::new()
    }
}
impl AdrenoDensityBackend {
    pub fn new() -> Self {
        Self::with_enabled(true)
    }
    pub fn with_enabled(enabled: bool) -> Self {
        Self::with_options(enabled, false)
    }
    /// `resource_reuse` requires the caller to hold the process-lifetime
    /// GPU_RESOURCE_REUSE_RESERVE_BYTES admission lease. It is off by default.
    pub fn with_options(enabled: bool, resource_reuse: bool) -> Self {
        let status = GpuDiagnostics {
            requested: enabled,
            active: false,
            available: false,
            adapter: String::new(),
            backend: "cpu",
            accelerated_stage: "density_curve_interp",
            gpu_batches: 0,
            cpu_fallbacks: 0,
            reason: if enabled {
                "GPU unavailable on this build/device"
            } else {
                "Disabled by user"
            }
            .into(),
            startup_self_test_passed: false,
            density_calibration: None,
            resource_reuse_requested: resource_reuse,
            resource_reuse_batches: 0,
            resource_reuse_exact_checks: 0,
            resource_reuse_reason: if resource_reuse {
                "Requested; no reused GPU batches committed"
            } else {
                "Disabled by configuration"
            }
            .into(),
        };
        #[allow(unused_mut)]
        let mut backend = Self {
            #[cfg(feature = "adreno-gpu")]
            gpu: std::sync::Arc::new(Mutex::new(None)),
            status: Mutex::new(status),
            batches: AtomicU64::new(0),
            fallbacks: AtomicU64::new(0),
        };
        #[cfg(all(feature = "adreno-gpu", target_os = "android"))]
        if enabled {
            match gpu::cached(resource_reuse) {
                Ok(shared) => {
                    // Metadata must not wait behind another preview/export's work.
                    let adapter = match shared.try_lock() {
                        Ok(g) => g.as_ref().map(|g| g.adapter.clone()),
                        Err(_) => {
                            backend.status.lock().unwrap().reason =
                                "GPU busy; CPU fallback for this operation".into();
                            return backend;
                        }
                    };
                    backend.gpu = shared;
                    let mut s = backend.status.lock().unwrap();
                    if let Some(adapter) = adapter {
                        s.adapter = adapter;
                        s.active = true;
                        s.backend = "vulkan-adreno";
                        s.reason="Runtime device, numeric self-check and measured cost gate passed; partial density acceleration".into();
                        s.startup_self_test_passed = true;
                    } else {
                        s.reason =
                            "GPU disabled after prior device or numerical failure; CPU fallback"
                                .into();
                    }
                }
                Err(e) => backend.status.lock().unwrap().reason = e,
            }
        }
        backend
    }
    pub fn diagnostics(&self) -> GpuDiagnostics {
        let mut s = self.status.lock().unwrap().clone();
        #[cfg(all(feature = "adreno-gpu", target_os = "android"))]
        if s.requested {
            s.density_calibration = gpu::calibration_snapshot();
        }
        #[cfg(feature = "adreno-gpu")]
        if s.active {
            match self.gpu.try_lock() {
                Ok(g) if g.is_some() => {}
                Ok(_) => {
                    s.active = false;
                    s.reason =
                        "GPU disabled after device or numerical failure; CPU fallback".into();
                }
                Err(_) => {
                    s.active = false;
                    s.reason = "GPU busy; availability snapshot unavailable".into();
                }
            }
        }
        s.gpu_batches = self.batches.load(Ordering::Relaxed);
        s.cpu_fallbacks = self.fallbacks.load(Ordering::Relaxed);
        s.available = s.active;
        s.active = s.gpu_batches > 0;
        s.backend = if s.active {
            "vulkan-adreno-partial"
        } else {
            "cpu"
        };
        if s.available && !s.active {
            s.reason = "Eligible GPU, but no accelerated batches used in this operation".into();
        }
        s
    }
    fn fallback(&self) {
        self.fallbacks.fetch_add(1, Ordering::Relaxed);
    }
    #[cfg(feature = "adreno-gpu")]
    fn disable(&self, reason: String) {
        *self.gpu.lock().unwrap() = None;
        let mut s = self.status.lock().unwrap();
        s.active = false;
        s.backend = "cpu";
        s.reason = reason;
    }
}
pub fn eligible_adapter(vendor: u32, name: &str, backend_vulkan: bool, integrated: bool) -> bool {
    backend_vulkan && integrated && vendor == 0x5143 && name.to_ascii_lowercase().contains("adreno")
}
fn valid_curves(axis: &[f64], curves: &[[f64; 3]], gamma: f64) -> bool {
    axis.len() >= 2
        && axis.len() <= MAX_KNOTS
        && curves.len() == axis.len()
        && gamma.is_finite()
        && gamma > 0.
        && axis.iter().all(|v| {
            v.is_finite()
                && (*v / gamma) as f32 != f32::INFINITY
                && (*v / gamma) as f32 != f32::NEG_INFINITY
        })
        && axis
            .windows(2)
            .all(|w| ((w[0] / gamma) as f32) < ((w[1] / gamma) as f32))
        && curves
            .iter()
            .flatten()
            .all(|v| v.is_finite() && (*v as f32).is_finite())
}
fn close(a: f64, b: f64) -> bool {
    a.is_finite() && b.is_finite() && (a - b).abs() <= ABS_TOLERANCE + REL_TOLERANCE * b.abs()
}
fn reference_scalar(x: f64, c: usize, axis: &[f64], curves: &[[f64; 3]], gamma: f64) -> f64 {
    let x = x * gamma;
    let last = axis.len() - 1;
    if x <= axis[0] {
        return curves[0][c];
    }
    if x >= axis[last] {
        return curves[last][c];
    }
    let hi = axis.partition_point(|v| *v <= x);
    let lo = hi - 1;
    let t = (x - axis[lo]) / (axis[hi] - axis[lo]);
    curves[lo][c] + t * (curves[hi][c] - curves[lo][c])
}
fn validate_sampled(
    input: &[spektrafilm_math::precision::Scalar],
    output: &[f32],
    axis: &[f64],
    curves: &[[f64; 3]],
    gamma: f64,
) -> bool {
    if input.is_empty()
        || input.len() % 3 != 0
        || output.len() != input.len()
        || output.iter().any(|v| !v.is_finite())
    {
        return false;
    }
    let pixels = input.len() / 3;
    for pixel in (0..pixels)
        .step_by((pixels / 64).max(1))
        .chain(std::iter::once(pixels - 1))
    {
        for channel in 0..3 {
            let at = pixel * 3 + channel;
            if !close(
                output[at] as f64,
                reference_scalar(input[at] as f64, channel, axis, curves, gamma),
            ) {
                return false;
            }
        }
    }
    true
}
// The driver may block internally. The caller has a finite wait; the single
// initializer can remain stuck, and is never claimed to have been killed.
fn initialize_bounded<T: Send + 'static>(
    timeout: std::time::Duration,
    init: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    initialize_serving(timeout, move || {
        init().map(|t| (t, Box::new(|| {}) as Box<dyn FnOnce() + Send>))
    })
}
fn initialize_serving<T: Send + 'static>(
    timeout: std::time::Duration,
    init: impl FnOnce() -> Result<(T, Box<dyn FnOnce() + Send>), String> + Send + 'static,
) -> Result<T, String> {
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::Builder::new()
        .name("film-adreno-worker".into())
        .spawn(move || match init() {
            Ok((handle, serve)) => {
                if tx.send(Ok(handle)).is_ok() {
                    serve();
                }
            }
            Err(e) => {
                let _ = tx.send(Err(e));
            }
        })
        .map_err(|_| "GPU worker could not start; CPU fallback".to_string())?;
    rx.recv_timeout(timeout).map_err(|e| match e {
        std::sync::mpsc::RecvTimeoutError::Timeout => {
            "GPU initialization timed out; CPU fallback until process restart".to_string()
        }
        std::sync::mpsc::RecvTimeoutError::Disconnected => {
            "GPU initializer failed; CPU fallback until process restart".to_string()
        }
    })?
}

impl ComputeBackend for AdrenoDensityBackend {
    fn colorspace_convert(&self, i: &ImageBuf, m: &[[f32; 3]; 3]) -> ImageBuf {
        CpuBackend.colorspace_convert(i, m)
    }
    fn cctf_encode_srgb(&self, i: &ImageBuf) -> ImageBuf {
        CpuBackend.cctf_encode_srgb(i)
    }
    fn cctf_decode_srgb(&self, i: &ImageBuf) -> ImageBuf {
        CpuBackend.cctf_decode_srgb(i)
    }
    fn gaussian_blur(&self, i: &ImageBuf, s: f32) -> ImageBuf {
        CpuBackend.gaussian_blur(i, s)
    }
    fn table_lookup(&self, i: &ImageBuf, x: &[f32], y: &[[f32; 3]]) -> ImageBuf {
        CpuBackend.table_lookup(i, x, y)
    }
    fn lut3d_interp(&self, i: &ImageBuf, l: &Lut3D) -> ImageBuf {
        CpuBackend.lut3d_interp(i, l)
    }
    fn density_curve_interp(
        &self,
        i: &ImageBuf,
        x: &[f64],
        y: &[[f64; 3]],
        gamma: f64,
    ) -> ImageBuf {
        #[cfg(feature = "adreno-gpu")]
        if valid_curves(x, y, gamma)
            && i.data.len() >= 3 * 1024
            && i.data
                .iter()
                .all(|v| (*v as f64).is_finite() && (*v as f32).is_finite())
        {
            let result = {
                let mut lock = match self.gpu.try_lock() {
                    Ok(v) => v,
                    Err(_) => {
                        self.fallback();
                        return CpuBackend.density_curve_interp(i, x, y, gamma);
                    }
                };
                lock.as_mut().map(|g| {
                    let mut out = Vec::with_capacity(i.data.len());
                    let reuse = self.status.lock().unwrap().resource_reuse_requested;
                    let (mut reused, mut checked) = (0u64, 0u64);
                    let mut reuse_reason = None;
                    for chunk in i.data.chunks(MAX_BATCH_PIXELS * 3) {
                        let pixels: Vec<f32> = chunk.iter().map(|v| *v as f32).collect();
                        let batch = g.run(&pixels, x, y, gamma, reuse)?;
                        reused += u64::from(batch.reused);
                        checked += u64::from(batch.exact_checked);
                        if batch.reuse_reason.is_some() {
                            reuse_reason = batch.reuse_reason;
                        }
                        let values = batch.values;
                        // Sample pixel locations, and all three channels at each location.
                        if !validate_sampled(chunk, &values, x, y, gamma) {
                            return Err("GPU numerical check failed; CPU fallback".into());
                        }
                        out.extend(values.into_iter().map(|v| from_f64(v as f64)));
                    }
                    Ok((out, reused, checked, reuse_reason))
                })
            };
            if let Some(r) = result {
                match r {
                    Ok((v, reused, checked, reuse_reason)) => {
                        let mut status = self.status.lock().unwrap();
                        status.resource_reuse_batches += reused;
                        status.resource_reuse_exact_checks += checked;
                        if let Some(reason) = reuse_reason {
                            status.resource_reuse_reason = reason;
                        } else if reused > 0 {
                            status.resource_reuse_reason =
                                "Allocation reuse used; exact baseline GPU comparison passed"
                                    .into();
                        }
                        drop(status);
                        self.batches.fetch_add(
                            i.data.len().div_ceil(MAX_BATCH_PIXELS * 3) as u64,
                            Ordering::Relaxed,
                        );
                        return ImageBuf::from_data(i.width, i.height, v);
                    }
                    Err(e) => self.disable(e),
                }
            }
        }
        self.fallback();
        CpuBackend.density_curve_interp(i, x, y, gamma)
    }
    // Remain false: generic core uses is_gpu to select approximate spatial paths.
    fn is_gpu(&self) -> bool {
        false
    }
    fn name(&self) -> &str {
        "cpu+optional-adreno-density"
    }
}

// These pure contracts compile without wgpu so the same production guards
// can be validated in a bounded CPU-only host build.
#[cfg(any(test, feature = "adreno-gpu"))]
mod reuse_contract {
    #[derive(Debug, PartialEq, Eq)]
    pub(super) struct ReuseKey {
        pub(super) scalar_count: usize,
        pub(super) axis_bits: Vec<u32>,
        pub(super) curve_bits: Vec<u32>,
    }
    impl ReuseKey {
        pub(super) fn new(scalar_count: usize, x: &[f64], y: &[[f64; 3]], gamma: f64) -> Self {
            Self {
                scalar_count,
                axis_bits: x.iter().map(|v| ((*v / gamma) as f32).to_bits()).collect(),
                curve_bits: y.iter().flatten().map(|v| (*v as f32).to_bits()).collect(),
            }
        }
    }
    pub(super) fn cost_gate(cpu_ms: f64, gpu_ms: f64) -> bool {
        cpu_ms.is_finite()
            && gpu_ms.is_finite()
            && cpu_ms > 0.0
            && gpu_ms >= 0.0
            && gpu_ms < cpu_ms * 0.85
    }
    pub(super) fn cpu_error(reference: &[f64], candidate: &[f32]) -> Option<(f64, f64, bool)> {
        if reference.is_empty() || reference.len() != candidate.len() {
            return None;
        }
        let (mut max_abs, mut squared, mut exact) = (0.0f64, 0.0f64, true);
        for (&a, &b) in reference.iter().zip(candidate) {
            let b = b as f64;
            if !a.is_finite() || !b.is_finite() {
                return None;
            }
            let difference = (a - b).abs();
            max_abs = max_abs.max(difference);
            squared += difference * difference;
            exact &= a.to_bits() == b.to_bits();
        }
        let rms = (squared / reference.len() as f64).sqrt();
        if !max_abs.is_finite() || !rms.is_finite() {
            return None;
        }
        Some((max_abs, rms, exact))
    }
    pub(super) fn exact_output(a: &[f32], b: &[f32]) -> bool {
        a.len() == b.len()
            && a.iter()
                .zip(b)
                .all(|(a, b)| a.is_finite() && b.is_finite() && a.to_bits() == b.to_bits())
    }
    #[cfg(test)]
    mod tests {
        use super::*;
        use crate::adreno::{
            GPU_RESOURCE_REUSE_BUFFER_BYTES, GPU_RESOURCE_REUSE_RESERVE_BYTES, MAX_BATCH_PIXELS,
            MAX_KNOTS,
        };
        #[test]
        fn cost_gate_requires_more_than_fifteen_percent_saving() {
            assert!(!cost_gate(10.0, 9.0));
            assert!(!cost_gate(10.0, 8.5));
            assert!(cost_gate(10.0, 8.49));
            assert!(!cost_gate(0.0, 0.0));
            assert!(!cost_gate(f64::NAN, 1.0));
            assert!(!cost_gate(10.0, f64::INFINITY));
        }
        #[test]
        fn cpu_error_metrics_do_not_confuse_tolerance_with_bit_equality() {
            assert_eq!(cpu_error(&[1.0, 2.0], &[1.0, 2.0]), Some((0.0, 0.0, true)));
            let (max, rms, exact) = cpu_error(&[1.0, 2.0], &[1.0, 2.25]).unwrap();
            assert_eq!(max, 0.25);
            assert!((rms - (0.0625f64 / 2.0).sqrt()).abs() < 1e-15);
            assert!(!exact);
            assert_eq!(cpu_error(&[0.0], &[-0.0]), Some((0.0, 0.0, false)));
            assert!(cpu_error(&[], &[]).is_none());
            assert!(cpu_error(&[f64::NAN], &[0.0]).is_none());
            assert!(cpu_error(&[1.0], &[]).is_none());
        }
        #[test]
        fn exact_guard_rejects_any_bit_difference_or_nonfinite() {
            assert!(exact_output(&[0.0, 1.0], &[0.0, 1.0]));
            assert!(!exact_output(&[0.0], &[-0.0]));
            assert!(!exact_output(
                &[1.0],
                &[f32::from_bits(1.0f32.to_bits() + 1)]
            ));
            assert!(!exact_output(&[f32::NAN], &[f32::NAN]));
            assert!(!exact_output(&[f32::INFINITY], &[f32::INFINITY]));
            assert!(!exact_output(&[0.0], &[]));
            let a = vec![1.0; MAX_BATCH_PIXELS * 3];
            let mut b = a.clone();
            b[MAX_BATCH_PIXELS * 3 - 2] = 1.001;
            assert!(!exact_output(&a, &b));
        }
        #[test]
        fn key_tracks_exact_shader_inputs_and_size_without_hash_collisions() {
            let x = [-2.0, 0.0, 3.0];
            let y = [[0.0; 3], [1.0; 3], [2.0; 3]];
            let key = ReuseKey::new(3000, &x, &y, 1.0);
            assert_eq!(key, ReuseKey::new(3000, &x, &y, 1.0));
            assert_ne!(key, ReuseKey::new(3003, &x, &y, 1.0));
            assert_ne!(key, ReuseKey::new(3000, &x, &y, 1.1));
            assert_ne!(key, ReuseKey::new(3000, &[-2.0, 0.1, 3.0], &y, 1.0));
            let mut changed = y;
            changed[1][1] = 1.25;
            assert_ne!(key, ReuseKey::new(3000, &x, &changed, 1.0));
        }
        #[test]
        fn retained_payload_and_max_key_fit_reserved_budget() {
            assert_eq!(GPU_RESOURCE_REUSE_BUFFER_BYTES, 1_245_200);
            let key_bytes = MAX_KNOTS * 4 * 4;
            assert!(
                GPU_RESOURCE_REUSE_BUFFER_BYTES + key_bytes + 64 * 1024
                    < GPU_RESOURCE_REUSE_RESERVE_BYTES
            );
        }
    }
}

#[cfg(feature = "adreno-gpu")]
mod gpu {
    use super::reuse_contract::{ReuseKey, cost_gate, cpu_error, exact_output};
    use super::*;
    use std::{
        sync::{Arc, atomic::AtomicBool, mpsc},
        time::{Duration, Instant},
    };
    use wgpu::util::DeviceExt;
    fn calibration_store() -> &'static Mutex<DensityCalibration> {
        static CALIBRATION: std::sync::OnceLock<Mutex<DensityCalibration>> =
            std::sync::OnceLock::new();
        CALIBRATION.get_or_init(|| Mutex::new(DensityCalibration::default()))
    }
    pub fn calibration_snapshot() -> Option<DensityCalibration> {
        calibration_store().try_lock().ok().map(|v| v.clone())
    }
    fn record_calibration(value: &DensityCalibration) {
        // Worker-only short critical section; readers use try_lock.
        if let Ok(mut slot) = calibration_store().lock() {
            *slot = value.clone();
        }
    }
    pub struct Gpu {
        device: wgpu::Device,
        queue: wgpu::Queue,
        pipeline: wgpu::ComputePipeline,
        error: Arc<AtomicBool>,
        pub adapter: String,
        reuse: Option<ReusableBuffers>,
        reuse_key: Option<ReuseKey>,
        reuse_disabled: Option<String>,
    }
    struct ReusableBuffers {
        input: wgpu::Buffer,
        axis: wgpu::Buffer,
        curves: wgpu::Buffer,
        output: wgpu::Buffer,
        readback: wgpu::Buffer,
        uniform: wgpu::Buffer,
        bind: wgpu::BindGroup,
    }
    pub struct BatchOutput {
        pub values: Vec<f32>,
        pub reused: bool,
        pub exact_checked: bool,
        pub reuse_reason: Option<String>,
    }
    struct Request {
        input: Vec<f32>,
        axis: Vec<f64>,
        curves: Vec<[f64; 3]>,
        gamma: f64,
        reuse: bool,
        response: mpsc::Sender<Result<BatchOutput, String>>,
    }
    pub struct Handle {
        tx: mpsc::SyncSender<Request>,
        pub adapter: String,
    }
    impl Handle {
        pub fn run(
            &mut self,
            input: &[f32],
            axis: &[f64],
            curves: &[[f64; 3]],
            gamma: f64,
            reuse: bool,
        ) -> Result<BatchOutput, String> {
            if input.len() > MAX_BATCH_PIXELS * 3 || axis.len() > MAX_KNOTS {
                return Err("GPU command exceeds batch limits".into());
            }
            let (tx, rx) = mpsc::channel();
            self.tx
                .try_send(Request {
                    input: input.to_vec(),
                    axis: axis.to_vec(),
                    curves: curves.to_vec(),
                    gamma,
                    reuse,
                    response: tx,
                })
                .map_err(|_| "GPU worker unavailable; CPU fallback".to_string())?;
            rx.recv_timeout(Duration::from_secs(3)).map_err(|_| {
                "GPU submission timed out or failed; CPU fallback until process restart".to_string()
            })?
        }
    }
    #[cfg(target_os = "android")]
    pub fn cached(calibrate_reuse: bool) -> Result<Arc<Mutex<Option<Handle>>>, String> {
        static GPU: std::sync::OnceLock<Result<Arc<Mutex<Option<Handle>>>, String>> =
            std::sync::OnceLock::new();
        GPU.get_or_init(|| {
            initialize_serving(Duration::from_secs(2), move || {
                let mut g = Gpu::new_with_calibration(false, calibrate_reuse)?;
                let (tx, rx) = mpsc::sync_channel::<Request>(1);
                let handle = Arc::new(Mutex::new(Some(Handle {
                    tx,
                    adapter: g.adapter.clone(),
                })));
                let serve = Box::new(move || {
                    while let Ok(q) = rx.recv() {
                        let result = g.run_request(&q.input, &q.axis, &q.curves, q.gamma, q.reuse);
                        let _ = q.response.send(result);
                    }
                });
                Ok((handle, serve as Box<dyn FnOnce() + Send>))
            })
        })
        .clone()
    }
    impl Gpu {
        pub fn new(allow_test_adapter: bool) -> Result<Self, String> {
            Self::new_with_calibration(allow_test_adapter, false)
        }
        fn new_with_calibration(
            allow_test_adapter: bool,
            calibrate_reuse: bool,
        ) -> Result<Self, String> {
            let mut evidence = DensityCalibration::default();
            if calibrate_reuse {
                evidence.candidate_status = "requested_not_measured";
            }
            record_calibration(&evidence);
            let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
                backends: wgpu::Backends::VULKAN,
                ..Default::default()
            });
            let adapter =
                pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
                    power_preference: wgpu::PowerPreference::HighPerformance,
                    compatible_surface: None,
                    force_fallback_adapter: false,
                }))
                .ok_or("No Vulkan adapter")?;
            let info = adapter.get_info();
            evidence.shader_f64_advertised =
                Some(adapter.features().contains(wgpu::Features::SHADER_F64));
            record_calibration(&evidence);
            if !allow_test_adapter
                && !eligible_adapter(
                    info.vendor,
                    &info.name,
                    info.backend == wgpu::Backend::Vulkan,
                    info.device_type == wgpu::DeviceType::IntegratedGpu,
                )
            {
                return Err("Not a supported Qualcomm Adreno Vulkan adapter".into());
            }
            let limits = adapter.limits();
            if limits.max_compute_workgroup_size_x < 64
                || limits.max_compute_invocations_per_workgroup < 64
                || limits.max_storage_buffers_per_shader_stage < 4
                || limits.max_storage_buffer_binding_size < MAX_BATCH_PIXELS as u32 * 12
            {
                return Err("Adreno compute limits insufficient".into());
            }
            let (device, queue) = pollster::block_on(adapter.request_device(
                &wgpu::DeviceDescriptor {
                    label: Some("filmroom-adreno-density"),
                    required_features: wgpu::Features::empty(),
                    required_limits: wgpu::Limits::downlevel_defaults().using_resolution(limits),
                    memory_hints: wgpu::MemoryHints::MemoryUsage,
                },
                None,
            ))
            .map_err(|e| e.to_string())?;
            let error = Arc::new(AtomicBool::new(false));
            let flag = error.clone();
            device.on_uncaptured_error(Box::new(move |_| {
                flag.store(true, Ordering::Relaxed);
            }));
            device.push_error_scope(wgpu::ErrorFilter::Validation);
            let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("bounded density"),
                source: wgpu::ShaderSource::Wgsl(SHADER.into()),
            });
            let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("bounded density"),
                layout: None,
                module: &shader,
                entry_point: Some("main"),
                compilation_options: Default::default(),
                cache: None,
            });
            if pollster::block_on(device.pop_error_scope()).is_some() {
                return Err("Density compute validation failed".into());
            }
            let mut g = Self {
                device,
                queue,
                pipeline,
                error,
                adapter: info.name,
                reuse: None,
                reuse_key: None,
                reuse_disabled: None,
            };
            let axis = [-8., -2., 0., 1., 4.];
            let curves = [
                [0., 0.1, 0.2],
                [0.2, 0.4, 0.6],
                [0.7, 1., 1.4],
                [1.2, 1.6, 2.],
                [2., 2.4, 2.8],
            ];
            let input: Vec<f32> = (0..1536).map(|v| -10. + v as f32 / 96.).collect();
            let output = g.run(&input, &axis, &curves, 1.17)?;
            if output.iter().enumerate().any(|(k, v)| {
                !close(
                    *v as f64,
                    reference_scalar(input[k] as f64, k % 3, &axis, &curves, 1.17),
                )
            }) {
                evidence.numeric_self_check_passed = Some(false);
                record_calibration(&evidence);
                return Err("Adreno density startup self-check failed".into());
            }
            evidence.numeric_self_check_passed = Some(true);
            record_calibration(&evidence);
            if !allow_test_adapter {
                // Warm shader/cache separately; benchmark transfer + dispatch + readback
                // and conversion, not just GPU execution. Median of three full chunks.
                // This constructor is called only by native processing workers.
                let axis: Vec<f64> = (0..256).map(|k| -8. + k as f64 / 20.).collect();
                let curves: Vec<[f64; 3]> = (0..256)
                    .map(|k| {
                        let v = k as f64 / 80.;
                        [v, v * 0.8, v * 1.1]
                    })
                    .collect();
                let input: Vec<f32> = (0..MAX_BATCH_PIXELS * 3)
                    .map(|k| -10. + (k % 1231) as f32 / 70.)
                    .collect();
                let image = ImageBuf::from_data(
                    MAX_BATCH_PIXELS as u32,
                    1,
                    input.iter().map(|v| from_f64(*v as f64)).collect(),
                );
                std::hint::black_box(CpuBackend.density_curve_interp(&image, &axis, &curves, 1.));
                std::hint::black_box(g.run(&input, &axis, &curves, 1.)?);
                let benchmark_start = Instant::now();
                let mut cpu_times = Vec::new();
                let mut gpu_times = Vec::new();
                for _ in 0..3 {
                    let at = Instant::now();
                    std::hint::black_box(
                        CpuBackend.density_curve_interp(&image, &axis, &curves, 1.),
                    );
                    cpu_times.push(at.elapsed().as_secs_f64());
                    let at = Instant::now();
                    let upload: Vec<f32> = image.data.iter().map(|v| *v as f32).collect();
                    let values = g.run(&upload, &axis, &curves, 1.)?;
                    if !validate_sampled(&image.data, &values, &axis, &curves, 1.) {
                        return Err("GPU calibration numerical check failed".into());
                    }
                    let returned: Vec<_> = values.into_iter().map(|v| from_f64(v as f64)).collect();
                    std::hint::black_box(returned);
                    gpu_times.push(at.elapsed().as_secs_f64());
                    evidence.samples = gpu_times.len() as u32;
                    record_calibration(&evidence);
                    if benchmark_start.elapsed() > Duration::from_secs(1) {
                        return Err("No measured acceleration benefit; CPU fallback (calibration time budget exceeded)".into());
                    }
                }
                cpu_times.sort_by(f64::total_cmp);
                gpu_times.sort_by(f64::total_cmp);
                evidence.cpu_median_ms = Some(cpu_times[1] * 1000.0);
                evidence.original_gpu_median_ms = Some(gpu_times[1] * 1000.0);
                let original_passed = cost_gate(cpu_times[1], gpu_times[1]);
                evidence.original_gate_passed = Some(original_passed);
                record_calibration(&evidence);
                if calibrate_reuse {
                    // Diagnostic only: NEVER let this result override the original
                    // CPU/GPU selection. Candidate arithmetic is the same shader,
                    // but GPU tolerance does not imply bit-identical f64 CPU pixels.
                    let diagnostic = g.calibrate_reuse(
                        &image,
                        &input,
                        &axis,
                        &curves,
                        &mut evidence,
                        benchmark_start,
                    );
                    if diagnostic.is_err() {
                        evidence.candidate_status = "device_or_validation_failure";
                        record_calibration(&evidence);
                        // A device/map error cannot safely continue using this GPU.
                        return Err(diagnostic.unwrap_err());
                    }
                    record_calibration(&evidence);
                }
                if !original_passed {
                    return Err(format!(
                        "No measured acceleration benefit; CPU fallback (CPU {:.3} ms, GPU {:.3} ms including transfers)",
                        cpu_times[1] * 1000.,
                        gpu_times[1] * 1000.
                    ));
                }
            }

            Ok(g)
        }
        fn calibrate_reuse(
            &mut self,
            image: &ImageBuf,
            input: &[f32],
            axis: &[f64],
            curves: &[[f64; 3]],
            evidence: &mut DensityCalibration,
            budget_start: Instant,
        ) -> Result<(), String> {
            // Keep this optional experiment within the existing calibration budget.
            if budget_start.elapsed() >= Duration::from_secs(1) {
                evidence.candidate_status = "budget_exhausted";
                return Ok(());
            }
            let validation_start = Instant::now();
            let baseline = self.run(input, axis, curves, 1.0)?;
            let cpu = CpuBackend.density_curve_interp(image, axis, curves, 1.0);
            let reference: Vec<f64> = cpu.data.iter().map(|v| *v as f64).collect();
            let key = ReuseKey::new(input.len(), axis, curves, 1.0);
            let warm = self.run_reused(input, &key)?;
            let exact = exact_output(&baseline, &warm);
            evidence.candidate_exact_vs_original_gpu = Some(exact);
            if let Some((max_abs, rms, exact_cpu)) = cpu_error(&reference, &warm) {
                evidence.candidate_cpu_max_abs = Some(max_abs);
                evidence.candidate_cpu_rms = Some(rms);
                evidence.candidate_cpu_bit_equal = Some(exact_cpu);
            }
            evidence.candidate_validation_ms =
                Some(validation_start.elapsed().as_secs_f64() * 1000.0);
            if !exact {
                self.reuse_disabled = Some("Allocation reuse exact comparison failed; original GPU allocation path retained".into());
                self.reuse = None;
                evidence.candidate_status = "exact_comparison_failed";
                evidence.candidate_gate_passed = Some(false);
                return Ok(());
            }
            let mut times = Vec::with_capacity(3);
            for _ in 0..3 {
                if budget_start.elapsed() >= Duration::from_secs(1) {
                    evidence.candidate_status = "budget_exhausted";
                    return Ok(());
                }
                let start = Instant::now();
                let upload: Vec<f32> = image.data.iter().map(|v| *v as f32).collect();
                // Match the production candidate's table conversion/key work.
                let timed_key = ReuseKey::new(upload.len(), axis, curves, 1.0);
                let values = self.run_reused(&upload, &timed_key)?;
                if !validate_sampled(&image.data, &values, axis, curves, 1.0) {
                    return Err("GPU calibration numerical check failed".into());
                }
                let returned: Vec<_> = values.iter().map(|v| from_f64(*v as f64)).collect();
                std::hint::black_box(returned);
                times.push(start.elapsed().as_secs_f64());
                evidence.candidate_samples = times.len() as u32;
                // Every measured batch must equal the original allocation route.
                // Comparison is outside the warm-path timer: production performs
                // this full check on first/key change. Validation/setup overhead is
                // separately reported, never represented as warm GPU execution.
                if !exact_output(&baseline, &values) {
                    self.reuse_disabled = Some("Allocation reuse exact comparison failed; original GPU allocation path retained".into());
                    self.reuse = None;
                    evidence.candidate_exact_vs_original_gpu = Some(false);
                    evidence.candidate_status = "exact_comparison_failed";
                    evidence.candidate_gate_passed = Some(false);
                    return Ok(());
                }
            }
            times.sort_by(f64::total_cmp);
            evidence.candidate_gpu_median_ms = Some(times[1] * 1000.0);
            evidence.candidate_gate_passed = evidence
                .cpu_median_ms
                .map(|cpu| cost_gate(cpu, times[1] * 1000.0));
            evidence.candidate_status = "measured_diagnostic_only";
            // Do not reuse this synthetic key as a real image's first-use check.
            self.reuse_key = None;
            Ok(())
        }
        pub fn run(
            &mut self,
            input: &[f32],
            x: &[f64],
            y: &[[f64; 3]],
            gamma: f64,
        ) -> Result<Vec<f32>, String> {
            if input.is_empty()
                || input.len() > MAX_BATCH_PIXELS * 3
                || input.len() % 3 != 0
                || !valid_curves(x, y, gamma)
            {
                return Err("GPU batch limit/curve validation".into());
            }
            if self.error.load(Ordering::Relaxed) {
                return Err("GPU device error".into());
            }
            let axis: Vec<f32> = x.iter().map(|v| (*v / gamma) as f32).collect();
            let curves: Vec<f32> = y.iter().flatten().map(|v| *v as f32).collect();
            let storage = |label, data: &[u8]| {
                self.device
                    .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                        label: Some(label),
                        contents: data,
                        usage: wgpu::BufferUsages::STORAGE,
                    })
            };
            let a = storage("density input", bytemuck::cast_slice(input));
            let b = storage("density axis", bytemuck::cast_slice(&axis));
            let c = storage("density curves", bytemuck::cast_slice(&curves));
            let size = (input.len() * 4) as u64;
            let output = self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("density output"),
                size,
                usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            });
            let readback = self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("bounded readback"),
                size,
                usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
            let params = [input.len() as u32, x.len() as u32, 0, 0];
            let uniform = self
                .device
                .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                    label: Some("density params"),
                    contents: bytemuck::cast_slice(&params),
                    usage: wgpu::BufferUsages::UNIFORM,
                });
            let buffers = [&a, &b, &c, &output, &uniform];
            let entries: Vec<_> = buffers
                .iter()
                .enumerate()
                .map(|(i, b)| wgpu::BindGroupEntry {
                    binding: i as u32,
                    resource: b.as_entire_binding(),
                })
                .collect();
            let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("density binding"),
                layout: &self.pipeline.get_bind_group_layout(0),
                entries: &entries,
            });
            self.dispatch_readback(&bind, &output, &readback, input.len())
        }
        fn dispatch_readback(
            &self,
            bind: &wgpu::BindGroup,
            output: &wgpu::Buffer,
            readback: &wgpu::Buffer,
            scalar_count: usize,
        ) -> Result<Vec<f32>, String> {
            let size = (scalar_count * 4) as u64;
            let mut encoder = self.device.create_command_encoder(&Default::default());
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("density batch"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.pipeline);
                pass.set_bind_group(0, bind, &[]);
                pass.dispatch_workgroups((scalar_count as u32).div_ceil(64), 1, 1);
            }
            encoder.copy_buffer_to_buffer(output, 0, readback, 0, size);
            self.queue.submit([encoder.finish()]);
            let (tx, rx) = mpsc::channel();
            readback
                .slice(0..size)
                .map_async(wgpu::MapMode::Read, move |r| {
                    let _ = tx.send(r);
                });
            let start = Instant::now();
            loop {
                let _ = self.device.poll(wgpu::Maintain::Poll);
                match rx.try_recv() {
                    Ok(Ok(())) => break,
                    Ok(Err(_)) => return Err("GPU readback failed".into()),
                    Err(mpsc::TryRecvError::Disconnected) => {
                        return Err("GPU callback disconnected".into());
                    }
                    Err(mpsc::TryRecvError::Empty) => {}
                }
                if start.elapsed() > Duration::from_secs(3) || self.error.load(Ordering::Relaxed) {
                    return Err("GPU batch timeout/device error".into());
                }
                std::thread::sleep(Duration::from_millis(1));
            }
            let mapped = readback.slice(0..size).get_mapped_range();
            let result = bytemuck::cast_slice::<u8, f32>(&mapped).to_vec();
            drop(mapped);
            readback.unmap();
            Ok(result)
        }
        fn create_reusable(&self) -> ReusableBuffers {
            let buffer = |label, size, usage| {
                self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some(label),
                    size,
                    usage,
                    mapped_at_creation: false,
                })
            };
            let upload = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST;
            let size = (MAX_BATCH_PIXELS * 3 * 4) as u64;
            let input = buffer("reused density input", size, upload);
            let axis = buffer("reused density axis", (MAX_KNOTS * 4) as u64, upload);
            let curves = buffer("reused density curves", (MAX_KNOTS * 3 * 4) as u64, upload);
            let output = buffer(
                "reused density output",
                size,
                wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
            );
            let readback = buffer(
                "reused density readback",
                size,
                wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            );
            let uniform = buffer(
                "reused density params",
                16,
                wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            );
            let buffers = [&input, &axis, &curves, &output, &uniform];
            let entries: Vec<_> = buffers
                .iter()
                .enumerate()
                .map(|(i, b)| wgpu::BindGroupEntry {
                    binding: i as u32,
                    resource: b.as_entire_binding(),
                })
                .collect();
            let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("reused density binding"),
                layout: &self.pipeline.get_bind_group_layout(0),
                entries: &entries,
            });
            ReusableBuffers {
                input,
                axis,
                curves,
                output,
                readback,
                uniform,
                bind,
            }
        }
        fn run_reused(&mut self, input: &[f32], key: &ReuseKey) -> Result<Vec<f32>, String> {
            if self.reuse.is_none() {
                self.reuse = Some(self.create_reusable());
            }
            let b = self.reuse.as_ref().unwrap();
            // Queue writes precede this dispatch. One worker owns these buffers and
            // fully completes readback/unmap before receiving the next request.
            self.queue
                .write_buffer(&b.input, 0, bytemuck::cast_slice(input));
            self.queue
                .write_buffer(&b.axis, 0, bytemuck::cast_slice(&key.axis_bits));
            self.queue
                .write_buffer(&b.curves, 0, bytemuck::cast_slice(&key.curve_bits));
            let params = [input.len() as u32, key.axis_bits.len() as u32, 0, 0];
            self.queue
                .write_buffer(&b.uniform, 0, bytemuck::cast_slice(&params));
            self.dispatch_readback(&b.bind, &b.output, &b.readback, input.len())
        }
        fn run_request(
            &mut self,
            input: &[f32],
            x: &[f64],
            y: &[[f64; 3]],
            gamma: f64,
            reuse: bool,
        ) -> Result<BatchOutput, String> {
            if !reuse || self.reuse_disabled.is_some() {
                return Ok(BatchOutput {
                    values: self.run(input, x, y, gamma)?,
                    reused: false,
                    exact_checked: false,
                    reuse_reason: if reuse {
                        self.reuse_disabled.clone()
                    } else {
                        None
                    },
                });
            }
            if input.is_empty()
                || input.len() > MAX_BATCH_PIXELS * 3
                || input.len() % 3 != 0
                || !valid_curves(x, y, gamma)
                || self.error.load(Ordering::Relaxed)
            {
                return Err("GPU reuse batch validation/device error".into());
            }
            let key = ReuseKey::new(input.len(), x, y, gamma);
            let check = self.reuse_key.as_ref() != Some(&key);
            // Validate the complete actual batch against the original allocation
            // route before accepting reuse for each size/table configuration.
            let baseline = if check {
                Some(self.run(input, x, y, gamma)?)
            } else {
                None
            };
            let values = self.run_reused(input, &key)?;
            if let Some(baseline) = baseline {
                if !exact_output(&baseline, &values) {
                    let reason = "Allocation reuse exact comparison failed; original GPU allocation path retained".to_string();
                    self.reuse_disabled = Some(reason.clone());
                    self.reuse = None;
                    self.reuse_key = None;
                    return Ok(BatchOutput {
                        values: baseline,
                        reused: false,
                        exact_checked: true,
                        reuse_reason: Some(reason),
                    });
                }
            }
            self.reuse_key = Some(key);
            Ok(BatchOutput {
                values,
                reused: true,
                exact_checked: check,
                reuse_reason: None,
            })
        }
    }
    #[cfg(test)]
    mod reuse_tests {
        use super::*;
        #[test]
        #[ignore = "Requires a real Vulkan device; no cloud/Adreno execution claim"]
        fn reused_buffers_match_original_across_sizes_tables_and_stale_data() {
            let mut gpu = test_adapter().expect("Vulkan adapter required");
            let x = [-3.0, -1.0, 0.2, 2.0];
            let y = [
                [0.1, 0.2, 0.3],
                [0.5, 0.8, 0.7],
                [1.3, 1.1, 1.2],
                [2.0, 2.8, 2.4],
            ];
            // Repeated equal keys change every pixel; shrinking/growing changes
            // the map range, including non-workgroup-aligned tail invocations.
            for (iteration, n) in [
                MAX_BATCH_PIXELS * 3,
                MAX_BATCH_PIXELS * 3,
                3075,
                3075,
                3,
                MAX_BATCH_PIXELS * 3,
            ]
            .into_iter()
            .enumerate()
            {
                let input: Vec<f32> = (0..n)
                    .map(|i| ((i * 17 + iteration * 31) % 1000) as f32 / 100.0 - 4.0)
                    .collect();
                let mut curves = y;
                if iteration >= 3 {
                    curves[1][1] = 0.9;
                }
                let baseline = gpu.run_request(&input, &x, &curves, 1.2, false).unwrap();
                let candidate = gpu.run_request(&input, &x, &curves, 1.2, true).unwrap();
                assert!(candidate.reused, "{:?}", candidate.reuse_reason);
                assert_eq!(candidate.exact_checked, iteration != 1);
                assert!(exact_output(&baseline.values, &candidate.values));
            }
        }
    }
    #[cfg(test)]
    pub fn test_adapter() -> Result<Gpu, String> {
        Gpu::new(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn calibration_unknowns_are_null_and_no_gpu_route_is_implied() {
        let evidence = DensityCalibration::default();
        let json = serde_json::to_value(&evidence).unwrap();
        assert_eq!(json["fixturePixels"], 32768);
        assert_eq!(json["curveKnots"], 256);
        assert!(json["cpuMedianMs"].is_null());
        assert!(json["originalGatePassed"].is_null());
        assert!(json["candidateGatePassed"].is_null());
        assert!(json["numericSelfCheckPassed"].is_null());
        assert!(json["shaderF64Advertised"].is_null());
        assert_eq!(json["candidateSamples"], 0);
        assert_eq!(json["candidateStatus"], "not_requested_at_initialization");
        assert!(
            AdrenoDensityBackend::with_enabled(false)
                .diagnostics()
                .density_calibration
                .is_none()
        );
    }
    #[test]
    fn allocation_reuse_is_default_off_and_never_claimed_on_cpu() {
        let baseline = AdrenoDensityBackend::with_enabled(false).diagnostics();
        assert!(!baseline.resource_reuse_requested);
        assert_eq!(baseline.resource_reuse_batches, 0);
        let requested = AdrenoDensityBackend::with_options(false, true).diagnostics();
        assert!(requested.resource_reuse_requested);
        assert_eq!(requested.resource_reuse_batches, 0);
        assert_eq!(requested.resource_reuse_exact_checks, 0);
    }
    #[test]
    fn strict_adreno_vendor_gate() {
        assert!(eligible_adapter(0x5143, "Adreno (TM) 830", true, true));
        assert!(!eligible_adapter(0x5143, "Adreno", false, true));
        assert!(!eligible_adapter(0x10de, "Adreno", true, true));
        assert!(!eligible_adapter(0x5143, "llvmpipe", true, true));
        assert!(!eligible_adapter(0x5143, "Adreno", true, false));
    }
    #[test]
    fn wgsl_validates() {
        let module = naga::front::wgsl::parse_str(SHADER).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap();
    }
    #[test]
    fn unsafe_curve_shapes_rejected() {
        assert!(!valid_curves(&[0., 0.], &[[0.; 3]; 2], 1.));
        assert!(!valid_curves(&[0., 1.], &[[0.; 3]; 2], 0.));
        assert!(!valid_curves(&[0., 1.], &[[f64::NAN; 3]; 2], 1.));
    }
    #[test]
    fn f32_curve_math_stays_inside_tolerance_on_dense_fixture() {
        let axis = [-8., -3., 0., 0.4, 2., 5.];
        let curves = [
            [0.; 3],
            [0.1, 0.2, 0.3],
            [0.7, 1., 1.3],
            [1.1, 1.3, 1.6],
            [2., 2.4, 2.7],
            [3., 3.5, 4.],
        ];
        for gamma in [0.5, 1., 1.8] {
            let a: Vec<f32> = axis.iter().map(|v| (*v / gamma) as f32).collect();
            for j in 0..50000 {
                let x = -10. + j as f32 / 2500.;
                for c in 0..3 {
                    let last = a.len() - 1;
                    let z = if x <= a[0] {
                        curves[0][c] as f32
                    } else if x >= a[last] {
                        curves[last][c] as f32
                    } else {
                        let hi = a.partition_point(|v| *v <= x);
                        let lo = hi - 1;
                        let t = (x - a[lo]) / (a[hi] - a[lo]);
                        curves[lo][c] as f32 + t * (curves[hi][c] as f32 - curves[lo][c] as f32)
                    };
                    assert!(close(
                        z as f64,
                        reference_scalar(x as f64, c, &axis, &curves, gamma)
                    ));
                }
            }
        }
    }
    #[test]
    fn sampled_guard_checks_green_and_blue_on_full_batches() {
        let input = vec![from_f64(0.5); MAX_BATCH_PIXELS * 3];
        let axis = [0., 1.];
        let curves = [[0.; 3], [1.; 3]];
        let output = vec![0.5f32; input.len()];
        assert!(validate_sampled(&input, &output, &axis, &curves, 1.));
        for c in 0..3 {
            let mut bad = output.clone();
            bad[512 * 3 + c] = 0.8;
            assert!(
                !validate_sampled(&input, &bad, &axis, &curves, 1.),
                "channel {c} must be checked"
            );
        }
        for c in 0..3 {
            let mut bad = output.clone();
            bad[(MAX_BATCH_PIXELS - 1) * 3 + c] = 0.8;
            assert!(!validate_sampled(&input, &bad, &axis, &curves, 1.));
        }
    }
    #[test]
    fn startup_timeout_is_bounded_and_failure_cached_without_respawn() {
        use std::{
            sync::{Arc, OnceLock, atomic::AtomicUsize},
            time::{Duration, Instant},
        };
        let cache: OnceLock<Result<u32, String>> = OnceLock::new();
        let calls = Arc::new(AtomicUsize::new(0));
        let count = calls.clone();
        let start = Instant::now();
        let first = cache.get_or_init(|| {
            initialize_bounded(Duration::from_millis(5), move || {
                count.fetch_add(1, Ordering::SeqCst);
                std::thread::sleep(Duration::from_millis(100));
                Ok(1)
            })
        });
        assert!(first.is_err());
        assert!(start.elapsed() < Duration::from_secs(1));
        let second = cache.get_or_init(|| {
            calls.fetch_add(100, Ordering::SeqCst);
            Ok(2)
        });
        assert!(second.is_err());
        std::thread::sleep(Duration::from_millis(10));
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }
    #[test]
    fn diagnostics_reports_use_not_just_eligibility() {
        let b = AdrenoDensityBackend::with_enabled(false);
        assert!(!b.diagnostics().active);
        assert_eq!(b.diagnostics().backend, "cpu");
        b.batches.store(2, Ordering::Relaxed);
        let d = b.diagnostics();
        assert!(d.active);
        assert!(!d.available);
        assert_eq!(d.backend, "vulkan-adreno-partial");
    }
    #[test]
    fn startup_success_within_deadline() {
        assert_eq!(
            initialize_bounded(std::time::Duration::from_secs(1), || Ok(42)).unwrap(),
            42
        );
    }
    #[test]
    fn cpu_fallback_is_exact() {
        let b = AdrenoDensityBackend::with_enabled(false);
        let i = ImageBuf::from_data(
            2,
            1,
            vec![
                from_f64(-2.),
                from_f64(0.2),
                from_f64(1.),
                from_f64(-2.),
                from_f64(0.2),
                from_f64(1.),
            ],
        );
        let x = [-1., 0., 1.];
        let y = [[0.; 3], [0.5; 3], [1.; 3]];
        assert_eq!(
            b.density_curve_interp(&i, &x, &y, 1.2).data,
            CpuBackend.density_curve_interp(&i, &x, &y, 1.2).data
        );
        assert!(!b.diagnostics().active);
    }
    #[cfg(feature = "adreno-gpu")]
    #[test]
    #[ignore = "Explicit cloud Vulkan test; not Android/Adreno validation"]
    fn host_vulkan_compute_matches_cpu() {
        let mut g =
            gpu::test_adapter().expect("Host Vulkan adapter required for this explicit test");
        let x = [-8., -3., 0., 0.4, 2., 5.];
        let y = [
            [0.; 3],
            [0.1, 0.2, 0.3],
            [0.7, 1., 1.3],
            [1.1, 1.3, 1.6],
            [2., 2.4, 2.7],
            [3., 3.5, 4.],
        ];
        for gamma in [0.5, 1., 1.8] {
            let input: Vec<f32> = (0..MAX_BATCH_PIXELS * 3)
                .map(|k| -10. + (k % 1231) as f32 / 70.)
                .collect();
            let z = g.run(&input, &x, &y, gamma).unwrap();
            for (k, v) in z.iter().enumerate() {
                assert!(close(
                    *v as f64,
                    reference_scalar(input[k] as f64, k % 3, &x, &y, gamma)
                ));
            }
        }
    }
}
