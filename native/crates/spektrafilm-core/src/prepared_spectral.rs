//! Immutable front-spectral reuse plus separately keyed constructor calibration.
//! The cache is owned by one Engine, not global. Its budget charges retained
//! entries AND evicted entries still leased by Pipelines, including Vec capacity.
//! Cold construction scratch and an unadmitted Pipeline remain ordinary caller
//! working memory, not a hidden second cache. No table precision is changed.
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, Weak, atomic::{AtomicBool, Ordering}};
use std::time::SystemTime;
use serde::Serialize;
use spektrafilm_math::spectral::TcLut;
use crate::{params::RuntimeParams, profile::Profile, spectral_service};
use crate::pipeline::{select_illuminant, select_illuminant_f64};

/// Bump whenever the front equations, baked data, precision or key contract change.
const FRONT_ALGORITHM_VERSION: u32 = 1;
const MANIFEST_LIMIT: u64 = 256 * 1024;

#[path = "prepared_calibration.rs"]
mod calibration;
pub(crate) use calibration::{CalibrationKey, PreparedCalibration};

#[derive(Clone, Debug, PartialEq, Eq)]
enum SpectralSource { Hanatos, Arctic, Mallett }
impl SpectralSource {
    fn parse(method: &str) -> Option<Self> {
        match method { "hanatos2025" => Some(Self::Hanatos), "arctic2026alpha02" => Some(Self::Arctic), "mallett2019" => Some(Self::Mallett), _ => None }
    }
    fn relative_path(&self) -> Option<&'static str> {
        match self {
            Self::Hanatos => Some("luts/spectral_upsampling/irradiance_xy_tc.npy"),
            Self::Arctic => Some("luts/spectral_upsampling/arctic2026alpha02/reflectance_xy_tc.npy"),
            Self::Mallett => None,
        }
    }
}

/// Content identity comes from VersionedEngineAssets' SHA-256 manifest, committed
/// last after atomic installation. Length/mtime are additional change detectors,
/// NOT a content hash. The installer owns the immutable active snapshot contract.
/// Unmanifested native callers still work, but do not retain file-backed fronts.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SpectralAssetRevision {
    root: PathBuf,
    source: SpectralSource,
    installer_version: String,
    sha256: String,
    length: u64,
    modified: Option<SystemTime>,
}
impl SpectralAssetRevision {
    pub fn read(data_dir: &Path, method: &str) -> Option<Self> {
        let source = SpectralSource::parse(method)?;
        let root = data_dir.canonicalize().ok()?;
        let Some(relative) = source.relative_path() else {
            return Some(Self { root, source, installer_version: String::new(), sha256: String::new(), length: 0, modified: None });
        };
        // Cap the read itself, not just metadata: a concurrent replacement cannot
        // turn this tiny identity read into unbounded I/O/allocation.
        use std::io::Read;
        let mut manifest = String::new();
        std::fs::File::open(root.join(".prepared-v2")).ok()?.take(MANIFEST_LIMIT + 1).read_to_string(&mut manifest).ok()?;
        if manifest.len() as u64 > MANIFEST_LIMIT { return None; }
        let mut lines = manifest.lines();
        let installer_version = lines.next()?.to_owned();
        if !installer_version.starts_with("assets-v2:") { return None; }
        let mut matches = lines.filter_map(|line| {
            let fields: Vec<_> = line.split('\t').collect();
            (fields.len() == 3 && fields[0] == relative).then_some(fields)
        });
        let row = matches.next()?;
        if matches.next().is_some() { return None; }
        let length: u64 = row[1].parse().ok()?;
        let sha256 = row[2];
        if sha256.len() != 64 || !sha256.bytes().all(|b| b.is_ascii_hexdigit()) { return None; }
        let metadata = std::fs::metadata(root.join(relative)).ok()?;
        if !metadata.is_file() || metadata.len() != length { return None; }
        Some(Self { root, source, installer_version, sha256: sha256.to_owned(), length, modified: Some(metadata.modified().ok()?) })
    }
    pub(crate) fn matches_root(&self, root: &Path) -> bool { root.canonicalize().ok().as_ref() == Some(&self.root) }
    pub(crate) fn still_current(&self, method: &str) -> bool {
        Self::read(&self.root, method).as_ref() == Some(self)
    }
    fn heap_bytes(&self) -> usize {
        self.root.capacity() + self.installer_version.capacity() + self.sha256.capacity()
    }
}

