struct Header { values: array<vec4<u32>,16>, }
@group(0) @binding(0) var<storage,read> tables:array<f32>;
@group(0) @binding(1) var<storage,read> input:array<f32>;
@group(0) @binding(2) var<storage,read_write> output:array<f32>;
@group(0) @binding(3) var<uniform> header:Header;
fn u(i:u32)->u32{return header.values[i/4u][i%4u];}
fn f(i:u32)->f32{return bitcast<f32>(u(i));}
fn finite3(x:vec3<f32>)->bool{return all((bitcast<vec3<u32>>(x)&vec3(0x7fffffffu))<vec3(0x7f800000u));}
fn invalid()->vec3<f32>{return vec3(bitcast<f32>(0x7fc00000u));}
fn mix1(a:f32,b:f32,t:f32)->f32{return a+t*(b-a);}
fn hermite(a:f32,b:f32,ma:f32,mb:f32,t:f32)->f32{let t2=t*t;let t3=t2*t;let h00=2.0*t3-3.0*t2+1.0;let h10=t3-2.0*t2+t;let h01=-2.0*t3+3.0*t2;let h11=t3-t2;return h00*a+h10*ma+h01*b+h11*mb;}
fn at(off:u32,n:u32,kind:u32,i:u32,j:u32,k:u32,c:u32)->f32{return tables[off+kind*3u*n*n*n+((i*n+j)*n+k)*3u+c];}
fn pchip(s:u32,x:vec3<f32>)->vec3<f32>{
 let n=u(s);let off=u(s+1u);let mn=vec3(f(s+2u),f(s+3u),f(s+4u));let mx=vec3(f(s+5u),f(s+6u),f(s+7u));
 let raw=(x-mn)*(vec3(f32(n-1u))/(mx-mn));if !finite3(raw){return invalid();}let q=clamp(raw,vec3(0.0),vec3(f32(n-1u)));
 let b=min(vec3<u32>(floor(q)),vec3(n-2u));let t=q-vec3<f32>(b);var result:vec3<f32>;
 for(var c=0u;c<3u;c++){
  var vx:array<f32,4>;var sy:array<f32,4>;
  for(var y=0u;y<2u;y++){for(var z=0u;z<2u;z++){let j=b.y+y;let k=b.z+z;let a=y*2u+z;
   vx[a]=hermite(at(off,n,0u,b.x,j,k,c),at(off,n,0u,b.x+1u,j,k,c),at(off,n,1u,b.x,j,k,c),at(off,n,1u,b.x+1u,j,k,c),t.x);
   sy[a]=mix1(at(off,n,2u,b.x,j,k,c),at(off,n,2u,b.x+1u,j,k,c),t.x);
  }}
  let va=hermite(vx[0],vx[2],sy[0],sy[2],t.y);let vb=hermite(vx[1],vx[3],sy[1],sy[3],t.y);
  var sz:array<f32,2>;for(var z=0u;z<2u;z++){let k=b.z+z;sz[z]=mix1(mix1(at(off,n,3u,b.x,b.y,k,c),at(off,n,3u,b.x+1u,b.y,k,c),t.x),mix1(at(off,n,3u,b.x,b.y+1u,k,c),at(off,n,3u,b.x+1u,b.y+1u,k,c),t.x),t.y);}
  let count=3u*n*n*n;let cells=3u*(n-1u)*(n-1u)*(n-1u);let ci=((b.x*(n-1u)+b.y)*(n-1u)+b.z)*3u+c;
  let value=hermite(va,vb,sz[0],sz[1],t.z);if !finite3(vec3(value)){return invalid();}result[c]=clamp(value,tables[off+4u*count+ci],tables[off+4u*count+cells+ci]);
 }return result;
}
@compute @workgroup_size(64) fn main(@builtin(global_invocation_id) id:vec3<u32>){let i=id.x;if i>=u(0u){return;}var x=vec3(input[i*3u],input[i*3u+1u],input[i*3u+2u]);
 if u(1u)==1u || u(1u)==2u{x=pchip(4u,x);x=pow(vec3(10.0),x);if !finite3(x){x=invalid();}x=x*vec3(f(38u));if !finite3(x){x=invalid();}x=x*vec3(f(39u));}
 if !finite3(x){output[i*3u]=invalid().x;output[i*3u+1u]=invalid().x;output[i*3u+2u]=invalid().x;return;}
 if u(1u)==2u{output[i*3u]=x.x;output[i*3u+1u]=x.y;output[i*3u+2u]=x.z;return;}
 let count=u(37u);let axis=u(36u);if count>0u{for(var c=0u;c<3u;c++){let q=log(max(x[c],0.0)+1e-10)/log(10.0);var lo=0u;var hi=count;loop{if lo>=hi{break;}let mid=lo+(hi-lo)/2u;if tables[axis+mid]<=q{lo=mid+1u;}else{hi=mid;}}
  let curves=axis+count;if lo==0u{x[c]=tables[curves+c];}else if lo>=count{x[c]=tables[curves+(count-1u)*3u+c];}else{let inv=1.0/(tables[axis+lo]-tables[axis+lo-1u]);let t=(q-tables[axis+lo-1u])*inv;let a=tables[curves+(lo-1u)*3u+c];x[c]=a+t*(tables[curves+lo*3u+c]-a);}
 }}x=pchip(20u,x);output[i*3u]=x.x;output[i*3u+1u]=x.y;output[i*3u+2u]=x.z;
}
