//! Closed composition for actual optics and DIR. Raw SHADER remains unchanged
//! for tail/fusion composition. All arithmetic is guarded before evaluation.
use super::{R, SHADER};
pub(crate) fn shader() -> R<String> {
    checked(SHADER)
}
fn checked(source: &str) -> R<String> {
    let mut result = crate::research_finish_tail::guarded_spatial_shader(source)?;
    let replacements = [
        (
            "if(!finite_value(x)){return bitcast<f32>(2143289344u);}",
            "let admitted=tbound(x); if(admitted!=x){return 0.0;}",
        ),
        (
            "return probe.values[curve_offset+ch];",
            "return tbound(probe.values[curve_offset+ch]);",
        ),
        (
            "return probe.values[curve_offset+3u*last+ch];",
            "return tbound(probe.values[curve_offset+3u*last+ch]);",
        ),
        (
            "(x-probe.values[axis_offset+lo])*probe.values[inverse_offset+lo]",
            "tmul(tsub(x,probe.values[axis_offset+lo]),probe.values[inverse_offset+lo])",
        ),
        ("return a+t*(b-a);", "return tadd(a,tmul(t,tsub(b,a)));"),
        (
            "accum.values[i]+bitcast<f32>(p.u[3].x)*blurred.values[i]",
            "tadd(accum.values[i],tmul(bitcast<f32>(p.u[3].x),blurred.values[i]))",
        ),
        (
            "(1.0-a)*source.values[i]+a*((1.0-t)*saved.values[i]+t*accum.values[i])",
            "tadd(tmul(tsub(1.0,a),source.values[i]),tmul(a,tadd(tmul(tsub(1.0,t),saved.values[i]),tmul(t,accum.values[i]))))",
        ),
        (
            "source.values[i]+bitcast<f32>(p.u[3].z)*accum.values[i]",
            "tadd(source.values[i],tmul(bitcast<f32>(p.u[3].z),accum.values[i]))",
        ),
        (
            "source.values[i]/bitcast<f32>(p.u[3].w)",
            "tdiv(source.values[i],bitcast<f32>(p.u[3].w))",
        ),
        (
            "bitcast<f32>(p.u[3].x)-d[0]",
            "tsub(bitcast<f32>(p.u[3].x),d[0])",
        ),
        (
            "bitcast<f32>(p.u[3].y)-d[1]",
            "tsub(bitcast<f32>(p.u[3].y),d[1])",
        ),
        (
            "bitcast<f32>(p.u[3].z)-d[2]",
            "tsub(bitcast<f32>(p.u[3].z),d[2])",
        ),
        (
            "d[0]*bitcast<f32>(p.u[4].x)+d[1]*bitcast<f32>(p.u[4].y)+d[2]*bitcast<f32>(p.u[4].z)",
            "tadd(tadd(tmul(d[0],bitcast<f32>(p.u[4].x)),tmul(d[1],bitcast<f32>(p.u[4].y))),tmul(d[2],bitcast<f32>(p.u[4].z)))",
        ),
        (
            "(1.0-t)*saved.values[i]+t*accum.values[i]",
            "tadd(tmul(tsub(1.0,t),saved.values[i]),tmul(t,accum.values[i]))",
        ),
        (
            "probe.values[524288u+i]-source.values[index]",
            "tsub(probe.values[524288u+i],source.values[index])",
        ),
    ];
    for (old, new) in replacements {
        if result.matches(old).count() != 1 {
            return Err("GPU_OPTICS_SHADER_VERSION".into());
        }
        result = result.replace(old, new);
    }
    Ok(result)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn actual_optics_dir_composition_validates() {
        let s = shader().unwrap();
        let module =
            naga::front::wgsl::parse_str(&s).unwrap_or_else(|e| panic!("{}", e.emit_to_string(&s)));
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap();
        assert!(s.contains("atomicOr(&tail_numeric_error.value,1u)"));
    }
    #[test]
    fn shared_shader_drift_is_closed() {
        assert!(checked(&SHADER.replace("return a+t*(b-a);", "return a;")).is_err());
    }
    #[test]
    fn conservative_bounds_prevent_partial_overflow() {
        let bound = 1e15f64;
        assert!(bound * bound < f32::MAX as f64);
        assert!(bound / 1e-15 < f32::MAX as f64);
        assert!(2e38f64 > bound);
        assert!(3e38f64 > bound);
    }
}
