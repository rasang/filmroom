//! Filmroom: single still CR3 raw track and explicit bounded CRX segments.
use super::*;
use crate::decoders::BoundedBayerPlan;
use crate::decompressors::crx::{bounded_crx_plan, decompress_crx_segments};
fn raw_track<'a>(
  d: &'a Cr3Decoder<'_>,
  file: &'a RawSource,
) -> Result<(usize, &'a Cmp1Box, &'a [u8])> {
  if rawler_crx_raw_trak().is_some() {
    return Err("Environment raw-track override is not admitted".into());
  }
  let desc = d
    .bmff
    .filebox
    .moov
    .cr3desc
    .as_ref()
    .ok_or("Missing CR3 descriptor")?;
  let mains: Vec<_> = desc
    .cctp
    .ccdts
    .iter()
    .filter(|r| r.image_type == Cr3ImageType::CrxBix as u64)
    .collect();
  if mains.len() != 1 || mains[0].trak_index == 0 {
    return Err("Ambiguous CR3 main raw track".into());
  }
  let id = (mains[0].trak_index - 1) as usize;
  let mdat = &d.bmff.filebox.mdat.header;
  let mdat_begin = mdat.offset + mdat.header_len;
  let mdat_end = mdat.offset + mdat.size;
  let mut ctmd_tracks = 0;
  for track in &d.bmff.filebox.moov.traks {
    let t = &track.mdia.minf.stbl;
    // Do not silently use the first sample of a burst/movie.
    if t.co64.as_ref().is_none_or(|v| v.entries.len() != 1)
      || t.stts.entries.len() != 1
      || t.stts.entries[0].sample_count != 1
      || t.stts.entries[0].sample_delta == 0
    {
      return Err("CR3 inconsistent still sample tables".into());
    }
    if t.stsz.sample_count != 1
      || t.stsc.entries.len() != 1
      || t.stsc.entries[0].first_chunk != 1
      || t.stsc.entries[0].samples_per_chunk != 1
      || t.stsc.entries[0].sample_description_index != 1
    {
      return Err("Multi-sample CR3 layout unsupported".into());
    }
    let (offset, size) = t.get_sample_offset(1).ok_or("Missing CR3 sample")?;
    if size == 0
      || (offset as u64) < mdat_begin
      || (offset as u64)
        .checked_add(size as u64)
        .is_none_or(|n| n > mdat_end)
    {
      return Err("CR3 sample range".into());
    }
    if t.stsd.ctmd.is_some() {
      ctmd_tracks += 1;
      if size > 1024 * 1024 {
        return Err("CR3 CTMD budget".into());
      }
      validate_ctmd(file.subview(offset as u64, size as u64)?)?;
    }
  }
  if ctmd_tracks != 1 {
    return Err("CR3 ambiguous metadata track".into());
  }
  if let Some(x) = d.bmff.filebox.cr3xpacket.as_ref() {
    if x.header.size - x.header.header_len > 1024 * 1024 {
      return Err("CR3 XMP budget".into());
    }
  }
  let track = d.moov_trak(id).ok_or("Missing main CR3 track")?;
  let cmp = d.cmp1_box(id).ok_or("Missing CRX parameters")?;
  if !matches!(SelfMode::from(d)?, SelfMode::Raw | SelfMode::Craw) {
    return Err("CRM unsupported".into());
  }
  if d.iad1_box(id).is_none() {
    return Err("Missing CR3 sensor crop".into());
  }
  let (offset, size) = track
    .mdia
    .minf
    .stbl
    .get_sample_offset(1)
    .ok_or("Missing raw sample")?;
  Ok((id, cmp, file.subview(offset as u64, size as u64)?))
}
enum SelfMode {
  Raw,
  Craw,
  Other,
}
impl SelfMode {
  fn from(d: &Cr3Decoder<'_>) -> Result<Self> {
    Ok(match Cr3Decoder::get_mode(d.cmt3.root_ifd())? {
      "raw" => Self::Raw,
      "craw" => Self::Craw,
      _ => Self::Other,
    })
  }
}
fn validate_ctmd(b: &[u8]) -> Result<()> {
  let mut at = 0;
  let mut records = std::collections::BTreeSet::new();
  while at < b.len() {
    if b.len() - at < 12 {
      return Err("Truncated CTMD record".into());
    }
    let size = u32::from_le_bytes(b[at..at + 4].try_into().unwrap()) as usize;
    let kind = u16::from_le_bytes(b[at + 4..at + 6].try_into().unwrap());
    if size < 12 || size > b.len() - at || !records.insert(kind) || records.len() > 64 {
      return Err("Invalid CTMD record".into());
    }
    if (7..=12).contains(&kind) {
      let mut p = at + 12;
      let mut count = 0;
      while p < at + size {
        if at + size - p < 8 {
          return Err("Truncated CTMD block".into());
        }
        let n = u32::from_le_bytes(b[p..p + 4].try_into().unwrap()) as usize;
        if n < 8 || n > at + size - p || count >= 64 {
          return Err("Invalid CTMD block".into());
        }
        p += n;
        count += 1;
      }
    }
    at += size;
  }
  Ok(())
}
pub(super) fn plan(d: &Cr3Decoder<'_>, file: &RawSource) -> Result<Option<BoundedBayerPlan>> {
  let (id, cmp, buf) = raw_track(d, file)?;
  let scratch_bytes = bounded_crx_plan(buf, cmp).map_err(|e| e.to_string())?;
  let md = d.read_cr3_metadata(file, &Default::default())?;
  if md.wb.is_none() || md.blacklevels.is_none() || md.whitelevel.is_none() {
    return Err("Missing CR3 color metadata".into());
  }
  let orientation = d
    .cmt1
    .root_ifd()
    .get_entry(TiffCommonTag::Orientation)
    .map(|v| v.force_u32(0))
    .unwrap_or(1);
  if !(1..=8).contains(&orientation) {
    return Err("Invalid CR3 orientation".into());
  }
  let active_area_override = match &d.iad1_box(id).ok_or("Missing CR3 geometry")?.iad1_type {
    Iad1Type::Small(_) => None,
    Iad1Type::Big(big) => {
      let crop = [
        big.crop_left_offset as usize,
        big.crop_top_offset as usize,
        big.crop_right_offset as usize + 1,
        big.crop_bottom_offset as usize + 1,
      ];
      let active = [
        big.active_area_left_offset as usize,
        big.active_area_top_offset as usize,
        (big.active_area_right_offset as usize)
          .checked_sub(1)
          .ok_or("CR3 active right")?
          .min(cmp.f_width as usize),
        (big.active_area_bottom_offset as usize)
          .checked_sub(1)
          .ok_or("CR3 active bottom")?
          .min(cmp.f_height as usize),
      ];
      let sensor = d
        .cmt3
        .root_ifd()
        .get_entry(0x00e0)
        .and_then(|v| match &v.value {
          Value::Short(v) => Some(v.as_slice()),
          _ => None,
        });
      reconcile_active(
        cmp.f_width as usize,
        cmp.f_height as usize,
        crop,
        active,
        sensor,
      )?
    }
  };
  Ok(Some(BoundedBayerPlan {
    format: "CR3",
    variant: if cmp.image_levels == 0 {
      "CR3_RAW_BAYER"
    } else {
      "CR3_CRAW_BAYER"
    },
    width: cmp.f_width as usize,
    height: cmp.f_height as usize,
    bits: cmp.n_bits as usize,
    scratch_bytes,
    active_area_override,
    orientation: orientation as u8,
  }))
}
pub(super) fn segments(
  d: &Cr3Decoder<'_>,
  file: &RawSource,
  sink: &mut dyn FnMut(usize, usize, &[u16]) -> Result<()>,
  cancel: &dyn Fn() -> bool,
) -> Result<()> {
  let (_, cmp, buf) = raw_track(d, file)?;
  decompress_crx_segments(buf, cmp, sink, cancel).map_err(|e| e.to_string().into())
}

