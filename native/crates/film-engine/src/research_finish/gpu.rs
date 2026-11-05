use super::*;
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};

pub(super) struct Runner {
    _cleanup_start: trace::CleanupStart,
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
    _cleanup_end: trace::CleanupEnd,
    context: crate::gpu_session::Context,
}
impl Runner {
    pub fn new(m: &Manifest, p: &FinishPlan, tables: &[f32]) -> R<Self> {
        let device_phase = PhaseGuard::begin(GpuPhase::Device);
        let context = crate::gpu_session::Context::acquire(
            (tables.len() as u64 * 4).max(p.chunk_pixels as u64 * 12),
            3,
            false,
        )?;
        let device = context.device.clone();
        let queue = context.queue.clone();
        let error = context.error.clone();
        let info = context.info.clone();
        let f64_cap = context.f64_cap;
        device_phase.finish();
        let pipeline_phase = PhaseGuard::begin(GpuPhase::Pipeline);
        let pipeline = context.pipelines(&[SHADER], None)?.0.remove(0);
        pipeline_phase.finish();

        let upload_phase = PhaseGuard::begin(GpuPhase::Upload);
        device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let table = context.buffer_init(
            &wgpu::util::BufferInitDescriptor {
                label: Some("immutable full tables"),
                contents: bytemuck::cast_slice(tables),
                usage: wgpu::BufferUsages::STORAGE,
            },
            false,
        )?;
        let bytes = p.chunk_pixels as u64 * 12;
        let buffer = |label, size, usage| {
            context.buffer(
                &wgpu::BufferDescriptor {
                    label: Some(label),
                    size,
                    usage,
                    mapped_at_creation: false,
                },
                false,
            )
        };
        let input = buffer(
            "input",
            bytes,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        )?;
        let output = buffer(
            "output",
            bytes,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        )?;
        let readback = buffer(
            "readback",
            bytes,
            wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        )?;
        let params = buffer(
            "parameters",
            256,
            wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        )?;
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
        header[1] = match m.mode.as_str() {
            "printFromDensity" => 1,
            "printExposureOnly" => 2,
            _ => 0,
        };
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
        upload_phase.finish();
        let (_cleanup_start, _cleanup_end) = trace::cleanup_bounds();
        Ok(Self {
            _cleanup_start,
            _cleanup_end,
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
            context,
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
        let upload_phase = PhaseGuard::begin(GpuPhase::Upload);
        self.queue
            .write_buffer(&self.input, 0, bytemuck::cast_slice(input));
        self.queue
            .write_buffer(&self.params, 0, bytemuck::cast_slice(&self.header));
        upload_phase.finish();
        let compute_phase = PhaseGuard::begin(GpuPhase::Compute);
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
        let readback_phase = PhaseGuard::begin(GpuPhase::Readback);
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
        readback_phase.finish();
        compute_phase.finish();
        Ok(output)
    }
}
