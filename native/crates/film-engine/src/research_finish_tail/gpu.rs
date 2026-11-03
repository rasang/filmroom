//! Only invoked in the existing isolated helper lifecycle. CPU handles file IO,
//! immutable calibration upload and bounded numeric witnesses, not image science.
use super::*;
use crate::research_spatial::gpu::{RunMetrics, Runner, params};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    path::Path,
    time::Instant,
};
fn io<T>(x: std::io::Result<T>) -> R<T> {
    x.map_err(|_| "GPU_TAIL_IO".into())
}
fn read64(f: &mut File, n: usize) -> R<Vec<f32>> {
    let mut b = vec![0u8; n * 8];
    io(f.read_exact(&mut b))?;
    b.chunks_exact(8)
        .map(|v| {
            let x = f64::from_le_bytes(v.try_into().unwrap());
            if finite(x) {
                Ok(x as f32)
            } else {
                Err("NONFINITE_TAIL_INPUT".into())
            }
        })
        .collect()
}
fn numbers(b: &[u8]) -> Vec<f32> {
    b.chunks_exact(4)
        .map(|x| f32::from_le_bytes(x.try_into().unwrap()))
        .collect()
}
fn upload(r: &Runner, v: &[f32], c: &mut impl FnMut() -> bool, m: &mut RunMetrics) -> R<()> {
    r.upload_aux(CHUNK_BASE as u64 * 4, bytemuck::cast_slice(v), c, m)
}
fn point_guard(expected: [f32; 3], actual: &[f32], m: &mut RunMetrics) -> R<()> {
    for (r, &a) in expected.into_iter().zip(actual) {
        let e = (r as f64 - a as f64).abs();
        if !r.is_finite() || !a.is_finite() || e > POINT_TOLERANCE * (r as f64).abs().max(1.) {
            return Err("GPU_TAIL_NUMERIC_CHECK".into());
        }
        m.sample_count += 1;
        m.sample_max_abs = m.sample_max_abs.max(e);
        m.sample_sum += e * e;
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
            let _ = std::fs::remove_file(self.path);
        }
    }
}
pub(super) fn run_files(
    p: &TailPlan,
    source: &Path,
    glare: Option<&Path>,
    output: &Path,
    c: &mut impl FnMut() -> bool,
) -> R<serde_json::Value> {
    run_files_inner(p, source, glare, output, c, false, None)
        .map_err(|s| s.replace("OPTICS", "TAIL"))
}
pub(crate) fn run_files_with_prefix(
    p: &TailPlan,
    output: &Path,
    c: &mut impl FnMut() -> bool,
    prefix: &mut crate::research_finish::fused::FinishPrefix,
) -> R<serde_json::Value> {
    run_files_inner(p, Path::new(""), None, output, c, false, Some(prefix))
}
#[cfg(test)]
pub(crate) fn run_files_with_prefix_probe(
    p: &TailPlan,
    output: &Path,
    c: &mut impl FnMut() -> bool,
    prefix: &mut crate::research_finish::fused::FinishPrefix,
) -> R<serde_json::Value> {
    run_files_inner(p, Path::new(""), None, output, c, true, Some(prefix))
}
fn run_files_inner(
    p: &TailPlan,
    source: &Path,
    glare: Option<&Path>,
    output: &Path,
    c: &mut impl FnMut() -> bool,
    allow_probe: bool,
    mut prefix: Option<&mut crate::research_finish::fused::FinishPrefix>,
) -> R<serde_json::Value> {
    let start = Instant::now();
    p.numerical_preflight(c)?;
    let n = p.width as u64 * p.height as u64;
    if source == output || glare == Some(output) || (glare.is_some() && p.options.glare.is_none()) {
        return Err("INVALID_TAIL_FILE".into());
    }
    if prefix.is_none() {
        full_stage::regular(source, n * 24)?;
    }
    if let Some(g) = glare {
        full_stage::regular(g, n * 8)?;
    }
    let source_shader = shader_checked(research_spatial::SHADER)?;
    let (r, mut m) = if let Some(prefix) = prefix.as_ref() {
        Runner::new_with_shader_error_tables(
            &p.spatial_plan()?,
            allow_probe,
            &prefix.shader(&source_shader)?,
            prefix.storage(),
        )?
    } else {
        Runner::new_with_shader_error(&p.spatial_plan()?, allow_probe, &source_shader)?
    };
    let mut prefix_samples = 0u64;
    if let Some(prefix) = prefix.as_ref() {
        m.upload_bytes += prefix.storage().len() as u64 * 4;
    }
    m.device_bytes = p.device_bytes;
    m.sample_scope = "full_line_f64_and_tail_f32_arithmetic_witnesses";
    let table = p.table32();
    r.upload_aux(
        TABLE_BASE as u64 * 4,
        bytemuck::cast_slice(&table),
        c,
        &mut m,
    )?;
    drop(table);
    let mut glare_random_words = 0u64;
    let mut glare_chunks = 0u64;
    if let Some(g) = glare {
        let mut f = io(File::open(g))?;
        let mut offset = 0;
        while offset < n {
            check(c)?;
            let count = (n - offset).min(CHUNK_PIXELS as u64) as usize;
            let values = read64(&mut f, count)?;
            if values.iter().any(|v| *v < 0.) {
                return Err("INVALID_TAIL_GLARE".into());
            }
            upload(&r, &values, c, &mut m)?;
            let mut v = params(p.width, p.height, 20);
            v[4] = count as u32;
            v[7] = offset as u32;
            r.dispatch(v, c, &mut m)?;
            offset += count as u64;
        }
    } else if p.options.glare.is_some() {
        (glare_random_words, glare_chunks) = generate_glare(&r, p, c, &mut m)?;
    }
    if let Some(g) = &p.options.glare {
        if g.blur > 0. {
            r.blur(p.width, p.height, g.blur, c, &mut m)?;
            let mut v = params(p.width, p.height, 4);
            v[6] = 2;
            v[7] = 0;
            r.dispatch(v, c, &mut m)?;
        }
    }
    let mut f = if prefix.is_none() {
        Some(io(File::open(source))?)
    } else {
        None
    };
    let mut offset = 0;
    while offset < n {
        check(c)?;
        let count = (n - offset).min(CHUNK_PIXELS as u64) as usize;
        let values = if let Some(prefix) = prefix.as_deref_mut() {
            prefix.read_chunk(count)?
        } else {
            read64(f.as_mut().ok_or("INVALID_TAIL_FILE")?, count * 3)?
        };
        let samples = count.min(8);
        let glare_witness = if p.options.glare.is_some() {
            let mut v = params(p.width, p.height, 15);
            v[4] = samples as u32;
            v[5] = count as u32;
            v[7] = offset as u32;
            r.dispatch(v, c, &mut m)?;
            numbers(&r.read_probe(samples as u64 * 4, c, &mut m)?)
        } else {
            vec![0.; samples]
        };
        upload(&r, &values, c, &mut m)?;
        let mut v = params(p.width, p.height, if prefix.is_some() { 40 } else { 21 });
        v[4] = count as u32;
        v[6] = u32::from(p.options.glare.is_some());
        v[7] = offset as u32;
        r.dispatch(v, c, &mut m)?;
        let mut v = params(p.width, p.height, 25);
        v[4] = samples as u32;
        v[5] = count as u32;
        v[7] = offset as u32;
        r.dispatch(v, c, &mut m)?;
        let witness_bytes = if prefix.is_some() {
            1120
        } else {
            samples as u64 * 12
        };
        let witnesses = numbers(&r.read_probe(witness_bytes, c, &mut m)?);
        let actual = &witnesses[..samples * 3];
        let guard_at = Instant::now();
        for j in 0..samples {
            let index = if samples == 1 {
                0
            } else {
                j * (count - 1) / (samples - 1)
            };
            let logxyz = if let Some(prefix) = prefix.as_deref_mut() {
                let x = prefix.witness(
                    index,
                    [
                        witnesses[256 + 3 * j],
                        witnesses[257 + 3 * j],
                        witnesses[258 + 3 * j],
                    ],
                )?;
                prefix_samples += 3;
                x
            } else {
                [
                    values[3 * index],
                    values[3 * index + 1],
                    values[3 * index + 2],
                ]
            };
            point_guard(
                color_mirror(p, logxyz, glare_witness[j])?,
                &actual[3 * j..3 * j + 3],
                &mut m,
            )?;
        }
        m.guard_ms += guard_at.elapsed().as_secs_f64() * 1000.;
        offset += count as u64;
    }
    if let Some(prefix) = prefix.as_ref() {
        prefix.complete()?;
    }
    // Source/saved/accum initially hold R/G/B. Swaps preserve the two untouched
    // channels while complete image lines use horizontal/blurred as scratch.
    for ch in 0..3 {
        check(c)?;
        if ch > 0 {
            let mut v = params(p.width, p.height, 22);
            v[6] = if ch == 1 { 3 } else { 4 };
            r.dispatch(v, c, &mut m)?;
        }
        if p.options.lens_sigma > 0. {
            r.blur(p.width, p.height, p.options.lens_sigma, c, &mut m)?;
            let mut v = params(p.width, p.height, 4);
            v[6] = 2;
            v[7] = 0;
            r.dispatch(v, c, &mut m)?;
        }
        let sharp = p.options.sharp_radius > 0. && p.options.sharp_amount > 0.;
        if sharp {
            r.blur(p.width, p.height, p.options.sharp_radius, c, &mut m)?;
        }
        let mut v = params(p.width, p.height, 23);
        v[6] = u32::from(sharp);
        v[12] = p.options.sharp_amount.to_bits();
        r.dispatch(v, c, &mut m)?;
    }
    if r.read_error_flag(c, &mut m)? != 0 {
        return Err("GPU_TAIL_NUMERIC_DOMAIN".into());
    }
    // Final mapping is saved=R, accum=G, source=B. This is the first full RGB
    // readback after color, glare, gamut, lens, sharpening, clipping and brightness.
    let mut out = io(OpenOptions::new().create_new(true).write(true).open(output))?;
    let mut guard = Output {
        path: output,
        keep: false,
    };
    let mut offset = 0;
    while offset < n {
        check(c)?;
        let count = (n - offset).min(CHUNK_PIXELS as u64) as usize;
        let mut v = params(p.width, p.height, 24);
        v[4] = count as u32;
        v[7] = offset as u32;
        r.dispatch(v, c, &mut m)?;
        let bytes = r.read_probe(count as u64 * 12, c, &mut m)?;
        if numbers(&bytes).iter().any(|x| !x.is_finite()) {
            return Err("NONFINITE_TAIL_OUTPUT".into());
        }
        io(out.write_all(&bytes))?;
        offset += count as u64;
    }
    // Includes preview matrices and brightness from every pixel, even those
    // outside the bounded arithmetic witness sample. Never publish dummy zeros.
    if r.read_error_flag(c, &mut m)? != 0 {
        return Err("GPU_TAIL_NUMERIC_DOMAIN".into());
    }
    io(out.sync_all())?;
    check(c)?;
    m.elapsed_ms = start.elapsed().as_secs_f64() * 1000.;
    m.sample_rms = (m.sample_sum / m.sample_count.max(1) as f64).sqrt();
    if m.pass_count != p.pass_count() || m.sample_count != p.sample_count() {
        return Err("GPU_TAIL_STATE".into());
    }
    if let Some(prefix) = prefix.as_ref() {
        m.sample_count += prefix_samples;
        m.sample_sum += prefix.sample_sum;
        m.sample_max_abs = m.sample_max_abs.max(prefix.sample_max);
        m.sample_rms = (m.sample_sum / m.sample_count.max(1) as f64).sqrt();
        m.sample_scope = "full_line_f64_tail_f32_and_prefix_f64_witnesses";
    }
    let mut result = serde_json::to_value(&m).map_err(|_| "GPU_TAIL_REPORT")?;
    let o = result.as_object_mut().ok_or("GPU_TAIL_REPORT")?;
    if prefix.is_some() {
        o.insert("prefixSampleCount".into(), prefix_samples.into());
    }
    o.insert("sampleCount".into(), m.sample_count.into());
    o.insert("gpuUsed".into(), true.into());
    o.insert("backend".into(), "Vulkan f32 original scanner tail".into());
    let generated = p.options.glare.is_some() && glare.is_none();
    o.insert("glareGenerationGpu".into(), generated.into());
    o.insert("glareRandomWords".into(), glare_random_words.into());
    o.insert("glareChunks".into(), glare_chunks.into());
    o.insert(
        "glarePredicateScope".into(),
        if generated {
            "chacha12_exact_fast_guarded_q48"
        } else if glare.is_some() {
            "external_original_field"
        } else {
            "disabled"
        }
        .into(),
    );
    o.insert(
        "glareAmbiguityBound".into(),
        if generated { 2f64.powi(-32) } else { 0. }.into(),
    );
    o.insert("fullImage".into(), true.into());
    o.insert("combinedPeakBytes".into(), p.combined_peak_bytes.into());
    guard.keep = true;
    Ok(result)
}
fn generate_glare(
    r: &Runner,
    p: &TailPlan,
    c: &mut impl FnMut() -> bool,
    m: &mut RunMetrics,
) -> R<(u64, u64)> {
    let g = p.options.glare.as_ref().ok_or("INVALID_TAIL_GLARE")?;
    let tables = glare::Tables::new(g.percent, g.roughness)?;
    let packed = tables.packed();
    r.upload_aux(
        glare::TABLE_BASE as u64 * 4,
        bytemuck::cast_slice(&packed),
        c,
        m,
    )?;
    let n = p.width as u64 * p.height as u64;
    let (mut offset, mut cursor, mut chunks) = (0u64, 0u64, 0u64);
    while offset < n {
        check(c)?;
        let requested = (n - offset).min(glare::OUTPUT_CAPACITY as u64) as u32;
        let words = (requested.saturating_mul(4) + 128)
            .min(glare::WORD_CAPACITY)
            .max(256);
        if cursor + words as u64 > u32::MAX as u64 {
            return Err("GPU_TAIL_GLARE_LIMIT".into());
        }
        let mut v = params(p.width, p.height, 30);
        v[4] = ((cursor as u32 % 8) + words).div_ceil(8);
        v[5] = cursor as u32;
        v[7] = offset as u32;
        v[8] = words;
        v[9] = requested;
        r.dispatch(v, c, m)?;
        v[2] = 31;
        v[4] = words;
        r.dispatch(v, c, m)?;
        v[2] = 32;
        v[4] = 1;
        r.dispatch(v, c, m)?;
        let state = numbers(&r.read_probe(12, c, m)?);
        if state.len() != 3
            || state
                .iter()
                .any(|x| !x.is_finite() || *x < 0. || x.fract() != 0.)
        {
            return Err("GPU_TAIL_GLARE_STATE".into());
        }
        let produced = state[0] as u64;
        let used = state[1] as u64;
        let status = state[2] as u32;
        match status {
            0 => {}
            2 => return Err("GPU_TAIL_GLARE_AMBIGUOUS".into()),
            3 => return Err("GPU_TAIL_GLARE_ITERATIONS".into()),
            4 => return Err("NONFINITE_TAIL_GLARE".into()),
            _ => return Err("GPU_TAIL_GLARE_STATE".into()),
        }
        if produced == 0 || produced > requested as u64 || used < produced || used > words as u64 {
            return Err("GPU_TAIL_GLARE_STATE".into());
        }
        offset += produced;
        cursor += used;
        chunks += 1;
    }
    Ok((cursor, chunks))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "Requires a Vulkan device; validates original RNG consumption, not mobile timing"]
    fn actual_gpu_glare_preserves_long_original_stream() {
        let p = super::super::tests::plan(257, 257, 0., 0., true);
        let (r, mut metrics) =
            Runner::new_with_shader_error(&p.spatial_plan().unwrap(), true, &shader()).unwrap();
        let (words, chunks) = generate_glare(&r, &p, &mut || false, &mut metrics).unwrap();
        assert!(chunks > 1);
        let n = p.width as usize * p.height as usize;
        let original = spektrafilm_model::glare::research_glare_normal_sequence(42, n).unwrap();
        assert_eq!(words, original.last().unwrap().1);
        let mut sampler = spektrafilm_model::glare::GlareSamples::new(0.3, 0.7, 42).unwrap();
        let mut offset = 0usize;
        let mut max_abs = 0f64;
        while offset < n {
            let count = (n - offset).min(CHUNK_PIXELS);
            let actual = numbers(
                &r.read_source(
                    offset as u64 * 4,
                    count as u64 * 4,
                    &mut || false,
                    &mut metrics,
                )
                .unwrap(),
            );
            for v in actual {
                let expected = sampler.sample();
                let error = (v as f64 - expected).abs();
                assert!(
                    error < 2e-5 * expected.abs().max(1.),
                    "pixel {offset}: {v} {expected}"
                );
                max_abs = max_abs.max(error);
                offset += 1;
            }
        }
        eprintln!(
            "GPU_GLARE_ORIGINAL_STREAM samples={n} words={words} chunks={chunks} max_abs={max_abs}"
        );
        assert_eq!(r.read_error_flag(&mut || false, &mut metrics).unwrap(), 0);
    }
    #[test]
    #[ignore = "Requires a Vulkan device; validates final output failure transport"]
    fn actual_final_brightness_failure_removes_candidate() {
        let root = super::super::tests::root();
        let mut p = super::super::tests::plan(17, 13, 0.7, 0.8, false);
        p.options.brightness_ev = 80.;
        let (src, _) = super::super::tests::sources(&root, 17, 13, 901);
        let out = root.join("must-not-exist");
        assert_eq!(
            run_files_inner(&p, &src, None, &out, &mut || false, true, None).unwrap_err(),
            "GPU_TAIL_NUMERIC_DOMAIN"
        );
        assert!(!out.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[ignore = "Requires a Vulkan device; validates unsampled failure transport"]
    fn actual_unsampled_domain_failure_never_publishes_candidate() {
        let root = super::super::tests::root();
        let p = super::super::tests::plan(17, 13, 0.7, 0.8, false);
        let (src, _) = super::super::tests::sources(&root, 17, 13, 900);
        // Eight witnesses select 0,31,62,94,125,157,188,220, not pixel 123.
        let mut bytes = std::fs::read(&src).unwrap();
        bytes[123 * 24..123 * 24 + 8].copy_from_slice(&50f64.to_le_bytes());
        std::fs::write(&src, bytes).unwrap();
        let out = root.join("must-not-exist");
        assert_eq!(
            run_files_inner(&p, &src, None, &out, &mut || false, true, None).unwrap_err(),
            "GPU_TAIL_NUMERIC_DOMAIN"
        );
        assert!(!out.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[ignore = "Requires a Vulkan device; validates integer ABI and per-request reset"]
    fn actual_integer_error_flag_stays_sticky_and_new_request_is_zero() {
        let p = super::super::tests::plan(1, 1, 0., 0., false)
            .spatial_plan()
            .unwrap();
        // Legacy six-buffer shaders must not be forced into the tail layout.
        let _legacy = Runner::new(&p, true).unwrap();
        let custom = shader().replace("fn tail_main(i:u32) {", "fn tail_main(i:u32) { if(p.u[0].z==26u){if(p.u[1].z!=0u){atomicOr(&tail_numeric_error.value,1u);}return;}");
        let (r, mut m) = Runner::new_with_shader_error(&p, true, &custom).unwrap();
        assert_eq!(r.read_error_flag(&mut || false, &mut m).unwrap(), 0);
        let mut v = params(1, 1, 26);
        v[6] = 1;
        r.dispatch(v, &mut || false, &mut m).unwrap();
        v[6] = 0;
        r.dispatch(v, &mut || false, &mut m).unwrap();
        assert_eq!(r.read_error_flag(&mut || false, &mut m).unwrap(), 1);
        drop(r);
        let (r, mut m) = Runner::new_with_shader_error(&p, true, &custom).unwrap();
        assert_eq!(r.read_error_flag(&mut || false, &mut m).unwrap(), 0);
    }
    #[test]
    #[ignore = "Requires a Vulkan device; software probe is not mobile performance evidence"]
    fn actual_vulkan_scanner_suffix_matches_mirror() {
        let root = super::super::tests::root();
        for (index, (w, h, sigma, sharp, glare)) in [
            (1, 1, 0., 0., false),
            (17, 13, 0.7, 0.8, true),
            (23, 19, 3.1, 3.2, true),
        ]
        .into_iter()
        .enumerate()
        {
            let p = super::super::tests::plan(w, h, sigma, sharp, glare);
            let (src, g) = super::super::tests::sources(&root, w, h, index as u64);
            let pixels = super::super::tests::logs(w, h);
            let field = super::super::tests::glare_values(w, h);
            let want = image_mirror(&p, &pixels, glare.then_some(field.as_slice()), &mut || {
                false
            })
            .unwrap();
            let out = root.join(format!("actual-{index}"));
            let m = run_files_inner(
                &p,
                &src,
                glare.then_some(g.as_path()),
                &out,
                &mut || false,
                true,
                None,
            )
            .unwrap();
            assert_eq!(m["gpuUsed"], true);
            let actual = numbers(&std::fs::read(out).unwrap());
            for (&a, b) in actual.iter().zip(want.into_iter().flatten()) {
                assert!((a - b).abs() < 2e-3, "{w}x{h}: {a} {b}");
            }
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}
