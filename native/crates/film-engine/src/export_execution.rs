//! Process-wide, export-only CPU policy. Interactive entry never waits for an
//! export lock. Export yields cooperatively at existing cancellation checkpoints;
//! work already admitted can drain (not an OS preemption/latency guarantee).
use std::sync::{Arc, Condvar, Mutex, OnceLock, atomic::{AtomicU64, Ordering}};
use std::time::{Duration, Instant};
use rayon::{ThreadPool, ThreadPoolBuilder};
use serde::Serialize;
use spektrafilm_math::precision::Scalar;

pub const MAX_EXPORT_WORKERS: usize = 4;
pub const EXPORT_STACK_RESERVE_BYTES: u64 = MAX_EXPORT_WORKERS as u64 * 2 * 1024 * 1024;
const WAIT: Duration = Duration::from_millis(5);
/// Dedicated export workers only. Linux/Android nice is per-thread here; never
/// lower a shared preview/UI thread or require permission to raise it later.
pub(crate) fn lower_export_thread_priority() {
    #[cfg(target_os="android")]
    unsafe {
        unsafe extern "C" {fn setpriority(which:i32,who:u32,priority:i32)->i32;}
        let _=setpriority(0,0,10);
    }
}
fn worker_cap(available: Option<usize>) -> usize {
    available.unwrap_or(1).saturating_sub(2).clamp(1, MAX_EXPORT_WORKERS)
}
#[derive(Default)]
struct State { export: bool, interactive: usize }
struct Coordinator {
    state: Mutex<State>, changed: Condvar, workers: usize,
    pool: OnceLock<Option<ThreadPool>>,
    #[cfg(test)] fail_pool: bool,
    #[cfg(test)] admission_wait_entries: AtomicU64,
    #[cfg(test)] checkpoint_wait_entries: AtomicU64,
}
impl Coordinator {
    fn new(workers: usize) -> Arc<Self> { Arc::new(Self {
        state: Mutex::new(State::default()), changed: Condvar::new(),
        workers: workers.clamp(1, MAX_EXPORT_WORKERS), pool: OnceLock::new(),
        #[cfg(test)] fail_pool: false,
        #[cfg(test)] admission_wait_entries: AtomicU64::new(0),
        #[cfg(test)] checkpoint_wait_entries: AtomicU64::new(0),
    }) }
    fn pool(&self) -> Option<&ThreadPool> {
        self.pool.get_or_init(|| {
            if self.workers < 2 { return None; }
            #[cfg(test)] if self.fail_pool { return None; }
            ThreadPoolBuilder::new().num_threads(self.workers)
                .stack_size(2 * 1024 * 1024)
                .thread_name(|n| format!("film-export-{n}"))
                .start_handler(|_|lower_export_thread_priority())
                .build().ok()
        }).as_ref()
    }
    fn interactive(self: &Arc<Self>) -> InteractiveWork {
        self.state.lock().unwrap_or_else(|e|e.into_inner()).interactive += 1;
        InteractiveWork { coordinator: self.clone() }
    }
    fn acquire(self: &Arc<Self>, mut cancelled: impl FnMut()->bool) -> Option<ExportPermit> {
        let at=Instant::now();
        loop {
            if cancelled() { return None; }
            let mut state=self.state.lock().unwrap_or_else(|e|e.into_inner());
            if !state.export && state.interactive==0 {
                state.export=true;
                return Some(ExportPermit { coordinator:self.clone(), stats:Stats::default(), admission_ns:ns(at.elapsed()) });
            }
            #[cfg(test)] self.admission_wait_entries.fetch_add(1,Ordering::Release);
            let (state,_)=self.changed.wait_timeout(state,WAIT).unwrap_or_else(|e|e.into_inner());
            drop(state);
        }
    }
}
fn global() -> &'static Arc<Coordinator> {
    static INSTANCE:OnceLock<Arc<Coordinator>>=OnceLock::new();
    INSTANCE.get_or_init(||Coordinator::new(worker_cap(std::thread::available_parallelism().ok().map(|v|v.get()))))
}
fn ns(v:Duration)->u64 {v.as_nanos().min(u64::MAX as u128)as u64}
pub(crate) struct InteractiveWork { coordinator:Arc<Coordinator> }
impl Drop for InteractiveWork {
    fn drop(&mut self) {
        let mut state=self.coordinator.state.lock().unwrap_or_else(|e|e.into_inner());
        state.interactive-=1;
        drop(state); self.coordinator.changed.notify_all();
    }
}
pub(crate) fn interactive_work()->InteractiveWork { global().interactive() }
#[derive(Default)]
struct Stats { parallel:AtomicU64, serial:AtomicU64, interactive_serial:AtomicU64, waits:AtomicU64, wait_ns:AtomicU64 }
#[derive(Clone,Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct ExportExecutionMetrics {
    pub worker_cap:usize, pub active_pool_workers:usize, pub pool_status:&'static str,
    pub parallel_particle_chunks:u64, pub serial_particle_chunks:u64,
    pub interactive_serial_chunks:u64, pub interactive_waits:u64,
    pub interactive_wait_ms:f64, pub admission_wait_ms:f64,
    pub policy:&'static str,
}
pub(crate) struct ExportPermit { coordinator:Arc<Coordinator>, stats:Stats, admission_ns:u64 }
impl ExportPermit {
    pub(crate) fn acquire(cancelled:impl FnMut()->bool)->Option<Self> {global().acquire(cancelled)}
    /// Installs the entire export into its private pool, including existing
    /// Rayon filters/interpolation. No global Rayon reconfiguration. If pool
    /// creation is unavailable, retain original behavior with SERIAL sampling.
    pub(crate) fn run<R:Send>(&self, work:impl FnOnce()->R+Send)->R {
        if let Some(pool)=self.coordinator.pool() {pool.install(work)} else {work()}
    }
    /// Cancellation remains live while paused behind interactive work. Locks are
    /// never held across caller callbacks, CPU work, IO, or pool installation.
    pub(crate) fn checkpoint(&self, mut cancelled:impl FnMut()->bool)->bool {
        let mut waited=false; let at=Instant::now();
        let result=loop {
            if cancelled() {break true;}
            let state=self.coordinator.state.lock().unwrap_or_else(|e|e.into_inner());
            if state.interactive==0 {break false;}
            waited=true;
            #[cfg(test)] self.coordinator.checkpoint_wait_entries.fetch_add(1,Ordering::Release);
            let (state,_)=self.coordinator.changed.wait_timeout(state,WAIT).unwrap_or_else(|e|e.into_inner());
            drop(state);
        };
        if waited {self.stats.waits.fetch_add(1,Ordering::Relaxed);self.stats.wait_ns.fetch_add(ns(at.elapsed()),Ordering::Relaxed);}
        result
    }
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn sample(&self,density:&[Scalar],w:u32,h:u32,dmax:f64,npp:f64,uniformity:f64,seed:u64,origin:Option<[u32;2]>)->Vec<Scalar> {
        let interactive=self.coordinator.state.lock().unwrap_or_else(|e|e.into_inner()).interactive>0;
        if !interactive {if let (Some(pool),Some(origin))=(self.coordinator.pool(),origin) {
            self.stats.parallel.fetch_add(1,Ordering::Relaxed);
            return spektrafilm_model::grain::layer_particle_model_export(pool,density,w,dmax,npp,uniformity,seed,origin);
        }}
        self.stats.serial.fetch_add(1,Ordering::Relaxed);
        if interactive {self.stats.interactive_serial.fetch_add(1,Ordering::Relaxed);}
        // This is the ORIGINAL routine, not a parallel call outside a pool.
        spektrafilm_model::grain::layer_particle_model(density,w,h,dmax,npp,uniformity,seed,0.,origin)
    }
    pub(crate) fn metrics(&self)->ExportExecutionMetrics {
        let pool_workers=self.coordinator.pool.get().and_then(|p|p.as_ref()).map_or(0,|p|p.current_num_threads());
        ExportExecutionMetrics {worker_cap:self.coordinator.workers, pool_status:if pool_workers>0 {"private_bounded"}else if self.coordinator.workers<2 {"low_or_unknown_cpu_serial_sampling"}else{"pool_unavailable_serial_sampling"},
            active_pool_workers:self.coordinator.pool.get().and_then(|p|p.as_ref()).map_or(0,|p|p.current_num_threads()),
            parallel_particle_chunks:self.stats.parallel.load(Ordering::Relaxed),serial_particle_chunks:self.stats.serial.load(Ordering::Relaxed),
            interactive_serial_chunks:self.stats.interactive_serial.load(Ordering::Relaxed),interactive_waits:self.stats.waits.load(Ordering::Relaxed),
            interactive_wait_ms:self.stats.wait_ns.load(Ordering::Relaxed)as f64/1e6,admission_wait_ms:self.admission_ns as f64/1e6,
            policy:if pool_workers>0 {"one export; private max4 workers with available-minus2 cap; no CPU affinity guarantee; interactive entry never waits; cooperative checkpoint pause"}else{"one export; original serial particle sampling; existing global Rayon for other stages; interactive entry never waits; cooperative checkpoint pause"}}
    }
}
impl Drop for ExportPermit {
    fn drop(&mut self) {
        self.coordinator.state.lock().unwrap_or_else(|e|e.into_inner()).export=false;
        self.coordinator.changed.notify_all();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{mpsc,atomic::AtomicBool};
    #[test] fn cap_reserves_two_and_never_exceeds_four() {
        for (n,expected) in [(None,1),(Some(1),1),(Some(2),1),(Some(3),1),(Some(4),2),(Some(8),4),(Some(128),4)] {assert_eq!(worker_cap(n),expected);}
    }
    fn values()->Vec<Scalar> {(0..17*13).map(|i|0.01+(i%97)as Scalar/50.).collect()}
    #[test] fn pool_and_serial_routes_are_exact_and_low_core_failure_are_cached() {
        for workers in [1,2,4] {for fail in [false,true] {
            let mut c=Coordinator::new(workers);Arc::get_mut(&mut c).unwrap().fail_pool=fail;
            let permit=c.acquire(||false).unwrap();let d=values();
            for origin in [None,Some([31,209])] {
                let expected=spektrafilm_model::grain::layer_particle_model(&d,17,13,2.4,12.3,0.97,42,0.,origin);
                let got=permit.run(||permit.sample(&d,17,13,2.4,12.3,0.97,42,origin));assert_eq!(expected,got);
            }
            let metrics=permit.metrics();assert_eq!(metrics.parallel_particle_chunks,u64::from(workers>=2&&!fail));
            assert_eq!(metrics.serial_particle_chunks,2-metrics.parallel_particle_chunks);
            assert_eq!(metrics.active_pool_workers,if workers>=2&&!fail {workers}else{0});
            let ptr=c.pool().map(|p|p as *const _);assert_eq!(ptr,c.pool().map(|p|p as *const _));
        }}
    }
    #[test] fn interactive_entry_never_waits_and_suppresses_next_parallel_chunk() {
        let c=Coordinator::new(4);let permit=c.acquire(||false).unwrap();let d=values();
        let expected=permit.run(||permit.sample(&d,17,13,2.4,12.3,0.97,42,Some([0,0])));
        let interactive=c.interactive();
        let actual=permit.run(||permit.sample(&d,17,13,2.4,12.3,0.97,42,Some([0,0])));assert_eq!(expected,actual);
        assert_eq!(permit.metrics().parallel_particle_chunks,1);assert_eq!(permit.metrics().interactive_serial_chunks,1);
        drop(interactive);assert!(!permit.checkpoint(||false));
        permit.run(||permit.sample(&d,17,13,2.4,12.3,0.97,42,Some([0,0])));
        assert_eq!(permit.metrics().parallel_particle_chunks,2);
    }
    #[test] fn checkpoint_wait_is_cancellable_without_blocking_interactive_drop() {
        let c=Coordinator::new(4);let permit=c.acquire(||false).unwrap();let interactive=c.interactive();
        let stop=Arc::new(AtomicBool::new(false));let stop2=stop.clone();let (tx,rx)=mpsc::channel();
        let (entered_tx,entered_rx)=mpsc::channel();
        let t=std::thread::spawn(move||{let mut calls=0;let cancelled=permit.checkpoint(||{calls+=1;if calls==2 {entered_tx.send(()).unwrap();}stop2.load(Ordering::Acquire)});tx.send((cancelled,permit.metrics())).unwrap();});
        entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();stop.store(true,Ordering::Release);
        let (cancelled,metrics)=rx.recv_timeout(Duration::from_secs(2)).unwrap();assert!(cancelled);assert_eq!(metrics.interactive_waits,1);
        t.join().unwrap();drop(interactive);assert!(c.acquire(||false).is_some());
    }
    #[test] fn second_export_waits_without_allocating_pool_and_can_cancel() {
        let c=Coordinator::new(4);let first=c.acquire(||false).unwrap();let c2=c.clone();let stop=Arc::new(AtomicBool::new(false));let s=stop.clone();let(tx,rx)=mpsc::channel();
        let (entered_tx,entered_rx)=mpsc::channel();
        let t=std::thread::spawn(move||{let mut calls=0;tx.send(c2.acquire(||{calls+=1;if calls==2 {entered_tx.send(()).unwrap();}s.load(Ordering::Acquire)}).is_none()).unwrap()});
        entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();assert!(c.pool.get().is_none());
        stop.store(true,Ordering::Release);assert!(rx.recv_timeout(Duration::from_secs(2)).unwrap());t.join().unwrap();
        drop(first);assert!(c.acquire(||false).is_some());
    }
    #[test] fn interactive_and_export_leases_release_on_unwind() {
        let c=Coordinator::new(2);
        let _=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{let _i=c.interactive();panic!("marker unwind");}));
        assert_eq!(c.state.lock().unwrap().interactive,0);
        let _=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{let p=c.acquire(||false).unwrap();p.run(||panic!("worker unwind"));}));
        assert!(c.acquire(||false).is_some());
    }
}

