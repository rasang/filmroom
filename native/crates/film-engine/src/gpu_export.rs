//! v41 approximate GPU export, with original-size geometry and bounded rectangular tiles.
//! CPU checkpoints retain their original namespace and are never reinterpreted.
use std::{fs::{self,File,OpenOptions},io::{Read,Seek,SeekFrom,Write},path::{Path,PathBuf},time::Instant};
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::Profile};
use crate::{mobile::{EngineError,Result},streamed::{StreamedMetrics,StageMetrics},streamed_progress::{ProgressSink,GpuWorkProgress}};

// Export ownership semantics are independent of the preview/detail protocol.
// Every export checkpoint, global-field/FilmLog reuse key, and reuse probe uses this ID.
pub(crate) const ALGORITHM:&str="export-ae-owned-v2-resident-f32-psf6-cap64-fir256-grain-rms-v1-cinestill4g-v1-donor-portra-v1-front-prepass-v3-disk-diffusion-v1-spatial-psf-v1";
#[cfg(test)]
thread_local!{static FORCE_DISK_DIFFUSION:std::cell::Cell<bool>=const{std::cell::Cell::new(false)};}
const FIXED_BYTES:u64=96*1024*1024;
// Includes 12 B/pixel saved log RGB for no-film donor grain, rounded up.
const BAND_BYTES_PER_PIXEL:u64=272;
#[derive(Clone,Copy,Debug)]
pub(crate) struct BandPlan {pub rows:u32,pub expanded_rows:u32,pub estimated_bytes:u64,pub whole:bool}
fn err(code:&'static str)->EngineError {EngineError::new(code,"GPU export could not complete within its validated contract")}
fn io(e:std::io::Error)->EngineError {EngineError::new(if e.kind()==std::io::ErrorKind::Interrupted{"CANCELLED"}else{"IO_ERROR"},"GPU spool operation failed")}
fn cancel(c:&mut impl FnMut()->bool)->Result<()> {if c(){Err(err("CANCELLED"))}else{Ok(())}}
/// Joint host/device/staging/driver estimate, not measured RSS. More budget
/// changes executable geometry; no MemAvailable override or minimum allocation.
pub(crate) fn band_plan(width:u32,height:u32,halo:u32,budget_mib:u32,binding:u64,fields:u64)->Result<BandPlan> {
    if width==0||height==0||!(128..=1024).contains(&budget_mib){return Err(err("INVALID_BUDGET"));}
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
/// Geometric work, not a calibrated time prediction. Compare full-image
/// expanded pixels first, then actual row IO calls, then dispatch count.
/// Whole-width/height candidates use the same budgets and no transposition.
#[derive(Clone,Copy,Debug,PartialEq,Eq,PartialOrd,Ord)]
struct TileCost { expanded_pixels:u64, row_io:u64, tiles:u64 }
fn tile_cost(width:u32,height:u32,halo:u32,core_w:u32,core_h:u32)->TileCost {
    fn expanded_axis(n:u32,core:u32,halo:u32)->u64 {
        (0..n).step_by(core as usize).map(|x| {
            let end=x.saturating_add(core).saturating_add(halo).min(n);
            u64::from(end-x.saturating_sub(halo))
        }).sum()
    }
    let nx=u64::from(width.div_ceil(core_w));let ny=u64::from(height.div_ceil(core_h));
    let sx=expanded_axis(width,core_w,halo);let sy=expanded_axis(height,core_h,halo);
    TileCost{expanded_pixels:sx*sy,row_io:nx*(sy+u64::from(height)),tiles:nx*ny}
}
/// Maximum live rectangular allocation, including both halo axes. All global
/// fields remain charged while every tile executes; storage is independently bounded.
pub(crate) fn tile_plan(width:u32,height:u32,halo:u32,budget_mib:u32,binding:u64,fields:u64)->Result<TilePlan> {
    if width==0||height==0||width>40000||height>40000||!(128..=1024).contains(&budget_mib){return Err(err("INVALID_BUDGET"));}
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
        if best.map_or(true,|b|tile_cost(width,height,halo,candidate.width,candidate.rows)<tile_cost(width,height,halo,b.width,b.rows)){best=Some(candidate);}
    }
    best.ok_or_else(||err(if binding_pixels<memory_pixels{"GPU_BINDING_LIMIT"}else{"GPU_MEMORY_BUDGET"}))
}
struct OutputGuard {path:PathBuf,keep:bool}
impl Drop for OutputGuard {fn drop(&mut self){if !self.keep {let _=fs::remove_file(&self.path);if let Some(path)=crate::scan_preview::path(&self.path){let _=fs::remove_file(&path);let _=fs::remove_file(path.with_extension("png.tmp"));}}}}
/// Restore into an exclusively owned directory, then atomically create the
/// destination link. A failed restore or destination collision never owns the
/// caller's path; partial copies are cleaned with their private directory.
fn restore_output(checkpoint:&crate::checkpoint::Checkpoints,output:&Path,cancelled:&mut impl FnMut()->bool)->Result<()> {
    use std::sync::atomic::{AtomicU64,Ordering};
    static NEXT:AtomicU64=AtomicU64::new(0);
    struct Directory(PathBuf);
    impl Drop for Directory {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
    let parent=output.parent().ok_or_else(||err("IO_ERROR"))?;
    let directory=loop {
        cancel(cancelled)?;
        let path=parent.join(format!(".gpu-restore-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
        match fs::create_dir(&path) {
            Ok(())=>break Directory(path),
            Err(e) if e.kind()==std::io::ErrorKind::AlreadyExists=>continue,
            Err(e)=>return Err(io(e)),
        }
    };
    let temporary=directory.0.join("output");
    checkpoint.restore(&[temporary.clone()],cancelled).map_err(io)?;
    cancel(cancelled)?;
    fs::hard_link(&temporary,output).map_err(io)?;
    Ok(())
}
/// Optional work must not make the authoritative cold render more expensive.
/// This is geometric work, not a latency claim; require no increase in expanded
/// pixels, row IO, or dispatches rather than a tolerance that hides tiny tiles.
fn optional_plan_admitted(width:u32,height:u32,halo:u32,base:TilePlan,candidate:TilePlan)->bool {
    let a=tile_cost(width,height,halo,base.width,base.rows);
    let b=tile_cost(width,height,halo,candidate.width,candidate.rows);
    b.expanded_pixels<=a.expanded_pixels && b.row_io<=a.row_io && b.tiles<=a.tiles
}
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
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    crate::resource_probe::observe_vec("export_read_rect_f32", &values);
    // RESOURCE_PROBE_END
    Ok(values)
}
#[allow(clippy::too_many_arguments)]
fn write_core(out:&mut File,rendered:&spektrafilm_math::image::ImageBuf,full_width:u32,left:u32,top:u32,x:u32,y:u32,right:u32,end:u32,exposure:f64,cancelled:&mut impl FnMut()->bool,mut proof:Option<&mut crate::film_log_reuse::Lease<'_>>)->Result<u64>{
    if x<left||y<top||right<=x||end<=y||right>full_width||right-left>rendered.width||end-top>rendered.height||rendered.data.len()!=rendered.width as usize*rendered.height as usize*3{return Err(err("INVALID_OUTPUT_SHAPE"));}
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    crate::resource_probe::observe_vec("export_rendered_scalar", &rendered.data);
    // RESOURCE_PROBE_END
    // One converted row, with explicit source/destination strides.
    let mut bytes=Vec::new();bytes.try_reserve_exact((right-x)as usize*12).map_err(|_|err("GPU_MEMORY_BUDGET"))?;
    let mut written=0;
    for row in y..end {
        cancel(cancelled)?;bytes.clear();
        let begin=(((row-top)as usize*rendered.width as usize)+(x-left)as usize)*3;
        for &value in &rendered.data[begin..begin+(right-x)as usize*3] {let value=(value as f64*exposure)as f32;if !value.is_finite(){return Err(err("NONFINITE_OUTPUT"));}bytes.extend_from_slice(&value.to_le_bytes());}
        out.seek(SeekFrom::Start((row as u64*full_width as u64+x as u64)*12)).map_err(io)?;
        out.write_all(&bytes).map_err(io)?;written+=bytes.len()as u64;
        if let Some(proof)=proof.as_deref_mut(){proof.record(x,row,&bytes);}
    }
    Ok(written)
}
fn metric(width:u32,height:u32,budget:u32,start:Instant,setup:f64,ev:Option<f32>,gpu:crate::adreno::GpuDiagnostics,plan:TilePlan,read:u64,written:u64,stages:Vec<StageMetrics>)->StreamedMetrics {
    StreamedMetrics{export_execution:None,metered_ev:ev,gpu,width,height,pixel_count:width as u64*height as u64,memory_budget_mi_b:budget,elapsed_ms:start.elapsed().as_secs_f64()*1000.,setup_ms:setup,estimated_scratch_bytes:0,peak_logical_scratch_bytes:0,spatial_peak_explicit_buffer_bytes:plan.estimated_bytes,spatial_bytes_read:read,spatial_bytes_written:written,spatial_pass_count:0,pointwise_chunk_rows:plan.rows,gpu_tiles:Some(crate::streamed::GpuTileMetrics{core_width:plan.width,core_height:plan.rows,expanded_width:plan.expanded_width,expanded_height:plan.expanded_rows}),lut_storage_bytes:0,stages,algorithm:ALGORITHM,grain_mode:"v41-approximate-global-coordinate-grain",memory_policy:"Conservative joint host/device/staging/driver estimate; 272B/expanded pixel + 96MiB + retained global diffusion fields; budget determines actual rectangular tile geometry"}
}
#[allow(clippy::too_many_arguments)]
fn global_fields_configuration(params:&RuntimeParams,film:&str,paper:&str,ev:Option<f32>,pitch:f32,
    backend:usize,adapter:&str,plan:TilePlan,front:TilePlan)->Option<String> {
    serde_json::to_string(&("full-source-global-fields-v1",ALGORITHM,film,paper,params,
        ev,pitch.to_bits(),backend,adapter,plan.width,plan.rows,front.width,front.rows)).ok()
}

#[allow(clippy::too_many_arguments)]
fn film_log_configuration(pipe:&Pipeline,fields:Option<&spektrafilm_core::pipeline::DiffusionScatteredFields>,
    ev:Option<f32>,pitch:f32,backend:usize,adapter:&str,prefix:TilePlan,prepass:TilePlan,prefix_halo:u32,cancelled:&mut impl FnMut()->bool)->Option<String> {
    // The enclosing checkpoint Identity already binds current verified source,
    // dimensions and complete engine-data contents, independently of this key.
    let prefix_key=pipe.gpu_film_log_prefix_key("export-checkpoint-data-v1")?;
    let mut hash=crate::checkpoint::Sha256::new();
    hash.update(b"actual-prefix-global-fields-v1");
    if fields.is_some_and(|f|f.has_disk_backing()){return None;}
    let highlight=fields.and_then(|f|f.highlight_max_raw);
    hash.update(&u32::from(highlight.is_some()).to_le_bytes());
    if let Some(v)=highlight{if !v.is_finite(){return None;}hash.update(&v.to_bits().to_le_bytes());}
    if let Some(camera)=fields.and_then(|f|f.camera.as_ref()) {
        if camera.crop.is_some()||!camera.p_s.is_finite(){return None;}
        hash.update(&[1]);
        for v in [camera.small_w,camera.small_h,camera.d,camera.p_s.to_bits()]{hash.update(&v.to_le_bytes());}
        hash.update(&(camera.field.len()as u64).to_le_bytes());
        for chunk in camera.field.chunks(4096){if cancelled(){return None;}for value in chunk{if !value.is_finite(){return None;}hash.update(&value.to_bits().to_le_bytes());}}
    }else{hash.update(&[0]);}
    serde_json::to_string(&("split-filmlog-reuse-v1",ALGORITHM,prefix_key,hash.finish(),
        ev.map(f32::to_bits),pitch.to_bits(),backend,adapter,
        (prefix.width,prefix.rows,prefix.expanded_width,prefix.expanded_rows,prefix_halo),
        (prepass.width,prepass.rows,prepass.expanded_width,prepass.expanded_rows))).ok()
}

#[derive(Clone,Copy,PartialEq,Eq)]
enum TilePhase { Full, FilmLog, FromFilmLog, ScatteredRaw, PostHalationRaw }
fn tile_count(width:u32,height:u32,plan:TilePlan)->u32 {width.div_ceil(plan.width)*height.div_ceil(plan.rows)}
// Every attempt owns its own spool. A crashed attempt must not poison a later
// retry, and create_new remains the final no-clobber authority.
fn film_log_attempt_path(output:&Path)->PathBuf {
    use std::sync::atomic::{AtomicU64,Ordering};
    static NEXT:AtomicU64=AtomicU64::new(0);
    let stamp=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_nanos();
    output.with_extension(format!("gpu-film-log-{}-{stamp}-{}.rgb",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)))
}
fn split_is_worthwhile(width:u32,height:u32,halo:u32,plan:TilePlan)->bool {
    !plan.whole && halo>=64 && tile_count(width,height,plan)>=64
}
// A full-chain capacity failure may be resolved by splitting; invalid input
// and other failures must propagate, as must a forced full-chain failure.
fn choose_main_split(width:u32,height:u32,halo:u32,full:&Result<TilePlan>,forced:Option<bool>)->Result<bool> {
    match full {
        Ok(plan)=>Ok(forced.unwrap_or_else(||split_is_worthwhile(width,height,halo,*plan))),
        Err(e) if matches!(e.code,"GPU_MEMORY_BUDGET"|"GPU_BINDING_LIMIT")&&forced!=Some(false)=>Ok(true),
        Err(e)=>Err(err(e.code)),
    }
}
// Preserve raw phase pixels while display progress spans all main tiles.
// Corrupt-cache replay increases total work: retain its attained fraction.
#[allow(clippy::too_many_arguments)]
fn report_main_tile(progress:&mut impl ProgressSink,stage:&'static str,done:u64,total:u64,
    index:u32,tiles:u32,fraction:&mut u64) {
    const SCALE:u64=1_000_000;
    let current=if tiles>0{u64::from(index.min(tiles))*SCALE/u64::from(tiles)}else{0};
    *fraction=(*fraction).max(current);
    progress.pass_fraction(stage,done,total,*fraction,SCALE);
}
#[allow(clippy::too_many_arguments)]
fn render_tiles(pipe:&Pipeline,gpu:&spektrafilm_gpu::wgpu_backend::WgpuBackend,
    fields:Option<&spektrafilm_core::pipeline::DiffusionScatteredFields>,input:&Path,output:&Path,
    width:u32,height:u32,halo:u32,plan:TilePlan,phase:TilePhase,exposure:f64,
    tile_index:&mut u32,tile_total:u32,main_fraction:&mut u64,submits_before:u64,start:Instant,
    cancelled:&mut impl FnMut()->bool,progress:&mut impl ProgressSink,negative:&mut Option<crate::negative_preview::GpuDensityAssembly>,exact_unavailable:&mut &'static str,mut log_cache:Option<&mut crate::film_log_reuse::Lease<'_>>)->Result<(u64,u64)> {
    let stage=if plan.whole{"gpu_full_chain"}else{"gpu_tiled_chain"};
    let cost=tile_cost(width,height,halo,plan.width,plan.rows);
    let geometry=GpuWorkProgress {
        tile_phase:Some(match phase{TilePhase::Full=>"full",TilePhase::FilmLog=>"film_log",TilePhase::FromFilmLog=>"from_film_log",TilePhase::ScatteredRaw=>"scattered_raw",TilePhase::PostHalationRaw=>"post_halation_raw"}),
        core_width:plan.width,core_height:plan.rows,expanded_width:plan.expanded_width,expanded_height:plan.expanded_rows,halo,
        expanded_ratio:cost.expanded_pixels as f64/(u64::from(width)*u64::from(height))as f64,
        plan_bytes:plan.estimated_bytes,
        reserved_bytes:plan.estimated_bytes.saturating_sub(FIXED_BYTES).saturating_sub(u64::from(plan.expanded_width)*u64::from(plan.expanded_rows)*BAND_BYTES_PER_PIXEL),
        ..GpuWorkProgress::default()
    };
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    if let Some(fields)=fields {
        if let Some(field)=fields.camera.as_ref(){crate::resource_probe::observe_vec("retained_camera_field_f32",&field.field);}
        if let Some(field)=fields.enlarger.as_ref(){crate::resource_probe::observe_vec("retained_enlarger_field_f32",&field.field);}
    }
    // RESOURCE_PROBE_END
    let mut out=OpenOptions::new().write(true).create_new(true).open(output).map_err(io)?;
    // Ownership starts only after create_new succeeds; never remove a foreign collision.
    let mut owned=OutputGuard{path:output.to_owned(),keep:false};
    let mut written=0u64;let mut bytes_read=0u64;let mut y=0u32;


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
            *tile_index+=1;
            progress.gpu_event(GpuWorkProgress{tile_index:*tile_index,tile_total,completed_submits:gpu.thread_submits().saturating_sub(submits_before),..geometry});
            let cached=phase==TilePhase::FromFilmLog&&log_cache.as_ref().is_some_and(|cache|cache.is_hit());
            let values=if cached {
                log_cache.as_deref_mut().unwrap().read_rect(left,top,ew,eh,cancelled)
                    .map_err(|e|if e.kind()==std::io::ErrorKind::Interrupted{io(e)}else{err("FILMLOG_CACHE_CORRUPT")})?
            }else{let values=read_rect(input,width,height,left,top,ew,eh)?;bytes_read+=values.len()as u64*4;values};
            cancel(cancelled)?;
            #[cfg(test)]
            if std::env::var("FILMROOM_LARGE_GPU_SMOKE").as_deref()==Ok("1"){eprintln!("GPU_TILE_BEGIN x={x} y={y} core={}x{} expanded={ew}x{eh} completedSubmits={} elapsedSeconds={}",right-x,end-y,gpu.thread_submits(),start.elapsed().as_secs_f64());}
            let tile_submits=gpu.thread_submits();let mut reported_batch=0;
            let mut checkpoint=||{
                if gpu.thread_submits()>tile_submits {if let Some(batch)=gpu.thread_resident_progress(){
                    if batch.completed_batches>reported_batch {reported_batch=batch.completed_batches;
                        progress.gpu_event(GpuWorkProgress{phase:Some(batch.stage.as_str()),tile_index:*tile_index,tile_total,batch_index:batch.completed_batches,batch_elapsed_ms:batch.batch_elapsed_ms,chain_wait_ms:batch.chain_gpu_wait_ms,completed_submits:gpu.thread_submits().saturating_sub(submits_before),..geometry});
                    }
                }}
                !cancelled()
            };
            let tile=spektrafilm_core::pipeline::TileContext{full_width:width,full_height:height,x:left,y:top};
            let request=negative.as_ref().filter(|_|phase!=TilePhase::FilmLog).map(|a|a.samples_for_core(left,top,x,y,right,end)).unwrap_or_default();
            let mut capture=(!request.is_empty()).then(||spektrafilm_gpu::FilmDensityCapture{samples:request,result:None});
            let rendered=match (phase,capture.as_mut()) {
                (TilePhase::ScatteredRaw,_)=>pipe.process_gpu_spatial_endpoint_controlled(&values,ew,eh,tile,gpu,fields,false,&mut checkpoint),
                (TilePhase::PostHalationRaw,_)=>pipe.process_gpu_spatial_endpoint_controlled(&values,ew,eh,tile,gpu,fields,true,&mut checkpoint),
                (TilePhase::FilmLog,_)=>pipe.tap_gpu_film_log_tile_f32_controlled(&values,ew,eh,tile,gpu,fields,&mut checkpoint),
                (TilePhase::FromFilmLog,Some(c))=>pipe.process_gpu_from_film_log_tile_f32_capture_controlled(&values,ew,eh,tile,gpu,fields,c,&mut checkpoint),
                (TilePhase::FromFilmLog,None)=>pipe.process_gpu_from_film_log_tile_f32_controlled(&values,ew,eh,tile,gpu,fields,&mut checkpoint),
                (TilePhase::Full,Some(c)) if plan.whole=>pipe.process_gpu_resident_cpu_gamut_f32_capture_controlled(&values,width,height,gpu,c,&mut checkpoint),
                (TilePhase::Full,Some(c))=>pipe.process_gpu_resident_tile_f32_capture_controlled(&values,ew,eh,tile,gpu,fields,c,&mut checkpoint),
                (TilePhase::Full,None) if plan.whole=>pipe.process_gpu_resident_cpu_gamut_f32_controlled(&values,width,height,gpu,&mut checkpoint),
                (TilePhase::Full,None)=>pipe.process_gpu_resident_tile_f32_controlled(&values,ew,eh,tile,gpu,fields,&mut checkpoint),
            };
            if let Some(c)=capture {
                let accepted=negative.as_mut().is_some_and(|a|c.result.as_ref().is_some_and(|v|a.add(&c.samples,v)));
                if !accepted {*negative=None;*exact_unavailable="failed";}
            }

            drop(values);cancel(cancelled)?;
            let rendered=rendered.ok_or_else(||err(if fields.is_some_and(|f|f.has_disk_backing()){"IO_ERROR"}else{"GPU_EXECUTION_FAILED"}))?;
            if rendered.width!=ew||rendered.height!=eh||rendered.data.len()!=ew as usize*eh as usize*3{return Err(err("INVALID_OUTPUT_SHAPE"));}
            if rendered.data.iter().any(|v|!v.is_finite()){return Err(err("NONFINITE_OUTPUT"));}
            #[cfg(test)]
            if std::env::var("FILMROOM_LARGE_GPU_SMOKE").as_deref()==Ok("1"){eprintln!("GPU_TILE_COMPLETE x={x} y={y} completedSubmits={} elapsedSeconds={}",gpu.thread_submits(),start.elapsed().as_secs_f64());}
            written+=write_core(&mut out,&rendered,width,left,top,x,y,right,end,exposure,cancelled,if matches!(phase,TilePhase::FilmLog|TilePhase::PostHalationRaw){log_cache.as_deref_mut()}else{None})?;
            report_main_tile(progress,stage,y as u64*width as u64+(end-y)as u64*right as u64,height as u64*width as u64,*tile_index,tile_total,main_fraction);
            x=right;
        }
        y=end;
    }
    out.flush().map_err(io)?;out.sync_all().map_err(io)?;drop(out);cancel(cancelled)?;
    if fs::metadata(output).map_err(io)?.len()!=width as u64*height as u64*12{return Err(err("INVALID_OUTPUT_SHAPE"));}
    owned.keep=true;
    Ok((bytes_read,written))
}

