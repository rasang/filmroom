#![allow(dead_code)]
#[derive(Debug)]enum RawlerError{DecoderFailed(String)}
type Result<T>=std::result::Result<T,RawlerError>;
fn unslice_rgb_in_place(data: &mut [u16], width: usize, height: usize, ljpeg_width: usize,
                        fields: &[usize], super_h: usize, super_v: usize) -> Result<()> {
  let fail = || RawlerError::DecoderFailed("Invalid CR2 RGB slice layout".into());
  if width == 0 || height == 0 || data.len() != width.checked_mul(height).ok_or_else(fail)?
      || super_h != 2 || !matches!(super_v, 1 | 2) || fields.is_empty() || fields.len() > 4096 {
    return Err(fail());
  }
  let widths: Vec<usize> = fields.iter().map(|f| if super_v == 2 { *f } else { f / 2 * 3 }).collect();
  if fields.iter().any(|f| *f == 0 || (super_v == 1 && f % 2 != 0))
      || widths.iter().any(|f| f % 3 != 0) || widths.iter().sum::<usize>() != width
      || (super_v == 2 && (height % 2 != 0 || ljpeg_width == 0 || ljpeg_width % 3 != 0
          || widths.iter().any(|f| *f != widths[0]))) {
    return Err(fail());
  }
  let mut starts = vec![0usize];
  for w in &widths { starts.push(starts.last().unwrap() + w); }
  let source = |dest: usize| -> usize {
    let row = dest / width;
    let col = dest % width;
    let field = starts.partition_point(|v| *v <= col) - 1;
    let local = col - starts[field];
    if super_v == 1 {
      starts[field] * height + row * widths[field] + local
    } else {
      let linear = starts[field] * (height / 2) + (row / 2) * widths[field] + local;
      (linear / ljpeg_width) * (2 * ljpeg_width) + linear % ljpeg_width + (row % 2) * ljpeg_width
    }
  };
  // Validate the mapping as a bijection before mutating any source pixels.
  let mut visited = vec![0u8; data.len().div_ceil(8)];
  for dest in 0..data.len() {
    let src = source(dest);
    if src >= data.len() || visited[src / 8] & (1 << (src % 8)) != 0 { return Err(fail()); }
    visited[src / 8] |= 1 << (src % 8);
  }
  visited.fill(0);
  for start in 0..data.len() {
    if visited[start / 8] & (1 << (start % 8)) != 0 { continue; }
    let value = data[start];
    let mut current = start;
    loop {
      visited[current / 8] |= 1 << (current % 8);
      let next = source(current);
      if next == start { data[current] = value; break; }
      data[current] = data[next];
      current = next;
    }
  }
  Ok(())
}

struct Layout { width: usize, height: usize, jpeg_width: usize, vertical: usize, starts: Vec<usize>, widths: Vec<usize> }
impl Layout {
  fn new(width: usize, height: usize, jpeg_width: usize, fields: &[usize], vertical: usize) -> Result<Self> {
    let fail = || RawlerError::DecoderFailed("Invalid CR2 streaming slices".into());
    if width == 0 || height == 0 || jpeg_width == 0 || !matches!(vertical,1|2) || fields.is_empty() || fields.len()>4096 { return Err(fail()); }
    let widths: Vec<_> = fields.iter().map(|f| if vertical==2 {Some(*f)} else {(f/2).checked_mul(3)}).collect::<Option<Vec<_>>>().ok_or_else(fail)?;
    if fields.iter().any(|f| *f==0 || (vertical==1 && f%2!=0)) || widths.iter().any(|w| w%3!=0)
      || widths.iter().try_fold(0usize,|a,w|a.checked_add(*w)) != Some(width)
      || (vertical==2 && (height%2!=0 || jpeg_width%3!=0 || widths.iter().any(|w|*w!=widths[0]))) { return Err(fail()); }
    let size=width.checked_mul(height).ok_or_else(fail)?;
    let group=jpeg_width.checked_mul(vertical).ok_or_else(fail)?;
    if vertical==2 && size%group!=0 {return Err(fail());}
    let mut starts=vec![0]; for w in &widths {starts.push(starts.last().unwrap()+w);}
    Ok(Self {width,height,jpeg_width,vertical,starts,widths})
  }
  // Inverse of unslice_rgb_in_place's destination->source permutation.
  // The deinterleaving step is a bijection iff total samples are divisible by
  // 2*jpeg_width; caller checks this using the even JPEG height.
  fn destination(&self, source: usize) -> usize {
    let (linear,parity)=if self.vertical==2 {
      ((source/(2*self.jpeg_width))*self.jpeg_width+source%self.jpeg_width,(source/self.jpeg_width)%2)
    } else {(source,0)};
    let rows=self.height/self.vertical;
    let field=self.starts.partition_point(|s| *s*rows<=linear)-1;
    let local=linear-self.starts[field]*rows;
    let row=(local/self.widths[field])*self.vertical+parity;
    row*self.width+self.starts[field]+local%self.widths[field]
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  #[test]
  fn invalid_layouts_fail_without_allocation_by_image_size() {
    assert!(Layout::new(12,2,6,&[usize::MAX],1).is_err());
    assert!(Layout::new(12,3,6,&[12],2).is_err());
    assert!(Layout::new(18,2,6,&[6,12],2).is_err());
    assert!(Layout::new(12,2,18,&[12],2).is_err());
    assert!(Layout::new(12,2,6,&[0,8],1).is_err());
  }
  #[test]
  fn inverse_422_unequal_last_slice() {
    for fields in [vec![8,8,4],vec![4,12,8],vec![2,2,2,10]] {
      for height in [1,3,8] {
        let width=fields.iter().sum::<usize>()/2*3;
        let l=Layout::new(width,height,6,&fields,1).unwrap();
        let mut old:Vec<u16>=(0..(width*height) as u16).collect();
        unslice_rgb_in_place(&mut old,width,height,6,&fields,2,1).unwrap();
        for src in 0..old.len() {assert_eq!(old[l.destination(src)],src as u16);}
      }
    }
  }
  #[test]
  fn inverse_matches_original_permutation() {
    for vertical in [1,2] { for height in [2,4,6,12] { for count in 1..5 { for field in [6,12,18] {
      let width=field*count;
      let fields=vec![if vertical==1 {field/3*2} else {field};count];
      for jpeg_width in [6,12,18,36] {
        let size=width*height;
        if size%(jpeg_width*vertical)!=0 {continue;}
        let l=Layout::new(width,height,jpeg_width,&fields,vertical).unwrap();
        let mut old:Vec<u16>=(0..size as u16).collect();
        unslice_rgb_in_place(&mut old,width,height,jpeg_width,&fields,2,vertical).unwrap();
        for src in 0..size {assert_eq!(old[l.destination(src)],src as u16);}
      }
    } } } }
  }
}
