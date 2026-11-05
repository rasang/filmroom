//! Filmroom bounded Olympus/OM System sensor rows.
use super::*;
use crate::decoders::BoundedBayerPlan;
fn decode_rows(
  buf: &[u8],
  width: usize,
  height: usize,
  bps: usize,
  sink: &mut dyn FnMut(usize, usize, &[u16]) -> Result<()>,
  cancel: &dyn Fn() -> bool,
) -> Result<()> {
  let mut out = vec![0u16; width * 2];

  /* Build a table to quickly look up "high" value */
  let mut bittable: [u8; 4096] = [0; 4096];
  for i in 0..4096 {
    let mut b = 12;
    for high in 0..12 {
      if ((i >> (11 - high)) & 1) != 0 {
        b = high;
        break;
      }
    }
    bittable[i] = b;
  }

  let mut left: [i32; 2] = [0; 2];
  let mut nw: [i32; 2] = [0; 2];
  let skip = if bps == 14 { 8 } else { 7 };
  if buf.len() < skip {
    return Err("Truncated ORF header".into());
  }
  let mut pump = crate::decoders::bounded_bits::StrictMsb::new(&buf[skip..]);

  for row in 0..height {
    if cancel() {
      return Err("Decode cancelled".into());
    }
    let mut acarry: [[i32; 3]; 2] = [[0; 3]; 2];

    for c in 0..width / 2 {
      let col: usize = c * 2;
      for s in 0..2 {
        // Run twice for odd and even pixels
        let i = if acarry[s][2] < 3 { 2 } else { 0 };
        let mut nbits = 2 + i;
        while ((acarry[s][0] >> (nbits + i)) & 0xffff) > 0 {
          nbits += 1
        }
        nbits = cmp::min(nbits, 16);
        let b = pump.peek_ibits(15);

        let sign: i32 = -(b >> 14);
        let low: i32 = (b >> 12) & 3;
        let mut high: i32 = bittable[(b & 4095) as usize] as i32;

        // Skip bytes used above or read bits
        if high == 12 {
          pump.consume_bits(15);
          high = pump.get_ibits(16 - nbits) >> 1;
        } else {
          pump.consume_bits((high + 4) as u32);
        }

        acarry[s][0] = ((high << nbits) | pump.get_ibits(nbits)) as i32;
        let diff = (acarry[s][0] ^ sign) + acarry[s][1];
        acarry[s][1] = (diff * 3 + acarry[s][1]) >> 5;
        acarry[s][2] = if acarry[s][0] > 16 {
          0
        } else {
          acarry[s][2] + 1
        };

        if row < 2 || col < 2 {
          // We're in a border, special care is needed
          let pred = if row < 2 && col < 2 {
            // We're in the top left corner
            0
          } else if row < 2 {
            // We're going along the top border
            left[s]
          } else {
            // col < 2, we're at the start of a line
            nw[s] = out[(row % 2) * width + (col + s)] as i32;
            nw[s]
          };
          left[s] = pred + ((diff << 2) | low);
          if left[s] < 0 || left[s] >= (1 << bps) {
            return Err("ORF predictor range".into());
          }
          out[(row % 2) * width + (col + s)] = left[s] as u16;
        } else {
          let up: i32 = out[(row % 2) * width + (col + s)] as i32;
          let left_minus_nw: i32 = left[s] - nw[s];
          let up_minus_nw: i32 = up - nw[s];
          // Check if sign is different, and one is not zero
          let pred = if left_minus_nw * up_minus_nw < 0 {
            if left_minus_nw.abs() > 32 || up_minus_nw.abs() > 32 {
              left[s] + up_minus_nw
            } else {
              (left[s] + up) >> 1
            }
          } else if left_minus_nw.abs() > up_minus_nw.abs() {
            left[s]
          } else {
            up
          };

          left[s] = pred + ((diff << 2) | low);
          nw[s] = up;
          if left[s] < 0 || left[s] >= (1 << bps) {
            return Err("ORF predictor range".into());
          }
          out[(row % 2) * width + (col + s)] = left[s] as u16;
        }
      }
    }
    if pump.exhausted() {
      return Err("Truncated ORF entropy".into());
    }
    sink(0, row, &out[(row % 2) * width..(row % 2 + 1) * width])?;
  }
  Ok(())
}

