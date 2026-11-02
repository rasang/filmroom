// SPDX-License-Identifier: LGPL-2.1
use crate::bits::LookupTable;
pub(super) fn linearize_row(table:&LookupTable,random:&mut Option<u32>,pixels:&[u16],copy:&mut Vec<u16>) {
 copy.clear();copy.extend_from_slice(pixels);
 if let Some(first)=pixels.first() {
  let seed=random.get_or_insert(*first as u32);
  for p in copy {*p=table.dither(*p,seed);}
 }
}