fn spatial_tiles(pipe:&Pipeline,width:u32,height:u32,plan:TilePlan)->Result<u32>{
 let (components,_,_)=pipe.gpu_physical_components(width,height).ok_or_else(||err("INVALID_PARAMETERS"))?;
 let pixels=u64::from(plan.expanded_width)*u64::from(plan.expanded_rows);let mut total=0u64;
 for c in components {for sigma in c.sigma_px {let radius=spektrafilm_gpu::back_reflection_fir_radius(sigma).ok_or_else(||err("INVALID_PARAMETERS"))?;
  for axis in [crate::gpu_spatial::Axis::Horizontal,crate::gpu_spatial::Axis::Vertical] {total=total.checked_add(crate::gpu_spatial::plan(width,height,radius,axis,crate::gpu_spatial::directional_pixel_cap(radius,pixels)).map_err(io)?.tiles).ok_or_else(||err("INVALID_PARAMETERS"))?;}
 }}u32::try_from(total).map_err(|_|err("INVALID_PARAMETERS"))
}
#[allow(clippy::too_many_arguments)]
fn render_prefix(pipe:&Pipeline,gpu:&spektrafilm_gpu::wgpu_backend::WgpuBackend,
 fields:Option<&spektrafilm_core::pipeline::DiffusionScatteredFields>,input:&Path,output:&Path,
 width:u32,height:u32,halo:u32,plan:TilePlan,spatial:bool,index:&mut u32,total:u32,fraction:&mut u64,
 submits:u64,start:Instant,cancelled:&mut impl FnMut()->bool,progress:&mut impl ProgressSink,
 cache:Option<&mut crate::film_log_reuse::Lease<'_>>)->Result<(u64,u64)> {
 if !spatial{return render_tiles(pipe,gpu,fields,input,output,width,height,halo,plan,TilePhase::FilmLog,1.,index,total,fraction,submits,start,cancelled,progress,&mut None,&mut "not_applicable",cache);}
 let (components,scale,renormalize)=pipe.gpu_physical_components(width,height).ok_or_else(||err("INVALID_PARAMETERS"))?;
 // Includes scatter RGB, post-halation RGB, four scalar files and FilmLog.
 // A free-space preflight is advisory; every subsequent IO error still aborts.
 #[cfg(unix)] {
  use std::os::unix::ffi::OsStrExt;
  let parent=output.parent().ok_or_else(||err("IO_ERROR"))?;
  let path=std::ffi::CString::new(parent.as_os_str().as_bytes()).map_err(|_|err("IO_ERROR"))?;
  let mut stat=std::mem::MaybeUninit::<libc::statvfs>::uninit();
  if unsafe{libc::statvfs(path.as_ptr(),stat.as_mut_ptr())}!=0{return Err(io(std::io::Error::last_os_error()));}
  let stat=unsafe{stat.assume_init()};
  let available=(stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64);
  if available<u64::from(width)*u64::from(height)*52+64*1024*1024{return Err(err("INSUFFICIENT_STORAGE"));}
 }
 let scattered=film_log_attempt_path(output);let physical=film_log_attempt_path(output);
 let (r1,w1)=render_tiles(pipe,gpu,fields,input,&scattered,width,height,halo,plan,TilePhase::ScatteredRaw,1.,index,total,fraction,submits,start,cancelled,progress,&mut None,&mut "not_applicable",None)?;
 let _scattered=OutputGuard{path:scattered.clone(),keep:false};
 // The RGB chain's conservative 272 B/expanded pixel covers scalar backend
 // 48 B/px, caller's 8 B/px, row/chunk scratch and live kernels/fields.
 let max_pixels=u64::from(plan.expanded_width)*u64::from(plan.expanded_rows);
 let mut directional_plans=std::collections::BTreeMap::new();
 for component in components {for sigma in component.sigma_px {let radius=spektrafilm_gpu::back_reflection_fir_radius(sigma).ok_or_else(||err("INVALID_PARAMETERS"))?;
  for axis in [crate::gpu_spatial::Axis::Horizontal,crate::gpu_spatial::Axis::Vertical] {directional_plans.insert((radius,axis==crate::gpu_spatial::Axis::Horizontal),crate::gpu_spatial::plan(width,height,radius,axis,crate::gpu_spatial::directional_pixel_cap(radius,max_pixels)).map_err(io)?);}
 }}
 let cancellation=std::cell::RefCell::new(&mut *cancelled);
 let spatial_metrics=crate::gpu_spatial::physical(&scattered,&physical,width,height,&components,scale,renormalize,max_pixels,
  &mut||(*cancellation.borrow_mut())(),&mut|v,w,h,sigma,axis,component,channel|{
   *index+=1;
   let stage=if axis==crate::gpu_spatial::Axis::Horizontal{"physical_halation_h"}else{"physical_halation_v"};
   let radius=spektrafilm_gpu::back_reflection_fir_radius(sigma).unwrap_or(0);
   let directional=directional_plans.get(&(radius,axis==crate::gpu_spatial::Axis::Horizontal)).ok_or_else(||std::io::Error::new(std::io::ErrorKind::Other,"Missing directional plan"))?;
   progress.gpu_event(GpuWorkProgress{tile_phase:Some(stage),tile_index:*index,tile_total:total,core_width:directional.core_width,core_height:directional.core_height,expanded_width:w,expanded_height:h,halo:radius,expanded_ratio:directional.expanded_pixels as f64/(u64::from(width)*u64::from(height))as f64,plan_bytes:plan.estimated_bytes,reserved_bytes:plan.estimated_bytes.saturating_sub(FIXED_BYTES).saturating_sub(max_pixels*BAND_BYTES_PER_PIXEL),completed_submits:gpu.thread_submits().saturating_sub(submits),..GpuWorkProgress::default()});
   let out=gpu.physical_blur_axis_with_context(v,w,h,sigma,axis==crate::gpu_spatial::Axis::Horizontal,max_pixels*64+1024*1024,
    &mut||!(*cancellation.borrow_mut())(),Some((component,channel))).ok_or_else(||std::io::Error::new(if (*cancellation.borrow_mut())(){std::io::ErrorKind::Interrupted}else{std::io::ErrorKind::Other},"GPU physical pass failed"))?;
   report_main_tile(progress,"gpu_tiled_chain",u64::from(*index),u64::from(total),*index,total,fraction);
   Ok(out)
  }).map_err(io)?;
 let _physical=OutputGuard{path:physical.clone(),keep:false};
 drop(cancellation);
 let (r2,w2)=render_tiles(pipe,gpu,fields,&physical,output,width,height,0,plan,TilePhase::PostHalationRaw,1.,index,total,fraction,submits,start,cancelled,progress,&mut None,&mut "not_applicable",cache)?;
 Ok((r1+r2+spatial_metrics.bytes_read,w1+w2+spatial_metrics.bytes_written))
}

/// A completed prepass releases construction scratch, not the physical PSF
/// needed by the full render. Without external fields, construction already
/// includes those kernels and must not be charged a second time.
fn full_field_reservation(retained:Option<u64>,construction:u64,early:u64,kernels:u64)->Option<u64> {
    let fields=match retained {Some(bytes)=>bytes.checked_add(kernels)?,None=>construction};
    fields.checked_add(early)
}

