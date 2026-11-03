//! Preview-only handle, operation and private-directory ownership. Never uses export jobs.
//! Private roots must not be modified concurrently by the caller while operations run.
use super::{
    CancellationToken, DetailConfig, DetailContext, DetailError, DetailIdentity, OwnedDetailTile,
    ResourcePlan,
};
use std::{
    collections::HashMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BridgeError {
    InvalidHandle,
    InvalidOperation,
    InvalidScope,
    Busy,
    Cancelled,
    Unsupported,
    Budget,
    StorageBudget,
    MemoryPlan,
    InvalidRequest,
    Processing,
}
impl BridgeError {
    pub fn code(self) -> &'static str {
        match self {
            Self::InvalidHandle => "INVALID_HANDLE",
            Self::InvalidOperation => "INVALID_OPERATION",
            Self::InvalidScope => "INVALID_SCOPE",
            Self::Busy => "BUSY",
            Self::Cancelled => "CANCELLED",
            Self::Unsupported => "UNSUPPORTED",
            Self::Budget => "BUDGET_EXCEEDED",
            Self::StorageBudget => "STORAGE_BUDGET",
            Self::MemoryPlan => "WORKING_MEMORY_BUDGET",
            Self::InvalidRequest => "INVALID_REQUEST",
            Self::Processing => "PROCESSING_FAILED",
        }
    }
    pub fn message(self) -> &'static str {
        match self {
            Self::InvalidHandle => "Detail session is unavailable",
            Self::InvalidOperation => "Detail operation is unavailable or already used",
            Self::InvalidScope => "Detail paths must remain in the private preview scope",
            Self::Busy => "A detail operation is already running",
            Self::Cancelled => "Detail operation was cancelled",
            Self::Unsupported => "Requested detail mode requires an unsupported global stage",
            Self::Budget => "Detail resource budget is insufficient",
            Self::StorageBudget => "Detail temporary storage budget is insufficient",
            Self::MemoryPlan => "Detail working-memory plan exceeds budget",
            Self::InvalidRequest => "Detail request is invalid",
            Self::Processing => "Detail processing failed",
        }
    }
}
impl std::fmt::Display for BridgeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message())
    }
}
impl std::error::Error for BridgeError {}
impl From<DetailError> for BridgeError {
    fn from(e: DetailError) -> Self {
        match e {
            DetailError::Cancelled => Self::Cancelled,
            DetailError::InvalidGeometry(_) => Self::InvalidRequest,
            DetailError::BudgetExceeded { .. } => Self::Budget,
            DetailError::StorageBudgetExceeded { .. } => Self::StorageBudget,
            DetailError::MemoryPlanExceeded { .. } => Self::MemoryPlan,
            DetailError::Unsupported(_) => Self::Unsupported,
            DetailError::Processing(_) => Self::Processing,
        }
    }
}
pub type Result<T> = std::result::Result<T, BridgeError>;
const MARKER: &[u8] = b"filmroom-owned-detail-session-v1\n";
const MAX_PENDING: usize = 64;
// Positive tagged IDs distinguish detail handles from the legacy engine's
// small monotonic handle namespace. Never wrap or carry out of this tag.
const ID_START: u64 = 0x4445_0000_0000_0000;
const ID_END: u64 = 0x4446_0000_0000_0000;
static NEXT: AtomicU64 = AtomicU64::new(ID_START);
fn next_id() -> Result<u64> {
    NEXT.fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
        if n < ID_END { Some(n + 1) } else { None }
    })
    .map_err(|_| BridgeError::Processing)
}
fn registry() -> &'static Mutex<HashMap<u64, Arc<Session>>> {
    static R: OnceLock<Mutex<HashMap<u64, Arc<Session>>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashMap::new()))
}
struct Operation {
    token: CancellationToken,
    state: AtomicU8,
}
struct Session {
    closed: AtomicBool,
    active: AtomicBool,
    data: PathBuf,
    sources: PathBuf,
    context: Mutex<Option<Arc<ContextChoice>>>,
    operations: Mutex<HashMap<u64, Arc<Operation>>>,
    _directory: SessionDirectory,
}
struct SessionDirectory {
    path: PathBuf,
    _lease: File,
}
impl Drop for SessionDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

