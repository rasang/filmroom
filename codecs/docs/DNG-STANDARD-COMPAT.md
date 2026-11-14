# DNG standard compatibility: bounded implementation

This change follows the Adobe DNG 1.7.1 field semantics. It does not claim a full
DNG renderer or a complete Adobe camera-profile appearance match.

## Admission vs development

Descriptor fields are not pixel operations. Valid copyright, preview provenance,
and digest fields do not reject the image; data are not logged, and no integrity
verification is claimed. Explicit rendering-default hints are handled like their
implicit default. Invalid field shapes still fail.

The nonstandard SDR tone-endpoint compatibility rule is structural, not based on
Make/Model. The curve remains unapplied with warnings. Standard SDR endpoints
are still distinguished from the narrow nonstandard tolerance. Header/byte-count,
finite range and increasing-x validation remain.

## Color

The complete color path handles single and dual calibration using the standard
EXIF illuminants with nominal temperatures defined by the Adobe reference model.
It supports ColorMatrix-only and ForwardMatrix conversion, full invertible
CameraCalibration, AnalogBalance, and explicit AsShotWhiteXY. A dual profile
with only one ForwardMatrix remains unsupported rather than silently selecting
another algorithm. Unknown/custom illuminants and third illuminants remain out
of scope. Existing unit-calibration D65 legacy paths retain their pixel pipeline.

AsShotNeutral accepts SHORT and RATIONAL; its absolute scale is not a rejection
criterion. Full paths normalize the neutral, validate the resulting transform,
and do not repeat legacy white balance. Supplying both Neutral and WhiteXY is
rejected. LinearRAW can use complete ColorMatrix-only calibration as well.

## Spatial black level

BlackLevel repeat patterns and signed row/column deltas are evaluated relative
to ActiveArea. Normalization uses the maximum computed black for each sample
plane, as specified by DNG, not a different denominator for each CFA phase.
This corrects existing nonuniform CFA black-level output. Uniform existing
inputs retain their f32 arithmetic; over-white headroom policy is unchanged.
Delta storage is bounded by image dimensions and included in scratch admission.

## Required List1 MapTable

MapTable runs in its declared position in the existing stage-1 chain, with
validated area, pitch and table length. Indexes beyond a short table use its last
entry, verified against the current official SDK implementation. TIFF tag and
aggregate metadata budgets still apply. This is not support for every opcode.

## GainMap

RGB subplanes and positive pitch apply only to the selected area lattice, anchored
at Top/Left. Map sample channel follows absolute image plane capped at the last
map plane, matching the reference model. This does not remove ActiveArea,
profile-development or color-pipeline composition restrictions.

## Tests and limitations

Tests cover untouched pixels for descriptive metadata/explicit default hints,
SHORT-vs-RATIONAL neutral equivalence, explicit XY-vs-equivalent neutral through
actual Bayer and LinearRAW decoding (including channel-mixing CC), matrix
identities/reference vectors, GainMap affine numeric oracles and nonaligned tile
partition equality, resource classification, malformed data and cancellation.

Android native compilation, APK packaging and physical-device validation are
separate stages and are not claimed by host tests. Private sample images and
derived pixels must never be committed. No new image/GPS metadata logging is added.

## Remaining staged work

- Unknown/third/custom illuminants, partial dual ForwardMatrix profiles,
  ReductionMatrix and richer camera profiles.
- Non-unit DefaultScale with spatial ProfileGainTableMap.
- More required opcodes and combinations of currently supported operators.
- Wider DNG storage layouts/precision/compression and budget-aware streaming.

Each requires genuine computation plus numeric regression; removing a rejection
alone is not an implementation.

## Independent reference check (host)

Adobe DNG SDK 1.7.1 Build2802 Stage2 was executed separately, not linked into the
app. Full original Xiaomi and vivo samples had maximum absolute differences of
5.96e-8 and 7.66e-6 respectively. A synthetic spatial black/delta case differed
by at most 7.57e-6. SDK uint16 staging quantizes values, unlike Filmroom floats.
Public Pixel2XL with List2 GainMap differed by at most 3.98e-5; isolated highlight
differences are explained by Filmroom retaining over-white headroom while SDK
Stage2 saturates, not by an incorrect denominator. No exact-match claim is made.
Required MapTable region/pitch/short-tail and ordered two-table cases were
byte-identical to actual SDK Stage1 output. Test adapters run outside the app;
no Adobe SDK source, private sample or derived image is committed.

## Fractional default crop (DNG_STD_V4)

Valid fractional DefaultCropOrigin/DefaultCropSize use Adobe SDK 1.7.1
DefaultCropArea integer rounding: validate the original active-relative range,
round origin and size separately (positive half values upward), then shift the
rounded crop back inside ActiveArea if rounding crosses its right/bottom edge.
Only then translate into sensor coordinates. Integer crops retain the existing
pixel path. This is SDK-equivalent integer crop selection, not subpixel sampling.
A positive size rounding to zero is explicitly unsupported, never widened to one
pixel. DefaultScale support follows below.


## DefaultScale (DNG_STD_V5)

Non-unit DefaultScale is developed in camera RGB after supported List3 operations
and before color calibration. The rounded source crop selects the mapping;
target dimensions are independently rounded from the original rational crop size
multiplied by DefaultScale, not from the rounded crop. Orientation is applied once.
Full ActiveArea disk backings preserve pixels outside the crop needed by the
filter. The independent cubic-convolution implementation uses A=-0.75, 128
subpixel phases and expanded support on downsampling. Both separable passes
clamp to [0,1], matching actual SDK ResampleImage float output. If source crop
and target dimensions match, resizing is bypassed just as in SDK rendering.

Uncompressed, tiled/strip SOF3 CFA, streaming Linear SOF3 and Linear JPEG XL share
the same final camera-space resampling route. Streaming Linear SOF3 is first
written as exact uint16 samples to an anonymous disk backing. Admission includes
source spools, camera ping-pong backings, output bytes, phase weights and row
buffers; cancellation and failure do not retain partial output. No resolution
fallback is applied to evade a budget limit.

Non-unit DefaultScale with ProfileGainTableMap is explicitly unsupported: the
SDK rendering coordinate behavior after resize needs a separate spatial PGT
oracle. It is not silently ignored or evaluated at guessed coordinates.

Host validation includes an external SDK ResampleImage oracle (100 randomized
cases and 7 fixed cases, all bit-identical float32), stored SDK golden values,
codec-storage equivalence, eight orientations, fractional crop sizing, budget
and cancellation checks. This is not Android/physical-device verification.
