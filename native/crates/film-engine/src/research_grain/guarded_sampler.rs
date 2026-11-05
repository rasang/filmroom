//! Higher-precision stochastic mirror for the guarded software-binary64 port.
//! Basic operations retain original f64 rounding; software log/exp differences
//! carry conservative comparison intervals. Ambiguity fails the entire stage.
//! This host mirror defines the guarded experimental shader contract; actual
//! export/preview GPU execution also requires per-request device checks.
use super::{guarded_graph,soft64,preview_rng::Mt19937,sampler::{Pair,MAX_STEPS}};
pub const EXP_REL_ERROR:f64=4e-12;
// Atanh63 truncation <3.361e-33; near1 truncation exit <2^-70.
// 4e-14 relative leaves >128 binary64 rounding units for the bounded series,
// range reduction and original libm's last-bit difference. This candidate
// envelope is empirically checked, not a complete target-libm error proof.
// See GRAIN_GUARDED_NUMERICS.md in the research deliverable directory.
pub fn log_error(x:f64)->f64 {4e-14*log(x).abs()+2e-20}
fn log(x:f64)->f64 {f64::from_bits(soft64::log(x.to_bits()))}
fn exp_nonpositive(x:f64)->f64 {f64::from_bits(soft64::exp_nonpositive(x.to_bits()))}
// Large-count branch of the existing libm0.2.16 lgamma_r implementation.
// Copyright (C)1993 Sun Microsystems, Inc. All rights reserved.
// Developed at SunSoft, a Sun Microsystems, Inc. business. Permission to use,
// copy, modify, and distribute this software is freely granted, provided that
// this notice is preserved. Only log is replaced by the guarded software log.
fn log_factorial(k:i64,prepared:&spektrafilm_math::numpy_rng::PreparedPoisson)->f64{
 if k<super::LOG_FACTORIAL_COUNT as i64{return prepared.log_factorial(k);}
 let x=k as f64+1.;let t=log(x);let z=1./x;let y=z*z;
 let w=0.418938533204672725052+z*(0.0833333333333329678849+y*(-0.00277777777728775536470+y*(0.000793650558643019558500+y*(-0.000595187557450339963135+y*(0.000836339918996282139126+y*(-0.00163092934096575273989))))));
 (x-0.5)*(t-1.)+w
}
fn log_factorial_error(k:i64)->f64{if k<super::LOG_FACTORIAL_COUNT as i64{0.}else{let x=k as f64+1.;(x-0.5)*log_error(x)+8.*f64::EPSILON*x*(log(x).abs()+1.)}}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure {Domain,Ambiguous,Iterations}
pub trait Words {fn next_u32(&mut self)->u32;}
impl Words for Mt19937 {fn next_u32(&mut self)->u32{Mt19937::next_u32(self)}}
pub struct Coordinate {state:Pair}
impl Coordinate {pub fn new(seed:u64,x:u32,y:u32)->Self{let inc=Pair::from(0x9e3779b97f4a7c15);Self{state:Pair::from(seed).xor(Pair(x,0).mul(inc)).xor(Pair(y,0).mul(Pair::from(0xd1b54a32d192ed03))).add(inc).mix()}}}
impl Words for Coordinate {fn next_u32(&mut self)->u32{self.state=self.state.add(Pair::from(0x9e3779b97f4a7c15));self.state.mix().1}}
pub struct Stream<'a>{words:&'a mut dyn Words,pub used:u64,steps:u32,pub failure:Option<Failure>,prepared:&'a spektrafilm_math::numpy_rng::PreparedPoisson}
impl<'a> Stream<'a>{
    pub fn new(words:&'a mut dyn Words,prepared:&'a spektrafilm_math::numpy_rng::PreparedPoisson)->Self{Self{words,used:0,steps:0,failure:None,prepared}}
    fn tick(&mut self)->bool{self.steps+=1;if self.steps>MAX_STEPS{self.failure=Some(Failure::Iterations);}self.failure.is_none()}
    fn prove(&mut self,a:f64,b:f64,error:f64)->bool{if !a.is_finite()||!b.is_finite()||!error.is_finite()||error<0.{self.failure=Some(Failure::Domain);false}else if(a-b).abs()<=error{self.failure=Some(Failure::Ambiguous);false}else{true}}
    fn prove_floor(&mut self,x:f64,error:f64)->bool{if !x.is_finite()||!error.is_finite()||error<0.{self.failure=Some(Failure::Domain);return false;}let fraction=x-x.floor();if fraction<=error||1.-fraction<=error{self.failure=Some(Failure::Ambiguous);false}else{true}}
    pub fn reset_budget(&mut self){self.steps=0;}
}
pub fn rk_double(rng:&mut Stream)->f64{let a=(rng.words.next_u32() as u64)>>5;let b=(rng.words.next_u32() as u64)>>6;rng.used+=2;(a as f64*67108864.+b as f64)/9007199254740992.}
#[derive(Debug,Clone)]pub struct Sample {pub particles:u64,pub developed:u64,pub words:u64,pub value:f64}
pub fn particle(words:&mut dyn Words,density:f64,dmax:f64,npp:f64,uniformity:f64,prepared:&spektrafilm_math::numpy_rng::PreparedPoisson)->Result<Sample,Failure>{
    if !density.is_finite()||!dmax.is_finite()||dmax<=0.||!npp.is_finite()||npp<=0.||!(0. ..=1.).contains(&uniformity){return Err(Failure::Domain);}
    let p=(density/dmax).clamp(1e-6,1.-1e-6);let saturation=1.-p*uniformity*(1.-1e-6);let lambda=npp/saturation;
    if lambda>super::guarded_graph::MAX_RATE{return Err(Failure::Domain);}
    let mut rng=Stream::new(words,prepared);let particles=rk_poisson(&mut rng,lambda);if particles>=super::guarded_graph::MAX_COUNT{return Err(Failure::Domain);}let developed=rk_binomial(&mut rng,particles,p);if let Some(e)=rng.failure{return Err(e);}Ok(Sample{particles,developed,words:rng.used,value:developed as f64*(dmax/npp)*saturation})
}
/// Legacy preview ordering: no interleaving, no per-pixel state, no reseeding.
/// Failure returns no partially computed image.
pub fn preview(seed:u64,density:&[f64],dmax:f64,npp:f64,uniformity:f64)->Result<(Vec<f64>,u64),Failure>{
    if !dmax.is_finite()||dmax<=0.||!npp.is_finite()||npp<=0.||!(0. ..=1.).contains(&uniformity){return Err(Failure::Domain);}
    let prepared=spektrafilm_math::numpy_rng::PreparedPoisson::new();let mut mt=Mt19937::new(seed);let mut rng=Stream::new(&mut mt,&prepared);let mut particles=Vec::with_capacity(density.len());let mut parameters=Vec::with_capacity(density.len());
    for &d in density{if !d.is_finite(){return Err(Failure::Domain);}let p=(d/dmax).clamp(1e-6,1.-1e-6);let sat=1.-p*uniformity*(1.-1e-6);let lambda=npp/sat;if !lambda.is_finite()||lambda>super::guarded_graph::MAX_RATE{return Err(Failure::Domain);}rng.reset_budget();let count=rk_poisson(&mut rng,lambda);if count>=super::guarded_graph::MAX_COUNT{return Err(Failure::Domain);}particles.push(count);if let Some(e)=rng.failure{return Err(e);}parameters.push((p,sat));}
    let mut output=Vec::with_capacity(density.len());for(i,&n)in particles.iter().enumerate(){rng.reset_budget();let developed=rk_binomial(&mut rng,n,parameters[i].0);if let Some(e)=rng.failure{return Err(e);}output.push(developed as f64*(dmax/npp)*parameters[i].1);}Ok((output,rng.used))
}
#[inline]
pub fn rk_poisson(rng: &mut Stream, lam: f64) -> u64 {
    if lam >= 10.0 {
        rk_poisson_ptrs(rng, lam)
    } else if lam == 0.0 {
        0
    } else {
        rk_poisson_mult(rng, lam)
    }
}