/// A result keeps the operation, context and private directory alive through JNI copy.
pub struct OperationLease {
    session: Arc<Session>,
    operation: Arc<Operation>,
    id: u64,
}
impl OperationLease {
    pub fn check_publish(&self) -> Result<()> {
        if self.session.closed.load(Ordering::Acquire) || self.operation.token.cancelled() {
            Err(BridgeError::Cancelled)
        } else {
            Ok(())
        }
    }
}
impl Drop for OperationLease {
    fn drop(&mut self) {
        self.operation.state.store(2, Ordering::Release);
        if let Ok(mut ops) = self.session.operations.lock() {
            ops.remove(&self.id);
        }
        self.session.active.store(false, Ordering::Release);
    }
}
pub enum ContextChoice { Original(Arc<DetailContext>), Scientific(Arc<super::scientific::ScientificContext>) }
impl ContextChoice {
 pub fn identity(&self)->&DetailIdentity{match self{Self::Original(c)=>c.identity(),Self::Scientific(c)=>c.identity()}}
 pub fn resource_plan(&self)->&ResourcePlan{match self{Self::Original(c)=>c.resource_plan(),Self::Scientific(c)=>c.resource_plan()}}
 pub fn backend_info(&self)->super::DetailBackendInfo{match self{Self::Original(c)=>c.backend_info(),Self::Scientific(c)=>c.backend_info()}}
 pub fn metered_ev(&self)->Option<f32>{match self{Self::Original(c)=>c.metered_ev(),Self::Scientific(c)=>c.metered_ev()}}
 pub fn metrics(&self)->Option<&crate::streamed::StreamedMetrics>{match self{Self::Original(_)=>None,Self::Scientific(c)=>c.metrics()}}
 pub fn quality(&self)->&'static str{match self{Self::Original(_)=>"ExactSameWorkingGrid",Self::Scientific(_)=>"ExperimentalSameWorkingGrid"}}
 fn render_cell(&self,x:usize,y:usize,t:&CancellationToken)->std::result::Result<OwnedDetailTile,DetailError>{match self{Self::Original(c)=>c.render_cell(x,y,t),Self::Scientific(c)=>c.render_cell(x,y,t)}}
 fn render_region_into(&self,core:super::Rect,out:&mut[f32],t:&CancellationToken)->std::result::Result<(),DetailError>{match self{Self::Original(c)=>c.render_region_into(core,out,t),Self::Scientific(c)=>c.render_region_into(core,out,t)}}
}
pub struct BuildResult {
    context: Arc<ContextChoice>,
    pub identity: DetailIdentity,
    pub plan: ResourcePlan,
    pub lease: OperationLease,
}
impl BuildResult {
    pub fn context(&self) -> &ContextChoice {
        &self.context
    }
    pub fn check_publish(&self) -> Result<()> {
        self.lease.check_publish()
    }
}
pub struct TileResult {
    pub tile: OwnedDetailTile,
    pub lease: OperationLease,
}
impl TileResult {
    pub fn check_publish(&self) -> Result<()> {
        self.lease.check_publish()
    }
}

