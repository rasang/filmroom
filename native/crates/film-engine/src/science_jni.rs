//! Private one-shot scientific worker bridge; the app confirms process death.
use jni::{
    JNIEnv,
    objects::{JObject, JString},
    sys::{jlong, jstring},
};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
};
static GATE: crate::research_diffusion::probe::ProcessGate =
    crate::research_diffusion::probe::ProcessGate::new();
fn text(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Result<String, String> {
    if s.is_null() {
        return Err("INVALID_ARGUMENT".into());
    }
    let n = env
        .call_method(s, "length", "()I", &[])
        .map_err(|_| "INVALID_ARGUMENT")?
        .i()
        .map_err(|_| "INVALID_ARGUMENT")?;
    if !(0..=4096).contains(&n) {
        return Err("INVALID_ARGUMENT".into());
    }
    let v: String = env.get_string(s).map_err(|_| "INVALID_ARGUMENT")?.into();
    if v.len() > 4096 {
        return Err("INVALID_ARGUMENT".into());
    }
    Ok(v)
}
fn reply(env: &mut JNIEnv<'_>, r: Result<serde_json::Value, String>) -> jstring {
    let v = match r {
        Ok(v) => v,
        Err(code) => serde_json::json!({"ok":false,"schemaVersion":1,"code":code}),
    };
    let s = v.to_string();
    let s = if s.len() <= 16384 {
        s
    } else {
        "{\"ok\":false,\"code\":\"RESULT_TOO_LARGE\"}".into()
    };
    env.new_string(s)
        .map(|v| v.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuAutoExposureStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let r=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=text(&mut env,&dir)?;let plan=crate::research_global::full_stage::plan_stage(Path::new(&dir),token as u64)?;if plan.operation!="fullAutoExposure"{return Err("INVALID_SCIENCE_OPERATION".into());}Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuAutoExposureStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuAutoExposureStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if token > 0 {
            GATE.cancel(token as u64);
        }
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuAutoExposureStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        }
        let dir = text(&mut env, &dir)?;
        let sha = text(&mut env, &sha)?;
        let plan = crate::research_global::full_stage::plan_stage(Path::new(&dir), token as u64)?;
        if plan.operation != "fullAutoExposure" || plan.manifest_sha256 != sha {
            return Err("INVALID_SCIENCE_OPERATION".into());
        }
        GATE.claim(token as u64)?;
        crate::research_global::full_stage::run_stage(
            Path::new(&dir),
            token as u64,
            budget as u64,
            &sha,
            || GATE.is_cancelled(token as u64),
        )
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuHighlightBoostStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let r=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=text(&mut env,&dir)?;let plan=crate::research_global::full_stage::plan_stage(Path::new(&dir),token as u64)?;if plan.operation!="fullHighlightBoost"{return Err("INVALID_SCIENCE_OPERATION".into());}Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuHighlightBoostStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuHighlightBoostStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if token > 0 {
            GATE.cancel(token as u64);
        }
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuHighlightBoostStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        }
        let dir = text(&mut env, &dir)?;
        let sha = text(&mut env, &sha)?;
        let plan = crate::research_global::full_stage::plan_stage(Path::new(&dir), token as u64)?;
        if plan.operation != "fullHighlightBoost" || plan.manifest_sha256 != sha {
            return Err("INVALID_SCIENCE_OPERATION".into());
        }
        GATE.claim(token as u64)?;
        crate::research_global::full_stage::run_stage(
            Path::new(&dir),
            token as u64,
            budget as u64,
            &sha,
            || GATE.is_cancelled(token as u64),
        )
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuExposureLogStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let r=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=text(&mut env,&dir)?;let plan=crate::research_global::full_stage::plan_stage(Path::new(&dir),token as u64)?;if plan.operation!="fullExposureLog"{return Err("INVALID_SCIENCE_OPERATION".into());}Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuExposureLogStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuExposureLogStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if token > 0 {
            GATE.cancel(token as u64);
        }
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuExposureLogStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        }
        let dir = text(&mut env, &dir)?;
        let sha = text(&mut env, &sha)?;
        let plan = crate::research_global::full_stage::plan_stage(Path::new(&dir), token as u64)?;
        if plan.operation != "fullExposureLog" || plan.manifest_sha256 != sha {
            return Err("INVALID_SCIENCE_OPERATION".into());
        }
        GATE.claim(token as u64)?;
        crate::research_global::full_stage::run_stage(
            Path::new(&dir),
            token as u64,
            budget as u64,
            &sha,
            || GATE.is_cancelled(token as u64),
        )
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuFilmGrainStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let r=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=text(&mut env,&dir)?;let plan=crate::research_grain::full_stage::plan_stage(Path::new(&dir),token as u64)?;if plan.operation!="fullFilmGrain"{return Err("INVALID_SCIENCE_OPERATION".into());}Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmGrainStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuFilmGrainStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if token > 0 {
            GATE.cancel(token as u64);
        }
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuFilmGrainStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        }
        let dir = text(&mut env, &dir)?;
        let sha = text(&mut env, &sha)?;
        let plan = crate::research_grain::full_stage::plan_stage(Path::new(&dir), token as u64)?;
        if plan.operation != "fullFilmGrain" || plan.manifest_sha256 != sha {
            return Err("INVALID_SCIENCE_OPERATION".into());
        }
        GATE.claim(token as u64)?;
        crate::research_grain::full_stage::run_stage(
            Path::new(&dir),
            token as u64,
            budget as u64,
            &sha,
            || GATE.is_cancelled(token as u64),
        )
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuPrintExposureStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let r=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=text(&mut env,&dir)?;let plan=crate::research_finish::plan_stage(Path::new(&dir),token as u64)?;if plan.operation!="fullPrintExposure"{return Err("INVALID_SCIENCE_OPERATION".into());}Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuPrintExposureStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuPrintExposureStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if token > 0 {
            GATE.cancel(token as u64);
        }
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuPrintExposureStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        }
        let dir = text(&mut env, &dir)?;
        let sha = text(&mut env, &sha)?;
        let plan = crate::research_finish::plan_stage(Path::new(&dir), token as u64)?;
        if plan.operation != "fullPrintExposure" || plan.manifest_sha256 != sha {
            return Err("INVALID_SCIENCE_OPERATION".into());
        }
        GATE.claim(token as u64)?;
        crate::research_finish::run_stage(
            Path::new(&dir),
            token as u64,
            budget as u64,
            &sha,
            || GATE.is_cancelled(token as u64),
        )
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuPrintDiffusionStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let r=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=text(&mut env,&dir)?;let plan=crate::research_diffusion::full_stage::plan_stage(Path::new(&dir),token as u64)?;if plan.operation!="fullPrintDiffusion"{return Err("INVALID_SCIENCE_OPERATION".into());}Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuPrintDiffusionStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuPrintDiffusionStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if token > 0 {
            GATE.cancel(token as u64);
        }
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuPrintDiffusionStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        }
        let dir = text(&mut env, &dir)?;
        let sha = text(&mut env, &sha)?;
        let plan =
            crate::research_diffusion::full_stage::plan_stage(Path::new(&dir), token as u64)?;
        if plan.operation != "fullPrintDiffusion" || plan.manifest_sha256 != sha {
            return Err("INVALID_SCIENCE_OPERATION".into());
        }
        GATE.claim(token as u64)?;
        crate::research_diffusion::full_stage::run_stage(
            Path::new(&dir),
            token as u64,
            budget as u64,
            &sha,
            || GATE.is_cancelled(token as u64),
        )
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuScanTailStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let r=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=text(&mut env,&dir)?;let plan=crate::research_finish_tail::full_stage::plan_stage(Path::new(&dir),token as u64)?;if plan.operation!="fullScanTail"{return Err("INVALID_SCIENCE_OPERATION".into());}Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuScanTailStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuScanTailStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if token > 0 {
            GATE.cancel(token as u64);
        }
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuScanTailStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        }
        let dir = text(&mut env, &dir)?;
        let sha = text(&mut env, &sha)?;
        let plan =
            crate::research_finish_tail::full_stage::plan_stage(Path::new(&dir), token as u64)?;
        if plan.operation != "fullScanTail" || plan.manifest_sha256 != sha {
            return Err("INVALID_SCIENCE_OPERATION".into());
        }
        GATE.claim(token as u64)?;
        crate::research_finish_tail::full_stage::run_stage(
            Path::new(&dir),
            token as u64,
            budget as u64,
            &sha,
            || GATE.is_cancelled(token as u64),
        )
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativePlanGpuFinishTailStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    token: jlong,
) -> jstring {
    let r=catch_unwind(AssertUnwindSafe(||{if token<=0{return Err("INVALID_TOKEN".into());}let dir=text(&mut env,&dir)?;let plan=crate::research_finish::fused::plan_stage(Path::new(&dir),token as u64)?;if plan.operation!="fullFinishTail"{return Err("INVALID_SCIENCE_OPERATION".into());}Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFinishTailStage","plan":plan}))})).unwrap_or_else(|_|Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeCancelGpuFinishTailStage(
    _: JNIEnv<'_>,
    _: JObject<'_>,
    token: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if token > 0 {
            GATE.cancel(token as u64);
        }
    }));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeRunGpuFinishTailStage(
    mut env: JNIEnv<'_>,
    _: JObject<'_>,
    dir: JString<'_>,
    budget: jlong,
    token: jlong,
    sha: JString<'_>,
) -> jstring {
    let r = catch_unwind(AssertUnwindSafe(|| {
        if token <= 0 || budget <= 0 {
            return Err("INVALID_ARGUMENT".into());
        }
        let dir = text(&mut env, &dir)?;
        let sha = text(&mut env, &sha)?;
        let plan =
            crate::research_finish::fused::plan_stage(Path::new(&dir), token as u64)?;
        if plan.operation != "fullFinishTail" || plan.manifest_sha256 != sha {
            return Err("INVALID_SCIENCE_OPERATION".into());
        }
        GATE.claim(token as u64)?;
        crate::research_finish::fused::run_stage(
            Path::new(&dir),
            token as u64,
            budget as u64,
            &sha,
            || GATE.is_cancelled(token as u64),
        )
    }))
    .unwrap_or_else(|_| Err("GPU_STAGE_PANIC".into()));
    reply(&mut env, r)
}
