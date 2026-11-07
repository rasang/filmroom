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
    fn try_run_film_chain_capture_controlled(&self,p:&spektrafilm_gpu::FilmChainParams<'_>,
        capture:&mut spektrafilm_gpu::FilmDensityCapture,checkpoint:&mut dyn FnMut()->bool)->Option<spektrafilm_math::image::ImageBuf>{
        let mut identical=p.clone();identical.stage_batched=false;
        self.0.try_run_film_chain_capture_controlled(&identical,capture,checkpoint)
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

#[test]
fn film_log_split_preserves_pixels_fields_and_short_tail_coordinates() {
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");
        return;
    };
    let (w,h,cw,ch)=(97u32,109u32,31u32,37u32);
    let values:Vec<f32>=(0..w*h*3).map(|i|0.08+((i*17+i/(w*3)*3)%199)as f32/300.).collect();
    for diffusion in [false,true] {
        let base=pipeline(diffusion);let mut params=base.params.clone();
        params.film_render.halation.active=true;
        params.film_render.dir_couplers.active=true;
        params.film_render.grain.active=true;
        let pipe=base.with_params(params);
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(w,h,cw,ch,
            |x,y,tw,th|Some(rect(&values,w,x,y,tw,th)),||true,&gpu).unwrap();
        let whole=TileContext{full_width:w,full_height:h,x:0,y:0};
        let reference=pipe.process_gpu_resident_tile_f32_controlled(&values,w,h,whole,&gpu,Some(&fields),||true).unwrap();
        let tapped=pipe.tap_gpu_film_log_tile_f32_controlled(&values,w,h,whole,&gpu,Some(&fields),||true).unwrap();
        let lograw:Vec<f32>=tapped.data.iter().map(|v|*v as f32).collect();
        assert!(lograw.iter().any(|v|*v<0.),"film log tap must not clamp signed values");
        let suffix_fields=DiffusionScatteredFields{camera:None,highlight_max_raw:None,enlarger:fields.enlarger.clone()};
        let exact=pipe.process_gpu_from_film_log_tile_f32_controlled(&lograw,w,h,whole,&gpu,Some(&suffix_fields),||true).unwrap();
        assert_eq!(reference.data,exact.data,"same rectangle split must preserve exact pixels");
        // GPU.4: the suffix's optional side channel must neither change final
        // pixels nor add submissions. Compare samples with the unsplit tap.
        let samples = vec![
            spektrafilm_gpu::FilmDensitySample { index: 7, x: 0, y: 0 },
            spektrafilm_gpu::FilmDensitySample { index: 19, x: w-1, y: h-1 },
            spektrafilm_gpu::FilmDensitySample { index: 31, x: w/2, y: h/2 },
        ];
        let mut full_capture = spektrafilm_gpu::FilmDensityCapture { samples: samples.clone(), result: None };
        let full = pipe.process_gpu_resident_tile_f32_capture_controlled(&values,w,h,whole,
            &gpu,Some(&fields),&mut full_capture,||true).unwrap();
        assert_eq!(reference.data,full.data);
        let before = gpu.thread_submits();
        let plain = pipe.process_gpu_from_film_log_tile_f32_controlled(&lograw,w,h,whole,
            &gpu,Some(&suffix_fields),||true).unwrap();
        let plain_submits = gpu.thread_submits()-before;
        let mut suffix_capture = spektrafilm_gpu::FilmDensityCapture { samples, result: None };
        let before = gpu.thread_submits();
        let captured = pipe.process_gpu_from_film_log_tile_f32_capture_controlled(&lograw,w,h,whole,
            &gpu,Some(&suffix_fields),&mut suffix_capture,||true).unwrap();
        assert_eq!(plain_submits,gpu.thread_submits()-before,"capture added a submission");
        assert_eq!(plain.data,captured.data,"suffix capture changed final pixels");
        assert!(suffix_capture.result.is_some());
        assert_eq!(full_capture.result,suffix_capture.result,"suffix density tap differs from full chain");
        let before = gpu.thread_submits();
        assert!(pipe.process_gpu_from_film_log_tile_f32_capture_controlled(&lograw,w,h,whole,
            &gpu,Some(&suffix_fields),&mut suffix_capture,||gpu.thread_submits()==before).is_none());
        assert!(suffix_capture.result.is_none(),"cancelled suffix retained stale samples");
        assert!(gpu.is_healthy() && !gpu.completion_uncertain());

        let (prefix_halo,suffix_halo)=pipe.gpu_split_halo_pixels(50.);
        assert_eq!(prefix_halo+suffix_halo-2,pipe.banded_halo_pixels(50.));
        let mut spool=vec![0f32;values.len()];let mut result=vec![0f64;values.len()];
        for suffix in [false,true] {
            let halo=if suffix{suffix_halo}else{prefix_halo};
            for y in (0..h).step_by(ch as usize) {for x in (0..w).step_by(cw as usize) {
                let right=(x+cw).min(w);let bottom=(y+ch).min(h);
                let left=x.saturating_sub(halo);let top=y.saturating_sub(halo);
                let tw=(right+halo).min(w)-left;let th=(bottom+halo).min(h)-top;
                let tile=TileContext{full_width:w,full_height:h,x:left,y:top};
                let input=rect(if suffix{&spool}else{&values},w,left,top,tw,th);
                let out=if suffix {pipe.process_gpu_from_film_log_tile_f32_controlled(&input,tw,th,tile,&gpu,Some(&suffix_fields),||true)}
                    else {pipe.tap_gpu_film_log_tile_f32_controlled(&input,tw,th,tile,&gpu,Some(&fields),||true)}.unwrap();
                for yy in y..bottom {for xx in x..right {for c in 0..3usize {
                    let global=((yy*w+xx)*3)as usize+c;let local=(((yy-top)*tw+xx-left)*3)as usize+c;
                    if suffix{result[global]=out.data[local];}else{spool[global]=out.data[local]as f32;}
                }}}
            }}
        }
        let max=reference.data.iter().zip(&result).map(|(a,b)|(a-b).abs()).fold(0f64,f64::max);
        assert!(max<1e-5,"split tiled seam error {max}");
        assert!(pipe.tap_gpu_film_log_tile_f32_controlled(&values,w,h,whole,&gpu,Some(&fields),||false).is_none());
        assert!(pipe.process_gpu_from_film_log_tile_f32_controlled(&lograw,w,h,whole,&gpu,Some(&suffix_fields),||false).is_none());
        eprintln!("FILM_LOG_SPLIT diffusion={diffusion} exactRectangle=true shortTailMax={max} prefixHalo={prefix_halo} suffixHalo={suffix_halo}");
    }
    let pipe=pipeline(false);let (w,h)=(257,259);let values=vec![0.3;(w*h*3)as usize];
    let tile=TileContext{full_width:w,full_height:h,x:0,y:0};
    let before=gpu.thread_submits();
    assert!(pipe.tap_gpu_film_log_tile_f32_controlled(&values,w,h,tile,&gpu,None,||gpu.thread_submits()==before).is_none());
    let before=gpu.thread_submits();
    assert!(pipe.process_gpu_from_film_log_tile_f32_controlled(&values,w,h,tile,&gpu,None,||gpu.thread_submits()==before).is_none());
    assert!(gpu.is_healthy()&&!gpu.completion_uncertain());
}

