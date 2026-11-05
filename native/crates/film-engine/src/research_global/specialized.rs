//! Compile only the global operations and metering branches this request uses.
//! This is source selection, not a replacement scientific implementation. Guards,
//! statistic slots, reductions and curve formulas are copied from research_global.wgsl.
//! Only op13's validated three-plane loop is unrolled with fixed addresses.
//! The small extractor fails closed on selector or address-contract changes.
use super::{GlobalKind, MeterMethod, R};
use std::collections::{BTreeMap, BTreeSet};
const ERROR: &str = "GPU_GLOBAL_SHADER_VERSION";
fn fail<T>() -> R<T> { Err(ERROR.into()) }

// Comment masking retains byte positions. Source outside ASCII, strings, or
// nested block comments is rejected: this deliberately small extractor must
// not guess after a shader grammar change. WGSL has no string expressions.
fn mask(s: &str) -> R<String> {
    if !s.is_ascii() {
        return fail();
    }
    let mut b = s.as_bytes().to_vec();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'"' {
            return fail();
        }
        if b[i..].starts_with(b"//") {
            while i < b.len() && b[i] != b'\n' {
                b[i] = b' ';
                i += 1;
            }
        } else if b[i..].starts_with(b"/*") {
            let start = i;
            i += 2;
            while i + 1 < b.len() && !b[i..].starts_with(b"*/") {
                if b[i..].starts_with(b"/*") {
                    return fail();
                }
                i += 1;
            }
            if i + 1 >= b.len() {
                return fail();
            }
            i += 2;
            for v in &mut b[start..i] {
                if *v != b'\n' {
                    *v = b' ';
                }
            }
        } else {
            i += 1;
        }
    }
    String::from_utf8(b).map_err(|_| ERROR.into())
}
fn close(s: &str, start: usize) -> R<usize> {
    if s.as_bytes().get(start) != Some(&b'{') {
        return fail();
    }
    let mut depth = 0;
    for (i, b) in s.bytes().enumerate().skip(start) {
        if b == b'{' {
            depth += 1;
        } else if b == b'}' {
            depth -= 1;
            if depth == 0 {
                return Ok(i + 1);
            }
        }
    }
    fail()
}
fn identifiers(s: &str) -> R<BTreeSet<String>> {
    Ok(mask(s)?
        .split(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect())
}
fn functions(s: &str) -> R<(String, BTreeMap<String, String>)> {
    let m = mask(s)?;
    let mut declarations = String::new();
    let mut found = BTreeMap::new();
    let mut cursor = 0;
    while let Some(relative) = m[cursor..].find("fn ") {
        let start = cursor + relative;
        if start > 0 && !m.as_bytes()[start - 1].is_ascii_whitespace() {
            return fail();
        }
        let name_end = m[start + 3..].find('(').ok_or(ERROR)? + start + 3;
        let name = m[start + 3..name_end].trim();
        if name.is_empty() || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') {
            return fail();
        }
        let open = m[name_end..].find('{').ok_or(ERROR)? + name_end;
        let end = close(&m, open)?;
        declarations.push_str(&s[cursor..start]);
        if found
            .insert(name.to_owned(), s[start..end].to_owned())
            .is_some()
        {
            return fail();
        }
        cursor = end;
    }
    declarations.push_str(&s[cursor..]);
    if declarations.matches("@compute @workgroup_size(64)").count() != 1 {
        return fail();
    }
    declarations = declarations.replace("@compute @workgroup_size(64)", "");
    if mask(&declarations)?.contains("fn ") {
        return fail();
    }
    Ok((declarations, found))
}

const OPS: [u32; 12] = [0, 1, 2, 3, 4, 10, 11, 12, 13, 14, 15, 16];
const MAIN_PREFIX: &str = "fn main(@builtin(global_invocation_id) gid:vec3<u32>){\n let i=gid.x+gid.y*65535u*64u;if(i>=p.u[1].x){return;}\n let op=p.u[0].z;let n=p.u[2].x;let method=p.u[2].y;\n ";
const METHOD_DECLARATION: &str = "let method=p.u[2].y;";
const BLOCK_SELECTORS: [&str; 6] = [
    "if(method==1u||method==4u||method==6u||method==7u){",
    "if(method==3u){", "if(method==4u){", "if(method==7u){",
    "if(method==6u){", "if(method==5u){",
];
const FINAL_SELECTORS: [&str; 7] = [
    "if(method==1u){", "if(method==2u){", "if(method==3u){",
    "if(method==4u){", "if(method==7u){", "if(method==6u){",
    "if(method==5u){",
];

