# Filmroom 0.1.21-beta1 — RAW compatibility preview

This private arm64-v8a beta is based on official 0.1.20.2 plus RAW commit 8ff45ed5. It retains the Android 17 HEIF property association fix. It does not include later experimental GPU work and is not a public release.

## Changes

- Content-based Canon CR3, Olympus/OM ORF/ORI and Pentax PEF admission and bounded sensor decoding. No embedded-preview fallback.
- Generic 12/14-bit packed and supported lossless-JPEG CFA DNG paths, including validated Ricoh, Pentax and Sigma representatives, with crop/black/white handling, bounded padded-tile work and supported required stage-one opcode processing.
- CR3 capture metadata preservation, source identity checks, cancellation, disk/memory admission and temporary-output cleanup.
- Checked CRX arithmetic and release overflow checks. Existing NEF/CR2/ARW representative outputs remain identical.

## Actual validation

The unchanged RAW implementation passed 182 native tests (8 explicit legacy opt-in ignores), 1060 Android host tests (58 explicit skips; 1118 total), release lint, an official-NDK ARM64 build and real Linux release-JNI import/JPEG-export tests for three formats. Five CR3 representatives match 103,271,424 LibRaw sensor samples exactly. Two Pentax DNG controls match 25,887,104 normalized active samples; this oracle uses adapter black/white metadata and does not independently validate those values. Packaging-specific version, signing, native-library hashes and contents are verified separately for this beta.

## Limits

- This package has not been installed or run on a physical Android device during preparation.
- Camera-specific proprietary rendering and full Adobe profile appearance are not claimed. The existing basic D65 developer remains.
- Unsupported or unverified CR3 burst/multi-sample modes, Pentax pixel-shift/HDR or monochrome, ORF 80 MP, encoded sensors above the existing 52 MP cap, and unsupported required DNG opcode/layout variants remain rejected.
- Leica X2/CL/M9/M10 variants described in the compatibility reports are not claimed supported. DNG support is structural and cross-brand, not a promise that every DNG file is accepted.

## Installation identity

Application ID dev.filmroom; versionName 0.1.21-beta1; versionCode 37; arm64-v8a; existing Filmroom release signing identity. After installing this beta, reinstalling an older build with a lower versionCode may require Android downgrade/uninstall steps; uninstalling can remove app data, so do not uninstall merely to test this beta without a backup.
