//! Dedicated-build JNI adapter. No code from this module enters ordinary APKs.
//! A native invocation gets one bounded report, including failure/cancellation.
//! Export collection begins on its actual private-pool worker, not the JNI thread.
use std::{cell::{Cell, RefCell}, panic::{catch_unwind, AssertUnwindSafe, resume_unwind}};
use serde_json::{json, Value};
use spektrafilm_gpu::resource_audit::{self, Snapshot, ThreadResourceAudit, HostObservationKind};

const MAX_METADATA_BYTES: usize = 48 * 1024;
thread_local! {
    static NATIVE_ACTIVE: Cell<bool> = const { Cell::new(false) };
    static EXPORT_REQUESTED: Cell<bool> = const { Cell::new(false) };
    static EXPORT_REPORT: RefCell<Option<Snapshot>> = const { RefCell::new(None) };
}

pub(crate) struct NativeProbe {
    audit: Option<ThreadResourceAudit>, owns: bool, export: bool,
}
impl NativeProbe {
    pub(crate) fn begin(export: bool) -> Self {
        let owns = NATIVE_ACTIVE.with(|active| if active.get() { false } else { active.set(true); true });
        let audit = if owns {
            EXPORT_REPORT.with(|report| { report.borrow_mut().take(); });
            EXPORT_REQUESTED.with(|requested| requested.set(export));
            if export { None } else { ThreadResourceAudit::begin() }
        } else { None };
        Self { audit, owns, export }
    }
    pub(crate) fn attach(mut self, original: String) -> String {
        let report = if !self.owns { None }
            else if self.export { EXPORT_REPORT.with(|report| report.borrow_mut().take()) }
            else { self.audit.take().map(ThreadResourceAudit::finish) };
        let scope = if self.export { "export-execution-thread" } else { "jni-preview-thread" };
        // Diagnostics must not turn a successful/failed render into an FFI panic.
        // Do not change existing ok/code/outputValid semantics on any outcome.
        catch_unwind(AssertUnwindSafe(|| {
            let mut value: Value = serde_json::from_str(&original).ok()?;
            let object = value.as_object_mut()?;
            object.insert("resourceCensus".into(), match report.as_ref() {
                Some(report) => bounded_report(report, scope),
                None => json!({"schema":"wgpu-allocation-census-v1", "collectionStatus":"not_collected",
                    "reason":if self.owns {"no_worker_or_audit_scope_entered"}else{"nested_native_scope"},
                    "scope":scope,"logicalLiveOwnerPeakBytes":null,"physicalDriverPeakBytes":null}),
            });
            Some(value.to_string())
        })).ok().flatten().unwrap_or(original)
    }
}
impl Drop for NativeProbe {
    fn drop(&mut self) {
        if self.owns {
            // Dropping a still-active preview guard also clears its census.
            self.audit.take();
            let _ = EXPORT_REPORT.try_with(|report| { report.borrow_mut().take(); });
            let _ = EXPORT_REQUESTED.try_with(|requested| requested.set(false));
            let _ = NATIVE_ACTIVE.try_with(|active| active.set(false));
        }
    }
}

fn capture<R>(work: impl FnOnce() -> R) -> (std::thread::Result<R>, Option<Snapshot>) {
    let audit = ThreadResourceAudit::begin();
    let result = catch_unwind(AssertUnwindSafe(work));
    let report = audit.map(ThreadResourceAudit::finish);
    (result, report)
}
/// Return the report across the same `run` boundary as its result. No thread-
/// local guard crosses threads; original panic payload is resumed afterward.
pub(crate) fn run_export<R: Send>(execution: &crate::export_execution::ExportPermit,
    work: impl FnOnce() -> R + Send) -> R {
    if !EXPORT_REQUESTED.with(Cell::get) { return execution.run(work); }
    let (result, report) = execution.run(|| capture(work));
    EXPORT_REPORT.with(|pending| *pending.borrow_mut() = report);
    match result { Ok(result) => result, Err(panic) => resume_unwind(panic) }
}

