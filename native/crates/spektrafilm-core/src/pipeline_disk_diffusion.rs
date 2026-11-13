//! GPU finite-FIR diffusion with bounded host storage. Only export calls this;
//! CPU FFT/IIR semantics are deliberately not replaced by a finite halo.
use super::*;
use std::fs::{File,OpenOptions};
use std::io::{Read,Write,Seek,SeekFrom};
use spektrafilm_math::precision::{from_f64};

const FIXED:u64=96*1024*1024;
const MARGIN:u64=32*1024*1024;
const PIXEL_BYTES:u64=192;
#[derive(Clone,Copy,Debug)]
pub struct DiskDiffusionPlan { pub rows:u32,pub radius:u32,pub estimated_bytes:u64 }
/// Reject before allocation; keep individual jobs responsive and bindable.
pub fn plan(width:u32,height:u32,radius:u32,budget:u32,binding:u64,retained:u64)->Option<DiskDiffusionPlan>{
    if width==0||height==0||!(128..=512).contains(&budget){return None;}
    let fixed=FIXED.checked_add(MARGIN)?.checked_add(retained)?;
    let available=(u64::from(budget)*1024*1024).checked_sub(fixed)?;
    let fit=(available/(u64::from(width)*PIXEL_BYTES)).min(binding.saturating_mul(9)/10/(u64::from(width)*12)).min(u64::from(height));
    let rows=if fit==u64::from(height){height.min(512)}else{u32::try_from(fit.checked_sub(u64::from(radius)*2)?).ok()?.min(512)};
    if rows==0{return None;}
    let rows=if rows>=16{rows/16*16}else{rows};
    let expanded=height.min(rows.saturating_add(radius.saturating_mul(2)));
    Some(DiskDiffusionPlan{rows,radius,estimated_bytes:fixed+u64::from(expanded)*u64::from(width)*PIXEL_BYTES})
}
fn radius(p:&spektrafilm_gpu::DiffusionGpuPlan)->Option<u32>{
    if p.sigmas.len()!=p.coeffs.len()||p.sigmas.iter().any(|s|!s.is_finite()||*s<=0.)||p.coeffs.iter().flatten().any(|v|!v.is_finite()){return None;}
    Some(p.sigmas.iter().map(|s|(3.0_f32*s).ceil().min(256.) as u32).max().unwrap_or(0))
}
fn fresh(path:&Path,len:u64)->Option<File>{let f=OpenOptions::new().read(true).write(true).create_new(true).open(path).ok()?;f.set_len(len).ok()?;Some(f)}
fn read_f64(file:&mut File,offset:u64,len:usize)->Option<Vec<f64>>{
    let mut out=Vec::new();out.try_reserve_exact(len).ok()?;out.resize(len,0.);
    file.seek(SeekFrom::Start(offset)).ok()?;
    // Row/window only, never allocate or map the entire work field.
    let mut bytes=vec![0u8;len.checked_mul(8)?];file.read_exact(&mut bytes).ok()?;
    for (value,chunk) in out.iter_mut().zip(bytes.chunks_exact(8)){*value=f64::from_le_bytes(chunk.try_into().ok()?);if !value.is_finite(){return None;}}
    Some(out)
}
fn write_f64(file:&mut File,offset:u64,values:&[f64])->Option<()>{file.seek(SeekFrom::Start(offset)).ok()?;let mut row=Vec::with_capacity(values.len()*8);for v in values{if !v.is_finite(){return None;}row.extend_from_slice(&v.to_le_bytes());}file.write_all(&row).ok()}
fn read_image(file:&mut File,w:u32,y:u32,h:u32)->Option<ImageBuf>{
    let data=read_f64(file,u64::from(y)*u64::from(w)*24,w as usize*h as usize*3)?;
    Some(ImageBuf::from_data(w,h,data.into_iter().map(from_f64).collect()))
}
/// Same y/x addition order as DownsampleAccumulator::add_tile, but only one
/// small row is resident. Division happens after every full-resolution tap.
fn add_tile(file:&mut File,sw:u32,d:u32,tap:&ImageBuf,tx:u32,ty:u32,core:[u32;4])->Option<()>{
    let [x0,y0,x1,y1]=core;
    for sy in y0/d..=(y1-1)/d {
        let mut row=read_f64(file,u64::from(sy)*u64::from(sw)*24,sw as usize*3)?;
        for y in y0.max(sy*d)..y1.min((sy+1)*d){for x in x0..x1{
            let src=((y-ty)as usize*tap.width as usize+(x-tx)as usize)*3;
            let dst=(x/d)as usize*3;
            for c in 0..3{row[dst+c]+=tap.data[src+c]as f64;}
        }}
        write_f64(file,u64::from(sy)*u64::from(sw)*24,&row)?;
    }Some(())
}
fn finish_downsample(file:&mut File,w:u32,h:u32,p:&spektrafilm_gpu::DiffusionGpuPlan,check:&mut impl FnMut()->bool)->Option<()>{
    for sy in 0..p.small_h{if !check(){return None;}let mut row=read_f64(file,u64::from(sy)*u64::from(p.small_w)*24,p.small_w as usize*3)?;
        let rows=((sy+1)*p.d).min(h)-sy*p.d;
        for sx in 0..p.small_w{let cols=((sx+1)*p.d).min(w)-sx*p.d;for c in 0..3{let i=sx as usize*3+c;row[i]=from_f64(row[i]/f64::from(rows*cols))as f64;}}
        write_f64(file,u64::from(sy)*u64::from(p.small_w)*24,&row)?;
    }Some(())
}
fn blur(file:&mut File,out:&mut File,p:&spektrafilm_gpu::DiffusionGpuPlan,b:DiskDiffusionPlan,backend:&dyn ComputeBackend,check:&mut impl FnMut()->bool,complete:&mut impl FnMut())->Option<()>{
    for y in (0..p.small_h).step_by(b.rows as usize){
        if !check(){return None;}
        let end=(y+b.rows).min(p.small_h);let top=y.saturating_sub(b.radius);let bottom=end.saturating_add(b.radius).min(p.small_h);
        let source=read_image(file,p.small_w,top,bottom-top)?;
        let mut acc=vec![0f64;p.small_w as usize*(end-y)as usize*3];
        for (&sigma,cf) in p.sigmas.iter().zip(&p.coeffs){
            if !check(){return None;}
            let r=(3.0_f32*sigma).ceil().min(256.)as u32;
            let a=y.saturating_sub(r);let z=end.saturating_add(r).min(p.small_h);
            let start=(a-top)as usize*p.small_w as usize*3;let len=(z-a)as usize*p.small_w as usize*3;
            let component=ImageBuf::from_data(p.small_w,z-a,source.data[start..start+len].to_vec());
            // The original backend FIR kernel and per-pixel accumulation order
            // are reused. The readback completes before buffers are reused.
            let result=backend.gaussian_blur(&component,sigma);
            if !check()||result.width!=p.small_w||result.height!=z-a||result.data.len()!=len{return None;}
            let offset=(y-a)as usize*p.small_w as usize*3;
            for (i,v) in acc.iter_mut().enumerate(){let sample=result.data[offset+i]as f64;if !sample.is_finite(){return None;}*v+=f64::from(cf[i%3])*sample;}
            complete();
        }
        let mut row=Vec::with_capacity(p.small_w as usize*12);
        for values in acc.chunks_exact(p.small_w as usize*3){row.clear();for &v in values{let v=v as f32;if !v.is_finite(){return None;}row.extend_from_slice(&v.to_le_bytes());}out.write_all(&row).ok()?;}
    }Some(())
}
impl Pipeline {
    #[allow(clippy::too_many_arguments)]
    pub fn diffusion_scattered_fields_gpu_disk_progress(&self,width:u32,height:u32,core_width:u32,core_height:u32,front_width:u32,front_height:u32,
        budget:u32,binding:u64,reserved:u64,window_pixels:u64,directory:&Path,
        mut read_rect:impl FnMut(u32,u32,u32,u32)->Option<Vec<f32>>,mut check:impl FnMut()->bool,backend:&dyn ComputeBackend,mut progress:impl FnMut(u64,u64))->Option<DiffusionScatteredFields>{
        if width==0||height==0||core_width==0||core_height==0||front_width==0||front_height==0||!check(){return None;}
        let (cam,enl)=self.gpu_diffusion_plans(width,height);
        let make=|p:&spektrafilm_gpu::DiffusionGpuPlan|plan(p.small_w,p.small_h,radius(p)?,budget,binding,reserved);
        let cam_band=cam.as_ref().map(&make).transpose_option()?;let enl_band=enl.as_ref().map(&make).transpose_option()?;
        let highlight=self.params.film_render.halation.boost_ev!=0.||self.params.film_render.halation.headroom_active();
        let tiles=|w:u32,h:u32|u64::from(width.div_ceil(w))*u64::from(height.div_ceil(h));
        let total=if highlight{tiles(front_width,front_height)}else{0}
            +cam.as_ref().zip(cam_band).map_or(0,|(p,b)|tiles(front_width,front_height)+u64::from(p.small_h.div_ceil(b.rows))*p.sigmas.len()as u64)
            +enl.as_ref().zip(enl_band).map_or(0,|(p,b)|tiles(core_width,core_height)+u64::from(p.small_h.div_ceil(b.rows))*p.sigmas.len()as u64);
        let mut done=0;progress(done,total);
        let maximum=if highlight{
            let mut params=self.params.clone();params.film_render.halation.boost_ev=0.;params.film_render.halation.headroom_ev=0.;let unboosted=self.clone().with_params(params);let mut maximum=0f32;
            for y in (0..height).step_by(front_height as usize){for x in (0..width).step_by(front_width as usize){if !check(){return None;}let w=front_width.min(width-x);let h=front_height.min(height-y);let data=read_rect(x,y,w,h)?;
                let tap=unboosted.tap_gpu_resident_tile_f32_controlled(&data,w,h,TileContext{full_width:width,full_height:height,x,y},backend,None,spektrafilm_gpu::ChainStop::Front,&mut check)?;
                for &v in &tap.data{maximum=maximum.max(v as f32);}done+=1;progress(done,total);
            }}Some(maximum)
        }else{None};
        let mut run=|name:&str,p:&spektrafilm_gpu::DiffusionGpuPlan,b:DiskDiffusionPlan,camera:Option<&DiffusionScattered>|->Option<DiffusionScattered>{
            let sums_path=directory.join(format!("{name}.sum"));let output_path=directory.join(format!("{name}.field"));
            let mut sums=fresh(&sums_path,u64::from(p.small_w)*u64::from(p.small_h)*24)?;
            let front=name=="camera";let margin=if front{0}else{self.banded_halo_pixels(self.full_image_pitch(width,height))};
            let (cw,ch)=if front{(front_width,front_height)}else{(core_width,core_height)};
            let fields=DiffusionScatteredFields{camera:camera.cloned(),enlarger:None,highlight_max_raw:maximum};
            for y in (0..height).step_by(ch as usize){for x in (0..width).step_by(cw as usize){if !check(){return None;}let x1=(x+cw).min(width);let y1=(y+ch).min(height);let tx=x.saturating_sub(margin);let ty=y.saturating_sub(margin);let tw=x1.saturating_add(margin).min(width)-tx;let th=y1.saturating_add(margin).min(height)-ty;
                let data=read_rect(tx,ty,tw,th)?;
                let tap=self.tap_gpu_resident_tile_f32_controlled(&data,tw,th,TileContext{full_width:width,full_height:height,x:tx,y:ty},backend,Some(&fields),if front{spektrafilm_gpu::ChainStop::Front}else{spektrafilm_gpu::ChainStop::PrintLinear},&mut check)?;
                add_tile(&mut sums,p.small_w,p.d,&tap,tx,ty,[x,y,x1,y1])?;done+=1;progress(done,total);
            }}drop(fields);
            finish_downsample(&mut sums,width,height,p,&mut check)?;
            let mut output=fresh(&output_path,0)?;
            blur(&mut sums,&mut output,p,b,backend,&mut check,&mut ||{done+=1;progress(done,total);})?;
            output.flush().ok()?;drop(output);drop(sums);std::fs::remove_file(&sums_path).ok()?;
            DiffusionScattered::from_disk_bounded(&output_path,p.small_w,p.small_h,p.d,p.p_s,window_pixels.min(u64::from(p.small_w)*u64::from(p.small_h))).ok()
        };
        let camera=match cam.as_ref().zip(cam_band){Some((p,b))=>Some(run("camera",p,b,None)?),None=>None};
        let enlarger=match enl.as_ref().zip(enl_band){Some((p,b))=>Some(run("enlarger",p,b,camera.as_ref())?),None=>None};
        if !check(){return None;}Some(DiffusionScatteredFields{camera,enlarger,highlight_max_raw:maximum})
    }
}
trait TransposeOption<T>{fn transpose_option(self)->Option<Option<T>>;}
impl<T> TransposeOption<T> for Option<Option<T>>{fn transpose_option(self)->Option<Option<T>>{match self{Some(v)=>v.map(Some),None=>Some(None)}}}

