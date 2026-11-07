//! Numerical contracts for the new path, leaving standard-stock GPU math alone.
use super::*;
use spektrafilm_gpu::{cpu_backend::CpuBackend, wgpu_backend::WgpuBackend};
use spektrafilm_math::precision::from_f64;

#[test]
fn cinestill_gpu_film_log_matches_cpu_and_shared_tiled_headroom() {
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");
        return;
    };
    let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let film=crate::profile::load_profile_by_name(&data,"cinestill_800t").unwrap();
    let paper=crate::profile::load_profile_by_name(&data,"kodak_portra_endura").unwrap();
    let mut params=RuntimeParams::default();
    params.camera.auto_exposure=false;
    params.settings.rgb_to_raw_method="mallett2019".into();
    params.camera.pixel_size_um_override=Some(500.); // All PSF components use CPU FIR.
    params.io.input_cctf_decoding=false;
    params.camera.diffusion_filter.active=false;
    params.enlarger.diffusion_filter.active=false;
    params.scanner.unsharp_mask=[0.,0.];
    let hp=&mut params.film_render.halation;
    hp.back_reflection=true; hp.scatter_amount=0.;
    hp.halation_strength=spektrafilm_math::halation::BACK_REFLECTION_STRENGTH;
    let (w,h)=(31u32,27u32);
    let mut values=vec![0.04f32;(w*h*3)as usize];
    for (x,y,v) in [(3,4,4.),(15,13,2.),(29,25,8.)] {
        let i=((y*w+x)*3)as usize; values[i..i+3].copy_from_slice(&[v,v*0.7,v*0.4]);
    }
    let image=ImageBuf::from_data(w,h,values.iter().map(|v|from_f64(*v as f64)).collect());
    for boost in [0.,0.75] {
        params.film_render.halation.boost_ev=boost;
        let pipe=Pipeline::new_with_spectral(film.clone(),paper.clone(),params.clone(),&data).unwrap();
        let actual=pipe.tap_gpu_film_log_preview_controlled(&image,&gpu,||true).unwrap();
        let expected=stages::filming::expose(&image,&pipe.film,&pipe.params,&CpuBackend,
            pipe.spectral.tc_lut.as_ref(),pipe.spectral.mallett_core.as_ref(),select_illuminant(&pipe.spectral.front_illuminant),1.);
        let max_error=actual.data.iter().zip(&expected.data).map(|(a,b)|(*a as f64-*b as f64).abs()).fold(0.,f64::max);
        assert!(max_error<0.003,"CPU/GPU FilmLog mismatch boost={boost}: {max_error}");
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(w,h,11,10,|x,y,tw,th| {
            let mut out=Vec::new();
            for yy in y..y+th { out.extend_from_slice(&values[((yy*w+x)*3)as usize..((yy*w+x+tw)*3)as usize]); }
            Some(out)
        },||true,&gpu).unwrap();
        let full=TileContext{full_width:w,full_height:h,x:0,y:0};
        let tiled=pipe.tap_gpu_film_log_tile_f32_controlled(&values,w,h,full,&gpu,Some(&fields),||true).unwrap();
        assert_eq!(actual.data,tiled.data,"shared full-frame max must equal resident reduction");
        let halo=pipe.gpu_split_halo_pixels(500.).0;
        for (x,y,cw,ch) in [(0u32,0u32,11u32,10u32),(22,20,9,7)] {
            let tx=x.saturating_sub(halo);let ty=y.saturating_sub(halo);
            let tw=(x+cw+halo).min(w)-tx;let th=(y+ch+halo).min(h)-ty;
            let mut input=Vec::new();
            for yy in ty..ty+th { input.extend_from_slice(&values[((yy*w+tx)*3)as usize..((yy*w+tx+tw)*3)as usize]); }
            let out=pipe.tap_gpu_film_log_tile_f32_controlled(&input,tw,th,TileContext{x:tx,y:ty,..full},&gpu,Some(&fields),||true).unwrap();
            for yy in y..y+ch { for xx in x..x+cw { for c in 0..3 {
                let a=out.data[(((yy-ty)*tw+xx-tx)*3+c)as usize];
                let b=actual.data[((yy*w+xx)*3+c)as usize];
                assert!((a-b).abs()<1e-5,"CineStill tile seam at {xx},{yy},{c}");
            }}}
        }
    }
}

