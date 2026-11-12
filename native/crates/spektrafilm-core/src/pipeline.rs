/// 3-stage simulation pipeline: Filming → Printing → Scanning.
///
/// Full calibration chain:
///   1. Load spectra LUT → compute TC LUT for film sensitivity
///   2. Process virtual gray card through filming to get midgray spectral density
///   3. Compute print exposure normalization factor from midgray spectral density
///   4. Pass all calibration data to the pipeline stages
use std::path::Path;
use std::sync::Arc;
use std::time::Instant;

use spektrafilm_gpu::ComputeBackend;
use spektrafilm_math::image::ImageBuf;
use spektrafilm_math::spectral::TcLut;

/// Diagnostic helper: when env var `$var` is set, dump the image's f64
/// pixel data to that path as a raw little-endian f64 blob. Used to
/// bisect the Python ↔ Rust drift one stage at a time without
/// modifying call sites. Silent no-op when the env var is unset.
fn dump_if_env(var: &str, image: &ImageBuf) {
    let Ok(path) = std::env::var(var) else {
        return;
    };
    let bytes: Vec<u8> = image
        .data
        .iter()
        .flat_map(|&v| (v as f64).to_le_bytes())
        .collect();
    match std::fs::write(&path, &bytes) {
        Ok(()) => tracing::info!(
            path = %path,
            count = image.data.len(),
            "dumped f64 buffer for {}",
            var
        ),
        Err(e) => tracing::error!("dump {var} → {path}: {e}"),
    }
}

fn stage_timings_enabled() -> bool {
    std::env::var_os("SPEKTRAFILM_STAGE_TIMINGS").is_some()
}

fn print_stage_timing(enabled: bool, stage: &str, start: Instant) {
    if enabled {
        eprintln!("stage {stage}: {} ms", start.elapsed().as_millis());
    }
}

use crate::enlarger;
use crate::params::RuntimeParams;
use crate::profile::Profile;
use crate::stages;
#[path = "pipeline_omitted.rs"]
mod omitted;

fn apply_film_specific_params(film: &Profile, params: &mut RuntimeParams) {
    if film.is_positive() {
        params.film_render.dir_couplers.gamma_samelayer_rgb = [0.12, 0.08, 0.06];
        params.film_render.dir_couplers.gamma_interlayer_r_to_gb = [0.12, 0.06];
        params.film_render.dir_couplers.gamma_interlayer_g_to_rb = [0.08, 0.06];
        params.film_render.dir_couplers.gamma_interlayer_b_to_rg = [0.06, 0.06];
    } else if film.is_negative() {
        params.film_render.dir_couplers.gamma_samelayer_rgb = [0.336, 0.319, 0.273];
        params.film_render.dir_couplers.gamma_interlayer_r_to_gb = [0.353, 0.302];
        params.film_render.dir_couplers.gamma_interlayer_g_to_rb = [0.154, 0.353];
        params.film_render.dir_couplers.gamma_interlayer_b_to_rg = [0.168, 0.226];
    }

    // Monochrome grain is derived from the film, never user-set — clear it
    // for colour so a stray params file can't correlate the colour channels'
    // noise.
    params.film_render.grain.monochrome = film.is_bw();
    if film.is_bw() {
        // Upstream n_channels==1 semantics (`match_channels` keeps channel 0
        // of every per-channel tuple; the DIR matrix is 1×1 self-inhibition
        // with no interlayer terms). Forcing the arrays to channel-0 keeps the
        // broadcast 3-channel engine numerically identical to the upstream
        // single-channel run.
        let dir = &mut params.film_render.dir_couplers;
        dir.gamma_samelayer_rgb = [dir.gamma_samelayer_rgb[0]; 3];
        dir.gamma_interlayer_r_to_gb = [0.0, 0.0];
        dir.gamma_interlayer_g_to_rb = [0.0, 0.0];
        dir.gamma_interlayer_b_to_rg = [0.0, 0.0];
        let g = &mut params.film_render.grain;
        g.agx_particle_scale = [g.agx_particle_scale[0]; 3];
        g.density_min = [g.density_min[0]; 3];
        g.uniformity = [g.uniformity[0]; 3];
        let h = &mut params.film_render.halation;
        h.halation_strength = [h.halation_strength[0]; 3];
        h.scatter_core_um = [h.scatter_core_um[0]; 3];
        h.scatter_tail_um = [h.scatter_tail_um[0]; 3];
        h.scatter_tail_weight = [h.scatter_tail_weight[0]; 3];
        h.halation_first_sigma_um = [h.halation_first_sigma_um[0]; 3];
    }
}

/// Canonical exact key: serde promotes f32 losslessly to f64 in Value. Every
/// floating Number is replaced by its bits. Null (including nonfinite JSON
/// floats) is rejected; callers express legitimate optional values as arrays.
fn film_log_exact_key(mut value: serde_json::Value) -> Option<String> {
    fn encode(value: &mut serde_json::Value) -> Option<()> {
        match value {
            serde_json::Value::Null => return None,
            serde_json::Value::Number(number) if number.is_f64() => {
                let value_bits = number.as_f64()?.to_bits();
                *value = serde_json::json!({"floatBits": value_bits});
            }
            serde_json::Value::Array(values) => for value in values { encode(value)?; },
            serde_json::Value::Object(values) => for value in values.values_mut() { encode(value)?; },
            _ => {},
        }
        Some(())
    }
    encode(&mut value)?;
    serde_json::to_string(&value).ok()
}

fn film_log_front_identity(film: &Profile, params: &RuntimeParams, spectral: &crate::prepared_spectral::PreparedSpectral) -> Option<String> {
    // Capture at construction, not from a later with_params view. NaN log
    // sensitivity is intentionally the core's zero-sensitivity convention.
    let sensitivity: Vec<_> = film.log_sensitivity_f64().iter().map(|row| row.map(|v| {
        let value = 10.0f64.powf(v); if value.is_nan() { 0.0 } else { value }
    })).collect();
    if sensitivity.iter().flatten().any(|v| !v.is_finite()) { return None; }
    let sensitivity: Vec<_> = sensitivity.iter().map(|row| row.map(f64::to_bits)).collect();
    film_log_exact_key(serde_json::json!({
        "actualFrontIlluminant": spectral.front_illuminant,
        "lutShape": spectral.tc_lut.as_ref().map(|lut| [lut.size,lut.channels,lut.data.len()]).into_iter().collect::<Vec<_>>(),
        "mallettCore": spectral.mallett_core.into_iter().collect::<Vec<_>>(),
        "stock": film.info.stock.as_deref().unwrap_or(""),
        "profileVersion": film.metadata.version,
        "filmType": film.info.film_type, "sensitivity": sensitivity,
        "referenceIlluminant": film.info.reference_illuminant,
        "window": film.data.hanatos2025_adaptation_window_params,
        "inputGamut": params.io.input_gamut_compress,
        "method": params.settings.rgb_to_raw_method,
        "applyWindow": params.settings.apply_hanatos2025_adaptation_window,
        "applySurface": params.settings.apply_hanatos2025_adaptation_surface,
        "spectralBlur": params.settings.spectral_gaussian_blur,
    }))
}

#[derive(Clone)]
pub struct Pipeline {
    pub film: Profile,
    pub print: Profile,
    pub params: RuntimeParams,
    pub donor_grain: Option<Arc<crate::donor_grain::DonorGrain>>,
    omitted_calibration: Option<Arc<omitted::Calibration>>,
    /// Shared immutable front data; the rest of this pipeline is recalibrated.
    spectral: Arc<crate::prepared_spectral::PreparedSpectral>,
    /// Constructor-captured provenance survives with_params without pretending to rebuild front assets.
    film_log_front_identity: Option<String>,
    /// Print exposure normalization factor (1/geomean of midgray raw through enlarger).
    print_exposure_factor: f64,
    /// Filtered enlarger illuminant for printing stage (f64 for Python parity).
    print_illuminant: Vec<f64>,
    /// Constant preflash raw 3-vector added to the print exposure before the
    /// inner log10. `[0; 3]` unless the enlarger preflash is active.
    preflash_raw: [f64; 3],
    /// Output gamut compressor (built once; identity unless oklch is enabled).
    output_gamut: crate::gamut_compression::OutputGamutCompress,
}

/// Prepared pointwise negative-density -> print -> RGB chain for disk exports.
/// No per-chunk LUT/calibration construction and no frame-sized allocation.
pub struct PreparedStreamedFinish {
    printing: Option<stages::printing::PreparedPrinting>,
    scanning: Option<stages::scanning::PreparedScanner>,
    omitted: Option<Pipeline>,
    pub filming_exposure_correction: f64,
}
impl PreparedStreamedFinish {
    pub fn process_density_chunk(&self,density:&ImageBuf,backend:&dyn ComputeBackend)->ImageBuf {
        if self.omitted.is_none() {
            let scan=self.scanning.as_ref().expect("physical scanner");
            return match &self.printing {Some(printing)=>scan.process(&printing.process(density,backend)),None=>scan.process(density)};
        }
        let mut rgb=self.process_density_chunk_unclipped(density,backend);
        for value in &mut rgb.data {*value=(*value).clamp(spektrafilm_math::precision::from_f64(0.),spektrafilm_math::precision::from_f64(1.));}
        rgb
    }
    pub fn process_density_chunk_unclipped(&self,density:&ImageBuf,backend:&dyn ComputeBackend)->ImageBuf {
        if let Some(pipe)=&self.omitted {
            if pipe.film.is_omitted() && !pipe.print.is_omitted() {
                let raw=pipe.expose_omitted_paper_raw(density);
                let developed=pipe.develop_omitted_paper_raw(&raw,backend);
                return self.scanning.as_ref().expect("paper scanner").process_unclipped(&developed);
            }
            if let Some(scan)=&self.scanning{return scan.process_unclipped(density);}
            return pipe.omitted_density_to_rgb(density,backend,false);
        }
        let scan=self.scanning.as_ref().expect("physical scanner");
        match &self.printing {Some(printing)=>scan.process_unclipped(&printing.process(density,backend)),None=>scan.process_unclipped(density)}
    }
    pub fn process_density_chunk_unclipped_with_glare(&self,density:&ImageBuf,field:&[spektrafilm_math::precision::Scalar],backend:&dyn ComputeBackend)->Result<ImageBuf,String> {
        if let Some(pipe)=&self.omitted {
            if pipe.print.is_omitted(){return Err("Glare requires a physical scanning medium".into());}
            let raw=pipe.expose_omitted_paper_raw(density);
            return self.scanning.as_ref().ok_or("Missing paper scanner")?.process_unclipped_with_glare(&pipe.develop_omitted_paper_raw(&raw,backend),field);
        }
        let scan=self.scanning.as_ref().ok_or("Missing scanner")?;
        match &self.printing {Some(printing)=>scan.process_unclipped_with_glare(&printing.process(density,backend),field),None=>scan.process_unclipped_with_glare(density,field)}
    }
    pub fn expose_print_raw_chunk(&self,density:&ImageBuf)->Result<ImageBuf,String> {
        if let Some(pipe)=&self.omitted {if !pipe.print.is_omitted(){return Ok(pipe.expose_omitted_paper_raw(density));}}
        self.printing.as_ref().map(|p|p.expose_raw(density)).ok_or_else(||"Print raw is unavailable without paper".into())
    }
    pub fn develop_print_raw_chunk(&self,raw:&ImageBuf,backend:&dyn ComputeBackend)->Result<ImageBuf,String> {
        if let Some(pipe)=&self.omitted {if !pipe.print.is_omitted(){return Ok(pipe.develop_omitted_paper_raw(raw,backend));}}
        self.printing.as_ref().map(|p|p.develop_raw(raw,backend)).ok_or_else(||"Print development is unavailable without paper".into())
    }
    pub fn scan_print_density_chunk_unclipped(&self,density:&ImageBuf)->Result<ImageBuf,String> {
        if self.printing.is_none() && !self.omitted.as_ref().is_some_and(|p|!p.print.is_omitted()){return Err("Paper scanning is unavailable without paper".into());}
        Ok(self.scanning.as_ref().ok_or("Missing paper scanner")?.process_unclipped(density))
    }
    pub fn scan_print_density_chunk_unclipped_with_glare(&self,density:&ImageBuf,field:&[spektrafilm_math::precision::Scalar])->Result<ImageBuf,String> {
        if self.printing.is_none() && !self.omitted.as_ref().is_some_and(|p|!p.print.is_omitted()){return Err("Paper scanning is unavailable without paper".into());}
        self.scanning.as_ref().ok_or("Missing paper scanner")?.process_unclipped_with_glare(density,field)
    }
    pub fn lut_storage_bytes(&self)->usize {
        self.scanning.as_ref().map_or(0,|p|p.lut_storage_bytes())+self.printing.as_ref().map_or(0,|p|p.lut_storage_bytes())
    }
}

impl Pipeline {
    pub fn prepare_streamed_finish(&self,backend:&dyn ComputeBackend)->Result<PreparedStreamedFinish,String> {
        self.prepare_streamed_finish_impl(backend,false)
    }
    /// Explicit opt-in: external exporter owns global enlarger diffusion.
    pub fn prepare_streamed_finish_spatial(&self,backend:&dyn ComputeBackend)->Result<PreparedStreamedFinish,String> {
        self.prepare_streamed_finish_impl(backend,true)
    }
    fn prepare_streamed_finish_impl(&self,backend:&dyn ComputeBackend,spatial:bool)->Result<PreparedStreamedFinish,String> {
        if self.film.is_omitted() || self.print.is_omitted() {
            let mut pipe=self.clone();pipe.params.scanner.lens_blur=0.;pipe.params.scanner.unsharp_mask=[0.,0.];
            let profile=if !pipe.print.is_omitted(){Some(&pipe.print)}else if pipe.film.is_positive(){Some(&pipe.film)}else{None};
            let scanning=if let Some(profile)=profile {
                let mut p=pipe.params.clone();p.io.scan_film=pipe.print.is_omitted();
                Some(stages::scanning::PreparedScanner::new_streamed(profile,&p,&crate::color_reference::ColorReference::identity(),&pipe.output_gamut)?)
            }else{None};
            return Ok(PreparedStreamedFinish{printing:None,scanning,omitted:Some(pipe),filming_exposure_correction:1.});
        }
        let color_ref=crate::color_reference::ColorReference::compute(&self.film,&self.print,&self.params,&self.print_illuminant,self.print_exposure_factor);
        let printing=if self.params.io.scan_film {None}else{
            let prepare=if spatial {stages::printing::PreparedPrinting::new_streamed_spatial}else{stages::printing::PreparedPrinting::new};
            Some(prepare(&self.film,&self.print,&self.params,backend,&self.print_illuminant,self.print_exposure_factor,self.preflash_raw,color_ref.printing_exposure_correction)?)
        };
        let profile=if self.params.io.scan_film {&self.film}else{&self.print};
        // Sharpening is a global spatial pass owned by the disk exporter, before clipping.
        let mut scanner_params=self.params.clone();scanner_params.scanner.unsharp_mask=[0.,0.];scanner_params.scanner.lens_blur=0.;
        let scanning=stages::scanning::PreparedScanner::new_streamed(profile,&scanner_params,&color_ref,&self.output_gamut)?;
        Ok(PreparedStreamedFinish {printing,scanning:Some(scanning),omitted:None,filming_exposure_correction:color_ref.filming_exposure_correction})
    }
    /// Reference-illuminant input for the existing Hanatos raw projection.
    pub fn front_illuminant_spd(&self)->&'static [f32] {select_illuminant(&self.spectral.front_illuminant)}
}

impl Pipeline {
    /// Display-only lower-resolution physical film simulation, not a sample
    /// of the full-resolution export. Caller supplies linear pixels from that
    /// same input and the already-metered export pipeline. No GPU is used.
    /// Optics/DIR retain physical units; only the pixel pitch is scaled and
    /// grain is omitted to match the existing stage-4 negative semantics.
    pub fn low_resolution_pre_grain_density_cpu(
        &self, image: &ImageBuf, full_width: u32, full_height: u32,
        keep_going: &mut dyn FnMut() -> bool,
    ) -> Result<ImageBuf, String> {
        if !keep_going() { return Err("Preview cancelled".into()); }
        if image.width == 0 || image.height == 0 || image.width > 256 || image.height > 256
            || full_width < image.width || full_height < image.height
            || image.data.len() != image.width as usize * image.height as usize * 3
            || image.data.iter().any(|v| !v.is_finite()) {
            return Err("Invalid early negative dimensions or input".into());
        }
        if self.film.info.film_type != "negative"
            || (self.params.camera.auto_exposure && !self.params.camera.preview_metered_ev.is_some_and(|ev|ev.is_finite() && 2.0f32.powf(ev).is_finite())) {
            return Err("Early negative requires resolved negative film and actual export AE".into());
        }
        // expose() currently derives its global highlight maximum from the
        // supplied image. Do not quietly substitute a thumbnail maximum.
        if (self.params.film_render.halation.boost_ev != 0.0 || self.params.film_render.halation.headroom_active()) {
            return Err("Early negative needs unavailable full-image highlight maximum".into());
        }
        let full_pitch = self.params.camera.pixel_size_um_override.unwrap_or_else(||
            stages::filming::pixel_size_um(self.params.camera.film_format_mm, full_width, full_height));
        let pitch = full_pitch * full_width.max(full_height) as f32 / image.width.max(image.height) as f32;
        if !pitch.is_finite() || pitch <= 0.0 { return Err("Invalid early negative pixel pitch".into()); }
        let mut params = self.params.clone();
        params.camera.pixel_size_um_override = Some(pitch);
        params.film_render.grain.active = false;
        let color_ref = crate::color_reference::ColorReference::compute(
            &self.film, &self.print, &self.params, &self.print_illuminant, self.print_exposure_factor);
        let backend = spektrafilm_gpu::cpu_backend::CpuBackend;
        let log_raw = stages::filming::expose(image, &self.film, &params, &backend,
            self.spectral.tc_lut.as_ref(), self.spectral.mallett_core.as_ref(), select_illuminant(&self.spectral.front_illuminant),
            color_ref.filming_exposure_correction);
        if !keep_going() { return Err("Preview cancelled".into()); }
        let density = stages::filming::develop(&log_raw, &self.film, &params, &backend);
        if !keep_going() { return Err("Preview cancelled".into()); }
        if density.data.iter().any(|v| !v.is_finite()) { return Err("Nonfinite early negative density".into()); }
        Ok(density)
    }

    /// Accessor for the pre-computed TC LUT (used by parity tests).
    pub fn tc_lut(&self) -> Option<&TcLut> {
        self.spectral.tc_lut.as_ref()
    }
    /// Accessor for the print exposure factor (parity tests).
    pub fn print_exposure_factor(&self) -> f64 {
        self.print_exposure_factor
    }
    /// Accessor for the filtered print illuminant (parity tests).
    pub fn print_illuminant_slice(&self) -> &[f64] {
        &self.print_illuminant
    }
    /// Accessor for the constant preflash raw vector (parity tests / debug).
    pub fn preflash_raw(&self) -> [f64; 3] {
        self.preflash_raw
    }

    /// Return a copy of this calibrated pipeline with updated runtime params.
    /// Callers must only use this when the calibration-affecting inputs match
    /// the pipeline that was originally built.
    pub fn with_params(mut self, params: RuntimeParams) -> Self {
        let mut params = params;
        apply_film_specific_params(&self.film, &mut params);
        Self::disable_absent_medium_controls(&self.film, &self.print, &mut params);
        self.output_gamut = crate::gamut_compression::OutputGamutCompress::build(
            &params.io.output_gamut_compress,
            &params.io.output_color_space,
        );
        self.params = params;
        self
    }
}

/// Build sublayer curve/particle data from the CPU model (`apply_grain_layers`)
/// for the approximate GPU pass. Returns `None` so the caller rejects the
/// resident attempt when the requested profile's layer curves are
/// malformed or degenerate (zero/negative total density or particle count).
fn build_grain_sublayers(
    film: &Profile,
    g: &crate::params::GrainParams,
    pixel_area: f32,
    _pix_um: f32,
) -> Option<spektrafilm_gpu::GrainSublayersGpu> {
    let norm_curves =
        spektrafilm_model::density_curves::normalize_density_curves_f64(&film.density_curves_f64());
    let layers = &film.data.density_curves_layers;
    let n_points = norm_curves.len();
    if n_points < 2 || layers.len() != n_points {
        return None;
    }
    // Every curve row must be 3 sublayers × 3 channels.
    if layers
        .iter()
        .any(|row| row.len() != 3 || row.iter().any(|sl| sl.len() != 3))
    {
        return None;
    }
    let sign: f64 = if film.is_positive() { -1.0 } else { 1.0 };
    // Per-sublayer max density per channel.
    let mut max_layers = [[0.0f64; 3]; 3];
    for row in layers.iter() {
        for sl in 0..3 {
            for ch in 0..3 {
                max_layers[sl][ch] = max_layers[sl][ch].max(row[sl][ch]);
            }
        }
    }
    // axis[point] = vec4(sign*curve[ch0], sign*curve[ch1], sign*curve[ch2], 0)
    let mut axis = Vec::with_capacity(n_points * 4);
    for row in &norm_curves {
        for ch in 0..3 {
            axis.push((sign * row[ch]) as f32);
        }
        axis.push(0.0);
    }
    // layers[point*3+sl] = vec4(layers[point][sl][ch0..ch2], 0)
    let mut layers_flat = Vec::with_capacity(n_points * 12);
    for row in layers.iter() {
        for sl in 0..3 {
            for ch in 0..3 {
                layers_flat.push(row[sl][ch] as f32);
            }
            layers_flat.push(0.0);
        }
    }
    // Per (channel, sublayer) particle params: (dmax, od_particle, particles, dmin).
    let mut sl_params = [[0.0f32; 4]; 9];
    for ch in 0..3 {
        let total: f64 = (0..3).map(|sl| max_layers[sl][ch]).sum();
        if !(total > 0.0) {
            return None;
        }
        for sl in 0..3 {
            let fraction = max_layers[sl][ch] / total;
            let dmin = fraction * g.density_min[ch];
            let dmax = max_layers[sl][ch] + dmin;
            let particles = pixel_area as f64 * fraction
                / (g.agx_particle_area_um2
                    * g.agx_particle_scale[ch]
                    * g.agx_particle_scale_layers[sl]);
            if !(particles > 0.0) || !(dmax > 0.0) {
                return None;
            }
            let od = dmax / particles;
            sl_params[ch * 3 + sl] = [dmax as f32, od as f32, particles as f32, dmin as f32];
        }
    }
    Some(spektrafilm_gpu::GrainSublayersGpu {
        sign: sign as f32,
        n_points: n_points as u32,
        axis,
        layers: layers_flat,
        sl_params,
    })
}