#[derive(Clone, Copy)]
enum Packing {
  Compressed,
  Unpacked,
  Control,
  Msb32,
  Interlaced,
}
fn scalar(i: &IFD, t: TiffCommonTag) -> Result<usize> {
  let e = i.get_entry(t).ok_or("Missing ORF tag")?;
  if e.count() != 1 {
    return Err("Invalid ORF scalar".into());
  }
  Ok(e.force_usize(0))
}
fn layout<'a>(
  d: &OrfDecoder<'_>,
  file: &'a RawSource,
) -> Result<(usize, usize, usize, Packing, &'a [u8])> {
  let raws = d.tiff.find_ifds_with_tag(TiffCommonTag::StripOffsets);
  if raws.len() != 1 {
    return Err("ORF ambiguous sensor IFD".into());
  }
  let raw = raws[0];
  let w = scalar(raw, TiffCommonTag::ImageWidth)?;
  let h = scalar(raw, TiffCommonTag::ImageLength)?;
  let bits = d.get_bits_per_pixel()?.unwrap_or(12) as usize;
  if w < 8
    || h < 8
    || w % 2 != 0
    || h % 2 != 0
    || w > 32768
    || h > 32768
    || w.checked_mul(h).is_none_or(|n| n > 52_000_000)
    || !matches!(bits, 12 | 14)
  {
    return Err("ORF dimensions/precision".into());
  }
  if scalar(raw, TiffCommonTag::SamplesPerPixel)? != 1
    || scalar(raw, TiffCommonTag::Compression)? != 1
  {
    return Err("Unsupported ORF layout".into());
  }
  let offsets = raw
    .get_entry(TiffCommonTag::StripOffsets)
    .ok_or("ORF offsets missing")?;
  let counts = raw
    .get_entry(TiffCommonTag::StripByteCounts)
    .ok_or("ORF counts missing")?;
  if offsets.count() == 0 || offsets.count() > 4096 || offsets.count() != counts.count() {
    return Err("ORF strip count".into());
  }
  let start = offsets.force_u32(0) as u64;
  let mut next = start;
  let mut size = 0u64;
  for i in 0..offsets.count() as usize {
    let off = offsets.force_u32(i) as u64;
    let n = counts.force_u32(i) as u64;
    if off != next || n == 0 {
      return Err("ORF strips must be contiguous".into());
    }
    next = off.checked_add(n).ok_or("ORF offset overflow")?;
    size = size.checked_add(n).ok_or("ORF size overflow")?;
  }
  let src = file.subview(start, size)?;
  let pixels = w * h;
  let bytes = size as usize;
  let packing = if bytes >= pixels * 2 {
    if bits != 12 || bytes != pixels * 2 {
      return Err("ORF unpacked size".into());
    }
    Packing::Unpacked
  } else if bytes >= pixels / 10 * 16 {
    if bits != 12 || w % 10 != 0 || bytes != (w * 3 / 2 + (w + 2) / 10) * h {
      return Err("ORF control layout".into());
    }
    Packing::Control
  } else if bytes >= pixels * 3 / 2 {
    if bits != 12 {
      return Err("ORF packed precision".into());
    }
    if d.camera.find_hint("interlaced") {
      let second = ((((h + 1) / 2 * w * 3 / 2) >> 11) + 1) << 11;
      if bytes < second + (h / 2) * w * 3 / 2 {
        return Err("Truncated ORF interlaced fields".into());
      }
      Packing::Interlaced
    } else {
      if w % 8 != 0 || bytes != pixels * 3 / 2 {
        return Err("ORF msb32 layout".into());
      }
      Packing::Msb32
    }
  } else {
    if offsets.count() != 1 || bytes < 16 {
      return Err("ORF compressed layout".into());
    }
    Packing::Compressed
  };
  Ok((w, h, bits, packing, src))
}
pub(super) fn plan(d: &OrfDecoder<'_>, file: &RawSource) -> Result<Option<BoundedBayerPlan>> {
  let (width, height, bits, packing, _) = layout(d, file)?;
  let orientation = d
    .tiff
    .root_ifd()
    .get_entry(TiffCommonTag::Orientation)
    .map(|v| v.force_u32(0))
    .unwrap_or(1);
  if !(1..=8).contains(&orientation) {
    return Err("Invalid ORF orientation".into());
  }
  Ok(Some(BoundedBayerPlan {
    format: "ORF",
    variant: match packing {
      Packing::Compressed => "ORF_COMPRESSED_BAYER",
      Packing::Unpacked => "ORF_UNPACKED_BAYER",
      Packing::Control => "ORF_CONTROL_BAYER",
      Packing::Msb32 => "ORF_PACKED_BAYER",
      Packing::Interlaced => "ORF_INTERLACED_BAYER",
    },
    width,
    height,
    bits,
    scratch_bytes: width as u64 * 16 + 1024 * 1024,
    active_area_override: None,
    orientation: orientation as u8,
  }))
}
pub(super) fn segments(
  d: &OrfDecoder<'_>,
  file: &RawSource,
  sink: &mut dyn FnMut(usize, usize, &[u16]) -> Result<()>,
  cancel: &dyn Fn() -> bool,
) -> Result<()> {
  let (w, h, bits, packing, src) = layout(d, file)?;
  if matches!(packing, Packing::Compressed) {
    return decode_rows(src, w, h, bits, sink, cancel);
  }
  let mut row = vec![0u16; w];
  for y in 0..h {
    if cancel() {
      return Err("Decode cancelled".into());
    }
    match packing {
      Packing::Unpacked => {
        for (x, b) in src[y * w * 2..(y + 1) * w * 2].chunks_exact(2).enumerate() {
          row[x] = if d.tiff.little_endian() {
            u16::from_le_bytes([b[0], b[1]]) >> 4
          } else {
            u16::from_be_bytes([b[0], b[1]]) >> 4
          };
        }
      }
      Packing::Control => {
        let stride = w * 3 / 2 + (w + 2) / 10;
        for (oc, ic) in row
          .chunks_exact_mut(10)
          .zip(src[y * stride..(y + 1) * stride].chunks_exact(16))
        {
          for (o, b) in oc.chunks_exact_mut(2).zip(ic.chunks_exact(3)) {
            o[0] = ((b[1] as u16 & 15) << 8) | b[0] as u16;
            o[1] = (b[2] as u16) << 4 | (b[1] as u16 >> 4);
          }
        }
      }
      Packing::Msb32 => {
        for (o, b) in row
          .chunks_exact_mut(8)
          .zip(src[y * w * 3 / 2..(y + 1) * w * 3 / 2].chunks_exact(12))
        {
          let mut r = [0u8; 12];
          for (g, v) in b.chunks_exact(4).zip(r.chunks_exact_mut(4)) {
            v.copy_from_slice(&[g[3], g[2], g[1], g[0]]);
          }
          for (p, v) in o.chunks_exact_mut(2).zip(r.chunks_exact(3)) {
            p[0] = (v[0] as u16) << 4 | (v[1] as u16 >> 4);
            p[1] = (v[1] as u16 & 15) << 8 | v[2] as u16;
          }
        }
      }
      Packing::Interlaced => {
        let second = ((((h + 1) / 2 * w * 3 / 2) >> 11) + 1) << 11;
        let at = if y % 2 == 0 {
          y / 2 * w * 3 / 2
        } else {
          second + y / 2 * w * 3 / 2
        };
        for (o, b) in row
          .chunks_exact_mut(2)
          .zip(src[at..at + w * 3 / 2].chunks_exact(3))
        {
          o[0] = (b[0] as u16) << 4 | (b[1] as u16 >> 4);
          o[1] = (b[1] as u16 & 15) << 8 | b[2] as u16;
        }
      }
      Packing::Compressed => unreachable!(),
    }
    sink(0, y, &row)?;
  }
  Ok(())
}
