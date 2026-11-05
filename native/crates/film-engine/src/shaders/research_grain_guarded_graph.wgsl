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
// The original 72 numeric-f32 lanes live in a bounded typed frame. Profile,
// factorial, source and admission-cursor probe offsets are unchanged.
const GG_PREPARED_STRIDE:u32=72u;
const GG_PRODUCTION:u32=0u;
const GG_CENSUS:u32=1u;
const GG_BOUNDARY:u32=2u;
const GG_ZERO:u32=3u;
const GG_PREPASS:u32=4u;
fn gg_store64(pixel:u32,offset:u32,v:vec2<u32>){
 grain_scratch.frames[pixel].prepared[offset]=f32(v.x&0xffffffu);
 grain_scratch.frames[pixel].prepared[offset+1u]=f32((v.x>>24u)|((v.y&0xffffu)<<8u));
 grain_scratch.frames[pixel].prepared[offset+2u]=f32(v.y>>16u);
}
fn gg_load64(pixel:u32,offset:u32)->vec2<u32>{
 let a=u32(grain_scratch.frames[pixel].prepared[offset]);let b=u32(grain_scratch.frames[pixel].prepared[offset+1u]);let c=u32(grain_scratch.frames[pixel].prepared[offset+2u]);
 return vec2<u32>(a|((b&255u)<<24u),(b>>8u)|(c<<16u));
}
fn gg_prepared(pixel:u32)->GrainParameters{return GrainParameters(gg_load64(pixel,0u),gg_load64(pixel,3u),gg_load64(pixel,6u),true);}
fn gg_store_parameters(pixel:u32,a:GrainParameters){gg_store64(pixel,0u,a.pb);gg_store64(pixel,3u,a.sat);gg_store64(pixel,6u,a.lam);}
fn gg_store_poisson(pixel:u32,value:SrPoissonSetup){
 let values=array<vec2<u32>,6>(value.enlam,value.loglam,value.b,value.a,value.invalpha,value.vr);
 for(var j=0u;j<6u;j=j+1u){gg_store64(pixel,18u+3u*j,values[j]);}
}
fn gg_load_poisson(pixel:u32)->SrPoissonSetup{return SrPoissonSetup(gg_load64(pixel,18u),gg_load64(pixel,21u),gg_load64(pixel,24u),gg_load64(pixel,27u),gg_load64(pixel,30u),gg_load64(pixel,33u));}
fn gg_store_binomial(pixel:u32,value:SrBinomialSetup){for(var j=0u;j<18u;j=j+1u){gg_store64(pixel,18u+3u*j,value.v[j]);}}
fn gg_load_binomial(pixel:u32)->SrBinomialSetup{var value:SrBinomialSetup;for(var j=0u;j<18u;j=j+1u){value.v[j]=gg_load64(pixel,18u+3u*j);}return value;}
fn gg_store_rng(pixel:u32,rng:SoftRng){
 gg_store64(pixel,9u,rng.state);grain_scratch.frames[pixel].prepared[12u]=f32(rng.words);grain_scratch.frames[pixel].prepared[13u]=f32(rng.steps);
 grain_scratch.frames[pixel].prepared[14u]=f32(rng.failure);grain_scratch.frames[pixel].prepared[15u]=f32(rng.mode);grain_scratch.frames[pixel].prepared[16u]=f32(sf_failed);
}
fn gg_load_rng(pixel:u32)->SoftRng{return SoftRng(gg_load64(pixel,9u),u32(grain_scratch.frames[pixel].prepared[12u]),u32(grain_scratch.frames[pixel].prepared[13u]),u32(grain_scratch.frames[pixel].prepared[14u]),u32(grain_scratch.frames[pixel].prepared[15u]));}
fn gg_rng(index:u32)->SoftRng{let old=grain_rng(p.u[2].xy,p.u[7].x+index%p.u[0].x,p.u[7].y+index/p.u[0].x);return SoftRng(old.state,0u,0u,0u,p.u[2].w);}
fn gg_admission_cursor(pixel:u32,phase:u32,rng:SoftRng)->bool{
 let at=868352u+12u*pixel+6u*phase;
 let state=vec2<u32>(u32(probe.values[at+2u])|(u32(probe.values[at+3u])<<16u),u32(probe.values[at+4u])|(u32(probe.values[at+5u])<<16u));
 return rng.steps==u32(probe.values[at])&&rng.words==u32(probe.values[at+1u])&&all(rng.state==state);
}
struct GrainFixture {lam:vec2<u32>,pb:vec2<u32>,rng:SoftRng,n:u32,}
fn gg_fixture(i:u32,kind:u32)->GrainFixture{
  let rates=array<vec2<u32>,13>(vec2<u32>(0x47ae147bu,0x3f847ae1u),vec2<u32>(0x00000000u,0x3ff00000u),vec2<u32>(0xde7210bfu,0x4023ffffu),vec2<u32>(0x00000000u,0x40240000u),vec2<u32>(0x218def41u,0x40240000u),vec2<u32>(0x00000000u,0x403e0000u),vec2<u32>(0x00000000u,0x40590000u),vec2<u32>(0x00000000u,0x408f4000u),vec2<u32>(0x00000000u,0x410d4c00u),vec2<u32>(0x00000000u,0x412e8480u),vec2<u32>(0x00000000u,0x40efffe0u),vec2<u32>(0x00000000u,0x41086a00u),vec2<u32>(0x00000000u,0x416c9c38u));let probabilities=array<vec2<u32>,10>(vec2<u32>(0xa0b5ed8du,0x3eb0c6f7u),vec2<u32>(0x47ae147bu,0x3f847ae1u),vec2<u32>(0xeb851eb8u,0x3f9eb851u),vec2<u32>(0x28766bc2u,0x3fd33333u),vec2<u32>(0x33333333u,0x3fd33333u),vec2<u32>(0x3deffaa4u,0x3fd33333u),vec2<u32>(0x00000000u,0x3fe00000u),vec2<u32>(0x66666666u,0x3fe66666u),vec2<u32>(0x70a3d70au,0x3fef0a3du),vec2<u32>(0xe7210be9u,0x3feffffdu));let seeds=array<vec2<u32>,3>(vec2<u32>(0u),vec2<u32>(42u,0u),vec2<u32>(0xffffffffu));

 let j=i%8u;let old=grain_rng(seeds[i/1040u],0xffffffe0u+j%71u,209u+j/71u);let rng=SoftRng(old.state,0u,0u,0u,0u);
 var lam=rates[(i/80u)%13u];var pb=probabilities[(i/8u)%10u];var n=0u;
 if(kind==GG_BOUNDARY){
  let ns=array<u32,8>(0u,1u,30u,60u,61u,100u,262144u,16777215u);n=ns[i%8u];
  if(n>=60u){let edge=sf_div(sf_integer(30u),sf_integer(n));pb=edge;if((i/8u)%3u==0u){pb=sf_subu(edge,vec2<u32>(1u,0u));}else if((i/8u)%3u==2u){pb=sf_addu(edge,vec2<u32>(1u,0u));}if(i>=48u){pb=sf_sub(SF_ONE,pb);}}
 }
 if(kind==GG_ZERO){lam=vec2<u32>(0u);}
 return GrainFixture(lam,pb,rng,n);
}
fn gg_phase_valid(op:u32)->bool{
 let capacity=arrayLength(&grain_scratch.frames);let count=p.u[1].y;let kind=p.u[3].x;let base=p.u[3].y;
 if((capacity!=256u&&capacity!=16384u)||count==0u||count>capacity||p.u[0].w!=0u||kind>GG_PREPASS||p.u[2].w>1u){return false;}
 if(arrayLength(&probe.values)<1028105u){return false;}
 let serial=op==52u||op==53u;
 if(serial){if(p.u[1].x!=1u||count>256u||kind!=GG_PRODUCTION||p.u[2].w!=1u){return false;}}
 else{if(p.u[1].x!=count){return false;}}
 if((op==64u||op==65u)&&p.u[2].w!=0u){return false;}
 if(kind==GG_PRODUCTION){
  if(p.u[0].x==0u){return false;}let offset=p.u[1].w;let sl=arrayLength(&source.values);let hl=arrayLength(&horizontal.values);
  if(offset>sl||offset>hl){return false;}if(count>sl-offset||count>hl-offset){return false;}
 }else{
  if(p.u[2].w!=0u||p.u[1].w!=0u){return false;}
  var limit=3120u;if(kind==GG_BOUNDARY||kind==GG_ZERO){limit=96u;}else if(kind==GG_PREPASS){limit=32u;}
  if(base>limit||count>limit-base){return false;}
  if(kind==GG_PREPASS&&(base!=0u||p.u[0].x!=17u)){return false;}
 }
 if((kind==GG_BOUNDARY&&(op==63u||op==65u))||(kind==GG_ZERO&&(op==66u||op==64u))){return false;}
 return true;
}
fn gg_output(developed:u32,sat:vec2<u32>)->f32{let dmax=gg_param(1u);let npp=gg_param(2u);let value=sf_integer(developed);return gg_scalar(sf_mul(sf_mul(value,sf_div(dmax,npp)),sat));}
fn gg_micro_value(u_input:vec2<u32>,v:vec2<u32>)->f32{
 var u=u_input;if(sf_zero(u)){u=vec2<u32>(0u,0x00100000u);}
 let radius=sf_sqrt(sf_mul(sf_neg(vec2<u32>(0x00000000u,0x40000000u)),sf_log(u)));let theta=sf_mul(vec2<u32>(0x54442d18u,0x401921fbu),v);let normal=tmul(gg_scalar(radius),cos(gg_scalar(theta)));
 return texp(tadd(gg_scalar(gg_param(4u)),tmul(gg_scalar(gg_param(5u)),normal)));
}
fn guarded_grain_main(i:u32){
 let op=p.u[0].z;let offset=p.u[1].w;let count=p.u[1].y;let index=offset+i;let kind=p.u[3].x;let fixture=p.u[3].y+i;
 if(op==50u){saved.values[index]=gg_scalar(sr_load64(978944u+3u*i));return;}
 if(op==52u||op==65u){
  if(!gg_phase_valid(op)){gg_fail();return;}let serial=op==52u;if(serial&&i!=0u){return;}if(atomicLoad(&tail_numeric_error.value)!=0u){return;}
  let active_lanes=select(count,1u,serial);let lane=select(i,0u,serial);if(!grain_alu_begin(lane,active_lanes)){return;}
  var total_steps=0u;let iterations=select(1u,count,serial);
  for(var step=0u;step<iterations;step=step+1u){
   let pixel=select(i,step,serial);let at=offset+pixel;let a=gg_prepared(pixel);var rng=SoftRng(vec2<u32>(0u),0u,0u,0u,1u);
   if(!serial){rng=gg_load_rng(pixel);sf_failed=u32(grain_scratch.frames[pixel].prepared[16u]);}
   let before=rng;let particles=sr_poisson_prepared(&rng,a.lam,gg_load_poisson(pixel));gg_store_rng(pixel,rng);grain_scratch.frames[pixel].prepared[17u]=f32(particles);
   if(serial){if(sf_failed!=0u||rng.failure!=0u||particles>=16777216u){gg_fail();return;}horizontal.values[at]=f32(particles);total_steps=total_steps+rng.steps;if(total_steps>8192u){gg_fail();return;}}
   else if(kind==GG_ZERO){if(particles!=0u||rng.words!=0u||rng.steps!=0u||rng.failure!=0u||sf_failed!=0u||any(rng.state!=before.state)){gg_fail();}return;}
   else if(kind==GG_PREPASS){if(sf_failed!=0u||rng.failure!=0u||particles>=16777216u||!gg_admission_cursor(pixel,0u,rng)){gg_fail();return;}}
   else if(particles>=16777216u){gg_fail();return;}
  }return;
 }
 if(op==53u||op==64u){
  if(!gg_phase_valid(op)){gg_fail();return;}let serial=op==53u;if(serial&&i!=0u){return;}if(atomicLoad(&tail_numeric_error.value)!=0u){return;}
  let active_lanes=select(count,1u,serial);let lane=select(i,0u,serial);if(!grain_alu_begin(lane,active_lanes)){return;}
  var total_steps=0u;let iterations=select(1u,count,serial);
  for(var step=0u;step<iterations;step=step+1u){
   let pixel=select(i,step,serial);let at=offset+pixel;let a=gg_prepared(pixel);let particles=u32(grain_scratch.frames[pixel].prepared[17u]);var rng=SoftRng(vec2<u32>(0u),0u,0u,0u,1u);
   if(!serial){rng=gg_load_rng(pixel);sf_failed=u32(grain_scratch.frames[pixel].prepared[16u]);}
   let developed=sr_binomial_prepared(&rng,particles,a.pb,gg_load_binomial(pixel));gg_store_rng(pixel,rng);
   if(kind==GG_BOUNDARY){
    let out=15600u+8u*(p.u[3].y+pixel);probe.values[out]=f32(developed);probe.values[out+1u]=f32(rng.words);probe.values[out+2u]=f32(rng.steps);probe.values[out+3u]=f32(rng.failure);
    probe.values[out+4u]=f32(rng.state.x&65535u);probe.values[out+5u]=f32(rng.state.x>>16u);probe.values[out+6u]=f32(rng.state.y&65535u);probe.values[out+7u]=f32(rng.state.y>>16u);if(sf_failed!=0u){gg_fail();}return;
   }
   var value=0.0;if(kind==GG_CENSUS){value=gg_scalar(sf_mul(sf_integer(developed),sf_div(SF_ONE,a.lam)));}else{value=gg_output(developed,a.sat);}
   if(sf_failed!=0u||rng.failure!=0u||developed>particles){gg_fail();return;}
   if(kind==GG_CENSUS){
    let f=p.u[3].y+pixel;let original=gg_fixture(f,GG_CENSUS);let expected_state=grain_add64(original.rng.state,grain_mul64(vec2<u32>(rng.words,0u),vec2<u32>(0x7f4a7c15u,0x9e3779b9u)));
    if(any(rng.state!=expected_state)||rng.steps!=u32(probe.values[868352u+f])){gg_fail();}
    probe.values[5u*f]=f32(particles);probe.values[5u*f+1u]=f32(developed);probe.values[5u*f+2u]=f32(rng.words);probe.values[5u*f+3u]=value;probe.values[5u*f+4u]=f32(rng.failure);
   }else if(kind==GG_PREPASS){
    if(!gg_admission_cursor(pixel,1u,rng)){gg_fail();}for(var k=0u;k<9u;k=k+1u){probe.values[14u*pixel+k]=grain_scratch.frames[pixel].prepared[k];}
    probe.values[14u*pixel+9u]=f32(particles);probe.values[14u*pixel+10u]=f32(developed);probe.values[14u*pixel+11u]=f32(rng.words);probe.values[14u*pixel+12u]=value;probe.values[14u*pixel+13u]=f32(rng.failure);
   }else{source.values[at]=value;}
   if(serial){total_steps=total_steps+rng.steps;if(total_steps>8192u){gg_fail();return;}}
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
  if(!gg_phase_valid(op)){gg_fail();return;}if(atomicLoad(&tail_numeric_error.value)!=0u){return;}
  var a:GrainParameters;var rng=SoftRng(vec2<u32>(0u),0u,0u,0u,1u);
  if(kind==GG_PRODUCTION||kind==GG_PREPASS){a=gg_parameters(i);if(p.u[2].w==0u){rng=gg_rng(index);}}
  else{let f=gg_fixture(fixture,kind);a=GrainParameters(f.pb,SF_ONE,f.lam,true);rng=f.rng;}
  if(!a.valid||sf_failed!=0u){gg_fail();return;}
  gg_store_parameters(i,a);gg_store_rng(i,rng);grain_scratch.frames[i].prepared[17u]=0.0;gg_store_poisson(i,sr_prepare_poisson(a.lam));
  grain_scratch.frames[i].prepared[16u]=f32(sf_failed);if(sf_failed!=0u){gg_fail();}return;
 }
 if(op==66u){
  if(!gg_phase_valid(op)){gg_fail();return;}if(atomicLoad(&tail_numeric_error.value)!=0u){return;}
  var a:GrainParameters;var particles=0u;
  if(kind==GG_PRODUCTION&&p.u[2].w==1u){
   a=gg_parameters(i);if(!a.valid||sf_failed!=0u){gg_fail();return;}particles=u32(horizontal.values[index]);gg_store_parameters(i,a);gg_store_rng(i,SoftRng(vec2<u32>(0u),0u,0u,0u,1u));grain_scratch.frames[i].prepared[17u]=f32(particles);
  }else if(kind==GG_BOUNDARY){let f=gg_fixture(fixture,kind);a=GrainParameters(f.pb,SF_ONE,f.lam,true);particles=f.n;gg_store_parameters(i,a);gg_store_rng(i,f.rng);grain_scratch.frames[i].prepared[17u]=f32(particles);}
  else{a=gg_prepared(i);particles=u32(grain_scratch.frames[i].prepared[17u]);sf_failed=u32(grain_scratch.frames[i].prepared[16u]);}
  if(particles>=16777216u){gg_fail();return;}gg_store_binomial(i,sr_prepare_binomial(particles,a.pb));grain_scratch.frames[i].prepared[16u]=f32(sf_failed);
  // Coordinate consumers retain prior failure state and original draw behavior.
  // Serial preview keeps its existing fail-closed prepass contract.
  if(p.u[2].w==1u&&sf_failed!=0u){gg_fail();}return;
 }
 if(op==62u){
  if(p.u[0].w!=0u||p.u[1].x>256u||arrayLength(&grain_scratch.frames)<256u||arrayLength(&probe.values)<1029632u){gg_fail();return;}
  let original_a=sr_load64(1028096u+6u*i);let original_b=sr_load64(1028099u+6u*i);
  gg_store64(i,0u,original_a);gg_store64(i,3u,original_b);let a=gg_load64(i,0u);let b=gg_load64(i,3u);
  if(any(a!=original_a)||any(b!=original_b)){gg_fail();return;}
  let out=array<vec2<u32>,5>(sf_add(a,b),sf_sub(a,b),sf_mul(a,b),sf_div(a,b),sf_sqrt(sf_abs(a)));
  for(var j=0u;j<5u;j=j+1u){let value=out[j];let base=20u*i+4u*j;probe.values[base]=f32(value.x&65535u);probe.values[base+1u]=f32(value.x>>16u);probe.values[base+2u]=f32(value.y&65535u);probe.values[base+3u]=f32(value.y>>16u);}return;
 }
}
