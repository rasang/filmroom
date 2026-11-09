# Beta27 reconstruction

This branch reconstructs changes from the beta27 APK against dev commit
`6dbb0151604cee3ca5fd3e1a5ea5d7cfbd770b86` (beta22). It is not the lost original source.

## Evidence and boundaries

- Original APKs, JADX 1.5.3 outputs, normalized diffs, and raw-control-flow checks are retained in the sibling `reverse-support` directory.
- New motion-photo extraction, bounded software fallback, surface hardware decoding, frame gate, GPU crop/blur, original-audio mixing, metadata preservation and preview diagnostics are reconstructed from DEX.
- Shader strings are copied from DEX where available; three audio assets and two arm64 native libraries are retained byte-for-byte from beta27.
- `EffectMotionSource` and `EffectPreview` contain reconstructed structured control flow because normal Java decompilation loses Kotlin try/finally and branch semantics. These require dedicated verification, not only successful compilation.
- Historical test counts do not apply. New test results are recorded separately.
- No Android emulator or physical-device run has been performed by this reconstruction step.

## Persistence

Work is in a persistent workspace, not `/tmp`. All source changes must be committed before package delivery, and audio assets must be explicitly included in the private build archive despite the historical media ignore pattern. Public source commits retain the documented audio licensing exclusions. Native library hashes are recorded in `docs/recovery/native-reuse-sha256.json` and should accompany the complete archive. Do not deliver only APK binaries.
