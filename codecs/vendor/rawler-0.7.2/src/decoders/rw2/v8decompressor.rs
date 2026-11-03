// SPDX-License-Identifier: LGPL-2.1
// Copyright 2024 Daniel Vogelbacher <daniel@chaospixel.com>

// Originally written by LibRaw LLC
// Copyright (C) 2022-2024 Alex Tutubalin, LibRaw LLC
// Ported from C++ to Rust by Daniel Vogelbacher




use crate::{
  alloc_image_ok,
  bits::{Endian, clamp},
  decoders::{Result, rw2::PanasonicTag},
  formats::tiff::IFD,
  pixarray::PixU16,
  pumps::ByteStream,
  rawsource::RawSource,
};

/// Defines the offsets for the start of a strip.
#[derive(Clone, Debug)]
struct StripLineOffset {
  cols: u16,
  rows: u16,
}

#[derive(Clone, Debug)]
struct CF2Params {
  /// Unknown value, it's labeled as strip_height but don't match any height
  #[allow(dead_code)]
  strip_height: u32,

  /// Unknown value, it's labeled as strip_width but don't match any width
  #[allow(dead_code)]
  strip_width: u32,

  /// Gamma point and slope value.
  gamma_point: Vec<u32>,
  gamma_slope: Vec<u32>,

  /// Max data value
  gamma_clip_val: u16,

  /// Initial values (base) for huffman coding
  huf_init_val0: u16,

  /// Initial values (base) for huffman coding
  huf_init_val1: u16,

  /// Initial values (base) for huffman coding
  huf_init_val2: u16,

  /// Initial values (base) for huffman coding
  huf_init_val3: u16,

  /// Stored huffman table, usually 17 entries
  ///
  /// This is a pair of (bitcnt, symbol).
  ///
  /// Example table:
  /// 0000000     10   1022     11   2046      8    254      9    510
  /// 0000020      7    126      4     14      4     12      3      4
  /// 0000040      3      2      2      0      3      3      3      5
  /// 0000060      4     13      5     30      6     62     12   4094
  /// 0000100     12   4095
  /// 0000104
  huf_table: Vec<(u16, u16)>,

  /// Shift down (0 in all samples...)
  huf_shift_down: Vec<u16>,

  /// Number of H strips
  num_of_strips_h: u16,

  /// Number of V strips
  num_of_strips_v: u16,

  /// Offset to bitstream
  strip_byte_offsets: Vec<u32>,

  /// Starting column offset in a output line
  strip_line_offsets: Vec<StripLineOffset>,

  /// Size in bits of compressed bitstream
  strip_data_size: Vec<u32>,

  /// Strip widths in pixels
  strip_widths: Vec<u16>,

  /// Strip heights in pixels
  strip_heights: Vec<u16>,
}

