//! One owned, bounded source with optional FilmLog and pre-brightness stages. The caller holds the mutex while
//! finishing a hit: retirement therefore cannot release the reservation before
//! its last reader. Cold work publishes only against the captured epoch.
pub const MAX_BYTES: u64 = 64 * 1024 * 1024;
pub const KEY_ALLOWANCE: u64 = 64 * 1024;

pub struct Entry<T> {
    pub key: String,
    pub input: Vec<f32>,
    pub linear_srgb: Vec<f64>,
    pub metadata: T,
    pub film_log: Option<FilmLog>,
    pub corrected_density: Option<FilmLog>,
}
pub struct FilmLog { pub key: String, pub values: Vec<f32> }
impl FilmLog {
    fn bytes(&self) -> u64 { self.values.capacity() as u64 * 4 }
    fn valid(&self, length: usize) -> bool {
        self.values.len() == length && self.values.iter().all(|v| v.is_finite())
            && self.key.capacity() as u64 <= KEY_ALLOWANCE / 4
    }
}
impl<T> Entry<T> {
    pub fn bytes(&self) -> u64 {
        (self.input.capacity() as u64) * 4 + (self.linear_srgb.capacity() as u64) * 8
            + self.film_log.as_ref().map_or(0, FilmLog::bytes)
            + self.corrected_density.as_ref().map_or(0, FilmLog::bytes)
            + KEY_ALLOWANCE // bounded key/metadata/allocator bookkeeping allowance
    }
    fn valid(&self) -> bool {
        self.input.len() == self.linear_srgb.len() && self.input.len() % 3 == 0
            && !self.input.is_empty() && self.input.iter().all(|v| v.is_finite())
            && self.linear_srgb.iter().all(|v| v.is_finite())
            && self.key.capacity() as u64 <= KEY_ALLOWANCE / 2
            && self.film_log.as_ref().is_none_or(|log| log.valid(self.input.len()))
            && self.corrected_density.as_ref().is_none_or(|log| log.valid(self.input.len()))
    }
    fn matches(&self, key: &str, input: &[f32]) -> bool {
        !self.linear_srgb.is_empty() && self.key == key && self.input.len() == input.len()
            && self.input.iter().zip(input).all(|(a,b)| a.to_bits() == b.to_bits())
    }
}
pub struct StageCache<T> {
    epoch: u64,
    budget: u64,
    entry: Option<Entry<T>>,
}
impl<T> Default for StageCache<T> {
    fn default() -> Self { Self { epoch: 0, budget: 0, entry: None } }
}
impl<T> StageCache<T> {
    /// Zero disables and retires; re-enabling starts a fresh ownership epoch.
    pub fn configure(&mut self, budget: u64) {
        self.entry = None;
        self.epoch = self.epoch.checked_add(1).expect("preview cache epoch exhausted");
        self.budget = if budget <= MAX_BYTES { budget } else { 0 };
    }
    pub fn epoch(&self) -> u64 { self.epoch }
    pub fn enabled(&self) -> bool { self.budget != 0 }
    pub fn bytes(&self) -> u64 { self.entry.as_ref().map_or(0, Entry::bytes) }
    pub fn film_log_fits(&self, samples: usize) -> bool {
        (samples as u64).checked_mul(16).and_then(|bytes|bytes.checked_add(KEY_ALLOWANCE))
            .is_some_and(|bytes|self.enabled() && bytes<=self.budget)
    }
    pub fn corrected_density_fits(&self, samples: usize) -> bool {
        (samples as u64).checked_mul(20).and_then(|bytes|bytes.checked_add(KEY_ALLOWANCE))
            .is_some_and(|bytes|self.enabled() && bytes<=self.budget)
    }
    /// Like FilmLog, copy into ACTIVE prepaid storage before releasing the lock.
    pub fn copy_corrected_density(&mut self, key: &str, input: &[f32]) -> Option<FilmLog> {
        let entry = self.entry.as_mut()?;
        let matches = entry.input.len() == input.len()
            && entry.input.iter().zip(input).all(|(a,b)| a.to_bits() == b.to_bits())
            && entry.corrected_density.as_ref().is_some_and(|density| density.key == key);
        if !matches { entry.corrected_density = None; return None; }
        let density = entry.corrected_density.as_ref()?;
        Some(FilmLog { key: density.key.clone(), values: density.values.clone() })
    }
    pub fn find(&mut self, key: &str, input: &[f32]) -> Option<&Entry<T>> {
        // Only one exact source/mapping is retained. A downstream change drops
        // final RGB but may retain scientifically valid FilmLog; upstream changes
        // are tested independently by copy_film_log below.
        if !self.entry.as_ref().is_some_and(|e| e.input.len() == input.len()
            && e.input.iter().zip(input).all(|(a,b)| a.to_bits() == b.to_bits())) {
            self.entry = None;
        }
        let entry = self.entry.as_mut()?;
        if !entry.matches(key, input) {
            entry.key = String::new(); entry.linear_srgb = Vec::new();
            return None;
        }
        Some(entry)
    }
    /// Copy under the passive mutex into the separately prepaid ACTIVE 12 B/px
    /// candidate. No Arc/borrow escapes the owner. GPU work then releases this
    /// mutex, so pressure and cancellation never wait for a full suffix render.
    /// configure(0) can immediately prove that all passive storage is gone.
    pub fn copy_film_log(&mut self, key: &str, input: &[f32]) -> Option<FilmLog> {
        let entry = self.entry.as_mut()?;
        let matches = entry.input.len() == input.len()
            && entry.input.iter().zip(input).all(|(a,b)| a.to_bits() == b.to_bits())
            && entry.film_log.as_ref().is_some_and(|log| log.key == key);
        if !matches { entry.film_log = None; return None; }
        let log = entry.film_log.as_ref()?;
        Some(FilmLog { key: log.key.clone(), values: log.values.clone() })
    }
    pub fn publish(&mut self, epoch: u64, entry: Entry<T>) -> bool {
        if epoch != self.epoch || !self.enabled() || !entry.valid() || entry.bytes() > self.budget { return false; }
        self.entry = Some(entry);
        true
    }
    pub fn discard(&mut self) { self.entry = None; }
    pub fn cancel_pending(&mut self) { self.epoch = self.epoch.checked_add(1).expect("preview cache epoch exhausted"); }
    pub fn invalidate(&mut self) { let budget=self.budget; self.configure(budget); }
}
/// Preserve the original f64 multiply followed by the f32 cast. Do not clamp:
/// upstream scientific gamut/clipping already happened and RGBA8 clamps later.
pub fn finish(linear_srgb: &[f64], brightness_ev: f64) -> Option<Vec<f32>> {
    let exposure = 2.0f64.powf(brightness_ev);
    let result: Vec<f32> = linear_srgb.iter().map(|v| (v * exposure) as f32).collect();
    if result.iter().all(|v| v.is_finite()) { Some(result) } else { None }
}

