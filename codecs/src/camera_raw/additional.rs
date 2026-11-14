//! CR3/ORF/PEF: validated raw sensor segments, anonymous integer spool, shared color development.
use super::*;
use arw6_bounded_prototype::storage::Scratch;
use rawler::decoders::{BoundedBayerPlan, FormatHint};
use std::sync::Arc;
mod cr3_boxes;

pub(crate) struct AdditionalRaw {
    camera: CameraRaw,
    plan: BoundedBayerPlan,
    file: File,
    source: RawSource,
    identity: crate::nikon_he::Identity,
    black: [f32; 4],
    white: [f32; 4],
}
pub(crate) fn orf_magic(h: &[u8]) -> bool {
    h.starts_with(b"IIRO") || h.starts_with(b"IIRS") || h.starts_with(b"MMOR")
}
pub(crate) fn cr3_magic(h: &[u8]) -> bool {
    h.len() >= 12 && &h[4..8] == b"ftyp" && &h[8..12] == b"crx "
}
// Only the bounded root identity is needed here. Opaque MakerNotes can contain
// megabytes of previews; the opted-in upstream parser reads their required fields.
fn identify_tiff(file: &mut File, h: &[u8], size: u64) -> Result<Option<&'static str>> {
    use crate::tiff::Endian;
    let endian = match &h[..2] {
        b"II" => Endian::Le,
        b"MM" => Endian::Be,
        _ => return Ok(None),
    };
    if endian.u16(&h[2..]) != 42 && !orf_magic(h) {
        return Ok(None);
    }
    let at = endian.u32(&h[4..]) as u64;
    if at < 8 || at > size - 2 {
        return Err(err("INVALID_INPUT", "probe"));
    }
    file.seek(SeekFrom::Start(at))
        .map_err(|e| io_error(e, "probe"))?;
    let mut count = [0; 2];
    file.read_exact(&mut count)
        .map_err(|e| io_error(e, "probe"))?;
    let count = endian.u16(&count) as usize;
    if count > 256 || at + 2 + count as u64 * 12 + 4 > size {
        return Err(err("INVALID_INPUT", "probe"));
    }
    let mut table = vec![0; count * 12];
    file.read_exact(&mut table)
        .map_err(|e| io_error(e, "probe"))?;
    let mut seen = std::collections::BTreeSet::new();
    let mut make = None;
    for entry in table.chunks_exact(12) {
        let tag = endian.u16(entry);
        if !seen.insert(tag) {
            return Err(err("INVALID_INPUT", "probe"));
        }
        if tag == 50706 {
            return Ok(None);
        }
        if tag != 271 {
            continue;
        }
        let n = endian.u32(&entry[4..]) as usize;
        if endian.u16(&entry[2..]) != 2 || n == 0 || n > 256 {
            return Err(err("INVALID_INPUT", "probe"));
        }
        let mut text = vec![0; n];
        if n <= 4 {
            text.copy_from_slice(&entry[8..8 + n]);
        } else {
            let offset = endian.u32(&entry[8..]) as u64;
            if offset + n as u64 > size {
                return Err(err("INVALID_INPUT", "probe"));
            }
            file.seek(SeekFrom::Start(offset))
                .map_err(|e| io_error(e, "probe"))?;
            file.read_exact(&mut text)
                .map_err(|e| io_error(e, "probe"))?;
        }
        make = Some(text);
    }
    let make = make.unwrap_or_default();
    let make = make.strip_suffix(&[0]).unwrap_or(&make).trim_ascii_end();
    Ok(
        if orf_magic(h)
            && matches!(
                make,
                b"OLYMPUS IMAGING CORP."
                    | b"OLYMPUS CORPORATION"
                    | b"OLYMPUS OPTICAL CO.,LTD"
                    | b"OM Digital Solutions"
            )
        {
            Some("ORF")
        } else if matches!(
            make,
            b"PENTAX" | b"PENTAX Corporation" | b"RICOH IMAGING COMPANY, LTD."
        ) {
            Some("PEF")
        } else {
            None
        },
    )
}
impl AdditionalRaw {
    pub(crate) fn read(input: &Path) -> Result<Option<Self>> {
        let mut file = File::open(input).map_err(|e| io_error(e, "probe"))?;
        let identity = crate::nikon_he::Identity::read(&file)?;
        if !(16..=512 * MIB).contains(&identity.bytes) {
            return Err(err("INVALID_INPUT", "probe"));
        }
        let mut h = [0u8; 16];
        file.read_exact(&mut h).map_err(|e| io_error(e, "probe"))?;
        let format = if cr3_magic(&h) {
            cr3_boxes::validate(&mut file, identity.bytes)?;
            "CR3"
        } else {
            file.seek(SeekFrom::Start(0))
                .map_err(|e| io_error(e, "probe"))?;
            match identify_tiff(&mut file, &h, identity.bytes)? {
                Some(format) => format,
                None => return Ok(None),
            }
        };
        let source = RawSource::new_from_file(&file, input).map_err(|e| io_error(e, "probe"))?;
        let (plan, raw) = bounded("probe", || {
            let decoder = rawler::get_decoder(&source)?;
            if !matches!(
                (format, decoder.format_hint()),
                ("CR3", FormatHint::CR3) | ("ORF", FormatHint::ORF) | ("PEF", FormatHint::PEF)
            ) {
                return Err("Unexpected RAW decoder".into());
            }
            let plan = decoder.bounded_bayer_plan(&source)?.ok_or_else(|| {
                rawler::RawlerError::DecoderFailed("Bounded variant unsupported".into())
            })?;
            rawler::check_bounded_raw_size(plan.width, plan.height)?;
            if plan.width < 8 || plan.height < 8 {
                return Err("RAW sensor bounds".into());
            }
            let mut raw = decoder.raw_image(&source, &Default::default(), true)?;
            if let Some([x, y, w, h]) = plan.active_area_override {
                raw.active_area = Some(rawler::imgop::Rect::new(
                    rawler::imgop::Point::new(x, y),
                    rawler::imgop::Dim2::new(w, h),
                ));
            }
            Ok((plan, raw))
        })?;
        crate::validate_dimensions(
            plan.width.try_into().map_err(|_| unsupported())?,
            plan.height.try_into().map_err(|_| unsupported())?,
        )?;
        if plan.format != format
            || plan.width != raw.width
            || plan.height != raw.height
            || !matches!(plan.bits, 12 | 14 | 16)
            || !(1..=8).contains(&plan.orientation)
            || plan.scratch_bytes > 128 * MIB
            || raw.cpp != 1
            || !matches!(&raw.data,RawImageData::Integer(v) if v.is_empty())
        {
            return Err(unsupported());
        }
        let mut development = descriptor(&raw, plan.orientation)?;
        configure_profile(&source,&file,format,&raw,&mut development);
        if raw.blacklevel.cpp != 1
            || !matches!(
                (raw.blacklevel.width, raw.blacklevel.height),
                (1, 1) | (2, 2)
            )
            || raw.blacklevel.levels.len() != raw.blacklevel.width * raw.blacklevel.height
            || !matches!(raw.whitelevel.0.len(), 1 | 4)
        {
            return Err(err("INVALID_COLOR_METADATA", "probe"));
        }
        let black = raw.blacklevel.as_bayer_array();
        let white = raw.whitelevel.as_bayer_array();
        if black
            .iter()
            .zip(white)
            .any(|(b, w)| !b.is_finite() || *b < 0. || !w.is_finite() || w <= *b || w > 65535.)
        {
            return Err(err("INVALID_COLOR_METADATA", "probe"));
        }
        if identity != crate::nikon_he::Identity::read(&file)? {
            return Err(err("INVALID_INPUT", "probe"));
        }
        Ok(Some(Self {
            camera: CameraRaw {
                he: None,
                development,
                format,
                input_bytes: identity.bytes,
                jpeg_blocks: Vec::new(),
                decoder_extra_bytes: 0,
                source_ifd_offset: 0,
                arw1_offset: None,
                arw6: None,
            },
            plan,
            file,
            source,
            identity,
            black,
            white,
        }))
    }
    pub(crate) fn probe(&self) -> Probe {
        let mut p = self.camera.probe();
        p.variant = self.plan.variant;
        p
    }
    pub(crate) fn admit(&self, b: &Budget) -> Result<Admission> {
        let memory = b
            .memory_budget_mi_b
            .checked_mul(MIB)
            .filter(|n| *n >= BASE && *n <= CAMERA_RAW_MAX_MEMORY_MIB * MIB)
            .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
        let d = &self.camera.development;
        let decode_peak = BASE
            .checked_add(self.identity.bytes)
            .and_then(|n| n.checked_add(self.plan.scratch_bytes))
            .and_then(|n| n.checked_add(d.height as u64 * 8 + d.width as u64 * 4))
            .ok_or_else(|| err("MEMORY_BUDGET", "admission"))?;
        let capacity = ((memory.min(128 * MIB) - BASE) / (d.active.w as u64 * 96))
            .saturating_sub((HALO * 2) as u64) as usize;
        let rows = b.raw_batch.camera_rows(capacity.min(128), capacity, 256);
        if rows < 8 || decode_peak > memory {
            return Err(err("MEMORY_BUDGET", "admission"));
        }
        let disk = self
            .probe()
            .output_bytes
            .checked_add((d.width * d.height * 2) as u64)
            .ok_or_else(|| err("DISK_BUDGET", "admission"))?;
        if disk > b.disk_budget_bytes.min(1024 * MIB) {
            return Err(err("DISK_BUDGET", "admission"));
        }
        crate::develop::admit_geometry(d, b, Admission {
            memory_budget_bytes: memory,
            estimated_peak_bytes: decode_peak
                .max(BASE + d.active.w as u64 * (rows + 2 * HALO) as u64 * 96),
            required_disk_bytes: disk,
            strip_rows: rows as u32,
        })
    }
    pub(crate) fn decode(
        self,
        output: &Path,
        b: &Budget,
        cancel: &AtomicBool,
    ) -> Result<(Probe, Admission)> {
        let check = || {
            if cancel.load(Ordering::Relaxed) {
                Err(err("CANCELLED", "decode"))
            } else {
                Ok(())
            }
        };
        check()?;
        let admission = self.admit(b)?;
        let d = &self.camera.development;
        if self.identity != crate::nikon_he::Identity::read(&self.file)? {
            return Err(err("INVALID_INPUT", "decode"));
        }
        let dir = output
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        let forward = Arc::new(AtomicBool::new(false));
        let scratch = Scratch::new(dir, b.disk_budget_bytes.min(1024 * MIB), forward.clone())
            .map_err(|e| sony_arw6::codec_error(e, cancel))?;
        let mosaic = scratch
            .reserve((d.width * d.height * 2) as u64)
            .map_err(|e| sony_arw6::codec_error(e, cancel))?;
        let mut next = vec![0usize; d.height];
        let mut io_failure = None;
        let result = bounded("decode", || {
            let decoder = rawler::get_decoder(&self.source)?;
            if decoder.bounded_bayer_plan(&self.source)?.as_ref() != Some(&self.plan) {
                return Err("RAW layout changed".into());
            }
            decoder.bounded_bayer_segments(
                &self.source,
                &mut |x, y, row| {
                    if cancel.load(Ordering::Relaxed) {
                        forward.store(true, Ordering::Relaxed);
                        return Err("Decode cancelled".into());
                    }
                    if y >= d.height
                        || x > d.width
                        || x != next[y]
                        || row.is_empty()
                        || x % 2 != 0
                        || row.len() % 2 != 0
                        || row.len() > d.width - x
                    {
                        return Err("Invalid sensor coverage".into());
                    }
                    let bytes: Vec<u8> = row.iter().flat_map(|v| v.to_le_bytes()).collect();
                    if let Err(e) = mosaic.write(((y * d.width + x) * 2) as u64, &bytes) {
                        io_failure = Some(sony_arw6::codec_error(e, cancel));
                        return Err("Sensor spool I/O failed".into());
                    }
                    next[y] += row.len();
                    Ok(())
                },
                &|| cancel.load(Ordering::Relaxed),
            )
        });
        check()?;
        if let Some(e) = io_failure {
            return Err(e);
        }
        result?;
        if next.iter().any(|x| *x != d.width)
            || self.identity != crate::nikon_he::Identity::read(&self.file)?
        {
            return Err(err("INVALID_INPUT", "decode"));
        }
        drop(next);
        drop(self.source);
        drop(self.file);
        let mut result = integer_spool::develop(
            &self.camera,
            output,
            &admission,
            cancel,
            &mosaic,
            d,
            self.black,
            self.white,
        )?;
        result.0.variant = self.plan.variant;
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    fn identity(bytes: &[u8]) -> Result<Option<&'static str>> {
        let p = std::env::temp_dir().join(format!(
            "filmroom-raw-identity-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let mut f = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&p)
            .unwrap();
        std::fs::remove_file(p).unwrap();
        f.write_all(bytes).unwrap();
        identify_tiff(&mut f, bytes, bytes.len() as u64)
    }
    fn tiff(magic: &[u8; 4], make: &[u8]) -> Vec<u8> {
        let mut b = magic.to_vec();
        b.extend(8u32.to_le_bytes());
        b.extend(1u16.to_le_bytes());
        b.extend(271u16.to_le_bytes());
        b.extend(2u16.to_le_bytes());
        b.extend((make.len() as u32).to_le_bytes());
        b.extend(26u32.to_le_bytes());
        b.extend([0u8; 4]);
        b.extend(make);
        b
    }
    #[test]
    fn format_hints_are_content_only() {
        assert_eq!(
            identity(&tiff(b"IIRO", b"OLYMPUS CORPORATION\0")).unwrap(),
            Some("ORF")
        );
        assert_eq!(
            identity(&tiff(b"IIRS", b"OM Digital Solutions\0")).unwrap(),
            Some("ORF")
        );
        assert_eq!(
            identity(&tiff(b"II*\0", b"PENTAX Corporation\0")).unwrap(),
            Some("PEF")
        );
        assert_eq!(
            identity(&tiff(b"II*\0", b"NIKON CORPORATION\0")).unwrap(),
            None
        );
        assert_eq!(
            identity(&tiff(b"II*\0", b"OLYMPUS CORPORATION\0")).unwrap(),
            None
        );
    }
    #[test]
    fn bounded_identity_rejects_bad_counts_and_external_make_extent() {
        let mut b = tiff(b"IIRO", b"OLYMPUS CORPORATION\0");
        b[8..10].copy_from_slice(&257u16.to_le_bytes());
        assert!(identity(&b).is_err());
        let mut b = tiff(b"IIRO", b"OLYMPUS CORPORATION\0");
        b[18..22].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(identity(&b).is_err());
        assert!(!cr3_magic(b"\0\0\0\x20ftypheic"));
        assert!(cr3_magic(b"\0\0\0\x20ftypcrx "));
    }
}
