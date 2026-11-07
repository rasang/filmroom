#![cfg(all(feature = "wgpu-backend", feature = "precision-f64"))]
//! Explicit device-only regression. Run with --ignored --test-threads=1 in the
//! allocated Vulkan window; adapter absence is failure, never a passing skip.
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::{load_profile_by_name, Profile}};
use spektrafilm_gpu::{cpu_backend::CpuBackend, wgpu_backend::WgpuBackend, ComputeBackend};
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use std::path::Path;

fn parameters(paper_none: bool) -> RuntimeParams {
    let mut p = RuntimeParams::default();
    p.camera.auto_exposure = false;
    p.camera.exposure_compensation_ev = 0.;
    p.camera.lens_blur_um = 0.;
    p.camera.diffusion_filter.active = false;
    p.film_render.grain.active = false;
    p.film_render.halation.active = false;
    p.film_render.halation.boost_ev = 0.;
    p.film_render.dir_couplers.active = false;
    p.film_render.glare.active = false;
    p.enlarger.diffusion_filter.active = false;
    p.print_render.glare.active = false;
    p.scanner.lens_blur = 0.;
    p.scanner.unsharp_mask = [0., 0.];
    p.scanner.white_correction = false;
    p.scanner.black_correction = false;
    p.io.scan_film = paper_none;
    p.io.crop = false;
    p.io.upscale_factor = 1.;
    p.io.input_color_space = "sRGB".into();
    p.io.output_color_space = "sRGB".into();
    p.io.input_cctf_decoding = false;
    p.io.output_cctf_encoding = false;
    p.io.output_gamut_compress.algorithm = "off".into();
    p.settings.rgb_to_raw_method = "hanatos2025".into();
    // Compare spectral computation, not separate CPU 3D-LUT interpolation.
    p.settings.use_enlarger_lut = false;
    p.settings.use_scanner_lut = false;
    p
}
fn difference(label: &str, got: &[f64], expected: &[f64], bound: f64) {
    assert_eq!(got.len(), expected.len());
    assert!(got.iter().chain(expected).all(|v| v.is_finite()), "{label}: non-finite output");
    let max = got.iter().zip(expected).map(|(a,b)|(a-b).abs()).fold(0.,f64::max);
    let rms = (got.iter().zip(expected).map(|(a,b)|(a-b).powi(2)).sum::<f64>() / got.len() as f64).sqrt();
    eprintln!("{label}: max={max}, rms={rms}");
    // Numerical regression bounds for this pointwise fixture, not a general
    // perceptual quality requirement or measured claim about other images.
    assert!(max < bound, "{label}: max {max} exceeds regression bound {bound}");
}

#[test]
#[ignore = "requires serialized Vulkan execution window"]
fn four_media_combinations_use_resident_whole_and_bands_and_match_cpu() {
    let gpu = WgpuBackend::new().expect("Vulkan adapter required; no CPU fallback permitted");
    let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let (w,h) = (12u32,9u32);
    let pixels: Vec<f32> = (0..w*h*3).map(|i| 0.025 + ((i*17)%113) as f32 / 140.).collect();
    for film_none in [false,true] { for paper_none in [false,true] {
        let film = if film_none {Profile::omitted()} else {load_profile_by_name(&data,"kodak_portra_400").unwrap()};
        let paper = if paper_none {Profile::omitted()} else {load_profile_by_name(&data,"kodak_portra_endura").unwrap()};
        let pipe = Pipeline::new_with_spectral(film,paper,parameters(paper_none),&data).unwrap();
        let label = format!("film_none={film_none}/paper_none={paper_none}");
        let cpu = pipe.process(ImageBuf::from_data(w,h,pixels.iter().map(|v|from_f64(*v as f64)).collect()),&CpuBackend);
        let before = gpu.thread_submits();
        // This API returns None on resident refusal; it cannot silently invoke
        // Pipeline::process. Submit delta additionally proves device execution.
        let whole = pipe.process_gpu_resident_cpu_gamut_f32(&pixels,w,h,&gpu).expect("whole resident rejected");
        assert!(gpu.thread_submits() > before, "{label}: whole did not submit GPU work");
        let mut stitched = Vec::with_capacity(pixels.len());
        for y in (0..h).step_by(3) {
            let rows = (h-y).min(3);
            let start = (y*w*3) as usize;
            let end = ((y+rows)*w*3) as usize;
            let before = gpu.thread_submits();
            let band = pipe.process_gpu_resident_band_f32(&pixels[start..end],w,rows,h,y*w,&gpu,None).expect("band resident rejected");
            assert!(gpu.thread_submits()>before,"{label}: band {y} did not submit GPU work");
            assert_eq!((band.width,band.height),(w,rows));
            stitched.extend(band.data);
        }
        difference(&format!("{label}/band-whole"),&stitched,&whole.data,2e-5);
        difference(&format!("{label}/whole-cpu"),&whole.data,&cpu.data,2e-3);
        difference(&format!("{label}/band-cpu"),&stitched,&cpu.data,2e-3);
    }}
}

