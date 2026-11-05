//! JNI owns no Java buffers. Callers keep direct buffers alive and untouched until return.
use crate::mobile::{self, EngineError, Result};
use jni::{JNIEnv, objects::{JByteBuffer,JObject,JString}, sys::{jint,jlong,jstring}};
use std::{panic::{catch_unwind,AssertUnwindSafe}, sync::atomic::Ordering,time::Instant};

fn jerr(e:impl std::fmt::Display)->EngineError {EngineError::new("JNI_ERROR",e.to_string())}
fn string(env:&mut JNIEnv<'_>,s:&JString<'_>)->Result<String> {if s.is_null() {return Err(EngineError::new("INVALID_PARAMS","String argument must not be null"));} Ok(env.get_string(s).map_err(jerr)?.into())}

pub(crate) fn buffer(env:&mut JNIEnv<'_>,b:&JByteBuffer<'_>,required:usize,output:bool)->Result<*mut u8> {
    if b.is_null() {return Err(EngineError::new("INVALID_BUFFER","Buffer must not be null"));}
    let cap=env.get_direct_buffer_capacity(b).map_err(|_|EngineError::new("INVALID_BUFFER","Buffer must be direct"))?;
    let position=env.call_method(b,"position","()I",&[]).map_err(jerr)?.i().map_err(jerr)?;
    let limit=env.call_method(b,"limit","()I",&[]).map_err(jerr)?.i().map_err(jerr)?;
    if position!=0 || limit<0 || (limit as usize)<required || cap<required {return Err(EngineError::new("INVALID_BUFFER","Buffer position must be zero and limit/capacity must cover the required output/input bytes"));}
    if output && env.call_method(b,"isReadOnly","()Z",&[]).map_err(jerr)?.z().map_err(jerr)? {return Err(EngineError::new("INVALID_BUFFER","Output buffer must be writable"));}
    let native=env.call_static_method("java/nio/ByteOrder","nativeOrder","()Ljava/nio/ByteOrder;",&[]).map_err(jerr)?.l().map_err(jerr)?;
    let order=env.call_method(b,"order","()Ljava/nio/ByteOrder;",&[]).map_err(jerr)?.l().map_err(jerr)?;
    if !env.is_same_object(native,order).map_err(jerr)? {return Err(EngineError::new("INVALID_BUFFER","Buffer byte order must equal ByteOrder.nativeOrder()"));}
    let ptr=env.get_direct_buffer_address(b).map_err(jerr)?;
    if ptr.is_null() || (ptr as usize)%4!=0 {return Err(EngineError::new("INVALID_BUFFER","Direct buffer address must be 4-byte aligned"));}
    Ok(ptr)
}
#[cfg(test)]
fn non_overlapping(a:usize,b:usize,n:usize)->bool {non_overlapping_ranges(a,n,b,n)}
pub(crate) fn non_overlapping_ranges(a:usize,an:usize,b:usize,bn:usize)->bool {match (a.checked_add(an),b.checked_add(bn)) {(Some(ae),Some(be))=>ae<=b || be<=a,_=>false}}

