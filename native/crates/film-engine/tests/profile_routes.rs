//! Catalog, reversal/direct route, output-space and global glare regression.
use film_engine::{profile_catalog, streamed};
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
static SEQ: AtomicU64 = AtomicU64::new(0);
fn data() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")
}
struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "film-route-{}-{}",
            std::process::id(),
            SEQ.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&p).unwrap();
        Self(p)
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn source(w: u32, h: u32) -> Vec<f32> {
    (0..w * h * 3)
        .map(|i| 0.012 + ((i * 31 + i / 3 * 7) % 997) as f32 / 1100.)
        .collect()
}
fn params(w: u32, h: u32) -> RuntimeParams {
    let mut p = RuntimeParams::default();
    p.camera.auto_exposure = false;
    p.camera.pixel_size_um_override = Some(35000. / w.max(h) as f32);
    p.io.input_cctf_decoding = false;
    p.io.output_cctf_encoding = false;
    p.film_render.grain.active = false;
    p.film_render.halation.active = false;
    p.film_render.dir_couplers.active = false;
    p.film_render.glare.active = false;
    p.print_render.glare.active = false;
    p.scanner.unsharp_mask = [0., 0.];
    p.settings.use_enlarger_lut = true;
    p.settings.use_scanner_lut = true;
    p.settings.lut_resolution = 33;
    p
}
fn compare(film: &str, paper: &str, p: RuntimeParams, w: u32, h: u32, tolerance: f64) {
    let t = Temp::new();
    let x = source(w, h);
    let input = t.0.join("in");
    let out = t.0.join("out");
    let scratch = t.0.join("scratch");
    fs::write(
        &input,
        x.iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>(),
    )
    .unwrap();
    let pipe = Pipeline::new_with_spectral(
        load_profile_by_name(&data(), film).unwrap(),
        load_profile_by_name(&data(), paper).unwrap(),
        p.clone(),
        &data(),
    )
    .unwrap();
    let expected = pipe.process(
        ImageBuf::from_data(w, h, x.iter().map(|&v| from_f64(v as f64)).collect()),
        &CpuBackend,
    );
    streamed::export_file(
        &data(),
        film,
        paper,
        p,
        &input,
        &out,
        &scratch,
        w,
        h,
        384,
        0.,
        || false,
        |_, _, _| {},
    )
    .unwrap();
    let raw = fs::read(&out).unwrap();
    let actual: Vec<f32> = raw
        .chunks_exact(4)
        .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
        .collect();
    let mut max = 0.0f64;
    for (&a, &b) in actual.iter().zip(&expected.data) {
        assert!(a.is_finite() && b.is_finite());
        max = max.max((a as f64 - b as f32 as f64).abs());
    }
    assert_eq!(actual.len(), x.len());
    assert!(!scratch.exists());
    assert!(max <= tolerance, "{film}/{paper} max abs {max}");
}
#[test]
fn all_shooting_profiles_match_whole_route() {
    let (w, h) = (13, 11);
    for s in profile_catalog::catalog().profiles {
        if !profile_catalog::FILMS.contains(&s.id.as_str()) {
            continue;
        }
        let mut p = params(w, h);
        p.io.scan_film = s.default_route == "direct";
        compare(&s.id, &s.default_paper, p, w, h, 1e-6);
    }
}
#[test]
fn all_print_profiles_match_whole() {
    let (w, h) = (11, 9);
    for id in profile_catalog::PAPERS {
        compare("kodak_portra_400", id, params(w, h), w, h, 1e-6);
    }
}
#[test]
fn reversal_dir_grain_and_endpoints_match() {
    let (w, h) = (37, 29);
    for id in [
        "fujifilm_provia_100f",
        "fujifilm_velvia_100",
        "kodak_ektachrome_100",
        "kodak_kodachrome_64",
    ] {
        let mut p = params(w, h);
        p.io.scan_film = true;
        p.film_render.dir_couplers.active = true;
        p.film_render.grain.active = true;
        p.film_render.grain.coordinate_origin = Some([0, 0]);
        p.scanner.white_correction = true;
        p.scanner.black_correction = true;
        compare(id, "kodak_portra_endura", p, w, h, 2e-5);
    }
}
#[test]
fn negative_direct_scan_matches_whole_without_fake_inversion() {
    let (w, h) = (17, 13);
    let mut p = params(w, h);
    p.io.scan_film = true;
    compare("kodak_portra_400", "kodak_portra_endura", p, w, h, 1e-6);
}
#[test]
fn output_color_spaces_match_and_preview_matrices_are_finite() {
    let (w, h) = (11, 9);
    for cs in ["sRGB", "Display P3", "Adobe RGB (1998)"] {
        let mut p = params(w, h);
        p.io.output_color_space = cs.into();
        p.io.output_gamut_compress.algorithm = "none".into();
        compare("kodak_portra_400", "kodak_portra_endura", p, w, h, 1e-6);
        assert!(
            spektrafilm_core::output_color::to_linear_srgb([0.7, 0.2, 0.4], cs)
                .iter()
                .all(|v| v.is_finite())
        );
    }
}
#[test]
fn supplied_global_glare_matches_whole_scan() {
    let (w, h) = (19, 15);
    let mut p = params(w, h);
    p.print_render.glare.active = true;
    p.print_render.glare.percent = 2.;
    p.print_render.glare.roughness = 0.3;
    p.print_render.glare.blur = 1.3;
    let pipe = Pipeline::new_with_spectral(
        load_profile_by_name(&data(), "kodak_portra_400").unwrap(),
        load_profile_by_name(&data(), "kodak_portra_endura").unwrap(),
        p.clone(),
        &data(),
    )
    .unwrap();
    let prepared = pipe.prepare_streamed_finish(&CpuBackend).unwrap();
    let den = ImageBuf::from_data(
        w,
        h,
        source(w, h).iter().map(|&x| from_f64(x as f64)).collect(),
    );
    let field = spektrafilm_model::glare::compute_random_glare_amount(w, h, 2., 0.3, 1.3, 42);
    let got = prepared
        .process_density_chunk_unclipped_with_glare(&den, &field, &CpuBackend)
        .unwrap();
    assert!(got.data.iter().all(|v| v.is_finite()));
    assert!(prepared
        .process_density_chunk_unclipped_with_glare(&den, &field[..field.len() - 1], &CpuBackend)
        .is_err());
    let zero = vec![from_f64(0.); (w * h) as usize];
    let none = prepared.process_density_chunk_unclipped(&den, &CpuBackend);
    let zero_got = prepared
        .process_density_chunk_unclipped_with_glare(&den, &zero, &CpuBackend)
        .unwrap();
    assert_eq!(none.data, zero_got.data);
    assert!(got
        .data
        .iter()
        .zip(&none.data)
        .any(|(a, b)| (a - b).abs() > from_f64(1e-6)));
}

