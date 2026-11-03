// Original scanner tail equations. Appended to research_spatial.wgsl.
const T_SRGB_RGB_TO_XYZ:array<vec3<f32>,3> = array<vec3<f32>,3>(vec3<f32>(0.4124,0.3576,0.1805),vec3<f32>(0.2126,0.7152,0.0722),vec3<f32>(0.0193,0.1192,0.9505));
const T_SRGB_XYZ_TO_RGB:array<vec3<f32>,3> = array<vec3<f32>,3>(vec3<f32>(3.2406,-1.5372,-0.4986),vec3<f32>(-0.9689,1.8758,0.0415),vec3<f32>(0.0557,-0.204,1.057));
const T_M1_XYZ_TO_LMS:array<vec3<f32>,3> = array<vec3<f32>,3>(vec3<f32>(0.8189330101,0.3618667424,-0.1288597137),vec3<f32>(0.0329845436,0.9293118715,0.0361456387),vec3<f32>(0.0482003018,0.2643662691,0.633851707));
const T_M1_LMS_TO_XYZ:array<vec3<f32>,3> = array<vec3<f32>,3>(vec3<f32>(1.2270138511035211,-0.5577999806518222,0.2812561489664678),vec3<f32>(-0.0405801784232806,1.11225686961683,-0.07167667866560119),vec3<f32>(-0.0763812845057069,-0.4214819784180127,1.5861632204407947));
const T_M2_LMS_TO_LAB:array<vec3<f32>,3> = array<vec3<f32>,3>(vec3<f32>(0.2104542553,0.793617785,-0.0040720468),vec3<f32>(1.9779984951,-2.428592205,0.4505937099),vec3<f32>(0.0259040371,0.7827717662,-0.808675766));
const T_M2_LAB_TO_LMS:array<vec3<f32>,3> = array<vec3<f32>,3>(vec3<f32>(0.9999999984505196,0.3963377921737678,0.21580375806075877),vec3<f32>(1.0000000088817607,-0.10556134232365633,-0.0638541747717059),vec3<f32>(1.0000000546724108,-0.08948418209496574,-1.2914855378640917));
const T_CAM16_M16:array<vec3<f32>,3> = array<vec3<f32>,3>(vec3<f32>(0.401288,0.650173,-0.051461),vec3<f32>(-0.250268,1.204414,0.045854),vec3<f32>(-0.002079,0.048952,0.953127));
const T_CAM16_M16I:array<vec3<f32>,3> = array<vec3<f32>,3>(vec3<f32>(1.8620678550872327,-1.0112546305316843,0.14918677544445175),vec3<f32>(0.3875265432361372,0.6214474419314753,-0.008973985167612516),vec3<f32>(-0.015841498849333863,-0.03412293802851557,1.0499644368778496));
fn tv(i:u32)->f32 {return tbound(probe.values[262144u+i]);}
fn tm(m:array<vec3<f32>,3>,v:vec3<f32>)->vec3<f32> {return vec3<f32>(tdot(m[0],v),tdot(m[1],v),tdot(m[2],v));}
fn tail_matrix(base:u32,v:vec3<f32>)->vec3<f32> {return vec3<f32>(tdot(vec3<f32>(tv(base),tv(base+1u),tv(base+2u)),v),tdot(vec3<f32>(tv(base+3u),tv(base+4u),tv(base+5u)),v),tdot(vec3<f32>(tv(base+6u),tv(base+7u),tv(base+8u)),v));}
fn tail_knee(d:f32,t:f32,lim:f32,power:f32)->f32 {
 if(d>t){let scale=tsub(lim,t);let x=tdiv(tsub(d,t),scale);let y=tdiv(x,tpow(tadd(1.0,tpow(x,power)),tdiv(1.0,power)));return tadd(t,tmul(scale,y));}return tbound(d);
}
fn tail_oklab(xyz:vec3<f32>)->vec3<f32> {let x=tm(T_M1_XYZ_TO_LMS,xyz);return tm(T_M2_LMS_TO_LAB,sign(x)*tpow3(abs(x),1.0/3.0));}
fn tail_okxyz(lab:vec3<f32>)->vec3<f32> {let x=tm(T_M2_LAB_TO_LMS,lab);return tm(T_M1_LMS_TO_XYZ,vec3<f32>(tmul(tmul(x.x,x.x),x.x),tmul(tmul(x.y,x.y),x.y),tmul(tmul(x.z,x.z),x.z)));}
fn tail_lr(l:f32)->f32 {let k3=1.206/1.03;let t=tsub(tmul(k3,l),0.206);return tmul(0.5,tadd(t,tsqrt(tadd(tmul(t,t),tmul(4.0*0.03*k3,l)))));}
fn tail_angle(y:f32,x:f32)->f32 {if(x==0.0&&y==0.0){return 0.0;}return atan2(tbound(y),tbound(x));}
fn tail_hue(y:f32,x:f32)->f32 {let degrees=tail_angle(y,x)*180.0/3.141592653589793;return ((degrees%360.0)+360.0)%360.0;}
fn tail_cam_forward(xyz:vec3<f32>)->vec3<f32> {
 let rgb=tb3(tm(T_CAM16_M16,tb3(xyz*100.0))*vec3<f32>(1.0228770275436545,0.9852074782801457,0.9285450586783286));
 let flr=tpow3(0.6839903845696502*abs(rgb)/100.0,0.42);let resp=400.0*sign(rgb)*flr/(vec3<f32>(27.13)+flr)+vec3<f32>(0.1);
 let ra=resp.x;let ga=resp.y;let ba=resp.z;let a=ra-12.0*ga/11.0+ba/11.0;let b=(ra+ga-2.0*ba)/9.0;
 let h=tail_hue(b,a);let hr=h*3.141592653589793/180.0;let et=0.25*(cos(2.0+hr)+3.8);
 let ar=(2.0*ra+ga+ba/20.0-0.305)*1.0003040045593807;let j=tmul(100.0,tpow(ar/37.16907530221132,0.69*1.9272135954999579));
 let den=ra+ga+21.0*ba/20.0;var t=0.0;if(den!=0.0){t=tdiv((50000.0/13.0)*1.0003040045593807*(et*tsqrt(a*a+b*b)),den);}
 let cc=tmul(tmul(tpow(t,0.9),tpow(j/100.0,0.5)),pow(1.64-pow(0.29,0.2),0.73));let m=tmul(cc,pow(0.6839903845696502,0.25));
 let jp=tdiv(tmul(1.7,j),tadd(1.0,tmul(0.007,j)));let mp=tmul(1.0/0.0228,tlog(tadd(1.0,tmul(0.0228,m))));return tb3(vec3<f32>(jp,mp*cos(hr),mp*sin(hr)));
}
fn tail_cam_inverse(jab:vec3<f32>)->vec3<f32> {
 let mp=tsqrt(tadd(tmul(jab.y,jab.y),tmul(jab.z,jab.z)));let h=tail_hue(jab.z,jab.y);let hr=h*3.141592653589793/180.0;
 let j=tdiv(jab.x,tsub(1.7,tmul(0.007,jab.x)));let m=tdiv(tsub(texp(tmul(0.0228,mp)),1.0),0.0228);let cc=tdiv(m,pow(0.6839903845696502,0.25));
 let t=tpow(tdiv(cc,tmul(tsqrt(max(j,2.220446049250313e-16)/100.0),pow(1.64-pow(0.29,0.2),0.73))),1.0/0.9);
 let et=0.25*(cos(2.0+hr)+3.8);let ar=tmul(37.16907530221132,tpow(j/100.0,1.0/(0.69*1.9272135954999579)));
 var p1=0.0;if(t!=0.0){p1=tdiv((50000.0/13.0)*1.0003040045593807*et,t);}
 let p2=tadd(ar/1.0003040045593807,0.305);let p3=21.0/20.0;let s=sin(hr);let co=cos(hr);let nn=tmul(tmul(p2,2.0+p3),460.0/1403.0);
 var a=0.0;var b=0.0;
 // The original t==0 branch overwrites a/b with zero. Do not execute its
 // unused divisions, which may otherwise be indeterminate for black pixels.
 if(t!=0.0){if(abs(s)>=abs(co)){let p4=tdiv(p1,s);b=tdiv(nn,tadd(tsub(tadd(p4,(2.0+p3)*(220.0/1403.0)*(co/s)),27.0/1403.0),p3*(6300.0/1403.0)));a=tmul(b,co/s);}
 else{let p5=tdiv(p1,co);a=tdiv(nn,tsub(tadd(p5,(2.0+p3)*(220.0/1403.0)),((27.0/1403.0)-p3*(6300.0/1403.0))*(s/co)));b=tmul(a,s/co);}}
 let resp=tb3(vec3<f32>((460.0*p2+451.0*a+288.0*b)/1403.0,(460.0*p2-891.0*a-261.0*b)/1403.0,(460.0*p2-220.0*a-6300.0*b)/1403.0));
 let d=tb3(resp-vec3<f32>(0.1));if(any(abs(d)>=vec3<f32>(400.0))){return tzero();}
 let base=vec3<f32>(tdiv(27.13*abs(d.x),400.0-abs(d.x)),tdiv(27.13*abs(d.y),400.0-abs(d.y)),tdiv(27.13*abs(d.z),400.0-abs(d.z)));
 let rgb=tb3(sign(d)*100.0/0.6839903845696502*tpow3(base,1.0/0.42));
 return tm(T_CAM16_M16I,rgb/vec3<f32>(1.0228770275436545,0.9852074782801457,0.9285450586783286))/100.0;
}
fn tail_cmax(l0:f32,h:f32)->f32 {
 let nl=u32(tv(47u));let nh=u32(tv(48u));let l=clamp(l0,tv(45u),tv(46u));let pi=3.141592653589793;
 let hi=(h+pi)/(2.0*pi/f32(nh));let hf=floor(hi);let hlo=u32(((i32(hf)%i32(nh))+i32(nh))%i32(nh));let hhi=(hlo+1u)%nh;let ht=hi-hf;
 let li=tmul(tdiv(tsub(l,tv(45u)),tsub(tv(46u),tv(45u))),f32(nl-1u));let llo=u32(clamp(i32(floor(li)),0,i32(nl)-2));let lhi=llo+1u;let lt=li-f32(llo);
 return tbound(tv(64u+llo*nh+hlo)*(1.0-lt)*(1.0-ht)+tv(64u+llo*nh+hhi)*(1.0-lt)*ht+tv(64u+lhi*nh+hlo)*lt*(1.0-ht)+tv(64u+lhi*nh+hhi)*lt*ht);
}
fn tail_gamut(rgb:vec3<f32>)->vec3<f32> {
 if(tv(35u)==0.0){return rgb;}let mode=u32(tv(36u));
 if(mode==0u){let ach=max(max(rgb.x,rgb.y),rgb.z);if(ach<=1e-12){return rgb;}var out:vec3<f32>;for(var c=0u;c<3u;c=c+1u){out[c]=tmul(ach,tsub(1.0,tail_knee(tdiv(tsub(ach,rgb[c]),ach),tv(37u),tv(38u),tv(39u))));}return out;}
 let xyz=tm(T_SRGB_RGB_TO_XYZ,rgb);var lab:vec3<f32>;if(mode==3u){lab=tail_cam_forward(xyz);}else{lab=tail_oklab(xyz);}
 var l=lab.x;if(tv(40u)!=0.0){l=tmul(tail_knee(tdiv(l,tv(44u)),tv(41u),tv(42u),tv(43u)),tv(44u));}
 let chroma=tsqrt(tadd(tmul(lab.y,lab.y),tmul(lab.z,lab.z)));let h=tail_angle(lab.z,lab.y);var ll=l;if(mode==2u){ll=tail_lr(l);}
 let safe=max(tail_cmax(ll,h),1e-9);let cn=tmul(tail_knee(tdiv(chroma,safe),tv(37u),tv(38u),tv(39u)),safe);
 let out=tb3(vec3<f32>(l,cn*cos(h),cn*sin(h)));var ox:vec3<f32>;if(mode==3u){ox=tail_cam_inverse(out);}else{ox=tail_okxyz(out);}return tm(T_SRGB_XYZ_TO_RGB,ox);
}
fn tail_color(logxyz:vec3<f32>,glare:f32)->vec3<f32> {
 // pow(10,x) is never evaluated outside this conservative normal-f32 window.
 if(any(logxyz<vec3<f32>(-30.0))||any(logxyz>vec3<f32>(15.0))||glare<0.0||glare>T_BOUND){return tzero();}
 var xyz=tb3(pow(vec3<f32>(10.0),logxyz));
 if(tv(27u)!=0.0){let scale=clamp(tadd(tmul(tv(28u),xyz.y),tv(29u)),0.0,1.0)/(xyz.y+1e-10);xyz=tb3(xyz*scale);}
 let adapted=tail_matrix(0u,xyz);var rgb=tail_matrix(9u,adapted);
 rgb=tb3(rgb+(glare*0.01)*vec3<f32>(tv(30u),tv(31u),tv(32u)));rgb=tail_gamut(rgb);
 if(tv(33u)!=0.0){rgb=clamp(tail_matrix(18u,rgb),vec3<f32>(0.0),vec3<f32>(1.0));
  for(var c=0u;c<3u;c=c+1u){if(rgb[c]<=0.0031308){rgb[c]=rgb[c]*12.92;}else{rgb[c]=1.055*pow(rgb[c],1.0/2.4)-0.055;}}}
 return tb3(rgb);
}
fn tail_main(i:u32) {
 let op=p.u[0].z;let offset=p.u[1].w;
 if(op==20u){source.values[offset+i]=tbound(probe.values[524288u+i]);return;}
 if(op==21u){let x=vec3<f32>(probe.values[524288u+3u*i],probe.values[524289u+3u*i],probe.values[524290u+3u*i]);
  var glare=0.0;if(p.u[1].z!=0u){glare=source.values[offset+i];}let rgb=tail_color(x,glare);source.values[offset+i]=rgb.x;saved.values[offset+i]=rgb.y;accum.values[offset+i]=rgb.z;return;}
 if(op==22u){let other=get_value(p.u[1].z,i);put_value(p.u[1].z,i,source.values[i]);source.values[i]=other;return;}
 if(op==23u){var v=tbound(source.values[i]);if(p.u[1].z!=0u){v=tadd(v,tmul(bitcast<f32>(p.u[3].x),tsub(v,blurred.values[i])));}source.values[i]=clamp(v,0.0,1.0);return;}
 if(op==24u){let j=offset+i;var rgb=tb3(vec3<f32>(saved.values[j],accum.values[j],source.values[j]));
  if(tv(49u)!=0.0){rgb=tm(T_SRGB_XYZ_TO_RGB,tail_matrix(50u,rgb));}rgb=tb3(rgb*tv(34u));
  probe.values[3u*i]=rgb.x;probe.values[3u*i+1u]=rgb.y;probe.values[3u*i+2u]=rgb.z;return;}
 if(op==25u){var j=0u;if(p.u[1].x>1u){j=i*(p.u[1].y-1u)/(p.u[1].x-1u);}j=j+offset;probe.values[3u*i]=source.values[j];probe.values[3u*i+1u]=saved.values[j];probe.values[3u*i+2u]=accum.values[j];return;}
}
