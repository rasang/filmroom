// Adaptation of this repository's original CoordinateRng + NumPy-style sampler.
// Every SplitMix64 word and original two-word uniform consumption is preserved.
// Floating-point rejection decisions are explicitly experimental f32, not f64 parity.
struct GrainRng {state:vec2<u32>, words:u32, steps:u32, failed:bool,}
fn grain_add64(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{let lo=a.x+b.x;return vec2<u32>(lo,a.y+b.y+select(0u,1u,lo<a.x));}
fn grain_mul_hi(a:u32,b:u32)->u32{
 let a0=a&65535u;let a1=a>>16u;let b0=b&65535u;let b1=b>>16u;
 let w0=a0*b0;let t=a1*b0+(w0>>16u);let w1=a0*b1+(t&65535u);return a1*b1+(t>>16u)+(w1>>16u);
}
fn grain_mul64(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{return vec2<u32>(a.x*b.x,grain_mul_hi(a.x,b.x)+a.x*b.y+a.y*b.x);}
fn grain_shr64(a:vec2<u32>,n:u32)->vec2<u32>{return vec2<u32>((a.x>>n)|(a.y<<(32u-n)),a.y>>n);}
fn grain_mix64(v:vec2<u32>)->vec2<u32>{
 var z=grain_mul64(v^grain_shr64(v,30u),vec2<u32>(0x1ce4e5b9u,0xbf58476du));
 z=grain_mul64(z^grain_shr64(z,27u),vec2<u32>(0x133111ebu,0x94d049bbu));return z^grain_shr64(z,31u);
}
fn grain_rng(seed:vec2<u32>,x:u32,y:u32)->GrainRng{
 let inc=vec2<u32>(0x7f4a7c15u,0x9e3779b9u);
 let s=seed^grain_mul64(vec2<u32>(x,0u),inc)^grain_mul64(vec2<u32>(y,0u),vec2<u32>(0xd192ed03u,0xd1b54a32u));
 return GrainRng(grain_mix64(grain_add64(s,inc)),0u,0u,false);
}
fn grain_tick(r:ptr<function,GrainRng>)->bool{(*r).steps=(*r).steps+1u;if((*r).steps>4096u){(*r).failed=true;}return !(*r).failed;}
fn grain_word(r:ptr<function,GrainRng>)->vec2<u32>{(*r).state=grain_add64((*r).state,vec2<u32>(0x7f4a7c15u,0x9e3779b9u));(*r).words=(*r).words+1u;return grain_mix64((*r).state);}
fn grain_uniform(r:ptr<function,GrainRng>)->f32{
 let a=grain_word(r).y>>5u;let b=grain_word(r).y>>6u;
 // Exact rounding of the original 53-bit uniform to f32, using integers.
 var bits=32u-countLeadingZeros(b);if(a!=0u){bits=32u-countLeadingZeros(a)+26u;}
 let lo=(a<<26u)|b;let hi=a>>6u;
 if(bits<=24u){return f32(lo)*bitcast<f32>(74u<<23u);}
 let shift=bits-24u;var mantissa=(lo>>shift)|(hi<<(32u-shift));
 let rest=lo&((1u<<shift)-1u);let half=1u<<(shift-1u);
 if(rest>half||(rest==half&&(mantissa&1u)!=0u)){mantissa=mantissa+1u;}
 return f32(mantissa)*bitcast<f32>((74u+shift)<<23u);
}
fn grain_poisson(r:ptr<function,GrainRng>,lam:f32)->u32{
 if(lam==0.0){return 0u;}
 if(lam<10.0){
  let enlam=exp(-lam);var x=0u;var prod=1.0;
  loop{if(!grain_tick(r)){return 0u;}prod=prod*grain_uniform(r);if(prod>enlam){x=x+1u;}else{return x;}}
 }
 let slam=sqrt(lam);let loglam=log(lam);let b=0.931+2.53*slam;let a=-0.059+0.02483*b;
 let invalpha=1.1239+1.1328/(b-3.4);let vr=0.9277-3.6224/(b-2.0);
 loop{
  if(!grain_tick(r)){return 0u;}
  let u=grain_uniform(r)-0.5;let v=grain_uniform(r);let us=0.5-abs(u);
  let kf=floor((2.0*a/us+b)*u+lam+0.43);
  if(us>=0.07&&v<=vr){return u32(kf);}
  if(kf<0.0||(us<0.013&&v>us)){continue;}
  // Proven original-PTRS rejection: log P(k) <= -991, lhs > -114 for
  // nonzero original 53-bit uniforms, lambda<=240000, k>=262144.
  // Zero v is excluded and must fail closed, never silently truncate.
  if(kf>=262144.0&&v>0.0&&lam<=240000.0){continue;}
  if(!finite_value(kf)||kf>=262144.0){(*r).failed=true;return 0u;}
  let k=i32(kf);
  let lhs=log(v)+log(invalpha)-log(a/(us*us)+b);
  let rhs=-lam+f32(k)*loglam-probe.values[262144u+u32(k)];
  if(lhs<=rhs){return u32(k);}
 }
 return 0u;
}
fn grain_inversion(rng:ptr<function,GrainRng>,n:u32,pb:f32)->u32{
 let q=1.0-pb;let qn=exp(f32(n)*log(q));let np=f32(n)*pb;
 let bound=u32(min(f32(n),np+10.0*sqrt(np*q+1.0)));
 var x=0u;var px=qn;var u=grain_uniform(rng);
 loop{
  if(!(u>px)){return x;}
  if(!grain_tick(rng)){return 0u;}
  x=x+1u;
  if(x>bound){x=0u;px=qn;u=grain_uniform(rng);}else{u=u-px;px=(f32(n-x+1u)*pb*px)/(f32(x)*q);}
 }
 return 0u;
}
fn grain_btpe(rng:ptr<function,GrainRng>,n:u32,pb:f32)->u32{
 let r=min(pb,1.0-pb);let q=1.0-r;let nf=f32(n);let fm=nf*r+r;let m=i32(floor(fm));
 let p1=floor(2.195*sqrt(nf*r*q)-4.6*q)+0.5;let xm=f32(m)+0.5;let xl=xm-p1;let xr=xm+p1;
 let c=0.134+20.5/(15.3+f32(m));let a1=(fm-xl)/(fm-xl*r);let laml=a1*(1.0+a1/2.0);
 let a2=(xr-fm)/(xr*q);let lamr=a2*(1.0+a2/2.0);
 let p2=p1*(1.0+2.0*c);let p3=p2+c/laml;let p4=p3+c/lamr;let nrq=nf*r*q;
 var y=0i;
 loop{
  if(!grain_tick(rng)){return 0u;}
  let u=grain_uniform(rng)*p4;var v=grain_uniform(rng);var cand=0i;
  if(u<=p1){y=i32(floor(xm-p1*v+u));break;}
  else if(u<=p2){let x=xl+(u-p1)/c;v=v*c+1.0-abs(f32(m)-x+0.5)/p1;if(v>1.0){continue;}cand=i32(floor(x));}
  else if(u<=p3){let cf=floor(xl+log(v)/laml);if(!finite_value(cf)||cf<0.0){continue;}cand=i32(cf);v=v*(u-p2)*laml;}
  else {let cf=floor(xr-log(v)/lamr);if(!finite_value(cf)||cf>nf){continue;}cand=i32(cf);v=v*(u-p3)*lamr;}
  let k=abs(cand-m);
  if(k<=20||f32(k)>=nrq/2.0-1.0){
   let s=r/q;let a=s*(nf+1.0);var f=1.0;
   if(m<cand){for(var j=m+1;j<=cand;j=j+1){if(!grain_tick(rng)){return 0u;}f=f*(a/f32(j)-s);}}
   else if(m>cand){for(var j=cand+1;j<=m;j=j+1){if(!grain_tick(rng)){return 0u;}f=f/(a/f32(j)-s);}}
   if(v>f){continue;}
  }else{
   let kf=f32(k);let rho=(kf/nrq)*((kf*(kf/3.0+0.625)+0.16666666666666666)/nrq+0.5);
   let t=-kf*kf/(2.0*nrq);let a_log=log(v);
   if(a_log<t-rho){y=cand;break;}if(a_log>t+rho){continue;}
   let x1=f32(cand+1);let f1=f32(m+1);let z=f32(i32(n)-m+1);let w=f32(i32(n)-cand+1);
   let x2=x1*x1;let f2=f1*f1;let z2=z*z;let w2=w*w;
   let bound=xm*log(f1/x1)+(nf-f32(m)+0.5)*log(z/w)+f32(cand-m)*log(w*r/(x1*q))
    +(13680.0-(462.0-(132.0-(99.0-140.0/f2)/f2)/f2)/f2)/f1/166320.0
    +(13680.0-(462.0-(132.0-(99.0-140.0/z2)/z2)/z2)/z2)/z/166320.0
    +(13680.0-(462.0-(132.0-(99.0-140.0/x2)/x2)/x2)/x2)/x1/166320.0
    +(13680.0-(462.0-(132.0-(99.0-140.0/w2)/w2)/w2)/w2)/w/166320.0;
   if(a_log>bound){continue;}
  }
  y=cand;break;
 }
 if(y<0||y>i32(n)){(*rng).failed=true;return 0u;}
 if(pb>0.5){return n-u32(y);}return u32(y);
}
fn grain_binomial(rng:ptr<function,GrainRng>,n:u32,pb:f32)->u32{
 if(pb<=0.5){if(pb*f32(n)<=30.0){return grain_inversion(rng,n,pb);}return grain_btpe(rng,n,pb);}
 let q=1.0-pb;if(q*f32(n)<=30.0){return n-grain_inversion(rng,n,q);}return n-grain_btpe(rng,n,q);
}