impl CF2Params {
  fn new(ifd: &IFD) -> Result<Self> {
    for tag in [PanasonicTag::CF2StripHeight, PanasonicTag::CF2StripWidth] {
      let e=fetch_tiff_tag!(ifd,tag); if e.value_type()!=4 || e.count()!=1 {return Err("Invalid CF2 scalar type".into());}
    }
    for tag in [PanasonicTag::CF2ClipVal,PanasonicTag::CF2HufInitVal0,PanasonicTag::CF2HufInitVal1,PanasonicTag::CF2HufInitVal2,PanasonicTag::CF2HufInitVal3,PanasonicTag::CF2NumberOfStripsH,PanasonicTag::CF2NumberOfStripsV] {
      let e=fetch_tiff_tag!(ifd,tag); if e.value_type()!=3 || e.count()!=1 {return Err("Invalid CF2 scalar type".into());}
    }
    for (tag,stride,max) in [(PanasonicTag::CF2Unknown1,4,6),(PanasonicTag::CF2Unknown2,4,6),(PanasonicTag::CF2HufTable,4,17),(PanasonicTag::CF2HufShiftDown,2,17),(PanasonicTag::CF2StripByteOffsets,4,256),(PanasonicTag::CF2StripLineOffsets,4,256),(PanasonicTag::CF2StripDataSize,4,256),(PanasonicTag::CF2StripWidths,2,256),(PanasonicTag::CF2StripHeights,2,256)] {
      let e=fetch_tiff_tag!(ifd,tag); if e.value_type()!=7 {return Err("Invalid CF2 vector type".into());}
      let bytes=e.get_data(); validate_vector(bytes,stride,max)?;
    }
    for c in 0..3u16 {
      let w=ifd.get_entry(0x000eu16+c).ok_or("Missing CF2 white level")?;
      let b=ifd.get_entry(0x001cu16+c).ok_or("Missing CF2 black level")?;
      if w.value_type()!=3 || w.count()!=1 || b.value_type()!=3 || b.count()!=1 || calibrated_white(w.force_u16(0))<=b.force_u16(0) as u32 {return Err("Invalid CF2 calibration".into());}
    }
    let strip_height = fetch_tiff_tag!(ifd, PanasonicTag::CF2StripHeight).force_u32(0);
    let gamma_point = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2Unknown1).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count).map(|_| bs.get_u32()).collect()
    };
    let gamma_slope = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2Unknown2).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count).map(|_| bs.get_u32()).collect()
    };
    let gamma_clip_val = fetch_tiff_tag!(ifd, PanasonicTag::CF2ClipVal).force_u16(0);
    let huf_init_val0 = fetch_tiff_tag!(ifd, PanasonicTag::CF2HufInitVal0).force_u16(0);
    let huf_init_val1 = fetch_tiff_tag!(ifd, PanasonicTag::CF2HufInitVal1).force_u16(0);
    let huf_init_val2 = fetch_tiff_tag!(ifd, PanasonicTag::CF2HufInitVal2).force_u16(0);
    let huf_init_val3 = fetch_tiff_tag!(ifd, PanasonicTag::CF2HufInitVal3).force_u16(0);
    let huf_table = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2HufTable).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count).map(|_| (bs.get_u16(), bs.get_u16())).collect()
    };
    let huf_shift_down = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2HufShiftDown).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count).map(|_| bs.get_u16()).collect()
    };
    let num_of_strips_h = fetch_tiff_tag!(ifd, PanasonicTag::CF2NumberOfStripsH).force_u16(0);
    let num_of_strips_v = fetch_tiff_tag!(ifd, PanasonicTag::CF2NumberOfStripsV).force_u16(0);
    let strip_byte_offsets = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2StripByteOffsets).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count).map(|_| bs.get_u32()).collect()
    };
    let strip_line_offsets = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2StripLineOffsets).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count)
        .map(|_| StripLineOffset {
          cols: bs.get_u16(),
          rows: bs.get_u16(),
        })
        .collect()
    };
    let strip_data_size = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2StripDataSize).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count).map(|_| bs.get_u32()).collect()
    };
    let strip_widths = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2StripWidths).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count).map(|_| bs.get_u16()).collect()
    };
    let strip_heights = {
      let mut bs = ByteStream::new(fetch_tiff_tag!(ifd, PanasonicTag::CF2StripHeights).get_data(), Endian::Little);
      let count = bs.get_u16();
      (0..count).map(|_| bs.get_u16()).collect()
    };
    let strip_width = fetch_tiff_tag!(ifd, PanasonicTag::CF2StripWidth).force_u32(0);

    Ok(Self {
      strip_height,
      gamma_point,
      gamma_slope,
      gamma_clip_val,
      huf_init_val0,
      huf_init_val1,
      huf_init_val2,
      huf_init_val3,
      huf_table,
      huf_shift_down,
      num_of_strips_h,
      num_of_strips_v,
      strip_byte_offsets,
      strip_line_offsets,
      strip_data_size,
      strip_widths,
      strip_heights,
      strip_width,
    })
  }
}

#[derive(Clone, Debug, Default)]
struct HuffmanSymbol {
  /// Length of the symbol in bits
  bitcnt: u8,
  /// Actual Huffman symbol, right-padded with 0 bits
  symbol: u16,
  /// Pre-calculated bitmask, actually (-1) << (16-bits)
  mask: u16,
}

#[derive(Clone, Debug, Default)]
struct HuffmanDecoder {
  #[allow(dead_code)]
  huff_symbols: [HuffmanSymbol; 17],

  /// Lookup cache for all possible u16 values.
  /// If a 16 bit input value is invalid (the symbol is undefined), the
  /// value is None, otherwise it's (bitcnt, ssss)
  cache: Vec<Option<(u8, u8)>>,
}

