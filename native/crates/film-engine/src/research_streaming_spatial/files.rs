//! Bounded byte gather/scatter. No production convolution or point arithmetic.
use super::*;
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
};

fn io<T>(value: std::io::Result<T>) -> R<T> {
    value.map_err(|_| "GPU_STREAM_IO".into())
}
pub(crate) fn validate_paths(
    p: &StreamPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    scratch: &Path,
) -> R<()> {
    p.validate()?;
    let expected = p.width as u64 * p.height as u64 * 8;
    for (i, path) in sources.iter().enumerate() {
        let meta = io(fs::symlink_metadata(path))?;
        if !meta.is_file()
            || meta.file_type().is_symlink()
            || meta.len() != expected
            || sources.contains(&outputs[i])
            || outputs[..i].contains(&outputs[i])
            || outputs[i].starts_with(scratch)
        {
            return Err("INVALID_GPU_STREAM_FILE".into());
        }
    }
    for path in outputs.into_iter().chain(std::iter::once(scratch)) {
        let parent = path.parent().ok_or("INVALID_GPU_STREAM_FILE")?;
        if io(parent.canonicalize())? != parent || path.file_name().is_none() {
            return Err("INVALID_GPU_STREAM_FILE".into());
        }
        match fs::symlink_metadata(path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            _ => return Err("INVALID_GPU_STREAM_FILE".into()),
        }
    }
    Ok(())
}

pub(crate) struct Workspace {
    path: PathBuf,
    next: u64,
    samples: u64,
}
impl Workspace {
    pub(crate) fn new(path: &Path, samples: u64) -> R<Self> {
        io(fs::create_dir(path))?;
        Ok(Self {
            path: path.to_owned(),
            next: 0,
            samples,
        })
    }
    pub(crate) fn plane(&mut self) -> R<Plane> {
        let path = self.path.join(format!("plane-{}.f32le", self.next));
        self.next += 1;
        let file = io(OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&path))?;
        let plane = Plane {
            file,
            path,
            samples: self.samples,
        };
        // A new zeroed file is the exact +0.0 initializer for accumulation.
        io(plane.file.set_len(self.samples * 4))?;
        Ok(plane)
    }
    #[cfg(feature = "gpu-diffusion-research")]
    fn sampled_plane(&mut self, sample: impl FnOnce(&Path) -> R<()>) -> R<Plane> {
        let path = self.path.join(format!("plane-{}.f32le", self.next));
        self.next += 1;
        sample(&path)?;
        let meta = io(fs::symlink_metadata(&path))?;
        if !meta.is_file() || meta.file_type().is_symlink() || meta.len() != self.samples * 4 {
            return Err("INVALID_GPU_STREAM_FILE".into());
        }
        let file = io(OpenOptions::new().read(true).write(true).open(&path))?;
        Ok(Plane {
            file,
            path,
            samples: self.samples,
        })
    }
}
impl Drop for Workspace {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}
pub(crate) struct Plane {
    file: File,
    path: PathBuf,
    samples: u64,
}
impl Drop for Plane {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}
impl Plane {
    fn range(&self, start: u64, count: usize) -> R<()> {
        if count > BATCH_SAMPLES as usize
            || start
                .checked_add(count as u64)
                .is_none_or(|n| n > self.samples)
        {
            return Err("GPU_STREAM_LINE_LIMIT".into());
        }
        Ok(())
    }
    pub(crate) fn read(&self, start: u64, count: usize) -> R<Vec<f32>> {
        self.range(start, count)?;
        let mut bytes = vec![0u8; count * 4];
        let mut file = &self.file;
        io(file.seek(SeekFrom::Start(start * 4)))?;
        io(file.read_exact(&mut bytes))?;
        Ok(bytes
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
            .collect())
    }
    pub(crate) fn write(&self, start: u64, values: &[f32]) -> R<()> {
        self.range(start, values.len())?;
        let bytes: Vec<u8> = values.iter().flat_map(|x| x.to_le_bytes()).collect();
        let mut file = &self.file;
        io(file.seek(SeekFrom::Start(start * 4)))?;
        io(file.write_all(&bytes))
    }
    fn gather(
        &self,
        width: u32,
        axis: Axis,
        batch: LineBatch,
        c: &mut impl FnMut() -> bool,
    ) -> R<Vec<f32>> {
        check(c)?;
        if axis == Axis::Rows {
            return self.read(
                batch.first as u64 * width as u64,
                (batch.length * batch.count) as usize,
            );
        }
        let mut packed = vec![0.; (batch.length * batch.count) as usize];
        for y in 0..batch.length {
            check(c)?;
            let row = self.read(
                y as u64 * width as u64 + batch.first as u64,
                batch.count as usize,
            )?;
            for (x, value) in row.into_iter().enumerate() {
                packed[x * batch.length as usize + y as usize] = value;
            }
        }
        Ok(packed)
    }
    fn scatter(
        &self,
        width: u32,
        axis: Axis,
        batch: LineBatch,
        packed: &[f32],
        c: &mut impl FnMut() -> bool,
    ) -> R<()> {
        check(c)?;
        if packed.len() != (batch.length * batch.count) as usize {
            return Err("GPU_STREAM_LINE_LIMIT".into());
        }
        if axis == Axis::Rows {
            return self.write(batch.first as u64 * width as u64, packed);
        }
        let mut row = vec![0.; batch.count as usize];
        for y in 0..batch.length {
            check(c)?;
            for (x, value) in row.iter_mut().enumerate() {
                *value = packed[x * batch.length as usize + y as usize];
            }
            self.write(y as u64 * width as u64 + batch.first as u64, &row)?;
        }
        Ok(())
    }
}

