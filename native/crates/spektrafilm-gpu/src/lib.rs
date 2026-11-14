/// Explicit algorithm identity for diagnostics/checkpoints; not CPU-bit-exact.
pub const RESIDENT_APPROXIMATION_VERSION: &str = "resident-f32-psf6-cap64-fir256-grain-rms-v1";

pub mod cpu_backend;
#[cfg(feature = "resource-audit")]
pub mod resource_audit;
#[cfg(feature = "cuda-backend")]
pub mod cuda_backend;
#[cfg(feature = "wgpu-backend")]
pub mod wgpu_backend;

use spektrafilm_math::image::ImageBuf;

/// Maximum four stratified samples for a 384 x 384 negative thumbnail.
/// Caller admission still owns all sample/gather memory; device binding and
/// dispatch limits are checked independently before optional capture starts.
pub const MAX_FILM_DENSITY_SAMPLES: usize = 384 * 384 * 4;

/// A caller-owned thumbnail sample ID and coordinates local to the input tile.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FilmDensitySample { pub index: u32, pub x: u32, pub y: u32 }

/// Optional, best-effort DIR-corrected normalized density BEFORE grain.
/// Each call clears `result`. None means unsupported, invalid, canceled, or
/// rejected by device limits. Valid captures share the required output map;
/// there is no separate callback, wait, or map-lag failure.
#[derive(Default, Debug)]
pub struct FilmDensityCapture {
    pub samples: Vec<FilmDensitySample>,
    pub result: Option<Vec<(u32, [f32; 3])>>,
}

/// Additional explicit GPU buffers; caller must reserve this within its
/// existing tile budget without changing tile geometry. Does not include
/// driver allocations, pipeline objects, or CPU sample/result vectors.
pub fn film_density_capture_gpu_bytes(count: usize) -> Option<u64> {
    if count == 0 || count > MAX_FILM_DENSITY_SAMPLES { return None; }
    (count as u64).checked_mul(28)?.checked_add(16)
}

impl FilmDensityCapture {
    pub fn valid_for(&self, width: u32, height: u32) -> bool {
        film_density_capture_gpu_bytes(self.samples.len()).is_some()
            && width.checked_mul(height).and_then(|n| n.checked_mul(3)).is_some()
            && self.samples.iter().all(|s| s.x < width && s.y < height)
    }
}

/// Compute backend abstraction. Each method corresponds to a GPU-friendly
/// operation in the film simulation pipeline.
///
/// Default implementations fall back to CPU. GPU backends override the
/// spectral methods for massive speedups.
pub trait ComputeBackend: Send + Sync {
    fn colorspace_convert(&self, img: &ImageBuf, matrix: &[[f32; 3]; 3]) -> ImageBuf;
    fn cctf_encode_srgb(&self, img: &ImageBuf) -> ImageBuf;
    fn cctf_decode_srgb(&self, img: &ImageBuf) -> ImageBuf;
    fn gaussian_blur(&self, img: &ImageBuf, sigma: f32) -> ImageBuf;

    /// Blur `img` with each of `sigmas`, returning one image per sigma.
    /// GPU backends fuse the work into a single command buffer (one upload,
    /// one submit, one readback) — much cheaper than N independent
    /// `gaussian_blur` calls when N > 1. Default implementation calls the
    /// per-sigma kernel in a loop.
    fn gaussian_blur_multi(&self, img: &ImageBuf, sigmas: &[f32]) -> Vec<ImageBuf> {
        sigmas.iter().map(|&s| self.gaussian_blur(img, s)).collect()
    }
    fn table_lookup(&self, img: &ImageBuf, table_x: &[f32], table_y: &[[f32; 3]]) -> ImageBuf;
    fn lut3d_interp(&self, img: &ImageBuf, lut: &Lut3D) -> ImageBuf;

    /// Spectral scanning: density CMY → RGB via spectral integration.
    /// GPU backends override this with a compute shader.
    ///
    /// The `cat` and `xyz_to_rgb` matrices are kept separate so the CPU
    /// path can apply them as two sequential matmuls (matching Python's
    /// `colour.XYZ_to_RGB` step-by-step). Pre-combining loses ~1 ULP per
    /// output channel which compounds to ~5e-6 of drift in the bare
    /// chain. GPU may collapse for performance.
    fn scan_spectral(
        &self,
        density_cmy: &ImageBuf,
        channel_density: &[[f64; 3]],
        base_density: &[f64],
        illuminant: &[f64],
        normalization: f64,
        cat: &[[f64; 3]; 3],
        xyz_to_rgb: &[[f64; 3]; 3],
    ) -> ImageBuf {
        cpu_backend::scan_spectral_cpu(
            density_cmy,
            channel_density,
            base_density,
            illuminant,
            normalization,
            cat,
            xyz_to_rgb,
        )
    }

    /// Spectral printing: film density CMY → print log-exposure via spectral integration.
    /// GPU backends override this with a compute shader.
    fn print_spectral(
        &self,
        density_cmy: &ImageBuf,
        channel_density: &[[f64; 3]],
        base_density: &[f64],
        illuminant: &[f64],
        sensitivity: &[[f64; 3]],
        normalization_factor: f64,
        preflash: [f64; 3],
    ) -> ImageBuf {
        cpu_backend::print_spectral_cpu(
            density_cmy,
            channel_density,
            base_density,
            illuminant,
            sensitivity,
            normalization_factor,
            preflash,
        )
    }

