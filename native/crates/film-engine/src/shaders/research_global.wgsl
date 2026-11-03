// Original global operators. Image luminance, statistics, median, EV, full RGB
// peak and output operators execute on GPU. Compose with research_global_guard.
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
fn boost_point(v:f32)->f32 {
 let value=gbound(v);let peak=gbound(probe.values[0]);let mode=probe.values[1];let x0=gbound(probe.values[2]);
 if(mode!=1.0){return value;}if(peak==0.0){return 0.0;}if(value<=x0){return value;}
 let a=gbound(probe.values[3]);let inv=gbound(probe.values[4]);let scale=gbound(probe.values[5]);
 let d=gmul(gsub(value,x0),inv);let t=gmul(a,d);let curve=gsub(gsub(gexp(t),t),1.0);
 return gadd(value,gmul(scale,curve));
}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>){
 let i=gid.x+gid.y*65535u*64u;if(i>=p.u[1].x){return;}
 let op=p.u[0].z;let n=p.u[2].x;let method=p.u[2].y;
 if(op==0u){
  if(i>=n){source.values[i]=bitcast<f32>(2139095039u);accum.values[i]=0.0;return;}
  let base=524288u+7u*i;let r=gbound(probe.values[base]);let g=gbound(probe.values[base+1u]);let b=gbound(probe.values[base+2u]);
  source.values[i]=gadd(gadd(gmul(bitcast<f32>(p.u[3].x),r),gmul(bitcast<f32>(p.u[3].y),g)),gmul(bitcast<f32>(p.u[3].z),b));
  accum.values[i]=0.0;return;
 }
 // Disjoint pairs, bounded <=65536 sorting network. Padding never participates
 // in arithmetic and every real sample is bounded before entering the network.
 if(op==1u){let j=i^p.u[1].y;if(j<=i||j>=p.u[1].x){return;}let a=source.values[i];let b=source.values[j];let up=(i&p.u[1].z)==0u;if((up&&a>b)||(!up&&a<b)){source.values[i]=b;source.values[j]=a;}return;}
 if(op==2u){
  var s:array<f32,40>;for(var k=0u;k<40u;k=k+1u){s[k]=0.0;}
  let end=min((i+1u)*256u,n);
  for(var j=i*256u;j<end;j=j+1u){
   let y=gbound(source.values[j]);let g=524288u+7u*j+3u;
   if(method==1u||method==4u||method==6u||method==7u){s[0]=gadd(s[0],y);}
   if(method==3u){let w=gbound(probe.values[g]);s[1]=gadd(s[1],gmul(y,w));s[2]=gadd(s[2],w);}
   if(method==4u){let w=gbound(probe.values[g+1u]);s[3]=gadd(s[3],gmul(y,w));s[4]=gadd(s[4],w);}
   if(method==7u){let w=gmul(y,y);s[5]=gadd(s[5],gmul(y,w));s[6]=gadd(s[6],w);}
   if(method==6u){let raw=probe.values[g+2u];if(raw<0.0||raw>3.0){s[7]=gfail();return;}let ring=u32(raw);if(ring<3u){s[8u+2u*ring]=gadd(s[8u+2u*ring],y);s[9u+2u*ring]=gadd(s[9u+2u*ring],1.0);}}
   if(method==5u){let raw=probe.values[g+3u];if(raw<0.0||raw>25.0){s[7]=gfail();return;}let cell=u32(raw);if(cell<25u){s[14u+cell]=gadd(s[14u+cell],y);}}
  }
  for(var k=0u;k<40u;k=k+1u){probe.values[64u+40u*i+k]=s[k];}return;
 }
 if(op==3u){
  var s:array<f32,40>;for(var k=0u;k<40u;k=k+1u){s[k]=0.0;}
  for(var j=0u;j<p.u[1].y;j=j+1u){for(var k=0u;k<40u;k=k+1u){s[k]=gadd(s[k],probe.values[64u+40u*j+k]);}}
  let average=gdiv(s[0],f32(n));var meter=0.184;
  if(method==1u){meter=average;}
  if(method==2u){if(n%2u==1u){meter=source.values[n/2u];}else{meter=gmul(0.5,gadd(source.values[n/2u-1u],source.values[n/2u]));}}
  if(method==3u){meter=gdiv(s[1],s[2]);}
  if(method==4u){meter=average;if(s[4]>0.0){meter=gdiv(s[3],s[4]);}}
  if(method==7u){meter=average;if(s[6]>=1e-12){meter=gdiv(s[5],s[6]);}}
  if(method==6u){var value=0.0;var weight=0.0;for(var r=0u;r<3u;r=r+1u){if(s[9u+2u*r]>0.0){let w=select(select(0.2,0.3,r==1u),0.5,r==0u);value=gadd(value,gmul(w,gdiv(s[8u+2u*r],s[9u+2u*r])));weight=gadd(weight,w);}}meter=average;if(weight>0.0){meter=gdiv(value,weight);}}
  if(method==5u){let cell=(p.u[0].x/5u)*(p.u[0].y/5u);meter=0.0;if(cell>0u){var wsum=0.0;for(var k=0u;k<25u;k=k+1u){wsum=gadd(wsum,probe.values[32u+k]);}for(var k=0u;k<25u;k=k+1u){meter=gadd(meter,gdiv(gmul(gdiv(s[14u+k],f32(cell)),probe.values[32u+k]),wsum));}}}
  let exposure=gdiv(meter,0.184);var ev=0.0;if(exposure>0.0){ev=-glog2(exposure);}let scale=gexp2(ev);
  probe.values[0]=ev;probe.values[1]=scale;return;
 }
 if(op==4u){var index=0u;if(p.u[1].x>1u){index=i*(p.u[1].y-1u)/(p.u[1].x-1u);}probe.values[i]=source.values[index];return;}
 if(op==10u){write_plane(p.u[1].z,p.u[1].w+i,gbound(probe.values[524288u+i]));return;}
 if(op==11u){var peak=-bitcast<f32>(2139095039u);let end=min((i+1u)*1024u,n);for(var j=i*1024u;j<end;j=j+1u){for(var ch=0u;ch<3u;ch=ch+1u){peak=max(peak,gbound(read_plane(ch,j)));}}saved.values[i]=peak;return;}
 if(op==12u){
  var peak=-bitcast<f32>(2139095039u);for(var j=0u;j<p.u[1].y;j=j+1u){peak=max(peak,gbound(saved.values[j]));}
  for(var k=0u;k<8u;k=k+1u){probe.values[k]=0.0;}probe.values[0]=peak;
  let boost=gbound(bitcast<f32>(p.u[3].x));let range=gbound(bitcast<f32>(p.u[3].y));let protect=gbound(bitcast<f32>(p.u[3].z));
  if(boost==0.0){return;}if(peak<0.0){probe.values[0]=gfail();return;}
  let x0=clamp(gmul(0.184,gexp2(protect)),0.0,peak);if(x0==peak&&peak!=0.0){return;}
  let a=gpow(28.0,gsub(1.0,range));var xn=0.0;if(peak>0.0){xn=gdiv(x0,peak);}let t=gmul(a,gsub(1.0,xn));let denom=gsub(gsub(gexp(t),t),1.0);
  if(denom<=0.0){probe.values[0]=gfail();return;}
  let k=gdiv(gsub(gexp2(boost),1.0),denom);var inv=0.0;if(peak>0.0){inv=gdiv(1.0,peak);}let scale=gmul(k,peak);
  probe.values[1]=1.0;probe.values[2]=x0;probe.values[3]=a;probe.values[4]=inv;probe.values[5]=scale;return;
 }
 if(op==13u){for(var ch=0u;ch<3u;ch=ch+1u){write_plane(ch,i,boost_point(read_plane(ch,i)));}return;}
 if(op==14u){let factor=gbound(bitcast<f32>(p.u[3].x));for(var ch=0u;ch<3u;ch=ch+1u){let corrected=gmul(read_plane(ch,i),factor);let out=gmul(glog(gadd(max(corrected,0.0),1e-10)),0.4342944819032518);write_plane(ch,i,out);}return;}
 if(op==15u){probe.values[i]=read_plane(p.u[1].z,p.u[1].w+i);return;}
 if(op==16u){probe.values[8]=boost_point(probe.values[0]);return;}
}
