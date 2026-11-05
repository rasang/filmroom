# Bounded standard DNG opcode support

Admission is by opcode structure and supported processing, never by camera make
or model. This is a supported subset, not complete DNG rendering.

## Implemented operations

- List1, single-plane Bayer integer RAW: `FixVignetteRadial` (ID 3). The five
  radial coefficients and optical center operate on the full encoded sensor
  image, before linearization and black subtraction. Pixel centers, integer
  Stage1 conversion, upper saturation, fixed coordinate arithmetic and bounded
  gain lookup quantization follow the Adobe SDK scalar path. Each operation
  completes, clips and rounds before the next operation. An all-zero polynomial
  is a validated identity. At most four nonidentity operations are retained.
- List2, single-plane CFA: bounded GainMap (ID 9) records, arbitrary in-bounds
  rectangular area and positive row/column pitch, one gain plane, at most four
  maps and 262,144 retained float gains in total. The normalized active image is
  the coordinate system, rebased after ActiveArea and before DefaultCrop and
  orientation. Maps execute in declared order, including overlaps. Values are
  bounded to the stage's logical range; unit gains still execute clipping.
- List3: bounded rectangular, all-three-plane, unit-pitch GainMap records. The
  prior full-ActiveArea requirement remains for nonidentity retained List3 maps.
  At most four maps and 262,144 retained float gains are accepted.
- List1 and List3: exact structural `WarpRectilinear` (ID 1) identities. One
  coefficient set or one per relevant image plane is allowed. All coefficients,
  centers and payload lengths must be valid; any nonidentity warp is rejected.
- These records accept declared opcode versions 1.3.0.0 and 1.4.0.0. Required
  preview-skip flags still execute for full-quality development.

The retained vignette table is shared by cloned descriptors and uses about
128 KiB per operation. The new CFA spool path conservatively accounts for both
metadata descriptors and their gain storage. The 128 MiB default DNG budget,
52-million-pixel limit, full-size output and preallocated disk admission remain.
No full-frame RGB allocation or source-photo mutation is introduced.

## Deliberately rejected or skipped

Required bad-pixel operations, nonidentity geometric warps, unimplemented
operations, unsupported stage/layout/plane combinations and newer opcode
versions remain errors. In particular, the tested Leica CL and X2 originals
still require unimplemented nonidentity warp processing. They are not described
as supported because sensor decoding alone works.

Optional unsupported operations preserve the explicit warning policy. Optional
supported CFA GainMaps are now applied regardless of camera brand. List1 and
List3 retain their prior optional-skip policy. Malformed envelopes fail closed.

Numeric limits reject nonfinite parameters, unbounded spacings, nonpositive or
excessive gain-map samples and vignette curves outside the supported gain-table
range; values are never silently repaired to make a file pass.

## Evidence and test scope

Primary references: Adobe Digital Negative Specification 1.7.1, chapter 7, and
Adobe DNG SDK 1.7.1 build 2768, `dng_lens_correction`, `dng_gain_map`,
`dng_opcodes`, `dng_negative`, `dng_reference`, and `dng_1d_table`.
Official source: https://www.adobe.com/support/downloads/dng/dng_sdk.html
The SDK is an independent host-test oracle only, not a product dependency.

Synthetic tests cover integer-stage ordering, active crop and orientation,
whole-image versus strip/region equivalence, Bayer phase/pitch, overlapping
operations, unit-map clipping, malformed/truncated payloads, unsupported
versions, mandatory unknown operations, coefficient bounds, and cancellation.
Real-sample validation is recorded separately with source hashes and actual
comparison metrics; a probe pass alone is not a pixel/color validation pass.
