//! DefaultScale runs in camera space after List3 and before color/PGT.
//! Full active-area backings retain crop-exterior filter support pixels.
use super::*;

pub(super) fn scratch(d:&Dng)->Result<u64>{
    let Some(target)=d.scaled_size else{return Ok(0)};
    let crop=Rect{x:d.crop.x-d.active.x,y:d.crop.y-d.active.y,w:d.crop.w,h:d.crop.h};
    crate::dng_scale_resample::scratch_bytes(d.active.w,d.active.h,crop,target)?
        .checked_add(target[0] as u64*96).ok_or_else(||err("MEMORY_BUDGET","admission"))
}
pub(super) fn backing_bytes(d:&Dng)->u64{
    let camera=d.active.w as u64*d.active.h as u64*12;
    camera*if d.warp.is_some(){2}else{1}
        +if d.streaming_linear {d.width as u64*d.height as u64*6}else{0}
}

// The streaming SOF3 decoder cannot serve overlapping/random row reads. Retain
// exact uint16 samples, then use the existing normalized reader for every stage.
fn linear_spool(d:&Dng,input:&mut File,output:&Path,cancel:&AtomicBool)->Result<File>{
    let file=geometry::backing_file(output,d.width as u64*d.height as u64*6)?;
    let mut expected=0;
    for (strip,(&offset,&bytes)) in d.offsets.iter().zip(&d.counts).enumerate(){
        let start=strip*d.rows_per_strip;let height=d.rows_per_strip.min(d.height-start);
        crate::linear_jpeg::rows(input,offset as u64,bytes as u64,d.width,height,d.sample_bits,cancel,|local,row|{
            let y=start+local;
            if y!=expected||row.len()!=d.width*3{return Err(err("INVALID_INPUT","decode"));}
            let mut bytes=Vec::with_capacity(row.len()*2);
            for &value in row{bytes.extend_from_slice(&value.to_le_bytes());}
            write_at_all(&file,&bytes,y as u64*d.width as u64*6).map_err(|e|io_error(e,"spool"))?;
            expected+=1;Ok(())
        })?;
    }
    if expected!=d.height{return Err(err("INVALID_INPUT","decode"));}Ok(file)
}

pub(super) fn decode(d:&Dng,reader:&Dng,input:&mut File,out:&File,output:&Path,
    admission:&Admission,pool:&rayon::ThreadPool,cancel:&AtomicBool)->Result<()>{
    let target=d.scaled_size.ok_or_else(||err("INVALID_INPUT","develop"))?;
    if d.profile.as_ref().is_some_and(|p|p.map.is_some()) {return Err(err("UNSUPPORTED_DNG_CALIBRATION","develop"));}
    let mut raw_spool;
    let mut storage;
    let (reader,input)=if d.streaming_linear {
        raw_spool=linear_spool(d,input,output,cancel)?;
        storage=d.clone();storage.streaming_linear=false;storage.compression=1;
        storage.sample_bits=16;storage.endian=crate::tiff::Endian::Le;
        storage.offsets=vec![0];storage.counts=vec![(d.width*d.height*6) as u32];
        storage.rows_per_strip=d.height;storage.tile_height=d.height;
        (&storage,&mut raw_spool)
    }else{(reader,input)};
    let mut camera=geometry::camera_backing(d,reader,input,output,admission,pool,cancel)?;
    let bounds=Rect{x:0,y:0,w:d.active.w,h:d.active.h};
    if let Some(pipeline)=&d.warp {
        let mut spare=geometry::backing_file(output,bounds.w as u64*bounds.h as u64*12)?;
        for stage in &pipeline.steps {
            let emit=|tile:Rect,pixels:rawler::pixarray::Color2D<f32,3>|{
                geometry::write_region(&spare,bounds,&pixels,tile,tile.y..tile.y+tile.h,cancel)
            };
            match stage {
                crate::dng_warp::Stage3Opcode::Warp(w)=>geometry::resample(&camera,bounds.w,bounds.h,
                    |x,y,c|w.source_position(bounds.w,bounds.h,x,y,c),w.is_identity(),true,cancel,emit)?,
                crate::dng_warp::Stage3Opcode::GainMap(m)=>geometry::map_stage(&camera,bounds.w,bounds.h,m,cancel,emit)?,
            }
            std::mem::swap(&mut camera,&mut spare);
        }
    }
    let crop=Rect{x:d.crop.x-d.active.x,y:d.crop.y-d.active.y,w:d.crop.w,h:d.crop.h};
    let mut destination=Development::from(d);
    destination.width=target[0];destination.height=target[1];
    destination.active=Rect{x:0,y:0,w:target[0],h:target[1]};destination.crop=destination.active;
    crate::dng_scale_resample::resample(&camera,bounds.w,bounds.h,crop,target,cancel,|y,row|{
        let pixels=if let Some(profile)=&d.profile {
            profile.develop(destination.active,y,1,row.into_iter().flatten().collect(),d.baseline_exposure_ev,cancel)?
        }else{
            calibrate_camera(d,rawler::pixarray::Color2D::new_with(row,target[0],1),cancel)?
        };
        emit(&destination,out,&pixels,y,y,y+1,cancel)
    })
}
