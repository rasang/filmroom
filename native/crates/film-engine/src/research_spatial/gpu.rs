//! Actual Adreno Vulkan resident optics, invoked only inside the isolated helper.
//! Original images remain immutable. Full-line dependencies survive dispatch batches.
use super::*;
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};

const IO_SAMPLES: usize = 262144;
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunMetrics {
    pub adapter: String,
    pub vendor: u32,
    pub shader_f64_advertised: bool,
    pub elapsed_ms: f64,
    pub setup_ms: f64,
    pub upload_ms: f64,
    pub dispatch_wait_ms: f64,
    pub readback_ms: f64,
    pub guard_ms: f64,
    pub upload_bytes: u64,
    pub readback_bytes: u64,
    pub dispatches: u64,
    /// Compute queue submissions only; readback/standalone transfer submits excluded.
    #[serde(skip)]
    pub compute_queue_submissions: u64,
    #[serde(skip)]
    pub(crate) pipeline_compile_ms: Vec<f64>,
    pub device_bytes: u64,
    pub pass_count: u64,
    pub sample_count: u64,
    pub sample_max_abs: f64,
    pub sample_rms: f64,
    pub sample_tolerance_absolute: f64,
    pub sample_tolerance_relative: f64,
    pub sample_scope: &'static str,
    #[serde(skip)]
    pub(crate) sample_sum: f64,
}
/// Frozen grain ABI. Construction admits only the checked-in, validated program
/// prefix and the two bounded frame capacities, never arbitrary caller bytes.
pub(crate) const GRAIN_CODE_PREFIX_BYTES: u64 = 4800;
pub(crate) const GRAIN_FRAME_BYTES: u64 = 544;
pub(crate) struct GrainScratchConfig {
    frame_capacity: u32,
    device_bytes: u64,
    code_prefix: &'static [u8; 4800],
}
impl GrainScratchConfig {
    pub(crate) fn new(frame_capacity: u32) -> R<Self> {
        use crate::research_grain::alu_programs;
        let device_bytes = alu_programs::allocation_bytes(frame_capacity)?;
        if alu_programs::ABI_VERSION != 1
            || alu_programs::CODE_BYTES != GRAIN_CODE_PREFIX_BYTES
            || alu_programs::FRAME_BYTES != GRAIN_FRAME_BYTES
            || !matches!(frame_capacity, 256 | 16384)
            || device_bytes
                != GRAIN_CODE_PREFIX_BYTES + GRAIN_FRAME_BYTES * u64::from(frame_capacity)
        {
            return Err("GPU_GRAIN_SCRATCH_ABI".into());
        }
        alu_programs::validate_prefix(alu_programs::CODE_PREFIX)?;
        Ok(Self {
            frame_capacity,
            device_bytes,
            code_prefix: alu_programs::CODE_PREFIX,
        })
    }
    pub(crate) fn device_bytes(&self) -> u64 {
        self.device_bytes
    }
    pub(crate) fn frame_capacity(&self) -> u32 {
        self.frame_capacity
    }
}
/// Binding 8 has exactly one purpose per Runner. Finish tables remain immutable.
enum Binding8Config<'a> {
    Absent,
    FinishTables(&'a [f32]),
    GrainScratch(GrainScratchConfig),
}
impl Binding8Config<'_> {
    fn device_bytes(&self) -> u64 {
        match self {
            Self::Absent => 0,
            Self::FinishTables(data) => data.len() as u64 * 4,
            Self::GrainScratch(config) => config.device_bytes(),
        }
    }
}
enum Binding8Resource {
    Absent,
    FinishTables(wgpu::Buffer),
    GrainScratch {
        buffer: wgpu::Buffer,
        frame_capacity: u32,
    },
}
impl Binding8Resource {
    fn buffer(&self) -> Option<&wgpu::Buffer> {
        match self {
            Self::Absent => None,
            Self::FinishTables(buffer) | Self::GrainScratch { buffer, .. } => Some(buffer),
        }
    }
}
fn validate_grain_dispatch(frame_capacity: u32, p: &[u32; 40]) -> R<()> {
    let valid = match p[2] {
        52 | 53 => p[4] == 1 && p[5] > 0 && p[5] <= frame_capacity.min(256),
        63 | 64 | 65 | 66 => p[4] == p[5] && p[5] > 0 && p[5] <= frame_capacity,
        62 => p[4] > 0 && p[4] <= frame_capacity,
        _ => true,
    };
    if valid {
        Ok(())
    } else {
        Err("GPU_GRAIN_SCRATCH_GEOMETRY".into())
    }
}
pub(crate) struct Runner {
    _cleanup_start: trace::CleanupStart,
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipelines: Vec<wgpu::ComputePipeline>,
    family_routes: Option<std::collections::BTreeMap<u32, usize>>,
    fixed_iir_routes: bool,
    error: Arc<AtomicBool>,
    planes: [wgpu::Buffer; 5],
    probe: wgpu::Buffer,
    map: wgpu::Buffer,
    numeric_error: Option<wgpu::Buffer>,
    binding8: Binding8Resource,
    _cleanup_end: trace::CleanupEnd,
    context: crate::gpu_session::Context,
}
// Same bindings as the inferred seven-storage-buffer ABI. Specialized shaders
// may omit uses of a plane without changing allocation or submit_dispatch.
/// Finite immutable routing table. Admission precedes device creation and all
/// uploads; duplicate/empty/oversize families cannot silently change routing.
const MAX_SHADER_FAMILIES: usize = 8;
fn validate_fixed_iir_params(fixed: bool, p: &[u32; 40]) -> R<()> {
    if fixed {
        if let Some((source, destination)) = super::families::iir_plane_route(p[2]) {
            if p[6] != source || p[7] != destination {
                return Err("GPU_OPTICS_IIR_ROUTE".into());
            }
        }
    }
    Ok(())
}

fn validate_shader_families(
    families: &[(Vec<u32>, String)],
) -> R<std::collections::BTreeMap<u32, usize>> {
    if families.is_empty() || families.len() > MAX_SHADER_FAMILIES {
        return Err("GPU_OPTICS_SHADER_FAMILIES".into());
    }
    let mut routes = std::collections::BTreeMap::new();
    for (index, (ops, source)) in families.iter().enumerate() {
        if ops.is_empty() || ops.len() > 64 || source.is_empty() || source.len() > 65536 {
            return Err("GPU_OPTICS_SHADER_FAMILIES".into());
        }
        for &op in ops {
            if routes.insert(op, index).is_some() {
                return Err("GPU_OPTICS_SHADER_FAMILIES".into());
            }
        }
    }
    Ok(routes)
}
fn numeric_error_bindings() -> [wgpu::BindGroupLayoutEntry; 8] {
    std::array::from_fn(|index| wgpu::BindGroupLayoutEntry {
        binding: index as u32,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: if index == 6 {
                wgpu::BufferBindingType::Uniform
            } else {
                wgpu::BufferBindingType::Storage { read_only: false }
            },
            has_dynamic_offset: false,
            min_binding_size: wgpu::BufferSize::new(if index == 6 { 160 } else { 4 }),
        },
        count: None,
    })
}
fn numeric_error_table_bindings() -> Vec<wgpu::BindGroupLayoutEntry> {
    let mut entries = numeric_error_bindings().to_vec();
    entries.push(wgpu::BindGroupLayoutEntry {
        binding: 8,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Buffer {
            ty: wgpu::BufferBindingType::Storage { read_only: true },
            has_dynamic_offset: false,
            min_binding_size: wgpu::BufferSize::new(4),
        },
        count: None,
    });
    entries
}
fn numeric_error_grain_bindings() -> Vec<wgpu::BindGroupLayoutEntry> {
    let mut entries = numeric_error_table_bindings();
    entries[8].ty = wgpu::BindingType::Buffer {
        ty: wgpu::BufferBindingType::Storage { read_only: false },
        has_dynamic_offset: false,
        min_binding_size: wgpu::BufferSize::new(GRAIN_CODE_PREFIX_BYTES + GRAIN_FRAME_BYTES),
    };
    entries
}
// Bound queue-owned upload payload and live command resources independently of
// image size. A dispatch still processes at most the caller's original chunk.
pub(crate) const BATCH_DISPATCHES: u32 = 32;
const BATCH_UPLOAD_BYTES: u64 = 256 * 1024;
const BATCH_PENDING_BYTES: u64 = 4 * MIB;
// Conservative reservation for one uniform, bind group, encoder and command
// buffer. Driver-private allocations are implementation-dependent, not measured.
const BATCH_COMMAND_RESERVATION: u64 = 64 * 1024;
#[derive(Default)]
struct BatchBudget {
    dispatches: u32,
    reserved_bytes: u64,
}
impl BatchBudget {
    fn reserve(&mut self, payload: u64) -> R<()> {
        let bytes = payload
            .checked_mul(3)
            .and_then(|n| n.checked_add(BATCH_COMMAND_RESERVATION))
            .ok_or("GPU_OPTICS_BATCH_LIMIT")?;
        let total = self
            .reserved_bytes
            .checked_add(bytes)
            .ok_or("GPU_OPTICS_BATCH_LIMIT")?;
        if payload > BATCH_UPLOAD_BYTES
            || self.dispatches >= BATCH_DISPATCHES
            || total > BATCH_PENDING_BYTES
        {
            return Err("GPU_OPTICS_BATCH_LIMIT".into());
        }
        self.dispatches += 1;
        self.reserved_bytes = total;
        Ok(())
    }
}
/// Immutable upload slices and uniforms in the exact caller dispatch order.
/// Payload is charged three times: up to twice its length for host arena
/// growth plus the COPY_SRC staging allocation; command resources are separate.
#[derive(Default)]
struct BatchPlan {
    budget: BatchBudget,
    uploads: Vec<u8>,
    commands: Vec<BatchCommand>,
}
struct BatchCommand {
    upload: Option<(u64, u64, u64)>, // staging offset, probe offset, length
    params: [u32; 40],
}
impl BatchPlan {
    fn push(
        &mut self,
        upload: Option<(u64, &[u8])>,
        params: [u32; 40],
        c: &mut impl FnMut() -> bool,
    ) -> R<()> {
        check(c)?;
        self.budget
            .reserve(upload.map_or(0, |(_, bytes)| bytes.len() as u64))?;
        let upload = upload.map(|(target, bytes)| {
            let start = self.uploads.len() as u64;
            self.uploads.extend_from_slice(bytes);
            (start, target, bytes.len() as u64)
        });
        self.commands.push(BatchCommand { upload, params });
        Ok(())
    }
    /// A failed recording never reaches submission. The final check also covers
    /// cancellation arriving while the last command was being encoded.
    fn record(&self, c: &mut impl FnMut() -> bool, mut record: impl FnMut(&BatchCommand)) -> R<()> {
        self.try_record(c, |command| {
            record(command);
            Ok(())
        })
    }
    fn try_record(
        &self,
        c: &mut impl FnMut() -> bool,
        mut record: impl FnMut(&BatchCommand) -> R<()>,
    ) -> R<()> {
        for command in &self.commands {
            check(c)?;
            record(command)?;
        }
        check(c)
    }
}
/// One ordered command buffer per bounded batch. Never queue-write multiple
/// chunks into the shared probe slot: writes precede all commands in a submit.
/// Instead copy immutable staging slices immediately before their consumers.
pub(crate) struct DispatchBatch<'a> {
    runner: &'a Runner,
    plan: BatchPlan,
}
impl DispatchBatch<'_> {
    pub(crate) fn submit_chunk(
        &mut self,
        upload: Option<(u64, &[u8])>,
        p: [u32; 40],
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        self.runner.validate_dispatch_params(&p)?;
        let at = Instant::now();
        if let Some((offset, bytes)) = upload {
            self.runner.validate_aux(offset, bytes)?;
        }
        let result = self.plan.push(upload, p, c);
        m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
        result
    }
    pub(crate) fn finish(self, c: &mut impl FnMut() -> bool, m: &mut RunMetrics) -> R<()> {
        check(c)?;
        if self.plan.commands.is_empty() {
            return Ok(());
        }
        let at = Instant::now();
        let staging = trace::phase_value(GpuPhase::Upload, || {
            (!self.plan.uploads.is_empty())
                .then(|| {
                    self.runner.context.buffer_init(
                        &wgpu::util::BufferInitDescriptor {
                            label: Some("immutable bounded batch uploads"),
                            contents: &self.plan.uploads,
                            usage: wgpu::BufferUsages::COPY_SRC,
                        },
                        true,
                    )
                })
                .transpose()
        })?;
        m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
        let at = Instant::now();
        let mut encoder = self
            .runner
            .device
            .create_command_encoder(&Default::default());
        self.plan.try_record(c, |command| {
            if let Some((source_offset, target_offset, bytes)) = command.upload {
                if bytes != 0 {
                    encoder.copy_buffer_to_buffer(
                        staging.as_ref().expect("nonempty batch upload"),
                        source_offset,
                        &self.runner.probe,
                        target_offset,
                        bytes,
                    );
                }
            }
            // A separate pass ends before the next copy. Uniform contents are
            // unique immutable buffers, never rewritten before execution.
            self.runner.record_dispatch(&mut encoder, command.params)
        })?;
        // Cancellation discards the entire unsubmitted encoder and staging.
        self.runner.queue.submit([encoder.finish()]);
        m.compute_queue_submissions += 1;
        m.dispatches += self.plan.commands.len() as u64;
        m.upload_bytes += self.plan.uploads.len() as u64 + 160 * self.plan.commands.len() as u64;
        trace::phase_result(GpuPhase::Compute, || self.runner.wait(c))?;
        m.dispatch_wait_ms += at.elapsed().as_secs_f64() * 1000.;
        Ok(())
    }
}

