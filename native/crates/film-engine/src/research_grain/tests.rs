use super::*;
use std::{fs::{self,File,OpenOptions},io::Write,path::{Path,PathBuf},sync::atomic::{AtomicU64,Ordering}};
static NEXT:AtomicU64=AtomicU64::new(1);
struct Root(PathBuf);
impl Root{fn new()->Self{let p=std::env::temp_dir().join(format!("film-grain-test-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&p).unwrap();Self(p)}}
impl Drop for Root{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
fn fixture(w:u32,h:u32,sublayers:bool,positive:bool,mono:bool)->GrainPlan{
    let mut p=GrainPlan {width:w,height:h,active:true,sublayers,positive,monochrome:mono,seed:u64::MAX-7,origin:[0,0],rng_mode:RngMode::CoordinateSplitMix64,amount:0.6,density_min:vec![0.03,0.04,0.05],final_sigma:0.65,micro_active:sublayers,micro_spread:1.3,micro_sigma_log:0.12,micro_mu:-0.5*0.12*0.12,layers:std::array::from_fn(|_|vec![])};
    for ch in 0..3 {for sl in 0..3 {let f=(sl+1) as f64/6.;
        p.layers[ch].push(Layer {seed:p.seed.wrapping_add(if mono{0}else{ch as u64}).wrapping_add(sl as u64*10),density_min:if sublayers{f*p.density_min[ch]}else{p.density_min[ch]},density_max:if sublayers{f*(2.4+p.density_min[ch])}else{2.4+p.density_min[ch]},particles:match sl{0=>2.3,1=>57.0,_=>1700.},uniformity:0.97,
            axis:if !sublayers{vec![]}else if positive{vec![-2.4,-1.7,-0.7,-0.2,0.]}else{vec![0.,0.2,0.7,1.7,2.4]},
            values:if !sublayers{vec![]}else if positive{vec![2.4*f,1.7*f,0.7*f,0.2*f,0.]}else{vec![0.,0.2*f,0.7*f,1.7*f,2.4*f]},cloud_sigma:if sublayers{0.6+sl as f32*1.1}else{0.}});
    }}p
}
fn input(w:u32,h:u32)->[Vec<f64>;3]{std::array::from_fn(|ch|(0..w*h).map(|i|match i%11{0=>-1.,1=>100.,2=>0.,_=>((i*37+ch as u32*17)%251)as f64/110.}).collect())}
fn write_inputs(root:&Root,v:&[Vec<f64>;3])->[PathBuf;3]{std::array::from_fn(|ch|{let path=root.0.join(format!("source-{ch}"));let mut f=File::create(&path).unwrap();for x in &v[ch]{f.write_all(&x.to_le_bytes()).unwrap();}path})}
#[test]fn shader_validates_without_f64_capability(){let s=shader();let m=naga::front::wgsl::parse_str(&s).unwrap_or_else(|e|panic!("{}",e.emit_to_string(&s)));naga::valid::Validator::new(naga::valid::ValidationFlags::all(),naga::valid::Capabilities::empty()).validate(&m).unwrap();}
#[test]fn pair_arithmetic_and_production_random_words_exact(){
    use sampler::{Pair,Rng};let edge=[0,1,65535,65536,u32::MAX as u64,1u64<<32,u64::MAX-1,u64::MAX];
    for a in edge{for b in edge{assert_eq!(Pair::from(a).add(Pair::from(b)).value(),a.wrapping_add(b));assert_eq!(Pair::from(a).mul(Pair::from(b)).value(),a.wrapping_mul(b));}}
    for seed in [0,42,u64::MAX]{for origin in [[0u32,0u32],[31,209],[u32::MAX-7,u32::MAX-3]]{for i in 0..4096u32{
        let x=origin[0].wrapping_add(i%71);let y=origin[1].wrapping_add(i/71);let oracle=spektrafilm_model::grain::coordinate_random_words(seed,x,y,16);
        let mut actual=Rng::new(seed,x,y,&[]);assert_eq!((0..16).map(|_|actual.next_word()).collect::<Vec<_>>(),oracle);
    }}}
}
#[test]fn tile_absolute_coordinates_and_seed_wrap_unchanged(){
    let p=fixture(17,13,true,false,true);let l=&p.layers[0][2];let table=sampler::factorial_table();let sample=|x,y|sampler::particle(l.seed,x,y,0.37,l.density_max as f32,l.particles as f32,l.uniformity as f32,&table).unwrap().value;
    let whole:Vec<f32>=(0..17*13).map(|i|sample(i%17,i/17)).collect();let mut chunks=Vec::new();for y in(0..13).step_by(3){for i in 0..17*3.min(13-y){chunks.push(sample(i%17,y+i/17));}}
    assert_eq!(whole,chunks);for sl in 0..3{assert_eq!(p.layers[0][sl].seed,p.layers[2][sl].seed);}assert_eq!(p.layers[0][2].seed,12);
}
#[test]fn descriptor_roundtrip_original_profile_all_controls(){
    use spektrafilm_core::profile::load_profile_by_name;
    let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    for name in ["kodak_portra_400","kodak_ektachrome_100"]{for sublayers in [false,true]{
        let mut params=RuntimeParams::default();params.film_render.grain.coordinate_origin=Some([0,0]);params.film_render.grain.sublayers_active=sublayers;params.film_render.grain.n_sub_layers=4;
        params.film_render.grain.seed=u64::MAX;params.film_render.grain.amount=0.7;params.film_render.grain.monochrome=true;
        let pipe=Pipeline::new(load_profile_by_name(&data,name).unwrap(),load_profile_by_name(&data,"kodak_portra_endura").unwrap(),params);
        let p=GrainPlan::new(&pipe,6.,17,11).unwrap();let encoded=serde_json::to_vec(&p).unwrap();let decoded:GrainPlan=serde_json::from_slice(&encoded).unwrap();assert_eq!(p,decoded);decoded.validate().unwrap();
        assert_eq!(p.layer_count(),if sublayers{3}else{4});assert_eq!(p.layers[0][0].seed,u64::MAX);
    }}
}
#[test]fn control_edges_memory_and_sampler_limits_fail_closed(){
    let p=fixture(17,13,true,false,false);p.validate().unwrap();assert!(2*40000*4<LOG_FACTORIAL_BASE*4);assert_eq!(LOG_FACTORIAL_BASE+LOG_FACTORIAL_COUNT,TABLE_BASE);
    assert!(CHUNK_BASE as u64*4+CHUNK_PIXELS as u64*4<=spatial::TRANSFER_BYTES);
    let mut bad=p.clone();bad.layers[0][0].particles=1e20;assert_eq!(bad.validate().unwrap_err(),"GPU_GRAIN_COUNT_LIMIT");
    let mut bad=p.clone();bad.width=40000;bad.height=40000;assert!(bad.validate().is_err());
    let mut bad=p.clone();bad.layers[0][0].uniformity=1.1;assert!(bad.validate().is_err());
    let mut bad=p.clone();bad.layers[0][0].axis[1]=f64::NAN;assert!(bad.validate().is_err());
    let mut duplicate=p.clone();duplicate.layers[0][0].axis[1]=duplicate.layers[0][0].axis[0];duplicate.validate().unwrap();
    let table=sampler::factorial_table();assert!(sampler::particle(0,0,0,f32::NAN,1.,10.,0.9,&table).is_err());
    let mut rng=sampler::Rng::new(0,0,0,&table);rng.steps=sampler::MAX_STEPS;assert_eq!(sampler::rk_poisson(&mut rng,3.),0);assert!(rng.failed);
}
#[cfg(feature="precision-f64")]
fn original_graph(p:&GrainPlan,v:&[Vec<f64>;3])->[Vec<f64>;3]{
    use spektrafilm_math::gaussian::gaussian_blur_channel;
    std::array::from_fn(|ch|{let mut sum=vec![0.;v[ch].len()];for l in &p.layers[ch]{let mapped:Vec<f64>=v[ch].iter().map(|&x|l.mapped_f64(x,p.positive)).collect();let mut values=spektrafilm_model::grain::layer_particle_model(&mapped,p.width,p.height,l.density_max,l.particles,l.uniformity,l.seed,0.,Some([0,0]));
        if l.cloud_sigma>0.{values=gaussian_blur_channel(&values,p.width,p.height,l.cloud_sigma);}for(a,b)in sum.iter_mut().zip(values){*a+=b;}}
        if p.micro_active {let mut field:Vec<f64>=(0..sum.len()).map(|i|spektrafilm_model::grain::microstructure_coordinate_value(p.seed,i as u32%p.width,i as u32/p.width,if p.monochrome{0}else{ch},p.micro_sigma_log,p.micro_mu)).collect();if p.micro_spread>0.{field=gaussian_blur_channel(&field,p.width,p.height,p.micro_spread);}for(a,b)in sum.iter_mut().zip(field){*a*=b;}}
        let avg=if p.sublayers{1.}else{1./p.layer_count() as f64};for x in &mut sum{*x=*x*avg-p.density_min[ch];}if p.final_sigma>0.{sum=gaussian_blur_channel(&sum,p.width,p.height,p.final_sigma);}if p.amount!=1.{for(i,x)in sum.iter_mut().enumerate(){*x=v[ch][i]+p.amount*(*x-v[ch][i]);}}sum
    })
}
#[test]
#[cfg(feature="precision-f64")]
fn whole_graph_all_modes_edges_hdr_and_cancellation(){
    let mut reports=Vec::new();for(w,h)in[(1,1),(1,37),(71,1),(71,37)]{for sublayers in[false,true]{for positive in[false,true]{for mono in[false,true]{
        let mut p=fixture(w,h,sublayers,positive,mono);p.final_sigma=if w==1{0.01}else{3.5};
        let v=input(w,h);let expected=original_graph(&p,&v);let actual=cpu_mirror(&p,&v,&mut||false).unwrap();let mut max=0f64;let mut sum=0f64;let mut mean=0f64;
        for ch in 0..3{for(&a,&b)in expected[ch].iter().zip(&actual[ch]){assert!(b.is_finite());let d=b-a;max=max.max(d.abs());sum+=d*d;mean+=d;}}
        let n=(w*h*3)as f64;reports.push(serde_json::json!({"width":w,"height":h,"sublayers":sublayers,"positive":positive,"monochrome":mono,"maxAbs":max,"rms":(sum/n).sqrt(),"meanDelta":mean/n}));
    }}}}
    eprintln!("GRAIN_GRAPH_CENSUS {}",serde_json::to_string(&reports).unwrap());
    if let Some(path)=std::env::var_os("FILM_GRAIN_GRAPH_CENSUS"){fs::write(path,serde_json::to_vec_pretty(&reports).unwrap()).unwrap();}
    let p=fixture(71,37,true,false,false);let mut checks=0;assert_eq!(cpu_mirror(&p,&input(71,37),&mut||{checks+=1;checks>3}).unwrap_err(),"CANCELLED");
}
#[test]fn particle_census_all_algorithms_thresholds_seeds_and_draw_consumption(){
    let table=sampler::factorial_table();let rates=[0.01,1.,9.999999,10.,10.000001,30.,100.,1000.,4095.,4096.,65535.,200000.];
    let ps=[1e-6,0.01,0.03,0.29999999,0.3,0.30000001,0.5,0.7,0.97,1.-1e-6];let mut reports=Vec::new();
    for seed in[0,42,u64::MAX]{for lambda in rates {for prob in ps{
        let mut poisson=0;let mut binomial=0;let mut words=0;let mut failures=0;let mut max=0f64;let mut sq=0f64;let mut mean=0f64;
        let count=1024u32;
        for i in 0..count {let x=(u32::MAX-31).wrapping_add(i%71);let y=209+i/71;
            let original=spektrafilm_model::grain::coordinate_particle_sample(seed,x,y,prob,1.,lambda,0.);
            match sampler::particle(seed,x,y,prob as f32,1.,lambda as f32,0.,&table){Ok(a)=>{poisson+=u64::from(a.particles as u64!=original.0);binomial+=u64::from(a.developed as u64!=original.1);words+=u64::from(a.words as u64!=original.2);let e=a.value as f64-original.3;max=max.max(e.abs());sq+=e*e;mean+=e;},Err(_)=>failures+=1}
        }
        reports.push(serde_json::json!({"seed":seed.to_string(),"lambda":lambda,"p":prob,"samples":count,"poissonCountMismatches":poisson,"binomialCountMismatches":binomial,"drawWordCountMismatches":words,"unsupportedSamples":failures,"maxAbsDensityDelta":max,"rmsDensityDelta":(sq/count as f64).sqrt(),"meanDensityDelta":mean/count as f64}));
    }}}
    eprintln!("GRAIN_PARTICLE_CENSUS {}",serde_json::to_string(&reports).unwrap());
    if let Some(path)=std::env::var_os("FILM_GRAIN_PARTICLE_CENSUS"){fs::write(path,serde_json::to_vec_pretty(&reports).unwrap()).unwrap();}
}
#[test]fn original_particle_census_oracle_matches_existing_sampler(){
    use spektrafilm_math::precision::from_f64;for lambda in[0.1,10.,1000.]{for prob in[1e-6,0.1,0.5,0.9,1.-1e-6]{for seed in[0,u64::MAX]{
        let value=from_f64(prob);let expected=spektrafilm_model::grain::layer_particle_model(&[value],1,1,1.,lambda,0.,seed,0.,Some([31,209]));
        let actual=spektrafilm_model::grain::coordinate_particle_sample(seed,31,209,value as f64,1.,lambda,0.);assert_eq!(expected[0] as f64,actual.3);
    }}}
}
#[test]fn stage_atomic_three_planes_immutable_sources_tamper_cancel(){
    let root=Root::new();let p=fixture(23,17,true,false,false);let values=input(p.width,p.height);let inputs=write_inputs(&root,&values);let before:Vec<_>=inputs.iter().map(|p|fs::read(p).unwrap()).collect();let dir=root.0.join("gpu-stage-9");
    let stage=full_stage::prepare_stage(inputs.each_ref().map(|p|p.as_path()),&dir,9,p.width,p.height,&p,||false).unwrap();assert_eq!(stage,full_stage::plan_stage(&dir,9).unwrap());let mut checks=0;
    assert_eq!(full_stage::run_stage_cpu_mirror_for_test(&dir,9,&stage.manifest_sha256,||{checks+=1;checks>5}).unwrap_err(),"CANCELLED");assert!(!dir.join("candidate").exists());
    full_stage::run_stage_cpu_mirror_for_test(&dir,9,&stage.manifest_sha256,||false).unwrap();let outputs=full_stage::validate_outputs(&dir,9,&stage.manifest_sha256,||false).unwrap();
    for ch in 0..3{assert_eq!(fs::read(&inputs[ch]).unwrap(),before[ch]);}
    OpenOptions::new().write(true).open(&outputs[2]).unwrap().write_all(&f64::NAN.to_le_bytes()).unwrap();assert!(full_stage::validate_outputs(&dir,9,&stage.manifest_sha256,||false).is_err());
}
#[test]fn pipeline_family_driver_allowance_is_separate_from_batch_payload(){
 let root=Root::new();let p=fixture(17,11,true,false,false);let sources=write_inputs(&root,&input(17,11));let dir=root.0.join("gpu-stage-99");
 let plan=full_stage::prepare_stage(sources.each_ref().map(|p|p.as_path()),&dir,99,17,11,&p,||false).unwrap();
 assert_eq!(guarded_graph::PIPELINE_RESERVATION_BYTES,64*MIB);
 assert_eq!(plan.driver_allowance_bytes,128*MIB);
 assert_eq!(plan.host_bytes,20*MIB+guarded_graph::CODE_HOST_RESERVATION_BYTES);
 assert_eq!(plan.device_bytes,p.spatial_plan().unwrap().device_bytes+guarded_graph::scratch_bytes(p.rng_mode).unwrap());
 assert_eq!(plan.combined_peak_bytes,plan.device_bytes+plan.host_bytes+plan.driver_allowance_bytes+plan.helper_allowance_bytes);
}
#[test]fn production_grain_plan_contract_for_android(){
    let Some(path)=std::env::var_os("FILM_GPU_CONTRACT_RESULTS")else{return;};let root=Root::new();let p=fixture(17,11,true,false,false);let sources=write_inputs(&root,&input(17,11));
    let plan=full_stage::prepare_stage(sources.each_ref().map(|p|p.as_path()),&root.0.join("gpu-stage-42"),42,17,11,&p,||false).unwrap();let path=PathBuf::from(path);fs::create_dir_all(&path).unwrap();
    fs::write(path.join("grain-plan.json"),serde_json::to_vec_pretty(&serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmGrainStage","plan":plan})).unwrap()).unwrap();
    let mut p=fixture(71,37,true,false,true);p.rng_mode=RngMode::SequentialMt19937;let sources=write_inputs(&root,&input(71,37));let plan=full_stage::prepare_stage(sources.each_ref().map(|p|p.as_path()),&root.0.join("gpu-stage-43"),43,71,37,&p,||false).unwrap();
    fs::write(path.join("grain-mt-plan.json"),serde_json::to_vec_pretty(&serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmGrainStage","plan":plan})).unwrap()).unwrap();
}

#[test] fn original_partition_point_walk_preserved_for_noisy_profile_axes() {
    fn walk(axis:&[f32],q:f32)->usize {let mut size=axis.len();let mut base=0;while size>1 {let half=size/2;let mid=base+half;if axis[mid]<=q{base=mid;}size-=half;}base+usize::from(axis[base]<=q)}
    for n in [2,3,7,16,17,255,256,257] {let axis:Vec<f32>=(0..n).map(|i|((i*7919%65521)as f32/65521.)-0.5).collect();
        for i in 0..1000 {let q=i as f32/999.-0.5;assert_eq!(walk(&axis,q),axis.partition_point(|&v|v<=q));}
    }
}
#[test] fn nonfinite_third_plane_rejects_whole_candidate() {
    let root=Root::new();let p=fixture(17,13,false,false,false);let mut v=input(17,13);v[2][7]=f64::INFINITY;let inputs=write_inputs(&root,&v);let dir=root.0.join("gpu-stage-5");
    let stage=full_stage::prepare_stage(inputs.each_ref().map(|p|p.as_path()),&dir,5,17,13,&p,||false).unwrap();
    assert_eq!(full_stage::run_stage_cpu_mirror_for_test(&dir,5,&stage.manifest_sha256,||false).unwrap_err(),"NONFINITE_GRAIN_INPUT");assert!(!dir.join("candidate").exists());
}

#[test] fn uniform_is_original_53_bits_correctly_rounded_once() {
    for seed in[0,42,u64::MAX]{for i in 0..65536u32{let x=i%257;let y=i/257;
        let words=spektrafilm_model::grain::coordinate_random_words(seed,x,y,2);
        let a=(words[0]>>32) as u32>>5;let b=(words[1]>>32) as u32>>6;
        let expected=((a as f64*67108864.+b as f64)/9007199254740992.) as f32;
        let mut rng=sampler::Rng::new(seed,x,y,&[]);assert_eq!(sampler::rk_double(&mut rng).to_bits(),expected.to_bits());assert_eq!(rng.words,2);
    }}
}

