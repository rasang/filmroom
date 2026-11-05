// Addressing-only adapter. Original RNG/distribution/ALU bodies are unchanged.
// p[7] is always a local plane offset; p[32],p[33] are global base x,y.
// The original body remains available for unchanged synthetic admission cases.
fn gg_rng(index:u32)->SoftRng {
 if(p.u[8].z==0u){return gg_rng_resident_reference(index);}
 let w=p.u[0].x;let h=p.u[0].y;let bx=p.u[8].x;let by=p.u[8].y;
 if(p.u[8].z!=0x47530001u||w==0u||h==0u||bx>=w||by>=h||p.u[1].w!=0u||p.u[0].w!=0u||p.u[2].w!=0u||p.u[1].y==0u||p.u[1].y>16384u||index>=p.u[1].y){
  gg_fail();return SoftRng(vec2<u32>(0u),0u,0u,1u,0u);
 }
 // Avoid bx+index overflow even when width approaches u32::MAX.
 let dx=index%w;let dy=index/w;let carry=select(0u,1u,dx>=w-bx);
 var x=dx-(w-bx);if(carry==0u){x=bx+dx;}
 if(dy>=h-by||carry>=h-by-dy){gg_fail();return SoftRng(vec2<u32>(0u),0u,0u,1u,0u);}
 let y=by+dy+carry;
 let old=grain_rng(p.u[2].xy,p.u[7].x+x,p.u[7].y+y);
 return SoftRng(old.state,0u,0u,0u,0u);
}
