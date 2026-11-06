//! Bounded logical buffer-descriptor ownership on one audited worker.
//! No GPU handles are stored here. Pipeline/driver/host allocations are excluded.
use std::{
    cell::RefCell, rc::Rc, sync::{
        Arc, atomic::{
            AtomicBool, Ordering
        }
    }
};

const MAX_ALLOCATIONS: usize = 2048;
const MAX_OWNERS: usize = 4096;
const MAX_EDGES: usize = 32768;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    Handle,
    BindGroup,
    Encoder,
    Command,
    Submission,
    QueueWrite,
}

#[derive(Clone, Copy)]
struct Allocation {
    id: u64,
    device: u64,
    bytes: u64,
    refs: usize
}

#[derive(Clone, Copy)]
struct Object {
    id: u64,
    device: u64,
    kind: Kind
}

#[derive(Clone, Copy)]
struct Edge {
    owner: u64,
    allocation: u64
}
struct Graph {
    allocations: Vec<Option<Allocation>>,
    owners: Vec<Option<Object>>,
    edges: Vec<Edge>,
    live: u64,
    peak: u64,
    valid: bool,
    uncertain: u64,
    submissions: u64,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct ResidentOwnerReport {
    pub peak_descriptor_bytes: Option<u64>,
    pub live_descriptor_bytes: Option<u64>,
    pub valid: bool,
    pub quiescent: bool,
    pub uncertain_submissions: u64,
    pub completed_submissions: u64,
    pub active_allocations: usize,
    pub active_owners: usize,
    pub metadata_capacity_bytes: usize,
}

impl Graph {
    fn new() -> Self {
        Self {
            allocations: Vec::new(), owners: Vec::new(), edges: Vec::new(), live: 0, peak: 0, valid: true, uncertain: 0, submissions: 0
        }
    }
    fn object(&self, id: u64) -> Option<Object> {
        self.owners.iter().flatten().find(|o| o.id == id).copied()
    }
    fn owner(&mut self, device: u64, kind: Kind) -> Option<u64> {
        let Some(id) = super::observed_owners::identity() else {
            self.valid = false;
            return None;
        };
        let obj = Some(Object {
            id, device, kind
        });
        if let Some(slot) = self.owners.iter_mut().find(|o| o.is_none()) {
            *slot = obj;
        } else if self.owners.len() < MAX_OWNERS {
            self.owners.push(obj);
        } else {
            self.valid = false;
            return None;
        }
        Some(id)
    }
    fn edge(&mut self, owner: u64, allocation: u64) {
        if self.edges.iter().any(|e| e.owner == owner && e.allocation == allocation) {
            return;
        }
        let Some(o) = self.object(owner) else {
            self.valid = false;
            return;
        };
        let Some(a) = self.allocations.iter_mut().flatten().find(|a| a.id == allocation) else {
            self.valid = false;
            return;
        };
        if a.device != o.device || self.edges.len() == MAX_EDGES {
            self.valid = false;
            return;
        }
        a.refs += 1;
        self.edges.push(Edge {
            owner, allocation
        });
    }
    fn allocate(&mut self, owner: u64, bytes: u64) {
        let Some(o) = self.object(owner) else {
            self.valid = false;
            return;
        };
        let Some(id) = super::observed_owners::identity() else {
            self.valid = false;
            return;
        };
        let Some(live) = self.live.checked_add(bytes) else {
            self.valid = false;
            return;
        };
        let a = Some(Allocation {
            id, device: o.device, bytes, refs: 0
        });
        if let Some(slot) = self.allocations.iter_mut().find(|a| a.is_none()) {
            *slot = a;
        } else if self.allocations.len() < MAX_ALLOCATIONS {
            self.allocations.push(a);
        } else {
            self.valid = false;
            return;
        }
        self.live = live;
        self.peak = self.peak.max(live);
        self.edge(owner, id);
    }
    fn retain(&mut self, to: u64, from: u64) {
        if self.object(to).is_none() || self.object(from).is_none() {
            self.valid = false;
            return;
        }
        let n = self.edges.len();
        for i in 0..n {
            let e = self.edges[i];
            if e.owner == from {
                self.edge(to, e.allocation);
            }
        }
    }
    fn release(&mut self, owner: u64) {
        let Some(slot) = self.owners.iter().position(|o| o.is_some_and(|o| o.id == owner)) else {
            self.valid = false;
            return;
        };
        self.owners[slot] = None;
        let mut i = 0;
        while i < self.edges.len() {
            if self.edges[i].owner != owner {
                i += 1;
                continue;
            }
            let e = self.edges.swap_remove(i);
            if let Some(slot) = self.allocations.iter().position(|a| a.is_some_and(|a| a.id == e.allocation)) {
                let a = self.allocations[slot].as_mut().unwrap();
                a.refs -= 1;
                if a.refs == 0 {
                    self.live -= a.bytes;
                    self.allocations[slot] = None;
                }
            } else {
                self.valid = false;
            }
        }
    }
    fn report(&self) -> ResidentOwnerReport {
        let active_owners = self.owners.iter().flatten().count();
        let quiescent = active_owners == 0;
        ResidentOwnerReport {
            // Only a fully retired observation interval yields a completed peak.
            peak_descriptor_bytes: (self.valid && quiescent && self.uncertain == 0).then_some(self.peak),
            live_descriptor_bytes: (self.valid && self.uncertain == 0).then_some(self.live),
            valid: self.valid, quiescent,
            uncertain_submissions: self.uncertain,
            completed_submissions: self.submissions, active_owners,
            active_allocations: self.allocations.iter().flatten().count(),
            metadata_capacity_bytes: std::mem::size_of::<Graph>()
            + self.allocations.capacity() * std::mem::size_of::<Option<Allocation>>()
            + self.owners.capacity() * std::mem::size_of::<Option<Object>>()
            + self.edges.capacity() * std::mem::size_of::<Edge>(),
        }
    }
}
thread_local! {
    static ACTIVE: RefCell<Option<Rc<RefCell<Graph>>>> = const {
        RefCell::new(None)
    };
    static DEVICE: RefCell<Option<u64>> = const {
        RefCell::new(None)
    };
    static PENDING: RefCell<Option<Arc<AtomicBool>>> = const {
        RefCell::new(None)
    };
}
pub(crate) fn begin(identity_available: bool) {
    let mut graph = Graph::new();
    graph.valid = identity_available;
    ACTIVE.with(|a| *a.borrow_mut() = Some(Rc::new(RefCell::new(graph))));
}
pub(crate) fn end() {
    let _ = ACTIVE.try_with(|a| a.borrow_mut().take());
}
pub(crate) fn report() -> ResidentOwnerReport {
    ACTIVE.with(|a| a.borrow().as_ref().map(|g| g.borrow().report()).unwrap_or_default())
}
pub(crate) struct DeviceScope {
    previous: Option<u64>, graph: Option<Rc<RefCell<Graph>>>, pending: Option<Arc<AtomicBool>>
}

impl DeviceScope {
    pub(crate) fn begin(device: Option<u64>) -> Self {
        Self {
            previous: DEVICE.with(|d| d.replace(device)), graph: ACTIVE.with(|g| g.borrow().clone()), pending: PENDING.with(|p| p.replace(None))
        }
    }
    /// Enter only after acquiring the backend's existing execution mutex.
    pub(crate) fn begin_backend(device: Option<u64>, pending: &Arc<AtomicBool>) -> Self {
        let scope = Self::begin(device);
        if pending.load(Ordering::Acquire) {
            if let Some(g) = &scope.graph {
                g.borrow_mut().valid = false;
            }
        }
        PENDING.with(|p| *p.borrow_mut() = Some(Arc::clone(pending)));
        scope
    }
}

impl Drop for DeviceScope {
    fn drop(&mut self) {
        if std::thread::panicking() {
            if let Some(g) = &self.graph {
                g.borrow_mut().valid = false;
            }
        }
        let _ = DEVICE.try_with(|d| d.replace(self.previous));
        let _ = PENDING.try_with(|p| p.replace(self.pending.take()));
    }
}
/// Unique logical object. Not Clone and not Send; it never holds a GPU handle.
pub(crate) struct Owner {
    graph: Option<Rc<RefCell<Graph>>>, id: Option<u64>
}

impl Owner {
    pub(crate) fn new(kind: Kind) -> Self {
        let graph = ACTIVE.with(|a| a.borrow().clone());
        let device = DEVICE.with(|d| *d.borrow());
        let id = graph.as_ref().and_then(|g| {
            let mut g = g.borrow_mut();
            let id = device.and_then(|d| g.owner(d, kind));
            if id.is_none() {
                g.valid = false;
            } id
        });
        Self {
            graph, id
        }
    }
    pub(crate) fn buffer(bytes: u64) -> Self {
        let o = Self::new(Kind::Handle);
        if let (Some(g), Some(id)) = (&o.graph, o.id) {
            g.borrow_mut().allocate(id, bytes);
        } o
    }
    pub(crate) fn retain(&self, from: &Self) {
        if let Some(g) = &self.graph {
            if !from.graph.as_ref().is_some_and(|f| Rc::ptr_eq(g, f)) {
                g.borrow_mut().valid = false;
                return;
            }
            let mut g = g.borrow_mut();
            if let (Some(to), Some(from)) = (self.id, from.id) {
                g.retain(to, from);
            } else {
                g.valid = false;
            }
        }
    }
    pub(crate) fn transfer(self, kind: Kind) -> Self {
        ACTIVE.with(|a| {
            if let Some(active) = a.borrow().as_ref() {
                if !self.graph.as_ref().is_some_and(|g| Rc::ptr_eq(g, active)) {
                    active.borrow_mut().valid = false;
                }
            }
        });
        // New owner is created in the original graph, never in a later audit.
        let id = self.graph.as_ref().and_then(|g| {
            let mut g = g.borrow_mut();
            let source = self.id.and_then(|id| g.object(id));
            if !source.is_some_and(|o| matches!((o.kind, kind),
                (Kind::Encoder, Kind::Command) | (Kind::Command, Kind::Submission))) {
                g.valid = false;
            }
            let device = source.map(|o| o.device);
            let id = device.and_then(|d| g.owner(d, kind)); if id.is_none() {
                g.valid = false;
            } id
        });
        let next = Self {
            graph: self.graph.clone(), id
        };
        next.retain(&self);
        next
    }
    pub(crate) fn absorb_queue_writes(&self) {
        if let (Some(g), Some(id)) = (&self.graph, self.id) {
            let mut g = g.borrow_mut();
            let Some(o) = g.object(id) else {
                g.valid = false;
                return;
            };
            for slot in 0..g.owners.len() {
                if let Some(q) = g.owners[slot].filter(|q| q.kind == Kind::QueueWrite && q.device == o.device) {
                    g.retain(id, q.id);
                    g.release(q.id);
                }
            }
        }
    }
    pub(crate) fn complete(&mut self) {
        if let (Some(g), Some(id)) = (&self.graph, self.id.take()) {
            let mut g = g.borrow_mut();
            if !g.object(id).is_some_and(|o| o.kind == Kind::Submission) {
                g.valid = false;
            }
            g.submissions = g.submissions.saturating_add(1);
            g.release(id);
        }
    }
}

impl Drop for Owner {
    fn drop(&mut self) {
        if let (Some(g), Some(id)) = (&self.graph, self.id) {
            let mut g = g.borrow_mut();
            if std::thread::panicking() {
                g.valid = false;
            }
            if g.object(id).is_some_and(|o| o.kind == Kind::Submission) {
                // Retain uncertain edges, even after actual API handles disappear.
                g.valid = false;
                g.uncertain = g.uncertain.saturating_add(1);
            } else {
                g.release(id);
            }
        }
    }
}
pub(crate) fn mark_pending_write() {
    PENDING.with(|p| {
        if let Some(p) = p.borrow().as_ref() {
            p.store(true, Ordering::Release);
        }
    });
}
pub(crate) fn queue_write(buffer: &Owner) {
    mark_pending_write();
    let mut pending = Owner::new(Kind::QueueWrite);
    pending.retain(buffer);
    // The graph retains this owner until the next same-device submission.
    pending.id = None;
}

#[cfg(test)]
mod tests {
    use super::*;
    fn audit() -> (super::super::ThreadResourceAudit, DeviceScope) {
        (super::super::ThreadResourceAudit::begin().unwrap(), DeviceScope::begin(Some(7)))
    }
    #[test]
    fn independent_same_role_owners_and_aliases() {
        let (audit, _device) = audit();
        let buffer = Owner::buffer(40);
        let a = Owner::new(Kind::BindGroup);
        let b = Owner::new(Kind::BindGroup);
        a.retain(&buffer);
        a.retain(&buffer);
        b.retain(&buffer);
        drop(buffer);
        drop(a);
        assert_eq!(report().live_descriptor_bytes, Some(40));
        drop(b);
        let r = audit.finish().resident_buffer_owners;
        assert!(r.valid && r.quiescent);
        assert_eq!(r.peak_descriptor_bytes, Some(40));
    }
    #[test]
    fn discarded_encoder_and_command_keep_other_handles() {
        let (audit, _device) = audit();
        let buffer = Owner::buffer(80);
        let encoder = Owner::new(Kind::Encoder);
        encoder.retain(&buffer);
        let command = encoder.transfer(Kind::Command);
        drop(command);
        assert_eq!(report().live_descriptor_bytes, Some(80));
        drop(buffer);
        assert_eq!(audit.finish().resident_buffer_owners.peak_descriptor_bytes, Some(80));
    }
    #[test]
    fn queue_write_destination_survives_handle_and_empty_encoder() {
        let (audit, _device) = audit();
        let buffer = Owner::buffer(60);
        queue_write(&buffer);
        drop(buffer);
        assert_eq!(report().live_descriptor_bytes, Some(60));
        assert!(!report().quiescent);
        let command = Owner::new(Kind::Encoder).transfer(Kind::Command);
        let mut submission = command.transfer(Kind::Submission);
        submission.absorb_queue_writes();
        assert_eq!(report().live_descriptor_bytes, Some(60));
        submission.complete();
        drop(submission);
        assert_eq!(audit.finish().resident_buffer_owners.peak_descriptor_bytes, Some(60));
    }
    #[test]
    fn overlapping_scopes_share_live_peak_not_sum_of_peaks() {
        let (audit, _device) = audit();
        let outer = Owner::buffer(100);
        {
            let _nested_device = DeviceScope::begin(Some(7));
            let _inner = Owner::buffer(20);
        }
        assert_eq!(report().live_descriptor_bytes, Some(100));
        drop(outer);
        {
            let _later = Owner::buffer(50);
        }
        assert_eq!(audit.finish().resident_buffer_owners.peak_descriptor_bytes, Some(120));
    }
    #[test]
    fn submissions_and_replacement_encoder_are_independent() {
        let (audit, _device) = audit();
        let buffer = Owner::buffer(20);
        let old = Owner::new(Kind::Encoder);
        old.retain(&buffer);
        let replacement = Owner::new(Kind::Encoder);
        replacement.retain(&buffer);
        let mut first = old.transfer(Kind::Command).transfer(Kind::Submission);
        let mut second = replacement.transfer(Kind::Command).transfer(Kind::Submission);
        drop(buffer);
        first.complete();
        assert_eq!(report().live_descriptor_bytes, Some(20));
        second.complete();
        let r = audit.finish().resident_buffer_owners;
        assert_eq!(r.completed_submissions, 2);
        assert_eq!(r.peak_descriptor_bytes, Some(20));
    }
    #[test]
    fn uncertain_submit_and_unretired_intervals_never_publish_peak() {
        let (audit, _device) = audit();
        let buffer = Owner::buffer(4);
        let encoder = Owner::new(Kind::Encoder);
        encoder.retain(&buffer);
        assert_eq!(report().peak_descriptor_bytes, None);
        drop(encoder.transfer(Kind::Command).transfer(Kind::Submission));
        drop(buffer);
        let r = audit.finish().resident_buffer_owners;
        assert!(!r.valid);
        assert_eq!(r.uncertain_submissions, 1);
        assert_eq!(r.live_descriptor_bytes, None);
        assert_eq!(r.peak_descriptor_bytes, None);
    }
    #[test]
    fn old_generation_binding_invalidates_new_graph_but_late_drop_does_not() {
        let (first, _device) = audit();
        let old = Owner::buffer(12);
        drop(first);
        let next = super::super::ThreadResourceAudit::begin().unwrap();
        let group = Owner::new(Kind::BindGroup);
        group.retain(&old);
        drop(group);
        drop(old);
        assert!(!next.finish().resident_buffer_owners.valid);
        let (first, _device) = audit();
        let old = Owner::buffer(12);
        drop(first);
        let next = super::super::ThreadResourceAudit::begin().unwrap();
        drop(old);
        assert!(next.finish().resident_buffer_owners.valid);
    }
    #[test]
    fn pre_audit_and_cross_device_resources_invalidate() {
        let old = Owner::buffer(5);
        let (audit, _device) = audit();
        let group = Owner::new(Kind::BindGroup);
        group.retain(&old);
        drop(group);
        assert!(!audit.finish().resident_buffer_owners.valid);
        let (audit, _device) = self::audit();
        let buffer = Owner::buffer(10);
        {
            let _other = DeviceScope::begin(Some(8));
            let group = Owner::new(Kind::BindGroup);
            group.retain(&buffer);
        }
        drop(buffer);
        assert!(!audit.finish().resident_buffer_owners.valid);
    }
    #[test]
    fn retired_slots_are_reused_without_identity_reuse() {
        let (audit, _device) = audit();
        let mut previous = None;
        for _ in 0..MAX_ALLOCATIONS * 2 {
            let b = Owner::buffer(1);
            assert_ne!(b.id, previous);
            previous = b.id;
        }
        let r = audit.finish().resident_buffer_owners;
        assert!(r.valid);
        assert_eq!(r.peak_descriptor_bytes, Some(1));
    }
    #[test]
    fn byte_allocation_owner_and_edge_limits_fail_closed() {
        let mut g = Graph::new();
        let h = g.owner(7, Kind::Handle).unwrap();
        g.allocate(h, u64::MAX);
        g.allocate(h, 1);
        assert!(!g.valid);
        let mut g = Graph::new();
        let h = g.owner(7, Kind::Handle).unwrap();
        for _ in 0..MAX_ALLOCATIONS {
            g.allocate(h, 1);
        } assert!(g.valid);
        g.allocate(h, 1);
        assert!(!g.valid);
        let mut g = Graph::new();
        for _ in 0..MAX_OWNERS {
            assert!(g.owner(7, Kind::BindGroup).is_some());
        } assert!(g.owner(7, Kind::BindGroup).is_none());
        assert!(!g.valid);
        let mut g = Graph::new();
        let h = g.owner(7, Kind::Handle).unwrap();
        for _ in 0..128 {
            g.allocate(h, 1);
        }
        for _ in 0..256 {
            let o = g.owner(7, Kind::BindGroup).unwrap();
            g.retain(o, h);
        }
        assert!(!g.valid);
        assert_eq!(g.edges.len(), MAX_EDGES);
    }
    #[test]
    fn panic_after_map_invalidates_without_false_submission_uncertainty() {
        let (audit, _device) = audit();
        let _ = std::panic::catch_unwind(|| {
            let b = Owner::buffer(10); let e = Owner::new(Kind::Encoder); e.retain(&b);
            let mut s = e.transfer(Kind::Command).transfer(Kind::Submission); s.complete(); panic!("cpu extraction");
        });
        let r = audit.finish().resident_buffer_owners;
        assert!(!r.valid);
        assert_eq!(r.uncertain_submissions, 0);
        assert_eq!(r.peak_descriptor_bytes, None);
    }
    #[test]
    fn snapshot_is_send_and_pending_queue_write_is_not_completed() {
        let s = std::thread::spawn(|| {
            let (audit, _device) = audit(); let b = Owner::buffer(8); queue_write(&b); drop(b); audit.finish()
        }).join().unwrap();
        assert!(!s.resident_buffer_owners.quiescent);
        assert_eq!(s.resident_buffer_owners.peak_descriptor_bytes, None);
    }
    #[test]
    fn pending_destinations_taint_later_audit_and_other_worker() {
        let flag = Arc::new(AtomicBool::new(false));
        {
            let first = super::super::ThreadResourceAudit::begin().unwrap();
            let scope = DeviceScope::begin_backend(Some(7), &flag);
            let b = Owner::buffer(32);
            queue_write(&b);
            drop(b);
            drop(scope);
            drop(first);
        }
        let other_flag = Arc::clone(&flag);
        let report = std::thread::spawn(move || {
            let next = super::super::ThreadResourceAudit::begin().unwrap();
            let _scope = DeviceScope::begin_backend(Some(7), &other_flag);
            let mut s = Owner::new(Kind::Encoder).transfer(Kind::Command).transfer(Kind::Submission);
            s.absorb_queue_writes(); s.complete();
            other_flag.store(false, Ordering::Release); next.finish().resident_buffer_owners
        }).join().unwrap();
        assert!(!report.valid);
        assert_eq!(report.peak_descriptor_bytes, None);
        let next = super::super::ThreadResourceAudit::begin().unwrap();
        let _scope = DeviceScope::begin_backend(Some(7), &flag);
        assert!(next.finish().resident_buffer_owners.valid);
    }
    #[test]
    fn pre_audit_init_unmap_failure_leaves_pending_provenance() {
        let flag = Arc::new(AtomicBool::new(false));
        {
            let _scope = DeviceScope::begin_backend(Some(7), &flag);
            mark_pending_write();
        }
        let next = super::super::ThreadResourceAudit::begin().unwrap();
        let _scope = DeviceScope::begin_backend(Some(7), &flag);
        assert!(!next.finish().resident_buffer_owners.valid);
    }
    #[test]
    fn missing_transfer_phase_and_stale_command_invalidate() {
        let (first, _device) = audit();
        drop(Owner::buffer(4).transfer(Kind::Command));
        assert!(!first.finish().resident_buffer_owners.valid);
        let (first, _device) = audit();
        let command = Owner::new(Kind::Encoder).transfer(Kind::Command);
        drop(first);
        let next = super::super::ThreadResourceAudit::begin().unwrap();
        let mut submission = command.transfer(Kind::Submission);
        submission.complete();
        assert!(!next.finish().resident_buffer_owners.valid);
    }

}
