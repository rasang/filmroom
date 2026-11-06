//! Disk-backed camera-space stage-three geometry. No full-frame RGB allocation.
use super::*;
use std::os::fd::AsRawFd;
use std::sync::atomic::AtomicU64;

fn backing_file(output:&Path,bytes:u64)->Result<File> {
    static NEXT:AtomicU64=AtomicU64::new(0);
    let parent=output.parent().ok_or_else(||err("INVALID_PATH","spool"))?;
    for _ in 0..16 {
        let path=parent.join(format!(".dng-camera-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
        let file=match OpenOptions::new().read(true).write(true).create_new(true).mode(0o600)
            .custom_flags(libc::O_NOFOLLOW).open(&path) {
            Ok(file)=>file,
            Err(e)if e.kind()==io::ErrorKind::AlreadyExists=>continue,
            Err(e)=>return Err(io_error(e,"spool")),
        };
        if let Err(e)=std::fs::remove_file(&path){drop(file);let _=std::fs::remove_file(&path);return Err(io_error(e,"spool"));}
        let rc=unsafe{libc::posix_fallocate(file.as_raw_fd(),0,bytes as libc::off_t)};
        if rc!=0{return Err(io_error(io::Error::from_raw_os_error(rc),"spool"));}
        return Ok(file);
    }
    Err(err("OUTPUT_EXISTS","spool"))
}

fn camera_backing(d:&Dng,reader:&Dng,input:&mut File,output:&Path,admission:&Admission,
    pool:&rayon::ThreadPool,cancel:&AtomicBool)->Result<File> {
    let development=Development::from(d);
    let camera=backing_file(output,(d.active.w*d.active.h*12)as u64)?;
    let mut first=d.active.y;
    let mut row=Vec::with_capacity(d.active.w*12);
    // Preserve the complete active image until geometric resampling finishes.
    // Default crop/orientation are deliberately deferred to final emission.
    while first<d.active.y+d.active.h {
        if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","develop"));}
        let last=(first+admission.strip_rows as usize).min(d.active.y+d.active.h);
        let start=first.saturating_sub(HALO).max(d.active.y);
        let end=(last+HALO).min(d.active.y+d.active.h);
        let data=reader.read_tile_cancellable(input,start,end-start,cancel)?;
        let pixels=pool.install(||camera_tile(&development,start,end-start,data))?;
        for y in first..last {
            row.clear();
            for p in &pixels.data[(y-start)*d.active.w..(y-start+1)*d.active.w] {
                for value in p {row.extend_from_slice(&value.to_le_bytes());}
            }
            write_at_all(&camera,&row,((y-d.active.y)*d.active.w*12)as u64).map_err(|e|io_error(e,"spool"))?;
        }
        first=last;
    }
    drop(row);
    Ok(camera)
}

pub(super) fn decode(d:&Dng,reader:&Dng,input:&mut File,out:&File,output:&Path,
    admission:&Admission,pool:&rayon::ThreadPool,cancel:&AtomicBool)->Result<()> {
    let warp=d.warp.as_ref().ok_or_else(||err("UNSUPPORTED_DNG_OPCODE","develop"))?;
    let development=Development::from(d);
    let camera=camera_backing(d,reader,input,output,admission,pool,cancel)?;
    let kernel=crate::dng_warp::CubicWeights::new();
    // Small destination tiles need only a bounded rectangular source window.
    // Enumerating each footprint handles arbitrary polynomial extrema safely.
    for y in (0..d.active.h).step_by(32) { for x in (0..d.active.w).step_by(128) {
        if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","develop"));}
        let tw=128.min(d.active.w-x);let th=32.min(d.active.h-y);
        let mut left=d.active.w;let mut top=d.active.h;let mut right=0;let mut bottom=0;
        for yy in y..y+th {for xx in x..x+tw {for channel in 0..3 {
            let [sx,sy]=warp.source_position(d.active.w,d.active.h,xx,yy,channel)?;
            let ax=(sx.floor()as isize-1).clamp(0,d.active.w as isize-5)as usize;
            let ay=(sy.floor()as isize-1).clamp(0,d.active.h as isize-5)as usize;
            left=left.min(ax);top=top.min(ay);right=right.max(ax+4);bottom=bottom.max(ay+4);
        }}}
        let sw=right-left;let sh=bottom-top;
        let bytes=sw.checked_mul(sh).and_then(|n|n.checked_mul(12)).filter(|n|*n<=8*1024*1024)
            .ok_or_else(||err("MEMORY_BUDGET","develop"))?;
        let mut window=vec![0u8;bytes];
        for yy in top..bottom {
            camera.read_exact_at(&mut window[(yy-top)*sw*12..(yy-top+1)*sw*12],((yy*d.active.w+left)*12)as u64)
                .map_err(|e|io_error(e,"develop"))?;
        }
        let mut pixels=Vec::with_capacity(tw*th);
        for yy in y..y+th {
            if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","develop"));}
            for xx in x..x+tw {
                let mut pixel=[0f32;3];
                for channel in 0..3 {
                    pixel[channel]=warp.sample(&kernel,d.active.w,d.active.h,xx,yy,channel,|sx,sy,c| {
                        if sx<left||sx>=right||sy<top||sy>=bottom{return Err(err("INVALID_INPUT","develop"));}
                        let i=((sy-top)*sw+sx-left)*12+c*4;
                        Ok(f32::from_le_bytes(window[i..i+4].try_into().unwrap()))
                    })?;
                }
                pixels.push(pixel);
            }
        }
        drop(window);
        let pixels=calibrate_camera(d,rawler::pixarray::Color2D::new_with(pixels,tw,th),cancel)?;
        emit_region(&development,out,&pixels,Rect{x:x+d.active.x,y:y+d.active.y,w:tw,h:th},cancel)?;
    }}
    Ok(())
}

#[cfg(test)]mod real_tests {
    use super::*;
    #[test]
    #[ignore="Real public Q2 reference export requires explicit paths"]
    fn real_q2_export_camera_reference() {
        let input=std::path::PathBuf::from(std::env::var("FILM_Q2_SOURCE").unwrap());
        let output=std::path::PathBuf::from(std::env::var("FILM_Q2_CAMERA").unwrap());
        let mut source=File::open(&input).unwrap();let d=Dng::read(&mut source).unwrap();
        let mut budget=Budget::default();budget.disk_budget_bytes=2*1024*1024*1024;
        let admission=admit(&d,&budget).unwrap();
        let pool=rayon::ThreadPoolBuilder::new().num_threads(2).build().unwrap();
        let mut file=camera_backing(&d,&d,&mut source,&output,&admission,&pool,&AtomicBool::new(false)).unwrap();
        let mut dest=OpenOptions::new().write(true).create_new(true).open(&output).unwrap();
        std::io::Seek::seek(&mut file,std::io::SeekFrom::Start(0)).unwrap();
        assert_eq!(std::io::copy(&mut file,&mut dest).unwrap(),(d.active.w*d.active.h*12)as u64);
        println!("camera reference {}x{}",d.active.w,d.active.h);
    }
}
