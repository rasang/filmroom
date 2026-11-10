# 0.1.39-beta39 (versionCode 91)

- Align vivo single-file terminal video extension and XMP video extent with the inspected Mimo SDK framing. Do not claim DJI identity or proprietary SDK version.
- Align OPPO-family clean-input metadata defaults and use the actual application package owner; preserve actual video extent in the Container item.
- Align Honor's three fixed-size fields with the inspected Mimo caller defaults. Do not infer unresolved time units.
- Includes Live capture EXIF preservation, vivo model case normalization, and the history effects Beta badge already on dev.
- Samsung SEF implementation is unchanged by this alignment. This does not certify gallery recognition on any device.

Integrated validation before versioning: 44 selected Android host tests, 126 preset gate tests, lintAnalyzeDebug and ARM64 Debug assembly passed. Release packaging reruns the aggregate gate. No physical-device gallery acceptance is claimed. Native engine/codec binaries are unchanged from beta38; provenance and hashes are recorded in MIMO_ALIGNMENT_VALIDATION.md.

Distribution: dev.filmroom, Android 10+, arm64-v8a, existing Filmroom release certificate. Pre-release only; do not replace the stable release.

Release validation: assembleRelease succeeded, its 126-test gate passed with zero failures/errors/skips; APK signature, ZIP integrity, ARM64-only payload and 16 KiB zipalign checks passed. Release native hashes match the documented unchanged beta38 libraries.
APK SHA-256: `da58f1299ca4c970b238ce0658a75b6d0639ea95bfa6b5b671d59d4d19902ad7` (30,071,551 bytes).