/// Exercises actual phase retirement (>256² pixels), unlike tiny preview
/// fixtures. Unbatched uses the identical kernels/parameters as the oracle.
#[test]
fn lazy_phase_owners_preserve_effects_capture_and_completed_cancel_reentry() {
    use spektrafilm_gpu::{FilmDensityCapture, FilmDensitySample, wgpu_backend::ResidentStage};
    use spektrafilm_math::{image::ImageBuf, precision::from_f64};
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");
        return;
    };
    let (w,h)=(257,259);
    let tile=TileContext{full_width:w,full_height:h,x:0,y:0};
    let input:Vec<f32>=(0..w*h*3).map(|i|0.1+(i%137)as f32/200.).collect();
    for scan_film in [false,true] { for external in [false,true] {
        let base=pipeline(true);let mut params=base.params.clone();
        params.film_render.halation.active=true;
        params.film_render.dir_couplers.active=true;
        params.film_render.grain.active=true;
        params.film_render.grain.sublayers_active=true;
        params.film_render.grain.micro_structure=[30.,5000.];
        params.camera.lens_blur_um=30.;
        params.io.scan_film=scan_film;
        let pipe=base.with_params(params);
        let fields=external.then(||pipe.diffusion_scattered_fields_gpu_tiled_controlled(w,h,131,133,
            |x,y,tw,th|Some(rect(&input,w,x,y,tw,th)),||true,&gpu).unwrap());
        // A whole-frame call may build its own global diffusion/highlight
        // dependencies. The tile contract deliberately rejects missing fields,
        // even when that tile happens to cover the full working grid.
        let expected=if external {
            pipe.process_gpu_resident_tile_f32_controlled(&input,w,h,tile,
                &Unbatched(&gpu),fields.as_ref(),||true)
        } else {
            pipe.process_gpu_resident_cpu_gamut_f32_controlled(&input,w,h,
                &Unbatched(&gpu),||true)
        }.unwrap();
        let render=|backend:&dyn ComputeBackend,capture:&mut FilmDensityCapture,checkpoint:&mut dyn FnMut()->bool| {
            if external {
                pipe.process_gpu_resident_tile_f32_capture_controlled(&input,w,h,tile,
                    backend,fields.as_ref(),capture,checkpoint)
            } else {
                pipe.process_gpu_resident_cpu_gamut_f32_capture_controlled(&input,w,h,
                    backend,capture,checkpoint)
            }
        };
        let samples=vec![FilmDensitySample{index:0,x:0,y:0},FilmDensitySample{index:1,x:w-1,y:h-1}];
        let mut capture=FilmDensityCapture{samples:samples.clone(),result:None};
        let mut schedule=Vec::new();
        let actual=render(&gpu,&mut capture,&mut ||{
                if let Some(p)=gpu.thread_resident_progress() {
                    if schedule.last().is_none_or(|last|*last!=(p.stage,p.completed_batches)) {
                        schedule.push((p.stage,p.completed_batches));
                    }
                }
                true
            }).unwrap();
        assert_eq!(expected.data,actual.data,"phase retirement changed optional-effect pixels");
        assert!(capture.result.is_some());
        let expected_capture=capture.result.clone();
        let mut eager_capture=FilmDensityCapture{samples:samples.clone(),result:None};
        let eager=render(&Unbatched(&gpu),&mut eager_capture,&mut ||true).unwrap();
        assert_eq!(expected.data,eager.data);
        assert_eq!(expected_capture,eager_capture.result,"lazy owners changed capture samples");
        // Select the LAST Dir callback: internal axis fences are not the
        // retirement boundary. Output also covers final mapping cancellation.
        for stage in [ResidentStage::FilmDensity,ResidentStage::Dir,ResidentStage::Grain,ResidentStage::Output] {
            let batch=schedule.iter().rev().find(|(s,_)|*s==stage).unwrap().1;
            let mut hit=false;
            let cancelled=render(&gpu,&mut capture,&mut ||{
                    if !hit && gpu.thread_resident_progress().is_some_and(|p|p.stage==stage && p.completed_batches==batch) {
                        hit=true;
                        let probe=ImageBuf::from_data(1,1,vec![from_f64(0.25);3]);
                        assert_eq!(gpu.gaussian_blur(&probe,0.5).data.len(),3);
                        return false;
                    }
                    true
                });
            assert!(hit && cancelled.is_none(),"missing completed cancellation at {stage:?}");
            assert!(capture.result.is_none());
            assert!(gpu.is_healthy() && !gpu.completion_uncertain());
            assert!(gpu.thread_resident_failure().is_none());
            let again=render(&gpu,&mut capture,&mut ||true).unwrap();
            assert_eq!(expected.data,again.data);
            assert_eq!(expected_capture,capture.result);
        }
        // FilmLog prefix/suffix are tile APIs, so the all-effect split chain
        // always supplies full-grid fields. Both film and print routes run.
        if external {
        let prefix=pipe.tap_gpu_film_log_tile_f32_controlled(&input,w,h,tile,
            &gpu,fields.as_ref(),||true).unwrap();
        let prefix_reference=pipe.tap_gpu_film_log_tile_f32_controlled(&input,w,h,tile,
            &Unbatched(&gpu),fields.as_ref(),||true).unwrap();
        assert_eq!(prefix.data,prefix_reference.data,"FilmLog must retain halation until final submit");
        let log:Vec<f32>=prefix.data.iter().map(|v|*v as f32).collect();
        let suffix=pipe.process_gpu_from_film_log_tile_f32_capture_controlled(&log,w,h,tile,
            &gpu,fields.as_ref(),&mut capture,||true).unwrap();
        assert_eq!(actual.data,suffix.data);
        assert_eq!(expected_capture,capture.result,"capture owner must outlive DIR scratch");
        }
    }}
}

