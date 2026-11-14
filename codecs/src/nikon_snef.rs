//! Explicit already-white-balanced linear-sRGB adapter for Nikon mode8.
//! Storage conversion follows LibRaw; no camera matrix / WB / inverse-gamma
//! step is applied a second time. Unrelated RAW paths are not modified.
use crate::{develop::{Budget,Admission,Development},dng::{Probe,Rect,Warning},error::{Result,err,io_error},tiff::{Tiff,Endian,uint}};
use rawler::{rawsource::RawSource,RawImageData,pixarray::Color2D};
use std::{fs::{File,OpenOptions,DirBuilder},io::{self,Write},os::unix::fs::{FileExt,OpenOptionsExt,DirBuilderExt},os::fd::AsRawFd,path::{Path,PathBuf},sync::atomic::{AtomicBool,AtomicU64,Ordering}};
const MIB:u64=1024*1024;const BASE:u64=32*MIB;const ROWS:usize=64;
#[derive(Clone,Copy,Debug,PartialEq)] pub(crate) enum PixelDomain { LibRawNikonSnefLinearSrgbAlreadyBalanced }
pub(crate) struct Snef {pub domain:PixelDomain,pub as_shot_wb:[f32;3],pub recorded_color_space:Option<u16>,width:usize,height:usize,orientation:u8,input_bytes:u64,offset:u64,bytes:u64,source:Option<RawSource>,file:File,identity:crate::nikon_he::Identity}
struct Maker {mode:u16,has_wb:bool,color_space:Option<u16>}
fn bad()->crate::error::CodecError{err("INVALID_INPUT","probe")}
fn maker(data:&[u8])->Result<Option<Maker>> {
 if data.len()<18 || !data.starts_with(b"Nikon\0\x02") {return Ok(None);}
 let e=match &data[10..12]{b"II"=>Endian::Le,b"MM"=>Endian::Be,_=>return Err(bad())};if e.u16(&data[12..14])!=42{return Err(bad());}
 let at=10usize.checked_add(e.u32(&data[14..18])as usize).ok_or_else(bad)?;
 let start=at.checked_add(2).ok_or_else(bad)?;
 let n=e.u16(data.get(at..start).ok_or_else(bad)?)as usize;if n>256{return Err(bad());}
 let end=start.checked_add(n*12).ok_or_else(bad)?;
 let entries=data.get(start..end).ok_or_else(bad)?;let mut mode=None;let mut wb=false;let mut cs=None;
 for entry in entries.chunks_exact(12){let tag=e.u16(entry);let ty=e.u16(&entry[2..]);let count=e.u32(&entry[4..]);
  if matches!(tag,0x000c|0x0014|0x0097){wb=true;}
  if tag==0x0093 {if mode.is_some()||ty!=3||count!=1{return Err(bad());}mode=Some(e.u16(&entry[8..]));}
  if tag==0x001e && ty==3 && count==1 {cs=Some(e.u16(&entry[8..]));}
 }
 Ok(mode.map(|mode|Maker{mode,has_wb:wb,color_space:cs}))
}
impl Snef {
 pub fn read(input:&Path)->Result<Option<Self>> {
  let mut file=File::open(input).map_err(|e|io_error(e,"probe"))?;let identity=crate::nikon_he::Identity::read(&file)?;
  let t=Tiff::read_camera(&mut file)?;
  if t.ifds[0].contains_key(&50706) || !t.ifds[0].get(&271).is_some_and(|v|v.typ==2&&v.bytes.starts_with(b"NIKON")){return Ok(None);}
  let mut notes=t.ifds.iter().filter_map(|i|i.get(&37500));let Some(note)=notes.next() else{return Ok(None)};if notes.next().is_some(){return Err(bad());}
  let Some(m)=maker(&note.bytes)? else{return Ok(None)};if m.mode!=8{return Ok(None);}
  if !m.has_wb {return Err(err("INVALID_COLOR_METADATA","probe"));}
  let mut sensors=t.ifds.iter().enumerate().filter(|(_,i)|i.contains_key(&33422));let (index,sensor)=sensors.next().ok_or_else(bad)?;if sensors.next().is_some(){return Err(bad());}
  let width=uint(sensor,256,None)?as usize;let height=uint(sensor,257,None)?as usize;crate::validate_dimensions(width as u32,height as u32)?;
  if width<8 || width>16384 || width%2!=0 || height<8 || uint(sensor,258,None)?!=12 || uint(sensor,259,None)?!=34713 || uint(sensor,262,None)?!=32803 || uint(sensor,277,Some(1))?!=1 {return Err(err("UNSUPPORTED_CAMERA_RAW_VARIANT","probe"));}
  let offsets=sensor.get(&273).ok_or_else(bad)?.uints()?;let counts=sensor.get(&279).ok_or_else(bad)?.uints()?;
  if offsets.len()!=1||counts.len()!=1||uint(sensor,278,Some(height as u32))?as usize!=height {return Err(err("UNSUPPORTED_CAMERA_RAW_VARIANT","probe"));}
  let offset=u64::from(offsets[0]);let bytes=u64::from(counts[0]);if bytes!=(width as u64)*(height as u64)*3 || offset.checked_add(bytes).is_none_or(|n|n>t.size){return Err(bad());}
  let orientation=uint(&t.ifds[0],274,Some(1))?;if !(1..=8).contains(&orientation){return Err(bad());}
  let source=RawSource::new_from_file(&file,input).map_err(|e|io_error(e,"probe"))?;
  let raw=crate::camera_raw::bounded("probe",||rawler::decode_dummy(&source))?;
  if raw.cpp!=3||raw.width!=width||raw.height!=height||raw.source_ifd_offset!=Some(t.ifd_offsets[index])||!matches!(&raw.data,RawImageData::Integer(v) if v.is_empty()) {return Err(bad());}
  let wb=[raw.wb_coeffs[0],raw.wb_coeffs[1],raw.wb_coeffs[2]];if wb.iter().any(|v|!v.is_finite()||*v<=0.||*v>64.){return Err(err("INVALID_COLOR_METADATA","probe"));}
  if identity!=crate::nikon_he::Identity::read(&file)?||identity.bytes!=t.size{return Err(bad());}
  Ok(Some(Self{domain:PixelDomain::LibRawNikonSnefLinearSrgbAlreadyBalanced,as_shot_wb:wb,recorded_color_space:m.color_space,width,height,orientation:orientation as u8,input_bytes:t.size,offset,bytes,source:Some(source),file,identity}))
 }
 fn full(&self)->Rect {Rect{x:0,y:0,w:self.width,h:self.height}}
 /// Geometry-only bridge to emit. Empty matrix and NaN WB make accidental use
 /// of the generic camera develop pipeline invalid; it is never called here.
 fn emission_geometry(&self)->Development<'static>{Development{width:self.width,height:self.height,cpp:3,orientation:self.orientation,active:self.full(),crop:self.full(),cfa:String::new(),wb:[f32::NAN;4],matrix:Vec::new(),baseline_exposure_ev:0.,gain_maps:&[],lens:crate::embedded_lens::LensStatus::Missing,lens_extras:Default::default(),lens_profile:None,lens_reference:self.full()}}
 pub fn probe(&self)->Probe {let(w,h)=self.emission_geometry().oriented_dim();Probe{format:"NEF",variant:"NIKON_SNEF_LINEAR_SRGB_INTEGER",encoded_width:self.width as u32,encoded_height:self.height as u32,width:w as u32,height:h as u32,source_orientation:self.orientation,orientation_applied:true,active_area:self.full(),default_crop:self.full(),output_orientation:1,input_bytes:self.input_bytes,output_bytes:(w*h*12)as u64,max_pixels:crate::MAX_PIXELS,warnings:vec![Warning{code:"CAMERA_RAW_BASIC_DEVELOPMENT",message:"使用已验证RAW基础解码，不复刻相机Picture Control或镜头校正"}],has_alternate_forward_matrix:false,development_policy:"nikon-snef-libraw-linear-srgb-v1",baseline_exposure_applied_ev:0.}}
 pub fn admit(&self,b:&Budget)->Result<Admission>{
  let memory=b.memory_budget_mi_b.checked_mul(MIB).filter(|n|*n>=BASE&&*n<=crate::camera_raw::CAMERA_RAW_MAX_MEMORY_MIB*MIB).ok_or_else(||err("MEMORY_BUDGET","admission"))?;
  let decode=BASE+self.input_bytes+MIB;
  let develop_base=BASE+self.width as u64*12+MIB;
  let classic_develop=develop_base+self.width as u64*ROWS as u64*18;
  if decode.max(classic_develop)>memory{return Err(err("MEMORY_BUDGET","admission"));}
  let capacity=((memory-develop_base)/(self.width as u64*18)) as usize;
  let rows=b.raw_batch.camera_rows(ROWS,capacity,128);
  let develop=develop_base+self.width as u64*rows as u64*18;let peak=decode.max(develop);
  if peak>memory{return Err(err("MEMORY_BUDGET","admission"));}let disk=self.width as u64*self.height as u64*18;if disk>b.disk_budget_bytes.min(1024*MIB){return Err(err("DISK_BUDGET","admission"));}
  Ok(Admission{memory_budget_bytes:memory,estimated_peak_bytes:peak,required_disk_bytes:disk,strip_rows:rows as u32})
 }
 pub fn decode(mut self,output:&Path,b:&Budget,cancel:&AtomicBool)->Result<(Probe,Admission)>{
  check(cancel)?;let admission=self.admit(b)?;if self.domain!=PixelDomain::LibRawNikonSnefLinearSrgbAlreadyBalanced || self.identity!=crate::nikon_he::Identity::read(&self.file)? {return Err(err("INVALID_INPUT","decode"));}
  let out=OpenOptions::new().write(true).create_new(true).mode(0o600).custom_flags(libc::O_NOFOLLOW).open(output).map_err(|e|io_error(e,"spool"))?;let mut guard=OutputGuard(output,false);allocate(&out,self.probe().output_bytes)?;
  let mut path=output.as_os_str().to_os_string();path.push(format!(".snef-private-{}-{}",std::process::id(),SEQUENCE.fetch_add(1,Ordering::Relaxed)));let dir=PathBuf::from(path);DirBuilder::new().mode(0o700).create(&dir).map_err(|e|io_error(e,"spool"))?;let _dir=DirGuard(dir.clone());
  let path=dir.join("rgb");let mut raw=OpenOptions::new().read(true).write(true).create_new(true).mode(0o600).custom_flags(libc::O_NOFOLLOW).open(&path).map_err(|e|io_error(e,"spool"))?;let mut raw_guard=OutputGuard(&path,false);std::fs::remove_file(&path).map_err(|e|io_error(e,"spool"))?;raw_guard.1=true;drop(raw_guard);allocate(&raw,self.width as u64*self.height as u64*6)?;
  {let source=self.source.take().ok_or_else(||err("INVALID_INPUT","decode"))?;if source.buf().len()as u64!=self.input_bytes{return Err(err("INVALID_INPUT","decode"));}let bytes=source.subview(self.offset,self.bytes).map_err(|e|io_error(e,"decode"))?;
   let mut rowbytes=buffer(self.width*6)?;let mut next=0usize;
   crate::snef_rows::decode_rows(bytes,self.width,self.height,cancel,&mut|y,row|{if y!=next||row.len()!=self.width{return Err(io::Error::new(io::ErrorKind::InvalidData,"row contract"));}for(i,p)in row.iter().enumerate(){for(c,v)in p.iter().enumerate(){let at=i*6+c*2;rowbytes[at..at+2].copy_from_slice(&v.to_le_bytes());}}raw.write_all(&rowbytes)?;next+=1;Ok(())}).map_err(decode_error)?;
   if next!=self.height||self.identity!=crate::nikon_he::Identity::read(&self.file)?{return Err(err("INVALID_INPUT","decode"));}
  } // entire input mapping and row decoder released before float buffers.
  check(cancel)?;raw.sync_all().map_err(|e|io_error(e,"spool"))?;check(cancel)?;
  let geometry=self.emission_geometry();let batch_rows=admission.strip_rows as usize;let mut encoded=buffer(self.width*batch_rows*6)?;
  for first in (0..self.height).step_by(batch_rows){check(cancel)?;let rows=batch_rows.min(self.height-first);let n=self.width*rows;
   raw.read_exact_at(&mut encoded[..n*6],(first*self.width*6)as u64).map_err(|e|io_error(e,"decode"))?;
   let mut pixels=Vec::new();pixels.try_reserve_exact(n).map_err(|_|err("MEMORY_BUDGET","decode"))?;
   for (i,p) in encoded[..n*6].chunks_exact(6).enumerate(){if i%4096==0{check(cancel)?;}pixels.push([u16::from_le_bytes([p[0],p[1]])as f32/16383.,u16::from_le_bytes([p[2],p[3]])as f32/16383.,u16::from_le_bytes([p[4],p[5]])as f32/16383.]);}
   let image=Color2D::new_with(pixels,self.width,rows);crate::develop::emit(&geometry,&out,&image,first,first,first+rows,cancel)?;
  }
  check(cancel)?;out.sync_all().map_err(|e|io_error(e,"spool"))?;check(cancel)?;guard.1=true;Ok((self.probe(),admission))
 }
}
fn check(c:&AtomicBool)->Result<()> {if c.load(Ordering::Relaxed){Err(err("CANCELLED","decode"))}else{Ok(())}}
fn buffer(n:usize)->Result<Vec<u8>>{let mut v=Vec::new();v.try_reserve_exact(n).map_err(|_|err("MEMORY_BUDGET","decode"))?;v.resize(n,0);Ok(v)}
fn allocate(f:&File,n:u64)->Result<()>{let rc=unsafe{libc::posix_fallocate(f.as_raw_fd(),0,n as libc::off_t)};if rc!=0{Err(io_error(io::Error::from_raw_os_error(rc),"spool"))}else{Ok(())}}
fn decode_error(e:io::Error)->crate::error::CodecError{match e.kind(){io::ErrorKind::Interrupted=>err("CANCELLED","decode"),io::ErrorKind::OutOfMemory=>err("MEMORY_BUDGET","decode"),io::ErrorKind::InvalidData=>err("INVALID_INPUT","decode"),_=>io_error(e,"decode")}}
struct OutputGuard<'a>(&'a Path,bool);impl Drop for OutputGuard<'_>{fn drop(&mut self){if !self.1{let _=std::fs::remove_file(self.0);}}}
struct DirGuard(PathBuf);impl Drop for DirGuard{fn drop(&mut self){let _=std::fs::remove_dir(&self.0);}}
static SEQUENCE:AtomicU64=AtomicU64::new(0);
#[cfg(test)] mod tests {
 use super::*;
 fn note(mode:u16,wb:bool,cs:u16)->Vec<u8>{let mut d=vec![0;18];d[..7].copy_from_slice(b"Nikon\0\x02");d[10..12].copy_from_slice(b"II");d[12..14].copy_from_slice(&42u16.to_le_bytes());d[14..18].copy_from_slice(&8u32.to_le_bytes());d.extend_from_slice(&(if wb{3u16}else{2}).to_le_bytes());for(tag,val)in [(0x93,mode),(0x1e,cs)].into_iter().chain(if wb{Some((0xc,1))}else{None}){d.extend_from_slice(&(tag as u16).to_le_bytes());d.extend_from_slice(&3u16.to_le_bytes());d.extend_from_slice(&1u32.to_le_bytes());d.extend_from_slice(&val.to_le_bytes());d.extend_from_slice(&[0;2]);}d}
 #[test]fn maker_explicit_mode_wb_and_recorded_space(){for cs in [1,2,4]{let m=maker(&note(8,true,cs)).unwrap().unwrap();assert_eq!(m.mode,8);assert!(m.has_wb);assert_eq!(m.color_space,Some(cs));}assert!(!maker(&note(8,false,1)).unwrap().unwrap().has_wb);assert_eq!(maker(&note(3,true,1)).unwrap().unwrap().mode,3);}
 #[test]fn maker_bounds_do_not_panic(){let good=note(8,true,1);for n in 18..good.len(){assert!(maker(&good[..n]).is_err());}let mut d=good.clone();d[14..18].copy_from_slice(&u32::MAX.to_le_bytes());assert!(maker(&d).is_err());d=good;d[18..20].copy_from_slice(&257u16.to_le_bytes());assert!(maker(&d).is_err());}
}
