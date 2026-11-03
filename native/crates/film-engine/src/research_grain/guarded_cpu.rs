//! Host graph mirrors for research comparisons only. Never a production hybrid.
use super::*;
use guarded_sampler::{Words,Coordinate,Failure};
fn uniform(rng:&mut dyn Words)->f64{let a=(rng.next_u32()>>5)as u64;let b=(rng.next_u32()>>6)as u64;((a<<26)|b)as f64/9007199254740992.}
fn failure(e:Failure)->String{format!("GPU_GRAIN_GUARDED_{e:?}")}
fn micro_value(u:f64,v:f64,mu:f64,sigma:f64,experimental:bool)->f64{
 let u=u.max(f64::MIN_POSITIVE);
 if !experimental{return (mu+sigma*((-2.*u.ln()).sqrt()*(std::f64::consts::TAU*v).cos())).exp();}
 let log=f64::from_bits(soft64::log(u.to_bits()));let radius=(-2.*log).sqrt()as f32;let theta=(std::f64::consts::TAU*v)as f32;
 ((mu as f32)+(sigma as f32)*(radius*theta.cos())).exp()as f64
}
pub fn microstructure(p:&GrainPlan,ch:usize,experimental:bool)->Vec<f64>{
 let n=p.width as usize*p.height as usize;let mut mt=preview_rng::Mt19937::new(p.seed.wrapping_add(0x51f15e));let mut result=Vec::with_capacity(n);
 for i in 0..n{if p.rng_mode==RngMode::CoordinateSplitMix64{let mut rng=Coordinate::new(p.micro_seed(ch),p.origin[0].wrapping_add(i as u32%p.width),p.origin[1].wrapping_add(i as u32/p.width));let u=uniform(&mut rng);let v=uniform(&mut rng);result.push(micro_value(u,v,p.micro_mu,p.micro_sigma_log,experimental));}
  else{let mut value=0.;for channel in 0..if p.monochrome{1}else{3}{let u=uniform(&mut mt);let v=uniform(&mut mt);if p.monochrome||channel==ch{value=micro_value(u,v,p.micro_mu,p.micro_sigma_log,experimental);}}result.push(value);}}
 result
}
/// Original model at identical input versus guarded-sampler/guarded-f32 graph.
/// This does not include upstream GPU density drift or hardware transcendental
/// differences. Rejected sampler inputs propagate; no image is partially accepted.
pub fn mirror(p:&GrainPlan,input:&[Vec<f64>;3],experimental:bool,c:&mut impl FnMut()->bool)->R<[Vec<f64>;3]>{
 p.numerical_preflight(c)?;guarded_graph::validate(p,p.rng_mode)?;let n=p.width as usize*p.height as usize;
 if input.iter().any(|x|x.len()!=n){return Err("INVALID_GRAIN_INPUT".into());}if input.iter().any(|x|x.iter().any(|v|!v.is_finite())){return Err("NONFINITE_GRAIN_INPUT".into());}if !p.active{return Ok(input.clone());}
 let prepared=spektrafilm_math::numpy_rng::PreparedPoisson::new();let mut out=std::array::from_fn(|_|Vec::new());let rounded=|x:f64|if experimental{x as f32 as f64}else{x};
 let blur=|v:&[f64],sigma:f32,c:&mut dyn FnMut()->bool|->R<Vec<f64>>{if experimental{blur_mirror(v,p.width,p.height,sigma,&mut ||c())}else{Ok(spektrafilm_math::gaussian::gaussian_blur_channel(v,p.width,p.height,sigma).into_iter().map(|v|v as f64).collect())}};
 for ch in 0..3{let mut sum=vec![0.;n];for l in&p.layers[ch]{spatial::check(c)?;let mapped:Vec<f64>=input[ch].iter().map(|&x|l.mapped_f64(x,p.positive)).collect();
   let mut values=if !experimental{let origin=if p.rng_mode==RngMode::SequentialMt19937{None}else{Some(p.origin)};spektrafilm_model::grain::layer_particle_model(&mapped,p.width,p.height,l.density_max,l.particles,l.uniformity,l.seed,0.,origin)}
   else if p.rng_mode==RngMode::SequentialMt19937{guarded_sampler::preview(l.seed,&mapped,l.density_max,l.particles,l.uniformity).map_err(failure)?.0}
   else{let mut values=Vec::with_capacity(n);for(i,&value)in mapped.iter().enumerate(){if i%256==0{spatial::check(c)?;}let mut rng=Coordinate::new(l.seed,p.origin[0].wrapping_add(i as u32%p.width),p.origin[1].wrapping_add(i as u32/p.width));values.push(guarded_sampler::particle(&mut rng,value,l.density_max,l.particles,l.uniformity,&prepared).map_err(failure)?.value);}values};
   for v in&mut values{*v=rounded(*v);}if l.cloud_sigma>0.{values=blur(&values,l.cloud_sigma,c)?;}for(a,b)in sum.iter_mut().zip(values){*a=rounded(*a+b);}
  }
  if p.micro_active{let mut field=microstructure(p,ch,experimental);if p.micro_spread>0.{field=blur(&field,p.micro_spread,c)?;}for(a,b)in sum.iter_mut().zip(field){*a=rounded(*a*b);}}
  let avg=rounded(if p.sublayers{1.}else{1./p.layer_count()as f64});for x in&mut sum{*x=rounded(rounded(*x*avg)-rounded(p.density_min[ch]));}
  if p.final_sigma>0.{sum=blur(&sum,p.final_sigma,c)?;}if p.amount!=1.{for(i,x)in sum.iter_mut().enumerate(){let original=rounded(input[ch][i]);*x=rounded(original+rounded(rounded(p.amount)*rounded(*x-original)));}}
  if sum.iter().any(|x|!x.is_finite()){return Err("NONFINITE_GRAIN_OUTPUT".into());}out[ch]=sum;
 }Ok(out)
}