// Same presentation boundary for ordinary and scientific GPU previews. A packed
// buffer may be partially written on cancellation; outputValid is false unless
// this returns success. Java must still reject obsolete generations after JNI.
unsafe fn commit_preview_output(engine:&mobile::Engine,generation:u64,pixels:&[f32],ptr:*mut u8,required:usize,width:usize,rgba8:bool)->Result<()> {
    mobile::check_cancellation(engine,generation)?;
    if pixels.len()!=required/4 {return Err(EngineError::new("INVALID_OUTPUT_SHAPE","Preview output shape mismatch"));}
    if rgba8 {
        // Caller validated capacity, alignment, writability and disjoint input.
        let output=unsafe {std::slice::from_raw_parts_mut(ptr,required/3)};
        crate::preview_display::pack_rgba8(pixels,output,width,||mobile::check_cancellation(engine,generation))?;
    } else {
        unsafe {std::ptr::copy_nonoverlapping(pixels.as_ptr() as *const u8,ptr,required);}
    }
    mobile::check_cancellation(engine,generation)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCreate(mut env:JNIEnv<'_>,_this:JObject<'_>,data:JString<'_>)->jlong {
    catch_unwind(AssertUnwindSafe(||string(&mut env,&data).and_then(|p|mobile::create(&p)).unwrap_or(0))).unwrap_or(0)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCancel(_env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong) {let _=catch_unwind(AssertUnwindSafe(||mobile::cancel(handle)));}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeDestroy(_env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong) {let _=catch_unwind(AssertUnwindSafe(||mobile::destroy(handle)));}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRender(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    render_preview_jni(env,handle,input,output,width,height,params,false)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRenderRgba8(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    render_preview_jni(env,handle,input,output,width,height,params,true)
}
fn render_preview_jni(mut env:JNIEnv<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>,rgba8:bool)->jstring {
    let started=Instant::now();let mut stage="validate_request";let mut progress_job=None;
    let result=catch_unwind(AssertUnwindSafe(||->Result<String> {
        let required=mobile::required_bytes(width,height)?;
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        params.check_preview_budget((width as u64)*(height as u64))?;
        stage="acquire_handle";let engine=mobile::get(handle)?;
        let _guard=engine.render_lock.try_lock().map_err(|_|EngineError::new("ENGINE_BUSY","Only one render may run per handle"))?;
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        progress_job=Some(mobile::progress_begin(&engine,"preview"));mobile::progress_update(&engine,"preview",0,1);
        mobile::check_cancellation(&engine,generation)?;
        stage="validate_buffers";let input_ptr=buffer(&mut env,&input,required,false)?;
        let output_required=if rgba8 {required/3}else{required};
        let output_ptr=buffer(&mut env,&output,output_required,true)?;
        if !non_overlapping_ranges(input_ptr as usize,required,output_ptr as usize,output_required) {return Err(EngineError::new("INVALID_BUFFER","Input and output memory ranges overlap"));}
        // JNI direct-buffer references stay live for this call. Bounds and pointer
        // alignment have been checked. Java must not mutate/access these buffers
        // concurrently, including aliases and buffers submitted to another handle.
        let input_bytes=unsafe {std::slice::from_raw_parts(input_ptr,required)};
        let pixels:Vec<f32>=input_bytes.chunks_exact(4).map(|v|f32::from_ne_bytes(v.try_into().unwrap())).collect();
        stage="render_pipeline";let rendered=mobile::render(&engine,generation,&pixels,width as u32,height as u32,&params)?;
        mobile::check_cancellation(&engine,generation)?;
        if rendered.pixels.len()!=required/4 {return Err(EngineError::new("INVALID_OUTPUT_SHAPE","Rendered buffer length differs from requested byte count"));}
        // Commit output only after complete successful rendering and cancellation
        // check. A cancel racing this final copy may arrive after commit; callers
        // must also use their own job generation to discard a stale result.
        stage="commit_output";let display_started=Instant::now();
        unsafe {commit_preview_output(&engine,generation,&rendered.pixels,output_ptr,required,width as usize,rgba8)?;}
        let display_encode_ms=if rgba8 {Some(display_started.elapsed().as_secs_f64()*1000.)}else{None};
        let warnings:Vec<&str>=rendered.warnings.iter().map(|&warning|if rgba8 && warning.starts_with("Float32 input/output ABI") {"Float32 input ABI with packed sRGB RGBA8 display output; internal arithmetic is reported separately. This is not a bit-exact float64 Python reference."}else{warning}).collect();
        Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"preview","stage":"complete","engine":"spektrafilm-rs-portable","backend":rendered.gpu.backend,"gpu":rendered.gpu,"precision":if rgba8 {if cfg!(feature="precision-f64") {"f64-internal-f32-input-rgba8-output"}else{"f32-internal-f32-input-rgba8-output"}}else if cfg!(feature="precision-f64") {"f64-internal-f32-io"} else {"f32"},"width":width,"height":height,"elapsedMs":rendered.elapsed_ms,"previewReuseHit":rendered.preview_reuse_hit,"previewCacheBytes":rendered.preview_cache_bytes,"meteredEv":rendered.metered_ev,"warnings":warnings,"displayEncodeMs":display_encode_ms,"outputValid":true,"outputFormat":if rgba8 {"srgb-rgba8"}else{"linear-rgb-f32"},"maxPixels":mobile::MAX_PIXELS,"memoryBudgetMiB":params.memory_budget_mi_b,"estimatedPeakNativeBytes":mobile::MEMORY_FIXED_ESTIMATE_BYTES+(width as u64)*(height as u64)*mobile::MEMORY_ESTIMATE_BYTES_PER_PIXEL,"effectiveParams":params}).to_string())
    }));
    if let Some(job_id)=progress_job {if let Ok(engine)=mobile::get(handle){let _=mobile::progress_finish(&engine,job_id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")});}}
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"preview",stage,started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Native render failed safely; output is invalid"),"preview",stage,started.elapsed().as_secs_f64()*1000.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Scientific preview uses the same bounded stage delegates as export, but keeps
/// the original preview grid and sequential random-stream semantics.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRenderWithGpuDelegate(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>,delegate:JObject<'_>)->jstring {
    render_preview_delegate_jni(env,handle,input,output,width,height,params,delegate,false)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRenderWithGpuDelegateRgba8(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>,delegate:JObject<'_>)->jstring {
    render_preview_delegate_jni(env,handle,input,output,width,height,params,delegate,true)
}
fn render_preview_delegate_jni(mut env:JNIEnv<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>,delegate:JObject<'_>,rgba8:bool)->jstring {
    let started=Instant::now(); let mut progress_job=None;
    let result=catch_unwind(AssertUnwindSafe(||->Result<String>{
        let required=mobile::required_bytes(width,height)?;
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        params.check_preview_budget(width as u64*height as u64)?;
        let engine=mobile::get(handle)?;
        let _guard=engine.render_lock.try_lock().map_err(|_|EngineError::new("ENGINE_BUSY","Only one render may run per handle"))?;
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        progress_job=Some(mobile::progress_begin(&engine,"preview"));
        mobile::check_cancellation(&engine,generation)?;
        let output_required=if rgba8 {required/3}else{required};
        let ip=buffer(&mut env,&input,required,false)?; let op=buffer(&mut env,&output,output_required,true)?;
        if !non_overlapping_ranges(ip as usize,required,op as usize,output_required){return Err(EngineError::new("INVALID_BUFFER","Input and output memory ranges overlap"));}
        let raw=unsafe{std::slice::from_raw_parts(ip,required)};
        let pixels:Vec<f32>=raw.chunks_exact(4).map(|v|f32::from_ne_bytes(v.try_into().unwrap())).collect();
        let delegates=scientific_delegates(&mut env,&delegate,&params);
        let rendered=crate::preview_gpu::render(&engine,generation,handle,&pixels,width as u32,height as u32,&params,delegates)?;
        mobile::check_cancellation(&engine,generation)?;
        if rendered.pixels.len()!=required/4 || rendered.pixels.iter().any(|x|!x.is_finite()){return Err(EngineError::new("INVALID_OUTPUT_SHAPE","Scientific preview output is invalid"));}
        let backend=scientific_backend(&rendered.metrics);
        // Only a complete successful presentation is marked outputValid below.
        let display_started=Instant::now();
        unsafe{commit_preview_output(&engine,generation,&rendered.pixels,op,required,width as usize,rgba8)?;}
        let display_encode_ms=if rgba8 {Some(display_started.elapsed().as_secs_f64()*1000.)}else{None};
        let mut warnings=vec!["Experimental scientific GPU stages retain the original preview grid; actual stage backends and fallbacks are reported separately."];
        if rgba8 {warnings.push("Float32 input ABI with packed sRGB RGBA8 display output; internal arithmetic is reported separately.");}
        Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"preview","stage":"complete","engine":"spektrafilm-rs-portable","backend":backend,"gpu":rendered.metrics.gpu,"metrics":rendered.metrics,"precision":"experimental-gpu-f32-cpu-f64-fallback","width":width,"height":height,"elapsedMs":rendered.elapsed_ms,"previewReuseHit":false,"previewCacheBytes":0,"meteredEv":rendered.metered_ev,"warnings":warnings,"displayEncodeMs":display_encode_ms,"outputValid":true,"outputFormat":if rgba8 {"srgb-rgba8"}else{"linear-rgb-f32"},"maxPixels":mobile::MAX_PIXELS,"memoryBudgetMiB":params.memory_budget_mi_b,"effectiveParams":params}).to_string())
    }));
    if let Some(id)=progress_job{if let Ok(engine)=mobile::get(handle){let _=mobile::progress_finish(&engine,id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")});}}
    let json=match result{Ok(Ok(v))=>v,Ok(Err(e))=>mobile::error_json(e,"preview","scientific_pipeline",started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Scientific preview failed; output is invalid"),"preview","scientific_pipeline",started.elapsed().as_secs_f64()*1000.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCleanupPreviewAfterGpuDrain(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong)->jstring {
    // Java must prove all helper processes dead before calling. Native also rejects
    // roots still registered as Running; this is not a substitute for drain proof.
    let result=catch_unwind(AssertUnwindSafe(||{let engine=mobile::get(handle)?;let _lock=engine.render_lock.try_lock().map_err(|_|EngineError::new("PREVIEW_STILL_RUNNING","Preview is still running"))?;let roots=crate::gpu_retained::cleanup_after_drain(handle)?;crate::preview_gpu::cleanup_after_drain(handle).map(|v|v||roots)}));
    let value=match result{Ok(Ok(cleaned))=>serde_json::json!({"ok":true,"cleaned":cleaned}),Ok(Err(e))=>serde_json::json!({"ok":false,"code":e.code}),Err(_)=>serde_json::json!({"ok":false,"code":"PREVIEW_CLEANUP_FAILED"})};
    env.new_string(value.to_string()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCleanupExportAfterGpuDrain(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong)->jstring {
 let result=catch_unwind(AssertUnwindSafe(||->Result<bool>{let engine=mobile::get(handle)?;let _lock=engine.render_lock.try_lock().map_err(|_|EngineError::new("EXPORT_STILL_RUNNING","Export is still running"))?;crate::gpu_retained::cleanup_after_drain(handle)}));
 let value=match result{Ok(Ok(cleaned))=>serde_json::json!({"ok":true,"cleaned":cleaned}),Ok(Err(e))=>serde_json::json!({"ok":false,"code":e.code}),Err(_)=>serde_json::json!({"ok":false,"code":"GPU_CLEANUP_FAILED"})};env.new_string(value.to_string()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
pub(crate) fn scientific_backend(metrics:&crate::streamed::StreamedMetrics)->&'static str {
    let mut states=vec![(&metrics.gpu_diffusion.backend,metrics.gpu_diffusion.requested,metrics.gpu_diffusion.fallback_reason),(&metrics.gpu_scanner.backend,metrics.gpu_scanner.requested,metrics.gpu_scanner.fallback_reason),(&metrics.gpu_optical.backend,metrics.gpu_optical.requested,metrics.gpu_optical.fallback_reason),(&metrics.gpu_exposure.backend,metrics.gpu_exposure.requested,metrics.gpu_exposure.fallback_reason),(&metrics.gpu_dir.backend,metrics.gpu_dir.requested,metrics.gpu_dir.fallback_reason)];
    if metrics.gpu_log_dir.requested{states.push((&metrics.gpu_log_dir.backend,true,metrics.gpu_log_dir.fallback_reason));}
    states.extend(metrics.gpu_science.values().map(|m|(&m.backend,m.requested,m.fallback_reason)));
    let used=states.iter().any(|(b,_,_)|b.starts_with("gpu_"));
    let fallback=states.iter().any(|(b,_,reason)|!b.starts_with("gpu_") && *reason!="inactive");
    if !used{"cpu"}else if fallback{"mixed_gpu_f32_cpu_f64"}else{"gpu_f32"}
}
pub(crate) fn scientific_delegates(env:&mut JNIEnv<'_>,object:&JObject<'_>,params:&mobile::MobileParams)->crate::streamed::GpuStageDelegates {
    let mut make=|on:bool|->Option<Box<dyn crate::streamed::GpuDiffusionDelegate>>{if !on{return None}match JvmGpuDelegate::new(env,object){Ok(v)=>Some(Box::new(v)),Err(_)=>{let _=env.exception_clear();None}}};
    crate::streamed::GpuStageDelegates{diffusion:make(params.gpu_diffusion_experimental),scanner:make(params.gpu_scanner_experimental),optical:make(params.gpu_optical_experimental),exposure:make(params.gpu_exposure_experimental),dir:make(params.gpu_dir_experimental),science:crate::streamed::ScienceKind::ALL.into_iter().filter_map(|k|make(params.gpu_science_experimental).map(|d|(k,d))).collect()}
}

/// Global Java ownership is Send-safe; each Rayon caller attaches independently.
/// Local references are bounded per call and exceptions never prove helper death.
struct JvmGpuDelegate {vm:jni::JavaVM,object:jni::objects::GlobalRef,session:bool,ticket:Option<crate::streamed::GpuSessionTicket>}
impl JvmGpuDelegate {
 fn new(env:&mut JNIEnv<'_>,object:&JObject<'_>)->jni::errors::Result<Self> {
  let class=env.get_object_class(object)?;
  let session=env.get_method_id(&class,"beginSessionStage","(Ljava/lang/String;)Ljava/lang/String;").is_ok();
  if !session {let _=env.exception_clear();}
  let methods:Vec<(&str,&str)>=if session{vec![("beginSessionStage","(Ljava/lang/String;)Ljava/lang/String;"),("pollSessionStage","(JJJJ)Ljava/lang/String;"),("cancelSessionStage","(JJJJ)V"),("cancelUncertainSessionStage","(J)V"),("pollUncertainSessionStage","(J)Ljava/lang/String;"),("availableDiskBytes","()J")]}else{vec![("begin","(Ljava/lang/String;)I"),("poll","(J)Ljava/lang/String;"),("cancel","(J)V"),("availableDiskBytes","()J")]};
  for (method,signature) in methods {env.get_method_id(&class,method,signature)?;}
  Ok(Self{vm:env.get_java_vm()?,object:env.new_global_ref(object)?,session,ticket:None})
 }
 fn call<T>(&self,work:impl FnOnce(&mut JNIEnv<'_>,&JObject<'static>)->jni::errors::Result<T>)->Option<T> {
  let mut env=self.vm.attach_current_thread().ok()?;
  let result=env.with_local_frame(16,|env|work(env,self.object.as_obj()));
  if env.exception_check().unwrap_or(true) {let _=env.exception_clear();None}else{result.ok()}
 }
}
fn bounded_java_reply(env:&mut JNIEnv<'_>,result:JObject<'_>)->jni::errors::Result<String>{
 if result.is_null(){return Err(jni::errors::Error::NullPtr("GPU delegate result"));}
 let length=env.call_method(&result,"length","()I",&[])?.i()?;
 if !(0..=16384).contains(&length){return Err(jni::errors::Error::NullPtr("GPU delegate result limit"));}
 let result=JString::from(result);let value=env.get_string(&result)?.into();Ok(value)
}
fn session_begin_ticket(raw:&str,request:&str)->Option<Option<crate::streamed::GpuSessionTicket>>{
 if raw.len()>16384{return None;}
 let v:serde_json::Value=serde_json::from_str(raw).ok()?;let m=v.as_object()?;
 if v["schemaVersion"].as_u64()!=Some(2){return None;}
 if v["accepted"].as_bool()==Some(false){if m.len()!=3||!m.contains_key("code")||v["code"].as_str()?.len()>96{return None;}return Some(None);}
 if v["accepted"].as_bool()!=Some(true)||m.len()!=7||m.keys().any(|k|!["schemaVersion","accepted","epoch","frameGeneration","sequence","token","manifestSha256"].contains(&k.as_str())){return None;}
 let request:serde_json::Value=serde_json::from_str(request).ok()?;
 let positive=|k:&str|v[k].as_u64().filter(|v|*v>0&&*v<=i64::MAX as u64);
 let t=crate::streamed::GpuSessionTicket{epoch:positive("epoch")?,generation:positive("frameGeneration")?,sequence:positive("sequence")?,token:positive("token")?};
 if request["token"].as_u64()!=Some(t.token)||v["manifestSha256"].as_str()!=request["manifestSha256"].as_str()||v["manifestSha256"].as_str()?.len()!=64{return None;}Some(Some(t))
}
impl crate::streamed::GpuDiffusionDelegate for JvmGpuDelegate {
 fn available_disk_bytes(&mut self)->Option<u64> {self.call(|env,object|env.call_method(object,"availableDiskBytes","()J",&[])?.j()).and_then(|v|u64::try_from(v).ok())}
 fn begin(&mut self,request:&str)->Option<bool> {
  if self.session {
   if self.ticket.is_some(){return None;}
   let raw=self.call(|env,object|{let value=env.new_string(request)?;let reply=env.call_method(object,"beginSessionStage","(Ljava/lang/String;)Ljava/lang/String;",&[jni::objects::JValue::Object(value.as_ref())])?.l()?;bounded_java_reply(env,reply)})?;
   match session_begin_ticket(&raw,request)?{Some(t)=>{self.ticket=Some(t);Some(true)},None=>Some(false)}
  }else{
   let value=self.call(|env,object|{let request=env.new_string(request)?;env.call_method(object,"begin","(Ljava/lang/String;)I",&[jni::objects::JValue::Object(request.as_ref())])?.i()})?;
   match value {0=>Some(false),1=>Some(true),_=>None}
  }
 }
 fn poll(&mut self,token:u64)->Option<String> {
  self.call(|env,object|{
   let result=if let Some(t)=self.ticket {
    if t.token!=token{return Err(jni::errors::Error::NullPtr("GPU session token"));}
    env.call_method(object,"pollSessionStage","(JJJJ)Ljava/lang/String;",&[jni::objects::JValue::Long(t.epoch as i64),jni::objects::JValue::Long(t.generation as i64),jni::objects::JValue::Long(t.sequence as i64),jni::objects::JValue::Long(token as i64)])?.l()?
   }else{env.call_method(object,if self.session{"pollUncertainSessionStage"}else{"poll"},"(J)Ljava/lang/String;",&[jni::objects::JValue::Long(token as i64)])?.l()?};
   let raw=bounded_java_reply(env,result)?;
   if self.session&&self.ticket.is_none(){
    let v:serde_json::Value=serde_json::from_str(&raw).map_err(|_|jni::errors::Error::NullPtr("GPU uncertain result"))?;
    if !matches!(v["status"].as_str(),Some("pending"|"running"|"stopping"|"failed_stopped"|"cancelled_stopped"|"not_started")){return Err(jni::errors::Error::NullPtr("GPU uncertain success forbidden"));}
   }
   Ok(raw)
  })
 }
 fn cancel(&mut self,token:u64) {
  let _=self.call(|env,object|{
   if let Some(t)=self.ticket {if t.token!=token{return Err(jni::errors::Error::NullPtr("GPU session token"));}env.call_method(object,"cancelSessionStage","(JJJJ)V",&[jni::objects::JValue::Long(t.epoch as i64),jni::objects::JValue::Long(t.generation as i64),jni::objects::JValue::Long(t.sequence as i64),jni::objects::JValue::Long(token as i64)])?.v()}
   else{env.call_method(object,if self.session{"cancelUncertainSessionStage"}else{"cancel"},"(J)V",&[jni::objects::JValue::Long(token as i64)])?.v()}
  });
 }
 fn session_ticket(&self)->Option<crate::streamed::GpuSessionTicket>{self.ticket}
 fn permits_no_route_proof(&self)->bool{self.session&&self.ticket.is_none()}
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeExportFile(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JString<'_>,output:JString<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    export_file_jni(env,handle,input,output,width,height,params,None)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeExportFileWithGpuDelegate(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JString<'_>,output:JString<'_>,width:jint,height:jint,params:JString<'_>,delegate:JObject<'_>)->jstring {
    export_file_jni(env,handle,input,output,width,height,params,Some(delegate))
}
fn export_file_jni(mut env:JNIEnv<'_>,handle:jlong,input:JString<'_>,output:JString<'_>,width:jint,height:jint,params:JString<'_>,delegate:Option<JObject<'_>>)->jstring {
    let started=Instant::now();let mut stage="validate_request";let mut progress_job=None;
    let result=catch_unwind(AssertUnwindSafe(||->Result<String> {
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        let input=string(&mut env,&input)?;let output=string(&mut env,&output)?;
        stage="acquire_handle";let engine=mobile::get(handle)?;
        let _guard=engine.render_lock.try_lock().map_err(|_|EngineError::new("ENGINE_BUSY","Only one render/export may run per handle"))?;
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        progress_job=Some(mobile::progress_begin(&engine,"exportFile"));
        stage="validate_paths_and_export";
        // Distinct native owners reference the same export-scoped Java router.
        // The router owns independent one-shot delegates; no live gate is reused.
        let mut make_delegate=|enabled:bool|->Option<Box<dyn crate::streamed::GpuDiffusionDelegate>> {
            if !enabled {return None;}
            delegate.as_ref().and_then(|object|match JvmGpuDelegate::new(&mut env,object){Ok(v)=>Some(Box::new(v)as Box<dyn crate::streamed::GpuDiffusionDelegate>),Err(_)=>{let _=env.exception_clear();None}})
        };
        let delegates=crate::streamed::GpuStageDelegates {
            diffusion:make_delegate(params.gpu_diffusion_experimental),
            scanner:make_delegate(params.gpu_scanner_experimental),
            optical:make_delegate(params.gpu_optical_experimental),
            exposure:make_delegate(params.gpu_exposure_experimental),
            dir:make_delegate(params.gpu_dir_experimental),
            science:crate::streamed::ScienceKind::ALL.into_iter().filter_map(|kind|make_delegate(params.gpu_science_experimental).map(|d|(kind,d))).collect(),
        };
        let metrics=mobile::export_with_gpu_delegates(&engine,generation,handle,&input,&output,width,height,&params,delegates)?;
        Ok(serde_json::json!({"ok":true,"schemaVersion":1,"engine":"spektrafilm-rs-portable","operation":"exportFile","stage":"complete","elapsedMs":started.elapsed().as_secs_f64()*1000.,"outputValid":true,"width":width,"height":height,"metrics":metrics,"memoryBudgetMiB":params.memory_budget_mi_b,"effectiveParams":params,"warnings":["Internal spectral integrations use 33-cube tables; spatial halation, DIR and film-density grain remain modeled when enabled.","Original-size spatial stages use disk-backed separable passes rather than finite-halo tiling; validation limits are reported separately, not claimed Python bit-exact.","Enabled grain uses absolute-coordinate seeded Poisson-binomial sampling; pattern differs from sequential Python RNG but does not restart at each tile.","Output is a local linear RGB float32 little-endian spool, not a JPEG or PNG; app must encode it without resizing."]}).to_string())
    }));
    if let Some(job_id)=progress_job {if let Ok(engine)=mobile::get(handle){if let Some(last_stage)=mobile::progress_finish(&engine,job_id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")}){stage=last_stage;}}}
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"exportFile",stage,started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Native export failed safely; partial output is invalid and removed"),"exportFile",stage,started.elapsed().as_secs_f64()*1000.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)] mod tests {use super::*;#[test] fn overlap_and_overflow_rejected(){assert!(!non_overlapping(100,100,20));assert!(!non_overlapping(100,110,20));assert!(non_overlapping(100,120,20));assert!(!non_overlapping(usize::MAX-2,0,4));}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativePlanExport(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,width:jint,height:jint,params:JString<'_>)->jstring {
    let started=Instant::now();
    let result=catch_unwind(AssertUnwindSafe(||->Result<String>{
        let _engine=mobile::get(handle)?;let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        let plan=mobile::plan_export(width,height,&params)?;
        Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planExport","stage":"complete","plan":plan,"elapsedMs":started.elapsed().as_secs_f64()*1000.,"performsIo":false,"resizesImage":false}).to_string())
    }));
    let json=match result{Ok(Ok(v))=>v,Ok(Err(e))=>mobile::error_json(e,"planExport","validate_request",started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Plan failed"),"planExport","validate_request",started.elapsed().as_secs_f64()*1000.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeGetProgress(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong)->jstring {
    let started=Instant::now();
    let result=catch_unwind(AssertUnwindSafe(||mobile::get(handle).and_then(|engine|mobile::progress_json(&engine))));
    let json=match result{Ok(Ok(v))=>v,Ok(Err(e))=>mobile::error_json(e,"getProgress","validate_request",started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Progress failed"),"getProgress","validate_request",started.elapsed().as_secs_f64()*1000.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// A distinct process gate for the one-shot full optical helper. The export
/// router must prove helper death before a later numeric stage is launched.
#[cfg(feature="gpu-diffusion-research")]
mod optical_stage_jni {
 use super::*;
 use std::path::Path;
 static GATE:crate::research_diffusion::probe::ProcessGate=crate::research_diffusion::probe::ProcessGate::new();
 fn bounded_string(env:&mut JNIEnv<'_>,value:&JString<'_>)->std::result::Result<String,String> {
  if value.is_null(){return Err("INVALID_ARGUMENT".into());}
  let length=env.call_method(value,"length","()I",&[]).map_err(|_|"INVALID_ARGUMENT")?.i().map_err(|_|"INVALID_ARGUMENT")?;
  if !(0..=4096).contains(&length){return Err("INVALID_ARGUMENT".into());}
  let text:String=env.get_string(value).map_err(|_|"INVALID_ARGUMENT")?.into();
  if text.len()>4096{return Err("INVALID_ARGUMENT".into());}Ok(text)
 }
 fn reply(env:&mut JNIEnv<'_>,value:std::result::Result<serde_json::Value,String>)->jstring {
  let value=match value{Ok(v)=>v,Err(code)=>serde_json::json!({"ok":false,"schemaVersion":1,"diagnosticOnly":true,"outputReplaced":false,"code":code})};
  let text=value.to_string();let text=if text.len()<=16384{text}else{"{\"ok\":false,\"code\":\"RESULT_TOO_LARGE\"}".into()};
  env.new_string(text).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuFilmOpticsStage(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,token:jlong)->jstring {
  let result=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=bounded_string(&mut env,&dir)?;let plan=crate::research_spatial::full_stage::plan_stage(Path::new(&dir),token as u64)?;Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmOpticsStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
  reply(&mut env,result)
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuFilmOpticsStage(_:JNIEnv<'_>,_:JObject<'_>,token:jlong) {if token>0{GATE.cancel(token as u64);}}
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuFilmOpticsStage(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,budget:jlong,token:jlong,sha:JString<'_>)->jstring {
 let mut telemetry=crate::gpu_phase_diagnostics::TaskScope::jni(&mut env,token,5);

  let result=catch_unwind(AssertUnwindSafe(||{
 let prepare_phase=crate::gpu_phase_diagnostics::PhaseGuard::begin(crate::gpu_phase_diagnostics::Phase::Prepare);
   if token<=0||budget<=0{return Err("INVALID_ARGUMENT".into());}GATE.claim(token as u64)?;
   let dir=bounded_string(&mut env,&dir)?;let sha=bounded_string(&mut env,&sha)?;
   prepare_phase.finish();
   crate::research_spatial::full_stage::run_stage(Path::new(&dir),token as u64,budget as u64,&sha,||GATE.is_cancelled(token as u64))
  })).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));telemetry.finish(&result);reply(&mut env,result)
 }
}

/// A distinct process gate for the one-shot full exposure helper. The export
/// router must prove helper death before a later numeric stage is launched.
#[cfg(feature="gpu-diffusion-research")]
mod exposure_stage_jni {
 use super::*;
 use std::path::Path;
 static GATE:crate::research_diffusion::probe::ProcessGate=crate::research_diffusion::probe::ProcessGate::new();
 fn bounded_string(env:&mut JNIEnv<'_>,value:&JString<'_>)->std::result::Result<String,String> {
  if value.is_null(){return Err("INVALID_ARGUMENT".into());}
  let length=env.call_method(value,"length","()I",&[]).map_err(|_|"INVALID_ARGUMENT")?.i().map_err(|_|"INVALID_ARGUMENT")?;
  if !(0..=4096).contains(&length){return Err("INVALID_ARGUMENT".into());}
  let text:String=env.get_string(value).map_err(|_|"INVALID_ARGUMENT")?.into();
  if text.len()>4096{return Err("INVALID_ARGUMENT".into());}Ok(text)
 }
 fn reply(env:&mut JNIEnv<'_>,value:std::result::Result<serde_json::Value,String>)->jstring {
  let value=match value{Ok(v)=>v,Err(code)=>serde_json::json!({"ok":false,"schemaVersion":1,"diagnosticOnly":true,"outputReplaced":false,"code":code})};
  let text=value.to_string();let text=if text.len()<=16384{text}else{"{\"ok\":false,\"code\":\"RESULT_TOO_LARGE\"}".into()};
  env.new_string(text).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuFilmExposureStage(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,token:jlong)->jstring {
  let result=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=bounded_string(&mut env,&dir)?;let plan=crate::research_exposure::plan_stage(Path::new(&dir),token as u64)?;Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmExposureStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
  reply(&mut env,result)
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuFilmExposureStage(_:JNIEnv<'_>,_:JObject<'_>,token:jlong) {if token>0{GATE.cancel(token as u64);}}
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuFilmExposureStage(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,budget:jlong,token:jlong,sha:JString<'_>)->jstring {
 let mut telemetry=crate::gpu_phase_diagnostics::TaskScope::jni(&mut env,token,4);

  let result=catch_unwind(AssertUnwindSafe(||{
 let prepare_phase=crate::gpu_phase_diagnostics::PhaseGuard::begin(crate::gpu_phase_diagnostics::Phase::Prepare);
   if token<=0||budget<=0{return Err("INVALID_ARGUMENT".into());}GATE.claim(token as u64)?;
   let dir=bounded_string(&mut env,&dir)?;let sha=bounded_string(&mut env,&sha)?;
   prepare_phase.finish();
   crate::research_exposure::run_stage(Path::new(&dir),token as u64,budget as u64,&sha,||GATE.is_cancelled(token as u64))
  })).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));telemetry.finish(&result);reply(&mut env,result)
 }
}

/// A distinct process gate for the one-shot full density/DIR helper. The export
/// router must prove helper death before a later numeric stage is launched.
#[cfg(feature="gpu-diffusion-research")]
mod dir_stage_jni {
 use super::*;
 use std::path::Path;
 static GATE:crate::research_diffusion::probe::ProcessGate=crate::research_diffusion::probe::ProcessGate::new();
 fn bounded_string(env:&mut JNIEnv<'_>,value:&JString<'_>)->std::result::Result<String,String> {
  if value.is_null(){return Err("INVALID_ARGUMENT".into());}
  let length=env.call_method(value,"length","()I",&[]).map_err(|_|"INVALID_ARGUMENT")?.i().map_err(|_|"INVALID_ARGUMENT")?;
  if !(0..=4096).contains(&length){return Err("INVALID_ARGUMENT".into());}
  let text:String=env.get_string(value).map_err(|_|"INVALID_ARGUMENT")?.into();
  if text.len()>4096{return Err("INVALID_ARGUMENT".into());}Ok(text)
 }
 fn reply(env:&mut JNIEnv<'_>,value:std::result::Result<serde_json::Value,String>)->jstring {
  let value=match value{Ok(v)=>v,Err(code)=>serde_json::json!({"ok":false,"schemaVersion":1,"diagnosticOnly":true,"outputReplaced":false,"code":code})};
  let text=value.to_string();let text=if text.len()<=16384{text}else{"{\"ok\":false,\"code\":\"RESULT_TOO_LARGE\"}".into()};
  env.new_string(text).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuFilmDirStage(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,token:jlong)->jstring {
  let result=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=bounded_string(&mut env,&dir)?;let plan=crate::research_dir::full_stage::plan_stage(Path::new(&dir),token as u64)?;Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmDirStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
  reply(&mut env,result)
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuFilmDirStage(_:JNIEnv<'_>,_:JObject<'_>,token:jlong) {if token>0{GATE.cancel(token as u64);}}
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuFilmDirStage(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,budget:jlong,token:jlong,sha:JString<'_>)->jstring {
 let mut telemetry=crate::gpu_phase_diagnostics::TaskScope::jni(&mut env,token,6);

  let result=catch_unwind(AssertUnwindSafe(||{
 let prepare_phase=crate::gpu_phase_diagnostics::PhaseGuard::begin(crate::gpu_phase_diagnostics::Phase::Prepare);
   if token<=0||budget<=0{return Err("INVALID_ARGUMENT".into());}GATE.claim(token as u64)?;
   let dir=bounded_string(&mut env,&dir)?;let sha=bounded_string(&mut env,&sha)?;
   prepare_phase.finish();
   crate::research_dir::full_stage::run_stage(Path::new(&dir),token as u64,budget as u64,&sha,||GATE.is_cancelled(token as u64))
  })).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));telemetry.finish(&result);reply(&mut env,result)
 }
}

/// A distinct process gate for the one-shot coupled exposure-log/DIR helper. The export
/// router must prove helper death before a later numeric stage is launched.
#[cfg(feature="gpu-diffusion-research")]
mod log_dir_stage_jni {
 use super::*;
 use std::path::Path;
 static GATE:crate::research_diffusion::probe::ProcessGate=crate::research_diffusion::probe::ProcessGate::new();
 fn bounded_string(env:&mut JNIEnv<'_>,value:&JString<'_>)->std::result::Result<String,String> {
  if value.is_null(){return Err("INVALID_ARGUMENT".into());}
  let length=env.call_method(value,"length","()I",&[]).map_err(|_|"INVALID_ARGUMENT")?.i().map_err(|_|"INVALID_ARGUMENT")?;
  if !(0..=4096).contains(&length){return Err("INVALID_ARGUMENT".into());}
  let text:String=env.get_string(value).map_err(|_|"INVALID_ARGUMENT")?.into();
  if text.len()>4096{return Err("INVALID_ARGUMENT".into());}Ok(text)
 }
 fn reply(env:&mut JNIEnv<'_>,value:std::result::Result<serde_json::Value,String>)->jstring {
  let value=match value{Ok(v)=>v,Err(code)=>serde_json::json!({"ok":false,"schemaVersion":1,"diagnosticOnly":true,"outputReplaced":false,"code":code})};
  let text=value.to_string();let text=if text.len()<=16384{text}else{"{\"ok\":false,\"code\":\"RESULT_TOO_LARGE\"}".into()};
  env.new_string(text).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuExposureLogDirStage(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,token:jlong)->jstring {
  let result=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=bounded_string(&mut env,&dir)?;let plan=crate::research_log_dir::plan_stage(Path::new(&dir),token as u64)?;Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuExposureLogDirStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
  reply(&mut env,result)
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuExposureLogDirStage(_:JNIEnv<'_>,_:JObject<'_>,token:jlong) {if token>0{GATE.cancel(token as u64);}}
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuExposureLogDirStage(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,budget:jlong,token:jlong,sha:JString<'_>)->jstring {
 let mut telemetry=crate::gpu_phase_diagnostics::TaskScope::jni(&mut env,token,14);

  let result=catch_unwind(AssertUnwindSafe(||{
 let prepare_phase=crate::gpu_phase_diagnostics::PhaseGuard::begin(crate::gpu_phase_diagnostics::Phase::Prepare);
   if token<=0||budget<=0{return Err("INVALID_ARGUMENT".into());}GATE.claim(token as u64)?;
   let dir=bounded_string(&mut env,&dir)?;let sha=bounded_string(&mut env,&sha)?;
   prepare_phase.finish();
   crate::research_log_dir::run_stage(Path::new(&dir),token as u64,budget as u64,&sha,||GATE.is_cancelled(token as u64))
  })).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));telemetry.finish(&result);reply(&mut env,result)
 }
}

/// A distinct process gate for the one-shot full printing/scanning helper. The export
/// router must prove helper death before a later numeric stage is launched.
#[cfg(feature="gpu-diffusion-research")]
mod scanner_stage_jni {
 use super::*;
 use std::path::Path;
 static GATE:crate::research_diffusion::probe::ProcessGate=crate::research_diffusion::probe::ProcessGate::new();
 fn bounded_string(env:&mut JNIEnv<'_>,value:&JString<'_>)->std::result::Result<String,String> {
  if value.is_null(){return Err("INVALID_ARGUMENT".into());}
  let length=env.call_method(value,"length","()I",&[]).map_err(|_|"INVALID_ARGUMENT")?.i().map_err(|_|"INVALID_ARGUMENT")?;
  if !(0..=4096).contains(&length){return Err("INVALID_ARGUMENT".into());}
  let text:String=env.get_string(value).map_err(|_|"INVALID_ARGUMENT")?.into();
  if text.len()>4096{return Err("INVALID_ARGUMENT".into());}Ok(text)
 }
 fn reply(env:&mut JNIEnv<'_>,value:std::result::Result<serde_json::Value,String>)->jstring {
  let value=match value{Ok(v)=>v,Err(code)=>serde_json::json!({"ok":false,"schemaVersion":1,"diagnosticOnly":true,"outputReplaced":false,"code":code})};
  let text=value.to_string();let text=if text.len()<=16384{text}else{"{\"ok\":false,\"code\":\"RESULT_TOO_LARGE\"}".into()};
  env.new_string(text).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuPrintScan(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,token:jlong)->jstring {
  let result=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=bounded_string(&mut env,&dir)?;let plan=crate::research_finish::plan_stage(Path::new(&dir),token as u64)?;Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuPrintScan","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
  reply(&mut env,result)
 }
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuPrintScan(_:JNIEnv<'_>,_:JObject<'_>,token:jlong) {let _=catch_unwind(AssertUnwindSafe(||{if token>0{GATE.cancel(token as u64);}}));}
 #[unsafe(no_mangle)]
 pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuPrintScan(mut env:JNIEnv<'_>,_:JObject<'_>,dir:JString<'_>,budget:jlong,token:jlong,sha:JString<'_>)->jstring {
 let mut telemetry=crate::gpu_phase_diagnostics::TaskScope::jni(&mut env,token,11);

  let result=catch_unwind(AssertUnwindSafe(||{
 let prepare_phase=crate::gpu_phase_diagnostics::PhaseGuard::begin(crate::gpu_phase_diagnostics::Phase::Prepare);
   if token<=0||budget<=0{return Err("INVALID_ARGUMENT".into());}GATE.claim(token as u64)?;
   let dir=bounded_string(&mut env,&dir)?;let sha=bounded_string(&mut env,&sha)?;
   prepare_phase.finish();
   crate::research_finish::run_stage(Path::new(&dir),token as u64,budget as u64,&sha,||GATE.is_cancelled(token as u64))
  })).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));telemetry.finish(&result);reply(&mut env,result)
 }
}

#[cfg(test)]
mod display_range_tests {
    use super::non_overlapping_ranges;
    #[test] fn unequal_ranges_reject_overlap_and_overflow() {
        assert!(non_overlapping_ranges(100,12,112,4));
        assert!(non_overlapping_ranges(104,12,100,4));
        assert!(!non_overlapping_ranges(100,12,108,4));
        assert!(!non_overlapping_ranges(104,12,100,8));
        assert!(!non_overlapping_ranges(usize::MAX-2,4,100,4));
        assert!(!non_overlapping_ranges(100,4,usize::MAX-2,4));
    }
}

#[cfg(test)] mod session_ticket_tests {
 use super::*;
 fn response()->serde_json::Value{serde_json::json!({"schemaVersion":2,"accepted":true,"epoch":1,"frameGeneration":2,"sequence":3,"token":4,"manifestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})}
 fn request()->String{serde_json::json!({"token":4,"manifestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}).to_string()}
 #[test]fn handshake_binds_manifest_and_positive_ticket(){let v=response();assert!(session_begin_ticket(&v.to_string(),&request()).unwrap().is_some());for k in ["epoch","frameGeneration","sequence","token"]{let mut v=response();v[k]=0.into();assert!(session_begin_ticket(&v.to_string(),&request()).is_none());}let mut v=response();v["manifestSha256"]="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".into();assert!(session_begin_ticket(&v.to_string(),&request()).is_none());}
 #[test]fn rejection_is_explicit_and_unknown_reply_stays_uncertain(){assert_eq!(session_begin_ticket(r#"{"schemaVersion":2,"accepted":false,"code":"NO_MEMORY"}"#,&request()),Some(None));assert!(session_begin_ticket(r#"{"schemaVersion":2,"accepted":false}"#,&request()).is_none());assert!(session_begin_ticket("",&request()).is_none());let mut v=response();v["extra"]=true.into();assert!(session_begin_ticket(&v.to_string(),&request()).is_none());}
}
