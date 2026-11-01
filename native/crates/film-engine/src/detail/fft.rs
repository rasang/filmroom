//! Exact finite-PSF valid-output overlap-save with bounded persistent cache.
//! Adapted from Filmroom fft_conv/disk_diffusion (GPL-3.0).
use super::{CancellationToken, DetailError, Rect};
use rayon::prelude::*;
use rustfft::{num_complex::Complex, FftPlanner};
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use spektrafilm_model::diffusion::{ExactDiffusionKernel, ExactDiffusionKernelDescriptor};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};

fn invalid(s: &str) -> DetailError {
    DetailError::InvalidGeometry(s.into())
}
fn mul(a: usize, b: usize) -> Result<usize, DetailError> {
    a.checked_mul(b).ok_or_else(|| invalid("size overflow"))
}
fn add(a: usize, b: usize) -> Result<usize, DetailError> {
    a.checked_add(b).ok_or_else(|| invalid("size overflow"))
}
fn zeros<T: Default + Clone>(n: usize) -> Result<Vec<T>, DetailError> {
    let mut v = Vec::new();
    v.try_reserve_exact(n)
        .map_err(|e| DetailError::Processing(format!("allocation failed: {e}")))?;
    v.resize(n, T::default());
    Ok(v)
}
fn reflect(x: isize, n: usize) -> usize {
    if n <= 1 {
        return 0;
    }
    let p = 2 * (n as isize - 1);
    let a = x.rem_euclid(p);
    if a < n as isize {
        a as usize
    } else {
        (p - a) as usize
    }
}
fn fast(mut n: usize) -> Result<usize, DetailError> {
    loop {
        let mut m = n;
        for p in [2, 3, 5, 7, 11] {
            while m > 0 && m % p == 0 {
                m /= p;
            }
        }
        if m == 1 {
            return Ok(n);
        }
        n = add(n, 1)?;
    }
}
fn transform(
    a: &mut [Complex<f64>],
    w: usize,
    h: usize,
    inverse: bool,
    cancel: &CancellationToken,
) -> Result<(), DetailError> {
    cancel.check()?;
    let mut planner = FftPlanner::new();
    let x = if inverse {
        planner.plan_fft_inverse(w)
    } else {
        planner.plan_fft_forward(w)
    };
    a.par_chunks_mut(w).try_for_each_init(
        || vec![Complex::default(); x.get_inplace_scratch_len()],
        |scratch, row| {
            cancel.check()?;
            x.process_with_scratch(row, scratch);
            cancel.check()
        },
    )?;
    cancel.check()?;
    let mut trans = zeros::<Complex<f64>>(mul(w, h)?)?;
    trans
        .par_chunks_mut(h)
        .enumerate()
        .try_for_each(|(x, row)| {
            cancel.check()?;
            for y in 0..h {
                row[y] = a[y * w + x];
            }
            Ok::<_, DetailError>(())
        })?;
    cancel.check()?;
    let y = if inverse {
        planner.plan_fft_inverse(h)
    } else {
        planner.plan_fft_forward(h)
    };
    trans.par_chunks_mut(h).try_for_each_init(
        || vec![Complex::default(); y.get_inplace_scratch_len()],
        |scratch, row| {
            cancel.check()?;
            y.process_with_scratch(row, scratch);
            cancel.check()
        },
    )?;
    a.par_chunks_mut(w).enumerate().try_for_each(|(y, row)| {
        cancel.check()?;
        for x in 0..w {
            row[x] = trans[x * h + y];
        }
        Ok::<_, DetailError>(())
    })?;
    cancel.check()
}
type Key = (usize, usize, usize);
const META_ALLOWANCE: usize = 64 * 1024;
const MAX_ENTRIES: usize = 128;
const IO_BYTES: usize = 64 * 1024;
#[derive(Clone, Copy, Debug, Default, serde::Serialize)]
pub struct CacheStats {
    pub kernel_builds: u64,
    pub ram_hits: u64,
    pub disk_hits: u64,
    pub ram_evictions: u64,
    pub disk_evictions: u64,
    pub ram_bytes: usize,
    pub disk_bytes: u64,
    pub peak_ram_bytes: usize,
    pub peak_disk_bytes: u64,
    pub peak_fft_work_reserved_bytes: usize,
}
struct RamEntry {
    values: Vec<Complex<f64>>,
    touch: u64,
}
struct DiskEntry {
    name: String,
    bytes: u64,
    touch: u64,
}
struct DiskCache {
    root: PathBuf,
    budget: u64,
    bytes: u64,
    entries: BTreeMap<Key, DiskEntry>,
    seq: u64,
}
impl Drop for DiskCache {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}
struct TempFile(PathBuf);
impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}
/// Conservative transform+IO reservation; no allocation or kernel normalization.
pub(crate) fn work_reservation(w: usize, h: usize, radius: usize) -> Result<usize, DetailError> {
    let fw = fast(add(w, mul(radius, 2)?)?)?;
    let fh = fast(add(h, mul(radius, 2)?)?)?;
    add(mul(mul(fw, fh)?, 64)?, 256 * 1024)
}
pub struct ValidFft {
    spectra: BTreeMap<Key, RamEntry>,
    identity: Option<ExactDiffusionKernelDescriptor>,
    scope: Box<[u8]>,
    disk: Option<DiskCache>,
    budget: usize,
    work_budget: usize,
    clock: u64,
    counters: CacheStats,
    pub kernel_bytes: usize,
}
impl ValidFft {
    pub fn new(cache_bytes: usize) -> Self {
        Self {
            spectra: BTreeMap::new(),
            identity: None,
            scope: Box::new([]),
            disk: None,
            budget: cache_bytes,
            work_budget: usize::MAX,
            clock: 0,
            counters: CacheStats::default(),
            kernel_bytes: 0,
        }
    }
    /// `cache_dir` must be new and inside the caller's preview-private root.
    /// Namespace includes the caller's complete immutable data/parameter identity.
    pub fn with_disk_cache(
        cache_bytes: usize,
        cache_dir: &Path,
        disk_bytes: u64,
        context_identity: Vec<u8>,
        fft_work_budget_bytes: usize,
    ) -> Result<Self, DetailError> {
        if context_identity.len() > 32768 {
            return Err(invalid("cache identity too large"));
        }
        let scope = context_identity.into_boxed_slice();
        let base = META_ALLOWANCE + scope.len() + cache_dir.as_os_str().len();
        if base > cache_bytes {
            return Err(DetailError::BudgetExceeded {
                required: base,
                budget: cache_bytes,
            });
        }
        fs::create_dir(cache_dir)?;
        let disk = DiskCache {
            root: cache_dir.into(),
            budget: disk_bytes,
            bytes: 0,
            entries: BTreeMap::new(),
            seq: 0,
        };
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(cache_dir, fs::Permissions::from_mode(0o700))?;
        }
        let mut result = Self::new(cache_bytes);
        result.scope = scope;
        result.disk = Some(disk);
        result.work_budget = fft_work_budget_bytes;
        result.refresh();
        Ok(result)
    }
    fn base_bytes(&self) -> usize {
        let metadata = if self.identity.is_some() || self.disk.is_some() {
            META_ALLOWANCE
        } else {
            0
        };
        metadata
            + self.scope.len()
            + self.disk.as_ref().map_or(0, |d| d.root.as_os_str().len())
            + self.identity.as_ref().map_or(0, |i| i.storage_bytes())
    }
    fn refresh(&mut self) {
        self.kernel_bytes = self.base_bytes()
            + self
                .spectra
                .values()
                .map(|e| e.values.capacity() * 16)
                .sum::<usize>();
        self.counters.peak_ram_bytes = self.counters.peak_ram_bytes.max(self.kernel_bytes);
        self.counters.peak_disk_bytes = self
            .counters
            .peak_disk_bytes
            .max(self.disk.as_ref().map_or(0, |d| d.bytes));
    }
    pub fn stats(&self) -> CacheStats {
        CacheStats {
            ram_bytes: self.kernel_bytes,
            disk_bytes: self.disk.as_ref().map_or(0, |d| d.bytes),
            ..self.counters
        }
    }
    pub fn clear(&mut self) -> Result<(), DetailError> {
        self.clear_cancellable(None)
    }
    fn clear_cancellable(&mut self, cancel: Option<&CancellationToken>) -> Result<(), DetailError> {
        self.spectra.clear();
        // Keep accounting valid even if a later file deletion is cancelled/fails.
        self.refresh();
        if let Some(d) = &mut self.disk {
            let keys: Vec<_> = d.entries.keys().copied().collect();
            for key in keys {
                if let Some(c) = cancel {
                    c.check()?;
                }
                let e = &d.entries[&key];
                fs::remove_file(d.root.join(&e.name))?;
                d.bytes -= e.bytes;
                d.entries.remove(&key);
            }
        }
        self.identity = None;
        self.refresh();
        Ok(())
    }
    fn touch(&mut self) -> u64 {
        self.clock = self.clock.wrapping_add(1);
        self.clock
    }
    fn evict_ram(&mut self) -> bool {
        if let Some(key) = self
            .spectra
            .iter()
            .min_by_key(|(_, e)| e.touch)
            .map(|(k, _)| *k)
        {
            self.spectra.remove(&key);
            self.counters.ram_evictions += 1;
            self.refresh();
            true
        } else {
            false
        }
    }
    fn room_ram(&mut self, bytes: usize) -> Result<(), DetailError> {
        while add(self.kernel_bytes, bytes)? > self.budget || self.spectra.len() >= MAX_ENTRIES {
            if !self.evict_ram() {
                return Err(DetailError::BudgetExceeded {
                    required: add(self.kernel_bytes, bytes)?,
                    budget: self.budget,
                });
            }
        }
        self.counters.peak_ram_bytes = self
            .counters
            .peak_ram_bytes
            .max(add(self.kernel_bytes, bytes)?);
        Ok(())
    }
    fn room_disk(&mut self, bytes: u64, cancel: &CancellationToken) -> Result<bool, DetailError> {
        let Some(d) = &mut self.disk else {
            return Ok(false);
        };
        if bytes > d.budget {
            return Ok(false);
        }
        while d
            .bytes
            .checked_add(bytes)
            .ok_or_else(|| invalid("disk budget overflow"))?
            > d.budget
            || d.entries.len() >= MAX_ENTRIES
        {
            cancel.check()?;
            let key = d
                .entries
                .iter()
                .min_by_key(|(_, e)| e.touch)
                .map(|(k, _)| *k)
                .ok_or_else(|| invalid("disk budget state"))?;
            let e = &d.entries[&key];
            fs::remove_file(d.root.join(&e.name))?;
            d.bytes -= e.bytes;
            d.entries.remove(&key);
            self.counters.disk_evictions += 1;
        }
        self.counters.peak_disk_bytes = self.counters.peak_disk_bytes.max(d.bytes + bytes);
        Ok(true)
    }
    fn prepare_identity(
        &mut self,
        k: &ExactDiffusionKernel,
        full: (usize, usize),
        cancel: &CancellationToken,
    ) -> Result<(), DetailError> {
        cancel.check()?;
        let descriptor = k.descriptor();
        if descriptor.geometry() != [full.0, full.1] {
            return Err(invalid("kernel geometry mismatch"));
        }
        if self.identity.as_ref() == Some(&descriptor) {
            return Ok(());
        }
        let base = META_ALLOWANCE
            + self.scope.len()
            + self.disk.as_ref().map_or(0, |d| d.root.as_os_str().len())
            + descriptor.storage_bytes();
        if base > self.budget {
            return Err(DetailError::BudgetExceeded {
                required: base,
                budget: self.budget,
            });
        }
        self.clear_cancellable(Some(cancel))?;
        self.identity = Some(descriptor);
        self.refresh();
        cancel.check()
    }
    fn disk_file(&self, key: Key) -> Result<File, DetailError> {
        let d = self
            .disk
            .as_ref()
            .ok_or_else(|| invalid("missing disk cache"))?;
        let e = d
            .entries
            .get(&key)
            .ok_or_else(|| invalid("missing disk spectrum"))?;
        let path = d.root.join(&e.name);
        let m = fs::symlink_metadata(&path)?;
        if !m.is_file() || m.len() != e.bytes {
            return Err(invalid("invalid cached spectrum file"));
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if m.nlink() != 1 {
                return Err(invalid("linked cache file"));
            }
        }
        Ok(File::open(path)?)
    }
    fn load_disk(
        &self,
        key: Key,
        n: usize,
        cancel: &CancellationToken,
    ) -> Result<Vec<Complex<f64>>, DetailError> {
        let mut file = self.disk_file(key)?;
        let mut out = zeros::<Complex<f64>>(n)?;
        let mut bytes = vec![0u8; IO_BYTES];
        for chunk in out.chunks_mut(IO_BYTES / 16) {
            cancel.check()?;
            file.read_exact(&mut bytes[..chunk.len() * 16])?;
            for (v, b) in chunk.iter_mut().zip(bytes.chunks_exact(16)) {
                let re =
                    f64::from_le_bytes(b[..8].try_into().map_err(|_| invalid("cache real bytes"))?);
                let im = f64::from_le_bytes(
                    b[8..16]
                        .try_into()
                        .map_err(|_| invalid("cache imaginary bytes"))?,
                );
                if !re.is_finite() || !im.is_finite() {
                    return Err(invalid("nonfinite cached spectrum"));
                }
                *v = Complex::new(re, im);
            }
        }
        cancel.check()?;
        Ok(out)
    }
    fn save_disk(
        &mut self,
        key: Key,
        values: &[Complex<f64>],
        cancel: &CancellationToken,
    ) -> Result<(), DetailError> {
        let bytes_len = mul(values.len(), 16)? as u64;
        if !self.room_disk(bytes_len, cancel)? {
            return Ok(());
        }
        let touch = self.touch();
        let d = self
            .disk
            .as_mut()
            .ok_or_else(|| invalid("missing disk cache"))?;
        d.seq = d
            .seq
            .checked_add(1)
            .ok_or_else(|| invalid("cache sequence exhausted"))?;
        let name = format!("s-{}-{}-{}-{}.fft", key.0, key.1, key.2, d.seq);
        let tmp = TempFile(d.root.join(format!("{name}.part")));
        let final_path = d.root.join(&name);
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&tmp.0)?;
        let mut bytes = vec![0u8; IO_BYTES];
        for chunk in values.chunks(IO_BYTES / 16) {
            cancel.check()?;
            for (v, b) in chunk.iter().zip(bytes.chunks_exact_mut(16)) {
                if !v.re.is_finite() || !v.im.is_finite() {
                    return Err(invalid("nonfinite spectrum"));
                }
                b[..8].copy_from_slice(&v.re.to_le_bytes());
                b[8..16].copy_from_slice(&v.im.to_le_bytes());
            }
            file.write_all(&bytes[..chunk.len() * 16])?;
        }
        file.flush()?;
        drop(file);
        cancel.check()?;
        fs::rename(&tmp.0, &final_path)?;
        d.entries.insert(
            key,
            DiskEntry {
                name,
                bytes: bytes_len,
                touch,
            },
        );
        d.bytes += bytes_len;
        self.refresh();
        Ok(())
    }
    fn ensure_spectrum(
        &mut self,
        key: Key,
        k: &ExactDiffusionKernel,
        cancel: &CancellationToken,
    ) -> Result<(), DetailError> {
        let touch = self.touch();
        if let Some(entry) = self.spectra.get_mut(&key) {
            entry.touch = touch;
            if let Some(d) = &mut self.disk {
                if let Some(e) = d.entries.get_mut(&key) {
                    e.touch = touch;
                }
            }
            self.counters.ram_hits += 1;
            return Ok(());
        }
        let n = mul(key.0, key.1)?;
        let bytes = mul(n, 16)?;
        let fits_ram = add(self.base_bytes(), bytes)? <= self.budget;
        if self
            .disk
            .as_ref()
            .map_or(false, |d| d.entries.contains_key(&key))
        {
            if let Some(d) = &mut self.disk {
                if let Some(e) = d.entries.get_mut(&key) {
                    e.touch = touch;
                }
            }
            self.counters.disk_hits += 1;
            if fits_ram {
                self.room_ram(bytes)?;
                let values = self.load_disk(key, n, cancel)?;
                self.spectra.insert(key, RamEntry { values, touch });
                self.refresh();
            }
            return Ok(());
        }
        if fits_ram {
            self.room_ram(bytes)?;
        } else {
            let disk_budget = self.disk.as_ref().map_or(0, |d| d.budget);
            if bytes as u64 > disk_budget {
                return Err(DetailError::BudgetExceeded {
                    required: add(self.base_bytes(), bytes)?,
                    budget: self.budget,
                });
            }
            // Oversized spectrum is bounded FFT scratch, never a cache entry.
            while self.evict_ram() {}
        }
        let mut spectrum = zeros::<Complex<f64>>(n)?;
        for y in 0..k.side() {
            cancel.check()?;
            for (x, v) in k.row(key.2, y).into_iter().enumerate() {
                if !v.is_finite() {
                    return Err(invalid("nonfinite PSF"));
                }
                spectrum[y * key.0 + x].re = v;
            }
        }
        transform(&mut spectrum, key.0, key.1, false, cancel)?;
        cancel.check()?;
        self.counters.kernel_builds += 1;
        self.save_disk(key, &spectrum, cancel)?;
        if fits_ram {
            self.spectra.insert(
                key,
                RamEntry {
                    values: spectrum,
                    touch,
                },
            );
            self.refresh();
        }
        Ok(())
    }
    fn multiply(
        &self,
        key: Key,
        a: &mut [Complex<f64>],
        cancel: &CancellationToken,
    ) -> Result<(), DetailError> {
        if let Some(spectrum) = self.spectra.get(&key) {
            a.par_chunks_mut(4096)
                .zip(spectrum.values.par_chunks(4096))
                .try_for_each(|(aa, bb)| {
                    cancel.check()?;
                    for (v, z) in aa.iter_mut().zip(bb) {
                        *v *= *z;
                    }
                    Ok::<_, DetailError>(())
                })
        } else {
            let mut file = self.disk_file(key)?;
            let mut bytes = vec![0u8; IO_BYTES];
            for chunk in a.chunks_mut(IO_BYTES / 16) {
                cancel.check()?;
                file.read_exact(&mut bytes[..chunk.len() * 16])?;
                for (v, b) in chunk.iter_mut().zip(bytes.chunks_exact(16)) {
                    let re = f64::from_le_bytes(
                        b[..8].try_into().map_err(|_| invalid("cache real bytes"))?,
                    );
                    let im = f64::from_le_bytes(
                        b[8..16]
                            .try_into()
                            .map_err(|_| invalid("cache imaginary bytes"))?,
                    );
                    if !re.is_finite() || !im.is_finite() {
                        return Err(invalid("nonfinite cached spectrum"));
                    }
                    *v *= Complex::new(re, im);
                }
            }
            cancel.check()
        }
    }
    pub fn apply(
        &mut self,
        raw: &ImageBuf,
        src: Rect,
        dst: Rect,
        full: (usize, usize),
        k: &ExactDiffusionKernel,
        cancel: &CancellationToken,
    ) -> Result<ImageBuf, DetailError> {
        cancel.check()?;
        if full.0 == 0
            || full.1 == 0
            || full.0 > 40000
            || full.1 > 40000
            || src.w == 0
            || src.h == 0
            || dst.w == 0
            || dst.h == 0
        {
            return Err(invalid("empty or excessive image geometry"));
        }
        if dst.w > 512 || dst.h > 512 {
            return Err(DetailError::Unsupported(
                "valid FFT output block exceeds 512 pixels".into(),
            ));
        }
        for r in [src, dst] {
            if add(r.x, r.w)? > full.0 || add(r.y, r.h)? > full.1 {
                return Err(invalid("rectangle outside full image"));
            }
        }
        if dst.x < src.x
            || dst.y < src.y
            || add(dst.x, dst.w)? > add(src.x, src.w)?
            || add(dst.y, dst.h)? > add(src.y, src.h)?
        {
            return Err(invalid("destination outside source"));
        }
        if raw.width as usize != src.w
            || raw.height as usize != src.h
            || raw.data.len() != mul(mul(src.w, src.h)?, 3)?
        {
            return Err(invalid("source buffer shape mismatch"));
        }
        if k.radius > full.0.max(full.1) || !k.scatter.is_finite() {
            return Err(invalid("invalid kernel geometry"));
        }
        self.prepare_identity(k, full, cancel)?;
        let rw = add(dst.w, mul(2, k.radius)?)?;
        let rh = add(dst.h, mul(2, k.radius)?)?;
        let (fw, fh) = (fast(rw)?, fast(rh)?);
        let n = mul(fw, fh)?;
        let work = work_reservation(dst.w, dst.h, k.radius)?;
        if work > self.work_budget {
            return Err(DetailError::BudgetExceeded {
                required: work,
                budget: self.work_budget,
            });
        }
        self.counters.peak_fft_work_reserved_bytes =
            self.counters.peak_fft_work_reserved_bytes.max(work);
        let data = zeros(mul(mul(dst.w, dst.h)?, 3)?)?;
        let mut out = ImageBuf::from_data(dst.w as u32, dst.h as u32, data);
        for c in 0..3 {
            cancel.check()?;
            let key = (fw, fh, c);
            self.ensure_spectrum(key, k, cancel)?;
            cancel.check()?;
            let mut a = zeros::<Complex<f64>>(n)?;
            for y in 0..rh {
                cancel.check()?;
                let sy = reflect(dst.y as isize + y as isize - k.radius as isize, full.1);
                for x in 0..rw {
                    let sx = reflect(dst.x as isize + x as isize - k.radius as isize, full.0);
                    if sx < src.x || sx >= src.x + src.w || sy < src.y || sy >= src.y + src.h {
                        return Err(invalid("source dependency missing"));
                    }
                    let value = raw.data[((sy - src.y) * src.w + sx - src.x) * 3 + c] as f64;
                    if !value.is_finite() {
                        return Err(DetailError::Processing("nonfinite source".into()));
                    }
                    a[y * fw + x].re = value;
                }
            }
            transform(&mut a, fw, fh, false, cancel)?;
            self.multiply(key, &mut a, cancel)?;
            transform(&mut a, fw, fh, true, cancel)?;
            for y in 0..dst.h {
                cancel.check()?;
                for x in 0..dst.w {
                    let original =
                        raw.data[((dst.y + y - src.y) * src.w + dst.x + x - src.x) * 3 + c] as f64;
                    let value = (1. - k.scatter) * original
                        + k.scatter * a[(y + 2 * k.radius) * fw + x + 2 * k.radius].re / n as f64;
                    if !value.is_finite() {
                        return Err(DetailError::Processing("nonfinite diffusion output".into()));
                    }
                    out.data[(y * dst.w + x) * 3 + c] = from_f64(value);
                }
            }
        }
        cancel.check()?;
        Ok(out)
    }
}

