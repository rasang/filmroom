# Full Lensfun / embedded optics merge validation

Scope: merge `feat/embedded-lens-correction` at `cb416935` into `dev` at
`157a389e`. Host validation performed on 2026-11-14; not Android device validation.

## Integration review

Two independent read-only reviews found no new merge blocker. The two textual
conflicts were resolved by retaining dev's DNG `DevelopmentTransform` and adding
`lens_profile`, and retaining both GPS-help and new optical-correction strings.
Dev-only files (including GPS diagnostics) are unchanged. DNG spatial black,
fractional crop, DefaultScale and required opcode handling remain intact.

Distortion, lateral chromatic aberration (TCA), and vignetting are selected
independently. Embedded Ready/Unsupported prevents corresponding external
replacement; only Missing permits a qualified database fallback. No manual
correction slider or switch is added. 1,580 database records are not a claim of
1,580 real-camera validations. ORF external vignetting remains disabled.

## Passed host checks

- Pinned database generator consistency: 56 XML files, 1,059 camera records,
  1,580 lens records; generated indexes/tables match.
- Final native release all-targets: 420 passed, 0 failed, 15 explicitly ignored
  across 47 test targets (`cargo test --offline --locked --release --all-targets -j2`).
  Includes 244 native-library passes; these counts overlap, not additive.
  Built from a fresh target directory using Rust 1.100.0 and pinned host libjxl.
- JNI smoke: 19 passed. Real Lensfun JNI: 9 passed, including memory/disk
  rejection and cancellation after actual pixels are written; input unchanged
  and incomplete output removed.
- Android/Robolectric targeted tests: 49 passed, no failures or skips. Includes
  RAW ownership/cache migration, metadata admission/warnings, technical
  diagnostics, GPS diagnostic whitelist and public JPEG GPS round trips.
- Nine public, SHA-256-verified original RAWs: Nikon Z f, Ricoh GR III/IIIx,
  Canon R5 II + RF135, Sony ILCE-6700/RX100M2, Fuji X-T20/X-T4 and Olympus
  E-M1 II. All probe/decode/JPEG export successfully. Every complete float32 RGB
  output is byte-identical to the feature branch's recorded output in
  `codecs/tests/public-fixtures/lensfun-full-SOURCES.json`, including the two
  native DNGs and the RF135 three-operation case. These are host results only.

## Aggregate-test limitations

The complete Android unit-test invocation was attempted, not passed. It reported
`CandidateBorderCatalogHostTest` and `CandidateBorderActivityHostTest` failures,
then stopped making observable progress near DetailLoading tests. After ten
minutes without progress it was interrupted; targeted tests above were rerun.

Both border failures were independently reproduced by executing the same tests
on untouched dev `157a389e`:

- Catalog line 89 expects 44 WebPs, excluding the ten existing reviewed-border
  assets. Actual assets contain 54; this is a stale baseline catalog assertion.
- Activity line 77 times out waiting for presentation/export readiness after
  enabling a border. Same failure location and message on dev and merge. The
  underlying pre-existing UI/test cause is not resolved by this optics merge.

Neither test nor border code/assets were changed or weakened to make this merge
pass. The overall UI suite is not claimed green. Cache tests use a mock native
API and do not establish real-device cross-version upgrade behavior.
