//! Stage-local work progress. Work units are completed image operations, not time.
//! Boundaries are emitted by the executing loops; never inferred from polling.
const UNIT: u64 = 1_000_000;
#[derive(Clone, Copy, Debug, Default)]
pub struct GpuWorkProgress {
    pub phase:Option<&'static str>, pub tile_index:u32, pub tile_total:u32,
    pub batch_index:u32, pub batch_elapsed_ms:f64, pub chain_wait_ms:f64,
    pub failure_kind:Option<&'static str>,pub submit_ms:Option<f64>,pub poll_ms:Option<f64>,pub map_wait_ms:Option<f64>,
    pub completed_submits:u64, pub failed_phase:Option<&'static str>, pub failed_elapsed_ms:Option<f64>,
}
#[derive(Clone, Copy, Debug)]
pub struct ExportProgress {
    /// True only for an original algorithm pass callback; stage/work boundaries are false.
    pub pass_updated: bool,
    pub gpu:Option<GpuWorkProgress>,
    pub phase: &'static str,
    pub done: u64,
    pub total: u64,
    pub display_stage: u8,
    pub stage_done: u64,
    pub stage_total: u64,
}
pub(crate) trait ProgressSink {
    fn begin_stage(&mut self, stage: u8, work: u64, phase: &'static str);
    fn finish_stage(&mut self);
    fn pass_fraction(
        &mut self,
        phase: &'static str,
        done: u64,
        total: u64,
        fraction_done: u64,
        fraction_total: u64,
    );
    fn pass(&mut self, phase: &'static str, done: u64, total: u64) {
        self.pass_fraction(phase, done, total, done, total);
    }
    fn begin_work(&mut self, phase: &'static str);
    fn end_work(&mut self);
    fn gpu_event(&mut self, _value:GpuWorkProgress) {}
}
pub(crate) struct Reporter<F: FnMut(ExportProgress)> {
    emit: F,
    state: ExportProgress,
    completed: u64,
    planned: u64,
    working: bool,
}
impl<F: FnMut(ExportProgress)> Reporter<F> {
    pub fn new(emit: F) -> Self {
        Self {
            emit,
            state: ExportProgress {
                pass_updated: false, gpu:None,
                phase: "setup",
                done: 0,
                total: 0,
                display_stage: 1,
                stage_done: 0,
                stage_total: 0,
            },
            completed: 0,
            planned: 0,
            working: false,
        }
    }
    pub fn begin_stage(&mut self, stage: u8, work: u64, phase: &'static str) {
        self.completed = 0;
        self.planned = work;
        self.working = false;
        // One final unit represents successful stage finalization. Until it has
        // happened the percentage cannot claim a completed stage.
        self.state = ExportProgress {
            pass_updated: false, gpu:None,
            phase,
            done: 0,
            total: 0,
            display_stage: stage,
            stage_done: 0,
            stage_total: (work + 1) * UNIT,
        };
        (self.emit)(self.state);
    }
    /// Out-of-band execution decision. This is not work or a completed pass.
    /// Emit before CPU setup so cancellation never erases the fallback reason.
    pub fn diagnostic(&mut self, phase: &'static str) {
        (self.emit)(ExportProgress { pass_updated:false, gpu:None, phase, done:0, total:0,
            display_stage:1, stage_done:0, stage_total:0 });
    }
    pub fn gpu_failure(&mut self, phase:&'static str, elapsed_ms:f64) {
        let mut value=self.state.gpu.unwrap_or_default();value.failed_phase=Some(phase);value.failed_elapsed_ms=Some(elapsed_ms);
        self.gpu_event(value);
    }
    pub fn gpu_failure_detail(&mut self, kind:&'static str, submit:f64, poll:f64, wait:f64) {
        let mut value=self.state.gpu.unwrap_or_default();value.failure_kind=Some(kind);value.submit_ms=Some(submit);value.poll_ms=Some(poll);value.map_wait_ms=Some(wait);self.gpu_event(value);
    }
    pub fn finish_stage(&mut self) {
        self.state.pass_updated = false;
        debug_assert_eq!(
            self.completed, self.planned,
            "progress budget for stage {}",
            self.state.display_stage
        );
        debug_assert!(!self.working);
        if self.completed == self.planned && !self.working {
            self.state.stage_done = self.state.stage_total;
        } else {
            self.state.stage_done = 0;
            self.state.stage_total = 0;
        }
        (self.emit)(self.state);
    }
}
impl<F: FnMut(ExportProgress)> ProgressSink for Reporter<F> {
    fn gpu_event(&mut self, value:GpuWorkProgress) {
        self.state.gpu=Some(value);let mut event=self.state;event.pass_updated=false;(self.emit)(event);
    }
    fn begin_stage(&mut self, stage: u8, work: u64, phase: &'static str) {
        Reporter::begin_stage(self, stage, work, phase);
    }
    fn finish_stage(&mut self) {
        Reporter::finish_stage(self);
    }
    fn pass_fraction(&mut self, phase: &'static str, done: u64, total: u64, fd: u64, ft: u64) {
        self.state.pass_updated = true;
        self.state.phase = phase;
        self.state.done = done;
        self.state.total = total;
        if self.working {
            self.state.stage_done = self.completed * UNIT
                + if ft > 0 {
                    ((fd.min(ft) as u128 * UNIT as u128) / ft as u128) as u64
                } else {
                    0
                };
        }
        (self.emit)(self.state);
    }
    fn begin_work(&mut self, phase: &'static str) {
        debug_assert!(!self.working);
        self.working = true;
        self.state.pass_updated = false;
        self.state.phase = phase;
        self.state.done = 0;
        self.state.total = 0;
        self.state.stage_done = self.completed * UNIT;
        (self.emit)(self.state);
    }
    fn end_work(&mut self) {
        debug_assert!(self.working);
        self.working = false;
        self.state.pass_updated = false;
        self.completed += 1;
        self.state.stage_done = self.completed * UNIT;
        (self.emit)(self.state);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn all_passes_share_one_stage_budget() {
        let mut events = Vec::new();
        {
            let mut p = Reporter::new(|v| events.push(v));
            p.begin_stage(5, 2, "grain_sampling");
            p.begin_work("grain_sampling");
            p.pass("grain_sampling", 2, 4);
            p.pass("grain_sampling", 4, 4);
            p.end_work();
            p.begin_work("grain_cloud");
            p.pass_fraction("grain_cloud", 8, 8, 1, 2);
            p.pass_fraction("grain_cloud", 0, 16, 1, 2);
            p.pass_fraction("grain_cloud", 16, 16, 2, 2);
            p.end_work();
            p.finish_stage();
        }
        assert!(events
            .windows(2)
            .all(|w| w[0].stage_done <= w[1].stage_done));
        assert!(events[..events.len() - 1]
            .iter()
            .all(|e| e.stage_done < e.stage_total));
        assert_eq!(events.last().unwrap().stage_done, 3 * UNIT);
    }
    #[test]
    fn failure_details_preserve_last_completed_work() {
        let mut events=Vec::new();
        { let mut p=Reporter::new(|v|events.push(v));
          p.gpu_event(GpuWorkProgress{tile_index:10,tile_total:20,batch_index:18,completed_submits:277,..Default::default()});
          p.gpu_failure("scan",29650.);
          p.gpu_failure_detail("map_timeout",2.,29000.,29600.);
        }
        let last=events.last().unwrap();let g=last.gpu.unwrap();
        assert_eq!(g.failed_phase,Some("scan"));assert_eq!(g.failure_kind,Some("map_timeout"));
        assert_eq!(g.tile_index,10);assert_eq!(g.completed_submits,277);assert_eq!(g.poll_ms,Some(29000.));
        assert_eq!(last.stage_done,0);assert!(!last.pass_updated);
    }

}
