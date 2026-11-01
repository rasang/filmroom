//! Real execution progress: no received-event counting and no elapsed-time estimate.
use film_engine::{streamed, streamed_progress::ExportProgress};
use spektrafilm_core::params::RuntimeParams;
use std::{
    fs,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
static NEXT: AtomicU64 = AtomicU64::new(0);
struct Temp(PathBuf);
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn run(mut p: RuntimeParams, cancel_phase: Option<&str>) -> (Vec<ExportProgress>, bool) {
    let root = std::env::temp_dir().join(format!(
        "film-stage-progress-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    fs::create_dir(&root).unwrap();
    let _guard = Temp(root.clone());
    let (w, h) = (67, 47);
    let input = root.join("input");
    let output = root.join("output");
    let scratch = root.join("scratch");
    let bytes: Vec<u8> = (0..w * h * 3)
        .flat_map(|i| ((i % 101) as f32 / 100.).to_le_bytes())
        .collect();
    fs::write(&input, &bytes).unwrap();
    p.io.input_cctf_decoding = false;
    p.io.output_cctf_encoding = false;
    let mut events = Vec::new();
    let stop = std::cell::Cell::new(false);
    let result = streamed::export_file_with_progress(
        &Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data"),
        "kodak_portra_400",
        "kodak_portra_endura",
        p,
        &input,
        &output,
        &scratch,
        w,
        h,
        384,
        0.,
        false,
        || stop.get(),
        |e| {
            events.push(e);
            if cancel_phase == Some(e.phase) && e.done > 0 {
                stop.set(true);
            }
        },
    );
    if cancel_phase.is_some() {
        assert_eq!(result.unwrap_err().code, "CANCELLED");
        assert!(!output.exists());
    } else {
        result.unwrap();
        assert_eq!(fs::metadata(&output).unwrap().len(), bytes.len() as u64);
    }
    assert!(!scratch.exists());
    assert_eq!(fs::read(input).unwrap(), bytes);
    (events, stop.get())
}
fn assert_aggregated(events: &[ExportProgress]) {
    for pair in events.windows(2) {
        assert!(
            pair[1].display_stage >= pair[0].display_stage,
            "stage went backwards: {pair:?}"
        );
        if pair[0].display_stage == pair[1].display_stage {
            assert_eq!(pair[0].stage_total, pair[1].stage_total);
            assert!(
                pair[1].stage_done >= pair[0].stage_done,
                "work went backwards: {pair:?}"
            );
        }
    }
    for stage in 2..=6 {
        let group: Vec<_> = events.iter().filter(|e| e.display_stage == stage).collect();
        if group.is_empty() {
            continue;
        }
        let last = group.last().unwrap();
        assert!(last.stage_total > 0);
        assert_eq!(
            last.stage_done, last.stage_total,
            "stage {stage} did not finish its real planned work"
        );
        assert!(
            group[..group.len() - 1]
                .iter()
                .all(|e| e.stage_done < e.stage_total),
            "stage {stage} reached 100% before finalization"
        );
    }
    assert_eq!(events.last().unwrap().display_stage, 6); // Native cleanup is not the app's final step8.
}
#[test]
fn default_nine_layers_cloud_passes_form_one_monotonic_stage() {
    let mut p = RuntimeParams::default();
    p.camera.pixel_size_um_override = Some(1.);
    p.film_render.grain.blur_dye_clouds_um = 2.;
    p.film_render.grain.blur = 0.8;
    let (events, _) = run(p, None);
    assert_aggregated(&events);
    let grain: Vec<_> = events.iter().filter(|e| e.display_stage == 5).collect();
    assert_eq!(
        grain
            .iter()
            .filter(|e| e.phase == "grain_sampling" && e.total == 0)
            .count(),
        10
    ); // stage boundary +9 actual layers
    assert_eq!(
        grain
            .iter()
            .filter(|e| e.phase == "grain_cloud" && e.total == 0)
            .count(),
        9
    );
    assert!(grain
        .iter()
        .any(|e| e.phase == "grain_cloud" && e.total == 47));
    assert!(grain
        .iter()
        .any(|e| e.phase == "grain_cloud" && e.total == 67));
}
#[test]
fn optional_work_and_direct_scan_keep_stable_numbering() {
    let mut p = RuntimeParams::default();
    p.camera.auto_exposure = false;
    p.film_render.halation.active = false;
    p.film_render.halation.boost_ev = 0.;
    p.film_render.grain.active = false;
    p.film_render.dir_couplers.active = false;
    p.io.scan_film = true;
    p.scanner.lens_blur = 0.;
    p.scanner.unsharp_mask = [0., 0.];
    let (events, _) = run(p, None);
    assert_aggregated(&events);
    assert!(!events
        .iter()
        .any(|e| e.display_stage == 3 || e.display_stage == 5));
    assert!(events.iter().any(|e| e.display_stage == 4));
}
#[test]
fn alternate_grain_and_all_spatial_options_finish_their_planned_work() {
    for layered in [false, true] {
        let mut p = RuntimeParams::default();
        p.camera.pixel_size_um_override = Some(1.);
        p.camera.lens_blur_um = 2.;
        p.camera.diffusion_filter.active = true;
        p.enlarger.diffusion_filter.active = true;
        p.film_render.halation.boost_ev = 1.;
        p.film_render.grain.sublayers_active = layered;
        p.film_render.grain.n_sub_layers = 4;
        p.film_render.grain.micro_structure = [2., 1000.];
        p.film_render.grain.blur = 0.8;
        p.scanner.lens_blur = 0.5;
        p.scanner.unsharp_mask = [0.7, 1.];
        p.print_render.glare.active = true;
        p.print_render.glare.percent = 1.;
        p.print_render.glare.blur = 0.8;
        let (events, _) = run(p, None);
        assert_aggregated(&events);
        if !layered {
            assert_eq!(
                events
                    .iter()
                    .filter(|e| e.display_stage == 5 && e.phase == "grain_sampling" && e.total == 0)
                    .count(),
                13
            );
            assert!(!events.iter().any(|e| e.phase == "grain_microstructure"));
        } else {
            assert!(events.iter().any(|e| e.phase == "grain_microstructure"));
        }
    }
}
#[test]
fn cancellation_does_not_publish_completed_grain() {
    let (events, stopped) = run(RuntimeParams::default(), Some("grain_sampling"));
    assert!(stopped);
    let grain: Vec<_> = events.iter().filter(|e| e.display_stage == 5).collect();
    assert!(!grain.is_empty());
    assert!(grain.iter().all(|e| e.stage_done < e.stage_total));
}