fn import(source: &Path, out: &Plane, c: &mut impl FnMut() -> bool) -> R<()> {
    let mut file = io(File::open(source))?;
    // Same f64 -> f32 conversion as resident upload. Temporary planes stay f32.
    let mut raw = vec![0u8; BATCH_SAMPLES as usize * 8];
    let mut start = 0;
    while start < out.samples {
        check(c)?;
        let count = (out.samples - start).min(BATCH_SAMPLES as u64) as usize;
        io(file.read_exact(&mut raw[..count * 8]))?;
        let values: Vec<f32> = raw[..count * 8]
            .chunks_exact(8)
            .map(|b| {
                let x = f64::from_le_bytes(b.try_into().unwrap());
                if !x.is_finite() || !(x as f32).is_finite() {
                    Err("NONFINITE_OPTICS_INPUT".into())
                } else {
                    Ok(x as f32)
                }
            })
            .collect::<R<_>>()?;
        out.write(start, &values)?;
        start += count as u64;
    }
    Ok(())
}

pub(crate) fn blur<G: Compute>(
    ws: &mut Workspace,
    source: &Plane,
    width: u32,
    height: u32,
    sigma: f32,
    capacity: u32,
    gpu: &mut G,
    c: &mut impl FnMut() -> bool,
) -> R<Plane> {
    let filter = Filter::new(sigma)?;
    let horizontal = ws.plane()?;
    let output = ws.plane()?;
    for (axis, input, destination) in [
        (Axis::Rows, source, &horizontal),
        (Axis::Columns, &horizontal, &output),
    ] {
        let middle = match axis {
            Axis::Rows => height / 2,
            Axis::Columns => width / 2,
        };
        for batch in batches(width, height, axis, capacity)? {
            let gather_started = std::time::Instant::now();
            let values = input.gather(width, axis, batch, c)?;
            gpu.metrics().gather_scatter_ms += gather_started.elapsed().as_secs_f64() * 1000.;
            // Packed columns are actual full columns, addressed as contiguous
            // rows. The original horizontal shader is used unmodified.
            let mut p = [0u32; 40];
            p[0] = batch.length;
            p[1] = batch.count;
            p[4] = batch.length * batch.count;
            p[6] = 0;
            p[7] = 1;
            match &filter {
                Filter::Identity => p[2] = 4,
                Filter::Fir(k) => {
                    p[2] = 0;
                    p[5] = (k.len() / 2) as u32;
                    for (i, &value) in k.iter().enumerate() {
                        p[16 + i] = (value as f32).to_bits();
                    }
                }
                Filter::Iir(k) => {
                    p[2] = 2;
                    p[4] = batch.count;
                    for (i, &value) in k.iter().enumerate() {
                        p[8 + i] = (value as f32).to_bits();
                    }
                }
            }
            let actual = gpu.execute(&[(0, &values)], p, 1, c)?;
            if (batch.first..batch.first + batch.count).contains(&middle) {
                let start = ((middle - batch.first) * batch.length) as usize;
                let end = start + batch.length as usize;
                let reference = filter.line_f64(
                    &values[start..end]
                        .iter()
                        .copied()
                        .map(f64::from)
                        .collect::<Vec<_>>(),
                );
                let (max, sum) = spatial::compare_lines(&reference, &actual[start..end])?;
                let metrics = gpu.metrics();
                metrics.sample_count += batch.length as u64;
                metrics.sample_max_abs = metrics.sample_max_abs.max(max);
                metrics.sample_sum_squared += sum;
            }
            let scatter_started = std::time::Instant::now();
            destination.scatter(width, axis, batch, &actual, c)?;
            gpu.metrics().gather_scatter_ms += scatter_started.elapsed().as_secs_f64() * 1000.;
        }
    }
    gpu.metrics().pass_count += 1;
    Ok(output)
}