/// Global origin and geometry of a rectangular input tile, including halo.
#[derive(Debug, Clone, Copy)]
pub struct TileContext {
    pub full_width: u32,
    pub full_height: u32,
    pub x: u32,
    pub y: u32,
}

/// Per-band context for the banded GPU export
/// ([`Pipeline::process_gpu_resident_banded_f32`]). Carries the data the
/// GPU-resident chain needs to keep horizontal bands spatially consistent
/// with the whole-frame approximate model (not a bitwise guarantee).
#[derive(Debug, Clone, Copy)]
pub struct BandContext {
    /// Whole-frame row stride; zero preserves the legacy full-width band API.
    pub full_width: u32,
    /// Global pixel index of the first pixel of the (band + halo) chain
    /// input. The grain/glare shaders seed their per-pixel RNGs with this
    /// offset added to the band-local index, so the noise fields are
    /// continuous across bands.
    pub pixel_offset: u32,
    /// Auto-exposure EV metered once on the WHOLE image, injected into the
    /// band's exposure scale instead of re-metering on the cropped band
    /// (which would drift per band). `None` meters normally.
    pub ae_ev: Option<f64>,
}

/// Whole-frame diffusion scattered field (downsampled to 1/d and blurred),
/// precomputed once so the *banded* GPU chain can apply a diffusion filter
/// without banding the (non-bandable) large-radius blur: each band only
/// bilinear-upsamples its row window and mixes `(1-p_s)·img + p_s·field`.
/// Produced by [`Pipeline::diffusion_scattered_fields_gpu`].
#[derive(Debug, Clone)]
pub struct DiffusionScattered {
    /// Optional [x, y, width, height] crop in global small-pixel coordinates.
    pub crop: Option<[u32; 4]>,
    /// `small_w*small_h*3` interleaved f32, row-major — the blurred scattered
    /// field of the WHOLE frame at 1/d resolution.
    pub field: Vec<f32>,
    pub small_w: u32,
    pub small_h: u32,
    /// The downsample factor the field was computed at.
    pub d: u32,
    /// Scatter mix strength.
    pub p_s: f32,
}

impl DiffusionScattered {
    /// GPU view of the field for one band whose first row (halo included) is
    /// global row `band_y0`.
    fn to_gpu(&self, band_x0: u32, band_y0: u32) -> spektrafilm_gpu::DiffusionScatteredGpu<'_> {
        spektrafilm_gpu::DiffusionScatteredGpu {
            field: &self.field,
            small_w: self.small_w,
            small_h: self.small_h,
            d: self.d,
            p_s: self.p_s,
            band_y0,
            band_x0,
            crop: self.crop.unwrap_or([0, 0, self.small_w, self.small_h]),
        }
    }
}

/// The scattered fields of the two diffusion filters, for the banded chain.
/// A field is `None` when its filter is inactive (or resolved to a no-op).
#[derive(Debug, Clone, Default)]
pub struct DiffusionScatteredFields {
    /// Global maximum before highlight boost, shared by every band/prepass.
    pub highlight_max_raw: Option<f32>,
    pub camera: Option<DiffusionScattered>,
    pub enlarger: Option<DiffusionScattered>,
}

impl DiffusionScatteredFields {
    /// Main-pass field reservation after prepass temporaries have been dropped.
    /// Includes inline metadata, both host Vec capacities, and both GPU crop
    /// allocations plus upload staging, each conservatively bounded by its
    /// complete stored field. Crop upload borrows rows (no host crop Vec).
    /// GPU crops are charged here, independently of the tile bytes-per-pixel
    /// envelope. Allocator/driver overhead remains in the fixed export reserve.
    /// This does not replace the larger prepass Gaussian working-set estimate.
    pub fn retained_working_bytes(&self) -> Option<u64> {
        let mut bytes = u64::try_from(std::mem::size_of::<Self>()).ok()?;
        for field in [&self.camera, &self.enlarger].into_iter().flatten() {
            bytes = bytes.checked_add(Self::field_working_bytes(
                u64::try_from(field.field.capacity()).ok()?,
                u64::try_from(field.field.len()).ok()?)?)?;
        }
        Some(bytes)
    }

    fn field_working_bytes(capacity: u64, len: u64) -> Option<u64> {
        capacity.checked_mul(4)?.checked_add(len.checked_mul(8)?)
    }
}

impl Pipeline {
    /// Create pipeline without spectral LUT (simplified path).
    pub fn new(film: Profile, print: Profile, mut params: RuntimeParams) -> Self {
        // Only the previously unsupported monochrome path changes here.
        if film.is_bw() { apply_film_specific_params(&film, &mut params); }
        // B&W profiles must be resolved on every construction path —
        // an unresolved family would read development-time columns as
        // R/G/B channels.
        let film = crate::profile::resolve_for_render(film, params.film_render.development_time);
        let print = crate::profile::resolve_for_render(print, params.print_render.development_time);
        let print_illuminant = enlarger::enlarger_filtered_illuminant_f64(
            &params.enlarger.illuminant,
            params.enlarger.c_filter_neutral as f64,
            (params.enlarger.m_filter_neutral + params.enlarger.m_filter_shift) as f64,
            (params.enlarger.y_filter_neutral + params.enlarger.y_filter_shift) as f64,
        );
        let output_gamut = crate::gamut_compression::OutputGamutCompress::build(
            &params.io.output_gamut_compress,
            &params.io.output_color_space,
        );
        let front_illuminant = film.info.reference_illuminant.clone();
        Self {
            film,
            print,
            params,
            omitted_calibration: None,
            donor_grain: None,
            spectral: Arc::new(crate::prepared_spectral::PreparedSpectral::without_lut(front_illuminant)),
            film_log_front_identity: None,
            print_exposure_factor: 1.0,
            print_illuminant,
            preflash_raw: [0.0; 3],
            output_gamut,
        }
    }

    /// Create pipeline with full Hanatos2025 spectral upsampling and calibration.
    pub fn new_with_spectral(
        film: Profile,
        print: Profile,
        params: RuntimeParams,
        data_dir: &Path,
    ) -> Result<Self, String> {
        Self::new_with_spectral_impl(film, print, params, data_dir, None, &mut || true)
    }

