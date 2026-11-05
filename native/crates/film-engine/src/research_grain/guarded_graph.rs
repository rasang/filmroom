//! Guarded sampler graph construction. Runtime routing remains separate until
//! device validation and the v2 private-manifest contract are enabled together.
use super::*;
/// Phased production-graph software-Vulkan gates, ARM type checking and source
/// review passed. Physical Adreno behavior still requires runtime validation.
/// Full per-device diagnostics remain mandatory; this is not a speed guarantee.
pub const SOURCE_ADMITTED:bool=true;
pub const PHASED_SOURCE_VERSION:u32=1;
pub const PHASED_GRAPH_SHA256:&str="2729a8ad8b16459527d939f19fe8c30c73e92e7b098edf2cfb829dcc09a780b6";
pub const MAX_RATE:f64=15000000.;
pub const MAX_COUNT:u64=16777216;
pub const FACTORIAL_BASE:u32=81920;
pub const TABLE_BASE:u32=868352;
pub const CHUNK_BASE:u32=978944;
pub const PARAM_BASE:u32=1028096;
pub const COORDINATE_CHUNK:u32=16384;
pub const PREVIEW_CHUNK:u32=256;
pub const SAMPLER_CHECKS:u32=3120;
include!("admission_steps.rs");
pub const PREPASS_CHECKS:u32=32;
pub const BOUNDARY_CHECKS:u32=96;
pub const PREPASS_RATES:[(f64,f64);2]=[(2300.,0.97),(0.01,0.97)];
pub const ARITHMETIC_CASES:u32=256;
pub const GUARDED_SHADER:&str=include_str!("../shaders/research_grain_guarded_sampler.wgsl");
pub const GRAPH_SHADER:&str=include_str!("../shaders/research_grain_guarded_graph.wgsl");
#[derive(Clone,Copy,Debug,PartialEq,Eq,Serialize,Deserialize)]
#[serde(rename_all="camelCase")]
pub enum RngMode {CoordinateSplitMix64,SequentialMt19937}
impl RngMode {pub fn chunk_pixels(self,n:u64)->u32{n.min(match self{Self::CoordinateSplitMix64=>COORDINATE_CHUNK,Self::SequentialMt19937=>PREVIEW_CHUNK}as u64)as u32}}
// Batch queue submissions, never increase serial sampler pixels/dispatch.
pub const SEQUENTIAL_BATCH_CHUNKS:u32=16;
/// Additional admission reservation; never borrowed from the unknown-driver reserve.
pub const BATCH_RESERVATION_BYTES:u64=4*MIB;
/// Conservative additional driver allowance for eight resident pipelines.
/// Policy reservation, not an empirically established driver-memory bound.
pub const PIPELINE_RESERVATION_BYTES:u64=64*MIB;
pub const PREPARED_STRIDE:u32=72;
pub const CODE_HOST_RESERVATION_BYTES:u64=9600;
pub const INPUT_PRODUCTION:u32=0;
pub const INPUT_CENSUS:u32=1;
pub const INPUT_BOUNDARY:u32=2;
pub const INPUT_ZERO:u32=3;
pub const INPUT_PREPASS:u32=4;
pub const ZERO_CHECKS:u32=96;
pub fn frame_capacity(mode:RngMode)->u32{match mode{RngMode::SequentialMt19937=>PREVIEW_CHUNK,RngMode::CoordinateSplitMix64=>COORDINATE_CHUNK}}
pub fn scratch_bytes(mode:RngMode)->R<u64>{super::alu_programs::allocation_bytes(frame_capacity(mode))}
pub fn diagnostic_dispatches(mode:RngMode)->u64{25+4*(SAMPLER_CHECKS as u64).div_ceil(frame_capacity(mode)as u64)}
pub fn submission_batch_chunks(mode:RngMode)->u32{match mode{RngMode::SequentialMt19937=>SEQUENTIAL_BATCH_CHUNKS,RngMode::CoordinateSplitMix64=>1}}
#[derive(Clone,Copy)]pub struct Table {pub axis:u32,pub values:u32,pub knots:u32}
pub fn encode(values:impl IntoIterator<Item=f64>)->Vec<u8>{values.into_iter().flat_map(|v|{let b=v.to_bits();[(b&0xffffff)as f32,((b>>24)&0xffffff)as f32,(b>>48)as f32].into_iter().flat_map(|x|x.to_le_bytes())}).collect()}
pub fn pack_tables(p:&GrainPlan)->(Vec<u8>,[Vec<Table>;3]){
    let mut data=Vec::new();let layout=std::array::from_fn(|ch|p.layers[ch].iter().map(|l|{
        let axis=TABLE_BASE+data.len() as u32*3;data.extend_from_slice(&l.axis);let values=TABLE_BASE+data.len() as u32*3;data.extend_from_slice(&l.values);Table{axis,values,knots:l.axis.len()as u32}
    }).collect());(encode(data),layout)
}
pub fn validate(p:&GrainPlan,mode:RngMode)->R<()>{
    p.validate()?;if mode!=p.rng_mode{return Err("INVALID_GRAIN_RNG_MODE".into());}let scalars=p.layers.iter().flatten().map(|l|3*(l.axis.len()+l.values.len())).sum::<usize>();
    if TABLE_BASE as usize+scalars>CHUNK_BASE as usize||CHUNK_BASE+3*COORDINATE_CHUNK>PARAM_BASE||PARAM_BASE as u64*4+6144>spatial::TRANSFER_BYTES{return Err("GPU_GRAIN_MEMORY_LIMIT".into());}
    for l in p.layers.iter().flatten(){if l.particles>MAX_RATE{return Err("GPU_GRAIN_COUNT_LIMIT".into());}}
    if mode==RngMode::SequentialMt19937&&p.origin!=[0,0]{return Err("INVALID_GRAIN_RNG_MODE".into());}Ok(())
}
pub fn shader()->R<String>{
    use sha2::{Digest,Sha256};
    if PHASED_SOURCE_VERSION!=super::alu_programs::ABI_VERSION||format!("{:x}",Sha256::digest(GRAPH_SHADER.as_bytes()))!=PHASED_GRAPH_SHA256{return Err("GPU_GRAIN_SHADER_VERSION".into());}
    let needle=" if(op==0u || op==1u){";
    if spatial::SHADER.matches(needle).count()!=1{return Err("GPU_GRAIN_SHADER_VERSION".into());}
    let base=spatial::SHADER.replace(needle," if(op==27u){grain_main(i);return;}\n if(op==40u||op==41u){preview_rng_main(i);return;}\n if(op>=50u){guarded_grain_main(i);return;}\n if(op==0u || op==1u){");
    let base=crate::research_finish_tail::guarded_spatial_shader(&base).map_err(|e|e.replace("TAIL","GRAIN"))?;
    let sampler=super::alu_programs::compose(GUARDED_SHADER)?;
    Ok(base+SAMPLER_SHADER+super::GRAPH_SHADER+preview_rng::SHADER+soft64::SHADER+&sampler+GRAPH_SHADER)
}
/// Deterministic device known-answer cases include tie-to-even, cancellation,
/// carry propagation, reciprocal and square-root exponent transitions.
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub struct ExecutionContract {pub dispatches:u64,pub numeric_flag_reads:u64,pub readback_bytes:u64,pub spatial_samples:u64}
pub fn execution_contract(p:&GrainPlan)->ExecutionContract{
 let n=p.width as u64*p.height as u64;let c=p.chunk_count();let l=p.layer_count()as u64;let s=u64::from(p.rng_mode==RngMode::SequentialMt19937);let m=u64::from(p.micro_active);let f=u64::from(p.active&&p.final_sigma>0.);let a=u64::from(p.active&&p.amount!=1.);let passes=p.pass_count();
 let dispatches=diagnostic_dispatches(p.rng_mode)+3*(c+3+l*(4*c+1+s)+m*(c+1+s)+f+a)+4*passes;
 let batches=c.div_ceil(submission_batch_chunks(p.rng_mode)as u64);let numeric_flag_reads=1+3*(l*(1+s)*batches+m*batches+1);let spatial_samples=passes*(p.width as u64+p.height as u64);
 ExecutionContract{dispatches,numeric_flag_reads,readback_bytes:12*n+1262528+PREPASS_CHECKS as u64*PREPASS_RATES.len()as u64*56+BOUNDARY_CHECKS as u64*32+8*spatial_samples+4*numeric_flag_reads,spatial_samples}
}
pub fn arithmetic_cases()->Vec<(f64,f64)>{
 let mut values=vec![(1.,2f64.powi(-53)),(f64::from_bits(1f64.to_bits()+1),2f64.powi(-53)),(1.,-1.),(-1.,1.),(2f64.powi(500),2f64.powi(-500)),(2f64.powi(-500),2f64.powi(500)),(f64::from_bits(4f64.to_bits()-1),3.),(f64::from_bits(4f64.to_bits()+1),3.),(f64::from_bits(1f64.to_bits()-1),f64::from_bits(1f64.to_bits()+1)),(0.,-1.),(-0.,1.),(-3.,7.),(262143.,0.29999999999999999),(100.,0.3),(100.,f64::from_bits(0.3f64.to_bits()+1)),(100.,f64::from_bits(0.3f64.to_bits()-1))];
 let mut state=42u64;for i in values.len() as u32..ARITHMETIC_CASES{state=state.wrapping_mul(6364136223846793005).wrapping_add(1);let a=((state>>11)as f64/9007199254740992.-0.5)*2f64.powi((i%100)as i32-50);state=state.wrapping_mul(6364136223846793005).wrapping_add(1);let b=((state>>11)as f64/9007199254740992.-0.5)*2f64.powi((i%93)as i32-40);values.push((a,b));}values
}
pub fn diagnostic_case(index:u32)->(u64,u32,u32,f64,f64){
    let rates=[0.01,1.,9.999999,10.,10.000001,30.,100.,1000.,240000.,1000000.,65535.,200000.,15000000.];
    let probabilities=[1e-6,0.01,0.03,0.29999999,0.3,0.30000001,0.5,0.7,0.97,1.-1e-6];
    let seed=[0,42,u64::MAX][(index/1040)as usize];let j=index%8;
    (seed,(u32::MAX-31).wrapping_add(j%71),209+j/71,rates[((index/80)%13)as usize],probabilities[((index/8)%10)as usize])
}
pub fn density_sample_count()->u64{SAMPLER_CHECKS as u64+PREPASS_CHECKS as u64*PREPASS_RATES.len()as u64}
/// Count-only boundary tuples are deliberately excluded from density RMS.
pub fn sampler_density_rms(squared:f64,density_samples:u64)->R<f64>{
 if density_samples!=density_sample_count()||!squared.is_finite()||squared<0.{return Err("GPU_GRAIN_NUMERIC_CHECK".into());}Ok((squared/density_samples as f64).sqrt())
}
pub fn boundary_case(index:u32)->(u64,u32,u32,u64,f64){
 assert!(index<BOUNDARY_CHECKS);let(seed,x,y,_,mut p)=diagnostic_case(index);let n=[0u64,1,30,60,61,100,262144,16777215][index as usize%8];
 if n>=60{let edge=30./n as f64;p=match(index/8)%3{0=>f64::from_bits(edge.to_bits()-1),2=>f64::from_bits(edge.to_bits()+1),_=>edge};if index>=48{p=1.-p;}}
 (seed,x,y,n,p)
}
#[cfg(test)]mod tests{
    use super::*;
    #[test]fn binary64_transport_preserves_every_bit_without_float_nan(){for x in[0.,-0.,1.,f64::MIN_POSITIVE,f64::MAX,f64::from_bits(1),f64::from_bits(u64::MAX)]{let bytes=encode([x]);let v:Vec<u32>=bytes.chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())as u32).collect();let bits=v[0]as u64|((v[1]as u64)<<24)|((v[2]as u64)<<48);assert_eq!(bits,x.to_bits());}}
    #[test]fn bounded_layout_and_serial_chunk_contract(){assert_eq!(FACTORIAL_BASE+3*LOG_FACTORIAL_COUNT,TABLE_BASE);assert_eq!(TABLE_BASE+3*18*2048,CHUNK_BASE);assert_eq!(CHUNK_BASE+3*COORDINATE_CHUNK,PARAM_BASE);assert!(preview_rng::STATE_BASE+preview_rng::STATE_FLOATS<=FACTORIAL_BASE);assert_eq!(RngMode::SequentialMt19937.chunk_pixels(1000),256);}
    #[test]fn guarded_full_graph_naga_validates_integer_only_f64(){let s=shader().unwrap();let m=naga::front::wgsl::parse_str(&s).unwrap_or_else(|e|panic!("{}",e.emit_to_string(&s)));naga::valid::Validator::new(naga::valid::ValidationFlags::all(),naga::valid::Capabilities::empty()).validate(&m).unwrap();}
}

