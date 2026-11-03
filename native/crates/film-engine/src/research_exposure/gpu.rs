//! Actual bounded Vulkan dispatch, only inside the isolated export helper.
//! LUT and two matrices stay resident; one input upload and one readback per chunk.
use super::full_stage::*;
use super::{ExposureDescriptor, SHADER, uniform_words};
use std::{
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};
use wgpu::util::DeviceExt;
struct Runner {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::ComputePipeline,
    group: wgpu::BindGroup,
    input: wgpu::Buffer,
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    uniform: wgpu::Buffer,
    numeric_error: wgpu::Buffer,
    error: Arc<AtomicBool>,
    chunk_pixels: u32,
}
struct Metrics {
    vendor: u32,
    f64_advertised: bool,
    setup_ms: f64,
    upload_ms: f64,
    dispatch_readback_ms: f64,
}
impl Runner {
    fn new(
        d: &ExposureDescriptor,
        p: &ExposurePlan,
        allow_probe: bool,
        c: &mut impl FnMut() -> bool,
    ) -> R<(Self, Metrics)> {
        check(c)?;
        let at = Instant::now();
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
            return err("GPU_UNAVAILABLE_OR_LIMITS");
        }
        let bytes = p.chunk_pixels as u64 * 12;
        let largest = bytes.max(p.table_scalars * 4);
        let l = adapter.limits();
        if largest > l.max_storage_buffer_binding_size as u64
            || largest > l.max_buffer_size
            || l.max_storage_buffers_per_shader_stage < 4
            || l.max_compute_workgroup_size_x < 64
            || l.max_compute_invocations_per_workgroup < 64
            || l.max_compute_workgroups_per_dimension < p.chunk_pixels.div_ceil(64)
        {
            return err("GPU_UNAVAILABLE_OR_LIMITS");
        }
        let mut limits = wgpu::Limits::downlevel_defaults();
        limits.max_storage_buffer_binding_size = largest as u32;
        limits.max_buffer_size = largest.max(64 * MIB);
        limits.max_storage_buffers_per_shader_stage = 4;
        let f64_advertised = adapter.features().contains(wgpu::Features::SHADER_F64);
        check(c)?;
        let (device, queue) = pollster::block_on(adapter.request_device(
            &wgpu::DeviceDescriptor {
                label: Some("isolated original film exposure"),
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
        device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("original two matrices and bicubic TC"),
            source: wgpu::ShaderSource::Wgsl(SHADER.into()),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("resident film exposure"),
            layout: None,
            module: &shader,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        // Do not inspect layouts of a failed pipeline.
        let validation = pollster::block_on(device.pop_error_scope());
        let memory = pollster::block_on(device.pop_error_scope());
        if validation.is_some() || memory.is_some() || error.load(Ordering::Relaxed) {
            return err("GPU_UNAVAILABLE_OR_LIMITS");
        }
        device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let make = |label, size, usage| {
            device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size,
                usage,
                mapped_at_creation: false,
            })
        };
        let input = make(
            "exposure input chunk",
            bytes,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        );
        let output = make(
            "exposure output chunk",
            bytes,
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let readback = make(
            "exposure bounded map",
            bytes + 4,
            wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        );
        let numeric_error = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("sticky exposure numeric error"),
            contents: &0u32.to_le_bytes(),
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        });
        let table: Vec<f32> = d.table.iter().map(|&v| v as f32).collect();
        let table = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("resident original full TC table"),
            contents: bytemuck::cast_slice(&table),
            usage: wgpu::BufferUsages::STORAGE,
        });
        let params = uniform_words(d, p.chunk_pixels);
        let uniform = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("resident original exposure matrices"),
            contents: bytemuck::cast_slice(&params),
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        });
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("exposure resident bindings"),
            layout: &pipeline.get_bind_group_layout(0),
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: uniform.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: input.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: table.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 4,
                    resource: numeric_error.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: output.as_entire_binding(),
                },
            ],
        });
        let validation = pollster::block_on(device.pop_error_scope());
        let memory = pollster::block_on(device.pop_error_scope());
        if validation.is_some() || memory.is_some() || error.load(Ordering::Relaxed) {
            return err("GPU_UNAVAILABLE_OR_LIMITS");
        }
        check(c)?;
        Ok((
            Self {
                device,
                queue,
                pipeline,
                group,
                input,
                output,
                readback,
                uniform,
                numeric_error,
                error,
                chunk_pixels: p.chunk_pixels,
            },
            Metrics {
                vendor: info.vendor,
                f64_advertised,
                setup_ms: at.elapsed().as_secs_f64() * 1000.,
                upload_ms: 0.,
                dispatch_readback_ms: 0.,
            },
        ))
    }
    fn dispatch(
        &self,
        source: &[u8],
        pixels: usize,
        c: &mut impl FnMut() -> bool,
        m: &mut Metrics,
    ) -> R<Vec<f32>> {
        if pixels == 0 || pixels > self.chunk_pixels as usize || source.len() != pixels * 12 {
            return err("INVALID_EXPOSURE_CHUNK");
        }
        check(c)?;
        let at = Instant::now();
        // Update only the pixel bound. Matrices and full TC LUT stay resident.
        self.queue
            .write_buffer(&self.uniform, 0, &(pixels as u32).to_le_bytes());
        self.queue.write_buffer(&self.input, 0, source);
        m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
        let at = Instant::now();
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("original exposure chunk"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.group, &[]);
            pass.dispatch_workgroups((pixels as u32).div_ceil(64), 1, 1);
        }
        encoder.copy_buffer_to_buffer(&self.output, 0, &self.readback, 0, source.len() as u64);
        encoder.copy_buffer_to_buffer(
            &self.numeric_error,
            0,
            &self.readback,
            source.len() as u64,
            4,
        );
        self.queue.submit([encoder.finish()]);
        let (tx, rx) = mpsc::channel();
        self.readback
            .slice(0..source.len() as u64 + 4)
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = tx.send(r);
            });
        let result = (|| {
            loop {
                check(c)?;
                if self.error.load(Ordering::Relaxed) {
                    return err("GPU_DEVICE_FAILED");
                }
                let _ = self.device.poll(wgpu::Maintain::Poll);
                match rx.try_recv() {
                    Ok(Ok(())) => break,
                    Ok(Err(_)) | Err(mpsc::TryRecvError::Disconnected) => {
                        return err("GPU_EXPOSURE_READBACK_FAILED");
                    }
                    Err(mpsc::TryRecvError::Empty) => {}
                }
                if at.elapsed() > Duration::from_secs(5) {
                    return err("GPU_EXPOSURE_TIMEOUT");
                }
                std::thread::sleep(Duration::from_millis(1));
            }
            let view = self
                .readback
                .slice(0..source.len() as u64 + 4)
                .get_mapped_range();
            if u32::from_le_bytes(view[source.len()..source.len() + 4].try_into().unwrap()) != 0 {
                return err("GPU_EXPOSURE_NUMERIC_DOMAIN");
            }
            let out = view[..source.len()]
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                .collect();
            drop(view);
            Ok(out)
        })();
        self.readback.unmap();
        m.dispatch_readback_ms += at.elapsed().as_secs_f64() * 1000.;
        result
    }
}
pub(super) fn run(
    dir: &Path,
    m: &Manifest,
    p: &ExposurePlan,
    c: &mut impl FnMut() -> bool,
    allow_probe: bool,
) -> R<serde_json::Value> {
    let at = Instant::now();
    let d = read_descriptor(dir, m, p, c)?;
    let (runner, mut metrics) = Runner::new(&d, p, allow_probe, c)?;
    let samples = super::streaming::write_candidate(dir, p, &d, c, |source, count, cancel| {
        runner.dispatch(source, count, cancel, &mut metrics)
    })?;
    Ok(serde_json::json!({
        "ok": true, "schemaVersion": 1, "operation": OPERATION, "token": p.token, "manifestSha256": p.manifest_sha256,
        "gpuUsed": true, "fullImage": true, "width": p.width, "height": p.height, "channels": 3,
        "chunkCount": p.chunk_count, "elapsedMs": at.elapsed().as_secs_f64()*1000., "backend": BACKEND,
        "vendor": metrics.vendor, "shaderF64Advertised": metrics.f64_advertised,
        "uploadBytes": p.table_scalars*4 + 128 + p.width as u64*p.height as u64*12 + p.chunk_count*4 + 4,
        "readbackBytes": p.width as u64*p.height as u64*12 + p.chunk_count*4, "dispatches": p.chunk_count, "deviceBytes": p.device_bytes,
        "sampleCount": samples.count, "sampleMaxAbs": samples.max_abs, "sampleRms": samples.rms(),
        "sampleToleranceRelative": super::NUMERIC_TOLERANCE, "sampleToleranceAbsolute": super::NUMERIC_TOLERANCE,
        "setupMs": metrics.setup_ms, "uploadMs": metrics.upload_ms, "dispatchReadbackMs": metrics.dispatch_readback_ms,
        "guardMs": samples.guard_ms,
    }))
}

