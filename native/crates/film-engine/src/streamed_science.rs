//! Closed orchestration for the additional scientific GPU stages. All outputs
//! stay private until the helper's terminal protocol confirms ownership ended.
use super::*;
use std::collections::BTreeMap;

#[derive(
    Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ScienceKind {
    AutoExposure,
    HighlightBoost,
    ExposureLog,
    Grain,
    PrintExposure,
    PrintDiffusion,
    ScanTail,
    FinishTail,
}
impl ScienceKind {
    pub fn operation(self) -> &'static str {
        match self {
            Self::AutoExposure => "fullAutoExposure",
            Self::HighlightBoost => "fullHighlightBoost",
            Self::ExposureLog => "fullExposureLog",
            Self::Grain => "fullFilmGrain",
            Self::PrintExposure => "fullPrintExposure",
            Self::PrintDiffusion => "fullPrintDiffusion",
            Self::ScanTail => "fullScanTail",
            Self::FinishTail => "fullFinishTail",
        }
    }
    pub fn checkpoint_stage(self) -> u8 {
        match self {
            Self::AutoExposure => 2,
            Self::HighlightBoost => 3,
            Self::ExposureLog => 4,
            Self::Grain => 5,
            _ => 6,
        }
    }
    pub fn backend(self) -> &'static str {
        match self {
            Self::AutoExposure => "gpu_meter_f32_v1",
            Self::HighlightBoost => "gpu_highlight_f32_v1",
            Self::ExposureLog => "gpu_log_f32_v1",
            Self::Grain => "gpu_grain_guarded64_spatial_f32_v2",
            Self::PrintExposure => "gpu_print_exposure_f32_v1",
            Self::PrintDiffusion => "gpu_print_fft_f32_v1",
            Self::ScanTail => "gpu_scan_tail_f32_v1",
            Self::FinishTail => "gpu_finish_tail_resident_f32_v1",
        }
    }
    pub const ALL: [Self; 8] = [
        Self::AutoExposure,
        Self::HighlightBoost,
        Self::ExposureLog,
        Self::Grain,
        Self::PrintExposure,
        Self::PrintDiffusion,
        Self::ScanTail,
        Self::FinishTail,
    ];
}
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq,serde::Serialize)]
pub(crate) enum ScienceRoute {#[default] Export,Preview,Detail}
pub(crate) type ScienceRequests = BTreeMap<ScienceKind, GpuDiffusionRequest>;
pub(crate) type ScienceDelegates = BTreeMap<ScienceKind, Box<dyn GpuDiffusionDelegate>>;

/// A projection of an already validated typed native plan, never an IPC plan.
pub(super) struct PlanMeta {
    pub digest: String,
    pub memory: u64,
    pub disk: u64,
    pub chunks: Option<u64>,
    pub passes: Option<u64>,
}
impl PlanMeta {
    pub fn from_plan(p: &impl Serialize) -> std::result::Result<Self, String> {
        let v = serde_json::to_value(p).map_err(|_| "GPU_SCIENCE_PLAN")?;
        let digest = v["manifestSha256"]
            .as_str()
            .filter(|s| {
                s.len() == 64
                    && s.bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            })
            .ok_or("GPU_SCIENCE_PLAN")?
            .to_owned();
        let memory = v["combinedPeakBytes"]
            .as_u64()
            .filter(|n| *n > 0 && *n <= 768 * 1024 * 1024)
            .ok_or("GPU_SCIENCE_PLAN")?;
        let disk = v["diskBytes"].as_u64().ok_or("GPU_SCIENCE_PLAN")?;
        Ok(Self {
            digest,
            memory,
            disk,
            chunks: v["chunkCount"].as_u64(),
            passes: v["passCount"].as_u64(),
        })
    }
}
pub(super) enum Candidate {
    Files(Vec<PathBuf>),
    Meter { ev: f32, scale: f32 },
}
pub(super) struct GpuScience {
    pub route: ScienceRoute,
    pub requests: ScienceRequests,
    pub metrics: BTreeMap<ScienceKind, GpuOpticalMetrics>,
}
impl GpuScience {
    pub fn new(
        requests: ScienceRequests,
        resume: u8,
        backends: &BTreeMap<ScienceKind, bool>,
    ) -> Self {
        let metrics = ScienceKind::ALL
            .into_iter()
            .map(|k| {
                let gpu = backends.get(&k).copied().unwrap_or(false);
                (
                    k,
                    GpuOpticalMetrics {
                        upload_bytes: None,
                        readback_bytes: None,
                        dispatches: None,
                        chunks: None,
                        prepare_error_code: None,
                        passes: None,
                        sample_count: None,
                        sample_max_abs: None,
                        sample_rms: None,
                        requested: requests.contains_key(&k),
                        backend: if gpu { k.backend() } else { "cpu_f64" },
                        attempted: false,
                        fallback_reason: "none",
                        resumed: resume >= k.checkpoint_stage(),
                        helper_total_ms: None,
                        gpu_stage_ms: None,
                        setup_ms: None,
                        combined_peak_bytes: None,
                    },
                )
            })
            .collect();
        Self { route:ScienceRoute::Export, requests, metrics }
    }
    pub fn requested(&self, k: ScienceKind) -> bool {
        self.requests.contains_key(&k)
    }
    pub fn gpu_used(&self, k: ScienceKind) -> bool {
        self.metrics[&k].backend == k.backend()
    }
    pub fn inactive(&mut self, k: ScienceKind) {
        if let Some(m) = self.metrics.get_mut(&k) {
            m.fallback_reason = "inactive";
        }
    }
    #[allow(clippy::too_many_arguments)]
    pub fn execute<P, V>(
        &mut self,
        k: ScienceKind,
        required_disk: u64,
        scratch: &mut Scratch,
        width: u32,
        height: u32,
        cancelled: &mut impl FnMut() -> bool,
        mut progress: impl FnMut(u64, u64),
        prepare: P,
        validate: V,
    ) -> Result<Option<Candidate>>
    where
        P: FnOnce(&Path, u64, &mut dyn FnMut() -> bool) -> std::result::Result<PlanMeta, String>,
        V: FnOnce(
            &Path,
            u64,
            &str,
            &serde_json::Value,
            &mut dyn FnMut() -> bool,
        ) -> std::result::Result<Candidate, String>,
    {
        let Some(request) = self.requests.get_mut(&k) else {
            return Ok(None);
        };
        let metrics = self.metrics.get_mut(&k).unwrap();
        let Some(delegate) = request.delegate.as_deref_mut() else {
            metrics.fallback_reason = "delegate_unavailable";
            return Ok(None);
        };
        let Some(dir) = request.stage_dir.as_ref() else {
            metrics.fallback_reason = "path_unavailable";
            return Ok(None);
        };
        if required_disk > delegate.available_disk_bytes().unwrap_or(0) {
            metrics.fallback_reason = "disk_unavailable";
            return Ok(None);
        }
        if !dir.parent().is_some_and(|root| {
            fs::symlink_metadata(root).is_ok_and(|m| m.is_dir() && !m.file_type().is_symlink())
                && root.canonicalize().ok().as_deref() == Some(root)
        }) {
            metrics.fallback_reason = "path_unavailable";
            return Ok(None);
        }
        cancel(cancelled)?;
        let setup = Instant::now();
        let planned = prepare(dir, request.token, cancelled);
        metrics.setup_ms = Some(setup.elapsed().as_secs_f64() * 1000.);
        let plan = match planned {
            Ok(v) => v,
            Err(code) => {
                cancel(cancelled)?;
                if code == "CANCELLED" {
                    return Err(error("CANCELLED", "GPU scientific preparation cancelled"));
                }
                metrics.prepare_error_code = crate::gpu_stage_staging::diagnostic_code(&code);
                metrics.fallback_reason = gpu_fallback_reason(&code, "prepare_failed");
                return Ok(None);
            }
        };
        let may_run = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let _directory = GpuStageDirectory::new(dir.clone(),may_run.clone(),request.parent_handle)?;
        scratch.gpu_may_run = Some(may_run.clone());
        if plan.disk > required_disk {
            metrics.fallback_reason = "disk_unavailable";
            return Ok(None);
        }
        metrics.chunks = plan.chunks;
        metrics.passes = plan.passes;
        metrics.combined_peak_bytes = Some(plan.memory);
        scratch.peak = scratch.peak.max(scratch.live()?.saturating_add(plan.disk));
        let total = width as u64 * height as u64 * 3;
        let wire=serde_json::json!({"schemaVersion":1,"operation":k.operation(),"token":request.token,"parentHandle":request.parent_handle,"jobDir":dir,"manifestSha256":plan.digest,"budgetBytes":plan.memory,"width":width,"height":height,"totalPixels":total}).to_string();
        let mut helper = GpuHelperOwner::new(delegate, request.token, total, may_run);
        let started = Instant::now();
        if !helper.begin(&wire) {
            metrics.fallback_reason = helper.poll().map_or("not_started", |p| {
                gpu_fallback_reason(&p.code, "not_started")
            });
            return Ok(None);
        }
        metrics.attempted = true;
        let mut was_cancelled = false;
        let mut completed = 0;
        let mut transport_errors = 0;
        let terminal = loop {
            if cancelled() {
                was_cancelled = true;
                helper.cancel_once();
            }
            if let Some(poll) = helper.poll() {
                transport_errors = 0;
                completed = completed.max(poll.completed);
                progress(completed, total);
                if poll.terminal.is_some() {
                    break poll;
                }
            } else {
                transport_errors += 1;
                if transport_errors >= GPU_TRANSPORT_ERROR_LIMIT {
                    helper.cancel_once();
                    return Err(error(
                        if was_cancelled {
                            "CANCELLED"
                        } else {
                            "GPU_CALLBACK_UNAVAILABLE"
                        },
                        "Scientific GPU helper ownership retained",
                    ));
                }
            }
            std::thread::sleep(Duration::from_millis(50));
        };
        metrics.helper_total_ms = Some(started.elapsed().as_secs_f64() * 1000.);
        if was_cancelled
            || terminal.terminal == Some(GpuTerminal::Cancelled)
            || terminal.code == "CANCELLED"
            || cancelled()
        {
            return Err(error("CANCELLED", "Scientific GPU cancelled"));
        }
        if terminal.terminal != Some(GpuTerminal::Success) {
            metrics.fallback_reason = gpu_fallback_reason(
                &terminal.code,
                if terminal.terminal == Some(GpuTerminal::NotStarted) {
                    "not_started"
                } else {
                    "helper_failed"
                },
            );
            return Ok(None);
        }
        let summary = terminal.summary.unwrap_or(serde_json::Value::Null);
        (
            metrics.upload_bytes,
            metrics.readback_bytes,
            metrics.dispatches,
        ) = gpu_transfer_counters(&summary);
        if summary["testOnly"] != true
            && summary["hostArithmeticMirror"] != true
            && summary["gpuUsed"] != false
        {
            metrics.gpu_stage_ms = summary["elapsedMs"]
                .as_f64()
                .filter(|v| v.is_finite() && *v >= 0.);
        }
        metrics.sample_count = summary["sampleCount"].as_u64();
        metrics.sample_max_abs = summary["sampleMaxAbs"]
            .as_f64()
            .filter(|v| v.is_finite() && *v >= 0.);
        metrics.sample_rms = summary["sampleRms"]
            .as_f64()
            .filter(|v| v.is_finite() && *v >= 0.);
        let candidate = match validate(dir, request.token, &plan.digest, &summary, cancelled) {
            Ok(v) => v,
            Err(code) => {
                cancel(cancelled)?;
                if code == "CANCELLED" {
                    return Err(error("CANCELLED", "Scientific GPU validation cancelled"));
                }
                metrics.fallback_reason = "invalid_output";
                return Ok(None);
            }
        };
        let adopted = match candidate {
            Candidate::Meter { ev, scale } => {
                if !ev.is_finite() || !scale.is_finite() || scale <= 0. {
                    metrics.fallback_reason = "invalid_output";
                    return Ok(None);
                }
                Candidate::Meter { ev, scale }
            }
            Candidate::Files(paths) => {
                if paths.is_empty()
                    || paths.len() > 3
                    || paths.iter().any(|p| {
                        p.parent() != Some(dir.join("candidate").as_path())
                            || !fs::symlink_metadata(p)
                                .is_ok_and(|m| m.is_file() && !m.file_type().is_symlink())
                    })
                {
                    metrics.fallback_reason = "invalid_output";
                    return Ok(None);
                }
                let mut moved = Vec::new();
                for source in paths {
                    let dest = scratch.path("gpu-science");
                    if fs::rename(source, &dest).is_err() {
                        for p in moved {
                            let _ = fs::remove_file(p);
                        }
                        metrics.fallback_reason = "commit_failed";
                        return Ok(None);
                    }
                    moved.push(dest);
                }
                Candidate::Files(moved)
            }
        };
        // Scratch owns moved files even if cancellation arrives here. No scientific
        // consumer receives a partially adopted candidate.
        cancel(cancelled)?;
        metrics.backend = k.backend();
        metrics.fallback_reason = "none";
        progress(total, total);
        Ok(Some(adopted))
    }
}

#[cfg(feature="diffusion-research")]
impl GpuScience {
 fn rejected(&mut self,k:ScienceKind,code:&str){self.metrics.get_mut(&k).unwrap().fallback_reason=gpu_fallback_reason(code,"unsupported_model");}
 pub fn global(&mut self,k:ScienceKind,input:Option<&Path>,planes:Option<&[PathBuf;3]>,plan:std::result::Result<crate::research_global::GlobalPlan,String>,scratch:&mut Scratch,w:u32,h:u32,c:&mut impl FnMut()->bool,p:impl FnMut(u64,u64))->Result<Option<Candidate>> {
  if !self.requested(k){return Ok(None);}let plan=match plan{Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  if plan.kind.operation()!=k.operation(){return Err(error("INTERNAL_ERROR","Scientific operation contract mismatch"));}
  use crate::research_global::full_stage as stage;
  self.execute(k,plan.disk_bytes,scratch,w,h,c,p,|dir,token,cancelled|{
   let value=if let Some(source)=input{stage::prepare_meter_stage(source,dir,token,&plan,||cancelled())?}else{let source=planes.ok_or("INVALID_GLOBAL_SOURCE")?;stage::prepare_plane_stage([&source[0],&source[1],&source[2]],dir,token,&plan,||cancelled())?};PlanMeta::from_plan(&value)
  },|dir,token,digest,_,cancelled|{Ok(match stage::validate_outputs(dir,token,digest,||cancelled())?{stage::GlobalOutput::Meter{ev,scale}=>Candidate::Meter{ev,scale},stage::GlobalOutput::Planes(p)=>Candidate::Files(p.into())})})
 }
 pub fn grain(&mut self,source:&[PathBuf;3],pipe:&Pipeline,pitch:f32,scratch:&mut Scratch,w:u32,h:u32,c:&mut impl FnMut()->bool,p:impl FnMut(u64,u64))->Result<Option<[PathBuf;3]>> {
  let k=ScienceKind::Grain;if !self.requested(k){return Ok(None);}
  // The guarded plan preserves preview MT versus export coordinate streams.
  // Runtime admission remains fail-closed in guarded_gpu until validation.
  use crate::research_grain::{GrainPlan,full_stage as stage};
  let plan=match GrainPlan::new_guarded(pipe,pitch,w,h){Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  let required=match stage::estimated_disk_bytes(w,h,&plan){Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  let value=self.execute(k,required,scratch,w,h,c,p,|dir,token,cancelled|PlanMeta::from_plan(&stage::prepare_stage([&source[0],&source[1],&source[2]],dir,token,w,h,&plan,||cancelled())?),|dir,token,digest,_,cancelled|Ok(Candidate::Files(stage::validate_outputs(dir,token,digest,||cancelled())?.into())))?;
  match value{None=>Ok(None),Some(Candidate::Files(paths))=>Ok(Some(paths.try_into().map_err(|_|error("INTERNAL_ERROR","Invalid grain candidate count"))?)),_=>Err(error("INTERNAL_ERROR","Invalid grain candidate kind"))}
 }
 pub fn print_exposure(&mut self,source:&[PathBuf;3],prepared:&spektrafilm_core::pipeline::PreparedStreamedFinish,scratch:&mut Scratch,w:u32,h:u32,c:&mut impl FnMut()->bool,p:impl FnMut(u64,u64))->Result<Option<PathBuf>> {
  let k=ScienceKind::PrintExposure;if !self.requested(k){return Ok(None);}use crate::research_finish as stage;
  let descriptor=match prepared.gpu_finish_descriptor(spektrafilm_core::pipeline::GpuFinishInput::PrintExposure){Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  let required=match stage::estimated_disk_bytes(w,h,&descriptor){Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  let result=self.execute(k,required,scratch,w,h,c,p,|dir,token,cancelled|PlanMeta::from_plan(&stage::prepare_stage([&source[0],&source[1],&source[2]],dir,token,w,h,&descriptor,||cancelled())?),|dir,token,digest,_,cancelled|Ok(Candidate::Files(vec![stage::validate_output(dir,token,digest,||cancelled())?])))?;
  match result{None=>Ok(None),Some(Candidate::Files(mut paths))if paths.len()==1=>Ok(paths.pop()),_=>Err(error("INTERNAL_ERROR","Invalid print exposure candidate"))}
 }
 pub fn print_diffusion(&mut self,source:&[PathBuf;3],pitch:f64,family:&str,kernel:&spektrafilm_model::diffusion::ExactDiffusionKernel,scratch:&mut Scratch,w:u32,h:u32,c:&mut impl FnMut()->bool,p:impl FnMut(u64,u64))->Result<Option<[PathBuf;3]>> {
  let k=ScienceKind::PrintDiffusion;if !self.requested(k){return Ok(None);}use crate::research_diffusion::full_stage as stage;
  let required=match stage::estimated_disk_bytes(w,h,kernel.radius){Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  let result=self.execute(k,required,scratch,w,h,c,p,|dir,token,cancelled|PlanMeta::from_plan(&stage::prepare_print_stage([&source[0],&source[1],&source[2]],dir,token,w,h,pitch,family,kernel,||cancelled())?),|dir,token,digest,_,cancelled|Ok(Candidate::Files(stage::validate_outputs(dir,token,digest,||cancelled())?.into())))?;
  match result{None=>Ok(None),Some(Candidate::Files(paths))=>Ok(Some(paths.try_into().map_err(|_|error("INTERNAL_ERROR","Invalid print diffusion count"))?)),_=>Err(error("INTERNAL_ERROR","Invalid print diffusion candidate"))}
 }
 pub fn finish_tail(&mut self,source:&[PathBuf;3],prepared:&spektrafilm_core::pipeline::PreparedStreamedFinish,params:&RuntimeParams,print_raw:bool,brightness:f64,scratch:&mut Scratch,w:u32,h:u32,c:&mut impl FnMut()->bool,p:impl FnMut(u64,u64))->Result<Option<PathBuf>> {
  let k=ScienceKind::FinishTail;if !self.requested(k){return Ok(None);}use crate::research_finish::fused as stage;
  let mode=if print_raw{spektrafilm_core::pipeline::GpuFinishInput::PrintRaw}else{spektrafilm_core::pipeline::GpuFinishInput::FilmDensity};
  let finish=match prepared.gpu_finish_descriptor(mode){Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  let tail=prepared.gpu_scan_tail_descriptor();let options=if self.route==ScienceRoute::Export{crate::research_finish_tail::TailOptions::from_params(params,brightness)}else{crate::research_finish_tail::TailOptions::for_preview(params,brightness)};
  let required=match stage::estimated_disk_bytes(w,h,&finish,&tail,options.clone()){Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  let result=self.execute(k,required,scratch,w,h,c,p,|dir,token,cancelled|PlanMeta::from_plan(&stage::prepare_stage([&source[0],&source[1],&source[2]],dir,token,w,h,&finish,&tail,options,||cancelled())?),|dir,token,digest,_,cancelled|Ok(Candidate::Files(vec![stage::validate_output(dir,token,digest,||cancelled())?])))?;
  match result{None=>Ok(None),Some(Candidate::Files(mut paths))if paths.len()==1=>Ok(paths.pop()),_=>Err(error("INTERNAL_ERROR","Invalid resident finish candidate"))}
 }
 pub fn tail(&mut self,source:&Path,prepared:&spektrafilm_core::pipeline::PreparedStreamedFinish,params:&RuntimeParams,brightness:f64,scratch:&mut Scratch,w:u32,h:u32,c:&mut impl FnMut()->bool,p:impl FnMut(u64,u64))->Result<Option<PathBuf>> {
  let k=ScienceKind::ScanTail;if !self.requested(k){return Ok(None);}use crate::research_finish_tail::{TailOptions,full_stage as stage};
  let descriptor=prepared.gpu_scan_tail_descriptor();let options=if self.route==ScienceRoute::Export{TailOptions::from_params(params,brightness)}else{TailOptions::for_preview(params,brightness)};
  let required=match stage::estimated_disk_bytes(w,h,&descriptor,options.clone()){Ok(v)=>v,Err(e)=>{self.rejected(k,&e);return Ok(None);}};
  let result=self.execute(k,required,scratch,w,h,c,p,|dir,token,cancelled|PlanMeta::from_plan(&stage::prepare_stage(source,None,dir,token,w,h,&descriptor,options,||cancelled())?),|dir,token,digest,_,cancelled|Ok(Candidate::Files(vec![stage::validate_output(dir,token,digest,||cancelled())?])))?;
  match result{None=>Ok(None),Some(Candidate::Files(mut paths))if paths.len()==1=>Ok(paths.pop()),_=>Err(error("INTERNAL_ERROR","Invalid scan tail candidate"))}
 }
}

impl GpuScience {pub fn record_checkpoint(&self,c:&mut crate::checkpoint::Checkpoints,stage:u8)->std::io::Result<()>{for k in ScienceKind::ALL{if k.checkpoint_stage()==stage&&self.requested(k){c.set_science_backend(k,self.gpu_used(k))?;}}Ok(())}}