/// A cold/replayed FilmLog prefix still owns physical PSF kernels. A suffix
/// starts after those kernels and must not reserve or run them a second time.
fn split_field_reservations(retained:u64,suffix_retained:u64,early:u64,kernels:u64)->Option<(u64,u64)> {
    Some((retained.checked_add(early)?.checked_add(kernels)?,suffix_retained.checked_add(early)?))
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn export(params:&RuntimeParams,film_stock:&str,paper_stock:&str,film:&Profile,paper:&Profile,data_dir:&Path,input:&Path,output:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,budget:u32,brightness:f64,cancelled:&mut impl FnMut()->bool,progress:&mut impl ProgressSink,early_preview:&mut Option<crate::early_negative_preview::PreviewHandle>)->Result<StreamedMetrics> {
    export_impl(params,film_stock,paper_stock,film,paper,data_dir,input,output,checkpoint_dir,width,height,budget,brightness,cancelled,progress,None,early_preview)
}
#[cfg(test)]
#[allow(clippy::too_many_arguments)]
fn export_with_split(params:&RuntimeParams,film_stock:&str,paper_stock:&str,film:&Profile,paper:&Profile,data_dir:&Path,input:&Path,output:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,budget:u32,brightness:f64,cancelled:&mut impl FnMut()->bool,progress:&mut impl ProgressSink,force_split:Option<bool>)->Result<StreamedMetrics> {
    export_impl(params,film_stock,paper_stock,film,paper,data_dir,input,output,checkpoint_dir,width,height,budget,brightness,cancelled,progress,force_split,&mut None)
}
#[allow(clippy::too_many_arguments)]
fn export_impl(params:&RuntimeParams,film_stock:&str,paper_stock:&str,film:&Profile,paper:&Profile,data_dir:&Path,input:&Path,output:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,budget:u32,brightness:f64,cancelled:&mut impl FnMut()->bool,progress:&mut impl ProgressSink,force_split:Option<bool>,early_preview:&mut Option<crate::early_negative_preview::PreviewHandle>)->Result<StreamedMetrics> {
    let start=Instant::now();cancel(cancelled)?;
    // Disarmed until this invocation successfully creates the final output.
    let mut output_guard=OutputGuard{path:output.to_owned(),keep:true};
    // Checkpoint configuration explicitly includes the algorithm. CPU manifests
    // live one level above and are selected before this entry is called.
    let mut current_identity=None;
    let mut checkpoints=if let Some(root)=checkpoint_dir {
        if !root.exists(){fs::create_dir(root).map_err(io)?;}
        if !fs::symlink_metadata(root).map_err(io)?.is_dir(){return Err(err("CHECKPOINT_INVALID"));}
        let config=serde_json::to_string(&(ALGORITHM,film_stock,paper_stock,params,brightness)).map_err(|_|err("INVALID_PARAMETERS"))?;
        let identity=crate::checkpoint::Identity::new(data_dir,input,width,height,config,cancelled).map_err(io)?;
        current_identity=Some(identity.clone());
        Some(crate::checkpoint::Checkpoints::open(&root.join("gpu-v41-approx-filmlog-ae-v4"),identity,cancelled).map_err(io)?)
    }else{None};
    if let Some(c)=checkpoints.as_ref().filter(|c|c.stage()==6) {
        progress.begin_stage(6,1,"output");progress.begin_work("output");
        restore_output(c,output,cancelled)?;
        output_guard.keep=false;
        cancel(cancelled)?;progress.end_work();progress.finish_stage();
        let mut gpu=crate::adreno::AdrenoDensityBackend::with_enabled(false).diagnostics();gpu.requested=true;gpu.reason="gpu_checkpoint_restored".into();gpu.accelerated_stage="tiled-chain";
        if let Err(e)=crate::scan_preview::publish_restored(output,width,height,&params.io.output_color_space,cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}
        output_guard.keep=true;
        return Ok(metric(width,height,budget,start,0.,c.metered_ev(),gpu,TilePlan{width:0,rows:0,expanded_width:0,expanded_rows:0,estimated_bytes:0,whole:false},0,width as u64*height as u64*12,vec![]));
    }
    let gpu=crate::mobile::full_chain_gpu().filter(|g|g.is_healthy()).ok_or_else(||err(if crate::mobile::full_chain_unsupported(){"GPU_UNSUPPORTED_ADAPTER"}else{"GPU_UNAVAILABLE"}))?;
    let mut p=params.clone();let mut stages=Vec::new();
    let diffusion=p.camera.diffusion_filter.active||(!p.io.scan_film&&p.enlarger.diffusion_filter.active);
    let global_effect=diffusion||p.film_render.halation.boost_ev!=0.||p.film_render.halation.headroom_active();
    let ev=if p.camera.auto_exposure {
        progress.begin_stage(2,1,"auto_exposure");progress.begin_work("auto_exposure");let at=Instant::now();
        let matrix=spektrafilm_core::stages::filming::input_colorspace_to_xyz(&p.io.input_color_space);
        let ev=match p.camera.preview_metered_ev {Some(ev)=>ev,None=>crate::streamed_global::auto_exposure_ev(input,width,height,&p.camera.auto_exposure_method,&matrix,&mut *cancelled).map_err(io)?};
        if !ev.is_finite(){return Err(err("NONFINITE_OUTPUT"));}
        // Keep automatic metering distinct from the user's manual exposure.
        // Pipeline construction uses only manual compensation to calibrate
        // print exposure. Folding AE into it changes the final print brightness.
        // This internal override is the freshly measured full-source EV, not a
        // caller-provided preview measurement (exports reject that override).
        // A pre-imaging export has already verified and metered its separate scene source.
        p.camera.preview_metered_ev=Some(ev);
        progress.end_work();progress.finish_stage();stages.push(StageMetrics{stage:"auto_exposure",elapsed_ms:at.elapsed().as_secs_f64()*1000.});Some(ev)
    }else{None};
    cancel(cancelled)?;
    let pipe=std::sync::Arc::new(Pipeline::new_with_spectral(film.clone(),paper.clone(),p,data_dir).map_err(|_|err("PIPELINE_ERROR"))?);
    let pitch=params.camera.pixel_size_um_override.unwrap_or(params.camera.film_format_mm*1000./width.max(height)as f32);
    let halo=pipe.banded_halo_pixels(pitch);
    let resident_fields=pipe.gpu_diffusion_working_bytes(width,height).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let kernels=pipe.gpu_halation_working_bytes(width,height).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    // Keep the validated resident path for frames that already fit. Large
    // fields use a disk-backed, bounded construction and crop-only consumer.
    let (camera_plan,enlarger_plan)=pipe.gpu_diffusion_plans(width,height);
    let field_binding_exceeded=[&camera_plan,&enlarger_plan].into_iter().flatten().any(|p|u64::from(p.small_w)*u64::from(p.small_h)*12>gpu.max_storage_buffer_binding_size()*9/10);
    let disk_diffusion=diffusion && (field_binding_exceeded || tile_plan(width,height,0,budget,gpu.max_storage_buffer_binding_size(),resident_fields).is_err());
    #[cfg(test)] let disk_diffusion=disk_diffusion||FORCE_DISK_DIFFUSION.with(|v|v.get());
    let disk_window_pixels=u64::from(budget)*1024*1024/BAND_BYTES_PER_PIXEL;
    let disk_reservation=if disk_diffusion {
        let (a,b)=pipe.gpu_diffusion_plans(width,height);
        [a,b].into_iter().flatten().try_fold(kernels,|bytes,p|bytes.checked_add((u64::from(p.small_w)*u64::from(p.small_h)).min(disk_window_pixels).checked_mul(36)?)).ok_or_else(||err("GPU_MEMORY_BUDGET"))?
    }else{0};
    let fields_bytes=if disk_diffusion{disk_reservation}else{resident_fields};
    struct DiffusionDirectory(PathBuf);
    impl Drop for DiffusionDirectory{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
    let disk_scratch=if disk_diffusion{[&camera_plan,&enlarger_plan].into_iter().flatten().map(|p|u64::from(p.small_w)*u64::from(p.small_h)*36).sum::<u64>()}else{0};
    #[cfg(unix)] if disk_diffusion {
        use std::os::unix::ffi::OsStrExt;
        let parent=output.parent().ok_or_else(||err("IO_ERROR"))?;
        let path=std::ffi::CString::new(parent.as_os_str().as_bytes()).map_err(|_|err("IO_ERROR"))?;
        let mut stat=std::mem::MaybeUninit::<libc::statvfs>::uninit();
        if unsafe{libc::statvfs(path.as_ptr(),stat.as_mut_ptr())}!=0{return Err(io(std::io::Error::last_os_error()));}
        let stat=unsafe{stat.assume_init()};
        if (stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64)<disk_scratch+64*1024*1024{return Err(err("INSUFFICIENT_STORAGE"));}
    }
    let disk_directory=if disk_diffusion {
        let path=film_log_attempt_path(output).with_extension("diffusion");fs::create_dir(&path).map_err(io)?;Some(DiffusionDirectory(path))
    }else{None};
    let mut disk_peak=0;
    if disk_diffusion {
        let (a,b)=pipe.gpu_diffusion_plans(width,height);
        for p in [a,b].into_iter().flatten(){
            let radius=p.sigmas.iter().map(|s|(3.0_f32*s).ceil().min(256.)as u32).max().unwrap_or(0);
            let b=spektrafilm_core::pipeline::disk_diffusion::plan(p.small_w,p.small_h,radius,budget,gpu.max_storage_buffer_binding_size(),disk_reservation).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
            disk_peak=disk_peak.max(b.estimated_bytes);
        }
    }
    let preview_bytes=if film.is_omitted(){0}else{crate::early_negative_preview::required_workspace_bytes(&pipe).unwrap_or(u64::MAX)};
    let preview_mib=preview_bytes.checked_add(1024*1024-1).map(|v|v/(1024*1024));
    // Admit the background worker only when both GPU prepass and a possible
    // CPU fallback can retain its full allowance without waiting for it.
    let candidate_fields=fields_bytes.checked_add(preview_bytes);
    let early_admitted=!disk_diffusion && !film.is_omitted() && preview_mib.is_some_and(|m|m<=u64::from(budget.saturating_sub(128))) && checkpoint_dir.and_then(Path::parent).is_some() && candidate_fields.is_some_and(|f|tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),fields_bytes).ok().zip(tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),f).ok()).is_some_and(|(a,b)|optional_plan_admitted(width,height,halo,a,b)));
    if early_admitted {
        if let Some(dir)=checkpoint_dir.and_then(Path::parent) {
            *early_preview=crate::early_negative_preview::spawn(input,dir,std::sync::Arc::clone(&pipe),width,height,preview_bytes).ok().flatten();
        }
    }else if let Some(dir)=checkpoint_dir.and_then(Path::parent) {
        let _=crate::negative_preview::mark_current_status(dir,if film.is_omitted(){"not_applicable"}else{"skipped_budget"},true);
    }
    let prepass_fields=fields_bytes.checked_add(if early_preview.is_some(){preview_bytes}else{0}).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    // A full-chain capacity probe must not reject independently bounded Front
    // taps or a later FilmLog split. PrintLinear still needs the spatial halo.
    let resident_probe=tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),prepass_fields);
    let prepass=global_effect&&(disk_diffusion||force_split==Some(true)||resident_probe.as_ref().map_or(true,|p|!p.whole));
    let front_plan=tile_plan(width,height,0,budget,gpu.max_storage_buffer_binding_size(),prepass_fields)?;
    let print_taps=prepass&&pipe.gpu_diffusion_plans(width,height).1.is_some();
    let prepass_plan=if print_taps {
        tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),prepass_fields)?
    }else{front_plan};
    let prepass_peak=if prepass{prepass_plan.estimated_bytes.max(front_plan.estimated_bytes).max(disk_peak)}else{0};
    let setup_ms=start.elapsed().as_secs_f64()*1000.;let submits_before=gpu.thread_submits();
    let mut bytes_read=0u64;
    // Prefix/suffix share one main-render work unit. Split is selected only
    // after construction scratch is gone and retained fields can be charged.
    progress.begin_stage(6,2+u64::from(prepass),"gpu_tiled_chain");
    // The entire runtime (including RNG, film/print calibration and effective
    // full-source AE) stays in this conservative key. Only late brightness is
    // excluded. Tile geometry is included because prepass reduction order matters.
    let mut field_cache=if prepass && !disk_diffusion { current_identity.as_ref().and_then(|identity| {
        let config=global_fields_configuration(&pipe.params,film_stock,paper_stock,ev,pitch,
            gpu as *const _ as usize,gpu.adapter_name(),prepass_plan,front_plan)?;
        crate::export_reuse::Lease::open(data_dir,identity.with_configuration(config).ok()?)
    }) }else{None};
    let mut cache_written=0;
    let mut fields=if prepass {
        let at=Instant::now();
        let restored=field_cache.as_mut().and_then(|cache|cache.load(fields_bytes.saturating_add(std::mem::size_of::<spektrafilm_core::pipeline::DiffusionScatteredFields>()as u64),cancelled));
        cancel(cancelled)?;
        if let Some(fields)=restored {
            progress.begin_work("gpu_diffusion_prepass");progress.end_work();
            stages.push(StageMetrics{stage:"gpu_global_fields_cache_hit",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
            Some(fields)
        }else{
        stages.push(StageMetrics{stage:if field_cache.is_some(){"gpu_global_fields_cache_miss"}else{"gpu_global_fields_cache_unavailable"},elapsed_ms:at.elapsed().as_secs_f64()*1000.});
        progress.begin_work("gpu_diffusion_prepass");let at=Instant::now();let mut read_error=None;
        let fields=if let Some(directory)=disk_directory.as_ref(){
            pipe.diffusion_scattered_fields_gpu_disk_progress(width,height,prepass_plan.width,prepass_plan.rows,
                front_plan.width,front_plan.rows,budget,gpu.max_storage_buffer_binding_size(),disk_reservation,disk_window_pixels,&directory.0,
                |x,y,w,h|match read_rect(input,width,height,x,y,w,h){Ok(v)=>{bytes_read+=w as u64*h as u64*12;Some(v)},Err(e)=>{read_error=Some(e);None}},
                ||!cancelled(),gpu,|done,total|progress.pass("gpu_diffusion_prepass",done,total))
        }else{
            pipe.diffusion_scattered_fields_gpu_tiled_progress(width,height,prepass_plan.width,prepass_plan.rows,
                front_plan.width,front_plan.rows,
                |x,y,w,h|match read_rect(input,width,height,x,y,w,h){Ok(v)=>{bytes_read+=w as u64*h as u64*12;Some(v)},Err(e)=>{read_error=Some(e);None}},
                ||!cancelled(),gpu,|done,total|progress.pass("gpu_diffusion_prepass",done,total))
        };
        if let Some(e)=read_error{return Err(e);}cancel(cancelled)?;
        // Disk construction failures are terminal: never turn storage/short-read
        // failures into a misleading GPU-to-CPU retry. Cancellation and input
        // read errors were already propagated above; backend panics retain the
        // existing uncertain-completion protection in streamed.rs.
        let fields=fields.ok_or_else(||err(if disk_diffusion{"IO_ERROR"}else{"GPU_EXECUTION_FAILED"}))?;
        progress.end_work();stages.push(StageMetrics{stage:if disk_diffusion{"gpu_diffusion_disk_prepass"}else{"gpu_diffusion_prepass"},elapsed_ms:at.elapsed().as_secs_f64()*1000.});
        let at=Instant::now();
        cache_written=field_cache.as_mut().map_or(0,|cache|cache.stage(&fields,cancelled));
        cancel(cancelled)?;
        stages.push(StageMetrics{stage:if cache_written>0{"gpu_global_fields_cache_staged"}else{"gpu_global_fields_cache_not_stored"},elapsed_ms:at.elapsed().as_secs_f64()*1000.});
        Some(fields)
        }
    }else{None};
    cancel(cancelled)?;
    // The prepass construction peak is gone. Charge its retained fields on
    // the main pass, plus its physical kernels and optional negative buffers.
    let retained=match fields.as_ref(){Some(f)=>Some(f.retained_working_bytes().ok_or_else(||err("GPU_MEMORY_BUDGET"))?),None=>None};
    let early_live=early_preview.as_ref().is_some_and(|h|h.running.load(std::sync::atomic::Ordering::Acquire));
    let main_fields=full_field_reservation(retained,fields_bytes,if early_live{preview_bytes}else{0},kernels)
        .ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
    let full_plan=tile_plan(width,height,halo,budget,gpu.max_storage_buffer_binding_size(),main_fields);
    let split=choose_main_split(width,height,halo,&full_plan,force_split)?;
    let spatial=split && pipe.gpu_physical_components(width,height).is_some();
    // For split rendering this is a real prefix plan, not a dummy full plan.
    // Both phases are checked again below with exact optional reservations.
    let mut plan=if split {
        let (legacy_halo,_)=pipe.gpu_split_halo_pixels(pitch);
        let prefix_halo=if spatial{pipe.gpu_scattered_raw_halo(width,height)}else{legacy_halo};
        tile_plan(width,height,prefix_halo,budget,gpu.max_storage_buffer_binding_size(),main_fields)?
    }else{full_plan?};
    if fields.is_some(){plan.whole=false;}
    let mut negative=if !pipe.film.is_omitted() && pipe.film.info.film_type=="negative" && checkpoint_dir.and_then(Path::parent).is_some() {
        crate::negative_preview::GpuDensityAssembly::new(width,height)
    }else{None};
    let mut exact_unavailable=if pipe.film.is_omitted(){"not_applicable"}else if pipe.film.info.film_type=="negative" {"skipped_budget"}else{"not_applicable"};
    if let Some(assembly)=negative.as_ref().filter(|_|!split) {
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
            if actual<=reserve {if optional_plan_admitted(width,height,halo,plan,candidate){chosen=Some(candidate);}break;}
            reserve=actual;
        }
        if let Some(candidate)=chosen {
            plan=candidate;exact_unavailable="failed";
            let old=negative.as_ref().and_then(|a|a.peak_reservation(plan.width,plan.rows)).unwrap_or(0);
            negative=negative.take().and_then(|a|a.upgrade_for_plans(&[(plan.width,plan.rows,plan.estimated_bytes)],u64::from(budget)*1024*1024));
            if let Some(new)=negative.as_ref().and_then(|a|a.peak_reservation(plan.width,plan.rows)) {plan.estimated_bytes+=new.saturating_sub(old);}
        }else{negative=None;}
    }
    let mut tile_index=0;let mut main_fraction=0;
    let mut written=0u64;
    let mut reported_plan=plan;
    let mut scratch_peak=0u64;
    let mut film_log_cache=None;
    let mut film_log_guard=None;
    if split {
        let (legacy_halo,suffix_halo)=pipe.gpu_split_halo_pixels(pitch);
        let prefix_halo=if spatial{pipe.gpu_scattered_raw_halo(width,height)}else{legacy_halo};
        let retained=match fields.as_ref(){Some(f)=>f.retained_working_bytes().ok_or_else(||err("GPU_MEMORY_BUDGET"))?,None=>0};

        // Compute the future suffix reservation without cloning its global fields.
        let camera=fields.as_mut().and_then(|f|f.camera.take());
        let suffix_retained=match fields.as_ref(){Some(f)=>f.retained_working_bytes().ok_or_else(||err("GPU_MEMORY_BUDGET"))?,None=>0};
        if let Some(ref mut f)=fields {f.camera=camera;}
        let early_reserve=if early_live{preview_bytes}else{0};
        let (prefix_fields,suffix_fields)=split_field_reservations(retained,suffix_retained,early_reserve,kernels)
            .ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
        let make_plans=|reserve:u64|->Result<(TilePlan,TilePlan)>{
            let mut a=tile_plan(width,height,prefix_halo,budget,gpu.max_storage_buffer_binding_size(),prefix_fields.checked_add(reserve).ok_or_else(||err("GPU_MEMORY_BUDGET"))?)?;
            let mut b=tile_plan(width,height,suffix_halo,budget,gpu.max_storage_buffer_binding_size(),suffix_fields.checked_add(reserve).ok_or_else(||err("GPU_MEMORY_BUDGET"))?)?;
            a.whole=false;b.whole=false;
            #[cfg(test)]
            tests::FILMLOG_TEST_CORE_ROWS.with(|rows|{if let Some(rows)=rows.get(){a.rows=a.rows.min(rows);b.rows=b.rows.min(rows);}});
            Ok((a,b))
        };
        let baseline=make_plans(0)?;
        let physical_capacity=|a:TilePlan,b:TilePlan|!spatial || u64::from(b.expanded_width)*u64::from(b.expanded_rows)>=u64::from(a.expanded_width)*u64::from(a.expanded_rows);
        let affordable=|a,b|physical_capacity(baseline.0,a)&&optional_plan_admitted(width,height,prefix_halo,baseline.0,a)&&optional_plan_admitted(width,height,suffix_halo,baseline.1,b);
        let mut chosen=None;
        if let Some(assembly)=negative.as_ref() {
            let mut reserve=assembly.peak_reservation(plan.width,plan.rows).unwrap_or(u64::MAX);
            for _ in 0..8 {
                let Ok((a,b))=make_plans(reserve) else{break};
                let Some(actual)=assembly.peak_reservation(a.width,a.rows).zip(assembly.peak_reservation(b.width,b.rows)).map(|(a,b)|a.max(b)) else{break};
                if actual<=reserve {if affordable(a,b){chosen=Some((a,b));exact_unavailable="failed";}break;}
                reserve=actual;
            }
            if chosen.is_none(){negative=None;}
        }
        let (cold_prefix,cold_suffix)=match chosen{Some(p)=>p,None=>make_plans(0)?};
        let (mut prefix,mut suffix)=(cold_prefix,cold_suffix);
        // Cache admission cannot displace either negative producer. Try a fully
        // prepaid proof/read workspace; otherwise retain the original cold plan.
        let mut cache_prepaid=false;
        if current_identity.is_some()&&crate::export_reuse::disk_reserve().is_some() {
            // Keep camera fields prepaid through a hit suffix, so a corrupt
            // cached read can safely replay without reallocation or cloning.
            let extra=crate::film_log_reuse::WORKING_BYTES.checked_add(retained.saturating_sub(suffix_retained)).ok_or_else(||err("GPU_MEMORY_BUDGET"))?;
            let mut reserve=extra;
            for _ in 0..8 {
                let Ok((a,b))=make_plans(reserve)else{break};
                let actual=if let Some(assembly)=negative.as_ref(){
                    assembly.peak_reservation(a.width,a.rows).zip(assembly.peak_reservation(b.width,b.rows)).and_then(|(a,b)|a.max(b).checked_add(extra))
                }else{Some(extra)};
                let Some(actual)=actual else{break};
                if actual<=reserve{if physical_capacity(cold_prefix,a)&&optional_plan_admitted(width,height,prefix_halo,cold_prefix,a)&&optional_plan_admitted(width,height,suffix_halo,cold_suffix,b){prefix=a;suffix=b;cache_prepaid=true;}break;}reserve=actual;
            }
        }
        if cache_prepaid {
            film_log_cache=current_identity.as_ref().and_then(|identity|{
                let config=film_log_configuration(&pipe,fields.as_ref(),ev,pitch,gpu as *const _ as usize,gpu.adapter_name(),prefix,prepass_plan,prefix_halo,cancelled)?;
                crate::film_log_reuse::Lease::open(data_dir,identity.with_configuration(config).ok()?,crate::film_log_reuse::Shape{width,height,core_width:prefix.width,core_rows:prefix.rows})
            });
        }
        if film_log_cache.is_none(){prefix=cold_prefix;suffix=cold_suffix;}
        let old_prefix=negative.as_ref().and_then(|a|a.peak_reservation(prefix.width,prefix.rows)).unwrap_or(0);
        let old_suffix=negative.as_ref().and_then(|a|a.peak_reservation(suffix.width,suffix.rows)).unwrap_or(0);
        negative=negative.take().and_then(|a|a.upgrade_for_plans(&[
            (prefix.width,prefix.rows,prefix.estimated_bytes),
            (suffix.width,suffix.rows,suffix.estimated_bytes),
        ],u64::from(budget)*1024*1024));
        if let Some(a)=negative.as_ref() {
            prefix.estimated_bytes+=a.peak_reservation(prefix.width,prefix.rows).unwrap_or(old_prefix).saturating_sub(old_prefix);
            suffix.estimated_bytes+=a.peak_reservation(suffix.width,suffix.rows).unwrap_or(old_suffix).saturating_sub(old_suffix);
        }
        let at=Instant::now();
        let hit=film_log_cache.as_mut().is_some_and(|cache|cache.load(cancelled));cancel(cancelled)?;
        stages.push(StageMetrics{stage:if hit{"gpu_film_log_cache_hit"}else if film_log_cache.is_some(){"gpu_film_log_cache_miss"}else{"gpu_film_log_cache_unavailable"},elapsed_ms:at.elapsed().as_secs_f64()*1000.});
        let total=tile_count(width,height,suffix)+if hit{0}else{tile_count(width,height,prefix)*if spatial{2}else{1}+if spatial{spatial_tiles(&pipe,width,height,prefix)?}else{0}};
        let log_path=if hit{film_log_cache.as_ref().unwrap().path()}else{film_log_attempt_path(output)};
        if hit{progress.begin_work("gpu_tiled_chain");}
        if !hit {
            if let Some(cache)=film_log_cache.as_mut(){cache.begin_prefix(cancelled);}
            progress.begin_work("gpu_tiled_chain");let at=Instant::now();
            let (r,w)=render_prefix(&pipe,gpu,fields.as_ref(),input,&log_path,width,height,prefix_halo,prefix,spatial,&mut tile_index,total,&mut main_fraction,submits_before,start,cancelled,progress,film_log_cache.as_mut())?;
            film_log_guard=Some(OutputGuard{path:log_path.clone(),keep:false});
            if let Some(cache)=film_log_cache.as_mut(){cache.finish_prefix(cancelled);}
            bytes_read+=r;written+=w;scratch_peak=if spatial{u64::from(width)*u64::from(height)*52}else{w};
            stages.push(StageMetrics{stage:"gpu_film_log",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
        }
        // A hit retains its already-prepaid camera fields only for corruption
        // rollback. A cold prefix is complete and can release them immediately.
        if !hit {if let Some(ref mut f)=fields{f.camera=None;f.highlight_max_raw=None;}}
        let capture_edge=negative.as_ref().map(|a|a.edge());
        let at=Instant::now();
        let suffix_result=render_tiles(&pipe,gpu,fields.as_ref(),&log_path,output,width,height,suffix_halo,suffix,TilePhase::FromFilmLog,2f64.powf(brightness),&mut tile_index,total,&mut main_fraction,submits_before,start,cancelled,progress,&mut negative,&mut exact_unavailable,film_log_cache.as_mut());
        let (r,w)=match suffix_result {
            Err(error) if hit&&error.code=="FILMLOG_CACHE_CORRUPT"=>{
                // render_tiles' owned output guard has removed every partial
                // result. Never publish old sampled density or mixed pixels.
                cancel(cancelled)?;if !gpu.is_healthy(){return Err(err("GPU_EXECUTION_FAILED"));}
                negative=None; // Retire partial capture before allocating its replacement.
                if let Some(edge)=capture_edge{negative=crate::negative_preview::GpuDensityAssembly::with_edge(width,height,edge);}
                let cache=film_log_cache.as_mut().unwrap();cache.invalidate();cache.begin_prefix(cancelled);
                stages.push(StageMetrics{stage:"gpu_film_log_cache_corrupt_replayed",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
                let fresh=film_log_attempt_path(output);
                let total=tile_index+tile_count(width,height,prefix)*if spatial{2}else{1}+tile_count(width,height,suffix)+if spatial{spatial_tiles(&pipe,width,height,prefix)?}else{0};
                let(r,w)=render_prefix(&pipe,gpu,fields.as_ref(),input,&fresh,width,height,prefix_halo,prefix,spatial,&mut tile_index,total,&mut main_fraction,submits_before,start,cancelled,progress,Some(&mut *cache))?;
                film_log_guard=Some(OutputGuard{path:fresh.clone(),keep:false});cache.finish_prefix(cancelled);bytes_read+=r;written+=w;scratch_peak=if spatial{u64::from(width)*u64::from(height)*52}else{w};
                if let Some(ref mut f)=fields{f.camera=None;f.highlight_max_raw=None;}
                render_tiles(&pipe,gpu,fields.as_ref(),&fresh,output,width,height,suffix_halo,suffix,TilePhase::FromFilmLog,2f64.powf(brightness),&mut tile_index,total,&mut main_fraction,submits_before,start,cancelled,progress,&mut negative,&mut exact_unavailable,Some(&mut *cache))?
            },other=>other?,
        };
        bytes_read+=r;written+=w;
        progress.end_work();stages.push(StageMetrics{stage:"gpu_from_film_log",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
        reported_plan=suffix;reported_plan.estimated_bytes=reported_plan.estimated_bytes.max(prefix.estimated_bytes).max(prepass_peak);
        if film_log_cache.as_ref().is_none_or(|cache|!cache.has_pending()){drop(film_log_guard.take());}
    }else{
        let stage=if plan.whole{"gpu_full_chain"}else{"gpu_tiled_chain"};
        progress.begin_work(stage);let at=Instant::now();
        let(r,w)=render_tiles(&pipe,gpu,fields.as_ref(),input,output,width,height,halo,plan,TilePhase::Full,2f64.powf(brightness),&mut tile_index,tile_count(width,height,plan),&mut main_fraction,submits_before,start,cancelled,progress,&mut negative,&mut exact_unavailable,None)?;
        bytes_read+=r;written+=w;
        progress.end_work();stages.push(StageMetrics{stage,elapsed_ms:at.elapsed().as_secs_f64()*1000.});
    }
    // render_tiles transferred its successfully created output to this scope.
    output_guard.keep=false;
    reported_plan.estimated_bytes=reported_plan.estimated_bytes.max(prepass_peak);
    let batches=gpu.thread_submits().saturating_sub(submits_before);
    if batches==0||!gpu.is_healthy(){return Err(err("GPU_EXECUTION_FAILED"));}
    progress.begin_work("output");
    if let Some(c)=checkpoints.as_mut(){c.commit(6,&[output.to_owned()],ev,cancelled).map_err(io)?;}
    // Sample finalized output with a bounded row buffer, rather than retaining
    // tile cores or duplicating full rows merely for the display thumbnail.
    if let Err(e)=crate::scan_preview::publish_restored(output,width,height,&params.io.output_color_space,cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}
    cancel(cancelled)?;progress.end_work();progress.finish_stage();
    if let Some(job_dir)=checkpoint_dir.and_then(Path::parent) {
        if let Some((preview_width,preview_height,samples))=negative.and_then(|a|a.complete()) {
            // Keep encoding inside the export permit and the capture reservation.
            // No detached worker can outlive this export's memory accounting.
            progress.begin_work("negative_preview");let at=Instant::now();
            let preview=crate::negative_preview::publish_sampled_negative(job_dir,&pipe.film,preview_width,preview_height,&samples,cancelled);
            progress.end_work();
            stages.push(StageMetrics{stage:"negative_preview",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
            if let Err(e)=preview {if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}

        }else{
            let _=crate::negative_preview::mark_current_status(job_dir,exact_unavailable,false);
        }
    }
    // No pending artifact survives a canceled/failed/quarantined GPU export.
    cancel(cancelled)?;
    if !gpu.is_healthy(){return Err(err("GPU_EXECUTION_FAILED"));}
    let at=Instant::now();
    if let Some(cache)=field_cache.as_mut() {
        let committed=cache.commit(cancelled);
        let (read,write)=cache.disk_io();bytes_read+=read;written+=write;
        cancel(cancelled)?;
        if cache_written>0 {stages.push(StageMetrics{stage:if committed{"gpu_global_fields_cache_committed"}else{"gpu_global_fields_cache_discarded"},elapsed_ms:at.elapsed().as_secs_f64()*1000.});}
    }
    if let Some(cache)=film_log_cache.as_mut(){
        let at=Instant::now();let was_hit=cache.is_hit();let committed=cache.complete(film_log_guard.as_ref().map(|guard|guard.path.as_path()),cancelled);
        let (read,write)=cache.disk_io();bytes_read+=read;written+=write;
        if let Err(error)=cancel(cancelled){cache.invalidate();return Err(error);}
        if !gpu.is_healthy(){cache.invalidate();return Err(err("GPU_EXECUTION_FAILED"));}
        stages.push(StageMetrics{stage:if committed{"gpu_film_log_cache_committed"}else if was_hit{"gpu_film_log_cache_retained"}else{"gpu_film_log_cache_not_stored"},elapsed_ms:at.elapsed().as_secs_f64()*1000.});
    }
    drop(film_log_guard);
    let diagnostics=crate::mobile::resident_diagnostics(gpu,batches,if !split && plan.whole{"full-resident-chain"}else{"tiled-chain"});
    let mut metrics=metric(width,height,budget,start,setup_ms,ev,diagnostics,reported_plan,bytes_read,written,stages);
    let proof_written=film_log_cache.as_ref().map_or(0,|cache|cache.disk_io().1);
    metrics.estimated_scratch_bytes=scratch_peak.saturating_add(cache_written).saturating_add(proof_written).saturating_add(disk_scratch);metrics.peak_logical_scratch_bytes=metrics.estimated_scratch_bytes;
    if film_log_cache.is_some(){metrics.memory_policy="Conservative 272B/expanded pixel + 96MiB + retained global fields; FilmLog proof/read workspace and rollback camera fields are additionally prepaid in prefix/suffix/capture tile plans";}
    if disk_diffusion{metrics.memory_policy="Bounded disk diffusion: 192B/expanded small pixel + 96MiB fixed + 32MiB margin + capped field windows; main chain 272B/pixel with field window reservations";}
    output_guard.keep=true;
    Ok(metrics)
}

#[cfg(test)]mod tests {
 use super::*;
 #[test]
 fn optional_reservations_never_amplify_authoritative_work() {
  for (w,h,halo) in [(2000,3000,297),(3072,4096,405),(4000,6000,591),(6000,8000,787)] {
   let base=tile_plan(w,h,halo,512,128*1024*1024,13_500_136+70_896);
   for extra in [0,4_188_144,18_742_880] {
    let candidate=tile_plan(w,h,halo,512,128*1024*1024,13_500_136+70_896+extra);
    if let (Ok(a),Ok(b))=(&base,&candidate) {
     let admitted=optional_plan_admitted(w,h,halo,*a,*b);
     let a=tile_cost(w,h,halo,a.width,a.rows);let b=tile_cost(w,h,halo,b.width,b.rows);
     assert_eq!(admitted,b.expanded_pixels<=a.expanded_pixels&&b.row_io<=a.row_io&&b.tiles<=a.tiles);
     if w==4000&&extra>0 {assert!(!admitted,"24 MP optional reservation must not multiply overlap");}
    }
   }
  }
 }
 #[test]
 fn restore_collision_and_cancel_never_delete_foreign_output() {
  let root=std::env::temp_dir().join(format!("gpu-restore-test-{}",std::process::id()));fs::create_dir(&root).unwrap();
  struct Cleanup(PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}let _cleanup=Cleanup(root.clone());
  let data=root.join("data");fs::create_dir(&data).unwrap();
  let input=root.join("input");fs::write(&input,[0u8;12]).unwrap();
  let identity=crate::checkpoint::Identity::new(&data,&input,1,1,"test".into(),&mut||false).unwrap();
  let mut checkpoint=crate::checkpoint::Checkpoints::open(&root.join("checkpoint"),identity,&mut||false).unwrap();
  checkpoint.commit(6,&[input],None,&mut||false).unwrap();
  let output=root.join("output");fs::write(&output,b"foreign").unwrap();
  assert!(restore_output(&checkpoint,&output,&mut||false).is_err());assert_eq!(fs::read(&output).unwrap(),b"foreign");
  fs::remove_file(&output).unwrap();
  let mut calls=0;
  assert_eq!(restore_output(&checkpoint,&output,&mut||{calls+=1;calls>=2}).unwrap_err().code,"CANCELLED");
  assert!(!output.exists());
  assert!(!fs::read_dir(&root).unwrap().any(|e|e.unwrap().file_name().to_string_lossy().starts_with(".gpu-restore-")));
  restore_output(&checkpoint,&output,&mut||false).unwrap();assert_eq!(fs::read(&output).unwrap(),[0u8;12]);
 }
 #[test]
 fn cinestill_non_split_tiled_prepass_reserves_physical_kernels() {
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let mut m=crate::mobile::MobileParams::default();m.film_stock="cinestill_800t".into();m.paper_stock="kodak_2383".into();
  m.grain_amount=0.;m.scatter_amount=0.;m.dir_amount=0.;m.film_format_mm=35.;
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();
  let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
  let pipe=Pipeline::new_with_spectral(film.clone(),paper,m.runtime_for_film(&film),&data).unwrap();
  let(w,h,budget,binding)=(768,512,128,128*1024*1024);
  let halo=pipe.banded_halo_pixels(35000./w as f32);
  let construction=pipe.gpu_diffusion_working_bytes(w,h).unwrap();
  let kernels=pipe.gpu_halation_working_bytes(w,h).unwrap();assert!(kernels>0);
  let prepass=tile_plan(w,h,halo,budget,binding,construction).unwrap();
  assert!(!prepass.whole);assert!(!split_is_worthwhile(w,h,halo,prepass));
  assert!(pipe.params.film_render.halation.headroom_active(),"headroom makes the non-split prepass reachable");
  let fields=spektrafilm_core::pipeline::DiffusionScatteredFields{highlight_max_raw:Some(1.),..Default::default()};
  let retained=fields.retained_working_bytes().unwrap();
  let reserve=full_field_reservation(Some(retained),construction,0,kernels).unwrap();
  assert_eq!(reserve,retained+kernels);
  let main=tile_plan(w,h,halo,budget,binding,reserve).unwrap();
  assert_eq!(main.estimated_bytes,FIXED_BYTES+reserve+u64::from(main.expanded_width)*u64::from(main.expanded_rows)*BAND_BYTES_PER_PIXEL);
  assert!(main.estimated_bytes<=u64::from(budget)*1024*1024);
 }

 #[test]
 fn cinestill_fields_some_none_and_split_full_reserve_kernels_once() {
  for kernels in [0,17] {for external in [false,true] {
   let retained=external.then_some(100);let suffix_retained=if external{20}else{0};
   let construction=400+kernels;let early=10;
   assert_eq!(full_field_reservation(retained,construction,early,kernels),
    Some(if external{110+kernels}else{410+kernels}),"full external={external} kernels={kernels}");
   assert_eq!(split_field_reservations(retained.unwrap_or(0),suffix_retained,early,kernels),
    Some((retained.unwrap_or(0)+early+kernels,suffix_retained+early)),
    "split external={external} kernels={kernels}");
  }}
  assert_eq!(full_field_reservation(Some(u64::MAX),0,0,1),None);
  assert_eq!(full_field_reservation(None,u64::MAX,1,17),None);
  assert_eq!(full_field_reservation(None,17,0,17),Some(17),"None construction already owns kernels");
 }

 #[test]
 fn cinestill_split_prefix_prepays_kernels_without_charging_suffix() {
  let kernels=spektrafilm_gpu::back_reflection_working_bytes(120.,1.,1.,5.).unwrap();
  assert!(kernels>0);
  assert_eq!(split_field_reservations(100,20,10,kernels),Some((110+kernels,30)));
  assert_eq!(split_field_reservations(100,20,10,0),Some((110,30)));
  assert_eq!(split_field_reservations(u64::MAX,20,10,kernels),None);
 }

 thread_local!{pub(super) static FILMLOG_TEST_CORE_ROWS:std::cell::Cell<Option<u32>>=const{std::cell::Cell::new(None)};}
 #[cfg(unix)]
 #[test]
 #[ignore="Actual GPU FilmLog cross-export, suffix-change, corruption and cancellation oracle; explicit serial GPU window required"]
 fn film_log_cross_export_real_gpu(){
  let gpu=crate::mobile::full_chain_gpu().expect("Configured actual GPU required");assert!(gpu.is_healthy());
  struct ResetGeometry;impl Drop for ResetGeometry{fn drop(&mut self){FILMLOG_TEST_CORE_ROWS.set(None);}}
  FILMLOG_TEST_CORE_ROWS.set(Some(36));let _reset_geometry=ResetGeometry;
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data").canonicalize().unwrap();
  let root=std::env::temp_dir().join(format!("gpu-filmlog-reuse-{}",std::process::id()));fs::create_dir(&root).unwrap();
  struct Cleanup(PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}let _cleanup=Cleanup(root.clone());
  fs::create_dir(root.join("files")).unwrap();fs::create_dir(root.join("cache")).unwrap();let local_data=root.join("files/data");std::os::unix::fs::symlink(data,&local_data).unwrap();
  let(w,h)=(65u32,71u32);let source=(0..w*h*3).flat_map(|i|(0.01f32+(i%251)as f32/300.).to_le_bytes()).collect::<Vec<_>>();
  let mut attempt=0;
  for(route,film_name,paper_name)in[("print","kodak_portra_400","kodak_portra_endura"),("print","kodak_trix","kodak_2302"),("direct","kodak_portra_400","kodak_portra_endura")]{
   let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.output_route=route.into();m.film_stock=film_name.into();m.paper_stock=paper_name.into();m.diffusion_filter="black_pro_mist".into();m.print_diffusion_filter="glimmerglass".into();m.auto_exposure=true;m.highlight_boost_ev=0.25;
   let film=spektrafilm_core::profile::load_profile_by_name(&local_data,film_name).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&local_data,paper_name).unwrap();let base=m.runtime_for_film(&film);
   let mut run=|params:&RuntimeParams,brightness:f64,cache:bool,cancel_on_gpu:bool,corrupt_after_first_tile:bool|{
    attempt+=1;let job=root.join(format!("job-{attempt}"));fs::create_dir(&job).unwrap();let input=job.join("input.rgb");let output=job.join("output.rgb");fs::write(&input,&source).unwrap();
    let cancel_now=std::cell::Cell::new(false);let corrupted=std::cell::Cell::new(false);
    let mut reporter=crate::streamed_progress::Reporter::new(|p|{
        if cancel_on_gpu&&p.gpu.is_some_and(|g|g.completed_submits>0){cancel_now.set(true);}
        if corrupt_after_first_tile&&p.pass_updated&&!corrupted.replace(true){
            // This callback is after the first suffix core was written. Its GPU
            // call completed and fenced before the next cached read can fail.
            assert!(output.metadata().unwrap().len()>0);
            let mut file=OpenOptions::new().write(true).open(root.join("cache/film-export-filmlog-reuse-v1/image.bin")).unwrap();
            file.seek(SeekFrom::Start((u64::from(w)*u64::from(h)-1)*12)).unwrap();file.write_all(&f32::NAN.to_le_bytes()).unwrap();file.sync_all().unwrap();
        }
    });
    let result=crate::export_reuse::with_disk_reserve(cache.then_some(64*1024*1024),||export_with_split(params,film_name,paper_name,&film,&paper,&local_data,&input,&output,Some(&job.join("checkpoint")),w,h,128,brightness,&mut||cancel_now.get(),&mut reporter,Some(true)));
    match result{Ok(metrics)=>{assert!(metrics.gpu.active&&metrics.gpu.gpu_batches>0);assert!(metrics.spatial_peak_explicit_buffer_bytes<=128*1024*1024);let bytes=fs::read(&output).unwrap();assert_eq!(bytes.len(),w as usize*h as usize*12);fs::remove_dir_all(&job).unwrap();Ok((bytes,metrics))},Err(error)=>{assert!(!output.exists(),"Failed attempt must remove its partial output");fs::remove_dir_all(&job).unwrap();Err(error)}}
   };
   let has=|metrics:&StreamedMetrics,name:&str|metrics.stages.iter().any(|s|s.stage==name);
   let(cold,cold_metrics)=run(&base,0.,true,false,false).unwrap();assert!(has(&cold_metrics,"gpu_film_log_cache_committed"));
   let(hit,hit_metrics)=run(&base,0.,true,false,false).unwrap();assert!(has(&hit_metrics,"gpu_film_log_cache_hit"));assert!(!has(&hit_metrics,"gpu_film_log"));assert_eq!(cold,hit);
   let(bright,bright_metrics)=run(&base,1.,true,false,false).unwrap();assert!(has(&bright_metrics,"gpu_film_log_cache_hit"));let(oracle,_)=run(&base,1.,false,false,false).unwrap();assert_eq!(bright,oracle,"Original uncached split route is the brightness oracle");
   let mut suffix=base.clone();suffix.scanner.unsharp_mask=[0.5,0.6];
   let(changed,changed_metrics)=run(&suffix,0.,true,false,false).unwrap();assert!(has(&changed_metrics,"gpu_film_log_cache_hit"),"Scanner-only change must reuse FilmLog while using fresh suffix state");let(oracle,_)=run(&suffix,0.,false,false,false).unwrap();assert_eq!(changed,oracle);
   let image=root.join("cache/film-export-filmlog-reuse-v1/image.bin");
   let(replayed,replayed_metrics)=run(&suffix,0.,true,false,true).unwrap();assert!(has(&replayed_metrics,"gpu_film_log_cache_corrupt_replayed"));assert_eq!(replayed,oracle,"Corruption must replay cold, never mix cached and cold output");
   let mut upstream=suffix.clone();upstream.camera.exposure_compensation_ev+=0.25;
   let(changed,upstream_metrics)=run(&upstream,0.,true,false,false).unwrap();assert!(!has(&upstream_metrics,"gpu_film_log_cache_hit"));let(oracle,_)=run(&upstream,0.,false,false,false).unwrap();assert_eq!(changed,oracle);
   let error=run(&upstream,0.,true,true,false).unwrap_err();assert_eq!(error.code,"CANCELLED");assert!(!image.exists(),"Cancelled/failed active lease must not survive as a reusable entry");assert!(gpu.is_healthy());
   eprintln!("FILMLOG_CROSS_EXPORT route={route} film={film_name} coldSubmits={} hitSubmits={} coldMs={} hitMs={}",cold_metrics.gpu.gpu_batches,hit_metrics.gpu.gpu_batches,cold_metrics.elapsed_ms,hit_metrics.elapsed_ms);
  }
 }

 #[test]fn film_log_key_covers_actual_fields_backend_ae_pitch_and_geometry(){
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let m=crate::mobile::MobileParams::default();
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
  let pipe=Pipeline::new_with_spectral(film.clone(),paper,m.runtime_for_film(&film),&data).unwrap();
  let plan=TilePlan{width:32,rows:27,expanded_width:64,expanded_rows:64,estimated_bytes:0,whole:false};
  let fields=spektrafilm_core::pipeline::DiffusionScatteredFields{highlight_max_raw:Some(1.),camera:Some(spektrafilm_core::pipeline::DiffusionScattered{disk:None,crop:None,field:vec![0.,1.,2.],small_w:1,small_h:1,d:4,p_s:0.25}),enlarger:None};
  let key=|fields:Option<&spektrafilm_core::pipeline::DiffusionScatteredFields>,ev,pitch,backend,adapter,prefix,prepass,halo|film_log_configuration(&pipe,fields,ev,pitch,backend,adapter,prefix,prepass,halo,&mut||false).unwrap();
  let original=key(Some(&fields),Some(0.25),4.25,1,"gpu",plan,plan,4);
  for changed in[key(None,Some(0.25),4.25,1,"gpu",plan,plan,4),key(Some(&fields),Some(0.5),4.25,1,"gpu",plan,plan,4),key(Some(&fields),Some(0.25),4.5,1,"gpu",plan,plan,4),key(Some(&fields),Some(0.25),4.25,2,"gpu",plan,plan,4),key(Some(&fields),Some(0.25),4.25,1,"other",plan,plan,4),key(Some(&fields),Some(0.25),4.25,1,"gpu",TilePlan{rows:28,..plan},plan,4),key(Some(&fields),Some(0.25),4.25,1,"gpu",plan,TilePlan{rows:28,..plan},4),key(Some(&fields),Some(0.25),4.25,1,"gpu",plan,plan,5)]{assert_ne!(original,changed);}
  let mut changed=fields.clone();changed.highlight_max_raw=Some(2.);assert_ne!(original,key(Some(&changed),Some(0.25),4.25,1,"gpu",plan,plan,4));
  let mut changed=fields.clone();changed.camera.as_mut().unwrap().field[0]=-0.;assert_ne!(original,key(Some(&changed),Some(0.25),4.25,1,"gpu",plan,plan,4));
  let mut changed=fields.clone();changed.enlarger=changed.camera.clone();assert_eq!(original,key(Some(&changed),Some(0.25),4.25,1,"gpu",plan,plan,4),"Fresh downstream enlarger field is not a FilmLog dependency");
 }

 #[test]fn global_fields_key_covers_science_rng_backend_ae_and_reduction_geometry(){
  let p=RuntimeParams::default();let plan=TilePlan{width:32,rows:27,expanded_width:64,expanded_rows:64,estimated_bytes:0,whole:false};
  let key=|p:&RuntimeParams|global_fields_configuration(p,"film","paper",Some(0.25),4.25,123,"adapter",plan,plan).unwrap();let original=key(&p);
  let changes:Vec<Box<dyn Fn(&mut RuntimeParams)>>=vec![
   Box::new(|p|p.camera.exposure_compensation_ev+=0.1),Box::new(|p|p.camera.auto_exposure_method="median".into()),
   Box::new(|p|p.camera.pixel_size_um_override=Some(4.25)),Box::new(|p|p.camera.diffusion_filter.strength+=0.1),
   Box::new(|p|p.enlarger.y_filter_shift+=1.),Box::new(|p|p.print_render.density_curves_morph.gamma_factor+=0.1),
   Box::new(|p|p.film_render.dir_couplers.amount+=0.1),Box::new(|p|p.film_render.grain.seed+=1),
   Box::new(|p|p.film_render.grain.coordinate_origin=Some([1,0])),Box::new(|p|p.film_render.halation.boost_ev+=1.),
   Box::new(|p|p.film_render.development_time=Some(8.)),Box::new(|p|p.print_render.development_time=Some(3.)),
   Box::new(|p|p.io.input_color_space="Display P3".into()),Box::new(|p|p.io.output_color_space="Display P3".into()),
   Box::new(|p|p.scanner.unsharp_mask=[0.5,0.6]),Box::new(|p|p.settings.lut_resolution+=1),
  ];
  for change in changes{let mut changed=p.clone();change(&mut changed);assert_ne!(original,key(&changed));}
  for changed in [global_fields_configuration(&p,"other-film","paper",Some(0.25),4.25,123,"adapter",plan,plan),
    global_fields_configuration(&p,"film","other-paper",Some(0.25),4.25,123,"adapter",plan,plan),
    global_fields_configuration(&p,"film","paper",Some(0.5),4.25,123,"adapter",plan,plan),
    global_fields_configuration(&p,"film","paper",Some(0.25),5.,123,"adapter",plan,plan),
    global_fields_configuration(&p,"film","paper",Some(0.25),4.25,124,"adapter",plan,plan),
    global_fields_configuration(&p,"film","paper",Some(0.25),4.25,123,"other-adapter",plan,plan),
    global_fields_configuration(&p,"film","paper",Some(0.25),4.25,123,"adapter",TilePlan{rows:28,..plan},plan),
    global_fields_configuration(&p,"film","paper",Some(0.25),4.25,123,"adapter",plan,TilePlan{width:12,..plan})] {
      assert_ne!(Some(&original),changed.as_ref());
  }
 }

 #[cfg(unix)]
 #[test]
 #[ignore="Actual GPU cross-job reuse/cold equivalence, explicit native/GPU window required"]
 fn global_fields_cross_export_real_gpu(){
  let gpu=crate::mobile::full_chain_gpu().expect("This gate requires a real configured GPU backend");assert!(gpu.is_healthy());
  let original_data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data").canonicalize().unwrap();
  let root=std::env::temp_dir().join(format!("gpu-global-reuse-{}",std::process::id()));fs::create_dir(&root).unwrap();
  struct Cleanup(PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}let _cleanup=Cleanup(root.clone());
  fs::create_dir(root.join("files")).unwrap();fs::create_dir(root.join("cache")).unwrap();
  let data=root.join("files/engine-data");std::os::unix::fs::symlink(original_data,&data).unwrap();
  let(w,h)=(65u32,71u32);let input_bytes=(0..w*h*3).flat_map(|i|(0.01f32+(i%251)as f32/300.).to_le_bytes()).collect::<Vec<_>>();
  for (route,film_name,paper_name) in [("print","kodak_portra_400","kodak_portra_endura"),("print","kodak_trix","kodak_2302"),("direct","kodak_portra_400","kodak_portra_endura")] {
   let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.output_route=route.into();m.film_stock=film_name.into();m.paper_stock=paper_name.into();
   m.diffusion_filter="black_pro_mist".into();m.print_diffusion_filter="glimmerglass".into();m.auto_exposure=true;m.highlight_boost_ev=0.25;
   let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
   let params=m.runtime_for_film(&film);let mut outputs=Vec::new();
   // Unique checkpoint directories prove this is not same-job checkpoint resume.
   for (attempt,brightness,cache) in [(0,0.,true),(1,0.,true),(2,1.,true),(3,1.,false)] {
    let job=root.join(format!("job-{route}-{film_name}-{attempt}"));fs::create_dir(&job).unwrap();
    let input=job.join("input.rgb");fs::write(&input,&input_bytes).unwrap();let output=job.join("output.rgb");
    let mut reporter=crate::streamed_progress::Reporter::new(|_|{});
    let metrics=crate::export_reuse::with_disk_reserve(cache.then_some(64*1024*1024),||export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,Some(&job.join("checkpoint")),w,h,128,brightness,&mut||false,&mut reporter,Some(true))).unwrap();
    assert!(metrics.gpu.active && metrics.gpu.gpu_batches>0);
    let hit=metrics.stages.iter().any(|s|s.stage=="gpu_global_fields_cache_hit");assert_eq!(hit,attempt==1||attempt==2,"route={route} attempt={attempt}");
    assert_eq!(metrics.stages.iter().any(|s|s.stage=="gpu_diffusion_prepass"),!hit);
    if attempt==0 {assert!(metrics.stages.iter().any(|s|s.stage=="gpu_global_fields_cache_committed"));}
    eprintln!("GLOBAL_FIELDS_REUSE route={route} film={film_name} attempt={attempt} cacheHit={hit} timeMs={} submits={} readBytes={} writeBytes={}",metrics.elapsed_ms,metrics.gpu.gpu_batches,metrics.spatial_bytes_read,metrics.spatial_bytes_written);
    outputs.push(fs::read(output).unwrap());fs::remove_dir_all(job).unwrap();
   }
   assert_eq!(outputs[0],outputs[1],"Cold vs fresh-job cache hit must preserve exact f32 output");
   assert_eq!(outputs[2],outputs[3],"Brightness reuse must equal a cold rerender with the same brightness");
  }
 }

 #[test]fn lower_budget_reduces_real_band_geometry(){let high=band_plan(2000,6000,20,384,128*1024*1024,4*1024*1024).unwrap();let low=band_plan(2000,6000,20,192,128*1024*1024,4*1024*1024).unwrap();assert!(low.rows<high.rows);assert!(low.estimated_bytes<high.estimated_bytes);assert!(low.estimated_bytes<=192*1024*1024);assert_eq!(low.expanded_rows,low.rows+40);}
 #[test]fn fixed_fields_and_halo_cannot_escape_budget(){assert_eq!(band_plan(8000,6000,500,128,128*1024*1024,0).unwrap_err().code,"GPU_MEMORY_BUDGET");assert!(band_plan(2000,6000,0,128,128*1024*1024,40*1024*1024).is_err());}
 #[test]fn binding_is_checked_separately(){assert_eq!(band_plan(2000,6000,20,512,1024,0).unwrap_err().code,"GPU_BINDING_LIMIT");}
 #[test]fn whole_frame_has_no_artificial_halo(){let p=band_plan(32,16,2000,128,128*1024*1024,0).unwrap();assert!(p.whole);assert_eq!(p.rows,16);assert_eq!(p.expanded_rows,16);}
 #[test]fn released_prepass_workspace_expands_device_main_tiles_without_cutting_halo(){
  // Recorded op10: full-size 50MP, Black Pro-Mist0.5, 415MiB budget.
  // Exact conservative prepass reservation from the resolved diffusion plan.
  let (w,h,halo,budget,prepass_bytes)=(6144u32,8192u32,348u32,415u32,79_959_360u64);
  let before=tile_plan(w,h,halo,budget,128*1024*1024,prepass_bytes).unwrap();
  let small_pixels=prepass_bytes/192;
  let retained=small_pixels*36+std::mem::size_of::<spektrafilm_core::pipeline::DiffusionScatteredFields>()as u64;
  let after=tile_plan(w,h,halo,budget,128*1024*1024,retained).unwrap();
  // Prior released GPU.2 used max-core-area geometry301x301 (588tiles).
  // Current cost planner may already choose a better shape at the same peak.
  assert!(tile_count(w,h,before)<=588);
  assert!(after.width>before.width&&after.rows>before.rows);
  assert!(after.estimated_bytes<=budget as u64*1024*1024);
  assert_eq!(after.expanded_width,after.width+2*halo);
  assert_eq!(after.expanded_rows,after.rows+2*halo);
  assert!(w.div_ceil(after.width)*h.div_ceil(after.rows)<588);
  eprintln!("DEVICE_OP10_PHASE_BUDGET before={}x{} retained={retained} main={}x{} tiles={} bytes={}",before.width,before.rows,after.width,after.rows,w.div_ceil(after.width)*h.div_ceil(after.rows),after.estimated_bytes);
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
   written+=write_core(&mut out,&rendered,w,left,top,x,y,right,end,0.5,&mut||false,None).unwrap();
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


 #[test]fn adaptive_shapes_use_real_budget_and_global_io_without_transposing_pixels(){
  for(w,h,halo,budget)in[(6144u32,8192u32,348u32,415u32),(8192,6144,89,415),(640,8192,4,128),(8192,640,4,128)] {
   let p=tile_plan(w,h,halo,budget,128*1024*1024,0).unwrap();
   assert!(p.estimated_bytes<=budget as u64*1024*1024);
   let chosen=tile_cost(w,h,halo,p.width,p.rows);
   let available=(budget as u64*1024*1024-FIXED_BYTES)/BAND_BYTES_PER_PIXEL;
   for cw in 1..=w {
    if cw>1024&&cw!=w {continue;}
    let ew=(cw as u64+halo as u64*2).min(w as u64);
    let mh=(available/ew).min(h as u64);
    let ch=if mh==h as u64{mh}else{mh.saturating_sub(halo as u64*2).min(1024)};
    if ch==0 {continue;}
    assert!(chosen<=tile_cost(w,h,halo,cw,ch as u32));
   }
   eprintln!("ADAPTIVE_PLAN {w}x{h} halo={halo} core={}x{} cost={chosen:?}",p.width,p.rows);
  }
 }
 #[test]
 #[ignore = "Controlled multi-megapixel GPU comparison; explicit resource window required"]
 fn film_log_same_input_benchmark(){
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.diffusion_filter="glimmerglass".into();
  // Synthetic 3MP field at the recorded 50MP pixel pitch. Not the user's photo,
  // not a smaller-resolution export of it, and not a physical-device timing.
  let(w,h)=(1536u32,2048u32);m.film_format_mm=35.*h as f64/8192.;
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
  let params=m.runtime_for_film(&film);let root=std::env::temp_dir().join(format!("filmlog-benchmark-{}",std::process::id()));fs::create_dir(&root).unwrap();
  struct Cleanup(PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}let _cleanup=Cleanup(root.clone());
  let input=root.join("input.rgb");let mut file=File::create(&input).unwrap();
  for y in 0..h {let row:Vec<u8>=(0..w*3).flat_map(|i|(0.03f32+((i*17+y*23)%997)as f32/1100.).to_le_bytes()).collect();file.write_all(&row).unwrap();}drop(file);
  let mut paths=Vec::new();
  for split in [false,true] {
   let out=root.join(format!("output-{split}.rgb"));let mut reporter=crate::streamed_progress::Reporter::new(|_|{});let at=Instant::now();
   let metrics=export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&out,None,w,h,512,0.,&mut||false,&mut reporter,Some(split)).unwrap();
   assert!(metrics.gpu.active&&metrics.gpu.gpu_batches>0);assert_eq!(fs::metadata(&out).unwrap().len(),w as u64*h as u64*12);
   eprintln!("FILMLOG_BENCH split={split} size={w}x{h} pitch={} milliseconds={} submits={} bytesRead={} bytesWritten={} scratchPeak={}",35.*1000./8192.,at.elapsed().as_millis(),metrics.gpu.gpu_batches,metrics.spatial_bytes_read,metrics.spatial_bytes_written,metrics.peak_logical_scratch_bytes);
   paths.push(out);
  }
  let(mut a,mut b)=(File::open(&paths[0]).unwrap(),File::open(&paths[1]).unwrap());let(mut av,mut bv)=(vec![0u8;w as usize*12],vec![0u8;w as usize*12]);let(mut max,mut squares)=(0f64,0f64);
  for _ in 0..h {a.read_exact(&mut av).unwrap();b.read_exact(&mut bv).unwrap();for(x,y)in av.chunks_exact(4).zip(bv.chunks_exact(4)){let d=(f32::from_le_bytes(x.try_into().unwrap())-f32::from_le_bytes(y.try_into().unwrap())).abs()as f64;assert!(d.is_finite());max=max.max(d);squares+=d*d;}}
  let rms=(squares/(w as f64*h as f64*3.)).sqrt();eprintln!("FILMLOG_BENCH_COMPARE max={max} rms={rms} sameInput=true sameEffects=true sameResolution=true");
  assert!(max<0.00002,"same GPU model split regression max={max}");
 }

 #[test]fn fifty_megapixel_glimmerglass_admits_bounded_plan(){
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let mut m=crate::mobile::MobileParams::default();m.film_format_mm=93.12;m.diffusion_filter="glimmerglass".into();m.diffusion_strength=0.5;
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();let p=Pipeline::new_with_spectral(film.clone(),paper,m.runtime_for_film(&film),&data).unwrap();
  let(w,h,budget,binding)=(8192,6144,512,128*1024*1024);let old=p.gpu_diffusion_working_bytes(w,h).unwrap();assert!(tile_plan(w,h,0,budget,binding,old).is_err());let (camera,_)=p.gpu_diffusion_plans(w,h);let camera=camera.unwrap();assert_eq!((camera.d,camera.small_w,camera.small_h),(3,2731,2048));
  let cap=u64::from(budget)*1024*1024/BAND_BYTES_PER_PIXEL;let fields=cap*36+p.gpu_halation_working_bytes(w,h).unwrap();let radius=camera.sigmas.iter().map(|s|(3.0_f32*s).ceil().min(256.)as u32).max().unwrap();
  let band=spektrafilm_core::pipeline::disk_diffusion::plan(camera.small_w,camera.small_h,radius,budget,binding,fields).unwrap();assert!(band.rows>128);assert!(band.estimated_bytes<=512*1024*1024);assert!(tile_plan(w,h,0,budget,binding,fields).is_ok());eprintln!("FIFTY_MP_DIFFUSION_PLAN rows={} estimate={} old={}",band.rows,band.estimated_bytes,old);
 }
 #[test]fn bounded_diffusion_matches_resident_dual_filter_export_and_cleans(){
  let gpu=crate::mobile::full_chain_gpu().expect("real WGSL software/hardware adapter required");
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.grain_amount=0.;m.diffusion_filter="glimmerglass".into();m.print_diffusion_filter="black_pro_mist".into();m.diffusion_strength=0.5;
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();let params=m.runtime_for_film(&film);let(w,h)=(97u32,113u32);
  let root=std::env::temp_dir().join(format!("bounded-diffusion-export-{}",std::process::id()));fs::create_dir(&root).unwrap();
  struct Cleanup(PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);FORCE_DISK_DIFFUSION.with(|v|v.set(false));}}let _cleanup=Cleanup(root.clone());
  let input=root.join("input.rgb");fs::write(&input,(0..w*h*3).flat_map(|i|(0.01f32+(i%251)as f32/300.).to_le_bytes()).collect::<Vec<_>>()).unwrap();let mut outputs=Vec::new();
  for disk in [false,true]{FORCE_DISK_DIFFUSION.with(|v|v.set(disk));let output=root.join(format!("out-{disk}"));let mut reporter=crate::streamed_progress::Reporter::new(|_|{});
   let metrics=export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,None,w,h,256,0.,&mut||false,&mut reporter,Some(true)).unwrap();assert!(metrics.gpu.active);assert!(metrics.spatial_peak_explicit_buffer_bytes<=256*1024*1024);if disk{assert!(metrics.stages.iter().any(|s|s.stage=="gpu_diffusion_disk_prepass"));}
   outputs.push(fs::read(output).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect::<Vec<_>>());assert!(!fs::read_dir(&root).unwrap().any(|e|e.unwrap().path().extension().is_some_and(|e|e=="diffusion")));
  }
  let max=outputs[0].iter().zip(&outputs[1]).map(|(a,b)|(a-b).abs()).fold(0f32,f32::max);assert!(max<0.00002,"disk full export max={max}");
  let mut checks=0;let output=root.join("cancel");let mut reporter=crate::streamed_progress::Reporter::new(|_|{});let e=export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,None,w,h,256,0.,&mut||{checks+=1;checks>30},&mut reporter,Some(true)).unwrap_err();assert_eq!(e.code,"CANCELLED");assert!(!output.exists());assert!(!fs::read_dir(&root).unwrap().any(|e|e.unwrap().path().extension().is_some_and(|e|e=="diffusion")));assert!(gpu.is_healthy());
  eprintln!("BOUNDED_DIFFUSION_FULL_EXPORT max={max} dual=true cleanup=true cancellation=true");
 }
 #[test]fn split_spool_preserves_full_size_effects_and_owned_cleanup(){
  let Some(gpu)=crate::mobile::full_chain_gpu() else {
   assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"));return;
  };assert!(gpu.is_healthy());
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.grain_amount=0.;m.diffusion_filter="black_pro_mist".into();m.print_diffusion_filter="glimmerglass".into();
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
  let params=m.runtime_for_film(&film);let(w,h)=(257u32,263u32);
  let root=std::env::temp_dir().join(format!("gpu-filmlog-split-{}",std::process::id()));fs::create_dir(&root).unwrap();
  struct Cleanup(PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}let _cleanup=Cleanup(root.clone());
  let input=root.join("input.rgb");fs::write(&input,(0..w*h*3).flat_map(|i|(0.01f32+(i%251)as f32/300.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut outputs=Vec::new();
  for split in [false,true] {
   let output=root.join(format!("out-{split}.rgb"));
   let mut reporter=crate::streamed_progress::Reporter::new(|_|{});
   let metrics=export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,None,w,h,128,0.,&mut||false,&mut reporter,Some(split)).unwrap();
   assert!(metrics.gpu.active);assert!(metrics.gpu.gpu_batches>0);assert_eq!(fs::metadata(&output).unwrap().len(),w as u64*h as u64*12);
   assert!(!output.with_extension("gpu-film-log.rgb").exists());
   assert_eq!(metrics.peak_logical_scratch_bytes,if split {w as u64*h as u64*if Pipeline::new(film.clone(),paper.clone(),params.clone()).gpu_physical_components(w,h).is_some(){52}else{12}}else{0});
   outputs.push(fs::read(&output).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect::<Vec<_>>());
  }
  let max=outputs[0].iter().zip(&outputs[1]).map(|(a,b)|(a-b).abs()).fold(0f32,f32::max);
  // Same GPU pipeline, but whole diffusion and external-field reduction order
  // can differ by float rounding. This is a numerical regression bound, not a
  // perceptual-quality or CPU-equality claim.
  assert!(max<0.00002,"split vs whole max={max}");
  let output=root.join("cancel.rgb");let cancel_flag=std::cell::Cell::new(false);
  let mut reporter=crate::streamed_progress::Reporter::new(|e|{if e.gpu.and_then(|g|g.phase)==Some("dir"){cancel_flag.set(true);}});
  let error=export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,None,w,h,128,0.,&mut||cancel_flag.get(),&mut reporter,Some(true)).unwrap_err();
  assert_eq!(error.code,"CANCELLED");assert!(!output.exists());assert!(!output.with_extension("gpu-film-log.rgb").exists());assert!(gpu.is_healthy());
  let output=root.join("collision.rgb");let log=output.with_extension("gpu-film-log.rgb");fs::write(&log,b"foreign fixture").unwrap();
  let mut reporter=crate::streamed_progress::Reporter::new(|_|{});
  assert!(export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,None,w,h,128,0.,&mut||false,&mut reporter,Some(true)).is_ok());
  assert_eq!(fs::read(&log).unwrap(),b"foreign fixture");assert!(output.exists());
  assert_ne!(film_log_attempt_path(&output),film_log_attempt_path(&output));
  assert!(!fs::read_dir(&root).unwrap().any(|e|e.unwrap().file_name().to_string_lossy().contains("gpu-film-log-")),"owned attempt spools must be cleaned");
  let foreign=root.join("foreign-output.rgb");fs::write(&foreign,b"foreign-output").unwrap();
  let mut reporter=crate::streamed_progress::Reporter::new(|_|{});
  assert!(export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&foreign,None,w,h,128,0.,&mut||false,&mut reporter,Some(true)).is_err());
  assert_eq!(fs::read(&foreign).unwrap(),b"foreign-output");
  eprintln!("GPU_FILMLOG_SPOOL_PASS max={max} fullsize={w}x{h} dualfilters cancellation cleanup collision_preserved");
 } #[test]fn staged_cinestill_main_plans_fit_representative_megapixels(){
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let mut m=crate::mobile::MobileParams::default();m.film_stock="cinestill_800t".into();m.paper_stock="kodak_2383".into();m.diffusion_filter="black_pro_mist".into();m.diffusion_strength=2.;
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
  let pipe=Pipeline::new(film.clone(),paper,m.runtime_for_film(&film));
  // Deliberately conservative fixed retained reservation, not measured RSS.
  for (w,h) in [(2000,3000),(3072,4096),(4000,6000),(6000,8000)] {
   let halo=pipe.gpu_scattered_raw_halo(w,h);let p=tile_plan(w,h,halo,512,128*1024*1024,160*1024*1024).unwrap();
   assert!(p.estimated_bytes<=512*1024*1024);assert!(p.width>0&&p.rows>0);assert!(pipe.gpu_physical_components(w,h).is_some());
   let tasks=spatial_tiles(&pipe,w,h,p).unwrap();assert!(tasks>0);
   eprintln!("STAGED_MAIN_PLAN {w}x{h} halo={halo} core={}x{} bytes={} physicalTiles={tasks}",p.width,p.rows,p.estimated_bytes);
  }
 }
 #[test]fn cinestill_staged_prefix_matches_resident_and_cleans_up(){
  let Some(gpu)=crate::mobile::full_chain_gpu() else {
   assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"));return;
  };assert!(gpu.is_healthy());
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.film_stock="cinestill_800t".into();m.paper_stock="kodak_2383".into();m.grain_amount=0.;m.diffusion_filter="black_pro_mist".into();m.print_diffusion_filter="glimmerglass".into();
  let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
  let mut params=m.runtime_for_film(&film);params.camera.pixel_size_um_override=Some(35_000./6000.);let(w,h)=(257u32,263u32);
  assert!(Pipeline::new(film.clone(),paper.clone(),params.clone()).gpu_physical_components(w,h).is_some());
  let root=std::env::temp_dir().join(format!("gpu-cinestill-staged-{}",std::process::id()));fs::create_dir(&root).unwrap();
  struct Cleanup(PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}let _cleanup=Cleanup(root.clone());
  let input=root.join("input.rgb");fs::write(&input,(0..w*h*3).flat_map(|i|(0.01f32+(i%251)as f32/300.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut outputs=Vec::new();
  for split in [false,true] {
   let output=root.join(format!("out-{split}.rgb"));
   let mut reporter=crate::streamed_progress::Reporter::new(|_|{});
   let metrics=export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,None,w,h,128,0.,&mut||false,&mut reporter,Some(split)).unwrap();
   assert!(metrics.gpu.active);assert!(metrics.gpu.gpu_batches>0);assert_eq!(fs::metadata(&output).unwrap().len(),w as u64*h as u64*12);
   assert!(!output.with_extension("gpu-film-log.rgb").exists());
   assert_eq!(metrics.peak_logical_scratch_bytes,if split {w as u64*h as u64*if Pipeline::new(film.clone(),paper.clone(),params.clone()).gpu_physical_components(w,h).is_some(){52}else{12}}else{0});
   outputs.push(fs::read(&output).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect::<Vec<_>>());
  }
  let max=outputs[0].iter().zip(&outputs[1]).map(|(a,b)|(a-b).abs()).fold(0f32,f32::max);
  // Same GPU pipeline, but whole diffusion and external-field reduction order
  // can differ by float rounding. This is a numerical regression bound, not a
  // perceptual-quality or CPU-equality claim.
  assert!(max<0.00002,"split vs whole max={max}");
  let output=root.join("cancel.rgb");let cancel_flag=std::cell::Cell::new(false);
  let mut reporter=crate::streamed_progress::Reporter::new(|e|{if e.gpu.and_then(|g|g.phase)==Some("dir"){cancel_flag.set(true);}});
  let error=export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,None,w,h,128,0.,&mut||cancel_flag.get(),&mut reporter,Some(true)).unwrap_err();
  assert_eq!(error.code,"CANCELLED");assert!(!output.exists());assert!(!output.with_extension("gpu-film-log.rgb").exists());assert!(gpu.is_healthy());
  let output=root.join("collision.rgb");let log=output.with_extension("gpu-film-log.rgb");fs::write(&log,b"foreign fixture").unwrap();
  let mut reporter=crate::streamed_progress::Reporter::new(|_|{});
  assert!(export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&output,None,w,h,128,0.,&mut||false,&mut reporter,Some(true)).is_ok());
  assert_eq!(fs::read(&log).unwrap(),b"foreign fixture");assert!(output.exists());
  assert_ne!(film_log_attempt_path(&output),film_log_attempt_path(&output));
  assert!(!fs::read_dir(&root).unwrap().any(|e|e.unwrap().file_name().to_string_lossy().contains("gpu-film-log-")),"owned attempt spools must be cleaned");
  let foreign=root.join("foreign-output.rgb");fs::write(&foreign,b"foreign-output").unwrap();
  let mut reporter=crate::streamed_progress::Reporter::new(|_|{});
  assert!(export_with_split(&params,&m.film_stock,&m.paper_stock,&film,&paper,&data,&input,&foreign,None,w,h,128,0.,&mut||false,&mut reporter,Some(true)).is_err());
  assert_eq!(fs::read(&foreign).unwrap(),b"foreign-output");
  eprintln!("GPU_FILMLOG_SPOOL_PASS max={max} fullsize={w}x{h} dualfilters cancellation cleanup collision_preserved");
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
   spektrafilm_core::pipeline::DiffusionScatteredFields{camera:Some(spektrafilm_core::pipeline::DiffusionScattered{disk:None,field:vec![0.25;plan.small_w as usize*plan.small_h as usize*3],small_w:plan.small_w,small_h:plan.small_h,d:plan.d,p_s:plan.p_s,crop:None}),enlarger:None,highlight_max_raw:None}
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