// Parse the complete versioned opcode inventory, not just selected branches.
// This ensures a newly inserted dispatch or selector cannot be silently lost.
fn operation_blocks(main: &str) -> R<BTreeMap<u32, String>> {
    if !main.starts_with(MAIN_PREFIX) { return fail(); }
    let m = mask(main)?;
    let mut cursor = MAIN_PREFIX.len();
    let mut blocks = BTreeMap::new();
    for op in OPS {
        let start = cursor + m[cursor..].find(|c: char| !c.is_ascii_whitespace()).ok_or(ERROR)?;
        let header = format!("if(op=={op}u){{");
        if !m[start..].starts_with(&header) { return fail(); }
        let end = close(&m, start + header.len() - 1)?;
        blocks.insert(op, main[start..end].to_owned());
        cursor = end;
    }
    if m[cursor..].trim() != "}" { return fail(); }
    Ok(blocks)
}

fn selector_matches(header: &str, method: MeterMethod) -> bool {
    if header == BLOCK_SELECTORS[0] {
        matches!(method, MeterMethod::Average | MeterMethod::Partial
            | MeterMethod::MultiZone | MeterMethod::HighlightWeighted)
    } else {
        header == format!("if(method=={}u){{", method.code())
    }
}

// The method condition itself is pure. Delete only branches whose versioned
// condition is false for this immutable request, retaining true blocks verbatim.
fn select_method(source: &str, op: u32, method: MeterMethod) -> R<String> {
    let selectors: &[&str] = match op {
        2 => &BLOCK_SELECTORS,
        3 => &FINAL_SELECTORS,
        _ => return fail(),
    };
    let m = mask(source)?;
    let mut output = String::new();
    let mut cursor = 0;
    for header in selectors {
        let start = cursor + m[cursor..].find("if(method").ok_or(ERROR)?;
        if identifiers(&source[cursor..start])?.contains("method")
            || !m[start..].starts_with(header) { return fail(); }
        let end = close(&m, start + header.len() - 1)?;
        // Removing a false if must never rebind a later else to another if.
        // No method selector in the reference inventory admits an else arm.
        if m[end..].trim_start().starts_with("else") { return fail(); }
        // Nested method selectors are not part of the admitted source grammar.
        if identifiers(&source[start + header.len()..end])?.contains("method") {
            return fail();
        }
        output.push_str(&source[cursor..start]);
        if selector_matches(header, method) { output.push_str(&source[start..end]); }
        cursor = end;
    }
    if identifiers(&source[cursor..])?.contains("method") { return fail(); }
    output.push_str(&source[cursor..]);
    Ok(output)
}

// Only address/control specialization: the original loop visits 0,1,2 in
// this order, and its versioned accessors map those values to these three planes.
// Every boost_point call and guard remains the original function verbatim.
const HIGHLIGHT_RGB_LOOP: &str = "if(op==13u){for(var ch=0u;ch<3u;ch=ch+1u){write_plane(ch,i,boost_point(read_plane(ch,i)));}return;}";
const HIGHLIGHT_FIXED_PLANES: &str = "if(op==13u){source.values[i]=boost_point(source.values[i]);horizontal.values[i]=boost_point(horizontal.values[i]);blurred.values[i]=boost_point(blurred.values[i]);return;}";
fn highlight_fixed_planes(block: &str, helpers: &BTreeMap<String, String>) -> R<String> {
    if block != HIGHLIGHT_RGB_LOOP
        || helpers.get("read_plane").map(String::as_str) != Some("fn read_plane(ch:u32,i:u32)->f32{if(ch==0u){return source.values[i];}if(ch==1u){return horizontal.values[i];}return blurred.values[i];}")
        || helpers.get("write_plane").map(String::as_str) != Some("fn write_plane(ch:u32,i:u32,v:f32){if(ch==0u){source.values[i]=v;}else if(ch==1u){horizontal.values[i]=v;}else{blurred.values[i]=v;}}")
    { return fail(); }
    Ok(HIGHLIGHT_FIXED_PLANES.into())
}

