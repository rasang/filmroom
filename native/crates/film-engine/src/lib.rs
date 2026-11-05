//! GPL-3.0 native adapter over the complete Spektrafilm pipeline.
//! This initial API is for measured synthetic reference validation.
pub const PYTHON_REFERENCE: &str = "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc";
pub const RUST_REFERENCE: &str = "9dd59b0380194b93686aaa230a8bb9680aa270a4";
pub mod mobile;
mod jni_bridge;
mod preview_display;
mod preview_display_test_jni;


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

pub mod export_optimizations;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_diffusion;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_spatial;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_streaming_spatial;

pub mod gpu_stage_staging;
pub(crate) mod gpu_phase_diagnostics;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_finish;
#[cfg(feature = "diffusion-research")]
pub mod research_finish_tail;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_exposure;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_dir;
#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_log_dir;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_grain;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_global;

#[cfg(feature="gpu-diffusion-research")]
mod science_jni;

pub(crate) mod preview_gpu;

pub(crate) mod gpu_retained;

#[cfg(feature = "gpu-diffusion-research")]
pub(crate) mod gpu_session;
#[cfg(any(test, feature = "gpu-diffusion-research"))]
pub(crate) mod gpu_session_contract;

#[cfg(feature = "gpu-diffusion-research")]
mod gpu_session_jni;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_streaming_grain;

#[cfg(any(test, feature = "diffusion-research"))]
pub mod research_streaming_dir;

#[cfg(feature = "diffusion-research")]
pub mod research_streaming_finish;

pub(crate) mod research_streaming_route;
#[cfg(feature = "diffusion-research")]
pub(crate) mod research_streaming_highlight;