#[test]fn guarded_factory_never_substitutes_preview_stream(){
 use spektrafilm_core::profile::load_profile_by_name;let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let mut params=RuntimeParams::default();params.film_render.grain.coordinate_origin=None;params.film_render.grain.active=true;
 let pipe=Pipeline::new(load_profile_by_name(&data,"kodak_portra_400").unwrap(),load_profile_by_name(&data,"kodak_portra_endura").unwrap(),params);
 assert_eq!(GrainPlan::new(&pipe,6.,71,37).unwrap_err(),"GPU_GRAIN_SEQUENTIAL_PENDING");let p=GrainPlan::new_guarded(&pipe,6.,71,37).unwrap();assert_eq!(p.rng_mode,RngMode::SequentialMt19937);assert_eq!(p.chunk_pixels(),256);assert_eq!(p.origin,[0,0]);
}
#[test]fn guarded_shader_propagates_unsupported_integer_arithmetic(){
 let s=guarded_graph::shader().unwrap();assert!(s.contains("var<private> sf_failed:u32"));assert!(s.contains("fn sf_fail()->vec2<u32>{sf_failed=1u;return SF_NAN;}"));assert!(s.contains("if(sf_failed!=0u||rng.failure!=0u"));assert!(s.contains("atomicOr(&tail_numeric_error.value,1u)"));
}
#[cfg(feature="precision-f64")]
#[test]fn guarded_full_graph_and_final_scan_texture_census(){
 use spektrafilm_core::profile::load_profile_by_name;use spektrafilm_gpu::cpu_backend::CpuBackend;use spektrafilm_math::image::ImageBuf;
 let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let mut reports=vec![];let(w,h)=(128u32,48u32);
 let stats=|a:&[f64],b:&[f64]|{let n=a.len()as f64;let ma=a.iter().sum::<f64>()/n;let mb=b.iter().sum::<f64>()/n;let va=a.iter().map(|x|(x-ma).powi(2)).sum::<f64>()/n;let vb=b.iter().map(|x|(x-mb).powi(2)).sum::<f64>()/n;let mut diffs:Vec<f64>=a.iter().zip(b).map(|(x,y)|(x-y).abs()).collect();assert!(diffs.iter().all(|x|x.is_finite()));let rms=(diffs.iter().map(|x|x*x).sum::<f64>()/n).sqrt();diffs.sort_by(f64::total_cmp);let cov=|v:&[f64],mean:f64,lag:usize|v.iter().zip(v.iter().skip(lag)).map(|(x,y)|(x-mean)*(y-mean)).sum::<f64>()/(v.len()-lag)as f64;serde_json::json!({"maxAbs":diffs.last().unwrap(),"rms":rms,"p99Abs":diffs[(diffs.len()-1)*99/100],"p999Abs":diffs[(diffs.len()-1)*999/1000],"referenceMean":ma,"candidateMean":mb,"referenceVariance":va,"candidateVariance":vb,"referenceLag1Covariance":cov(a,ma,1),"candidateLag1Covariance":cov(b,mb,1),"referenceLagWidthCovariance":cov(a,ma,w as usize),"candidateLagWidthCovariance":cov(b,mb,w as usize)})};
 for slide in[false,true]{for sublayers in[false,true]{for mono in[false,true]{for mode in[RngMode::CoordinateSplitMix64,RngMode::SequentialMt19937]{
  let mut params=RuntimeParams::default();params.settings.use_enlarger_lut=true;params.settings.use_scanner_lut=true;params.settings.lut_resolution=5;params.io.scan_film=slide;params.io.output_color_space="sRGB".into();params.io.output_gamut_compress.algorithm="off".into();params.scanner.lens_blur=0.;params.scanner.unsharp_mask=[0.,0.];params.print_render.glare.active=false;params.film_render.glare.active=false;params.enlarger.diffusion_filter.active=false;
  params.film_render.grain.coordinate_origin=if mode==RngMode::CoordinateSplitMix64{Some([31,209])}else{None};params.film_render.grain.sublayers_active=sublayers;params.film_render.grain.monochrome=mono;params.film_render.grain.seed=42;params.film_render.grain.micro_structure=[1.3,600.];params.film_render.grain.blur=0.65;params.film_render.grain.amount=0.8;
  let film=load_profile_by_name(&data,if slide{"kodak_ektachrome_100"}else{"kodak_portra_400"}).unwrap();let paper=load_profile_by_name(&data,"kodak_portra_endura").unwrap();let pipe=Pipeline::new_with_spectral(film,paper,params,&data).unwrap();let p=GrainPlan::new_guarded(&pipe,6.,w,h).unwrap();
  let input:[Vec<f64>;3]=std::array::from_fn(|ch|(0..w*h).map(|i|{let x=i%w;let y=i/w;let value=if y<h/3{0.3}else if y<2*h/3{0.02+2.2*x as f64/(w-1)as f64}else{0.5+0.3*((x/4+y/4)%2)as f64+0.03*((i*7919)%65521)as f64/65521.};value+ch as f64*0.02}).collect());
  let reference=guarded_cpu::mirror(&p,&input,false,&mut ||false).unwrap();let candidate=guarded_cpu::mirror(&p,&input,true,&mut ||false).unwrap();let flat_a:Vec<f64>=reference.iter().flatten().copied().collect();let flat_b:Vec<f64>=candidate.iter().flatten().copied().collect();let density_stats=stats(&flat_a,&flat_b);
  let image=|v:&[Vec<f64>;3]|ImageBuf::from_data(w,h,(0..(w*h)as usize).flat_map(|i|[v[0][i],v[1][i],v[2][i]]).collect());let finish=pipe.prepare_streamed_finish_spatial(&CpuBackend).unwrap();let a=finish.process_density_chunk_unclipped(&image(&reference),&CpuBackend);let b=finish.process_density_chunk_unclipped(&image(&candidate),&CpuBackend);let scan_stats=stats(&a.data,&b.data);
  // Guard against a large regression, not a claim that this fixture envelope
  // proves photographic quality or accounts for upstream GPU density changes.
  assert!(density_stats["maxAbs"].as_f64().unwrap()<0.01);assert!(scan_stats["maxAbs"].as_f64().unwrap()<0.01);
  reports.push(serde_json::json!({"profile":if slide{"kodak_ektachrome_100"}else{"kodak_portra_400"},"rngMode":mode,"sublayers":sublayers,"monochrome":mono,"width":w,"height":h,"hostMirrorOnly":true,"sameInputDensity":true,"density":density_stats,"finishedScanRgb":scan_stats}));
 }}}}
 if let Some(path)=std::env::var_os("FILM_GRAIN_GUARDED_GRAPH_CENSUS"){fs::write(path,serde_json::to_vec_pretty(&reports).unwrap()).unwrap();}eprintln!("GUARDED_GRAPH_CENSUS {}",serde_json::to_string(&reports).unwrap());
}

