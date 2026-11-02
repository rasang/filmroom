//! Filmroom opt-in bounds for untrusted camera metadata. No pixel allocations.
//! A sticky failure is checked by the caller even if a tolerant parser skips a tag.
use std::cell::Cell;
#[derive(Clone, Copy)]
struct State {
  bytes: usize,
  ifds: usize,
  failed: bool,
}
thread_local! { static BUDGET: Cell<Option<State>> = const { Cell::new(None) }; }
struct Restore(Option<State>);
impl Drop for Restore {
  fn drop(&mut self) {
    BUDGET.with(|b| b.set(self.0));
  }
}
pub fn bounded<T>(f: impl FnOnce() -> T) -> (T, bool) {
  let _restore = Restore(BUDGET.with(|b| {
    b.replace(Some(State {
      bytes: 4 * 1024 * 1024,
      ifds: 64,
      failed: false,
    }))
  }));
  let result = f();
  let exceeded = BUDGET.with(|b| b.get().is_some_and(|s| s.failed));
  (result, exceeded)
}
pub(crate) fn charge(bytes: usize, ifd: bool) -> bool {
  BUDGET.with(|b| {
    let Some(mut s) = b.get() else { return true };
    if s.failed || bytes > 1024 * 1024 || bytes > s.bytes || (ifd && s.ifds == 0) {
      s.failed = true;
    } else {
      s.bytes -= bytes;
      if ifd {
        s.ifds -= 1;
      }
    }
    b.set(Some(s));
    !s.failed
  })
}
