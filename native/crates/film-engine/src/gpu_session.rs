//! Explicitly activated, worker-confined Vulkan reuse inside one isolated helper.
//! Cached handles never constitute process-death or file-access completion proof.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase};
use crate::gpu_session_contract::{PipelineKey, SessionContract, StageTicket};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc,
    },
    thread::ThreadId,
    time::{Duration, Instant},
};
use wgpu::util::DeviceExt;
type R<T> = Result<T, String>;
const MAX_POOL_SLOTS: usize = 4096;
const MIB: u64 = 1024 * 1024;
fn failure() -> String {
    "GPU_SESSION_RECYCLE_REQUIRED".into()
}
fn contract<T>(result: crate::gpu_session_contract::ContractResult<T>) -> R<T> {
    result.map_err(|_| failure())
}
#[derive(Clone, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SessionStats {
    pub epoch: u64,
    pub prepaid_bytes: u64,
    pub fixed_bytes: u64,
    pub charged_bytes: u64,
    pub pipeline_slots: usize,
    pub pool_bytes: u64,
    pub pipeline_hits: u64,
    pub buffer_hits: u64,
    pub clear_submissions: u64,
}
#[derive(Clone)]
struct DeviceContext {
    device: wgpu::Device,
    queue: wgpu::Queue,
    error: Arc<AtomicBool>,
    info: wgpu::AdapterInfo,
    f64_cap: bool,
}
struct Slot {
    buffer: wgpu::Buffer,
    size: u64,
    usage: wgpu::BufferUsages,
    occupied: bool,
    transient: bool,
}
struct Stage {
    ticket: StageTicket,
    owner: ThreadId,
    compilation_allowance_bytes: u64,
}
struct Session {
    contract: SessionContract,
    fixed_bytes: u64,
    device: Option<DeviceContext>,
    pipelines: HashMap<PipelineKey, wgpu::ComputePipeline>,
    slots: Vec<Slot>,
    stage: Option<Stage>,
    live_contexts: usize,
    pipeline_hits: u64,
    buffer_hits: u64,
    clear_submissions: u64,
}
impl Session {
    fn healthy(&mut self) -> R<()> {
        if STOP.load(Ordering::Acquire) {
            self.contract.request_stop();
        }
        if self
            .device
            .as_ref()
            .is_some_and(|d| d.error.load(Ordering::Acquire))
        {
            self.contract.request_stop();
        }
        if self.contract.is_retired() {
            Err(failure())
        } else {
            Ok(())
        }
    }
    fn stats(&self) -> SessionStats {
        SessionStats {
            epoch: self.contract.epoch(),
            prepaid_bytes: self.contract.prepaid_bytes(),
            fixed_bytes: self.fixed_bytes,
            charged_bytes: self.contract.charged_bytes(),
            pipeline_slots: self.contract.pipeline_slots_charged(),
            pool_bytes: self.slots.iter().map(|s| s.size).sum(),
            pipeline_hits: self.pipeline_hits,
            buffer_hits: self.buffer_hits,
            clear_submissions: self.clear_submissions,
        }
    }
    fn check_stage(&mut self, ticket: StageTicket) -> R<()> {
        self.healthy()?;
        match &self.stage {
            Some(stage) if stage.ticket == ticket && stage.owner == std::thread::current().id() => {
                Ok(())
            }
            _ => Err(failure()),
        }
    }
}
static EPOCH: AtomicU64 = AtomicU64::new(0);
static STOP: AtomicBool = AtomicBool::new(false);
static SESSION: OnceLock<Mutex<Option<Arc<Mutex<Session>>>>> = OnceLock::new();
fn registry() -> &'static Mutex<Option<Arc<Mutex<Session>>>> {
    SESSION.get_or_init(|| Mutex::new(None))
}
fn current() -> R<Option<Arc<Mutex<Session>>>> {
    Ok(registry().lock().map_err(|_| failure())?.clone())
}
fn required(epoch: u64) -> R<Arc<Mutex<Session>>> {
    let state = current()?.ok_or_else(failure)?;
    if state.lock().map_err(|_| failure())?.contract.epoch() != epoch {
        return Err(failure());
    }
    Ok(state)
}
/// Test evidence comes from the actual retained device, not optional stage telemetry.
#[cfg(test)]
pub(crate) fn adapter_info_for_test(epoch:u64)->R<wgpu::AdapterInfo> {
    let state=required(epoch)?;
    let mut s=state.lock().map_err(|_|failure())?;
    s.healthy()?;
    s.device.as_ref().map(|d|d.info.clone()).ok_or_else(failure)
}
/// Only called after transport admission and retained Android memory prepayment.
/// A retired epoch is never replaced in-process: the owner must kill the helper.
pub(crate) fn activate(
    epoch: u64,
    prepaid_bytes: u64,
    fixed_bytes: u64,
    pipeline_limit: usize,
) -> R<()> {
    let mut registry = registry().lock().map_err(|_| failure())?;
    if registry.is_some() {
        retire_current();
        return Err(failure());
    }
    let state = contract(SessionContract::new(
        epoch,
        prepaid_bytes,
        fixed_bytes,
        pipeline_limit,
    ))?;
    *registry = Some(Arc::new(Mutex::new(Session {
        contract: state,
        fixed_bytes,
        device: None,
        pipelines: HashMap::new(),
        slots: Vec::new(),
        stage: None,
        live_contexts: 0,
        pipeline_hits: 0,
        buffer_hits: 0,
        clear_submissions: 0,
    })));
    EPOCH.store(epoch, Ordering::Release);
    Ok(())
}
pub(crate) fn grant_prepaid(epoch: u64, total: u64) -> R<()> {
    let state = required(epoch)?;
    let mut s = state.lock().map_err(|_| failure())?;
    s.healthy()?;
    contract(s.contract.grant_prepaid(total))
}
pub(crate) fn begin_frame(epoch: u64, generation: u64) -> R<()> {
    let state = required(epoch)?;
    let mut s = state.lock().map_err(|_| failure())?;
    s.healthy()?;
    contract(s.contract.begin_frame(generation))
}
pub(crate) fn begin_stage(ticket: StageTicket, compilation_allowance_bytes: u64) -> R<()> {
    let state = required(ticket.epoch)?;
    let mut s = state.lock().map_err(|_| failure())?;
    s.healthy()?;
    if compilation_allowance_bytes == 0 || s.live_contexts != 0 || s.stage.is_some() {
        return Err(failure());
    }
    contract(s.contract.begin_stage(ticket))?;
    s.stage = Some(Stage {
        ticket,
        owner: std::thread::current().id(),
        compilation_allowance_bytes,
    });
    Ok(())
}
/// Lock-free cancellation remains available during driver creation/compilation.
pub(crate) fn is_cancelled(epoch: u64) -> bool {
    epoch == 0 || EPOCH.load(Ordering::Acquire) != epoch || STOP.load(Ordering::Acquire)
}
/// Invalid or duplicate open cannot leave an earlier epoch ambiguously alive.
/// This is lock-free retirement only, never a claim that the helper has died.
pub(crate) fn retire_current() {
    if EPOCH.load(Ordering::Acquire) != 0 {
        STOP.store(true, Ordering::Release);
    }
}
pub(crate) fn request_stop(epoch: u64) -> R<()> {
    if epoch == 0 || EPOCH.load(Ordering::Acquire) != epoch {
        return Err(failure());
    }
    STOP.store(true, Ordering::Release);
    Ok(())
}
pub(crate) fn validate_fixed_reserve(epoch: u64, required_bytes: u64) -> R<()> {
    let state = required(epoch)?;
    let mut s = state.lock().map_err(|_| failure())?;
    s.healthy()?;
    if required_bytes > s.fixed_bytes {
        s.contract.request_stop();
        STOP.store(true, Ordering::Release);
        return Err(failure());
    }
    Ok(())
}
fn fence(device: &DeviceContext) -> R<()> {
    let (tx, rx) = mpsc::channel();
    device.queue.submit([]);
    device.queue.on_submitted_work_done(move || {
        let _ = tx.send(());
    });
    let started = Instant::now();
    loop {
        if STOP.load(Ordering::Acquire) || device.error.load(Ordering::Acquire) {
            return Err(failure());
        }
        let _ = device.device.poll(wgpu::Maintain::Poll);
        if rx.try_recv().is_ok() {
            return Ok(());
        }
        if started.elapsed() > Duration::from_secs(5) {
            device.error.store(true, Ordering::Release);
            return Err(failure());
        }
        std::thread::sleep(Duration::from_millis(1));
    }
}
/// Caller must first return from the stage, unmap buffers and close all file
/// handles. A queue fence plus zero live Context handles proves pool reuse only.
pub(crate) fn complete_stage(ticket: StageTicket) -> R<SessionStats> {
    let state = required(ticket.epoch)?;
    let device = {
        let mut s = state.lock().map_err(|_| failure())?;
        s.check_stage(ticket)?;
        if s.live_contexts != 0 {
            return Err(failure());
        }
        s.device.clone()
    };
    if let Some(device) = &device {
        if let Err(e) = fence(device) {
            let _ = request_stop(ticket.epoch);
            return Err(e);
        }
    }
    let mut s = state.lock().map_err(|_| failure())?;
    s.check_stage(ticket)?;
    contract(s.contract.complete_stage(ticket))?;
    s.stage = None;
    for slot in &mut s.slots {
        slot.occupied = false;
        slot.transient = false;
    }
    Ok(s.stats())
}
pub(crate) fn finish_frame(epoch: u64, generation: u64) -> R<SessionStats> {
    let state = required(epoch)?;
    let mut s = state.lock().map_err(|_| failure())?;
    s.healthy()?;
    if s.live_contexts != 0 || s.stage.is_some() {
        return Err(failure());
    }
    contract(s.contract.finish_frame(generation))?;
    Ok(s.stats())
}
/// A Runner-owned context token. Store it after all buffers so its Drop cannot
/// make the stage sealable while a Runner's resources are still alive.
pub(crate) struct Context {
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
    pub error: Arc<AtomicBool>,
    pub info: wgpu::AdapterInfo,
    pub f64_cap: bool,
    session: Option<(Arc<Mutex<Session>>, StageTicket)>,
}
impl Drop for Context {
    fn drop(&mut self) {
        if let Some((state, _)) = &self.session {
            if let Ok(mut s) = state.lock() {
                s.live_contexts = s.live_contexts.saturating_sub(1);
                if self.error.load(Ordering::Acquire) || std::thread::panicking() {
                    s.contract.request_stop();
                }
            }
        }
    }
}
fn create_device(
    largest: u64,
    storage_count: u32,
    allow_probe: bool,
    shared: bool,
) -> R<DeviceContext> {
    let step = trace::StepGuard::begin(1, GpuPhase::Device);
    let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
        backends: wgpu::Backends::VULKAN,
        ..Default::default()
    });
    let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
        power_preference: wgpu::PowerPreference::HighPerformance,
        compatible_surface: None,
        force_fallback_adapter: false,
    }))
    .ok_or("GPU_UNAVAILABLE_OR_LIMITS")?;
    step.finish();
    let info = adapter.get_info();
    if !allow_probe
        && !crate::adreno::eligible_adapter(
            info.vendor,
            &info.name,
            info.backend == wgpu::Backend::Vulkan,
            info.device_type == wgpu::DeviceType::IntegratedGpu,
        )
    {
        return Err("GPU_UNAVAILABLE_OR_LIMITS".into());
    }
    let available = adapter.limits();
    if largest > available.max_storage_buffer_binding_size as u64
        || largest > available.max_buffer_size
        || available.max_storage_buffers_per_shader_stage < storage_count
        || available.max_compute_workgroup_size_x < 64
        || available.max_compute_invocations_per_workgroup < 64
        || available.max_compute_workgroups_per_dimension < 65535
    {
        return Err("GPU_UNAVAILABLE_OR_LIMITS".into());
    }
    let mut limits = wgpu::Limits::downlevel_defaults();
    limits.max_storage_buffer_binding_size = if shared {
        available.max_storage_buffer_binding_size
    } else {
        largest as u32
    };
    limits.max_buffer_size = if shared {
        available.max_buffer_size
    } else {
        largest.max(64 * MIB)
    };
    limits.max_storage_buffers_per_shader_stage = if shared {
        available.max_storage_buffers_per_shader_stage.min(8)
    } else {
        storage_count
    };
    let f64_cap = adapter.features().contains(wgpu::Features::SHADER_F64);
    let step = trace::StepGuard::begin(2, GpuPhase::Device);
    let (device, queue) = pollster::block_on(adapter.request_device(
        &wgpu::DeviceDescriptor {
            label: Some("isolated science context"),
            required_features: wgpu::Features::empty(),
            required_limits: limits,
            memory_hints: wgpu::MemoryHints::MemoryUsage,
        },
        None,
    ))
    .map_err(|_| "GPU_UNAVAILABLE_OR_LIMITS")?;
    step.finish();
    let error = Arc::new(AtomicBool::new(false));
    let device_epoch = if shared {
        EPOCH.load(Ordering::Acquire)
    } else {
        0
    };
    let flag = error.clone();
    device.on_uncaptured_error(Box::new(move |_| {
        flag.store(true, Ordering::Release);
        if device_epoch != 0 && EPOCH.load(Ordering::Acquire) == device_epoch {
            STOP.store(true, Ordering::Release);
        }
    }));
    let flag = error.clone();
    device.set_device_lost_callback(move |_, _| {
        flag.store(true, Ordering::Release);
        if device_epoch != 0 && EPOCH.load(Ordering::Acquire) == device_epoch {
            STOP.store(true, Ordering::Release);
        }
    });
    Ok(DeviceContext {
        device,
        queue,
        error,
        info,
        f64_cap,
    })
}
/// Only the initializer holding every byte may omit a reused buffer's clear.
/// The proof and the actual queue write stay together in checkout_buffer.
enum BufferInitialization<'a> {
    Zeroed,
    FullOverwrite(&'a [u8]),
}
fn validate_full_overwrite(size: u64, contents: &[u8]) -> R<()> {
    if contents.is_empty() || contents.len() % 4 != 0 || contents.len() as u64 != size {
        Err(failure())
    } else {
        Ok(())
    }
}
#[cfg(test)]
thread_local! {static TEST_SOFTWARE_PROBE: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };}
#[cfg(test)]
pub(crate) struct SoftwareProbeScope {previous:bool,_thread:std::marker::PhantomData<std::rc::Rc<()>>}
#[cfg(test)]
impl Drop for SoftwareProbeScope {fn drop(&mut self){TEST_SOFTWARE_PROBE.with(|v|v.set(self.previous));}}
/// Test-binary only, thread-confined opt-in. No environment or production switch.
#[cfg(test)]
pub(crate) fn software_probe_scope()->SoftwareProbeScope {SoftwareProbeScope{previous:TEST_SOFTWARE_PROBE.with(|v|v.replace(true)),_thread:std::marker::PhantomData}}
impl Context {
    pub(crate) fn acquire(largest: u64, storage_count: u32, allow_probe: bool) -> R<Self> {
        #[cfg(test)]
        let allow_probe=allow_probe||TEST_SOFTWARE_PROBE.with(|v|v.get());
        let session = current()?;
        let (d, session) = if let Some(state) = session {
            let mut s = state.lock().map_err(|_| failure())?;
            s.healthy()?;
            let ticket = s.stage.as_ref().ok_or_else(failure)?.ticket;
            s.check_stage(ticket)?;
            if s.live_contexts != 0 {
                return Err(failure());
            }
            if s.device.is_none() {
                s.device = Some(create_device(largest, storage_count, allow_probe, true)?);
            }
            let d = s.device.as_ref().unwrap().clone();
            let limits = d.device.limits();
            if largest > limits.max_storage_buffer_binding_size as u64
                || largest > limits.max_buffer_size
                || storage_count > limits.max_storage_buffers_per_shader_stage
                || (!allow_probe
                    && !crate::adreno::eligible_adapter(
                        d.info.vendor,
                        &d.info.name,
                        d.info.backend == wgpu::Backend::Vulkan,
                        d.info.device_type == wgpu::DeviceType::IntegratedGpu,
                    ))
            {
                s.contract.request_stop();
                return Err(failure());
            }
            s.live_contexts += 1;
            drop(s);
            (d, Some((state, ticket)))
        } else {
            (
                create_device(largest, storage_count, allow_probe, false)?,
                None,
            )
        };
        Ok(Self {
            device: d.device,
            queue: d.queue,
            error: d.error,
            info: d.info,
            f64_cap: d.f64_cap,
            session,
        })
    }
    pub(crate) fn buffer(
        &self,
        desc: &wgpu::BufferDescriptor<'_>,
        transient: bool,
    ) -> R<wgpu::Buffer> {
        self.checkout_buffer(desc, transient, BufferInitialization::Zeroed)
    }
    fn checkout_buffer(
        &self,
        desc: &wgpu::BufferDescriptor<'_>,
        transient: bool,
        initialization: BufferInitialization<'_>,
    ) -> R<wgpu::Buffer> {
        if let BufferInitialization::FullOverwrite(contents) = &initialization {
            validate_full_overwrite(desc.size, contents)?;
        }
        let Some((state, ticket)) = &self.session else {
            return Ok(match initialization {
                BufferInitialization::Zeroed => self.device.create_buffer(desc),
                BufferInitialization::FullOverwrite(contents) => {
                    self.device
                        .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                            label: desc.label,
                            contents,
                            usage: desc.usage,
                        })
                }
            });
        };
        if desc.mapped_at_creation
            || desc.size == 0
            || desc.size % 4 != 0
            || desc.usage.contains(wgpu::BufferUsages::MAP_WRITE)
        {
            return Err(failure());
        }
        let usage = desc.usage | wgpu::BufferUsages::COPY_DST;
        let mut s = state.lock().map_err(|_| failure())?;
        s.check_stage(*ticket)?;
        if let Some(index) = s
            .slots
            .iter()
            .position(|slot| !slot.occupied && slot.size == desc.size && slot.usage == usage)
        {
            let slot = &mut s.slots[index];
            slot.occupied = true;
            slot.transient = transient;
            let buffer = slot.buffer.clone();
            s.buffer_hits += 1;
            drop(s);
            match initialization {
                BufferInitialization::Zeroed => {
                    let mut encoder = self.device.create_command_encoder(&Default::default());
                    encoder.clear_buffer(&buffer, 0, None);
                    self.queue.submit([encoder.finish()]);
                    state.lock().map_err(|_| failure())?.clear_submissions += 1;
                }
                BufferInitialization::FullOverwrite(contents) => {
                    self.queue.write_buffer(&buffer, 0, contents)
                }
            }
            return Ok(buffer);
        }
        if s.slots.len() >= MAX_POOL_SLOTS {
            s.contract.request_stop();
            return Err(failure());
        }
        if contract(s.contract.charge_allocation(desc.size)).is_err() {
            s.contract.request_stop();
            return Err(failure());
        }
        let buffer = self
            .device
            .create_buffer(&wgpu::BufferDescriptor { usage, ..*desc });
        s.slots.push(Slot {
            buffer: buffer.clone(),
            size: desc.size,
            usage,
            occupied: true,
            transient,
        });
        drop(s);
        if let BufferInitialization::FullOverwrite(contents) = initialization {
            self.queue.write_buffer(&buffer, 0, contents);
        }
        Ok(buffer)
    }
    pub(crate) fn buffer_init(
        &self,
        desc: &wgpu::util::BufferInitDescriptor<'_>,
        transient: bool,
    ) -> R<wgpu::Buffer> {
        if self.session.is_none() {
            return Ok(self.device.create_buffer_init(desc));
        }
        if desc.contents.len() % 4 != 0 {
            return Err(failure());
        }
        let size = (desc.contents.len() as u64).max(4);
        self.checkout_buffer(
            &wgpu::BufferDescriptor {
                label: desc.label,
                size,
                usage: desc.usage,
                mapped_at_creation: false,
            },
            transient,
            if desc.contents.is_empty() {
                BufferInitialization::Zeroed
            } else {
                BufferInitialization::FullOverwrite(desc.contents)
            },
        )
    }
    /// Invoke only after a successful queue completion fence for all commands
    /// recorded with these transient uniforms/staging copies, never after cancel.
    pub(crate) fn release_transients_after_fence(&self) -> R<()> {
        if let Some((state, ticket)) = &self.session {
            let mut s = state.lock().map_err(|_| failure())?;
            s.check_stage(*ticket)?;
            for slot in &mut s.slots {
                if slot.transient {
                    slot.occupied = false;
                    slot.transient = false;
                }
            }
        }
        Ok(())
    }
    pub(crate) fn pipelines(
        &self,
        sources: &[&str],
        entries: Option<&[wgpu::BindGroupLayoutEntry]>,
    ) -> R<(Vec<wgpu::ComputePipeline>, Vec<f64>)> {
        let layout_abi = format!("wgpu24-default-main-v1:{entries:?}");
        let mut result = Vec::with_capacity(sources.len());
        let mut timings = Vec::with_capacity(sources.len());
        let mut missing = 0;
        if let Some((state, ticket)) = &self.session {
            let mut s = state.lock().map_err(|_| failure())?;
            s.check_stage(*ticket)?;
            for source in sources {
                let key = contract(PipelineKey::new(
                    ticket.epoch,
                    source,
                    layout_abi.as_bytes(),
                    "main",
                    b"features-empty-options-default",
                ))?;
                if !s.pipelines.contains_key(&key) {
                    missing += 1;
                }
            }
            if missing > 0 {
                let allowance = s.stage.as_ref().unwrap().compilation_allowance_bytes;
                if contract(s.contract.reserve_pipeline_slots(missing, allowance)).is_err() {
                    s.contract.request_stop();
                    return Err(failure());
                }
            }
        }
        let bindings = entries
            .filter(|_| self.session.is_none() || missing > 0)
            .map(|entries| {
                self.device
                    .create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                        label: Some("science cached ABI"),
                        entries,
                    })
            });
        let layout = bindings.as_ref().map(|bindings| {
            self.device
                .create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                    label: Some("science cached layout"),
                    bind_group_layouts: &[bindings],
                    push_constant_ranges: &[],
                })
        });
        for source in sources {
            let key = if let Some((_, ticket)) = &self.session {
                Some(contract(PipelineKey::new(
                    ticket.epoch,
                    source,
                    layout_abi.as_bytes(),
                    "main",
                    b"features-empty-options-default",
                ))?)
            } else {
                None
            };
            if let (Some((state, ticket)), Some(key)) = (&self.session, key) {
                let mut s = state.lock().map_err(|_| failure())?;
                s.check_stage(*ticket)?;
                if let Some(pipeline) = s.pipelines.get(&key).cloned() {
                    s.pipeline_hits += 1;
                    result.push(pipeline);
                    timings.push(0.);
                    continue;
                }
            }
            let started = Instant::now();
            self.device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
            self.device.push_error_scope(wgpu::ErrorFilter::Validation);
            let step = trace::StepGuard::begin(3, GpuPhase::Pipeline);
            let shader = self
                .device
                .create_shader_module(wgpu::ShaderModuleDescriptor {
                    label: Some("science exact shader"),
                    source: wgpu::ShaderSource::Wgsl((*source).into()),
                });
            step.finish();
            let step = trace::StepGuard::begin(4, GpuPhase::Pipeline);
            let pipeline = self
                .device
                .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some("science cached pipeline"),
                    layout: layout.as_ref(),
                    module: &shader,
                    entry_point: Some("main"),
                    compilation_options: Default::default(),
                    cache: None,
                });
            step.finish();
            let step = trace::StepGuard::begin(5, GpuPhase::Pipeline);
            let validation = pollster::block_on(self.device.pop_error_scope());
            let memory = pollster::block_on(self.device.pop_error_scope());
            step.finish();
            if validation.is_some() || memory.is_some() || self.error.load(Ordering::Acquire) {
                self.error.store(true, Ordering::Release);
                return Err("GPU_UNAVAILABLE_OR_LIMITS".into());
            }
            timings.push(started.elapsed().as_secs_f64() * 1000.);
            if let (Some((state, ticket)), Some(key)) = (&self.session, key) {
                let mut s = state.lock().map_err(|_| failure())?;
                s.check_stage(*ticket)?;
                s.pipelines.insert(key, pipeline.clone());
            }
            result.push(pipeline);
        }
        Ok((result, timings))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn omitted_clear_requires_complete_aligned_nonempty_overwrite() {
        assert!(validate_full_overwrite(4, &[1, 2, 3, 4]).is_ok());
        assert!(validate_full_overwrite(160, &[7; 160]).is_ok());
        for (size, contents) in [
            (4, &[][..]),
            (4, &[1, 2, 3][..]),
            (8, &[1, 2, 3, 4][..]),
            (4, &[1; 8][..]),
        ] {
            assert!(validate_full_overwrite(size, contents).is_err());
        }
    }
    #[test]
    fn all_science_allocators_use_admitted_context() {
        for source in [
            include_str!("research_spatial/gpu.rs"),
            include_str!("research_exposure/gpu.rs"),
            include_str!("research_finish/gpu.rs"),
            include_str!("research_diffusion/gpu.rs"),
            include_str!("research_diffusion/streaming.rs"),
        ] {
            assert!(!source.contains(".create_buffer("));
            assert!(!source.contains(".create_buffer_init("));
            assert!(!source.contains("request_device("));
            assert!(!source.contains("create_compute_pipeline("));
        }
    }

    #[test]
    #[ignore = "explicit Vulkan pool/pipeline probe; not Android speed or memory evidence"]
    fn actual_pool_keeps_pending_uploads_distinct_and_resets_reused_bytes() {
        let d = create_device(4 * MIB, 8, true, true).unwrap();
        let ticket = StageTicket {
            epoch: 91001,
            generation: 1,
            sequence: 1,
        };
        let mut admission = SessionContract::new(ticket.epoch, 64 * MIB, MIB, 8).unwrap();
        admission.begin_frame(1).unwrap();
        admission.begin_stage(ticket).unwrap();
        let state = Arc::new(Mutex::new(Session {
            contract: admission,
            fixed_bytes: MIB,
            device: Some(d.clone()),
            pipelines: HashMap::new(),
            slots: Vec::new(),
            stage: Some(Stage {
                ticket,
                owner: std::thread::current().id(),
                compilation_allowance_bytes: MIB,
            }),
            live_contexts: 1,
            pipeline_hits: 0,
            buffer_hits: 0,
            clear_submissions: 0,
        }));
        let context = Context {
            device: d.device.clone(),
            queue: d.queue.clone(),
            error: d.error.clone(),
            info: d.info.clone(),
            f64_cap: d.f64_cap,
            session: Some((state.clone(), ticket)),
        };
        let init = |word: u32| {
            context
                .buffer_init(
                    &wgpu::util::BufferInitDescriptor {
                        label: Some("distinct pending values"),
                        contents: &word.to_le_bytes(),
                        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                    },
                    true,
                )
                .unwrap()
        };
        let first = init(17);
        let second = init(31);
        assert_ne!(first, second);
        assert_eq!(state.lock().unwrap().clear_submissions, 0);
        let map = context
            .buffer(
                &wgpu::BufferDescriptor {
                    label: Some("pool test readback"),
                    size: 8,
                    usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
                    mapped_at_creation: false,
                },
                false,
            )
            .unwrap();
        let read = |buffers: [&wgpu::Buffer; 2]| {
            let mut e = context.device.create_command_encoder(&Default::default());
            for (i, buffer) in buffers.into_iter().enumerate() {
                e.copy_buffer_to_buffer(buffer, 0, &map, i as u64 * 4, 4);
            }
            context.queue.submit([e.finish()]);
            fence(&d).unwrap();
            let (tx, rx) = mpsc::channel();
            map.slice(..).map_async(wgpu::MapMode::Read, move |r| {
                tx.send(r).unwrap();
            });
            context.device.poll(wgpu::Maintain::Wait);
            rx.recv().unwrap().unwrap();
            let bytes = map.slice(..).get_mapped_range().to_vec();
            map.unmap();
            bytes
        };
        assert_eq!(
            read([&first, &second]),
            [17u32.to_le_bytes(), 31u32.to_le_bytes()].concat()
        );
        context.release_transients_after_fence().unwrap();
        let charged = state.lock().unwrap().contract.charged_bytes();
        let zero = context
            .buffer(
                &wgpu::BufferDescriptor {
                    label: Some("clear reused frame"),
                    size: 4,
                    usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                    mapped_at_creation: false,
                },
                true,
            )
            .unwrap();
        assert_eq!(zero, first);
        assert_eq!(state.lock().unwrap().clear_submissions, 1);
        assert_eq!(&read([&zero, &second])[..4], &0u32.to_le_bytes());
        assert_eq!(state.lock().unwrap().contract.charged_bytes(), charged);
        context.release_transients_after_fence().unwrap();
        let fresh = init(47);
        let other = init(59);
        assert_eq!(fresh, first);
        assert_eq!(other, second);
        assert_ne!(fresh, other);
        assert_eq!(state.lock().unwrap().clear_submissions, 1);
        assert_eq!(
            read([&fresh, &other]),
            [47u32.to_le_bytes(), 59u32.to_le_bytes()].concat()
        );
        context.release_transients_after_fence().unwrap();
        let empty = context
            .buffer_init(
                &wgpu::util::BufferInitDescriptor {
                    label: Some("empty initialization must clear"),
                    contents: &[],
                    usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                },
                true,
            )
            .unwrap();
        assert_eq!(state.lock().unwrap().clear_submissions, 2);
        assert_eq!(&read([&empty, &other])[..4], &0u32.to_le_bytes());
        let shader = "@compute @workgroup_size(64) fn main() {}";
        let a = context.pipelines(&[shader], None).unwrap().0.remove(0);
        let charged = state.lock().unwrap().contract.charged_bytes();
        let b = context.pipelines(&[shader], None).unwrap().0.remove(0);
        assert_eq!(a, b);
        assert_eq!(state.lock().unwrap().contract.charged_bytes(), charged);
        assert_eq!(state.lock().unwrap().pipeline_hits, 1);
        assert!(state.lock().unwrap().buffer_hits >= 2);
    }
}