    /// Hanatos2025 RGB → film raw exposure (the per-pixel bicubic LUT lookup).
    ///
    /// CPU implementation does the two-step CAT02 adaptation (RGB → native
    /// XYZ, then CAT02 adapt) per pixel for bit-exact Python parity —
    /// fusing both matrices into a single matmul gives a different ULP than
    /// applying them sequentially. GPU backends override and may collapse
    /// the two into a single matrix per the precision budget for live preview.
    fn hanatos2025_rgb_to_raw(
        &self,
        image: &ImageBuf,
        tc_lut: &spektrafilm_math::spectral::TcLut,
        color_space: &str,
        ref_illuminant: &[f32],
        cat16: bool,
    ) -> ImageBuf {
        spektrafilm_math::spectral::hanatos2025_rgb_to_raw(
            image,
            tc_lut,
            color_space,
            ref_illuminant,
            cat16,
        )
    }

    /// log_raw → density_cmy via per-channel curve interpolation.
    ///
    /// Called twice per render (filming.develop + printing.develop). GPU
    /// backends override with `density_curve_interp.wgsl`; CPU falls back to
    /// the f64 reference (`interpolate_exposure_to_density_f64`).
    fn density_curve_interp(
        &self,
        log_raw: &ImageBuf,
        log_exposure: &[f64],
        density_curves: &[[f64; 3]],
        gamma_factor: f64,
    ) -> ImageBuf {
        // CPU fallback — uses the f64 reference (`fast_interp_image_f64`).
        // Scalar gamma is broadcast to all channels; we just stretch the x-axis once.
        let scaled: Vec<f64> = if (gamma_factor - 1.0).abs() < 1e-12 {
            log_exposure.to_vec()
        } else {
            log_exposure.iter().map(|&v| v / gamma_factor).collect()
        };
        spektrafilm_math::interp::fast_interp_image_f64(log_raw, &scaled, density_curves)
    }

    /// Optional fused fast-path: runs filming + printing + scanning as a single
    /// GPU-resident command buffer (one upload at start, one readback at end).
    /// Returns `None` to fall back to per-stage trait methods. CPU backend
    /// always returns `None`; wgpu implements it.
    fn try_run_film_chain(&self, _params: &FilmChainParams<'_>) -> Option<ImageBuf> {
        None
    }

    /// Start a new controlled chain before any caller checkpoint observes progress.
    fn reset_thread_resident_progress(&self) {}

    /// Cooperative resident entry. A backend may checkpoint between proven
    /// completed batches, always outside its execution mutex.
    fn try_run_film_chain_controlled(&self, params: &FilmChainParams<'_>,
        checkpoint: &mut dyn FnMut() -> bool) -> Option<ImageBuf> {
        if !checkpoint() { return None; }
        let result = self.try_run_film_chain(params);
        if !checkpoint() { return None; }
        result
    }

    /// Optional side-channel; default backends leave capture unavailable.
    /// Preview failure does not change the required rendering result.
    fn try_run_film_chain_capture_controlled(&self, params: &FilmChainParams<'_>,
        capture: &mut FilmDensityCapture, checkpoint: &mut dyn FnMut() -> bool) -> Option<ImageBuf> {
        capture.result = None;
        self.try_run_film_chain_controlled(params, checkpoint)
    }

    /// True when `try_run_film_chain` returns the same post-scan output that
    /// `Pipeline::apply_post_scan` would produce (final clamp and optional
    /// sRGB encoding). Backends default to returning linear RGB and letting
    /// the pipeline apply the final CPU pass.
    fn resident_chain_applies_post_scan(&self) -> bool {
        false
    }

    /// True for f32 GPU backends where effects may trade exactness for
    /// speed (the diffusion filter uses a downsampled sum-of-Gaussians
    /// instead of the exact FFT convolution). CPU keeps the exact path.
    fn is_gpu(&self) -> bool {
        false
    }

    fn name(&self) -> &str;
}

/// Chain input pixels: either the usual f64 working image or, on the export
/// fast path, the f32 upload data read straight from the spool. The f32 form
/// skips materializing an n*24-byte f64 copy for large exports, which roughly
/// halves the CPU-side peak of the GPU full-chain attempt.
#[derive(Clone, Copy)]
pub enum ChainImage<'a> {
    F64(&'a ImageBuf),
    /// Interleaved RGB f32, row-major, width*height*3 elements.
    F32(&'a [f32], u32, u32),
}

impl<'a> ChainImage<'a> {
    pub fn width(&self) -> u32 {
        match self {
            Self::F64(i) => i.width,
            Self::F32(_, w, _) => *w,
        }
    }
    pub fn height(&self) -> u32 {
        match self {
            Self::F64(i) => i.height,
            Self::F32(_, _, h) => *h,
        }
    }
    pub fn pixel_count(&self) -> usize {
        self.width() as usize * self.height() as usize
    }
    /// f32 pixels ready for GPU upload, when the input is already in that
    /// layout (export fast path).
    pub fn f32_data(&self) -> Option<&'a [f32]> {
        match self {
            Self::F32(d, _, _) => Some(d),
            _ => None,
        }
    }
    /// The f64 working image, when the input is in that form.
    pub fn f64_image(&self) -> Option<&'a ImageBuf> {
        match self {
            Self::F64(i) => Some(i),
            _ => None,
        }
    }
}

