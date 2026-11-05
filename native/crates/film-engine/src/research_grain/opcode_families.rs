//! Bounded pipelines extracted from the reference graph, not parallel science.
//! Only dispatcher selection changes. Every reachable scientific function is
//! copied byte-for-byte, including sticky guards and persistent MT operations.
use super::R;
use std::collections::{BTreeMap, BTreeSet};

pub const FAMILY_OPS: [&[u32]; 8] = [
    &[0, 1, 2, 3, 4, 5, 10, 11, 15, 56, 57, 58, 59, 60],
    &[27, 40, 41], &[50, 62], &[54, 55], &[63], &[66], &[52, 65], &[53, 64],
];
pub const MAX_FAMILIES: usize = FAMILY_OPS.len();
const ERROR: &str = "GPU_GRAIN_SHADER_VERSION";
fn fail<T>() -> R<T> { Err(ERROR.into()) }

// Comment masking retains byte positions. Source outside ASCII, strings, or
// nested block comments is rejected: this deliberately small extractor must
// not guess after a shader grammar change. WGSL has no string expressions.
fn mask(s: &str) -> R<String> {
    let mut b=s.as_bytes().to_vec(); let mut i=0;
    while i<b.len() {
        if b[i]==b'"' { return fail(); }
        if b[i..].starts_with(b"//") {
            while i<b.len() && b[i]!=b'\n' {b[i]=b' ';i+=1;}
        } else if b[i..].starts_with(b"/*") {
            let start=i;i+=2;
            while i+1<b.len() && !b[i..].starts_with(b"*/") {
                if b[i..].starts_with(b"/*") {return fail();} i+=1;
            }
            if i+1>=b.len(){return fail();} i+=2;
            for v in &mut b[start..i] {if *v!=b'\n'{*v=b' ';}}
        } else {i+=1;}
    }
    if !b.is_ascii() { return fail(); }
    String::from_utf8(b).map_err(|_|ERROR.into())
}
fn close(s:&str, start:usize)->R<usize>{
    if s.as_bytes().get(start)!=Some(&b'{'){return fail();}
    let mut depth=0;
    for(i,b)in s.bytes().enumerate().skip(start){
        if b==b'{'{depth+=1;}else if b==b'}'{depth-=1;if depth==0{return Ok(i+1);}}
    }fail()
}
fn identifiers(s:&str)->R<BTreeSet<String>> {
    Ok(mask(s)?.split(|c:char|!(c.is_ascii_alphanumeric()||c=='_'))
        .filter(|s|!s.is_empty()).map(str::to_owned).collect())
}
fn functions(s:&str)->R<(String,BTreeMap<String,String>)>{
    let m=mask(s)?;let mut declarations=String::new();let mut found=BTreeMap::new();let mut cursor=0;
    while let Some(relative)=m[cursor..].find("fn ") {
        let start=cursor+relative;
        if start>0 && !m.as_bytes()[start-1].is_ascii_whitespace(){return fail();}
        let name_end=m[start+3..].find('(').ok_or(ERROR)?+start+3;
        let name=m[start+3..name_end].trim();
        if name.is_empty()||!name.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'_'){return fail();}
        let open=m[name_end..].find('{').ok_or(ERROR)?+name_end;let end=close(&m,open)?;
        declarations.push_str(&s[cursor..start]);
        if found.insert(name.to_owned(),s[start..end].to_owned()).is_some(){return fail();}
        cursor=end;
    }
    declarations.push_str(&s[cursor..]);
    if declarations.matches("@compute @workgroup_size(64)").count()!=1{return fail();}
    declarations=declarations.replace("@compute @workgroup_size(64)","");
    if mask(&declarations)?.contains("fn "){return fail();}
    Ok((declarations,found))
}
// Branch headers are a versioned allowlist. Comments and bodies survive verbatim;
// unexpected statements/branches after the dispatch header fail closed.
fn select(source:&str,first:&str,expected:&[&str],keep:&[bool])->R<String>{
    if expected.len()!=keep.len()||source.matches(first).count()!=1{return fail();}
    let m=mask(source)?;let start=m.find(first).ok_or(ERROR)?;
    let mut output=source[..start].to_owned();let mut cursor=start;
    for (header,retain) in expected.iter().zip(keep) {
        let next=m[cursor..].find(|c:char|!c.is_ascii_whitespace()).ok_or(ERROR)?+cursor;
        if !m[next..].starts_with(header){return fail();}
        let open=next+header.len()-1;let end=close(&m,open)?;
        if *retain {output.push_str(&source[cursor..end]);output.push('\n');}
        cursor=end;
    }
    if m[cursor..].trim()!="}"{return fail();}
    output.push_str("\n atomicOr(&tail_numeric_error.value,1u);\n}");Ok(output)
}
fn has(ops:&[u32], subset:&[u32])->bool{subset.iter().any(|op|ops.contains(op))}
fn subset_headers(source:&str,first:&str,headers:&[&str],groups:&[&[u32]],ops:&[u32])->R<String>{
    select(source,first,headers,&groups.iter().map(|g|has(ops,g)).collect::<Vec<_>>())
}
fn dispatchers(f:&mut BTreeMap<String,String>,ops:&[u32])->R<()> {
    let main_headers=["if(op==27u){","if(op==40u||op==41u){","if(op>=50u){","if(op==0u || op==1u){","if(op==2u || op==3u){","if(op==4u){","if(op==5u){","if(op==6u){","if(op==7u){","if(op==8u){","if(op==9u){","if(op==10u){","if(op==11u){","if(op==12u){","if(op==13u){","if(op==14u){","if(op==15u){"];
    let main_groups:&[&[u32]]=&[&[27],&[40,41],&[50,52,53,54,55,56,57,58,59,60,62,63,64,65,66],&[0,1],&[2,3],&[4],&[5],&[6],&[7],&[8],&[9],&[10],&[11],&[12],&[13],&[14],&[15]];
    let main=subset_headers(f.get("main").ok_or(ERROR)?,main_headers[0],&main_headers,main_groups,ops)?;f.insert("main".into(),main);
    let grain_headers=["if(op==20u){","if(op==21u){","if(op==22u){","if(op==23u){","if(op==24u){","if(op==25u){","if(op==26u){","if(op==27u){","if(op==28u){"];
    let grain=select(f.get("grain_main").ok_or(ERROR)?,grain_headers[0],&grain_headers,&[false,false,false,false,false,false,false,ops.contains(&27),false])?;f.insert("grain_main".into(),grain);
    let headers=["if(op==50u){","if(op==52u||op==65u){","if(op==53u||op==64u){","if(op==54u){","if(op==55u){","if(op==56u){","if(op==57u){","if(op==58u){","if(op==59u){","if(op==60u){","if(op==63u){","if(op==66u){","if(op==62u){"];
    let groups:&[&[u32]]=&[&[50],&[52,65],&[53,64],&[54],&[55],&[56],&[57],&[58],&[59],&[60],&[63],&[66],&[62]];
    let grain=subset_headers(f.get("guarded_grain_main").ok_or(ERROR)?,headers[0],&headers,groups,ops)?;
    f.insert("guarded_grain_main".into(),grain);Ok(())
}

