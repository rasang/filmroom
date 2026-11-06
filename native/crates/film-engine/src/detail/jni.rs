//! Separate opt-in detail JNI ABI. Existing NativeFilmBridge/export ABI is untouched.
//! Java retains exclusive access to each direct output buffer until this call returns.
use super::{DetailConfig, DetailContext, bridge};
use crate::mobile::MobileParams;
use jni::{
    JNIEnv,
    objects::{JByteBuffer, JObject, JString},
    sys::{jint, jlong, jstring},
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
};
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Request {
    #[serde(default = "allow_gpu_attempt")]
    gpu_attempt: bool,
    #[serde(default)]
    region_output: bool,
    #[serde(default)]
    initial_region: Option<InitialRegion>,
    schema_version: u32,
    width: u32,
    height: u32,
    parameters: Value,
    source_revision: String,
    mapping_revision: String,
    data_revision: String,
    memory_budget_bytes: u64,
    disk_budget_bytes: u64,
    stage_cache_bytes: u64,
    spectrum_cache_bytes: u64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct InitialRegion { x: u32, y: u32, w: u32, h: u32 }
fn allow_gpu_attempt() -> bool {true}
fn parse(s: &str) -> Result<DetailConfig, &'static str> {
    if s.len() > 32768 {
        return Err("INVALID_REQUEST");
    }
    let r: Request = serde_json::from_str(s).map_err(|_| "INVALID_REQUEST")?;
    if r.schema_version != 1
        || [&r.source_revision, &r.mapping_revision, &r.data_revision]
            .iter()
            .any(|s| s.is_empty() || s.len() > 1024)
    {
        return Err("INVALID_REQUEST");
    }
    let initial_region=r.initial_region.map(|v| super::Rect {
        x:v.x as usize,y:v.y as usize,w:v.w as usize,h:v.h as usize,
    });
    if initial_region.is_some_and(|v| !v.valid(r.width as usize,r.height as usize)) {
        return Err("INVALID_REQUEST");
    }
    let params = MobileParams::parse(&r.parameters.to_string()).map_err(|e| {
        if e.code == "UNSUPPORTED_PROFILE" {
            "UNSUPPORTED"
        } else {
            "INVALID_REQUEST"
        }
    })?;
    Ok(DetailConfig {
        gpu_attempt: r.gpu_attempt,
        region_output: r.region_output,
        initial_region,
        width: r.width,
        height: r.height,
        params,
        source_revision: r.source_revision,
        mapping_revision: r.mapping_revision,
        data_revision: r.data_revision,
        memory_budget_bytes: usize::try_from(r.memory_budget_bytes)
            .map_err(|_| "INVALID_REQUEST")?,
        disk_budget_bytes: r.disk_budget_bytes,
        stage_cache_bytes: usize::try_from(r.stage_cache_bytes).map_err(|_| "INVALID_REQUEST")?,
        spectrum_cache_bytes: usize::try_from(r.spectrum_cache_bytes)
            .map_err(|_| "INVALID_REQUEST")?,
    })
}
fn text(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Result<String, &'static str> {
    if s.is_null() {
        return Err("INVALID_REQUEST");
    }
    let length = env
        .call_method(s, "length", "()I", &[])
        .and_then(|v| v.i())
        .map_err(|_| "INVALID_REQUEST")?;
    if length < 0 || length > 32768 {
        return Err("INVALID_REQUEST");
    }
    env.get_string(s)
        .map(Into::into)
        .map_err(|_| "INVALID_REQUEST")
}
fn id(id: jlong) -> Result<u64, &'static str> {
    if id <= 0 {
        Err("INVALID_HANDLE")
    } else {
        Ok(id as u64)
    }
}
fn output(
    env: &mut JNIEnv<'_>,
    b: &JByteBuffer<'_>,
    bytes: usize,
) -> Result<*mut u8, &'static str> {
    if b.is_null() {
        return Err("INVALID_BUFFER");
    }
    let capacity = env
        .get_direct_buffer_capacity(b)
        .map_err(|_| "INVALID_BUFFER")?;
    let position = env
        .call_method(b, "position", "()I", &[])
        .and_then(|v| v.i())
        .map_err(|_| "INVALID_BUFFER")?;
    let limit = env
        .call_method(b, "limit", "()I", &[])
        .and_then(|v| v.i())
        .map_err(|_| "INVALID_BUFFER")?;
    if position != 0 || limit < 0 || (limit as usize) < bytes || capacity < bytes {
        return Err("INVALID_BUFFER");
    }
    if env
        .call_method(b, "isReadOnly", "()Z", &[])
        .and_then(|v| v.z())
        .map_err(|_| "INVALID_BUFFER")?
    {
        return Err("INVALID_BUFFER");
    }
    let native = env
        .call_static_method(
            "java/nio/ByteOrder",
            "nativeOrder",
            "()Ljava/nio/ByteOrder;",
            &[],
        )
        .and_then(|v| v.l())
        .map_err(|_| "INVALID_BUFFER")?;
    let order = env
        .call_method(b, "order", "()Ljava/nio/ByteOrder;", &[])
        .and_then(|v| v.l())
        .map_err(|_| "INVALID_BUFFER")?;
    if !env
        .is_same_object(native, order)
        .map_err(|_| "INVALID_BUFFER")?
    {
        return Err("INVALID_BUFFER");
    }
    let ptr = env
        .get_direct_buffer_address(b)
        .map_err(|_| "INVALID_BUFFER")?;
    if ptr.is_null() || (ptr as usize) % 4 != 0 {
        return Err("INVALID_BUFFER");
    }
    Ok(ptr)
}
fn reply(
    mut env: JNIEnv<'_>,
    operation: &str,
    f: impl FnOnce(&mut JNIEnv<'_>) -> Result<Value, &'static str>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if matches!(operation,"create"|"build"|"tile"|"region") {
            crate::mobile::gpu_completion_guard().map_err(|e|e.code)?;
        }
        f(&mut env)
    }));
    let mut value = match result {
        Ok(Ok(value)) => value,
        Ok(Err(code)) => json!({"ok":false,"code":code,"outputValid":false}),
        Err(_) => json!({"ok":false,"code":"INTERNAL_ERROR","outputValid":false}),
    };
    if value["code"]=="GPU_COMPLETION_UNCERTAIN" { value["stage"]=json!("scientific_pipeline"); }
    value["schemaVersion"] = json!(1);
    value["operation"] = json!(operation);
    // Clear low-level JNI exceptions before returning a sanitized typed status.
    // Never echo a filesystem path, serde diagnostic or raw exception message.
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
fn ok() -> Value {
    json!({"ok":true,"code":"OK","outputValid":false})
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeCapabilities(
    env: JNIEnv<'_>,
    _: JObject<'_>,
) -> jstring {
    reply(env, "capabilities", |_| {
        let profiles:Vec<Value>=crate::profile_catalog::FILMS.iter().map(|film|json!({"filmStock":film,"printSupported":!crate::profile_catalog::is_reversal(film),"directSupported":crate::profile_catalog::is_reversal(film),"reason":"SUPPORTED_ROUTE_FINITE_GRAIN_ONLY"})).collect();
        Ok(
            json!({"ok":true,"code":"OK","outputValid":false,"actualBackend":"unresolved","gpuRequestPolicy":"residentDetailOrExplicitCpuFallback","precision":"backend-specific","quality":"backend-specific","gpuAlgorithm":"owned-detail-v2-resident-f32-psf6-cap64-fir256-grain-rms-v1","cpuAlgorithm":"owned-detail-v1-f64-fixed256-gutter1","coreEdge":256,"visibleRegion":true,"outputRgba8":true,"outputRgba8Abi":1,"gutter":1,"maxDimension":1536,"profiles":profiles,"unsupported":["negativeDirectRoute","scannerBlur","scannerSharpen","scannerGlare"],"cpuOnlyUnsupported":["nonlocalGrain","sequentialGrainRng"],"sourceScope":"cache/detail-preview/sources","dataScope":"files/engine-data","requiresSharedReservation":true}),
        )
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativePlan(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    request: JString<'_>,
) -> jstring {
    reply(env, "plan", |env| {
        let c = parse(&text(env, &request)?)?;
        let plan = DetailContext::preflight(&c).map_err(|e| bridge::BridgeError::from(e).code())?;
        Ok(
            json!({"ok":true,"code":"OK","outputValid":false,"plan":plan,"actualBackend":"unresolved","requestedGpu":c.params.use_gpu}),
        )
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeCreate(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    root: JString<'_>,
) -> jstring {
    reply(env, "create", |env| {
        let root = text(env, &root)?;
        bridge::cleanup_orphans(Path::new(&root)).map_err(|e| e.code())?;
        let h = bridge::create(Path::new(&root)).map_err(|e| e.code())?;
        Ok(json!({"ok":true,"code":"OK","outputValid":false,"sessionId":h.to_string()}))
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeBeginOperation(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
) -> jstring {
    reply(env, "beginOperation", |_| {
        let operation = bridge::begin_operation(id(session)?).map_err(|e| e.code())?;
        Ok(json!({"ok":true,"code":"OK","outputValid":false,"operationId":operation.to_string()}))
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeCancel(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
    operation: jlong,
) -> jstring {
    reply(env, "cancel", |_| {
        bridge::cancel(id(session)?, id(operation)?).map_err(|e| e.code())?;
        Ok(ok())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeDestroy(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
) -> jstring {
    reply(env, "destroy", |_| {
        bridge::destroy(id(session)?).map_err(|e| e.code())?;
        Ok(ok())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeBuild(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
    operation: jlong,
    source: JString<'_>,
    request: JString<'_>,
) -> jstring {
    reply(env, "build", |env| {
        let session = id(session)?;
        let operation = id(operation)?;
        let parsed = (|| {
            let source = text(env, &source)?;
            let config = parse(&text(env, &request)?)?;
            Ok::<_, &'static str>((source, config))
        })();
        let (source, config) = match parsed {
            Ok(v) => v,
            Err(e) => {
                let _ = bridge::release_operation(session, operation);
                return Err(e);
            }
        };
        let result =
            bridge::build(session, operation, config, Path::new(&source)).map_err(|e| e.code())?;
        result.check_publish().map_err(|e| e.code())?;
        Ok(
            json!({"ok":true,"code":"OK","outputValid":false,"identity":result.context().identity(),"backend":result.context().backend_info(),"plan":result.context().resource_plan(),"meteredEv":result.context().metered_ev(),"quality":result.context().backend_info().quality(),"detailPerformance":result.context().build_performance()}),
        )
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeTile(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
    operation: jlong,
    cell_x: jint,
    cell_y: jint,
    buffer: JByteBuffer<'_>,
) -> jstring {
    reply(env, "tile", |env| {
        let session = id(session)?;
        let operation = id(operation)?;
        let validated = (|| {
            if cell_x < 0 || cell_y < 0 {
                return Err("INVALID_REQUEST");
            };
            let (_, sample) = bridge::tile_layout(session, cell_x as usize, cell_y as usize)
                .map_err(|e| e.code())?;
            let bytes = sample
                .w
                .checked_mul(sample.h)
                .and_then(|v| v.checked_mul(12))
                .ok_or("INVALID_REQUEST")?;
            let ptr = output(env, &buffer, bytes)?;
            Ok::<_, &'static str>((ptr, bytes))
        })();
        let (ptr, bytes) = match validated {
            Ok(v) => v,
            Err(e) => {
                let _ = bridge::release_operation(session, operation);
                return Err(e);
            }
        };
        let result = bridge::tile(session, operation, cell_x as usize, cell_y as usize)
            .map_err(|e| e.code())?;
        result.check_publish().map_err(|e| e.code())?;
        if result.tile.rgb.len() * 4 != bytes {
            return Err("INTERNAL_ERROR");
        }
        // Java local buffer reference remains alive; capacity/alignment/order/writability
        // checked before computation. Caller must not concurrently use this buffer/aliases.
        unsafe { std::ptr::copy_nonoverlapping(result.tile.rgb.as_ptr() as *const u8, ptr, bytes) };
        // Cancellation may race commit: an invalid result must be ignored by the UI;
        // generation guards remain necessary even after this final operation check.
        result.check_publish().map_err(|e| e.code())?;
        Ok(
            json!({"ok":true,"code":"OK","outputValid":true,"bytesWritten":bytes,"coreRect":result.tile.core_rect,"sampleRect":result.tile.sample_rect,"identity":result.tile.identity,"backend":result.tile.backend,"quality":result.tile.backend.quality()}),
        )
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeRegion(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
    operation: jlong,
    x: jint,
    y: jint,
    width: jint,
    height: jint,
    buffer: JByteBuffer<'_>,
) -> jstring {
    reply(env, "region", |env| {
        let session = id(session)?;
        let operation = id(operation)?;
        let validated = (|| {
            if x < 0 || y < 0 || width <= 0 || height <= 0 {
                return Err("INVALID_REQUEST");
            };
            let (_, sample) = bridge::region_layout(session, super::Rect{x:x as usize,y:y as usize,w:width as usize,h:height as usize})
                .map_err(|e| e.code())?;
            let bytes = sample
                .w
                .checked_mul(sample.h)
                .and_then(|v| v.checked_mul(12))
                .ok_or("INVALID_REQUEST")?;
            let ptr = output(env, &buffer, bytes)?;
            Ok::<_, &'static str>((ptr, bytes))
        })();
        let (ptr, bytes) = match validated {
            Ok(v) => v,
            Err(e) => {
                let _ = bridge::release_operation(session, operation);
                return Err(e);
            }
        };
        // The validated direct buffer remains strongly held by the JNI local ref.
        // Caller promises exclusive access until this synchronous operation returns.
        let output=unsafe { std::slice::from_raw_parts_mut(ptr as *mut f32,bytes/4) };
        let result=bridge::region_into(session,operation,super::Rect{x:x as usize,y:y as usize,w:width as usize,h:height as usize},output)
            .map_err(|e|e.code())?;
        result.check_publish().map_err(|e| e.code())?;
        Ok(
            json!({"ok":true,"code":"OK","outputValid":true,"bytesWritten":bytes,"coreRect":result.core_rect,"sampleRect":result.sample_rect,"identity":result.identity,"backend":result.backend,"quality":result.backend.quality(),"detailPerformance":result.performance}),
        )
    })
}
/// RGBA8 variant of `nativeTile`: identical tile render and metadata, but the
/// output buffer receives packed sRGB-encoded RGBA8 (`sample.w*sample.h*4`
/// bytes) instead of linear RGB float32, so the Java side can hand it straight
/// to `Bitmap.copyPixelsFromBuffer` instead of a per-pixel Kotlin encode loop.
/// Replies keep the "tile" operation name so existing response validation applies.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeTileRgba8(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
    operation: jlong,
    cell_x: jint,
    cell_y: jint,
    buffer: JByteBuffer<'_>,
) -> jstring {
    reply(env, "tile", |env| {
        let session = id(session)?;
        let operation = id(operation)?;
        let validated = (|| {
            if cell_x < 0 || cell_y < 0 {
                return Err("INVALID_REQUEST");
            };
            let (_, sample) = bridge::tile_layout(session, cell_x as usize, cell_y as usize)
                .map_err(|e| e.code())?;
            let bytes = sample
                .w
                .checked_mul(sample.h)
                .and_then(|v| v.checked_mul(4))
                .ok_or("INVALID_REQUEST")?;
            let ptr = output(env, &buffer, bytes)?;
            Ok::<_, &'static str>((ptr, bytes))
        })();
        let (ptr, bytes) = match validated {
            Ok(v) => v,
            Err(e) => {
                let _ = bridge::release_operation(session, operation);
                return Err(e);
            }
        };
        let result = bridge::tile(session, operation, cell_x as usize, cell_y as usize)
            .map_err(|e| e.code())?;
        result.check_publish().map_err(|e| e.code())?;
        // Preserve the legacy Kotlin contract: non-finite output invalidates the tile.
        if result.tile.rgb.iter().any(|v| !v.is_finite()) {
            return Err("INVALID_RESULT");
        }
        let rgba8 = crate::mobile::linear_srgb_to_rgba8(&result.tile.rgb);
        if rgba8.len() != bytes {
            return Err("INTERNAL_ERROR");
        }
        // Java local buffer reference remains alive; capacity/alignment/order/writability
        // checked before computation. Caller must not concurrently use this buffer/aliases.
        unsafe { std::ptr::copy_nonoverlapping(rgba8.as_ptr(), ptr, bytes) };
        // Cancellation may race commit: an invalid result must be ignored by the UI;
        // generation guards remain necessary even after this final operation check.
        result.check_publish().map_err(|e| e.code())?;
        Ok(
            json!({"ok":true,"code":"OK","outputValid":true,"outputFormat":"srgb-rgba8","bytesWritten":bytes,"coreRect":result.tile.core_rect,"sampleRect":result.tile.sample_rect,"identity":result.tile.identity,"backend":result.tile.backend,"quality":result.tile.backend.quality()}),
        )
    })
}
/// RGBA8 variant of `nativeRegion`: renders into a temporary linear RGB float32
/// working buffer via the same `region_into` path, then sRGB-encodes to packed
/// RGBA8 (`sample.w*sample.h*4` bytes) before committing to the Java buffer.
/// Replies keep the "region" operation name so existing response validation applies.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeRegionRgba8(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
    operation: jlong,
    x: jint,
    y: jint,
    width: jint,
    height: jint,
    buffer: JByteBuffer<'_>,
) -> jstring {
    reply(env, "region", |env| {
        let session = id(session)?;
        let operation = id(operation)?;
        let validated = (|| {
            if x < 0 || y < 0 || width <= 0 || height <= 0 {
                return Err("INVALID_REQUEST");
            };
            let (_, sample) = bridge::region_layout(session, super::Rect{x:x as usize,y:y as usize,w:width as usize,h:height as usize})
                .map_err(|e| e.code())?;
            let floats = sample
                .w
                .checked_mul(sample.h)
                .and_then(|v| v.checked_mul(3))
                .ok_or("INVALID_REQUEST")?;
            let bytes = sample
                .w
                .checked_mul(sample.h)
                .and_then(|v| v.checked_mul(4))
                .ok_or("INVALID_REQUEST")?;
            let ptr = output(env, &buffer, bytes)?;
            Ok::<_, &'static str>((ptr, floats, bytes))
        })();
        let (ptr, floats, bytes) = match validated {
            Ok(v) => v,
            Err(e) => {
                let _ = bridge::release_operation(session, operation);
                return Err(e);
            }
        };
        let mut rgb = vec![0f32; floats];
        let result=bridge::region_into(session,operation,super::Rect{x:x as usize,y:y as usize,w:width as usize,h:height as usize},&mut rgb)
            .map_err(|e|e.code())?;
        result.check_publish().map_err(|e| e.code())?;
        // Preserve the legacy Kotlin contract: non-finite output invalidates the region.
        if rgb.iter().any(|v| !v.is_finite()) {
            return Err("INVALID_RESULT");
        }
        let rgba8 = crate::mobile::linear_srgb_to_rgba8(&rgb);
        if rgba8.len() != bytes {
            return Err("INTERNAL_ERROR");
        }
        // The validated direct buffer remains strongly held by the JNI local ref.
        // Caller promises exclusive access until this synchronous operation returns.
        unsafe { std::ptr::copy_nonoverlapping(rgba8.as_ptr(), ptr, bytes) };
        result.check_publish().map_err(|e| e.code())?;
        Ok(
            json!({"ok":true,"code":"OK","outputValid":true,"outputFormat":"srgb-rgba8","bytesWritten":bytes,"coreRect":result.core_rect,"sampleRect":result.sample_rect,"identity":result.identity,"backend":result.backend,"quality":result.backend.quality(),"detailPerformance":result.performance}),
        )
    })
}
/// Explicitly abandon an operation which the dispatcher will never invoke.
/// Unlike cancel, this removes its pending ID; running work retains its lease.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeDetailBridge_nativeReleaseOperation(
    env: JNIEnv<'_>,
    _: JObject<'_>,
    session: jlong,
    operation: jlong,
) -> jstring {
    reply(env, "releaseOperation", |_| {
        bridge::release_operation(id(session)?, id(operation)?).map_err(|e| e.code())?;
        Ok(ok())
    })
}


#[cfg(test)]
mod admission_tests {
    use super::*;
    fn request()->Value {json!({"schemaVersion":1,"width":16,"height":12,
        "parameters":{"useGpu":true},"sourceRevision":"s","mappingRevision":"m","dataRevision":"d",
        "memoryBudgetBytes":536870912u64,"diskBudgetBytes":1073741824u64,
        "stageCacheBytes":2097152u64,"spectrumCacheBytes":67108864u64})}
    #[test]
    fn initial_region_request_is_optional_strict_and_not_scientific() {
        let mut r=request();let baseline=parse(&r.to_string()).unwrap();assert!(baseline.initial_region.is_none());
        r["initialRegion"]=json!({"x":4,"y":3,"w":12,"h":9});
        let hinted=parse(&r.to_string()).unwrap();
        assert_eq!(hinted.initial_region,Some(super::super::Rect{x:4,y:3,w:12,h:9}));
        assert_eq!(serde_json::to_string(&hinted.params).unwrap(),serde_json::to_string(&baseline.params).unwrap());
        for bad in [json!({"x":0,"y":0,"w":0,"h":1}),json!({"x":16,"y":0,"w":1,"h":1}),
            json!({"x":0,"y":11,"w":1,"h":2}),json!({"x":-1,"y":0,"w":1,"h":1}),
            json!({"x":0.5,"y":0,"w":1,"h":1}),json!({"x":0,"y":0,"w":1}),
            json!({"x":0,"y":0,"w":1,"h":1,"extra":0}),
            json!({"x":u64::MAX,"y":0,"w":1,"h":1}),json!([])] {
            r["initialRegion"]=bad;assert_eq!(parse(&r.to_string()).unwrap_err(),"INVALID_REQUEST");
        }
        r["initialRegion"]=Value::Null;assert!(parse(&r.to_string()).unwrap().initial_region.is_none());
    }
    #[test]
    fn operational_gpu_attempt_defaults_true_but_never_rewrites_use_gpu() {
        let mut r=request();let original=parse(&r.to_string()).unwrap();assert!(original.gpu_attempt);
        r["gpuAttempt"]=json!(false);let cpu=parse(&r.to_string()).unwrap();
        assert!(!cpu.gpu_attempt);assert!(cpu.params.use_gpu);
        assert_eq!(serde_json::to_string(&original.params).unwrap(),serde_json::to_string(&cpu.params).unwrap());
        r["gpuAttempt"]=json!("false");assert!(parse(&r.to_string()).is_err());
    }
}
