# Independent performance comparison APK

Based on performance branch merged with dev 9414dc3d (beta11).
Application ID dev.filmroom.perftest; label Filmroom Perf; version 0.1.22.2-perf2; versionCode 2.
Production update endpoint disabled. Settings/caches/records separate from dev.filmroom. Gallery files are shared.
ARM64, Android 10+. Same release signature permits updating perf1.
The native engine source is identical to perf1; its verified library is reused. The merged codec library is freshly rebuilt.
For A/B use identical input file, parameters and resolution. No phone performance claim is made by host tests.
