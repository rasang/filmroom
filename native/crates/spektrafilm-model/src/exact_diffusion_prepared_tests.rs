use super::*;
use std::time::{Duration, Instant};

const FAMILIES: [&str; 4] = ["glimmerglass", "black_pro_mist", "pro_mist", "cinebloom"];

fn config(family: &'static str) -> DiffusionFilter<'static> {
    DiffusionFilter {
        family, strength: 0.24, spatial_scale: 1.0, halo_warmth: 0.0,
        core_intensity: 1.0, core_size: 1.0, halo_intensity: 1.0, halo_size: 1.0,
        bloom_intensity: 1.0, bloom_size: 1.0,
    }
}

fn kernel(df: &DiffusionFilter, pitch: f64, width: usize, height: usize, prepared: bool) -> ExactDiffusionKernel {
    ExactDiffusionKernel::new_cancellable_with_prepared(df, pitch, width, height, prepared, || false)
        .unwrap().unwrap()
}

fn assert_rows_equal(legacy: &ExactDiffusionKernel, prepared: &ExactDiffusionKernel) {
    assert_eq!(legacy.descriptor(), prepared.descriptor(), "normalization and scientific identity");
    assert!(legacy.prepared.is_none());
    let constants = prepared.prepared.as_ref().unwrap();
    assert!(constants.storage_bytes() < 1024);
    for channel in 0..3 {
        // Non-sequential requests establish that there is no cache/order dependency.
        for y in (0..legacy.side()).rev() {
            let expected = legacy.row(channel, y);
            let actual = prepared.row(channel, y);
            assert_eq!(actual.capacity(), prepared.side(), "one-row output bound");
            for (x, (a, b)) in expected.iter().zip(&actual).enumerate() {
                assert_eq!(a.to_bits(), b.to_bits(), "channel={channel} y={y} x={x}");
            }
        }
    }
}

#[test]
fn prepared_full_matrix_is_bitwise_legacy_across_families_and_controls() {
    for family in FAMILIES {
        for variant in 0..14 {
            let mut df = config(family);
            let pitch = match variant % 3 { 0 => 0.7, 1 => 5.833333492279053, _ => 3000.0 };
            match variant {
                0 => (),
                1 => df.spatial_scale = 0.00000001,
                2 => df.spatial_scale = 2.75,
                3 => df.halo_warmth = -20.0,
                4 => df.halo_warmth = 20.0,
                5 => df.core_intensity = 0.0,
                6 => df.halo_intensity = 0.0,
                7 => df.bloom_intensity = 0.0,
                8 => df.core_size = 0.0,
                9 => df.halo_size = 0.00000001,
                10 => df.bloom_size = 2.25,
                11 => { df.core_intensity = 0.0; df.halo_intensity = 0.0; df.bloom_intensity = 0.0; }
                12 => {
                    df.core_intensity = 0.37; df.halo_intensity = 1.7; df.bloom_intensity = 2.4;
                    df.core_size = 0.4; df.halo_size = 1.25; df.bloom_size = 1.9;
                    df.halo_warmth = -0.42;
                }
                _ => { df.strength = 2.7; df.spatial_scale = 0.03125; }
            }
            for (width, height) in [(1, 7), (18, 14), (47, 29), (83, 74)] {
                let legacy = kernel(&df, pitch, width, height, false);
                let prepared = kernel(&df, pitch, width, height, true);
                assert_rows_equal(&legacy, &prepared);
                // Also compare the untouched full-image reference, not only the
                // row-generator implementation used by the streamed baseline.
                let full = diffusion_filter_psf(legacy.side(), &legacy.cfg,
                    df.spatial_scale, pitch, df.halo_warmth);
                for channel in 0..3 {
                    for y in 0..legacy.side() {
                        for (x, value) in prepared.row(channel, y).iter().enumerate() {
                            assert_eq!(value.to_bits(), full[channel][y * legacy.side() + x].to_bits(),
                                "full PSF family={family} variant={variant} channel={channel} y={y} x={x}");
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn prepared_negative_and_nonfinite_public_controls_match_legacy() {
    // The engine validates finite controls, but this public model API is also
    // used directly. Preserve its historical handling rather than imposing new
    // validation or assuming all callers arrive through the engine.
    for family in FAMILIES {
        for field in 0..9 {
            for edge in [-1.25, f64::NEG_INFINITY, 0.0, f64::INFINITY, f64::NAN] {
                let mut df = config(family);
                match field {
                    0 => df.strength = edge,
                    1 => df.spatial_scale = edge,
                    2 => df.halo_warmth = edge,
                    3 => df.core_intensity = edge,
                    4 => df.core_size = edge,
                    5 => df.halo_intensity = edge,
                    6 => df.halo_size = edge,
                    7 => df.bloom_intensity = edge,
                    _ => df.bloom_size = edge,
                }
                let legacy = ExactDiffusionKernel::new_cancellable_with_prepared(
                    &df, 5.833333492279053, 19, 15, false, || false);
                let prepared = ExactDiffusionKernel::new_cancellable_with_prepared(
                    &df, 5.833333492279053, 19, 15, true, || false);
                match (legacy, prepared) {
                    (Ok(None), Ok(None)) => (),
                    (Ok(Some(legacy)), Ok(Some(prepared))) => {
                        assert_rows_equal(&legacy, &prepared);
                        let full = diffusion_filter_psf(legacy.side(), &legacy.cfg,
                            df.spatial_scale, 5.833333492279053, df.halo_warmth);
                        for channel in 0..3 {
                            for y in 0..legacy.side() {
                                for (x, value) in prepared.row(channel, y).iter().enumerate() {
                                    assert_eq!(value.to_bits(), full[channel][y * legacy.side() + x].to_bits(),
                                        "edge family={family} field={field} value={edge} channel={channel} y={y} x={x}");
                                }
                            }
                        }
                    }
                    (Err(a), Err(b)) => assert_eq!(a, b),
                    _ => panic!("constructor outcome differs: family={family} field={field} value={edge}"),
                }
            }
        }
    }
}

#[test]
fn prepared_cancellation_matches_legacy_row_boundaries() {
    for prepared in [false, true] {
        for cancel_at in [1, 2, 7, 13] {
            let mut calls = 0;
            let result = ExactDiffusionKernel::new_cancellable_with_prepared(
                &config("cinebloom"), 5.833333492279053, 4000, 6000, prepared,
                || { calls += 1; calls == cancel_at });
            assert!(matches!(result, Err("CANCELLED")));
            assert_eq!(calls, cancel_at);
        }
        let mut calls = 0;
        let result = ExactDiffusionKernel::new_cancellable_with_prepared(
            &config("cinebloom"), 10.0, 18, 14, prepared,
            || { calls += 1; false }).unwrap().unwrap();
        assert_eq!(calls, result.side());
    }
}

#[test]
fn prepared_no_op_validation_matches_legacy() {
    for prepared in [false, true] {
        for variant in 0..9 {
            let mut df = config("cinebloom");
            let (mut width, mut height, mut pitch) = (10, 10, 5.0);
            match variant {
                0 => df.family = "unknown",
                1 => df.strength = 0.0,
                2 => df.spatial_scale = 0.0,
                3 => width = 0,
                4 => height = 0,
                5 => pitch = 0.0,
                6 => pitch = -1.0,
                7 => pitch = f64::INFINITY,
                _ => pitch = f64::NAN,
            }
            let result = ExactDiffusionKernel::new_cancellable_with_prepared(
                &df, pitch, width, height, prepared, || panic!("no-op must not enter row loop"));
            assert!(matches!(result, Ok(None)));
        }
    }
}

#[test]
fn prepared_storage_is_rows_not_kernel_area_and_is_thread_safe() {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<ExactDiffusionKernel>();
    // Stop before normalization: the bound can be verified for the actual
    // 3999-square geometry without allocating an area-sized reference kernel.
    let mut calls = 0;
    let result = ExactDiffusionKernel::new_cancellable_with_prepared(
        &config("cinebloom"), 5.833333492279053, 4000, 6000, true,
        || { calls += 1; true });
    assert!(matches!(result, Err("CANCELLED")));
    assert_eq!(calls, 1);
    let df = config("cinebloom");
    let prepared = kernel(&df, 10.0, 83, 74, true);
    let constants = prepared.prepared.as_ref().unwrap();
    assert!(constants.storage_bytes() < 1024);
    let rows = prepared.prepared_raw_rows(constants, 0);
    assert_eq!(rows.iter().map(|row| row.capacity() * 8).sum::<usize>(), 3 * prepared.side() * 8);
    std::thread::scope(|scope| {
        for ch in 0..3 {
            let prepared = &prepared;
            scope.spawn(move || assert_eq!(prepared.row(ch, 1), prepared.row(ch, prepared.side() - 2)));
        }
    });
}

/// No FFTs, scratch files, large matrices, hashing, or image IO. Every one of
/// the 47,976,003 RGB coefficients is compared by its f64 bits outside timing.
/// Optional FILMROOM_DIFFUSION_BENCH_RUNS allows paired repeated measurements.
#[test]
#[ignore = "representative 4000x6000 kernel-only timing and full bitwise comparison"]
fn benchmark_prepared_cinebloom_radius_1999() {
    let runs = std::env::var("FILMROOM_DIFFUSION_BENCH_RUNS")
        .ok().and_then(|v| v.parse::<usize>().ok()).unwrap_or(1).max(1);
    let df = config("cinebloom");
    let mut timings = Vec::new();
    for run in 0..runs {
        let mut kernels = [None, None];
        let mut normalization = [Duration::ZERO; 2];
        for mode in if run % 2 == 0 { [0, 1] } else { [1, 0] } {
            let start = Instant::now();
            kernels[mode] = Some(kernel(&df, 5.833333492279053, 4000, 6000, mode == 1));
            normalization[mode] = start.elapsed();
        }
        let legacy = kernels[0].as_ref().unwrap();
        let prepared = kernels[1].as_ref().unwrap();
        assert_eq!(legacy.radius, 1999);
        assert_eq!(legacy.descriptor(), prepared.descriptor());
        let constants_bytes = prepared.prepared.as_ref().unwrap().storage_bytes();
        let normalization_row_bytes = 3 * prepared.side() * std::mem::size_of::<f64>();
        assert!(constants_bytes < 1024);
        assert_eq!(normalization_row_bytes, 95_976);
        let mut generation = [Duration::ZERO; 2];
        for channel in 0..3 {
            for y in 0..legacy.side() {
                let mut rows = [Vec::new(), Vec::new()];
                for mode in if (run + y) % 2 == 0 { [0, 1] } else { [1, 0] } {
                    let start = Instant::now();
                    rows[mode] = kernels[mode].as_ref().unwrap().row(channel, y);
                    generation[mode] += start.elapsed();
                }
                for x in 0..legacy.side() {
                    assert_eq!(rows[0][x].to_bits(), rows[1][x].to_bits(),
                        "representative channel={channel} y={y} x={x}");
                }
            }
        }
        let total = [normalization[0] + generation[0], normalization[1] + generation[1]];
        println!("EXACT_DIFFUSION_KERNEL run={} radius={} side={} compared_values={} legacy_normalization_ms={:.3} prepared_normalization_ms={:.3} legacy_rgb_rows_ms={:.3} prepared_rgb_rows_ms={:.3} legacy_total_ms={:.3} prepared_total_ms={:.3} speedup={:.3} prepared_constants_bytes={} prepared_normalization_rows_bytes={} prepared_output_row_bytes={} parity=bitwise",
            run + 1, legacy.radius, legacy.side(), 3 * legacy.side() * legacy.side(),
            normalization[0].as_secs_f64() * 1000.0, normalization[1].as_secs_f64() * 1000.0,
            generation[0].as_secs_f64() * 1000.0, generation[1].as_secs_f64() * 1000.0,
            total[0].as_secs_f64() * 1000.0, total[1].as_secs_f64() * 1000.0,
            total[0].as_secs_f64() / total[1].as_secs_f64(), constants_bytes,
            normalization_row_bytes, legacy.side() * 8);
        timings.push([total[0].as_secs_f64(), total[1].as_secs_f64()]);
    }
    if runs > 1 {
        let mut legacy: Vec<_> = timings.iter().map(|t| t[0]).collect();
        let mut prepared: Vec<_> = timings.iter().map(|t| t[1]).collect();
        legacy.sort_by(f64::total_cmp);
        prepared.sort_by(f64::total_cmp);
        println!("EXACT_DIFFUSION_KERNEL_MEDIAN runs={runs} legacy_ms={:.3} prepared_ms={:.3} speedup={:.3}",
            legacy[runs / 2] * 1000.0, prepared[runs / 2] * 1000.0, legacy[runs / 2] / prepared[runs / 2]);
    }
}