/// Exact effective inputs, not a lossy hash or a full RuntimeParams snapshot.
/// Curves, paper, filters, manual/automatic EV, CAT16, effects and output gamut
/// intentionally are NOT here: the constructor recalibrates/uses them afterward.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SpectralKey {
    version: u32,
    revision: SpectralAssetRevision,
    sensitivity: Vec<[u64; 3]>,
    reference_illuminant: String,
    window: Vec<u64>,
    input_gamut: Option<(String, [u32; 3])>,
}
impl SpectralKey {
    pub(crate) fn new(film: &Profile, params: &RuntimeParams, revision: SpectralAssetRevision) -> Option<Self> {
        let source = SpectralSource::parse(&params.settings.rgb_to_raw_method)?;
        if revision.source != source { return None; }
        let sensitivity: Vec<_> = film.log_sensitivity_f64().iter().map(|row| row.map(|v| {
            let value = 10.0f64.powf(v);
            (if value.is_nan() { 0.0 } else { value }).to_bits()
        })).collect();
        // Malformed/non-finite data follows the cold/error path and never enters
        // retained storage. -infinity log sensitivity is valid zero sensitivity.
        if sensitivity.iter().flatten().any(|&v| !f64::from_bits(v).is_finite()) { return None; }
        let window = if source == SpectralSource::Hanatos && params.settings.apply_hanatos2025_adaptation_window && film.data.hanatos2025_adaptation_window_params.len() >= 4 {
            film.data.hanatos2025_adaptation_window_params.iter().map(|v| v.to_bits()).collect()
        } else { Vec::new() };
        if window.iter().any(|&v| !f64::from_bits(v).is_finite()) { return None; }
        let input_gamut = if source == SpectralSource::Mallett { None } else {
            let gamut = &params.io.input_gamut_compress;
            if gamut.knee.iter().any(|v| !v.is_finite()) { return None; }
            Some((gamut.algorithm.clone(), gamut.knee.map(f32::to_bits)))
        };
        Some(Self { version: FRONT_ALGORITHM_VERSION, revision, sensitivity,
            reference_illuminant: film.info.reference_illuminant.clone(), window, input_gamut })
    }
    pub(crate) fn revision_is_current(&self, method: &str) -> bool { self.revision.still_current(method) }
    fn heap_bytes(&self) -> usize {
        self.revision.heap_bytes() + self.sensitivity.capacity() * std::mem::size_of::<[u64; 3]>() + self.reference_illuminant.capacity()
            + self.window.capacity() * 8 + self.input_gamut.as_ref().map_or(0, |v| v.0.capacity())
    }
}

