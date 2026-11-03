//! Guarded sampler graph construction. Runtime routing remains separate until
//! device validation and the v2 private-manifest contract are enabled together.
use super::*;
/// Host source gates admitted after snapshot2: full shader, ARM metadata,
/// guarded graph/final-image tests and independent scoped review. Every device
/// still must pass its exact RNG/arithmetic/sampler checks before publishing.
pub const SOURCE_ADMITTED:bool=true;
pub const MAX_RATE:f64=15000000.;
pub const MAX_COUNT:u64=16777216;
pub const FACTORIAL_BASE:u32=81920;
pub const TABLE_BASE:u32=868352;
pub const CHUNK_BASE:u32=978944;
pub const PARAM_BASE:u32=1028096;
pub const COORDINATE_CHUNK:u32=16384;
pub const PREVIEW_CHUNK:u32=256;
pub const SAMPLER_CHECKS:u32=3120;
pub const PREPASS_CHECKS:u32=32;
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
pub const PREPARED_BASE:u32=CHUNK_BASE+3*PREVIEW_CHUNK;
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
    let needle=" if(op==0u || op==1u){";
    if spatial::SHADER.matches(needle).count()!=1{return Err("GPU_GRAIN_SHADER_VERSION".into());}
    let base=spatial::SHADER.replace(needle," if(op==27u){grain_main(i);return;}\n if(op==40u||op==41u){preview_rng_main(i);return;}\n if(op>=50u){guarded_grain_main(i);return;}\n if(op==0u || op==1u){");
    let base=crate::research_finish_tail::guarded_spatial_shader(&base).map_err(|e|e.replace("TAIL","GRAIN"))?;
    Ok(base+SAMPLER_SHADER+super::GRAPH_SHADER+preview_rng::SHADER+soft64::SHADER+GUARDED_SHADER+GRAPH_SHADER)
}
/// Deterministic device known-answer cases include tie-to-even, cancellation,
/// carry propagation, reciprocal and square-root exponent transitions.
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub struct ExecutionContract {pub dispatches:u64,pub numeric_flag_reads:u64,pub readback_bytes:u64,pub spatial_samples:u64}
pub fn execution_contract(p:&GrainPlan)->ExecutionContract{
 let n=p.width as u64*p.height as u64;let c=p.chunk_count();let l=p.layer_count()as u64;let s=u64::from(p.rng_mode==RngMode::SequentialMt19937);let m=u64::from(p.micro_active);let f=u64::from(p.active&&p.final_sigma>0.);let a=u64::from(p.active&&p.amount!=1.);let passes=p.pass_count();
 let dispatches=16+3*(c+3+l*((1+3*s)*c+1+s)+m*(c+1+s)+f+a)+4*passes;
 let batches=c.div_ceil(submission_batch_chunks(p.rng_mode)as u64);let numeric_flag_reads=1+3*(l*(1+s)*batches+m*batches+1);let spatial_samples=passes*(p.width as u64+p.height as u64);
 ExecutionContract{dispatches,numeric_flag_reads,readback_bytes:12*n+1262528+PREPASS_CHECKS as u64*56+8*spatial_samples+4*numeric_flag_reads,spatial_samples}
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
 fn sequential_prepass_scratch_is_bounded_and_disjoint(){
  assert_eq!(PREPARED_BASE,979712);assert_eq!(PREPARED_BASE+9*PREVIEW_CHUNK,982016);
  assert!(PREPARED_BASE+9*PREVIEW_CHUNK<=PARAM_BASE);assert!(PREPASS_CHECKS<=PREVIEW_CHUNK);
  assert!(GRAPH_SHADER.contains("const GG_PREPARED_BASE:u32=979712u;"));
  assert!(GRAPH_SHADER.contains("if(!a.valid||sf_failed!=0u){gg_fail();return;}"));
 }
 #[test]
 fn partial_batches_and_coordinate_contract(){
  assert_eq!(submission_batch_chunks(RngMode::CoordinateSplitMix64),1);
  for (chunks,expected) in [(1u64,1),(16,1),(17,2),(676,43),(768,48)]{assert_eq!(chunks.div_ceil(submission_batch_chunks(RngMode::SequentialMt19937)as u64),expected);}
 }
}