pub(crate) fn observe_vec<T>(label: &'static str, values: &Vec<T>) {
    resource_audit::observe_host(label, HostObservationKind::VecCapacity,
        values.capacity() as u64 * std::mem::size_of::<T>() as u64);
}
fn clipped(label: &str) -> String { label.chars().take(64).collect() }
fn observed_owner_report(s: &Snapshot) -> Value {
    let o = s.observed_dispatch_owners;
    json!({"scope":"generic-dispatch-compute-only",
        "measurement":"maximum-completed-scope-observed-buffer-descriptor-owner-peak",
        "auditIdentityAvailable":s.audit_generation.is_some(),
        "maximumCompletedScopePeakBytes":o.maximum_completed_scope_peak_bytes,
        "completedScopes":o.completed_scopes,"invalidScopes":o.invalid_scopes,
        "uncertainSubmissionScopes":o.uncertain_submission_scopes,
        "globalCoverageComplete":false,"residentChainCovered":false,
        "activeOrUncertainLiveBytes":null,
        "excludes":"resident chains, other dispatch methods, host allocations, pipeline caches, driver pools and hidden staging",
        "invalidScopePolicy":"overflow, missing transitions and unwind invalidate that scope; unknown submissions never retire as zero"})
}
fn resident_owner_report(s: &Snapshot) -> Value {
    let o = s.resident_buffer_owners;
    json!({"scope":"current-worker-wgpu-backend-buffer-owners",
        "measurement":"logical-buffer-descriptor-ownership-not-physical-memory",
        "completedIntervalPeakDescriptorBytes":o.peak_descriptor_bytes,
        "observedLiveDescriptorBytes":o.live_descriptor_bytes,
        "valid":o.valid,"quiescent":o.quiescent,
        "activeAllocations":o.active_allocations,"activeOwners":o.active_owners,
        "completedSubmissions":o.completed_submissions,"uncertainSubmissions":o.uncertain_submissions,
        "observerGraphCapacityBytes":o.metadata_capacity_bytes,
        "observerMemory":"audit-only host metadata; excludes allocator overhead, Rc control block and temporary binding conversion",
        "instrumentedRoutes":"generic dispatch, single/multi blur, resident state/effects/density capture, stage-batched and final submission",
        "instrumentedOwners":"individual handles, bind groups, encoders, commands, submissions, pending queue-write destinations",
        "excludes":"host image/caches, opaque pipeline/layout caches, other workers/backends, hidden staging and physical driver pools",
        "globalCoverageComplete":false,"physicalDriverMeasured":false,"peakAdmissionAuthority":false,
        "invalidPolicy":"unknown/pre-audit resources, metadata/identity/byte overflow, cross-device edges, unwind and uncertain submission suppress completed interval peaks",
        "limits":{"simultaneousAllocations":2048,"simultaneousOwners":4096,"simultaneousEdges":32768}})
}
fn bounded_report(s: &Snapshot, scope: &'static str) -> Value {
    let labels: Vec<_> = s.buffer_labels.iter().flatten().map(|b| json!({
        "label":b.label.as_str(),"labelTruncated":b.label.truncated,"usageBits":b.usage_bits,
        "creationCount":b.creation_count,"cumulativeDescriptorBytes":b.cumulative_descriptor_bytes,
        "largestDescriptorBytes":b.largest_descriptor_bytes})).collect();
    let host: Vec<_> = s.host_observations.iter().flatten().map(|h| json!({
        "label":clipped(h.label),"kind":match h.kind {
            HostObservationKind::VecCapacity=>"owned_vec_capacity",
            HostObservationKind::BorrowedPayload=>"borrowed_payload_capacity_unknown",
            HostObservationKind::ConversionCapacityEnvelope=>"conversion_capacity_envelope_not_observed_overlap"},
        "bytes":h.bytes,"cumulativeDescriptorBytesAtObservation":h.cumulative_buffer_descriptor_bytes_at_observation})).collect();
    let checkpoints: Vec<_> = s.checkpoints.iter().flatten().map(|p| json!({
        "label":clipped(p.label),"cumulativeDescriptorBytes":p.cumulative_buffer_descriptor_bytes,
        "cumulativeQueueWritePayloadBytes":p.cumulative_queue_write_payload_bytes,
        "bufferCreationCount":p.buffer_creation_count})).collect();
    let mut value = json!({"schema":s.schema,"collectionStatus":"collected","scope":scope,
        "deviceCoverage":"spektrafilm-gpu/wgpu_backend-current-thread-only",
        "deviceMeasurement":s.device_measurement,"hostMeasurement":s.host_measurement,
        "cumulativeBufferDescriptorBytes":s.cumulative_buffer_descriptor_bytes,
        "cumulativeStorageDescriptorBytes":s.cumulative_storage_descriptor_bytes,
        "cumulativeMapReadDescriptorBytes":s.cumulative_map_read_descriptor_bytes,
        "largestBufferDescriptorBytes":s.largest_buffer_descriptor_bytes,
        "bufferCreationCount":s.buffer_creation_count,"cumulativeInitPayloadBytes":s.cumulative_init_payload_bytes,
        "cumulativeQueueWritePayloadBytes":s.cumulative_queue_write_payload_bytes,"queueWriteCount":s.queue_write_count,
        "logicalLiveOwnerPeakBytes":null,"physicalDriverPeakBytes":null,
        "observedDispatchOwners":observed_owner_report(s),
        "residentBufferOwners":resident_owner_report(s),
        "peakAdmissionAuthority":false,"hostCoverageComplete":false,"physicalDriverMeasured":false,
        "driverPoolsAndHiddenStaging":"unknown","otherBackendAllocations":"not_observed",
        "bufferLabels":labels,"hostObservations":host,"checkpoints":checkpoints,
        "unbucketedBufferCreations":s.unbucketed_buffer_creations,"omittedHostObservations":s.omitted_host_observations,
        "omittedCheckpoints":s.omitted_checkpoints,"arithmeticSaturated":s.arithmetic_saturated,
        "detailSerializationTruncated":false,"maxMetadataBytes":MAX_METADATA_BYTES,
        "collectorValueBytes":std::mem::size_of::<Snapshot>(),
        "warning":"Cumulative descriptors, transfer payload and partial host samples are not simultaneous live memory, RSS or a physical GPU peak. Categories overlap. Admission unchanged."});
    // All arrays are already fixed-count and labels bounded; cap serialized
    // metadata too. Keep totals and make serialization-only loss unmistakable.
    if value.to_string().len() > MAX_METADATA_BYTES {
        value["serializedBufferLabelsOmitted"] = json!(value["bufferLabels"].as_array().map_or(0, Vec::len));
        value["serializedHostObservationsOmitted"] = json!(value["hostObservations"].as_array().map_or(0, Vec::len));
        value["serializedCheckpointsOmitted"] = json!(value["checkpoints"].as_array().map_or(0, Vec::len));
        value["bufferLabels"] = json!([]); value["hostObservations"] = json!([]); value["checkpoints"] = json!([]);
        value["detailSerializationTruncated"] = json!(true);
    }
    if value.to_string().len() > MAX_METADATA_BYTES {
        // Future schema descriptions must not defeat the serialized cap.
        return json!({"schema":"wgpu-allocation-census-v1","collectionStatus":"collected",
            "scope":scope,"cumulativeBufferDescriptorBytes":s.cumulative_buffer_descriptor_bytes,
            "cumulativeStorageDescriptorBytes":s.cumulative_storage_descriptor_bytes,
            "cumulativeMapReadDescriptorBytes":s.cumulative_map_read_descriptor_bytes,
            "largestBufferDescriptorBytes":s.largest_buffer_descriptor_bytes,
            "bufferCreationCount":s.buffer_creation_count,"cumulativeInitPayloadBytes":s.cumulative_init_payload_bytes,
            "cumulativeQueueWritePayloadBytes":s.cumulative_queue_write_payload_bytes,"queueWriteCount":s.queue_write_count,
            "logicalLiveOwnerPeakBytes":null,"physicalDriverPeakBytes":null,
            "observedDispatchOwners":observed_owner_report(s),
        "residentBufferOwners":resident_owner_report(s),
            "peakAdmissionAuthority":false,"hostCoverageComplete":false,"physicalDriverMeasured":false,
            "detailSerializationTruncated":true,"descriptionSerializationTruncated":true,
            "unbucketedBufferCreations":s.unbucketed_buffer_creations,"omittedHostObservations":s.omitted_host_observations,
            "omittedCheckpoints":s.omitted_checkpoints,"arithmeticSaturated":s.arithmetic_saturated,
            "maxMetadataBytes":MAX_METADATA_BYTES,
            "warning":"Cumulative descriptors only; categories overlap; host/device peak unknown; admission unchanged."});
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preview_metadata_preserves_result_and_null_unknown_peaks() {
        let probe = NativeProbe::begin(false);
        let values = Vec::<f32>::with_capacity(71); observe_vec("fixture", &values);
        let value: Value = serde_json::from_str(&probe.attach("{\"ok\":true,\"outputValid\":true}".into())).unwrap();
        assert_eq!(value["ok"], true); assert_eq!(value["outputValid"], true);
        let report = &value["resourceCensus"];
        assert_eq!(report["collectionStatus"], "collected");
        assert_eq!(report["physicalDriverPeakBytes"], Value::Null);
        assert_eq!(report["logicalLiveOwnerPeakBytes"], Value::Null);
        assert_eq!(report["hostObservations"][0]["bytes"], values.capacity() * 4);
    }
    #[test]
    fn worker_census_crosses_thread_without_guard_and_error_is_preserved() {
        let probe = NativeProbe::begin(true);
        let (result, report) = std::thread::spawn(|| capture(|| {
            let v = Vec::<f64>::with_capacity(17); observe_vec("worker", &v); Err::<(), _>("CANCELLED")
        })).join().unwrap();
        assert_eq!(result.unwrap(), Err("CANCELLED"));
        EXPORT_REPORT.with(|pending| *pending.borrow_mut() = report);
        let value: Value = serde_json::from_str(&probe.attach("{\"ok\":false,\"code\":\"CANCELLED\",\"outputValid\":false}".into())).unwrap();
        assert_eq!(value["code"], "CANCELLED"); assert_eq!(value["outputValid"], false);
        assert_eq!(value["resourceCensus"]["scope"], "export-execution-thread");
        assert_eq!(value["resourceCensus"]["hostObservations"][0]["bytes"], 136);
    }
    #[test]
    fn panic_report_is_available_and_fresh_native_call_resets() {
        let probe = NativeProbe::begin(true);
        let (result, report) = capture(|| { resource_audit::checkpoint("before_panic"); panic!("probe") });
        assert!(result.is_err()); EXPORT_REPORT.with(|pending| *pending.borrow_mut() = report);
        let value: Value = serde_json::from_str(&probe.attach("{\"ok\":false,\"code\":\"ENGINE_PANIC\"}".into())).unwrap();
        assert_eq!(value["code"], "ENGINE_PANIC");
        assert_eq!(value["resourceCensus"]["checkpoints"][0]["label"], "before_panic");
        let next = NativeProbe::begin(true);
        let value: Value = serde_json::from_str(&next.attach("{\"ok\":false}".into())).unwrap();
        assert_eq!(value["resourceCensus"]["collectionStatus"], "not_collected");
    }
    #[test]
    fn nested_native_scope_cannot_erase_outer_census() {
        let outer = NativeProbe::begin(false); resource_audit::checkpoint("outer");
        let inner = NativeProbe::begin(false);
        let value: Value = serde_json::from_str(&inner.attach("{}".into())).unwrap();
        assert_eq!(value["resourceCensus"]["reason"], "nested_native_scope");
        let value: Value = serde_json::from_str(&outer.attach("{}".into())).unwrap();
        assert_eq!(value["resourceCensus"]["checkpoints"][0]["label"], "outer");
    }
    #[test]
    fn metadata_is_bounded_and_does_not_relabel_cumulative_as_peak() {
        let s = Snapshot::default(); let report = bounded_report(&s, "test");
        assert!(report.to_string().len() <= MAX_METADATA_BYTES);
        assert_eq!(report["peakAdmissionAuthority"], false);
        assert_eq!(report["physicalDriverMeasured"], false);
        assert_eq!(report["hostCoverageComplete"], false);
        let mut large = s;
        large.device_measurement = Box::leak("description".repeat(MAX_METADATA_BYTES).into_boxed_str());
        let report = bounded_report(&large, "test");
        assert!(report.to_string().len() <= MAX_METADATA_BYTES);
        assert_eq!(report["descriptionSerializationTruncated"], true);
        assert_eq!(report["physicalDriverPeakBytes"], Value::Null);
    }
}

#[cfg(test)]
mod observed_owner_adapter_tests {
    use super::*;
    #[test]
    fn scoped_peak_never_promotes_global_coverage_or_unknown_zero() {
        let mut s = Snapshot::default();
        s.observed_dispatch_owners.completed_scopes = 1;
        s.observed_dispatch_owners.invalid_scopes = 1;
        s.observed_dispatch_owners.uncertain_submission_scopes = 1;
        s.observed_dispatch_owners.maximum_completed_scope_peak_bytes = Some(123);
        let value = bounded_report(&s, "fixture");
        assert_eq!(value["observedDispatchOwners"]["maximumCompletedScopePeakBytes"], 123);
        assert_eq!(value["observedDispatchOwners"]["uncertainSubmissionScopes"], 1);
        assert_eq!(value["observedDispatchOwners"]["globalCoverageComplete"], false);
        assert_eq!(value["observedDispatchOwners"]["activeOrUncertainLiveBytes"], Value::Null);
        assert_eq!(value["logicalLiveOwnerPeakBytes"], Value::Null);
        assert_eq!(value["physicalDriverPeakBytes"], Value::Null);
        assert_eq!(value["peakAdmissionAuthority"], false);
        s.device_measurement = Box::leak("large".repeat(MAX_METADATA_BYTES).into_boxed_str());
        let fallback = bounded_report(&s, "fixture");
        assert_eq!(fallback["observedDispatchOwners"], value["observedDispatchOwners"]);
        assert!(fallback.to_string().len() <= MAX_METADATA_BYTES);
    }
    #[test]
    fn resident_owner_report_preserves_bounds_without_global_peak_claims() {
        let mut s = Snapshot::default();
        s.resident_buffer_owners = resource_audit::resident_owners::ResidentOwnerReport {
            peak_descriptor_bytes: Some(1234), live_descriptor_bytes: Some(0),
            valid: true, quiescent: true, completed_submissions: 3, ..Default::default()
        };
        let value = bounded_report(&s, "fixture");
        let owner = &value["residentBufferOwners"];
        assert_eq!(owner["completedIntervalPeakDescriptorBytes"], 1234);
        assert_eq!(owner["completedSubmissions"], 3);
        assert_eq!(owner["globalCoverageComplete"], false);
        assert_eq!(value["logicalLiveOwnerPeakBytes"], Value::Null);
        assert_eq!(value["physicalDriverPeakBytes"], Value::Null);
        assert_eq!(value["peakAdmissionAuthority"], false);
        s.device_measurement = Box::leak("large".repeat(MAX_METADATA_BYTES).into_boxed_str());
        let fallback = bounded_report(&s, "fixture");
        assert_eq!(fallback["residentBufferOwners"], *owner);
        assert!(fallback.to_string().len() <= MAX_METADATA_BYTES);
    }

}
