//! The Nikon row/HE spool must use the production optics sink, not just publish
//! a selected profile in probe metadata. No private RAW or full sensor fixture.
use super::*;
use crate::{dng::Rect,embedded_lens::LensStatus};
use rawler::{decoders::{Decoder,FormatHint,RawDecodeParams,RawMetadata},rawsource::RawSource};

struct Scratch(PathBuf);
impl Scratch {
 fn new()->Self {
  let path=std::env::temp_dir().join(format!("film-nef-optics-{}-{}",std::process::id(),SEQUENCE.fetch_add(1,Ordering::Relaxed)));
  std::fs::create_dir(&path).unwrap();Self(path)
 }
}
impl Drop for Scratch {fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}

// Inject exact integer rows through the public RawSpool contract. The real NEF
// entropy decoder is covered separately; this regression targets its downstream
// handoff, which previously bypassed all optics in nikon_he::decode.
struct Rows {width:usize,height:usize}
fn sample(x:usize,y:usize)->u16 {
 (1000+x*23+y*17+if (x/9+y/7)%2==0 {1300}else{0}) as u16
}
impl Decoder for Rows {
 fn raw_image(&self,_:&RawSource,_:&RawDecodeParams,_:bool)->rawler::Result<rawler::RawImage>{unreachable!("row fixture")}
 fn raw_metadata(&self,_:&RawSource,_:&RawDecodeParams)->rawler::Result<RawMetadata>{unreachable!("row fixture")}
 fn format_dump(&self)->rawler::analyze::FormatDump{unreachable!("row fixture")}
 fn format_hint(&self)->FormatHint{FormatHint::NEF}
 fn bayer_rows(&self,_:&RawSource,sink:&mut dyn FnMut(usize,&[u16])->rawler::Result<()>,cancel:&dyn Fn()->bool)->rawler::Result<()> {
  for y in 0..self.height {
   if cancel(){return Err("cancelled test rows".into());}
   let row:Vec<u16>=(0..self.width).map(|x|sample(x,y)).collect();sink(y,&row)?;
  }Ok(())
 }
}

fn development()->Development<'static> {
 Development {width:160,height:112,cpp:1,orientation:1,
  active:Rect{x:4,y:4,w:152,h:104},crop:Rect{x:8,y:8,w:144,h:96},
  lens_reference:Rect{x:4,y:4,w:152,h:104},cfa:"RGGB".into(),
  wb:[1.35,1.0,1.15,1.0],matrix:vec![0.64,0.28,0.08,0.15,0.78,0.07,0.03,0.11,0.86],
  baseline_exposure_ev:0.0,gain_maps:&[],lens:LensStatus::Missing,
  lens_extras:Default::default(),lens_profile:None}
}
fn admission(rows:u32)->Admission {
 Admission{memory_budget_bytes:128*MIB,estimated_peak_bytes:64*MIB,
  required_disk_bytes:MIB,strip_rows:rows}
}
const BLACK:[f32;4]=[64.,65.,66.,67.];
const WHITE:[f32;4]=[16383.;4];

fn render(d:&Development,rows:u32)->Vec<u8> {
 let scratch=Scratch::new();let path=scratch.0.join("linear.rgb");
 let out=OpenOptions::new().read(true).write(true).create_new(true).open(&path).unwrap();
 out.set_len((d.crop.w*d.crop.h*12) as u64).unwrap();
 let mut spool=RawSpool::create_bayer(&scratch.0.join("sensor"),d.width as u32,d.height as u32).unwrap();
 let cancel=AtomicBool::new(false);
 spool.decode_rows(&Rows{width:d.width,height:d.height},&RawSource::new_from_slice(&[]),&cancel).unwrap();
 develop_spool(spool,d,&path,&out,&admission(rows),BLACK,WHITE,&cancel).unwrap();
 drop(out);let bytes=std::fs::read(&path).unwrap();
 assert_eq!(bytes.len(),d.crop.w*d.crop.h*12);
 assert!(bytes.chunks_exact(4).all(|v|f32::from_le_bytes(v.try_into().unwrap()).is_finite()));
 assert_eq!(std::fs::read_dir(&scratch.0).unwrap().count(),1,"anonymous backing files must be gone");
 bytes
}

// Independent legacy normalization + develop + emit, deliberately bypassing
// CameraRgbSink. The unselected path must remain byte-identical to this oracle.
fn legacy(d:&Development,rows:usize)->Vec<u8> {
 let scratch=Scratch::new();let path=scratch.0.join("legacy.rgb");
 let out=OpenOptions::new().write(true).create_new(true).open(&path).unwrap();
 out.set_len((d.crop.w*d.crop.h*12) as u64).unwrap();
 let cancel=AtomicBool::new(false);let mut first=d.crop.y;
 while first<d.crop.y+d.crop.h {
  let last=(first+rows).min(d.crop.y+d.crop.h);
  let y=first.saturating_sub(HALO).max(d.active.y);let end=(last+HALO).min(d.active.y+d.active.h);
  let mut normalized=Vec::with_capacity(d.active.w*(end-y));
  for sy in y..end {for sx in d.active.x..d.active.x+d.active.w {
   let ch=(sy%2)*2+sx%2;
   normalized.push((sample(sx,sy) as f32-BLACK[ch]).max(0.)/(WHITE[ch]-BLACK[ch]));
  }}
  let pixels=crate::develop::develop_tile_cancel(d,y,end-y,normalized,&cancel).unwrap();
  crate::develop::emit(d,&out,&pixels,y,first,last,&cancel).unwrap();first=last;
 }
 drop(out);std::fs::read(path).unwrap()
}

#[test]
fn nikon_spool_without_optics_preserves_legacy_pixels() {
 for orientation in [1,6] {
  let mut d=development();d.orientation=orientation;
  assert_eq!(render(&d,13),legacy(&d,13));
  assert_eq!(render(&d,80),legacy(&d,80));
 }
}

#[test]
fn nikon_spool_executes_selected_profile_and_is_strip_independent() {
 let identity=crate::lensfun_metadata::CaptureIdentity {
  make:"Nikon".into(),model:"Z f".into(),lens_make:Some("Tamron".into()),
  lens_model:Some("Tamron 35-150mm F/2-2.8 Di III VXD".into()),
  lens_aliases:vec![],mount:Some("Nikon Z".into()),focal_length_mm:150.0,
  aperture:None,focus_range_m:None,
  provenance:crate::lensfun_metadata::LensIdentityProvenance::OriginalExif,
 };
 for orientation in [1,6] {
  let mut d=development();d.orientation=orientation;
  let uncorrected=legacy(&d,13);
  crate::lensfun::configure_capture("NEF",&identity,&mut d);
  assert!(d.lens_profile.as_ref().unwrap().has_distortion());
  let corrected=render(&d,13);
  assert_ne!(corrected,uncorrected,"a reported Nikon Lensfun correction must execute on pixels");
  assert_eq!(corrected,render(&d,31),"strip partition must not affect the optical map");
  assert_eq!(corrected,render(&d,80));
 }
}
