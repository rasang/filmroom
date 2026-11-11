//! Optional partial GPU acceleration: density interpolation only. All spectral,
//! spatial, grain and calibration algorithms remain the exact CPU implementations.
//! Android Vulkan hardware adapters, without a vendor/model allowlist. Device
//! limits, numerical self-checks and measured cost still gate partial acceleration.
//! The module/feature and diagnostic IDs retain their legacy Adreno names for ABI
//! compatibility. Host production builds deliberately stay on CPU.
use serde::Serialize;
use spektrafilm_gpu::{ComputeBackend, Lut3D, cpu_backend::CpuBackend};
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use std::sync::{
    Mutex,
    atomic::{AtomicU64, Ordering},
};

pub const MAX_BATCH_PIXELS: usize = 32768;
pub const MAX_KNOTS: usize = 4096;
pub const ABS_TOLERANCE: f64 = 2e-5;
pub const REL_TOLERANCE: f64 = 2e-5;
pub const SHADER: &str = include_str!("shaders/adreno_density.wgsl");
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
    pub numeric_guard_passed: bool,
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
            numeric_guard_passed: false,
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
            match gpu::cached() {
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
        #[cfg(feature = "adreno-gpu")]
        if s.active {
            match self.gpu.try_lock() {
                Ok(g) if g.is_some() => {},
                Ok(_) => {
                    s.active = false;
                    s.reason = "GPU disabled after device or numerical failure; CPU fallback".into();
                },
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
/// Identity is diagnostic only: Mali/Immortalis, PowerVR and future vendors use
/// the same Vulkan capability checks as Adreno. Do not mistake a CPU software
/// adapter for hardware acceleration. Device creation validates actual limits.
pub fn eligible_adapter(_vendor: u32, _name: &str, backend_vulkan: bool, hardware_gpu: bool) -> bool {
    backend_vulkan && hardware_gpu
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
                    for chunk in i.data.chunks(MAX_BATCH_PIXELS * 3) {
                        let pixels: Vec<f32> = chunk.iter().map(|v| *v as f32).collect();
                        let values = g.run(&pixels, x, y, gamma)?;
                        // Sample pixel locations, and all three channels at each location.
                        if !validate_sampled(chunk, &values, x, y, gamma) {
                            return Err("GPU numerical check failed; CPU fallback".into());
                        }
                        out.extend(values.into_iter().map(|v| from_f64(v as f64)));
                    }
                    Ok(out)
                })
            };
            if let Some(r) = result {
                match r {
                    Ok(v) => {
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

#[cfg(feature = "adreno-gpu")]
mod gpu {
    use super::*;
    use std::{
        sync::{Arc, atomic::AtomicBool, mpsc},
        time::{Duration, Instant},
    };
    use wgpu::util::DeviceExt;
    pub struct Gpu {
        device: wgpu::Device,
        queue: wgpu::Queue,
        pipeline: wgpu::ComputePipeline,
        error: Arc<AtomicBool>,
        pub adapter: String,
    }
    struct Request {
        input: Vec<f32>,
        axis: Vec<f64>,
        curves: Vec<[f64; 3]>,
        gamma: f64,
        response: mpsc::Sender<Result<Vec<f32>, String>>,
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
        ) -> Result<Vec<f32>, String> {
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
                    response: tx,
                })
                .map_err(|_| "GPU worker unavailable; CPU fallback".to_string())?;
            rx.recv_timeout(Duration::from_secs(3)).map_err(|_| {
                "GPU submission timed out or failed; CPU fallback until process restart".to_string()
            })?
        }
    }
    #[cfg(target_os = "android")]
    pub fn cached() -> Result<Arc<Mutex<Option<Handle>>>, String> {
        static GPU: std::sync::OnceLock<Result<Arc<Mutex<Option<Handle>>>, String>> =
            std::sync::OnceLock::new();
        GPU.get_or_init(|| {
            initialize_serving(Duration::from_secs(2), || {
                let mut g = Gpu::new(false)?;
                let (tx, rx) = mpsc::sync_channel::<Request>(1);
                let handle = Arc::new(Mutex::new(Some(Handle {
                    tx,
                    adapter: g.adapter.clone(),
                })));
                let serve = Box::new(move || {
                    while let Ok(q) = rx.recv() {
                        let result = g.run(&q.input, &q.axis, &q.curves, q.gamma);
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
            if !allow_test_adapter
                && !eligible_adapter(
                    info.vendor,
                    &info.name,
                    info.backend == wgpu::Backend::Vulkan,
                    info.device_type != wgpu::DeviceType::Cpu,
                )
            {
                return Err("Not a Vulkan hardware adapter".into());
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
                return Err("Adreno density startup self-check failed".into());
            }
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
                    if !validate_sampled(&image.data,&values,&axis,&curves,1.){return Err("GPU calibration numerical check failed".into());}
                    let returned: Vec<_> = values.into_iter().map(|v| from_f64(v as f64)).collect();
                    std::hint::black_box(returned);
                    gpu_times.push(at.elapsed().as_secs_f64());
                    if benchmark_start.elapsed() > Duration::from_secs(1) {
                        return Err("No measured acceleration benefit; CPU fallback (calibration time budget exceeded)".into());
                    }
                }
                cpu_times.sort_by(f64::total_cmp);
                gpu_times.sort_by(f64::total_cmp);
                if gpu_times[1] >= cpu_times[1] * 0.85 {
                    return Err(format!(
                        "No measured acceleration benefit; CPU fallback (CPU {:.3} ms, GPU {:.3} ms including transfers)",
                        cpu_times[1] * 1000.,
                        gpu_times[1] * 1000.
                    ));
                }
            }
            Ok(g)
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
            let mut encoder = self.device.create_command_encoder(&Default::default());
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("density batch"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.pipeline);
                pass.set_bind_group(0, &bind, &[]);
                pass.dispatch_workgroups((input.len() as u32).div_ceil(64), 1, 1);
            }
            encoder.copy_buffer_to_buffer(&output, 0, &readback, 0, size);
            self.queue.submit([encoder.finish()]);
            let (tx, rx) = mpsc::channel();
            readback.slice(..).map_async(wgpu::MapMode::Read, move |r| {
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
            let mapped = readback.slice(..).get_mapped_range();
            let result = bytemuck::cast_slice::<u8, f32>(&mapped).to_vec();
            drop(mapped);
            readback.unmap();
            Ok(result)
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
    fn vulkan_hardware_admission_is_vendor_neutral() {
        for (vendor, name) in [
            (0x5143, "Adreno (TM) 830"),
            (0x13b5, "Mali-G715"),
            (0x13b5, "Immortalis-G925"),
            (0x1010, "PowerVR B-Series"),
            (0x144d, "Samsung Xclipse 940"),
            (0x10de, "NVIDIA"),
            (0, "Unknown future hardware"),
        ] {
            assert!(eligible_adapter(vendor, name, true, true), "{name}");
            assert!(!eligible_adapter(vendor, name, false, true), "non-Vulkan {name}");
            assert!(!eligible_adapter(vendor, name, true, false), "software {name}");
        }
        assert!(!eligible_adapter(0x10005, "llvmpipe", true, false));
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