#[cfg(test)]
mod capacity_boundary_tests {
    use super::*;
    #[test]
    fn conservative_budget_coefficients_remain_unchanged() {
        assert_eq!(FIXED_BYTES,96*1024*1024);assert_eq!(BAND_BYTES_PER_PIXEL,272);
    }
    #[test]
    fn retained_fields_exact_pixel_boundary_is_inclusive() {
        let fields=128*1024*1024-FIXED_BYTES-BAND_BYTES_PER_PIXEL;
        for plan in [tile_plan(1,1,40000,128,u64::MAX,fields).unwrap().estimated_bytes,
                     band_plan(1,1,40000,128,u64::MAX,fields).unwrap().estimated_bytes] {
            assert_eq!(plan,128*1024*1024);
        }
        assert_eq!(tile_plan(1,1,0,128,u64::MAX,fields+1).unwrap_err().code,"GPU_MEMORY_BUDGET");
        assert_eq!(band_plan(1,1,0,128,u64::MAX,fields+1).unwrap_err().code,"GPU_MEMORY_BUDGET");
    }
    #[test]
    fn binding_margin_requires_fourteen_bytes_for_one_rgb_pixel() {
        assert!(tile_plan(1,1,0,128,14,0).is_ok());
        assert!(band_plan(1,1,0,128,14,0).is_ok());
        assert_eq!(tile_plan(1,1,0,128,13,0).unwrap_err().code,"GPU_BINDING_LIMIT");
        assert_eq!(band_plan(1,1,0,128,13,0).unwrap_err().code,"GPU_BINDING_LIMIT");
    }
    #[test]
    fn overflow_and_reserve_exhaustion_never_admit() {
        for fields in [u64::MAX,u64::MAX-FIXED_BYTES+1,32*1024*1024,32*1024*1024+1] {
            assert_eq!(tile_plan(1,1,0,128,u64::MAX,fields).unwrap_err().code,"GPU_MEMORY_BUDGET");
            assert_eq!(band_plan(1,1,0,128,u64::MAX,fields).unwrap_err().code,"GPU_MEMORY_BUDGET");
        }
    }
    #[test]
    fn invalid_geometry_and_budget_are_not_capacity_failures() {
        for (w,h,b) in [(0,1,128),(1,0,128),(1,1,127),(1,1,513)] {
            assert_eq!(tile_plan(w,h,0,b,u64::MAX,0).unwrap_err().code,"INVALID_BUDGET");
            assert_eq!(band_plan(w,h,0,b,u64::MAX,0).unwrap_err().code,"INVALID_BUDGET");
        }
        assert_eq!(tile_plan(40001,1,0,128,u64::MAX,0).unwrap_err().code,"INVALID_BUDGET");
    }
}

