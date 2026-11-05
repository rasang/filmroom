//! Prune unreachable operations; preserve science and defer numeric flag publication.
use super::*;

fn before<'a>(source: &'a str, marker: &str) -> R<&'a str> {
    if source.matches(marker).count() != 1 {
        return err("GPU_FUSION_SHADER_VERSION");
    }
    Ok(source.split_once(marker).unwrap().0)
}
fn between<'a>(source: &'a str, start: &str, end: &str) -> R<&'a str> {
    if source.matches(start).count() != 1 {
        return err("GPU_FUSION_SHADER_VERSION");
    }
    let at = source.find(start).unwrap();
    before(&source[at..], end)
}

pub(super) fn shader(p: &TailPlan) -> R<Option<String>> {
    // Admission must precede source selection, including rejection of NaNs.
    p.validate()?;
    if p.options.glare.is_some()
        || p.options.lens_sigma != 0.
        || (p.options.sharp_radius > 0. && p.options.sharp_amount > 0.)
    {
        return Ok(None);
    }
    Ok(Some(private_numeric_flag(&assemble(
        crate::research_spatial::SHADER,
        tail::SHADER_APPEND,
    )?)?))
}

pub(super) fn standalone_shader(p: &TailPlan) -> R<Option<String>> {
    p.validate()?;
    if p.options.glare.is_some()
        || p.options.lens_sigma != 0.
        || (p.options.sharp_radius > 0. && p.options.sharp_amount > 0.)
    {
        return Ok(None);
    }
    let source = assemble(crate::research_spatial::SHADER, tail::SHADER_APPEND)?;
    let dispatcher = " if(op==40u){finish_main(i);return;}\n";
    let finish = include_str!("../../shaders/research_finish_fused.wgsl");
    if source.matches(dispatcher).count() != 1 || source.matches(finish).count() != 1 {
        return err("GPU_FUSION_SHADER_VERSION");
    }
    let input_op = between(tail::SHADER_APPEND, " if(op==21u){", " if(op==22u){")?;
    let source = source
        .replace(dispatcher, "")
        .replace(finish, "")
        .replace("if(op>=22u && op<=25u)", "if(op>=21u && op<=25u)")
        .replace(" if(op==22u){", &format!("{input_op} if(op==22u){{"));
    private_numeric_flag(&source).map(Some)
}

fn assemble(spatial: &str, tail_source: &str) -> R<String> {
    let declarations = before(spatial, "fn reflect_index(")?;
    let accessors = between(spatial, "fn get_value(", "// Preserve nonfinite failure")?;
    let entry = between(
        spatial,
        "@compute @workgroup_size(64)",
        " if(op==0u || op==1u){",
    )?;
    let color = before(tail_source, "fn tail_main(i:u32) {")?;
    let tail_header = between(tail_source, "fn tail_main(i:u32) {", " if(op==20u){")?;
    let marker = " if(op==22u){";
    if tail_source.matches(marker).count() != 1 {
        return err("GPU_FUSION_SHADER_VERSION");
    }
    let tail_ops = &tail_source[tail_source.find(marker).unwrap()..];
    // The suffix must remain exactly ops 22..25, and end the original function.
    for marker in [" if(op==23u){", " if(op==24u){", " if(op==25u){"] {
        if tail_ops.matches(marker).count() != 1 {
            return err("GPU_FUSION_SHADER_VERSION");
        }
    }
    if !tail_ops.trim_end().ends_with('}') {
        return err("GPU_FUSION_SHADER_VERSION");
    }
    Ok(format!(
        "{declarations}{accessors}{entry}\n if(op==40u){{finish_main(i);return;}}\n if(op>=22u && op<=25u){{tail_main(i);return;}}\n _ = tfail();\n}}\n{}{color}{tail_header}{tail_ops}\n{}",
        tail::SHADER_GUARD,
        include_str!("../../shaders/research_finish_fused.wgsl"),
    ))
}

// These exact guards are versioned. Only transport changes: each invocation
// accumulates the same sticky bit, then publishes it after every body return.
// No invocation may observe the global flag before this deferred publication.
const ORIGINAL_FLAG_COMMENT: &str =
    "// Rejected pixels become inert zeros only after the request-wide atomic flag.";
