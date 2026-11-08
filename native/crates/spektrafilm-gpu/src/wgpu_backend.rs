#[cfg(feature = "resource-audit")]
#[path = "audited_resources.rs"]
mod audited_resources;
#[cfg(feature = "resource-audit")]
use audited_resources::{Buffer as AuditBuffer, BindGroup as AuditBindGroup, BindGroupEntry as AuditBindGroupEntry,
    BindGroupDescriptor as AuditBindGroupDescriptor, CommandEncoder as AuditCommandEncoder, CommandBuffer as AuditCommandBuffer};
#[cfg(not(feature = "resource-audit"))]
use wgpu::{Buffer as AuditBuffer, BindGroup as AuditBindGroup, BindGroupEntry as AuditBindGroupEntry,
    BindGroupDescriptor as AuditBindGroupDescriptor, CommandEncoder as AuditCommandEncoder, CommandBuffer as AuditCommandBuffer};
trait AuditDeviceExt {
    fn audit_create_bind_group(&self, desc: &AuditBindGroupDescriptor<'_>) -> AuditBindGroup;
    fn audit_create_command_encoder(&self, desc: &wgpu::CommandEncoderDescriptor<'_>) -> AuditCommandEncoder;
}
impl AuditDeviceExt for wgpu::Device {
    #[inline(always)]
    fn audit_create_bind_group(&self, desc: &AuditBindGroupDescriptor<'_>) -> AuditBindGroup {
        #[cfg(feature = "resource-audit")] { audited_resources::bind_group(self, desc) }
        #[cfg(not(feature = "resource-audit"))] { self.create_bind_group(desc) }
    }
    #[inline(always)]
    fn audit_create_command_encoder(&self, desc: &wgpu::CommandEncoderDescriptor<'_>) -> AuditCommandEncoder {
        #[cfg(feature = "resource-audit")] { audited_resources::encoder(self, desc) }
        #[cfg(not(feature = "resource-audit"))] { self.create_command_encoder(desc) }
    }
}

/// wgpu compute backend — dispatches WGSL shaders on GPU via Metal/Vulkan/DX12.


// RESOURCE_AUDIT_FORWARDERS_BEGIN
// Feature-off methods are inline, exact forwarding only: no observer, TLS,
// extra allocation, drop, submit, map, wait or planner change.
#[cfg(feature = "wgpu-backend")]
trait CensusDeviceExt {
    fn census_create_buffer(&self, desc: &wgpu::BufferDescriptor<'_>) -> AuditBuffer;
    fn census_create_buffer_init(&self, desc: &wgpu::util::BufferInitDescriptor<'_>) -> AuditBuffer;
}
#[cfg(feature = "wgpu-backend")]
impl CensusDeviceExt for wgpu::Device {
    #[inline(always)]
    fn census_create_buffer(&self, desc: &wgpu::BufferDescriptor<'_>) -> AuditBuffer {
        let buffer = self.create_buffer(desc);
        #[cfg(feature = "resource-audit")]
        crate::resource_audit::buffer_created(desc.label, buffer.size(), desc.usage.bits(),
            desc.usage.contains(wgpu::BufferUsages::STORAGE),
            desc.usage.contains(wgpu::BufferUsages::MAP_READ), 0);
        #[cfg(feature = "resource-audit")]
        { AuditBuffer::new(buffer, desc.mapped_at_creation && !desc.usage.contains(wgpu::BufferUsages::MAP_WRITE)) }
        #[cfg(not(feature = "resource-audit"))]
        { buffer }
    }
    #[inline(always)]
    fn census_create_buffer_init(&self, desc: &wgpu::util::BufferInitDescriptor<'_>) -> AuditBuffer {
        use wgpu::util::DeviceExt;
        // wgpu 24 stages non-MAP_WRITE mapped-at-creation initialization.
        // Mark before the API call so an unmap/submit panic cannot hide pending work.
        #[cfg(feature = "resource-audit")]
        if !desc.contents.is_empty() && !desc.usage.contains(wgpu::BufferUsages::MAP_WRITE) {
            crate::resource_audit::resident_owners::mark_pending_write();
        }
        let buffer = self.create_buffer_init(desc);
        #[cfg(feature = "resource-audit")]
        crate::resource_audit::buffer_created(desc.label, buffer.size(), desc.usage.bits(),
            desc.usage.contains(wgpu::BufferUsages::STORAGE),
            desc.usage.contains(wgpu::BufferUsages::MAP_READ), desc.contents.len() as u64);
        #[cfg(feature = "resource-audit")]
        {
            let buffer = AuditBuffer::new(buffer, false);
            if !desc.contents.is_empty() && !desc.usage.contains(wgpu::BufferUsages::MAP_WRITE) { buffer.queue_write(); }
            buffer
        }
        #[cfg(not(feature = "resource-audit"))]
        { buffer }
    }
}
#[cfg(feature = "wgpu-backend")]
trait CensusQueueExt {
    fn census_write_buffer(&self, buffer: &AuditBuffer, offset: u64, data: &[u8]);
}
#[cfg(feature = "wgpu-backend")]
impl CensusQueueExt for wgpu::Queue {
    #[inline(always)]
    fn census_write_buffer(&self, buffer: &AuditBuffer, offset: u64, data: &[u8]) {
        #[cfg(feature = "resource-audit")]
        if !data.is_empty() { buffer.queue_write(); }
        self.write_buffer(buffer, offset, data);
        #[cfg(feature = "resource-audit")]
        crate::resource_audit::queue_write(data.len() as u64);
    }
}
// RESOURCE_AUDIT_FORWARDERS_END

#[cfg(feature = "wgpu-backend")]
use std::borrow::Cow;

#[cfg(feature = "wgpu-backend")]
use spektrafilm_math::image::ImageBuf;

#[cfg(feature = "wgpu-backend")]
use crate::{ComputeBackend, Lut3D};

#[cfg(feature = "wgpu-backend")]
thread_local! {
    static THREAD_SUBMITS: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
    static THREAD_RESIDENT_PROGRESS: std::cell::Cell<Option<ResidentBatchProgress>> = const { std::cell::Cell::new(None) };
    static THREAD_RESIDENT_FAILURE: std::cell::Cell<Option<ResidentBatchFailure>> = const { std::cell::Cell::new(None) };
}

#[cfg(feature = "wgpu-backend")]
fn bounded_init<F: std::future::Future>(future: F) -> Option<F::Output> {
    struct Wake;
    impl std::task::Wake for Wake { fn wake(self: std::sync::Arc<Self>) {} }
    let waker = std::task::Waker::from(std::sync::Arc::new(Wake));
    let mut cx = std::task::Context::from_waker(&waker);
    let mut future = std::pin::pin!(future);
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(6);
    loop {
        match future.as_mut().poll(&mut cx) {
            std::task::Poll::Ready(value) => return Some(value),
            std::task::Poll::Pending if std::time::Instant::now() < deadline =>
                std::thread::sleep(std::time::Duration::from_millis(2)),
            std::task::Poll::Pending => return None,
        }
    }
}

/// Borrow a `&[Scalar]` as `&[f32]` for GPU upload.
/// Zero-copy in f32 mode; allocates a converted Vec in f64 mode.
#[cfg(all(feature = "wgpu-backend", not(feature = "precision-f64")))]
#[inline]
fn scalars_to_f32(v: &[spektrafilm_math::precision::Scalar]) -> std::borrow::Cow<'_, [f32]> {
    std::borrow::Cow::Borrowed(v)
}

/// Fixed, non-driver stage identifiers suitable for progress/diagnostics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResidentStage { Front, CameraOptics, Halation, FilmLog, FilmDensity, Dir, Grain, Print, Scan, Glare, Output }
impl ResidentStage {
    pub fn as_str(self) -> &'static str {
        match self { Self::Front=>"front", Self::CameraOptics=>"camera_optics", Self::Halation=>"halation",
            Self::FilmLog=>"film_log", Self::FilmDensity=>"film_density", Self::Dir=>"dir", Self::Grain=>"grain", Self::Print=>"print",
            Self::Scan=>"scan", Self::Glare=>"glare", Self::Output=>"output" }
    }
}
/// Only proven-complete submissions contribute to this per-thread snapshot.
#[derive(Debug, Clone, Copy)]
pub struct ResidentBatchProgress {
    pub stage: ResidentStage,
    pub completed_batches: u32,
    pub batch_elapsed_ms: f64,
    pub chain_gpu_wait_ms: f64,
}
#[derive(Debug, Clone, Copy)]
pub struct ResidentBatchFailure { pub stage: ResidentStage, pub elapsed_ms: f64, pub detail: failure_trace::Snapshot }
#[path = "failure_trace.rs"]
pub mod failure_trace;
#[cfg(feature = "wgpu-backend")]
struct ResidentBatchAttempt { stage: ResidentStage, start: std::time::Instant, completed: bool }
#[cfg(feature = "wgpu-backend")]
impl Drop for ResidentBatchAttempt {
    fn drop(&mut self) {
        if !self.completed {
            THREAD_RESIDENT_FAILURE.with(|v| v.set(Some(ResidentBatchFailure {
                stage: self.stage, detail: failure_trace::snapshot(), elapsed_ms: self.start.elapsed().as_secs_f64() * 1000.0 })));
        }
    }
}

/// Return failure telemetry owned by this export attempt, even if the first
/// submit unwinds before its success counter advances. Consuming the snapshot
/// prevents a later CPU-only or rejected-adapter attempt from inheriting it.
/// This does not acknowledge any submission or change device quarantine.
#[cfg(feature = "wgpu-backend")]
pub fn observe_resident_attempt<T>(attempt: impl FnOnce() -> T)
    -> (std::thread::Result<T>, Option<ResidentBatchFailure>)
{
    THREAD_RESIDENT_FAILURE.with(|v| v.set(None));
    failure_trace::reset();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(attempt));
    let failure = THREAD_RESIDENT_FAILURE.with(|v| v.replace(None));
    (result, failure)
}

/// Must match the 1D point-kernel WGSL declarations. Spatial kernels stay
/// 16x16 and reduction kernels keep their own values-per-group contract.
pub const POINT_WORKGROUP_SIZE: u32 = 256;

/// Checked 1D dispatch geometry, including shader u32 addressing. Separate
/// from buffer admission; callers must satisfy both limits.
pub fn point_dispatch_groups(values: u64, max_groups: u32) -> Option<u32> {
    if values == 0 || values > u64::from(u32::MAX) { return None; }
    let groups=values.div_ceil(u64::from(POINT_WORKGROUP_SIZE));
    (groups <= u64::from(max_groups)).then_some(groups as u32)
}

#[cfg(feature = "wgpu-backend")]
fn supports_resident_workgroups(limits: &wgpu::Limits) -> bool {
    limits.max_compute_invocations_per_workgroup >= POINT_WORKGROUP_SIZE
        && limits.max_compute_workgroup_size_x >= POINT_WORKGROUP_SIZE
        && limits.max_compute_workgroup_size_y >= 16
        && limits.max_compute_workgroup_size_z >= 1
}

/// FIR Gaussian-blur half-width `ceil(3σ)`, hard-capped so a pathological σ
/// can never build a multi-thousand-tap kernel that hangs the GPU (a
/// monster kernel froze the display once). Callers that need a blur wider
/// than this must downsample first (the diffusion filter does). 256 → at
/// most a 513-tap separable kernel; well above any legitimate σ here
/// (halation tops out at tens of pixels).
const MAX_BLUR_RADIUS: u32 = 256;

#[inline]
fn fir_blur_radius(sigma: f32) -> u32 {
    ((3.0_f32 * sigma).ceil() as u32).min(MAX_BLUR_RADIUS)
}

#[cfg(all(feature = "wgpu-backend", feature = "precision-f64"))]
#[inline]
fn scalars_to_f32(v: &[spektrafilm_math::precision::Scalar]) -> std::borrow::Cow<'static, [f32]> {
    std::borrow::Cow::Owned(v.iter().map(|&s| s as f32).collect())
}

/// Convert a `Vec<f32>` from GPU readback into `Vec<Scalar>`.
/// Zero-copy (identity) in f32 mode; allocates in f64 mode.
#[cfg(all(feature = "wgpu-backend", not(feature = "precision-f64")))]
#[inline]
fn f32_to_scalars(v: Vec<f32>) -> Vec<spektrafilm_math::precision::Scalar> {
    // RESOURCE_AUDIT_BEGIN
    #[cfg(feature = "resource-audit")]
    {
        crate::resource_audit::observe_host("readback_f32_identity",
            crate::resource_audit::HostObservationKind::VecCapacity, v.capacity() as u64 * 4);
    }
    // RESOURCE_AUDIT_END
    v
}

/// Sanitize NaN values in spectral inputs before GPU upload.
///
/// **Why this exists**: profile JSON has NaN values for `channel_density` /
/// `base_density` at UV/IR wavelengths where the dye/base density isn't
/// measured. Python's `density_to_light` zeros NaN values out (correctly
/// excluding those wavelengths from the einsum). WGSL on Metal compiles with
/// fast-math semantics and optimizes away `x != x` NaN checks, so we cannot
/// rely on in-shader NaN handling. We sanitize CPU-side instead:
///
/// - `channel_density` NaN → 0
/// - `base_density` NaN OR any of its row in channel_density is NaN → +1000
///   (so `pow(10, -1000) ≈ 0` zeros the whole wavelength's contribution)
///
/// This preserves Python's wavelength-skipping semantics exactly.
#[cfg(feature = "wgpu-backend")]
fn sanitize_spectral_inputs(
    channel_density: &[[f64; 3]],
    base_density: &[f64],
    n_wl: usize,
) -> (Vec<f32>, Vec<f32>) {
    let mut cd = Vec::with_capacity(n_wl * 3);
    let mut bd = Vec::with_capacity(n_wl);
    for wl in 0..n_wl {
        let r = channel_density[wl][0];
        let g = channel_density[wl][1];
        let b = channel_density[wl][2];
        let base = if wl < base_density.len() {
            base_density[wl]
        } else {
            f64::NAN
        };
        let row_has_nan = r.is_nan() || g.is_nan() || b.is_nan() || base.is_nan();
        cd.push(if r.is_nan() { 0.0 } else { r as f32 });
        cd.push(if g.is_nan() { 0.0 } else { g as f32 });
        cd.push(if b.is_nan() { 0.0 } else { b as f32 });
        bd.push(if row_has_nan { 1000.0 } else { base as f32 });
    }
    (cd, bd)
}

#[cfg(all(feature = "wgpu-backend", feature = "precision-f64"))]
#[inline]
fn f32_to_scalars(v: Vec<f32>) -> Vec<spektrafilm_math::precision::Scalar> {
    // RESOURCE_AUDIT_BEGIN
    #[cfg(feature = "resource-audit")]
    {
        use crate::resource_audit::{observe_host, HostObservationKind};
        let input_capacity = v.capacity() as u64 * 4;
        observe_host("readback_f32", HostObservationKind::VecCapacity, input_capacity);
        let output: Vec<spektrafilm_math::precision::Scalar> = v.into_iter().map(|x| x as f64).collect();
        let output_capacity = output.capacity() as u64 * 8;
        observe_host("converted_f64", HostObservationKind::VecCapacity, output_capacity);
        observe_host("f32_to_f64", HostObservationKind::ConversionCapacityEnvelope,
            input_capacity.saturating_add(output_capacity));
        output
    }
    #[cfg(not(feature = "resource-audit"))]
    // RESOURCE_AUDIT_END
    v.into_iter().map(|x| x as f64).collect()
}

#[cfg(feature = "wgpu-backend")]
pub struct WgpuBackend {
    #[cfg(feature = "resource-audit")]
    audit_device_id: Option<u64>,
    #[cfg(feature = "resource-audit")]
    audit_pending_writes: std::sync::Arc<std::sync::atomic::AtomicBool>,
    device: wgpu::Device,
    queue: wgpu::Queue,
    /// Cache compiled compute pipelines keyed by shader source pointer.
    /// `&'static str` is fine because all our shader sources come from `include_str!`.
    pipeline_cache: std::sync::Mutex<std::collections::HashMap<usize, CachedPipeline>>,
    /// Human-readable adapter name, surfaced in mobile GPU diagnostics.
    adapter_name: String,
    /// Total command-buffer submissions since process start. Mobile preview
    /// diagnostics read the per-frame delta to prove real GPU execution.
    submits: std::sync::atomic::AtomicU64,
    healthy: std::sync::atomic::AtomicBool,
    completion_uncertain: std::sync::atomic::AtomicBool,
    execution: std::sync::Mutex<()>,
}

/// A submitted command owns its uncertainty until all mappings complete.
/// Unwinding after submit/map setup cannot silently authorize a CPU overlap.
#[cfg(feature = "wgpu-backend")]
struct SubmittedWork<'a> {
    healthy: &'a std::sync::atomic::AtomicBool,
    completion_uncertain: &'a std::sync::atomic::AtomicBool,
    completed: bool,
    #[cfg(feature = "resource-audit")]
    owner: Option<crate::resource_audit::resident_owners::Owner>,
    #[cfg(feature = "resource-audit")]
    pending_writes: Option<&'a std::sync::atomic::AtomicBool>,
}
#[cfg(feature = "wgpu-backend")]
impl SubmittedWork<'_> {
    fn complete(&mut self) {
        self.completed = true;
        #[cfg(feature = "resource-audit")]
        if let Some(owner) = &mut self.owner { owner.complete(); }
        #[cfg(feature = "resource-audit")]
        if let Some(pending) = self.pending_writes { pending.store(false, std::sync::atomic::Ordering::Release); }
    }
}

#[cfg(feature = "wgpu-backend")]
impl Drop for SubmittedWork<'_> {
    fn drop(&mut self) {
        if !self.completed {
            self.completion_uncertain.store(true, std::sync::atomic::Ordering::Release);
            self.healthy.store(false, std::sync::atomic::Ordering::Release);
        }
    }
}

#[cfg(feature = "wgpu-backend")]
struct CachedPipeline {
    bind_group_layout: wgpu::BindGroupLayout,
    pipeline: wgpu::ComputePipeline,
}

#[cfg(feature = "wgpu-backend")]
fn only_when_adapter_allowed<T>(allowed: bool, create: impl FnOnce() -> Option<T>) -> Option<T> {
    if allowed { create() } else { None }
}

#[cfg(feature = "wgpu-backend")]
impl WgpuBackend {
    pub fn new() -> Option<Self> {
        Self::new_with_adapter_filter(|_| true)
    }

