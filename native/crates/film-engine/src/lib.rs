//! GPL-3.0 native adapter over the complete Spektrafilm pipeline.
//! This initial API is for measured synthetic reference validation.
pub const PYTHON_REFERENCE: &str = "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc";
pub const RUST_REFERENCE: &str = "9dd59b0380194b93686aaa230a8bb9680aa270a4";
pub mod mobile;
mod preview_stage_cache;
mod jni_bridge;
#[cfg(feature="resource-audit")]
mod resource_probe;


pub mod tiled;

pub mod disk_spatial;
pub mod streamed;
pub mod checkpoint;
pub mod negative_preview;
#[cfg(feature = "adreno-gpu")]
mod early_negative_preview;
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

#[cfg(feature="adreno-gpu")]
mod gpu_export;
#[cfg(feature="adreno-gpu")]
mod export_reuse;
#[cfg(feature="adreno-gpu")]
mod film_log_reuse;
#[cfg(feature="adreno-gpu")]
mod cache_root_owner;

// Reuse the identical integration sources in the library harness so focused
// GPU contract checks do not compile unrelated command-line tools.
#[cfg(test)] extern crate self as film_engine;
#[cfg(test)] mod gpu_app_smoke_tests;
#[cfg(test)] #[path="../tests/gpu_banded_chain.rs"] mod gpu_banded_chain_tests;
#[cfg(test)] #[path="../tests/gpu_grain_sublayers.rs"] mod gpu_grain_sublayers_tests;
#[cfg(test)] #[path="../tests/gpu_point_shaders.rs"] mod gpu_point_shaders_tests;
#[cfg(test)] #[path="../tests/gpu_resident_chain.rs"] mod gpu_resident_chain_tests;
