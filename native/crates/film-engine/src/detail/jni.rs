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
    #[serde(default)]
    region_output: bool,
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
    let params = MobileParams::parse(&r.parameters.to_string()).map_err(|e| {
        if e.code == "UNSUPPORTED_PROFILE" {
            "UNSUPPORTED"
        } else {
            "INVALID_REQUEST"
        }
    })?;
    Ok(DetailConfig {
        region_output: r.region_output,
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
    let result = catch_unwind(AssertUnwindSafe(|| f(&mut env)));
    let mut value = match result {
        Ok(Ok(value)) => value,
        Ok(Err(code)) => json!({"ok":false,"code":code,"outputValid":false}),
        Err(_) => json!({"ok":false,"code":"INTERNAL_ERROR","outputValid":false}),
    };
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
            json!({"ok":true,"code":"OK","outputValid":false,"actualBackend":"cpu","gpuRequestPolicy":"explicitCpuFallback","precision":"f64-internal-f32-io","quality":"ExactSameWorkingGrid","coreEdge":256,"visibleRegion":true,"gutter":1,"maxDimension":1536,"profiles":profiles,"unsupported":["negativeDirectRoute","scannerBlur","scannerSharpen","scannerGlare","nonlocalGrain","sequentialGrainRng"],"sourceScope":"cache/detail-preview/sources","dataScope":"files/engine-data","requiresSharedReservation":true}),
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
            json!({"ok":true,"code":"OK","outputValid":false,"plan":plan,"actualBackend":"cpu","requestedGpu":c.params.use_gpu,"cpuFallback":c.params.use_gpu}),
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
            json!({"ok":true,"code":"OK","outputValid":false,"identity":result.context().identity(),"backend":result.context().backend_info(),"plan":result.context().resource_plan(),"meteredEv":result.context().metered_ev(),"quality":"ExactSameWorkingGrid"}),
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
            json!({"ok":true,"code":"OK","outputValid":true,"bytesWritten":bytes,"coreRect":result.tile.core_rect,"sampleRect":result.tile.sample_rect,"identity":result.tile.identity,"backend":result.tile.backend,"quality":"ExactSameWorkingGrid"}),
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
            json!({"ok":true,"code":"OK","outputValid":true,"bytesWritten":bytes,"coreRect":result.core_rect,"sampleRect":result.sample_rect,"identity":result.identity,"backend":result.backend,"quality":"ExactSameWorkingGrid"}),
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