impl Runner {
    pub(crate) fn new(p: &FilmOpticsPlan, allow_probe: bool) -> R<(Self, RunMetrics)> {
        Self::new_with_fixed_iir_families(p, allow_probe, &super::families::shaders_for_optics(p)?)
    }
    pub(crate) fn new_with_shader(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader_source: &str,
    ) -> R<(Self, RunMetrics)> {
        Self::new_shader_inner(
            p,
            allow_probe,
            shader_source,
            false,
            Binding8Config::Absent,
            false,
            None,
        )
    }
    /// Tail-only integer failure transport. Existing shaders keep their inferred
    /// six-buffer layout; each new tail Runner starts with an independent zero.
    pub(crate) fn new_with_shader_error(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader_source: &str,
    ) -> R<(Self, RunMetrics)> {
        Self::new_shader_inner(
            p,
            allow_probe,
            shader_source,
            true,
            Binding8Config::Absent,
            false,
            None,
        )
    }
    /// Preserve all eight bindings when a smaller shader does not use every
    /// allocated plane. Existing runners continue to infer their own layouts.
    pub(crate) fn new_with_shader_error_explicit_layout(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader_source: &str,
    ) -> R<(Self, RunMetrics)> {
        Self::new_shader_inner(
            p,
            allow_probe,
            shader_source,
            true,
            Binding8Config::Absent,
            true,
            None,
        )
    }
    /// One device and binding set for the PCHIP-to-scanner resident chain.
    pub(crate) fn new_with_shader_error_tables(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader: &str,
        tables: &[f32],
    ) -> R<(Self, RunMetrics)> {
        if tables.is_empty() {
            return Err("GPU_OPTICS_TABLES".into());
        }
        Self::new_shader_inner(
            p,
            allow_probe,
            shader,
            true,
            Binding8Config::FinishTables(tables),
            false,
            None,
        )
    }
    /// Explicit nine-binding ABI for specialized fused finish shaders.
    pub(crate) fn new_with_shader_error_tables_explicit_layout(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader: &str,
        tables: &[f32],
    ) -> R<(Self, RunMetrics)> {
        if tables.is_empty() {
            return Err("GPU_OPTICS_TABLES".into());
        }
        Self::new_shader_inner(
            p,
            allow_probe,
            shader,
            true,
            Binding8Config::FinishTables(tables),
            true,
            None,
        )
    }
    /// Compile a bounded family set once on one device. All families share the
    /// eight-binding ABI and resident buffers, including persistent MT state.
    pub(crate) fn new_with_shader_family_error_explicit_layout(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        families: &[(Vec<u32>, String)],
    ) -> R<(Self, RunMetrics)> {
        validate_shader_families(families)?;
        Self::new_shader_inner(
            p,
            allow_probe,
            &families[0].1,
            true,
            Binding8Config::Absent,
            true,
            Some(families),
        )
    }
    /// Explicit optics/DIR contract; generic families do not imply binding specialization.
    pub(crate) fn new_with_fixed_iir_families(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        families: &[(Vec<u32>, String)],
    ) -> R<(Self, RunMetrics)> {
        let (mut runner, metrics) =
            Self::new_with_shader_family_error_explicit_layout(p, allow_probe, families)?;
        runner.fixed_iir_routes = true;
        Ok((runner, metrics))
    }
    /// One device and resident buffers for split print/scanner families, with
    /// the same immutable tables and explicit nine-binding ABI as fused finish.
    pub(crate) fn new_with_shader_family_error_tables_explicit_layout(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        families: &[(Vec<u32>, String)],
        tables: &[f32],
    ) -> R<(Self, RunMetrics)> {
        validate_shader_families(families)?;
        if tables.is_empty() {
            return Err("GPU_OPTICS_TABLES".into());
        }
        Self::new_shader_inner(
            p,
            allow_probe,
            &families[0].1,
            true,
            Binding8Config::FinishTables(tables),
            true,
            Some(families),
        )
    }
    /// One typed, persistent scratch allocation shared by every admitted family.
    pub(crate) fn new_with_grain_shader_families(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        families: &[(Vec<u32>, String)],
        scratch: GrainScratchConfig,
    ) -> R<(Self, RunMetrics)> {
        validate_shader_families(families)?;
        Self::new_shader_inner(
            p,
            allow_probe,
            &families[0].1,
            true,
            Binding8Config::GrainScratch(scratch),
            true,
            Some(families),
        )
    }
    fn new_shader_inner(
        p: &FilmOpticsPlan,
        allow_probe: bool,
        shader_source: &str,
        with_numeric_error: bool,
        binding8_config: Binding8Config<'_>,
        explicit_numeric_error_layout: bool,
        families: Option<&[(Vec<u32>, String)]>,
    ) -> R<(Self, RunMetrics)> {
        let family_routes = families.map(validate_shader_families).transpose()?;
        let fixed_iir_routes = false;
        if families.is_some() && (!with_numeric_error || !explicit_numeric_error_layout) {
            return Err("GPU_OPTICS_SHADER_FAMILIES".into());
        }
        p.validate()?;
        let grain_bytes = match &binding8_config {
            Binding8Config::GrainScratch(config) => config.device_bytes(),
            _ => 0,
        };
        // The spatial plan remains unchanged. Grain adds its device allocation
        // and conservative prefix host/staging charge, never a full host frame array.
        if grain_bytes != 0
            && p.combined_peak_bytes
                .checked_add(grain_bytes)
                .and_then(|bytes| bytes.checked_add(2 * GRAIN_CODE_PREFIX_BYTES))
                .is_none_or(|bytes| bytes > MEMORY_CAP)
        {
            return Err("GPU_OPTICS_MEMORY_LIMIT".into());
        }
        let at = Instant::now();
        let plane = p.width as u64 * p.height as u64 * 4;
        let device_phase = PhaseGuard::begin(GpuPhase::Device);
        let largest = plane
            .max(TRANSFER_BYTES)
            .max(binding8_config.device_bytes());
        let storage_count = if !matches!(&binding8_config, Binding8Config::Absent) {
            8
        } else if with_numeric_error {
            7
        } else {
            6
        };
        let context = crate::gpu_session::Context::acquire(largest, storage_count, allow_probe)?;
        let device = context.device.clone();
        let queue = context.queue.clone();
        let error = context.error.clone();
        let info = context.info.clone();
        let f64_cap = context.f64_cap;
        device_phase.finish();
        let pipeline_phase = PhaseGuard::begin(GpuPhase::Pipeline);
        let entries = explicit_numeric_error_layout.then(|| match &binding8_config {
            Binding8Config::Absent => numeric_error_bindings().to_vec(),
            Binding8Config::FinishTables(_) => numeric_error_table_bindings(),
            Binding8Config::GrainScratch(_) => numeric_error_grain_bindings(),
        });
        let sources: Vec<&str> = match families {
            Some(f) => f.iter().map(|(_, source)| source.as_str()).collect(),
            None => vec![shader_source],
        };
        let (pipelines, pipeline_compile_ms) = context.pipelines(&sources, entries.as_deref())?;
        pipeline_phase.finish();
        let upload_phase = PhaseGuard::begin(GpuPhase::Upload);
        let make = |label, size, usage| {
            context.buffer(
                &wgpu::BufferDescriptor {
                    label: Some(label),
                    size,
                    usage,
                    mapped_at_creation: false,
                },
                false,
            )
        };
        let usage = wgpu::BufferUsages::STORAGE
            | wgpu::BufferUsages::COPY_SRC
            | wgpu::BufferUsages::COPY_DST;
        let planes: [wgpu::Buffer; 5] = (0..5)
            .map(|_| make("resident film optics plane", plane, usage))
            .collect::<R<Vec<_>>>()?
            .try_into()
            .map_err(|_| "GPU_OPTICS_MEMORY_LIMIT")?;
        let probe = make("bounded full-line numeric gather", TRANSFER_BYTES, usage)?;
        let map = make(
            "bounded optical readback",
            TRANSFER_BYTES,
            wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        )?;
        // Four bytes fit inside the existing 65,536-byte fixed metadata reserve.
        // Never reset this flag during a request, including later spatial passes.
        let numeric_error = with_numeric_error
            .then(|| {
                context.buffer_init(
                    &wgpu::util::BufferInitDescriptor {
                        label: Some("sticky integer numeric error"),
                        contents: &0u32.to_le_bytes(),
                        usage: wgpu::BufferUsages::STORAGE
                            | wgpu::BufferUsages::COPY_SRC
                            | if cfg!(test)
                                && matches!(&binding8_config, Binding8Config::GrainScratch(_))
                            {
                                wgpu::BufferUsages::COPY_DST
                            } else {
                                wgpu::BufferUsages::empty()
                            },
                    },
                    false,
                )
            })
            .transpose()?;
        let mut prefix_upload_ms = 0.;
        let binding8 = match binding8_config {
            Binding8Config::Absent => Binding8Resource::Absent,
            Binding8Config::FinishTables(data) => {
                Binding8Resource::FinishTables(context.buffer_init(
                    &wgpu::util::BufferInitDescriptor {
                        label: Some("immutable fused finish tables and header"),
                        contents: bytemuck::cast_slice(data),
                        usage: wgpu::BufferUsages::STORAGE,
                    },
                    false,
                )?)
            }
            Binding8Config::GrainScratch(config) => {
                let buffer = make(
                    "typed persistent grain code and frames",
                    config.device_bytes(),
                    wgpu::BufferUsages::STORAGE
                        | wgpu::BufferUsages::COPY_DST
                        | if cfg!(test) {
                            wgpu::BufferUsages::COPY_SRC
                        } else {
                            wgpu::BufferUsages::empty()
                        },
                )?;
                let started = Instant::now();
                queue.write_buffer(&buffer, 0, config.code_prefix);
                prefix_upload_ms = started.elapsed().as_secs_f64() * 1000.;
                Binding8Resource::GrainScratch {
                    buffer,
                    frame_capacity: config.frame_capacity(),
                }
            }
        };
        let m = RunMetrics {
            adapter: info.name,
            vendor: info.vendor,
            shader_f64_advertised: f64_cap,
            elapsed_ms: 0.,
            setup_ms: at.elapsed().as_secs_f64() * 1000.,
            upload_ms: prefix_upload_ms,
            dispatch_wait_ms: 0.,
            readback_ms: 0.,
            guard_ms: 0.,
            upload_bytes: if grain_bytes != 0 {
                GRAIN_CODE_PREFIX_BYTES
            } else {
                0
            },
            readback_bytes: 0,
            dispatches: 0,
            compute_queue_submissions: 0,
            pipeline_compile_ms,
            device_bytes: p.device_bytes + grain_bytes,
            pass_count: 0,
            sample_count: 0,
            sample_max_abs: 0.,
            sample_rms: 0.,
            sample_tolerance_absolute: NUMERIC_TOLERANCE,
            sample_tolerance_relative: NUMERIC_TOLERANCE,
            sample_scope: "per_axis_f64_on_gpu_f32_input",
            sample_sum: 0.,
        };
        upload_phase.finish();
        let (_cleanup_start, _cleanup_end) = trace::cleanup_bounds();
        Ok((
            Self {
                _cleanup_start,
                _cleanup_end,
                device,
                queue,
                pipelines,
                family_routes,
                fixed_iir_routes,
                error,
                planes,
                probe,
                map,
                numeric_error,
                binding8,
                context,
            },
            m,
        ))
    }
    fn wait(&self, c: &mut impl FnMut() -> bool) -> R<()> {
        let (tx, rx) = mpsc::channel();
        self.queue.on_submitted_work_done(move || {
            let _ = tx.send(());
        });
        let at = Instant::now();
        loop {
            check(c)?;
            if self.error.load(Ordering::Relaxed) {
                return Err("GPU_DEVICE_FAILED".into());
            }
            let _ = self.device.poll(wgpu::Maintain::Poll);
            if rx.try_recv().is_ok() {
                self.context.release_transients_after_fence()?;
                return Ok(());
            }
            if at.elapsed() > Duration::from_secs(5) {
                return Err("GPU_OPTICS_TIMEOUT".into());
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    pub(crate) fn dispatch(
        &self,
        p: [u32; 40],
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        trace::phase_result(GpuPhase::Compute, || {
            check(c)?;
            let at = Instant::now();
            self.validate_dispatch_params(&p)?;
            self.submit_dispatch(p)?;
            m.compute_queue_submissions += 1;
            m.dispatches += 1;
            self.wait(c)?;
            m.upload_bytes += 160;
            m.dispatch_wait_ms += at.elapsed().as_secs_f64() * 1000.;
            Ok(())
        })
    }
    pub(crate) fn batch(&self) -> DispatchBatch<'_> {
        DispatchBatch {
            runner: self,
            plan: BatchPlan::default(),
        }
    }
    fn validate_dispatch_params(&self, p: &[u32; 40]) -> R<()> {
        self.pipeline_for(p[2])?;
        if let Binding8Resource::GrainScratch { frame_capacity, .. } = &self.binding8 {
            validate_grain_dispatch(*frame_capacity, p)?;
        }
        validate_fixed_iir_params(self.fixed_iir_routes, p)
    }
    fn pipeline_for(&self, op: u32) -> R<&wgpu::ComputePipeline> {
        let index = match &self.family_routes {
            Some(routes) => *routes.get(&op).ok_or("GPU_OPTICS_UNKNOWN_OPCODE")?,
            None => 0,
        };
        self.pipelines
            .get(index)
            .ok_or_else(|| "GPU_OPTICS_SHADER_FAMILIES".into())
    }
    fn submit_dispatch(&self, p: [u32; 40]) -> R<()> {
        let mut encoder = self.device.create_command_encoder(&Default::default());
        self.record_dispatch(&mut encoder, p)?;
        self.queue.submit([encoder.finish()]);
        Ok(())
    }
    fn record_dispatch(&self, encoder: &mut wgpu::CommandEncoder, p: [u32; 40]) -> R<()> {
        trace::phase_value(GpuPhase::Compute, || {
            // Both dispatch entry points validated this immutable route before any upload.
            let pipeline = self
                .pipeline_for(p[2])
                .expect("admitted immutable opcode route");
            let uniform = self.context.buffer_init(
                &wgpu::util::BufferInitDescriptor {
                    label: Some("resident optics parameters"),
                    contents: bytemuck::cast_slice(&p),
                    usage: wgpu::BufferUsages::UNIFORM,
                },
                true,
            )?;
            let mut entries: Vec<_> = self
                .planes
                .iter()
                .enumerate()
                .map(|(i, b)| wgpu::BindGroupEntry {
                    binding: i as u32,
                    resource: b.as_entire_binding(),
                })
                .collect();
            entries.push(wgpu::BindGroupEntry {
                binding: 5,
                resource: self.probe.as_entire_binding(),
            });
            entries.push(wgpu::BindGroupEntry {
                binding: 6,
                resource: uniform.as_entire_binding(),
            });
            if let Some(flag) = &self.numeric_error {
                entries.push(wgpu::BindGroupEntry {
                    binding: 7,
                    resource: flag.as_entire_binding(),
                });
            }
            if let Some(buffer) = self.binding8.buffer() {
                entries.push(wgpu::BindGroupEntry {
                    binding: 8,
                    resource: buffer.as_entire_binding(),
                });
            }
            let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("resident optics bindings"),
                layout: &pipeline.get_bind_group_layout(0),
                entries: &entries,
            });
            {
                let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                    label: Some("original FIR/IIR optics pass"),
                    timestamp_writes: None,
                });
                pass.set_pipeline(pipeline);
                pass.set_bind_group(0, &group, &[]);
                let groups = (p[4] as u64).div_ceil(64);
                pass.dispatch_workgroups(
                    groups.min(65535) as u32,
                    groups.div_ceil(65535) as u32,
                    1,
                );
            }
            Ok(())
        })
    }
    pub(crate) fn read_error_flag(
        &self,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<u32> {
        trace::phase_result(GpuPhase::Guard, || {
            let flag = self.numeric_error.as_ref().ok_or("GPU_OPTICS_ERROR_ABI")?;
            let bytes = self.read(flag, 0, 4, c, m)?;
            Ok(u32::from_le_bytes(
                bytes.try_into().map_err(|_| "GPU_OPTICS_ERROR_ABI")?,
            ))
        })
    }
    fn read(
        &self,
        buffer: &wgpu::Buffer,
        offset: u64,
        bytes: u64,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<Vec<u8>> {
        trace::phase_result(GpuPhase::Readback, || {
            if bytes > TRANSFER_BYTES {
                return Err("GPU_OPTICS_MEMORY_LIMIT".into());
            }
            check(c)?;
            let at = Instant::now();
            let mut e = self.device.create_command_encoder(&Default::default());
            e.copy_buffer_to_buffer(buffer, offset, &self.map, 0, bytes);
            self.queue.submit([e.finish()]);
            let (tx, rx) = mpsc::channel();
            self.map
                .slice(0..bytes)
                .map_async(wgpu::MapMode::Read, move |r| {
                    let _ = tx.send(r);
                });
            let result = (|| {
                loop {
                    check(c)?;
                    if self.error.load(Ordering::Relaxed) {
                        return Err("GPU_DEVICE_FAILED".into());
                    }
                    let _ = self.device.poll(wgpu::Maintain::Poll);
                    match rx.try_recv() {
                        Ok(Ok(())) => break,
                        Ok(Err(_)) => return Err("GPU_OPTICS_READBACK_FAILED".into()),
                        Err(mpsc::TryRecvError::Disconnected) => {
                            return Err("GPU_OPTICS_READBACK_FAILED".into());
                        }
                        Err(mpsc::TryRecvError::Empty) => {}
                    }
                    if at.elapsed() > Duration::from_secs(5) {
                        return Err("GPU_OPTICS_TIMEOUT".into());
                    }
                    std::thread::sleep(Duration::from_millis(1));
                }
                Ok(self.map.slice(0..bytes).get_mapped_range().to_vec())
            })();
            self.map.unmap();
            m.readback_ms += at.elapsed().as_secs_f64() * 1000.;
            if result.is_ok() {
                m.readback_bytes += bytes;
            }
            result
        })
    }
    fn guard(
        &self,
        w: u32,
        h: u32,
        vertical: bool,
        filter: &Filter,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        trace::phase_result(GpuPhase::Guard, || {
            // Deterministic true-image lines, not cropped halos. Includes edges within each line.
            let mut p = params(w, h, if vertical { 11 } else { 10 });
            let n = if vertical { h } else { w };
            p[4] = n;
            p[36] = if vertical { w / 2 } else { h / 2 };
            self.dispatch(p, c, m)?;
            let raw = self.read(&self.probe, 0, n as u64 * 8, c, m)?;
            let at = Instant::now();
            let mut source = Vec::with_capacity(n as usize);
            let mut actual = Vec::with_capacity(n as usize);
            for b in raw[..n as usize * 4].chunks_exact(4) {
                source.push(f32::from_le_bytes(b.try_into().unwrap()) as f64);
            }
            for b in raw[n as usize * 4..].chunks_exact(4) {
                actual.push(f32::from_le_bytes(b.try_into().unwrap()));
            }
            let reference = filter.line_f64(&source);
            let (max, sum) = compare_lines(&reference, &actual)?;
            m.sample_count += n as u64;
            m.sample_max_abs = m.sample_max_abs.max(max);
            m.sample_sum += sum;
            m.guard_ms += at.elapsed().as_secs_f64() * 1000.;
            Ok(())
        })
    }
    pub(crate) fn blur(
        &self,
        w: u32,
        h: u32,
        sigma: f32,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        let f = Filter::new(sigma)?;
        for vertical in [false, true] {
            let mut p = params(w, h, 0);
            p[6] = if vertical { 1 } else { 0 };
            p[7] = if vertical { 2 } else { 1 };
            match &f {
                Filter::Identity => p[2] = 4,
                Filter::Fir(k) => {
                    p[2] = u32::from(vertical);
                    p[5] = (k.len() / 2) as u32;
                    for (i, &x) in k.iter().enumerate() {
                        p[16 + i] = (x as f32).to_bits();
                    }
                }
                Filter::Iir(k) => {
                    p[2] = if vertical { 3 } else { 2 };
                    p[4] = if vertical { w } else { h };
                    for (i, &x) in k.iter().enumerate() {
                        p[8 + i] = (x as f32).to_bits();
                    }
                }
            }
            self.dispatch(p, c, m)?;
            self.guard(w, h, vertical, &f, c, m)?;
        }
        m.pass_count += 1;
        Ok(())
    }
    fn validate_aux(&self, offset: u64, bytes: &[u8]) -> R<()> {
        if offset % 4 != 0
            || bytes.len() % 4 != 0
            || offset
                .checked_add(bytes.len() as u64)
                .is_none_or(|end| end > TRANSFER_BYTES)
        {
            return Err("GPU_OPTICS_MEMORY_LIMIT".into());
        }
        Ok(())
    }
    pub(crate) fn upload_aux(
        &self,
        offset: u64,
        bytes: &[u8],
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        trace::phase_result(GpuPhase::Upload, || {
            check(c)?;
            self.validate_aux(offset, bytes)?;
            let at = Instant::now();
            self.queue.write_buffer(&self.probe, offset, bytes);
            self.queue.submit([]);
            self.wait(c)?;
            m.upload_bytes += bytes.len() as u64;
            m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
            Ok(())
        })
    }
    /// Test-only fault injection/sentinel access. Production has no API to
    /// mutate the admitted code prefix or expose sampler frame contents.
    #[cfg(test)]
    fn test_grain_scratch_range(&self, offset: u64, bytes: u64) -> R<&wgpu::Buffer> {
        let Binding8Resource::GrainScratch {
            buffer,
            frame_capacity,
        } = &self.binding8
        else {
            return Err("GPU_GRAIN_SCRATCH_ABI".into());
        };
        let size = GRAIN_CODE_PREFIX_BYTES + GRAIN_FRAME_BYTES * u64::from(*frame_capacity);
        if bytes == 0
            || offset % 4 != 0
            || bytes % 4 != 0
            || bytes > TRANSFER_BYTES
            || offset.checked_add(bytes).is_none_or(|end| end > size)
        {
            return Err("GPU_GRAIN_SCRATCH_GEOMETRY".into());
        }
        Ok(buffer)
    }
    #[cfg(test)]
    pub(crate) fn test_upload_grain_scratch(
        &self,
        offset: u64,
        bytes: &[u8],
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        check(c)?;
        let buffer = self.test_grain_scratch_range(offset, bytes.len() as u64)?;
        trace::phase_result(GpuPhase::Upload, || {
            let at = Instant::now();
            self.queue.write_buffer(buffer, offset, bytes);
            self.queue.submit([]);
            self.wait(c)?;
            m.upload_bytes += bytes.len() as u64;
            m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
            Ok(())
        })
    }
    /// Start an independent negative-test trial on an existing test device.
    /// This deliberately is not available to production request execution.
    #[cfg(test)]
    pub(crate) fn test_reset_grain_error_flag(
        &self,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        check(c)?;
        if !matches!(&self.binding8, Binding8Resource::GrainScratch { .. }) {
            return Err("GPU_GRAIN_SCRATCH_ABI".into());
        }
        let buffer = self.numeric_error.as_ref().ok_or("GPU_OPTICS_ERROR_ABI")?;
        trace::phase_result(GpuPhase::Upload, || {
            let at = Instant::now();
            self.queue.write_buffer(buffer, 0, &0u32.to_le_bytes());
            self.queue.submit([]);
            self.wait(c)?;
            m.upload_bytes += 4;
            m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
            Ok(())
        })
    }
    #[cfg(test)]
    pub(crate) fn test_read_grain_scratch(
        &self,
        offset: u64,
        bytes: u64,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<Vec<u8>> {
        check(c)?;
        let buffer = self.test_grain_scratch_range(offset, bytes)?;
        self.read(buffer, offset, bytes, c, m)
    }
    pub(crate) fn read_source(
        &self,
        offset: u64,
        bytes: u64,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<Vec<u8>> {
        self.read(&self.planes[0], offset, bytes, c, m)
    }
    /// Explicit bounded transport for the file-backed full-line executor.
    /// It never exposes binding8, sampler prefix or typed-frame contents.
    pub(crate) fn upload_stream_plane(&self, plane: u32, values: &[f32],
        c: &mut impl FnMut() -> bool, m: &mut RunMetrics) -> R<()> {
        let result = (|| {
            check(c)?;
            if self.error.load(Ordering::Acquire) { return Err("GPU_DEVICE_FAILED".into()); }
            let buffer = self.planes.get(plane as usize).ok_or("GPU_STREAM_DISPATCH_CONTRACT")?;
            let bytes = values.len() as u64 * 4;
            if bytes == 0 || bytes > TRANSFER_BYTES || bytes > buffer.size() {
                return Err("GPU_STREAM_LINE_LIMIT".into());
            }
            let started = Instant::now();
            self.queue.write_buffer(buffer, 0, bytemuck::cast_slice(values));
            self.queue.submit([]);
            self.wait(c)?;
            m.upload_bytes += bytes; m.upload_ms += started.elapsed().as_secs_f64() * 1000.;
            Ok(())
        })();
        if result.is_err() { self.invalidate_streaming(); }
        result
    }
    pub(crate) fn read_stream_plane(&self, plane: u32, bytes: u64,
        c: &mut impl FnMut() -> bool, m: &mut RunMetrics) -> R<Vec<u8>> {
        let result = (|| {
            check(c)?;
            if self.error.load(Ordering::Acquire) { return Err("GPU_DEVICE_FAILED".into()); }
            let buffer = self.planes.get(plane as usize).ok_or("GPU_STREAM_DISPATCH_CONTRACT")?;
            if bytes == 0 || bytes % 4 != 0 || bytes > TRANSFER_BYTES || bytes > buffer.size() {
                return Err("GPU_STREAM_LINE_LIMIT".into());
            }
            self.read(buffer, 0, bytes, c, m)
        })();
        if result.is_err() { self.invalidate_streaming(); }
        result
    }
    pub(crate) fn invalidate_streaming(&self) {
        self.error.store(true, Ordering::Release);
        crate::gpu_session::retire_current();
    }
    pub(crate) fn read_probe(
        &self,
        bytes: u64,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<Vec<u8>> {
        self.read(&self.probe, 0, bytes, c, m)
    }
    fn run_channel(
        &self,
        p: &ChannelPlan,
        w: u32,
        h: u32,
        c: &mut impl FnMut() -> bool,
        m: &mut RunMetrics,
    ) -> R<()> {
        if p.lens_sigma > 0. {
            self.blur(w, h, p.lens_sigma, c, m)?;
            let mut v = params(w, h, 4);
            v[6] = 2;
            v[7] = 0;
            self.dispatch(v, c, m)?;
        }
        if let Some(s) = &p.scatter {
            self.blur(w, h, s.sigma, c, m)?;
            let mut v = params(w, h, 4);
            v[6] = 2;
            v[7] = 3;
            self.dispatch(v, c, m)?;
            self.dispatch(params(w, h, 5), c, m)?;
            for (amp, ratio) in FIT {
                self.blur(w, h, (ratio * s.decay as f64) as f32, c, m)?;
                let mut v = params(w, h, 6);
                v[12] = (amp as f32).to_bits();
                self.dispatch(v, c, m)?;
            }
            let mut v = params(w, h, 7);
            v[12] = (s.amount as f32).to_bits();
            v[13] = (s.tail_weight as f32).to_bits();
            self.dispatch(v, c, m)?;
        }
        if !p.bounce_sigmas.is_empty() {
            self.dispatch(params(w, h, 5), c, m)?;
            for (&sigma, &weight) in p.bounce_sigmas.iter().zip(&p.bounce_weights) {
                self.blur(w, h, sigma, c, m)?;
                let mut v = params(w, h, 6);
                v[12] = (weight as f32).to_bits();
                self.dispatch(v, c, m)?;
            }
            let mut v = params(w, h, 8);
            v[14] = (p.strength as f32).to_bits();
            self.dispatch(v, c, m)?;
        }
        if p.divisor != 1. {
            let mut v = params(w, h, 9);
            v[15] = (p.divisor as f32).to_bits();
            self.dispatch(v, c, m)?;
        }
        Ok(())
    }
}
pub(crate) fn params(w: u32, h: u32, op: u32) -> [u32; 40] {
    let mut p = [0; 40];
    p[0] = w;
    p[1] = h;
    p[2] = op;
    p[4] = w * h;
    p
}
struct Outputs<'a> {
    paths: [&'a Path; 3],
    created: usize,
    keep: bool,
}
impl Drop for Outputs<'_> {
    fn drop(&mut self) {
        if !self.keep {
            trace::phase_value(GpuPhase::Cleanup, || {
                for p in &self.paths[..self.created] {
                    let _ = std::fs::remove_file(p);
                }
            });
        }
    }
}
/// One invocation per helper process; caller waits for confirmed process death
/// before discarding source links or retrying the entire stage on the CPU.
pub fn run_files(
    p: &FilmOpticsPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
) -> R<RunMetrics> {
    run_files_inner(p, sources, outputs, c, progress, false)
}
fn run_files_inner(
    p: &FilmOpticsPlan,
    sources: [&Path; 3],
    outputs: [&Path; 3],
    c: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(u64, u64),
    allow_probe: bool,
) -> R<RunMetrics> {
    let start = Instant::now();
    trace::phase_result(GpuPhase::Guard, || p.numerical_preflight(c))?;
    let preflight = start.elapsed().as_secs_f64() * 1000.;
    let n = p.width as u64 * p.height as u64;
    for i in 0..3 {
        if sources.contains(&outputs[i]) || outputs[..i].contains(&outputs[i]) {
            return Err("INVALID_OPTICS_FILE".into());
        }
        let meta = std::fs::symlink_metadata(sources[i]).map_err(|_| "GPU_OPTICS_IO")?;
        if !meta.is_file() || meta.file_type().is_symlink() || meta.len() != n * 8 {
            return Err("INVALID_OPTICS_FILE".into());
        }
    }
    check(c)?;
    let (r, mut m) = Runner::new(p, allow_probe)?;
    m.setup_ms += preflight;
    let mut guard = Outputs {
        paths: outputs,
        created: 0,
        keep: false,
    };
    for ch in 0..3 {
        check(c)?;
        let mut source = File::open(sources[ch]).map_err(|_| "GPU_OPTICS_IO")?;
        let at = Instant::now();
        let mut left = n;
        let mut offset = 0;
        let mut raw = vec![0u8; IO_SAMPLES * 8];
        let mut upload = vec![0u8; IO_SAMPLES * 4];
        while left > 0 {
            check(c)?;
            let count = left.min(IO_SAMPLES as u64) as usize;
            source
                .read_exact(&mut raw[..count * 8])
                .map_err(|_| "GPU_OPTICS_IO")?;
            for (i, b) in raw[..count * 8].chunks_exact(8).enumerate() {
                let x = f64::from_le_bytes(b.try_into().unwrap());
                if !x.is_finite() || !(x as f32).is_finite() {
                    return Err("NONFINITE_OPTICS_INPUT".into());
                }
                upload[i * 4..i * 4 + 4].copy_from_slice(&(x as f32).to_le_bytes());
            }
            let upload_phase = PhaseGuard::begin(GpuPhase::Upload);
            r.queue
                .write_buffer(&r.planes[0], offset, &upload[..count * 4]);
            r.queue.submit([]);
            r.wait(c)?;
            upload_phase.finish();
            offset += count as u64 * 4;
            left -= count as u64;
            m.upload_bytes += count as u64 * 4;
        }
        drop(raw);
        drop(upload);
        drop(source);
        m.upload_ms += at.elapsed().as_secs_f64() * 1000.;
        r.run_channel(&p.channels[ch], p.width, p.height, c, &mut m)?;
        if r.read_error_flag(c, &mut m)? != 0 {
            return Err("GPU_OPTICS_NUMERIC_DOMAIN".into());
        }
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(outputs[ch])
            .map_err(|_| "GPU_OPTICS_IO")?;
        guard.created += 1;
        let mut offset = 0;
        let mut encoded = Vec::with_capacity(IO_SAMPLES * 8);
        while offset < n {
            check(c)?;
            let count = (n - offset).min(IO_SAMPLES as u64);
            let bytes = r.read(&r.planes[0], offset * 4, count * 4, c, &mut m)?;
            encoded.clear();
            for b in bytes.chunks_exact(4) {
                let x = f32::from_le_bytes(b.try_into().unwrap());
                if !x.is_finite() {
                    return Err("NONFINITE_OPTICS_OUTPUT".into());
                }
                encoded.extend_from_slice(&(x as f64).to_le_bytes());
            }
            output.write_all(&encoded).map_err(|_| "GPU_OPTICS_IO")?;
            offset += count;
            progress(ch as u64 * n + offset, 3 * n);
        }
        output.sync_all().map_err(|_| "GPU_OPTICS_IO")?;
    }
    check(c)?;
    if r.error.load(Ordering::Relaxed) {
        return Err("GPU_DEVICE_FAILED".into());
    }
    m.sample_rms = (m.sample_sum / m.sample_count.max(1) as f64).sqrt();
    m.elapsed_ms = start.elapsed().as_secs_f64() * 1000.;
    if r.read_error_flag(c, &mut m)? != 0 {
        return Err("GPU_OPTICS_NUMERIC_DOMAIN".into());
    }
    guard.keep = true;
    Ok(m)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "explicit Vulkan software/hardware probe; not mobile speed evidence"]
    fn actual_vulkan_optics_matches_mirror() {
        let root = std::env::temp_dir().join(format!("film-optics-vulkan-{}", std::process::id()));
        std::fs::create_dir(&root).unwrap();
        let (w, h) = (71, 37);
        let mut params = RuntimeParams::default();
        params.camera.lens_blur_um = 0.4;
        let p = FilmOpticsPlan::new(&params, 20., w, h).unwrap();
        let src: Vec<f64> = (0..w * h)
            .map(|i| ((i * 37 % 101) as f64 / 13.) - 0.7)
            .collect();
        let inputs: [std::path::PathBuf; 3] = std::array::from_fn(|i| root.join(format!("in{i}")));
        let outputs: [std::path::PathBuf; 3] =
            std::array::from_fn(|i| root.join(format!("out{i}")));
        for path in &inputs {
            let mut f = File::create(path).unwrap();
            for x in &src {
                f.write_all(&x.to_le_bytes()).unwrap();
            }
        }
        let metrics = run_files_inner(
            &p,
            inputs.each_ref().map(|x| x.as_path()),
            outputs.each_ref().map(|x| x.as_path()),
            &mut || false,
            &mut |_, _| {},
            true,
        )
        .unwrap();
        assert_eq!(metrics.sample_count, metrics.pass_count * (w + h) as u64);
        for ch in 0..3 {
            let expected = channel_cpu_mirror(&src, w, h, &p.channels[ch], &mut || false).unwrap();
            let bytes = std::fs::read(&outputs[ch]).unwrap();
            for (x, b) in expected.iter().zip(bytes.chunks_exact(8)) {
                let y = f64::from_le_bytes(b.try_into().unwrap());
                assert!((x - y).abs() < 2e-4 * x.abs().max(1.), "{x} {y}");
            }
        }
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[ignore = "explicit Vulkan probe; not mobile performance evidence"]
    fn actual_vulkan_unsampled_optics_overflow_has_no_output() {
        for sigma in [1., 4.] {
            let root =
                std::env::temp_dir().join(format!("optics-domain-{}-{sigma}", std::process::id()));
            std::fs::create_dir(&root).unwrap();
            let mut rp = RuntimeParams::default();
            rp.camera.lens_blur_um = 0.;
            let mut p = FilmOpticsPlan::new(&rp, 20., 71, 37).unwrap();
            for ch in &mut p.channels {
                ch.lens_sigma = sigma;
                ch.scatter = None;
                ch.bounce_sigmas.clear();
                ch.bounce_weights.clear();
                ch.strength = 0.;
                ch.divisor = 1.;
            }
            let inputs: [std::path::PathBuf; 3] =
                std::array::from_fn(|ch| root.join(format!("in{ch}")));
            let outputs: [std::path::PathBuf; 3] =
                std::array::from_fn(|ch| root.join(format!("out{ch}")));
            let mut values = vec![0.25f64; 71 * 37];
            values[72] = 3e38;
            for path in &inputs {
                std::fs::write(
                    path,
                    values
                        .iter()
                        .flat_map(|x| x.to_le_bytes())
                        .collect::<Vec<_>>(),
                )
                .unwrap();
            }
            let result = run_files_inner(
                &p,
                inputs.each_ref().map(|p| p.as_path()),
                outputs.each_ref().map(|p| p.as_path()),
                &mut || false,
                &mut |_, _| {},
                true,
            );
            assert_eq!(result.err().unwrap(), "GPU_OPTICS_NUMERIC_DOMAIN");
            assert!(outputs.iter().all(|p| !p.exists()));
            std::fs::remove_dir_all(root).unwrap();
        }
    }
}

#[cfg(test)]
mod bounded_batch_tests {
    use super::*;
    #[test]
    fn specialized_numeric_error_layout_keeps_existing_binding_abi() {
        let entries = numeric_error_bindings();
        assert_eq!(entries.len(), 8);
        for (index, entry) in entries.iter().enumerate() {
            assert_eq!(entry.binding, index as u32);
            assert_eq!(entry.visibility, wgpu::ShaderStages::COMPUTE);
            assert_eq!(entry.count, None);
            assert_eq!(
                entry.ty,
                wgpu::BindingType::Buffer {
                    ty: if index == 6 {
                        wgpu::BufferBindingType::Uniform
                    } else {
                        wgpu::BufferBindingType::Storage { read_only: false }
                    },
                    has_dynamic_offset: false,
                    min_binding_size: wgpu::BufferSize::new(if index == 6 { 160 } else { 4 }),
                }
            );
        }
        assert_eq!(
            entries
                .iter()
                .filter(|entry| matches!(
                    entry.ty,
                    wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Storage { .. },
                        ..
                    }
                ))
                .count(),
            7
        );
    }
    #[test]
    fn sequential_batch_bounds_include_uploads_and_command_reservations() {
        assert_eq!(
            BATCH_DISPATCHES,
            2 * crate::research_grain::guarded_graph::SEQUENTIAL_BATCH_CHUNKS
        );
        assert_eq!(
            BATCH_PENDING_BYTES,
            crate::research_grain::guarded_graph::BATCH_RESERVATION_BYTES
        );
        let mut b = BatchBudget::default();
        for _ in 0..BATCH_DISPATCHES {
            b.reserve(256 * 3 * 4).unwrap();
        }
        assert_eq!(b.dispatches, 32);
        assert_eq!(
            b.reserved_bytes,
            32 * (3 * 3072 + BATCH_COMMAND_RESERVATION)
        );
        assert!(b.reserved_bytes <= BATCH_PENDING_BYTES);
        let saved = (b.dispatches, b.reserved_bytes);
        assert!(b.reserve(0).is_err());
        assert_eq!(saved, (b.dispatches, b.reserved_bytes));
    }
    #[test]
    fn batch_rejects_oversize_and_overflow_before_queue_write() {
        for n in [BATCH_UPLOAD_BYTES + 1, u64::MAX] {
            let mut b = BatchBudget::default();
            assert!(b.reserve(n).is_err());
            assert_eq!((b.dispatches, b.reserved_bytes), (0, 0));
        }
        let mut b = BatchBudget::default();
        while b.reserve(BATCH_UPLOAD_BYTES).is_ok() {}
        assert!(b.dispatches < BATCH_DISPATCHES);
        assert!(b.reserved_bytes <= BATCH_PENDING_BYTES);
        // Existing coordinate chunk remains admitted (batch size one).
        BatchBudget::default().reserve(16384 * 3 * 4).unwrap();
    }
}

#[cfg(test)]
mod recorded_batch_tests {
    use super::*;
    #[test]
    fn ordered_copies_keep_each_chunk_and_uniform_immutable() {
        let mut plan = BatchPlan::default();
        let mut params = [0u32; 40];
        let mut bytes = vec![1u8; 12];
        for chunk in 0..16u32 {
            bytes.fill(chunk as u8);
            params[2] = 63;
            params[7] = chunk * 256;
            plan.push(Some((978944 * 4, &bytes)), params, &mut || false)
                .unwrap();
            params[2] = 52;
            plan.push(None, params, &mut || false).unwrap();
        }
        bytes.fill(255);
        params.fill(u32::MAX);
        let mut events = Vec::new();
        let mut shared = Vec::new();
        plan.record(&mut || false, |command| {
            if let Some((source, target, len)) = command.upload {
                assert_eq!(target, 978944 * 4);
                assert_eq!(source, (command.params[7] / 256) as u64 * 12);
                shared = plan.uploads[source as usize..(source + len) as usize].to_vec();
                events.push((0, command.params[7]));
            }
            assert_eq!(shared, vec![(command.params[7] / 256) as u8; 12]);
            events.push((command.params[2], command.params[7]));
        })
        .unwrap();
        let expected: Vec<_> = (0..16u32)
            .flat_map(|chunk| [(0, chunk * 256), (63, chunk * 256), (52, chunk * 256)])
            .collect();
        assert_eq!(events, expected);
        assert_eq!(plan.commands.len(), 32);
        assert!(plan.push(None, params, &mut || false).is_err());
        assert_eq!(plan.commands.len(), 32);
    }
    #[test]
    fn canceled_recording_cannot_submit_partial_batch() {
        for cancel_at in 0..=4 {
            let mut plan = BatchPlan::default();
            for op in [63, 52, 63, 52] {
                let mut params = [0; 40];
                params[2] = op;
                plan.push(None, params, &mut || false).unwrap();
            }
            let mut checks = 0;
            let mut recorded = 0;
            let mut submissions = 0;
            let result = plan.record(
                &mut || {
                    let cancel = checks == cancel_at;
                    checks += 1;
                    cancel
                },
                |_| recorded += 1,
            );
            if result.is_ok() {
                submissions += 1;
            }
            assert!(result.is_err());
            assert_eq!(recorded, cancel_at);
            assert_eq!(submissions, 0);
        }
        let mut plan = BatchPlan::default();
        assert!(
            plan.push(Some((0, &[1, 2, 3, 4])), [0; 40], &mut || true)
                .is_err()
        );
        assert!(plan.commands.is_empty() && plan.uploads.is_empty());
        assert_eq!(plan.budget.reserved_bytes, 0);
    }
    #[test]
    fn empty_and_partial_batch_record_exactly_the_requested_work() {
        let mut plan = BatchPlan::default();
        plan.record(&mut || false, |_| panic!("empty batch recorded a dispatch"))
            .unwrap();
        for count in [1u32, 255, 256] {
            let mut params = [0; 40];
            params[4] = count;
            plan.push(Some((0, &[])), params, &mut || false).unwrap();
        }
        let mut counts = Vec::new();
        plan.record(&mut || false, |cmd| counts.push(cmd.params[4]))
            .unwrap();
        assert_eq!(counts, [1, 255, 256]);
        assert!(plan.uploads.is_empty());
    }
    #[test]
    fn table_layout_preserves_all_original_bindings_and_read_only_table() {
        let entries = numeric_error_table_bindings();
        assert_eq!(&entries[..8], &numeric_error_bindings());
        assert_eq!(entries.len(), 9);
        assert_eq!(entries[8].binding, 8);
        assert_eq!(entries[8].visibility, wgpu::ShaderStages::COMPUTE);
        assert_eq!(entries[8].count, None);
        assert_eq!(
            entries[8].ty,
            wgpu::BindingType::Buffer {
                ty: wgpu::BufferBindingType::Storage { read_only: true },
                has_dynamic_offset: false,
                min_binding_size: wgpu::BufferSize::new(4),
            }
        );
    }
}

#[cfg(test)]
mod batch_vulkan_tests {
    use super::*;
    // Small shader exercises the actual Runner ABI, staging copy order, unique
    // uniforms and persistent state without compiling the full grain sampler.
    const SHADER: &str = r#"
struct Data { values: array<f32>, }
struct Params { u: array<vec4<u32>, 10>, }
@group(0) @binding(0) var<storage, read_write> source: Data;
@group(0) @binding(1) var<storage, read_write> horizontal: Data;
@group(0) @binding(4) var<storage, read_write> accum: Data;
@group(0) @binding(5) var<storage, read_write> probe: Data;
@group(0) @binding(6) var<uniform> p: Params;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>) {
 if (gid.x != 0u) { return; }
 let i = p.u[1].w;
 if (p.u[0].z == 63u) { horizontal.values[i] = probe.values[0]; return; }
 accum.values[0] = accum.values[0] + 1.0;
 source.values[i] = horizontal.values[i] + bitcast<f32>(p.u[2].x) + accum.values[0];
}
"#;
    #[test]
    #[ignore = "explicit small Vulkan batching probe; not mobile performance evidence"]
    fn actual_ordered_batch_preserves_uploads_uniforms_and_state() {
        let plan = FilmOpticsPlan::new(&RuntimeParams::default(), 20., 16, 1).unwrap();
        let (runner, mut metrics) =
            Runner::new_with_shader_error_explicit_layout(&plan, true, SHADER).unwrap();
        runner.batch().finish(&mut || false, &mut metrics).unwrap();
        assert_eq!(metrics.compute_queue_submissions, 0);
        let mut batch = runner.batch();
        for i in 0..16u32 {
            let mut p = params(16, 1, 63);
            p[4] = 1;
            p[7] = i;
            batch
                .submit_chunk(
                    Some((0, &(i as f32 * 2.).to_le_bytes())),
                    p,
                    &mut || false,
                    &mut metrics,
                )
                .unwrap();
            p[2] = 52;
            p[8] = (100. + i as f32).to_bits();
            batch
                .submit_chunk(None, p, &mut || false, &mut metrics)
                .unwrap();
        }
        assert_eq!(metrics.dispatches, 0);
        assert_eq!(metrics.compute_queue_submissions, 0);
        batch.finish(&mut || false, &mut metrics).unwrap();
        assert_eq!(metrics.dispatches, 32);
        assert_eq!(metrics.compute_queue_submissions, 1);
        assert_eq!(metrics.upload_bytes, 16 * 4 + 32 * 160);
        let bytes = runner
            .read_source(0, 16 * 4, &mut || false, &mut metrics)
            .unwrap();
        let actual: Vec<_> = bytes
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
            .collect();
        assert_eq!(
            actual,
            (0..16).map(|i| 101. + 4. * i as f32).collect::<Vec<_>>()
        );
        let mut cancelled = runner.batch();
        let mut p = params(16, 1, 52);
        p[4] = 1;
        cancelled
            .submit_chunk(None, p, &mut || false, &mut metrics)
            .unwrap();
        assert!(cancelled.finish(&mut || true, &mut metrics).is_err());
        assert_eq!(metrics.compute_queue_submissions, 1);
        assert_eq!(metrics.dispatches, 32);
        assert_eq!(
            runner
                .read_source(0, 16 * 4, &mut || false, &mut metrics)
                .unwrap(),
            bytes
        );
    }
}

#[cfg(test)]
mod batch_mt_vulkan_tests {
    use super::*;
    const PIXELS: u32 = 33 * 256 + 17;
    const PREFIX: &str = r#"
struct Data { values: array<f32>, }
struct Params { u: array<vec4<u32>, 10>, }
@group(0) @binding(0) var<storage, read_write> source: Data;
@group(0) @binding(3) var<storage, read_write> saved: Data;
@group(0) @binding(5) var<storage, read_write> probe: Data;
@group(0) @binding(6) var<uniform> p: Params;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>) {
 let i=gid.x;
 if(i>=p.u[1].x){return;}
 if(p.u[0].z==40u){preview_rng_main(i);return;}
 let offset=p.u[1].w;
 if(p.u[0].z==63u){saved.values[offset+i]=probe.values[i];return;}
 if(i!=0u){return;}
 for(var pixel=0u;pixel<p.u[1].y;pixel=pixel+1u){
  let word=mt_next();let at=offset+pixel;
  source.values[3u*at]=f32(word&65535u);
  source.values[3u*at+1u]=f32(word>>16u);
  source.values[3u*at+2u]=saved.values[at]+f32(p.u[2].y);
 }
}
"#;
    fn shader() -> String {
        // Append the complete original file byte-for-byte, including MT twist,
        // cursor, word counter and initialization. No substitute RNG algorithm.
        let mut source = PREFIX.to_owned();
        source.push_str(crate::research_grain::preview_rng::SHADER);
        source
    }
    fn chunk(offset: u32) -> (Vec<u8>, [u32; 40], [u32; 40]) {
        let count = (PIXELS - offset).min(256);
        let bytes = (offset..offset + count)
            .flat_map(|i| (i as f32 + 0.25).to_le_bytes())
            .collect();
        let mut prepare = params(3 * PIXELS, 1, 63);
        prepare[4] = count;
        prepare[5] = count;
        prepare[7] = offset;
        prepare[9] = offset / 256 + 17;
        let mut consume = prepare;
        consume[2] = 52;
        consume[4] = 1;
        (bytes, prepare, consume)
    }
    #[test]
    fn mt_batch_fixture_appends_original_rng_source_unchanged() {
        let source = shader();
        assert_eq!(
            &source[PREFIX.len()..],
            crate::research_grain::preview_rng::SHADER
        );
        let module = naga::front::wgsl::parse_str(&source)
            .unwrap_or_else(|e| panic!("{}", e.emit_to_string(&source)));
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap();
        assert_eq!(PIXELS.div_ceil(256), 34);
        assert!(PIXELS > 2 * 16 * 256);
    }
    #[test]
    #[ignore = "explicit original-MT Vulkan submission probe; not full grain or mobile speed evidence"]
    fn actual_mt_stream_matches_serial_and_cpu_across_twists_and_batches() {
        let plan = FilmOpticsPlan::new(&RuntimeParams::default(), 20., 3 * PIXELS, 1).unwrap();
        let (runner, mut metrics) =
            Runner::new_with_shader_error_explicit_layout(&plan, true, &shader()).unwrap();
        for seed in [0u32, 42, u32::MAX] {
            let mut init = params(3 * PIXELS, 1, 40);
            init[4] = 1;
            init[8] = seed;
            runner.dispatch(init, &mut || false, &mut metrics).unwrap();
            // Old path: upload followed by individual ordered queue submission
            // and completion wait for every prepare and consumer dispatch.
            let old_dispatches = metrics.dispatches;
            let old_submissions = metrics.compute_queue_submissions;
            for offset in (0..PIXELS).step_by(256) {
                let (bytes, prepare, consume) = chunk(offset);
                runner
                    .upload_aux(0, &bytes, &mut || false, &mut metrics)
                    .unwrap();
                runner
                    .dispatch(prepare, &mut || false, &mut metrics)
                    .unwrap();
                runner
                    .dispatch(consume, &mut || false, &mut metrics)
                    .unwrap();
            }
            assert_eq!(metrics.dispatches - old_dispatches, 68);
            assert_eq!(metrics.compute_queue_submissions - old_submissions, 68);
            let serial = runner
                .read_source(0, 3 * PIXELS as u64 * 4, &mut || false, &mut metrics)
                .unwrap();
            runner.dispatch(init, &mut || false, &mut metrics).unwrap();
            let batch_dispatches = metrics.dispatches;
            let batch_submissions = metrics.compute_queue_submissions;
            let mut offset = 0;
            while offset < PIXELS {
                if offset == 4096 {
                    // Encode both commands, then cancel at the final pre-submit
                    // check. Neither output nor persistent MT may advance.
                    let (bytes, prepare, consume) = chunk(offset);
                    let mut cancelled = runner.batch();
                    cancelled
                        .submit_chunk(Some((0, &bytes)), prepare, &mut || false, &mut metrics)
                        .unwrap();
                    cancelled
                        .submit_chunk(None, consume, &mut || false, &mut metrics)
                        .unwrap();
                    let before = (metrics.dispatches, metrics.compute_queue_submissions);
                    let mut checks = 0;
                    assert!(
                        cancelled
                            .finish(
                                &mut || {
                                    checks += 1;
                                    checks == 4
                                },
                                &mut metrics
                            )
                            .is_err()
                    );
                    assert_eq!(checks, 4);
                    assert_eq!(
                        (metrics.dispatches, metrics.compute_queue_submissions),
                        before
                    );
                }
                let mut batch = runner.batch();
                for _ in 0..16 {
                    if offset >= PIXELS {
                        break;
                    }
                    let (bytes, prepare, consume) = chunk(offset);
                    batch
                        .submit_chunk(Some((0, &bytes)), prepare, &mut || false, &mut metrics)
                        .unwrap();
                    batch
                        .submit_chunk(None, consume, &mut || false, &mut metrics)
                        .unwrap();
                    offset += consume[5];
                }
                batch.finish(&mut || false, &mut metrics).unwrap();
            }
            assert_eq!(metrics.dispatches - batch_dispatches, 68);
            assert_eq!(metrics.compute_queue_submissions - batch_submissions, 3);
            let batched = runner
                .read_source(0, 3 * PIXELS as u64 * 4, &mut || false, &mut metrics)
                .unwrap();
            assert_eq!(batched, serial, "serial versus batched, seed={seed}");
            let expected =
                spektrafilm_model::grain::legacy_random_words(seed as u64, PIXELS as usize);
            for (i, (pixel, word)) in batched.chunks_exact(12).zip(expected).enumerate() {
                let lanes: Vec<_> = pixel
                    .chunks_exact(4)
                    .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                    .collect();
                assert_eq!(
                    lanes[0],
                    (word & 65535) as f32,
                    "low word seed={seed} index={i}"
                );
                assert_eq!(
                    lanes[1],
                    (word >> 16) as f32,
                    "high word seed={seed} index={i}"
                );
                assert_eq!(
                    lanes[2],
                    i as f32 + 0.25 + (i / 256 + 17) as f32,
                    "upload/uniform seed={seed} index={i}"
                );
            }
        }
    }
}

