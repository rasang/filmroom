// SPDX-License-Identifier: LGPL-2.1
//! Canon 4:2:0 expanded YCbCr interpolation. Observed chroma lives at even
//! physical rows and even columns; never interpolate an observed anchor.
//!
//! Integer policy follows LibRaw canon_sraw_load_raw: vertical first, then
//! horizontal, each (+1)>>1; duplicate chroma at the bottom/right edges.
//! RawSpeed uses a direct four-anchor floor average and may differ by one LSB.

/// Interpolate the first two rows of a window containing at most three rows.
/// The third row is a read-only future anchor and must stay untouched so the
/// caller can convert/write the first pair without corrupting the next pair.
/// Returns the number of output rows. Width is measured in u16 samples (3/pixel).
pub(super) fn interpolate_420_window(width: usize, image: &mut [u16]) -> usize {
  if width == 0 { assert!(image.is_empty()); return 0; }
  assert_eq!(width % 3, 0);
  assert_eq!(image.len() % width, 0);
  let rows=image.len()/width;
  assert!(rows<=3);
  let emitted=rows.min(2);
  if rows>=2 {
    for col in (0..width).step_by(6) {
      for c in 1..=2 {
        let upper=image[col+c] as u32;
        image[width+col+c]=if rows==3 {
          ((upper+image[2*width+col+c] as u32+1)>>1) as u16
        } else { upper as u16 };
      }
    }
  }
  for row in 0..emitted {
    for col in (3..width).step_by(6) {
      for c in 1..=2 {
        let left=image[row*width+col-3+c] as u32;
        image[row*width+col+c]=if col+3<width {
          ((left+image[row*width+col+3+c] as u32+1)>>1) as u16
        } else {left as u16};
      }
    }
  }
  emitted
}

pub(super) fn interpolate_420(width: usize, height: usize, image: &mut [u16]) {
  assert_eq!(width.checked_mul(height),Some(image.len()));
  if width==0 {return;}
  for row in (0..height).step_by(2) {
    let rows=3.min(height-row);
    interpolate_420_window(width,&mut image[row*width..(row+rows)*width]);
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  // Independent per-destination reference: choose global even anchors directly.
  // No calls to the production window helper and no shared loop boundaries.
  fn reference(w:usize,h:usize,input:&[u16])->Vec<u16> {
    let mut out=input.to_vec();
    for y in 0..h {for x in 0..w {for c in 1..=2 {
      let top=y/2*2;
      let bottom=(top+2).min((h-1)/2*2);
      let left=x/2*2;
      let right=(left+2).min((w-1)/2*2);
      let vertical=|col| {
        let a=input[(top*w+col)*3+c] as u32;
        if y%2==0 {a} else {(a+input[(bottom*w+col)*3+c] as u32+1)/2}
      };
      let a=vertical(left);
      out[(y*w+x)*3+c]=if x%2==0 {a as u16} else {((a+vertical(right)+1)/2) as u16};
    } } }
    out
  }
  #[test]
  #[ignore = "offline private real-frame oracle; requires CANON_QA_YUV_SAVE, WIDTH, HEIGHT"]
  fn private_real_frame_matches_independent_coordinates() {
    let path=std::env::var("CANON_QA_YUV_SAVE").unwrap();
    let w:usize=std::env::var("CANON_QA_WIDTH").unwrap().parse().unwrap();
    let h:usize=std::env::var("CANON_QA_HEIGHT").unwrap().parse().unwrap();
    let bytes=std::fs::read(path).unwrap();assert_eq!(bytes.len(),w*h*6);
    let mut actual:Vec<u16>=bytes.chunks_exact(2).map(|b|u16::from_le_bytes([b[0],b[1]])).collect();drop(bytes);
    let expected=reference(w,h,&actual);
    interpolate_420(w*3,h,&mut actual);
    assert_eq!(actual,expected,"real-frame chroma differs from coordinate oracle");
  }
  #[test]
  fn small_odd_even_sizes_match_independent_coordinates() {
    for w in 0..=9 {for h in 0..=9 {
      let input:Vec<_>=(0..w*h*3).map(|i|((i*617+i*i*13)%65536) as u16).collect();
      let expected=reference(w,h,&input);
      let mut full=input.clone();interpolate_420(w*3,h,&mut full);
      assert_eq!(full,expected,"{w}x{h}");
      for i in (0..input.len()).step_by(3) {assert_eq!(full[i],input[i],"Y changed");}
      for y in (0..h).step_by(2) {for x in (0..w).step_by(2) {
        let p=(y*w+x)*3;assert_eq!(&full[p+1..p+3],&input[p+1..p+3],"anchor changed");
      }}
    }}
  }
  #[test]
  fn streamed_windows_do_not_overwrite_next_anchor() {
    for w in [1,2,3,4,7] {for h in [1,2,3,4,5,8] {
      let input:Vec<_>=(0..w*h*3).map(|i|((i*1237)%65536) as u16).collect();
      let mut disk=input.clone();
      for y in (0..h).step_by(2) {
        let rows=3.min(h-y);let mut window=disk[y*w*3..(y+rows)*w*3].to_vec();
        let anchor=if rows==3 {Some(window[2*w*3..].to_vec())} else {None};
        let emitted=interpolate_420_window(w*3,&mut window);
        if let Some(anchor)=anchor {assert_eq!(&window[2*w*3..],&anchor);}
        disk[y*w*3..(y+emitted)*w*3].copy_from_slice(&window[..emitted*w*3]);
      }
      assert_eq!(disk,reference(w,h,&input));
    }}
  }
  #[test]
  fn constant_and_linear_chroma_weights_and_saturated_rounding() {
    for kind in 0..3 {
      let (w,h)=(6,6);let mut input=vec![0u16;w*h*3];
      for y in 0..h {for x in 0..w {
        let p=(y*w+x)*3;input[p]=4711;
        for c in 1..=2 {input[p+c]=match kind {0=>16384,1=>(100+10*(x/2*2)+20*(y/2*2)) as u16,_=>if (x/2+y/2)%2==0 {65535} else {0}};}
      }}
      let expected=reference(w,h,&input);interpolate_420(w*3,h,&mut input);assert_eq!(input,expected);
      if kind==1 {for y in 0..h-1 {for x in 0..w-1 {assert_eq!(input[(y*w+x)*3+1],(100+10*x+20*y) as u16);}}}
    }
  }
}
