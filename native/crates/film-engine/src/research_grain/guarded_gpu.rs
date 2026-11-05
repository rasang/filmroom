//! Guarded full grain execution selected by the experimental public stage runner.
//! Every device request must pass RNG/arithmetic/sampling gates before candidate
//! publication. Host source admission is not a claim of GPU preview speed.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase};
use super::{*,guarded_graph::{self as graph,RngMode,Table}};
use crate::research_spatial::{check,gpu::{Runner,RunMetrics,params,GrainScratchConfig}};
use std::{fs::{File,OpenOptions},io::{Read,Write,Seek,SeekFrom},path::Path,time::Instant};
#[derive(Default,Serialize)]#[serde(rename_all="camelCase")]
struct Census {exact_rng_words_checked:u64,software_arithmetic_checks:u64,sampler_sample_count:u64,poisson_count_mismatches:u64,binomial_count_mismatches:u64,random_word_count_mismatches:u64,sampler_tuple_mismatches:u64,sampler_max_abs:f64,sampler_rms:f64,numeric_flag_reads:u64}
fn floats(raw:&[u8])->Vec<f32>{raw.chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect()}
fn flag(r:&Runner,c:&mut impl FnMut()->bool,m:&mut RunMetrics,s:&mut Census)->R<()>{
        trace::phase_result(GpuPhase::Guard, || {s.numeric_flag_reads+=1;if r.read_error_flag(c,m)?!=0{return Err("GPU_GRAIN_GUARDED_REJECTION".into());}Ok(())
        })
    }
fn layer_params(p:&GrainPlan,l:&Layer,t:Table,mode:RngMode,offset:u64,count:usize,op:u32)->[u32;40]{
 let mut v=params(p.width,p.height,op);v[4]=if mode==RngMode::SequentialMt19937&&matches!(op,52|53|55){1}else{count as u32};v[5]=count as u32;v[7]=offset as u32;v[8]=l.seed as u32;v[9]=(l.seed>>32)as u32;v[10]=u32::from(p.positive);v[11]=u32::from(mode==RngMode::SequentialMt19937);v[16]=t.axis;v[17]=t.values;v[18]=t.knots;v[28]=p.origin[0];v[29]=p.origin[1];v
}
fn read_chunk(input:&mut File,offset:u64,count:usize,c:&mut impl FnMut()->bool)->R<Vec<u8>>{
        trace::phase_result(GpuPhase::Upload, || {
 check(c)?;input.seek(SeekFrom::Start(offset*8)).map_err(|_|"GPU_GRAIN_IO")?;let mut raw=vec![0u8;count*8];input.read_exact(&mut raw).map_err(|_|"GPU_GRAIN_IO")?;
 let values:Vec<f64>=raw.chunks_exact(8).map(|b|f64::from_le_bytes(b.try_into().unwrap())).collect();if values.iter().any(|x|!x.is_finite()){return Err("NONFINITE_GRAIN_INPUT".into());}
 // Crucially no as-f32 conversion: the three numeric lanes carry all64 bits.
 Ok(graph::encode(values))

        })
    }
