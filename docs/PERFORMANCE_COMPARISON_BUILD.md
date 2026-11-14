# Independent performance comparison APK

This test build is based on `feat/finishing-preview-export-perf` commit
`9e7d90acfa83b2767feb993b3582239bab3c91c6` (standalone finishing grade removed).
It is not a merge into dev/main and does not include subsequent dev-only changes.

- Application ID: `dev.filmroom.perftest` (namespace/JNI names remain `dev.filmroom`).
- Launcher label: `Filmroom Perf`.
- Test version: `0.1.22.2-perf1`, independent versionCode 1.
- Production update endpoint disabled to avoid offering unrelated production APKs.
- Private settings, caches and export records are independent of `dev.filmroom`.
  User-selected input photos and exported public gallery files still share the device's gallery.
- ARM64 only, Android API 29+.

Rebuild both Android native libraries from this source, following `docs/DEVELOPMENT.md`,
then build `:app:assembleRelease -PfilmroomAbi=arm64-v8a` and sign externally.
No private signing material or licensed audio is committed.

For A/B timing, use the same input, recipe, output resolution and border/Live settings
in both apps. Distinguish first import/preview from warmed repeat operations.
This package does not establish Android-device speed gains; those require device measurements.
