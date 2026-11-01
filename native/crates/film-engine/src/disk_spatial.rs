//! Exact full-image Gaussian passes on local planar little-endian f64 files.
//!
//! The physical operator is the existing FIR/Young-van Vliet IIR, including its
//! whole-image boundary initialization. Only storage/layout changes. A row pass
//! writes one full-plane temporary; full-column strips then produce the output.
//! No finite halo, downsampling or per-pixel filesystem seek is involved.
use std::{fs::{File, OpenOptions}, io::{self, Read, Seek, SeekFrom, Write}, path::{Path, PathBuf}, sync::atomic::{AtomicU64, Ordering}};
use spektrafilm_math::{gaussian::gaussian_blur_rows, precision::Scalar};

#[derive(Clone, Copy, Debug, serde::Serialize)]
pub struct DiskProgress { pub pass: &'static str, pub completed: u64, pub total: u64 }
#[derive(Clone, Copy, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiskMetrics {
    /// Extra intermediate storage; excludes caller input and final output.
    pub scratch_bytes: u64,
    pub input_bytes: u64,
    pub output_bytes: u64,
    /// Maximum explicitly owned sample/byte buffers; not process RSS. Small
    /// filter kernels, Rayon scheduling and filesystem cache are not included.
    pub peak_buffer_bytes: u64,
    pub bytes_read: u64,
    pub bytes_written: u64,
    pub row_batch_rows: u32,
    pub column_strip_columns: u32,
}

