//! Bounded logical owners for ONE generic dispatch, not the resident graph.
//! No pointer identity, GPU handles, locks, or cross-thread guard ownership.
//! Each owner bit names the single object of that role in dispatch_compute.
//! This is not a general multi-bind-group or multi-submission graph.
use std::{marker::PhantomData, rc::Rc, sync::atomic::{AtomicU64, Ordering}};

const MAX_ALLOCATIONS: usize = 128;
const HANDLE: u8 = 1;
const BIND_GROUP: u8 = 2;
const ENCODER: u8 = 4;
const COMMAND: u8 = 8;
const SUBMISSION: u8 = 16;
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
/// Exhaustion stays exhausted; it never wraps and reuses an identity.
pub(crate) fn identity() -> Option<u64> {
    NEXT_ID.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1)).ok()
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct AllocationId { device: u64, generation: u64, scope: u64, slot: usize }
#[derive(Clone, Copy, Debug)]
struct Allocation { id: AllocationId, bytes: u64, owners: u8 }
#[derive(Debug)]
struct Graph {
    device: u64, generation: u64, scope: u64,
    allocations: [Option<Allocation>; MAX_ALLOCATIONS],
    len: usize, live: u64, peak: u64, valid: bool,
}
impl Graph {
    fn new(device: u64, generation: u64, scope: u64) -> Self {
        Self { device, generation, scope, allocations: [None; MAX_ALLOCATIONS],
            len: 0, live: 0, peak: 0, valid: true }
    }
    fn allocate(&mut self, bytes: u64) -> Option<AllocationId> {
        if self.len == MAX_ALLOCATIONS { self.valid = false; return None; }
        let Some(live) = self.live.checked_add(bytes) else { self.valid = false; return None; };
        let id = AllocationId { device: self.device, generation: self.generation, scope: self.scope, slot: self.len };
        self.allocations[self.len] = Some(Allocation { id, bytes, owners: HANDLE });
        self.len += 1; self.live = live; self.peak = self.peak.max(live); Some(id)
    }
    fn retain(&mut self, id: AllocationId, owner: u8) {
        let Some(Some(a)) = self.allocations.get_mut(id.slot) else { self.valid = false; return; };
        if a.id != id || a.owners == 0 { self.valid = false; return; }
        // Repeated alias in a single owner is a set edge, not another allocation.
        a.owners |= owner;
    }
    fn release(&mut self, owner: u8) {
        for a in self.allocations.iter_mut().flatten() {
            let before = a.owners;
            a.owners &= !owner;
            if before != 0 && a.owners == 0 { self.live -= a.bytes; }
        }
    }
    fn retain_all(&mut self, owner: u8) {
        for slot in 0..self.len {
            let id = self.allocations[slot].unwrap().id; self.retain(id, owner);
        }
    }
}

/// Values aggregate independent dispatch scopes by MAX, never by addition.
/// They exclude all resident paths, host memory and driver allocations.
#[derive(Clone, Copy, Debug, Default)]
pub struct ObservedDispatchOwners {
    pub completed_scopes: u64,
    pub invalid_scopes: u64,
    pub uncertain_submission_scopes: u64,
    pub maximum_completed_scope_peak_bytes: Option<u64>,
}
impl ObservedDispatchOwners {
    pub(crate) fn include(&mut self, peak: Option<u64>, uncertain: bool) {
        if uncertain { self.uncertain_submission_scopes = self.uncertain_submission_scopes.saturating_add(1); }
        if let Some(peak) = peak {
            self.completed_scopes = self.completed_scopes.saturating_add(1);
            self.maximum_completed_scope_peak_bytes = Some(self.maximum_completed_scope_peak_bytes.unwrap_or(0).max(peak));
        } else { self.invalid_scopes = self.invalid_scopes.saturating_add(1); }
    }
}