fn point<G: Compute>(
    ws: &mut Workspace,
    inputs: &[(u32, &Plane)],
    op: u32,
    constants: [f32; 4],
    output: u32,
    capacity: u32,
    gpu: &mut G,
    c: &mut impl FnMut() -> bool,
) -> R<Plane> {
    let mut p = [0; 40];
    p[2] = op;
    for (i, value) in constants.into_iter().enumerate() {
        p[12 + i] = value.to_bits();
    }
    point_params(ws, inputs, p, output, capacity, gpu, c)
}

pub(crate) fn point_params<G: Compute>(
    ws: &mut Workspace,
    inputs: &[(u32, &Plane)],
    mut p: [u32; 40],
    output: u32,
    capacity: u32,
    gpu: &mut G,
    c: &mut impl FnMut() -> bool,
) -> R<Plane> {
    let out = ws.plane()?;
    let mut start = 0;
    while start < ws.samples {
        check(c)?;
        let count = (ws.samples - start).min(capacity as u64) as usize;
        let loaded: Vec<_> = inputs
            .iter()
            .map(|(slot, plane)| Ok((*slot, plane.read(start, count)?)))
            .collect::<R<_>>()?;
        let refs: Vec<_> = loaded
            .iter()
            .map(|(slot, values)| (*slot, values.as_slice()))
            .collect();
        p[0] = count as u32;
        p[1] = 1;
        p[4] = count as u32;
        let result = gpu.execute(&refs, p, output, c)?;
        out.write(start, &result)?;
        start += count as u64;
    }
    Ok(out)
}

fn channel<G: Compute>(
    ws: &mut Workspace,
    mut source: Plane,
    width: u32,
    height: u32,
    p: &ChannelPlan,
    capacity: u32,
    gpu: &mut G,
    c: &mut impl FnMut() -> bool,
) -> R<Plane> {
    if p.lens_sigma > 0. {
        source = blur(ws, &source, width, height, p.lens_sigma, capacity, gpu, c)?;
    }
    if let Some(s) = &p.scatter {
        let core = blur(ws, &source, width, height, s.sigma, capacity, gpu, c)?;
        let mut accum = ws.plane()?;
        for (weight, ratio) in spatial::FIT {
            let blurred = blur(
                ws,
                &source,
                width,
                height,
                (ratio * s.decay as f64) as f32,
                capacity,
                gpu,
                c,
            )?;
            accum = point(
                ws,
                &[(4, &accum), (2, &blurred)],
                6,
                [weight as f32, 0., 0., 0.],
                4,
                capacity,
                gpu,
                c,
            )?;
        }
        source = point(
            ws,
            &[(0, &source), (3, &core), (4, &accum)],
            7,
            [s.amount as f32, s.tail_weight as f32, 0., 0.],
            0,
            capacity,
            gpu,
            c,
        )?;
    }
    if !p.bounce_sigmas.is_empty() {
        let mut accum = ws.plane()?;
        for (&sigma, &weight) in p.bounce_sigmas.iter().zip(&p.bounce_weights) {
            let blurred = blur(ws, &source, width, height, sigma, capacity, gpu, c)?;
            accum = point(
                ws,
                &[(4, &accum), (2, &blurred)],
                6,
                [weight as f32, 0., 0., 0.],
                4,
                capacity,
                gpu,
                c,
            )?;
        }
        source = point(
            ws,
            &[(0, &source), (4, &accum)],
            8,
            [0., 0., p.strength as f32, 0.],
            0,
            capacity,
            gpu,
            c,
        )?;
    }
    if p.divisor != 1. {
        source = point(
            ws,
            &[(0, &source)],
            9,
            [0., 0., 0., p.divisor as f32],
            0,
            capacity,
            gpu,
            c,
        )?;
    }
    Ok(source)
}

