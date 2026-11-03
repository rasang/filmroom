//! Actual Adreno Vulkan resident optics, invoked only inside the isolated helper.
//! Original images remain immutable. Full-line dependencies survive dispatch batches.
use super::*;
use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};
use wgpu::util::DeviceExt;
const IO_SAMPLES: usize = 262144;
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunMetrics {
    pub adapter: String,
    pub vendor: u32,
    pub shader_f64_advertised: bool,
    pub elapsed_ms: f64,
    pub setup_ms: f64,
    pub upload_ms: f64,
    pub dispatch_wait_ms: f64,
    pub readback_ms: f64,
    pub guard_ms: f64,
    pub upload_bytes: u64,
    pub readback_bytes: u64,
    pub dispatches: u64,
    pub device_bytes: u64,
    pub pass_count: u64,
    pub sample_count: u64,
    pub sample_max_abs: f64,
    pub sample_rms: f64,
    pub sample_tolerance_absolute: f64,
    pub sample_tolerance_relative: f64,
    pub sample_scope: &'static str,
    #[serde(skip)]
    pub(crate) sample_sum: f64,
}
pub(crate) struct Runner {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    error: Arc<AtomicBool>,
    planes: [wgpu::Buffer; 5],
    probe: wgpu::Buffer,
    map: wgpu::Buffer,
    numeric_error: Option<wgpu::Buffer>,
    immutable_tables: Option<wgpu::Buffer>,
}
// Bound queue-owned upload payload and live command resources independently of
// image size. A dispatch still processes at most the caller's original chunk.
pub(crate) const BATCH_DISPATCHES: u32 = 32;
const BATCH_UPLOAD_BYTES: u64 = 256 * 1024;
const BATCH_PENDING_BYTES: u64 = 4 * MIB;
// Conservative reservation for one uniform, bind group, encoder and command
// buffer. Driver-private allocations are implementation-dependent, not measured.
const BATCH_COMMAND_RESERVATION: u64 = 64 * 1024;
#[derive(Default)]
struct BatchBudget { submissions: u32, reserved_bytes: u64 }
impl BatchBudget {
    fn reserve(&mut self, payload: u64) -> R<()> {
        let bytes = payload.checked_add(BATCH_COMMAND_RESERVATION).ok_or("GPU_OPTICS_BATCH_LIMIT")?;
        let total = self.reserved_bytes.checked_add(bytes).ok_or("GPU_OPTICS_BATCH_LIMIT")?;
        if payload > BATCH_UPLOAD_BYTES || self.submissions >= BATCH_DISPATCHES || total > BATCH_PENDING_BYTES {
            return Err("GPU_OPTICS_BATCH_LIMIT".into());
        }
        self.submissions += 1;
        self.reserved_bytes = total;
        Ok(())
    }
}
/// Owns bounded ordered submissions, not a list of deferred writes. The only
/// enqueue operation pairs each write with its consuming submission immediately.
/// wgpu Queue::write_buffer schedules the transfer before the commands in the
/// NEXT submit. Never collect several writes to the shared chunk slot first.
/// https://wgpu.rs/doc/wgpu/struct.Queue.html#method.write_buffer
pub(crate) struct DispatchBatch<'a> { runner: &'a Runner, budget: BatchBudget }
impl DispatchBatch<'_> {
    pub(crate) fn submit_chunk(&mut self, upload: Option<(u64, &[u8])>, p: [u32; 40], c: &mut impl FnMut() -> bool, m: &mut RunMetrics) -> R<()> {
        check(c)?;
        let payload = upload.map_or(0, |(_, b)| b.len() as u64);
        if let Some((offset, bytes)) = upload { self.runner.validate_aux(offset, bytes)?; }
        self.budget.reserve(payload)?;
        if let Some((offset, bytes)) = upload {
            let at = Instant::now();
            self.runner.queue.write_buffer(&self.runner.probe, offset, bytes);
            m.upload_bytes += bytes.len() as u64;
            m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
        }
        // No cancellation/error exit between write and its consuming submit.
        let at = Instant::now();
        self.runner.submit_dispatch(p);
        m.dispatches += 1;
        m.upload_bytes += 160;
        m.dispatch_wait_ms += at.elapsed().as_secs_f64() * 1000.;
        Ok(())
    }
    pub(crate) fn finish(self, c: &mut impl FnMut() -> bool, m: &mut RunMetrics) -> R<()> {
        if self.budget.submissions != 0 {
            let at = Instant::now();
            self.runner.wait(c)?;
            m.dispatch_wait_ms += at.elapsed().as_secs_f64() * 1000.;
        }
        Ok(())
    }
}
impl Runner {
    pub(crate) fn new(p: &FilmOpticsPlan, allow_probe: bool) -> R<(Self, RunMetrics)> {
        Self::new_with_shader_error(p, allow_probe, &super::guarded::shader()?)
    }
    pub(crate) fn new_with_shader(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader_source: &str,
    ) -> R<(Self, RunMetrics)> {
        Self::new_shader_inner(p, allow_probe, shader_source, false, None)
    }
    /// Tail-only integer failure transport. Existing shaders keep their inferred
    /// six-buffer layout; each new tail Runner starts with an independent zero.
    pub(crate) fn new_with_shader_error(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader_source: &str,
    ) -> R<(Self, RunMetrics)> {
        Self::new_shader_inner(p, allow_probe, shader_source, true, None)
    }
    /// One device and binding set for the PCHIP-to-scanner resident chain.
    pub(crate) fn new_with_shader_error_tables(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader: &str,
        tables: &[f32],
    ) -> R<(Self, RunMetrics)> {
        if tables.is_empty() {
            return Err("GPU_OPTICS_TABLES".into());
        }
        Self::new_shader_inner(p, allow_probe, shader, true, Some(tables))
    }
    fn new_shader_inner(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader_source: &str,
        with_numeric_error: bool,
        tables: Option<&[f32]>,
    ) -> R<(Self, RunMetrics)> {
        p.validate()?;
        let at = Instant::now();
        let plane = p.width as u64 * p.height as u64 * 4;
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
        let l = adapter.limits();
        let largest = plane
            .max(TRANSFER_BYTES)
            .max(tables.map_or(0, |t| t.len() as u64 * 4));
        let storage_count = if tables.is_some() {
            8
        } else if with_numeric_error {
            7
        } else {
            6
        };
        if largest > l.max_storage_buffer_binding_size as u64
            || largest > l.max_buffer_size
            || l.max_storage_buffers_per_shader_stage < storage_count
            || l.max_compute_workgroup_size_x < 64
            || l.max_compute_invocations_per_workgroup < 64
            || l.max_compute_workgroups_per_dimension < 65535
        {
            return Err("GPU_UNAVAILABLE_OR_LIMITS".into());
        }
        let mut limits = wgpu::Limits::downlevel_defaults();
        limits.max_storage_buffer_binding_size = largest as u32;
        limits.max_buffer_size = largest.max(64 * MIB);
        limits.max_storage_buffers_per_shader_stage = storage_count;
        let f64_cap = adapter.features().contains(wgpu::Features::SHADER_F64);
        let (device, queue) = pollster::block_on(adapter.request_device(
            &wgpu::DeviceDescriptor {
                label: Some("isolated resident film optics"),
                required_features: wgpu::Features::empty(),
                required_limits: limits,
                memory_hints: wgpu::MemoryHints::MemoryUsage,
            },
            None,
        ))
        .map_err(|_| "GPU_UNAVAILABLE_OR_LIMITS")?;
        let error = Arc::new(AtomicBool::new(false));
        let flag = error.clone();
        device.on_uncaptured_error(Box::new(move |_| {
            flag.store(true, Ordering::Relaxed);
        }));
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("original FIR/IIR film optics"),
            source: wgpu::ShaderSource::Wgsl(shader_source.into()),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("resident film optics"),
            layout: None,
            module: &shader,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        if pollster::block_on(device.pop_error_scope()).is_some() {
            return Err("GPU_UNAVAILABLE_OR_LIMITS".into());
        }
        let make = |label, size, usage| {
            device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size,
                usage,
                mapped_at_creation: false,
            })
        };
        let usage = wgpu::BufferUsages::STORAGE
            | wgpu::BufferUsages::COPY_SRC
            | wgpu::BufferUsages::COPY_DST;
        let planes = std::array::from_fn(|_| make("resident film optics plane", plane, usage));
        let probe = make("bounded full-line numeric gather", TRANSFER_BYTES, usage);
        let map = make(
            "bounded optical readback",
            TRANSFER_BYTES,
            wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        );
        // Four bytes fit inside the existing 65,536-byte fixed metadata reserve.
        // Never reset this flag during a request, including later spatial passes.
        let numeric_error = with_numeric_error.then(|| {
            device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("sticky integer numeric error"),
                contents: &0u32.to_le_bytes(),
                usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
            })
        });
        let immutable_tables = tables.map(|data| {
            device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("immutable fused finish tables and header"),
                contents: bytemuck::cast_slice(data),
                usage: wgpu::BufferUsages::STORAGE,
            })
        });
        let m = RunMetrics {
            adapter: info.name,
            vendor: info.vendor,
            shader_f64_advertised: f64_cap,
            elapsed_ms: 0.,
            setup_ms: at.elapsed().as_secs_f64() * 1000.,
            upload_ms: 0.,
            dispatch_wait_ms: 0.,
            readback_ms: 0.,
            guard_ms: 0.,
            upload_bytes: 0,
            readback_bytes: 0,
            dispatches: 0,
            device_bytes: p.device_bytes,
            pass_count: 0,
            sample_count: 0,
            sample_max_abs: 0.,
            sample_rms: 0.,
            sample_tolerance_absolute: NUMERIC_TOLERANCE,
            sample_tolerance_relative: NUMERIC_TOLERANCE,
            sample_scope: "per_axis_f64_on_gpu_f32_input",
            sample_sum: 0.,
        };
        Ok((
            Self {
                device,
                queue,
                pipeline,
                error,
                planes,
                probe,
                map,
                numeric_error,
                immutable_tables,
            },
            m,
        ))
    }
    fn wait(&self, c: &mut impl FnMut() -> bool) -> R<()> {
        let (tx, rx) = mpsc::channel();
        self.queue.on_submitted_work_done(move || {
            let _ = tx.send(());
        });
        let at = Instant::now();
        loop {
            check(c)?;
            if self.error.load(Ordering::Relaxed) {
                return Err("GPU_DEVICE_FAILED".into());
            }
            let _ = self.device.poll(wgpu::Maintain::Poll);
            if rx.try_recv().is_ok() {
                return Ok(());
            }
            if at.elapsed() > Duration::from_secs(5) {
                return Err("GPU_OPTICS_TIMEOUT".into());
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    pub(crate) fn dispatch(
        &self,
        p: [u32; 40],
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        check(c)?;
        let at = Instant::now();
        self.submit_dispatch(p);
        self.wait(c)?;
        m.dispatches += 1;
        m.upload_bytes += 160;
        m.dispatch_wait_ms += at.elapsed().as_secs_f64() * 1000.;
        Ok(())
    }
    pub(crate) fn batch(&self) -> DispatchBatch<'_> {
        DispatchBatch { runner: self, budget: BatchBudget::default() }
    }
    fn submit_dispatch(&self, p: [u32; 40]) {
        let uniform = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("resident optics parameters"),
                contents: bytemuck::cast_slice(&p),
                usage: wgpu::BufferUsages::UNIFORM,
            });
        let mut entries: Vec<_> = self
            .planes
            .iter()
            .enumerate()
            .map(|(i, b)| wgpu::BindGroupEntry {
                binding: i as u32,
                resource: b.as_entire_binding(),
            })
            .collect();
        entries.push(wgpu::BindGroupEntry {
            binding: 5,
            resource: self.probe.as_entire_binding(),
        });
        entries.push(wgpu::BindGroupEntry {
            binding: 6,
            resource: uniform.as_entire_binding(),
        });
        if let Some(flag) = &self.numeric_error {
            entries.push(wgpu::BindGroupEntry {
                binding: 7,
                resource: flag.as_entire_binding(),
            });
        }
        if let Some(tables) = &self.immutable_tables {
            entries.push(wgpu::BindGroupEntry {
                binding: 8,
                resource: tables.as_entire_binding(),
            });
        }
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("resident optics bindings"),
            layout: &self.pipeline.get_bind_group_layout(0),
            entries: &entries,
        });
        let mut e = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = e.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("original FIR/IIR optics pass"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &group, &[]);
            let groups = (p[4] as u64).div_ceil(64);
            pass.dispatch_workgroups(groups.min(65535) as u32, groups.div_ceil(65535) as u32, 1);
        }
        self.queue.submit([e.finish()]);
    }
    pub(crate) fn read_error_flag(
        &self,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<u32> {
        let flag = self.numeric_error.as_ref().ok_or("GPU_OPTICS_ERROR_ABI")?;
        let bytes = self.read(flag, 0, 4, c, m)?;
        Ok(u32::from_le_bytes(
            bytes.try_into().map_err(|_| "GPU_OPTICS_ERROR_ABI")?,
        ))
    }
    fn read(
        &self,
        buffer: &wgpu::Buffer,
        offset: u64,
        bytes: u64,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<Vec<u8>> {
        if bytes > TRANSFER_BYTES {
            return Err("GPU_OPTICS_MEMORY_LIMIT".into());
        }
        check(c)?;
        let at = Instant::now();
        let mut e = self.device.create_command_encoder(&Default::default());
        e.copy_buffer_to_buffer(buffer, offset, &self.map, 0, bytes);
        self.queue.submit([e.finish()]);
        let (tx, rx) = mpsc::channel();
        self.map
            .slice(0..bytes)
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = tx.send(r);
            });
        let result = (|| {
            loop {
                check(c)?;
                if self.error.load(Ordering::Relaxed) {
                    return Err("GPU_DEVICE_FAILED".into());
                }
                let _ = self.device.poll(wgpu::Maintain::Poll);
                match rx.try_recv() {
                    Ok(Ok(())) => break,
                    Ok(Err(_)) => return Err("GPU_OPTICS_READBACK_FAILED".into()),
                    Err(mpsc::TryRecvError::Disconnected) => {
                        return Err("GPU_OPTICS_READBACK_FAILED".into());
                    }
                    Err(mpsc::TryRecvError::Empty) => {}
                }
                if at.elapsed() > Duration::from_secs(5) {
                    return Err("GPU_OPTICS_TIMEOUT".into());
                }
                std::thread::sleep(Duration::from_millis(1));
            }
            Ok(self.map.slice(0..bytes).get_mapped_range().to_vec())
        })();
        self.map.unmap();
        m.readback_ms += at.elapsed().as_secs_f64() * 1000.;
        if result.is_ok() {
            m.readback_bytes += bytes;
        }
        result
    }
    fn guard(
        &self,
        w: u32,
        h: u32,
        vertical: bool,
        filter: &Filter,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        // Deterministic true-image lines, not cropped halos. Includes edges within each line.
        let mut p = params(w, h, if vertical { 11 } else { 10 });
        let n = if vertical { h } else { w };
        p[4] = n;
        p[36] = if vertical { w / 2 } else { h / 2 };
        self.dispatch(p, c, m)?;
        let raw = self.read(&self.probe, 0, n as u64 * 8, c, m)?;
        let at = Instant::now();
        let mut source = Vec::with_capacity(n as usize);
        let mut actual = Vec::with_capacity(n as usize);
        for b in raw[..n as usize * 4].chunks_exact(4) {
            source.push(f32::from_le_bytes(b.try_into().unwrap()) as f64);
        }
        for b in raw[n as usize * 4..].chunks_exact(4) {
            actual.push(f32::from_le_bytes(b.try_into().unwrap()));
        }
        let reference = filter.line_f64(&source);
        let (max, sum) = compare_lines(&reference, &actual)?;
        m.sample_count += n as u64;
        m.sample_max_abs = m.sample_max_abs.max(max);
        m.sample_sum += sum;
        m.guard_ms += at.elapsed().as_secs_f64() * 1000.;
        Ok(())
    }
    pub(crate) fn blur(
        &self,
        w: u32,
        h: u32,
        sigma: f32,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        let f = Filter::new(sigma)?;
        for vertical in [false, true] {
            let mut p = params(w, h, 0);
            p[6] = if vertical { 1 } else { 0 };
            p[7] = if vertical { 2 } else { 1 };
            match &f {
                Filter::Identity => p[2] = 4,
                Filter::Fir(k) => {
                    p[2] = u32::from(vertical);
                    p[5] = (k.len() / 2) as u32;
                    for (i, &x) in k.iter().enumerate() {
                        p[16 + i] = (x as f32).to_bits();
                    }
                }
                Filter::Iir(k) => {
                    p[2] = if vertical { 3 } else { 2 };
                    p[4] = if vertical { w } else { h };
                    for (i, &x) in k.iter().enumerate() {
                        p[8 + i] = (x as f32).to_bits();
                    }
                }
            }
            self.dispatch(p, c, m)?;
            self.guard(w, h, vertical, &f, c, m)?;
        }
        m.pass_count += 1;
        Ok(())
    }
    fn validate_aux(&self, offset: u64, bytes: &[u8]) -> R<()> {
        if offset % 4 != 0 || bytes.len() % 4 != 0 || offset.checked_add(bytes.len() as u64).is_none_or(|end| end > TRANSFER_BYTES) {
            return Err("GPU_OPTICS_MEMORY_LIMIT".into());
        }
        Ok(())
    }
    pub(crate) fn upload_aux(
        &self,
        offset: u64,
        bytes: &[u8],
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        check(c)?;
        self.validate_aux(offset, bytes)?;
        let at = Instant::now();
        self.queue.write_buffer(&self.probe, offset, bytes);
        self.queue.submit([]);
        self.wait(c)?;
        m.upload_bytes += bytes.len() as u64;
        m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
        Ok(())
    }
    pub(crate) fn read_source(
        &self,
        offset: u64,
        bytes: u64,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<Vec<u8>> {
        self.read(&self.planes[0], offset, bytes, c, m)
    }
    pub(crate) fn read_probe(
        &self,
        bytes: u64,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<Vec<u8>> {
        self.read(&self.probe, 0, bytes, c, m)
    }
    fn run_channel(
        &self,
        p: &ChannelPlan,
        w: u32,
        h: u32,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        if p.lens_sigma > 0. {
            self.blur(w, h, p.lens_sigma, c, m)?;
            let mut v = params(w, h, 4);
            v[6] = 2;
            v[7] = 0;
            self.dispatch(v, c, m)?;
        }
        if let Some(s) = &p.scatter {
            self.blur(w, h, s.sigma, c, m)?;
            let mut v = params(w, h, 4);
            v[6] = 2;
            v[7] = 3;
            self.dispatch(v, c, m)?;
            self.dispatch(params(w, h, 5), c, m)?;
            for (amp, ratio) in FIT {
                self.blur(w, h, (ratio * s.decay as f64) as f32, c, m)?;
                let mut v = params(w, h, 6);
                v[12] = (amp as f32).to_bits();
                self.dispatch(v, c, m)?;
            }
            let mut v = params(w, h, 7);
            v[12] = (s.amount as f32).to_bits();
            v[13] = (s.tail_weight as f32).to_bits();
            self.dispatch(v, c, m)?;
        }
        if !p.bounce_sigmas.is_empty() {
            self.dispatch(params(w, h, 5), c, m)?;
            for (&sigma, &weight) in p.bounce_sigmas.iter().zip(&p.bounce_weights) {
                self.blur(w, h, sigma, c, m)?;
                let mut v = params(w, h, 6);
                v[12] = (weight as f32).to_bits();
                self.dispatch(v, c, m)?;
            }
            let mut v = params(w, h, 8);
            v[14] = (p.strength as f32).to_bits();
            self.dispatch(v, c, m)?;
        }
        if p.divisor != 1. {
            let mut v = params(w, h, 9);
            v[15] = (p.divisor as f32).to_bits();
            self.dispatch(v, c, m)?;
        }
        Ok(())
    }
}
pub(crate) fn params(w: u32, h: u32, op: u32) -> [u32; 40] {
    let mut p = [0; 40];
    p[0] = w;
    p[1] = h;
    p[2] = op;
    p[4] = w * h;
    p
}
struct Outputs<'a> {
    paths: [&'a Path; 3],
    created: usize,
    keep: bool,
}
impl Drop for Outputs<'_> {
    fn drop(&mut self) {
        if !self.keep {
            for p in &self.paths[..self.created] {
                let _ = std::fs::remove_file(p);
            }
        }
    }
}
/// One invocation per helper process; caller waits for confirmed process death
/// before discarding source links or retrying the entire stage on the CPU.
pub fn run_files(
    p: &FilmOpticsPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
) -> R<RunMetrics> {
    run_files_inner(p, sources, outputs, c, progress, false)
}
fn run_files_inner(
    p: &FilmOpticsPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
    allow_probe: bool,
) -> R<RunMetrics> {
    let start = Instant::now();
    p.numerical_preflight(c)?;
    let preflight = start.elapsed().as_secs_f64() * 1000.;
    let n = p.width as u64 * p.height as u64;
    for i in 0..3 {
        if sources.contains(&outputs[i]) || outputs[..i].contains(&outputs[i]) {
            return Err("INVALID_OPTICS_FILE".into());
        }
        let meta = std::fs::symlink_metadata(sources[i]).map_err(|_| "GPU_OPTICS_IO")?;
        if !meta.is_file() || meta.file_type().is_symlink() || meta.len() != n * 8 {
            return Err("INVALID_OPTICS_FILE".into());
        }
    }
    check(c)?;
    let (r, mut m) = Runner::new(p, allow_probe)?;
    m.setup_ms += preflight;
    let mut guard = Outputs {
        paths: outputs,
        created: 0,
        keep: false,
    };
    for ch in 0..3 {
        check(c)?;
        let mut source = File::open(sources[ch]).map_err(|_| "GPU_OPTICS_IO")?;
        let at = Instant::now();
        let mut left = n;
        let mut offset = 0;
        let mut raw = vec![0u8; IO_SAMPLES * 8];
        let mut upload = vec![0u8; IO_SAMPLES * 4];
        while left > 0 {
            check(c)?;
            let count = left.min(IO_SAMPLES as u64) as usize;
            source
                .read_exact(&mut raw[..count * 8])
                .map_err(|_| "GPU_OPTICS_IO")?;
            for (i, b) in raw[..count * 8].chunks_exact(8).enumerate() {
                let x = f64::from_le_bytes(b.try_into().unwrap());
                if !x.is_finite() || !(x as f32).is_finite() {
                    return Err("NONFINITE_OPTICS_INPUT".into());
                }
                upload[i * 4..i * 4 + 4].copy_from_slice(&(x as f32).to_le_bytes());
            }
            r.queue
                .write_buffer(&r.planes[0], offset, &upload[..count * 4]);
            r.queue.submit([]);
            r.wait(c)?;
            offset += count as u64 * 4;
            left -= count as u64;
            m.upload_bytes += count as u64 * 4;
        }
        drop(raw);
        drop(upload);
        drop(source);
        m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
        r.run_channel(&p.channels[ch], p.width, p.height, c, &mut m)?;
        if r.read_error_flag(c, &mut m)? != 0 { return Err("GPU_OPTICS_NUMERIC_DOMAIN".into()); }
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(outputs[ch])
            .map_err(|_| "GPU_OPTICS_IO")?;
        guard.created += 1;
        let mut offset = 0;
        let mut encoded = Vec::with_capacity(IO_SAMPLES * 8);
        while offset < n {
            check(c)?;
            let count = (n - offset).min(IO_SAMPLES as u64);
            let bytes = r.read(&r.planes[0], offset * 4, count * 4, c, &mut m)?;
            encoded.clear();
            for b in bytes.chunks_exact(4) {
                let x = f32::from_le_bytes(b.try_into().unwrap());
                if !x.is_finite() {
                    return Err("NONFINITE_OPTICS_OUTPUT".into());
                }
                encoded.extend_from_slice(&(x as f64).to_le_bytes());
            }
            output.write_all(&encoded).map_err(|_| "GPU_OPTICS_IO")?;
            offset += count;
            progress(ch as u64 * n + offset, 3 * n);
        }
        output.sync_all().map_err(|_| "GPU_OPTICS_IO")?;
    }
    check(c)?;
    if r.error.load(Ordering::Relaxed) {
        return Err("GPU_DEVICE_FAILED".into());
    }
    m.sample_rms = (m.sample_sum / m.sample_count.max(1) as f64).sqrt();
    m.elapsed_ms = start.elapsed().as_secs_f64() * 1000.;
    if r.read_error_flag(c, &mut m)? != 0 { return Err("GPU_OPTICS_NUMERIC_DOMAIN".into()); }
    guard.keep = true;
    Ok(m)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "explicit Vulkan software/hardware probe; not mobile speed evidence"]
    fn actual_vulkan_optics_matches_mirror() {
        let root = std::env::temp_dir().join(format!("film-optics-vulkan-{}", std::process::id()));
        std::fs::create_dir(&root).unwrap();
        let (w, h) = (71, 37);
        let mut params = RuntimeParams::default();
        params.camera.lens_blur_um = 0.4;
        let p = FilmOpticsPlan::new(&params, 20., w, h).unwrap();
        let src: Vec<f64> = (0..w * h)
            .map(|i| ((i * 37 % 101) as f64 / 13.) - 0.7)
            .collect();
        let inputs: [std::path::PathBuf; 3] = std::array::from_fn(|i| root.join(format!("in{i}")));
        let outputs: [std::path::PathBuf; 3] =
            std::array::from_fn(|i| root.join(format!("out{i}")));
        for path in &inputs {
            let mut f = File::create(path).unwrap();
            for x in &src {
                f.write_all(&x.to_le_bytes()).unwrap();
            }
        }
        let metrics = run_files_inner(
            &p,
            inputs.each_ref().map(|x| x.as_path()),
            outputs.each_ref().map(|x| x.as_path()),
            &mut || false,
            &mut |_, _| {},
            true,
        )
        .unwrap();
        assert_eq!(metrics.sample_count, metrics.pass_count * (w + h) as u64);
        for ch in 0..3 {
            let expected = channel_cpu_mirror(&src, w, h, &p.channels[ch], &mut || false).unwrap();
            let bytes = std::fs::read(&outputs[ch]).unwrap();
            for (x, b) in expected.iter().zip(bytes.chunks_exact(8)) {
                let y = f64::from_le_bytes(b.try_into().unwrap());
                assert!((x - y).abs() < 2e-4 * x.abs().max(1.), "{x} {y}");
            }
        }
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[ignore = "explicit Vulkan probe; not mobile performance evidence"]
    fn actual_vulkan_unsampled_optics_overflow_has_no_output() {
        for sigma in [1., 4.] {
            let root =
                std::env::temp_dir().join(format!("optics-domain-{}-{sigma}", std::process::id()));
            std::fs::create_dir(&root).unwrap();
            let mut rp = RuntimeParams::default();
            rp.camera.lens_blur_um = 0.;
            let mut p = FilmOpticsPlan::new(&rp, 20., 71, 37).unwrap();
            for ch in &mut p.channels {
                ch.lens_sigma = sigma;
                ch.scatter = None;
                ch.bounce_sigmas.clear();
                ch.bounce_weights.clear();
                ch.strength = 0.;
                ch.divisor = 1.;
            }
            let inputs: [std::path::PathBuf; 3] =
                std::array::from_fn(|ch| root.join(format!("in{ch}")));
            let outputs: [std::path::PathBuf; 3] =
                std::array::from_fn(|ch| root.join(format!("out{ch}")));
            let mut values = vec![0.25f64; 71 * 37];
            values[72] = 3e38;
            for path in &inputs {
                std::fs::write(
                    path,
                    values
                        .iter()
                        .flat_map(|x| x.to_le_bytes())
                        .collect::<Vec<_>>(),
                )
                .unwrap();
            }
            let result = run_files_inner(
                &p,
                inputs.each_ref().map(|p| p.as_path()),
                outputs.each_ref().map(|p| p.as_path()),
                &mut || false,
                &mut |_, _| {},
                true,
            );
            assert_eq!(result.err().unwrap(), "GPU_OPTICS_NUMERIC_DOMAIN");
            assert!(outputs.iter().all(|p| !p.exists()));
            std::fs::remove_dir_all(root).unwrap();
        }
    }
}

