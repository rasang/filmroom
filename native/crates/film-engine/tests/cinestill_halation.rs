//! Physical 35 mm fixtures, unlike a tiny virtual-frame smoke test. These
//! compare the real CPU whole/streamed paths, not an assumed UI appearance.
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use std::{fs, path::{Path,PathBuf}, sync::atomic::{AtomicU64,Ordering}};

static NEXT:AtomicU64=AtomicU64::new(0);
struct Temp(PathBuf);
impl Temp {fn new()->Self {let p=std::env::temp_dir().join(format!("cinestill-halo-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&p).unwrap();Self(p)}}
impl Drop for Temp {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
fn data()->PathBuf {Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
fn params(direct:bool)->RuntimeParams {
    let mut p=RuntimeParams::default();
    p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.io.scan_film=direct;
    p.camera.auto_exposure=false;p.camera.film_format_mm=35.;
    p.film_render.grain.active=false;p.film_render.dir_couplers.active=false;
    p.film_render.glare.active=false;p.print_render.glare.active=false;p.scanner.unsharp_mask=[0.,0.];
    p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;
    p.film_render.halation.scatter_amount=0.;p.film_render.halation.back_reflection=true;
    p.film_render.halation.halation_strength=spektrafilm_math::halation::BACK_REFLECTION_STRENGTH;
    p
}
fn pipe(p:RuntimeParams)->Pipeline {
    Pipeline::new_with_spectral(load_profile_by_name(&data(),"cinestill_800t").unwrap(),load_profile_by_name(&data(),"kodak_2383").unwrap(),p,&data()).unwrap()
}
fn lamp(w:u32,h:u32,level:f32)->Vec<f32> {
    (0..w*h*3).map(|i| {let x=(i/3)%w;let y=(i/3)/w;if x>=w/2-8&&x<w/2+8&&y>=h/4&&y<3*h/4{level}else{0.002}}).collect()
}
#[test]
fn cinestill_35mm_has_extended_red_halo_without_changing_the_source_profile() {
    let (w,h)=(512,96);let input=lamp(w,h,1.);
    let image=ImageBuf::from_data(w,h,input.iter().map(|v|from_f64(*v as f64)).collect());
    let p=params(false);let new=pipe(p.clone()).process(image.clone(),&CpuBackend);
    let mut old=p.clone();old.film_render.halation.back_reflection=false;old.film_render.halation.halation_strength=[0.30,0.10,0.015];
    let old=pipe(old).process(image.clone(),&CpuBackend);
    let mut off=p;off.film_render.halation.halation_amount=0.;
    let off=pipe(off).process(image,&CpuBackend);
    let energy=|a:&ImageBuf,c:usize|->f64{(272..296).map(|x|{let i=((h/2*w+x)*3+c as u32)as usize;((a.data[i]-off.data[i])as f64).max(0.)}).sum()};
    assert!(new.data.iter().all(|x|x.is_finite()));
    assert!(energy(&new,0)>0.001,"new red energy {}",energy(&new,0));
    assert!(energy(&new,0)>10.*energy(&old,0),"new={} old={}",energy(&new,0),energy(&old,0));
    assert!(energy(&new,0)>3.*energy(&new,1),"red={} green={}",energy(&new,0),energy(&new,1));
}

#[test]
fn cinestill_streamed_matches_whole_in_both_routes_and_after_boost() {
    let (w,h)=(256,64);let input=lamp(w,h,1.);let t=Temp::new();let source=t.0.join("input.rgb");
    fs::write(&source,input.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
    for (index,(direct,boost,size,amount)) in [(false,0.,1.,1.),(true,0.,1.,1.),(false,2.,0.5,2.),(true,1.,3.,0.)].into_iter().enumerate() {
        let mut p=params(direct);p.film_render.halation.boost_ev=boost;p.film_render.halation.halation_spatial_scale=size;p.film_render.halation.halation_amount=amount;
        let expected=pipe(p.clone()).process(ImageBuf::from_data(w,h,input.iter().map(|v|from_f64(*v as f64)).collect()),&CpuBackend);
        let output=t.0.join(format!("out{index}"));let scratch=t.0.join(format!("scratch{index}"));
        film_engine::streamed::export_file(&data(),"cinestill_800t","kodak_2383",p,&source,&output,&scratch,w,h,384,0.,||false,|_,_,_|{}).unwrap();
        let raw=fs::read(&output).unwrap();let mut error=0f64;
        for (bytes,want) in raw.chunks_exact(4).zip(&expected.data) {let got=f32::from_le_bytes(bytes.try_into().unwrap());assert!(got.is_finite());error=error.max((got as f64-*want as f64).abs());}
        assert!(error<2e-5,"route direct={direct} boost={boost} size={size} amount={amount}: {error}");
        assert!(!scratch.exists());
    }
}

#[test]
fn cinestill_legacy_diagnostic_tiler_refuses_incomplete_global_model() {
    let error=film_engine::tiled::export_file(&data(),"cinestill_800t","kodak_2383",params(false),Path::new("unused-input"),Path::new("unused-output"),32,32,64,65536,0.,||false).unwrap_err();
    assert_eq!(error.code,"UNSUPPORTED_EXPORT_OPTIONS");
}
