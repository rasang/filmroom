//! Exact two-pass, full-image highlight boost with bounded GPU storage.
//! The resident GlobalPlan is not weakened. This has a separate typed contract,
//! operation and layout; production admission remains closed pending full gates.
use super::{GlobalKind, GlobalPlan, R};
use crate::research_spatial::{FilmOpticsPlan, MIB, MEMORY_CAP, TRANSFER_BYTES};
use serde::{Deserialize, Serialize};

#[path = "streaming_stage.rs"]
pub mod full_stage;
#[cfg(feature = "gpu-diffusion-research")]
#[path = "streaming_gpu.rs"]
mod gpu;
#[cfg(test)]
#[path = "streaming_tests.rs"]
mod tests;

pub const OPERATION: &str = "fullStreamingHighlightBoost";
pub const LAYOUT: &str = "twoPassFileBackedHighlightV1";
pub const BACKEND: &str = "gpu_global_highlight_stream_f32_v1";
pub const BATCH_BYTES: u64 = 4 * MIB;
pub const BATCH_SAMPLES: u32 = (BATCH_BYTES / 4) as u32;
pub const REDUCTION_SAMPLES: u32 = 1024;
pub const FAMILY_COUNT: u32 = 5;
pub const METADATA_BYTES: u64 = 65536;
pub const DISK_METADATA_BYTES: u64 = 2 * MIB;

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamHighlightPlan {
    schema_version: u32,
    layout: String,
    width: u32,
    height: u32,
    // Genuine 1x1 original semantic plan, including genuine 1x1 budget fields.
    semantic: GlobalPlan,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct WirePlan {
    schema_version: u32,
    layout: String,
    width: u32,
    height: u32,
    semantic: GlobalPlan,
}
impl<'de> Deserialize<'de> for StreamHighlightPlan {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let wire = WirePlan::deserialize(deserializer)?;
        if wire.schema_version != 1 || wire.layout != LAYOUT {
            return Err(serde::de::Error::custom("INVALID_GLOBAL_STREAM_PLAN"));
        }
        Self::from_semantic_plan(wire.semantic, wire.width, wire.height)
            .map_err(serde::de::Error::custom)
    }
}
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamHighlightMemory {
    pub schema_version: u32,
    pub resident_device_bytes: u64,
    pub upload_staging_bytes: u64,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub source_snapshot_bytes: u64,
    pub output_bytes: u64,
    pub metadata_disk_bytes: u64,
    pub disk_bytes: u64,
    pub pipeline_count: u32,
}
impl StreamHighlightPlan {
    pub fn new(width: u32, height: u32, boost_ev: f32, boost_range: f32, protect_ev: f32) -> R<Self> {
        Self::from_semantic_plan(GlobalPlan::highlight(1, 1, boost_ev, boost_range, protect_ev)?, width, height)
    }
    pub fn from_semantic_plan(semantic: GlobalPlan, width: u32, height: u32) -> R<Self> {
        let result = Self { schema_version: 1, layout: LAYOUT.into(), width, height, semantic };
        result.validate()?;
        result.memory()?;
        Ok(result)
    }
    pub fn validate(&self) -> R<()> {
        self.semantic.validate()?;
        if self.schema_version != 1 || self.layout != LAYOUT
            || self.width == 0 || self.height == 0 || self.width > 40000 || self.height > 40000
            || self.semantic.width != 1 || self.semantic.height != 1
            || !matches!(self.semantic.kind, GlobalKind::Highlight { .. })
            || BATCH_SAMPLES % REDUCTION_SAMPLES != 0
            || BATCH_SAMPLES % super::CHUNK as u32 != 0
            || BATCH_BYTES != TRANSFER_BYTES
        { return Err("INVALID_GLOBAL_STREAM_PLAN".into()); }
        Ok(())
    }
    pub fn width(&self) -> u32 { self.width }
    pub fn height(&self) -> u32 { self.height }
    pub fn parameters(&self) -> &GlobalPlan { &self.semantic }
    pub fn pixels(&self) -> u64 { u64::from(self.width) * u64::from(self.height) }
    pub fn memory(&self) -> R<StreamHighlightMemory> {
        self.validate()?;
        // All five existing planes, probe and map are fixed-size. The running
        // maximum + eight coefficient scalars occupy existing accum[0..9], and
        // at most 1024 partials occupy existing saved[0..1024]; no extra buffer.
        let resident_device_bytes = 5 * BATCH_BYTES + 2 * TRANSFER_BYTES + METADATA_BYTES;
        // upload_aux fences each queue.write_buffer before the next upload.
        // Reserve a full 4 MiB staging allocation, above the 256 KiB actual upload.
        let upload_staging_bytes = BATCH_BYTES;
        let device_bytes = resident_device_bytes + upload_staging_bytes;
        let host_bytes = 64 * MIB;
        let driver_allowance_bytes = 64 * MIB + u64::from(FAMILY_COUNT) * 8 * MIB;
        let helper_allowance_bytes = 64 * MIB;
        let combined_peak_bytes = device_bytes + host_bytes + driver_allowance_bytes + helper_allowance_bytes;
        if combined_peak_bytes > MEMORY_CAP { return Err("GPU_GLOBAL_STREAM_MEMORY_LIMIT".into()); }
        let source_snapshot_bytes = self.pixels() * 24;
        let output_bytes = self.pixels() * 24;
        Ok(StreamHighlightMemory { schema_version: 1, resident_device_bytes, upload_staging_bytes,
            device_bytes, host_bytes, driver_allowance_bytes, helper_allowance_bytes, combined_peak_bytes,
            source_snapshot_bytes, output_bytes, metadata_disk_bytes: DISK_METADATA_BYTES,
            disk_bytes: source_snapshot_bytes + output_bytes + DISK_METADATA_BYTES, pipeline_count: FAMILY_COUNT })
    }
    #[cfg(feature = "gpu-diffusion-research")]
    fn allocation_plan(&self) -> R<FilmOpticsPlan> {
        self.validate()?;
        // Valid independent allocation geometry, never overwrite an invalid
        // full-image resident plan. Linear chunk indexing uses these 4MiB planes.
        GlobalPlan::new(1024, 1024, self.semantic.kind.clone())?.spatial_plan()
    }
}

