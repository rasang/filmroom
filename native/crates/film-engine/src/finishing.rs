//! Independent, scene-linear finishing. Existing darkroom controls are unchanged.
//! Defaults are a bit-preserving bypass; this layer never enters film calibration.
use crate::mobile::{EngineError, Result};
use std::{fs::{self,File,OpenOptions},io::{Read,Write,BufReader,BufWriter},path::Path};

#[derive(Clone,Copy,Debug,PartialEq)]
pub struct Finishing { pub exposure_ev:f64, pub contrast:f64, pub saturation:f64 }
impl Default for Finishing { fn default()->Self {Self{exposure_ev:0.,contrast:1.,saturation:1.}} }
impl Finishing {
    pub fn is_identity(self)->bool {self==Self::default()}
    pub fn validate(self)->Result<()> {
        for (v,lo,hi) in [(self.exposure_ev,-2.,2.),(self.contrast,0.5,1.5),(self.saturation,0.,2.)] {
            if !v.is_finite() || v<lo || v>hi {return Err(EngineError::new("INVALID_PARAMS","Invalid finishing control"));}
        }
        Ok(())
    }
    /// Shared D65 linear-sRGB operation. Preserve chromatic ratios for contrast,
    /// then adjust chroma about luminance. No display clipping/8-bit round trip.
    fn pixel(self, rgb:[f64;3], gain:f64)->[f64;3] {
        if self.is_identity(){return rgb;}
        let mut p=rgb.map(|v|v*gain);
        let weights=spektrafilm_math::colorspace::SRGB_TO_XYZ_F64[1];
        let mut y=weights[0]*p[0]+weights[1]*p[1]+weights[2]*p[2];
        if self.contrast!=1. && y.abs()>1e-12 {
            let mapped=y.signum()*0.18*(y.abs()/0.18).powf(self.contrast);
            let scale=mapped/y;p=p.map(|v|v*scale);y=mapped;
        }
        if self.saturation!=1. {p=p.map(|v|y+(v-y)*self.saturation);}
        p
    }
    pub fn apply(self,pixels:&mut [f32],space:&str)->Result<()> {
        self.validate()?;
        if pixels.len()%3!=0 {return Err(EngineError::new("INVALID_PIXELS","Finishing expects RGB pixels"));}
        if self.is_identity(){return Ok(());}
        let gain=2f64.powf(self.exposure_ev);
        for p in pixels.chunks_exact_mut(3) {
            let rgb=spektrafilm_core::output_color::to_linear_srgb([p[0]as f64,p[1]as f64,p[2]as f64],space);
            let graded=self.pixel(rgb,gain);
            let output=match space {
                "Display P3"|"Adobe RGB (1998)"=>{
                    use spektrafilm_core::output_color::{mul,XYZ_TO_P3,XYZ_TO_ADOBE};
                    // Inverse of the existing XYZ_TO_SRGB_F64, not its separately
                    // rounded forward matrix; a wide-gamut round trip stays neutral.
                    let xyz=mul([[0.4123955889674142,0.3575834307637148,0.18049264738170157],[0.21258623078559552,0.715170303703411,0.0722004986433362],[0.019297215491746938,0.11918386458084851,0.9504971251315798]],graded);
                    mul(if space=="Display P3"{XYZ_TO_P3}else{XYZ_TO_ADOBE},xyz)
                },
                "sRGB"=>graded,
                _=>return Err(EngineError::new("INVALID_PARAMS","Unknown finishing output space")),
            };
            for c in 0..3 {
                let v=output[c]as f32;
                if !v.is_finite(){return Err(EngineError::new("NONFINITE_OUTPUT","Finishing produced nonfinite pixels"));}
                p[c]=v;
            }
        }
        Ok(())
    }
    /// A checkpoint may hard-link the ungraded output: NEVER modify it in place.
    /// Stream to a new inode, sync, then atomically replace only the owned output.
    pub fn apply_spool(self,path:&Path,pixels:u64,space:&str,mut cancelled:impl FnMut()->bool)->Result<()> {
        self.validate()?;if self.is_identity(){return Ok(());}
        fn io(e:std::io::Error)->EngineError{EngineError::new("IO_ERROR",e.to_string())}
        let expected=pixels.checked_mul(12).ok_or_else(||EngineError::new("INVALID_PIXELS","Finishing size overflow"))?;
        let source=File::open(path).map_err(io)?;
        if source.metadata().map_err(io)?.len()!=expected{return Err(EngineError::new("INVALID_PIXELS","Finishing source shape mismatch"));}
        let name=path.file_name().ok_or_else(||EngineError::new("INVALID_PATH","Missing output filename"))?;
        let mut temporary=name.to_os_string();temporary.push(".finishing.part");let temporary=path.with_file_name(temporary);
        let target=OpenOptions::new().write(true).create_new(true).open(&temporary).map_err(io)?;
        struct Guard(std::path::PathBuf);impl Drop for Guard{fn drop(&mut self){let _=fs::remove_file(&self.0);}}
        let guard=Guard(temporary.clone());let mut input=BufReader::new(source);let mut output=BufWriter::new(target);
        let mut bytes=vec![0u8;1024*12];let mut values=vec![0f32;1024*3];let mut remaining=pixels;
        while remaining>0 {
            if cancelled(){return Err(EngineError::new("CANCELLED","Finishing cancelled"));}
            let count=remaining.min(1024)as usize;input.read_exact(&mut bytes[..count*12]).map_err(io)?;
            for (dst,b) in values[..count*3].iter_mut().zip(bytes[..count*12].chunks_exact(4)){*dst=f32::from_le_bytes(b.try_into().unwrap());}
            self.apply(&mut values[..count*3],space)?;
            for (v,b) in values[..count*3].iter().zip(bytes[..count*12].chunks_exact_mut(4)){b.copy_from_slice(&v.to_le_bytes());}
            output.write_all(&bytes[..count*12]).map_err(io)?;remaining-=count as u64;
        }
        output.flush().map_err(io)?;output.get_ref().sync_all().map_err(io)?;drop(output);drop(input);
        if cancelled(){return Err(EngineError::new("CANCELLED","Finishing cancelled before commit"));}
        fs::rename(&temporary,path).map_err(io)?;drop(guard);Ok(())
    }
}
#[cfg(test)]mod tests {
    use super::*;
    #[test]fn identity_preserves_bits_and_extended_range(){let a=[-0.,-0.1,0.18,0.5,1.,4.];let mut b=a;Finishing::default().apply(&mut b,"Display P3").unwrap();assert_eq!(a.map(f32::to_bits),b.map(f32::to_bits));}
    #[test]fn exposure_and_neutral_chroma(){let mut a=[0.1,0.2,0.4];Finishing{exposure_ev:1.,..Finishing::default()}.apply(&mut a,"sRGB").unwrap();assert_eq!(a,[0.2,0.4,0.8]);let mut a=[0.8,0.1,0.2];Finishing{saturation:0.,..Finishing::default()}.apply(&mut a,"sRGB").unwrap();assert_eq!(a[0],a[1]);assert_eq!(a[1],a[2]);}
    #[test]fn rejects_invalid_and_does_not_clip(){assert!(Finishing{contrast:f64::NAN,..Finishing::default()}.validate().is_err());let mut a=[1.,2.,4.];Finishing{exposure_ev:1.,..Finishing::default()}.apply(&mut a,"sRGB").unwrap();assert_eq!(a,[2.,4.,8.]);}
    #[test]fn wide_gamut_and_srgb_use_same_colour_operation(){for space in ["Display P3","Adobe RGB (1998)"]{let original=[0.8f32,0.15,0.25];let rgb=spektrafilm_core::output_color::to_linear_srgb(original.map(|v|v as f64),space);let mut reference=rgb.map(|v|v as f32);let grade=Finishing{exposure_ev:0.4,contrast:1.2,saturation:0.8};grade.apply(&mut reference,"sRGB").unwrap();let mut wide=original;grade.apply(&mut wide,space).unwrap();let actual=spektrafilm_core::output_color::to_linear_srgb(wide.map(|v|v as f64),space);for c in 0..3{assert!((actual[c]-reference[c]as f64).abs()<3e-6);}}}
    #[test]fn spool_does_not_modify_checkpoint_link_and_cancel_is_atomic(){
        let root=std::env::temp_dir().join(format!("film-finish-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));fs::create_dir(&root).unwrap();let source=root.join("result.rgb");let checkpoint=root.join("checkpoint.rgb");
        let bytes:Vec<u8>=[0.1f32,0.2,0.3].into_iter().flat_map(f32::to_le_bytes).collect();fs::write(&source,&bytes).unwrap();fs::hard_link(&source,&checkpoint).unwrap();let p=Finishing{exposure_ev:1.,..Finishing::default()};
        assert!(p.apply_spool(&source,1,"sRGB",||true).is_err());assert_eq!(fs::read(&source).unwrap(),bytes);assert!(!root.join("result.rgb.finishing.part").exists());
        p.apply_spool(&source,1,"sRGB",||false).unwrap();assert_eq!(fs::read(&checkpoint).unwrap(),bytes);let got=fs::read(&source).unwrap();assert_eq!(f32::from_le_bytes(got[..4].try_into().unwrap()),0.2);fs::remove_dir_all(root).unwrap();
    }
}
