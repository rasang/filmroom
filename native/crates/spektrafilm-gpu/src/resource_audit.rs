//! Opt-in bounded allocation census, NOT a global live-memory or physical-peak meter.
//! The separate observed_dispatch_owners field covers completed generic dispatch
//! scopes only. resident_buffer_owners separately tracks current-worker buffer
//! handles, state bind groups, commands, submissions and pending write destinations.
//! Neither field establishes global host ownership or physical driver coverage.
//!
//! A session records calls made on its current thread only. Device bytes are
//! cumulative sizes of returned WGPU buffers, never decremented at a drop or
//! fence. WGPU/driver allocator pools, hidden staging, cached pipelines, other
//! threads, and most caller-owned CPU allocations remain unobserved. Do not
//! use this evidence alone to reduce a memory-admission coefficient.
use std::{cell::RefCell, marker::PhantomData, rc::Rc};
#[path = "observed_owners.rs"]
pub mod observed_owners;
#[path = "resident_owners.rs"]
pub mod resident_owners;

pub const MAX_BUFFER_LABELS: usize = 96;
pub const MAX_HOST_OBSERVATIONS: usize = 32;
pub const MAX_CHECKPOINTS: usize = 32;

/// UTF-8 label stored without heap allocation. Truncation is explicit.
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct Label { bytes: [u8; 64], len: u8, pub truncated: bool }
impl Label {
    fn new(text: &str) -> Self {
        let mut len = text.len().min(64);
        while !text.is_char_boundary(len) { len -= 1; }
        let mut bytes = [0; 64];
        bytes[..len].copy_from_slice(&text.as_bytes()[..len]);
        Self { bytes, len: len as u8, truncated: len != text.len() }
    }
    pub fn as_str(&self) -> &str {
        // Only constructed from a valid UTF-8 prefix above.
        std::str::from_utf8(&self.bytes[..usize::from(self.len)]).unwrap_or("invalid_label")
    }
}
impl std::fmt::Debug for Label {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Label").field("text", &self.as_str()).field("truncated", &self.truncated).finish()
    }
}

