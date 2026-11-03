use image::DynamicImage;

use crate::CFA;
use crate::RawImage;
use crate::RawLoader;
use crate::RawlerError;
use crate::Result;
use crate::analyze::FormatDump;
use crate::exif::Exif;
use crate::formats::tiff::Entry;
use crate::formats::tiff::GenericTiffReader;
use crate::formats::tiff::IFD;
use crate::formats::tiff::Rational;
use crate::formats::tiff::Value;
use crate::formats::tiff::reader::TiffReader;
use crate::imgop::Dim2;
use crate::imgop::Point;
use crate::imgop::Rect;
use crate::lens::LensDescription;
use crate::lens::LensResolver;
use crate::packed::decode_12le_unpacked_left_aligned;
use crate::packed::decode_12le_wcontrol;
use crate::pixarray::PixU16;
use crate::rawimage::CFAConfig;
use crate::rawimage::RawPhotometricInterpretation;
use crate::rawsource::RawSource;
use crate::tags::ExifTag;
use crate::tags::TiffCommonTag;
use crate::tags::tiff_tag_enum;

use self::v4decompressor::decode_panasonic_v4;
use self::v5decompressor::decode_panasonic_v5;
use self::v6decompressor::decode_panasonic_v6;
use self::v7decompressor::decode_panasonic_v7;
use self::v8decompressor::decode_panasonic_v8;

use super::BlackLevel;
use super::Camera;
use super::Decoder;
use super::FormatHint;
use super::RawDecodeParams;
use super::RawMetadata;

pub(crate) mod v4decompressor;
pub(crate) mod v5decompressor;
pub(crate) mod v6decompressor;
pub(crate) mod v7decompressor;
pub(crate) mod v8decompressor;

#[derive(Debug, Clone)]
pub struct Rw2Decoder<'a> {
  #[allow(unused)]
  rawloader: &'a RawLoader,
  tiff: GenericTiffReader,
  camera_ifd: Option<IFD>,
  camera: Camera,
}

impl<'a> Rw2Decoder<'a> {
  pub fn new(_file: &RawSource, tiff: GenericTiffReader, rawloader: &'a RawLoader) -> Result<Rw2Decoder<'a>> {
    let raw = {
      let data = tiff.find_ifds_with_tag(TiffCommonTag::PanaOffsets);
      if !data.is_empty() {
        data[0]
      } else {
        tiff
          .find_first_ifd_with_tag(TiffCommonTag::StripOffsets)
          .ok_or_else(|| RawlerError::DecoderFailed(format!("Failed to find a IFD with StripOffsets tag")))?
      }
    };

    let width = fetch_tiff_tag!(raw, TiffCommonTag::PanaWidth).force_usize(0);
    let height = fetch_tiff_tag!(raw, TiffCommonTag::PanaLength).force_usize(0);

    let mode = {
      let ratio = width * 100 / height;
      if ratio < 125 {
        "1:1"
      } else if ratio < 145 {
        "4:3"
      } else if ratio < 165 {
        "3:2"
      } else {
        "16:9"
      }
    };
    let camera = rawloader.check_supported_with_mode(tiff.root_ifd(), mode)?;

    let camera_ifd = if let Some(ifd) = tiff.get_entry(PanasonicTag::CameraIFD) {
      let buf = ifd.get_data();
      match IFD::new_root(&mut std::io::Cursor::new(buf), 0) {
        Ok(ifd) => Some(ifd),
        Err(_) => None,
      }
    } else {
      None
    };

    Ok(Rw2Decoder {
      rawloader,
      tiff,
      camera_ifd,
      camera,
    })
  }
}

impl<'a> Decoder for Rw2Decoder<'a> {
  fn raw_image(&self,file:&RawSource,_params:&RawDecodeParams,dummy:bool)->Result<RawImage>{self.raw_image_checked(file,dummy,&||false)}
  fn raw_image_cancel(&self,file:&RawSource,_params:&RawDecodeParams,cancel:&dyn Fn()->bool)->Result<RawImage>{self.raw_image_checked(file,false,cancel)}

