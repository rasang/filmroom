//! Actual Vulkan dispatch implementation of the isolated FFT stage. No mobile
//! routing. Call only on a dedicated worker; driver calls themselves can block.
use super::{Plan, SHADER};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};
use wgpu::util::DeviceExt;
#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunMetrics {
    pub adapter: String,
    pub vendor: u32,
    pub shader_f64_advertised: bool,
    pub backend: String,
    pub adapter_device_type: String,
    pub hardware_gpu: bool,
    pub stage_total_ms: f64,
    pub device_payload_bytes: u64,
    pub upload_bytes: u64,
    pub readback_bytes: u64,
    pub dispatches: u64,
    pub tiles: usize,
    pub exact_f64_claim: bool,
}
pub struct Runner {
    pub(super) device: wgpu::Device,
    pub(super) queue: wgpu::Queue,
    pub(super) pipeline: wgpu::ComputePipeline,
    pub(super) error: Arc<AtomicBool>,
    pub(super) adapter: String,
    pub(super) vendor: u32,
    pub(super) f64_cap: bool,
    pub(super) device_type: wgpu::DeviceType,
    admitted_shape: [u64; 8],
}
impl Runner {
    pub fn new(plan: &Plan, allow_non_adreno_for_probe: bool) -> Result<Self, String> {
        plan.validate_layout()?;
        let largest = (plan.side as u64 * plan.side as u64 * 8)
            .max(((plan.width * plan.height + plan.kernel.len() + plan.side * 2) * 4) as u64);
        Self::create(largest, plan.shape_signature(), allow_non_adreno_for_probe)
    }
    pub(super) fn create(
        largest: u64,
        shape: [u64; 8],
        allow_non_adreno_for_probe: bool,
    ) -> Result<Self, String> {
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::VULKAN,
            ..Default::default()
        });
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: None,
            force_fallback_adapter: false,
        }))
        .ok_or("no Vulkan adapter; no GPU timings available")?;
        let info = adapter.get_info();
        if !allow_non_adreno_for_probe
            && !crate::adreno::eligible_adapter(
                info.vendor,
                &info.name,
                info.backend == wgpu::Backend::Vulkan,
                info.device_type == wgpu::DeviceType::IntegratedGpu,
            )
        {
            return Err("not a qualified Qualcomm Adreno; research route unavailable".into());
        }
        let limits = adapter.limits();
        if largest > limits.max_storage_buffer_binding_size as u64
            || largest > limits.max_buffer_size
            || limits.max_storage_buffers_per_shader_stage < 4
            || limits.max_compute_workgroup_size_x < 64
            || limits.max_compute_invocations_per_workgroup < 64
            || limits.max_compute_workgroups_per_dimension < 65535
        {
            return Err("GPU limits cannot fit admitted original-PSF FFT".into());
        }
        let mut required = wgpu::Limits::downlevel_defaults();
        required.max_storage_buffer_binding_size = largest as u32;
        required.max_buffer_size = largest.max(64 * 1024 * 1024);
        let f64_cap = adapter.features().contains(wgpu::Features::SHADER_F64);
        let (device, queue) = pollster::block_on(adapter.request_device(
            &wgpu::DeviceDescriptor {
                label: Some("isolated research diffusion"),
                required_features: wgpu::Features::empty(),
                required_limits: required,
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
            label: Some("research FFT"),
            source: wgpu::ShaderSource::Wgsl(SHADER.into()),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("research FFT"),
            layout: None,
            module: &shader,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        if pollster::block_on(device.pop_error_scope()).is_some() {
            return Err("FFT shader validation failed".into());
        }
        Ok(Self {
            device,
            queue,
            pipeline,
            error,
            adapter: info.name,
            vendor: info.vendor,
            f64_cap,
            device_type: info.device_type,
            admitted_shape: shape,
        })
    }
    /// One-shot: consumes the runner, so interrupted submitted work cannot be
    /// followed by another run or concurrent run on this instance.
    pub fn run(
        self,
        plan: &Plan,
        input: &[f64],
        cancel: &mut impl FnMut() -> bool,
    ) -> Result<(Vec<f64>, RunMetrics), String> {
        let start = Instant::now();
        plan.validate_layout()?;
        if plan.shape_signature() != self.admitted_shape {
            return Err("research plan differs from admitted device shape".into());
        }
        plan.validate_input(input)?;
        if cancel() {
            return Err("cancelled before GPU work".into());
        }
        let mut data: Vec<f32> = input.iter().map(|v| *v as f32).collect();
        data.extend(plan.kernel.iter().map(|v| *v as f32));
        data.extend(plan.twiddles.iter().flatten());
        self.run_uploaded(plan, data, None, start, cancel)
    }
    /// Pre-reflected source window followed by original kernel and twiddles.
    /// One bounded ROI only; never writes into the normal rendering pipeline.
    pub(crate) fn run_probe(
        self,
        plan: &Plan,
        data: Vec<f32>,
        width: usize,
        height: usize,
        cancel: &mut impl FnMut() -> bool,
    ) -> Result<(Vec<f64>, RunMetrics), String> {
        let start = Instant::now();
        self.run_uploaded(plan, data, Some((width, height)), start, cancel)
    }
    fn run_uploaded(
        self,
        plan: &Plan,
        data: Vec<f32>,
        roi: Option<(usize, usize)>,
        start: Instant,
        cancel: &mut impl FnMut() -> bool,
    ) -> Result<(Vec<f64>, RunMetrics), String> {
        plan.validate_layout()?;
        if plan.shape_signature() != self.admitted_shape
            || data.len() != plan.width * plan.height + plan.kernel.len() + plan.side * 2
            || data.iter().any(|v| !v.is_finite())
        {
            return Err("invalid admitted GPU data".into());
        }
        if let Some((w, h)) = roi {
            if plan.width != plan.side
                || plan.height != plan.side
                || w == 0
                || h == 0
                || w > plan.core
                || h > plan.core
            {
                return Err("invalid probe crop".into());
            }
        }
        if cancel() {
            return Err("cancelled before upload".into());
        }
        let n = plan.side;
        let size = (n * n * 8) as u64;
        let twiddle_offset = (plan.width * plan.height + plan.kernel.len()) as u32;
        let mk = |label, size, usage| {
            self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size,
                usage,
                mapped_at_creation: false,
            })
        };
        let usage = wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC;
        let a = mk("FFT A", size, usage);
        let b = mk("FFT B", size, usage);
        let spectrum = mk("kernel spectrum", size, wgpu::BufferUsages::STORAGE);
        let source = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("source PSF twiddles"),
                contents: bytemuck::cast_slice(&data),
                usage: wgpu::BufferUsages::STORAGE,
            });
        let readback = mk(
            "tile readback",
            (plan.core * plan.core * 8) as u64,
            wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        );
        let mut params = [0u32; 16];
        params[0] = n as u32;
        params[1] = plan.width as u32;
        params[2] = plan.height as u32;
        params[3] = (2 * plan.radius + 1) as u32;
        params[4] = plan.radius as u32;
        params[14] = twiddle_offset;
        params[15] = (plan.scatter as f32).to_bits();
        let buffers = [&a, &b, &spectrum, &source];
        let mut dispatches = 0u64;
        let dispatch = |encoder: &mut wgpu::CommandEncoder,
                        p: [u32; 16],
                        count: usize,
                        dispatches: &mut u64| {
            let uniform = self
                .device
                .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                    label: Some("FFT pass params"),
                    contents: bytemuck::cast_slice(&p),
                    usage: wgpu::BufferUsages::UNIFORM,
                });
            let mut entries: Vec<_> = buffers
                .iter()
                .enumerate()
                .map(|(i, b)| wgpu::BindGroupEntry {
                    binding: i as u32,
                    resource: b.as_entire_binding(),
                })
                .collect();
            entries.push(wgpu::BindGroupEntry {
                binding: 4,
                resource: uniform.as_entire_binding(),
            });
            let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("FFT pass bindings"),
                layout: &self.pipeline.get_bind_group_layout(0),
                entries: &entries,
            });
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("resident FFT pass"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &bind, &[]);
            let groups = count.div_ceil(64) as u32;
            pass.dispatch_workgroups(groups.min(65535), groups.div_ceil(65535), 1);
            *dispatches += 1;
        };
        let fft = |encoder: &mut wgpu::CommandEncoder,
                   p: &mut [u32; 16],
                   current: &mut u32,
                   inverse: bool,
                   dispatches: &mut u64| {
            p[9] = u32::from(inverse);
            for _ in 0..2 {
                p[7] = 2;
                p[10] = *current;
                p[11] = 1 - *current;
                dispatch(encoder, *p, n * n, dispatches);
                *current = 1 - *current;
                let mut span = 2;
                while span <= n {
                    p[7] = 3;
                    p[8] = span as u32;
                    p[10] = *current;
                    p[11] = 1 - *current;
                    dispatch(encoder, *p, n * n, dispatches);
                    *current = 1 - *current;
                    span *= 2;
                }
                p[7] = 4;
                p[10] = *current;
                p[11] = 1 - *current;
                dispatch(encoder, *p, n * n, dispatches);
                *current = 1 - *current;
            }
        };
        let mut encoder = self.device.create_command_encoder(&Default::default());
        params[7] = 1;
        params[11] = 0;
        dispatch(&mut encoder, params, n * n, &mut dispatches);
        let mut current = 0;
        fft(
            &mut encoder,
            &mut params,
            &mut current,
            false,
            &mut dispatches,
        );
        params[7] = 5;
        params[10] = current;
        dispatch(&mut encoder, params, n * n, &mut dispatches);
        self.queue.submit([encoder.finish()]);
        let (output_width, output_height) = roi.unwrap_or((plan.width, plan.height));
        let mut output = vec![0f64; output_width * output_height];
        let mut readback_bytes = 0;
        for output_y in (0..output_height).step_by(plan.core) {
            for output_x in (0..output_width).step_by(plan.core) {
                let (ox, oy) = if roi.is_some() {
                    (plan.radius, plan.radius)
                } else {
                    (output_x, output_y)
                };
                if cancel() || self.error.load(Ordering::Relaxed) {
                    return Err("cancelled or GPU device failure".into());
                }
                let ww = plan.core.min(output_width - output_x);
                let hh = plan.core.min(output_height - output_y);
                params[5] = ox as u32;
                params[6] = oy as u32;
                params[12] = ww as u32;
                params[13] = hh as u32;
                let mut encoder = self.device.create_command_encoder(&Default::default());
                params[7] = 0;
                params[11] = 0;
                dispatch(&mut encoder, params, n * n, &mut dispatches);
                current = 0;
                fft(
                    &mut encoder,
                    &mut params,
                    &mut current,
                    false,
                    &mut dispatches,
                );
                params[7] = 6;
                params[10] = current;
                params[11] = 1 - current;
                dispatch(&mut encoder, params, n * n, &mut dispatches);
                current = 1 - current;
                fft(
                    &mut encoder,
                    &mut params,
                    &mut current,
                    true,
                    &mut dispatches,
                );
                params[7] = 7;
                params[10] = current;
                params[11] = 1 - current;
                dispatch(&mut encoder, params, ww * hh, &mut dispatches);
                current = 1 - current;
                let bytes = (ww * hh * 8) as u64;
                encoder.copy_buffer_to_buffer(
                    if current == 0 { &a } else { &b },
                    0,
                    &readback,
                    0,
                    bytes,
                );
                self.queue.submit([encoder.finish()]);
                let (tx, rx) = mpsc::channel();
                readback
                    .slice(0..bytes)
                    .map_async(wgpu::MapMode::Read, move |r| {
                        let _ = tx.send(r);
                    });
                let waiting = Instant::now();
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
                    if cancel()
                        || waiting.elapsed() > Duration::from_secs(5)
                        || self.error.load(Ordering::Relaxed)
                    {
                        return Err("GPU wait cancelled/timed out/device error; no candidate output accepted".into());
                    }
                    std::thread::sleep(Duration::from_millis(1));
                }
                if cancel() || self.error.load(Ordering::Relaxed) {
                    readback.unmap();
                    return Err("cancelled/device failure after mapping; no output accepted".into());
                }
                let mapped = readback.slice(0..bytes).get_mapped_range();
                let values = bytemuck::cast_slice::<u8, f32>(&mapped);
                for y in 0..hh {
                    for x in 0..ww {
                        let v = values[(y * ww + x) * 2];
                        if !v.is_finite() {
                            return Err("nonfinite GPU output".into());
                        }
                        output[(output_y + y) * output_width + output_x + x] = v as f64;
                    }
                }
                drop(mapped);
                readback.unmap();
                readback_bytes += bytes;
            }
        }
        if cancel() || self.error.load(Ordering::Relaxed) {
            return Err("cancelled/device failure before commit; no output accepted".into());
        }
        Ok((
            output,
            RunMetrics {
                adapter: self.adapter.clone(),
                vendor: self.vendor,
                shader_f64_advertised: self.f64_cap,
                backend: "Vulkan research f32 FFT (not f64)".into(),
                adapter_device_type: format!("{:?}", self.device_type),
                hardware_gpu: matches!(
                    self.device_type,
                    wgpu::DeviceType::IntegratedGpu | wgpu::DeviceType::DiscreteGpu
                ),
                stage_total_ms: start.elapsed().as_secs_f64() * 1000.,
                device_payload_bytes: plan.device_bytes,
                upload_bytes: (data.len() * 4) as u64 + dispatches * 64,
                readback_bytes,
                dispatches,
                tiles: if roi.is_some() { 1 } else { plan.tiles() },
                exact_f64_claim: false,
            },
        ))
    }
}
