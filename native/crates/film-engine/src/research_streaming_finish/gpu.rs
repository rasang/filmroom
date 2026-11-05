//! A single typed original Runner owns every finish/glare/spatial operation.
use super::*;
use crate::research_finish::fused::{self, FinishPrefix};
use crate::research_finish_tail::{self as tail, glare};
use crate::research_spatial::{
    check,
    gpu::{RunMetrics, Runner, params},
};
use crate::research_streaming_spatial::{self as stream, Compute, StreamMetrics};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::Path,
    time::Instant,
};
use stream::files::{Plane, Workspace, blur, point_params};

pub(crate) struct StreamingFinish {
    runner: Runner,
    plan: StreamFinishPlan,
    prefix_digest: Option<[u8; 32]>,
    metrics: RunMetrics,
    spatial: StreamMetrics,
    failed: bool,
    complete: bool,
    capacity: u32,
    glare_words: u64,
    glare_chunks: u64,
    prefix_samples: u64,
    started: Instant,
}
impl StreamingFinish {
    pub(crate) fn new(_plan: &StreamFinishPlan, _c: &mut impl FnMut() -> bool) -> R<Self> {
        Err("GPU_FINISH_STREAM_PENDING".into())
    }
    /// Fused entry preserves the immutable prefix binding when production
    /// admission is enabled; until then it has the same closed gate as new().
    pub(crate) fn new_with_prefix(
        _plan: &StreamFinishPlan,
        _prefix: &FinishPrefix,
        _c: &mut impl FnMut() -> bool,
    ) -> R<Self> {
        Err("GPU_FINISH_STREAM_PENDING".into())
    }
    #[cfg(test)]
    pub(crate) fn new_probe(
        plan: &StreamFinishPlan,
        prefix: Option<&FinishPrefix>,
        c: &mut impl FnMut() -> bool,
    ) -> R<Self> {
        Self::construct(plan, prefix, c).map_err(|e| {
            crate::gpu_session::retire_current();
            e
        })
    }
    #[cfg(test)]
    pub(crate) fn new_probe_with_capacity(
        plan: &StreamFinishPlan,
        prefix: Option<&FinishPrefix>,
        capacity: u32,
        c: &mut impl FnMut() -> bool,
    ) -> R<Self> {
        if capacity < plan.width.max(plan.height) || capacity > stream::BATCH_SAMPLES {
            return Err("GPU_STREAM_LINE_LIMIT".into());
        }
        let mut owner = Self::new_probe(plan, prefix, c)?;
        owner.capacity = capacity;
        Ok(owner)
    }
    fn construct(
        plan: &StreamFinishPlan,
        prefix: Option<&FinishPrefix>,
        c: &mut impl FnMut() -> bool,
    ) -> R<Self> {
        let started = Instant::now();
        plan.numerical_preflight(c)?;
        let memory = plan.memory()?;
        let prefix_digest = prefix_identity(plan, prefix)?;
        let families = shader_families(plan)?;
        // Exactly 4 MiB per scalar plane; real dimensions remain in plan.
        let allocation = spatial::FilmOpticsPlan::new(
            &spektrafilm_core::params::RuntimeParams::default(),
            1.,
            1024,
            1024,
        )?;
        let (runner, mut metrics) = if let Some(prefix) = prefix {
            Runner::new_with_shader_family_error_tables_explicit_layout(
                &allocation,
                true,
                &families,
                prefix.storage(),
            )?
        } else {
            Runner::new_with_fixed_iir_families(&allocation, true, &families)?
        };
        metrics.device_bytes = memory.device_bytes;
        metrics.sample_scope = "full_line_f64_tail_f32_and_optional_prefix_f64_witnesses";
        if let Some(p) = prefix {
            metrics.upload_bytes += p.storage().len() as u64 * 4;
        }
        let table = plan.tail.table32();
        runner.upload_aux(
            tail::TABLE_BASE as u64 * 4,
            bytemuck::cast_slice(&table),
            c,
            &mut metrics,
        )?;
        let spatial = StreamMetrics {
            adapter: metrics.adapter.clone(),
            vendor: metrics.vendor,
            layout: LAYOUT.into(),
            full_resolution: true,
            device_bytes: memory.device_bytes,
            ..Default::default()
        };
        Ok(Self {
            runner,
            plan: plan.clone(),
            prefix_digest,
            metrics,
            spatial,
            failed: false,
            complete: false,
            capacity: stream::BATCH_SAMPLES,
            glare_words: 0,
            glare_chunks: 0,
            prefix_samples: 0,
            started,
        })
    }
    pub(crate) fn poison(&mut self) {
        self.failed = true;
        self.runner.invalidate_streaming();
    }
    fn terminal<T>(&mut self, result: R<T>) -> R<T> {
        if result.is_err() {
            self.poison();
        }
        result
    }
    fn ready(&self) -> R<()> {
        if self.failed {
            Err("GPU_FINISH_STREAM_FAILED".into())
        } else if self.complete {
            Err("GPU_FINISH_STREAM_COMPLETE".into())
        } else {
            Ok(())
        }
    }
    pub(crate) fn run_files(
        &mut self,
        source: &Path,
        glare: Option<&Path>,
        output: &Path,
        scratch: &Path,
        c: &mut impl FnMut() -> bool,
    ) -> R<serde_json::Value> {
        self.ready()?;
        let result = self.run_inner(Some(source), glare, output, scratch, None, c);
        self.terminal(result)
    }
    /// Owns prefix so all three source file handles close before returning.
    /// The owner still holds a live Context: this result is NOT a native seal.
    pub(crate) fn run_with_prefix(
        &mut self,
        prefix: FinishPrefix,
        output: &Path,
        scratch: &Path,
        c: &mut impl FnMut() -> bool,
    ) -> R<serde_json::Value> {
        self.ready()?;
        let result = self.run_inner(None, None, output, scratch, Some(prefix), c);
        self.terminal(result)
    }
    fn run_inner(
        &mut self,
        source: Option<&Path>,
        glare_file: Option<&Path>,
        output: &Path,
        scratch: &Path,
        mut prefix: Option<FinishPrefix>,
        c: &mut impl FnMut() -> bool,
    ) -> R<serde_json::Value> {
        check(c)?;
        let plan = self.plan.clone();
        let capacity = self.capacity;
        let p = &plan.tail;
        let n = plan.width as u64 * plan.height as u64;
        validate_prefix_binding(&plan, prefix.as_ref(), self.prefix_digest)?;
        if source.is_some() == prefix.is_some() || glare_file.is_some() != plan.external_glare {
            return Err("GPU_FINISH_STREAM_IDENTITY".into());
        }
        validate_paths(source, glare_file, output, scratch, n)?;
        let mut ws = Workspace::new(scratch, n)?;
        // Glare is completely generated/imported before conversion, retaining
        // the original single sequential random-word cursor across all windows.
        let mut glare_field = if p.options.glare.is_some() {
            let field = ws.plane()?;
            if let Some(path) = glare_file {
                self.import_glare(path, &field, n, c)?;
            } else {
                self.generate_glare(&field, n, c)?;
            }
            Some(field)
        } else {
            None
        };
        if let (Some(field), Some(g)) = (glare_field.as_ref(), p.options.glare.as_ref()) {
            if g.blur > 0. {
                glare_field = Some(blur(
                    &mut ws,
                    field,
                    plan.width,
                    plan.height,
                    g.blur,
                    capacity,
                    self,
                    c,
                )?);
            }
        }
        let mut channels = [Some(ws.plane()?), Some(ws.plane()?), Some(ws.plane()?)];
        let mut input = source.map(|path| io(File::open(path))).transpose()?;
        let mut start = 0;
        while start < n {
            check(c)?;
            let count = (n - start).min(tail::CHUNK_PIXELS as u64) as usize;
            let values = if let Some(prefix) = prefix.as_mut() {
                prefix.read_chunk(count)?
            } else {
                read64(
                    input.as_mut().ok_or("GPU_FINISH_STREAM_IDENTITY")?,
                    count * 3,
                )?
            };
            let glare = glare_field
                .as_ref()
                .map(|field| field.read(start, count))
                .transpose()?;
            if let Some(glare) = glare.as_ref() {
                self.runner
                    .upload_stream_plane(0, glare, c, &mut self.metrics)?;
            }
            self.runner.upload_aux(
                tail::CHUNK_BASE as u64 * 4,
                bytemuck::cast_slice(&values),
                c,
                &mut self.metrics,
            )?;
            let mut v = params(count as u32, 1, if prefix.is_some() { 40 } else { 21 });
            v[4] = count as u32;
            v[6] = u32::from(glare.is_some());
            v[7] = 0; // file offset is u64; pointwise GPU offsets are always local
            self.runner.dispatch(v, c, &mut self.metrics)?;
            self.check_numeric(c)?;
            let samples = count.min(8);
            let mut v = params(count as u32, 1, 25);
            v[4] = samples as u32;
            v[5] = count as u32;
            v[7] = 0;
            self.runner.dispatch(v, c, &mut self.metrics)?;
            let witnesses = numbers(&self.runner.read_probe(
                if prefix.is_some() {
                    1120
                } else {
                    samples as u64 * 12
                },
                c,
                &mut self.metrics,
            )?);
            let guard_started = Instant::now();
            for j in 0..samples {
                let index = if samples == 1 {
                    0
                } else {
                    j * (count - 1) / (samples - 1)
                };
                let logs = if let Some(prefix) = prefix.as_mut() {
                    self.prefix_samples += 3;
                    prefix.witness(
                        index,
                        [
                            witnesses[256 + 3 * j],
                            witnesses[257 + 3 * j],
                            witnesses[258 + 3 * j],
                        ],
                    )?
                } else {
                    [
                        values[3 * index],
                        values[3 * index + 1],
                        values[3 * index + 2],
                    ]
                };
                let expected =
                    tail::color_mirror(p, logs, glare.as_ref().map_or(0., |g| g[index]))?;
                point_guard(expected, &witnesses[3 * j..3 * j + 3], &mut self.metrics)?;
            }
            self.metrics.guard_ms += guard_started.elapsed().as_secs_f64() * 1000.;
            for (ch, slot) in [0, 3, 4].into_iter().enumerate() {
                let bytes =
                    self.runner
                        .read_stream_plane(slot, count as u64 * 4, c, &mut self.metrics)?;
                let values = checked_numbers(&bytes)?;
                channels[ch]
                    .as_ref()
                    .ok_or("GPU_FINISH_STREAM_STATE")?
                    .write(start, &values)?;
            }
            start += count as u64;
        }
        if let Some(prefix) = prefix.as_ref() {
            prefix.complete()?;
            self.metrics.sample_count += self.prefix_samples;
            self.metrics.sample_sum += prefix.sample_sum;
            self.metrics.sample_max_abs = self.metrics.sample_max_abs.max(prefix.sample_max);
        }
        drop(prefix);
        drop(input);
        drop(glare_field);
        // Five f32 files maximum: three channels plus horizontal/output, or
        // three channels plus sharpen blur and new clipped output.
        for slot in &mut channels {
            let mut source = slot.take().ok_or("GPU_FINISH_STREAM_STATE")?;
            if p.options.lens_sigma > 0. {
                source = blur(
                    &mut ws,
                    &source,
                    plan.width,
                    plan.height,
                    p.options.lens_sigma,
                    capacity,
                    self,
                    c,
                )?;
            }
            let sharp = p.options.sharp_radius > 0. && p.options.sharp_amount > 0.;
            let blurred = if sharp {
                Some(blur(
                    &mut ws,
                    &source,
                    plan.width,
                    plan.height,
                    p.options.sharp_radius,
                    capacity,
                    self,
                    c,
                )?)
            } else {
                None
            };
            let mut v = [0; 40];
            v[2] = 23;
            v[6] = u32::from(sharp);
            v[12] = p.options.sharp_amount.to_bits();
            let mut inputs = vec![(0, &source)];
            if let Some(b) = blurred.as_ref() {
                inputs.push((2, b));
            }
            let clipped = point_params(&mut ws, &inputs, v, 0, capacity, self, c)?;
            drop(inputs);
            drop(blurred);
            drop(source);
            *slot = Some(clipped);
        }
        self.check_numeric(c)?;
        let mut out = io(OpenOptions::new().write(true).create_new(true).open(output))?;
        let mut guard = Output {
            path: output,
            keep: false,
        };
        let mut start = 0;
        while start < n {
            check(c)?;
            let count = (n - start).min(tail::CHUNK_PIXELS as u64) as usize;
            // Original final mapping saved=R, accum=G, source=B. The shader
            // performs the original preview conversion and then brightness.
            for (ch, slot) in [3, 4, 0].into_iter().enumerate() {
                let values = channels[ch]
                    .as_ref()
                    .ok_or("GPU_FINISH_STREAM_STATE")?
                    .read(start, count)?;
                self.runner
                    .upload_stream_plane(slot, &values, c, &mut self.metrics)?;
            }
            let mut v = params(count as u32, 1, 24);
            v[4] = count as u32;
            v[7] = 0;
            self.runner.dispatch(v, c, &mut self.metrics)?;
            self.check_numeric(c)?;
            let bytes = self
                .runner
                .read_probe(count as u64 * 12, c, &mut self.metrics)?;
            checked_numbers(&bytes)?;
            io(out.write_all(&bytes))?;
            start += count as u64;
        }
        io(out.sync_all())?;
        drop(out);
        check(c)?;
        if self.spatial.pass_count != p.pass_count()
            || self.metrics.sample_count + self.spatial.sample_count != plan.sample_count()
        {
            return Err("GPU_FINISH_STREAM_STATE".into());
        }
        self.complete = true;
        let result = self.report()?;
        guard.keep = true;
        // Workspace/planes and every source/output handle are dropped before
        // returning. Callers must also drop this owner before native stage seal.
        Ok(result)
    }
    fn check_numeric(&mut self, c: &mut impl FnMut() -> bool) -> R<()> {
        if self.runner.read_error_flag(c, &mut self.metrics)? != 0 {
            Err("GPU_TAIL_NUMERIC_DOMAIN".into())
        } else {
            Ok(())
        }
    }
    fn import_glare(
        &mut self,
        path: &Path,
        target: &Plane,
        n: u64,
        c: &mut impl FnMut() -> bool,
    ) -> R<()> {
        let mut file = io(File::open(path))?;
        let mut start = 0;
        while start < n {
            check(c)?;
            let count = (n - start).min(tail::CHUNK_PIXELS as u64) as usize;
            let values = read64(&mut file, count)?;
            if values.iter().any(|v| *v < 0.) {
                return Err("INVALID_TAIL_GLARE".into());
            }
            self.runner.upload_aux(
                tail::CHUNK_BASE as u64 * 4,
                bytemuck::cast_slice(&values),
                c,
                &mut self.metrics,
            )?;
            let mut p = params(count as u32, 1, 20);
            p[4] = count as u32;
            p[7] = 0;
            self.runner.dispatch(p, c, &mut self.metrics)?;
            self.check_numeric(c)?;
            let values = checked_numbers(&self.runner.read_stream_plane(
                0,
                count as u64 * 4,
                c,
                &mut self.metrics,
            )?)?;
            target.write(start, &values)?;
            start += count as u64;
        }
        Ok(())
    }
    fn generate_glare(&mut self, target: &Plane, n: u64, c: &mut impl FnMut() -> bool) -> R<()> {
        let g = self
            .plan
            .tail
            .options
            .glare
            .as_ref()
            .ok_or("INVALID_TAIL_GLARE")?;
        let tables = glare::Tables::new(g.percent, g.roughness)?.packed();
        self.runner.upload_aux(
            glare::TABLE_BASE as u64 * 4,
            bytemuck::cast_slice(&tables),
            c,
            &mut self.metrics,
        )?;
        let (mut start, mut cursor) = (0u64, 0u64);
        while start < n {
            check(c)?;
            let requested = (n - start).min(glare::OUTPUT_CAPACITY as u64) as u32;
            let words = (requested.saturating_mul(4) + 128)
                .min(glare::WORD_CAPACITY)
                .max(256);
            if cursor + words as u64 > u32::MAX as u64 {
                return Err("GPU_TAIL_GLARE_LIMIT".into());
            }
            let mut p = params(requested, 1, 30);
            p[4] = ((cursor as u32 % 8) + words).div_ceil(8);
            p[5] = cursor as u32;
            p[7] = 0;
            p[8] = words;
            p[9] = requested;
            self.runner.dispatch(p, c, &mut self.metrics)?;
            p[2] = 31;
            p[4] = words;
            self.runner.dispatch(p, c, &mut self.metrics)?;
            p[2] = 32;
            p[4] = 1;
            self.runner.dispatch(p, c, &mut self.metrics)?;
            let state = numbers(&self.runner.read_probe(12, c, &mut self.metrics)?);
            let (produced, used) = glare_state(&state, requested, words)?;
            self.check_numeric(c)?;
            let values = checked_numbers(&self.runner.read_stream_plane(
                0,
                produced * 4,
                c,
                &mut self.metrics,
            )?)?;
            target.write(start, &values)?;
            start += produced;
            cursor += used;
            self.glare_chunks += 1;
        }
        self.glare_words = cursor;
        Ok(())
    }
    pub(crate) fn report(&self) -> R<serde_json::Value> {
        if self.failed {
            return Err("GPU_FINISH_STREAM_FAILED".into());
        }
        if !self.complete {
            return Err("GPU_FINISH_STREAM_INCOMPLETE".into());
        }
        let mut value =
            serde_json::to_value(&self.metrics).map_err(|_| "GPU_FINISH_STREAM_REPORT")?;
        let o = value.as_object_mut().ok_or("GPU_FINISH_STREAM_REPORT")?;
        let sample_count = self.metrics.sample_count + self.spatial.sample_count;
        let max_abs = self.metrics.sample_max_abs.max(self.spatial.sample_max_abs);
        let rms = ((self.metrics.sample_sum + self.spatial.sample_sum_squared)
            / sample_count.max(1) as f64)
            .sqrt();
        for (key, value) in [
            ("gpuUsed", true.into()),
            ("fullImage", true.into()),
            ("fullResolution", true.into()),
            ("width", self.plan.width.into()),
            ("height", self.plan.height.into()),
            ("layout", LAYOUT.into()),
            (
                "backend",
                "Vulkan f32 original streamed scanner finish".into(),
            ),
            ("passCount", self.spatial.pass_count.into()),
            ("sampleCount", sample_count.into()),
            ("sampleMaxAbs", max_abs.into()),
            ("sampleRms", rms.into()),
            ("prefixSampleCount", self.prefix_samples.into()),
            (
                "glareGenerationGpu",
                (self.plan.tail.options.glare.is_some() && !self.plan.external_glare).into(),
            ),
            ("glareRandomWords", self.glare_words.into()),
            ("glareChunks", self.glare_chunks.into()),
            (
                "elapsedMs",
                (self.started.elapsed().as_secs_f64() * 1000.).into(),
            ),
            ("contextCount", 1.into()),
            ("spatialBatchSamples", self.capacity.into()),
            ("nativeStageSealed", false.into()),
            (
                "glarePredicateScope",
                if self.plan.external_glare {
                    "external_original_field"
                } else if self.plan.tail.options.glare.is_some() {
                    "chacha12_exact_fast_guarded_q48"
                } else {
                    "disabled"
                }
                .into(),
            ),
            (
                "glareAmbiguityBound",
                if self.glare_chunks > 0 {
                    2f64.powi(-32)
                } else {
                    0.
                }
                .into(),
            ),
            (
                "memory",
                serde_json::to_value(self.plan.memory()?)
                    .map_err(|_| "GPU_FINISH_STREAM_REPORT")?,
            ),
            (
                "streamingSpatial",
                serde_json::to_value(&self.spatial).map_err(|_| "GPU_FINISH_STREAM_REPORT")?,
            ),
        ] {
            o.insert(key.into(), value);
        }
        Ok(value)
    }
}