#[test]
fn scanner_global_field_is_bit_exact_with_original_scanner() {
    use spektrafilm_core::{
        color_reference::ColorReference,
        gamut_compression::OutputGamutCompress,
        stages::scanning::{scan, PreparedScanner},
    };
    let (w, h) = (23, 17);
    let density = ImageBuf::from_data(
        w,
        h,
        source(w, h).iter().map(|&x| from_f64(x as f64)).collect(),
    );
    for (id, direct) in [
        ("kodak_portra_endura", false),
        ("kodak_2383", false),
        ("fujifilm_provia_100f", true),
    ] {
        let mut p = params(w, h);
        p.io.scan_film = direct;
        p.film_render.glare.active = true;
        p.print_render.glare.active = true;
        for g in [&mut p.film_render.glare, &mut p.print_render.glare] {
            g.percent = 2.;
            g.roughness = 0.3;
            g.blur = 1.3;
        }
        let profile = load_profile_by_name(&data(), id).unwrap();
        let cr = ColorReference::identity();
        let gamut =
            OutputGamutCompress::build(&p.io.output_gamut_compress, &p.io.output_color_space);
        let original = scan(&density, &profile, &p, &CpuBackend, &cr, &gamut);
        let prepared = PreparedScanner::new_streamed(&profile, &p, &cr, &gamut).unwrap();
        let field = spektrafilm_model::glare::compute_random_glare_amount(w, h, 2., 0.3, 1.3, 42);
        let staged = prepared
            .process_unclipped_with_glare(&density, &field)
            .unwrap();
        for (&a, &b) in staged.data.iter().zip(&original.data) {
            assert_eq!(a.clamp(0., 1.), b, "scanner glare offset differs for {id}");
        }
    }
}