fn init_mt(r:&Runner,p:&GrainPlan,seed:u64,c:&mut impl FnMut()->bool,m:&mut RunMetrics)->R<()>{let mut v=params(p.width,p.height,40);v[4]=1;v[8]=seed as u32;r.dispatch(v,c,m)}
fn diagnostic_params(p:&GrainPlan,op:u32,kind:u32,base:u32,count:u32)->[u32;40]{
 let mut v=params(p.width,p.height,op);v[4]=count;v[5]=count;v[12]=kind;v[13]=base;v
}
fn diagnostic_phases(r:&Runner,p:&GrainPlan,kind:u32,total:u32,ops:&[u32],c:&mut impl FnMut()->bool,m:&mut RunMetrics)->R<()>{
 let capacity=graph::frame_capacity(p.rng_mode);let mut base=0;
 while base<total{let count=(total-base).min(capacity);let mut batch=r.batch();
  for &op in ops{batch.submit_chunk(None,diagnostic_params(p,op,kind,base,count),c,m)?;}
  batch.finish(c,m)?;base+=count;
 }Ok(())
}
fn diagnostics(r:&Runner,p:&GrainPlan,c:&mut impl FnMut()->bool,m:&mut RunMetrics,s:&mut Census)->R<()>{
 let _admission=trace::AdmissionScope::begin();
 trace::phase_result(GpuPhase::Guard, || {
 for(seed,origin)in[(0,[0u32,0u32]),(u64::MAX,[u32::MAX-7,u32::MAX-3]),(p.seed,[31,209])]{
  let mut v=params(17,121,27);v[4]=2048;v[8]=seed as u32;v[9]=(seed>>32)as u32;v[28]=origin[0];v[29]=origin[1];r.dispatch(v,c,m)?;
  let data=floats(&r.read_probe(2048*8*16,c,m)?);
  for i in 0..2048u32{check(c)?;let words=spektrafilm_model::grain::coordinate_random_words(seed,origin[0].wrapping_add(i%17),origin[1].wrapping_add(i/17),8);
   for(j,&word)in words.iter().enumerate(){for k in 0..4{if data[(i as usize*8+j)*4+k]!=((word>>(16*k))&65535)as f32{return Err("GPU_GRAIN_INTEGER_RNG_CHECK".into());}}s.exact_rng_words_checked+=1;}
  }
 }
 for seed in[0,42,u64::MAX]{init_mt(r,p,seed,c,m)?;let mut data=Vec::with_capacity(32768);
  // Deliberately cross a dispatch boundary at a non-twist-aligned cursor.
  for count in[17u32,16367]{let mut v=params(p.width,p.height,41);v[4]=1;v[5]=count;r.dispatch(v,c,m)?;data.extend(floats(&r.read_probe(count as u64*8,c,m)?));}
  let expected=spektrafilm_model::grain::legacy_random_words(seed,16384);for(i,word)in expected.into_iter().enumerate(){if data[2*i]!=(word&65535)as f32||data[2*i+1]!=(word>>16)as f32{return Err("GPU_GRAIN_MT_RNG_CHECK".into());}s.exact_rng_words_checked+=1;}
 }
 let pairs=graph::arithmetic_cases();
 r.upload_aux(graph::PARAM_BASE as u64*4,&graph::encode(pairs.iter().flat_map(|&(a,b)|[a,b])),c,m)?;
 let mut v=params(p.width,p.height,62);v[4]=graph::ARITHMETIC_CASES;r.dispatch(v,c,m)?;let data=floats(&r.read_probe(graph::ARITHMETIC_CASES as u64*80,c,m)?);
 for(i,&(a,b))in pairs.iter().enumerate(){for(j,value)in[a+b,a-b,a*b,a/b,a.abs().sqrt()].into_iter().enumerate(){let mut actual=0u64;for k in 0..4{let part=data[20*i+4*j+k];if !part.is_finite()||part<0.||part>65535.||part.fract()!=0.{return Err("GPU_GRAIN_SOFTWARE64_CHECK".into());}actual|=(part as u64)<<(16*k);}let expected=if value==0.{0}else{value.to_bits()};if actual<<1==0{actual=0;}if actual!=expected{return Err("GPU_GRAIN_SOFTWARE64_CHECK".into());}s.software_arithmetic_checks+=1;}}
 // The immutable libm table is calibration, not per-image CPU sampling.
 let prepared=spektrafilm_math::numpy_rng::PreparedPoisson::new();let table=graph::encode((0..LOG_FACTORIAL_COUNT).map(|k|prepared.log_factorial(k as i64)));
 r.upload_aux(graph::FACTORIAL_BASE as u64*4,&table,c,m)?;drop(table);
 // Fixed original-generic step goldens: no per-request distribution replay.
 // This validation-only upload is included in real upload bytes/time/wait metrics.
 let step_bytes:Vec<u8>=graph::ADMISSION_STEPS.iter().flat_map(|&n|(n as f32).to_le_bytes()).collect();
 r.upload_aux(graph::TABLE_BASE as u64*4,&step_bytes,c,m)?;drop(step_bytes);
 diagnostic_phases(r,p,graph::INPUT_CENSUS,graph::SAMPLER_CHECKS,&[63,65,66,64],c,m)?;
 diagnostic_phases(r,p,graph::INPUT_BOUNDARY,graph::BOUNDARY_CHECKS,&[66,64],c,m)?;
 diagnostic_phases(r,p,graph::INPUT_ZERO,graph::ZERO_CHECKS,&[63,65],c,m)?;
 let data=floats(&r.read_probe(graph::SAMPLER_CHECKS as u64*20+graph::BOUNDARY_CHECKS as u64*32,c,m)?);let mut squared=0.;let mut density_samples=0u64;
 for i in 0..graph::SAMPLER_CHECKS{check(c)?;let(seed,x,y,lambda,probability)=graph::diagnostic_case(i);let expected=spektrafilm_model::grain::coordinate_particle_sample(seed,x,y,probability,1.,lambda,0.);let a=&data[5*i as usize..5*i as usize+5];
  s.poisson_count_mismatches+=u64::from(a[0]!=expected.0 as f32);s.binomial_count_mismatches+=u64::from(a[1]!=expected.1 as f32);s.random_word_count_mismatches+=u64::from(a[2]!=expected.2 as f32);
  s.sampler_tuple_mismatches+=u64::from(a[0]!=expected.0 as f32||a[1]!=expected.1 as f32||a[2]!=expected.2 as f32||a[3].to_bits()!=(expected.3 as f32).to_bits()||a[4]!=0.);
  let error=(a[3]as f64-expected.3).abs();if !error.is_finite(){return Err("GPU_GRAIN_NUMERIC_CHECK".into());}s.sampler_max_abs=s.sampler_max_abs.max(error);squared+=error*error;density_samples+=1;s.sampler_sample_count+=1;
 }
 // Preserve all96 formerly GPU-paired boundaries against the independent
 // original guarded CPU sampler, including allowed matching failure states.
 for i in 0..graph::BOUNDARY_CHECKS{check(c)?;let(seed,x,y,n,pb)=graph::boundary_case(i);let expected=guarded_sampler::binomial_admission_reference(seed,x,y,n,pb,&prepared);let at=graph::SAMPLER_CHECKS as usize*5+i as usize*8;let a=&data[at..at+8];
  s.binomial_count_mismatches+=u64::from(a[0]!=expected[0]);s.random_word_count_mismatches+=u64::from(a[1]!=expected[1]);s.sampler_tuple_mismatches+=u64::from(a!=expected.as_slice());s.sampler_sample_count+=1;
 }
 // Exercise the real parallel prepass and its stored-bit consumer on device.
 // These are additional gates, not replacements for the existing3120 tuples.
 for (npp,uniformity) in graph::PREPASS_RATES{
 let density:Vec<f64>=(0..graph::PREPASS_CHECKS).map(|i|0.1+i as f64/40.).collect();
 let mut cursor_bytes=Vec::with_capacity(graph::PREPASS_CHECKS as usize*48);
 for(i,&d)in density.iter().enumerate(){let golden=guarded_sampler::particle_cursor_reference(42,31+i as u32%17,209+i as u32/17,d,npp,uniformity,&prepared).map_err(|_|"GPU_GRAIN_NUMERIC_CHECK")?;cursor_bytes.extend(golden.into_iter().flat_map(f32::to_le_bytes));}
 // Profile tables are not uploaded until admission ends. This bounded scratch
 // is validation metadata only, never sampler input or a supplied count.
 r.upload_aux(graph::TABLE_BASE as u64*4,&cursor_bytes,c,m)?;
 r.upload_aux(graph::PARAM_BASE as u64*4,&graph::encode([0.,1.,npp,uniformity,0.,0.]),c,m)?;
 r.upload_aux(graph::CHUNK_BASE as u64*4,&graph::encode(density.iter().copied()),c,m)?;
 let mut v=params(17,2,63);v[4]=graph::PREPASS_CHECKS;v[5]=graph::PREPASS_CHECKS;v[8]=42;v[12]=graph::INPUT_PREPASS;v[28]=31;v[29]=209;r.dispatch(v,c,m)?;
 v[2]=65;r.dispatch(v,c,m)?;v[2]=66;r.dispatch(v,c,m)?;
 v[2]=64;r.dispatch(v,c,m)?;let data=floats(&r.read_probe(graph::PREPASS_CHECKS as u64*56,c,m)?);
 for(i,&d)in density.iter().enumerate(){
  let pb=d.clamp(1e-6,1.-1e-6);let sat=1.-pb*uniformity*(1.-1e-6);let lam=npp/sat;let encoded=floats(&graph::encode([pb,sat,lam]));let a=&data[14*i..14*i+14];
  if a[..9]!=encoded[..]{return Err("GPU_GRAIN_PREPASS_BITS_CHECK".into());}
  let expected=spektrafilm_model::grain::coordinate_particle_sample(42,31+i as u32%17,209+i as u32/17,d,1.,npp,uniformity);
  s.poisson_count_mismatches+=u64::from(a[9]!=expected.0 as f32);s.binomial_count_mismatches+=u64::from(a[10]!=expected.1 as f32);s.random_word_count_mismatches+=u64::from(a[11]!=expected.2 as f32);
  s.sampler_tuple_mismatches+=u64::from(a[9]!=expected.0 as f32||a[10]!=expected.1 as f32||a[11]!=expected.2 as f32||a[12].to_bits()!=(expected.3 as f32).to_bits()||a[13]!=0.);
  let error=(a[12]as f64-expected.3).abs();if !error.is_finite(){return Err("GPU_GRAIN_NUMERIC_CHECK".into());}s.sampler_max_abs=s.sampler_max_abs.max(error);squared+=error*error;density_samples+=1;s.sampler_sample_count+=1;
 }
 }
 s.sampler_rms=graph::sampler_density_rms(squared,density_samples)?;if s.sampler_tuple_mismatches!=0{return Err("GPU_GRAIN_SAMPLER_TUPLE_CHECK".into());}flag(r,c,m,s)

 })
}
/// Shared immutable fixed admission for a separately allocated streaming runner.
/// No diagnostics body, expected tuple or failure policy is changed.
pub(crate) fn admit_shared_runner(r: &Runner, p: &GrainPlan, c: &mut impl FnMut() -> bool,
    m: &mut RunMetrics) -> R<serde_json::Value> {
    let mut census = Census::default();
    diagnostics(r, p, c, m, &mut census)?;
    serde_json::to_value(census).map_err(|_| "INVALID_GRAIN_RESULT".into())
}
struct Outputs<'a>{paths:[&'a Path;3],created:usize,keep:bool}
impl Drop for Outputs<'_>{fn drop(&mut self){
        if !self.keep{
            trace::phase_value(GpuPhase::Cleanup, || {for p in&self.paths[..self.created]{let _=std::fs::remove_file(p);}
            });
        }
    }}