/// Fixed donor-density noise field for omitted film. The donor is used only
/// to synthesize a grain residual, never as the image's sensitometry or colour.
#[derive(Clone, Copy, Debug)]
pub struct NeutralGrainReference {
    pub density: [f32; 3],
    pub baseline: [f32; 3],
    pub amount: f32,
}
impl NeutralGrainReference {
    pub fn valid(&self) -> bool {
        self.density.iter().all(|v| v.is_finite() && *v >= 0.)
            && self.baseline.iter().all(|v| v.is_finite())
            && self.amount.is_finite() && self.amount > 0. && self.amount <= 2.
    }
    /// Additional GPU storage while the grain stage is live. Admission must
    /// include this 12 B/pixel original-log-RGB copy in addition to grain scratch.
    pub fn workspace_bytes(width: u32, height: u32) -> Option<u64> {
        u64::from(width).checked_mul(u64::from(height))?.checked_mul(12)
    }
}

/// Explicit digital transport when film or paper is omitted. Not a stock profile.
#[derive(Clone, Copy, Debug)]
pub struct NeutralMediaGpuParams {
    pub grain_reference: Option<NeutralGrainReference>,
    pub film_omitted: bool,
    pub paper_omitted: bool,
    /// False for positive-film direct scanning, which keeps spectral scanning.
    pub negative_readout: bool,
    pub paper_gain: [f64; 3],
    pub paper_gray_log: f64,
    pub paper_log_min: f64,
    pub paper_log_max: f64,
    pub negative_min: [f64; 3],
    pub negative_range: [f64; 3],
    pub negative_gamma: [f64; 3],
}
impl NeutralMediaGpuParams {
    pub fn valid(&self) -> bool {
        let gpu_finite=|v:f64|v.is_finite() && (v as f32).is_finite();
        if !self.film_omitted && !self.paper_omitted {return false;}
        if self.grain_reference.is_some_and(|g| !self.film_omitted || !g.valid()) { return false; }
        if self.negative_readout && (self.film_omitted || !self.paper_omitted) {return false;}
        if self.film_omitted && !self.paper_omitted &&
            (!gpu_finite(self.paper_gray_log) || !gpu_finite(self.paper_log_min) || !gpu_finite(self.paper_log_max) || (self.paper_log_min as f32) >= (self.paper_log_max as f32) || self.paper_gain.iter().any(|v|!gpu_finite(*v)||*v<0.)) {return false;}
        if self.negative_readout && (self.negative_min.iter().any(|v|!gpu_finite(*v)) ||
            self.negative_range.iter().chain(self.negative_gamma.iter()).any(|v|!gpu_finite(*v)||(*v as f32)<=0.)) {return false;}
        true
    }
}

