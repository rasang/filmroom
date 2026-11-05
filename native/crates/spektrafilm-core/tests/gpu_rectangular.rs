#![cfg(all(feature = "wgpu-backend", feature = "precision-f64"))]
//! Actual-device rectangular coordinate, crop, prepass and seam contracts.
use spektrafilm_core::{params::RuntimeParams, pipeline::{Pipeline, TileContext, DiffusionScatteredFields}, profile::load_profile_by_name};
use spektrafilm_gpu::{wgpu_backend::WgpuBackend, DiffusionScatteredGpu, ComputeBackend};
use std::path::Path;

fn pipeline(diffusion: bool) -> Pipeline {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let mut p = RuntimeParams::default();
    p.camera.auto_exposure = false;
    p.camera.pixel_size_um_override = Some(50.);
    p.io.input_cctf_decoding = false;
    p.io.output_cctf_encoding = false;
    p.film_render.halation.active = false;
    p.film_render.halation.boost_ev = if diffusion { 0.5 } else { 0. };
    p.film_render.dir_couplers.active = false;
    p.film_render.grain.active = !diffusion;
    p.film_render.grain.coordinate_origin = Some([0,0]);
    p.print_render.glare.active = true;
    p.print_render.glare.blur = 0.7;
    p.scanner.lens_blur = 0.5;
    p.scanner.unsharp_mask = [0.7, 0.3];
    p.camera.diffusion_filter.active = diffusion;
    p.enlarger.diffusion_filter.active = diffusion;
    Pipeline::new_with_spectral(load_profile_by_name(&path,"kodak_portra_400").unwrap(),
        load_profile_by_name(&path,"kodak_portra_endura").unwrap(), p, &path).unwrap()
}
fn rect(values:&[f32], full_width:u32,x:u32,y:u32,w:u32,h:u32)->Vec<f32> {
    let mut out=Vec::with_capacity((w*h*3)as usize);
    for yy in y..y+h { out.extend_from_slice(&values[((yy*full_width+x)*3)as usize..((yy*full_width+x+w)*3)as usize]); }
    out
}
fn crop_fields(fields:&DiffusionScatteredFields,tile:TileContext,w:u32,h:u32)->DiffusionScatteredFields {
    let mut out=fields.clone();
    for f in [&mut out.camera,&mut out.enlarger].into_iter().flatten() {
        let [x,y,cw,ch]=DiffusionScatteredGpu::required_crop(f.small_w,f.small_h,f.d,tile.x,tile.y,w,h).unwrap();
        f.field=rect(&f.field,f.small_w,x,y,cw,ch);
        f.crop=Some([x,y,cw,ch]);
    }
    out
}
#[test]
fn rectangular_tiles_preserve_rng_and_global_diffusion_sampling() {
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");
        return;
    };
    let (w,h,cw,ch)=(97u32,109u32,31u32,37u32);
    let values:Vec<f32>=(0..w*h*3).map(|i|0.05+((i*13+i/(w*3)*7)%251)as f32/300.).collect();
    for diffusion in [false,true] {
        let pipe=pipeline(diffusion);
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(w,h,cw,ch,
            |x,y,tw,th|Some(rect(&values,w,x,y,tw,th)),||true,&gpu).unwrap();
        let whole=pipe.process_gpu_resident_tile_f32_controlled(&values,w,h,
            TileContext{full_width:w,full_height:h,x:0,y:0},&gpu,Some(&fields),||true).unwrap();
        let halo=pipe.banded_halo_pixels(50.);
        let mut stitched=vec![0.;whole.data.len()];
        let mut calls=0;
        for y in (0..h).step_by(ch as usize) { for x in (0..w).step_by(cw as usize) {
            let x1=(x+cw).min(w);let y1=(y+ch).min(h);
            let tx=x.saturating_sub(halo);let ty=y.saturating_sub(halo);
            let tw=(x1+halo).min(w)-tx;let th=(y1+halo).min(h)-ty;
            let tile=TileContext{full_width:w,full_height:h,x:tx,y:ty};
            let input=rect(&values,w,tx,ty,tw,th);
            let cropped=crop_fields(&fields,tile,tw,th);
            let out=pipe.process_gpu_resident_tile_f32_controlled(&input,tw,th,tile,&gpu,Some(&cropped),||true).unwrap();
            let repeat=pipe.process_gpu_resident_tile_f32_controlled(&input,tw,th,tile,&gpu,Some(&cropped),||true).unwrap();
            assert_eq!(out.data,repeat.data,"same-path tile repeat");
            assert_eq!((out.width,out.height),(tw,th));
            for yy in y..y1 { for xx in x..x1 { for c in 0..3usize {
                stitched[((yy*w+xx)*3)as usize+c]=out.data[(((yy-ty)*tw+xx-tx)*3)as usize+c];
            }}}
            calls+=1;
        }}
        let maximum=whole.data.iter().zip(&stitched).map(|(a,b)|(a-b).abs()).fold(0.0f64,f64::max);
        let rms=(whole.data.iter().zip(&stitched).map(|(a,b)|(a-b).powi(2)).sum::<f64>()/stitched.len()as f64).sqrt();
        eprintln!("rectangular diffusion={diffusion} tiles={calls} halo={halo} max={maximum} rms={rms} submits={}",gpu.thread_submits());
        assert_eq!(calls,12);
        // With a shared global field, no different prepass arithmetic enters
        // discrete grain. A stride/crop/halo regression should exceed this.
        assert!(maximum<1e-5,"rectangular seam/coordinate difference {maximum}");
    }
    assert!(gpu.thread_submits()>0);
}

