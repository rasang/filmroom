//! Software binary64 arithmetic foundation for guarded stochastic predicates.
//! No f32 operand arithmetic is used. Finite normal binary64 and signed zero
//! are numerically supported; zero sign is not bit-preserved. Unsupported underflow/overflow returns a NaN sentinel so a
//! caller must reject the complete candidate. Not yet a rendering backend.
const FRAC:u64=(1u64<<52)-1;
const HIDDEN:u64=1u64<<52;
pub const SHADER:&str=include_str!("../shaders/research_grain_soft64.wgsl");
pub const NAN:u64=0x7ff8000000000000;
fn parts(x:u64)->Option<(bool,i32,u64)>{let e=((x>>52)&2047)as i32;let m=x&FRAC;if e==2047||(e==0&&m!=0){None}else{Some((x>>63!=0,e,if e==0{0}else{m|HIDDEN}))}}
fn jam(v:u64,n:u32)->u64{if n==0{v}else if n<64{(v>>n)|u64::from(v&((1u64<<n)-1)!=0)}else{u64::from(v!=0)}}
fn round(sign:bool,mut exponent:i32,mut sig:u64)->u64{
    if sig==0{return 0;}
    if sig&(1u64<<56)!=0{sig=jam(sig,1);exponent+=1;}
    while sig&(1u64<<55)==0{sig<<=1;exponent-=1;}
    let residue=sig&7;let mut mantissa=(sig>>3)+u64::from(residue>4||(residue==4&&(sig>>3)&1!=0));
    if mantissa&(1u64<<53)!=0{mantissa>>=1;exponent+=1;}
    if !(1..2047).contains(&exponent){return NAN;}
    ((sign as u64)<<63)|((exponent as u64)<<52)|(mantissa&FRAC)
}
pub fn add(a:u64,b:u64)->u64{
    let(Some((mut sa,mut ea,mut ma)),Some((mut sb,mut eb,mut mb)))=(parts(a),parts(b))else{return NAN;};
    if ma==0{return b;}if mb==0{return a;}
    if ea<eb||(ea==eb&&ma<mb){std::mem::swap(&mut sa,&mut sb);std::mem::swap(&mut ea,&mut eb);std::mem::swap(&mut ma,&mut mb);}
    let x=ma<<3;let y=jam(mb<<3,(ea-eb)as u32);round(sa,ea,if sa==sb{x+y}else{x-y})
}
pub fn sub(a:u64,b:u64)->u64{add(a,b^(1u64<<63))}
pub fn mul(a:u64,b:u64)->u64{
    let(Some((sa,ea,ma)),Some((sb,eb,mb)))=(parts(a),parts(b))else{return NAN;};if ma==0||mb==0{return 0;}
    let product=ma as u128*mb as u128;let shift=if product&(1u128<<105)!=0{50}else{49};
    let sig=((product>>shift)as u64)|u64::from(product&((1u128<<shift)-1)!=0);
    round(sa^sb,ea+eb-1023+i32::from(shift==50),sig)
}
pub fn div(a:u64,b:u64)->u64{
    let(Some((sa,ea,mut ma)),Some((sb,eb,mb)))=(parts(a),parts(b))else{return NAN;};if mb==0{return NAN;}if ma==0{return 0;}
    let mut e=ea-eb+1023;if ma<mb{ma<<=1;e-=1;}
    let numerator=(ma as u128)<<55;let q=numerator/mb as u128;let rem=numerator%mb as u128;
    round(sa^sb,e,q as u64|u64::from(rem!=0))
}
pub fn sqrt(a:u64)->u64{
    let Some((sign,e,mut m))=parts(a)else{return NAN;};if m==0{return 0;}if sign{return NAN;}
    let mut exponent=e-1023;if exponent&1!=0{m<<=1;exponent-=1;}let radicand=(m as u128)<<58;
    let mut root=0u64;let mut rem=0u64;
    for i in(0..56).rev(){rem=(rem<<2)|((radicand>>(i*2))&3)as u64;let trial=(root<<2)|1;root<<=1;if rem>=trial{rem-=trial;root|=1;}}
    round(false,exponent/2+1023,root|u64::from(rem!=0))
}
pub fn cmp(a:u64,b:u64)->Option<std::cmp::Ordering>{parts(a)?;parts(b)?;if a<<1==0&&b<<1==0{return Some(std::cmp::Ordering::Equal);}let ka=if a>>63!=0{!a}else{a|(1u64<<63)};let kb=if b>>63!=0{!b}else{b|(1u64<<63)};Some(ka.cmp(&kb))}
pub fn integer(n:u32)->u64{(n as f64).to_bits()}
pub fn floor(a:u64)->Option<i64>{let(sign,e,m)=parts(a)?;if m==0{return Some(0);}let power=e-1023;if power<0{return Some(if sign{-1}else{0});}if power>51{return if power<=52{Some(if sign{-(m as i64)}else{m as i64})}else{None};}let shift=52-power as u32;let whole=m>>shift;let residue=m&((1u64<<shift)-1);Some(if sign{-(whole as i64)-i64::from(residue!=0)}else{whole as i64})}
const ONE:u64=0x3ff0000000000000;
const TWO:u64=0x4000000000000000;
const LN2:u64=0x3fe62e42fefa39ef;
/// Range reduction to m in [1,2), atanh series through odd degree63.
/// Its analytic truncation is <3.361e-33. Binary64 operation rounding is separately
/// included by the caller's predicate interval; this is not libm-bit parity.
pub fn log(a:u64)->u64{
    let Some((sign,e,mantissa))=parts(a)else{return NAN;};if sign||mantissa==0{return NAN;}
    let(mut k,mut m)=(e-1023,ONE|(a&FRAC));if (0.5..=2.).contains(&f64::from_bits(a)){k=0;m=a;}let z=div(sub(m,ONE),add(m,ONE));let z2=mul(z,z);let mut term=z;let mut sum=z;
    for k in(3..=63).step_by(2){term=mul(term,z2);if (term&0x7fffffffffffffff)<0x3b90000000000000{break;}sum=add(sum,div(term,integer(k)));}
    let integer_k=(k as f64).to_bits();add(mul(TWO,sum),mul(integer_k,LN2))
}
/// exp(x), admitted only for -64 <= x <= 0 (original inversion/small-Poisson
/// domain). Degree20 Taylor on x/256 then8 squarings; no f32 exp/log call.
pub fn exp_nonpositive(a:u64)->u64{
    let x=f64::from_bits(a);if !(-64. ..=0.).contains(&x){return NAN;}
    let z=div(a,integer(256));let(mut term,mut sum)=(ONE,ONE);
    for k in 1..=20{term=div(mul(term,z),integer(k));sum=add(sum,term);}
    for _ in 0..8{sum=mul(sum,sum);}sum
}
#[cfg(test)]mod tests{
    use super::*;
    #[test]fn binary64_basic_arithmetic_exact_for_admitted_finite_domain(){let mut state=42u64;for i in 0..100000u32{state=state.wrapping_mul(6364136223846793005).wrapping_add(1);let a=((state>>11)as f64/9007199254740992.-0.5)*2f64.powi((i%100)as i32-50);state=state.wrapping_mul(6364136223846793005).wrapping_add(1);let b=((state>>11)as f64/9007199254740992.-0.5)*2f64.powi((i%93)as i32-40);let(aa,bb)=(a.to_bits(),b.to_bits());assert_eq!(add(aa,bb),(a+b).to_bits());assert_eq!(sub(aa,bb),(a-b).to_bits());assert_eq!(mul(aa,bb),(a*b).to_bits());assert_eq!(div(aa,bb),(a/b).to_bits());assert_eq!(sqrt(a.abs().to_bits()),a.abs().sqrt().to_bits());}}
    #[test]fn signed_zero_is_predicate_equivalent_not_bit_identical(){assert_eq!(mul((-0f64).to_bits(),ONE),0);assert_eq!(div((-0f64).to_bits(),ONE),0);assert_eq!(sqrt((-0f64).to_bits()),0);assert_eq!(add(0,(-0f64).to_bits()),(-0f64).to_bits());assert_eq!(cmp(0,(-0f64).to_bits()),Some(std::cmp::Ordering::Equal));}
    #[test]fn binomial_mode_boundary_retains_original_f64_rounding(){for p in[0.29999999,0.3,0.30000001]{let product=mul((p as f64).to_bits(),integer(100));assert_eq!(f64::from_bits(product),p*100.);assert_eq!(cmp(product,integer(30)).unwrap().is_le(),p*100.<=30.);}}
    #[test]fn transcendental_error_census_with_conservative_bound(){for i in 1..100000{let x=2f64.powf(-53.+i as f64/100000.*72.);let error=(f64::from_bits(log(x.to_bits()))-x.ln()).abs();assert!(error<=2e-13,"{error}");}for i in 0..10000{let x=-64.*i as f64/10000.;let actual=f64::from_bits(exp_nonpositive(x.to_bits()));assert!((actual-x.exp()).abs()<=1e-12*x.exp());}}
}
