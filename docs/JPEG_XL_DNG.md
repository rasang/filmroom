# JPEG XL DNG import

This path decodes a DNG main image, never its embedded JPEG preview. It is not a
Samsung model allowlist or a promise that every file with a .DNG suffix works.

## Supported storage contract

- DNG 1.7, Compression 52546, unsigned 16-bit, three-channel LinearRaw strips.
- Main-image TIFF geometry must match each JPEG XL strip exactly. Multiple strips
  are decoded independently; tiled JPEG XL, CFA, floating point, other bit depths,
  alpha/extra channels, animation and nonidentity embedded JPEG XL orientation
  are explicitly rejected. TIFF orientation and active-area/crop are still applied.
- The calibrated LinearRaw path requires valid paired illuminants, ColorMatrix /
  ForwardMatrix entries and AsShotNeutral, and validates any supplied gain table.
- JPEG XL must preserve the original profile, with equal supported structured RGB
  ORIGINAL/DATA profiles. No CMS, requested color profile, tone map, spot color,
  unpremultiplication, progressive partial image, or rendered preview is used.
- Official unmodified libjxl 0.12.0 handles the encoded bitstream. Pinned archives,
  licenses, dependency versions and offline build instructions are in
  `third-party/libjxl`. An old system libjxl is not a shipping dependency.

Exact uint16 output is stored in an anonymous, preallocated private temporary
file because JPEG XL pixel callbacks can arrive out of scanline order. Once the
JPEG XL decoder is destroyed, the existing bounded DNG strips perform black/white
normalization, white balance, dual-illuminant ColorMatrix/ForwardMatrix calibration,
exposure and ProfileGainTableMap processing, then oriented linear-sRGB output.
Existing required-opcode and calibration rejection policies remain active.
Optional lens correction warnings are unchanged, not silently removed.

## Resource contract

Existing ordinary DNG remains capped at 128 MiB and other camera RAW at 256 MiB.
Only a bounded TIFF content check finding a non-preview LinearRaw main IFD with
Compression 52546 can request up to 384 MiB. The application's memory policy can
supply less; the existing system-memory/concurrency reservation must still grant
it. Native admission can reject the image even at 384 MiB. This is not an Android
whole-process RSS guarantee or a blanket increase for all photos.

libjxl has a custom checked allocator. Encoded input and allocation headers count
against its hard cap. The native estimate includes padded full decoder planes,
32 MiB overhead, DNG tables, the existing base allowance and development strips.
Do not use the low observed RSS of one file to remove the full-plane allocation
reservation: upstream temporarily allocates full modular planes even when it later
uses group-wise output. Larger images or uncommon transform configurations can
fail closed with MEMORY_BUDGET. The 52 MP outer guard is unchanged.

Disk admission includes both the uint16 intermediate (6 bytes/pixel) and final
float RGB (12 bytes/output pixel). Both receive real block reservations. The
intermediate is immediately unlinked and its descriptor closes on every return;
the ordinary output guard removes partial final output on failure or cancellation.
The existing target is never overwritten. Cancellation is checked during input,
allocation, callback output, strip development and final emission.

## Build and validation

Build `third-party/libjxl/build.sh host` and set FILM_JXL_PREFIX to its reported
prefix before Cargo host builds. `codecs/build-android.sh` builds each target's
pinned dependency before the Rust codec. Use NDK r27c or newer and verify final
native libraries and APK zip alignment for 16 KiB pages. APK license text includes
libjxl, Brotli, Highway and skcms notices.

A tested Galaxy S25 Ultra DNG with 24,470,208 RGB pixels decoded exactly to the Adobe SDK 2768/libjxl storage
reference, including agreement with a separate libjxl 0.11.2 diagnostic run.
The Adobe SDK independently exercised DNG normalization, calibrated color and
profile gain processing; JPEG XL decoding itself shares the official libjxl backend.
All 73,410,624 developed samples passed the unchanged 1e-6 absolute numerical gate.
The maximum observed difference was 1.1920928955078125e-7, with no nonfinite values.
Reference input photographs and Adobe SDK code are not included in the application.

Synthetic regression covers full uint16 endpoints, TIFF orientation/crop, multiple
strips, malformed/truncated data, geometry mismatch, unsupported layouts, budget
failures, cancellation, write failure and existing-output protection. Android
cross-compilation and ELF verification do not constitute Android device testing.