#[derive(Clone, Copy, Debug)]
pub struct BufferLabelCensus {
    pub label: Label,
    pub usage_bits: u32,
    pub creation_count: u64,
    pub cumulative_descriptor_bytes: u64,
    pub largest_descriptor_bytes: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HostObservationKind {
    /// Capacity of one actually owned Vec at the observation point.
    VecCapacity,
    /// Length only: the originating Vec's spare capacity is unavailable here.
    BorrowedPayload,
    /// Sum of input/output capacities surrounding conversion, not a claim
    /// that both allocations were observed simultaneously alive.
    ConversionCapacityEnvelope,
}
#[derive(Clone, Copy, Debug)]
pub struct HostObservation {
    pub label: &'static str,
    pub kind: HostObservationKind,
    pub bytes: u64,
    pub cumulative_buffer_descriptor_bytes_at_observation: u64,
}
#[derive(Clone, Copy, Debug)]
pub struct Checkpoint {
    pub label: &'static str,
    pub cumulative_buffer_descriptor_bytes: u64,
    pub cumulative_queue_write_payload_bytes: u64,
    pub buffer_creation_count: u64,
}

/// Bounded diagnostic value. None entries are unused slots, not zero-byte
/// allocations. Overflow/truncation must be retained by any JSON adapter.
#[derive(Clone, Debug)]
pub struct Snapshot {
    pub audit_generation: Option<u64>,
    pub resident_buffer_owners: resident_owners::ResidentOwnerReport,
    pub observed_dispatch_owners: observed_owners::ObservedDispatchOwners,
    pub schema: &'static str,
    pub cumulative_buffer_descriptor_bytes: u64,
    pub cumulative_storage_descriptor_bytes: u64,
    pub cumulative_map_read_descriptor_bytes: u64,
    pub largest_buffer_descriptor_bytes: u64,
    pub buffer_creation_count: u64,
    /// Payload handed to create_buffer_init; already included in device
    /// descriptor totals. NOT an additional staging allocation estimate.
    pub cumulative_init_payload_bytes: u64,
    /// Payload handed to Queue::write_buffer. NOT its physical staging size.
    pub cumulative_queue_write_payload_bytes: u64,
    pub queue_write_count: u64,
    pub buffer_labels: [Option<BufferLabelCensus>; MAX_BUFFER_LABELS],
    pub host_observations: [Option<HostObservation>; MAX_HOST_OBSERVATIONS],
    pub checkpoints: [Option<Checkpoint>; MAX_CHECKPOINTS],
    pub unbucketed_buffer_creations: u64,
    pub omitted_host_observations: u64,
    pub omitted_checkpoints: u64,
    pub arithmetic_saturated: bool,
    /// These literal coverage labels prevent accidental presentation as RSS.
    pub device_measurement: &'static str,
    pub host_measurement: &'static str,
    pub logical_live_owner_peak: Option<u64>,
    pub physical_driver_peak: Option<u64>,
}
impl Default for Snapshot {
    fn default() -> Self {
        Self {
            audit_generation: None,
            resident_buffer_owners: Default::default(),
            observed_dispatch_owners: Default::default(),
            schema: "wgpu-allocation-census-v1",
            cumulative_buffer_descriptor_bytes: 0, cumulative_storage_descriptor_bytes: 0,
            cumulative_map_read_descriptor_bytes: 0, largest_buffer_descriptor_bytes: 0,
            buffer_creation_count: 0, cumulative_init_payload_bytes: 0,
            cumulative_queue_write_payload_bytes: 0, queue_write_count: 0,
            buffer_labels: [None; MAX_BUFFER_LABELS],
            host_observations: [None; MAX_HOST_OBSERVATIONS], checkpoints: [None; MAX_CHECKPOINTS],
            unbucketed_buffer_creations: 0, omitted_host_observations: 0, omitted_checkpoints: 0,
            arithmetic_saturated: false,
            device_measurement: "cumulative-returned-buffer-descriptors-current-thread",
            host_measurement: "partial-labeled-capacity-or-payload-observations-not-total",
            logical_live_owner_peak: None, physical_driver_peak: None,
        }
    }
}
fn add(value: &mut u64, increment: u64, saturated: &mut bool) {
    match value.checked_add(increment) {
        Some(sum) => *value = sum,
        None => { *value = u64::MAX; *saturated = true; }
    }
}
impl Snapshot {
    fn buffer(&mut self, label: &str, size: u64, usage: u32, storage: bool, map_read: bool, init: u64) {
        add(&mut self.cumulative_buffer_descriptor_bytes, size, &mut self.arithmetic_saturated);
        if storage { add(&mut self.cumulative_storage_descriptor_bytes, size, &mut self.arithmetic_saturated); }
        if map_read { add(&mut self.cumulative_map_read_descriptor_bytes, size, &mut self.arithmetic_saturated); }
        add(&mut self.buffer_creation_count, 1, &mut self.arithmetic_saturated);
        add(&mut self.cumulative_init_payload_bytes, init, &mut self.arithmetic_saturated);
        self.largest_buffer_descriptor_bytes = self.largest_buffer_descriptor_bytes.max(size);
        let label = Label::new(label);
        // Truncated labels are never merged: different long labels could
        // share a prefix. Global totals still include unbucketed events.
        let index = self.buffer_labels.iter().position(|entry| entry.is_some_and(|b|
            !label.truncated && b.label == label && b.usage_bits == usage))
            .or_else(|| self.buffer_labels.iter().position(Option::is_none));
        if let Some(index) = index {
            let entry = self.buffer_labels[index].get_or_insert(BufferLabelCensus {
                label, usage_bits: usage, creation_count: 0,
                cumulative_descriptor_bytes: 0, largest_descriptor_bytes: 0,
            });
            add(&mut entry.creation_count, 1, &mut self.arithmetic_saturated);
            add(&mut entry.cumulative_descriptor_bytes, size, &mut self.arithmetic_saturated);
            entry.largest_descriptor_bytes = entry.largest_descriptor_bytes.max(size);
        } else { add(&mut self.unbucketed_buffer_creations, 1, &mut self.arithmetic_saturated); }
    }
}

thread_local! { static ACTIVE: RefCell<Option<Snapshot>> = const { RefCell::new(None) }; }
fn record(f: impl FnOnce(&mut Snapshot)) {
    ACTIVE.with(|state| { if let Some(snapshot) = state.borrow_mut().as_mut() { f(snapshot); } });
}

/// Start explicitly in a validation/probe call, before the intended renderer
/// entry. No environment variable, background thread or global observer exists.
/// Nested sessions are rejected rather than clearing an outer session.
/// This guard cannot move to another thread. Drop disables and clears it,
/// including cancellation/error/unwind paths. `finish` preserves the report.
pub struct ThreadResourceAudit { _same_thread: PhantomData<Rc<()>> }
impl ThreadResourceAudit {
    pub fn begin() -> Option<Self> {
        ACTIVE.with(|state| {
            let mut state = state.borrow_mut();
            if state.is_some() { return None; }
            let mut snapshot = Snapshot::default();
            snapshot.audit_generation = observed_owners::identity();
            *state = Some(snapshot);
            resident_owners::begin(state.as_ref().unwrap().audit_generation.is_some());
            Some(Self { _same_thread: PhantomData })
        })
    }
    pub fn snapshot(&self) -> Snapshot {
        let mut snapshot = ACTIVE.with(|state| state.borrow().as_ref().expect("active audit guard").clone());
        snapshot.resident_buffer_owners = resident_owners::report();
        snapshot
    }
    pub fn finish(self) -> Snapshot {
        let mut snapshot = ACTIVE.with(|state| state.borrow_mut().take().expect("active audit guard"));
        snapshot.resident_buffer_owners = resident_owners::report();
        snapshot
    }
}
impl Drop for ThreadResourceAudit {
    fn drop(&mut self) { resident_owners::end(); let _ = ACTIVE.try_with(|state| { state.borrow_mut().take(); }); }
}

pub(crate) fn buffer_created(label: Option<&str>, size: u64, usage: u32, storage: bool, map_read: bool, init: u64) {
    record(|s| s.buffer(label.unwrap_or("<unlabeled>"), size, usage, storage, map_read, init));
}
pub(crate) fn queue_write(payload: u64) {
    record(|s| {
        add(&mut s.cumulative_queue_write_payload_bytes, payload, &mut s.arithmetic_saturated);
        add(&mut s.queue_write_count, 1, &mut s.arithmetic_saturated);
    });
}
/// Capacity/payload observations are independent samples; do not sum them.
/// A probe may add caller field/input capacities that a borrowed GPU view hides.
pub fn observe_host(label: &'static str, kind: HostObservationKind, bytes: u64) {
    record(|s| {
        if let Some(slot) = s.host_observations.iter_mut().find(|slot| slot.is_none()) {
            *slot = Some(HostObservation { label, kind, bytes,
                cumulative_buffer_descriptor_bytes_at_observation: s.cumulative_buffer_descriptor_bytes });
        } else { add(&mut s.omitted_host_observations, 1, &mut s.arithmetic_saturated); }
    });
}
pub fn checkpoint(label: &'static str) {
    record(|s| {
        if let Some(slot) = s.checkpoints.iter_mut().find(|slot| slot.is_none()) {
            *slot = Some(Checkpoint { label,
                cumulative_buffer_descriptor_bytes: s.cumulative_buffer_descriptor_bytes,
                cumulative_queue_write_payload_bytes: s.cumulative_queue_write_payload_bytes,
                buffer_creation_count: s.buffer_creation_count });
        } else { add(&mut s.omitted_checkpoints, 1, &mut s.arithmetic_saturated); }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cumulative_bytes_are_not_live_peak_or_staging() {
        let guard = ThreadResourceAudit::begin().unwrap();
        buffer_created(Some("image"), 12, 1, true, false, 0);
        buffer_created(Some("init"), 8, 2, false, true, 5);
        queue_write(12); checkpoint("completed_fence");
        let s = guard.finish();
        assert_eq!(s.cumulative_buffer_descriptor_bytes, 20);
        assert_eq!(s.cumulative_init_payload_bytes, 5);
        assert_eq!(s.cumulative_storage_descriptor_bytes, 12);
        assert_eq!(s.cumulative_map_read_descriptor_bytes, 8);
        assert_eq!(s.cumulative_queue_write_payload_bytes, 12);
        assert_eq!(s.checkpoints[0].unwrap().cumulative_buffer_descriptor_bytes, 20);
        assert_eq!(s.logical_live_owner_peak, None); assert_eq!(s.physical_driver_peak, None);
    }
    #[test]
    fn nesting_drop_and_disabled_calls_do_not_leak() {
        buffer_created(None, 100, 0, false, false, 0);
        let outer = ThreadResourceAudit::begin().unwrap();
        assert!(ThreadResourceAudit::begin().is_none());
        queue_write(4); assert_eq!(outer.snapshot().queue_write_count, 1);
        drop(outer);
        queue_write(8);
        let fresh = ThreadResourceAudit::begin().unwrap().finish();
        assert_eq!(fresh.queue_write_count, 0);
    }
    #[test]
    fn cancelled_scope_and_unwind_disable_and_reset() {
        fn cancelled() -> Option<()> {
            let _guard = ThreadResourceAudit::begin().unwrap(); queue_write(1); None
        }
        assert_eq!(cancelled(), None);
        assert!(std::panic::catch_unwind(|| {
            let _guard = ThreadResourceAudit::begin().unwrap(); queue_write(2); panic!("test unwind")
        }).is_err());
        let fresh = ThreadResourceAudit::begin().unwrap().finish();
        assert_eq!(fresh.cumulative_queue_write_payload_bytes, 0);
    }
    #[test]
    fn sessions_are_thread_isolated() {
        let guard = ThreadResourceAudit::begin().unwrap(); queue_write(7);
        let child = std::thread::spawn(|| {
            queue_write(99);
            let guard = ThreadResourceAudit::begin().unwrap(); queue_write(3); guard.finish()
        }).join().unwrap();
        assert_eq!(child.cumulative_queue_write_payload_bytes, 3);
        assert_eq!(guard.finish().cumulative_queue_write_payload_bytes, 7);
    }
    #[test]
    fn bounded_slots_preserve_totals_and_report_omissions() {
        let guard = ThreadResourceAudit::begin().unwrap();
        for i in 0..MAX_BUFFER_LABELS + 4 { buffer_created(Some(&format!("b{i}")), 3, 0, false, false, 0); }
        for _ in 0..MAX_HOST_OBSERVATIONS + 2 { observe_host("host", HostObservationKind::VecCapacity, 40); }
        for _ in 0..MAX_CHECKPOINTS + 3 { checkpoint("phase"); }
        let s = guard.finish();
        assert_eq!(s.cumulative_buffer_descriptor_bytes, (MAX_BUFFER_LABELS as u64 + 4) * 3);
        assert_eq!(s.unbucketed_buffer_creations, 4);
        assert_eq!(s.omitted_host_observations, 2); assert_eq!(s.omitted_checkpoints, 3);
    }
    #[test]
    fn label_and_arithmetic_limits_are_explicit() {
        let long = "测".repeat(30);
        let label = Label::new(&long);
        assert!(label.truncated); assert!(label.as_str().len() <= 64);
        let guard = ThreadResourceAudit::begin().unwrap();
        buffer_created(Some(&long), u64::MAX, 0, false, false, 0);
        buffer_created(Some(&long), 1, 0, false, false, 0);
        let s = guard.finish();
        assert!(s.arithmetic_saturated); assert_eq!(s.cumulative_buffer_descriptor_bytes, u64::MAX);
        assert!(s.buffer_labels[1].is_some()); // Truncated prefixes never merge.
    }
    #[test]
    fn host_capacity_is_not_replaced_by_length_or_totalled() {
        let mut values = Vec::<f32>::with_capacity(100); values.resize(3, 0.0);
        let guard = ThreadResourceAudit::begin().unwrap();
        observe_host("owned", HostObservationKind::VecCapacity, values.capacity() as u64 * 4);
        observe_host("slice", HostObservationKind::BorrowedPayload, values.len() as u64 * 4);
        let s = guard.finish();
        assert!(s.host_observations[0].unwrap().bytes >= 400);
        assert_eq!(s.host_observations[1].unwrap().bytes, 12);
        assert_eq!(s.logical_live_owner_peak, None);
    }
}

fn active_generation() -> Option<u64> {
    ACTIVE.with(|state| state.borrow().as_ref().and_then(|s| s.audit_generation))
}
fn record_owner_scope(generation: u64, peak: Option<u64>, uncertain: bool) {
    // A stale guard must not write into a later audit on a reused worker.
    let _ = ACTIVE.try_with(|state| {
        if let Ok(mut state) = state.try_borrow_mut() {
            if let Some(s) = state.as_mut().filter(|s| s.audit_generation == Some(generation)) {
                s.observed_dispatch_owners.include(peak, uncertain);
            }
        }
    });
}