// Some crop modes contain a stale IAD1 active rectangle. Do not guess an offset
// or widen it into unverified pixels. The exact IAD1 crop is usable only when
// independently repeated by typed Canon SensorInfo and inside the encoded sensor.
fn reconcile_active(
  w: usize,
  h: usize,
  c: [usize; 4],
  a: [usize; 4],
  s: Option<&[u16]>,
) -> Result<Option<[usize; 4]>> {
  let valid = |r: [usize; 4]| r[0] < r[2] && r[1] < r[3] && r[2] <= w && r[3] <= h;
  if !valid(c) || !valid(a) {
    return Err("CR3 geometry bounds".into());
  }
  if c[0] >= a[0] && c[1] >= a[1] && c[2] <= a[2] && c[3] <= a[3] {
    return Ok(None);
  }
  let s = s.ok_or("Contradictory CR3 geometry")?;
  if s.len() < 9
    || s[1] as usize != w
    || s[2] as usize != h
    || [
      s[5] as usize,
      s[6] as usize,
      s[7] as usize + 1,
      s[8] as usize + 1,
    ] != c
  {
    return Err("Contradictory CR3 geometry".into());
  }
  Ok(Some([c[0], c[1], c[2] - c[0], c[3] - c[1]]))
}
#[cfg(test)]
mod tests {
  use super::*;
  #[test]
  fn contradictory_crop_requires_independent_exact_sensorinfo() {
    let c = [272, 172, 5360, 3564];
    let a = [132, 160, 5242, 3566];
    let s = [34, 5376, 3574, 1, 1, 272, 172, 5359, 3563];
    assert_eq!(
      reconcile_active(5376, 3574, c, a, Some(&s)).unwrap(),
      Some([272, 172, 5088, 3392])
    );
    assert!(reconcile_active(5376, 3574, c, a, None).is_err());
    let mut wrong = s;
    wrong[5] += 2;
    assert!(reconcile_active(5376, 3574, c, a, Some(&wrong)).is_err());
    assert!(reconcile_active(5000, 3574, c, a, Some(&s)).is_err());
    assert_eq!(
      reconcile_active(5376, 3574, c, [0, 0, 5376, 3574], None).unwrap(),
      None
    );
  }
}
