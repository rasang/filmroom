//! Bounded coordinate-only sampling to private f32 little-endian spools.
//! Independent module; original research_grain files remain unchanged.
//! Caller owns separate streamed Runner creation, mandatory admission, exact
//! profile/factorial table upload, full-line spatial passes and final publication.
use super::streaming_contract::Chunk;
use crate::research_grain::{GrainPlan, Layer, guarded_graph as graph};
use crate::research_spatial::{
    check,
    gpu::{RunMetrics, Runner},
};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
};
type R<T> = Result<T, String>;

/// Shader provenance and mandatory admission are carried by an unforgeable
/// owner, never by a caller-provided generic resident Runner.
pub(crate) struct StreamingSampler {
    runner: Runner,
    plan: super::StreamGrainPlan,
    tables: [Vec<graph::Table>; 3],
    metrics: RunMetrics,
    pub(crate) census: serde_json::Value,
    failed: bool,
    numeric_flag_reads: u64,
    spatial_metrics: crate::research_streaming_spatial::StreamMetrics,
    started: std::time::Instant,
}
impl StreamingSampler {
    pub(crate) fn new(
        _plan: &super::StreamGrainPlan,
        _cancel: &mut impl FnMut() -> bool,
    ) -> R<Self> {
        // Independent source gate. No JNI entrypoint or partial-GPU route is
        // admitted by adding the candidate module to a development build.
        Err("GPU_GRAIN_STREAM_PENDING".into())
    }
    #[cfg(test)]
    pub(crate) fn new_probe(
        plan: &super::StreamGrainPlan,
        cancel: &mut impl FnMut() -> bool,
    ) -> R<Self> {
        Self::construct(plan, cancel, true).map_err(|error| {
            crate::gpu_session::retire_current();
            error
        })
    }
    fn construct(
        plan: &super::StreamGrainPlan,
        cancel: &mut impl FnMut() -> bool,
        allow_probe: bool,
    ) -> R<Self> {
        let started = std::time::Instant::now();
        plan.numerical_preflight(cancel)?;
        plan.memory()?;
        let p = plan.parameters();
        let mut allocation = p.clone();
        allocation.width = super::streaming_contract::CAPACITY;
        allocation.height =
            crate::research_streaming_spatial::BATCH_SAMPLES / super::streaming_contract::CAPACITY;
        let families = super::streaming_shader::shaders_for_plan(p)?;
        let scratch = crate::research_spatial::gpu::GrainScratchConfig::new(
            super::streaming_contract::CAPACITY,
        )?;
        let (runner, mut metrics) = Runner::new_with_grain_shader_families(
            &allocation.spatial_plan()?,
            allow_probe,
            &families,
            scratch,
        )?;
        let admission_started = std::time::Instant::now();
        let census = crate::research_grain::guarded_gpu::admit_shared_runner(
            &runner,
            p,
            cancel,
            &mut metrics,
        )?;
        metrics.setup_ms += admission_started.elapsed().as_secs_f64() * 1000.;
        let (bytes, tables) = graph::pack_tables(p);
        if !bytes.is_empty() {
            runner.upload_aux(graph::TABLE_BASE as u64 * 4, &bytes, cancel, &mut metrics)?;
        }
        let spatial_metrics = crate::research_streaming_spatial::StreamMetrics {
            adapter: metrics.adapter.clone(),
            vendor: metrics.vendor,
            layout: "fullLineCoordinateGrainV1".into(),
            full_resolution: true,
            device_bytes: metrics.device_bytes,
            ..Default::default()
        };
        let numeric_flag_reads = census["numericFlagReads"].as_u64().ok_or("INVALID_GRAIN_RESULT")?;
        Ok(Self {
            runner,
            plan: plan.clone(),
            tables,
            metrics,
            census,
            failed: false,
            numeric_flag_reads,
            spatial_metrics,
            started,
        })
    }
    pub(crate) fn poison(&mut self) {
        self.failed = true;
        self.runner.invalidate_streaming();
    }
    fn finish<T>(&mut self, result: R<T>) -> R<T> {
        if result.is_err() {
            self.poison();
        }
        result
    }
    pub(crate) fn sample_layer(
        &mut self,
        channel: usize,
        layer: usize,
        source_f64: &Path,
        output_f32: &Path,
        cancel: &mut impl FnMut() -> bool,
    ) -> R<()> {
        if self.failed {
            return Err("GPU_GRAIN_STREAM_FAILED".into());
        }
        let result = self.sample_layer_inner(channel, layer, source_f64, output_f32, cancel);
        self.finish(result)
    }
    pub(crate) fn original(
        &mut self,
        source_f64: &Path,
        output_f32: &Path,
        cancel: &mut impl FnMut() -> bool,
    ) -> R<()> {
        if self.failed {
            return Err("GPU_GRAIN_STREAM_FAILED".into());
        }
        let result = self.original_inner(source_f64, output_f32, cancel);
        self.finish(result)
    }
    pub(crate) fn microstructure(
        &mut self,
        channel: usize,
        output_f32: &Path,
        cancel: &mut impl FnMut() -> bool,
    ) -> R<()> {
        if self.failed {
            return Err("GPU_GRAIN_STREAM_FAILED".into());
        }
        let result = self.microstructure_inner(channel, output_f32, cancel);
        self.finish(result)
    }
    fn sample_layer_inner(
        &mut self,
        channel: usize,
        layer: usize,
        source_f64: &Path,
        output_f32: &Path,
        cancel: &mut impl FnMut() -> bool,
    ) -> R<()> {
        let p = self.plan.parameters();
        let l = p
            .layers
            .get(channel)
            .and_then(|layers| layers.get(layer))
            .ok_or("INVALID_GRAIN_PLAN")?;
        sample_layer_to_f32(
            &self.runner,
            p,
            l,
            self.tables[channel][layer],
            source_f64,
            output_f32,
            cancel,
            &mut self.metrics,
            &mut self.numeric_flag_reads,
        )
    }
    /// Original op50 binary64-to-f32 conversion, including its exact range and
    /// underflow policy. A plain host cast would not retain that contract.
    fn original_inner(
        &mut self,
        source_f64: &Path,
        output_f32: &Path,
        cancel: &mut impl FnMut() -> bool,
    ) -> R<()> {
        let p = self.plan.parameters();
        let n = u64::from(p.width) * u64::from(p.height);
        let mut input = input_file(source_f64, n)?;
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(output_f32)
            .map_err(io_error)?;
        let mut pending = Pending {
            path: output_f32,
            keep: false,
        };
        let mut start = 0;
        while start < n {
            check(cancel)?;
            let count = (n - start).min(u64::from(super::streaming_contract::CAPACITY)) as u32;
            let chunk = Chunk::new(p.width, p.height, start, count).map_err(str::to_owned)?;
            let bytes = read_f64_chunk(&mut input, chunk)?;
            let mut batch = self.runner.batch();
            batch.submit_chunk(
                Some((u64::from(graph::CHUNK_BASE) * 4, &bytes)),
                chunk.apply([0; 40], 50).map_err(str::to_owned)?,
                cancel,
                &mut self.metrics,
            )?;
            let mut copy = [0; 40];
            copy[0] = count;
            copy[1] = 1;
            copy[2] = 4;
            copy[4] = count;
            copy[6] = 3;
            copy[7] = 0;
            batch.submit_chunk(None, copy, cancel, &mut self.metrics)?;
            batch.finish(cancel, &mut self.metrics)?;
            write_checked_chunk(&self.runner, chunk, &mut output, cancel, &mut self.metrics, &mut self.numeric_flag_reads)?;
            start += u64::from(count);
        }
        check(cancel)?;
        output.sync_all().map_err(io_error)?;
        pending.keep = true;
        Ok(())
    }
    fn microstructure_inner(
        &mut self,
        channel: usize,
        output_f32: &Path,
        cancel: &mut impl FnMut() -> bool,
    ) -> R<()> {
        microstructure_to_f32(
            &self.runner,
            self.plan.parameters(),
            channel,
            output_f32,
            cancel,
            &mut self.metrics,
            &mut self.numeric_flag_reads,
        )
    }
    pub(crate) fn plan(&self) -> &super::StreamGrainPlan {
        &self.plan
    }
    pub(crate) fn report(&self) -> R<serde_json::Value> {
        if self.failed {
            return Err("GPU_GRAIN_STREAM_FAILED".into());
        }
        let mut value = serde_json::to_value(&self.metrics).map_err(|_| "INVALID_GRAIN_RESULT")?;
        let object = value.as_object_mut().ok_or("INVALID_GRAIN_RESULT")?;
        object.insert(
            "elapsedMs".into(),
            serde_json::json!(self.started.elapsed().as_secs_f64() * 1000.),
        );
        object.insert(
            "passCount".into(),
            serde_json::json!(self.spatial_metrics.pass_count),
        );
        object.insert(
            "sampleCount".into(),
            serde_json::json!(self.spatial_metrics.sample_count),
        );
        object.insert(
            "sampleMaxAbs".into(),
            serde_json::json!(self.spatial_metrics.sample_max_abs),
        );
        object.insert(
            "sampleRms".into(),
            serde_json::json!(
                (self.spatial_metrics.sample_sum_squared
                    / self.spatial_metrics.sample_count.max(1) as f64)
                    .sqrt()
            ),
        );
        object.insert("fixedAdmission".into(), self.census.clone());
        object.insert("numericFlagReads".into(), serde_json::json!(self.numeric_flag_reads));
        object.insert(
            "streamingSpatial".into(),
            serde_json::to_value(&self.spatial_metrics).map_err(|_| "INVALID_GRAIN_RESULT")?,
        );
        object.insert(
            "layout".into(),
            serde_json::json!("fullLineCoordinateGrainV1"),
        );
        object.insert(
            "memory".into(),
            serde_json::to_value(self.plan.memory()?).map_err(|_| "INVALID_GRAIN_RESULT")?,
        );
        Ok(value)
    }
    pub(crate) fn metrics(&self) -> &RunMetrics {
        &self.metrics
    }
}

