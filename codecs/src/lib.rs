//! Entirely local, memory-admitted photo codecs. No network code or telemetry.
pub mod develop;
pub mod dng;
mod compat;
pub mod error;
pub mod tiff;

mod jni_bridge;

pub mod export;
pub mod export_limits;

pub const MAX_PIXELS: u64 = 52_000_000;
pub fn validate_dimensions(width: u32, height: u32) -> error::Result<u64> {
    let n = u64::from(width)
        .checked_mul(u64::from(height))
        .ok_or_else(|| error::err("PIXEL_LIMIT", "admission"))?;
    if width == 0 || height == 0 {
        return Err(error::err("INVALID_INPUT", "admission"));
    }
    if width > 32768 || height > 32768 {
        return Err(error::err("EDGE_LIMIT", "admission"));
    }
    if n > MAX_PIXELS {
        return Err(error::err("PIXEL_LIMIT", "admission"));
    }
    Ok(n)
}
#[cfg(test)]
mod dimension_tests {
    #[test]
    fn marketed_50mp_and_exact_guard() {
        assert_eq!(super::validate_dimensions(0, 8).unwrap_err().code, "INVALID_INPUT");
        assert_eq!(super::validate_dimensions(32769, 8).unwrap_err().code, "EDGE_LIMIT");
        assert_eq!(super::validate_dimensions(8, 32769).unwrap_err().code, "EDGE_LIMIT");
        assert_eq!(super::validate_dimensions(32768, 8).unwrap(), 262144);
        assert_eq!(super::validate_dimensions(8192, 6144).unwrap(), 50_331_648);
        assert_eq!(super::validate_dimensions(10000, 5200).unwrap(), 52_000_000);
        assert_eq!(
            super::validate_dimensions(5311, 9791).unwrap_err().code,
            "PIXEL_LIMIT"
        );
        assert!(super::validate_dimensions(u32::MAX, u32::MAX).is_err());
    }
}

pub mod opcodes;
pub mod dng_bad_pixels;
pub mod dng_warp;
pub mod embedded_lens;
pub mod lensfun;
pub mod lensfun_geometry;
mod lensfun_metadata;
pub mod dng_cfa_color;

mod lossless;

pub mod output_color;

mod technical;

pub mod gainmap;

pub mod cfa_gainmap;

pub mod camera_raw;
mod nikon_he;
mod he_spool;
pub mod raw;

mod snef_rows;
mod nikon_snef;

pub mod raw_batch;

pub mod linear_jpeg;

pub mod profile_gain_table;

pub mod dng_color_spec;

pub mod profile_develop;

mod jxl;
