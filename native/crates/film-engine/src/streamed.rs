//! Full-resolution disk-backed physical pipeline. No image-space halo truncation.
//! Pointwise stages use bounded row chunks; every spatial filter spans full rows
//! and columns through disk_spatial's exact FIR/IIR operator. Files remain local.
pub use crate::export_optimizations::ExportOptimizations;
use std::{fs::{self,File,OpenOptions},io::{Read,Write,Seek,SeekFrom},path::{Path,PathBuf},time::{Duration,Instant}};
use serde::Serialize;
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::load_profile_by_name};
use spektrafilm_gpu::{ComputeBackend,cpu_backend::CpuBackend};
use spektrafilm_math::{image::ImageBuf,precision::{Scalar,from_f64,from_f32}};
use crate::{mobile::{EngineError,Result},disk_spatial};
use crate::streamed_progress::{ExportProgress,ProgressSink,Reporter};

#[path="streamed_science.rs"]
mod science;
pub(crate) use science::{ScienceKind,ScienceRoute};
use science::{GpuScience,ScienceRequests,ScienceDelegates,Candidate as ScienceCandidate};

const SPATIAL_MEMORY:usize=16*1024*1024;
const BLOCK_PIXELS:usize=32768;
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct StageMetrics {pub stage:&'static str,pub elapsed_ms:f64}
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct StreamedMetrics {pub gpu_science:std::collections::BTreeMap<ScienceKind,GpuOpticalMetrics>,pub gpu_dir:GpuOpticalMetrics,pub gpu_exposure:GpuScannerMetrics,pub gpu_optical:GpuOpticalMetrics,pub gpu_scanner:GpuScannerMetrics,pub gpu_diffusion:GpuDiffusionMetrics,pub gpu_probe_capture:GpuProbeCaptureMetrics,pub wall_elapsed_ms:f64,pub checkpoint_commit_ms:f64,pub checkpoint_identity_ms:f64,pub checkpoint_resume_stage:u8,pub export_optimization_mask:u32,pub gpu_resource_reuse:bool,pub export_execution:Option<crate::export_execution::ExportExecutionMetrics>,pub metered_ev:Option<f32>,pub gpu:crate::adreno::GpuDiagnostics,pub width:u32,pub height:u32,pub pixel_count:u64,pub memory_budget_mi_b:u32,pub elapsed_ms:f64,pub setup_ms:f64,pub estimated_scratch_bytes:u64,pub peak_logical_scratch_bytes:u64,pub spatial_peak_explicit_buffer_bytes:u64,pub spatial_bytes_read:u64,pub spatial_bytes_written:u64,pub spatial_pass_count:u64,pub pointwise_chunk_rows:u32,pub lut_storage_bytes:usize,pub stages:Vec<StageMetrics>,pub algorithm:&'static str,pub grain_mode:&'static str,pub memory_policy:&'static str}

/// Safe public summary. Snapshot paths, tokens, manifests and samples stay private.
#[derive(Clone,Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct GpuProbeCaptureMetrics {pub status:&'static str,pub capture_ms:f64}

/// App-private, ephemeral input, deliberately separate from export/science options.
pub(crate) enum GpuProbeCaptureRequest {Capture{token:u64,snapshot_dir:PathBuf},Invalid}
struct GpuProbeCapture {
 request:Option<GpuProbeCaptureRequest>,metrics:GpuProbeCaptureMetrics,
 owned_snapshot:Option<PathBuf>,keep:bool,
}
impl GpuProbeCapture {
 fn new(request:Option<GpuProbeCaptureRequest>,resume_stage:u8,active:bool)->Self {
  let status=match request.as_ref() {
   None=>"disabled",Some(GpuProbeCaptureRequest::Invalid)=>"skipped_invalid_request",
   Some(_) if !cfg!(feature="diffusion-research")=>"skipped_unsupported",
   Some(_) if resume_stage>=3=>"skipped_resumed",Some(_) if !active=>"skipped_inactive",
   Some(_)=>"skipped_noop",
  };
  Self{request,metrics:GpuProbeCaptureMetrics{status,capture_ms:0.},owned_snapshot:None,keep:false}
 }
 #[allow(clippy::too_many_arguments)]
 fn capture(&mut self,source:&Path,reference:&Path,width:u32,height:u32,pitch:f64,family:&str,kernel:&spektrafilm_model::diffusion::ExactDiffusionKernel,cancelled:impl FnMut()->bool) {
  // A zero-radius/zero-scatter PSF has no optical work to compare.
  if self.metrics.status!="skipped_noop"||kernel.radius==0||kernel.scatter<=0. {return;}
  #[cfg(feature="diffusion-research")]
  if let Some(GpuProbeCaptureRequest::Capture{token,snapshot_dir})=&self.request {
   let at=Instant::now();
   // Recheck the app-owned root immediately before capture's exclusive mkdir.
   let valid=snapshot_dir.parent().is_some_and(|root|
    fs::symlink_metadata(root).is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink())
    &&root.canonicalize().ok().as_deref()==Some(root));
   if !valid {self.metrics.status="skipped_invalid_request";self.metrics.capture_ms+=at.elapsed().as_secs_f64()*1000.;return;}
   let result=crate::research_diffusion::probe::capture_snapshot(source,reference,snapshot_dir,*token,width,height,pitch,family,kernel,1,cancelled);
   // Account the whole attempted capture, including failed or cancelled IO.
   self.metrics.capture_ms+=at.elapsed().as_secs_f64()*1000.;
   self.metrics.status=match result {
    Ok(_)=>{self.owned_snapshot=Some(snapshot_dir.clone());"captured"},
    Err(code)=>match code.as_str() {
     "ORIGINAL_PSF_EXCEEDS_PROBE_LIMIT"|"PROBE_BUDGET_EXCEEDED"=>"skipped_limit",
     "CANCELLED"=>"skipped_cancelled",_=>"skipped_failed",
    },
   };
  }
  #[cfg(not(feature="diffusion-research"))]
  let _=(source,reference,width,height,pitch,family,cancelled,&self.request);
 }
}
impl Drop for GpuProbeCapture {fn drop(&mut self){if !self.keep {if let Some(path)=&self.owned_snapshot {let _=fs::remove_dir_all(path);}}}}

/// Full-image f32 optical execution is an explicit photographic policy, not an exact optimization.
pub(crate) trait GpuDiffusionDelegate:Send {
 fn available_disk_bytes(&mut self)->Option<u64>;
 /// None means an uncertain invocation, never permission to fall back.
 fn begin(&mut self,request:&str)->Option<bool>;
 fn poll(&mut self,token:u64)->Option<String>;
 fn cancel(&mut self,token:u64);
}
pub(crate) struct GpuDiffusionRequest {pub parent_handle:i64,pub token:u64,pub stage_dir:Option<PathBuf>,pub delegate:Option<Box<dyn GpuDiffusionDelegate>>}
/// Named stage bundles keep additions from changing every export ABI/call site.
#[derive(Default)]
pub(crate) struct GpuStageRequests {pub route:ScienceRoute,pub science:ScienceRequests,pub diffusion:Option<GpuDiffusionRequest>,pub scanner:Option<GpuDiffusionRequest>,pub optical:Option<GpuDiffusionRequest>,pub exposure:Option<GpuDiffusionRequest>,pub dir:Option<GpuDiffusionRequest>}
#[derive(Default)]
pub(crate) struct GpuStageDelegates {pub science:ScienceDelegates,pub diffusion:Option<Box<dyn GpuDiffusionDelegate>>,pub scanner:Option<Box<dyn GpuDiffusionDelegate>>,pub optical:Option<Box<dyn GpuDiffusionDelegate>>,pub exposure:Option<Box<dyn GpuDiffusionDelegate>>,pub dir:Option<Box<dyn GpuDiffusionDelegate>>}
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct GpuDiffusionMetrics {
 #[serde(skip_serializing_if="Option::is_none")] pub upload_bytes:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub readback_bytes:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub dispatches:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub prepare_error_code:Option<String>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_count:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_max_abs:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_rms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_tolerance_relative:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_tolerance_absolute:Option<f64>,
 pub requested:bool,pub backend:&'static str,pub attempted:bool,pub fallback_reason:&'static str,pub resumed:bool,
 #[serde(skip_serializing_if="Option::is_none")] pub helper_total_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub gpu_stage_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub setup_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub tiles:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub fft_side:Option<u32>,
 #[serde(skip_serializing_if="Option::is_none")] pub combined_peak_bytes:Option<u64>,
}
struct GpuDiffusion {request:Option<GpuDiffusionRequest>,metrics:GpuDiffusionMetrics}
/// Only the explicitly opted-in printing/scanner PCHIP chain uses f32.
/// Paths, tokens and internal descriptors never appear in public metrics.
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct GpuScannerMetrics {
 #[serde(skip_serializing_if="Option::is_none")] pub upload_bytes:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub readback_bytes:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub dispatches:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub prepare_error_code:Option<String>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_count:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_max_abs:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_rms:Option<f64>,
 pub requested:bool,pub backend:&'static str,pub attempted:bool,pub fallback_reason:&'static str,pub resumed:bool,
 #[serde(skip_serializing_if="Option::is_none")] pub helper_total_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub gpu_stage_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub setup_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub chunks:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub combined_peak_bytes:Option<u64>,
}
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct GpuOpticalMetrics {
 #[serde(skip_serializing_if="Option::is_none")] pub upload_bytes:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub readback_bytes:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub dispatches:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub chunks:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub prepare_error_code:Option<String>,
 #[serde(skip_serializing_if="Option::is_none")] pub passes:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_count:Option<u64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_max_abs:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub sample_rms:Option<f64>,
 pub requested:bool,pub backend:&'static str,pub attempted:bool,pub fallback_reason:&'static str,pub resumed:bool,
 #[serde(skip_serializing_if="Option::is_none")] pub helper_total_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub gpu_stage_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub setup_ms:Option<f64>,
 #[serde(skip_serializing_if="Option::is_none")] pub combined_peak_bytes:Option<u64>,
}
struct GpuOptical {request:Option<GpuDiffusionRequest>,metrics:GpuOpticalMetrics}
impl GpuOptical {
 fn new(request:Option<GpuDiffusionRequest>,resume:u8,backend:crate::checkpoint::FilmOpticalBackend)->Self {
  Self{metrics:GpuOpticalMetrics{upload_bytes:None,readback_bytes:None,dispatches:None,chunks:None,prepare_error_code:None,passes:None,sample_count:None,sample_max_abs:None,sample_rms:None,requested:request.is_some(),backend:backend.as_str(),attempted:false,fallback_reason:"none",resumed:resume>=3,helper_total_ms:None,gpu_stage_ms:None,setup_ms:None,combined_peak_bytes:None},request}
 }
}
struct GpuDir {request:Option<GpuDiffusionRequest>,metrics:GpuOpticalMetrics}
impl GpuDir {
 fn new(request:Option<GpuDiffusionRequest>,resume:u8,backend:crate::checkpoint::FilmDirBackend)->Self {
  Self{metrics:GpuOpticalMetrics{upload_bytes:None,readback_bytes:None,dispatches:None,chunks:None,prepare_error_code:None,passes:None,sample_count:None,sample_max_abs:None,sample_rms:None,requested:request.is_some(),backend:backend.as_str(),attempted:false,fallback_reason:"none",resumed:resume>=4,helper_total_ms:None,gpu_stage_ms:None,setup_ms:None,combined_peak_bytes:None},request}
 }
}
struct GpuScanner {request:Option<GpuDiffusionRequest>,metrics:GpuScannerMetrics}
impl GpuScanner {
 fn new(request:Option<GpuDiffusionRequest>,resume:u8,backend:crate::checkpoint::PrintingScanningBackend)->Self {
  Self{metrics:GpuScannerMetrics{upload_bytes:None,readback_bytes:None,dispatches:None,prepare_error_code:None,sample_count:None,sample_max_abs:None,sample_rms:None,requested:request.is_some(),backend:backend.as_str(),attempted:false,fallback_reason:"none",resumed:resume>=6,helper_total_ms:None,gpu_stage_ms:None,setup_ms:None,chunks:None,combined_peak_bytes:None},request}
 }
}
struct GpuExposure {request:Option<GpuDiffusionRequest>,metrics:GpuScannerMetrics}
impl GpuExposure {
 fn new(request:Option<GpuDiffusionRequest>,resume:u8,backend:crate::checkpoint::FilmExposureBackend)->Self {
  Self{metrics:GpuScannerMetrics{upload_bytes:None,readback_bytes:None,dispatches:None,prepare_error_code:None,sample_count:None,sample_max_abs:None,sample_rms:None,requested:request.is_some(),backend:backend.as_str(),attempted:false,fallback_reason:"none",resumed:resume>=2,helper_total_ms:None,gpu_stage_ms:None,setup_ms:None,chunks:None,combined_peak_bytes:None},request}
 }
}
#[derive(Clone,Copy,Debug,PartialEq)]
enum GpuTerminal {Success,Failed,Cancelled,NotStarted}
struct GpuPoll {terminal:Option<GpuTerminal>,completed:u64,code:String,summary:Option<serde_json::Value>}
fn parse_gpu_poll(raw:&str,token:u64,total:u64)->Option<GpuPoll> {
 if raw.len()>16*1024 {return None;}
 let v:serde_json::Value=serde_json::from_str(raw).ok()?;let map=v.as_object()?;
 if map.len()<6||map.len()>7||map.keys().any(|k|!["schemaVersion","token","status","completedPixels","totalPixels","code","summary"].contains(&k.as_str())) {return None;}
 if v["schemaVersion"].as_u64()!=Some(1)||v["token"].as_u64()!=Some(token)||v["totalPixels"].as_u64()!=Some(total) {return None;}
 let completed=v["completedPixels"].as_u64()?;if completed>total {return None;}
 let code=v["code"].as_str()?;if code.len()>96 {return None;}
 let terminal=match v["status"].as_str()? {"pending"|"running"|"stopping"=>None,"success_stopped"=>Some(GpuTerminal::Success),"failed_stopped"=>Some(GpuTerminal::Failed),"cancelled_stopped"=>Some(GpuTerminal::Cancelled),"not_started"=>Some(GpuTerminal::NotStarted),_=>return None};
 Some(GpuPoll{terminal,completed,code:code.into(),summary:v.get("summary").cloned()})
}
/// Native helper elapsed includes IO, numeric guards and driver waits. These
/// counters preserve measured transfers without presenting absent data as zero.
fn gpu_transfer_counters(summary:&serde_json::Value)->(Option<u64>,Option<u64>,Option<u64>) {
 (summary["uploadBytes"].as_u64().filter(|v|*v<=64*1024*1024*1024),summary["readbackBytes"].as_u64().filter(|v|*v<=64*1024*1024*1024),summary["dispatches"].as_u64().filter(|v|*v<=10_000_000))
}
fn gpu_fallback_reason(code:&str,default:&'static str)->&'static str {
 match code {
  "PREVIEW_BUDGET_EXHAUSTED"=>"preview_budget",
  "TIMED_OUT"|"TIMEOUT"|"GPU_STAGE_TIMEOUT"=>"helper_timeout",
  "MEMORY_UNAVAILABLE"|"PROBE_BUDGET_EXCEEDED"|"BUDGET_EXCEEDED"|"GPU_STAGE_MEMORY_LIMIT"|"GPU_OPTICS_MEMORY_LIMIT"|"GPU_DIR_MEMORY_LIMIT"|"GPU_EXPOSURE_MEMORY_LIMIT"|"GPU_FINISH_MEMORY_LIMIT"|"INSUFFICIENT_GPU_STAGE_MEMORY"=>"helper_memory",
  "NUMERIC_REJECTED"|"GPU_STAGE_NUMERIC_CHECK"|"SAMPLE_GUARD_FAILED"|"QUALITY_GUARD_FAILED"|"GPU_OPTICS_NUMERIC_CHECK"|"GPU_DIR_NUMERIC_CHECK"|"GPU_EXPOSURE_NUMERIC_CHECK"|"GPU_FINISH_NUMERIC_CHECK"=>"quality_guard",
  "GPU_UNAVAILABLE_OR_LIMITS"|"GPU_DEVICE_FAILED"|"GPU_STAGE_READBACK_FAILED"|"GPU_ERROR"|"DRIVER_ERROR"|"DEVICE_LOST"=>"driver_error",
  "ORIGINAL_PSF_EXCEEDS_GPU_LIMIT"|"GPU_TILE_COUNT_LIMIT"=>"geometry_limit",
  "UNSUPPORTED"|"GPU_FINISH_UNSUPPORTED"|"GPU_EXPOSURE_UNSUPPORTED"|"GPU_OPTICS_UNSUPPORTED"|"GPU_DIR_UNSUPPORTED"=>"unsupported_configuration",
  "NATIVE_UNAVAILABLE"=>"delegate_unavailable",_=>default,
 }
}
/// This owner is declared after the job-directory guard: unwind drains the helper
/// BEFORE any candidate/source links, export scratch or outer reservation can drop.
const GPU_TRANSPORT_ERROR_LIMIT:usize=20;
type GpuMayRun=std::sync::Arc<std::sync::atomic::AtomicBool>;
struct GpuHelperOwner<'a> {may_run:GpuMayRun,delegate:&'a mut dyn GpuDiffusionDelegate,token:u64,total:u64,may_have_started:bool,terminal:bool,cancel_sent:bool}
impl<'a> GpuHelperOwner<'a> {
 fn new(delegate:&'a mut dyn GpuDiffusionDelegate,token:u64,total:u64,may_run:GpuMayRun)->Self {Self{may_run,delegate,token,total,may_have_started:false,terminal:false,cancel_sent:false}}
 fn begin(&mut self,request:&str)->bool {
  self.may_have_started=true;self.may_run.store(true,std::sync::atomic::Ordering::Release);
  if self.delegate.begin(request)==Some(false) {self.terminal=true;self.may_run.store(false,std::sync::atomic::Ordering::Release);false}else{true}
 }
 fn cancel_once(&mut self) {if !self.cancel_sent {self.cancel_sent=true;self.delegate.cancel(self.token);}}
 fn poll(&mut self)->Option<GpuPoll> {let parsed=parse_gpu_poll(&self.delegate.poll(self.token)?,self.token,self.total)?;if parsed.terminal.is_some(){self.terminal=true;self.may_run.store(false,std::sync::atomic::Ordering::Release);}Some(parsed)}
}
impl Drop for GpuHelperOwner<'_> {
 fn drop(&mut self) {
  if !self.may_have_started||self.terminal{return;}
  let _=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||self.cancel_once()));
  // A broken callback must reach Java's independent finally-drain. Preserve
  // private files under uncertainty rather than blocking Rust Drop forever.
  for _ in 0..GPU_TRANSPORT_ERROR_LIMIT {
   if self.terminal {break;}
   let _=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||self.poll()));
   if !self.terminal {std::thread::sleep(Duration::from_millis(50));}
  }
 }
}
struct GpuStageDirectory {path:PathBuf,may_run:GpuMayRun}
impl GpuStageDirectory {fn new(path:PathBuf,may_run:GpuMayRun,handle:i64)->Result<Self>{let owner=Self{path,may_run};crate::gpu_retained::register(handle,&owner.path)?;Ok(owner)}}
impl Drop for GpuStageDirectory {fn drop(&mut self){if !self.may_run.load(std::sync::atomic::Ordering::Acquire){let _=fs::remove_dir_all(&self.path);}}}
#[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
struct GpuStageOutputs {paths:[PathBuf;3],keep:bool}
#[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
impl Drop for GpuStageOutputs {fn drop(&mut self){if !self.keep {for path in &self.paths {let _=fs::remove_file(path);}}}}
impl GpuDiffusion {
 fn new(request:Option<GpuDiffusionRequest>,resume:u8,backend:crate::checkpoint::CameraDiffusionBackend)->Self {
  Self{metrics:GpuDiffusionMetrics{upload_bytes:None,readback_bytes:None,dispatches:None,prepare_error_code:None,sample_count:None,sample_max_abs:None,sample_rms:None,sample_tolerance_relative:None,sample_tolerance_absolute:None,requested:request.is_some(),backend:backend.as_str(),attempted:false,fallback_reason:"none",resumed:resume>=3,helper_total_ms:None,gpu_stage_ms:None,setup_ms:None,tiles:None,fft_side:None,combined_peak_bytes:None},request}
 }
 #[allow(clippy::too_many_arguments)]
 fn camera_stage(&mut self,sources:&[PathBuf;3],scratch:&mut Scratch,width:u32,height:u32,pitch:f64,family:&str,kernel:&spektrafilm_model::diffusion::ExactDiffusionKernel,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->Result<Option<[PathBuf;3]>> {
  if self.request.is_none(){return Ok(None);}
  #[cfg(not(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research"))))]
  {let _=(sources,scratch,width,height,pitch,family,kernel,&mut cancelled,&mut progress);self.metrics.fallback_reason="unsupported_build";Ok(None)}
  #[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
  {
   use crate::research_diffusion::full_stage;
   let request=self.request.as_mut().unwrap();
   let Some(delegate)=request.delegate.as_deref_mut() else {self.metrics.fallback_reason="delegate_unavailable";return Ok(None);};
   let Some(dir)=request.stage_dir.as_ref() else {self.metrics.fallback_reason="path_unavailable";return Ok(None);};
   let required=match full_stage::estimated_disk_bytes(width,height,kernel.radius){Ok(v)=>v,Err(_)=>{self.metrics.fallback_reason="geometry_limit";return Ok(None);}};
   let available=delegate.available_disk_bytes().unwrap_or(0);if required>available {self.metrics.fallback_reason="disk_unavailable";return Ok(None);}
   if !dir.parent().is_some_and(|root|fs::symlink_metadata(root).is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink())&&root.canonicalize().ok().as_deref()==Some(root)) {self.metrics.fallback_reason="path_unavailable";return Ok(None);}
   cancel(&mut cancelled)?;let setup=Instant::now();
   let planned=full_stage::prepare_stage([sources[0].as_path(),sources[1].as_path(),sources[2].as_path()],dir,request.token,width,height,pitch,family,kernel,&mut cancelled);
   self.metrics.setup_ms=Some(setup.elapsed().as_secs_f64()*1000.);
   let plan=match planned {Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU stage preparation cancelled"));}self.metrics.prepare_error_code=crate::gpu_stage_staging::diagnostic_code(&code);self.metrics.fallback_reason=gpu_fallback_reason(&code,"prepare_failed");return Ok(None);}};
   let may_run=std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
   let _directory=GpuStageDirectory::new(dir.clone(),may_run.clone(),request.parent_handle)?;
   scratch.gpu_may_run=Some(may_run.clone());
   self.metrics.tiles=Some(plan.tile_count);self.metrics.fft_side=Some(plan.fft_side);self.metrics.combined_peak_bytes=Some(plan.combined_peak_bytes);
   scratch.peak=scratch.peak.max(scratch.live()?.saturating_add(plan.disk_bytes));
   let total=width as u64*height as u64*3;
   let wire=serde_json::json!({"schemaVersion":1,"token":request.token,"parentHandle":request.parent_handle,"jobDir":dir,"manifestSha256":plan.manifest_sha256,"budgetBytes":plan.combined_peak_bytes,"width":width,"height":height,"totalPixels":total}).to_string();
   let mut helper=GpuHelperOwner::new(delegate,request.token,total,may_run);let started=Instant::now();
   if !helper.begin(&wire) {self.metrics.fallback_reason=helper.poll().map_or("not_started",|p|gpu_fallback_reason(&p.code,"not_started"));return Ok(None);}
   self.metrics.attempted=true;let mut was_cancelled=false;let mut completed=0;let mut transport_errors=0;
   let terminal=loop {
    if cancelled(){was_cancelled=true;helper.cancel_once();}
    if let Some(poll)=helper.poll() {
     transport_errors=0;completed=completed.max(poll.completed);progress(completed,total);
     if poll.terminal.is_some(){break poll;}
    } else {
     transport_errors+=1;
     if transport_errors>=GPU_TRANSPORT_ERROR_LIMIT {helper.cancel_once();return Err(error(if was_cancelled{"CANCELLED"}else{"GPU_CALLBACK_UNAVAILABLE"},"GPU callback unavailable; helper ownership retained"));}
    }
    std::thread::sleep(Duration::from_millis(50));
   };
   self.metrics.helper_total_ms=Some(started.elapsed().as_secs_f64()*1000.);
   if was_cancelled||terminal.terminal==Some(GpuTerminal::Cancelled)||terminal.code=="CANCELLED"||cancelled(){return Err(error("CANCELLED","GPU stage cancelled"));}
   if terminal.terminal!=Some(GpuTerminal::Success) {
    self.metrics.fallback_reason=gpu_fallback_reason(&terminal.code,if terminal.terminal==Some(GpuTerminal::NotStarted){"not_started"}else{"helper_failed"});return Ok(None);
   }
   if let Some(summary)=terminal.summary.as_ref() {
    (self.metrics.upload_bytes,self.metrics.readback_bytes,self.metrics.dispatches)=gpu_transfer_counters(summary);
    let finite=|key:&str|summary[key].as_f64().filter(|v|v.is_finite()&&*v>=0.);
    // Host mirror tests must never present host timing as actual GPU timing.
    if summary["testOnly"]!=true {self.metrics.gpu_stage_ms=finite("elapsedMs");}
    self.metrics.sample_count=summary["sampleCount"].as_u64().filter(|v|*v==plan.tile_count);
    self.metrics.sample_max_abs=finite("sampleMaxAbs");self.metrics.sample_rms=finite("sampleRms");
    self.metrics.sample_tolerance_relative=finite("sampleToleranceRelative");self.metrics.sample_tolerance_absolute=finite("sampleToleranceAbsolute");
   }
   let candidates=match full_stage::validate_outputs(dir,request.token,&plan.manifest_sha256,&mut cancelled) {
    Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU output validation cancelled"));}self.metrics.fallback_reason="invalid_output";return Ok(None);}
   };
   let mut adopted=GpuStageOutputs{paths:std::array::from_fn(|_|scratch.path("gpu-camera-diffusion")),keep:false};
   for ch in 0..3 {
    if candidates[ch]!=dir.join("candidate").join(format!("plane-{ch}.f64le"))||fs::rename(&candidates[ch],&adopted.paths[ch]).is_err() {self.metrics.fallback_reason="commit_failed";return Ok(None);}
   }
   cancel(&mut cancelled)?;self.metrics.backend="gpu_f32_v1";self.metrics.fallback_reason="none";
   adopted.keep=true;progress(total,total);Ok(Some(adopted.paths.clone()))
  }
 }
}

#[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
struct GpuScannerOutput {path:PathBuf,keep:bool}
#[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
impl Drop for GpuScannerOutput {fn drop(&mut self){if !self.keep {let _=fs::remove_file(&self.path);}}}
impl GpuScanner {
 #[allow(clippy::too_many_arguments)]
 fn printing_stage(&mut self,sources:&[PathBuf;3],prepared:&spektrafilm_core::pipeline::PreparedStreamedFinish,print_raw:bool,scratch:&mut Scratch,width:u32,height:u32,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->Result<Option<PathBuf>> {
  if self.request.is_none(){return Ok(None);}
  #[cfg(not(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research"))))]
  {let _=(sources,prepared,print_raw,scratch,width,height,&mut cancelled,&mut progress);self.metrics.fallback_reason="unsupported_build";Ok(None)}
  #[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
  {
   use crate::research_finish;
   use spektrafilm_core::pipeline::GpuFinishInput;
   let request=self.request.as_mut().unwrap();
   let Some(delegate)=request.delegate.as_deref_mut() else {self.metrics.fallback_reason="delegate_unavailable";return Ok(None);};
   let Some(dir)=request.stage_dir.as_ref() else {self.metrics.fallback_reason="path_unavailable";return Ok(None);};
   let descriptor=match prepared.gpu_finish_descriptor(if print_raw{GpuFinishInput::PrintRaw}else{GpuFinishInput::FilmDensity}) {Ok(v)=>v,Err(_)=>{self.metrics.fallback_reason="unsupported_model";return Ok(None);}};
   let required=match research_finish::estimated_disk_bytes(width,height,&descriptor){Ok(v)=>v,Err(_)=>{self.metrics.fallback_reason="geometry_limit";return Ok(None);}};
   if required>delegate.available_disk_bytes().unwrap_or(0) {self.metrics.fallback_reason="disk_unavailable";return Ok(None);}
   if !dir.parent().is_some_and(|root|fs::symlink_metadata(root).is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink())&&root.canonicalize().ok().as_deref()==Some(root)) {self.metrics.fallback_reason="path_unavailable";return Ok(None);}
   // This stage has an independent one-shot helper owner, after the camera
   // helper is proved dead and the original CPU optics/grain have finished.
   cancel(&mut cancelled)?;let setup=Instant::now();
   let planned=research_finish::prepare_stage([sources[0].as_path(),sources[1].as_path(),sources[2].as_path()],dir,request.token,width,height,&descriptor,&mut cancelled);
   self.metrics.setup_ms=Some(setup.elapsed().as_secs_f64()*1000.);
   let plan=match planned {Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU scanner preparation cancelled"));}self.metrics.prepare_error_code=crate::gpu_stage_staging::diagnostic_code(&code);self.metrics.fallback_reason=gpu_fallback_reason(&code,"prepare_failed");return Ok(None);}};
   let may_run=std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
   let _directory=GpuStageDirectory::new(dir.clone(),may_run.clone(),request.parent_handle)?;
   scratch.gpu_may_run=Some(may_run.clone());
   self.metrics.chunks=Some(plan.chunk_count);self.metrics.combined_peak_bytes=Some(plan.combined_peak_bytes);
   scratch.peak=scratch.peak.max(scratch.live()?.saturating_add(plan.disk_bytes));
   let total=width as u64*height as u64*3;
   let wire=serde_json::json!({"schemaVersion":1,"operation":"fullPrintScanLogXyz","token":request.token,"parentHandle":request.parent_handle,"jobDir":dir,"manifestSha256":plan.manifest_sha256,"budgetBytes":plan.combined_peak_bytes,"width":width,"height":height,"totalPixels":total}).to_string();
   let mut helper=GpuHelperOwner::new(delegate,request.token,total,may_run);let started=Instant::now();
   if !helper.begin(&wire) {self.metrics.fallback_reason=helper.poll().map_or("not_started",|p|gpu_fallback_reason(&p.code,"not_started"));return Ok(None);}
   self.metrics.attempted=true;let mut was_cancelled=false;let mut completed=0;let mut transport_errors=0;
   let terminal=loop {
    if cancelled(){was_cancelled=true;helper.cancel_once();}
    if let Some(poll)=helper.poll() {
     transport_errors=0;completed=completed.max(poll.completed);progress(completed,total);
     if poll.terminal.is_some(){break poll;}
    } else {
     transport_errors+=1;
     if transport_errors>=GPU_TRANSPORT_ERROR_LIMIT {helper.cancel_once();return Err(error(if was_cancelled{"CANCELLED"}else{"GPU_CALLBACK_UNAVAILABLE"},"GPU scanner callback unavailable; helper ownership retained"));}
    }
    std::thread::sleep(Duration::from_millis(50));
   };
   self.metrics.helper_total_ms=Some(started.elapsed().as_secs_f64()*1000.);
   if was_cancelled||terminal.terminal==Some(GpuTerminal::Cancelled)||terminal.code=="CANCELLED"||cancelled(){return Err(error("CANCELLED","GPU scanner cancelled"));}
   if terminal.terminal!=Some(GpuTerminal::Success) {
    self.metrics.fallback_reason=gpu_fallback_reason(&terminal.code,if terminal.terminal==Some(GpuTerminal::NotStarted){"not_started"}else{"helper_failed"});return Ok(None);
   }
   if let Some(summary)=terminal.summary.as_ref() {
    (self.metrics.upload_bytes,self.metrics.readback_bytes,self.metrics.dispatches)=gpu_transfer_counters(summary);
    if summary["testOnly"]!=true&&summary["hostArithmeticMirror"]!=true&&summary["gpuUsed"]!=false {self.metrics.gpu_stage_ms=summary["elapsedMs"].as_f64().filter(|v|v.is_finite()&&*v>=0.);}
    self.metrics.sample_count=summary["sampleCount"].as_u64().filter(|v|*v<=total);
    self.metrics.sample_max_abs=summary["sampleMaxAbs"].as_f64().filter(|v|v.is_finite()&&*v>=0.);
    self.metrics.sample_rms=summary["sampleRms"].as_f64().filter(|v|v.is_finite()&&*v>=0.);
   }
   let candidate=match research_finish::validate_output(dir,request.token,&plan.manifest_sha256,&mut cancelled) {
    Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU scanner output validation cancelled"));}self.metrics.fallback_reason="invalid_output";return Ok(None);}
   };
   // A single interleaved file makes adoption atomic across all three channels.
   // Original source planes remain untouched for a complete CPU fallback.
   let mut adopted=GpuScannerOutput{path:scratch.path("gpu-scan-logxyz"),keep:false};
   if fs::rename(&candidate,&adopted.path).is_err() {self.metrics.fallback_reason="commit_failed";return Ok(None);}
   cancel(&mut cancelled)?;self.metrics.backend="gpu_pchip_f32_v1";self.metrics.fallback_reason="none";
   adopted.keep=true;progress(total,total);Ok(Some(adopted.path.clone()))
  }
 }
}
impl GpuOptical {
 #[allow(clippy::too_many_arguments)]
 fn optical_stage(&mut self,sources:&[PathBuf;3],params:&RuntimeParams,pitch:f32,scratch:&mut Scratch,width:u32,height:u32,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->Result<Option<[PathBuf;3]>> {
  if self.request.is_none(){return Ok(None);}
  #[cfg(not(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research"))))]
  {let _=(sources,params,pitch,scratch,width,height,&mut cancelled,&mut progress);self.metrics.fallback_reason="unsupported_build";Ok(None)}
  #[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
  {
   use crate::research_spatial::{FilmOpticsPlan,full_stage};
   let request=self.request.as_mut().unwrap();
   let Some(delegate)=request.delegate.as_deref_mut() else {self.metrics.fallback_reason="delegate_unavailable";return Ok(None);};
   let Some(dir)=request.stage_dir.as_ref() else {self.metrics.fallback_reason="path_unavailable";return Ok(None);};
   let descriptor=match FilmOpticsPlan::new(params,pitch,width,height) {Ok(v)=>v,Err(code)=>{self.metrics.fallback_reason=gpu_fallback_reason(&code,"unsupported_model");return Ok(None);}};
   if !descriptor.has_work(){self.metrics.fallback_reason="inactive";return Ok(None);}
   let required=match full_stage::estimated_disk_bytes(width,height,&descriptor){Ok(v)=>v,Err(_)=>{self.metrics.fallback_reason="geometry_limit";return Ok(None);}};
   if required>delegate.available_disk_bytes().unwrap_or(0) {self.metrics.fallback_reason="disk_unavailable";return Ok(None);}
   if !dir.parent().is_some_and(|root|fs::symlink_metadata(root).is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink())&&root.canonicalize().ok().as_deref()==Some(root)) {self.metrics.fallback_reason="path_unavailable";return Ok(None);}
   // This stage has an independent one-shot helper owner, after the camera
   // helper is proved dead and before the CPU film density/DIR/grain run.
   cancel(&mut cancelled)?;let setup=Instant::now();
   let planned=full_stage::prepare_stage([sources[0].as_path(),sources[1].as_path(),sources[2].as_path()],dir,request.token,width,height,&descriptor,&mut cancelled);
   self.metrics.setup_ms=Some(setup.elapsed().as_secs_f64()*1000.);
   let plan=match planned {Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU optical preparation cancelled"));}self.metrics.prepare_error_code=crate::gpu_stage_staging::diagnostic_code(&code);self.metrics.fallback_reason=gpu_fallback_reason(&code,"prepare_failed");return Ok(None);}};
   let may_run=std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
   let _directory=GpuStageDirectory::new(dir.clone(),may_run.clone(),request.parent_handle)?;
   scratch.gpu_may_run=Some(may_run.clone());
   self.metrics.passes=Some(plan.pass_count);self.metrics.combined_peak_bytes=Some(plan.combined_peak_bytes);
   scratch.peak=scratch.peak.max(scratch.live()?.saturating_add(plan.disk_bytes));
   let total=width as u64*height as u64*3;
   let wire=serde_json::json!({"schemaVersion":1,"operation":"fullFilmOptics","token":request.token,"parentHandle":request.parent_handle,"jobDir":dir,"manifestSha256":plan.manifest_sha256,"budgetBytes":plan.combined_peak_bytes,"width":width,"height":height,"totalPixels":total}).to_string();
   let mut helper=GpuHelperOwner::new(delegate,request.token,total,may_run);let started=Instant::now();
   if !helper.begin(&wire) {self.metrics.fallback_reason=helper.poll().map_or("not_started",|p|gpu_fallback_reason(&p.code,"not_started"));return Ok(None);}
   self.metrics.attempted=true;let mut was_cancelled=false;let mut completed=0;let mut transport_errors=0;
   let terminal=loop {
    if cancelled(){was_cancelled=true;helper.cancel_once();}
    if let Some(poll)=helper.poll() {
     transport_errors=0;completed=completed.max(poll.completed);progress(completed,total);
     if poll.terminal.is_some(){break poll;}
    } else {
     transport_errors+=1;
     if transport_errors>=GPU_TRANSPORT_ERROR_LIMIT {helper.cancel_once();return Err(error(if was_cancelled{"CANCELLED"}else{"GPU_CALLBACK_UNAVAILABLE"},"GPU optical callback unavailable; helper ownership retained"));}
    }
    std::thread::sleep(Duration::from_millis(50));
   };
   self.metrics.helper_total_ms=Some(started.elapsed().as_secs_f64()*1000.);
   if was_cancelled||terminal.terminal==Some(GpuTerminal::Cancelled)||terminal.code=="CANCELLED"||cancelled(){return Err(error("CANCELLED","GPU optical cancelled"));}
   if terminal.terminal!=Some(GpuTerminal::Success) {
    self.metrics.fallback_reason=gpu_fallback_reason(&terminal.code,if terminal.terminal==Some(GpuTerminal::NotStarted){"not_started"}else{"helper_failed"});return Ok(None);
   }
   if let Some(summary)=terminal.summary.as_ref() {
    (self.metrics.upload_bytes,self.metrics.readback_bytes,self.metrics.dispatches)=gpu_transfer_counters(summary);
    if summary["testOnly"]!=true&&summary["hostArithmeticMirror"]!=true&&summary["gpuUsed"]!=false {self.metrics.gpu_stage_ms=summary["elapsedMs"].as_f64().filter(|v|v.is_finite()&&*v>=0.);}
    self.metrics.sample_count=summary["sampleCount"].as_u64().filter(|v|*v<=plan.pass_count.saturating_mul(width as u64+height as u64));
    self.metrics.sample_max_abs=summary["sampleMaxAbs"].as_f64().filter(|v|v.is_finite()&&*v>=0.);
    self.metrics.sample_rms=summary["sampleRms"].as_f64().filter(|v|v.is_finite()&&*v>=0.);
   }
   let candidates=match full_stage::validate_outputs(dir,request.token,&plan.manifest_sha256,&mut cancelled) {
    Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU optical output validation cancelled"));}self.metrics.fallback_reason="invalid_output";return Ok(None);}
   };
   // Stage originals survive until all channels validate and are moved into
   // scratch. No downstream consumer can observe a partial channel set.
   let mut adopted=GpuStageOutputs{paths:std::array::from_fn(|_|scratch.path("gpu-film-optics")),keep:false};
   for ch in 0..3 {
    if candidates[ch]!=dir.join("candidate").join(format!("plane-{ch}.f64le"))||fs::rename(&candidates[ch],&adopted.paths[ch]).is_err() {self.metrics.fallback_reason="commit_failed";return Ok(None);}
   }
   cancel(&mut cancelled)?;self.metrics.backend="gpu_spatial_f32_v1";self.metrics.fallback_reason="none";
   adopted.keep=true;progress(total,total);Ok(Some(adopted.paths.clone()))
  }
 }
}
impl GpuDir {
 #[allow(clippy::too_many_arguments)]
 fn density_stage(&mut self,sources:&[PathBuf;3],pipe:&Pipeline,pitch:f32,scratch:&mut Scratch,width:u32,height:u32,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->Result<Option<[PathBuf;3]>> {
  if self.request.is_none(){return Ok(None);}
  #[cfg(not(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research"))))]
  {let _=(sources,pipe,pitch,scratch,width,height,&mut cancelled,&mut progress);self.metrics.fallback_reason="unsupported_build";Ok(None)}
  #[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
  {
   use crate::research_dir::{DirPlan,full_stage};
   let request=self.request.as_mut().unwrap();
   let Some(delegate)=request.delegate.as_deref_mut() else {self.metrics.fallback_reason="delegate_unavailable";return Ok(None);};
   let Some(dir)=request.stage_dir.as_ref() else {self.metrics.fallback_reason="path_unavailable";return Ok(None);};
   let descriptor=match DirPlan::new(pipe,pitch,width,height) {Ok(v)=>v,Err(code)=>{self.metrics.fallback_reason=gpu_fallback_reason(&code,"unsupported_model");return Ok(None);}};
   let required=match full_stage::estimated_disk_bytes(width,height,&descriptor){Ok(v)=>v,Err(_)=>{self.metrics.fallback_reason="geometry_limit";return Ok(None);}};
   if required>delegate.available_disk_bytes().unwrap_or(0) {self.metrics.fallback_reason="disk_unavailable";return Ok(None);}
   if !dir.parent().is_some_and(|root|fs::symlink_metadata(root).is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink())&&root.canonicalize().ok().as_deref()==Some(root)) {self.metrics.fallback_reason="path_unavailable";return Ok(None);}
   // Original full-frame CPU film-log planes stay immutable throughout this
   // complete initial-density, DIR diffusion and redevelopment helper.
   cancel(&mut cancelled)?;let setup=Instant::now();
   let planned=full_stage::prepare_stage([sources[0].as_path(),sources[1].as_path(),sources[2].as_path()],dir,request.token,width,height,&descriptor,&mut cancelled);
   self.metrics.setup_ms=Some(setup.elapsed().as_secs_f64()*1000.);
   let plan=match planned {Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU DIR preparation cancelled"));}self.metrics.prepare_error_code=crate::gpu_stage_staging::diagnostic_code(&code);self.metrics.fallback_reason=gpu_fallback_reason(&code,"prepare_failed");return Ok(None);}};
   let may_run=std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
   let _directory=GpuStageDirectory::new(dir.clone(),may_run.clone(),request.parent_handle)?;
   scratch.gpu_may_run=Some(may_run.clone());
   self.metrics.chunks=Some(plan.chunk_count);self.metrics.passes=Some(plan.pass_count);self.metrics.combined_peak_bytes=Some(plan.combined_peak_bytes);
   scratch.peak=scratch.peak.max(scratch.live()?.saturating_add(plan.disk_bytes));
   let total=width as u64*height as u64*3;
   let wire=serde_json::json!({"schemaVersion":1,"operation":"fullFilmDirDensity","token":request.token,"parentHandle":request.parent_handle,"jobDir":dir,"manifestSha256":plan.manifest_sha256,"budgetBytes":plan.combined_peak_bytes,"width":width,"height":height,"totalPixels":total}).to_string();
   let mut helper=GpuHelperOwner::new(delegate,request.token,total,may_run);let started=Instant::now();
   if !helper.begin(&wire) {self.metrics.fallback_reason=helper.poll().map_or("not_started",|p|gpu_fallback_reason(&p.code,"not_started"));return Ok(None);}
   self.metrics.attempted=true;let mut was_cancelled=false;let mut completed=0;let mut transport_errors=0;
   let terminal=loop {
    if cancelled(){was_cancelled=true;helper.cancel_once();}
    if let Some(poll)=helper.poll() {
     transport_errors=0;completed=completed.max(poll.completed);progress(completed,total);
     if poll.terminal.is_some(){break poll;}
    } else {
     transport_errors+=1;
     if transport_errors>=GPU_TRANSPORT_ERROR_LIMIT {helper.cancel_once();return Err(error(if was_cancelled{"CANCELLED"}else{"GPU_CALLBACK_UNAVAILABLE"},"GPU DIR callback unavailable; helper ownership retained"));}
    }
    std::thread::sleep(Duration::from_millis(50));
   };
   self.metrics.helper_total_ms=Some(started.elapsed().as_secs_f64()*1000.);
   if was_cancelled||terminal.terminal==Some(GpuTerminal::Cancelled)||terminal.code=="CANCELLED"||cancelled(){return Err(error("CANCELLED","GPU DIR cancelled"));}
   if terminal.terminal!=Some(GpuTerminal::Success) {
    self.metrics.fallback_reason=gpu_fallback_reason(&terminal.code,if terminal.terminal==Some(GpuTerminal::NotStarted){"not_started"}else{"helper_failed"});return Ok(None);
   }
   if let Some(summary)=terminal.summary.as_ref() {
    (self.metrics.upload_bytes,self.metrics.readback_bytes,self.metrics.dispatches)=gpu_transfer_counters(summary);
    if summary["testOnly"]!=true&&summary["hostArithmeticMirror"]!=true&&summary["gpuUsed"]!=false {self.metrics.gpu_stage_ms=summary["elapsedMs"].as_f64().filter(|v|v.is_finite()&&*v>=0.);}
    let point_samples=8*(plan.chunk_count-1)+(width as u64*height as u64-(plan.chunk_count-1)*plan.chunk_pixels as u64).min(8);
    let expected_samples=plan.pass_count*(width as u64+height as u64)+if plan.dir_active{6*point_samples}else{3*point_samples};
    self.metrics.sample_count=summary["sampleCount"].as_u64().filter(|v|*v==expected_samples);
    self.metrics.sample_max_abs=summary["sampleMaxAbs"].as_f64().filter(|v|v.is_finite()&&*v>=0.);
    self.metrics.sample_rms=summary["sampleRms"].as_f64().filter(|v|v.is_finite()&&*v>=0.);
   }
   let candidates=match full_stage::validate_outputs(dir,request.token,&plan.manifest_sha256,&mut cancelled) {
    Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU DIR output validation cancelled"));}self.metrics.fallback_reason="invalid_output";return Ok(None);}
   };
   // Stage originals survive until all channels validate and are moved into
   // scratch. No downstream consumer can observe a partial channel set.
   let mut adopted=GpuStageOutputs{paths:std::array::from_fn(|_|scratch.path("gpu-film-dir")),keep:false};
   for ch in 0..3 {
    if candidates[ch]!=dir.join("candidate").join(format!("plane-{ch}.f64le"))||fs::rename(&candidates[ch],&adopted.paths[ch]).is_err() {self.metrics.fallback_reason="commit_failed";return Ok(None);}
   }
   cancel(&mut cancelled)?;self.metrics.backend="gpu_dir_f32_v1";self.metrics.fallback_reason="none";
   adopted.keep=true;progress(total,total);Ok(Some(adopted.paths.clone()))
  }
 }
}
impl GpuExposure {
 #[allow(clippy::too_many_arguments)]
 fn exposure_stage(&mut self,source:&Path,pipe:&Pipeline,ae:f64,exposure:f64,scratch:&mut Scratch,width:u32,height:u32,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->Result<Option<[PathBuf;3]>> {
  if self.request.is_none(){return Ok(None);}
  #[cfg(not(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research"))))]
  {let _=(source,pipe,ae,exposure,scratch,width,height,&mut cancelled,&mut progress);self.metrics.fallback_reason="unsupported_build";Ok(None)}
  #[cfg(any(feature="gpu-diffusion-research",all(test,feature="diffusion-research")))]
  {
   use crate::research_exposure::{self as full_stage,ExposureDescriptor};
   let request=self.request.as_mut().unwrap();
   let Some(delegate)=request.delegate.as_deref_mut() else {self.metrics.fallback_reason="delegate_unavailable";return Ok(None);};
   let Some(dir)=request.stage_dir.as_ref() else {self.metrics.fallback_reason="path_unavailable";return Ok(None);};
   let descriptor=match ExposureDescriptor::from_pipeline(pipe,ae,exposure) {Ok(v)=>v,Err(code)=>{self.metrics.fallback_reason=gpu_fallback_reason(code,"unsupported_model");return Ok(None);}};
   let required=match full_stage::estimated_disk_bytes(width,height,&descriptor){Ok(v)=>v,Err(_)=>{self.metrics.fallback_reason="geometry_limit";return Ok(None);}};
   if required>delegate.available_disk_bytes().unwrap_or(0) {self.metrics.fallback_reason="disk_unavailable";return Ok(None);}
   if !dir.parent().is_some_and(|root|fs::symlink_metadata(root).is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink())&&root.canonicalize().ok().as_deref()==Some(root)) {self.metrics.fallback_reason="path_unavailable";return Ok(None);}
   // The original global AE result is already fixed. This helper owns only
   // Hanatos RGB-to-raw arithmetic and must die before camera optics starts.
   cancel(&mut cancelled)?;let setup=Instant::now();
   let planned=full_stage::prepare_stage(source,dir,request.token,width,height,&descriptor,&mut cancelled);
   self.metrics.setup_ms=Some(setup.elapsed().as_secs_f64()*1000.);
   let plan=match planned {Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU exposure preparation cancelled"));}self.metrics.prepare_error_code=crate::gpu_stage_staging::diagnostic_code(&code);self.metrics.fallback_reason=gpu_fallback_reason(&code,"prepare_failed");return Ok(None);}};
   drop(descriptor);
   let may_run=std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
   let _directory=GpuStageDirectory::new(dir.clone(),may_run.clone(),request.parent_handle)?;
   scratch.gpu_may_run=Some(may_run.clone());
   self.metrics.chunks=Some(plan.chunk_count);self.metrics.combined_peak_bytes=Some(plan.combined_peak_bytes);
   scratch.peak=scratch.peak.max(scratch.live()?.saturating_add(plan.disk_bytes));
   let total=width as u64*height as u64*3;
   let wire=serde_json::json!({"schemaVersion":1,"operation":"fullFilmExposure","token":request.token,"parentHandle":request.parent_handle,"jobDir":dir,"manifestSha256":plan.manifest_sha256,"budgetBytes":plan.combined_peak_bytes,"width":width,"height":height,"totalPixels":total}).to_string();
   let mut helper=GpuHelperOwner::new(delegate,request.token,total,may_run);let started=Instant::now();
   if !helper.begin(&wire) {self.metrics.fallback_reason=helper.poll().map_or("not_started",|p|gpu_fallback_reason(&p.code,"not_started"));return Ok(None);}
   self.metrics.attempted=true;let mut was_cancelled=false;let mut completed=0;let mut transport_errors=0;
   let terminal=loop {
    if cancelled(){was_cancelled=true;helper.cancel_once();}
    if let Some(poll)=helper.poll() {
     transport_errors=0;completed=completed.max(poll.completed);progress(completed,total);
     if poll.terminal.is_some(){break poll;}
    } else {
     transport_errors+=1;
     if transport_errors>=GPU_TRANSPORT_ERROR_LIMIT {helper.cancel_once();return Err(error(if was_cancelled{"CANCELLED"}else{"GPU_CALLBACK_UNAVAILABLE"},"GPU exposure callback unavailable; helper ownership retained"));}
    }
    std::thread::sleep(Duration::from_millis(50));
   };
   self.metrics.helper_total_ms=Some(started.elapsed().as_secs_f64()*1000.);
   if was_cancelled||terminal.terminal==Some(GpuTerminal::Cancelled)||terminal.code=="CANCELLED"||cancelled(){return Err(error("CANCELLED","GPU exposure cancelled"));}
   if terminal.terminal!=Some(GpuTerminal::Success) {
    self.metrics.fallback_reason=gpu_fallback_reason(&terminal.code,if terminal.terminal==Some(GpuTerminal::NotStarted){"not_started"}else{"helper_failed"});return Ok(None);
   }
   if let Some(summary)=terminal.summary.as_ref() {
    (self.metrics.upload_bytes,self.metrics.readback_bytes,self.metrics.dispatches)=gpu_transfer_counters(summary);
    if summary["testOnly"]!=true&&summary["hostArithmeticMirror"]!=true&&summary["gpuUsed"]!=false {self.metrics.gpu_stage_ms=summary["elapsedMs"].as_f64().filter(|v|v.is_finite()&&*v>=0.);}
    self.metrics.sample_count=summary["sampleCount"].as_u64().filter(|v|*v<=total);
    self.metrics.sample_max_abs=summary["sampleMaxAbs"].as_f64().filter(|v|v.is_finite()&&*v>=0.);
    self.metrics.sample_rms=summary["sampleRms"].as_f64().filter(|v|v.is_finite()&&*v>=0.);
   }
   let candidates=match full_stage::validate_outputs(dir,request.token,&plan.manifest_sha256,&mut cancelled) {
    Ok(v)=>v,Err(code)=>{cancel(&mut cancelled)?;if code=="CANCELLED"{return Err(error("CANCELLED","GPU exposure output validation cancelled"));}self.metrics.fallback_reason="invalid_output";return Ok(None);}
   };
   // Stage originals survive until all channels validate and are moved into
   // scratch. No downstream consumer can observe a partial channel set.
   let mut adopted=GpuStageOutputs{paths:std::array::from_fn(|_|scratch.path("gpu-film-exposure")),keep:false};
   for ch in 0..3 {
    if candidates[ch]!=dir.join("candidate").join(format!("plane-{ch}.f64le"))||fs::rename(&candidates[ch],&adopted.paths[ch]).is_err() {self.metrics.fallback_reason="commit_failed";return Ok(None);}
   }
   cancel(&mut cancelled)?;self.metrics.backend="gpu_exposure_f32_v1";self.metrics.fallback_reason="none";
   adopted.keep=true;progress(total,total);Ok(Some(adopted.paths.clone()))
  }
 }
}

// Only native execution checkpoints can trigger a heartbeat. Repeating the
// last completed-pixel count is honest: no image work is credited early.
struct DiffusionHeartbeat { last: Instant, interval: Duration }
impl DiffusionHeartbeat {
 fn new(interval:Duration)->Self {Self{last:Instant::now(),interval}}
 fn due(&mut self,now:Instant)->bool {
  if now.duration_since(self.last)>=self.interval {self.last=now;true}else{false}
 }
}

fn error(code:&'static str,message:impl Into<String>)->EngineError {EngineError::new(code,message)}
fn checkpoint_error(e:std::io::Error)->EngineError {if e.kind()==std::io::ErrorKind::InvalidData{error("CHECKPOINT_INVALID","Saved export checkpoint is corrupt or does not match this job")}else{io(e)}}
fn io(e:std::io::Error)->EngineError {error(if e.kind()==std::io::ErrorKind::Interrupted {"CANCELLED"} else {"IO_ERROR"},e.to_string())}
fn cancel(c:&mut impl FnMut()->bool)->Result<()> {if c(){Err(error("CANCELLED","Streamed export cancelled"))}else{Ok(())}}
fn new_file(p:&Path)->Result<File> {OpenOptions::new().write(true).create_new(true).open(p).map_err(io)}
fn read64(f:&mut File,n:usize)->Result<Vec<f64>> {let mut b=vec![0u8;n*8];f.read_exact(&mut b).map_err(io)?;let mut v=Vec::with_capacity(n);for x in b.chunks_exact(8){let a=f64::from_le_bytes(x.try_into().unwrap());if !a.is_finite(){return Err(error("INVALID_PIXELS","Nonfinite intermediate"));}v.push(a);}Ok(v)}
fn write64(f:&mut File,v:&[f64])->Result<()> {let mut b=Vec::with_capacity(v.len()*8);for &x in v {if !x.is_finite(){return Err(error("NONFINITE_OUTPUT","Nonfinite intermediate"));}b.extend_from_slice(&x.to_le_bytes());}f.write_all(&b).map_err(io)}
struct OutputGuard {path:PathBuf,keep:bool}
impl Drop for OutputGuard {fn drop(&mut self){if !self.keep {let _=fs::remove_file(&self.path);if let Some(p)=crate::scan_preview::path(&self.path){let _=fs::remove_file(&p);let _=fs::remove_file(p.with_extension("png.tmp"));}}}}
struct Scratch {gpu_may_run:Option<GpuMayRun>,optimizations:ExportOptimizations,dir:PathBuf,seq:u64,peak:u64,spatial_buffer:u64,read:u64,written:u64,passes:u64}
impl Scratch {
 fn new(dir:&Path)->Result<Self>{fs::create_dir(dir).map_err(io)?;Ok(Self{gpu_may_run:None,optimizations:ExportOptimizations::default(),dir:dir.to_path_buf(),seq:0,peak:0,spatial_buffer:0,read:0,written:0,passes:0})}
 fn path(&mut self,label:&str)->PathBuf {self.seq+=1;self.dir.join(format!("stage-{:05}-{label}.f64le",self.seq))}
 fn live(&self)->Result<u64>{let mut n=0u64;for e in fs::read_dir(&self.dir).map_err(io)? {let e=e.map_err(io)?;let m=e.metadata().map_err(io)?;if m.is_file(){n=n.checked_add(m.len()).ok_or_else(||error("INVALID_DIMENSIONS","Scratch size overflow"))?;}}Ok(n)}
 fn observe(&mut self)->Result<()> {self.peak=self.peak.max(self.live()?);Ok(())}
 fn remove(&self,p:&Path)->Result<()> {fs::remove_file(p).map_err(io)}
 fn blur(&mut self,input:&Path,w:u32,h:u32,sigma:f32,stage:&'static str,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<PathBuf>{
   p.begin_work(stage);cancel(c)?;let out=self.path("gaussian");let before=self.live()?;
   let m=disk_spatial::gaussian_file(input,&out,w,h,sigma,&self.dir,SPATIAL_MEMORY,c,&mut |v|{let (done,total)=if v.pass=="identity" {(v.completed*w as u64,w as u64*h as u64)}else if v.pass=="horizontal" {(v.completed*w as u64,2*w as u64*h as u64)}else{(w as u64*h as u64+v.completed*h as u64,2*w as u64*h as u64)};p.pass_fraction(stage,v.completed,v.total,done,total)}).map_err(io)?;
   // gaussian_file sets full logical lengths for output and temporary at once;
   // this counts their actual live logical bytes, not RSS/filesystem cache.
   self.peak=self.peak.max(before+m.output_bytes+m.scratch_bytes);self.spatial_buffer=self.spatial_buffer.max(m.peak_buffer_bytes);self.read+=m.bytes_read;self.written+=m.bytes_written;self.passes+=1;p.end_work();Ok(out)
 }
 fn exponential(&mut self,input:&Path,w:u32,h:u32,decay:f32,stage:&'static str,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<PathBuf>{
   if self.optimizations.fused_exponential_io {
   cancel(c)?;let accum=self.path("exponential");let before=self.live()?;
   p.begin_work(stage);
   let m=disk_spatial::exponential_file_fused(input,&accum,w,h,decay,&self.dir,SPATIAL_MEMORY,c,&mut|v|{
     let(done,total)=if v.pass=="horizontal"{(v.completed*w as u64,2*w as u64*h as u64)}else{(w as u64*h as u64+v.completed*h as u64,2*w as u64*h as u64)};
     p.pass_fraction(stage,v.completed,v.total,done,total);
   }).map_err(|e|if disk_spatial::fused_accumulation_nonfinite(&e){error("NONFINITE_OUTPUT","Nonfinite intermediate")}else{io(e)})?;
   self.peak=self.peak.max(before+m.output_bytes+m.scratch_bytes);self.spatial_buffer=self.spatial_buffer.max(m.peak_buffer_bytes);self.read+=m.bytes_read;self.written+=m.bytes_written;self.passes+=3;
   // All three original work units completed. Keep the existing stage budget;
   // this prototype conservatively under-reports progress until fused completion.
   p.end_work();for _ in 0..2{p.begin_work(stage);p.end_work();}return Ok(accum);
   }
   let n=w as u64*h as u64;let accum=self.path("exponential");let f=new_file(&accum)?;f.set_len(n*8).map_err(io)?;drop(f);self.observe()?;
   for (amp,ratio) in [(0.1633f64,0.5360f64),(0.6496,1.5236),(0.1870,2.7684)] {
     let component=self.blur(input,w,h,(ratio*decay as f64) as f32,stage,c,p)?;
     inplace_with(&accum,&[component.as_path()],n,c,|a,src|a+amp*src[0])?;self.remove(&component)?;
   } Ok(accum)
 }
}
impl Drop for Scratch {fn drop(&mut self){if !self.gpu_may_run.as_ref().is_some_and(|state|state.load(std::sync::atomic::Ordering::Acquire)){let _=fs::remove_dir_all(&self.dir);}}}
fn inplace_with(path:&Path,others:&[&Path],n:u64,c:&mut impl FnMut()->bool,op:impl Fn(f64,&[f64])->f64)->Result<()> {
 let mut f=OpenOptions::new().read(true).write(true).open(path).map_err(io)?;let mut sources=others.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;
 let mut at=0;while at<n {cancel(c)?;let len=(n-at).min(BLOCK_PIXELS as u64) as usize;let mut a=read64(&mut f,len)?;let mut v=Vec::new();for src in &mut sources {v.push(read64(src,len)?);}let mut values=vec![0.;v.len()];for i in 0..len {for(k,s)in v.iter().enumerate(){values[k]=s[i];}a[i]=op(a[i],&values);}f.seek(SeekFrom::Start(at*8)).map_err(io)?;write64(&mut f,&a)?;at+=len as u64;}Ok(())
}
fn combine_new(path:&Path,inputs:&[&Path],n:u64,c:&mut impl FnMut()->bool,op:impl Fn(&[f64])->f64)->Result<()> {
 let mut out=new_file(path)?;let mut ins=inputs.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;let mut at=0;while at<n {cancel(c)?;let len=(n-at).min(BLOCK_PIXELS as u64) as usize;let mut v=Vec::new();for f in &mut ins {v.push(read64(f,len)?);}let mut values=vec![0.;v.len()];let mut z=vec![0.;len];for i in 0..len{for(k,s)in v.iter().enumerate(){values[k]=s[i];}z[i]=op(&values);}write64(&mut out,&z)?;at+=len as u64;}Ok(())
}
fn rows_per_chunk(width:u32)->u32 {(BLOCK_PIXELS/(width as usize)).max(1) as u32}
fn image_from_planes(v:&[Vec<f64>],w:u32,h:u32)->ImageBuf {let mut a=Vec::with_capacity(w as usize*h as usize*3);for i in 0..v[0].len(){for ch in 0..3 {a.push(from_f64(v[ch][i]));}}ImageBuf::from_data(w,h,a)}
fn write_image_planes(files:&mut [File],img:&ImageBuf)->Result<()> {for ch in 0..3 {let v:Vec<f64>=img.data.chunks_exact(3).map(|p|p[ch] as f64).collect();write64(&mut files[ch],&v)?;}Ok(())}

fn write_output_rgb_for_route(output:&mut File,rgb:&ImageBuf,exposure:f64,space:&str,route:ScienceRoute)->Result<()> {
 if route==ScienceRoute::Export{return write_output_rgb(output,rgb,exposure);}
 let mut bytes=Vec::with_capacity(rgb.data.len()*4);
 for pixel in rgb.data.chunks_exact(3){let converted=spektrafilm_core::output_color::to_linear_srgb([pixel[0]as f64,pixel[1]as f64,pixel[2]as f64],space);for v in converted{let v=(v*exposure)as f32;if !v.is_finite(){return Err(error("NONFINITE_OUTPUT","Preview conversion produced nonfinite sample"));}bytes.extend_from_slice(&v.to_le_bytes());}}
 output.write_all(&bytes).map_err(io)
}
fn write_output_rgb(output:&mut File,rgb:&ImageBuf,exposure:f64)->Result<()> {
 let mut bytes=Vec::with_capacity(rgb.data.len()*4);for &value in &rgb.data {let z=(value as f64*exposure)as f32;if !z.is_finite(){return Err(error("NONFINITE_OUTPUT","Nonfinite final output"));}bytes.extend_from_slice(&z.to_le_bytes());}output.write_all(&bytes).map_err(io)
}

fn expose(input:&Path,pipe:&Pipeline,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink,gpu:&mut GpuExposure,science:&mut GpuScience)->Result<([PathBuf;3],Option<f32>)> {
 let rows=rows_per_chunk(w);let lut=pipe.tc_lut().ok_or_else(||error("UNSUPPORTED_EXPORT_OPTIONS","Streamed exposure requires Hanatos spectral upsampling"))?;
 let exposure=from_f32(2.0f32.powf(pipe.params.camera.exposure_compensation_ev));
 let mut gpu_ae_scale=None::<f32>;
 let metered_ev=if pipe.params.camera.auto_exposure {p.begin_work("auto_exposure");p.pass("auto_exposure",0,1);let matrix=spektrafilm_core::stages::filming::input_colorspace_to_xyz(&pipe.params.io.input_color_space);let candidate={
 #[cfg(feature="diffusion-research")]{science.global(ScienceKind::AutoExposure,Some(input),None,crate::research_global::GlobalPlan::meter(w,h,&pipe.params.camera.auto_exposure_method,matrix),s,w,h,c,|done,total|p.pass("auto_exposure",done,total))?}
 #[cfg(not(feature="diffusion-research"))]{None::<ScienceCandidate>}
 };let ev=if let Some(ScienceCandidate::Meter{ev,scale})=candidate{gpu_ae_scale=Some(scale);ev}else{crate::streamed_global::auto_exposure_ev(input,w,h,&pipe.params.camera.auto_exposure_method,&matrix,&mut *c).map_err(io)?};p.pass("auto_exposure",1,1);p.end_work();Some(ev)}else{None};
 let ae=gpu_ae_scale.map(from_f32).or_else(||metered_ev.map(|ev|from_f32(2.0f32.powf(ev)))).unwrap_or(from_f64(1.));
 if !pipe.params.camera.auto_exposure {science.inactive(ScienceKind::AutoExposure);}
 if gpu.metrics.requested {p.begin_work("film_exposure");}
 let candidate=gpu.exposure_stage(input,pipe,ae as f64,exposure as f64,s,w,h,&mut *c,|done,total|p.pass("film_exposure",done,total))?;
 if gpu.metrics.requested {p.end_work();}
 if let Some(paths)=candidate {s.observe()?;p.begin_work("film_exposure");p.pass("film_exposure",w as u64*h as u64,w as u64*h as u64);p.end_work();return Ok((paths,metered_ev));}
 let paths=std::array::from_fn(|_|s.path("film-raw"));let mut out=paths.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?;let mut f=File::open(input).map_err(io)?;
 p.begin_work("film_exposure");for y in (0..h).step_by(rows as usize){cancel(c)?;let hh=rows.min(h-y);let mut bytes=vec![0u8;w as usize*hh as usize*12];f.read_exact(&mut bytes).map_err(io)?;let mut v=Vec::with_capacity(bytes.len()/4);for b in bytes.chunks_exact(4){let x=f32::from_le_bytes(b.try_into().unwrap());if !x.is_finite(){return Err(error("INVALID_PIXELS","Nonfinite input spool"));}v.push((from_f64(x as f64)*ae)*exposure);}let rgb=ImageBuf::from_data(w,hh,v);let raw=CpuBackend.hanatos2025_rgb_to_raw(&rgb,lut,&pipe.params.io.input_color_space,pipe.front_illuminant_spd(),pipe.params.settings.use_cat16);write_image_planes(&mut out,&raw)?;p.pass("film_exposure",(y+hh) as u64,h as u64);}
 drop(out);s.observe()?;p.end_work();Ok((paths,metered_ev))
}
fn halation(paths:&mut [PathBuf;3],params:&RuntimeParams,pitch:f32,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<()> {
 let v=&params.film_render.halation;if !v.active{return Ok(());}let n=w as u64*h as u64;
 for ch in 0..3 {if v.scatter_amount>0. {
   let sigma=v.scatter_core_um[ch]*v.scatter_spatial_scale/pitch as f64;let decay=v.scatter_tail_um[ch]*v.scatter_spatial_scale/pitch as f64;
   if sigma>0.||decay>0. {let core=s.blur(&paths[ch],w,h,sigma.max(1e-6) as f32,"film_scatter",c,p)?;let tail=s.exponential(&paths[ch],w,h,decay.max(1e-6) as f32,"film_scatter",c,p)?;let sa=v.scatter_amount;let wt=v.scatter_tail_weight[ch];inplace_with(&paths[ch],&[&core,&tail],n,c,|a,x|(1.-sa)*a+sa*((1.-wt)*x[0]+wt*x[1]))?;s.remove(&core)?;s.remove(&tail)?;}
 }}
 let strengths=v.halation_strength.map(|a|a*v.halation_amount);
 if v.halation_n_bounces>=1&&strengths.iter().any(|&a|a>0.) {let mut weights:Vec<f64>=(0..v.halation_n_bounces).map(|k|v.halation_bounce_decay.powi(k as i32)).collect();let sum:f64=weights.iter().sum();for x in &mut weights{*x/=sum;}
  for ch in 0..3 {let first=v.halation_first_sigma_um[ch]*v.halation_spatial_scale/pitch as f64;if strengths[ch]==0.||first<=0.{continue;}let acc=s.path("halation-sum");let f=new_file(&acc)?;f.set_len(n*8).map_err(io)?;drop(f);s.observe()?;
   for(k,&weight)in weights.iter().enumerate(){let blur=s.blur(&paths[ch],w,h,(first*((k+1)as f64).sqrt()).max(1e-6) as f32,"film_halation",c,p)?;inplace_with(&acc,&[&blur],n,c,|a,x|a+weight*x[0])?;s.remove(&blur)?;}
   inplace_with(&paths[ch],&[&acc],n,c,|a,x|a+strengths[ch]*x[0])?;s.remove(&acc)?;
  }
  if v.halation_renormalize {for ch in 0..3 {inplace_with(&paths[ch],&[],n,c,|a,_|a/(1.+strengths[ch]))?;}}
 } Ok(())
}
fn density_initial(logs:&[PathBuf;3],pipe:&Pipeline,backend:&dyn ComputeBackend,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<[PathBuf;3]> {
 let paths=std::array::from_fn(|_|s.path("film-density"));let mut out=paths.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?;let mut ins=logs.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;let axis=pipe.film.log_exposure_f64();let curves=spektrafilm_model::density_curves::normalize_density_curves_f64(&pipe.film.density_curves_f64());let rows=rows_per_chunk(w);
 p.begin_work("film_density");for y in (0..h).step_by(rows as usize){cancel(c)?;let hh=rows.min(h-y);let mut v=Vec::new();for f in &mut ins{v.push(read64(f,w as usize*hh as usize)?);}let img=image_from_planes(&v,w,hh);let z=backend.density_curve_interp(&img,&axis,&curves,pipe.params.film_render.density_curve_gamma as f64);write_image_planes(&mut out,&z)?;p.pass("film_density",(y+hh)as u64,h as u64);}
 drop(out);s.observe()?;p.end_work();Ok(paths)
}
fn dir_density(logs:[PathBuf;3],density:[PathBuf;3],pipe:&Pipeline,backend:&dyn ComputeBackend,pitch:f32,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<[PathBuf;3]> {
 let d=&pipe.params.film_render.dir_couplers;let n=w as u64*h as u64;if !d.active {for q in logs{s.remove(&q)?;}return Ok(density);}
 let mut matrix=spektrafilm_model::couplers::compute_dir_couplers_matrix(d.gamma_samelayer_rgb,d.gamma_interlayer_r_to_gb,d.gamma_interlayer_g_to_rb,d.gamma_interlayer_b_to_rg,d.inhibition_samelayer,d.inhibition_interlayer);for row in &mut matrix{for x in row{*x*=d.amount;}}
 let rawcurves=pipe.film.density_curves_f32();let norm=spektrafilm_model::density_curves::normalize_density_curves(&rawcurves);let le=pipe.film.log_exposure_f32();let curves0=spektrafilm_model::couplers::compute_curves_before_dir(&norm,&le,&matrix.map(|r|r.map(|v|v as f32)),pipe.film.is_positive());let axis:Vec<f64>=le.iter().map(|&x|x as f64).collect();let curves:Vec<[f64;3]>=curves0.iter().map(|r|r.map(|x|x as f64)).collect();let positive=pipe.film.is_positive();let dmax=spektrafilm_model::density_curves::max_density(&norm).map(|v|v as f64);let mut result:Vec<PathBuf>=Vec::new();
 for ch in 0..3 {let corr=s.path("dir-correction");combine_new(&corr,&[&density[0],&density[1],&density[2]],n,c,|x|if positive{(dmax[0]-x[0])*matrix[0][ch]+(dmax[1]-x[1])*matrix[1][ch]+(dmax[2]-x[2])*matrix[2][ch]}else{x[0]*matrix[0][ch]+x[1]*matrix[1][ch]+x[2]*matrix[2][ch]})?;s.observe()?;
  if d.diffusion_size_um>0. {let core=s.blur(&corr,w,h,(d.diffusion_size_um/pitch as f64)as f32,"dir_diffusion",c,p)?;let tail=s.exponential(&corr,w,h,(d.diffusion_tail_um/pitch as f64)as f32,"dir_diffusion",c,p)?;let wt=d.diffusion_tail_weight;inplace_with(&corr,&[&core,&tail],n,c,|_,x|(1.-wt)*x[0]+wt*x[1])?;s.remove(&core)?;s.remove(&tail)?;}
  let output=s.path("dir-density");let mut out=new_file(&output)?;let mut log=File::open(&logs[ch]).map_err(io)?;let mut correction=File::open(&corr).map_err(io)?;let rows=rows_per_chunk(w);
  p.begin_work("dir_develop");for y in(0..h).step_by(rows as usize){cancel(c)?;let hh=rows.min(h-y);let len=w as usize*hh as usize;let mut v=read64(&mut log,len)?;let correction=read64(&mut correction,len)?;for(a,b)in v.iter_mut().zip(correction){*a-=b;}let data:Vec<Scalar>=v.iter().flat_map(|&x|[from_f64(x);3]).collect();let z=backend.density_curve_interp(&ImageBuf::from_data(w,hh,data),&axis,&curves,pipe.params.film_render.density_curve_gamma as f64);let values:Vec<f64>=z.data.chunks_exact(3).map(|a|a[ch]as f64).collect();write64(&mut out,&values)?;p.pass("dir_develop",(y+hh)as u64,h as u64);}
  drop(out);s.observe()?;p.end_work();s.remove(&corr)?;s.remove(&logs[ch])?;result.push(output);
 }
 for q in density.iter(){s.remove(q)?;}Ok(result.try_into().unwrap())
}

fn grain_density(execution:Option<&crate::export_execution::ExportPermit>,mut density:[PathBuf;3],pipe:&Pipeline,pitch:f32,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<[PathBuf;3]> {
 let g=&pipe.params.film_render.grain;if !g.active||g.amount==0.{return Ok(density);}let n=w as u64*h as u64;
 if g.coordinate_origin.is_none(){
  if n>crate::mobile::MAX_PIXELS as u64{return Err(error("IMAGE_TOO_LARGE","Sequential preview grain exceeds original preview grid"));}
  cancel(c)?;p.begin_stage(5,1,"grain_sampling");p.begin_work("grain_sampling");
  let planes=density.iter().map(|path|{let mut file=File::open(path).map_err(io)?;read64(&mut file,n as usize)}).collect::<Result<Vec<_>>>()?;
  let image=image_from_planes(&planes,w,h);drop(planes);
  let output=spektrafilm_core::stages::filming::grain_density(image,&pipe.film,&pipe.params,&CpuBackend);cancel(c)?;
  let paths:[PathBuf;3]=std::array::from_fn(|_|s.path("preview-grain"));let mut files=paths.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?;write_image_planes(&mut files,&output)?;drop(files);
  for source in density{s.remove(&source)?;}s.observe()?;p.pass("grain_sampling",n,n);p.end_work();return Ok(paths);
 }
let rows=rows_per_chunk(w);let pixel_area=(pitch as f64).powi(2);let norm=spektrafilm_model::density_curves::normalize_density_curves_f64(&pipe.film.density_curves_f64());
 if norm.len()<2 {return Err(error("PROFILE_ERROR","Missing sensitometry curves"));}
 let prepared_poisson=s.optimizations.exact_grain_sampling.then(spektrafilm_math::numpy_rng::PreparedPoisson::new);
 let mut max_layers=[[0f64;3];3];let layers=&pipe.film.data.density_curves_layers;
 if g.sublayers_active {if layers.len()!=norm.len()||layers.iter().any(|r|r.len()<3||r.iter().take(3).any(|v|v.len()<3)){return Err(error("PROFILE_ERROR","Invalid grain sublayer curves"));}for row in layers {for sl in 0..3 {for ch in 0..3 {max_layers[sl][ch]=max_layers[sl][ch].max(row[sl][ch]);}}}}
 let density_max=spektrafilm_model::density_curves::max_density_f64(&norm);
 let count=if g.sublayers_active {3}else{g.n_sub_layers.max(1)as usize};
 let layer_values=|ch:usize,sl:usize| {
   let total:f64=(0..3).map(|layer|max_layers[layer][ch]).sum();
   if g.sublayers_active {let fraction=max_layers[sl][ch]/total;let dmin=fraction*g.density_min[ch];(dmin,max_layers[sl][ch]+dmin,pixel_area*fraction/(g.agx_particle_area_um2*g.agx_particle_scale[ch]*g.agx_particle_scale_layers[sl]))}
   else {(g.density_min[ch],density_max[ch]+g.density_min[ch],pixel_area/(g.agx_particle_area_um2*g.agx_particle_scale[ch])/count as f64)}
 };
 let cloud_sigma=|dmax:f64,particles:f64| (g.blur_dye_clouds_um as f64*(dmax/particles).sqrt())as f32;
 let cloud_active=|sigma:f32| g.sublayers_active&&g.blur_dye_clouds_um>0.4&&sigma>0.1;
 let micro_active=g.sublayers_active && g.micro_structure[1]as f64*0.001/pitch as f64>0.05;
 let needs_blur=if g.sublayers_active{g.blur>0.}else{g.blur>0.4};
 let mut work=3*count as u64;
 for ch in 0..3 {for sl in 0..count {let(_,dmax,particles)=layer_values(ch,sl);if cloud_active(cloud_sigma(dmax,particles)){work+=1;}}}
 work+=3*u64::from(micro_active)*(1+u64::from(g.micro_structure[0]/pitch>0.4))+3*u64::from(needs_blur);
 p.begin_stage(5,work,"grain_sampling");
 for ch in 0..3 {let accum=s.path("grain-sum");let zero=new_file(&accum)?;zero.set_len(n*8).map_err(io)?;drop(zero);s.observe()?;let sign=if pipe.film.is_positive(){-1.}else{1.};let axis:Vec<f64>=norm.iter().map(|r|sign*r[ch]).collect();let count=if g.sublayers_active {3}else{g.n_sub_layers.max(1)as usize};
  for sl in 0..count {let (dmin,dmax,particles)=layer_values(ch,sl);
   if !dmax.is_finite()||dmax<=0.||!particles.is_finite()||particles<=0.{return Err(error("PROFILE_ERROR","Invalid physical grain layer"));}
   let sampled=s.path("grain-layer");let mut out=new_file(&sampled)?;let mut input=File::open(&density[ch]).map_err(io)?;
   p.begin_work("grain_sampling");for y in (0..h).step_by(rows as usize){cancel(c)?;let hh=rows.min(h-y);let len=w as usize*hh as usize;let values=read64(&mut input,len)?;let map_layer=|original_q:f64| {let q=if g.sublayers_active{sign*original_q}else{original_q};let value=if g.sublayers_active {let last=axis.len()-1;if q<=axis[0]{layers[0][sl][ch]}else if q>=axis[last]{layers[last][sl][ch]}else{let hi=axis.partition_point(|&v|v<=q).min(last);let lo=hi-1;let dx=axis[hi]-axis[lo];let t=if dx!=0.{(q-axis[lo])/dx}else{0.};layers[lo][sl][ch]+t*(layers[hi][sl][ch]-layers[lo][sl][ch])}}else{q};from_f64(value+dmin)};
    let seed_ch=if g.monochrome {0}else{ch as u64};let seed=g.seed.wrapping_add(seed_ch+sl as u64*10);
    let sampled=if let Some(prepared)=prepared_poisson.as_ref() {
     if let Some(execution)=execution {execution.sample_prepared(prepared,&values,w,dmax,particles,g.uniformity[ch],seed,[0,y],&map_layer)}
     else {spektrafilm_model::grain::layer_particle_model_export_prepared(None,prepared,&values,w,dmax,particles,g.uniformity[ch],seed,[0,y],&map_layer)}
    } else {
     let layer=if s.optimizations.parallel_grain_interpolation {execution.map(|e|e.map_grain_layer(&values,&map_layer)).unwrap_or_else(||values.iter().copied().map(&map_layer).collect())}else{values.iter().copied().map(&map_layer).collect()};
     drop(values);
     let sampled_values=if let Some(execution)=execution {execution.sample(&layer,w,hh,dmax,particles,g.uniformity[ch],seed,Some([0,y]))}else{spektrafilm_model::grain::layer_particle_model(&layer,w,hh,dmax,particles,g.uniformity[ch],seed,0.,Some([0,y]))};
     sampled_values.iter().map(|&v|v as f64).collect()
    };write64(&mut out,&sampled)?;p.pass("grain_sampling",(y+hh)as u64,h as u64);
   } drop(out);s.observe()?;p.end_work();
   let sigma=cloud_sigma(dmax,particles);
   let contribution=if cloud_active(sigma) {let blurred=s.blur(&sampled,w,h,sigma,"grain_cloud",c,p)?;s.remove(&sampled)?;blurred}else{sampled};
   inplace_with(&accum,&[&contribution],n,c,|a,x|a+x[0])?;s.remove(&contribution)?;
  }
  if micro_active {
    let field=s.path("grain-clump");p.begin_work("grain_microstructure");crate::streamed_global::write_microstructure_field(&field,w,h,if g.monochrome {0}else{ch},g.micro_structure,pitch,g.seed,&mut *c,|done,total|p.pass("grain_microstructure",done,total)).map_err(io)?;s.observe()?;p.end_work();
    let spread=g.micro_structure[0]/pitch;let field=if spread>0.4 {let b=s.blur(&field,w,h,spread,"grain_microstructure",c,p)?;s.remove(&field)?;b}else{field};
    inplace_with(&accum,&[&field],n,c,|a,v|a*v[0])?;s.remove(&field)?;
  }
  let average=if g.sublayers_active{1.}else{1./count as f64};inplace_with(&accum,&[],n,c,|a,_|a*average-g.density_min[ch])?;
  let needs_blur=if g.sublayers_active{g.blur>0.}else{g.blur>0.4};let final_plane=if needs_blur {let blurred=s.blur(&accum,w,h,g.blur,"grain_blur",c,p)?;s.remove(&accum)?;blurred}else{accum};
  if g.amount!=1. {inplace_with(&final_plane,&[&density[ch]],n,c,|a,x|x[0]+g.amount*(a-x[0]))?;}
  s.remove(&density[ch])?;density[ch]=final_plane;s.observe()?;
 }Ok(density)
}

// Count full-image reported operations; Gaussian row/column passes form one
// operation. Ancillary combines and filesystem finalization finish before the
// final completion unit. These are work fractions, never duration estimates.
fn light_work_count(params:&RuntimeParams,pitch:f32)->u64 {
 let v=&params.film_render.halation;
 let mut count=u64::from(v.boost_ev>0.)+u64::from(params.camera.diffusion_filter.active)+3*u64::from(params.camera.lens_blur_um>0.);
 if !v.active {return count;}
 for ch in 0..3 {
  let sigma=v.scatter_core_um[ch]*v.scatter_spatial_scale/pitch as f64;
  let decay=v.scatter_tail_um[ch]*v.scatter_spatial_scale/pitch as f64;
  if v.scatter_amount>0.&&(sigma>0.||decay>0.){count+=4;}
  let strength=v.halation_strength[ch]*v.halation_amount;
  let first=v.halation_first_sigma_um[ch]*v.halation_spatial_scale/pitch as f64;
  if v.halation_n_bounces>=1&&v.halation_strength.iter().any(|a|a*v.halation_amount>0.)&&strength!=0.&&first>0.{count+=v.halation_n_bounces as u64;}
 }
 count
}

/// Native-owned scratch budget, additional to input/output spools. Kept
/// conservative until measured across all supported controls; logical file sizes.
/// Fused exponential adds four planes at once (output + three horizontals).
/// Scatter has three live channels + one core + four = eight planes. DIR is
/// the worst fused caller: three original densities + three logs/results
/// (each completed result replaces its removed log) + correction + core +
/// four fused planes = twelve f64 planes (96 bytes/pixel), below the reserved
/// sixteen (128 bytes/pixel). No fused operator is used in FFT workspaces.
pub fn estimated_scratch_bytes(width:u32,height:u32)->Result<u64> {
 (width as u64).checked_mul(height as u64).and_then(|n|n.checked_mul(128)).and_then(|n|n.checked_add(64*1024*1024)).ok_or_else(||error("INVALID_DIMENSIONS","Scratch plan overflow"))
}
/// Conservative active-diffusion reservation uses the full permitted kernel cap,
/// avoiding expensive PSF construction during UI preflight. Includes 3 source planes.
pub fn estimated_scratch_with_diffusion(width:u32,height:u32,active:bool)->Result<u64> {
 let base=estimated_scratch_bytes(width,height)?;if !active{return Ok(base);}
 let radius=((width.min(height)as usize)/2).saturating_sub(1).max(1);
 let diffusion=24*width as u64*height as u64+crate::disk_diffusion::estimated_extra_bytes(width,height,radius)+64*1024*1024;
 Ok(base.max(diffusion))
}
#[allow(clippy::too_many_arguments)]
pub fn export_file(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,cancelled:impl FnMut()->bool,progress:impl FnMut(&'static str,u64,u64))->Result<StreamedMetrics> {
 export_file_with_gpu(data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,width,height,memory_budget_mib,brightness_ev,false,cancelled,progress)
}
#[allow(clippy::too_many_arguments)]
pub fn export_file_with_gpu(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,cancelled:impl FnMut()->bool,mut progress:impl FnMut(&'static str,u64,u64))->Result<StreamedMetrics> {
 export_file_with_progress(data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,cancelled,|v|if v.pass_updated {progress(v.phase,v.done,v.total)})
}
#[allow(clippy::too_many_arguments)]
pub fn export_file_with_progress(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed(data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,None,width,height,memory_budget_mib,brightness_ev,use_gpu,cancelled,progress)
}
#[allow(clippy::too_many_arguments)]
pub fn export_file_checkpointed(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(None,data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,ExportOptimizations::default(),None,GpuStageRequests::default(),cancelled,progress)
}

pub fn export_file_checkpointed_with_optimizations(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,optimizations:ExportOptimizations,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(None,data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,optimizations,None,GpuStageRequests::default(),cancelled,progress)
}

pub(crate) fn export_file_checkpointed_managed(execution:&crate::export_execution::ExportPermit,data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(Some(execution),data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,ExportOptimizations::default(),None,GpuStageRequests::default(),cancelled,progress)
}

pub(crate) fn export_file_checkpointed_managed_with_optimizations(execution:&crate::export_execution::ExportPermit,data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,optimizations:ExportOptimizations,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(Some(execution),data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,optimizations,None,GpuStageRequests::default(),cancelled,progress)
}

pub(crate) fn export_file_checkpointed_managed_with_gpu_probe(execution:&crate::export_execution::ExportPermit,data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,optimizations:ExportOptimizations,gpu_probe_capture:Option<GpuProbeCaptureRequest>,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(Some(execution),data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,optimizations,gpu_probe_capture,GpuStageRequests::default(),cancelled,progress)
}

pub(crate) fn export_file_checkpointed_managed_with_gpu_delegate(execution:&crate::export_execution::ExportPermit,data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,optimizations:ExportOptimizations,gpu_probe_capture:Option<GpuProbeCaptureRequest>,gpu_diffusion:Option<GpuDiffusionRequest>,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(Some(execution),data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,optimizations,gpu_probe_capture,GpuStageRequests{diffusion:gpu_diffusion,..GpuStageRequests::default()},cancelled,progress)
}

pub(crate) fn export_file_checkpointed_managed_with_gpu_delegates(execution:&crate::export_execution::ExportPermit,data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,optimizations:ExportOptimizations,gpu_probe_capture:Option<GpuProbeCaptureRequest>,gpu_stages:GpuStageRequests,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(Some(execution),data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,optimizations,gpu_probe_capture,gpu_stages,cancelled,progress)
}

/// Keep CPU and opt5 identities byte-identical. Each experimental numeric
/// policy is independent: switching either requested policy requires a new job.
fn checkpoint_configuration(film_stock:&str,paper_stock:&str,params:&RuntimeParams,brightness_ev:f64,use_gpu:bool,gpu_diffusion:bool,gpu_scanner:bool,gpu_optical:bool,gpu_exposure:bool,gpu_dir:bool)->Result<String> {
 let config=if gpu_dir {
  serde_json::to_string(&(film_stock,paper_stock,params,brightness_ev,use_gpu,"gpu-film-dir-f32-whole-stage-fallback-v1",gpu_diffusion,gpu_scanner,gpu_optical,gpu_exposure))
 }else if gpu_exposure {
  serde_json::to_string(&(film_stock,paper_stock,params,brightness_ev,use_gpu,"gpu-film-exposure-f32-whole-stage-fallback-v1",gpu_diffusion,gpu_scanner,gpu_optical))
 }else if gpu_optical {
  serde_json::to_string(&(film_stock,paper_stock,params,brightness_ev,use_gpu,"gpu-film-optics-f32-whole-stage-fallback-v1",gpu_diffusion,gpu_scanner))
 }else{match (gpu_diffusion,gpu_scanner) {
  (false,false)=>serde_json::to_string(&(film_stock,paper_stock,params,brightness_ev,use_gpu)),
  (true,false)=>serde_json::to_string(&(film_stock,paper_stock,params,brightness_ev,use_gpu,"gpu-camera-f32-whole-stage-fallback-v1")),
  (false,true)=>serde_json::to_string(&(film_stock,paper_stock,params,brightness_ev,use_gpu,"gpu-print-scan-pchip-f32-whole-stage-fallback-v1")),
  (true,true)=>serde_json::to_string(&(film_stock,paper_stock,params,brightness_ev,use_gpu,"gpu-camera-f32-whole-stage-fallback-v1","gpu-print-scan-pchip-f32-whole-stage-fallback-v1")),
 }};
 config.map_err(|_|error("INVALID_PARAMETERS","Checkpoint configuration serialization failed"))
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn render_scientific_preview_file(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input:&Path,output:&Path,scratch:&Path,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,mut stages:GpuStageRequests,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics>{
 stages.route=ScienceRoute::Preview;
 export_file_checkpointed_impl(None,data_dir,film_stock,paper_stock,params,input,output,scratch,None,width,height,memory_budget_mib,brightness_ev,false,ExportOptimizations::default(),None,stages,cancelled,progress)
}

pub(crate) fn render_scientific_detail_file(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input:&Path,output:&Path,scratch:&Path,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,mut stages:GpuStageRequests,cancelled:impl FnMut()->bool)->Result<StreamedMetrics>{
 if width==0||height==0||width>1536||height>1536{return Err(error("INVALID_DIMENSIONS","Detail working grid must remain within 1536 pixels per side"));}
 stages.route=ScienceRoute::Detail;
 export_file_checkpointed_impl(None,data_dir,film_stock,paper_stock,params,input,output,scratch,None,width,height,memory_budget_mib,brightness_ev,false,ExportOptimizations::default(),None,stages,cancelled,|_|{})
}

fn export_file_checkpointed_impl(execution:Option<&crate::export_execution::ExportPermit>,data_dir:&Path,film_stock:&str,paper_stock:&str,mut params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,optimizations:ExportOptimizations,gpu_probe_capture:Option<GpuProbeCaptureRequest>,gpu_stages:GpuStageRequests,mut cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 let GpuStageRequests{diffusion:gpu_diffusion,scanner:gpu_scanner,optical:gpu_optical,exposure:gpu_exposure,dir:gpu_dir,science:science_requests,route:science_route}=gpu_stages;
 let retained_handle=gpu_diffusion.as_ref().or(gpu_scanner.as_ref()).or(gpu_optical.as_ref()).or(gpu_exposure.as_ref()).or(gpu_dir.as_ref()).or_else(||science_requests.values().next()).map_or(0,|r|r.parent_handle);
 // Isolated numeric stages never overlap the old in-process density delegate,
 // including direct native callers that bypass the app's JSON normalization.
 let isolated=!science_requests.is_empty()||gpu_diffusion.is_some()||gpu_scanner.is_some()||gpu_optical.is_some()||gpu_exposure.is_some()||gpu_dir.is_some();
 let use_gpu=use_gpu&&!isolated;let mut optimizations=optimizations;if isolated{optimizations.gpu_resource_reuse=false;}
 let mut progress=Reporter::new(progress);
 let start=Instant::now();if !cfg!(feature="precision-f64"){return Err(error("UNSUPPORTED_PRECISION","Streamed physical export requires f64 internal precision"));}
 if width==0||height==0||width>40000||height>40000{return Err(error("INVALID_DIMENSIONS","Dimensions must be1..40000"));}
 if !(128..=512).contains(&memory_budget_mib)||!brightness_ev.is_finite()||!(-2.0..=2.0).contains(&brightness_ev){return Err(error("INVALID_BUDGET","Invalid memory budget or brightness"));}
 if input_path==output_path||fs::symlink_metadata(output_path).is_ok()||fs::symlink_metadata(scratch_dir).is_ok(){return Err(error("INVALID_PATH","Output and owned scratch must be new and distinct"));}
 let n=width as u64*height as u64;if science_route==ScienceRoute::Preview&&n>crate::mobile::MAX_PIXELS as u64{return Err(error("IMAGE_TOO_LARGE","Preview scientific grid exceeds original limit"));}if fs::metadata(input_path).map_err(io)?.len()!=n*12{return Err(error("INVALID_INPUT_FILE","Input length must equal width*height*12"));}
 if params.io.crop||params.io.upscale_factor!=1.||params.io.input_cctf_decoding||params.io.output_cctf_encoding||params.settings.rgb_to_raw_method!="hanatos2025" {return Err(error("UNSUPPORTED_EXPORT_OPTIONS","Unsupported scientific option; no effect silently ignored"));}
 for d in [&params.camera.diffusion_filter,&params.enlarger.diffusion_filter] {
   if d.active && (!["glimmerglass","black_pro_mist","pro_mist","cinebloom"].contains(&d.filter_family.as_str()) || !d.strength.is_finite() || !(0.0..=2.0).contains(&d.strength) || !d.spatial_scale.is_finite() || !(0.25..=2.0).contains(&d.spatial_scale)) {return Err(error("INVALID_PARAMETERS","Unsupported diffusion controls"));}
 }
 let [sharp_radius,sharp_amount]=params.scanner.unsharp_mask;
 if !params.camera.lens_blur_um.is_finite() || !(0.0..=100.0).contains(&params.camera.lens_blur_um) || !params.scanner.lens_blur.is_finite() || !(0.0..=3.0).contains(&params.scanner.lens_blur){return Err(error("INVALID_PARAMETERS","Blur range exceeded"));}
 if !sharp_radius.is_finite()||!sharp_amount.is_finite()||sharp_radius<0.||sharp_radius>3.||sharp_amount<0.||sharp_amount>2. {return Err(error("INVALID_PARAMETERS","Sharpening range exceeded"));}
 let pitch=params.camera.pixel_size_um_override.unwrap_or(params.camera.film_format_mm*1000./width.max(height)as f32);if !pitch.is_finite()||pitch<=0.{return Err(error("INVALID_PARAMETERS","Invalid full-image physical pitch"));}

 params.camera.pixel_size_um_override=Some(pitch);params.film_render.grain.coordinate_origin=if science_route==ScienceRoute::Preview{None}else{Some([0,0])};params.settings.use_enlarger_lut=true;params.settings.use_scanner_lut=true;params.settings.lut_resolution=33;
 cancel(&mut cancelled)?;progress.pass("setup",0,1);let film=load_profile_by_name(data_dir,film_stock).map_err(|e|error("PROFILE_ERROR",e.to_string()))?;let paper=load_profile_by_name(data_dir,paper_stock).map_err(|e|error("PROFILE_ERROR",e.to_string()))?;
 if (!film.is_negative()&&!film.is_positive())||film.is_positive()&&!params.io.scan_film{return Err(error("UNSUPPORTED_PROFILE","Unsupported film or reversal print route"));}
 let checkpoint_at=Instant::now();let mut checkpoint_commit_ms=0.;
 let mut checkpoints=if let Some(root)=checkpoint_dir {
   let config=checkpoint_configuration(film_stock,paper_stock,&params,brightness_ev,use_gpu,gpu_diffusion.is_some(),gpu_scanner.is_some(),gpu_optical.is_some(),gpu_exposure.is_some(),gpu_dir.is_some())?;
   let config=if science_requests.is_empty(){config}else{serde_json::to_string(&(config,"gpu-complete-science-guarded64-rng-resident-finish-v2",science_route,science_requests.keys().collect::<Vec<_>>())).map_err(|_|error("INVALID_PARAMETERS","Science identity serialization failed"))?};
   let identity=crate::checkpoint::Identity::new_with_hash(data_dir,input_path,width,height,config,optimizations.accelerated_checkpoint_sha,&mut cancelled).map_err(checkpoint_error)?;
   Some(crate::checkpoint::Checkpoints::open_with_hash(root,identity,optimizations.accelerated_checkpoint_sha,&mut cancelled).map_err(checkpoint_error)?)
 }else{None};
 let checkpoint_identity_ms=checkpoint_at.elapsed().as_secs_f64()*1000.;
 let resume_stage=checkpoints.as_ref().map_or(0,|c|c.stage());
 let science_backends=checkpoints.as_ref().map(|c|c.science_backends().clone()).unwrap_or_default();
 for kind in ScienceKind::ALL {if (science_requests.contains_key(&kind)&&resume_stage>=kind.checkpoint_stage()&&!science_backends.contains_key(&kind))||(!science_requests.contains_key(&kind)&&science_backends.get(&kind)==Some(&true)){return Err(error("CHECKPOINT_INVALID","Scientific GPU checkpoint policy differs"));}}
 let mut gpu_science=GpuScience::new(science_requests,resume_stage,&science_backends);gpu_science.route=science_route;

 let backend=checkpoints.as_ref().map_or(crate::checkpoint::CameraDiffusionBackend::CpuF64,|c|c.camera_diffusion_backend());
 if (gpu_diffusion.is_some()&&resume_stage>=3&&checkpoints.as_ref().is_some_and(|c|c.camera_diffusion_backend_metadata().is_none()))||(gpu_diffusion.is_none()&&backend!=crate::checkpoint::CameraDiffusionBackend::CpuF64) {return Err(error("CHECKPOINT_INVALID","Checkpoint execution policy differs"));}
 let scanner_backend=checkpoints.as_ref().map_or(crate::checkpoint::PrintingScanningBackend::CpuF64,|c|c.printing_scanning_backend());
 if (gpu_scanner.is_some()&&resume_stage>=6&&checkpoints.as_ref().is_some_and(|c|c.printing_scanning_backend_metadata().is_none()))||(gpu_scanner.is_none()&&scanner_backend!=crate::checkpoint::PrintingScanningBackend::CpuF64) {return Err(error("CHECKPOINT_INVALID","Checkpoint scanner execution policy differs"));}
 let mut gpu_scanner=GpuScanner::new(gpu_scanner,resume_stage,scanner_backend);
 let optical_backend=checkpoints.as_ref().map_or(crate::checkpoint::FilmOpticalBackend::CpuF64,|c|c.film_optical_backend());
 if (gpu_optical.is_some()&&resume_stage>=3&&checkpoints.as_ref().is_some_and(|c|c.film_optical_backend_metadata().is_none()))||(gpu_optical.is_none()&&optical_backend!=crate::checkpoint::FilmOpticalBackend::CpuF64) {return Err(error("CHECKPOINT_INVALID","Checkpoint optical execution policy differs"));}
 let mut gpu_optical=GpuOptical::new(gpu_optical,resume_stage,optical_backend);
 let exposure_backend=checkpoints.as_ref().map_or(crate::checkpoint::FilmExposureBackend::CpuF64,|c|c.film_exposure_backend());
 if (gpu_exposure.is_some()&&resume_stage>=2&&checkpoints.as_ref().is_some_and(|c|c.film_exposure_backend_metadata().is_none()))||(gpu_exposure.is_none()&&exposure_backend!=crate::checkpoint::FilmExposureBackend::CpuF64) {return Err(error("CHECKPOINT_INVALID","Checkpoint exposure execution policy differs"));}
 let mut gpu_exposure=GpuExposure::new(gpu_exposure,resume_stage,exposure_backend);
 let dir_backend=checkpoints.as_ref().map_or(crate::checkpoint::FilmDirBackend::CpuF64,|c|c.film_dir_backend());
 if (gpu_dir.is_some()&&resume_stage>=4&&checkpoints.as_ref().is_some_and(|c|c.film_dir_backend_metadata().is_none()))||(gpu_dir.is_none()&&dir_backend!=crate::checkpoint::FilmDirBackend::CpuF64) {return Err(error("CHECKPOINT_INVALID","Checkpoint DIR execution policy differs"));}
 let mut gpu_dir=GpuDir::new(gpu_dir,resume_stage,dir_backend);
 let gpu_probe_capture=if gpu_diffusion.is_some(){None}else{gpu_probe_capture};
 let mut gpu_diffusion=GpuDiffusion::new(gpu_diffusion,resume_stage,backend);
 let mut gpu_probe_capture=GpuProbeCapture::new(gpu_probe_capture,resume_stage,params.camera.diffusion_filter.active);
 let pipe=Pipeline::new_with_spectral(film,paper,params,data_dir).map_err(|e|error("PIPELINE_ERROR",e))?;let prepared=pipe.prepare_streamed_finish_spatial(&CpuBackend).map_err(|e|error("PIPELINE_ERROR",e))?;let setup_ms=start.elapsed().as_secs_f64()*1000.;let backend=crate::adreno::AdrenoDensityBackend::with_options(use_gpu,optimizations.gpu_resource_reuse);cancel(&mut cancelled)?;let mut scratch=Scratch::new(scratch_dir)?;crate::gpu_retained::register(retained_handle,scratch_dir)?;scratch.optimizations=optimizations;let mut stages=Vec::new();
 let mut output_guard=OutputGuard{path:output_path.to_path_buf(),keep:false};
 let rows=rows_per_chunk(width);
 let print_diffusion= !pipe.params.io.scan_film && pipe.params.enlarger.diffusion_filter.active;
 let mut metered_ev=checkpoints.as_ref().and_then(|c|c.metered_ev());
 if resume_stage<6 {
 let mut logs:[PathBuf;3]=std::array::from_fn(|_|scratch.path("restored"));
 if resume_stage==2||resume_stage==3 {checkpoints.as_ref().unwrap().restore(&logs,&mut cancelled).map_err(checkpoint_error)?;scratch.observe()?;}
 if resume_stage<2 {
 progress.begin_stage(2,1+u64::from(pipe.params.camera.auto_exposure)+u64::from(gpu_exposure.metrics.requested),"film_exposure");
 let at=Instant::now();let exposed=expose(input_path,&pipe,width,height,&mut scratch,&mut cancelled,&mut progress,&mut gpu_exposure,&mut gpu_science)?;logs=exposed.0;metered_ev=exposed.1;stages.push(StageMetrics{stage:"film_exposure",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 if let Some(c)=checkpoints.as_mut(){
  if gpu_exposure.metrics.requested {c.set_film_exposure_backend(if gpu_exposure.metrics.backend=="gpu_exposure_f32_v1"{crate::checkpoint::FilmExposureBackend::GpuExposureF32V1}else{crate::checkpoint::FilmExposureBackend::CpuF64}).map_err(checkpoint_error)?;}
  gpu_science.record_checkpoint(c,2).map_err(checkpoint_error)?;let at=Instant::now();c.commit(2,&logs,metered_ev,&mut cancelled).map_err(checkpoint_error)?;checkpoint_commit_ms+=at.elapsed().as_secs_f64()*1000.;}
 progress.finish_stage();
 }
 if resume_stage<3 {
 let optical_work=light_work_count(&pipe.params,pitch)-u64::from(pipe.params.film_render.halation.boost_ev>0.)-u64::from(pipe.params.camera.diffusion_filter.active);
 let light_work=light_work_count(&pipe.params,pitch)+u64::from(gpu_optical.metrics.requested);
 if light_work>0 {progress.begin_stage(3,light_work,"film_scatter_halation");}
 if pipe.params.film_render.halation.boost_ev>0. {let at=Instant::now();let v=&pipe.params.film_render.halation;progress.begin_work("highlight_boost");let candidate={
 #[cfg(feature="diffusion-research")]{gpu_science.global(ScienceKind::HighlightBoost,None,Some(&logs),crate::research_global::GlobalPlan::highlight(width,height,v.boost_ev,v.boost_range,v.protect_ev),&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("highlight_boost",done,total))?}
 #[cfg(not(feature="diffusion-research"))]{None::<ScienceCandidate>}
 };if let Some(ScienceCandidate::Files(paths))=candidate {let paths:[PathBuf;3]=paths.try_into().map_err(|_|error("INTERNAL_ERROR","Invalid highlight candidate"))?;for old in std::mem::replace(&mut logs,paths){scratch.remove(&old)?;}}else{crate::streamed_global::boost_planes(&logs,n,v.boost_ev as f64,v.boost_range as f64,v.protect_ev as f64,&mut cancelled,|done,total|progress.pass("highlight_boost",done,total)).map_err(io)?;}progress.end_work();stages.push(StageMetrics{stage:"highlight_boost",elapsed_ms:at.elapsed().as_secs_f64()*1000.});}
 if pipe.params.film_render.halation.boost_ev<=0. {gpu_science.inactive(ScienceKind::HighlightBoost);}
 let at_diffusion=Instant::now();
 if pipe.params.camera.diffusion_filter.active {
   let d=&pipe.params.camera.diffusion_filter;
   if !["glimmerglass","black_pro_mist","pro_mist","cinebloom"].contains(&d.filter_family.as_str()) || !d.strength.is_finite() || !(0.0..=2.0).contains(&d.strength) || !d.spatial_scale.is_finite() || !(0.25..=2.0).contains(&d.spatial_scale) {return Err(error("INVALID_PARAMETERS","Unsupported diffusion controls"));}
   progress.begin_work("camera_diffusion");progress.pass("camera_diffusion",0,n*3);
   {
     // The kernel and each disk FFT can take a long time before completing one
     // output tile. Emit a bounded-frequency execution heartbeat at existing
     // cancellation checkpoints, without crediting any unfinished pixels.
     let reporter=std::cell::RefCell::new(&mut progress);
     let completed=std::cell::Cell::new(0u64);
     let mut heartbeat=DiffusionHeartbeat::new(Duration::from_secs(1));
     let mut checkpoint=|| {
       if heartbeat.due(Instant::now()) {reporter.borrow_mut().pass("camera_diffusion",completed.get(),n*3);}
       cancelled()
     };
     if let Some(kernel)=spektrafilm_model::diffusion::ExactDiffusionKernel::new_cancellable_with_prepared(&d.to_model(),pitch as f64,width as usize,height as usize,optimizations.prepared_diffusion_kernel,&mut checkpoint).map_err(|_|error("CANCELLED","Diffusion kernel cancelled"))? {
       if let Some(outputs)=gpu_diffusion.camera_stage(&logs,&mut scratch,width,height,pitch as f64,&d.filter_family,&kernel,&mut checkpoint,|done,total|{completed.set(done);reporter.borrow_mut().pass("camera_diffusion",done,total)})? {
         cancel(&mut checkpoint)?;let originals=std::mem::replace(&mut logs,outputs);
         // All three files are complete and the helper is dead. No downstream
         // consumer can observe a partially adopted channel set.
         for source in &originals {scratch.remove(source)?;}
       } else {
       for ch in 0..3 {
         cancel(&mut checkpoint)?;let transformed=scratch.path("camera-diffusion");let work=scratch.path("diffusion-work");let before=scratch.live()?;
         let m=crate::disk_diffusion::apply_plane_with_options(&logs[ch],&transformed,&work,width,height,&kernel,ch,optimizations.fft_transpose_256,optimizations.fused_diffusion_fft,&mut checkpoint,|done,total|{let done=ch as u64*n+done;completed.set(done);reporter.borrow_mut().pass("camera_diffusion",done,total*3)}).map_err(io)?;
         // Capture the original green plane and completed CPU reference before
         // deleting either source or allowing any downstream stage to mutate it.
         if ch==1 {gpu_probe_capture.capture(&logs[ch],&transformed,width,height,pitch as f64,&d.filter_family,&kernel,&mut checkpoint);}
         scratch.peak=scratch.peak.max(before+m.peak_scratch_bytes);scratch.spatial_buffer=scratch.spatial_buffer.max(m.explicit_buffer_bytes);scratch.passes+=m.tiles;scratch.remove(&logs[ch])?;logs[ch]=transformed;
       }
       }
     }
   }
   progress.end_work();stages.push(StageMetrics{stage:"camera_diffusion",elapsed_ms:(at_diffusion.elapsed().as_secs_f64()*1000.-gpu_probe_capture.metrics.capture_ms).max(0.)});
 }
 let optical_at=Instant::now();
 if gpu_optical.metrics.requested {progress.begin_work("film_scatter_halation");}
 let optical_outputs=gpu_optical.optical_stage(&logs,&pipe.params,pitch,&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("film_scatter_halation",done,total))?;
 if gpu_optical.metrics.requested {progress.end_work();}
 if let Some(outputs)=optical_outputs {
  cancel(&mut cancelled)?;let originals=std::mem::replace(&mut logs,outputs);for source in &originals{scratch.remove(source)?;}
  // The successful helper has actually completed each modeled optical pass.
  for _ in 0..optical_work {progress.begin_work("film_scatter_halation");progress.end_work();}
  stages.push(StageMetrics{stage:"film_scatter_halation",elapsed_ms:optical_at.elapsed().as_secs_f64()*1000.});
 }else{
 if pipe.params.camera.lens_blur_um>0. {
   let at=Instant::now();for ch in 0..3 {let b=scratch.blur(&logs[ch],width,height,pipe.params.camera.lens_blur_um/pitch,"camera_blur",&mut cancelled,&mut progress)?;scratch.remove(&logs[ch])?;logs[ch]=b;}stages.push(StageMetrics{stage:"camera_blur",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 }
 let at=Instant::now();halation(&mut logs,&pipe.params,pitch,width,height,&mut scratch,&mut cancelled,&mut progress)?;stages.push(StageMetrics{stage:"film_scatter_halation",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 }
 if let Some(c)=checkpoints.as_mut(){
  if gpu_optical.metrics.requested {c.set_film_optical_backend(if gpu_optical.metrics.backend=="gpu_spatial_f32_v1"{crate::checkpoint::FilmOpticalBackend::GpuSpatialF32V1}else{crate::checkpoint::FilmOpticalBackend::CpuF64}).map_err(checkpoint_error)?;}
  if gpu_diffusion.metrics.requested {c.set_camera_diffusion_backend(if gpu_diffusion.metrics.backend=="gpu_f32_v1"{crate::checkpoint::CameraDiffusionBackend::GpuF32V1}else{crate::checkpoint::CameraDiffusionBackend::CpuF64}).map_err(checkpoint_error)?;}
  gpu_science.record_checkpoint(c,3).map_err(checkpoint_error)?;let at=Instant::now();c.commit(3,&logs,metered_ev,&mut cancelled).map_err(checkpoint_error)?;checkpoint_commit_ms+=at.elapsed().as_secs_f64()*1000.;}
 if light_work>0 {progress.finish_stage();}
 }
 let density=if resume_stage>=4 {let paths=std::array::from_fn(|_|scratch.path("restored-density"));checkpoints.as_ref().unwrap().restore(&paths,&mut cancelled).map_err(checkpoint_error)?;scratch.observe()?;paths}else{
 let density_work=1+if pipe.params.film_render.dir_couplers.active {3+if pipe.params.film_render.dir_couplers.diffusion_size_um>0. {12}else{0}}else{0};
 progress.begin_stage(4,1+density_work+u64::from(gpu_dir.metrics.requested),"film_log");
 progress.begin_work("film_log");
 let log_candidate={
 #[cfg(feature="diffusion-research")]{gpu_science.global(ScienceKind::ExposureLog,None,Some(&logs),crate::research_global::GlobalPlan::log_exposure(width,height,prepared.filming_exposure_correction),&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("film_log",done,total))?}
 #[cfg(not(feature="diffusion-research"))]{None::<ScienceCandidate>}
 };
 if let Some(ScienceCandidate::Files(paths))=log_candidate{let paths:[PathBuf;3]=paths.try_into().map_err(|_|error("INTERNAL_ERROR","Invalid logarithm candidate"))?;for old in std::mem::replace(&mut logs,paths){scratch.remove(&old)?;}}else{for q in &logs {inplace_with(q,&[],n,&mut cancelled,|a,_|((a*prepared.filming_exposure_correction).max(0.)+1e-10).log10())?;}}
 progress.pass("film_log",1,1);progress.end_work();
 let at=Instant::now();
 if gpu_dir.metrics.requested {progress.begin_work("film_density_dir");}
 let candidate=gpu_dir.density_stage(&logs,&pipe,pitch,&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("film_density_dir",done,total))?;
 if gpu_dir.metrics.requested {progress.end_work();}
 let density=if let Some(density)=candidate {
  for path in &logs{scratch.remove(path)?;}
  for _ in 0..density_work {progress.begin_work("film_density_dir");progress.end_work();}density
 }else{let density=density_initial(&logs,&pipe,&backend,width,height,&mut scratch,&mut cancelled,&mut progress)?;dir_density(logs,density,&pipe,&backend,pitch,width,height,&mut scratch,&mut cancelled,&mut progress)?};
 stages.push(StageMetrics{stage:"film_density_dir",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 if let Some(c)=checkpoints.as_mut(){
  if gpu_dir.metrics.requested {c.set_film_dir_backend(if gpu_dir.metrics.backend=="gpu_dir_f32_v1"{crate::checkpoint::FilmDirBackend::GpuDirF32V1}else{crate::checkpoint::FilmDirBackend::CpuF64}).map_err(checkpoint_error)?;}
  gpu_science.record_checkpoint(c,4).map_err(checkpoint_error)?;let at=Instant::now();c.commit(4,&density,metered_ev,&mut cancelled).map_err(checkpoint_error)?;checkpoint_commit_ms+=at.elapsed().as_secs_f64()*1000.;}
 progress.finish_stage();density
 };
 // Stage visualisation from the actual developed film-density planes, never
 // a cosmetic inversion of the original. Preview failure must not invalidate
 // an otherwise durable scientific export; cancellation still propagates.
 if let Some(job_dir)=checkpoint_dir.and_then(|p|p.parent()) {
   if let Err(e)=crate::negative_preview::write_negative_preview(&density,&pipe.film,width,height,job_dir,&mut cancelled) {if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}
 }
 let density=if resume_stage<5 {
 let at=Instant::now();
 let grain_active=pipe.params.film_render.grain.active&&pipe.params.film_render.grain.amount!=0.;
 if gpu_science.requested(ScienceKind::Grain)&&grain_active{progress.begin_stage(5,1,"grain_gpu");progress.begin_work("grain_gpu");}
 let candidate=if grain_active{
 #[cfg(feature="diffusion-research")]{gpu_science.grain(&density,&pipe,pitch,&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("grain_gpu",done,total))?}
 #[cfg(not(feature="diffusion-research"))]{None::<[PathBuf;3]>}
 }else{gpu_science.inactive(ScienceKind::Grain);None};
 if gpu_science.requested(ScienceKind::Grain)&&grain_active{progress.end_work();}
 let density=if let Some(output)=candidate{for source in density{scratch.remove(&source)?;}output}else{grain_density(execution,density,&pipe,pitch,width,height,&mut scratch,&mut cancelled,&mut progress)?};stages.push(StageMetrics{stage:"grain",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 if let Some(c)=checkpoints.as_mut(){gpu_science.record_checkpoint(c,5).map_err(checkpoint_error)?;let at=Instant::now();c.commit(5,&density,metered_ev,&mut cancelled).map_err(checkpoint_error)?;checkpoint_commit_ms+=at.elapsed().as_secs_f64()*1000.;}
 if pipe.params.film_render.grain.active&&pipe.params.film_render.grain.amount!=0. {progress.finish_stage();}density
 }else{density};
 let glare=if pipe.params.io.scan_film{&pipe.params.film_render.glare}else{&pipe.params.print_render.glare};
 let has_glare=glare.active&&glare.percent>0.;
 let sharpening=(sharp_radius>0. && sharp_amount>0.) || pipe.params.scanner.lens_blur>0.;
 let finish_work=1+u64::from(gpu_scanner.metrics.requested)+2*u64::from(print_diffusion)+u64::from(has_glare)*(1+u64::from(glare.blur>0.))+
   if sharpening {1+3*u64::from(pipe.params.scanner.lens_blur>0.)+3*u64::from(sharp_radius>0.&&sharp_amount>0.)}else{0};
 progress.begin_stage(6,finish_work,"printing_scanning");
 let density=if print_diffusion {
   let at=Instant::now();let raw:[PathBuf;3]=std::array::from_fn(|_|scratch.path("print-raw"));
   let mut out=raw.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?;
   let rows=rows_per_chunk(width);
   progress.begin_work("printing_scanning");
   let raw_candidate={
    #[cfg(feature="diffusion-research")]{gpu_science.print_exposure(&density,&prepared,&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("printing_scanning",done,total))?}
    #[cfg(not(feature="diffusion-research"))]{None::<PathBuf>}
   };
   if let Some(path)=raw_candidate {
    let mut input=File::open(&path).map_err(io)?;
    for y in (0..height).step_by(rows as usize){cancel(&mut cancelled)?;let hh=rows.min(height-y);let data=read64(&mut input,width as usize*hh as usize*3)?.into_iter().map(from_f64).collect();write_image_planes(&mut out,&ImageBuf::from_data(width,hh,data))?;}
    drop(input);scratch.remove(&path)?;
   }else{
    let mut inputs=density.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;
    for y in (0..height).step_by(rows as usize) {cancel(&mut cancelled)?;let hh=rows.min(height-y);let mut planes=Vec::new();for f in &mut inputs{planes.push(read64(f,width as usize*hh as usize)?);}
     let exposed=prepared.expose_print_raw_chunk(&image_from_planes(&planes,width,hh)).map_err(|e|error("PIPELINE_ERROR",e))?;write_image_planes(&mut out,&exposed)?;progress.pass("printing_scanning",(y+hh)as u64,height as u64);
    }
   }
   drop(out);scratch.observe()?;progress.end_work();for p in &density{scratch.remove(p)?;}
   let mut raw=raw;let d=&pipe.params.enlarger.diffusion_filter;let print_pitch=pipe.params.camera.film_format_mm*1000./width.max(height)as f32;
   progress.begin_work("print_diffusion");if let Some(kernel)=spektrafilm_model::diffusion::ExactDiffusionKernel::new_cancellable_with_prepared(&d.to_model(),print_pitch as f64,width as usize,height as usize,optimizations.prepared_diffusion_kernel,&mut cancelled).map_err(|_|error("CANCELLED","Print diffusion cancelled"))? {
     let candidate={
      #[cfg(feature="diffusion-research")]{gpu_science.print_diffusion(&raw,print_pitch as f64,&d.filter_family,&kernel,&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("print_diffusion",done,total))?}
      #[cfg(not(feature="diffusion-research"))]{None::<[PathBuf;3]>}
     };
     if let Some(paths)=candidate{for source in std::mem::replace(&mut raw,paths){scratch.remove(&source)?;}}else{
     for ch in 0..3 {cancel(&mut cancelled)?;let transformed=scratch.path("print-diffusion");let work=scratch.path("print-diffusion-work");let before=scratch.live()?;
       let m=crate::disk_diffusion::apply_plane_with_options(&raw[ch],&transformed,&work,width,height,&kernel,ch,optimizations.fft_transpose_256,optimizations.fused_diffusion_fft,&mut cancelled,|done,total|progress.pass("print_diffusion",ch as u64*n+done,total*3)).map_err(io)?;
       scratch.peak=scratch.peak.max(before+m.peak_scratch_bytes);scratch.spatial_buffer=scratch.spatial_buffer.max(m.explicit_buffer_bytes);scratch.passes+=m.tiles;scratch.remove(&raw[ch])?;raw[ch]=transformed;
     }
     }
   }
   progress.end_work();stages.push(StageMetrics{stage:"print_diffusion",elapsed_ms:at.elapsed().as_secs_f64()*1000.});raw
 }else{gpu_science.inactive(ScienceKind::PrintExposure);gpu_science.inactive(ScienceKind::PrintDiffusion);density};
 let resident_at=Instant::now();
 let resident_requested=gpu_scanner.metrics.requested&&gpu_science.requested(ScienceKind::FinishTail);
 let resident_candidate=if resident_requested {
  #[cfg(feature="diffusion-research")]{gpu_science.finish_tail(&density,&prepared,&pipe.params,print_diffusion,brightness_ev,&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("printing_scanning",done,total))?}
  #[cfg(not(feature="diffusion-research"))]{None::<PathBuf>}
 }else{gpu_science.inactive(ScienceKind::FinishTail);None};
 if let Some(candidate)=resident_candidate {
  cancel(&mut cancelled)?;if fs::metadata(&candidate).map_err(io)?.len()!=n*12{return Err(error("INVALID_OUTPUT_SHAPE","Resident finish size changed"));}
  fs::rename(&candidate,output_path).map_err(io)?;
  if science_route==ScienceRoute::Export {if let Err(e)=crate::scan_preview::publish_restored(output_path,width,height,&pipe.params.io.output_color_space,&mut cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}}
  for q in &density{scratch.remove(q)?;}
  gpu_scanner.metrics.backend="gpu_pchip_f32_v1";gpu_scanner.metrics.attempted=true;gpu_scanner.metrics.fallback_reason="included_in_resident";
  if let Some(m)=gpu_science.metrics.get_mut(&ScienceKind::ScanTail){m.backend="gpu_scan_tail_f32_v1";m.attempted=true;m.fallback_reason="included_in_resident";}
  progress.begin_work("printing_scanning");progress.pass("printing_scanning",n,n);progress.end_work();
  stages.push(StageMetrics{stage:"printing_scanning",elapsed_ms:resident_at.elapsed().as_secs_f64()*1000.});
 }else{
  // One failed combined candidate falls back atomically to the original CPU
  // printing/scanner suffix, never a partially completed GPU prefix.
  if resident_requested {gpu_scanner.request=None;gpu_scanner.metrics.fallback_reason="resident_fallback";}
 let scanner_at=Instant::now();
 if gpu_scanner.metrics.requested {progress.begin_work("printing_scanning");}
 let scanner_candidate=gpu_scanner.printing_stage(&density,&prepared,print_diffusion,&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("printing_scanning",done,total))?;
 if gpu_scanner.metrics.requested {progress.end_work();}
 let scanner_elapsed_ms=scanner_at.elapsed().as_secs_f64()*1000.;
 let tail_started=Instant::now();
 let tail_candidate=if let Some(source)=scanner_candidate.as_ref(){
  #[cfg(feature="diffusion-research")]{gpu_science.tail(source,&prepared,&pipe.params,brightness_ev,&mut scratch,width,height,&mut cancelled,|done,total|progress.pass("printing_scanning",done,total))?}
  #[cfg(not(feature="diffusion-research"))]{None::<PathBuf>}
 }else{if gpu_science.requested(ScienceKind::ScanTail){gpu_science.metrics.get_mut(&ScienceKind::ScanTail).unwrap().fallback_reason="upstream_cpu";}None};
 if let Some(candidate)=tail_candidate {
  cancel(&mut cancelled)?;
  if fs::metadata(&candidate).map_err(io)?.len()!=n*12{return Err(error("INVALID_OUTPUT_SHAPE","GPU scan tail size changed"));}
  fs::rename(&candidate,output_path).map_err(io)?;
  if science_route==ScienceRoute::Export {if let Err(e)=crate::scan_preview::publish_restored(output_path,width,height,&pipe.params.io.output_color_space,&mut cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}}
  if let Some(q)=&scanner_candidate{scratch.remove(q)?;}for q in &density{scratch.remove(q)?;}
  progress.begin_work("printing_scanning");progress.pass("printing_scanning",n,n);progress.end_work();
  stages.push(StageMetrics{stage:"printing_scanning",elapsed_ms:scanner_elapsed_ms+tail_started.elapsed().as_secs_f64()*1000.});
 }else{
 let mut scanner_input=scanner_candidate.as_ref().map(|p|File::open(p).map_err(io)).transpose()?;
 let glare=if pipe.params.io.scan_film{&pipe.params.film_render.glare}else{&pipe.params.print_render.glare};
 let glare_path=if glare.active&&glare.percent>0. {
   let at=Instant::now();let raw=scratch.path("scan-glare");progress.begin_work("scan_glare");crate::streamed_global::write_glare_field(&raw,width,height,glare.percent,glare.roughness,42,&mut cancelled,|done,total|progress.pass("scan_glare",done,total)).map_err(io)?;scratch.observe()?;progress.end_work();
   let field=if glare.blur>0. {let b=scratch.blur(&raw,width,height,glare.blur,"scan_glare",&mut cancelled,&mut progress)?;scratch.remove(&raw)?;b}else{raw};inplace_with(&field,&[],n,&mut cancelled,|a,_|a*0.01)?;stages.push(StageMetrics{stage:"scan_glare",elapsed_ms:at.elapsed().as_secs_f64()*1000.});Some(field)
 }else{None};
 let mut glare_input=glare_path.as_ref().map(|p|File::open(p).map_err(io)).transpose()?;
 let at=Instant::now();cancel(&mut cancelled)?;let mut output=new_file(output_path)?;let mut files=if scanner_input.is_none(){density.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?}else{Vec::new()};let rows=rows_per_chunk(width);let exposure=2f64.powf(brightness_ev);
 let sharpening=(sharp_radius>0. && sharp_amount>0.) || pipe.params.scanner.lens_blur>0.;
 let scan_paths:[PathBuf;3]=std::array::from_fn(|_|scratch.path("scan-linear"));
 let mut scan_files=if sharpening {scan_paths.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?}else{Vec::new()};
 let mut scan_preview=if crate::scan_preview::path(output_path).is_some(){crate::scan_preview::Sampler::new(width,height).ok()}else{None};
 progress.begin_work("printing_scanning");for y in(0..height).step_by(rows as usize){cancel(&mut cancelled)?;let hh=rows.min(height-y);
   let mut rgb=if let Some(log_xyz)=scanner_input.as_mut(){
     let values=read64(log_xyz,width as usize*hh as usize*3)?.into_iter().map(from_f64).collect();
     let image=ImageBuf::from_data(width,hh,values);
     let finalize=!sharpening&&glare_input.is_none()&&!print_diffusion;
     if let Some(field)=glare_input.as_mut(){let values:Vec<Scalar>=read64(field,width as usize*hh as usize)?.into_iter().map(from_f64).collect();prepared.finish_scan_log_xyz_chunk_with_glare(&image,&values,finalize).map_err(|e|error("PIPELINE_ERROR",e))?}else{prepared.finish_scan_log_xyz_chunk(&image,finalize).map_err(|e|error("PIPELINE_ERROR",e))?}
   }else{
   let mut planes=Vec::new();for f in &mut files{planes.push(read64(f,width as usize*hh as usize)?);}let image=image_from_planes(&planes,width,hh);
   let developed=if print_diffusion{Some(prepared.develop_print_raw_chunk(&image,&CpuBackend).map_err(|e|error("PIPELINE_ERROR",e))?)}else{None};
   if let Some(developed)=developed.as_ref(){
     if let Some(field)=glare_input.as_mut(){let values:Vec<Scalar>=read64(field,width as usize*hh as usize)?.into_iter().map(from_f64).collect();prepared.scan_print_density_chunk_unclipped_with_glare(developed,&values).map_err(|e|error("PIPELINE_ERROR",e))?}else{prepared.scan_print_density_chunk_unclipped(developed).map_err(|e|error("PIPELINE_ERROR",e))?}
   }else if let Some(field)=glare_input.as_mut(){let values:Vec<Scalar>=read64(field,width as usize*hh as usize)?.into_iter().map(from_f64).collect();prepared.process_density_chunk_unclipped_with_glare(&image,&values,&CpuBackend).map_err(|e|error("PIPELINE_ERROR",e))?}else if sharpening {prepared.process_density_chunk_unclipped(&image,&CpuBackend)}else{prepared.process_density_chunk(&image,&CpuBackend)}
   };
   if !sharpening&&(glare_input.is_some()||print_diffusion){for v in &mut rgb.data{*v=(*v).clamp(from_f64(0.),from_f64(1.));}}
   if scan_preview.as_mut().is_some_and(|preview|preview.capture(y,&rgb).is_err()){scan_preview=None;}
   if sharpening {write_image_planes(&mut scan_files,&rgb)?;}else{write_output_rgb_for_route(&mut output,&rgb,exposure,&pipe.params.io.output_color_space,science_route)?;}
   progress.pass("printing_scanning",(y+hh)as u64,height as u64);
 }
 progress.end_work();
 // Real full-frame scan RGB exists now; later scanner blur/sharpen still belongs to stage6.
 if let Some(preview)=scan_preview {if let Err(e)=preview.publish(output_path,&pipe.params.io.output_color_space,exposure,&mut cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}}
 drop(scan_files);drop(files);drop(glare_input);drop(scanner_input);if let Some(q)=&scanner_candidate{scratch.remove(q)?;}if let Some(q)=&glare_path{scratch.remove(q)?;}for q in &density{scratch.remove(q)?;}scratch.observe()?;
 if sharpening {
   let at_sharpen=Instant::now();
   for ch in 0..3 {
     if pipe.params.scanner.lens_blur>0. {let b=scratch.blur(&scan_paths[ch],width,height,pipe.params.scanner.lens_blur,"scan_blur",&mut cancelled,&mut progress)?;scratch.remove(&scan_paths[ch])?;fs::rename(&b,&scan_paths[ch]).map_err(io)?;}
     if sharp_amount>0. && sharp_radius>0. {
       let blurred=scratch.blur(&scan_paths[ch],width,height,sharp_radius,"scan_sharpen",&mut cancelled,&mut progress)?;
       let amount=from_f32(sharp_amount) as f64;
       inplace_with(&scan_paths[ch],&[&blurred],n,&mut cancelled,|original,x|(original+amount*(original-x[0])).clamp(0.,1.))?;
       scratch.remove(&blurred)?;
     }else{inplace_with(&scan_paths[ch],&[],n,&mut cancelled,|original,_|original.clamp(0.,1.))?;}
   }
   let mut scans=scan_paths.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;
   progress.begin_work(if sharp_amount>0.&&sharp_radius>0.{"scan_sharpen"}else{"scan_blur"});for y in(0..height).step_by(rows as usize){cancel(&mut cancelled)?;let hh=rows.min(height-y);let mut planes=Vec::new();for f in &mut scans{planes.push(read64(f,width as usize*hh as usize)?);}write_output_rgb_for_route(&mut output,&image_from_planes(&planes,width,hh),exposure,&pipe.params.io.output_color_space,science_route)?;progress.pass(if sharp_amount>0.&&sharp_radius>0.{"scan_sharpen"}else{"scan_blur"},(y+hh)as u64,height as u64);}
   progress.end_work();drop(scans);for q in &scan_paths{scratch.remove(q)?;}
   stages.push(StageMetrics{stage:if sharp_amount>0.&&sharp_radius>0.{"scan_sharpen"}else{"scan_blur"},elapsed_ms:at_sharpen.elapsed().as_secs_f64()*1000.});
 }
 output.flush().map_err(io)?;cancel(&mut cancelled)?;drop(output);if fs::metadata(output_path).map_err(io)?.len()!=n*12{return Err(error("INVALID_OUTPUT_SHAPE","Final output byte count changed"));}stages.push(StageMetrics{stage:"printing_scanning",elapsed_ms:at.elapsed().as_secs_f64()*1000.+scanner_elapsed_ms});
 }
 }
 if let Some(c)=checkpoints.as_mut(){
  if gpu_scanner.metrics.requested {c.set_printing_scanning_backend(if gpu_scanner.metrics.backend=="gpu_pchip_f32_v1"{crate::checkpoint::PrintingScanningBackend::GpuPchipF32V1}else{crate::checkpoint::PrintingScanningBackend::CpuF64}).map_err(checkpoint_error)?;}
  gpu_science.record_checkpoint(c,6).map_err(checkpoint_error)?;let at=Instant::now();c.commit(6,&[output_path.to_path_buf()],metered_ev,&mut cancelled).map_err(checkpoint_error)?;checkpoint_commit_ms+=at.elapsed().as_secs_f64()*1000.;}
 }else{
   progress.begin_stage(6,1,"printing_scanning");progress.begin_work("printing_scanning");
   checkpoints.as_ref().unwrap().restore(&[output_path.to_path_buf()],&mut cancelled).map_err(checkpoint_error)?;progress.end_work();
   if science_route==ScienceRoute::Export {if let Err(e)=crate::scan_preview::publish_restored(output_path,width,height,&pipe.params.io.output_color_space,&mut cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}}
 }
 scratch.observe()?;let wall_elapsed_ms=start.elapsed().as_secs_f64()*1000.;
 let metrics=StreamedMetrics{gpu_science:gpu_science.metrics,gpu_dir:gpu_dir.metrics,gpu_exposure:gpu_exposure.metrics,gpu_optical:gpu_optical.metrics,gpu_scanner:gpu_scanner.metrics,gpu_diffusion:gpu_diffusion.metrics,gpu_probe_capture:gpu_probe_capture.metrics.clone(),wall_elapsed_ms,checkpoint_commit_ms,checkpoint_identity_ms,checkpoint_resume_stage:resume_stage,export_optimization_mask:optimizations.bits(),gpu_resource_reuse:optimizations.gpu_resource_reuse,export_execution:None,metered_ev,gpu:backend.diagnostics(),width,height,pixel_count:n,memory_budget_mi_b:memory_budget_mib,elapsed_ms:(wall_elapsed_ms-gpu_probe_capture.metrics.capture_ms).max(0.),setup_ms,estimated_scratch_bytes:estimated_scratch_with_diffusion(width,height,pipe.params.camera.diffusion_filter.active||print_diffusion)?,peak_logical_scratch_bytes:scratch.peak,spatial_peak_explicit_buffer_bytes:scratch.spatial_buffer,spatial_bytes_read:scratch.read,spatial_bytes_written:scratch.written,spatial_pass_count:scratch.passes,pointwise_chunk_rows:rows,lut_storage_bytes:prepared.lut_storage_bytes(),stages,algorithm:"Full-image FIR/IIR and optional exact disk FFT overlap-save; spatial IO counters cover FIR/IIR only",grain_mode:if science_route==ScienceRoute::Preview{"Whole-image sequential MT19937 Poisson-binomial with original blur"}else{"Absolute-coordinate Poisson-binomial with global dye-cloud/final blur"},memory_policy:"Bounded row chunks plus16MiB spatial buffers; budget is admission estimate, not OS guarantee"};
 fs::remove_dir_all(&scratch.dir).map_err(io)?;drop(scratch);output_guard.keep=true;progress.pass("complete",n,n);progress.finish_stage();gpu_probe_capture.keep=true;Ok(metrics)
}

#[cfg(all(test,feature="precision-f64"))] mod optimization_tests {
 use super::*;
 #[test] fn fused_worst_stage_fits_existing_disk_reservation() {
  for (w,h) in [(1,1),(40000,1),(1,40000),(40000,40000)] {
   let worst_fused=12*8*w as u64*h as u64;
   assert!(worst_fused<=estimated_scratch_bytes(w,h).unwrap());
  }
 }
 #[test] fn new_exact_choices_match_frozen_and_current_baselines_across_sizes_seeds_and_profiles() {
  use sha2::{Digest,Sha256};
  let root=std::env::temp_dir().join(format!("export-exact-opt3-{}",std::process::id()));fs::create_dir(&root).unwrap();
  struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}let _guard=Temp(root.clone());
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let execution=crate::export_execution::ExportPermit::acquire(||false).unwrap();
  for (w,h) in [(17u32,13u32),(47,29),(257,129)] {for (seed,film) in [(1u64,"kodak_portra_400"),(42,"fujifilm_pro_400h"),(42,"kodak_doublex")] {
   let case=root.join(format!("{w}-{h}-{seed}-{film}"));fs::create_dir(&case).unwrap();let input=case.join("input");
   let original=(0..w*h*3).flat_map(|i|{let pixel=i/3;let x=pixel%w;let y=pixel/w;let ch=i%3;
    let v=if (x+11*y)%97==0 {8.0f32} else if (x+3*y)%71==0 {0.} else if x<w/4 {0.0001*((ch+1)as f32)} else {((x*17+y*31+ch*53+seed as u32*13)%1024)as f32/1024.};v.to_le_bytes()
   }).collect::<Vec<_>>();fs::write(&input,&original).unwrap();
   let mut params=RuntimeParams::default();params.io.input_cctf_decoding=false;params.io.output_cctf_encoding=false;
   params.camera.auto_exposure=seed==42;params.camera.pixel_size_um_override=Some(6.);
   params.film_render.grain.seed=seed;params.camera.diffusion_filter.active=true;params.camera.diffusion_filter.filter_family="black_pro_mist".into();
   params.enlarger.diffusion_filter.active=true;params.enlarger.diffusion_filter.filter_family=if seed==1{"cinebloom"}else{"glimmerglass"}.into();
   let paper="kodak_portra_endura";
   // Frozen pre-optimization pipeline provides an independent orchestration
   // oracle, including baseline FFT and original grain sampling/interpolation.
   // Frozen test_baseline_streamed16.rs rejects film.is_bw() explicitly.
   // Current mask 0 is therefore the baseline for the valid Double-X print
   // route. Both color profiles keep the independent frozen oracle.
   let frozen=film!="kodak_doublex";let oracle=case.join("oracle");
   if frozen {crate::test_baseline_streamed16::export_file(&data,film,paper,params.clone(),&input,&oracle,&case.join("oracle-work"),w,h,384,0.,||false,|_,_,_|{}).unwrap();}
   else {execution.run(||export_file_checkpointed_managed_with_optimizations(&execution,&data,film,paper,params.clone(),&input,&oracle,&case.join("oracle-work"),None,w,h,384,0.,false,ExportOptimizations::default(),||false,|_|{})).unwrap();}
   let expected=fs::read(&oracle).unwrap();assert_eq!(expected.len(),w as usize*h as usize*12);
   assert!(expected.chunks_exact(4).all(|v|f32::from_le_bytes(v.try_into().unwrap()).is_finite()));
   for bits in [0,16,32,48,63,64,80,96,112,127] {
    let output=case.join(format!("output-{bits}"));let scratch=case.join(format!("work-{bits}"));
    let metrics=execution.run(||export_file_checkpointed_managed_with_optimizations(&execution,&data,film,paper,params.clone(),&input,&output,&scratch,None,w,h,384,0.,false,ExportOptimizations::from_bits(bits).unwrap(),||false,|_|{})).unwrap();
    let bytes=fs::read(&output).unwrap();assert_eq!(bytes,expected,"{w}x{h} seed={seed} film={film} bits={bits}");
    assert!(!scratch.exists());assert_eq!(metrics.export_optimization_mask,bits);assert!(metrics.spatial_peak_explicit_buffer_bytes<=16*1024*1024);
    eprintln!("EXACT_OPT3_PARITY {}",serde_json::json!({"width":w,"height":h,"seed":seed,"film":film,"oracle":if frozen {"frozen_pre_optimization"}else{"current_mask_0_bw_route"},"bits":bits,"sha256":format!("{:x}",Sha256::digest(&bytes)),"metrics":metrics}));
   }
   assert_eq!(fs::read(&input).unwrap(),original);
  }}
 }
 #[test] fn all_export_choices_preserve_full_output_and_cross_resume() {
  let root=std::env::temp_dir().join(format!("export-optimization-{}",std::process::id()));fs::create_dir(&root).unwrap();
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let input=root.join("input");let(w,h)=(17,13);
  fs::write(&input,(0..w*h*3).flat_map(|i|((i%107)as f32/107.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut params=RuntimeParams::default();params.io.input_cctf_decoding=false;params.io.output_cctf_encoding=false;params.camera.auto_exposure=true;params.camera.diffusion_filter.active=true;params.camera.diffusion_filter.filter_family="glimmerglass".into();
  let execution=crate::export_execution::ExportPermit::acquire(||false).unwrap();
  let mut expected=None;
  for bits in [0,1,2,4,8,15,16,31,32,48,63,64,80,96,112,127] {
   let output=root.join(format!("out-{bits}"));let scratch=root.join(format!("scratch-{bits}"));let checkpoint=root.join(format!("checkpoint-{bits}"));
   execution.run(||export_file_checkpointed_managed_with_optimizations(&execution,&data,"kodak_portra_400","kodak_portra_endura",params.clone(),&input,&output,&scratch,Some(&checkpoint),w,h,384,0.,false,ExportOptimizations::from_bits(bits).unwrap(),||false,|_|{})).unwrap();
   let bytes=fs::read(&output).unwrap();if let Some(want)=&expected {assert_eq!(&bytes,want,"bits={bits}");}else{expected=Some(bytes);}
   let resumed=root.join(format!("resume-{bits}"));let scratch=root.join(format!("resume-scratch-{bits}"));
   execution.run(||export_file_checkpointed_managed_with_optimizations(&execution,&data,"kodak_portra_400","kodak_portra_endura",params.clone(),&input,&resumed,&scratch,Some(&checkpoint),w,h,384,0.,false,ExportOptimizations::from_bits(bits^127).unwrap(),||false,|_|{})).unwrap();assert_eq!(fs::read(resumed).unwrap(),*expected.as_ref().unwrap());
  }
  // Interrupt only after a durable early checkpoint, then switch execution choices.
  let output=root.join("cancel-output");let scratch=root.join("cancel-scratch");let checkpoint=root.join("cancel-checkpoint");
  let stop=std::sync::atomic::AtomicBool::new(false);
  let error=execution.run(||export_file_checkpointed_managed_with_optimizations(&execution,&data,"kodak_portra_400","kodak_portra_endura",params.clone(),&input,&output,&scratch,Some(&checkpoint),w,h,384,0.,false,ExportOptimizations::from_bits(15).unwrap(),||stop.load(std::sync::atomic::Ordering::Relaxed),|event|{if event.display_stage==3&&event.stage_done==event.stage_total {stop.store(true,std::sync::atomic::Ordering::Relaxed);}})).unwrap_err();
  assert_eq!(error.code,"CANCELLED");assert!(!output.exists());assert!(!scratch.exists());
  let result=execution.run(||export_file_checkpointed_managed_with_optimizations(&execution,&data,"kodak_portra_400","kodak_portra_endura",params,&input,&output,&scratch,Some(&checkpoint),w,h,384,0.,false,ExportOptimizations::default(),||false,|_|{})).unwrap();assert_eq!(result.checkpoint_resume_stage,3);assert_eq!(fs::read(output).unwrap(),*expected.as_ref().unwrap());
  fs::remove_dir_all(root).unwrap();
 }
}

#[cfg(test)] mod gpu_scanner_policy_tests {
 use super::*;
 #[test] fn checkpoint_policies_are_independent_and_preserve_legacy_identities() {
  let params=RuntimeParams::default();
  let config=|camera,scanner|checkpoint_configuration("film","paper",&params,0.25,false,camera,scanner,false,false,false).unwrap();
  assert_eq!(config(false,false),serde_json::to_string(&("film","paper",&params,0.25,false)).unwrap());
  assert_eq!(config(true,false),serde_json::to_string(&("film","paper",&params,0.25,false,"gpu-camera-f32-whole-stage-fallback-v1")).unwrap());
  let mut all=vec![config(false,false),config(true,false),config(false,true),config(true,true)];
  for camera in [false,true]{for scanner in [false,true]{all.push(checkpoint_configuration("film","paper",&params,0.25,false,camera,scanner,true,false,false).unwrap());}}
  for camera in [false,true]{for scanner in [false,true]{for optical in [false,true]{all.push(checkpoint_configuration("film","paper",&params,0.25,false,camera,scanner,optical,true,false).unwrap());}}}
  for camera in [false,true]{for scanner in [false,true]{for optical in [false,true]{for exposure in [false,true]{all.push(checkpoint_configuration("film","paper",&params,0.25,false,camera,scanner,optical,exposure,true).unwrap());}}}}
  for i in 0..all.len(){for j in i+1..all.len(){assert_ne!(all[i],all[j]);}}
 }
 #[test] fn transfer_metrics_are_optional_bounded_counters() {
  assert_eq!(gpu_transfer_counters(&serde_json::json!({})),(None,None,None));
  assert_eq!(gpu_transfer_counters(&serde_json::json!({"uploadBytes":42,"readbackBytes":99,"dispatches":7})),(Some(42),Some(99),Some(7)));
  assert_eq!(gpu_transfer_counters(&serde_json::json!({"uploadBytes":68719476737u64,"readbackBytes":-1,"dispatches":10000001})),(None,None,None));
 }
 #[test] fn scanner_resume_and_diagnostics_expose_actual_backend_not_requested_backend() {
  let cpu=GpuScanner::new(None,0,crate::checkpoint::PrintingScanningBackend::CpuF64);
  assert!(!cpu.metrics.requested);assert!(!cpu.metrics.attempted);assert!(!cpu.metrics.resumed);assert_eq!(cpu.metrics.backend,"cpu_f64");
  let resumed=GpuScanner::new(Some(GpuDiffusionRequest{parent_handle:1,token:7,stage_dir:Some(PathBuf::from("/private/stage")),delegate:None}),6,crate::checkpoint::PrintingScanningBackend::GpuPchipF32V1);
  assert!(resumed.metrics.requested&&resumed.metrics.resumed);assert!(!resumed.metrics.attempted);assert_eq!(resumed.metrics.backend,"gpu_pchip_f32_v1");
  let safe=serde_json::to_string(&resumed.metrics).unwrap();assert!(!safe.contains("private")&&!safe.contains("token")&&!safe.contains("manifest"));
 }
}

#[cfg(test)] mod diffusion_heartbeat_tests {
 use super::*;
 #[test] fn heartbeat_is_rate_limited_and_never_depends_on_pixel_counts() {
  let mut heartbeat=DiffusionHeartbeat::new(Duration::from_secs(1));
  let start=heartbeat.last;
  assert!(!heartbeat.due(start+Duration::from_millis(999)));
  assert!(heartbeat.due(start+Duration::from_secs(1)));
  assert!(!heartbeat.due(start+Duration::from_millis(1999)));
  assert!(heartbeat.due(start+Duration::from_secs(2)));
 }
}

#[cfg(all(test,feature="precision-f64",feature="diffusion-research"))]
mod gpu_probe_capture_tests {
 use super::*;
 use std::sync::atomic::{AtomicBool,Ordering};
 struct Fixture {root:PathBuf,input:PathBuf,params:RuntimeParams}
 impl Fixture {
  fn new()->Self {
   let root=std::env::temp_dir().join(format!("gpu-capture-hook-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
   fs::create_dir(&root).unwrap();let root=root.canonicalize().unwrap();let input=root.join("input.rgb");
   fs::write(&input,(0..17*13*3).flat_map(|i|((i%107)as f32/107.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
   let mut params=RuntimeParams::default();params.io.input_cctf_decoding=false;params.io.output_cctf_encoding=false;
   params.camera.diffusion_filter.active=true;params.camera.diffusion_filter.filter_family="black_pro_mist".into();params.camera.pixel_size_um_override=Some(10.);
   Self{root,input,params}
  }
  fn request(&self,token:u64)->Option<GpuProbeCaptureRequest> {Some(GpuProbeCaptureRequest::Capture{token,snapshot_dir:self.root.join(format!("probe-{token}"))})}
  fn run(&self,name:&str,params:RuntimeParams,checkpoint:Option<&Path>,request:Option<GpuProbeCaptureRequest>,cancelled:impl FnMut()->bool+Send,progress:impl FnMut(ExportProgress)+Send)->Result<StreamedMetrics> {
   let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let execution=crate::export_execution::ExportPermit::acquire(||false).unwrap();
   execution.run(||export_file_checkpointed_managed_with_gpu_probe(&execution,&data,"kodak_portra_400","kodak_portra_endura",params,&self.input,&self.root.join(name),&self.root.join(format!("work-{name}")),checkpoint,17,13,384,0.,false,ExportOptimizations::default(),request,cancelled,progress))
  }
 }
 impl Drop for Fixture {fn drop(&mut self){let _=fs::remove_dir_all(&self.root);}}
 #[test] fn gpu_probe_capture_preserves_full_output_bytes_and_safe_metrics() {
  let f=Fixture::new();let original=fs::read(&f.input).unwrap();
  let disabled=f.run("baseline",f.params.clone(),None,None,||false,|_|{}).unwrap();
  assert_eq!(disabled.gpu_probe_capture.status,"disabled");assert_eq!(disabled.gpu_probe_capture.capture_ms,0.);
  let enabled=f.run("captured",f.params.clone(),None,f.request(11),||false,|_|{}).unwrap();
  assert_eq!(enabled.gpu_probe_capture.status,"captured");assert!(enabled.gpu_probe_capture.capture_ms>0.);
  assert_eq!(fs::read(f.root.join("baseline")).unwrap(),fs::read(f.root.join("captured")).unwrap());assert_eq!(fs::read(&f.input).unwrap(),original);
  let plan=crate::research_diffusion::probe::plan(&f.root.join("probe-11"),11).unwrap();assert_eq!(plan.channel,1);assert_eq!((plan.original_width,plan.original_height),(17,13));
  let metrics=serde_json::to_value(&enabled).unwrap();let safe=metrics["gpuProbeCapture"].as_object().unwrap();
  assert_eq!(safe.len(),2);assert!(safe.contains_key("status")&&safe.contains_key("captureMs"));
  assert!((enabled.wall_elapsed_ms-enabled.elapsed_ms-enabled.gpu_probe_capture.capture_ms).abs()<1e-6);
  assert!(enabled.stages.iter().any(|s|s.stage=="camera_diffusion"&&s.elapsed_ms>=0.));
  // Existing snapshots belong to their caller. A collision cannot overwrite or
  // remove that snapshot and cannot change the export result.
  let manifest=fs::read(f.root.join("probe-11/manifest.json")).unwrap();
  let collision=f.run("collision",f.params.clone(),None,f.request(11),||false,|_|{}).unwrap();
  assert_eq!(collision.gpu_probe_capture.status,"skipped_failed");
  assert_eq!(fs::read(f.root.join("probe-11/manifest.json")).unwrap(),manifest);
  assert_eq!(fs::read(f.root.join("baseline")).unwrap(),fs::read(f.root.join("collision")).unwrap());
 }
 #[test] fn gpu_probe_capture_skips_inactive_noop_and_invalid_requests() {
  let f=Fixture::new();
  for (i,active,strength,status,invalid) in [(1,false,0.5,"skipped_inactive",false),(2,true,0.,"skipped_noop",false),(3,true,0.5,"skipped_invalid_request",true)] {
   let mut params=f.params.clone();params.camera.diffusion_filter.active=active;params.camera.diffusion_filter.strength=strength;
   let request=if invalid {Some(GpuProbeCaptureRequest::Invalid)}else{f.request(i)};
   let m=f.run(&format!("skip-{i}"),params,None,request,||false,|_|{}).unwrap();
   assert_eq!(m.gpu_probe_capture.status,status);assert_eq!(m.gpu_probe_capture.capture_ms,0.);assert!(!f.root.join(format!("probe-{i}")).exists());
  }
 }
 #[test] fn gpu_probe_capture_cleans_cancelled_export_and_skips_camera_checkpoint_resume() {
  let f=Fixture::new();let stop=AtomicBool::new(false);let observed=AtomicBool::new(false);let checkpoint=f.root.join("checkpoint");let snapshot=f.root.join("probe-21");
  let error=f.run("cancelled",f.params.clone(),Some(&checkpoint),f.request(21),||{if snapshot.join("manifest.json").exists(){observed.store(true,Ordering::Relaxed);}stop.load(Ordering::Relaxed)},|event|{if event.display_stage==3&&event.stage_done==event.stage_total {stop.store(true,Ordering::Relaxed);}}).unwrap_err();
  assert!(observed.load(Ordering::Relaxed));assert_eq!(error.code,"CANCELLED");assert!(!snapshot.exists());assert!(!f.root.join("cancelled").exists());assert!(!f.root.join("work-cancelled").exists());
  let resumed=f.run("resumed",f.params.clone(),Some(&checkpoint),f.request(22),||false,|_|{}).unwrap();
  assert_eq!(resumed.checkpoint_resume_stage,3);assert_eq!(resumed.gpu_probe_capture.status,"skipped_resumed");assert_eq!(resumed.gpu_probe_capture.capture_ms,0.);assert!(!f.root.join("probe-22").exists());
  let complete=f.run("complete-resume",f.params.clone(),Some(&checkpoint),f.request(23),||false,|_|{}).unwrap();assert_eq!(complete.checkpoint_resume_stage,6);assert_eq!(complete.gpu_probe_capture.status,"skipped_resumed");assert!(!f.root.join("probe-23").exists());
  assert_eq!(fs::read(f.root.join("resumed")).unwrap(),fs::read(f.root.join("complete-resume")).unwrap());
 }
 #[test] fn gpu_probe_capture_cleans_partial_capture_and_downstream_failure() {
  let f=Fixture::new();let stop=AtomicBool::new(false);let snapshot=f.root.join("probe-31");
  let error=f.run("partial-cancel",f.params.clone(),None,f.request(31),||{if snapshot.exists(){stop.store(true,Ordering::Relaxed);}stop.load(Ordering::Relaxed)},|_|{}).unwrap_err();
  assert!(stop.load(Ordering::Relaxed));assert_eq!(error.code,"CANCELLED");assert!(!snapshot.exists());assert!(!f.root.join("partial-cancel").exists());
  let observed=AtomicBool::new(false);let snapshot=f.root.join("probe-32");
  // Removing the owned scratch directory at the next channel checkpoint forces
  // an ordinary IO failure after a ready snapshot, without touching input.
  let scratch=f.root.join("work-downstream-failure");
  let error=f.run("downstream-failure",f.params.clone(),None,f.request(32),||{if !observed.load(Ordering::Relaxed)&&snapshot.join("manifest.json").exists(){observed.store(true,Ordering::Relaxed);fs::remove_dir_all(&scratch).unwrap();}false},|_|{}).unwrap_err();
  assert!(observed.load(Ordering::Relaxed));assert_eq!(error.code,"IO_ERROR");assert!(!snapshot.exists());assert!(!f.root.join("downstream-failure").exists());
 }
}

#[cfg(test)] mod gpu_delegate_lifetime_tests {
 use super::*;
 use std::{collections::VecDeque,sync::{Arc,atomic::{AtomicUsize,AtomicBool,Ordering}}};
 fn terminal(token:u64,total:u64)->String {serde_json::json!({"schemaVersion":1,"token":token,"status":"success_stopped","completedPixels":total,"totalPixels":total,"code":"COMPLETE"}).to_string()}
 struct Fake {begin:Option<bool>,panic_begin:bool,replies:VecDeque<Option<String>>,cancels:Arc<AtomicUsize>}
 impl GpuDiffusionDelegate for Fake {
  fn available_disk_bytes(&mut self)->Option<u64>{Some(u64::MAX)}
  fn begin(&mut self,_:&str)->Option<bool>{if self.panic_begin{panic!("after-dispatch test panic");}self.begin}
  fn poll(&mut self,_:u64)->Option<String>{self.replies.pop_front().flatten()}
  fn cancel(&mut self,_:u64){self.cancels.fetch_add(1,Ordering::Relaxed);}
 }
 #[test] fn gpu_poll_is_closed_and_requires_matching_terminal_identity() {
  let raw=terminal(7,9);assert_eq!(parse_gpu_poll(&raw,7,9).unwrap().terminal,Some(GpuTerminal::Success));
  assert!(parse_gpu_poll(&raw,8,9).is_none());assert!(parse_gpu_poll(&raw,7,10).is_none());
  for replacement in ["success","timed_out","died_maybe","COMPLETE"] {assert!(parse_gpu_poll(&raw.replace("success_stopped",replacement),7,9).is_none());}
  let mut v:serde_json::Value=serde_json::from_str(&raw).unwrap();v["path"]=serde_json::json!("private");assert!(parse_gpu_poll(&v.to_string(),7,9).is_none());
 }
 #[test] fn gpu_uncertain_begin_and_transient_poll_error_do_not_release_ownership() {
  let may_run=Arc::new(AtomicBool::new(false));let cancels=Arc::new(AtomicUsize::new(0));
  let mut fake=Fake{begin:None,panic_begin:false,replies:VecDeque::from([None,Some(terminal(7,9))]),cancels:cancels.clone()};
  let mut owner=GpuHelperOwner::new(&mut fake,7,9,may_run.clone());assert!(owner.begin("{}"));assert!(may_run.load(Ordering::Acquire));
  assert!(owner.poll().is_none());assert!(may_run.load(Ordering::Acquire));owner.cancel_once();owner.cancel_once();assert_eq!(cancels.load(Ordering::Relaxed),1);
  assert_eq!(owner.poll().unwrap().terminal,Some(GpuTerminal::Success));assert!(!may_run.load(Ordering::Acquire));
 }
 #[test] fn gpu_panic_after_begin_drains_or_preserves_without_infinite_drop() {
  let may_run=Arc::new(AtomicBool::new(false));let cancels=Arc::new(AtomicUsize::new(0));
  let mut fake=Fake{begin:None,panic_begin:true,replies:VecDeque::from([Some(terminal(7,9))]),cancels:cancels.clone()};
  let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{let mut owner=GpuHelperOwner::new(&mut fake,7,9,may_run.clone());owner.begin("{}");}));
  assert!(result.is_err());assert!(!may_run.load(Ordering::Acquire));assert_eq!(cancels.load(Ordering::Relaxed),1);
  let mut broken=Fake{begin:Some(true),panic_begin:false,replies:VecDeque::new(),cancels};let at=Instant::now();
  {let mut owner=GpuHelperOwner::new(&mut broken,7,9,may_run.clone());assert!(owner.begin("{}"));}
  assert!(at.elapsed()<Duration::from_secs(5));assert!(may_run.load(Ordering::Acquire));
 }
}

#[cfg(all(test,feature="precision-f64",feature="diffusion-research"))]
mod gpu_full_stage_hook_tests {
 use super::*;
 use std::sync::{Arc,atomic::{AtomicBool,AtomicUsize,Ordering}};
 use sha2::{Digest,Sha256};
 #[derive(Clone,Copy)] enum Mode {Mirror,ThirdInvalid,RenameFailure,Uncertain,Reject,Broken,Malformed,UnknownTerminal,CancelReady}
 struct FakeDelegate {mode:Mode,token:u64,total:u64,summary:Option<serde_json::Value>,polls:usize,cancels:Arc<AtomicUsize>,cancelled:Arc<AtomicBool>}
 impl GpuDiffusionDelegate for FakeDelegate {
  fn available_disk_bytes(&mut self)->Option<u64>{Some(u64::MAX)}
  fn begin(&mut self,wire:&str)->Option<bool>{
   let value:serde_json::Value=serde_json::from_str(wire).unwrap();self.token=value["token"].as_u64().unwrap();self.total=value["totalPixels"].as_u64().unwrap();
   if matches!(self.mode,Mode::Reject){return Some(false);}
   if matches!(self.mode,Mode::Broken|Mode::Malformed|Mode::UnknownTerminal){return Some(true);}
   let dir=Path::new(value["jobDir"].as_str().unwrap());
   self.summary=Some(crate::research_diffusion::full_stage::run_stage_cpu_mirror_for_test(dir,self.token,value["manifestSha256"].as_str().unwrap(),||false).unwrap());
   if matches!(self.mode,Mode::ThirdInvalid){fs::write(dir.join("candidate/plane-2.f64le"),[0u8;8]).unwrap();}
   if matches!(self.mode,Mode::RenameFailure) {
    let scratch=fs::read_dir(dir.parent().unwrap()).unwrap().map(|e|e.unwrap().path()).find(|p|p.file_name().unwrap().to_string_lossy().starts_with("work-")).unwrap();
    let last=fs::read_dir(&scratch).unwrap().filter_map(|e|e.ok()?.file_name().to_string_lossy().split('-').nth(1)?.parse::<u64>().ok()).max().unwrap();
    fs::create_dir(scratch.join(format!("stage-{:05}-gpu-camera-diffusion.f64le",last+2))).unwrap();
   }
   if matches!(self.mode,Mode::CancelReady){self.cancelled.store(true,Ordering::Release);}
   if matches!(self.mode,Mode::Uncertain){None}else{Some(true)}
  }
  fn poll(&mut self,_:u64)->Option<String>{
   self.polls+=1;
   if matches!(self.mode,Mode::Broken)||(matches!(self.mode,Mode::Uncertain)&&self.polls==1){return None;}
   if matches!(self.mode,Mode::Malformed){return Some("malformed".into());}
   let status=if matches!(self.mode,Mode::UnknownTerminal){"death_assumed"}else{"success_stopped"};
   Some(serde_json::json!({"schemaVersion":1,"token":self.token,"status":status,"completedPixels":self.total,"totalPixels":self.total,"code":"COMPLETE","summary":self.summary}).to_string())
  }
  fn cancel(&mut self,_:u64){self.cancels.fetch_add(1,Ordering::Relaxed);}
 }
 struct Fixture {root:PathBuf,input:PathBuf,params:RuntimeParams,w:u32,h:u32}
 impl Fixture {
  fn new(w:u32,h:u32,seed:u64)->Self {
   let root=std::env::temp_dir().join(format!("gpu-full-hook-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
   fs::create_dir(&root).unwrap();let root=root.canonicalize().unwrap();let input=root.join("input.rgb");
   fs::write(&input,(0..w*h*3).flat_map(|i|{let x=(i/3)%w;let y=(i/3)/w;let ch=i%3;let f=if (x+7*y)%53==0{6f32}else if x==0||y==h-1{0.0001*(ch+1)as f32}else{((x*17+y*31+ch*53+seed as u32)%1009)as f32/1009.};f.to_le_bytes()}).collect::<Vec<_>>()).unwrap();
   let mut params=RuntimeParams::default();params.io.input_cctf_decoding=false;params.io.output_cctf_encoding=false;params.camera.pixel_size_um_override=Some(10.);
   params.camera.diffusion_filter.active=true;params.camera.diffusion_filter.filter_family="black_pro_mist".into();params.film_render.grain.seed=seed;
   Self{root,input,params,w,h}
  }
  fn request(&self,token:u64,mode:Option<Mode>,cancelled:Arc<AtomicBool>,cancels:Arc<AtomicUsize>)->GpuDiffusionRequest {
   GpuDiffusionRequest{parent_handle:1,token,stage_dir:Some(self.root.join(format!("gpu-stage-{token}"))),delegate:mode.map(|mode|Box::new(FakeDelegate{mode,token,total:0,summary:None,polls:0,cancelled,cancels})as Box<dyn GpuDiffusionDelegate>)}
  }
  fn run(&self,name:&str,checkpoint:Option<&Path>,request:Option<GpuDiffusionRequest>,cancelled:Arc<AtomicBool>)->Result<StreamedMetrics> {
   let execution=crate::export_execution::ExportPermit::acquire(||false).unwrap();let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
   execution.run(||export_file_checkpointed_managed_with_gpu_delegate(&execution,&data,"kodak_portra_400","kodak_portra_endura",self.params.clone(),&self.input,&self.root.join(name),&self.root.join(format!("work-{name}")),checkpoint,self.w,self.h,384,0.,false,ExportOptimizations::default(),None,request,||cancelled.load(Ordering::Acquire),|_|{}))
  }
 }
 impl Drop for Fixture {fn drop(&mut self){let _=fs::remove_dir_all(&self.root);}}
 fn flags()->(Arc<AtomicBool>,Arc<AtomicUsize>){(Arc::new(AtomicBool::new(false)),Arc::new(AtomicUsize::new(0)))}
 #[test] fn full_gpu_hook_rejects_partial_channel_and_keeps_cpu_fallback_byte_identical() {
  let f=Fixture::new(17,13,42);let(stop,cancels)=flags();f.run("baseline",None,None,stop.clone()).unwrap();let baseline=fs::read(f.root.join("baseline")).unwrap();
  for (token,mode,reason) in [(1,Mode::ThirdInvalid,"invalid_output"),(2,Mode::Reject,"not_started"),(22,Mode::RenameFailure,"commit_failed")] {
   let request=f.request(token,Some(mode),stop.clone(),cancels.clone());let result=f.run(&format!("fallback-{token}"),None,Some(request),stop.clone()).unwrap();
   assert_eq!(result.gpu_diffusion.backend,"cpu_f64");assert_eq!(result.gpu_diffusion.fallback_reason,reason);assert_eq!(fs::read(f.root.join(format!("fallback-{token}"))).unwrap(),baseline);assert!(!f.root.join(format!("gpu-stage-{token}")).exists());
  }
 }
 #[test] fn full_gpu_hook_cancel_at_ready_is_cancel_not_cpu_fallback() {
  let f=Fixture::new(17,13,42);let(stop,cancels)=flags();let original=fs::read(&f.input).unwrap();
  let request=f.request(3,Some(Mode::CancelReady),stop.clone(),cancels.clone());let result=f.run("cancelled",None,Some(request),stop).unwrap_err();
  assert_eq!(result.code,"CANCELLED");assert_eq!(cancels.load(Ordering::Relaxed),1);assert!(!f.root.join("gpu-stage-3").exists());assert!(!f.root.join("cancelled").exists());assert!(!f.root.join("work-cancelled").exists());assert_eq!(fs::read(&f.input).unwrap(),original);
 }
 #[test] fn full_gpu_hook_broken_transport_returns_bounded_error_and_retains_live_files() {
  for (token,mode) in [(4,Mode::Broken),(5,Mode::Malformed),(6,Mode::UnknownTerminal)] {
   let f=Fixture::new(17,13,42);let(stop,cancels)=flags();let at=Instant::now();let request=f.request(token,Some(mode),stop.clone(),cancels.clone());
   let result=f.run("broken",None,Some(request),stop).unwrap_err();assert_eq!(result.code,"GPU_CALLBACK_UNAVAILABLE");assert!(at.elapsed()<Duration::from_secs(8));assert_eq!(cancels.load(Ordering::Relaxed),1);
   let stage=f.root.join(format!("gpu-stage-{token}"));for ch in 0..3{assert_eq!(fs::metadata(stage.join(format!("source-{ch}.f64le"))).unwrap().len(),17*13*8);}
   assert!(f.root.join("work-broken").is_dir());assert!(!f.root.join("broken").exists());
   assert!(fs::read_dir(f.root.join("work-broken")).unwrap().all(|p|!p.unwrap().file_name().to_string_lossy().contains("camera-diffusion")));
   // Test fixture teardown occurs only after this synthetic helper has ceased;
   // production Java's independent finally-drain owns that proof on this path.
  }
 }
 #[test] fn full_gpu_hook_checkpoint_identity_and_backend_cannot_cross_cpu_policy() {
  let f=Fixture::new(17,13,42);let(stop,cancels)=flags();let cpu_cp=f.root.join("cpu-checkpoint");f.run("cpu",Some(&cpu_cp),None,stop.clone()).unwrap();
  let request=f.request(7,None,stop.clone(),cancels.clone());assert_eq!(f.run("cpu-to-gpu",Some(&cpu_cp),Some(request),stop.clone()).unwrap_err().code,"CHECKPOINT_INVALID");
  let gpu_cp=f.root.join("gpu-checkpoint");let request=f.request(8,Some(Mode::Mirror),stop.clone(),cancels.clone());let gpu=f.run("gpu",Some(&gpu_cp),Some(request),stop.clone()).unwrap();assert_eq!(gpu.gpu_diffusion.backend,"gpu_f32_v1");
  assert_eq!(f.run("gpu-to-cpu",Some(&gpu_cp),None,stop.clone()).unwrap_err().code,"CHECKPOINT_INVALID");
  let request=f.request(9,None,stop.clone(),cancels.clone());let resumed=f.run("gpu-resumed",Some(&gpu_cp),Some(request),stop.clone()).unwrap();assert!(resumed.gpu_diffusion.resumed);assert_eq!(resumed.gpu_diffusion.backend,"gpu_f32_v1");assert!(!resumed.gpu_diffusion.attempted);assert_eq!(fs::read(f.root.join("gpu")).unwrap(),fs::read(f.root.join("gpu-resumed")).unwrap());
  let mut manifest:serde_json::Value=serde_json::from_slice(&fs::read(gpu_cp.join("manifest.json")).unwrap()).unwrap();manifest.as_object_mut().unwrap().remove("camera_diffusion_backend");fs::write(gpu_cp.join("manifest.json"),serde_json::to_vec(&manifest).unwrap()).unwrap();
  let request=f.request(10,None,stop.clone(),cancels.clone());assert_eq!(f.run("missing-provenance",Some(&gpu_cp),Some(request),stop.clone()).unwrap_err().code,"CHECKPOINT_INVALID");
  let fallback_cp=f.root.join("fallback-checkpoint");let request=f.request(11,None,stop.clone(),cancels.clone());let fallback=f.run("fallback",Some(&fallback_cp),Some(request),stop.clone()).unwrap();assert_eq!(fallback.gpu_diffusion.fallback_reason,"delegate_unavailable");
  let request=f.request(12,None,stop.clone(),cancels);let resumed=f.run("fallback-resumed",Some(&fallback_cp),Some(request),stop).unwrap();assert!(resumed.gpu_diffusion.resumed);assert_eq!(resumed.gpu_diffusion.backend,"cpu_f64");
 }
 #[test] fn full_gpu_hook_host_f32_mirror_final_image_error_is_measured_not_gpu_benchmarked() {
  let results=std::env::var_os("FILM_GPU_QUALITY_RESULTS").map(PathBuf::from).unwrap_or_else(||std::env::temp_dir().join(format!("filmroom-host-f32-final-quality-{}",std::process::id())));fs::create_dir_all(&results).unwrap();
  let mut reports=Vec::new();
  for (index,(w,h,seed)) in [(17,13,42),(47,29,7),(65,33,19)].into_iter().enumerate() {
   let f=Fixture::new(w,h,seed);let(stop,cancels)=flags();f.run("cpu",None,None,stop.clone()).unwrap();
   let request=f.request(30+index as u64,Some(if index==0{Mode::Uncertain}else{Mode::Mirror}),stop.clone(),cancels);let gpu=f.run("mirror",None,Some(request),stop).unwrap();
   assert_eq!(gpu.gpu_diffusion.backend,"gpu_f32_v1");assert!(gpu.gpu_diffusion.attempted);assert_eq!(gpu.gpu_diffusion.gpu_stage_ms,None);assert_eq!(gpu.gpu_probe_capture.status,"disabled");
   let cpu=fs::read(f.root.join("cpu")).unwrap();let mirror=fs::read(f.root.join("mirror")).unwrap();assert_eq!(cpu.len(),mirror.len());
   let mut errors=Vec::new();let mut peak=0f64;let mut sum=0f64;let mut changed=0u64;
   for (a,b) in cpu.chunks_exact(4).zip(mirror.chunks_exact(4)) {let a=f32::from_le_bytes(a.try_into().unwrap())as f64;let b=f32::from_le_bytes(b.try_into().unwrap())as f64;assert!(a.is_finite()&&b.is_finite());let d=(a-b).abs();errors.push(d);sum+=d*d;peak=peak.max(a.abs());if d!=0.{changed+=1;}}
   errors.sort_by(f64::total_cmp);let percentile=|p:f64|errors[((errors.len()-1)as f64*p).ceil()as usize];
   let prefix=format!("fixture-{index}-{w}x{h}-seed-{seed}");fs::write(results.join(format!("{prefix}-cpu.f32le")),&cpu).unwrap();fs::write(results.join(format!("{prefix}-host-f32-stage.f32le")),&mirror).unwrap();
   let report=serde_json::json!({"testOnly":true,"actualGpuMeasured":false,"width":w,"height":h,"seed":seed,"downstream":"unchanged halation, DIR, seeded grain and printing/scanning","samples":errors.len(),"changedSamples":changed,"maxAbs":errors.last(),"rms":(sum/errors.len()as f64).sqrt(),"p50":percentile(0.5),"p95":percentile(0.95),"p99":percentile(0.99),"p999":percentile(0.999),"referencePeak":peak,"maxAbsOverPeak":errors.last().unwrap()/peak.max(f64::MIN_POSITIVE),"cpuSha256":format!("{:x}",Sha256::digest(&cpu)),"hostF32StageSha256":format!("{:x}",Sha256::digest(&mirror))});
   eprintln!("HOST_F32_FINAL_IMAGE {report}");reports.push(report);
  }
  fs::write(results.join("final-image-quality.json"),serde_json::to_vec_pretty(&reports).unwrap()).unwrap();
 }
}

#[cfg(all(test,feature="precision-f64",feature="diffusion-research"))]
mod gpu_numeric_stage_hook_tests {
 use super::*;
 use std::sync::{Arc,atomic::{AtomicBool,AtomicUsize,Ordering}};
 #[derive(Clone,Copy)] enum Mode {Mirror,Reject,InvalidOutput,CancelReady,Uncertain,Broken}
 struct Fake {mode:Mode,token:u64,total:u64,summary:Option<serde_json::Value>,polls:usize,stop:Arc<AtomicBool>,cancels:Arc<AtomicUsize>}
 impl GpuDiffusionDelegate for Fake {
  fn available_disk_bytes(&mut self)->Option<u64>{Some(u64::MAX)}
  fn begin(&mut self,wire:&str)->Option<bool>{
   let v:serde_json::Value=serde_json::from_str(wire).unwrap();self.token=v["token"].as_u64().unwrap();self.total=v["totalPixels"].as_u64().unwrap();
   if matches!(self.mode,Mode::Reject){return Some(false);}
   if matches!(self.mode,Mode::Broken){return Some(true);}
   let dir=Path::new(v["jobDir"].as_str().unwrap());let sha=v["manifestSha256"].as_str().unwrap();
   self.summary=Some(match v["operation"].as_str().unwrap_or("fullCameraDiffusion"){
    "fullCameraDiffusion"=>crate::research_diffusion::full_stage::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullPrintScanLogXyz"=>crate::research_finish::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullFilmOptics"=>crate::research_spatial::full_stage::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullFilmExposure"=>crate::research_exposure::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullFilmDirDensity"=>crate::research_dir::full_stage::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullAutoExposure"|"fullHighlightBoost"|"fullExposureLog"=>crate::research_global::full_stage::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullFilmGrain"=>crate::research_grain::full_stage::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullPrintExposure"=>crate::research_finish::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullPrintDiffusion"=>crate::research_diffusion::full_stage::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullScanTail"=>crate::research_finish_tail::full_stage::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    "fullFinishTail"=>crate::research_finish::fused::run_stage_cpu_mirror_for_test(dir,self.token,sha,||false).unwrap(),
    other=>panic!("Unexpected numeric operation {other}"),
   });
   if matches!(self.mode,Mode::InvalidOutput){
    let path=match v["operation"].as_str().unwrap_or("fullCameraDiffusion"){"fullCameraDiffusion"=>crate::research_diffusion::full_stage::validate_outputs(dir,self.token,sha,||false).unwrap()[2].clone(),"fullPrintScanLogXyz"=>crate::research_finish::validate_output(dir,self.token,sha,||false).unwrap(),"fullFilmOptics"=>crate::research_spatial::full_stage::validate_outputs(dir,self.token,sha,||false).unwrap()[2].clone(),"fullFilmExposure"=>crate::research_exposure::validate_outputs(dir,self.token,sha,||false).unwrap()[2].clone(),"fullFilmDirDensity"=>crate::research_dir::full_stage::validate_outputs(dir,self.token,sha,||false).unwrap()[2].clone(),_=>unreachable!()};
    fs::write(path,[0u8;8]).unwrap();
   }
   if matches!(self.mode,Mode::CancelReady){self.stop.store(true,Ordering::Release);}
   if matches!(self.mode,Mode::Uncertain){None}else{Some(true)}
  }
  fn poll(&mut self,_:u64)->Option<String>{
   self.polls+=1;if matches!(self.mode,Mode::Broken)||(matches!(self.mode,Mode::Uncertain)&&self.polls==1){return None;}
   Some(serde_json::json!({"schemaVersion":1,"token":self.token,"status":"success_stopped","completedPixels":self.total,"totalPixels":self.total,"code":"COMPLETE","summary":self.summary}).to_string())
  }
  fn cancel(&mut self,_:u64){self.cancels.fetch_add(1,Ordering::Relaxed);}
 }
 struct Fixture{root:PathBuf,input:PathBuf,params:RuntimeParams,width:u32,height:u32,film:&'static str,paper:&'static str}
 impl Fixture {
  fn new()->Self {Self::sized(17,13,42,"kodak_portra_400","kodak_portra_endura",false)}
  fn sized(width:u32,height:u32,seed:u64,film:&'static str,paper:&'static str,hdr:bool)->Self {
   let root=std::env::temp_dir().join(format!("gpu-numeric-hook-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));fs::create_dir(&root).unwrap();let root=root.canonicalize().unwrap();let input=root.join("input");
   fs::write(&input,(0..width*height*3).flat_map(|i|{let x=(i/3)%width;let y=(i/3)/width;let ch=i%3;
    let v=if !hdr{(i%107)as f32/107.}else if (x+7*y)%53==0{6.}else if x==0||y==height-1{-0.04*(ch+1)as f32}else{((x*17+y*31+ch*53+seed as u32)%1009)as f32/1009.};v.to_le_bytes()
   }).collect::<Vec<_>>()).unwrap();
   let mut params=RuntimeParams::default();params.io.input_cctf_decoding=false;params.io.output_cctf_encoding=false;params.camera.pixel_size_um_override=Some(10.);params.film_render.halation.active=false;params.camera.lens_blur_um=5.;params.film_render.grain.seed=seed;
   Self{root,input,params,width,height,film,paper}
  }
  fn request(&self,token:u64,mode:Option<Mode>,stop:Arc<AtomicBool>,cancels:Arc<AtomicUsize>)->GpuDiffusionRequest {
   GpuDiffusionRequest{parent_handle:1,token,stage_dir:Some(self.root.join(format!("gpu-stage-{token}"))),delegate:mode.map(|mode|Box::new(Fake{mode,token,total:0,summary:None,polls:0,stop,cancels})as Box<dyn GpuDiffusionDelegate>)}
  }
  fn run(&self,name:&str,params:RuntimeParams,checkpoint:Option<&Path>,scanner:Option<GpuDiffusionRequest>,optical:Option<GpuDiffusionRequest>,stop:Arc<AtomicBool>)->Result<StreamedMetrics>{
   self.run_requests(name,params,checkpoint,GpuStageRequests{scanner,optical,..GpuStageRequests::default()},stop)
  }
  fn run_requests(&self,name:&str,params:RuntimeParams,checkpoint:Option<&Path>,requests:GpuStageRequests,stop:Arc<AtomicBool>)->Result<StreamedMetrics>{
   self.run_requests_with_legacy_gpu(name,params,checkpoint,requests,stop,false)
  }
  fn run_requests_with_legacy_gpu(&self,name:&str,params:RuntimeParams,checkpoint:Option<&Path>,requests:GpuStageRequests,stop:Arc<AtomicBool>,use_gpu:bool)->Result<StreamedMetrics>{
   let execution=crate::export_execution::ExportPermit::acquire(||false).unwrap();let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
   execution.run(||export_file_checkpointed_managed_with_gpu_delegates(&execution,&data,self.film,self.paper,params,&self.input,&self.root.join(name),&self.root.join(format!("work-{name}")),checkpoint,self.width,self.height,384,0.,use_gpu,ExportOptimizations{gpu_resource_reuse:use_gpu,..ExportOptimizations::default()},None,requests,||stop.load(Ordering::Acquire),|_|{}))
  }
 }
 impl Drop for Fixture{fn drop(&mut self){let _=fs::remove_dir_all(&self.root);}}
 fn flags()->(Arc<AtomicBool>,Arc<AtomicUsize>){(Arc::new(AtomicBool::new(false)),Arc::new(AtomicUsize::new(0)))}
 #[test] fn numeric_helpers_reject_partial_output_and_preserve_exact_cpu_fallback() {
  let f=Fixture::new();let(stop,cancels)=flags();f.run("baseline",f.params.clone(),None,None,None,stop.clone()).unwrap();let baseline=fs::read(f.root.join("baseline")).unwrap();
  for scanner in [true,false]{for (i,mode,reason) in [(1,Mode::Reject,"not_started"),(2,Mode::InvalidOutput,"invalid_output")] {
   let token=i+if scanner{0}else{10};let request=f.request(token,Some(mode),stop.clone(),cancels.clone());let name=format!("fallback-{token}");
   let (scan,opt)=if scanner{(Some(request),None)}else{(None,Some(request))};let result=f.run(&name,f.params.clone(),None,scan,opt,stop.clone()).unwrap();
   let (backend,fallback)=if scanner{(result.gpu_scanner.backend,result.gpu_scanner.fallback_reason)}else{(result.gpu_optical.backend,result.gpu_optical.fallback_reason)};
   assert_eq!(backend,"cpu_f64");assert_eq!(fallback,reason);assert_eq!(fs::read(f.root.join(name)).unwrap(),baseline);assert!(!f.root.join(format!("gpu-stage-{token}")).exists());
  }}
 }
 #[test] fn numeric_helper_ready_cancellation_never_falls_back_or_publishes() {
  for scanner in [true,false]{let f=Fixture::new();let(stop,cancels)=flags();let original=fs::read(&f.input).unwrap();let request=f.request(20,Some(Mode::CancelReady),stop.clone(),cancels.clone());
   let(scan,opt)=if scanner{(Some(request),None)}else{(None,Some(request))};let result=f.run("cancelled",f.params.clone(),None,scan,opt,stop).unwrap_err();
   assert_eq!(result.code,"CANCELLED");assert_eq!(cancels.load(Ordering::Acquire),1);assert!(!f.root.join("cancelled").exists());assert!(!f.root.join("work-cancelled").exists());assert!(!f.root.join("gpu-stage-20").exists());assert_eq!(fs::read(&f.input).unwrap(),original);
  }
 }
 #[test] fn numeric_helpers_resume_actual_backend_and_reject_changed_policy() {
  let f=Fixture::new();let(stop,cancels)=flags();let checkpoint=f.root.join("checkpoint");
  let scan=f.request(30,Some(Mode::Uncertain),stop.clone(),cancels.clone());let optics=f.request(31,Some(Mode::Mirror),stop.clone(),cancels.clone());
  let first=f.run("first",f.params.clone(),Some(&checkpoint),Some(scan),Some(optics),stop.clone()).unwrap();
  assert_eq!(first.gpu_scanner.backend,"gpu_pchip_f32_v1");assert_eq!(first.gpu_optical.backend,"gpu_spatial_f32_v1");assert_eq!(first.gpu_scanner.gpu_stage_ms,None);assert_eq!(first.gpu_optical.gpu_stage_ms,None);
  let scan=f.request(32,None,stop.clone(),cancels.clone());let optics=f.request(33,None,stop.clone(),cancels.clone());let resumed=f.run("resumed",f.params.clone(),Some(&checkpoint),Some(scan),Some(optics),stop.clone()).unwrap();
  assert_eq!(resumed.checkpoint_resume_stage,6);assert!(resumed.gpu_scanner.resumed&&resumed.gpu_optical.resumed);assert!(!resumed.gpu_scanner.attempted&&!resumed.gpu_optical.attempted);assert_eq!(resumed.gpu_scanner.backend,"gpu_pchip_f32_v1");assert_eq!(resumed.gpu_optical.backend,"gpu_spatial_f32_v1");assert_eq!(fs::read(f.root.join("first")).unwrap(),fs::read(f.root.join("resumed")).unwrap());
  let scan=f.request(34,None,stop.clone(),cancels);assert_eq!(f.run("policy-mismatch",f.params.clone(),Some(&checkpoint),Some(scan),None,stop).unwrap_err().code,"CHECKPOINT_INVALID");
 }
 #[test] fn numeric_helper_uncertain_transport_retains_sources_until_external_drain() {
  let f=Fixture::new();let(stop,cancels)=flags();let request=f.request(40,Some(Mode::Broken),stop.clone(),cancels.clone());
  let result=f.run("broken",f.params.clone(),None,Some(request),None,stop).unwrap_err();assert_eq!(result.code,"GPU_CALLBACK_UNAVAILABLE");assert_eq!(cancels.load(Ordering::Acquire),1);assert!(!f.root.join("broken").exists());assert!(f.root.join("work-broken").exists());assert!(f.root.join("gpu-stage-40").exists());
 }
 #[test] fn scanner_full_image_hook_supports_film_scan_and_print_diffusion_routes() {
  for (token,film_scan,print_diffusion) in [(50,false,false),(51,true,false),(52,false,true)] {
   let f=Fixture::new();let(stop,cancels)=flags();let mut params=f.params.clone();params.io.scan_film=film_scan;params.enlarger.diffusion_filter.active=print_diffusion;params.enlarger.diffusion_filter.filter_family="black_pro_mist".into();params.film_render.glare.active=true;params.film_render.glare.percent=0.2;params.print_render.glare.active=true;params.print_render.glare.percent=0.2;params.scanner.lens_blur=0.4;params.scanner.unsharp_mask=[0.7,0.3];
   let request=f.request(token,Some(Mode::Mirror),stop.clone(),cancels);let result=f.run("routes",params,None,Some(request),None,stop).unwrap();assert_eq!(result.gpu_scanner.backend,"gpu_pchip_f32_v1");
   let output=fs::read(f.root.join("routes")).unwrap();assert_eq!(output.len(),17*13*12);assert!(output.chunks_exact(4).all(|b|f32::from_le_bytes(b.try_into().unwrap()).is_finite()));
  }
 }
 #[test] fn exposure_fallback_preserves_exact_output_and_global_metering() {
  let f=Fixture::new();let(stop,cancels)=flags();let mut params=f.params.clone();params.camera.auto_exposure=true;params.camera.exposure_compensation_ev=0.7;
  let base=f.run_requests("ae-cpu",params.clone(),None,GpuStageRequests::default(),stop.clone()).unwrap();let baseline=fs::read(f.root.join("ae-cpu")).unwrap();
  for (token,mode,reason) in [(60,Mode::Reject,"not_started"),(61,Mode::InvalidOutput,"invalid_output")] {
   let request=f.request(token,Some(mode),stop.clone(),cancels.clone());let name=format!("ae-fallback-{token}");
   let result=f.run_requests(&name,params.clone(),None,GpuStageRequests{exposure:Some(request),..GpuStageRequests::default()},stop.clone()).unwrap();
   assert_eq!(result.gpu_exposure.backend,"cpu_f64");assert_eq!(result.gpu_exposure.fallback_reason,reason);assert_eq!(result.metered_ev,base.metered_ev);assert_eq!(fs::read(f.root.join(name)).unwrap(),baseline);
  }
 }
 #[test] fn exposure_checkpoint_resume_preserves_actual_backend_and_metering() {
  let f=Fixture::new();let(stop,cancels)=flags();let mut params=f.params.clone();params.camera.auto_exposure=true;let checkpoint=f.root.join("exposure-checkpoint");
  let request=f.request(62,Some(Mode::Mirror),stop.clone(),cancels.clone());let first=f.run_requests("exposure-first",params.clone(),Some(&checkpoint),GpuStageRequests{exposure:Some(request),..GpuStageRequests::default()},stop.clone()).unwrap();
  assert_eq!(first.gpu_exposure.backend,"gpu_exposure_f32_v1");assert_eq!(first.gpu_exposure.gpu_stage_ms,None);
  let request=f.request(63,None,stop.clone(),cancels);let resumed=f.run_requests("exposure-resumed",params.clone(),Some(&checkpoint),GpuStageRequests{exposure:Some(request),..GpuStageRequests::default()},stop.clone()).unwrap();
  assert!(resumed.gpu_exposure.resumed&&!resumed.gpu_exposure.attempted);assert_eq!(resumed.gpu_exposure.backend,"gpu_exposure_f32_v1");assert_eq!(resumed.metered_ev,first.metered_ev);assert_eq!(fs::read(f.root.join("exposure-first")).unwrap(),fs::read(f.root.join("exposure-resumed")).unwrap());
  assert_eq!(f.run_requests("exposure-policy-mismatch",params,Some(&checkpoint),GpuStageRequests::default(),stop).unwrap_err().code,"CHECKPOINT_INVALID");
 }

