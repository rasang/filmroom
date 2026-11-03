// Export-only original two-step matrices and Mitchell-Netravali 2D TC LUT.
// One input RGB chunk, resident full LUT/parameters, one film-raw RGB output.
struct Params { pixels:u32, side:u32, pad0:u32, pad1:u32, ae:f32, exposure:f32, pad2:f32, pad3:f32,
 rgb0:vec4<f32>,rgb1:vec4<f32>,rgb2:vec4<f32>,cat0:vec4<f32>,cat1:vec4<f32>,cat2:vec4<f32> }
@group(0) @binding(0) var<uniform> p:Params;
@group(0) @binding(1) var<storage,read> input:array<f32>;
@group(0) @binding(2) var<storage,read> table:array<f32>;
@group(0) @binding(3) var<storage,read_write> output:array<f32>;
fn weight(t:f32)->f32{
 let b=1.0/3.0;let c=1.0/3.0;let x=abs(t);
 if x<1.0{return (1.0/6.0)*((12.0-9.0*b-6.0*c)*x*x*x+(-18.0+12.0*b+6.0*c)*x*x+(6.0-2.0*b));}
 if x<2.0{return (1.0/6.0)*((-b-6.0*c)*x*x*x+(6.0*b+30.0*c)*x*x+(-12.0*b-48.0*c)*x+(8.0*b+24.0*c));}
 return 0.0;
}
fn reflected(v:i32)->u32{if v<0{return u32(-v);}if v>=i32(p.side){return u32(2*(i32(p.side)-1)-v);}return u32(v);}
fn row(r:vec4<f32>,v:vec3<f32>)->f32{return tdot(r.xyz,v);}
fn scale(v:vec3<f32>,s:f32)->vec3<f32>{return vec3<f32>(tmul(v.x,s),tmul(v.y,s),tmul(v.z,s));}
fn plus(a:vec3<f32>,b:vec3<f32>)->vec3<f32>{return vec3<f32>(tadd(a.x,b.x),tadd(a.y,b.y),tadd(a.z,b.z));}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>){
 let i=gid.x;if i>=p.pixels{return;}let at=3u*i;
 let ae_rgb=scale(vec3<f32>(input[at],input[at+1u],input[at+2u]),p.ae);
 let rgb=scale(ae_rgb,p.exposure);
 let native=vec3<f32>(row(p.rgb0,rgb),row(p.rgb1,rgb),row(p.rgb2,rgb));
 let xyz=vec3<f32>(row(p.cat0,native),row(p.cat1,native),row(p.cat2,native));
 let brightness=tadd(tadd(xyz.x,xyz.y),xyz.z);
 if brightness<=1e-10{output[at]=0.0;output[at+1u]=0.0;output[at+2u]=0.0;return;}
 let x=tdiv(xyz.x,brightness);let y=tdiv(xyz.y,brightness);
 let raw_tx=tmul(tsub(1.0,x),tsub(1.0,x));let raw_ty=tdiv(y,max(tsub(1.0,x),1e-10));
 let tx=clamp(raw_tx,0.0,1.0);let ty=clamp(raw_ty,0.0,1.0);
 let maximum=f32(p.side-1u);let xf=clamp(tx*maximum,0.0,maximum);let yf=clamp(ty*maximum,0.0,maximum);
 var xi=u32(xf);var yi=u32(yf);if xf>=maximum{xi=p.side-2u;}if yf>=maximum{yi=p.side-2u;}
 let fx=xf-f32(xi);let fy=yf-f32(yi);
 let wx=array<f32,4>(weight(fx+1.0),weight(fx),weight(fx-1.0),weight(fx-2.0));
 let wy=array<f32,4>(weight(fy+1.0),weight(fy),weight(fy-1.0),weight(fy-2.0));
 var result=vec3<f32>(0.0);var total=0.0;
 for(var a=0u;a<4u;a++){for(var b=0u;b<4u;b++){
  let w=wx[a]*wy[b];total+=w;
  let offset=(reflected(i32(xi)+i32(a)-1)*p.side+reflected(i32(yi)+i32(b)-1))*3u;
  result=plus(result,scale(vec3<f32>(table[offset],table[offset+1u],table[offset+2u]),w));
  }}
 if total!=0.0{result=vec3<f32>(tdiv(result.x,total),tdiv(result.y,total),tdiv(result.z,total));}result=scale(result,brightness);
 output[at]=result.x;output[at+1u]=result.y;output[at+2u]=result.z;
}

// Exposure numeric admission. No NaN sentinel or post-overflow detection.
// Host validates all uploaded scalars as finite. Each guarded operation keeps
// subsequent operands within 1e15; raw add/multiply/divide remain <= 2e30.
// Rejected pixels become inert zeros only after the request-wide atomic flag.
struct TailNumericError { value: atomic<u32>, }
@group(0) @binding(4) var<storage, read_write> tail_numeric_error: TailNumericError;
const T_BOUND:f32=1e15;
fn tfail()->f32 {atomicOr(&tail_numeric_error.value,1u);return 0.0;}
fn tbound(v:f32)->f32 {if(abs(v)>T_BOUND){return tfail();}return v;}
fn tb3(v:vec3<f32>)->vec3<f32> {return vec3<f32>(tbound(v.x),tbound(v.y),tbound(v.z));}
fn tzero()->vec3<f32> {return vec3<f32>(tfail());}
fn tadd(a:f32,b:f32)->f32 {return tbound(tbound(a)+tbound(b));}
fn tsub(a:f32,b:f32)->f32 {return tbound(tbound(a)-tbound(b));}
fn tmul(a:f32,b:f32)->f32 {return tbound(tbound(a)*tbound(b));}
fn tdiv(a:f32,b:f32)->f32 {let x=tbound(a);let y=tbound(b);if(abs(y)<1e-15){return tfail();}return tbound(x/y);}
fn texp(x:f32)->f32 {if(x< -60.0||x>34.0){return tfail();}return tbound(exp(x));}
fn tlog(x:f32)->f32 {if(x<1e-30||x>T_BOUND){return tfail();}return log(x);}
fn tpow(x:f32,y:f32)->f32 {
 let base=tbound(x);let power=tbound(y);
 if(base<0.0){return tfail();}
 if(base==0.0){if(power>0.0){return 0.0;}return tfail();}
 if(base<1e-30){return tfail();}
 let exponent=log(base)*power;
 if(exponent< -60.0||exponent>34.0){return tfail();}
 return tbound(pow(base,power));
}
fn tsqrt(x:f32)->f32 {if(x<0.0||x>T_BOUND){return tfail();}return sqrt(x);}
fn tpow3(x:vec3<f32>,p:f32)->vec3<f32> {return vec3<f32>(tpow(x.x,p),tpow(x.y,p),tpow(x.z,p));}
fn tdot(a:vec3<f32>,b:vec3<f32>)->f32 {return tadd(tadd(tmul(a.x,b.x),tmul(a.y,b.y)),tmul(a.z,b.z));}
