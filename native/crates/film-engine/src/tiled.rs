//! Bounded-memory original-size exporter. Input/output are local RGB f32 files.
//! Spectral tables accelerate only internal integrals; all film effects remain.
//! Finite halo approximates the reference IIR's infinite support and is tested
//! against whole-frame output. Coordinate RNG preserves pattern across tiles.
use std::{fs::{File,OpenOptions}, io::{Read,Write,Seek,SeekFrom}, path::Path, time::Instant};
use serde::Serialize;
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::load_profile_by_name};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf,precision::from_f64};
use crate::mobile::{EngineError,Result};

struct PartialOutput<'a> {path:&'a Path,committed:bool}
impl Drop for PartialOutput<'_> {fn drop(&mut self) {if !self.committed {let _=std::fs::remove_file(self.path);}}}

#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct ExportMetrics {pub width:u32,pub height:u32,pub tile_size:u32,pub halo:u32,pub tile_count:u64,pub peak_tile_pixels:u64,pub expanded_tile_budget_pixels:u64,pub spectral_scratch_max_bytes:u64,pub estimated_peak_native_bytes:u64,pub memory_estimate_policy:&'static str,pub setup_ms:f64,pub elapsed_ms:f64,pub precision:&'static str,pub grain_mode:&'static str,pub halo_policy:&'static str}

pub fn halo_pixels(params:&RuntimeParams,pitch:f32)->u32 {
    let mut sigma=0.0f64;
    let h=&params.film_render.halation;
    if h.active {
        if h.scatter_amount>0. {sigma+=h.scatter_core_um.iter().copied().fold(0.,f64::max).max(h.scatter_tail_um.iter().copied().fold(0.,f64::max)*2.7684)*h.scatter_spatial_scale;}
        if h.halation_amount>0. {sigma+=h.halation_first_sigma_um.iter().copied().fold(0.,f64::max)*h.halation_spatial_scale*(h.halation_n_bounces as f64).sqrt();}
    }
    let d=&params.film_render.dir_couplers;
    if d.active && d.diffusion_size_um>0. {sigma+=d.diffusion_size_um.max(if d.diffusion_tail_weight>0. {d.diffusion_tail_um*2.7684}else{0.});}
    let g=&params.film_render.grain;
    let pixel_sigma=if g.active { g.blur as f64 + 4.0*(g.agx_particle_area_um2.sqrt()/pitch as f64)*g.blur_dye_clouds_um as f64 + g.micro_structure[0] as f64/pitch as f64 }else{0.};
    ((5.0*(sigma/pitch as f64+pixel_sigma)).ceil() as u32).saturating_add(4)
}

/// Worst expanded rectangle; u64 arithmetic avoids halo overflow. Image-edge
/// clipping can only reduce this area, including very narrow/small images.
fn expanded_tile_pixels(width:u32,height:u32,core:u32,halo:u32)->u64 {
    let extent=core as u64+2*(halo as u64);
    (width as u64).min(extent)*(height as u64).min(extent)
}

/// Largest 16-pixel-quantized core within both the preferred size and budget.
/// Unlike repeated halving, a small budget overflow does not discard half the
/// useful interior. At most61 exact integer checks for the supported64..1024.
fn select_tile_core(width:u32,height:u32,preferred:u32,halo:u32,budget:u64)->Result<u32> {
    if !(64..=1024).contains(&preferred) {return Err(EngineError::new("INVALID_BUDGET","Preferred tile core must be64..1024"));}
    let largest=preferred/16*16;
    for unit in (4..=largest/16).rev() {
        let core=unit*16;
        if expanded_tile_pixels(width,height,core,halo)<=budget {return Ok(core);}
    }
    Err(EngineError::new("MEMORY_BUDGET_TOO_SMALL",format!("Physical halo {halo}px cannot fit memory budget at this resolution/settings; no resizing performed")))
}

