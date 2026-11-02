// SPDX-License-Identifier: LGPL-2.1
//! Inverse of the original full-height vertical-slice copy, in sample units.
pub(super) struct BayerLayout {width:usize,height:usize,starts:Vec<usize>}
impl BayerLayout {
 pub(super) fn new(width:usize,height:usize,fields:&[usize])->Result<Self,String> {
  if width==0 || height==0 || fields.is_empty() || fields.len()>4096 || fields.contains(&0) {return Err("Invalid Bayer slice layout".into());}
  width.checked_mul(height).ok_or("Bayer slice size overflow")?;
  let mut starts=vec![0usize];
  for field in fields {starts.push(starts.last().unwrap().checked_add(*field).ok_or("Bayer slice width overflow")?);}
  if starts.last()!=Some(&width) {return Err("Bayer slice widths do not sum to final width".into());}
  Ok(Self{width,height,starts})
 }
 pub(super) fn destination(&self,source:usize)->usize {
  let field=self.starts.partition_point(|s|*s*self.height<=source)-1;
  let local=source-self.starts[field]*self.height;
  let field_width=self.starts[field+1]-self.starts[field];
  (local/field_width)*self.width+self.starts[field]+local%field_width
 }
}
#[cfg(test)]
mod tests {
 use super::*;
 #[test]
 fn matches_old_literal_copy_unequal_slices_and_reshape() {
  for fields in [vec![2],vec![1,3],vec![2,2,1],vec![4,3,7]] {for height in [1,2,3,8,17] {
   let width=fields.iter().sum::<usize>();let size=width*height;
   let source:Vec<u16>=(0..size).map(|v|v as u16).collect();let mut old=vec![0;size];
   let(mut fieldstart,mut fieldpos)=(0,0);
   for fieldwidth in &fields {for row in 0..height {old[row*width+fieldstart..row*width+fieldstart+fieldwidth].copy_from_slice(&source[fieldpos+row*fieldwidth..fieldpos+(row+1)*fieldwidth]);}fieldstart+=fieldwidth;fieldpos+=fieldwidth*height;}
   let layout=BayerLayout::new(width,height,&fields).unwrap();
   for (s,p) in source.iter().enumerate(){assert_eq!(*p,old[layout.destination(s)]);}
   // Decoded JPEG row boundaries are irrelevant, including double_line reshape.
   for jpeg_width in [1,2,3,4,7,14] {if size%jpeg_width==0 {
    let mut out=vec![0;size];for (r,row) in source.chunks(jpeg_width).enumerate(){for (c,p) in row.iter().enumerate(){out[layout.destination(r*jpeg_width+c)]=*p;}}
    assert_eq!(out,old);
   }}
  }}
 }
 #[test]
 fn invalid_fields_rejected() {
  for fields in [vec![],vec![0,4],vec![2,3],vec![usize::MAX,1]] {assert!(BayerLayout::new(4,2,&fields).is_err());}
  assert!(BayerLayout::new(usize::MAX,2,&[usize::MAX]).is_err());
 }
}