fn rk_poisson_mult(rng: &mut Stream, lam: f64) -> u64 {
    let enlam = exp_nonpositive(-lam);
    let mut x: u64 = 0;
    let mut prod = 1.0_f64;
    loop {
        if !rng.tick() { return 0; }
        let u = rk_double(rng);
        prod *= u;
        if !rng.prove(prod,enlam,EXP_REL_ERROR*enlam) { return 0; }
        if prod > enlam {
            x += 1;
        } else {
            return x;
        }
    }
}

fn rk_poisson_ptrs(rng: &mut Stream, lam: f64) -> u64 {
    let slam = lam.sqrt();
    let loglam = log(lam);
    let b = 0.931 + 2.53 * slam;
    let a = -0.059 + 0.02483 * b;
    let invalpha = 1.1239 + 1.1328 / (b - 3.4);
    let vr = 0.9277 - 3.6224 / (b - 2.0);
    loop {
        if !rng.tick() { return 0; }
        let u = rk_double(rng) - 0.5;
        let v = rk_double(rng);
        let us = 0.5 - u.abs();
        let k_f = ((2.0 * a / us + b) * u + lam + 0.43).floor();
        let k = k_f as i64;
        if us >= 0.07 && v <= vr {
            return k as u64;
        }
        if k < 0 || (us < 0.013 && v > us) {
            continue;
        }
        if k>=super::guarded_graph::MAX_COUNT as i64{rng.failure=Some(Failure::Domain);return 0;}
        let lhs = log(v) + log(invalpha) - log(a / (us * us) + b);
        let rhs = -lam + (k as f64) * loglam - log_factorial(k,rng.prepared);
        let error=log_factorial_error(k)+log_error(v)+log_error(invalpha)+log_error(a/(us*us)+b)+(k as f64)*log_error(lam)+8.*f64::EPSILON*(lam.abs()+(k as f64*loglam).abs()+log_factorial(k,rng.prepared).abs());
        if !rng.prove(lhs,rhs,error) { return 0; }
        if lhs <= rhs {
            return k as u64;
        }
    }
}

