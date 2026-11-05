// ExposureLog specialization. ABI, guards and operation bodies match research_global.
// Only the existing upload, logarithm and gather kernels are reachable here.
struct Data { values: array<f32>, }
struct Params { u: array<vec4<u32>, 10>, }
@group(0) @binding(0) var<storage, read_write> source: Data;
@group(0) @binding(1) var<storage, read_write> horizontal: Data;
@group(0) @binding(2) var<storage, read_write> blurred: Data;
@group(0) @binding(3) var<storage, read_write> saved: Data;
@group(0) @binding(4) var<storage, read_write> accum: Data;
@group(0) @binding(5) var<storage, read_write> probe: Data;
@group(0) @binding(6) var<uniform> p: Params;
fn read_plane(ch:u32,i:u32)->f32{if(ch==0u){return source.values[i];}if(ch==1u){return horizontal.values[i];}return blurred.values[i];}
fn write_plane(ch:u32,i:u32,v:f32){if(ch==0u){source.values[i]=v;}else if(ch==1u){horizontal.values[i]=v;}else{blurred.values[i]=v;}}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>){
 let i=gid.x+gid.y*65535u*64u;if(i>=p.u[1].x){return;}
 let op=p.u[0].z;
 if(op==10u){write_plane(p.u[1].z,p.u[1].w+i,gbound(probe.values[524288u+i]));return;}
 if(op==14u){let factor=gbound(bitcast<f32>(p.u[3].x));for(var ch=0u;ch<3u;ch=ch+1u){let corrected=gmul(read_plane(ch,i),factor);let out=gmul(glog(gadd(max(corrected,0.0),1e-10)),0.4342944819032518);write_plane(ch,i,out);}return;}
 if(op==15u){probe.values[i]=read_plane(p.u[1].z,p.u[1].w+i);return;}
}