fn strict_path(path: &Path, directory: bool) -> Result<PathBuf> {
    if !path.is_absolute()
        || path
            .components()
            .any(|c| matches!(c, Component::ParentDir | Component::CurDir))
    {
        return Err(BridgeError::InvalidScope);
    }
    let canonical = fs::canonicalize(path).map_err(|_| BridgeError::InvalidScope)?;
    if canonical.as_os_str() != path.as_os_str() {
        return Err(BridgeError::InvalidScope);
    }
    let mut current = PathBuf::new();
    for part in path.components() {
        current.push(part);
        let m = fs::symlink_metadata(&current).map_err(|_| BridgeError::InvalidScope)?;
        if m.file_type().is_symlink() {
            return Err(BridgeError::InvalidScope);
        }
    }
    let m = fs::symlink_metadata(path).map_err(|_| BridgeError::InvalidScope)?;
    if (directory && !m.is_dir()) || (!directory && !m.is_file()) {
        return Err(BridgeError::InvalidScope);
    }
    Ok(canonical)
}
fn validate_tree(root: &Path) -> Result<()> {
    let mut todo = vec![root.to_path_buf()];
    let mut count = 0usize;
    while let Some(dir) = todo.pop() {
        for entry in fs::read_dir(dir).map_err(|_| BridgeError::InvalidScope)? {
            let entry = entry.map_err(|_| BridgeError::InvalidScope)?;
            count += 1;
            if count > 10000 {
                return Err(BridgeError::InvalidScope);
            }
            let kind = entry.file_type().map_err(|_| BridgeError::InvalidScope)?;
            if kind.is_symlink() {
                return Err(BridgeError::InvalidScope);
            }
            if kind.is_dir() {
                todo.push(entry.path())
            } else if !kind.is_file() {
                return Err(BridgeError::InvalidScope);
            }
        }
    }
    Ok(())
}
#[cfg(unix)]
fn private_dir(path: &Path) -> Result<()> {
    use std::os::unix::fs::DirBuilderExt;
    let mut b = fs::DirBuilder::new();
    b.mode(0o700)
        .create(path)
        .map_err(|_| BridgeError::InvalidScope)
}
#[cfg(not(unix))]
fn private_dir(_: &Path) -> Result<()> {
    Err(BridgeError::Unsupported)
}
#[cfg(any(target_os = "linux", target_os = "android"))]
fn lease(path: &Path, create: bool) -> Result<File> {
    use std::os::{fd::AsRawFd, unix::fs::OpenOptionsExt};
    unsafe extern "C" {
        fn flock(fd: std::os::raw::c_int, operation: std::os::raw::c_int) -> std::os::raw::c_int;
    }
    let mut options = OpenOptions::new();
    options
        .read(true)
        .write(true)
        .custom_flags(libc::O_NOFOLLOW)
        .mode(0o600);
    if create {
        options.create_new(true);
    }
    let file = options.open(path).map_err(|_| BridgeError::InvalidScope)?;
    if unsafe { flock(file.as_raw_fd(), 2 | 4) } != 0 {
        return Err(BridgeError::Busy);
    }
    Ok(file)
}
#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn lease(_: &Path, _: bool) -> Result<File> {
    Err(BridgeError::Unsupported)
}
fn scopes(root: &Path) -> Result<(PathBuf, PathBuf, PathBuf)> {
    let root = strict_path(root, true)?;
    let data = strict_path(&root.join("files/engine-data"), true)?;
    let preview = strict_path(&root.join("cache/detail-preview"), true)?;
    let sources = strict_path(&preview.join("sources"), true)?;
    Ok((data, preview, sources))
}