    /// Opt-in per-owner front-asset reuse. An asset revision is verified by the
    /// caller's immutable installer contract; `None` deliberately takes cold path.
    /// Failed/cancelled calibration never publishes newly built prepared data.
    pub fn new_with_spectral_cached_controlled(
        film: Profile,
        print: Profile,
        params: RuntimeParams,
        data_dir: &Path,
        cache: &crate::prepared_spectral::PreparedSpectralCache,
        revision: Option<crate::prepared_spectral::SpectralAssetRevision>,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Result<Self, String> {
        let revision = revision.filter(|revision| revision.matches_root(data_dir));
        Self::new_with_spectral_impl(film, print, params, data_dir,
            revision.as_ref().map(|revision| (cache, revision)), &mut checkpoint)
    }

    fn new_with_spectral_impl(
        film: Profile,
        print: Profile,
        mut params: RuntimeParams,
        data_dir: &Path,
        prepared_cache: Option<(&crate::prepared_spectral::PreparedSpectralCache,
            &crate::prepared_spectral::SpectralAssetRevision)>,
        checkpoint: &mut dyn FnMut() -> bool,
    ) -> Result<Self, String> {
        if !checkpoint() { return Err("Pipeline preparation cancelled".into()); }
        if film.is_omitted() || print.is_omitted() {
            return Self::new_omitted(film, print, params, data_dir);
        }
        // B&W profiles: collapse the development-time family to the selected
        // time and broadcast the single channel onto the 3-channel engine
        // layout. Must happen before anything reads the profile data.
        let film = crate::profile::resolve_for_render(film, params.film_render.development_time);
        let print = crate::profile::resolve_for_render(print, params.print_render.development_time);

        // Python parity: mirror `_apply_film_specifics` in
        // `spektrafilm/runtime/params_builder.py`. Python applies these
        // overrides inside `digest_params()` before the pipeline runs,
        // so a fresh `RuntimeParams::default()` does NOT match what
        // Python uses. The DIR-coupler gammas in particular differ
        // between positive and negative films.
        apply_film_specific_params(&film, &mut params);

        // Python parity: look up per-(print, illuminant, film) neutral filter values from
        // the JSON database — matches `apply_database_neutral_print_filters`. Defaults to
        // params.enlarger.{c,m,y}_filter_neutral when the combo isn't in the database.
        // Keep the f64 lookup values around (params is f32) — narrowing to
        // f32 here costs ~4e-8 precision through the `10^(-cc/100)` step.
        let mut neutral_cmy_f64: Option<[f64; 3]> = None;
        if params.settings.neutral_print_filters_from_database {
            let db = crate::neutral_filters::NeutralFilters::load(data_dir);
            let print_stock = print.info.stock.as_deref().unwrap_or("");
            let film_stock = film.info.stock.as_deref().unwrap_or("");
            if let Some([c, m, y]) = db.lookup(print_stock, &params.enlarger.illuminant, film_stock)
            {
                params.enlarger.c_filter_neutral = c as f32;
                params.enlarger.m_filter_neutral = m as f32;
                params.enlarger.y_filter_neutral = y as f32;
                neutral_cmy_f64 = Some([c, m, y]);
            }
        }
        let cmy_f64 = neutral_cmy_f64.unwrap_or([
            params.enlarger.c_filter_neutral as f64,
            params.enlarger.m_filter_neutral as f64,
            params.enlarger.y_filter_neutral as f64,
        ]);
        let (c_neutral_f64, m_neutral_f64, y_neutral_f64) = (cmy_f64[0], cmy_f64[1], cmy_f64[2]);

        // Front assets and constructor calibration have independent exact keys.
        // Every downstream stage still uses this fresh resolved Pipeline state.
        let key = prepared_cache.and_then(|(cache, revision)| {
            crate::prepared_spectral::SpectralKey::new(&film, &params, revision.clone())
                .map(|key| (cache, key))
        });
        let (epoch, hit) = key.as_ref().map_or((0, None), |(cache, key)| cache.lookup(key));
        if !checkpoint() { return Err("Pipeline preparation cancelled".into()); }
        let was_hit = hit.is_some();
        let build_started = Instant::now();
        let spectral = match hit {
            Some(value) => value,
            None => Arc::new(crate::prepared_spectral::PreparedSpectral::build(
                &film, &params, data_dir, key.as_ref().map(|(_, key)| key.clone()),
            )?),
        };
        let build_ns = build_started.elapsed().as_nanos().min(u64::MAX as u128) as u64;
        if !checkpoint() { return Err("Pipeline preparation cancelled".into()); }
        // Python parity: sensitivities are pre-balanced in the profile so midgray ≈ 1.0.
        // The TC LUT is used unnormalized — Python's `rgb_to_raw_hanatos2025` does NOT scale it.
        // See `spektrafilm/utils/spectral_upsampling.py:rgb_to_raw_hanatos2025` (comment line 373).

        let calibration_key = key.as_ref().and_then(|(_, front)|
            crate::prepared_spectral::CalibrationKey::new(front, &film, &print, &params, cmy_f64));
        let (_, calibration_hit) = match (&key, &calibration_key) {
            (Some((cache, _)), Some(key)) => cache.lookup_calibration(key),
            _ => (0, None),
        };
        let calibration_was_hit = calibration_hit.is_some();
        let calibration_started = Instant::now();
        let calibration = match calibration_hit {
            Some(value) => value,
            None => {
                // Compute enlarger illuminant with dichroic filters — f64 for Python parity.
                // c/m/y come from the f64 lookup, shift values are f32 in params.
                let print_illuminant = enlarger::enlarger_filtered_illuminant_f64(
                    &params.enlarger.illuminant,
                    c_neutral_f64,
                    m_neutral_f64 + params.enlarger.m_filter_shift as f64,
                    y_neutral_f64 + params.enlarger.y_filter_shift as f64,
                );

                // Midgray sensor raw for the print-exposure normalization — always in
                // sRGB (Python `_rgb_to_film_raw` default), through whichever upsampler
                // is active. `scale` folds the EV compensation for the `_comp` branch.
                let midgray_raw = |scale: f64| -> [f64; 3] {
                    if let Some(lut) = &spectral.tc_lut {
                        enlarger::midgray_raw_hanatos(
                            lut,
                            select_illuminant(&spectral.front_illuminant),
                            params.settings.use_cat16,
                            scale,
                        )
                    } else {
                        let core = spectral.mallett_core
                            .as_ref()
                            .expect("one upsampler is always built");
                        let m = crate::mallett::film_matrix(core, "sRGB");
                        let g = 0.184 * scale;
                        crate::mallett::apply(&m, [g, g, g])
                    }
                };

                // Compute midgray spectral density (gray card through full filming path)
                let density_spectral_midgray =
                    enlarger::midgray_density_spectral_from_raw(midgray_raw(1.0), &film, &params);

                // Print sensitivity: Python `sensitivity = np.nan_to_num(10 ** log_sensitivity)` — f64 with NaN→0.
                let print_log_sens = print.log_sensitivity_f64();
                let print_sensitivity: Vec<[f64; 3]> = print_log_sens
                    .iter()
                    .map(|row| {
                        let mut out = [0.0f64; 3];
                        for c in 0..3 {
                            let v = 10.0f64.powf(row[c]);
                            out[c] = if v.is_nan() { 0.0 } else { v };
                        }
                        out
                    })
                    .collect();

                // Constant enlarger preflash exposure (Python `_compute_raw_preflash`):
                // film base density lit by the preflash-filtered illuminant, integrated
                // against the print sensitivity. Off (→ [0; 3]) unless preflash_exposure > 0.
                let preflash_raw = enlarger::compute_preflash_raw(
                    &params.enlarger,
                    c_neutral_f64,
                    m_neutral_f64,
                    y_neutral_f64,
                    &film.data.base_density,
                    &print_sensitivity,
                );

                // Print exposure normalization — mirror Python's
                // `_compute_exposure_factor_midgray` in
                // `spektrafilm/runtime/stages/printing.py`. There are two
                // candidate factors:
                //   * `factor_midgray`     = 1 / geomean(raw_midgray)
                //   * `factor_midgray_comp`= 1 / geomean(raw_midgray_with_neg_EV)
                // and four flag combinations of
                // `enlarger.normalize_print_exposure` × `enlarger.print_exposure_compensation`.
                //
                // With both flags ON (defaults) and no EV compensation,
                // Python returns `factor_midgray_comp == 1.0`. Rust used to
                // unconditionally apply `factor_midgray` here, which biased
                // the print exposure by ~3% on every render and was the root
                // cause of the residual Python-parity drift in the print stage.
                let factor_midgray = enlarger::compute_exposure_factor(
                    &density_spectral_midgray,
                    &print_illuminant,
                    &print_sensitivity,
                );
                // Python builds `density_spectral_midgray_comp` whenever
                // `print_exposure_compensation` is on — even when EV == 0, in
                // which case `rgb_midgray_comp = rgb_midgray * 2^0` and so
                // `factor_midgray_comp == factor_midgray`. We have to mirror
                // that (NOT short-circuit to 1.0) because the
                // `factor_midgray_comp` branch is what gets returned by default.
                let factor_midgray_comp = if !params.enlarger.print_exposure_compensation {
                    1.0
                } else if params.camera.exposure_compensation_ev == 0.0 {
                    factor_midgray
                } else {
                    // Python: `rgb_midgray_comp = rgb_midgray * 2 ** exposure_compensation_ev`.
                    let scale = 2.0f64.powf(params.camera.exposure_compensation_ev as f64);
                    let density_spectral_midgray_comp =
                        enlarger::midgray_density_spectral_from_raw(midgray_raw(scale), &film, &params);
                    enlarger::compute_exposure_factor(
                        &density_spectral_midgray_comp,
                        &print_illuminant,
                        &print_sensitivity,
                    )
                };
                let print_exposure_factor = match (
                    params.enlarger.normalize_print_exposure,
                    params.enlarger.print_exposure_compensation,
                ) {
                    (true, true) => factor_midgray_comp,
                    (true, false) => factor_midgray,
                    (false, true) => factor_midgray_comp / factor_midgray,
                    (false, false) => 1.0,
                };

                crate::prepared_spectral::PreparedCalibration { print_illuminant, print_exposure_factor, preflash_raw }
            }
        };

        let calibration_build_ns = calibration_started.elapsed().as_nanos().min(u64::MAX as u128) as u64;
        tracing::info!(
            method = params.settings.rgb_to_raw_method,
            lut_size = spectral.tc_lut.as_ref().map_or(0, |l| l.size),
            film = film.info.stock.as_deref().unwrap_or("unknown"),
            print_exposure_factor = calibration.print_exposure_factor,
            "pipeline calibrated"
        );

        let output_gamut = crate::gamut_compression::OutputGamutCompress::build(
            &params.io.output_gamut_compress,
            &params.io.output_color_space,
        );
        if !checkpoint() { return Err("Pipeline preparation cancelled".into()); }
        if let Some((cache, key)) = key {
            if !key.revision_is_current(&params.settings.rgb_to_raw_method) {
                return Err("Spectral assets changed during preparation".into());
            }
            if !was_hit { cache.publish(epoch, &spectral, build_ns); }
            if !calibration_was_hit {
                if let Some(key) = calibration_key {
                    // Use the original constructor ticket, not a newer calibration
                    // lookup epoch after pressure/retirement during front construction.
                    cache.publish_calibration(epoch, key, calibration.clone(), calibration_build_ns);
                }
            }
        }
        let film_log_front_identity = film_log_front_identity(&film, &params, &spectral);
        Ok(Self {
            film,
            print,
            params,
            omitted_calibration: None,
            donor_grain: None,
            spectral,
            film_log_front_identity,
            print_exposure_factor: calibration.print_exposure_factor,
            print_illuminant: calibration.print_illuminant,
            preflash_raw: calibration.preflash_raw,
            output_gamut,
        })
    }

    pub fn process(&self, image: ImageBuf, backend: &dyn ComputeBackend) -> ImageBuf {
        if self.film.is_omitted() || self.print.is_omitted() {
            return self.process_omitted(image, backend);
        }
        let stage_timings = stage_timings_enabled();
        // Working-resolution rescale (io.upscale_factor) — mirrors Python's
        // ResizingService.crop_and_rescale. Done first so the whole pipeline
        // (and the diffusion-filter kernel size, which scales with the image)
        // runs at the chosen resolution. pixel_size_um is derived per-stage
        // from the image dims, so it follows automatically.
        let t = Instant::now();
        let image = self.rescale_working(image);
        print_stage_timing(stage_timings, "rescale", t);
        tracing::info!(backend = backend.name(), "pipeline: start");

        // Scanner B&W/slide exposure correction (no-op unless scanner
        // white/black correction is on for a slide or print scan). Computed
        // up front so both the GPU-resident and per-stage paths share it.
        let t = Instant::now();
        let color_ref = crate::color_reference::ColorReference::compute(
            &self.film,
            &self.print,
            &self.params,
            &self.print_illuminant,
            self.print_exposure_factor,
        );
        print_stage_timing(stage_timings, "color_reference", t);

        // GPU fast path: dispatch the whole filming→printing→scanning chain
        // (or filming→scanning when scan_film) as a single GPU command
        // buffer — one upload + one readback total. CUDA and WGSL run the
        // extended resident chain, including camera/scanner lens blur,
        // highlight boost, and enlarger diffusion. Backends that do not
        // implement the resident chain return `None` and fall through.
        if self.spectral.tc_lut.is_some() || self.spectral.mallett_core.is_some() {
            if let Some(out) = self.try_gpu_resident(
                spektrafilm_gpu::ChainImage::F64(&image),
                backend,
                &color_ref,
                false,
                None,
                spektrafilm_gpu::ChainStop::Full,
                None,
            ) {
                tracing::info!("pipeline: gpu-resident fast path complete");
                if backend.resident_chain_applies_post_scan() {
                    return out;
                }
                return self.apply_post_scan(out);
            }
        }

        let t = Instant::now();
        let log_raw = stages::filming::expose(
            &image,
            &self.film,
            &self.params,
            backend,
            self.spectral.tc_lut.as_ref(),
            self.spectral.mallett_core.as_ref(),
            select_illuminant(&self.spectral.front_illuminant),
            color_ref.filming_exposure_correction,
        );
        drop(image); // input is no longer needed; important for bounded mobile memory
        print_stage_timing(stage_timings, "filming_expose", t);
        dump_if_env("SPEKTRAFILM_DUMP_FILM_LOG_RAW", &log_raw);
        let t = Instant::now();
        let filmed = stages::filming::develop(&log_raw, &self.film, &self.params, backend);
        drop(log_raw);
        print_stage_timing(stage_timings, "filming_develop", t);
        tracing::info!("pipeline: filming complete");
        dump_if_env("SPEKTRAFILM_DUMP_FILM_DENSITY", &filmed);

        if self.params.io.scan_film {
            let t = Instant::now();
            let result = stages::scanning::process(
                &filmed,
                &self.film,
                &self.params,
                backend,
                &color_ref,
                &self.output_gamut,
            );
            print_stage_timing(stage_timings, "scanning", t);
            tracing::info!("pipeline: scanning complete (film scan)");
            result
        } else {
            let t = Instant::now();
            let printed = stages::printing::process_with_calibration(
                &filmed,
                &self.film,
                &self.print,
                &self.params,
                backend,
                &self.print_illuminant,
                self.print_exposure_factor,
                self.preflash_raw,
                color_ref.printing_exposure_correction,
            );
            drop(filmed);
            print_stage_timing(stage_timings, "printing", t);
            tracing::info!("pipeline: printing complete");
            dump_if_env("SPEKTRAFILM_DUMP_PRINT_DENSITY", &printed);
            let t = Instant::now();
            let result = stages::scanning::process(
                &printed,
                &self.print,
                &self.params,
                backend,
                &color_ref,
                &self.output_gamut,
            );
            print_stage_timing(stage_timings, "scanning", t);
            tracing::info!("pipeline: scanning complete");
            result
        }
    }

    /// Run only the GPU-resident path from a borrowed image. This is used by
    /// the live GUI when it has already decided the working resolution, so a
    /// full-resolution preview does not need to deep-clone the loaded image
    /// just to satisfy `process(ImageBuf)`.
    pub fn process_resident_borrowed(
        &self,
        image: &ImageBuf,
        backend: &dyn ComputeBackend,
    ) -> Option<ImageBuf> {
        self.process_resident_borrowed_controlled(image, backend, || true)
    }

    /// Controlled preview. Checkpoints run outside GPU ownership and may
    /// reject stale work before submission or discard its completed result.
    /// Already-submitted GPU work drains under the backend's bounded wait.
    pub fn process_resident_borrowed_controlled(
        &self,
        image: &ImageBuf,
        backend: &dyn ComputeBackend,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() { return None; }
        if self.spectral.tc_lut.is_none() && self.spectral.mallett_core.is_none() {
            return None;
        }
        tracing::info!(backend = backend.name(), "pipeline: borrowed resident start");
        let color_ref = crate::color_reference::ColorReference::compute(
            &self.film,
            &self.print,
            &self.params,
            &self.print_illuminant,
            self.print_exposure_factor,
        );
        if !checkpoint() { return None; }
        let out = self.try_gpu_resident_controlled(
            spektrafilm_gpu::ChainImage::F64(image),
            backend,
            &color_ref,
            false,
            None,
            spektrafilm_gpu::ChainStop::Full,
            None,
            &mut checkpoint,
        )?;
        if !checkpoint() { return None; }
        let out = if backend.resident_chain_applies_post_scan() { out }
            else { self.apply_post_scan(out) };
        if !checkpoint() { return None; }
        Some(out)
    }

    /// GPU-approximate export path retaining the f64 CPU gamut compressor.
    ///
    /// Runs the whole film chain on the GPU (diffusion, scatter/halation,
    /// density, grain, print/scan) but defers output gamut compression and the
    /// final clamp+sRGB encode to the CPU. Only this boundary retains the
    /// f64 gamut calculation; the complete result remains GPU-approximate.
    /// Returns `None` when the resident chain is unavailable (caller falls back
    /// to the fully-CPU streamed path).
    pub fn process_gpu_resident_cpu_gamut(
        &self,
        image: ImageBuf,
        backend: &dyn ComputeBackend,
    ) -> Option<ImageBuf> {
        self.process_gpu_resident_cpu_gamut_input(
            spektrafilm_gpu::ChainImage::F64(&image),
            backend,
            None,
            None,
        )
    }

    /// f32-direct variant of [`Self::process_gpu_resident_cpu_gamut`] for the
    /// streamed export fast path: the spool already holds interleaved RGB f32
    /// (exactly the GPU upload layout), so the chain runs without
    /// materializing an n*24-byte f64 working image. The caller must fold
    /// auto-exposure into the exposure compensation first — an f32 input has
    /// no f64 image to meter on.
    pub fn process_gpu_resident_cpu_gamut_f32(
        &self,
        data: &[f32],
        width: u32,
        height: u32,
        backend: &dyn ComputeBackend,
    ) -> Option<ImageBuf> {
        self.process_gpu_resident_cpu_gamut_input(
            spektrafilm_gpu::ChainImage::F32(data, width, height),
            backend,
            None,
            None,
        )
    }

    /// f32 resident export with cooperative cancellation/interactive yield
    /// between completed GPU submissions and CPU gamut/output phases.
    pub fn process_gpu_resident_cpu_gamut_f32_controlled(
        &self, data: &[f32], width: u32, height: u32,
        backend: &dyn ComputeBackend, mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        self.process_gpu_resident_cpu_gamut_input_controlled(
            spektrafilm_gpu::ChainImage::F32(data, width, height), backend,
            None, None, &mut checkpoint,
        )
    }

    /// Same export as the ordinary controlled method, with an optional pre-grain tap.
    pub fn process_gpu_resident_cpu_gamut_f32_capture_controlled(
        &self, data: &[f32], width: u32, height: u32,
        backend: &dyn ComputeBackend, capture: &mut spektrafilm_gpu::FilmDensityCapture,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        capture.result = None;
        let result = self.process_gpu_resident_cpu_gamut_input_capture_controlled(
            spektrafilm_gpu::ChainImage::F32(data, width, height), backend,
            None, None, Some(&mut *capture), &mut checkpoint,
        );
        if result.is_none() { capture.result = None; }
        result
    }

    fn process_gpu_resident_cpu_gamut_input(
        &self,
        input: spektrafilm_gpu::ChainImage<'_>,
        backend: &dyn ComputeBackend,
        band: Option<BandContext>,
        diffusion_fields: Option<&DiffusionScatteredFields>,
    ) -> Option<ImageBuf> {
        self.process_gpu_resident_cpu_gamut_input_controlled(input, backend, band, diffusion_fields, &mut || true)
    }

    fn process_gpu_resident_cpu_gamut_input_controlled(
        &self,
        input: spektrafilm_gpu::ChainImage<'_>,
        backend: &dyn ComputeBackend,
        band: Option<BandContext>,
        diffusion_fields: Option<&DiffusionScatteredFields>,
        checkpoint: &mut dyn FnMut() -> bool,
    ) -> Option<ImageBuf> {
        self.process_gpu_resident_cpu_gamut_input_capture_controlled(input, backend, band, diffusion_fields, None, checkpoint)
    }

    fn process_gpu_resident_cpu_gamut_input_capture_controlled(
        &self,
        input: spektrafilm_gpu::ChainImage<'_>,
        backend: &dyn ComputeBackend,
        band: Option<BandContext>,
        diffusion_fields: Option<&DiffusionScatteredFields>,
        capture: Option<&mut spektrafilm_gpu::FilmDensityCapture>,
        checkpoint: &mut dyn FnMut() -> bool,
    ) -> Option<ImageBuf> {
        self.process_gpu_resident_cpu_gamut_range_controlled(input, backend, band,
            spektrafilm_gpu::ChainStart::Rgb, diffusion_fields, capture, checkpoint)
    }

    fn process_gpu_resident_cpu_gamut_range_controlled(
        &self,
        input: spektrafilm_gpu::ChainImage<'_>,
        backend: &dyn ComputeBackend,
        band: Option<BandContext>,
        chain_start: spektrafilm_gpu::ChainStart,
        diffusion_fields: Option<&DiffusionScatteredFields>,
        capture: Option<&mut spektrafilm_gpu::FilmDensityCapture>,
        checkpoint: &mut dyn FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() { return None; }
        if self.spectral.tc_lut.is_none() && self.spectral.mallett_core.is_none() {
            return None;
        }
        let color_ref = crate::color_reference::ColorReference::compute(
            &self.film,
            &self.print,
            &self.params,
            &self.print_illuminant,
            self.print_exposure_factor,
        );
        if !checkpoint() { return None; }
        // Unclamped linear RGB — gamut + encode deferred to the CPU below.
        let mut rgb = self.try_gpu_resident_range_controlled(
            input,
            backend,
            &color_ref,
            true,
            band,
            spektrafilm_gpu::ChainStop::Full,
            chain_start,
            diffusion_fields,
            capture,
            checkpoint,
        )?;
        // The resident mutex is released before any callback can yield.
        if !checkpoint() { return None; }
        if rgb.width != input.width() || rgb.height != input.height()
            || rgb.data.len() != (input.width() as usize).checked_mul(input.height() as usize)?.checked_mul(3)?
            || rgb.data.iter().any(|v| !v.is_finite()) { return None; }
        // Exact f64 output gamut compression (mirrors the CPU scanning stage).
        if self.output_gamut.is_active() {
            use rayon::prelude::*;
            use spektrafilm_math::precision::from_f64;
            let gamut = &self.output_gamut;
            rgb.data.par_chunks_exact_mut(3).for_each(|px| {
                let out = gamut.compress([px[0] as f64, px[1] as f64, px[2] as f64]);
                px[0] = from_f64(out[0]);
                px[1] = from_f64(out[1]);
                px[2] = from_f64(out[2]);
            });
        }
        if !checkpoint() { return None; }
        if self.params.scanner.lens_blur > 0.0 {
            rgb = backend.gaussian_blur(&rgb, self.params.scanner.lens_blur);
            if !checkpoint() { return None; }
        }
        let [radius, amount] = self.params.scanner.unsharp_mask;
        if radius > 0.0 && amount > 0.0 {
            if !checkpoint() { return None; }
            let blurred = backend.gaussian_blur(&rgb, radius);
            if !checkpoint() { return None; }
            for (v, b) in rgb.data.iter_mut().zip(blurred.data.iter()) {
                *v = spektrafilm_math::precision::from_f64((1.0 + amount as f64) * (*v as f64) - amount as f64 * (*b as f64));
            }
        }
        if !checkpoint() { return None; }
        if rgb.data.iter().any(|v| !v.is_finite()) { return None; }
        let rgb = self.apply_post_scan(rgb);
        if !checkpoint() { return None; }
        Some(rgb)
    }

    /// Banded (tiled) variant of [`Self::process_gpu_resident_cpu_gamut_f32`]
    /// for images whose full frame exceeds the GPU's
    /// `max_storage_buffer_binding_size` (e.g. 50 MP on a 256 MiB mobile
    /// limit). The image is processed in horizontal bands of `band_rows`
    /// core rows plus a halo covering every active spatial kernel, so each
    /// band's frame fits one GPU storage-buffer binding; the core rows are
    /// stitched into the full output.
    ///
    /// Band consistency:
    ///   * the grain/glare per-pixel RNGs are seeded by the GLOBAL pixel
    ///     index ([`BandContext::pixel_offset`]), so the noise fields match
    ///     the whole-frame run exactly;
    ///   * auto-exposure is metered ONCE here on the whole input and the
    ///     same EV is injected into every band;
    ///   * the physical pixel pitch is pinned to the full-image value (a
    ///     band's smaller row count must not shrink it).
    ///
    /// Returns `None` — the caller falls back to the exact CPU path — when
    /// the camera or enlarger diffusion filter is active (a global
    /// downsampled effect that cannot be banded) or the resident chain is
    /// unavailable.
    pub fn process_gpu_resident_banded_f32(
        &self,
        data: &[f32],
        width: u32,
        height: u32,
        band_rows: u32,
        backend: &dyn ComputeBackend,
    ) -> Option<ImageBuf> {
        use spektrafilm_math::precision::{from_f32, from_f64};
        if self.spectral.tc_lut.is_none() && self.spectral.mallett_core.is_none() {
            return None;
        }
        // Global effects require the controlled production prepass.
        if (self.params.film_render.halation.boost_ev != 0.0 || self.params.film_render.halation.headroom_active()) || self.params.camera.diffusion_filter.active
            || (!self.params.io.scan_film && self.params.enlarger.diffusion_filter.active)
        {
            return None;
        }
        let n = width as usize * height as usize;
        if n == 0 || data.len() != n * 3 {
            return None;
        }
        // Physical effects (halation, grain, lens blur, …) are parameterized
        // in micrometers and converted with the FULL-image pitch; the CPU
        // chunk stages do the same via `pixel_size_um_override`. Pin it so a
        // band's smaller row count can't shrink the pixel pitch.
        let full_pitch = self.params.camera.pixel_size_um_override.unwrap_or_else(|| {
            stages::filming::pixel_size_um(self.params.camera.film_format_mm, width, height)
        });
        let owned;
        let pipe: &Pipeline = if self.params.camera.pixel_size_um_override == Some(full_pitch) {
            self
        } else {
            let mut p = self.params.clone();
            p.camera.pixel_size_um_override = Some(full_pitch);
            owned = self.clone().with_params(p);
            &owned
        };

        // Meter auto-exposure ONCE on the whole input; every band gets the
        // same EV injected (per-band metering would drift across bands).
        let ae_ev = if !pipe.params.camera.auto_exposure {None} else if let Some(ev)=pipe.params.camera.preview_metered_ev {Some(ev as f64)} else {
            let img = ImageBuf::from_data(width, height, data.iter().map(|&v| from_f32(v)).collect());
            let rgb_to_xyz =
                stages::filming::input_colorspace_to_xyz(&pipe.params.io.input_color_space);
            Some(stages::filming::measure_autoexposure_ev(
                &img,
                &rgb_to_xyz,
                &pipe.params.camera.auto_exposure_method,
            ) as f64)
        };

        let halo = pipe.banded_halo_pixels(full_pitch);
        let band_rows = band_rows.max(1).min(height);
        let w = width as usize;
        let mut out = ImageBuf::from_data(width, height, vec![from_f64(0.0); n * 3]);
        let mut y0 = 0u32;
        while y0 < height {
            let y1 = (y0 + band_rows).min(height);
            // Band + halo, clamped at the image top/bottom edges.
            let hy0 = y0.saturating_sub(halo);
            let hy1 = y1.saturating_add(halo).min(height);
            let band_h = hy1 - hy0;
            let band: Vec<f32> = data[hy0 as usize * w * 3..hy1 as usize * w * 3].to_vec();
            let band_out = pipe.process_gpu_resident_cpu_gamut_input(
                spektrafilm_gpu::ChainImage::F32(&band, width, band_h),
                backend,
                Some(BandContext { full_width: width,
                    pixel_offset: hy0 * width,
                    ae_ev,
                }),
                None,
            )?;
            // Copy only the CORE rows (halo excluded) into the output.
            let core_top = (y0 - hy0) as usize;
            let core_rows = (y1 - y0) as usize;
            for r in 0..core_rows {
                let src = (core_top + r) * w * 3;
                let dst = (y0 as usize + r) * w * 3;
                out.data[dst..dst + w * 3].copy_from_slice(&band_out.data[src..src + w * 3]);
            }
            y0 = y1;
        }
        Some(out)
    }

    /// Single-band variant of [`Self::process_gpu_resident_banded_f32`] for
    /// STREAMING exporters: processes one (core + halo) horizontal band
    /// through the GPU-resident chain and returns the whole band (halo rows
    /// included) so the caller can stitch core rows straight into the output
    /// file — no full-frame f32 copy and no n*24-byte f64 output image are
    /// ever materialized.
    ///
    /// * `band_data`: `width * band_height` interleaved f32 RGB rows; the
    ///   first row is global row `pixel_offset / width` (halo rows included).
    /// * `full_height`: row count of the WHOLE frame — physical effects are
    ///   parameterized in micrometers and converted with the FULL-image
    ///   pitch, so the full-frame pitch is pinned here exactly like
    ///   [`Self::process_gpu_resident_banded_f32`] (a band's smaller row
    ///   count must not shrink the pixel pitch). The exporter pre-pins
    ///   `pixel_size_um_override`, making this a no-op.
    /// * `pixel_offset`: global pixel index of the first input pixel — keeps
    ///   the grain/glare RNG seeds continuous across bands (see
    ///   [`BandContext::pixel_offset`]).
    ///
    /// The band runs the exact f64 CPU gamut compressor + post-scan clamp
    /// (both pointwise, hence band-safe), matching
    /// [`Self::process_gpu_resident_cpu_gamut_f32`]. Auto-exposure must be
    /// folded by the caller first, exactly like the whole-frame banded
    /// export. Returns `None` when the resident chain is unavailable, or
    /// when a diffusion filter is active but its precomputed scattered field
    /// was not supplied in `diffusion_fields` (see
    /// [`Self::diffusion_scattered_fields_gpu`]).
    pub fn process_gpu_resident_band_f32(
        &self,
        band_data: &[f32],
        width: u32,
        band_height: u32,
        full_height: u32,
        pixel_offset: u32,
        backend: &dyn ComputeBackend,
        diffusion_fields: Option<&DiffusionScatteredFields>,
    ) -> Option<ImageBuf> {
        self.process_gpu_resident_band_f32_controlled(band_data, width, band_height, full_height,
            pixel_offset, backend, diffusion_fields, || true)
    }

    /// Controlled single-band export. Callbacks execute only between complete
    /// GPU submissions, never while holding the device execution mutex.
    pub fn process_gpu_resident_band_f32_controlled(
        &self,
        band_data: &[f32],
        width: u32,
        band_height: u32,
        full_height: u32,
        pixel_offset: u32,
        backend: &dyn ComputeBackend,
        diffusion_fields: Option<&DiffusionScatteredFields>,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() { return None; }
        if self.spectral.tc_lut.is_none() && self.spectral.mallett_core.is_none() {
            return None;
        }
        // Diffusion filters are global downsampled effects: the banded chain
        // can only apply them from a precomputed whole-frame scattered field.
        // Without the field there is nothing band-safe to do — the caller
        // falls back to the exact CPU path.
        if (self.params.film_render.halation.boost_ev != 0.0 || self.params.film_render.halation.headroom_active()) && diffusion_fields.and_then(|f| f.highlight_max_raw).is_none() { return None; }
        if self.params.film_render.halation.headroom_active()
            && !diffusion_fields.and_then(|f|f.highlight_max_raw).is_some_and(|v|v.is_finite() && v>=0.) {return None;}
        let cam_diff = self.params.camera.diffusion_filter.active;
        let enl_diff =
            !self.params.io.scan_film && self.params.enlarger.diffusion_filter.active;
        if (cam_diff || enl_diff) && diffusion_fields.is_none() { return None; }
        if self.params.camera.auto_exposure || width == 0 || band_height == 0
            || band_data.len() != width as usize * band_height as usize * 3
            || pixel_offset % width != 0 || pixel_offset / width + band_height > full_height {
            return None;
        }
        if let Some(fields) = diffusion_fields {
            if cam_diff && self.params.camera.diffusion_filter.strength > 0.0 && fields.camera.is_none() { return None; }
            if enl_diff && self.params.enlarger.diffusion_filter.strength > 0.0 && fields.enlarger.is_none() { return None; }
        }
        // Pin the FULL-image pitch so the band's spatial kernels match the
        // whole-frame run (mirrors `process_gpu_resident_banded_f32`).
        let full_pitch = self.params.camera.pixel_size_um_override.unwrap_or_else(|| {
            stages::filming::pixel_size_um(self.params.camera.film_format_mm, width, full_height)
        });
        let owned;
        let pipe = if self.params.camera.pixel_size_um_override == Some(full_pitch) {
            self
        } else {
            let mut p = self.params.clone();
            p.camera.pixel_size_um_override = Some(full_pitch);
            owned = self.clone().with_params(p);
            &owned
        };
        let band = BandContext { full_width: width,
            pixel_offset,
            ae_ev: None,
        };
        pipe.process_gpu_resident_cpu_gamut_input_controlled(
            spektrafilm_gpu::ChainImage::F32(band_data, width, band_height),
            backend,
            Some(band),
            diffusion_fields,
            &mut checkpoint,
        )
    }

    /// Banded diffusion pre-pass: compute the whole-frame downsampled +
    /// blurred scattered fields of the active diffusion filters, so the
    /// banded GPU chain can mix them per band
    /// ([`Self::process_gpu_resident_band_f32`]).
    ///
    /// A diffusion filter is a global effect (`(1-p_s)·img + p_s·up(blur(
    /// down(img)))`), but its scattered field lives at 1/d resolution — a few
    /// MB at export sizes — so it is computed once for the whole frame here:
    /// the chain is tapped band by band at the filter's input point
    /// ([`spektrafilm_gpu::ChainStop::Front`] for the camera filter,
    /// [`spektrafilm_gpu::ChainStop::PrintLinear`] for the enlarger filter),
    /// the taps are box-downsampled into the whole-frame accumulator, and
    /// the small image is blurred with the plan's Gaussian mixture.
    ///
    /// * `read_rows(row_start, row_count)` must return the interleaved f32
    ///   input rows `[row_start, row_start + row_count)` of the WHOLE frame
    ///   (`row_count*width*3` elements) — the exporter's spool reader.
    /// * `band_rows` is the core band height the exporter will use; the
    ///   pre-pass never exceeds it, including when a downsample cell is taller.
    ///
    /// Returns empty fields when no global effect is active. Returns `None`
    /// on cancellation, failed reads, unsupported state or unavailable backend.
    pub fn diffusion_scattered_fields_gpu(
        &self,
        width: u32,
        height: u32,
        band_rows: u32,
        read_rows: impl Fn(u32, u32) -> Vec<f32>,
        backend: &dyn ComputeBackend,
    ) -> Option<DiffusionScatteredFields> {
        self.diffusion_scattered_fields_gpu_controlled(width, height, band_rows,
            |y, rows| Some(read_rows(y, rows)), || true, backend)
    }

    /// Cancel-safe production prepass. `checkpoint` is called between GPU
    /// batches and Gaussian components, never while submitted work is in flight.
    /// `false` and failed spool reads abort without returning partial fields.
    pub fn diffusion_scattered_fields_gpu_controlled(
        &self, width: u32, height: u32, band_rows: u32,
        mut read_rows: impl FnMut(u32, u32) -> Option<Vec<f32>>,
        mut checkpoint: impl FnMut() -> bool,
        backend: &dyn ComputeBackend,
    ) -> Option<DiffusionScatteredFields> {
        if width == 0 || height == 0 || band_rows == 0 || !checkpoint() { return None; }
        if self.spectral.tc_lut.is_none() && self.spectral.mallett_core.is_none() {
            return None;
        }
        let cam_active = self.params.camera.diffusion_filter.active;
        let enl_active =
            !self.params.io.scan_film && self.params.enlarger.diffusion_filter.active;
        let highlight_active = (self.params.film_render.halation.boost_ev != 0.0 || self.params.film_render.halation.headroom_active());
        if !cam_active && !enl_active && !highlight_active {
            return Some(DiffusionScatteredFields::default());
        }
        // Plans at WHOLE-frame dims, with the full-image pitch pinned exactly
        // like the banded chain (the exporter pre-pins the override).
        let pitch_um = self.params.camera.pixel_size_um_override.unwrap_or_else(|| {
            stages::filming::pixel_size_um(self.params.camera.film_format_mm, width, height)
        });
        let cam_plan = if cam_active {
            spektrafilm_model::diffusion::diffusion_gpu_plan(
                &self.params.camera.diffusion_filter.to_model(),
                pitch_um as f64,
                width,
                height,
            )
        } else {
            None
        };
        let enl_plan = if enl_active {
            spektrafilm_model::diffusion::diffusion_gpu_plan(
                &self.params.enlarger.diffusion_filter.to_model(),
                pitch_um as f64,
                width,
                height,
            )
        } else {
            None
        };
        let color_ref = crate::color_reference::ColorReference::compute(
            &self.film,
            &self.print,
            &self.params,
            &self.print_illuminant,
            self.print_exposure_factor,
        );
        let halo = self.banded_halo_pixels(pitch_um) as usize;
        // Highlight boosting depends on the maximum front exposure of the
        // whole image. v41 reduced each band independently, creating seams.
        let highlight_max_raw = if highlight_active {
            let mut params = self.params.clone();
            params.film_render.halation.boost_ev = 0.0;
            params.film_render.halation.headroom_ev = 0.0;
            let unboosted = self.clone().with_params(params);
            let mut maximum = 0.0f32;
            let mut y = 0;
            while y < height {
                if !checkpoint() { return None; }
                let rows_count = band_rows.min(height - y);
                let rows = read_rows(y, rows_count)?;
                if rows.len() != width as usize * rows_count as usize * 3 || rows.iter().any(|v| !v.is_finite()) { return None; }
                let tap = unboosted.try_gpu_resident(
                    spektrafilm_gpu::ChainImage::F32(&rows, width, rows_count), backend,
                    &color_ref, true, Some(BandContext { full_width: width, pixel_offset: y * width, ae_ev: None }),
                    spektrafilm_gpu::ChainStop::Front, None)?;
                for &value in &tap.data { maximum = maximum.max(value as f32); }
                y += rows_count;
            }
            Some(maximum)
        } else { None };

        // One band loop over the frame, tapping the chain at `stop` and
        // accumulating the 1/d box downsample of the tap's core rows.
        // `cam_field` is mixed in when `stop` is PrintLinear, so the enlarger
        // tap sees the camera-diffused state exactly like the full chain.
        let mut run_prepass = |stop: spektrafilm_gpu::ChainStop,
                           plan: &spektrafilm_gpu::DiffusionGpuPlan,
                           cam_field: Option<&DiffusionScattered>|
         -> Option<DiffusionScattered> {
            let d = plan.d.max(1) as usize;
            let mut acc = spektrafilm_model::diffusion::DownsampleAccumulator::new(
                width as usize,
                height as usize,
                d,
            );
            // Accumulation supports partial cells spanning consecutive bands.
            // Never round UP past the admitted band size when d > band_rows.
            let core_step = band_rows as usize;
            // Clone the small camera field once, not once for every band.
            let fields_here = DiffusionScatteredFields {
                highlight_max_raw, camera: cam_field.cloned(), enlarger: None,
            };
            let mut y0 = 0usize;
            while y0 < height as usize {
                if !checkpoint() { return None; }
                let y1 = (y0 + core_step).min(height as usize);
                let (hy0, hy1) = if stop == spektrafilm_gpu::ChainStop::Front {
                    // The Front tap is before any spatial stage — no halo.
                    (y0, y1)
                } else {
                    (y0.saturating_sub(halo), (y1 + halo).min(height as usize))
                };
                let rows = read_rows(hy0 as u32, (hy1 - hy0) as u32)?;
                if rows.len() != width as usize * (hy1 - hy0) * 3 || rows.iter().any(|v| !v.is_finite()) { return None; }
                let tap = self.try_gpu_resident(
                    spektrafilm_gpu::ChainImage::F32(&rows, width, (hy1 - hy0) as u32),
                    backend,
                    &color_ref,
                    true,
                    Some(BandContext { full_width: width,
                        pixel_offset: hy0 as u32 * width,
                        ae_ev: None,
                    }),
                    stop,
                    Some(&fields_here),
                )?;
                acc.add_band(&tap, hy0, y0, y1);
                y0 = y1;
            }
            let small = acc.finish();
            drop(acc); // release f64 sums before any Gaussian allocation
            let field = spektrafilm_model::diffusion::blur_scattered_field_controlled(backend, &small, plan, &mut checkpoint)?;
            Some(DiffusionScattered { crop: None,
                field,
                small_w: plan.small_w,
                small_h: plan.small_h,
                d: plan.d,
                p_s: plan.p_s,
            })
        };

        // Camera filter first: its scattered field feeds the enlarger tap.
        let camera = match cam_plan.as_ref() {
            Some(plan) => Some(run_prepass(spektrafilm_gpu::ChainStop::Front, plan, None)?),
            None => None,
        };
        let enlarger = match enl_plan.as_ref() {
            Some(plan) => Some(run_prepass(
                spektrafilm_gpu::ChainStop::PrintLinear,
                plan,
                camera.as_ref(),
            )?),
            None => None,
        };
        Some(DiffusionScatteredFields { highlight_max_raw, camera, enlarger })
    }

    /// Render a rectangular tile including its caller-provided spatial halo.
    /// Output has exactly the input tile dimensions. The caller writes only
    /// its core; all tile coordinates are in the unchanged full-size image.
    pub fn process_gpu_resident_tile_f32_controlled(
        &self, data: &[f32], width: u32, height: u32, tile: TileContext,
        backend: &dyn ComputeBackend, fields: Option<&DiffusionScatteredFields>,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() { return None; }
        self.validate_gpu_tile(data, width, height, tile, fields, spektrafilm_gpu::ChainStop::Full)?;
        let pitch = self.full_image_pitch(tile.full_width, tile.full_height);
        let owned;
        let pipe = if self.params.camera.pixel_size_um_override == Some(pitch) { self } else {
            let mut params = self.params.clone();
            params.camera.pixel_size_um_override = Some(pitch);
            owned = self.clone().with_params(params);
            &owned
        };
        pipe.process_gpu_resident_cpu_gamut_input_controlled(
            spektrafilm_gpu::ChainImage::F32(data, width, height), backend,
            Some(BandContext { full_width: tile.full_width,
                pixel_offset: tile.y.checked_mul(tile.full_width)?.checked_add(tile.x)?, ae_ev: None }),
            fields, &mut checkpoint)
    }

    /// Whole-preview tile: preserve the ordinary preview GPU gamut and post-scan
    /// contract. Export/detail deliberately retain their separate CPU gamut path.
    /// Caller supplies global fields, full-grid metering and composed spatial halos.
    pub fn process_gpu_preview_tile_f32_controlled(
        &self, data: &[f32], width: u32, height: u32, tile: TileContext,
        backend: &dyn ComputeBackend, fields: Option<&DiffusionScatteredFields>,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() { return None; }
        self.validate_gpu_tile(data, width, height, tile, fields, spektrafilm_gpu::ChainStop::Full)?;
        let pitch = self.full_image_pitch(tile.full_width, tile.full_height);
        let owned;
        let pipe = if self.params.camera.pixel_size_um_override == Some(pitch) { self } else {
            let mut params = self.params.clone();
            params.camera.pixel_size_um_override = Some(pitch);
            owned = self.clone().with_params(params);
            &owned
        };
        if pipe.spectral.tc_lut.is_none() && pipe.spectral.mallett_core.is_none() { return None; }
        let color_ref = crate::color_reference::ColorReference::compute(
            &pipe.film, &pipe.print, &pipe.params, &pipe.print_illuminant, pipe.print_exposure_factor);
        if !checkpoint() { return None; }
        let out = pipe.try_gpu_resident_controlled(
            spektrafilm_gpu::ChainImage::F32(data, width, height), backend, &color_ref,
            false, Some(BandContext { full_width: tile.full_width,
                pixel_offset: tile.y.checked_mul(tile.full_width)?.checked_add(tile.x)?, ae_ev: None }),
            spektrafilm_gpu::ChainStop::Full, fields, &mut checkpoint)?;
        if !checkpoint() { return None; }
        let out = if backend.resident_chain_applies_post_scan() { out } else { pipe.apply_post_scan(out) };
        if !checkpoint() || out.width != width || out.height != height
            || out.data.len() != width as usize * height as usize * 3
            || out.data.iter().any(|v| !v.is_finite()) { return None; }
        Some(out)
    }

    /// Tile export with a best-effort pre-grain density side channel.
    pub fn process_gpu_resident_tile_f32_capture_controlled(
        &self, data: &[f32], width: u32, height: u32, tile: TileContext,
        backend: &dyn ComputeBackend, fields: Option<&DiffusionScatteredFields>,
        capture: &mut spektrafilm_gpu::FilmDensityCapture,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        capture.result = None;
        backend.reset_thread_resident_progress();
        if !checkpoint() { return None; }
        self.validate_gpu_tile(data, width, height, tile, fields, spektrafilm_gpu::ChainStop::Full)?;
        let pitch = self.full_image_pitch(tile.full_width, tile.full_height);
        let owned;
        let pipe = if self.params.camera.pixel_size_um_override == Some(pitch) { self } else {
            let mut params = self.params.clone();
            params.camera.pixel_size_um_override = Some(pitch);
            owned = self.clone().with_params(params);
            &owned
        };
        let result = pipe.process_gpu_resident_cpu_gamut_input_capture_controlled(
            spektrafilm_gpu::ChainImage::F32(data, width, height), backend,
            Some(BandContext { full_width: tile.full_width,
                pixel_offset: tile.y.checked_mul(tile.full_width)?.checked_add(tile.x)?, ae_ev: None }),
            fields, Some(&mut *capture), &mut checkpoint);
        if result.is_none() { capture.result = None; }
        result
    }

    /// Exact effective prefix identity. The caller supplies a verified identity
    /// for ALL scientific data and adds source bits/mapping, dimensions, actual
    /// backend and external-field/reduction geometry. A fresh calibrated Pipeline
    /// remains required for the suffix; this never licenses stale with_params.
    /// Float values are encoded as bits; malformed/nonfinite inputs do not cache.
    pub fn gpu_film_log_prefix_key(&self, scientific_data_revision: &str) -> Option<String> {
        if scientific_data_revision.is_empty() { return None; }
        let front = self.film_log_front_identity.as_ref()?;
        let mut camera = serde_json::to_value(&self.params.camera).ok()?;
        let object = camera.as_object_mut()?;
        object.remove("pixel_size_um_override");
        object.remove("preview_metered_ev");
        let correction = crate::color_reference::ColorReference::compute(
            &self.film, &self.print, &self.params, &self.print_illuminant,
            self.print_exposure_factor).filming_exposure_correction;
        if !correction.is_finite() { return None; }
        film_log_exact_key(serde_json::json!({
            "version": 1, "data": scientific_data_revision, "front": front,
            "camera": camera,
            "pitch": self.params.camera.pixel_size_um_override.into_iter().collect::<Vec<_>>(),
            "meteredEv": self.params.camera.preview_metered_ev.into_iter().collect::<Vec<_>>(),
            "halation": self.params.film_render.halation,
            "cat16": self.params.settings.use_cat16,
            "inputSpace": self.params.io.input_color_space,
            "inputDecoding": self.params.io.input_cctf_decoding,
            "crop": (self.params.io.crop, self.params.io.crop_center, self.params.io.crop_size),
            "upscale": self.params.io.upscale_factor,
            "filmingExposureCorrectionBits": correction.to_bits(),
        }))
    }

    /// Exact effective state through corrected density. Actual resolved curves
    /// are included so profile development and mutated prepared views cannot
    /// silently reuse stale density. Callers may SHA-256 this exact identity.
    pub fn gpu_corrected_density_prefix_key(&self, revision: &str) -> Option<String> {
        let prefix = self.gpu_film_log_prefix_key(revision)?;
        film_log_exact_key(serde_json::json!({
            "version": 1, "filmLog": prefix,
            "logExposure": self.film.log_exposure_f64(),
            "densityCurves": self.film.density_curves_f64(),
            "positive": self.film.is_positive(),
            "gamma": self.params.film_render.density_curve_gamma,
            "dir": self.params.film_render.dir_couplers,
        }))
    }

    /// Continue an exact FilmLog preview through density/DIR only.
    pub fn tap_gpu_corrected_density_preview_controlled(
        &self, data: &[f32], width: u32, height: u32, backend: &dyn ComputeBackend,
        checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        self.corrected_density_preview_range(data, width, height, backend, false, checkpoint)
    }

    /// Resume before grain using a freshly calibrated pipeline. Gamut, scanner
    /// optics, encoding and clamps retain the ordinary whole-preview order.
    pub fn process_gpu_from_corrected_density_preview_controlled(
        &self, data: &[f32], width: u32, height: u32, backend: &dyn ComputeBackend,
        checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        self.corrected_density_preview_range(data, width, height, backend, true, checkpoint)
    }

    fn corrected_density_preview_range(
        &self, data: &[f32], width: u32, height: u32, backend: &dyn ComputeBackend,
        finish: bool, mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() || !self.valid_preview_film_log_shape(width, height, data.len())
            || data.iter().any(|v| !v.is_finite()) { return None; }
        let color_ref = crate::color_reference::ColorReference::compute(
            &self.film, &self.print, &self.params, &self.print_illuminant, self.print_exposure_factor);
        let (start, stop) = if finish {
            (spektrafilm_gpu::ChainStart::CorrectedDensity, spektrafilm_gpu::ChainStop::Full)
        } else {
            (spektrafilm_gpu::ChainStart::FilmLog, spektrafilm_gpu::ChainStop::CorrectedDensity)
        };
        let out = self.try_gpu_resident_range_controlled(
            spektrafilm_gpu::ChainImage::F32(data, width, height), backend, &color_ref,
            false, None, stop, start, None, None, &mut checkpoint)?;
        if !checkpoint() || out.width != width || out.height != height
            || out.data.len() != data.len() || out.data.iter().any(|v| !v.is_finite()) { return None; }
        let out = if finish && !backend.resident_chain_applies_post_scan() { self.apply_post_scan(out) } else { out };
        if !checkpoint() { return None; }
        Some(out)
    }

    /// Whole-frame preview prefix, retaining the ordinary preview GPU contract
    /// (including internally computed global highlight/camera diffusion). It is
    /// NOT a tile and requires no fabricated global fields or coordinate offset.
    pub fn tap_gpu_film_log_preview_controlled(
        &self, image: &ImageBuf, backend: &dyn ComputeBackend,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() || !self.valid_preview_film_log_shape(image.width, image.height, image.data.len())
            || image.data.iter().any(|v| !v.is_finite()) { return None; }
        let color_ref = crate::color_reference::ColorReference::compute(
            &self.film, &self.print, &self.params, &self.print_illuminant, self.print_exposure_factor);
        let out = self.try_gpu_resident_range_controlled(
            spektrafilm_gpu::ChainImage::F64(image), backend, &color_ref, false, None,
            spektrafilm_gpu::ChainStop::FilmLog, spektrafilm_gpu::ChainStart::Rgb,
            None, None, &mut checkpoint)?;
        if !checkpoint() || out.width != image.width || out.height != image.height
            || out.data.len() != image.data.len() || out.data.iter().any(|v| !v.is_finite()) { return None; }
        Some(out)
    }

    /// Whole-frame suffix with the SAME GPU gamut/post-scan order as preview.
    /// FilmLog is the exact f32 GPU boundary, not a second exposure operation.
    /// Enlarger global diffusion, density/DIR, grain, scan, gamut and optics all
    /// run with this freshly calibrated pipeline. Export CPU-gamut APIs differ.
    pub fn process_gpu_from_film_log_preview_controlled(
        &self, data: &[f32], width: u32, height: u32, backend: &dyn ComputeBackend,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() || !self.valid_preview_film_log_shape(width, height, data.len())
            || data.iter().any(|v| !v.is_finite()) { return None; }
        let color_ref = crate::color_reference::ColorReference::compute(
            &self.film, &self.print, &self.params, &self.print_illuminant, self.print_exposure_factor);
        let out = self.try_gpu_resident_range_controlled(
            spektrafilm_gpu::ChainImage::F32(data, width, height), backend, &color_ref, false, None,
            spektrafilm_gpu::ChainStop::Full, spektrafilm_gpu::ChainStart::FilmLog,
            None, None, &mut checkpoint)?;
        if !checkpoint() || out.width != width || out.height != height
            || out.data.len() != data.len() || out.data.iter().any(|v| !v.is_finite()) { return None; }
        let out = if backend.resident_chain_applies_post_scan() { out } else { self.apply_post_scan(out) };
        if !checkpoint() { return None; }
        Some(out)
    }

    fn valid_preview_film_log_shape(&self, width: u32, height: u32, count: usize) -> bool {
        width > 0 && height > 0
            && (width as usize).checked_mul(height as usize).and_then(|n| n.checked_mul(3)) == Some(count)
            && self.params.io.upscale_factor == 1.0 && !self.params.io.crop
            && (self.spectral.tc_lut.is_some() || self.spectral.mallett_core.is_some())
    }

    /// Tap unclamped film log exposure after camera optics, before density/DIR.
    pub fn tap_gpu_film_log_tile_f32_controlled(
        &self, data: &[f32], width: u32, height: u32, tile: TileContext,
        backend: &dyn ComputeBackend, fields: Option<&DiffusionScatteredFields>,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        self.process_gpu_film_log_range(data, width, height, tile, backend, fields,
            spektrafilm_gpu::ChainStart::Rgb, None, &mut checkpoint)
    }

    /// Resume from f32 film log exposure with unchanged global pitch and RNG.
    pub fn process_gpu_from_film_log_tile_f32_controlled(
        &self, data: &[f32], width: u32, height: u32, tile: TileContext,
        backend: &dyn ComputeBackend, fields: Option<&DiffusionScatteredFields>,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        self.process_gpu_film_log_range(data, width, height, tile, backend, fields,
            spektrafilm_gpu::ChainStart::FilmLog, None, &mut checkpoint)
    }

    /// Resume FilmLog and gather sparse corrected-density samples after DIR,
    /// before grain. The caller must reserve capture bytes in its engine budget.
    /// A failed/cancelled final output never publishes a capture result.
    pub fn process_gpu_from_film_log_tile_f32_capture_controlled(
        &self, data: &[f32], width: u32, height: u32, tile: TileContext,
        backend: &dyn ComputeBackend, fields: Option<&DiffusionScatteredFields>,
        capture: &mut spektrafilm_gpu::FilmDensityCapture,
        mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        capture.result = None;
        let result = self.process_gpu_film_log_range(data, width, height, tile, backend, fields,
            spektrafilm_gpu::ChainStart::FilmLog, Some(&mut *capture), &mut checkpoint);
        if result.is_none() { capture.result = None; }
        result
    }

    fn process_gpu_film_log_range(
        &self, data: &[f32], width: u32, height: u32, tile: TileContext,
        backend: &dyn ComputeBackend, fields: Option<&DiffusionScatteredFields>,
        start: spektrafilm_gpu::ChainStart,
        capture: Option<&mut spektrafilm_gpu::FilmDensityCapture>,
        checkpoint: &mut dyn FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if !checkpoint() { return None; }
        if start == spektrafilm_gpu::ChainStart::Rgb && capture.is_some() { return None; }
        let stop = if start == spektrafilm_gpu::ChainStart::Rgb {
            spektrafilm_gpu::ChainStop::FilmLog
        } else { spektrafilm_gpu::ChainStop::Full };
        self.validate_gpu_tile_range(data, width, height, tile, fields, stop, start)?;
        let pitch = self.full_image_pitch(tile.full_width, tile.full_height);
        let owned;
        let pipe = if self.params.camera.pixel_size_um_override == Some(pitch) { self } else {
            let mut params = self.params.clone();
            params.camera.pixel_size_um_override = Some(pitch);
            owned = self.clone().with_params(params);
            &owned
        };
        let band = Some(BandContext { full_width: tile.full_width,
            pixel_offset: tile.y.checked_mul(tile.full_width)?.checked_add(tile.x)?, ae_ev: None });
        let image = spektrafilm_gpu::ChainImage::F32(data, width, height);
        if start == spektrafilm_gpu::ChainStart::FilmLog {
            return pipe.process_gpu_resident_cpu_gamut_range_controlled(
                image, backend, band, start, fields, capture, checkpoint);
        }
        let color_ref = crate::color_reference::ColorReference::compute(&pipe.film, &pipe.print,
            &pipe.params, &pipe.print_illuminant, pipe.print_exposure_factor);
        let out = pipe.try_gpu_resident_range_controlled(image, backend, &color_ref, true,
            band, stop, start, fields, capture, checkpoint)?;
        if !checkpoint() || out.width != width || out.height != height
            || out.data.len() != data.len() || out.data.iter().any(|v| !v.is_finite()) { return None; }
        Some(out)
    }

    /// Bounded staged prefix endpoints. Intermediate samples retain their raw
    /// exposure domain; no RGB decoding/exposure is applied when resuming.
    pub fn process_gpu_spatial_endpoint_controlled(
        &self, data: &[f32], width:u32, height:u32, tile:TileContext,
        backend:&dyn ComputeBackend, fields:Option<&DiffusionScatteredFields>,
        resume_post_halation:bool, mut checkpoint:impl FnMut()->bool,
    )->Option<ImageBuf> {
        use spektrafilm_gpu::{ChainStart,ChainStop};
        backend.reset_thread_resident_progress();
        let (start,stop)=if resume_post_halation {(ChainStart::PostHalationRaw,ChainStop::FilmLog)}
            else {(ChainStart::Rgb,ChainStop::ScatteredRaw)};
        self.validate_gpu_tile_range(data,width,height,tile,fields,stop,start)?;
        if !checkpoint(){return None;}
        let mut params=self.params.clone();
        params.camera.pixel_size_um_override=Some(self.full_image_pitch(tile.full_width,tile.full_height));
        let pipe=self.clone().with_params(params);
        let color_ref=crate::color_reference::ColorReference::compute(&pipe.film,&pipe.print,
            &pipe.params,&pipe.print_illuminant,pipe.print_exposure_factor);
        let out=pipe.try_gpu_resident_range_controlled(spektrafilm_gpu::ChainImage::F32(data,width,height),
            backend,&color_ref,true,Some(BandContext{full_width:tile.full_width,
                pixel_offset:tile.y.checked_mul(tile.full_width)?.checked_add(tile.x)?,ae_ev:None}),
            stop,start,fields,None,&mut checkpoint)?;
        if !checkpoint()||out.width!=width||out.height!=height||out.data.len()!=data.len()
            ||out.data.iter().any(|v|!v.is_finite()){return None;}
        Some(out)
    }

    /// Only camera-lens and scatter supports are needed by ScatteredRaw.
    pub fn gpu_scattered_raw_halo(&self,width:u32,height:u32)->u32 {
        let mut params=self.params.clone();
        params.film_render.halation.back_reflection=false;
        params.film_render.halation.halation_first_sigma_um=[0.;3];
        self.clone().with_params(params).gpu_split_halo_pixels(self.full_image_pitch(width,height)).0
    }

    pub fn gpu_physical_components(&self,width:u32,height:u32)
        ->Option<([spektrafilm_gpu::HalationComponentGpu;4],[f32;3],bool)> {
        let h=&self.params.film_render.halation;
        if !h.active||!h.back_reflection||h.halation_amount<=0.||h.base_thickness_um<=0. {return None;}
        let components=spektrafilm_math::halation::back_reflection_components(
            h.base_thickness_um,h.halation_spatial_scale,h.halation_amount,self.full_image_pitch(width,height) as f64)
            .map(|c|spektrafilm_gpu::HalationComponentGpu{weight:c.weight.map(|v|v as f32),sigma_px:c.sigma_px.map(|v|v as f32)});
        Some((components,h.halation_strength.map(|v|(v*h.halation_amount) as f32),h.halation_renormalize))
    }

    /// Controlled prepass tap. Front stops before camera optics; PrintLinear
    /// stops before enlarger diffusion. Neither performs final scan/gamut.
    pub fn tap_gpu_resident_tile_f32_controlled(
        &self, data: &[f32], width: u32, height: u32, tile: TileContext,
        backend: &dyn ComputeBackend, fields: Option<&DiffusionScatteredFields>,
        stop: spektrafilm_gpu::ChainStop, mut checkpoint: impl FnMut() -> bool,
    ) -> Option<ImageBuf> {
        backend.reset_thread_resident_progress();
        if stop == spektrafilm_gpu::ChainStop::Full || !checkpoint() { return None; }
        self.validate_gpu_tile(data, width, height, tile, fields, stop)?;
        let pitch = self.full_image_pitch(tile.full_width, tile.full_height);
        let owned;
        let pipe = if self.params.camera.pixel_size_um_override == Some(pitch) { self } else {
            let mut params = self.params.clone();
            params.camera.pixel_size_um_override = Some(pitch);
            owned = self.clone().with_params(params);
            &owned
        };
        let color_ref = crate::color_reference::ColorReference::compute(&pipe.film, &pipe.print,
            &pipe.params, &pipe.print_illuminant, pipe.print_exposure_factor);
        let out = pipe.try_gpu_resident_controlled(spektrafilm_gpu::ChainImage::F32(data, width, height),
            backend, &color_ref, true,
            Some(BandContext { full_width: tile.full_width,
                pixel_offset: tile.y.checked_mul(tile.full_width)?.checked_add(tile.x)?, ae_ev: None }),
            stop, fields, &mut checkpoint)?;
        if !checkpoint() || out.width != width || out.height != height
            || out.data.len() != data.len() || out.data.iter().any(|v| !v.is_finite()) { return None; }
        Some(out)
    }

    fn full_image_pitch(&self, width: u32, height: u32) -> f32 {
        self.params.camera.pixel_size_um_override.unwrap_or_else(||
            stages::filming::pixel_size_um(self.params.camera.film_format_mm, width, height))
    }

    fn validate_gpu_tile(&self, data: &[f32], width: u32, height: u32,
        tile: TileContext, fields: Option<&DiffusionScatteredFields>, stop: spektrafilm_gpu::ChainStop,
    ) -> Option<()> {
        self.validate_gpu_tile_range(data, width, height, tile, fields, stop, spektrafilm_gpu::ChainStart::Rgb)
    }

    fn validate_gpu_tile_range(&self, data: &[f32], width: u32, height: u32,
        tile: TileContext, fields: Option<&DiffusionScatteredFields>, stop: spektrafilm_gpu::ChainStop,
        start: spektrafilm_gpu::ChainStart,
    ) -> Option<()> {
        if width == 0 || height == 0 || tile.full_width == 0 || tile.full_height == 0
            || tile.full_width.checked_mul(tile.full_height).is_none()
            || tile.x.checked_add(width)? > tile.full_width || tile.y.checked_add(height)? > tile.full_height
            || data.len() != (width as usize).checked_mul(height as usize)?.checked_mul(3)?
            || data.iter().any(|v| !v.is_finite())
            || (self.params.camera.auto_exposure && !self.params.camera.preview_metered_ev
                .is_some_and(|ev|ev.is_finite() && 2.0f32.powf(ev).is_finite()))
            || (self.spectral.tc_lut.is_none() && self.spectral.mallett_core.is_none()) { return None; }
        let pitch = self.full_image_pitch(tile.full_width, tile.full_height);
        if !pitch.is_finite() || pitch <= 0.0 { return None; }
        if start == spektrafilm_gpu::ChainStart::Rgb && (self.params.film_render.halation.boost_ev != 0.0 || self.params.film_render.halation.headroom_active())
            && !fields.and_then(|f| f.highlight_max_raw).is_some_and(|v| v.is_finite() && v >= 0.0) { return None; }
        let (cam, enl) = self.gpu_diffusion_plans(tile.full_width, tile.full_height);
        if start == spektrafilm_gpu::ChainStart::Rgb && stop != spektrafilm_gpu::ChainStop::Front && cam.is_some()
            && fields.and_then(|f| f.camera.as_ref()).is_none() { return None; }
        if stop == spektrafilm_gpu::ChainStop::Full && enl.is_some()
            && fields.and_then(|f| f.enlarger.as_ref()).is_none() { return None; }
        if let Some(fields) = fields {
            for field in [&fields.camera, &fields.enlarger].into_iter().flatten() {
                if !field.to_gpu(tile.x, tile.y).valid_for(width, height) { return None; }
            }
        }
        Some(())
    }

    /// Global geometry plans, shared by bounded prepass and memory admission.
    pub fn gpu_diffusion_plans(&self, width: u32, height: u32)
        -> (Option<spektrafilm_gpu::DiffusionGpuPlan>, Option<spektrafilm_gpu::DiffusionGpuPlan>) {
        if width == 0 || height == 0 { return (None, None); }
        let pitch = self.full_image_pitch(width, height);
        let plan = |active, filter: &crate::params::DiffusionFilterParams| {
            if active { spektrafilm_model::diffusion::diffusion_gpu_plan(
                &filter.to_model(), pitch as f64, width, height) } else { None }
        };
        (plan(self.params.camera.diffusion_filter.active, &self.params.camera.diffusion_filter),
         plan(!self.params.io.scan_film && self.params.enlarger.diffusion_filter.active, &self.params.enlarger.diffusion_filter))
    }

    /// Rectangular prepass: cores partition the image exactly once, including
    /// partial downsample cells. The global small fields remain conservatively
    /// admitted by gpu_diffusion_working_bytes; only full-resolution taps tile.
    pub fn diffusion_scattered_fields_gpu_tiled_controlled(
        &self, width: u32, height: u32, core_width: u32, core_height: u32,
        read_rect: impl FnMut(u32, u32, u32, u32) -> Option<Vec<f32>>,
        checkpoint: impl FnMut() -> bool, backend: &dyn ComputeBackend,
    ) -> Option<DiffusionScatteredFields> {
        self.diffusion_scattered_fields_gpu_tiled_progress(width, height, core_width, core_height,
            core_width, core_height, read_rect, checkpoint, backend, |_, _| {})
    }

    /// Front is pointwise and uses its own cores; PrintLinear retains spatial halo.
    pub fn diffusion_scattered_fields_gpu_tiled_progress(
        &self, width: u32, height: u32, core_width: u32, core_height: u32,
        front_width: u32, front_height: u32,
        mut read_rect: impl FnMut(u32, u32, u32, u32) -> Option<Vec<f32>>,
        mut checkpoint: impl FnMut() -> bool, backend: &dyn ComputeBackend,
        mut progress: impl FnMut(u64, u64),
    ) -> Option<DiffusionScatteredFields> {
        backend.reset_thread_resident_progress();
        if width == 0 || height == 0 || core_width == 0 || core_height == 0
            || front_width == 0 || front_height == 0
            || width.checked_mul(height).is_none() || !checkpoint() { return None; }
        let (cam_plan, enl_plan) = self.gpu_diffusion_plans(width, height);
        let highlight = (self.params.film_render.halation.boost_ev != 0.0 || self.params.film_render.halation.headroom_active());
        let halo = self.banded_halo_pixels(self.full_image_pitch(width, height));
        let tiles = |w: u32, h: u32| u64::from(width.div_ceil(w)) * u64::from(height.div_ceil(h));
        let total = if highlight { tiles(front_width, front_height) } else { 0 }
            + cam_plan.as_ref().map_or(0, |p| tiles(front_width, front_height) + p.sigmas.len() as u64)
            + enl_plan.as_ref().map_or(0, |p| tiles(core_width, core_height) + p.sigmas.len() as u64);
        let mut completed = 0u64;
        progress(0, total);
        let highlight_max_raw = if highlight {
            let mut params = self.params.clone();
            params.film_render.halation.boost_ev = 0.0;
            params.film_render.halation.headroom_ev = 0.0;
            let unboosted = self.clone().with_params(params);
            let mut maximum = 0.0f32;
            let mut y = 0;
            while y < height {
                let h = front_height.min(height - y);
                let mut x = 0;
                while x < width {
                    if !checkpoint() { return None; }
                    let w = front_width.min(width - x);
                    let data = read_rect(x, y, w, h)?;
                    let tap = unboosted.tap_gpu_resident_tile_f32_controlled(&data, w, h,
                        TileContext { full_width: width, full_height: height, x, y }, backend,
                        None, spektrafilm_gpu::ChainStop::Front, &mut checkpoint)?;
                    for &v in &tap.data { maximum = maximum.max(v as f32); }
                    completed += 1; progress(completed, total);
                    x += w;
                }
                y += h;
            }
            Some(maximum)
        } else { None };
        let mut run = |stop: spektrafilm_gpu::ChainStop, plan: &spektrafilm_gpu::DiffusionGpuPlan,
                       camera: Option<&DiffusionScattered>| -> Option<DiffusionScattered> {
            let mut acc = spektrafilm_model::diffusion::DownsampleAccumulator::new(
                width as usize, height as usize, plan.d as usize);
            let fields = DiffusionScatteredFields { camera: camera.cloned(), enlarger: None, highlight_max_raw };
            let margin = if stop == spektrafilm_gpu::ChainStop::Front { 0 } else { halo };
            let (tap_width, tap_height) = if stop == spektrafilm_gpu::ChainStop::Front {
                (front_width, front_height)
            } else { (core_width, core_height) };
            let mut y = 0;
            while y < height {
                let y1 = y + tap_height.min(height - y);
                let mut x = 0;
                while x < width {
                    if !checkpoint() { return None; }
                    let x1 = x + tap_width.min(width - x);
                    let tx = x.saturating_sub(margin);
                    let ty = y.saturating_sub(margin);
                    let tw = x1.saturating_add(margin).min(width) - tx;
                    let th = y1.saturating_add(margin).min(height) - ty;
                    let data = read_rect(tx, ty, tw, th)?;
                    let tap = self.tap_gpu_resident_tile_f32_controlled(&data, tw, th,
                        TileContext { full_width: width, full_height: height, x: tx, y: ty },
                        backend, Some(&fields), stop, &mut checkpoint)?;
                    acc.add_tile(&tap, tx as usize, ty as usize,
                        [x as usize, y as usize, x1 as usize, y1 as usize])?;
                    completed += 1; progress(completed, total);
                    x = x1;
                }
                y = y1;
            }
            drop(fields);
            let small = acc.finish();
            drop(acc);
            let field = spektrafilm_model::diffusion::blur_scattered_field_controlled_progress(
                backend, &small, plan, &mut checkpoint, || { completed += 1; progress(completed, total); })?;
            Some(DiffusionScattered { field, small_w: plan.small_w, small_h: plan.small_h,
                d: plan.d, p_s: plan.p_s, crop: None })
        };
        let camera = match cam_plan.as_ref() { Some(p) => Some(run(spektrafilm_gpu::ChainStop::Front, p, None)?), None => None };
        let enlarger = match enl_plan.as_ref() { Some(p) => Some(run(spektrafilm_gpu::ChainStop::PrintLinear, p, camera.as_ref())?), None => None };
        if !checkpoint() { return None; }
        Some(DiffusionScatteredFields { camera, enlarger, highlight_max_raw })
    }

    /// Conservative combined host/device/prepass field bytes, excluding the
    /// separately reserved band working set and fixed pipeline/driver reserve.
    /// Includes both final fields plus one component at a time, not N blurs.
    pub fn gpu_diffusion_working_bytes(&self, width: u32, height: u32) -> Option<u64> {
        if width == 0 || height == 0 { return None; }
        let pitch = self.params.camera.pixel_size_um_override.unwrap_or_else(||
            stages::filming::pixel_size_um(self.params.camera.film_format_mm, width, height));
        let mut bytes = 0u64;
        for (active, filter) in [
            (self.params.camera.diffusion_filter.active, &self.params.camera.diffusion_filter),
            (!self.params.io.scan_film && self.params.enlarger.diffusion_filter.active, &self.params.enlarger.diffusion_filter),
        ] {
            if !active { continue; }
            if let Some(plan) = spektrafilm_model::diffusion::diffusion_gpu_plan(&filter.to_model(), pitch as f64, width, height) {
                bytes = bytes.checked_add(u64::from(plan.small_w).checked_mul(u64::from(plan.small_h))?.checked_mul(192)?)?;
            }
        }
        bytes.checked_add(self.gpu_halation_working_bytes(width,height)?)
    }

    /// Physical kernel reserve is needed during RGB-to-FilmLog, but not when
    /// resuming a cached FilmLog suffix. Keep this separate from global fields.
    pub fn gpu_halation_working_bytes(&self, width:u32, height:u32) -> Option<u64> {
        if width==0 || height==0 {return None;}
        let h=&self.params.film_render.halation;
        if !h.active || !h.back_reflection || h.halation_amount<=0. || h.base_thickness_um<=0. {return Some(0);}
        spektrafilm_gpu::back_reflection_working_bytes(h.base_thickness_um,
            h.halation_spatial_scale,h.halation_amount,self.full_image_pitch(width,height) as f64)
    }

    /// Halo (rows) needed above/below a band so the banded GPU chain's
    /// finite-kernel spatial effects read exactly the pixels the whole-frame
    /// chain would. The effects chain, so their supports ADD: this sums
    /// every active blur's FIR radius, matching the GPU's `ceil(3σ)` kernels
    /// (legacy radius capped at 256; physical back-reflection uncapped) plus
    /// a small safety margin. Public so
    /// exporters can size bands such that band + 2×halo fits a GPU binding.
    pub fn banded_halo_pixels(&self, pitch_um: f32) -> u32 {
        let (prefix, suffix) = self.gpu_split_halo_pixels(pitch_um);
        prefix.saturating_add(suffix).saturating_sub(2)
    }

    /// Independent supports for RGB-to-film-log and film-log-to-output passes.
    pub fn gpu_split_halo_pixels(&self, pitch_um: f32) -> (u32, u32) {
        let p = &self.params;
        let pitch = pitch_um as f64;
        // GPU FIR Gaussian half-width: ceil(3σ), hard-capped at 256
        // (mirrors `fir_blur_radius` in spektrafilm-gpu).
        let fir = |sigma_px: f64| -> u32 {
            if sigma_px <= 0.0 {
                0
            } else {
                ((3.0 * sigma_px).ceil() as u32).min(256)
            }
        };
        let mut radius = 0u32;
        // Camera lens blur (raw film exposure).
        if p.camera.lens_blur_um > 0.0 {
            radius += fir(p.camera.lens_blur_um as f64 / pitch);
        }
        // Halation: scatter mix (chained) then the bounce stack (chained).
        let h = &p.film_render.halation;
        if h.active {
            let avg = |a: [f64; 3]| (a[0] + a[1] + a[2]) / 3.0;
            let core_px = avg(h.scatter_core_um) * h.scatter_spatial_scale / pitch;
            let tail_px = avg(h.scatter_tail_um) * h.scatter_spatial_scale / pitch;
            radius += fir(core_px.max(tail_px));
            let first_px = avg(h.halation_first_sigma_um) * h.halation_spatial_scale / pitch;
            radius = radius.saturating_add(if h.back_reflection && h.halation_amount > 0.0 && h.base_thickness_um > 0.0 {
                let components = spektrafilm_math::halation::back_reflection_components(
                    h.base_thickness_um, h.halation_spatial_scale, h.halation_amount, pitch);
                components.iter().flat_map(|c| c.sigma_px).map(|sigma|
                    spektrafilm_gpu::back_reflection_fir_radius(sigma as f32).unwrap_or(u32::MAX / 2)).max().unwrap_or(0)
            } else { fir(first_px * (h.halation_n_bounces.max(1) as f64).sqrt()) });
        }
        let prefix_radius = radius;
        radius = 0;
        // DIR coupler diffusion (film density): core Gaussian vs the
        // 3-Gaussian exponential tail (widest component ratio 2.7684).
        let d = &p.film_render.dir_couplers;
        if d.active && d.diffusion_size_um > 0.0 {
            let core_px = d.diffusion_size_um / pitch;
            let tail_px = if d.diffusion_tail_weight > 0.0 {
                2.7684 * d.diffusion_tail_um / pitch
            } else {
                0.0
            };
            radius += fir(core_px.max(tail_px));
        }
        // Grain post-blur (film density).
        let g = &p.film_render.grain;
        if g.active {
            if g.sublayers_active {
                let pixel_area = pitch_um * pitch_um;
                if let Some(sl) = build_grain_sublayers(self.donor_grain.as_ref().map_or(&self.film,|d|&d.profile), g, pixel_area, pitch_um) {
                    let max_od = sl.sl_params.iter().map(|p| p[1] as f64).fold(0.0, f64::max);
                    let dye_sigma = if g.blur_dye_clouds_um > 0.4 { g.blur_dye_clouds_um as f64 * max_od.sqrt() } else { 0.0 };
                    radius += fir((g.blur as f64).hypot(dye_sigma));
                }
                if g.micro_structure[1] as f64 * 0.001 / pitch > 0.05 && g.micro_structure[0] as f64 / pitch > 0.4 {
                    radius += fir(g.micro_structure[0] as f64 / pitch);
                }
            } else if g.blur > 0.4 { radius += fir(g.blur as f64); }
        }
        // Glare field blur (final RGB).
        let glare = if p.io.scan_film {
            &p.film_render.glare
        } else {
            &p.print_render.glare
        };
        if glare.active && glare.percent > 0.0 && glare.blur > 0.0 {
            radius += fir(glare.blur as f64);
        }
        // Scanner lens blur + unsharp mask (final RGB).
        if p.scanner.lens_blur > 0.0 {
            radius += fir(p.scanner.lens_blur as f64);
        }
        let [usm_sigma, usm_amount] = p.scanner.unsharp_mask;
        if usm_sigma > 0.0 && usm_amount > 0.0 {
            radius += fir(usm_sigma as f64);
        }
        // Safety margin for kernel rounding.
        (prefix_radius.saturating_add(2), radius.saturating_add(2))
    }

    /// Resize the working image by `io.upscale_factor` (bicubic), mirroring
    /// Python's `ResizingService.crop_and_rescale`. No-op at factor 1.0.
    /// Lets callers trade resolution for speed (the whole pipeline, and the
    /// diffusion-filter kernel, scale with the image).
    fn rescale_working(&self, image: ImageBuf) -> ImageBuf {
        use spektrafilm_math::precision::{Scalar, from_f32, to_f32};
        let uf = self.params.io.upscale_factor;
        if uf <= 0.0 || (uf - 1.0).abs() < 1e-6 {
            return image;
        }
        let new_w = (((image.width as f32) * uf).round() as u32).max(1);
        let new_h = (((image.height as f32) * uf).round() as u32).max(1);
        if new_w == image.width && new_h == image.height {
            return image;
        }
        let f32buf: Vec<f32> = image.data.iter().map(|&v| to_f32(v)).collect();
        let src =
            image::ImageBuffer::<image::Rgb<f32>, _>::from_raw(image.width, image.height, f32buf)
                .expect("ImageBuf dims match its data length");
        let dst =
            image::imageops::resize(&src, new_w, new_h, image::imageops::FilterType::CatmullRom);
        let data: Vec<Scalar> = dst.into_raw().into_iter().map(from_f32).collect();
        tracing::info!(
            from = format!("{}x{}", image.width, image.height),
            to = format!("{new_w}x{new_h}"),
            "pipeline: rescaled working image"
        );
        ImageBuf::from_data(new_w, new_h, data)
    }

    /// Try the GPU-resident fast path. Builds all the per-stage data and
    /// hands it to the backend's `try_run_film_chain`. The output is linear RGB
    /// (clipped to [0,1]) — sRGB encoding is applied by `apply_post_scan`.
    ///
    /// When `defer_gamut_to_cpu` is set (export path), the chain skips the GPU
    /// gamut pass and the final clamp+encode, returning unclamped linear RGB;
    /// the caller then runs the exact f64 CPU gamut compressor + post-scan.
    fn try_gpu_resident(
        &self,
        image: spektrafilm_gpu::ChainImage<'_>,
        backend: &dyn ComputeBackend,
        color_ref: &crate::color_reference::ColorReference,
        defer_gamut_to_cpu: bool,
        band: Option<BandContext>,
        chain_stop: spektrafilm_gpu::ChainStop,
        diffusion_fields: Option<&DiffusionScatteredFields>,
    ) -> Option<ImageBuf> {
        self.try_gpu_resident_controlled(image, backend, color_ref, defer_gamut_to_cpu,
            band, chain_stop, diffusion_fields, &mut || true)
    }

    fn try_gpu_resident_controlled(
        &self,
        image: spektrafilm_gpu::ChainImage<'_>,
        backend: &dyn ComputeBackend,
        color_ref: &crate::color_reference::ColorReference,
        defer_gamut_to_cpu: bool,
        band: Option<BandContext>,
        chain_stop: spektrafilm_gpu::ChainStop,
        diffusion_fields: Option<&DiffusionScatteredFields>,
        checkpoint: &mut dyn FnMut() -> bool,
    ) -> Option<ImageBuf> {
        self.try_gpu_resident_range_controlled(image, backend, color_ref, defer_gamut_to_cpu,
            band, chain_stop, spektrafilm_gpu::ChainStart::Rgb, diffusion_fields, None, checkpoint)
    }

    fn try_gpu_resident_range_controlled(
        &self,
        image: spektrafilm_gpu::ChainImage<'_>,
        backend: &dyn ComputeBackend,
        color_ref: &crate::color_reference::ColorReference,
        defer_gamut_to_cpu: bool,
        band: Option<BandContext>,
        chain_stop: spektrafilm_gpu::ChainStop,
        chain_start: spektrafilm_gpu::ChainStart,
        diffusion_fields: Option<&DiffusionScatteredFields>,
        capture: Option<&mut spektrafilm_gpu::FilmDensityCapture>,
        checkpoint: &mut dyn FnMut() -> bool,
    ) -> Option<ImageBuf> {
        // The post-boost maximum shortcut assumes monotonic nonnegative boost.
        // Mobile controls enforce that domain; bare runtime callers may not.
        if chain_start == spektrafilm_gpu::ChainStart::Rgb
            && self.params.film_render.halation.headroom_active()
            && self.params.film_render.halation.boost_ev < 0.0 { return None; }
        // Banded export: global pixel index of the first input pixel (keeps
        // the grain/glare RNG seeds continuous across bands). 0 otherwise.
        let pixel_offset = band.map(|b| b.pixel_offset).unwrap_or(0);
        let full_width = band.map(|b| b.full_width).filter(|w| *w > 0).unwrap_or(image.width());
        // Bake the exposure scale (auto-exposure × manual EV compensation)
        // into the front-pass matrix. Both upsamplers (hanatos and mallett)
        // are homogeneous in the input RGB, so scaling the matrix is
        // equivalent to scaling the input — saves a separate "scale" compute
        // pass at the head of the chain. Auto-exposure metering itself stays
        // on CPU (~30 ms at 6 MP after the per-row rayon parallelization);
        // the result is a single float that's cheap to roll into the matrix.
        let mut exposure_scale_f64 = 1.0f64;
        if chain_start == spektrafilm_gpu::ChainStart::Rgb && self.params.camera.auto_exposure {
            let ae_ev = match self.params.camera.preview_metered_ev.map(|ev|ev as f64).or_else(||band.and_then(|b| b.ae_ev)) {
                // Banded export: AE was metered once on the whole image by
                // the caller — inject it instead of re-metering on the band.
                Some(ev) => ev,
                None => {
                    let Some(f64_image) = image.f64_image() else {
                        // The f32 export fast path supplies its full-source AE via
                        // preview_metered_ev, separately from manual compensation.
                        // Unresolved AE with f32 input means a caller bug; fall back
                        // to the CPU path, which meters correctly.
                        return None;
                    };
                    // Borrow back the f32 matrix shape that the CPU metering
                    // function expects.
                    let rgb_to_xyz_f32 =
                        stages::filming::input_colorspace_to_xyz(&self.params.io.input_color_space);
                    stages::filming::measure_autoexposure_ev(
                        f64_image,
                        &rgb_to_xyz_f32,
                        &self.params.camera.auto_exposure_method,
                    ) as f64
                }
            };
            exposure_scale_f64 *= 2.0f64.powf(ae_ev);
        }
        if self.params.camera.exposure_compensation_ev != 0.0 {
            exposure_scale_f64 *= 2.0f64.powf(self.params.camera.exposure_compensation_ev as f64);
        }
        // B&W/slide filming exposure correction is a linear scale on the raw
        // film exposure (CPU: `raw *= factor` before log10). Since both
        // upsamplers are homogeneous in the input RGB, folding it into the
        // exposure scale is equivalent. 1.0 (no-op) on every path except a
        // corrected slide scan.
        exposure_scale_f64 *= color_ref.filming_exposure_correction;
        let fold_exposure = |m: &mut [[f64; 3]; 3]| {
            if (exposure_scale_f64 - 1.0).abs() > 1e-9 {
                for row in m.iter_mut() {
                    for v in row.iter_mut() {
                        *v *= exposure_scale_f64;
                    }
                }
            }
        };

        // Front pass: hanatos TC LUT lookup, or the mallett 3×3 matmul
        // (`core · M_cs`, same fold as the CPU `expose` dispatch).
        let front = if let Some(tc_lut) = self.spectral.tc_lut.as_ref() {
            let ref_illuminant = select_illuminant(&self.spectral.front_illuminant);
            let mut rgb_to_adapted = spektrafilm_math::spectral::build_rgb_to_adapted_xyz(
                &self.params.io.input_color_space,
                ref_illuminant,
                self.params.settings.use_cat16,
            );
            fold_exposure(&mut rgb_to_adapted);
            spektrafilm_gpu::FrontPass::Hanatos2025 {
                tc_lut,
                rgb_to_adapted_xyz: rgb_to_adapted,
            }
        } else {
            let core = self.spectral.mallett_core.as_ref()?;
            let mut matrix = crate::mallett::film_matrix(core, &self.params.io.input_color_space);
            fold_exposure(&mut matrix);
            spektrafilm_gpu::FrontPass::Mallett2019 { matrix }
        };

        // Film density curves: normalized (filming.develop subtracts nanmin).
        let film_log_exp = if self.film.is_omitted(){vec![-10.,10.]}else{self.film.log_exposure_f64()};
        let film_curves = self.film.density_curves_f64();
        let film_curves_norm = if self.film.is_omitted(){vec![[-10.;3],[10.;3]]}else{
            spektrafilm_model::density_curves::normalize_density_curves_f64(&film_curves)};
        let mut film_channel_density: Vec<[f64; 3]> = self
            .film
            .data
            .channel_density
            .iter()
            .map(|r| {
                [
                    r.first().copied().unwrap_or(0.0),
                    r.get(1).copied().unwrap_or(0.0),
                    r.get(2).copied().unwrap_or(0.0),
                ]
            })
            .collect();
        let mut film_base_density = self.film.data.base_density.clone();
        if self.film.is_omitted(){film_channel_density=vec![[0.;3];81];film_base_density=vec![0.;81];}

        // Print sensitivity (10**log_sensitivity, NaN→0).
        let mut print_sens: Vec<[f64; 3]> = self
            .print
            .log_sensitivity_f64()
            .iter()
            .map(|row| {
                let mut o = [0.0; 3];
                for c in 0..3 {
                    let v = 10f64.powf(row[c]);
                    o[c] = if v.is_nan() { 0.0 } else { v };
                }
                o
            })
            .collect();
        let print_log_exp = if self.print.is_omitted(){vec![-10.,10.]}else{self.print.log_exposure_f64()};
        if self.print.is_omitted(){print_sens=vec![[0.;3];81];}
        // Print density curves: model-evaluated at gamma 1 whenever the
        // profile carries a fitted `density_curves_model` (identity when the
        // morph is inactive, morphed when active — matching the CPU
        // `develop_print_morph` path); stored RAW curves at the configured
        // gamma only for model-less profiles. Computed once on CPU here so
        // the resident print density pass needs no shader change.
        let morph = &self.params.print_render.density_curves_morph;
        let (mut print_curves, print_gamma_eff) = match self.print.data.density_curves_model.as_ref() {
            Some(model) => match crate::print_morph::morph_density_curves(
                &print_log_exp,
                model,
                morph,
                self.print.is_positive(),
            ) {
                Ok(curves) => (curves, 1.0),
                Err(e) => {
                    tracing::error!(error = %e, "print-curve model eval failed; using stored curves");
                    (
                        self.print.density_curves_f64(),
                        self.params.print_render.density_curve_gamma as f64,
                    )
                }
            },
            None => (
                self.print.density_curves_f64(),
                self.params.print_render.density_curve_gamma as f64,
            ),
        };
        if self.print.is_omitted(){print_curves=vec![[-10.;3],[10.;3]];}
        let mut print_channel_density: Vec<[f64; 3]> = self
            .print
            .data
            .channel_density
            .iter()
            .map(|r| {
                [
                    r.first().copied().unwrap_or(0.0),
                    r.get(1).copied().unwrap_or(0.0),
                    r.get(2).copied().unwrap_or(0.0),
                ]
            })
            .collect();
        let mut print_base_density = self.print.data.base_density.clone();
        if self.print.is_omitted(){print_channel_density=vec![[0.;3];81];print_base_density=vec![0.;81];}

        // Scanning: viewing illuminant + normalization + combined XYZ→RGB
        // matrix. For scan_film we scan the developed film directly, so the
        // viewing illuminant and dye-density wavelength count come from the
        // film, not the print (mirrors the CPU scanning stage, which is
        // handed `self.film` as its profile when scan_film).
        let scan_profile = if self.params.io.scan_film {
            &self.film
        } else {
            &self.print
        };
        // Use the same viewing spectrum as CPU scan, including projector
        // K75P used by 2383/2393. Falling back to D50 changes their colors.
        let viewing_illu_f32 = select_illuminant(&scan_profile.info.viewing_illuminant);
        let viewing_illu: Vec<f64> = viewing_illu_f32.iter().map(|&v| v as f64).collect();
        let n_wl = if self.params.io.scan_film {
            film_channel_density.len()
        } else {
            print_channel_density.len()
        };
        let scan_norm: f64 = (0..n_wl)
            .map(|i| viewing_illu[i] * spektrafilm_math::spectral::CMF_Y[i] as f64)
            .sum();
        let viewing_white = spektrafilm_math::spectral::illuminant_xyz_f64(viewing_illu_f32);
        let output_white = spektrafilm_math::spectral::colorspace_white_xyz_f64(
            &self.params.io.output_color_space,
        );
        let adapt = spektrafilm_math::colorspace::chromatic_adaptation_matrix_f64(
            viewing_white,
            output_white,
        );
        let base_xyz_to_rgb = match self.params.io.output_color_space.as_str() {
            "sRGB" => spektrafilm_math::colorspace::XYZ_TO_SRGB_F64,
            "ProPhoto RGB" => spektrafilm_math::colorspace::XYZ_TO_PROPHOTO_F64,
            "Rec. 2020" | "Rec2020" | "ITU-R BT.2020" => {
                spektrafilm_math::colorspace::XYZ_TO_REC2020_F64
            }
            "ACES2065-1" => spektrafilm_math::colorspace::XYZ_TO_ACES_F64,
            _ => spektrafilm_math::colorspace::XYZ_TO_SRGB_F64,
        };
        let mut scan_xyz_to_rgb = [[0.0f64; 3]; 3];
        for i in 0..3 {
            for j in 0..3 {
                scan_xyz_to_rgb[i][j] = base_xyz_to_rgb[i][0] * adapt[0][j]
                    + base_xyz_to_rgb[i][1] * adapt[1][j]
                    + base_xyz_to_rgb[i][2] * adapt[2][j];
            }
        }

        // Digital readout produces linear sRGB, not XYZ. Keep the output
        // matrix explicit so wide-gamut exports are not merely relabelled.
        let neutral_media=self.omitted_calibration.as_ref().map(|c| {
            let bridge=c.negative;
            spektrafilm_gpu::NeutralMediaGpuParams {
                film_omitted:self.film.is_omitted(),paper_omitted:self.print.is_omitted(),
                negative_readout:bridge.is_some(),paper_gain:c.paper_gain,paper_gray_log:c.paper_gray.unwrap_or(0.),
                paper_log_min:c.paper_log_bounds[0],paper_log_max:c.paper_log_bounds[1],
                negative_min:bridge.map_or([0.;3],|b|b.minimum),
                negative_range:bridge.map_or([1.;3],|b|std::array::from_fn(|i|b.maximum[i]-b.minimum[i])),
                negative_gamma:bridge.map_or([1.;3],|b|b.gamma),
                grain_reference:self.donor_grain.as_ref().filter(|_|self.params.film_render.grain.active && self.params.film_render.grain.amount>0.).map(|d|spektrafilm_gpu::NeutralGrainReference{density:d.density.map(|x|x as f32),baseline:d.baseline(&self.params.film_render.grain).map(|x|x as f32),amount:self.params.film_render.grain.amount as f32}),
            }
        });
        if neutral_media.as_ref().is_some_and(|m|m.paper_omitted && (m.film_omitted || m.negative_readout)) {
            let target=match self.params.io.output_color_space.as_str(){
                "Display P3"=>crate::output_color::XYZ_TO_P3,
                "Adobe RGB (1998)"=>crate::output_color::XYZ_TO_ADOBE,
                _=>spektrafilm_math::colorspace::XYZ_TO_SRGB_F64,
            };
            scan_xyz_to_rgb=if self.params.io.output_color_space=="sRGB" {[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]}else{
                std::array::from_fn(|i|std::array::from_fn(|j|(0..3).map(|k|target[i][k]*spektrafilm_math::colorspace::SRGB_TO_XYZ_F64[k][j]).sum()))
            };
        }

        // Honor the export chunking override (full-image physical pitch)
        // exactly like the CPU stages; falls back to the input dims.
        let pix_um = self.params.camera.pixel_size_um_override.unwrap_or_else(|| {
            stages::filming::pixel_size_um(
                self.params.camera.film_format_mm,
                image.width(),
                image.height(),
            )
        });

        // print_exposure_factor × print_exposure, with the B&W printing
        // exposure correction folded in (CPU applies it as a further raw
        // multiply; print_spectral applies the whole product as one
        // normalization). 1.0 except on a corrected print scan.
        let print_exposure_scale =
            self.params.enlarger.print_exposure as f64 * color_ref.printing_exposure_correction;
        let print_norm_factor = self.print_exposure_factor * print_exposure_scale;

        // Halation in the resident chain — only built when the halation
        // stage is active. Mirrors `apply_halation_um`: averages the
        // per-channel µm sigmas, converts to pixel space, and passes the
        // resulting scalars to the shaders.
        let halation = if self.params.film_render.halation.active {
            // Honor the export chunking override (full-image physical
            // pitch) exactly like the CPU stages; falls back to input dims.
            let pix_um = self.params.camera.pixel_size_um_override.unwrap_or_else(|| {
                stages::filming::pixel_size_um(
                    self.params.camera.film_format_mm,
                    image.width(),
                    image.height(),
                )
            });
            let h = &self.params.film_render.halation;
            // GPU shaders take f32 — narrow at the boundary.
            let avg_f64 = |a: [f64; 3]| (a[0] + a[1] + a[2]) / 3.0;
            let strength_avg = (avg_f64(h.halation_strength) * h.halation_amount) as f32;
            let a_tot = [
                (h.halation_strength[0] * h.halation_amount) as f32,
                (h.halation_strength[1] * h.halation_amount) as f32,
                (h.halation_strength[2] * h.halation_amount) as f32,
            ];
            Some(spektrafilm_gpu::HalationGpuParams {
                back_reflection: if h.back_reflection && h.halation_amount > 0.0 && h.base_thickness_um > 0.0 {
                    Some(spektrafilm_math::halation::back_reflection_components(
                        h.base_thickness_um, h.halation_spatial_scale, h.halation_amount, pix_um as f64,
                    ).map(|c| spektrafilm_gpu::HalationComponentGpu {
                        weight: c.weight.map(|v| v as f32), sigma_px: c.sigma_px.map(|v| v as f32),
                    }))
                } else { None },
                scatter_amount: h.scatter_amount as f32,
                scatter_core_px: (avg_f64(h.scatter_core_um) * h.scatter_spatial_scale
                    / pix_um as f64) as f32,
                scatter_tail_px: (avg_f64(h.scatter_tail_um) * h.scatter_spatial_scale
                    / pix_um as f64) as f32,
                scatter_tail_weight: [
                    h.scatter_tail_weight[0] as f32,
                    h.scatter_tail_weight[1] as f32,
                    h.scatter_tail_weight[2] as f32,
                ],
                halation_amount: h.halation_amount as f32,
                halation_strength_avg: strength_avg,
                halation_a_tot: a_tot,
                halation_first_sigma_px: (avg_f64(h.halation_first_sigma_um)
                    * h.halation_spatial_scale
                    / pix_um as f64) as f32,
                halation_n_bounces: h.halation_n_bounces,
                halation_bounce_decay: h.halation_bounce_decay as f32,
                halation_renormalize: h.halation_renormalize,
            })
        } else {
            None
        };

        // DIR couplers in the resident chain. Mirrors CPU
        // `apply_density_correction`: build the scaled couplers matrix and
        // pre-compute the "density curves before DIR" once. The shader
        // re-interpolates these against `log_raw - correction`.
        // Held in this binding so `&density_curves_0_f64` outlives the
        // backend call.
        let dir_inputs = if self.params.film_render.dir_couplers.active {
            // Honor the export chunking override (full-image physical
            // pitch) exactly like the CPU stages; falls back to input dims.
            let pix_um = self.params.camera.pixel_size_um_override.unwrap_or_else(|| {
                stages::filming::pixel_size_um(
                    self.params.camera.film_format_mm,
                    image.width(),
                    image.height(),
                )
            });
            let dir = &self.params.film_render.dir_couplers;
            let matrix = spektrafilm_model::couplers::compute_dir_couplers_matrix(
                dir.gamma_samelayer_rgb,
                dir.gamma_interlayer_r_to_gb,
                dir.gamma_interlayer_g_to_rb,
                dir.gamma_interlayer_b_to_rg,
                dir.inhibition_samelayer,
                dir.inhibition_interlayer,
            );
            let mut matrix_scaled = matrix;
            for row in &mut matrix_scaled {
                for v in row.iter_mut() {
                    *v *= dir.amount;
                }
            }
            let film_curves_f32 = self.film.density_curves_f32();
            let film_log_exp_f32 = self.film.log_exposure_f32();
            let norm_curves_f32 =
                spektrafilm_model::density_curves::normalize_density_curves(&film_curves_f32);
            // Narrow matrix to f32 just for the compute_curves_before_dir helper
            // (which still operates on f32 density curves from the profile).
            let matrix_scaled_f32: [[f32; 3]; 3] = [
                [
                    matrix_scaled[0][0] as f32,
                    matrix_scaled[0][1] as f32,
                    matrix_scaled[0][2] as f32,
                ],
                [
                    matrix_scaled[1][0] as f32,
                    matrix_scaled[1][1] as f32,
                    matrix_scaled[1][2] as f32,
                ],
                [
                    matrix_scaled[2][0] as f32,
                    matrix_scaled[2][1] as f32,
                    matrix_scaled[2][2] as f32,
                ],
            ];
            let density_curves_0_f32 = spektrafilm_model::couplers::compute_curves_before_dir(
                &norm_curves_f32,
                &film_log_exp_f32,
                &matrix_scaled_f32,
                self.film.is_positive(),
            );
            let density_curves_0_f64: Vec<[f64; 3]> = density_curves_0_f32
                .iter()
                .map(|row| [row[0] as f64, row[1] as f64, row[2] as f64])
                .collect();
            let density_max_f32 = spektrafilm_model::density_curves::max_density(&norm_curves_f32);
            Some((
                density_curves_0_f64,
                matrix_scaled,
                density_max_f32,
                pix_um,
                dir.diffusion_size_um,
                dir.diffusion_tail_um,
                dir.diffusion_tail_weight,
                self.film.is_positive(),
                self.params.film_render.density_curve_gamma as f64,
            ))
        } else {
            None
        };
        let dir_couplers = dir_inputs.as_ref().map(|d| {
            // GPU shader path is f32 — narrow at the boundary.
            let matrix_f32: [[f32; 3]; 3] = [
                [d.1[0][0] as f32, d.1[0][1] as f32, d.1[0][2] as f32],
                [d.1[1][0] as f32, d.1[1][1] as f32, d.1[1][2] as f32],
                [d.1[2][0] as f32, d.1[2][1] as f32, d.1[2][2] as f32],
            ];
            spektrafilm_gpu::DirCouplersGpuParams {
                couplers_matrix_scaled: matrix_f32,
                density_max: d.2,
                is_positive: d.7,
                diffusion_size_px: (d.4 / d.3 as f64) as f32,
                diffusion_tail_px: (d.5 / d.3 as f64) as f32,
                diffusion_tail_weight: d.6 as f32,
                density_curves_0: &d.0,
                log_exposure: &film_log_exp,
                gamma_factor: d.8,
            }
        });

        // Grain in the resident chain — same per-channel particle math as
        // `apply_grain_to_density`. The GPU uses normal-approximation
        // sampling; CPU does the same whenever λ > 30 / var > 9, which is
        // the typical regime for ≥ 1 MP images.
        let grain = if self.params.film_render.grain.active && self.params.film_render.grain.amount > 0. {
            let grain_film=self.donor_grain.as_ref().map_or(&self.film,|d|&d.profile);
            // Honor the export chunking override (full-image physical
            // pitch) exactly like the CPU stages; falls back to input dims.
            let pix_um = self.params.camera.pixel_size_um_override.unwrap_or_else(|| {
                stages::filming::pixel_size_um(
                    self.params.camera.film_format_mm,
                    image.width(),
                    image.height(),
                )
            });
            let g = &self.params.film_render.grain;
            let pixel_area = pix_um * pix_um;
            let n_sub = g.n_sub_layers.max(1);
            // GPU shaders are f32 — narrow the f64 grain params at the boundary.
            let mut npp = [0.0f32; 3];
            for c in 0..3 {
                let particle_area = g.agx_particle_area_um2 * g.agx_particle_scale[c];
                npp[c] = ((pixel_area as f64 / particle_area) / n_sub as f64) as f32;
            }
            let film_curves_f32 = grain_film.density_curves_f32();
            let norm_curves_f32 =
                spektrafilm_model::density_curves::normalize_density_curves(&film_curves_f32);
            let dmax_curves = spektrafilm_model::density_curves::max_density(&norm_curves_f32);
            let mut density_max = [0.0f32; 3];
            for c in 0..3 {
                density_max[c] = dmax_curves[c] + g.density_min[c] as f32;
            }

            // Sublayer curve/particle data (`apply_grain_layers`) — the CPU
            // default (`grain.sublayers_active`). Builds the per-sublayer
            // curve tables and particle params for `grain_layers.wgsl`;
            // rejects the resident attempt when requested layer curves
            // are malformed or degenerate; no silent model switch.
            let sublayers = if g.sublayers_active {
                Some(build_grain_sublayers(grain_film, g, pixel_area, pix_um)?)
            } else {
                None
            };

            // Approximate per-sublayer dye-cloud convolution with one RMS
            // effective blur. The user control remains active; it is not
            // silently discarded as in the source v41 implementation.
            let dye_variance = sublayers.as_ref().map_or(0.0, |sl| {
                if g.blur_dye_clouds_um <= 0.4 { return 0.0; }
                sl.sl_params.iter().map(|p| {
                    let sigma = g.blur_dye_clouds_um * p[1].sqrt();
                    if sigma > 0.1 { sigma * sigma } else { 0.0 }
                }).sum::<f32>() / 9.0
            });
            let final_blur = if g.sublayers_active || g.blur > 0.4 { g.blur.max(0.0) } else { 0.0 };
            let effective_blur = (final_blur * final_blur + dye_variance).sqrt();
            let micro_sigma = g.micro_structure[1] * 0.001 / pix_um;
            let microstructure = (g.sublayers_active && micro_sigma > 0.05)
                .then_some([micro_sigma, g.micro_structure[0] / pix_um]);
            Some(spektrafilm_gpu::GrainGpuParams {
                density_min: [
                    g.density_min[0] as f32,
                    g.density_min[1] as f32,
                    g.density_min[2] as f32,
                ],
                density_max,
                n_particles_per_pixel: npp,
                grain_uniformity: [
                    g.uniformity[0] as f32,
                    g.uniformity[1] as f32,
                    g.uniformity[2] as f32,
                ],
                n_sub_layers: n_sub,
                base_seed: (g.seed as u32) ^ ((g.seed >> 32) as u32).rotate_left(13)
                    ^ if g.coordinate_origin.is_some() { 0x4558_5054 } else { 0x5052_4556 },
                grain_blur: effective_blur,
                microstructure,
                monochrome: g.monochrome,
                pixel_offset,
                full_width,
                sublayers,
            })
        } else {
            None
        };

        // Glare in the resident chain — applied after scan_spectral on the
        // final RGB buffer. Mirrors the CPU lognormal + blur + add. The CPU
        // scanning stage reads `film_render.glare` on the scan_film path and
        // `print_render.glare` on the print path — follow the same choice.
        let glare_params = if self.params.io.scan_film {
            &self.params.film_render.glare
        } else {
            &self.params.print_render.glare
        };
        let glare = if glare_params.active && glare_params.percent > 0.0 {
            let g = glare_params;
            // LogNormal parameters (same derivation as `compute_random_glare_amount`).
            let m = g.percent as f64;
            let s = (g.roughness * g.percent) as f64;
            let sigma2 = (1.0 + (s * s) / (m * m)).ln();
            let sigma = sigma2.sqrt();
            let mu = m.ln() - sigma2 / 2.0;
            // glare_rgb_offset = (XYZ→RGB) · illuminant_xyz / 100.
            let mut illu_xyz = [0.0f64; 3];
            for i in 0..n_wl {
                illu_xyz[0] += viewing_illu[i] * spektrafilm_math::spectral::CMF_X[i] as f64;
                illu_xyz[1] += viewing_illu[i] * spektrafilm_math::spectral::CMF_Y[i] as f64;
                illu_xyz[2] += viewing_illu[i] * spektrafilm_math::spectral::CMF_Z[i] as f64;
            }
            for c in 0..3 {
                illu_xyz[c] /= scan_norm;
            }
            let mut offset_rgb = [0.0f32; 3];
            for i in 0..3 {
                let v = scan_xyz_to_rgb[i][0] * illu_xyz[0]
                    + scan_xyz_to_rgb[i][1] * illu_xyz[1]
                    + scan_xyz_to_rgb[i][2] * illu_xyz[2];
                offset_rgb[i] = (v / 100.0) as f32;
            }
            Some(spektrafilm_gpu::GlareGpuParams {
                mu: mu as f32,
                sigma: sigma as f32,
                blur_px: g.blur,
                base_seed: 42,
                rgb_offset: offset_rgb,
                pixel_offset,
                full_width,
            })
        } else {
            None
        };

        // Unsharp mask: scanner.unsharp_mask = [sigma, amount].
        let [usm_sigma, usm_amount] = self.params.scanner.unsharp_mask;
        let unsharp = if usm_sigma > 0.0 && usm_amount > 0.0 {
            Some(spektrafilm_gpu::UnsharpGpuParams {
                sigma_px: usm_sigma,
                amount: usm_amount,
            })
        } else {
            None
        };

        let camera_lens_blur_px = if self.params.camera.lens_blur_um > 0.0 {
            Some(self.params.camera.lens_blur_um / pix_um)
        } else {
            None
        };

        let scanner_lens_blur_px = if self.params.scanner.lens_blur > 0.0 {
            Some(self.params.scanner.lens_blur)
        } else {
            None
        };

        let hboost = &self.params.film_render.halation;
        let highlight_boost = if hboost.boost_ev != 0.0 || hboost.headroom_active() {
            Some(spektrafilm_gpu::HighlightBoostGpuParams {
                headroom_ev: if hboost.headroom_active() { hboost.headroom_ev as f32 } else { 0.0 },
                headroom_from_ev: hboost.headroom_from_ev as f32,
                headroom_to_ev: hboost.headroom_to_ev as f32,
                max_raw_override: diffusion_fields.and_then(|fields| fields.highlight_max_raw),
                boost_ev: hboost.boost_ev as f32,
                boost_range: hboost.boost_range as f32,
                protect_ev: hboost.protect_ev as f32,
            })
        } else {
            None
        };

        // Camera diffusion filter plan (downsampled sum-of-Gaussians) for the
        // resident chain. `None` when the filter is a no-op. Plans are only
        // built for the whole-frame resident chain (no band context, full
        // chain run): the banded chain consumes precomputed scattered fields
        // instead, and the pre-pass stop modes run without diffusion.
        let whole_frame_full_chain =
            chain_stop == spektrafilm_gpu::ChainStop::Full && band.is_none();
        // FilmLog is after camera diffusion too; whole-frame prefix taps must
        // build the same internal field as a full resident preview.
        let whole_frame_camera = band.is_none() && chain_start == spektrafilm_gpu::ChainStart::Rgb
            && matches!(chain_stop, spektrafilm_gpu::ChainStop::Full | spektrafilm_gpu::ChainStop::FilmLog);
        let diffusion = if whole_frame_camera && self.params.camera.diffusion_filter.active {
            spektrafilm_model::diffusion::diffusion_gpu_plan(
                &self.params.camera.diffusion_filter.to_model(),
                pix_um as f64,
                image.width(),
                image.height(),
            )
        } else {
            None
        };

        let enlarger_diffusion = if whole_frame_full_chain
            && !self.params.io.scan_film
            && self.params.enlarger.diffusion_filter.active
        {
            spektrafilm_model::diffusion::diffusion_gpu_plan(
                &self.params.enlarger.diffusion_filter.to_model(),
                pix_um as f64,
                image.width(),
                image.height(),
            )
        } else {
            None
        };

        // Banded diffusion: precomputed whole-frame scattered fields, mixed
        // in per band. The camera field is also mixed during the PrintLinear
        // pre-pass (so the enlarger tap sees the camera-diffused state); the
        // Front pre-pass mixes nothing. `band_y0` is the global row of the
        // band's first (halo) row.
        let band_y0 = pixel_offset / full_width;
        let band_x0 = pixel_offset % full_width;
        let diffusion_scattered = if chain_stop != spektrafilm_gpu::ChainStop::Front {
            diffusion_fields
                .and_then(|f| f.camera.as_ref())
                .map(|s| s.to_gpu(band_x0, band_y0))
        } else {
            None
        };
        let enlarger_scattered =
            if chain_stop == spektrafilm_gpu::ChainStop::Full && band.is_some() {
                diffusion_fields
                    .and_then(|f| f.enlarger.as_ref())
                    .map(|s| s.to_gpu(band_x0, band_y0))
            } else {
                None
            };

        let mut params = spektrafilm_gpu::FilmChainParams {
            neutral_media,
            start_at: chain_start,
            // Bounded preview tiles retain GPU gamut but need the same phase-local
            // scratch retirement as export. Ordinary whole preview stays unchanged.
            stage_batched: (defer_gamut_to_cpu || band.is_some()) && image.pixel_count() > 256 * 256,
            image,
            front,
            film_log_exposure: &film_log_exp,
            film_density_curves_normalized: &film_curves_norm,
            film_gamma: self.params.film_render.density_curve_gamma as f64,
            film_channel_density: &film_channel_density,
            film_base_density: &film_base_density,
            print_illuminant: &self.print_illuminant,
            print_sensitivity: &print_sens,
            print_normalization_factor: print_norm_factor,
            print_log_exposure: &print_log_exp,
            print_density_curves: &print_curves,
            print_gamma: print_gamma_eff,
            print_channel_density: &print_channel_density,
            print_base_density: &print_base_density,
            preflash: self.preflash_raw,
            viewing_illuminant: &viewing_illu,
            scan_normalization: scan_norm,
            scan_xyz_to_rgb: &scan_xyz_to_rgb,
            bw_xyz_remap: color_ref.xyz_remap(),
            scan_film: self.params.io.scan_film,
            halation,
            dir_couplers,
            grain,
            glare,
            gamut: self.output_gamut.gpu_params(),
            unsharp,
            camera_lens_blur_px,
            scanner_lens_blur_px,
            highlight_boost,
            diffusion,
            enlarger_diffusion,
            diffusion_scattered,
            enlarger_scattered,
            stop_after: chain_stop,
            print_exposure_scale,
            output_cctf_encoding: self.params.io.output_cctf_encoding,
            defer_gamut_to_cpu,
        };
        if chain_start != spektrafilm_gpu::ChainStart::Rgb {
            if chain_stop != spektrafilm_gpu::ChainStop::Full
                && !(chain_start == spektrafilm_gpu::ChainStart::FilmLog && chain_stop == spektrafilm_gpu::ChainStop::CorrectedDensity)
                && !(chain_start == spektrafilm_gpu::ChainStart::PostHalationRaw && chain_stop == spektrafilm_gpu::ChainStop::FilmLog) { return None; }
            params.halation = None;
            params.camera_lens_blur_px = None;
            params.highlight_boost = None;
            params.diffusion = None;
            params.diffusion_scattered = None;
        }
        if chain_start == spektrafilm_gpu::ChainStart::CorrectedDensity { params.dir_couplers = None; }
        if chain_stop == spektrafilm_gpu::ChainStop::CorrectedDensity {
            params.grain = None; params.glare = None;
            params.enlarger_diffusion = None; params.enlarger_scattered = None;
        }
        if matches!(chain_stop, spektrafilm_gpu::ChainStop::FilmLog | spektrafilm_gpu::ChainStop::ScatteredRaw) {
            params.dir_couplers = None;
            params.grain = None;
            params.glare = None;
            params.enlarger_diffusion = None;
            params.enlarger_scattered = None;
        }
        if let Some(capture) = capture {
            backend.try_run_film_chain_capture_controlled(&params, capture, checkpoint)
        } else {
            backend.try_run_film_chain_controlled(&params, checkpoint)
        }
    }

    /// After the GPU fast path returns linear RGB, apply sRGB encoding + clip
    /// (the only post-scan op when no scanner blur/unsharp/glare is active).
    fn apply_post_scan(&self, mut rgb: ImageBuf) -> ImageBuf {
        use rayon::prelude::*;
        use spektrafilm_math::precision::from_f64;
        let zero = from_f64(0.0);
        let one = from_f64(1.0);
        if self.params.io.output_cctf_encoding {
            rgb.data.par_iter_mut().for_each(|v| {
                *v = spektrafilm_math::precision::srgb_encode((*v).clamp(zero, one));
            });
        } else {
            rgb.data
                .par_iter_mut()
                .for_each(|v| *v = (*v).clamp(zero, one));
        }
        rgb
    }
}

pub(crate) fn select_illuminant(name: &str) -> &'static [f32] {
    use spektrafilm_math::spectral;
    match name {
        "T" => &crate::extra_illuminants::T_F32,
        "K75P" => &crate::extra_illuminants::K75P_F32,
        "D50" => &spectral::ILLUMINANT_D50,
        "D55" => &spectral::ILLUMINANT_D55,
        "D65" => &spectral::ILLUMINANT_D65,
        _ => &spectral::ILLUMINANT_D55,
    }
}

