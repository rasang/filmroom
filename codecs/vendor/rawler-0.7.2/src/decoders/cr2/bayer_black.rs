// SPDX-License-Identifier: LGPL-2.1
//! Keep masked-black accumulation order identical to RawImage::calc_black_levels.
pub(super) fn masked_black_levels(
 width:usize,height:usize,cfa_width:usize,cfa_height:usize,areas:&[(usize,usize,usize,usize)],
 mut read:impl FnMut(usize,&mut[u16])->Result<(),String>,
)->Result<[f32;4],String> {
 width.checked_mul(height).ok_or("CR2 black image size overflow")?;
 if cfa_width.checked_mul(cfa_height)!=Some(4) || cfa_width==0 || cfa_height==0 {return Err("Unsupported CR2 masked-black CFA".into());}
 let mut sums=[0f32;4];let mut counts=[0usize;4];let mut row=Vec::new();
 for &(x,y,w,h) in areas {
  let end_x=x.checked_add(w).ok_or("CR2 black area overflow")?;
  let end_y=y.checked_add(h).ok_or("CR2 black area overflow")?;
  if end_x>width || end_y>height {return Err("CR2 black area outside image".into());}
  row.resize(w,0);
  for y in y..end_y {
   read(y*width+x,&mut row)?;
   for (i,p) in row.iter().enumerate(){let c=(y%cfa_height)*cfa_width+(x+i)%cfa_width;sums[c]+=*p as f32;counts[c]+=1;}
  }
 }
 Ok(std::array::from_fn(|c|sums[c]/counts[c] as f32))
}