    /// Select an adapter before creating a device. The default constructor remains unrestricted.
    pub fn new_with_adapter_filter(accept: impl FnOnce(&wgpu::AdapterInfo) -> bool) -> Option<Self> {
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::PRIMARY,
            ..Default::default()
        });

        let adapter = bounded_init(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: None,
            force_fallback_adapter: false,
        }))??;

        // The default `Limits` cap storage buffer bindings at 128 MB,
        // which a 6-channel ≥ 14 MP image exceeds (image_bytes =
        // width × height × 3 × 4). Bump every relevant limit up to the
        // adapter's hardware ceiling so we can render arbitrary
        // megapixel counts (within RAM).
        let adapter_info = adapter.get_info();
        let (device, queue) = only_when_adapter_allowed(accept(&adapter_info), || {
            let adapter_limits = adapter.limits();
            let mut limits = wgpu::Limits::default();
            limits.max_storage_buffer_binding_size = adapter_limits.max_storage_buffer_binding_size;
            limits.max_buffer_size = adapter_limits.max_buffer_size;
            limits.max_compute_workgroups_per_dimension =
                adapter_limits.max_compute_workgroups_per_dimension;
            limits.max_bind_groups = adapter_limits.max_bind_groups.max(limits.max_bind_groups);
            // Portable 1D point kernels and 16x16 spatial kernels both need
            // 256 invocations. Do not request 1024 or assume adapter ceilings
            // make a 1024-wide shader legal on a 256/512-wide mobile device.
            if !supports_resident_workgroups(&adapter_limits) {
                return None;
            }
            limits.max_compute_invocations_per_workgroup = POINT_WORKGROUP_SIZE;
            limits.max_compute_workgroup_size_x = POINT_WORKGROUP_SIZE;
            limits.max_compute_workgroup_size_y = 16;
            limits.max_compute_workgroup_size_z = 1;
            // MAPPABLE_PRIMARY_BUFFERS lets the input/output STORAGE buffers also
            // be MAP_WRITE / MAP_READ, so they map directly for a zero-copy
            // upload/readback. On unified-memory GPUs this skips the slow
            // Private↔Shared staging blits (~0.5 GB/s) that otherwise dominate the
            // per-frame cost. No-op (falls back to the blit path) if unsupported.
            let opt_feats = if adapter_info.device_type == wgpu::DeviceType::IntegratedGpu {
                wgpu::Features::MAPPABLE_PRIMARY_BUFFERS & adapter.features()
            } else {
                wgpu::Features::empty()
            };
            bounded_init(adapter.request_device(
                &wgpu::DeviceDescriptor {
                    label: Some("spektrafilm"),
                    required_features: opt_feats,
                    required_limits: limits,
                    memory_hints: wgpu::MemoryHints::Performance,
                },
                None,
            ))?
            .ok()
        })?;

        tracing::info!(
            adapter = adapter_info.name,
            backend = ?adapter_info.backend,
            "wgpu backend initialized"
        );

        Some(Self {
            #[cfg(feature = "resource-audit")]
            audit_device_id: crate::resource_audit::observed_owners::identity(),
            #[cfg(feature = "resource-audit")]
            audit_pending_writes: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            device,
            queue,
            pipeline_cache: std::sync::Mutex::new(std::collections::HashMap::new()),
            adapter_name: adapter_info.name.clone(),
            submits: std::sync::atomic::AtomicU64::new(0),
            healthy: std::sync::atomic::AtomicBool::new(true),
            completion_uncertain: std::sync::atomic::AtomicBool::new(false),
            execution: std::sync::Mutex::new(()),
        })
    }

    /// Human-readable GPU adapter name (e.g. for mobile diagnostics).
    pub fn adapter_name(&self) -> &str {
        &self.adapter_name
    }

    /// Monotonic count of GPU command-buffer submissions on this backend.
    pub fn submits(&self) -> u64 {
        self.submits.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// Whether the device maps primary buffers directly (no readback staging
    /// buffer). Used by the export memory gate's residency estimate.
    pub fn mappable_primary_buffers(&self) -> bool {
        self.device
            .features()
            .contains(wgpu::Features::MAPPABLE_PRIMARY_BUFFERS)
    }

    /// The device's max single storage-buffer binding size (bytes). One full-res
    /// RGB f32 frame (width*height*3*4) must fit under this to be bound as a
    /// storage buffer; many mobile adapters cap this well below `max_buffer_size`.
    pub fn max_storage_buffer_binding_size(&self) -> u64 {
        self.device.limits().max_storage_buffer_binding_size as u64
    }

    /// A failed/expired device is never reused in this process. This does not
    /// claim that a driver call itself is preemptible. A driver-level hang
    /// can still require process recovery.
    pub fn is_healthy(&self) -> bool {
        self.healthy.load(std::sync::atomic::Ordering::Acquire)
    }

    /// Sticky until process restart: a submitted command was not proven
    /// complete. Callers must not allocate CPU fallback work on this process.
    pub fn completion_uncertain(&self) -> bool {
        self.completion_uncertain.load(std::sync::atomic::Ordering::Acquire)
    }

    fn submit_tracked(&self, command: AuditCommandBuffer) -> SubmittedWork<'_> {
        // A panic inside queue.submit has an unknown acceptance boundary, so
        // conservatively retain the same sticky failure as a map timeout.
        #[cfg(feature = "resource-audit")]
        let (command, owner) = {
            let owner = command.owner.transfer(crate::resource_audit::resident_owners::Kind::Submission);
            owner.absorb_queue_writes();
            (command.raw, owner)
        };
        let submission = SubmittedWork { healthy: &self.healthy, completion_uncertain: &self.completion_uncertain, completed: false,
            #[cfg(feature = "resource-audit")]
            owner: Some(owner),
            #[cfg(feature = "resource-audit")]
            pending_writes: Some(&self.audit_pending_writes),
        };
        { let _span = failure_trace::Span::new(failure_trace::Step::Submit);
        self.queue.submit(Some(command)); }
        self.record_submit();
        submission
    }

    /// Number of actual submissions by this calling worker thread. Interleaved
    /// preview/export work on other threads is never attributed to this job.
    pub fn thread_submits(&self) -> u64 { THREAD_SUBMITS.with(|n| n.get()) }
    pub fn thread_resident_progress(&self) -> Option<ResidentBatchProgress> { THREAD_RESIDENT_PROGRESS.with(|v| v.get()) }
    pub fn thread_resident_failure(&self) -> Option<ResidentBatchFailure> { THREAD_RESIDENT_FAILURE.with(|v| v.get()) }

    fn record_submit(&self) {
        self.submits.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        THREAD_SUBMITS.with(|n| n.set(n.get().saturating_add(1)));
    }

    fn acquire_dispatch(&self) -> std::sync::MutexGuard<'_, ()> {
        let deadline = std::time::Instant::now() + std::time::Duration::from_millis(250);
        loop {
            assert!(self.is_healthy(), "GPU_DEVICE_RETIRED");
            match self.execution.try_lock() {
                Ok(guard) => return guard,
                Err(std::sync::TryLockError::Poisoned(_)) => {
                    self.healthy.store(false, std::sync::atomic::Ordering::Release);
                    panic!("GPU_DEVICE_RETIRED");
                }
                Err(std::sync::TryLockError::WouldBlock) => {
                    assert!(std::time::Instant::now() < deadline, "GPU_QUEUE_BUSY");
                    std::thread::sleep(std::time::Duration::from_millis(2));
                }
            }
        }
    }

    fn await_maps(&self, rx: &std::sync::mpsc::Receiver<Result<(), wgpu::BufferAsyncError>>, count: usize) {
        let _wait = failure_trace::Span::new(failure_trace::Step::Wait);
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        let mut completed = 0;
        while completed < count {
            if !self.is_healthy() { panic!("GPU_DEVICE_RETIRED"); }
            { let _span = failure_trace::Span::new(failure_trace::Step::Poll);
            self.device.poll(wgpu::Maintain::Poll); }
            match rx.try_recv() {
                Ok(Ok(())) => { completed += 1; }
                Ok(Err(_)) | Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    self.completion_uncertain.store(true, std::sync::atomic::Ordering::Release);
                    self.healthy.store(false, std::sync::atomic::Ordering::Release);
                    failure_trace::fail(failure_trace::Kind::MapFailed);
                    panic!("GPU_MAP_FAILED");
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => {
                    if std::time::Instant::now() >= deadline {
                        self.completion_uncertain.store(true, std::sync::atomic::Ordering::Release);
                        self.healthy.store(false, std::sync::atomic::Ordering::Release);
                        failure_trace::fail(failure_trace::Kind::MapTimeout);
                        panic!("GPU_MAP_TIMEOUT");
                    }
                    std::thread::sleep(std::time::Duration::from_millis(2));
                }
            }
        }
    }

    /// Get or compile + cache a pipeline keyed by shader source pointer.
    /// All shader sources come from `include_str!` so the pointer is stable.
    fn get_or_compile_pipeline<F>(
        &self,
        shader_source: &'static str,
        layout_entries_fn: F,
    ) -> std::sync::MutexGuard<'_, std::collections::HashMap<usize, CachedPipeline>>
    where
        F: FnOnce() -> Vec<wgpu::BindGroupLayoutEntry>,
    {
        let key = shader_source.as_ptr() as usize;
        let mut cache = self.pipeline_cache.lock().unwrap();
        if !cache.contains_key(&key) {
            let shader = self
                .device
                .create_shader_module(wgpu::ShaderModuleDescriptor {
                    label: Some("compute_shader"),
                    source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(shader_source)),
                });
            let entries = layout_entries_fn();
            let bind_group_layout =
                self.device
                    .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                        label: Some("compute_layout"),
                        entries: &entries,
                    });
            let pipeline_layout =
                self.device
                    .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                        label: Some("compute_pipeline_layout"),
                        bind_group_layouts: &[&bind_group_layout],
                        push_constant_ranges: &[],
                    });
            let pipeline = self
                .device
                .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some("compute_pipeline"),
                    layout: Some(&pipeline_layout),
                    module: &shader,
                    entry_point: Some("main"),
                    compilation_options: Default::default(),
                    cache: None,
                });
            cache.insert(
                key,
                CachedPipeline {
                    bind_group_layout,
                    pipeline,
                },
            );
        }
        cache
    }

    /// Generic GPU compute dispatch helper.
    ///
    /// `shader_source` must be a `'static str` (typically from `include_str!`) so
    /// the cache can key by pointer identity.
    fn dispatch_compute(
        &self,
        shader_source: &'static str,
        bindings: &[GpuBuffer],
        n_pixels: u32,
        output_idx: usize,
    ) -> Vec<f32> {
        let _execution = self.acquire_dispatch();
        #[cfg(feature = "resource-audit")]
        let _owner_device = crate::resource_audit::resident_owners::DeviceScope::begin_backend(self.audit_device_id, &self.audit_pending_writes);
        assert!(self.is_healthy(), "GPU_DEVICE_RETIRED");
        let t_start = std::time::Instant::now();
        let num_workgroups = point_dispatch_groups(u64::from(n_pixels), self.device.limits().max_compute_workgroups_per_dimension).expect("GPU_DISPATCH_LIMIT");
        assert!(bindings.iter().all(|b| b.data.len() as u64 <= self.device.limits().max_buffer_size && (!matches!(b.binding_type, wgpu::BufferBindingType::Storage { .. }) || b.data.len() as u64 <= self.max_storage_buffer_binding_size())), "GPU_BINDING_LIMIT");
        let layout_entries: Vec<wgpu::BindGroupLayoutEntry> = bindings
            .iter()
            .enumerate()
            .map(|(i, b)| wgpu::BindGroupLayoutEntry {
                binding: i as u32,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Buffer {
                    ty: b.binding_type,
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            })
            .collect();
        let entries_for_compile = layout_entries.clone();
        let cache = self.get_or_compile_pipeline(shader_source, || entries_for_compile);
        let key = shader_source.as_ptr() as usize;
        let cached = cache.get(&key).expect("pipeline just inserted");
        let pipeline = &cached.pipeline;
        let bind_group_layout = &cached.bind_group_layout;
        let t_compile = t_start.elapsed();

        #[cfg(feature = "resource-audit")]
        let mut owners = crate::resource_audit::observed_owners::DispatchOwners::begin(self.audit_device_id);

        // Create GPU buffers
        let gpu_buffers: Vec<AuditBuffer> = bindings
            .iter()
            .map(|b| {
                let buffer = self.device
                    .census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                        label: Some("buffer"),
                        contents: &b.data,
                        usage: b.usage,
                    });
                #[cfg(feature = "resource-audit")]
                owners.buffer(buffer.size());
                buffer
            })
            .collect();

        let bind_entries: Vec<AuditBindGroupEntry> = gpu_buffers
            .iter()
            .enumerate()
            .map(|(i, buf)| AuditBindGroupEntry {
                binding: i as u32,
                resource: buf.as_entire_binding(),
            })
            .collect();

        let bind_group = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("compute_bind_group"),
            layout: &bind_group_layout,
            entries: &bind_entries,
        });

        #[cfg(feature = "resource-audit")]
        owners.bind_group();

        // Readback buffer
        let output_size = bindings[output_idx].data.len() as u64;
        let readback = self.device.census_create_buffer(&wgpu::BufferDescriptor {
            label: Some("readback"),
            size: output_size,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        #[cfg(feature = "resource-audit")]
        owners.buffer(readback.size());

        // Dispatch
        // num_workgroups was checked before allocating bindings.

        let mut encoder = self.device.audit_create_command_encoder(&Default::default());
        #[cfg(feature = "resource-audit")]
        owners.encoder();
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("compute_pass"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&pipeline);
            pass.set_bind_group(0, &bind_group, &[]);
            pass.dispatch_workgroups(num_workgroups, 1, 1);
        }
        encoder.copy_buffer_to_buffer(&gpu_buffers[output_idx], 0, &readback, 0, output_size);
        let command = encoder.finish();
        #[cfg(feature = "resource-audit")]
        { owners.command(); owners.submitting(); }
        let mut submission = self.submit_tracked(command);

        // Read back
        let slice = readback.slice(..);
        let (tx, rx) = std::sync::mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r);
        });
        self.await_maps(&rx, 1);
        submission.complete();
        #[cfg(feature = "resource-audit")]
        owners.completed();

        let data = slice.get_mapped_range();
        let result: Vec<f32> = bytemuck::cast_slice(&data).to_vec();
        drop(data);
        readback.unmap();
        let _t_total = t_start.elapsed();
        let _ = t_compile;
        result
    }

    /// GPU separable Gaussian blur via two FIR passes (horizontal then vertical).
    /// Kernel weights are computed on CPU and uploaded as a storage buffer.
    /// Two ping-pong image buffers minimize allocations.
    pub fn gaussian_blur_gpu(&self, img: &ImageBuf, sigma: f32) -> ImageBuf {
        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            crate::resource_audit::checkpoint("gaussian_blur_enter");
            crate::resource_audit::observe_host("blur_source_scalar",
                crate::resource_audit::HostObservationKind::VecCapacity,
                img.data.capacity() as u64 * std::mem::size_of::<spektrafilm_math::precision::Scalar>() as u64);
        }
        // RESOURCE_AUDIT_END
        let _execution = self.acquire_dispatch();
        #[cfg(feature = "resource-audit")]
        let _owner_device = crate::resource_audit::resident_owners::DeviceScope::begin_backend(self.audit_device_id, &self.audit_pending_writes);
        assert!(self.is_healthy(), "GPU_DEVICE_RETIRED");
        assert!(img.width > 0 && img.height > 0 && img.pixel_count() as u64 * 12 <= self.max_storage_buffer_binding_size(), "GPU_BINDING_LIMIT");
        let radius = fir_blur_radius(sigma);
        let kernel_size = (2 * radius + 1) as usize;

        // Pre-compute normalized Gaussian kernel on CPU.
        let sigma_f64 = sigma as f64;
        let two_sigma_sq = 2.0 * sigma_f64 * sigma_f64;
        let mut kernel = Vec::with_capacity(kernel_size);
        let r_i32 = radius as i32;
        for i in 0..kernel_size {
            let x = (i as i32 - r_i32) as f64;
            kernel.push((-x * x / two_sigma_sq).exp());
        }
        let sum: f64 = kernel.iter().sum();
        let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / sum) as f32).collect();

        let w = img.width;
        let h = img.height;
        let n_pixels = (w as usize) * (h as usize);
        let img_bytes = n_pixels * 3 * 4;

        let make_buf = |label: &str| {
            self.device.census_create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size: img_bytes as u64,
                usage: wgpu::BufferUsages::STORAGE
                    | wgpu::BufferUsages::COPY_DST
                    | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            })
        };
        let buf_in = make_buf("blur_in");
        let buf_mid = make_buf("blur_mid");
        let buf_out = make_buf("blur_out");

        let input_f32 = scalars_to_f32(&img.data);
        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            match &input_f32 {
                Cow::Owned(values) => crate::resource_audit::observe_host("blur_upload_f32",
                    crate::resource_audit::HostObservationKind::VecCapacity, values.capacity() as u64 * 4),
                Cow::Borrowed(values) => crate::resource_audit::observe_host("blur_upload_borrowed",
                    crate::resource_audit::HostObservationKind::BorrowedPayload, values.len() as u64 * 4),
            }
        }
        // RESOURCE_AUDIT_END
        self.queue
            .census_write_buffer(&buf_in, 0, bytemuck::cast_slice(&input_f32));

        let kernel_buf = self
            .device
            .census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("gaussian_kernel"),
                contents: bytemuck::cast_slice(&kernel_f32),
                usage: wgpu::BufferUsages::STORAGE,
            });

        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            width: u32,
            height: u32,
            radius: u32,
            _pad: u32,
        }
        let params = Params {
            width: w,
            height: h,
            radius,
            _pad: 0,
        };
        let params_buf = self
            .device
            .census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("gaussian_params"),
                contents: bytemuck::bytes_of(&params),
                usage: wgpu::BufferUsages::UNIFORM,
            });

        let layout = &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ];
        let h_pipe = self.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
            layout,
        );
        let v_pipe = self.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
            layout,
        );

        let bg_h = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("bg_blur_h"),
            layout: &h_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_in.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: kernel_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: buf_mid.as_entire_binding(),
                },
            ],
        });
        let bg_v = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("bg_blur_v"),
            layout: &v_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_mid.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: kernel_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: buf_out.as_entire_binding(),
                },
            ],
        });

        let readback = self.device.census_create_buffer(&wgpu::BufferDescriptor {
            label: Some("blur_readback"),
            size: img_bytes as u64,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let wg_x = w.div_ceil(16);
        let wg_y = h.div_ceil(16);
        let mut encoder = self.device.audit_create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("blur_h"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&h_pipe.pipeline);
            pass.set_bind_group(0, &bg_h, &[]);
            pass.dispatch_workgroups(wg_x, wg_y, 1);
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("blur_v"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&v_pipe.pipeline);
            pass.set_bind_group(0, &bg_v, &[]);
            pass.dispatch_workgroups(wg_x, wg_y, 1);
        }
        encoder.copy_buffer_to_buffer(&buf_out, 0, &readback, 0, img_bytes as u64);
        let mut submission = self.submit_tracked(encoder.finish());

        let slice = readback.slice(..);
        let (tx, rx) = std::sync::mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r);
        });
        self.await_maps(&rx, 1);
        submission.complete();
        let data = slice.get_mapped_range();
        let out_f32: Vec<f32> = bytemuck::cast_slice(&data).to_vec();
        drop(data);
        readback.unmap();

        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            crate::resource_audit::checkpoint("gaussian_blur_readback");
        }
        // RESOURCE_AUDIT_END
        ImageBuf::from_data(w, h, f32_to_scalars(out_f32))
    }

    /// Blur `img` with every sigma in `sigmas`, all within a single command
    /// buffer. One upload, N pairs of H/V dispatches, one submit, one
    /// readback that fans out into N output `ImageBuf`s.
    ///
    /// Used by halation (multi-bounce blurs of the same source) and any
    /// caller that needs the same input at several radii.
    pub fn gaussian_blur_multi_gpu(&self, img: &ImageBuf, sigmas: &[f32]) -> Vec<ImageBuf> {
        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            crate::resource_audit::checkpoint("gaussian_multi_enter");
            crate::resource_audit::observe_host("blur_source_scalar",
                crate::resource_audit::HostObservationKind::VecCapacity,
                img.data.capacity() as u64 * std::mem::size_of::<spektrafilm_math::precision::Scalar>() as u64);
        }
        // RESOURCE_AUDIT_END
        let _execution = self.acquire_dispatch();
        #[cfg(feature = "resource-audit")]
        let _owner_device = crate::resource_audit::resident_owners::DeviceScope::begin_backend(self.audit_device_id, &self.audit_pending_writes);
        assert!(!sigmas.is_empty(), "gaussian_blur_multi_gpu: empty sigmas");

        let w = img.width;
        let h = img.height;
        let n_pixels = (w as usize) * (h as usize);
        let img_bytes = n_pixels * 3 * 4;

        let make_buf = |label: &str| {
            self.device.census_create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size: img_bytes as u64,
                usage: wgpu::BufferUsages::STORAGE
                    | wgpu::BufferUsages::COPY_DST
                    | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            })
        };

        // Input is uploaded once. Mid is shared between the H and V passes
        // of each sigma — wgpu inserts a barrier between compute passes so
        // pass N's H write of `mid` waits on pass N-1's V read.
        let buf_in = make_buf("blur_multi_in");
        let buf_mid = make_buf("blur_multi_mid");

        let input_f32 = scalars_to_f32(&img.data);
        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            match &input_f32 {
                Cow::Owned(values) => crate::resource_audit::observe_host("blur_upload_f32",
                    crate::resource_audit::HostObservationKind::VecCapacity, values.capacity() as u64 * 4),
                Cow::Borrowed(values) => crate::resource_audit::observe_host("blur_upload_borrowed",
                    crate::resource_audit::HostObservationKind::BorrowedPayload, values.len() as u64 * 4),
            }
        }
        // RESOURCE_AUDIT_END
        self.queue
            .census_write_buffer(&buf_in, 0, bytemuck::cast_slice(&input_f32));

        // One output buffer per sigma.
        let bufs_out: Vec<_> = (0..sigmas.len())
            .map(|i| make_buf(&format!("blur_multi_out_{i}")))
            .collect();

        // Pipelines (cached across calls).
        let layout = &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ];
        let h_pipe = self.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
            layout,
        );
        let v_pipe = self.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
            layout,
        );

        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            width: u32,
            height: u32,
            radius: u32,
            _pad: u32,
        }

        // Pre-build per-sigma kernel/params/bind groups. The struct owns
        // the kernel/params buffers so they outlive the encoder.
        #[allow(dead_code)]
        struct PerSigma {
            params_buf: AuditBuffer,
            kernel_buf: AuditBuffer,
            bg_h: AuditBindGroup,
            bg_v: AuditBindGroup,
        }

        let per_sigma: Vec<PerSigma> = sigmas
            .iter()
            .enumerate()
            .map(|(i, &sigma)| {
                let radius = fir_blur_radius(sigma);
                let kernel_size = (2 * radius + 1) as usize;
                let sigma_f64 = sigma as f64;
                let two_sigma_sq = 2.0 * sigma_f64 * sigma_f64;
                let r_i32 = radius as i32;
                let mut kernel = Vec::with_capacity(kernel_size);
                for k in 0..kernel_size {
                    let x = (k as i32 - r_i32) as f64;
                    kernel.push((-x * x / two_sigma_sq).exp());
                }
                let sum: f64 = kernel.iter().sum();
                let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / sum) as f32).collect();

                let kernel_buf =
                    self.device
                        .census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                            label: Some(&format!("blur_multi_kernel_{i}")),
                            contents: bytemuck::cast_slice(&kernel_f32),
                            usage: wgpu::BufferUsages::STORAGE,
                        });

                let params = Params {
                    width: w,
                    height: h,
                    radius,
                    _pad: 0,
                };
                let params_buf =
                    self.device
                        .census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                            label: Some(&format!("blur_multi_params_{i}")),
                            contents: bytemuck::bytes_of(&params),
                            usage: wgpu::BufferUsages::UNIFORM,
                        });

                let bg_h = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
                    label: Some(&format!("bg_blur_multi_h_{i}")),
                    layout: &h_pipe.layout,
                    entries: &[
                        AuditBindGroupEntry {
                            binding: 0,
                            resource: params_buf.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 1,
                            resource: buf_in.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 2,
                            resource: kernel_buf.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 3,
                            resource: buf_mid.as_entire_binding(),
                        },
                    ],
                });
                let bg_v = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
                    label: Some(&format!("bg_blur_multi_v_{i}")),
                    layout: &v_pipe.layout,
                    entries: &[
                        AuditBindGroupEntry {
                            binding: 0,
                            resource: params_buf.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 1,
                            resource: buf_mid.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 2,
                            resource: kernel_buf.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 3,
                            resource: bufs_out[i].as_entire_binding(),
                        },
                    ],
                });

                PerSigma {
                    params_buf,
                    kernel_buf,
                    bg_h,
                    bg_v,
                }
            })
            .collect();

        // One readback buffer per sigma — wgpu caps a single buffer at
        // ~256 MB on Metal, so a 4×6 MP combined readback would overflow.
        // Per-sigma buffers stay well under the cap (72 MB at 6 MP).
        let readbacks: Vec<_> = (0..sigmas.len())
            .map(|i| {
                self.device.census_create_buffer(&wgpu::BufferDescriptor {
                    label: Some(&format!("blur_multi_readback_{i}")),
                    size: img_bytes as u64,
                    usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                    mapped_at_creation: false,
                })
            })
            .collect();

        let wg_x = w.div_ceil(16);
        let wg_y = h.div_ceil(16);
        let mut encoder = self.device.audit_create_command_encoder(&Default::default());
        for (i, ps) in per_sigma.iter().enumerate() {
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some(&format!("blur_multi_h_{i}")),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&h_pipe.pipeline);
                pass.set_bind_group(0, &ps.bg_h, &[]);
                pass.dispatch_workgroups(wg_x, wg_y, 1);
            }
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some(&format!("blur_multi_v_{i}")),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&v_pipe.pipeline);
                pass.set_bind_group(0, &ps.bg_v, &[]);
                pass.dispatch_workgroups(wg_x, wg_y, 1);
            }
            encoder.copy_buffer_to_buffer(&bufs_out[i], 0, &readbacks[i], 0, img_bytes as u64);
        }
        // `per_sigma` and `bufs_out` are alive until the function returns,
        // which is after `queue.submit()` — so all referenced buffers stay
        // valid for the encoded work.
        let mut submission = self.submit_tracked(encoder.finish());

        // Map every readback with a finite wait; late callbacks tolerate the
        // receiver being dropped after timeout.
        let (tx, rx) = std::sync::mpsc::channel();
        for rb in &readbacks {
            let tx = tx.clone();
            rb.slice(..).map_async(wgpu::MapMode::Read, move |r| { let _ = tx.send(r); });
        }
        drop(tx);
        self.await_maps(&rx, readbacks.len());
        submission.complete();

        let mut out_imgs = Vec::with_capacity(sigmas.len());
        for rb in &readbacks {
            let slice = rb.slice(..);
            let data = slice.get_mapped_range();
            let chunk: Vec<f32> = bytemuck::cast_slice(&data).to_vec();
            out_imgs.push(ImageBuf::from_data(w, h, f32_to_scalars(chunk)));
            drop(data);
            rb.unmap();
        }

        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            crate::resource_audit::checkpoint("gaussian_multi_complete");
        }
        // RESOURCE_AUDIT_END
        out_imgs
    }

    /// GPU-resident pipeline: runs the front pass (hanatos LUT lookup or
    /// mallett matmul), highlight boost, camera diffusion/lens blur,
    /// halation, density curves, DIR, grain, print spectral, enlarger
    /// diffusion, scan spectral, glare, gamut compression, scanner lens blur,
    /// and unsharp as a single command buffer with ping-pong image storage.
    /// Only one upload at the start and one readback at the end.
    pub fn run_film_chain(&self, p: &crate::FilmChainParams<'_>) -> ImageBuf {
        self.run_film_chain_controlled(p, &mut || true).expect("uncontrolled resident chain cancelled")
    }

    fn run_film_chain_controlled(&self, p: &crate::FilmChainParams<'_>,
        checkpoint: &mut dyn FnMut() -> bool) -> Option<ImageBuf> {
        self.run_film_chain_capture_controlled(p, None, checkpoint)
    }

    fn run_film_chain_capture_controlled(&self, p: &crate::FilmChainParams<'_>,
        capture: Option<&mut crate::FilmDensityCapture>,
        checkpoint: &mut dyn FnMut() -> bool) -> Option<ImageBuf> {
        self.run_film_chain_capture_controlled_impl::<false>(p, capture, checkpoint)
    }

    // The true specialization is a private test oracle for the old empty-fence
    // schedule. Production always skips it; shader work and arithmetic are identical.
    fn run_film_chain_capture_controlled_impl<const RETAIN_EMPTY_GLARE_FENCE: bool>(
        &self, p: &crate::FilmChainParams<'_>,
        mut capture: Option<&mut crate::FilmDensityCapture>,
        checkpoint: &mut dyn FnMut() -> bool) -> Option<ImageBuf> {
        if let Some(capture) = capture.as_deref_mut() { capture.result = None; }
        if let Some(media)=&p.neutral_media {
            if !media.valid() || p.scan_film!=media.paper_omitted {return None;}
            if media.film_omitted && ((p.grain.is_some() && media.grain_reference.is_none()) || p.dir_couplers.is_some() || p.halation.is_some()) {return None;}
        }
        let omitted_film=p.neutral_media.as_ref().is_some_and(|m|m.film_omitted);
        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            crate::resource_audit::checkpoint("resident_enter");
            match p.image {
                crate::ChainImage::F64(img) => crate::resource_audit::observe_host("resident_source_scalar",
                    crate::resource_audit::HostObservationKind::VecCapacity,
                    img.data.capacity() as u64 * std::mem::size_of::<spektrafilm_math::precision::Scalar>() as u64),
                crate::ChainImage::F32(values, _, _) => crate::resource_audit::observe_host("resident_source_f32_slice",
                    crate::resource_audit::HostObservationKind::BorrowedPayload, values.len() as u64 * 4),
            }
            if let Some(request) = capture.as_deref() {
                crate::resource_audit::observe_host("density_sample_requests",
                    crate::resource_audit::HostObservationKind::VecCapacity,
                    request.samples.capacity() as u64 * std::mem::size_of::<crate::FilmDensitySample>() as u64);
            }
        }
        // RESOURCE_AUDIT_END
        THREAD_RESIDENT_PROGRESS.with(|v| v.set(None));
        THREAD_RESIDENT_FAILURE.with(|v| v.set(None));
        if !checkpoint() { return None; }
        if p.start_at == crate::ChainStart::Rgb && p.highlight_boost.as_ref()
            .is_some_and(|h|h.headroom_ev>0. && h.boost_ev<0.) {return None;}
        // New physical PSFs retain their complete support. Reject impossible
        // kernels before allocating or dispatching, allowing the CPU fallback.
        if let Some(components) = p.halation.as_ref().and_then(|h| h.back_reflection.as_ref()) {
            if p.image.width() > i32::MAX as u32 / 2 || p.image.height() > i32::MAX as u32 / 2 { return None; }
            let mut bytes = 0u64;
            for component in components {
                if component.weight.iter().any(|v| !v.is_finite()) { return None; }
                for sigma in component.sigma_px {
                    let radius = crate::back_reflection_fir_radius(sigma)?;
                    bytes = bytes.checked_add((u64::from(radius) * 2 + 1) * 4)?;
                }
            }
            if bytes > self.max_storage_buffer_binding_size() || bytes > self.device.limits().max_buffer_size { return None; }
        }
        let mut execution = Some(self.acquire_dispatch());
        #[cfg(feature = "resource-audit")]
        let _owner_device = crate::resource_audit::resident_owners::DeviceScope::begin_backend(self.audit_device_id, &self.audit_pending_writes);
        assert!(self.is_healthy(), "GPU_DEVICE_RETIRED");
        assert!(p.image.width() > 0 && p.image.height() > 0, "GPU_INVALID_SHAPE");
        let pixels = u64::from(p.image.width()) * u64::from(p.image.height());
        assert!(pixels * 3 <= u64::from(u32::MAX) && pixels * 12 <= self.max_storage_buffer_binding_size() && point_dispatch_groups(pixels * if p.highlight_boost.is_some() { 3 } else { 1 }, self.device.limits().max_compute_workgroups_per_dimension).is_some(), "GPU_BINDING_LIMIT");
        let len = p.image.f32_data().map_or_else(|| p.image.f64_image().unwrap().data.len(), |data| data.len());
        assert_eq!(len as u64, pixels * 3, "GPU_INVALID_SHAPE");
        let t_start = std::time::Instant::now();
        // Pull all references into locals so the existing body below
        // doesn't need a rewrite — only the param sources change.
        //
        // Chain input: dims plus the f32 upload pixels. The export fast path
        // supplies f32 data directly so no n*24-byte f64 image is ever built;
        // the f64 path converts here (the converted copy is dropped right
        // after the upload below).
        struct ChainDims {
            width: u32,
            height: u32,
        }
        impl ChainDims {
            fn pixel_count(&self) -> usize {
                self.width as usize * self.height as usize
            }
        }
        let (image, input_f32) = match p.image {
            crate::ChainImage::F64(img) => (
                ChainDims { width: img.width, height: img.height },
                scalars_to_f32(&img.data),
            ),
            crate::ChainImage::F32(data, w, h) => (
                ChainDims { width: w, height: h },
                std::borrow::Cow::Borrowed(data),
            ),
        };
        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            if let Cow::Owned(values) = &input_f32 {
                crate::resource_audit::observe_host("resident_upload_f32",
                    crate::resource_audit::HostObservationKind::VecCapacity, values.capacity() as u64 * 4);
            }
        }
        // RESOURCE_AUDIT_END
        for field in [p.diffusion_scattered.as_ref(), p.enlarger_scattered.as_ref()].into_iter().flatten() {
            assert!(field.valid_for(image.width, image.height), "GPU_INVALID_FIELD_CROP");
            // RESOURCE_AUDIT_BEGIN
            #[cfg(feature = "resource-audit")]
            {
                crate::resource_audit::observe_host("external_field_slice",
                    crate::resource_audit::HostObservationKind::BorrowedPayload, field.field.len() as u64 * 4);
            }
            // RESOURCE_AUDIT_END
            let crop = crate::DiffusionScatteredGpu::required_crop(field.small_w, field.small_h,
                field.d, field.band_x0, field.band_y0, image.width, image.height).unwrap();
            assert!(u64::from(crop[2]) * u64::from(crop[3]) * 12 <= self.max_storage_buffer_binding_size(), "GPU_FIELD_BINDING_LIMIT");
        }
        let coordinate_valid = |offset: u32, full_width: u32| {
            full_width >= image.width && offset % full_width <= full_width - image.width
                && u64::from(offset) + u64::from(image.height - 1) * u64::from(full_width)
                    + u64::from(image.width - 1) <= u64::from(u32::MAX)
        };
        assert!(p.grain.as_ref().is_none_or(|g| coordinate_valid(g.pixel_offset, g.full_width))
            && p.glare.as_ref().is_none_or(|g| coordinate_valid(g.pixel_offset, g.full_width)), "GPU_INVALID_TILE_COORDINATES");
        assert!(input_f32.iter().all(|v| v.is_finite()), "GPU_INVALID_INPUT");
        let film_log_exposure = p.film_log_exposure;
        let film_density_curves_normalized = p.film_density_curves_normalized;
        let film_gamma = p.film_gamma;
        let film_channel_density = p.film_channel_density;
        let film_base_density = p.film_base_density;
        let print_illuminant = p.print_illuminant;
        let print_sensitivity = p.print_sensitivity;
        let print_normalization_factor = p.print_normalization_factor;
        let print_log_exposure = p.print_log_exposure;
        let print_density_curves = p.print_density_curves;
        let print_gamma = p.print_gamma;
        let preflash = p.preflash;
        let print_channel_density = p.print_channel_density;
        let print_base_density = p.print_base_density;
        let viewing_illuminant = p.viewing_illuminant;
        let scan_normalization = p.scan_normalization;
        let scan_xyz_to_rgb = p.scan_xyz_to_rgb;

        let n_pixels = image.pixel_count() as u32;
        let img_bytes = n_pixels as usize * 3 * 4;
        // Reserve a tail in the one required output mapping. Reject unsupported
        // capture requests before allocation; the main export remains valid.
        let capture_bytes = capture.as_deref().filter(|request|
            p.stop_after == crate::ChainStop::Full && request.valid_for(image.width, image.height))
            .and_then(|request| {
                let bytes = (request.samples.len() as u64).checked_mul(12)?;
                let total = (img_bytes as u64).checked_add(bytes)?;
                let limits = self.device.limits();
                // Conservative for both paths: direct-map buf_b is also bound
                // as storage, while ordinary readback is COPY_DST | MAP_READ.
                (total <= limits.max_buffer_size && total <= self.max_storage_buffer_binding_size()
                    && (request.samples.len() as u32).div_ceil(64) <= limits.max_compute_workgroups_per_dimension)
                    .then_some(bytes)
            }).unwrap_or(0);

        // Chain stop mode (banded diffusion pre-pass taps the chain mid-way).
        // `past_front` is false only for ChainStop::Front, which returns the
        // raw film exposure right after the front pass + highlight boost;
        // `past_print` is false except for ChainStop::Full, skipping the
        // stages downstream of the enlarger-diffusion point.
        let stop = p.stop_after;
        let runs_front = p.start_at == crate::ChainStart::Rgb;
        assert!(runs_front || stop == crate::ChainStop::Full || (p.start_at == crate::ChainStart::FilmLog && stop == crate::ChainStop::CorrectedDensity), "GPU_INVALID_CHAIN_RANGE");
        let past_front = stop != crate::ChainStop::Front;
        let runs_density = past_front && stop != crate::ChainStop::FilmLog
            && p.start_at != crate::ChainStart::CorrectedDensity;
        let runs_finish = past_front && !matches!(stop, crate::ChainStop::FilmLog | crate::ChainStop::CorrectedDensity);
        let past_print = stop == crate::ChainStop::Full;
        let neutral_grain_reference = p.neutral_media.as_ref().and_then(|m| m.grain_reference);
        if runs_finish && neutral_grain_reference.is_some() && p.grain.is_none() { return None; }

        // Ping-pong image buffers (each holds H*W*3 f32 values).
        let make_img_buf = |label: &str| {
            self.device.census_create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size: img_bytes as u64,
                usage: wgpu::BufferUsages::STORAGE
                    | wgpu::BufferUsages::COPY_DST
                    | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            })
        };
        let mappable = self
            .device
            .features()
            .contains(wgpu::Features::MAPPABLE_PRIMARY_BUFFERS);

        // buf_a is the input/ping-pong buffer. The dominant per-frame cost was
        // the input upload: `queue.write_buffer` stages CPU→GPU through a slow
        // (~0.5 GB/s) blit on this unified-memory GPU. With
        // MAPPABLE_PRIMARY_BUFFERS we create buf_a already mapped (MAP_WRITE)
        // and memcpy the input straight into its (shared) memory — no staging.
        let buf_a = if mappable {
            let buf = self.device.census_create_buffer(&wgpu::BufferDescriptor {
                label: Some("img_a"),
                size: img_bytes as u64,
                usage: wgpu::BufferUsages::STORAGE
                    | wgpu::BufferUsages::COPY_DST
                    | wgpu::BufferUsages::COPY_SRC
                    | wgpu::BufferUsages::MAP_WRITE,
                mapped_at_creation: true,
            });
            buf.slice(..)
                .get_mapped_range_mut()
                .copy_from_slice(bytemuck::cast_slice(&input_f32));
            buf.unmap();
            buf
        } else {
            let buf = make_img_buf("img_a");
            self.queue
                .census_write_buffer(&buf, 0, bytemuck::cast_slice(&input_f32));
            buf
        };
        // The upload is complete: release the CPU-side input copy now (f64
        // mode converted an n*12-byte staging Vec) instead of holding it for
        // the whole chain. A borrowed f32 export input is unaffected.
        drop(input_f32);

        // buf_b holds the final RGB. With MAPPABLE_PRIMARY_BUFFERS we add
        // MAP_READ so it can be mapped directly for a zero-copy readback,
        // skipping the slow Private→Shared blit on unified memory.
        let buf_b = {
            let mut usage = wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC;
            if mappable {
                usage |= wgpu::BufferUsages::MAP_READ;
            }
            self.device.census_create_buffer(&wgpu::BufferDescriptor {
                label: Some("img_b"),
                size: img_bytes as u64 + if mappable { capture_bytes } else { 0 },
                usage,
                mapped_at_creation: false,
            })
        };

        // Static (LUT) buffers — uploaded once.
        let mk_storage = |label: &str, bytes: &[u8]| {
            self.device
                .census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                    label: Some(label),
                    contents: bytes,
                    usage: wgpu::BufferUsages::STORAGE,
                })
        };
        let mk_uniform = |label: &str, bytes: &[u8]| {
            self.device
                .census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                    label: Some(label),
                    contents: bytes,
                    usage: wgpu::BufferUsages::UNIFORM,
                })
        };

        // ── Pre-compute every static GPU-side buffer ─────────────────────
        // Filming density curves — already normalized by caller.
        let film_log_exp_f32: Vec<f32> = film_log_exposure.iter().map(|&v| v as f32).collect();
        let film_curves_f32: Vec<f32> = film_density_curves_normalized
            .iter()
            .flat_map(|r| r.iter().map(|&v| if v.is_nan() { 0.0 } else { v as f32 }))
            .collect();
        let film_log_exp_buf = mk_storage("film_log_exp", bytemuck::cast_slice(&film_log_exp_f32));
        let film_curves_buf = mk_storage("film_curves", bytemuck::cast_slice(&film_curves_f32));

        // Film spectral data (for printing pass).
        let (film_cd_f32, mut film_bd_f32) = sanitize_spectral_inputs(
            film_channel_density,
            film_base_density,
            film_channel_density.len(),
        );
        film_bd_f32.resize(film_channel_density.len(), 0.0);
        let film_cd_buf = mk_storage("film_cd", bytemuck::cast_slice(&film_cd_f32));
        let film_bd_buf = mk_storage("film_bd", bytemuck::cast_slice(&film_bd_f32));

        let print_illu_f32: Vec<f32> = print_illuminant.iter().map(|&v| v as f32).collect();
        let print_sens_f32: Vec<f32> = print_sensitivity
            .iter()
            .flat_map(|r| r.iter().map(|&v| if v.is_nan() { 0.0 } else { v as f32 }))
            .collect();
        let print_illu_buf = mk_storage("print_illu", bytemuck::cast_slice(&print_illu_f32));
        let print_sens_buf = mk_storage("print_sens", bytemuck::cast_slice(&print_sens_f32));

        // Print density curves (RAW, no normalization for print path).
        let print_log_exp_f32: Vec<f32> = print_log_exposure.iter().map(|&v| v as f32).collect();
        let print_curves_f32: Vec<f32> = print_density_curves
            .iter()
            .flat_map(|r| r.iter().map(|&v| if v.is_nan() { 0.0 } else { v as f32 }))
            .collect();
        let print_log_exp_buf =
            mk_storage("print_log_exp", bytemuck::cast_slice(&print_log_exp_f32));
        let print_curves_buf = mk_storage("print_curves", bytemuck::cast_slice(&print_curves_f32));

        // Spectral dye-density data for the scanning pass. For scan_film we
        // scan the developed film directly, so feed the film's spectral
        // densities; otherwise the print's. (print_spectral uses
        // sensitivity, not these — this buffer feeds only scan_spectral.)
        let (scan_cd_src, scan_bd_src): (&[[f64; 3]], &[f64]) = if p.scan_film {
            (film_channel_density, film_base_density)
        } else {
            (print_channel_density, print_base_density)
        };
        let (scan_cd_f32, mut scan_bd_f32) =
            sanitize_spectral_inputs(scan_cd_src, scan_bd_src, scan_cd_src.len());
        scan_bd_f32.resize(scan_cd_src.len(), 0.0);
        let scan_cd_buf = mk_storage("scan_cd", bytemuck::cast_slice(&scan_cd_f32));
        let scan_bd_buf = mk_storage("scan_bd", bytemuck::cast_slice(&scan_bd_f32));

        let view_illu_f32: Vec<f32> = viewing_illuminant.iter().map(|&v| v as f32).collect();
        let view_illu_buf = mk_storage("view_illu", bytemuck::cast_slice(&view_illu_f32));
        let cmf_x_buf = mk_storage(
            "cmf_x",
            bytemuck::cast_slice(&spektrafilm_math::spectral::CMF_X),
        );
        let cmf_y_buf = mk_storage(
            "cmf_y",
            bytemuck::cast_slice(&spektrafilm_math::spectral::CMF_Y),
        );
        let cmf_z_buf = mk_storage(
            "cmf_z",
            bytemuck::cast_slice(&spektrafilm_math::spectral::CMF_Z),
        );

        // ── Param structs ────────────────────────────────────────────────
        // Front pass (hanatos LUT lookup or mallett matmul). Both shaders
        // share this uniform layout; `lut_size` is hanatos-only (0 for
        // mallett, where the field is a pad).
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct FrontParams {
            width: u32,
            height: u32,
            lut_size: u32,
            _pad: u32,
            col0: [f32; 4],
            col1: [f32; 4],
            col2: [f32; 4],
        }
        let (m, front_lut_size) = match &p.front {
            crate::FrontPass::Hanatos2025 {
                tc_lut,
                rgb_to_adapted_xyz,
            } => (rgb_to_adapted_xyz, tc_lut.size as u32),
            crate::FrontPass::Mallett2019 { matrix } => (matrix, 0u32),
        };
        let front_params = FrontParams {
            width: image.width,
            height: image.height,
            lut_size: front_lut_size,
            _pad: 0,
            col0: [m[0][0] as f32, m[1][0] as f32, m[2][0] as f32, 0.0],
            col1: [m[0][1] as f32, m[1][1] as f32, m[2][1] as f32, 0.0],
            col2: [m[0][2] as f32, m[1][2] as f32, m[2][2] as f32, 0.0],
        };
        let front_params_buf = mk_uniform("front_params", bytemuck::bytes_of(&front_params));

        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct DensityParams {
            width: u32,
            height: u32,
            k: u32,
            uniform_grid: u32,
            gamma_inv: [f32; 3],
            _pad: f32,
        }
        let film_density_params = DensityParams {
            width: image.width,
            height: image.height,
            k: film_log_exposure.len() as u32,
            uniform_grid: if is_uniform(film_log_exposure) { 1 } else { 0 },
            gamma_inv: [(1.0 / film_gamma) as f32; 3],
            _pad: 0.0,
        };
        let film_density_params_buf =
            mk_uniform("film_dens_params", bytemuck::bytes_of(&film_density_params));

        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct PrintParams {
            width: u32,
            height: u32,
            n_wavelengths: u32,
            normalization_factor: f32,
            preflash: [f32; 3],
            _pad: f32,
            media: [f32; 4], // x=omitted film, y=fixed paper gray log exposure
            paper_gain: [f32; 4],
        }
        // The enlarger sequence (log→linear, diffusion mix, linear→log) runs
        // when an enlarger diffusion is planned (whole-frame chain), when a
        // precomputed scattered field is mixed in (banded chain), or when the
        // chain stops at the enlarger input (PrintLinear pre-pass tap).
        let has_enlarger_diffusion = runs_finish && (p.enlarger_diffusion.is_some()
            || p.enlarger_scattered.is_some()
            || stop == crate::ChainStop::PrintLinear);
        // The digital bridge has already applied print exposure before adding
        // preflash. Do not multiply that preflash again in the diffusion bridge.
        let print_exposure_scale = if omitted_film {1.0}else{p.print_exposure_scale as f32};
        let print_norm_for_shader = if omitted_film {p.print_exposure_scale as f32}
        else if has_enlarger_diffusion && print_exposure_scale != 0.0 {
            (print_normalization_factor / p.print_exposure_scale) as f32
        } else {
            print_normalization_factor as f32
        };
        let print_params = PrintParams {
            width: image.width,
            height: image.height,
            n_wavelengths: film_channel_density.len() as u32,
            normalization_factor: print_norm_for_shader,
            preflash: [preflash[0] as f32, preflash[1] as f32, preflash[2] as f32],
            _pad: 0.0,
            media: p.neutral_media.as_ref().filter(|m|m.film_omitted).map_or([0.;4],|m|[1.,m.paper_gray_log as f32,m.paper_log_min as f32,m.paper_log_max as f32]),
            paper_gain: p.neutral_media.as_ref().map_or([1.,1.,1.,0.],|m|[m.paper_gain[0] as f32,m.paper_gain[1] as f32,m.paper_gain[2] as f32,0.]),
        };
        let print_params_buf = mk_uniform("print_params", bytemuck::bytes_of(&print_params));

        let print_density_params = DensityParams {
            width: image.width,
            height: image.height,
            k: print_log_exposure.len() as u32,
            uniform_grid: if is_uniform(print_log_exposure) { 1 } else { 0 },
            gamma_inv: [(1.0 / print_gamma) as f32; 3],
            _pad: 0.0,
        };
        let print_density_params_buf = mk_uniform(
            "print_dens_params",
            bytemuck::bytes_of(&print_density_params),
        );

        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct ScanParams {
            width: u32,
            height: u32,
            n_wavelengths: u32,
            normalization: f32,
            col0: [f32; 4],
            col1: [f32; 4],
            col2: [f32; 4],
            bw: [f32; 4],
            media: [f32; 4], // x: 0 spectral, 1 logRGB, 2 negative density
            negative_min: [f32; 4],
            negative_range: [f32; 4],
            negative_gamma: [f32; 4],
        }
        let s = scan_xyz_to_rgb;
        // B&W/slide luminance remap (m, q); z=1 enables it in the shader.
        // w=1 skips the shader's [0,1] clamp — required when the gamut
        // compression pass follows (it needs the out-of-gamut values), and
        // also when gamut is deferred to the CPU on the export path.
        // Preserve out-of-range light through glare/optics even when gamut
        // compression is disabled. The final post-scan stage owns clipping.
        let skip_clamp = 1.0;
        let bw = match p.bw_xyz_remap {
            Some((m, q)) => [m as f32, q as f32, 1.0, skip_clamp],
            None => [1.0, 0.0, 0.0, skip_clamp],
        };
        let scan_params = ScanParams {
            width: image.width,
            height: image.height,
            n_wavelengths: scan_cd_src.len() as u32,
            normalization: scan_normalization as f32,
            col0: [s[0][0] as f32, s[1][0] as f32, s[2][0] as f32, 0.0],
            col1: [s[0][1] as f32, s[1][1] as f32, s[2][1] as f32, 0.0],
            col2: [s[0][2] as f32, s[1][2] as f32, s[2][2] as f32, 0.0],
            bw,
            media: p.neutral_media.as_ref().map_or([0.;4],|m|[if m.film_omitted&&m.paper_omitted {1.}else if m.negative_readout {2.}else{0.},0.,0.,0.]),
            negative_min: p.neutral_media.as_ref().map_or([0.;4],|m|[m.negative_min[0] as f32,m.negative_min[1] as f32,m.negative_min[2] as f32,0.]),
            negative_range: p.neutral_media.as_ref().map_or([1.;4],|m|[m.negative_range[0] as f32,m.negative_range[1] as f32,m.negative_range[2] as f32,1.]),
            negative_gamma: p.neutral_media.as_ref().map_or([1.;4],|m|[m.negative_gamma[0] as f32,m.negative_gamma[1] as f32,m.negative_gamma[2] as f32,1.]),
        };
        let scan_params_buf = mk_uniform("scan_params", bytemuck::bytes_of(&scan_params));

        // ── Pre-compile pipelines (cached after first call) ──────────────
        // Each shader's bindings layout is fixed and known here.
        let density_pipe = self.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/density_curve_interp.wgsl"),
            &[
                wgpu::BufferBindingType::Uniform,
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: false },
            ],
        );
        let print_pipe = self.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/print_spectral.wgsl"),
            &[
                wgpu::BufferBindingType::Uniform,
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: false },
            ],
        );
        let scan_pipe = self.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/scan_spectral.wgsl"),
            &[
                wgpu::BufferBindingType::Uniform,
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: false },
            ],
        );

        // ── Build bind groups (per dispatch, but no buffer creation) ─────
        let workgroup_size = POINT_WORKGROUP_SIZE;

        // Front pass: hanatos (params + rgb_in + tc_lut + raw_out) or mallett
        // (params + rgb_in + raw_out). The tc_lut buffer rides along in the
        // tuple to outlive the encoder on the hanatos arm.
        let front_state = if runs_front { Some(match &p.front {
            crate::FrontPass::Hanatos2025 { tc_lut, .. } => {
                let tc_lut_f32: Vec<f32> = tc_lut.data.iter().map(|&v| v as f32).collect();
                let tc_lut_buf = mk_storage("tc_lut", bytemuck::cast_slice(&tc_lut_f32));
                let pipe = self.cached_pipeline(
                    include_str!("../../spektrafilm-shaders/wgsl/hanatos2025_rgb_to_raw.wgsl"),
                    &[
                        wgpu::BufferBindingType::Uniform,
                        wgpu::BufferBindingType::Storage { read_only: true },
                        wgpu::BufferBindingType::Storage { read_only: true },
                        wgpu::BufferBindingType::Storage { read_only: false },
                    ],
                );
                let bg = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
                    label: Some("bg_hanatos"),
                    layout: &pipe.layout,
                    entries: &[
                        AuditBindGroupEntry {
                            binding: 0,
                            resource: front_params_buf.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 1,
                            resource: buf_a.as_entire_binding(),
                        }, // rgb_in
                        AuditBindGroupEntry {
                            binding: 2,
                            resource: tc_lut_buf.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 3,
                            resource: buf_b.as_entire_binding(),
                        }, // raw_out
                    ],
                });
                (pipe, bg, Some(tc_lut_buf))
            }
            crate::FrontPass::Mallett2019 { .. } => {
                let pipe = self.cached_pipeline(
                    include_str!("../../spektrafilm-shaders/wgsl/mallett_rgb_to_raw.wgsl"),
                    &[
                        wgpu::BufferBindingType::Uniform,
                        wgpu::BufferBindingType::Storage { read_only: true },
                        wgpu::BufferBindingType::Storage { read_only: false },
                    ],
                );
                let bg = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
                    label: Some("bg_mallett"),
                    layout: &pipe.layout,
                    entries: &[
                        AuditBindGroupEntry {
                            binding: 0,
                            resource: front_params_buf.as_entire_binding(),
                        },
                        AuditBindGroupEntry {
                            binding: 1,
                            resource: buf_a.as_entire_binding(),
                        }, // rgb_in
                        AuditBindGroupEntry {
                            binding: 2,
                            resource: buf_b.as_entire_binding(),
                        }, // raw_out
                    ],
                });
                (pipe, bg, None)
            }
        }) } else { None };
        // After the front pass: log10 + density curve interp into normalized
        // film curves. The front pass outputs raw (not log_raw), so a small
        // log10 shader transforms buf_b in-place, then density_curve_interp
        // reads buf_b → buf_a.

        let bg_log10 = {
            let pipe = self.cached_pipeline(
                include_str!("../../spektrafilm-shaders/wgsl/log10_inplace.wgsl"),
                &[
                    wgpu::BufferBindingType::Uniform,
                    wgpu::BufferBindingType::Storage { read_only: false },
                ],
            );
            #[repr(C)]
            #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
            struct Log10Params {
                // WGSL struct: `n: u32 + _pad: vec3<u32>`. vec3 has 16-byte alignment,
                // so the struct is 32 bytes total. We pad on the Rust side accordingly.
                n_pixels: u32,
                _pad: [u32; 7],
            }
            let log10_params_buf = mk_uniform(
                "log10_params",
                bytemuck::bytes_of(&Log10Params {
                    n_pixels,
                    _pad: [0; 7],
                }),
            );
            let bg = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
                label: Some("bg_log10"),
                layout: &pipe.layout,
                entries: &[
                    AuditBindGroupEntry {
                        binding: 0,
                        resource: log10_params_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 1,
                        resource: buf_b.as_entire_binding(),
                    },
                ],
            });
            // Return both the buffer (to keep it alive) and the bind group.
            (pipe, bg, log10_params_buf)
        };

        let bg_density_film = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("bg_density_film"),
            layout: &density_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: film_density_params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_b.as_entire_binding(),
                }, // log_raw
                AuditBindGroupEntry {
                    binding: 2,
                    resource: film_log_exp_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: film_curves_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 4,
                    resource: buf_a.as_entire_binding(),
                }, // density_cmy
            ],
        });
        let bg_print = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("bg_print"),
            layout: &print_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: print_params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_a.as_entire_binding(),
                }, // density_cmy
                AuditBindGroupEntry {
                    binding: 2,
                    resource: film_cd_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: film_bd_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 4,
                    resource: print_illu_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 5,
                    resource: print_sens_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 6,
                    resource: buf_b.as_entire_binding(),
                }, // log_raw_print
            ],
        });
        let bg_density_print = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("bg_density_print"),
            layout: &density_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: print_density_params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_b.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: print_log_exp_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: print_curves_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 4,
                    resource: buf_a.as_entire_binding(),
                },
            ],
        });
        let bg_scan = self.device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("bg_scan"),
            layout: &scan_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: scan_params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_a.as_entire_binding(),
                }, // density_print
                AuditBindGroupEntry {
                    binding: 2,
                    resource: scan_cd_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: scan_bd_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 4,
                    resource: view_illu_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 5,
                    resource: cmf_x_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 6,
                    resource: cmf_y_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 7,
                    resource: cmf_z_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 8,
                    resource: buf_b.as_entire_binding(),
                }, // final rgb
            ],
        });

        // Readback buffer (for the final image only) — only needed when we
        // can't map buf_b directly. Skipping it on the mappable path also
        // avoids allocating a second full-image buffer per frame.
        let make_phase_readback = || (!mappable).then(|| {
            self.device.census_create_buffer(&wgpu::BufferDescriptor {
                label: Some("readback"),
                size: img_bytes as u64 + capture_bytes,
                usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            })
        });
        let mut readback = if p.stage_batched { None } else { make_phase_readback() };

        // ── Halation auxiliary buffers + bind groups (only if active) ────
        // Preview allocates up-front; controlled chains allocate at first use. The two ping-pong
        // buffers `buf_a` / `buf_b` are reused as blur input/mid; the new
        // `buf_c` and `buf_d` hold the scatter outputs and the halation
        // accumulator. None of this is touched when `p.halation` is `None`.
        let make_phase_halation_state = || if runs_front && past_front {
            p.halation.as_ref().map(|hp| {
                build_halation_state(
                    &self.device,
                    hp,
                    image.width,
                    image.height,
                    &buf_a,
                    &buf_b,
                    self,
                )
            })
        } else {
            None
        };
        let mut halation_state = if p.stage_batched { None } else { make_phase_halation_state() };

        // ── Highlight boost state ────────────────────────────────────────
        // Runs on raw film exposure immediately after the front pass.
        let highlight_state = p.highlight_boost.as_ref().filter(|_| runs_front).map(|hp| {
            build_highlight_boost_state(&self.device, hp, n_pixels, &buf_b, self)
        });

        // ── Camera diffusion filter state ────────────────────────────────
        // Applied on the raw film exposure (buf_b) right after hanatos and
        // before halation, matching the CPU filming order. Owns its own
        // downsampled scratch buffers. Whole-frame plan form — the banded
        // chain mixes a precomputed scattered field instead (below), and the
        // pre-pass stop modes never build either.
        let make_phase_diffusion_state = || p.diffusion.as_ref().filter(|_| runs_front && past_front).map(|plan| {
            build_diffusion_state(&self.device, plan, image.width, image.height, &buf_b, self)
        });
        let mut diffusion_state = if p.stage_batched { None } else { make_phase_diffusion_state() };
        // Banded-chain camera diffusion: precomputed whole-frame scattered
        // field, upsampled+mixed into buf_b at the same chain point.
        let make_phase_cam_mix_state = || p.diffusion_scattered.as_ref().filter(|_| runs_front && past_front).map(|sc| {
            build_diffusion_mix_state(
                &self.device,
                &self.queue,
                sc,
                image.width,
                image.height,
                &buf_b,
                self,
            )
        });
        let mut cam_mix_state = if p.stage_batched { None } else { make_phase_cam_mix_state() };

        let make_phase_camera_lens_blur_state = || if runs_front && past_front {
            p.camera_lens_blur_px.and_then(|sigma| {
                (sigma > 0.0).then(|| {
                    build_simple_blur_state(
                        &self.device,
                        sigma,
                        image.width,
                        image.height,
                        &buf_b,
                        &buf_a,
                        "camera_lens",
                        self,
                    )
                })
            })
        } else {
            None
        };
        let mut camera_lens_blur_state = if p.stage_batched { None } else { make_phase_camera_lens_blur_state() };

        // ── Unsharp mask state ───────────────────────────────────────────
        // Last pass before readback. Blurs buf_b → buf_c (via buf_a mid),
        // then combines: buf_b_out = (1+amount)*buf_b - amount*buf_c.
        // Since the combine writes back to buf_b in-place we need to
        // route via a temporary buffer to satisfy wgpu aliasing rules.
        let unsharp_state = if past_print && !p.defer_gamut_to_cpu {
            p.unsharp.as_ref().map(|up| {
                build_unsharp_state(
                    &self.device,
                    up,
                    image.width,
                    image.height,
                    &buf_a,
                    &buf_b,
                    self,
                )
            })
        } else {
            None
        };

        // ── Output gamut compression state ───────────────────────────────
        // Single per-pixel dispatch in place on buf_b, after glare and
        // before unsharp (the CPU scanning order). The C_max table is
        // baked once at pipeline construction; only the upload happens here.
        // Skipped on the export path (`defer_gamut_to_cpu`) — the caller runs
        // the exact f64 CPU gamut compressor after readback instead.
        let gamut_state = if past_print && !p.defer_gamut_to_cpu {
            p.gamut
                .as_ref()
                .map(|gp| build_gamut_state(&self.device, gp, &buf_b, n_pixels, self))
        } else {
            None
        };

        // ── Glare state ──────────────────────────────────────────────────
        // Applied in place on buf_b (the scan_spectral output) just before
        // readback. Generates per-pixel lognormal noise into a scratch
        // buffer, optionally blurs it, then adds `g * rgb_offset[c]` to
        // the image. Uses buf_a (free after scan_spectral consumed
        // density_cmy) and one fresh scratch buffer.
        let glare_state = if past_print {
            p.glare.as_ref().map(|gp| {
                build_glare_state(
                    &self.device,
                    gp,
                    image.width,
                    image.height,
                    &buf_a,
                    &buf_b,
                    self,
                )
            })
        } else {
            None
        };

        let scanner_lens_blur_state = if past_print && !p.defer_gamut_to_cpu {
            p.scanner_lens_blur_px.and_then(|sigma| {
                (sigma > 0.0).then(|| {
                    build_simple_blur_state(
                        &self.device,
                        sigma,
                        image.width,
                        image.height,
                        &buf_b,
                        &buf_a,
                        "scanner_lens",
                        self,
                    )
                })
            })
        } else {
            None
        };

        // ── Grain state ──────────────────────────────────────────────────
        // Applied in place on buf_a (density_cmy) after DIR couplers,
        // before print_spectral. Optionally followed by a Gaussian
        // post-blur (uses buf_b as mid, since log_raw_corrected in buf_b
        // is no longer needed after DIR's final density_curve_0).
        let make_phase_grain_state = || if runs_finish {
            p.grain.as_ref().map(|gp| {
                build_grain_state(
                    &self.device,
                    gp,
                    image.width,
                    image.height,
                    &buf_a,
                    &buf_b,
                    self,
                )
            })
        } else {
            None
        };
        let mut grain_state = if p.stage_batched { None } else { make_phase_grain_state() };
        let make_phase_neutral_grain_state = || if runs_finish {
            neutral_grain_reference.as_ref().map(|reference| build_neutral_grain_state(
                &self.device, reference, image.width, image.height, &buf_a, self))
        } else { None };
        // Extra 12 B/pixel, included by caller admission; retire at the grain
        // fence together with all bind groups retaining the original copy.
        let mut neutral_grain_state = if p.stage_batched { None } else { make_phase_neutral_grain_state() };

        // ── DIR couplers state ────────────────────────────────────────────
        // Constructed at the DIR boundary in controlled chains. Reads buf_a
        // (density_cmy from film density curve) and buf_b (log_raw),
        // produces a corrected buf_a via re-interpolation of
        // `density_curves_0` against `log_raw - correction`. Uses three
        // owned scratch buffers (correction, mid, accumulator). buf_a is
        // reused as blur mid since density_cmy is no longer needed once
        // the matmul has consumed it (the final density_curve_interp
        // overwrites buf_a anyway).
        let make_phase_dir_state = || if runs_density {
            p.dir_couplers.as_ref().map(|dp| {
                build_dir_state(
                    &self.device,
                    dp,
                    image.width,
                    image.height,
                    &buf_a,
                    &buf_b,
                    self,
                )
            })
        } else {
            None
        };
        let mut dir_state = if p.stage_batched { None } else { make_phase_dir_state() };

        // The enlarger log→linear conversion is needed whenever the chain
        // reaches the enlarger-diffusion input point — including the
        // PrintLinear pre-pass tap, which stops right after it.
        let enlarger_log_to_linear_state = if has_enlarger_diffusion {
            Some(build_log_to_linear_state(&self.device, n_pixels, print_exposure_scale, &buf_b, self))
        } else {
            None
        };
        let make_phase_enlarger_diffusion_state = || p.enlarger_diffusion.as_ref().filter(|_| runs_finish).map(|plan| {
            build_diffusion_state(&self.device, plan, image.width, image.height, &buf_b, self)
        });
        let mut enlarger_diffusion_state = if p.stage_batched { None } else { make_phase_enlarger_diffusion_state() };
        // Banded-chain enlarger diffusion: precomputed whole-frame scattered
        // field, upsampled+mixed into buf_b between log→linear and
        // linear→log.
        let make_phase_enl_mix_state = || p.enlarger_scattered.as_ref().filter(|_| runs_finish).map(|sc| {
            build_diffusion_mix_state(
                &self.device,
                &self.queue,
                sc,
                image.width,
                image.height,
                &buf_b,
                self,
            )
        });
        let mut enl_mix_state = if p.stage_batched { None } else { make_phase_enl_mix_state() };
        // linear→log runs only when the chain continues past the enlarger
        // mix (the PrintLinear pre-pass stops before it).
        let enlarger_linear_to_log_state = if p.enlarger_diffusion.is_some()
            || p.enlarger_scattered.is_some()
        {
            Some(build_linear_to_log_state(&self.device, n_pixels, &buf_b, self))
        } else {
            None
        };
        // Skipped on the export path (`defer_gamut_to_cpu`) — the chain returns
        // unclamped linear RGB and the caller runs the f64 CPU gamut compressor
        // + post-scan encode after readback. Also skipped by the pre-pass
        // stop modes, which never reach the post-scan stage.
        let post_scan_state = if p.defer_gamut_to_cpu || !past_print {
            None
        } else {
            Some(build_post_scan_state(&self.device, n_pixels, p.output_cctf_encoding, &buf_b, self))
        };

        // Opt-in only, Full chains only. Caller reserves the explicit byte
        // bound without changing export geometry. Allocation/device failures
        // still follow the normal GPU failure path. No independent map exists.
        let density_capture = capture.as_deref().filter(|_| capture_bytes > 0)
            .and_then(|request| DensityCaptureState::new(self, request, image.width, &buf_a));

        // Small preview retains the single-submission fast path. Large
        // whole or rectangular exports fence bounded batches without reading images back.
        let fence = p.stage_batched.then(|| self.device.census_create_buffer(&wgpu::BufferDescriptor {
            label: Some("resident_stage_completion"), size: 4,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        }));
        let mut encoder = self.device.audit_create_command_encoder(&Default::default());
        let mut batch_wait_ms = 0.0f32;
        let mut completed_batches = 0u32;
        let mut finish_batch = |encoder: &mut AuditCommandEncoder, stage: ResidentStage| -> bool {
            let Some(fence) = fence.as_ref() else { return true; };
            // Scan already proved completion before retiring enlarger owners.
            // With no glare dispatch, preserve the unlocked cancellation checkpoint
            // below without an empty GPU submit/map or a fictitious progress stage.
            if stage != ResidentStage::Glare || glare_state.is_some() || RETAIN_EMPTY_GLARE_FENCE {
                encoder.copy_buffer_to_buffer(&buf_b, 0, fence, 0, 4);
                let submitted_encoder = std::mem::replace(encoder,
                    self.device.audit_create_command_encoder(&Default::default()));
                let batch_start = std::time::Instant::now();
                failure_trace::reset();
                let mut attempt = ResidentBatchAttempt { stage, start: batch_start, completed: false };
                let mut submission = self.submit_tracked(submitted_encoder.finish());
                let slice = fence.slice(..);
                let (tx, rx) = std::sync::mpsc::channel();
                slice.map_async(wgpu::MapMode::Read, move |r| { let _ = tx.send(r); });
                self.await_maps(&rx, 1);
                submission.complete();
                let elapsed = batch_start.elapsed().as_secs_f64() * 1000.0;
                batch_wait_ms += elapsed as f32;
                completed_batches += 1;
                attempt.completed = true;
                THREAD_RESIDENT_PROGRESS.with(|v| v.set(Some(ResidentBatchProgress {
                    stage, completed_batches, batch_elapsed_ms: elapsed, chain_gpu_wait_ms: batch_wait_ms as f64 })));
                fence.unmap();
                // RESOURCE_AUDIT_BEGIN
                #[cfg(feature = "resource-audit")]
                {
                    crate::resource_audit::checkpoint(stage.as_str());
                }
                // RESOURCE_AUDIT_END
                drop(submission);
            }
            // No submitted work remains. Never invoke client code under the
            // device mutex, including callbacks that wait for ExportPermit.
            drop(execution.take());
            if !checkpoint() { return false; }
            execution = Some(self.acquire_dispatch());
            true
        };
        let dispatch = |encoder: &mut AuditCommandEncoder,
                        pipe: &wgpu::ComputePipeline,
                        bg: &AuditBindGroup,
                        n: u32| {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: None,
                timestamp_writes: None,
            });
            pass.set_pipeline(pipe);
            pass.set_bind_group(0, bg, &[]);
            pass.dispatch_workgroups(n.div_ceil(workgroup_size), 1, 1);
        };

        // 1. Front pass (hanatos or mallett): buf_a (rgb in) → buf_b (raw)
        if let Some((front_pipe, bg_front, _front_tc_lut)) = front_state.as_ref() {
        dispatch(&mut encoder, &front_pipe.pipeline, bg_front, n_pixels);
        // 1a. Highlight boost on raw, before optical scatter.
        if let Some(hs) = highlight_state.as_ref() {
            hs.encode_passes(&mut encoder);
        }
        if past_front && !finish_batch(&mut encoder, ResidentStage::Front) { return None; }
        // ChainStop::Front taps the raw film exposure here (the
        // camera-diffusion input) — nothing later is encoded.
        if past_front {
        if p.stage_batched {
            diffusion_state = make_phase_diffusion_state();
            cam_mix_state = make_phase_cam_mix_state();
            camera_lens_blur_state = make_phase_camera_lens_blur_state();
        }
        // 1a. Camera diffusion filter in-place on buf_b (raw), before
        //     halation — mirrors the CPU filming order. The banded chain
        //     mixes a precomputed scattered field; the whole-frame chain
        //     runs the full downsample→blur→upsample→mix plan.
        if let Some(ms) = cam_mix_state.as_ref() {
            ms.encode_pass(&mut encoder);
        } else if let Some(ds) = diffusion_state.as_ref() {
            if !ds.encode_passes_batched(&mut encoder, &buf_b, &mut |enc| finish_batch(enc, ResidentStage::CameraOptics)) { return None; }
        }
        // 1b. Camera lens blur on raw, after camera diffusion and before halation.
        if let Some(bs) = camera_lens_blur_state.as_ref() {
            let wg_xy = (image.width.div_ceil(16), image.height.div_ceil(16));
            bs.encode_passes(&mut encoder, wg_xy, &buf_b);
        }
        if !finish_batch(&mut encoder, ResidentStage::CameraOptics) { return None; }
        // Only the OUTER fence covers the final diffusion mix and lens copy.
        // Retire whole owners, including their bind groups, never buffers alone.
        if p.stage_batched {
            drop(diffusion_state.take());
            drop(cam_mix_state.take());
            drop(camera_lens_blur_state.take());
            halation_state = make_phase_halation_state();
        }
        // 1b. Halation in-place on buf_b. Uses buf_a as blur scratch (the
        //     input RGB image is no longer needed), buf_c / buf_d for the
        //     scatter outputs and halation accumulator.
        if let Some(hs) = halation_state.as_ref() {
            let wg_xy = (image.width.div_ceil(16), image.height.div_ceil(16));
            if !hs.encode_passes_batched(&mut encoder, n_pixels, workgroup_size, wg_xy,
                &mut |enc| finish_batch(enc, ResidentStage::Halation)) { return None; }
        }
        // 2. log10 in-place on buf_b: raw → log_raw (3 channels per thread).
        let (log10_pipe, log10_bg, _keepalive) = &bg_log10;
        dispatch(&mut encoder, &log10_pipe.pipeline, log10_bg, n_pixels);
        }
        } else if p.start_at == crate::ChainStart::FilmLog {
            // FilmLog input is already logarithmic; preserve its exact f32 bits.
            encoder.copy_buffer_to_buffer(&buf_a, 0, &buf_b, 0, img_bytes as u64);
        }
        if runs_density {
        // 3. Density curve (film, normalized): buf_b (log_raw) → buf_a (density_cmy)
        if omitted_film {
            // No sensitometry, no interpolation and no implicit log-RGB clamp.
            encoder.copy_buffer_to_buffer(&buf_b,0,&buf_a,0,img_bytes as u64);
        } else {
        dispatch(
            &mut encoder,
            &density_pipe.pipeline,
            &bg_density_film,
            n_pixels,
        );
        }
        if !finish_batch(&mut encoder, ResidentStage::FilmDensity) { return None; }
        // This completed fence covers halation's final add/renormalize and
        // log10 too. FilmLog prefixes skip it and retain halation to readback.
        if p.stage_batched {
            drop(halation_state.take());
            dir_state = make_phase_dir_state();
        }
        // 3b. DIR couplers (operates on buf_a, mutates buf_b → log_raw_corrected,
        //     re-interps density curve back into buf_a).
        if let Some(ds) = dir_state.as_ref() {
            let wg_xy = (image.width.div_ceil(16), image.height.div_ceil(16));
            if !ds.encode_passes_batched(&mut encoder, n_pixels, workgroup_size, wg_xy,
                &mut |enc| finish_batch(enc, ResidentStage::Dir)) { return None; }
        }
        // Preserve the CPU stage-4 preview semantics: corrected density,
        // BEFORE grain and before print overwrites buf_a.
        if let Some(tap) = density_capture.as_ref() { tap.encode(&mut encoder); }
        if !finish_batch(&mut encoder, ResidentStage::Dir) { return None; }
        // Internal DIR fences precede its final vertical blur/subtract/density.
        // The independent density_capture owner must survive through readback.
        if p.stage_batched {
            drop(dir_state.take());
        }
        }
        // Corrected-density input already resides in buf_a. No density or DIR
        // operation is repeated, and grain keeps the original f32 boundary.
        if runs_finish {
        if p.stage_batched {
            grain_state = make_phase_grain_state();
            neutral_grain_state = make_phase_neutral_grain_state();
        }
        if let Some(ng) = neutral_grain_state.as_ref() { ng.encode(&mut encoder, false, n_pixels); }
        // 3c. Grain (in-place on buf_a, optional post-blur via buf_b mid).
        if let Some(gs) = grain_state.as_ref() {
            let wg_xy = (image.width.div_ceil(16), image.height.div_ceil(16));
            gs.encode_passes(&mut encoder, n_pixels, workgroup_size, wg_xy);
        }
        if let Some(ng) = neutral_grain_state.as_ref() { ng.encode(&mut encoder, true, n_pixels); }
        if !finish_batch(&mut encoder, ResidentStage::Grain) { return None; }
        if p.stage_batched { drop(grain_state.take()); drop(neutral_grain_state.take()); }
        // 4 + 5. Printing: print_spectral (buf_a → buf_b) then the print
        //     density curve (buf_b → buf_a). Skipped for scan_film — the
        //     scan pass consumes the film density already in buf_a.
        if !p.scan_film {
            // 4. Print spectral: buf_a → buf_b (log_raw_print)
            dispatch(&mut encoder, &print_pipe.pipeline, &bg_print, n_pixels);
            // Enlarger diffusion filter on linear print raw, between
            // print_spectral and the print density curves. The log→linear
            // conversion runs whenever the chain reaches this point —
            // including the PrintLinear pre-pass, which stops right after.
            if let Some(l2l) = enlarger_log_to_linear_state.as_ref() {
                l2l.encode_passes(&mut encoder, n_pixels);
            }
            if past_print && !finish_batch(&mut encoder, ResidentStage::Print) { return None; }
            // ChainStop::PrintLinear taps the linear print light here (the
            // enlarger-diffusion input) — nothing later is encoded.
            if past_print {
                if p.stage_batched {
                    enlarger_diffusion_state = make_phase_enlarger_diffusion_state();
                    enl_mix_state = make_phase_enl_mix_state();
                }
                // The banded chain mixes a precomputed scattered field; the
                // whole-frame chain runs the full diffusion plan.
                if let Some(ms) = enl_mix_state.as_ref() {
                    ms.encode_pass(&mut encoder);
                } else if let Some(ds) = enlarger_diffusion_state.as_ref() {
                    if !ds.encode_passes_batched(&mut encoder, &buf_b, &mut |enc| finish_batch(enc, ResidentStage::Print)) { return None; }
                }
                if let Some(l2g) = enlarger_linear_to_log_state.as_ref() {
                    l2g.encode_passes(&mut encoder, n_pixels);
                }
                // 5. Density curve (print, raw curves): buf_b → buf_a (density_print)
                dispatch(
                    &mut encoder,
                    &density_pipe.pipeline,
                    &bg_density_print,
                    n_pixels,
                );
            }
        }
        // ChainStop::Front / PrintLinear end here — the stages below are
        // only encoded for a full chain run.
        if past_print {
        // 6. Scan spectral: buf_a → buf_b (final rgb, clamped, NOT sRGB-encoded)
        dispatch(&mut encoder, &scan_pipe.pipeline, &bg_scan, n_pixels);
        if !finish_batch(&mut encoder, ResidentStage::Scan) { return None; }
        // Print's outer fence is BEFORE enlarger work. Scan is the first
        // existing outer boundary covering its final mix/log/density passes.
        if p.stage_batched {
            drop(enlarger_diffusion_state.take());
            drop(enl_mix_state.take());
        }
        // 6b. Glare (in place on buf_b).
        if let Some(gs) = glare_state.as_ref() {
            let wg_xy = (image.width.div_ceil(16), image.height.div_ceil(16));
            gs.encode_passes(&mut encoder, n_pixels, workgroup_size, wg_xy);
        }
        if !finish_batch(&mut encoder, ResidentStage::Glare) { return None; }
        // 6c. Output gamut compression (in place on buf_b) — after glare,
        //     before unsharp, mirroring the CPU scanning order.
        if let Some(gs) = gamut_state.as_ref() {
            gs.encode_passes(&mut encoder, n_pixels, workgroup_size);
        }
        // 6d. Scanner lens blur — after glare/gamut, before unsharp.
        if let Some(bs) = scanner_lens_blur_state.as_ref() {
            let wg_xy = (image.width.div_ceil(16), image.height.div_ceil(16));
            bs.encode_passes(&mut encoder, wg_xy, &buf_b);
        }
        // 6d. Unsharp mask — last in-flight pass. Writes the final image
        //     back to buf_b so the readback path below is unchanged.
        if let Some(us) = unsharp_state.as_ref() {
            let wg_xy = (image.width.div_ceil(16), image.height.div_ceil(16));
            us.encode_passes(
                &mut encoder,
                n_pixels,
                workgroup_size,
                wg_xy,
                &buf_b,
                img_bytes as u64,
            );
        }
        // 6e. Final clamp + optional sRGB encode. This mirrors
        // Pipeline::apply_post_scan so the resident WGSL path can return
        // display-ready pixels without a CPU full-frame post pass. Skipped on
        // the export path (gamut + encode deferred to the CPU).
        if let Some(ps) = post_scan_state.as_ref() {
            ps.encode_passes(&mut encoder, n_pixels);
        }
        }
        }

        if stop == crate::ChainStop::CorrectedDensity {
            encoder.copy_buffer_to_buffer(&buf_a, 0, &buf_b, 0, img_bytes as u64);
        }
        drop(finish_batch);
        // Delay the full-image map buffer until phase-local scratch has retired.
        // Unbatched preview retains its original allocation schedule.
        if p.stage_batched { readback = make_phase_readback(); }
        // Zero-copy path: when buf_b is mappable, skip the blit and map it
        // directly below. Otherwise stage it into the MAP_READ readback buffer.
        if let Some(rb) = readback.as_ref() {
            encoder.copy_buffer_to_buffer(&buf_b, 0, rb, 0, img_bytes as u64);
        }
        // All image compute/copy passes are finished before writing this
        // tail. No shader is dispatched after this copy. Existing shaders use
        // explicit pixel dimensions, never arrayLength; image copies likewise
        // use img_bytes. The original image prefix therefore remains unchanged.
        if let Some(tap) = density_capture.as_ref() {
            encoder.copy_buffer_to_buffer(&tap.gathered, 0,
                readback.as_ref().unwrap_or(&buf_b), img_bytes as u64, capture_bytes);
        }
        // Everything since function entry: CPU-side param prep, buffer
        // creation/uploads, and command encoding.
        let before_readback_ms = t_start.elapsed().as_secs_f32() * 1000.0;
        let cpu_setup_ms = before_readback_ms - batch_wait_ms;
        let final_stage = match p.stop_after { crate::ChainStop::CorrectedDensity=>ResidentStage::Dir, crate::ChainStop::Front=>ResidentStage::Front,
            crate::ChainStop::FilmLog=>ResidentStage::FilmLog,
            crate::ChainStop::PrintLinear=>ResidentStage::Print, crate::ChainStop::Full=>ResidentStage::Output };
        failure_trace::reset();
        let mut attempt = ResidentBatchAttempt { stage: final_stage, start: std::time::Instant::now(), completed: false };
        let mut submission = self.submit_tracked(encoder.finish());

        // Final full image sync after any completed intermediate fences. Map buf_b directly on the zero-copy
        // path, or the staging buffer otherwise.
        let map_target = readback.as_ref().unwrap_or(&buf_b);
        let slice = map_target.slice(..);
        let (tx, rx) = std::sync::mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r);
        });
        self.await_maps(&rx, 1);
        submission.complete();
        let gpu_wait_ms = batch_wait_ms + t_start.elapsed().as_secs_f32() * 1000.0 - before_readback_ms;
        attempt.completed = true;
        THREAD_RESIDENT_PROGRESS.with(|v| v.set(Some(ResidentBatchProgress { stage: final_stage,
            completed_batches: completed_batches + 1, batch_elapsed_ms: attempt.start.elapsed().as_secs_f64() * 1000.0,
            chain_gpu_wait_ms: gpu_wait_ms as f64 })));
        let data = slice.get_mapped_range();
        let out_f32: Vec<f32> = bytemuck::cast_slice(&data[..img_bytes]).to_vec();
        if let (Some(request), Some(_tap)) = (capture.as_deref_mut(), density_capture.as_ref()) {
            request.result = decode_density_samples(&request.samples, &data[img_bytes..]);
            // RESOURCE_AUDIT_BEGIN
            #[cfg(feature = "resource-audit")]
            {
                if let Some(values) = request.result.as_ref() {
                    crate::resource_audit::observe_host("density_sample_results",
                        crate::resource_audit::HostObservationKind::VecCapacity,
                        values.capacity() as u64 * std::mem::size_of::<(u32, [f32; 3])>() as u64);
                }
            }
            // RESOURCE_AUDIT_END
        }
        drop(data);
        map_target.unmap();
        drop(density_capture);

        // Release remaining effect owners before f64 conversion. Image bind
        // groups still retain buf_a/b: keep ping-pong bytes charged through
        // conversion. Dropping local buffer handles alone is not release.
        drop(buf_a);
        drop(buf_b);
        drop(readback);
        drop(halation_state);
        drop(highlight_state);
        drop(diffusion_state);
        drop(cam_mix_state);
        drop(enlarger_diffusion_state);
        drop(enl_mix_state);
        drop(enlarger_log_to_linear_state);
        drop(enlarger_linear_to_log_state);
        drop(camera_lens_blur_state);
        drop(unsharp_state);
        drop(gamut_state);
        drop(glare_state);
        drop(scanner_lens_blur_state);
        drop(grain_state);
        drop(neutral_grain_state);
        drop(dir_state);
        drop(post_scan_state);

        let out = ImageBuf::from_data(image.width, image.height, f32_to_scalars(out_f32));
        // RESOURCE_AUDIT_BEGIN
        #[cfg(feature = "resource-audit")]
        {
            crate::resource_audit::checkpoint("resident_complete");
        }
        // RESOURCE_AUDIT_END
        tracing::debug!(
            cpu_setup_ms = format!("{cpu_setup_ms:.1}"),
            gpu_wait_ms = format!("{gpu_wait_ms:.1}"),
            readback_ms = format!(
                "{:.1}",
                t_start.elapsed().as_secs_f32() * 1000.0 - cpu_setup_ms - gpu_wait_ms
            ),
            completed_batches,
            "film chain timings"
        );
        Some(out)
    }

    /// Get-or-compile a pipeline by shader source + binding layout. Cached by
    /// shader source pointer.
    fn cached_pipeline(
        &self,
        shader_source: &'static str,
        binding_types: &[wgpu::BufferBindingType],
    ) -> CachedPipelineRef {
        let entries: Vec<wgpu::BindGroupLayoutEntry> = binding_types
            .iter()
            .enumerate()
            .map(|(i, &ty)| wgpu::BindGroupLayoutEntry {
                binding: i as u32,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Buffer {
                    ty,
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            })
            .collect();
        let entries_for_compile = entries;
        let mut cache = self.pipeline_cache.lock().unwrap();
        let key = shader_source.as_ptr() as usize;
        if !cache.contains_key(&key) {
            let shader = self
                .device
                .create_shader_module(wgpu::ShaderModuleDescriptor {
                    label: Some("compute_shader"),
                    source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(shader_source)),
                });
            let bind_group_layout =
                self.device
                    .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                        label: Some("compute_layout"),
                        entries: &entries_for_compile,
                    });
            let pipeline_layout =
                self.device
                    .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                        label: Some("compute_pipeline_layout"),
                        bind_group_layouts: &[&bind_group_layout],
                        push_constant_ranges: &[],
                    });
            let pipeline = self
                .device
                .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some("compute_pipeline"),
                    layout: Some(&pipeline_layout),
                    module: &shader,
                    entry_point: Some("main"),
                    compilation_options: Default::default(),
                    cache: None,
                });
            cache.insert(
                key,
                CachedPipeline {
                    bind_group_layout,
                    pipeline,
                },
            );
        }
        // Drop the guard but the underlying Arc keeps the entries alive.
        // Return cloned handles.
        let cached = cache.get(&key).unwrap();
        CachedPipelineRef {
            pipeline: cached.pipeline.clone(),
            layout: cached.bind_group_layout.clone(),
        }
    }
}

