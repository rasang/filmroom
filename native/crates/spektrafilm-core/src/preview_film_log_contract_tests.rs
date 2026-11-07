use super::*;
use spektrafilm_gpu::{ChainImage, ChainStart, ChainStop, ComputeBackend, FilmChainParams, Lut3D};
use spektrafilm_math::precision::from_f64;
use std::sync::Mutex;
#[derive(Default)]
struct Probe { ranges: Mutex<Vec<(ChainStart,ChainStop,bool,bool,bool)>>, effects: Mutex<Vec<(bool,bool)>> }
impl ComputeBackend for Probe {
    fn colorspace_convert(&self,i:&ImageBuf,_:&[[f32;3];3])->ImageBuf{i.clone()}
    fn cctf_encode_srgb(&self,i:&ImageBuf)->ImageBuf{i.clone()}
    fn cctf_decode_srgb(&self,i:&ImageBuf)->ImageBuf{i.clone()}
    fn gaussian_blur(&self,i:&ImageBuf,_:f32)->ImageBuf{i.clone()}
    fn table_lookup(&self,i:&ImageBuf,_:&[f32],_:&[[f32;3]])->ImageBuf{i.clone()}
    fn lut3d_interp(&self,i:&ImageBuf,_:&Lut3D)->ImageBuf{i.clone()}
    fn name(&self)->&str{"preview-film-log-probe"}
    fn try_run_film_chain(&self,p:&FilmChainParams<'_>)->Option<ImageBuf>{
        self.ranges.lock().unwrap().push((p.start_at,p.stop_after,p.diffusion.is_some(),p.enlarger_diffusion.is_some(),p.defer_gamut_to_cpu));
        self.effects.lock().unwrap().push((p.dir_couplers.is_some(),p.grain.is_some()));
        assert!(p.diffusion_scattered.is_none() && p.enlarger_scattered.is_none());
        Some(match p.image { ChainImage::F64(i)=>i.clone(),ChainImage::F32(v,w,h)=>ImageBuf::from_data(w,h,v.iter().map(|v|from_f64(*v as f64)).collect()) })
    }
}
fn pipe()->Pipeline {
    let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let film=crate::profile::load_profile_by_name(&data,"kodak_portra_400").unwrap();
    let paper=crate::profile::load_profile_by_name(&data,"kodak_portra_endura").unwrap();
    let mut p=RuntimeParams::default();p.settings.rgb_to_raw_method="mallett2019".into();
    p.camera.auto_exposure=false;p.camera.pixel_size_um_override=Some(5.);
    Pipeline::new_with_spectral(film,paper,p,&data).unwrap()
}
#[test]
fn whole_preview_ranges_preserve_internal_fields_and_preview_gamut_contract() {
    let mut p=pipe();p.params.camera.diffusion_filter.active=true;p.params.enlarger.diffusion_filter.active=true;
    // AE without a shared override is measured from the original whole RGB only.
    p.params.camera.auto_exposure=true;
    let backend=Probe::default();let image=ImageBuf::from_data(32,24,vec![from_f64(0.18);32*24*3]);
    let prefix=p.tap_gpu_film_log_preview_controlled(&image,&backend,||true).unwrap();
    let log:Vec<f32>=prefix.data.iter().map(|v|*v as f32).collect();
    assert!(p.process_gpu_from_film_log_preview_controlled(&log,32,24,&backend,||true).is_some());
    let calls=backend.ranges.lock().unwrap();
    assert_eq!(calls[0],(ChainStart::Rgb,ChainStop::FilmLog,true,false,false));
    assert_eq!(calls[1],(ChainStart::FilmLog,ChainStop::Full,false,true,false));
}
#[test]
fn whole_preview_validation_cancellation_and_source_shape_do_not_submit() {
    let mut p=pipe();let backend=Probe::default();let image=ImageBuf::from_data(2,2,vec![from_f64(0.1);12]);
    assert!(p.tap_gpu_film_log_preview_controlled(&image,&backend,||false).is_none());
    assert!(p.process_gpu_from_film_log_preview_controlled(&[0.;12],2,2,&backend,||false).is_none());
    for (values,w,h) in [(vec![0.;11],2,2),(vec![f32::NAN;12],2,2),(vec![],0,2)] {
        assert!(p.process_gpu_from_film_log_preview_controlled(&values,w,h,&backend,||true).is_none());
    }
    p.params.io.upscale_factor=0.5;assert!(p.tap_gpu_film_log_preview_controlled(&image,&backend,||true).is_none());
    assert!(backend.ranges.lock().unwrap().is_empty());
}
#[test]
fn exact_prefix_key_covers_every_camera_and_halation_field_and_rejects_nonfinite() {
    let p=pipe();let key=p.gpu_film_log_prefix_key("verified-data-a").unwrap();
    assert_ne!(Some(key.clone()),p.gpu_film_log_prefix_key("verified-data-b"));
    assert!(p.gpu_film_log_prefix_key("").is_none());
    fn change(value:&mut serde_json::Value) {
        match value {
            serde_json::Value::Bool(v)=>*v=!*v,
            serde_json::Value::Number(n)=>*value=serde_json::json!(n.as_f64().unwrap()+0.25),
            serde_json::Value::String(s)=>s.push('x'),
            serde_json::Value::Array(values)=>change(&mut values[0]),
            serde_json::Value::Object(values)=>change(values.values_mut().next().unwrap()),
            serde_json::Value::Null=>*value=serde_json::json!(0.25),
        }
    }
    for group in ["camera","halation"] {
        let baseline=if group=="camera"{serde_json::to_value(&p.params.camera).unwrap()}else{serde_json::to_value(&p.params.film_render.halation).unwrap()};
        for name in baseline.as_object().unwrap().keys() {
            // Integer bounce count must stay integral when deserialized.
            let mut value=baseline.clone();if name=="halation_n_bounces"{value[name]=serde_json::json!(4)}else{change(&mut value[name]);}
            let mut q=p.clone();if group=="camera"{q.params.camera=serde_json::from_value(value).unwrap()}else{q.params.film_render.halation=serde_json::from_value(value).unwrap()}
            assert_ne!(Some(key.clone()),q.gpu_film_log_prefix_key("verified-data-a"),"omitted {group}.{name}");
        }
    }
    let mut q=p.clone();q.params.camera.exposure_compensation_ev=-0.;
    assert_ne!(Some(key.clone()),q.gpu_film_log_prefix_key("verified-data-a"));
    q.params.camera.exposure_compensation_ev=f32::NAN;assert!(q.gpu_film_log_prefix_key("verified-data-a").is_none());
    q=p.clone();q.params.film_render.halation.halation_amount=f64::INFINITY;assert!(q.gpu_film_log_prefix_key("verified-data-a").is_none());
}

