//! A stage-4/5 film light-table illustration, NOT a print preview or inverted RGB.
//! Real CMY density -> spectral transmittance under D65 -> CIE XYZ -> sRGB.
//! No auto-exposure, scanner glare, luminance remap, paper, or creative tone curve.
//! Four stratified source samples per thumbnail pixel are averaged in light (XYZ),
//! not log density. This is a bounded spatial approximation, not an exact area scan.
use spektrafilm_core::profile::Profile;
use spektrafilm_math::{colorspace, spectral};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
};

pub const MAX_EDGE: u32 = 384;
const GPU_BASE_EDGE: u32 = 256;
const MAX_SOURCE_EDGE: u32 = 131_072; // At most three 1 MiB source row buffers.
fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}
fn cancelled(cancel: &mut impl FnMut() -> bool) -> io::Result<()> {
    if cancel() {
        Err(io::Error::new(
            io::ErrorKind::Interrupted,
            "Negative preview cancelled",
        ))
    } else {
        Ok(())
    }
}
struct TempGuard(PathBuf);
impl Drop for TempGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// Accepts a resolved runtime profile (`Pipeline::film`), not an unselected B&W
/// development-time family. Do not resolve again here: the pipeline owns that choice.
/// Returns None for reversal/positive/unknown film types: never fabricate a negative.
/// A non-cancellation preview failure may be ignored by the exporter; checkpoints
/// remain authoritative. Published files are complete PNGs; stale .tmp is removed
/// on retry. Only call with an exclusively owned export-job directory.
pub fn write_negative_preview(
    planes: &[PathBuf; 3],
    profile: &Profile,
    width: u32,
    height: u32,
    job_dir: &Path,
    cancel: &mut impl FnMut() -> bool,
) -> io::Result<Option<PathBuf>> {
    cancelled(cancel)?;
    if profile.info.film_type != "negative" {
        return Ok(None);
    }
    if width == 0 || height == 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE {
        return Err(invalid("Invalid negative preview dimensions"));
    }
    let model = LightTable::new(profile)?;
    let edge = width.max(height);
    let w = ((width as u64 * MAX_EDGE.min(edge) as u64) / edge as u64).max(1) as u32;
    let h = ((height as u64 * MAX_EDGE.min(edge) as u64) / edge as u64).max(1) as u32;
    let mut files = planes
        .iter()
        .map(File::open)
        .collect::<io::Result<Vec<_>>>()?;
    let expected = width as u64 * height as u64 * 8;
    for file in &files {
        if file.metadata()?.len() != expected {
            return Err(invalid("Invalid density plane size"));
        }
    }
    let mut rows = vec![vec![0u8; width as usize * 8]; 3];
    let mut rgb = vec![0u8; w as usize * h as usize * 3];
    for y in 0..h {
        cancelled(cancel)?;
        let mut xyz_row = vec![[0.; 3]; w as usize];
        for sy in 0..2 {
            let iy = sample_coordinate(y, sy, height, h);
            for (file, row) in files.iter_mut().zip(&mut rows) {
                file.seek(SeekFrom::Start(iy as u64 * width as u64 * 8))?;
                file.read_exact(row)?;
            }
            for x in 0..w {
                for sx in 0..2 {
                    let ix = sample_coordinate(x, sx, width, w) as usize;
                    let density = std::array::from_fn(|c| {
                        f64::from_le_bytes(rows[c][ix * 8..ix * 8 + 8].try_into().unwrap())
                    });
                    if density.iter().any(|v| !v.is_finite()) {
                        return Err(invalid("Nonfinite film density"));
                    }
                    let xyz = model.xyz(density)?;
                    for c in 0..3 {
                        xyz_row[x as usize][c] += xyz[c] * 0.25;
                    }
                }
            }
        }
        for x in 0..w as usize {
            let encoded = encode_srgb(xyz_row[x])?;
            let offset = (y as usize * w as usize + x) * 3;
            rgb[offset..offset + 3].copy_from_slice(&encoded);
        }
    }
    cancelled(cancel)?;
    let png = png_rgb(w, h, &rgb);
    let output = job_dir.join("negative-preview.png");
    let temp = job_dir.join("negative-preview.png.tmp");
    match fs::remove_file(&temp) {
        Ok(()) => (),
        Err(e) if e.kind() == io::ErrorKind::NotFound => (),
        Err(e) => return Err(e),
    }
    let _guard = TempGuard(temp.clone());
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp)?;
    file.write_all(&png)?;
    file.sync_all()?;
    drop(file);
    cancelled(cancel)?;
    fs::rename(&temp, &output)?;
    File::open(job_dir)?.sync_all()?;
    Ok(Some(output))
}
pub(crate) fn sample_coordinate(pixel: u32, sub: u32, source: u32, target: u32) -> u32 {
    (((pixel as u64 * 4 + sub as u64 * 2 + 1) * source as u64) / (target as u64 * 4))
        .min(source as u64 - 1) as u32
}

