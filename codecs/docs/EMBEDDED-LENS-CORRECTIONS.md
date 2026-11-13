# Embedded lens distortion correction

This change extends DNG stage-three correction and adds file-embedded distortion
models for compatible Sony ARW, Fujifilm RAF and Olympus / OM System ORF files.
Correction is selected from validated file metadata, not from the extension or
a hard-coded camera/lens name. It runs locally and does not download profiles.

## Supported scope

| Input | Metadata / model | Scope |
| --- | --- | --- |
| DNG CFA | `OpcodeList3`: `WarpRectilinear` (1), `WarpFisheye` (2), supported `GainMap` (9) | Optional and required supported operations execute in record order. Geometry is applied to demosaiced camera RGB before the color transform and final crop/orientation. |
| Sony ARW | Raw-IFD `DistortionCorrParams` (`0x7037`) | Bounded signed node array with the declared node count; piecewise radial scaling. Fixed-size trailing entries are not additional nodes. |
| Fujifilm RAF | FujiIFD `GeometricDistortionParams` (`0xf00b`) | The known 11-node and 9-node layouts; inversion of the stored radius mapping for destination-to-source sampling, with supported crop metadata. |
| Olympus / OM System ORF | MakerNote ImageProcessing `0x150a` | Four finite parameters describing the radial polynomial and radius scale. The separate correction-setting switch is not treated as the coefficients. |

The manufacturer paths in this change apply **geometric distortion only**.
They do not add manufacturer chromatic-aberration or vignetting correction.
An ORI is handled by the existing ORF decoder, but this does not establish that
every ORI contains a usable correction model. Missing parameters do not imply
that a lens is distortion-free.

DNG support remains bounded. `WarpRectilinear2` (14), unknown opcodes, unsupported
versions or layouts are not newly supported. Required unsupported operations
still fail with `UNSUPPORTED_DNG_OPCODE`. Optional unsupported operations are
skipped with the existing optional-correction notice. LinearRaw / profile routes
outside the supported geometry envelope keep their optional-operation fallback;
they do not silently accept a required geometric operation. Damaged opcode
containers remain input errors.

Supported no-op models avoid an unnecessary interpolation pass. Their parameters
can be recognized and accepted while the pixels stay unchanged. The presence of
a correction tag alone is never sufficient to mark an unsupported model applied.

## Pipeline and resource contract

The adapter reads and validates the model during RAW metadata admission. A
non-identity model retains the complete active camera-RGB image in a disk-backed
temporary file. Bounded destination tiles read only their source window, then
the normal color conversion and final crop/orientation produce the existing
linear-sRGB float32 output.

This preserves the sensor/active/default-crop distinction: pixels outside the
final crop may still be needed by the geometric resampling. Orientation is
applied once at emission. Manufacturer coordinate mapping and DNG opcode
ordering remain explicit instead of forcing all formats into one coefficient
layout.

For manufacturer models, Filmroom uses Sony's validated active image (including
`SonyRawImageSize` when present) as the radius/center reference. Fujifilm and
Olympus use the file's default crop as their visible reference rectangle. The
full active image is still retained for demosaic neighborhoods and resampling.
These are explicit Filmroom adapter conventions: open-source processors can
use different visible crops and demosaic borders, so matching their coefficients
does not establish identical pixel coordinates or identical framing.

Manufacturer correction also applies a conservative automatic zoom that keeps
the corrected visible image inside the source rectangle. This avoids filling
its edges with repeated clamped pixels when positive distortion parameters map
outward. Radius-dependent factors are evaluated after the zoom changes the
sampling radius. DNG operations retain their specification-defined coordinate
scale and do not inherit this manufacturer automatic zoom.

The existing 52-million sensor-pixel cap remains. Geometry scratch memory and
the extra camera-RGB backing file must be included in admission; a source
integer spool is also charged while it remains live. Insufficient disk/memory,
cancellation and decoding errors must fail without publishing a partial output.
The implementation does not allocate another full-frame RGB array in RAM.

The camera-RGB backing adds 12 bytes per active pixel to temporary disk usage,
in addition to the final float output and any decoded integer spool. Large
admitted images can therefore need more than 1 GiB in total. The geometry stage
checks this sum against the caller's actual disk budget; the sensor decoders'
existing per-phase limits remain in place. Backing files are preallocated and
unlinked immediately so error/cancellation paths do not leave named temporary
files behind.

## Photo information messages

The native response adds two exact, allowlisted codes:

| Code | Chinese photo-information text |
| --- | --- |
| `EMBEDDED_LENS_DISTORTION_APPLIED` | 已应用内嵌镜头畸变校正。 |
| `EMBEDDED_LENS_DISTORTION_NOT_APPLIED` | 检测到内嵌镜头畸变参数但未应用，可能保留畸变。 |

The applied code covers an accepted manufacturer model or an executed DNG warp
chain. The not-applied code is used for manufacturer parameters that are present
but unsupported or invalid. Skipped optional DNG operations retain the existing
`DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED` notice, including partial unsupported
chains and optional LinearRaw/profile fallbacks, without a duplicate generic
not-applied message. Unsupported required DNG operations still reject the import.

