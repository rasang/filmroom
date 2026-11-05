//! Speculative zero lookahead is allowed; consuming past actual encoded bytes is not.
use crate::pumps::BitPump;
pub(crate) struct StrictMsb<'a> {
  source: &'a [u8],
  position: usize,
  bits: u64,
  available: u32,
  used: usize,
  failed: bool,
}
impl<'a> StrictMsb<'a> {
  pub(crate) fn new(source: &'a [u8]) -> Self {
    Self {
      source,
      position: 0,
      bits: 0,
      available: 0,
      used: 0,
      failed: false,
    }
  }
  pub(crate) fn exhausted(&self) -> bool {
    self.failed
  }
}
impl BitPump for StrictMsb<'_> {
  fn peek_bits(&mut self, num: u32) -> u32 {
    assert!(num <= 32);
    if num == 0 {
      return 0;
    }
    while self.available < num {
      let b = self.source.get(self.position).copied().unwrap_or(0);
      self.position += 1;
      self.bits = (self.bits << 8) | b as u64;
      self.available += 8;
    }
    ((self.bits >> (self.available - num)) & ((1u64 << num) - 1)) as u32
  }
  fn consume_bits(&mut self, num: u32) {
    if num > self.available {
      self.failed = true;
      return;
    }
    self.available -= num;
    self.used += num as usize;
    if self.used > self.source.len() * 8 {
      self.failed = true;
    }
    self.bits &= if self.available == 0 {
      0
    } else {
      (1u64 << self.available) - 1
    };
  }
}
#[cfg(test)]
mod tests {
  use super::*;
  #[test]
  fn lookahead_does_not_authorize_fake_entropy() {
    let mut p = StrictMsb::new(&[0xab]);
    assert_eq!(p.peek_bits(16), 0xab00);
    assert_eq!(p.get_bits(8), 0xab);
    assert!(!p.exhausted());
    p.get_bits(1);
    assert!(p.exhausted());
  }
}
