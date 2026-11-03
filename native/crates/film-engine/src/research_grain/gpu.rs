//! Isolated-helper execution of the complete grain graph. Images stay on the
//! device through layer sampling, clouds, clump multiplication and final blur.
use super::*;
use crate::research_spatial::{check,gpu::{Runner,RunMetrics,params}};
use std::{fs::{File,OpenOptions},io::{Read,Write,Seek,SeekFrom},path::Path,time::Instant};
#[derive(Clone,Copy)]struct Table {axis:u32,values:u32,knots:u32}
#[derive(Default,Serialize)]
#[serde(rename_all="camelCase")]
struct Census {
    exact_rng_words_checked:u64,sampler_sample_count:u64,poisson_count_mismatches:u64,
    binomial_count_mismatches:u64,random_word_count_mismatches:u64,
    host_f32_count_mismatches:u64,sampler_max_abs:f64,sampler_rms:f64,
    #[serde(skip)] sum_square:f64,
}
fn pack_tables(p:&GrainPlan)->(Vec<u8>,[Vec<Table>;3]){
    let mut data=Vec::<f32>::new();
    let layout=std::array::from_fn(|ch|p.layers[ch].iter().map(|l|{
        let axis=TABLE_BASE+data.len() as u32;data.extend(l.axis.iter().map(|&x|x as f32));
        let values=TABLE_BASE+data.len() as u32;data.extend(l.values.iter().map(|&x|x as f32));
        Table {axis,values,knots:l.axis.len() as u32}
    }).collect());
    (data.iter().flat_map(|x|x.to_le_bytes()).collect(),layout)
}
fn layer_params(p:&GrainPlan,l:&Layer,t:Table,offset:u64,count:usize,op:u32)->[u32;40]{
    let mut v=params(p.width,p.height,op);v[4]=count as u32;v[7]=offset as u32;
    v[8]=l.seed as u32;v[9]=(l.seed>>32) as u32;v[10]=u32::from(p.positive);
    for(i,x)in[l.density_min,l.density_max,l.particles,l.uniformity].into_iter().enumerate(){v[12+i]=(x as f32).to_bits();}
    v[16]=t.axis;v[17]=t.values;v[18]=t.knots;v[28]=p.origin[0];v[29]=p.origin[1];v
}
fn read64(f:&mut File,n:usize)->R<Vec<f64>>{
    let mut raw=vec![0u8;n*8];f.read_exact(&mut raw).map_err(|_|"GPU_GRAIN_IO")?;
    let v:Vec<f64>=raw.chunks_exact(8).map(|b|f64::from_le_bytes(b.try_into().unwrap())).collect();
    if v.iter().any(|&x|!finite(x)){return Err("NONFINITE_GRAIN_INPUT".into());}Ok(v)
}
fn floats(raw:&[u8])->Vec<f32>{raw.chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect()}
fn rng_guard(r:&Runner,p:&GrainPlan,c:&mut impl FnMut()->bool,m:&mut RunMetrics,census:&mut Census)->R<()>{
    for(seed,origin)in[(0,[0u32,0u32]),(u64::MAX,[u32::MAX-7,u32::MAX-3]),(p.seed,[31,209])]{
        let mut v=params(17,121,27);v[4]=2048;v[8]=seed as u32;v[9]=(seed>>32) as u32;v[28]=origin[0];v[29]=origin[1];
        r.dispatch(v,c,m)?;let data=floats(&r.read_probe(2048*8*4*4,c,m)?);
        for i in 0..2048u32 {check(c)?;let words=spektrafilm_model::grain::coordinate_random_words(seed,origin[0].wrapping_add(i%17),origin[1].wrapping_add(i/17),8);
            for(j,&word)in words.iter().enumerate(){let parts=&data[(i as usize*8+j)*4..(i as usize*8+j+1)*4];
                for(k,&part)in parts.iter().enumerate(){if part!=((word>>(16*k))&65535) as f32{return Err("GPU_GRAIN_INTEGER_RNG_CHECK".into());}}
                census.exact_rng_words_checked+=1;
            }
        }
    }Ok(())
}
fn sampler_guard(r:&Runner,p:&GrainPlan,l:&Layer,t:Table,offset:u64,count:usize,input:&mut File,table:&[f32],c:&mut impl FnMut()->bool,m:&mut RunMetrics,census:&mut Census)->R<()>{
    let indices=crate::research_dir::sample_indices(count);
    let mut v=layer_params(p,l,t,offset,indices.len(),28);v[5]=count as u32;
    r.dispatch(v,c,m)?;let raw=floats(&r.read_probe(indices.len() as u64*20,c,m)?);
    let at=Instant::now();
    for(i,a)in indices.into_iter().zip(raw.chunks_exact(5)){
        check(c)?;let index=offset+i as u64;input.seek(SeekFrom::Start(index*8)).map_err(|_|"GPU_GRAIN_IO")?;
        let original=read64(input,1)?[0];let x=p.origin[0].wrapping_add(index as u32%p.width);let y=p.origin[1].wrapping_add(index as u32/p.width);
        let reference=spektrafilm_model::grain::coordinate_particle_sample(l.seed,x,y,l.mapped_f64(original,p.positive),l.density_max,l.particles,l.uniformity);
        let mirror=sampler::particle(l.seed,x,y,l.mapped_f32(original as f32,p.positive),l.density_max as f32,l.particles as f32,l.uniformity as f32,table)?;
        // Full output is finite-scanned before publication. This sparse diagnostic
        // proves repeated invocation consistency and records divergence; it is NOT
        // an assertion that stochastic f32/f64 appearance is unchanged.
        if a.iter().any(|x|!x.is_finite()) || a[0].to_bits()!=a[4].to_bits() || a[1]<0. || a[1]>=LOG_FACTORIAL_COUNT as f32 || a[2]<0. || a[2]>a[1] || a[1].fract()!=0. || a[2].fract()!=0. || a[3].fract()!=0. {return Err("GPU_GRAIN_NUMERIC_CHECK".into());}
        census.sampler_sample_count+=1;
        census.poisson_count_mismatches+=u64::from(a[1] as u64!=reference.0);
        census.binomial_count_mismatches+=u64::from(a[2] as u64!=reference.1);
        census.random_word_count_mismatches+=u64::from(a[3] as u64!=reference.2);
        census.host_f32_count_mismatches+=u64::from(a[1] as u32!=mirror.particles||a[2] as u32!=mirror.developed||a[3] as u32!=mirror.words);
        let error=(a[0] as f64-reference.3).abs();census.sampler_max_abs=census.sampler_max_abs.max(error);census.sum_square+=error*error;
    }
    m.guard_ms+=at.elapsed().as_secs_f64()*1000.;Ok(())
}
struct Outputs<'a>{paths:[&'a Path;3],created:usize,keep:bool}
impl Drop for Outputs<'_>{fn drop(&mut self){if !self.keep{for p in &self.paths[..self.created]{let _=std::fs::remove_file(p);}}}}
pub fn run_files(p:&GrainPlan,sources:[&Path;3],outputs:[&Path;3],c:&mut impl FnMut()->bool)->R<serde_json::Value>{
    if p.rng_mode!=RngMode::CoordinateSplitMix64{return Err("GPU_GRAIN_SEQUENTIAL_PENDING".into());}
    run_files_inner(p,sources,outputs,c,false).map_err(|e|e.replace("OPTICS","GRAIN"))
}
fn run_files_inner(p:&GrainPlan,sources:[&Path;3],outputs:[&Path;3],c:&mut impl FnMut()->bool,allow_probe:bool)->R<serde_json::Value>{
    let started=Instant::now();p.numerical_preflight(c)?;let n=p.width as u64*p.height as u64;let chunk=p.chunk_pixels() as usize;
    for i in 0..3 {if sources.contains(&outputs[i])||outputs[..i].contains(&outputs[i]){return Err("INVALID_GRAIN_FILE".into());}
        let meta=std::fs::symlink_metadata(sources[i]).map_err(|_|"GPU_GRAIN_IO")?;if !meta.is_file()||meta.file_type().is_symlink()||meta.len()!=n*8{return Err("INVALID_GRAIN_FILE".into());}}
    let memory=p.spatial_plan()?;let (r,mut m)=Runner::new_with_shader(&memory,allow_probe,&shader())?;
    let mut census=Census::default();rng_guard(&r,p,c,&mut m,&mut census)?;
    check(c)?;let table=sampler::factorial_table();let bytes:Vec<u8>=table.iter().flat_map(|x|x.to_le_bytes()).collect();
    r.upload_aux(LOG_FACTORIAL_BASE as u64*4,&bytes,c,&mut m)?;drop(bytes);
    let(bytes,tables)=pack_tables(p);if !bytes.is_empty(){r.upload_aux(TABLE_BASE as u64*4,&bytes,c,&mut m)?;}drop(bytes);
    let mut ownership=Outputs {paths:outputs,created:0,keep:false};
    for ch in 0..3 {
        check(c)?;let mut input=File::open(sources[ch]).map_err(|_|"GPU_GRAIN_IO")?;let mut offset=0;
        while offset<n {check(c)?;let count=(n-offset).min(chunk as u64) as usize;let values=read64(&mut input,count)?;
            let bytes:Vec<u8>=values.iter().flat_map(|&x|(x as f32).to_le_bytes()).collect();r.upload_aux(CHUNK_BASE as u64*4,&bytes,c,&mut m)?;
            let mut v=params(p.width,p.height,20);v[4]=count as u32;v[7]=offset as u32;r.dispatch(v,c,&mut m)?;offset+=count as u64;}
        r.dispatch(params(p.width,p.height,5),c,&mut m)?;
        for(sl,layer)in p.layers[ch].iter().enumerate(){
            let mut offset=0;while offset<n {check(c)?;let count=(n-offset).min(chunk as u64) as usize;
                r.dispatch(layer_params(p,layer,tables[ch][sl],offset,count,21),c,&mut m)?;
                sampler_guard(&r,p,layer,tables[ch][sl],offset,count,&mut input,&table,c,&mut m,&mut census)?;offset+=count as u64;}
            if layer.cloud_sigma>0.{r.blur(p.width,p.height,layer.cloud_sigma,c,&mut m)?;}
            let mut v=params(p.width,p.height,23);v[26]=if layer.cloud_sigma>0.{2}else{0};r.dispatch(v,c,&mut m)?;
        }
        if p.micro_active {
            let seed=p.micro_seed(ch);let mut v=params(p.width,p.height,22);v[8]=seed as u32;v[9]=(seed>>32) as u32;v[24]=(p.micro_mu as f32).to_bits();v[25]=(p.micro_sigma_log as f32).to_bits();v[28]=p.origin[0];v[29]=p.origin[1];
            r.dispatch(v,c,&mut m)?;if p.micro_spread>0.{r.blur(p.width,p.height,p.micro_spread,c,&mut m)?;}
            let mut v=params(p.width,p.height,24);v[26]=if p.micro_spread>0.{2}else{0};r.dispatch(v,c,&mut m)?;
        }
        if p.active {
            let mut v=params(p.width,p.height,25);v[22]=(if p.sublayers{1.}else{1./p.layer_count() as f64} as f32).to_bits();v[23]=(p.density_min[ch] as f32).to_bits();r.dispatch(v,c,&mut m)?;
            if p.final_sigma>0.{r.blur(p.width,p.height,p.final_sigma,c,&mut m)?;let mut v=params(p.width,p.height,4);v[6]=2;v[7]=0;r.dispatch(v,c,&mut m)?;}
            if p.amount!=1.{let mut v=params(p.width,p.height,26);v[21]=(p.amount as f32).to_bits();r.dispatch(v,c,&mut m)?;}
        }else{let mut v=params(p.width,p.height,4);v[6]=3;v[7]=0;r.dispatch(v,c,&mut m)?;}
        let mut output=OpenOptions::new().write(true).create_new(true).open(outputs[ch]).map_err(|_|"GPU_GRAIN_IO")?;ownership.created+=1;
        let mut offset=0;while offset<n {check(c)?;let count=(n-offset).min(chunk as u64);let raw=r.read_source(offset*4,count*4,c,&mut m)?;
            let values=floats(&raw);if values.iter().any(|x|!x.is_finite()){return Err("NONFINITE_GRAIN_OUTPUT".into());}
            let encoded:Vec<u8>=values.iter().flat_map(|&x|(x as f64).to_le_bytes()).collect();output.write_all(&encoded).map_err(|_|"GPU_GRAIN_IO")?;offset+=count;}
        output.sync_all().map_err(|_|"GPU_GRAIN_IO")?;
    }
    check(c)?;m.sample_rms=(m.sample_sum/m.sample_count.max(1) as f64).sqrt();m.elapsed_ms=started.elapsed().as_secs_f64()*1000.;
    census.sampler_rms=(census.sum_square/census.sampler_sample_count.max(1) as f64).sqrt();
    let mut result=serde_json::to_value(m).map_err(|_|"INVALID_GRAIN_RESULT")?;let object=result.as_object_mut().ok_or("INVALID_GRAIN_RESULT")?;
    for(k,v)in serde_json::to_value(census).map_err(|_|"INVALID_GRAIN_RESULT")?.as_object().ok_or("INVALID_GRAIN_RESULT")?{object.insert(k.clone(),v.clone());}
    object.insert("stochasticScope".into(),serde_json::json!("8 samples per layer/chunk versus original f64 counts, draw consumption and value; not full-image quality proof"));
    object.insert("integerRng".into(),serde_json::json!("original absolute-coordinate SplitMix64; exact u32-pair words"));
    object.insert("distributionArithmetic".into(),serde_json::json!("f32 original Poisson multiplication/PTRS and binomial inversion/BTPE; counts may differ"));
    object.insert("calibrationCpu".into(),serde_json::json!("profile constants, original libm log-factorial table, filter coefficients and diagnostic oracles"));
    object.insert("grainActive".into(),serde_json::json!(p.active));object.insert("sublayers".into(),serde_json::json!(p.sublayers));object.insert("layerCount".into(),serde_json::json!(p.layer_count()));object.insert("chunkCount".into(),serde_json::json!(p.chunk_count()));
    ownership.keep=true;Ok(result)
}
