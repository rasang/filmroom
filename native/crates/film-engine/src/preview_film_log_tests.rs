use super::*;
fn params()->MobileParams {
    let mut p=MobileParams::default();p.use_gpu=true;
    p.preview_source=Some(PreviewSourceIdentity{source_revision:"owned-film-log-fixture".into(),mapping_revision:"oriented-32x24-linear-v1".into(),data_revision:"fixture-scientific-v1".into()});p
}
struct Owner(i64);
impl Owner {fn new()->Self{Self(create(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data").to_str().unwrap()).unwrap())}}
impl Drop for Owner {fn drop(&mut self){destroy(self.0);}}
fn input()->Vec<f32>{(0..32*24*3).map(|i|0.001+((i*37+13)%257)as f32/370.).collect()}
fn exact(a:&[f32],b:&[f32]) {
    assert_eq!(a.iter().map(|v|v.to_bits()).collect::<Vec<_>>(),b.iter().map(|v|v.to_bits()).collect::<Vec<_>>());
    assert_eq!(linear_srgb_to_rgba8(a),linear_srgb_to_rgba8(b));
}
#[test]
fn preview_prefix_keys_bind_source_geometry_backend_and_effective_upstream() {
    let owner=Owner::new();let engine=get(owner.0).unwrap();let p=params();
    let pipe=cached_pipeline(&engine,&p,0).unwrap();
    let key=preview_film_log_key(&p,&pipe,32,24,"wgpu:fixture").unwrap();
    for change in [0,1,2,3] {
        let mut q=p.clone();match change {0=>q.preview_source.as_mut().unwrap().source_revision.push('x'),
            1=>q.preview_source.as_mut().unwrap().mapping_revision.push('x'),
            2=>q.preview_source.as_mut().unwrap().data_revision.push('x'),_=>q.film_stock.push('x')}
        assert_ne!(Some(key.clone()),preview_film_log_key(&q,&pipe,32,24,"wgpu:fixture"));
    }
    assert_ne!(Some(key.clone()),preview_film_log_key(&p,&pipe,24,32,"wgpu:fixture"));
    assert_ne!(Some(key.clone()),preview_film_log_key(&p,&pipe,32,24,"wgpu:other"));
    assert!(preview_film_log_key(&p,&pipe,32,24,"cpu").is_none());
    let mut q=p.clone();q.use_gpu=false;assert!(preview_film_log_key(&q,&pipe,32,24,"wgpu:fixture").is_none());
    // Fresh calibration, never with_params: each of these UI controls is downstream.
    for change in [0,1,2] {
        let mut q=p.clone();match change {0=>q.contrast=1.25,1=>q.warmth=0.3,_=>q.saturation=0.5}
        let fresh=cached_pipeline(&engine,&q,0).unwrap();
        assert_eq!(Some(key.clone()),preview_film_log_key(&q,&fresh,32,24,"wgpu:fixture"));
    }
}
#[test]
fn cpu_preview_stays_honestly_cold_for_scientific_suffix_changes() {
    let owner=Owner::new();let engine=get(owner.0).unwrap();let mut p=params();p.use_gpu=false;
    set_preview_cache_budget(owner.0,128*1024).unwrap();let first=render(&engine,0,&input(),32,24,&p).unwrap();
    assert!(!first.preview_cache.film_log_retained);p.contrast=1.2;
    let changed=render(&engine,0,&input(),32,24,&p).unwrap();
    assert!(!changed.preview_cache.hit && !changed.preview_cache.film_log_hit && !changed.gpu.active);
}
#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
fn assert_gpu(result:&RenderOutput) {
    assert!(result.gpu.active,"{}",result.gpu.reason);assert_eq!(result.gpu.backend,"wgpu");
    assert!(result.gpu.gpu_batches>0);assert_eq!(result.gpu.cpu_fallbacks,0);assert!(result.gpu.numeric_guard_passed);
}
#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
#[test]
fn actual_gpu_filmlog_hits_match_old_full_cold_with_scientific_controls() {
    for variant in 0..7 {
        let owner=Owner::new();let engine=get(owner.0).unwrap();let mut p=params();
        match variant {
            0=>{},
            1=>{p.diffusion_filter="black_pro_mist".into();p.print_diffusion_filter="glimmerglass".into();p.highlight_boost_ev=0.5;p.scan_blur=0.3;p.scan_sharpen_amount=0.2;},
            2=>{p.film_stock="kodak_doublex".into();p.paper_stock="kodak_2302".into();},
            3=>{p.output_route="direct".into();},
            4=>{p.film_stock="kodak_ektachrome_100".into();p.output_route="direct".into();p.scan_white_correction=true;p.scan_black_correction=true;},
            5=>{p.auto_exposure=true;p.output_color_space="Display P3".into();p.output_gamut=false;},
            _=>{p.auto_exposure=true;p.preview_metering=Some(PreviewMetering {
                algorithm:PREVIEW_METERING_ALGORITHM.into(),source_revision:"owned-film-log-fixture".into(),
                mapping_revision:"canonical-grid-v1".into(),data_revision:"fixture-scientific-v1".into(),
                owner:"fixture-owner".into(),method:p.metering.clone(),width:32,height:24,ev:0.75});},
        }
        for control in 0..3 {
            set_preview_cache_budget(owner.0,128*1024).unwrap();
            let seed=render(&engine,0,&input(),32,24,&p).unwrap();assert_gpu(&seed);
            assert!(seed.preview_cache.film_log_retained,"variant={variant} control={control}");
            assert!(!seed.preview_cache.film_log_hit);
            // Ordinary pre-existing whole resident path is the oracle.
            set_preview_cache_budget(owner.0,0).unwrap();let ordinary=render(&engine,0,&input(),32,24,&p).unwrap();
            assert_gpu(&ordinary);exact(&seed.pixels,&ordinary.pixels);
            set_preview_cache_budget(owner.0,128*1024).unwrap();render(&engine,0,&input(),32,24,&p).unwrap();
            match control {0=>p.contrast=1.2,1=>p.warmth=0.3,_=>p.saturation=0.6}
            let hit=render(&engine,0,&input(),32,24,&p).unwrap();assert_gpu(&hit);
            assert!(hit.preview_cache.film_log_hit,"variant={variant} control={control}");
            assert!(!hit.preview_cache.hit);assert_eq!(hit.preview_cache.film_log_prefix_ms,0.);
            set_preview_cache_budget(owner.0,0).unwrap();let cold=render(&engine,0,&input(),32,24,&p).unwrap();assert_gpu(&cold);
            exact(&hit.pixels,&cold.pixels);
            eprintln!("PREVIEW_FILMLOG_PASS variant={variant} control={control} cold_submits={} hit_submits={} prefix_ms={} suffix_ms={}",cold.gpu.gpu_batches,hit.gpu.gpu_batches,seed.preview_cache.film_log_prefix_ms,hit.preview_cache.film_log_suffix_ms);
        }
    }
}
#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
#[test]
fn actual_gpu_prefix_misses_on_source_bits_ae_camera_halation_and_slide_correction() {
    let owner=Owner::new();let engine=get(owner.0).unwrap();let p=params();
    for change in 0..7 {
        set_preview_cache_budget(owner.0,128*1024).unwrap();render(&engine,0,&input(),32,24,&p).unwrap();
        let mut q=p.clone();let mut values=input();match change {
            0=>values[0]=f32::from_bits(values[0].to_bits()+1),1=>q.film_exposure_ev=0.5,
            2=>q.film_format_mm=50.,3=>q.lens_blur_um=3.,4=>q.halation_amount=0.5,
            5=>q.auto_exposure=true,_=>q.diffusion_filter="black_pro_mist".into(),
        }
        let result=render(&engine,0,&values,32,24,&q).unwrap();assert_gpu(&result);assert!(!result.preview_cache.film_log_hit,"change={change}");
    }
    let mut p=p;p.film_stock="kodak_ektachrome_100".into();p.output_route="direct".into();p.scan_white_correction=true;
    set_preview_cache_budget(owner.0,128*1024).unwrap();render(&engine,0,&input(),32,24,&p).unwrap();
    p.scan_white_level=0.9;let result=render(&engine,0,&input(),32,24,&p).unwrap();assert_gpu(&result);assert!(!result.preview_cache.film_log_hit);
}

#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
#[test]
fn actual_gpu_active_prefix_copy_allows_pressure_cancel_and_epoch_reentry() {
    for cancelled in [false,true] {
        let owner=Owner::new();let engine=get(owner.0).unwrap();let mut p=params();
        set_preview_cache_budget(owner.0,128*1024).unwrap();render(&engine,0,&input(),32,24,&p).unwrap();p.contrast=1.2;
        let id=owner.0;
        FILM_LOG_CANDIDATE_TEST_HOOK.with(|slot|*slot.borrow_mut()=Some(Box::new(move||{
            // This would deadlock if a cache-reader mutex escaped into GPU work.
            set_preview_cache_budget(id,0).unwrap();if cancelled{cancel(id);}
        })));
        let result=render(&engine,0,&input(),32,24,&p);
        FILM_LOG_CANDIDATE_TEST_HOOK.with(|slot|*slot.borrow_mut()=None);
        assert_eq!(engine.preview_stage_cache.lock().unwrap().bytes(),0);
        if cancelled {assert_eq!(result.err().unwrap().code,"CANCELLED");}
        else {let result=result.unwrap();assert_gpu(&result);assert!(result.preview_cache.film_log_hit);assert!(!result.preview_cache.film_log_retained);}
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        set_preview_cache_budget(owner.0,128*1024).unwrap();let reentry=render(&engine,generation,&input(),32,24,&p).unwrap();
        assert_gpu(&reentry);assert!(!reentry.preview_cache.film_log_hit);assert!(reentry.preview_cache.film_log_retained);
    }
}

#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
#[test]
fn actual_gpu_corrected_density_hits_match_ordinary_cold_for_grain_and_finish() {
    for variant in 0..4 {
        let owner=Owner::new();let engine=get(owner.0).unwrap();let mut p=params();
        match variant {
            1=>{p.film_stock="kodak_doublex".into();p.paper_stock="kodak_2302".into();},
            2=>{p.output_route="direct".into();},
            3=>{p.diffusion_filter="black_pro_mist".into();p.print_diffusion_filter="glimmerglass".into();p.highlight_boost_ev=0.5;},
            _=>{},
        }
        for control in 0..7 {
            set_preview_cache_budget(owner.0,128*1024).unwrap();
            let seed=render(&engine,0,&input(),32,24,&p).unwrap();assert_gpu(&seed);
            assert!(seed.preview_cache.corrected_density_retained,"variant={variant}");
            let mut q=p.clone();match control {
                0=>q.grain_amount=0.5,1=>q.grain_size=1.3,2=>q.seed+=1,
                3=>q.grain_sublayers=!q.grain_sublayers,4=>q.contrast=1.2,
                5=>q.warmth=0.3,_=>{q.scan_blur=0.3;q.scan_sharpen_amount=0.2;q.output_gamut=false;},
            }
            let hit=render(&engine,0,&input(),32,24,&q).unwrap();assert_gpu(&hit);
            assert!(hit.preview_cache.corrected_density_hit,"variant={variant} control={control}");
            set_preview_cache_budget(owner.0,0).unwrap();
            let cold=render(&engine,0,&input(),32,24,&q).unwrap();assert_gpu(&cold);exact(&hit.pixels,&cold.pixels);
        }
    }
}
#[test]
fn corrected_density_mobile_key_preserves_physical_dir_and_exact_source_identity() {
    let owner=Owner::new();let engine=get(owner.0).unwrap();let p=params();
    let pipe=cached_pipeline(&engine,&p,0).unwrap();
    let key=preview_corrected_density_key(&p,&pipe,32,24,"wgpu:fixture").unwrap();
    for control in 0..5 {
        let mut q=p.clone();match control {0=>q.grain_amount=0.5,1=>q.grain_size=1.3,
            2=>q.seed+=1,3=>q.contrast=1.2,_=>q.warmth=0.3}
        let fresh=cached_pipeline(&engine,&q,0).unwrap();
        assert_eq!(Some(key.clone()),preview_corrected_density_key(&q,&fresh,32,24,"wgpu:fixture"));
    }
    let mut q=p.clone();q.saturation=0.5;let fresh=cached_pipeline(&engine,&q,0).unwrap();
    assert_ne!(Some(key.clone()),preview_corrected_density_key(&q,&fresh,32,24,"wgpu:fixture"));
    q=p.clone();q.preview_source.as_mut().unwrap().source_revision.push('x');
    assert_ne!(Some(key.clone()),preview_corrected_density_key(&q,&pipe,32,24,"wgpu:fixture"));
    assert_ne!(Some(key.clone()),preview_corrected_density_key(&p,&pipe,24,32,"wgpu:fixture"));
    assert_ne!(Some(key),preview_corrected_density_key(&p,&pipe,32,24,"wgpu:other"));
}

#[test]
fn corrected_density_reservation_contract_and_capacity_tiers() {
    assert_eq!(PREVIEW_STAGE_CACHE_ABI,3);assert_eq!(PREVIEW_CANDIDATE_BYTES_PER_PIXEL,48);
    for (side,bpp,log,density) in [(1024usize,60u64,true,true),(1152,48,true,false),(1280,36,false,false)] {
        let mut cache=crate::preview_stage_cache::StageCache::<()>::default();
        let bytes=(side*side) as u64*bpp+crate::preview_stage_cache::KEY_ALLOWANCE;
        assert!(bytes<=crate::preview_stage_cache::MAX_BYTES);cache.configure(bytes);
        assert_eq!(cache.film_log_fits(side*side*3),log);
        assert_eq!(cache.corrected_density_fits(side*side*3),density);
    }
}

#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
#[test]
fn actual_gpu_density_saturation_miss_keeps_filmlog_and_low_budget_stays_filmlog_only() {
    let owner=Owner::new();let engine=get(owner.0).unwrap();let p=params();let values=input();
    set_preview_cache_budget(owner.0,128*1024).unwrap();
    assert_gpu(&render(&engine,0,&values,32,24,&p).unwrap());
    let mut q=p.clone();q.saturation=0.5;
    let result=render(&engine,0,&values,32,24,&q).unwrap();assert_gpu(&result);
    assert!(result.preview_cache.film_log_hit);assert!(!result.preview_cache.corrected_density_hit);
    assert!(result.preview_cache.corrected_density_retained);
    set_preview_cache_budget(owner.0,0).unwrap();let cold=render(&engine,0,&values,32,24,&q).unwrap();assert_gpu(&cold);exact(&result.pixels,&cold.pixels);
    let budget=32*24*48+crate::preview_stage_cache::KEY_ALLOWANCE;
    set_preview_cache_budget(owner.0,budget).unwrap();
    let seed=render(&engine,0,&values,32,24,&p).unwrap();assert_gpu(&seed);
    assert!(seed.preview_cache.film_log_retained);assert!(!seed.preview_cache.corrected_density_retained);
    q=p.clone();q.grain_size=1.3;
    let hit=render(&engine,0,&values,32,24,&q).unwrap();assert_gpu(&hit);
    assert!(hit.preview_cache.film_log_hit);assert!(!hit.preview_cache.corrected_density_hit && !hit.preview_cache.corrected_density_retained);
    set_preview_cache_budget(owner.0,0).unwrap();let cold=render(&engine,0,&values,32,24,&q).unwrap();assert_gpu(&cold);exact(&hit.pixels,&cold.pixels);
}

#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
#[test]
fn actual_gpu_density_new_candidate_retirement_cancel_and_reentry() {
    for cancelled in [false,true] {
        let owner=Owner::new();let engine=get(owner.0).unwrap();let p=params();let id=owner.0;
        set_preview_cache_budget(id,128*1024).unwrap();
        let reached=std::rc::Rc::new(std::cell::Cell::new(false));let mark=reached.clone();
        DENSITY_CANDIDATE_TEST_HOOK.with(|slot|*slot.borrow_mut()=Some(Box::new(move||{
            mark.set(true);set_preview_cache_budget(id,0).unwrap();if cancelled{cancel(id);}
        })));
        let result=render(&engine,0,&input(),32,24,&p);
        DENSITY_CANDIDATE_TEST_HOOK.with(|slot|*slot.borrow_mut()=None);
        assert!(reached.get());assert_eq!(engine.preview_stage_cache.lock().unwrap().bytes(),0);
        if cancelled {assert_eq!(result.err().unwrap().code,"CANCELLED");}
        else {let result=result.unwrap();assert_gpu(&result);assert!(!result.preview_cache.corrected_density_retained);}
        let generation=engine.cancel_generation.load(Ordering::Acquire);
        set_preview_cache_budget(id,128*1024).unwrap();
        let result=render(&engine,generation,&input(),32,24,&p).unwrap();assert_gpu(&result);
        assert!(!result.preview_cache.corrected_density_hit);assert!(result.preview_cache.corrected_density_retained);
    }
}

#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
#[test]
fn actual_gpu_density_practical_non_square_and_positive_no_grain_match_cold() {
    for (w,h,slide) in [(512u32,384u32,false),(32,24,true)] {
        let owner=Owner::new();let engine=get(owner.0).unwrap();let mut p=params();
        if slide {p.film_stock="kodak_ektachrome_100".into();p.output_route="direct".into();p.grain_amount=0.;}
        let values:Vec<f32>=(0..w as usize*h as usize*3).map(|i|0.001+((i*37+13)%257)as f32/370.).collect();
        let budget=w as u64*h as u64*60+crate::preview_stage_cache::KEY_ALLOWANCE;
        set_preview_cache_budget(owner.0,budget).unwrap();
        let seed=render(&engine,0,&values,w,h,&p).unwrap();assert_gpu(&seed);assert!(seed.preview_cache.corrected_density_retained);
        set_preview_cache_budget(owner.0,0).unwrap();let ordinary=render(&engine,0,&values,w,h,&p).unwrap();assert_gpu(&ordinary);exact(&seed.pixels,&ordinary.pixels);
        set_preview_cache_budget(owner.0,budget).unwrap();assert_gpu(&render(&engine,0,&values,w,h,&p).unwrap());
        let mut q=p.clone();q.scan_blur=0.3;q.scan_sharpen_amount=0.2;
        let hit=render(&engine,0,&values,w,h,&q).unwrap();assert_gpu(&hit);assert!(hit.preview_cache.corrected_density_hit);
        set_preview_cache_budget(owner.0,0).unwrap();let cold=render(&engine,0,&values,w,h,&q).unwrap();assert_gpu(&cold);exact(&hit.pixels,&cold.pixels);
        eprintln!("DENSITY_PARITY width={w} height={h} slide={slide} cold_ms={} hit_ms={}",cold.elapsed_ms,hit.elapsed_ms);
    }
}

// The mobile UI exposes DIR amount, not its active flag. Exercise genuinely
// disabled DIR with a freshly calibrated core Pipeline instead of conflating
// zero amount with a disabled stage or mutating a calibrated with_params view.
#[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
#[test]
fn actual_gpu_density_disabled_dir_and_grain_preserve_full_chain_bits() {
    let gpu=full_chain_gpu().filter(|g|g.is_healthy()).expect("actual GPU required");
    let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
    for grain in [false,true] {
        let p=params();let film=load_profile_by_name(&data,&p.film_stock).unwrap();
        let paper=load_profile_by_name(&data,&p.paper_stock).unwrap();
        let mut runtime=p.preview_runtime_for_film(&film);
        runtime.film_render.dir_couplers.active=false;runtime.film_render.grain.active=grain;
        let pipe=Pipeline::new_with_spectral(film,paper,runtime,&data).unwrap();
        let image=ImageBuf::from_data(32,24,input().iter().map(|v|from_f64(*v as f64)).collect());
        let before=gpu.thread_submits();
        let cold=pipe.process_resident_borrowed_controlled(&image,gpu,||true).expect("full GPU chain required");
        let log=pipe.tap_gpu_film_log_preview_controlled(&image,gpu,||true).expect("FilmLog GPU tap required");
        let log:Vec<f32>=log.data.iter().map(|v|*v as f32).collect();
        let density=pipe.tap_gpu_corrected_density_preview_controlled(&log,32,24,gpu,||true).expect("density GPU tap required");
        let density:Vec<f32>=density.data.iter().map(|v|*v as f32).collect();
        let resumed=pipe.process_gpu_from_corrected_density_preview_controlled(&density,32,24,gpu,||true).expect("density GPU suffix required");
        assert!(gpu.thread_submits()>before);
        assert_eq!(cold.data.iter().map(|v|v.to_bits()).collect::<Vec<_>>(),resumed.data.iter().map(|v|v.to_bits()).collect::<Vec<_>>());
    }
}
