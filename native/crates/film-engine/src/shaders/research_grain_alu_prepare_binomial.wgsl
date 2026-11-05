fn sr_prepare_binomial(n:u32,pb:vec2<u32>)->SrBinomialSetup{
 let nf=sf_integer(n);var effective=pb;var inversion=false;
 if(sf_cmp(pb,vec2<u32>(0x00000000u,0x3fe00000u))<=0){inversion=sf_cmp(sf_mul(pb,nf),vec2<u32>(0x00000000u,0x403e0000u))<=0;}
 else{let q=sf_sub(vec2<u32>(0x00000000u,0x3ff00000u),pb);effective=q;inversion=sf_cmp(sf_mul(q,nf),vec2<u32>(0x00000000u,0x403e0000u))<=0;}
 if(inversion){return sr_prepare_inversion(n,effective);}return sr_prepare_btpe(n,effective);
}