/// Created before allocations and dropped after actual local handles/bind group.
/// A panic or incomplete submit never reports a zero or a completed scope peak.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase { Building, Encoding, Command, Submitting, Complete }
pub(crate) struct DispatchOwners {
    graph: Option<Graph>, submitting: bool, completed: bool, phase: Phase, bound: bool,
    _same_thread: PhantomData<Rc<()>>,
}
impl DispatchOwners {
    pub(crate) fn begin(device: Option<u64>) -> Self {
        let generation = super::active_generation();
        let graph = generation.and_then(|generation| device.zip(identity()).map(|(device, scope)| Graph::new(device, generation, scope)));
        if generation.is_some() && graph.is_none() { super::record_owner_scope(generation.unwrap(), None, false); }
        Self { graph, submitting: false, completed: false, phase: Phase::Building, bound: false, _same_thread: PhantomData }
    }
    fn transition(&mut self, from: Phase, to: Phase) {
        if self.phase != from { if let Some(g) = &mut self.graph { g.valid = false; } }
        self.phase = to;
    }
    pub(crate) fn buffer(&mut self, bytes: u64) {
        self.transition(Phase::Building, Phase::Building); if let Some(g) = &mut self.graph { g.allocate(bytes); } }
    pub(crate) fn bind_group(&mut self) {
        self.transition(Phase::Building, Phase::Building);
        if let Some(g) = &mut self.graph { if self.bound { g.valid = false; } g.retain_all(BIND_GROUP); }
        self.bound = true;
    }
    pub(crate) fn encoder(&mut self) { self.transition(Phase::Building, Phase::Encoding); if let Some(g) = &mut self.graph { g.retain_all(ENCODER); } }
    pub(crate) fn command(&mut self) { self.transition(Phase::Encoding, Phase::Command); if let Some(g) = &mut self.graph { g.retain_all(COMMAND); g.release(ENCODER); } }
    pub(crate) fn submitting(&mut self) {
        self.transition(Phase::Command, Phase::Submitting);
        self.submitting = true;
        if let Some(g) = &mut self.graph { g.retain_all(SUBMISSION); g.release(COMMAND); }
    }
    /// Call only after existing successful mapping fence for this dispatch.
    pub(crate) fn completed(&mut self) {
        if !self.submitting { if let Some(g) = &mut self.graph { g.valid = false; } return; }
        self.transition(Phase::Submitting, Phase::Complete);
        self.completed = true;
        if let Some(g) = &mut self.graph { g.release(SUBMISSION); }
    }
}
impl Drop for DispatchOwners {
    fn drop(&mut self) {
        if let Some(g) = &mut self.graph {
            let valid = g.valid && self.bound && self.completed && !std::thread::panicking();
            // All actual local resource owners have now dropped. This is NOT
            // a statement about allocator pools, cached pipelines or staging.
            if valid { g.release(BIND_GROUP | HANDLE); }
            super::record_owner_scope(g.generation, valid.then_some(g.peak), self.submitting && !self.completed);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn aliases_and_fence_keep_surviving_handles_charged() {
        let mut g = Graph::new(1, 2, 3); let a = g.allocate(100).unwrap();
        g.retain(a, BIND_GROUP); g.retain(a, BIND_GROUP); g.retain(a, ENCODER);
        assert_eq!(g.live, 100); g.release(HANDLE);
        g.retain(a, COMMAND); g.release(ENCODER); g.retain(a, SUBMISSION); g.release(COMMAND);
        g.release(SUBMISSION); assert_eq!(g.live, 100); g.release(BIND_GROUP); assert_eq!(g.live, 0); assert_eq!(g.peak, 100);
    }
    #[test]
    fn discarded_encoder_releases_only_its_edges() {
        let mut g = Graph::new(1, 2, 3); let a = g.allocate(10).unwrap();
        g.retain(a, ENCODER); g.release(ENCODER); assert_eq!(g.live, 10);
        g.release(HANDLE); assert_eq!(g.live, 0);
    }
    #[test]
    fn identities_do_not_alias_across_device_generation_or_scope() {
        let mut g = Graph::new(1, 2, 3); let a = g.allocate(8).unwrap();
        for other in [AllocationId { device: 2, ..a }, AllocationId { generation: 3, ..a }, AllocationId { scope: 4, ..a }] {
            g.valid = true; g.retain(other, BIND_GROUP); assert!(!g.valid); assert_eq!(g.live, 8);
        }
    }
    #[test]
    fn overflow_invalidates_instead_of_silently_undercounting() {
        let mut g = Graph::new(1, 2, 3);
        for _ in 0..MAX_ALLOCATIONS { assert!(g.allocate(1).is_some()); }
        assert!(g.allocate(1).is_none()); assert!(!g.valid);
        let mut g = Graph::new(1, 2, 4); g.allocate(u64::MAX); assert!(g.allocate(1).is_none()); assert!(!g.valid);
    }
    #[test]
    fn uncertain_submit_never_reports_zero_peak() {
        let audit = super::super::ThreadResourceAudit::begin().unwrap();
        { let mut scope = DispatchOwners::begin(Some(1)); scope.buffer(12); scope.encoder(); scope.command(); scope.submitting(); }
        let s = audit.finish(); assert_eq!(s.observed_dispatch_owners.uncertain_submission_scopes, 1);
        assert_eq!(s.observed_dispatch_owners.maximum_completed_scope_peak_bytes, None);
        assert_eq!(s.logical_live_owner_peak, None);
    }
    #[test]
    fn old_scope_cannot_contaminate_new_generation() {
        let audit = super::super::ThreadResourceAudit::begin().unwrap();
        let mut scope = DispatchOwners::begin(Some(1)); scope.buffer(12); drop(audit);
        let next = super::super::ThreadResourceAudit::begin().unwrap(); drop(scope);
        assert_eq!(next.finish().observed_dispatch_owners.invalid_scopes, 0);
    }
    #[test]
    fn completed_worker_report_is_sendable_and_scopes_use_max() {
        let s = std::thread::spawn(|| {
            let audit = super::super::ThreadResourceAudit::begin().unwrap();
            for bytes in [12, 20] { let mut scope = DispatchOwners::begin(Some(1)); scope.buffer(bytes); scope.bind_group(); scope.encoder(); scope.command(); scope.submitting(); scope.completed(); }
            audit.finish()
        }).join().unwrap();
        assert_eq!(s.observed_dispatch_owners.completed_scopes, 2);
        assert_eq!(s.observed_dispatch_owners.maximum_completed_scope_peak_bytes, Some(20));
    }
    #[test]
    fn panic_after_fence_still_invalidates_scope() {
        let audit = super::super::ThreadResourceAudit::begin().unwrap();
        let _ = std::panic::catch_unwind(|| { let mut scope = DispatchOwners::begin(Some(1)); scope.buffer(4); scope.bind_group(); scope.encoder(); scope.command(); scope.submitting(); scope.completed(); panic!("fixture"); });
        let s = audit.finish(); assert_eq!(s.observed_dispatch_owners.invalid_scopes, 1);
        assert_eq!(s.observed_dispatch_owners.maximum_completed_scope_peak_bytes, None);
    }
    #[test]
    fn map_error_timeout_disconnect_and_submit_panic_stay_unknown() {
        for boundary in ["map_error", "map_timeout", "map_disconnect", "submit_panic"] {
            let audit = super::super::ThreadResourceAudit::begin().unwrap();
            let _ = std::panic::catch_unwind(|| {
                let mut scope = DispatchOwners::begin(Some(1)); scope.buffer(40);
                scope.encoder(); scope.command(); scope.submitting(); panic!("{boundary}");
            });
            let s = audit.finish(); assert_eq!(s.observed_dispatch_owners.uncertain_submission_scopes, 1);
            assert_eq!(s.observed_dispatch_owners.maximum_completed_scope_peak_bytes, None);
        }
    }
    #[test]
    fn missing_transition_and_partial_construction_are_invalid() {
        let audit = super::super::ThreadResourceAudit::begin().unwrap();
        { let mut scope = DispatchOwners::begin(Some(1)); scope.buffer(12); }
        { let mut scope = DispatchOwners::begin(Some(1)); scope.buffer(12); scope.submitting(); scope.completed(); }
        let s = audit.finish(); assert_eq!(s.observed_dispatch_owners.invalid_scopes, 2);
        assert_eq!(s.observed_dispatch_owners.maximum_completed_scope_peak_bytes, None);
    }
    #[test]
    fn late_old_generation_completion_is_not_new_generation_evidence() {
        let audit = super::super::ThreadResourceAudit::begin().unwrap();
        let mut scope = DispatchOwners::begin(Some(1));
        scope.buffer(12); scope.bind_group(); scope.encoder(); scope.command(); scope.submitting();
        drop(audit);
        let next = super::super::ThreadResourceAudit::begin().unwrap();
        scope.completed();
        // Without the generation mismatch this is a valid completed scope,
        // not an independently invalid graph that could mask missing filtering.
        assert!(scope.graph.as_ref().unwrap().valid);
        assert!(scope.bound && scope.completed && !std::thread::panicking());
        assert_eq!(scope.graph.as_ref().unwrap().peak, 12);
        drop(scope);
        let observed = next.finish().observed_dispatch_owners;
        assert_eq!(observed.completed_scopes, 0);
        assert_eq!(observed.invalid_scopes, 0);
        assert_eq!(observed.uncertain_submission_scopes, 0);
        assert_eq!(observed.maximum_completed_scope_peak_bytes, None);
    }

}
