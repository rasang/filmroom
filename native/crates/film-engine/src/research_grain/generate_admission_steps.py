"""Emit a tiny Rust generator; it runs the original guarded CPU step oracle.
No Cargo, compilation or approximation is performed by this script.
Usage: python3 generate_admission_steps.py /tmp/grain-step-generator.rs
Compile emitted source against the existing spektrafilm_math rlib, then run it
with the output admission_steps.rs path. All fixtures must pass before writing.
"""
import pathlib,sys
ROOT=pathlib.Path(__file__).resolve().parent
from prepared_shader import function
case='pub '+function((ROOT/'guarded_graph.rs').read_text(),'diagnostic_case')
lines=['extern crate spektrafilm_math;','mod research_grain {','pub type R<T> = Result<T,String>;','pub const LOG_FACTORIAL_COUNT:u32=262144;pub const MAX_LAMBDA:f32=240000.;','pub mod guarded_graph {pub const MAX_RATE:f64=15000000.;pub const MAX_COUNT:u64=16777216;'+case+'}']
for module in ['sampler','soft64','preview_rng','guarded_sampler']:
 lines.append(f'#[path="{ROOT/module}.rs"] pub mod {module};')
lines+=['}',r'''
fn main(){
 let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();let mut values=Vec::with_capacity(3120);let mut fingerprint=14695981039346656037u64;
 for i in 0..3120u32{let(seed,x,y,lam,p)=research_grain::guarded_graph::diagnostic_case(i);for byte in seed.to_le_bytes().into_iter().chain(x.to_le_bytes()).chain(y.to_le_bytes()).chain(lam.to_bits().to_le_bytes()).chain(p.to_bits().to_le_bytes()){fingerprint=(fingerprint^byte as u64).wrapping_mul(1099511628211);}
 let steps=research_grain::guarded_sampler::particle_steps_reference(seed,x,y,lam,p,&table).unwrap_or_else(|e|panic!("fixture {i} rejected: {e:?}"));assert!(steps<=4096);values.push(steps);}
 let mut out=format!("// Fixture-order fingerprint, not a security digest.\npub const ADMISSION_FIXTURE_FNV64:u64={fingerprint};\n");out.push_str("// Offline-generated from the ORIGINAL guarded CPU generic sampler.\n// Regenerate with generate_admission_steps.py; full recomputation is a test gate.\npub const ADMISSION_STEPS:[u32;3120]=[\n");
 for row in values.chunks(24){out.push_str(" ");for value in row{out.push_str(&format!("{value},"));}out.push('\n');}out.push_str("];\n");
 let path=std::env::args().nth(1).expect("output admission_steps.rs path required");std::fs::write(path,out).unwrap();
 eprintln!("Generated3120 original-generic step goldens; min={} max={}",values.iter().min().unwrap(),values.iter().max().unwrap());
}
''']
pathlib.Path(sys.argv[1]).write_text('\n'.join(lines))
