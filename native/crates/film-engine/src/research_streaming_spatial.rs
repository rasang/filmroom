//! File-backed, full-resolution experimental GPU optics.
//!
//! This module is deliberately not wired into JNI or Android admission yet.
//! A complete row or column is one recurrence, never a finite-halo IIR tile.
//! Only file I/O, byte transposition and the existing numerical guard run on CPU.
//! Arithmetic uses the existing guarded resident GPU shader without modification.
pub mod full_stage;

use crate::research_spatial::{self as spatial, ChannelPlan, FilmOpticsPlan, Filter, R, check};
use serde::{Deserialize, Serialize};
use spektrafilm_core::params::RuntimeParams;

pub(crate) mod files;
#[cfg(feature = "gpu-diffusion-research")]
mod gpu;
#[cfg(test)]
mod tests;
#[cfg(test)]
#[path = "research_streaming_default_gates.rs"]
mod default_gates;

pub const LAYOUT: &str = "fullLineFileBackedV1";
pub const BATCH_BYTES: u64 = 4 * spatial::MIB;
pub const BATCH_SAMPLES: u32 = (BATCH_BYTES / 4) as u32;
pub const DEVICE_BYTES: u64 = 7 * BATCH_BYTES + 65536;
pub const HOST_BYTES: u64 = 64 * spatial::MIB;
pub const DRIVER_BYTES: u64 = 64 * spatial::MIB + spatial::PIPELINE_RESERVATION_BYTES;
pub const HELPER_BYTES: u64 = 64 * spatial::MIB;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StreamPlan {
    pub schema_version: u32,
    pub layout: String,
    pub width: u32,
    pub height: u32,
    pub channels: [ChannelPlan; 3],
    pub batch_bytes: u64,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    /// Three immutable f64 sources, three f64 candidates, at most five f32 temporaries.
    pub disk_bytes: u64,
}

