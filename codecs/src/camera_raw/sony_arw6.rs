//! ARW6 compression32766 only: anonymous integer spool then existing Bayer development.
use super::*;
use arw6_bounded_prototype::storage::Scratch;
use std::sync::Arc;
pub(super) fn codec_error(
    e: Box<dyn std::error::Error + Send + Sync>,
    cancel: &AtomicBool,
) -> crate::error::CodecError {
    if cancel.load(Ordering::Relaxed) {
        return err("CANCELLED", "decode");
    }
    if let Some(io) = e.downcast_ref::<std::io::Error>() {
        return io_error(
            std::io::Error::from_raw_os_error(io.raw_os_error().unwrap_or(libc::EIO)),
            "spool",
        );
    }
    if e.to_string().contains("memory budget") {
        return err("MEMORY_BUDGET", "admission");
    }
    if e.to_string().contains("budget") {
        return err("DISK_BUDGET", "admission");
    }
    err("INVALID_INPUT", "decode")
}
pub(super) fn decode(
    camera: &CameraRaw,
    input: &Path,
    output: &Path,
    b: &Budget,
    admission: &Admission,
    cancel: &AtomicBool,
) -> Result<(Probe, Admission)> {
    let (offset, count, _) = camera.arw6.ok_or_else(unsupported)?;
    let directory = output
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let forwarded = Arc::new(AtomicBool::new(cancel.load(Ordering::Relaxed)));
    let scratch = Scratch::new(
        directory,
        b.disk_budget_bytes.min(1024 * MIB),
        forwarded.clone(),
    )
    .map_err(|e| codec_error(e, cancel))?;
    let (mosaic, d, black, white) = {
        let source = RawSource::new(input).map_err(|e| io_error(e, "decode"))?;
        let raw = bounded("decode", || rawler::decode_dummy(&source))?;
        let d = descriptor(&raw, camera.development.orientation)?;
        let rect = |r: Rect| (r.x, r.y, r.w, r.h);
        if raw.source_ifd_offset != Some(camera.source_ifd_offset)
            || d.cpp != 1
            || d.lens != camera.development.lens
            || d.width != camera.development.width
            || d.height != camera.development.height
            || rect(d.active) != rect(camera.development.active)
            || rect(d.crop) != rect(camera.development.crop)
            || raw.blacklevel.cpp != 1
            || !matches!(
                (raw.blacklevel.width, raw.blacklevel.height),
                (1, 1) | (2, 2)
            )
            || raw.blacklevel.levels.len() != raw.blacklevel.width * raw.blacklevel.height
            || !matches!(raw.whitelevel.0.len(), 1 | 4)
        {
            return Err(err("INVALID_COLOR_METADATA", "decode"));
        }
        let black = raw.blacklevel.as_bayer_array();
        let white = raw.whitelevel.as_bayer_array();
        if black
            .iter()
            .zip(white)
            .any(|(b, w)| !b.is_finite() || *b < 0. || !w.is_finite() || w <= *b)
        {
            return Err(err("INVALID_COLOR_METADATA", "decode"));
        }
        drop(raw);
        let bytes = source
            .subview(offset, count)
            .map_err(|e| io_error(e, "decode"))?;
        // Bridge the existing borrowed cancellation contract without unsafe pointer ownership.
        // A scoped128KiB poller is within BASE, joins before metadata/input is released.
        let stop = AtomicBool::new(false);
        let mosaic = std::thread::scope(|scope| -> Result<_> {
            let watch = &stop;
            let shared = &forwarded;
            let handle = std::thread::Builder::new()
                .name("arw6-cancel".into())
                .stack_size(128 * 1024)
                .spawn_scoped(scope, move || {
                    while !watch.load(Ordering::Relaxed) {
                        if cancel.load(Ordering::Relaxed) {
                            shared.store(true, Ordering::Relaxed);
                            break;
                        }
                        std::thread::sleep(std::time::Duration::from_millis(1));
                    }
                })
                .map_err(|_| err("MEMORY_BUDGET", "decode"))?;
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                arw6_bounded_prototype::bounded::decode_with_memory_limit(&scratch, bytes, d.width, d.height, admission.memory_budget_bytes)
            }));
            stop.store(true, Ordering::Relaxed);
            handle.join().map_err(|_| err("INVALID_INPUT", "decode"))?;
            result
                .map_err(|_| err("INVALID_INPUT", "decode"))?
                .map_err(|e| codec_error(e, cancel))
        })?;
        (mosaic, d, black, white)
    }; // Full input map and decoder metadata dropped; only integer FD remains.
    if cancel.load(Ordering::Relaxed) {
        return Err(err("CANCELLED", "decode"));
    }
    if scratch.usage().0 != (d.width * d.height * 2) as u64
        || scratch.usage().1 > admission.required_disk_bytes
    {
        return Err(err("DISK_BUDGET", "admission"));
    }
    super::integer_spool::develop(camera, output, admission, cancel, &mosaic, &d, black, white)
}
