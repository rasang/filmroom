mod bits {pub use rawler::bits::LookupTable;}
#[path="../vendor/rawler-0.7.2/src/decoders/cr2/bayer_linearization.rs"]
mod linearization;
#[test]
fn original_dither_seed_and_sample_sequence_survive_arbitrary_row_boundaries() {
 let table:Vec<u16>=(0..4096).map(|i|((i*i/4096)*8) as u16).collect();let table=bits::LookupTable::new(&table);
 let input:Vec<u16>=(0..113).map(|i|((i*397+31)%4096) as u16).collect();
 let mut expected=input.clone();let mut seed=expected[0] as u32;for p in &mut expected {*p=table.dither(*p,&mut seed);}
 for width in [1,2,4,17,113] {
  let mut random=None;let mut copy=Vec::new();let mut actual=Vec::new();
  for pixels in input.chunks(width) {linearization::linearize_row(&table,&mut random,pixels,&mut copy);actual.extend_from_slice(&copy);}
  assert_eq!(actual,expected);assert_eq!(random,Some(seed));
 }
}
