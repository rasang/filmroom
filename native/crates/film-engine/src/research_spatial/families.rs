//! Compile bounded optics/DIR pipelines from the guarded reference shader.
//! Original arithmetic and guards survive verbatim; IIR accessors specialize only
//! the two fixed plane routes admitted by Runner::blur.
//! All eight resource declarations survive, including the request-wide error flag.
use super::R;
use std::collections::{BTreeMap, BTreeSet};

pub(crate) const FAMILY_OPS: [&[u32]; 5] = [
    &[0, 1],
    &[2],
    &[3],
    &[4, 5, 6, 7, 8, 9, 10, 11, 13, 15],
    &[12, 14],
];
pub(crate) const MAX_FAMILIES: usize = FAMILY_OPS.len();
const ERROR: &str = "GPU_OPTICS_SHADER_VERSION";
fn fail<T>() -> R<T> {
    Err(ERROR.into())
}

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
// Branch headers are a versioned allowlist. Comments and bodies survive verbatim;
// unexpected statements/branches after the dispatch header fail closed.
fn select(source: &str, first: &str, expected: &[&str], keep: &[bool]) -> R<String> {
    if expected.len() != keep.len() || source.matches(first).count() != 1 {
        return fail();
    }
    let m = mask(source)?;
    let start = m.find(first).ok_or(ERROR)?;
    let mut output = source[..start].to_owned();
    let mut cursor = start;
    for (header, retain) in expected.iter().zip(keep) {
        let next = m[cursor..]
            .find(|c: char| !c.is_ascii_whitespace())
            .ok_or(ERROR)?
            + cursor;
        if !m[next..].starts_with(header) {
            return fail();
        }
        let open = next + header.len() - 1;
        let end = close(&m, open)?;
        if *retain {
            output.push_str(&source[cursor..end]);
            output.push('\n');
        }
        cursor = end;
    }
    if m[cursor..].trim() != "}" {
        return fail();
    }
    output.push_str("\n atomicOr(&tail_numeric_error.value,1u);\n}");
    Ok(output)
}

const HEADERS: [&str; 14] = [
    "if(op==0u || op==1u){",
    "if(op==2u || op==3u){",
    "if(op==4u){",
    "if(op==5u){",
    "if(op==6u){",
    "if(op==7u){",
    "if(op==8u){",
    "if(op==9u){",
    "if(op==10u){",
    "if(op==11u){",
    "if(op==12u){",
    "if(op==13u){",
    "if(op==14u){",
    "if(op==15u){",
];
const GROUPS: [&[u32]; 14] = [
    &[0, 1],
    &[2, 3],
    &[4],
    &[5],
    &[6],
    &[7],
    &[8],
    &[9],
    &[10],
    &[11],
    &[12],
    &[13],
    &[14],
    &[15],
];
const MAIN_PREFIX: &str = "fn main(@builtin(global_invocation_id) gid:vec3<u32>){\n let local=gid.x+gid.y*65535u*64u;\n if(local>=p.u[1].x){return;}let i=local+p.u[0].w;let w=p.u[0].x;let h=p.u[0].y;let op=p.u[0].z;\n ";