#[cfg(test)]
mod bounded_batch_tests {
    use super::*;
    #[test]
    fn sequential_batch_bounds_include_uploads_and_command_reservations() {
        assert_eq!(BATCH_DISPATCHES, 2 * crate::research_grain::guarded_graph::SEQUENTIAL_BATCH_CHUNKS);
        assert_eq!(BATCH_PENDING_BYTES, crate::research_grain::guarded_graph::BATCH_RESERVATION_BYTES);
        let mut b = BatchBudget::default();
        for _ in 0..BATCH_DISPATCHES { b.reserve(256 * 3 * 4).unwrap(); }
        assert_eq!(b.submissions, 32);
        assert_eq!(b.reserved_bytes, 32 * (3072 + BATCH_COMMAND_RESERVATION));
        assert!(b.reserved_bytes <= BATCH_PENDING_BYTES);
        let saved = (b.submissions, b.reserved_bytes);
        assert!(b.reserve(0).is_err());
        assert_eq!(saved, (b.submissions, b.reserved_bytes));
    }
    #[test]
    fn batch_rejects_oversize_and_overflow_before_queue_write() {
        for n in [BATCH_UPLOAD_BYTES + 1, u64::MAX] {
            let mut b = BatchBudget::default();
            assert!(b.reserve(n).is_err());
            assert_eq!((b.submissions, b.reserved_bytes), (0, 0));
        }
        let mut b = BatchBudget::default();
        while b.reserve(BATCH_UPLOAD_BYTES).is_ok() {}
        assert!(b.submissions < BATCH_DISPATCHES);
        assert!(b.reserved_bytes <= BATCH_PENDING_BYTES);
        // Existing coordinate chunk remains admitted (batch size one).
        BatchBudget::default().reserve(16384 * 3 * 4).unwrap();
    }
}
