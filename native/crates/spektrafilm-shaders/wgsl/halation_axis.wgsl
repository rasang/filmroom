// Packed single-channel physical PSF. Caller owns global halo coordinates.
struct Params { width:u32, height:u32, radius:u32, horizontal:u32,
    reflect_edges:u32, pad0:u32, pad1:u32, pad2:u32, }
@group(0) @binding(0) var<uniform> p: Params;
@group(0) @binding(1) var<storage, read> src: array<f32>;
@group(0) @binding(2) var<storage, read> weights: array<f32>;
@group(0) @binding(3) var<storage, read_write> dst: array<f32>;
fn boundary(i:i32, n:i32)->u32 {
    if p.reflect_edges == 0u { return u32(clamp(i,0i,n-1i)); }
    var folded=i % (2i*n);
    if folded<0i { folded += 2i*n; }
    if folded>=n { folded=2i*n-1i-folded; }
    return u32(folded);
}
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid:vec3<u32>) {
    if gid.x>=p.width*p.height {return;}
    let x=gid.x%p.width; let y=gid.x/p.width;
    var sum=0.0;
    for(var k=0u;k<2u*p.radius+1u;k++) {
        let d=i32(k)-i32(p.radius);
        var index=boundary(i32(y)+d,i32(p.height))*p.width+x;
        if p.horizontal!=0u { index=y*p.width+boundary(i32(x)+d,i32(p.width)); }
        sum += weights[k]*src[index];
    }
    dst[gid.x]=sum;
}