pub(crate) fn select_illuminant_f64(name: &str) -> &'static [f64] {
    use spektrafilm_math::spectral;
    match name {
        "T" => &crate::extra_illuminants::T_F64,
        "K75P" => &crate::extra_illuminants::K75P_F64,
        "D50" => &spectral::ILLUMINANT_D50_F64,
        "D55" => &spectral::ILLUMINANT_D55_F64,
        "D65" => &spectral::ILLUMINANT_D65_F64,
        _ => &spectral::ILLUMINANT_D55_F64,
    }
}

#[cfg(test)]
mod prepared_streamed_tests {
    use super::*;
    use spektrafilm_gpu::cpu_backend::CpuBackend;
    use spektrafilm_math::precision::from_f64;
    #[test]
    fn prepared_finish_matches_uncached_stages_exactly_across_chunks() {
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        for (film_name,paper_name,contrast,encode) in [
            ("kodak_portra_400","kodak_portra_endura",1.0,false),
            ("kodak_gold_200","kodak_portra_endura",1.3,true),
            ("fujifilm_c200","fujifilm_crystal_archive_typeii",0.7,false),
        ] {
            let film=crate::profile::load_profile_by_name(&data,film_name).unwrap();
            let paper=crate::profile::load_profile_by_name(&data,paper_name).unwrap();
            let mut p=RuntimeParams::default();p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;
            p.io.output_cctf_encoding=encode;p.print_render.glare.active=false;p.scanner.lens_blur=0.;p.scanner.unsharp_mask=[0.,0.];
            p.print_render.density_curves_morph.active=true;p.print_render.density_curves_morph.gamma_factor=contrast;p.enlarger.y_filter_shift=-7.;
            let pipe=Pipeline::new_with_spectral(film,paper,p,&data).unwrap();let backend=CpuBackend;
            let prepared=pipe.prepare_streamed_finish(&backend).unwrap();assert!(prepared.lut_storage_bytes()<16*1024*1024);
            let color_ref=crate::color_reference::ColorReference::compute(&pipe.film,&pipe.print,&pipe.params,&pipe.print_illuminant,pipe.print_exposure_factor);
            for (w,h) in [(1,1),(17,9),(31,3)] {
                let values=(0..w*h*3).map(|i|from_f64(-0.05+2.8*((i*31%211)as f64/211.))).collect();
                let density=ImageBuf::from_data(w,h,values);
                let reference_print=stages::printing::process_with_calibration(&density,&pipe.film,&pipe.print,&pipe.params,&backend,&pipe.print_illuminant,pipe.print_exposure_factor,pipe.preflash_raw,color_ref.printing_exposure_correction);
                let reference=stages::scanning::process(&reference_print,&pipe.print,&pipe.params,&backend,&color_ref,&pipe.output_gamut);
                let actual=prepared.process_density_chunk(&density,&backend);
                assert_eq!(actual.data,reference.data,"cached finish changed math for {film_name}/{paper_name} {w}x{h}");
            }
        }
    }
    #[test]
    fn split_print_raw_matches_reference_all_diffusion_families() {
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let film=crate::profile::load_profile_by_name(&data,"kodak_portra_400").unwrap();
        let paper=crate::profile::load_profile_by_name(&data,"kodak_portra_endura").unwrap();
        for family in ["glimmerglass","black_pro_mist","pro_mist","cinebloom"] {
            let mut p=RuntimeParams::default();p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;
            p.settings.lut_resolution=17;p.print_render.glare.active=false;
            p.enlarger.diffusion_filter.active=true;p.enlarger.diffusion_filter.filter_family=family.into();
            p.enlarger.diffusion_filter.strength=0.8;p.enlarger.print_exposure=1.7;
            p.enlarger.preflash_exposure=0.03;p.enlarger.preflash_y_filter_shift=5.;p.enlarger.preflash_m_filter_shift=-3.;
            let pipe=Pipeline::new_with_spectral(film.clone(),paper.clone(),p,&data).unwrap();let backend=CpuBackend;
            let prepared=pipe.prepare_streamed_finish_spatial(&backend).unwrap();
            let color_ref=crate::color_reference::ColorReference::compute(&pipe.film,&pipe.print,&pipe.params,&pipe.print_illuminant,pipe.print_exposure_factor);
            let (w,h)=(19,13);
            let density=ImageBuf::from_data(w,h,(0..w*h*3).map(|i|from_f64(-0.05+2.8*((i*31%211)as f64/211.))).collect());
            let reference=stages::printing::process_with_calibration(&density,&pipe.film,&pipe.print,&pipe.params,&backend,&pipe.print_illuminant,pipe.print_exposure_factor,pipe.preflash_raw,color_ref.printing_exposure_correction);
            let raw=prepared.expose_print_raw_chunk(&density).unwrap();
            let pitch=stages::filming::pixel_size_um(pipe.params.camera.film_format_mm,w,h);
            let filtered=spektrafilm_model::diffusion::apply_diffusion_filter_um(&raw,&pipe.params.enlarger.diffusion_filter.to_model(),pitch as f64);
            let actual=prepared.develop_print_raw_chunk(&filtered,&backend).unwrap();
            assert_eq!(actual.data,reference.data,"split print changed {family}");
            let expected_rgb=prepared.scanning.as_ref().expect("physical scan fixture").process_unclipped(&reference);
            assert_eq!(prepared.scan_print_density_chunk_unclipped(&actual).unwrap().data,expected_rgb.data);
        }
    }
    #[test]
    fn prepared_finish_requires_explicit_enlarger_spatial_opt_in() {
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let film=crate::profile::load_profile_by_name(&data,"kodak_portra_400").unwrap();let paper=crate::profile::load_profile_by_name(&data,"kodak_portra_endura").unwrap();
        let mut p=RuntimeParams::default();p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.print_render.glare.active=false;
        p.enlarger.diffusion_filter.active=true;let pipe=Pipeline::new_with_spectral(film.clone(),paper.clone(),p.clone(),&data).unwrap();assert!(pipe.prepare_streamed_finish(&CpuBackend).is_err());
        p.enlarger.diffusion_filter.active=false;p.scanner.lens_blur=1.;let pipe=Pipeline::new_with_spectral(film,paper,p,&data).unwrap();assert!(pipe.prepare_streamed_finish(&CpuBackend).is_ok()); // scanner spatial pass is explicitly deferred by this API
    }
}