  fn full_image(&self, _file: &RawSource, params: &RawDecodeParams) -> Result<Option<DynamicImage>> {
    if params.image_index != 0 {
      return Ok(None);
    }
    if let Some(data) = self.tiff.get_entry(PanasonicTag::JpegData) {
      let buf = data.get_data();
      let img = image::load_from_memory_with_format(buf, image::ImageFormat::Jpeg)
        .map_err(|e| RawlerError::DecoderFailed(format!("Unable to load jpeg preview: {:?}", e)))?;
      return Ok(Some(img));
    }
    Ok(None)
  }

  fn format_dump(&self) -> FormatDump {
    todo!()
  }

  fn raw_metadata(&self, _file: &RawSource, _params: &RawDecodeParams) -> Result<RawMetadata> {
    let mut exif = Exif::new(self.tiff.root_ifd())?;
    if exif.iso_speed.unwrap_or(0) == 0 && exif.iso_speed_ratings.unwrap_or(0) == 0 && exif.recommended_exposure_index.unwrap_or(0) == 0 {
      // Use ISO from PanasonicRaw IFD
      if let Some(iso) = self.tiff.get_entry(PanasonicTag::ISO) {
        exif.iso_speed_ratings = Some(iso.force_u16(0));
      }
    }
    let mdata = RawMetadata::new_with_lens(&self.camera, exif, self.get_lens_description()?.cloned());
    Ok(mdata)
  }

  fn format_hint(&self) -> FormatHint {
    FormatHint::RW2
  }
}

impl<'a> Rw2Decoder<'a> {
  fn get_wb(&self) -> Result<[f32; 4]> {
    if self.tiff.has_entry(PanasonicTag::PanaWBsR) && self.tiff.has_entry(PanasonicTag::PanaWBsB) {
      let r = fetch_tiff_tag!(self.tiff, PanasonicTag::PanaWBsR).force_u32(0) as f32;
      let b = fetch_tiff_tag!(self.tiff, PanasonicTag::PanaWBsB).force_u32(0) as f32;
      Ok([r, 256.0, 256.0, b])
    } else if self.tiff.has_entry(PanasonicTag::PanaWBs2R) && self.tiff.has_entry(PanasonicTag::PanaWBs2G) && self.tiff.has_entry(PanasonicTag::PanaWBs2B) {
      let r = fetch_tiff_tag!(self.tiff, PanasonicTag::PanaWBs2R).force_u32(0) as f32;
      let g = fetch_tiff_tag!(self.tiff, PanasonicTag::PanaWBs2G).force_u32(0) as f32;
      let b = fetch_tiff_tag!(self.tiff, PanasonicTag::PanaWBs2B).force_u32(0) as f32;
      Ok([r, g, g, b])
    } else {
      Err(RawlerError::DecoderFailed("RW2: Couldn't find WB".to_string()))
    }
  }

  fn get_cfa(&self) -> Result<Option<CFA>> {
    if self.tiff.has_entry(PanasonicTag::CFAPattern) {
      let pattern = fetch_tiff_tag!(self.tiff, PanasonicTag::CFAPattern).force_u16(0);
      Ok(Some(match pattern {
        1 => CFA::new("RGGB"),
        2 => CFA::new("GRBG"),
        3 => CFA::new("GBRG"),
        4 => CFA::new("BGGR"),
        _ => return Err(format!("RW2: Unknown CFA pattern: {}", pattern).into()),
      }))
    } else {
      Ok(None)
    }
  }