pub fn run_files(p:&GrainPlan,mode:RngMode,sources:[&Path;3],outputs:[&Path;3],c:&mut impl FnMut()->bool)->R<serde_json::Value>{if !graph::SOURCE_ADMITTED{return Err("GPU_GRAIN_GUARDED_PENDING".into());}run(p,mode,sources,outputs,c,false).map_err(|e|e.replace("OPTICS","GRAIN").replace("TAIL","GRAIN"))}
fn run(p:&GrainPlan,mode:RngMode,sources:[&Path;3],outputs:[&Path;3],c:&mut impl FnMut()->bool,allow_probe:bool)->R<serde_json::Value>{
 let started=Instant::now();graph::validate(p,mode)?;trace::phase_result(GpuPhase::Guard, || p.numerical_preflight(c))?;let n=p.width as u64*p.height as u64;let chunk=mode.chunk_pixels(n)as usize;
 for i in 0..3{if sources.contains(&outputs[i])||outputs[..i].contains(&outputs[i]){return Err("INVALID_GRAIN_FILE".into());}let meta=std::fs::symlink_metadata(sources[i]).map_err(|_|"GPU_GRAIN_IO")?;if !meta.is_file()||meta.file_type().is_symlink()||meta.len()!=n*8{return Err("INVALID_GRAIN_FILE".into());}}
 let(r,mut m)=Runner::new_with_grain_shader_families(&p.spatial_plan()?,allow_probe,&graph::opcode_families::shaders_for_plan(p)?,GrainScratchConfig::new(graph::frame_capacity(mode))?)?;let mut census=Census::default();let admission=Instant::now();diagnostics(&r,p,c,&mut m,&mut census)?;m.setup_ms+=admission.elapsed().as_secs_f64()*1000.;
 let(bytes,tables)=graph::pack_tables(p);if !bytes.is_empty(){r.upload_aux(graph::TABLE_BASE as u64*4,&bytes,c,&mut m)?;}drop(bytes);
 let mut ownership=Outputs{paths:outputs,created:0,keep:false};
 for ch in 0..3{
  let mut input=File::open(sources[ch]).map_err(|_|"GPU_GRAIN_IO")?;
  let mut offset=0;while offset<n{
   let mut batch=r.batch();
   for _ in 0..graph::submission_batch_chunks(mode){if offset==n{break;}let count=(n-offset).min(chunk as u64)as usize;let bytes=read_chunk(&mut input,offset,count,c)?;let mut v=params(p.width,p.height,50);v[4]=count as u32;v[7]=offset as u32;batch.submit_chunk(Some((graph::CHUNK_BASE as u64*4,&bytes)),v,c,&mut m)?;offset+=count as u64;}
   batch.finish(c,&mut m)?;
  }
  r.dispatch(params(p.width,p.height,5),c,&mut m)?;
  for(sl,l)in p.layers[ch].iter().enumerate(){
   let parameters=graph::encode([l.density_min,l.density_max,l.particles,l.uniformity,p.micro_mu,p.micro_sigma_log]);r.upload_aux(graph::PARAM_BASE as u64*4,&parameters,c,&mut m)?;
   if mode==RngMode::SequentialMt19937{init_mt(&r,p,l.seed,c,&mut m)?;}
   let operations:&[u32]=if mode==RngMode::SequentialMt19937{&[52,53]}else{&[65]};
   for &op in operations {let mut offset=0;while offset<n{
    let mut batch=r.batch();
    for _ in 0..graph::submission_batch_chunks(mode){if offset==n{break;}let count=(n-offset).min(chunk as u64)as usize;let bytes=read_chunk(&mut input,offset,count,c)?;if mode==RngMode::SequentialMt19937{
     let prepare=layer_params(p,l,tables[ch][sl],mode,offset,count,if op==52{63}else{66});batch.submit_chunk(Some((graph::CHUNK_BASE as u64*4,&bytes)),prepare,c,&mut m)?;
     batch.submit_chunk(None,layer_params(p,l,tables[ch][sl],mode,offset,count,op),c,&mut m)?;
    }else{
     batch.submit_chunk(Some((graph::CHUNK_BASE as u64*4,&bytes)),layer_params(p,l,tables[ch][sl],mode,offset,count,63),c,&mut m)?;
     for phase in [65,66,64]{batch.submit_chunk(None,layer_params(p,l,tables[ch][sl],mode,offset,count,phase),c,&mut m)?;}
    }offset+=count as u64;}
    batch.finish(c,&mut m)?;flag(&r,c,&mut m,&mut census)?;
   }}
   if l.cloud_sigma>0.{r.blur(p.width,p.height,l.cloud_sigma,c,&mut m)?;}let mut v=params(p.width,p.height,56);v[26]=if l.cloud_sigma>0.{2}else{0};r.dispatch(v,c,&mut m)?;
  }
  if p.micro_active{
   let seed=if mode==RngMode::SequentialMt19937{p.seed.wrapping_add(0x51f15e)}else{p.micro_seed(ch)};
   if mode==RngMode::SequentialMt19937{init_mt(&r,p,seed,c,&mut m)?;}
   let mut offset=0;while offset<n{let mut batch=r.batch();for _ in 0..graph::submission_batch_chunks(mode){if offset==n{break;}let count=(n-offset).min(chunk as u64)as usize;let mut v=params(p.width,p.height,if mode==RngMode::SequentialMt19937{55}else{54});v[4]=if mode==RngMode::SequentialMt19937{1}else{count as u32};v[5]=count as u32;v[7]=offset as u32;v[8]=seed as u32;v[9]=(seed>>32)as u32;v[11]=u32::from(mode==RngMode::SequentialMt19937);v[19]=ch as u32;v[20]=u32::from(p.monochrome);v[28]=p.origin[0];v[29]=p.origin[1];batch.submit_chunk(None,v,c,&mut m)?;offset+=count as u64;}batch.finish(c,&mut m)?;flag(&r,c,&mut m,&mut census)?;}
   if p.micro_spread>0.{r.blur(p.width,p.height,p.micro_spread,c,&mut m)?;}let mut v=params(p.width,p.height,57);v[26]=if p.micro_spread>0.{2}else{0};r.dispatch(v,c,&mut m)?;
  }
  if p.active{
   let mut v=params(p.width,p.height,58);v[22]=(if p.sublayers{1.}else{1./p.layer_count()as f64}as f32).to_bits();v[23]=(p.density_min[ch]as f32).to_bits();r.dispatch(v,c,&mut m)?;
   if p.final_sigma>0.{r.blur(p.width,p.height,p.final_sigma,c,&mut m)?;let mut v=params(p.width,p.height,4);v[6]=2;v[7]=0;r.dispatch(v,c,&mut m)?;}
   if p.amount!=1.{let mut v=params(p.width,p.height,59);v[21]=(p.amount as f32).to_bits();r.dispatch(v,c,&mut m)?;}
  }else{let mut v=params(p.width,p.height,4);v[6]=3;v[7]=0;r.dispatch(v,c,&mut m)?;}
  r.dispatch(params(p.width,p.height,60),c,&mut m)?;flag(&r,c,&mut m,&mut census)?;
  let mut output=OpenOptions::new().write(true).create_new(true).open(outputs[ch]).map_err(|_|"GPU_GRAIN_IO")?;ownership.created+=1;let mut offset=0;
  while offset<n{check(c)?;let count=(n-offset).min(262144);let values=floats(&r.read_source(offset*4,count*4,c,&mut m)?);if values.iter().any(|x|!x.is_finite()){return Err("NONFINITE_GRAIN_OUTPUT".into());}let encoded:Vec<u8>=values.iter().flat_map(|&x|(x as f64).to_le_bytes()).collect();output.write_all(&encoded).map_err(|_|"GPU_GRAIN_IO")?;offset+=count;}
  output.sync_all().map_err(|_|"GPU_GRAIN_IO")?;
 }
 m.sample_rms=(m.sample_sum/m.sample_count.max(1)as f64).sqrt();m.elapsed_ms=started.elapsed().as_secs_f64()*1000.;let mut result=serde_json::to_value(m).map_err(|_|"INVALID_GRAIN_RESULT")?;let object=result.as_object_mut().ok_or("INVALID_GRAIN_RESULT")?;
 for(k,v)in serde_json::to_value(census).map_err(|_|"INVALID_GRAIN_RESULT")?.as_object().ok_or("INVALID_GRAIN_RESULT")?{object.insert(k.clone(),v.clone());}
 for(k,v)in[("rngMode",serde_json::to_value(mode).map_err(|_|"INVALID_GRAIN_RESULT")?),("distributionArithmetic",serde_json::json!("guardedSoftwareBinary64")),("stochasticScope",serde_json::json!("fixed_synthetic_reference_tuple_census")),("microstructureArithmetic",serde_json::json!("original_words_guarded_f32_box_muller")),("calibrationCpu",serde_json::json!("profile constants, original libm log-factorial table, filter coefficients and diagnostic oracles")),("grainActive",serde_json::json!(p.active)),("microActive",serde_json::json!(p.micro_active)),("finalBlurActive",serde_json::json!(p.active&&p.final_sigma>0.)),("amountBlendActive",serde_json::json!(p.active&&p.amount!=1.)),("sublayers",serde_json::json!(p.sublayers)),("layerCount",serde_json::json!(p.layer_count())),("chunkCount",serde_json::json!(n.div_ceil(chunk as u64)))]{object.insert(k.into(),v);}
 ownership.keep=true;Ok(result)
}

