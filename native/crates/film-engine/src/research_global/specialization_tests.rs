//! Each ignored test MUST run alone in a fresh process. Session epochs cannot be
//! reopened after retirement. These are actual Vulkan runner/oracle gates, not
//! Android performance, driver-memory, or full-pipeline image-quality evidence.
use super::*;
use crate::{gpu_session as session, gpu_session_contract::StageTicket};

fn session_mode(method: &str) {
    const EPOCH: u64 = 7301;
    const MIB: u64 = 1024 * 1024;
    let root = super::super::tests::root();
    let families = if method == "median" { 4usize } else { 3 };
    session::activate(EPOCH, 768 * MIB, 80 * MIB, 8).unwrap();
    let mut previous_hits = 0;
    let mut cold_bytes = Vec::new();
    let mut cold_oracle = None;
    for (index, (w, h, changed)) in [
        (5, 3, false), (5, 3, false), (17, 13, true), (256, 256, false),
    ].into_iter().enumerate() {
        let generation = index as u64 + 1;
        let input = super::super::tests::rgb(&root, w, h);
        let mut matrix = spektrafilm_math::colorspace::SRGB_TO_XYZ;
        if changed { matrix[1] = [0.2, 0.6, 0.2]; }
        let p = GlobalPlan::meter(w, h, method, matrix).unwrap();
        let dir = root.join(format!("gpu-stage-{}", 7300 + generation));
        prepare_meter_stage(&input, &dir, 7300 + generation, &p, || false).unwrap();
        let candidate = dir.join("candidate");
        std::fs::create_dir(&candidate).unwrap();
        let (sw, sh, pack) = read_meter_pack(&input, &p, &mut || false).unwrap();
        let expected = meter_mirror(&p, &pack, sw, sh, &mut || false).unwrap();
        session::begin_frame(EPOCH, generation).unwrap();
        let ticket = StageTicket { epoch: EPOCH, generation, sequence: generation };
        session::begin_stage(ticket, p.driver_allowance_bytes).unwrap();
        let report = run_files_inner(&p, &dir, &candidate, &mut || false, true).unwrap();
        let stats = session::complete_stage(ticket).unwrap();
        session::finish_frame(EPOCH, generation).unwrap();
        let actual = std::fs::read(candidate.join("meter.f32le")).unwrap();
        let values = floats(&actual);
        assert_eq!(values.len(), 2);
        assert!((values[0] - expected.0).abs() < 2e-3, "{method} frame {generation}: {report}");
        assert!((values[1] - expected.1).abs() < 2e-3 * expected.1.max(1.), "{method} frame {generation}: {report}");
        assert_eq!(report["meterMethod"], method);
        assert_eq!(report["gpuUsed"], true);
        assert!(report["dispatches"].as_u64().unwrap() >= 4);
        assert_eq!(stats.pipeline_slots, families, "dimensions/matrix must not create new variants");
        if index == 0 {
            assert_eq!(stats.pipeline_hits, 0, "fresh process required");
            cold_bytes = actual.clone();
            cold_oracle = Some(expected);
        } else {
            assert_eq!(stats.pipeline_hits - previous_hits, families as u64);
            if index == 1 {
                assert_eq!(actual, cold_bytes, "same input cold/warm output changed");
                assert_eq!(Some(expected), cold_oracle);
            }
        }
        previous_hits = stats.pipeline_hits;
        eprintln!("ACTUAL_VULKAN_GLOBAL_SPECIALIZED method={method} generation={generation} width={w} height={h} expected={expected:?} report={report} session={}", serde_json::to_string(&stats).unwrap());
    }
    session::request_stop(EPOCH).unwrap();
    std::fs::remove_dir_all(root).unwrap();
}
macro_rules! mode_gate {
    ($name:ident, $mode:literal) => {
        #[test]
        #[ignore = "Actual Vulkan cold/warm/matrix/dimension gate; run this one test per process"]
        fn $name() { session_mode($mode); }
    };
}
mode_gate!(actual_vulkan_average_cold_warm_changed, "average");
mode_gate!(actual_vulkan_median_cold_warm_changed, "median");
mode_gate!(actual_vulkan_center_weighted_cold_warm_changed, "center_weighted");
mode_gate!(actual_vulkan_partial_cold_warm_changed, "partial");
mode_gate!(actual_vulkan_matrix_cold_warm_changed, "matrix");
mode_gate!(actual_vulkan_multi_zone_cold_warm_changed, "multi_zone");
mode_gate!(actual_vulkan_highlight_weighted_cold_warm_changed, "highlight_weighted");
mode_gate!(actual_vulkan_unknown_cold_warm_changed, "unknown");

#[test]
#[ignore = "Actual Vulkan whole-candidate rejection for every mode; no host replacement"]
fn actual_vulkan_every_mode_rejects_unsampled_luminance() {
    let root = super::super::tests::root();
    for (index, method) in super::super::tests::METHODS.iter().enumerate() {
        let input = root.join(format!("unsafe-meter-{index}"));
        let mut rgb = vec![0.25f32; 251 * 3];
        rgb[17 * 3 + 1] = 2e15;
        assert!(!(0..8).map(|i| i * 250 / 7).any(|i| i == 17));
        std::fs::write(&input, bytemuck::cast_slice(&rgb)).unwrap();
        let p = GlobalPlan::meter(251, 1, method, [[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]).unwrap();
        let token = 7400 + index as u64;
        let dir = root.join(format!("gpu-stage-{token}"));
        prepare_meter_stage(&input, &dir, token, &p, || false).unwrap();
        let candidate = dir.join("candidate");
        std::fs::create_dir(&candidate).unwrap();
        assert_eq!(run_files_inner(&p, &dir, &candidate, &mut || false, true).unwrap_err(), "GPU_GLOBAL_NUMERIC_CHECK", "method={method}");
        assert_eq!(std::fs::read_dir(candidate).unwrap().count(), 0, "method={method}");
    }
    std::fs::remove_dir_all(root).unwrap();
}
