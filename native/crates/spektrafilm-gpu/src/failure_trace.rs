//! Fixed enum telemetry only: never retains driver errors, paths or panic text.
use std::{cell::Cell, time::Instant};
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind { UnknownUnwind, SubmitUnwind, PollUnwind, MapFailed, MapTimeout }
impl Kind { pub fn as_str(self)->&'static str { match self {Self::UnknownUnwind=>"unknown_unwind",Self::SubmitUnwind=>"submit_unwind",Self::PollUnwind=>"poll_unwind",Self::MapFailed=>"map_failed",Self::MapTimeout=>"map_timeout"} } }
#[derive(Debug, Clone, Copy)]
pub struct Snapshot {pub kind:Kind,pub submit_ms:f64,pub poll_ms:f64,pub map_wait_ms:f64}
impl Default for Snapshot { fn default()->Self{Self{kind:Kind::UnknownUnwind,submit_ms:0.,poll_ms:0.,map_wait_ms:0.}} }
thread_local! {static TRACE:Cell<Snapshot> = Cell::new(Snapshot::default());}
pub fn reset(){TRACE.with(|v|v.set(Snapshot::default()));}
pub fn snapshot()->Snapshot{TRACE.with(Cell::get)}
pub fn fail(kind:Kind){TRACE.with(|v|{let mut s=v.get();s.kind=kind;v.set(s);});}
#[derive(Clone,Copy)] pub enum Step{Submit,Poll,Wait}
pub struct Span{step:Step,start:Instant}
impl Span{pub fn new(step:Step)->Self{Self{step,start:Instant::now()}}}
impl Drop for Span{fn drop(&mut self){TRACE.with(|v|{let mut s=v.get();let ms=self.start.elapsed().as_secs_f64()*1000.;match self.step{Step::Submit=>{s.submit_ms+=ms;if std::thread::panicking(){s.kind=Kind::SubmitUnwind;}},Step::Poll=>{s.poll_ms+=ms;if std::thread::panicking(){s.kind=Kind::PollUnwind;}},Step::Wait=>s.map_wait_ms+=ms}v.set(s);});}}
#[cfg(test)] mod tests{use super::*;
#[test] fn specific_failure_survives_wait_unwind(){reset();let _=std::panic::catch_unwind(||{let _s=Span::new(Step::Wait);fail(Kind::MapTimeout);panic!("private driver text");});assert_eq!(snapshot().kind,Kind::MapTimeout);assert!(snapshot().map_wait_ms>=0.);}
#[test] fn poll_unwind_and_reset(){reset();let _=std::panic::catch_unwind(||{let _s=Span::new(Step::Poll);panic!("private text");});assert_eq!(snapshot().kind,Kind::PollUnwind);reset();assert_eq!(snapshot().kind,Kind::UnknownUnwind);assert_eq!(snapshot().poll_ms,0.);}
#[test] fn normal_submit_and_thread_isolation(){reset();{let _s=Span::new(Step::Submit);}assert_eq!(snapshot().kind,Kind::UnknownUnwind);fail(Kind::MapFailed);std::thread::spawn(||assert_eq!(snapshot().kind,Kind::UnknownUnwind)).join().unwrap();assert_eq!(snapshot().kind,Kind::MapFailed);}
}
