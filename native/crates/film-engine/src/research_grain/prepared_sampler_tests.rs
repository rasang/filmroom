//! Exact host oracle checks. Device transport/execution is separately admitted.
use super::*;
use super::prepared::{self,PoissonSetup,BinomialSetup};
fn transport(values:&[f64])->Vec<f64>{
 let bytes=crate::research_grain::guarded_graph::encode(values.iter().copied());
 bytes.chunks_exact(12).map(|b|{let mut lanes=[0u64;3];for(i,lane)in b.chunks_exact(4).enumerate(){lanes[i]=f32::from_le_bytes(lane.try_into().unwrap())as u64;}f64::from_bits(lanes[0]|lanes[1]<<24|lanes[2]<<48)}).collect()
}
fn poisson_setup(lam:f64)->PoissonSetup{
 let p=prepared::prepare_poisson(lam);let values=[p.enlam,p.loglam,p.b,p.a,p.invalpha,p.vr];let v=transport(&values);
 assert_eq!(values.map(f64::to_bits),std::array::from_fn::<_,6,_>(|i|v[i].to_bits()));
 PoissonSetup{enlam:v[0],loglam:v[1],b:v[2],a:v[3],invalpha:v[4],vr:v[5]}
}
fn binomial_setup(n:u64,p:f64)->BinomialSetup{
 let s=prepared::prepare_binomial(n,p);let v=transport(&s.values);let values=std::array::from_fn(|i|v[i]);assert_eq!(s.values.map(f64::to_bits),values.map(f64::to_bits));BinomialSetup{values}
}
#[test]
fn prepared_poisson_and_binomial_match_generic_edges_and_rng_cursor(){
 let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();
 for seed in [0u64,42,u64::MAX]{
  for lam in [0.,0.01,1.,9.999999,10.,10.000001,30.,1000.,240000.,262143.,262144.,1000000.,15000000.]{
   for p in [0.,1e-6,0.01,0.29999999,0.3,0.30000001,0.5,f64::from_bits(0.5f64.to_bits()+1),0.7,0.97,1.-1e-6,1.]{
    let mut a=Mt19937::new(seed);let mut b=a.clone();
    let (result_a,result_b);
    {let mut ra=Stream::new(&mut a,&table);let mut rb=Stream::new(&mut b,&table);
     let na=rk_poisson(&mut ra,lam);let nb=prepared::poisson(&mut rb,lam,poisson_setup(lam));
     assert_eq!((na,ra.used,ra.failure),(nb,rb.used,rb.failure),"Poisson seed={seed} lam={lam}");
     let da=if ra.failure.is_none(){rk_binomial(&mut ra,na,p)}else{0};
     // Setup is computed after the ACTUAL candidate count, never the oracle n.
     let db=if rb.failure.is_none(){prepared::binomial(&mut rb,nb,p,binomial_setup(nb,p))}else{0};
     result_a=(na,da,ra.used,ra.failure);result_b=(nb,db,rb.used,rb.failure);
    }
    assert_eq!(result_a,result_b,"seed={seed} lam={lam} p={p}");assert_eq!(a.packed(),b.packed());
    if lam==0. {let mut c=Mt19937::new(seed);let mut rc=Stream::new(&mut c,&table);assert_eq!(prepared::poisson(&mut rc,0.,poisson_setup(0.)),0);assert_eq!(rc.used,0);}
   }
  }
 }
}
#[test]
fn prepared_binomial_exact_threshold_neighbors_and_reflections(){
 let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();
 for n in [0u64,1,29,30,31,59,60,61,99,100,101,65535,262143,262144,14999999,16777215]{
  let mut ps=vec![0.,1e-6,0.3,0.5,f64::from_bits(0.5f64.to_bits()+1),0.7,1.-1e-6,1.];
  if n>=60{let edge=30./n as f64;for x in [f64::from_bits(edge.to_bits()-1),edge,f64::from_bits(edge.to_bits()+1)]{ps.extend([x,1.-x]);}}
  for p in ps {for seed in [0u64,42,u64::MAX]{let mut a=Mt19937::new(seed);let mut b=a.clone();
   {let mut ra=Stream::new(&mut a,&table);let mut rb=Stream::new(&mut b,&table);let expected=rk_binomial(&mut ra,n,p);let candidate=prepared::binomial(&mut rb,n,p,binomial_setup(n,p));assert_eq!((expected,ra.used,ra.failure),(candidate,rb.used,rb.failure),"n={n} p={p} seed={seed}");}
   assert_eq!(a.packed(),b.packed());
  }}
 }
}
#[test]
fn prepared_whole_passes_preserve_mt_across_chunks_and_batches(){
 let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();let n=4097;
 for base in [0u64,42,u64::MAX]{for layer in 0..3{let seed=base.wrapping_add(layer*10);let mut a=Mt19937::new(seed);let mut b=a.clone();let mut expected=vec![0;n];let mut actual=vec![0;n];
  for op in [52,53]{let mut offset=0;while offset<n{for _ in 0..crate::research_grain::guarded_graph::SEQUENTIAL_BATCH_CHUNKS{if offset==n{break;}let end=(offset+256).min(n);
   let setup:Vec<_>=(offset..end).map(|i|{let pb=0.1+(i%79)as f64/100.;let sat=1.-pb*0.97*(1.-1e-6);(pb,2300./sat)}).collect();
   for i in offset..end{let (p,lam)=setup[i-offset];let mut ra=Stream::new(&mut a,&table);let mut rb=Stream::new(&mut b,&table);
    if op==52{expected[i]=rk_poisson(&mut ra,lam);actual[i]=prepared::poisson(&mut rb,lam,poisson_setup(lam));}
    else{expected[i]=rk_binomial(&mut ra,expected[i],p);actual[i]=prepared::binomial(&mut rb,actual[i],p,binomial_setup(actual[i],p));}
    assert_eq!((expected[i],ra.used,ra.failure),(actual[i],rb.used,rb.failure));assert!(ra.failure.is_none(),"fixture must be admitted");
   }offset=end;
  }assert_eq!(a.packed(),b.packed());}}
  assert_eq!(expected,actual);
 }}
}
fn function<'a>(source:&'a str,name:&str)->&'a str{
 let start=source.find(&format!("fn {name}(")).unwrap();let body=start+source[start..].find('{').unwrap();let mut depth=1;let mut end=body+1;while depth!=0{match source.as_bytes()[end]{b'{'=>depth+=1,b'}'=>depth-=1,_=>{}}end+=1;}&source[start..end]
}
#[test]
fn generated_rejection_loops_are_verbatim_generic_oracles(){
 let generic=include_str!("guarded_sampler.rs");let prepared=include_str!("prepared_sampler.rs");
 for(a,b,marker)in[("rk_poisson_ptrs","poisson_ptrs","    loop {"),("rk_poisson_mult","poisson_mult","    let mut x:"),("rk_binomial_inversion","prepared_inversion","    let mut x:"),("rk_binomial_btpe","prepared_btpe","    let y: i64;")]{let a=function(generic,a);let b=function(prepared,b);assert_eq!(&a[a.find(marker).unwrap()..],&b[b.find(marker).unwrap()..]);}
 let shader=include_str!("../shaders/research_grain_guarded_sampler.wgsl");
 let reference=include_str!("../shaders/research_grain_guarded_sampler_reference.wgsl");
 for name in ["sr_prepare_poisson","sr_prepare_inversion","sr_prepare_btpe","sr_prepare_binomial"]{let setup=function(shader,name);for forbidden in ["SoftRng","sr_uniform(","sr_word(","sr_tick(","mt_next("]{assert!(!setup.contains(forbidden),"{name} must not consume RNG");}}
 for(a,b,marker)in[("sr_poisson","sr_poisson_prepared","\n loop{"),("sr_inversion","sr_inversion_prepared"," var x="),("sr_btpe","sr_btpe_prepared","var y=0i;")]{let a=function(reference,a);let b=function(shader,b);assert_eq!(&a[a.find(marker).unwrap()..],&b[b.find(marker).unwrap()..]);}
}
#[test]
fn admission_cpu_goldens_cover_boundaries_and_actual_prepass_cursors(){
 let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();
 for i in 0..crate::research_grain::guarded_graph::BOUNDARY_CHECKS{
  let(seed,x,y,n,p)=crate::research_grain::guarded_graph::boundary_case(i);
  let expected=binomial_admission_reference(seed,x,y,n,p,&table);let mut words=Coordinate::new(seed,x,y);
  let(value,used,steps,failure)={let mut rng=Stream::new(&mut words,&table);let value=prepared::binomial(&mut rng,n,p,binomial_setup(n,p));(value,rng.used,rng.steps,rng.failure)};
  let tag=match failure{None=>0,Some(Failure::Domain)=>1,Some(Failure::Ambiguous)=>2,Some(Failure::Iterations)=>3};let Pair(lo,hi)=words.state;
  assert_eq!(expected,[value as f32,used as f32,steps as f32,tag as f32,(lo&65535)as f32,(lo>>16)as f32,(hi&65535)as f32,(hi>>16)as f32]);
 }
 for(npp,uniformity)in crate::research_grain::guarded_graph::PREPASS_RATES{for i in 0..32u32{
  let d=0.1+i as f64/40.;let x=31+i%17;let y=209+i/17;let metadata=particle_cursor_reference(42,x,y,d,npp,uniformity,&table).expect("actual-prepass golden must be admitted");
  let p=d.clamp(1e-6,1.-1e-6);let lam=npp/(1.-p*uniformity*(1.-1e-6));let mut words=Coordinate::new(42,x,y);
  let(n,used,steps)={let mut rng=Stream::new(&mut words,&table);let n=prepared::poisson(&mut rng,lam,poisson_setup(lam));assert!(rng.failure.is_none());(n,rng.used,rng.steps)};
  let first=words.state;
  let(developed,total,total_steps)={let mut rng=Stream::new(&mut words,&table);rng.used=used;rng.steps=steps;let developed=prepared::binomial(&mut rng,n,p,binomial_setup(n,p));assert!(rng.failure.is_none());(developed,rng.used,rng.steps)};
  for(j,(state,used,steps))in[(first,used,steps),(words.state,total,total_steps)].into_iter().enumerate(){let Pair(lo,hi)=state;assert_eq!(&metadata[j*6..j*6+6],&[steps as f32,used as f32,(lo&65535)as f32,(lo>>16)as f32,(hi&65535)as f32,(hi>>16)as f32]);}
  let original=spektrafilm_model::grain::coordinate_particle_sample(42,x,y,d,1.,npp,uniformity);assert_eq!((n,developed,total),(original.0,original.1,original.2));
 }}
}
#[test]
fn runtime_shader_has_one_rejection_body_and_offline_reference_is_separate(){
 let runtime=include_str!("../shaders/research_grain_guarded_sampler.wgsl");
 for name in ["sr_poisson","sr_binomial"]{let body=function(runtime,name);assert!(body.len()<180);assert!(!body.contains("loop{"));}
 assert!(!runtime.contains("fn sr_inversion("));assert!(!runtime.contains("fn sr_btpe("));
 assert_eq!(runtime.matches("fn sr_poisson_prepared(").count(),1);assert_eq!(runtime.matches("fn sr_btpe_prepared(").count(),1);
}
