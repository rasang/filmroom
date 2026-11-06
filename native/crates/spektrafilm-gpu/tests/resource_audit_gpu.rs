//! Explicit real-GPU probe; not built into default APK behavior.
#![cfg(feature = "resource-audit")]
use spektrafilm_gpu::{resource_audit::ThreadResourceAudit, wgpu_backend::WgpuBackend};
use spektrafilm_math::{image::ImageBuf, precision::from_f64};

fn backend() -> Option<WgpuBackend> {
    let result = WgpuBackend::new();
    if result.is_none() {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(), Ok("1"),
            "Actual GPU required for allocation census probe");
        eprintln!("NOT_RUN resource_audit_gpu: GPU unavailable");
    }
    result
}
fn input() -> ImageBuf {
    ImageBuf::from_data(19, 13, (0..19 * 13 * 3)
        .map(|i| from_f64((i % 43) as f64 / 23.0 - 0.2)).collect())
}

#[test]
fn standalone_scanner_blur_is_censused_without_pixel_changes() {
    let Some(gpu) = backend() else { return; };
    let img = input();
    let expected = gpu.gaussian_blur_gpu(&img, 1.25);
    let submits = gpu.thread_submits();
    let audit = ThreadResourceAudit::begin().unwrap();
    let actual = gpu.gaussian_blur_gpu(&img, 1.25);
    let report = audit.finish();
    assert_eq!(actual.data, expected.data);
    assert_eq!(gpu.thread_submits() - submits, 1);
    assert_eq!(report.buffer_creation_count, 6);
    assert_eq!(report.queue_write_count, 1);
    assert_eq!(report.cumulative_queue_write_payload_bytes, 19 * 13 * 12);
    for label in ["blur_in", "blur_mid", "blur_out", "blur_readback", "gaussian_kernel", "gaussian_params"] {
        assert!(report.buffer_labels.iter().flatten().any(|b| b.label.as_str() == label), "missing {label}");
    }
    assert!(report.host_observations.iter().flatten().any(|h|
        h.label == "blur_source_scalar" && h.bytes == img.data.capacity() as u64
            * std::mem::size_of::<spektrafilm_math::precision::Scalar>() as u64));
    assert_eq!(report.logical_live_owner_peak, None);
    assert_eq!(report.physical_driver_peak, None);
    assert!(!report.arithmetic_saturated);
    eprintln!("RESOURCE_CENSUS {report:?}");
}

#[test]
fn standalone_multi_blur_is_censused_and_session_resets() {
    let Some(gpu) = backend() else { return; };
    let img = input();
    let sigmas = [1.25, 2.0];
    let expected = gpu.gaussian_blur_multi_gpu(&img, &sigmas);
    let submits = gpu.thread_submits();
    let audit = ThreadResourceAudit::begin().unwrap();
    let actual = gpu.gaussian_blur_multi_gpu(&img, &sigmas);
    let report = audit.finish();
    assert_eq!(gpu.thread_submits() - submits, 1);
    assert_eq!(actual.len(), expected.len());
    for (a, b) in actual.iter().zip(&expected) { assert_eq!(a.data, b.data); }
    assert_eq!(report.buffer_creation_count, 10); // 2 shared + 4 per sigma.
    assert_eq!(report.queue_write_count, 1);
    for label in ["blur_multi_in", "blur_multi_mid", "blur_multi_out_0", "blur_multi_out_1",
        "blur_multi_readback_0", "blur_multi_readback_1"] {
        assert!(report.buffer_labels.iter().flatten().any(|b| b.label.as_str() == label), "missing {label}");
    }
    let fresh = ThreadResourceAudit::begin().unwrap().finish();
    assert_eq!(fresh.buffer_creation_count, 0);
    assert_eq!(fresh.queue_write_count, 0);
    eprintln!("RESOURCE_CENSUS {report:?}");
}