#[cfg(feature = "wgpu-backend")]
#[derive(Clone)]
struct CachedPipelineRef {
    pipeline: wgpu::ComputePipeline,
    layout: wgpu::BindGroupLayout,
}

#[cfg(feature = "wgpu-backend")]
struct GpuBuffer {
    data: Vec<u8>,
    binding_type: wgpu::BufferBindingType,
    usage: wgpu::BufferUsages,
}

#[cfg(feature = "wgpu-backend")]
impl GpuBuffer {
    fn uniform(data: &[u8]) -> Self {
        Self {
            data: data.to_vec(),
            binding_type: wgpu::BufferBindingType::Uniform,
            usage: wgpu::BufferUsages::UNIFORM,
        }
    }
    fn storage_ro(data: &[u8]) -> Self {
        Self {
            data: data.to_vec(),
            binding_type: wgpu::BufferBindingType::Storage { read_only: true },
            usage: wgpu::BufferUsages::STORAGE,
        }
    }
    fn storage_rw(data: &[u8]) -> Self {
        Self {
            data: data.to_vec(),
            binding_type: wgpu::BufferBindingType::Storage { read_only: false },
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        }
    }
}

#[cfg(feature = "wgpu-backend")]
impl ComputeBackend for WgpuBackend {
    fn colorspace_convert(&self, img: &ImageBuf, matrix: &[[f32; 3]; 3]) -> ImageBuf {
        let n_pixels = img.pixel_count() as u32;
        // Row-major 3x3 matrix packed as three vec4s (std140 alignment).
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            n_pixels: u32,
            _pad0: u32,
            _pad1: u32,
            _pad2: u32,
            m_row0: [f32; 4],
            m_row1: [f32; 4],
            m_row2: [f32; 4],
        }
        let params = Params {
            n_pixels,
            _pad0: 0,
            _pad1: 0,
            _pad2: 0,
            m_row0: [matrix[0][0], matrix[0][1], matrix[0][2], 0.0],
            m_row1: [matrix[1][0], matrix[1][1], matrix[1][2], 0.0],
            m_row2: [matrix[2][0], matrix[2][1], matrix[2][2], 0.0],
        };
        let input_f32 = scalars_to_f32(&img.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];
        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/colorspace_convert.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            2,
        );
        ImageBuf::from_data(img.width, img.height, f32_to_scalars(result))
    }
    fn cctf_encode_srgb(&self, img: &ImageBuf) -> ImageBuf {
        let n_pixels = img.pixel_count() as u32;
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            n_pixels: u32,
            _pad: [u32; 3],
        }
        let params = Params {
            n_pixels,
            _pad: [0; 3],
        };
        let input_f32 = scalars_to_f32(&img.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];
        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/cctf_encode_srgb.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            2,
        );
        ImageBuf::from_data(img.width, img.height, f32_to_scalars(result))
    }
    fn cctf_decode_srgb(&self, img: &ImageBuf) -> ImageBuf {
        let n_pixels = img.pixel_count() as u32;
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            n_pixels: u32,
            _pad: [u32; 3],
        }
        let params = Params {
            n_pixels,
            _pad: [0; 3],
        };
        let input_f32 = scalars_to_f32(&img.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];
        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/cctf_decode_srgb.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            2,
        );
        ImageBuf::from_data(img.width, img.height, f32_to_scalars(result))
    }
    fn gaussian_blur(&self, img: &ImageBuf, sigma: f32) -> ImageBuf {
        if sigma <= 0.0 {
            return img.clone();
        }
        // For very small sigmas the FIR overhead dominates; CPU path is fine.
        // For practical halation/glare sigmas (1-40 pixels) the GPU is much faster.
        self.gaussian_blur_gpu(img, sigma)
    }
    fn gaussian_blur_multi(&self, img: &ImageBuf, sigmas: &[f32]) -> Vec<ImageBuf> {
        if sigmas.is_empty() {
            return Vec::new();
        }
        self.gaussian_blur_multi_gpu(img, sigmas)
    }
    fn table_lookup(&self, img: &ImageBuf, table_x: &[f32], table_y: &[[f32; 3]]) -> ImageBuf {
        let n_pixels = img.pixel_count() as u32;
        let k = table_x.len() as u32;
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            n_pixels: u32,
            k: u32,
            _pad: [u32; 2],
        }
        let params = Params {
            n_pixels,
            k,
            _pad: [0; 2],
        };
        // Flatten row-major [k][3] — matches the shader's table_y layout.
        let table_y_flat: Vec<f32> = table_y.iter().flat_map(|r| r.iter().copied()).collect();
        let input_f32 = scalars_to_f32(&img.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];
        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/table_lookup.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(table_x)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&table_y_flat)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            4,
        );
        ImageBuf::from_data(img.width, img.height, f32_to_scalars(result))
    }
    fn lut3d_interp(&self, img: &ImageBuf, lut: &Lut3D) -> ImageBuf {
        let n_pixels = img.pixel_count() as u32;
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            n_pixels: u32,
            size: u32,
            _pad: [u32; 2],
        }
        let params = Params {
            n_pixels,
            size: lut.size as u32,
            _pad: [0; 2],
        };
        let input_f32 = scalars_to_f32(&img.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];
        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/lut3d_interp.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&lut.data)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            3,
        );
        ImageBuf::from_data(img.width, img.height, f32_to_scalars(result))
    }

    fn scan_spectral(
        &self,
        density_cmy: &ImageBuf,
        channel_density: &[[f64; 3]],
        base_density: &[f64],
        illuminant: &[f64],
        normalization: f64,
        cat: &[[f64; 3]; 3],
        xyz_to_rgb: &[[f64; 3]; 3],
    ) -> ImageBuf {
        // GPU live-preview path collapses CAT and XYZ→RGB into a single
        // matrix — small precision drop acceptable for preview, matches
        // the same trade-off as `hanatos2025_rgb_to_raw`.
        let combined: [[f64; 3]; 3] = {
            let mut out = [[0.0f64; 3]; 3];
            for i in 0..3 {
                for j in 0..3 {
                    out[i][j] = xyz_to_rgb[i][0] * cat[0][j]
                        + xyz_to_rgb[i][1] * cat[1][j]
                        + xyz_to_rgb[i][2] * cat[2][j];
                }
            }
            out
        };
        let xyz_to_rgb = &combined;
        let n_wl = channel_density.len();
        let n_pixels = density_cmy.pixel_count() as u32;

        // Pack uniform params — mat3x3 in WGSL std140 is 3 columns of vec4.
        // GPU shaders are f32-only: cast precision-preserved inputs at the shader boundary.
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            width: u32,
            height: u32,
            n_wavelengths: u32,
            normalization: f32,
            col0: [f32; 4],
            col1: [f32; 4],
            col2: [f32; 4],
            bw: [f32; 4],
            media: [f32; 4], // x: 0 spectral, 1 logRGB, 2 negative density
            negative_min: [f32; 4],
            negative_range: [f32; 4],
            negative_gamma: [f32; 4],
        }

        let params = Params {
            width: density_cmy.width,
            height: density_cmy.height,
            n_wavelengths: n_wl as u32,
            normalization: normalization as f32,
            col0: [
                xyz_to_rgb[0][0] as f32,
                xyz_to_rgb[1][0] as f32,
                xyz_to_rgb[2][0] as f32,
                0.0,
            ],
            col1: [
                xyz_to_rgb[0][1] as f32,
                xyz_to_rgb[1][1] as f32,
                xyz_to_rgb[2][1] as f32,
                0.0,
            ],
            col2: [
                xyz_to_rgb[0][2] as f32,
                xyz_to_rgb[1][2] as f32,
                xyz_to_rgb[2][2] as f32,
                0.0,
            ],
            // Standalone scan path carries no B&W remap (the resident chain
            // is the only caller that sets it). Identity / disabled.
            // w=1: skip the shader clamp — the per-stage CPU callers
            // (glare, gamut compression, blur/unsharp) expect the
            // unclamped scan RGB, exactly like `scan_spectral_cpu` returns;
            // the final encode+clip happens at the end of the scanning stage.
            bw: [1.0, 0.0, 0.0, 1.0],
            media: [0.;4],
            negative_min: [0.;4],
            negative_range: [1.;4],
            negative_gamma: [1.;4],
        };

        // GPU shaders are f32-only — narrow f64 inputs at the shader boundary.
        // Metal's compiler runs fast-math (no-NaN assumption), so we sanitize CPU-side:
        // any wavelength with a NaN input has its `base_density` bumped to +1000 so
        // `pow(10, -d) ≈ 0`, zeroing that wavelength's contribution exactly the same
        // way Python's `density_to_light` zeros NaN-bearing wavelengths.
        let (cd_flat, mut bd) = sanitize_spectral_inputs(channel_density, base_density, n_wl);
        let illu_f32: Vec<f32> = illuminant.iter().map(|&v| v as f32).collect();
        bd.resize(n_wl, 0.0);
        let input_f32 = scalars_to_f32(&density_cmy.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];

        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/scan_spectral.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&cd_flat)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&bd)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&illu_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&spektrafilm_math::spectral::CMF_X)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&spektrafilm_math::spectral::CMF_Y)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&spektrafilm_math::spectral::CMF_Z)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            8, // output buffer index
        );

        ImageBuf::from_data(
            density_cmy.width,
            density_cmy.height,
            f32_to_scalars(result),
        )
    }

    fn print_spectral(
        &self,
        density_cmy: &ImageBuf,
        channel_density: &[[f64; 3]],
        base_density: &[f64],
        illuminant: &[f64],
        sensitivity: &[[f64; 3]],
        normalization_factor: f64,
        preflash: [f64; 3],
    ) -> ImageBuf {
        let n_wl = channel_density.len();
        let n_pixels = density_cmy.pixel_count() as u32;

        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            width: u32,
            height: u32,
            n_wavelengths: u32,
            normalization_factor: f32,
            preflash: [f32; 3],
            _pad: f32,
            media: [f32; 4], // x=omitted film, y=fixed paper gray log exposure
            paper_gain: [f32; 4],
        }

        let params = Params {
            width: density_cmy.width,
            height: density_cmy.height,
            n_wavelengths: n_wl as u32,
            normalization_factor: normalization_factor as f32,
            preflash: [preflash[0] as f32, preflash[1] as f32, preflash[2] as f32],
            _pad: 0.0,
            media: [0.;4],
            paper_gain: [1.,1.,1.,0.],
        };

        // GPU shaders are f32-only — narrow f64 inputs at the shader boundary.
        // See `sanitize_spectral_inputs` for the NaN-handling story (Metal fast-math).
        let (cd_flat, mut bd) = sanitize_spectral_inputs(channel_density, base_density, n_wl);
        let sens_flat: Vec<f32> = sensitivity
            .iter()
            .flat_map(|r| r.iter().map(|&v| if v.is_nan() { 0.0 } else { v as f32 }))
            .collect();
        bd.resize(n_wl, 0.0);
        let illu_f32: Vec<f32> = illuminant.iter().map(|&v| v as f32).collect();
        let input_f32 = scalars_to_f32(&density_cmy.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];

        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/print_spectral.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&cd_flat)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&bd)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&illu_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&sens_flat)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            6, // output buffer index
        );

        ImageBuf::from_data(
            density_cmy.width,
            density_cmy.height,
            f32_to_scalars(result),
        )
    }

    fn hanatos2025_rgb_to_raw(
        &self,
        image: &ImageBuf,
        tc_lut: &spektrafilm_math::spectral::TcLut,
        color_space: &str,
        ref_illuminant: &[f32],
        cat16: bool,
    ) -> ImageBuf {
        // GPU live-preview path collapses the two-step CAT02 adaptation
        // into a single matmul — small visible-spectrum precision drop
        // that's acceptable for preview. The CPU path keeps the two-step
        // for export bit-parity with Python.
        let rgb_to_adapted_xyz = spektrafilm_math::spectral::build_rgb_to_adapted_xyz(
            color_space,
            ref_illuminant,
            cat16,
        );
        let rgb_to_adapted_xyz = &rgb_to_adapted_xyz;
        let n_pixels = image.pixel_count() as u32;
        let lut_size = tc_lut.size as u32;

        // Pack uniform: mat3x3 in std140 = 3 vec4 columns. f32 only on GPU.
        // WGSL columns are read as columns of the matrix; storing row-major as
        // [col[0], col[1], col[2]] where col[j][i] = mat[i][j].
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            width: u32,
            height: u32,
            lut_size: u32,
            _pad: u32,
            col0: [f32; 4],
            col1: [f32; 4],
            col2: [f32; 4],
        }

        let m = rgb_to_adapted_xyz;
        let params = Params {
            width: image.width,
            height: image.height,
            lut_size,
            _pad: 0,
            col0: [m[0][0] as f32, m[1][0] as f32, m[2][0] as f32, 0.0],
            col1: [m[0][1] as f32, m[1][1] as f32, m[2][1] as f32, 0.0],
            col2: [m[0][2] as f32, m[1][2] as f32, m[2][2] as f32, 0.0],
        };

        // TC LUT data is f64 on disk; narrow to f32 at the GPU boundary.
        let tc_lut_f32: Vec<f32> = tc_lut.data.iter().map(|&v| v as f32).collect();
        let input_f32 = scalars_to_f32(&image.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];

        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/hanatos2025_rgb_to_raw.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&tc_lut_f32)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            3, // output buffer index
        );

        ImageBuf::from_data(image.width, image.height, f32_to_scalars(result))
    }

    fn density_curve_interp(
        &self,
        log_raw: &ImageBuf,
        log_exposure: &[f64],
        density_curves: &[[f64; 3]],
        gamma_factor: f64,
    ) -> ImageBuf {
        let n_pixels = log_raw.pixel_count() as u32;
        let k = log_exposure.len() as u32;

        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct Params {
            width: u32,
            height: u32,
            k: u32,
            uniform_grid: u32,
            gamma_inv: [f32; 3],
            _pad: f32,
        }
        // Detect uniformly-spaced log_exposure (typical case) to use the fast path.
        let uniform = is_uniform(log_exposure);
        let gamma_inv = if gamma_factor.abs() > 1e-12 {
            (1.0 / gamma_factor) as f32
        } else {
            1.0
        };
        let params = Params {
            width: log_raw.width,
            height: log_raw.height,
            k,
            uniform_grid: if uniform { 1 } else { 0 },
            gamma_inv: [gamma_inv; 3],
            _pad: 0.0,
        };

        let log_exp_f32: Vec<f32> = log_exposure.iter().map(|&v| v as f32).collect();
        let curves_f32: Vec<f32> = density_curves
            .iter()
            .flat_map(|r| r.iter().map(|&v| if v.is_nan() { 0.0 } else { v as f32 }))
            .collect();
        let input_f32 = scalars_to_f32(&log_raw.data);
        let output_bytes = vec![0u8; n_pixels as usize * 3 * 4];

        let result = self.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/density_curve_interp.wgsl"),
            &[
                GpuBuffer::uniform(bytemuck::bytes_of(&params)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&input_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&log_exp_f32)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&curves_f32)),
                GpuBuffer::storage_rw(&output_bytes),
            ],
            n_pixels,
            4, // output buffer index
        );

        ImageBuf::from_data(log_raw.width, log_raw.height, f32_to_scalars(result))
    }

    fn try_run_film_chain(&self, params: &crate::FilmChainParams<'_>) -> Option<ImageBuf> {
        if !self.is_healthy() { return None; }
        self.run_film_chain_controlled(params, &mut || true)
    }

    fn reset_thread_resident_progress(&self) {
        THREAD_RESIDENT_PROGRESS.with(|v| v.set(None));
        THREAD_RESIDENT_FAILURE.with(|v| v.set(None));
    }

    fn try_run_film_chain_controlled(&self, params: &crate::FilmChainParams<'_>,
        checkpoint: &mut dyn FnMut() -> bool) -> Option<ImageBuf> {
        if !self.is_healthy() { return None; }
        self.run_film_chain_controlled(params, checkpoint)
    }

    fn try_run_film_chain_capture_controlled(&self, params: &crate::FilmChainParams<'_>,
        capture: &mut crate::FilmDensityCapture, checkpoint: &mut dyn FnMut() -> bool) -> Option<ImageBuf> {
        capture.result = None;
        if !self.is_healthy() { return None; }
        self.run_film_chain_capture_controlled(params, Some(capture), checkpoint)
    }

    fn resident_chain_applies_post_scan(&self) -> bool {
        true
    }

    fn is_gpu(&self) -> bool {
        true
    }

    fn name(&self) -> &str {
        "wgpu (GPU)"
    }
}