/// All inputs to the GPU-resident film chain. Bundled into a struct so the
/// trait method stays object-safe and adding more optional stages (halation,
/// DIR couplers, …) doesn't churn every callsite.
#[derive(Clone)]
pub struct FilmChainParams<'a> {
    pub neutral_media: Option<NeutralMediaGpuParams>,
    pub start_at: ChainStart,
    /// Large export and bounded preview tiles: fence spatial batches, preserving GPU intermediates.
    pub stage_batched: bool,
    pub image: ChainImage<'a>,
    /// RGB → film-raw front pass (the configured upsampler).
    pub front: FrontPass<'a>,
    pub film_log_exposure: &'a [f64],
    pub film_density_curves_normalized: &'a [[f64; 3]],
    pub film_gamma: f64,
    pub film_channel_density: &'a [[f64; 3]],
    pub film_base_density: &'a [f64],
    pub print_illuminant: &'a [f64],
    pub print_sensitivity: &'a [[f64; 3]],
    pub print_normalization_factor: f64,
    pub print_log_exposure: &'a [f64],
    pub print_density_curves: &'a [[f64; 3]],
    pub print_gamma: f64,
    pub print_channel_density: &'a [[f64; 3]],
    pub print_base_density: &'a [f64],
    /// Constant enlarger preflash raw 3-vector, added to the print exposure
    /// before the inner log10 (Python `_compute_raw_preflash`). `[0; 3]` off.
    pub preflash: [f64; 3],
    pub viewing_illuminant: &'a [f64],
    pub scan_normalization: f64,
    pub scan_xyz_to_rgb: &'a [[f64; 3]; 3],
    /// B&W/slide scanner luminance remap `(m, q)` applied per-pixel on the
    /// scan XYZ (`clip(m·Y+q, 0, 1)/(Y+1e-10)`). `None` is identity. The
    /// filming/printing exposure halves of the correction are folded by the
    /// caller into `rgb_to_adapted_xyz` / `print_normalization_factor`.
    pub bw_xyz_remap: Option<(f64, f64)>,
    /// Scan the developed film directly instead of printing onto paper.
    /// When true the print_spectral + print density-curve passes are
    /// skipped and scan_spectral runs on the film density buffer using the
    /// film's `film_channel_density` / `film_base_density` (the caller sets
    /// `viewing_illuminant` / `scan_normalization` / `scan_xyz_to_rgb` from
    /// the film's viewing illuminant). Mirrors the CPU `scan_film` path.
    pub scan_film: bool,
    /// Optional halation pass — when `Some`, inserted on the raw film
    /// exposure buffer between hanatos2025 and log10.
    pub halation: Option<HalationGpuParams>,
    /// Optional DIR (development inhibitor release) couplers pass — when
    /// `Some`, inserted on the film density buffer between filming and
    /// printing. Re-interpolates density curves using `density_curves_0`.
    pub dir_couplers: Option<DirCouplersGpuParams<'a>>,
    /// Optional grain pass — Poisson-binomial particle model on the film
    /// density buffer, after DIR couplers and before print spectral.
    /// Uses normal-approximation sampling on the GPU (matches what the
    /// CPU path does for typical λ > 30 / variance > 9 regimes).
    pub grain: Option<GrainGpuParams>,
    /// Optional viewing glare pass — applied after scan spectral on the
    /// final RGB buffer. Lognormal-distributed per-pixel surface noise +
    /// blur + per-channel illuminant offset.
    pub glare: Option<GlareGpuParams>,
    /// Optional output gamut compression pass — applied after glare on
    /// the final RGB buffer, before unsharp (matching the CPU scanning
    /// order). When set, scan_spectral skips its [0,1] clamp so the
    /// compressor sees the out-of-gamut values it needs.
    pub gamut: Option<GamutGpuParams<'a>>,
    /// Optional unsharp mask pass — applied after glare (last step
    /// before readback). Blur σ in pixels + amount scalar; both come
    /// from `scanner.unsharp_mask`.
    pub unsharp: Option<UnsharpGpuParams>,
    /// Optional camera lens Gaussian blur on the raw film exposure buffer,
    /// after camera diffusion and before halation.
    pub camera_lens_blur_px: Option<f32>,
    /// Optional scanner lens Gaussian blur on the final RGB buffer, after
    /// glare/gamut compression and before unsharp.
    pub scanner_lens_blur_px: Option<f32>,
    /// Optional highlight reconstruction on the raw film exposure buffer,
    /// immediately after the front pass and before optical scatter.
    pub highlight_boost: Option<HighlightBoostGpuParams>,
    /// Optional camera lens diffusion filter — applied on the raw film
    /// exposure buffer right after hanatos2025, before halation (matching
    /// the CPU filming order). A downsampled sum-of-Gaussians; see
    /// `DiffusionGpuPlan`. Used by the whole-frame resident chain; the
    /// banded chain sets `diffusion_scattered` instead.
    pub diffusion: Option<DiffusionGpuPlan>,
    /// Optional enlarger diffusion filter. Applied in the print stage on
    /// linear print raw between print_spectral and print density curves.
    /// Used by the whole-frame resident chain; the banded chain sets
    /// `enlarger_scattered` instead.
    pub enlarger_diffusion: Option<DiffusionGpuPlan>,
    /// Banded-chain camera diffusion: precomputed whole-frame scattered
    /// field, mixed in at the same point as `diffusion`. Mutually exclusive
    /// with `diffusion` (the scattered form takes precedence when both are
    /// set, and the full plan is ignored).
    pub diffusion_scattered: Option<DiffusionScatteredGpu<'a>>,
    /// Banded-chain enlarger diffusion: precomputed whole-frame scattered
    /// field, mixed in at the same point as `enlarger_diffusion`.
    pub enlarger_scattered: Option<DiffusionScatteredGpu<'a>>,
    /// Where the chain stops. Everything except `Full` exists for the banded
    /// diffusion pre-pass; the normal render paths always pass `Full`.
    pub stop_after: ChainStop,
    /// The print_exposure × B&W printing correction scalar. Used by CUDA
    /// when `enlarger_diffusion` is active so it can preserve the CPU
    /// print-stage ordering around preflash.
    pub print_exposure_scale: f64,
    /// Whether the final output should be sRGB-encoded after clamping. Mirrors
    /// `RuntimeParams::io.output_cctf_encoding`.
    pub output_cctf_encoding: bool,
    /// Export-only: skip the GPU gamut-compression pass AND the final
    /// clamp+sRGB encode, returning unclamped linear RGB so the caller can run
    /// the exact f64 CPU gamut compressor + post-scan. The scan-spectral pass
    /// still skips its [0,1] clamp so out-of-gamut values survive to the CPU.
    /// Preview paths leave this `false` (gamut runs on the GPU in f32).
    pub defer_gamut_to_cpu: bool,
}

/// RGB → film-raw front pass of the GPU-resident chain. Both variants are
/// homogeneous in the input RGB, so the caller folds the exposure scale
/// (auto-exposure × EV compensation × B&W filming correction) into the
/// matrix — no separate scale pass.
#[derive(Clone)]
pub enum FrontPass<'a> {
    /// Hanatos2025 spectral upsampling: per-pixel chromaticity LUT lookup.
    Hanatos2025 {
        tc_lut: &'a spektrafilm_math::spectral::TcLut,
        /// Combined input-RGB → CAT-adapted-XYZ matrix (row-major).
        rgb_to_adapted_xyz: [[f64; 3]; 3],
    },
    /// Mallett2019 reflectance-basis upsampling: one rgb → raw 3×3 matmul
    /// (`core · M_cs`, see `spektrafilm-core/src/mallett.rs`).
    Mallett2019 { matrix: [[f64; 3]; 3] },
}

