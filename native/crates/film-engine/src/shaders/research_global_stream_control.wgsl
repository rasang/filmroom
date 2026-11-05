// Bounded transport/reduction state, using the existing eight-binding ABI.
// accum[0] = running full-image maximum; accum[1..9] = immutable GPU coefficients.
// saved[0..1024] = this chunk's original 1024-pixel max partials.
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>){
 let i=gid.x+gid.y*65535u*64u;if(i>=p.u[1].x){return;}
 if(p.u[1].x!=1u){atomicOr(&global_numeric_error.value,1u);return;}
 let op=p.u[0].z;
 if(op==17u){accum.values[0]=-bitcast<f32>(2139095039u);return;}
 if(op==18u){
  if(p.u[1].y==0u||p.u[1].y>1024u){atomicOr(&global_numeric_error.value,1u);return;}
  var peak=accum.values[0];
  for(var j=0u;j<p.u[1].y;j=j+1u){peak=max(peak,gbound(saved.values[j]));}
  accum.values[0]=peak;saved.values[0]=peak;return;
 }
 if(op==19u){for(var k=0u;k<8u;k=k+1u){accum.values[1u+k]=probe.values[k];}return;}
 if(op==20u){for(var k=0u;k<8u;k=k+1u){probe.values[k]=accum.values[1u+k];}return;}
 atomicOr(&global_numeric_error.value,1u);
}
