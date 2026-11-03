// Original FIR / complete-line Young-van Vliet IIR, experimental f32 only.
// No finite IIR halo, implicit downsampling, Gaussian substitute or fast-math hint.
struct Data { values: array<f32>, }
struct Params { u: array<vec4<u32>, 10>, }
@group(0) @binding(0) var<storage, read_write> source: Data;
@group(0) @binding(1) var<storage, read_write> horizontal: Data;
@group(0) @binding(2) var<storage, read_write> blurred: Data;
@group(0) @binding(3) var<storage, read_write> saved: Data;
@group(0) @binding(4) var<storage, read_write> accum: Data;
@group(0) @binding(5) var<storage, read_write> probe: Data;
@group(0) @binding(6) var<uniform> p: Params;
fn reflect_index(i:i32,n:u32)->u32 {
 if(n<=1u){return 0u;} let period=i32(2u*n); let folded=((i%period)+period)%period;
 if(folded>=i32(n)){return u32(period-1-folded);} return u32(folded);
}
fn get_value(which:u32,i:u32)->f32 {
 switch which {case 0u:{return source.values[i];}case 1u:{return horizontal.values[i];}case 2u:{return blurred.values[i];}case 3u:{return saved.values[i];}default:{return accum.values[i];}}
}
fn put_value(which:u32,i:u32,v:f32){
 switch which {case 0u:{source.values[i]=v;}case 1u:{horizontal.values[i]=v;}case 2u:{blurred.values[i]=v;}case 3u:{saved.values[i]=v;}default:{accum.values[i]=v;}}
}
// Preserve nonfinite failure through later clamping interpolation. The scalar
// curves cannot turn overflow in an unsampled DIR correction into a valid pixel.
fn finite_value(x:f32)->bool {return abs(x)<=bitcast<f32>(2139095039u);}
// DIR uses the same original nonuniform scaled axes and reciprocal spacing.
fn density_value(axis_offset:u32,inverse_offset:u32,curve_offset:u32,knots:u32,ch:u32,x:f32)->f32 {
 if(!finite_value(x)){return bitcast<f32>(2143289344u);}
 let last=knots-1u;
 if(x<=probe.values[axis_offset]){return probe.values[curve_offset+ch];}
 if(x>=probe.values[axis_offset+last]){return probe.values[curve_offset+3u*last+ch];}
 var lo=0u;var hi=last;
 loop{if(hi-lo<=1u){break;}let mid=(lo+hi)/2u;if(probe.values[axis_offset+mid]<=x){lo=mid;}else{hi=mid;}}
 let t=(x-probe.values[axis_offset+lo])*probe.values[inverse_offset+lo];
 let a=probe.values[curve_offset+3u*lo+ch];let b=probe.values[curve_offset+3u*(lo+1u)+ch];
 return a+t*(b-a);
}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>){
 let local=gid.x+gid.y*65535u*64u;
 if(local>=p.u[1].x){return;}let i=local+p.u[0].w;let w=p.u[0].x;let h=p.u[0].y;let op=p.u[0].z;
 if(op==0u || op==1u){
  let x=i%w;let y=i/w;var sum=0.0;let radius=p.u[1].y;
  for(var k=0u;k<2u*radius+1u;k=k+1u){
   var index=0u;if(op==0u){index=y*w+reflect_index(i32(x)+i32(k)-i32(radius),w);}else{index=reflect_index(i32(y)+i32(k)-i32(radius),h)*w+x;}
   let coefficient=bitcast<f32>(p.u[4u+k/4u][k%4u]);
   sum=sum+get_value(p.u[1].z,index)*coefficient;
  }put_value(p.u[1].w,i,sum);return;
 }
 if(op==2u || op==3u){
  var n=w;var stride=1u;var base=i*w;if(op==3u){n=h;stride=w;base=i;}
  let a=bitcast<f32>(p.u[2].x);let b1=bitcast<f32>(p.u[2].y);let b2=bitcast<f32>(p.u[2].z);let b3=bitcast<f32>(p.u[2].w);
  var s1=get_value(p.u[1].z,base);var s2=s1;var s3=s1;
  for(var j=0u;j<n;j=j+1u){let index=base+j*stride;let v=a*get_value(p.u[1].z,index)+b1*s1+b2*s2+b3*s3;put_value(p.u[1].w,index,v);s3=s2;s2=s1;s1=v;}
  s1=get_value(p.u[1].w,base+(n-1u)*stride);s2=s1;s3=s1;
  for(var k=0u;k<n;k=k+1u){let j=n-1u-k;let index=base+j*stride;let v=a*get_value(p.u[1].w,index)+b1*s1+b2*s2+b3*s3;put_value(p.u[1].w,index,v);s3=s2;s2=s1;s1=v;}return;
 }
 if(op==4u){put_value(p.u[1].w,i,get_value(p.u[1].z,i));return;}
 if(op==5u){accum.values[i]=0.0;return;}
 if(op==6u){accum.values[i]=accum.values[i]+bitcast<f32>(p.u[3].x)*blurred.values[i];return;}
 if(op==7u){let a=bitcast<f32>(p.u[3].x);let t=bitcast<f32>(p.u[3].y);source.values[i]=(1.0-a)*source.values[i]+a*((1.0-t)*saved.values[i]+t*accum.values[i]);return;}
 if(op==8u){source.values[i]=source.values[i]+bitcast<f32>(p.u[3].z)*accum.values[i];return;}
 if(op==9u){source.values[i]=source.values[i]/bitcast<f32>(p.u[3].w);return;}
 if(op==10u){let index=p.u[9].x*w+i;probe.values[i]=source.values[index];probe.values[w+i]=horizontal.values[index];return;}
 if(op==11u){let index=i*w+p.u[9].x;probe.values[i]=horizontal.values[index];probe.values[h+i]=blurred.values[index];return;}
 if(op==12u){
  var d:array<f32,3>;
  for(var ch=0u;ch<3u;ch=ch+1u){d[ch]=density_value(p.u[2].y,p.u[2].z,p.u[2].w,p.u[2].x,ch,probe.values[524288u+3u*i+ch]);}
  var value=d[p.u[1].z];
  if(p.u[5].z==1u){
   if(p.u[3].w==1u){d[0]=bitcast<f32>(p.u[3].x)-d[0];d[1]=bitcast<f32>(p.u[3].y)-d[1];d[2]=bitcast<f32>(p.u[3].z)-d[2];}
   value=d[0]*bitcast<f32>(p.u[4].x)+d[1]*bitcast<f32>(p.u[4].y)+d[2]*bitcast<f32>(p.u[4].z);
  }source.values[p.u[1].w+i]=value;return;
 }
 if(op==13u){let t=bitcast<f32>(p.u[3].y);source.values[i]=(1.0-t)*saved.values[i]+t*accum.values[i];return;}
 if(op==14u){let index=p.u[1].w+i;let corrected=probe.values[524288u+i]-source.values[index];source.values[index]=density_value(p.u[5].x,p.u[5].y,p.u[5].w,p.u[6].x,p.u[1].z,corrected);return;}
 if(op==15u){var index=0u;if(p.u[1].x>1u){index=i*(p.u[1].y-1u)/(p.u[1].x-1u);}probe.values[i]=source.values[p.u[1].w+index];return;}

}