#[cfg(test)]
mod submission_batch_tests {
 use super::*;
 use crate::research_grain::{preview_rng::Mt19937,guarded_sampler::{self,Stream,Failure}};
 // Host replay of the unchanged shader's Poisson-all / binomial-all ordering.
 // Covers chunk tails, the 16-chunk boundary, MT twists, multiple seeds/layers.
 fn replay(seed:u64,n:usize,batch:u32)->Result<(Vec<u64>,Vec<u64>,Vec<u64>,u64),Failure>{
  let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();let mut mt=Mt19937::new(seed);let mut rng=Stream::new(&mut mt,&table);
  let mut particles=vec![0;n];let mut developed=vec![0;n];let mut output=vec![0;n];
  for op in [52,53]{let mut offset=0;while offset<n{for _ in 0..batch{if offset==n{break;}let end=(offset+PREVIEW_CHUNK as usize).min(n);
   // Model the exact-bit prepared scratch independently of RNG state.
   let prepared:Vec<[f64;3]>=(offset..end).map(|i|{let pb=0.1+(i%79)as f64/100.;let sat=1.-pb*0.97*(1.-1e-6);[pb,sat,2300./sat]}).collect();
   let transport=encode(prepared.iter().flatten().copied());
   let decoded:Vec<f64>=transport.chunks_exact(12).map(|b|{let lane:Vec<u64>=b.chunks_exact(4).map(|x|f32::from_le_bytes(x.try_into().unwrap())as u64).collect();f64::from_bits(lane[0]|lane[1]<<24|lane[2]<<48)}).collect();
   for i in offset..end {let direct=prepared[i-offset];let a=if batch==1{direct}else{[decoded[3*(i-offset)],decoded[3*(i-offset)+1],decoded[3*(i-offset)+2]]};assert_eq!(a.map(f64::to_bits),direct.map(f64::to_bits));let [pb,sat,lam]=a;rng.reset_budget();
    if op==52 {particles[i]=guarded_sampler::rk_poisson(&mut rng,lam);}else{developed[i]=guarded_sampler::rk_binomial(&mut rng,particles[i],pb);output[i]=(developed[i]as f64*(1./2300.)*sat).to_bits();}
    if let Some(e)=rng.failure{return Err(e);}
   }offset=end;
  }}}
  Ok((particles,developed,output,rng.used))
 }
 #[test]
 fn sequential_submission_batches_preserve_sampler_tuples_and_words(){
  for base in [0u64,42,u64::MAX]{for layer in 0..3u64{let seed=base.wrapping_add(layer*10);let n=4097;
   let unbatched=replay(seed,n,1);let batched=replay(seed,n,submission_batch_chunks(RngMode::SequentialMt19937));assert_eq!(batched,unbatched);
   let (_,_,bits,words)=batched.expect("fixture must be numerically admitted");
   let density:Vec<f64>=(0..n).map(|i|0.1+(i%79)as f64/100.).collect();let (expected,used)=guarded_sampler::preview(seed,&density,1.,2300.,0.97).unwrap();assert_eq!(words,used);assert_eq!(bits,expected.iter().map(|v|v.to_bits()).collect::<Vec<_>>());
  }}
 }
 #[test]
 fn typed_prepass_scratch_retains_exact_transport_and_bounded_capacity(){
  assert_eq!(PREPARED_STRIDE,72);assert!(PREPASS_CHECKS<=PREVIEW_CHUNK);
  assert_eq!(scratch_bytes(RngMode::SequentialMt19937).unwrap(),144064);
  assert_eq!(scratch_bytes(RngMode::CoordinateSplitMix64).unwrap(),8917696);
  assert!(GRAPH_SHADER.contains("const GG_PREPARED_STRIDE:u32=72u;"));
  assert!(GRAPH_SHADER.contains("grain_scratch.frames[pixel].prepared"));
  assert!(GRAPH_SHADER.contains("if(!a.valid||sf_failed!=0u){gg_fail();return;}"));
 }