/// Pre-built halation passes — owns the per-sigma kernel buffers, params
/// buffers, and bind groups so they live long enough for the encoder.
///
/// The CPU equivalent is `spektrafilm_model::diffusion::apply_halation_um`.
/// All passes operate on the resident image buffer (passed in as `buf_b`),
/// using `buf_a` as blur intermediate and two newly-allocated buffers
/// `buf_c` / `buf_d` for scatter outputs and the halation accumulator.
#[cfg(feature = "wgpu-backend")]
struct HalationState {
    // Owns all the auxiliary buffers + bind groups; only `encode_passes` is
    // called from the hot path.
    buf_c: AuditBuffer,
    buf_d: AuditBuffer,
    scatter_blurs: Vec<BlurJob>, // [core, tail]
    scatter_mix: DispatchJob,
    halation_blurs: Vec<BlurJob>,          // one per bounce
    halation_accumulate: Vec<DispatchJob>, // one add_scaled per bounce
    halation_final_add: DispatchJob,
    halation_renormalize: Option<DispatchJob>,
    blur_pipe_h: CachedPipelineRef,
    blur_pipe_v: CachedPipelineRef,
    physical_blur_pipe_h: Option<CachedPipelineRef>,
    physical_blur_pipe_v: Option<CachedPipelineRef>,
}