#[cfg(all(test, feature = "precision-f64"))]
mod tests {
    use super::*;
    use spektrafilm_model::diffusion::{apply_diffusion_filter_um, DiffusionFilter};
    fn config(warmth: f64) -> DiffusionFilter<'static> {
        DiffusionFilter {
            family: "black_pro_mist",
            strength: 0.5,
            spatial_scale: 1.,
            halo_warmth: warmth,
            core_intensity: 1.,
            core_size: 1.,
            halo_intensity: 1.,
            halo_size: 1.,
            bloom_intensity: 1.,
            bloom_size: 1.,
        }
    }
    fn crop(raw: &ImageBuf, q: Rect) -> ImageBuf {
        let mut v = vec![];
        for y in q.y..q.y + q.h {
            let i = (y * raw.width as usize + q.x) * 3;
            v.extend_from_slice(&raw.data[i..i + q.w * 3]);
        }
        ImageBuf::from_data(q.w as u32, q.h as u32, v)
    }
    fn fixture(w: usize, h: usize) -> ImageBuf {
        ImageBuf::from_data(
            w as u32,
            h as u32,
            (0..w * h * 3)
                .map(|i| {
                    from_f64(if i < 12 {
                        100.
                    } else {
                        ((i * 37) % 251) as f64 / 13.
                    })
                })
                .collect(),
        )
    }
    fn assert_close(a: &ImageBuf, b: &ImageBuf) {
        assert_eq!(a.data.len(), b.data.len());
        let err = a
            .data
            .iter()
            .zip(&b.data)
            .map(|(a, b)| (*a as f64 - *b as f64).abs())
            .fold(0., f64::max);
        assert!(err < 1e-9, "error={err}");
    }
    #[test]
    fn hdr_true_edges_non_square_and_singleton() {
        let token = CancellationToken::new();
        for (w, h) in [(47, 29), (1, 7), (7, 1), (2, 5), (13, 17)] {
            let raw = fixture(w, h);
            let df = config(0.);
            let k = ExactDiffusionKernel::new(&df, 10., w, h).unwrap();
            let reference = apply_diffusion_filter_um(&raw, &df, 10.);
            let mut cache = ValidFft::new(4 * 1024 * 1024);
            for q in [
                Rect {
                    x: 0,
                    y: 0,
                    w: w.min(7),
                    h: h.min(5),
                },
                Rect {
                    x: w / 2,
                    y: h / 2,
                    w: (w - w / 2).min(7),
                    h: (h - h / 2).min(5),
                },
                Rect {
                    x: w.saturating_sub(7),
                    y: h.saturating_sub(5),
                    w: w.min(7),
                    h: h.min(5),
                },
            ] {
                let s = q.expand(k.radius, w, h);
                let got = cache
                    .apply(&crop(&raw, s), s, q, (w, h), &k, &token)
                    .unwrap();
                assert_close(&got, &crop(&reference, q));
            }
            assert!(cache.kernel_bytes <= 4 * 1024 * 1024);
        }
    }
    #[test]
    fn cache_identity_changes_with_kernel_not_only_shape() {
        let (w, h) = (47, 29);
        let raw = fixture(w, h);
        let q = Rect { x: 0, y: 0, w, h };
        let token = CancellationToken::new();
        let mut cache = ValidFft::new(4 * 1024 * 1024);
        for warmth in [0., 1.2, 0.] {
            let df = config(warmth);
            let k = ExactDiffusionKernel::new(&df, 10., w, h).unwrap();
            let got = cache.apply(&raw, q, q, (w, h), &k, &token).unwrap();
            assert_close(&got, &apply_diffusion_filter_um(&raw, &df, 10.));
        }
        cache.clear().unwrap();
        assert_eq!(cache.kernel_bytes, 0);
        assert!(cache.identity.is_none());
    }
    #[test]
    fn precancel_and_budget_are_typed_errors() {
        let (w, h) = (47, 29);
        let raw = fixture(w, h);
        let q = Rect { x: 0, y: 0, w, h };
        let k = ExactDiffusionKernel::new(&config(0.), 10., w, h).unwrap();
        let token = CancellationToken::new();
        token.cancel();
        let mut cache = ValidFft::new(1 << 20);
        assert!(matches!(
            cache.apply(&raw, q, q, (w, h), &k, &token),
            Err(DetailError::Cancelled)
        ));
        assert_eq!(cache.kernel_bytes, 0);
        let token = CancellationToken::new();
        let mut cache = ValidFft::new(1);
        assert!(matches!(
            cache.apply(&raw, q, q, (w, h), &k, &token),
            Err(DetailError::BudgetExceeded { .. })
        ));
    }
    #[test]
    fn missing_dependency_is_error_not_panic() {
        let (w, h) = (47, 29);
        let full = fixture(w, h);
        let q = Rect {
            x: 15,
            y: 10,
            w: 7,
            h: 5,
        };
        let k = ExactDiffusionKernel::new(&config(0.), 10., w, h).unwrap();
        let mut cache = ValidFft::new(1 << 20);
        assert!(matches!(
            cache.apply(&crop(&full, q), q, q, (w, h), &k, &CancellationToken::new()),
            Err(DetailError::InvalidGeometry(_))
        ));
    }
    #[test]
    fn cancellation_during_large_transform_does_not_publish_partial_spectrum() {
        let (w, h) = (512, 512);
        let raw = fixture(w, h);
        let q = Rect { x: 0, y: 0, w, h };
        let k = ExactDiffusionKernel::new(&config(0.), 35., w, h).unwrap();
        let token = CancellationToken::new();
        let worker_token = token.clone();
        let mut cache = ValidFft::new(64 << 20);
        let thread = std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(2));
            worker_token.cancel();
        });
        let result = cache.apply(&raw, q, q, (w, h), &k, &token);
        thread.join().unwrap();
        assert!(matches!(result, Err(DetailError::Cancelled)));
        assert!(cache.kernel_bytes <= 64 << 20);
        // Any retained entry is complete; a noncancelled retry must remain correct.
        let small = Rect {
            x: 0,
            y: 0,
            w: 7,
            h: 5,
        };
        let got = cache
            .apply(&raw, q, small, (w, h), &k, &CancellationToken::new())
            .unwrap();
        let reference = apply_diffusion_filter_um(&raw, &config(0.), 35.);
        assert_close(&got, &crop(&reference, small));
    }
    fn private_cache(label: &str) -> PathBuf {
        static N: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        std::env::temp_dir().join(format!(
            "print-spectrum-{}-{}-{}",
            std::process::id(),
            label,
            N.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ))
    }
    #[test]
    fn byte_lru_disk_spill_warm_three_channels_and_oversized_spectra() {
        let (w, h) = (47, 29);
        let raw = fixture(w, h);
        let q = Rect { x: 0, y: 0, w, h };
        let k = ExactDiffusionKernel::new(&config(0.), 10., w, h).unwrap();
        let fw = fast(w + 2 * k.radius).unwrap();
        let fh = fast(h + 2 * k.radius).unwrap();
        let bytes = fw * fh * 16;
        for oversized in [false, true] {
            let path = private_cache(if oversized { "disk-only" } else { "one-ram" });
            let base =
                META_ALLOWANCE + path.as_os_str().len() + 16 + k.descriptor().storage_bytes();
            let budget = base + if oversized { 1024 } else { bytes + 16 };
            let mut cache = ValidFft::with_disk_cache(
                budget,
                &path,
                (bytes * 3) as u64,
                b"full-context-v1".to_vec(),
                work_reservation(w, h, k.radius).unwrap(),
            )
            .unwrap();
            let first = cache
                .apply(&raw, q, q, (w, h), &k, &CancellationToken::new())
                .unwrap();
            assert_eq!(cache.stats().kernel_builds, 3);
            assert_eq!(cache.stats().disk_bytes, (bytes * 3) as u64);
            let warm = cache
                .apply(&raw, q, q, (w, h), &k, &CancellationToken::new())
                .unwrap();
            assert_eq!(first.data, warm.data);
            assert_close(&warm, &apply_diffusion_filter_um(&raw, &config(0.), 10.));
            assert_eq!(cache.stats().kernel_builds, 3);
            assert_eq!(cache.stats().disk_hits, 3);
            assert!(cache.stats().peak_ram_bytes <= budget);
            assert!(cache.stats().peak_disk_bytes <= (bytes * 3) as u64);
            if oversized {
                assert!(cache.spectra.is_empty());
            } else {
                assert_eq!(cache.spectra.len(), 1);
                assert!(cache.stats().ram_evictions >= 5);
            }
            drop(cache);
            assert!(!path.exists());
        }
    }
    #[test]
    fn true_lru_disk_quota_and_kernel_change_do_not_reuse_wrong_spectrum() {
        let (w, h) = (47, 29);
        let raw = fixture(w, h);
        let q = Rect { x: 0, y: 0, w, h };
        let k = ExactDiffusionKernel::new(&config(0.), 10., w, h).unwrap();
        let fw = fast(w + 2 * k.radius).unwrap();
        let fh = fast(h + 2 * k.radius).unwrap();
        let bytes = fw * fh * 16;
        let path = private_cache("lru");
        let budget =
            META_ALLOWANCE + path.as_os_str().len() + k.descriptor().storage_bytes() + bytes + 1024;
        let mut cache =
            ValidFft::with_disk_cache(budget, &path, (bytes * 2) as u64, vec![], usize::MAX)
                .unwrap();
        let token = CancellationToken::new();
        cache.prepare_identity(&k, (w, h), &token).unwrap();
        for ch in [0, 1, 0, 2] {
            cache.ensure_spectrum((fw, fh, ch), &k, &token).unwrap();
        }
        let disk = cache.disk.as_ref().unwrap();
        assert!(disk.entries.contains_key(&(fw, fh, 0)));
        assert!(!disk.entries.contains_key(&(fw, fh, 1)));
        assert!(disk.entries.contains_key(&(fw, fh, 2)));
        assert!(cache.stats().disk_evictions > 0);
        assert!(cache.stats().peak_disk_bytes <= (bytes * 2) as u64);
        let changed = ExactDiffusionKernel::new(&config(1.2), 10., w, h).unwrap();
        let got = cache.apply(&raw, q, q, (w, h), &changed, &token).unwrap();
        assert_close(&got, &apply_diffusion_filter_um(&raw, &config(1.2), 10.));
        cache.clear().unwrap();
        assert_eq!(cache.stats().disk_bytes, 0);
        assert_eq!(fs::read_dir(&path).unwrap().count(), 0);
        drop(cache);
        assert!(!path.exists());
    }
    #[test]
    fn cancelled_partial_clear_can_retry_same_identity_without_stale_ram_charge() {
        let (w,h)=(47,29);let raw=fixture(w,h);let q=Rect{x:0,y:0,w,h};
        let k=ExactDiffusionKernel::new(&config(0.),10.,w,h).unwrap();
        let fw=fast(w+2*k.radius).unwrap();let fh=fast(h+2*k.radius).unwrap();let bytes=fw*fh*16;
        let path=private_cache("partial-clear");
        let budget=META_ALLOWANCE+path.as_os_str().len()+k.descriptor().storage_bytes()+bytes+1024;
        let mut cache=ValidFft::with_disk_cache(budget,&path,(bytes*3)as u64,vec![],usize::MAX).unwrap();
        let expected=cache.apply(&raw,q,q,(w,h),&k,&CancellationToken::new()).unwrap();
        assert_eq!(cache.spectra.len(),1);
        let cancelled=CancellationToken::new();cancelled.cancel_after_checks(2);
        assert!(matches!(cache.clear_cancellable(Some(&cancelled)),Err(DetailError::Cancelled)));
        assert!(cache.spectra.is_empty());assert_eq!(cache.kernel_bytes,cache.base_bytes());
        assert_eq!(cache.disk.as_ref().unwrap().entries.len(),2);
        assert_eq!(cache.stats().disk_bytes,(bytes*2)as u64);
        let actual=cache.apply(&raw,q,q,(w,h),&k,&CancellationToken::new()).unwrap();
        assert_eq!(actual.data,expected.data);assert!(cache.stats().peak_ram_bytes<=budget);
        drop(cache);assert!(!path.exists());
    }
    #[test]
    fn disk_io_cancellation_corruption_and_work_budget_are_explicit() {
        let path = private_cache("io");
        let mut cache =
            ValidFft::with_disk_cache(2 << 20, &path, 1 << 20, vec![], usize::MAX).unwrap();
        let key = (128, 64, 0);
        let values = vec![Complex::new(1., 0.); 128 * 64];
        let token = CancellationToken::new();
        token.cancel_after_checks(2);
        assert!(matches!(
            cache.save_disk(key, &values, &token),
            Err(DetailError::Cancelled)
        ));
        assert_eq!(cache.stats().disk_bytes, 0);
        assert_eq!(fs::read_dir(&path).unwrap().count(), 0);
        cache
            .save_disk(key, &values, &CancellationToken::new())
            .unwrap();
        let token = CancellationToken::new();
        token.cancel_after_checks(2);
        assert!(matches!(
            cache.load_disk(key, values.len(), &token),
            Err(DetailError::Cancelled)
        ));
        let filename = cache.disk.as_ref().unwrap().entries[&key].name.clone();
        fs::write(path.join(filename), [0u8; 16]).unwrap();
        assert!(cache
            .load_disk(key, values.len(), &CancellationToken::new())
            .is_err());
        cache.clear().unwrap();
        drop(cache);
        assert!(!path.exists());
        let path = private_cache("work");
        let mut cache = ValidFft::with_disk_cache(1 << 20, &path, 1 << 20, vec![], 1).unwrap();
        let raw = fixture(47, 29);
        let q = Rect {
            x: 0,
            y: 0,
            w: 47,
            h: 29,
        };
        let k = ExactDiffusionKernel::new(&config(0.), 10., 47, 29).unwrap();
        assert!(matches!(
            cache.apply(&raw, q, q, (47, 29), &k, &CancellationToken::new()),
            Err(DetailError::BudgetExceeded { .. })
        ));
        assert_eq!(cache.stats().kernel_builds, 0);
        drop(cache);
        assert!(!path.exists());
    }
}
