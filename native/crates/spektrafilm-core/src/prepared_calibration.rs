//! Exact constructor-only print calibration. No image, backend result, scanner
//! state or mutable Pipeline is retained. The value is copied into each fresh
//! Pipeline; retained ownership uses the existing prepared-assets reservation.
use super::*;

const CALIBRATION_VERSION: u64 = 1;
const MAX_KEY_BYTES: usize = 64 * 1024;
const MAX_KEY_WORDS: usize = 4096;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CalibrationKey {
    front: SpectralKey,
    illuminant: String,
    bits: Vec<u64>,
}
impl CalibrationKey {
    pub(crate) fn new(front: &SpectralKey, film: &Profile, print: &Profile,
        params: &RuntimeParams, neutral: [f64; 3]) -> Option<Self> {
        // Check input sizes before copying. These raw resolved arrays are a
        // conservative superset of the getters read by calibration. Lengths
        // distinguish absent/extra channels; no rounded hash or JSON NaN loss.
        let matrices = [&film.data.density_curves, &film.data.channel_density,
            &print.data.log_sensitivity];
        let vectors = [&film.data.log_exposure, &film.data.base_density];
        let mut words = 32usize;
        for matrix in matrices {
            words = words.checked_add(1)?.checked_add(matrix.len())?;
            if words > MAX_KEY_WORDS { return None; }
            for row in matrix { words = words.checked_add(row.len())?; if words > MAX_KEY_WORDS { return None; } }
        }
        for vector in vectors { words = words.checked_add(1)?.checked_add(vector.len())?; }
        if words > MAX_KEY_WORDS || front.heap_bytes().checked_add(params.enlarger.illuminant.len())?
            .checked_add(words.checked_mul(8)?)? > MAX_KEY_BYTES { return None; }
        let e = &params.enlarger;
        let mut bits = Vec::with_capacity(words);
        bits.extend([CALIBRATION_VERSION, u64::from(params.settings.use_cat16),
            params.film_render.density_curve_gamma.to_bits() as u64,
            params.camera.exposure_compensation_ev.to_bits() as u64,
            u64::from(e.normalize_print_exposure), u64::from(e.print_exposure_compensation)]);
        bits.extend(neutral.map(f64::to_bits));
        bits.extend([e.m_filter_shift, e.y_filter_shift, e.preflash_exposure,
            e.preflash_m_filter_shift, e.preflash_y_filter_shift].map(|v| v.to_bits() as u64));
        for matrix in matrices {
            bits.push(matrix.len() as u64);
            for row in matrix { bits.push(row.len() as u64); bits.extend(row.iter().map(|v| v.to_bits())); }
        }
        for vector in vectors { bits.push(vector.len() as u64); bits.extend(vector.iter().map(|v| v.to_bits())); }
        let key = Self { front: front.clone(), illuminant: e.illuminant.clone(), bits };
        (key.heap_bytes() <= MAX_KEY_BYTES).then_some(key)
    }
    fn heap_bytes(&self) -> usize { self.front.heap_bytes() + self.illuminant.capacity() + self.bits.capacity() * 8 }
}