fn assemble(reference: &str, ops: &[u32], method: Option<MeterMethod>) -> R<String> {
    let allowed = match method {
        Some(MeterMethod::Median) => matches!(ops, [0, 4] | [1] | [2] | [3]),
        Some(_) => matches!(ops, [0, 4] | [2] | [3]),
        None => matches!(ops, [10, 15] | [11] | [12] | [13, 16]),
    };
    if !allowed { return fail(); }
    let (declarations, mut functions) = functions(reference)?;
    let blocks = operation_blocks(functions.get("main").ok_or(ERROR)?)?;
    // Validate ALL method selectors even for a point/other operator family.
    for op in [2, 3] { select_method(&blocks[&op], op, MeterMethod::Unknown)?; }
    for op in OPS {
        if ![2, 3].contains(&op) && identifiers(&blocks[&op])?.contains("method") {
            return fail();
        }
    }
    let uses_method = ops.iter().any(|op| [2, 3].contains(op));
    let declaration = if uses_method {
        let code = method.ok_or(ERROR)?.code();
        // Defense in depth: a stale host method must never use another method's
        // pipeline. The production base closure already sends this exact code.
        format!("let method={code}u;if(p.u[2].y!={code}u){{atomicOr(&global_numeric_error.value,1u);return;}}")
    } else { String::new() };
    let mut selected = MAIN_PREFIX.replace(METHOD_DECLARATION, &declaration);
    for op in ops {
        if [2, 3].contains(op) {
            selected.push_str(&select_method(&blocks[op], *op, method.ok_or(ERROR)?)?);
        } else if *op == 13 {
            selected.push_str(&highlight_fixed_planes(&blocks[op], &functions)?);
        } else { selected.push_str(&blocks[op]); }
        selected.push('\n');
    }
    selected.push_str("atomicOr(&global_numeric_error.value,1u);\n}");
    functions.insert("main".into(), selected);
    let mut reachable = BTreeSet::from(["main".to_owned()]);
    loop {
        let mut next = reachable.clone();
        for name in &reachable {
            for call in identifiers(functions.get(name).ok_or(ERROR)?)? {
                if functions.contains_key(&call) { next.insert(call); }
            }
        }
        if next == reachable { break; }
        reachable = next;
    }
    let mut output = declarations;
    for name in reachable {
        if name == "main" { output.push_str("\n@compute @workgroup_size(64)\n"); }
        output.push_str(functions.get(&name).ok_or(ERROR)?);
        output.push('\n');
    }
    Ok(output)
}

