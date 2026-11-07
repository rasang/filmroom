//! Bounded ordinary whole-photo preview. No high-resolution CPU fallback and no
//! stage-cache candidates. JNI-owned full-frame buffers are charged explicitly.
use super::*;
pub const MAX_EDGE:u32=1536;
const FULL_FRAME_BYTES_PER_PIXEL:u64=60;
#[derive(Clone,Copy,Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct PlanMetrics {
    pub abi:u32,pub estimated_peak_bytes:u64,pub external_bytes:u64,
    pub full_frame_reserve_bytes:u64,pub global_field_reserve_bytes:u64,
    pub core_width:u32,pub core_height:u32,pub halo:u32,
    pub expanded_width:u32,pub expanded_height:u32,
}
fn error(code:&'static str)->EngineError {EngineError::new(code,"Refined preview unavailable within the bounded GPU contract; retain the lower-resolution preview")}
pub fn required_bytes(width:i32,height:i32)->Result<usize> {
    if width<=0 || height<=0 || width as u32>MAX_EDGE || height as u32>MAX_EDGE {return Err(error("INVALID_DIMENSIONS"));}
    Ok(width as usize*height as usize*12)
}
/// 24 B/pixel native linear input + output, plus 36 B/pixel conservative
/// GPU-preview-only scanner/unsharp scratch and conversion/lifecycle allowance
/// (bounded above by the full frame even when tiles are much smaller). Actual external source/input and
/// destination allocations are charged additionally, including stride/capacity.
/// No full-frame f64 ImageBuf or cache candidate is created in this path.
fn outside_bytes(width:u32,height:u32,external:u64)->Result<u64> {
    required_bytes(width as i32,height as i32)?;
    (width as u64*height as u64).checked_mul(FULL_FRAME_BYTES_PER_PIXEL)
        .and_then(|n|n.checked_add(external)).ok_or_else(||error("MEMORY_BUDGET_TOO_SMALL"))
}
pub fn preflight(width:i32,height:i32,params:&MobileParams,external:u64)->Result<()> {
    required_bytes(width,height)?;params.validate()?;
    if let Some(p)=&params.pre_imaging {p.check_frame(width as u32,height as u32)?;}
    if params.mode!="preview" || !params.use_gpu {return Err(error("GPU_UNAVAILABLE"));}
    if params.auto_exposure && params.preview_metering.is_none() {return Err(error("INVALID_PARAMS"));}
    let outside=outside_bytes(width as u32,height as u32,external)?;
    if outside.checked_add(MEMORY_FIXED_ESTIMATE_BYTES).is_none_or(|n|n>=params.memory_budget_mi_b as u64*1024*1024) {return Err(error("MEMORY_BUDGET_TOO_SMALL"));}
    #[cfg(not(feature="adreno-gpu"))] return Err(error("GPU_UNAVAILABLE"));
    #[cfg(feature="adreno-gpu")] {
        gpu_completion_guard()?;
        if full_chain_gpu().is_none_or(|g|!g.is_healthy()) {return Err(error("GPU_UNAVAILABLE"));}
        Ok(())
    }
}
#[cfg(not(feature="adreno-gpu"))]
pub fn render(_engine:&Engine,_generation:u64,_input:&[f32],_width:u32,_height:u32,_params:&MobileParams,_external:u64)->Result<RenderOutput> {Err(error("GPU_UNAVAILABLE"))}
#[cfg(feature="adreno-gpu")]
pub fn render(engine:&Engine,generation:u64,input:&[f32],width:u32,height:u32,params:&MobileParams,external:u64)->Result<RenderOutput> {
    use spektrafilm_core::pipeline::{DiffusionScatteredFields,TileContext};
    preflight(width as i32,height as i32,params,external)?;
    if input.len()!=required_bytes(width as i32,height as i32)?/4 || input.iter().any(|v|!v.is_finite()) {return Err(error("INVALID_PIXELS"));}
    check_cancellation(engine,generation)?;
    let _interactive=crate::export_execution::interactive_work();
    // The separately charged quick-frame cache is not borrowed or republished.
    let start=Instant::now();let before=engine.prepared_spectral_cache.stats();
    let prepared=cached_pipeline(engine,params,generation)?;
    // Resolve full-grid pitch once, not by cloning/rebuilding the gamut table
    // for every prepass/main tile. Never mutate the cached quick-frame pipeline.
    let pitch=prepared.params.camera.pixel_size_um_override.unwrap_or_else(||spektrafilm_core::stages::filming::pixel_size_um(prepared.params.camera.film_format_mm,width,height));
    let mut runtime=prepared.params.clone();runtime.camera.pixel_size_um_override=Some(pitch);
    let pipe=prepared.as_ref().clone().with_params(runtime);
    let after=engine.prepared_spectral_cache.stats();
    let prepared_spectral=PreparedAssetFrameMetrics {pipeline_prepare_ms:start.elapsed().as_secs_f64()*1000.,
        front_hits:after.hits.saturating_sub(before.hits),front_misses:after.misses.saturating_sub(before.misses),
        front_build_nanoseconds:after.build_nanoseconds.saturating_sub(before.build_nanoseconds),
        calibration_hits:after.calibration_hits.saturating_sub(before.calibration_hits),
        calibration_misses:after.calibration_misses.saturating_sub(before.calibration_misses),
        calibration_build_nanoseconds:after.calibration_build_nanoseconds.saturating_sub(before.calibration_build_nanoseconds),cache:after};
    let gpu=full_chain_gpu().filter(|g|g.is_healthy()).ok_or_else(||error("GPU_UNAVAILABLE"))?;
    let halo=pipe.banded_halo_pixels(pitch);
    let field_bytes=pipe.gpu_diffusion_working_bytes(width,height)
        .and_then(|n|n.checked_add(std::mem::size_of::<DiffusionScatteredFields>()as u64)).ok_or_else(||error("MEMORY_BUDGET_TOO_SMALL"))?;
    let reserve=outside_bytes(width,height,external)?.checked_add(field_bytes).ok_or_else(||error("MEMORY_BUDGET_TOO_SMALL"))?;
    let plan=crate::gpu_export::tile_plan(width,height,halo,params.memory_budget_mi_b,gpu.max_storage_buffer_binding_size(),reserve).map_err(|e|if e.code=="GPU_MEMORY_BUDGET"{error("MEMORY_BUDGET_TOO_SMALL")}else{e})?;
    let prepare_ms=start.elapsed().as_secs_f64()*1000.;let science=Instant::now();
    let read=|x:u32,y:u32,w:u32,h:u32|->Option<Vec<f32>> {
        if check_cancellation(engine,generation).is_err(){return None;}
        let mut out=Vec::new();out.try_reserve_exact(w as usize*h as usize*3).ok()?;
        for yy in y..y+h {let begin=((yy*width+x)*3)as usize;out.extend_from_slice(&input[begin..begin+w as usize*3]);}
        Some(out)
    };
    let submits=gpu.thread_submits();
    let attempt=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||->Result<Vec<f32>> {
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(width,height,plan.width,plan.rows,read,
            ||check_cancellation(engine,generation).is_ok(),gpu).ok_or_else(||error("GPU_EXECUTION_FAILED"))?;
        check_cancellation(engine,generation)?;
        if fields.retained_working_bytes().is_none_or(|n|n>field_bytes){return Err(error("MEMORY_BUDGET_TOO_SMALL"));}
        let mut pixels=Vec::new();pixels.try_reserve_exact(input.len()).map_err(|_|error("MEMORY_BUDGET_TOO_SMALL"))?;pixels.resize(input.len(),0.);
        let exposure=2f64.powf(params.brightness_ev);
        for y in (0..height).step_by(plan.rows as usize) {for x in (0..width).step_by(plan.width as usize) {
            check_cancellation(engine,generation)?;
            let endx=(x+plan.width).min(width);let endy=(y+plan.rows).min(height);
            let left=x.saturating_sub(halo);let top=y.saturating_sub(halo);
            let w=endx.saturating_add(halo).min(width)-left;let h=endy.saturating_add(halo).min(height)-top;
            if w>plan.expanded_width||h>plan.expanded_rows{return Err(error("MEMORY_BUDGET_TOO_SMALL"));}
            let data=read(left,top,w,h).ok_or_else(||error("CANCELLED"))?;
            let out=pipe.process_gpu_preview_tile_f32_controlled(&data,w,h,TileContext{full_width:width,full_height:height,x:left,y:top},gpu,Some(&fields),
                ||check_cancellation(engine,generation).is_ok()).ok_or_else(||error("GPU_EXECUTION_FAILED"))?;
            check_cancellation(engine,generation)?;
            for yy in y..endy {for xx in x..endx {
                let src=(((yy-top)*w+xx-left)*3)as usize;let dst=((yy*width+xx)*3)as usize;
                let rgb=spektrafilm_core::output_color::to_linear_srgb([out.data[src]as f64,out.data[src+1]as f64,out.data[src+2]as f64],&params.output_color_space);
                for c in 0..3 {let value=(rgb[c]*exposure)as f32;if !value.is_finite(){return Err(error("NONFINITE_OUTPUT"));}pixels[dst+c]=value;}
            }}
        }}
        Ok(pixels)
    }));
    // Quarantine and cancellation take precedence over any rendering error or
    // caught panic. Never start a CPU fallback or a second high-res GPU attempt.
    check_cancellation(engine,generation)?;
    let pixels=attempt.map_err(|_|error("GPU_EXECUTION_FAILED"))??;
    let batches=gpu.thread_submits().saturating_sub(submits);
    if batches==0{return Err(error("GPU_EXECUTION_FAILED"));}
    Ok(RenderOutput{refined_plan:Some(PlanMetrics{abi:1,estimated_peak_bytes:plan.estimated_bytes,external_bytes:external,
        full_frame_reserve_bytes:width as u64*height as u64*FULL_FRAME_BYTES_PER_PIXEL,global_field_reserve_bytes:field_bytes,
        core_width:plan.width,core_height:plan.rows,halo,expanded_width:plan.expanded_width,expanded_height:plan.expanded_rows}),prepared_spectral,preview_cache:PreviewStageMetrics{prepare_ms,science_ms:science.elapsed().as_secs_f64()*1000.,..PreviewStageMetrics::default()},
        metered_ev:params.preview_metering.as_ref().map(|m|m.ev),gpu:resident_diagnostics(gpu,batches,"tiled-chain"),pixels,
        elapsed_ms:start.elapsed().as_secs_f64()*1000.,warnings:preview_warnings()})
}
#[cfg(test)] mod tests {
    use super::*;
    #[test] fn refined_geometry_is_separate_from_legacy(){assert_eq!(required_bytes(1536,1536).unwrap(),1536*1536*12);assert!(super::super::required_bytes(1536,1536).is_err());for (w,h) in [(0,1),(1,0),(1537,1),(1,1537),(-1,1)]{assert!(required_bytes(w,h).is_err());}}
    #[test] fn external_buffers_and_overflow_are_charged(){assert_eq!(outside_bytes(1536,1536,1536*1536*16).unwrap(),1536*1536*76);assert!(outside_bytes(1,1,u64::MAX).is_err());}
    #[cfg(feature="adreno-gpu")]
    #[test] fn tile_admission_includes_all_full_frame_buffers() {
        for (w,h) in [(1536u32,1536u32),(1536,512),(512,1536)] {
            let outside=outside_bytes(w,h,w as u64*h as u64*16).unwrap();
            for budget in [128u32,256,384,512] {
                if let Ok(plan)=crate::gpu_export::tile_plan(w,h,32,budget,128*1024*1024,outside) {
                    assert!(plan.estimated_bytes<=budget as u64*1024*1024);
                    assert_eq!(plan.estimated_bytes,MEMORY_FIXED_ESTIMATE_BYTES+outside+256*plan.expanded_width as u64*plan.expanded_rows as u64);
                }
            }
        }
        let outside=outside_bytes(1536,1536,1536*1536*16).unwrap();
        assert!(crate::gpu_export::tile_plan(1536,1536,32,256,128*1024*1024,outside).is_err());
        assert!(crate::gpu_export::tile_plan(1536,1536,32,384,128*1024*1024,outside).is_ok());
        assert!(crate::gpu_export::tile_plan(1536,1536,1536,384,128*1024*1024,outside).is_err());
    }
    #[cfg(feature="adreno-gpu")]
    fn actual_refined_frame(width:u32,height:u32,diffusion:bool) {
        assert_eq!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Explicit required-GPU validation only");
        assert!(full_chain_gpu().is_some_and(|g|g.is_healthy()),"Required GPU unavailable");
        let path=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let id=create(path.to_str().unwrap()).unwrap();let engine=get(id).unwrap();
        let lock=engine.render_lock.lock().unwrap();
        let mut p=MobileParams::default();p.memory_budget_mi_b=512;
        p.highlight_boost_ev=0.4;p.scan_glare_percent=0.7;p.scan_blur=0.5;p.scan_sharpen_amount=0.3;
        if diffusion {p.diffusion_filter="glimmerglass".into();p.print_diffusion_filter="black_pro_mist".into();p.diffusion_strength=0.8;p.print_diffusion_strength=0.8;}
        let input:Vec<f32>=(0..width*height*3).map(|i|if i%113==0{2.0}else{0.02+((i*13+i/(width*3)*7)%251)as f32/300.}).collect();
        let external=width as u64*height as u64*16;
        cancel(id);
        let cancelled=render(&engine,0,&input,width,height,&p,external).err().unwrap();
        assert_eq!(cancelled.code,"CANCELLED");
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        let result=render(&engine,generation,&input,width,height,&p,external).unwrap();
        assert_eq!(result.pixels.len(),width as usize*height as usize*3);
        assert!(result.pixels.iter().all(|v|v.is_finite()));
        assert!(result.gpu.active && result.gpu.gpu_batches>0);assert_eq!(result.gpu.cpu_fallbacks,0);
        let plan=result.refined_plan.unwrap();assert!(plan.estimated_peak_bytes<=512*1024*1024);
        assert_eq!(plan.external_bytes,external);assert_eq!(result.preview_cache.retained_bytes,0);
        assert!(full_chain_gpu().unwrap().is_healthy());
        eprintln!("actual refined {width}x{height} diffusion={diffusion} ms={} batches={} plan={plan:?}",result.elapsed_ms,result.gpu.gpu_batches);
        drop(lock);destroy(id);
    }
    #[cfg(feature="adreno-gpu")]
    #[test] #[ignore="Explicit 1536 GPU execution, run alone after bounded unit/oracle tests"]
    fn actual_1536_square_bounded_gpu_and_cancel_reentry(){actual_refined_frame(1536,1536,false);}
    #[cfg(feature="adreno-gpu")]
    #[test] #[ignore="Explicit 1536 dual-diffusion GPU execution, run alone"]
    fn actual_1536_dual_diffusion_bounded_gpu(){actual_refined_frame(1536,1024,true);}
    #[test] fn low_budget_and_missing_shared_ae_fail_before_gpu(){let mut p=MobileParams::default();p.memory_budget_mi_b=128;assert_eq!(preflight(1536,1536,&p,0).unwrap_err().code,"MEMORY_BUDGET_TOO_SMALL");p.auto_exposure=true;assert_eq!(preflight(512,512,&p,0).unwrap_err().code,"INVALID_PARAMS");}
}