const PRIVATE_FLAG_COMMENT: &str = "// Rejected pixels become inert zeros after the invocation-local sticky bit; the wrapper publishes it before invocation completion.";
const ORIGINAL_FAIL: &str = "fn tfail()->f32 {atomicOr(&tail_numeric_error.value,1u);return 0.0;}";
const PRIVATE_FAIL: &str = "var<private> invocation_numeric_error:u32;\nfn tfail()->f32 {invocation_numeric_error=invocation_numeric_error|1u;return 0.0;}";
const ORIGINAL_BOUND: &str = "fn tbound(v:f32)->f32 {if(abs(v)>T_BOUND){return tfail();}return v;}";
const PRIVATE_BOUND: &str = "fn tbound(v:f32)->f32 {let rejected=abs(v)>T_BOUND;invocation_numeric_error=invocation_numeric_error|select(0u,1u,rejected);return select(v,0.0,rejected);}";
const ORIGINAL_ENTRY: &str =
    "@compute @workgroup_size(64)\nfn main(@builtin(global_invocation_id) gid:vec3<u32>){";
const PRIVATE_ENTRY: &str = "fn invocation_body(gid:vec3<u32>){";
const FLUSH_ENTRY: &str = "\n@compute @workgroup_size(64)\nfn main(@builtin(global_invocation_id) gid:vec3<u32>){\n invocation_numeric_error=0u;\n invocation_body(gid);\n if(invocation_numeric_error!=0u){atomicOr(&tail_numeric_error.value,invocation_numeric_error); }\n}\n";

pub(super) fn private_numeric_flag(source: &str) -> R<String> {
    transport(source, false)
}

/// Applied only after ALL source assembly. The sole allowed reader is the
/// versioned single-invocation glare selector, after ordered candidate dispatch.
pub(super) fn full_private_numeric_flag(source: &str) -> R<String> {
    let glare = include_str!("../../shaders/research_finish_glare.wgsl");
    if source.matches(glare).count() != 1
        || source.matches("gselect(").count() != 2
        || source.matches(" if(op>=30u){glare_main(i);return;}").count() != 1
        || !glare.contains("if(p.u[0].z==32u&&i==0u){gselect();}")
        || !glare.contains("let value=texp(tadd(mu,tmul(sigma,probe.values[at])));if(atomicLoad(&tail_numeric_error.value)!=0u){status=4u;break;}source.values")
    { return err("GPU_FUSION_SHADER_VERSION"); }
    transport(source, true)
}