#[cfg(feature = "wgpu-backend")]
struct BlurJob {
    _kernel_buf: AuditBuffer,
    _params_buf: AuditBuffer,
    bg_h: AuditBindGroup,
    bg_v: AuditBindGroup,
}

/// Decode only the appended region of the already-completed output map.
fn decode_density_samples(samples: &[crate::FilmDensitySample], bytes: &[u8]) -> Option<Vec<(u32, [f32; 3])>> {
    if bytes.len() != samples.len().checked_mul(12)? { return None; }
    let values: &[f32] = bytemuck::try_cast_slice(bytes).ok()?;
    if values.iter().any(|v| !v.is_finite()) { return None; }
    Some(samples.iter().zip(values.chunks_exact(3))
        .map(|(sample, rgb)| (sample.index, [rgb[0], rgb[1], rgb[2]])).collect())
}

/// Bounded GPU-only pre-grain snapshot, harvested by the REQUIRED output map.
#[cfg(feature = "wgpu-backend")]
struct DensityCaptureState {
    pipeline: CachedPipelineRef,
    bg: AuditBindGroup,
    _offsets: AuditBuffer,
    _params: AuditBuffer,
    gathered: AuditBuffer,
    count: u32,
}
#[cfg(feature = "wgpu-backend")]
impl DensityCaptureState {
    fn new(backend: &WgpuBackend, request: &crate::FilmDensityCapture, width: u32,
        density: &AuditBuffer) -> Option<Self> {
        let count = u32::try_from(request.samples.len()).ok()?;
        let bytes = u64::from(count).checked_mul(12)?;
        let limits = backend.device.limits();
        if bytes > backend.max_storage_buffer_binding_size() || bytes > limits.max_buffer_size
            || count.div_ceil(64) > limits.max_compute_workgroups_per_dimension { return None; }
        let offsets: Vec<u32> = request.samples.iter().map(|s| s.y * width + s.x).collect();
        let offsets = backend.device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("negative_sample_offsets"), contents: bytemuck::cast_slice(&offsets),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let params = backend.device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("negative_sample_params"), contents: bytemuck::cast_slice(&[count, 0, 0, 0]),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let gathered = backend.device.census_create_buffer(&wgpu::BufferDescriptor {
            label: Some("negative_samples"), size: bytes,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC, mapped_at_creation: false,
        });
        let pipeline = backend.cached_pipeline(include_str!("../../spektrafilm-shaders/wgsl/film_density_gather.wgsl"), &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ]);
        let bg = backend.device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("negative_samples"), layout: &pipeline.layout,
            entries: &[
                AuditBindGroupEntry { binding: 0, resource: params.as_entire_binding() },
                AuditBindGroupEntry { binding: 1, resource: density.as_entire_binding() },
                AuditBindGroupEntry { binding: 2, resource: offsets.as_entire_binding() },
                AuditBindGroupEntry { binding: 3, resource: gathered.as_entire_binding() },
            ],
        });
        Some(Self { pipeline, bg, _offsets: offsets, _params: params, gathered, count })
    }
    fn encode(&self, encoder: &mut AuditCommandEncoder) {
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("negative_density_gather"), timestamp_writes: None,
            });
            pass.set_pipeline(&self.pipeline.pipeline);
            pass.set_bind_group(0, &self.bg, &[]);
            pass.dispatch_workgroups(self.count.div_ceil(64), 1, 1);
        }
    }
}

#[cfg(feature = "wgpu-backend")]
struct DispatchJob {
    _params_buf: AuditBuffer,
    pipeline: CachedPipelineRef,
    bg: AuditBindGroup,
}

#[cfg(feature = "wgpu-backend")]
fn build_halation_state(
    device: &wgpu::Device,
    hp: &crate::HalationGpuParams,
    width: u32,
    height: u32,
    buf_a: &AuditBuffer,
    buf_b: &AuditBuffer,
    backend: &WgpuBackend,
) -> HalationState {
    let n_pixels = (width as usize) * (height as usize);
    let img_bytes = (n_pixels * 3 * 4) as u64;

    let mk_buf = |label: &str| {
        device.census_create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: img_bytes,
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        })
    };
    let buf_c = mk_buf("halation_c"); // scatter core / blur output
    let buf_d = mk_buf("halation_d"); // scatter tail / accumulator

    let blur_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    let blur_pipe_h = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
        blur_layout,
    );
    let blur_pipe_v = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
        blur_layout,
    );

    // Helper: build a single H/V blur job from source → output, using buf_a
    // (the freed RGB upload) as blur scratch (mid).
    let physical_blur_pipe_h = hp.back_reflection.as_ref().map(|_| backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/halation_blur_h.wgsl"), blur_layout));
    let physical_blur_pipe_v = hp.back_reflection.as_ref().map(|_| backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/halation_blur_v.wgsl"), blur_layout));
    let make_blur_job =
        |sigma: f32, src: &AuditBuffer, out: &AuditBuffer, label: &str, full_support: bool| -> BlurJob {
            let blur_pipe_h = if full_support { physical_blur_pipe_h.as_ref().unwrap() } else { &blur_pipe_h };
            let blur_pipe_v = if full_support { physical_blur_pipe_v.as_ref().unwrap() } else { &blur_pipe_v };
            let sigma = sigma.max(0.01);
            let radius = if full_support { crate::back_reflection_fir_radius(sigma).expect("validated physical PSF") } else { fir_blur_radius(sigma) };
            let kernel_size = (2 * radius + 1) as usize;
            let sigma_f64 = sigma as f64;
            let two_sigma_sq = 2.0 * sigma_f64 * sigma_f64;
            let r_i32 = radius as i32;
            let mut kernel = Vec::with_capacity(kernel_size);
            for k in 0..kernel_size {
                let x = (k as i32 - r_i32) as f64;
                kernel.push((-x * x / two_sigma_sq).exp());
            }
            let sum: f64 = kernel.iter().sum();
            let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / sum) as f32).collect();

            let kernel_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some(&format!("halation_blur_kernel_{label}")),
                contents: bytemuck::cast_slice(&kernel_f32),
                usage: wgpu::BufferUsages::STORAGE,
            });
            #[repr(C)]
            #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
            struct BlurParams {
                width: u32,
                height: u32,
                radius: u32,
                _pad: u32,
            }
            let params = BlurParams {
                width,
                height,
                radius,
                _pad: u32::from(full_support && sigma < 3.0),
            };
            let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some(&format!("halation_blur_params_{label}")),
                contents: bytemuck::bytes_of(&params),
                usage: wgpu::BufferUsages::UNIFORM,
            });
            let bg_h = device.audit_create_bind_group(&AuditBindGroupDescriptor {
                label: Some(&format!("halation_blur_h_{label}")),
                layout: &blur_pipe_h.layout,
                entries: &[
                    AuditBindGroupEntry {
                        binding: 0,
                        resource: params_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 1,
                        resource: src.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 2,
                        resource: kernel_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 3,
                        resource: buf_a.as_entire_binding(),
                    }, // mid
                ],
            });
            let bg_v = device.audit_create_bind_group(&AuditBindGroupDescriptor {
                label: Some(&format!("halation_blur_v_{label}")),
                layout: &blur_pipe_v.layout,
                entries: &[
                    AuditBindGroupEntry {
                        binding: 0,
                        resource: params_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 1,
                        resource: buf_a.as_entire_binding(),
                    }, // mid
                    AuditBindGroupEntry {
                        binding: 2,
                        resource: kernel_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 3,
                        resource: out.as_entire_binding(),
                    },
                ],
            });
            BlurJob {
                _kernel_buf: kernel_buf,
                _params_buf: params_buf,
                bg_h,
                bg_v,
            }
        };

    // ── Scatter blurs (core → buf_c, tail → buf_d) ─────────────────────
    let scatter_blurs = vec![
        make_blur_job(hp.scatter_core_px, buf_b, &buf_c, "scatter_core", false),
        make_blur_job(hp.scatter_tail_px, buf_b, &buf_d, "scatter_tail", false),
    ];

    // ── Scatter mix: result = (1-sa)*result + sa*((1-atw)*core + atw*tail)
    let scatter_mix_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/scatter_mix.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct ScatterMixParams {
        n_pixels: u32,
        scatter_amount: f32,
        _pad0: u32,
        _pad1: u32,
        // vec4<f32>: per-channel tail weights, .w padding for 16-byte alignment
        tail_weight: [f32; 4],
    }
    let scatter_mix_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("halation_scatter_mix_params"),
        contents: bytemuck::bytes_of(&ScatterMixParams {
            n_pixels: n_pixels as u32,
            scatter_amount: hp.scatter_amount,
            _pad0: 0,
            _pad1: 0,
            tail_weight: [
                hp.scatter_tail_weight[0],
                hp.scatter_tail_weight[1],
                hp.scatter_tail_weight[2],
                0.0,
            ],
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let scatter_mix_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("halation_scatter_mix_bg"),
        layout: &scatter_mix_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: scatter_mix_params.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: buf_c.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: buf_d.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 3,
                resource: buf_b.as_entire_binding(),
            },
        ],
    });
    let scatter_mix_job = DispatchJob {
        _params_buf: scatter_mix_params,
        pipeline: scatter_mix_pipe,
        bg: scatter_mix_bg,
    };

    // ── Halation bounces ──────────────────────────────────────────────
    let n = if hp.back_reflection.is_some() { 0 } else { hp.halation_n_bounces as usize };
    // Pre-compute normalized decay weights.
    let mut decay = vec![0.0f32; n];
    for k in 0..n {
        decay[k] = hp.halation_bounce_decay.powi(k as i32);
    }
    let decay_sum: f32 = decay.iter().sum();
    if decay_sum > 0.0 {
        for d in &mut decay {
            *d /= decay_sum;
        }
    }

    let mut halation_blurs = Vec::new();
    let mut halation_accumulate = Vec::new();
    if let Some(components) = &hp.back_reflection {
        let pipe = backend.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/halation_accumulate.wgsl"),
            &[
                wgpu::BufferBindingType::Uniform,
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: false },
            ],
        );
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct ComponentParams { n_pixels: u32, clear_first: u32, _pad: [u32; 2], scale: [f32; 4] }
        for (k, component) in components.iter().enumerate() {
            for c in 0..3 {
                let mut scale = [0.0; 4];
                scale[c] = component.weight[c];
                halation_blurs.push(make_blur_job(component.sigma_px[c], buf_b, &buf_c,
                    &format!("back_reflection_{k}_{c}"), true));
                let params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                    label: Some("halation_component_params"),
                    contents: bytemuck::bytes_of(&ComponentParams {
                        n_pixels: n_pixels as u32, clear_first: u32::from(k == 0 && c == 0), _pad: [0; 2], scale,
                    }), usage: wgpu::BufferUsages::UNIFORM,
                });
                let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
                    label: Some("halation_component_bg"), layout: &pipe.layout,
                    entries: &[
                        AuditBindGroupEntry { binding: 0, resource: params.as_entire_binding() },
                        AuditBindGroupEntry { binding: 1, resource: buf_c.as_entire_binding() },
                        AuditBindGroupEntry { binding: 2, resource: buf_d.as_entire_binding() },
                    ],
                });
                halation_accumulate.push(DispatchJob { _params_buf: params, pipeline: pipe.clone(), bg });
            }
        }
    } else {
    // Each bounce blurs buf_b → buf_c at sigma_k, then accumulates into
    // buf_d. First bounce sets `clear_first` so we don't need a separate
    // zero pass.
    let add_scaled_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/add_scaled.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct AddScaledParams {
        n_pixels: u32,
        scale: f32,
        clear_first: u32,
        _pad: u32,
    }

    for (k, &wk) in decay.iter().enumerate() {
        let sigma_k = hp.halation_first_sigma_px * ((k as f32) + 1.0).sqrt();
        halation_blurs.push(make_blur_job(
            sigma_k,
            buf_b,
            &buf_c,
            &format!("bounce_{k}"), false,
        ));

        let acc_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some(&format!("halation_acc_params_{k}")),
            contents: bytemuck::bytes_of(&AddScaledParams {
                n_pixels: n_pixels as u32,
                scale: wk,
                clear_first: if k == 0 { 1 } else { 0 },
                _pad: 0,
            }),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let acc_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some(&format!("halation_acc_bg_{k}")),
            layout: &add_scaled_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: acc_params.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_c.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: buf_d.as_entire_binding(),
                },
            ],
        });
        halation_accumulate.push(DispatchJob {
            _params_buf: acc_params,
            pipeline: add_scaled_pipe.clone(),
            bg: acc_bg,
        });
    }

    }

    // Final add: result[c] += a_tot[c] * accumulator[c]. Per-channel
    // because halation_strength varies dramatically across channels
    // (Portra zeros out blue entirely). Uses the per-channel variant
    // of add_scaled.
    let final_add_pipe = backend.cached_pipeline(
        if hp.back_reflection.is_some() { include_str!("../../spektrafilm-shaders/wgsl/halation_final_add.wgsl") }
        else { include_str!("../../spektrafilm-shaders/wgsl/add_scaled_per_channel.wgsl") },
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct AddScaledPerChannelParams {
        n_pixels: u32,
        _pad0: u32,
        _pad1: u32,
        _pad2: u32,
        scale: [f32; 4],
    }
    let final_add_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("halation_final_add_params"),
        contents: bytemuck::bytes_of(&AddScaledPerChannelParams {
            n_pixels: n_pixels as u32,
            _pad0: 0,
            _pad1: 0,
            _pad2: 0,
            scale: [
                hp.halation_a_tot[0],
                hp.halation_a_tot[1],
                hp.halation_a_tot[2],
                0.0,
            ],
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let final_add_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("halation_final_add_bg"),
        layout: &final_add_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: final_add_params.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: buf_d.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: buf_b.as_entire_binding(),
            },
        ],
    });
    let halation_final_add = DispatchJob {
        _params_buf: final_add_params,
        pipeline: final_add_pipe,
        bg: final_add_bg,
    };

    // Per-channel renormalize: `result[c] /= 1 + a_tot[c]`. Uses the
    // pre-computed inverse factor passed in `inv_factor.xyz`.
    let halation_renormalize = if hp.halation_renormalize {
        let renorm_pipe = backend.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/halation_renormalize.wgsl"),
            &[
                wgpu::BufferBindingType::Uniform,
                wgpu::BufferBindingType::Storage { read_only: false },
            ],
        );
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct RenormParams {
            n_pixels: u32,
            _pad: u32,
            // f32x4: .xyz used, .w padding to align next vec4. Two u32
            // header fields above push this to offset 16, matching the
            // WGSL std140 layout.
            _gap: [u32; 2],
            inv_factor: [f32; 4],
        }
        let inv = [
            1.0 / (1.0 + hp.halation_a_tot[0]),
            1.0 / (1.0 + hp.halation_a_tot[1]),
            1.0 / (1.0 + hp.halation_a_tot[2]),
            0.0,
        ];
        let renorm_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("halation_renorm_params"),
            contents: bytemuck::bytes_of(&RenormParams {
                n_pixels: n_pixels as u32,
                _pad: 0,
                _gap: [0, 0],
                inv_factor: inv,
            }),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let renorm_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("halation_renorm_bg"),
            layout: &renorm_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: renorm_params.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_b.as_entire_binding(),
                },
            ],
        });
        Some(DispatchJob {
            _params_buf: renorm_params,
            pipeline: renorm_pipe,
            bg: renorm_bg,
        })
    } else {
        None
    };

    HalationState {
        buf_c,
        buf_d,
        scatter_blurs,
        scatter_mix: scatter_mix_job,
        halation_blurs,
        halation_accumulate,
        halation_final_add,
        halation_renormalize,
        blur_pipe_h,
        blur_pipe_v,
        physical_blur_pipe_h,
        physical_blur_pipe_v,
    }
}

#[cfg(feature = "wgpu-backend")]
impl HalationState {
    fn encode_passes_batched(
        &self,
        encoder: &mut AuditCommandEncoder,
        n_pixels: u32,
        workgroup_size: u32,
        wg_xy: (u32, u32),
        finish_batch: &mut dyn FnMut(&mut AuditCommandEncoder) -> bool,
    ) -> bool {
        let _ = (&self.buf_c, &self.buf_d); // owned, just keepalive
        let dispatch_blur = |enc: &mut AuditCommandEncoder, job: &BlurJob, physical: bool| {
            let h = if physical { self.physical_blur_pipe_h.as_ref().unwrap_or(&self.blur_pipe_h) } else { &self.blur_pipe_h };
            let v = if physical { self.physical_blur_pipe_v.as_ref().unwrap_or(&self.blur_pipe_v) } else { &self.blur_pipe_v };
            {
                let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("halation_blur_h"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&h.pipeline);
                pass.set_bind_group(0, &job.bg_h, &[]);
                pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
            }
            {
                let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("halation_blur_v"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&v.pipeline);
                pass.set_bind_group(0, &job.bg_v, &[]);
                pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
            }
        };
        let dispatch_linear = |enc: &mut AuditCommandEncoder, job: &DispatchJob| {
            let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("halation_linear"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&job.pipeline.pipeline);
            pass.set_bind_group(0, &job.bg, &[]);
            pass.dispatch_workgroups(n_pixels.div_ceil(workgroup_size), 1, 1);
        };

        // Scatter
        if !self.scatter_blurs.is_empty() {
            for j in &self.scatter_blurs {
                dispatch_blur(encoder, j, false);
                if !finish_batch(encoder) { return false; }
            }
            dispatch_linear(encoder, &self.scatter_mix);
        }

        // Halation bounces
        for (blur_job, acc_job) in self
            .halation_blurs
            .iter()
            .zip(self.halation_accumulate.iter())
        {
            dispatch_blur(encoder, blur_job, true);
            dispatch_linear(encoder, acc_job);
            if !finish_batch(encoder) { return false; }
        }
        if !self.halation_blurs.is_empty() {
            dispatch_linear(encoder, &self.halation_final_add);
            if let Some(rn) = &self.halation_renormalize {
                dispatch_linear(encoder, rn);
            }
        }
        true
    }
}

/// A 16×16-workgroup compute pass that writes an `out_w × out_h` image
/// (downsample / upsample).
#[cfg(feature = "wgpu-backend")]
struct GridJob {
    pipeline: CachedPipelineRef,
    bg: AuditBindGroup,
    out_w: u32,
    out_h: u32,
}

/// Pre-built camera diffusion-filter passes for the resident chain. Operates
/// on `buf_b` (raw film exposure, after hanatos / before halation):
/// downsample buf_b → small, blur the small image at each Gaussian component
/// accumulating per-channel, upsample the scattered field, then mix
/// `buf_b = (1-p_s)·buf_b + p_s·scattered`. Owns all scratch buffers so they
/// outlive the encoder. CPU equivalent: `apply_diffusion_filter_blur`.
#[cfg(feature = "wgpu-backend")]
struct DiffusionState {
    small_w: u32,
    small_h: u32,
    n_full: u32,
    n_small: u32,
    // Owned buffers (keepalive for the bind groups + clear/copy targets).
    _small_in: AuditBuffer,
    _small_mid: AuditBuffer,
    _small_out: AuditBuffer,
    small_acc: AuditBuffer,
    _upsampled: AuditBuffer,
    buf_mix: AuditBuffer,
    blur_pipe_h: CachedPipelineRef,
    blur_pipe_v: CachedPipelineRef,
    downsample: GridJob,
    blur_jobs: Vec<BlurJob>,
    accumulate: Vec<DispatchJob>,
    upsample: GridJob,
    mix1: DispatchJob,
    mix2: DispatchJob,
}

#[cfg(feature = "wgpu-backend")]
fn build_diffusion_state(
    device: &wgpu::Device,
    plan: &crate::DiffusionGpuPlan,
    width: u32,
    height: u32,
    buf_b: &AuditBuffer,
    backend: &WgpuBackend,
) -> DiffusionState {
    let small_w = plan.small_w;
    let small_h = plan.small_h;
    let n_full = (width as usize) * (height as usize);
    let n_small = (small_w as usize) * (small_h as usize);
    let full_bytes = (n_full * 3 * 4) as u64;
    let small_bytes = (n_small * 3 * 4) as u64;

    let mk = |label: &str, bytes: u64| {
        device.census_create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: bytes,
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        })
    };
    let small_in = mk("diff_small_in", small_bytes);
    let small_mid = mk("diff_small_mid", small_bytes);
    let small_out = mk("diff_small_out", small_bytes);
    let small_acc = mk("diff_small_acc", small_bytes);
    let upsampled = mk("diff_upsampled", full_bytes);
    let buf_mix = mk("diff_mix", full_bytes);

    // ── Downsample buf_b → small_in ───────────────────────────────────
    let grid_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct DownParams {
        in_w: u32,
        in_h: u32,
        out_w: u32,
        out_h: u32,
        factor: u32,
        _p0: u32,
        _p1: u32,
        _p2: u32,
    }
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct UpParams {
        in_w: u32,
        in_h: u32,
        out_w: u32,
        out_h: u32,
        inv_factor: f32,
        _p0: u32,
        _p1: u32,
        _p2: u32,
    }
    let down_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/downsample_area.wgsl"),
        grid_layout,
    );
    let down_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("diff_down_params"),
        contents: bytemuck::bytes_of(&DownParams {
            in_w: width,
            in_h: height,
            out_w: small_w,
            out_h: small_h,
            factor: plan.d,
            _p0: 0,
            _p1: 0,
            _p2: 0,
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let down_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("diff_down_bg"),
        layout: &down_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: down_params.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: buf_b.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: small_in.as_entire_binding(),
            },
        ],
    });
    let downsample = GridJob {
        pipeline: down_pipe,
        bg: down_bg,
        out_w: small_w,
        out_h: small_h,
    };

    // ── Per-component blurs (small_in → small_out via small_mid) ──────
    let blur_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    let blur_pipe_h = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
        blur_layout,
    );
    let blur_pipe_v = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
        blur_layout,
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct BlurParams {
        width: u32,
        height: u32,
        radius: u32,
        _pad: u32,
    }
    let add_pc_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/add_scaled_per_channel.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct AddPcParams {
        n_pixels: u32,
        _p0: u32,
        _p1: u32,
        _p2: u32,
        scale: [f32; 4],
    }

    let mut blur_jobs = Vec::with_capacity(plan.sigmas.len());
    let mut accumulate = Vec::with_capacity(plan.sigmas.len());
    for (i, (&sigma, coeff)) in plan.sigmas.iter().zip(plan.coeffs.iter()).enumerate() {
        let sigma = sigma.max(0.01);
        let radius = fir_blur_radius(sigma);
        let kernel_size = (2 * radius + 1) as usize;
        let two_sigma_sq = 2.0 * (sigma as f64) * (sigma as f64);
        let r_i32 = radius as i32;
        let mut kernel = Vec::with_capacity(kernel_size);
        for k in 0..kernel_size {
            let x = (k as i32 - r_i32) as f64;
            kernel.push((-x * x / two_sigma_sq).exp());
        }
        let ksum: f64 = kernel.iter().sum();
        let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / ksum) as f32).collect();
        let kernel_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some(&format!("diff_kernel_{i}")),
            contents: bytemuck::cast_slice(&kernel_f32),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let bp = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some(&format!("diff_blur_params_{i}")),
            contents: bytemuck::bytes_of(&BlurParams {
                width: small_w,
                height: small_h,
                radius,
                _pad: 0,
            }),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let bg_h = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some(&format!("diff_blur_h_{i}")),
            layout: &blur_pipe_h.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: bp.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: small_in.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: kernel_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: small_mid.as_entire_binding(),
                },
            ],
        });
        let bg_v = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some(&format!("diff_blur_v_{i}")),
            layout: &blur_pipe_v.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: bp.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: small_mid.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: kernel_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: small_out.as_entire_binding(),
                },
            ],
        });
        blur_jobs.push(BlurJob {
            _kernel_buf: kernel_buf,
            _params_buf: bp,
            bg_h,
            bg_v,
        });

        // Accumulate small_out (per-channel coeff) → small_acc.
        let ap = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some(&format!("diff_acc_params_{i}")),
            contents: bytemuck::bytes_of(&AddPcParams {
                n_pixels: n_small as u32,
                _p0: 0,
                _p1: 0,
                _p2: 0,
                scale: [coeff[0], coeff[1], coeff[2], 0.0],
            }),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let abg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some(&format!("diff_acc_bg_{i}")),
            layout: &add_pc_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: ap.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: small_out.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: small_acc.as_entire_binding(),
                },
            ],
        });
        accumulate.push(DispatchJob {
            _params_buf: ap,
            pipeline: add_pc_pipe.clone(),
            bg: abg,
        });
    }

    // ── Upsample small_acc → upsampled (full res) ────────────────────
    let up_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/upsample_bilinear.wgsl"),
        grid_layout,
    );
    let up_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("diff_up_params"),
        contents: bytemuck::bytes_of(&UpParams {
            in_w: small_w,
            in_h: small_h,
            out_w: width,
            out_h: height,
            inv_factor: 1.0 / plan.d as f32,
            _p0: 0,
            _p1: 0,
            _p2: 0,
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let up_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("diff_up_bg"),
        layout: &up_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: up_params.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: small_acc.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: upsampled.as_entire_binding(),
            },
        ],
    });
    let upsample = GridJob {
        pipeline: up_pipe,
        bg: up_bg,
        out_w: width,
        out_h: height,
    };

    // ── Mix: buf_mix = (1-p_s)*buf_b + p_s*upsampled (then copied to buf_b)
    let add_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/add_scaled.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct AddScaledParams {
        n_pixels: u32,
        scale: f32,
        clear_first: u32,
        _pad: u32,
    }
    let mk_add = |label: &str, src: &AuditBuffer, scale: f32, clear: u32| -> DispatchJob {
        let pb = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some(label),
            contents: bytemuck::bytes_of(&AddScaledParams {
                n_pixels: n_full as u32,
                scale,
                clear_first: clear,
                _pad: 0,
            }),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some(label),
            layout: &add_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: pb.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: src.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: buf_mix.as_entire_binding(),
                },
            ],
        });
        DispatchJob {
            _params_buf: pb,
            pipeline: add_pipe.clone(),
            bg,
        }
    };
    let mix1 = mk_add("diff_mix1", buf_b, 1.0 - plan.p_s, 1);
    let mix2 = mk_add("diff_mix2", &upsampled, plan.p_s, 0);

    DiffusionState {
        small_w,
        small_h,
        n_full: n_full as u32,
        n_small: n_small as u32,
        _small_in: small_in,
        _small_mid: small_mid,
        _small_out: small_out,
        small_acc,
        _upsampled: upsampled,
        buf_mix,
        blur_pipe_h,
        blur_pipe_v,
        downsample,
        blur_jobs,
        accumulate,
        upsample,
        mix1,
        mix2,
    }
}

#[cfg(feature = "wgpu-backend")]
impl DiffusionState {
    /// Encode the diffusion onto `buf_b` (in place: result copied back).
    fn encode_passes_batched(&self, encoder: &mut AuditCommandEncoder, buf_b: &AuditBuffer,
        finish_batch: &mut dyn FnMut(&mut AuditCommandEncoder) -> bool) -> bool {
        let grid = |g: &GridJob, enc: &mut AuditCommandEncoder| {
            let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("diff_grid"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&g.pipeline.pipeline);
            pass.set_bind_group(0, &g.bg, &[]);
            pass.dispatch_workgroups(g.out_w.div_ceil(16), g.out_h.div_ceil(16), 1);
        };
        let blur_small = |job: &BlurJob, enc: &mut AuditCommandEncoder| {
            let wg = (self.small_w.div_ceil(16), self.small_h.div_ceil(16));
            {
                let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("diff_blur_h"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipe_h.pipeline);
                pass.set_bind_group(0, &job.bg_h, &[]);
                pass.dispatch_workgroups(wg.0, wg.1, 1);
            }
            {
                let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("diff_blur_v"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipe_v.pipeline);
                pass.set_bind_group(0, &job.bg_v, &[]);
                pass.dispatch_workgroups(wg.0, wg.1, 1);
            }
        };
        let linear = |job: &DispatchJob, n: u32, enc: &mut AuditCommandEncoder| {
            let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("diff_linear"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&job.pipeline.pipeline);
            pass.set_bind_group(0, &job.bg, &[]);
            pass.dispatch_workgroups(n.div_ceil(POINT_WORKGROUP_SIZE), 1, 1);
        };

        // Zero the accumulator (add_scaled_per_channel has no clear).
        encoder.clear_buffer(&self.small_acc, 0, None);
        grid(&self.downsample, encoder);
        for (b, a) in self.blur_jobs.iter().zip(self.accumulate.iter()) {
            blur_small(b, encoder);
            linear(a, self.n_small, encoder);
            if !finish_batch(encoder) { return false; }
        }
        grid(&self.upsample, encoder);
        linear(&self.mix1, self.n_full, encoder);
        linear(&self.mix2, self.n_full, encoder);
        encoder.copy_buffer_to_buffer(&self.buf_mix, 0, buf_b, 0, (self.n_full as u64) * 3 * 4);
        true
    }
}

