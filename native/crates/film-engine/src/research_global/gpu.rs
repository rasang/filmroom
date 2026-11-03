//! Existing isolated Vulkan runner, bounded uploads and whole-stage publication.
use super::full_stage::{io, regular};
use super::*;
use crate::research_spatial::gpu::{params, RunMetrics, Runner};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
    time::Instant,
};
fn floats(bytes: &[u8]) -> Vec<f32> {
    bytes
        .chunks_exact(4)
        .map(|x| f32::from_le_bytes(x.try_into().unwrap()))
        .collect()
}
fn read64(f: &mut File, count: usize) -> R<Vec<f32>> {
    let mut b = vec![0u8; count * 8];
    io(f.read_exact(&mut b))?;
    b.chunks_exact(8)
        .map(|x| {
            let v = f64::from_le_bytes(x.try_into().unwrap());
            if finite(v) {
                Ok(v as f32)
            } else {
                Err("NONFINITE_GLOBAL_INPUT".into())
            }
        })
        .collect()
}
fn reject_numeric_error(r: &Runner, c: &mut impl FnMut() -> bool, m: &mut RunMetrics) -> R<()> {
    if r.read_error_flag(c, m)? != 0 {
        return Err("GPU_GLOBAL_NUMERIC_CHECK".into());
    }
    Ok(())
}
fn guard(expected: f64, actual: f32, m: &mut RunMetrics) -> R<()> {
    let delta = (expected - actual as f64).abs();
    if !expected.is_finite()
        || !actual.is_finite()
        || delta > NUMERIC_TOLERANCE * expected.abs().max(1.)
    {
        return Err("GPU_GLOBAL_NUMERIC_CHECK".into());
    }
    m.sample_count += 1;
    m.sample_max_abs = m.sample_max_abs.max(delta);
    m.sample_sum += delta * delta;
    Ok(())
}
pub(super) fn run_files(
    p: &GlobalPlan,
    source: &Path,
    output: &Path,
    c: &mut impl FnMut() -> bool,
) -> R<serde_json::Value> {
    run_files_inner(p, source, output, c, false).map_err(|s| s.replace("OPTICS", "GLOBAL"))
}
fn run_files_inner(
    p: &GlobalPlan,
    source: &Path,
    output: &Path,
    c: &mut impl FnMut() -> bool,
    allow_probe: bool,
) -> R<serde_json::Value> {
    check(c)?;
    p.validate()?;
    let at = Instant::now();
    let (r, mut m) = Runner::new_with_shader_error(&p.spatial_plan()?, allow_probe, SHADER)?;
    m.sample_tolerance_absolute = NUMERIC_TOLERANCE;
    m.sample_tolerance_relative = NUMERIC_TOLERANCE;
    let n = p.width as u64 * p.height as u64;
    let mut report = serde_json::json!({"gpuUsed":true,"backend":"Vulkan f32 original global science","fullImage":true,"combinedPeakBytes":p.combined_peak_bytes,"hostWork":"file IO, finite validation, original nearest-grid addresses and fixed geometry weights, bounded numeric witnesses; no host EV, image reduction, boost or log output"});
    if let GlobalKind::Meter { method, rgb_to_xyz } = &p.kind {
        regular(
            &source.join("source.meter.f32le"),
            p.sample_width as u64 * p.sample_height as u64 * 28,
        )?;
        let (sw, sh, pack) = read_meter_packet(&source.join("source.meter.f32le"), p, c)?;
        let count = sw * sh;
        let padded = count.next_power_of_two();
        let blocks = count.div_ceil(256);
        if count == 0 || count > 65536 || PACK_BASE * 4 + pack.len() * 4 > TRANSFER_BYTES as usize {
            return Err("GPU_GLOBAL_MEMORY_LIMIT".into());
        }
        r.upload_aux(PACK_BASE as u64 * 4, bytemuck::cast_slice(&pack), c, &mut m)?;
        r.upload_aux(32 * 4, bytemuck::cast_slice(&matrix_weights()), c, &mut m)?;
        let base = |op: u32| {
            let mut v = params(sw, sh, op);
            v[8] = count;
            v[9] = method.code();
            v[12] = rgb_to_xyz[1][0].to_bits();
            v[13] = rgb_to_xyz[1][1].to_bits();
            v[14] = rgb_to_xyz[1][2].to_bits();
            v
        };
        let mut v = base(0);
        v[4] = padded;
        r.dispatch(v, c, &mut m)?;
        let samples = count.min(8);
        let mut v = base(4);
        v[4] = samples;
        v[5] = count;
        r.dispatch(v, c, &mut m)?;
        let actual = floats(&r.read_probe(samples as u64 * 4, c, &mut m)?);
        let guard_at = Instant::now();
        for i in 0..samples as usize {
            let index = if samples == 1 {
                0
            } else {
                i * (count as usize - 1) / (samples as usize - 1)
            };
            let b = index * METER_STRIDE;
            let y = (rgb_to_xyz[1][0] * pack[b] + rgb_to_xyz[1][1] * pack[b + 1])
                + rgb_to_xyz[1][2] * pack[b + 2];
            guard(y as f64, actual[i], &mut m)?;
        }
        m.guard_ms += guard_at.elapsed().as_secs_f64() * 1000.;
        drop(pack);
        let mut v = base(2);
        v[4] = blocks;
        r.dispatch(v, c, &mut m)?;
        if *method == MeterMethod::Median {
            let mut k = 2;
            while k <= padded {
                let mut j = k / 2;
                while j > 0 {
                    check(c)?;
                    let mut v = base(1);
                    v[4] = padded;
                    v[5] = j;
                    v[6] = k;
                    r.dispatch(v, c, &mut m)?;
                    j /= 2;
                }
                k *= 2;
            }
        }
        let mut v = base(3);
        v[4] = 1;
        v[5] = blocks;
        r.dispatch(v, c, &mut m)?;
        reject_numeric_error(&r, c, &mut m)?;
        let bytes = r.read_probe(8, c, &mut m)?;
        let result = floats(&bytes);
        if result.len() != 2 || result.iter().any(|x| !x.is_finite()) || result[1] <= 0. {
            return Err("NONFINITE_GLOBAL_OUTPUT".into());
        }
        let mut f = io(OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(output.join("meter.f32le")))?;
        io(f.write_all(&bytes))?;
        io(f.sync_all())?;
        report["meteredEv"] = result[0].into();
        report["autoExposureScale"] = result[1].into();
        report["sampleWidth"] = sw.into();
        report["sampleHeight"] = sh.into();
        report["meterMethod"] = method.name().into();
        report["separateF32AutoExposureScale"] = true.into();
        m.sample_scope="bounded luminance witnesses; full GPU statistics and median validated by host test oracle";
    } else {
        for ch in 0..3 {
            let path = source.join(format!("source-{ch}.f64le"));
            regular(&path, n * 8)?;
            let mut f = io(File::open(path))?;
            let mut offset = 0;
            while offset < n {
                check(c)?;
                let count = (n - offset).min(CHUNK as u64) as usize;
                let values = read64(&mut f, count)?;
                r.upload_aux(
                    PACK_BASE as u64 * 4,
                    bytemuck::cast_slice(&values),
                    c,
                    &mut m,
                )?;
                let mut v = params(p.width, p.height, 10);
                v[4] = count as u32;
                v[6] = ch as u32;
                v[7] = offset as u32;
                r.dispatch(v, c, &mut m)?;
                offset += count as u64;
            }
        }
        let mut peak = None;
        match p.kind {
            GlobalKind::Highlight {
                boost_ev,
                boost_range,
                protect_ev,
            } => {
                let blocks = (n as u32).div_ceil(1024);
                let mut v = params(p.width, p.height, 11);
                v[4] = blocks;
                v[8] = n as u32;
                r.dispatch(v, c, &mut m)?;
                let mut v = params(p.width, p.height, 12);
                v[4] = 1;
                v[5] = blocks;
                v[12] = boost_ev.to_bits();
                v[13] = boost_range.to_bits();
                v[14] = protect_ev.to_bits();
                r.dispatch(v, c, &mut m)?;
                reject_numeric_error(&r, c, &mut m)?;
                let coefficients = floats(&r.read_probe(32, c, &mut m)?);
                if coefficients.iter().any(|x| !x.is_finite()) {
                    return Err("NONFINITE_GLOBAL_INTERMEDIATE".into());
                }
                peak = Some(coefficients[0]);
                report["highlightPeak"] = coefficients[0].into();
                // The maximum must obey the original curve too. This scalar GPU
                // witness catches cancellation near the protection threshold,
                // where an f32 zero denominator must not silently skip boosting.
                let mut witness = params(p.width, p.height, 16);
                witness[4] = 1;
                r.dispatch(witness, c, &mut m)?;
                reject_numeric_error(&r, c, &mut m)?;
                let peak_value = floats(&r.read_probe(36, c, &mut m)?)[8];
                guard(
                    boost_reference_value(
                        coefficients[0] as f64,
                        coefficients[0] as f64,
                        boost_ev as f64,
                        boost_range as f64,
                        protect_ev as f64,
                    ),
                    peak_value,
                    &mut m,
                )?;
                r.dispatch(params(p.width, p.height, 13), c, &mut m)?;
            }
            GlobalKind::LogExposure { correction } => {
                let mut v = params(p.width, p.height, 14);
                v[12] = (correction as f32).to_bits();
                r.dispatch(v, c, &mut m)?;
            }
            _ => unreachable!(),
        }
        reject_numeric_error(&r, c, &mut m)?;
        for ch in 0..3 {
            let mut out = io(OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(output.join(format!("plane-{ch}.f64le"))))?;
            let mut original = io(File::open(source.join(format!("source-{ch}.f64le"))))?;
            let mut offset = 0;
            while offset < n {
                check(c)?;
                let count = (n - offset).min(CHUNK as u64) as usize;
                let mut v = params(p.width, p.height, 15);
                v[4] = count as u32;
                v[6] = ch as u32;
                v[7] = offset as u32;
                r.dispatch(v, c, &mut m)?;
                let values = floats(&r.read_probe(count as u64 * 4, c, &mut m)?);
                if values.iter().any(|x| !x.is_finite()) {
                    return Err("NONFINITE_GLOBAL_OUTPUT".into());
                }
                let guard_at = Instant::now();
                for i in 0..count.min(8) {
                    let local = if count == 1 {
                        0
                    } else {
                        i * (count - 1) / (count.min(8) - 1)
                    };
                    io(original.seek(SeekFrom::Start((offset + local as u64) * 8)))?;
                    let mut b = [0u8; 8];
                    io(original.read_exact(&mut b))?;
                    let raw = f64::from_le_bytes(b);
                    let rounded = (raw as f32) as f64;
                    let expected = match p.kind {
                        GlobalKind::LogExposure { correction } => {
                            ((rounded * (correction as f32) as f64).max(0.) + 1e-10).log10()
                        }
                        GlobalKind::Highlight {
                            boost_ev,
                            boost_range,
                            protect_ev,
                        } => boost_reference_value(
                            rounded,
                            peak.unwrap() as f64,
                            boost_ev as f64,
                            boost_range as f64,
                            protect_ev as f64,
                        ),
                        _ => unreachable!(),
                    };
                    guard(expected, values[local], &mut m)?;
                }
                m.guard_ms += guard_at.elapsed().as_secs_f64() * 1000.;
                let bytes: Vec<_> = values
                    .iter()
                    .flat_map(|&v| (v as f64).to_le_bytes())
                    .collect();
                io(out.write_all(&bytes))?;
                offset += count as u64;
            }
            io(out.sync_all())?;
        }
        m.sample_scope="bounded f64 point witnesses on GPU f32 inputs and global peak; all output scalars finite validated";
    }
    reject_numeric_error(&r, c, &mut m)?;
    check(c)?;
    m.elapsed_ms = at.elapsed().as_secs_f64() * 1000.;
    m.sample_rms = (m.sample_sum / m.sample_count.max(1) as f64).sqrt();
    let metrics = serde_json::to_value(&m).map_err(|_| "GPU_GLOBAL_REPORT")?;
    for (k, v) in metrics.as_object().ok_or("GPU_GLOBAL_REPORT")? {
        report[k] = v.clone();
    }
    Ok(report)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "Requires an explicit Vulkan device; integer error transport gate"]
    fn actual_vulkan_unsampled_overflow_rejects_entire_global_candidate() {
        let root = super::super::tests::root();
        let (w, h) = (257, 1);
        let witness_indices: Vec<_> = (0..8).map(|i| i * 256 / 7).collect();
        assert!(!witness_indices.contains(&17));
        // This image is <=256 along the longest sampled axis after reduction.
        // Use 251 pixels for the AE case so index 17 is a real, unwitnessed
        // luminance whose cube exceeds admission while remaining finite f32.
        let p = GlobalPlan::meter(
            251,
            1,
            "highlight_weighted",
            [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]],
        )
        .unwrap();
        let input = root.join("meter-overflow");
        let mut rgb = vec![0.25f32; 251 * 3];
        rgb[17 * 3 + 1] = 1e6;
        assert!(!(0..8).map(|i| i * 250 / 7).any(|i| i == 17));
        std::fs::write(&input, bytemuck::cast_slice(&rgb)).unwrap();
        let dir = root.join("gpu-stage-4001");
        prepare_meter_stage(&input, &dir, 4001, &p, || false).unwrap();
        let candidate = dir.join("candidate");
        std::fs::create_dir(&candidate).unwrap();
        assert_eq!(
            run_files_inner(&p, &dir, &candidate, &mut || false, true).unwrap_err(),
            "GPU_GLOBAL_NUMERIC_CHECK"
        );
        assert_eq!(std::fs::read_dir(&candidate).unwrap().count(), 0);
        for (index, p, spike) in [
            (0, GlobalPlan::log_exposure(w, h, 1e12).unwrap(), 1e12f64),
            (
                1,
                GlobalPlan::highlight(w, h, 8., 0.3, 1.).unwrap(),
                1e14f64,
            ),
        ] {
            let token = 4100 + index;
            let sources: [std::path::PathBuf; 3] = std::array::from_fn(|ch| {
                let path = root.join(format!("unsafe-{index}-{ch}"));
                let mut values = vec![0.25f64; w as usize];
                if ch == 2 {
                    values[17] = spike;
                }
                std::fs::write(
                    &path,
                    values
                        .iter()
                        .flat_map(|v| v.to_le_bytes())
                        .collect::<Vec<_>>(),
                )
                .unwrap();
                path
            });
            let dir = root.join(format!("gpu-stage-{token}"));
            prepare_plane_stage(
                std::array::from_fn(|ch| sources[ch].as_path()),
                &dir,
                token,
                &p,
                || false,
            )
            .unwrap();
            let candidate = dir.join("candidate");
            std::fs::create_dir(&candidate).unwrap();
            assert_eq!(
                run_files_inner(&p, &dir, &candidate, &mut || false, true).unwrap_err(),
                "GPU_GLOBAL_NUMERIC_CHECK"
            );
            assert_eq!(std::fs::read_dir(&candidate).unwrap().count(), 0);
        }
        // A second safe dispatch must not erase a failure from an earlier one.
        let p = GlobalPlan::log_exposure(1, 1, 1.).unwrap();
        let (r, mut m) =
            Runner::new_with_shader_error(&p.spatial_plan().unwrap(), true, SHADER).unwrap();
        for value in [1e20f32, 0.25] {
            r.upload_aux(
                PACK_BASE as u64 * 4,
                bytemuck::bytes_of(&value),
                &mut || false,
                &mut m,
            )
            .unwrap();
            let mut v = params(1, 1, 10);
            v[4] = 1;
            r.dispatch(v, &mut || false, &mut m).unwrap();
            assert_ne!(r.read_error_flag(&mut || false, &mut m).unwrap(), 0);
        }
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[ignore = "Requires an explicit Vulkan device; not mobile performance evidence"]
    fn actual_vulkan_global_science_matches_cpu_oracles() {
        let root = super::super::tests::root();
        for (index, (w, h)) in [(1, 1), (3, 2), (17, 13), (307, 181)]
            .into_iter()
            .enumerate()
        {
            let input = super::super::tests::rgb(&root, w, h);
            for (method_index, method) in super::super::tests::METHODS.iter().enumerate() {
                let p = GlobalPlan::meter(w, h, method, spektrafilm_math::colorspace::SRGB_TO_XYZ)
                    .unwrap();
                let dir = root.join(format!("gpu-stage-{}", 1000 + 100 * index + method_index));
                let plan = prepare_meter_stage(
                    &input,
                    &dir,
                    (1000 + 100 * index + method_index) as u64,
                    &p,
                    || false,
                )
                .unwrap();
                let candidate = dir.join("candidate");
                std::fs::create_dir(&candidate).unwrap();
                let report = run_files_inner(&p, &dir, &candidate, &mut || false, true).unwrap();
                let (sw, sh, pack) = read_meter_pack(&input, &p, &mut || false).unwrap();
                let (ev, scale) = meter_mirror(&p, &pack, sw, sh, &mut || false).unwrap();
                assert!((report["meteredEv"].as_f64().unwrap() - ev as f64).abs() < 2e-3);
                assert!(
                    (report["autoExposureScale"].as_f64().unwrap() - scale as f64).abs()
                        < 2e-3 * (scale as f64).max(1.)
                );
                assert!(plan.combined_peak_bytes < crate::research_spatial::MEMORY_CAP);
            }
        }
        for (index, kind) in [
            GlobalKind::Highlight {
                boost_ev: 2.,
                boost_range: 0.3,
                protect_ev: 1.,
            },
            GlobalKind::LogExposure { correction: 0.73 },
        ]
        .into_iter()
        .enumerate()
        {
            let p = GlobalPlan::new(17, 13, kind).unwrap();
            let paths = super::super::tests::planes(&root, 17, 13);
            let source = std::array::from_fn(|ch| paths[ch].as_path());
            let dir = root.join(format!("gpu-stage-{}", 2000 + index));
            prepare_plane_stage(source, &dir, (2000 + index) as u64, &p, || false).unwrap();
            let candidate = dir.join("candidate");
            std::fs::create_dir(&candidate).unwrap();
            run_files_inner(&p, &dir, &candidate, &mut || false, true).unwrap();
            let input = super::super::tests::read_planes(&paths);
            let expected = planes_mirror(&p, &input, &mut || false).unwrap();
            for ch in 0..3 {
                let actual = std::fs::read(candidate.join(format!("plane-{ch}.f64le"))).unwrap();
                for (b, want) in actual.chunks_exact(8).zip(&expected[ch]) {
                    let value = f64::from_le_bytes(b.try_into().unwrap());
                    assert!((value - want).abs() < 2e-3 * want.abs().max(1.));
                }
            }
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}
