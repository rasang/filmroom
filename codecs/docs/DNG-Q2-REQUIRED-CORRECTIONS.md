# Required CFA corrections and A/D50 calibration

This document records the earlier Q2 single-warp implementation and its SDK
validation. The current supported geometry scope is extended by
[Embedded lens distortion correction](EMBEDDED-LENS-CORRECTIONS.md), including
ordered supported List3 geometry/GainMap chains. The historical measurements
below remain specific to the original cases; they do not certify the later
fisheye, mixed-operation or manufacturer extensions.

This is a bounded structural extension, not a blanket Leica or DNG compatibility claim. It was exercised using public raw.pixls.us sample 3204 (Leica Q2, 14-bit uncompressed DNG). The original file is not modified; its required operations are executed rather than removed, made optional, or replaced by embedded JPEG previews.

## Supported extension

- One required List1 FixBadPixelsConstant, in original integer CFA codes. Immutable same-color neighbors, BayerPhase, repeat-2 boundaries, integer rounding, and ordered preceding/following scalar operations are preserved. The initial storage envelope is uncompressed CFA strips; unsupported compressed/tiled combinations still fail at probe.
- One required nonidentity List3 WarpRectilinear, one or three parameter sets, finite bounded coefficients and unit pixel aspect ratio. It acts on the complete active, demosaiced **camera RGB** image before color mapping, crop and orientation. Bicubic phase quantization, whole-footprint borders, per-plane distortion and clipping follow the validated numerical contract. Required mixed List3 operations remain rejected instead of reordered or skipped.
- Three-channel CFA A/D50 ColorMatrix-only calibration, with convergent neutral-white iteration and Bradford adaptation. No light-source tag is relabelled as D65 and no ForwardMatrix is invented. Decoder admission retains unit AnalogBalance, supported positive diagonal CameraCalibration and existing geometry limits. Unsupported forward/reduction/third-calibration/profile combinations and required List3 GainMap combinations fail closed. The standalone matrix resolver has a wider positive-AnalogBalance mathematical envelope; that does not expand decoder admission.
- Existing D65 and LinearRaw/profile paths are unchanged. Explicit D65 LONG-valued legacy tags retain their previous admission behavior. Original unknown mandatory opcodes remain rejected.

## Resource behavior

Five integer sensor rows cover bad-pixel neighborhoods. Camera-space RGB is written to an anonymous, actually reserved disk file; geometric tiles read bounded source windows rather than allocating a full float image. Default crop/orientation are applied only at final emission. Reservations include both output and camera backing store and the bounded extra scratch. Cancellation, allocation errors and writes release both the anonymous backing store and incomplete output.

For sample 3204, the encoded sensor is 8424×5632 (47,443,968 pixels), active area 8392×5632, and output 8368×5584. Output plus camera backing store require 1,127,887,872 bytes, approximately 1.05 GiB, in addition to an already-copied input. The CLI default 1 GiB disk allowance therefore correctly rejects it; Android JNI passes actual available disk minus its safety reserve. No budget is silently increased in production.

A host run completed in 26.46 seconds under a 128 MiB codec allowance and enforced 512 MiB virtual-address cap; measured peak RSS was 25.2 MiB. This is one host measurement, not a device performance or universal memory guarantee.

## Validation scope

- 60 independent SDK opcode cases / 4,439,613 scalar samples are bit-identical, covering four CFA phases, marker values, dense/corner defects, radial/tangential/combined/per-plane/identity warps, edge conditions, off-center transforms and SDK tile boundaries.
- All 47,443,968 original Q2 integer samples match independently decoded SDK samples before and after List1 processing. This particular image contains no zero marker values, so real-sample changed count is zero; synthetic cases exercise actual repairs.
- Actual Q2 calibration and convergent synthetic A/D50 whites match the independent SDK within 3e-14 matrix error. Nonconvergent whites are rejected, not approximated by averaging final iterates.
- Full-frame geometry/calibration/crop comparison uses the **same demosaic input** in the independent SDK and Filmroom. Of 140,180,736 final RGB values, one differs by one f32 ULP (4.55e-13 absolute). Its camera-space warp values are identical; the residual is attributable solely to double-precision matrix rounding. This is not an independent demosaicer or manufacturer-rendering comparison.
- Integrated tests cover repaired strip seams, required-operation rejection, memory/disk admission, nonzero active-area origin, asymmetric per-plane warp in all eight orientations, batch equivalence and finite output.
- Real-source cancellations before camera completion and after warped output writing begins leave no output, no named temporary file, and unchanged FD count. Injected camera reservation and write ENOSPC failures have the same cleanup guarantees.

Independent SDK executables and public full-sized source images remain external test inputs, not product dependencies or committed test assets. Physical Android-device rendering remains a separate verification step.
