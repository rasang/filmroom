# Capture identity for Lensfun

`capture_identity(file, input_bytes, format, raw, metadata)` is the shared owned
metadata entrance. The adapter supplies the already selected `RawImage` and an
optional `decoder.raw_metadata()` result from the same source inside its existing
rawler metadata budget and panic boundary. An optional metadata failure must not
become a RAW decoding failure.

The returned `CaptureMetadata` contains a status, reason, optional identity and
source evidence. `Ready` means camera identity and actual focal length are
consistent. It may deliberately have no lens name: only the Lensfun camera
database may establish that such a camera has a fixed lens. Interchangeable lens
cameras with no lens identity must not acquire a lens by camera name or focal
length alone. Profile selection, processing state, crop qualification and
per-item embedded correction priority remain adapter responsibilities.

## Provenance and ambiguity

- Camera make/model and sensor/active/crop rectangles are bound to the decoded
  source. Their physical crop factor is never inferred from pixel dimensions.
- Original EXIF names, actual focal length, optional FNumber and optional
  SubjectDistance are read separately. RAF EXIF uses its documented embedded
  TIFF position; CR3 uses its CMT1/CMT2/CMT3 containers. Classic TIFF covers
  CR2, NEF, ARW, ORF, PEF, RW2 and DNG headers.
- `RawMetadata::new_with_lens()` overwrites its EXIF lens name/make/specification
  with the rawler database description. Such values are labelled
  `RawlerDatabase`, never original EXIF. The existing rawler ID/decryption
  implementations are reused. All entries sharing the resolved identifier and
  mount are considered; known conflicts or multiple remaining optical models
  prevent automatic selection.
- Canon is independently cross-checked against its actual CameraSettings lens
  ID, RF FileInfo sub-ID and both available on-file names. Name-first database
  resolution cannot override a contradictory ID. The RF16 288-to-289 control
  remains rejected even if rawler resolves the RF16 name first.
- Optical accessory names are retained intact. A declared converter/extender
  cannot be reduced to a base-lens alias. On EOS R cameras, Canon FileInfo[26]
  values 5/6 explicitly identify RF1.4x/RF2x extenders; an incomplete combination
  identity is rejected even if its focal length fits the base lens's range.
  No accessory marker does not prove physical absence of an accessory.
- Missing or malformed aperture/distance affects only those optional capture
  fields. It does not erase a valid identity/focal length for distortion or TCA.
  EXIF SubjectDistance is retained as its recorded nominal distance; Canon
  FileInfo preserves the near/far interval and explicit infinity marker.

## Bounds and source safety

The additional file reader consumes at most 32 KiB, 4 KiB per read, 256 CR3 boxes
and 256 entries per selected TIFF directory. DNG conversion-marker inspection
visits at most 16 image/EXIF directories through SubIFD, EXIF and next pointers.
It never copies image payloads,
previews or complete MakerNote blobs. Canon arrays are read only through the
established prefix after their full extent is checked. Duplicate selected
identity tags, conflicting values, unsupported layouts and over-budget reads
fail conservatively. The original cursor is restored; the source is never
modified. Source evidence excludes serial numbers, GPS, author and timestamps.

`SourceEvidence.is_dng`, `source_is_cfa`, software, original names and rectangles
are evidence for the caller, not authorization to correct a converted DNG or a
linear/developed image. Existing embedded correction terms must retain priority
independently for distortion, TCA and vignetting.

`converted_dng_marker` records OriginalRawFileName (50827), OriginalRawFileData
(50828), or an Adobe signature in DNGPrivateData (50740). The first two need only
tag presence, regardless of payload size/readability. The private-data reader
validates the declared extent and reads at most the six-byte `Adobe\0` prefix.
An incomplete directory traversal, unreadable signature or exceeded budget
returns no identity rather than inferring that conversion markers are absent.
Native private data with another signature is recorded separately. These
markers augment the adapter's positive original-firmware and CFA checks; their
absence alone never establishes an original unprocessed camera capture.

## Reference implementations and tests

The pinned `vendor/rawler-0.7.2/src/decoders/mod.rs`, `lens.rs`, `exif.rs` and
individual decoders define the metadata and resolver behavior reused here.
Canon array fields use the already audited
[ExifTool Canon.pm revision](https://github.com/exiftool/exiftool/blob/1da714924f67763b6a8a7013dd2aa1983a16f87b/lib/Image/ExifTool/Canon.pm),
including FileInfo indices 20/21 (focus interval), 26 (EOS R extender) and 61
(RF lens type). No proprietary correction coefficient is inferred from a switch.

`capture_tests.rs` uses anonymous synthetic metadata and the pinned rawler lens
database. It covers original names, absent optional parameters, duplicate tags
and containers, conflicts, short arrays, reading limits, ambiguous IDs,
accessory preservation, native DNG evidence and format-specific offsets. The
old exact RF16 reader is compiled only in tests as an additional historical
regression oracle, with its existing opt-in public-RAW test preserved.