#[cfg(test)]
mod numeric_tests {
    use super::*;
    #[test]
    fn ordered_exposure_domain_rejects_before_overflow() {
        let bound = 1e15f64;
        let raw = 2e38f32;
        assert!((raw as f64 * 2. * 0.5).is_finite());
        assert!(raw.abs() as f64 > bound);
        assert!(SHADER.contains("let ae_rgb=scale("));
        assert!(SHADER.contains("let rgb=scale(ae_rgb,p.exposure)"));
        assert!(SHADER.contains("@binding(4)"));
    }
    #[test]
    #[ignore = "explicit Vulkan probe; not mobile performance evidence"]
    fn actual_vulkan_unsampled_exposure_overflow_has_no_candidate() {
        let root = std::env::temp_dir().join(format!("exposure-domain-{}", std::process::id()));
        std::fs::create_dir(&root).unwrap();
        let source = root.join("source");
        let dir = root.join("gpu-stage-7");
        let mut d = crate::research_exposure::tests::fixture(5);
        d.auto_exposure = 2.;
        d.exposure = 0.5;
        let mut values = vec![0.25f32; 64 * 3];
        values[3] = 2e38;
        assert!(!crate::research_exposure::streaming::sample_indices(64).contains(&1));
        std::fs::write(&source, bytemuck::cast_slice(&values)).unwrap();
        let p =
            crate::research_exposure::prepare_stage(&source, &dir, 7, 64, 1, &d, || false).unwrap();
        let (r, mut m) = Runner::new(&d, &p, true, &mut || false).unwrap();
        let result = crate::research_exposure::streaming::write_candidate(
            &dir,
            &p,
            &d,
            &mut || false,
            |b, n, c| r.dispatch(b, n, c, &mut m),
        );
        assert_eq!(result.err().unwrap(), "GPU_EXPOSURE_NUMERIC_DOMAIN");
        assert!(!dir.join("candidate").exists());
        assert!(!dir.join(".candidate.partial").exists());
        // Failure is sticky even if the next chunk itself is ordinary.
        values[3] = 0.25;
        assert_eq!(
            r.dispatch(bytemuck::cast_slice(&values), 64, &mut || false, &mut m)
                .unwrap_err(),
            "GPU_EXPOSURE_NUMERIC_DOMAIN"
        );
        std::fs::remove_dir_all(root).unwrap();
    }
}
