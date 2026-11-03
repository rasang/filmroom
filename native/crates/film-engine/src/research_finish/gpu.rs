use super::*;
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};
use wgpu::util::DeviceExt;
pub(super) struct Runner {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    bind: wgpu::BindGroup,
    input: wgpu::Buffer,
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    params: wgpu::Buffer,
    header: [u32; 64],
    error: Arc<AtomicBool>,
    capacity: usize,
    pub adapter: String,
    pub vendor: u32,
    pub f64_cap: bool,
}
impl Runner {
    pub fn new(m: &Manifest, p: &FinishPlan, tables: &[f32]) -> R<Self> {
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
        if !crate::adreno::eligible_adapter(
            info.vendor,
            &info.name,
            info.backend == wgpu::Backend::Vulkan,
            info.device_type == wgpu::DeviceType::IntegratedGpu,
        ) {
            return err("GPU_UNAVAILABLE_OR_LIMITS");
        }
        let largest = (tables.len() as u64 * 4).max(p.chunk_pixels as u64 * 12);
        let l = adapter.limits();
        if largest > l.max_storage_buffer_binding_size as u64
            || largest > l.max_buffer_size
            || l.max_storage_buffers_per_shader_stage < 3
            || l.max_compute_invocations_per_workgroup < 64
            || l.max_compute_workgroup_size_x < 64
            || l.max_compute_workgroups_per_dimension < (p.chunk_pixels + 63) / 64
        {
            return err("GPU_UNAVAILABLE_OR_LIMITS");
        }
        let f64_cap = adapter.features().contains(wgpu::Features::SHADER_F64);
        let mut limits = wgpu::Limits::downlevel_defaults();
        limits.max_storage_buffer_binding_size = largest as u32;
        limits.max_buffer_size = largest.max(64 * MIB);
        let (device, queue) = pollster::block_on(adapter.request_device(
            &wgpu::DeviceDescriptor {
                label: Some("experimental print scanner"),
                required_features: wgpu::Features::empty(),
                required_limits: limits,
                memory_hints: wgpu::MemoryHints::MemoryUsage,
            },
            None,
        ))
        .map_err(|_| "GPU_DEVICE_FAILED")?;
        let error = Arc::new(AtomicBool::new(false));
        let e = error.clone();
        device.on_uncaptured_error(Box::new(move |_| {
            e.store(true, Ordering::Relaxed);
        }));
        device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("resident PCHIP chain"),
            source: wgpu::ShaderSource::Wgsl(SHADER.into()),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: None,
            layout: None,
            module: &shader,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        // Validate the pipeline before querying its bind-group layout.
        let validation = pollster::block_on(device.pop_error_scope());
        let memory = pollster::block_on(device.pop_error_scope());
        if validation.is_some() || memory.is_some() || error.load(Ordering::Relaxed) {
            return err("GPU_DEVICE_FAILED");
        }
        device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let table = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("immutable full tables"),
            contents: bytemuck::cast_slice(tables),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let bytes = p.chunk_pixels as u64 * 12;
        let buffer = |label, size, usage| {
            device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size,
                usage,
                mapped_at_creation: false,
            })
        };
        let input = buffer(
            "input",
            bytes,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        );
        let output = buffer(
            "output",
            bytes,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let readback = buffer(
            "readback",
            bytes,
            wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        );
        let params = buffer(
            "parameters",
            256,
            wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        );
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &pipeline.get_bind_group_layout(0),
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: table.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: input.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: output.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: params.as_entire_binding(),
                },
            ],
        });
        let validation = pollster::block_on(device.pop_error_scope());
        let memory = pollster::block_on(device.pop_error_scope());
        if validation.is_some() || memory.is_some() || error.load(Ordering::Relaxed) {
            return err("GPU_DEVICE_FAILED");
        }
        let mut header = [0; 64];
        header[1] = match m.mode.as_str() { "printFromDensity" => 1, "printExposureOnly" => 2, _ => 0 };
        let mut spec = |base: usize, s: &LutSpec| {
            header[base] = s.size;
            header[base + 1] = s.offset as u32;
            for c in 0..3 {
                header[base + 2 + c] = (s.min[c] as f32).to_bits();
                header[base + 5 + c] = (s.max[c] as f32).to_bits();
            }
        };
        if let Some(s) = &m.printing {
            spec(4, s)
        }
        spec(20, &m.scanner);
        header[36] = m.axis_offset as u32;
        header[37] = m.curve_count;
        header[38] = (m.exposure as f32).to_bits();
        header[39] = (m.bw as f32).to_bits();
        Ok(Self {
            device,
            queue,
            pipeline,
            bind,
            input,
            output,
            readback,
            params,
            header,
            error,
            capacity: p.chunk_pixels as usize,
            adapter: info.name,
            vendor: info.vendor,
            f64_cap,
        })
    }
    pub fn dispatch(&mut self, input: &[f32], cancel: &mut impl FnMut() -> bool) -> R<Vec<f32>> {
        check(cancel)?;
        if input.len() % 3 != 0 || input.len() / 3 > self.capacity || input.is_empty() {
            return err("INVALID_FINISH_CHUNK");
        };
        if self.error.load(Ordering::Relaxed) {
            return err("GPU_DEVICE_FAILED");
        };
        let n = input.len() / 3;
        self.header[0] = n as u32;
        self.queue
            .write_buffer(&self.input, 0, bytemuck::cast_slice(input));
        self.queue
            .write_buffer(&self.params, 0, bytemuck::cast_slice(&self.header));
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor { label: None });
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: None,
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.bind, &[]);
            pass.dispatch_workgroups((n as u32 + 63) / 64, 1, 1);
        }
        let bytes = input.len() as u64 * 4;
        encoder.copy_buffer_to_buffer(&self.output, 0, &self.readback, 0, bytes);
        self.queue.submit(Some(encoder.finish()));
        let slice = self.readback.slice(0..bytes);
        let (tx, rx) = mpsc::channel();
        slice.map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r);
        });
        let start = Instant::now();
        loop {
            self.device.poll(wgpu::Maintain::Poll);
            if cancel() {
                self.readback.unmap();
                return err("CANCELLED");
            };
            if self.error.load(Ordering::Relaxed) {
                self.readback.unmap();
                return err("GPU_DEVICE_FAILED");
            };
            match rx.try_recv() {
                Ok(Ok(())) => break,
                Ok(Err(_)) | Err(mpsc::TryRecvError::Disconnected) => {
                    self.readback.unmap();
                    return err("GPU_FINISH_READBACK_FAILED");
                }
                Err(mpsc::TryRecvError::Empty) => {}
            }
            if start.elapsed() > Duration::from_secs(15) {
                self.readback.unmap();
                return err("GPU_FINISH_TIMEOUT");
            };
            std::thread::sleep(Duration::from_millis(1));
        }
        let output = {
            let mapped = slice.get_mapped_range();
            bytemuck::cast_slice::<u8, f32>(&mapped).to_vec()
        };
        self.readback.unmap();
        Ok(output)
    }
}