impl HuffmanDecoder {
  fn new(symlens: impl Iterator<Item = u8>, mut symbols: impl Iterator<Item = u16>) -> Self {
    let mut huff_symbols: [HuffmanSymbol; 17] = Default::default();
    for (i, symlen) in symlens.enumerate() {
      let symbol = symbols.next().expect("symbol iterator is shorter than symlens iterator");
      let bitmask = 0xFFFFu16 >> (16 - symlen);
      debug_assert_eq!(symbol, symbol & bitmask);
      // Left-align symbol and mask
      huff_symbols[i] = HuffmanSymbol {
        bitcnt: symlen,
        symbol: (symbol) << (16 - symlen),
        mask: 0xFFFF << (16 - symlen),
      };
    }

    // Generate lookup cache for all possible 16 bit input values.
    let cache = (0..=0xFFFFu16)
      .map(|x| Self::slow_lookup(&huff_symbols, x).map(|ssss| (huff_symbols[ssss as usize].bitcnt, ssss)))
      .collect();
    Self { huff_symbols, cache }
  }

  /// Slow lookup into Huffman symbol table
  fn slow_lookup(huff_symbols: &[HuffmanSymbol; 17], bits: u16) -> Option<u8> {
    for i in 0..17 {
      if (bits & huff_symbols[i].mask) == huff_symbols[i].symbol {
        return Some(i as u8);
      }
    }
    None
  }

  /// Extract Huffman symbol from bitstream pump and
  /// return index into symbol table.
  fn get_next(&self, pump: &mut CheckedBits<'_>) -> Result<u8> {
    let next=pump.peek16();
    let (bits,symbol)=self.cache[next as usize].ok_or("Invalid CF2 Huffman symbol")?;
    pump.read(bits as usize)?;
    Ok(symbol)
  }

}

/// Internal decoder state
struct State {
  huffdec: HuffmanDecoder,
  gamma_table: Option<Vec<u16>>,
  datamax: i32,
  line_base: CoeffBase,
  current_base: CoeffBase,
}

impl State {
  fn new(params: &CF2Params) -> Self {
    let initial = [params.huf_init_val0, params.huf_init_val1, params.huf_init_val2, params.huf_init_val3];
    let gamma_table = make_gammatable(params);

    let line_base = CoeffBase::new([initial[0], initial[1], initial[2], initial[3]]);
    let current_base = line_base;

    let huffdec = HuffmanDecoder::new(
      params.huf_table.iter().map(|(bits, _symbol)| *bits as u8),
      params.huf_table.iter().map(|(_bits, symbol)| *symbol),
    );

    Self {
      huffdec,
      gamma_table,
      datamax: params.gamma_clip_val as i32,
      line_base,
      current_base,
    }
  }
}

#[derive(Clone, Copy, Debug)]
struct CoeffBase {
  coeff: [u16; 4],
}

impl CoeffBase {
  pub fn new(coeff: [u16; 4]) -> Self {
    Self { coeff }
  }

  pub fn update(&mut self, line: &[u16]) {
    self.coeff = [line[0], line[1], line[2], line[3]];
  }
}

fn calc_gamma(params: &CF2Params, idx: u32) -> u16 {
  let gamma_base = 0;
  let gamma_points = &params.gamma_point; // [65536, 65536, 65536, 65536, 65536, 65536]
  let gamma_slopes = &params.gamma_slope; // 0
  let clipping = params.gamma_clip_val;

  let mut x = {
    let mut tmp: u32 = idx | 0xFFFF0000;
    if (idx & 0x10000) == 0 {
      tmp = idx & 0x1FFFF;
    }
    u32::min(gamma_base + tmp, 0xFFFF)
  };

  let mut idx = 0;
  if (x & 0x80000000) != 0 {
    x = 0;
  }

  if x >= (0xFFFF & gamma_slopes[1]) {
    idx = 1;
    if x >= (0xFFFF & gamma_slopes[2]) {
      idx = 2;
      if x >= (0xFFFF & gamma_slopes[3]) {
        idx = 3;
        if x >= (0xFFFF & gamma_slopes[4]) {
          idx = (((x as u64 | 0x5_00000000u64) - (0xFFFF & gamma_slopes[5]) as u64) >> 32) as usize;
        }
      }
    }
  }

  let point = gamma_points[idx];
  let slope = gamma_slopes[idx];
  let mut tmp: u32 = x - (slope & 0xFFFF);
  let result: u16;

  if (point & 0x1F) == 31 {
    result = if idx == 5 { 0xFFFF } else { (gamma_slopes[idx + 1] >> 16) & 0xFFFF } as u16;
    return u16::min(result, clipping);
  }
  if (point & 0x10) == 0 {
    if (point & 0x1F) == 15 {
      let result = ((slope >> 16) & 0xFFFF) as u16;
      return u16::min(result, clipping);
    } else if (point & 0x1F) != 0 {
      tmp = (tmp + (1 << ((point & 0x1F) - 1))) >> (point & 0x1F);
    }
  } else {
    tmp <<= point & 0xF;
  }

  result = (tmp + ((slope >> 16) & 0xFFFF)) as u16;
  u16::min(result, clipping)
}