#[inline]
pub fn rk_binomial(rng: &mut Stream, n: u64, p: f64) -> u64 {
    if p <= 0.5 {
        if (p * n as f64) <= 30.0 {
            rk_binomial_inversion(rng, n, p)
        } else {
            rk_binomial_btpe(rng, n, p)
        }
    } else {
        let q = 1.0 - p;
        if (q * n as f64) <= 30.0 {
            n - rk_binomial_inversion(rng, n, q)
        } else {
            n - rk_binomial_btpe(rng, n, q)
        }
    }
}

fn rk_binomial_inversion(rng: &mut Stream, n: u64, p: f64) -> u64 {
    let q = 1.0 - p;
    let qn = exp_nonpositive((n as f64) * log(q));
    let inversion_error=2.*((n as f64)*log_error(q)+EXP_REL_ERROR)+2e-12;
    if !qn.is_finite() {rng.failure=Some(Failure::Domain);return 0;}
    let np = (n as f64) * p;
    let bound = (n as f64).min(np + 10.0 * (np * q + 1.0).sqrt()) as u64;
    let mut x: u64 = 0;
    let mut px = qn;
    let mut u = rk_double(rng);
    loop {
        if !rng.tick() || !rng.prove(u,px,inversion_error) { return 0; }
        if u<=px {break;}
        x += 1;
        if x > bound {
            x = 0;
            px = qn;
            u = rk_double(rng);
        } else {
            u -= px;
            px = (((n - x + 1) as f64) * p * px) / ((x as f64) * q);
        }
    }
    x
}

