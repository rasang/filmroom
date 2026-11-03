// Software IEEE binary64 for stochastic branch predicates. Integer arithmetic
// only: no shader-f64 extension, float2 reassociation, or f32 transcendentals.
// Finite normal binary64 and zeros are supported. Unsupported exponents become
// an integer-tagged invalid value; every caller must check sf_failed before output.
const SF_NAN:vec2<u32>=vec2<u32>(0u,0x7ff80000u);
var<private> sf_failed:u32;
fn sf_fail()->vec2<u32>{sf_failed=1u;return SF_NAN;}
const SF_ONE:vec2<u32>=vec2<u32>(0u,0x3ff00000u);
const SF_TWO:vec2<u32>=vec2<u32>(0u,0x40000000u);
const SF_LN2:vec2<u32>=vec2<u32>(0xfefa39efu,0x3fe62e42u);
fn sf_cmpu(a:vec2<u32>,b:vec2<u32>)->i32{if(a.y<b.y){return -1;}if(a.y>b.y){return 1;}if(a.x<b.x){return -1;}if(a.x>b.x){return 1;}return 0;}
fn sf_addu(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{let lo=a.x+b.x;return vec2<u32>(lo,a.y+b.y+select(0u,1u,lo<a.x));}
fn sf_subu(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{return vec2<u32>(a.x-b.x,a.y-b.y-select(0u,1u,a.x<b.x));}
fn sf_shr(a:vec2<u32>,n:u32)->vec2<u32>{if(n==0u){return a;}if(n>=64u){return vec2<u32>(0u);}if(n>=32u){return vec2<u32>(a.y>>(n-32u),0u);}return vec2<u32>((a.x>>n)|(a.y<<(32u-n)),a.y>>n);}
fn sf_shl(a:vec2<u32>,n:u32)->vec2<u32>{if(n==0u){return a;}if(n>=64u){return vec2<u32>(0u);}if(n>=32u){return vec2<u32>(0u,a.x<<(n-32u));}return vec2<u32>(a.x<<n,(a.y<<n)|(a.x>>(32u-n)));}
fn sf_jam(a:vec2<u32>,n:u32)->vec2<u32>{let out=sf_shr(a,n);let back=sf_shl(out,n);return vec2<u32>(out.x|select(0u,1u,any(a!=back)),out.y);}
fn sf_exp(x:vec2<u32>)->i32{return i32((x.y>>20u)&2047u);}
fn sf_valid(x:vec2<u32>)->bool{let e=sf_exp(x);return e!=2047&&(e!=0||(x.x==0u&&(x.y&0xfffffu)==0u));}
fn sf_zero(x:vec2<u32>)->bool{return x.x==0u&&(x.y&0x7fffffffu)==0u;}
fn sf_sig(x:vec2<u32>)->vec2<u32>{if(sf_exp(x)==0){return vec2<u32>(0u);}return vec2<u32>(x.x,(x.y&0xfffffu)|0x100000u);}
fn sf_neg(x:vec2<u32>)->vec2<u32>{return vec2<u32>(x.x,x.y^0x80000000u);}
fn sf_abs(x:vec2<u32>)->vec2<u32>{return vec2<u32>(x.x,x.y&0x7fffffffu);}
fn sf_round(sign:u32,input_e:i32,input_s:vec2<u32>)->vec2<u32>{
 var e=input_e;var s=input_s;if(all(s==vec2<u32>(0u))){return vec2<u32>(0u);}
 if((s.y&0x1000000u)!=0u){s=sf_jam(s,1u);e=e+1;}
 loop{if((s.y&0x800000u)!=0u){break;}s=sf_shl(s,1u);e=e-1;}
 let residue=s.x&7u;var m=sf_shr(s,3u);
 if(residue>4u||(residue==4u&&(m.x&1u)!=0u)){m=sf_addu(m,vec2<u32>(1u,0u));}
 if((m.y&0x200000u)!=0u){m=sf_shr(m,1u);e=e+1;}
 if(e<=0||e>=2047){return sf_fail();}return vec2<u32>(m.x,(sign<<31u)|(u32(e)<<20u)|(m.y&0xfffffu));
}
fn sf_add(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{
 if(!sf_valid(a)||!sf_valid(b)){return sf_fail();}if(sf_zero(a)){return b;}if(sf_zero(b)){return a;}
 var ea=sf_exp(a);var eb=sf_exp(b);var ma=sf_sig(a);var mb=sf_sig(b);var sa=a.y>>31u;var sb=b.y>>31u;
 if(ea<eb||(ea==eb&&sf_cmpu(ma,mb)<0)){let te=ea;ea=eb;eb=te;let tm=ma;ma=mb;mb=tm;let ts=sa;sa=sb;sb=ts;}
 let x=sf_shl(ma,3u);let y=sf_jam(sf_shl(mb,3u),u32(ea-eb));var sum=sf_subu(x,y);if(sa==sb){sum=sf_addu(x,y);}return sf_round(sa,ea,sum);
}
fn sf_sub(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{return sf_add(a,sf_neg(b));}
fn sf_mul(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{
 if(!sf_valid(a)||!sf_valid(b)){return sf_fail();}if(sf_zero(a)||sf_zero(b)){return vec2<u32>(0u);}
 let ma=sf_sig(a);let mb=sf_sig(b);
 let ad=array<u32,4>(ma.x&65535u,ma.x>>16u,ma.y&65535u,ma.y>>16u);let bd=array<u32,4>(mb.x&65535u,mb.x>>16u,mb.y&65535u,mb.y>>16u);var product:array<u32,8>;
 for(var i=0u;i<4u;i=i+1u){var carry=0u;for(var j=0u;j<4u;j=j+1u){let v=ad[i]*bd[j]+product[i+j]+carry;product[i+j]=v&65535u;carry=v>>16u;}product[i+4u]=carry;}
 let words=array<u32,4>(product[0]|(product[1]<<16u),product[2]|(product[3]<<16u),product[4]|(product[5]<<16u),product[6]|(product[7]<<16u));
 let high=(words[3]&512u)!=0u;let shift=select(17u,18u,high);let residue=words[0]!=0u||(words[1]&((1u<<shift)-1u))!=0u;
 let s=vec2<u32>((words[1]>>shift)|(words[2]<<(32u-shift))|select(0u,1u,residue),(words[2]>>shift)|(words[3]<<(32u-shift)));
 return sf_round((a.y^b.y)>>31u,sf_exp(a)+sf_exp(b)-1023+select(0,1,high),s);
}
fn sf_div(a:vec2<u32>,b:vec2<u32>)->vec2<u32>{
 if(!sf_valid(a)||!sf_valid(b)||sf_zero(b)){return sf_fail();}if(sf_zero(a)){return vec2<u32>(0u);}
 var ma=sf_sig(a);let mb=sf_sig(b);var e=sf_exp(a)-sf_exp(b)+1023;
 if(sf_cmpu(ma,mb)<0){ma=sf_shl(ma,1u);e=e-1;}
 var rem=sf_subu(ma,mb);var q=vec2<u32>(1u,0u);
 for(var i=0u;i<55u;i=i+1u){rem=sf_shl(rem,1u);q=sf_shl(q,1u);if(sf_cmpu(rem,mb)>=0){rem=sf_subu(rem,mb);q.x=q.x|1u;}}
 if(any(rem!=vec2<u32>(0u))){q.x=q.x|1u;}return sf_round((a.y^b.y)>>31u,e,q);
}
fn sf_sqrt(a:vec2<u32>)->vec2<u32>{
 if(!sf_valid(a)){return sf_fail();}if(sf_zero(a)){return vec2<u32>(0u);}if((a.y>>31u)!=0u){return sf_fail();}
 var exponent=sf_exp(a)-1023;var m=sf_sig(a);if((exponent&1)!=0){m=sf_shl(m,1u);exponent=exponent-1;}
 var root=vec2<u32>(0u);var rem=vec2<u32>(0u);
 for(var j=0u;j<56u;j=j+1u){let i=55u-j;var bits=0u;if(i>=29u){bits=sf_shr(m,2u*i-58u).x&3u;}rem=sf_shl(rem,2u);rem.x=rem.x|bits;var trial=sf_shl(root,2u);trial.x=trial.x|1u;root=sf_shl(root,1u);if(sf_cmpu(rem,trial)>=0){rem=sf_subu(rem,trial);root.x=root.x|1u;}}
 if(any(rem!=vec2<u32>(0u))){root.x=root.x|1u;}return sf_round(0u,exponent/2+1023,root);
}
fn sf_cmp(a:vec2<u32>,b:vec2<u32>)->i32{
 if(!sf_valid(a)||!sf_valid(b)){sf_failed=1u;return 0;}
 if(sf_zero(a)&&sf_zero(b)){return 0;}let sa=a.y>>31u;let sb=b.y>>31u;if(sa!=sb){return select(1,-1,sa!=0u);}let c=sf_cmpu(sf_abs(a),sf_abs(b));return select(c,-c,sa!=0u);
}
fn sf_integer(v:u32)->vec2<u32>{if(v==0u){return vec2<u32>(0u);}let top=31u-countLeadingZeros(v);let m=sf_shl(vec2<u32>(v,0u),52u-top);return vec2<u32>(m.x,((1023u+top)<<20u)|(m.y&0xfffffu));}
fn sf_signed(v:i32)->vec2<u32>{let a=sf_integer(u32(abs(v)));if(v<0){return sf_neg(a);}return a;}
fn sf_floor(a:vec2<u32>)->i32{
 if(!sf_valid(a)){sf_failed=1u;return -2147483647-1;}if(sf_zero(a)){return 0;}let sign=a.y>>31u;let power=sf_exp(a)-1023;if(power<0){return select(0,-1,sign!=0u);}if(power>=31){sf_failed=1u;return -2147483647-1;}
 let m=sf_sig(a);let shifted=sf_shr(m,u32(52-power));let residue=any(m!=sf_shl(shifted,u32(52-power)));let value=i32(shifted.x);return select(value,-value-select(0,1,residue),sign!=0u);
}
fn sf_scalar(a:vec2<u32>)->f32{
 if(!sf_valid(a)){sf_failed=1u;return 0.0;}if(sf_zero(a)){return 0.0;}var exponent=sf_exp(a)-1023+127;let sig=sf_sig(a);var mantissa=(sig.x>>29u)|(sig.y<<3u);let rest=sig.x&0x1fffffffu;
 if(rest>0x10000000u||(rest==0x10000000u&&(mantissa&1u)!=0u)){mantissa=mantissa+1u;}if((mantissa&0x1000000u)!=0u){mantissa=mantissa>>1u;exponent=exponent+1;}
 if(exponent<=0||exponent>=255){sf_failed=1u;return 0.0;}return bitcast<f32>((a.y&0x80000000u)|(u32(exponent)<<23u)|(mantissa&0x7fffffu));
}
fn sf_log(a:vec2<u32>)->vec2<u32>{
 if(!sf_valid(a)||sf_zero(a)||(a.y>>31u)!=0u){return sf_fail();}
 var k=sf_exp(a)-1023;var m=vec2<u32>(a.x,(a.y&0xfffffu)|0x3ff00000u);
 // Direct atanh near1 avoids subtracting two large logs for binomial q≈1.
 if(sf_cmp(a,vec2<u32>(0u,0x3fe00000u))>=0&&sf_cmp(a,SF_TWO)<=0){m=a;k=0;}
 let z=sf_div(sf_sub(m,SF_ONE),sf_add(m,SF_ONE));let z2=sf_mul(z,z);var term=z;var sum=z;
 for(var j=3u;j<=63u;j=j+2u){term=sf_mul(term,z2);if(sf_cmpu(sf_abs(term),vec2<u32>(0u,0x3b900000u))<0){break;}sum=sf_add(sum,sf_div(term,sf_integer(j)));}
 return sf_add(sf_mul(SF_TWO,sum),sf_mul(sf_signed(k),SF_LN2));
}
fn sf_exp_nonpositive(a:vec2<u32>)->vec2<u32>{
 if(!sf_valid(a)||sf_cmp(a,vec2<u32>(0u))>0||sf_cmp(a,vec2<u32>(0u,0xc0500000u))<0){return sf_fail();}
 let z=sf_div(a,sf_integer(256u));var term=SF_ONE;var sum=SF_ONE;
 for(var k=1u;k<=20u;k=k+1u){term=sf_div(sf_mul(term,z),sf_integer(k));sum=sf_add(sum,term);}
 for(var k=0u;k<8u;k=k+1u){sum=sf_mul(sum,sum);}return sum;
}