/// Convert complete, stratified samples of genuine developed film density.
/// Four entries per output pixel are ordered top-left, top-right, bottom-left,
/// bottom-right. This is intentionally pure: the caller controls publication.
pub(crate) fn render_sampled_negative(
    profile: &Profile,
    width: u32,
    height: u32,
    samples: &[[f32; 3]],
    cancel: &mut impl FnMut() -> bool,
) -> io::Result<Option<Vec<u8>>> {
    cancelled(cancel)?;
    if profile.info.film_type != "negative" { return Ok(None); }
    if width == 0 || height == 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE {
        return Err(invalid("Invalid negative preview dimensions"));
    }
    let edge = width.max(height);
    let w = ((width as u64 * MAX_EDGE.min(edge) as u64) / edge as u64).max(1) as u32;
    let h = ((height as u64 * MAX_EDGE.min(edge) as u64) / edge as u64).max(1) as u32;
    if samples.len() != (w as usize) * (h as usize) * 4 {
        return Err(invalid("Incomplete film density samples"));
    }
    let model = LightTable::new(profile)?;
    let mut rgb = Vec::with_capacity(w as usize * h as usize * 3);
    for row in samples.chunks_exact(w as usize * 4) {
        cancelled(cancel)?;
        for px in row.chunks_exact(4) {
            let mut xyz = [0.; 3];
            for density in px {
                if density.iter().any(|v| !v.is_finite()) {
                    return Err(invalid("Nonfinite film density"));
                }
                let light = model.xyz(density.map(f64::from))?;
                for c in 0..3 { xyz[c] += light[c] * 0.25; }
            }
            rgb.extend_from_slice(&encode_srgb(xyz)?);
        }
    }
    cancelled(cancel)?;
    Ok(Some(png_rgb(w, h, &rgb)))
}

/// A low-resolution model already yields one complete developed density per
/// preview pixel. Avoid evaluating four identical spectral samples per pixel.
pub(crate) fn render_early_negative(profile:&Profile,width:u32,height:u32,samples:&[[f32;3]],cancel:&mut impl FnMut()->bool) -> io::Result<Option<Vec<u8>>> {
    cancelled(cancel)?;
    if profile.info.film_type!="negative" {return Ok(None);}
    if width==0 || height==0 || width>MAX_EDGE || height>MAX_EDGE || samples.len()!=width as usize*height as usize {
        return Err(invalid("Invalid low-resolution density"));
    }
    let model=LightTable::new(profile)?;
    let mut rgb=Vec::with_capacity(width as usize*height as usize*3);
    for row in samples.chunks_exact(width as usize) {
        cancelled(cancel)?;
        for density in row {
            if density.iter().any(|v|!v.is_finite()) {return Err(invalid("Nonfinite film density"));}
            rgb.extend_from_slice(&encode_srgb(model.xyz(density.map(f64::from))?)?);
        }
    }
    cancelled(cancel)?;
    Ok(Some(png_rgb(width,height,&rgb)))
}

/// Bounded, optional assembly of genuine density samples from disjoint tile
/// cores. A NaN sentinel means missing; incomplete captures are never rendered.
pub(crate) struct GpuDensityAssembly {
    width: u32,
    height: u32,
    thumb_w: u32,
    thumb_h: u32,
    values: Vec<[f32; 3]>,
    received: usize,
}
impl GpuDensityAssembly {
    fn lower(source:u32, target:u32, bound:u32) -> u32 {
        let (mut lo,mut hi) = (0,target*2);
        while lo<hi {
            let mid=lo+(hi-lo)/2;
            if sample_coordinate(mid/2,mid%2,source,target)<bound {lo=mid+1;} else {hi=mid;}
        }
        lo
    }
    pub(crate) fn new(width: u32, height: u32) -> Option<Self> {
        Self::with_edge(width, height, GPU_BASE_EDGE)
    }
    fn empty(width: u32, height: u32, cap: u32) -> Option<Self> {
        if width == 0 || height == 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE { return None; }
        let edge = width.max(height);
        if cap == 0 || cap > MAX_EDGE { return None; }
        let thumb_w = ((width as u64 * cap.min(edge) as u64) / edge as u64).max(1) as u32;
        let thumb_h = ((height as u64 * cap.min(edge) as u64) / edge as u64).max(1) as u32;
        Some(Self { width, height, thumb_w, thumb_h, values: Vec::new(), received: 0 })
    }
    fn allocate(mut self) -> Option<Self> {
        let count = (self.thumb_w as usize).checked_mul(self.thumb_h as usize)?.checked_mul(4)?;
        self.values.try_reserve_exact(count).ok()?;
        self.values.resize(count, [f32::NAN; 3]);
        Some(self)
    }
    pub(crate) fn with_edge(width: u32, height: u32, cap: u32) -> Option<Self> {
        Self::empty(width, height, cap)?.allocate()
    }
    pub(crate) fn edge(&self) -> u32 { self.thumb_w.max(self.thumb_h) }
    /// Use only memory left over by the original 256-edge capture plan. Never
    /// shrink tiles, alter full-resolution reduction order, or add export work
    /// to make room for a sharper display-only image. Plans include baseline
    /// capture reservation; each is (core width, core height, planned bytes).
    pub(crate) fn upgrade_for_plans(self, plans: &[(u32, u32, u64)], budget: u64) -> Option<Self> {
        let candidate = Self::empty(self.width, self.height, MAX_EDGE)?;
        if self.received != 0 || candidate.edge() == self.edge() || plans.is_empty() { return Some(self); }
        let fits = plans.iter().all(|&(w, h, bytes)| {
            self.peak_reservation(w, h).and_then(|old| bytes.checked_sub(old))
                .and_then(|base| candidate.peak_reservation(w, h).and_then(|extra| base.checked_add(extra)))
                .is_some_and(|total| total <= budget)
        });
        if !fits { return Some(self); }
        // Release the old sample array before allocating its replacement.
        drop(self);
        candidate.allocate()
    }
    /// Conservative extra live bytes: full density samples; per-tile request,
    /// result, GPU index/gather/readback; and space for optional image encoding.
    pub(crate) fn peak_reservation(&self, core_w:u32, core_h:u32) -> Option<u64> {
        if core_w==0 || core_h==0 {return None;}
        let mut maximum=0usize;
        let mut y=0;
        while y<self.height {
            let bottom=y.saturating_add(core_h).min(self.height);
            let mut x=0;
            while x<self.width {
                let right=x.saturating_add(core_w).min(self.width);
                let nx=Self::lower(self.width,self.thumb_w,right)-Self::lower(self.width,self.thumb_w,x);
                let ny=Self::lower(self.height,self.thumb_h,bottom)-Self::lower(self.height,self.thumb_h,y);
                maximum=maximum.max((nx as usize).checked_mul(ny as usize)?);
                x=right;
            }
            y=bottom;
        }
        let sample_bytes=u64::from(self.thumb_w).checked_mul(u64::from(self.thumb_h))?.checked_mul(48)?;
        let tile_bytes=(maximum as u64).checked_mul(56)?.checked_add(16)?;
        sample_bytes.checked_add(tile_bytes)?.checked_add(2*1024*1024)
    }
    /// Coordinates are local to the expanded image; ownership uses core only.
    pub(crate) fn samples_for_core(&self, left:u32, top:u32, x:u32, y:u32, right:u32, bottom:u32)
        -> Vec<spektrafilm_gpu::FilmDensitySample> {
        if left>x || top>y || x>right || y>bottom || right>self.width || bottom>self.height { return Vec::new(); }
        let (x0,x1)=(Self::lower(self.width,self.thumb_w,x),Self::lower(self.width,self.thumb_w,right));
        let (y0,y1)=(Self::lower(self.height,self.thumb_h,y),Self::lower(self.height,self.thumb_h,bottom));
        let mut out = Vec::new();
        if out.try_reserve_exact((x1-x0) as usize*(y1-y0) as usize).is_err() {return out;}
        for qy in y0..y1 { for qx in x0..x1 {
            let (ty,sy)=(qy/2,qy%2); let (tx,sx)=(qx/2,qx%2);
            let gx=sample_coordinate(tx,sx,self.width,self.thumb_w);
            let gy=sample_coordinate(ty,sy,self.height,self.thumb_h);
            let index=((ty*self.thumb_w+tx)*4)+sy*2+sx;
            out.push(spektrafilm_gpu::FilmDensitySample { index, x:gx-left, y:gy-top });
        }}
        out
    }
    pub(crate) fn add(&mut self, expected:&[spektrafilm_gpu::FilmDensitySample], values:&[(u32,[f32;3])]) -> bool {
        if expected.len()!=values.len() { return false; }
        // The backend must return exactly the requested samples, in order.
        for (request,(index,value)) in expected.iter().zip(values) {
            if request.index != *index || value.iter().any(|v| !v.is_finite()) { return false; }
            let Some(slot) = self.values.get_mut(*index as usize) else { return false; };
            if slot[0].is_finite() { return false; }
            *slot = *value;
            self.received += 1;
        }
        true
    }
    pub(crate) fn complete(self) -> Option<(u32, u32, Vec<[f32;3]>)> {
        (self.received == self.values.len()).then_some((self.thumb_w, self.thumb_h, self.values))
    }
}