impl StreamPlan {
    pub fn new(params: &RuntimeParams, pitch: f32, width: u32, height: u32) -> R<Self> {
        // Reuse semantic construction on a genuine 1x1 reference plan. Do not
        // modify or weaken the resident allocation admission for the real image.
        let channels = FilmOpticsPlan::new(params, pitch, 1, 1)?.channels;
        Self::from_channels(width, height, channels)
    }
    pub fn from_channels(width: u32, height: u32, channels: [ChannelPlan; 3]) -> R<Self> {
        if width == 0 || height == 0 || width > 40000 || height > 40000 {
            return Err("GPU_STREAM_MEMORY_LIMIT".into());
        }
        let p = Self {
            schema_version: 1,
            layout: LAYOUT.into(),
            width,
            height,
            channels,
            batch_bytes: BATCH_BYTES,
            device_bytes: DEVICE_BYTES,
            host_bytes: HOST_BYTES,
            driver_allowance_bytes: DRIVER_BYTES,
            helper_allowance_bytes: HELPER_BYTES,
            combined_peak_bytes: DEVICE_BYTES + HOST_BYTES + DRIVER_BYTES + HELPER_BYTES,
            disk_bytes: width as u64 * height as u64 * 68 + 65536,
        };
        p.validate()?;
        Ok(p)
    }
    pub fn validate(&self) -> R<()> {
        if self.schema_version != 1
            || self.layout != LAYOUT
            || self.width == 0
            || self.height == 0
            || self.width > 40000
            || self.height > 40000
            || self.batch_bytes != BATCH_BYTES
            || self.device_bytes != DEVICE_BYTES
            || self.host_bytes != HOST_BYTES
            || self.driver_allowance_bytes != DRIVER_BYTES
            || self.helper_allowance_bytes != HELPER_BYTES
            || self.combined_peak_bytes != DEVICE_BYTES + HOST_BYTES + DRIVER_BYTES + HELPER_BYTES
            || self.combined_peak_bytes > spatial::MEMORY_CAP
            || self.disk_bytes != self.width as u64 * self.height as u64 * 68 + 65536
        {
            return Err("GPU_STREAM_MEMORY_LIMIT".into());
        }
        self.semantic_plan()?.validate()
    }
    pub(crate) fn semantic_plan(&self) -> R<FilmOpticsPlan> {
        let mut p = FilmOpticsPlan::new(&RuntimeParams::default(), 1., 1, 1)?;
        p.channels = self.channels.clone();
        Ok(p)
    }
    pub fn numerical_preflight(&self, cancelled: &mut impl FnMut() -> bool) -> R<()> {
        self.validate()?;
        spatial::numerical_preflight_for_sigmas(
            self.width,
            self.height,
            self.channels.iter().flat_map(ChannelPlan::sigmas),
            cancelled,
        )
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Axis {
    Rows,
    Columns,
}

/// A batch contains only complete lines. A long line may never be split.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct LineBatch {
    first: u32,
    count: u32,
    length: u32,
}
fn batches(width: u32, height: u32, axis: Axis, capacity: u32) -> R<Vec<LineBatch>> {
    let (length, lines) = match axis {
        Axis::Rows => (width, height),
        Axis::Columns => (height, width),
    };
    if length == 0 || lines == 0 || capacity < length || capacity > BATCH_SAMPLES {
        return Err("GPU_STREAM_LINE_LIMIT".into());
    }
    let per_batch = capacity / length;
    Ok((0..lines)
        .step_by(per_batch as usize)
        .map(|first| LineBatch {
            first,
            count: (lines - first).min(per_batch),
            length,
        })
        .collect())
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamMetrics {
    pub adapter: String,
    pub vendor: u32,
    pub layout: String,
    pub full_resolution: bool,
    pub device_bytes: u64,
    pub upload_bytes: u64,
    pub readback_bytes: u64,
    pub upload_ms: f64,
    pub compute_readback_ms: f64,
    pub gather_scatter_ms: f64,
    pub dispatches: u64,
    pub pass_count: u64,
    pub sample_count: u64,
    pub sample_max_abs: f64,
    pub sample_sum_squared: f64,
    pub elapsed_ms: f64,
}

/// Private abstraction. The only production implementation is Vulkan; the CPU
/// implementation exists under cfg(test), never as an automatic fallback.
pub(crate) trait Compute {
    fn execute(
        &mut self,
        inputs: &[(u32, &[f32])],
        p: [u32; 40],
        output: u32,
        c: &mut impl FnMut() -> bool,
    ) -> R<Vec<f32>>;
    fn metrics(&mut self) -> &mut StreamMetrics;
}

#[cfg(feature = "gpu-diffusion-research")]
pub fn run_files(
    plan: &StreamPlan,
    sources: [&std::path::Path; 3],
    outputs: [&std::path::Path; 3],
    scratch: &std::path::Path,
    cancelled: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
) -> R<StreamMetrics> {
    let started = std::time::Instant::now();
    plan.numerical_preflight(cancelled)?;
    files::validate_paths(plan, sources, outputs, scratch)?;
    check(cancelled)?;
    let mut gpu = gpu::Gpu::new(plan, false)?;
    let result = files::run(
        plan,
        sources,
        outputs,
        scratch,
        BATCH_SAMPLES,
        &mut gpu,
        cancelled,
        progress,
    );
    if let Err(error) = result {
        gpu.fail();
        return Err(error);
    }
    let mut metrics = gpu.metrics().clone();
    metrics.elapsed_ms = started.elapsed().as_secs_f64() * 1000.;
    Ok(metrics)
}

/// Kept private until independent grain source/device/admission gates pass.
#[cfg(feature = "gpu-diffusion-research")]
pub(crate) fn run_grain_files(
    sources: [&std::path::Path; 3],
    outputs: [&std::path::Path; 3],
    scratch: &std::path::Path,
    sampler: &mut crate::research_streaming_grain::streaming_sampler::StreamingSampler,
    cancelled: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
) -> R<serde_json::Value> {
    // Scientific identity comes only from the admitted typed owner.
    let plan = sampler.plan().clone();
    // The immutable owner's constructor already preflighted these exact full
    // line lengths. All subsequent cancellation flows through the poison guard.
    let result = files::run_grain(
        &plan,
        sources,
        outputs,
        scratch,
        BATCH_SAMPLES,
        sampler,
        cancelled,
        progress,
    );
    if let Err(error) = result {
        sampler.poison();
        return Err(error);
    }
    sampler.report()
}