These use the existing photo-information display and persisted resource keys.
Successful imports do not gain a new automatic toast/dialog. The generic basic
RAW-development notice remains valid: applying distortion does not reproduce
camera color styles or implement every other lens correction. Native free-form
messages and unknown codes are not displayed or persisted. Old metadata without
the new codes retains its previous behavior.

Resource entries are provided in the default Chinese resources and explicit
Chinese, English, Japanese and Korean resources.

## Public regression inputs

The selected originals are CC0 files from raw.pixls.us. Source URLs, exact sizes,
SHA-256 values and independently decoded parameters are recorded in
[`embedded-lens-SOURCES.json`](../tests/public-fixtures/embedded-lens-SOURCES.json).
The large originals are intentionally outside Git.

| Sample ID | Camera / lens | Independently verified embedded metadata |
| --- | --- | --- |
| 6736 | Sony ILCE-6700 / E 16–55mm F2.8 G at 16 mm | Signed 17-element payload, 11 declared nodes, nonzero correction values. |
| 1178 | Fujifilm X-T20 / XF18–55mmF2.8–4 R LM OIS at 50.5 mm | 23 signed rationals: header, 11 radius nodes and 11 distortion percentages; final value 3.837%. |
| 1993 | Olympus E-M1 Mark II / M.Zuiko Digital ED 12–40mm F2.8 Pro | Four float32 values at ImageProcessing `0x150a`, with a non-identity polynomial. |

## Executed validation

The final-source Linux native suite passed **250 tests, with zero failures and
13 explicit opt-in ignores** across 39 test suites. The three additional opt-in
real-file metadata tests also passed. Seven new shared-sink tests cover nonzero geometry with two
reference rectangles, a shifted active origin, a smaller final crop, all eight
EXIF orientations and three strip sizes. They also cover Bayer/X-Trans halo
equivalence, independently decoded RGB tiles, identity/unsupported bypass,
color/exposure ordering, exact disk/memory admission boundaries and cancellation
that releases both the anonymous backing descriptor and partial output.

All three original public files completed full-resolution release decoding
under the 256 MiB codec allowance and reported
`EMBEDDED_LENS_DISTORTION_APPLIED`:

| Sample | Output | Float32 output bytes | Required temporary disk bytes |
| --- | --- | ---: | ---: |
| Sony 6736 | 6192 × 4128 | 306,726,912 | 680,368,512 |
| Fujifilm 1178 | 6000 × 4000 | 288,000,000 | 581,061,888 |
| Olympus 1993 | 5184 × 3888 | 241,864,704 | 528,849,024 |

An independent chunked scan checked all **209,147,904 float values**: every value
was finite, file lengths exactly matched the declared dimensions, and output
SHA-256 values matched the decode report. The source RAW hashes remained
unchanged. Exact output hashes are included in the source manifest. The three
rendered previews were inspected for obvious strip seams and repeated edge
bands; none were observed. This visual check is not an optical calibration or
camera-JPEG color comparison.

Controlled copies of the Sony and Fujifilm files, with only the distortion
metadata changed to identity, produced different full-frame output hashes from
their corrected originals. A Sony copy with unsupported distortion metadata
decoded successfully, reported the new not-applied status, and produced
exactly the same pixel hash as the identity control. Both controls omitted the
extra camera-RGB disk charge, as did the Fuji identity control. This checks that actual pixels are corrected and
that unsupported optional manufacturer parameters preserve the existing image
development path. The original public files were not altered.

A real Sony cancellation after output writing began returned `CANCELLED`, left
no partial output or named camera temporary file, and kept the process file
descriptor count unchanged at four. The unit tests separately verify that an
anonymous camera backing descriptor closes on cancellation. Fresh independent
RAF/ORF decode runs reproduced the original corrected output hashes; performance
and resource measurements from a Linux host do not establish Android behavior.

The five resource XML files parse correctly and contain each new key once.
Three Android host tests were added for strict code admission, coexistence,
deduplication, persistence and locale changes. **They were not run in this
workspace:** it lacks an Android host toolchain and the private audio assets
required by the project's normal test prerequisites. No prerequisite was
disabled. Android build/device rendering and physical-device performance are
not claimed.

The new fisheye checks use the public DNG specification, closed-form point
references and continuous derivative bounds. They do not add Adobe SDK oracle
measurements. The earlier Q2 SDK comparisons remain limited to the cases in
the [Q2 validation record](DNG-Q2-REQUIRED-CORRECTIONS.md). Passing these samples
does not establish compatibility with every camera, lens or encoding mode.

## Implementation references

- [Adobe DNG specification and SDK](https://www.adobe.com/support/downloads/dng/dng_sdk.html)
- [RawTherapee lens metadata implementation, revision 64c2d90](https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/lensmetadata.cc)
- [darktable metadata extraction, revision 78f66f8](https://github.com/darktable-org/darktable/blob/78f66f802183c6d241656f82b03ce6c10c1724d8/src/common/exif.cc)
- [darktable lens coordinate mapping, revision 78f66f8](https://github.com/darktable-org/darktable/blob/78f66f802183c6d241656f82b03ce6c10c1724d8/src/iop/lens.cc)

These open implementations are engineering references. Manufacturer metadata
layouts are not presented as complete manufacturer-published specifications.