pub(crate) fn valid_attempt(s:&str) -> bool {
    s.len()==36 && s.bytes().enumerate().all(|(i,b)|
        if [8,13,18,23].contains(&i) { b==b'-' } else { b.is_ascii_digit() || (b'a'..=b'f').contains(&b) })
}
pub(crate) fn same_attempt(dir:&Path, attempt:&str) -> bool {
    fs::read_to_string(dir.join("scan-preview-attempt")).ok().as_deref()==Some(attempt)
}
fn write_status(dir:&Path,attempt:&str,status:&str,early:bool) -> io::Result<()> {
    if !valid_attempt(attempt) || !["pending","ready","skipped_budget","failed","cancelled","not_applicable"].contains(&status) || !same_attempt(dir,attempt) {
        return Err(invalid("Expired preview attempt"));
    }
    let prefix=if early {"negative-preview-early-"}else{"negative-preview-"};
    let target=dir.join(format!("{prefix}{attempt}.status"));
    let temp=dir.join(format!("{prefix}{attempt}.status.part"));
    let _guard=TempGuard(temp.clone());
    let mut file=OpenOptions::new().write(true).create(true).truncate(true).open(&temp)?;
    file.write_all(status.as_bytes())?; drop(file);
    if !same_attempt(dir,attempt) { return Err(invalid("Expired preview attempt")); }
    fs::rename(temp,target)
}
fn write_attempt_status(dir:&Path,attempt:&str,status:&str) -> io::Result<()> {write_status(dir,attempt,status,false)}
pub(crate) fn write_early_attempt_status(dir:&Path,attempt:&str,status:&str) -> io::Result<()> {write_status(dir,attempt,status,true)}
pub(crate) fn mark_current_status(dir:&Path,status:&str,early:bool) -> io::Result<()> {
    let attempt=fs::read_to_string(dir.join("scan-preview-attempt"))?;
    write_status(dir,&attempt,status,early)
}

