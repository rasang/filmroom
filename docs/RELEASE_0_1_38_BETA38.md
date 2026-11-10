# 0.1.38-beta38 (versionCode 90)

- Add local Live export admission for vivo/iQOO, OPPO/OnePlus/realme, Honor and Samsung based on inspected DJI Mimo 2.12.8 predicates.
- Keep format selection separate from admission. Existing export formats remain unchanged.
- Visually dim Live export when blocked; keep it clickable to show a Toast without starting encoding. Video export remains available.
- Keep unreadable/unknown capability hints distinct from explicit unsupported results. vivo models absent from the observed whitelist remain unknown rather than falsely rejected.
- SDK and exporter guards prevent bypass through direct export calls. Remote target selection does not inherit local-device restrictions.

Validation before packaging: 42 focused host tests, 126 preset compatibility gate tests, ARM64 debug build and lintAnalyzeDebug passed. Release packaging runs the preset aggregate gate again. No physical-device gallery acceptance is claimed. Samsung admission follows Mimo's OLive gate, while Filmroom still writes SEF; the gate is not proof of SEF gallery compatibility.

Release package validation: assembleRelease and its 126-test gate passed after a resource-limited retry. The signed ARM64 APK has package dev.filmroom, versionCode 90, versionName 0.1.38-beta38; certificate matches beta37, and zipalign validation passed.
APK SHA-256: `7cb03a8d95c26838a624f6747fdcd048969c976f781d5a2fa855a46912f68c91`.
