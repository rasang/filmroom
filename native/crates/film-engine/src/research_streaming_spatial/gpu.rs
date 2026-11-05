//! Small-buffer Vulkan executor of the original resident shader families.
use super::*;
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase};
use std::{
    collections::BTreeMap,
    sync::{atomic::Ordering, mpsc},
    time::{Duration, Instant},
};

pub(super) struct Gpu {
    planes: [wgpu::Buffer; 5],
    probe: wgpu::Buffer,
    numeric_error: wgpu::Buffer,
    map: wgpu::Buffer,
    pipelines: Vec<wgpu::ComputePipeline>,
    routes: BTreeMap<u32, usize>,
    metrics: StreamMetrics,
    // Kept after resource handles: dropping a Context must not make a session
    // stage sealable while this executor still owns buffers or pipelines.
    context: crate::gpu_session::Context,
}

impl Gpu {
    pub(super) fn new(plan: &StreamPlan, allow_probe: bool) -> R<Self> {
        plan.validate()?;
        let families = spatial::families::shaders_for_optics(&plan.semantic_plan()?)?;
        Self::with_families(&families, allow_probe)
    }
    fn with_families(families: &[(Vec<u32>, String)], allow_probe: bool) -> R<Self> {
        let context = trace::phase_result(GpuPhase::Device, || {
            crate::gpu_session::Context::acquire(BATCH_BYTES + 4, 7, allow_probe)
        })?;
        let entries: Vec<_> = (0..8)
            .map(|index| wgpu::BindGroupLayoutEntry {
                binding: index,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Buffer {
                    ty: if index == 6 {
                        wgpu::BufferBindingType::Uniform
                    } else {
                        wgpu::BufferBindingType::Storage { read_only: false }
                    },
                    has_dynamic_offset: false,
                    min_binding_size: wgpu::BufferSize::new(if index == 6 { 160 } else { 4 }),
                },
                count: None,
            })
            .collect();
        let sources: Vec<&str> = families.iter().map(|(_, source)| source.as_str()).collect();
        let (pipelines, _) = trace::phase_result(GpuPhase::Pipeline, || {
            context.pipelines(&sources, Some(&entries))
        })?;
        let routes = families
            .iter()
            .enumerate()
            .flat_map(|(index, (ops, _))| ops.iter().map(move |op| (*op, index)))
            .collect();
        let usage = wgpu::BufferUsages::STORAGE
            | wgpu::BufferUsages::COPY_SRC
            | wgpu::BufferUsages::COPY_DST;
        let make = |label, size, usage| {
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
        let planes = (0..5)
            .map(|_| make("bounded full-line plane", BATCH_BYTES, usage))
            .collect::<R<Vec<_>>>()?
            .try_into()
            .map_err(|_| "GPU_STREAM_MEMORY_LIMIT")?;
        let probe = make("original guarded spatial ABI probe", BATCH_BYTES, usage)?;
        // A Context checkout zeroes reused allocations before handing them out.
        // Never clear the numeric flag between batches or channels.
        let numeric_error = make("sticky full-stage numeric failure", 4, usage)?;
        let map = make(
            "bounded full-line readback plus error flag",
            BATCH_BYTES + 4,
            wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        )?;
        let metrics = StreamMetrics {
            adapter: context.info.name.clone(),
            vendor: context.info.vendor,
            layout: LAYOUT.into(),
            full_resolution: true,
            device_bytes: DEVICE_BYTES,
            ..Default::default()
        };
        Ok(Self {
            planes,
            probe,
            numeric_error,
            map,
            pipelines,
            routes,
            metrics,
            context,
        })
    }
    pub(super) fn fail(&self) {
        // Unknown queue completion or a failed numerical guard invalidates this
        // helper epoch. It is not a process-death or file-release certificate.
        self.context.error.store(true, Ordering::Release);
    }
    fn execute_inner(
        &mut self,
        inputs: &[(u32, &[f32])],
        p: [u32; 40],
        output: u32,
        c: &mut impl FnMut() -> bool,
    ) -> R<Vec<f32>> {
        check(c)?;
        if self.context.error.load(Ordering::Acquire) {
            return Err("GPU_DEVICE_FAILED".into());
        }
        let count = (p[0] as u64)
            .checked_mul(p[1] as u64)
            .ok_or("GPU_STREAM_LINE_LIMIT")?;
        let (expected, target): (&[u32], u32) = match p[2] {
            0 | 2 | 4 if p[6] == 0 && p[7] == 1 => (&[0], 1),
            6 => (&[4, 2], 4),
            7 => (&[0, 3, 4], 0),
            8 => (&[0, 4], 0),
            9 => (&[0], 0),
            56 | 57 if p[26] == 0 => (&[4, 0], 4),
            58 => (&[4], 0),
            59 => (&[0, 3], 0),
            60 => (&[0], 0),
            _ => return Err("GPU_STREAM_DISPATCH_CONTRACT".into()),
        };
        if count == 0
            || count > BATCH_SAMPLES as u64
            || output != target
            || p[3] != 0
            || p[4] != if p[2] == 2 { p[1] } else { count as u32 }
            || inputs.len() != expected.len()
            || inputs
                .iter()
                .zip(expected)
                .any(|((slot, data), expected)| slot != expected || data.len() as u64 != count)
        {
            return Err("GPU_STREAM_DISPATCH_CONTRACT".into());
        }
        let index = *self
            .routes
            .get(&p[2])
            .ok_or("GPU_STREAM_DISPATCH_CONTRACT")?;
        let pipeline = &self.pipelines[index];
        let upload_started = Instant::now();
        trace::phase_result(GpuPhase::Upload, || -> R<()> {
            for &(slot, values) in inputs {
                check(c)?;
                self.context.queue.write_buffer(
                    &self.planes[slot as usize],
                    0,
                    bytemuck::cast_slice(values),
                );
                self.metrics.upload_bytes += values.len() as u64 * 4;
            }
            Ok(())
        })?;
        self.metrics.upload_ms += upload_started.elapsed().as_secs_f64() * 1000.;
        let uniform = self.context.buffer_init(
            &wgpu::util::BufferInitDescriptor {
                label: Some("immutable complete-line params"),
                contents: bytemuck::cast_slice(&p),
                usage: wgpu::BufferUsages::UNIFORM,
            },
            true,
        )?;
        let mut entries: Vec<_> = self
            .planes
            .iter()
            .enumerate()
            .map(|(index, buffer)| wgpu::BindGroupEntry {
                binding: index as u32,
                resource: buffer.as_entire_binding(),
            })
            .collect();
        entries.extend([
            wgpu::BindGroupEntry {
                binding: 5,
                resource: self.probe.as_entire_binding(),
            },
            wgpu::BindGroupEntry {
                binding: 6,
                resource: uniform.as_entire_binding(),
            },
            wgpu::BindGroupEntry {
                binding: 7,
                resource: self.numeric_error.as_entire_binding(),
            },
        ]);
        let group = self
            .context
            .device
            .create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("unchanged guarded spatial ABI"),
                layout: &pipeline.get_bind_group_layout(0),
                entries: &entries,
            });
        let mut encoder = self
            .context
            .device
            .create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("original full-line spatial arithmetic"),
                timestamp_writes: None,
            });
            pass.set_pipeline(pipeline);
            pass.set_bind_group(0, &group, &[]);
            let groups = (p[4] as u64).div_ceil(64);
            pass.dispatch_workgroups(groups.min(65535) as u32, groups.div_ceil(65535) as u32, 1);
        }
        let bytes = count * 4;
        encoder.copy_buffer_to_buffer(&self.planes[output as usize], 0, &self.map, 0, bytes);
        encoder.copy_buffer_to_buffer(&self.numeric_error, 0, &self.map, bytes, 4);
        check(c)?;
        self.context.queue.submit([encoder.finish()]);
        self.metrics.dispatches += 1;
        self.metrics.upload_bytes += 160;
        let (tx, rx) = mpsc::channel();
        self.map
            .slice(0..bytes + 4)
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = tx.send(r);
            });
        let start = Instant::now();
        let result = trace::phase_result(GpuPhase::Readback, || {
            loop {
                check(c)?;
                if self.context.error.load(Ordering::Acquire) {
                    return Err("GPU_DEVICE_FAILED".into());
                }
                let _ = self.context.device.poll(wgpu::Maintain::Poll);
                match rx.try_recv() {
                    Ok(Ok(())) => break,
                    Ok(Err(_)) | Err(mpsc::TryRecvError::Disconnected) => {
                        return Err("GPU_STREAM_READBACK_FAILED".into());
                    }
                    Err(mpsc::TryRecvError::Empty) => {}
                }
                if start.elapsed() > Duration::from_secs(5) {
                    return Err("GPU_STREAM_TIMEOUT".into());
                }
                std::thread::sleep(Duration::from_millis(1));
            }
            let mapped = self.map.slice(0..bytes + 4).get_mapped_range();
            let flag = u32::from_le_bytes(
                mapped[bytes as usize..bytes as usize + 4]
                    .try_into()
                    .unwrap(),
            );
            if flag != 0 {
                return Err("GPU_OPTICS_NUMERIC_DOMAIN".into());
            }
            let result: Vec<f32> = mapped[..bytes as usize]
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                .collect();
            if result.iter().any(|value| !value.is_finite()) {
                return Err("NONFINITE_OPTICS_OUTPUT".into());
            }
            Ok(result)
        });
        self.map.unmap();
        self.metrics.compute_readback_ms += start.elapsed().as_secs_f64() * 1000.;
        if result.is_ok() {
            self.context.release_transients_after_fence()?;
            self.metrics.readback_bytes += bytes + 4;
        }
        result
    }
}