#[cfg(test)] mod tests {
    use super::*;
    fn entry(key: &str) -> Entry<()> { Entry { key: key.into(), input: vec![0., 0.5, 1.], linear_srgb: vec![-0.125, 0.5000000298023224, 1.25], metadata: (), corrected_density: None, film_log: None } }
    #[test] fn exact_hit_cold_brightness_and_out_of_range() {
        let mut cache = StageCache::default(); cache.configure(128*1024);
        let cold = entry("source-a/cpu/3x1/seed-42/data-1/params"); let input=cold.input.clone();
        for ev in [-2., 0., 2.] {
            let expected: Vec<f32> = cold.linear_srgb.iter().map(|v| (*v * 2.0f64.powf(ev)) as f32).collect();
            assert_eq!(finish(&cold.linear_srgb, ev).unwrap(), expected);
        }
        let expected=finish(&cold.linear_srgb,2.).unwrap();assert!(expected[0]<0. && expected[2]>1.);
        assert!(cache.publish(cache.epoch(),cold));
        let hit=cache.find("source-a/cpu/3x1/seed-42/data-1/params",&input).unwrap();
        assert_eq!(finish(&hit.linear_srgb,2.).unwrap(),expected);
        // This value distinguishes retaining f64 from early f32.
        assert_ne!(finish(&[0.13436424411240122],0.3).unwrap()[0],((0.13436424411240122f64 as f32) as f64*2.0f64.powf(0.3))as f32);
    }
    #[test] fn identity_input_and_latest_request_invalidation() {
        for key in ["source-b", "backend-b", "size-b", "rng-b", "data-b", "ae-b", "gamut-b", "warmth-b", "contrast-b", "saturation-b"] {
            let mut cache=StageCache::default();cache.configure(128*1024);assert!(cache.publish(cache.epoch(),entry("a")));
            assert!(cache.find(key,&[0.,0.5,1.]).is_none());assert!(cache.find("a",&[0.,0.5,1.]).is_none());
        }
        let mut cache=StageCache::default();cache.configure(128*1024);cache.publish(cache.epoch(),entry("a"));
        assert!(cache.find("a",&[-0.,0.5,1.]).is_none());
    }
    #[test] fn pressure_close_cancel_epoch_blocks_late_publication() {
        let mut cache=StageCache::default();cache.configure(128*1024);let old=cache.epoch();cache.publish(old,entry("a"));
        assert!(cache.bytes()>0);cache.configure(0);assert_eq!(cache.bytes(),0);assert!(!cache.publish(old,entry("a")));
        cache.configure(128*1024);assert!(!cache.publish(old,entry("a")));assert!(cache.publish(cache.epoch(),entry("a")));
    }
    #[test] fn cancel_keeps_completed_science_but_rejects_older_inflight_work() {
        let mut cache=StageCache::default();cache.configure(128*1024);let old=cache.epoch();
        assert!(cache.publish(old,entry("a")));cache.cancel_pending();
        assert!(cache.find("a",&[0.,0.5,1.]).is_some());
        assert!(!cache.publish(old,entry("b")));
        assert!(cache.find("a",&[0.,0.5,1.]).is_some());
    }
    #[test] fn budget_invalid_and_nonfinite_never_publish() {
        let mut cache=StageCache::default();cache.configure(1);assert!(!cache.publish(cache.epoch(),entry("a")));
        cache.configure(MAX_BYTES+1);assert!(!cache.enabled());cache.configure(128*1024);
        for x in [f64::NAN,f64::INFINITY] {let mut e=entry("a");e.linear_srgb[0]=x;assert!(!cache.publish(cache.epoch(),e));}
        let mut e=entry("a");e.input.pop();assert!(!cache.publish(cache.epoch(),e));
        assert!(finish(&[f64::MAX],2.).is_none());
    }
    #[test] fn reader_lease_blocks_retirement_and_preserves_accounting() {
        use std::sync::{Arc,Mutex,mpsc};
        let cache=Arc::new(Mutex::new(StageCache::default()));
        {let mut c=cache.lock().unwrap();c.configure(128*1024);let epoch=c.epoch();c.publish(epoch,entry("a"));}
        let mut lease=cache.lock().unwrap();let borrowed=lease.find("a",&[0.,0.5,1.]).unwrap();
        let other=cache.clone();let(tx,rx)=mpsc::channel();
        let worker=std::thread::spawn(move||{other.lock().unwrap().configure(0);tx.send(()).unwrap();});
        assert!(rx.try_recv().is_err());assert_eq!(finish(&borrowed.linear_srgb,0.).unwrap().len(),3);
        drop(lease);worker.join().unwrap();rx.recv().unwrap();assert_eq!(cache.lock().unwrap().bytes(),0);
    }
}

