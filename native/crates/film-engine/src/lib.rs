//! GPL-3.0 native adapter over the complete Spektrafilm pipeline.
//! This initial API is for measured synthetic reference validation.
pub const PYTHON_REFERENCE: &str = "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc";
pub const RUST_REFERENCE: &str = "9dd59b0380194b93686aaa230a8bb9680aa270a4";
pub mod mobile;
mod jni_bridge;


pub mod tiled;

pub mod disk_spatial;
pub mod streamed;
pub mod checkpoint;
pub mod negative_preview;
pub mod scan_preview;
pub mod streamed_progress;

pub mod disk_diffusion;

pub mod profile_catalog;

pub mod adreno;

pub mod streamed_global;

#[cfg(feature = "precision-f64")]
pub mod detail;

pub mod export_execution;

// Exact pre-preview source snapshot, compiled only in the host unit-test oracle.
#[cfg(test)]
mod test_baseline_streamed16;