#[cfg(test)]
mod family_vulkan_tests {
 use super::*;
 fn fixture(mode:RngMode)->GrainPlan {
  let mut p=GrainPlan{width:17,height:17,active:true,sublayers:true,positive:false,monochrome:false,seed:42,origin:[0,0],rng_mode:mode,amount:0.6,density_min:vec![0.03;3],final_sigma:0.65,micro_active:true,micro_spread:1.3,micro_sigma_log:0.12,micro_mu:-0.5*0.12*0.12,layers:std::array::from_fn(|_|vec![])};
  for ch in 0..3{for sl in 0..3{let f=(sl+1)as f64/6.;p.layers[ch].push(Layer{seed:42+ch as u64+sl*10,density_min:0.03*f,density_max:2.43*f,particles:[2.3,57.,1700.][sl as usize],uniformity:0.97,axis:vec![0.,0.2,0.7,1.7,2.4],values:vec![0.,0.2*f,0.7*f,1.7*f,2.4*f],cloud_sigma:0.6+sl as f32*1.1});}}
  p.validate().expect("full-effects Vulkan fixture must pass plan admission");
  p
 }
 #[test]
 fn full_effects_fixture_is_admitted_in_both_rng_modes(){for mode in [RngMode::SequentialMt19937,RngMode::CoordinateSplitMix64]{let p=fixture(mode);graph::validate(&p,mode).unwrap();assert!(p.sublayers && p.micro_active && p.final_sigma>0. && p.amount!=1.);assert!(p.layers.iter().flatten().all(|l|l.cloud_sigma>0. && !l.axis.is_empty()));}}
 #[test]
 fn route_selection_and_tiny_image_admission_stay_independent(){
  for mode in [RngMode::SequentialMt19937,RngMode::CoordinateSplitMix64]{
   let mut p=fixture(mode);p.width=1;p.height=1;
   let v=diagnostic_params(&p,63,graph::INPUT_CENSUS,0,graph::frame_capacity(mode).min(graph::SAMPLER_CHECKS));
   assert!(v[4]>p.width*p.height);assert_eq!(v[4],v[5]);assert_eq!(v[11],0);assert_eq!(v[12],graph::INPUT_CENSUS);
   p.micro_active=false;let inactive=graph::opcode_families::shaders_for_plan(&p).unwrap();assert_eq!(inactive.len(),7);
   assert!(!inactive.iter().any(|(ops,_)|ops.iter().any(|op|[51,54,55,61].contains(op))));
   p.micro_active=true;let active=graph::opcode_families::shaders_for_plan(&p).unwrap();assert_eq!(active.len(),8);
   let micro=if mode==RngMode::SequentialMt19937{55}else{54};assert!(active.iter().any(|(ops,_)|ops==&vec![micro]));
  }
 }
 #[test]
 #[ignore = "explicit all-eight-family Vulkan compile and original RNG/arithmetic/sampler census; can compile slowly"]
 fn actual_grain_families_compile_and_pass_original_rng_census(){
  let p=fixture(RngMode::SequentialMt19937);let families=graph::opcode_families::shaders().unwrap();
  let(r,mut m)=Runner::new_with_grain_shader_families(&p.spatial_plan().unwrap(),true,&families,GrainScratchConfig::new(graph::frame_capacity(p.rng_mode)).unwrap()).unwrap();
  assert_eq!(m.pipeline_compile_ms.len(),8);
  for((ops,source),ms)in families.iter().zip(&m.pipeline_compile_ms){eprintln!("grain family {ops:?}: {} bytes, pipeline {ms:.3} ms",source.len());}
  let mut census=Census::default();diagnostics(&r,&p,&mut ||false,&mut m,&mut census).unwrap();
  assert_eq!(m.dispatches,graph::diagnostic_dispatches(p.rng_mode));
  assert_eq!(census.numeric_flag_reads,1);
  assert_eq!(m.readback_bytes,1269184+4);
  assert_eq!(census.exact_rng_words_checked,98304);
  assert_eq!(census.software_arithmetic_checks,1280);
  assert_eq!(census.sampler_tuple_mismatches,0);
  assert_eq!(census.sampler_sample_count,(graph::SAMPLER_CHECKS+graph::BOUNDARY_CHECKS+graph::PREPASS_CHECKS*graph::PREPASS_RATES.len()as u32)as u64);
 }