#[test]
fn every_print_stock_supports_each_exposed_curve_control() {
    use spektrafilm_core::{params::PrintCurvesMorphParams, print_morph::morph_density_curves};
    for id in profile_catalog::PAPERS {
        let profile = load_profile_by_name(&data(), id).unwrap();
        // BW 2302 exposes measured-table scalar gamma, not this colour fitted morph.
        if profile.is_bw() {
            assert!(matches!(*id,"kodak_2302"|"ilford_multigrade_iv_rc"));
            assert!(spektrafilm_core::profile::resolve_for_render(profile,None).data.density_curves_model.is_none());
            continue;
        }
        let model = profile
            .data
            .density_curves_model
            .as_ref()
            .expect("every exposed print has a fitted model");
        let axis = profile.log_exposure_f64();
        let baseline = morph_density_curves(
            &axis,
            model,
            &PrintCurvesMorphParams::default(),
            profile.is_positive(),
        )
        .unwrap();
        for which in 0..8 {
            let mut p = PrintCurvesMorphParams::default();
            p.active = true;
            match which {
                0 => p.gamma_factor = 1.4,
                1 => p.gamma_factor_fast = 1.4,
                2 => p.gamma_factor_slow = 1.4,
                3 => p.gamma_factor_red = 1.4,
                4 => p.gamma_factor_green = 1.4,
                5 => p.gamma_factor_blue = 1.4,
                6 => p.developer_exhaustion = 0.6,
                _ => {
                    p.gamma_factor = 3.;
                    p.gamma_factor_fast = 2.;
                    p.gamma_factor_slow = 0.5;
                    p.gamma_factor_red = 0.5;
                    p.gamma_factor_blue = 2.;
                    p.developer_exhaustion = 1.;
                }
            }
            let curves = morph_density_curves(&axis, model, &p, profile.is_positive()).unwrap();
            assert!(
                curves.iter().flatten().all(|v| v.is_finite()),
                "{id} field{which}"
            );
            assert!(
                curves
                    .iter()
                    .flatten()
                    .zip(baseline.iter().flatten())
                    .any(|(a, b)| (a - b).abs() > 1e-8),
                "No effect: {id} field{which}"
            );
        }
    }
}
#[test]
fn nonneutral_film_and_paper_curve_controls_match_whole() {
    let (w, h) = (17, 13);
    for paper in [
        "kodak_portra_endura",
        "kodak_2383",
        "fujifilm_crystal_archive_typeii",
    ] {
        let mut p = params(w, h);
        p.film_render.density_curve_gamma = 1.3;
        p.print_render.density_curves_morph.active = true;
        p.print_render.density_curves_morph.gamma_factor = 1.2;
        p.print_render.density_curves_morph.gamma_factor_fast = 0.8;
        p.print_render.density_curves_morph.gamma_factor_slow = 1.25;
        p.print_render.density_curves_morph.gamma_factor_red = 1.1;
        p.print_render.density_curves_morph.gamma_factor_green = 0.9;
        p.print_render.density_curves_morph.gamma_factor_blue = 1.15;
        p.print_render.density_curves_morph.developer_exhaustion = 0.4;
        compare("kodak_portra_400", paper, p, w, h, 1e-6);
    }
}
#[test]
fn cinema_illuminants_match_pinned_python_reference() {
    let v: serde_json::Value =
        serde_json::from_str(include_str!("../../../data/illuminants_reference.json")).unwrap();
    for (name, values) in [
        ("T", spektrafilm_core::extra_illuminants::T_F64),
        ("K75P", spektrafilm_core::extra_illuminants::K75P_F64),
    ] {
        let expected = v["spectra"][name].as_array().unwrap();
        assert_eq!(expected.len(), 81);
        for (&actual, expected) in values.iter().zip(expected) {
            // serde_json without float_roundtrip may round the final decimal by one ULP.
            let reference = expected.as_f64().unwrap();
            assert!(
                (actual - reference).abs() <= 4.0 * f64::EPSILON * reference.abs().max(1.0),
                "{name}: {actual} vs {reference}"
            );
        }
    }
}