/// Preserve original fixed-family selection and exact shader assembly. The
/// full tail source is routed only to its own ops, never used as a substitute
/// for fixed FIR/IIR families. All pipelines share the same original Runner.
pub(super) fn shader_families(plan: &StreamFinishPlan) -> R<Vec<(Vec<u32>, String)>> {
    plan.validate()?;
    let mut semantic = plan.tail.spatial_plan()?;
    for (channel, sigma) in semantic.channels.iter_mut().zip(plan.sigmas()) {
        channel.lens_sigma = sigma;
    }
    let mut families = spatial::families::shaders_for_optics(&semantic)?;
    let fused = plan.prefix_table_scalars.is_some();
    let point = if fused {
        fused::pointwise_shader(&plan.tail)?
    } else {
        fused::standalone_pointwise_shader(&plan.tail)?
    };
    let (source, ops) = if let Some(point) = point {
        (
            point,
            if fused {
                vec![23, 24, 25, 40]
            } else {
                vec![21, 23, 24, 25]
            },
        )
    } else {
        let original = tail::shader_checked(spatial::SHADER)?;
        let source = if fused {
            fused::fused_shader(&original)?
        } else {
            original
        };
        (
            fused::full_private_shader(&source)?,
            if fused {
                vec![20, 21, 23, 24, 25, 30, 31, 32, 40]
            } else {
                vec![20, 21, 23, 24, 25, 30, 31, 32]
            },
        )
    };
    families.push((ops, source));
    Ok(families)
}
fn prefix_identity(plan: &StreamFinishPlan, prefix: Option<&FinishPrefix>) -> R<Option<[u8; 32]>> {
    match (plan.prefix_table_scalars, prefix) {
        (None, None) => Ok(None),
        (Some(count), Some(prefix)) => {
            if prefix.stream_identity()
                != (
                    plan.width,
                    plan.height,
                    count,
                    plan.width as u64 * plan.height as u64,
                )
                || prefix.storage().len() as u64 != 64 + count
            {
                return Err("GPU_FINISH_STREAM_IDENTITY".into());
            }
            Ok(Some(
                Sha256::digest(bytemuck::cast_slice(prefix.storage())).into(),
            ))
        }
        _ => Err("GPU_FINISH_STREAM_IDENTITY".into()),
    }
}
fn validate_prefix_binding(
    plan: &StreamFinishPlan,
    prefix: Option<&FinishPrefix>,
    expected: Option<[u8; 32]>,
) -> R<()> {
    if prefix_identity(plan, prefix)? == expected {
        Ok(())
    } else {
        Err("GPU_FINISH_STREAM_IDENTITY".into())
    }
}
fn validate_dispatch(inputs: &[(u32, &[f32])], p: &[u32; 40], output: u32) -> R<u64> {
    let count = p[0] as u64 * p[1] as u64;
    let (expected, target): (&[u32], u32) = match p[2] {
        0 | 2 | 4 if p[6] == 0 && p[7] == 1 => (&[0], 1),
        23 if p[6] == 0 && p[7] == 0 => (&[0], 0),
        23 if p[6] == 1 && p[7] == 0 => (&[0, 2], 0),
        _ => return Err("GPU_STREAM_DISPATCH_CONTRACT".into()),
    };
    if count == 0
        || count > stream::BATCH_SAMPLES as u64
        || output != target
        || p[3] != 0
        || p[4] != if p[2] == 2 { p[1] } else { count as u32 }
        || inputs.len() != expected.len()
        || inputs
            .iter()
            .zip(expected)
            .any(|((slot, data), wanted)| slot != wanted || data.len() as u64 != count)
    {
        return Err("GPU_STREAM_DISPATCH_CONTRACT".into());
    }
    Ok(count)
}
impl Compute for StreamingFinish {
    fn execute(
        &mut self,
        inputs: &[(u32, &[f32])],
        p: [u32; 40],
        output: u32,
        c: &mut impl FnMut() -> bool,
    ) -> R<Vec<f32>> {
        self.ready()?;
        let result = (|| {
            check(c)?;
            let count = validate_dispatch(inputs, &p, output)?;
            let before_upload = self.metrics.upload_bytes;
            let before_upload_ms = self.metrics.upload_ms;
            let before_readback = self.metrics.readback_bytes;
            for &(slot, values) in inputs {
                self.runner
                    .upload_stream_plane(slot, values, c, &mut self.metrics)?;
            }
            let start = Instant::now();
            self.runner.dispatch(p, c, &mut self.metrics)?;
            self.check_numeric(c)?;
            let result = checked_numbers(&self.runner.read_stream_plane(
                output,
                count * 4,
                c,
                &mut self.metrics,
            )?)?;
            self.spatial.upload_bytes += self.metrics.upload_bytes - before_upload;
            self.spatial.upload_ms += self.metrics.upload_ms - before_upload_ms;
            self.spatial.readback_bytes += self.metrics.readback_bytes - before_readback;
            self.spatial.dispatches += 1;
            self.spatial.compute_readback_ms += start.elapsed().as_secs_f64() * 1000.;
            Ok(result)
        })();
        self.terminal(result)
    }
    fn metrics(&mut self) -> &mut StreamMetrics {
        &mut self.spatial
    }
}
fn io<T>(r: std::io::Result<T>) -> R<T> {
    r.map_err(|_| "GPU_FINISH_STREAM_IO".into())
}
fn numbers(b: &[u8]) -> Vec<f32> {
    b.chunks_exact(4)
        .map(|v| f32::from_le_bytes(v.try_into().unwrap()))
        .collect()
}
fn checked_numbers(b: &[u8]) -> R<Vec<f32>> {
    let values = numbers(b);
    if b.len() % 4 != 0 || values.iter().any(|v| !v.is_finite()) {
        Err("NONFINITE_TAIL_OUTPUT".into())
    } else {
        Ok(values)
    }
}
fn read64(f: &mut File, n: usize) -> R<Vec<f32>> {
    let mut bytes = vec![0; n * 8];
    io(f.read_exact(&mut bytes))?;
    bytes
        .chunks_exact(8)
        .map(|b| {
            let x = f64::from_le_bytes(b.try_into().unwrap());
            if !x.is_finite() || !(x as f32).is_finite() {
                Err("NONFINITE_TAIL_INPUT".into())
            } else {
                Ok(x as f32)
            }
        })
        .collect()
}
fn point_guard(expected: [f32; 3], actual: &[f32], m: &mut RunMetrics) -> R<()> {
    for (r, &a) in expected.into_iter().zip(actual) {
        let error = (r as f64 - a as f64).abs();
        if !r.is_finite()
            || !a.is_finite()
            || error > tail::POINT_TOLERANCE * (r as f64).abs().max(1.)
        {
            return Err("GPU_TAIL_NUMERIC_CHECK".into());
        }
        m.sample_count += 1;
        m.sample_max_abs = m.sample_max_abs.max(error);
        m.sample_sum += error * error;
    }
    Ok(())
}
fn glare_state(state: &[f32], requested: u32, words: u32) -> R<(u64, u64)> {
    if state.len() != 3
        || state
            .iter()
            .any(|x| !x.is_finite() || *x < 0. || x.fract() != 0.)
    {
        return Err("GPU_TAIL_GLARE_STATE".into());
    }
    match state[2] as u32 {
        0 => {}
        2 => return Err("GPU_TAIL_GLARE_AMBIGUOUS".into()),
        3 => return Err("GPU_TAIL_GLARE_ITERATIONS".into()),
        4 => return Err("NONFINITE_TAIL_GLARE".into()),
        _ => return Err("GPU_TAIL_GLARE_STATE".into()),
    }
    let produced = state[0] as u64;
    let used = state[1] as u64;
    if produced == 0 || produced > requested as u64 || used < produced || used > words as u64 {
        return Err("GPU_TAIL_GLARE_STATE".into());
    }
    Ok((produced, used))
}
fn validate_paths(
    source: Option<&Path>,
    glare: Option<&Path>,
    output: &Path,
    scratch: &Path,
    n: u64,
) -> R<()> {
    if output.starts_with(scratch) || scratch.starts_with(output) {
        return Err("INVALID_TAIL_FILE".into());
    }
    for (path, bytes) in source
        .map(|p| (p, n * 24))
        .into_iter()
        .chain(glare.map(|p| (p, n * 8)))
    {
        let meta = io(fs::symlink_metadata(path))?;
        if !meta.is_file()
            || meta.file_type().is_symlink()
            || meta.len() != bytes
            || io(path.canonicalize())? != path
            || path == output
            || path.starts_with(scratch)
        {
            return Err("INVALID_TAIL_FILE".into());
        }
    }
    for path in [output, scratch] {
        let parent = path.parent().ok_or("INVALID_TAIL_FILE")?;
        if io(parent.canonicalize())? != parent || path.file_name().is_none() {
            return Err("INVALID_TAIL_FILE".into());
        }
        match fs::symlink_metadata(path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            _ => return Err("INVALID_TAIL_FILE".into()),
        }
    }
    Ok(())
}
struct Output<'a> {
    path: &'a Path,
    keep: bool,
}
impl Drop for Output<'_> {
    fn drop(&mut self) {
        if !self.keep {
            let _ = fs::remove_file(self.path);
        }
    }
}