#[cfg(test)]
mod mobile_lifecycle_tests {
    use super::*;
    use crate::mobile;
    use std::{fs, path::{Path,PathBuf}, sync::mpsc};
    struct Temp(PathBuf);
    impl Drop for Temp { fn drop(&mut self) { let _=fs::remove_dir_all(&self.0); } }
    fn link_tree(from:&Path,to:&Path) {
        fs::create_dir_all(to).unwrap();
        for entry in fs::read_dir(from).unwrap() {let entry=entry.unwrap();let dest=to.join(entry.file_name());
            if entry.file_type().unwrap().is_dir() {link_tree(&entry.path(),&dest)} else {fs::copy(entry.path(),dest).unwrap();}
        }
    }
    #[test]
    fn real_mobile_cancel_destroy_while_paused_and_queued_then_exact_checkpoint_resume() {
        let root=std::env::temp_dir().join(format!("film-export-lifecycle-{}",std::process::id()));
        fs::create_dir(&root).unwrap();let _temp=Temp(root.clone());
        let data=root.join("files/engine-data");link_tree(&Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data"),&data);
        fs::create_dir(root.join("cache")).unwrap();let job=root.join("files/export-jobs/00000000-0000-0000-0000-000000000001");fs::create_dir_all(&job).unwrap();
        let input=job.join("input.rgb");let(w,h)=(512,384);
        let pixels:Vec<f32>=(0..w*h*3).map(|i|0.01+(i%997)as f32/800.).collect();fs::write(&input,pixels.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
        let mut p=mobile::MobileParams::default();p.use_gpu=false;p.checkpoint_directory=Some(job.join("checkpoint").to_str().unwrap().into());
        let id=mobile::create(data.to_str().unwrap()).unwrap();let engine=mobile::get(id).unwrap();mobile::progress_begin(&engine,"exportFile");
        let out=job.join("film-result-00000000-0000-0000-0000-000000000002.rgb");let e=engine.clone();let ip=input.clone();let op=out.clone();let pp=p.clone();let(tx,rx)=mpsc::channel();
        let export=std::thread::spawn(move||tx.send(mobile::export(&e,0,ip.to_str().unwrap(),op.to_str().unwrap(),w,h,&pp)).unwrap());
        let start=Instant::now();while !mobile::progress_json(&engine).unwrap().contains("\"stage\":\"grain_sampling\"") {assert!(start.elapsed()<Duration::from_secs(30));assert!(rx.try_recv().is_err(),"export ended before grain");std::thread::sleep(Duration::from_millis(1));}
        let before_wait=global().checkpoint_wait_entries.load(Ordering::Acquire);
        let interactive=interactive_work();let wait_at=Instant::now();
        while global().checkpoint_wait_entries.load(Ordering::Acquire)==before_wait {assert!(wait_at.elapsed()<Duration::from_secs(2));std::thread::sleep(Duration::from_millis(1));}
        mobile::cancel(id);mobile::destroy(id);
        assert_eq!(rx.recv_timeout(Duration::from_secs(2)).unwrap().unwrap_err().code,"CANCELLED");export.join().unwrap();
        assert!(!out.exists());assert!(fs::read_dir(root.join("cache")).unwrap().next().is_none());
        let manifest:serde_json::Value=serde_json::from_slice(&fs::read(job.join("checkpoint/manifest.json")).unwrap()).unwrap();assert_eq!(manifest["stage"],4);
        // A second real mobile export queued behind foreground must also honor destroy,
        // without consuming the permit or mutating the committed checkpoint.
        let id2=mobile::create(data.to_str().unwrap()).unwrap();let e2=mobile::get(id2).unwrap();let ip=input.clone();let op=out.clone();let pp=p.clone();let(tx,rx)=mpsc::channel();
        let before_admission=global().admission_wait_entries.load(Ordering::Acquire);
        let queued=std::thread::spawn(move||tx.send(mobile::export(&e2,0,ip.to_str().unwrap(),op.to_str().unwrap(),w,h,&pp)).unwrap());
        let wait_at=Instant::now();while global().admission_wait_entries.load(Ordering::Acquire)==before_admission {assert!(wait_at.elapsed()<Duration::from_secs(2));std::thread::sleep(Duration::from_millis(1));}mobile::destroy(id2);
        assert_eq!(rx.recv_timeout(Duration::from_secs(2)).unwrap().unwrap_err().code,"CANCELLED");queued.join().unwrap();drop(interactive);
        let id3=mobile::create(data.to_str().unwrap()).unwrap();let e3=mobile::get(id3).unwrap();
        mobile::export(&e3,0,input.to_str().unwrap(),out.to_str().unwrap(),w,h,&p).unwrap();let resumed=fs::read(&out).unwrap();
        // Fresh managed export with no checkpoint establishes identical output, not
        // merely a successful resume. Same source, seed and scientific parameters.
        p.checkpoint_directory=None;let fresh=job.join("film-result-00000000-0000-0000-0000-000000000003.rgb");
        mobile::export(&e3,0,input.to_str().unwrap(),fresh.to_str().unwrap(),w,h,&p).unwrap();assert_eq!(resumed,fs::read(fresh).unwrap());mobile::destroy(id3);
        assert!(fs::read_dir(root.join("cache")).unwrap().next().is_none());
    }
}
