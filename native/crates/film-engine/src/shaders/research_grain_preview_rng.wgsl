// Exact original MT19937 preview word stream. Diagnostic foundation only;
// never substitute this for a complete Poisson-all / binomial-all grain graph.
const MT_BASE:u32=80000u;
fn mt_load(i:u32)->u32{return u32(probe.values[MT_BASE+2u*i])|(u32(probe.values[MT_BASE+2u*i+1u])<<16u);}
fn mt_store(i:u32,v:u32){probe.values[MT_BASE+2u*i]=f32(v&65535u);probe.values[MT_BASE+2u*i+1u]=f32(v>>16u);}
fn mt_initialize(seed:u32){
 mt_store(0u,seed);var previous=seed;
 for(var i=1u;i<624u;i=i+1u){previous=1812433253u*(previous^(previous>>30u))+i;mt_store(i,previous);}
 probe.values[MT_BASE+1248u]=624.0;probe.values[MT_BASE+1249u]=0.0;
 for(var i=1250u;i<1254u;i=i+1u){probe.values[MT_BASE+i]=0.0;}
}
fn mt_next()->u32{
 var index=u32(probe.values[MT_BASE+1248u]);
 if(index>=624u){
  for(var i=0u;i<624u;i=i+1u){let y=(mt_load(i)&0x80000000u)|(mt_load((i+1u)%624u)&0x7fffffffu);
   var parity=0u;if((y&1u)!=0u){parity=0x9908b0dfu;}mt_store(i,mt_load((i+397u)%624u)^(y>>1u)^parity);}
  index=0u;
 }
 var y=mt_load(index);probe.values[MT_BASE+1248u]=f32(index+1u);
 var carry=1u;for(var j=1250u;j<1254u;j=j+1u){let value=u32(probe.values[MT_BASE+j])+carry;probe.values[MT_BASE+j]=f32(value&65535u);carry=value>>16u;}
 y=y^(y>>11u);y=y^((y<<7u)&0x9d2c5680u);y=y^((y<<15u)&0xefc60000u);return y^(y>>18u);
}
fn mt_uniform_numerator()->vec2<u32>{let a=mt_next()>>5u;let b=mt_next()>>6u;return vec2<u32>((a<<26u)|b,a>>6u);}
fn preview_rng_main(i:u32){
 if(i!=0u){return;}
 if(p.u[0].z==40u){mt_initialize(p.u[2].x);return;}
 if(p.u[0].z==41u){
  // At most 32768 exact u32 words per bounded diagnostic dispatch.
  for(var j=0u;j<min(p.u[1].y,32768u);j=j+1u){let word=mt_next();probe.values[2u*j]=f32(word&65535u);probe.values[2u*j+1u]=f32(word>>16u);}return;
 }
}