/// Pre-computed plan for the GPU-resident diffusion filter. The per-channel
/// PSF is decomposed into Gaussian components (6 per PSF sub-component) and
/// the whole scattered field is computed on an image downsampled by `d` so
/// the blur σ stay small. `sigmas`/`coeffs` are parallel: component `i`
/// blurs the downsampled image at `sigmas[i]` (working-resolution px) and
/// adds `coeffs[i]` (per-channel) into the accumulator.
#[derive(Debug, Clone)]
pub struct DiffusionGpuPlan {
    pub d: u32,
    pub small_w: u32,
    pub small_h: u32,
    pub p_s: f32,
    pub sigmas: Vec<f32>,
    pub coeffs: Vec<[f32; 3]>,
}

/// Input color domain of the resident chain.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChainStart { Rgb, FilmLog, CorrectedDensity, GrainedDensity, PostHalationRaw }

/// Where the resident film chain should stop, and what it returns. The
/// default is [`ChainStop::Full`]; the other variants exist for the banded
/// diffusion pre-pass, which taps the chain state at a diffusion filter's
/// input point so the (global) scattered field can be computed once for the
/// whole frame instead of per band.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChainStop {
    /// Raw film exposure after camera optics and scatter, before physical PSF.
    ScatteredRaw,
    /// Exact f32 density after DIR, before grain.
    CorrectedDensity,
    /// Exact resident f32 film density after grain, before printing.
    GrainedDensity,
    /// Camera optics completed; returns unclamped f32 film log exposure.
    FilmLog,
    /// Run the whole chain; the readback holds the final RGB.
    Full,
    /// Stop right after the front pass + highlight boost; the readback holds
    /// the raw film exposure — the camera-diffusion input. Later stages
    /// (diffusion, lens blur, halation, …) are neither built nor encoded.
    Front,
    /// Stop after print spectral + log-to-linear; the readback holds the
    /// linear print light — the enlarger-diffusion input. Print density
    /// curves, scan spectral and everything downstream are skipped. Only
    /// meaningful when `scan_film` is false.
    PrintLinear,
}

/// Precomputed whole-frame diffusion scattered field (already downsampled +
/// blurred at 1/d resolution), consumed by the *banded* resident chain: the
/// global part of the diffusion filter is done, so each band only needs the
/// local part — bilinear-upsample its row window from the small field and
/// mix `(1-p_s)·img + p_s·scattered` at the diffusion point. Produced by
/// `Pipeline::diffusion_scattered_fields_gpu`.
#[derive(Debug, Clone, Copy)]
pub struct DiffusionScatteredGpu<'a> {
    pub band_x0: u32,
    /// [x,y,width,height] in global small-pixel coordinates.
    pub crop: [u32; 4],
    /// `small_w*small_h*3` interleaved f32, row-major — the blurred scattered
    /// field of the WHOLE frame at 1/d resolution.
    pub field: &'a [f32],
    pub small_w: u32,
    pub small_h: u32,
    /// The downsample factor the field was computed at.
    pub d: u32,
    /// Scatter mix strength.
    pub p_s: f32,
    /// Global row index of this band's row 0 (halo rows included), so the
    /// upsample window lines up with the whole-frame field.
    pub band_y0: u32,
}

impl DiffusionScatteredGpu<'_> {
    /// Exact conservative bilinear footprint in global small-pixel coordinates.
    /// Uses the same f32 arithmetic as WGSL (including large-coordinate rounding).
    pub fn required_crop(small_w: u32, small_h: u32, d: u32,
        x: u32, y: u32, width: u32, height: u32) -> Option<[u32; 4]> {
        if small_w == 0 || small_h == 0 || d == 0 || width == 0 || height == 0 { return None; }
        let end_x = x.checked_add(width - 1)?;
        let end_y = y.checked_add(height - 1)?;
        let bounds = |start: u32, end: u32, size: u32| {
            let sample = |v: u32| (((v as f32 + 0.5) * (1.0 / d as f32) - 0.5)
                .clamp(0.0, (size - 1) as f32).floor() as u32).min(size - 1);
            let lo = sample(start);
            let hi = sample(end).saturating_add(1).min(size - 1);
            (lo, hi - lo + 1)
        };
        let (cx, cw) = bounds(x, end_x, small_w);
        let (cy, ch) = bounds(y, end_y, small_h);
        Some([cx, cy, cw, ch])
    }

    pub fn valid_for(&self, width: u32, height: u32) -> bool {
        let Some([x, y, w, h]) = Self::required_crop(self.small_w, self.small_h, self.d,
            self.band_x0, self.band_y0, width, height) else { return false; };
        let [cx, cy, cw, ch] = self.crop;
        let Some(ex) = cx.checked_add(cw) else { return false; };
        let Some(ey) = cy.checked_add(ch) else { return false; };
        let Some(len) = (cw as usize).checked_mul(ch as usize).and_then(|n| n.checked_mul(3)) else { return false; };
        cw > 0 && ch > 0 && ex <= self.small_w && ey <= self.small_h
            && cx <= x && cy <= y && ex >= x + w && ey >= y + h
            && self.field.len() == len && self.p_s.is_finite()
            && self.field.iter().all(|v| v.is_finite())
    }
}

#[derive(Debug, Clone, Copy)]
pub struct HighlightBoostGpuParams {
    /// Film-specific shared RGB headroom after the user highlight boost.
    pub headroom_ev: f32,
    pub headroom_from_ev: f32,
    pub headroom_to_ev: f32,
    /// Whole-image front-pass maximum for banded exports, never a band maximum.
    pub max_raw_override: Option<f32>,
    pub boost_ev: f32,
    pub boost_range: f32,
    pub protect_ev: f32,
}