pub(crate) struct PreparedSpectral {
    pub(crate) tc_lut: Option<TcLut>,
    pub(crate) mallett_core: Option<[[f64; 3]; 3]>,
    pub(crate) front_illuminant: String,
    key: Option<SpectralKey>,
    prewarmed: AtomicBool,
}
impl PreparedSpectral {
    pub(crate) fn omitted_rgb() -> Self {
        Self { tc_lut:None, mallett_core:Some([[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]), front_illuminant:"D65".into(), key:None, prewarmed:AtomicBool::new(false) }
    }
    pub(crate) fn without_lut(front_illuminant: String) -> Self {
        Self { tc_lut: None, mallett_core: None, front_illuminant, key: None, prewarmed: AtomicBool::new(false) }
    }
    pub(crate) fn build(film: &Profile, params: &RuntimeParams, data_dir: &Path, key: Option<SpectralKey>) -> Result<Self, String> {
        // Film sensitivity: Python `sensitivity = np.nan_to_num(10 ** log_sensitivity)` — f64.
        let log_sens = film.log_sensitivity_f64();
        let sensitivity: Vec<[f64; 3]> = log_sens
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

        let ref_illuminant = select_illuminant(&film.info.reference_illuminant);
        let ref_illuminant_f64 = select_illuminant_f64(&film.info.reference_illuminant);
        let mut front_illuminant = film.info.reference_illuminant.clone();

        // RGB → film raw upsampler. Default `hanatos2025` builds the spectral tc
        // LUT; `mallett2019` builds a 3×3 reflectance-basis matrix instead (no
        // LUT). Dispatch mirrors Python `_rgb_to_film_raw`.
        let (tc_lut, mallett_core): (Option<TcLut>, Option<[[f64; 3]; 3]>) =
            match params.settings.rgb_to_raw_method.as_str() {
                "hanatos2025" => {
                    let spectra_lut = spectral_service::load_spectra_lut(data_dir)?;
                    let window_params: Vec<f64> =
                        film.data.hanatos2025_adaptation_window_params.clone();
                    let tc_lut = if params.settings.apply_hanatos2025_adaptation_window
                        && window_params.len() >= 4
                    {
                        spectral_service::compute_tc_lut_with_window(
                            &spectra_lut,
                            &sensitivity,
                            &window_params,
                            ref_illuminant_f64,
                        )
                    } else {
                        spectral_service::compute_tc_lut(&spectra_lut, &sensitivity)
                    };

                    // Bake input gamut compression into the LUT at build time, around
                    // the film reference illuminant (the runtime's achromatic axis).
                    // The per-pixel path then stays compression-agnostic.
                    let input_gamut = crate::input_gamut::InputGamutCompress::build(
                        &params.io.input_gamut_compress.algorithm,
                        params.io.input_gamut_compress.knee,
                    )?;
                    let tc_lut = if input_gamut.is_active() {
                        let (rx, ry) = spektrafilm_math::spectral::illuminant_to_xy(ref_illuminant);
                        input_gamut.remap(&tc_lut, [rx, ry])
                    } else {
                        tc_lut
                    };
                    (Some(tc_lut), None)
                }
                "arctic2026alpha02" => {
                    front_illuminant = "D65".into();
                    let reflectance_lut = spectral_service::load_arctic2026alpha02_lut(data_dir)?;
                    let tc_lut = spectral_service::compute_reflectance_tc_lut(
                        &reflectance_lut,
                        &sensitivity,
                        ref_illuminant_f64,
                    );
                    let input_gamut = crate::input_gamut::InputGamutCompress::build(
                        &params.io.input_gamut_compress.algorithm,
                        params.io.input_gamut_compress.knee,
                    )?;
                    let tc_lut = if input_gamut.is_active() {
                        let (rx, ry) = spektrafilm_math::spectral::illuminant_to_xy(
                            select_illuminant(&front_illuminant),
                        );
                        input_gamut.remap(&tc_lut, [rx, ry])
                    } else {
                        tc_lut
                    };
                    (Some(tc_lut), None)
                }
                "mallett2019" => (
                    None,
                    Some(crate::mallett::compute_core_matrix(
                        &sensitivity,
                        ref_illuminant_f64,
                    )),
                ),
                other => {
                    return Err(format!("unsupported rgb_to_raw_method: {other:?}"));
                }
            };

        Ok(Self { tc_lut, mallett_core, front_illuminant, key, prewarmed: AtomicBool::new(false) })
    }
    fn build_prewarm(film: &Profile, params: &RuntimeParams, data_dir: &Path, key: Option<SpectralKey>, checkpoint: &mut dyn FnMut() -> bool) -> Result<Self, String> {
        // Film sensitivity: Python `sensitivity = np.nan_to_num(10 ** log_sensitivity)` — f64.
        let log_sens = film.log_sensitivity_f64();
        let sensitivity: Vec<[f64; 3]> = log_sens
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

        let ref_illuminant = select_illuminant(&film.info.reference_illuminant);
        let ref_illuminant_f64 = select_illuminant_f64(&film.info.reference_illuminant);
        let mut front_illuminant = film.info.reference_illuminant.clone();

        // RGB → film raw upsampler. Default `hanatos2025` builds the spectral tc
        // LUT; `mallett2019` builds a 3×3 reflectance-basis matrix instead (no
        // LUT). Dispatch mirrors Python `_rgb_to_film_raw`.
        let (tc_lut, mallett_core): (Option<TcLut>, Option<[[f64; 3]; 3]>) =
            match params.settings.rgb_to_raw_method.as_str() {
                "hanatos2025" => {
                    let spectra_lut = load_bounded_front(data_dir, "luts/spectral_upsampling/irradiance_xy_tc.npy", checkpoint)?;
                    let window_params: Vec<f64> =
                        film.data.hanatos2025_adaptation_window_params.clone();
                    let tc_lut = if params.settings.apply_hanatos2025_adaptation_window
                        && window_params.len() >= 4
                    {
                        spectral_service::compute_tc_lut_with_window_controlled(
                            &spectra_lut,
                            &sensitivity,
                            &window_params,
                            ref_illuminant_f64, checkpoint,
                        )?
                    } else {
                        spectral_service::compute_tc_lut_controlled(&spectra_lut, &sensitivity, checkpoint)?
                    };

                    // Bake input gamut compression into the LUT at build time, around
                    // the film reference illuminant (the runtime's achromatic axis).
                    // The per-pixel path then stays compression-agnostic.
                    let input_gamut = crate::input_gamut::InputGamutCompress::build(
                        &params.io.input_gamut_compress.algorithm,
                        params.io.input_gamut_compress.knee,
                    )?;
                    let tc_lut = if input_gamut.is_active() {
                        let (rx, ry) = spektrafilm_math::spectral::illuminant_to_xy(ref_illuminant);
                        input_gamut.remap_controlled(&tc_lut, [rx, ry], checkpoint)?
                    } else {
                        tc_lut
                    };
                    (Some(tc_lut), None)
                }
                "arctic2026alpha02" => {
                    front_illuminant = "D65".into();
                    let reflectance_lut = load_bounded_front(data_dir, "luts/spectral_upsampling/arctic2026alpha02/reflectance_xy_tc.npy", checkpoint)?;
                    let tc_lut = spectral_service::compute_reflectance_tc_lut_controlled(
                        &reflectance_lut,
                        &sensitivity,
                        ref_illuminant_f64, checkpoint,
                    )?;
                    let input_gamut = crate::input_gamut::InputGamutCompress::build(
                        &params.io.input_gamut_compress.algorithm,
                        params.io.input_gamut_compress.knee,
                    )?;
                    let tc_lut = if input_gamut.is_active() {
                        let (rx, ry) = spektrafilm_math::spectral::illuminant_to_xy(
                            select_illuminant(&front_illuminant),
                        );
                        input_gamut.remap_controlled(&tc_lut, [rx, ry], checkpoint)?
                    } else {
                        tc_lut
                    };
                    (Some(tc_lut), None)
                }
                "mallett2019" => (
                    None,
                    Some(crate::mallett::compute_core_matrix(
                        &sensitivity,
                        ref_illuminant_f64,
                    )),
                ),
                other => {
                    return Err(format!("unsupported rgb_to_raw_method: {other:?}"));
                }
            };

        Ok(Self { tc_lut, mallett_core, front_illuminant, key, prewarmed: AtomicBool::new(false) })
    }
    fn finite(&self) -> bool {
        self.tc_lut.as_ref().is_none_or(|lut| lut.data.iter().all(|v| v.is_finite()))
            && self.mallett_core.as_ref().is_none_or(|matrix| matrix.iter().flatten().all(|v| v.is_finite()))
    }
    fn storage_bytes(&self) -> usize {
        std::mem::size_of::<Self>() + 2 * std::mem::size_of::<usize>() + self.front_illuminant.capacity()
            + self.key.as_ref().map_or(0, SpectralKey::heap_bytes)
            + self.tc_lut.as_ref().map_or(0, |lut| lut.data.capacity() * std::mem::size_of::<f64>())
    }
}

/// Prewarm only accepts bounded table geometry. Cold rendering is unrestricted.
/// 192²×81 gives at most 23 MiB raw f64, 12 MiB converted f32 and 23 MiB
/// window scratch (raw has dropped before window scratch starts). Together with
/// two TC tables, profiles, stack and manifest this fits the 64 MiB active estimate.
fn load_bounded_front(data_dir: &Path, relative: &str, checkpoint: &mut dyn FnMut() -> bool) -> Result<spectral_service::SpectraLut, String> {
    let file = std::fs::File::open(data_dir.join(relative)).map_err(|e| e.to_string())?;
    if file.metadata().map_err(|e| e.to_string())?.len() > 24 * 1024 * 1024 { return Err("Prewarm table exceeds bound".into()); }
    let (shape, data) = spektrafilm_math::npy::load_npy_f32_bounded_controlled(std::io::BufReader::new(file), 192 * 192 * 81, checkpoint).map_err(|e| e.to_string())?;
    if shape.len() != 3 || shape[0] != shape[1] || !(2..=192).contains(&shape[0]) || shape[2] != 81 { return Err("Prewarm table geometry exceeds bound".into()); }
    Ok(spectral_service::SpectraLut {size: shape[0], n_wavelengths: 81, data})
}

#[derive(Clone, Copy, Default, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreparedSpectralStats {
    pub available: bool,
    pub epoch: u64,
    pub enabled: bool,
    pub hits: u64,
    pub misses: u64,
    pub publications: u64,
    pub evictions: u64,
    pub rejected_publications: u64,
    pub build_nanoseconds: u64,
    pub calibration_hits: u64,
    pub calibration_misses: u64,
    pub calibration_publications: u64,
    pub calibration_build_nanoseconds: u64,
    pub calibration_rejections: u64,
    pub calibration_retained_bytes: usize,
    pub prewarm_attempts: u64,
    pub prewarm_published: u64,
    pub prewarm_used: u64,
    pub prewarm_wasted: u64,
    pub prewarm_skipped: u64,
    pub prewarm_cancelled: u64,
    pub retained_entries: usize,
    pub retained_bytes: usize,
    pub live_bytes: usize,
    pub live_assets: usize,
    pub max_bytes: usize,
    pub max_entries: usize,
}
struct Lease { value: Weak<PreparedSpectral>, bytes: usize }
struct CacheState {
    enabled: bool,
    epoch: u64,
    calibration: Option<Box<calibration::CalibrationEntry>>,
    entries: Vec<Arc<PreparedSpectral>>,
    leases: Vec<Lease>,
    stats: PreparedSpectralStats,
}
/// Bound both the number of live cached allocations (including retired leases)
/// and their bytes. Retiring never invalidates an active Pipeline's Arc.
pub struct PreparedSpectralCache {
    state: Mutex<CacheState>,
    max_bytes: usize,
    max_entries: usize,
}
impl Default for PreparedSpectralCache {
    fn default() -> Self { Self::new(8 * 1024 * 1024, 4) }
}
impl PreparedSpectralCache {
    pub fn new(max_bytes: usize, max_entries: usize) -> Self {
        // Keep a pathological public caller from requesting an unbounded index.
        let index_entry_bytes = std::mem::size_of::<Arc<PreparedSpectral>>() + std::mem::size_of::<Lease>();
        let max_entries = max_entries.min(64).min(max_bytes / index_entry_bytes);
        Self { state: Mutex::new(CacheState { enabled: true, epoch: 0, calibration: None, entries: Vec::with_capacity(max_entries), leases: Vec::with_capacity(max_entries), stats: PreparedSpectralStats::default() }), max_bytes, max_entries }
    }
    /// Mobile starts disabled until its shared-memory owner admits the passive
    /// budget. This gate is separate from the native cache's own bounded ledger.
    pub fn disabled() -> Self { let cache = Self::default(); cache.set_enabled(false); cache }
    pub fn set_enabled(&self, enabled: bool) {
        if let Ok(mut state) = self.state.lock() {
            if state.enabled != enabled {
                state.epoch = state.epoch.wrapping_add(1);
                state.enabled = enabled;
                if !enabled {
                    Self::waste_unused(&mut state);
                    state.entries = Vec::new(); state.calibration = None; Self::prune(&mut state);
                } else {
                    state.entries.reserve_exact(self.max_entries);
                    let remaining = self.max_entries.saturating_sub(state.leases.len());
                    state.leases.reserve_exact(remaining);
                }
            }
        }
    }
    fn prune(state: &mut CacheState) {
        state.leases.retain(|lease| lease.value.strong_count() != 0);
        if !state.enabled && state.leases.is_empty() { state.leases = Vec::new(); }
    }
    fn overhead(state: &CacheState) -> usize {
        // Inline mutex/counters belong to Engine metadata. Every dynamic cache
        // allocation is charged here and released when disabled and drained.
        state.entries.capacity() * std::mem::size_of::<Arc<PreparedSpectral>>() + state.leases.capacity() * std::mem::size_of::<Lease>()
    }
    fn live_bytes(state: &CacheState) -> usize { Self::overhead(state) + state.leases.iter().map(|lease| lease.bytes).sum::<usize>() + state.calibration.as_ref().map_or(0, |v| v.storage_bytes()) }
    pub(crate) fn lookup(&self, key: &SpectralKey) -> (u64, Option<Arc<PreparedSpectral>>) { self.lookup_inner(key, true) }
    fn lookup_inner(&self, key: &SpectralKey, foreground: bool) -> (u64, Option<Arc<PreparedSpectral>>) {
        let Ok(mut state) = self.state.lock() else { return (u64::MAX, None); };
        Self::prune(&mut state);
        if !state.enabled { return (state.epoch, None); }
        if let Some(index) = state.entries.iter().position(|value| value.key.as_ref() == Some(key)) {
            let value = state.entries.remove(index);
            state.entries.push(value.clone());
            state.stats.hits = state.stats.hits.saturating_add(1);
            if foreground && value.prewarmed.swap(false, Ordering::AcqRel) { state.stats.prewarm_used = state.stats.prewarm_used.saturating_add(1); }
            (state.epoch, Some(value))
        } else {
            state.stats.misses = state.stats.misses.saturating_add(1);
            (state.epoch, None)
        }
    }
    pub(crate) fn publish(&self, epoch: u64, value: &Arc<PreparedSpectral>, build_ns: u64) {
        self.publish_controlled(epoch,value,build_ns,&mut || true);
    }
    // The prewarm checkpoint reads only atomics, never this cache mutex. Check
    // it under the publication lock to close token-update/epoch-update races.
    fn publish_controlled(&self, epoch: u64, value: &Arc<PreparedSpectral>, build_ns: u64, checkpoint: &mut dyn FnMut()->bool) {
        let Ok(mut state) = self.state.lock() else { return; };
        state.stats.build_nanoseconds = state.stats.build_nanoseconds.saturating_add(build_ns);
        if !checkpoint() || !state.enabled || epoch != state.epoch || value.key.is_none() || !value.finite() {
            state.stats.rejected_publications = state.stats.rejected_publications.saturating_add(1); return;
        }
        if state.entries.iter().any(|existing| existing.key == value.key) { return; }
        let bytes = value.storage_bytes();
        Self::prune(&mut state);
        // Prefer the expensive front over this tiny recomputable calibration.
        if Self::live_bytes(&state).saturating_add(bytes) > self.max_bytes { state.calibration = None; }
        // Retired leased values still count, so pressure never makes new cache
        // ownership exceed the budget by pretending an eviction freed them.
        while !state.entries.is_empty() && (state.leases.len() >= self.max_entries || Self::live_bytes(&state).saturating_add(bytes) > self.max_bytes) {
            let retired = state.entries.remove(0);
            if retired.prewarmed.swap(false, Ordering::AcqRel) { state.stats.prewarm_wasted = state.stats.prewarm_wasted.saturating_add(1); }
            drop(retired);
            state.stats.evictions = state.stats.evictions.saturating_add(1);
            Self::prune(&mut state);
        }
        if state.leases.len() >= self.max_entries || Self::live_bytes(&state).saturating_add(bytes) > self.max_bytes {
            state.stats.rejected_publications = state.stats.rejected_publications.saturating_add(1); return;
        }
        state.leases.push(Lease { value: Arc::downgrade(value), bytes });
        state.entries.push(value.clone());
        state.stats.publications = state.stats.publications.saturating_add(1);
        if value.prewarmed.load(Ordering::Acquire) {state.stats.prewarm_published = state.stats.prewarm_published.saturating_add(1);}
    }
    fn waste_unused(state: &mut CacheState) {
        state.stats.prewarm_wasted = state.stats.prewarm_wasted.saturating_add(state.entries.iter().filter(|value| value.prewarmed.swap(false, Ordering::AcqRel)).count() as u64);
    }
    /// Only the source-independent front is built. AE, print/scanner calibration,
    /// output gamut and effects are still recomputed by the normal constructor.
    pub fn prewarm(&self, film: Profile, params: &RuntimeParams, data_dir: &Path, mut checkpoint: impl FnMut() -> bool) -> Result<&'static str, String> {
        if let Ok(mut state) = self.state.lock() { state.stats.prewarm_attempts = state.stats.prewarm_attempts.saturating_add(1); }
        let mut run = || -> Result<&'static str, String> {
            if !checkpoint() { return Err("Prewarm cancelled".into()); }
            if !self.stats().enabled { return Ok("disabled"); }
            let film = crate::profile::resolve_for_render(film, params.film_render.development_time);
            let Some(revision) = SpectralAssetRevision::read(data_dir, &params.settings.rgb_to_raw_method) else { return Ok("unverified_assets"); };
            let Some(key) = SpectralKey::new(&film, params, revision) else { return Ok("invalid_key"); };
            let (epoch, hit) = self.lookup_inner(&key, false);
            if hit.is_some() { return Ok("already_warm"); }
            let started = std::time::Instant::now();
            let value = Arc::new(PreparedSpectral::build_prewarm(&film, params, data_dir, Some(key.clone()), &mut checkpoint)?);
            if !checkpoint() || !key.revision_is_current(&params.settings.rgb_to_raw_method) { return Err("Prewarm cancelled or assets changed".into()); }
            value.prewarmed.store(true, Ordering::Release);
            self.publish_controlled(epoch, &value, started.elapsed().as_nanos().min(u64::MAX as u128) as u64, &mut checkpoint);
            let state = self.state.lock().map_err(|_| "Prewarm ledger unavailable")?;
            if state.entries.iter().any(|entry| Arc::ptr_eq(entry, &value)) {
                Ok("published")
            } else if !checkpoint() {Err("Prewarm cancelled".into())} else { Ok("not_admitted") }
        };
        let result = run();
        let cancelled = result.is_err() && !checkpoint();
        if let Ok(mut state) = self.state.lock() {
            match &result { Ok("published") => {}, Err(_) if cancelled => state.stats.prewarm_cancelled = state.stats.prewarm_cancelled.saturating_add(1), _ => state.stats.prewarm_skipped = state.stats.prewarm_skipped.saturating_add(1) }
        }
        result
    }
    /// Cancel an in-flight builder ticket without discarding valid completed
    /// entries. Slider cancellation must not destroy cross-parameter reuse.
    pub fn cancel_pending(&self) {
        if let Ok(mut state) = self.state.lock() { state.epoch = state.epoch.wrapping_add(1); }
    }
    /// Retire ownership, cancel in-progress publication, keep leases valid. A new
    /// build cannot refill the cache using a ticket obtained before this clear.
    pub fn clear(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.epoch = state.epoch.wrapping_add(1);
            Self::waste_unused(&mut state);
            state.entries.clear();
            state.calibration = None;
            Self::prune(&mut state);
        }
    }
    pub fn stats(&self) -> PreparedSpectralStats {
        let Ok(mut state) = self.state.lock() else { return PreparedSpectralStats { max_bytes: self.max_bytes, max_entries: self.max_entries, ..Default::default() }; };
        Self::prune(&mut state);
        PreparedSpectralStats { calibration_retained_bytes: state.calibration.as_ref().map_or(0, |v| v.storage_bytes()), available: true, epoch: state.epoch, enabled: state.enabled, retained_entries: state.entries.len(), retained_bytes: state.entries.iter().map(|value| value.storage_bytes()).sum(),
            live_bytes: Self::live_bytes(&state), live_assets: state.leases.len(), max_bytes: self.max_bytes, max_entries: self.max_entries, ..state.stats }
    }
}

