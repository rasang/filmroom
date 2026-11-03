//! Direct-buffer scientific preview with private, independently owned file inputs.
//! The Java caller holds the per-engine render lock and the preview GPU lease.
//! A failed/abandoned invocation retains its roots until the caller positively
//! confirms helper termination and calls `cleanup_after_drain`.
//!
//! Like stage staging, this relies on app-private parents not being concurrently
//! renamed. An observed replacement fails closed. Cleanup never follows a link
//! or deletes an inode that was not registered by this invocation.
use crate::mobile::{self, Engine, EngineError, MobileParams, Result};
use crate::streamed::{
    GpuDiffusionDelegate, GpuDiffusionRequest, GpuStageDelegates, GpuStageRequests, ScienceRoute,
    StreamedMetrics,
};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        Mutex, OnceLock,
    },
    time::Instant,
};

const BUFFER_BYTES: usize = 64 * 1024;
const MAX_RETAINED_JOBS: usize = 4;
static NEXT_JOB: AtomicU64 = AtomicU64::new(0);

fn err(code: &'static str, message: &'static str) -> EngineError {
    EngineError::new(code, message)
}
fn io(_: std::io::Error) -> EngineError {
    err("IO_ERROR", "Private preview file operation failed")
}
fn nofollow(options: &mut OpenOptions) {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW); // O_NOFOLLOW
    }
}
fn identity(a: &fs::Metadata, b: &fs::Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev() && a.ino() == b.ino()
    }
    #[cfg(not(unix))]
    {
        let _ = (a, b);
        false
    }
}
fn unlinked(file: &File) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        file.metadata().is_ok_and(|m| m.nlink() == 0)
    }
    #[cfg(not(unix))]
    {
        let _ = file;
        false
    }
}
fn open_directory(path: &Path) -> Result<File> {
    let failure = || {
        err(
            "PREVIEW_CLEANUP_IDENTITY",
            "Private preview directory identity is not valid",
        )
    };
    let before = fs::symlink_metadata(path).map_err(|_| failure())?;
    if !before.is_dir()
        || before.file_type().is_symlink()
        || path.canonicalize().ok().as_deref() != Some(path)
    {
        return Err(failure());
    }
    let mut options = OpenOptions::new();
    options.read(true);
    nofollow(&mut options);
    let directory = options.open(path).map_err(|_| failure())?;
    if !identity(&before, &directory.metadata().map_err(|_| failure())?)
        || !identity(&before, &fs::symlink_metadata(path).map_err(|_| failure())?)
    {
        return Err(failure());
    }
    Ok(directory)
}
struct OwnedDirectory {
    path: PathBuf,
    directory: File,
    parent: File,
}
impl OwnedDirectory {
    fn capture(path: PathBuf) -> Result<Self> {
        let parent = open_directory(path.parent().ok_or_else(|| {
            err(
                "PREVIEW_CLEANUP_IDENTITY",
                "Private preview parent is absent",
            )
        })?)?;
        let directory = open_directory(&path)?;
        Ok(Self {
            path,
            directory,
            parent,
        })
    }
    fn matches(&self) -> Result<bool> {
        let invalid = || {
            err(
                "PREVIEW_CLEANUP_IDENTITY",
                "Private preview directory changed; retained without deletion",
            )
        };
        let parent_path = self.path.parent().ok_or_else(invalid)?;
        let parent = open_directory(parent_path)?;
        if !identity(
            &parent.metadata().map_err(|_| invalid())?,
            &self.parent.metadata().map_err(|_| invalid())?,
        ) {
            return Err(invalid());
        }
        match fs::symlink_metadata(&self.path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound && unlinked(&self.directory) => {
                Ok(false)
            }
            Err(_) => Err(invalid()),
            Ok(metadata) => {
                if !metadata.is_dir()
                    || metadata.file_type().is_symlink()
                    || self.path.canonicalize().ok().as_ref() != Some(&self.path)
                    || !identity(
                        &metadata,
                        &self.directory.metadata().map_err(|_| invalid())?,
                    )
                {
                    return Err(invalid());
                }
                let current = open_directory(&self.path)?;
                if !identity(
                    &current.metadata().map_err(|_| invalid())?,
                    &self.directory.metadata().map_err(|_| invalid())?,
                ) {
                    return Err(invalid());
                }
                Ok(true)
            }
        }
    }
    fn cleanup(&self) -> Result<()> {
        if self.matches()? {
            fs::remove_dir_all(&self.path).map_err(|_| {
                err(
                    "PREVIEW_CLEANUP_FAILED",
                    "Private preview cleanup failed; ownership retained",
                )
            })?;
        }
        if !unlinked(&self.directory) {
            return Err(err(
                "PREVIEW_CLEANUP_IDENTITY",
                "Private preview directory removal was not confirmed",
            ));
        }
        Ok(())
    }
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum State {
    Running,
    Retained,
    Cleaning,
}
struct Job {
    id: u64,
    state: State,
    root: OwnedDirectory,
    packets: Vec<OwnedDirectory>,
}
static JOBS: OnceLock<Mutex<BTreeMap<i64, Job>>> = OnceLock::new();
fn jobs() -> &'static Mutex<BTreeMap<i64, Job>> {
    JOBS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
fn registry_error() -> EngineError {
    err(
        "PREVIEW_REGISTRY_UNAVAILABLE",
        "Private preview ownership registry unavailable",
    )
}
struct JobGuard {
    handle: i64,
    id: u64,
    root: PathBuf,
    complete: bool,
}
impl JobGuard {
    fn create(cache: &Path, handle: i64) -> Result<Self> {
        if handle <= 0 {
            return Err(err("INVALID_HANDLE", "Preview needs a live engine handle"));
        }
        let mut registry = jobs().lock().map_err(|_| registry_error())?;
        if registry.contains_key(&handle) {
            return Err(err(
                "PREVIEW_DRAIN_REQUIRED",
                "A previous preview must finish cleanup before another preview",
            ));
        }
        if registry.len() >= MAX_RETAINED_JOBS {
            return Err(err(
                "PREVIEW_DRAIN_REQUIRED",
                "Pending preview cleanup limit reached",
            ));
        }
        let id = NEXT_JOB
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1))
            .map_err(|_| err("PREVIEW_DRAIN_REQUIRED", "Preview identity space exhausted"))?
            + 1;
        let epoch = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|_| err("INTERNAL_ERROR", "Preview identity unavailable"))?
            .as_nanos();
        let root = cache.join(format!("film-preview-{}-{epoch}-{id}", std::process::id()));
        // Exclusive creation, never claim or delete a pre-existing name.
        fs::create_dir(&root).map_err(io)?;
        let owned = OwnedDirectory::capture(root.clone())?;
        registry.insert(
            handle,
            Job {
                id,
                state: State::Running,
                root: owned,
                packets: Vec::new(),
            },
        );
        Ok(Self {
            handle,
            id,
            root,
            complete: false,
        })
    }
    fn track(&self, request: &mut GpuDiffusionRequest) {
        if let (Some(delegate), Some(dir)) = (request.delegate.take(), request.stage_dir.clone()) {
            request.delegate = Some(Box::new(TrackedDelegate {
                delegate,
                handle: self.handle,
                id: self.id,
                token: request.token,
                dir,
            }));
        }
    }
    fn finish(mut self) -> Result<()> {
        {
            let mut registry = jobs().lock().map_err(|_| registry_error())?;
            let job = registry
                .get_mut(&self.handle)
                .filter(|job| job.id == self.id)
                .ok_or_else(registry_error)?;
            if job.state != State::Running {
                return Err(registry_error());
            }
            job.state = State::Retained;
        }
        cleanup_after_drain(self.handle)?;
        self.complete = true;
        Ok(())
    }
}
impl Drop for JobGuard {
    fn drop(&mut self) {
        if !self.complete {
            if let Ok(mut registry) = jobs().lock() {
                if let Some(job) = registry.get_mut(&self.handle).filter(|j| j.id == self.id) {
                    if job.state == State::Running {
                        job.state = State::Retained;
                    }
                }
            }
        }
    }
}
struct TrackedDelegate {
    delegate: Box<dyn GpuDiffusionDelegate>,
    handle: i64,
    id: u64,
    token: u64,
    dir: PathBuf,
}
impl GpuDiffusionDelegate for TrackedDelegate {
    fn available_disk_bytes(&mut self) -> Option<u64> {
        self.delegate.available_disk_bytes()
    }
    fn begin(&mut self, request: &str) -> Option<bool> {
        // Register before forwarding. A delegate may launch and then panic or
        // lose its response; that must still retain its source dependencies.
        let wire: serde_json::Value = serde_json::from_str(request).ok()?;
        if wire["token"].as_u64() != Some(self.token)
            || wire["parentHandle"].as_i64() != Some(self.handle)
            || wire["jobDir"].as_str().map(Path::new) != Some(self.dir.as_path())
        {
            return Some(false);
        }
        let packet = match OwnedDirectory::capture(self.dir.clone()) {
            Ok(packet) => packet,
            Err(_) => return Some(false),
        };
        {
            let mut registry = match jobs().lock() {
                Ok(r) => r,
                Err(_) => return Some(false),
            };
            let Some(job) = registry
                .get_mut(&self.handle)
                .filter(|j| j.id == self.id && j.state == State::Running)
            else {
                return Some(false);
            };
            if job.packets.iter().any(|p| p.path == self.dir) {
                return Some(false);
            }
            job.packets.push(packet);
        }
        self.delegate.begin(request)
    }
    fn poll(&mut self, token: u64) -> Option<String> {
        if token != self.token {
            return None;
        }
        self.delegate.poll(token)
    }
    fn cancel(&mut self, token: u64) {
        if token == self.token {
            self.delegate.cancel(token)
        }
    }
}
/// Only call after all Java helper drains positively succeed. It is intentionally
/// valid after handle destruction, but never while native work is still Running.
pub(crate) fn cleanup_after_drain(handle: i64) -> Result<bool> {
    let mut registry = jobs().lock().map_err(|_| registry_error())?;
    let Some(job) = registry.get_mut(&handle) else {
        return Ok(false);
    };
    if job.state != State::Retained {
        return Err(err(
            "PREVIEW_STILL_RUNNING",
            "Preview is still running or being cleaned",
        ));
    }
    job.state = State::Cleaning;
    // Packets first: if a packet is uncertain, keep input and scratch as well.
    let result = (|| {
        for packet in &job.packets {
            packet.cleanup()?;
        }
        job.root.cleanup()
    })();
    if let Err(error) = result {
        job.state = State::Retained;
        return Err(error);
    }
    registry.remove(&handle);
    Ok(true)
}
fn private_cache(engine: &Engine) -> Result<PathBuf> {
    let invalid = || {
        err(
            "PREVIEW_PATH_POLICY",
            "Preview requires the canonical private app cache",
        )
    };
    let app = engine
        .data_dir
        .parent()
        .and_then(Path::parent)
        .ok_or_else(invalid)?;
    let cache = app.join("cache");
    if app.canonicalize().ok().as_deref() != Some(app) {
        return Err(invalid());
    }
    open_directory(&cache).map_err(|_| invalid())?;
    Ok(cache)
}
fn requests_mut(stages: &mut GpuStageRequests) -> impl Iterator<Item = &mut GpuDiffusionRequest> {
    stages
        .diffusion
        .iter_mut()
        .chain(stages.scanner.iter_mut())
        .chain(stages.optical.iter_mut())
        .chain(stages.exposure.iter_mut())
        .chain(stages.dir.iter_mut())
        .chain(stages.science.values_mut())
}
fn stage_input(path: &Path, input: &[f32], cancel: &mut impl FnMut() -> Result<()>) -> Result<()> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    nofollow(&mut options);
    let mut output = options.open(path).map_err(io)?;
    let mut buffer = Vec::with_capacity(BUFFER_BYTES);
    for values in input.chunks(BUFFER_BYTES / 4) {
        cancel()?;
        buffer.clear();
        for value in values {
            if !value.is_finite() {
                return Err(err(
                    "INVALID_PIXELS",
                    "All preview input samples must be finite",
                ));
            }
            buffer.extend_from_slice(&value.to_le_bytes());
        }
        output.write_all(&buffer).map_err(io)?;
    }
    output.sync_all().map_err(io)?;
    cancel()
}
fn read_output(
    path: &Path,
    samples: usize,
    cancel: &mut impl FnMut() -> Result<()>,
) -> Result<Vec<f32>> {
    let invalid = || {
        err(
            "INVALID_OUTPUT_SHAPE",
            "Preview output shape or identity is invalid",
        )
    };
    let before = fs::symlink_metadata(path).map_err(io)?;
    if !before.is_file() || before.file_type().is_symlink() || before.len() != samples as u64 * 4 {
        return Err(invalid());
    }
    let mut options = OpenOptions::new();
    options.read(true);
    nofollow(&mut options);
    let mut file = options.open(path).map_err(io)?;
    if !identity(&before, &file.metadata().map_err(io)?) {
        return Err(invalid());
    }
    let mut output = Vec::with_capacity(samples);
    let mut buffer = vec![0u8; BUFFER_BYTES];
    let mut left = samples;
    while left > 0 {
        cancel()?;
        let count = left.min(BUFFER_BYTES / 4);
        file.read_exact(&mut buffer[..count * 4]).map_err(io)?;
        for bytes in buffer[..count * 4].chunks_exact(4) {
            let value = f32::from_le_bytes(bytes.try_into().unwrap());
            if !value.is_finite() {
                return Err(err(
                    "INVALID_PIXELS",
                    "Preview output has a non-finite sample",
                ));
            }
            output.push(value);
        }
        left -= count;
    }
    if !identity(&before, &fs::symlink_metadata(path).map_err(io)?)
        || file.metadata().map_err(io)?.len() != before.len()
    {
        return Err(invalid());
    }
    cancel()?;
    Ok(output)
}
pub(crate) struct PreviewOutput {
    pub pixels: Vec<f32>,
    pub metrics: StreamedMetrics,
    pub metered_ev: Option<f32>,
    pub elapsed_ms: f64,
}
#[allow(clippy::too_many_arguments)]
pub(crate) fn render(
    engine: &Engine,
    generation: u64,
    parent_handle: i64,
    input: &[f32],
    width: u32,
    height: u32,
    params: &MobileParams,
    delegates: GpuStageDelegates,
) -> Result<PreviewOutput> {
    let start = Instant::now();
    let bytes = mobile::required_bytes(width as i32, height as i32)?;
    params.validate()?;
    params.check_preview_budget(width as u64 * height as u64)?;
    if input.len() != bytes / 4 {
        return Err(err("INVALID_BUFFER", "Preview input length mismatch"));
    }
    if input.iter().any(|v| !v.is_finite()) {
        return Err(err(
            "INVALID_PIXELS",
            "All preview input samples must be finite",
        ));
    }
    mobile::check_cancellation(engine, generation)?;
    let _interactive = crate::export_execution::interactive_work();
    mobile::clear_preview_cache(engine)?;
    let cache = private_cache(engine)?;
    let mut stages = mobile::gpu_stage_requests(&cache, parent_handle, params, delegates);
    stages.route = ScienceRoute::Preview;
    // Query the same app cache used by all helpers before the first file write.
    let available = requests_mut(&mut stages)
        .filter_map(|r| {
            r.delegate
                .as_deref_mut()
                .and_then(|d| d.available_disk_bytes())
        })
        .min()
        .ok_or_else(|| {
            err(
                "PREVIEW_DISK_UNAVAILABLE",
                "Preview disk admission unavailable",
            )
        })?;
    let mut disk_params = params.clone();
    disk_params.checkpoint_directory = None;
    let required =
        mobile::plan_export(width as i32, height as i32, &disk_params)?.estimated_total_local_bytes;
    if available < required {
        return Err(err(
            "INSUFFICIENT_DISK_SPACE",
            "Preview disk reservation unavailable",
        ));
    }
    let job = JobGuard::create(&cache, parent_handle)?;
    for request in requests_mut(&mut stages) {
        job.track(request);
    }
    let input_path = job.root.join("input.rgb.f32le");
    let output_path = job.root.join("output.rgb.f32le");
    let scratch = job.root.join("scratch");
    let mut cancel = || mobile::check_cancellation(engine, generation);
    stage_input(&input_path, input, &mut cancel)?;
    let film =
        spektrafilm_core::profile::load_profile_by_name(&engine.data_dir, &params.film_stock)
            .map_err(|_| err("PROFILE_ERROR", "Preview film profile unavailable"))?;
    let runtime = params.runtime_for_film(&film);
    let metrics = crate::streamed::render_scientific_preview_file(
        &engine.data_dir,
        &params.film_stock,
        &params.paper_stock,
        runtime,
        &input_path,
        &output_path,
        &scratch,
        width,
        height,
        params.memory_budget_mi_b,
        params.brightness_ev,
        stages,
        || cancel().is_err(),
        |event| mobile::progress_update_export(engine, event),
    )?;
    let pixels = read_output(&output_path, bytes / 4, &mut cancel)?;
    let metered_ev = metrics.metered_ev;
    job.finish()?;
    cancel()?;
    Ok(PreviewOutput {
        pixels,
        metrics,
        metered_ev,
        elapsed_ms: start.elapsed().as_secs_f64() * 1000.,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    static SERIAL: Mutex<()> = Mutex::new(());
    struct Fixture {
        root: PathBuf,
        cache: PathBuf,
        handle: i64,
    }
    impl Fixture {
        fn new() -> Self {
            static ID: AtomicU64 = AtomicU64::new(100000);
            let handle = ID.fetch_add(1, Ordering::Relaxed) as i64;
            let root = std::env::temp_dir()
                .join(format!("film-preview-test-{}-{handle}", std::process::id()));
            fs::create_dir(&root).unwrap();
            let root = root.canonicalize().unwrap();
            let cache = root.join("cache");
            fs::create_dir(&cache).unwrap();
            fs::create_dir(cache.join("gpu-render")).unwrap();
            Self {
                root,
                cache,
                handle,
            }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = cleanup_after_drain(self.handle);
            let _ = fs::remove_dir_all(&self.root);
        }
    }
    #[test]
    fn cleanup_running_rejected_then_terminal_and_idempotent() {
        let _serial = SERIAL.lock().unwrap();
        let f = Fixture::new();
        let job = JobGuard::create(&f.cache, f.handle).unwrap();
        fs::write(job.root.join("input"), b"private input").unwrap();
        assert_eq!(
            cleanup_after_drain(f.handle).unwrap_err().code,
            "PREVIEW_STILL_RUNNING"
        );
        assert!(job.root.exists());
        assert_eq!(
            JobGuard::create(&f.cache, f.handle).err().unwrap().code,
            "PREVIEW_DRAIN_REQUIRED"
        );
        let root = job.root.clone();
        drop(job);
        assert!(root.exists());
        assert!(cleanup_after_drain(f.handle).unwrap());
        assert!(!root.exists());
        assert!(!cleanup_after_drain(f.handle).unwrap());
    }
    #[test]
    fn cleanup_replaced_directory_and_symlink_fail_closed() {
        let _serial = SERIAL.lock().unwrap();
        let f = Fixture::new();
        let job = JobGuard::create(&f.cache, f.handle).unwrap();
        let root = job.root.clone();
        drop(job);
        let moved = f.cache.join("moved");
        fs::rename(&root, &moved).unwrap();
        fs::create_dir(&root).unwrap();
        fs::write(root.join("foreign"), b"untouched").unwrap();
        assert_eq!(
            cleanup_after_drain(f.handle).unwrap_err().code,
            "PREVIEW_CLEANUP_IDENTITY"
        );
        assert_eq!(fs::read(root.join("foreign")).unwrap(), b"untouched");
        assert!(moved.exists());
        fs::remove_dir_all(&root).unwrap();
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&moved, &root).unwrap();
            assert_eq!(
                cleanup_after_drain(f.handle).unwrap_err().code,
                "PREVIEW_CLEANUP_IDENTITY"
            );
            fs::remove_file(&root).unwrap();
        }
        // Missing path with a still-linked registered inode is uncertain, too.
        assert_eq!(
            cleanup_after_drain(f.handle).unwrap_err().code,
            "PREVIEW_CLEANUP_IDENTITY"
        );
        fs::rename(&moved, &root).unwrap();
        assert!(cleanup_after_drain(f.handle).unwrap());
    }
    #[test]
    fn cleanup_parent_replacement_fails_closed() {
        let _serial = SERIAL.lock().unwrap();
        let f = Fixture::new();
        let job = JobGuard::create(&f.cache, f.handle).unwrap();
        let basename = job.root.file_name().unwrap().to_owned();
        drop(job);
        let original = f.root.join("old-cache");
        fs::rename(&f.cache, &original).unwrap();
        fs::create_dir(&f.cache).unwrap();
        fs::create_dir(f.cache.join(&basename)).unwrap();
        assert_eq!(
            cleanup_after_drain(f.handle).unwrap_err().code,
            "PREVIEW_CLEANUP_IDENTITY"
        );
        assert!(original.join(&basename).exists());
        fs::remove_dir_all(&f.cache).unwrap();
        fs::rename(&original, &f.cache).unwrap();
        assert!(cleanup_after_drain(f.handle).unwrap());
    }
    struct Fake {
        panic_begin: bool,
    }
    impl GpuDiffusionDelegate for Fake {
        fn available_disk_bytes(&mut self) -> Option<u64> {
            Some(u64::MAX)
        }
        fn begin(&mut self, _: &str) -> Option<bool> {
            if self.panic_begin {
                panic!("test uncertain dispatch");
            }
            Some(false)
        }
        fn poll(&mut self, _: u64) -> Option<String> {
            None
        }
        fn cancel(&mut self, _: u64) {}
    }
    #[test]
    fn panic_retains_packet_and_independent_input_until_drain() {
        let _serial = SERIAL.lock().unwrap();
        let f = Fixture::new();
        let job = JobGuard::create(&f.cache, f.handle).unwrap();
        let root = job.root.clone();
        let input = root.join("input");
        stage_input(&input, &[0.25, 0.5, 1.], &mut || Ok(())).unwrap();
        let packet = f.cache.join("gpu-render/gpu-stage-91");
        fs::create_dir(&packet).unwrap();
        fs::hard_link(&input, packet.join("source")).unwrap();
        let mut request = GpuDiffusionRequest {
            parent_handle: f.handle,
            token: 91,
            stage_dir: Some(packet.clone()),
            delegate: Some(Box::new(Fake { panic_begin: true })),
        };
        job.track(&mut request);
        let wire =
            serde_json::json!({"token":91,"parentHandle":f.handle,"jobDir":packet}).to_string();
        assert!(
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| request
                .delegate
                .as_mut()
                .unwrap()
                .begin(&wire)))
            .is_err()
        );
        drop(request);
        drop(job);
        assert!(input.exists());
        assert!(packet.join("source").exists());
        assert!(cleanup_after_drain(f.handle).unwrap());
        assert!(!root.exists());
        assert!(!packet.exists());
    }
    #[test]
    fn successful_stage_already_removed_is_safe_and_foreign_packet_not_claimed() {
        let _serial = SERIAL.lock().unwrap();
        let f = Fixture::new();
        let job = JobGuard::create(&f.cache, f.handle).unwrap();
        let packet = f.cache.join("gpu-render/gpu-stage-92");
        fs::create_dir(&packet).unwrap();
        let mut request = GpuDiffusionRequest {
            parent_handle: f.handle,
            token: 92,
            stage_dir: Some(packet.clone()),
            delegate: Some(Box::new(Fake { panic_begin: false })),
        };
        job.track(&mut request);
        let foreign = f.cache.join("gpu-render/foreign");
        fs::create_dir(&foreign).unwrap();
        let wrong =
            serde_json::json!({"token":92,"parentHandle":f.handle,"jobDir":foreign}).to_string();
        assert_eq!(
            request.delegate.as_mut().unwrap().begin(&wrong),
            Some(false)
        );
        let wire =
            serde_json::json!({"token":92,"parentHandle":f.handle,"jobDir":packet}).to_string();
        assert_eq!(request.delegate.as_mut().unwrap().begin(&wire), Some(false));
        fs::remove_dir(&packet).unwrap();
        let root = job.root.clone();
        job.finish().unwrap();
        assert!(!root.exists());
        assert!(foreign.exists());
    }
    #[test]
    fn input_copy_and_output_validation_are_bounded_cancellable_and_exact() {
        let _serial = SERIAL.lock().unwrap();
        let f = Fixture::new();
        let path = f.root.join("input");
        let values: Vec<f32> = (0..40000).map(|n| n as f32 / 40000.).collect();
        let original = values.clone();
        stage_input(&path, &values, &mut || Ok(())).unwrap();
        assert_eq!(values, original);
        assert_eq!(
            read_output(&path, values.len(), &mut || Ok(())).unwrap(),
            values
        );
        assert_eq!(
            stage_input(&path, &values, &mut || Ok(()))
                .unwrap_err()
                .code,
            "IO_ERROR"
        );
        assert_eq!(
            read_output(&path, values.len() - 1, &mut || Ok(()))
                .unwrap_err()
                .code,
            "INVALID_OUTPUT_SHAPE"
        );
        let cancelled = f.root.join("cancelled");
        let mut calls = 0;
        let result = stage_input(&cancelled, &values, &mut || {
            calls += 1;
            if calls >= 2 {
                Err(err("CANCELLED", "Cancelled"))
            } else {
                Ok(())
            }
        });
        assert_eq!(result.unwrap_err().code, "CANCELLED");
        assert_eq!(fs::metadata(&cancelled).unwrap().len(), BUFFER_BYTES as u64);
        fs::write(&path, f32::NAN.to_le_bytes()).unwrap();
        assert_eq!(
            read_output(&path, 1, &mut || Ok(())).unwrap_err().code,
            "INVALID_PIXELS"
        );
        #[cfg(unix)]
        {
            let link = f.root.join("link");
            std::os::unix::fs::symlink(&path, &link).unwrap();
            assert_eq!(
                read_output(&link, 1, &mut || Ok(())).unwrap_err().code,
                "INVALID_OUTPUT_SHAPE"
            );
        }
    }
    #[test]
    fn process_tokens_unique_across_preview_and_export_bundles() {
        let _serial = SERIAL.lock().unwrap();
        let f = Fixture::new();
        let params = MobileParams {
            gpu_diffusion_experimental: true,
            gpu_scanner_experimental: true,
            gpu_optical_experimental: true,
            gpu_exposure_experimental: true,
            gpu_dir_experimental: true,
            gpu_science_experimental: true,
            ..MobileParams::default()
        };
        let mut previous = 0;
        for _ in 0..10 {
            let mut stages = mobile::gpu_stage_requests(
                &f.cache,
                f.handle,
                &params,
                GpuStageDelegates::default(),
            );
            let mut tokens: Vec<_> = requests_mut(&mut stages).map(|r| r.token).collect();
            tokens.sort();
            assert_eq!(tokens.len(), 5 + crate::streamed::ScienceKind::ALL.len());
            for token in tokens {
                assert!(token > previous && token <= i64::MAX as u64);
                previous = token;
            }
        }
    }
    fn link_tree(source: &Path, destination: &Path) {
        fs::create_dir(destination).unwrap();
        for entry in fs::read_dir(source).unwrap() {
            let entry = entry.unwrap();
            let target = destination.join(entry.file_name());
            if entry.file_type().unwrap().is_dir() {
                link_tree(&entry.path(), &target);
            } else {
                fs::copy(entry.path(), target).unwrap();
            }
        }
    }
    #[test]
    #[ignore = "Full scientific preview differential gate; requires bundled data and serial memory admission"]
    fn whole_preview_preserves_mt_grain_geometry_and_color_brightness_order() {
        let _serial = SERIAL.lock().unwrap();
        let f = Fixture::new();
        let files = f.root.join("files");
        fs::create_dir(&files).unwrap();
        let data = files.join("data");
        link_tree(
            &Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data"),
            &data,
        );
        let handle = mobile::create(data.to_str().unwrap()).unwrap();
        let engine = mobile::get(handle).unwrap();
        let generation = engine.cancel_generation.load(Ordering::Acquire);
        for (width, height, space, route) in [
            (17, 13, "Adobe RGB (1998)", "print"),
            (31, 19, "Display P3", "direct"),
            (9, 7, "sRGB", "print"),
        ] {
            let input: Vec<f32> = (0..width * height * 3)
                .map(|n| 0.01 + (n % 211) as f32 / 300.)
                .collect();
            let original = input.clone();
            let params = MobileParams {
                output_color_space: space.into(),
                output_gamut: false,
                use_gpu: false,
                output_route: route.into(),
                brightness_ev: 0.7,
                gpu_science_experimental: true,
                gpu_diffusion_experimental: true,
                ..MobileParams::default()
            };
            let expected =
                mobile::render(&engine, generation, &input, width, height, &params).unwrap();
            let delegates = GpuStageDelegates {
                diffusion: Some(Box::new(Fake { panic_begin: false })),
                ..GpuStageDelegates::default()
            };
            let actual = render(
                &engine, generation, handle, &input, width, height, &params, delegates,
            )
            .unwrap();
            assert_eq!(input, original);
            assert_eq!(actual.pixels.len(), expected.pixels.len());
            assert_eq!(actual.metered_ev, expected.metered_ev);
            let maximum = actual
                .pixels
                .iter()
                .zip(&expected.pixels)
                .map(|(a, b)| (*a as f64 - *b as f64).abs())
                .fold(0., f64::max);
            eprintln!(
                "PREVIEW_ROUTE_DIFFERENTIAL {}",
                serde_json::json!({"width":width,"height":height,"space":space,"route":route,"maxAbs":maximum,"metrics":actual.metrics})
            );
            assert!(maximum < 0.00001, "Original preview differs by {maximum}");
            assert!(!cleanup_after_drain(handle).unwrap());
        }
        mobile::destroy(handle);
    }
}
