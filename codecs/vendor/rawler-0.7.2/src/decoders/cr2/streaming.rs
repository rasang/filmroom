// SPDX-License-Identifier: LGPL-2.1
//! Bounded storage extension; original interpolation/conversion remain the oracle.
use super::*;

/// Storage is sample-addressed u16, not bytes. Implementations must enforce the
/// pre-admitted total length and check cancellation on every read/write call.
pub trait CanonSrawStorage {
  fn write(&mut self, sample: usize, values: &[u16]) -> Result<()>;
  fn read(&mut self, sample: usize, values: &mut [u16]) -> Result<()>;
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

impl Cr2Decoder<'_> {
  pub(super) fn stream_sraw(&self, file: &RawSource, storage: &mut dyn CanonSrawStorage) -> Result<RawImage> {
    let metadata=self.raw_image(file,&RawDecodeParams::default(),true)?;
    if metadata.cpp!=3 || self.camera.find_hint("linearization") {return Err("Not supported by Canon RGB streaming".into());}
    let raw=self.tiff.find_first_ifd(TiffCommonTag::Cr2Id).ok_or("CR2 missing raw IFD")?;
    if metadata.source_ifd_offset!=Some(raw.offset) {return Err("CR2 source IFD mismatch".into());}
    let offset=fetch_tiff_tag!(raw,TiffCommonTag::StripOffsets).force_usize(0);
    let src=file.subview_until_eof(offset as u64)?;
    let jpeg=LjpegDecompressor::new(src)?;
    let width=metadata.width.checked_mul(3).ok_or("CR2 width overflow")?;
    let height=metadata.height;
    if width.checked_mul(height)!=jpeg.width().checked_mul(jpeg.height()) || jpeg.components()!=3 || jpeg.super_h()!=2 || !matches!(jpeg.super_v(),1|2) {
      return Err("CR2 streaming dimensions mismatch".into());
    }
    let stripe=raw.get_entry(TiffCommonTag::Cr2StripeWidths);
    let layout=if let Some(stripe)=stripe {
      let count=stripe.value.force_usize(0);
      if count>4095 {return Err("Too many CR2 slices".into());}
      if count==0 {None} else {
        let mut fields=vec![stripe.value.force_usize(1);count]; fields.push(stripe.value.force_usize(2));
        Some(Layout::new(width,height,jpeg.width(),&fields,jpeg.super_v())?)
      }
    } else {None};
    jpeg.decode_canon_rows(|row,pixels| {
      let first=row*jpeg.width();
      if let Some(layout)=&layout {
        // Coalesce adjacent samples into positional writes. Boundaries are only
        // slice rows / MCU raster rows, never one syscall per individual sample.
        let mut start=0;
        while start<pixels.len() {
          let dest=layout.destination(first+start);
          let mut end=start+1;
          while end<pixels.len() && layout.destination(first+end)==dest+end-start {end+=1;}
          storage.write(dest,&pixels[start..end]).map_err(|e|e.to_string())?;
          start=end;
        }
      } else {storage.write(first,pixels).map_err(|e|e.to_string())?;}
      Ok(())
    })?;
    // Missing stripe tag retains the original decoder's no-conversion behavior.
    if stripe.is_some() {
      let mut pixels=vec![0u16;width*3];
      if jpeg.super_v()==2 && sraw_interpolation_enabled() {
        // Read 3 YCbCr rows, emit only 2; the next observed even-row anchor is
        // never overwritten with RGB before the following window consumes it.
        for row in (0..height).step_by(2) {
          let rows=3.min(height-row);
          let p=&mut pixels[..width*rows];
          storage.read(row*width,p)?;
          let emitted=chroma::interpolate_420_window(width,p);
          let p=&mut p[..width*emitted];
          self.convert_to_rgb_inner(file,&self.camera,&jpeg,width,emitted,p,false,false)?;
          storage.write(row*width,p)?;
        }
      } else {
        // 4:2:2 or explicit no-interpolation keeps the original arithmetic.
        for row in (0..height).step_by(3) {
          let rows=3.min(height-row);
          let p=&mut pixels[..width*rows];
          storage.read(row*width,p)?;
          self.convert_to_rgb(file,&self.camera,&jpeg,width,rows,p,false)?;
          storage.write(row*width,p)?;
        }
      }
    }
    Ok(metadata)
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