  fn get_blacklevel(&self) -> Result<Option<BlackLevel>> {
    if self.tiff.has_entry(PanasonicTag::BlackLevelRed) {
      let r = fetch_tiff_tag!(self.tiff, PanasonicTag::BlackLevelRed).force_u16(0);
      let g = fetch_tiff_tag!(self.tiff, PanasonicTag::BlackLevelGreen).force_u16(0);
      let b = fetch_tiff_tag!(self.tiff, PanasonicTag::BlackLevelBlue).force_u16(0);
      Ok(Some(BlackLevel::new(&[r, g, g, b], self.camera.cfa.width, self.camera.cfa.height, 1)))
    } else {
      Ok(None)
    }
  }

  /// Get lens description by analyzing TIFF tags and makernotes
  fn get_lens_description(&self) -> Result<Option<&'static LensDescription>> {
    const MFT_MOUNT: &str = "MFT-mount";
    if let Some(ifd) = &self.camera_ifd {
      if ifd.has_entry(CameraIfdTag::LensTypeMake) && ifd.has_entry(CameraIfdTag::LensTypeModel) {
        let make_id = fetch_tiff_tag!(ifd, CameraIfdTag::LensTypeMake);
        let model_id = fetch_tiff_tag!(ifd, CameraIfdTag::LensTypeModel);

        if make_id.value_type() == 3 && model_id.value_type() == 3 {
          let composite_id = format!(
            "{:02X} {:02X} {:02X}",
            make_id.force_u16(0) & 0xFF,
            model_id.force_u16(0) & 0xFF,
            model_id.force_u16(0) >> 8
          );
          log::debug!("RW2 lens composite ID: {}", composite_id);
          let resolver = LensResolver::new()
            .with_camera(&self.camera)
            .with_olympus_id(Some(composite_id))
            .with_focal_len(self.get_focal_len()?)
            .with_mounts(&[MFT_MOUNT.into()]);
          return Ok(resolver.resolve());
        } else {
          log::info!("Unknown value types for lens tags: {}, {}", make_id.value_type(), model_id.value_type());
        }
      }
    }
    log::warn!("No lens data available");
    Ok(None)
  }

  fn get_focal_len(&self) -> Result<Option<Rational>> {
    if let Some(exif) = self.tiff.find_first_ifd_with_tag(ExifTag::MakerNotes) {
      if let Some(Entry {
        value: Value::Short(focal), ..
      }) = exif.get_entry(ExifTag::FocalLength)
      {
        return Ok(focal.get(1).map(|v| Rational::new(*v as u32, 1)));
      }
    }
    Ok(None)
  }

  fn get_crop(&self) -> Result<Option<Rect>> {
    if self.tiff.has_entry(PanasonicTag::CropLeft) {
      let crop_left = fetch_tiff_tag!(self.tiff, PanasonicTag::CropLeft).force_usize(0);
      let crop_top = fetch_tiff_tag!(self.tiff, PanasonicTag::CropTop).force_usize(0);
      let crop_right = fetch_tiff_tag!(self.tiff, PanasonicTag::CropRight).force_usize(0);
      let crop_bottom = fetch_tiff_tag!(self.tiff, PanasonicTag::CropBottom).force_usize(0);
      Ok(Some(Rect::new(
        Point::new(crop_left, crop_top),
        Dim2::new(crop_right - crop_left, crop_bottom - crop_top),
      )))
    } else {
      Ok(None)
    }
  }

  fn get_active_area(&self) -> Result<Option<Rect>> {
    if self.tiff.has_entry(PanasonicTag::SensorLeftBorder) {
      let sensor_left = fetch_tiff_tag!(self.tiff, PanasonicTag::SensorLeftBorder).force_usize(0);
      let sensor_top = fetch_tiff_tag!(self.tiff, PanasonicTag::SensorTopBorder).force_usize(0);
      let sensor_right = fetch_tiff_tag!(self.tiff, PanasonicTag::SensorRightBorder).force_usize(0);
      let sensor_bottom = fetch_tiff_tag!(self.tiff, PanasonicTag::SensorBottomBorder).force_usize(0);
      Ok(Some(Rect::new(
        Point::new(sensor_left, sensor_top),
        Dim2::new(sensor_right - sensor_left, sensor_bottom - sensor_top),
      )))
    } else {
      Ok(None)
    }
  }

