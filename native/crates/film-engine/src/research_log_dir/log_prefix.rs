//! The original global log operator in the existing bounded DIR upload slot.
//! Only bounded arithmetic witnesses return to the host, never an image plane.
use crate::research_dir::R;
pub(crate) const PIPELINE_BYTES: u64 = 8 * crate::research_spatial::MIB;
pub(crate) const HOST_BYTES: u64 = crate::research_spatial::MIB;
pub(crate) fn validate_correction(w: u32, h: u32, correction: f64) -> R<()> {
    crate::research_global::GlobalPlan::log_exposure(w, h, correction).map(|_| ())
}

pub(crate) fn shader() -> R<String> {
    // Extract rather than maintain a second copy of the photographic arithmetic.
    // Reject an upstream source change instead of silently inventing a new log.
    let source = crate::research_global::LOG_SHADER;
    let begin = "let corrected=";
    let end = ";write_plane(ch,i,out);";
    if source.matches(begin).count() != 1 || source.matches(end).count() != 1 {
        return Err("GPU_LOG_DIR_SHADER_VERSION".into());
    }
    let body = source.split_once(begin).unwrap().1.split_once(end).ok_or("GPU_LOG_DIR_SHADER_VERSION")?.0;
    if body.matches("read_plane(ch,i)").count() != 1 {
        return Err("GPU_LOG_DIR_SHADER_VERSION".into());
    }
    let arithmetic = format!("let corrected={};return out;", body.replace("read_plane(ch,i)", "raw"));
    Ok(format!(r#"{}
struct Data {{ values: array<f32>, }}
struct Params {{ u: array<vec4<u32>, 10>, }}
@group(0) @binding(0) var<storage, read_write> source: Data;
@group(0) @binding(1) var<storage, read_write> horizontal: Data;
@group(0) @binding(2) var<storage, read_write> blurred: Data;
@group(0) @binding(3) var<storage, read_write> saved: Data;
@group(0) @binding(4) var<storage, read_write> accum: Data;
@group(0) @binding(5) var<storage, read_write> probe: Data;
@group(0) @binding(6) var<uniform> p: Params;
fn exposure_log(raw:f32,factor:f32)->f32 {{{arithmetic}}}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>) {{
 let i=gid.x+gid.y*65535u*64u;if(i>=p.u[1].x){{return;}}
 let op=p.u[0].z;
 if(op==16u){{let factor=gbound(bitcast<f32>(p.u[3].x));probe.values[524288u+i]=exposure_log(gbound(probe.values[524288u+i]),factor);return;}}
 if(op==17u){{
  let channels=p.u[1].z;let samples=p.u[1].x/channels;
  var index=0u;if(samples>1u){{index=(i/channels)*(p.u[1].y-1u)/(samples-1u);}}
  probe.values[i]=probe.values[524288u+index*channels+i%channels];return;
 }}
 _=gfail();
}}
"#, include_str!("../shaders/research_global_guard.wgsl")))
}

#[cfg(feature = "gpu-diffusion-research")]
#[derive(Default)]
pub(crate) struct Metrics { pub count: u64, pub max: f64, pub sum: f64 }

#[cfg(feature = "gpu-diffusion-research")]
pub(crate) fn apply(
    runner: &crate::research_spatial::gpu::Runner,
    correction: f64,
    originals: &[&[f64]],
    c: &mut impl FnMut() -> bool,
    metrics: &mut crate::research_spatial::gpu::RunMetrics,
    prefix: &mut Metrics,
) -> R<Vec<f32>> {
    use crate::research_spatial::gpu::params;
    let channels = originals.len();
    let count = originals.first().map_or(0, |v| v.len());
    if !matches!(channels, 1 | 3) || count == 0 || count > crate::research_dir::CHUNK_PIXELS as usize
        || originals.iter().any(|v| v.len() != count) {
        return Err("INVALID_LOG_DIR_CHUNK".into());
    }
    let mut v = params(1, 1, 16);
    v[4] = (count * channels) as u32;
    v[12] = (correction as f32).to_bits();
    runner.dispatch(v, c, metrics)?;
    let indices = crate::research_dir::sample_indices(count);
    let mut v = params(1, 1, 17);
    v[4] = (indices.len() * channels) as u32;
    v[5] = count as u32;
    v[6] = channels as u32;
    runner.dispatch(v, c, metrics)?;
    let bytes = runner.read_probe((indices.len() * channels * 4) as u64, c, metrics)?;
    let actual: Vec<f32> = bytes.chunks_exact(4).map(|b| f32::from_le_bytes(b.try_into().unwrap())).collect();
    if actual.len() != indices.len() * channels { return Err("GPU_LOG_DIR_NUMERIC_CHECK".into()); }
    for (sample, &index) in indices.iter().enumerate() {
        for ch in 0..channels {
            // Same f64 oracle and rounded operands as research_global::gpu.
            let raw = (originals[ch][index] as f32) as f64;
            let reference = ((raw * (correction as f32) as f64).max(0.) + 1e-10).log10();
            let value = actual[sample * channels + ch] as f64;
            let error = (reference - value).abs();
            if !reference.is_finite() || !value.is_finite()
                || error > crate::research_global::NUMERIC_TOLERANCE * reference.abs().max(1.) {
                return Err("GPU_LOG_DIR_NUMERIC_CHECK".into());
            }
            prefix.count += 1;
            prefix.max = prefix.max.max(error);
            prefix.sum += error * error;
        }
    }
    Ok(actual)
}

#[cfg(test)]
pub(crate) fn mirror(raw: f64, correction: f64) -> R<f32> {
    fn bounded(v: f32) -> R<f32> {
        if !v.is_finite() || v.abs() > 1e15 { Err("GPU_LOG_DIR_NUMERIC_DOMAIN".into()) } else { Ok(v) }
    }
    let raw = bounded(raw as f32)?;
    let correction = bounded(correction as f32)?;
    let corrected = bounded(raw * correction)?;
    let input = bounded(corrected.max(0.) + 1e-10)?;
    if input < 1e-30 { return Err("GPU_LOG_DIR_NUMERIC_DOMAIN".into()); }
    bounded(bounded(input.ln())? * 0.4342944819032518_f32)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shader_preserves_global_arithmetic_and_bounded_fifth_family() {
        let source = shader().unwrap();
        assert!(source.contains("let corrected=gmul(raw,factor);let out=gmul(glog(gadd(max(corrected,0.0),1e-10)),0.4342944819032518);return out;"));
        assert!(source.starts_with(include_str!("../shaders/research_global_guard.wgsl")));
        assert!(source.len() < 65536);
        assert!(source.contains("op==16u"));
        assert!(source.contains("op==17u"));
        assert!(!source.contains("density_value"));
    }
    #[test]
    fn original_floor_and_epsilon_and_domain_survive() {
        assert_eq!(mirror(-1., 2.).unwrap(), mirror(0., 2.).unwrap());
        assert!(mirror(1e16, 1.).is_err());
        assert!(mirror(1e14, 1e14).is_err());
        assert!(validate_correction(1, 1, f64::NAN).is_err());
        assert!(validate_correction(1, 1, 0.).is_err());
        assert!(validate_correction(1, 1, 0.73).is_ok());
    }
}
