// Bounded RGB density interpolation; one scalar per invocation. No spatial halos.
struct Params { count: u32, knots: u32, _pad0: u32, _pad1: u32 }
@group(0) @binding(0) var<storage, read> pixels: array<f32>;
@group(0) @binding(1) var<storage, read> axis: array<f32>;
@group(0) @binding(2) var<storage, read> curves: array<f32>;
@group(0) @binding(3) var<storage, read_write> result: array<f32>;
@group(0) @binding(4) var<uniform> params: Params;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
 let i=id.x;
 if(i>=params.count){return;}
 let c=i%3u; let x=pixels[i]; let last=params.knots-1u;
 if(x<=axis[0]) {result[i]=curves[c];return;}
 if(x>=axis[last]) {result[i]=curves[last*3u+c];return;}
 var lo=0u; var hi=last;
 loop {if(hi-lo<=1u){break;} let mid=(lo+hi)/2u;
   if(axis[mid]<=x){lo=mid;}else{hi=mid;}
 }
 let t=(x-axis[lo])/(axis[hi]-axis[lo]);
 result[i]=curves[lo*3u+c]+t*(curves[hi*3u+c]-curves[lo*3u+c]);
}
