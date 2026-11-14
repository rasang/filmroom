//! Exercise the vendored DNG metadata-only contract through its public API.
//! The small malformed inputs prove that probing never touches sensor data.
use rawler::{RawImageData, rawsource::RawSource, decoders::RawDecodeParams,
    rawimage::RawPhotometricInterpretation, imgop::{Rect,Point,Dim2}};

// A real TIFF directory with no pixel buffer, or an explicitly supplied one.
// In particular, the large-image probe never constructs a large test input.
fn source(width: u32, height: u32, compression: u16, pixels: Option<&[u8]>) -> RawSource {
  let short = |value: u16| value.to_le_bytes().to_vec();
  let long = |value: u32| value.to_le_bytes().to_vec();
  let longs = |values: &[u32]| values.iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>();
  let mut entries = vec![
    (256u16, 4u16, 1u32, long(width)),
    (257, 4, 1, long(height)),
    (258, 3, 1, short(16)),
    (259, 3, 1, short(compression)),
    (262, 3, 1, short(32803)),
    (271, 2, 9, b"Filmroom\0".to_vec()),
    (272, 2, 12, b"Dummy Probe\0".to_vec()),
    (273, 4, 1, long(0)), // Patched to the end of the metadata below.
    (274, 3, 1, short(6)),
    (277, 3, 1, short(1)),
    (278, 4, 1, long(height)),
    (279, 4, 1, long(pixels.map_or(width * height * 2, |v| v.len() as u32))),
    (284, 3, 1, short(1)),
    (33421, 3, 2, vec![2, 0, 2, 0]),
    (33422, 1, 4, vec![0, 1, 1, 2]),
    (50706, 1, 4, vec![1, 4, 0, 0]),
    (50714, 3, 1, short(0)),
    (50717, 4, 1, long(65535)),
    (50719, 4, 2, longs(&[1, 2])),
    (50720, 4, 2, longs(&[width - 6, height - 8])),
    (50829, 4, 4, longs(&[2, 2, height - 2, width - 2])),
  ];
  entries.sort_by_key(|entry| entry.0);
  let mut bytes = b"II\x2a\0\x08\0\0\0".to_vec();
  bytes.extend_from_slice(&(entries.len() as u16).to_le_bytes());
  bytes.resize(8 + 2 + entries.len() * 12 + 4, 0);
  let mut strip_offset = 0;
  for (index, (tag, typ, count, value)) in entries.into_iter().enumerate() {
    let at = 10 + index * 12;
    bytes[at..at + 2].copy_from_slice(&tag.to_le_bytes());
    bytes[at + 2..at + 4].copy_from_slice(&typ.to_le_bytes());
    bytes[at + 4..at + 8].copy_from_slice(&count.to_le_bytes());
    if value.len() <= 4 {
      bytes[at + 8..at + 8 + value.len()].copy_from_slice(&value);
    } else {
      let offset = bytes.len() as u32;
      bytes[at + 8..at + 12].copy_from_slice(&offset.to_le_bytes());
      bytes.extend_from_slice(&value);
    }
    if tag == 273 {
      strip_offset = at + 8;
    }
  }
  let pixel_start = bytes.len() as u32;
  bytes[strip_offset..strip_offset + 4].copy_from_slice(&pixel_start.to_le_bytes());
  if let Some(pixels) = pixels {
    bytes.extend_from_slice(pixels);
  }
  RawSource::new_from_slice(&bytes)
}

#[test]
fn dummy_dng_keeps_geometry_without_reading_or_allocating_large_pixels() {
  let source = source(8192, 6144, 1, None);
  assert!(source.len() < 1024);
  let decoder = rawler::get_decoder(&source).unwrap();
  let raw = decoder.raw_image(&source, &RawDecodeParams::default(), true).unwrap();
  assert_eq!((raw.width, raw.height, raw.cpp), (8192, 6144, 1));
  assert_eq!(raw.make, "Filmroom");
  assert_eq!(raw.model, "Dummy Probe");
  assert!(matches!(raw.data, RawImageData::Integer(ref data) if data.is_empty()));
  assert!(matches!(raw.photometric, RawPhotometricInterpretation::Cfa(_)));
  assert_eq!(raw.active_area, Some(Rect::new(Point::new(2, 2), Dim2::new(8188, 6140))));
  assert_eq!(raw.crop_area, Some(Rect::new(Point::new(3, 4), Dim2::new(8186, 6136))));
}

#[test]
fn dummy_dng_does_not_hide_missing_or_corrupt_pixels_from_real_decode() {
  for (compression, pixels) in [(1, None), (7, Some(b"bad!".as_slice()))] {
    let source = source(16, 16, compression, pixels);
    let decoder = rawler::get_decoder(&source).unwrap();
    let dummy = decoder.raw_image(&source, &RawDecodeParams::default(), true).unwrap();
    assert!(matches!(dummy.data, RawImageData::Integer(ref data) if data.is_empty()));
    assert!(decoder.raw_image(&source, &RawDecodeParams::default(), false).is_err());
  }

  // Valid pixels still use the unchanged decoder, with every sample retained.
  let expected: Vec<u16> = (0..256).collect();
  let pixels: Vec<u8> = expected.iter().flat_map(|v| v.to_le_bytes()).collect();
  let source = source(16, 16, 1, Some(&pixels));
  let decoder = rawler::get_decoder(&source).unwrap();
  let decoded = decoder.raw_image(&source, &RawDecodeParams::default(), false).unwrap();
  assert!(matches!(decoded.data, RawImageData::Integer(ref data) if *data == expected));
}