#[derive(Clone)]
pub(crate) struct PreparedCalibration {
    pub(crate) print_illuminant: Vec<f64>,
    pub(crate) print_exposure_factor: f64,
    pub(crate) preflash_raw: [f64; 3],
}
impl PreparedCalibration {
    fn finite(&self) -> bool {
        self.print_illuminant.iter().chain(self.preflash_raw.iter()).all(|v| v.is_finite())
            && self.print_exposure_factor.is_finite()
    }
}
pub(super) struct CalibrationEntry { key: CalibrationKey, value: PreparedCalibration }
impl CalibrationEntry {
    pub(super) fn storage_bytes(&self) -> usize {
        std::mem::size_of::<Self>() + self.key.heap_bytes() + self.value.print_illuminant.capacity() * 8
    }
}
impl PreparedSpectralCache {
    pub(crate) fn lookup_calibration(&self, key: &CalibrationKey) -> (u64, Option<PreparedCalibration>) {
        let Ok(mut state) = self.state.lock() else { return (u64::MAX, None); };
        if !state.enabled { return (state.epoch, None); }
        let value = state.calibration.as_ref().filter(|entry| entry.key == *key).map(|entry| entry.value.clone());
        if value.is_some() { state.stats.calibration_hits = state.stats.calibration_hits.saturating_add(1); }
        else { state.stats.calibration_misses = state.stats.calibration_misses.saturating_add(1); }
        (state.epoch, value)
    }
    pub(crate) fn publish_calibration(&self, epoch: u64, key: CalibrationKey, value: PreparedCalibration, build_ns: u64) {
        let Ok(mut state) = self.state.lock() else { return; };
        state.stats.calibration_build_nanoseconds = state.stats.calibration_build_nanoseconds.saturating_add(build_ns);
        // Exactly the same generation/pressure domain as the front cache.
        if !state.enabled || state.epoch != epoch || !value.finite()
            || value.print_illuminant.len() > 81 || key.heap_bytes() > MAX_KEY_BYTES {
            state.stats.calibration_rejections = state.stats.calibration_rejections.saturating_add(1); return;
        }
        Self::prune(&mut state);
        let entry = CalibrationEntry { key, value };
        let old_bytes = state.calibration.as_ref().map_or(0, |v| v.storage_bytes());
        if Self::live_bytes(&state).saturating_sub(old_bytes).saturating_add(entry.storage_bytes()) > self.max_bytes
            || self.max_entries == 0 {
            state.stats.calibration_rejections = state.stats.calibration_rejections.saturating_add(1); return;
        }
        // No evicted result remains leased: Pipeline owns a separate small copy.
        state.calibration = Some(Box::new(entry));
        state.stats.calibration_publications = state.stats.calibration_publications.saturating_add(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn key(id: u64) -> CalibrationKey {
        CalibrationKey { front: SpectralKey { version: FRONT_ALGORITHM_VERSION,
            revision: SpectralAssetRevision { root: PathBuf::from("fixture"), source: SpectralSource::Mallett,
                installer_version: String::new(), sha256: String::new(), length: 0, modified: None },
            sensitivity: vec![[1; 3]], reference_illuminant: "D55".into(), window: vec![], input_gamut: None },
            illuminant: "TH-KG3".into(), bits: vec![id] }
    }
    fn value() -> PreparedCalibration {
        let mut illuminant = Vec::with_capacity(127); illuminant.extend([1.; 81]);
        PreparedCalibration { print_illuminant: illuminant, print_exposure_factor: 1., preflash_raw: [0.; 3] }
    }
    #[test]
    fn calibration_capacity_and_single_entry_replace_are_bounded() {
        let cache = PreparedSpectralCache::new(8192, 1);
        let epoch = cache.stats().epoch; let prepared = value();
        let bytes = CalibrationEntry { key: key(1), value: prepared.clone() }.storage_bytes();
        cache.publish_calibration(epoch, key(1), prepared, 17);
        assert!(cache.stats().calibration_retained_bytes >= bytes);
        assert!(cache.stats().calibration_retained_bytes >= 127 * 8);
        let first = cache.lookup_calibration(&key(1)).1.unwrap();
        cache.publish_calibration(epoch, key(2), value(), 19);
        assert!(cache.lookup_calibration(&key(1)).1.is_none());
        assert!(cache.lookup_calibration(&key(2)).1.is_some());
        assert_eq!(first.print_illuminant, vec![1.; 81]);
        assert_eq!(cache.stats().calibration_build_nanoseconds, 36);
        assert!(cache.stats().live_bytes <= cache.stats().max_bytes);
        cache.set_enabled(false); assert_eq!(cache.stats().live_bytes, 0);
        assert_eq!(first.print_exposure_factor.to_bits(), 1f64.to_bits());
    }
    #[test]
    fn calibration_tiny_budget_stale_epoch_disabled_and_nonfinite_reject() {
        let tiny = PreparedSpectralCache::new(32, 1);
        tiny.publish_calibration(tiny.stats().epoch, key(1), value(), 0);
        assert_eq!(tiny.stats().calibration_retained_bytes, 0); assert_eq!(tiny.stats().calibration_rejections, 1);
        let cache = PreparedSpectralCache::default(); let epoch = cache.stats().epoch;
        cache.clear(); cache.publish_calibration(epoch, key(1), value(), 0);
        cache.set_enabled(false); cache.publish_calibration(cache.stats().epoch, key(1), value(), 0);
        cache.set_enabled(true); let mut bad = value(); bad.preflash_raw[1] = f64::NAN;
        cache.publish_calibration(cache.stats().epoch, key(1), bad, 0);
        assert_eq!(cache.stats().calibration_rejections, 3); assert_eq!(cache.stats().calibration_retained_bytes, 0);
    }
    #[test]
    fn calibration_front_pressure_drops_calibration_without_hiding_leases() {
        let cache = PreparedSpectralCache::new(8192, 2); let epoch = cache.stats().epoch;
        cache.publish_calibration(epoch, key(1), value(), 0);
        assert!(cache.stats().calibration_retained_bytes > 0);
        let front = Arc::new(PreparedSpectral { tc_lut: Some(TcLut { size: 2, channels: 3, data: vec![1.; 900] }),
            mallett_core: None, front_illuminant: "D55".into(), key: Some(key(1).front), prewarmed: AtomicBool::new(false) });
        cache.publish(epoch, &front, 0);
        assert_eq!(cache.stats().calibration_retained_bytes, 0);
        assert_eq!(cache.stats().live_assets, 1);
        assert!(cache.stats().live_bytes <= cache.stats().max_bytes);
        cache.publish_calibration(epoch, key(2), value(), 0);
        assert_eq!(cache.stats().calibration_retained_bytes, 0);
        cache.clear(); assert_eq!(cache.stats().live_assets, 1);
        drop(front); cache.set_enabled(false); assert_eq!(cache.stats().live_bytes, 0);
    }
    #[test]
    fn calibration_exact_key_keeps_signed_zero_nan_payload_and_lengths() {
        let cache = PreparedSpectralCache::default(); let epoch = cache.stats().epoch;
        for bits in [0f64.to_bits(), (-0f64).to_bits(), 0x7ff8000000000001u64, 0x7ff8000000000002u64] {
            cache.publish_calibration(epoch, key(bits), value(), 0);
            assert!(cache.lookup_calibration(&key(bits)).1.is_some());
            assert!(cache.lookup_calibration(&key(bits ^ 1)).1.is_none());
        }
        let mut changed = key(0x7ff8000000000002); changed.bits.push(0);
        assert!(cache.lookup_calibration(&changed).1.is_none());
    }
}
