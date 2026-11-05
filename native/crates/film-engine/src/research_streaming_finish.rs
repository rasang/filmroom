//! Independent full-resolution scanner/fused-finish streaming candidate.
//! Production admission remains closed. Allocation geometry is separate from
//! the original scientific plan; no image arithmetic runs on the host.
use crate::research_finish_tail::{TailOptions, TailPlan};
use crate::research_spatial::{self as spatial, R};
use serde::{Deserialize, Serialize};
use spektrafilm_core::pipeline::GpuScanTailDescriptor;

#[cfg(feature = "gpu-diffusion-research")]
pub(crate) mod gpu;
#[cfg(test)]
mod tests;

pub const LAYOUT: &str = "fullLineFileBackedFinishV1";
const METADATA_DISK_BYTES: u64 = 3 * spatial::MIB;

/// The inner TailPlan always has genuine 1x1 resident allocation metadata.
/// Real dimensions are immutable and used for every complete-line preflight.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamFinishPlan {
    schema_version: u32,
    layout: String,
    tail: TailPlan,
    width: u32,
    height: u32,
    prefix_table_scalars: Option<u64>,
    external_glare: bool,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct WirePlan {
    schema_version: u32,
    layout: String,
    tail: TailPlan,
    width: u32,
    height: u32,
    prefix_table_scalars: Option<u64>,
    external_glare: bool,
}
impl<'de> Deserialize<'de> for StreamFinishPlan {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let wire = WirePlan::deserialize(deserializer)?;
        if wire.schema_version != 1 || wire.layout != LAYOUT {
            return Err(serde::de::Error::custom("GPU_FINISH_STREAM_PLAN"));
        }
        Self::from_semantic_plan(
            wire.tail,
            wire.width,
            wire.height,
            wire.prefix_table_scalars,
            wire.external_glare,
        )
        .map_err(serde::de::Error::custom)
    }
}
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamFinishMemory {
    pub schema_version: u32,
    pub device_bytes: u64,
    pub resident_device_bytes: u64,
    pub upload_staging_bytes: u64,
    pub prefix_device_table_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub source_snapshot_bytes: u64,
    pub imported_glare_bytes: u64,
    pub output_bytes: u64,
    pub spatial_spool_peak_bytes: u64,
    pub output_and_spool_peak_bytes: u64,
    pub prefix_disk_table_bytes: u64,
    pub metadata_disk_bytes: u64,
    pub disk_bytes: u64,
}
impl StreamFinishPlan {
    pub fn new(
        descriptor: &GpuScanTailDescriptor<'_>,
        width: u32,
        height: u32,
        options: TailOptions,
        prefix_table_scalars: Option<u64>,
        external_glare: bool,
    ) -> R<Self> {
        Self::from_semantic_plan(
            TailPlan::new(descriptor, 1, 1, options)?,
            width,
            height,
            prefix_table_scalars,
            external_glare,
        )
    }
    /// Reconstruct an immutable typed plan from calibrated packet values.
    /// The TailPlan must be a genuinely valid 1x1 semantic plan, never an
    /// unchecked full-size plan with its admission fields overwritten.
    pub fn from_semantic_plan(
        tail: TailPlan,
        width: u32,
        height: u32,
        prefix_table_scalars: Option<u64>,
        external_glare: bool,
    ) -> R<Self> {
        let p = Self {
            schema_version: 1,
            layout: LAYOUT.into(),
            tail,
            width,
            height,
            prefix_table_scalars,
            external_glare,
        };
        p.validate()?;
        p.memory()?;
        Ok(p)
    }
    pub fn validate(&self) -> R<()> {
        self.tail.validate()?;
        if self.schema_version != 1
            || self.layout != LAYOUT
            || self.width == 0
            || self.height == 0
            || self.width > 40000
            || self.height > 40000
            || self.tail.width != 1
            || self.tail.height != 1
            || self
                .prefix_table_scalars
                .is_some_and(|n| n == 0 || n > u32::MAX as u64 - 64)
            || (self.external_glare
                && (self.tail.options.glare.is_none() || self.prefix_table_scalars.is_some()))
        {
            return Err("GPU_FINISH_STREAM_PLAN".into());
        }
        Ok(())
    }
    pub fn width(&self) -> u32 {
        self.width
    }
    pub fn height(&self) -> u32 {
        self.height
    }
    /// Scientific calibration only. Its allocation dimensions are 1x1; packet
    /// and output dimensions must come from width()/height(), never this value.
    pub fn parameters(&self) -> &TailPlan {
        &self.tail
    }
    pub fn prefix_table_scalars(&self) -> Option<u64> {
        self.prefix_table_scalars
    }
    pub fn external_glare(&self) -> bool {
        self.external_glare
    }
    pub fn numerical_preflight(&self, c: &mut impl FnMut() -> bool) -> R<()> {
        self.validate()?;
        spatial::numerical_preflight_for_sigmas(self.width, self.height, self.sigmas(), c)
    }
    fn sigmas(&self) -> [f32; 3] {
        let o = &self.tail.options;
        [
            o.lens_sigma,
            if o.sharp_amount > 0. {
                o.sharp_radius
            } else {
                0.
            },
            o.glare.as_ref().map_or(0., |g| g.blur),
        ]
    }
    pub fn sample_count(&self) -> u64 {
        let n = self.width as u64 * self.height as u64;
        let chunk = crate::research_finish_tail::CHUNK_PIXELS as u64;
        let chunks = n.div_ceil(chunk);
        let point = 3 * (8 * (chunks - 1) + (n - (chunks - 1) * chunk).min(8));
        self.tail.pass_count() * (self.width as u64 + self.height as u64)
            + point
                * if self.prefix_table_scalars.is_some() {
                    2
                } else {
                    1
                }
    }
    pub fn memory(&self) -> R<StreamFinishMemory> {
        self.validate()?;
        let b = crate::research_streaming_spatial::BATCH_BYTES;
        let s = self.prefix_table_scalars.unwrap_or(0);
        let tables = self.prefix_table_scalars.map_or(0, |n| 4 * (64 + n));
        // Original Runner: five scalar planes, aux/probe, readback and fixed
        // metadata. Tail/gamut/glare tables and random-word/candidate windows
        // are disjoint regions INSIDE aux, not extra uncharged allocations.
        let resident_device_bytes = 7 * b + 65536 + tables;
        // Every upload is fenced before the next. Account a full plane transfer
        // plus immutable prefix creation staging, without assuming zero-copy.
        let upload_staging_bytes = b + tables;
        let device_bytes = resident_device_bytes + upload_staging_bytes;
        // Bounded line gather/readback/transposition and witnesses fit 64 MiB.
        // Prefix load may retain f64 tables + flat32 + packed32 simultaneously.
        let host_bytes = 64 * spatial::MIB
            + 16 * s
            + if self.prefix_table_scalars.is_some() {
                8 * spatial::MIB + 256
            } else {
                0
            };
        // Driver/compiler amounts are admission allowances, not measured caps.
        let driver_allowance_bytes =
            self.tail.expected_driver_allowance() + spatial::PIPELINE_RESERVATION_BYTES;
        let helper_allowance_bytes = 64 * spatial::MIB;
        let combined_peak_bytes =
            device_bytes + host_bytes + driver_allowance_bytes + helper_allowance_bytes;
        if combined_peak_bytes > spatial::MEMORY_CAP {
            return Err("GPU_FINISH_STREAM_MEMORY_LIMIT".into());
        }
        let n = self.width as u64 * self.height as u64;
        let source_snapshot_bytes = 24 * n; // interleaved logXYZ or three prefix f64 planes
        let imported_glare_bytes = if self.external_glare { 8 * n } else { 0 };
        let output_bytes = 12 * n; // original interleaved f32 contract
        let spatial_spool_peak_bytes = 20 * n; // three channels + two blur/point temporaries
        let output_and_spool_peak_bytes = 12 * n + output_bytes;
        let prefix_disk_table_bytes = 8 * s;
        let disk_bytes = source_snapshot_bytes
            + imported_glare_bytes
            + spatial_spool_peak_bytes.max(output_and_spool_peak_bytes)
            + prefix_disk_table_bytes
            + METADATA_DISK_BYTES;
        Ok(StreamFinishMemory {
            schema_version: 1,
            device_bytes,
            resident_device_bytes,
            upload_staging_bytes,
            prefix_device_table_bytes: tables,
            host_bytes,
            driver_allowance_bytes,
            helper_allowance_bytes,
            combined_peak_bytes,
            source_snapshot_bytes,
            imported_glare_bytes,
            output_bytes,
            spatial_spool_peak_bytes,
            output_and_spool_peak_bytes,
            prefix_disk_table_bytes,
            metadata_disk_bytes: METADATA_DISK_BYTES,
            disk_bytes,
        })
    }
}

pub mod full_stage;
