//! Single isolated helper, one full correction plane at a time. The original
//! initial-density curves are reused per receiver rather than storing RGB images.
use super::*;
use crate::research_spatial::{
    check,
    gpu::{RunMetrics, Runner, params},
};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    path::Path,
    time::Instant,
};
#[derive(Clone, Copy)]
struct Tables {
    initial: [u32; 4],
    before: [u32; 4],
}
fn pack_tables(p: &DirPlan) -> (Vec<u8>, Tables) {
    let mut data = Vec::<f32>::new();
    let mut pack = |c: &Curve| {
        let base = TABLE_BASE + data.len() as u32;
        let n = c.axis.len() as u32;
        data.extend(c.axis.iter().map(|&x| x as f32));
        data.extend(c.inverse_dx.iter().map(|&x| x as f32));
        data.push(0.);
        data.extend(c.values.iter().flatten().map(|&x| x as f32));
        [n, base, base + n, base + 2 * n]
    };
    let initial = pack(&p.initial);
    let before = pack(&p.before_dir);
    (
        data.iter().flat_map(|x| x.to_le_bytes()).collect(),
        Tables { initial, before },
    )
}
fn base(p: &DirPlan, t: Tables, ch: usize, offset: u64, count: usize, op: u32) -> [u32; 40] {
    let mut v = params(p.width, p.height, op);
    v[4] = count as u32;
    v[6] = ch as u32;
    v[7] = offset as u32;
    v[8..12].copy_from_slice(&t.initial);
    for i in 0..3 {
        v[12 + i] = (p.density_max[i] as f32).to_bits();
        v[16 + i] = (p.matrix[i][ch] as f32).to_bits();
    }
    v[15] = u32::from(p.positive);
    v[20] = t.before[1];
    v[21] = t.before[2];
    v[22] = u32::from(p.dir_active);
    v[23] = t.before[3];
    v[24] = t.before[0];
    v
}
fn sample(
    r: &Runner,
    p: &DirPlan,
    offset: u64,
    n: usize,
    c: &mut impl FnMut() -> bool,
    m: &mut RunMetrics,
) -> R<Vec<f32>> {
    let mut v = params(p.width, p.height, 15);
    v[4] = n.min(8) as u32;
    v[5] = n as u32;
    v[7] = offset as u32;
    r.dispatch(v, c, m)?;
    let raw = r.read_probe(n.min(8) as u64 * 4, c, m)?;
    Ok(raw
        .chunks_exact(4)
        .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
        .collect())
}
fn guard(reference: impl Iterator<Item = f64>, actual: &[f32], m: &mut RunMetrics) -> R<()> {
    let at = Instant::now();
    for (r, &a) in reference.zip(actual) {
        let e = (r - a as f64).abs();
        if !r.is_finite() || !a.is_finite() || e > POINT_TOLERANCE * r.abs().max(1.) {
            return Err("GPU_DIR_NUMERIC_CHECK".into());
        }
        m.sample_count += 1;
        m.sample_max_abs = m.sample_max_abs.max(e);
        m.sample_sum += e * e;
    }
    m.guard_ms += at.elapsed().as_secs_f64() * 1000.;
    Ok(())
}
fn read64(f: &mut File, n: usize) -> R<Vec<f64>> {
    let mut b = vec![0u8; n * 8];
    f.read_exact(&mut b).map_err(|_| "GPU_DIR_IO")?;
    let v: Vec<f64> = b
        .chunks_exact(8)
        .map(|b| f64::from_le_bytes(b.try_into().unwrap()))
        .collect();
    if v.iter().any(|&x| !finite(x)) {
        return Err("NONFINITE_DIR_INPUT".into());
    }
    Ok(v)
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
pub fn run_files(
    p: &DirPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
) -> R<serde_json::Value> {
    run_files_inner(p, sources, outputs, c, false).map_err(|code| code.replace("OPTICS", "DIR"))
}
fn run_files_inner(
    p: &DirPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
    allow_probe: bool,
) -> R<serde_json::Value> {
    let start = Instant::now();
    p.numerical_preflight(c)?;
    let preflight = start.elapsed().as_secs_f64() * 1000.;
    let n = p.width as u64 * p.height as u64;
    let chunk = p.chunk_pixels() as usize;
    for i in 0..3 {
        if sources.contains(&outputs[i]) || outputs[..i].contains(&outputs[i]) {
            return Err("INVALID_DIR_FILE".into());
        }
        let meta = std::fs::symlink_metadata(sources[i]).map_err(|_| "GPU_DIR_IO")?;
        if !meta.is_file() || meta.file_type().is_symlink() || meta.len() != n * 8 {
            return Err("INVALID_DIR_FILE".into());
        }
    }
    let (r, mut m) = Runner::new(&p.spatial_plan()?, allow_probe)?;
    m.setup_ms += preflight;
    m.sample_scope = "per_axis_and_chunk_f64_on_gpu_f32_input";
    let (tables, layout) = pack_tables(p);
    r.upload_aux(TABLE_BASE as u64 * 4, &tables, c, &mut m)?;
    drop(tables);
    let mut ownership = Outputs {
        paths: outputs,
        created: 0,
        keep: false,
    };
    for ch in 0..3 {
        check(c)?;
        let mut input: Vec<File> = sources
            .iter()
            .map(|p| File::open(p).map_err(|_| "GPU_DIR_IO".to_string()))
            .collect::<R<_>>()?;
        let mut offset = 0;
        while offset < n {
            check(c)?;
            let at = Instant::now();
            let count = (n - offset).min(chunk as u64) as usize;
            let logs: [Vec<f64>; 3] = [
                read64(&mut input[0], count)?,
                read64(&mut input[1], count)?,
                read64(&mut input[2], count)?,
            ];
            let mut upload = Vec::with_capacity(count * 12);
            for i in 0..count {
                for values in &logs {
                    upload.extend_from_slice(&(values[i] as f32).to_le_bytes());
                }
            }
            m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
            r.upload_aux(CHUNK_BASE as u64 * 4, &upload, c, &mut m)?;
            r.dispatch(base(p, layout, ch, offset, count, 12), c, &mut m)?;
            let actual = sample(&r, p, offset, count, c, &mut m)?;
            guard(
                sample_indices(count)
                    .into_iter()
                    .map(|i| p.initial_f64(ch, [logs[0][i], logs[1][i], logs[2][i]])),
                &actual,
                &mut m,
            )?;
            offset += count as u64;
        }
        drop(input);
        if p.dir_active && p.core_sigma > 0. {
            r.blur(p.width, p.height, p.core_sigma, c, &mut m)?;
            let mut v = params(p.width, p.height, 4);
            v[6] = 2;
            v[7] = 3;
            r.dispatch(v, c, &mut m)?;
            r.dispatch(params(p.width, p.height, 5), c, &mut m)?;
            for (amp, ratio) in FIT {
                r.blur(
                    p.width,
                    p.height,
                    (ratio * p.tail_decay as f64) as f32,
                    c,
                    &mut m,
                )?;
                let mut v = params(p.width, p.height, 6);
                v[12] = (amp as f32).to_bits();
                r.dispatch(v, c, &mut m)?;
            }
            let mut v = params(p.width, p.height, 13);
            v[13] = (p.tail_weight as f32).to_bits();
            r.dispatch(v, c, &mut m)?;
        }
        if p.dir_active {
            let mut original = File::open(sources[ch]).map_err(|_| "GPU_DIR_IO")?;
            let mut offset = 0;
            while offset < n {
                check(c)?;
                let at = Instant::now();
                let count = (n - offset).min(chunk as u64) as usize;
                let logs = read64(&mut original, count)?;
                let upload: Vec<u8> = logs
                    .iter()
                    .flat_map(|&x| (x as f32).to_le_bytes())
                    .collect();
                m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
                r.upload_aux(CHUNK_BASE as u64 * 4, &upload, c, &mut m)?;
                let correction = sample(&r, p, offset, count, c, &mut m)?;
                r.dispatch(base(p, layout, ch, offset, count, 14), c, &mut m)?;
                let actual = sample(&r, p, offset, count, c, &mut m)?;
                guard(
                    sample_indices(count)
                        .into_iter()
                        .zip(&correction)
                        .map(|(i, &corr)| p.before_dir.at_f64(ch, logs[i] - corr as f64)),
                    &actual,
                    &mut m,
                )?;
                offset += count as u64;
            }
        }
        if r.read_error_flag(c, &mut m)? != 0 {
            return Err("GPU_DIR_NUMERIC_DOMAIN".into());
        }
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(outputs[ch])
            .map_err(|_| "GPU_DIR_IO")?;
        ownership.created += 1;
        let mut offset = 0;
        while offset < n {
            check(c)?;
            let count = (n - offset).min(CHUNK_PIXELS as u64);
            let bytes = r.read_source(offset * 4, count * 4, c, &mut m)?;
            let mut encoded = Vec::with_capacity(count as usize * 8);
            for b in bytes.chunks_exact(4) {
                let x = f32::from_le_bytes(b.try_into().unwrap());
                if !x.is_finite() {
                    return Err("NONFINITE_DIR_OUTPUT".into());
                }
                encoded.extend_from_slice(&(x as f64).to_le_bytes());
            }
            output.write_all(&encoded).map_err(|_| "GPU_DIR_IO")?;
            offset += count;
        }
        output.sync_all().map_err(|_| "GPU_DIR_IO")?;
    }
    check(c)?;
    if r.read_error_flag(c, &mut m)? != 0 {
        return Err("GPU_DIR_NUMERIC_DOMAIN".into());
    }
    m.sample_rms = (m.sample_sum / m.sample_count.max(1) as f64).sqrt();
    m.elapsed_ms = start.elapsed().as_secs_f64() * 1000.;
    let mut value = serde_json::to_value(m).map_err(|_| "INVALID_DIR_RESULT")?;
    let object = value.as_object_mut().ok_or("INVALID_DIR_RESULT")?;
    object.insert("dirActive".into(), serde_json::json!(p.dir_active));
    object.insert("chunkCount".into(), serde_json::json!(p.chunk_count()));
    object.insert(
        "pointwiseToleranceAbsolute".into(),
        serde_json::json!(POINT_TOLERANCE),
    );
    object.insert(
        "pointwiseToleranceRelative".into(),
        serde_json::json!(POINT_TOLERANCE),
    );
    ownership.keep = true;
    Ok(value)
}

#[cfg(test)]
mod numeric_tests {
    use super::*;
    #[test]
    #[ignore = "explicit Vulkan probe; not mobile performance evidence"]
    fn actual_vulkan_unsampled_dir_overflow_has_no_output() {
        let root = std::env::temp_dir().join(format!("dir-domain-{}", std::process::id()));
        std::fs::create_dir(&root).unwrap();
        let curve =
            Curve::new(vec![-1., 0., 1.], vec![[0.; 3], [-3e38; 3], [3e38; 3]], 1.).unwrap();
        let p = DirPlan {
            width: 64,
            height: 1,
            dir_active: false,
            positive: false,
            core_sigma: 0.,
            tail_decay: 0.,
            tail_weight: 0.,
            matrix: [[0.; 3]; 3],
            density_max: [1.; 3],
            initial: curve.clone(),
            before_dir: curve,
        };
        let inputs: [std::path::PathBuf; 3] =
            std::array::from_fn(|ch| root.join(format!("in{ch}")));
        let outputs: [std::path::PathBuf; 3] =
            std::array::from_fn(|ch| root.join(format!("out{ch}")));
        let mut values = vec![-2f64; 64];
        values[1] = 0.5;
        assert!(!sample_indices(64).contains(&1));
        for path in &inputs {
            std::fs::write(
                path,
                values
                    .iter()
                    .flat_map(|v| v.to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
        }
        assert_eq!(
            run_files_inner(
                &p,
                inputs.each_ref().map(|p| p.as_path()),
                outputs.each_ref().map(|p| p.as_path()),
                &mut || false,
                true
            )
            .unwrap_err(),
            "GPU_DIR_NUMERIC_DOMAIN"
        );
        assert!(outputs.iter().all(|p| !p.exists()));
        std::fs::remove_dir_all(root).unwrap();
    }
}
