//! Display-only stage-6 RGB sampling. No inversion, exposure metering or scientific re-render.
use spektrafilm_math::image::ImageBuf;
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
};

/// Matches the Android scan-source reader. Uncompressed RGB PNG is below 2 MiB.
pub const MAX_EDGE: u32 = 768;
/// Keep the original export admission allowance. Only RGB8 pixels and one f64
/// sample row survive capture; PNG writes use a single stored-deflate block.
pub(crate) const MEMORY_RESERVATION_BYTES: u64 = 3 * 1024 * 1024;
/// At most 1536 contiguous row reads, never per-pixel seeks. CPU capture needs
/// no source file I/O; this cap applies only to complete GPU/restored output.
const RESUME_READ_BUDGET: u64 = 128 * 1024 * 1024;
const MAX_SOURCE_EDGE: u32 = 40_000;
const DEFLATE_BLOCK: usize = 65_535;

fn invalid() -> io::Error { io::Error::new(io::ErrorKind::InvalidData, "Invalid scan preview") }
fn active(cancel: &mut impl FnMut() -> bool) -> io::Result<()> {
    if cancel() { Err(io::Error::new(io::ErrorKind::Interrupted, "Scan preview cancelled")) } else { Ok(()) }
}
fn coordinate(p: u32, s: u32, source: u32, target: u32) -> u32 {
    (((p as u64 * 4 + s as u64 * 2 + 1) * source as u64) / (target as u64 * 4)).min(source as u64 - 1) as u32
}
fn dimensions(width: u32, height: u32, edge: u32) -> io::Result<(u32, u32)> {
    if width == 0 || height == 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE || edge == 0 { return Err(invalid()); }
    let longest = width.max(height);
    let edge = edge.min(longest);
    Ok(((width as u64 * edge as u64 / longest as u64).max(1) as u32,
        (height as u64 * edge as u64 / longest as u64).max(1) as u32))
}
fn restored_edge(width: u32, height: u32) -> io::Result<u32> {
    dimensions(width, height, MAX_EDGE)?;
    // Landscape output has fewer thumbnail rows than its long edge. Do not
    // shrink it using the old, overly conservative long-edge-as-height bound.
    let mut lo = 1;
    let mut hi = MAX_EDGE.min(width.max(height));
    while lo < hi {
        let mid = lo + (hi - lo).div_ceil(2);
        let (_, h) = dimensions(width, height, mid)?;
        if width as u64 * 12 * 2 * h as u64 <= RESUME_READ_BUDGET { lo = mid; } else { hi = mid - 1; }
    }
    Ok(lo)
}
fn zeroed<T: Clone>(len: usize, value: T) -> io::Result<Vec<T>> {
    let mut values = Vec::new();
    values.try_reserve_exact(len).map_err(|_| io::Error::new(io::ErrorKind::OutOfMemory, "Scan preview allocation failed"))?;
    values.resize(len, value);
    Ok(values)
}
pub fn path(output: &Path) -> Option<PathBuf> {
    let id = output.file_stem()?.to_str()?.strip_prefix("film-result-")?;
    if id.len() != 36 || !id.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-') { return None; }
    Some(output.parent()?.join(format!("scan-source-{id}.png")))
}

/// A successful post-finishing thumbnail has its own immutable identity. Failure
/// leaves this absent, rather than exposing an ungraded PNG under a final label.
pub fn finished_path(output: &Path) -> Option<PathBuf> {
    let ordinary = path(output)?;
    let stem = ordinary.file_stem()?.to_str()?;
    Some(ordinary.with_file_name(format!("{stem}-finished.png")))
}