// These are the only plane routes emitted by Runner::blur. Keep this table
// shared with host admission rather than inferring routes from shader text.
pub(crate) fn iir_plane_route(op: u32) -> Option<(u32, u32)> {
    match op {
        2 => Some((0, 1)),
        3 => Some((1, 2)),
        _ => None,
    }
}
const IIR_ACCESSORS: [(&str, &str, usize); 5] = [
    ("get_value(p.u[1].z,base)", "iir_source(base)", 1),
    ("get_value(p.u[1].z,index)", "iir_source(index)", 1),
    (
        "get_value(p.u[1].w,base+(n-1u)*stride)",
        "iir_destination(base+(n-1u)*stride)",
        1,
    ),
    ("get_value(p.u[1].w,index)", "iir_destination(index)", 1),
    ("put_value(p.u[1].w,index,v)", "iir_store(index,v)", 2),
];
fn iir_admission(op: u32) -> R<String> {
    let (source, destination) = iir_plane_route(op).ok_or(ERROR)?;
    Ok(format!(
        "if(p.u[1].z!={source}u || p.u[1].w!={destination}u){{atomicOr(&tail_numeric_error.value,1u);return;}}\n  "
    ))
}
fn specialize_iir(main: &str, op: u32, functions: &mut BTreeMap<String, String>) -> R<String> {
    let (source, destination) = match op {
        2 => ("source", "horizontal"),
        3 => ("horizontal", "blurred"),
        _ => return fail(),
    };
    // Accessor semantics are also versioned: call-site replacement must never
    // silently discard a future guard or other side effect inside get/put.
    for (name, expected) in [
        (
            "get_value",
            "fn get_value(which:u32,i:u32)->f32 {\n switch which {case 0u:{return source.values[i];}case 1u:{return horizontal.values[i];}case 2u:{return blurred.values[i];}case 3u:{return saved.values[i];}default:{return accum.values[i];}}\n}",
        ),
        (
            "put_value",
            "fn put_value(which:u32,i:u32,v:f32){\n switch which {case 0u:{source.values[i]=v;}case 1u:{horizontal.values[i]=v;}case 2u:{blurred.values[i]=v;}case 3u:{saved.values[i]=v;}default:{accum.values[i]=v;}}\n}",
        ),
    ] {
        if functions.get(name).map(String::as_str) != Some(expected) {
            return fail();
        }
    }
    let mut selected = main.to_owned();
    let header = HEADERS[1];
    if selected.matches(header).count() != 1 {
        return fail();
    }
    selected = selected.replace(
        header,
        &format!("if(op=={op}u){{\n  {}", iir_admission(op)?),
    );
    for (old, new, count) in IIR_ACCESSORS {
        if selected.matches(old).count() != count {
            return fail();
        }
        selected = selected.replace(old, new);
    }
    // No remaining dynamic plane access may leak into either recurrence.
    if selected.contains("get_value(") || selected.contains("put_value(") {
        return fail();
    }
    for (name, body) in [
        (
            "iir_source",
            format!("fn iir_source(i:u32)->f32 {{return {source}.values[i];}}"),
        ),
        (
            "iir_destination",
            format!("fn iir_destination(i:u32)->f32 {{return {destination}.values[i];}}"),
        ),
        (
            "iir_store",
            format!("fn iir_store(i:u32,v:f32){{{destination}.values[i]=v;}}"),
        ),
    ] {
        if functions.insert(name.into(), body).is_some() {
            return fail();
        }
    }
    Ok(selected)
}

fn assemble(reference: &str, ops: &[u32]) -> R<String> {
    if !FAMILY_OPS.contains(&ops) {
        return fail();
    }
    let (declarations, mut functions) = functions(reference)?;
    let main = functions.get("main").ok_or(ERROR)?;
    // Fail closed on dispatch setup changes as well as new/reordered branches.
    if !main.starts_with(MAIN_PREFIX) || !main[MAIN_PREFIX.len()..].starts_with(HEADERS[0]) {
        return fail();
    }
    let keep = GROUPS
        .iter()
        .map(|group| group.iter().any(|op| ops.contains(op)))
        .collect::<Vec<_>>();
    let mut selected = select(main, HEADERS[0], &HEADERS, &keep)?;
    if ops == [2] || ops == [3] {
        selected = specialize_iir(&selected, ops[0], &mut functions)?;
    }
    functions.insert("main".into(), selected);
    let mut reachable = BTreeSet::from(["main".to_owned()]);
    loop {
        let mut next = reachable.clone();
        for name in &reachable {
            for call in identifiers(functions.get(name).ok_or(ERROR)?)? {
                if functions.contains_key(&call) {
                    next.insert(call);
                }
            }
        }
        if next == reachable {
            break;
        }
        reachable = next;
    }
    let mut output = declarations;
    for name in reachable {
        if name == "main" {
            output.push_str("\n@compute @workgroup_size(64)\n");
        }
        output.push_str(functions.get(&name).ok_or(ERROR)?);
        output.push('\n');
    }
    Ok(output)
}

// Selection avoids compiling families this request cannot dispatch. Both IIR
// axes are selected together, with no per-sigma or per-channel pipelines.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Selection([bool; MAX_FAMILIES]);
impl Selection {
    fn point(density: bool) -> Self {
        Self([false, false, false, true, density])
    }
    fn add_sigma(&mut self, sigma: f32) -> R<()> {
        match super::Filter::new(sigma)? {
            super::Filter::Identity => {}
            super::Filter::Fir(_) => self.0[0] = true,
            super::Filter::Iir(_) => {
                self.0[1] = true;
                self.0[2] = true;
            }
        }
        Ok(())
    }
    fn optics(p: &super::FilmOpticsPlan) -> R<Self> {
        let mut selected = Self::point(false);
        for channel in &p.channels {
            // This existing plan enumeration includes lens, scatter core, each
            // original f64 FIT ratio cast to f32, and every halation bounce.
            for sigma in channel.sigmas() {
                selected.add_sigma(sigma)?;
            }
        }
        Ok(selected)
    }
    fn dir(active: bool, core_sigma: f32, tail_decay: f32) -> R<Self> {
        let mut selected = Self::point(true);
        // DIR's memory-only spatial plan has empty channels. Mirror the actual
        // DIR blur gate instead, including the original FIT cast/order.
        if active && core_sigma > 0. {
            selected.add_sigma(core_sigma)?;
            for (_, ratio) in super::FIT {
                selected.add_sigma((ratio * tail_decay as f64) as f32)?;
            }
        }
        Ok(selected)
    }
    fn shaders(self) -> R<Vec<(Vec<u32>, String)>> {
        let source = super::guarded::shader()?;
        FAMILY_OPS
            .iter()
            .zip(self.0)
            .filter(|(_, keep)| *keep)
            .map(|(ops, _)| Ok((ops.to_vec(), assemble(&source, ops)?)))
            .collect()
    }
}