// The stream control family has only a bounded maximum and scalar copies.
// Reuse the versioned parser, retaining declarations and function bodies exactly.
// An expanded user-function closure requires an explicit contract/test update;
// never silently discard a newly needed helper or import the curve family again.
pub(super) fn streaming_control_source(reference: &str) -> R<String> {
    let (declarations, functions) = functions(reference)?;
    let mut reachable = BTreeSet::from(["main".to_owned()]);
    loop {
        let mut next = reachable.clone();
        for name in &reachable {
            for call in identifiers(functions.get(name).ok_or(ERROR)?)? {
                if functions.contains_key(&call) { next.insert(call); }
            }
        }
        if next == reachable { break; }
        reachable = next;
    }
    let expected: BTreeSet<String> = ["gbound", "gfail", "main"].into_iter().map(str::to_owned).collect();
    if reachable != expected { return fail(); }
    // Unknown calls must fail here too, not only during later WGSL validation.
    // WGSL user functions are non-generic. The only admitted direct calls in
    // this version are these helpers, builtins, attributes and control syntax.
    for name in &reachable {
        let body = mask(functions.get(name).ok_or(ERROR)?)?;
        for (open, _) in body.match_indices('(') {
            let preceding = body[..open].trim_end();
            let called = preceding.rsplit(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
                .next().unwrap_or("");
            if !matches!(called, "" | "main" | "gbound" | "gfail" | "if" | "for"
                | "return" | "builtin" | "atomicOr" | "abs" | "max") { return fail(); }
        }
    }
    let mut output = declarations;
    for name in reachable {
        if name == "main" { output.push_str("\n@compute @workgroup_size(64)\n"); }
        output.push_str(functions.get(&name).ok_or(ERROR)?);
        output.push('\n');
    }
    Ok(output)
}

pub(super) fn shaders(kind: &GlobalKind) -> R<Vec<(Vec<u32>, String)>> {
    let (groups, method): (Vec<&[u32]>, Option<MeterMethod>) = match kind {
        GlobalKind::Meter { method, .. } => {
            let mut groups: Vec<&[u32]> = vec![&[0, 4], &[2], &[3]];
            if *method == MeterMethod::Median { groups.insert(1, &[1]); }
            (groups, Some(*method))
        }
        GlobalKind::Highlight { .. } => (vec![&[10, 15], &[11], &[12], &[13, 16]], None),
        // Keep the existing dedicated log shader and its exact ABI unchanged.
        GlobalKind::LogExposure { .. } => return fail(),
    };
    groups.into_iter().map(|ops| Ok((ops.to_vec(), assemble(super::SHADER, ops, method)?))).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    const METHODS: [MeterMethod; 8] = [MeterMethod::Average, MeterMethod::Median,
        MeterMethod::CenterWeighted, MeterMethod::Partial, MeterMethod::Matrix,
        MeterMethod::MultiZone, MeterMethod::HighlightWeighted, MeterMethod::Unknown];
    fn kind(method: MeterMethod) -> GlobalKind {
        GlobalKind::Meter { method, rgb_to_xyz: [[0.; 3]; 3] }
    }
    #[test]
    fn every_mode_validates_with_exact_bindings_and_dispatch_inventory() {
        for method in METHODS {
            let families = shaders(&kind(method)).unwrap();
            let mut ops: Vec<_> = families.iter().flat_map(|(ops, _)| ops.clone()).collect();
            ops.sort_unstable();
            assert_eq!(ops, if method == MeterMethod::Median { vec![0, 1, 2, 3, 4] } else { vec![0, 2, 3, 4] });
            for (ops, source) in &families {
                let module = naga::front::wgsl::parse_str(source)
                    .unwrap_or_else(|error| panic!("{}", error.emit_to_string(source)));
                naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::empty())
                    .validate(&module).unwrap();
                let mut bindings: Vec<_> = module.global_variables.iter().map(|(_, var)| {
                    let binding = var.binding.as_ref().unwrap();
                    assert_eq!(binding.group, 0); binding.binding
                }).collect();
                bindings.sort_unstable();
                assert_eq!(bindings, (0..8).collect::<Vec<_>>());
                assert_eq!(module.entry_points.len(), 1);
                assert_eq!(module.entry_points[0].workgroup_size, [64, 1, 1]);
                assert!(!source.contains("boost_point"));
                assert!(!source.contains("if(op==14u)"));
                assert_eq!(source.matches("array<f32,40>").count(), usize::from(ops.as_slice() == [2] || ops.as_slice() == [3]));
            }
        }
        for (_, source) in shaders(&GlobalKind::Highlight { boost_ev: 2., boost_range: 0.3, protect_ev: 1. }).unwrap() {
            let module = naga::front::wgsl::parse_str(&source).unwrap();
            naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::empty())
                .validate(&module).unwrap();
            assert!(!source.contains("array<f32,40>"));
            assert!(!source.contains("if(op==14u)"));
        }
    }
    #[test]
    fn retained_arithmetic_and_guard_functions_are_verbatim() {
        let (_, original) = functions(super::super::SHADER).unwrap();
        let blocks = operation_blocks(&original["main"]).unwrap();
        for method in METHODS {
            for (ops, source) in shaders(&kind(method)).unwrap() {
                let (_, specialized) = functions(&source).unwrap();
                for (name, body) in &specialized {
                    if name != "main" { assert_eq!(body, &original[name]); }
                }
                for op in ops {
                    let retained = if [2, 3].contains(&op) { select_method(&blocks[&op], op, method).unwrap() }
                        else { blocks[&op].clone() };
                    assert!(specialized["main"].contains(&retained));
                }
            }
        }
        // All non-statistical families are identical across modes, permitting
        // the existing content-keyed session cache to reuse them unchanged.
        let first = shaders(&kind(MeterMethod::Average)).unwrap()[0].clone();
        for method in METHODS { assert_eq!(shaders(&kind(method)).unwrap()[0], first); }
    }
    #[test]
    fn selector_truth_table_is_independent_of_the_selector_evaluator() {
        let (_, source) = functions(super::super::SHADER).unwrap();
        let blocks = operation_blocks(&source["main"]).unwrap();
        // Explicit positions in BLOCK_SELECTORS, not selector_matches itself.
        for (method, retained) in [
            (MeterMethod::Unknown, vec![]),
            (MeterMethod::Average, vec![0usize]),
            (MeterMethod::Median, vec![]),
            (MeterMethod::CenterWeighted, vec![1]),
            (MeterMethod::Partial, vec![0, 2]),
            (MeterMethod::Matrix, vec![5]),
            (MeterMethod::MultiZone, vec![0, 4]),
            (MeterMethod::HighlightWeighted, vec![0, 3]),
        ] {
            let selected = select_method(&blocks[&2], 2, method).unwrap();
            for (index, header) in BLOCK_SELECTORS.iter().enumerate() {
                assert_eq!(selected.contains(header), retained.contains(&index), "{method:?}: {header}");
            }
            let selected = select_method(&blocks[&3], 3, method).unwrap();
            for header in FINAL_SELECTORS {
                assert_eq!(selected.contains(header), header == format!("if(method=={}u){{", method.code()));
            }
        }
        for (ops, specialized) in shaders(&GlobalKind::Highlight { boost_ev: 2., boost_range: 0.3, protect_ev: 1. }).unwrap() {
            let (_, found) = functions(&specialized).unwrap();
            for (name, body) in &found {
                if name != "main" { assert_eq!(body, &source[name]); }
            }
            for op in ops {
                let retained = if op == 13 { HIGHLIGHT_FIXED_PLANES } else { &blocks[&op] };
                assert!(found["main"].contains(retained));
            }
        }
    }
    #[test]
    fn highlight_fixed_planes_preserves_curve_guards_and_original_channel_order() {
        let (_, original) = functions(super::super::SHADER).unwrap();
        let source = assemble(super::super::SHADER, &[13, 16], None).unwrap();
        let (_, found) = functions(&source).unwrap();
        assert!(!found.contains_key("read_plane"));
        assert!(!found.contains_key("write_plane"));
        for (name, body) in &found {
            if name != "main" { assert_eq!(body, &original[name]); }
        }
        // Invert the source-only address specialization to recover the complete
        // prior main body, including the untouched scalar witness and fallback.
        let before = format!("{}{}\n{}\natomicOr(&global_numeric_error.value,1u);\n}}",
            MAIN_PREFIX.replace(METHOD_DECLARATION, ""), HIGHLIGHT_RGB_LOOP,
            operation_blocks(&original["main"]).unwrap()[&16]);
        assert_eq!(found["main"].replacen(HIGHLIGHT_FIXED_PLANES, HIGHLIGHT_RGB_LOOP, 1), before);
        let expected = ["source", "horizontal", "blurred"].map(|plane|
            format!("{plane}.values[i]=boost_point({plane}.values[i]);")).concat();
        assert_eq!(HIGHLIGHT_FIXED_PLANES, format!("if(op==13u){{{expected}return;}}"));
        assert_eq!(found["main"].matches("boost_point(").count(), 4);
    }
    #[test]
    fn highlight_fixed_planes_rejects_changed_loop_or_accessors() {
        for (old, new) in [
            ("if(op==13u){for(var ch=0u;ch<3u;", "if(op==13u){for(var ch=0u;ch<4u;"),
            ("write_plane(ch,i,boost_point(read_plane(ch,i)))", "write_plane(ch,i,boost_point(read_plane(ch+1u,i)))"),
            ("if(ch==0u){return source.values[i];}", "if(ch==0u){return horizontal.values[i];}"),
            ("if(ch==0u){source.values[i]=v;}", "if(ch==0u){horizontal.values[i]=v;}"),
        ] {
            assert!(super::super::SHADER.contains(old));
            let changed = super::super::SHADER.replacen(old, new, 1);
            assert_eq!(assemble(&changed, &[13, 16], None).unwrap_err(), ERROR);
        }
    }
    fn unpruned_stream_control() -> String {
        let prefix = super::super::SHADER.split_once("@compute @workgroup_size(64)").unwrap().0;
        format!("{prefix}\n{}", include_str!("../shaders/research_global_stream_control.wgsl"))
    }
    #[test]
    fn stream_control_preserves_exact_main_guards_and_declarations() {
        let reference = unpruned_stream_control();
        let (declarations, original) = functions(&reference).unwrap();
        let selected = streaming_control_source(&reference).unwrap();
        let (_, retained) = functions(&selected).unwrap();
        assert_eq!(retained.keys().map(String::as_str).collect::<Vec<_>>(), ["gbound", "gfail", "main"]);
        assert!(selected.starts_with(&declarations));
        for (name, body) in retained { assert_eq!(body, original[&name]); }
        assert_eq!(selected.matches("@compute @workgroup_size(64)").count(), 1);
        for absent in ["boost_point", "gpow", "glog", "gexp", "read_plane", "write_plane"] {
            assert!(!selected.contains(&format!("fn {absent}(")));
        }
        let module = naga::front::wgsl::parse_str(&selected).unwrap();
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::empty())
            .validate(&module).unwrap();
        let mut bindings: Vec<_> = module.global_variables.iter().map(|(_, var)| {
            let binding = var.binding.as_ref().unwrap();
            assert_eq!(binding.group, 0); binding.binding
        }).collect();
        bindings.sort_unstable(); assert_eq!(bindings, (0..8).collect::<Vec<_>>());
    }
    #[test]
    fn stream_control_changed_dependency_or_grammar_fails_closed() {
        let reference = unpruned_stream_control();
        for (old, new) in [
            ("gbound(saved.values[j])", "gadd(saved.values[j],0.0)"),
            ("peak=max(peak,gbound(saved.values[j]));", "peak=unknown_helper(peak);peak=max(peak,gbound(saved.values[j]));"),
            ("return gfail();}return v;", "return gmul(v,0.0);}return v;"),
            ("fn gbound(", "fn missing_bound("),
            ("@compute @workgroup_size(64)", "@compute @workgroup_size(32)"),
        ] {
            assert!(reference.contains(old));
            assert_eq!(streaming_control_source(&reference.replacen(old, new, 1)).unwrap_err(), ERROR);
        }
        let duplicate = format!("{}\nfn gfail()->f32 {{return 0.0;}}", reference);
        assert_eq!(streaming_control_source(&duplicate).unwrap_err(), ERROR);
    }
    #[test]
    fn selector_or_dispatch_grammar_changes_fail_closed() {
        for (old, new) in [
            ("let method=p.u[2].y;", "let method=p.u[2].z;"),
            ("if(op==16u){", "if(op==17u){"),
            ("if(method==3u){", "if(method==8u){"),
            ("let average=gdiv(s[0],f32(n));", "let average=gdiv(s[0],f32(n));let extra=method;"),
            ("source.values[i]=gadd(", "source.values[i]=f32(method)+gadd("),
            ("s[2]=gadd(s[2],w);}", "s[2]=gadd(s[2],w);}else{s[39]=gfail();}"),
        ] {
            assert_eq!(assemble(&super::super::SHADER.replacen(old, new, 1), &[0, 4], Some(MeterMethod::Average)).unwrap_err(), ERROR);
        }
        assert!(assemble(super::super::SHADER, &[1], Some(MeterMethod::Average)).is_err());
        assert!(assemble(super::super::SHADER, &[2], None).is_err());
        assert!(shaders(&GlobalKind::LogExposure { correction: 1. }).is_err());
    }
}