pub struct Sampler {
    width: u32, height: u32, w: u32, h: u32,
    pixels: Vec<u8>, row: Vec<[f64; 3]>,
    next_y: u32, next_sy: u32, next_source: u32,
    space: String, exposure: f64, failed: bool,
}
impl Sampler {
    /// Both display parameters are already known before the first CPU scan
    /// chunk. Applying them after each complete thumbnail row is byte-identical
    /// to applying them after the full frame: neither operation is global.
    pub fn new(width: u32, height: u32, space: &str, exposure: f64) -> io::Result<Self> {
        Self::edge(width, height, MAX_EDGE, space, exposure)
    }
    fn edge(width: u32, height: u32, edge: u32, space: &str, exposure: f64) -> io::Result<Self> {
        let (w, h) = dimensions(width, height, edge)?;
        if !exposure.is_finite() { return Err(invalid()); }
        Ok(Self { width, height, w, h,
            pixels: zeroed(w as usize * h as usize * 3, 0)?, row: zeroed(w as usize, [0.; 3])?,
            next_y: 0, next_sy: 0, next_source: 0, space: space.to_owned(), exposure, failed: false,
        })
    }
    /// Preserve the original per-pixel addition order: sy0/sx0, sy0/sx1,
    /// sy1/sx0, sy1/sx1. Linear f64 values survive until all four are present.
    fn sample_row(&mut self, mut sample: impl FnMut(u32, usize) -> f64) -> io::Result<()> {
        if self.failed || self.next_y >= self.h { return Err(invalid()); }
        for x in 0..self.w {
            for sx in 0..2 {
                let ix = coordinate(x, sx, self.width, self.w);
                for channel in 0..3 {
                    let value = sample(ix, channel);
                    if !value.is_finite() { self.failed = true; return Err(invalid()); }
                    self.row[x as usize][channel] += value * 0.25;
                }
            }
        }
        self.next_sy += 1;
        if self.next_sy == 2 {
            for x in 0..self.w as usize {
                let rgb = spektrafilm_core::output_color::to_linear_srgb(self.row[x].map(|v| v * self.exposure), &self.space);
                for (channel, value) in rgb.into_iter().enumerate() {
                    if !value.is_finite() { self.failed = true; return Err(invalid()); }
                    let value = value.clamp(0., 1.);
                    let encoded = if value <= 0.0031308 { 12.92 * value } else { 1.055 * value.powf(1. / 2.4) - 0.055 };
                    self.pixels[(self.next_y as usize * self.w as usize + x) * 3 + channel] = (encoded * 255.).round().clamp(0., 255.) as u8;
                }
            }
            self.row.fill([0.; 3]); self.next_y += 1; self.next_sy = 0;
        }
        Ok(())
    }
    /// Sequential, already-materialized CPU chunks only. No source file I/O.
    /// Monotone strata mean at most one partial output row spans two chunks.
    pub fn capture(&mut self, start: u32, image: &ImageBuf) -> io::Result<()> {
        let end = start.checked_add(image.height);
        if self.failed || image.height == 0 || start != self.next_source || image.width != self.width
            || end.is_none_or(|end| end > self.height)
            || image.data.len() != image.width as usize * image.height as usize * 3 {
            self.failed = true; return Err(invalid());
        }
        let end = end.unwrap();
        while self.next_y < self.h {
            let iy = coordinate(self.next_y, self.next_sy, self.height, self.h);
            if iy >= end { break; }
            if iy < start { self.failed = true; return Err(invalid()); }
            let base = (iy - start) as usize * self.width as usize * 3;
            self.sample_row(|ix, channel| image.data[base + ix as usize * 3 + channel] as f64)?;
        }
        self.next_source = end;
        Ok(())
    }
    pub fn publish(&self, output: &Path, cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
        let Some(target) = path(output) else { active(cancel)?; return Ok(None); };
        self.publish_target(target, cancel)
    }
    fn publish_target(&self, target: PathBuf, cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
        active(cancel)?;
        if self.failed || self.next_y != self.h || self.next_sy != 0 || self.next_source != self.height { return Err(invalid()); }
        let temp = target.with_extension("png.tmp");
        struct Guard(PathBuf);
        impl Drop for Guard { fn drop(&mut self) { let _ = fs::remove_file(&self.0); } }
        match fs::remove_file(&temp) {
            Ok(()) => (), Err(e) if e.kind() == io::ErrorKind::NotFound => (), Err(e) => return Err(e),
        }
        let _guard = Guard(temp.clone());
        let mut file = OpenOptions::new().create_new(true).write(true).open(&temp)?;
        write_png(&mut file, self.w, self.h, &self.pixels, cancel)?;
        file.sync_all()?; drop(file);
        active(cancel)?;
        fs::rename(&temp, &target)?;
        Ok(Some(target))
    }
}
fn crc_update(mut crc: u32, bytes: &[u8]) -> u32 {
    for &byte in bytes {
        crc ^= byte as u32;
        for _ in 0..8 { crc = (crc >> 1) ^ (0xedb88320u32.wrapping_mul(crc & 1)); }
    }
    crc
}
fn png_chunk(writer: &mut impl Write, kind: &[u8; 4], data: &[u8]) -> io::Result<()> {
    writer.write_all(&(data.len() as u32).to_be_bytes())?; writer.write_all(kind)?; writer.write_all(data)?;
    writer.write_all(&(!crc_update(crc_update(!0u32, kind), data)).to_be_bytes())
}
fn write_crc(writer: &mut impl Write, crc: &mut u32, data: &[u8]) -> io::Result<()> {
    writer.write_all(data)?; *crc = crc_update(*crc, data); Ok(())
}
/// Same RGB8/filter-0/stored-deflate bytes as negative_preview::png_rgb, but
/// without simultaneous full raw, zlib and PNG allocations. One block suffices.
fn write_png(writer: &mut impl Write, w: u32, h: u32, pixels: &[u8], cancel: &mut impl FnMut() -> bool) -> io::Result<()> {
    active(cancel)?;
    let row_bytes = w as usize * 3;
    if w == 0 || h == 0 || w > MAX_EDGE || h > MAX_EDGE || pixels.len() != row_bytes * h as usize { return Err(invalid()); }
    writer.write_all(b"\x89PNG\r\n\x1a\n")?;
    let mut header = [0u8; 13]; header[..4].copy_from_slice(&w.to_be_bytes()); header[4..8].copy_from_slice(&h.to_be_bytes()); header[8] = 8; header[9] = 2;
    png_chunk(writer, b"IHDR", &header)?; png_chunk(writer, b"sRGB", &[0])?;
    let stride = row_bytes + 1;
    let raw_len = stride * h as usize;
    let block_count = raw_len.div_ceil(DEFLATE_BLOCK);
    let zlib_len = raw_len + block_count * 5 + 6;
    writer.write_all(&(zlib_len as u32).to_be_bytes())?; writer.write_all(b"IDAT")?;
    let mut crc = crc_update(!0u32, b"IDAT");
    write_crc(writer, &mut crc, &[0x78, 0x01])?;
    let mut block = zeroed(raw_len.min(DEFLATE_BLOCK), 0u8)?;
    let (mut a, mut b) = (1u32, 0u32);
    let mut position = 0;
    while position < raw_len {
        active(cancel)?;
        let size = block.len().min(raw_len - position);
        let mut filled = 0;
        while filled < size {
            let offset = position + filled;
            let row = offset / stride; let column = offset % stride;
            if column == 0 { block[filled] = 0; filled += 1; }
            else {
                let count = (stride - column).min(size - filled);
                let from = row * row_bytes + column - 1;
                block[filled..filled + count].copy_from_slice(&pixels[from..from + count]); filled += count;
            }
        }
        let length = size as u16;
        let mut stored = [0u8; 5]; stored[0] = u8::from(position + size == raw_len);
        stored[1..3].copy_from_slice(&length.to_le_bytes()); stored[3..5].copy_from_slice(&(!length).to_le_bytes());
        write_crc(writer, &mut crc, &stored)?; write_crc(writer, &mut crc, &block[..size])?;
        for &byte in &block[..size] { a = (a + byte as u32) % 65521; b = (b + a) % 65521; }
        position += size;
    }
    write_crc(writer, &mut crc, &((b << 16) | a).to_be_bytes())?;
    writer.write_all(&(!crc).to_be_bytes())?;
    png_chunk(writer, b"IEND", &[])?;
    active(cancel)
}

