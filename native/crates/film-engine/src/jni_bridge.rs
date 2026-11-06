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

/// Success payload shared by every preview entry point (ByteBuffer and Bitmap).
fn preview_ok_json(rendered:&mobile::RenderOutput,params:&mobile::MobileParams,width:jint,height:jint,encoding:&str)->String {
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    crate::resource_probe::observe_vec("jni_preview_output_f32", &rendered.pixels);
    // RESOURCE_PROBE_END
    serde_json::json!({"ok":true,"schemaVersion":1,"nativeAbiRevision":2,"outputEncoding":encoding,"operation":"preview","stage":"complete","engine":"spektrafilm-rs-portable","backend":rendered.gpu.backend,"gpu":rendered.gpu,"precision":if rendered.gpu.backend=="wgpu" {"gpu-f32-cpu-f64-boundaries-f32-io"} else if cfg!(feature="precision-f64") {"f64-internal-f32-io"} else {"f32"},"width":width,"height":height,"elapsedMs":rendered.elapsed_ms,"previewCache":rendered.preview_cache,"meteredEv":rendered.metered_ev,"preparedSpectral":rendered.prepared_spectral,"warnings":rendered.warnings,"outputValid":true,"maxPixels":if rendered.refined_plan.is_some(){mobile::refined::MAX_EDGE as usize*mobile::refined::MAX_EDGE as usize}else{mobile::MAX_PIXELS},"refinedPlan":rendered.refined_plan,"memoryBudgetMiB":params.memory_budget_mi_b,"estimatedPeakNativeBytes":rendered.refined_plan.map(|p|p.estimated_peak_bytes).unwrap_or_else(||mobile::MEMORY_FIXED_ESTIMATE_BYTES+(width as u64)*(height as u64)*(mobile::MEMORY_ESTIMATE_BYTES_PER_PIXEL+if params.preview_source.is_some(){mobile::PREVIEW_CANDIDATE_BYTES_PER_PIXEL}else{0})),"effectiveParams":params}).to_string()
}
fn non_overlapping(a:usize,b:usize,n:usize)->bool {match (a.checked_add(n),b.checked_add(n)) {(Some(ae),Some(be))=>ae<=b || be<=a,_=>false}}
/// True when `[a,a+a_len)` and `[b,b+b_len)` intersect. Unlike `non_overlapping`
/// this takes each range's own length, so a small output buffer is not falsely
/// reported as overlapping merely because it sits within the (larger) input
/// length of the input buffer. Overflow is treated as overlap (safe default).
fn ranges_overlap(a:usize,a_len:usize,b:usize,b_len:usize)->bool {match (a.checked_add(a_len),b.checked_add(b_len)) {(Some(ae),Some(be))=>a<be && b<ae,_=>true}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCreate(mut env:JNIEnv<'_>,_this:JObject<'_>,data:JString<'_>)->jlong {
    catch_unwind(AssertUnwindSafe(||string(&mut env,&data).and_then(|p|mobile::create(&p)).unwrap_or(0))).unwrap_or(0)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCancel(_env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong) {let _=catch_unwind(AssertUnwindSafe(||mobile::cancel(handle)));}
/// Best-effort cache retirement before the existing queue disk admission.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeTrimExportReuse(_env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,reserve:jlong) {
    let _=catch_unwind(AssertUnwindSafe(||{
        #[cfg(feature="adreno-gpu")]
        if reserve>0 {if let Ok(engine)=mobile::get(handle){crate::export_reuse::trim_for_reserve(&engine.data_dir,reserve as u64);}}
        #[cfg(not(feature="adreno-gpu"))]
        let _=(handle,reserve);
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeSetPreviewCacheBudget(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,bytes:jlong) {
    let result=catch_unwind(AssertUnwindSafe(||if bytes<0 {Err(EngineError::new("INVALID_BUDGET","Negative preview cache budget"))}else{mobile::set_preview_cache_budget(handle,bytes as u64)}));
    if !matches!(result,Ok(Ok(()))) {let _=env.throw_new("java/lang/IllegalStateException","Preview cache ownership unavailable");}
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeDestroy(_env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong) {let _=catch_unwind(AssertUnwindSafe(||mobile::destroy(handle)));}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeInvalidatePrewarm(_env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,token:jlong) {
    let _=catch_unwind(AssertUnwindSafe(||if token>=0 {mobile::invalidate_prewarm(handle,token as u64)}));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativePrewarmFront(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,token:jlong,params:JString<'_>)->jstring {
    let started=Instant::now();
    let result=catch_unwind(AssertUnwindSafe(||->Result<String>{
        if token<0 {return Err(EngineError::new("INVALID_PARAMS","Invalid prewarm token"));}
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        let engine=mobile::get(handle)?;
        mobile::prewarm_front(&engine,token as u64,&params)
    }));
    let json=match result{Ok(Ok(v))=>v,Ok(Err(e))=>mobile::error_json(e,"prewarmFront","prepare",started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Optional prewarm failed"),"prewarmFront","prepare",started.elapsed().as_secs_f64()*1000.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Only the shared passive owner calls this, never from a native render callback.
/// Disabling waits for render_lock and retires every front Arc before success.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeSetPreparedAssetsBudget(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,bytes:jlong)->jstring {
    let started=Instant::now();
    let result=catch_unwind(AssertUnwindSafe(||->Result<String>{
        if bytes<0 {return Err(EngineError::new("INVALID_BUDGET","Negative prepared-asset budget"));}
        let engine=mobile::get(handle)?;
        mobile::set_prepared_spectral_cache_budget(&engine,bytes as u64)?;
        Ok(serde_json::json!({"ok":true,"schemaVersion":1,"preparedSpectral":mobile::prepared_spectral_cache_stats(&engine)}).to_string())
    }));
    let json=match result{Ok(Ok(v))=>v,Ok(Err(e))=>mobile::error_json(e,"preparedAssets","retire_or_admit",started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Prepared asset admission failed"),"preparedAssets","retire_or_admit",started.elapsed().as_secs_f64()*1000.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRender(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let resource_probe = crate::resource_probe::NativeProbe::begin(false);
    // RESOURCE_PROBE_END
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
        // RESOURCE_PROBE_BEGIN
        #[cfg(feature="resource-audit")]
        crate::resource_probe::observe_vec("jni_preview_input_f32", &pixels);
        // RESOURCE_PROBE_END
        stage="render_pipeline";let rendered=mobile::render(&engine,generation,&pixels,width as u32,height as u32,&params)?;
        mobile::check_cancellation(&engine,generation)?;
        if rendered.pixels.len()!=required/4 {return Err(EngineError::new("INVALID_OUTPUT_SHAPE","Rendered buffer length differs from requested byte count"));}
        // Commit output only after complete successful rendering and cancellation
        // check. A cancel racing this final copy may arrive after commit; callers
        // must also use their own job generation to discard a stale result.
        stage="commit_output";unsafe {std::ptr::copy_nonoverlapping(rendered.pixels.as_ptr() as *const u8,output_ptr,required);}
        Ok(preview_ok_json(&rendered,&params,width,height,"linear-rgb-f32"))
    }));
    if let Some(job_id)=progress_job {if let Ok(engine)=mobile::get(handle){let _=mobile::progress_finish(&engine,job_id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")});}}
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"preview",stage,started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Native render failed safely; output is invalid"),"preview",stage,started.elapsed().as_secs_f64()*1000.)};
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let json = resource_probe.attach(json);
    // RESOURCE_PROBE_END
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// RGBA8 variant of `nativeRender`: identical pipeline and metadata, but the
/// output buffer receives packed sRGB-encoded RGBA8 (`width*height*4` bytes)
/// instead of linear RGB float32. The Java preview can then hand the buffer
/// straight to `Bitmap.copyPixelsFromBuffer` (one JNI memcpy) instead of a
/// per-pixel float→sRGB `Double.pow` loop, which was the dominant ~400 ms
/// post-render cost on the UI path.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRenderRgba8(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,output:JByteBuffer<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let resource_probe = crate::resource_probe::NativeProbe::begin(false);
    // RESOURCE_PROBE_END
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
        let output_required=usize::try_from((width as u64)*(height as u64)*4).map_err(|_|EngineError::new("INVALID_BUFFER","Output buffer size overflow"))?;
        stage="validate_buffers";let input_ptr=buffer(&mut env,&input,required,false)?;
        let output_ptr=buffer(&mut env,&output,output_required,true)?;
        if ranges_overlap(input_ptr as usize,required,output_ptr as usize,output_required) {return Err(EngineError::new("INVALID_BUFFER","Input and output memory ranges overlap"));}
        let input_bytes=unsafe {std::slice::from_raw_parts(input_ptr,required)};
        let pixels:Vec<f32>=input_bytes.chunks_exact(4).map(|v|f32::from_ne_bytes(v.try_into().unwrap())).collect();
        // RESOURCE_PROBE_BEGIN
        #[cfg(feature="resource-audit")]
        crate::resource_probe::observe_vec("jni_preview_input_f32", &pixels);
        // RESOURCE_PROBE_END
        stage="render_pipeline";let rendered=mobile::render(&engine,generation,&pixels,width as u32,height as u32,&params)?;
        mobile::check_cancellation(&engine,generation)?;
        let rgba8=mobile::linear_srgb_to_rgba8(&rendered.pixels);
        if rgba8.len()!=output_required {return Err(EngineError::new("INVALID_OUTPUT_SHAPE","Rendered buffer length differs from requested byte count"));}
        stage="commit_output";unsafe {std::ptr::copy_nonoverlapping(rgba8.as_ptr(),output_ptr,output_required);}
        Ok(preview_ok_json(&rendered,&params,width,height,"srgb-rgba8"))
    }));
    if let Some(job_id)=progress_job {if let Ok(engine)=mobile::get(handle){let _=mobile::progress_finish(&engine,job_id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")});}}
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"preview",stage,started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Native render failed safely; output is invalid"),"preview",stage,started.elapsed().as_secs_f64()*1000.)};
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let json = resource_probe.attach(json);
    // RESOURCE_PROBE_END
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

// --- AndroidBitmap direct I/O (Android only) ----------------------------------
// These entry points read the source `Bitmap` and write the result `Bitmap`
// directly through `AndroidBitmap_lockPixels`, eliminating the per-frame Kotlin
// pixel loop, the `createScaledBitmap` intermediate, and both direct `ByteBuffer`
// copies (input fill + `copyPixelsFromBuffer`) used by the ByteBuffer variants.
// They are Android-only because they link `libjnigraphics`; the ByteBuffer
// variants above remain available and keep the host-side tests runnable.
#[cfg(target_os="android")]
mod android_bitmap {
    use jni::sys;
    use std::os::raw::{c_int,c_void};
    pub const FORMAT_RGBA_8888:i32=1;
    pub const RESULT_SUCCESS:c_int=0;
    #[repr(C)]
    pub struct AndroidBitmapInfo { pub width:u32, pub height:u32, pub stride:u32, pub format:i32, pub flags:u32 }
    #[link(name="jnigraphics")]
    unsafe extern "C" {
        pub fn AndroidBitmap_getInfo(env:*mut sys::JNIEnv, jbitmap:sys::jobject, info:*mut AndroidBitmapInfo)->c_int;
        pub fn AndroidBitmap_lockPixels(env:*mut sys::JNIEnv, jbitmap:sys::jobject, addr:*mut *mut c_void)->c_int;
        pub fn AndroidBitmap_unlockPixels(env:*mut sys::JNIEnv, jbitmap:sys::jobject)->c_int;
    }
}
/// Drop always runs on normal errors and Rust unwinding. Register each acquired
/// resource immediately; never acquire a second lock before creating its guard.
struct ReleaseGuard<F:FnMut()->Result<()>> { release:Option<F> }
impl<F:FnMut()->Result<()>> ReleaseGuard<F> {
    fn new(release:F)->Self {Self{release:Some(release)}}
    fn finish(mut self)->Result<()> {self.release.take().unwrap()()}
}
impl<F:FnMut()->Result<()>> Drop for ReleaseGuard<F> {
    fn drop(&mut self) {if let Some(release)=self.release.as_mut(){let _=release();}}
}
#[cfg(target_os="android")]
fn bitmap_info(env:&mut JNIEnv<'_>,bitmap:&JObject<'_>,output:bool)->Result<android_bitmap::AndroidBitmapInfo> {
    if bitmap.is_null(){return Err(EngineError::new("INVALID_BUFFER","Bitmap is null"));}
    let mut info=android_bitmap::AndroidBitmapInfo{width:0,height:0,stride:0,format:0,flags:0};
    let rc=unsafe{android_bitmap::AndroidBitmap_getInfo(env.get_native_interface(),bitmap.as_raw(),&mut info)};
    if rc!=android_bitmap::RESULT_SUCCESS || info.format!=android_bitmap::FORMAT_RGBA_8888 || info.flags&0x80000000!=0 {
        return Err(EngineError::new("INVALID_BUFFER","Bitmap must be software ARGB_8888"));
    }
    if info.width==0||info.height==0||info.width.checked_mul(4).is_none_or(|row|info.stride<row) || (info.stride as usize).checked_mul(info.height as usize).is_none() {
        return Err(EngineError::new("INVALID_BUFFER","Bitmap geometry is invalid"));
    }
    if output&&!env.call_method(bitmap,"isMutable","()Z",&[]).map_err(jerr)?.z().map_err(jerr)? {return Err(EngineError::new("INVALID_BUFFER","Destination bitmap is immutable"));}
    let color=env.call_method(bitmap,"getColorSpace","()Landroid/graphics/ColorSpace;",&[]).map_err(jerr)?.l().map_err(jerr)?;
    if color.is_null()||!env.call_method(color,"isSrgb","()Z",&[]).map_err(jerr)?.z().map_err(jerr)? {return Err(EngineError::new("INVALID_BUFFER","Bitmap must be sRGB"));}
    Ok(info)
}
#[cfg(target_os="android")]
fn bitmap_lock(env:&mut JNIEnv<'_>,bitmap:&JObject<'_>)->Result<(*mut u8,ReleaseGuard<impl FnMut()->Result<()>>)> {
    let native=env.get_native_interface();let object=bitmap.as_raw();
    let mut ptr:*mut std::os::raw::c_void=std::ptr::null_mut();
    let rc=unsafe{android_bitmap::AndroidBitmap_lockPixels(native,object,&mut ptr)};
    if rc!=android_bitmap::RESULT_SUCCESS {return Err(EngineError::new("INVALID_BUFFER","Unable to lock bitmap pixels"));}
    let guard=ReleaseGuard::new(move|| {
        let rc=unsafe{android_bitmap::AndroidBitmap_unlockPixels(native,object)};
        if rc==android_bitmap::RESULT_SUCCESS {Ok(())}else{Err(EngineError::new("JNI_ERROR","Unable to unlock bitmap pixels"))}
    });
    if ptr.is_null(){return Err(EngineError::new("INVALID_BUFFER","Locked bitmap pixels are null"));}
    Ok((ptr as *mut u8,guard))
}

/// Bitmap-direct preview: reads the source `Bitmap` (any size), box-averages it
/// down to `width*height`, linearizes, renders, and writes the sRGB result straight
/// into the destination `Bitmap`. Replaces the Kotlin `getPixels`/`putFloat` loop,
/// the `createScaledBitmap` intermediate, and both direct-buffer copies.
#[cfg(target_os="android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRenderBitmap(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,src:JObject<'_>,dst:JObject<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    render_bitmap(env,handle,src,dst,width,height,params,false)
}
#[cfg(target_os="android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRenderRefinedBitmap(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,src:JObject<'_>,dst:JObject<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    render_bitmap(env,handle,src,dst,width,height,params,true)
}
#[cfg(target_os="android")]
fn render_bitmap(mut env:JNIEnv<'_>,handle:jlong,src:JObject<'_>,dst:JObject<'_>,width:jint,height:jint,params:JString<'_>,refined:bool)->jstring {
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let resource_probe = crate::resource_probe::NativeProbe::begin(false);
    // RESOURCE_PROBE_END
    let started=Instant::now();let mut stage="validate_request";let mut progress_job=None;
    let result=catch_unwind(AssertUnwindSafe(||->Result<String> {
        if refined {mobile::refined::required_bytes(width,height)?} else {mobile::required_bytes(width,height)?}; // reject oversize before any allocation
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        if !refined {params.check_preview_budget((width as u64)*(height as u64))?;}
        stage="acquire_handle";let engine=mobile::get(handle)?;
        let _guard=engine.render_lock.try_lock().map_err(|_|EngineError::new("ENGINE_BUSY","Only one render may run per handle"))?;
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        progress_job=Some(mobile::progress_begin(&engine,"preview"));mobile::progress_update(&engine,"preview",0,1);
        mobile::check_cancellation(&engine,generation)?;
        stage="validate_bitmaps";
        if env.is_same_object(&src,&dst).map_err(jerr)? {return Err(EngineError::new("INVALID_BUFFER","Source and destination bitmaps must be distinct"));}
        let src_info=bitmap_info(&mut env,&src,false)?;
        let dst_info=bitmap_info(&mut env,&dst,true)?;
        if dst_info.width!=width as u32||dst_info.height!=height as u32 {return Err(EngineError::new("INVALID_BUFFER","Output bitmap dimensions do not match the request"));}
        let external=if refined {
            if src_info.width!=width as u32 || src_info.height!=height as u32 {return Err(EngineError::new("INVALID_BUFFER","Refined source must match the bounded output grid"));}
            (src_info.stride as u64*src_info.height as u64).checked_add(dst_info.stride as u64*dst_info.height as u64)
                .ok_or_else(||EngineError::new("MEMORY_BUDGET_TOO_SMALL","External buffer size overflow"))?
        }else{0};
        if refined {mobile::refined::preflight(width,height,&params,external)?;}
        stage="read_bitmap";
        let (src_ptr,src_guard)=bitmap_lock(&mut env,&src)?;
        let src_len=src_info.stride as usize*(src_info.height as usize-1)+src_info.width as usize*4;
        let src_pixels=unsafe{std::slice::from_raw_parts(src_ptr as *const u8,src_len)};
        for row in src_pixels.chunks(src_info.stride as usize) {
            if row[..src_info.width as usize*4].chunks_exact(4).any(|p|p[3]!=255) {return Err(EngineError::new("INVALID_BUFFER","Source bitmap must already be alpha-composited"));}
        }
        let input=mobile::downsample_linearize_rgba8(src_pixels,src_info.width as usize,src_info.height as usize,src_info.stride as usize,width as usize,height as usize);
        // RESOURCE_PROBE_BEGIN
        #[cfg(feature="resource-audit")]
        crate::resource_probe::observe_vec("jni_bitmap_linear_input", &input);
        // RESOURCE_PROBE_END
        src_guard.finish()?;
        mobile::check_cancellation(&engine,generation)?;
        stage="render_pipeline";let rendered=if refined {mobile::refined::render(&engine,generation,&input,width as u32,height as u32,&params,external)?} else {mobile::render(&engine,generation,&input,width as u32,height as u32,&params)?};
        mobile::check_cancellation(&engine,generation)?;
        stage="commit_output";
        let (dst_ptr,dst_guard)=bitmap_lock(&mut env,&dst)?;
        let dst_len=dst_info.stride as usize*(dst_info.height as usize-1)+width as usize*4;
        let dst_pixels=unsafe{std::slice::from_raw_parts_mut(dst_ptr,dst_len)};
        mobile::linear_srgb_to_rgba8_into(&rendered.pixels,dst_pixels,dst_info.stride as usize,width as usize,height as usize);
        dst_guard.finish()?;
        mobile::check_cancellation(&engine,generation)?;
        Ok(preview_ok_json(&rendered,&params,width,height,"srgb-rgba8"))
    }));
    if let Some(job_id)=progress_job {if let Ok(engine)=mobile::get(handle){let _=mobile::progress_finish(&engine,job_id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")});}}
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"preview",stage,started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Native render failed safely; output is invalid"),"preview",stage,started.elapsed().as_secs_f64()*1000.)};
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let json = resource_probe.attach(json);
    // RESOURCE_PROBE_END
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Linear-buffer + Bitmap-output preview for RAW sources: the input is the
/// unclipped linear float32 spool (`width*height*12` bytes), the rendered sRGB
/// frame is written straight into the destination `Bitmap`. Eliminates the output
/// direct buffer and `copyPixelsFromBuffer` memcpy used by `nativeRenderRgba8`.
#[cfg(target_os="android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRenderLinearBitmap(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,dst:JObject<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    render_linearbitmap(env,handle,input,dst,width,height,params,false)
}
#[cfg(target_os="android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeRenderRefinedLinearBitmap(env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JByteBuffer<'_>,dst:JObject<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    render_linearbitmap(env,handle,input,dst,width,height,params,true)
}
#[cfg(target_os="android")]
fn render_linearbitmap(mut env:JNIEnv<'_>,handle:jlong,input:JByteBuffer<'_>,dst:JObject<'_>,width:jint,height:jint,params:JString<'_>,refined:bool)->jstring {
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let resource_probe = crate::resource_probe::NativeProbe::begin(false);
    // RESOURCE_PROBE_END
    let started=Instant::now();let mut stage="validate_request";let mut progress_job=None;
    let result=catch_unwind(AssertUnwindSafe(||->Result<String> {
        let required=if refined {mobile::refined::required_bytes(width,height)?} else {mobile::required_bytes(width,height)?};
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        if !refined {params.check_preview_budget((width as u64)*(height as u64))?;}
        stage="acquire_handle";let engine=mobile::get(handle)?;
        let _guard=engine.render_lock.try_lock().map_err(|_|EngineError::new("ENGINE_BUSY","Only one render may run per handle"))?;
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        progress_job=Some(mobile::progress_begin(&engine,"preview"));mobile::progress_update(&engine,"preview",0,1);
        mobile::check_cancellation(&engine,generation)?;
        stage="validate_buffers";let input_ptr=buffer(&mut env,&input,required,false)?;
        let dst_info=bitmap_info(&mut env,&dst,true)?;
        if dst_info.width!=width as u32||dst_info.height!=height as u32 {return Err(EngineError::new("INVALID_BUFFER","Output bitmap dimensions do not match the request"));}
        let external=if refined {(env.get_direct_buffer_capacity(&input).map_err(jerr)? as u64)
            .checked_add(dst_info.stride as u64*dst_info.height as u64)
            .ok_or_else(||EngineError::new("MEMORY_BUDGET_TOO_SMALL","External buffer size overflow"))?}else{0};
        if refined {mobile::refined::preflight(width,height,&params,external)?;}
        let input_bytes=unsafe{std::slice::from_raw_parts(input_ptr,required)};
        let pixels:Vec<f32>=input_bytes.chunks_exact(4).map(|v|f32::from_ne_bytes(v.try_into().unwrap())).collect();
        // RESOURCE_PROBE_BEGIN
        #[cfg(feature="resource-audit")]
        crate::resource_probe::observe_vec("jni_preview_input_f32", &pixels);
        // RESOURCE_PROBE_END
        stage="render_pipeline";let rendered=if refined {mobile::refined::render(&engine,generation,&pixels,width as u32,height as u32,&params,external)?} else {mobile::render(&engine,generation,&pixels,width as u32,height as u32,&params)?};
        mobile::check_cancellation(&engine,generation)?;
        stage="commit_output";
        let (dst_ptr,dst_guard)=bitmap_lock(&mut env,&dst)?;
        let dst_len=dst_info.stride as usize*(dst_info.height as usize-1)+width as usize*4;
        if ranges_overlap(input_ptr as usize,required,dst_ptr as usize,dst_len) {return Err(EngineError::new("INVALID_BUFFER","Input buffer overlaps destination bitmap"));}
        let dst_pixels=unsafe{std::slice::from_raw_parts_mut(dst_ptr,dst_len)};
        mobile::linear_srgb_to_rgba8_into(&rendered.pixels,dst_pixels,dst_info.stride as usize,width as usize,height as usize);
        dst_guard.finish()?;
        mobile::check_cancellation(&engine,generation)?;
        Ok(preview_ok_json(&rendered,&params,width,height,"srgb-rgba8"))
    }));
    if let Some(job_id)=progress_job {if let Ok(engine)=mobile::get(handle){let _=mobile::progress_finish(&engine,job_id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")});}}
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"preview",stage,started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Native render failed safely; output is invalid"),"preview",stage,started.elapsed().as_secs_f64()*1000.)};
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let json = resource_probe.attach(json);
    // RESOURCE_PROBE_END
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeExportFile(mut env:JNIEnv<'_>,_this:JObject<'_>,handle:jlong,input:JString<'_>,output:JString<'_>,width:jint,height:jint,params:JString<'_>)->jstring {
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let resource_probe = crate::resource_probe::NativeProbe::begin(true);
    // RESOURCE_PROBE_END
    let started=Instant::now();let mut stage="validate_request";let mut progress_job=None;
    let result=catch_unwind(AssertUnwindSafe(||->Result<String> {
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        let input=string(&mut env,&input)?;let output=string(&mut env,&output)?;
        stage="acquire_handle";let engine=mobile::get(handle)?;
        let _guard=engine.render_lock.try_lock().map_err(|_|EngineError::new("ENGINE_BUSY","Only one render/export may run per handle"))?;
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        progress_job=Some(mobile::progress_begin(&engine,"exportFile"));
        stage="validate_paths_and_export";let metrics=mobile::export(&engine,generation,&input,&output,width,height,&params)?;
        // Only completed, actually accelerated GPU pixels are cache eligible. CPU fallback
        // under a GPU request and checkpoint-only restorations have no equivalent receipt.
        let encoded_reuse=mobile::encoded_reuse_probe(params.use_gpu).filter(|probe| {
            if params.use_gpu {metrics.gpu.backend=="wgpu" && metrics.gpu.active && metrics.gpu.cpu_fallbacks==0 && probe["algorithm"].as_str()==Some(metrics.algorithm)}
            else {metrics.gpu.backend=="cpu" && !metrics.gpu.active}
        }).map(|probe|serde_json::json!({"probe":probe,"algorithm":metrics.algorithm,"tiles":metrics.gpu_tiles,"chunkRows":metrics.pointwise_chunk_rows,"budgetMiB":metrics.memory_budget_mi_b}));
        Ok(serde_json::json!({"ok":true,"schemaVersion":1,"engine":"spektrafilm-rs-portable","operation":"exportFile","stage":"complete","elapsedMs":started.elapsed().as_secs_f64()*1000.,"outputValid":true,"width":width,"height":height,"metrics":metrics,"encodedReuse":encoded_reuse,"memoryBudgetMiB":params.memory_budget_mi_b,"effectiveParams":params,"warnings":["Internal spectral integrations use 33-cube tables; spatial halation, DIR and film-density grain remain modeled when enabled.","GPU export uses v41 f32 finite-kernel spatial/grain and six-Gaussian diffusion approximations. CPU fallback retains original PSF and full-image spatial operators.","GPU and CPU grain are seeded in absolute image coordinates but use different sampling models and are not pixel-identical. Actual algorithm and backend are reported in metrics.","Output is a local linear RGB float32 little-endian spool, not a JPEG or PNG; app must encode it without resizing."]}).to_string())
    }));
    if let Some(job_id)=progress_job {if let Ok(engine)=mobile::get(handle){if let Some(last_stage)=mobile::progress_finish(&engine,job_id,match &result{Ok(Ok(_))=>None,Ok(Err(e))=>Some(e.code),Err(_)=>Some("ENGINE_PANIC")}){stage=last_stage;}}}
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"exportFile",stage,started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Native export failed safely; partial output is invalid and removed"),"exportFile",stage,started.elapsed().as_secs_f64()*1000.)};
    // RESOURCE_PROBE_BEGIN
    #[cfg(feature="resource-audit")]
    let json = resource_probe.attach(json);
    // RESOURCE_PROBE_END
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)] mod tests {use super::*;#[test] fn overlap_and_overflow_rejected(){assert!(!non_overlapping(100,100,20));assert!(!non_overlapping(100,110,20));assert!(non_overlapping(100,120,20));assert!(!non_overlapping(usize::MAX-2,0,4));}
#[test] fn ranges_overlap_uses_each_ranges_own_length(){assert!(ranges_overlap(0,12,8,4));assert!(!ranges_overlap(0,12,12,4));assert!(!ranges_overlap(0,12,100,4));assert!(ranges_overlap(100,12,96,12));}
#[test] fn ranges_overlap_does_not_flag_small_output_below_input(){// v32 regression: a small (count*4) output just below the (count*12) input must not be flagged; the old symmetric check measured the output with the input's length and false-positived.
let input=100usize;let output=96usize;assert!(!ranges_overlap(input,12,output,4));assert!(!non_overlapping(input,output,12));}
#[test] fn ranges_overlap_overflow_is_safe(){assert!(ranges_overlap(usize::MAX-4,16,0,4));}}

/// Optional cache ABI. Older libraries safely miss through the Kotlin LinkageError guard.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeEncodedReuseProbe(mut env:JNIEnv<'_>,_this:JObject<'_>,params:JString<'_>)->jstring {
    let result=catch_unwind(AssertUnwindSafe(||->Result<String>{
        let params=mobile::MobileParams::parse(&string(&mut env,&params)?)?;
        Ok(serde_json::json!({"ok":true,"identity":mobile::encoded_reuse_probe(params.use_gpu)}).to_string())
    }));
    let json=match result{Ok(Ok(v))=>v,_=>"{\"ok\":false}".into()};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
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

fn native_capabilities_json()->String {
    serde_json::json!({"ok":true,"schemaVersion":1,"nativeAbiRevision":2,"previewStageCacheAbi":mobile::PREVIEW_STAGE_CACHE_ABI,"previewStageCandidateBytesPerPixel":mobile::PREVIEW_CANDIDATE_BYTES_PER_PIXEL,"refinedPreviewAbi":if cfg!(feature="adreno-gpu"){1}else{0},"refinedPreviewMaxEdge":mobile::refined::MAX_EDGE,"sharedPreviewMeteringAbi":1,"preparedSpectralCacheAbi":1,"preparedSpectralCacheBudgetBytes":mobile::PREPARED_ASSET_BUDGET_BYTES,"sharedPreviewMeteringAlgorithm":mobile::PREVIEW_METERING_ALGORITHM,"previewRgba8":true,"bitmapPreview":cfg!(target_os="android"),"previewLinearBitmap":cfg!(target_os="android"),"gpuResidentApproximate":cfg!(feature="adreno-gpu"),"gpuAlgorithm":spektrafilm_gpu::RESIDENT_APPROXIMATION_VERSION,"fullResolutionExport":true,"boundedGpuWait":cfg!(feature="adreno-gpu"),"maxPreviewPixels":mobile::MAX_PIXELS}).to_string()
}
#[cfg(test)]
#[test]
fn corrected_density_capability_matches_active_reservation() {
    let value:serde_json::Value=serde_json::from_str(&native_capabilities_json()).unwrap();
    assert_eq!(value["previewStageCacheAbi"],3);
    assert_eq!(value["previewStageCandidateBytesPerPixel"],mobile::PREVIEW_CANDIDATE_BYTES_PER_PIXEL);
    assert_eq!(mobile::PREVIEW_CANDIDATE_BYTES_PER_PIXEL,48);
}

/// Capability handshake belongs to the loaded library, not a Kotlin constant.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeCapabilities(env:JNIEnv<'_>,_this:JObject<'_>)->jstring {
    let json=native_capabilities_json();
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[cfg(test)]
mod resource_guard_tests {
    use super::*;use std::cell::Cell;
    #[test] fn release_on_error_and_unwind_and_exactly_once() {
        let n=Cell::new(0);
        {let _g=ReleaseGuard::new(||{n.set(n.get()+1);Ok(())});}
        assert_eq!(n.get(),1);
        let _=catch_unwind(AssertUnwindSafe(||{let _g=ReleaseGuard::new(||{n.set(n.get()+1);Ok(())});panic!("render failure");}));
        assert_eq!(n.get(),2);
        ReleaseGuard::new(||{n.set(n.get()+1);Ok(())}).finish().unwrap();assert_eq!(n.get(),3);
    }
    #[test] fn first_resource_released_when_second_acquisition_fails() {
        let n=Cell::new(0);let attempt=(||->Result<()>{let _first=ReleaseGuard::new(||{n.set(n.get()+1);Ok(())});Err(EngineError::new("INVALID_BUFFER","Second lock failed"))})();
        assert!(attempt.is_err());assert_eq!(n.get(),1);
    }
}

/// Bounded read-only metering. Java owns source lease, reservation and cancellation publication.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeFilmBridge_nativeMeasurePreview(mut env:JNIEnv<'_>,_this:JObject<'_>,input:JByteBuffer<'_>,width:jint,height:jint,method:JString<'_>)->jstring {
    let started=Instant::now();
    let result=catch_unwind(AssertUnwindSafe(||->Result<String>{
        if !(1..=256).contains(&width)||!(1..=256).contains(&height){return Err(EngineError::new("INVALID_DIMENSIONS","Canonical meter grid must be at most 256"));}
        let required=mobile::required_bytes(width,height)?;
        let ptr=buffer(&mut env,&input,required,false)?;
        let method=string(&mut env,&method)?;
        let bytes=unsafe{std::slice::from_raw_parts(ptr,required)};
        let pixels:Vec<f32>=bytes.chunks_exact(4).map(|v|f32::from_ne_bytes(v.try_into().unwrap())).collect();
        let ev=mobile::measure_preview(&pixels,width as u32,height as u32,&method)?;
        Ok(serde_json::json!({"ok":true,"algorithm":mobile::PREVIEW_METERING_ALGORITHM,"method":method,"ev":ev}).to_string())
    }));
    let json=match result {Ok(Ok(s))=>s,Ok(Err(e))=>mobile::error_json(e,"preview_metering","measure",started.elapsed().as_secs_f64()*1000.),Err(_)=>mobile::error_json(EngineError::new("ENGINE_PANIC","Meter failed safely"),"preview_metering","measure",0.)};
    env.new_string(json).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod prepared_asset_preview_metrics_tests {
    use super::*;
    #[test]
    fn preview_success_exposes_request_and_retained_front_metrics() {
        let params=mobile::MobileParams::default();
        let mut output=mobile::RenderOutput {refined_plan:None,
            preview_cache:mobile::PreviewStageMetrics::default(),
            prepared_spectral:mobile::PreparedAssetFrameMetrics {pipeline_prepare_ms:1.25,front_hits:1,front_misses:0,front_build_nanoseconds:0,calibration_hits:1,calibration_misses:0,calibration_build_nanoseconds:0,
                cache:spektrafilm_core::prepared_spectral::PreparedSpectralStats {available:true,enabled:true,hits:7,calibration_hits:3,calibration_retained_bytes:512,retained_entries:1,retained_bytes:1024,live_bytes:2048,max_bytes:8*1024*1024,..Default::default()}},
            metered_ev:None,gpu:crate::adreno::AdrenoDensityBackend::with_enabled(false).diagnostics(),pixels:vec![0.;3],elapsed_ms:2.,warnings:vec![],
        };
        let result:serde_json::Value=serde_json::from_str(&preview_ok_json(&output,&params,1,1,"linear-rgb-f32")).unwrap();
        assert_eq!(result["preparedSpectral"]["frontHits"],1);
        assert_eq!(result["preparedSpectral"]["pipelinePrepareMs"],1.25);
        assert_eq!(result["preparedSpectral"]["calibrationHits"],1);
        assert_eq!(result["preparedSpectral"]["calibrationMisses"],0);
        assert_eq!(result["preparedSpectral"]["calibrationBuildNanoseconds"],0);
        assert_eq!(result["preparedSpectral"]["cache"]["calibrationHits"],3);
        assert_eq!(result["preparedSpectral"]["cache"]["calibrationRetainedBytes"],512);
        assert_eq!(result["preparedSpectral"]["cache"]["hits"],7);
        assert_eq!(result["preparedSpectral"]["cache"]["liveBytes"],2048);
        assert_eq!(result["maxPixels"],mobile::MAX_PIXELS);
        assert!(result["refinedPlan"].is_null());
        output.refined_plan=Some(mobile::refined::PlanMetrics{abi:1,estimated_peak_bytes:300_000_000,external_bytes:16_000_000,
            full_frame_reserve_bytes:60_000_000,global_field_reserve_bytes:1024,core_width:512,core_height:256,
            halo:32,expanded_width:576,expanded_height:320});
        let refined:serde_json::Value=serde_json::from_str(&preview_ok_json(&output,&params,1536,1536,"srgb-rgba8")).unwrap();
        assert_eq!(refined["maxPixels"],1536*1536);
        assert_eq!(refined["estimatedPeakNativeBytes"],300_000_000);
        assert_eq!(refined["refinedPlan"]["abi"],1);
        assert_eq!(refined["refinedPlan"]["coreWidth"],512);
    }
}