 struct Temp(std::path::PathBuf);
 impl Temp {fn new(label:&str)->Self{let path=std::env::temp_dir().join(format!("grain-{label}-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));std::fs::create_dir(&path).unwrap();Self(path)}}
 impl Drop for Temp{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
 fn case_files(root:&Temp,p:&GrainPlan)->([Vec<f64>;3],[std::path::PathBuf;3],[std::path::PathBuf;3]){
  let input:[Vec<f64>;3]=std::array::from_fn(|ch|(0..p.width*p.height).map(|i|0.1+((i*37+ch as u32*17)%251)as f64/150.).collect());
  let sources=std::array::from_fn(|ch|{let path=root.0.join(format!("input-{ch}"));std::fs::write(&path,input[ch].iter().flat_map(|x|x.to_le_bytes()).collect::<Vec<_>>()).unwrap();path});
  let outputs=std::array::from_fn(|ch|root.0.join(format!("output-{ch}")));(input,sources,outputs)
 }
 #[cfg(feature="precision-f64")]
 fn assert_actual_full_effects(p:GrainPlan,label:&str){
  graph::validate(&p,p.rng_mode).unwrap();let root=Temp::new(label);let(input,sources,outputs)=case_files(&root,&p);
  let source_bytes:[Vec<u8>;3]=std::array::from_fn(|ch|std::fs::read(&sources[ch]).unwrap());
  // false selects the original model's entire layer, blur, microstructure and
  // amount graph. It is not the guarded CPU mirror used as its own oracle.
  let expected=guarded_cpu::mirror(&p,&input,false,&mut ||false).unwrap();
  let actual=run(&p,p.rng_mode,sources.each_ref().map(|p|p.as_path()),outputs.each_ref().map(|p|p.as_path()),&mut ||false,true);
  if let Err(error)=&actual{assert!(outputs.iter().all(|path|!path.exists()),"failed candidate left output: {error}");}
  let result=actual.unwrap();
  for key in ["poissonCountMismatches","binomialCountMismatches","randomWordCountMismatches","samplerTupleMismatches"]{assert_eq!(result[key],0,"{label}: {key}");}
  assert_eq!(result["samplerSampleCount"],3280);assert_eq!(result["exactRngWordsChecked"],98304);assert_eq!(result["softwareArithmeticChecks"],1280);
  let contract=graph::execution_contract(&p);assert_eq!(result["dispatches"],contract.dispatches);assert_eq!(result["numericFlagReads"],contract.numeric_flag_reads);assert_eq!(result["readbackBytes"],contract.readback_bytes);assert_eq!(result["chunkCount"],p.chunk_count());
  let mut max=0f64;let mut squared=0.;let mut count=0;
  for ch in 0..3{assert_eq!(std::fs::read(&sources[ch]).unwrap(),source_bytes[ch]);let raw=std::fs::read(&outputs[ch]).unwrap();assert_eq!(raw.len(),input[ch].len()*8);for(b,e)in raw.chunks_exact(8).zip(&expected[ch]){let a=f64::from_le_bytes(b.try_into().unwrap());assert!(a.is_finite());let delta=(a-e).abs();max=max.max(delta);squared+=delta*delta;count+=1;}}
  let rms=(squared/count as f64).sqrt();eprintln!("{label} {:?} {}x{} origin={:?} chunks={}: max_abs={max}, rms={rms}",p.rng_mode,p.width,p.height,p.origin,p.chunk_count());assert!(max<=5e-4 && rms<=1e-4,"{label}: max={max}, rms={rms}");
 }
 #[test]
 #[cfg(feature="precision-f64")]
 #[ignore = "actual original full-effects Vulkan comparison in both RNG modes"]
 fn actual_family_full_grain_matches_original_effects(){
  for mode in [RngMode::SequentialMt19937,RngMode::CoordinateSplitMix64]{assert_actual_full_effects(fixture(mode),"full-17x17");}
 }
 #[test]
 #[cfg(feature="precision-f64")]
 #[ignore = "actual original full-effects MT Vulkan: seventeen real256-pixel chunks and16-chunk submission boundary"]
 fn actual_phased_sequential_65x65_crosses_seventeen_chunks(){
  let mut p=fixture(RngMode::SequentialMt19937);p.width=65;p.height=65;
  assert_eq!(graph::frame_capacity(p.rng_mode),256);assert_eq!(p.chunk_count(),17);assert_eq!(graph::submission_batch_chunks(p.rng_mode),16);
  assert_actual_full_effects(p,"sequential-65x65");
 }
 #[test]
 #[cfg(feature="precision-f64")]
 #[ignore = "actual original full-effects coordinate Vulkan: real16384-pixel split plus both-coordinate wrap"]
 fn actual_phased_coordinate_129x129_wraps_origin(){
  let mut p=fixture(RngMode::CoordinateSplitMix64);p.width=129;p.height=129;p.origin=[u32::MAX-63,u32::MAX-63];
  assert_eq!(graph::frame_capacity(p.rng_mode),16384);assert_eq!(p.chunk_count(),2);assert!(p.origin[0].checked_add(p.width-1).is_none());assert!(p.origin[1].checked_add(p.height-1).is_none());
  assert_actual_full_effects(p,"coordinate-129x129-wrap");
 }
 #[test]
 #[cfg(feature="precision-f64")]
 #[ignore = "actual1x1 Vulkan in both capacities with complete3280-tuple/98304-word/1280-arithmetic admission"]
 fn actual_phased_tiny_1x1_keeps_full_admission(){
  for mode in [RngMode::SequentialMt19937,RngMode::CoordinateSplitMix64]{let mut p=fixture(mode);p.width=1;p.height=1;assert_eq!(p.chunk_count(),1);assert_actual_full_effects(p,"tiny-1x1");}
 }
 #[test]
 #[ignore = "actual Vulkan candidate failure/cancellation after output creation must remove all partial planes"]
 fn actual_phased_failure_and_cancel_remove_candidate_planes(){
  for cancel in [false,true]{
   let mut p=fixture(RngMode::SequentialMt19937);p.width=1;p.height=1;let root=Temp::new(if cancel{"cancel"}else{"nonfinite"});let(_,sources,outputs)=case_files(&root,&p);
   if !cancel{std::fs::write(&sources[1],f64::NAN.to_le_bytes()).unwrap();}
   let source_bytes:[Vec<u8>;3]=std::array::from_fn(|ch|std::fs::read(&sources[ch]).unwrap());let mut saw_output=false;
   let result=run(&p,p.rng_mode,sources.each_ref().map(|p|p.as_path()),outputs.each_ref().map(|p|p.as_path()),&mut ||{if outputs[0].exists(){saw_output=true;}cancel&&saw_output},true);
   assert!(result.is_err());assert!(saw_output,"failure must occur after a candidate file was created");if !cancel{assert_eq!(result.unwrap_err(),"NONFINITE_GRAIN_INPUT");}
   assert!(outputs.iter().all(|path|!path.exists()));for ch in 0..3{assert_eq!(std::fs::read(&sources[ch]).unwrap(),source_bytes[ch]);}
  }
 }

 fn read_test_frames(r:&Runner,capacity:u32,m:&mut RunMetrics)->Vec<u8>{
  let bytes=capacity as u64*544;let mut output=Vec::with_capacity(bytes as usize);let mut offset=0;
  while offset<bytes{let count=(bytes-offset).min(spatial::TRANSFER_BYTES);output.extend(r.test_read_grain_scratch(4800+offset,count,&mut ||false,m).unwrap());offset+=count;}output
 }
 #[test]
 #[ignore = "actual typed scratch on Vulkan: padded lanes and malformed ALU descriptors cannot mutate any frame"]
 fn actual_phased_padded_lanes_and_malformed_descriptors_are_isolated(){
  let all=graph::opcode_families::shaders().unwrap();let families:Vec<_>=all.into_iter().filter(|(ops,_)|ops.iter().any(|op|[63,65,66,64].contains(op))).collect();assert_eq!(families.len(),4);
  for mode in [RngMode::SequentialMt19937,RngMode::CoordinateSplitMix64]{
   let mut p=fixture(mode);p.width=1;p.height=1;let capacity=graph::frame_capacity(mode);
   let(r,mut m)=Runner::new_with_grain_shader_families(&p.spatial_plan().unwrap(),true,&families,GrainScratchConfig::new(capacity).unwrap()).unwrap();
   let initial=vec![0x5au8;capacity as usize*544];for(offset,chunk)in initial.chunks(spatial::TRANSFER_BYTES as usize).enumerate(){r.test_upload_grain_scratch(4800+offset as u64*spatial::TRANSFER_BYTES,chunk,&mut ||false,&mut m).unwrap();}
   let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();r.upload_aux(graph::FACTORIAL_BASE as u64*4,&graph::encode((0..LOG_FACTORIAL_COUNT).map(|k|table.log_factorial(k as i64))),&mut ||false,&mut m).unwrap();
   let steps:Vec<u8>=graph::ADMISSION_STEPS.iter().flat_map(|&n|(n as f32).to_le_bytes()).collect();r.upload_aux(graph::TABLE_BASE as u64*4,&steps,&mut ||false,&mut m).unwrap();
   //65 active lanes require128 physical invocations.63 padded lanes must not
   // touch frame65 onward, even though the bound1x1 image is much smaller.
   let base=560u32;let count=65u32;let start=m.dispatches;
   for op in [63,65,66,64]{r.dispatch(diagnostic_params(&p,op,graph::INPUT_CENSUS,base,count),&mut ||false,&mut m).unwrap();}
   assert_eq!(m.dispatches-start,4);assert_eq!(r.read_error_flag(&mut ||false,&mut m).unwrap(),0);
   let after=read_test_frames(&r,capacity,&mut m);assert_ne!(&after[..count as usize*544],&initial[..count as usize*544]);assert_eq!(&after[count as usize*544..],&initial[count as usize*544..]);
   let data=floats(&r.read_probe((base+count)as u64*20,&mut ||false,&mut m).unwrap());
   for i in base..base+count{let(seed,x,y,lam,pb)=graph::diagnostic_case(i);let e=spektrafilm_model::grain::coordinate_particle_sample(seed,x,y,pb,1.,lam,0.);assert_eq!(&data[i as usize*5..i as usize*5+5],&[e.0 as f32,e.1 as f32,e.2 as f32,e.3 as f32,0.]);}
   // These mutable uploads exist only under cfg(test). Each trial is a fresh
   // independent comparison, with immutable-code identity restored beforehand.
   // Invalid opcode, destination and uninitialized source cover both tables.
   for (offset,word) in [(0u64,99u32),(4,32),(24,31),(1488,99),(1492,32),(1496,31)]{
    for op in [65,64]{
     r.test_upload_grain_scratch(0,super::super::alu_programs::CODE_PREFIX,&mut ||false,&mut m).unwrap();r.test_reset_grain_error_flag(&mut ||false,&mut m).unwrap();r.test_upload_grain_scratch(offset,&word.to_le_bytes(),&mut ||false,&mut m).unwrap();
     let before=m.dispatches;r.dispatch(diagnostic_params(&p,op,graph::INPUT_CENSUS,base,count),&mut ||false,&mut m).unwrap();assert_eq!(m.dispatches-before,1);assert_eq!(r.read_error_flag(&mut ||false,&mut m).unwrap(),1);
     assert_eq!(read_test_frames(&r,capacity,&mut m),after,"mode={mode:?} op={op} byte={offset}: rejected begin wrote frame storage");
    }
   }
   r.test_upload_grain_scratch(0,super::super::alu_programs::CODE_PREFIX,&mut ||false,&mut m).unwrap();r.test_reset_grain_error_flag(&mut ||false,&mut m).unwrap();
   let mut malformed=diagnostic_params(&p,65,graph::INPUT_CENSUS,base,count);malformed[4]=count+1;let before=m.dispatches;assert!(r.dispatch(malformed,&mut ||false,&mut m).is_err());assert_eq!(m.dispatches,before);assert_eq!(read_test_frames(&r,capacity,&mut m),after);
  }
 }
}