#[cfg(test)] mod front_prepass_regression {
    use super::*;
    fn case_for(unbordered:bool)->(PathBuf,Pipeline) {
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.memory_budget_mi_b=512;
        m.film_stock="cinestill_800t".into();m.paper_stock="kodak_2383".into();
        m.diffusion_filter="black_pro_mist".into();m.diffusion_strength=0.5;
        if unbordered {m.film_gamma=0.7625;m.scan_sharpen_radius=0.699999988079071;}
        else{m.print_yellow=6.599998474121094;m.print_exposure_ev=-0.24000001;m.film_gamma=0.95;}
        let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();
        let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
        let mut params=m.runtime_for_film(&film);
        params.camera.pixel_size_um_override=Some(35000./if unbordered{4096.}else{3840.});
        (data.clone(),Pipeline::new_with_spectral(film,paper,params,&data).unwrap())
    }
    #[test] fn main_progress_is_monotonic_across_split_hit_and_corrupt_replay() {
        for schedule in [vec![(1,5,50),(2,5,100),(3,5,20),(4,5,60),(5,5,100)],
            vec![(1,2,50),(2,2,100)],
            vec![(1,4,25),(2,4,50),(4,9,20),(5,9,50),(6,9,100),(7,9,30),(8,9,60),(9,9,100)]] {
            let mut events=Vec::new();let mut fraction=0;
            {let mut p=crate::streamed_progress::Reporter::new(|e|events.push(e));
             p.begin_stage(6,2,"gpu_tiled_chain");p.begin_work("gpu_tiled_chain");
             for(index,total,raw)in schedule{report_main_tile(&mut p,"gpu_tiled_chain",raw,100,index,total,&mut fraction);}
             p.end_work();p.begin_work("output");p.end_work();p.finish_stage();}
            assert!(events.windows(2).all(|w|w[0].stage_done<=w[1].stage_done));
            let last=events.last().unwrap();assert_eq!(last.stage_done,last.stage_total);assert_eq!(fraction,1_000_000);
        }
    }
    #[test] fn bordered_front_does_not_inherit_spatial_tiles() {
        let(_,pipe)=case_for(false);let(w,h)=(3347,4170);let fields=pipe.gpu_diffusion_working_bytes(w,h).unwrap();
        let halo=pipe.banded_halo_pixels(35000./3840.);
        let spatial=tile_plan(w,h,halo,512,128*1024*1024,fields).unwrap();
        let front=tile_plan(w,h,0,512,128*1024*1024,fields).unwrap();
        assert_eq!((spatial.width,spatial.rows,tile_count(w,h,spatial)),(21,21,31840));
        assert_eq!((front.width,front.rows,tile_count(w,h,front)),(3347,397,11));
        assert!(front.estimated_bytes<=512*1024*1024);assert!(!split_is_worthwhile(w,h,halo,front));
    }
    #[test] fn main_split_does_not_require_full_plan_or_use_front_geometry() {
        let(_,pipe)=case_for(true);let(w,h)=(3072,4096);let halo=pipe.banded_halo_pixels(35000./4096.);
        let construction=pipe.gpu_diffusion_working_bytes(w,h).unwrap();assert_eq!(halo,603);assert_eq!(construction,67_191_264);
        let old=tile_plan(w,h,halo,512,128*1024*1024,construction);
        assert_eq!(old.as_ref().unwrap_err().code,"GPU_MEMORY_BUDGET");
        assert!(choose_main_split(w,h,halo,&old,None).unwrap());assert!(choose_main_split(w,h,halo,&old,Some(false)).is_err());
        let front=tile_plan(w,h,0,512,128*1024*1024,construction).unwrap();
        assert_eq!((front.width,front.rows,tile_count(w,h,front)),(3072,441,10));
        let d=pipe.gpu_diffusion_plans(w,h).0.unwrap();
        let retained=spektrafilm_core::pipeline::DiffusionScatteredFields{highlight_max_raw:Some(1.),enlarger:None,
            camera:Some(spektrafilm_core::pipeline::DiffusionScattered{disk:None,field:vec![0.;d.small_w as usize*d.small_h as usize*3],small_w:d.small_w,small_h:d.small_h,d:d.d,p_s:d.p_s,crop:None})}.retained_working_bytes().unwrap();
        let kernels=pipe.gpu_halation_working_bytes(w,h).unwrap();
        let full=tile_plan(w,h,halo,512,128*1024*1024,retained+kernels);
        assert!(choose_main_split(w,h,halo,&full,None).unwrap());
        let(ph,sh)=pipe.gpu_split_halo_pixels(35000./4096.);
        let prefix=tile_plan(w,h,ph,512,128*1024*1024,retained+kernels).unwrap();
        let suffix=tile_plan(w,h,sh,512,128*1024*1024,spektrafilm_core::pipeline::DiffusionScatteredFields::default().retained_working_bytes().unwrap()).unwrap();
        assert_eq!(tile_count(w,h,prefix),70);assert_eq!(tile_count(w,h,suffix),18);
        for p in [front,prefix,suffix]{assert!(p.estimated_bytes<=512*1024*1024);}
        assert!(choose_main_split(w,h,halo,&tile_plan(0,h,halo,512,128*1024*1024,0),None).is_err());
    }
    #[test] #[cfg(feature="precision-f64")]
    fn complete_export_when_explicitly_enabled() {
        if std::env::var("FILMROOM_FRONT_PREPASS_LARGE").as_deref()!=Ok("1"){return;}
        let unbordered=std::env::var("FILMROOM_FRONT_TEST_CASE").as_deref()==Ok("unbordered");
        let(data,pipe)=case_for(unbordered);let(w,h)=if unbordered{(3072,4096)}else{(3347,4170)};
        let root=PathBuf::from(std::env::var_os("FILMROOM_FRONT_TEST_SCRATCH").expect("explicit disk-backed scratch required"));
        fs::create_dir_all(&root).unwrap();let input=root.join("source.rgb");let output=root.join("output.rgb");
        let mut file=File::create(&input).unwrap();
        for y in 0..h {let row:Vec<u8>=(0..w*3).flat_map(|i|(0.01f32+((i*13+y*17)%251)as f32/180.).to_le_bytes()).collect();file.write_all(&row).unwrap();}drop(file);
        let start=Instant::now();let mut last=String::new();let mut updates=0;let mut stage_done=0;
        let result=crate::streamed::export_file_checkpointed(&data,"cinestill_800t","kodak_2383",pipe.params.clone(),&input,&output,&root.join("scratch"),None,w,h,512,0.,true,
            ||start.elapsed().as_secs()>600,|e|{
                if e.display_stage==6{assert!(e.stage_done>=stage_done);stage_done=e.stage_done;}
                if e.phase=="gpu_diffusion_prepass"&&e.pass_updated{updates+=1;}
                let key=format!("{}:{}",e.phase,e.gpu.map_or(0,|g|g.tile_index));if key!=last{eprintln!("FRONT_EXPORT {} {}/{} elapsed={:.2}",key,e.done,e.total,start.elapsed().as_secs_f64());last=key;}
            }).unwrap();
        assert!(result.gpu.active,"must not silently test CPU fallback");assert!(updates>=75);
        let bytes=fs::read(&output).unwrap();assert_eq!(bytes.len(),w as usize*h as usize*12);
        assert!(bytes.chunks_exact(4).all(|b|f32::from_le_bytes(b.try_into().unwrap()).is_finite()));
        eprintln!("FRONT_EXPORT_COMPLETE case={} seconds={} submits={} updates={} stages={:?}",if unbordered{"unbordered"}else{"bordered"},start.elapsed().as_secs_f64(),result.gpu.gpu_batches,updates,result.stages);
    }
}

