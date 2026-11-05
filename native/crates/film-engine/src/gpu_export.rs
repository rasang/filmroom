//! v41 approximate GPU export, with original-size geometry and bounded rectangular tiles.
//! CPU checkpoints retain their original namespace and are never reinterpreted.
use std::{fs::{self,File,OpenOptions},io::{Read,Seek,SeekFrom,Write},path::{Path,PathBuf},time::Instant};
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::Profile};
use crate::{mobile::{EngineError,Result},streamed::{StreamedMetrics,StageMetrics},streamed_progress::{ProgressSink,GpuWorkProgress}};

pub(crate) const ALGORITHM:&str=spektrafilm_gpu::RESIDENT_APPROXIMATION_VERSION;
const FIXED_BYTES:u64=96*1024*1024;
const BAND_BYTES_PER_PIXEL:u64=256;
#[derive(Clone,Copy,Debug)]
pub(crate) struct BandPlan {pub rows:u32,pub expanded_rows:u32,pub estimated_bytes:u64,pub whole:bool}
fn err(code:&'static str)->EngineError {EngineError::new(code,"GPU export could not complete within its validated contract")}
fn io(e:std::io::Error)->EngineError {EngineError::new(if e.kind()==std::io::ErrorKind::Interrupted{"CANCELLED"}else{"IO_ERROR"},"GPU spool operation failed")}
fn cancel(c:&mut impl FnMut()->bool)->Result<()> {if c(){Err(err("CANCELLED"))}else{Ok(())}}
/// Joint host/device/staging/driver estimate, not measured RSS. More budget
/// changes executable geometry; no MemAvailable override or minimum allocation.
pub(crate) fn band_plan(width:u32,height:u32,halo:u32,budget_mib:u32,binding:u64,fields:u64)->Result<BandPlan> {
    if width==0||height==0||!(128..=512).contains(&budget_mib){return Err(err("INVALID_BUDGET"));}
    let total=budget_mib as u64*1024*1024;
    let fixed=FIXED_BYTES.checked_add(fields).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let row=width as u64*BAND_BYTES_PER_PIXEL;
    let available=total.checked_sub(fixed).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let memory_rows=available/row;
    let binding_rows=binding.saturating_mul(9)/10/(width as u64*12);
    let fit=memory_rows.min(binding_rows).min(height as u64);
    // Small frames use one resident chain without artificial edge halos.
    if fit>=height as u64 {return Ok(BandPlan{rows:height,expanded_rows:height,estimated_bytes:fixed+row*height as u64,whole:true});}
    let pad=halo as u64*2;
    if memory_rows<=pad{return Err(err("GPU_MEMORY_BUDGET"));}
    if binding_rows<=pad{return Err(err("GPU_BINDING_LIMIT"));}
    let rows=(fit-pad).min(1024) as u32;
    if rows==0{return Err(err("GPU_MEMORY_BUDGET"));}
    let expanded=(rows as u64+pad).min(height as u64) as u32;
    Ok(BandPlan{rows,expanded_rows:expanded,estimated_bytes:fixed+row*expanded as u64,whole:false})
}
#[derive(Clone,Copy,Debug)]
pub(crate) struct TilePlan {pub width:u32,pub rows:u32,pub expanded_width:u32,pub expanded_rows:u32,pub estimated_bytes:u64,pub whole:bool}
/// Maximum live rectangular allocation, including both halo axes. All global
/// fields remain charged while every tile executes; storage is independently bounded.
pub(crate) fn tile_plan(width:u32,height:u32,halo:u32,budget_mib:u32,binding:u64,fields:u64)->Result<TilePlan> {
    if width==0||height==0||width>40000||height>40000||!(128..=512).contains(&budget_mib){return Err(err("INVALID_BUDGET"));}
    let fixed=FIXED_BYTES.checked_add(fields).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let available=(budget_mib as u64*1024*1024).checked_sub(fixed).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let memory_pixels=available/BAND_BYTES_PER_PIXEL;
    let binding_pixels=binding.saturating_mul(9)/10/12;
    let pixels=memory_pixels.min(binding_pixels);
    if width as u64*height as u64<=pixels{return Ok(TilePlan{width,rows:height,expanded_width:width,expanded_rows:height,estimated_bytes:fixed+width as u64*height as u64*BAND_BYTES_PER_PIXEL,whole:true});}
    let pad=halo as u64*2;
    let mut best:Option<TilePlan>=None;
    for core_w in 1..=width {
        // Keep jobs responsive, except when using an entire short dimension.
        if core_w>1024&&core_w!=width{continue;}
        let ew=(core_w as u64+pad).min(width as u64);
        let max_h=(pixels/ew).min(height as u64);
        let core_h=if max_h==height as u64{height as u64}else{max_h.saturating_sub(pad).min(1024)};
        if core_h==0{continue;}
        let eh=(core_h+pad).min(height as u64);
        let candidate=TilePlan{width:core_w,rows:core_h as u32,expanded_width:ew as u32,expanded_rows:eh as u32,estimated_bytes:fixed+ew*eh*BAND_BYTES_PER_PIXEL,whole:false};
        if best.map_or(true,|b|core_w as u64*core_h>b.width as u64*b.rows as u64){best=Some(candidate);}
    }
    best.ok_or_else(||err(if binding_pixels<memory_pixels{"GPU_BINDING_LIMIT"}else{"GPU_MEMORY_BUDGET"}))
}
struct OutputGuard {path:PathBuf,keep:bool}
impl Drop for OutputGuard {fn drop(&mut self){if !self.keep {let _=fs::remove_file(&self.path);if let Some(path)=crate::scan_preview::path(&self.path){let _=fs::remove_file(&path);let _=fs::remove_file(path.with_extension("png.tmp"));}}}}
/// Explicit source row stride; only the requested rectangle is materialized.
fn read_rect(path:&Path,full_width:u32,full_height:u32,x:u32,y:u32,width:u32,height:u32)->Result<Vec<f32>> {
    if width==0||height==0||x.checked_add(width).is_none_or(|n|n>full_width)||y.checked_add(height).is_none_or(|n|n>full_height){return Err(err("INVALID_OUTPUT_SHAPE"));}
    let len=(width as usize).checked_mul(height as usize).and_then(|n|n.checked_mul(3)).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let mut values=Vec::new();values.try_reserve_exact(len).map_err(|_|err("GPU_MEMORY_BUDGET"))?;values.resize(len,0f32);
    let mut file=File::open(path).map_err(io)?;
    if file.metadata().map_err(io)?.len()!=full_width as u64*full_height as u64*12{return Err(err("INVALID_PIXELS"));}
    for row in 0..height {
        file.seek(SeekFrom::Start(((y+row)as u64*full_width as u64+x as u64)*12)).map_err(io)?;
        let offset=row as usize*width as usize*3;
        file.read_exact(bytemuck::cast_slice_mut(&mut values[offset..offset+width as usize*3])).map_err(io)?;
    }
    if cfg!(target_endian="big"){for value in &mut values {*value=f32::from_bits(value.to_bits().swap_bytes());}}
    if values.iter().any(|v|!v.is_finite()){return Err(err("INVALID_PIXELS"));}
    Ok(values)
}
#[allow(clippy::too_many_arguments)]
fn write_core(out:&mut File,rendered:&spektrafilm_math::image::ImageBuf,full_width:u32,left:u32,top:u32,x:u32,y:u32,right:u32,end:u32,exposure:f64,cancelled:&mut impl FnMut()->bool)->Result<u64>{
    if x<left||y<top||right<=x||end<=y||right>full_width||right-left>rendered.width||end-top>rendered.height||rendered.data.len()!=rendered.width as usize*rendered.height as usize*3{return Err(err("INVALID_OUTPUT_SHAPE"));}
    // One converted row, with explicit source/destination strides.
    let mut bytes=Vec::new();bytes.try_reserve_exact((right-x)as usize*12).map_err(|_|err("GPU_MEMORY_BUDGET"))?;
    let mut written=0;
    for row in y..end {
        cancel(cancelled)?;bytes.clear();
        let begin=(((row-top)as usize*rendered.width as usize)+(x-left)as usize)*3;
        for &value in &rendered.data[begin..begin+(right-x)as usize*3] {let value=(value as f64*exposure)as f32;if !value.is_finite(){return Err(err("NONFINITE_OUTPUT"));}bytes.extend_from_slice(&value.to_le_bytes());}
        out.seek(SeekFrom::Start((row as u64*full_width as u64+x as u64)*12)).map_err(io)?;
        out.write_all(&bytes).map_err(io)?;written+=bytes.len()as u64;
    }
    Ok(written)
}
fn metric(width:u32,height:u32,budget:u32,start:Instant,setup:f64,ev:Option<f32>,gpu:crate::adreno::GpuDiagnostics,plan:TilePlan,read:u64,written:u64,stages:Vec<StageMetrics>)->StreamedMetrics {
    StreamedMetrics{export_execution:None,metered_ev:ev,gpu,width,height,pixel_count:width as u64*height as u64,memory_budget_mi_b:budget,elapsed_ms:start.elapsed().as_secs_f64()*1000.,setup_ms:setup,estimated_scratch_bytes:0,peak_logical_scratch_bytes:0,spatial_peak_explicit_buffer_bytes:plan.estimated_bytes,spatial_bytes_read:read,spatial_bytes_written:written,spatial_pass_count:0,pointwise_chunk_rows:plan.rows,gpu_tiles:Some(crate::streamed::GpuTileMetrics{core_width:plan.width,core_height:plan.rows,expanded_width:plan.expanded_width,expanded_height:plan.expanded_rows}),lut_storage_bytes:0,stages,algorithm:ALGORITHM,grain_mode:"v41-approximate-global-coordinate-grain",memory_policy:"Conservative joint host/device/staging/driver estimate; 256B/expanded pixel + 96MiB + retained global diffusion fields; budget determines actual rectangular tile geometry"}
}
#[allow(clippy::too_many_arguments)]
pub(crate) fn export(params:&RuntimeParams,film_stock:&str,paper_stock:&str,film:&Profile,paper:&Profile,data_dir:&Path,input:&Path,output:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,budget:u32,brightness:f64,cancelled:&mut impl FnMut()->bool,progress:&mut impl ProgressSink,early_preview:&mut Option<crate::early_negative_preview::PreviewHandle>)->Result<StreamedMetrics> {
    let start=Instant::now();cancel(cancelled)?;
    let mut output_guard=OutputGuard{path:output.to_owned(),keep:false};
    // Checkpoint configuration explicitly includes the algorithm. CPU manifests
    // live one level above and are selected before this entry is called.
    let mut checkpoints=if let Some(root)=checkpoint_dir {
        if !root.exists(){fs::create_dir(root).map_err(io)?;}
        if !fs::symlink_metadata(root).map_err(io)?.is_dir(){return Err(err("CHECKPOINT_INVALID"));}
        let config=serde_json::to_string(&(ALGORITHM,film_stock,paper_stock,params,brightness)).map_err(|_|err("INVALID_PARAMETERS"))?;
        let identity=crate::checkpoint::Identity::new(data_dir,input,width,height,config,cancelled).map_err(io)?;
        Some(crate::checkpoint::Checkpoints::open(&root.join("gpu-v41-approx-tiles-v2"),identity,cancelled).map_err(io)?)
    }else{None};
    if let Some(c)=checkpoints.as_ref().filter(|c|c.stage()==6) {
        progress.begin_stage(6,1,"output");progress.begin_work("output");
        c.restore(&[output.to_owned()],cancelled).map_err(io)?;
        cancel(cancelled)?;progress.end_work();progress.finish_stage();
        let mut gpu=crate::adreno::AdrenoDensityBackend::with_enabled(false).diagnostics();gpu.requested=true;gpu.reason="gpu_checkpoint_restored".into();gpu.accelerated_stage="tiled-chain";
        if let Err(e)=crate::scan_preview::publish_restored(output,width,height,&params.io.output_color_space,cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}
        output_guard.keep=true;
        return Ok(metric(width,height,budget,start,0.,c.metered_ev(),gpu,TilePlan{width:0,rows:0,expanded_width:0,expanded_rows:0,estimated_bytes:0,whole:false},0,width as u64*height as u64*12,vec![]));
    }
    let gpu=crate::mobile::full_chain_gpu().filter(|g|g.is_healthy()).ok_or_else(||err(if crate::mobile::full_chain_unsupported(){"GPU_UNSUPPORTED_ADAPTER"}else{"GPU_UNAVAILABLE"}))?;
    let mut p=params.clone();let mut stages=Vec::new();
    let diffusion=p.camera.diffusion_filter.active||(!p.io.scan_film&&p.enlarger.diffusion_filter.active);
    let global_effect=diffusion||p.film_render.halation.boost_ev!=0.;
    let ev=if p.camera.auto_exposure {
        progress.begin_stage(2,1,"auto_exposure");progress.begin_work("auto_exposure");let at=Instant::now();
        let matrix=spektrafilm_core::stages::filming::input_colorspace_to_xyz(&p.io.input_color_space);
        let ev=crate::streamed_global::auto_exposure_ev(input,width,height,&p.camera.auto_exposure_method,&matrix,&mut *cancelled).map_err(io)?;
        if !ev.is_finite(){return Err(err("NONFINITE_OUTPUT"));}
        p.camera.exposure_compensation_ev+=ev;p.camera.auto_exposure=false;
        progress.end_work();progress.finish_stage();stages.push(StageMetrics{stage:"auto_exposure",elapsed_ms:at.elapsed().as_secs_f64()*1000.});Some(ev)
    }else{None};
    cancel(cancelled)?;
    let pipe=std::sync::Arc::new(Pipeline::new_with_spectral(film.clone(),paper.clone(),p,data_dir).map_err(|_|err("PIPELINE_ERROR"))?);
    let pitch=params.camera.pixel_size_um_override.unwrap_or(params.camera.film_format_mm*1000./width.max(height)as f32);
    let halo=pipe.banded_halo_pixels(pitch);
    let fields_bytes=pipe.gpu_diffusion_working_bytes(width,height).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let preview_bytes=crate::early_negative_preview::required_workspace_bytes(&pipe).unwrap_or(u64::MAX);
    let preview_mib=preview_bytes.checked_add(1024*1024-1).map(|v|v/(1024*1024));
    // Admit the background worker only when both GPU prepass and a possible
    // CPU fallback can retain its full allowance without waiting for it.
    let candidate_fields=fields_bytes.checked_add(preview_bytes);
    let early_admitted=preview_mib.is_some_and(|m|m<=u64::from(budget.saturating_sub(128))) && checkpoint_dir.and_then(Path::parent).is_some() && candidate_fields.is_some_and(|f|tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),f).is_ok());
    if early_admitted {
        if let Some(dir)=checkpoint_dir.and_then(Path::parent) {
            *early_preview=crate::early_negative_preview::spawn(input,dir,std::sync::Arc::clone(&pipe),width,height,preview_bytes).ok().flatten();
        }
    }else if let Some(dir)=checkpoint_dir.and_then(Path::parent) {
        let _=crate::negative_preview::mark_current_status(dir,"skipped_budget",true);
    }
    let prepass_fields=fields_bytes.checked_add(if early_preview.is_some(){preview_bytes}else{0}).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let prepass_plan=tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),prepass_fields)?;
    let setup_ms=start.elapsed().as_secs_f64()*1000.;let submits_before=gpu.thread_submits();
    let mut bytes_read=0u64;
    let prepass=global_effect&&!prepass_plan.whole;
    progress.begin_stage(6,2+u64::from(prepass),"gpu_tiled_chain");
    let fields=if prepass {
        progress.begin_work("gpu_diffusion_prepass");let at=Instant::now();let mut read_error=None;
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(width,height,prepass_plan.width,prepass_plan.rows,
            |x,y,w,h|match read_rect(input,width,height,x,y,w,h){Ok(v)=>{bytes_read+=w as u64*h as u64*12;Some(v)},Err(e)=>{read_error=Some(e);None}},
            ||!cancelled(),gpu);
        if let Some(e)=read_error{return Err(e);}cancel(cancelled)?;
        let fields=fields.ok_or_else(||err("GPU_EXECUTION_FAILED"))?;
        progress.end_work();stages.push(StageMetrics{stage:"gpu_diffusion_prepass",elapsed_ms:at.elapsed().as_secs_f64()*1000.});Some(fields)
    }else{None};
    cancel(cancelled)?;
    // The prepass construction peak is gone. Charge its retained fields on
    // the main pass, and the optional negative buffers on top of that.
    let retained=if let Some(ref retained)=fields {retained.retained_working_bytes().ok_or_else(||err("GPU_MEMORY_BUDGET"))?}else{fields_bytes};
    let early_live=early_preview.as_ref().is_some_and(|h|h.running.load(std::sync::atomic::Ordering::Acquire));
    let main_fields=retained.checked_add(if early_live{preview_bytes}else{0}).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let mut plan=if fields.is_some() {tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),main_fields)?}else{prepass_plan};
    if fields.is_some(){plan.whole=false;}
    let mut negative=if pipe.film.info.film_type=="negative" && checkpoint_dir.and_then(Path::parent).is_some() {
        crate::negative_preview::GpuDensityAssembly::new(width,height)
    }else{None};
    let mut exact_unavailable=if pipe.film.info.film_type=="negative" {"skipped_budget"}else{"failed"};
    if let Some(assembly)=negative.as_ref() {
        let mut reserve=assembly.peak_reservation(plan.width,plan.rows).unwrap_or(u64::MAX);
        let mut chosen=None;
        for _ in 0..8 {
            let Some(with_preview)=main_fields.checked_add(reserve) else {break};
            let Ok(mut candidate)=tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),with_preview) else {break};
            // Never change a whole-frame global-effect export into tiles after
            // its prepass decision: that would require fields not computed.
            if global_effect && !prepass && !candidate.whole {break;}
            if fields.is_some(){candidate.whole=false;}
            let Some(actual)=assembly.peak_reservation(candidate.width,candidate.rows) else {break};
            if actual<=reserve {chosen=Some(candidate);break;}
            reserve=actual;
        }
        if let Some(candidate)=chosen {plan=candidate;exact_unavailable="failed";}else{negative=None;}
    }
    plan.estimated_bytes=plan.estimated_bytes.max(prepass_plan.estimated_bytes);
    let stage=if plan.whole{"gpu_full_chain"}else{"gpu_tiled_chain"};
    progress.begin_work(stage);let at=Instant::now();
    let mut out=OpenOptions::new().write(true).create_new(true).open(output).map_err(io)?;
    let exposure=2f64.powf(brightness);let mut written=0u64;let mut y=0u32;
    let tile_total=width.div_ceil(plan.width)*height.div_ceil(plan.rows);let mut tile_index=0;

    while y<height {
        let end=y.saturating_add(plan.rows).min(height);
        let top=y.saturating_sub(halo);let bottom=end.saturating_add(halo).min(height);
        let mut x=0;
        while x<width {
            cancel(cancelled)?;
            let right=x.saturating_add(plan.width).min(width);
            let left=x.saturating_sub(halo);let outer_right=right.saturating_add(halo).min(width);
            let (ew,eh)=(outer_right-left,bottom-top);
            if ew>plan.expanded_width||eh>plan.expanded_rows{return Err(err("GPU_MEMORY_BUDGET"));}
            tile_index+=1;
            progress.gpu_event(GpuWorkProgress{tile_index,tile_total,completed_submits:gpu.thread_submits().saturating_sub(submits_before),..GpuWorkProgress::default()});
            let values=read_rect(input,width,height,left,top,ew,eh)?;bytes_read+=values.len()as u64*4;
            cancel(cancelled)?;
            #[cfg(test)]
            if std::env::var("FILMROOM_LARGE_GPU_SMOKE").as_deref()==Ok("1"){eprintln!("GPU_TILE_BEGIN x={x} y={y} core={}x{} expanded={ew}x{eh} completedSubmits={} elapsedSeconds={}",right-x,end-y,gpu.thread_submits(),start.elapsed().as_secs_f64());}
            let tile_submits=gpu.thread_submits();let mut reported_batch=0;
            let mut checkpoint=||{
                if gpu.thread_submits()>tile_submits {if let Some(batch)=gpu.thread_resident_progress(){
                    if batch.completed_batches>reported_batch {reported_batch=batch.completed_batches;
                        progress.gpu_event(GpuWorkProgress{phase:Some(batch.stage.as_str()),tile_index,tile_total,batch_index:batch.completed_batches,batch_elapsed_ms:batch.batch_elapsed_ms,chain_wait_ms:batch.chain_gpu_wait_ms,completed_submits:gpu.thread_submits().saturating_sub(submits_before),..GpuWorkProgress::default()});
                    }
                }}
                !cancelled()
            };
            let request=negative.as_ref().map(|a|a.samples_for_core(left,top,x,y,right,end)).unwrap_or_default();
            let mut capture=(!request.is_empty()).then(||spektrafilm_gpu::FilmDensityCapture{samples:request,result:None});
            let tile=spektrafilm_core::pipeline::TileContext{full_width:width,full_height:height,x:left,y:top};
            let rendered=match (plan.whole,capture.as_mut()) {
                (true,Some(c))=>pipe.process_gpu_resident_cpu_gamut_f32_capture_controlled(&values,width,height,gpu,c,&mut checkpoint),
                (false,Some(c))=>pipe.process_gpu_resident_tile_f32_capture_controlled(&values,ew,eh,tile,gpu,fields.as_ref(),c,&mut checkpoint),
                (true,None)=>pipe.process_gpu_resident_cpu_gamut_f32_controlled(&values,width,height,gpu,&mut checkpoint),
                (false,None)=>pipe.process_gpu_resident_tile_f32_controlled(&values,ew,eh,tile,gpu,fields.as_ref(),&mut checkpoint),
            };
            if let Some(c)=capture {
                let accepted=negative.as_mut().is_some_and(|a|c.result.as_ref().is_some_and(|v|a.add(&c.samples,v)));
                if !accepted {negative=None;exact_unavailable="failed";}
            }

            drop(values);cancel(cancelled)?;
            let rendered=rendered.ok_or_else(||err("GPU_EXECUTION_FAILED"))?;
            if rendered.width!=ew||rendered.height!=eh||rendered.data.len()!=ew as usize*eh as usize*3{return Err(err("INVALID_OUTPUT_SHAPE"));}
            if rendered.data.iter().any(|v|!v.is_finite()){return Err(err("NONFINITE_OUTPUT"));}
            #[cfg(test)]
            if std::env::var("FILMROOM_LARGE_GPU_SMOKE").as_deref()==Ok("1"){eprintln!("GPU_TILE_COMPLETE x={x} y={y} completedSubmits={} elapsedSeconds={}",gpu.thread_submits(),start.elapsed().as_secs_f64());}
            written+=write_core(&mut out,&rendered,width,left,top,x,y,right,end,exposure,cancelled)?;
            progress.pass(stage,y as u64*width as u64+(end-y)as u64*right as u64,height as u64*width as u64);
            x=right;
        }
        y=end;
    }
    out.flush().map_err(io)?;out.sync_all().map_err(io)?;drop(out);cancel(cancelled)?;
    if fs::metadata(output).map_err(io)?.len()!=width as u64*height as u64*12{return Err(err("INVALID_OUTPUT_SHAPE"));}
    let batches=gpu.thread_submits().saturating_sub(submits_before);
    if batches==0||!gpu.is_healthy(){return Err(err("GPU_EXECUTION_FAILED"));}
    progress.end_work();stages.push(StageMetrics{stage,elapsed_ms:at.elapsed().as_secs_f64()*1000.});
    progress.begin_work("output");
    if let Some(c)=checkpoints.as_mut(){c.commit(6,&[output.to_owned()],ev,cancelled).map_err(io)?;}
    // Sample finalized output with a bounded row buffer, rather than retaining
    // tile cores or duplicating full rows merely for the display thumbnail.
    if let Err(e)=crate::scan_preview::publish_restored(output,width,height,&params.io.output_color_space,cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}
    cancel(cancelled)?;progress.end_work();progress.finish_stage();output_guard.keep=true;
    if let Some(job_dir)=checkpoint_dir.and_then(Path::parent) {
        if let Some(samples)=negative.and_then(|a|a.complete()) {
            // The worker never owns export state or waits on it. UUID-scoped output
            // cannot be reused by a canceled or retried queue attempt.
            if crate::negative_preview::publish_sampled_negative_async(job_dir,pipe.film.clone(),width,height,samples).is_err() {
                let _=crate::negative_preview::mark_current_status(job_dir,"failed",false);
            }
        }else{
            let _=crate::negative_preview::mark_current_status(job_dir,exact_unavailable,false);
        }
    }
    let diagnostics=crate::mobile::resident_diagnostics(gpu,batches,if plan.whole{"full-resident-chain"}else{"tiled-chain"});
    Ok(metric(width,height,budget,start,setup_ms,ev,diagnostics,plan,bytes_read,written,stages))
}