/// DIR couplers parameters for the GPU-resident matmul + diffusion + final
/// density-curve re-interpolation pass. Mirrors the CPU
/// `apply_density_correction`.
#[derive(Debug, Clone, Copy)]
pub struct DirCouplersGpuParams<'a> {
    /// Already-scaled `couplers_matrix * amount`, row-major.
    pub couplers_matrix_scaled: [[f32; 3]; 3],
    /// Per-channel max density from the normalized curves (used only when
    /// `is_positive` is true; ignored otherwise).
    pub density_max: [f32; 3],
    pub is_positive: bool,
    pub diffusion_size_px: f32,
    pub diffusion_tail_px: f32,
    pub diffusion_tail_weight: f32,
    /// "Density curves before DIR" — re-interpolated by the final shader
    /// pass with the corrected log-exposure.
    pub density_curves_0: &'a [[f64; 3]],
    pub log_exposure: &'a [f64],
    pub gamma_factor: f64,
}

/// Exact CPU sublayer grain model data (`apply_grain_layers`). When attached
/// to [`GrainGpuParams::sublayers`], the GPU runs the per-sublayer particle
/// model (curve-interpolated sublayer densities + per-sublayer particle
/// sizes) instead of the simple per-channel model. This is what the CPU
/// export/preview uses by default (`grain.sublayers_active == true`).
///
/// f32 particle sampling is statistically modeled, not NumPy-bit-identical.
/// Dye-cloud spread is approximated by a combined post-blur and active
/// microstructure is retained as a separately blurred multiplicative field.
#[derive(Debug, Clone)]
pub struct GrainSublayersGpu {
    /// `+1.0` for negative film, `-1.0` for positive (reversal) film.
    pub sign: f32,
    /// Number of curve points (`axis.len()/4`, `layers.len()/12`).
    pub n_points: u32,
    /// Per curve point, per channel: `sign * normalized_density_curve[ch]`.
    /// Laid out as `n_points` consecutive `[f32; 4]` (xyz = channel, w = pad).
    pub axis: Vec<f32>,
    /// Per curve point, per sublayer, per channel:
    /// `density_curves_layers[point][sl][ch]`. Laid out as `n_points * 3`
    /// consecutive `[f32; 4]` (`point*3 + sl`, xyz = channel, w = pad).
    pub layers: Vec<f32>,
    /// Per `(channel*3 + sublayer)`: `(dmax, od_particle, particles, dmin)`.
    pub sl_params: [[f32; 4]; 9],
}

/// Grain parameters for the GPU-resident Poisson-binomial particle model.
/// Mirrors `apply_grain_to_density`: per-channel n_particles_per_pixel
/// already divided by `n_sub_layers`, density_max already includes
/// `density_min`, etc. When `sublayers` is `Some`, the exact CPU
/// `apply_grain_layers` sublayer model is used instead and the simple
/// `density_max`/`n_particles_per_pixel`/`n_sub_layers` fields are ignored.
#[derive(Debug, Clone)]
pub struct GrainGpuParams {
    pub density_min: [f32; 3],
    pub density_max: [f32; 3],
    pub n_particles_per_pixel: [f32; 3],
    pub grain_uniformity: [f32; 3],
    pub n_sub_layers: u32,
    pub base_seed: u32,
    pub grain_blur: f32,
    /// Active lognormal microstructure: (standard deviation, blur spread px).
    pub microstructure: Option<[f32; 2]>,
    /// One shared noise field across all channels (B&W single emulsion)
    /// instead of independent per-channel RNG streams.
    pub monochrome: bool,
    /// Global pixel index of the first pixel of the chain input. Banded
    /// export sets this to the band's global start so the per-pixel RNG
    /// seeds match the whole-frame run; 0 for whole-frame dispatches.
    pub pixel_offset: u32,
    /// Whole-frame row stride for coordinate-stable rectangular tiles.
    pub full_width: u32,
    /// Exact CPU sublayer model data; `None` selects the simple model.
    pub sublayers: Option<GrainSublayersGpu>,
}

/// Output gamut compression parameters for the GPU-resident per-pixel pass.
/// CPU equivalent: `OutputGamutCompress::compress`. The `C_max(L, h)` table
/// is baked once on the CPU (bisection against the output cube) and uploaded
/// as a storage buffer; the shader only does the per-pixel forward/inverse
/// perceptual transform + Reinhard knee.
#[derive(Debug, Clone, Copy)]
pub struct GamutGpuParams<'a> {
    /// 0 = aces_rgc, 1 = oklch, 2 = oklrab, 3 = cam16ucs — must match the
    /// mode dispatch in `gamut_compress.wgsl`.
    pub mode: u32,
    /// Reinhard knee on normalized chroma: (threshold, limit, power).
    pub knee: [f32; 3],
    /// One-sided lightness compression (threshold, limit, power); `None` off.
    pub lightness: Option<[f32; 3]>,
    /// Perceptual lightness of the output white (1.0 OkLab, ~100 CAM16-UCS).
    pub l_white: f32,
    /// Lightness-axis bounds of the `C_max` table grid.
    pub l_min: f32,
    pub l_max: f32,
    /// `C_max(L, h)` table, row-major `[n_l][n_h]`. Empty for aces_rgc.
    pub cmax: &'a [f64],
    pub n_l: u32,
    pub n_h: u32,
}