/// Sample complete, already brightness-adjusted GPU/stage-6 checkpoint output.
/// No science or source writes. Ordinary 6K/8K landscapes retain 768px detail;
/// extreme images reduce the thumbnail edge to respect the fixed read budget.
pub fn publish_restored(output: &Path, width: u32, height: u32, space: &str,
    cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
    publish_restored_kind(output, width, height, space, false, cancel)
}
pub fn publish_finished(output: &Path, width: u32, height: u32, space: &str,
    cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
    publish_restored_kind(output, width, height, space, true, cancel)
}
fn publish_restored_kind(output: &Path, width: u32, height: u32, space: &str, finished: bool,
    cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
    active(cancel)?;
    let target = if finished { finished_path(output) } else { path(output) };
    let Some(target) = target else { return Ok(None); };
    let edge = restored_edge(width, height)?;
    let mut sampler = Sampler::edge(width, height, edge, space, 1.)?;
    let mut file = File::open(output)?;
    if file.metadata()?.len() != width as u64 * height as u64 * 12 { return Err(invalid()); }
    let mut row = zeroed(width as usize * 12, 0u8)?;
    let mut previous_row = None;
    while sampler.next_y < sampler.h {
        active(cancel)?;
        let iy = coordinate(sampler.next_y, sampler.next_sy, height, sampler.h);
        if previous_row != Some(iy) {
            file.seek(SeekFrom::Start(iy as u64 * width as u64 * 12))?; file.read_exact(&mut row)?; previous_row = Some(iy);
        }
        // Decode only the selected RGB values. A full f64 ImageBuf would need
        // another 960 KiB for a 40,000px row and is deliberately never built.
        sampler.sample_row(|ix, channel| {
            let offset = ix as usize * 12 + channel * 4;
            f32::from_le_bytes(row[offset..offset + 4].try_into().unwrap()) as f64
        })?;
    }
    sampler.next_source = height;
    drop(row); drop(file);
    sampler.publish_target(target, cancel)
}

