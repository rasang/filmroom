//! File-backed initial-density / DIR with optional original exposure-log prefix.
//! Full image geometry and bounded GPU allocation are separate admission domains.
//! Production routing is deliberately pending; no resident limit is weakened.
use crate::research_dir::{DirPlan, R};
use crate::research_spatial::{self as spatial, FIT, MIB};
use crate::research_streaming_spatial as stream;
use serde::{Deserialize, Serialize};

#[cfg(feature = "gpu-diffusion-research")]
mod gpu;
#[cfg(feature = "gpu-diffusion-research")]
pub(crate) use gpu::StreamingDir;
#[cfg(test)]
mod tests;

pub const LAYOUT: &str = "fullLineDirV1";
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StreamDirPlan {
    schema_version: u32,
    layout: String,
    inner: DirPlan,
    log_correction: Option<f64>,
}
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamDirMemory {
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
    pub pipeline_count: u32,
}
impl StreamDirPlan {
    pub fn new(
        pipeline: &spektrafilm_core::pipeline::Pipeline,
        pitch: f32,
        width: u32,
        height: u32,
        log_correction: Option<f64>,
    ) -> R<Self> {
        let mut inner = DirPlan::new(pipeline, pitch, 1, 1)?;
        inner.width = width;
        inner.height = height;
        Self::from_plan(inner, log_correction)
    }
    pub fn from_plan(inner: DirPlan, log_correction: Option<f64>) -> R<Self> {
        let plan = Self {
            schema_version: 1,
            layout: LAYOUT.into(),
            inner,
            log_correction,
        };
        plan.validate()?;
        Ok(plan)
    }
    pub fn parameters(&self) -> &DirPlan {
        &self.inner
    }
    pub fn log_correction(&self) -> Option<f64> {
        self.log_correction
    }
    pub fn validate(&self) -> R<()> {
        let p = &self.inner;
        if self.schema_version != 1
            || self.layout != LAYOUT
            || p.width == 0
            || p.height == 0
            || p.width > 40000
            || p.height > 40000
        {
            return Err("INVALID_DIR_STREAM_PLAN".into());
        }
        // Preserve all original curve, reciprocal, rate, filter and domain
        // checks. Only the old whole-plane allocation dimensions are separated.
        let mut semantic = p.clone();
        semantic.width = 1;
        semantic.height = 1;
        semantic.validate()?;
        if let Some(correction) = self.log_correction {
            crate::research_log_dir::log_prefix::validate_correction(1, 1, correction)?;
        }
        Ok(())
    }
    pub fn numerical_preflight(&self, c: &mut impl FnMut() -> bool) -> R<()> {
        self.validate()?;
        spatial::check(c)?;
        let p = &self.inner;
        if p.dir_active && p.core_sigma > 0. {
            spatial::numerical_preflight_for_sigmas(
                p.width,
                p.height,
                std::iter::once(p.core_sigma).chain(
                    FIT.into_iter()
                        .map(|(_, ratio)| (ratio * p.tail_decay as f64) as f32),
                ),
                c,
            )?;
        }
        Ok(())
    }
    pub(crate) fn families(&self) -> R<Vec<(Vec<u32>, String)>> {
        self.validate()?;
        let p = &self.inner;
        let mut families =
            spatial::families::shaders_for_dir(p.dir_active, p.core_sigma, p.tail_decay)?;
        if self.log_correction.is_some() {
            families.push((vec![16, 17], crate::research_log_dir::log_prefix::shader()?));
        }
        Ok(families)
    }
    pub fn memory(&self) -> R<StreamDirMemory> {
        self.validate()?;
        // Five 4MiB planes, 4MiB probe + map, metadata, and one 4MiB transient
        // upload. Every transfer is fenced before the next staging allocation.
        let device_bytes = stream::DEVICE_BYTES + stream::BATCH_BYTES;
        let host_bytes = stream::HOST_BYTES
            + if self.log_correction.is_some() {
                MIB
            } else {
                0
            };
        let pipeline_count = self.families()?.len() as u32;
        let driver_allowance_bytes = 64 * MIB + u64::from(pipeline_count) * 8 * MIB;
        let helper_allowance_bytes = 64 * MIB;
        let combined_peak_bytes =
            device_bytes + host_bytes + driver_allowance_bytes + helper_allowance_bytes;
        if combined_peak_bytes > spatial::MEMORY_CAP {
            return Err("GPU_DIR_STREAM_MEMORY_LIMIT".into());
        }
        Ok(StreamDirMemory {
            device_bytes,
            host_bytes,
            driver_allowance_bytes,
            helper_allowance_bytes,
            combined_peak_bytes,
            pipeline_count,
            // Three immutable f64 sources, three f64 output candidates, at most
            // five concurrent f32 planes, plus calibration/manifest reserve.
            disk_bytes: u64::from(self.inner.width) * u64::from(self.inner.height) * 68 + 2 * MIB,
        })
    }
}

pub mod full_stage;