fn publish_for_attempt(dir:&Path,attempt:&str,profile:&Profile,width:u32,height:u32,samples:&[[f32;3]],early:bool,cancel:&mut impl FnMut()->bool) -> io::Result<()> {
    let mut cancelled=||cancel() || !same_attempt(dir,attempt);
    let png=if early {render_early_negative(profile,width,height,samples,&mut cancelled)?}else{render_sampled_negative(profile,width,height,samples,&mut cancelled)?}
        .ok_or_else(||invalid("Negative unavailable for this film"))?;
    let prefix=if early {"negative-preview-early-"}else{"negative-preview-"};
    let temp=dir.join(format!("{prefix}{attempt}.png.part"));
    let _guard=TempGuard(temp.clone());
    let mut file=OpenOptions::new().write(true).create_new(true).open(&temp)?;
    file.write_all(&png)?; drop(file);
    active_publish(&mut cancelled)?;
    fs::rename(temp,dir.join(format!("{prefix}{attempt}.png")))?;
    active_publish(&mut cancelled)?;
    write_status(dir,attempt,"ready",early)
}
fn active_publish(cancel:&mut impl FnMut()->bool) -> io::Result<()> {
    if cancel() {Err(io::Error::new(io::ErrorKind::Interrupted,"Negative preview cancelled"))} else {Ok(())}
}
pub(crate) fn publish_early_negative_for_attempt(dir:&Path,attempt:&str,profile:&Profile,width:u32,height:u32,samples:&[[f32;3]],cancel:&mut impl FnMut()->bool) -> io::Result<()> {
    publish_for_attempt(dir,attempt,profile,width,height,samples,true,cancel)
}

