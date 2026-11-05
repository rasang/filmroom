//! Pure ownership/admission contract for an isolated, serialized science helper.
//!
//! This module does not establish Binder death, acquire Android memory leases,
//! fence GPU work, or enable persistent execution. The transport/context owner
//! supplies those proofs. In particular `complete_stage` is called only after
//! queue completion, unmapping, and closing all stage-owned file handles.
//! Known allocation charges never decrease before confirmed process death.
use sha2::{Digest, Sha256};

pub(crate) const MAX_SESSION_PIPELINES: usize = 256;
pub(crate) const MAX_SESSION_PREPAID_BYTES: u64 = 2 * 1024 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ContractError {
    InvalidConfiguration,
    WrongEpoch,
    StaleGeneration,
    WrongSequence,
    Busy,
    Retired,
    InsufficientPrepayment,
    PipelineLimit,
    Overflow,
}
pub(crate) type ContractResult<T> = Result<T, ContractError>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct StageTicket {
    pub epoch: u64,
    pub generation: u64,
    pub sequence: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum State {
    Ready,
    Frame,
    Running(StageTicket),
    Stopping,
    Dead,
}

/// Worker-confined state; synchronization belongs to the sole session owner.
/// `charged_bytes` is cumulative known allocations plus fixed allowances, NOT
/// a measurement or upper bound of driver-private memory or process RSS.
pub(crate) struct SessionContract {
    epoch: u64,
    generation: u64,
    last_sequence: u64,
    prepaid_bytes: u64,
    charged_bytes: u64,
    pipeline_limit: usize,
    pipeline_slots_charged: usize,
    state: State,
}
impl SessionContract {
    pub(crate) fn new(
        epoch: u64,
        prepaid_bytes: u64,
        fixed_bytes: u64,
        pipeline_limit: usize,
    ) -> ContractResult<Self> {
        if epoch == 0 || prepaid_bytes == 0 || prepaid_bytes > MAX_SESSION_PREPAID_BYTES
            || fixed_bytes == 0 || fixed_bytes > prepaid_bytes
            || pipeline_limit == 0 || pipeline_limit > MAX_SESSION_PIPELINES
        {
            return Err(ContractError::InvalidConfiguration);
        }
        Ok(Self {
            epoch, generation: 0, last_sequence: 0, prepaid_bytes,
            charged_bytes: fixed_bytes, pipeline_limit, pipeline_slots_charged: 0,
            state: State::Ready,
        })
    }
    fn live(&self) -> ContractResult<()> {
        match self.state {
            State::Stopping | State::Dead => Err(ContractError::Retired),
            _ => Ok(()),
        }
    }
    fn epoch_matches(&self, epoch: u64) -> ContractResult<()> {
        if epoch == self.epoch { Ok(()) } else { Err(ContractError::WrongEpoch) }
    }
    pub(crate) fn epoch(&self) -> u64 { self.epoch }
    pub(crate) fn prepaid_bytes(&self) -> u64 { self.prepaid_bytes }
    pub(crate) fn charged_bytes(&self) -> u64 { self.charged_bytes }
    pub(crate) fn pipeline_slots_charged(&self) -> usize { self.pipeline_slots_charged }
    pub(crate) fn is_retired(&self) -> bool { matches!(self.state, State::Stopping | State::Dead) }
    pub(crate) fn is_dead(&self) -> bool { self.state == State::Dead }

    /// Call ONLY after the Android owner has retained the additional lease.
    /// This is an acknowledgement of prepayment, not permission to acquire it.
    pub(crate) fn grant_prepaid(&mut self, new_total: u64) -> ContractResult<()> {
        self.live()?;
        if new_total < self.prepaid_bytes || new_total > MAX_SESSION_PREPAID_BYTES {
            return Err(ContractError::InvalidConfiguration);
        }
        self.prepaid_bytes = new_total;
        Ok(())
    }
    /// Charge BEFORE attempting a new allocation. Failed/abandoned allocations
    /// remain charged conservatively. Returning a physical buffer to the frozen
    /// arena requires no call; allocating a replacement does. Never refund Drop.
    pub(crate) fn charge_allocation(&mut self, bytes: u64) -> ContractResult<()> {
        self.live()?;
        let next = self.charged_bytes.checked_add(bytes).ok_or(ContractError::Overflow)?;
        if next > self.prepaid_bytes { return Err(ContractError::InsufficientPrepayment); }
        self.charged_bytes = next;
        Ok(())
    }
    /// Reserve a lifetime compilation slot AND its conservative allowance before
    /// compilation. A cache hit costs no new slot. Eviction or failed compilation
    /// does not refund the slot or allowance; rotate the helper when exhausted.
    pub(crate) fn reserve_pipeline(&mut self, allowance_bytes: u64) -> ContractResult<()> {
        self.reserve_pipeline_slots(1, allowance_bytes)
    }
    /// Charge a whole admitted shader family without inventing a per-pipeline
    /// memory estimate by dividing its aggregate allowance. All slots/bytes are
    /// reserved atomically before compilation and never refunded within epoch.
    pub(crate) fn reserve_pipeline_slots(&mut self, count: usize, allowance_bytes: u64) -> ContractResult<()> {
        self.live()?;
        if count == 0 || allowance_bytes == 0 { return Err(ContractError::InvalidConfiguration); }
        let slots = self.pipeline_slots_charged.checked_add(count).ok_or(ContractError::Overflow)?;
        if slots > self.pipeline_limit { return Err(ContractError::PipelineLimit); }
        self.charge_allocation(allowance_bytes)?;
        self.pipeline_slots_charged = slots;
        Ok(())
    }
    pub(crate) fn begin_frame(&mut self, generation: u64) -> ContractResult<()> {
        self.live()?;
        if self.state != State::Ready { return Err(ContractError::Busy); }
        if generation == 0 || generation <= self.generation { return Err(ContractError::StaleGeneration); }
        self.generation = generation;
        self.state = State::Frame;
        Ok(())
    }
    /// Sequence is consecutive across the entire epoch, not reset per frame.
    pub(crate) fn begin_stage(&mut self, ticket: StageTicket) -> ContractResult<()> {
        self.live()?;
        self.epoch_matches(ticket.epoch)?;
        if ticket.generation != self.generation { return Err(ContractError::StaleGeneration); }
        if self.state != State::Frame { return Err(ContractError::Busy); }
        let expected = self.last_sequence.checked_add(1).ok_or(ContractError::Overflow)?;
        if ticket.sequence != expected { return Err(ContractError::WrongSequence); }
        self.state = State::Running(ticket);
        Ok(())
    }
    /// A successful stage seal proves no more access to its temporary resources.
    /// It NEVER authorizes releasing the session lease or reporting *_stopped.
    pub(crate) fn complete_stage(&mut self, ticket: StageTicket) -> ContractResult<()> {
        self.live()?;
        self.epoch_matches(ticket.epoch)?;
        if ticket.generation != self.generation { return Err(ContractError::StaleGeneration); }
        if self.state != State::Running(ticket) { return Err(ContractError::WrongSequence); }
        self.last_sequence = ticket.sequence;
        self.state = State::Frame;
        Ok(())
    }
    pub(crate) fn finish_frame(&mut self, generation: u64) -> ContractResult<()> {
        self.live()?;
        if generation != self.generation { return Err(ContractError::StaleGeneration); }
        if self.state != State::Frame { return Err(ContractError::Busy); }
        self.state = State::Ready;
        Ok(())
    }
    /// Cancel, timeout, trim, device loss and close retire this epoch permanently.
    /// Initial policy kills instead of trying to reuse a partially cancelled GPU.
    pub(crate) fn request_stop(&mut self) {
        if self.state != State::Dead { self.state = State::Stopping; }
    }
    /// Parent-side proof hook only: NEVER infer death from JNI return, a timeout,
    /// dropping Device/Buffer, or receiving a frame result. The helper cannot
    /// call this as proof of its own death. The lease owner must verify Binder
    /// death (or a proven never-launched revocation) independently.
    pub(crate) fn confirm_dead(&mut self, epoch: u64) -> ContractResult<()> {
        self.epoch_matches(epoch)?;
        self.state = State::Dead;
        Ok(())
    }
}

/// Exact code/layout identity only. This deliberately does NOT authorize reuse
/// of uniforms, tables, profiles, source pixels, numeric flags, or output images.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) struct PipelineKey([u8; 32]);
impl PipelineKey {
    pub(crate) fn new(
        device_epoch: u64,
        shader: &str,
        layout_abi: &[u8],
        entry: &str,
        compilation_abi: &[u8],
    ) -> ContractResult<Self> {
        if device_epoch == 0 || shader.is_empty() || layout_abi.is_empty()
            || entry.is_empty() || compilation_abi.is_empty()
        { return Err(ContractError::InvalidConfiguration); }
        let mut digest = Sha256::new();
        digest.update(b"filmroom-science-pipeline-v1");
        digest.update(device_epoch.to_le_bytes());
        for field in [shader.as_bytes(), layout_abi, entry.as_bytes(), compilation_abi] {
            let length = u64::try_from(field.len()).map_err(|_| ContractError::Overflow)?;
            digest.update(length.to_le_bytes());
            digest.update(field);
        }
        Ok(Self(digest.finalize().into()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn session() -> SessionContract { SessionContract::new(7, 1000, 100, 2).unwrap() }
    fn ticket(generation: u64, sequence: u64) -> StageTicket { StageTicket { epoch: 7, generation, sequence } }
    #[test]
    fn generations_and_sequences_do_not_replay() {
        let mut s = session();
        s.begin_frame(1).unwrap();
        assert_eq!(s.begin_stage(ticket(1, 2)), Err(ContractError::WrongSequence));
        s.begin_stage(ticket(1, 1)).unwrap();
        assert_eq!(s.finish_frame(1), Err(ContractError::Busy));
        assert_eq!(s.complete_stage(ticket(1, 2)), Err(ContractError::WrongSequence));
        s.complete_stage(ticket(1, 1)).unwrap();
        assert_eq!(s.begin_stage(ticket(1, 1)), Err(ContractError::WrongSequence));
        s.finish_frame(1).unwrap();
        assert_eq!(s.begin_frame(1), Err(ContractError::StaleGeneration));
        s.begin_frame(2).unwrap();
        assert_eq!(s.begin_stage(ticket(1, 1)), Err(ContractError::StaleGeneration));
        assert_eq!(s.begin_stage(ticket(2, 1)), Err(ContractError::WrongSequence));
        s.begin_stage(ticket(2, 2)).unwrap();
        s.complete_stage(ticket(2, 2)).unwrap();
    }
    #[test]
    fn allocation_is_prepaid_cumulative_and_failed_charge_is_atomic() {
        let mut s = session();
        s.charge_allocation(800).unwrap();
        assert_eq!(s.charge_allocation(101), Err(ContractError::InsufficientPrepayment));
        assert_eq!(s.charged_bytes(), 900);
        s.grant_prepaid(1100).unwrap();
        s.charge_allocation(200).unwrap();
        assert_eq!(s.grant_prepaid(1000), Err(ContractError::InvalidConfiguration));
        s.begin_frame(1).unwrap(); s.finish_frame(1).unwrap();
        assert_eq!(s.charged_bytes(), 1100);
    }
    #[test]
    fn pipeline_slots_are_lifetime_bounded() {
        let mut s = session();
        assert_eq!(s.reserve_pipeline(901), Err(ContractError::InsufficientPrepayment));
        assert_eq!(s.pipeline_slots_charged(), 0);
        s.reserve_pipeline(100).unwrap(); s.reserve_pipeline(100).unwrap();
        assert_eq!(s.reserve_pipeline(100), Err(ContractError::PipelineLimit));
        assert_eq!(s.charged_bytes(), 300);
    }
    #[test]
    fn family_reservation_is_atomic() {
        let mut s = session();
        assert_eq!(s.reserve_pipeline_slots(3, 100), Err(ContractError::PipelineLimit));
        assert_eq!(s.charged_bytes(), 100);
        assert_eq!(s.reserve_pipeline_slots(2, 901), Err(ContractError::InsufficientPrepayment));
        assert_eq!(s.pipeline_slots_charged(), 0);
        assert_eq!(s.charged_bytes(), 100);
        s.reserve_pipeline_slots(2, 500).unwrap();
        assert_eq!(s.pipeline_slots_charged(), 2);
        assert_eq!(s.charged_bytes(), 600);
    }
    #[test]
    fn cancellation_retires_epoch_without_releasing_charge() {
        let mut s = session(); s.begin_frame(1).unwrap(); s.begin_stage(ticket(1, 1)).unwrap();
        s.request_stop();
        assert!(!s.is_dead()); assert!(s.is_retired());
        assert_eq!(s.complete_stage(ticket(1, 1)), Err(ContractError::Retired));
        assert_eq!(s.begin_frame(2), Err(ContractError::Retired));
        assert_eq!(s.grant_prepaid(2000), Err(ContractError::Retired));
        assert_eq!(s.confirm_dead(8), Err(ContractError::WrongEpoch));
        assert_eq!(s.charged_bytes(), 100);
        s.confirm_dead(7).unwrap(); s.request_stop(); assert!(s.is_dead());
        assert_eq!(s.charged_bytes(), 100);
        assert!(SessionContract::new(8, 1000, 100, 2).is_ok());
    }
    #[test]
    fn key_distinguishes_code_layout_epoch_and_unambiguous_fields() {
        let key = |epoch, source: &str, layout: &[u8], entry: &str, options: &[u8]| PipelineKey::new(epoch, source, layout, entry, options).unwrap();
        let a = key(7, "shader", b"layout", "main", b"defaults");
        assert_eq!(a, key(7, "shader", b"layout", "main", b"defaults"));
        assert_ne!(a, key(8, "shader", b"layout", "main", b"defaults"));
        assert_ne!(a, key(7, "shader2", b"layout", "main", b"defaults"));
        assert_ne!(a, key(7, "shader", b"layout2", "main", b"defaults"));
        assert_ne!(a, key(7, "shader", b"layout", "other", b"defaults"));
        assert_ne!(a, key(7, "shader", b"layout", "main", b"other"));
        assert_ne!(key(7, "ab", b"c", "main", b"x"), key(7, "a", b"bc", "main", b"x"));
    }
}