/// Call only with bundled/trusted data and locally generated linear float spool.
/// No whole-image allocation. Cancellation callback is checked between tiles.
#[allow(clippy::too_many_arguments)]
pub fn export_file(data_dir:&Path, film_stock:&str,paper_stock:&str,mut params:RuntimeParams,
    input_path:&Path,output_path:&Path,width:u32,height:u32,requested_tile:u32,
    max_tile_pixels:u64,brightness_ev:f64,mut cancelled:impl FnMut()->bool)->Result<ExportMetrics> {
    if width==0||height==0||width>40000||height>40000 {return Err(EngineError::new("INVALID_DIMENSIONS","Export dimensions must be 1..40000"));}
    if !(64..=1024).contains(&requested_tile) || max_tile_pixels<65536 || max_tile_pixels>4_194_304 {return Err(EngineError::new("INVALID_BUDGET","Invalid tile or memory budget"));}
    if input_path==output_path {return Err(EngineError::new("INVALID_PATH","Input and output must differ"));}
    if params.camera.auto_exposure || params.camera.diffusion_filter.active || params.enlarger.diffusion_filter.active || params.camera.lens_blur_um!=0. || params.scanner.lens_blur!=0. || params.scanner.unsharp_mask!=[0.,0.] || params.print_render.glare.active || params.io.crop || params.io.upscale_factor!=1. {
        return Err(EngineError::new("UNSUPPORTED_EXPORT_OPTIONS","Tiled export requires AE/crop/resize/lens filters/scan glare/sharpening off; it retains film halation, DIR and density grain"));
    }
    let n=(width as u64).checked_mul(height as u64).and_then(|v|v.checked_mul(12)).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Byte count overflow"))?;
    let mut input=File::open(input_path).map_err(io_error)?;
    if input.metadata().map_err(io_error)?.len()!=n {return Err(EngineError::new("INVALID_INPUT_FILE","Input length is not width*height*12"));}
    let film=load_profile_by_name(data_dir,film_stock).map_err(|e|EngineError::new("PROFILE_ERROR",e.to_string()))?;
    let print=load_profile_by_name(data_dir,paper_stock).map_err(|e|EngineError::new("PROFILE_ERROR",e.to_string()))?;
    let pitch=params.camera.pixel_size_um_override.unwrap_or(params.camera.film_format_mm*1000./width.max(height) as f32);
    if !pitch.is_finite() || pitch <= 0. || !brightness_ev.is_finite() || !(-2.0..=2.0).contains(&brightness_ev) {return Err(EngineError::new("INVALID_PARAMETERS","Physical pixel pitch must be positive and finite, and brightness EV must be in -2..2"));}
    params.camera.pixel_size_um_override=Some(pitch);
    params.film_render.grain.coordinate_origin=Some([0,0]);
    let halo=halo_pixels(&params,pitch);
    let tile=select_tile_core(width,height,requested_tile,halo,max_tile_pixels)?;
    let start=Instant::now();
    let pipeline=Pipeline::new_with_spectral(film,print,params,data_dir).map_err(|e|EngineError::new("PIPELINE_ERROR",e))?;
    let setup_ms=start.elapsed().as_secs_f64()*1000.;
    // create_new is intentional: never truncate an existing user file.
    let mut output=OpenOptions::new().write(true).create_new(true).open(output_path).map_err(io_error)?;
    let mut partial=PartialOutput {path:output_path,committed:false};
    let result=(|| {
        output.set_len(n).map_err(io_error)?;
        let mut count=0;let mut peak=0;
        for top in (0..height).step_by(tile as usize) {for left in (0..width).step_by(tile as usize) {
            if cancelled(){return Err(EngineError::new("CANCELLED","Export cancelled; partial output removed"));}
            let right=(left+tile).min(width);let bottom=(top+tile).min(height);
            let x0=left.saturating_sub(halo);let y0=top.saturating_sub(halo);
            let x1=right.saturating_add(halo).min(width);let y1=bottom.saturating_add(halo).min(height);
            let tw=x1-x0;let th=y1-y0;peak=peak.max(tw as u64*th as u64);
            let mut data=Vec::with_capacity(tw as usize*th as usize*3);
            let mut row=vec![0u8;tw as usize*12];
            for y in y0..y1 {
                input.seek(SeekFrom::Start((y as u64*width as u64+x0 as u64)*12)).map_err(io_error)?;
                input.read_exact(&mut row).map_err(io_error)?;
                for bytes in row.chunks_exact(4) {let v=f32::from_le_bytes(bytes.try_into().unwrap());if !v.is_finite(){return Err(EngineError::new("INVALID_PIXELS","Nonfinite spool sample"));}data.push(from_f64(v as f64));}
            }
            let mut tile_params=pipeline.params.clone();tile_params.film_render.grain.coordinate_origin=Some([x0,y0]);
            let tile_pipe=pipeline.clone().with_params(tile_params);
            let rendered=tile_pipe.process(ImageBuf::from_data(tw,th,data),&CpuBackend);
            let exposure=2.0f64.powf(brightness_ev);
            let mut bytes=Vec::with_capacity((right-left) as usize*12);
            for y in top..bottom {bytes.clear();
                let offset=((y-y0) as usize*tw as usize+(left-x0) as usize)*3;
                for &value in &rendered.data[offset..offset+(right-left) as usize*3] {let v=(value as f64*exposure) as f32;if !v.is_finite(){return Err(EngineError::new("NONFINITE_OUTPUT","Nonfinite export sample"));}bytes.extend_from_slice(&v.to_le_bytes());}
                output.seek(SeekFrom::Start((y as u64*width as u64+left as u64)*12)).map_err(io_error)?;output.write_all(&bytes).map_err(io_error)?;
            }
            count+=1;
        }}
        if cancelled(){return Err(EngineError::new("CANCELLED","Export cancelled; partial output removed"));}
        output.flush().map_err(io_error)?;
        Ok(ExportMetrics {width,height,tile_size:tile,halo,tile_count:count,peak_tile_pixels:peak,expanded_tile_budget_pixels:max_tile_pixels,spectral_scratch_max_bytes:4096*81*8,estimated_peak_native_bytes:96*1024*1024+peak*512,memory_estimate_policy:"96 MiB reserve plus 512 bytes per expanded tile pixel; conservative admission estimate, not measured RSS or OS allocation guarantee",setup_ms,elapsed_ms:start.elapsed().as_secs_f64()*1000.,precision:if cfg!(feature="precision-f64"){"f64"}else{"f32"},grain_mode:"absolute-coordinate Poisson-binomial",halo_policy:"5-sigma cumulative physical support plus 4 pixels; bounded IIR-tail approximation"})
    })();
    drop(output);if result.is_ok(){partial.committed=true;}result
}
fn io_error(e:std::io::Error)->EngineError {EngineError::new("IO_ERROR",e.to_string())}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mobile::MobileParams;
    fn directory(name:&str)->std::path::PathBuf {let p=std::env::temp_dir().join(format!("film-tiled-{name}-{}",std::process::id()));std::fs::create_dir_all(&p).unwrap();p}
    #[test] fn near_boundary_selects_largest_safe_quantum_not_half() {
        assert_eq!(select_tile_core(2560,1600,512,262,589824).unwrap(),240);
        assert_eq!(expanded_tile_pixels(2560,1600,240,262),583696);
        assert!(expanded_tile_pixels(2560,1600,256,262)>589824);
        assert_eq!(select_tile_core(2560,1600,512,262,608400).unwrap(),256);
        assert_eq!(select_tile_core(2560,1600,512,262,608399).unwrap(),240);
        assert_eq!(select_tile_core(8,8,511,262,64).unwrap(),496);
        assert!(select_tile_core(2560,1600,63,262,589824).is_err());
        assert!(select_tile_core(2560,1600,1025,262,589824).is_err());
    }
    #[test] fn tile_budget_adversarial_boundaries_are_exact_and_maximal() {
        for w in [1,31,64,255,511,1024,2560,40000] {for h in [1,64,1600,40000] {
            for halo in [0,1,31,262,500,u32::MAX] {for preferred in [64,79,255,511,512,1024] {
                for budget in [0,1,65535,65536,583695,583696,589823,589824,589825,608399,608400,4194304,u64::MAX] {
                    match select_tile_core(w,h,preferred,halo,budget) {
                        Ok(core)=>{assert!(core>=64&&core<=preferred&&core%16==0);assert!(expanded_tile_pixels(w,h,core,halo)<=budget);
                            if core+16<=preferred {assert!(expanded_tile_pixels(w,h,core+16,halo)>budget);}
                            for left in [0,w/2,w-1] {for top in [0,h/2,h-1] {
                                let tw=(left+core).min(w).saturating_add(halo).min(w)-left.saturating_sub(halo);
                                let th=(top+core).min(h).saturating_add(halo).min(h)-top.saturating_sub(halo);
                                assert!((tw as u64)*(th as u64)<=budget);
                            }}
                        },
                        Err(e)=>{assert_eq!(e.code,"MEMORY_BUDGET_TOO_SMALL");assert!(expanded_tile_pixels(w,h,64,halo)>budget);}
                    }
                }
            }}
        }}
    }
    #[test] fn partial_output_removed_on_unwind() {
        let d=directory("panic");let p=d.join("partial");
        let result=std::panic::catch_unwind(|| {std::fs::write(&p,b"partial").unwrap();let _guard=PartialOutput {path:&p,committed:false};panic!("intentional test");});
        assert!(result.is_err());assert!(!p.exists());let _=std::fs::remove_dir_all(d);
    }
    #[test] fn cancellation_removes_partial_and_existing_output_is_never_truncated() {
        let d=directory("cancel");let input=d.join("input");let output=d.join("output");std::fs::write(&input,vec![0u8;2*2*12]).unwrap();
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let p=MobileParams::default().runtime();
        let r=export_file(&data,"kodak_portra_400","kodak_portra_endura",p.clone(),&input,&output,2,2,64,65536,0.,||true);
        assert_eq!(r.unwrap_err().code,"CANCELLED");assert!(!output.exists());
        std::fs::write(&output,b"keep-existing-output").unwrap();
        let r=export_file(&data,"kodak_portra_400","kodak_portra_endura",p,&input,&output,2,2,64,65536,0.,||false);
        assert_eq!(r.unwrap_err().code,"IO_ERROR");assert_eq!(std::fs::read(&output).unwrap(),b"keep-existing-output");let _=std::fs::remove_dir_all(d);
    }
    #[test] fn insufficient_physical_halo_budget_rejects_without_resize_or_output() {
        let d=directory("budget");let input=d.join("input");let output=d.join("output");
        let file=OpenOptions::new().write(true).create(true).truncate(true).open(&input).unwrap();
        let original_bytes=2048u64*2048*12;file.set_len(original_bytes).unwrap();drop(file);
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let r=export_file(&data,"kodak_portra_400","kodak_portra_endura",MobileParams::default().runtime(),&input,&output,2048,2048,512,65536,0.,||false);
        assert_eq!(r.unwrap_err().code,"MEMORY_BUDGET_TOO_SMALL");assert!(!output.exists());
        assert_eq!(std::fs::metadata(&input).unwrap().len(),original_bytes);let _=std::fs::remove_dir_all(d);
    }
    #[test] fn wrong_spool_length_fails_before_creating_output() {
        let d=directory("length");let input=d.join("input");let output=d.join("output");std::fs::write(&input,b"short").unwrap();
        let r=export_file(Path::new("unused"),"kodak_portra_400","kodak_portra_endura",MobileParams::default().runtime(),&input,&output,2,2,64,65536,0.,||false);
        assert_eq!(r.unwrap_err().code,"INVALID_INPUT_FILE");assert!(!output.exists());let _=std::fs::remove_dir_all(d);
    }
}
