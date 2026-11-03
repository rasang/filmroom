// Complete guarded grain graph. Original f64 source, curves and parameters
// remain bit-exact until sampling. Spatial/output arithmetic is guarded f32.
// Preview is sequential MT: ALL Poisson pixels, then ALL binomial pixels.
fn gg_fail(){atomicOr(&tail_numeric_error.value,1u);}
fn gg_scalar(v:vec2<u32>)->f32 {
 if(!sf_valid(v)){gg_fail();return 0.0;}if(sf_zero(v)){return 0.0;}
 if(sf_exp(v)<897||sf_cmp(sf_abs(v),vec2<u32>(0x26340000u,0x430c6bf5u))>0){gg_fail();return 0.0;}
 return sf_scalar(v);
}
fn gg_param(i:u32)->vec2<u32>{return sr_load64(1028096u+3u*i);}
fn gg_mapped(pixel:u32)->vec2<u32>{
 let original=sr_load64(978944u+3u*pixel);let dmin=gg_param(0u);let knots=p.u[4].z;
 if(!sf_valid(original)){gg_fail();return SF_NAN;}if(knots==0u){return sf_add(original,dmin);}
 var q=original;if(p.u[2].z!=0u){q=sf_neg(q);}let axis=p.u[4].x;let values=p.u[4].y;let last=knots-1u;
 if(sf_cmp(q,sr_load64(axis))<=0){return sf_add(sr_load64(values),dmin);}
 if(sf_cmp(q,sr_load64(axis+3u*last))>=0){return sf_add(sr_load64(values+3u*last),dmin);}
 var size=knots;var base=0u;loop{if(size<=1u){break;}let half=size/2u;let mid=base+half;if(sf_cmp(sr_load64(axis+3u*mid),q)<=0){base=mid;}size=size-half;}
 var right=base;if(sf_cmp(sr_load64(axis+3u*base),q)<=0){right=right+1u;}let hi=min(right,last);if(hi==0u){gg_fail();return SF_NAN;}let lo=hi-1u;
 let lower=sr_load64(axis+3u*lo);let dx=sf_sub(sr_load64(axis+3u*hi),lower);var t=vec2<u32>(0u);if(!sf_zero(dx)){t=sf_div(sf_sub(q,lower),dx);}
 let a=sr_load64(values+3u*lo);let b=sr_load64(values+3u*hi);return sf_add(sf_add(a,sf_mul(t,sf_sub(b,a))),dmin);
}
struct GrainParameters {pb:vec2<u32>,sat:vec2<u32>,lam:vec2<u32>,valid:bool,}
fn gg_parameters(pixel:u32)->GrainParameters{
 let mapped=gg_mapped(pixel);let dmax=gg_param(1u);let npp=gg_param(2u);let gu=gg_param(3u);var pb=sf_div(mapped,dmax);
 if(!sf_valid(pb)){gg_fail();return GrainParameters(pb,pb,pb,false);}
 if(sf_cmp(pb,vec2<u32>(0xa0b5ed8du,0x3eb0c6f7u))<0){pb=vec2<u32>(0xa0b5ed8du,0x3eb0c6f7u);}if(sf_cmp(pb,sf_sub(vec2<u32>(0x00000000u,0x3ff00000u),vec2<u32>(0xa0b5ed8du,0x3eb0c6f7u)))>0){pb=sf_sub(vec2<u32>(0x00000000u,0x3ff00000u),vec2<u32>(0xa0b5ed8du,0x3eb0c6f7u));}
 let sat=sf_sub(vec2<u32>(0x00000000u,0x3ff00000u),sf_mul(sf_mul(pb,gu),sf_sub(vec2<u32>(0x00000000u,0x3ff00000u),vec2<u32>(0xa0b5ed8du,0x3eb0c6f7u))));let lam=sf_div(npp,sat);
 let valid=sf_valid(sat)&&sf_valid(lam)&&sf_cmp(lam,vec2<u32>(0u))>=0&&sf_cmp(lam,vec2<u32>(0x00000000u,0x416c9c38u))<=0;
 if(!valid){gg_fail();}return GrainParameters(pb,sat,lam,valid);
}
// Sequential-only prepared triplets. Original encoded source ends at979712;
// 256 * 9 lanes end at982016, below parameter storage1028096. Coordinate
// kernels never use this scratch, whose range overlaps their larger input slot.
const GG_PREPARED_BASE:u32=979712u;
fn gg_store64(offset:u32,v:vec2<u32>){
 probe.values[offset]=f32(v.x&0xffffffu);
 probe.values[offset+1u]=f32((v.x>>24u)|((v.y&0xffffu)<<8u));
 probe.values[offset+2u]=f32(v.y>>16u);
}
fn gg_prepared(pixel:u32)->GrainParameters{
 let at=GG_PREPARED_BASE+9u*pixel;
 return GrainParameters(sr_load64(at),sr_load64(at+3u),sr_load64(at+6u),true);
}
fn gg_rng(index:u32)->SoftRng{let old=grain_rng(p.u[2].xy,p.u[7].x+index%p.u[0].x,p.u[7].y+index/p.u[0].x);return SoftRng(old.state,0u,0u,0u,p.u[2].w);}
fn gg_output(developed:u32,sat:vec2<u32>)->f32{let dmax=gg_param(1u);let npp=gg_param(2u);let value=sf_integer(developed);return gg_scalar(sf_mul(sf_mul(value,sf_div(dmax,npp)),sat));}
fn gg_micro_value(u_input:vec2<u32>,v:vec2<u32>)->f32{
 var u=u_input;if(sf_zero(u)){u=vec2<u32>(0u,0x00100000u);}
 let radius=sf_sqrt(sf_mul(sf_neg(vec2<u32>(0x00000000u,0x40000000u)),sf_log(u)));let theta=sf_mul(vec2<u32>(0x54442d18u,0x401921fbu),v);let normal=tmul(gg_scalar(radius),cos(gg_scalar(theta)));
 return texp(tadd(gg_scalar(gg_param(4u)),tmul(gg_scalar(gg_param(5u)),normal)));
}
fn guarded_grain_main(i:u32){
 let op=p.u[0].z;let offset=p.u[1].w;let count=p.u[1].y;let index=offset+i;
 if(op==50u){saved.values[index]=gg_scalar(sr_load64(978944u+3u*i));return;}
 if(op==51u){
  let a=gg_parameters(i);if(!a.valid){return;}var rng=gg_rng(index);let particles=sr_poisson(&rng,a.lam);let developed=sr_binomial(&rng,particles,a.pb);
  if(sf_failed!=0u||rng.failure!=0u||particles>=16777216u||developed>particles){gg_fail();return;}source.values[index]=gg_output(developed,a.sat);if(sf_failed!=0u){gg_fail();}return;
 }
 if(op==52u||op==53u){
  if(i!=0u||atomicLoad(&tail_numeric_error.value)!=0u){return;}var total_steps=0u;
  // Only this invocation touches the persistent MT state. A whole-image
  // Poisson pass completes before the first binomial dispatch is submitted.
  for(var pixel=0u;pixel<count;pixel=pixel+1u){
   let a=gg_prepared(pixel);var rng=SoftRng(vec2<u32>(0u),0u,0u,0u,1u);let at=offset+pixel;
   if(op==52u){let particles=sr_poisson(&rng,a.lam);if(sf_failed!=0u||rng.failure!=0u||particles>=16777216u){gg_fail();return;}horizontal.values[at]=f32(particles);}
   else{let particles=u32(horizontal.values[at]);let developed=sr_binomial(&rng,particles,a.pb);if(sf_failed!=0u||rng.failure!=0u||developed>particles){gg_fail();return;}source.values[at]=gg_output(developed,a.sat);}
   if(sf_failed!=0u){gg_fail();return;}total_steps=total_steps+rng.steps;if(total_steps>8192u){gg_fail();return;}
  }return;
 }
 if(op==54u){var rng=gg_rng(index);let u=sr_uniform(&rng);let v=sr_uniform(&rng);source.values[index]=gg_micro_value(u,v);if(sf_failed!=0u){gg_fail();}return;}
 if(op==55u){
  if(i!=0u){return;}var rng=SoftRng(vec2<u32>(0u),0u,0u,0u,1u);let channels=select(3u,1u,p.u[5].x!=0u);
  // Replaying the identical full RGB stream for each destination channel saves
  // two resident planes. Dropped channels still consume their original words.
  for(var pixel=0u;pixel<count;pixel=pixel+1u){for(var ch=0u;ch<channels;ch=ch+1u){let u=sr_uniform(&rng);let v=sr_uniform(&rng);if(ch==p.u[4].w||channels==1u){source.values[offset+pixel]=gg_micro_value(u,v);if(sf_failed!=0u){gg_fail();return;}}}}return;
 }
 if(op==56u){accum.values[i]=tadd(accum.values[i],get_value(p.u[6].z,i));return;}
 if(op==57u){accum.values[i]=tmul(accum.values[i],get_value(p.u[6].z,i));return;}
 if(op==58u){source.values[i]=tsub(tmul(accum.values[i],bitcast<f32>(p.u[5].z)),bitcast<f32>(p.u[5].w));return;}
 if(op==59u){source.values[i]=tadd(saved.values[i],tmul(bitcast<f32>(p.u[5].y),tsub(source.values[i],saved.values[i])));return;}
 if(op==60u){source.values[i]=tbound(source.values[i]);return;}
 if(op==63u){
  let a=gg_parameters(i);
  if(!a.valid||sf_failed!=0u){gg_fail();return;}
  let at=GG_PREPARED_BASE+9u*i;
  gg_store64(at,a.pb);gg_store64(at+3u,a.sat);gg_store64(at+6u,a.lam);return;
 }
 if(op==64u){
  // Admission consumer uses the production prepass loads and output conversion.
  if(atomicLoad(&tail_numeric_error.value)!=0u){return;}
  let a=gg_prepared(i);var rng=gg_rng(i);let particles=sr_poisson(&rng,a.lam);let developed=sr_binomial(&rng,particles,a.pb);
  let value=gg_output(developed,a.sat);if(sf_failed!=0u||rng.failure!=0u){gg_fail();}
  let at=GG_PREPARED_BASE+9u*i;for(var k=0u;k<9u;k=k+1u){probe.values[14u*i+k]=probe.values[at+k];}
  probe.values[14u*i+9u]=f32(particles);probe.values[14u*i+10u]=f32(developed);probe.values[14u*i+11u]=f32(rng.words);probe.values[14u*i+12u]=value;probe.values[14u*i+13u]=f32(rng.failure);return;
 }
 if(op==61u){
  let rates=array<vec2<u32>,13>(vec2<u32>(0x47ae147bu,0x3f847ae1u),vec2<u32>(0x00000000u,0x3ff00000u),vec2<u32>(0xde7210bfu,0x4023ffffu),vec2<u32>(0x00000000u,0x40240000u),vec2<u32>(0x218def41u,0x40240000u),vec2<u32>(0x00000000u,0x403e0000u),vec2<u32>(0x00000000u,0x40590000u),vec2<u32>(0x00000000u,0x408f4000u),vec2<u32>(0x00000000u,0x410d4c00u),vec2<u32>(0x00000000u,0x412e8480u),vec2<u32>(0x00000000u,0x40efffe0u),vec2<u32>(0x00000000u,0x41086a00u),vec2<u32>(0x00000000u,0x416c9c38u));let probabilities=array<vec2<u32>,10>(vec2<u32>(0xa0b5ed8du,0x3eb0c6f7u),vec2<u32>(0x47ae147bu,0x3f847ae1u),vec2<u32>(0xeb851eb8u,0x3f9eb851u),vec2<u32>(0x28766bc2u,0x3fd33333u),vec2<u32>(0x33333333u,0x3fd33333u),vec2<u32>(0x3deffaa4u,0x3fd33333u),vec2<u32>(0x00000000u,0x3fe00000u),vec2<u32>(0x66666666u,0x3fe66666u),vec2<u32>(0x70a3d70au,0x3fef0a3du),vec2<u32>(0xe7210be9u,0x3feffffdu));let seeds=array<vec2<u32>,3>(vec2<u32>(0u),vec2<u32>(42u,0u),vec2<u32>(0xffffffffu));
  let j=i%8u;let old=grain_rng(seeds[i/1040u],0xffffffe0u+j%71u,209u+j/71u);var rng=SoftRng(old.state,0u,0u,0u,0u);let lam=rates[(i/80u)%13u];let pb=probabilities[(i/8u)%10u];
  let particles=sr_poisson(&rng,lam);let developed=sr_binomial(&rng,particles,pb);let df=sf_integer(developed);let value=gg_scalar(sf_mul(df,sf_div(SF_ONE,lam)));
  if(sf_failed!=0u||rng.failure!=0u){gg_fail();}probe.values[5u*i]=f32(particles);probe.values[5u*i+1u]=f32(developed);probe.values[5u*i+2u]=f32(rng.words);probe.values[5u*i+3u]=value;probe.values[5u*i+4u]=f32(rng.failure);return;
 }
 if(op==62u){
  let original_a=sr_load64(1028096u+6u*i);let original_b=sr_load64(1028099u+6u*i);
  // Every admission exercises the same exact-bit transport as the prepass.
  let at=GG_PREPARED_BASE+9u*i;gg_store64(at,original_a);gg_store64(at+3u,original_b);
  let a=sr_load64(at);let b=sr_load64(at+3u);
  if(any(a!=original_a)||any(b!=original_b)){gg_fail();return;}
  let out=array<vec2<u32>,5>(sf_add(a,b),sf_sub(a,b),sf_mul(a,b),sf_div(a,b),sf_sqrt(sf_abs(a)));
  for(var j=0u;j<5u;j=j+1u){let value=out[j];let base=20u*i+4u*j;probe.values[base]=f32(value.x&65535u);probe.values[base+1u]=f32(value.x>>16u);probe.values[base+2u]=f32(value.y&65535u);probe.values[base+3u]=f32(value.y>>16u);}return;
 }
}
