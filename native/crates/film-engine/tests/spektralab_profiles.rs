//! SpektraLab product-data adaptation and whole/streamed route regression.
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
const BW_FILMS: &[&str] = &["kodak_tri_x_400", "kodak_tmax_100", "fujifilm_neopan_acros_100_ii", "ilford_hp5_plus_400"];
const PAPER: &str = "ilford_multigrade_iv_rc";

#[test]
fn replicated_bw_sources_resolve_without_losing_silver_density() {
    for id in BW_FILMS {
        let raw: serde_json::Value = serde_json::from_slice(&fs::read(data().join(format!("profiles/{id}.json"))).unwrap()).unwrap();
        let p = load_profile_by_name(&data(), id).unwrap();
        assert!(p.is_bw() && p.is_filming());
        assert!(p.data.development_time.is_empty());
        assert_eq!(p.info.target_print.as_deref(), Some(PAPER));
        for (index, row) in p.data.channel_density.iter().enumerate() {
            let expected: f64 = raw["data"]["channel_density"][index].as_array().unwrap().iter().map(|v|v.as_f64().unwrap()).sum();
            assert_eq!(row, &vec![expected]);
            assert_eq!(expected, 1.0);
            assert_eq!(p.data.log_sensitivity[index],vec![raw["data"]["log_sensitivity"][index][0].as_f64().unwrap()]);
        }
        let resolved = spektrafilm_core::profile::resolve_for_render(p, Some(12.0));
        assert!(resolved.data.development_time.is_empty());
        assert!(resolved.data.channel_density.iter().all(|r|r==&vec![1.0,0.0,0.0]));
        for (i,row) in resolved.data.density_curves.iter().enumerate() {
            assert_eq!(row,&vec![raw["data"]["density_curves"][i][0].as_f64().unwrap();3]);
        }
    }
    assert_ne!(load_profile_by_name(&data(), "kodak_trix").unwrap().info.film_type,
               load_profile_by_name(&data(), "kodak_tri_x_400").unwrap().info.film_type);
}

#[test]
fn silver_paper_preserves_three_different_emulsions() {
    let p=load_profile_by_name(&data(),PAPER).unwrap();
    assert!(p.is_multilayer_bw_paper());
    assert!(!profile_catalog::FILMS.contains(&PAPER));
    assert!(profile_catalog::PAPERS.contains(&PAPER));
    let curves=p.data.density_curves.clone();let sensitivity=p.data.log_sensitivity.clone();
    assert!(curves.iter().any(|r|r[0]!=r[1] && r[1]!=r[2]));
    assert!(sensitivity.iter().any(|r|r[0]!=r[1] && r[1]!=r[2]));
    let p=spektrafilm_core::profile::resolve_for_render(p,Some(9.0));
    assert_eq!(p.data.density_curves,curves);assert_eq!(p.data.log_sensitivity,sensitivity);
    assert!(p.data.channel_density.iter().all(|r|r==&vec![1.0;3]));
    assert!(p.data.density_curves_model.is_none());
}

#[test]
fn cinestill_alias_keeps_measured_data_but_distinct_identity_and_backing() {
    let a=load_profile_by_name(&data(),"cinestill_800t").unwrap();
    let b=load_profile_by_name(&data(),"kodak_vision3_500t").unwrap();
    assert_eq!(a.info.stock.as_deref(),Some("cinestill_800t"));
    assert_eq!(a.info.name.as_deref(),Some("CineStill 800T"));
    assert_eq!(a.info.usage,"still");assert_eq!(a.info.antihalation,"no");
    assert_eq!(b.info.antihalation,"strong");
    assert_eq!(a.info.target_print,b.info.target_print);
    assert_eq!(a.metadata.license,b.metadata.license);assert_eq!(a.metadata.copyright,b.metadata.copyright);
    assert_eq!(a.data.log_sensitivity,b.data.log_sensitivity);
    assert_eq!(a.data.density_curves,b.data.density_curves);
    // channel density contains NaN for unsupplied spectral samples.
    for (x,y) in a.data.channel_density.iter().flatten().zip(b.data.channel_density.iter().flatten()) {assert!(x==y || x.is_nan()&&y.is_nan());}
}

#[test]
fn product_profiles_render_and_match_streamed_direct_and_print() {
    for film in BW_FILMS.iter().copied().chain(["cinestill_800t"]) {
        let paper=if film=="cinestill_800t" {"kodak_2383"} else {PAPER};
        for direct in [false,true] {
            let mut p=params(17,13);p.io.scan_film=direct;
            compare(film,paper,p,17,13,2e-5);
        }
    }
}