 #[test]
 fn partial_batches_and_coordinate_contract(){
  assert_eq!(submission_batch_chunks(RngMode::CoordinateSplitMix64),1);
  for (chunks,expected) in [(1u64,1),(16,1),(17,2),(676,43),(768,48)]{assert_eq!(chunks.div_ceil(submission_batch_chunks(RngMode::SequentialMt19937)as u64),expected);}
 }
}

#[cfg(test)]mod density_rms_tests{
 use super::*;
 #[test]fn density_rms_excludes_count_only_boundary_census(){assert_eq!(density_sample_count(),3184);assert_eq!(sampler_density_rms(3184.,3184).unwrap(),1.);assert!(sampler_density_rms(3184.,3280).is_err());assert!(sampler_density_rms(f64::NAN,3184).is_err());}
}

#[cfg(test)]mod fixed_admission_step_tests{
 use super::*;
 #[test]fn original_generic_step_goldens_recompute_all_ordered_fixtures(){
  let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();let mut fingerprint=14695981039346656037u64;
  assert_eq!(ADMISSION_STEPS.len(),SAMPLER_CHECKS as usize);
  for i in 0..SAMPLER_CHECKS{let(seed,x,y,lam,p)=diagnostic_case(i);for byte in seed.to_le_bytes().into_iter().chain(x.to_le_bytes()).chain(y.to_le_bytes()).chain(lam.to_bits().to_le_bytes()).chain(p.to_bits().to_le_bytes()){fingerprint=(fingerprint^byte as u64).wrapping_mul(1099511628211);}
   let expected=super::super::guarded_sampler::particle_steps_reference(seed,x,y,lam,p,&table).unwrap_or_else(|e|panic!("fixture {i} rejected: {e:?}"));assert_eq!(ADMISSION_STEPS[i as usize],expected,"fixture {i}");assert!(expected<=4096);assert_eq!((expected as f32)as u32,expected);
  }assert_eq!(fingerprint,ADMISSION_FIXTURE_FNV64);
 }
 #[test]fn step_table_and_later_cursor_metadata_reuse_only_empty_profile_scratch(){
  assert!(TABLE_BASE+SAMPLER_CHECKS<=CHUNK_BASE);assert!(TABLE_BASE+12*PREPASS_CHECKS<=CHUNK_BASE);assert_eq!(SAMPLER_CHECKS as u64*4,12480);
  assert!(GRAPH_SHADER.contains("rng.steps!=u32(probe.values[868352u+f])"));
 }
}