#[cfg(test)]
mod cancellation_tests {
    use super::*;
    #[test]
    #[ignore = "one test per process: actual activated-session post-submit cancellation retires epoch"]
    fn actual_sampler_post_submit_cancel_forbids_reuse_and_seal() {
        use crate::{gpu_session as session, gpu_session_contract::StageTicket};
        let mut p = crate::research_streaming_grain::streaming_shader::tests::plan();
        p.width = 1;
        p.height = 1;
        let plan = crate::research_streaming_grain::StreamGrainPlan::from_plan(p).unwrap();
        let memory = plan.memory().unwrap();
        let epoch = 9002;
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
        let mut sampler = StreamingSampler::new_probe(&plan, &mut || false).unwrap();
        let before = sampler.metrics.dispatches;
        let mut p = [0; 40];
        p[0] = 8192;
        p[1] = 1;
        p[2] = 4;
        p[4] = 8192;
        p[6] = 0;
        p[7] = 1;
        let mut checks = 0;
        // Exercise the exact original Runner failure returned to the same typed
        // terminalizer used by production sampling and Compute::execute.
        let result = sampler.runner.dispatch(
            p,
            &mut || {
                checks += 1;
                checks == 2
            },
            &mut sampler.metrics,
        );
        assert_eq!(result.as_ref().unwrap_err(), "CANCELLED");
        assert_eq!(
            sampler.metrics.dispatches,
            before + 1,
            "cancellation must follow real submit"
        );
        assert_eq!(sampler.finish(result).unwrap_err(), "CANCELLED");
        assert_eq!(sampler.report().unwrap_err(), "GPU_GRAIN_STREAM_FAILED");
        assert_eq!(
            sampler
                .original(
                    Path::new("unused-input"),
                    Path::new("unused-output"),
                    &mut || false
                )
                .unwrap_err(),
            "GPU_GRAIN_STREAM_FAILED"
        );
        drop(sampler);
        assert!(session::complete_stage(ticket).is_err());
        assert!(session::finish_frame(epoch, 1).is_err());
        assert!(session::is_cancelled(epoch));
    }
}