 #[test] fn direct_native_experiments_disable_legacy_density_delegate_and_reuse() {
  let f=Fixture::new();let(stop,cancels)=flags();
  for (i,key) in ["gpuDiffusionExperimental","gpuScannerExperimental","gpuOpticalExperimental","gpuExposureExperimental","gpuDirExperimental"].into_iter().enumerate(){
   let params=crate::mobile::MobileParams::parse(&format!(r#"{{"{key}":true,"useGpu":true,"gpuResourceReuse":true}}"#)).unwrap();assert!(params.use_gpu&&params.gpu_resource_reuse);
   let mut requests=GpuStageRequests::default();let request=Some(f.request(70+i as u64,None,stop.clone(),cancels.clone()));
   match key{"gpuDiffusionExperimental"=>requests.diffusion=request,"gpuScannerExperimental"=>requests.scanner=request,"gpuOpticalExperimental"=>requests.optical=request,"gpuExposureExperimental"=>requests.exposure=request,"gpuDirExperimental"=>requests.dir=request,_=>unreachable!()}
   let result=f.run_requests_with_legacy_gpu(&format!("legacy-blocked-{i}"),f.params.clone(),None,requests,stop.clone(),true).unwrap();
   assert!(!result.gpu.requested);assert!(!result.gpu_resource_reuse);assert_eq!(result.gpu_diffusion.backend,"cpu_f64");assert_eq!(result.gpu_scanner.backend,"cpu_f64");assert_eq!(result.gpu_optical.backend,"cpu_f64");assert_eq!(result.gpu_exposure.backend,"cpu_f64");assert_eq!(result.gpu_dir.backend,"cpu_f64");
  }
 }

 fn all_requests(f:&Fixture,base:u64,mode:Option<Mode>,stop:&Arc<AtomicBool>,cancels:&Arc<AtomicUsize>)->GpuStageRequests {
  GpuStageRequests{
   route:ScienceRoute::Export,
   science:ScienceRequests::new(),
   exposure:Some(f.request(base,mode,stop.clone(),cancels.clone())),
   diffusion:Some(f.request(base+1,mode,stop.clone(),cancels.clone())),
   optical:Some(f.request(base+2,mode,stop.clone(),cancels.clone())),
   dir:Some(f.request(base+3,mode,stop.clone(),cancels.clone())),
   scanner:Some(f.request(base+4,mode,stop.clone(),cancels.clone())),
  }
 }
 // This is a same-input full-output integration oracle, not a GPU benchmark.
 #[test] fn combined_scientific_pipeline_routes_preview_detail_and_export_without_duplicate_tail() {
  use sha2::{Digest,Sha256};
  let out=std::env::var_os("FILM_GPU_QUALITY_RESULTS").map(PathBuf::from).unwrap_or_else(||std::env::temp_dir().join(format!("filmroom-opt7-science-quality-{}",std::process::id())));
  fs::create_dir_all(&out).unwrap();let mut records=Vec::new();
  for (case,route,film_scan,print_diffusion) in [(0,ScienceRoute::Export,false,false),(1,ScienceRoute::Preview,false,true),(2,ScienceRoute::Detail,true,false)] {
   let f=Fixture::sized(17,13,42,"kodak_portra_400","kodak_portra_endura",false);let(stop,cancels)=flags();
   let original=fs::read(&f.input).unwrap();let mut params=f.params.clone();params.camera.auto_exposure=true;
   params.io.scan_film=film_scan;params.enlarger.diffusion_filter.active=print_diffusion;params.enlarger.diffusion_filter.filter_family="glimmerglass".into();
   params.print_render.glare.active=true;params.print_render.glare.percent=0.3;params.print_render.glare.blur=0.6;params.scanner.lens_blur=0.4;params.scanner.unsharp_mask=[0.7,0.3];
   let baseline=f.run_requests("science-cpu",params.clone(),None,GpuStageRequests{route,..GpuStageRequests::default()},stop.clone()).unwrap();
   let mut req=all_requests(&f,300,Some(Mode::Mirror),&stop,&cancels);req.route=route;
   for(i,k)in ScienceKind::ALL.into_iter().enumerate(){req.science.insert(k,f.request(320+i as u64,Some(Mode::Mirror),stop.clone(),cancels.clone()));}
   let m=f.run_requests("science-mirror",params,None,req,stop.clone()).unwrap();
   for k in [ScienceKind::AutoExposure,ScienceKind::ExposureLog,ScienceKind::Grain,ScienceKind::FinishTail]{let v=&m.gpu_science[&k];assert_eq!(v.backend,k.backend(),"case {case}, {k:?}: {}",v.fallback_reason);}
   assert_eq!(m.gpu_scanner.fallback_reason,"included_in_resident");assert_eq!(m.gpu_science[&ScienceKind::ScanTail].fallback_reason,"included_in_resident");
   assert_eq!(m.grain_mode,baseline.grain_mode);assert_eq!(fs::read(&f.input).unwrap(),original);
   let a=fs::read(f.root.join("science-cpu")).unwrap();let b=fs::read(f.root.join("science-mirror")).unwrap();assert_eq!(a.len(),b.len());
   let(mut max,mut square)=(0f64,0f64);for(x,y)in a.chunks_exact(4).zip(b.chunks_exact(4)){let x=f32::from_le_bytes(x.try_into().unwrap())as f64;let y=f32::from_le_bytes(y.try_into().unwrap())as f64;assert!(x.is_finite()&&y.is_finite());let e=(x-y).abs();max=max.max(e);square+=e*e;}
   let rms=(square/(a.len()/4)as f64).sqrt();assert!(max<=0.01&&rms<=0.002,"case {case}: max {max}, rms {rms}");
   records.push(serde_json::json!({"case":case,"route":format!("{route:?}"),"hostMirrorOnly":true,"actualGpuExecuted":false,"maxAbs":max,"rms":rms,"cpuSha256":format!("{:x}",Sha256::digest(&a)),"candidateSha256":format!("{:x}",Sha256::digest(&b)),"science":m.gpu_science}));
  }
  fs::write(out.join("combined-science-final-images.json"),serde_json::to_vec_pretty(&records).unwrap()).unwrap();
 }
 #[test] fn dir_partial_output_and_ready_cancel_preserve_whole_stage_fallback() {
  let f=Fixture::new();let(stop,cancels)=flags();let mut params=f.params.clone();params.film_render.dir_couplers.diffusion_size_um=5.;params.film_render.dir_couplers.diffusion_tail_um=2.;
  f.run_requests("dir-cpu",params.clone(),None,GpuStageRequests::default(),stop.clone()).unwrap();let original=fs::read(f.root.join("dir-cpu")).unwrap();
  for(token,mode,reason)in[(80,Mode::Reject,"not_started"),(81,Mode::InvalidOutput,"invalid_output")]{
   let req=f.request(token,Some(mode),stop.clone(),cancels.clone());let name=format!("dir-fallback-{token}");let result=f.run_requests(&name,params.clone(),None,GpuStageRequests{dir:Some(req),..GpuStageRequests::default()},stop.clone()).unwrap();assert_eq!(result.gpu_dir.backend,"cpu_f64");assert_eq!(result.gpu_dir.fallback_reason,reason);assert_eq!(fs::read(f.root.join(name)).unwrap(),original);
  }
  let req=f.request(82,Some(Mode::CancelReady),stop.clone(),cancels.clone());assert_eq!(f.run_requests("dir-cancelled",params,None,GpuStageRequests{dir:Some(req),..GpuStageRequests::default()},stop).unwrap_err().code,"CANCELLED");assert!(!f.root.join("dir-cancelled").exists());assert!(!f.root.join("work-dir-cancelled").exists());assert!(!f.root.join("gpu-stage-82").exists());
 }
 #[test] fn combined_five_stage_host_mirrors_measure_complete_final_images() {
  use sha2::{Digest,Sha256};
  fn run_captured(f:&Fixture,name:&str,params:RuntimeParams,checkpoint:Option<&Path>,requests:GpuStageRequests,stop:Arc<AtomicBool>,errno:Option<i32>)->(Result<StreamedMetrics>,Vec<crate::gpu_stage_staging::SnapshotSummary>){
   let execution=crate::export_execution::ExportPermit::acquire(||false).unwrap();let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
   // Install fault injection INSIDE the export worker. Thread-local hooks around
   // pool.install would silently miss every production prepare on pooled hosts.
   execution.run(||crate::gpu_stage_staging::with_test_snapshot_summaries(||{
    let work=||export_file_checkpointed_managed_with_gpu_delegates(&execution,&data,f.film,f.paper,params,&f.input,&f.root.join(name),&f.root.join(format!("work-{name}")),checkpoint,f.width,f.height,384,0.,false,ExportOptimizations::default(),None,requests,||stop.load(Ordering::Acquire),|_|{});
    if let Some(errno)=errno {crate::gpu_stage_staging::with_test_link_error(errno,work)}else{work()}
   }))
  }
  let results=std::env::var_os("FILM_GPU_QUALITY_RESULTS").map(PathBuf::from).unwrap_or_else(||std::env::temp_dir().join(format!("filmroom-opt7-final-quality-{}",std::process::id()))).join("combined-five-stage");fs::create_dir_all(&results).unwrap();
  let mut reports=Vec::new();
  for (index,(width,height,seed,film,film_scan,print_diffusion,hdr)) in [(17,13,42,"kodak_portra_400",false,false,false),(47,29,7,"kodak_ektachrome_100",true,false,true),(65,33,19,"kodak_portra_400",false,true,true)].into_iter().enumerate(){
   let f=Fixture::sized(width,height,seed,film,"kodak_portra_endura",hdr);let(stop,cancels)=flags();let original_input=fs::read(&f.input).unwrap();let mut params=f.params.clone();params.io.scan_film=film_scan;params.camera.auto_exposure=true;params.camera.diffusion_filter.active=true;params.camera.diffusion_filter.filter_family="black_pro_mist".into();params.camera.diffusion_filter.strength=0.5;
   // Controlled original physical parameters exercise every full scientific
   // branch while staying inside the admitted f32 FIR/IIR numeric regimes.
   params.film_render.halation.active=true;params.film_render.halation.scatter_core_um=[5.,6.,7.];params.film_render.halation.scatter_tail_um=[2.,2.5,3.];params.film_render.halation.scatter_amount=0.4;params.film_render.halation.halation_first_sigma_um=[6.,7.,8.];params.film_render.halation.halation_n_bounces=2;
   params.film_render.dir_couplers.active=true;params.film_render.dir_couplers.diffusion_size_um=5.;params.film_render.dir_couplers.diffusion_tail_um=2.;
   params.enlarger.diffusion_filter.active=print_diffusion;params.enlarger.diffusion_filter.filter_family="glimmerglass".into();
   if print_diffusion{params.print_render.glare.active=true;params.print_render.glare.percent=0.3;params.print_render.glare.blur=0.6;params.scanner.lens_blur=0.4;params.scanner.unsharp_mask=[0.7,0.3];}
   let cpu=f.run_requests("all-cpu",params.clone(),None,GpuStageRequests::default(),stop.clone()).unwrap();
   let checkpoint=f.root.join("combined-checkpoint");let (first,linked_snapshots)=run_captured(&f,"all-mirror",params.clone(),Some(&checkpoint),all_requests(&f,100,Some(Mode::Mirror),&stop,&cancels),stop.clone(),None);let first=first.unwrap();
   assert_eq!(linked_snapshots.len(),5);assert_eq!(linked_snapshots.iter().map(|s|s.linked_planes as u64).sum::<u64>(),13);assert!(linked_snapshots.iter().all(|s|s.copied_planes==0&&s.copied_bytes==0));
   assert_eq!(first.gpu_exposure.backend,"gpu_exposure_f32_v1");assert_eq!(first.gpu_diffusion.backend,"gpu_f32_v1");assert_eq!(first.gpu_optical.backend,"gpu_spatial_f32_v1");assert_eq!(first.gpu_dir.backend,"gpu_dir_f32_v1");assert_eq!(first.gpu_scanner.backend,"gpu_pchip_f32_v1");
   assert!(first.gpu_exposure.attempted&&first.gpu_diffusion.attempted&&first.gpu_optical.attempted&&first.gpu_dir.attempted&&first.gpu_scanner.attempted);
   assert!(first.gpu_exposure.gpu_stage_ms.is_none()&&first.gpu_diffusion.gpu_stage_ms.is_none()&&first.gpu_optical.gpu_stage_ms.is_none()&&first.gpu_dir.gpu_stage_ms.is_none()&&first.gpu_scanner.gpu_stage_ms.is_none());assert_eq!(first.metered_ev,cpu.metered_ev);
   let baseline=fs::read(f.root.join("all-cpu")).unwrap();let actual=fs::read(f.root.join("all-mirror")).unwrap();assert_eq!(baseline.len(),width as usize*height as usize*12);assert_eq!(baseline.len(),actual.len());
   // Same input, scientific parameters, seed, and flags. Force the exact phone
   // failure at the real link boundary for all five production prepare jobs.
   let (copied, snapshots)=run_captured(&f,"all-eacces-copy",params.clone(),None,all_requests(&f,140,Some(Mode::Mirror),&stop,&cancels),stop.clone(),Some(13));
   let copied=copied.unwrap();let copied_image=fs::read(f.root.join("all-eacces-copy")).unwrap();
   assert_eq!(snapshots.len(),5,"All five production prepares must actually snapshot their inputs");
   assert_eq!(snapshots.iter().map(|s|s.linked_planes as u64).sum::<u64>(),0);
   assert_eq!(snapshots.iter().map(|s|s.copied_planes as u64).sum::<u64>(),13);
   assert_eq!(snapshots.iter().filter(|s|s.copied_planes==1&&s.copied_bytes==width as u64*height as u64*12).count(),1);
   assert_eq!(snapshots.iter().filter(|s|s.copied_planes==3&&s.copied_bytes==width as u64*height as u64*24).count(),4);
   let copied_bytes=snapshots.iter().map(|s|s.copied_bytes).sum::<u64>();assert_eq!(copied_bytes,width as u64*height as u64*108);
   macro_rules! assert_copied_stage {($copy:expr,$linked:expr)=>{{
    let copy=$copy;let linked=$linked;
    assert!(copy.requested&&copy.attempted&&!copy.resumed);assert_eq!(copy.backend,linked.backend);assert_ne!(copy.backend,"cpu_f64");assert!(copy.prepare_error_code.is_none());assert_eq!(copy.fallback_reason,linked.fallback_reason);assert!(copy.gpu_stage_ms.is_none());
   }};}
   assert_copied_stage!(&copied.gpu_exposure,&first.gpu_exposure);assert_copied_stage!(&copied.gpu_diffusion,&first.gpu_diffusion);assert_copied_stage!(&copied.gpu_optical,&first.gpu_optical);assert_copied_stage!(&copied.gpu_dir,&first.gpu_dir);assert_copied_stage!(&copied.gpu_scanner,&first.gpu_scanner);
   assert_eq!(copied.metered_ev,first.metered_ev);assert_eq!(copied_image,actual,"Copy staging must be bit-identical to linked staging for the complete final image");
   let mut errors=Vec::with_capacity(baseline.len()/4);let mut squared=0.;let mut peak=0f64;let mut changed=0u64;
   for(a,b)in baseline.chunks_exact(4).zip(actual.chunks_exact(4)){let a=f32::from_le_bytes(a.try_into().unwrap())as f64;let b=f32::from_le_bytes(b.try_into().unwrap())as f64;assert!(a.is_finite()&&b.is_finite());let delta=(a-b).abs();errors.push(delta);squared+=delta*delta;peak=peak.max(a.abs());changed+=u64::from(delta!=0.);}
   errors.sort_by(f64::total_cmp);let percentile=|p:f64|errors[((errors.len()-1)as f64*p).ceil()as usize];let prefix=format!("fixture-{index}-{width}x{height}-seed-{seed}");
   fs::write(results.join(format!("{prefix}-cpu.f32le")),&baseline).unwrap();fs::write(results.join(format!("{prefix}-all-stage-host-mirror.f32le")),&actual).unwrap();fs::write(results.join(format!("{prefix}-all-stage-eacces-copy-host-mirror.f32le")),&copied_image).unwrap();
   fs::write(results.join(format!("{prefix}-params.json")),serde_json::to_vec_pretty(&params).unwrap()).unwrap();
   let report=serde_json::json!({"testOnly":true,"actualGpuMeasured":false,"width":width,"height":height,"seed":seed,"film":film,"route":if film_scan{"positive_film_scan"}else if print_diffusion{"negative_print_diffusion_glare_sharpen"}else{"negative_print"},"inputContainsHdrAndNegativeEdges":hdr,"allFiveStagesActuallyMirrored":true,"forcedLinkErrno":13,"allFiveEaccesCopyStagesActuallyMirrored":true,"sourceCopies":13,"sourceCopyBytes":copied_bytes,"copyStagingByteIdentical":true,"eaccesCopyHostMirrorSha256":format!("{:x}",Sha256::digest(&copied_image)),"cpuMeteredEv":cpu.metered_ev,"mirrorMeteredEv":first.metered_ev,"samples":errors.len(),"changedSamples":changed,"maxAbs":errors.last(),"rms":(squared/errors.len()as f64).sqrt(),"p50":percentile(0.5),"p95":percentile(0.95),"p99":percentile(0.99),"p999":percentile(0.999),"referencePeak":peak,"maxAbsOverPeak":errors.last().unwrap()/peak.max(f64::MIN_POSITIVE),"cpuSha256":format!("{:x}",Sha256::digest(&baseline)),"hostMirrorSha256":format!("{:x}",Sha256::digest(&actual)),"inputSha256":format!("{:x}",Sha256::digest(&original_input))});eprintln!("HOST_OPT7_EACCES_FIVE_STAGE_FINAL_IMAGE {report}");reports.push(report);
   let resumed=f.run_requests("all-resumed",params.clone(),Some(&checkpoint),all_requests(&f,110,None,&stop,&cancels),stop.clone()).unwrap();
   assert_eq!(resumed.checkpoint_resume_stage,6);assert_eq!(resumed.metered_ev,first.metered_ev);assert_eq!(resumed.gpu_exposure.backend,first.gpu_exposure.backend);assert_eq!(resumed.gpu_diffusion.backend,first.gpu_diffusion.backend);assert_eq!(resumed.gpu_optical.backend,first.gpu_optical.backend);assert_eq!(resumed.gpu_dir.backend,first.gpu_dir.backend);assert_eq!(resumed.gpu_scanner.backend,first.gpu_scanner.backend);assert!(!resumed.gpu_exposure.attempted&&!resumed.gpu_diffusion.attempted&&!resumed.gpu_optical.attempted&&!resumed.gpu_dir.attempted&&!resumed.gpu_scanner.attempted);assert_eq!(fs::read(f.root.join("all-resumed")).unwrap(),actual);
   if index==0{
    // A malformed third DIR plane cannot leak into a chain where every other
    // stage succeeded on the mirror path. Compare with a clean full DIR fallback.
    let mut rejected=all_requests(&f,120,Some(Mode::Mirror),&stop,&cancels);rejected.dir=Some(f.request(123,Some(Mode::Reject),stop.clone(),cancels.clone()));
    let a=f.run_requests("mixed-reject",params.clone(),None,rejected,stop.clone()).unwrap();
    let mut invalid=all_requests(&f,130,Some(Mode::Mirror),&stop,&cancels);invalid.dir=Some(f.request(133,Some(Mode::InvalidOutput),stop.clone(),cancels.clone()));
    let b=f.run_requests("mixed-invalid",params.clone(),None,invalid,stop.clone()).unwrap();assert_eq!(a.gpu_dir.backend,"cpu_f64");assert_eq!(b.gpu_dir.backend,"cpu_f64");assert_eq!(b.gpu_dir.fallback_reason,"invalid_output");assert_eq!(fs::read(f.root.join("mixed-reject")).unwrap(),fs::read(f.root.join("mixed-invalid")).unwrap());
   }
   assert_eq!(fs::read(&f.input).unwrap(),original_input);
  }
  fs::write(results.join("final-image-quality.json"),serde_json::to_vec_pretty(&reports).unwrap()).unwrap();
 }

}