  pub(crate) fn decode_panasonic(
    file: &RawSource,
    buf: &[u8],
    width: usize,
    height: usize,
    split: bool,
    raw_format: u16,
    bps: u32,
    ifd: &IFD,
    dummy: bool,
  ) -> Result<PixU16> {
    log::debug!("width: {}, height: {}, bps: {}", width, height, bps);
    Ok(match raw_format {
      3 => decode_panasonic_v4(buf, width, height, split, dummy),
      4 => decode_panasonic_v4(buf, width, height, split, dummy),
      5 => decode_panasonic_v5(buf, width, height, bps, dummy),
      6 => decode_panasonic_v6(buf, width, height, bps, dummy),
      7 => decode_panasonic_v7(buf, width, height, bps, dummy),
      8 => decode_panasonic_v8(file, width, height, bps, ifd, dummy)?,
      _ => todo!("Format {} is not implemented", raw_format), // TODO: return error
    })
  }
}

fn normalize_wb(raw_wb: [f32; 4]) -> [f32; 4] {
  log::debug!("RW2 raw wb: {:?}", raw_wb);
  let div = raw_wb[1];
  let mut norm = raw_wb;
  norm.iter_mut().for_each(|v| {
    if v.is_normal() {
      *v /= div
    }
  });
  [norm[0], (norm[1] + norm[2]) / 2.0, norm[3], f32::NAN]
}

tiff_tag_enum!(PanasonicTag);
tiff_tag_enum!(CameraIfdTag);

/// Common tags, generally used in root IFD or SubIFDs
#[derive(Debug, Copy, Clone, PartialEq, enumn::N)]
#[repr(u16)]
pub enum PanasonicTag {
  PanaWidth = 0x0002,
  PanaLength = 0x0003,
  SensorTopBorder = 0x0004,
  SensorLeftBorder = 0x0005,
  SensorBottomBorder = 0x0006,
  SensorRightBorder = 0x0007,
  SamplesPerPixel = 0x0008,
  CFAPattern = 0x0009,
  BitsPerSample = 0x000a,
  Compression = 0x000b,
  PanaWBsR = 0x0011,
  PanaWBsB = 0x0012,
  ISO = 0x0017,

  BlackLevelRed = 0x001c,
  BlackLevelGreen = 0x001d,
  BlackLevelBlue = 0x001e,

  PanaWBs2R = 0x0024,
  PanaWBs2G = 0x0025,
  PanaWBs2B = 0x0026,
  RawFormat = 0x0002d,
  JpegData = 0x002e,
  CropTop = 0x002f,
  CropLeft = 0x0030,
  CropBottom = 0x0031,
  CropRight = 0x0032,

  CF2StripHeight = 0x0037,
  CF2Unknown1 = 0x0039, // Gamma table CF2_GammaSlope?
  CF2Unknown2 = 0x003a, // Gamma table CF2_GammaPoint?
  CF2ClipVal = 0x003b,  // CF2_GammaClipVal
  CF2HufInitVal0 = 0x003c,
  CF2HufInitVal1 = 0x003d,
  CF2HufInitVal2 = 0x003e,
  CF2HufInitVal3 = 0x003f,
  CF2HufTable = 0x0040,
  CF2HufShiftDown = 0x0041,
  CF2NumberOfStripsH = 0x0042,
  CF2NumberOfStripsV = 0x0043,
  CF2StripByteOffsets = 0x0044,
  CF2StripLineOffsets = 0x0045,
  CF2StripDataSize = 0x0046,
  CF2StripWidths = 0x0047,
  CF2StripHeights = 0x0048,
  CF2StripWidth = 0x0064,

  CameraIFD = 0x0120,
  Multishot = 0x0121,
}

/// Common tags, generally used in root IFD or SubIFDs
#[derive(Debug, Copy, Clone, PartialEq, enumn::N)]
#[repr(u16)]
pub enum CameraIfdTag {
  LensTypeMake = 0x1201,
  LensTypeModel = 0x1202,
}

