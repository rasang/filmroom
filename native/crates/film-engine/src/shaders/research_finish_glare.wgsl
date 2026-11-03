// Original rand 0.9 ChaCha12 / rand_distr 0.5.1 ZIGNOR stream.
// u64 words and ALL rejection predicates use u32 integer arithmetic. Slow
// predicates whose Q48 error interval straddles the comparison fail closed.
const GBASE:u32=327680u;
const GONE:vec2<u32> = vec2<u32>(0u,65536u);
const GGUARD:vec2<u32> = vec2<u32>(65536u,0u);
fn gload32(offset:u32)->u32 {return u32(probe.values[GBASE+offset])|(u32(probe.values[GBASE+offset+1u])<<16u);}
fn gqload(offset:u32)->vec2<u32>{return vec2<u32>(gload32(offset),gload32(offset+2u));}
fn gcmp(a:vec2<u32>,b:vec2<u32>)->i32 {if(a.y<b.y){return -1;}if(a.y>b.y){return 1;}if(a.x<b.x){return -1;}if(a.x>b.x){return 1;}return 0;}
fn gadd(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{let lo=a.x+b.x;return vec2<u32>(lo,a.y+b.y+select(0u,1u,lo<a.x));}
fn gsub(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{return vec2<u32>(a.x-b.x,a.y-b.y-select(0u,1u,a.x<b.x));}
fn gshr(a:vec2<u32>,n:u32)->vec2<u32>{if(n==0u){return a;}if(n<32u){return vec2<u32>((a.x>>n)|(a.y<<(32u-n)),a.y>>n);}return vec2<u32>(a.y>>(n-32u),0u);}
fn gshl(a:vec2<u32>,n:u32)->vec2<u32>{if(n==0u){return a;}if(n<32u){return vec2<u32>(a.x<<n,(a.y<<n)|(a.x>>(32u-n)));}return vec2<u32>(0u,a.x<<(n-32u));}
fn gdiff(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{if(gcmp(a,b)>=0){return gsub(a,b);}return gsub(b,a);}
fn gmul(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{
 var ad=array<u32,4>(a.x&65535u,a.x>>16u,a.y&65535u,a.y>>16u);var bd=array<u32,4>(b.x&65535u,b.x>>16u,b.y&65535u,b.y>>16u);var result:array<u32,8>;
 for(var i=0u;i<4u;i=i+1u){var carry=0u;for(var j=0u;j<4u;j=j+1u){let value=ad[i]*bd[j]+result[i+j]+carry;result[i+j]=value&65535u;carry=value>>16u;}result[i+4u]=carry;}
 return vec2<u32>(result[3]|(result[4]<<16u),result[5]|(result[6]<<16u));
}
fn gdivsmall(a:vec2<u32>,b:u32)->vec2<u32>{
 var digits=array<u32,4>(a.x&65535u,a.x>>16u,a.y&65535u,a.y>>16u);var out:array<u32,4>;var rem=0u;
 for(var i=0u;i<4u;i=i+1u){let j=3u-i;let value=(rem<<16u)|digits[j];out[j]=value/b;rem=value%b;}return vec2<u32>(out[0]|(out[1]<<16u),out[2]|(out[3]<<16u));
}
fn gratio(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{var rem=a;var result=vec2<u32>(0u);for(var i=0u;i<48u;i=i+1u){rem=gshl(rem,1u);result=gshl(result,1u);if(gcmp(rem,b)>=0){rem=gsub(rem,b);result.x=result.x|1u;}}return result;}
fn gscalar(a:vec2<u32>)->f32{return f32(a.y)*(1.0/65536.0)+f32(a.x)*(1.0/281474976710656.0);}
fn gexpnegative(x:vec2<u32>)->vec2<u32>{let z=gshr(x,8u);var sum=GONE;var term=GONE;for(var k=1u;k<=12u;k=k+1u){term=gdivsmall(gmul(term,z),k);if(k%2u==0u){sum=gadd(sum,term);}else{sum=gsub(sum,term);}}for(var k=0u;k<8u;k=k+1u){sum=gmul(sum,sum);}return sum;}
fn gneglog(bits:vec2<u32>)->vec2<u32>{
 var n=gshl(gshr(bits,12u),1u);n.x=n.x|1u;var b=0u;if(n.y!=0u){b=63u-countLeadingZeros(n.y);}else{b=31u-countLeadingZeros(n.x);}
 var m=n;if(b>48u){m=gshr(n,b-48u);}else{m=gshl(n,48u-b);}let z=gratio(gsub(m,GONE),gadd(m,GONE));let z2=gmul(z,z);var term=z;var sum=z;
 for(var k=3u;k<=63u;k=k+2u){term=gmul(term,z2);sum=gadd(sum,gdivsmall(term,k));}
 let ln2=gqload(3112u);var total=vec2<u32>(0u);for(var i=0u;i<53u-b;i=i+1u){total=gadd(total,ln2);}return gsub(total,gshl(sum,1u));
}
fn grot(x:u32,n:u32)->u32{return (x<<n)|(x>>(32u-n));}
fn gquarter(s:ptr<function,array<u32,16>>,a:u32,b:u32,c:u32,d:u32){
 (*s)[a]=(*s)[a]+(*s)[b];(*s)[d]=grot((*s)[d]^(*s)[a],16u);(*s)[c]=(*s)[c]+(*s)[d];(*s)[b]=grot((*s)[b]^(*s)[c],12u);
 (*s)[a]=(*s)[a]+(*s)[b];(*s)[d]=grot((*s)[d]^(*s)[a],8u);(*s)[c]=(*s)[c]+(*s)[d];(*s)[b]=grot((*s)[b]^(*s)[c],7u);
}
fn gwords(i:u32){
 let start=p.u[1].y;let count=p.u[2].x;let block=start/8u+i;
 var initial=array<u32,16>(0x61707865u,0x3320646eu,0x79622d32u,0x6b206574u,gload32(0u),gload32(2u),gload32(4u),gload32(6u),gload32(8u),gload32(10u),gload32(12u),gload32(14u),block,0u,0u,0u);var s=initial;
 for(var k=0u;k<6u;k=k+1u){gquarter(&s,0u,4u,8u,12u);gquarter(&s,1u,5u,9u,13u);gquarter(&s,2u,6u,10u,14u);gquarter(&s,3u,7u,11u,15u);gquarter(&s,0u,5u,10u,15u);gquarter(&s,1u,6u,11u,12u);gquarter(&s,2u,7u,8u,13u);gquarter(&s,3u,4u,9u,14u);}
 for(var k=0u;k<8u;k=k+1u){let index=block*8u+k;if(index>=start&&index-start<count){let j=(index-start)*4u;let lo=s[2u*k]+initial[2u*k];let hi=s[2u*k+1u]+initial[2u*k+1u];probe.values[j]=f32(lo&65535u);probe.values[j+1u]=f32(lo>>16u);probe.values[j+2u]=f32(hi&65535u);probe.values[j+3u]=f32(hi>>16u);}}
}
fn gword(i:u32)->vec2<u32>{let j=4u*i;return vec2<u32>(u32(probe.values[j])|(u32(probe.values[j+1u])<<16u),u32(probe.values[j+2u])|(u32(probe.values[j+3u])<<16u));}
// status 0: proven candidate; 1: next window; 2: ambiguous; 3: loop bound.
fn gcandidate(start:u32)->vec3<f32>{
 let end=p.u[2].x;var cursor=start;
 for(var attempt=0u;attempt<16u;attempt=attempt+1u){if(cursor>=end){return vec3<f32>(0.0,0.0,1.0);}let bits=gword(cursor);cursor=cursor+1u;let index=bits.x&255u;let mant=gshr(bits,12u);let center=vec2<u32>(0u,524288u);let negative=gcmp(mant,center)<0;let numerator=gdiff(mant,center);
  let x=gshr(gmul(numerator,gqload(32u+4u*index)),3u);let threshold=gqload(2088u+4u*index);
  if(gcmp(numerator,threshold)<0){return vec3<f32>(select(gscalar(x),-gscalar(x),negative),f32(cursor-start),0.0);}
  if(index==0u){for(var retry=0u;retry<32u;retry=retry+1u){if(cursor+1u>=end){return vec3<f32>(0.0,0.0,1.0);}let nx=gmul(gneglog(gword(cursor)),gqload(3116u));let ny=gneglog(gword(cursor+1u));cursor=cursor+2u;let lhs=gshl(ny,1u);let rhs=gmul(nx,nx);
    if(gcmp(gdiff(lhs,rhs),GGUARD)<=0){return vec3<f32>(0.0,0.0,2.0);}if(gcmp(lhs,rhs)>=0){let value=gscalar(gadd(nx,gqload(3120u)));return vec3<f32>(select(value,-value,negative),f32(cursor-start),0.0);}}
   return vec3<f32>(0.0,0.0,3.0);
  }
  if(cursor>=end){return vec3<f32>(0.0,0.0,1.0);}let uniform=gshr(gword(cursor),16u);cursor=cursor+1u;let f0=gqload(1060u+4u*index);let f1=gqload(1060u+4u*(index+1u));let left=gsub(f1,gmul(gsub(f1,f0),uniform));let right=gexpnegative(gshr(gmul(x,x),1u));
  if(gcmp(gdiff(left,right),GGUARD)<=0){return vec3<f32>(0.0,0.0,2.0);}if(gcmp(left,right)<0){return vec3<f32>(select(gscalar(x),-gscalar(x),negative),f32(cursor-start),0.0);}
 }return vec3<f32>(0.0,0.0,3.0);
}
fn gselect(){
 var cursor=0u;var produced=0u;var status=0u;let count=p.u[2].x;let requested=p.u[2].y;let mu=probe.values[GBASE+16u];let sigma=probe.values[GBASE+17u];
 for(var n=0u;n<16384u;n=n+1u){if(produced>=requested||cursor>=count){break;}let at=524288u+cursor*3u;let code=u32(probe.values[at+2u]);if(code==1u){break;}if(code!=0u){status=code;break;}
  let value=texp(tadd(mu,tmul(sigma,probe.values[at])));if(atomicLoad(&tail_numeric_error.value)!=0u){status=4u;break;}source.values[p.u[1].w+produced]=value;let used=u32(probe.values[at+1u]);if(used==0u||used>95u||cursor+used>count){status=5u;break;}cursor=cursor+used;produced=produced+1u;
 }
 probe.values[0]=f32(produced);probe.values[1]=f32(cursor);probe.values[2]=f32(status);
}
fn glare_main(i:u32){if(p.u[0].z==30u){gwords(i);return;}if(p.u[0].z==31u){let v=gcandidate(i);probe.values[524288u+3u*i]=v.x;probe.values[524289u+3u*i]=v.y;probe.values[524290u+3u*i]=v.z;return;}if(p.u[0].z==32u&&i==0u){gselect();}}