#[cfg(test)]
mod cancellation_tests {
    use super::*;
    #[test]
    #[ignore = "explicit Vulkan in-flight cancellation, not hardware speed evidence"]
    fn post_submit_cancel_poisons_executor_and_never_reuses_transients() {
        let mut rp = RuntimeParams::default();
        rp.camera.lens_blur_um = 4.;
        let plan = StreamPlan::new(&rp, 1., 8192, 128).unwrap();
        let mut gpu = Gpu::new(&plan, true).unwrap();
        let values = vec![0.5; 8192 * 128];
        let mut p = [0u32; 40];
        p[0] = 8192;
        p[1] = 128;
        p[2] = 2;
        p[4] = 128;
        p[6] = 0;
        p[7] = 1;
        let Filter::Iir(coefficients) = Filter::new(4.).unwrap() else {
            panic!()
        };
        for (i, value) in coefficients.into_iter().enumerate() {
            p[8 + i] = (value as f32).to_bits();
        }
        let mut checks = 0;
        let error = gpu
            .execute(&[(0, &values)], p, 1, &mut || {
                checks += 1;
                checks >= 4
            })
            .unwrap_err();
        assert_eq!(error, "CANCELLED");
        assert_eq!(
            gpu.metrics.dispatches, 1,
            "cancel must occur after actual submit"
        );
        assert!(gpu.context.error.load(Ordering::Acquire));
        assert_eq!(
            gpu.execute(&[(0, &values)], p, 1, &mut || false)
                .unwrap_err(),
            "GPU_DEVICE_FAILED"
        );
        assert_eq!(gpu.metrics.dispatches, 1);
        drop(gpu);
        // A distinct nonshared device may be created only after the failed
        // executor is dropped. Shared helper reuse remains forbidden by Context.
        let mut next = Gpu::new(&plan, true).unwrap();
        let actual = next.execute(&[(0, &values)], p, 1, &mut || false).unwrap();
        assert!(actual.iter().all(|x| (*x - 0.5).abs() < 1e-4));
    }
}
impl Compute for Gpu {
    fn execute(
        &mut self,
        inputs: &[(u32, &[f32])],
        p: [u32; 40],
        output: u32,
        c: &mut impl FnMut() -> bool,
    ) -> R<Vec<f32>> {
        let result = self.execute_inner(inputs, p, output, c);
        if result.is_err() {
            self.fail();
        }
        result
    }
    fn metrics(&mut self) -> &mut StreamMetrics {
        &mut self.metrics
    }
}
