//! One immutable typed Runner, shared by original DIR/log and full-line passes.
use super::*;
use crate::research_dir::{CHUNK_BASE, CHUNK_PIXELS, TABLE_BASE, gpu as original, sample_indices};
use crate::research_log_dir::log_prefix;
use crate::research_spatial::{
    check,
    gpu::{RunMetrics, Runner},
};
use crate::research_streaming_spatial::{
    Compute, StreamMetrics,
    files::{self, Plane, Workspace},
};
use std::{
    fs::{File, OpenOptions},
    io::Write,
    path::Path,
    time::Instant,
};

pub(crate) struct StreamingDir {
    runner: Runner,
    plan: StreamDirPlan,
    tables: original::Tables,
    metrics: RunMetrics,
    spatial: StreamMetrics,
    prefix: log_prefix::Metrics,
    failed: bool,
    complete: bool,
    started: Instant,
}
impl StreamingDir {
    pub(crate) fn new(_plan: &StreamDirPlan, _c: &mut impl FnMut() -> bool) -> R<Self> {
        Err("GPU_DIR_STREAM_PENDING".into())
    }
    #[cfg(test)]
    pub(crate) fn new_probe(plan: &StreamDirPlan, c: &mut impl FnMut() -> bool) -> R<Self> {
        Self::construct(plan, c, true).map_err(|error| {
            crate::gpu_session::retire_current();
            error
        })
    }
    fn construct(plan: &StreamDirPlan, c: &mut impl FnMut() -> bool, allow_probe: bool) -> R<Self> {
        let started = Instant::now();
        plan.numerical_preflight(c)?;
        let memory = plan.memory()?;
        let mut allocation = plan.parameters().clone();
        allocation.width = 1024;
        allocation.height = stream::BATCH_SAMPLES / 1024;
        let (runner, mut metrics) = Runner::new_with_fixed_iir_families(
            &allocation.spatial_plan()?,
            allow_probe,
            &plan.families()?,
        )?;
        let (bytes, tables) = original::pack_tables(plan.parameters());
        runner.upload_aux(u64::from(TABLE_BASE) * 4, &bytes, c, &mut metrics)?;
        metrics.device_bytes = memory.device_bytes;
        metrics.sample_scope = "per_axis_and_chunk_f64_on_gpu_f32_input";
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
            tables,
            metrics,
            spatial,
            prefix: Default::default(),
            failed: false,
            complete: false,
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
    fn alive(&self) -> R<()> {
        if self.failed {
            Err("GPU_DIR_STREAM_FAILED".into())
        } else {
            Ok(())
        }
    }
    fn read_plane(&mut self, slot: u32, count: usize, c: &mut impl FnMut() -> bool) -> R<Vec<f32>> {
        if self.runner.read_error_flag(c, &mut self.metrics)? != 0 {
            return Err("GPU_DIR_NUMERIC_DOMAIN".into());
        }
        let bytes = self
            .runner
            .read_stream_plane(slot, count as u64 * 4, c, &mut self.metrics)?;
        let values: Vec<f32> = bytes
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
            .collect();
        if values.iter().any(|v| !v.is_finite()) {
            return Err("NONFINITE_DIR_OUTPUT".into());
        }
        Ok(values)
    }
    /// The immutable owner supplies scientific identity. Outputs remain private
    /// candidates; this result is not a stage seal or publication certificate.
    pub(crate) fn run_files(
        &mut self,
        sources: [&Path; 3],
        outputs: [&Path; 3],
        scratch: &Path,
        c: &mut impl FnMut() -> bool,
        progress: &mut impl FnMut(u64, u64),
    ) -> R<serde_json::Value> {
        self.run_capacity(
            sources,
            outputs,
            scratch,
            stream::BATCH_SAMPLES,
            c,
            progress,
        )
    }
    fn run_capacity(
        &mut self,
        sources: [&Path; 3],
        outputs: [&Path; 3],
        scratch: &Path,
        capacity: u32,
        c: &mut impl FnMut() -> bool,
        progress: &mut impl FnMut(u64, u64),
    ) -> R<serde_json::Value> {
        self.alive()?;
        if self.complete {
            return Err("GPU_DIR_STREAM_COMPLETE".into());
        }
        let result = self.run_inner(sources, outputs, scratch, capacity, c, progress);
        self.finish(result)?;
        self.report()
    }
    fn run_inner(
        &mut self,
        sources: [&Path; 3],
        outputs: [&Path; 3],
        scratch: &Path,
        capacity: u32,
        c: &mut impl FnMut() -> bool,
        progress: &mut impl FnMut(u64, u64),
    ) -> R<()> {
        let p = self.plan.parameters().clone();
        let correction = self.plan.log_correction();
        if capacity < p.width.max(p.height) || capacity > stream::BATCH_SAMPLES {
            return Err("GPU_STREAM_LINE_LIMIT".into());
        }
        let path_plan = stream::StreamPlan::from_channels(
            p.width,
            p.height,
            std::array::from_fn(|_| spatial::ChannelPlan {
                lens_sigma: 0.,
                scatter: None,
                bounce_sigmas: vec![],
                bounce_weights: vec![],
                strength: 0.,
                divisor: 1.,
            }),
        )?;
        files::validate_paths(&path_plan, sources, outputs, scratch)?;
        check(c)?;
        let n = u64::from(p.width) * u64::from(p.height);
        let mut ws = Workspace::new(scratch, n)?;
        let mut pending = Outputs {
            paths: outputs,
            created: 0,
            keep: false,
        };
        for ch in 0..3 {
            check(c)?;
            let mut source = ws.plane()?;
            let mut inputs: Vec<File> = sources
                .iter()
                .map(|path| File::open(path).map_err(io_error))
                .collect::<R<_>>()?;
            let mut offset = 0;
            while offset < n {
                check(c)?;
                let count = (n - offset).min(u64::from(CHUNK_PIXELS)) as usize;
                let mut logs = [
                    original::read64(&mut inputs[0], count)?,
                    original::read64(&mut inputs[1], count)?,
                    original::read64(&mut inputs[2], count)?,
                ];
                let at = Instant::now();
                let mut upload = Vec::with_capacity(count * 12);
                for i in 0..count {
                    for values in &logs {
                        upload.extend_from_slice(&(values[i] as f32).to_le_bytes());
                    }
                }
                self.metrics.upload_ms += at.elapsed().as_secs_f64() * 1000.;
                self.runner
                    .upload_aux(u64::from(CHUNK_BASE) * 4, &upload, c, &mut self.metrics)?;
                if let Some(correction) = correction {
                    let actual = log_prefix::apply(
                        &self.runner,
                        correction,
                        &[&logs[0], &logs[1], &logs[2]],
                        c,
                        &mut self.metrics,
                        &mut self.prefix,
                    )?;
                    for (sample, i) in sample_indices(count).into_iter().enumerate() {
                        for lane in 0..3 {
                            logs[lane][i] = f64::from(actual[3 * sample + lane]);
                        }
                    }
                }
                self.runner.dispatch(
                    original::base(&p, self.tables, ch, 0, count, 12),
                    c,
                    &mut self.metrics,
                )?;
                let actual = original::sample(&self.runner, &p, 0, count, c, &mut self.metrics)?;
                original::guard(
                    sample_indices(count)
                        .into_iter()
                        .map(|i| p.initial_f64(ch, [logs[0][i], logs[1][i], logs[2][i]])),
                    &actual,
                    &mut self.metrics,
                )?;
                let values = self.read_plane(0, count, c)?;
                source.write(offset, &values)?;
                offset += count as u64;
            }
            drop(inputs);
            if p.dir_active && p.core_sigma > 0. {
                let core = files::blur(
                    &mut ws,
                    &source,
                    p.width,
                    p.height,
                    p.core_sigma,
                    capacity,
                    self,
                    c,
                )?;
                let mut accum = ws.plane()?;
                for (amp, ratio) in FIT {
                    let blurred = files::blur(
                        &mut ws,
                        &source,
                        p.width,
                        p.height,
                        (ratio * p.tail_decay as f64) as f32,
                        capacity,
                        self,
                        c,
                    )?;
                    let mut params = [0; 40];
                    params[2] = 6;
                    params[12] = (amp as f32).to_bits();
                    accum = files::point_params(
                        &mut ws,
                        &[(4, &accum), (2, &blurred)],
                        params,
                        4,
                        capacity,
                        self,
                        c,
                    )?;
                }
                let mut params = [0; 40];
                params[2] = 13;
                params[13] = (p.tail_weight as f32).to_bits();
                source = files::point_params(
                    &mut ws,
                    &[(3, &core), (4, &accum)],
                    params,
                    0,
                    capacity,
                    self,
                    c,
                )?;
            }
            let mut output = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(outputs[ch])
                .map_err(io_error)?;
            pending.created += 1;
            let mut original_input = if p.dir_active {
                Some(File::open(sources[ch]).map_err(io_error)?)
            } else {
                None
            };
            let mut offset = 0;
            while offset < n {
                check(c)?;
                let count = (n - offset).min(u64::from(CHUNK_PIXELS)) as usize;
                let values = source.read(offset, count)?;
                let final_values = if let Some(input) = &mut original_input {
                    let mut logs = original::read64(input, count)?;
                    let at = Instant::now();
                    let upload: Vec<u8> = logs
                        .iter()
                        .flat_map(|&x| (x as f32).to_le_bytes())
                        .collect();
                    self.metrics.upload_ms += at.elapsed().as_secs_f64() * 1000.;
                    self.runner.upload_aux(
                        u64::from(CHUNK_BASE) * 4,
                        &upload,
                        c,
                        &mut self.metrics,
                    )?;
                    if let Some(correction) = correction {
                        let actual = log_prefix::apply(
                            &self.runner,
                            correction,
                            &[&logs],
                            c,
                            &mut self.metrics,
                            &mut self.prefix,
                        )?;
                        for (sample, i) in sample_indices(count).into_iter().enumerate() {
                            logs[i] = f64::from(actual[sample]);
                        }
                    }
                    self.runner
                        .upload_stream_plane(0, &values, c, &mut self.metrics)?;
                    let correction =
                        original::sample(&self.runner, &p, 0, count, c, &mut self.metrics)?;
                    self.runner.dispatch(
                        original::base(&p, self.tables, ch, 0, count, 14),
                        c,
                        &mut self.metrics,
                    )?;
                    let actual =
                        original::sample(&self.runner, &p, 0, count, c, &mut self.metrics)?;
                    original::guard(
                        sample_indices(count)
                            .into_iter()
                            .zip(&correction)
                            .map(|(i, &corr)| p.before_dir.at_f64(ch, logs[i] - f64::from(corr))),
                        &actual,
                        &mut self.metrics,
                    )?;
                    self.read_plane(0, count, c)?
                } else {
                    values
                };
                if final_values.iter().any(|v| !v.is_finite()) {
                    return Err("NONFINITE_DIR_OUTPUT".into());
                }
                let encoded: Vec<u8> = final_values
                    .into_iter()
                    .flat_map(|x| f64::from(x).to_le_bytes())
                    .collect();
                output.write_all(&encoded).map_err(io_error)?;
                offset += count as u64;
                progress(ch as u64 * n + offset, 3 * n);
            }
            output.sync_all().map_err(io_error)?;
        }
        check(c)?;
        if self.runner.read_error_flag(c, &mut self.metrics)? != 0 {
            return Err("GPU_DIR_NUMERIC_DOMAIN".into());
        }
        self.complete = true;
        pending.keep = true;
        Ok(())
    }
    pub(crate) fn report(&self) -> R<serde_json::Value> {
        self.alive()?;
        if !self.complete {
            return Err("GPU_DIR_STREAM_INCOMPLETE".into());
        }
        let mut report = serde_json::to_value(&self.metrics).map_err(|_| "INVALID_DIR_RESULT")?;
        let o = report.as_object_mut().ok_or("INVALID_DIR_RESULT")?;
        let count = self.metrics.sample_count + self.spatial.sample_count;
        o.insert("gpuUsed".into(), true.into());
        o.insert("nativeStageSealed".into(), false.into());
        o.insert("contextCount".into(), 1.into());
        o.insert("layout".into(), LAYOUT.into());
        o.insert("fullResolution".into(), true.into());
        o.insert("passCount".into(), self.spatial.pass_count.into());
        o.insert("sampleCount".into(), count.into());
        o.insert(
            "sampleMaxAbs".into(),
            self.metrics
                .sample_max_abs
                .max(self.spatial.sample_max_abs)
                .into(),
        );
        o.insert(
            "sampleRms".into(),
            ((self.metrics.sample_sum + self.spatial.sample_sum_squared) / count.max(1) as f64)
                .sqrt()
                .into(),
        );
        o.insert(
            "elapsedMs".into(),
            (self.started.elapsed().as_secs_f64() * 1000.).into(),
        );
        o.insert("dirActive".into(), self.plan.parameters().dir_active.into());
        o.insert(
            "chunkCount".into(),
            self.plan.parameters().chunk_count().into(),
        );
        o.insert(
            "spatial".into(),
            serde_json::to_value(&self.spatial).map_err(|_| "INVALID_DIR_RESULT")?,
        );
        o.insert(
            "streamMemory".into(),
            serde_json::to_value(self.plan.memory()?).map_err(|_| "INVALID_DIR_RESULT")?,
        );
        if self.plan.log_correction().is_some() {
            o.insert("logSampleCount".into(), self.prefix.count.into());
            o.insert("logSampleMaxAbs".into(), self.prefix.max.into());
            o.insert(
                "logSampleRms".into(),
                (self.prefix.sum / self.prefix.count.max(1) as f64)
                    .sqrt()
                    .into(),
            );
        }
        Ok(report)
    }
}
impl Compute for StreamingDir {
    fn execute(
        &mut self,
        inputs: &[(u32, &[f32])],
        p: [u32; 40],
        output: u32,
        c: &mut impl FnMut() -> bool,
    ) -> R<Vec<f32>> {
        self.alive()?;
        if self.complete {
            return Err("GPU_DIR_STREAM_COMPLETE".into());
        }
        let result = (|| {
            check(c)?;
            let count = u64::from(p[0]) * u64::from(p[1]);
            let (expected, target): (&[u32], u32) = match p[2] {
                0 | 2 | 4 if p[6] == 0 && p[7] == 1 => (&[0], 1),
                6 if FIT.iter().any(|(amp, _)| (*amp as f32).to_bits() == p[12]) => (&[4, 2], 4),
                13 if p[13] == (self.plan.parameters().tail_weight as f32).to_bits() => {
                    (&[3, 4], 0)
                }
                _ => return Err("GPU_STREAM_DISPATCH_CONTRACT".into()),
            };
            if count == 0
                || count > u64::from(stream::BATCH_SAMPLES)
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
            let before = (
                self.metrics.upload_bytes,
                self.metrics.readback_bytes,
                self.metrics.upload_ms,
            );
            for &(slot, values) in inputs {
                self.runner
                    .upload_stream_plane(slot, values, c, &mut self.metrics)?;
            }
            let started = Instant::now();
            self.runner.dispatch(p, c, &mut self.metrics)?;
            let values = self.read_plane(output, count as usize, c)?;
            self.spatial.upload_bytes += self.metrics.upload_bytes - before.0;
            self.spatial.readback_bytes += self.metrics.readback_bytes - before.1;
            self.spatial.upload_ms += self.metrics.upload_ms - before.2;
            self.spatial.compute_readback_ms += started.elapsed().as_secs_f64() * 1000.;
            self.spatial.dispatches += 1;
            Ok(values)
        })();
        self.finish(result)
    }
    fn metrics(&mut self) -> &mut StreamMetrics {
        &mut self.spatial
    }
}
fn io_error(_: std::io::Error) -> String {
    "GPU_DIR_STREAM_IO".into()
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{gpu_session as session, gpu_session_contract::StageTicket};
    use std::{
        fs,
        path::PathBuf,
        sync::atomic::{AtomicU64, Ordering},
    };
    static NEXT: AtomicU64 = AtomicU64::new(1);
    struct Root(PathBuf);
    impl Root {
        fn new() -> Self {
            let p = std::env::temp_dir().join(format!(
                "stream-dir-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&p).unwrap();
            Self(p)
        }
    }
    impl Drop for Root {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn fixture(w: u32, h: u32, positive: bool) -> DirPlan {
        super::super::tests::fixture(w, h, positive)
    }
    fn files(root: &Root, w: u32, h: u32) -> ([PathBuf; 3], [PathBuf; 3], [Vec<f64>; 3]) {
        let source = std::array::from_fn(|ch| {
            (0..w * h)
                .map(|i| ((i * 17 + (i / w) * 31 + ch as u32 * 23) % 101) as f64 / 13. - 0.2)
                .collect::<Vec<_>>()
        });
        let inputs = std::array::from_fn(|ch| root.0.join(format!("source-{ch}")));
        let outputs = std::array::from_fn(|ch| root.0.join(format!("output-{ch}")));
        for ch in 0..3 {
            fs::write(
                &inputs[ch],
                source[ch]
                    .iter()
                    .flat_map(|x| x.to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
        }
        (inputs, outputs, source)
    }
    fn reference(plan: &StreamDirPlan, source: &[Vec<f64>; 3]) -> [Vec<f64>; 3] {
        let logs = std::array::from_fn(|ch| {
            source[ch]
                .iter()
                .map(|&x| match plan.log_correction() {
                    Some(k) => f64::from(log_prefix::mirror(x, k).unwrap()),
                    None => x,
                })
                .collect()
        });
        crate::research_dir::cpu_mirror(plan.parameters(), &logs, &mut || false).unwrap()
    }
    fn compare(outputs: &[PathBuf; 3], expected: &[Vec<f64>; 3]) {
        let (mut maximum, mut sum, mut count) = (0f64, 0f64, 0usize);
        for ch in 0..3 {
            let bytes = fs::read(&outputs[ch]).unwrap();
            assert_eq!(bytes.len(), expected[ch].len() * 8);
            for (b, &want) in bytes.chunks_exact(8).zip(&expected[ch]) {
                let actual = f64::from_le_bytes(b.try_into().unwrap());
                let error = (actual - want).abs();
                assert!(
                    actual.is_finite() && error <= 2e-4 * want.abs().max(1.),
                    "ch{ch} actual={actual} expected={want}"
                );
                maximum = maximum.max(error);
                sum += error * error;
                count += 1;
            }
        }
        eprintln!(
            "streamed DIR max={maximum} rms={}",
            (sum / count as f64).sqrt()
        );
    }
    fn activate(plan: &StreamDirPlan, epoch: u64) -> StageTicket {
        let m = plan.memory().unwrap();
        let ticket = StageTicket {
            epoch,
            generation: 1,
            sequence: 1,
        };
        session::activate(
            epoch,
            m.combined_peak_bytes,
            m.host_bytes + m.helper_allowance_bytes,
            m.pipeline_count as usize,
        )
        .unwrap();
        session::begin_frame(epoch, 1).unwrap();
        session::begin_stage(ticket, m.driver_allowance_bytes).unwrap();
        ticket
    }
    #[test]
    #[ignore = "one process: actual activated Context, original full log/DIR graph crosses 65536 with forced full-line batches"]
    fn actual_vulkan_streamed_log_dir_crosses_chunks_and_seals() {
        let root = Root::new();
        let (w, h) = (257, 257);
        let plan = StreamDirPlan::from_plan(fixture(w, h, true), Some(0.73)).unwrap();
        let (inputs, outputs, source) = files(&root, w, h);
        let expected = reference(&plan, &source);
        let ticket = activate(&plan, 9401);
        let mut gpu = StreamingDir::new_probe(&plan, &mut || false).unwrap();
        assert_eq!(gpu.report().unwrap_err(), "GPU_DIR_STREAM_INCOMPLETE");
        assert!(
            session::complete_stage(ticket).is_err(),
            "live owner must prevent native seal"
        );
        let report = gpu
            .run_capacity(
                inputs.each_ref().map(|p| p.as_path()),
                outputs.each_ref().map(|p| p.as_path()),
                &root.0.join("scratch"),
                32768,
                &mut || false,
                &mut |_, _| {},
            )
            .unwrap();
        compare(&outputs, &expected);
        assert!(!root.0.join("scratch").exists());
        assert_eq!(
            report["passCount"].as_u64().unwrap(),
            plan.parameters().pass_count()
        );
        assert_eq!(
            report["sampleCount"].as_u64().unwrap(),
            plan.parameters().pass_count() * u64::from(w + h) + 96
        );
        assert!(report["logSampleCount"].as_u64().unwrap() > 0);
        assert_eq!(report["fullResolution"], true);
        assert_eq!(report["nativeStageSealed"], false);
        assert_eq!(report["deviceBytes"], plan.memory().unwrap().device_bytes);
        assert_eq!(
            gpu.run_files(
                [Path::new("unused"); 3],
                [Path::new("unused"); 3],
                Path::new("unused"),
                &mut || false,
                &mut |_, _| {}
            )
            .unwrap_err(),
            "GPU_DIR_STREAM_COMPLETE"
        );
        drop(gpu);
        let stats = session::complete_stage(ticket).unwrap();
        assert!(stats.charged_bytes <= plan.memory().unwrap().combined_peak_bytes);
        assert_eq!(
            stats.pipeline_slots,
            plan.memory().unwrap().pipeline_count as usize
        );
        session::finish_frame(ticket.epoch, 1).unwrap();
        for ch in 0..3 {
            assert_eq!(
                fs::read(&inputs[ch]).unwrap(),
                source[ch]
                    .iter()
                    .flat_map(|x| x.to_le_bytes())
                    .collect::<Vec<_>>()
            );
        }
    }
    #[test]
    #[ignore = "explicit Vulkan edge modes; original inactive DIR, zero-diffusion redevelopment and narrow reflected lines"]
    fn actual_vulkan_streamed_dir_edge_modes_preserve_original_graph() {
        for (w, h, positive, active, sigma, log) in [
            (1, 9, true, true, 2.999, None),
            (9, 1, false, false, 3.5, Some(0.73)),
            (23, 17, false, true, 0., None),
        ] {
            let root = Root::new();
            let mut p = fixture(w, h, positive);
            p.dir_active = active;
            p.core_sigma = sigma;
            let plan = StreamDirPlan::from_plan(p, log).unwrap();
            let (inputs, outputs, source) = files(&root, w, h);
            let expected = reference(&plan, &source);
            let mut gpu = StreamingDir::new_probe(&plan, &mut || false).unwrap();
            gpu.run_capacity(
                inputs.each_ref().map(|p| p.as_path()),
                outputs.each_ref().map(|p| p.as_path()),
                &root.0.join("scratch"),
                w.max(h) * 2 + 3,
                &mut || false,
                &mut |_, _| {},
            )
            .unwrap();
            compare(&outputs, &expected);
            assert!(!root.0.join("scratch").exists());
        }
    }
    #[test]
    #[ignore = "one process: actual post-submit cancellation terminalizes typed DIR owner and retires activated session"]
    fn actual_vulkan_streamed_dir_post_submit_cancel_forbids_reuse_and_seal() {
        let plan = StreamDirPlan::from_plan(fixture(9, 1, false), None).unwrap();
        let ticket = activate(&plan, 9402);
        let mut gpu = StreamingDir::new_probe(&plan, &mut || false).unwrap();
        let before = gpu.metrics.dispatches;
        let mut p = [0; 40];
        p[0] = 8192;
        p[1] = 1;
        p[2] = 4;
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
        assert_eq!(gpu.metrics.dispatches, before + 1);
        assert_eq!(gpu.finish(result).unwrap_err(), "CANCELLED");
        assert_eq!(gpu.report().unwrap_err(), "GPU_DIR_STREAM_FAILED");
        assert_eq!(
            gpu.run_files(
                [Path::new("unused"); 3],
                [Path::new("unused"); 3],
                Path::new("unused"),
                &mut || false,
                &mut |_, _| {}
            )
            .unwrap_err(),
            "GPU_DIR_STREAM_FAILED"
        );
        drop(gpu);
        assert!(session::complete_stage(ticket).is_err());
        assert!(session::finish_frame(ticket.epoch, 1).is_err());
        assert!(session::is_cancelled(ticket.epoch));
    }
    #[test]
    #[ignore = "one process: actual files graph cancel after first candidate removes all private outputs and retires Context"]
    fn actual_vulkan_streamed_dir_partial_cancel_removes_candidates() {
        use std::sync::atomic::AtomicBool;
        let root = Root::new();
        let mut p = fixture(9, 1, false);
        p.dir_active = false;
        let plan = StreamDirPlan::from_plan(p, Some(0.73)).unwrap();
        let ticket = activate(&plan, 9403);
        let (inputs, outputs, source) = files(&root, 9, 1);
        let mut gpu = StreamingDir::new_probe(&plan, &mut || false).unwrap();
        let cancelled = AtomicBool::new(false);
        let result = gpu.run_files(
            inputs.each_ref().map(|p| p.as_path()),
            outputs.each_ref().map(|p| p.as_path()),
            &root.0.join("scratch"),
            &mut || cancelled.load(Ordering::Relaxed),
            &mut |done, _| {
                if done >= 9 {
                    cancelled.store(true, Ordering::Relaxed);
                }
            },
        );
        assert_eq!(result.unwrap_err(), "CANCELLED");
        assert!(outputs.iter().all(|p| !p.exists()));
        assert!(!root.0.join("scratch").exists());
        assert_eq!(gpu.report().unwrap_err(), "GPU_DIR_STREAM_FAILED");
        drop(gpu);
        assert!(session::complete_stage(ticket).is_err());
        for ch in 0..3 {
            assert_eq!(
                fs::read(&inputs[ch]).unwrap(),
                source[ch]
                    .iter()
                    .flat_map(|x| x.to_le_bytes())
                    .collect::<Vec<_>>()
            );
        }
    }
}