#[allow(clippy::many_single_char_names)]
fn rk_binomial_btpe(rng: &mut Stream, n: u64, p: f64) -> u64 {
    let r = p.min(1.0 - p);
    let q = 1.0 - r;
    let nf = n as f64;
    let fm = nf * r + r;
    let m = fm.floor() as i64;
    let p1 = (2.195 * (nf * r * q).sqrt() - 4.6 * q).floor() + 0.5;
    let xm = m as f64 + 0.5;
    let xl = xm - p1;
    let xr = xm + p1;
    let c = 0.134 + 20.5 / (15.3 + m as f64);
    let a1 = (fm - xl) / (fm - xl * r);
    let laml = a1 * (1.0 + a1 / 2.0);
    let a2 = (xr - fm) / (xr * q);
    let lamr = a2 * (1.0 + a2 / 2.0);
    let p2 = p1 * (1.0 + 2.0 * c);
    let p3 = p2 + c / laml;
    let p4 = p3 + c / lamr;
    let nrq = nf * r * q;

    let y: i64;
    'outer: loop {
        if !rng.tick() { return 0; }
        let u = rk_double(rng) * p4;
        let mut v = rk_double(rng);

        let cand;
        if u <= p1 {
            cand = (xm - p1 * v + u).floor() as i64;
            y = cand;
            break 'outer;
        } else if u <= p2 {
            let x = xl + (u - p1) / c;
            v = v * c + 1.0 - ((m as f64) - x + 0.5).abs() / p1;
            if v > 1.0 {
                continue 'outer;
            }
            cand = x.floor() as i64;
        } else if u <= p3 {
            let candidate=xl+log(v)/laml;
            if !rng.prove_floor(candidate,log_error(v)/laml+8.*f64::EPSILON*candidate.abs()) { return 0; }
            cand = candidate.floor() as i64;
            if cand < 0 {
                continue 'outer;
            }
            v *= (u - p2) * laml;
        } else {
            let candidate=xr-log(v)/lamr;
            if !rng.prove_floor(candidate,log_error(v)/lamr+8.*f64::EPSILON*candidate.abs()) { return 0; }
            cand = candidate.floor() as i64;
            if cand > n as i64 {
                continue 'outer;
            }
            v *= (u - p3) * lamr;
        }

        let k = (cand - m).unsigned_abs() as i64;
        if k <= 20 || k as f64 >= nrq / 2.0 - 1.0 {
            let s = r / q;
            let a_step51 = s * (nf + 1.0);
            let mut f = 1.0_f64;
            if m < cand {
                for i in (m + 1)..=cand {
                    if !rng.tick() { return 0; }
                    f *= a_step51 / (i as f64) - s;
                }
            } else if m > cand {
                for i in (cand + 1)..=m {
                    if !rng.tick() { return 0; }
                    f /= a_step51 / (i as f64) - s;
                }
            }
            if v > f {
                continue 'outer;
            }
        } else {
            let kf = k as f64;
            let rho =
                (kf / nrq) * ((kf * (kf / 3.0 + 0.625) + 0.166_666_666_666_666_66) / nrq + 0.5);
            let t = -kf * kf / (2.0 * nrq);
            let a_log = log(v);
            if !rng.prove(a_log,t-rho,log_error(v)+8.*f64::EPSILON*(t.abs()+rho.abs())) || !rng.prove(a_log,t+rho,log_error(v)+8.*f64::EPSILON*(t.abs()+rho.abs())) { return 0; }
            if a_log < t - rho {
                y = cand;
                break 'outer;
            }
            if a_log > t + rho {
                continue 'outer;
            }
            let x1 = (cand + 1) as f64;
            let f1 = (m + 1) as f64;
            let z = (n as i64 - m + 1) as f64;
            let w = (n as i64 - cand + 1) as f64;
            let x2 = x1 * x1;
            let f2 = f1 * f1;
            let z2 = z * z;
            let w2 = w * w;
            let bound = xm * log(f1 / x1)
                + (nf - m as f64 + 0.5) * log(z / w)
                + ((cand - m) as f64) * log(w * r / (x1 * q))
                + (13680.0 - (462.0 - (132.0 - (99.0 - 140.0 / f2) / f2) / f2) / f2)
                    / f1
                    / 166320.0
                + (13680.0 - (462.0 - (132.0 - (99.0 - 140.0 / z2) / z2) / z2) / z2) / z / 166320.0
                + (13680.0 - (462.0 - (132.0 - (99.0 - 140.0 / x2) / x2) / x2) / x2)
                    / x1
                    / 166320.0
                + (13680.0 - (462.0 - (132.0 - (99.0 - 140.0 / w2) / w2) / w2) / w2) / w / 166320.0;
            let error=log_error(v)+xm.abs()*log_error(f1/x1)+(nf-m as f64+0.5).abs()*log_error(z/w)+(cand-m).abs() as f64*log_error(w*r/(x1*q))+64.*f64::EPSILON*nf.max(1.);
            if !rng.prove(a_log,bound,error) {return 0;}
            if a_log > bound {
                continue 'outer;
            }
        }
        y = cand;
        break 'outer;
    }

    let y_pos = if p > 0.5 { n as i64 - y } else { y };
    y_pos as u64
}