#[cfg(test)]
mod tests {
 use super::*;
 struct Cleanup(std::path::PathBuf);impl Drop for Cleanup{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
 fn dir(name:&str)->Cleanup{use std::sync::atomic::{AtomicU64,Ordering};static N:AtomicU64=AtomicU64::new(0);let p=std::env::temp_dir().join(format!("disk-diff-{name}-{}-{}",std::process::id(),N.fetch_add(1,Ordering::Relaxed)));std::fs::create_dir(&p).unwrap();Cleanup(p)}
 #[test]fn planner_enforces_budget_binding_and_response(){
  for budget in [128,256,384,512]{let p=plan(2731,2048,179,budget,128*1024*1024,72*1024*1024);if let Some(p)=p{assert!(p.rows>0&&p.rows<=512);assert!(p.estimated_bytes<=u64::from(budget)*1024*1024);assert!(u64::from((p.rows+358).min(2048))*2731*12<=128*1024*1024*9/10);}}
  assert!(plan(2731,2048,179,512,1024,0).is_none());assert!(plan(2731,2048,179,512,u64::MAX,512*1024*1024).is_none());assert!(plan(0,200,2,512,u64::MAX,0).is_none());assert!(plan(2731,2048,179,768,u64::MAX,0).is_none());
  let a=plan(2731,2048,179,384,128*1024*1024,0).unwrap();let b=plan(2731,2048,179,512,128*1024*1024,0).unwrap();assert!(b.rows>a.rows);
 }
 #[test]fn disk_downsample_preserves_partial_cells_and_tile_order(){
  let root=dir("sum");let(w,h,d)=(31u32,23u32,3u32);let p=spektrafilm_gpu::DiffusionGpuPlan{d,small_w:w.div_ceil(d),small_h:h.div_ceil(d),p_s:0.5,sigmas:vec![1.],coeffs:vec![[1.;3]]};
  let mut f=fresh(&root.0.join("sum"),u64::from(p.small_w)*u64::from(p.small_h)*24).unwrap();
  let mut reference=spektrafilm_model::diffusion::DownsampleAccumulator::new(w as usize,h as usize,d as usize);
  for y in (0..h).step_by(7){for x in (0..w).step_by(8){let ww=8.min(w-x);let hh=7.min(h-y);let data=(0..ww*hh*3).map(|i|from_f64(((i*17+x+y)%113)as f64/71.)).collect();let image=ImageBuf::from_data(ww,hh,data);
   add_tile(&mut f,p.small_w,d,&image,x,y,[x,y,x+ww,y+hh]).unwrap();reference.add_tile(&image,x as usize,y as usize,[x as usize,y as usize,(x+ww)as usize,(y+hh)as usize]).unwrap();
  }}finish_downsample(&mut f,w,h,&p,&mut||true).unwrap();let actual=read_image(&mut f,p.small_w,0,p.small_h).unwrap();assert_eq!(actual.data,reference.finish().data);
  assert!(finish_downsample(&mut f,w,h,&p,&mut||false).is_none());
 }
 #[cfg(feature="wgpu-backend")]
 #[test]fn formal_wgsl_striped_diffusion_matches_whole_field(){
  let Some(gpu)=spektrafilm_gpu::wgpu_backend::WgpuBackend::new()else{assert_ne!(std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref(),Ok("1"));return;};
  let root=dir("wgsl");
  for family in ["glimmerglass","black_pro_mist"]{
   let filter=spektrafilm_model::diffusion::DiffusionFilter{family,strength:0.5,spatial_scale:1.,halo_warmth:0.8,core_intensity:1.,core_size:1.,halo_intensity:1.,halo_size:1.,bloom_intensity:1.,bloom_size:1.};
   let mut p=spektrafilm_model::diffusion::diffusion_gpu_plan(&filter,11.3671875,8192,6144).unwrap();p.small_w=47;p.small_h=53;
   let data=(0..p.small_w*p.small_h*3).map(|i|from_f64(if i%307==0{4.}else{(i%131)as f64/113.})).collect();let source=ImageBuf::from_data(p.small_w,p.small_h,data);
   let expected=spektrafilm_model::diffusion::blur_scattered_field(&gpu,&source,&p);
   let path=root.0.join(format!("{family}.sum"));let mut sum=fresh(&path,0).unwrap();write_f64(&mut sum,0,&source.data.iter().map(|v|*v as f64).collect::<Vec<_>>()).unwrap();
   let path=root.0.join(format!("{family}.out"));let mut output=fresh(&path,0).unwrap();let r=radius(&p).unwrap();
   let mut completed=0;blur(&mut sum,&mut output,&p,DiskDiffusionPlan{rows:7,radius:r,estimated_bytes:0},&gpu,&mut||true,&mut||completed+=1).unwrap();drop(output);
   let bytes=std::fs::read(&path).unwrap();let actual:Vec<f32>=bytes.chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect();let max=actual.iter().zip(&expected).map(|(a,b)|(a-b).abs()).fold(0f32,f32::max);assert_eq!(actual.len(),expected.len());assert!(max<=1e-6,"{family} max={max}");assert_eq!(completed,p.sigmas.len()*p.small_h.div_ceil(7)as usize);assert!(gpu.is_healthy());
   eprintln!("DISK_DIFFUSION_WGSL family={family} max={max} components={} rows=7",p.sigmas.len());
  }
 }
}