/// Observe actual logical descriptor owners, not physical GPU memory. The
/// feature is opt-in and the adapter-selected upload/readback strategy is logged.
#[cfg(feature="resource-audit")]
#[test]
fn resident_owner_graph_covers_effects_capture_nested_calls_and_cancellation() {
    use spektrafilm_gpu::{FilmDensityCapture,FilmDensitySample,resource_audit::{ThreadResourceAudit,Snapshot},wgpu_backend::ResidentStage};
    use spektrafilm_math::{image::ImageBuf,precision::from_f64};
    fn completed(snapshot:&Snapshot,submits:u64,label:&str) {
        let r=&snapshot.resident_buffer_owners;
        assert!(r.valid && r.quiescent,"{label}: invalid/nonquiescent {r:?}");
        assert_eq!(r.uncertain_submissions,0,"{label}");
        assert_eq!(r.active_allocations,0,"{label}");assert_eq!(r.active_owners,0,"{label}");
        assert_eq!(r.live_descriptor_bytes,Some(0),"{label}");
        assert_eq!(r.completed_submissions,submits,"{label}");
        let peak=r.peak_descriptor_bytes.expect("completed graph must have descriptor peak");
        assert!(peak>0 && peak<=snapshot.cumulative_buffer_descriptor_bytes,"{label}");
        assert!(r.metadata_capacity_bytes>0);
        assert!(snapshot.logical_live_owner_peak.is_none() && snapshot.physical_driver_peak.is_none());
        eprintln!("OWNER_MATRIX {label} peakDescriptors={peak} cumulativeDescriptors={} completedSubmits={submits} metadataCapacity={} physicalPeak=unknown",snapshot.cumulative_buffer_descriptor_bytes,r.metadata_capacity_bytes);
    }
    let gpu=WgpuBackend::new().expect("resource owner validation requires real WGPU execution");
    eprintln!("OWNER_MATRIX mappablePrimary={}",gpu.mappable_primary_buffers());
    let (w,h)=(257,259);
    let tile=TileContext{full_width:w,full_height:h,x:0,y:0};
    let input:Vec<f32>=(0..w*h*3).map(|i|0.1+(i%137)as f32/200.).collect();
    for scan_film in [false,true] { for external in [false,true] {
        let base=pipeline(true);let mut params=base.params.clone();
        params.film_render.halation.active=true;params.film_render.dir_couplers.active=true;
        params.film_render.grain.active=true;params.film_render.grain.sublayers_active=true;
        params.film_render.grain.micro_structure=[30.,5000.];params.camera.lens_blur_um=30.;params.io.scan_film=scan_film;
        let pipe=base.with_params(params);
        let fields=external.then(||pipe.diffusion_scattered_fields_gpu_tiled_controlled(w,h,131,133,
            |x,y,tw,th|Some(rect(&input,w,x,y,tw,th)),||true,&gpu).unwrap());
        let render=|backend:&dyn ComputeBackend,capture:&mut FilmDensityCapture,checkpoint:&mut dyn FnMut()->bool| {
            if external {pipe.process_gpu_resident_tile_f32_capture_controlled(&input,w,h,tile,backend,fields.as_ref(),capture,checkpoint)}
            else {pipe.process_gpu_resident_cpu_gamut_f32_capture_controlled(&input,w,h,backend,capture,checkpoint)}
        };
        let samples=vec![FilmDensitySample{index:0,x:0,y:0},FilmDensitySample{index:1,x:w-1,y:h-1}];
        let mut reference_capture=FilmDensityCapture{samples:samples.clone(),result:None};
        let expected=render(&Unbatched(&gpu),&mut reference_capture,&mut ||true).unwrap();
        let audit=ThreadResourceAudit::begin().unwrap();let before=gpu.thread_submits();
        let mut capture=FilmDensityCapture{samples:samples.clone(),result:None};let mut nested=false;
        let actual=render(&gpu,&mut capture,&mut ||{
            if !nested && gpu.thread_resident_progress().is_some_and(|p|p.stage==ResidentStage::Dir) {
                nested=true;let probe=ImageBuf::from_data(2,3,vec![from_f64(0.25);18]);
                let outputs=gpu.gaussian_blur_multi(&probe,&[0.5,0.7]);assert_eq!(outputs.len(),2);
                // Resident handles remain live during nested dispatch; summing
                // independently completed call peaks would be incorrect.
                assert!(audit.snapshot().resident_buffer_owners.active_allocations>0);
            } true
        }).unwrap();
        assert!(nested);assert_eq!(actual.data,expected.data);assert_eq!(capture.result,reference_capture.result);
        completed(&audit.finish(),gpu.thread_submits()-before,&format!("film={scan_film}/external={external}/normal"));
        let audit=ThreadResourceAudit::begin().unwrap();let before=gpu.thread_submits();let mut cancelled=false;
        let out=render(&gpu,&mut capture,&mut ||{
            if !cancelled && gpu.thread_resident_progress().is_some_and(|p|p.stage==ResidentStage::Grain) {cancelled=true;false}else{true}
        });
        assert!(cancelled && out.is_none());assert!(capture.result.is_none());
        assert!(gpu.is_healthy()&&!gpu.completion_uncertain());
        completed(&audit.finish(),gpu.thread_submits()-before,&format!("film={scan_film}/external={external}/cancel"));
        let audit=ThreadResourceAudit::begin().unwrap();let before=gpu.thread_submits();
        let again=render(&gpu,&mut capture,&mut ||true).unwrap();assert_eq!(again.data,expected.data);assert_eq!(capture.result,reference_capture.result);
        completed(&audit.finish(),gpu.thread_submits()-before,&format!("film={scan_film}/external={external}/reentry"));
        if external {
            let audit=ThreadResourceAudit::begin().unwrap();let before=gpu.thread_submits();
            let prefix=pipe.tap_gpu_film_log_tile_f32_controlled(&input,w,h,tile,&gpu,fields.as_ref(),||true).unwrap();
            let log:Vec<f32>=prefix.data.iter().map(|v|*v as f32).collect();
            let suffix=pipe.process_gpu_from_film_log_tile_f32_capture_controlled(&log,w,h,tile,&gpu,fields.as_ref(),&mut capture,||true).unwrap();
            assert_eq!(suffix.data,expected.data);assert_eq!(capture.result,reference_capture.result);
            completed(&audit.finish(),gpu.thread_submits()-before,&format!("film={scan_film}/external=true/FilmLog"));
        }
    }}
}