pub(crate) fn shaders_for_optics(p: &super::FilmOpticsPlan) -> R<Vec<(Vec<u32>, String)>> {
    Selection::optics(p)?.shaders()
}
pub(crate) fn shaders_for_dir(
    active: bool,
    core_sigma: f32,
    tail_decay: f32,
) -> R<Vec<(Vec<u32>, String)>> {
    Selection::dir(active, core_sigma, tail_decay)?.shaders()
}
#[cfg(test)]
pub(crate) fn shaders() -> R<Vec<(Vec<u32>, String)>> {
    Selection([true; MAX_FAMILIES]).shaders()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn optics_plan() -> super::super::FilmOpticsPlan {
        super::super::FilmOpticsPlan {
            width: 1,
            height: 1,
            channels: std::array::from_fn(|_| super::super::ChannelPlan {
                lens_sigma: 0.,
                scatter: None,
                bounce_sigmas: vec![],
                bounce_weights: vec![],
                strength: 0.,
                divisor: 1.,
            }),
            device_bytes: 0,
            host_bytes: 0,
            driver_allowance_bytes: 0,
            helper_allowance_bytes: 0,
            combined_peak_bytes: 0,
            disk_bytes: 0,
        }
    }

    #[test]
    fn request_selection_keeps_exact_original_shaders_and_routes() {
        let all = shaders().unwrap();
        for fir in [false, true] {
            for iir in [false, true] {
                for density in [false, true] {
                    let selection = Selection([fir, iir, iir, true, density]);
                    let selected = selection.shaders().unwrap();
                    assert_eq!(
                        selected.len(),
                        1 + fir as usize + 2 * iir as usize + density as usize
                    );
                    let expected: Vec<_> = all
                        .iter()
                        .zip(selection.0)
                        .filter(|(_, keep)| *keep)
                        .map(|(family, _)| family.clone())
                        .collect();
                    assert_eq!(selected, expected);
                    let ops: BTreeSet<_> = selected
                        .iter()
                        .flat_map(|(ops, _)| ops.iter().copied())
                        .collect();
                    for op in 0..16 {
                        let family = FAMILY_OPS.iter().position(|ops| ops.contains(&op)).unwrap();
                        assert_eq!(ops.contains(&op), selection.0[family]);
                    }
                    assert!(!ops.contains(&16));
                }
            }
        }
    }

    #[test]
    fn optics_selection_covers_identity_fir_iir_and_mixed_channels() {
        let mut p = optics_plan();
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [false, false, false, true, false]
        );
        for (sigma, expected) in [
            (f32::MIN_POSITIVE, [true, false, false, true, false]),
            (2.999, [true, false, false, true, false]),
            (3., [false, true, true, true, false]),
        ] {
            p.channels[0].lens_sigma = sigma;
            assert_eq!(Selection::optics(&p).unwrap().0, expected);
        }
        p.channels[1].lens_sigma = 0.5;
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [true, true, true, true, false]
        );
        p.channels[2].lens_sigma = f32::NAN;
        assert!(Selection::optics(&p).is_err());
    }

    #[test]
    fn optics_selection_covers_scatter_tails_and_bounces_crossing_three() {
        let mut p = optics_plan();
        p.channels[1].scatter = Some(super::super::Scatter {
            sigma: 0.,
            decay: 0.,
            amount: 1.,
            tail_weight: 0.5,
        });
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [false, false, false, true, false]
        );
        p.channels[1].scatter.as_mut().unwrap().decay = 1.;
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [true, false, false, true, false]
        );
        // Core remains identity, but the third original FIT tail exceeds 3.
        p.channels[1].scatter.as_mut().unwrap().decay = 1.1;
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [true, true, true, true, false]
        );
        p.channels[1].scatter.as_mut().unwrap().decay = 6.;
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [false, true, true, true, false]
        );
        p.channels[1].scatter.as_mut().unwrap().sigma = 0.5;
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [true, true, true, true, false]
        );
        p.channels[1].scatter = None;
        p.channels[2].bounce_sigmas = vec![0., 2.999, 3.];
        p.channels[2].bounce_weights = vec![0.2, 0.3, 0.5];
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [true, true, true, true, false]
        );
        p.channels[2].bounce_sigmas = vec![0., 3.];
        p.channels[2].bounce_weights = vec![0.5, 0.5];
        assert_eq!(
            Selection::optics(&p).unwrap().0,
            [false, true, true, true, false]
        );
    }

    #[test]
    fn dir_selection_uses_actual_gate_not_empty_spatial_channels() {
        for (active, core, decay, expected) in [
            (false, 3., 6., [false, false, false, true, true]),
            (false, f32::NAN, f32::NAN, [false, false, false, true, true]),
            (true, 0., 6., [false, false, false, true, true]),
            (true, 0.5, 0., [true, false, false, true, true]),
            (true, 0.5, 1., [true, false, false, true, true]),
            (true, 0.5, 1.1, [true, true, true, true, true]),
            (true, 3., 0., [false, true, true, true, true]),
            (true, 3., 6., [false, true, true, true, true]),
        ] {
            assert_eq!(Selection::dir(active, core, decay).unwrap().0, expected);
        }
        assert!(Selection::dir(true, 0.5, f32::NAN).is_err());
    }

    fn branches(main: &str) -> BTreeMap<String, String> {
        let masked = mask(main).unwrap();
        let mut result = BTreeMap::new();
        let mut cursor = MAIN_PREFIX.len();
        while let Some(relative) = masked[cursor..].find("if(op==") {
            let start = cursor + relative;
            let open = masked[start..].find('{').unwrap() + start;
            let end = close(&masked, open).unwrap();
            result.insert(main[start..=open].to_owned(), main[start..end].to_owned());
            cursor = end;
        }
        result
    }

    #[test]
    fn all_families_validate_with_exact_bodies_and_eight_bindings() {
        let reference = super::super::guarded::shader().unwrap();
        let (original_declarations, original) = functions(&reference).unwrap();
        let original_branches = branches(&original["main"]);
        let all = shaders().unwrap();
        assert_eq!(all.len(), MAX_FAMILIES);
        for (ops, source) in &all {
            let module = naga::front::wgsl::parse_str(source)
                .unwrap_or_else(|e| panic!("{ops:?}: {}", e.emit_to_string(source)));
            naga::valid::Validator::new(
                naga::valid::ValidationFlags::all(),
                naga::valid::Capabilities::empty(),
            )
            .validate(&module)
            .unwrap();
            assert_eq!(module.entry_points.len(), 1);
            assert_eq!(module.entry_points[0].workgroup_size, [64, 1, 1]);
            let bindings = module
                .global_variables
                .iter()
                .filter_map(|(_, v)| v.binding.as_ref())
                .map(|b| (b.group, b.binding))
                .collect::<BTreeSet<_>>();
            assert_eq!(bindings, (0..8).map(|b| (0, b)).collect());
            let (_, selected) = functions(source).unwrap();
            // The original declaration block is emitted untouched, not rebuilt.
            assert!(source.starts_with(&original_declarations));
            for (name, body) in &selected {
                if name != "main" && !name.starts_with("iir_") {
                    assert_eq!(Some(body), original.get(name), "{ops:?}: {name}");
                }
            }
            let mut restored = selected["main"].clone();
            if ops.as_slice() == [2] || ops.as_slice() == [3] {
                let op = ops[0];
                let special = format!("if(op=={op}u){{\n  {}", iir_admission(op).unwrap());
                assert_eq!(restored.matches(&special).count(), 1);
                restored = restored.replacen(&special, HEADERS[1], 1);
                for (old, new, count) in IIR_ACCESSORS {
                    assert_eq!(restored.matches(new).count(), count);
                    restored = restored.replace(new, old);
                }
                assert!(!source.contains("switch which"));
                assert!(!selected.contains_key("get_value"));
                assert!(!selected.contains_key("put_value"));
                let source_plane = if op == 2 { "source" } else { "horizontal" };
                let destination_plane = if op == 2 { "horizontal" } else { "blurred" };
                assert_eq!(
                    selected["iir_source"],
                    format!("fn iir_source(i:u32)->f32 {{return {source_plane}.values[i];}}")
                );
                assert_eq!(
                    selected["iir_destination"],
                    format!(
                        "fn iir_destination(i:u32)->f32 {{return {destination_plane}.values[i];}}"
                    )
                );
                assert_eq!(
                    selected["iir_store"],
                    format!("fn iir_store(i:u32,v:f32){{{destination_plane}.values[i]=v;}}")
                );
                let keep = GROUPS.iter().map(|g| g.contains(&op)).collect::<Vec<_>>();
                assert_eq!(
                    restored,
                    select(&original["main"], HEADERS[0], &HEADERS, &keep).unwrap()
                );
            }
            let selected_branches = branches(&restored);
            for (header, group) in HEADERS.iter().zip(GROUPS) {
                let expected = group.iter().any(|op| ops.contains(op));
                assert_eq!(selected_branches.contains_key(*header), expected);
                if expected {
                    assert_eq!(selected_branches[*header], original_branches[*header]);
                }
            }
            assert!(source.contains("atomicOr(&tail_numeric_error.value,1u)"));
            assert!(source.len() < reference.len());
        }
        assert!(all[0].1.contains("fn reflect_index("));
        assert!(!all[1].1.contains("fn reflect_index("));
        // Complete forward/backward line recurrences survived exact extraction.
        assert!(all[1].1.contains("for(var j=0u;j<n;j=j+1u)"));
        assert!(all[1].1.contains("for(var k=0u;k<n;k=k+1u)"));
        for (_, source) in &all[..4] {
            assert!(!source.contains("fn density_value("));
        }
        assert!(all[4].1.contains(&original["density_value"]));
    }

    #[test]
    fn fixed_iir_routes_and_source_drift_are_closed() {
        assert_eq!(iir_plane_route(2), Some((0, 1)));
        assert_eq!(iir_plane_route(3), Some((1, 2)));
        for op in [0, 1, 4, 15, u32::MAX] {
            assert_eq!(iir_plane_route(op), None);
        }
        let reference = super::super::guarded::shader().unwrap();
        for (old, new) in [
            ("get_value(p.u[1].z,base)", "get_value(p.u[1].w,base)"),
            ("get_value(p.u[1].z,index)", "get_value(0u,index)"),
            ("get_value(p.u[1].w,index)", "get_value(1u,index)"),
            ("put_value(p.u[1].w,index,v)", "put_value(1u,index,v)"),
            (
                "return source.values[i];",
                "return tbound(source.values[i]);",
            ),
            ("source.values[i]=v;", "source.values[i]=tbound(v);"),
        ] {
            assert!(reference.contains(old));
            for ops in [&[2][..], &[3][..]] {
                assert!(
                    assemble(&reference.replace(old, new), ops).is_err(),
                    "{old}"
                );
            }
        }
        for op in [2, 3] {
            let source = assemble(&reference, &[op]).unwrap();
            let guard = iir_admission(op).unwrap();
            assert_eq!(source.matches(&guard).count(), 1);
            assert!(source.find(&guard).unwrap() < source.find("var s1=").unwrap());
            assert!(source.contains("for(var j=0u;j<n;j=j+1u)"));
            assert!(source.contains("for(var k=0u;k<n;k=k+1u)"));
        }
    }

    #[test]
    fn coverage_is_exact_and_drift_fails_closed() {
        let reference = super::super::guarded::shader().unwrap();
        let mut seen = BTreeSet::new();
        for ops in FAMILY_OPS {
            for op in ops {
                assert!(seen.insert(*op));
            }
        }
        assert_eq!(seen, (0..16).collect());
        for unknown in [
            &[][..],
            &[16][..],
            &[0][..],
            &[1, 0][..],
            &[0, 1, 2][..],
            &[2, 3][..],
        ] {
            assert!(assemble(&reference, unknown).is_err());
        }
        for (old, new) in [
            ("if(op==15u){", "if(op==16u){"),
            ("if(op==0u || op==1u){", "if(op==0u){"),
            ("let op=p.u[0].z;", "let op=p.u[0].y;"),
            (
                "@compute @workgroup_size(64)",
                "@compute @workgroup_size(32)",
            ),
            ("if(op==5u){", "accum.values[i]=1.0; if(op==5u){"),
        ] {
            assert!(reference.contains(old));
            for ops in FAMILY_OPS {
                assert!(
                    assemble(&reference.replace(old, new), ops).is_err(),
                    "{old}"
                );
            }
        }
        assert!(
            assemble(
                &(reference.clone() + "\nfn tfail()->f32{return 0.0;}"),
                FAMILY_OPS[0]
            )
            .is_err()
        );
        assert!(assemble(&(reference + "\n/* unterminated"), FAMILY_OPS[0]).is_err());
    }
}