/// Only exact-marker directories with an obtainable OS lease are removed.
/// Another live process's lease prevents cleanup even after PID reuse.
pub fn cleanup_orphans(root: &Path) -> Result<usize> {
    let (_, preview, _) = scopes(root)?;
    let parent = preview.join("sessions");
    if !parent.exists() {
        return Ok(0);
    }
    let parent = strict_path(&parent, true)?;
    let mut count = 0;
    for item in fs::read_dir(parent).map_err(|_| BridgeError::InvalidScope)? {
        let item = item.map_err(|_| BridgeError::InvalidScope)?;
        let name = item.file_name();
        if !name.to_string_lossy().starts_with("owned-") {
            continue;
        }
        if !item
            .file_type()
            .map_err(|_| BridgeError::InvalidScope)?
            .is_dir()
        {
            continue;
        }
        let path = item.path();
        if strict_path(&path, true).is_err() {
            continue;
        }
        let marker = path.join("owner.marker");
        if strict_path(&marker, false).is_err() {
            continue;
        }
        let metadata = fs::metadata(&marker).map_err(|_| BridgeError::InvalidScope)?;
        if metadata.len() != MARKER.len() as u64 {
            continue;
        }
        let mut bytes = Vec::new();
        File::open(&marker)
            .and_then(|mut f| f.read_to_end(&mut bytes))
            .map_err(|_| BridgeError::InvalidScope)?;
        if bytes != MARKER {
            continue;
        }
        let Ok(_lease) = lease(&path.join("owner.lock"), false) else {
            continue;
        };
        fs::remove_dir_all(&path).map_err(|_| BridgeError::InvalidScope)?;
        count += 1;
    }
    Ok(count)
}
pub fn create(root: &Path) -> Result<u64> {
    let (data, preview, sources) = scopes(root)?;
    validate_tree(&data)?;
    let parent = preview.join("sessions");
    if !parent.exists() {
        match private_dir(&parent) {
            Ok(()) => (),
            Err(e) => {
                if !parent.exists() {
                    return Err(e);
                }
            }
        }
    }
    strict_path(&parent, true)?;
    let id = next_id()?;
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| BridgeError::Processing)?
        .as_nanos();
    let path = parent.join(format!("owned-{}-{now}-{id}", std::process::id()));
    private_dir(&path)?;
    // Install lease before the recognizable marker so an incomplete creator is
    // never mistaken for an abandoned context.
    let lock = match lease(&path.join("owner.lock"), true) {
        Ok(v) => v,
        Err(e) => {
            let _ = fs::remove_dir_all(&path);
            return Err(e);
        }
    };
    let directory = SessionDirectory { path, _lease: lock };
    let mut marker = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(directory.path.join("owner.marker"))
        .map_err(|_| BridgeError::InvalidScope)?;
    marker
        .write_all(MARKER)
        .map_err(|_| BridgeError::InvalidScope)?;
    drop(marker);
    let session = Arc::new(Session {
        closed: AtomicBool::new(false),
        active: AtomicBool::new(false),
        data,
        sources,
        context: Mutex::new(None),
        operations: Mutex::new(HashMap::new()),
        _directory: directory,
    });
    registry()
        .lock()
        .map_err(|_| BridgeError::Processing)?
        .insert(id, session);
    Ok(id)
}
fn get(id: u64) -> Result<Arc<Session>> {
    if id == 0 {
        return Err(BridgeError::InvalidHandle);
    }
    let s = registry()
        .lock()
        .map_err(|_| BridgeError::Processing)?
        .get(&id)
        .cloned()
        .ok_or(BridgeError::InvalidHandle)?;
    if s.closed.load(Ordering::Acquire) {
        Err(BridgeError::InvalidHandle)
    } else {
        Ok(s)
    }
}
pub fn begin_operation(session: u64) -> Result<u64> {
    let s = get(session)?;
    let mut ops = s.operations.lock().map_err(|_| BridgeError::Processing)?;
    if s.closed.load(Ordering::Acquire) {
        return Err(BridgeError::InvalidHandle);
    }
    if ops.len() >= MAX_PENDING {
        return Err(BridgeError::Busy);
    }
    let id = next_id()?;
    ops.insert(
        id,
        Arc::new(Operation {
            token: CancellationToken::new(),
            state: AtomicU8::new(0),
        }),
    );
    Ok(id)
}
pub fn cancel(session: u64, operation: u64) -> Result<()> {
    let s = get(session)?;
    let ops = s.operations.lock().map_err(|_| BridgeError::Processing)?;
    let op = ops.get(&operation).ok_or(BridgeError::InvalidOperation)?;
    op.token.cancel();
    Ok(())
}
/// Abandon a never-executed operation; running operations retain their own lease.
pub fn release_operation(session: u64, operation: u64) -> Result<()> {
    let s = get(session)?;
    let mut ops = s.operations.lock().map_err(|_| BridgeError::Processing)?;
    let op = ops
        .remove(&operation)
        .ok_or(BridgeError::InvalidOperation)?;
    op.token.cancel();
    Ok(())
}
pub fn destroy(session: u64) -> Result<()> {
    let s = registry()
        .lock()
        .map_err(|_| BridgeError::Processing)?
        .remove(&session)
        .ok_or(BridgeError::InvalidHandle)?;
    s.closed.store(true, Ordering::Release);
    let ops = s.operations.lock().map_err(|_| BridgeError::Processing)?;
    for op in ops.values() {
        op.token.cancel()
    }
    Ok(())
}
fn acquire(session: u64, operation: u64) -> Result<OperationLease> {
    let s = get(session)?;
    let op = s
        .operations
        .lock()
        .map_err(|_| BridgeError::Processing)?
        .get(&operation)
        .cloned()
        .ok_or(BridgeError::InvalidOperation)?;
    if op
        .state
        .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return Err(BridgeError::InvalidOperation);
    }
    if s.active
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        op.state.store(2, Ordering::Release);
        s.operations
            .lock()
            .map_err(|_| BridgeError::Processing)?
            .remove(&operation);
        return Err(BridgeError::Busy);
    }
    let lease = OperationLease {
        session: s,
        operation: op,
        id: operation,
    };
    lease.check_publish()?;
    Ok(lease)
}
pub fn cleanup_gpu_after_drain(session:u64)->Result<bool>{let s=get(session)?;if s.active.compare_exchange(false,true,Ordering::AcqRel,Ordering::Acquire).is_err(){return Err(BridgeError::Busy);}struct Reset<'a>(&'a AtomicBool);impl Drop for Reset<'_>{fn drop(&mut self){self.0.store(false,Ordering::Release);}}let _reset=Reset(&s.active);crate::gpu_retained::cleanup_after_drain(((1u64<<62)|session)as i64).map_err(|_|BridgeError::Processing)}
pub fn plan(config: &DetailConfig) -> Result<ResourcePlan> {
    if super::scientific::requested(&config.params){super::scientific::plan(config).map_err(Into::into)}else{DetailContext::preflight(config).map_err(Into::into)}
}
pub fn build(
    session: u64,
    operation: u64,
    config: DetailConfig,
    source: &Path,
) -> Result<BuildResult> {
    build_impl(session,operation,config,source,None)
}
pub fn build_with_gpu(session:u64,operation:u64,config:DetailConfig,source:&Path,delegates:crate::streamed::GpuStageDelegates)->Result<BuildResult>{
    build_impl(session,operation,config,source,Some(delegates))
}
fn build_impl(session:u64,operation:u64,config:DetailConfig,source:&Path,delegates:Option<crate::streamed::GpuStageDelegates>)->Result<BuildResult>{
    let lease = acquire(session, operation)?;
    plan(&config)?;
    if lease
        .session
        .context
        .lock()
        .map_err(|_| BridgeError::Processing)?
        .is_some()
    {
        return Err(BridgeError::InvalidRequest);
    }
    if source.is_absolute()
        || source
            .components()
            .any(|c| !matches!(c, Component::Normal(_)))
    {
        return Err(BridgeError::InvalidScope);
    }
    let relative = source
        .strip_prefix("sources")
        .map_err(|_| BridgeError::InvalidScope)?;
    if relative.as_os_str().is_empty() {
        return Err(BridgeError::InvalidScope);
    }
    let normalized: PathBuf = source.components().collect();
    if normalized.as_os_str() != source.as_os_str() {
        return Err(BridgeError::InvalidScope);
    }
    let source = strict_path(&lease.session.sources.join(relative), false)?;
    if !source.starts_with(&lease.session.sources) || source == lease.session.sources {
        return Err(BridgeError::InvalidScope);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if fs::metadata(&source)
            .map_err(|_| BridgeError::InvalidScope)?
            .nlink()
            != 1
        {
            return Err(BridgeError::InvalidScope);
        }
    }
    let context=if super::scientific::requested(&config.params){
        let delegates=delegates.ok_or(BridgeError::InvalidRequest)?;
        Arc::new(ContextChoice::Scientific(super::scientific::ScientificContext::build(config,&lease.session.data,&source,&lease.session._directory.path,((1u64<<62)|session) as i64,delegates,&lease.operation.token).map_err(BridgeError::from)?))
    }else{
        Arc::new(ContextChoice::Original(DetailContext::build(config,&lease.session.data,&source,&lease.session._directory.path,&lease.operation.token).map_err(BridgeError::from)?))
    };
    lease.check_publish()?;
    let identity = context.identity().clone();
    let plan = context.resource_plan().clone();
    *lease
        .session
        .context
        .lock()
        .map_err(|_| BridgeError::Processing)? = Some(context.clone());
    lease.check_publish()?;
    Ok(BuildResult {
        context,
        identity,
        plan,
        lease,
    })
}
pub fn tile(session: u64, operation: u64, x: usize, y: usize) -> Result<TileResult> {
    let lease = acquire(session, operation)?;
    let context = lease
        .session
        .context
        .lock()
        .map_err(|_| BridgeError::Processing)?
        .clone()
        .ok_or(BridgeError::InvalidRequest)?;
    let tile = context
        .render_cell(x, y, &lease.operation.token)
        .map_err(BridgeError::from)?;
    lease.check_publish()?;
    Ok(TileResult { tile, lease })
}
pub struct RegionResult {
    pub identity:DetailIdentity,
    pub backend:super::DetailBackendInfo,
    pub core_rect:super::Rect,
    pub sample_rect:super::Rect,
    pub lease:OperationLease,
}
impl RegionResult {pub fn check_publish(&self)->Result<()> {self.lease.check_publish()}}
pub fn region_into(session:u64,operation:u64,core:super::Rect,output:&mut[f32])->Result<RegionResult> {
    let lease=acquire(session,operation)?;
    let context=lease.session.context.lock().map_err(|_|BridgeError::Processing)?.clone().ok_or(BridgeError::InvalidRequest)?;
    context.render_region_into(core,output,&lease.operation.token).map_err(BridgeError::from)?;
    lease.check_publish()?;
    let sample=core.expand(1,context.identity().width as usize,context.identity().height as usize);
    Ok(RegionResult{identity:context.identity().clone(),backend:context.backend_info(),core_rect:core,sample_rect:sample,lease})
}
pub fn region_layout(session:u64, core:super::Rect) -> Result<(super::Rect,super::Rect)> {
    let s=get(session)?;
    let context=s.context.lock().map_err(|_|BridgeError::Processing)?;
    let context=context.as_ref().ok_or(BridgeError::InvalidRequest)?;
    if !context.resource_plan().region_output {return Err(BridgeError::Unsupported);}
    let (w,h)=(context.identity().width as usize,context.identity().height as usize);
    if !core.valid(w,h) { return Err(BridgeError::InvalidRequest); }
    Ok((core,core.expand(1,w,h)))
}
/// Cheap tile shape query; does not begin/allocate an operation or output plane.
pub fn tile_layout(session: u64, x: usize, y: usize) -> Result<(super::Rect, super::Rect)> {
    let s = get(session)?;
    let context = s.context.lock().map_err(|_| BridgeError::Processing)?;
    let context = context.as_ref().ok_or(BridgeError::InvalidRequest)?;
    let (w, h) = (
        context.identity().width as usize,
        context.identity().height as usize,
    );
    let x = x.checked_mul(256).ok_or(BridgeError::InvalidRequest)?;
    let y = y.checked_mul(256).ok_or(BridgeError::InvalidRequest)?;
    if x >= w || y >= h {
        return Err(BridgeError::InvalidRequest);
    }
    let core = super::Rect {
        x,
        y,
        w: 256.min(w - x),
        h: 256.min(h - y),
    };
    Ok((core, core.expand(1, w, h)))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn root() -> PathBuf {
        let id = next_id().unwrap();
        let p =
            std::env::temp_dir().join(format!("detail-bridge-test-{}-{id}", std::process::id()));
        fs::create_dir(&p).unwrap();
        fs::create_dir_all(p.join("files/engine-data")).unwrap();
        fs::create_dir_all(p.join("cache/detail-preview/sources")).unwrap();
        p
    }
    fn config() -> DetailConfig {
        let mut p = crate::mobile::MobileParams::default();
        p.use_gpu = false;
        DetailConfig {
            region_output: true,
            width: 16,
            height: 12,
            params: p,
            source_revision: "s1".into(),
            mapping_revision: "m1".into(),
            data_revision: "d1".into(),
            memory_budget_bytes: 512 * 1024 * 1024,
            disk_budget_bytes: 1024 * 1024 * 1024,
            stage_cache_bytes: 2 * 1024 * 1024,
            spectrum_cache_bytes: 64 * 1024 * 1024,
        }
    }
    #[test]
    fn precancel_is_preserved_and_operation_is_single_use() {
        let root = root();
        let session = create(&root).unwrap();
        let op = begin_operation(session).unwrap();
        cancel(session, op).unwrap();
        assert!(matches!(
            build(session, op, config(), Path::new("sources/absent.f32le")),
            Err(BridgeError::Cancelled)
        ));
        assert!(matches!(
            acquire(session, op),
            Err(BridgeError::InvalidOperation)
        ));
        destroy(session).unwrap();
        assert_eq!(
            fs::read_dir(root.join("cache/detail-preview/sessions"))
                .unwrap()
                .count(),
            0
        );
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn destroy_cancels_worker_but_keeps_files_until_worker_exits() {
        let root = root();
        let session = create(&root).unwrap();
        let op = begin_operation(session).unwrap();
        let lease = acquire(session, op).unwrap();
        let dir = lease.session._directory.path.clone();
        let weak = Arc::downgrade(&lease.session);
        let barrier = Arc::new(std::sync::Barrier::new(2));
        let worker_barrier = barrier.clone();
        let worker = std::thread::spawn(move || {
            worker_barrier.wait();
            assert_eq!(lease.check_publish(), Err(BridgeError::Cancelled));
            drop(lease)
        });
        destroy(session).unwrap();
        assert!(dir.exists());
        assert!(weak.upgrade().is_some());
        assert_eq!(cleanup_orphans(&root).unwrap(), 0);
        barrier.wait();
        worker.join().unwrap();
        assert!(!dir.exists());
        assert!(weak.upgrade().is_none());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn pending_operations_are_bounded_busy_and_abandoned_cleanly() {
        let root = root();
        let session = create(&root).unwrap();
        let ids: Vec<_> = (0..MAX_PENDING)
            .map(|_| begin_operation(session).unwrap())
            .collect();
        assert_eq!(begin_operation(session), Err(BridgeError::Busy));
        let lease = acquire(session, ids[0]).unwrap();
        assert!(matches!(acquire(session, ids[1]), Err(BridgeError::Busy)));
        release_operation(session, ids[0]).unwrap();
        assert_eq!(lease.check_publish(), Err(BridgeError::Cancelled));
        drop(lease);
        assert!(matches!(
            acquire(session, ids[1]),
            Err(BridgeError::InvalidOperation)
        ));
        let lease = acquire(session, ids[2]).unwrap();
        drop(lease);
        for id in ids.iter().skip(3) {
            release_operation(session, *id).unwrap()
        }
        assert!(begin_operation(session).is_ok());
        destroy(session).unwrap();
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn confinement_rejects_absolute_traversal_sibling_and_symlink() {
        let root = root();
        let session = create(&root).unwrap();
        for bad in [
            "/etc/passwd",
            "../input.f32le",
            "sources/../input.f32le",
            "sources/./input.f32le",
            "sources//input.f32le",
            "sources-other/input.f32le",
        ] {
            let op = begin_operation(session).unwrap();
            assert!(
                matches!(
                    build(session, op, config(), Path::new(bad)),
                    Err(BridgeError::InvalidScope)
                ),
                "{bad}"
            );
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::symlink;
            let outside = root.join("outside");
            fs::write(&outside, [0u8; 16]).unwrap();
            symlink(
                &outside,
                root.join("cache/detail-preview/sources/link.f32le"),
            )
            .unwrap();
            let op = begin_operation(session).unwrap();
            assert!(matches!(
                build(session, op, config(), Path::new("sources/link.f32le")),
                Err(BridgeError::InvalidScope)
            ));
            fs::hard_link(
                &outside,
                root.join("cache/detail-preview/sources/hard.f32le"),
            )
            .unwrap();
            let op = begin_operation(session).unwrap();
            assert!(matches!(
                build(session, op, config(), Path::new("sources/hard.f32le")),
                Err(BridgeError::InvalidScope)
            ));
            symlink(&outside, root.join("files/engine-data/profile-link")).unwrap();
            assert!(matches!(create(&root), Err(BridgeError::InvalidScope)));
        }
        destroy(session).unwrap();
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn orphan_cleanup_requires_exact_marker_and_exclusive_lease() {
        let root = root();
        let session = create(&root).unwrap();
        let parent = root.join("cache/detail-preview/sessions");
        let orphan = parent.join("owned-orphan");
        fs::create_dir(&orphan).unwrap();
        fs::write(orphan.join("owner.marker"), MARKER).unwrap();
        let lock = lease(&orphan.join("owner.lock"), true).unwrap();
        let unrelated = parent.join("owned-unrelated");
        fs::create_dir(&unrelated).unwrap();
        fs::write(unrelated.join("owner.marker"), b"not-ours").unwrap();
        assert_eq!(cleanup_orphans(&root).unwrap(), 0);
        drop(lock);
        assert_eq!(cleanup_orphans(&root).unwrap(), 1);
        assert!(!orphan.exists());
        assert!(unrelated.exists());
        assert!(get(session).is_ok());
        destroy(session).unwrap();
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn wrong_session_cannot_cancel_another_operation() {
        let root = root();
        let a = create(&root).unwrap();
        let b = create(&root).unwrap();
        let op = begin_operation(a).unwrap();
        assert_eq!(cancel(b, op), Err(BridgeError::InvalidOperation));
        let lease = acquire(a, op).unwrap();
        assert_eq!(lease.check_publish(), Ok(()));
        drop(lease);
        destroy(a).unwrap();
        destroy(b).unwrap();
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn tagged_ids_cannot_accidentally_target_legacy_engine_handles() {
        let root = root();
        let data = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let old = crate::mobile::create(data.to_str().unwrap()).unwrap();
        let old_owner = crate::mobile::get(old).unwrap();
        let session = create(&root).unwrap();
        let operation = begin_operation(session).unwrap();
        assert!((ID_START..ID_END).contains(&session));
        assert!((ID_START..ID_END).contains(&operation));
        assert_ne!(session, old as u64);
        crate::mobile::cancel(session as i64);
        crate::mobile::destroy(session as i64);
        crate::mobile::cancel(operation as i64);
        crate::mobile::destroy(operation as i64);
        assert!(crate::mobile::get(old).is_ok());
        crate::mobile::check_cancellation(&old_owner, 0).unwrap();
        assert_eq!(destroy(old as u64), Err(BridgeError::InvalidHandle));
        assert_eq!(
            cancel(old as u64, operation),
            Err(BridgeError::InvalidHandle)
        );
        let lease = acquire(session, operation).unwrap();
        assert_eq!(lease.check_publish(), Ok(()));
        drop(lease);
        destroy(session).unwrap();
        crate::mobile::destroy(old);
        fs::remove_dir_all(root).unwrap();
    }
}