fn make_gammatable(params: &CF2Params) -> Option<Vec<u16>> {
  const LINEAR_POINTS: [u32; 6] = [65536; 6];
  const LINEAR_SLOPES: [u32; 6] = [0; 6];

  if params.gamma_point == LINEAR_POINTS && params.gamma_slope == LINEAR_SLOPES {
    None
  } else {
    let mut table = vec![0; 0x10000];
    let mut _linear = true;
    for idx in 0..0x10000 {
      table[idx] = calc_gamma(params, idx as u32);
      _linear = _linear && table[idx] == idx as u16;
    }
    Some(table)
  }
}

// LibRaw0.22.2 Panasonic LinearityLimit calibration (metadata, not pixel math).
pub(super) fn calibrated_white(raw:u16)->u32 {match raw {4095=>4079,16383=>16319,_=>raw as u32}}
fn validate_vector(bytes:&[u8], stride:usize, max:usize)->Result<()> {
  if bytes.len()<2 || bytes.len()>2+256*4 {return Err("Invalid CF2 vector length".into());}
  let count=u16::from_le_bytes([bytes[0],bytes[1]]) as usize;
  let end=2+count*stride;
  if count==0 || count>max || end>bytes.len() || bytes[end..].iter().any(|&b|b!=0) {return Err("Invalid CF2 vector count/padding".into());}
  Ok(())
}
impl CF2Params {
  fn validate(&self, width:usize,height:usize,file_len:usize,bps:u32)->Result<()> {
    let bad=|| crate::RawlerError::DecoderFailed("Invalid or unsupported CF2 plan".into());
    let pixels=width.checked_mul(height).filter(|&n|n>0 && n<=52_000_000).ok_or_else(bad)?;
    if !matches!(bps,12|14|16) || width%2!=0 || height%2!=0 {return Err(bad());}
    let n=(self.num_of_strips_h as usize).checked_mul(self.num_of_strips_v as usize).filter(|&n|n>0&&n<=256).ok_or_else(bad)?;
    if [self.strip_byte_offsets.len(),self.strip_line_offsets.len(),self.strip_data_size.len(),self.strip_widths.len(),self.strip_heights.len()].iter().any(|&v|v!=n) {return Err(bad());}
    // Unproven gamma/shift modes fail closed rather than silently changing decoded values.
    if self.gamma_point!=[65536;6] || self.gamma_slope!=[0;6] || self.huf_shift_down!=[0;17] || self.huf_table.len()!=17 || self.gamma_clip_val==0 {return Err(bad());}
    if [self.huf_init_val0,self.huf_init_val1,self.huf_init_val2,self.huf_init_val3].iter().any(|&v|v>self.gamma_clip_val) {return Err(bad());}
    for &(bits,code) in &self.huf_table {
      if bits==0 || bits>16 || u32::from(code)>=(1u32<<bits) {return Err(bad());}
      // Ordered first-match semantics are intentional: real 14-bit CF2 tables
      // contain unused longer codes shadowed by the symbol-14 prefix.

    }
    let mut area=0usize;
    for i in 0..n {
      let (x,y)=(self.strip_line_offsets[i].cols as usize,self.strip_line_offsets[i].rows as usize);
      let (w,h)=(self.strip_widths[i] as usize,self.strip_heights[i] as usize);
      if w<2||h<2||[x,y,w,h].iter().any(|v|v%2!=0)||x+w>width||y+h>height {return Err(bad());}
      area=area.checked_add(w*h).ok_or_else(bad)?;
      let off=self.strip_byte_offsets[i] as usize;let bytes=(self.strip_data_size[i] as usize).div_ceil(8);
      if off<8||bytes==0||off.checked_add(bytes).is_none_or(|end|end>file_len) {return Err(bad());}
      for j in 0..i {
        let (xx,yy)=(self.strip_line_offsets[j].cols as usize,self.strip_line_offsets[j].rows as usize);
        if x<xx+self.strip_widths[j] as usize && xx<x+w && y<yy+self.strip_heights[j] as usize && yy<y+h {return Err(bad());}
        let oo=self.strip_byte_offsets[j] as usize;let bb=(self.strip_data_size[j] as usize).div_ceil(8);
        if off<oo+bb && oo<off+bytes {return Err(bad());}
      }
    }
    if area!=pixels {return Err(bad());} Ok(())
  }
}
/// Byte-reversed bit order, with declared bit length rather than padded reads.
/// Huffman lookahead may be zero-extended; consumed bits never may be.
struct CheckedBits<'a>{data:&'a[u8],pos:usize,len:usize}
impl<'a> CheckedBits<'a>{
 fn peek16(&self)->u16 {
   let byte=self.pos/8;let shift=self.pos%8;
   let at=|n:usize|self.data.get(n).copied().unwrap_or(0).reverse_bits() as u32;
   let value=(((at(byte)<<16)|(at(byte+1)<<8)|at(byte+2))>>(8-shift)) as u16;
   let available=self.len.saturating_sub(self.pos).min(16);
   if available==0 {0} else {value & (u16::MAX << (16-available))}
 }
 fn read(&mut self,n:usize)->Result<u32>{if n>16||self.pos.checked_add(n).is_none_or(|p|p>self.len){return Err("Truncated CF2 bits".into());}let v=(self.peek16() as u32)>>(16-n);self.pos+=n;Ok(v)}
}
/// Plan validation precedes dummy decode and allocation.
pub(crate) fn decode_panasonic_v8(rawfile:&RawSource,width:usize,height:usize,bps:u32,ifd:&IFD,dummy:bool)->Result<PixU16>{
 decode_panasonic_v8_cancel(rawfile,width,height,bps,ifd,dummy,&||false)
}
pub(crate) fn decode_panasonic_v8_cancel(rawfile:&RawSource,width:usize,height:usize,bps:u32,ifd:&IFD,dummy:bool,cancel:&dyn Fn()->bool)->Result<PixU16>{
 let params=CF2Params::new(ifd)?;params.validate(width,height,rawfile.buf().len(),bps)?;
 if cancel(){return Err("CF2 cancelled".into());}
 let mut out=alloc_image_ok!(width,height,dummy);
 for i in 0..params.strip_widths.len(){
   let buf=rawfile.subview(params.strip_byte_offsets[i] as u64,(params.strip_data_size[i] as u64).div_ceil(8))?;
   decode_strip(buf,&params,i,&mut out,cancel)?;
 }
 Ok(out)
}