#[cfg(test)]
mod resident_contract_tests {
    use super::*;
    use spektrafilm_gpu::{ChainImage, ComputeBackend, FilmChainParams, Lut3D};
    use spektrafilm_math::precision::from_f64;
    use std::sync::Mutex;
    #[derive(Default)]
    struct Probe { ranges: Mutex<Vec<(spektrafilm_gpu::ChainStart, spektrafilm_gpu::ChainStop, bool)>>, deferred_gamut: Mutex<Vec<bool>>, calls: Mutex<Vec<(Option<u32>, Option<f32>)>>, blurs: std::sync::atomic::AtomicUsize, fronts: Mutex<Vec<[[f64;3];3]>> }
    impl ComputeBackend for Probe {
        fn colorspace_convert(&self,i:&ImageBuf,_:&[[f32;3];3])->ImageBuf{i.clone()}
        fn cctf_encode_srgb(&self,i:&ImageBuf)->ImageBuf{i.clone()}
        fn cctf_decode_srgb(&self,i:&ImageBuf)->ImageBuf{i.clone()}
        fn gaussian_blur(&self,i:&ImageBuf,_:f32)->ImageBuf{self.blurs.fetch_add(1,std::sync::atomic::Ordering::Relaxed);i.clone()}
        fn table_lookup(&self,i:&ImageBuf,_:&[f32],_:&[[f32;3]])->ImageBuf{i.clone()}
        fn lut3d_interp(&self,i:&ImageBuf,_:&Lut3D)->ImageBuf{i.clone()}
        fn name(&self)->&str{"resident-contract-probe"}
        fn try_run_film_chain(&self,p:&FilmChainParams<'_>)->Option<ImageBuf>{
            self.ranges.lock().unwrap().push((p.start_at,p.stop_after,p.halation.is_some()));
            self.deferred_gamut.lock().unwrap().push(p.defer_gamut_to_cpu);
            self.calls.lock().unwrap().push((p.grain.as_ref().map(|g|g.base_seed),p.highlight_boost.as_ref().and_then(|h|h.max_raw_override)));
            self.fronts.lock().unwrap().push(match &p.front {
                spektrafilm_gpu::FrontPass::Hanatos2025{rgb_to_adapted_xyz,..}=>*rgb_to_adapted_xyz,
                spektrafilm_gpu::FrontPass::Mallett2019{matrix}=>*matrix,
            });
            Some(match p.image {
                ChainImage::F64(i)=>i.clone(),
                ChainImage::F32(data,w,h)=>ImageBuf::from_data(w,h,data.iter().map(|v|from_f64(*v as f64)).collect()),
            })
        }
    }
    fn pipe()->Pipeline {
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let film=crate::profile::load_profile_by_name(&data,"kodak_portra_400").unwrap();
        let paper=crate::profile::load_profile_by_name(&data,"kodak_portra_endura").unwrap();
        let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.camera.pixel_size_um_override=Some(5.);
        p.camera.diffusion_filter.active=false;p.enlarger.diffusion_filter.active=false;p.scanner.unsharp_mask=[0.,0.];
        Pipeline::new_with_spectral(film,paper,p,&data).unwrap()
    }
    #[test]
    fn spatial_endpoints_preserve_domains_and_validate_cancel_geometry() {
        use spektrafilm_gpu::{ChainStart,ChainStop};
        let p=pipe();let b=Probe::default();
        let t=TileContext{full_width:17,full_height:13,x:2,y:3};
        let input=[0.2;18];
        for resume in [false,true] {
            assert!(p.process_gpu_spatial_endpoint_controlled(&input,3,2,t,&b,None,resume,||false).is_none());
            assert!(p.process_gpu_spatial_endpoint_controlled(&[f32::NAN;18],3,2,t,&b,None,resume,||true).is_none());
            assert!(p.process_gpu_spatial_endpoint_controlled(&input,3,2,TileContext{x:16,..t},&b,None,resume,||true).is_none());
            assert!(p.process_gpu_spatial_endpoint_controlled(&input,3,2,t,&b,None,resume,||true).is_some());
        }
        let ranges=b.ranges.lock().unwrap();
        assert_eq!(ranges.len(),2);
        assert_eq!((ranges[0].0,ranges[0].1),(ChainStart::Rgb,ChainStop::ScatteredRaw));
        assert_eq!(ranges[1],(ChainStart::PostHalationRaw,ChainStop::FilmLog,false));
    }
    #[test]
    fn preview_tile_keeps_gpu_gamut_and_rejects_missing_ae_or_cancel() {
        let mut pipe=pipe();let backend=Probe::default();
        let tile=TileContext{full_width:9,full_height:7,x:2,y:3};
        pipe.params.camera.auto_exposure=true;
        assert!(pipe.process_gpu_preview_tile_f32_controlled(&[0.2;12],2,2,tile,&backend,None,||true).is_none());
        pipe.params.camera.preview_metered_ev=Some(0.25);
        assert!(pipe.process_gpu_preview_tile_f32_controlled(&[0.2;12],2,2,tile,&backend,None,||false).is_none());
        assert!(backend.calls.lock().unwrap().is_empty());
        assert!(pipe.process_gpu_preview_tile_f32_controlled(&[0.2;12],2,2,tile,&backend,None,||true).is_some());
        assert_eq!(*backend.deferred_gamut.lock().unwrap(),vec![false]);
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.2;12],2,2,tile,&backend,None,||true).is_some());
        assert_eq!(*backend.deferred_gamut.lock().unwrap(),vec![false,true]);
    }
    #[test]
    fn detail_tile_shared_ae_is_required_and_does_not_remeter_or_change_compensation() {
        let mut pipe=pipe();let backend=Probe::default();
        pipe.params.camera.auto_exposure=true;pipe.params.camera.exposure_compensation_ev=0.75;
        let a=TileContext{full_width:9,full_height:7,x:0,y:0};
        let b=TileContext{x:5,y:3,..a};
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.01;12],2,2,a,&backend,None,||true).is_none());
        assert!(backend.calls.lock().unwrap().is_empty());
        pipe.params.camera.preview_metered_ev=Some(1.25);
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.01;12],2,2,a,&backend,None,||true).is_some());
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[1.0;12],2,2,b,&backend,None,||true).is_some());
        {let fronts=backend.fronts.lock().unwrap();assert_eq!(fronts[0],fronts[1]);}
        assert_eq!(pipe.params.camera.exposure_compensation_ev,0.75);
        pipe.params.camera.preview_metered_ev=Some(2.25);
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.01;12],2,2,b,&backend,None,||true).is_some());
        {let fronts=backend.fronts.lock().unwrap();
            for(i,j)in fronts[0].iter().flatten().zip(fronts[2].iter().flatten()){assert!((i*2.-j).abs()<1e-12);}}
        pipe.params.camera.preview_metered_ev=Some(f32::NAN);
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.01;12],2,2,a,&backend,None,||true).is_none());
        assert_eq!(backend.calls.lock().unwrap().len(),3);
    }
    #[test]
    fn controlled_preview_rejects_stale_work_before_and_after_resident() {
        let pipe=pipe();let backend=Probe::default();let image=ImageBuf::from_data(2,2,vec![from_f64(0.25);12]);
        assert!(pipe.process_resident_borrowed_controlled(&image,&backend,||false).is_none());
        assert!(backend.calls.lock().unwrap().is_empty());
        assert!(pipe.process_resident_borrowed_controlled(&image,&backend,||backend.calls.lock().unwrap().is_empty()).is_none());
        assert_eq!(backend.calls.lock().unwrap().len(),1);
    }
    #[test]
    fn controlled_whole_stops_after_resident_before_scanner_submissions() {
        let mut pipe=pipe();pipe.params.scanner.lens_blur=1.;pipe.params.scanner.unsharp_mask=[1.,0.5];
        let backend=Probe::default();
        assert!(pipe.process_gpu_resident_cpu_gamut_f32_controlled(&[0.25;12],2,2,&backend,||backend.calls.lock().unwrap().is_empty()).is_none());
        assert_eq!(backend.calls.lock().unwrap().len(),1);
        assert_eq!(backend.blurs.load(std::sync::atomic::Ordering::Relaxed),0);
    }
    #[test]
    fn controlled_band_yields_between_scanner_blur_and_unsharp() {
        let mut pipe=pipe();pipe.params.scanner.lens_blur=1.;pipe.params.scanner.unsharp_mask=[1.,0.5];
        let backend=Probe::default();
        assert!(pipe.process_gpu_resident_band_f32_controlled(&[0.25;12],2,2,4,2,&backend,None,
            ||backend.blurs.load(std::sync::atomic::Ordering::Relaxed)==0).is_none());
        assert_eq!(backend.calls.lock().unwrap().len(),1);
        assert_eq!(backend.blurs.load(std::sync::atomic::Ordering::Relaxed),1);
    }
    #[test]
    fn always_true_controlled_entry_keeps_legacy_numeric_output() {
        let mut pipe=pipe();pipe.params.scanner.lens_blur=1.;pipe.params.scanner.unsharp_mask=[1.,0.5];
        let backend=Probe::default();let input=[0.15,0.25,0.5,0.8,0.1,0.3,0.2,0.9,0.1,0.4,0.7,0.5];
        let expected=pipe.process_gpu_resident_cpu_gamut_f32(&input,2,2,&backend).unwrap();
        let actual=pipe.process_gpu_resident_cpu_gamut_f32_controlled(&input,2,2,&backend,||true).unwrap();
        assert_eq!(expected.data,actual.data);
        assert_eq!(backend.blurs.load(std::sync::atomic::Ordering::Relaxed),4);
    }
    #[test]
    fn early_density_uses_scaled_pitch_and_omits_only_grain() {
        let mut pipe = pipe();
        pipe.params.film_render.halation.boost_ev = 0.;
        pipe.params.camera.pixel_size_um_override = Some(5.);
        pipe.params.film_render.grain.active = true;
        let image = ImageBuf::from_data(2,2,vec![from_f64(0.25);12]);
        let actual = pipe.low_resolution_pre_grain_density_cpu(&image,8,8,&mut ||true).unwrap();
        let mut expected_pipe = pipe.clone();
        expected_pipe.params.camera.pixel_size_um_override = Some(20.);
        expected_pipe.params.film_render.grain.active = false;
        let expected = expected_pipe.low_resolution_pre_grain_density_cpu(&image,2,2,&mut ||true).unwrap();
        assert_eq!(actual.data, expected.data);
        assert!(pipe.params.film_render.grain.active);
        assert_eq!(pipe.params.camera.pixel_size_um_override,Some(5.));
        assert!(pipe.low_resolution_pre_grain_density_cpu(&image,8,8,&mut ||false).is_err());
        pipe.params.camera.auto_exposure = true;
        assert!(pipe.low_resolution_pre_grain_density_cpu(&image,8,8,&mut ||true).is_err());
        pipe.params.camera.auto_exposure = false;
        pipe.params.film_render.halation.boost_ev = 1.;
        assert!(pipe.low_resolution_pre_grain_density_cpu(&image,8,8,&mut ||true).is_err());
    }
    #[test]
    fn optional_density_capture_keeps_output_and_clears_stale_results() {
        use spektrafilm_gpu::{FilmDensityCapture, FilmDensitySample};
        let pipe = pipe(); let backend = Probe::default();
        let input = [0.25; 12];
        let mut capture = FilmDensityCapture {
            samples: vec![FilmDensitySample { index: 7, x: 1, y: 0 }],
            result: Some(vec![(7, [99.; 3])]),
        };
        let expected = pipe.process_gpu_resident_cpu_gamut_f32_controlled(&input, 2, 2, &backend, || true).unwrap();
        let actual = pipe.process_gpu_resident_cpu_gamut_f32_capture_controlled(&input, 2, 2, &backend, &mut capture, || true).unwrap();
        assert_eq!(expected.data, actual.data);
        assert!(capture.result.is_none()); // backend does not implement capture
        capture.result = Some(vec![(7, [99.; 3])]);
        assert!(pipe.process_gpu_resident_cpu_gamut_f32_capture_controlled(&input, 2, 2, &backend, &mut capture, || false).is_none());
        assert!(capture.result.is_none());
        let tile = TileContext { full_width: 4, full_height: 4, x: 1, y: 1 };
        let expected = pipe.process_gpu_resident_tile_f32_controlled(&input, 2, 2, tile, &backend, None, || true).unwrap();
        let actual = pipe.process_gpu_resident_tile_f32_capture_controlled(&input, 2, 2, tile, &backend, None, &mut capture, || true).unwrap();
        assert_eq!(expected.data, actual.data);
        assert!(capture.result.is_none());
    }
    #[test]
    fn film_log_suffix_capture_fallback_and_validation_clear_stale_results() {
        use spektrafilm_gpu::{FilmDensityCapture, FilmDensitySample};
        let pipe = pipe(); let backend = Probe::default();
        let lograw = [-0.25; 12];
        let tile = TileContext { full_width: 4, full_height: 4, x: 1, y: 1 };
        let mut capture = FilmDensityCapture {
            samples: vec![FilmDensitySample { index: 7, x: 1, y: 0 }],
            result: Some(vec![(7, [99.; 3])]),
        };
        let expected = pipe.process_gpu_from_film_log_tile_f32_controlled(
            &lograw, 2, 2, tile, &backend, None, || true).unwrap();
        let actual = pipe.process_gpu_from_film_log_tile_f32_capture_controlled(
            &lograw, 2, 2, tile, &backend, None, &mut capture, || true).unwrap();
        assert_eq!(expected.data, actual.data);
        assert!(capture.result.is_none()); // unsupported tap must not fail output
        for cancelled in [false, true] {
            capture.result = Some(vec![(7, [99.; 3])]);
            let input: &[f32] = if cancelled { &lograw } else { &lograw[..3] };
            assert!(pipe.process_gpu_from_film_log_tile_f32_capture_controlled(
                input, 2, 2, tile, &backend, None, &mut capture, || !cancelled).is_none());
            assert!(capture.result.is_none());
        }
    }
    #[test]
    fn grain_seed_respects_user_value_and_preview_export_layout() {
        let mut pipe=pipe();let backend=Probe::default();let image=ImageBuf::from_data(2,2,vec![from_f64(0.25);12]);
        pipe.params.film_render.grain.seed=42;
        assert!(pipe.process_resident_borrowed(&image,&backend).is_some());
        pipe.params.film_render.grain.seed=43;
        assert!(pipe.process_resident_borrowed(&image,&backend).is_some());
        pipe.params.film_render.grain.coordinate_origin=Some([0,0]);
        assert!(pipe.process_resident_borrowed(&image,&backend).is_some());
        pipe.params.film_render.grain.active=false;
        assert!(pipe.process_resident_borrowed(&image,&backend).is_some());
        let calls=backend.calls.lock().unwrap();
        assert_ne!(calls[0].0,calls[1].0);assert_ne!(calls[1].0,calls[2].0);assert_eq!(calls[3].0,None);
    }
    #[test]
    fn controlled_prepass_aborts_before_reads_or_after_failed_read() {
        let mut pipe=pipe();pipe.params.film_render.halation.boost_ev=1.;let backend=Probe::default();
        assert!(pipe.diffusion_scattered_fields_gpu_controlled(3,5,2,|_,_|panic!("read after cancellation"),||false,&backend).is_none());
        assert!(pipe.diffusion_scattered_fields_gpu_controlled(3,5,2,|_,_|None,||true,&backend).is_none());
        assert!(backend.calls.lock().unwrap().is_empty());
    }
    #[test]
    fn film_log_range_validation_and_split_halos_preserve_contracts() {
        let mut pipe=pipe();let backend=Probe::default();
        let tile=TileContext{full_width:7,full_height:9,x:2,y:3};
        let input=[-0.3f32;18];
        pipe.params.camera.diffusion_filter.active=true;
        // RGB prefix must never silently drop an active global camera effect.
        assert!(pipe.tap_gpu_film_log_tile_f32_controlled(&input,3,2,tile,&backend,None,||true).is_none());
        // That effect has already been baked into signed lograw for the suffix.
        assert!(pipe.process_gpu_from_film_log_tile_f32_controlled(&input,3,2,tile,&backend,None,||true).is_some());
        pipe.params.enlarger.diffusion_filter.active=true;
        assert!(pipe.process_gpu_from_film_log_tile_f32_controlled(&input,3,2,tile,&backend,None,||true).is_none());
        pipe.params.enlarger.diffusion_filter.active=false;
        assert!(pipe.process_gpu_from_film_log_tile_f32_controlled(&[f32::NAN;18],3,2,tile,&backend,None,||true).is_none());
        assert!(pipe.process_gpu_from_film_log_tile_f32_controlled(&input,3,2,TileContext{x:6,..tile},&backend,None,||true).is_none());
        for pitch in [4.2724609375, 5.0, 50.0] {
            let (prefix,suffix)=pipe.gpu_split_halo_pixels(pitch);
            assert_eq!(prefix+suffix-2,pipe.banded_halo_pixels(pitch));
            assert!(prefix>=2&&suffix>=2);
        }
    }

    #[test]
    fn retained_field_reservation_counts_capacity_both_fields_and_gpu_crops() {
        assert_eq!(DiffusionScatteredFields::field_working_bytes(u64::MAX, 0), None);
        assert_eq!(DiffusionScatteredFields::field_working_bytes(0, u64::MAX), None);
        assert_eq!(DiffusionScatteredFields::field_working_bytes(u64::MAX / 4, 1), None);
        let metadata = std::mem::size_of::<DiffusionScatteredFields>() as u64;
        assert_eq!(DiffusionScatteredFields::default().retained_working_bytes(), Some(metadata));
        let make = |capacity, len| {
            let mut field = Vec::with_capacity(capacity);
            field.resize(len, 0.0);
            DiffusionScattered { field, small_w: 3, small_h: 2, d: 4,
                crop: None, p_s: 0.5 }
        };
        let camera = make(64, 18);
        let enlarger = make(128, 18);
        let expected = metadata + ((camera.field.capacity() + camera.field.len() * 2
            + enlarger.field.capacity() + enlarger.field.len() * 2) * 4) as u64;
        let mut fields = DiffusionScatteredFields {
            camera: Some(camera), enlarger: Some(enlarger), highlight_max_raw: Some(1.0) };
        assert_eq!(fields.retained_working_bytes(), Some(expected));
        // Reserved capacity stays live even after the populated data shrinks.
        fields.camera.as_mut().unwrap().field.truncate(3);
        assert_eq!(fields.retained_working_bytes(), Some(expected - 15 * 8));
    }

    #[test]
    fn rectangular_entry_rejects_invalid_geometry_and_cancels_before_submit() {
        let pipe=pipe();let backend=Probe::default();
        let tile=TileContext { full_width:7,full_height:9,x:2,y:3 };
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.5;18],3,2,tile,&backend,None,||false).is_none());
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.5;18],3,2,TileContext{x:6,..tile},&backend,None,||true).is_none());
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[f32::NAN;18],3,2,tile,&backend,None,||true).is_none());
        assert!(backend.calls.lock().unwrap().is_empty());
        let out=pipe.process_gpu_resident_tile_f32_controlled(&[0.5;18],3,2,tile,&backend,None,||true).unwrap();
        assert_eq!((out.width,out.height,out.data.len()),(3,2,18));
    }
    #[test]
    fn cinestill_negative_boost_and_invalid_band_max_decline_before_submit() {
        let mut pipe=pipe();pipe.params.film_render.halation.back_reflection=true;
        pipe.params.film_render.halation.boost_ev=-1.;
        let backend=Probe::default();let image=ImageBuf::from_data(2,2,vec![from_f64(0.2);12]);
        assert!(pipe.tap_gpu_film_log_preview_controlled(&image,&backend,||true).is_none());
        assert!(backend.calls.lock().unwrap().is_empty());
        pipe.params.film_render.halation.boost_ev=0.;
        for maximum in [f32::NAN,f32::INFINITY,-1.] {
            let fields=DiffusionScatteredFields{highlight_max_raw:Some(maximum),..Default::default()};
            assert!(pipe.process_gpu_resident_band_f32(&[0.2;12],2,2,4,0,&backend,Some(&fields)).is_none());
        }
        assert!(backend.calls.lock().unwrap().is_empty());
        pipe.params.film_render.halation.back_reflection=false;
        pipe.params.film_render.halation.boost_ev=-1.;
        assert!(pipe.tap_gpu_film_log_preview_controlled(&image,&backend,||true).is_some(),"legacy behavior changed");
    }

    #[test]
    fn cinestill_halo_preserves_support_beyond_legacy_radius_cap() {
        let mut pipe=pipe();
        pipe.params.film_render.halation.scatter_amount=0.;
        pipe.params.film_render.halation.back_reflection=true;
        assert!(pipe.gpu_halation_working_bytes(512,256).unwrap()>0);
        assert_eq!(spektrafilm_gpu::back_reflection_working_bytes(120.,1.,1.,0.),None);
        assert_eq!(spektrafilm_gpu::back_reflection_working_bytes(120.,1.,f64::INFINITY,5.),None);
        let radius=pipe.gpu_split_halo_pixels(5.).0;
        let full=(3.0_f32 * (9.448921_f64 * 120. / 5.) as f32).ceil() as u32;
        assert!(full>256);
        assert!(radius>=full,"physical tail was truncated: {radius} < {full}");
        pipe.params.film_render.halation.back_reflection=false;
        assert!(pipe.gpu_split_halo_pixels(5.).0<radius);
        for (sigma,radius) in [(0.,0), (0.1,0), (0.49,1), (0.5,2), (1.12,3), (2.9,9), (3.,9), (3.1,10)] {
            assert_eq!(spektrafilm_gpu::back_reflection_fir_radius(sigma),Some(radius));
        }
        assert_eq!(spektrafilm_gpu::back_reflection_fir_radius(f32::INFINITY),None);
        assert_eq!(spektrafilm_gpu::back_reflection_fir_radius(f32::NAN),None);
        assert_eq!(spektrafilm_gpu::back_reflection_fir_radius(f32::MAX),None);
    }

    #[test]
    fn cinestill_headroom_requires_and_reuses_full_frame_maximum() {
        let mut pipe=pipe();
        pipe.params.film_render.halation.back_reflection=true;
        pipe.params.film_render.halation.boost_ev=0.;
        let backend=Probe::default();
        let tile=TileContext { full_width:7,full_height:9,x:0,y:0 };
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.5;18],3,2,tile,&backend,None,||true).is_none());
        assert!(backend.calls.lock().unwrap().is_empty());
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(7,9,3,4,
            |x,y,w,h|Some(vec![(x+w+y+h) as f32;(w*h*3)as usize]),||true,&backend).unwrap();
        assert_eq!(fields.highlight_max_raw,Some(16.));
        assert!(pipe.process_gpu_resident_tile_f32_controlled(&[0.5;18],3,2,tile,&backend,Some(&fields),||true).is_some());
        assert_eq!(backend.calls.lock().unwrap().last().unwrap().1,Some(16.));
        // A FilmLog suffix already contains the headroom and must not require
        // or apply it a second time.
        assert!(pipe.process_gpu_from_film_log_tile_f32_controlled(&[0.5;18],3,2,tile,&backend,None,||true).is_some());
        assert_eq!(backend.calls.lock().unwrap().last().unwrap().1,None);
    }

    #[test]
    fn rectangular_prepass_maximum_covers_short_right_and_bottom_cores() {
        let mut pipe=pipe();pipe.params.film_render.halation.boost_ev=1.;let backend=Probe::default();
        let mut pixels=0;
        let fields=pipe.diffusion_scattered_fields_gpu_tiled_controlled(7,9,3,4,|x,y,w,h|{
            pixels+=w*h;
            Some(vec![(x+w+y+h) as f32;(w*h*3)as usize])
        },||true,&backend).unwrap();
        assert_eq!(pixels,63);
        assert_eq!(fields.highlight_max_raw,Some(16.));
        assert!(pipe.diffusion_scattered_fields_gpu_tiled_controlled(7,9,3,4,|_,_,_,_|panic!("read after cancellation"),||false,&backend).is_none());
    }

    #[test]
    fn highlight_max_is_whole_image_and_reused_for_every_band() {
        let mut pipe=pipe();pipe.params.film_render.halation.boost_ev=1.;let backend=Probe::default();
        let fields=pipe.diffusion_scattered_fields_gpu_controlled(3,5,2,|y,rows|Some(vec![(y+rows)as f32;rows as usize*9]),||true,&backend).unwrap();
        assert_eq!(fields.highlight_max_raw,Some(5.));
        assert!(pipe.process_gpu_resident_band_f32(&[0.5;18],3,2,5,0,&backend,Some(&fields)).is_some());
        assert!(pipe.process_gpu_resident_band_f32(&[0.5;18],3,2,5,6,&backend,Some(&fields)).is_some());
        let calls=backend.calls.lock().unwrap();assert_eq!(calls[calls.len()-1].1,Some(5.));assert_eq!(calls[calls.len()-2].1,Some(5.));
    }
}

#[cfg(test)]
#[path = "prepared_spectral_tests.rs"]
mod prepared_spectral_tests;

#[cfg(test)]
#[path = "preview_film_log_contract_tests.rs"]
mod preview_film_log_contract_tests;

#[cfg(all(test, feature = "wgpu-backend"))]
#[path = "cinestill_gpu_tests.rs"]
mod cinestill_gpu_tests;