#[cfg(test)] mod film_log_owner_tests {
    use super::*;
    fn entry() -> Entry<()> { Entry { key: "final-a".into(), input: vec![0.1, 0.2, 0.3],
        linear_srgb: vec![0.1, 0.2, 0.3], metadata: (), corrected_density: None,
        film_log: Some(FilmLog { key: "prefix-a".into(), values: vec![-1., 0., 1.] }) } }
    #[test] fn downstream_miss_keeps_prefix_and_upstream_miss_destroys_it() {
        let mut cache=StageCache::default();cache.configure(128*1024);
        assert!(cache.publish(cache.epoch(),entry()));
        assert!(cache.find("final-b", &[0.1,0.2,0.3]).is_none());
        assert!(cache.copy_film_log("prefix-a", &[0.1,0.2,0.3]).is_some());
        assert!(cache.copy_film_log("prefix-b", &[0.1,0.2,0.3]).is_none());
        assert!(cache.copy_film_log("prefix-a", &[0.1,0.2,0.3]).is_none());
    }
    #[test] fn active_copy_survives_passive_retirement_without_lease_or_publication() {
        let mut cache=StageCache::default();cache.configure(128*1024);let epoch=cache.epoch();
        assert!(cache.publish(epoch,entry()));let log=cache.copy_film_log("prefix-a", &[0.1,0.2,0.3]).unwrap();
        cache.configure(0);assert_eq!(cache.bytes(),0);assert_eq!(log.values,[-1.,0.,1.]);
        assert!(!cache.publish(epoch,entry()));
    }
    #[test] fn exact_capacity_admission_and_nonfinite_prefix_rejection() {
        let value=entry();assert_eq!(value.bytes(),KEY_ALLOWANCE+48);
        let mut brightness_only=StageCache::<()>::default();brightness_only.configure(KEY_ALLOWANCE+36);
        assert!(!brightness_only.film_log_fits(3));
        brightness_only.configure(KEY_ALLOWANCE+48);assert!(brightness_only.film_log_fits(3));
        let mut cache=StageCache::default();cache.configure(value.bytes()-1);
        assert!(!cache.publish(cache.epoch(),value));
        cache.configure(128*1024);let mut value=entry();value.film_log.as_mut().unwrap().values[0]=f32::NAN;
        assert!(!cache.publish(cache.epoch(),value));
    }
}