#[cfg(test)]
mod cache_tests {
    use super::*;
    fn key(id: u64) -> SpectralKey {
        SpectralKey { version: FRONT_ALGORITHM_VERSION, revision: SpectralAssetRevision { root: PathBuf::from("fixture"), source: SpectralSource::Mallett,
            installer_version: String::new(), sha256: String::new(), length: 0, modified: None }, sensitivity: vec![[id; 3]], reference_illuminant: "D55".into(), window: vec![], input_gamut: None }
    }
    fn value(id: u64) -> Arc<PreparedSpectral> {
        let mut data = Vec::with_capacity(127); data.extend([0.1f64; 12]);
        Arc::new(PreparedSpectral { tc_lut: Some(TcLut { size: 2, channels: 3, data }), mallett_core: None, front_illuminant: "D55".into(), key: Some(key(id)), prewarmed: AtomicBool::new(false) })
    }
    #[test]
    fn cold_hit_same_arc_and_capacity_accounted() {
        let cache = PreparedSpectralCache::new(8192, 2);
        let value = value(1); let (epoch, miss) = cache.lookup(&key(1)); assert!(miss.is_none());
        cache.publish(epoch, &value, 7);
        let (_, hit) = cache.lookup(&key(1)); assert!(Arc::ptr_eq(&value, &hit.unwrap()));
        let stats = cache.stats(); assert_eq!((stats.hits, stats.misses, stats.publications), (1, 1, 1));
        assert_eq!(stats.retained_bytes, value.storage_bytes()); assert!(stats.retained_bytes >= 127 * 8);
        assert!(stats.live_bytes <= stats.max_bytes); assert_eq!(stats.build_nanoseconds, 7);
    }
    #[test]
    fn exact_bits_and_typed_version_invalidate() {
        let cache = PreparedSpectralCache::default(); let value = value(1);
        cache.publish(cache.lookup(&key(1)).0, &value, 0);
        let mut changed = key(1); changed.sensitivity[0][0] += 1;
        assert!(cache.lookup(&changed).1.is_none());
        changed = key(1); changed.version += 1; assert!(cache.lookup(&changed).1.is_none());
        changed = key(1); changed.revision.installer_version = "replacement".into(); assert!(cache.lookup(&changed).1.is_none());
    }
    #[test]
    fn pressure_retirement_keeps_live_lease_charged_and_valid() {
        let cache = PreparedSpectralCache::new(8192, 1); let first = value(1);
        cache.publish(cache.lookup(&key(1)).0, &first, 0);
        let second = value(2); cache.publish(cache.lookup(&key(2)).0, &second, 0);
        assert_eq!(cache.stats().retained_entries, 0); assert_eq!(cache.stats().live_assets, 1);
        assert_eq!(first.tc_lut.as_ref().unwrap().data[0], 0.1); assert_eq!(cache.stats().rejected_publications, 1);
        drop(first); assert_eq!(cache.stats().live_assets, 0);
        cache.publish(cache.lookup(&key(2)).0, &second, 0); assert_eq!(cache.stats().retained_entries, 1);
        assert!(cache.stats().live_bytes <= cache.stats().max_bytes);
    }
    #[test]
    fn clear_retires_only_and_rejects_late_publication() {
        let cache = PreparedSpectralCache::default(); let first = value(1);
        let epoch = cache.lookup(&key(1)).0; cache.publish(epoch, &first, 0);
        cache.clear(); assert_eq!(cache.stats().retained_entries, 0); assert_eq!(cache.stats().live_assets, 1);
        cache.publish(epoch, &value(2), 0); assert_eq!(cache.stats().retained_entries, 0);
        assert_eq!(first.tc_lut.as_ref().unwrap().data.len(), 12);
        drop(first); assert_eq!(cache.stats().live_assets, 0);
    }
    #[test]
    fn cancel_pending_preserves_completed_entries_but_blocks_builder() {
        let cache = PreparedSpectralCache::default(); let first = value(1);
        let epoch = cache.lookup(&key(1)).0; cache.publish(epoch, &first, 0);
        cache.cancel_pending(); cache.publish(epoch, &value(2), 0);
        assert!(cache.lookup(&key(1)).1.is_some()); assert!(cache.lookup(&key(2)).1.is_none());
    }
    #[test]
    fn byte_bound_rejects_oversized_and_nonfinite_values() {
        let small = PreparedSpectralCache::new(1, 2); small.publish(small.lookup(&key(1)).0, &value(1), 0);
        assert_eq!(small.stats().retained_entries, 0);
        let cache = PreparedSpectralCache::default(); let mut invalid = value(2);
        Arc::get_mut(&mut invalid).unwrap().tc_lut.as_mut().unwrap().data[0] = f64::NAN;
        cache.publish(cache.lookup(&key(2)).0, &invalid, 0); assert_eq!(cache.stats().retained_entries, 0);
    }
    #[test]
    fn least_recently_used_eviction_and_a_b_a_hit() {
        let cache = PreparedSpectralCache::new(8192, 2);
        for id in [1, 2] { cache.publish(cache.lookup(&key(id)).0, &value(id), 0); }
        assert!(cache.lookup(&key(1)).1.is_some());
        cache.publish(cache.lookup(&key(3)).0, &value(3), 0);
        assert!(cache.lookup(&key(1)).1.is_some()); assert!(cache.lookup(&key(2)).1.is_none());
    }
    #[test]
    fn poisoned_ledger_never_reports_a_valid_retirement_snapshot() {
        let cache = Arc::new(PreparedSpectralCache::default()); let worker_cache = cache.clone();
        let _ = std::thread::spawn(move || { let _guard = worker_cache.state.lock().unwrap(); panic!("poison fixture"); }).join();
        assert!(!cache.stats().available);
    }

}
