use super::*;
use crate::research_dir::Curve;
fn curve(axis: Vec<f64>, values: Vec<[f64; 3]>, gamma: f64) -> Curve {
    let axis: Vec<_> = axis.into_iter().map(|x| x / gamma).collect();
    let inverse_dx = axis.windows(2).map(|p| 1. / (p[1] - p[0])).collect();
    Curve {
        axis,
        inverse_dx,
        values,
    }
}
pub(super) fn fixture(w: u32, h: u32, positive: bool) -> DirPlan {
    let axis = vec![-3., -1.25, -0.5, 0.2, 1.7, 3.];
    let values = vec![
        [0., 0.1, 0.2],
        [0.4, 0.7, 0.5],
        [0.9, 0.8, 0.6],
        [1.1, 1.3, 1.4],
        [1.8, 1.6, 1.5],
        [2.2, 2.1, 2.],
    ];
    DirPlan {
        width: w,
        height: h,
        dir_active: true,
        positive,
        core_sigma: 3.5,
        tail_decay: 2.1,
        tail_weight: 0.3,
        matrix: [[0.09, 0.03, 0.02], [0.07, 0.06, 0.01], [0.04, 0.05, 0.08]],
        density_max: [2.2, 2.1, 2.],
        initial: curve(axis.clone(), values.clone(), 0.8),
        before_dir: curve(axis, values, 1.2),
    }
}
#[test]
fn fifty_megapixel_plan_keeps_scientific_descriptor_and_old_resident_rejection() {
    let p = fixture(8192, 6144, true);
    assert!(p.validate().is_err());
    let plan = StreamDirPlan::from_plan(p.clone(), Some(0.73)).unwrap();
    assert_eq!(plan.parameters(), &p);
    let mut small = p.clone();
    small.width = 71;
    small.height = 37;
    small.validate().unwrap();
    let mut descriptor = plan.parameters().clone();
    descriptor.width = 71;
    descriptor.height = 37;
    assert_eq!(descriptor, small);
    let m = plan.memory().unwrap();
    assert_eq!(m.device_bytes, 32 * MIB + 65536);
    assert_eq!(m.host_bytes, 65 * MIB);
    assert_eq!(m.pipeline_count, 6);
    assert_eq!(m.driver_allowance_bytes, 112 * MIB);
    assert_eq!(m.combined_peak_bytes, 273 * MIB + 65536);
    assert_eq!(m.disk_bytes, 8192 * 6144 * 68 + 2 * MIB);
    assert!(plan.parameters().validate().is_err());
    let encoded = serde_json::to_vec(&plan).unwrap();
    let restored: StreamDirPlan = serde_json::from_slice(&encoded).unwrap();
    restored.validate().unwrap();
    assert_eq!(restored, plan);
}
#[test]
fn stream_plan_rejects_invalid_geometry_semantics_and_version() {
    for (w, h) in [(0, 1), (1, 0), (40001, 1), (u32::MAX, u32::MAX)] {
        assert!(StreamDirPlan::from_plan(fixture(w, h, false), None).is_err());
    }
    let mut p = fixture(8192, 6144, false);
    p.initial.inverse_dx[0] += 1.;
    assert!(StreamDirPlan::from_plan(p, None).is_err());
    for correction in [0., -1., f64::NAN, f64::INFINITY] {
        assert!(StreamDirPlan::from_plan(fixture(8192, 6144, false), Some(correction)).is_err());
    }
    let plan = StreamDirPlan::from_plan(fixture(71, 37, false), None).unwrap();
    let mut value = serde_json::to_value(&plan).unwrap();
    value["schemaVersion"] = 2.into();
    assert!(
        serde_json::from_value::<StreamDirPlan>(value)
            .unwrap()
            .validate()
            .is_err()
    );
    let mut value = serde_json::to_value(&plan).unwrap();
    value["unknown"] = true.into();
    assert!(serde_json::from_value::<StreamDirPlan>(value).is_err());
}
#[test]
fn true_line_preflight_rejects_unstable_recursion_despite_tiny_semantic_plan() {
    let mut p = fixture(40000, 1, false);
    p.core_sigma = 128.;
    p.tail_decay = 0.;
    let plan = StreamDirPlan::from_plan(p, None).unwrap();
    assert_eq!(
        plan.numerical_preflight(&mut || false).unwrap_err(),
        "GPU_OPTICS_NUMERIC_CHECK"
    );
    let plan = StreamDirPlan::from_plan(fixture(71, 37, false), None).unwrap();
    assert_eq!(
        plan.numerical_preflight(&mut || true).unwrap_err(),
        "CANCELLED"
    );
}
#[test]
fn original_selected_families_and_log_body_survive_verbatim() {
    let p = fixture(8192, 6144, false);
    let plan = StreamDirPlan::from_plan(p.clone(), Some(0.73)).unwrap();
    let actual = plan.families().unwrap();
    let expected =
        spatial::families::shaders_for_dir(p.dir_active, p.core_sigma, p.tail_decay).unwrap();
    assert_eq!(&actual[..expected.len()], expected.as_slice());
    assert_eq!(
        actual.last().unwrap(),
        &(
            vec![16, 17],
            crate::research_log_dir::log_prefix::shader().unwrap()
        )
    );
    for (_, source) in actual {
        let module = naga::front::wgsl::parse_str(&source).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::all(),
        )
        .validate(&module)
        .unwrap();
    }
}

#[cfg(feature = "gpu-diffusion-research")]
#[test]
fn production_constructor_remains_closed_before_context_or_files() {
    let plan = StreamDirPlan::from_plan(fixture(8192, 6144, false), Some(0.73)).unwrap();
    let mut called = false;
    assert_eq!(
        StreamingDir::new(&plan, &mut || {
            called = true;
            false
        })
        .err()
        .unwrap(),
        "GPU_DIR_STREAM_PENDING"
    );
    assert!(!called);
}