#[test]
fn cinestill_35mm_gpu_reports_iir_fir_error_and_preserves_tile_halos() {
    let Some(gpu)=WgpuBackend::new() else {
        assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"),"Required GPU unavailable");
        return;
    };
    let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    let film=crate::profile::load_profile_by_name(&data,"cinestill_800t").unwrap();
    let paper=crate::profile::load_profile_by_name(&data,"kodak_2383").unwrap();
    let mut p=RuntimeParams::default();
    p.camera.auto_exposure=false;p.camera.film_format_mm=35.;
    p.settings.rgb_to_raw_method="mallett2019".into();
    p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;
    p.camera.diffusion_filter.active=false;p.enlarger.diffusion_filter.active=false;
    p.film_render.grain.active=false;p.film_render.dir_couplers.active=false;
    p.print_render.glare.active=false;p.scanner.unsharp_mask=[0.,0.];
    p.film_render.halation.back_reflection=true;p.film_render.halation.scatter_amount=0.;
    p.film_render.halation.halation_strength=spektrafilm_math::halation::BACK_REFLECTION_STRENGTH;
    let (w,h)=(512u32,256u32);
    let pitch=35000. / w as f32;
    assert_eq!(stages::filming::pixel_size_um(35.,w,h),pitch);
    let pipe=Pipeline::new_with_spectral(film,paper,p,&data).unwrap();
    let halo=pipe.gpu_split_halo_pixels(pitch).0;
    let widest=spektrafilm_math::halation::back_reflection_components(120.,1.,1.,pitch as f64)
        .iter().flat_map(|c|c.sigma_px).fold(0.,f64::max);
    assert!(halo >= (3.*widest).ceil() as u32);
    for (name,left) in [("center",248u32),("left_edge",0u32)] {
        let is_lamp=|x:u32,y:u32| x>=left && x<left+16 && y>=96 && y<160;
        let values:Vec<f32>=(0..w*h*3).map(|i|if is_lamp(i/3%w,i/3/w){1.}else{0.002}).collect();
        let image=ImageBuf::from_data(w,h,values.iter().map(|v|from_f64(*v as f64)).collect());
        let actual=pipe.tap_gpu_film_log_preview_controlled(&image,&gpu,||true).unwrap();
        let expected=stages::filming::expose(&image,&pipe.film,&pipe.params,&CpuBackend,
            pipe.spectral.tc_lut.as_ref(),pipe.spectral.mallett_core.as_ref(),select_illuminant(&pipe.spectral.front_illuminant),1.);
        assert!(actual.data.iter().chain(&expected.data).all(|x|x.is_finite()));
        let cpu:Vec<f64>=expected.data.iter().map(|v|10f64.powf(*v as f64)).collect();
        let gpu_raw:Vec<f64>=actual.data.iter().map(|v|10f64.powf(*v as f64)).collect();
        let peak=cpu.iter().copied().fold(0.,f64::max);
        let linear_error:Vec<f64>=cpu.iter().zip(&gpu_raw).map(|(a,b)|(a-b).abs()).collect();
        let normalized_max=linear_error.iter().copied().fold(0.,f64::max)/peak;
        let normalized_rms=(linear_error.iter().map(|v|v*v).sum::<f64>()/linear_error.len() as f64).sqrt()/peak;
        let mut log_error:Vec<f64>=actual.data.iter().zip(&expected.data).map(|(a,b)|(*a as f64-*b as f64).abs()).collect();
        log_error.sort_by(f64::total_cmp);
        let halo_energy=|raw:&[f64],c:usize| {
            let background=raw.chunks_exact(3).map(|p|p[c]).fold(f64::INFINITY,f64::min);
            raw.chunks_exact(3).enumerate().filter(|(i,_)|!is_lamp(*i as u32%w,*i as u32/w))
                .map(|(_,p)|(p[c]-background).max(0.)).sum::<f64>()
        };
        let relative_energy:[f64;3]=std::array::from_fn(|c| {
            let a=halo_energy(&cpu,c);(halo_energy(&gpu_raw,c)-a).abs()/a.max(1e-12)
        });
        eprintln!("CINESTILL_GPU_35MM {name} pitch_um={pitch} halo={halo} max_linear_over_peak={normalized_max} rms_linear_over_peak={normalized_rms} halo_energy_relative_rgb={relative_energy:?} log10_max={} log10_p99={}",
            log_error.last().unwrap(),log_error[log_error.len()*99/100]);
        // Fixed before measurement: 3σ drops ~0.27% Gaussian mass per axis;
        // 1% peak-relative allowance includes that plus the existing IIR fit.
        // Deep-shadow log errors are reported, never hidden by a broad log gate.
        assert!(normalized_max<=0.01,"{name}: CPU IIR / GPU FIR discrepancy exceeds 1% peak: {normalized_max}");
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(w,h,127,67,|x,y,tw,th| {
            let mut out=Vec::new();for yy in y..y+th {out.extend_from_slice(&values[((yy*w+x)*3)as usize..((yy*w+x+tw)*3)as usize]);}Some(out)
        },||true,&gpu).unwrap();
        // A core through the halo edge, with full-source dimensions and pitch.
        let (x,y,cw,ch)=(left.saturating_add(8),100u32,37u32,43u32);
        let tx=x.saturating_sub(halo);let ty=y.saturating_sub(halo);
        let tw=(x+cw+halo).min(w)-tx;let th=(y+ch+halo).min(h)-ty;
        let mut input=Vec::new();for yy in ty..ty+th {input.extend_from_slice(&values[((yy*w+tx)*3)as usize..((yy*w+tx+tw)*3)as usize]);}
        let tile=pipe.tap_gpu_film_log_tile_f32_controlled(&input,tw,th,
            TileContext{full_width:w,full_height:h,x:tx,y:ty},&gpu,Some(&fields),||true).unwrap();
        let mut seam=0f64;
        for yy in y..y+ch {for xx in x..x+cw {for c in 0..3 {
            seam=seam.max((tile.data[(((yy-ty)*tw+xx-tx)*3+c)as usize] as f64-actual.data[((yy*w+xx)*3+c)as usize] as f64).abs());
        }}}
        assert!(seam<1e-5,"{name}: full-grid geometry or halo mismatch {seam}");
    }
}