#[cfg(test)]
mod shader_family_tests {
    #[test]
    fn specialized_iir_binding_drift_rejected_before_recording() {
        for (op, source, destination) in [(2, 0, 1), (3, 1, 2)] {
            let mut p = [0; 40];
            p[2] = op;
            p[6] = source;
            p[7] = destination;
            assert!(super::validate_fixed_iir_params(true, &p).is_ok());
            p[6] = 4;
            assert!(super::validate_fixed_iir_params(true, &p).is_err());
            assert!(super::validate_fixed_iir_params(false, &p).is_ok());
            p[6] = source;
            p[7] = 4;
            assert!(super::validate_fixed_iir_params(true, &p).is_err());
        }
    }
    use super::*;
    #[test]
    fn family_routes_are_bounded_unique_and_fail_closed() {
        for count in 1..=8 {
            let families: Vec<_> = (0..count)
                .map(|i| (vec![i as u32], "shader".into()))
                .collect();
            let routes = validate_shader_families(&families).unwrap();
            for i in 0..count {
                assert_eq!(routes.get(&(i as u32)), Some(&i));
            }
            assert!(!routes.contains_key(&u32::MAX));
        }
        for bad in [
            vec![],
            vec![(vec![], "shader".into())],
            vec![(vec![0, 0], "shader".into())],
            vec![(vec![0], String::new())],
            vec![(vec![0], "x".repeat(65537))],
            vec![(vec![0], "shader".into()); 9],
            vec![(vec![0], "shader".into()), (vec![0], "shader".into())],
        ] {
            assert!(validate_shader_families(&bad).is_err());
        }
    }
    #[test]
    #[ignore = "explicit Vulkan pre-upload rejection probe; not mobile performance evidence"]
    fn actual_fixed_iir_route_rejection_precedes_serial_and_batch_uploads() {
        let shader = "@compute @workgroup_size(64) fn main() {}".to_string();
        let families = vec![(vec![2], shader.clone()), (vec![3], shader)];
        let plan = FilmOpticsPlan::new(&RuntimeParams::default(), 20., 16, 1).unwrap();
        let (runner, mut metrics) =
            Runner::new_with_fixed_iir_families(&plan, true, &families).unwrap();
        for (op, source, destination) in [(2, 0, 1), (3, 1, 2)] {
            for wrong_source in [true, false] {
                let mut p = params(16, 1, op);
                p[4] = 1;
                p[6] = if wrong_source { 4 } else { source };
                p[7] = if wrong_source { destination } else { 4 };
                let before = (
                    metrics.dispatches,
                    metrics.upload_bytes,
                    metrics.compute_queue_submissions,
                );
                assert_eq!(
                    runner.dispatch(p, &mut || false, &mut metrics).unwrap_err(),
                    "GPU_OPTICS_IIR_ROUTE"
                );
                let mut batch = runner.batch();
                assert_eq!(
                    batch
                        .submit_chunk(
                            Some((0, &1234f32.to_le_bytes())),
                            p,
                            &mut || false,
                            &mut metrics
                        )
                        .unwrap_err(),
                    "GPU_OPTICS_IIR_ROUTE"
                );
                assert!(batch.plan.uploads.is_empty() && batch.plan.commands.is_empty());
                assert_eq!(
                    (
                        metrics.dispatches,
                        metrics.upload_bytes,
                        metrics.compute_queue_submissions
                    ),
                    before
                );
            }
        }
    }
    #[test]
    #[ignore = "explicit Vulkan family routing probe; not mobile performance evidence"]
    fn actual_family_switches_share_buffers_and_reject_unknown_before_upload() {
        let shader = |increment: u32| {
            format!(
                r#"
struct Data {{ values: array<f32>, }}
struct Params {{ u: array<vec4<u32>, 10>, }}
@group(0) @binding(0) var<storage, read_write> source: Data;
@group(0) @binding(5) var<storage, read_write> probe: Data;
@group(0) @binding(6) var<uniform> p: Params;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid:vec3<u32>) {{
 if(gid.x != 0u) {{return;}}
 source.values[p.u[1].w] = probe.values[0] + f32({increment}u) + f32(p.u[2].x);
}}
"#
            )
        };
        let families = vec![(vec![63], shader(1)), (vec![52], shader(10))];
        let plan = FilmOpticsPlan::new(&RuntimeParams::default(), 20., 16, 1).unwrap();
        let (runner, mut metrics) =
            Runner::new_with_shader_family_error_explicit_layout(&plan, true, &families).unwrap();
        assert_eq!(metrics.pipeline_compile_ms.len(), 2);
        let mut batch = runner.batch();
        let mut bad = params(16, 1, u32::MAX);
        bad[4] = 1;
        assert_eq!(
            batch
                .submit_chunk(
                    Some((0, &1234f32.to_le_bytes())),
                    bad,
                    &mut || false,
                    &mut metrics
                )
                .unwrap_err(),
            "GPU_OPTICS_UNKNOWN_OPCODE"
        );
        assert!(batch.plan.uploads.is_empty() && batch.plan.commands.is_empty());
        assert_eq!(metrics.upload_bytes, 0);
        for i in 0..16u32 {
            let mut p = params(16, 1, if i % 2 == 0 { 63 } else { 52 });
            p[4] = 1;
            p[7] = i;
            p[8] = i;
            batch
                .submit_chunk(
                    Some((0, &(i as f32 * 2.).to_le_bytes())),
                    p,
                    &mut || false,
                    &mut metrics,
                )
                .unwrap();
        }
        batch.finish(&mut || false, &mut metrics).unwrap();
        assert_eq!(metrics.compute_queue_submissions, 1);
        let actual = runner
            .read_source(0, 64, &mut || false, &mut metrics)
            .unwrap();
        for (i, b) in actual.chunks_exact(4).enumerate() {
            assert_eq!(
                f32::from_le_bytes(b.try_into().unwrap()),
                3. * i as f32 + if i % 2 == 0 { 1. } else { 10. }
            );
        }
        let before = (
            metrics.dispatches,
            metrics.upload_bytes,
            metrics.compute_queue_submissions,
        );
        assert_eq!(
            runner
                .dispatch(bad, &mut || false, &mut metrics)
                .unwrap_err(),
            "GPU_OPTICS_UNKNOWN_OPCODE"
        );
        assert_eq!(
            (
                metrics.dispatches,
                metrics.upload_bytes,
                metrics.compute_queue_submissions
            ),
            before
        );
    }
}