#[cfg(feature="precision-f64")]
#[test]fn guarded_default_portra_preview_rates_and_large_count_branches(){
 use spektrafilm_core::profile::load_profile_by_name;let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();let mut rows=vec![];
 for width in[512u32,768,1024]{for sublayers in[false,true]{for mono in[false,true]{
  let mut params=RuntimeParams::default();params.film_render.grain.coordinate_origin=None;params.film_render.grain.sublayers_active=sublayers;params.film_render.grain.monochrome=mono;params.film_render.grain.n_sub_layers=if sublayers{1}else{4};
  let pipe=Pipeline::new(load_profile_by_name(&data,"kodak_portra_400").unwrap(),load_profile_by_name(&data,"kodak_portra_endura").unwrap(),params);let p=GrainPlan::new_guarded(&pipe,35000./width as f32,width,width*2/3).unwrap();let(mut accepted,mut ambiguous,mut max_lambda)=(0,0,0f64);
  for l in p.layers.iter().flatten(){for density in[-1.,0.,0.1,0.3,1.,2.,100.]{let value=l.mapped_f64(density,p.positive);let probability=(value/l.density_max).clamp(1e-6,1.-1e-6);let lambda=l.particles/(1.-probability*l.uniformity*(1.-1e-6));max_lambda=max_lambda.max(lambda);
   for i in 0..32u32{let expected=spektrafilm_model::grain::coordinate_particle_sample(l.seed,i,209,value,l.density_max,l.particles,l.uniformity);let mut words=guarded_sampler::Coordinate::new(l.seed,i,209);
    match guarded_sampler::particle(&mut words,value,l.density_max,l.particles,l.uniformity,&table){Ok(actual)=>{assert_eq!((actual.particles,actual.developed,actual.words),(expected.0,expected.1,expected.2));assert_eq!(actual.value.to_bits(),expected.3.to_bits());accepted+=1;},Err(guarded_sampler::Failure::Ambiguous)=>ambiguous+=1,Err(e)=>panic!("{width} {sublayers} {mono} {lambda}: {e:?}")}
   }
  }}assert!(accepted>1000);rows.push(serde_json::json!({"width":width,"sublayers":sublayers,"monochrome":mono,"accepted":accepted,"ambiguousRejected":ambiguous,"maxActualLambda":max_lambda}));
 }}}
 for lambda in[262143.,262144.,1000000.,15000000.]{for probability in[1e-6,0.00003,0.01,0.3,0.5,0.7,1.-1e-6]{for seed in[0,42,u64::MAX]{let(mut accepted,mut ambiguous)=(0,0);for i in 0..1024u32{let expected=spektrafilm_model::grain::coordinate_particle_sample(seed,i%71,209+i/71,probability,1.,lambda,0.);let mut words=guarded_sampler::Coordinate::new(seed,i%71,209+i/71);match guarded_sampler::particle(&mut words,probability,1.,lambda,0.,&table){Ok(actual)=>{assert_eq!((actual.particles,actual.developed,actual.words),(expected.0,expected.1,expected.2));assert_eq!(actual.value.to_bits(),expected.3.to_bits());accepted+=1;},Err(guarded_sampler::Failure::Ambiguous)=>ambiguous+=1,Err(e)=>panic!("{lambda} {probability}: {e:?}")}}assert!(accepted>1000);rows.push(serde_json::json!({"lambda":lambda,"p":probability,"seed":seed.to_string(),"accepted":accepted,"ambiguousRejected":ambiguous}));}}}
 if let Some(path)=std::env::var_os("FILM_GRAIN_LARGE_COUNT_CENSUS"){fs::write(path,serde_json::to_vec_pretty(&rows).unwrap()).unwrap();}eprintln!("GUARDED_LARGE_COUNT_CENSUS {}",serde_json::to_string(&rows).unwrap());
}

