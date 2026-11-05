use super::*;
use crate::research_finish_tail::{self as tail, GlarePlan};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};
const ID: [[f64; 3]; 3] = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
pub(super) fn descriptor() -> GpuScanTailDescriptor<'static> {
    GpuScanTailDescriptor {
        adapt: ID,
        xyz_to_rgb: ID,
        xyz_remap: Some((0.9, 0.01)),
        glare_rgb_offset: [1., 0.99, 1.01],
        gamut: None,
        rgb_identity: ID,
        output_cctf_encoding: false,
    }
}
pub(super) fn options(lens: f32, sharp: f32, glare: bool) -> TailOptions {
    TailOptions {
        glare: glare.then_some(GlarePlan {
            percent: 0.3,
            roughness: 0.7,
            blur: 0.8,
        }),
        lens_sigma: lens,
        sharp_radius: sharp,
        sharp_amount: 0.3,
        brightness_ev: 0.5,
        finalize_cctf: !glare && lens == 0. && sharp == 0.,
        preview_output_space: Some("Display P3".into()),
    }
}
pub(super) fn root() -> PathBuf {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
    let p = std::env::temp_dir().join(format!(
        "stream-finish-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    ));
    fs::create_dir(&p).unwrap();
    p.canonicalize().unwrap()
}
fn logs(w: u32, h: u32) -> Vec<[f32; 3]> {
    (0..w * h)
        .map(|i| {
            let v = -2.5 + (i % 31) as f32 / 13.;
            [v, v + 0.025, v - 0.01]
        })
        .collect()
}
pub(super) fn sources(root: &Path, w: u32, h: u32) -> (PathBuf, PathBuf) {
    let source = root.join("source.rgb.f64le");
    fs::write(
        &source,
        logs(w, h)
            .into_iter()
            .flatten()
            .flat_map(|x| (x as f64).to_le_bytes())
            .collect::<Vec<_>>(),
    )
    .unwrap();
    let mut sampler = spektrafilm_model::glare::GlareSamples::new(0.3, 0.7, 42).unwrap();
    let glare = root.join("glare.f64le");
    fs::write(
        &glare,
        (0..w * h)
            .flat_map(|_| sampler.sample().to_le_bytes())
            .collect::<Vec<_>>(),
    )
    .unwrap();
    (source, glare)
}
#[test]
fn large_geometry_is_scientific_not_resident_allocation() {
    let plan = StreamFinishPlan::new(
        &descriptor(),
        14000,
        12000,
        options(0.7, 3.2, true),
        None,
        false,
    )
    .unwrap();
    assert_eq!((plan.parameters().width, plan.parameters().height), (1, 1));
    assert_eq!((plan.width(), plan.height()), (14000, 12000));
    assert!(TailPlan::new(&descriptor(), 14000, 12000, options(0.7, 3.2, true)).is_err());
    assert!(plan.numerical_preflight(&mut || true).is_err());
    let small = TailPlan::new(&descriptor(), 17, 13, options(0.7, 3.2, true)).unwrap();
    let scientific = |p: &TailPlan| {
        let mut value = serde_json::to_value(p).unwrap();
        let object = value.as_object_mut().unwrap();
        for key in [
            "width",
            "height",
            "deviceBytes",
            "hostBytes",
            "driverAllowanceBytes",
            "helperAllowanceBytes",
            "combinedPeakBytes",
            "diskBytes",
        ] {
            object.remove(key);
        }
        value
    };
    assert_eq!(scientific(plan.parameters()), scientific(&small));
    assert_eq!(plan.parameters().table32(), small.table32());
    let unstable = StreamFinishPlan::new(
        &descriptor(),
        40000,
        1,
        options(128., 0., false),
        None,
        false,
    )
    .unwrap();
    assert_eq!(
        unstable.numerical_preflight(&mut || false).unwrap_err(),
        "GPU_OPTICS_NUMERIC_CHECK"
    );
    for (w, h) in [(0, 1), (1, 0), (40001, 1), (1, 40001)] {
        assert!(
            StreamFinishPlan::new(&descriptor(), w, h, options(0., 0., false), None, false)
                .is_err()
        );
    }
}
#[test]
fn strict_plan_packet_round_trips_and_rejects_unvalidated_science_or_geometry() {
    let plan = StreamFinishPlan::new(
        &descriptor(),
        14000,
        12000,
        options(0.7, 3.2, true),
        Some(1024),
        false,
    )
    .unwrap();
    let json = serde_json::to_value(&plan).unwrap();
    assert_eq!(
        serde_json::from_value::<StreamFinishPlan>(json.clone()).unwrap(),
        plan
    );
    assert_eq!(
        StreamFinishPlan::from_semantic_plan(
            plan.parameters().clone(),
            plan.width(),
            plan.height(),
            plan.prefix_table_scalars(),
            plan.external_glare()
        )
        .unwrap(),
        plan
    );
    for (key, value) in [
        ("schemaVersion", serde_json::json!(2)),
        ("layout", serde_json::json!("resident")),
        ("width", serde_json::json!(40001)),
        ("unknown", serde_json::json!(0)),
        ("prefixTableScalars", serde_json::json!(0)),
    ] {
        let mut bad = json.clone();
        bad[key] = value;
        assert!(serde_json::from_value::<StreamFinishPlan>(bad).is_err());
    }
    for (key, value) in [
        ("width", serde_json::json!(2)),
        ("deviceBytes", serde_json::json!(0)),
        ("encode", serde_json::json!(true)),
    ] {
        let mut bad = json.clone();
        bad["tail"][key] = value;
        assert!(serde_json::from_value::<StreamFinishPlan>(bad).is_err());
    }
}
#[test]
fn resource_accounting_charges_actual_encodings_tables_and_lifetimes() {
    let n = 14000u64 * 12000;
    for (prefix, external) in [(None, false), (None, true), (Some(1256728), false)] {
        let plan = StreamFinishPlan::new(
            &descriptor(),
            14000,
            12000,
            options(0.7, 3.2, true),
            prefix,
            external,
        )
        .unwrap();
        let m = plan.memory().unwrap();
        let s = prefix.unwrap_or(0);
        let table_bytes = prefix.map_or(0, |s| 4 * (s + 64));
        assert_eq!(
            m.resident_device_bytes,
            7 * stream_batch() + 65536 + table_bytes
        );
        assert_eq!(m.upload_staging_bytes, stream_batch() + table_bytes);
        assert_eq!(
            m.device_bytes,
            m.resident_device_bytes + m.upload_staging_bytes
        );
        assert_eq!(m.prefix_device_table_bytes, table_bytes);
        assert_eq!(
            m.host_bytes,
            64 * spatial::MIB
                + 16 * s
                + if prefix.is_some() {
                    8 * spatial::MIB + 256
                } else {
                    0
                }
        );
        assert_eq!(m.source_snapshot_bytes, 24 * n);
        assert_eq!(m.imported_glare_bytes, if external { 8 * n } else { 0 });
        assert_eq!(m.spatial_spool_peak_bytes, 20 * n);
        assert_eq!(m.output_bytes, 12 * n);
        assert_eq!(m.output_and_spool_peak_bytes, 24 * n);
        assert_eq!(
            m.disk_bytes,
            (48 + if external { 8 } else { 0 }) * n + 8 * s + 3 * spatial::MIB
        );
        assert_eq!(
            m.combined_peak_bytes,
            m.device_bytes + m.host_bytes + m.driver_allowance_bytes + m.helper_allowance_bytes
        );
        assert!(m.combined_peak_bytes <= spatial::MEMORY_CAP);
    }
    assert!(
        StreamFinishPlan::new(&descriptor(), 1, 1, options(0., 0., false), None, true).is_err()
    );
    assert!(
        StreamFinishPlan::new(&descriptor(), 1, 1, options(0., 0., true), Some(1), true).is_err()
    );
    assert!(
        StreamFinishPlan::new(&descriptor(), 1, 1, options(0., 0., true), Some(0), false).is_err()
    );
    assert!(
        StreamFinishPlan::new(
            &descriptor(),
            1,
            1,
            options(0., 0., true),
            Some(100000000),
            false
        )
        .is_err()
    );
}
fn stream_batch() -> u64 {
    crate::research_streaming_spatial::BATCH_BYTES
}
#[cfg(feature = "gpu-diffusion-research")]
#[test]
fn production_constructor_is_closed_before_context_or_files() {
    let plan = StreamFinishPlan::new(
        &descriptor(),
        14000,
        12000,
        options(1., 3.2, true),
        None,
        false,
    )
    .unwrap();
    let mut called = false;
    assert_eq!(
        gpu::StreamingFinish::new(&plan, &mut || {
            called = true;
            false
        })
        .err()
        .unwrap(),
        "GPU_FINISH_STREAM_PENDING"
    );
    assert!(!called);
}
#[cfg(feature = "gpu-diffusion-research")]
#[test]
fn shader_families_preserve_original_fixed_spatial_and_guarded_finish_sources() {
    for (lens, sharp, glare) in [
        (0., 0., false),
        (0.7, 0.8, true),
        (0.7, 3.2, true),
        (3.1, 3.2, false),
    ] {
        for prefix in [None, Some(1024)] {
            let plan = StreamFinishPlan::new(
                &descriptor(),
                257,
                131,
                options(lens, sharp, glare),
                prefix,
                false,
            )
            .unwrap();
            let families = gpu::shader_families(&plan).unwrap();
            let mut semantic = plan.tail.spatial_plan().unwrap();
            for (channel, sigma) in semantic.channels.iter_mut().zip(plan.sigmas()) {
                channel.lens_sigma = sigma;
            }
            let originals = spatial::families::shaders_for_optics(&semantic).unwrap();
            assert_eq!(&families[..families.len() - 1], originals.as_slice());
            let mut seen = std::collections::BTreeSet::new();
            for (ops, source) in &families {
                for op in ops {
                    assert!(seen.insert(*op));
                }
                let module = naga::front::wgsl::parse_str(source)
                    .unwrap_or_else(|e| panic!("{}", e.emit_to_string(source)));
                naga::valid::Validator::new(
                    naga::valid::ValidationFlags::all(),
                    naga::valid::Capabilities::empty(),
                )
                .validate(&module)
                .unwrap();
            }
            let source = &families.last().unwrap().1;
            assert!(source.contains("invocation_numeric_error"));
            assert!(source.contains("if(op==23u)"));
            assert!(source.contains("if(op==24u)"));
            assert_eq!(seen.contains(&40), prefix.is_some());
            assert_eq!(seen.contains(&30), glare || lens > 0. || sharp > 0.);
            if glare {
                assert!(source.contains("block=start/8u+i"));
                assert!(source.contains("cursor=cursor+used"));
            }
        }
    }
}
#[cfg(feature = "gpu-diffusion-research")]
fn decode(path: &Path) -> Vec<f32> {
    fs::read(path)
        .unwrap()
        .chunks_exact(4)
        .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
        .collect()
}
#[cfg(feature = "gpu-diffusion-research")]
fn close(actual: &[f32], expected: impl Iterator<Item = f32>) {
    let expected: Vec<_> = expected.collect();
    assert_eq!(actual.len(), expected.len());
    for (i, (&a, b)) in actual.iter().zip(expected).enumerate() {
        assert!((a - b).abs() < 2e-3, "sample {i}: {a} != {b}");
    }
}
#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "Actual Vulkan complete tail graph, multi-window sequential glare and full-line FIR/IIR; not mobile timing"]
fn actual_streamed_scanner_graph_matches_original_mirror() {
    for (w, h, lens, sharp, glare, external) in [
        (1, 1, 0., 0., false, false),
        (17, 13, 0.7, 0.8, true, true),
        (257, 131, 0.7, 3.2, true, false),
        (23, 19, 3.1, 3.2, true, true),
    ] {
        let root = root();
        let (src, g) = sources(&root, w, h);
        let opts = options(lens, sharp, glare);
        let plan =
            StreamFinishPlan::new(&descriptor(), w, h, opts.clone(), None, external).unwrap();
        let reference = TailPlan::new(&descriptor(), w, h, opts).unwrap();
        let mut sampler = spektrafilm_model::glare::GlareSamples::new(0.3, 0.7, 42).unwrap();
        let field: Vec<_> = (0..w * h).map(|_| sampler.sample() as f32).collect();
        let expected = tail::image_mirror(
            &reference,
            &logs(w, h),
            glare.then_some(field.as_slice()),
            &mut || false,
        )
        .unwrap();
        let mut gpu =
            gpu::StreamingFinish::new_probe_with_capacity(&plan, None, 8192, &mut || false)
                .unwrap();
        let out = root.join("output.rgb.f32le");
        let scratch = root.join("scratch");
        let m = gpu
            .run_files(
                &src,
                external.then_some(g.as_path()),
                &out,
                &scratch,
                &mut || false,
            )
            .unwrap();
        assert_eq!(m["gpuUsed"], true);
        assert_eq!(m["nativeStageSealed"], false);
        assert_eq!(m["sampleCount"], plan.sample_count());
        assert_eq!(m["passCount"], reference.pass_count());
        close(&decode(&out), expected.into_iter().flatten());
        assert!(!scratch.exists());
        if w * h > 8192 && reference.pass_count() > 0 {
            assert_eq!(m["spatialBatchSamples"], 8192);
            assert!(
                m["streamingSpatial"]["dispatches"].as_u64().unwrap()
                    > 2 * reference.pass_count() + 3
            );
        }
        if glare && !external && w * h > 16384 {
            let stream =
                spektrafilm_model::glare::research_glare_normal_sequence(42, (w * h) as usize)
                    .unwrap();
            assert_eq!(m["glareRandomWords"], stream.last().unwrap().1);
            assert!(m["glareChunks"].as_u64().unwrap() > 1);
        }
        drop(gpu);
        fs::remove_dir_all(root).unwrap();
    }
}
#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "One test per process: real activated-session full graph, zero-context native sealing, then actual reuse"]
fn actual_session_seals_only_after_stream_owner_and_files_close() {
    use crate::{gpu_session as session, gpu_session_contract::StageTicket};
    let root = root();
    let (src, _) = sources(&root, 33, 19);
    let plan =
        StreamFinishPlan::new(&descriptor(), 33, 19, options(0.7, 3.2, true), None, false).unwrap();
    let memory = plan.memory().unwrap();
    let epoch = 9201;
    session::activate(
        epoch,
        memory.combined_peak_bytes,
        memory.host_bytes + memory.helper_allowance_bytes,
        8,
    )
    .unwrap();
    let mut outputs = Vec::new();
    for generation in 1..=2 {
        let ticket = StageTicket {
            epoch,
            generation,
            // This fixture runs one stage per frame. Sequence is consecutive
            // across the entire epoch, not reset when generation changes.
            sequence: generation,
        };
        session::begin_frame(epoch, generation).unwrap();
        session::begin_stage(ticket, memory.driver_allowance_bytes).unwrap();
        let mut gpu =
            gpu::StreamingFinish::new_probe_with_capacity(&plan, None, 8192, &mut || false)
                .unwrap();
        assert!(session::complete_stage(ticket).is_err());
        let out = root.join(format!("out-{generation}"));
        let scratch = root.join(format!("scratch-{generation}"));
        let report = gpu
            .run_files(&src, None, &out, &scratch, &mut || false)
            .unwrap();
        assert_eq!(report["nativeStageSealed"], false);
        assert!(session::complete_stage(ticket).is_err());
        drop(gpu);
        assert!(!scratch.exists());
        let sealed = session::complete_stage(ticket).unwrap();
        assert!(sealed.pool_bytes <= memory.resident_device_bytes);
        assert!(sealed.charged_bytes <= memory.combined_peak_bytes);
        if generation == 2 {
            assert!(sealed.pipeline_hits > 0);
            assert!(sealed.buffer_hits > 0);
        }
        session::finish_frame(epoch, generation).unwrap();
        outputs.push(decode(&out));
    }
    assert_eq!(outputs[0], outputs[1]);
    fs::remove_dir_all(root).unwrap();
}
#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "Actual Vulkan fused original print/scanner graph comparison with original resident reference, spatial effects and glare"]
fn actual_fused_prefix_streaming_matches_original_resident_complete_graph() {
    use crate::research_finish::{self as finish, fused::FinishPrefix};
    use spektrafilm_core::pipeline::{GpuFinishDescriptor, GpuFinishInput, GpuPrintingDescriptor};
    use spektrafilm_math::pchip3d::prepare_pchip_3d;
    let (w, h) = (257, 131);
    let root = root();
    let src: [PathBuf; 3] = std::array::from_fn(|ch| {
        let p = root.join(format!("density-{ch}"));
        let mut f = FileForTest::new(&p);
        for i in 0..w * h {
            f.write_all(&((i % 11) as f64 / 10. + ch as f64 * 0.01).to_le_bytes())
                .unwrap();
        }
        p
    });
    let mut data = Vec::new();
    for x in 0..4 {
        for y in 0..4 {
            for z in 0..4 {
                data.extend([x as f64 / 3., y as f64 / 3., z as f64 / 3.]);
            }
        }
    }
    let lut = prepare_pchip_3d(data, 4);
    let axis = [-10., -1., 0., 1., 10.];
    let curves = [[0.; 3], [0.2; 3], [0.5; 3], [0.7; 3], [1.; 3]];
    for (token, mode) in [
        (11, GpuFinishInput::FilmDensity),
        (12, GpuFinishInput::PrintRaw),
    ] {
        let d = GpuFinishDescriptor {
            input: mode,
            printing: Some(GpuPrintingDescriptor {
                lut: &lut,
                data_min: [0.; 3],
                data_max: [1.; 3],
                print_exposure: 0.75,
                bw: 1.2,
                log_exposure: &axis,
                curves: &curves,
                gamma: 1.1,
                external_diffusion_required: false,
            }),
            scanner: &lut,
            scanner_min: [0.; 3],
            scanner_max: [1.; 3],
        };
        let dir = root.join(format!("gpu-stage-{token}"));
        let prefix_plan =
            finish::prepare_stage([&src[0], &src[1], &src[2]], &dir, token, w, h, &d, || false)
                .unwrap();
        let plan = StreamFinishPlan::new(
            &descriptor(),
            w,
            h,
            options(0.7, 3.2, true),
            Some(prefix_plan.table_scalars),
            false,
        )
        .unwrap();
        let mut original = FinishPrefix::load(&dir, token, &mut || false).unwrap();
        let original_tail = TailPlan::new(&descriptor(), w, h, options(0.7, 3.2, true)).unwrap();
        let expected = root.join(format!("resident-{token}"));
        tail::gpu::run_files_with_prefix_probe(
            &original_tail,
            &expected,
            &mut || false,
            &mut original,
        )
        .unwrap();
        drop(original);
        let prefix = FinishPrefix::load(&dir, token, &mut || false).unwrap();
        let mut gpu =
            gpu::StreamingFinish::new_probe_with_capacity(&plan, Some(&prefix), 8192, &mut || {
                false
            })
            .unwrap();
        let output = root.join(format!("streamed-{token}"));
        let scratch = root.join(format!("scratch-{token}"));
        let m = gpu
            .run_with_prefix(prefix, &output, &scratch, &mut || false)
            .unwrap();
        assert_eq!(m["contextCount"], 1);
        assert_eq!(m["spatialBatchSamples"], 8192);
        assert!(
            m["streamingSpatial"]["dispatches"].as_u64().unwrap()
                > 2 * original_tail.pass_count() + 3
        );
        assert_eq!(m["sampleCount"], plan.sample_count());
        assert!(m["prefixSampleCount"].as_u64().unwrap() > 24);
        close(&decode(&output), decode(&expected).into_iter());
        assert!(!scratch.exists());
        drop(gpu);
    }
    fs::remove_dir_all(root).unwrap();
}
#[cfg(feature = "gpu-diffusion-research")]
struct FileForTest;
#[cfg(feature = "gpu-diffusion-research")]
impl FileForTest {
    fn new(path: &Path) -> fs::File {
        fs::File::create(path).unwrap()
    }
}
