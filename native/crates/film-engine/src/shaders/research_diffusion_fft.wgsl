// Filmroom isolated research, GPL-3.0. Fresh implementation from the DFT
// radix-2 identities; no third-party shader copied. f32, NOT exact f64 output.
struct Params {
 n:u32, width:u32, height:u32, ks:u32,
 radius:u32, origin_x:u32, origin_y:u32, op:u32,
 span:u32, inverse:u32, input_buffer:u32, output_buffer:u32,
 out_width:u32, out_height:u32, twiddle_offset:u32, scatter:f32,
}
@group(0) @binding(0) var<storage,read_write> a:array<vec2<f32>>;
@group(0) @binding(1) var<storage,read_write> b:array<vec2<f32>>;
@group(0) @binding(2) var<storage,read_write> spectrum:array<vec2<f32>>;
@group(0) @binding(3) var<storage,read> data:array<f32>;
@group(0) @binding(4) var<uniform> p:Params;
fn read_complex(i:u32)->vec2<f32>{if(p.input_buffer==0u){return a[i];}return b[i];}
fn write_complex(i:u32,v:vec2<f32>){if(p.output_buffer==0u){a[i]=v;}else{b[i]=v;}}
fn reflect_index(v:i32,n:u32)->u32 {
 if(n<=1u){return 0u;} let period=i32(2u*(n-1u));
 let folded=((v%period)+period)%period;
 if(folded<i32(n)){return u32(folded);}return u32(period-folded);
}
fn cmul(x:vec2<f32>,y:vec2<f32>)->vec2<f32>{return vec2<f32>(x.x*y.x-x.y*y.y,x.x*y.y+x.y*y.x);}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>){
 let i=gid.x+gid.y*65535u*64u;
 if(p.op==10u){
  if(i>=p.out_width*p.out_height){return;}
  let original=data[p.twiddle_offset+2u*p.n+i];
  let blurred=read_complex(p.span+i).x;
  write_complex(p.span+i,vec2<f32>((1.0-p.scatter)*original+p.scatter*blurred,0.0));return;
 }
 if(p.op==8u){
  if(i>=p.out_width*p.out_height){return;}let x=i%p.out_width;let y=i/p.out_width;
  let v=read_complex((y+2u*p.radius)*p.n+x+2u*p.radius).x/f32(p.n*p.n);
  write_complex(i,vec2<f32>(v,0.0));return;
 }
 if(p.op==7u){
  if(i>=p.out_width*p.out_height){return;}
  let x=i%p.out_width; let y=i/p.out_width;
  let original=data[(p.origin_y+y)*p.width+p.origin_x+x];
  let value=read_complex((y+2u*p.radius)*p.n+x+2u*p.radius).x/f32(p.n*p.n);
  write_complex(i,vec2<f32>((1.0-p.scatter)*original+p.scatter*value,0.0));return;
 }
 if(i>=p.n*p.n){return;}let x=i%p.n;let y=i/p.n;
 if(p.op==0u){
  let sx=reflect_index(i32(p.origin_x+x)-i32(p.radius),p.width);
  let sy=reflect_index(i32(p.origin_y+y)-i32(p.radius),p.height);
  write_complex(i,vec2<f32>(data[sy*p.width+sx],0.0));return;
 }
 if(p.op==9u){var v=0.0;if(x<p.ks&&y<p.ks){v=data[y*p.ks+x];}write_complex(i,vec2<f32>(v,0.0));return;}
 if(p.op==1u){var v=0.0;if(x<p.ks&&y<p.ks){v=data[p.width*p.height+y*p.ks+x];}write_complex(i,vec2<f32>(v,0.0));return;}
 if(p.op==2u){var v=x;var reversed=0u;var k=p.n;loop{if(k<=1u){break;}reversed=(reversed<<1u)|(v&1u);v=v>>1u;k=k>>1u;}write_complex(i,read_complex(y*p.n+reversed));return;}
 if(p.op==3u){
  let half_span=p.span/2u;let j=x%half_span;let base=y*p.n+(x/p.span)*p.span;
  let offset=p.twiddle_offset+2u*(j*p.n/p.span);
  var tw=vec2<f32>(data[offset],data[offset+1u]);if(p.inverse!=0u){tw.y=-tw.y;}
  let even=read_complex(base+j);let odd=cmul(read_complex(base+j+half_span),tw);
  if(x%p.span<half_span){write_complex(i,even+odd);}else{write_complex(i,even-odd);}return;
 }
 if(p.op==4u){write_complex(i,read_complex(x*p.n+y));return;}
 if(p.op==5u){spectrum[i]=read_complex(i);return;}
 if(p.op==6u){write_complex(i,cmul(read_complex(i),spectrum[i]));return;}
}
