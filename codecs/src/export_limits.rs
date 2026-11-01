//! Export-only admission. Import and RAW MUST keep crate::MAX_PIXELS = 52_000_000.
//! 84 MP is bounded for a <=52 MP photograph plus an approved geometry envelope,
//! NOT permission to grow the shared importer limit or allocate a full-image buffer.
pub const MAX_EXPORT_PIXELS: u64 = 84_000_000;
pub const MAX_EXPORT_EDGE: u32 = 32_768;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ExportAdmission {
    pub pixels: u64,
    pub input_bytes: u64,
    pub estimated_peak_bytes: u64,
    pub required_disk_bytes: u64,
}

pub fn plan(width: u32, height: u32, jpeg: bool) -> Option<ExportAdmission> {
    if width == 0 || height == 0 || width > MAX_EXPORT_EDGE || height > MAX_EXPORT_EDGE {
        return None;
    }
    let pixels = u64::from(width).checked_mul(u64::from(height))?;
    if pixels > MAX_EXPORT_PIXELS { return None; }
    let input_bytes = pixels.checked_mul(12)?;
    let estimated_peak_bytes = u64::from(width).checked_mul(1024)?.checked_add(32 * 1024 * 1024)?;
    let required_disk_bytes = pixels.checked_mul(if jpeg { 12 } else { 6 })?
        .checked_add(if jpeg { 4 * 1024 * 1024 } else { 2 * 1024 * 1024 })?;
    Some(ExportAdmission { pixels, input_bytes, estimated_peak_bytes, required_disk_bytes })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn exact_boundary_and_portrait() {
        assert_eq!(plan(12000, 7000, true).unwrap().pixels, MAX_EXPORT_PIXELS);
        assert_eq!(plan(7000, 12000, false).unwrap().pixels, MAX_EXPORT_PIXELS);
        assert!(plan(12000, 7001, true).is_none());
        assert!(plan(7001, 12000, false).is_none());
    }
    #[test] fn edge_zero_overflow_and_skinny_images() {
        for (w,h) in [(0,1),(1,0),(u32::MAX,u32::MAX),(32769,1),(1,32769),(32768,32768)] {
            assert!(plan(w,h,true).is_none());
        }
        assert_eq!(plan(32768,1,true).unwrap().pixels,32768);
        assert_eq!(plan(1,32768,false).unwrap().pixels,32768);
    }
    #[test] fn bytes_and_memory_are_checked_and_row_bounded() {
        let p=plan(12000,7000,true).unwrap();
        assert_eq!(p.input_bytes,1_008_000_000);
        assert_eq!(p.required_disk_bytes,1_012_194_304);
        assert_eq!(p.estimated_peak_bytes,45_842_432);
        assert_eq!(plan(12000,1,true).unwrap().estimated_peak_bytes,p.estimated_peak_bytes);
        assert_eq!(plan(7000,12000,false).unwrap().required_disk_bytes,506_097_152);
        assert!(plan(32768,2000,true).unwrap().estimated_peak_bytes<=128*1024*1024);
    }
}
