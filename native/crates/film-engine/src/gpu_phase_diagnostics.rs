//! Privacy-safe helper telemetry. No driver call, callback, IO or serialization
//! runs under REGISTRY. A separate thread can observe a blocked native worker.
//! The callback is advisory: unavailable diagnostics never alter image science.
use serde::Serialize;
use std::{
    cell::RefCell,
    collections::VecDeque,
    rc::Rc,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};

const MAX_TOKENS: usize = 16;
const MAX_EVENTS: usize = 32;
#[derive(Clone, Copy, Debug)]
#[repr(u8)]
pub(crate) enum Phase {
    Prepare = 1,
    Device = 2,
    Pipeline = 3,
    Upload = 4,
    Compute = 5,
    Readback = 6,
    Guard = 7,
    Commit = 8,
    Cleanup = 9,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Event {
    step_code: u8,
    activity_code: u8,
    sequence: u64,
    phase_code: u8,
    state_code: u8,
    elapsed_ms: u64,
    total_elapsed_ms: u64,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Snapshot {
    step_code: u8,
    activity_code: u8,
    schema_version: u8,
    token: u64,
    stage_code: u8,
    sequence: u64,
    phase_code: u8,
    state_code: u8,
    elapsed_ms: u64,
    total_elapsed_ms: u64,
    terminal_code: u8,
    diagnostic_code: u8,
    failure_code: u8,
    events: Vec<Event>,
}
struct Active {
    id: u64,
    phase: u8,
    step: u8,
    activity: u8,
    at: Instant,
}
struct Entry {
    snapshot: Snapshot,
    started: Instant,
    active: Vec<Active>,
    step: u8,
    activity: u8,
}
#[derive(Default)]
struct Registry {
    entries: VecDeque<Entry>,
}
fn ms(d: Duration) -> u64 {
    d.as_millis().min(i64::MAX as u128) as u64
}
impl Entry {
    fn snapshot(&self) -> Snapshot {
        let mut s = self.snapshot.clone();
        if s.terminal_code == 0 {
            s.total_elapsed_ms = ms(self.started.elapsed());
        }
        if let Some(active) = self.active.last() {
            s.step_code = active.step;
            s.activity_code = active.activity;
            s.phase_code = active.phase;
            s.state_code = 1;
            s.elapsed_ms = ms(active.at.elapsed());
        }
        s
    }
    fn event(&mut self, phase: u8, state: u8, elapsed: u64) {
        self.snapshot.sequence = self.snapshot.sequence.saturating_add(1);
        self.snapshot.phase_code = phase;
        self.snapshot.state_code = state;
        self.snapshot.elapsed_ms = elapsed;
        self.snapshot.total_elapsed_ms = ms(self.started.elapsed());
        if self.snapshot.events.len() == MAX_EVENTS {
            self.snapshot.events.remove(0);
        }
        self.snapshot.events.push(Event {
            step_code: self.snapshot.step_code,
            activity_code: self.snapshot.activity_code,
            sequence: self.snapshot.sequence,
            phase_code: phase,
            state_code: state,
            elapsed_ms: elapsed,
            total_elapsed_ms: self.snapshot.total_elapsed_ms,
        });
    }
}
impl Registry {
    fn begin(&mut self, token: u64, stage: u8) -> bool {
        if token == 0 || self.entries.iter().any(|e| e.snapshot.token == token) {
            return false;
        }
        if self.entries.len() == MAX_TOKENS {
            if let Some(index) = self
                .entries
                .iter()
                .position(|e| e.snapshot.terminal_code != 0)
            {
                self.entries.remove(index);
            } else {
                return false;
            }
        }
        self.entries.push_back(Entry {
            snapshot: Snapshot {
                step_code: 0,
                activity_code: 0,
                schema_version: 1,
                token,
                stage_code: stage,
                sequence: 0,
                phase_code: 0,
                state_code: 0,
                elapsed_ms: 0,
                total_elapsed_ms: 0,
                terminal_code: 0,
                diagnostic_code: 0,
                failure_code: 0,
                events: Vec::with_capacity(MAX_EVENTS),
            },
            started: Instant::now(),
            active: Vec::new(),
            step: 0,
            activity: 0,
        });
        true
    }
    fn entry(&mut self, token: u64) -> Option<&mut Entry> {
        self.entries.iter_mut().find(|e| e.snapshot.token == token)
    }
    fn start(&mut self, token: u64, phase: Phase) -> Option<u64> {
        let e = self.entry(token)?;
        if e.snapshot.terminal_code != 0 || e.active.len() >= 16 {
            return None;
        }
        e.snapshot.step_code = e.step;
        e.snapshot.activity_code = e.activity;
        e.event(phase as u8, 1, 0);
        let id = e.snapshot.sequence;
        e.active.push(Active {
            id,
            phase: phase as u8,
            step: e.snapshot.step_code,
            activity: e.snapshot.activity_code,
            at: Instant::now(),
        });
        Some(id)
    }
    fn end(&mut self, token: u64, id: u64, ok: bool) -> Option<()> {
        let e = self.entry(token)?;
        let index = e.active.iter().position(|a| a.id == id)?;
        let active = e.active.remove(index);
        e.snapshot.step_code = active.step;
        e.snapshot.activity_code = active.activity;
        e.event(
            active.phase,
            if ok { 2 } else { 3 },
            ms(active.at.elapsed()),
        );
        // The callback is the actual edge; independent snapshots restore a parent
        // scope while it remains active, never report a completed child as stuck.
        Some(())
    }
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}
type Callback = Rc<dyn Fn(&Snapshot) -> bool>;
#[derive(Default)]
struct Context {
    token: u64,
    callback: Option<Callback>,
    sent_edges: [u64; 12],
    last_sent: Option<Instant>,
}
thread_local! {static CONTEXT:RefCell<Context>=RefCell::new(Context::default());}
// Only copy the bounded event ring when a callback is due, not per dispatch.
// This function is allowed under REGISTRY: it only reads/writes thread-local
// integers and the monotonic clock, with no Java, allocation, driver or file IO.
fn should_emit(s: &Snapshot, force: bool) -> bool {
    CONTEXT.with(|context| {
        let mut c = context.borrow_mut();
        let key = s.activity_code as usize * 6 + s.step_code as usize;
        let bit = 1u64
            .checked_shl((s.phase_code as u32) * 4 + s.state_code as u32)
            .unwrap_or(0);
        let due = force
            || c.sent_edges[key] & bit == 0
            || c.last_sent
                .is_none_or(|t| t.elapsed() >= Duration::from_secs(1));
        if due {
            c.sent_edges[key] |= bit;
            c.last_sent = Some(Instant::now());
        }
        due
    })
}
fn emit(s: Snapshot) {
    let callback = CONTEXT.with(|context| context.borrow().callback.clone());
    let ok = callback.is_some_and(|callback| callback(&s));
    if !ok {
        if let Ok(mut r) = registry().lock() {
            if let Some(e) = r.entry(s.token) {
                if e.snapshot.diagnostic_code == 0 {
                    e.snapshot.diagnostic_code = 1;
                    e.event(
                        e.snapshot.phase_code,
                        e.snapshot.state_code,
                        e.snapshot.elapsed_ms,
                    );
                }
            }
        }
    }
}

pub(crate) struct PhaseGuard {
    token: u64,
    id: u64,
    done: bool,
}
impl PhaseGuard {
    pub(crate) fn begin(phase: Phase) -> Self {
        let token = CONTEXT.with(|c| c.borrow().token);
        let started = if token == 0 {
            None
        } else {
            registry().lock().ok().and_then(|mut r| {
                let id = r.start(token, phase)?;
                let s = &r.entry(token)?.snapshot;
                Some((id, should_emit(s, false).then(|| s.clone())))
            })
        };
        if let Some((id, s)) = started {
            if let Some(s) = s {
                emit(s);
            }
            Self {
                token,
                id,
                done: false,
            }
        } else {
            Self {
                token: 0,
                id: 0,
                done: true,
            }
        }
    }
    pub(crate) fn finish(mut self) {
        self.end(true);
    }
    fn end(&mut self, ok: bool) {
        if self.done {
            return;
        }
        self.done = true;
        let snapshot = registry().lock().ok().and_then(|mut r| {
            r.end(self.token, self.id, ok)?;
            let s = &r.entry(self.token)?.snapshot;
            should_emit(s, false).then(|| s.clone())
        });
        if let Some(s) = snapshot {
            emit(s);
        }
    }
}
impl Drop for PhaseGuard {
    fn drop(&mut self) {
        self.end(false);
    }
}
pub(crate) fn phase_result<T, E>(phase: Phase, f: impl FnOnce() -> Result<T, E>) -> Result<T, E> {
    let mut guard = PhaseGuard::begin(phase);
    let result = f();
    guard.end(result.is_ok());
    result
}
pub(crate) fn phase_value<T>(phase: Phase, f: impl FnOnce() -> T) -> T {
    let guard = PhaseGuard::begin(phase);
    let value = f();
    guard.finish();
    value
}

pub(crate) struct TaskScope {
    token: u64,
    previous: Option<Context>,
}
impl TaskScope {
    #[cfg(feature = "gpu-diffusion-research")]
    pub(crate) fn jni(env: &mut jni::JNIEnv<'_>, token: i64, stage: u8) -> Self {
        let callback = (|| {
            let vm = env.get_java_vm().ok()?;
            let class = env
                .find_class("dev/filmroom/engine/NativeGpuTelemetryBridge")
                .ok()?;
            let class = env.new_global_ref(class).ok()?;
            Some(Rc::new(move |s: &Snapshot| -> bool {
                let Ok(mut env) = vm.get_env() else {
                    return false;
                };
                let Ok(text) = serde_json::to_string(s) else {
                    return false;
                };
                let Ok(text) = env.new_string(text) else {
                    let _ = env.exception_clear();
                    return false;
                };
                let class: &jni::objects::JClass<'_> = class.as_obj().into();
                let result = env.call_static_method(
                    class,
                    "onNativePhase",
                    "(Ljava/lang/String;)Z",
                    &[jni::objects::JValue::Object(&text)],
                );
                // This native frame can emit thousands of phases. Bound JNI locals
                // independently of Java GC and never propagate telemetry exceptions.
                let _ = env.delete_local_ref(text);
                match result {
                    Ok(value) => value.z().unwrap_or(false),
                    Err(_) => {
                        let _ = env.exception_clear();
                        false
                    }
                }
            }) as Callback)
        })();
        if callback.is_none() {
            let _ = env.exception_clear();
        }
        Self::begin(token.max(0) as u64, stage, callback)
    }
    fn begin(token: u64, stage: u8, callback: Option<Callback>) -> Self {
        let registered = registry().lock().is_ok_and(|mut r| r.begin(token, stage));
        let previous = CONTEXT.with(|c| {
            c.replace(Context {
                token: if registered { token } else { 0 },
                callback,
                sent_edges: [0; 12],
                last_sent: None,
            })
        });
        Self {
            token: if registered { token } else { 0 },
            previous: Some(previous),
        }
    }
    pub(crate) fn finish<T>(&mut self, result: &Result<T, String>) {
        let terminal = match result {
            Ok(_) => 1,
            Err(code) if code == "GPU_STAGE_PANIC" => 4,
            Err(code) if code == "CANCELLED" || code == "GPU_STAGE_CANCELLED" => 3,
            Err(_) => 2,
        };
        self.finish_code(
            terminal,
            result.as_ref().err().map_or(0, |code| failure_code(code)),
        );
    }
    fn finish_code(&mut self, terminal: u8, failure: u8) {
        if self.token == 0 {
            return;
        }
        let snapshot = registry().lock().ok().and_then(|mut r| {
            let e = r.entry(self.token)?;
            e.snapshot.terminal_code = terminal;
            e.snapshot.failure_code = failure;
            e.event(
                e.snapshot.phase_code,
                e.snapshot.state_code,
                e.snapshot.elapsed_ms,
            );
            Some(e.snapshot())
        });
        if let Some(s) = snapshot {
            emit(s);
        }
        self.token = 0;
    }
}
impl Drop for TaskScope {
    fn drop(&mut self) {
        self.finish_code(if std::thread::panicking() { 4 } else { 2 }, 1);
        if let Some(previous) = self.previous.take() {
            CONTEXT.with(|c| {
                c.replace(previous);
            });
        }
    }
}
fn snapshot(token: u64) -> Snapshot {
    // Pollers never wait for telemetry. No GPU/Java/file lock is acquired here.
    if let Ok(r) = registry().try_lock() {
        if let Some(e) = r.entries.iter().find(|e| e.snapshot.token == token) {
            return e.snapshot();
        }
    }
    Snapshot {
        step_code: 0,
        activity_code: 0,
        schema_version: 1,
        token,
        stage_code: 0,
        sequence: 0,
        phase_code: 0,
        state_code: 0,
        elapsed_ms: 0,
        total_elapsed_ms: 0,
        terminal_code: 0,
        diagnostic_code: 1,
        failure_code: 0,
        events: Vec::new(),
    }
}
#[cfg(feature = "gpu-diffusion-research")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuProbeBridge_nativeGpuPhaseSnapshot(
    mut env: jni::JNIEnv<'_>,
    _: jni::objects::JObject<'_>,
    token: jni::sys::jlong,
) -> jni::sys::jstring {
    let text =
        serde_json::to_string(&snapshot(token.max(0) as u64)).unwrap_or_else(|_| "{}".into());
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_registry_preserves_active_and_evicts_completed() {
        let mut r = Registry::default();
        for t in 1..=MAX_TOKENS as u64 {
            assert!(r.begin(t, 1));
        }
        assert!(!r.begin(999, 1));
        r.entry(1).unwrap().snapshot.terminal_code = 1;
        assert!(r.begin(999, 8));
        assert_eq!(r.entries.len(), MAX_TOKENS);
        assert!(r.entry(2).is_some());
    }
    #[test]
    fn nested_real_edges_and_bounded_events() {
        let mut r = Registry::default();
        r.begin(7, 8);
        let parent = r.start(7, Phase::Guard).unwrap();
        for _ in 0..100 {
            let id = r.start(7, Phase::Readback).unwrap();
            r.end(7, id, true).unwrap();
            assert_eq!(r.entry(7).unwrap().snapshot.state_code, 2);
            assert_eq!(r.entry(7).unwrap().snapshot().phase_code, 7);
        }
        r.end(7, parent, false);
        let s = r.entry(7).unwrap().snapshot();
        assert_eq!(s.events.len(), MAX_EVENTS);
        assert_eq!(s.state_code, 3);
        assert_eq!(s.sequence, 202);
    }
    #[test]
    fn snapshot_only_contains_numeric_protocol_data() {
        let mut r = Registry::default();
        r.begin(17, 8);
        r.start(17, Phase::Compute);
        let value = serde_json::to_value(r.entry(17).unwrap().snapshot()).unwrap();
        fn numeric(v: &serde_json::Value) {
            match v {
                serde_json::Value::Object(o) => o.values().for_each(numeric),
                serde_json::Value::Array(a) => a.iter().for_each(numeric),
                serde_json::Value::Number(n) => assert!(n.as_u64().is_some()),
                _ => panic!("unsafe telemetry value"),
            }
        }
        numeric(&value);
    }
    #[test]
    fn callback_not_under_registry_lock_and_failure_advisory() {
        let cb: Callback = Rc::new(|s| {
            assert!(registry().try_lock().is_ok());
            assert!(s.token > 0);
            false
        });
        let mut scope = TaskScope::begin(900001, 1, Some(cb));
        phase_result(Phase::Device, || Ok::<_, String>(())).unwrap();
        scope.finish(&Ok::<_, String>(()));
        let s = snapshot(900001);
        assert_eq!(s.terminal_code, 1);
        assert_eq!(s.diagnostic_code, 1);
    }
    #[test]
    fn live_phase_is_visible_to_an_independent_reader() {
        let mut scope = TaskScope::begin(900002, 8, None);
        let phase = PhaseGuard::begin(Phase::Compute);
        let s = std::thread::spawn(|| snapshot(900002)).join().unwrap();
        assert_eq!(s.phase_code, 5);
        assert_eq!(s.state_code, 1);
        phase.finish();
        scope.finish(&Ok::<_, String>(()));
    }
}

/// Fields surround a Runner's driver-owned fields in declaration order. Rust
/// drops fields in declaration order after the value's Drop body. These scopes
/// therefore cover the *actual* Vulkan resource destructors on success/error,
/// not a fake cleanup event before destruction starts.
pub(crate) struct CleanupStart(std::sync::Arc<Mutex<Option<PhaseGuard>>>);
pub(crate) struct CleanupEnd(std::sync::Arc<Mutex<Option<PhaseGuard>>>);
pub(crate) fn cleanup_bounds() -> (CleanupStart, CleanupEnd) {
    let shared = std::sync::Arc::new(Mutex::new(None));
    (CleanupStart(shared.clone()), CleanupEnd(shared))
}
impl Drop for CleanupStart {
    fn drop(&mut self) {
        let phase = PhaseGuard::begin(Phase::Cleanup);
        if let Ok(mut slot) = self.0.lock() {
            *slot = Some(phase);
        }
    }
}
impl Drop for CleanupEnd {
    fn drop(&mut self) {
        let phase = self.0.lock().ok().and_then(|mut slot| slot.take());
        if let Some(phase) = phase {
            phase.finish();
        }
    }
}

/// Synthetic admission runs the original numerical gates, not image processing.
pub(crate) struct AdmissionScope {
    token: u64,
    previous: u8,
}
impl AdmissionScope {
    pub(crate) fn begin() -> Self {
        let token = CONTEXT.with(|c| c.borrow().token);
        let previous = registry()
            .lock()
            .ok()
            .and_then(|mut r| {
                let e = r.entry(token)?;
                let old = e.activity;
                e.activity = 1;
                Some(old)
            })
            .unwrap_or(0);
        Self { token, previous }
    }
}
impl Drop for AdmissionScope {
    fn drop(&mut self) {
        if let Ok(mut r) = registry().lock() {
            if let Some(e) = r.entry(self.token) {
                e.activity = self.previous;
            }
        }
    }
}

/// Distinguish adapter/device and shader/driver pipeline calls without carrying
/// names, driver error strings, paths, parameters or image data across JNI.
pub(crate) struct StepGuard {
    phase: Option<PhaseGuard>,
    token: u64,
    previous: u8,
}
impl StepGuard {
    pub(crate) fn begin(step: u8, phase: Phase) -> Self {
        let token = CONTEXT.with(|c| c.borrow().token);
        let previous = registry()
            .lock()
            .ok()
            .and_then(|mut r| {
                let e = r.entry(token)?;
                let old = e.step;
                e.step = step;
                Some(old)
            })
            .unwrap_or(0);
        Self {
            phase: Some(PhaseGuard::begin(phase)),
            token,
            previous,
        }
    }
    pub(crate) fn finish(mut self) {
        if let Some(phase) = self.phase.take() {
            phase.finish();
        }
    }
}
impl Drop for StepGuard {
    fn drop(&mut self) {
        drop(self.phase.take());
        if let Ok(mut r) = registry().lock() {
            if let Some(e) = r.entry(self.token) {
                e.step = self.previous;
            }
        }
    }
}

// Schema1 stable IDs: append only; never sort/reassign after release.
const FAILURE_CODES: &[&str] = &[
    "CANCELLED",
    "GPU_DEVICE_FAILED",
    "GPU_DIR_IO",
    "GPU_DIR_MEMORY_LIMIT",
    "GPU_DIR_NUMERIC_CHECK",
    "GPU_DIR_NUMERIC_DOMAIN",
    "GPU_DIR_UNSUPPORTED",
    "GPU_EXECUTION_FAILED",
    "GPU_EXPOSURE_HASH_MISMATCH",
    "GPU_EXPOSURE_INVALID_TABLE",
    "GPU_EXPOSURE_IO",
    "GPU_EXPOSURE_MEMORY_LIMIT",
    "GPU_EXPOSURE_NONFINITE",
    "GPU_EXPOSURE_NUMERIC_CHECK",
    "GPU_EXPOSURE_NUMERIC_DOMAIN",
    "GPU_EXPOSURE_OUTPUT_COLLISION",
    "GPU_EXPOSURE_READBACK_FAILED",
    "GPU_EXPOSURE_TIMEOUT",
    "GPU_EXPOSURE_UNSUPPORTED",
    "GPU_FINISH_IO",
    "GPU_FINISH_MEMORY_LIMIT",
    "GPU_FINISH_NUMERIC_CHECK",
    "GPU_FINISH_READBACK_FAILED",
    "GPU_FINISH_TIMEOUT",
    "GPU_FINISH_UNSUPPORTED",
    "GPU_FUSION_MEMORY_LIMIT",
    "GPU_FUSION_NUMERIC_DOMAIN",
    "GPU_FUSION_PREFIX_NUMERIC_CHECK",
    "GPU_FUSION_SHADER_VERSION",
    "GPU_FUSION_UNSUPPORTED",
    "GPU_GLOBAL_IO",
    "GPU_GLOBAL_MEMORY_LIMIT",
    "GPU_GLOBAL_NUMERIC_CHECK",
    "GPU_GLOBAL_REPORT",
    "GPU_GLOBAL_UNSUPPORTED",
    "GPU_GRAIN_COUNT_LIMIT",
    "GPU_GRAIN_GUARDED_PENDING",
    "GPU_GRAIN_GUARDED_REJECTION",
    "GPU_GRAIN_INTEGER_RNG_CHECK",
    "GPU_GRAIN_IO",
    "GPU_GRAIN_LAYER_LIMIT",
    "GPU_GRAIN_MEMORY_LIMIT",
    "GPU_GRAIN_MT_RNG_CHECK",
    "GPU_GRAIN_NUMERIC_CHECK",
    "GPU_GRAIN_PREPASS_BITS_CHECK",
    "GPU_GRAIN_SAMPLER_LIMIT",
    "GPU_GRAIN_SAMPLER_TUPLE_CHECK",
    "GPU_GRAIN_SEQUENTIAL_PENDING",
    "GPU_GRAIN_SHADER_VERSION",
    "GPU_GRAIN_SOFTWARE64_CHECK",
    "GPU_GRAIN_UNSUPPORTED",
    "GPU_GRAIN_UNSUPPORTED_CURVES",
    "GPU_OPTICS_BATCH_LIMIT",
    "GPU_OPTICS_ERROR_ABI",
    "GPU_OPTICS_IO",
    "GPU_OPTICS_MEMORY_LIMIT",
    "GPU_OPTICS_NUMERIC_CHECK",
    "GPU_OPTICS_NUMERIC_DOMAIN",
    "GPU_OPTICS_READBACK_FAILED",
    "GPU_OPTICS_SHADER_VERSION",
    "GPU_OPTICS_TABLES",
    "GPU_OPTICS_TIMEOUT",
    "GPU_OPTICS_UNSUPPORTED",
    "GPU_PREPARE_",
    "GPU_PREPARE_COPY_CREATE_EACCES",
    "GPU_PREPARE_COPY_HASH",
    "GPU_PREPARE_COPY_RESERVE",
    "GPU_PREPARE_COPY_VERIFY_EACCES",
    "GPU_PREPARE_COPY_WRITE_ENOSPC",
    "GPU_PREPARE_SOURCE_IDENTITY",
    "GPU_PREPARE_SOURCE_LINK_DESTINATION",
    "GPU_PREPARE_SOURCE_LINK_ENOSPC",
    "GPU_PREPARE_SOURCE_LINK_EXDEV",
    "GPU_PREPARE_SOURCE_NONFINITE",
    "GPU_PREPARE_SOURCE_OPEN_EACCES",
    "GPU_PREPARE_SOURCE_OPEN_ELOOP",
    "GPU_PREPARE_SOURCE_READ_EOF",
    "GPU_PREPARE_SOURCE_SHAPE",
    "GPU_PREPARE_STAGE_MKDIR_EEXIST",
    "GPU_PREPARE_STAGE_PATH",
    "GPU_STAGE_HASH_MISMATCH",
    "GPU_STAGE_IO",
    "GPU_STAGE_KERNEL_HASH",
    "GPU_STAGE_MEMORY_LIMIT",
    "GPU_STAGE_NUMERIC_CHECK",
    "GPU_STAGE_NUMERIC_DOMAIN",
    "GPU_STAGE_PANIC",
    "GPU_STAGE_READBACK_FAILED",
    "GPU_STAGE_RESULT_FAILED",
    "GPU_STAGE_TIMEOUT",
    "GPU_TAIL_GLARE_AMBIGUOUS",
    "GPU_TAIL_GLARE_ITERATIONS",
    "GPU_TAIL_GLARE_LIMIT",
    "GPU_TAIL_GLARE_STATE",
    "GPU_TAIL_IO",
    "GPU_TAIL_MEMORY_LIMIT",
    "GPU_TAIL_NUMERIC_CHECK",
    "GPU_TAIL_NUMERIC_DOMAIN",
    "GPU_TAIL_REPORT",
    "GPU_TAIL_SHADER_VERSION",
    "GPU_TAIL_STATE",
    "GPU_TAIL_UNSUPPORTED",
    "GPU_TILE_COUNT_LIMIT",
    "GPU_UNAVAILABLE_OR_LIMITS",
    "INSUFFICIENT_GPU_DIR_MEMORY",
    "INSUFFICIENT_GPU_EXPOSURE_MEMORY",
    "INSUFFICIENT_GPU_FINISH_MEMORY",
    "INSUFFICIENT_GPU_FUSION_MEMORY",
    "INSUFFICIENT_GPU_GLOBAL_MEMORY",
    "INSUFFICIENT_GPU_GRAIN_MEMORY",
    "INSUFFICIENT_GPU_OPTICS_MEMORY",
    "INSUFFICIENT_GPU_STAGE_MEMORY",
    "INSUFFICIENT_GPU_TAIL_MEMORY",
    "INSUFFICIENT_PROBE_MEMORY",
    "INVALID_ARGUMENT",
    "INVALID_CAPTURE_IDENTITY",
    "INVALID_DIR_COMPLETE",
    "INVALID_DIR_FILE",
    "INVALID_DIR_MANIFEST",
    "INVALID_DIR_OWNER",
    "INVALID_DIR_PLAN",
    "INVALID_DIR_RESULT",
    "INVALID_DIR_TABLE",
    "INVALID_EXPOSURE_CHUNK",
    "INVALID_EXPOSURE_FILE",
    "INVALID_EXPOSURE_FILES",
    "INVALID_EXPOSURE_MANIFEST",
    "INVALID_EXPOSURE_OUTPUT",
    "INVALID_EXPOSURE_OWNER",
    "INVALID_FINISH_BOUNDS",
    "INVALID_FINISH_CHUNK",
    "INVALID_FINISH_CURVE",
    "INVALID_FINISH_FILE",
    "INVALID_FINISH_FILES",
    "INVALID_FINISH_IDENTITY",
    "INVALID_FINISH_MANIFEST",
    "INVALID_FINISH_OUTPUT",
    "INVALID_FINISH_OUTPUT_HASH",
    "INVALID_FINISH_OWNER",
    "INVALID_FINISH_TABLE",
    "INVALID_FINISH_TABLE_HASH",
    "INVALID_FUSION_CHUNK",
    "INVALID_FUSION_IDENTITY",
    "INVALID_FUSION_LENGTH",
    "INVALID_FUSION_MANIFEST",
    "INVALID_FUSION_OUTPUT",
    "INVALID_FUSION_OWNER",
    "INVALID_FUSION_REPORT",
    "INVALID_GLOBAL_DIMENSIONS",
    "INVALID_GLOBAL_FILE",
    "INVALID_GLOBAL_IDENTITY",
    "INVALID_GLOBAL_INPUT",
    "INVALID_GLOBAL_KIND",
    "INVALID_GLOBAL_MANIFEST",
    "INVALID_GLOBAL_OUTPUT",
    "INVALID_GLOBAL_OWNER",
    "INVALID_GLOBAL_PARAMETERS",
    "INVALID_GLOBAL_PEAK",
    "INVALID_GLOBAL_PLAN",
    "INVALID_GLOBAL_SOURCE_HASH",
    "INVALID_GRAIN_COMPLETE",
    "INVALID_GRAIN_CURVE",
    "INVALID_GRAIN_FILE",
    "INVALID_GRAIN_INPUT",
    "INVALID_GRAIN_MANIFEST",
    "INVALID_GRAIN_OWNER",
    "INVALID_GRAIN_PLAN",
    "INVALID_GRAIN_RESULT",
    "INVALID_GRAIN_RNG_MODE",
    "INVALID_MANIFEST",
    "INVALID_OPTICS_COMPLETE",
    "INVALID_OPTICS_FILE",
    "INVALID_OPTICS_MANIFEST",
    "INVALID_OPTICS_OWNER",
    "INVALID_OPTICS_PLAN",
    "INVALID_PREVIEW_MT_STATE",
    "INVALID_PROBE_PLAN",
    "INVALID_SCIENCE_OPERATION",
    "INVALID_SNAPSHOT_DIRECTORY",
    "INVALID_SNAPSHOT_FILE",
    "INVALID_SNAPSHOT_FILES",
    "INVALID_SNAPSHOT_OWNER",
    "INVALID_STAGE_CHUNK",
    "INVALID_STAGE_FILE",
    "INVALID_STAGE_FILES",
    "INVALID_STAGE_KERNEL",
    "INVALID_STAGE_MANIFEST",
    "INVALID_STAGE_OUTPUT",
    "INVALID_STAGE_OWNER",
    "INVALID_STAGE_ROW",
    "INVALID_TAIL_FILE",
    "INVALID_TAIL_GAMUT",
    "INVALID_TAIL_GLARE",
    "INVALID_TAIL_IDENTITY",
    "INVALID_TAIL_INPUT",
    "INVALID_TAIL_MANIFEST",
    "INVALID_TAIL_OUTPUT",
    "INVALID_TAIL_OWNER",
    "INVALID_TAIL_PLAN",
    "INVALID_TOKEN",
    "NONFINITE_DIR_INPUT",
    "NONFINITE_DIR_OUTPUT",
    "NONFINITE_FINISH_INPUT",
    "NONFINITE_FINISH_OUTPUT",
    "NONFINITE_FINISH_TABLE",
    "NONFINITE_FUSION_INPUT",
    "NONFINITE_FUSION_OUTPUT",
    "NONFINITE_GLOBAL_INPUT",
    "NONFINITE_GLOBAL_INTERMEDIATE",
    "NONFINITE_GLOBAL_OUTPUT",
    "NONFINITE_GLOBAL_PARAMETERS",
    "NONFINITE_GPU_OUTPUT",
    "NONFINITE_GRAIN_INPUT",
    "NONFINITE_GRAIN_OUTPUT",
    "NONFINITE_MANIFEST",
    "NONFINITE_OPTICS_INPUT",
    "NONFINITE_OPTICS_OUTPUT",
    "NONFINITE_SNAPSHOT",
    "NONFINITE_STAGE_INPUT",
    "NONFINITE_STAGE_OUTPUT",
    "NONFINITE_TAIL_GLARE",
    "NONFINITE_TAIL_INPUT",
    "NONFINITE_TAIL_OUTPUT",
    "STALE_EXPOSURE_OUTPUT",
    "STALE_EXPOSURE_PLAN",
    "STALE_PROBE_PLAN",
    "STALE_STAGE_OUTPUT",
    "STALE_STAGE_PLAN",
    "GPU_GLOBAL_STREAM_MEMORY_LIMIT",
    "GPU_GLOBAL_STREAM_PENDING",
    "GPU_GLOBAL_STREAM_REPORT",
    "GPU_GLOBAL_STREAM_SHADER_VERSION",
    "INSUFFICIENT_GPU_GLOBAL_STREAM_MEMORY",
    "INVALID_GLOBAL_STREAM_CHUNK",
    "INVALID_GLOBAL_STREAM_IDENTITY",
    "INVALID_GLOBAL_STREAM_MANIFEST",
    "INVALID_GLOBAL_STREAM_OUTPUT",
    "INVALID_GLOBAL_STREAM_OWNER",
    "INVALID_GLOBAL_STREAM_PLAN",
    "INVALID_GLOBAL_STREAM_SOURCE",
    "INVALID_GLOBAL_STREAM_SOURCE_DIGEST",
    "INVALID_GLOBAL_STREAM_SOURCE_IDENTITY",
    "INVALID_GLOBAL_STREAM_SOURCE_RANGE",
];
fn failure_code(code: &str) -> u8 {
    FAILURE_CODES
        .iter()
        .position(|candidate| *candidate == code)
        .map_or(1, |i| (i + 2) as u8)
}

#[cfg(test)]
mod contract_tests {
    use super::*;
    #[test]
    fn native_serialization_matches_android_fixture() {
        let snapshot = Snapshot {
            schema_version: 1,
            token: 73001,
            stage_code: 8,
            sequence: 12,
            phase_code: 5,
            state_code: 1,
            elapsed_ms: 142,
            total_elapsed_ms: 2500,
            terminal_code: 0,
            diagnostic_code: 0,
            activity_code: 1,
            step_code: 0,
            failure_code: 0,
            events: vec![
                Event {
                    sequence: 11,
                    phase_code: 4,
                    state_code: 2,
                    elapsed_ms: 3,
                    total_elapsed_ms: 2358,
                    activity_code: 1,
                    step_code: 0,
                },
                Event {
                    sequence: 12,
                    phase_code: 5,
                    state_code: 1,
                    elapsed_ms: 0,
                    total_elapsed_ms: 2358,
                    activity_code: 1,
                    step_code: 0,
                },
            ],
        };
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../../../app/src/test/resources/gpu-native-phase-v1.json"
        ))
        .unwrap();
        assert_eq!(serde_json::to_value(snapshot).unwrap(), fixture);
    }
    #[test]
    fn exact_failure_legend_matches_closed_native_allowlist() {
        let legend: serde_json::Value =
            serde_json::from_str(include_str!("../../../GPU_PHASE_FAILURE_CODES.json")).unwrap();
        let codes = legend["failureCodes"].as_array().unwrap();
        assert_eq!(codes.len(), FAILURE_CODES.len() + 2);
        assert!(codes.len() <= 256);
        for (i, code) in FAILURE_CODES.iter().enumerate() {
            assert_eq!(codes[i + 2]["code"], i + 2);
            assert_eq!(codes[i + 2]["name"], *code);
            assert_eq!(failure_code(code) as usize, i + 2);
        }
        assert_eq!(
            failure_code("private driver message /data/user/secret.jpg"),
            1
        );
    }
    #[test]
    fn callback_and_snapshot_jni_contract_are_kept() {
        let bridge = include_str!(
            "../../../../app/src/main/java/dev/filmroom/engine/NativeGpuTelemetryBridge.kt"
        );
        assert!(bridge.contains("@Keep\nobject NativeGpuTelemetryBridge"));
        assert!(bridge.contains("@Keep @JvmStatic fun onNativePhase(raw: String): Boolean"));
        let probe = include_str!(
            "../../../../app/src/main/java/dev/filmroom/engine/NativeGpuProbeBridge.kt"
        );
        assert!(probe.contains("external fun nativeGpuPhaseSnapshot(token: Long): String"));
    }
    #[test]
    fn failure_is_sticky_and_terminal_has_new_sequence() {
        let calls = Rc::new(std::cell::Cell::new(0));
        let copy = calls.clone();
        let cb: Callback = Rc::new(move |_| {
            let n = copy.get();
            copy.set(n + 1);
            n == 0
        });
        let mut scope = TaskScope::begin(900003, 8, Some(cb));
        let phase = PhaseGuard::begin(Phase::Device);
        let before = snapshot(900003);
        assert_eq!(before.diagnostic_code, 0);
        phase.finish();
        let failed = snapshot(900003);
        assert_eq!(failed.diagnostic_code, 1);
        assert!(failed.sequence > before.sequence + 1);
        scope.finish(&Err::<(), _>("GPU_GRAIN_SOFTWARE64_CHECK".into()));
        let terminal = snapshot(900003);
        assert!(terminal.sequence > failed.sequence);
        assert_eq!(terminal.terminal_code, 2);
        assert_eq!(
            terminal.failure_code,
            failure_code("GPU_GRAIN_SOFTWARE64_CHECK")
        );
    }
    #[test]
    fn step_and_admission_survive_nested_dispatch_and_restore_context() {
        let mut scope = TaskScope::begin(900004, 8, Some(Rc::new(|_| true)));
        {
            let admission = AdmissionScope::begin();
            let phase = PhaseGuard::begin(Phase::Guard);
            phase_result(Phase::Compute, || {
                assert_eq!(snapshot(900004).activity_code, 1);
                Ok::<_, ()>(())
            })
            .unwrap();
            phase.finish();
            drop(admission);
        }
        assert_eq!(snapshot(900004).activity_code, 1); // last actual edge, not restored context
        {
            let step = StepGuard::begin(4, Phase::Pipeline);
            assert_eq!(snapshot(900004).step_code, 4);
            assert_eq!(snapshot(900004).activity_code, 0);
            step.finish();
        }
        phase_result(Phase::Compute, || {
            let s = snapshot(900004);
            assert_eq!(s.step_code, 0);
            assert_eq!(s.activity_code, 0);
            Ok::<_, ()>(())
        })
        .unwrap();
        scope.finish(&Ok::<_, String>(()));
    }
    #[test]
    fn cleanup_wraps_real_field_destructors() {
        struct Resource;
        impl Drop for Resource {
            fn drop(&mut self) {
                let s = snapshot(900005);
                assert_eq!(s.phase_code, 9);
                assert_eq!(s.state_code, 1);
            }
        }
        struct Owned {
            _start: CleanupStart,
            _resource: Resource,
            _end: CleanupEnd,
        }
        let mut scope = TaskScope::begin(900005, 8, Some(Rc::new(|_| true)));
        let (start, end) = cleanup_bounds();
        drop(Owned {
            _start: start,
            _resource: Resource,
            _end: end,
        });
        assert_eq!(snapshot(900005).state_code, 2);
        scope.finish(&Ok::<_, String>(()));
    }
    #[test]
    fn phase_overhead_microbenchmark_reports_without_timing_threshold() {
        const N: u64 = 100_000;
        let disabled = Instant::now();
        for _ in 0..N {
            phase_value(Phase::Compute, || ());
        }
        let disabled_ms = ms(disabled.elapsed());
        let callbacks = Rc::new(std::cell::Cell::new(0u64));
        let counted = callbacks.clone();
        let mut scope = TaskScope::begin(
            900006,
            8,
            Some(Rc::new(move |_| {
                counted.set(counted.get() + 1);
                true
            })),
        );
        let at = Instant::now();
        for _ in 0..N {
            phase_value(Phase::Compute, || ());
        }
        let enabled_ms = ms(at.elapsed());
        scope.finish(&Ok::<_, String>(()));
        // Two first-seen edges, one forced terminal, then <=1 callback per second.
        assert!(callbacks.get() <= 3 + enabled_ms / 1000);
        assert_eq!(snapshot(900006).events.len(), MAX_EVENTS);
        eprintln!(
            "GPU_PHASE_BENCH scopes={N} disabledMs={disabled_ms} enabledMs={enabled_ms} callbacks={}",
            callbacks.get()
        );
    }
}