#[cfg(test)] mod corrected_density_owner_tests {
    use super::*;
    fn entry() -> Entry<()> {
        Entry {key:"final".into(),input:vec![0.1,0.2,0.3],linear_srgb:vec![0.1,0.2,0.3],metadata:(),
            film_log:Some(FilmLog{key:"log".into(),values:vec![-1.,0.,1.]}),
            corrected_density:Some(FilmLog{key:"density".into(),values:vec![0.3,0.4,0.5]})}
    }
    #[test] fn second_slot_admission_preserves_first_slot_and_accounts_capacity() {
        let mut cache=StageCache::default();cache.configure(KEY_ALLOWANCE+48);
        assert!(cache.film_log_fits(3));assert!(!cache.corrected_density_fits(3));
        let mut first=entry();first.corrected_density=None;
        assert!(cache.publish(cache.epoch(),first));assert!(cache.copy_film_log("log",&[0.1,0.2,0.3]).is_some());
        assert!(!cache.publish(cache.epoch(),entry()));
        cache.configure(KEY_ALLOWANCE+60);assert!(cache.corrected_density_fits(3));
        assert!(cache.publish(cache.epoch(),entry()));
        let mut excessive=entry();excessive.corrected_density.as_mut().unwrap().values.reserve_exact(100);
        assert!(excessive.bytes()>KEY_ALLOWANCE+60);assert!(!cache.publish(cache.epoch(),excessive));
    }
    #[test] fn downstream_hit_upstream_miss_and_source_bits_are_exact() {
        let mut cache=StageCache::default();cache.configure(128*1024);
        assert!(cache.publish(cache.epoch(),entry()));
        assert!(cache.find("changed-grain",&[0.1,0.2,0.3]).is_none());
        assert!(cache.copy_corrected_density("density",&[0.1,0.2,0.3]).is_some());
        assert!(cache.copy_corrected_density("changed-dir",&[0.1,0.2,0.3]).is_none());
        assert!(cache.copy_corrected_density("density",&[0.1,0.2,0.3]).is_none());
        assert!(cache.copy_film_log("log",&[0.1,0.2,0.3]).is_some());
        assert!(cache.publish(cache.epoch(),entry()));
        assert!(cache.copy_corrected_density("density",&[0.1,0.2,f32::from_bits(0.3f32.to_bits()+1)]).is_none());
    }
    #[test] fn active_two_slot_writer_survives_retirement_but_cannot_publish() {
        use std::sync::{Arc,Mutex};
        let cache=Arc::new(Mutex::new(StageCache::default()));
        let (epoch,log,density)={let mut c=cache.lock().unwrap();c.configure(128*1024);
            let epoch=c.epoch();assert!(c.publish(epoch,entry()));
            (epoch,c.copy_film_log("log",&[0.1,0.2,0.3]).unwrap(),c.copy_corrected_density("density",&[0.1,0.2,0.3]).unwrap())};
        let other=cache.clone();std::thread::spawn(move||other.lock().unwrap().configure(0)).join().unwrap();
        assert_eq!(log.values,[-1.,0.,1.]);assert_eq!(density.values,[0.3,0.4,0.5]);
        let mut c=cache.lock().unwrap();assert_eq!(c.bytes(),0);
        let mut late=entry();late.film_log=Some(log);late.corrected_density=Some(density);
        c.configure(128*1024);assert!(!c.publish(epoch,late));assert_eq!(c.bytes(),0);
        let epoch=c.epoch();c.cancel_pending();assert!(!c.publish(epoch,entry()));
    }
    #[test] fn invalid_density_never_publishes() {
        let mut c=StageCache::default();c.configure(128*1024);
        let mut invalid=entry();invalid.corrected_density.as_mut().unwrap().values[0]=f32::INFINITY;
        assert!(!c.publish(c.epoch(),invalid));
        let mut invalid=entry();invalid.corrected_density.as_mut().unwrap().values.pop();
        assert!(!c.publish(c.epoch(),invalid));
    }
}