#[path = "opcode_families.rs"]
pub(crate) mod opcode_families;

#[cfg(test)]mod phased_contract_tests{
 use super::*;
 #[test]fn diagnostic_strength_and_mode_dispatch_counts_are_fixed(){
  assert_eq!(SAMPLER_CHECKS+BOUNDARY_CHECKS+PREPASS_CHECKS*PREPASS_RATES.len()as u32,3280);
  assert_eq!(ZERO_CHECKS,96);assert_eq!(density_sample_count(),3184);
  assert_eq!(diagnostic_dispatches(RngMode::SequentialMt19937),77);
  assert_eq!(diagnostic_dispatches(RngMode::CoordinateSplitMix64),29);
 }
 #[test]fn split_consumers_admit_lane_before_any_sampler_register_writes(){
  let poisson=GRAPH_SHADER.split("if(op==52u||op==65u){").nth(1).unwrap().split("if(op==53u||op==64u){").next().unwrap();
  let binomial=GRAPH_SHADER.split("if(op==53u||op==64u){").nth(1).unwrap().split("if(op==54u){").next().unwrap();
  for (body,call) in [(poisson,"sr_poisson_prepared(&rng"),(binomial,"sr_binomial_prepared(&rng")]{
   let geometry=body.find("if(!gg_phase_valid(op)){gg_fail();return;}").unwrap();
   let begin=body.find("if(!grain_alu_begin(lane,active_lanes)){return;}").unwrap();
   assert!(geometry<begin && begin<body.find(call).unwrap());
   assert!(body.find("if(serial&&i!=0u){return;}").unwrap()<begin);
  }
  let composed=shader().unwrap();let entry=composed.split("fn main(").nth(1).unwrap();
  assert!(entry.find("if(local>=p.u[1].x){return;}").unwrap()<entry.find("guarded_grain_main(i)").unwrap());
 }
 #[test]fn split_consumers_have_single_scientific_callsite_and_complete_cursor(){
  assert_eq!(GRAPH_SHADER.matches("sr_poisson_prepared(&rng").count(),1);
  assert_eq!(GRAPH_SHADER.matches("sr_binomial_prepared(&rng").count(),1);
  for field in ["rng.words","rng.steps","rng.failure","rng.mode","sf_failed"]{assert!(GRAPH_SHADER.contains(field));}
  assert!(!GRAPH_SHADER.contains("if(op==51u)"));assert!(!GRAPH_SHADER.contains("if(op==61u)"));
  assert!(GRAPH_SHADER.contains("if(op==66u)"));
 }
}
