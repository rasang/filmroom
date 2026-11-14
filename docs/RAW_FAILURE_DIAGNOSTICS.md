# RAW failure diagnostics

This change adds diagnostics, not new RAW support or a workaround for rejected files.
Existing error codes, user messages, admission limits and pixel processing are unchanged.

## Reading a failure

Read the IMPORT operation's ERROR record, not merely the report's last cancelled preview.
`codecStage` is the codec stage; `importPhase` and `importMemoryStage` describe the app boundary.
The new fields are:

- `codecDiagnosticSchema`: version 1, permitting strict protocol validation.

- `codecReason`: a closed validation category, e.g. `MISSING_TAG`, `D65_MATRIX_MISSING`,
  `WHITE_BALANCE_RANGE`, `MATRIX_SINGULAR`, or `COLOR_TRANSFORM`.
- `codecSource`, `codecLine`, `codecColumn`: fixed module identifier and source-code coordinates
  at the rejection. Generic `VALIDATION` still has coordinates for finding the precise predicate.
- `codecTag`: optional allowlisted TIFF/DNG tag identifier, **not its value**.

Coordinates must be resolved against the exact codec sources used to build the installed APK.
The report's film engine build number is not a codec source revision. Release/build records must
retain the APK version-to-source mapping; rebuild the native codec when shipping this change.
Do not reuse an already published APK version for changed code. Old native libraries simply omit
these optional fields; the app must continue to accept those errors.

The source identifiers are not file paths. Native strings, filenames, URIs, exception messages,
GPS coordinates, camera metadata contents, matrices, white balance values and pixel data are not
added to the report. Kotlin validates both incoming native JSON and persisted diagnostic records.

## Boundaries

Source positions identify our rejection/check, including supported DNG color-transform helpers.
They do not reveal missing metadata values or reconstruct a user's original file. Failures inside
third-party decoders can still identify only our boundary when the decoder gives no safe detail.
A new diagnostic schema cannot retroactively identify the exact cause of an old coarse log.

`rawProbeStatus=0` refers to the separate bounded technical-information scan, not successful RAW
import. `rawCalibrationMask=3` means CameraCalibration1 and CameraCalibration2 tags were seen,
not that their values passed validation. TIFF header recognition is expected for DNG.

## Verification

Run the codec Rust library tests with the pinned host libjxl build and the app diagnostic JVM tests.
Tests cover callsite preservation, separate rejection sites, fixed reason/tag transport, safe JSON,
unknown/malformed input rejection and persisted-record round trips. Pixel/decode tests should remain
unchanged and continue to pass. Android-native compilation and device reproduction are separate
checks; a host unit-test pass is not a device import pass.