#[test]
fn corrected_density_ranges_preserve_grain_boundary_and_suffix_order() {
    let mut p=pipe();p.params.enlarger.diffusion_filter.active=true;
    p.params.film_render.dir_couplers.active=true;p.params.film_render.grain.active=true;
    let backend=Probe::default();let values=vec![0.18;32*24*3];
    let density=p.tap_gpu_corrected_density_preview_controlled(&values,32,24,&backend,||true).unwrap();
    assert_eq!(density.data.iter().map(|v|*v as f32).collect::<Vec<_>>(),values);
    assert!(p.process_gpu_from_corrected_density_preview_controlled(&values,32,24,&backend,||true).is_some());
    let calls=backend.ranges.lock().unwrap();
    assert_eq!(calls[0],(ChainStart::FilmLog,ChainStop::CorrectedDensity,false,false,false));
    assert_eq!(calls[1],(ChainStart::CorrectedDensity,ChainStop::Full,false,true,false));
    assert_eq!(*backend.effects.lock().unwrap(),vec![(true,false),(false,true)]);
}
#[test]
fn corrected_density_keys_include_resolved_curves_gamma_and_dir_not_grain() {
    let p=pipe();let key=p.gpu_corrected_density_prefix_key("data").unwrap();
    let mut q=p.clone();q.params.film_render.grain.active=!q.params.film_render.grain.active;
    assert_eq!(Some(key.clone()),q.gpu_corrected_density_prefix_key("data"));
    q=p.clone();q.params.film_render.density_curve_gamma+=0.1;
    assert_ne!(Some(key.clone()),q.gpu_corrected_density_prefix_key("data"));
    q=p.clone();q.params.film_render.dir_couplers.amount+=0.1;
    assert_ne!(Some(key.clone()),q.gpu_corrected_density_prefix_key("data"));
    q=p.clone();q.film.data.density_curves[0][0]+=0.1;
    assert_ne!(Some(key.clone()),q.gpu_corrected_density_prefix_key("data"));
    assert!(p.gpu_corrected_density_prefix_key("").is_none());
}
#[test]
fn corrected_density_validation_and_cancel_do_not_submit() {
    let p=pipe();let backend=Probe::default();
    for finish in [false,true] {
        for (values,w,h) in [(vec![0.;11],2,2),(vec![f32::NAN;12],2,2),(vec![],0,2)] {
            assert!(p.corrected_density_preview_range(&values,w,h,&backend,finish,||true).is_none());
        }
        assert!(p.corrected_density_preview_range(&[0.;12],2,2,&backend,finish,||false).is_none());
    }
    assert!(backend.ranges.lock().unwrap().is_empty());
}
