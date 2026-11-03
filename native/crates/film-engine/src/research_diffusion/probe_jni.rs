//! Separate diagnostic bridge. A process is allowed exactly one GPU invocation.
use jni::{
    JNIEnv,
    objects::{JObject, JString},
    sys::{jlong, jstring},
};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
};
static GATE: super::probe::ProcessGate = super::probe::ProcessGate::new();
fn string(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Result<String, String> {
    if s.is_null() {
        return Err("INVALID_ARGUMENT".into());
    };
    let chars = env
        .call_method(s, "length", "()I", &[])
        .map_err(|_| "INVALID_ARGUMENT")?
        .i()
        .map_err(|_| "INVALID_ARGUMENT")?;
    if chars < 0 || chars > 4096 {
        return Err("INVALID_ARGUMENT".into());
    }
    let v: String = env.get_string(s).map_err(|_| "INVALID_ARGUMENT")?.into();
    if v.len() > 4096 {
        return Err("INVALID_ARGUMENT".into());
    };
    Ok(v)
}
fn reply(env: &mut JNIEnv<'_>, v: Result<serde_json::Value, String>) -> jstring {
    let out = match v {
        Ok(v) => v,
        Err(code) => {
            serde_json::json!({"ok":false,"schemaVersion":1,"diagnosticOnly":true,"outputReplaced":false,"code":code})
        }
    };
    let text = out.to_string();
    let text = if text.len() <= 16384 {
        text
    } else {
        "{\"ok\":false,\"code\":\"RESULT_TOO_LARGE\"}".into()
    };
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuDiffusionProbe(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let result=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into())};let dir=string(&mut env,&dir)?;let plan=super::probe::plan(Path::new(&dir),token as u64)?;Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuDiffusionProbe","plan":plan}))})).unwrap_or_else(|_|Err("PROBE_PANIC".into()));
    reply(&mut env, result)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuDiffusionProbe(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    if token > 0 {
        GATE.cancel(token as u64);
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuDiffusionProbe(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        };
        GATE.claim(token as u64)?;
        let dir = string(&mut env, &dir)?;
        let sha = string(&mut env, &sha)?;
        super::probe::run(Path::new(&dir), budget as u64, token as u64, &sha, || {
            GATE.is_cancelled(token as u64)
        })
    }))
    .unwrap_or_else(|_| Err("PROBE_PANIC".into()));
    reply(&mut env, result)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuOpticalStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let result=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into())};let dir=string(&mut env,&dir)?;let plan=super::full_stage::plan_stage(Path::new(&dir),token as u64)?;Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuOpticalStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, result)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuOpticalStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    if token > 0 {
        GATE.cancel(token as u64)
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuOpticalStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        };
        GATE.claim(token as u64)?;
        let dir = string(&mut env, &dir)?;
        let sha = string(&mut env, &sha)?;
        super::full_stage::run_stage(Path::new(&dir), token as u64, budget as u64, &sha, || {
            GATE.is_cancelled(token as u64)
        })
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, result)
}