/// No per-image-sized batch schedule allocation. Every nonfinal chunk ends at
/// an original 1024-pixel reduction boundary; only the original final block is short.
fn chunks(pixels: u64, capacity: u32) -> R<impl Iterator<Item = (u64, u32)>> {
    if pixels == 0 || pixels > 40000u64 * 40000
        || capacity == 0 || capacity > BATCH_SAMPLES || capacity % REDUCTION_SAMPLES != 0
    { return Err("INVALID_GLOBAL_STREAM_CHUNK".into()); }
    Ok((0..pixels).step_by(capacity as usize).map(move |start| (start, (pixels - start).min(u64::from(capacity)) as u32)))
}

#[cfg(any(test, feature = "gpu-diffusion-research"))]
fn families() -> R<Vec<(Vec<u32>, String)>> {
    let semantic = GlobalKind::Highlight { boost_ev: 0., boost_range: 0., protect_ev: 0. };
    // Existing four highlight families and every original guard/curve/max block
    // are unchanged, as are all AE specialization sources.
    let mut families = super::specialized::shaders(&semantic)?;
    families.push((vec![17, 18, 19, 20], control_shader()?));
    if families.len() != FAMILY_COUNT as usize { return Err("GPU_GLOBAL_STREAM_SHADER_VERSION".into()); }
    Ok(families)
}
#[cfg(any(test, feature = "gpu-diffusion-research"))]
fn control_shader() -> R<String> {
    let marker = "@compute @workgroup_size(64)";
    if super::SHADER.matches(marker).count() != 1 { return Err("GPU_GLOBAL_STREAM_SHADER_VERSION".into()); }
    let declarations_and_original_helpers = super::SHADER.split_once(marker).ok_or("GPU_GLOBAL_STREAM_SHADER_VERSION")?.0;
    let reference = format!("{declarations_and_original_helpers}\n{}", include_str!("../shaders/research_global_stream_control.wgsl"));
    super::specialized::streaming_control_source(&reference)
        .map_err(|_| "GPU_GLOBAL_STREAM_SHADER_VERSION".into())
}