impl crate::research_streaming_spatial::Compute for StreamingSampler {
    fn execute(
        &mut self,
        inputs: &[(u32, &[f32])],
        p: [u32; 40],
        output: u32,
        cancel: &mut impl FnMut() -> bool,
    ) -> R<Vec<f32>> {
        if self.failed {
            return Err("GPU_GRAIN_STREAM_FAILED".into());
        }
        let result = (|| {
            check(cancel)?;
            let count = p[0] as u64 * p[1] as u64;
            let (expected, target): (&[u32], u32) = match p[2] {
                0 | 2 | 4 if p[6] == 0 && p[7] == 1 => (&[0], 1),
                56 | 57 if p[26] == 0 => (&[4, 0], 4),
                58 => (&[4], 0),
                59 => (&[0, 3], 0),
                60 => (&[0], 0),
                _ => return Err("GPU_STREAM_DISPATCH_CONTRACT".into()),
            };
            if count == 0
                || count > crate::research_streaming_spatial::BATCH_SAMPLES as u64
                || p[3] != 0
                || output != target
                || p[4] != if p[2] == 2 { p[1] } else { count as u32 }
                || inputs.len() != expected.len()
                || inputs
                    .iter()
                    .zip(expected)
                    .any(|((slot, data), expected)| slot != expected || data.len() as u64 != count)
            {
                return Err("GPU_STREAM_DISPATCH_CONTRACT".into());
            }
            let before_upload_time = self.metrics.upload_ms;
            let before_upload = self.metrics.upload_bytes;
            let before_readback = self.metrics.readback_bytes;
            for &(slot, values) in inputs {
                self.runner
                    .upload_stream_plane(slot, values, cancel, &mut self.metrics)?;
            }
            let started = std::time::Instant::now();
            self.runner.dispatch(p, cancel, &mut self.metrics)?;
            let numeric_flag = self.runner.read_error_flag(cancel, &mut self.metrics)?;
            self.numeric_flag_reads += 1;
            if numeric_flag != 0 {
                return Err("GPU_GRAIN_GUARDED_REJECTION".into());
            }
            let bytes =
                self.runner
                    .read_stream_plane(output, count * 4, cancel, &mut self.metrics)?;
            let values: Vec<f32> = bytes
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                .collect();
            if values.iter().any(|v| !v.is_finite()) {
                return Err("NONFINITE_GRAIN_OUTPUT".into());
            }
            self.spatial_metrics.upload_ms += self.metrics.upload_ms - before_upload_time;
            self.spatial_metrics.upload_bytes += self.metrics.upload_bytes - before_upload;
            self.spatial_metrics.readback_bytes += self.metrics.readback_bytes - before_readback;
            self.spatial_metrics.dispatches += 1;
            self.spatial_metrics.compute_readback_ms += started.elapsed().as_secs_f64() * 1000.;
            Ok(values)
        })();
        self.finish(result)
    }
    fn metrics(&mut self) -> &mut crate::research_streaming_spatial::StreamMetrics {
        &mut self.spatial_metrics
    }
}

