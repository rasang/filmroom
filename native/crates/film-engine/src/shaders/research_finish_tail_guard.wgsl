// Tail-only numeric admission. No NaN sentinel or post-overflow detection.
// Host validates all uploaded scalars as finite. Each guarded operation keeps
// subsequent operands within 1e15; raw add/multiply/divide remain <= 2e30.
// Rejected pixels become inert zeros only after the request-wide atomic flag.
struct TailNumericError { value: atomic<u32>, }
@group(0) @binding(7) var<storage, read_write> tail_numeric_error: TailNumericError;
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
