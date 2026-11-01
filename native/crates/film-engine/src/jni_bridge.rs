//! JNI owns no Java buffers. Callers keep direct buffers alive and untouched until return.
use crate::mobile::{self, EngineError, Result};
use jni::{JNIEnv, objects::{JByteBuffer,JObject,JString}, sys::{jint,jlong,jstring}};
use std::{panic::{catch_unwind,AssertUnwindSafe}, sync::atomic::Ordering,time::Instant};

fn jerr(e:impl std::fmt::Display)->EngineError {EngineError::new("JNI_ERROR",e.to_string())}
fn string(env:&mut JNIEnv<'_>,s:&JString<'_>)->Result<String> {if s.is_null() {return Err(EngineError::new("INVALID_PARAMS","String argument must not be null"));} Ok(env.get_string(s).map_err(jerr)?.into())}

fn buffer(env:&mut JNIEnv<'_>,b:&JByteBuffer<'_>,required:usize,output:bool)->Result<*mut u8> {
    if b.is_null() {return Err(EngineError::new("INVALID_BUFFER","Buffer must not be null"));}
    let cap=env.get_direct_buffer_capacity(b).map_err(|_|EngineError::new("INVALID_BUFFER","Buffer must be direct"))?;
    let position=env.call_method(b,"position","()I",&[]).map_err(jerr)?.i().map_err(jerr)?;
    let limit=env.call_method(b,"limit","()I",&[]).map_err(jerr)?.i().map_err(jerr)?;
    if position!=0 || limit<0 || (limit as usize)<required || cap<required {return Err(EngineError::new("INVALID_BUFFER","Buffer position must be zero and limit/capacity must cover every RGB float32 sample"));}
    if output && env.call_method(b,"isReadOnly","()Z",&[]).map_err(jerr)?.z().map_err(jerr)? {return Err(EngineError::new("INVALID_BUFFER","Output buffer must be writable"));}
    let native=env.call_static_method("java/nio/ByteOrder","nativeOrder","()Ljava/nio/ByteOrder;",&[]).map_err(jerr)?.l().map_err(jerr)?;
    let order=env.call_method(b,"order","()Ljava/nio/ByteOrder;",&[]).map_err(jerr)?.l().map_err(jerr)?;
    if !env.is_same_object(native,order).map_err(jerr)? {return Err(EngineError::new("INVALID_BUFFER","Buffer byte order must equal ByteOrder.nativeOrder()"));}
    let ptr=env.get_direct_buffer_address(b).map_err(jerr)?;
    if ptr.is_null() || (ptr as usize)%4!=0 {return Err(EngineError::new("INVALID_BUFFER","Direct buffer address must be 4-byte aligned"));}
    Ok(ptr)
}
fn non_overlapping(a:usize,b:usize,n:usize)->bool {match (a.checked_add(n),b.checked_add(n)) {(Some(ae),Some(be))=>ae<=b || be<=a,_=>false}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCreate(mut env:JNIEnv<'_>,_this:JObject<'_>,data:JString<'_>)->jlong {
    catch_unwind(AssertUnwindSafe(||string(&mut env,&data).and_then(|p|mobile::create(&p)).unwrap_or(0))).unwrap_or(0)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCancel(_env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong) {let _=catch_unwind(AssertUnwindSafe(||mobile::cancel(handle)));}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeDestroy(_env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong) {let _=catch_unwind(AssertUnwindSafe(||mobile::destroy(handle)));}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRender(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
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
        let output_ptr=buffer(&mut env,&output,required,true)?;
        if !non_overlapping(input_ptr as usize,output_ptr as usize,required) {return Err(EngineError::new("INVALID_BUFFER","Input and output memory ranges overlap"));}
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
        stage="commit_output";unsafe {std::ptr::copy_nonoverlapping(rendered.pixels.as_ptr() as *const u8,output_ptr,required);}
        Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"preview","stage":"complete","engine":"spektrafilm-rs-portable","backend":rendered.gpu.backend,"gpu":rendered.gpu,"precision":if cfg!(feature="precision-f64") {"f64-internal-f32-io"} else {"f32"},"width":width,"height":height,"elapsedMs":rendered.elapsed_ms,"meteredEv":rendered.metered_ev,"warnings":rendered.warnings,"outputValid":true,"maxPixels":mobile::MAX_PIXELS,"memoryBudgetMiB":params.memory_budget_mi_b,"estimatedPeakNativeBytes":mobile::MEMORY_FIXED_ESTIMATE_BYTES+(width as u64)*(height as u64)*mobile::MEMORY_ESTIMATE_BYTES_PER_PIXEL,"effectiveParams":params}).to_string())
    }));
    if let Some(job_id)=progress_job {if let Ok(engine)=mobile::get(handle){let _=mobile::progress_finish(&engine,job_id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")});}}
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"preview",stage,started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Native render failed safely; output is invalid"),"preview",stage,started.elapsed().as_secs_f64()*1000.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeExportFile(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JString<'_>,output:JString<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    let started=Instant::now();let mut stage="validate_request";let mut progress_job=None;
    let result=catch_unwind(AssertUnwindSafe(||->Result<String> {
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        let input=string(&mut env,&input)?;let output=string(&mut env,&output)?;
        stage="acquire_handle";let engine=mobile::get(handle)?;
        let _guard=engine.render_lock.try_lock().map_err(|_|EngineError::new("ENGINE_BUSY","Only one render/export may run per handle"))?;
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        progress_job=Some(mobile::progress_begin(&engine,"exportFile"));
        stage="validate_paths_and_export";let metrics=mobile::export(&engine,generation,&input,&output,width,height,&params)?;
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