#[test]fn guarded_actual_summary_validator_accepts_v2_and_rejects_old_counters(){
 // Unit contract values only. This is explicitly not an actual GPU execution.
 for mode in[RngMode::CoordinateSplitMix64,RngMode::SequentialMt19937]{let mut p=fixture(71,37,true,false,true);p.rng_mode=mode;let e=guarded_graph::execution_contract(&p);
  let mut value=serde_json::json!({"passCount":p.pass_count(),"sampleCount":e.spatial_samples,"samplerSampleCount":3280,"exactRngWordsChecked":98304,"softwareArithmeticChecks":1280,"dispatches":e.dispatches,"numericFlagReads":e.numeric_flag_reads,"readbackBytes":e.readback_bytes,"poissonCountMismatches":0,"binomialCountMismatches":0,"randomWordCountMismatches":0,"samplerTupleMismatches":0,"layerCount":p.layer_count(),"chunkCount":p.chunk_count(),"grainActive":p.active,"sublayers":p.sublayers,"microActive":p.micro_active,"finalBlurActive":true,"amountBlendActive":true,"rngMode":mode,"distributionArithmetic":"guardedSoftwareBinary64","stochasticScope":"fixed_synthetic_reference_tuple_census","microstructureArithmetic":"original_words_guarded_f32_box_muller","samplerMaxAbs":0.,"samplerRms":0.});
  full_stage::validate_run_summary(&p,&value).unwrap();let saved=value.clone();for key in["samplerSampleCount","exactRngWordsChecked","softwareArithmeticChecks","dispatches","numericFlagReads","readbackBytes","samplerTupleMismatches"]{value=saved.clone();value[key]=serde_json::json!(value[key].as_u64().unwrap()+1);assert_eq!(full_stage::validate_run_summary(&p,&value).unwrap_err(),"GPU_GRAIN_NUMERIC_CHECK");}value=saved;value["exactRngWordsChecked"]=serde_json::json!(49152);assert!(full_stage::validate_run_summary(&p,&value).is_err());
 }
}
