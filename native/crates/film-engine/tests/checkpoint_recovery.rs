//! Synthetic-only host tests. The parent kills a separate worker process, not a
//! cancellation token: destructors cannot rescue the checkpoint under test.
use film_engine::{
    checkpoint::{Checkpoints, Identity},
    streamed,
    streamed_progress::ExportProgress,
};
use spektrafilm_core::params::RuntimeParams;
use std::{
    fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
};
const W: u32 = 37;
const H: u32 = 29;
fn data() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")
}
fn params() -> RuntimeParams {
    let mut p = RuntimeParams::default();
    p.io.input_cctf_decoding = false;
    p.io.output_cctf_encoding = false;
    p.camera.auto_exposure = true;
    p.film_render.grain.seed = 12345;
    p
}
fn render(
    root: &Path,
    checkpoint: bool,
    mut event: impl FnMut(ExportProgress),
) -> film_engine::mobile::Result<streamed::StreamedMetrics> {
    streamed::export_file_checkpointed(
        &data(),
        "kodak_portra_400",
        "kodak_portra_endura",
        params(),
        &root.join("input.rgb"),
        &root.join("output.rgb"),
        &root.join("scratch"),
        checkpoint.then(|| root.join("checkpoint")).as_deref(),
        W,
        H,
        384,
        0.,
        false,
        || false,
        |e| event(e),
    )
}
struct Temp(PathBuf);
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn root(label: &str) -> Temp {
    let p = std::env::temp_dir().join(format!("checkpoint-{label}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&p);
    fs::create_dir(&p).unwrap();
    fs::write(
        p.join("input.rgb"),
        (0..W * H * 3)
            .flat_map(|i| ((i % 107) as f32 / 107.).to_le_bytes())
            .collect::<Vec<_>>(),
    )
    .unwrap();
    Temp(p)
}
#[test]
fn subprocess_worker() {
    let Ok(path) = std::env::var("FILMROOM_CHECKPOINT_TEST_ROOT") else {
        return;
    };
    let root = Path::new(&path);
    let stop: u8 = std::env::var("FILMROOM_CHECKPOINT_STOP")
        .unwrap()
        .parse()
        .unwrap();
    let result = render(root, true, |_| {
        let stage = fs::read(root.join("checkpoint/manifest.json"))
            .ok()
            .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
            .and_then(|v| v["stage"].as_u64())
            .unwrap_or(0);
        if stage == stop as u64 {
            fs::write(root.join("ready"), b"committed").unwrap();
            loop {
                std::thread::sleep(Duration::from_secs(1));
            }
        }
    });
    result.unwrap();
}
#[test]
fn real_sigkill_at_each_committed_boundary_resumes_with_exact_seed_parity() {
    let r = root("kill");
    render(&r.0, false, |_| {}).unwrap();
    let baseline = fs::read(r.0.join("output.rgb")).unwrap();
    fs::remove_file(r.0.join("output.rgb")).unwrap();
    for stage in 2..=6 {
        let mut child = Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "subprocess_worker", "--nocapture"])
            .env("FILMROOM_CHECKPOINT_TEST_ROOT", &r.0)
            .env("FILMROOM_CHECKPOINT_STOP", stage.to_string())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap();
        let started = Instant::now();
        while !r.0.join("ready").exists() {
            if let Some(status) = child.try_wait().unwrap() {
                panic!("worker exited before checkpoint {stage}: {status}");
            }
            if started.elapsed() > Duration::from_secs(180) {
                let _ = child.kill();
                panic!("checkpoint worker timed out");
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        child.kill().unwrap();
        let status = child.wait().unwrap();
        assert!(!status.success());
        assert!(r.0.join("checkpoint/manifest.json").is_file());
        fs::remove_file(r.0.join("ready")).unwrap();
        let _ = fs::remove_dir_all(r.0.join("scratch"));
        let _ = fs::remove_file(r.0.join("output.rgb"));
    }
    let mut events = Vec::new();
    let metrics = render(&r.0, true, |e| events.push(e)).unwrap();
    assert!(
        metrics.stages.is_empty(),
        "stage 6 restore must not rerun rendering"
    );
    assert!(events
        .iter()
        .all(|e| e.display_stage == 0 || e.display_stage == 1 || e.display_stage == 6));
    assert_eq!(fs::read(r.0.join("output.rgb")).unwrap(), baseline);
    // Cancellation is an owner operation: deleting this one job removes every
    // durable artifact and cannot affect the shared bundled profile directory.
    fs::remove_dir_all(&r.0).unwrap();
    assert!(!r.0.exists());
    assert!(data().is_dir());
}
fn identity(root: &Path) -> Identity {
    Identity::new(
        &data(),
        &root.join("input.rgb"),
        W,
        H,
        "parameters-seed-12345".into(),
        &mut || false,
    )
    .unwrap()
}
fn planes(root: &Path) -> [PathBuf; 3] {
    std::array::from_fn(|i| {
        let p = root.join(format!("plane-{i}"));
        fs::write(&p, vec![0u8; W as usize * H as usize * 8]).unwrap();
        p
    })
}
#[test]
fn interrupted_commit_preserves_previous_and_discards_partial_generation() {
    let r = root("interrupted");
    let paths = planes(&r.0);
    let id = identity(&r.0);
    let mut c = Checkpoints::open(&r.0.join("checkpoint"), id.clone(), &mut || false).unwrap();
    c.commit(2, &paths, Some(0.5), &mut || false).unwrap();
    let previous = fs::read(r.0.join("checkpoint/manifest.json")).unwrap();
    let mut calls = 0;
    assert!(c
        .commit(3, &paths, Some(0.5), &mut || {
            calls += 1;
            calls > 1
        })
        .is_err());
    assert_eq!(
        previous,
        fs::read(r.0.join("checkpoint/manifest.json")).unwrap()
    );
    fs::write(r.0.join("checkpoint/manifest.tmp"), b"{torn").unwrap();
    let c = Checkpoints::open(&r.0.join("checkpoint"), id, &mut || false).unwrap();
    assert_eq!(c.stage(), 2);
    assert!(!r.0.join("checkpoint/pending").exists());
    assert!(!r.0.join("checkpoint/manifest.tmp").exists());
    let dest = std::array::from_fn::<_, 3, _>(|i| r.0.join(format!("restored-{i}")));
    c.restore(&dest, &mut || false).unwrap();
    fs::write(&dest[0], b"mutated").unwrap();
    assert_eq!(
        fs::metadata(r.0.join("checkpoint/stage-2/0.bin"))
            .unwrap()
            .len(),
        W as u64 * H as u64 * 8
    );
}
#[test]
fn corrupt_plane_or_torn_committed_manifest_is_rejected_not_silently_restarted() {
    let r = root("corrupt");
    let id = identity(&r.0);
    let mut c = Checkpoints::open(&r.0.join("checkpoint"), id.clone(), &mut || false).unwrap();
    c.commit(2, &planes(&r.0), None, &mut || false).unwrap();
    let p = r.0.join("checkpoint/stage-2/0.bin");
    let mut b = fs::read(&p).unwrap();
    b[0] = 1;
    fs::write(&p, &b).unwrap();
    assert!(Checkpoints::open(&r.0.join("checkpoint"), id.clone(), &mut || false).is_err());
    fs::write(r.0.join("checkpoint/manifest.json"), b"{torn").unwrap();
    assert!(Checkpoints::open(&r.0.join("checkpoint"), id, &mut || false).is_err());
}
#[test]
fn changed_source_configuration_and_dimensions_are_rejected() {
    let r = root("identity");
    let mut c = Checkpoints::open(&r.0.join("checkpoint"), identity(&r.0), &mut || false).unwrap();
    c.commit(2, &planes(&r.0), None, &mut || false).unwrap();
    let different = Identity::new(
        &data(),
        &r.0.join("input.rgb"),
        W,
        H,
        "different-seed".into(),
        &mut || false,
    )
    .unwrap();
    assert!(Checkpoints::open(&r.0.join("checkpoint"), different, &mut || false).is_err());
    let input = r.0.join("input.rgb");
    let mut bytes = fs::read(&input).unwrap();
    bytes[0] ^= 1;
    fs::write(&input, bytes).unwrap();
    assert!(Checkpoints::open(&r.0.join("checkpoint"), identity(&r.0), &mut || false).is_err());
    assert!(Identity::new(
        &data(),
        &input,
        W + 1,
        H,
        "parameters-seed-12345".into(),
        &mut || false
    )
    .is_err());
}

#[test]
fn changed_profile_bytes_and_symlink_plane_are_rejected() {
    let r = root("profiles");
    let assets = r.0.join("assets");
    fs::create_dir(&assets).unwrap();
    fs::write(assets.join("profile.json"), b"synthetic profile v1").unwrap();
    let make = || {
        Identity::new(
            &assets,
            &r.0.join("input.rgb"),
            W,
            H,
            "seed42".into(),
            &mut || false,
        )
        .unwrap()
    };
    let id = make();
    let dir = r.0.join("checkpoint");
    let mut c = Checkpoints::open(&dir, id.clone(), &mut || false).unwrap();
    let sources = planes(&r.0);
    c.commit(2, &sources, None, &mut || false).unwrap();
    fs::write(assets.join("profile.json"), b"synthetic profile v2").unwrap();
    assert!(Checkpoints::open(&dir, make(), &mut || false).is_err());
    #[cfg(unix)]
    {
        fs::remove_file(dir.join("stage-2/0.bin")).unwrap();
        std::os::unix::fs::symlink(&sources[0], dir.join("stage-2/0.bin")).unwrap();
        assert!(Checkpoints::open(&dir, id, &mut || false).is_err());
    }
}