/// Decode a single strip
fn decode_strip(buf: &[u8], params: &CF2Params, strip_id: usize, out: &mut PixU16,cancel:&dyn Fn()->bool) -> Result<()> {
  let mut pump = CheckedBits{data:buf,pos:0,len:params.strip_data_size[strip_id] as usize};
  let width = params.strip_widths[strip_id] as usize;
  let height = params.strip_heights[strip_id] as usize;
  let halfheight = height >> 1;
  let halfwidth = width >> 1;
  let doublewidth = halfwidth * 4;
  let mut linebuf = vec![0_u16; doublewidth];

  // for (i, item) in params.huf_table.iter().enumerate() {
  //   let fmt = format!("{:#032b}", item.1);
  //   log::debug!("Pana8 huf_table {i}: {} (bits: {})", fmt.split_at((32 - item.0) as usize).1, item.0);
  //   //log::debug!("Pana8: huf_table {:02}: {}, {}", i, item.0, item.1);
  // }

  let mut state = State::new(params);

  // Data is encoded in RGRGRG..GBGBGB in a single line (like LJPEG92 4-7 predictors)
  for curr_row in 0..halfheight {
    if cancel(){return Err("CF2 cancelled".into());}
    state.current_base = state.line_base;
    for col in 0..doublewidth {
      // Calculate index
      let ssss = state.huffdec.get_next(&mut pump)?;

      // Shiftdown seems to be the count of bits shifted to right during encoding.
      // It's 0 for all existing samples so far, highly interested in samples that has
      let shift_down: u8 = (params.huf_shift_down[ssss as usize] & 0x1F) as u8;
      assert_eq!(shift_down, 0, "CF2HufShiftDown samples required");

      // Calculate total required bits to read from bitstream.
      let req_bits: u32 = ssss.saturating_sub(shift_down as u8) as u32;
      let delta1: i32 = if req_bits == 0 {
        0
      } else {
        debug_assert_ne!(req_bits, 0);
        let rawbits: u32 = pump.read(req_bits as usize)?; // Get additional bits
        let sign = rawbits >> (req_bits - 1); // Get leading sign bit
        let val = (rawbits << (params.huf_shift_down[ssss as usize] & 0xFF)) as i32;

        if sign == 1 {
          val
        } else if ssss > 0 {
          if shift_down != 0 { val + (-1 << ssss) } else { val + (-1 << ssss) + 1 }
        } else {
          0
        }
      };

      let delta2 = if shift_down != 0 { 1 << (shift_down - 1) } else { 0 };
      let delta = delta1 + delta2;

      // For each col iteration, we write to ONE pixel of of 4-pixel group.
      let destpixel = &mut linebuf[col & !0x3..];

      if col & 3 == 2 {
        let val = state.current_base.coeff[1] as i32 + delta;
        destpixel[1] = clamp(val, 0, state.datamax) as u16;
      } else if col & 3 == 1 {
        let val = state.current_base.coeff[2] as i32 + delta;
        destpixel[2] = clamp(val, 0, state.datamax) as u16;
      } else if col & 3 != 0 {
        let val = state.current_base.coeff[3] as i32 + delta;
        destpixel[3] = clamp(val, 0, state.datamax) as u16;
      } else {
        let val = state.current_base.coeff[0] as i32 + delta;
        destpixel[0] = clamp(val, 0, state.datamax) as u16;
      }

      if col & 3 == 3 {
        state.current_base.update(destpixel);
      }
      if col == 3 {
        // base for next line (col == 3 -> first 4 pixels are complete in current row)
        state.line_base.update(&linebuf);
      }
    }

    // Copy line buffer into output image.
    // Line buffer contains two rows packed into one row with double width.
    assert_eq!(linebuf.len(), 2 * width);
    for col in (0..width).step_by(2) {
      let row_offset = params.strip_line_offsets[strip_id].rows as usize;
      let left_margin = params.strip_line_offsets[strip_id].cols as usize;
      let dest_row = row_offset + (curr_row * 2);
      if let Some(gamma_table) = &state.gamma_table {
        debug_assert!(!gamma_table.is_empty());
        *out.at_mut(dest_row + 0, left_margin + col + 0) = gamma_table[linebuf[2 * col + 0] as usize];
        *out.at_mut(dest_row + 0, left_margin + col + 1) = gamma_table[linebuf[2 * col + 1] as usize];
        *out.at_mut(dest_row + 1, left_margin + col + 0) = gamma_table[linebuf[2 * col + 2] as usize];
        *out.at_mut(dest_row + 1, left_margin + col + 1) = gamma_table[linebuf[2 * col + 3] as usize];
      } else {
        *out.at_mut(dest_row + 0, left_margin + col + 0) = linebuf[2 * col + 0];
        *out.at_mut(dest_row + 0, left_margin + col + 1) = linebuf[2 * col + 1];
        *out.at_mut(dest_row + 1, left_margin + col + 0) = linebuf[2 * col + 2];
        *out.at_mut(dest_row + 1, left_margin + col + 1) = linebuf[2 * col + 3];
      }
    }
  }
  Ok(())
}