#[test]
fn each_new_bw_has_spectral_response_and_neutral_print() {
    for film in BW_FILMS {
        let p=params(3,1);
        let pipe=Pipeline::new_with_spectral(load_profile_by_name(&data(),film).unwrap(),load_profile_by_name(&data(),PAPER).unwrap(),p,&data()).unwrap();
        assert_eq!([pipe.params.enlarger.c_filter_neutral,pipe.params.enlarger.m_filter_neutral,pipe.params.enlarger.y_filter_neutral],[0.0,0.0,68.0]);
        let input=ImageBuf::from_data(3,1,vec![from_f64(0.7152),from_f64(0.0),from_f64(0.0),from_f64(0.0),from_f64(0.2126),from_f64(0.0),from_f64(0.184),from_f64(0.184),from_f64(0.184)]);
        let output=pipe.process(input,&CpuBackend);
        assert!(output.data.iter().all(|v|v.is_finite()));
        assert!((output.data[0]-output.data[3]).abs()>1e-4,"{film} is a spectral model, not Rec709 desaturation");
        // Neutral silver under D50 transformed to sRGB is approximately neutral,
        // not a source-dependent colour image. The scanner remains physical.
        for rgb in output.data.chunks_exact(3) {assert!((rgb[0]-rgb[1]).abs()<0.015 && (rgb[1]-rgb[2]).abs()<0.015,"{film}: {rgb:?}");}
        assert!(output.data[6]>0.01 && output.data[6]<0.95,"{film}: middle gray is not clipped");
    }
}

#[test]
fn malformed_replicated_film_and_alias_paths_are_rejected() {
    let t=Temp::new();
    let mut raw:serde_json::Value=serde_json::from_slice(&fs::read(data().join("profiles/kodak_tri_x_400.json")).unwrap()).unwrap();
    raw["data"]["log_sensitivity"][0][1]=serde_json::json!(42.0);
    let path=t.0.join("bad.json");fs::write(&path,serde_json::to_vec(&raw).unwrap()).unwrap();
    assert!(spektrafilm_core::profile::load_profile(&path).is_err());
    fs::write(&path,r#"{"derived_from":"../kodak_vision3_500t","info":{"name":"bad"}}"#).unwrap();
    assert!(spektrafilm_core::profile::load_profile(&path).is_err());
    fs::write(&path,r#"{"derived_from":"bad","info":{"name":"bad"}}"#).unwrap();
    assert!(spektrafilm_core::profile::load_profile(&path).is_err());
    assert!(load_profile_by_name(&data(),"../kodak_vision3_500t").is_err());
}

#[test]
fn new_bw_sublayer_grain_remains_a_single_silver_channel() {
    for film in BW_FILMS {
        let (w,h)=(17,13);let mut p=params(w,h);p.io.scan_film=true;
        p.film_render.grain.active=true;p.film_render.grain.coordinate_origin=Some([0,0]);
        p.film_render.grain.sublayers_active=true;p.film_render.grain.micro_structure=[0.8,100.];
        p.camera.pixel_size_um_override=Some(1.2);p.film_render.dir_couplers.active=true;
        compare(film,PAPER,p.clone(),w,h,3e-5);
        let pipe=Pipeline::new_with_spectral(load_profile_by_name(&data(),film).unwrap(),load_profile_by_name(&data(),PAPER).unwrap(),p,&data()).unwrap();
        let density=ImageBuf::from_data(w,h,vec![from_f64(0.3);(w*h*3)as usize]);
        let output=spektrafilm_core::stages::filming::develop(&density,&pipe.film,&pipe.params,&CpuBackend);
        assert!(output.data.chunks_exact(3).all(|rgb|rgb[0]==rgb[1]&&rgb[1]==rgb[2]));
    }
}

#[test]
fn legacy_profile_numbers_are_unchanged_by_alias_aware_loading() {
    use spektrafilm_core::profile::Profile;
    fn same(a:&[Vec<f64>],b:&[Vec<f64>]) {
        assert_eq!(a.len(),b.len());
        for(x,y)in a.iter().zip(b) {
            assert_eq!(x.len(),y.len());
            for(x,y)in x.iter().zip(y){assert!(x.to_bits()==y.to_bits() || x.is_nan()&&y.is_nan());}
        }
    }
    for entry in profile_catalog::catalog().profiles {
        if BW_FILMS.contains(&entry.id.as_str()) || entry.id==PAPER || entry.id=="cinestill_800t" {continue;}
        let raw:Profile=serde_json::from_reader(fs::File::open(data().join(format!("profiles/{}.json",entry.id))).unwrap()).unwrap();
        let loaded=load_profile_by_name(&data(),&entry.id).unwrap();
        assert_eq!(raw.info.stock,loaded.info.stock);assert_eq!(raw.info.antihalation,loaded.info.antihalation);
        same(&raw.data.log_sensitivity,&loaded.data.log_sensitivity);
        same(&raw.data.density_curves,&loaded.data.density_curves);
        same(&raw.data.channel_density,&loaded.data.channel_density);
        same(&raw.data.base_density_rows,&loaded.data.base_density_rows);
        assert_eq!(raw.data.development_time,loaded.data.development_time);
        assert_eq!(raw.data.log_exposure,loaded.data.log_exposure);
        for (x,y) in raw.data.density_curves_layers.iter().zip(&loaded.data.density_curves_layers){same(x,y);}
    }
}