#[cfg(test)] mod tests {
 use super::*;use spektrafilm_math::precision::from_f64;
 struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
 fn temp()->Temp{let p=std::env::temp_dir().join(format!("scan-preview-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));fs::create_dir(&p).unwrap();Temp(p)}
 fn output(t:&Temp)->PathBuf{t.0.join("film-result-12345678-1234-1234-1234-123456789abc.rgb")}
 #[test]fn finishing_publication_has_distinct_identity_and_graded_pixels(){
  let t=temp();let out=output(&t);let values=vec![0.18f32;96];
  fs::write(&out,values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let early=publish_restored(&out,8,4,"sRGB",&mut||false).unwrap().unwrap();let before=fs::read(&early).unwrap();
  let grade=crate::finishing::Finishing{exposure_ev:1.,..Default::default()};
  grade.apply_spool(&out,32,"sRGB",||false).unwrap();let final_source=publish_finished(&out,8,4,"sRGB",&mut||false).unwrap().unwrap();
  assert_ne!(early,final_source);assert_eq!(Some(final_source.clone()),finished_path(&out));
  assert_eq!(fs::read(&early).unwrap(),before);
  assert_eq!(fs::read(final_source).unwrap(),crate::negative_preview::png_rgb(8,4,&vec![162;96]));
 }
 #[test]fn finishing_cancel_or_bad_spool_never_publishes_an_ungraded_final(){
  let t=temp();let out=output(&t);fs::write(&out,(0..96).flat_map(|_|0.18f32.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let early=publish_restored(&out,8,4,"sRGB",&mut||false).unwrap().unwrap();let before=fs::read(&early).unwrap();
  assert!(publish_finished(&out,8,4,"sRGB",&mut||true).is_err());
  assert!(!finished_path(&out).unwrap().exists());
  fs::write(&out,b"incomplete").unwrap();assert!(publish_finished(&out,8,4,"sRGB",&mut||false).is_err());
  assert!(!finished_path(&out).unwrap().exists());assert_eq!(fs::read(early).unwrap(),before);
 }
 #[test]fn chunk_sampling_is_read_only_and_srgb_is_encoded_once(){
  let t=temp();let out=output(&t);let image=ImageBuf::from_data(8,4,vec![from_f64(0.18);8*4*3]);let before=image.data.clone();
  for space in ["sRGB","Display P3","Adobe RGB (1998)"]{
   let mut s=Sampler::new(8,4,space,1.).unwrap();s.capture(0,&image).unwrap();
   let p=s.publish(&out,&mut||false).unwrap().unwrap();assert_eq!(fs::read(p).unwrap(),crate::negative_preview::png_rgb(8,4,&vec![118;8*4*3]));assert_eq!(before,image.data);
  }
 }
 #[test]fn incomplete_or_cancelled_preview_never_replaces_publication(){
  let t=temp();let out=output(&t);let target=path(&out).unwrap();fs::write(&target,b"old-valid").unwrap();let mut s=Sampler::new(8,4,"sRGB",1.).unwrap();
  assert!(s.publish(&out,&mut||false).is_err());assert_eq!(fs::read(&target).unwrap(),b"old-valid");
  s.capture(0,&ImageBuf::from_data(8,4,vec![from_f64(0.18);96])).unwrap();let mut calls=0;
  assert!(s.publish(&out,&mut||{calls+=1;calls==4}).is_err());assert_eq!(fs::read(&target).unwrap(),b"old-valid");assert!(!target.with_extension("png.tmp").exists());
 }
 #[test]fn resumed_output_uses_same_transfer_without_exposure_twice(){
  let t=temp();let out=output(&t);fs::write(&out,(0..96).flat_map(|_|0.18f32.to_le_bytes()).collect::<Vec<_>>()).unwrap();let p=publish_restored(&out,8,4,"sRGB",&mut||false).unwrap().unwrap();assert_eq!(fs::read(p).unwrap(),crate::negative_preview::png_rgb(8,4,&vec![118;96]));
  assert!(publish_restored(&out,0,4,"sRGB",&mut||false).is_err());
 }
 #[test]fn chunk_partition_and_four_strata_match_whole_capture(){
  let values:Vec<_>=(0..63*37*3).map(|i|from_f64((i%91)as f64/100.)).collect();let mut a=Sampler::new(63,37,"sRGB",1.).unwrap();a.capture(0,&ImageBuf::from_data(63,37,values.clone())).unwrap();let mut b=Sampler::new(63,37,"sRGB",1.).unwrap();
  for y in (0..37).step_by(5){let h=5.min(37-y);b.capture(y,&ImageBuf::from_data(63,h,values[(y*63*3)as usize..((y+h)*63*3)as usize].to_vec())).unwrap();}
  assert_eq!(a.pixels,b.pixels);assert_eq!(a.next_y,b.next_y);assert_eq!(a.next_sy,b.next_sy);assert!(a.row.len() <= MAX_EDGE as usize);
 }