#[cfg(test)] mod bounded_tests {
 use super::*;
 fn params()->CF2Params {CF2Params{strip_height:0,strip_width:0,gamma_point:vec![65536;6],gamma_slope:vec![0;6],gamma_clip_val:65535,huf_init_val0:0,huf_init_val1:0,huf_init_val2:0,huf_init_val3:0,huf_table:(0..17).map(|i|(5,i)).collect(),huf_shift_down:vec![0;17],num_of_strips_h:2,num_of_strips_v:1,strip_byte_offsets:vec![8,16],strip_line_offsets:vec![StripLineOffset{cols:0,rows:0},StripLineOffset{cols:2,rows:0}],strip_data_size:vec![64,64],strip_widths:vec![2,2],strip_heights:vec![2,2]}}
 #[test] fn checked_reader_matches_scalar_every_boundary(){
  let data=[0xa5,0x01,0xff,0x80,0x53];
  for len in 0..=40 {for pos in 0..=len {for n in 0..=16 {
    let mut p=CheckedBits{data:&data,pos,len};let got=p.read(n);
    if pos+n>len {assert!(got.is_err());assert_eq!(p.pos,pos);} else {
      let mut expected=0;for k in pos..pos+n {expected=expected*2+((data[k/8]>>(k%8))&1) as u32;}
      assert_eq!(got.unwrap(),expected);assert_eq!(p.pos,pos+n);
    }
  }}}
 }
 #[test] fn vector_capacity_padding_is_checked(){
  let mut b=vec![0;50];b[0]=2;assert!(validate_vector(&b,4,256).is_ok());
  b[49]=1;assert!(validate_vector(&b,4,256).is_err());b[49]=0;b[0]=13;assert!(validate_vector(&b,4,256).is_err());
  assert!(validate_vector(&[1],4,256).is_err());assert!(validate_vector(&[0,0],4,256).is_err());
 }
 #[test] fn plan_rejects_geometry_entropy_and_unproven_modes(){
  let base=params();assert!(base.validate(4,2,24,14).is_ok());
  let mut mutations=vec![];
  let mut p=base.clone();p.num_of_strips_v=0;mutations.push(p);
  let mut p=base.clone();p.num_of_strips_h=u16::MAX;mutations.push(p);
  let mut p=base.clone();p.strip_line_offsets[1].cols=0;mutations.push(p);
  let mut p=base.clone();p.strip_line_offsets[1].cols=3;mutations.push(p);
  let mut p=base.clone();p.strip_widths[1]=0;mutations.push(p);
  let mut p=base.clone();p.strip_heights[1]=4;mutations.push(p);
  let mut p=base.clone();p.strip_widths.pop();mutations.push(p);
  let mut p=base.clone();p.strip_byte_offsets[1]=9;mutations.push(p);
  let mut p=base.clone();p.strip_byte_offsets[1]=u32::MAX;mutations.push(p);
  let mut p=base.clone();p.strip_data_size[1]=65;mutations.push(p);
  let mut p=base.clone();p.gamma_point[0]=1;mutations.push(p);
  let mut p=base.clone();p.huf_shift_down[0]=32;mutations.push(p);
  let mut p=base.clone();p.huf_table[0]=(17,0);mutations.push(p);
  let mut p=base.clone();p.huf_table[0]=(1,2);mutations.push(p);
  for p in mutations {assert!(p.validate(4,2,24,14).is_err());}
  assert!(base.validate(usize::MAX,2,24,14).is_err());
 }
 #[test] fn ordered_shadowed_huffman_keeps_first_match(){
  let mut p=params();p.huf_table=(0..14).map(|i|(8,i)).chain([(8,255),(12,4094),(12,4095)]).collect();
  assert!(p.validate(4,2,24,14).is_ok());
  let h=HuffmanDecoder::new(p.huf_table.iter().map(|x|x.0 as u8),p.huf_table.iter().map(|x|x.1));
  for x in 0..=u16::MAX {assert_eq!(h.cache[x as usize],HuffmanDecoder::slow_lookup(&h.huff_symbols,x).map(|i|(h.huff_symbols[i as usize].bitcnt,i)));}
  assert_eq!(h.cache[0xffe0],Some((8,14)));assert_eq!(h.cache[0xfff0],Some((8,14)));
 }
 #[test] fn strip_truncation_invalid_symbol_cancel_and_retry(){
  let p=params();let mut out=PixU16::new(4,2);
  assert!(decode_strip(&[0;8],&p,0,&mut out,&||true).is_err());
  assert!(decode_strip(&[0;8],&p,0,&mut out,&||false).is_ok());
  let mut short=p.clone();short.strip_data_size[0]=19;
  assert!(decode_strip(&[0;3],&short,0,&mut out,&||false).is_err());
  assert!(decode_strip(&[255;8],&p,0,&mut out,&||false).is_err());
  assert!(decode_strip(&[0;8],&p,0,&mut out,&||false).is_ok());
  let mut tall=p.clone();tall.strip_heights[0]=4;tall.strip_data_size[0]=128;
  let polls=std::cell::Cell::new(0);let mut out=PixU16::new(4,4);
  assert!(decode_strip(&[0;16],&tall,0,&mut out,&||{polls.set(polls.get()+1);polls.get()==2}).is_err());
  assert_eq!(polls.get(),2); // first row pair completed, second is cancelled
  assert_eq!(calibrated_white(4095),4079);assert_eq!(calibrated_white(16383),16319);assert_eq!(calibrated_white(65535),65535);
 }
}
