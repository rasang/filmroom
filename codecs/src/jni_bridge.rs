use crate::{
    develop::{Budget, admit, decode},
    dng::Dng,
    error::{Result, err, io_error},
};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jlong, jstring},
};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicI64, Ordering},
    },
    time::Instant,
};
struct Handle {
    root: PathBuf,
    cancel: AtomicBool,
    busy: AtomicBool,
}
static HANDLES: OnceLock<Mutex<HashMap<i64, Arc<Handle>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn handles() -> &'static Mutex<HashMap<i64, Arc<Handle>>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn handle(id: i64) -> Result<Arc<Handle>> {
    handles()
        .lock()
        .map_err(|_| err("INVALID_HANDLE", "validate"))?
        .get(&id)
        .cloned()
        .ok_or_else(|| err("INVALID_HANDLE", "validate"))
}
fn path(h: &Handle, p: &str, output: bool) -> Result<PathBuf> {
    let p = Path::new(p);
    if !p.is_absolute() {
        return Err(err("INVALID_PATH", "validate"));
    }
    if output {
        let parent = p
            .parent()
            .and_then(|x| x.canonicalize().ok())
            .ok_or_else(|| err("INVALID_PATH", "validate"))?;
        let name = p
            .file_name()
            .ok_or_else(|| err("INVALID_PATH", "validate"))?;
        if !parent.starts_with(&h.root) {
            return Err(err("INVALID_PATH", "validate"));
        }
        Ok(parent.join(name))
    } else {
        let p = p
            .canonicalize()
            .map_err(|_| err("INVALID_PATH", "validate"))?;
        if !p.starts_with(&h.root) || !p.is_file() {
            return Err(err("INVALID_PATH", "validate"));
        }
        Ok(p)
    }
}
struct Busy<'a>(&'a AtomicBool);
impl Drop for Busy<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}
fn run(id: i64, input: &str, output: Option<&str>, budget: &str) -> Value {
    let started = Instant::now();
    let op = if output.is_some() { "decode" } else { "probe" };
    let result = std::panic::catch_unwind(|| -> Result<Value> {
        let h = handle(id)?;
        if h.busy
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Err(err("BUSY", "validate"));
        }
        let _busy = Busy(&h.busy);
        if h.cancel.load(Ordering::Acquire) {
            return Err(err("CANCELLED", "validate"));
        }
        if budget.len() > 1024 {
            return Err(err("INVALID_INPUT", "validate"));
        }
        let mut budget_value: Value =
            serde_json::from_str(budget).map_err(|_| err("INVALID_INPUT", "validate"))?;
        let metadata_only = if output.is_none() {
            match budget_value
                .as_object_mut()
                .and_then(|v| v.remove("metadataOnly"))
            {
                None => false,
                Some(Value::Bool(value)) => value,
                _ => return Err(err("INVALID_INPUT", "validate")),
            }
        } else {
            false
        };
        let b: Budget =
            serde_json::from_value(budget_value).map_err(|_| err("INVALID_INPUT", "validate"))?;
        let input = path(&h, input, false)?;
        let (probe, admission) = if let Some(output) = output {
            let (p, a) = decode(&input, &path(&h, output, true)?, &b, &h.cancel)?;
            (p, Some(a))
        } else {
            let d = Dng::read(&mut std::fs::File::open(&input).map_err(|e| io_error(e, "probe"))?)?;
            (
                d.probe(),
                if metadata_only {
                    None
                } else {
                    Some(admit(&d, &b)?)
                },
            )
        };
        let mut v = serde_json::to_value(probe).map_err(|_| err("DECODE_FAILED", "complete"))?;
        v["admission"] =
            serde_json::to_value(admission).map_err(|_| err("DECODE_FAILED", "complete"))?;
        v["ok"] = json!(true);
        v["stage"] = json!("complete");
        v["outputValid"] = json!(output.is_some());
        v["metadataOnly"] = json!(metadata_only);
        Ok(v)
    })
    .unwrap_or_else(|_| Err(err("DECODE_FAILED", "decode")));
    let mut v = match result {
        Ok(v) => v,
        Err(e) => {
            let mut failure = json!({"ok":false,"code":e.code,"message":e.message,"stage":e.stage,"outputValid":false});
            // Failure-only, private path verified again; no validation/cancel probes.
            if e.stage != "validate" && e.code != "CANCELLED" {
                if let Ok(h) = handle(id) {
                    if let Ok(p) = path(&h, input, false) {
                        failure["technical"] = crate::technical::probe(&p);
                    }
                }
            }
            failure
        }
    };
    v["schemaVersion"] = json!(1);
    v["operation"] = json!(op);
    v["elapsedMs"] = json!(started.elapsed().as_millis().min(u64::MAX as u128) as u64);
    v
}
fn string(env: &mut JNIEnv, s: JString) -> Result<String> {
    env.get_string(&s)
        .map(|x| x.into())
        .map_err(|_| err("INVALID_INPUT", "validate"))
}
fn reply(env: &mut JNIEnv, v: Value) -> jstring {
    env.new_string(v.to_string())
        .map(|x| x.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_codec_NativeCodecBridge_nativeCreate(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
) -> jlong {
    let Ok(root) = string(&mut env, root) else {
        return 0;
    };
    let Ok(root) = Path::new(&root).canonicalize() else {
        return 0;
    };
    if !root.is_dir() || root.parent().is_none() {
        return 0;
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let Ok(mut map) = handles().lock() else {
        return 0;
    };
    map.insert(
        id,
        Arc::new(Handle {
            root,
            cancel: AtomicBool::new(false),
            busy: AtomicBool::new(false),
        }),
    );
    id
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_codec_NativeCodecBridge_nativeProbe(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    input: JString,
    budget: JString,
) -> jstring {
    let v = match (string(&mut env, input), string(&mut env, budget)) {
        (Ok(i), Ok(b)) => run(id, &i, None, &b),
        _ => {
            json!({"ok":false,"code":"INVALID_INPUT","message":"参数无效","stage":"validate","outputValid":false,"schemaVersion":1})
        }
    };
    reply(&mut env, v)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_codec_NativeCodecBridge_nativeDecode(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    input: JString,
    output: JString,
    budget: JString,
) -> jstring {
    let v = match (
        string(&mut env, input),
        string(&mut env, output),
        string(&mut env, budget),
    ) {
        (Ok(i), Ok(o), Ok(b)) => run(id, &i, Some(&o), &b),
        _ => {
            json!({"ok":false,"code":"INVALID_INPUT","message":"参数无效","stage":"validate","outputValid":false,"schemaVersion":1})
        }
    };
    reply(&mut env, v)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_codec_NativeCodecBridge_nativeCancel(
    _: JNIEnv,
    _: JClass,
    id: jlong,
) {
    if let Ok(h) = handle(id) {
        h.cancel.store(true, Ordering::Release);
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_codec_NativeCodecBridge_nativeDestroy(
    _: JNIEnv,
    _: JClass,
    id: jlong,
) {
    if let Ok(mut h) = handles().lock() {
        if let Some(h) = h.remove(&id) {
            h.cancel.store(true, Ordering::Release);
        }
    }
}
fn run_encode(id: i64, input: &str, output: &str, params: &str) -> Value {
    let start = Instant::now();
    let result = std::panic::catch_unwind(|| -> Result<Value> {
        let h = handle(id)?;
        if h.busy
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Err(err("BUSY", "validate"));
        }
        let _busy = Busy(&h.busy);
        if h.cancel.load(Ordering::Acquire) {
            return Err(err("CANCELLED", "validate"));
        }
        if params.len() > 2048 {
            return Err(err("INVALID_INPUT", "validate"));
        }
        let p: crate::export::ExportParams =
            serde_json::from_str(params).map_err(|_| err("INVALID_INPUT", "validate"))?;
        let r = crate::export::encode(
            &path(&h, input, false)?,
            &path(&h, output, true)?,
            &p,
            &h.cancel,
        )?;
        let mut v = serde_json::to_value(r).map_err(|_| err("ENCODE_FAILED", "encode"))?;
        v["ok"] = json!(true);
        v["stage"] = json!("complete");
        v["outputValid"] = json!(true);
        Ok(v)
    })
    .unwrap_or_else(|_| Err(err("ENCODE_FAILED", "encode")));
    let mut v = match result {
        Ok(v) => v,
        Err(e) => {
            json!({"ok":false,"code":e.code,"message":e.message,"stage":e.stage,"outputValid":false})
        }
    };
    v["schemaVersion"] = json!(1);
    v["operation"] = json!("encode");
    v["elapsedMs"] = json!(start.elapsed().as_millis().min(u64::MAX as u128) as u64);
    v
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_codec_NativeCodecBridge_nativeEncode(
    mut env: JNIEnv,
    _: JClass,
    id: jlong,
    input: JString,
    output: JString,
    params: JString,
) -> jstring {
    let v = match (
        string(&mut env, input),
        string(&mut env, output),
        string(&mut env, params),
    ) {
        (Ok(i), Ok(o), Ok(p)) => run_encode(id, &i, &o, &p),
        _ => {
            json!({"ok":false,"code":"INVALID_INPUT","message":"参数无效","stage":"validate","outputValid":false,"schemaVersion":1})
        }
    };
    reply(&mut env, v)
}

#[cfg(test)]
mod technical_failure_tests {
    use super::*;
    #[test]
    fn failure_has_numeric_technical_but_validation_and_cancel_do_not() {
        let root = std::env::temp_dir().join(format!("film-codec-failure-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let root = root.canonicalize().unwrap();
        let input = root.join("PRIVATE_GPS_NAME.dng");
        // Minimal classic TIFF with 50 MP dimensions, but deliberately no DNG tags.
        let mut b = b"II\x2a\0\x08\0\0\0\x02\0".to_vec();
        for (tag, value) in [(256u16, 8192u32), (257, 6144)] {
            b.extend(tag.to_le_bytes());
            b.extend(4u16.to_le_bytes());
            b.extend(1u32.to_le_bytes());
            b.extend(value.to_le_bytes());
        }
        b.extend(0u32.to_le_bytes());
        std::fs::write(&input, b).unwrap();
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        handles().lock().unwrap().insert(
            id,
            Arc::new(Handle {
                root: root.clone(),
                cancel: AtomicBool::new(false),
                busy: AtomicBool::new(false),
            }),
        );
        let budget = r#"{"memoryBudgetMiB":128,"diskBudgetBytes":1073741824}"#;
        let result = run(id, input.to_str().unwrap(), None, budget);
        assert_eq!(result["ok"], false);
        assert_eq!(result["code"], "UNSUPPORTED_FORMAT");
        assert_eq!(result["stage"], "probe");
        assert_eq!(result["technical"]["rawWidth"], 8192);
        assert!(
            result["technical"]
                .as_object()
                .unwrap()
                .values()
                .all(Value::is_u64)
        );
        assert!(!result.to_string().contains("PRIVATE_GPS_NAME"));
        assert!(
            run(id, "relative-path", None, budget)
                .get("technical")
                .is_none()
        );
        // Both parsers reject this bounded malformed IFD. Technical failure
        // must not replace the original decoder classification or stage.
        let mut malformed = std::fs::read(&input).unwrap();
        malformed[8..10].copy_from_slice(&257u16.to_le_bytes());
        std::fs::write(&input, malformed).unwrap();
        let malformed = run(id, input.to_str().unwrap(), None, budget);
        assert_eq!(malformed["code"], "INVALID_INPUT");
        assert_eq!(malformed["stage"], "probe");
        assert_eq!(malformed["technical"]["rawProbeStatus"], 2);
        handle(id).unwrap().cancel.store(true, Ordering::Release);
        assert!(
            run(id, input.to_str().unwrap(), None, budget)
                .get("technical")
                .is_none()
        );
        handles().lock().unwrap().remove(&id);
        std::fs::remove_file(input).unwrap();
        std::fs::remove_dir(root).unwrap();
    }
}