#[cfg(test)]mod tests{
    use super::*;
    #[test]fn accepted_counts_and_consumed_words_match_original_at_mode_boundary(){let table=spektrafilm_math::numpy_rng::PreparedPoisson::new();let(mut accepted,mut ambiguous)=(0,0);
        for seed in[0,42,u64::MAX]{for lambda in[0.01,1.,9.999999,10.,10.000001,30.,100.,1000.,65535.,200000.,240000.]{for prob in[1e-6,0.03,0.29999999,0.3,0.30000001,0.5,0.7,0.97,1.-1e-6]{for i in 0..1024u32{
            let(x,y)=(31u32.wrapping_add(i%71),209+i/71);let expected=spektrafilm_model::grain::coordinate_particle_sample(seed,x,y,prob,1.,lambda,0.);let mut rng=Coordinate::new(seed,x,y);
            match particle(&mut rng,prob,1.,lambda,0.,&table){Ok(v)=>{assert_eq!((v.particles,v.developed,v.words),(expected.0,expected.1,expected.2),"seed={seed} lambda={lambda} p={prob} x={x} y={y}");assert_eq!(v.value.to_bits(),expected.3.to_bits());accepted+=1;},Err(Failure::Ambiguous)=>ambiguous+=1,Err(e)=>panic!("unexpected {e:?}")}
        }}}}eprintln!("guarded grain accepted={accepted} ambiguous={ambiguous}");assert!(accepted>300000);
    }
    #[test]#[cfg(feature="precision-f64")]fn accepted_legacy_preview_keeps_all_poisson_then_all_binomial(){
        for seed in[0,42,u64::MAX]{for npp in[0.1,10.,100.,1000.]{let density:Vec<f64>=(0..71*37).map(|i|match i%7{0=>0.3,1=>0.30000001,2=>0.5,_=>(i*7919%65521)as f64/65521.}).collect();let reference=spektrafilm_model::grain::layer_particle_model(&density,71,37,1.,npp,0.,seed,0.,None);
            match preview(seed,&density,1.,npp,0.){Ok((v,words))=>{assert_eq!(v,reference);assert!(words>0);},Err(Failure::Ambiguous)=>{},Err(e)=>panic!("{e:?}")}
        }}
    }
}

/// Offline-only step oracle for the fixed3120 admission fixtures. This calls
/// original generic guarded functions, never the prepared implementation.
pub fn particle_steps_reference(seed:u64,x:u32,y:u32,lambda:f64,p:f64,table:&spektrafilm_math::numpy_rng::PreparedPoisson)->Result<u32,Failure>{
 let mut words=Coordinate::new(seed,x,y);let mut rng=Stream::new(&mut words,table);let n=rk_poisson(&mut rng,lambda);if let Some(e)=rng.failure{return Err(e);}rk_binomial(&mut rng,n,p);if let Some(e)=rng.failure{return Err(e);}Ok(rng.steps)
}
/// CPU-only admission oracle, intentionally using the original generic sampler.
/// Failure tags and complete coordinate cursor preserve rejected-boundary tests.
pub fn binomial_admission_reference(seed:u64,x:u32,y:u32,n:u64,p:f64,table:&spektrafilm_math::numpy_rng::PreparedPoisson)->[f32;8]{
 let mut words=Coordinate::new(seed,x,y);
 let (value,used,steps,failure)={let mut rng=Stream::new(&mut words,table);let value=rk_binomial(&mut rng,n,p);(value,rng.used,rng.steps,rng.failure)};
 let failure=match failure{None=>0,Some(Failure::Domain)=>1,Some(Failure::Ambiguous)=>2,Some(Failure::Iterations)=>3};let Pair(lo,hi)=words.state;
 [value as f32,used as f32,steps as f32,failure as f32,(lo&65535)as f32,(lo>>16)as f32,(hi&65535)as f32,(hi>>16)as f32]
}
/// Metadata only: actual device particle counts/RNG are never supplied by CPU.
/// Used for64 admission cases, not the3120 original-model tuple census.
pub fn particle_cursor_reference(seed:u64,x:u32,y:u32,density:f64,npp:f64,uniformity:f64,table:&spektrafilm_math::numpy_rng::PreparedPoisson)->Result<[f32;12],Failure>{
 let p=density.clamp(1e-6,1.-1e-6);let sat=1.-p*uniformity*(1.-1e-6);let mut words=Coordinate::new(seed,x,y);
 let (count,used,steps)={let mut rng=Stream::new(&mut words,table);let count=rk_poisson(&mut rng,npp/sat);if let Some(e)=rng.failure{return Err(e);}(count,rng.used,rng.steps)};
 let first=words.state;
 let (total,total_steps)={let mut rng=Stream::new(&mut words,table);rng.used=used;rng.steps=steps;rk_binomial(&mut rng,count,p);if let Some(e)=rng.failure{return Err(e);}(rng.used,rng.steps)};
 let last=words.state;
 let mut out=[0.;12];for (i,(state,used,steps)) in [(first,used,steps),(last,total,total_steps)].into_iter().enumerate(){let Pair(lo,hi)=state;out[6*i..6*i+6].copy_from_slice(&[steps as f32,used as f32,(lo&65535)as f32,(lo>>16)as f32,(hi&65535)as f32,(hi>>16)as f32]);}Ok(out)
}

#[path="prepared_sampler.rs"]
pub mod prepared;
#[cfg(test)]
#[path="prepared_sampler_tests.rs"]
mod prepared_tests;
