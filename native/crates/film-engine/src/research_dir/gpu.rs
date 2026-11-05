//! Single isolated helper, one full correction plane at a time. The original
//! initial-density curves are reused per receiver rather than storing RGB images.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase};
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
pub(crate) struct Tables {
    initial: [u32; 4],
    before: [u32; 4],
}
pub(crate) fn pack_tables(p: &DirPlan) -> (Vec<u8>, Tables) {
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
pub(crate) fn base(p: &DirPlan, t: Tables, ch: usize, offset: u64, count: usize, op: u32) -> [u32; 40] {
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
pub(crate) fn sample(
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
pub(crate) fn guard(reference: impl Iterator<Item = f64>, actual: &[f32], m: &mut RunMetrics) -> R<()> {
        trace::phase_result(GpuPhase::Guard, || {
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

        })
    }
pub(crate) fn read64(f: &mut File, n: usize) -> R<Vec<f64>> {
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
/// Compound-only entry. Ordinary DIR uses the same request-selected DIR families.
pub(crate) fn run_files_with_log_prefix(
    p: &DirPlan,
    correction: f64,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
) -> R<serde_json::Value> {
    crate::research_log_dir::log_prefix::validate_correction(p.width, p.height, correction)?;
    run_files_inner_with_prefix(p, sources, outputs, c, false, Some(correction))
}
fn run_files_inner(
    p: &DirPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
    allow_probe: bool,
) -> R<serde_json::Value> {
    run_files_inner_with_prefix(p, sources, outputs, c, allow_probe, None)
}
fn run_files_inner_with_prefix(
    p: &DirPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
    allow_probe: bool,
    log_correction: Option<f64>,
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
    let mut families = crate::research_spatial::families::shaders_for_dir(
        p.dir_active, p.core_sigma, p.tail_decay,
    )?;
    if log_correction.is_some() {
        families.push((vec![16, 17], crate::research_log_dir::log_prefix::shader()?));
    }
    let (r, mut m) = Runner::new_with_fixed_iir_families(
        &p.spatial_plan()?, allow_probe, &families,
    )?;
    let mut prefix_metrics = crate::research_log_dir::log_prefix::Metrics::default();
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
            let mut logs: [Vec<f64>; 3] = [
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
            if let Some(correction) = log_correction {
                let actual = crate::research_log_dir::log_prefix::apply(
                    &r, correction, &[&logs[0], &logs[1], &logs[2]], c, &mut m, &mut prefix_metrics,
                )?;
                // Only bounded witness slots are changed on the host. All image
                // arithmetic and the following DIR consumer remain on-device.
                for (sample, i) in sample_indices(count).into_iter().enumerate() {
                    for lane in 0..3 { logs[lane][i] = actual[3 * sample + lane] as f64; }
                }
            }
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
                let mut logs = read64(&mut original, count)?;
                let upload: Vec<u8> = logs
                    .iter()
                    .flat_map(|&x| (x as f32).to_le_bytes())
                    .collect();
                m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
                r.upload_aux(CHUNK_BASE as u64 * 4, &upload, c, &mut m)?;
                if let Some(factor) = log_correction {
                    let actual = crate::research_log_dir::log_prefix::apply(
                        &r, factor, &[&logs], c, &mut m, &mut prefix_metrics,
                    )?;
                    for (sample, i) in sample_indices(count).into_iter().enumerate() {
                        logs[i] = actual[sample] as f64;
                    }
                }
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
    if log_correction.is_some() {
        object.insert("logSampleCount".into(), prefix_metrics.count.into());
        object.insert("logSampleMaxAbs".into(), prefix_metrics.max.into());
        object.insert("logSampleRms".into(), (prefix_metrics.sum / prefix_metrics.count.max(1) as f64).sqrt().into());
    }
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

#[cfg(test)]
mod log_prefix_gpu_tests {
    use super::*;
    use std::{fs, path::PathBuf, sync::atomic::{AtomicU64, Ordering}};
    static NEXT: AtomicU64 = AtomicU64::new(1);
    struct Root(PathBuf);
    impl Root {
        fn new() -> Self {
            let p=std::env::temp_dir().join(format!("log-dir-vulkan-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
            fs::create_dir(&p).unwrap();Self(p.canonicalize().unwrap())
        }
    }
    impl Drop for Root { fn drop(&mut self) {let _=fs::remove_dir_all(&self.0);} }
    fn config(w:u32,h:u32,active:bool)->DirPlan {
        let curve=Curve::new(vec![-12.,-3.,-1.,0.,2.],vec![[0.;3],[0.2,0.3,0.4],[0.8,0.9,1.],[1.4,1.5,1.6],[2.;3]],1.).unwrap();
        DirPlan {width:w,height:h,dir_active:active,positive:false,core_sigma:if active{0.7}else{0.},tail_decay:0.5,tail_weight:0.3,
            matrix:[[0.09,0.03,0.02],[0.07,0.06,0.01],[0.04,0.05,0.08]],density_max:[2.;3],initial:curve.clone(),before_dir:curve}
    }
    fn inputs(root:&Root,p:&DirPlan,bad:bool)->[PathBuf;3] {
        let paths=std::array::from_fn(|ch|root.0.join(format!("source-{ch}.f64le")));
        for ch in 0..3 {
            let values:Vec<f64>=(0..p.width*p.height).map(|i| {
                if bad&&i==1 {1e16} else if i%17==0 {1e12} else if i%13==0 {-0.3} else if i%11==0 {0.} else if i%7==0 {1.0000000596046448} else {(i as f64+ch as f64*0.37)/67.}
            }).collect();
            fs::write(&paths[ch],values.iter().flat_map(|x|x.to_le_bytes()).collect::<Vec<_>>()).unwrap();
        }paths
    }
    #[test]
    #[ignore="Explicit Vulkan including software; all-output correctness only, never mobile performance evidence"]
    fn actual_vulkan_compound_log_dir_matches_separate_gpu_stages_all_scalars() {
        for (w,h,active,correction) in [(17,11,false,0.731923),(17,11,true,3.7),(257,257,false,1e-6)] {
            let root=Root::new();let p=config(w,h,active);let source=inputs(&root,&p,false);
            let original:Vec<_>=source.iter().map(|p|fs::read(p).unwrap()).collect();
            let log_out=root.0.join("log-output");fs::create_dir(&log_out).unwrap();
            let log=crate::research_global::GlobalPlan::log_exposure(w,h,correction).unwrap();
            let lm=crate::research_global::run_probe_for_test(&log,&root.0,&log_out,&mut ||false).unwrap();
            let logged:[PathBuf;3]=std::array::from_fn(|ch|log_out.join(format!("plane-{ch}.f64le")));
            let separate:[PathBuf;3]=std::array::from_fn(|ch|root.0.join(format!("separate-{ch}")));
            let dm=run_files_inner(&p,logged.each_ref().map(|p|p.as_path()),separate.each_ref().map(|p|p.as_path()),&mut ||false,true).unwrap();
            let compound:[PathBuf;3]=std::array::from_fn(|ch|root.0.join(format!("compound-{ch}")));
            let cm=run_files_inner_with_prefix(&p,source.each_ref().map(|p|p.as_path()),compound.each_ref().map(|p|p.as_path()),&mut ||false,true,Some(correction)).unwrap();
            assert_eq!(lm["adapter"],dm["adapter"]);assert_eq!(dm["adapter"],cm["adapter"]);
            assert_eq!(lm["vendor"],dm["vendor"]);assert_eq!(dm["vendor"],cm["vendor"]);
            let n=w as u64*h as u64;let chunks=p.chunk_count();let samples=8*(chunks-1)+(n-(chunks-1)*p.chunk_pixels() as u64).min(8);
            assert_eq!(cm["logSampleCount"].as_u64(),Some((9+if active{3}else{0})*samples));
            let mut max=0f64;let mut square=0f64;let mut count=0u64;
            for ch in 0..3 {
                let got=fs::read(&compound[ch]).unwrap();let want=fs::read(&separate[ch]).unwrap();assert_eq!(got.len(),want.len());
                for(a,b)in got.chunks_exact(8).zip(want.chunks_exact(8)) {
                    let a=f64::from_le_bytes(a.try_into().unwrap());let b=f64::from_le_bytes(b.try_into().unwrap());let e=(a-b).abs();
                    assert!(a.is_finite()&&b.is_finite()&&e<=POINT_TOLERANCE*b.abs().max(1.),"scalar {count}: compound={a} separate={b}");
                    max=max.max(e);square+=e*e;count+=1;
                }
                assert_eq!(fs::read(&source[ch]).unwrap(),original[ch]);
            }
            assert_eq!(count,n*3);
            eprintln!("SOFTWARE_CAPABLE_VULKAN_CORRECTNESS_ONLY compound={cm}; separate_log={lm}; separate_dir={dm}; all_scalars={count}; max_abs={max}; rms={}",(square/count as f64).sqrt());
        }
    }
    #[test]
    #[ignore="Explicit Vulkan including software; verifies unsampled prefix error and cancelled output cleanup"]
    fn actual_vulkan_compound_prefix_unsampled_failure_and_cancel_publish_nothing() {
        let root=Root::new();let p=config(64,1,false);assert!(!sample_indices(64).contains(&1));
        let source=inputs(&root,&p,true);
        let outputs:[PathBuf;3]=std::array::from_fn(|ch|root.0.join(format!("invalid-{ch}")));
        let result=run_files_inner_with_prefix(&p,source.each_ref().map(|p|p.as_path()),outputs.each_ref().map(|p|p.as_path()),&mut ||false,true,Some(0.73));
        assert_eq!(result.unwrap_err(),"GPU_DIR_NUMERIC_DOMAIN");assert!(outputs.iter().all(|p|!p.exists()));
        let source=inputs(&root,&p,false);
        let outputs:[PathBuf;3]=std::array::from_fn(|ch|root.0.join(format!("cancelled-{ch}")));
        let mut observed_output=false;
        let mut cancel=||{if outputs[0].exists(){observed_output=true;true}else{false}};
        let result=run_files_inner_with_prefix(&p,source.each_ref().map(|p|p.as_path()),outputs.each_ref().map(|p|p.as_path()),&mut cancel,true,Some(0.73));
        assert_eq!(result.unwrap_err(),"CANCELLED");assert!(observed_output);assert!(outputs.iter().all(|p|!p.exists()));
    }
}
