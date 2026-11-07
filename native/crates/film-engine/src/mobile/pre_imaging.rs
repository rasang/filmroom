//! A literal linear scene + film-edge composite, prior to all photographic stages.
//! The aperture controls physical pitch. Only the separately owned scene meters AE.
use super::*;
use std::io::Read;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub struct PreImaging {
    pub version:u32,
    pub frame_width:u32, pub frame_height:u32,
    pub photo_width:u32, pub photo_height:u32,
    pub metering_width:u32, pub metering_height:u32,
    /// An operational private file location, never a diagnostic or cache identity.
    #[serde(default, skip_serializing)]
    pub metering_path:Option<String>,
}
fn invalid()->EngineError {EngineError::new("INVALID_PARAMS","Invalid pre-imaging composite contract")}
impl PreImaging {
    pub(super) fn validate(&self,p:&MobileParams)->Result<()> {
        if self.version!=1 || [self.frame_width,self.frame_height,self.photo_width,self.photo_height,self.metering_width,self.metering_height].iter().any(|&n|n==0||n>40000)
            || self.photo_width>self.frame_width || self.photo_height>self.frame_height {return Err(invalid());}
        match p.mode.as_str() {
            "preview" if self.metering_path.is_none() && (!p.auto_exposure || p.preview_metering.is_some())=>{},
            "export" if self.metering_path.as_ref().is_some_and(|s|!s.is_empty()&&s.len()<=4096&&Path::new(s).is_absolute())=>{},
            _=>return Err(invalid()),
        }
        Ok(())
    }
    pub(super) fn check_frame(&self,w:u32,h:u32)->Result<()> {
        if (w,h)!=(self.frame_width,self.frame_height) {return Err(EngineError::new("INVALID_DIMENSIONS","Composite frame does not match supplied pixels"));} Ok(())
    }
    /// Verify every source sample, not merely the sparse AE grid. Resolve EV
    /// before any GPU reuse or CPU/GPU checkpoint lookup. Its exact f32 value
    /// enters RuntimeParams and therefore all scientific cache identities.
    pub(super) fn resolve_export_meter(&self,path:&Path,p:&MobileParams,mut cancelled:impl FnMut()->bool)->Result<Option<f32>> {
        let io=|_:std::io::Error|EngineError::new("INVALID_INPUT_FILE","Invalid scene metering source");
        let mut file=std::fs::File::open(path).map_err(io)?;
        let meta=file.metadata().map_err(io)?;
        let expected=self.metering_width as u64*self.metering_height as u64*12;
        if !meta.is_file() || meta.len()!=expected {return Err(io(std::io::Error::from(std::io::ErrorKind::InvalidData)));}
        let mut remaining=expected;let mut buffer=vec![0u8;32768*12];
        while remaining>0 {
            if cancelled(){return Err(EngineError::new("CANCELLED","Scene metering cancelled"));}
            let len=remaining.min(buffer.len() as u64) as usize;
            file.read_exact(&mut buffer[..len]).map_err(io)?;
            if buffer[..len].chunks_exact(4).any(|b|!f32::from_le_bytes(b.try_into().unwrap()).is_finite()) {return Err(EngineError::new("INVALID_PIXELS","Nonfinite scene metering sample"));}
            remaining-=len as u64;
        }
        if !p.auto_exposure {return Ok(None);}
        let matrix=spektrafilm_core::stages::filming::input_colorspace_to_xyz("sRGB");
        let ev=crate::streamed_global::auto_exposure_ev(path,self.metering_width,self.metering_height,&p.metering,&matrix,&mut cancelled)
            .map_err(|e|if e.kind()==std::io::ErrorKind::Interrupted {EngineError::new("CANCELLED","Scene metering cancelled")}else{io(e)})?;
        if !ev.is_finite() || !2.0f32.powf(ev).is_finite() || !2.0f32.powf(ev+p.film_exposure_ev as f32).is_finite() {return Err(invalid());}
        Ok(Some(ev))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn contract()->PreImaging {PreImaging{version:1,frame_width:40,frame_height:32,photo_width:32,photo_height:24,metering_width:32,metering_height:24,metering_path:None}}
    fn preview()->MobileParams {let mut p=MobileParams::default();p.pre_imaging=Some(contract());p}
    fn meter(ev:f32)->PreviewMetering {PreviewMetering{algorithm:PREVIEW_METERING_ALGORITHM.into(),source_revision:"scene".into(),mapping_revision:"scene-mapping".into(),data_revision:"data".into(),owner:"owner".into(),method:"center_weighted".into(),width:32,height:24,ev}}
    struct Temp(PathBuf);
    impl Temp {
        fn new()->Self {static N:AtomicU64=AtomicU64::new(0);let path=std::env::temp_dir().join(format!("film-preimaging-{}-{}",std::process::id(),N.fetch_add(1,Ordering::Relaxed)));std::fs::create_dir(&path).unwrap();Self(path)}
        fn write(&self,values:&[f32])->PathBuf {let path=self.0.join("scene.f32");let bytes:Vec<u8>=values.iter().flat_map(|v|v.to_le_bytes()).collect();std::fs::write(&path,bytes).unwrap();path}
    }
    impl Drop for Temp {fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
    #[test] fn pre_imaging_parse_validation_and_private_path() {
        let json=r#"{"mode":"export","preImaging":{"version":1,"frameWidth":40,"frameHeight":32,"photoWidth":32,"photoHeight":24,"meteringWidth":32,"meteringHeight":24,"meteringPath":"/private/scene.f32"}}"#;
        let p=MobileParams::parse(json).unwrap();assert!(!serde_json::to_string(&p).unwrap().contains("/private"));
        for invalid in [json.replace("\"version\":1","\"version\":2"),json.replace("\"photoWidth\":32","\"photoWidth\":41"),json.replace("\"meteringHeight\":24","\"meteringHeight\":0"),json.replace("/private/scene.f32","relative"),json.replace("\"mode\":\"export\"","\"mode\":\"preview\"")] {assert!(MobileParams::parse(&invalid).is_err());}
        assert!(plan_export(40,32,&p).is_ok());assert!(plan_export(32,24,&p).is_err());
        assert_eq!(plan_export(40,32,&p).unwrap().input_bytes,40*32*12);
        let mut p=preview();p.auto_exposure=true;assert!(p.validate().is_err());p.preview_metering=Some(meter(1.));p.validate().unwrap();
    }
    #[test] fn pre_imaging_pitch_identity_and_legacy_absence() {
        let p=preview();let runtime=p.runtime();assert_eq!(runtime.camera.pixel_size_um_override,Some(35000./32.));
        let mut larger=p.clone();larger.pre_imaging.as_mut().unwrap().frame_width=60;assert_eq!(runtime.camera.pixel_size_um_override,larger.runtime().camera.pixel_size_um_override);
        assert_eq!(runtime.camera.pre_imaging_version,Some(1));
        let old=MobileParams::default();assert!(old.runtime().camera.pixel_size_um_override.is_none());
        assert!(!serde_json::to_string(&old).unwrap().contains("preImaging"));assert!(!serde_json::to_string(&old.runtime()).unwrap().contains("pre_imaging_version"));
        let mut matching_legacy=old.runtime();matching_legacy.camera.pixel_size_um_override=runtime.camera.pixel_size_um_override;
        assert_ne!(serde_json::to_string(&runtime).unwrap(),serde_json::to_string(&matching_legacy).unwrap());
    }
    #[test] fn pre_imaging_scene_ev_border_invariance_and_preview_parity() {
        let dir=Temp::new();let values:Vec<f32>=(0..32*24*3).map(|i|0.08+(i%29) as f32/100.).collect();let path=dir.write(&values);
        let mut p=preview();p.auto_exposure=true;let frame=p.pre_imaging.as_ref().unwrap();
        let ev=frame.resolve_export_meter(&path,&p,||false).unwrap().unwrap();
        assert_eq!(ev.to_bits(),measure_preview(&values,32,24,&p.metering).unwrap().to_bits());
        let mut frame2=frame.clone();frame2.frame_width=100;frame2.frame_height=80;
        assert_eq!(Some(ev),frame2.resolve_export_meter(&path,&p,||false).unwrap());
        p.preview_metering=Some(meter(ev));assert_eq!(p.apply_preview_metering(p.runtime()).camera.preview_metered_ev,Some(ev));
        let before=serde_json::to_string(&p.apply_preview_metering(p.runtime())).unwrap();
        p.preview_metering.as_mut().unwrap().ev=ev+1.;assert_ne!(before,serde_json::to_string(&p.apply_preview_metering(p.runtime())).unwrap());
    }
    #[test] fn pre_imaging_source_size_finite_and_cancellation() {
        let dir=Temp::new();let mut p=preview();p.auto_exposure=true;let frame=p.pre_imaging.as_ref().unwrap();
        let path=dir.write(&vec![0.18;32*24*3]);assert_eq!(frame.resolve_export_meter(&path,&p,||true).unwrap_err().code,"CANCELLED");
        dir.write(&[0.18]);assert_eq!(frame.resolve_export_meter(&path,&p,||false).unwrap_err().code,"INVALID_INPUT_FILE");
        let mut values=vec![0.18;32*24*3];values[17]=f32::NAN;dir.write(&values);assert_eq!(frame.resolve_export_meter(&path,&p,||false).unwrap_err().code,"INVALID_PIXELS");
        p.auto_exposure=false;assert_eq!(frame.resolve_export_meter(&path,&p,||false).unwrap_err().code,"INVALID_PIXELS");
    }
    #[cfg(feature="precision-f64")]
    #[test] fn pre_imaging_cpu_streamed_matches_whole_scene_metering() {
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let dir=Temp::new();let source=vec![0.08f32;32*24*3];let source_path=dir.write(&source);
        let mut p=preview();p.auto_exposure=true;p.use_gpu=false;p.grain_amount=0.;p.halation_amount=0.;p.scatter_amount=0.;p.dir_amount=0.;p.output_gamut=false;
        let frame=p.pre_imaging.as_ref().unwrap();let ev=frame.resolve_export_meter(&source_path,&p,||false).unwrap().unwrap();
        p.preview_metering=Some(meter(ev));
        let film=load_profile_by_name(&data,&p.film_stock).unwrap();
        let mut runtime=p.preview_runtime_for_film(&film);runtime.film_render.grain.coordinate_origin=Some([0,0]);
        let composite:Vec<f32>=(0..40*32*3).map(|i|{let x=i/3%40;let y=i/3/40;if (4..36).contains(&x)&&(4..28).contains(&y){0.08}else{1.0}}).collect();
        let composite_path=dir.0.join("composite.f32");std::fs::write(&composite_path,composite.iter().flat_map(|v|f32::to_le_bytes(*v)).collect::<Vec<_>>()).unwrap();
        assert!((ev-measure_preview(&composite,40,32,&p.metering).unwrap()).abs()>0.1);
        let paper=load_profile_by_name(&data,&p.paper_stock).unwrap();
        let pipe=Pipeline::new_with_spectral(film,paper,runtime.clone(),&data).unwrap();
        let key=pipe.gpu_film_log_prefix_key("pre-imaging-test").unwrap();
        let mut changed=runtime.clone();changed.camera.preview_metered_ev=Some(ev+1.);
        assert_ne!(key,pipe.clone().with_params(changed).gpu_film_log_prefix_key("pre-imaging-test").unwrap());
        let mut legacy=runtime.clone();legacy.camera.pre_imaging_version=None;
        assert_ne!(key,pipe.clone().with_params(legacy).gpu_film_log_prefix_key("pre-imaging-test").unwrap());
        let image=ImageBuf::from_data(40,32,composite.iter().map(|v|from_f64(*v as f64)).collect());
        let expected=pipe.process(image,&spektrafilm_gpu::cpu_backend::CpuBackend);
        let output=dir.0.join("output.f32");
        let metrics=crate::streamed::export_file(&data,&p.film_stock,&p.paper_stock,runtime,&composite_path,&output,&dir.0.join("scratch"),40,32,384,0.,||false,|_,_,_|{}).unwrap();
        assert_eq!(metrics.metered_ev,Some(ev));
        let bytes=std::fs::read(output).unwrap();let actual:Vec<f32>=bytes.chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect();
        let max=actual.iter().zip(&expected.data).map(|(a,b)|(*a as f64-*b as f64).abs()).fold(0.,f64::max);
        assert!(max<5e-6,"whole/streamed composite error {max}");
    }

    #[test] fn pre_imaging_checkpoint_requires_exact_same_job_owned_files() {
        let dir=Temp::new();let files=dir.0.join("files");let data=files.join("engine-data");
        let job=files.join("export-jobs/12345678-1234-1234-1234-123456789abc");
        std::fs::create_dir_all(&job).unwrap();std::fs::create_dir_all(&data).unwrap();
        let engine=Engine {data_dir:data,cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()};
        let scene=job.join("input.rgb");let composite=job.join("pre-imaging-input.rgb");let output=job.join("result.rgb");let checkpoint=job.join("checkpoint");
        std::fs::write(&scene,[]).unwrap();std::fs::write(&composite,[]).unwrap();
        let requested=checkpoint.to_str().unwrap();let mut frame=contract();frame.metering_path=Some(scene.to_str().unwrap().into());
        assert!(private_checkpoint_path(&engine,&scene,&output,requested,None).is_ok());
        assert!(private_checkpoint_path(&engine,&composite,&output,requested,Some(&frame)).is_ok());
        assert!(private_checkpoint_path(&engine,&composite,&output,requested,None).is_err());
        assert!(private_checkpoint_path(&engine,&scene,&output,requested,Some(&frame)).is_err());
        assert!(private_checkpoint_path(&engine,&job.join("arbitrary.rgb"),&output,requested,Some(&frame)).is_err());
        frame.version=2;assert!(private_checkpoint_path(&engine,&composite,&output,requested,Some(&frame)).is_err());frame.version=1;
        frame.metering_path=Some(composite.to_str().unwrap().into());assert!(private_checkpoint_path(&engine,&composite,&output,requested,Some(&frame)).is_err());
        let other=files.join("input.rgb");std::fs::write(&other,[]).unwrap();frame.metering_path=Some(other.to_str().unwrap().into());
        assert!(private_checkpoint_path(&engine,&composite,&output,requested,Some(&frame)).is_err());
        frame.metering_path=Some(scene.to_str().unwrap().into());std::fs::remove_file(&scene).unwrap();
        assert!(private_checkpoint_path(&engine,&composite,&output,requested,Some(&frame)).is_err());
        #[cfg(unix)] {
            std::os::unix::fs::symlink(&other,&scene).unwrap();
            assert!(private_checkpoint_path(&engine,&composite,&output,requested,Some(&frame)).is_err());
        }
    }

}
