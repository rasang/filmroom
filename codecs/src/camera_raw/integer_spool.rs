//! Shared full-sensor integer FD development; global halo, never tile-local demosaic.
use super::sony_arw6::codec_error;
use super::*;
use arw6_bounded_prototype::storage::AnonymousFile;
use std::os::fd::AsRawFd;
pub(super) fn develop(
    camera: &CameraRaw,
    output: &Path,
    admission: &Admission,
    cancel: &AtomicBool,
    mosaic: &AnonymousFile,
    d: &Development,
    black: [f32; 4],
    white: [f32; 4],
) -> Result<(Probe, Admission)> {
    let out = OpenOptions::new()
        .write(true)
        .create_new(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(output)
        .map_err(|e| io_error(e, "spool"))?;
    let mut guard = OutputGuard(output, false);
    // Caller has dropped metadata/input and any coefficient FDs; only integer spool remains.
    let rc = unsafe {
        libc::posix_fallocate(
            out.as_raw_fd(),
            0,
            camera.probe().output_bytes as libc::off_t,
        )
    };
    if rc != 0 {
        return Err(io_error(std::io::Error::from_raw_os_error(rc), "spool"));
    }
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .stack_size(1024 * 1024)
        .build()
        .map_err(|_| err("MEMORY_BUDGET", "develop"))?;
    let sink = crate::develop::CameraRgbSink::new(d, output)?;
    let region = sink.region(d);
    let mut first = region.y;
    while first < region.y + region.h {
        if cancel.load(Ordering::Relaxed) {
            return Err(err("CANCELLED", "develop"));
        }
        let last = (first + admission.strip_rows as usize).min(region.y + region.h);
        let y = first.saturating_sub(HALO).max(d.active.y);
        let end = (last + HALO).min(d.active.y + d.active.h);
        let mut normalized = Vec::with_capacity(d.active.w * (end - y));
        let mut row = vec![0u8; d.active.w * 2];
        for sy in y..end {
            if cancel.load(Ordering::Relaxed) {
                return Err(err("CANCELLED", "develop"));
            }
            mosaic
                .read(((sy * d.width + d.active.x) * 2) as u64, &mut row)
                .map_err(|e| codec_error(e, cancel))?;
            for (x, v) in row.chunks_exact(2).enumerate() {
                let ch = (sy % 2) * 2 + (d.active.x + x) % 2;
                normalized.push(
                    (u16::from_le_bytes(v.try_into().unwrap()) as f32 - black[ch]).max(0.)
                        / (white[ch] - black[ch]),
                );
            }
        }
        drop(row);
        let p = pool
            .install(|| sink.develop(&d, y, end - y, normalized, cancel))?;
        sink.emit_strip(&d, &out, &p, y, first, last, cancel)?;
        first = last;
    }
    pool.install(|| sink.finish(d, &out, cancel))?;
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "spool"));
    }
    out.sync_all().map_err(|e| io_error(e, "spool"))?;
    guard.1 = true;
    Ok((camera.probe(), admission.clone()))
}
