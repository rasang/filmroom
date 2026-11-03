// Resident prefix. Host admits bounded immutable tables and strictly positive ranges.
@group(0) @binding(8) var<storage,read> finish_tables:array<f32>;
fn u(i:u32)->u32{return bitcast<u32>(finish_tables[i]);}
fn f(i:u32)->f32{return bitcast<f32>(u(i));}
fn finite3(x:vec3<f32>)->bool{return all((bitcast<vec3<u32>>(x)&vec3(0x7fffffffu))<vec3(0x7f800000u));}
fn invalid()->vec3<f32>{return tzero();}
fn mix1(a:f32,b:f32,t:f32)->f32{return tadd(a,tmul(t,tsub(b,a)));}
fn hermite(a:f32,b:f32,ma:f32,mb:f32,t:f32)->f32{let t2=t*t;let t3=t2*t;let h00=2.0*t3-3.0*t2+1.0;let h10=t3-2.0*t2+t;let h01=-2.0*t3+3.0*t2;let h11=t3-t2;return tadd(tadd(tadd(tmul(h00,a),tmul(h10,ma)),tmul(h01,b)),tmul(h11,mb));}
fn at(off:u32,n:u32,kind:u32,i:u32,j:u32,k:u32,c:u32)->f32{return finish_tables[64u+off+kind*3u*n*n*n+((i*n+j)*n+k)*3u+c];}
fn pchip(s:u32,x:vec3<f32>)->vec3<f32>{
 let n=u(s);let off=u(s+1u);let mn=vec3(f(s+2u),f(s+3u),f(s+4u));let mx=vec3(f(s+5u),f(s+6u),f(s+7u));
 let delta=vec3<f32>(tsub(x.x,mn.x),tsub(x.y,mn.y),tsub(x.z,mn.z));let raw=delta*(vec3(f32(n-1u))/(mx-mn));if !finite3(raw){return invalid();}let q=clamp(raw,vec3(0.0),vec3(f32(n-1u)));
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
  let value=hermite(va,vb,sz[0],sz[1],t.z);if !finite3(vec3(value)){return invalid();}result[c]=clamp(value,finish_tables[64u+off+4u*count+ci],finish_tables[64u+off+4u*count+cells+ci]);
 }return result;
}

fn finish_main(i:u32) {
 let offset=p.u[1].w;var x=tb3(vec3<f32>(probe.values[524288u+i*3u],probe.values[524289u+i*3u],probe.values[524290u+i*3u]));
 if(u(1u)==1u){x=pchip(4u,x);x=vec3<f32>(tpow(10.0,x.x),tpow(10.0,x.y),tpow(10.0,x.z));x=vec3<f32>(tmul(tmul(x.x,f(38u)),f(39u)),tmul(tmul(x.y,f(38u)),f(39u)),tmul(tmul(x.z,f(38u)),f(39u)));}
 let count=u(37u);let axis=u(36u);
 if(count>0u){for(var c=0u;c<3u;c++){let q=tlog(tadd(max(x[c],0.0),1e-10))/log(10.0);var lo=0u;var hi=count;
 loop{if(lo>=hi){break;}let mid=lo+(hi-lo)/2u;if(finish_tables[64u+axis+mid]<=q){lo=mid+1u;}else{hi=mid;}}
 let curves=64u+axis+count;
 if(lo==0u){x[c]=finish_tables[curves+c];}else if(lo>=count){x[c]=finish_tables[curves+(count-1u)*3u+c];}
 else{let inv=tdiv(1.0,tsub(finish_tables[64u+axis+lo],finish_tables[64u+axis+lo-1u]));let t=tmul(tsub(q,finish_tables[64u+axis+lo-1u]),inv);x[c]=mix1(finish_tables[curves+(lo-1u)*3u+c],finish_tables[curves+lo*3u+c],t);}
 }}
 x=tb3(pchip(20u,x));
 let samples=min(p.u[1].x,8u);for(var j=0u;j<samples;j++){var at=0u;if(samples>1u){at=j*(p.u[1].x-1u)/(samples-1u);}if(i==at){probe.values[256u+3u*j]=x.x;probe.values[257u+3u*j]=x.y;probe.values[258u+3u*j]=x.z;}}
 var glare=0.0;if(p.u[1].z!=0u){glare=source.values[offset+i];}let rgb=tail_color(x,glare);
 source.values[offset+i]=rgb.x;saved.values[offset+i]=rgb.y;accum.values[offset+i]=rgb.z;
}