struct Pending<'a> {
    path: &'a Path,
    keep: bool,
}
impl Drop for Pending<'_> {
    fn drop(&mut self) {
        if !self.keep {
            let _ = std::fs::remove_file(self.path);
        }
    }
}
fn io_error(_: std::io::Error) -> String {
    "GPU_GRAIN_STREAM_IO".into()
}
fn coordinate_only(p: &GrainPlan) -> R<()> {
    if p.rng_mode != graph::RngMode::CoordinateSplitMix64 {
        return Err("INVALID_GRAIN_RNG_MODE".into());
    }
    // Check geometry and byte-offset overflow without a resident spatial plan.
    Chunk::new(p.width, p.height, 0, 1).map_err(str::to_owned)?;
    Ok(())
}
fn input_file(path: &Path, pixels: u64) -> R<File> {
    let meta = std::fs::symlink_metadata(path).map_err(io_error)?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.len() != pixels * 8 {
        return Err("INVALID_GRAIN_FILE".into());
    }
    File::open(path).map_err(io_error)
}
fn read_f64_chunk(input: &mut File, chunk: Chunk) -> R<Vec<u8>> {
    input
        .seek(SeekFrom::Start(chunk.input_file_offset()))
        .map_err(io_error)?;
    let mut raw = vec![0; chunk.count() as usize * 8];
    input.read_exact(&mut raw).map_err(io_error)?;
    // Validate first, then encode in exactly the existing 24/24/16 lane layout.
    let decode = |b: &[u8]| f64::from_le_bytes(b.try_into().unwrap());
    if raw.chunks_exact(8).any(|b| !decode(b).is_finite()) {
        return Err("NONFINITE_GRAIN_INPUT".into());
    }
    Ok(graph::encode(raw.chunks_exact(8).map(decode)))
}
fn write_checked_chunk(
    r: &Runner,
    chunk: Chunk,
    output: &mut File,
    cancel: &mut impl FnMut() -> bool,
    metrics: &mut RunMetrics,
    numeric_flag_reads: &mut u64,
) -> R<()> {
    check(cancel)?;
    // Numeric flag is sticky for the complete admitted request. Never reset it.
    let numeric_flag = r.read_error_flag(cancel, metrics)?;
    *numeric_flag_reads += 1;
    if numeric_flag != 0 {
        return Err("GPU_GRAIN_GUARDED_REJECTION".into());
    }
    let bytes = r.read_source(0, u64::from(chunk.count()) * 4, cancel, metrics)?;
    if bytes.len() != chunk.count() as usize * 4
        || bytes
            .chunks_exact(4)
            .any(|b| !f32::from_le_bytes(b.try_into().unwrap()).is_finite())
    {
        return Err("NONFINITE_GRAIN_OUTPUT".into());
    }
    check(cancel)?;
    output.write_all(&bytes).map_err(io_error)
}
/// Writes exactly width*height*4 bytes, no header, in raster order.
/// Layer is sampled in full before the caller applies its full-line cloud blur.
/// Do not call this with resident shaders: base x,y would be ignored silently.
fn sample_layer_to_f32(
    r: &Runner,
    p: &GrainPlan,
    layer: &Layer,
    table: graph::Table,
    source_f64: &Path,
    output_f32: &Path,
    cancel: &mut impl FnMut() -> bool,
    metrics: &mut RunMetrics,
    numeric_flag_reads: &mut u64,
) -> R<()> {
    coordinate_only(p)?;
    check(cancel)?;
    if source_f64 == output_f32 {
        return Err("INVALID_GRAIN_FILE".into());
    }
    let n = u64::from(p.width) * u64::from(p.height);
    let mut input = input_file(source_f64, n)?;
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(output_f32)
        .map_err(io_error)?;
    let mut pending = Pending {
        path: output_f32,
        keep: false,
    };
    let parameters = graph::encode([
        layer.density_min,
        layer.density_max,
        layer.particles,
        layer.uniformity,
        p.micro_mu,
        p.micro_sigma_log,
    ]);
    r.upload_aux(
        u64::from(graph::PARAM_BASE) * 4,
        &parameters,
        cancel,
        metrics,
    )?;
    let mut base = [0u32; 40];
    base[8] = layer.seed as u32;
    base[9] = (layer.seed >> 32) as u32;
    base[10] = u32::from(p.positive);
    base[16] = table.axis;
    base[17] = table.values;
    base[18] = table.knots;
    base[28] = p.origin[0];
    base[29] = p.origin[1];
    let mut start = 0;
    while start < n {
        check(cancel)?;
        let count = (n - start).min(u64::from(super::streaming_contract::CAPACITY)) as u32;
        let chunk = Chunk::new(p.width, p.height, start, count).map_err(str::to_owned)?;
        let bytes = read_f64_chunk(&mut input, chunk)?;
        let mut batch = r.batch();
        for op in super::streaming_contract::SAMPLER_PHASES {
            let params = chunk.apply(base, op).map_err(str::to_owned)?;
            let upload = if op == 63 {
                Some((u64::from(graph::CHUNK_BASE) * 4, bytes.as_slice()))
            } else {
                None
            };
            batch.submit_chunk(upload, params, cancel, metrics)?;
        }
        batch.finish(cancel, metrics)?;
        write_checked_chunk(r, chunk, &mut output, cancel, metrics, numeric_flag_reads)?;
        start += u64::from(count);
    }
    check(cancel)?;
    output.sync_all().map_err(io_error)?;
    pending.keep = true;
    Ok(())
}