/// Banded-chain diffusion mix state: owns the uploaded copy of a precomputed
/// whole-frame scattered field plus the params/bind group for one
/// `diffusion_mix_scattered` dispatch on the band's `buf_b`. Built per band
/// (the band height and `band_y0` change); the scattered field upload is the
/// only sizeable cost (~9 MB at 6144×8192 with d=8) and stays on the GPU
/// only for the duration of the band's command buffer.
#[cfg(feature = "wgpu-backend")]
struct DiffusionMixState {
    band_w: u32,
    band_h: u32,
    _scattered_buf: AuditBuffer,
    _params_buf: AuditBuffer,
    bg: AuditBindGroup,
    pipe: CachedPipelineRef,
}

#[cfg(feature = "wgpu-backend")]
fn build_diffusion_mix_state(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    sc: &crate::DiffusionScatteredGpu<'_>,
    band_w: u32,
    band_h: u32,
    buf_b: &AuditBuffer,
    backend: &WgpuBackend,
) -> DiffusionMixState {
    let grid_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    let pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/diffusion_mix_scattered.wgsl"),
        grid_layout,
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct MixParams {
        band_w: u32,
        band_h: u32,
        small_w: u32,
        small_h: u32,
        d: u32,
        band_y0: u32,
        p_s: f32,
        inv_d: f32,
        band_x0: u32,
        crop_x: u32,
        crop_y: u32,
        crop_w: u32,
    }
    // Upload only the global bilinear footprint, even when the host retained
    // a whole small field. Row slices avoid a second host crop allocation.
    let crop = crate::DiffusionScatteredGpu::required_crop(sc.small_w, sc.small_h,
        sc.d, sc.band_x0, sc.band_y0, band_w, band_h).expect("validated field footprint");
    let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("diff_mix_params"),
        contents: bytemuck::bytes_of(&MixParams {
            band_w,
            band_h,
            small_w: sc.small_w,
            small_h: sc.small_h,
            d: sc.d,
            band_y0: sc.band_y0,
            p_s: sc.p_s,
            inv_d: 1.0 / sc.d as f32,
            band_x0: sc.band_x0,
            crop_x: crop[0],
            crop_y: crop[1],
            crop_w: crop[2],
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let scattered_buf = device.census_create_buffer(&wgpu::BufferDescriptor {
        label: Some("diff_mix_scattered"),
        size: u64::from(crop[2]) * u64::from(crop[3]) * 12,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    if crop == sc.crop {
        queue.census_write_buffer(&scattered_buf, 0, bytemuck::cast_slice(sc.field));
    } else {
        for row in 0..crop[3] {
            let start = (((crop[1] - sc.crop[1] + row) as usize) * sc.crop[2] as usize
                + (crop[0] - sc.crop[0]) as usize) * 3;
            let len = crop[2] as usize * 3;
            queue.census_write_buffer(&scattered_buf, u64::from(row) * u64::from(crop[2]) * 12,
                bytemuck::cast_slice(&sc.field[start..start + len]));
        }
    }
    let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("diff_mix_bg"),
        layout: &pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: scattered_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: buf_b.as_entire_binding(),
            },
        ],
    });
    DiffusionMixState {
        band_w,
        band_h,
        _scattered_buf: scattered_buf,
        _params_buf: params_buf,
        bg,
        pipe,
    }
}

#[cfg(feature = "wgpu-backend")]
impl DiffusionMixState {
    /// Encode the upsample+mix dispatch onto `buf_b` (in place).
    fn encode_pass(&self, encoder: &mut AuditCommandEncoder) {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
            label: Some("diff_mix_scattered"),
            timestamp_writes: None,
        });
        pass.set_pipeline(&self.pipe.pipeline);
        pass.set_bind_group(0, &self.bg, &[]);
        pass.dispatch_workgroups(self.band_w.div_ceil(16), self.band_h.div_ceil(16), 1);
    }
}

#[cfg(feature = "wgpu-backend")]
struct SimpleBlurState {
    _dst: AuditBuffer,
    blur: BlurJob,
    blur_pipe_h: CachedPipelineRef,
    blur_pipe_v: CachedPipelineRef,
    n_bytes: u64,
}

#[cfg(feature = "wgpu-backend")]
fn build_simple_blur_state(
    device: &wgpu::Device,
    sigma: f32,
    width: u32,
    height: u32,
    src: &AuditBuffer,
    mid: &AuditBuffer,
    label: &str,
    backend: &WgpuBackend,
) -> SimpleBlurState {
    let n_bytes = (width as u64) * (height as u64) * 3 * 4;
    let dst = device.census_create_buffer(&wgpu::BufferDescriptor {
        label: Some(&format!("{label}_blur_dst")),
        size: n_bytes,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        mapped_at_creation: false,
    });

    let blur_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    let blur_pipe_h = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
        blur_layout,
    );
    let blur_pipe_v = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
        blur_layout,
    );

    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct BlurParams {
        width: u32,
        height: u32,
        radius: u32,
        _pad: u32,
    }
    let sigma = sigma.max(0.01);
    let radius = fir_blur_radius(sigma);
    let kernel_size = (2 * radius + 1) as usize;
    let two_sigma_sq = 2.0 * (sigma as f64) * (sigma as f64);
    let r_i32 = radius as i32;
    let mut kernel = Vec::with_capacity(kernel_size);
    for k in 0..kernel_size {
        let x = (k as i32 - r_i32) as f64;
        kernel.push((-x * x / two_sigma_sq).exp());
    }
    let ksum: f64 = kernel.iter().sum();
    let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / ksum) as f32).collect();
    let kernel_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some(&format!("{label}_blur_kernel")),
        contents: bytemuck::cast_slice(&kernel_f32),
        usage: wgpu::BufferUsages::STORAGE,
    });
    let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some(&format!("{label}_blur_params")),
        contents: bytemuck::bytes_of(&BlurParams {
            width,
            height,
            radius,
            _pad: 0,
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let bg_h = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some(&format!("{label}_blur_h_bg")),
        layout: &blur_pipe_h.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: src.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: kernel_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 3,
                resource: mid.as_entire_binding(),
            },
        ],
    });
    let bg_v = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some(&format!("{label}_blur_v_bg")),
        layout: &blur_pipe_v.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: mid.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: kernel_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 3,
                resource: dst.as_entire_binding(),
            },
        ],
    });

    SimpleBlurState {
        _dst: dst,
        blur: BlurJob {
            _kernel_buf: kernel_buf,
            _params_buf: params_buf,
            bg_h,
            bg_v,
        },
        blur_pipe_h,
        blur_pipe_v,
        n_bytes,
    }
}

#[cfg(feature = "wgpu-backend")]
impl SimpleBlurState {
    fn encode_passes(
        &self,
        encoder: &mut AuditCommandEncoder,
        wg_xy: (u32, u32),
        dst_main: &AuditBuffer,
    ) {
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("simple_blur_h"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.blur_pipe_h.pipeline);
            pass.set_bind_group(0, &self.blur.bg_h, &[]);
            pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("simple_blur_v"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.blur_pipe_v.pipeline);
            pass.set_bind_group(0, &self.blur.bg_v, &[]);
            pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
        }
        encoder.copy_buffer_to_buffer(&self._dst, 0, dst_main, 0, self.n_bytes);
    }
}

#[cfg(feature = "wgpu-backend")]
struct HighlightBoostState {
    _buffers: Vec<AuditBuffer>,
    reductions: Vec<(DispatchJob, u32)>,
    boost: DispatchJob,
    n_values: u32,
}

#[cfg(feature = "wgpu-backend")]
fn build_highlight_boost_state(
    device: &wgpu::Device,
    hp: &crate::HighlightBoostGpuParams,
    n_pixels: u32,
    img: &AuditBuffer,
    backend: &WgpuBackend,
) -> HighlightBoostState {
    let n_values = n_pixels * 3;
    let mut counts = Vec::new();
    let mut values = n_values;
    loop {
        let blocks = values.div_ceil(2048).max(1);
        counts.push(blocks);
        if blocks == 1 {
            break;
        }
        values = blocks;
    }

    let reduce_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/max_reduce.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    let boost_pipe = backend.cached_pipeline(
        if hp.headroom_ev != 0.0 { include_str!("../../spektrafilm-shaders/wgsl/halation_headroom.wgsl") }
        else { include_str!("../../spektrafilm-shaders/wgsl/highlight_boost.wgsl") },
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );

    let mut buffers: Vec<AuditBuffer> = counts
        .iter()
        .enumerate()
        .map(|(i, &c)| {
            device.census_create_buffer(&wgpu::BufferDescriptor {
                label: Some(&format!("highlight_reduce_{i}")),
                size: (c as u64) * 4,
                usage: wgpu::BufferUsages::STORAGE,
                mapped_at_creation: false,
            })
        })
        .collect();

    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct ReduceParams {
        n_values: u32,
        _pad: [u32; 7],
    }

    let mut reductions = Vec::with_capacity(counts.len());
    for (i, &blocks) in counts.iter().enumerate() {
        let pass_values = if i == 0 { n_values } else { counts[i - 1] };
        let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some(&format!("highlight_reduce_params_{i}")),
            contents: bytemuck::bytes_of(&ReduceParams {
                n_values: pass_values,
                _pad: [0; 7],
            }),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let input = if i == 0 { img } else { &buffers[i - 1] };
        let output = &buffers[i];
        let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some(&format!("highlight_reduce_bg_{i}")),
            layout: &reduce_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: input.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: output.as_entire_binding(),
                },
            ],
        });
        reductions.push((
            DispatchJob {
                _params_buf: params_buf,
                pipeline: reduce_pipe.clone(),
                bg,
            },
            blocks,
        ));
    }

    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct BoostParams {
        n_values: u32,
        boost_ev: f32,
        boost_range: f32,
        protect_ev: f32,
        headroom_ev: f32,
        headroom_from_ev: f32,
        headroom_to_ev: f32,
        _pad: f32,
    }
    let boost_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("highlight_boost_params"),
        contents: bytemuck::bytes_of(&BoostParams {
            n_values,
            boost_ev: hp.boost_ev,
            boost_range: hp.boost_range,
            protect_ev: hp.protect_ev,
            headroom_ev: hp.headroom_ev,
            headroom_from_ev: hp.headroom_from_ev,
            headroom_to_ev: hp.headroom_to_ev,
            _pad: 0.0,
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    if let Some(maximum) = hp.max_raw_override {
        reductions.clear();
        buffers.push(device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("highlight_global_max"), contents: bytemuck::bytes_of(&maximum),
            usage: wgpu::BufferUsages::STORAGE,
        }));
    }
    let final_max = buffers.last().expect("highlight reduction has output");
    let boost_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("highlight_boost_bg"),
        layout: &boost_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: boost_params.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: final_max.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: img.as_entire_binding(),
            },
        ],
    });

    HighlightBoostState {
        _buffers: buffers,
        reductions,
        boost: DispatchJob {
            _params_buf: boost_params,
            pipeline: boost_pipe,
            bg: boost_bg,
        },
        n_values,
    }
}

#[cfg(feature = "wgpu-backend")]
impl HighlightBoostState {
    fn encode_passes(&self, encoder: &mut AuditCommandEncoder) {
        for (job, blocks) in &self.reductions {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("highlight_reduce"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&job.pipeline.pipeline);
            pass.set_bind_group(0, &job.bg, &[]);
            pass.dispatch_workgroups(*blocks, 1, 1);
        }
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
            label: Some("highlight_boost"),
            timestamp_writes: None,
        });
        pass.set_pipeline(&self.boost.pipeline.pipeline);
        pass.set_bind_group(0, &self.boost.bg, &[]);
        pass.dispatch_workgroups(self.n_values.div_ceil(POINT_WORKGROUP_SIZE), 1, 1);
    }
}

#[cfg(feature = "wgpu-backend")]
struct InplaceUnaryState {
    job: DispatchJob,
}

#[cfg(feature = "wgpu-backend")]
impl InplaceUnaryState {
    fn encode_passes(&self, encoder: &mut AuditCommandEncoder, n_pixels: u32) {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
            label: Some("inplace_unary"),
            timestamp_writes: None,
        });
        pass.set_pipeline(&self.job.pipeline.pipeline);
        pass.set_bind_group(0, &self.job.bg, &[]);
        pass.dispatch_workgroups(n_pixels.div_ceil(POINT_WORKGROUP_SIZE), 1, 1);
    }
}

#[cfg(feature = "wgpu-backend")]
fn build_log_to_linear_state(
    device: &wgpu::Device,
    n_pixels: u32,
    scale: f32,
    img: &AuditBuffer,
    backend: &WgpuBackend,
) -> InplaceUnaryState {
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct Params {
        n_pixels: u32,
        scale: f32,
        _pad: [u32; 2],
    }
    let pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/log_to_linear_scaled.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("print_log_to_linear_params"),
        contents: bytemuck::bytes_of(&Params {
            n_pixels,
            scale,
            _pad: [0; 2],
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("print_log_to_linear_bg"),
        layout: &pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: img.as_entire_binding(),
            },
        ],
    });
    InplaceUnaryState {
        job: DispatchJob {
            _params_buf: params_buf,
            pipeline: pipe,
            bg,
        },
    }
}

#[cfg(feature = "wgpu-backend")]
fn build_linear_to_log_state(
    device: &wgpu::Device,
    n_pixels: u32,
    img: &AuditBuffer,
    backend: &WgpuBackend,
) -> InplaceUnaryState {
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct Params {
        n_pixels: u32,
        _pad: [u32; 7],
    }
    let pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/linear_to_log10_outer.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("print_linear_to_log_params"),
        contents: bytemuck::bytes_of(&Params {
            n_pixels,
            _pad: [0; 7],
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("print_linear_to_log_bg"),
        layout: &pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: img.as_entire_binding(),
            },
        ],
    });
    InplaceUnaryState {
        job: DispatchJob {
            _params_buf: params_buf,
            pipeline: pipe,
            bg,
        },
    }
}

#[cfg(feature = "wgpu-backend")]
fn build_post_scan_state(
    device: &wgpu::Device,
    n_pixels: u32,
    output_cctf_encoding: bool,
    img: &AuditBuffer,
    backend: &WgpuBackend,
) -> InplaceUnaryState {
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct Params {
        n_pixels: u32,
        output_cctf_encoding: u32,
        _pad: [u32; 2],
    }
    let pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/post_scan.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("post_scan_params"),
        contents: bytemuck::bytes_of(&Params {
            n_pixels,
            output_cctf_encoding: u32::from(output_cctf_encoding),
            _pad: [0; 2],
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("post_scan_bg"),
        layout: &pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: img.as_entire_binding(),
            },
        ],
    });
    InplaceUnaryState {
        job: DispatchJob {
            _params_buf: params_buf,
            pipeline: pipe,
            bg,
        },
    }
}

/// Pre-built DIR coupler passes — owns scratch buffers, kernel buffers,
/// and bind groups so they live for the encoder.
///
/// CPU equivalent: `spektrafilm_model::couplers::apply_density_correction`.
/// Encodes per-pixel matmul → two Gaussian blurs of the correction →
/// weighted lerp via `add_scaled` → in-place subtract from log_raw via
/// `add_scaled(scale=-1)` → re-interpolation of `density_curves_0`.
#[cfg(feature = "wgpu-backend")]
struct DirState {
    _buf_correction: AuditBuffer, // matmul output → final correction (after lerp)
    _buf_gaussian: AuditBuffer,   // core blur output (tail blurs reuse it as temp)
    _buf_mix: AuditBuffer,        // 3-Gaussian exponential-tail mixture accumulator
    matmul: DispatchJob,
    /// Exponential tail as a sum of 3 Gaussians (the CPU
    /// `exponential_filter_channel` fit). Each blurs buf_correction into
    /// buf_gaussian (a temp), then is folded into buf_mix with its amplitude.
    blur_tail: [BlurJob; 3],
    tail_accumulate: [DispatchJob; 3], // buf_mix (+)= amp_i * buf_gaussian
    blur_gaussian: BlurJob,            // core Gaussian → buf_gaussian
    lerp_clear: DispatchJob,      // buf_correction = (1-w) * buf_gaussian
    lerp_accumulate: DispatchJob, // buf_correction += w * buf_mix (tail)
    subtract: DispatchJob,        // buf_b -= buf_correction
    density_curve_0: DispatchJob, // re-interp density curves
    blur_pipe_h: CachedPipelineRef,
    blur_pipe_v: CachedPipelineRef,
    /// Buffers we own that the bind groups reference (log_exp, curves) —
    /// kept alive for the encoder's lifetime.
    _owned: Vec<AuditBuffer>,
}

#[cfg(feature = "wgpu-backend")]
fn build_dir_state(
    device: &wgpu::Device,
    dp: &crate::DirCouplersGpuParams<'_>,
    width: u32,
    height: u32,
    buf_a: &AuditBuffer,
    buf_b: &AuditBuffer,
    backend: &WgpuBackend,
) -> DirState {
    let n_pixels = (width as usize) * (height as usize);
    let img_bytes = (n_pixels * 3 * 4) as u64;

    let mk_buf = |label: &str| {
        device.census_create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: img_bytes,
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        })
    };
    // buf_correction holds the matmul output, then is overwritten by the
    // tail blur (in-place via the V pass of a separable blur — H and V
    // are separate compute passes so wgpu does NOT see the input/output
    // as aliasing). buf_gaussian holds the first blur's output.
    // buf_mix holds the weighted lerp result that ultimately gets
    // subtracted from log_raw.
    let buf_correction = mk_buf("dir_correction");
    let buf_gaussian = mk_buf("dir_gaussian");
    let buf_mix = mk_buf("dir_mix");

    // ── 1. dir_matmul ─────────────────────────────────────────────────
    let matmul_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/dir_matmul.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct DirMatmulParams {
        n_pixels: u32,
        positive: u32,
        _pad0: u32,
        _pad1: u32,
        density_max: [f32; 4],
        m_row0: [f32; 4],
        m_row1: [f32; 4],
        m_row2: [f32; 4],
    }
    let m = &dp.couplers_matrix_scaled;
    let matmul_params = DirMatmulParams {
        n_pixels: n_pixels as u32,
        positive: if dp.is_positive { 1 } else { 0 },
        _pad0: 0,
        _pad1: 0,
        density_max: [dp.density_max[0], dp.density_max[1], dp.density_max[2], 0.0],
        m_row0: [m[0][0], m[0][1], m[0][2], 0.0],
        m_row1: [m[1][0], m[1][1], m[1][2], 0.0],
        m_row2: [m[2][0], m[2][1], m[2][2], 0.0],
    };
    let matmul_params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("dir_matmul_params"),
        contents: bytemuck::bytes_of(&matmul_params),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let matmul_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("dir_matmul_bg"),
        layout: &matmul_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: matmul_params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: buf_a.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: buf_correction.as_entire_binding(),
            },
        ],
    });
    let matmul = DispatchJob {
        _params_buf: matmul_params_buf,
        pipeline: matmul_pipe,
        bg: matmul_bg,
    };

    // ── 2. Two Gaussian blurs of the correction ───────────────────────
    let blur_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    let blur_pipe_h = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
        blur_layout,
    );
    let blur_pipe_v = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
        blur_layout,
    );

    // Buffer reuse scheme (no extra full-frame buffers vs. the old
    // single-Gaussian-tail version):
    //   * buf_correction — matmul output; read as the blur source by every
    //     Gaussian below, then overwritten by the final weighted lerp.
    //   * buf_gaussian  — temp for each of the 3 exponential-tail blurs
    //     (folded into buf_mix before the next overwrites it), then the core
    //     Gaussian blur output.
    //   * buf_mix       — the accumulated 3-Gaussian exponential tail.
    //   * buf_a         — separable-blur mid scratch (density_cmy is dead
    //     once the matmul consumed it; the final density_curve_0 rewrites it).
    let make_blur_job =
        |sigma: f32, src: &AuditBuffer, out: &AuditBuffer, label: &str| -> BlurJob {
            let sigma = sigma.max(0.01);
            let radius = fir_blur_radius(sigma);
            let kernel_size = (2 * radius + 1) as usize;
            let sigma_f64 = sigma as f64;
            let two_sigma_sq = 2.0 * sigma_f64 * sigma_f64;
            let r_i32 = radius as i32;
            let mut kernel = Vec::with_capacity(kernel_size);
            for k in 0..kernel_size {
                let x = (k as i32 - r_i32) as f64;
                kernel.push((-x * x / two_sigma_sq).exp());
            }
            let sum: f64 = kernel.iter().sum();
            let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / sum) as f32).collect();

            let kernel_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some(&format!("dir_blur_kernel_{label}")),
                contents: bytemuck::cast_slice(&kernel_f32),
                usage: wgpu::BufferUsages::STORAGE,
            });
            #[repr(C)]
            #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
            struct BlurParams {
                width: u32,
                height: u32,
                radius: u32,
                _pad: u32,
            }
            let params = BlurParams {
                width,
                height,
                radius,
                _pad: 0,
            };
            let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some(&format!("dir_blur_params_{label}")),
                contents: bytemuck::bytes_of(&params),
                usage: wgpu::BufferUsages::UNIFORM,
            });
            let bg_h = device.audit_create_bind_group(&AuditBindGroupDescriptor {
                label: Some(&format!("dir_blur_h_{label}")),
                layout: &blur_pipe_h.layout,
                entries: &[
                    AuditBindGroupEntry {
                        binding: 0,
                        resource: params_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 1,
                        resource: src.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 2,
                        resource: kernel_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 3,
                        resource: buf_a.as_entire_binding(),
                    }, // mid
                ],
            });
            let bg_v = device.audit_create_bind_group(&AuditBindGroupDescriptor {
                label: Some(&format!("dir_blur_v_{label}")),
                layout: &blur_pipe_v.layout,
                entries: &[
                    AuditBindGroupEntry {
                        binding: 0,
                        resource: params_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 1,
                        resource: buf_a.as_entire_binding(),
                    }, // mid
                    AuditBindGroupEntry {
                        binding: 2,
                        resource: kernel_buf.as_entire_binding(),
                    },
                    AuditBindGroupEntry {
                        binding: 3,
                        resource: out.as_entire_binding(),
                    },
                ],
            });
            BlurJob {
                _kernel_buf: kernel_buf,
                _params_buf: params_buf,
                bg_h,
                bg_v,
            }
        };
    // ── 2. Exponential tail as a sum of 3 Gaussians ───────────────────
    // CPU `exponential_filter_channel` (spektrafilm-math/gaussian.rs)
    // approximates the isotropic exponential PSF `exp(-r/decay)` with the
    // Python `_EXPONENTIAL_GAUSSIAN_FITS[3]` mixture:
    //     tail = Σᵢ ampᵢ · gaussian_blur(correction, ratioᵢ · tail_px)
    // The amplitudes sum to 0.9999 by design — do NOT renormalize, so the
    // result stays bit-comparable with the CPU reference. All three tail
    // blurs read buf_correction and write buf_gaussian as a temp; each is
    // folded into buf_mix (below) before the next blur overwrites it.
    const TAIL_FIT: [(f32, f32); 3] =
        [(0.1633, 0.5360), (0.6496, 1.5236), (0.1870, 2.7684)];
    let blur_tail = [
        make_blur_job(
            TAIL_FIT[0].1 * dp.diffusion_tail_px,
            &buf_correction,
            &buf_gaussian,
            "tail0",
        ),
        make_blur_job(
            TAIL_FIT[1].1 * dp.diffusion_tail_px,
            &buf_correction,
            &buf_gaussian,
            "tail1",
        ),
        make_blur_job(
            TAIL_FIT[2].1 * dp.diffusion_tail_px,
            &buf_correction,
            &buf_gaussian,
            "tail2",
        ),
    ];
    // Core Gaussian of the correction → buf_gaussian. Dispatched AFTER the
    // tail blurs have been accumulated, so reusing buf_gaussian is safe.
    let blur_gaussian = make_blur_job(
        dp.diffusion_size_px,
        &buf_correction,
        &buf_gaussian,
        "gaussian",
    );

    // ── 3. add_scaled passes (tail mixture + final lerp + subtract) ────
    let add_scaled_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/add_scaled.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct AddScaledParams {
        n_pixels: u32,
        scale: f32,
        clear_first: u32,
        _pad: u32,
    }
    // dst = (clear_first ? 0 : dst) + scale * src. All src/dst pairs below
    // are distinct buffers, so there is no aliasing.
    let make_add_scaled = |label: &str,
                           scale: f32,
                           clear_first: u32,
                           src: &AuditBuffer,
                           dst: &AuditBuffer|
     -> DispatchJob {
        let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some(label),
            contents: bytemuck::bytes_of(&AddScaledParams {
                n_pixels: n_pixels as u32,
                scale,
                clear_first,
                _pad: 0,
            }),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some(label),
            layout: &add_scaled_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: src.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: dst.as_entire_binding(),
                },
            ],
        });
        DispatchJob {
            _params_buf: params_buf,
            pipeline: add_scaled_pipe.clone(),
            bg,
        }
    };

    // buf_mix = Σᵢ ampᵢ · tail_blurᵢ  (accumulate the 3-Gaussian tail).
    let tail_accumulate = [
        make_add_scaled("dir_tail_acc0", TAIL_FIT[0].0, 1, &buf_gaussian, &buf_mix),
        make_add_scaled("dir_tail_acc1", TAIL_FIT[1].0, 0, &buf_gaussian, &buf_mix),
        make_add_scaled("dir_tail_acc2", TAIL_FIT[2].0, 0, &buf_gaussian, &buf_mix),
    ];

    let w = dp.diffusion_tail_weight;
    // Final correction = (1-w)·core + w·tail, written into buf_correction
    // (the matmul output is fully consumed by the blurs, so it is free).
    let lerp_clear = make_add_scaled("dir_lerp_clear", 1.0 - w, 1, &buf_gaussian, &buf_correction);
    let lerp_accumulate = make_add_scaled("dir_lerp_acc", w, 0, &buf_mix, &buf_correction);
    // buf_b -= correction.
    let subtract = make_add_scaled("dir_subtract", -1.0, 0, &buf_correction, &buf_b);

    // ── 5. Final density curve re-interp using density_curves_0 ───────
    let density_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/density_curve_interp.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    let log_exp_f32: Vec<f32> = dp.log_exposure.iter().map(|&v| v as f32).collect();
    let curves_f32: Vec<f32> = dp
        .density_curves_0
        .iter()
        .flat_map(|r| r.iter().map(|&v| if v.is_nan() { 0.0 } else { v as f32 }))
        .collect();
    let log_exp_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("dir_density_log_exp"),
        contents: bytemuck::cast_slice(&log_exp_f32),
        usage: wgpu::BufferUsages::STORAGE,
    });
    let curves_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("dir_density_curves_0"),
        contents: bytemuck::cast_slice(&curves_f32),
        usage: wgpu::BufferUsages::STORAGE,
    });
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct DensityParams {
        width: u32,
        height: u32,
        k: u32,
        uniform_grid: u32,
        gamma_inv: [f32; 3],
        _pad: f32,
    }
    let density_params = DensityParams {
        width,
        height,
        k: dp.log_exposure.len() as u32,
        uniform_grid: if is_uniform(dp.log_exposure) { 1 } else { 0 },
        gamma_inv: [(1.0 / dp.gamma_factor) as f32; 3],
        _pad: 0.0,
    };
    let density_params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("dir_density_params"),
        contents: bytemuck::bytes_of(&density_params),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let density_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("dir_density_bg"),
        layout: &density_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: density_params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: buf_b.as_entire_binding(),
            }, // input: corrected log_raw
            AuditBindGroupEntry {
                binding: 2,
                resource: log_exp_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 3,
                resource: curves_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 4,
                resource: buf_a.as_entire_binding(),
            }, // output: corrected density_cmy
        ],
    });
    // Keep log_exp_buf / curves_buf alive via the DispatchJob's
    // _params_buf isn't ideal — pack them into the state instead.
    let density_curve_0 = DispatchJob {
        _params_buf: density_params_buf,
        pipeline: density_pipe,
        bg: density_bg,
    };

    // The two storage buffers (log_exp, curves) need to outlive the
    // encoder. Stuff them somewhere — extend DirState with a small
    // owner Vec.
    DirState {
        _buf_correction: buf_correction,
        _buf_gaussian: buf_gaussian,
        _buf_mix: buf_mix,
        matmul,
        blur_tail,
        tail_accumulate,
        blur_gaussian,
        lerp_clear,
        lerp_accumulate,
        subtract,
        density_curve_0,
        blur_pipe_h,
        blur_pipe_v,
        _owned: vec![log_exp_buf, curves_buf],
    }
}

