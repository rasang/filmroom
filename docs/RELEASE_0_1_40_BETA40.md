# 0.1.40-beta40 (versionCode 92)

- Fix beta39 distribution missing the three effect audio assets, which caused silent previews and failed exports.
- Require approved audio input hashes before packing, and validate the actual APK contains each asset uncompressed and unchanged. Missing, altered, or compressed assets fail the build.
- Add public device manufacturer/model and OS labels to shared diagnostics; no serial, IMEI or advertising identifier.
- Retain beta39's vivo/Oplus/Honor alignment and other dev changes. No homepage Live preview experiment is included.

Pre-versioning validation: 195 targeted host tests, 126 preset gate tests, lintAnalyzeDebug, Debug assembly and actual Debug audio validation passed. Release packaging reruns the aggregate gate and checks all three audio assets. No physical-device playback or gallery acceptance is claimed.

Distribution: dev.filmroom, Android 10+, arm64-v8a, original release certificate; pre-release only. Audio binaries remain private build inputs, not source-control files.

Final distribution validation: Release assembly and its 126-test gate passed. The final repacked, aligned and signed APK passed verify-package.py: all three audio entries are STORED and match the approved SHA256SUMS. ZIP integrity, original certificate, ARM64-only payload and 16 KiB ZIP alignment passed. Both native-library hashes match beta38/beta39.
APK SHA-256: `db5755323fc99221315558825d2fc286bd2b7c2241307be5fa5bd0b4d73e6dc9` (30,219,333 bytes).
