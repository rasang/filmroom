//! Independent bounded coordinate-export candidate. Not publicly admitted.
//! Original resident/preview source and routing are deliberately untouched.
pub(crate) mod streaming_contract;
#[cfg(feature = "gpu-diffusion-research")]
pub(crate) mod streaming_sampler;
pub(crate) mod streaming_shader;

pub mod full_stage;

use crate::research_grain::{GrainPlan, guarded_graph as graph};
use crate::research_spatial::{self as spatial, R};

/// New admission domain, not a resident plan with its memory fields altered.
/// Private inner plan prevents callers mutating geometry after validation.
#[derive(Clone, Debug)]
pub struct StreamGrainPlan {
    inner: GrainPlan,
}

#[derive(Clone, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamGrainMemory {
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
impl StreamGrainPlan {
    pub fn from_plan(plan: GrainPlan) -> R<Self> {
        let result = Self { inner: plan };
        result.validate()?;
        Ok(result)
    }
    pub fn new(
        pipe: &spektrafilm_core::pipeline::Pipeline,
        pitch: f32,
        width: u32,
        height: u32,
    ) -> R<Self> {
        let mut inner = GrainPlan::new_guarded(pipe, pitch, 1, 1)?;
        inner.width = width;
        inner.height = height;
        Self::from_plan(inner)
    }
    pub fn validate(&self) -> R<()> {
        let p = &self.inner;
        if p.width == 0 || p.height == 0 || p.width > 40000 || p.height > 40000 {
            return Err("GPU_GRAIN_STREAM_GEOMETRY".into());
        }
        if p.rng_mode != graph::RngMode::CoordinateSplitMix64 {
            return Err("INVALID_GRAIN_RNG_MODE".into());
        }
        // All original rate, profile, curve, bit-lane, table, filter and mode
        // validation is retained; only its allocation geometry is separate.
        let mut semantic = p.clone();
        semantic.width = 1;
        semantic.height = 1;
        graph::validate(&semantic, p.rng_mode)
    }
    pub fn numerical_preflight(&self, cancel: &mut impl FnMut() -> bool) -> R<()> {
        self.validate()?;
        spatial::numerical_preflight_for_sigmas(
            self.inner.width,
            self.inner.height,
            self.inner.sigmas(),
            cancel,
        )
    }
    pub fn parameters(&self) -> &GrainPlan {
        &self.inner
    }
    pub fn memory(&self) -> R<StreamGrainMemory> {
        self.validate()?;
        let device_bytes = crate::research_streaming_spatial::DEVICE_BYTES
            + graph::scratch_bytes(graph::RngMode::CoordinateSplitMix64)?
            + graph::BATCH_RESERVATION_BYTES;
        let host_bytes =
            crate::research_streaming_spatial::HOST_BYTES + graph::CODE_HOST_RESERVATION_BYTES;
        let driver_allowance_bytes = 64 * spatial::MIB + graph::PIPELINE_RESERVATION_BYTES;
        let helper_allowance_bytes = 64 * spatial::MIB;
        let combined_peak_bytes =
            device_bytes + host_bytes + driver_allowance_bytes + helper_allowance_bytes;
        if combined_peak_bytes > spatial::MEMORY_CAP {
            return Err("GPU_GRAIN_MEMORY_LIMIT".into());
        }
        Ok(StreamGrainMemory {
            device_bytes,
            host_bytes,
            driver_allowance_bytes,
            helper_allowance_bytes,
            combined_peak_bytes,
            disk_bytes: 68 * self.inner.width as u64 * self.inner.height as u64 + 65536,
        })
    }
}