/// Unsharp mask parameters: blur sigma in pixels and amount.
#[derive(Debug, Clone, Copy)]
pub struct UnsharpGpuParams {
    pub sigma_px: f32,
    pub amount: f32,
}

/// Viewing glare parameters for the GPU-resident lognormal + blur + apply
/// pass. CPU equivalent: `compute_random_glare_amount` followed by
/// `add_glare_with_amount`.
#[derive(Debug, Clone, Copy)]
pub struct GlareGpuParams {
    /// LogNormal μ and σ derived on the CPU from `percent` + `roughness`.
    pub mu: f32,
    pub sigma: f32,
    pub blur_px: f32,
    pub base_seed: u32,
    /// `(XYZ→RGB) * illuminant_xyz`, pre-divided by 100. The shader
    /// just multiplies the lognormal scalar by this per-channel offset.
    pub rgb_offset: [f32; 3],
    /// Global pixel index of the first pixel of the chain input. Banded
    /// export sets this to the band's global start so the per-pixel RNG
    /// seeds match the whole-frame run; 0 for whole-frame dispatches.
    pub pixel_offset: u32,
    /// Whole-frame row stride for coordinate-stable rectangular tiles.
    pub full_width: u32,
}

/// Physical PSF support. Small kernels match the CPU round-half-up FIR;
/// wide kernels retain full 3σ support without the historical radius cap.
/// Reserve signed-index headroom for shader x/y + kernel offsets.
pub fn back_reflection_fir_radius(sigma_px: f32) -> Option<u32> {
    let sigma = sigma_px as f64;
    let radius = if sigma_px < 3.0 { (3.0 * sigma + 0.5).floor() } else { (3.0 * sigma).ceil() };
    (sigma_px.is_finite() && sigma_px >= 0.0 && radius < (i32::MAX / 4) as f64)
        .then_some(radius as u32)
}

/// Conservative twelve-kernel storage plus construction/upload scratch.
/// Shared by whole/tiled exports and detail preflight, including disabled-GPU builds.
pub fn back_reflection_working_bytes(base_um: f64, scale: f64, amount: f64, pitch_um: f64) -> Option<u64> {
    if ![base_um,scale,amount,pitch_um].iter().all(|v|v.is_finite())
        || base_um<=0. || scale<0. || amount<0. || pitch_um<=0. { return None; }
    let mut bytes=0u64;
    for component in spektrafilm_math::halation::back_reflection_components(base_um,scale,amount,pitch_um) {
        for sigma in component.sigma_px {
            let radius=back_reflection_fir_radius(sigma as f32)?;
            let kernel=u64::from(radius).checked_mul(2)?.checked_add(1)?;
            bytes=bytes.checked_add(kernel.checked_mul(24)?.checked_add(256)?)?;
        }
    }
    Some(bytes)
}

#[derive(Debug, Clone, Copy)]
pub struct HalationComponentGpu {
    pub weight: [f32; 3],
    pub sigma_px: [f32; 3],
}

/// Halation parameters for the GPU-resident scatter + multi-bounce pass.
/// Historical stocks retain averaged pixel-space widths. Physical back-
/// reflection keeps all four fitted components and their per-channel widths.
#[derive(Debug, Clone, Copy)]
pub struct HalationGpuParams {
    /// Four physical PSF components, with independently calibrated RGB widths.
    pub back_reflection: Option<[HalationComponentGpu; 4]>,
    pub scatter_amount: f32,
    pub scatter_core_px: f32,
    pub scatter_tail_px: f32,
    /// Per-channel tail weight. Python applies these per channel
    /// (Portra 0.78 / 0.65 / 0.67); averaging is a noticeable
    /// chromatic drift.
    pub scatter_tail_weight: [f32; 3],
    pub halation_amount: f32,
    pub halation_strength_avg: f32,
    /// Per-channel `halation_strength[c] * halation_amount`, used by the
    /// final additive pass and optional energy renormalization.
    pub halation_a_tot: [f32; 3],
    pub halation_first_sigma_px: f32,
    pub halation_n_bounces: u32,
    pub halation_bounce_decay: f32,
    pub halation_renormalize: bool,
}

pub struct Lut3D {
    pub size: u32,
    pub data: Vec<f32>,
}

/// Select the best available backend at runtime.
///
/// Honors `SPEKTRAFILM_BACKEND=cpu|cuda|wgpu` when the requested backend is compiled in.
/// Useful for benchmarking and for f64 mode where the GPU shaders truncate to f32.
pub fn select_backend() -> Box<dyn ComputeBackend> {
    let requested = std::env::var("SPEKTRAFILM_BACKEND")
        .ok()
        .map(|v| v.to_ascii_lowercase());

    if requested.as_deref() == Some("cpu") {
        tracing::info!("using CPU backend");
        return Box::new(cpu_backend::CpuBackend);
    }

    #[cfg(feature = "cuda-backend")]
    {
        if requested.as_deref() == Some("cuda") {
            if let Some(cuda) = cuda_backend::CudaBackend::new() {
                tracing::info!("using CUDA GPU backend");
                return Box::new(cuda);
            }
            tracing::warn!("CUDA backend requested but unavailable; falling back");
        }
    }

    #[cfg(not(feature = "cuda-backend"))]
    if requested.as_deref() == Some("cuda") {
        tracing::warn!("CUDA backend requested but spektrafilm-gpu was built without cuda-backend");
    }

    #[cfg(feature = "wgpu-backend")]
    {
        if requested.as_deref().is_none() || requested.as_deref() == Some("wgpu") {
            if let Some(gpu) = wgpu_backend::WgpuBackend::new() {
                tracing::info!("using wgpu GPU backend");
                return Box::new(gpu);
            }
            if requested.as_deref() == Some("wgpu") {
                tracing::warn!("wgpu backend requested but unavailable; falling back");
            }
        }
    }
    tracing::info!("using CPU backend");
    Box::new(cpu_backend::CpuBackend)
}

