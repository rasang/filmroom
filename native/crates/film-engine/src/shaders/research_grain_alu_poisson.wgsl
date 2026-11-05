fn sr_log_factorial(k:u32)->vec2<u32>{
 if(k<262144u){return sr_load64(81920u+3u*k);}
 let kf=sf_integer(k);grain_scratch.frames[grain_alu_lane].alu_registers[0u]=kf;let arithmetic=sr_palu_run(0u);return arithmetic.a;
}
fn sr_log_factorial_error(k:u32)->vec2<u32>{if(k<262144u){return vec2<u32>(0u);}let kf=sf_integer(k);grain_scratch.frames[grain_alu_lane].alu_registers[0u]=kf;let arithmetic=sr_palu_run(2u);return arithmetic.a;
}
fn sr_poisson_prepared(r:ptr<function,SoftRng>,lam:vec2<u32>,prepared:SrPoissonSetup)->u32{
 if(sf_zero(lam)){return 0u;}
 if(sf_cmp(lam,vec2<u32>(0x00000000u,0x40240000u))<0){let enlam=prepared.enlam;var x=0u;var prod=SF_ONE;
  loop{if(!sr_tick(r)){return 0u;}prod=sf_mul(prod,sr_uniform(r));if(!sr_prove(r,prod,enlam,sf_mul(vec2<u32>(0x812dea11u,0x3d919799u),enlam))){return 0u;}if(sf_cmp(prod,enlam)>0){x=x+1u;}else{return x;}}
 }
 let loglam=prepared.loglam;let b=prepared.b;let a=prepared.a;let invalpha=prepared.invalpha;let vr=prepared.vr;
 loop{
  if(!sr_tick(r)){return 0u;}let uniform=sr_uniform(r);let u=sf_sub(uniform,vec2<u32>(0x00000000u,0x3fe00000u));let v=sr_uniform(r);let us=sf_sub(vec2<u32>(0x00000000u,0x3fe00000u),sf_abs(u));
  let proposal=sf_add(sf_add(sf_mul(sf_add(sf_div(sf_mul(vec2<u32>(0x00000000u,0x40000000u),a),us),b),u),lam),vec2<u32>(0xb851eb85u,0x3fdb851eu));let k=sf_floor(proposal);
  if(sf_cmp(us,vec2<u32>(0x1eb851ecu,0x3fb1eb85u))>=0&&sf_cmp(v,vr)<=0){if(k<0||k>=16777216){(*r).failure=1u;return 0u;}return u32(k);}
  if((proposal.y>>31u)!=0u||(sf_cmp(us,vec2<u32>(0x76c8b439u,0x3f8a9fbeu))<0&&sf_cmp(v,us)>0)){continue;}

  if(!sf_valid(proposal)||k<0||k>=16777216){(*r).failure=1u;return 0u;}
  let kf=sf_integer(u32(k));let denominator=sf_add(sf_div(a,sf_mul(us,us)),b);let factorial=sr_log_factorial(u32(k));
  grain_scratch.frames[grain_alu_lane].alu_registers[0u]=v;grain_scratch.frames[grain_alu_lane].alu_registers[1u]=invalpha;grain_scratch.frames[grain_alu_lane].alu_registers[2u]=denominator;grain_scratch.frames[grain_alu_lane].alu_registers[3u]=lam;grain_scratch.frames[grain_alu_lane].alu_registers[4u]=kf;grain_scratch.frames[grain_alu_lane].alu_registers[5u]=loglam;grain_scratch.frames[grain_alu_lane].alu_registers[6u]=factorial;
  let middle=sr_palu_run(1u);
  let lhs=middle.a;let rhs=middle.b;let ev=middle.c;let ei=middle.d;let ed=middle.e;let el=middle.f;
  let ef=sr_log_factorial_error(u32(k));let error=sf_add(sf_add(sf_add(sf_add(sf_add(ef,ev),ei),ed),sf_mul(kf,el)),sf_mul(sf_mul(vec2<u32>(0x00000000u,0x40200000u),vec2<u32>(0x00000000u,0x3cb00000u)),sf_add(sf_add(sf_abs(lam),sf_abs(sf_mul(kf,loglam))),sf_abs(factorial))));
  if(!sr_prove(r,lhs,rhs,error)){return 0u;}if(sf_cmp(lhs,rhs)<=0){return u32(k);}
 }
 return 0u;
}
