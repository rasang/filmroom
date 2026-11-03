// The original full grain graph uses five resident planes. saved keeps the
// immutable input channel for every layer and for the final amount blend.
fn grain_mapped(value:f32)->f32{
 if(!finite_value(value)){return bitcast<f32>(2143289344u);}
 let dmin=bitcast<f32>(p.u[3].x);let knots=p.u[4].z;
 if(knots==0u){return value+dmin;}
 var q=value;if(p.u[2].z!=0u){q=-q;}
 let axis=p.u[4].x;let values=p.u[4].y;let last=knots-1u;
 if(q<=probe.values[axis]){return probe.values[values]+dmin;}
 if(q>=probe.values[axis+last]){return probe.values[values+last]+dmin;}
 // Match Rust 1.88 slice::partition_point's binary_search_by walk exactly.
 // Measured profile axes have nonmonotonic toe/shoulder points: sorting or a
 // conventional lo/hi binary search would change the original mapping.
 var size=knots;var base=0u;
 loop{if(size<=1u){break;}let half=size/2u;let mid=base+half;
  if(probe.values[axis+mid]<=q){base=mid;}size=size-half;}
 var right=base;if(probe.values[axis+base]<=q){right=right+1u;}
 let hi=min(right,last);let lo=hi-1u;
 let dx=probe.values[axis+hi]-probe.values[axis+lo];var t=0.0;if(dx!=0.0){t=(q-probe.values[axis+lo])/dx;}
 return probe.values[values+lo]+t*(probe.values[values+hi]-probe.values[values+lo])+dmin;
}
struct GrainSample {value:f32, particles:u32, developed:u32, words:u32,}
fn grain_sample(index:u32)->GrainSample {
 let value=grain_mapped(saved.values[index]);let dmax=bitcast<f32>(p.u[3].y);let npp=bitcast<f32>(p.u[3].z);let gu=bitcast<f32>(p.u[3].w);
 if(!finite_value(value)){return GrainSample(bitcast<f32>(2143289344u),0u,0u,0u);}
 let pb=clamp(value/dmax,1e-6,1.0-1e-6);let sat=1.0-pb*gu*(1.0-1e-6);let lam=npp/sat;
 if(!finite_value(lam)||lam<0.0||lam>240000.0){return GrainSample(bitcast<f32>(2143289344u),0u,0u,0u);}
 let w=p.u[0].x;var rng=grain_rng(p.u[2].xy,p.u[7].x+index%w,p.u[7].y+index/w);
 let particles=grain_poisson(&rng,lam);let developed=grain_binomial(&rng,particles,pb);
 var v=f32(developed)*(dmax/npp)*sat;
 if(rng.failed||particles>=262144u||developed>particles){v=bitcast<f32>(2143289344u);}
 return GrainSample(v,particles,developed,rng.words);
}
fn grain_main(i:u32){
 let op=p.u[0].z;let index=p.u[1].w+i;let w=p.u[0].x;
 if(op==20u){saved.values[index]=probe.values[786432u+i];return;}
 if(op==21u){source.values[index]=grain_sample(index).value;return;}
 if(op==22u){
  var rng=grain_rng(p.u[2].xy,p.u[7].x+index%w,p.u[7].y+index/w);
  let u=max(grain_uniform(&rng),bitcast<f32>(8388608u));let v=grain_uniform(&rng);
  let normal=sqrt(-2.0*log(u))*cos(6.283185307179586*v);
  source.values[index]=exp(bitcast<f32>(p.u[6].x)+bitcast<f32>(p.u[6].y)*normal);return;
 }
 if(op==23u){accum.values[i]=accum.values[i]+get_value(p.u[6].z,i);return;}
 if(op==24u){accum.values[i]=accum.values[i]*get_value(p.u[6].z,i);return;}
 if(op==25u){source.values[i]=accum.values[i]*bitcast<f32>(p.u[5].z)-bitcast<f32>(p.u[5].w);return;}
 if(op==26u){source.values[i]=saved.values[i]+bitcast<f32>(p.u[5].y)*(source.values[i]-saved.values[i]);return;}
 // Exact diagnostic words for actual-device comparison, never replacing samples.
 if(op==27u){
  var rng=grain_rng(p.u[2].xy,p.u[7].x+i%w,p.u[7].y+i/w);
  for(var j=0u;j<8u;j=j+1u){let word=grain_word(&rng);let base=32u*i+4u*j;
   probe.values[base]=f32(word.x&65535u);probe.values[base+1u]=f32(word.x>>16u);
   probe.values[base+2u]=f32(word.y&65535u);probe.values[base+3u]=f32(word.y>>16u);}
return;
 }
 if(op==28u){
  var pixel=0u;if(p.u[1].x>1u){pixel=i*(p.u[1].y-1u)/(p.u[1].x-1u);}let at=p.u[1].w+pixel;
  let sampled=grain_sample(at);let base=5u*i;
  probe.values[base]=sampled.value;probe.values[base+1u]=f32(sampled.particles);
  probe.values[base+2u]=f32(sampled.developed);probe.values[base+3u]=f32(sampled.words);probe.values[base+4u]=source.values[at];return;
 }

}