fn transport(source: &str, glare: bool) -> R<String> {
    // Fail closed if a later shader starts observing the global flag, adds an
    // entrypoint/atomic writer, or no longer has the reviewed guard definitions.
    if source.matches("atomicLoad").count() != usize::from(glare)
        || source.contains("atomicStore")
        || source.matches("atomicOr").count() != 1
        || source.matches("tail_numeric_error").count() != 2 + usize::from(glare)
        || source.matches("@compute").count() != 1
        || source.contains("invocation_numeric_error")
        || source.contains("invocation_body")
        || source.contains("discard")
    {
        return err("GPU_FUSION_SHADER_VERSION");
    }
    let mut result = source.to_owned();
    for (old, new) in [
        (ORIGINAL_FLAG_COMMENT, PRIVATE_FLAG_COMMENT),
        (ORIGINAL_FAIL, PRIVATE_FAIL),
        (ORIGINAL_BOUND, PRIVATE_BOUND),
        (ORIGINAL_ENTRY, PRIVATE_ENTRY),
    ] {
        if result.matches(old).count() != 1 {
            return err("GPU_FUSION_SHADER_VERSION");
        }
        result = result.replace(old, new);
    }
    if glare {
        let old = "atomicLoad(&tail_numeric_error.value)";
        if result.matches(old).count() != 1 {
            return err("GPU_FUSION_SHADER_VERSION");
        }
        result = result.replace(
            old,
            "(atomicLoad(&tail_numeric_error.value)|invocation_numeric_error)",
        );
    }
    result.push_str(FLUSH_ENTRY);
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn original() -> String {
        assemble(crate::research_spatial::SHADER, tail::SHADER_APPEND).unwrap()
    }
    #[test]
    fn private_flag_reverses_exactly_and_retains_all_original_science() {
        let original = original();
        let transformed = private_numeric_flag(&original).unwrap();
        let mut recovered = transformed.strip_suffix(FLUSH_ENTRY).unwrap().to_owned();
        for (old, new) in [
            (ORIGINAL_FLAG_COMMENT, PRIVATE_FLAG_COMMENT),
            (ORIGINAL_FAIL, PRIVATE_FAIL),
            (ORIGINAL_BOUND, PRIVATE_BOUND),
            (ORIGINAL_ENTRY, PRIVATE_ENTRY),
        ] {
            assert_eq!(recovered.matches(new).count(), 1);
            recovered = recovered.replace(new, old);
        }
        assert_eq!(recovered, original);
        assert!(transformed.contains(include_str!("../../shaders/research_finish_fused.wgsl")));
        assert!(
            transformed.contains(before(tail::SHADER_APPEND, "fn tail_main(i:u32) {").unwrap())
        );
        assert!(transformed.contains(tail::SHADER_APPEND.split_once(" if(op==22u){").unwrap().1));
        assert_eq!(transformed.matches("pchip(4u,x)").count(), 1);
        assert_eq!(transformed.matches("pchip(20u,x)").count(), 1);
        for absent in [
            "glare_main",
            "reflect_index",
            "density_value",
            "op==20u",
            "op==21u",
            "op==41u",
            "op==42u",
        ] {
            assert!(!transformed.contains(absent));
        }
        for source in [&original, &transformed] {
            let module = naga::front::wgsl::parse_str(source).unwrap();
            naga::valid::Validator::new(
                naga::valid::ValidationFlags::all(),
                naga::valid::Capabilities::empty(),
            )
            .validate(&module)
            .unwrap();
            assert_eq!(
                module
                    .global_variables
                    .iter()
                    .filter(|(_, v)| matches!(v.space, naga::AddressSpace::Storage { .. }))
                    .count(),
                8
            );
        }
    }
    #[test]
    fn all_early_returns_and_unknown_operations_publish_failure() {
        let source = private_numeric_flag(&original()).unwrap();
        assert_eq!(source.matches("@compute").count(), 1);
        assert_eq!(source.matches("atomicOr").count(), 1);
        let wrapper = source
            .strip_prefix(source.split_once(FLUSH_ENTRY).unwrap().0)
            .unwrap();
        assert_eq!(wrapper, FLUSH_ENTRY);
        assert!(!wrapper.contains("return"));
        assert!(
            wrapper.find("invocation_numeric_error=0u;").unwrap()
                < wrapper.find("invocation_body(gid);").unwrap()
        );
        assert!(wrapper.find("invocation_body(gid);").unwrap() < wrapper.find("atomicOr").unwrap());
        assert!(source.contains("if(local>=p.u[1].x){return;}"));
        assert!(source.contains("if(op==40u){finish_main(i);return;}"));
        assert!(source.contains("_ = tfail();"));
    }
    #[test]
    fn full_glare_guard_observes_local_failure_and_rejects_final_source_drift() {
        let source = super::super::fused_shader(&tail::shader()).unwrap();
        let transformed = full_private_numeric_flag(&source).unwrap();
        assert!(transformed.contains("if((atomicLoad(&tail_numeric_error.value)|invocation_numeric_error)!=0u){status=4u;break;}source.values"));
        let mut recovered = transformed.strip_suffix(FLUSH_ENTRY).unwrap().to_owned();
        recovered = recovered.replace(
            "(atomicLoad(&tail_numeric_error.value)|invocation_numeric_error)",
            "atomicLoad(&tail_numeric_error.value)",
        );
        for (old, new) in [
            (ORIGINAL_FLAG_COMMENT, PRIVATE_FLAG_COMMENT),
            (ORIGINAL_FAIL, PRIVATE_FAIL),
            (ORIGINAL_BOUND, PRIVATE_BOUND),
            (ORIGINAL_ENTRY, PRIVATE_ENTRY),
        ] {
            assert_eq!(recovered.matches(new).count(), 1);
            recovered = recovered.replace(new, old);
        }
        assert_eq!(recovered, source);
        for mutation in [
            format!("{source}\n// atomicLoad"),
            format!("{source}\n// gselect("),
            source.replace("&&i==0u", "&&i<2u"),
            source.replace("status=4u;break;", "status=4u;"),
        ] {
            assert!(full_private_numeric_flag(&mutation).is_err());
        }
        let module = naga::front::wgsl::parse_str(&transformed).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap();
    }
    #[test]
    fn guard_transport_rejects_ineligible_or_drifted_source() {
        let original = original();
        for mutation in [
            original.replace("atomicOr", "atomicStore"),
            format!("{original}\n// atomicLoad"),
            format!("{original}\n// atomicOr"),
            format!("{original}\n// tail_numeric_error"),
            format!("{original}\n// @compute"),
            format!("{original}\n// invocation_body"),
            original.replace("abs(v)>T_BOUND", "abs(v)>=T_BOUND"),
            original.replace("@workgroup_size(64)", "@workgroup_size(32)"),
        ] {
            assert!(private_numeric_flag(&mutation).is_err());
        }
        assert!(private_numeric_flag(&private_numeric_flag(&original).unwrap()).is_err());
        assert!(assemble("drift", tail::SHADER_APPEND).is_err());
        assert!(assemble(crate::research_spatial::SHADER, "drift").is_err());
    }
}
