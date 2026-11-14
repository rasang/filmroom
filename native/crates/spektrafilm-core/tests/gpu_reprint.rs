#![cfg(all(feature="wgpu-backend",feature="precision-f64"))]
//! Real GPU, not a mocked backend: splitting at post-grain must preserve pixels.
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::load_profile_by_name};
use spektrafilm_gpu::wgpu_backend::WgpuBackend;
use spektrafilm_math::{image::ImageBuf,precision::from_f64};
use std::path::Path;

#[test]
fn postgrain_reprint_and_scoped_output_reuse_match_cold_render() {
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");return;
    };
    let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let (w,h)=(31,23);let values:Vec<f32>=(0..w*h*3).map(|i|0.02+((i*17)%251)as f32/400.).collect();
    let image=ImageBuf::from_data(w,h,values.iter().map(|v|from_f64(*v as f64)).collect());
    for (film,paper,diffusion) in [("kodak_portra_400","kodak_portra_endura",false),("kodak_portra_400","kodak_portra_endura",true),("kodak_ektachrome_100","kodak_portra_endura",false)] {
        let mut params=RuntimeParams::default();params.camera.auto_exposure=false;
        params.io.input_cctf_decoding=false;params.io.output_cctf_encoding=false;
        params.io.scan_film=film=="kodak_ektachrome_100";
        params.camera.pixel_size_um_override=Some(100.);params.camera.diffusion_filter.active=diffusion;
        params.enlarger.diffusion_filter.active=diffusion;params.film_render.grain.active=true;
        let make=|p|Pipeline::new_with_spectral(load_profile_by_name(&root,film).unwrap(),load_profile_by_name(&root,paper).unwrap(),p,&root).unwrap();
        let pipe=make(params.clone());
        let cold=pipe.process_resident_borrowed_controlled(&image,&gpu,||true).unwrap();
        let reuse=gpu.resident_reuse_scope();
        let log=pipe.tap_gpu_film_log_preview_controlled(&image,&gpu,||true).unwrap();
        let log:Vec<f32>=log.data.iter().map(|v|*v as f32).collect();
        let density=pipe.tap_gpu_corrected_density_preview_controlled(&log,w,h,&gpu,||true).unwrap();
        let density:Vec<f32>=density.data.iter().map(|v|*v as f32).collect();
        let grained=pipe.tap_gpu_grained_density_preview_controlled(&density,w,h,&gpu,||true).unwrap();
        let grained:Vec<f32>=grained.data.iter().map(|v|*v as f32).collect();
        let actual=pipe.process_gpu_from_grained_density_preview_controlled(&grained,w,h,&gpu,||true).unwrap();
        assert_eq!(cold.data,actual.data,"{film} diffusion={diffusion}: post-grain split changed pixels");
        assert!(reuse.stats().hits>=2,"output buffer was not reused");
        assert!(reuse.stats().peak_bytes<=32*1024*1024);
        params.enlarger.y_filter_shift=0.2;params.enlarger.print_exposure=1.2;
        let edited=make(params);
        if pipe.gpu_grained_density_prefix_key("data")==edited.gpu_grained_density_prefix_key("data") {
            let reference=edited.process_resident_borrowed_controlled(&image,&gpu,||true).unwrap();
            let reprint=edited.process_gpu_from_grained_density_preview_controlled(&grained,w,h,&gpu,||true).unwrap();
            assert_eq!(reference.data,reprint.data,"print edit reused stale negative");
        }
        assert!(pipe.process_gpu_from_grained_density_preview_controlled(&grained,w,h,&gpu,||false).is_none());
        drop(reuse);let fresh=gpu.resident_reuse_scope();assert_eq!(fresh.stats().hits,0);drop(fresh);
    }
}
