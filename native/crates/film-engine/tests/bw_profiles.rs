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
fn bw_measured_families_are_resolved_as_one_silver_channel() {
    for (id,times) in [("kodak_doublex",vec![4.,5.,6.5,9.,12.]),("kodak_trix",vec![1.]),("kodak_2302",vec![2.,3.5,5.,7.,9.])] {
        let p=load_profile_by_name(&data(),id).unwrap(); assert!(p.is_bw());assert_eq!(p.data.development_time,times);
        let raw:serde_json::Value=serde_json::from_str(&fs::read_to_string(data().join(format!("profiles/{id}.json"))).unwrap()).unwrap();
        for (i,row) in raw["data"]["log_sensitivity"].as_array().unwrap().iter().enumerate(){if row[0].is_null(){assert_eq!(10f64.powf(p.data.log_sensitivity[i][0]),0.0);}}
        for (idx,time) in times.iter().enumerate() {
            let resolved=spektrafilm_core::profile::resolve_for_render(p.clone(),Some(*time));
            for (a,b) in resolved.data.density_curves.iter().zip(&p.data.density_curves) {assert_eq!(a,&vec![b[idx];3]);}
            for row in &resolved.data.density_curves_layers {for layer in row {assert_eq!(layer.len(),3);assert_eq!(layer[0],layer[1]);assert_eq!(layer[1],layer[2]);}}
            assert!(resolved.data.channel_density.iter().all(|x|x[1]==0. && x[2]==0.));
            if id=="kodak_2302" {assert!(resolved.data.density_curves_model.is_none());}
        }
    }
    assert!(!profile_catalog::FILMS.contains(&"kodak_2302"));
    assert!(!profile_catalog::valid_route("kodak_trix","kodak_2302","print"));
}
#[test]
fn bw_all_routes_and_development_times_match_streamed() {
    for (film,paper,direct) in [("kodak_doublex","kodak_2302",false),("kodak_doublex","kodak_2302",true),("kodak_trix","kodak_2302",true),("kodak_gold_200","kodak_2302",false),("kodak_doublex","kodak_portra_endura",false)] {
        let mut p=params(13,11);p.io.scan_film=direct;
        compare(film,paper,p.clone(),13,11,2e-5);
        p.film_render.development_time=Some(12.);p.print_render.development_time=Some(9.);
        p.film_render.density_curve_gamma=1.2;p.print_render.density_curve_gamma=1.25;
        p.camera.exposure_compensation_ev=0.7;p.enlarger.print_exposure=1.3;
        if !direct || film=="kodak_trix" {p.scanner.white_correction=true;p.scanner.black_correction=true;}
        compare(film,paper,p,13,11,2e-5);
    }
}
#[test]
fn bw_sublayer_grain_and_clumps_remain_single_channel_and_match_streamed() {
    for film in ["kodak_doublex","kodak_trix"] {
        let (w,h)=(31,29);let mut p=params(w,h);p.io.scan_film=true;
        p.camera.pixel_size_um_override=Some(0.8);
        p.film_render.grain.active=true;p.film_render.grain.coordinate_origin=Some([0,0]);
        p.film_render.grain.sublayers_active=true;p.film_render.grain.micro_structure=[0.8,100.];
        p.film_render.grain.blur=0.5;p.film_render.grain.blur_dye_clouds_um=0.4;
        p.film_render.dir_couplers.active=true;
        compare(film,"kodak_2302",p.clone(),w,h,3e-5);
        let pipe=Pipeline::new_with_spectral(load_profile_by_name(&data(),film).unwrap(),load_profile_by_name(&data(),"kodak_2302").unwrap(),p,&data()).unwrap();
        let density=ImageBuf::from_data(w,h,vec![from_f64(0.3);(w*h*3)as usize]);
        let output=spektrafilm_core::stages::filming::develop(&density,&pipe.film,&pipe.params,&CpuBackend);
        assert!(output.data.chunks_exact(3).all(|x|x[0]==x[1]&&x[1]==x[2]));
        let mut sequential=pipe.params.clone();sequential.film_render.grain.coordinate_origin=None;
        let output=spektrafilm_core::stages::filming::develop(&density,&pipe.film,&sequential,&CpuBackend);
        assert!(output.data.chunks_exact(3).all(|x|x[0]==x[1]&&x[1]==x[2]));
    }
}
#[test]
fn bw_spectral_response_is_not_a_rec709_desaturation() {
    let p=params(2,1);
    let pipe=Pipeline::new_with_spectral(load_profile_by_name(&data(),"kodak_doublex").unwrap(),load_profile_by_name(&data(),"kodak_2302").unwrap(),p,&data()).unwrap();
    // These saturated inputs have the same Rec.709 luminance, but expose
    // the measured stock spectrum differently before silver development.
    let image=ImageBuf::from_data(2,1,vec![from_f64(0.7152),from_f64(0.),from_f64(0.),from_f64(0.),from_f64(0.2126),from_f64(0.)]);
    let output=pipe.process(image,&CpuBackend);
    assert!((output.data[0]-output.data[3]).abs()>1e-4);
}
