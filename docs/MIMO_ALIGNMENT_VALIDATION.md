# Mimo output alignment validation

Base: dev `3fe0c090384302430b737abfd2a65a0e3a519846`.

- vivo single-file adds the inspected private video extension and counts it in XMP extent. Unknown Filmroom source is omitted rather than claiming DJI identity or a vivo SDK version.
- Oplus uses the actual application package owner, inspected clean-input version 1 and auxiliary VideoLength 0, with actual length in the Container item.
- Honor uses the inspected Mimo call defaults (`v2_f00`, `0:1000`) in three 20-byte fields. The unresolved time units are not guessed.
- Samsung SEF output is unchanged in this patch.

Validation on the integrated source:

- Android Kotlin and unit-test compilation passed.
- 44 selected Live/core/metadata/direct-export tests passed, zero failures/errors/skips; includes the previously unrun PhotoMetadata Robolectric adapter tests.
- 126 saved-preset compatibility gate tests passed.
- `lintAnalyzeDebug` passed. This is not a claim that full lint or device instrumentation ran.
- ARM64 Debug assembly passed. Real gallery recognition remains untested.

Native engine and codec code are unchanged from beta38 (`git diff f0ab0962..HEAD -- native codecs` is empty). The ARM64 native libraries were recovered from the already released beta38 APK, whose full SHA-256 was checked as `7cb03a8d95c26838a624f6747fdcd048969c976f781d5a2fa855a46912f68c91`:

- libfilm_engine.so: `abd0c47d7b4ebff2508b99a40fa047bda1a2598b7e347132108febb9e19a95d9`
- libfilm_codecs.so: `b4ed6bb3abb25bac42e3b2920fe84abb25d692a80e0c8816545f092796a17bf1`

These are unchanged native build artifacts, not a fresh native build or a device test.