#[test]
fn completed_batches_release_lock_and_cancel_without_quarantine() {
    use spektrafilm_gpu::ComputeBackend;
    use spektrafilm_math::{image::ImageBuf, precision::from_f64};
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");
        return;
    };
    let base=pipeline(false);
    let mut params=base.params.clone();
    params.film_render.dir_couplers.active=true;
    let pipe=base.with_params(params);
    let (w,h)=(257,257);
    let values=vec![0.3; (w*h*3) as usize];
    let tile=TileContext{full_width:w,full_height:h,x:0,y:0};
    let whole=pipe.process_gpu_resident_cpu_gamut_f32(&values,w,h,&Unbatched(&gpu)).unwrap();
    let mut interleaved=false;
    let mut maximum_batch=0;
    let before_actual=gpu.thread_submits();
    let actual=pipe.process_gpu_resident_tile_f32_controlled(&values,w,h,tile,&gpu,None,|| {
        if let Some(progress)=gpu.thread_resident_progress() {
            maximum_batch=maximum_batch.max(progress.completed_batches);
            assert!(progress.batch_elapsed_ms.is_finite() && progress.batch_elapsed_ms>=0.);
            if gpu.thread_submits()>before_actual && progress.completed_batches==1 && !interleaved {
                // A real GPU call would hit the 250ms ownership deadline if
                // the resident callback still held the execution mutex.
                let probe=ImageBuf::from_data(1,1,vec![from_f64(0.25);3]);
                assert_eq!(gpu.gaussian_blur(&probe,0.5).data.len(),3);
                interleaved=true;
            }
        }
        true
    }).unwrap();
    assert!(interleaved && maximum_batch>1);
    let maximum=whole.data.iter().zip(&actual.data).map(|(a,b)|(a-b).abs()).fold(0.0f64,f64::max);
    assert!(maximum<1e-5,"stage fences changed pixels {maximum}");
    // Permit cancellation only after the current invocation submitted work,
    // not on the prior invocation's retained completion snapshot.
    let before=gpu.thread_submits();
    let cancelled=pipe.process_gpu_resident_tile_f32_controlled(&values,w,h,tile,&gpu,None,|| {
        gpu.thread_submits()==before || gpu.thread_resident_progress().is_none_or(|p|p.completed_batches<2)
    });
    assert!(cancelled.is_none());
    assert!(gpu.is_healthy() && !gpu.completion_uncertain());
    assert!(gpu.thread_resident_failure().is_none());
    assert!(pipe.process_gpu_resident_tile_f32_controlled(&values,w,h,tile,&gpu,None,||true).is_some());
    eprintln!("GPU_STAGE_BATCH_PASS completed={maximum_batch} max={maximum}, callback GPU reentry, confirmed-fence cancellation, healthy reuse");
}

