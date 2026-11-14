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

The complete color path handles full invertible CameraCalibration matrices in
the existing A/D50 ColorMatrix-only and A/D65 dual-ForwardMatrix envelopes.
It interpolates ColorMatrix and CameraCalibration separately, then combines them
with AnalogBalance. ForwardMatrix conversion uses the inverse individual-camera
calibration, never a second application of AB to sensor pixels.

AsShotWhiteXY directly sets the reference white in these envelopes. AsShotNeutral
accepts SHORT and RATIONAL. Supplying both is rejected. Legacy unit-AB diagonal
development retains its previous pipeline; full paths do not use its WB/matrix.

LinearRAW requiring AB/channel-mixing/XY selects the complete transform rather
than passing the value through unused. Unsupported single/other illuminants,
missing optional FM needed by this implementation, or unsupported required
processing remain UNSUPPORTED, not silently guessed.

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

- General/third/custom illuminants, ReductionMatrix and richer camera profiles.
- DefaultScale, fractional resampling, spatial black-level processing.
- More required opcodes and combinations of currently supported operators.
- Wider DNG storage layouts/precision/compression and budget-aware streaming.

Each requires genuine computation plus numeric regression; removing a rejection
alone is not an implementation.