/// Original coordinate microstructure before the complete-image spread blur.
fn microstructure_to_f32(
    r: &Runner,
    p: &GrainPlan,
    channel: usize,
    output_f32: &Path,
    cancel: &mut impl FnMut() -> bool,
    metrics: &mut RunMetrics,
    numeric_flag_reads: &mut u64,
) -> R<()> {
    coordinate_only(p)?;
    check(cancel)?;
    if channel >= 3 || !p.micro_active {
        return Err("INVALID_GRAIN_PLAN".into());
    }
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(output_f32)
        .map_err(io_error)?;
    let mut pending = Pending {
        path: output_f32,
        keep: false,
    };
    // Only parameter indices4,5 are read by the unchanged microstructure body.
    let bytes = graph::encode([0., 0., 0., 0., p.micro_mu, p.micro_sigma_log]);
    r.upload_aux(u64::from(graph::PARAM_BASE) * 4, &bytes, cancel, metrics)?;
    let seed = p.micro_seed(channel);
    let mut base = [0u32; 40];
    base[8] = seed as u32;
    base[9] = (seed >> 32) as u32;
    base[28] = p.origin[0];
    base[29] = p.origin[1];
    let n = u64::from(p.width) * u64::from(p.height);
    let mut start = 0;
    while start < n {
        check(cancel)?;
        let count = (n - start).min(u64::from(super::streaming_contract::CAPACITY)) as u32;
        let chunk = Chunk::new(p.width, p.height, start, count).map_err(str::to_owned)?;
        r.dispatch(
            chunk.apply(base, 54).map_err(str::to_owned)?,
            cancel,
            metrics,
        )?;
        write_checked_chunk(r, chunk, &mut output, cancel, metrics, numeric_flag_reads)?;
        start += u64::from(count);
    }
    check(cancel)?;
    output.sync_all().map_err(io_error)?;
    pending.keep = true;
    Ok(())
}
