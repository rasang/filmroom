// All uploaded operands are host-finite. The bound is deliberately narrower
// than f32: guarded raw add/multiply/divide stay <=2e30, never overflow first.
// Unsupported range/domain latches one request-wide integer error before any
// unsafe operation. Inert zero results must never be adopted after that flag.
struct GlobalNumericError { value: atomic<u32>, }
@group(0) @binding(7) var<storage, read_write> global_numeric_error: GlobalNumericError;
const G_BOUND:f32=1e15;
fn gfail()->f32 {atomicOr(&global_numeric_error.value,1u);return 0.0;}
fn gbound(v:f32)->f32 {if(abs(v)>G_BOUND){return gfail();}return v;}
fn gadd(a:f32,b:f32)->f32 {return gbound(gbound(a)+gbound(b));}
fn gsub(a:f32,b:f32)->f32 {return gbound(gbound(a)-gbound(b));}
fn gmul(a:f32,b:f32)->f32 {return gbound(gbound(a)*gbound(b));}
fn gdiv(a:f32,b:f32)->f32 {let x=gbound(a);let y=gbound(b);if(abs(y)<1e-15){return gfail();}return gbound(x/y);}
fn gexp(x:f32)->f32 {if(x< -60.0||x>34.0){return gfail();}return gbound(exp(x));}
fn gexp2(x:f32)->f32 {if(x< -80.0||x>49.0){return gfail();}return gbound(exp2(x));}
fn glog(x:f32)->f32 {if(x<1e-30||x>G_BOUND){return gfail();}return log(x);}
fn glog2(x:f32)->f32 {if(x<1e-30||x>G_BOUND){return gfail();}return log2(x);}
fn gpow(x:f32,y:f32)->f32 {
 let base=gbound(x);let power=gbound(y);
 if(base<1e-30){return gfail();}
 // The admission exponent is <=70*1e15 and therefore finite. Do not
 // replace it with an inert zero before checking the domain of pow below.
 let exponent=glog(base)*power;
 if(exponent< -60.0||exponent>34.0){return gfail();}
 return gbound(pow(base,power));
}