/// Filmroom checked storage geometry for bounded ordinary RW2 decoding.
/// v8 has separate strip entropy/metadata requirements and is not admitted here.
pub fn bounded_payload_bytes(width:usize,height:usize,version:u16,bps:u32)->Result<usize> {
  let bad=||RawlerError::DecoderFailed("Unsupported or invalid RW2 storage geometry".into());
  let pixels=width.checked_mul(height).filter(|v|*v>0 && *v<=52_000_000).ok_or_else(bad)?;
  if !matches!(bps,12|14) {return Err(bad());}
  let bytes=match version {
    3|4 if bps==12 && width%14==0 => {
      // Panasonic pump addresses 16KiB shuffled blocks, with a two-byte lookup.
      (pixels/14).checked_mul(16).ok_or_else(bad)?.div_ceil(0x4000)*0x4000
    },
    5 => {let n=if bps==12 {10}else{9}; if pixels%n!=0{return Err(bad());} pixels.div_ceil(1024*n)*0x4000},
    6 => {let n=if bps==12 {14}else{11}; if width%n!=0{return Err(bad());} pixels/n*16},
    // Vendor v7 currently reads 14 bits even for 12-bit metadata; fail closed.
    7 if bps==14 && width%9==0 => pixels/9*16,
    _=>return Err(bad()),
  };
  Ok(bytes)
}

