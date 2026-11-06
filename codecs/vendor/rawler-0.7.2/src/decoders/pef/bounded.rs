//! Filmroom bounded PEF Huffman/unpacked row decoder.
use super::*;
use crate::decoders::BoundedBayerPlan;
fn decode_rows(
  src: &[u8],
  huff: Option<(&[u8], Endian)>,
  width: usize,
  height: usize,
  bits: usize,
  sink: &mut dyn FnMut(usize, usize, &[u16]) -> Result<()>,
  cancel: &dyn Fn() -> bool,
) -> Result<()> {
  let mut out = vec![0u16; width];
  let mut htable = HuffTable::empty();

  /* Attempt to read huffman table, if found in makernote */
  if let Some((huff, endian)) = huff {
    debug!("Use in-file Huffman table");
    if huff.len() < 14 {
      return Err("Truncated PEF Huffman table".into());
    }
    let mut stream = ByteStream::new(huff, endian);

    let depth: usize = (stream.get_u16() as usize + 12) & 0xf;
    if depth == 0 || huff.len() < 14 + depth * 3 {
      return Err("Invalid PEF Huffman depth".into());
    }
    stream.consume_bytes(12);

    let mut v0: [u32; 16] = [0; 16];
    for i in 0..depth {
      v0[i] = stream.get_u16() as u32;
    }

    let mut v1: [u32; 16] = [0; 16];
    for i in 0..depth {
      v1[i] = stream.get_u8() as u32;
      if v1[i] == 0 || v1[i] > 12 {
        return Err("Invalid PEF Huffman length".into());
      }
    }

    // Calculate codes and store bitcounts
    let mut v2: [u32; 16] = [0; 16];
    for c in 0..depth {
      v2[c] = v0[c] >> (12 - v1[c]);
      htable.bits[v1[c] as usize] += 1;
    }

    // Find smallest
    for i in 0..depth {
      let mut sm_val: u32 = 0xfffffff;
      let mut sm_num: u32 = 0xff;
      for j in 0..depth {
        if v2[j] <= sm_val {
          sm_num = j as u32;
          sm_val = v2[j];
        }
      }
      htable.huffval[i] = sm_num;
      v2[sm_num as usize] = 0xffffffff;
    }
  } else {
    debug!("Fallback to standard Huffman table");
    // Initialize with legacy data
    let pentax_tree: [u8; 29] = [
      0, 2, 3, 1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 3, 4, 2, 5, 1, 6, 0, 7, 8, 9, 10, 11, 12,
    ];
    let mut acc: usize = 0;
    for i in 0..16 {
      htable.bits[i + 1] = pentax_tree[i] as u32;
      acc += htable.bits[i + 1] as usize;
    }
    for i in 0..acc {
      htable.huffval[i] = pentax_tree[i + 16] as u32;
    }
  }

  htable.initialize()?;

  let mut pump = crate::decoders::bounded_bits::StrictMsb::new(src);
  let mut pred_up1: [i32; 2] = [0, 0];
  let mut pred_up2: [i32; 2] = [0, 0];
  let mut pred_left1: i32;
  let mut pred_left2: i32;

  for row in 0..height {
    if cancel() {
      return Err("Decode cancelled".into());
    }
    pred_up1[row & 1] = pred_up1[row & 1]
      .checked_add(htable.huff_decode(&mut pump)?)
      .ok_or("PEF predictor overflow")?;
    pred_up2[row & 1] = pred_up2[row & 1]
      .checked_add(htable.huff_decode(&mut pump)?)
      .ok_or("PEF predictor overflow")?;
    pred_left1 = pred_up1[row & 1];
    pred_left2 = pred_up2[row & 1];
    if pred_left1 < 0 || pred_left2 < 0 || pred_left1 >= (1 << bits) || pred_left2 >= (1 << bits) {
      return Err("PEF predictor range".into());
    }
    out[0] = pred_left1 as u16;
    out[1] = pred_left2 as u16;
    for col in (2..width).step_by(2) {
      pred_left1 = pred_left1
        .checked_add(htable.huff_decode(&mut pump)?)
        .ok_or("PEF predictor overflow")?;
      pred_left2 = pred_left2
        .checked_add(htable.huff_decode(&mut pump)?)
        .ok_or("PEF predictor overflow")?;
      if pred_left1 < 0 || pred_left2 < 0 || pred_left1 >= (1 << bits) || pred_left2 >= (1 << bits)
      {
        return Err("PEF predictor range".into());
      }
      out[col + 0] = pred_left1 as u16;
      out[col + 1] = pred_left2 as u16;
    }
    if pump.exhausted() {
      return Err("Truncated PEF entropy".into());
    }
    sink(0, row, &out)?;
  }
  Ok(())
}
fn scalar(ifd: &IFD, tag: TiffCommonTag) -> Result<u32> {
  let e = ifd.get_entry(tag).ok_or("Missing PEF tag")?;
  if e.count() != 1 {
    return Err("Invalid PEF scalar".into());
  }
  e.get_u32(0)
    .map_err(|_| RawlerError::from("Invalid PEF scalar type"))?
    .ok_or_else(|| "Invalid PEF value".into())
}
fn layout<'a>(
  d: &'a PefDecoder<'_>,
  file: &'a RawSource,
) -> Result<(&'a IFD, usize, usize, usize, u32, &'a [u8])> {
  let raws = d.tiff.find_ifds_with_tag(TiffCommonTag::StripOffsets);
  if raws.len() != 1 {
    return Err("PEF multi-frame pixel shift/HDR requires explicit merge".into());
  }
  let raw = raws[0];
  let w = scalar(raw, TiffCommonTag::ImageWidth)? as usize;
  let h = scalar(raw, TiffCommonTag::ImageLength)? as usize;
  let bits = scalar(raw, TiffCommonTag::BitsPerSample)? as usize;
  crate::check_bounded_raw_size(w, h)?;
  if w < 8
    || h < 8
    || w % 2 != 0
    || h % 2 != 0
    || !matches!(bits, 12 | 14 | 16)
  {
    return Err("PEF sensor dimensions/precision".into());
  }
  if scalar(raw, TiffCommonTag::PhotometricInt)? != 32803
    || scalar(raw, TiffCommonTag::SamplesPerPixel)? != 1
  {
    return Err("PEF non-Bayer sensor requires its own developer".into());
  }
  if scalar(raw, TiffCommonTag::RowsPerStrip)? as usize != h {
    return Err("PEF multi-strip layout".into());
  }
  let compression = scalar(raw, TiffCommonTag::Compression)?;
  if !matches!(compression, 1 | 32773 | 65535) {
    return Err("Unsupported PEF compression".into());
  }
  let offset = scalar(raw, TiffCommonTag::StripOffsets)? as u64;
  let bytes = scalar(raw, TiffCommonTag::StripByteCounts)? as u64;
  if offset < 8 || bytes == 0 {
    return Err("Invalid PEF strip".into());
  }
  let src = file.subview(offset, bytes)?;
  if (compression == 1 && bytes != w as u64 * h as u64 * 2)
    || (compression == 32773 && (bits != 12 || bytes != w as u64 * h as u64 * 3 / 2))
  {
    return Err("PEF packed strip size".into());
  }
  Ok((raw, w, h, bits, compression, src))
}
pub(super) fn plan(d: &PefDecoder<'_>, file: &RawSource) -> Result<Option<BoundedBayerPlan>> {
  let (_, width, height, bits, compression, _) = layout(d, file)?;
  let orientation = d
    .tiff
    .root_ifd()
    .get_entry(TiffCommonTag::Orientation)
    .map(|v| v.force_u32(0))
    .unwrap_or(1);
  if !(1..=8).contains(&orientation) {
    return Err("Invalid PEF orientation".into());
  }
  Ok(Some(BoundedBayerPlan {
    format: "PEF",
    variant: match compression {
      1 => "PEF_UNPACKED_BAYER",
      32773 => "PEF_PACKED_BAYER",
      _ => "PEF_HUFFMAN_BAYER",
    },
    width,
    height,
    bits,
    scratch_bytes: width as u64 * 8 + 1024 * 1024,
    active_area_override: None,
    orientation: orientation as u8,
  }))
}
pub(super) fn segments(
  d: &PefDecoder<'_>,
  file: &RawSource,
  sink: &mut dyn FnMut(usize, usize, &[u16]) -> Result<()>,
  cancel: &dyn Fn() -> bool,
) -> Result<()> {
  let (_, w, h, bits, compression, src) = layout(d, file)?;
  if compression == 65535 {
    let huff = match d.makernote.get_entry(PefMakernote::HuffmanTable) {
      Some(e) => match &e.value {
        Value::Undefined(b) => Some((b.as_slice(), d.tiff.get_endian())),
        _ => return Err("Invalid PEF Huffman type".into()),
      },
      None => None,
    };
    return decode_rows(src, huff, w, h, bits, sink, cancel);
  }
  let mut row = vec![0u16; w];
  for y in 0..h {
    if cancel() {
      return Err("Decode cancelled".into());
    }
    if compression == 1 {
      for (x, b) in src[y * w * 2..(y + 1) * w * 2].chunks_exact(2).enumerate() {
        row[x] = u16::from_be_bytes([b[0], b[1]]);
      }
    } else {
      for (x, b) in src[y * w * 3 / 2..(y + 1) * w * 3 / 2]
        .chunks_exact(3)
        .enumerate()
      {
        row[2 * x] = ((b[0] as u16) << 4) | ((b[1] as u16) >> 4);
        row[2 * x + 1] = ((b[1] as u16 & 15) << 8) | (b[2] as u16);
      }
    }
    if row.iter().any(|v| (*v as u32) >= (1u32 << bits)) {
      return Err("PEF sample range".into());
    }
    sink(0, y, &row)?;
  }
  Ok(())
}