#[cfg(feature = "wgpu-backend")]
impl DirState {
    fn encode_passes_batched(
        &self,
        encoder: &mut AuditCommandEncoder,
        n_pixels: u32,
        workgroup_size: u32,
        wg_xy: (u32, u32),
        finish_batch: &mut dyn FnMut(&mut AuditCommandEncoder) -> bool,
    ) -> bool {
        let dispatch_linear = |enc: &mut AuditCommandEncoder, job: &DispatchJob| {
            let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("dir_linear"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&job.pipeline.pipeline);
            pass.set_bind_group(0, &job.bg, &[]);
            pass.dispatch_workgroups(n_pixels.div_ceil(workgroup_size), 1, 1);
        };
        let dispatch_blur = |enc: &mut AuditCommandEncoder, job: &BlurJob,
            finish_batch: &mut dyn FnMut(&mut AuditCommandEncoder) -> bool| -> bool {
            {
                let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("dir_blur_h"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipe_h.pipeline);
                pass.set_bind_group(0, &job.bg_h, &[]);
                pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
            }
            // Full-resolution DIR can exceed the map deadline when both
            // FIR axes share a batch. Preserve the intermediate on GPU and
            // fence the horizontal axis separately; no radius/math changes.
            if !finish_batch(enc) { return false; }
            {
                let mut pass = enc.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("dir_blur_v"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipe_v.pipeline);
                pass.set_bind_group(0, &job.bg_v, &[]);
                pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
            }
            true
        };

        dispatch_linear(encoder, &self.matmul);
        // Exponential tail: 3 Gaussian blurs of the correction, each folded
        // into the buf_mix accumulator before the next overwrites buf_gaussian.
        for i in 0..3 {
            if !dispatch_blur(encoder, &self.blur_tail[i], finish_batch) { return false; } // buf_correction → buf_gaussian
            dispatch_linear(encoder, &self.tail_accumulate[i]); // buf_mix (+)= amp_i·buf_gaussian
            if !finish_batch(encoder) { return false; }
        }
        if !dispatch_blur(encoder, &self.blur_gaussian, finish_batch) { return false; } // core: buf_correction → buf_gaussian
        dispatch_linear(encoder, &self.lerp_clear); // buf_correction = (1-w)·buf_gaussian
        dispatch_linear(encoder, &self.lerp_accumulate); // buf_correction += w·buf_mix
        dispatch_linear(encoder, &self.subtract); // buf_b -= buf_correction
        dispatch_linear(encoder, &self.density_curve_0); // buf_b → buf_a (corrected)
        true
    }
}

/// Pre-built unsharp mask pass — owns the blur and the combine dispatch.
///
/// CPU equivalent: `spektrafilm_model::diffusion::apply_unsharp_mask`.
/// The combine shader writes to a temp buffer (avoids same-pass
/// aliasing); `encode_passes` then `copy_buffer_to_buffer`s the result
/// back into buf_b so the rest of the pipeline doesn't need to know
/// about the temporary.
#[cfg(feature = "wgpu-backend")]
struct UnsharpState {
    blur: BlurJob,
    combine: DispatchJob,
    blur_pipe_h: CachedPipelineRef,
    blur_pipe_v: CachedPipelineRef,
    out_buf: AuditBuffer, // combine target; copied back to buf_b at the end
}

#[cfg(feature = "wgpu-backend")]
fn build_unsharp_state(
    device: &wgpu::Device,
    up: &crate::UnsharpGpuParams,
    width: u32,
    height: u32,
    buf_a: &AuditBuffer,
    buf_b: &AuditBuffer,
    backend: &WgpuBackend,
) -> UnsharpState {
    let n_pixels = (width as usize) * (height as usize);
    let img_bytes = (n_pixels * 3 * 4) as u64;

    // Output buffer for the combine pass. Cleared each render — fine,
    // it's only used between the combine dispatch and the copy_buffer.
    let out_buf = device.census_create_buffer(&wgpu::BufferDescriptor {
        label: Some("unsharp_out"),
        size: img_bytes,
        usage: wgpu::BufferUsages::STORAGE
            | wgpu::BufferUsages::COPY_DST
            | wgpu::BufferUsages::COPY_SRC,
        mapped_at_creation: false,
    });

    // ── Blur (buf_b → buf_a (mid) → out_buf as the blurred destination
    // tmp). We re-use out_buf for two purposes: it first holds the
    // blurred image, then the combine overwrites it with the final
    // sharpened image.
    let blur_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    let blur_pipe_h = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
        blur_layout,
    );
    let blur_pipe_v = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
        blur_layout,
    );

    let sigma = up.sigma_px.max(0.01);
    let radius = fir_blur_radius(sigma);
    let kernel_size = (2 * radius + 1) as usize;
    let sigma_f64 = sigma as f64;
    let two_sigma_sq = 2.0 * sigma_f64 * sigma_f64;
    let r_i32 = radius as i32;
    let mut kernel = Vec::with_capacity(kernel_size);
    for k in 0..kernel_size {
        let x = (k as i32 - r_i32) as f64;
        kernel.push((-x * x / two_sigma_sq).exp());
    }
    let sum: f64 = kernel.iter().sum();
    let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / sum) as f32).collect();

    let kernel_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("unsharp_blur_kernel"),
        contents: bytemuck::cast_slice(&kernel_f32),
        usage: wgpu::BufferUsages::STORAGE,
    });
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct BlurParams {
        width: u32,
        height: u32,
        radius: u32,
        _pad: u32,
    }
    let params = BlurParams {
        width,
        height,
        radius,
        _pad: 0,
    };
    let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("unsharp_blur_params"),
        contents: bytemuck::bytes_of(&params),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    // Need a separate buffer for blur output since out_buf is the
    // combine destination. Use a small fresh allocation.
    let blur_dst = device.census_create_buffer(&wgpu::BufferDescriptor {
        label: Some("unsharp_blur_dst"),
        size: img_bytes,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let bg_h = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("unsharp_blur_h_bg"),
        layout: &blur_pipe_h.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: buf_b.as_entire_binding(),
            }, // src
            AuditBindGroupEntry {
                binding: 2,
                resource: kernel_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 3,
                resource: buf_a.as_entire_binding(),
            }, // mid
        ],
    });
    let bg_v = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("unsharp_blur_v_bg"),
        layout: &blur_pipe_v.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: buf_a.as_entire_binding(),
            }, // mid
            AuditBindGroupEntry {
                binding: 2,
                resource: kernel_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 3,
                resource: blur_dst.as_entire_binding(),
            }, // blurred output
        ],
    });
    let blur = BlurJob {
        _kernel_buf: kernel_buf,
        _params_buf: params_buf,
        bg_h,
        bg_v,
    };

    // ── Combine: out = (1+amount)*orig - amount*blurred
    let combine_pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/unsharp_combine.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct CombineParams {
        n_pixels: u32,
        amount: f32,
        _pad0: u32,
        _pad1: u32,
    }
    let combine_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("unsharp_combine_params"),
        contents: bytemuck::bytes_of(&CombineParams {
            n_pixels: n_pixels as u32,
            amount: up.amount,
            _pad0: 0,
            _pad1: 0,
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let combine_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("unsharp_combine_bg"),
        layout: &combine_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: combine_params.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: buf_b.as_entire_binding(),
            }, // a = original
            AuditBindGroupEntry {
                binding: 2,
                resource: blur_dst.as_entire_binding(),
            }, // b = blurred
            AuditBindGroupEntry {
                binding: 3,
                resource: out_buf.as_entire_binding(),
            }, // out = sharpened
        ],
    });
    let combine = DispatchJob {
        _params_buf: combine_params,
        pipeline: combine_pipe,
        bg: combine_bg,
    };

    // Drop blur_dst's strong ref into UnsharpState via an owned vec.
    // We need to keep it alive for the encoder lifetime; tucking it
    // into the BlurJob is awkward, so use a separate field.
    let mut state = UnsharpState {
        blur,
        combine,
        blur_pipe_h,
        blur_pipe_v,
        out_buf,
    };
    // Leak the blur output into the combine's owned buffers list by
    // creating it inside the state; here we tuck it into a global
    // owner. Simpler: keep it alive via a sidecar field.
    // (Reusing _params_buf would be confusing; just attach as another
    // implicit slot.)
    let _ = blur_dst; // moved into bind groups, lives via wgpu's Arc
    // AuditBuffer is Arc-backed under the hood — the bind groups keep
    // it alive. Nothing to leak here.
    let _ = &mut state;
    state
}

#[cfg(feature = "wgpu-backend")]
impl UnsharpState {
    fn encode_passes(
        &self,
        encoder: &mut AuditCommandEncoder,
        n_pixels: u32,
        workgroup_size: u32,
        wg_xy: (u32, u32),
        buf_b: &AuditBuffer,
        img_bytes: u64,
    ) {
        // Blur H + V.
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("unsharp_blur_h"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.blur_pipe_h.pipeline);
            pass.set_bind_group(0, &self.blur.bg_h, &[]);
            pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
        }
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("unsharp_blur_v"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.blur_pipe_v.pipeline);
            pass.set_bind_group(0, &self.blur.bg_v, &[]);
            pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
        }
        // Combine.
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("unsharp_combine"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.combine.pipeline.pipeline);
            pass.set_bind_group(0, &self.combine.bg, &[]);
            pass.dispatch_workgroups(n_pixels.div_ceil(workgroup_size), 1, 1);
        }
        // Copy sharpened result back into buf_b so the downstream
        // readback sees it without needing to know we used a temp.
        encoder.copy_buffer_to_buffer(&self.out_buf, 0, buf_b, 0, img_bytes);
    }
}

/// Pre-built glare pass — owns the noise-gen dispatch, optional blur,
/// and the additive RGB apply.
///
/// CPU equivalent:
/// `spektrafilm_model::glare::{compute_random_glare_amount,
/// add_glare_with_amount}`. Operates on buf_b (the RGB output of
/// scan_spectral) in place; the lognormal noise is held in a fresh
/// scratch buffer, blurred via the existing 3-channel separable
/// Gaussian.
#[cfg(feature = "wgpu-backend")]
struct GlareState {
    _scratch: AuditBuffer,
    gen_dispatch: DispatchJob,
    blur: Option<BlurJob>,
    apply_dispatch: DispatchJob,
    blur_pipe_h: CachedPipelineRef,
    blur_pipe_v: CachedPipelineRef,
}

#[cfg(feature = "wgpu-backend")]
fn build_glare_state(
    device: &wgpu::Device,
    gp: &crate::GlareGpuParams,
    width: u32,
    height: u32,
    buf_a: &AuditBuffer,
    buf_b: &AuditBuffer,
    backend: &WgpuBackend,
) -> GlareState {
    build_noise_state(device, gp, width, height, buf_a, buf_b, backend, false, true)
}

#[cfg(feature = "wgpu-backend")]
fn build_noise_state(
    device: &wgpu::Device, gp: &crate::GlareGpuParams, width: u32, height: u32,
    buf_a: &AuditBuffer, buf_b: &AuditBuffer, backend: &WgpuBackend,
    grain_microstructure: bool, monochrome: bool,
) -> GlareState {
    let n_pixels = (width as usize) * (height as usize);
    let img_bytes = (n_pixels * 3 * 4) as u64;

    let scratch = device.census_create_buffer(&wgpu::BufferDescriptor {
        label: Some("glare_scratch"),
        size: img_bytes,
        usage: wgpu::BufferUsages::STORAGE
            | wgpu::BufferUsages::COPY_DST
            | wgpu::BufferUsages::COPY_SRC,
        mapped_at_creation: false,
    });

    // ── Noise generation ──────────────────────────────────────────────
    let gen_pipe = backend.cached_pipeline(
        if grain_microstructure { include_str!("../../spektrafilm-shaders/wgsl/grain_clump_gen.wgsl") }
        else { include_str!("../../spektrafilm-shaders/wgsl/glare_gen.wgsl") },
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct GenParams {
        n_pixels: u32,
        base_seed: u32,
        mu: f32,
        sigma: f32,
        // Matches the WGSL `Params.pixel_offset` tail field (struct is
        // 16-byte-rounded, so 3 pad words follow).
        pixel_offset: u32,
        monochrome: u32,
        tile_width: u32,
        full_width: u32,
    }
    let gen_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("glare_gen_params"),
        contents: bytemuck::bytes_of(&GenParams {
            n_pixels: n_pixels as u32,
            base_seed: gp.base_seed,
            mu: gp.mu,
            sigma: gp.sigma,
            pixel_offset: gp.pixel_offset,
            monochrome: monochrome as u32,
            tile_width: width,
            full_width: gp.full_width,
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let gen_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("glare_gen_bg"),
        layout: &gen_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: gen_params.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: scratch.as_entire_binding(),
            },
        ],
    });
    let gen_dispatch = DispatchJob {
        _params_buf: gen_params,
        pipeline: gen_pipe,
        bg: gen_bg,
    };

    // ── Optional blur (separable, in place on scratch via buf_a mid) ──
    let blur_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    let blur_pipe_h = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
        blur_layout,
    );
    let blur_pipe_v = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
        blur_layout,
    );
    let blur = if gp.blur_px > 0.0 {
        let sigma = gp.blur_px.max(0.01);
        let radius = fir_blur_radius(sigma);
        let kernel_size = (2 * radius + 1) as usize;
        let sigma_f64 = sigma as f64;
        let two_sigma_sq = 2.0 * sigma_f64 * sigma_f64;
        let r_i32 = radius as i32;
        let mut kernel = Vec::with_capacity(kernel_size);
        for k in 0..kernel_size {
            let x = (k as i32 - r_i32) as f64;
            kernel.push((-x * x / two_sigma_sq).exp());
        }
        let sum: f64 = kernel.iter().sum();
        let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / sum) as f32).collect();

        let kernel_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("glare_blur_kernel"),
            contents: bytemuck::cast_slice(&kernel_f32),
            usage: wgpu::BufferUsages::STORAGE,
        });
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct BlurParams {
            width: u32,
            height: u32,
            radius: u32,
            _pad: u32,
        }
        let params = BlurParams {
            width,
            height,
            radius,
            _pad: 0,
        };
        let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("glare_blur_params"),
            contents: bytemuck::bytes_of(&params),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let bg_h = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("glare_blur_h_bg"),
            layout: &blur_pipe_h.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: scratch.as_entire_binding(),
                }, // src (read)
                AuditBindGroupEntry {
                    binding: 2,
                    resource: kernel_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: buf_a.as_entire_binding(),
                }, // mid
            ],
        });
        let bg_v = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("glare_blur_v_bg"),
            layout: &blur_pipe_v.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_a.as_entire_binding(),
                }, // mid (read)
                AuditBindGroupEntry {
                    binding: 2,
                    resource: kernel_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: scratch.as_entire_binding(),
                }, // back into scratch (separate pass — no aliasing)
            ],
        });
        Some(BlurJob {
            _kernel_buf: kernel_buf,
            _params_buf: params_buf,
            bg_h,
            bg_v,
        })
    } else {
        None
    };

    // ── Apply: image[c] += glare_amount * rgb_offset[c] ───────────────
    let apply_pipe = backend.cached_pipeline(
        if grain_microstructure { include_str!("../../spektrafilm-shaders/wgsl/grain_clump_apply.wgsl") }
        else { include_str!("../../spektrafilm-shaders/wgsl/glare_apply.wgsl") },
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct ApplyParams {
        n_pixels: u32,
        _pad0: u32,
        _pad1: u32,
        _pad2: u32,
        offset: [f32; 4],
    }
    let apply_params = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("glare_apply_params"),
        contents: bytemuck::bytes_of(&ApplyParams {
            n_pixels: n_pixels as u32,
            _pad0: 0,
            _pad1: 0,
            _pad2: 0,
            offset: [gp.rgb_offset[0], gp.rgb_offset[1], gp.rgb_offset[2], 0.0],
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    let apply_bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("glare_apply_bg"),
        layout: &apply_pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: apply_params.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: scratch.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: buf_b.as_entire_binding(),
            },
        ],
    });
    let apply_dispatch = DispatchJob {
        _params_buf: apply_params,
        pipeline: apply_pipe,
        bg: apply_bg,
    };

    GlareState {
        _scratch: scratch,
        gen_dispatch,
        blur,
        apply_dispatch,
        blur_pipe_h,
        blur_pipe_v,
    }
}

#[cfg(feature = "wgpu-backend")]
impl GlareState {
    fn encode_passes(
        &self,
        encoder: &mut AuditCommandEncoder,
        n_pixels: u32,
        workgroup_size: u32,
        wg_xy: (u32, u32),
    ) {
        // 1. Noise generation.
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("glare_gen"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.gen_dispatch.pipeline.pipeline);
            pass.set_bind_group(0, &self.gen_dispatch.bg, &[]);
            pass.dispatch_workgroups(n_pixels.div_ceil(workgroup_size), 1, 1);
        }
        // 2. Optional blur.
        if let Some(b) = &self.blur {
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("glare_blur_h"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipe_h.pipeline);
                pass.set_bind_group(0, &b.bg_h, &[]);
                pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
            }
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("glare_blur_v"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipe_v.pipeline);
                pass.set_bind_group(0, &b.bg_v, &[]);
                pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
            }
        }
        // 3. Apply onto buf_b.
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("glare_apply"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.apply_dispatch.pipeline.pipeline);
            pass.set_bind_group(0, &self.apply_dispatch.bg, &[]);
            pass.dispatch_workgroups(n_pixels.div_ceil(workgroup_size), 1, 1);
        }
    }
}

/// Pre-built output gamut compression pass — a single per-pixel dispatch
/// in place on `buf_b` (the scan output), between glare and unsharp,
/// matching the CPU scanning order. Owns the uniform + `C_max` table
/// buffers so they outlive the encoder.
///
/// CPU equivalent: `OutputGamutCompress::compress`.
#[cfg(feature = "wgpu-backend")]
struct GamutState {
    _cmax_buf: AuditBuffer,
    dispatch: DispatchJob,
}

#[cfg(feature = "wgpu-backend")]
fn build_gamut_state(
    device: &wgpu::Device,
    gp: &crate::GamutGpuParams<'_>,
    buf_b: &AuditBuffer,
    n_pixels: u32,
    backend: &WgpuBackend,
) -> GamutState {
    let pipe = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gamut_compress.wgsl"),
        &[
            wgpu::BufferBindingType::Uniform,
            wgpu::BufferBindingType::Storage { read_only: true },
            wgpu::BufferBindingType::Storage { read_only: false },
        ],
    );
    #[repr(C)]
    #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
    struct GamutParams {
        n_pixels: u32,
        mode: u32,
        n_l: u32,
        n_h: u32,
        knee: [f32; 4],
        lightness: [f32; 4],
        lspace: [f32; 4],
    }
    let lightness = match gp.lightness {
        Some([t, l, p]) => [t, l, p, 1.0],
        None => [0.0, 1.0, 1.0, 0.0],
    };
    let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("gamut_params"),
        contents: bytemuck::bytes_of(&GamutParams {
            n_pixels,
            mode: gp.mode,
            n_l: gp.n_l,
            n_h: gp.n_h,
            knee: [gp.knee[0], gp.knee[1], gp.knee[2], 0.0],
            lightness,
            lspace: [gp.l_white, gp.l_min, gp.l_max, 0.0],
        }),
        usage: wgpu::BufferUsages::UNIFORM,
    });
    // aces_rgc carries no table — bind a 1-element placeholder (the shader
    // never reads it on that mode; wgpu requires a non-empty binding).
    let cmax_f32: Vec<f32> = if gp.cmax.is_empty() {
        vec![0.0]
    } else {
        gp.cmax.iter().map(|&v| v as f32).collect()
    };
    let cmax_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
        label: Some("gamut_cmax"),
        contents: bytemuck::cast_slice(&cmax_f32),
        usage: wgpu::BufferUsages::STORAGE,
    });
    let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
        label: Some("gamut_bg"),
        layout: &pipe.layout,
        entries: &[
            AuditBindGroupEntry {
                binding: 0,
                resource: params_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 1,
                resource: cmax_buf.as_entire_binding(),
            },
            AuditBindGroupEntry {
                binding: 2,
                resource: buf_b.as_entire_binding(),
            },
        ],
    });
    GamutState {
        _cmax_buf: cmax_buf,
        dispatch: DispatchJob {
            _params_buf: params_buf,
            pipeline: pipe,
            bg,
        },
    }
}

#[cfg(feature = "wgpu-backend")]
impl GamutState {
    fn encode_passes(
        &self,
        encoder: &mut AuditCommandEncoder,
        n_pixels: u32,
        workgroup_size: u32,
    ) {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
            label: Some("gamut_compress"),
            timestamp_writes: None,
        });
        pass.set_pipeline(&self.dispatch.pipeline.pipeline);
        pass.set_bind_group(0, &self.dispatch.bg, &[]);
        pass.dispatch_workgroups(n_pixels.div_ceil(workgroup_size), 1, 1);
    }
}

/// Owns the original log-RGB copy for donor grain, including every binding
/// retaining it. One extra interleaved f32 RGB image (12 B/pixel), phase-local.
#[cfg(feature = "wgpu-backend")]
struct NeutralGrainState {
    _original: AuditBuffer,
    fill: DispatchJob,
    restore: DispatchJob,
}
#[cfg(feature = "wgpu-backend")]
fn build_neutral_grain_state(
    device: &wgpu::Device, reference: &crate::NeutralGrainReference,
    width: u32, height: u32, image: &AuditBuffer, backend: &WgpuBackend,
) -> NeutralGrainState {
    let original=device.census_create_buffer(&wgpu::BufferDescriptor {
        label:Some("neutral_grain_original_log_rgb"),
        size:crate::NeutralGrainReference::workspace_bytes(width,height).expect("validated GPU shape"),
        usage:wgpu::BufferUsages::STORAGE, mapped_at_creation:false,
    });
    #[repr(C)]
    #[derive(Copy,Clone,bytemuck::Pod,bytemuck::Zeroable)]
    struct Params {
        n_pixels:u32, restore:u32, _pad:[u32;2],
        density:[f32;4], baseline:[f32;4], amount:f32, _pad2:[f32;3],
    }
    let make=|restore:u32| {
        let pipeline=backend.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/neutral_grain_residual.wgsl"),
            &[wgpu::BufferBindingType::Uniform,
              wgpu::BufferBindingType::Storage{read_only:false},
              wgpu::BufferBindingType::Storage{read_only:false}],
        );
        let params=device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label:Some("neutral_grain_params"),
            contents:bytemuck::bytes_of(&Params {
                n_pixels:width*height,restore,_pad:[0;2],
                density:[reference.density[0],reference.density[1],reference.density[2],0.],
                baseline:[reference.baseline[0],reference.baseline[1],reference.baseline[2],0.],
                amount:reference.amount,_pad2:[0.;3],
            }), usage:wgpu::BufferUsages::UNIFORM,
        });
        let bg=device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label:Some("neutral_grain_bg"),layout:&pipeline.layout,
            entries:&[
                AuditBindGroupEntry{binding:0,resource:params.as_entire_binding()},
                AuditBindGroupEntry{binding:1,resource:original.as_entire_binding()},
                AuditBindGroupEntry{binding:2,resource:image.as_entire_binding()},
            ],
        });
        DispatchJob{_params_buf:params,pipeline,bg}
    };
    let fill=make(0);let restore=make(1);
    NeutralGrainState{_original:original,fill,restore}
}
#[cfg(feature = "wgpu-backend")]
impl NeutralGrainState {
    fn encode(&self,encoder:&mut AuditCommandEncoder,restore:bool,n_pixels:u32) {
        let job=if restore {&self.restore} else {&self.fill};
        let mut pass=encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
            label:Some(if restore {"neutral_grain_restore"} else {"neutral_grain_fill"}),
            timestamp_writes:None,
        });
        pass.set_pipeline(&job.pipeline.pipeline);pass.set_bind_group(0,&job.bg,&[]);
        pass.dispatch_workgroups(n_pixels.div_ceil(POINT_WORKGROUP_SIZE),1,1);
    }
}

/// Pre-built grain pass — owns the grain compute bind group plus the
/// optional post-blur pipeline state.
///
/// CPU equivalent: `spektrafilm_model::grain::apply_grain_to_density`.
/// Operates in place on `buf_a` (density_cmy). When `grain_blur > 0.4`,
/// follows up with a separable Gaussian blur that uses `buf_b` as mid
/// (free at this point — DIR's `density_curve_0` already overwrote
/// buf_b's role).
#[cfg(feature = "wgpu-backend")]
struct GrainState {
    microstructure: Option<GlareState>,
    grain_dispatch: DispatchJob,
    blur: Option<BlurJob>,
    blur_pipe_h: CachedPipelineRef,
    blur_pipe_v: CachedPipelineRef,
    /// Keeps the sublayer curve/param storage buffers (axis, layers,
    /// sl_params) alive for the exact CPU sublayer model; `None` for the
    /// simple per-channel model.
    _sublayer_bufs: Option<[AuditBuffer; 3]>,
}

#[cfg(feature = "wgpu-backend")]
fn build_grain_state(
    device: &wgpu::Device,
    gp: &crate::GrainGpuParams,
    width: u32,
    height: u32,
    buf_a: &AuditBuffer,
    buf_b: &AuditBuffer,
    backend: &WgpuBackend,
) -> GrainState {
    let n_pixels = (width as usize) * (height as usize);

    // Grain pass: exact CPU sublayer model when `gp.sublayers` is set,
    // otherwise the simple per-channel model. Both run in place on buf_a.
    let (grain_dispatch, sublayer_bufs) = if let Some(sl) = gp.sublayers.as_ref() {
        let grain_pipe = backend.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/grain_layers.wgsl"),
            &[
                wgpu::BufferBindingType::Uniform,
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: true },
                wgpu::BufferBindingType::Storage { read_only: false },
            ],
        );
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct GrainLayersParams {
            n_pixels: u32,
            base_seed: u32,
            monochrome: u32,
            n_points: u32,
            sign: f32,
            _pad0: f32,
            _pad1: f32,
            _pad2: f32,
            density_min: [f32; 4],
            grain_uniformity: [f32; 4],
            // Matches the WGSL `Params.pixel_offset` tail field (struct is
            // 16-byte-rounded, so 3 pad words follow).
            pixel_offset: u32,
            tile_width: u32,
            full_width: u32,
            _pad3: u32,
        }
        let params = GrainLayersParams {
            n_pixels: n_pixels as u32,
            base_seed: gp.base_seed,
            monochrome: gp.monochrome as u32,
            n_points: sl.n_points,
            sign: sl.sign,
            _pad0: 0.0,
            _pad1: 0.0,
            _pad2: 0.0,
            density_min: [gp.density_min[0], gp.density_min[1], gp.density_min[2], 0.0],
            grain_uniformity: [
                gp.grain_uniformity[0],
                gp.grain_uniformity[1],
                gp.grain_uniformity[2],
                0.0,
            ],
            pixel_offset: gp.pixel_offset,
            tile_width: width,
            full_width: gp.full_width,
            _pad3: 0,
        };
        let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grain_layers_params"),
            contents: bytemuck::bytes_of(&params),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let axis_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grain_layers_axis"),
            contents: bytemuck::cast_slice(&sl.axis),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let layers_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grain_layers_layers"),
            contents: bytemuck::cast_slice(&sl.layers),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let sl_params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grain_layers_sl_params"),
            contents: bytemuck::cast_slice(&sl.sl_params[..]),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("grain_layers_bg"),
            layout: &grain_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: axis_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 2,
                    resource: layers_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: sl_params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 4,
                    resource: buf_a.as_entire_binding(),
                },
            ],
        });
        (
            DispatchJob {
                _params_buf: params_buf,
                pipeline: grain_pipe,
                bg,
            },
            Some([axis_buf, layers_buf, sl_params_buf]),
        )
    } else {
        let grain_pipe = backend.cached_pipeline(
            include_str!("../../spektrafilm-shaders/wgsl/grain.wgsl"),
            &[
                wgpu::BufferBindingType::Uniform,
                wgpu::BufferBindingType::Storage { read_only: false },
            ],
        );
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct GrainParams {
            n_pixels: u32,
            base_seed: u32,
            n_sub_layers: u32,
            monochrome: u32,
            density_min: [f32; 4],
            density_max: [f32; 4],
            n_particles_per_pixel: [f32; 4],
            grain_uniformity: [f32; 4],
            // Matches the WGSL `Params.pixel_offset` tail field (struct is
            // 16-byte-rounded, so 3 pad words follow).
            pixel_offset: u32,
            tile_width: u32,
            full_width: u32,
            _pad: u32,
        }
        let params = GrainParams {
            n_pixels: n_pixels as u32,
            base_seed: gp.base_seed,
            n_sub_layers: gp.n_sub_layers.max(1),
            monochrome: gp.monochrome as u32,
            density_min: [gp.density_min[0], gp.density_min[1], gp.density_min[2], 0.0],
            density_max: [gp.density_max[0], gp.density_max[1], gp.density_max[2], 0.0],
            n_particles_per_pixel: [
                gp.n_particles_per_pixel[0],
                gp.n_particles_per_pixel[1],
                gp.n_particles_per_pixel[2],
                0.0,
            ],
            grain_uniformity: [
                gp.grain_uniformity[0],
                gp.grain_uniformity[1],
                gp.grain_uniformity[2],
                0.0,
            ],
            pixel_offset: gp.pixel_offset,
            tile_width: width,
            full_width: gp.full_width,
            _pad: 0,
        };
        let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grain_params"),
            contents: bytemuck::bytes_of(&params),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let bg = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("grain_bg"),
            layout: &grain_pipe.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_a.as_entire_binding(),
                },
            ],
        });
        (
            DispatchJob {
                _params_buf: params_buf,
                pipeline: grain_pipe,
                bg,
            },
            None,
        )
    };

    // Optional post-blur: blur buf_a → buf_a in place (H writes mid=buf_b,
    // V reads buf_b writes buf_a, separate compute passes so no aliasing).
    let blur_layout = &[
        wgpu::BufferBindingType::Uniform,
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: true },
        wgpu::BufferBindingType::Storage { read_only: false },
    ];
    let blur_pipe_h = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_h.wgsl"),
        blur_layout,
    );
    let blur_pipe_v = backend.cached_pipeline(
        include_str!("../../spektrafilm-shaders/wgsl/gaussian_blur_v.wgsl"),
        blur_layout,
    );
    let blur = if gp.grain_blur > 0.0 {
        let sigma = gp.grain_blur.max(0.01);
        let radius = fir_blur_radius(sigma);
        let kernel_size = (2 * radius + 1) as usize;
        let sigma_f64 = sigma as f64;
        let two_sigma_sq = 2.0 * sigma_f64 * sigma_f64;
        let r_i32 = radius as i32;
        let mut kernel = Vec::with_capacity(kernel_size);
        for k in 0..kernel_size {
            let x = (k as i32 - r_i32) as f64;
            kernel.push((-x * x / two_sigma_sq).exp());
        }
        let sum: f64 = kernel.iter().sum();
        let kernel_f32: Vec<f32> = kernel.into_iter().map(|v| (v / sum) as f32).collect();

        let kernel_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grain_blur_kernel"),
            contents: bytemuck::cast_slice(&kernel_f32),
            usage: wgpu::BufferUsages::STORAGE,
        });
        #[repr(C)]
        #[derive(Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
        struct BlurParams {
            width: u32,
            height: u32,
            radius: u32,
            _pad: u32,
        }
        let params = BlurParams {
            width,
            height,
            radius,
            _pad: 0,
        };
        let params_buf = device.census_create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grain_blur_params"),
            contents: bytemuck::bytes_of(&params),
            usage: wgpu::BufferUsages::UNIFORM,
        });
        let bg_h = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("grain_blur_h_bg"),
            layout: &blur_pipe_h.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_a.as_entire_binding(),
                }, // src
                AuditBindGroupEntry {
                    binding: 2,
                    resource: kernel_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: buf_b.as_entire_binding(),
                }, // mid
            ],
        });
        let bg_v = device.audit_create_bind_group(&AuditBindGroupDescriptor {
            label: Some("grain_blur_v_bg"),
            layout: &blur_pipe_v.layout,
            entries: &[
                AuditBindGroupEntry {
                    binding: 0,
                    resource: params_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 1,
                    resource: buf_b.as_entire_binding(),
                }, // mid
                AuditBindGroupEntry {
                    binding: 2,
                    resource: kernel_buf.as_entire_binding(),
                },
                AuditBindGroupEntry {
                    binding: 3,
                    resource: buf_a.as_entire_binding(),
                }, // dst (in place)
            ],
        });
        Some(BlurJob {
            _kernel_buf: kernel_buf,
            _params_buf: params_buf,
            bg_h,
            bg_v,
        })
    } else {
        None
    };

    let microstructure = gp.microstructure.map(|[sigma, spread]| {
        let sigma_log = (1.0 + sigma * sigma).ln().sqrt();
        let noise = crate::GlareGpuParams {
            mu: -0.5 * sigma_log * sigma_log, sigma: sigma_log,
            blur_px: if spread > 0.4 { spread } else { 0.0 },
            base_seed: gp.base_seed.wrapping_add(0x51f15e),
            rgb_offset: gp.density_min, pixel_offset: gp.pixel_offset, full_width: gp.full_width,
        };
        // Density is buf_a; buf_b is free scratch at this stage.
        build_noise_state(device, &noise, width, height, buf_b, buf_a, backend, true, gp.monochrome)
    });
    GrainState {
        microstructure,
        grain_dispatch,
        blur,
        blur_pipe_h,
        blur_pipe_v,
        _sublayer_bufs: sublayer_bufs,
    }
}