#[cfg(test)]
mod grain_scratch_tests {
    use super::*;

    #[test]
    fn frozen_layout_has_exact_code_offsets_and_frame_stride() {
        assert_eq!(93 * 16, 1488);
        assert_eq!(207 * 16, 3312);
        assert_eq!(GRAIN_CODE_PREFIX_BYTES, (93 + 207) * 16);
        assert_eq!(GRAIN_FRAME_BYTES, 72 * 4 + 32 * 8);
        for (capacity, bytes) in [(256, 144064), (16384, 8917696)] {
            let config = GrainScratchConfig::new(capacity).unwrap();
            assert_eq!(config.frame_capacity(), capacity);
            assert_eq!(config.device_bytes(), bytes);
            assert_eq!(config.code_prefix.len() as u64, GRAIN_CODE_PREFIX_BYTES);
            assert!((config.code_prefix.len() as u64) < config.device_bytes());
            crate::research_grain::alu_programs::validate_prefix(config.code_prefix).unwrap();
        }
        for capacity in [0, 1, 255, 257, 16383, 16385, u32::MAX] {
            assert!(GrainScratchConfig::new(capacity).is_err());
        }
    }

    #[test]
    fn grain_binding_is_read_write_and_finish_binding_stays_read_only() {
        let entries = numeric_error_grain_bindings();
        assert_eq!(&entries[..8], &numeric_error_bindings());
        assert_eq!(entries.len(), 9);
        assert_eq!(entries[8].binding, 8);
        assert_eq!(
            entries[8].ty,
            wgpu::BindingType::Buffer {
                ty: wgpu::BufferBindingType::Storage { read_only: false },
                has_dynamic_offset: false,
                min_binding_size: wgpu::BufferSize::new(5344),
            }
        );
        assert_ne!(entries[8].ty, numeric_error_table_bindings()[8].ty);
        assert_eq!(
            entries
                .iter()
                .filter(|entry| matches!(
                    entry.ty,
                    wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Storage { .. },
                        ..
                    }
                ))
                .count(),
            8
        );
        assert_eq!(Binding8Config::Absent.device_bytes(), 0);
        assert_eq!(Binding8Config::FinishTables(&[1., 2.]).device_bytes(), 8);
    }

    #[test]
    fn grain_geometry_rejects_invalid_counts_before_recording() {
        for capacity in [256, 16384] {
            // Synthetic census geometry is independent of a one-pixel image.
            let mut p = params(1, 1, 63);
            for op in [63, 64, 65, 66] {
                p[2] = op;
                for count in [1, 63, 64, 65, 255, 256, capacity] {
                    p[4] = count;
                    p[5] = count;
                    validate_grain_dispatch(capacity, &p).unwrap();
                }
                for count in [0, capacity + 1, u32::MAX] {
                    p[4] = count;
                    p[5] = count;
                    assert!(validate_grain_dispatch(capacity, &p).is_err());
                }
                p[4] = 1;
                p[5] = 2;
                assert!(validate_grain_dispatch(capacity, &p).is_err());
            }
            for op in [52, 53] {
                p[2] = op;
                p[4] = 1;
                p[5] = 256;
                validate_grain_dispatch(capacity, &p).unwrap();
                p[4] = 2;
                assert!(validate_grain_dispatch(capacity, &p).is_err());
                p[4] = 1;
                for count in [0, 257] {
                    p[5] = count;
                    assert!(validate_grain_dispatch(capacity, &p).is_err());
                }
            }
            p[2] = 62;
            p[4] = 256;
            p[5] = 0;
            validate_grain_dispatch(capacity, &p).unwrap();
            p[4] = capacity + 1;
            assert!(validate_grain_dispatch(capacity, &p).is_err());
        }
    }

    #[test]
    fn explicit_binomial_prepare_route_keeps_unknown_op_rejection() {
        let routes = validate_shader_families(&[
            (vec![63], "prepare poisson".into()),
            (vec![66], "prepare binomial".into()),
        ])
        .unwrap();
        assert_eq!(routes.get(&66), Some(&1));
        assert!(!routes.contains_key(&61));
        assert!(
            validate_shader_families(&[(vec![66], "a".into()), (vec![66], "b".into())]).is_err()
        );
    }
}