/// Test-only adapter: replay the identical prepared chain with its batching
/// flag disabled, so comparison changes scheduling only, not the pixel model.
struct Unbatched<'a>(&'a WgpuBackend);
impl spektrafilm_gpu::ComputeBackend for Unbatched<'_> {
    fn colorspace_convert(&self,i:&spektrafilm_math::image::ImageBuf,m:&[[f32;3];3])->spektrafilm_math::image::ImageBuf{self.0.colorspace_convert(i,m)}
    fn cctf_encode_srgb(&self,i:&spektrafilm_math::image::ImageBuf)->spektrafilm_math::image::ImageBuf{self.0.cctf_encode_srgb(i)}
    fn cctf_decode_srgb(&self,i:&spektrafilm_math::image::ImageBuf)->spektrafilm_math::image::ImageBuf{self.0.cctf_decode_srgb(i)}
    fn gaussian_blur(&self,i:&spektrafilm_math::image::ImageBuf,s:f32)->spektrafilm_math::image::ImageBuf{self.0.gaussian_blur(i,s)}
    fn table_lookup(&self,i:&spektrafilm_math::image::ImageBuf,x:&[f32],y:&[[f32;3]])->spektrafilm_math::image::ImageBuf{self.0.table_lookup(i,x,y)}
    fn lut3d_interp(&self,i:&spektrafilm_math::image::ImageBuf,l:&spektrafilm_gpu::Lut3D)->spektrafilm_math::image::ImageBuf{self.0.lut3d_interp(i,l)}
    fn reset_thread_resident_progress(&self){self.0.reset_thread_resident_progress()}
    fn try_run_film_chain(&self,p:&spektrafilm_gpu::FilmChainParams<'_>)->Option<spektrafilm_math::image::ImageBuf>{
        let mut identical=p.clone();identical.stage_batched=false;self.0.try_run_film_chain(&identical)
    }
    fn resident_chain_applies_post_scan(&self)->bool{true}
    fn is_gpu(&self)->bool{true}
    fn name(&self)->&str{"test-only identical unbatched scheduling"}
}

#[test]
fn whole_export_diffusion_batches_keep_pixels_and_preview_fast_path() {
    use spektrafilm_gpu::{ComputeBackend,wgpu_backend::ResidentStage};
    use spektrafilm_math::{image::ImageBuf,precision::from_f64};
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");
        return;
    };
    let pipe=pipeline(true);
    let (w,h)=(257,259);
    let input:Vec<f32>=(0..w*h*3).map(|v|0.1+(v%137)as f32/200.).collect();
    let before=gpu.thread_submits();
    let reference=pipe.process_gpu_resident_cpu_gamut_f32(&input,w,h,&Unbatched(&gpu)).unwrap();
    let reference_submits=gpu.thread_submits()-before;
    let before=gpu.thread_submits();
    let (mut last,mut camera,mut print)=(0,0,0);
    let actual=pipe.process_gpu_resident_cpu_gamut_f32_controlled(&input,w,h,&gpu,||{
        if let Some(p)=gpu.thread_resident_progress() {if p.completed_batches>last {
            last=p.completed_batches;
            if p.stage==ResidentStage::CameraOptics {camera+=1;}
            if p.stage==ResidentStage::Print {print+=1;}
        }}
        true
    }).unwrap();
    assert!(camera>1 && print>1,"each whole diffusion mixture must have bounded component batches");
    assert_eq!(actual.data,reference.data,"batch scheduling changed whole export pixels");
    let batched_submits=gpu.thread_submits()-before;
    assert!(batched_submits>reference_submits);
    let before=gpu.thread_submits();
    assert!(pipe.process_gpu_resident_cpu_gamut_f32_controlled(&input,w,h,&gpu,||{
        gpu.thread_submits()==before || gpu.thread_resident_progress().is_none_or(|p|p.completed_batches<3)
    }).is_none());
    assert!(gpu.is_healthy() && !gpu.completion_uncertain());
    let preview=ImageBuf::from_data(96,97,vec![from_f64(0.3);96*97*3]);
    let before=gpu.thread_submits();
    assert!(pipe.process_resident_borrowed(&preview,&gpu).is_some());
    assert_eq!(gpu.thread_submits()-before,1,"small preview must keep one resident submission");
    eprintln!("GPU_WHOLE_BATCH_PASS camera={camera} print={print} referenceSubmits={reference_submits} batchedSubmits={batched_submits} exactPixels=true confirmedCancel=true previewSubmits=1");
}
