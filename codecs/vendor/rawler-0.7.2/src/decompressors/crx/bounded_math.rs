// SPDX-License-Identifier: LGPL-2.1
// Filmroom: profile-independent arithmetic at the CRX quantization boundary.
pub fn scaled(value: i32, scale: i64) -> Option<i32> {
  i32::try_from(i64::from(value).checked_mul(scale)?).ok()
}
pub fn quantized(value: i32, base: i32, step: u32, multiplier: u32) -> Option<i32> {
  let extra = ((u64::from(step) * u64::from(multiplier)) >> 3) as i64;
  let scale = (i64::from(base) + extra).clamp(1, 0x168000);
  scaled(value, scale)
}
pub fn planes(bits: u8, encoding: u8, v: [i32; 4]) -> Option<[u16; 4]> {
  if !matches!(bits, 12 | 14 | 16) {
    return None;
  }
  let max = (1i64 << bits) - 1;
  let clip = |x: i64| x.clamp(0, max) as u16;
  let [v0, v1, v2, v3] = v.map(i64::from);
  let median = 1i64 << (bits - 1);
  match encoding {
    0 => Some([
      clip(median + v0),
      clip(median + v1),
      clip(median + v2),
      clip(median + v3),
    ]),
    3 => {
      let median = median << 10;
      let gr = median + (v0 << 10) - 168 * v1 - 585 * v3;
      let rounded = ((gr.abs() + 512) >> 9) & !1;
      let gr = if gr < 0 { -rounded } else { rounded };
      Some([
        clip((median + (v0 << 10) + 1510 * v3 + 512) >> 10),
        clip((v2 + gr + 1) >> 1),
        clip((gr - v2 + 1) >> 1),
        clip((median + (v0 << 10) + 1927 * v1 + 512) >> 10),
      ])
    }
    _ => None,
  }
}