struct Outputs<'a> {
    paths: [&'a Path; 3],
    created: usize,
    keep: bool,
}
impl Drop for Outputs<'_> {
    fn drop(&mut self) {
        if !self.keep {
            for path in &self.paths[..self.created] {
                let _ = fs::remove_file(path);
            }
        }
    }
}
pub(super) fn run<G: Compute>(
    plan: &StreamPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    scratch: &Path,
    capacity: u32,
    gpu: &mut G,
    c: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
) -> R<()> {
    validate_paths(plan, sources, outputs, scratch)?;
    // Fail before creating any owned files if a complete line cannot fit.
    batches(plan.width, plan.height, Axis::Rows, capacity)?;
    batches(plan.width, plan.height, Axis::Columns, capacity)?;
    check(c)?;
    let n = plan.width as u64 * plan.height as u64;
    let mut ws = Workspace::new(scratch, n)?;
    let mut guard = Outputs {
        paths: outputs,
        created: 0,
        keep: false,
    };
    for ch in 0..3 {
        let source = ws.plane()?;
        import(sources[ch], &source, c)?;
        let result = channel(
            &mut ws,
            source,
            plan.width,
            plan.height,
            &plan.channels[ch],
            capacity,
            gpu,
            c,
        )?;
        check(c)?;
        let mut output = io(OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(outputs[ch]))?;
        guard.created += 1;
        let mut start = 0;
        while start < n {
            check(c)?;
            let count = (n - start).min(capacity as u64) as usize;
            let values = result.read(start, count)?;
            if values.iter().any(|x| !x.is_finite()) {
                return Err("NONFINITE_OPTICS_OUTPUT".into());
            }
            let encoded: Vec<u8> = values
                .into_iter()
                .flat_map(|x| (x as f64).to_le_bytes())
                .collect();
            io(output.write_all(&encoded))?;
            start += count as u64;
            progress(ch as u64 * n + start, 3 * n);
        }
        io(output.sync_all())?;
    }
    check(c)?;
    guard.keep = true;
    Ok(())
}

/// Full original coordinate-grain graph. Sampler identity and admission are
/// supplied only by its typed owner; all spatial and point arithmetic is GPU.
#[cfg(feature = "gpu-diffusion-research")]
pub(super) fn run_grain(
    plan: &crate::research_streaming_grain::StreamGrainPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    scratch: &Path,
    capacity: u32,
    sampler: &mut crate::research_streaming_grain::streaming_sampler::StreamingSampler,
    c: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
) -> R<()> {
    let result = run_grain_inner(
        plan, sources, outputs, scratch, capacity, sampler, c, progress,
    );
    if result.is_err() {
        sampler.poison();
    }
    result
}

