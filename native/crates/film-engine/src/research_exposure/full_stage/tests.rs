use super::*;
use crate::research_exposure::{streaming, tests::fixture};
struct Fixture {
    root: PathBuf,
    source: PathBuf,
    dir: PathBuf,
    descriptor: ExposureDescriptor,
    plan: ExposurePlan,
}
impl Fixture {
    fn new(w: u32, h: u32) -> Self {
        let root = std::env::temp_dir().join(format!(
            "exposure-packet-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&root).unwrap();
        let root = root.canonicalize().unwrap();
        let source = root.join("original");
        fs::write(
            &source,
            (0..w as usize * h as usize)
                .flat_map(|i| {
                    [i as f32 / 9000., 0.3, (i % 7) as f32 / 3.]
                        .into_iter()
                        .flat_map(f32::to_le_bytes)
                })
                .collect::<Vec<_>>(),
        )
        .unwrap();
        let dir = root.join("gpu-stage-17");
        let descriptor = fixture(17);
        let plan = prepare_stage(&source, &dir, 17, w, h, &descriptor, || false).unwrap();
        Self {
            root,
            source,
            dir,
            descriptor,
            plan,
        }
    }
    fn mirror(&self) -> SamplesResult {
        streaming::write_candidate(
            &self.dir,
            &self.plan,
            &self.descriptor,
            &mut || false,
            |bytes, _, _| {
                Ok(bytes
                    .chunks_exact(12)
                    .flat_map(|px| {
                        self.descriptor.emulate_f32(std::array::from_fn(|ch| {
                            f32::from_le_bytes(px[ch * 4..ch * 4 + 4].try_into().unwrap())
                        }))
                    })
                    .collect())
            },
        )
    }
}
type SamplesResult = R<streaming::Samples>;
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}
#[test]
fn bounded_plan_and_exact_descriptor_roundtrip() {
    let f = Fixture::new(257, 257);
    assert_eq!(f.plan, plan_stage(&f.dir, 17).unwrap());
    assert_eq!(f.plan.chunk_pixels, 65536);
    assert_eq!(f.plan.chunk_count, 2);
    assert_eq!(f.plan.device_bytes, 4 * 17 * 17 * 3 + 36 * 65536 + 4096 + 8);
    assert_eq!(
        f.plan.combined_peak_bytes,
        f.plan.device_bytes + f.plan.host_bytes + 128 * MIB
    );
    assert_eq!(f.plan.disk_bytes, 36 * 257 * 257 + 8 * 17 * 17 * 3 + 65536);
    let (m, p) = load(&f.dir, 17).unwrap();
    let d = read_descriptor(&f.dir, &m, &p, &mut || false).unwrap();
    assert_eq!(d.table, f.descriptor.table);
    assert_eq!(
        d.rgb_to_xyz.map(|r| r.map(f64::to_bits)),
        f.descriptor.rgb_to_xyz.map(|r| r.map(f64::to_bits))
    );
    assert_eq!(
        d.adaptation.map(|r| r.map(f64::to_bits)),
        f.descriptor.adaptation.map(|r| r.map(f64::to_bits))
    );
    assert_eq!(
        d.auto_exposure.to_bits(),
        f.descriptor.auto_exposure.to_bits()
    );
    assert_eq!(d.exposure.to_bits(), f.descriptor.exposure.to_bits());
    assert!(estimated_disk_bytes(0, 1, &d).is_err());
    assert!(estimated_disk_bytes(40001, 1, &d).is_err());
    let large = make_plan(
        &manifest(&fixture(1024), 1, 40000, 40000).unwrap(),
        "0".repeat(64),
    )
    .unwrap();
    assert!(large.combined_peak_bytes < MEMORY_CAP);
    assert_eq!(large.chunk_pixels, 65536);
}
#[test]
fn exact_atomic_output_all_planes_and_tail_coverage() {
    for (w, h) in [(1, 1), (1, 7), (257, 257)] {
        let f = Fixture::new(w, h);
        let original = fs::read(&f.source).unwrap();
        let stats = f.mirror().unwrap();
        let n = w as u64 * h as u64;
        let last = n - (f.plan.chunk_count - 1) * f.plan.chunk_pixels as u64;
        assert_eq!(
            stats.count,
            3 * ((f.plan.chunk_count - 1) * 8 + last.min(8))
        );
        assert!(stats.rms().is_finite());
        assert!(!f.dir.join(".candidate.partial").exists());
        let paths = validate_outputs(&f.dir, 17, &f.plan.manifest_sha256, || false).unwrap();
        for ch in 0..3 {
            let actual = fs::read(&paths[ch]).unwrap();
            assert_eq!(actual.len(), n as usize * 8);
            for (i, b) in actual.chunks_exact(8).enumerate() {
                let px = std::array::from_fn(|c| {
                    f32::from_le_bytes(
                        original[i * 12 + c * 4..i * 12 + c * 4 + 4]
                            .try_into()
                            .unwrap(),
                    )
                });
                assert_eq!(
                    f64::from_le_bytes(b.try_into().unwrap()),
                    f.descriptor.emulate_f32(px)[ch] as f64
                );
            }
        }
        assert_eq!(fs::read(&f.source).unwrap(), original);
        assert!(f.mirror().is_err());
        assert_eq!(
            validate_outputs(&f.dir, 17, &"0".repeat(64), || false).unwrap_err(),
            "STALE_EXPOSURE_PLAN"
        );
        let mut corrupt = fs::read(&paths[2]).unwrap();
        corrupt[..8].copy_from_slice(&f64::NAN.to_le_bytes());
        fs::write(&paths[2], corrupt).unwrap();
        assert_eq!(
            validate_outputs(&f.dir, 17, &f.plan.manifest_sha256, || false).unwrap_err(),
            "GPU_EXPOSURE_NONFINITE"
        );
    }
}
#[test]
fn failure_and_cancel_leave_no_partial_or_candidate() {
    for kind in 0..4 {
        let f = Fixture::new(13, 7);
        let original = fs::read(&f.source).unwrap();
        let mut checkpoints = 0;
        let result = streaming::write_candidate(
            &f.dir,
            &f.plan,
            &f.descriptor,
            &mut || {
                checkpoints += 1;
                kind == 3 && checkpoints >= 4
            },
            |bytes, _, _| {
                let mut out: Vec<f32> = bytes
                    .chunks_exact(12)
                    .flat_map(|px| {
                        f.descriptor.emulate_f32(std::array::from_fn(|ch| {
                            f32::from_le_bytes(px[ch * 4..ch * 4 + 4].try_into().unwrap())
                        }))
                    })
                    .collect();
                match kind {
                    0 => out[0] += 1.,
                    1 => out[4] = f32::NAN,
                    2 => return err("GPU_DEVICE_FAILED"),
                    _ => {}
                }
                Ok(out)
            },
        );
        assert!(result.is_err());
        assert!(!f.dir.join("candidate").exists());
        assert!(!f.dir.join(".candidate.partial").exists());
        assert_eq!(fs::read(&f.source).unwrap(), original);
    }
}
#[test]
fn preparation_collision_cancellation_and_table_hash_fail_closed() {
    let f = Fixture::new(3, 5);
    let manifest_before = fs::read(f.dir.join("manifest.json")).unwrap();
    assert!(prepare_stage(&f.source, &f.dir, 17, 3, 5, &f.descriptor, || false).is_err());
    assert_eq!(
        fs::read(f.dir.join("manifest.json")).unwrap(),
        manifest_before
    );
    let second = f.root.join("gpu-stage-18");
    let mut calls = 0;
    assert!(
        prepare_stage(&f.source, &second, 18, 3, 5, &f.descriptor, || {
            calls += 1;
            calls > 2
        })
        .is_err()
    );
    assert!(!second.exists());
    let (m, p) = load(&f.dir, 17).unwrap();
    let table = f.dir.join("tables.f64le");
    let mut bytes = fs::read(&table).unwrap();
    bytes[0] ^= 1;
    fs::write(table, bytes).unwrap();
    assert!(
        matches!(read_descriptor(&f.dir,&m,&p,&mut||false),Err(e) if e=="GPU_EXPOSURE_HASH_MISMATCH")
    );
    assert!(plan_stage(&f.dir, 18).is_err());
    #[cfg(unix)]
    {
        let alias = f.root.join("alias");
        std::os::unix::fs::symlink(&f.dir, &alias).unwrap();
        assert!(plan_stage(&alias, 17).is_err());
    }
}
#[test]
fn guard_checks_all_outputs_and_samples_are_unique() {
    for n in [0, 1, 2, 7, 8, 9, 65535, 65536] {
        let indices = streaming::sample_indices(n);
        assert_eq!(indices.len(), n.min(8));
        assert!(indices.windows(2).all(|x| x[0] < x[1]));
        if n > 0 {
            assert_eq!(indices[0], 0);
            assert_eq!(*indices.last().unwrap(), n - 1);
        }
    }
    let d = fixture(5);
    let source = [0.3f32, 0.7, 0.9]
        .into_iter()
        .flat_map(f32::to_le_bytes)
        .collect::<Vec<_>>();
    assert!(streaming::guard_chunk(&d, &source, &[0.; 2], &mut Default::default()).is_err());
}

/// Real production serialization for the Kotlin parser's cross-language gate.
#[test]
fn emit_exposure_plan_contract_when_requested() {
    let Some(destination) = std::env::var_os("FILM_GPU_CONTRACT_RESULTS") else {
        return;
    };
    let f = Fixture::new(17, 11);
    let plan = plan_stage(&f.dir, 17).unwrap();
    let envelope = serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuFilmExposureStage","plan":plan});
    let destination = PathBuf::from(destination);
    fs::create_dir_all(&destination).unwrap();
    fs::write(
        destination.join("exposure-plan.json"),
        serde_json::to_vec_pretty(&envelope).unwrap(),
    )
    .unwrap();
}
