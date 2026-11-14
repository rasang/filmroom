//! Canon full-quality Bayer/RGB streaming via a bounded, immediately unlinked spool.
use super::*;
use rawler::decoders::cr2::CanonSrawStorage;
use std::os::unix::fs::FileExt;
use std::os::fd::AsRawFd;
use std::sync::atomic::AtomicU64;
static NEXT: AtomicU64 = AtomicU64::new(0);

struct Storage<'a> { file: File, samples: usize, cancel: &'a AtomicBool, failure: Option<crate::error::CodecError> }
impl Storage<'_> {
    fn check(&self, at: usize, len: usize) -> Result<()> {
        if self.cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "decode")); }
        if at.checked_add(len).is_none_or(|n| n > self.samples) { return Err(err("INVALID_INPUT", "decode")); }
        Ok(())
    }
    fn transfer(&mut self, at: usize, pixels: &mut [u16], write: bool) -> rawler::Result<()> {
        let result = (|| {
            self.check(at, pixels.len())?;
            let mut bytes = vec![0u8; pixels.len() * 2];
            if write {
                for (p,b) in pixels.iter().zip(bytes.chunks_exact_mut(2)) { b.copy_from_slice(&p.to_le_bytes()); }
                self.file.write_all_at(&bytes, at as u64 * 2).map_err(|e| io_error(e,"spool"))?;
            } else {
                self.file.read_exact_at(&mut bytes, at as u64 * 2).map_err(|e| io_error(e,"spool"))?;
                for (p,b) in pixels.iter_mut().zip(bytes.chunks_exact(2)) { *p=u16::from_le_bytes([b[0],b[1]]); }
            }
            Ok(())
        })();
        if let Err(e)=result { self.failure=Some(e); return Err("Canon spool failure".into()); }
        Ok(())
    }
}
impl CanonSrawStorage for Storage<'_> {
    fn read(&mut self, at: usize, pixels: &mut [u16]) -> rawler::Result<()> { self.transfer(at,pixels,false) }
    fn write(&mut self, at: usize, pixels: &[u16]) -> rawler::Result<()> { self.transfer(at,&mut pixels.to_vec(),true) }
}
fn anonymous(output: &Path, bytes: u64) -> Result<File> {
    let parent=output.parent().ok_or_else(||err("INVALID_PATH","spool"))?;
    for _ in 0..32 {
        let name=parent.join(format!(".canon-{}-{}.tmp",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
        let file=match OpenOptions::new().read(true).write(true).create_new(true).mode(0o600).custom_flags(libc::O_NOFOLLOW|libc::O_CLOEXEC).open(&name) {
            Ok(f)=>f,
            Err(e) if e.kind()==std::io::ErrorKind::AlreadyExists=>continue,
            Err(e)=>return Err(io_error(e,"spool")),
        };
        // An unlink failure is an error, never proceed with a named RAW temp.
        if let Err(e)=std::fs::remove_file(&name) {drop(file); let _=std::fs::remove_file(&name); return Err(io_error(e,"spool"));}
        let rc=unsafe {libc::posix_fallocate(file.as_raw_fd(),0,bytes as libc::off_t)};
        if rc!=0 {return Err(io_error(std::io::Error::from_raw_os_error(rc),"spool"));}
        return Ok(file);
    }
    Err(err("IO_ERROR","spool"))
}

pub(super) fn decode(camera:&CameraRaw,input:&Path,output:&Path,out:&File,admission:&Admission,cancel:&AtomicBool)->Result<()> {
    let d=&camera.development;
    let mut storage=Storage {file:anonymous(output,(d.width*d.height*d.cpp*2) as u64)?,samples:d.width*d.height*d.cpp,cancel,failure:None};
    let (raw,actual)={
        let file=File::open(input).map_err(|e|io_error(e,"decode"))?;
        let source=RawSource::new_from_file(&file,input).map_err(|e|io_error(e,"decode"))?;
        for j in &camera.jpeg_blocks {
            let bytes=source.subview(j.offset,j.bytes).map_err(|e|io_error(e,"decode"))?;
            crate::lossless::validate_camera(bytes,j.width,j.height,cancel)?;
        }
        let decoded=bounded("decode",|| {
            let decoder=rawler::get_decoder(&source)?;
            if d.cpp==3 {decoder.canon_sraw_stream(&source,&mut storage)} else {decoder.canon_bayer_stream(&source,&mut storage)}
        });
        if let Some(e)=storage.failure.take() {return Err(e);}
        let raw=decoded?;
        let mut actual=descriptor(&raw,d.orientation)?;
        configure_profile(&source,&file,"CR2",&raw,&mut actual);
        (raw,actual)
    }; // release input mapping before linear development
    let rect=|r:Rect|(r.x,r.y,r.w,r.h);
    let levels_valid=if d.cpp==3 {
        (raw.blacklevel.width,raw.blacklevel.height)==(1,1) && raw.blacklevel.levels.len()==3 && raw.whitelevel.0.len()==3
    } else {
        matches!((raw.blacklevel.width,raw.blacklevel.height),(1,1)|(2,2))
            && raw.blacklevel.levels.len()==raw.blacklevel.width*raw.blacklevel.height && matches!(raw.whitelevel.0.len(),1|4)
    };
    if raw.source_ifd_offset!=Some(camera.source_ifd_offset) || actual.cpp!=d.cpp || actual.width!=d.width || actual.height!=d.height
        || rect(actual.active)!=rect(d.active) || rect(actual.crop)!=rect(d.crop)
        || actual.lens!=d.lens || actual.lens_extras!=d.lens_extras || actual.lens_profile!=d.lens_profile
        || raw.blacklevel.cpp!=d.cpp || !levels_valid {
        return Err(err("INVALID_COLOR_METADATA","decode"));
    }
    let (black,white)=if d.cpp==3 {(raw.blacklevel.as_vec(),raw.whitelevel.as_vec())}
        else {(raw.blacklevel.as_bayer_array().to_vec(),raw.whitelevel.as_bayer_array().to_vec())};
    if black.iter().zip(&white).any(|(b,w)|!b.is_finite()||*b<0.||!w.is_finite()||w<=b) {return Err(err("INVALID_COLOR_METADATA","decode"));}
    drop(raw);
    let sink=crate::develop::CameraRgbSink::new(d,output)?;
    let region=sink.region(d);
    let mut first=region.y;
    while first<region.y+region.h {
        let last=(first+admission.strip_rows as usize).min(region.y+region.h);
        let y=first.saturating_sub(HALO).max(d.active.y);
        let end=(last+HALO).min(d.active.y+d.active.h);
        let mut integers=vec![0u16;d.active.w*d.cpp];
        let mut data=Vec::with_capacity(d.active.w*(end-y)*d.cpp);
        for row in y..end {
            let result=storage.read((row*d.width+d.active.x)*d.cpp,&mut integers);
            if let Some(e)=storage.failure.take() {return Err(e);}
            result.map_err(|_|err("IO_ERROR","spool"))?;
            data.extend(integers.iter().enumerate().map(|(i,p)| {
                let c=if d.cpp==3 {i%3} else {(row%2)*2+(d.active.x+i)%2};
                (*p as f32-black[c]).max(0.)/(white[c]-black[c])
            }));
        }
        drop(integers);
        let p=sink.develop(d,y,end-y,data,cancel)?;
        sink.emit_strip(d,out,&p,y,first,last,cancel)?;
        first=last;
    }
    drop(storage);
    sink.finish(d,out,cancel)?;
    Ok(())
}