#[cfg(test)]
mod doubled_export_budget_tests {
    use super::*;
    #[test]
    fn reported_49mp_budget_geometry_comparison() {
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let mut m=crate::mobile::MobileParams::default();m.mode="export".into();m.film_stock="kodak_portra_400".into();m.paper_stock="kodak_portra_endura".into();m.diffusion_filter="black_pro_mist".into();m.diffusion_strength=2.;m.film_format_mm=35.;m.scan_sharpen_radius=0.699999988079071;
        let film=spektrafilm_core::profile::load_profile_by_name(&data,&m.film_stock).unwrap();let paper=spektrafilm_core::profile::load_profile_by_name(&data,&m.paper_stock).unwrap();
        let p=Pipeline::new_with_spectral(film.clone(),paper,m.runtime_for_film(&film),&data).unwrap();
        let(w,h)=(7347,6767);let halo=p.banded_halo_pixels(35000./7347.);let fields=p.gpu_diffusion_working_bytes(w,h).unwrap();
        for budget in [512,1024] {
            let front=tile_plan(w,h,0,budget,128*1024*1024,fields).unwrap();let main=tile_plan(w,h,halo,budget,128*1024*1024,fields).unwrap();
            eprintln!("BUDGET_COMPARE budget={budget} halo={halo} fields={fields} front={}x{} main={}x{} tiles={} expanded={}x{} estimate={}",front.width,front.rows,main.width,main.rows,w.div_ceil(main.width)*h.div_ceil(main.rows),main.expanded_width,main.expanded_rows,main.estimated_bytes);
            assert!(main.estimated_bytes<=u64::from(budget)*1024*1024);
        }
    }
    #[test]
    fn one_gib_geometry_preserves_binding_and_response_caps() {
        let binding=128*1024*1024;
        let small=tile_plan(7347,6767,261,512,binding,13_500_136+70_896).unwrap();
        let large=tile_plan(7347,6767,261,1024,binding,13_500_136+70_896).unwrap();
        assert!(large.estimated_bytes<=1024*1024*1024);
        assert!(large.expanded_width as u64*large.expanded_rows as u64*12<=binding*9/10);
        assert!(large.width<=1024||large.width==7347);
        assert!(large.rows<=1024||large.rows==6767);
        assert!(tile_cost(7347,6767,261,large.width,large.rows)<=tile_cost(7347,6767,261,small.width,small.rows));
        let band=band_plan(1024,4096,10,1024,binding,0).unwrap();
        assert!(band.rows<=1024);assert!(band.estimated_bytes<=1024*1024*1024);
        assert_eq!(tile_plan(10,10,0,1025,binding,0).unwrap_err().code,"INVALID_BUDGET");
        assert_eq!(band_plan(10,10,0,1025,binding,0).unwrap_err().code,"INVALID_BUDGET");
        assert!(tile_plan(7347,6767,261,1024,1024,0).is_err());
    }
}
