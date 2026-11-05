fn sr_btpe_prepared(rng:ptr<function,SoftRng>,n:u32,pb:vec2<u32>,prepared:SrBinomialSetup)->u32{let r=prepared.v[0u];let q=prepared.v[1u];let nf=prepared.v[2u];let fm=prepared.v[3u];let m=sf_floor(prepared.v[4u]);let mf=prepared.v[5u];let p1=prepared.v[6u];let xm=prepared.v[7u];let xl=prepared.v[8u];let xr=prepared.v[9u];let c=prepared.v[10u];let laml=prepared.v[11u];let lamr=prepared.v[12u];let p2=prepared.v[13u];let p3=prepared.v[14u];let p4=prepared.v[15u];let nrq=prepared.v[16u];var y=0i;
 loop{
  if(!sr_tick(rng)){return 0u;}let uniform=sr_uniform(rng);let u=sf_mul(uniform,p4);var v=sr_uniform(rng);var cand=0i;
  if(sf_cmp(u,p1)<=0){y=sf_floor(sf_add(sf_sub(xm,sf_mul(p1,v)),u));break;}
  else if(sf_cmp(u,p2)<=0){let x=sf_add(xl,sf_div(sf_sub(u,p1),c));v=sf_sub(sf_add(sf_mul(v,c),vec2<u32>(0x00000000u,0x3ff00000u)),sf_div(sf_abs(sf_add(sf_sub(mf,x),vec2<u32>(0x00000000u,0x3fe00000u))),p1));if(sf_cmp(v,SF_ONE)>0){continue;}cand=sf_floor(x);}
  else{
   let tail_left=sf_cmp(u,p3)<=0;var tail_program=1u;
   if(tail_left){tail_program=0u;grain_scratch.frames[grain_alu_lane].alu_registers[0u]=xl;grain_scratch.frames[grain_alu_lane].alu_registers[1u]=laml;}else{grain_scratch.frames[grain_alu_lane].alu_registers[0u]=xr;grain_scratch.frames[grain_alu_lane].alu_registers[1u]=lamr;}
   grain_scratch.frames[grain_alu_lane].alu_registers[2u]=v;
   let tail_result=sr_alu_run(tail_program);
   let candidate=tail_result.a;let tail_error=tail_result.b;
   if(tail_left){cand=sr_floor(rng,candidate,tail_error);if((*rng).failure!=0u){return 0u;}if(cand<0){continue;}v=sf_mul(v,sf_mul(sf_sub(u,p2),laml));}
   else{cand=sr_floor(rng,candidate,tail_error);if((*rng).failure!=0u){return 0u;}if(cand>i32(n)){continue;}v=sf_mul(v,sf_mul(sf_sub(u,p3),lamr));}
  }
  let k=abs(cand-m);let kf=sf_integer(u32(k));
  if(k<=20||sf_cmp(kf,sf_sub(sf_div(nrq,vec2<u32>(0x00000000u,0x40000000u)),vec2<u32>(0x00000000u,0x3ff00000u)))>=0){let s=sf_div(r,q);let a=sf_mul(s,sf_add(nf,vec2<u32>(0x00000000u,0x3ff00000u)));var f=SF_ONE;
   if(m<cand){for(var j=m+1;j<=cand;j=j+1){if(!sr_tick(rng)){return 0u;}let jf=sf_signed(j);f=sf_mul(f,sf_sub(sf_div(a,jf),s));}}
   else if(m>cand){for(var j=cand+1;j<=m;j=j+1){if(!sr_tick(rng)){return 0u;}let jf=sf_signed(j);f=sf_div(f,sf_sub(sf_div(a,jf),s));}}
   if(sf_cmp(v,f)>0){continue;}
  }else{
   grain_scratch.frames[grain_alu_lane].alu_registers[0u]=kf;grain_scratch.frames[grain_alu_lane].alu_registers[1u]=nrq;grain_scratch.frames[grain_alu_lane].alu_registers[2u]=v;
   let rho_result=sr_alu_run(2u);
   let rho=rho_result.a;let t=rho_result.b;let alog=rho_result.c;let ev=rho_result.d;let interval=rho_result.e;
   if(!sr_prove(rng,alog,sf_sub(t,rho),interval)||!sr_prove(rng,alog,sf_add(t,rho),interval)){return 0u;}
   if(sf_cmp(alog,sf_sub(t,rho))<0){y=cand;break;}if(sf_cmp(alog,sf_add(t,rho))>0){continue;}
   let x1=sf_signed(cand+1);let f1=sf_signed(m+1);let z=sf_signed(i32(n)-m+1);let w=sf_signed(i32(n)-cand+1);
   let x2=sf_mul(x1,x1);let f2=sf_mul(f1,f1);let z2=sf_mul(z,z);let w2=sf_mul(w,w);let difference=sf_signed(cand-m);
   grain_scratch.frames[grain_alu_lane].alu_registers[0u]=r;grain_scratch.frames[grain_alu_lane].alu_registers[1u]=q;grain_scratch.frames[grain_alu_lane].alu_registers[2u]=nf;grain_scratch.frames[grain_alu_lane].alu_registers[3u]=mf;grain_scratch.frames[grain_alu_lane].alu_registers[4u]=xm;grain_scratch.frames[grain_alu_lane].alu_registers[5u]=ev;grain_scratch.frames[grain_alu_lane].alu_registers[6u]=x1;grain_scratch.frames[grain_alu_lane].alu_registers[7u]=f1;grain_scratch.frames[grain_alu_lane].alu_registers[8u]=z;grain_scratch.frames[grain_alu_lane].alu_registers[9u]=w;grain_scratch.frames[grain_alu_lane].alu_registers[10u]=x2;grain_scratch.frames[grain_alu_lane].alu_registers[11u]=f2;grain_scratch.frames[grain_alu_lane].alu_registers[12u]=z2;grain_scratch.frames[grain_alu_lane].alu_registers[13u]=w2;grain_scratch.frames[grain_alu_lane].alu_registers[14u]=difference;
   let cold_result=sr_alu_run(3u);
   let bound=cold_result.a;let error=cold_result.b;
   if(!sr_prove(rng,alog,bound,error)){return 0u;}if(sf_cmp(alog,bound)>0){continue;}
  }
  y=cand;break;
 }
 if(y<0||y>i32(n)){(*rng).failure=1u;return 0u;}if(sf_cmp(pb,vec2<u32>(0x00000000u,0x3fe00000u))>0){return n-u32(y);}return u32(y);
}
fn sr_binomial_prepared(rng:ptr<function,SoftRng>,n:u32,pb:vec2<u32>,prepared:SrBinomialSetup)->u32{
 let nf=sf_integer(n);var effective=pb;var flip=false;var inversion=false;
 if(sf_cmp(pb,vec2<u32>(0x00000000u,0x3fe00000u))<=0){inversion=sf_cmp(sf_mul(pb,nf),vec2<u32>(0x00000000u,0x403e0000u))<=0;}
 else{let q=sf_sub(vec2<u32>(0x00000000u,0x3ff00000u),pb);effective=q;flip=true;inversion=sf_cmp(sf_mul(q,nf),vec2<u32>(0x00000000u,0x403e0000u))<=0;}
 var value=0u;if(inversion){value=sr_inversion_prepared(rng,n,effective,prepared);}else{value=sr_btpe_prepared(rng,n,effective,prepared);}
 if(flip){return n-value;}return value;
}