#[cfg(test)]
mod contract_tests {
    use super::*;
    #[test]
    fn prefix_identity_rejects_consumption_geometry_and_same_sized_different_tables() {
        use super::super::tests::{descriptor, options, root};
        use crate::research_finish as finish;
        use spektrafilm_core::pipeline::{GpuFinishDescriptor, GpuFinishInput};
        use spektrafilm_math::pchip3d::prepare_pchip_3d;
        let root = root();
        let sources: [std::path::PathBuf; 3] = std::array::from_fn(|ch| {
            let path = root.join(format!("density-{ch}"));
            fs::write(
                &path,
                (0..15)
                    .flat_map(|i| (i as f64 / 16.).to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
            path
        });
        let load = |token, scale: f64| {
            let mut data = Vec::new();
            for x in 0..4 {
                for y in 0..4 {
                    for z in 0..4 {
                        data.extend([
                            scale * x as f64 / 3.,
                            scale * y as f64 / 3.,
                            scale * z as f64 / 3.,
                        ]);
                    }
                }
            }
            let lut = prepare_pchip_3d(data, 4);
            let d = GpuFinishDescriptor {
                input: GpuFinishInput::FilmDensity,
                printing: None,
                scanner: &lut,
                scanner_min: [0.; 3],
                scanner_max: [1.; 3],
            };
            let dir = root.join(format!("gpu-stage-{token}"));
            let prepared = finish::prepare_stage(
                [&sources[0], &sources[1], &sources[2]],
                &dir,
                token,
                5,
                3,
                &d,
                || false,
            )
            .unwrap();
            (
                prepared.table_scalars,
                FinishPrefix::load(&dir, token, &mut || false).unwrap(),
            )
        };
        let (count, mut first) = load(501, 1.);
        let plan = StreamFinishPlan::new(
            &descriptor(),
            5,
            3,
            options(0., 0., false),
            Some(count),
            false,
        )
        .unwrap();
        let mut called = false;
        assert_eq!(
            StreamingFinish::new_with_prefix(&plan, &first, &mut || {
                called = true;
                false
            })
            .err()
            .unwrap(),
            "GPU_FINISH_STREAM_PENDING"
        );
        assert!(!called);
        let digest = prefix_identity(&plan, Some(&first)).unwrap();
        validate_prefix_binding(&plan, Some(&first), digest).unwrap();
        let mut wrong = plan.clone();
        wrong.width = 3;
        wrong.height = 5;
        assert!(prefix_identity(&wrong, Some(&first)).is_err());
        wrong = plan.clone();
        wrong.prefix_table_scalars = Some(count + 1);
        assert!(prefix_identity(&wrong, Some(&first)).is_err());
        let (_, different) = load(502, 0.5);
        assert_eq!(first.storage().len(), different.storage().len());
        assert_eq!(
            validate_prefix_binding(&plan, Some(&different), digest).unwrap_err(),
            "GPU_FINISH_STREAM_IDENTITY"
        );
        first.read_chunk(1).unwrap();
        assert_eq!(
            prefix_identity(&plan, Some(&first)).unwrap_err(),
            "GPU_FINISH_STREAM_IDENTITY"
        );
        drop(first);
        drop(different);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn closed_dispatch_contract_rejects_foreign_offsets_routes_and_missing_inputs() {
        let values = [0.5; 8];
        let mut p = params(8, 1, 23);
        p[4] = 8;
        assert!(validate_dispatch(&[(0, &values)], &p, 0).is_ok());
        for op in [1, 3, 6, 20, 21, 24, 25, 30, 31, 32, 40, 56] {
            let mut bad = p;
            bad[2] = op;
            assert!(validate_dispatch(&[(0, &values)], &bad, 0).is_err());
        }
        p[6] = 1;
        assert!(validate_dispatch(&[(0, &values)], &p, 0).is_err());
        assert!(validate_dispatch(&[(0, &values), (2, &values)], &p, 0).is_ok());
        p[7] = 1;
        assert!(validate_dispatch(&[(0, &values), (2, &values)], &p, 0).is_err());
    }
    #[test]
    fn glare_windows_retain_exact_consumption_and_reject_bad_states() {
        assert_eq!(
            glare_state(&[16384., 16824., 0.], 16384, 65536).unwrap(),
            (16384, 16824)
        );
        for bad in [
            [0., 1., 0.],
            [2., 1., 0.],
            [2., 65537., 0.],
            [2.5, 3., 0.],
            [f32::NAN, 1., 0.],
            [1., 1., 2.],
            [1., 1., 3.],
            [1., 1., 4.],
        ] {
            assert!(glare_state(&bad, 16384, 65536).is_err());
        }
    }
}

#[cfg(test)]
mod actual_failure_tests {
    use super::super::tests::{descriptor, options, root, sources};
    use super::*;
    #[test]
    #[ignore = "One test per process: actual activated-session post-submit cancellation retires epoch and forbids seal"]
    fn actual_post_submit_cancel_forbids_reuse_and_seal() {
        use crate::{gpu_session as session, gpu_session_contract::StageTicket};
        let plan =
            StreamFinishPlan::new(&descriptor(), 17, 13, options(0.7, 3.2, false), None, false)
                .unwrap();
        let memory = plan.memory().unwrap();
        let epoch = 9202;
        let ticket = StageTicket {
            epoch,
            generation: 1,
            sequence: 1,
        };
        session::activate(
            epoch,
            memory.combined_peak_bytes,
            memory.host_bytes + memory.helper_allowance_bytes,
            8,
        )
        .unwrap();
        session::begin_frame(epoch, 1).unwrap();
        session::begin_stage(ticket, memory.driver_allowance_bytes).unwrap();
        let mut gpu = StreamingFinish::new_probe(&plan, None, &mut || false).unwrap();
        let before = gpu.metrics.dispatches;
        let mut p = params(8192, 1, 4);
        p[4] = 8192;
        p[6] = 0;
        p[7] = 1;
        let mut checks = 0;
        let result = gpu.runner.dispatch(
            p,
            &mut || {
                checks += 1;
                checks == 2
            },
            &mut gpu.metrics,
        );
        assert_eq!(result.as_ref().unwrap_err(), "CANCELLED");
        assert_eq!(
            gpu.metrics.dispatches,
            before + 1,
            "must cancel after actual queue submit"
        );
        assert_eq!(gpu.terminal(result).unwrap_err(), "CANCELLED");
        assert_eq!(gpu.report().unwrap_err(), "GPU_FINISH_STREAM_FAILED");
        assert_eq!(
            gpu.run_files(
                Path::new("unused"),
                None,
                Path::new("unused-output"),
                Path::new("unused-scratch"),
                &mut || false
            )
            .unwrap_err(),
            "GPU_FINISH_STREAM_FAILED"
        );
        drop(gpu);
        assert!(session::complete_stage(ticket).is_err());
        assert!(session::finish_frame(epoch, 1).is_err());
        assert!(session::is_cancelled(epoch));
    }
    #[test]
    #[ignore = "Actual Vulkan unsampled input guard and private candidate cleanup, no image substitution"]
    fn actual_unsampled_domain_failure_removes_all_private_spools() {
        let root = root();
        let (source, _) = sources(&root, 17, 13);
        let mut raw = fs::read(&source).unwrap();
        raw[123 * 24..123 * 24 + 8].copy_from_slice(&50f64.to_le_bytes());
        fs::write(&source, raw).unwrap();
        let plan =
            StreamFinishPlan::new(&descriptor(), 17, 13, options(0.7, 3.2, false), None, false)
                .unwrap();
        let mut gpu = StreamingFinish::new_probe(&plan, None, &mut || false).unwrap();
        let output = root.join("must-not-exist");
        let scratch = root.join("scratch");
        assert_eq!(
            gpu.run_files(&source, None, &output, &scratch, &mut || false)
                .unwrap_err(),
            "GPU_TAIL_NUMERIC_DOMAIN"
        );
        assert!(!output.exists());
        assert!(!scratch.exists());
        assert_eq!(gpu.report().unwrap_err(), "GPU_FINISH_STREAM_FAILED");
        drop(gpu);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[ignore = "Actual Vulkan final brightness failure deletes partially-created output and all scratch"]
    fn actual_final_brightness_failure_removes_candidate() {
        let root = root();
        let (source, _) = sources(&root, 17, 13);
        let mut opts = options(0.7, 3.2, false);
        opts.brightness_ev = 80.;
        let plan = StreamFinishPlan::new(&descriptor(), 17, 13, opts, None, false).unwrap();
        let mut gpu = StreamingFinish::new_probe(&plan, None, &mut || false).unwrap();
        let output = root.join("must-not-exist");
        let scratch = root.join("scratch");
        assert_eq!(
            gpu.run_files(&source, None, &output, &scratch, &mut || false)
                .unwrap_err(),
            "GPU_TAIL_NUMERIC_DOMAIN"
        );
        assert!(!output.exists());
        assert!(!scratch.exists());
        drop(gpu);
        fs::remove_dir_all(root).unwrap();
    }
}