impl<'a> Rw2Decoder<'a> {
  fn raw_image_checked(&self, file: &RawSource, dummy: bool,cancel:&dyn Fn()->bool) -> Result<RawImage> {
    let width;
    let height;

    let (raw, split) = {
      let data = self.tiff.find_ifds_with_tag(TiffCommonTag::PanaOffsets);
      if !data.is_empty() {
        (data[0], true)
      } else {
        (
          self
            .tiff
            .find_first_ifd_with_tag(TiffCommonTag::StripOffsets)
            .ok_or_else(|| RawlerError::DecoderFailed(format!("Failed to find a IFD with StripOffsets tag")))?,
          false,
        )
      }
    };

    let compression = raw.get_entry(PanasonicTag::Compression).map(|entry| entry.force_u16(0)).unwrap_or_default(); // TODO BUG
    //let compression = fetch_tiff_tag!(raw, PanasonicTag::Compression).force_u16(0);

    let raw_format = raw.get_entry(PanasonicTag::RawFormat).map(|entry| entry.force_u16(0)).unwrap_or_default(); // TODO BUG

    let bps = fetch_tiff_tag!(raw, PanasonicTag::BitsPerSample).force_u32(0);
    let multishot = raw.get_entry(PanasonicTag::Multishot).map(|entry| entry.force_u32(0) == 65536).unwrap_or(false);

    let image = {
      let data = self.tiff.find_ifds_with_tag(TiffCommonTag::PanaOffsets);
      if !data.is_empty() {
        let raw = data[0];
        width = fetch_tiff_tag!(raw, TiffCommonTag::PanaWidth).force_usize(0);
        height = fetch_tiff_tag!(raw, TiffCommonTag::PanaLength).force_usize(0);
        let offset = fetch_tiff_tag!(raw, TiffCommonTag::PanaOffsets).force_usize(0);
        //let size = fetch_tiff_tag!(raw, TiffCommonTag::StripByteCounts).force_usize(0);
        log::debug!("PanaOffset: {}", offset);
        // Filmroom: validate fixed storage before decoding and borrow it. No full-file
        // padding clone, including during the metadata-only probe.
        if raw_format!=8 {let needed = bounded_payload_bytes(width, height, raw_format, bps)?;
        file.subview(offset as u64, needed as u64)?;}
        let src = file.subview_until_eof(offset as u64)?;
        if raw_format==8 {v8decompressor::decode_panasonic_v8_cancel(file,width,height,bps,raw,dummy,cancel)?}
        else {Rw2Decoder::decode_panasonic(file, &src, width, height, split, raw_format, bps, raw, dummy)?}
      } else {
        let raw = self
          .tiff
          .find_first_ifd_with_tag(TiffCommonTag::StripOffsets)
          .ok_or_else(|| RawlerError::DecoderFailed(format!("Failed to find a IFD with StripOffsets tag")))?;
        width = fetch_tiff_tag!(raw, TiffCommonTag::PanaWidth).force_usize(0);
        height = fetch_tiff_tag!(raw, TiffCommonTag::PanaLength).force_usize(0);
        let offset = fetch_tiff_tag!(raw, TiffCommonTag::StripOffsets).force_usize(0);
        //let size = fetch_tiff_tag!(raw, TiffCommonTag::StripByteCounts).force_usize(0);
        log::debug!("StripOffset: {}", offset);
        let src = file.subview_until_eof_padded(offset as u64)?; // TODO add size and check all samples

        if src.len() >= width * height * 2 {
          decode_12le_unpacked_left_aligned(&src, width, height, dummy)
        } else if src.len() >= width * height * 3 / 2 {
          decode_12le_wcontrol(&src, width, height, dummy)
        } else {
          if raw_format==8 {v8decompressor::decode_panasonic_v8_cancel(file,width,height,bps,raw,dummy,cancel)?}
        else {Rw2Decoder::decode_panasonic(file, &src, width, height, split, raw_format, bps, raw, dummy)?}
        }
      }
    };

    log::debug!(
      "RW2 raw: {}, compression: {}, bps: {}, width: {}, height: {}, multishot: {}",
      raw_format,
      compression,
      bps,
      width,
      height,
      multishot
    );

    let cpp = 1;
    let mut blacklevel = self.get_blacklevel()?;
    let mut camera = self.camera.clone();
    if let Some(cfa) = self.get_cfa()? {
      camera.cfa = cfa;
    }
    let whitelevel = if raw_format==8 {
      // CF2 modes on the same camera can be 12/14/16-bit. Static profile white
      // points are not sufficient: preserve the encoded channel calibration.
      let mut whites=[0u32;3];let mut blacks=[0u16;3];
      for c in 0..3 {
        let white=raw.get_entry(0x000eu16+c as u16).ok_or("Missing CF2 white level")?;
        let black=raw.get_entry(0x001cu16+c as u16).ok_or("Missing CF2 black level")?;
        if white.value_type()!=3 || white.count()!=1 || black.value_type()!=3 || black.count()!=1 {return Err("Invalid CF2 level types".into());}
        whites[c]=v8decompressor::calibrated_white(white.force_u16(0));blacks[c]=black.force_u16(0);
        if whites[c]<=blacks[c] as u32 {return Err("Invalid CF2 black/white levels".into());}
      }
      let mut wbayer=vec![0u32;4];let mut bbayer=[0u16;4];
      for row in 0..2 {for col in 0..2 {let c=camera.cfa.color_at(row,col);if c>2{return Err("Invalid CF2 CFA".into());}wbayer[row*2+col]=whites[c];bbayer[row*2+col]=blacks[c];}}
      blacklevel=Some(BlackLevel::new(&bbayer,2,2,1));
      camera.whitelevel=None;camera.blacklevel=None;
      Some(crate::rawimage::WhiteLevel::new(wbayer))
    } else {None};
    let photometric = RawPhotometricInterpretation::Cfa(CFAConfig::new_from_camera(&camera));
    let mut img = RawImage::new(camera, image, cpp, normalize_wb(self.get_wb()?), photometric, blacklevel, whitelevel, dummy);

    if let Some(area) = self.get_active_area()? {
      img.active_area = Some(area);
      img.crop_area = Some(area);
    } else if let Some(area) = self.get_crop()? {
      img.crop_area = Some(area);
    }

    img.source_ifd_offset = Some(raw.offset);
    Ok(img)
  }

}