/// This oracle deliberately uses the ordinary whole-preview GPU-gamut path,
/// not the export/detail CPU-gamut path. Both sides use the same full grid.
#[test]
fn refined_preview_tiles_match_ordinary_whole_gpu_preview() {
    use spektrafilm_math::{image::ImageBuf,precision::from_f64};
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");return;
    };
    for (w,h,diffusion,scan_film,reversal) in [(337u32,337u32,false,false,false),(257,79,true,false,false),(79,257,false,true,false),(193,193,true,true,true)] {
        let base=pipeline(diffusion);let mut params=base.params.clone();
        params.io.scan_film=scan_film;params.camera.auto_exposure=true;params.camera.preview_metered_ev=Some(0.25);
        params.film_render.halation.boost_ev=0.5;params.film_render.halation.active=true;
        params.film_render.dir_couplers.active=true;params.camera.lens_blur_um=35.;
        params.film_render.grain.active=true;params.film_render.grain.sublayers_active=true;
        params.print_render.glare.percent=0.7;params.film_render.glare=params.print_render.glare.clone();
        let pipe=if reversal {
            let path=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
            Pipeline::new_with_spectral(load_profile_by_name(&path,"fujifilm_provia_100f").unwrap(),
                load_profile_by_name(&path,"kodak_portra_endura").unwrap(),params,&path).unwrap()
        }else{base.with_params(params)};
        let input:Vec<f32>=(0..w*h*3).map(|i|if i%113==0{4.0}else{0.01+((i*13+i/(w*3)*7)%251)as f32/300.}).collect();
        let image=ImageBuf::from_data(w,h,input.iter().map(|v|from_f64(*v as f64)).collect());
        let whole=pipe.process_resident_borrowed_controlled(&image,&gpu,||true).unwrap();
        let (cw,ch)=(67,61);let halo=pipe.banded_halo_pixels(50.);
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(w,h,cw,ch,|x,y,tw,th|Some(rect(&input,w,x,y,tw,th)),||true,&gpu).unwrap();
        let direct=pipe.process_gpu_preview_tile_f32_controlled(&input,w,h,TileContext{full_width:w,full_height:h,x:0,y:0},&gpu,Some(&fields),||true).unwrap();
        if w==337 {
            let before=gpu.thread_submits();let mut cancelled_after_submit=false;
            let cancelled=pipe.process_gpu_preview_tile_f32_controlled(&input,w,h,TileContext{full_width:w,full_height:h,x:0,y:0},&gpu,Some(&fields),||{
                if gpu.thread_submits()>before {cancelled_after_submit=true;false}else{true}
            });
            assert!(cancelled_after_submit && cancelled.is_none(),"Must cancel after a real completed GPU batch");
            assert!(gpu.is_healthy() && !gpu.completion_uncertain());
            let reentry=pipe.process_gpu_preview_tile_f32_controlled(&input,w,h,TileContext{full_width:w,full_height:h,x:0,y:0},&gpu,Some(&fields),||true).unwrap();
            assert_eq!(direct.data,reentry.data,"Completed cancellation must not poison the next preview");
        }
        let direct_error=whole.data.iter().zip(&direct.data).map(|(a,b)|(a-b).abs()).fold(0f64,f64::max);
        // Report the complete distribution before evaluating the initial strict float gate.
        let mut stitched=vec![0.;whole.data.len()];
        for y in (0..h).step_by(ch as usize) {for x in (0..w).step_by(cw as usize) {
            let x1=(x+cw).min(w);let y1=(y+ch).min(h);
            let tx=x.saturating_sub(halo);let ty=y.saturating_sub(halo);
            let tw=(x1+halo).min(w)-tx;let th=(y1+halo).min(h)-ty;
            let tile=TileContext{full_width:w,full_height:h,x:tx,y:ty};
            let data=rect(&input,w,tx,ty,tw,th);
            assert!(pipe.process_gpu_preview_tile_f32_controlled(&data,tw,th,tile,&gpu,Some(&fields),||false).is_none());
            let out=pipe.process_gpu_preview_tile_f32_controlled(&data,tw,th,tile,&gpu,Some(&fields),||true).unwrap();
            for yy in y..y1 {for xx in x..x1 {for c in 0..3usize {
                stitched[((yy*w+xx)*3)as usize+c]=out.data[(((yy-ty)*tw+xx-tx)*3)as usize+c];
            }}}
        }}
        let maximum=whole.data.iter().zip(&stitched).map(|(a,b)|(a-b).abs()).fold(0f64,f64::max);
        let rms=(whole.data.iter().zip(&stitched).map(|(a,b)|(a-b).powi(2)).sum::<f64>()/stitched.len()as f64).sqrt();
        let mean=whole.data.iter().zip(&stitched).map(|(a,b)|(a-b).abs()).sum::<f64>()/stitched.len()as f64;
        eprintln!("preview gamut oracle {w}x{h} diffusion={diffusion} grain=true direct={scan_film} reversal={reversal} halo={halo} full_tile_max={direct_error} stitched_max={maximum} stitched_mean_abs={mean} stitched_rms={rms}");
        let seam_max=direct.data.iter().zip(&stitched).map(|(a,b)|(a-b).abs()).fold(0f64,f64::max);
        let encode=|v:f64|->i32 {let x=v.clamp(0.,1.);let s=if x<=0.0031308{x*12.92}else{1.055*x.powf(1./2.4)-0.055};((s*255.+0.5)as i32).clamp(0,255)};
        let byte_diffs:Vec<i32>=whole.data.iter().zip(&stitched).map(|(a,b)|(encode(*a)-encode(*b)).abs()).collect();
        let differing=byte_diffs.iter().filter(|&&v|v!=0).count();let byte_max=byte_diffs.iter().copied().max().unwrap();
        let counts=[1e-6,1e-5,1e-4,1e-3].map(|t|whole.data.iter().zip(&stitched).filter(|(a,b)|(*a-*b).abs()>t).count());
        eprintln!("preview distribution {w}x{h} reversal={reversal} samples={} float_counts_gt_1e6_1e5_1e4_1e3={counts:?} same_fields_seam_max={seam_max} srgb8_max_delta={byte_max} srgb8_differing_samples={differing} srgb8_difference_fraction={}",stitched.len(),differing as f64/stitched.len()as f64);
        // The bounded global-field prepass intentionally retains its existing
        // f64 accumulation, whereas ordinary whole-frame WGSL accumulates f32.
        // Enabled grain can amplify isolated low-bit differences: the reversal
        // fixture has one of 111747 channels at 1.114e-4, yet every delivered
        // sRGB8 channel and every shared-field tile seam is identical. Gate the
        // actual display contract plus tight RMS, sparse tails and a bounded
        // float maximum; do not alter either scientific path to chase rounding.
        assert_eq!(byte_max,0,"Delivered sRGB8 preview differs");
        assert_eq!(differing,0,"Delivered sRGB8 preview differs");
        assert_eq!(seam_max,0.,"Shared-field tile seam differs");
        assert!(maximum<=2e-4 && direct_error<=2e-4 && rms<1e-5 && counts[2]<=1,
            "ordinary preview floating-point envelope max={maximum} rms={rms} direct={direct_error} tails={counts:?}");
    }
}
