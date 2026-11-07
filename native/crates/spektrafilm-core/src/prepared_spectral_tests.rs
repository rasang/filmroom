//! Native tests are deliberately separate from implementation. Run with f64.
use super::*;
use crate::prepared_spectral::{PreparedSpectralCache, SpectralAssetRevision};
use crate::profile::load_profile_by_name;
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::precision::from_f64;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

struct Fixture { root: PathBuf }
impl Fixture {
    fn new(real_hanatos: bool) -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let root = std::env::temp_dir().join(format!("prepared-spectral-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir_all(&root).unwrap();
        let fixture = Self { root };
        for relative in ["luts/spectral_upsampling/irradiance_xy_tc.npy", "luts/spectral_upsampling/arctic2026alpha02/reflectance_xy_tc.npy"] {
            let path = fixture.root.join(relative); std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            if real_hanatos && relative.ends_with("/irradiance_xy_tc.npy") {
                std::fs::copy(data().join(relative), path).unwrap();
            } else {
                let header = "{'descr': '<f4', 'fortran_order': False, 'shape': (4, 4, 81), }\n";
                let mut bytes = b"\x93NUMPY\x01\x00".to_vec(); bytes.extend((header.len() as u16).to_le_bytes()); bytes.extend(header.bytes());
                for i in 0..4*4*81 { bytes.extend((0.1f32 + (i % 81) as f32 * 0.001 + (i / 81) as f32 * 0.0001).to_le_bytes()); }
                std::fs::write(path, bytes).unwrap();
            }
        }
        fixture.manifest('a', 'b'); fixture
    }
    // Test identities intentionally stand in for installer-produced SHA-256 rows.
    // Production only accepts the trusted installer manifest; this is not hashing.
    fn manifest(&self, hanatos: char, arctic: char) {
        let mut text = "assets-v2:test\n".to_string();
        for (relative, digest) in [("luts/spectral_upsampling/irradiance_xy_tc.npy", hanatos), ("luts/spectral_upsampling/arctic2026alpha02/reflectance_xy_tc.npy", arctic)] {
            text.push_str(&format!("{}\t{}\t{}\n", relative, std::fs::metadata(self.root.join(relative)).unwrap().len(), digest.to_string().repeat(64)));
        }
        std::fs::write(self.root.join(".prepared-v2"), text).unwrap();
    }
}
impl Drop for Fixture { fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.root); } }
fn data() -> PathBuf { Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data") }
fn profiles(film: &str, paper: &str) -> (Profile, Profile) {
    (load_profile_by_name(&data(), film).unwrap(), load_profile_by_name(&data(), paper).unwrap())
}
fn params(method: &str) -> RuntimeParams {
    let mut p = RuntimeParams::default(); p.settings.rgb_to_raw_method = method.into();
    p.settings.neutral_print_filters_from_database = false;
    p.settings.lut_resolution = 33; p.settings.use_enlarger_lut = true; p.settings.use_scanner_lut = true;
    p.camera.auto_exposure = false; p.film_render.grain.active = false; p.print_render.glare.active = false;
    p.scanner.lens_blur = 0.; p.scanner.unsharp_mask = [0., 0.]; p
}
fn cached(fixture: &Fixture, cache: &PreparedSpectralCache, film: Profile, paper: Profile, p: RuntimeParams) -> Pipeline {
    let revision = SpectralAssetRevision::read(&fixture.root, &p.settings.rgb_to_raw_method);
    assert!(revision.is_some());
    Pipeline::new_with_spectral_cached_controlled(film, paper, p, &fixture.root, cache, revision, || true).unwrap()
}
fn assert_calibration_equal(a: &Pipeline, b: &Pipeline) {
    assert_eq!(a.print_exposure_factor.to_bits(), b.print_exposure_factor.to_bits());
    assert_eq!(a.print_illuminant.iter().map(|v|v.to_bits()).collect::<Vec<_>>(), b.print_illuminant.iter().map(|v|v.to_bits()).collect::<Vec<_>>());
    assert_eq!(a.preflash_raw.map(f64::to_bits), b.preflash_raw.map(f64::to_bits));
    assert_eq!(serde_json::to_string(&a.params).unwrap(), serde_json::to_string(&b.params).unwrap());
    match (a.tc_lut(), b.tc_lut()) {
        (Some(a), Some(b)) => { assert_eq!((a.size, a.channels), (b.size, b.channels)); assert_eq!(a.data.iter().map(|v|v.to_bits()).collect::<Vec<_>>(), b.data.iter().map(|v|v.to_bits()).collect::<Vec<_>>()); }
        (None, None) => assert_eq!(a.spectral.mallett_core, b.spectral.mallett_core),
        _ => panic!("front method changed"),
    }
}
#[cfg(feature = "precision-f64")]
fn assert_image_equal(a: &Pipeline, b: &Pipeline) {
    let image = ImageBuf::from_data(9, 7, (0..9*7*3).map(|i| from_f64(0.005 + (i * 17 % 163) as f64 * 0.001)).collect());
    let actual = a.process(image.clone(), &CpuBackend); let expected = b.process(image, &CpuBackend);
    assert!(actual.data.iter().chain(&expected.data).all(|v| v.is_finite()));
    assert_eq!(actual.data.iter().map(|v|v.to_bits()).collect::<Vec<_>>(), expected.data.iter().map(|v|v.to_bits()).collect::<Vec<_>>());
}

#[test]
fn cold_hit_and_fresh_calibration_match_all_front_methods() {
    let fixture = Fixture::new(true);
    for method in ["hanatos2025", "arctic2026alpha02", "mallett2019"] {
        let cache = PreparedSpectralCache::default(); let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params(method);
        let first = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        let second = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        assert!(Arc::ptr_eq(&first.spectral, &second.spectral)); assert_eq!(cache.stats().hits, 1);
        let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap();
        assert_calibration_equal(&second, &cold);
        #[cfg(feature = "precision-f64")] assert_image_equal(&second, &cold);
    }
}

#[test]
fn downstream_controls_reuse_front_and_recalibrate_fresh() {
    let fixture = Fixture::new(true); let cache = PreparedSpectralCache::default();
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let base = params("hanatos2025");
    let first = cached(&fixture, &cache, film.clone(), paper.clone(), base.clone());
    let mutations: Vec<Box<dyn Fn(&mut RuntimeParams)>> = vec![
        Box::new(|p| p.print_render.density_curves_morph.gamma_factor = 1.3),
        Box::new(|p| p.film_render.density_curve_gamma = 1.2),
        Box::new(|p| p.film_render.dir_couplers.amount = 0.7),
        Box::new(|p| p.enlarger.y_filter_shift = -12.),
        Box::new(|p| p.enlarger.m_filter_neutral = 15.),
        Box::new(|p| p.enlarger.illuminant = "D55".into()),
        Box::new(|p| p.enlarger.normalize_print_exposure = false),
        Box::new(|p| p.enlarger.print_exposure_compensation = false),
        Box::new(|p| p.camera.exposure_compensation_ev = -2.),
        Box::new(|p| p.camera.exposure_compensation_ev = 2.),
        Box::new(|p| {p.camera.auto_exposure = true; p.camera.preview_metered_ev = Some(1.);}),
        Box::new(|p| p.enlarger.preflash_exposure = 0.1),
        Box::new(|p| p.settings.use_cat16 = false),
        Box::new(|p| p.io.scan_film = true),
    ];
    for mutate in mutations {
        let mut p = base.clone(); mutate(&mut p);
        let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        assert!(Arc::ptr_eq(&first.spectral, &actual.spectral));
        let cold = Pipeline::new_with_spectral(film.clone(), paper.clone(), p, &fixture.root).unwrap();
        assert_calibration_equal(&actual, &cold);
        #[cfg(feature = "precision-f64")] assert_image_equal(&actual, &cold);
    }
    // Paper switch is downstream of film front construction, including 2302.
    let paper = load_profile_by_name(&data(), "kodak_2302").unwrap();
    let actual = cached(&fixture, &cache, film.clone(), paper.clone(), base.clone());
    assert!(Arc::ptr_eq(&first.spectral, &actual.spectral));
    let cold = Pipeline::new_with_spectral(film, paper, base, &fixture.root).unwrap(); assert_calibration_equal(&actual, &cold);
    #[cfg(feature = "precision-f64")] assert_image_equal(&actual, &cold);
    assert_eq!(cache.stats().publications, 1);
}

#[test]
fn every_front_dependency_misses_without_rounded_keys() {
    let fixture = Fixture::new(false); let cache = PreparedSpectralCache::default();
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let base = params("hanatos2025");
    let original = cached(&fixture, &cache, film.clone(), paper.clone(), base.clone());
    let mut cases = Vec::new();
    let mut f = film.clone(); f.data.log_sensitivity[30][0] += 0.00001; cases.push((f, base.clone()));
    let mut f = film.clone(); f.info.reference_illuminant = "D65".into(); cases.push((f, base.clone()));
    let mut f = film.clone(); f.data.hanatos2025_adaptation_window_params[0] += 0.01; cases.push((f, base.clone()));
    let mut p = base.clone(); p.settings.apply_hanatos2025_adaptation_window = false; cases.push((film.clone(), p));
    let mut p = base.clone(); p.io.input_gamut_compress.algorithm = "xy".into(); cases.push((film.clone(), p));
    let mut p = base.clone(); p.io.input_gamut_compress.knee[0] = 0.1; cases.push((film.clone(), p));
    let mut p = base.clone(); p.settings.rgb_to_raw_method = "arctic2026alpha02".into(); cases.push((film.clone(), p));
    let mut p = base.clone(); p.settings.rgb_to_raw_method = "mallett2019".into(); cases.push((film.clone(), p));
    for (f, p) in cases {
        let before = cache.stats().misses; let calibration_before = cache.stats().calibration_misses; let actual = cached(&fixture, &cache, f, paper.clone(), p);
        assert!(!Arc::ptr_eq(&original.spectral, &actual.spectral)); assert_eq!(cache.stats().misses, before + 1);
        assert_eq!(cache.stats().calibration_misses, calibration_before + 1);
    }
}

#[test]
fn manifest_identity_tracks_selected_lut_and_missing_manifest_is_cold() {
    let fixture = Fixture::new(false);
    let hanatos = SpectralAssetRevision::read(&fixture.root, "hanatos2025").unwrap();
    let arctic = SpectralAssetRevision::read(&fixture.root, "arctic2026alpha02").unwrap();
    fixture.manifest('a', 'c');
    assert_eq!(Some(hanatos), SpectralAssetRevision::read(&fixture.root, "hanatos2025"));
    assert_ne!(Some(arctic), SpectralAssetRevision::read(&fixture.root, "arctic2026alpha02"));
    std::fs::remove_file(fixture.root.join(".prepared-v2")).unwrap();
    assert!(SpectralAssetRevision::read(&fixture.root, "hanatos2025").is_none());
    let cache = PreparedSpectralCache::default(); let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("hanatos2025");
    let actual = Pipeline::new_with_spectral_cached_controlled(film.clone(), paper.clone(), p.clone(), &fixture.root, &cache, None, || true).unwrap();
    let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap(); assert_calibration_equal(&actual, &cold);
    assert_eq!(cache.stats().publications, 0);
}

#[test]
fn cancellation_and_disable_never_publish_partial_preparation() {
    let fixture = Fixture::new(false); let cache = PreparedSpectralCache::default(); let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("hanatos2025");
    for stop_at in 1..=4 {
        let mut calls = 0;
        let result = Pipeline::new_with_spectral_cached_controlled(film.clone(), paper.clone(), p.clone(), &fixture.root, &cache,
            SpectralAssetRevision::read(&fixture.root, "hanatos2025"), || { calls += 1; calls < stop_at });
        assert!(result.is_err()); assert_eq!(cache.stats().publications, 0);
    }
    cache.set_enabled(false);
    let actual = cached(&fixture, &cache, film, paper, p); assert!(actual.tc_lut().is_some());
    assert_eq!(cache.stats().retained_entries, 0); assert!(!cache.stats().enabled);
}

#[cfg(feature = "precision-f64")]
#[test]
fn f64_bw_development_print_direct_and_seeded_effects_match_cold() {
    let fixture = Fixture::new(true);
    for film_name in ["kodak_doublex", "kodak_trix", "kodak_portra_400"] {
        let cache = PreparedSpectralCache::default(); let (film, paper) = profiles(film_name, "kodak_2302");
        let mut p = params("hanatos2025"); p.film_render.grain.active = true; p.film_render.grain.seed = 123;
        p.film_render.halation.active = true; p.film_render.dir_couplers.active = true;
        p.camera.pixel_size_um_override = Some(50.);
        p.camera.diffusion_filter.active = true; p.camera.diffusion_filter.strength = 0.1;
        p.enlarger.diffusion_filter.active = true; p.enlarger.diffusion_filter.strength = 0.1;
        p.scanner.lens_blur = 0.2; p.scanner.unsharp_mask = [0.3, 0.2];
        let first = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        for direct in [false, true] {
            let mut current = p.clone(); current.io.scan_film = direct; current.film_render.development_time = Some(10.);
            let actual = cached(&fixture, &cache, film.clone(), paper.clone(), current.clone());
            assert!(Arc::ptr_eq(&first.spectral, &actual.spectral));
            let cold = Pipeline::new_with_spectral(film.clone(), paper.clone(), current, &fixture.root).unwrap();
            assert_calibration_equal(&actual, &cold); assert_image_equal(&actual, &cold);
        }
    }
}

#[test]
fn asset_change_during_preparation_is_not_published() {
    let fixture = Fixture::new(false); let cache = PreparedSpectralCache::default(); let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura");
    let revision = SpectralAssetRevision::read(&fixture.root, "hanatos2025"); let mut calls = 0;
    let result = Pipeline::new_with_spectral_cached_controlled(film, paper, params("hanatos2025"), &fixture.root, &cache, revision, || {
        calls += 1; if calls == 3 { fixture.manifest('d', 'b'); } true
    });
    assert!(result.is_err()); assert_eq!(cache.stats().publications, 0);
}

#[test]
fn incorrect_asset_root_cannot_hit_a_prior_front() {
    let first = Fixture::new(false); let second = Fixture::new(false); let cache = PreparedSpectralCache::default();
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("hanatos2025");
    let original = cached(&first, &cache, film.clone(), paper.clone(), p.clone());
    let wrong_revision = SpectralAssetRevision::read(&first.root, "hanatos2025");
    let actual = Pipeline::new_with_spectral_cached_controlled(film, paper, p, &second.root, &cache, wrong_revision, || true).unwrap();
    assert!(!Arc::ptr_eq(&original.spectral, &actual.spectral)); assert_eq!(cache.stats().hits, 0);
}

#[cfg(all(feature = "wgpu-backend", feature = "precision-f64"))]
#[test]
fn real_gpu_cached_front_matches_fresh_front_without_cpu_fallback() {
    let fixture = Fixture::new(true); let cache = PreparedSpectralCache::default(); let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura");
    let p = params("hanatos2025"); let _first = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap();
    let backend = spektrafilm_gpu::wgpu_backend::WgpuBackend::new().expect("designated GPU test requires a real backend");
    let image = ImageBuf::from_data(16, 12, (0..16*12*3).map(|i| from_f64(0.02 + (i * 13 % 137) as f64 * 0.001)).collect());
    let before = backend.thread_submits();
    let a = actual.process_resident_borrowed(&image, &backend).expect("cached path must execute GPU");
    assert!(backend.thread_submits() > before);
    let before = backend.thread_submits();
    let b = cold.process_resident_borrowed(&image, &backend).expect("cold path must execute GPU");
    assert!(backend.thread_submits() > before); assert!(backend.is_healthy());
    assert!(a.data.iter().chain(&b.data).all(|v| v.is_finite()));
    assert_eq!(a.data.iter().map(|v|v.to_bits()).collect::<Vec<_>>(), b.data.iter().map(|v|v.to_bits()).collect::<Vec<_>>());
}

#[test]
fn idle_prewarm_front_matches_cold_and_is_consumed_once() {
    let fixture=Fixture::new(true);
    for method in ["hanatos2025","arctic2026alpha02","mallett2019"] {
        let cache=PreparedSpectralCache::default();
        let (film,paper)=profiles("kodak_portra_400","kodak_portra_endura");
        let p=params(method);
        assert_eq!(cache.prewarm(film.clone(),&p,&fixture.root,||true).unwrap(),"published");
        assert_eq!((cache.stats().prewarm_published,cache.stats().prewarm_used),(1,0));
        let build_ns=cache.stats().build_nanoseconds;
        assert_eq!(cache.prewarm(film.clone(),&p,&fixture.root,||true).unwrap(),"already_warm");
        assert_eq!(cache.stats().prewarm_used,0);
        let warm=cached(&fixture,&cache,film.clone(),paper.clone(),p.clone());
        let repeat=cached(&fixture,&cache,film.clone(),paper.clone(),p.clone());
        assert!(Arc::ptr_eq(&warm.spectral,&repeat.spectral));
        assert_eq!((cache.stats().prewarm_used,cache.stats().publications),(1,1));
        assert_eq!(cache.stats().build_nanoseconds,build_ns);
        let cold=Pipeline::new_with_spectral(film,paper,p,&fixture.root).unwrap();
        assert_calibration_equal(&warm,&cold);
        #[cfg(feature="precision-f64")] assert_image_equal(&warm,&cold);
        cache.clear();assert_eq!(cache.stats().prewarm_wasted,0);
    }
}

#[test]
fn idle_prewarm_never_bakes_unknown_auto_exposure_or_paper_controls() {
    let fixture=Fixture::new(true);let cache=PreparedSpectralCache::default();
    let (film,paper)=profiles("kodak_doublex","kodak_2302");
    let mut p=params("hanatos2025");p.camera.auto_exposure=true;p.camera.preview_metered_ev=None;
    assert_eq!(cache.prewarm(film.clone(),&p,&fixture.root,||true).unwrap(),"published");
    p.camera.preview_metered_ev=Some(1.25);p.camera.exposure_compensation_ev=-0.5;
    p.enlarger.preflash_exposure=0.04;p.enlarger.y_filter_shift=3.;
    let warm=cached(&fixture,&cache,film.clone(),paper.clone(),p.clone());
    let cold=Pipeline::new_with_spectral(film,paper,p,&fixture.root).unwrap();
    assert_eq!(cache.stats().prewarm_used,1);assert_calibration_equal(&warm,&cold);
    #[cfg(feature="precision-f64")] assert_image_equal(&warm,&cold);
}

#[test]
fn idle_prewarm_cancellation_never_publishes_partial_front() {
    let fixture=Fixture::new(true);let (film,_)=profiles("kodak_portra_400","kodak_portra_endura");
    for limit in [0,2,40,500,1000] {
        let cache=PreparedSpectralCache::default();let mut visits=0;
        let result=cache.prewarm(film.clone(),&params("hanatos2025"),&fixture.root,||{visits+=1;visits<=limit});
        assert!(result.is_err());assert_eq!(cache.stats().retained_entries,0);
        assert_eq!(cache.stats().live_assets,0);assert_eq!(cache.stats().prewarm_cancelled,1);
    }
}

#[test]
fn idle_prewarm_unused_retirement_and_epoch_rejection_are_counted() {
    let fixture=Fixture::new(false);let (film,_)=profiles("kodak_portra_400","kodak_portra_endura");
    let cache=PreparedSpectralCache::default();let p=params("mallett2019");
    cache.prewarm(film.clone(),&p,&fixture.root,||true).unwrap();cache.clear();
    assert_eq!((cache.stats().prewarm_published,cache.stats().prewarm_wasted),(1,1));
    // Simulate invalidation after a ticket is obtained, without asking the
    // loop to abort. The publication epoch is an independent final safety net.
    let mut checkpoints=0;
    let result=cache.prewarm(film,&p,&fixture.root,||{checkpoints+=1;if checkpoints==2{cache.cancel_pending();}true}).unwrap();
    assert_eq!(result,"not_admitted");assert_eq!(cache.stats().retained_entries,0);
    assert_eq!(cache.stats().rejected_publications,1);
}

#[test]
fn idle_prewarm_missing_manifest_and_disabled_owner_are_cold_skips() {
    let fixture=Fixture::new(false);let (film,_)=profiles("kodak_portra_400","kodak_portra_endura");
    let cache=PreparedSpectralCache::disabled();let p=params("hanatos2025");
    assert_eq!(cache.prewarm(film.clone(),&p,&fixture.root,||true).unwrap(),"disabled");
    cache.set_enabled(true);std::fs::remove_file(fixture.root.join(".prepared-v2")).unwrap();
    assert_eq!(cache.prewarm(film,&p,&fixture.root,||true).unwrap(),"unverified_assets");
    assert_eq!((cache.stats().prewarm_skipped,cache.stats().live_assets),(2,0));
}

#[test]
fn calibration_reuse_hits_across_downstream_controls_with_exact_pixels() {
    let fixture = Fixture::new(true);
    for (method, stock) in [("hanatos2025", "kodak_portra_400"), ("arctic2026alpha02", "fujifilm_provia_100f"), ("mallett2019", "kodak_doublex")] {
        let cache = PreparedSpectralCache::default();
        let (film, paper) = profiles(stock, "kodak_portra_endura");
        let base = params(method);
        let _first = cached(&fixture, &cache, film.clone(), paper.clone(), base.clone());
        let mut p = base.clone();
        p.print_render.density_curves_morph.gamma_factor = 1.2;
        p.enlarger.print_exposure = 1.3;
        p.scanner.white_correction = true;
        p.io.output_color_space = "Display P3".into();
        let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap();
        assert_eq!(cache.stats().calibration_hits, 1, "{method}/{stock}");
        assert_eq!(cache.stats().calibration_publications, 1);
        assert!(cache.stats().calibration_retained_bytes > 0);
        assert!(cache.stats().live_bytes <= cache.stats().max_bytes);
        assert_calibration_equal(&actual, &cold);
        #[cfg(feature = "precision-f64")] assert_image_equal(&actual, &cold);
    }
}

#[test]
fn calibration_every_runtime_dependency_invalidates_exactly() {
    let fixture = Fixture::new(false);
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura");
    let base = params("mallett2019");
    let mutations: Vec<Box<dyn Fn(&mut RuntimeParams)>> = vec![
        Box::new(|p| p.settings.use_cat16 = !p.settings.use_cat16),
        Box::new(|p| p.film_render.density_curve_gamma = f32::from_bits(p.film_render.density_curve_gamma.to_bits()+1)),
        Box::new(|p| p.camera.exposure_compensation_ev = 0.25),
        Box::new(|p| p.enlarger.illuminant = "D55".into()),
        Box::new(|p| p.enlarger.c_filter_neutral = 0.25),
        Box::new(|p| p.enlarger.m_filter_neutral += 0.25),
        Box::new(|p| p.enlarger.y_filter_neutral += 0.25),
        Box::new(|p| p.enlarger.m_filter_shift = 0.25),
        Box::new(|p| p.enlarger.y_filter_shift = 0.25),
        Box::new(|p| p.enlarger.preflash_exposure = 0.1),
        Box::new(|p| p.enlarger.preflash_m_filter_shift = 0.25),
        Box::new(|p| p.enlarger.preflash_y_filter_shift = 0.25),
        Box::new(|p| p.enlarger.normalize_print_exposure = false),
        Box::new(|p| p.enlarger.print_exposure_compensation = false),
    ];
    for mutate in mutations {
        let cache = PreparedSpectralCache::default();
        let _ = cached(&fixture, &cache, film.clone(), paper.clone(), base.clone());
        let mut p = base.clone(); mutate(&mut p);
        let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        let cold = Pipeline::new_with_spectral(film.clone(), paper.clone(), p, &fixture.root).unwrap();
        assert_eq!(cache.stats().calibration_misses, 2);
        assert_eq!(cache.stats().calibration_hits, 0);
        assert_calibration_equal(&actual, &cold);
    }
}

#[test]
fn calibration_resolved_profile_bit_changes_invalidate() {
    let fixture = Fixture::new(false);
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura");
    let p = params("mallett2019");
    let mut cases = Vec::new();
    let bump = |v: &mut f64| { assert!(v.is_finite()); *v = f64::from_bits(v.to_bits()+1); };
    let mut f = film.clone(); bump(&mut f.data.log_exposure[20]); cases.push((f, paper.clone()));
    let mut f = film.clone(); bump(&mut f.data.density_curves[20][0]); cases.push((f, paper.clone()));
    let mut f = film.clone(); bump(&mut f.data.channel_density[40][0]); cases.push((f, paper.clone()));
    let mut f = film.clone(); bump(&mut f.data.base_density[40]); cases.push((f, paper.clone()));
    let mut print = paper.clone(); bump(&mut print.data.log_sensitivity[40][0]); cases.push((film.clone(), print));
    for (f, print) in cases {
        let cache = PreparedSpectralCache::default();
        let _ = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        let actual = cached(&fixture, &cache, f.clone(), print.clone(), p.clone());
        let cold = Pipeline::new_with_spectral(f, print, p.clone(), &fixture.root).unwrap();
        assert_eq!(cache.stats().calibration_misses, 2);
        assert_calibration_equal(&actual, &cold);
    }
}

#[test]
fn calibration_bw_development_time_is_resolved_before_keying() {
    let fixture = Fixture::new(false); let cache = PreparedSpectralCache::default();
    let (film, paper) = profiles("kodak_doublex", "kodak_2302");
    let mut p = params("mallett2019"); p.film_render.development_time = Some(6.);
    let _ = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    p.film_render.development_time = Some(10.);
    let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap();
    assert_eq!(cache.stats().calibration_misses, 2);
    assert_calibration_equal(&actual, &cold);
    #[cfg(feature = "precision-f64")] assert_image_equal(&actual, &cold);
}

#[test]
fn calibration_pipeline_mutation_cannot_poison_retained_value() {
    let fixture = Fixture::new(false); let cache = PreparedSpectralCache::default();
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("mallett2019");
    let mut first = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    first.print_illuminant.fill(0.); first.print_exposure_factor = -100.; first.preflash_raw = [50.; 3];
    first.film.data.density_curves[20][0] = 100.; first.params.enlarger.preflash_exposure = 10.;
    let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap();
    assert_eq!(cache.stats().calibration_hits, 1);
    assert_calibration_equal(&actual, &cold);
}

#[test]
fn calibration_cancellation_clear_disable_and_reentry_are_safe() {
    let fixture = Fixture::new(false);
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("mallett2019");
    for stop_at in 1..=4 {
        let cache = PreparedSpectralCache::default(); let mut calls = 0;
        let result = Pipeline::new_with_spectral_cached_controlled(film.clone(), paper.clone(), p.clone(), &fixture.root, &cache,
            SpectralAssetRevision::read(&fixture.root, "mallett2019"), || { calls += 1; let _ = cache.stats(); calls < stop_at });
        assert!(result.is_err()); assert_eq!(cache.stats().calibration_publications, 0);
        let _ = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        assert_eq!(cache.stats().calibration_publications, 1);
        cache.clear(); assert_eq!(cache.stats().calibration_retained_bytes, 0);
        cache.set_enabled(false); assert_eq!(cache.stats().live_bytes, 0);
    }
    let cache = PreparedSpectralCache::default(); let mut calls = 0;
    let actual = Pipeline::new_with_spectral_cached_controlled(film.clone(), paper.clone(), p.clone(), &fixture.root, &cache,
        SpectralAssetRevision::read(&fixture.root, "mallett2019"), || { calls += 1; if calls == 4 { cache.clear(); } true }).unwrap();
    assert_eq!(cache.stats().calibration_publications, 0);
    assert_eq!(cache.stats().calibration_rejections, 1);
    let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap(); assert_calibration_equal(&actual, &cold);
}

#[test]
fn calibration_asset_replacement_invalidates_and_blocks_late_publication() {
    let fixture = Fixture::new(false); let cache = PreparedSpectralCache::default();
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("hanatos2025");
    let _ = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    fixture.manifest('c', 'b');
    let _ = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    assert_eq!(cache.stats().calibration_misses, 2); assert_eq!(cache.stats().calibration_hits, 0);
    cache.clear(); let before = cache.stats().calibration_publications; let mut calls = 0;
    let result = Pipeline::new_with_spectral_cached_controlled(film, paper, p, &fixture.root, &cache,
        SpectralAssetRevision::read(&fixture.root, "hanatos2025"), || { calls += 1; if calls == 4 { fixture.manifest('d', 'b'); } true });
    assert!(result.is_err()); assert_eq!(cache.stats().calibration_publications, before);
}

#[test]
fn calibration_database_f64_values_invalidate_below_f32_resolution() {
    let fixture = Fixture::new(false); let cache = PreparedSpectralCache::default();
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura");
    let mut p = params("mallett2019"); p.settings.neutral_print_filters_from_database = true;
    let dir = fixture.root.join("filters"); std::fs::create_dir_all(&dir).unwrap();
    let write = |m: f64| std::fs::write(dir.join("neutral_print_filters.json"),
        serde_json::to_vec(&serde_json::json!({"kodak_portra_endura":{"TH-KG3":{"kodak_portra_400":[0.,m,55.]}}})).unwrap()).unwrap();
    write(64.5);
    let first = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    let m = f64::from_bits(64.5f64.to_bits()+1); assert_eq!(m as f32, 64.5f32); write(m);
    let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap();
    assert_eq!(first.params.enlarger.m_filter_neutral, actual.params.enlarger.m_filter_neutral);
    assert_eq!(cache.stats().calibration_misses, 2);
    assert_calibration_equal(&actual, &cold);
}

#[test]
fn calibration_key_bounds_exact_nan_and_signed_zero_profile_bits() {
    use crate::prepared_spectral::{CalibrationKey, SpectralKey};
    let fixture = Fixture::new(false);
    let (mut film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("mallett2019");
    let front = SpectralKey::new(&film, &p, SpectralAssetRevision::read(&fixture.root, "mallett2019").unwrap()).unwrap();
    let make = |f: &Profile| CalibrationKey::new(&front, f, &paper, &p, [0., 65., 55.]);
    film.data.base_density[0] = 0.; let zero = make(&film).unwrap();
    film.data.base_density[0] = -0.; assert_ne!(make(&film).unwrap(), zero);
    film.data.base_density[0] = f64::from_bits(0x7ff8000000000001); let nan = make(&film).unwrap();
    film.data.base_density[0] = f64::from_bits(0x7ff8000000000002); assert_ne!(make(&film).unwrap(), nan);
    film.data.log_exposure = vec![0.; 4097]; assert!(make(&film).is_none());
}

#[test]
fn calibration_front_construction_retirement_does_not_refresh_epoch_ticket() {
    let fixture = Fixture::new(false);
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("mallett2019");
    for retirement in 0..3 {
        let cache = PreparedSpectralCache::default(); let mut calls = 0;
        let result = Pipeline::new_with_spectral_cached_controlled(film.clone(), paper.clone(), p.clone(), &fixture.root, &cache,
            SpectralAssetRevision::read(&fixture.root, "mallett2019"), || {
                calls += 1;
                if calls == 3 { match retirement {
                    0 => cache.clear(),
                    1 => cache.cancel_pending(),
                    _ => { cache.set_enabled(false); cache.set_enabled(true); }
                } }
                true
            });
        assert!(result.is_ok());
        assert_eq!(cache.stats().calibration_retained_bytes, 0);
        assert_eq!(cache.stats().calibration_publications, 0);
        assert_eq!(cache.stats().publications, 0);
        let _ = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
        assert_eq!(cache.stats().calibration_publications, 1);
    }
}

#[test]
fn calibration_active_cat16_preflash_compensation_branches_match_cold() {
    let fixture = Fixture::new(false);
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura");
    for cat16 in [false, true] {
        for normalize in [false, true] {
            for compensate in [false, true] {
                let cache = PreparedSpectralCache::default();
                let mut p = params("hanatos2025"); p.settings.use_cat16 = cat16;
                p.enlarger.normalize_print_exposure = normalize;
                p.enlarger.print_exposure_compensation = compensate;
                p.camera.exposure_compensation_ev = 0.75;
                p.enlarger.preflash_exposure = 0.12;
                p.enlarger.preflash_m_filter_shift = 2.5;
                p.enlarger.preflash_y_filter_shift = -3.25;
                let _ = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
                p.print_render.density_curve_gamma = 1.2;
                let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
                let cold = Pipeline::new_with_spectral(film.clone(), paper.clone(), p, &fixture.root).unwrap();
                assert_eq!(cache.stats().calibration_hits, 1);
                assert_calibration_equal(&actual, &cold);
                #[cfg(feature = "precision-f64")] assert_image_equal(&actual, &cold);
            }
        }
    }
}

#[cfg(feature = "precision-f64")]
#[test]
fn calibration_source_changes_reuse_only_constants_not_pixels() {
    let fixture = Fixture::new(false); let cache = PreparedSpectralCache::default();
    let (film, paper) = profiles("kodak_portra_400", "kodak_portra_endura"); let p = params("mallett2019");
    let _ = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    let actual = cached(&fixture, &cache, film.clone(), paper.clone(), p.clone());
    let cold = Pipeline::new_with_spectral(film, paper, p, &fixture.root).unwrap();
    let mut previous = None;
    for level in [0.01, 0.2] {
        let source = ImageBuf::from_data(3, 2, vec![from_f64(level); 18]);
        let output = actual.process(source.clone(), &CpuBackend);
        let reference = cold.process(source, &CpuBackend);
        let bits: Vec<_> = output.data.iter().map(|v| v.to_bits()).collect();
        assert_eq!(bits, reference.data.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        if let Some(previous) = previous { assert_ne!(bits, previous); }
        previous = Some(bits);
    }
    assert_eq!(cache.stats().calibration_hits, 1);
}