#[cfg(test)]
mod rectangular_field_tests {
    use super::*;
    #[test]
    fn cropped_field_requires_both_global_bilinear_taps() {
        let crop = DiffusionScatteredGpu::required_crop(8, 9, 4, 9, 13, 7, 5).unwrap();
        assert_eq!(crop, [1, 2, 4, 3]);
        let values = vec![0.25; (crop[2] * crop[3] * 3) as usize];
        let mut view = DiffusionScatteredGpu { field: &values, small_w: 8, small_h: 9,
            d: 4, p_s: 0.5, band_x0: 9, band_y0: 13, crop };
        assert!(view.valid_for(7, 5));
        view.crop[0] += 1;
        assert!(!view.valid_for(7, 5));
        view.crop = crop;
        view.d = 0;
        assert!(!view.valid_for(7, 5));
    }
    #[test]
    fn edge_crop_clamps_at_global_image_not_tile_boundary() {
        assert_eq!(DiffusionScatteredGpu::required_crop(8, 9, 4, 0, 0, 1, 1), Some([0, 0, 2, 2]));
        assert_eq!(DiffusionScatteredGpu::required_crop(8, 9, 4, 31, 35, 1, 1), Some([7, 8, 1, 1]));
        assert!(DiffusionScatteredGpu::required_crop(8, 9, 0, 0, 0, 1, 1).is_none());
        assert!(DiffusionScatteredGpu::required_crop(8, 9, 4, u32::MAX, 0, 2, 1).is_none());
    }
}

#[cfg(test)]
mod density_capture_tests {
    use super::*;
    #[test] fn bounded_capture_contract() {
        assert_eq!(film_density_capture_gpu_bytes(0), None);
        assert_eq!(film_density_capture_gpu_bytes(1), Some(44));
        assert_eq!(film_density_capture_gpu_bytes(MAX_FILM_DENSITY_SAMPLES), Some(16_515_088));
        assert_eq!(film_density_capture_gpu_bytes(MAX_FILM_DENSITY_SAMPLES + 1), None);
        assert_eq!(film_density_capture_gpu_bytes(usize::MAX), None);
        let mut c = FilmDensityCapture { samples: vec![FilmDensitySample { index: 99, x: 2, y: 3 }], result: None };
        assert!(c.valid_for(3, 4));
        assert!(!c.valid_for(2, 4));
        assert!(!c.valid_for(u32::MAX, 2));
        c.samples.clear(); assert!(!c.valid_for(3, 4));
    }
}

#[cfg(test)]
mod neutral_media_contract_tests {
    use super::NeutralMediaGpuParams;
    fn neutral()->NeutralMediaGpuParams {NeutralMediaGpuParams{grain_reference:None,film_omitted:true,paper_omitted:true,negative_readout:false,paper_gain:[1.;3],paper_gray_log:0.,paper_log_min:-4.,paper_log_max:2.,negative_min:[0.;3],negative_range:[1.;3],negative_gamma:[1.;3]}}
    #[test]
    fn donor_reference_is_explicit_finite_and_budgeted() {
        let mut m=neutral();
        m.grain_reference=Some(super::NeutralGrainReference {density:[1.;3],baseline:[1.;3],amount:1.});
        assert!(m.valid());
        assert_eq!(super::NeutralGrainReference::workspace_bytes(2048,1365),Some(2048*1365*12));
        m.grain_reference.as_mut().unwrap().amount=0.;assert!(!m.valid());
        m.grain_reference.as_mut().unwrap().amount=f32::NAN;assert!(!m.valid());
        m.grain_reference.as_mut().unwrap().amount=1.;
        m.grain_reference.as_mut().unwrap().density[0]=f32::INFINITY;assert!(!m.valid());
        m.grain_reference.as_mut().unwrap().density[0]=1.;
        m.film_omitted=false;assert!(!m.valid());
    }
    #[test]
    fn digital_bridge_modes_are_explicit_and_validated() {
        let mut m=neutral();assert!(m.valid());
        m.paper_omitted=false;assert!(m.valid());
        m.negative_readout=true;assert!(!m.valid());
        m.film_omitted=false;m.paper_omitted=true;assert!(m.valid());
        m.negative_range[0]=0.;assert!(!m.valid());
        m.negative_range[0]=1.;m.negative_gamma[0]=f64::NAN;assert!(!m.valid());
        m.negative_gamma[0]=1.;m.negative_readout=false;assert!(m.valid()); // positive spectral scan
        m.paper_omitted=false;assert!(!m.valid()); // ordinary chain must use None
    }
}

#[cfg(feature="wgpu-backend")]
pub mod resident_reuse;
