// SPDX-License-Identifier: LGPL-2.1
//! Canon Bayer spool path: original prediction, reshape, unslice, black statistics.
use super::*;
use super::bayer_layout::BayerLayout;

impl Cr2Decoder<'_> {
 pub(super) fn stream_bayer(&self,file:&RawSource,storage:&mut dyn CanonSrawStorage)->Result<RawImage> {
  let mut metadata=self.raw_image(file,&RawDecodeParams::default(),true)?;
  if metadata.cpp!=1 {return Err("Not a Canon Bayer image".into());}
  let raw=self.tiff.find_first_ifd(TiffCommonTag::Cr2Id).ok_or("CR2 missing raw IFD")?;
  if metadata.source_ifd_offset!=Some(raw.offset) {return Err("CR2 source IFD mismatch".into());}
  let offset=fetch_tiff_tag!(raw,TiffCommonTag::StripOffsets).force_usize(0);
  let src=file.subview_until_eof(offset as u64)?;let jpeg=LjpegDecompressor::new(src)?;
  let width=metadata.width;let height=metadata.height;
  if jpeg.super_h()!=1 || jpeg.super_v()!=1 || width.checked_mul(height)!=jpeg.width().checked_mul(jpeg.height()) {
   return Err("CR2 Bayer streaming dimensions mismatch".into());
  }
  let layout=if let Some(stripe)=raw.get_entry(TiffCommonTag::Cr2StripeWidths) {
   let count=stripe.value.force_usize(0);if count>4095{return Err("Too many CR2 Bayer slices".into());}
   if count==0 {None} else {
    let mut fields=vec![stripe.value.force_usize(1);count];fields.push(stripe.value.force_usize(2));
    Some(BayerLayout::new(width,height,&fields)?)
   }
  } else {None};
  // Original linearization happens in decoded JPEG order BEFORE unslicing.
  // A separate row copy leaves predictor history untouched. RNG carries across
  // callbacks, initialized from the very first undecorated sample exactly once.
  let linearization=if self.camera.find_hint("linearization") {
   let linearization=fetch_tiff_tag!(raw,TiffCommonTag::GrayResponse);let mut table=[0u16;4096];
   for (i,p) in table.iter_mut().enumerate(){*p=linearization.force_u16(i);}
   Some(LookupTable::new(&table))
  } else {None};
  let mut random=None;let mut row_copy=Vec::new();
  jpeg.decode_raster_rows(|row,pixels| {
   let pixels=if let Some(table)=&linearization {
    super::bayer_linearization::linearize_row(table,&mut random,pixels,&mut row_copy);
    row_copy.as_slice()
   } else {pixels};
   let first=row*jpeg.width();
   if let Some(layout)=&layout {
    let mut start=0;while start<pixels.len() {
     let dest=layout.destination(first+start);let mut end=start+1;
     while end<pixels.len() && layout.destination(first+end)==dest+end-start {end+=1;}
     storage.write(dest,&pixels[start..end]).map_err(|e|e.to_string())?;start=end;
    }
   } else {storage.write(first,pixels).map_err(|e|e.to_string())?;}
   Ok(())
  })?;
  // Dummy metadata uses a placeholder when full decode calculates masked black.
  // Preserve RawImage::calc_black_levels' exact area,row,col f32 addition order.
  if self.camera.make_blacklevel(1).is_none()
   && (self.camera.find_hint("invalid_blacklevel") || self.get_blacklevel(&self.camera,1)?.is_none()) {
   if metadata.blackareas.is_empty() {metadata.blacklevel=BlackLevel::zero(1,1,1);} else {
    let cfa=&self.camera.cfa;
    let areas:Vec<_>=metadata.blackareas.iter().map(|a|(a.p.x,a.p.y,a.d.w,a.d.h)).collect();
    let levels=super::bayer_black::masked_black_levels(width,height,cfa.width,cfa.height,&areas,
      |at,row|storage.read(at,row).map_err(|e|e.to_string()))?;
    metadata.blacklevel=BlackLevel::new(&levels,2,2,1);
   }
  }
  Ok(metadata)
 }
}
