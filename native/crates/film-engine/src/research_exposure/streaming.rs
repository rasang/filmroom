//! Shared bounded file loop. Tests exercise the production atomic publication path.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use super::full_stage::*;
use super::{ExposureDescriptor, NUMERIC_TOLERANCE};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{BufWriter, Read, Write},
    path::Path,
    time::Instant,
};
#[derive(Default, Debug)]
pub(super) struct Samples {
    pub count: u64,
    pub max_abs: f64,
    pub sum: f64,
    pub guard_ms: f64,
}
impl Samples {
    pub fn rms(&self) -> f64 {
        (self.sum / self.count.max(1) as f64).sqrt()
    }
}
pub(super) fn sample_indices(pixels: usize) -> Vec<usize> {
    let count = pixels.min(8);
    if count <= 1 {
        return (0..count).collect();
    }
    (0..count).map(|i| i * (pixels - 1) / (count - 1)).collect()
}
fn pixel(source: &[u8], at: usize) -> [f32; 3] {
    std::array::from_fn(|ch| {
        f32::from_le_bytes(
            source[at * 12 + ch * 4..at * 12 + ch * 4 + 4]
                .try_into()
                .unwrap(),
        )
    })
}
pub(super) fn guard_chunk(
    d: &ExposureDescriptor,
    source: &[u8],
    output: &[f32],
    stats: &mut Samples,
) -> R<()> {
        trace::phase_result(GpuPhase::Guard, || {
    if source.len() % 12 != 0
        || output.len() * 4 != source.len()
        || output.iter().any(|x| !x.is_finite())
    {
        return err("GPU_EXPOSURE_NONFINITE");
    }
    let at = Instant::now();
    for i in sample_indices(source.len() / 12) {
        let reference = d.reference(pixel(source, i));
        for ch in 0..3 {
            let r = reference[ch];
            let a = output[i * 3 + ch] as f64;
            let error = (r - a).abs();
            if !r.is_finite() || error > NUMERIC_TOLERANCE * r.abs().max(1.) {
                return err("GPU_EXPOSURE_NUMERIC_CHECK");
            }
            stats.max_abs = stats.max_abs.max(error);
            stats.sum += error * error;
            stats.count += 1;
        }
    }
    stats.guard_ms += at.elapsed().as_secs_f64() * 1000.;
    Ok(())

        })
    }
pub(super) fn write_candidate<C: FnMut() -> bool>(
    dir: &Path,
    p: &ExposurePlan,
    d: &ExposureDescriptor,
    c: &mut C,
    mut dispatch: impl FnMut(&[u8], usize, &mut C) -> R<Vec<f32>>,
) -> R<Samples> {
    check(c)?;
    let candidate = dir.join("candidate");
    let partial = dir.join(".candidate.partial");
    if !matches!(fs::symlink_metadata(&candidate), Err(e) if e.kind() == std::io::ErrorKind::NotFound)
    {
        return err("GPU_EXPOSURE_OUTPUT_COLLISION");
    }
    io(fs::create_dir(&partial))?;
    let mut owned = OwnedDir(partial.clone(), false);
    let mut outs = (0..3)
        .map(|ch| {
            Ok(BufWriter::with_capacity(
                65536,
                io(OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(partial.join(format!("plane-{ch}.f64le"))))?,
            ))
        })
        .collect::<R<Vec<_>>>()?;
    let mut hashes: [Sha256; 3] = std::array::from_fn(|_| Sha256::new());
    let mut input = io(File::open(dir.join("source.rgb.f32le")))?;
    let mut source = vec![0; p.chunk_pixels as usize * 12];
    let mut pack = vec![0; p.chunk_pixels as usize * 8];
    let mut left = p.width as u64 * p.height as u64;
    let mut stats = Samples::default();
    while left > 0 {
        check(c)?;
        let pixels = left.min(p.chunk_pixels as u64) as usize;
        let bytes = &mut source[..pixels * 12];
        io(input.read_exact(bytes))?;
        if bytes
            .chunks_exact(4)
            .any(|b| !f32::from_le_bytes(b.try_into().unwrap()).is_finite())
        {
            return err("GPU_EXPOSURE_NONFINITE");
        }
        let output = dispatch(bytes, pixels, c)?;
        check(c)?;
        guard_chunk(d, bytes, &output, &mut stats)?;
        for ch in 0..3 {
            check(c)?;
            let dst = &mut pack[..pixels * 8];
            for i in 0..pixels {
                dst[i * 8..i * 8 + 8].copy_from_slice(&(output[i * 3 + ch] as f64).to_le_bytes());
            }
            io(outs[ch].write_all(dst))?;
            hashes[ch].update(dst);
        }
        left -= pixels as u64;
    }
    let mut extra = [0u8; 1];
    if io(input.read(&mut extra))? != 0 {
        return err("INVALID_EXPOSURE_FILE");
    }
    let commit_phase=PhaseGuard::begin(GpuPhase::Commit);
    for out in &mut outs {
        check(c)?;
        io(out.flush())?;
        io(out.get_ref().sync_all())?;
    }
    drop(outs);
    let complete = Complete {
        schema_version: 1,
        operation: OPERATION.into(),
        token: p.token,
        manifest_sha256: p.manifest_sha256.clone(),
        width: p.width,
        height: p.height,
        output_sha256: hashes.map(|h| hex(&h.finalize())),
    };
    let bytes = serde_json::to_vec(&complete).map_err(|_| "INVALID_EXPOSURE_OUTPUT")?;
    let mut out = io(OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(partial.join("complete.json")))?;
    io(out.write_all(&bytes))?;
    io(out.sync_all())?;
    check(c)?;
    io(fs::rename(&partial, &candidate))?;
    commit_phase.finish();
    owned.1 = true;
    Ok(stats)
}