#[cfg(test)]mod tests {
 use super::*;
 #[test]fn lower_budget_reduces_real_band_geometry(){let high=band_plan(2000,6000,20,384,128*1024*1024,4*1024*1024).unwrap();let low=band_plan(2000,6000,20,192,128*1024*1024,4*1024*1024).unwrap();assert!(low.rows<high.rows);assert!(low.estimated_bytes<high.estimated_bytes);assert!(low.estimated_bytes<=192*1024*1024);assert_eq!(low.expanded_rows,low.rows+40);}
 #[test]fn fixed_fields_and_halo_cannot_escape_budget(){assert_eq!(band_plan(8000,6000,500,128,128*1024*1024,0).unwrap_err().code,"GPU_MEMORY_BUDGET");assert!(band_plan(2000,6000,0,128,128*1024*1024,40*1024*1024).is_err());}
 #[test]fn binding_is_checked_separately(){assert_eq!(band_plan(2000,6000,20,512,1024,0).unwrap_err().code,"GPU_BINDING_LIMIT");}
 #[test]fn whole_frame_has_no_artificial_halo(){let p=band_plan(32,16,2000,128,128*1024*1024,0).unwrap();assert!(p.whole);assert_eq!(p.rows,16);assert_eq!(p.expanded_rows,16);}
 #[test]fn released_prepass_workspace_expands_device_main_tiles_without_cutting_halo(){
  let (w,h,halo,budget,prepass_bytes)=(6144u32,8192u32,348u32,415u32,79_959_360u64);
  let before=tile_plan(w,h,halo,budget,128*1024*1024,prepass_bytes).unwrap();
  let retained=prepass_bytes/192*36+std::mem::size_of::<spektrafilm_core::pipeline::DiffusionScatteredFields>()as u64;
  let after=tile_plan(w,h,halo,budget,128*1024*1024,retained).unwrap();
  assert_eq!((before.width,before.rows),(301,301));
  assert_eq!(w.div_ceil(before.width)*h.div_ceil(before.rows),588);
  assert!(after.width>before.width&&after.rows>before.rows);
  assert!(after.estimated_bytes<=budget as u64*1024*1024);
  assert_eq!(after.expanded_width,after.width+2*halo);
  assert_eq!(after.expanded_rows,after.rows+2*halo);
  assert!(w.div_ceil(after.width)*h.div_ceil(after.rows)<588);
 }
 #[test]fn representative_camera_shapes_fit_real_two_dimensional_tiles(){
  for(w,h,halo,fields)in[(3072,4096,244,144*1024*1024),(3072,4096,244,218136576),(6144,8192,348,144*1024*1024),(6144,8192,348,230954304)]{
   let p=tile_plan(w,h,halo,512,128*1024*1024,fields).unwrap();
   assert!(p.width<w&&p.rows<h&&!p.whole);assert!(p.estimated_bytes<=512*1024*1024);
   assert_eq!(p.expanded_width,p.width+2*halo);assert_eq!(p.expanded_rows,p.rows+2*halo);
   eprintln!("TILE_PLAN {w}x{h} fields={fields} halo={halo} core={}x{} expanded={}x{} estimate={}",p.width,p.rows,p.expanded_width,p.expanded_rows,p.estimated_bytes);
  }
 }
 #[test]fn tiles_never_discard_halo_or_exceed_binding(){
  assert!(tile_plan(6144,8192,348,128,128*1024*1024,144*1024*1024).is_err());
  assert_eq!(tile_plan(3072,4096,244,512,1024,0).unwrap_err().code,"GPU_BINDING_LIMIT");
  let p=tile_plan(32,16,2000,128,128*1024*1024,0).unwrap();assert!(p.whole);assert_eq!((p.expanded_width,p.expanded_rows),(32,16));
 }
 #[test]fn rectangular_spool_strides_short_tails_and_exposure_preserve_order(){
  let root=std::env::temp_dir().join(format!("gpu-tile-io-{}",std::process::id()));fs::create_dir_all(&root).unwrap();
  let input=root.join("in.rgb");let output=root.join("out.rgb");let _=fs::remove_file(&output);
  let(w,h)=(13u32,11u32);let values:Vec<f32>=(0..w*h*3).map(|n|n as f32/100.).collect();fs::write(&input,values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut out=OpenOptions::new().write(true).create_new(true).open(&output).unwrap();let mut written=0;
  for y in (0..h).step_by(4){for x in (0..w).step_by(5){let right=(x+5).min(w);let end=(y+4).min(h);let left=x.saturating_sub(2);let top=y.saturating_sub(2);let ew=(right+2).min(w)-left;let eh=(end+2).min(h)-top;
   let v=read_rect(&input,w,h,left,top,ew,eh).unwrap();let rendered=spektrafilm_math::image::ImageBuf::from_data(ew,eh,v.iter().map(|&v|spektrafilm_math::precision::from_f64(v as f64)).collect());
   written+=write_core(&mut out,&rendered,w,left,top,x,y,right,end,0.5,&mut||false).unwrap();
  }}drop(out);assert_eq!(written,w as u64*h as u64*12);
  assert_eq!(fs::read(&output).unwrap(),values.iter().flat_map(|v|(v*0.5).to_le_bytes()).collect::<Vec<_>>());
  assert!(read_rect(&input,w,h,12,0,2,1).is_err());assert!(read_rect(&input,w,h,0,10,1,2).is_err());
  let _=fs::remove_dir_all(root);
 }

 #[test]fn reported_device_op71_black_pro_mist_plan_is_budget_bounded(){
  // Reproduce 2026-11-05 op71 START, not a synthetic Glimmerglass substitute.
  // All photographic values are MobileParams defaults except these explicit fields.
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let mut mobile=crate::mobile::MobileParams::default();mobile.mode="export".into();mobile.memory_budget_mi_b=512;mobile.diffusion_filter="black_pro_mist".into();mobile.print_exposure_ev=-0.44000006;mobile.scan_sharpen_radius=0.699999988079071;
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&mobile.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&mobile.paper_stock).unwrap();
  let mut params=mobile.runtime_for_film(&film);params.settings.use_enlarger_lut=true;params.settings.use_scanner_lut=true;params.settings.lut_resolution=33;
  let pipe=Pipeline::new_with_spectral(film,paper,params,&data).unwrap();
  for(w,h,strength,op)in[(6144,8192,0.5,71),(3072,4096,1.600000023841858,86)]{
   let mut params=pipe.params.clone();params.camera.diffusion_filter.strength=strength as f32;let pipe=pipe.clone().with_params(params);
   let halo=pipe.banded_halo_pixels(35.*1000./w.max(h)as f32);let fields=pipe.gpu_diffusion_working_bytes(w,h).unwrap();
   let p=tile_plan(w,h,halo,512,128*1024*1024,fields).unwrap();assert!(p.width<w&&p.rows<h);assert!(p.estimated_bytes<=512*1024*1024);
   eprintln!("DEVICE_OP{op}_PLAN {w}x{h} black_pro_mist{strength} halo={halo} fields={fields} core={}x{} expanded={}x{} estimate={}",p.width,p.rows,p.expanded_width,p.expanded_rows,p.estimated_bytes);
  }
 }

 #[test]fn actual_device_shapes_complete_on_gpu_when_large_validation_enabled(){
  if std::env::var("FILMROOM_LARGE_GPU_SMOKE").as_deref()!=Ok("1"){eprintln!("LARGE_GPU_NOT_RUN: set FILMROOM_LARGE_GPU_SMOKE=1 under the resource guard");return;}
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let selected=std::env::var("FILMROOM_LARGE_GPU_CASE").unwrap_or_else(|_|"both".into());assert!(["both","71","86"].contains(&selected.as_str()));
  let cancel_path=std::env::var_os("FILMROOM_GPU_TEST_CANCEL").map(PathBuf::from);
  for(w,h,strength,op)in[(3072u32,4096u32,1.600000023841858,86),(6144,8192,0.5,71)]{
   if selected!="both"&&selected!=op.to_string(){continue;}

   let base=std::env::var_os("FILMROOM_GPU_TEST_SCRATCH").map(PathBuf::from).expect("Large validation requires an explicitly disk-backed scratch directory");let root=base.join(format!("filmroom-large-gpu-{}-{op}",std::process::id()));fs::create_dir(&root).unwrap();
   struct Guard(PathBuf);impl Drop for Guard{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}let _guard=Guard(root.clone());
   let input=root.join("input.rgb");let output=root.join("output.rgb");let mut source=File::create(&input).unwrap();
   for y in 0..h{let row:Vec<u8>=(0..w*3).flat_map(|i|(0.01f32+((i*13+y*17)%251)as f32/180.).to_le_bytes()).collect();source.write_all(&row).unwrap();}drop(source);
   let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.memory_budget_mi_b=512;m.diffusion_filter="black_pro_mist".into();m.diffusion_strength=strength;m.print_exposure_ev=-0.44000006;m.scan_sharpen_radius=0.699999988079071;
   let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let params=m.runtime_for_film(&film);
   let fallback=std::cell::Cell::new(false);let started=Instant::now();let last_phase=std::cell::Cell::new("");let last_batch=std::cell::Cell::new((0,0));
   let result=crate::streamed::export_file_checkpointed(&data,&m.film_stock,&m.paper_stock,params,&input,&output,&root.join("scratch"),None,w,h,512,0.,true,||fallback.get()||cancel_path.as_ref().is_some_and(|p|p.exists()),|e|{if let Some(g)=e.gpu {if let Some(phase)=g.phase {if last_batch.replace((g.tile_index,g.batch_index))!=(g.tile_index,g.batch_index){eprintln!("DEVICE_OP{op}_BATCH tile={}/{} batch={} phase={phase} batchMs={} chainWaitMs={} confirmedSubmits={}",g.tile_index,g.tile_total,g.batch_index,g.batch_elapsed_ms,g.chain_wait_ms,g.completed_submits);}}if let Some(failed)=g.failed_phase {eprintln!("DEVICE_OP{op}_FAILED_PHASE {failed} elapsedMs={:?} tile={}/{} confirmedSubmits={}",g.failed_elapsed_ms,g.tile_index,g.tile_total,g.completed_submits);}}if last_phase.replace(e.phase)!=e.phase{eprintln!("DEVICE_OP{op}_PHASE {} elapsedSeconds={}",e.phase,started.elapsed().as_secs_f64());}if e.phase.ends_with("fallback"){fallback.set(true);eprintln!("DEVICE_OP{op}_UNEXPECTED_FALLBACK {}",e.phase);}}).unwrap();
   assert!(!fallback.get());assert!(result.gpu.active);assert_eq!(result.gpu.accelerated_stage,"tiled-chain");assert!(result.gpu.gpu_batches>0);assert_eq!(fs::metadata(&output).unwrap().len(),w as u64*h as u64*12);
   let mut file=File::open(&output).unwrap();let mut bytes=vec![0;w as usize*12];for _ in 0..h{file.read_exact(&mut bytes).unwrap();assert!(bytes.chunks_exact(4).all(|b|f32::from_le_bytes(b.try_into().unwrap()).is_finite()));}
   eprintln!("DEVICE_OP{op}_GPU_PASS shape={w}x{h} strength={strength} submits={} elapsedSeconds={} plannedBytes={} tiles={:?}",result.gpu.gpu_batches,started.elapsed().as_secs_f64(),result.spatial_peak_explicit_buffer_bytes,result.gpu_tiles);
  }
 }

 #[test]fn reported_step6_no_diffusion_geometry_explains_submit_count(){
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.memory_budget_mi_b=458;m.auto_exposure=true;m.diffusion_strength=1.3200000524520874;m.print_exposure_ev=-0.31999993;
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();let params=m.runtime_for_film(&film);let pipe=Pipeline::new_with_spectral(film,paper,params,&data).unwrap();let(w,h)=(3028,3572);let halo=pipe.banded_halo_pixels(35000./3572.);let fields=pipe.gpu_diffusion_working_bytes(w,h).unwrap();
  let old=band_plan(w,h,halo,458,128*1024*1024,fields).unwrap();let new=tile_plan(w,h,halo,458,128*1024*1024,fields).unwrap();assert_eq!(fields,0);assert_eq!(halo,213);assert_eq!(old.rows,63);assert_eq!(h.div_ceil(old.rows),57);assert_eq!(pipe.params.scanner.lens_blur,0.);assert_eq!(pipe.params.scanner.unsharp_mask,[0.,0.]);
  eprintln!("DEVICE_STEP6_PLAN halo={halo} oldCoreRows={} oldBands={} oldSubmitsPerBand=1 newCore={}x{} newTiles={} expanded={}x{} estimatedBytes={}",old.rows,h.div_ceil(old.rows),new.width,new.rows,w.div_ceil(new.width)*h.div_ceil(new.rows),new.expanded_width,new.expanded_rows,new.estimated_bytes);
 }

 #[test]fn isolated_op71_interior_tile_probe_when_enabled(){
  if std::env::var("FILMROOM_GPU_TILE_PROBE").as_deref()!=Ok("1"){eprintln!("OP71_TILE_PROBE_NOT_RUN");return;}
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.memory_budget_mi_b=512;m.diffusion_filter="black_pro_mist".into();m.diffusion_strength=0.5;m.print_exposure_ev=-0.44000006;
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();let mut params=m.runtime_for_film(&film);params.camera.pixel_size_um_override=Some(35000./8192.);params.film_render.grain.coordinate_origin=Some([0,0]);let pipe=Pipeline::new_with_spectral(film,paper,params,&data).unwrap();
  let plan=pipe.gpu_diffusion_plans(6144,8192).0.unwrap();
  let gpu=crate::mobile::full_chain_gpu().expect("Required GPU unavailable");let base=std::env::var_os("FILMROOM_GPU_TEST_SCRATCH").map(PathBuf::from).expect("Probe requires disk-backed scratch");
  struct Guard(PathBuf);impl Drop for Guard{fn drop(&mut self){let _=fs::remove_file(&self.0);}}
  let real_fields=std::env::var("FILMROOM_GPU_TILE_PROBE_REAL_FIELDS").as_deref()!=Ok("0");
  let fields=if real_fields {
   let input=base.join(format!("op71-probe-input-{}.rgb",std::process::id()));let _input_guard=Guard(input.clone());let mut source=File::create(&input).unwrap();
   for y in 0..8192u32{let row:Vec<u8>=(0..6144u32*3).flat_map(|i|(0.01f32+((i*13+y*17)%251)as f32/180.).to_le_bytes()).collect();source.write_all(&row).unwrap();}drop(source);
   let admitted=tile_plan(6144,8192,348,512,gpu.max_storage_buffer_binding_size(),pipe.gpu_diffusion_working_bytes(6144,8192).unwrap()).unwrap();let at=Instant::now();
   let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(6144,8192,admitted.width,admitted.rows,|x,y,w,h|Some(read_rect(&input,6144,8192,x,y,w,h).unwrap()),||true,gpu).unwrap();
   eprintln!("OP71_PROBE_GLOBAL_PREPASS realFields=true elapsedSeconds={} submits={}",at.elapsed().as_secs_f64(),gpu.thread_submits());fields
  }else{
   // Optional synthetic field isolates scheduling only; never full-image proof.
   spektrafilm_core::pipeline::DiffusionScatteredFields{camera:Some(spektrafilm_core::pipeline::DiffusionScattered{field:vec![0.25;plan.small_w as usize*plan.small_h as usize*3],small_w:plan.small_w,small_h:plan.small_h,d:plan.d,p_s:plan.p_s,crop:None}),enlarger:None,highlight_max_raw:None}
  };
  let(x,y,w,h)=(1101u32,136u32,1179u32,1180u32);let values:Vec<f32>=(0..w*h*3).map(|i|{let gx=x+(i/3)%w;let gy=y+(i/3)/w;let ch=i%3;0.01+(((gx*3+ch)*13+gy*17)%251)as f32/180.}).collect();
  let expected=base.join(format!("op71-probe-{}.rgb64",std::process::id()));let _guard=Guard(expected.clone());
  for repeat in 0..2 {
   let before=gpu.thread_submits();let mut batch_seen=0;let mut dir_max=0f64;let start=Instant::now();
   let output=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||pipe.process_gpu_resident_tile_f32_controlled(&values,w,h,spektrafilm_core::pipeline::TileContext{full_width:6144,full_height:8192,x,y},gpu,Some(&fields),||{if let Some(batch)=gpu.thread_resident_progress(){if batch.completed_batches>batch_seen{batch_seen=batch.completed_batches;if batch.stage.as_str()=="dir"{dir_max=dir_max.max(batch.batch_elapsed_ms);}eprintln!("OP71_PROBE_BATCH repeat={repeat} phase={} batch={} elapsedMs={}",batch.stage.as_str(),batch.completed_batches,batch.batch_elapsed_ms);}}true})));
   if let Some(f)=gpu.thread_resident_failure(){eprintln!("OP71_PROBE_FAILED phase={} elapsedMs={}",f.stage.as_str(),f.elapsed_ms);}
   let output=output.unwrap().expect("Required tile GPU output");assert_eq!((output.width,output.height),(w,h));assert!(output.data.iter().all(|v|v.is_finite()));assert!(gpu.is_healthy());assert!(gpu.thread_submits()>before);
   if repeat==0 {let mut out=File::create(&expected).unwrap();for row in output.data.chunks(w as usize*3){let bytes:Vec<u8>=row.iter().flat_map(|&v|(v as f64).to_le_bytes()).collect();out.write_all(&bytes).unwrap();}}
   else {let mut file=File::open(&expected).unwrap();let mut bytes=vec![0u8;w as usize*3*8];for row in output.data.chunks(w as usize*3){file.read_exact(&mut bytes).unwrap();for (v,b) in row.iter().zip(bytes.chunks_exact(8)){assert_eq!((*v as f64).to_bits(),f64::from_le_bytes(b.try_into().unwrap()).to_bits());}}}
   eprintln!("OP71_PROBE_PASS realGlobalField={real_fields} repeat={repeat} full=6144x8192 origin={x},{y} expanded={w}x{h} submits={} maxDirBatchMs={dir_max} elapsedSeconds={}",gpu.thread_submits()-before,start.elapsed().as_secs_f64());
  }
 }

}
