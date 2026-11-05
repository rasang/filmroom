//! One Runner/Context for both passes; the host only performs bounded IO,
//! original f64->f32 upload rounding, hashing, and original scalar witnesses.
use super::*;
use crate::research_spatial::gpu::{params, RunMetrics, Runner};
use sha2::{Digest, Sha256};
use std::{fs::{self, File, Metadata, OpenOptions}, io::{Read, Seek, SeekFrom, Write}, path::{Path, PathBuf}, time::Instant};

fn io<T>(x: std::io::Result<T>) -> R<T> { x.map_err(|_| "GPU_GLOBAL_IO".into()) }
fn floats(bytes: &[u8]) -> Vec<f32> { bytes.chunks_exact(4).map(|x| f32::from_le_bytes(x.try_into().unwrap())).collect() }
fn same(a: &Metadata, b: &Metadata) -> bool {
    #[cfg(unix)] {
        use std::os::unix::fs::MetadataExt;
        a.dev()==b.dev() && a.ino()==b.ino() && a.len()==b.len()
            && a.mtime()==b.mtime() && a.mtime_nsec()==b.mtime_nsec()
            && a.ctime()==b.ctime() && a.ctime_nsec()==b.ctime_nsec()
    }
    #[cfg(not(unix))] { a.len()==b.len() && a.modified().ok()==b.modified().ok() && a.created().ok()==b.created().ok() }
}
struct Source {
    path: PathBuf, file: File, original: Metadata, expected: String,
    hash: Sha256, position: u64, pixels: u64,
}
impl Source {
    fn open(path: PathBuf, pixels: u64, expected: &str) -> R<Self> {
        let original = io(fs::symlink_metadata(&path))?;
        if !original.is_file() || original.file_type().is_symlink() || original.len()!=pixels*8 {
            return Err("INVALID_GLOBAL_FILE".into());
        }
        let mut options = OpenOptions::new(); options.read(true);
        #[cfg(any(target_os="linux",target_os="android"))] {
            use std::os::unix::fs::OpenOptionsExt;
            options.custom_flags(libc::O_NOFOLLOW);
        }
        let file = io(options.open(&path))?;
        if !same(&original, &io(file.metadata())?) { return Err("INVALID_GLOBAL_STREAM_SOURCE_IDENTITY".into()); }
        Ok(Self {path,file,original,expected:expected.into(),hash:Sha256::new(),position:0,pixels})
    }
    fn begin_pass(&mut self) -> R<()> {
        self.verify_identity()?;
        self.hash = Sha256::new(); self.position=0;
        io(self.file.seek(SeekFrom::Start(0)))?; Ok(())
    }
    fn verify_identity(&self) -> R<()> {
        let current = io(fs::symlink_metadata(&self.path))?;
        if current.file_type().is_symlink() || !same(&self.original, &current)
            || !same(&self.original, &io(self.file.metadata())?) {
            return Err("INVALID_GLOBAL_STREAM_SOURCE_IDENTITY".into());
        }
        Ok(())
    }
    fn read(&mut self, start:u64, count:usize) -> R<Vec<f32>> {
        if start!=self.position || count>super::super::CHUNK || start+count as u64>self.pixels {
            return Err("INVALID_GLOBAL_STREAM_SOURCE_RANGE".into());
        }
        io(self.file.seek(SeekFrom::Start(start*8)))?;
        let mut bytes=vec![0u8;count*8]; io(self.file.read_exact(&mut bytes))?;
        self.hash.update(&bytes); self.position+=count as u64;
        bytes.chunks_exact(8).map(|x| {
            let v=f64::from_le_bytes(x.try_into().unwrap());
            if super::super::finite(v) { Ok(v as f32) } else { Err("NONFINITE_GLOBAL_INPUT".into()) }
        }).collect()
    }
    fn witness(&mut self,index:u64)->R<f64> {
        io(self.file.seek(SeekFrom::Start(index*8)))?;
        let mut bytes=[0u8;8];io(self.file.read_exact(&mut bytes))?;
        let value=f64::from_le_bytes(bytes);
        if !super::super::finite(value) { return Err("NONFINITE_GLOBAL_INPUT".into()); }
        Ok((value as f32) as f64)
    }
    fn finish_pass(&self) -> R<()> {
        self.verify_identity()?;
        let actual:String=self.hash.clone().finalize().iter().map(|b|format!("{b:02x}")).collect();
        if self.position!=self.pixels || actual!=self.expected {
            return Err("INVALID_GLOBAL_STREAM_SOURCE_DIGEST".into());
        }
        Ok(())
    }
}
fn reject(r:&Runner,c:&mut impl FnMut()->bool,m:&mut RunMetrics)->R<()> {
    if r.read_error_flag(c,m)?!=0 { return Err("GPU_GLOBAL_NUMERIC_CHECK".into()); } Ok(())
}
fn witness(expected:f64,actual:f32,m:&mut RunMetrics)->R<()> {
    let delta=(expected-actual as f64).abs();
    if !expected.is_finite() || !actual.is_finite()
        || delta>super::super::NUMERIC_TOLERANCE*expected.abs().max(1.) {
        return Err("GPU_GLOBAL_NUMERIC_CHECK".into());
    }
    m.sample_count+=1;m.sample_max_abs=m.sample_max_abs.max(delta);m.sample_sum+=delta*delta;Ok(())
}
fn scalar(op:u32)->[u32;40] { let mut p=params(1024,1024,op);p[4]=1;p }
fn upload(r:&Runner,sources:&mut [Source;3],start:u64,count:u32,c:&mut impl FnMut()->bool,m:&mut RunMetrics)->R<()> {
    for (ch,source) in sources.iter_mut().enumerate() {
        let mut local=0u32;
        while local<count {
            super::super::check(c)?;
            let take=(count-local).min(super::super::CHUNK as u32) as usize;
            let values=source.read(start+u64::from(local),take)?;
            r.upload_aux(super::super::PACK_BASE as u64*4,bytemuck::cast_slice(&values),c,m)?;
            let mut p=params(1024,1024,10);p[4]=take as u32;p[6]=ch as u32;p[7]=local;
            r.dispatch(p,c,m)?;local+=take as u32;
        }
    }
    Ok(())
}
pub(super) fn run_files(p:&StreamHighlightPlan,dir:&Path,candidate:&Path,expected:&[String;3],c:&mut impl FnMut()->bool,allow_probe:bool,capacity:u32)->R<serde_json::Value> {
    p.validate()?;super::super::check(c)?;
    let at=Instant::now();let pixels=p.pixels();
    let mut sources: [Source;3] = (0..3).map(|ch| Source::open(dir.join(format!("source-{ch}.f64le")),pixels,&expected[ch]))
        .collect::<R<Vec<_>>>()?.try_into().map_err(|_|"INVALID_GLOBAL_STREAM_SOURCE")?;
    // Exactly one Context is acquired. No new Runner exists inside either loop.
    let (r,mut m)=Runner::new_with_shader_family_error_explicit_layout(&p.allocation_plan()?,allow_probe,&families()?)?;
    m.sample_tolerance_absolute=super::super::NUMERIC_TOLERANCE;
    m.sample_tolerance_relative=super::super::NUMERIC_TOLERANCE;
    r.dispatch(scalar(17),c,&mut m)?;
    for source in &mut sources {source.begin_pass()?;}
    for (start,count) in chunks(pixels,capacity)? {
        upload(&r,&mut sources,start,count,c,&mut m)?;
        let blocks=count.div_ceil(REDUCTION_SAMPLES);
        let mut v=params(1024,1024,11);v[4]=blocks;v[8]=count;r.dispatch(v,c,&mut m)?;
        let mut v=scalar(18);v[5]=blocks;r.dispatch(v,c,&mut m)?;
    }
    for source in &sources {source.finish_pass()?;}
    let GlobalKind::Highlight {boost_ev,boost_range,protect_ev}=p.parameters().kind else {return Err("INVALID_GLOBAL_KIND".into());};
    // saved[0] now contains the full-image GPU maximum. The original op12
    // reduction and coefficient body runs unchanged, over this one exact value.
    let mut v=scalar(12);v[5]=1;v[12]=boost_ev.to_bits();v[13]=boost_range.to_bits();v[14]=protect_ev.to_bits();
    r.dispatch(v,c,&mut m)?;reject(&r,c,&mut m)?;
    let coefficients=floats(&r.read_probe(32,c,&mut m)?);
    if coefficients.len()!=8 || coefficients.iter().any(|x|!x.is_finite()) {return Err("NONFINITE_GLOBAL_INTERMEDIATE".into());}
    let peak=coefficients[0];
    r.dispatch(scalar(16),c,&mut m)?;reject(&r,c,&mut m)?;
    let peak_value=floats(&r.read_probe(36,c,&mut m)?)[8];
    witness(super::super::boost_reference_value(peak as f64,peak as f64,boost_ev as f64,boost_range as f64,protect_ev as f64),peak_value,&mut m)?;
    r.dispatch(scalar(19),c,&mut m)?;
    // Candidate ownership/atomic discard lives in full_stage. Files never alias
    // the immutable snapshots and create_new refuses any pre-existing output.
    let mut outputs:[File;3]=(0..3).map(|ch|io(OpenOptions::new().write(true).create_new(true).open(candidate.join(format!("plane-{ch}.f64le")))))
        .collect::<R<Vec<_>>>()?.try_into().map_err(|_|"INVALID_GLOBAL_OUTPUT")?;
    for source in &mut sources {source.begin_pass()?;}
    for (start,count) in chunks(pixels,capacity)? {
        upload(&r,&mut sources,start,count,c,&mut m)?;
        // Gather overwrites probe[0..], but never accum. Restore on GPU before
        // EVERY chunk; no coefficient is reconstructed or uploaded by the host.
        r.dispatch(scalar(20),c,&mut m)?;
        let mut v=params(1024,1024,13);v[4]=count;r.dispatch(v,c,&mut m)?;
        reject(&r,c,&mut m)?;
        for ch in 0..3 {
            let mut local=0u32;
            while local<count {
                super::super::check(c)?;
                let take=(count-local).min(super::super::CHUNK as u32) as usize;
                let mut v=params(1024,1024,15);v[4]=take as u32;v[6]=ch as u32;v[7]=local;
                r.dispatch(v,c,&mut m)?;
                let values=floats(&r.read_probe(take as u64*4,c,&mut m)?);
                if values.len()!=take || values.iter().any(|x|!x.is_finite()) {return Err("NONFINITE_GLOBAL_OUTPUT".into());}
                let guard_at=Instant::now();
                for i in 0..take.min(8) {
                    let within=if take==1 {0} else {i*(take-1)/(take.min(8)-1)};
                    let rounded=sources[ch].witness(start+u64::from(local)+within as u64)?;
                    witness(super::super::boost_reference_value(rounded,peak as f64,boost_ev as f64,boost_range as f64,protect_ev as f64),values[within],&mut m)?;
                }
                m.guard_ms+=guard_at.elapsed().as_secs_f64()*1000.;
                let bytes:Vec<u8>=values.iter().flat_map(|&v|(v as f64).to_le_bytes()).collect();
                io(outputs[ch].write_all(&bytes))?;local+=take as u32;
            }
        }
    }
    for source in &sources {source.finish_pass()?;}
    for output in &mut outputs {io(output.sync_all())?;}
    reject(&r,c,&mut m)?;super::super::check(c)?;
    m.pass_count=2;m.elapsed_ms=at.elapsed().as_secs_f64()*1000.;m.sample_rms=(m.sample_sum/m.sample_count.max(1) as f64).sqrt();
    m.sample_scope="original bounded f64 boost witnesses on GPU f32 inputs and full-image GPU peak; complete scalar finite/hash validation";
    let mut report=serde_json::to_value(&m).map_err(|_|"GPU_GLOBAL_REPORT")?;
    report["highlightPeak"]=peak.into();report["fullResolution"]=true.into();report["contextCount"]=1.into();
    report["passes"]=2.into();report["chunkCapacitySamples"]=capacity.into();
    report["hostWork"]="bounded file IO, original f64-to-f32 upload rounding, input hashes, and original numeric witnesses; no host full-image max, published coefficients or image boost output".into();
    Ok(report)
}
