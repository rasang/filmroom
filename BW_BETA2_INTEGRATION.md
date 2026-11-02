# Beta 2 BW integration handoff

Base: `1a05595fa4fda0a9676a0a38466ab727cc5ed6b6`; isolated branch `beta2-bw-integration`. No version, bundled `.so`, APK, signing, release, batch-decoding or RAW metadata implementation is changed here. The joint integrator owns merging Beta 2 UI fixes, rebuilding both Android ABIs and the final complete gates.

## Delivered behavior

- Actual upstream Kodak Double-X 5222 negative and Tri-X 7266 reversal shooting profiles; Kodak 2302 **print medium**, never a fake camera stock. 7266 artwork is Super 8, not 135 and not still-photo Tri-X 400.
- Exact original upstream profile blobs verified against both Rust main and Python dev; original measured spectra, curves, development families and licenses retained. See `native/BW_PROFILE_SUPPORT.md`.
- BW single-channel resolution now handles missing spectral sensitivity as zero response, broadcasts measured grain sublayers, and shares single-emulsion microstructure noise in both preview and streamed output.
- 2302 uses its measured density table and scalar gamma because the old colour morph cannot represent its skew-fit model. Unsupported colour-layer/morph controls are grey; saved colour preferences survive switching. No BW neutral-filter calibration is invented; manual spectral enlarger filters remain usable.
- Measured 5222/2302 development-time choices persist independently, are sent through the native JSON contract, and appear in bounded diagnostics. Chinese, English, Japanese and Korean text is provided.
- Approved original thumbnails (240 px lossless) and overlays only. Role-correct final print frame, 5222 polarity/white-hole preference, 7266 rounded gate/current-image adjacent slivers, 2302 clear rails/opaque image-zone. New immutable geometry v4, old colour v1–3 preserved. See `docs/BW_FRAME_ASSETS.md`.
- One geometry/linear-light composition contract drives preview, live composition, export options, queued export and scan-stage preview. Crop center, zoom and orientation survive cross-format stock changes; effective aperture aspect follows the selected stock without rewriting saved preferences.

## Native/API contract for the integrator

New JSON fields: `filmDevelopmentTime` (0..12) and `paperDevelopmentTime` (0..9); zero selects the upstream default, positive selects nearest supplied time. Old JSON defaults remain accepted. The UI and both native ABIs must ship together: old bundled native libraries reject these new fields. JNI function signatures are unchanged.

New camera IDs: `kodak_doublex`, `kodak_trix`; new paper ID: `kodak_2302`. Catalog has 22 shooting configurations and 9 print media. Physical camera stocks: 20, plus two Portra push configurations. New profiles and catalog are mirrored in `native/data` and Android `engine-data`.

`EditorState.frameStock` / `BorderCatalog.frameStock` resolves the output presentation stock; use it instead of `filmStock` for frame lookup. `presentationTransform` normalizes effective frame aspect without changing stored composition. White-hole availability uses the effective frame, not the camera-stock name alone.

## Verification status

- Exact Git blob identity and native/Android mirrors: passed for all 3 profiles.
- Four-language key presence/XML uniqueness: passed.
- Native focused BW spectral/family/grain/full-vs-streamed tests: 4 passed, including the final endpoint-correction rerun.
- Native catalogue/routes/print/scanner regression: 10 passed after narrowing the old colour-only morph test to actually exposed capabilities.
- Initial focused Android border/appearance/registry/direct-route gate: 18 passed.
- Native mobile default all-effects preview/export and effective-parameter tests: 3 passed. Frozen streamed colour baseline byte-equality check: 1 passed.
- Independent native science review: integration candidate accepted; review and source hashes are `/workspace/shared/filmroom-beta2-20261102/results/BW_SCIENCE_REVIEW.md` and `BW_REVIEW_SOURCE.sha256`.
- Final Android state/controls/queue/geometry/artwork/diagnostic gate: **73 passed, 6 existing opt-in skipped, 0 failures** (79 total), `/workspace/shared/bw-frame-final-tests.log`.
- 24 actual compositor/PhotoView live-drag PNGs generated and independently visually reviewed. Both orientations, three stock roles, 5222 material/holes, Super 8 adjacent current-photo slivers and 2302 rails checked. Contact sheet: `/workspace/shared/bw-runtime-contact.png`. No outstanding frame-review blocker.
- Android ABI builds, full joint aggregate suite, real-device GPU/UX checks: not run by this isolated worker. Joint integrator must run the first two; device checks must not be represented as host verification.

Logs are at `/workspace/shared/bw-native-focused.log`, `bw-native-mobile.log`, `bw-color-baseline.log` and `bw-frame-focused-tests.log`; final expanded gate is `bw-frame-final-tests.log`; PNGs are under `app/build/ui-host-renders/bw-runtime/`. Initial compilation failures and a stale last-card-ID test oracle were corrected before the final passing Android gate. An old test assuming every print profile exposes the colour morph was corrected specifically for 2302; all 8 historical print-model checks remain intact.

## Explicit joint follow-up

`native/tests/jni/dev/filmroom/engine/BwProfileProbe.java` is supplied for the integrator's freshly built host library. It checks the actual JNI boundary for all three BW output routes, complete finite file output, new development fields, source preservation and invalid stock-role rejection. This added harness has **not** been run in this isolated gate; the source-level mobile/export equivalents have passed. Compile alongside the existing Java probes and run with `BwProfileProbe <new-host-lib> <sandbox-root>` after copying final engine data into `<sandbox-root>/files/engine-data`.

The independent joint owner additionally prepared an old-Beta1-binary/new-binary same-input colour oracle; that stronger binary comparison is still a joint-gate responsibility. Existing preview versus export stochastic grain sampling-layout differences remain as documented by the native API; border/crop/linear-overlay parity is exact in the focused tests, not a claim that different-resolution stochastic samples become pixel-identical.