#[test]fn actual_route_output_is_bit_exact_and_preview_precedes_scanner_sharpen(){
 let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let(w,h)=(41,29);
 for (index,(film,direct)) in [("kodak_portra_400",false),("kodak_portra_400",true),("kodak_ektachrome_100",true)].into_iter().enumerate(){
  let root=std::env::temp_dir().join(format!("scan-integration-{}-{index}",std::process::id()));fs::create_dir_all(&root).unwrap();let _guard=Temp(root.clone());let input=root.join("input.rgb");fs::write(&input,(0..w*h*3).flat_map(|i|(0.02+((i*17)%91)as f32/110.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut p=spektrafilm_core::params::RuntimeParams::default();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.io.scan_film=direct;p.camera.auto_exposure=false;p.film_render.grain.active=false;p.film_render.halation.active=false;p.film_render.dir_couplers.active=false;p.print_render.glare.active=false;p.film_render.glare.active=false;p.scanner.unsharp_mask=[0.7,0.6];
  let baseline=root.join("baseline.rgb");crate::test_baseline_streamed16::export_file(&data,film,"kodak_portra_endura",p.clone(),&input,&baseline,&root.join("scratch-a"),w,h,384,0.,||false,|_,_,_|{}).unwrap();
  let actual=root.join("film-result-12345678-1234-1234-1234-123456789abc.rgb");let preview=path(&actual).unwrap();let observed=std::cell::Cell::new(false);
  crate::streamed::export_file(&data,film,"kodak_portra_endura",p,&input,&actual,&root.join("scratch-b"),w,h,384,0.,||false,|phase,_,_|{if phase=="scan_sharpen" && preview.is_file(){observed.set(true)}}).unwrap();
  assert_eq!(fs::read(&baseline).unwrap(),fs::read(&actual).unwrap(),"Presentation must not alter {film}/{direct} scientific output bytes");assert!(observed.get(),"Full-color source must exist while scanner sharpening still runs");assert!(preview.is_file());
 }
}

 #[test]fn nonneutral_output_primaries_and_half_exposure_have_independent_encoded_oracle(){
  let t=temp();let out=output(&t);let image=ImageBuf::from_data(1,1,vec![from_f64(0.2),from_f64(0.4),from_f64(0.6)]);
  // Independently calculated D65 RGB-primary matrices + IEC sRGB transfer, rounded8-bit.
  for(space,expected)in [("sRGB",[89,124,149]),("Display P3",[79,125,152]),("Adobe RGB (1998)",[69,124,150])]{
   let mut s=Sampler::new(1,1,space,0.5).unwrap();s.capture(0,&image).unwrap();
   let p=s.publish(&out,&mut||false).unwrap().unwrap();assert_eq!(fs::read(p).unwrap(),crate::negative_preview::png_rgb(1,1,&expected));
  }
 }
 #[test]fn stage_six_checkpoint_restore_cancel_and_optional_preview_failure_preserve_science(){
  let t=temp();let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let(w,h)=(19,13);let input=t.0.join("input.rgb");fs::write(&input,(0..w*h*3).flat_map(|i|(0.1+(i%31)as f32/50.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut p=spektrafilm_core::params::RuntimeParams::default();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.camera.auto_exposure=false;p.film_render.grain.active=false;p.film_render.halation.active=false;p.film_render.dir_couplers.active=false;
  let cp=t.0.join("checkpoint");let first=output(&t);
  crate::streamed::export_file_checkpointed(&data,"kodak_portra_400","kodak_portra_endura",p.clone(),&input,&first,&t.0.join("scratch1"),Some(&cp),w,h,384,0.5,false,||false,|_|{}).unwrap();
  let expected=fs::read(&first).unwrap();let manifest=fs::read(cp.join("manifest.json")).unwrap();let cp_data:Vec<_>=fs::read_dir(cp.join("stage-6")).unwrap().map(|entry|{let path=entry.unwrap().path();(path.clone(),fs::read(path).unwrap())}).collect();
  let second=t.0.join("film-result-22345678-1234-1234-1234-123456789abc.rgb");let thumb=path(&second).unwrap();
  crate::streamed::export_file_checkpointed(&data,"kodak_portra_400","kodak_portra_endura",p.clone(),&input,&second,&t.0.join("scratch2"),Some(&cp),w,h,384,0.5,false,||false,|_|{}).unwrap();
  assert_eq!(expected,fs::read(&second).unwrap());assert!(thumb.is_file());
  let third=t.0.join("film-result-32345678-1234-1234-1234-123456789abc.rgb");let stop=std::cell::Cell::new(false);
  assert!(crate::streamed::export_file_checkpointed(&data,"kodak_portra_400","kodak_portra_endura",p.clone(),&input,&third,&t.0.join("scratch3"),Some(&cp),w,h,384,0.5,false,||stop.get(),|v|{if v.display_stage==6&&v.stage_done>0{stop.set(true)}}).is_err());
  assert!(!third.exists());assert!(!path(&third).unwrap().exists());
  let fourth=t.0.join("film-result-42345678-1234-1234-1234-123456789abc.rgb");fs::create_dir(path(&fourth).unwrap()).unwrap();
  crate::streamed::export_file_checkpointed(&data,"kodak_portra_400","kodak_portra_endura",p,&input,&fourth,&t.0.join("scratch4"),Some(&cp),w,h,384,0.5,false,||false,|_|{}).unwrap();
  assert_eq!(expected,fs::read(fourth).unwrap());assert_eq!(manifest,fs::read(cp.join("manifest.json")).unwrap());for(path,bytes)in cp_data{assert_eq!(bytes,fs::read(path).unwrap());}
 }
 #[test]
 fn native_admission_plan_keeps_original_export_threshold() {
     for budget in [128, 384, 512] {
         let mut params = crate::mobile::MobileParams::default();
         params.memory_budget_mi_b = budget;
         let p = crate::mobile::plan_export(6000, 4000, &params).unwrap();
         let baseline = 115 * 1024 * 1024 + 256 * 32768 + crate::export_execution::EXPORT_STACK_RESERVE_BYTES;
         assert_eq!(p.estimated_native_working_bytes - p.background_preview_lease_bytes,
             baseline.max(budget as u64 * 1024 * 1024));
     }
 }

 #[test]
 fn high_detail_dimensions_follow_actual_row_budget_without_upscaling() {
     for (width, height, expected) in [
         (6144, 4096, (768, 512)), (6144, 8192, (576, 768)),
         (8192, 6144, (768, 576)), (40000, 2000, (768, 38)),
         (16000, 12000, (466, 349)), (40000, 40000, (139, 139)),
         (1, 40000, (1, 768)), (13, 7, (13, 7)),
     ] {
         let edge = restored_edge(width, height).unwrap();
         let (w, h) = dimensions(width, height, edge).unwrap();
         assert_eq!((w, h), expected, "{width}x{height}");
         assert!(width as u64 * 12 * 2 * h as u64 <= RESUME_READ_BUDGET);
         assert!(h * 2 <= MAX_EDGE * 2);
         assert!(w <= width && h <= height);
     }
 }

 #[test]
 fn high_detail_chunk_and_finalized_file_sampling_match_all_output_primaries() {
     let t = temp(); let out = output(&t); let (width, height) = (1536u32, 1024u32);
     let mut file = File::create(&out).unwrap();
     let spaces = ["sRGB", "Display P3", "Adobe RGB (1998)"];
     let mut samplers: Vec<_> = spaces.iter().map(|space| Sampler::new(width, height, space, 1.).unwrap()).collect();
     for y in (0..height).step_by(17) {
         let count = 17.min(height - y);
         let values: Vec<f32> = (y * width * 3..(y + count) * width * 3).map(|i| ((i * 13 % 251) as f32) / 256.).collect();
         file.write_all(&values.iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
         let chunk = ImageBuf::from_data(width, count, values.into_iter().map(|v| from_f64(v as f64)).collect());
         for sampler in &mut samplers { sampler.capture(y, &chunk).unwrap(); }
     }
     file.sync_all().unwrap(); drop(file);
     let original = fs::read(&out).unwrap();
     for (space, sampler) in spaces.into_iter().zip(samplers) {
         assert_eq!((sampler.w, sampler.h), (768, 512));
         let target = sampler.publish(&out, &mut || false).unwrap().unwrap(); let expected = fs::read(&target).unwrap();
         publish_restored(&out, width, height, space, &mut || false).unwrap();
         assert_eq!(fs::read(target).unwrap(), expected, "{space}");
     }
     assert_eq!(fs::read(out).unwrap(), original);
 }

 #[test]
 fn higher_detail_sampling_retains_features_missing_at_256() {
     let (width, height) = (1536u32, 6u32);
     let source = ImageBuf::from_data(width, height, (0..width * height * 3)
         .map(|i| from_f64(if (i / 3) % 6 == 2 { 1. } else { 0. })).collect());
     let original = source.data.clone();
     let mut old = Sampler::edge(width, height, 256, "sRGB", 1.).unwrap();
     let mut current = Sampler::new(width, height, "sRGB", 1.).unwrap();
     old.capture(0, &source).unwrap();
     current.capture(0, &source).unwrap();
     assert!(old.pixels.iter().all(|value| *value == 0));
     assert!(current.pixels.iter().any(|value| *value == 188));
     assert_eq!(source.data, original);
 }

 #[test]
 fn full_scan_png_and_live_allocations_fit_declared_bounds() {
     let t = temp();
     let out = output(&t);
     let mut sampler = Sampler::new(MAX_EDGE, MAX_EDGE, "sRGB", 1.).unwrap();
     let row = ImageBuf::from_data(MAX_EDGE, 1, vec![from_f64(0.18); MAX_EDGE as usize * 3]);
     for y in 0..MAX_EDGE { sampler.capture(y, &row).unwrap(); }
     let target = sampler.publish(&out, &mut || false).unwrap().unwrap();
     let png_len = fs::metadata(target).unwrap().len();
     assert!(png_len <= 2 * 1024 * 1024);
     // Count actual sampler capacities, the only PNG block, maximum-width
     // restore row (even though dropped before encoding), and 128 KiB slack.
     // No BufWriter, full PNG/zlib/raw buffer or full f64 image exists.
     let peak = sampler.pixels.capacity() as u64 + sampler.row.capacity() as u64 * 24
         + DEFLATE_BLOCK as u64 + u64::from(MAX_SOURCE_EDGE) * 12 + 128 * 1024;
     assert!(peak < MEMORY_RESERVATION_BYTES, "{peak}");
     eprintln!("SCAN_PREVIEW_BOUND edge={MAX_EDGE} png={png_len} conservative_live={peak} reserved={MEMORY_RESERVATION_BYTES}");
 }

 #[test]
 fn streamed_png_matches_whole_buffer_encoder_at_block_and_row_boundaries() {
     for (w,h) in [(1u32,1u32),(63,37),(428,51),(85,256),(768,768)] {
         let pixels: Vec<_> = (0..w*h*3).map(|i| ((i*17)%251) as u8).collect();
         let mut encoded = Vec::new(); write_png(&mut encoded,w,h,&pixels,&mut||false).unwrap();
         assert_eq!(encoded,crate::negative_preview::png_rgb(w,h,&pixels),"{w}x{h}");
     }
 }

 #[test]
 fn partial_rows_keep_linear_precision_and_match_original_full_frame_accumulator() {
     let (width,height)=(1597u32,1003u32);
     let value=|i:u32| from_f64((i*17%401)as f64/251.-0.2) as f64;
     for space in ["sRGB","Display P3","Adobe RGB (1998)"] { for exposure in [0.5,2.] {
         let mut sampler=Sampler::new(width,height,space,exposure).unwrap();
         for y in (0..height).step_by(17) {
             let h=17.min(height-y);
             let image=ImageBuf::from_data(width,h,(y*width*3..(y+h)*width*3).map(|i|from_f64(value(i))).collect());
             sampler.capture(y,&image).unwrap();
         }
         let mut sums=vec![[0.;3];(sampler.w*sampler.h)as usize];
         for y in 0..sampler.h {for sy in 0..2 {let iy=coordinate(y,sy,height,sampler.h);
             for x in 0..sampler.w {for sx in 0..2 {let ix=coordinate(x,sx,width,sampler.w);
                 for ch in 0..3 {sums[(y*sampler.w+x)as usize][ch]+=value((iy*width+ix)*3+ch as u32)*0.25;}
             }}
         }}
         let mut expected=Vec::new();
         for sum in sums {for v in spektrafilm_core::output_color::to_linear_srgb(sum.map(|v|v*exposure),space) {
             let v=v.clamp(0.,1.);let v=if v<=0.0031308{12.92*v}else{1.055*v.powf(1./2.4)-0.055};expected.push((v*255.).round().clamp(0.,255.)as u8);
         }}
         assert_eq!(sampler.pixels,expected,"{space}/{exposure}");
     }}
 }

 #[test]
 fn missing_or_duplicate_chunks_and_incomplete_last_row_never_publish() {
     let t=temp();let out=output(&t);let target=path(&out).unwrap();fs::write(&target,b"old-valid").unwrap();
     let chunk=ImageBuf::from_data(1536,1,vec![from_f64(0.2);1536*3]);
     let mut sampler=Sampler::new(1536,1024,"sRGB",1.).unwrap();sampler.capture(0,&chunk).unwrap();
     assert_eq!(sampler.next_sy,1);assert_eq!(sampler.next_y,0);
     assert!(sampler.publish(&out,&mut||false).is_err());
     assert!(sampler.capture(0,&chunk).is_err());assert!(sampler.capture(1,&chunk).is_err());
     let mut missing=Sampler::new(1536,1024,"sRGB",1.).unwrap();assert!(missing.capture(1,&chunk).is_err());
     let mut empty=Sampler::new(1536,1024,"sRGB",1.).unwrap();
     assert!(empty.capture(0,&ImageBuf::from_data(1536,0,Vec::new())).is_err());
     // Every selected stratum may arrive before the final unsampled source row.
     // A complete thumbnail must still wait for complete source-chunk coverage.
     let mut tail=Sampler::edge(8,40,8,"sRGB",1.).unwrap();
     tail.capture(0,&ImageBuf::from_data(8,39,vec![from_f64(0.2);8*39*3])).unwrap();
     assert_eq!(tail.next_y,tail.h);assert!(tail.publish(&out,&mut||false).is_err());
     assert_eq!(fs::read(&target).unwrap(),b"old-valid");
     tail.capture(39,&ImageBuf::from_data(8,1,vec![from_f64(0.2);8*3])).unwrap();
     assert!(tail.publish(&out,&mut||false).unwrap().is_some());
 }

 #[test]
 fn failed_restoration_never_replaces_existing_preview_or_modifies_output() {
     let t = temp();
     let out = output(&t);
     let target = path(&out).unwrap();
     fs::write(&target, b"previous complete preview").unwrap();
     let source: Vec<_> = (0..96).flat_map(|_| 0.18f32.to_le_bytes()).collect();
     fs::write(&out, &source[..source.len() - 1]).unwrap();
     assert!(publish_restored(&out, 8, 4, "sRGB", &mut || false).is_err());
     assert_eq!(fs::read(&target).unwrap(), b"previous complete preview");
     fs::write(&out, &source).unwrap();
     let mut checks = 0;
     assert!(publish_restored(&out, 8, 4, "sRGB", &mut || { checks += 1; checks == 3 }).is_err());
     assert_eq!(fs::read(&target).unwrap(), b"previous complete preview");
     assert_eq!(fs::read(&out).unwrap(), source);
     assert!(!target.with_extension("png.tmp").exists());
     let mut corrupt = source.clone();
     corrupt[..4].copy_from_slice(&f32::NAN.to_le_bytes());
     fs::write(&out, &corrupt).unwrap();
     assert!(publish_restored(&out, 8, 4, "sRGB", &mut || false).is_err());
     assert_eq!(fs::read(&target).unwrap(), b"previous complete preview");
     assert_eq!(fs::read(out).unwrap(), corrupt);
 }

 #[test]
 #[ignore = "Explicit bounded real-file host benchmark; writes at most 576 MiB at a time"]
 fn benchmark_restored_high_width() {
     for (width, height) in [(6144u32, 4096u32), (6144, 8192), (8192, 6144), (16000, 1000)] {
         let t = temp();
         let out = output(&t);
         let row: Vec<_> = (0..width * 3).flat_map(|i| ((i % 191) as f32 / 256.).to_le_bytes()).collect();
         let mut file = File::create(&out).unwrap();
         for _ in 0..height { file.write_all(&row).unwrap(); }
         file.sync_all().unwrap();
         drop(file);
         let edge = restored_edge(width, height).unwrap();
         let (w, h) = dimensions(width, height, edge).unwrap();
         let start = std::time::Instant::now();
         let target = publish_restored(&out, width, height, "sRGB", &mut || false).unwrap().unwrap();
         eprintln!("SCAN_RESTORE_BENCH source={width}x{height} thumb={w}x{h} max_read_bytes={} max_row_reads={} png={} elapsed_ms={:.3}",
             width as u64 * 12 * h as u64 * 2, h * 2, fs::metadata(target).unwrap().len(), start.elapsed().as_secs_f64() * 1000.);
     }
 }

 #[cfg(feature = "adreno-gpu")]
 #[test]
 #[ignore = "Requires a configured actual GPU; never pass silently via CPU fallback"]
 fn actual_gpu_scan_preview_preserves_output_bytes() {
     let gpu = crate::mobile::full_chain_gpu().expect("An actual GPU is required");
     assert!(gpu.is_healthy());
     let t = temp();
     let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
     let (width, height) = (800u32, 32u32);
     let input = t.0.join("input.rgb");
     fs::write(&input, (0..width * height * 3).flat_map(|i| (0.02 + (i % 191) as f32 / 256.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
     for (index, (film, direct, space)) in [
         ("kodak_portra_400", false, "sRGB"),
         ("kodak_portra_400", true, "Display P3"),
         ("kodak_ektachrome_100", true, "Adobe RGB (1998)"),
     ].into_iter().enumerate() {
         // Each export owns a fresh destination and scratch directory. Reusing
         // an already written path must remain INVALID_PATH in production.
         let case = temp();
         let mut p = spektrafilm_core::params::RuntimeParams::default();
         p.io.input_cctf_decoding = false; p.io.output_cctf_encoding = false;
         p.io.scan_film = direct; p.io.output_color_space = space.into();
         p.camera.auto_exposure = false; p.film_render.grain.active = false;
         p.film_render.halation.active = false; p.film_render.dir_couplers.active = false;
         p.print_render.glare.active = false; p.film_render.glare.active = false;
         let baseline = case.0.join(format!("baseline-{index}.rgb"));
         let actual = output(&case);
         for (destination, scratch) in [(&baseline, "baseline-scratch"), (&actual, "actual-scratch")] {
             let metrics = crate::streamed::export_file_with_gpu(&data, film, "kodak_portra_endura", p.clone(), &input,
                 destination, &case.0.join(scratch), width, height, 384, 0.5, true, || false, |_, _, _| {}).unwrap();
             assert!(metrics.gpu.active && metrics.gpu.gpu_batches > 0);
         }
         assert_eq!(fs::read(baseline).unwrap(), fs::read(&actual).unwrap());
         let png = fs::read(path(&actual).unwrap()).unwrap();
         assert_eq!(u32::from_be_bytes(png[16..20].try_into().unwrap()), MAX_EDGE);
         assert_eq!(u32::from_be_bytes(png[20..24].try_into().unwrap()), 30);
         eprintln!("GPU_SCAN_PREVIEW_PASS film={film} direct={direct} space={space} thumb=768x30 output_bytes_identical");
         // Exercise the GPU-owned stage-6 checkpoint, not just the CPU restore
         // route. Reversal has no concurrent negative-preview worker.
         if film == "kodak_ektachrome_100" {
             let checkpoint = case.0.join("gpu-checkpoint");
             let first_output = case.0.join("film-result-22345678-1234-1234-1234-123456789abc.rgb");
             let restored_output = case.0.join("film-result-32345678-1234-1234-1234-123456789abc.rgb");
             let first = crate::streamed::export_file_checkpointed(&data, film, "kodak_portra_endura", p.clone(), &input,
                 &first_output, &case.0.join("gpu-first-scratch"), Some(&checkpoint), width, height, 384, 0.5, true, || false, |_| {}).unwrap();
             assert!(first.gpu.active && first.gpu.gpu_batches > 0);
             let expected = fs::read(&first_output).unwrap();
             let expected_png = fs::read(path(&first_output).unwrap()).unwrap();
             assert!(!restored_output.exists() && !path(&restored_output).unwrap().exists());
             let restored = crate::streamed::export_file_checkpointed(&data, film, "kodak_portra_endura", p, &input,
                 &restored_output, &case.0.join("gpu-restore-scratch"), Some(&checkpoint), width, height, 384, 0.5, true, || false, |_| {}).unwrap();
             assert_eq!(restored.gpu.reason, "gpu_checkpoint_restored");
             assert_eq!(expected, fs::read(&restored_output).unwrap());
             assert_eq!(expected_png, fs::read(path(&restored_output).unwrap()).unwrap());
             eprintln!("GPU_SCAN_CHECKPOINT_PASS fresh_output RGB_and_PNG_bytes_identical brightness_ev=0.5");
         }
     }
 }
}