fn assemble(reference:&str,ops:&[u32])->R<String>{
    if !FAMILY_OPS.iter().any(|known|*known==ops)&&ops!=[54]&&ops!=[55]{return fail();}
    let (declarations,mut f)=functions(reference)?;dispatchers(&mut f,ops)?;
    let mut reachable=BTreeSet::from(["main".to_owned()]);
    loop{let mut next=reachable.clone();for name in &reachable{for call in identifiers(f.get(name).ok_or(ERROR)?)?{if f.contains_key(&call){next.insert(call);}}}if next==reachable{break;}reachable=next;}
    let mut output=declarations;
    for name in reachable {if name=="main"{output.push_str("\n@compute @workgroup_size(64)\n");}output.push_str(f.get(&name).ok_or(ERROR)?);output.push('\n');}
    Ok(output)
}
pub fn shaders()->R<Vec<(Vec<u32>,String)>>{
    let source=super::shader()?;
    FAMILY_OPS.iter().map(|ops|Ok((ops.to_vec(),assemble(&source,ops)?))).collect()
}
/// Compile only the closed production/admission routes. Both coordinate sampler
/// consumers remain required by preview admission, sharing one scientific call.
pub fn shaders_for_plan(plan:&super::GrainPlan)->R<Vec<(Vec<u32>,String)>>{
    let source=super::shader()?;let mut out=Vec::new();
    for (index,ops) in FAMILY_OPS.iter().enumerate(){
        if index==3 {
            if !plan.micro_active{continue;}
            let selected=if plan.rng_mode==super::RngMode::SequentialMt19937{vec![55]}else{vec![54]};
            out.push((selected.clone(),assemble(&source,&selected)?));
        }else{out.push((ops.to_vec(),assemble(&source,ops)?));}
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]fn all_families_validate_and_keep_exact_science(){
        let reference=super::super::shader().unwrap();let(_,original)=functions(&reference).unwrap();
        let all=shaders().unwrap();assert_eq!(all.len(),MAX_FAMILIES);
        for(ops,s)in &all{
            let m=naga::front::wgsl::parse_str(s).unwrap_or_else(|e|panic!("{ops:?}: {}",e.emit_to_string(s)));
            naga::valid::Validator::new(naga::valid::ValidationFlags::all(),naga::valid::Capabilities::empty()).validate(&m).unwrap();
            assert_eq!(m.entry_points.len(),1);
            assert_eq!(m.global_variables.iter().filter(|(_,v)|v.binding.is_some()).count(),9);
            let(_,selected)=functions(s).unwrap();
            for(name,body)in &selected{if !["main","grain_main","guarded_grain_main"].contains(&name.as_str()){assert_eq!(Some(body),original.get(name),"{ops:?}: {name}");}}
            // Independently walk the emitted graph: every retained function is
            // reachable, and every reference to an original helper resolves.
            let mut reached=BTreeSet::from(["main".to_owned()]);
            loop {
                let mut next=reached.clone();
                for name in &reached {
                    for token in identifiers(&selected[name]).unwrap() {
                        if original.contains_key(&token) {
                            assert!(selected.contains_key(&token), "missing helper {token}");
                            next.insert(token);
                        }
                    }
                }
                if next==reached {break;} reached=next;
            }
            assert_eq!(reached,selected.keys().cloned().collect());
            for absent in ["grain_poisson","grain_binomial","grain_sample","density_value"]{assert!(!selected.contains_key(absent));}
            assert!(s.len()<reference.len());
        }
        // Admission and production share exactly one reachable sampler call.
        for (index,name) in [(6,"sr_poisson_prepared"),(7,"sr_binomial_prepared"),(4,"sr_prepare_poisson"),(5,"sr_prepare_binomial")]{
            assert!(all[index].1.contains(&original[name]));
        }
        assert!(!all[6].1.contains("fn sr_binomial_prepared("));
        assert!(!all[7].1.contains("fn sr_poisson_prepared("));
        assert!(!all[4].1.contains("fn sr_binomial_prepared("));
        assert!(!all[5].1.contains("fn sr_poisson_prepared("));
        assert_eq!(all[6].1.matches("sr_poisson_prepared(&rng").count(),1);
        assert_eq!(all[7].1.matches("sr_binomial_prepared(&rng").count(),1);
    }
    #[test]fn comment_mask_preserves_unicode_comment_byte_offsets(){
        let source="// q≈1\nfn main(){} /* λ */";
        let masked=mask(source).unwrap();assert_eq!(masked.len(),source.len());assert!(masked.is_ascii());
        assert_eq!(masked.find("fn main"),source.find("fn main"));
        assert!(mask("fn λ(){}").is_err());
    }
    #[test]fn closed_opcode_set_and_drift_rejection(){
        let source=super::super::shader().unwrap();let mut seen=BTreeSet::new();
        for ops in FAMILY_OPS {for op in ops {assert!(seen.insert(*op));}}
        let expected:BTreeSet<u32>=[0,1,2,3,4,5,10,11,15,27,40,41,50,52,53,54,55,56,57,58,59,60,62,63,64,65,66].into_iter().collect();assert_eq!(seen,expected);
        for unknown in [6,20,26,28,42,49,51,61,67,u32::MAX]{assert!(assemble(&source,&[unknown]).is_err());}
        assert!(assemble(&(source.clone()+"\nfn main() {}"),FAMILY_OPS[0]).is_err());
        assert!(assemble(&source.replace("if(op==60u){", "let unexpected=1u; if(op==60u){"),FAMILY_OPS[0]).is_err());
        assert!(assemble(&source.replace("if(op==60u){", "if(op==60u){ /* nested /* bad */ */"),FAMILY_OPS[0]).is_err());
        for (old,new) in [("if(op==60u){","if(op==67u){"),("if(op==52u||op==65u){","if(op==99u){"),("@compute @workgroup_size(64)","@compute @workgroup_size(32)")]{
            let drift=source.replace(old,new);assert!(assemble(&drift,FAMILY_OPS[5]).is_err(),"{old}");
        }
    }
}
