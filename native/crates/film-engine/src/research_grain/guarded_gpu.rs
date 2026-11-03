//! Guarded full grain execution selected by the experimental public stage runner.
//! Every device request must pass RNG/arithmetic/sampling gates before candidate
//! publication. Host source admission is not a claim of GPU preview speed.
use super::{*,guarded_graph::{self as graph,RngMode,Table}};
use crate::research_spatial::{check,gpu::{Runner,RunMetrics,params}};
use std::{fs::{File,OpenOptions},io::{Read,Write,Seek,SeekFrom},path::Path,time::Instant};
#[derive(Default,Serialize)]#[serde(rename_all="camelCase")]
struct Census {exact_rng_words_checked:u64,software_arithmetic_checks:u64,sampler_sample_count:u64,poisson_count_mismatches:u64,binomial_count_mismatches:u64,random_word_count_mismatches:u64,sampler_tuple_mismatches:u64,sampler_max_abs:f64,sampler_rms:f64,numeric_flag_reads:u64}
fn floats(raw:&[u8])->Vec<f32>{raw.chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect()}
fn flag(r:&Runner,c:&mut impl FnMut()->bool,m:&mut RunMetrics,s:&mut Census)->R<()>{s.numeric_flag_reads+=1;if r.read_error_flag(c,m)?!=0{return Err("GPU_GRAIN_GUARDED_REJECTION".into());}Ok(())}
fn layer_params(p:&GrainPlan,l:&Layer,t:Table,mode:RngMode,offset:u64,count:usize,op:u32)->[u32;40]{
 let mut v=params(p.width,p.height,op);v[4]=if mode==RngMode::SequentialMt19937&&matches!(op,52|53|55){1}else{count as u32};v[5]=count as u32;v[7]=offset as u32;v[8]=l.seed as u32;v[9]=(l.seed>>32)as u32;v[10]=u32::from(p.positive);v[11]=u32::from(mode==RngMode::SequentialMt19937);v[16]=t.axis;v[17]=t.values;v[18]=t.knots;v[28]=p.origin[0];v[29]=p.origin[1];v
}
fn read_chunk(input:&mut File,offset:u64,count:usize,c:&mut impl FnMut()->bool)->R<Vec<u8>>{
 check(c)?;input.seek(SeekFrom::Start(offset*8)).map_err(|_|"GPU_GRAIN_IO")?;let mut raw=vec![0u8;count*8];input.read_exact(&mut raw).map_err(|_|"GPU_GRAIN_IO")?;
 let values:Vec<f64>=raw.chunks_exact(8).map(|b|f64::from_le_bytes(b.try_into().unwrap())).collect();if values.iter().any(|x|!x.is_finite()){return Err("NONFINITE_GRAIN_INPUT".into());}
 // Crucially no as-f32 conversion: the three numeric lanes carry all64 bits.
 Ok(graph::encode(values))
}
fn init_mt(r:&Runner,p:&GrainPlan,seed:u64,c:&mut impl FnMut()->bool,m:&mut RunMetrics)->R<()>{let mut v=params(p.width,p.height,40);v[4]=1;v[8]=seed as u32;r.dispatch(v,c,m)}
fn diagnostics(r:&Runner,p:&GrainPlan,c:&mut impl FnMut()->bool,m:&mut RunMetrics,s:&mut Census)->R<()>{
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
 let mut v=params(p.width,p.height,61);v[4]=graph::SAMPLER_CHECKS;r.dispatch(v,c,m)?;let data=floats(&r.read_probe(graph::SAMPLER_CHECKS as u64*20,c,m)?);let mut squared=0.;
 for i in 0..graph::SAMPLER_CHECKS{check(c)?;let(seed,x,y,lambda,probability)=graph::diagnostic_case(i);let expected=spektrafilm_model::grain::coordinate_particle_sample(seed,x,y,probability,1.,lambda,0.);let a=&data[5*i as usize..5*i as usize+5];
  s.poisson_count_mismatches+=u64::from(a[0]!=expected.0 as f32);s.binomial_count_mismatches+=u64::from(a[1]!=expected.1 as f32);s.random_word_count_mismatches+=u64::from(a[2]!=expected.2 as f32);
  s.sampler_tuple_mismatches+=u64::from(a[0]!=expected.0 as f32||a[1]!=expected.1 as f32||a[2]!=expected.2 as f32||a[3].to_bits()!=(expected.3 as f32).to_bits()||a[4]!=0.);
  let error=(a[3]as f64-expected.3).abs();if !error.is_finite(){return Err("GPU_GRAIN_NUMERIC_CHECK".into());}s.sampler_max_abs=s.sampler_max_abs.max(error);squared+=error*error;s.sampler_sample_count+=1;
 }
 // Exercise the real parallel prepass and its stored-bit consumer on device.
 // These are additional gates, not replacements for the existing3120 tuples.
 let density:Vec<f64>=(0..graph::PREPASS_CHECKS).map(|i|0.1+i as f64/40.).collect();
 r.upload_aux(graph::PARAM_BASE as u64*4,&graph::encode([0.,1.,2300.,0.97,0.,0.]),c,m)?;
 r.upload_aux(graph::CHUNK_BASE as u64*4,&graph::encode(density.iter().copied()),c,m)?;
 let mut v=params(17,2,63);v[4]=graph::PREPASS_CHECKS;v[5]=graph::PREPASS_CHECKS;v[8]=42;v[28]=31;v[29]=209;r.dispatch(v,c,m)?;
 v[2]=64;r.dispatch(v,c,m)?;let data=floats(&r.read_probe(graph::PREPASS_CHECKS as u64*56,c,m)?);
 for(i,&d)in density.iter().enumerate(){
  let pb=d.clamp(1e-6,1.-1e-6);let sat=1.-pb*0.97*(1.-1e-6);let lam=2300./sat;let encoded=floats(&graph::encode([pb,sat,lam]));let a=&data[14*i..14*i+14];
  if a[..9]!=encoded[..]{return Err("GPU_GRAIN_PREPASS_BITS_CHECK".into());}
  let expected=spektrafilm_model::grain::coordinate_particle_sample(42,31+i as u32%17,209+i as u32/17,d,1.,2300.,0.97);
  s.poisson_count_mismatches+=u64::from(a[9]!=expected.0 as f32);s.binomial_count_mismatches+=u64::from(a[10]!=expected.1 as f32);s.random_word_count_mismatches+=u64::from(a[11]!=expected.2 as f32);
  s.sampler_tuple_mismatches+=u64::from(a[9]!=expected.0 as f32||a[10]!=expected.1 as f32||a[11]!=expected.2 as f32||a[12].to_bits()!=(expected.3 as f32).to_bits()||a[13]!=0.);
  let error=(a[12]as f64-expected.3).abs();if !error.is_finite(){return Err("GPU_GRAIN_NUMERIC_CHECK".into());}s.sampler_max_abs=s.sampler_max_abs.max(error);squared+=error*error;s.sampler_sample_count+=1;
 }
 s.sampler_rms=(squared/s.sampler_sample_count as f64).sqrt();if s.sampler_tuple_mismatches!=0{return Err("GPU_GRAIN_SAMPLER_TUPLE_CHECK".into());}flag(r,c,m,s)
}
struct Outputs<'a>{paths:[&'a Path;3],created:usize,keep:bool}
impl Drop for Outputs<'_>{fn drop(&mut self){if !self.keep{for p in&self.paths[..self.created]{let _=std::fs::remove_file(p);}}}}
pub fn run_files(p:&GrainPlan,mode:RngMode,sources:[&Path;3],outputs:[&Path;3],c:&mut impl FnMut()->bool)->R<serde_json::Value>{if !graph::SOURCE_ADMITTED{return Err("GPU_GRAIN_GUARDED_PENDING".into());}run(p,mode,sources,outputs,c,false).map_err(|e|e.replace("OPTICS","GRAIN").replace("TAIL","GRAIN"))}
fn run(p:&GrainPlan,mode:RngMode,sources:[&Path;3],outputs:[&Path;3],c:&mut impl FnMut()->bool,allow_probe:bool)->R<serde_json::Value>{
 let started=Instant::now();graph::validate(p,mode)?;p.numerical_preflight(c)?;let n=p.width as u64*p.height as u64;let chunk=mode.chunk_pixels(n)as usize;
 for i in 0..3{if sources.contains(&outputs[i])||outputs[..i].contains(&outputs[i]){return Err("INVALID_GRAIN_FILE".into());}let meta=std::fs::symlink_metadata(sources[i]).map_err(|_|"GPU_GRAIN_IO")?;if !meta.is_file()||meta.file_type().is_symlink()||meta.len()!=n*8{return Err("INVALID_GRAIN_FILE".into());}}
 let(r,mut m)=Runner::new_with_shader_error(&p.spatial_plan()?,allow_probe,&graph::shader()?)?;let mut census=Census::default();let admission=Instant::now();diagnostics(&r,p,c,&mut m,&mut census)?;m.setup_ms+=admission.elapsed().as_secs_f64()*1000.;
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
   let operations:&[u32]=if mode==RngMode::SequentialMt19937{&[52,53]}else{&[51]};
   for &op in operations {let mut offset=0;while offset<n{
    let mut batch=r.batch();
    for _ in 0..graph::submission_batch_chunks(mode){if offset==n{break;}let count=(n-offset).min(chunk as u64)as usize;let bytes=read_chunk(&mut input,offset,count,c)?;if mode==RngMode::SequentialMt19937{
     batch.submit_chunk(Some((graph::CHUNK_BASE as u64*4,&bytes)),layer_params(p,l,tables[ch][sl],mode,offset,count,63),c,&mut m)?;
     batch.submit_chunk(None,layer_params(p,l,tables[ch][sl],mode,offset,count,op),c,&mut m)?;
    }else{batch.submit_chunk(Some((graph::CHUNK_BASE as u64*4,&bytes)),layer_params(p,l,tables[ch][sl],mode,offset,count,op),c,&mut m)?;}offset+=count as u64;}
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
