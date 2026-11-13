//! Original-size row/strip exports. JPEG must remain baseline INTERLEAVED:
//! progressive, optimized-Huffman and unusual sampling paths retain all DCT blocks.
use crate::{
    develop::Budget,
    error::{CodecError, Result, err, io_error},
};
use jpeg_encoder::{Encoder, ImageBuffer, JpegColorType, SamplingFactor, rgb_to_ycbcr};
use serde::{Deserialize, Serialize};
use std::{
    cell::RefCell,
    fs::{File, OpenOptions},
    io::{self, BufWriter, Read, Seek, SeekFrom, Write},
    os::{
        fd::AsRawFd,
        unix::fs::{FileExt, OpenOptionsExt},
    },
    path::Path,
    rc::Rc,
    sync::atomic::{AtomicBool, Ordering},
};
#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExportParams {
    pub width: u32,
    pub height: u32,
    pub format: String,
    pub quality: Option<u8>,
    #[serde(default = "crate::output_color::default_output_color_space")]
    pub output_color_space: String,
    #[serde(flatten)]
    pub budget: Budget,
}
#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ExportReport {
    pub format: &'static str,
    pub mime_type: &'static str,
    pub width: u32,
    pub height: u32,
    pub output_bytes: u64,
    pub estimated_peak_bytes: u64,
    pub required_disk_bytes: u64,
    pub color_space: &'static str,
    pub bit_depth: u8,
    pub output_orientation: u8,
    pub clipped_samples: u64,
}
type Reason = Rc<RefCell<Option<CodecError>>>;
fn check(cancel: &AtomicBool, reason: &Reason) -> io::Result<()> {
    if cancel.load(Ordering::Relaxed) {
        *reason.borrow_mut() = Some(err("CANCELLED", "encode"));
    }
    if reason.borrow().is_some() {
        Err(io::Error::other("CODEC_STOP"))
    } else {
        Ok(())
    }
}
struct CheckedWriter<'a> {
    file: &'a mut File,
    cancel: &'a AtomicBool,
    reason: Reason,
}
impl Write for CheckedWriter<'_> {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        check(self.cancel, &self.reason)?;
        self.file.write(b)
    }
    fn flush(&mut self) -> io::Result<()> {
        check(self.cancel, &self.reason)?;
        self.file.flush()
    }
}
impl Seek for CheckedWriter<'_> {
    fn seek(&mut self, s: SeekFrom) -> io::Result<u64> {
        check(self.cancel, &self.reason)?;
        self.file.seek(s)
    }
}
struct Guard<'a> {
    path: &'a Path,
    keep: bool,
}
impl Drop for Guard<'_> {
    fn drop(&mut self) {
        if !self.keep {
            let _ = std::fs::remove_file(self.path);
        }
    }
}
pub fn srgb(v: f32) -> f32 {
    let v = v.clamp(0., 1.);
    if v <= 0.0031308 {
        12.92 * v
    } else {
        1.055 * v.powf(1. / 2.4) - 0.055
    }
}
fn read_at_all(f: &File, mut b: &mut [u8], mut offset: u64) -> io::Result<()> {
    while !b.is_empty() {
        let n = f.read_at(b, offset)?;
        if n == 0 {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        b = &mut b[n..];
        offset += n as u64;
    }
    Ok(())
}
/// Rows consumed is work progress, not a promise that entropy coding/sync finished.
pub type EncodeProgress<'a> = &'a dyn Fn(u32, u32, bool);
struct JpegRows<'a> {
    file: &'a File,
    width: u16,
    height: u16,
    row: RefCell<Vec<u8>>,
    cancel: &'a AtomicBool,
    reason: Reason,
    clipped: Rc<RefCell<u64>>,
    last_counted_y: RefCell<Option<u16>>,
    color_space: crate::output_color::OutputColorSpace,
    progress: EncodeProgress<'a>,
}
impl ImageBuffer for JpegRows<'_> {
    fn get_jpeg_color_type(&self) -> JpegColorType {
        JpegColorType::Ycbcr
    }
    fn width(&self) -> u16 {
        self.width
    }
    fn height(&self) -> u16 {
        self.height
    }
    fn fill_buffers(&self, y: u16, buffers: &mut [Vec<u8>; 4]) {
        let mut row = self.row.borrow_mut();
        let good = check(self.cancel, &self.reason)
            .and_then(|_| read_at_all(self.file, &mut row, y as u64 * self.width as u64 * 12));
        if let Err(e) = good {
            if self.reason.borrow().is_none() {
                *self.reason.borrow_mut() = Some(io_error(e, "encode"));
            }
            for b in &mut buffers[..3] {
                b.extend(std::iter::repeat_n(0, self.width as usize));
            }
            return;
        }
        let count_clipping = self.last_counted_y.replace(Some(y)) != Some(y);
        for pix in row.chunks_exact(12) {
            let mut c = [0u8; 3];
            for i in 0..3 {
                let v = f32::from_le_bytes(pix[i * 4..i * 4 + 4].try_into().unwrap());
                if !v.is_finite() {
                    *self.reason.borrow_mut() = Some(err("INVALID_INPUT", "encode"));
                }
                if count_clipping && !(0. ..=1.).contains(&v) {
                    *self.clipped.borrow_mut() += 1;
                }
                c[i] = (self.color_space.encode(v) * 255.).round() as u8;
            }
            let (y, cb, cr) = rgb_to_ycbcr(c[0], c[1], c[2]);
            buffers[0].push(y);
            buffers[1].push(cb);
            buffers[2].push(cr);
        }
        if self.reason.borrow().is_none() && count_clipping {
            (self.progress)(u32::from(y) + 1, u32::from(self.height), false);
        }
    }
}
fn jpeg(
    input: &File,
    out: &mut File,
    p: &ExportParams,
    cancel: &AtomicBool,
    reason: Reason,
    icc: &[u8],
    progress: EncodeProgress<'_>,
) -> Result<u64> {
    let clipped = Rc::new(RefCell::new(0));
    let rows = JpegRows {
        file: input,
        width: p.width as u16,
        height: p.height as u16,
        row: RefCell::new(vec![0; p.width as usize * 12]),
        cancel,
        reason: reason.clone(),
        clipped: clipped.clone(),
        last_counted_y: RefCell::new(None),
        progress,
        color_space: crate::output_color::OutputColorSpace::parse(&p.output_color_space)?,
    };
    let writer = CheckedWriter {
        file: out,
        cancel,
        reason: reason.clone(),
    };
    let mut writer = BufWriter::with_capacity(65536, writer);
    let mut encoder = Encoder::new(&mut writer, p.quality.unwrap_or(95));
    encoder.set_progressive(false);
    encoder.set_optimized_huffman_tables(false);
    encoder.set_sampling_factor(SamplingFactor::F_1_1);
    encoder
        .add_icc_profile(icc)
        .map_err(|_| err("ENCODE_FAILED", "encode"))?;
    let r = encoder.encode_image(rows);
    if let Some(e) = reason.borrow_mut().take() {
        return Err(e);
    }
    r.map_err(|e| match e {
        jpeg_encoder::EncodingError::IoError(e) => io_error(e, "encode"),
        _ => err("ENCODE_FAILED", "encode"),
    })?;
    writer.flush().map_err(|e| io_error(e, "encode"))?;
    let count = *clipped.borrow();
    Ok(count)
}
fn tiff_error(e: ::tiff::TiffError) -> CodecError {
    match e {
        ::tiff::TiffError::IoError(e) => io_error(e, "encode"),
        _ => err("ENCODE_FAILED", "encode"),
    }
}
fn tiff16(
    input: &mut File,
    out: &mut File,
    p: &ExportParams,
    cancel: &AtomicBool,
    reason: Reason,
    icc: &[u8],
    progress: EncodeProgress<'_>,
) -> Result<u64> {
    let writer = CheckedWriter {
        file: out,
        cancel,
        reason: reason.clone(),
    };
    let mut writer = BufWriter::with_capacity(65536, writer);
    let color_space = crate::output_color::OutputColorSpace::parse(&p.output_color_space)?;
    let result = (|| -> Result<u64> {
        let mut encoder = ::tiff::encoder::TiffEncoder::new(&mut writer).map_err(tiff_error)?;
        let mut image = encoder
            .new_image::<::tiff::encoder::colortype::RGB16>(p.width, p.height)
            .map_err(tiff_error)?;
        image.rows_per_strip(32).map_err(tiff_error)?;
        image
            .encoder()
            .write_tag(::tiff::tags::Tag::IccProfile, icc)
            .map_err(tiff_error)?;
        image
            .encoder()
            .write_tag(::tiff::tags::Tag::Orientation, 1u16)
            .map_err(tiff_error)?;
        let mut clipped = 0;
        let mut written_samples = 0u64;
        while image.next_strip_sample_count() > 0 {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "encode"));
            }
            let n = image.next_strip_sample_count() as usize;
            let mut bytes = vec![0; n * 4];
            input
                .read_exact(&mut bytes)
                .map_err(|e| io_error(e, "encode"))?;
            let mut samples = Vec::with_capacity(n);
            for b in bytes.chunks_exact(4) {
                let v = f32::from_le_bytes(b.try_into().unwrap());
                if !v.is_finite() {
                    return Err(err("INVALID_INPUT", "encode"));
                }
                if !(0. ..=1.).contains(&v) {
                    clipped += 1;
                }
                samples.push((color_space.encode(v) * 65535.).round() as u16);
            }
            image.write_strip(&samples).map_err(tiff_error)?;
            written_samples += n as u64;
            progress(
                (written_samples / (u64::from(p.width) * 3)) as u32,
                p.height,
                false,
            );
        }
        image.finish().map_err(tiff_error)?;
        Ok(clipped)
    })();
    if let Some(e) = reason.borrow_mut().take() {
        return Err(e);
    }
    let n = result?;
    writer.flush().map_err(|e| io_error(e, "encode"))?;
    Ok(n)
}
pub fn encode(
    input: &Path,
    output: &Path,
    p: &ExportParams,
    cancel: &AtomicBool,
) -> Result<ExportReport> {
    encode_with_progress(input, output, p, cancel, &|_, _, _| {})
}
/// `finished` is only emitted after the encoder and output sync succeed.
pub fn encode_with_progress(
    input: &Path,
    output: &Path,
    p: &ExportParams,
    cancel: &AtomicBool,
    progress: EncodeProgress<'_>,
) -> Result<ExportReport> {
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "encode"));
    }
    let color_space = crate::output_color::OutputColorSpace::parse(&p.output_color_space)?;
    let (format, mime, bits) = match p.format.as_str() {
        "JPEG" => ("JPEG", "image/jpeg", 8),
        "TIFF16" => ("TIFF16", "image/tiff", 16),
        _ => return Err(err("UNSUPPORTED_FORMAT", "admission")),
    };
    if p.quality.is_some_and(|q| q == 0 || q > 100) {
        return Err(err("INVALID_INPUT", "admission"));
    }
    if p.width == 0 || p.height == 0 {
        return Err(err("INVALID_INPUT", "admission"));
    }
    if p.width > crate::export_limits::MAX_EXPORT_EDGE
        || p.height > crate::export_limits::MAX_EXPORT_EDGE
    {
        return Err(err("EDGE_LIMIT", "admission"));
    }
    let admission = crate::export_limits::plan(p.width, p.height, format == "JPEG")
        .ok_or_else(|| err("PIXEL_LIMIT", "admission"))?;
    let peak = admission.estimated_peak_bytes;
    if p.budget
        .memory_budget_mi_b
        .checked_mul(1024 * 1024)
        .is_none_or(|b| b < peak)
    {
        return Err(err("MEMORY_BUDGET", "admission"));
    }
    let disk = admission.required_disk_bytes;
    if p.budget.disk_budget_bytes < disk {
        return Err(err("DISK_BUDGET", "admission"));
    }
    let mut input = File::open(input).map_err(|e| io_error(e, "encode"))?;
    if input.metadata().map_err(|e| io_error(e, "encode"))?.len() != admission.input_bytes {
        return Err(err("INVALID_INPUT", "encode"));
    }
    let mut out = OpenOptions::new()
        .write(true)
        .create_new(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(output)
        .map_err(|e| io_error(e, "encode"))?;
    let mut guard = Guard {
        path: output,
        keep: false,
    };
    let mut stat = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    if unsafe { libc::fstatvfs(out.as_raw_fd(), stat.as_mut_ptr()) } != 0 {
        return Err(err("IO_ERROR", "admission"));
    }
    let stat = unsafe { stat.assume_init() };
    if (stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64) < disk {
        return Err(err("DISK_BUDGET", "admission"));
    }
    let icc = color_space.icc()?;
    let reason = Rc::new(RefCell::new(None));
    progress(0, p.height, false);
    let clipped = if format == "JPEG" {
        jpeg(&input, &mut out, p, cancel, reason, &icc, progress)?
    } else {
        tiff16(&mut input, &mut out, p, cancel, reason, &icc, progress)?
    };
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "encode"));
    }
    out.sync_all().map_err(|e| io_error(e, "encode"))?;
    let output_bytes = out.metadata().map_err(|e| io_error(e, "encode"))?.len();
    guard.keep = true;
    progress(p.height, p.height, true);
    Ok(ExportReport {
        format,
        mime_type: mime,
        width: p.width,
        height: p.height,
        output_bytes,
        estimated_peak_bytes: peak,
        required_disk_bytes: disk,
        color_space: color_space.name(),
        bit_depth: bits,
        output_orientation: 1,
        clipped_samples: clipped,
    })
}
