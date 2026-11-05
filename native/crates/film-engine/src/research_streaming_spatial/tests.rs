use super::*;
use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};

static NEXT: AtomicU64 = AtomicU64::new(1);
struct Root(PathBuf);
impl Root {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "film-stream-spatial-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Root {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[derive(Default)]
struct Mirror {
    metrics: StreamMetrics,
    failure_at: Option<u64>,
}
impl Compute for Mirror {
    fn execute(
        &mut self,
        inputs: &[(u32, &[f32])],
        p: [u32; 40],
        _output: u32,
        c: &mut impl FnMut() -> bool,
    ) -> R<Vec<f32>> {
        check(c)?;
        if self.failure_at == Some(self.metrics.dispatches) {
            return Err("INJECTED_GPU_FAILURE".into());
        }
        self.metrics.dispatches += 1;
        let get = |slot| inputs.iter().find(|(s, _)| *s == slot).unwrap().1;
        let n = (p[0] * p[1]) as usize;
        let coeff = |i| f32::from_bits(p[i]);
        let out = match p[2] {
            0 | 2 | 4 => {
                let f = match p[2] {
                    0 => Filter::Fir(
                        (0..2 * p[5] + 1)
                            .map(|i| coeff(16 + i as usize) as f64)
                            .collect(),
                    ),
                    2 => Filter::Iir([
                        coeff(8) as f64,
                        coeff(9) as f64,
                        coeff(10) as f64,
                        coeff(11) as f64,
                    ]),
                    _ => Filter::Identity,
                };
                get(0)
                    .chunks_exact(p[0] as usize)
                    .flat_map(|line| f.line_f32(line))
                    .collect()
            }
            6 => (0..n).map(|i| get(4)[i] + coeff(12) * get(2)[i]).collect(),
            7 => (0..n)
                .map(|i| {
                    (1. - coeff(12)) * get(0)[i]
                        + coeff(12) * ((1. - coeff(13)) * get(3)[i] + coeff(13) * get(4)[i])
                })
                .collect(),
            8 => (0..n).map(|i| get(0)[i] + coeff(14) * get(4)[i]).collect(),
            9 => (0..n).map(|i| get(0)[i] / coeff(15)).collect(),
            _ => panic!("unexpected op"),
        };
        Ok(out)
    }
    fn metrics(&mut self) -> &mut StreamMetrics {
        &mut self.metrics
    }
}
fn channel(sigma: f32) -> ChannelPlan {
    ChannelPlan {
        lens_sigma: sigma,
        scatter: None,
        bounce_sigmas: vec![],
        bounce_weights: vec![],
        strength: 0.,
        divisor: 1.,
    }
}
fn fixture(root: &Root, width: u32, height: u32) -> ([PathBuf; 3], [PathBuf; 3], Vec<f64>) {
    let source: Vec<_> = (0..width * height)
        .map(|i| {
            ((i * 37 % 101) as f64 / 13.) - 0.7 + if i == width * height / 3 { 17. } else { 0. }
        })
        .collect();
    let inputs = std::array::from_fn(|ch| root.0.join(format!("in-{ch}")));
    let outputs = std::array::from_fn(|ch| root.0.join(format!("out-{ch}")));
    let encoded: Vec<u8> = source.iter().flat_map(|x| x.to_le_bytes()).collect();
    for path in &inputs {
        fs::write(path, &encoded).unwrap();
    }
    (inputs, outputs, source)
}
fn decoded(path: &std::path::Path) -> Vec<f64> {
    fs::read(path)
        .unwrap()
        .chunks_exact(8)
        .map(|b| f64::from_le_bytes(b.try_into().unwrap()))
        .collect()
}

#[test]
fn fifty_megapixels_admits_bounded_buffers_without_weakening_resident_gate() {
    let params = RuntimeParams::default();
    assert!(FilmOpticsPlan::new(&params, 20., 8192, 6144).is_err());
    let p = StreamPlan::new(&params, 20., 8192, 6144).unwrap();
    assert_eq!(p.device_bytes, 28 * spatial::MIB + 65536);
    assert_eq!(p.combined_peak_bytes, 260 * spatial::MIB + 65536);
    assert_eq!(p.disk_bytes, 8192 * 6144 * 68 + 65536);
    assert!(p.batch_bytes < 128 * spatial::MIB);
    let mut altered = p.clone();
    altered.batch_bytes += 4;
    assert!(altered.validate().is_err());
    let mut altered = p;
    altered.channels[0].bounce_weights.push(1.);
    assert!(altered.validate().is_err());
    for (w, h) in [(0, 2), (2, 0), (40001, 1), (u32::MAX, u32::MAX)] {
        assert!(StreamPlan::new(&params, 20., w, h).is_err());
    }
}

#[test]
fn full_lines_cover_edges_and_last_batch_without_halos_or_restarts() {
    for (w, h, cap) in [
        (1, 1, 1),
        (1, 39, 39),
        (39, 1, 39),
        (19, 37, 73),
        (8192, 6144, BATCH_SAMPLES),
    ] {
        for axis in [Axis::Rows, Axis::Columns] {
            let batches = batches(w, h, axis, cap).unwrap();
            let length = if axis == Axis::Rows { w } else { h };
            let lines = if axis == Axis::Rows { h } else { w };
            let mut next = 0;
            for b in batches {
                assert_eq!(b.first, next);
                assert_eq!(b.length, length);
                assert!(b.count > 0);
                assert!(b.count * b.length <= cap);
                next += b.count;
            }
            assert_eq!(next, lines);
        }
    }
    assert!(batches(100, 1, Axis::Rows, 99).is_err());
}

#[test]
fn byte_gather_scatter_graph_matches_resident_mirror_bitwise_across_batches() {
    for (w, h) in [(1, 9), (9, 1), (2, 3), (17, 29), (71, 37)] {
        for sigma in [0., 0.1, 0.5, 2.999, 3., 4., 7.] {
            let root = Root::new();
            let mut ch = channel(sigma);
            if w > 2 {
                ch.scatter = Some(spatial::Scatter {
                    sigma: 0.7,
                    decay: 2.,
                    amount: 0.13,
                    tail_weight: 0.4,
                });
                ch.bounce_sigmas = vec![2.999, 3., 4.];
                ch.bounce_weights = vec![0.5, 0.3, 0.2];
                ch.strength = 0.2;
                ch.divisor = 1.2;
            }
            let plan =
                StreamPlan::from_channels(w, h, [ch.clone(), ch.clone(), ch.clone()]).unwrap();
            let (inputs, outputs, source) = fixture(&root, w, h);
            let mut mirror = Mirror::default();
            let scratch = root.0.join("scratch");
            files::run(
                &plan,
                inputs.each_ref().map(|p| p.as_path()),
                outputs.each_ref().map(|p| p.as_path()),
                &scratch,
                w.max(h) * 2 + 3,
                &mut mirror,
                &mut || false,
                &mut |_, _| {},
            )
            .unwrap();
            let expected = spatial::channel_cpu_mirror(&source, w, h, &ch, &mut || false).unwrap();
            for output in &outputs {
                assert_eq!(
                    decoded(output)
                        .iter()
                        .map(|x| x.to_bits())
                        .collect::<Vec<_>>(),
                    expected.iter().map(|x| x.to_bits()).collect::<Vec<_>>(),
                    "{w}x{h}, sigma={sigma}"
                );
            }
            assert!(!scratch.exists());
            assert_eq!(
                mirror.metrics.sample_count,
                mirror.metrics.pass_count * (w + h) as u64
            );
        }
    }
}

#[test]
fn ordinary_failure_removes_private_candidates_without_touching_existing_outputs() {
    for fail in [false, true] {
        let root = Root::new();
        let (w, h) = (19, 31);
        let plan = StreamPlan::from_channels(w, h, std::array::from_fn(|_| channel(4.))).unwrap();
        let (inputs, outputs, _) = fixture(&root, w, h);
        let source_bytes = fs::read(&inputs[0]).unwrap();
        let scratch = root.0.join("scratch");
        let mut mirror = Mirror {
            failure_at: fail.then_some(35),
            ..Default::default()
        };
        let mut calls = 0;
        let result = files::run(
            &plan,
            inputs.each_ref().map(|p| p.as_path()),
            outputs.each_ref().map(|p| p.as_path()),
            &scratch,
            70,
            &mut mirror,
            &mut || {
                calls += 1;
                !fail && calls > 300
            },
            &mut |_, _| {},
        );
        assert!(result.is_err());
        assert!(outputs.iter().all(|p| !p.exists()));
        assert!(!scratch.exists());
        assert_eq!(fs::read(&inputs[0]).unwrap(), source_bytes);
        fs::write(&outputs[0], b"keep user file").unwrap();
        assert!(
            files::run(
                &plan,
                inputs.each_ref().map(|p| p.as_path()),
                outputs.each_ref().map(|p| p.as_path()),
                &scratch,
                70,
                &mut Mirror::default(),
                &mut || false,
                &mut |_, _| {}
            )
            .is_err()
        );
        assert_eq!(fs::read(&outputs[0]).unwrap(), b"keep user file");
    }
}

#[test]
fn actual_image_preflight_not_tiny_allocation_dimensions() {
    let plan = StreamPlan::from_channels(40000, 1, std::array::from_fn(|_| channel(128.))).unwrap();
    assert_eq!(
        plan.numerical_preflight(&mut || false).unwrap_err(),
        "GPU_OPTICS_NUMERIC_CHECK"
    );
}

#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "explicit real Vulkan probe; software results do not establish Adreno quality or speed"]
fn actual_vulkan_streamed_full_lines_match_mirror() {
    for sigma in [0.5, 2.999, 3., 4.] {
        let root = Root::new();
        let (w, h) = (71, 37);
        let mut ch = channel(sigma);
        ch.scatter = Some(spatial::Scatter {
            sigma: 0.7,
            decay: 1.2,
            amount: 0.13,
            tail_weight: 0.4,
        });
        ch.bounce_sigmas = vec![3., 4.];
        ch.bounce_weights = vec![0.6, 0.4];
        ch.strength = 0.2;
        ch.divisor = 1.2;
        let plan = StreamPlan::from_channels(w, h, std::array::from_fn(|_| ch.clone())).unwrap();
        plan.numerical_preflight(&mut || false).unwrap();
        let (inputs, outputs, source) = fixture(&root, w, h);
        let mut gpu = gpu::Gpu::new(&plan, true).unwrap();
        files::run(
            &plan,
            inputs.each_ref().map(|p| p.as_path()),
            outputs.each_ref().map(|p| p.as_path()),
            &root.0.join("scratch"),
            149,
            &mut gpu,
            &mut || false,
            &mut |_, _| {},
        )
        .unwrap();
        let reference = spatial::channel_cpu_mirror(&source, w, h, &ch, &mut || false).unwrap();
        for output in outputs {
            for (actual, expected) in decoded(&output).iter().zip(&reference) {
                assert!(
                    (actual - expected).abs() <= 2e-4 * expected.abs().max(1.),
                    "{actual} {expected}"
                );
            }
        }
        let metrics = gpu.metrics();
        assert_eq!(metrics.sample_count, metrics.pass_count * (w + h) as u64);
    }
}

#[cfg(feature = "gpu-diffusion-research")]
#[test]
#[ignore = "explicit full 8192/6144/40000-length Vulkan lines with forced batch boundaries"]
fn actual_vulkan_real_export_line_lengths_do_not_restart_recurrences() {
    for (w, h) in [(8192, 3), (3, 6144), (40000, 2)] {
        let root = Root::new();
        let ch = channel(4.);
        let plan = StreamPlan::from_channels(w, h, std::array::from_fn(|_| ch.clone())).unwrap();
        plan.numerical_preflight(&mut || false).unwrap();
        let (inputs, outputs, source) = fixture(&root, w, h);
        let mut gpu = gpu::Gpu::new(&plan, true).unwrap();
        files::run(
            &plan,
            inputs.each_ref().map(|p| p.as_path()),
            outputs.each_ref().map(|p| p.as_path()),
            &root.0.join("scratch"),
            w.max(h) + 1,
            &mut gpu,
            &mut || false,
            &mut |_, _| {},
        )
        .unwrap();
        let expected = spatial::channel_cpu_mirror(&source, w, h, &ch, &mut || false).unwrap();
        for path in outputs {
            for (a, e) in decoded(&path).iter().zip(&expected) {
                assert!((a - e).abs() <= 2e-4 * e.abs().max(1.), "{w}x{h}: {a} {e}");
            }
        }
    }
}

#[cfg(all(feature = "gpu-diffusion-research", feature = "precision-f64"))]
#[test]
#[ignore = "explicit original full grain comparison with real 16384-sample split and wrapped origins"]
fn actual_vulkan_streamed_coordinate_grain_preserves_full_graph() {
    use crate::research_grain::{GrainPlan, Layer, RngMode, guarded_cpu};
    use crate::research_streaming_grain::{StreamGrainPlan, streaming_sampler::StreamingSampler};
    let root = Root::new();
    let (w, h) = (129, 129);
    let mut p = GrainPlan {
        width: w,
        height: h,
        active: true,
        sublayers: true,
        positive: false,
        monochrome: false,
        seed: 42,
        origin: [u32::MAX - 63, u32::MAX - 63],
        rng_mode: RngMode::CoordinateSplitMix64,
        layers: std::array::from_fn(|_| vec![]),
        amount: 0.6,
        density_min: vec![0.03; 3],
        final_sigma: 0.65,
        micro_active: true,
        micro_spread: 1.3,
        micro_sigma_log: 0.12,
        micro_mu: -0.5 * 0.12 * 0.12,
    };
    for ch in 0..3 {
        for sl in 0..3 {
            let f = (sl + 1) as f64 / 6.;
            p.layers[ch].push(Layer {
                seed: 42 + ch as u64 + sl as u64 * 10,
                density_min: 0.03 * f,
                density_max: 2.43 * f,
                particles: [2.3, 57., 1700.][sl],
                uniformity: 0.97,
                axis: vec![0., 0.2, 0.7, 1.7, 2.4],
                values: vec![0., 0.2 * f, 0.7 * f, 1.7 * f, 2.4 * f],
                cloud_sigma: 0.6 + sl as f32 * 1.1,
            });
        }
    }
    let plan = StreamGrainPlan::from_plan(p.clone()).unwrap();
    let (inputs, outputs, _) = fixture(&root, w, h);
    let source: Vec<f64> = (0..w * h)
        .map(|i| 0.1 + ((i * 37) % 211) as f64 / 100.)
        .collect();
    let bytes: Vec<u8> = source.iter().flat_map(|x| x.to_le_bytes()).collect();
    for path in &inputs {
        fs::write(path, &bytes).unwrap();
    }
    let input = std::array::from_fn(|_| source.clone());
    let reference = guarded_cpu::mirror(&p, &input, false, &mut || false).unwrap();
    use crate::{gpu_session as session, gpu_session_contract::StageTicket};
    let memory = plan.memory().unwrap();
    let epoch = 9001;
    let ticket = StageTicket {
        epoch,
        generation: 1,
        sequence: 1,
    };
    session::activate(
        epoch,
        memory.combined_peak_bytes,
        memory.host_bytes + memory.helper_allowance_bytes,
        8,
    )
    .unwrap();
    session::begin_frame(epoch, 1).unwrap();
    session::begin_stage(ticket, memory.driver_allowance_bytes).unwrap();
    let mut sampler = StreamingSampler::new_probe(&plan, &mut || false).unwrap();
    files::run_grain(
        &plan,
        inputs.each_ref().map(|p| p.as_path()),
        outputs.each_ref().map(|p| p.as_path()),
        &root.0.join("scratch"),
        521,
        &mut sampler,
        &mut || false,
        &mut |_, _| {},
    )
    .unwrap();
    let mut maximum = 0f64;
    let mut sum = 0.;
    let mut count = 0;
    for (path, reference) in outputs.iter().zip(&reference) {
        for (actual, expected) in decoded(path).iter().zip(reference) {
            let error = (actual - expected).abs();
            maximum = maximum.max(error);
            sum += error * error;
            count += 1;
        }
    }
    assert!(
        maximum <= 5e-4 && (sum / count as f64).sqrt() <= 1e-4,
        "max={maximum}, squared={sum}, n={count}"
    );
    assert_eq!(Compute::metrics(&mut sampler).pass_count, p.pass_count());
    assert_eq!(
        Compute::metrics(&mut sampler).sample_count,
        p.pass_count() * (w + h) as u64
    );
    let report = sampler.report().unwrap();
    assert!(report["dispatches"].as_u64().unwrap() > 0);
    drop(sampler);
    let stats = session::complete_stage(ticket).unwrap();
    assert!(stats.charged_bytes <= memory.combined_peak_bytes);
    assert_eq!(stats.pipeline_slots, 8);
    session::finish_frame(epoch, 1).unwrap();
    assert!(!root.0.join("scratch").exists());
    for path in &inputs {
        assert_eq!(fs::read(path).unwrap(), bytes);
    }
}