#[test]
#[ignore = "requires serialized Vulkan execution window"]
fn four_media_combinations_keep_global_camera_and_paper_diffusion_across_bands() {
    let gpu = WgpuBackend::new().expect("Vulkan adapter required; no passing skip");
    let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let (w,h) = (37u32,43u32);
    // Include zero and a bright impulse: the no-film reciprocal paper bridge
    // must remain finite before its whole-image scattered-field prepass.
    let pixels: Vec<f32> = (0..w*h*3).map(|i| {
        let px=i/3; if px==w*21+18 {4.} else if px%31==0 {0.}
        else {0.025+((i*17)%113) as f32/140.}
    }).collect();
    for film_none in [false,true] { for paper_none in [false,true] {
        let film = if film_none {Profile::omitted()} else {load_profile_by_name(&data,"kodak_portra_400").unwrap()};
        let paper = if paper_none {Profile::omitted()} else {load_profile_by_name(&data,"kodak_portra_endura").unwrap()};
        let mut params=parameters(paper_none);
        params.camera.pixel_size_um_override=Some(20.);
        params.camera.diffusion_filter.active=true;
        params.camera.diffusion_filter.strength=0.5;
        params.enlarger.diffusion_filter.active=!paper_none;
        params.enlarger.diffusion_filter.strength=0.5;
        let pipe=Pipeline::new_with_spectral(film,paper,params,&data).unwrap();
        let label=format!("optics film_none={film_none}/paper_none={paper_none}");
        let before=gpu.thread_submits();
        let fields=pipe.diffusion_scattered_fields_gpu(w,h,11,|y,rows| {
            pixels[(y*w*3)as usize..((y+rows)*w*3)as usize].to_vec()
        },&gpu).expect("global optical prepass rejected");
        assert!(gpu.thread_submits()>before,"{label}: prepass not executed on device");
        assert!(fields.camera.is_some(),"camera diffusion unexpectedly skipped");
        assert_eq!(fields.enlarger.is_some(),!paper_none,"paper diffusion applicability");
        for field in [&fields.camera,&fields.enlarger].into_iter().flatten() {
            assert!(field.field.iter().all(|x|x.is_finite()),"{label}: non-finite global field");
        }
        let before=gpu.thread_submits();
        let whole=pipe.process_gpu_resident_band_f32(&pixels,w,h,h,0,&gpu,Some(&fields)).expect("whole with shared fields rejected");
        assert!(gpu.thread_submits()>before);
        let halo=pipe.banded_halo_pixels(20.);
        let mut stitched=Vec::with_capacity(pixels.len());
        for y in (0..h).step_by(11) {
            let end=(y+11).min(h);
            let top=y.saturating_sub(halo);
            let bottom=(end+halo).min(h);
            let before=gpu.thread_submits();
            let band=pipe.process_gpu_resident_band_f32(&pixels[(top*w*3)as usize..(bottom*w*3)as usize],w,bottom-top,h,top*w,&gpu,Some(&fields)).expect("optical band rejected");
            assert!(gpu.thread_submits()>before,"{label}: band {y} did not submit");
            stitched.extend_from_slice(&band.data[((y-top)*w*3)as usize..((end-top)*w*3)as usize]);
        }
        difference(&format!("{label}/shared-fields-band-whole"),&stitched,&whole.data,2e-5);
        // Also exercise independently planned whole-frame optics, not merely
        // two consumers of the same field. No CPU fallback is available here.
        let before=gpu.thread_submits();
        let independent=pipe.process_gpu_resident_cpu_gamut_f32(&pixels,w,h,&gpu).expect("independent whole optics rejected");
        assert!(gpu.thread_submits()>before);
        difference(&format!("{label}/independent-whole-global-prepass"),&independent.data,&whole.data,2e-4);
    }}
}

#[test]
#[ignore = "requires serialized Vulkan execution window"]
fn every_paper_calibrates_without_film_and_reversal_keeps_positive_scan() {
    let gpu=WgpuBackend::new().expect("Vulkan required");
    let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let catalog:serde_json::Value=serde_json::from_str(&std::fs::read_to_string(data.join("profile_catalog.json")).unwrap()).unwrap();
    let pixels=vec![0.0f32,0.0,0.0,0.18,0.18,0.18,0.7,0.3,0.1,0.9,0.9,0.9];
    let mut count=0;
    for row in catalog["profiles"].as_array().unwrap() {
        if !matches!(row["kind"].as_str(),Some("paper"|"cine_print")){continue;}
        let id=row["id"].as_str().unwrap();
        let pipe=Pipeline::new_with_spectral(Profile::omitted(),load_profile_by_name(&data,id).unwrap(),parameters(false),&data).unwrap_or_else(|e|panic!("paper {id}: {e}"));
        let cpu=pipe.process(ImageBuf::from_data(4,1,pixels.iter().map(|v|from_f64(*v as f64)).collect()),&CpuBackend);
        let before=gpu.thread_submits();
        let actual=pipe.process_gpu_resident_cpu_gamut_f32(&pixels,4,1,&gpu).expect("paper resident rejected");
        assert!(gpu.thread_submits()>before);
        difference(id,&actual.data,&cpu.data,2e-3);count+=1;
    }
    assert_eq!(count,10,"update coverage when paper catalog changes");
    let pipe=Pipeline::new_with_spectral(load_profile_by_name(&data,"kodak_kodachrome_64").unwrap(),Profile::omitted(),parameters(true),&data).unwrap();
    let cpu=pipe.process(ImageBuf::from_data(4,1,pixels.iter().map(|v|from_f64(*v as f64)).collect()),&CpuBackend);
    let before=gpu.thread_submits();
    let actual=pipe.process_gpu_resident_cpu_gamut_f32(&pixels,4,1,&gpu).expect("positive film resident rejected");
    assert!(gpu.thread_submits()>before);
    difference("positive-film/no-paper",&actual.data,&cpu.data,2e-3);
}