#[cfg(feature = "gpu-diffusion-research")]
fn run_grain_inner(
    plan: &crate::research_streaming_grain::StreamGrainPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    scratch: &Path,
    capacity: u32,
    sampler: &mut crate::research_streaming_grain::streaming_sampler::StreamingSampler,
    c: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
) -> R<()> {
    plan.validate()?;
    if plan.parameters() != sampler.plan().parameters() {
        return Err("GPU_GRAIN_STREAM_PLAN_IDENTITY".into());
    }
    let p = plan.parameters();
    let empty = ChannelPlan {
        lens_sigma: 0.,
        scatter: None,
        bounce_sigmas: vec![],
        bounce_weights: vec![],
        strength: 0.,
        divisor: 1.,
    };
    let geometry =
        StreamPlan::from_channels(p.width, p.height, std::array::from_fn(|_| empty.clone()))?;
    validate_paths(&geometry, sources, outputs, scratch)?;
    batches(p.width, p.height, Axis::Rows, capacity)?;
    batches(p.width, p.height, Axis::Columns, capacity)?;
    check(c)?;
    let n = p.width as u64 * p.height as u64;
    let mut ws = Workspace::new(scratch, n)?;
    let mut guard = Outputs {
        paths: outputs,
        created: 0,
        keep: false,
    };
    for ch in 0..3 {
        let original = ws.sampled_plane(|out| sampler.original(sources[ch], out, c))?;
        let mut result = if p.active {
            let mut accum = ws.plane()?;
            for (sl, layer) in p.layers[ch].iter().enumerate() {
                let mut sampled =
                    ws.sampled_plane(|out| sampler.sample_layer(ch, sl, sources[ch], out, c))?;
                if layer.cloud_sigma > 0. {
                    sampled = blur(
                        &mut ws,
                        &sampled,
                        p.width,
                        p.height,
                        layer.cloud_sigma,
                        capacity,
                        sampler,
                        c,
                    )?;
                }
                let mut params = [0; 40];
                params[2] = 56;
                params[26] = 0;
                accum = point_params(
                    &mut ws,
                    &[(4, &accum), (0, &sampled)],
                    params,
                    4,
                    capacity,
                    sampler,
                    c,
                )?;
            }
            if p.micro_active {
                let mut micro = ws.sampled_plane(|out| sampler.microstructure(ch, out, c))?;
                if p.micro_spread > 0. {
                    micro = blur(
                        &mut ws,
                        &micro,
                        p.width,
                        p.height,
                        p.micro_spread,
                        capacity,
                        sampler,
                        c,
                    )?;
                }
                let mut params = [0; 40];
                params[2] = 57;
                params[26] = 0;
                accum = point_params(
                    &mut ws,
                    &[(4, &accum), (0, &micro)],
                    params,
                    4,
                    capacity,
                    sampler,
                    c,
                )?;
            }
            let mut params = [0; 40];
            params[2] = 58;
            params[22] = (if p.sublayers {
                1.
            } else {
                1. / p.layer_count() as f64
            } as f32)
                .to_bits();
            params[23] = (p.density_min[ch] as f32).to_bits();
            let mut result =
                point_params(&mut ws, &[(4, &accum)], params, 0, capacity, sampler, c)?;
            drop(accum);
            if p.final_sigma > 0. {
                result = blur(
                    &mut ws,
                    &result,
                    p.width,
                    p.height,
                    p.final_sigma,
                    capacity,
                    sampler,
                    c,
                )?;
            }
            if p.amount != 1. {
                let mut params = [0; 40];
                params[2] = 59;
                params[21] = (p.amount as f32).to_bits();
                result = point_params(
                    &mut ws,
                    &[(0, &result), (3, &original)],
                    params,
                    0,
                    capacity,
                    sampler,
                    c,
                )?;
            }
            result
        } else {
            original
        };
        let mut params = [0; 40];
        params[2] = 60;
        result = point_params(&mut ws, &[(0, &result)], params, 0, capacity, sampler, c)?;
        check(c)?;
        let mut output = io(OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(outputs[ch]))?;
        guard.created += 1;
        let mut start = 0;
        while start < n {
            check(c)?;
            let count = (n - start).min(capacity as u64) as usize;
            let values = result.read(start, count)?;
            if values.iter().any(|value| !value.is_finite()) {
                return Err("NONFINITE_GRAIN_OUTPUT".into());
            }
            let encoded: Vec<u8> = values
                .into_iter()
                .flat_map(|value| (value as f64).to_le_bytes())
                .collect();
            io(output.write_all(&encoded))?;
            start += count as u64;
            progress(ch as u64 * n + start, 3 * n);
        }
        io(output.sync_all())?;
    }
    check(c)?;
    guard.keep = true;
    Ok(())
}