#[cfg(feature = "wgpu-backend")]
impl GrainState {
    fn encode_passes(
        &self,
        encoder: &mut AuditCommandEncoder,
        n_pixels: u32,
        workgroup_size: u32,
        wg_xy: (u32, u32),
    ) {
        // Grain compute.
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("grain_compute"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.grain_dispatch.pipeline.pipeline);
            pass.set_bind_group(0, &self.grain_dispatch.bg, &[]);
            pass.dispatch_workgroups(n_pixels.div_ceil(workgroup_size), 1, 1);
        }
        if let Some(ms) = &self.microstructure {
            ms.encode_passes(encoder, n_pixels, workgroup_size, wg_xy);
        }
        // Optional post-blur.
        if let Some(b) = &self.blur {
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("grain_blur_h"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipe_h.pipeline);
                pass.set_bind_group(0, &b.bg_h, &[]);
                pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
            }
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("grain_blur_v"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(&self.blur_pipe_v.pipeline);
                pass.set_bind_group(0, &b.bg_v, &[]);
                pass.dispatch_workgroups(wg_xy.0, wg_xy.1, 1);
            }
        }
    }
}

/// Detects whether a 1D sequence is uniformly spaced (within tight tolerance).
#[cfg(feature = "wgpu-backend")]
fn is_uniform(xs: &[f64]) -> bool {
    if xs.len() < 3 {
        return true;
    }
    let step = (xs[xs.len() - 1] - xs[0]) / (xs.len() as f64 - 1.0);
    let tol = step.abs() * 1e-9 + 1e-12;
    for i in 1..xs.len() {
        let expected = xs[0] + (i as f64) * step;
        if (xs[i] - expected).abs() > tol {
            return false;
        }
    }
    true
}


#[cfg(all(test, feature = "wgpu-backend"))]
mod resident_dispatch_tests {
    #[test]
    fn cinestill_final_add_clamps_only_the_completed_halo() {
        let Some(gpu)=super::WgpuBackend::new() else {
            assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");
            return;
        };
        let mut params=Vec::new();
        for v in [2u32,0,0,0] { params.extend_from_slice(&v.to_le_bytes()); }
        for v in [0.3f32,0.03,0.003,0.] { params.extend_from_slice(&v.to_le_bytes()); }
        let halo=[-0.000004f32,2.,-1.,0.,-2.,3.];
        let source=[0.2f32,0.3,0.4,-0.1,0.5,0.6];
        let result=gpu.dispatch_compute(
            include_str!("../../spektrafilm-shaders/wgsl/halation_final_add.wgsl"),
            &[super::GpuBuffer::uniform(&params),
              super::GpuBuffer::storage_ro(bytemuck::cast_slice(&halo)),
              super::GpuBuffer::storage_rw(bytemuck::cast_slice(&source))],2,2);
        for i in 0..6 {
            let added=result[i]-source[i];
            assert!(added>=0.,"negative physical halo energy at {i}: {added}");
            let expected=source[i]+halo[i].max(0.)*[0.3f32,0.03,0.003][i%3];
            assert!((result[i]-expected).abs()<1e-7);
        }
        assert_eq!(result[3],source[3],"must not clamp the original exposure");
    }
    use super::*;

    fn glare_fence_fixture(data:&[f32],w:u32,h:u32)->crate::FilmChainParams<'_> {
        const AXIS:[f64;4]=[-4.,-2.,0.,2.];
        const CURVES:[[f64;3];4]=[[0.,0.,0.],[0.4,0.5,0.6],[1.,1.1,1.2],[2.,2.1,2.2]];
        const SPECTRAL:[[f64;3];81]=[[0.2,0.3,0.4];81];
        const BASE:[f64;81]=[0.;81];const LIGHT:[f64;81]=[1.;81];
        const MATRIX:[[f64;3];3]=[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]];
        crate::FilmChainParams {
            neutral_media:None,
            start_at:crate::ChainStart::Rgb,stage_batched:true,image:crate::ChainImage::F32(data,w,h),
            front:crate::FrontPass::Mallett2019{matrix:MATRIX},
            film_log_exposure:&AXIS,film_density_curves_normalized:&CURVES,film_gamma:1.,
            film_channel_density:&SPECTRAL,film_base_density:&BASE,print_illuminant:&LIGHT,
            print_sensitivity:&SPECTRAL,print_normalization_factor:0.05,print_log_exposure:&AXIS,
            print_density_curves:&CURVES,print_gamma:1.,print_channel_density:&SPECTRAL,
            print_base_density:&BASE,preflash:[0.01;3],viewing_illuminant:&LIGHT,
            scan_normalization:10.,scan_xyz_to_rgb:&MATRIX,bw_xyz_remap:None,scan_film:false,
            halation:Some(crate::HalationGpuParams{back_reflection:None,scatter_amount:0.25,scatter_core_px:0.6,
                scatter_tail_px:1.2,scatter_tail_weight:[0.2,0.3,0.4],halation_amount:0.3,
                halation_strength_avg:0.1,halation_a_tot:[0.03;3],halation_first_sigma_px:0.7,
                halation_n_bounces:3,halation_bounce_decay:0.5,halation_renormalize:true}),
            dir_couplers:Some(crate::DirCouplersGpuParams{couplers_matrix_scaled:[[0.01;3];3],
                density_max:[2.2;3],is_positive:false,diffusion_size_px:0.8,diffusion_tail_px:1.3,
                diffusion_tail_weight:0.2,density_curves_0:&CURVES,log_exposure:&AXIS,gamma_factor:1.}),
            grain:Some(crate::GrainGpuParams{density_min:[0.;3],density_max:[2.2;3],
                n_particles_per_pixel:[128.;3],grain_uniformity:[0.8;3],n_sub_layers:1,
                base_seed:42,grain_blur:0.65,microstructure:None,monochrome:false,
                pixel_offset:0,full_width:w,sublayers:None}),
            glare:None,gamut:None,unsharp:None,camera_lens_blur_px:Some(0.6),scanner_lens_blur_px:None,
            highlight_boost:None,diffusion:None,enlarger_diffusion:None,diffusion_scattered:None,
            enlarger_scattered:None,stop_after:crate::ChainStop::Full,print_exposure_scale:1.,
            output_cctf_encoding:false,defer_gamut_to_cpu:true,
        }
    }

    #[test]
    #[ignore = "requires serialized real Vulkan execution window"]
    fn real_gpu_empty_glare_fence_21_to_20_preserves_pixels_and_safe_checkpoints() {
        let gpu=WgpuBackend::new().expect("real GPU required, no CPU fallback or silent skip");
        let (w,h)=(19,13);let input:Vec<f32>=(0..w*h*3).map(|i|0.01+(i%127) as f32/91.).collect();
        // Explicit stage batching on a tiny fixture tests the actual schedule cheaply.
        let mut p=glare_fence_fixture(&input,w,h);
        let before=gpu.thread_submits();let mut old_checkpoints=0;
        let expected=gpu.run_film_chain_capture_controlled_impl::<true>(&p,None,&mut || {
            assert!(gpu.execution.try_lock().is_ok(),"checkpoint must be outside device lock");
            old_checkpoints+=1;true
        }).unwrap();
        assert_eq!(gpu.thread_submits()-before,21);
        assert_eq!(gpu.thread_resident_progress().unwrap().completed_batches,21);
        let before=gpu.thread_submits();let mut stages=Vec::new();
        let actual=gpu.run_film_chain_capture_controlled(&p,None,&mut || {
            assert!(gpu.execution.try_lock().is_ok());
            stages.push(gpu.thread_resident_progress().map(|v|v.stage));true
        }).unwrap();
        assert_eq!(gpu.thread_submits()-before,20);
        assert_eq!(actual.data,expected.data);assert!(actual.data.iter().all(|v|v.is_finite()));
        assert_eq!(stages.len(),old_checkpoints,"empty fence removal must keep cancellation opportunities");
        assert!(!stages.contains(&Some(ResidentStage::Glare)),"no fictitious completed GPU stage");
        assert_eq!(stages.iter().filter(|s|**s==Some(ResidentStage::Scan)).count(),2);
        let progress=gpu.thread_resident_progress().unwrap();
        assert_eq!((progress.stage,progress.completed_batches),(ResidentStage::Output,20));

        // Cancellation at the preserved empty-stage checkpoint must stop before final readback.
        let before=gpu.thread_submits();let mut scans=0;
        let cancelled=gpu.run_film_chain_capture_controlled(&p,None,&mut || {
            assert!(gpu.execution.try_lock().is_ok());
            if gpu.thread_resident_progress().is_some_and(|v|v.stage==ResidentStage::Scan) {scans+=1;}
            scans<2
        });
        assert!(cancelled.is_none());assert_eq!(gpu.thread_submits()-before,19);
        assert!(gpu.is_healthy());assert!(!gpu.completion_uncertain());assert!(gpu.execution.try_lock().is_ok());
        assert_eq!(gpu.run_film_chain_capture_controlled(&p,None,&mut ||true).unwrap().data,expected.data);
        let before=gpu.thread_submits();
        assert!(gpu.run_film_chain_capture_controlled(&p,None,&mut ||false).is_none());
        assert_eq!(gpu.thread_submits(),before);

        // A real glare dispatch still needs its completion fence and is unchanged.
        p.glare=Some(crate::GlareGpuParams{mu:-3.,sigma:0.2,blur_px:0.6,base_seed:19,
            rgb_offset:[0.02,0.03,0.01],pixel_offset:0,full_width:w});
        let before=gpu.thread_submits();
        let expected_glare=gpu.run_film_chain_capture_controlled_impl::<true>(&p,None,&mut ||true).unwrap();
        assert_eq!(gpu.thread_submits()-before,21);
        let before=gpu.thread_submits();
        assert_eq!(gpu.run_film_chain_capture_controlled(&p,None,&mut ||true).unwrap().data,expected_glare.data);
        assert_eq!(gpu.thread_submits()-before,21);
        // Small, unbatched preview retains its existing single-submit path too.
        p.stage_batched=false;p.glare=None;
        let before=gpu.thread_submits();
        let unbatched=gpu.run_film_chain_capture_controlled(&p,None,&mut ||true).unwrap();
        assert_eq!(gpu.thread_submits()-before,1);assert_eq!(unbatched.data,expected.data);
    }

    #[test]
    fn unconfirmed_submission_retires_on_every_unwind_boundary() {
        use std::sync::atomic::{AtomicBool, Ordering};
        for boundary in ["queue.submit", "map_async", "device.poll", "map error", "map timeout"] {
            let healthy=AtomicBool::new(true);
            let uncertain=AtomicBool::new(false);
            let result=std::panic::catch_unwind(|| {
                // Same guard is installed before the actual queue call.
                let _pending=SubmittedWork { healthy: &healthy, completion_uncertain: &uncertain, completed: false, #[cfg(feature = "resource-audit")] owner: None, #[cfg(feature = "resource-audit")] pending_writes: None };
                panic!("simulated {boundary}");
            });
            assert!(result.is_err());
            assert!(uncertain.load(Ordering::Acquire), "{boundary}");
            assert!(!healthy.load(Ordering::Acquire), "{boundary}");
        }
    }

    #[test]
    fn confirmed_maps_allow_cpu_failure_without_false_isolation() {
        use std::sync::atomic::{AtomicBool, Ordering};
        let healthy=AtomicBool::new(true);
        let uncertain=AtomicBool::new(false);
        let result=std::panic::catch_unwind(|| {
            let mut pending=SubmittedWork { healthy: &healthy, completion_uncertain: &uncertain, completed: false, #[cfg(feature = "resource-audit")] owner: None, #[cfg(feature = "resource-audit")] pending_writes: None };
            // This transition occurs only after ALL mapping callbacks succeed.
            pending.completed=true;
            panic!("simulated later readback or CPU conversion failure");
        });
        assert!(result.is_err());
        assert!(!uncertain.load(Ordering::Acquire));
        assert!(healthy.load(Ordering::Acquire));
    }

    #[test]
    fn pre_submit_failure_is_safe_and_uncertain_retirement_is_sticky() {
        use std::sync::atomic::{AtomicBool, Ordering};
        let healthy=AtomicBool::new(true);
        let uncertain=AtomicBool::new(false);
        let _=std::panic::catch_unwind(|| panic!("simulated allocation failure before submit guard"));
        assert!(!uncertain.load(Ordering::Acquire));
        assert!(healthy.load(Ordering::Acquire));
        drop(SubmittedWork { healthy: &healthy, completion_uncertain: &uncertain, completed: false, #[cfg(feature = "resource-audit")] owner: None, #[cfg(feature = "resource-audit")] pending_writes: None });
        drop(SubmittedWork { healthy: &healthy, completion_uncertain: &uncertain, completed: true, #[cfg(feature = "resource-audit")] owner: None, #[cfg(feature = "resource-audit")] pending_writes: None });
        assert!(uncertain.load(Ordering::Acquire));
        assert!(!healthy.load(Ordering::Acquire));
    }

    #[test]
    fn point_dispatch_respects_device_group_and_u32_limits() {
        let cap=65_535u32;
        let last=u64::from(cap)*u64::from(POINT_WORKGROUP_SIZE);
        assert_eq!(point_dispatch_groups(0,cap),None);
        assert_eq!(point_dispatch_groups(1,cap),Some(1));
        assert_eq!(point_dispatch_groups(256,cap),Some(1));
        assert_eq!(point_dispatch_groups(257,cap),Some(2));
        assert_eq!(point_dispatch_groups(last,cap),Some(cap));
        assert_eq!(point_dispatch_groups(last+1,cap),None);
        assert_eq!(point_dispatch_groups(1,0),None);
        assert_eq!(point_dispatch_groups(u64::from(u32::MAX)+1,u32::MAX),None);
        // Highlight boost launches one invocation per scalar, not per pixel.
        assert!(point_dispatch_groups((last/3)*3,cap).is_some());
        assert!(point_dispatch_groups((last/3+1)*3,cap).is_none());
    }

    #[test]
    fn mobile_256_and_512_limits_support_resident_kernels() {
        for maximum in [256,512,1024] {
            let mut limits=wgpu::Limits::default();
            limits.max_compute_invocations_per_workgroup=maximum;
            limits.max_compute_workgroup_size_x=maximum;
            assert!(supports_resident_workgroups(&limits));
        }
        let mut limits=wgpu::Limits::default();
        limits.max_compute_invocations_per_workgroup=128;
        assert!(!supports_resident_workgroups(&limits));
        limits.max_compute_invocations_per_workgroup=256;
        limits.max_compute_workgroup_size_x=128;
        assert!(!supports_resident_workgroups(&limits));
        limits.max_compute_workgroup_size_x=256;
        limits.max_compute_workgroup_size_y=8;
        assert!(!supports_resident_workgroups(&limits));
    }

    #[test]
    fn point_shader_width_matches_dispatch_and_all_modules_validate() {
        let dir=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../spektrafilm-shaders/wgsl");
        let expected=format!("@workgroup_size({POINT_WORKGROUP_SIZE})");
        let (mut points,mut spatial,mut reductions,mut gathers)=(0,0,0,0);
        for path in std::fs::read_dir(dir).unwrap().map(|p|p.unwrap().path()) {
            if !path.extension().is_some_and(|e|e=="wgsl") {continue;}
            let source=std::fs::read_to_string(&path).unwrap();
            if path.file_name().unwrap()=="max_reduce.wgsl" {
                assert!(source.contains("@workgroup_size(256)"));
                assert!(source.contains("wid.x * 2048u + local * 8u"));
                reductions+=1;
            } else if path.file_name().unwrap()=="film_density_gather.wgsl" {
                // Bounded thumbnail samples use their own 64-thread dispatcher.
                assert!(source.contains("@workgroup_size(64)"));
                assert!(source.contains("if i >= params.count { return; }"));
                gathers+=1;
            } else if source.contains("@workgroup_size(16, 16)") {spatial+=1;}
            else {assert!(source.contains(&expected),"{}",path.display());points+=1;}
            let module=wgpu::naga::front::wgsl::parse_str(&source).unwrap_or_else(|e|panic!("{}: {}",path.display(),e.emit_to_string(&source)));
            wgpu::naga::valid::Validator::new(wgpu::naga::valid::ValidationFlags::all(),wgpu::naga::valid::Capabilities::all())
                .validate(&module).unwrap_or_else(|e|panic!("{}: {}",path.display(),e.emit_to_string(&source)));
        }
        assert_eq!((points,spatial,reductions,gathers),(29,5,1,1));
    }
}

#[cfg(test)]
mod density_tail_tests {
    use super::decode_density_samples;
    use crate::FilmDensitySample;
    #[test]
    fn appended_samples_keep_ids_and_exact_values() {
        let samples = [FilmDensitySample { index: 9, x: 0, y: 0 }, FilmDensitySample { index: 3, x: 1, y: 1 }];
        let values = [-0.0f32, 0.25, 12., -1., 2., 3.];
        let actual = decode_density_samples(&samples, bytemuck::cast_slice(&values)).unwrap();
        assert_eq!(actual, vec![(9, [-0., 0.25, 12.]), (3, [-1., 2., 3.])]);
        assert_eq!(actual[0].1[0].to_bits(), (-0.0f32).to_bits());
        assert!(decode_density_samples(&samples, &[0; 12]).is_none());
        assert!(decode_density_samples(&samples, bytemuck::cast_slice(&[f32::NAN; 6])).is_none());
    }
    #[test]
    fn image_shaders_never_infer_pixel_count_from_extended_buffer() {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../spektrafilm-shaders/wgsl");
        for entry in std::fs::read_dir(dir).unwrap() {
            let path = entry.unwrap().path();
            if path.extension().is_some_and(|s| s == "wgsl") {
                assert!(!std::fs::read_to_string(&path).unwrap().contains("arrayLength"), "{} must preserve image-prefix bounds", path.display());
            }
        }
    }
}

#[cfg(all(test, feature = "resource-audit", feature = "wgpu-backend"))]
mod observed_owner_gpu_tests {
    use super::*;
    /// Requires a real supported adapter. An unavailable device fails rather
    /// than silently claiming GPU validation. Run explicitly, serially.
    #[test]
    #[ignore = "requires explicit actual-GPU validation window"]
    fn generic_dispatch_owner_scope_real_gpu() {
        let backend = WgpuBackend::new().expect("real GPU adapter required");
        let audit = crate::resource_audit::ThreadResourceAudit::begin().unwrap();
        static SHADER: &str = "@group(0) @binding(0) var<storage, read_write> data: array<f32>; @compute @workgroup_size(256) fn main(@builtin(global_invocation_id) id: vec3<u32>) { if (id.x < arrayLength(&data)) { data[id.x] = data[id.x] + 1.0; } }";
        for _ in 0..2 {
            let output = backend.dispatch_compute(SHADER, &[GpuBuffer {
                data: bytemuck::cast_slice(&[1.0f32, 2.0, 3.0]).to_vec(),
                binding_type: wgpu::BufferBindingType::Storage { read_only: false },
                usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
            }], 3, 0);
            assert_eq!(output, [2.0, 3.0, 4.0]);
        }
        let s = audit.finish();
        assert_eq!(s.observed_dispatch_owners.completed_scopes, 2);
        assert_eq!(s.observed_dispatch_owners.invalid_scopes, 0);
        assert_eq!(s.observed_dispatch_owners.uncertain_submission_scopes, 0);
        assert_eq!(s.observed_dispatch_owners.maximum_completed_scope_peak_bytes, Some(24));
        assert_eq!(s.cumulative_buffer_descriptor_bytes, 48);
        let owners = s.resident_buffer_owners;
        assert!(owners.valid && owners.quiescent);
        assert_eq!(owners.peak_descriptor_bytes, Some(24));
        assert_eq!(owners.live_descriptor_bytes, Some(0));
        assert_eq!(owners.completed_submissions, 2);
        assert_eq!(owners.uncertain_submissions, 0);
        assert_eq!(s.logical_live_owner_peak, None); assert_eq!(s.physical_driver_peak, None);
    }
    #[test]
    #[ignore = "requires explicit actual-GPU validation window"]
    fn multi_blur_resident_owners_real_gpu() {
        let backend = WgpuBackend::new().expect("real GPU adapter required");
        let audit = crate::resource_audit::ThreadResourceAudit::begin().unwrap();
        let input = ImageBuf { width: 4, height: 4, data: vec![0.25; 4 * 4 * 3] };
        let outputs = backend.gaussian_blur_multi_gpu(&input, &[0.5, 1.0, 2.0]);
        assert_eq!(outputs.len(), 3);
        for output in outputs { assert!(output.data.iter().all(|v| (*v as f64 - 0.25).abs() < 1e-5)); }
        let s = audit.finish(); let owners = s.resident_buffer_owners;
        assert!(owners.valid && owners.quiescent);
        assert_eq!(owners.completed_submissions, 1);
        assert_eq!(owners.uncertain_submissions, 0);
        assert_eq!(owners.live_descriptor_bytes, Some(0));
        // This fixture constructs every buffer before encoding; all descriptors
        // coexist, including per-sigma uniforms/kernels and all readbacks.
        assert_eq!(owners.peak_descriptor_bytes, Some(s.cumulative_buffer_descriptor_bytes));
        assert_eq!(s.logical_live_owner_peak, None); assert_eq!(s.physical_driver_peak, None);
    }

}

#[cfg(all(test,feature="wgpu-backend"))]
mod neutral_media_shader_tests {
    use super::*;
    fn f32s(bytes:&mut Vec<u8>,values:&[f32]) {bytes.extend_from_slice(bytemuck::cast_slice(values));}
    fn leading(n:u32)->Vec<u8> {let mut b=Vec::new();for x in [n,1,0]{b.extend_from_slice(&x.to_le_bytes());}b}
    #[test]
    #[ignore="requires serialized real Vulkan execution window"]
    fn neutral_print_shader_uses_fixed_gray_gain_and_additive_preflash() {
        let gpu=WgpuBackend::new().expect("real GPU required, no silent skip");
        let placeholder=[0f32;3];
        for rgb in [[0.1f32,0.18,0.5],[1e-10f32,1e-8,10000.]] {
        let log=rgb.map(|v|v.log10());
        for exposure in [1f32,4.] {
            let mut uniform=leading(1);f32s(&mut uniform,&[exposure]);
            f32s(&mut uniform,&[0.01,0.02,0.03,0.]);
            f32s(&mut uniform,&[1.,-1.,-4.,2.]);f32s(&mut uniform,&[0.8,1.,1.2,0.]);
            let out=gpu.dispatch_compute(include_str!("../../spektrafilm-shaders/wgsl/print_spectral.wgsl"),&[
                GpuBuffer::uniform(&uniform),GpuBuffer::storage_ro(bytemuck::cast_slice(&log)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),
                GpuBuffer::storage_rw(&[0u8;12])],1,6);
            for c in 0..3 {
                let raw_log=(0.1f64*0.18/(rgb[c] as f64)).log10().clamp(-4.,2.);
                let expected=(10f64.powf(raw_log)*exposure as f64*[0.8,1.,1.2][c]+[0.01,0.02,0.03][c]+1e-10).log10();
                assert!((out[c] as f64-expected).abs()<2e-6,"channel {c}: {} != {expected}",out[c]);
            }
        }
        }
    }
    #[test]
    #[ignore="requires serialized real Vulkan execution window"]
    fn neutral_scan_shader_log_rgb_and_negative_density_are_distinct() {
        let gpu=WgpuBackend::new().expect("real GPU required, no silent skip");
        let placeholder=[0f32;3];
        for mode in [1f32,2.] {
            let source=if mode==1. {[-10f32,-2.,-0.3,0.,0.5,1.]}else{[0.1f32,0.4,0.9,1.1,1.2,3.3]};
            let mut uniform=leading(2);f32s(&mut uniform,&[1.]);
            for column in [[1.,0.,0.,0.],[0.,1.,0.,0.],[0.,0.,1.,0.]]{f32s(&mut uniform,&column);}
            f32s(&mut uniform,&[1.,0.,0.,1.]);f32s(&mut uniform,&[mode,0.,0.,0.]);
            f32s(&mut uniform,&[0.1,0.2,0.3,0.]);f32s(&mut uniform,&[1.,2.,3.,1.]);f32s(&mut uniform,&[1.,2.,0.5,1.]);
            let out=gpu.dispatch_compute(include_str!("../../spektrafilm-shaders/wgsl/scan_spectral.wgsl"),&[
                GpuBuffer::uniform(&uniform),GpuBuffer::storage_ro(bytemuck::cast_slice(&source)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),
                GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),GpuBuffer::storage_ro(bytemuck::cast_slice(&placeholder)),
                GpuBuffer::storage_rw(&[0u8;24])],2,8);
            for i in 0..6 {
                let c=i%3;let expected=if mode==1. {(10f64.powf(source[i] as f64)-1e-10).max(0.)}
                else {((source[i] as f64-[0.1,0.2,0.3][c])/[1.,2.,3.][c]).clamp(0.,1.).powf([1.,2.,0.5][c])};
                assert!((out[i] as f64-expected).abs()<5e-6,"mode {mode} channel {i}: {} != {expected}",out[i]);
            }
        }
    }
}

#[cfg(all(test, feature="wgpu-backend"))]
mod resident_attempt_telemetry_tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};

    #[test]
    fn first_submit_unwind_is_reported_without_weakening_quarantine() {
        let healthy=AtomicBool::new(true);
        let uncertain=AtomicBool::new(false);
        let before=THREAD_SUBMITS.with(|v|v.get());
        let (result,failure)=observe_resident_attempt(|| {
            let _batch=ResidentBatchAttempt {stage:ResidentStage::Front,start:std::time::Instant::now(),completed:false};
            let _submission=SubmittedWork {healthy:&healthy,completion_uncertain:&uncertain,completed:false,
                #[cfg(feature="resource-audit")] owner:None,
                #[cfg(feature="resource-audit")] pending_writes:None};
            let _submit=failure_trace::Span::new(failure_trace::Step::Submit);
            // A driver panic before record_submit() must not need a fabricated count.
            panic!("synthetic first-submit unwind");
        });
        assert!(result.is_err());
        assert_eq!(THREAD_SUBMITS.with(|v|v.get()),before);
        let failure=failure.expect("first submit failure belongs to this attempt");
        assert_eq!(failure.stage,ResidentStage::Front);
        assert_eq!(failure.detail.kind,failure_trace::Kind::SubmitUnwind);
        assert!(failure.detail.submit_ms.is_finite() && failure.detail.submit_ms>=0.);
        assert!(uncertain.load(Ordering::Acquire));
        assert!(!healthy.load(Ordering::Acquire));
        assert!(THREAD_RESIDENT_FAILURE.with(|v|v.get()).is_none());
    }

    #[test]
    fn empty_and_rejected_attempts_cannot_inherit_old_failure() {
        let (_,old)=observe_resident_attempt(|| {
            let _batch=ResidentBatchAttempt {stage:ResidentStage::Scan,start:std::time::Instant::now(),completed:false};
            failure_trace::fail(failure_trace::Kind::MapTimeout);
            panic!("synthetic map timeout");
        });
        let old=old.unwrap();
        // Include stale data left by a different caller of the resident backend.
        THREAD_RESIDENT_FAILURE.with(|v|v.set(Some(old)));
        let (cpu,failure)=observe_resident_attempt(|| 7);
        assert_eq!(cpu.unwrap(),7);assert!(failure.is_none());
        THREAD_RESIDENT_FAILURE.with(|v|v.set(Some(old)));
        let calls=std::cell::Cell::new(0);
        let (rejected,failure)=observe_resident_attempt(|| only_when_adapter_allowed(false,|| {
            calls.set(calls.get()+1);Some(7)
        }));
        assert_eq!(rejected.unwrap(),None);assert_eq!(calls.get(),0);
        assert!(failure.is_none());
        assert!(THREAD_RESIDENT_FAILURE.with(|v|v.get()).is_none());
        assert_eq!(failure_trace::snapshot().kind,failure_trace::Kind::UnknownUnwind);
    }
}

#[cfg(all(test, feature="wgpu-backend"))]
mod adapter_filter_contract_tests {
    use super::only_when_adapter_allowed;
    #[test] fn rejected_adapter_never_requests_device() {
        let calls=std::cell::Cell::new(0);
        assert_eq!(only_when_adapter_allowed(false, || { calls.set(calls.get()+1); Some(7) }),None);
        assert_eq!(calls.get(),0);
        assert_eq!(only_when_adapter_allowed(true, || { calls.set(calls.get()+1); Some(7) }),Some(7));
        assert_eq!(calls.get(),1);
    }
}