/// A detached best-effort visual artifact; it neither holds the export permit
/// nor changes rendering, saving, or terminal state. UUID-scoped names ensure
/// a canceled/retried attempt can never be selected by a newer attempt.
pub(crate) fn publish_sampled_negative_async(
    dir:&Path, profile:Profile, width:u32, height:u32, samples:Vec<[f32;3]>,
) -> io::Result<()> {
    let attempt=fs::read_to_string(dir.join("scan-preview-attempt"))?;
    if !valid_attempt(&attempt) {return Err(invalid("Invalid preview attempt"));}
    write_attempt_status(dir,&attempt,"pending")?;
    let dir=dir.to_path_buf();
    let thread_dir=dir.clone(); let thread_attempt=attempt.clone();
    let started=std::thread::Builder::new().name("film-negative-preview".into()).spawn(move || {
        let result=publish_for_attempt(&thread_dir,&thread_attempt,&profile,width,height,&samples,false,&mut ||false);
        if result.is_err() && same_attempt(&thread_dir,&thread_attempt) {
            let _=write_attempt_status(&thread_dir,&thread_attempt,"failed");
        }
    });
    match started {Ok(_) => Ok(()), Err(e) => {let _=write_attempt_status(&dir,&attempt,"failed");Err(e)}}
}
struct LightTable {
    dye: Vec<[f64; 3]>,
    base: Vec<f64>,
    weights: Vec<[f64; 3]>,
}
impl LightTable {
    fn new(profile: &Profile) -> io::Result<Self> {
        let n = spectral::N_WAVELENGTHS;
        if profile.data.channel_density.len() != n || profile.data.base_density.len() != n {
            return Err(invalid("Incomplete film spectrum"));
        }
        if !profile.data.wavelengths.is_empty()
            && (profile.data.wavelengths.len() != n
                || profile
                    .data
                    .wavelengths
                    .iter()
                    .enumerate()
                    .any(|(i, v)| (*v - (380. + i as f64 * 5.)).abs() > 1e-6))
        {
            return Err(invalid("Unsupported spectral wavelength grid"));
        }
        let normalization: f64 = (0..n)
            .map(|i| spectral::ILLUMINANT_D65_F64[i] * spectral::CMF_Y_F64[i])
            .sum();
        let mut dye = Vec::new();
        let mut base = Vec::new();
        let mut weights = Vec::new();
        for i in 0..n {
            let row = &profile.data.channel_density[i];
            if row.len() < 3 {
                return Err(invalid("Incomplete dye spectrum"));
            }
            // Same missing-wavelength convention as the physical scanner: absent
            // spectral measurements contribute zero light, never invented data.
            if !profile.data.base_density[i].is_finite() || row[..3].iter().any(|v| !v.is_finite())
            {
                continue;
            }
            dye.push([row[0], row[1], row[2]]);
            base.push(profile.data.base_density[i]);
            let light = spectral::ILLUMINANT_D65_F64[i] / normalization;
            weights.push([
                spectral::CMF_X_F64[i] * light,
                spectral::CMF_Y_F64[i] * light,
                spectral::CMF_Z_F64[i] * light,
            ]);
        }
        if weights.is_empty() {
            return Err(invalid("Missing film spectral measurements"));
        }
        Ok(Self { dye, base, weights })
    }
    fn xyz(&self, density: [f64; 3]) -> io::Result<[f64; 3]> {
        let mut xyz = [0.; 3];
        for i in 0..self.dye.len() {
            let d = self.base[i] + (0..3).map(|c| density[c] * self.dye[i][c]).sum::<f64>();
            let transmittance = 10f64.powf(-d);
            if !transmittance.is_finite() {
                return Err(invalid("Nonfinite spectral transmittance"));
            }
            for c in 0..3 {
                xyz[c] += transmittance * self.weights[i][c];
            }
        }
        Ok(xyz)
    }
}
fn encode_srgb(xyz: [f64; 3]) -> io::Result<[u8; 3]> {
    let mut out = [0; 3];
    for c in 0..3 {
        let v = (0..3)
            .map(|k| colorspace::XYZ_TO_SRGB_F64[c][k] * xyz[k])
            .sum::<f64>();
        if !v.is_finite() {
            return Err(invalid("Nonfinite preview color"));
        }
        // Display clipping, then standard sRGB OETF; no exposure compensation.
        let v = v.clamp(0., 1.);
        let v = if v <= 0.0031308 {
            12.92 * v
        } else {
            1.055 * v.powf(1. / 2.4) - 0.055
        };
        out[c] = (v * 255.).round() as u8;
    }
    Ok(out)
}
// Tiny standards-compliant RGB8 PNG encoder. Stored DEFLATE blocks avoid adding
// an Android dependency for a bounded thumbnail. CRC32 and Adler32 cover all data.
pub(crate) fn png_rgb(w: u32, h: u32, rgb: &[u8]) -> Vec<u8> {
    let mut raw = Vec::with_capacity(rgb.len() + h as usize);
    for row in rgb.chunks_exact(w as usize * 3) {
        raw.push(0);
        raw.extend_from_slice(row);
    }
    let count = raw.len().div_ceil(65535);
    let mut z = Vec::with_capacity(6 + raw.len() + count * 5);
    z.extend_from_slice(&[0x78, 0x01]);
    for (i, block) in raw.chunks(65535).enumerate() {
        z.push(if i + 1 == count { 1 } else { 0 });
        let len = block.len() as u16;
        z.extend_from_slice(&len.to_le_bytes());
        z.extend_from_slice(&(!len).to_le_bytes());
        z.extend_from_slice(block);
    }
    let (mut a, mut b) = (1u32, 0u32);
    for &v in &raw {
        a = (a + v as u32) % 65521;
        b = (b + a) % 65521;
    }
    z.extend_from_slice(&((b << 16) | a).to_be_bytes());
    let mut png = Vec::with_capacity(z.len() + 70);
    png.extend_from_slice(b"\x89PNG\r\n\x1a\n");
    let mut header = Vec::new();
    header.extend_from_slice(&w.to_be_bytes());
    header.extend_from_slice(&h.to_be_bytes());
    header.extend_from_slice(&[8, 2, 0, 0, 0]);
    chunk(&mut png, b"IHDR", &header);
    chunk(&mut png, b"sRGB", &[0]);
    chunk(&mut png, b"IDAT", &z);
    chunk(&mut png, b"IEND", &[]);
    png
}
fn chunk(png: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    png.extend_from_slice(&(data.len() as u32).to_be_bytes());
    png.extend_from_slice(kind);
    png.extend_from_slice(data);
    let mut crc = !0u32;
    for &b in kind.iter().chain(data) {
        crc ^= b as u32;
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xedb88320u32.wrapping_mul(crc & 1));
        }
    }
    png.extend_from_slice(&(!crc).to_be_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    static SEQ: AtomicU64 = AtomicU64::new(0);
    struct Fixture {
        dir: PathBuf,
        planes: [PathBuf; 3],
    }
    impl Fixture {
        fn new(w: u32, h: u32, value: impl Fn(u32, u32) -> f64) -> Self {
            let dir = std::env::temp_dir().join(format!(
                "film-negative-test-{}-{}",
                std::process::id(),
                SEQ.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&dir).unwrap();
            let planes = std::array::from_fn(|i| dir.join(format!("plane-{i}.f64")));
            for path in &planes {
                let mut f = File::create(path).unwrap();
                for y in 0..h {
                    for x in 0..w {
                        f.write_all(&value(x, y).to_le_bytes()).unwrap();
                    }
                }
            }
            Self { dir, planes }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.dir);
        }
    }
    fn profile() -> Profile {
        let mut p = spektrafilm_core::profile::load_profile_by_name(
            &PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data"),
            "kodak_portra_400",
        )
        .unwrap();
        p.data.channel_density = vec![vec![1. / 3.; 3]; spectral::N_WAVELENGTHS];
        p.data.base_density = vec![0.; spectral::N_WAVELENGTHS];
        p
    }
    // Independent stored-DEFLATE decoder for pixel assertions. External Pillow
    // decode additionally checks PNG CRC/zlib interoperability in the QA script.
    fn pixels(bytes: &[u8]) -> (u32, u32, Vec<u8>) {
        assert_eq!(&bytes[..8], b"\x89PNG\r\n\x1a\n");
        let w = u32::from_be_bytes(bytes[16..20].try_into().unwrap());
        let h = u32::from_be_bytes(bytes[20..24].try_into().unwrap());
        let mut offset = 8;
        let mut data = Vec::new();
        while offset < bytes.len() {
            let n = u32::from_be_bytes(bytes[offset..offset + 4].try_into().unwrap()) as usize;
            if &bytes[offset + 4..offset + 8] == b"IDAT" {
                data.extend_from_slice(&bytes[offset + 8..offset + 8 + n]);
            }
            offset += 12 + n;
        }
        let mut at = 2;
        let mut raw = Vec::new();
        loop {
            let final_block = data[at];
            at += 1;
            let n = u16::from_le_bytes(data[at..at + 2].try_into().unwrap()) as usize;
            assert_eq!(
                u16::from_le_bytes(data[at + 2..at + 4].try_into().unwrap()),
                !(n as u16)
            );
            at += 4;
            raw.extend_from_slice(&data[at..at + n]);
            at += n;
            if final_block == 1 {
                break;
            }
        }
        let mut rgb = Vec::new();
        for row in raw.chunks_exact(w as usize * 3 + 1) {
            assert_eq!(row[0], 0);
            rgb.extend_from_slice(&row[1..]);
        }
        (w, h, rgb)
    }
    #[test]
    fn neutral_density_obeys_transmission_not_rgb_inversion() {
        let f = Fixture::new(3, 1, |x, _| x as f64);
        let path = write_negative_preview(&f.planes, &profile(), 3, 1, &f.dir, &mut || false)
            .unwrap()
            .unwrap();
        let (w, h, rgb) = pixels(&fs::read(path).unwrap());
        assert_eq!((w, h), (3, 1));
        for (i, expected) in [255i16, 89, 25].iter().enumerate() {
            for c in 0..3 {
                assert!((rgb[i * 3 + c] as i16 - expected).abs() <= 1, "{rgb:?}");
            }
        }
        // A numeric inversion would give zero for density 1, not ~89.
        assert!(rgb[3] > 80);
    }
    #[test]
    fn sampled_density_matches_existing_light_table_and_validates_coverage() {
        let f = Fixture::new(3, 2, |x, y| x as f64 * 0.25 + y as f64 * 0.5);
        let reference = write_negative_preview(&f.planes, &profile(), 3, 2, &f.dir, &mut || false)
            .unwrap().unwrap();
        let mut samples = Vec::new();
        for y in 0..2 { for x in 0..3 { for sy in 0..2 { for sx in 0..2 {
            let ix = sample_coordinate(x, sx, 3, 3);
            let iy = sample_coordinate(y, sy, 2, 2);
            samples.push([ix as f32 * 0.25 + iy as f32 * 0.5; 3]);
        }}}}
        let actual = render_sampled_negative(&profile(), 3, 2, &samples, &mut || false)
            .unwrap().unwrap();
        assert_eq!(actual, fs::read(reference).unwrap());
        let one_per_pixel:Vec<_>=samples.chunks_exact(4).map(|v|v[0]).collect();
        assert_eq!(render_early_negative(&profile(),3,2,&one_per_pixel,&mut ||false).unwrap().unwrap(),actual);
        assert!(render_sampled_negative(&profile(), 3, 2, &samples[..samples.len()-1], &mut || false).is_err());
        samples[0][0] = f32::NAN;
        assert!(render_sampled_negative(&profile(), 3, 2, &samples, &mut || false).is_err());
        let mut reversal = profile(); reversal.info.film_type = "positive".into();
        assert!(render_sampled_negative(&reversal, 3, 2, &samples, &mut || false).unwrap().is_none());
        assert_eq!(render_sampled_negative(&profile(), 3, 2, &samples, &mut || true).unwrap_err().kind(), io::ErrorKind::Interrupted);
    }
    #[test]
    fn sharper_capture_uses_only_spare_memory_and_retains_low_budget_fallback() {
        let old=GpuDensityAssembly::new(1536,1024).unwrap();
        let old_bytes=old.peak_reservation(512,512).unwrap();
        let base=100*1024*1024;
        let tight=old.upgrade_for_plans(&[(512,512,base+old_bytes)],base+old_bytes).unwrap();
        assert_eq!(tight.edge(),GPU_BASE_EDGE);
        let upgraded=tight.upgrade_for_plans(&[(512,512,base+old_bytes)],512*1024*1024).unwrap();
        assert_eq!(upgraded.edge(),MAX_EDGE);
        assert!(upgraded.peak_reservation(512,512).unwrap()>old_bytes);
        assert_eq!(upgraded.values.len(),384*256*4);
        let multi=GpuDensityAssembly::new(1536,1024).unwrap().upgrade_for_plans(&[
            (512,512,base+old_bytes),(1536,1024,512*1024*1024),
        ],512*1024*1024).unwrap();
        assert_eq!(multi.edge(),GPU_BASE_EDGE,"Both split phases must fit unchanged");
    }
    #[test]
    fn larger_thumbnail_png_bound_and_tile_capture_are_complete() {
        assert_eq!(MAX_EDGE as usize*MAX_EDGE as usize*4,spektrafilm_gpu::MAX_FILM_DENSITY_SAMPLES);
        let mut assembly=GpuDensityAssembly::with_edge(1024,1024,MAX_EDGE).unwrap();
        for y in (0..1024).step_by(173) {for x in (0..1024).step_by(197) {
            let requests=assembly.samples_for_core(x,y,x,y,(x+197).min(1024),(y+173).min(1024));
            let values:Vec<_>=requests.iter().map(|r|(r.index,[0.2;3])).collect();
            assert!(assembly.add(&requests,&values));
        }}
        let (w,h,samples)=assembly.complete().unwrap();
        assert_eq!((w,h),(384,384));
        let png=render_sampled_negative(&profile(),w,h,&samples,&mut||false).unwrap().unwrap();
        assert_eq!(pixels(&png).0,w);
        let square=png_rgb(MAX_EDGE,MAX_EDGE,&vec![0;(MAX_EDGE*MAX_EDGE*3) as usize]);
        assert_eq!(square.len(),442_863);
        assert_eq!(square.len(),square.capacity(),"Encoder allocations are exact and bounded");
    }
    #[test]
    fn sampled_tile_cores_cover_each_point_once_without_halo_duplicates() {
        let mut assembly = GpuDensityAssembly::new(17, 23).unwrap();
        let mut count=0;
        for y in (0u32..23).step_by(7) { for x in (0u32..17).step_by(6) {
            let right=(x+6).min(17); let bottom=(y+7).min(23);
            let left=x.saturating_sub(3); let top=y.saturating_sub(3);
            let requests=assembly.samples_for_core(left,top,x,y,right,bottom);
            let values:Vec<_>=requests.iter().map(|r| {
                assert!(r.x+left>=x && r.x+left<right && r.y+top>=y && r.y+top<bottom);
                (r.index,[(r.x+left) as f32,(r.y+top) as f32,0.25])
            }).collect();
            count+=requests.len();
            assert!(assembly.add(&requests,&values));
        }}
        assert_eq!(count,17*23*4);
        let (_,_,values)=assembly.complete().unwrap();
        for ty in 0..23 { for tx in 0..17 { for sy in 0..2 { for sx in 0..2 {
            let index=((ty*17+tx)*4+sy*2+sx) as usize;
            assert_eq!(values[index], [sample_coordinate(tx,sx,17,17) as f32,sample_coordinate(ty,sy,23,23) as f32,0.25]);
        }}}}
    }
    #[test]
    fn cancelled_high_detail_publication_preserves_ready_image_and_attempt() {
        let f=Fixture::new(1,1,|_,_|0.);
        let id="12345678-1234-1234-1234-123456789abc";
        fs::write(f.dir.join("scan-preview-attempt"),id).unwrap();
        let target=f.dir.join(format!("negative-preview-{id}.png"));
        fs::write(&target,b"old-valid-publication").unwrap();
        write_attempt_status(&f.dir,id,"ready").unwrap();
        let samples=vec![[0.25;3];MAX_EDGE as usize*MAX_EDGE as usize*4];
        let mut calls=0;
        let error=publish_for_attempt(&f.dir,id,&profile(),MAX_EDGE,MAX_EDGE,&samples,false,&mut||{
            calls+=1;calls>=5
        }).unwrap_err();
        assert_eq!(error.kind(),io::ErrorKind::Interrupted);
        assert_eq!(fs::read(&target).unwrap(),b"old-valid-publication");
        assert_eq!(fs::read_to_string(f.dir.join(format!("negative-preview-{id}.status"))).unwrap(),"ready");
        assert!(!f.dir.join(format!("negative-preview-{id}.png.part")).exists());
        fs::write(f.dir.join("scan-preview-attempt"),"87654321-1234-1234-1234-123456789abc").unwrap();
        assert!(publish_for_attempt(&f.dir,id,&profile(),MAX_EDGE,MAX_EDGE,&samples,false,&mut||false).is_err());
        assert_eq!(fs::read(&target).unwrap(),b"old-valid-publication");
    }
    #[test]
    fn async_preview_is_attempt_scoped_and_real_density() {
        let f=Fixture::new(1,1,|_,_|1.0);
        let id="12345678-1234-1234-1234-123456789abc";
        fs::write(f.dir.join("scan-preview-attempt"),id).unwrap();
        publish_sampled_negative_async(&f.dir,profile(),1,1,vec![[1.;3];4]).unwrap();
        let status=f.dir.join(format!("negative-preview-{id}.status"));
        let deadline=std::time::Instant::now()+std::time::Duration::from_secs(10);
        while fs::read_to_string(&status).ok().as_deref()!=Some("ready") && std::time::Instant::now()<deadline {
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        assert_eq!(fs::read_to_string(status).unwrap(),"ready");
        let image=fs::read(f.dir.join(format!("negative-preview-{id}.png"))).unwrap();
        let (_,_,rgb)=pixels(&image);
        assert!(rgb.iter().all(|v| (88..=90).contains(v)));
        fs::write(f.dir.join("scan-preview-attempt"),"87654321-1234-1234-1234-123456789abc").unwrap();
        assert!(write_attempt_status(&f.dir,id,"ready").is_err());
    }
    #[test]
    fn bounded_thumbnail_averages_light_not_log_density() {
        let f = Fixture::new(MAX_EDGE * 2, 2, |x, _| if x % 2 == 0 { 0. } else { 2. });
        let path = write_negative_preview(&f.planes, &profile(), MAX_EDGE * 2, 2, &f.dir, &mut || false)
            .unwrap()
            .unwrap();
        let (w, h, rgb) = pixels(&fs::read(path).unwrap());
        assert_eq!((w, h), (MAX_EDGE, 1));
        // mean(10^0,10^-2)=0.505; sRGB ~188. mean density=1 would be89.
        assert!(rgb.iter().all(|v| (185..=190).contains(v)));
    }
    #[test]
    fn no_upscaling_and_reversal_is_not_fabricated() {
        let f = Fixture::new(1, 2, |_, _| 0.2);
        let mut p = profile();
        p.info.film_type = "positive".into();
        assert!(
            write_negative_preview(&f.planes, &p, 1, 2, &f.dir, &mut || false)
                .unwrap()
                .is_none()
        );
        assert!(!f.dir.join("negative-preview.png").exists());
        p.info.film_type = "negative".into();
        let path = write_negative_preview(&f.planes, &p, 1, 2, &f.dir, &mut || false)
            .unwrap()
            .unwrap();
        let (w, h, _) = pixels(&fs::read(path).unwrap());
        assert_eq!((w, h), (1, 2));
    }
    #[test]
    fn cancel_after_write_cleans_temp_and_preserves_existing_publication() {
        let f = Fixture::new(2, 2, |_, _| 0.3);
        let published = f.dir.join("negative-preview.png");
        fs::write(&published, b"old-valid-publication").unwrap();
        let mut calls = 0;
        let error = write_negative_preview(&f.planes, &profile(), 2, 2, &f.dir, &mut || {
            calls += 1;
            calls == 5
        })
        .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Interrupted);
        assert_eq!(fs::read(published).unwrap(), b"old-valid-publication");
        assert!(!f.dir.join("negative-preview.png.tmp").exists());
    }
    #[test]
    fn invalid_density_and_dimensions_never_publish() {
        let f = Fixture::new(2, 2, |_, _| f64::NAN);
        assert!(
            write_negative_preview(&f.planes, &profile(), 2, 2, &f.dir, &mut || false).is_err()
        );
        assert!(
            write_negative_preview(&f.planes, &profile(), 0, 2, &f.dir, &mut || false).is_err()
        );
        assert!(!f.dir.join("negative-preview.png").exists());
    }
    #[test]
    fn film_base_and_dye_spectra_change_actual_image() {
        let mut p = profile();
        let clear = LightTable::new(&p).unwrap().xyz([0.; 3]).unwrap();
        p.data.base_density = vec![1.; spectral::N_WAVELENGTHS];
        let fogged = LightTable::new(&p).unwrap().xyz([0.; 3]).unwrap();
        assert!((fogged[1] / clear[1] - 0.1).abs() < 1e-12);
        p.data.base_density.fill(0.);
        p.data
            .channel_density
            .iter_mut()
            .for_each(|v| *v = vec![1., 0., 0.]);
        let light = LightTable::new(&p).unwrap();
        assert!(
            (light.xyz([1., 0., 0.]).unwrap()[1] / light.xyz([0., 1., 0.]).unwrap()[1] - 0.1).abs()
                < 1e-12
        );
    }
    #[test]
    fn actual_portra_transmission_matches_existing_physical_scanner() {
        use spektrafilm_math::{image::ImageBuf, precision::from_f64};
        let p = spektrafilm_core::profile::load_profile_by_name(
            &PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data"),
            "kodak_portra_400",
        )
        .unwrap();
        let density = [0.25, 0.5, 0.75];
        let model = LightTable::new(&p).unwrap();
        let xyz = model.xyz(density).unwrap();
        let image = ImageBuf::from_data(1, 1, density.map(from_f64).to_vec());
        let dyes: Vec<[f64; 3]> = p
            .data
            .channel_density
            .iter()
            .map(|v| [v[0], v[1], v[2]])
            .collect();
        let norm: f64 = (0..spectral::N_WAVELENGTHS)
            .map(|i| spectral::ILLUMINANT_D65_F64[i] * spectral::CMF_Y_F64[i])
            .sum();
        let identity = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
        let reference = spektrafilm_gpu::cpu_backend::scan_spectral_cpu(
            &image,
            &dyes,
            &p.data.base_density,
            &spectral::ILLUMINANT_D65_F64,
            norm,
            &identity,
            &identity,
        );
        for c in 0..3 {
            assert!((xyz[c] - reference.data[c] as f64).abs() < 1e-6);
        }
    }
    #[test]
    fn actual_doublex_runtime_families_write_png_and_match_physical_scanner() {
        use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::load_profile_by_name};
        use spektrafilm_math::{image::ImageBuf, precision::from_f64};
        let data = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let raw = load_profile_by_name(&data, "kodak_doublex").unwrap();
        let print = load_profile_by_name(&data, "kodak_2302").unwrap();
        assert_eq!(raw.data.development_time, vec![4., 5., 6.5, 9., 12.]);
        let norm: f64 = (0..spectral::N_WAVELENGTHS)
            .map(|i| spectral::ILLUMINANT_D65_F64[i] * spectral::CMF_Y_F64[i]).sum();
        let identity = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
        for (index, &time) in raw.data.development_time.iter().enumerate() {
            let mut params = RuntimeParams::default();
            params.film_render.development_time = Some(time);
            let pipe = Pipeline::new(raw.clone(), print.clone(), params);
            let p = &pipe.film;
            assert_eq!(p.data.development_time, vec![time]);
            for i in 0..spectral::N_WAVELENGTHS {
                assert_eq!(p.data.channel_density[i], vec![raw.data.channel_density[i][0], 0., 0.]);
                assert_eq!(p.data.base_density[i], raw.data.base_density_rows[i][index]);
            }
            let model = LightTable::new(p).unwrap();
            let density = [0.5; 3];
            let xyz = model.xyz(density).unwrap();
            let image = ImageBuf::from_data(1, 1, density.map(from_f64).to_vec());
            let dyes: Vec<[f64; 3]> = p.data.channel_density.iter().map(|v| [v[0], v[1], v[2]]).collect();
            let reference = spektrafilm_gpu::cpu_backend::scan_spectral_cpu(
                &image, &dyes, &p.data.base_density, &spectral::ILLUMINANT_D65_F64,
                norm, &identity, &identity,
            );
            for c in 0..3 { assert!((xyz[c] - reference.data[c] as f64).abs() < 1e-6, "time={time} channel={c}"); }
            // Real publication and decoder exercise, not merely a finite matrix.
            let f = Fixture::new(3, 1, |x, _| x as f64 * 0.5);
            let path = write_negative_preview(&f.planes, p, 3, 1, &f.dir, &mut || false).unwrap().unwrap();
            let (w, h, rgb) = pixels(&fs::read(path).unwrap());
            assert_eq!((w, h), (3, 1));
            for x in 0..3 {
                assert_eq!(&rgb[x * 3..x * 3 + 3], &encode_srgb(model.xyz([x as f64 * 0.5; 3]).unwrap()).unwrap());
            }
            assert!(rgb[..3].iter().any(|v| *v > 0));
            for c in 0..3 { assert!(rgb[c] > rgb[3 + c] && rgb[3 + c] > rgb[6 + c]); }
        }
    }
    #[test]
    fn all_shipped_negative_profiles_have_usable_spectra() {
        let data = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data");
        for name in crate::profile_catalog::FILMS {
            let raw = spektrafilm_core::profile::load_profile_by_name(&data, name).unwrap();
            let p = spektrafilm_core::profile::resolve_for_render(raw, None);
            if p.info.film_type == "negative" {
                let model = LightTable::new(&p).unwrap_or_else(|e| panic!("{name}: {e}"));
                let rgb = encode_srgb(model.xyz([0.5; 3]).unwrap()).unwrap();
                assert!(rgb.iter().any(|v| *v > 0), "{name}");
            }
        }
    }
    #[test]
    fn published_png_fixture_for_external_decoder() {
        // Synthetic nonprivate chart only. Optional env output supports independent
        // decoder QA without retaining files during ordinary test runs.
        if let Ok(dir) = std::env::var("FILM_NEGATIVE_QA_DIR") {
            let f = Fixture::new(512, 256, |x, y| x as f64 / 200. + y as f64 / 500.);
            fs::create_dir_all(&dir).unwrap();
            write_negative_preview(
                &f.planes,
                &profile(),
                512,
                256,
                Path::new(&dir),
                &mut || false,
            )
            .unwrap();
        }
    }
}
