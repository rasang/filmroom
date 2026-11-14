//! One bounded OUTPUT buffer, reusable only within an admitted native work scope.
//! No idle cross-frame retention, no mapped buffers, no reuse after uncertain work.
//! Input MAP_WRITE buffers deliberately keep their existing direct-upload path.
use std::{cell::RefCell,marker::PhantomData,rc::Rc};
const MAX_BYTES:u64=32*1024*1024;
#[derive(Clone,Copy,Debug,Default)]
pub struct ReuseStats {pub hits:u64,pub misses:u64,pub peak_bytes:u64}
struct Pool {backend:usize,slot:Option<(u64,u32,wgpu::Buffer)>,stats:ReuseStats}
thread_local! {static POOL:RefCell<Option<Pool>>=const {RefCell::new(None)};}
/// !Send: a worker must release its own thread-local buffers before its active
/// memory reservation ends, including cancellation/unwinding paths.
pub struct ResidentReuseScope {owns:bool,_thread:PhantomData<Rc<()>>}
impl ResidentReuseScope {
    pub fn stats(&self)->ReuseStats {POOL.with(|p|p.borrow().as_ref().map(|p|p.stats).unwrap_or_default())}
}
impl Drop for ResidentReuseScope {
    fn drop(&mut self){if self.owns {POOL.with(|p|{let retired=p.borrow_mut().take();if let Some(p)=retired {
        tracing::debug!(hits=p.stats.hits,misses=p.stats.misses,peak_bytes=p.stats.peak_bytes,"scoped resident output reuse");
        drop(p); // drop all cached GPU handles before returning to admission owner
    }});}}
}
pub(super) fn begin(backend:usize)->ResidentReuseScope {
    let owns=POOL.with(|p|{let mut p=p.borrow_mut();if p.is_some(){false}else{*p=Some(Pool{backend,slot:None,stats:ReuseStats::default()});true}});
    ResidentReuseScope{owns,_thread:PhantomData}
}
pub(super) fn take(backend:usize,bytes:u64,usage:wgpu::BufferUsages)->Option<wgpu::Buffer> {
    POOL.with(|p|{let mut p=p.borrow_mut();let p=p.as_mut()?;if p.backend!=backend{return None;}
        let previous=p.slot.take(); // stale size/usage never remains next to a new allocation
        if let Some((size,flags,buffer))=previous {
            if bytes<=MAX_BYTES && size==bytes && flags==usage.bits(){p.stats.hits+=1;return Some(buffer);}
        }
        p.stats.misses+=1;None
    })
}
/// Called ONLY after all submissions complete and all mappings have been unmapped.
pub(super) fn put_completed(backend:usize,bytes:u64,usage:wgpu::BufferUsages,buffer:wgpu::Buffer) {
    POOL.with(|p|{let mut p=p.borrow_mut();if let Some(p)=p.as_mut(){if p.backend==backend && bytes<=MAX_BYTES {
        p.stats.peak_bytes=p.stats.peak_bytes.max(bytes);p.slot=Some((bytes,usage.bits(),buffer));
    }}})
}
#[cfg(test)]mod tests {
    use super::*;
    #[test]fn nested_scope_never_retires_outer_and_drop_clears(){
        assert!(POOL.with(|p|p.borrow().is_none()));let outer=begin(1);
        {let inner=begin(1);assert!(!inner.owns);assert!(POOL.with(|p|p.borrow().is_some()));}
        assert!(POOL.with(|p|p.borrow().is_some()));drop(outer);assert!(POOL.with(|p|p.borrow().is_none()));
    }
    #[test]fn unwinding_clears_scope(){let _=std::panic::catch_unwind(||{let _scope=begin(2);panic!("fixture")});assert!(POOL.with(|p|p.borrow().is_none()));}
    #[test]fn another_backend_does_not_consume_a_slot(){let scope=begin(3);assert!(take(4,1024,wgpu::BufferUsages::STORAGE).is_none());assert_eq!(scope.stats().misses,0);}
}
