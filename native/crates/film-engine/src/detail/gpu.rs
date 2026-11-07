//! Owned, approximate GPU detail. Full-grid dependencies are resolved once;
//! visible output uses immutable global fields, composed halos and full-grid RNG.
//! This is not CPU exactness, full-resolution export, or an RGBA-upload shortcut.
use super::{CancellationToken, DetailConfig, DetailError, DetailPerformance, PregrainMetrics, Rect, ResourcePlan, CELL, MIB};
use spektrafilm_core::pipeline::{DiffusionScatteredFields, Pipeline, TileContext};
use spektrafilm_gpu::wgpu_backend::WgpuBackend;
use std::{collections::BTreeMap, fs::File, io::{Read, Seek, SeekFrom}, path::{Path, PathBuf},
    sync::{Mutex, atomic::{AtomicBool, AtomicU64, Ordering}}, time::Instant};

pub(super) const ALGORITHM: &str = "owned-detail-v2-resident-f32-psf6-cap64-fir256-grain-rms-v1";

pub(super) enum BuildFailure { Fallback(&'static str), Fatal(DetailError) }
impl From<DetailError> for BuildFailure {fn from(e: DetailError) -> Self {Self::Fatal(e)}}

/// Sticky process quarantine takes precedence over cancellation. Never allocate
/// a CPU fallback while an earlier command might still own driver resources.
pub(super) fn check_completion(cancel: &CancellationToken) -> Result<(), DetailError> {
    crate::mobile::gpu_completion_guard().map_err(|_| DetailError::GpuCompletionUncertain)?;
    cancel.check()
}
struct CachedTile { values: Vec<f32>, touched: u64 }
#[derive(Default)]
struct Cache { tiles: BTreeMap<(usize, usize), CachedTile>, bytes: usize, clock: u64 }

pub(super) struct GpuDetail {
    source: PathBuf,
    backend: &'static WgpuBackend,
    fields: DiffusionScatteredFields,
    width: usize, height: usize,
    core_width: usize, core_height: usize, halo: usize,
    expanded_width: usize, expanded_height: usize,
    cache_limit: usize,
    cache: Mutex<Cache>,
    failed: AtomicBool,
    completed_submits: AtomicU64,
    brightness: f64,
    pub pitch: f32,
    pub metered_ev: Option<f32>,
    pub metrics: PregrainMetrics,
    pub(super) performance: DetailPerformance,
}

fn read_rect(source: &Path, full_width: usize, full_height: usize, rect: Rect,
             cancel: &CancellationToken) -> Result<Vec<f32>, DetailError> {
    check_completion(cancel)?;
    if !rect.valid(full_width, full_height) {return Err(DetailError::InvalidGeometry("GPU source rectangle".into()));}
    let len = rect.w.checked_mul(rect.h).and_then(|n|n.checked_mul(3))
        .ok_or_else(||DetailError::InvalidGeometry("GPU rectangle overflow".into()))?;
    let mut values: Vec<f32> = Vec::new();
    values.try_reserve_exact(len).map_err(|_|DetailError::Processing("GPU source allocation failed".into()))?;
    values.resize(len, 0.);
    let mut file = File::open(source)?;
    if file.metadata()?.len() != full_width as u64 * full_height as u64 * 12 {
        return Err(DetailError::InvalidGeometry("GPU source length changed".into()));
    }
    for row in 0..rect.h {
        check_completion(cancel)?;
        file.seek(SeekFrom::Start((((rect.y+row)*full_width+rect.x)*12) as u64))?;
        let offset=row*rect.w*3;
        file.read_exact(bytemuck::cast_slice_mut(&mut values[offset..offset+rect.w*3]))?;
    }
    if cfg!(target_endian="big") {for value in &mut values {*value=f32::from_bits(value.to_bits().swap_bytes());}}
    if values.iter().any(|v|!v.is_finite()) {return Err(DetailError::Processing("GPU source is nonfinite".into()));}
    Ok(values)
}

/// Output and both persistent cache capacities stay outside the existing
/// 256 B/pixel + 96 MiB tile envelope. Unused CPU FFT cache stays reserved,
/// so a completed failed setup may retire before entering the CPU fallback.
fn execution_budget(config: &DetailConfig, plan: &ResourcePlan) -> Option<u32> {
    if !config.gpu_attempt || !plan.gpu_attempt {return None;}
    let outside=super::gpu_output_bytes(config).checked_add(plan.stage_cache_bytes)?
        .checked_add(plan.spectrum_cache_bytes)?;
    let execution=plan.gpu_execution_budget_bytes;
    if execution%MIB!=0 || outside.checked_add(execution)?>plan.peak_reserved_bytes
        || plan.peak_reserved_bytes>config.memory_budget_bytes {return None;}
    u32::try_from(execution/MIB).ok().filter(|n|(128..=512).contains(n))
}

/// Select an intersecting canonical proof core without changing scientific geometry.
/// Validate independently of preflight so an invalid hint can never select out-of-grid data.
fn initial_cell(region:Option<Rect>,width:usize,height:usize,core_width:usize,core_height:usize)
    -> Result<(usize,usize),DetailError> {
    if width==0 || height==0 || core_width==0 || core_height==0 {
        return Err(DetailError::InvalidGeometry("GPU proof grid".into()));
    }
    match region {
        None=>Ok((0,0)),
        Some(r) if r.valid(width,height)=>Ok(((r.x+r.w/2)/core_width,(r.y+r.h/2)/core_height)),
        Some(_)=>Err(DetailError::InvalidGeometry("Initial region outside working grid".into())),
    }
}

impl GpuDetail {
    pub(super) fn build(pipe: &Pipeline, source: &Path, config: &DetailConfig,
                        plan: &ResourcePlan, cancel: &CancellationToken) -> Result<Self, BuildFailure> {
        check_completion(cancel)?;
        // Never derive AE from the visible crop or fold it into the user's
        // compensation (which would alter print normalization/calibration).
        if pipe.params.camera.auto_exposure && pipe.params.camera.preview_metered_ev.is_none() {
            return Err(BuildFailure::Fallback("shared_preview_metering_required"));
        }
        let budget=execution_budget(config,plan).ok_or(BuildFailure::Fallback("gpu_detail_memory_budget"))?;
        let backend=crate::mobile::full_chain_gpu().filter(|g|g.is_healthy())
            .ok_or(BuildFailure::Fallback("gpu_unavailable"))?;
        check_completion(cancel)?;
        let started=Instant::now();
        let pitch=pipe.params.camera.pixel_size_um_override
            .ok_or(BuildFailure::Fallback("gpu_full_grid_pitch_required"))?;
        let halo=pipe.banded_halo_pixels(pitch);
        let field_bytes=pipe.gpu_diffusion_working_bytes(config.width,config.height)
            .ok_or(BuildFailure::Fallback("gpu_global_field_budget"))?;
        let field_bytes=field_bytes.checked_add(std::mem::size_of::<DiffusionScatteredFields>() as u64)
            .ok_or(BuildFailure::Fallback("gpu_global_field_budget"))?;
        let tile=crate::gpu_export::tile_plan(config.width,config.height,halo,budget,
            backend.max_storage_buffer_binding_size(),field_bytes)
            .map_err(|_|BuildFailure::Fallback("gpu_detail_halo_or_binding_budget"))?;
        // Canonical bounded cells, not independently rescaled/metered crops.
        // Smaller cores cannot exceed the planner's expanded allocations.
        let core_width=tile.width.min(CELL as u32);
        let core_height=tile.rows.min(CELL as u32);
        let before=backend.thread_submits();
        let fields_started=Instant::now();
        let mut read_error=None;
        let mut bytes_read=0;
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(
            config.width,config.height,core_width,core_height,
            |x,y,w,h| match read_rect(source,config.width as usize,config.height as usize,
                Rect{x:x as usize,y:y as usize,w:w as usize,h:h as usize},cancel) {
                Ok(v)=>{bytes_read+=v.len() as u64*4;Some(v)}, Err(e)=>{read_error=Some(e);None}
            }, ||check_completion(cancel).is_ok(), backend);
        check_completion(cancel)?;
        if let Some(e)=read_error {return Err(e.into());}
        let fields=fields.ok_or(BuildFailure::Fallback("gpu_global_field_execution_failed"))?;
        // Check actual Vec capacities and retained GPU upload/crop accounting,
        // not just dimensions. Failure drops fields before CPU work can begin.
        let retained=fields.retained_working_bytes()
            .ok_or(BuildFailure::Fallback("gpu_global_field_budget"))?;
        if retained > field_bytes.max(std::mem::size_of::<DiffusionScatteredFields>() as u64) {
            return Err(BuildFailure::Fallback("gpu_global_field_capacity_exceeded"));
        }
        let global_fields_ms=fields_started.elapsed().as_secs_f64()*1000.;
        let mut ctx=Self {
            source:source.to_owned(), backend, fields,
            width:config.width as usize,height:config.height as usize,
            core_width:core_width as usize,core_height:core_height as usize,halo:halo as usize,
            expanded_width:tile.expanded_width as usize,expanded_height:tile.expanded_rows as usize,
            cache_limit:plan.stage_cache_bytes,cache:Mutex::new(Cache::default()),
            performance:DetailPerformance{global_fields_ms:Some(global_fields_ms),..Default::default()},
            failed:AtomicBool::new(false),completed_submits:AtomicU64::new(backend.thread_submits().saturating_sub(before)),
            brightness:2f64.powf(config.params.brightness_ev),pitch,
            metered_ev:if pipe.params.camera.auto_exposure {pipe.params.camera.preview_metered_ev} else {None},
            metrics:PregrainMetrics{elapsed_ms:0.,peak_logical_scratch_bytes:config.width as u64*config.height as u64*12,
                spatial_peak_buffer_bytes:tile.estimated_bytes,spatial_bytes_read:bytes_read,spatial_bytes_written:0},
        };
        // Confirm a complete real scientific chain before publishing this
        // backend/algorithm identity. Availability or a prepass submit is not proof.
        let key=initial_cell(config.initial_region,ctx.width,ctx.height,ctx.core_width,ctx.core_height)?;
        let core=ctx.core(key.0,key.1);
        let proof_started=Instant::now();
        let values=match ctx.render_one(pipe,core,cancel,&mut DetailPerformance::default()) {
            Ok(v)=>v,
            Err(DetailError::Cancelled)=>return Err(BuildFailure::Fatal(DetailError::Cancelled)),
            Err(DetailError::GpuCompletionUncertain)=>return Err(BuildFailure::Fatal(DetailError::GpuCompletionUncertain)),
            Err(_)=>return Err(BuildFailure::Fallback("gpu_detail_chain_execution_failed")),
        };
        if ctx.completed_submits()==0 {return Err(BuildFailure::Fallback("gpu_no_confirmed_scientific_submits"));}
        {let mut cache=ctx.cache.lock().map_err(|_|DetailError::Processing("GPU cache lock".into()))?;
         ctx.insert(&mut cache,key,values);}
        ctx.performance.proof_tile_ms=Some(proof_started.elapsed().as_secs_f64()*1000.);
        ctx.performance.gpu_submit_delta=Some(backend.thread_submits().saturating_sub(before));
        ctx.metrics.elapsed_ms=started.elapsed().as_secs_f64()*1000.;
        check_completion(cancel)?;
        Ok(ctx)
    }
    pub(super) fn completed_submits(&self) -> u64 {self.completed_submits.load(Ordering::Acquire)}
    fn core(&self,x:usize,y:usize)->Rect {let x=x*self.core_width;let y=y*self.core_height;
        Rect{x,y,w:self.core_width.min(self.width-x),h:self.core_height.min(self.height-y)}}
    fn insert(&self,cache:&mut Cache,key:(usize,usize),values:Vec<f32>) {
        let bytes=values.capacity()*4;
        if bytes>self.cache_limit {return;}
        while cache.bytes+bytes>self.cache_limit {
            let Some(old)=cache.tiles.iter().min_by_key(|(_,v)|v.touched).map(|(k,_)|*k) else {break};
            if let Some(v)=cache.tiles.remove(&old) {cache.bytes-=v.values.capacity()*4;}
        }
        if let Some(old)=cache.tiles.remove(&key) {cache.bytes-=old.values.capacity()*4;}
        cache.clock=cache.clock.wrapping_add(1);
        cache.bytes+=bytes;
        cache.tiles.insert(key,CachedTile{values,touched:cache.clock});
    }
    fn render_one(&self,pipe:&Pipeline,core:Rect,cancel:&CancellationToken,performance:&mut DetailPerformance)->Result<Vec<f32>,DetailError> {
        check_completion(cancel)?;
        if self.failed.load(Ordering::Acquire) {return Err(DetailError::GpuExecutionFailed);}
        let outer=core.expand(self.halo,self.width,self.height);
        if outer.w>self.expanded_width || outer.h>self.expanded_height {return Err(DetailError::GpuExecutionFailed);}
        let source_started=Instant::now();
        let input=read_rect(&self.source,self.width,self.height,outer,cancel)?;
        *performance.source_read_ms.get_or_insert(0.)+=source_started.elapsed().as_secs_f64()*1000.;
        let chain_started=Instant::now();
        let before=self.backend.thread_submits();
        let attempt=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||
            pipe.process_gpu_resident_tile_f32_controlled(&input,outer.w as u32,outer.h as u32,
                TileContext{full_width:self.width as u32,full_height:self.height as u32,x:outer.x as u32,y:outer.y as u32},
                self.backend,Some(&self.fields),||check_completion(cancel).is_ok())));
        // Unconfirmed work is fatal, including after a panic. It never authorizes
        // fallback, cache publication or another scientific allocation.
        check_completion(cancel)?;
        let submits=self.backend.thread_submits().saturating_sub(before);
        let image=match attempt {
            Ok(Some(v)) if submits>0 && v.width as usize==outer.w && v.height as usize==outer.h
                && v.data.len()==outer.w*outer.h*3 && v.data.iter().all(|x|x.is_finite())=>v,
            _=>{self.failed.store(true,Ordering::Release);return Err(DetailError::GpuExecutionFailed);}
        };
        *performance.chain_ms.get_or_insert(0.)+=chain_started.elapsed().as_secs_f64()*1000.;
        self.completed_submits.fetch_add(submits,Ordering::AcqRel);
        let copy_started=Instant::now();
        let mut values=Vec::with_capacity(core.w*core.h*3);
        for y in core.y..core.y+core.h {
            check_completion(cancel)?;
            let offset=((y-outer.y)*outer.w+core.x-outer.x)*3;
            for pixel in image.data[offset..offset+core.w*3].chunks_exact(3) {
                let linear=spektrafilm_core::output_color::to_linear_srgb(
                    [pixel[0] as f64,pixel[1] as f64,pixel[2] as f64],&pipe.params.io.output_color_space);
                for v in linear {let v=(v*self.brightness) as f32;
                    if !v.is_finite() {self.failed.store(true,Ordering::Release);return Err(DetailError::GpuExecutionFailed);}
                    values.push(v);
                }
            }
        }
        check_completion(cancel)?;
        *performance.result_copy_ms.get_or_insert(0.)+=copy_started.elapsed().as_secs_f64()*1000.;
        Ok(values)
    }
    pub(super) fn render_into(&self,pipe:&Pipeline,sample:Rect,output:&mut[f32],cancel:&CancellationToken)->Result<DetailPerformance,DetailError> {
        check_completion(cancel)?;
        if !sample.valid(self.width,self.height) || output.len()!=sample.w*sample.h*3 {
            return Err(DetailError::InvalidGeometry("GPU detail output shape".into()));
        }
        if self.failed.load(Ordering::Acquire) {return Err(DetailError::GpuExecutionFailed);}
        let before=self.backend.thread_submits();
        let mut performance=DetailPerformance{tile_cache_hits:Some(0),tile_cache_misses:Some(0),
            source_read_ms:Some(0.),chain_ms:Some(0.),result_copy_ms:Some(0.),..Default::default()};
        let mut cache=self.cache.try_lock().map_err(|_|DetailError::Processing("GPU detail already rendering".into()))?;
        for cy in sample.y/self.core_height..=(sample.y+sample.h-1)/self.core_height {
            for cx in sample.x/self.core_width..=(sample.x+sample.w-1)/self.core_width {
                check_completion(cancel)?;
                let key=(cx,cy);let core=self.core(cx,cy);
                if !cache.tiles.contains_key(&key) {
                    *performance.tile_cache_misses.as_mut().unwrap()+=1;
                    let values=self.render_one(pipe,core,cancel,&mut performance)?;
                    // CPU-side copying cannot be replaced by another numerical
                    // renderer when a canonical tile does not fit the cache.
                    if values.capacity()*4>self.cache_limit {
                        let started=Instant::now();
                        copy_core(&values,core,sample,output,cancel)?;
                        *performance.result_copy_ms.as_mut().unwrap()+=started.elapsed().as_secs_f64()*1000.;continue;
                    }
                    self.insert(&mut cache,key,values);
                } else {*performance.tile_cache_hits.as_mut().unwrap()+=1;}
                cache.clock=cache.clock.wrapping_add(1);let touched=cache.clock;
                let tile=cache.tiles.get_mut(&key).ok_or(DetailError::GpuExecutionFailed)?;
                let started=Instant::now();
                tile.touched=touched;copy_core(&tile.values,core,sample,output,cancel)?;
                *performance.result_copy_ms.as_mut().unwrap()+=started.elapsed().as_secs_f64()*1000.;
            }
        }
        check_completion(cancel)?;
        performance.gpu_submit_delta=Some(self.backend.thread_submits().saturating_sub(before));
        Ok(performance)
    }
    pub(super) fn clear_cache(&self)->Result<(),DetailError> {
        let mut c=self.cache.lock().map_err(|_|DetailError::Processing("GPU cache lock".into()))?;
        c.tiles.clear();c.bytes=0;Ok(())
    }
    pub(super) fn cache_bytes(&self)->Result<usize,DetailError> {
        Ok(self.cache.lock().map_err(|_|DetailError::Processing("GPU cache lock".into()))?.bytes)
    }
}
fn copy_core(values:&[f32],core:Rect,sample:Rect,output:&mut[f32],cancel:&CancellationToken)->Result<(),DetailError> {
    let left=core.x.max(sample.x);let right=(core.x+core.w).min(sample.x+sample.w);
    let top=core.y.max(sample.y);let bottom=(core.y+core.h).min(sample.y+sample.h);
    for y in top..bottom {check_completion(cancel)?;
        let src=((y-core.y)*core.w+left-core.x)*3;
        let dst=((y-sample.y)*sample.w+left-sample.x)*3;
        output[dst..dst+(right-left)*3].copy_from_slice(&values[src..src+(right-left)*3]);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{detail::DetailContext, mobile::{MobileParams, PreviewMetering, PREVIEW_METERING_ALGORITHM}};
    use std::{fs, sync::atomic::AtomicU64};
    struct Root(PathBuf);
    impl Drop for Root {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
    fn config(w:u32,h:u32)->DetailConfig {
        let mut params=MobileParams::default();
        params.paper_stock="kodak_2383".into();params.auto_exposure=true;
        params.film_exposure_ev=0.75;params.brightness_ev=0.25;params.seed=9127;
        params.preview_metering=Some(PreviewMetering {
            algorithm:PREVIEW_METERING_ALGORITHM.into(),source_revision:"gpu-fixture".into(),
            mapping_revision:"oriented-original-v1:snapshot=gpu-fixture:meter-grid:linear-bilinear-center-v1".into(),
            data_revision:"bundled-v1".into(),owner:"owner-v1".into(),method:params.metering.clone(),width:256,height:181,ev:1.25,
        });
        DetailConfig{gpu_attempt:true,region_output:true,initial_region:None,width:w,height:h,params,
            source_revision:"gpu-fixture".into(),mapping_revision:"oriented-original-v1:snapshot=gpu-fixture:bilinear-center-v1:working".into(),
            data_revision:"bundled-v1".into(),memory_budget_bytes:512*MIB,disk_budget_bytes:1024*1024*1024,
            stage_cache_bytes:CELL*CELL*24,spectrum_cache_bytes:128*1024}
    }
    fn root()->Root {static N:AtomicU64=AtomicU64::new(0);
        let p=std::env::temp_dir().join(format!("detail-real-gpu-{}-{}",std::process::id(),N.fetch_add(1,Ordering::Relaxed)));
        fs::create_dir(&p).unwrap();Root(p)}
    fn source(root:&Path,w:u32,h:u32)->(PathBuf,Vec<f32>) {
        let mut data=Vec::new();
        for y in 0..h {for x in 0..w {
            // Different left/right luminance makes accidental ROI AE conspicuous.
            let base=if x<w/2 {0.015} else {0.8};
            data.extend([base+(x%17) as f32*0.001,base*0.7+(y%11) as f32*0.002,
                if x==w/2 && y==h/2 {8.} else {base*1.3}]);
        }}
        let path=root.join("source.f32le");fs::write(&path,data.iter().flat_map(|x|x.to_le_bytes()).collect::<Vec<_>>()).unwrap();(path,data)
    }
    fn data_dir()->PathBuf {PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data")}

    #[test]
    fn proof_hint_selects_an_intersecting_canonical_cell_and_preserves_default() {
        assert_eq!(initial_cell(None,1152,1536,256,256).unwrap(),(0,0));
        for r in [Rect{x:700,y:800,w:1,h:1},Rect{x:1024,y:1280,w:128,h:256},
            Rect{x:250,y:250,w:20,h:20},Rect{x:0,y:0,w:1152,h:1536}] {
            let (x,y)=initial_cell(Some(r),1152,1536,256,256).unwrap();
            assert!(x*256<r.x+r.w && (x+1)*256>r.x);
            assert!(y*256<r.y+r.h && (y+1)*256>r.y);
        }
        assert_eq!(initial_cell(Some(Rect{x:1100,y:1500,w:52,h:36}),1152,1536,128,192).unwrap(),(8,7));
        assert!(initial_cell(Some(Rect{x:usize::MAX,y:0,w:1,h:1}),1152,1536,256,256).is_err());
        assert!(initial_cell(None,1152,1536,0,256).is_err());
    }

    #[test]
    #[ignore = "requires serialized real Vulkan execution window"]
    fn real_gpu_initial_region_reuses_proof_without_changing_identity_or_pixels() {
        let r=root();let (w,h)=(383,271);let(path,input)=source(&r.0,w,h);let token=CancellationToken::new();
        let c=config(w,h);let baseline=DetailContext::build(c.clone(),&data_dir(),&path,&r.0,&token).unwrap();
        assert_eq!(baseline.backend_info().actual_backend,"wgpu");
        assert!(baseline.gpu.as_ref().unwrap().cache.lock().unwrap().tiles.contains_key(&(0,0)));
        let region=Rect{x:260,y:260,w:10,h:10};let sample=region.expand(1,w as usize,h as usize);
        let mut hinted_config=c.clone();hinted_config.initial_region=Some(region);
        let hinted=DetailContext::build(hinted_config.clone(),&data_dir(),&path,&r.0,&token).unwrap();
        assert_eq!(hinted.backend_info().actual_backend,"wgpu");
        assert_eq!(baseline.identity(),hinted.identity());
        let gpu=hinted.gpu.as_ref().unwrap();
        assert!(gpu.cache.lock().unwrap().tiles.contains_key(&(1,1)));
        let before=gpu.backend.thread_submits();let mut got=vec![0.;sample.w*sample.h*3];
        let warm=hinted.render_region_into_measured(region,&mut got,&token).unwrap().unwrap();
        assert_eq!(gpu.backend.thread_submits(),before);
        assert_eq!((warm.tile_cache_hits,warm.tile_cache_misses,warm.gpu_submit_delta),(Some(1),Some(0),Some(0)));
        assert_eq!((warm.source_read_ms,warm.chain_ms),(Some(0.),Some(0.)));
        let mut expected=vec![0.;got.len()];
        let cold=baseline.render_region_into_measured(region,&mut expected,&token).unwrap().unwrap();
        assert_eq!(cold.tile_cache_misses,Some(1));assert!(cold.gpu_submit_delta.unwrap()>0);
        assert_eq!(got,expected);
        assert_eq!(crate::mobile::linear_srgb_to_rgba8(&got),crate::mobile::linear_srgb_to_rgba8(&expected));
        for v in [cold.source_read_ms,cold.chain_ms,cold.result_copy_ms] {assert!(v.unwrap().is_finite() && v.unwrap()>=0.);}
        let build=hinted.build_performance().unwrap();
        assert!(build.gpu_submit_delta.unwrap()>0);
        for v in [build.profile_prepare_ms,build.pipeline_prepare_ms,build.global_fields_ms,build.proof_tile_ms] {
            assert!(v.unwrap().is_finite() && v.unwrap()>=0.);
        }
        // A rejected operation returns no metrics/output proof; the next hit is still per-call zero.
        let cancelled=CancellationToken::new();cancelled.cancel();
        assert!(matches!(hinted.render_region_into_measured(region,&mut got,&cancelled),Err(DetailError::Cancelled)));
        // A cancelled real chain on this same worker has actual, unreported submits.
        // A later successful region must not inherit those submits as its own delta.
        let previous=gpu.backend.thread_submits();
        let partial=hinted.pipe.process_gpu_resident_tile_f32_controlled(&input,w,h,
            TileContext{full_width:w,full_height:h,x:0,y:0},gpu.backend,Some(&gpu.fields),
            ||gpu.backend.thread_submits()==previous);
        assert!(partial.is_none());assert!(gpu.backend.thread_submits()>previous);
        assert!(gpu.backend.is_healthy());assert!(!gpu.backend.completion_uncertain());
        let next=hinted.render_region_into_measured(region,&mut got,&token).unwrap().unwrap();
        assert_eq!(next.gpu_submit_delta,Some(0));assert_eq!(next.tile_cache_hits,Some(1));
        drop(hinted);drop(baseline);
        assert!(matches!(DetailContext::build(hinted_config,&data_dir(),&path,&r.0,&cancelled),Err(DetailError::Cancelled)));
        assert_eq!(fs::read_dir(&r.0).unwrap().count(),1);
    }
    #[test]
    fn gpu_algorithm_tracks_resident_model_identity() {
        assert!(ALGORITHM.ends_with(spektrafilm_gpu::RESIDENT_APPROXIMATION_VERSION));
    }
    #[test]
    fn gpu_reservation_keeps_output_cache_fields_and_fixed_reserve() {
        for region in [false,true] {for (w,h) in [(32,24),(383,271),(1536,1024)] {
            let mut c=config(w,h);c.region_output=region;
            let p=DetailContext::preflight(&c).unwrap();let b=execution_budget(&c,&p).unwrap();
            let output=if region {w as usize*h as usize*16+128*1024} else {(CELL+2)*(CELL+2)*16+128*1024};
            assert!(b>=128);assert!(b as usize*MIB+output+p.stage_cache_bytes+p.spectrum_cache_bytes<=p.peak_reserved_bytes);
            assert!(p.peak_reserved_bytes<=c.memory_budget_bytes);
            let tight=ResourcePlan{peak_reserved_bytes:output+p.stage_cache_bytes+p.spectrum_cache_bytes+127*MIB,..p.clone()};
            assert!(execution_budget(&c,&tight).is_none());
            let admitted=crate::gpu_export::tile_plan(w,h,13,b,128*MIB as u64,7*MIB as u64).unwrap();
            assert!(admitted.estimated_bytes>=96*MIB as u64+7*MIB as u64+
                admitted.expanded_width as u64*admitted.expanded_rows as u64*256);
        }}
    }
    #[test]
    fn full_grid_and_diffusion_field_upper_bound_is_actually_prepaid() {
        let mut c=config(383,271);
        c.params.diffusion_filter="black_pro_mist".into();
        c.params.print_diffusion_filter="black_pro_mist".into();c.params.highlight_boost_ev=1.;
        let plan=DetailContext::preflight(&c).unwrap();
        assert!(plan.gpu_attempt);let budget=execution_budget(&c,&plan).unwrap();
        assert!(budget>128,"dual global fields cannot fit the old 128 MiB envelope");
        let fields=super::super::planned_gpu_field_bytes(&c).unwrap();
        let full_bound=96*MIB+383*271*256+fields;
        assert!(plan.gpu_execution_budget_bytes>=full_bound);
        assert!(plan.peak_reserved_bytes<c.memory_budget_bytes,"do not prepay the whole 512 MiB cap blindly");
        // A full-grid expanded allocation bounds every possible halo, so this
        // case must fit regardless of stock-specific composed halo length.
        let tile=crate::gpu_export::tile_plan(c.width,c.height,u32::MAX,budget,
            128*MIB as u64,fields as u64).unwrap();
        assert!(tile.whole);assert!(tile.estimated_bytes<=plan.gpu_execution_budget_bytes as u64);
        // Planning uses the exact same actual small-field dimensions as the
        // runtime pipeline, not a separate strength/downsample approximation.
        let film=spektrafilm_core::profile::load_profile_by_name(&data_dir(),&c.params.film_stock).unwrap();
        let paper=spektrafilm_core::profile::load_profile_by_name(&data_dir(),&c.params.paper_stock).unwrap();
        let mut params=c.params.preview_runtime_for_film(&film);
        params.camera.pixel_size_um_override=Some(params.camera.film_format_mm*1000./c.width.max(c.height) as f32);
        let pipe=Pipeline::new_with_spectral(film,paper,params,&data_dir()).unwrap();
        assert_eq!(fields as u64,pipe.gpu_diffusion_working_bytes(c.width,c.height).unwrap()+
            std::mem::size_of::<DiffusionScatteredFields>() as u64);
        c.params.output_route="direct".into();c.params.film_stock="fujifilm_provia_100f".into();
        let direct=super::super::planned_gpu_field_bytes(&c).unwrap();assert!(direct<fields);
        c.params.diffusion_strength=0.;
        assert_eq!(super::super::planned_gpu_field_bytes(&c).unwrap(),std::mem::size_of::<DiffusionScatteredFields>());
    }
    #[test]
    fn gpu_cap_and_forged_execution_plan_cannot_borrow_unreserved_bytes() {
        let mut c=config(1536,1024);c.memory_budget_bytes=384*MIB;
        let plan=DetailContext::preflight(&c).unwrap();
        assert!(plan.peak_reserved_bytes<=c.memory_budget_bytes);
        assert!(execution_budget(&c,&plan).is_some());
        let mut bad=plan.clone();bad.gpu_execution_budget_bytes+=MIB;
        bad.peak_reserved_bytes=super::super::gpu_output_bytes(&c)+bad.stage_cache_bytes+bad.spectrum_cache_bytes+bad.gpu_execution_budget_bytes-1;
        assert!(execution_budget(&c,&bad).is_none());
        let mut bad=plan.clone();bad.gpu_attempt=false;
        assert!(execution_budget(&c,&bad).is_none());
        c.gpu_attempt=false;assert!(execution_budget(&c,&plan).is_none());
    }
    #[test]
    fn missing_shared_ae_fails_before_gpu_initialization() {
        let r=root();let (path,_)=source(&r.0,16,12);let mut c=config(16,12);c.params.preview_metering=None;
        let ctx=DetailContext::build(c,&data_dir(),&path,&r.0,&CancellationToken::new()).unwrap();
        assert_eq!(ctx.backend_info().actual_backend,"cpu");
        assert_eq!(ctx.backend_info().reason,"shared_preview_metering_required");
        assert!(ctx.backend_info().cpu_fallback);drop(ctx);
        assert_eq!(fs::read_dir(&r.0).unwrap().count(),1);
    }
    /// Explicit opt-in, strict real execution: no adapter is a failure, not a pass/skip.
    #[test]
    #[ignore = "requires serialized real Vulkan execution window"]
    fn real_gpu_detail_global_grid_roi_seams_cache_cancel_and_failure() {
        let r=root();let (w,h)=(383,271);let(path,input)=source(&r.0,w,h);
        let original=fs::read(&path).unwrap();let token=CancellationToken::new();
        for variant in 0..3 {
            let mut c=config(w,h);
            if variant>=1 {c.params.diffusion_filter="black_pro_mist".into();c.params.print_diffusion_filter="black_pro_mist".into();c.params.highlight_boost_ev=1.;}
            if variant==2 {c.params.film_stock="fujifilm_provia_100f".into();c.params.output_route="direct".into();}
            let ctx=DetailContext::build(c.clone(),&data_dir(),&path,&r.0,&token).unwrap();
            assert_eq!(ctx.backend_info().actual_backend,"wgpu","variant {variant}: {:?}",ctx.backend_info());
            assert!(!ctx.backend_info().cpu_fallback);assert!(ctx.backend_info().gpu_batches>0);
            assert_eq!(ctx.identity().algorithm,ALGORITHM);assert_eq!(ctx.backend_info().quality(),"ApproximateSameWorkingGrid");
            assert_eq!(ctx.metered_ev(),Some(1.25));
            assert_eq!(ctx.pipe.params.camera.exposure_compensation_ev,0.75);
            assert_eq!(ctx.pixel_pitch_um(),35_000./w as f32);
            let gpu=ctx.gpu.as_ref().unwrap();
            let before=gpu.backend.thread_submits();
            let full=ctx.pipe.process_gpu_resident_tile_f32_controlled(&input,w,h,
                TileContext{full_width:w,full_height:h,x:0,y:0},gpu.backend,Some(&gpu.fields),||true).expect("whole-grid GPU oracle");
            assert!(gpu.backend.thread_submits()>before);
            let reference:Vec<f32>=full.data.chunks_exact(3).flat_map(|p|
                spektrafilm_core::output_color::to_linear_srgb([p[0] as f64,p[1] as f64,p[2] as f64],&ctx.pipe.params.io.output_color_space)
                    .map(|v|(v*2f64.powf(c.params.brightness_ev)) as f32)).collect();
            drop(full);
            let whole=ctx.render_region(Rect{x:0,y:0,w:w as usize,h:h as usize},&token).unwrap();
            let mut max=0f32;let mut sum=0f64;
            for(a,b)in whole.rgb.iter().zip(&reference){let e=(a-b).abs();max=max.max(e);sum+=(e as f64).powi(2);}
            // This threshold is solely GPU tile/global consistency, not CPU
            // perceptual equivalence. Report the error even when it passes.
            let rms=(sum/whole.rgb.len() as f64).sqrt();
            eprintln!("DETAIL_GPU_GRID variant={variant} max={max} rms={rms} submits={} pitch={} ae={:?}",ctx.backend_info().gpu_batches,ctx.pixel_pitch_um(),ctx.metered_ev());
            assert!(max<2e-4,"GPU ROI/global mismatch: {max}");
            for rect in [Rect{x:245,y:247,w:35,h:24},Rect{x:251,y:0,w:132,h:19},Rect{x:0,y:257,w:23,h:14},Rect{x:381,y:269,w:2,h:2}] {
                let roi=ctx.render_region(rect,&token).unwrap();let s=roi.sample_rect;
                for y in 0..s.h {let begin=((s.y+y)*w as usize+s.x)*3;
                    assert_eq!(&roi.rgb[y*s.w*3..(y+1)*s.w*3],&whole.rgb[begin..begin+s.w*3]);}
            }
            // Warm cache must retain the actual GPU identity without new submits.
            let rect=Rect{x:381,y:269,w:2,h:2};let a=ctx.render_region(rect,&token).unwrap();
            let count=ctx.backend_info().gpu_batches;let b=ctx.render_region(rect,&token).unwrap();
            assert_eq!(a.rgb,b.rgb);assert_eq!(count,ctx.backend_info().gpu_batches);
            assert!(ctx.cache_bytes().unwrap()<=c.stage_cache_bytes);
            ctx.clear_cache().unwrap();assert_eq!(ctx.cache_bytes().unwrap(),0);
            assert_eq!(a.rgb,ctx.render_region(rect,&token).unwrap().rgb);
            assert!(ctx.backend_info().gpu_batches>count);
            let cancelled=CancellationToken::new();cancelled.cancel();
            assert!(matches!(ctx.render_region(rect,&cancelled),Err(DetailError::Cancelled)));
            assert_eq!(a.rgb,ctx.render_region(rect,&token).unwrap().rgb);
            // A terminal failure after publication cannot return CPU pixels,
            // even when a matching cached tile is already present.
            gpu.failed.store(true,Ordering::Release);
            assert!(matches!(ctx.render_region(rect,&token),Err(DetailError::GpuExecutionFailed)));
            assert_eq!(ctx.identity().algorithm,ALGORITHM);
            drop(ctx);assert_eq!(fs::read_dir(&r.0).unwrap().count(),1);
        }
        assert_eq!(fs::read(&path).unwrap(),original);
    }
}