static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);
struct Cleanup { path: PathBuf, keep: bool }
impl Cleanup { fn new(path: &Path) -> Self { Self { path: path.to_path_buf(), keep: false } } }
impl Drop for Cleanup { fn drop(&mut self) { if !self.keep { let _ = std::fs::remove_file(&self.path); } } }
fn invalid(message: &'static str) -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, message) }
fn cancelled_error() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, "Spatial pass cancelled") }
fn check_cancel(cancelled: &mut impl FnMut() -> bool) -> io::Result<()> { if cancelled() { Err(cancelled_error()) } else { Ok(()) } }
fn decode(src: &[u8], dst: &mut [Scalar]) -> io::Result<()> {
    debug_assert_eq!(src.len(), dst.len() * 8);
    for (bytes, value) in src.chunks_exact(8).zip(dst) {
        let x = f64::from_le_bytes(bytes.try_into().unwrap());
        if !x.is_finite() { return Err(io::Error::new(io::ErrorKind::InvalidData, "Nonfinite spatial input")); }
        *value = x as Scalar;
    }
    Ok(())
}
fn encode(src: &[Scalar], dst: &mut [u8]) -> io::Result<()> {
    debug_assert_eq!(dst.len(), src.len() * 8);
    for (&value, bytes) in src.iter().zip(dst.chunks_exact_mut(8)) {
        if !value.is_finite() { return Err(io::Error::new(io::ErrorKind::InvalidData, "Nonfinite spatial output")); }
        bytes.copy_from_slice(&(value as f64).to_le_bytes());
    }
    Ok(())
}
fn new_temp(scratch_dir: &Path) -> io::Result<(File, Cleanup)> {
    for _ in 0..64 {
        let serial = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
        let path = scratch_dir.join(format!("spatial-{}-{serial}.f64le", std::process::id()));
        match OpenOptions::new().read(true).write(true).create_new(true).open(&path) {
            Ok(file) => return Ok((file, Cleanup::new(&path))),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    Err(io::Error::new(io::ErrorKind::AlreadyExists, "Spatial scratch names exhausted"))
}

/// Process one planar f64 image. Input/output/scratch are trusted app-private
/// paths validated by the caller. Output must not exist. On error, cancellation
/// or unwind, all newly-created output/scratch files are removed; input remains.
///
/// `memory_bytes` limits the explicit sample/byte buffers (typically16MiB).
/// Positive sigma requires one extra `width*height*8` scratch file. Total live
/// storage including input and output is3 planes, not an unbounded tile cache.
#[allow(clippy::too_many_arguments)]
pub fn gaussian_file(
    input_path: &Path, output_path: &Path, width: u32, height: u32, sigma: f32,
    scratch_dir: &Path, memory_bytes: usize,
    cancelled: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(DiskProgress),
) -> io::Result<DiskMetrics> {
    if !cfg!(feature = "precision-f64") { return Err(io::Error::new(io::ErrorKind::Unsupported, "Disk spatial passes require precision-f64")); }
    if width == 0 || height == 0 || width > 40000 || height > 40000 { return Err(invalid("Spatial dimensions must be1..40000")); }
    if !sigma.is_finite() || sigma < 0.0 { return Err(invalid("Sigma must be finite and nonnegative")); }
    if input_path == output_path { return Err(invalid("Spatial input and output must differ")); }
    let w = width as usize; let h = height as usize;
    let samples = (width as u64).checked_mul(height as u64).ok_or_else(|| invalid("Spatial size overflow"))?;
    let plane_bytes = samples.checked_mul(8).ok_or_else(|| invalid("Spatial byte count overflow"))?;
    let row_work_bytes = w.checked_mul(24).ok_or_else(|| invalid("Spatial row budget overflow"))?;
    let column_work_bytes = h.checked_mul(16).and_then(|n|n.checked_add(8)).ok_or_else(|| invalid("Spatial column budget overflow"))?;
    if memory_bytes > 64*1024*1024 { return Err(invalid("Spatial explicit buffer budget must not exceed64MiB")); }
    if memory_bytes < row_work_bytes || (sigma > 0.0 && memory_bytes < column_work_bytes) { return Err(invalid("Spatial working budget cannot fit one complete row/column")); }
    check_cancel(cancelled)?;
    let mut input = File::open(input_path)?;
    if !input.metadata()?.is_file() || input.metadata()?.len() != plane_bytes { return Err(invalid("Spatial input size must equal width*height*8")); }
    let mut output = OpenOptions::new().write(true).create_new(true).open(output_path)?;
    let mut output_guard = Cleanup::new(output_path);
    output.set_len(plane_bytes)?;
    let row_batch = (memory_bytes / row_work_bytes).min(h).max(1);
    let row_samples = row_batch * w;
    let mut byte_buffer = vec![0u8; row_samples * 8];
    let mut src = vec![0.0 as Scalar; row_samples];
    let mut dst = vec![0.0 as Scalar; row_samples];
    let mut metrics = DiskMetrics {
        scratch_bytes: if sigma > 0.0 { plane_bytes } else { 0 },
        input_bytes: plane_bytes, output_bytes: plane_bytes,
        peak_buffer_bytes: (row_samples * 24) as u64,
        bytes_read: 0, bytes_written: 0,
        row_batch_rows: row_batch as u32, column_strip_columns: 0,
    };
    if sigma == 0.0 {
        progress(DiskProgress { pass: "identity", completed: 0, total: height as u64 });
        for y in (0..h).step_by(row_batch) {
            check_cancel(cancelled)?;
            let rows = row_batch.min(h-y); let len = rows*w;
            input.read_exact(&mut byte_buffer[..len*8])?;
            decode(&byte_buffer[..len*8], &mut src[..len])?;
            output.write_all(&byte_buffer[..len*8])?;
            metrics.bytes_read += (len*8) as u64; metrics.bytes_written += (len*8) as u64;
            progress(DiskProgress { pass: "identity", completed: (y+rows) as u64, total: height as u64 });
        }
        check_cancel(cancelled)?; output.flush()?; drop(output); output_guard.keep = true;
        return Ok(metrics);
    }
    let (mut horizontal, horizontal_guard) = new_temp(scratch_dir)?;
    horizontal.set_len(plane_bytes)?;
    progress(DiskProgress { pass: "horizontal", completed: 0, total: height as u64 });
    for y in (0..h).step_by(row_batch) {
        check_cancel(cancelled)?;
        let rows = row_batch.min(h-y); let len = rows*w;
        input.read_exact(&mut byte_buffer[..len*8])?;
        decode(&byte_buffer[..len*8], &mut src[..len])?;
        gaussian_blur_rows(&src[..len], &mut dst[..len], w, sigma);
        encode(&dst[..len], &mut byte_buffer[..len*8])?;
        horizontal.write_all(&byte_buffer[..len*8])?;
        metrics.bytes_read += (len*8) as u64; metrics.bytes_written += (len*8) as u64;
        progress(DiskProgress { pass: "horizontal", completed: (y+rows) as u64, total: height as u64 });
    }
    horizontal.flush()?;
    drop(input); drop(src); drop(dst); drop(byte_buffer);
    let strip_columns = (memory_bytes / column_work_bytes).min(w).max(1);
    let strip_samples = strip_columns * h;
    let mut columns = vec![0.0 as Scalar; strip_samples];
    let mut filtered = vec![0.0 as Scalar; strip_samples];
    let mut row_bytes = vec![0u8; strip_columns*8];
    metrics.column_strip_columns = strip_columns as u32;
    metrics.peak_buffer_bytes = metrics.peak_buffer_bytes.max((strip_samples*16+strip_columns*8) as u64);
    progress(DiskProgress { pass: "vertical", completed: 0, total: width as u64 });
    for left in (0..w).step_by(strip_columns) {
        check_cancel(cancelled)?;
        let cols = strip_columns.min(w-left);
        for y in 0..h {
            check_cancel(cancelled)?;
            horizontal.seek(SeekFrom::Start(((y as u64)*(width as u64)+left as u64)*8))?;
            horizontal.read_exact(&mut row_bytes[..cols*8])?;
            for x in 0..cols { columns[x*h+y] = f64::from_le_bytes(row_bytes[x*8..x*8+8].try_into().unwrap()) as Scalar; }
        }
        metrics.bytes_read += (cols*h*8) as u64;
        // Each contiguous row is a complete ORIGINAL image column, so the
        // IIR forward/backward state is initialized at true image boundaries.
        gaussian_blur_rows(&columns[..cols*h], &mut filtered[..cols*h], h, sigma);
        for y in 0..h {
            check_cancel(cancelled)?;
            for x in 0..cols {
                let value = filtered[x*h+y];
                if !value.is_finite() { return Err(io::Error::new(io::ErrorKind::InvalidData, "Nonfinite spatial output")); }
                row_bytes[x*8..x*8+8].copy_from_slice(&(value as f64).to_le_bytes());
            }
            output.seek(SeekFrom::Start(((y as u64)*(width as u64)+left as u64)*8))?;
            output.write_all(&row_bytes[..cols*8])?;
        }
        metrics.bytes_written += (cols*h*8) as u64;
        progress(DiskProgress { pass: "vertical", completed: (left+cols) as u64, total: width as u64 });
    }
    check_cancel(cancelled)?; output.flush()?;
    drop(horizontal); drop(horizontal_guard); drop(output); output_guard.keep = true;
    Ok(metrics)
}
