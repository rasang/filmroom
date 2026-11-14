# Finishing performance branch: validation record

Base: dev `e89db876594845fe111a98646492d795e7e66274` (beta8).
Implementation: `ca50edd961c47cb678f86cc50ef97e24dc62bf98`.
Android detail-entry guard correction: `c537fea3857baf9c2b6667e178bdb4c7b81c1d58`.

This is source-branch work, not a release or a device-performance certification.
The remaining CI workflow is read-only. Temporary source-transport files and
write-enabled installation workflows have been removed from the branch tip.

## Completed host checks

- Film-engine full CPU library suite: **224 passed, 3 failed, 2 ignored**.
  All three failures were reproduced separately on the unchanged base source,
  with the same failure messages and numerical values (details below).
- Finishing-specific tests, including scientific-cache reuse and checkpoint
  hard-link/cancellation safety: **6 passed**.
- Core FilmLog/reprint dependency and range contracts: **9 passed**.
- Scoped GPU buffer ownership/unwinding contracts: **3 passed**.
- Codec full library suite: **111 passed, 0 failed, 9 ignored**. Ignored tests
  include fixture/benchmark cases; they are not counted as executed checks.
- Host compilation of film-engine with `precision-f64,adreno-gpu`: passed.
- Real Vulkan dispatch via software driver in GitHub Actions run
  `43534457624`: the `gpu_reprint` integration test passed. It compares cold and
  split/reprinted pixels, checks buffer reuse, and includes Portra with/without
  camera+print diffusion plus direct Ektachrome. The test requires an adapter;
  it cannot silently skip when `FILMROOM_REQUIRE_GPU_SMOKE=1`.

The first Android compile identified a misplaced parameter guard. The correction
moves it from orphan cleanup to the parameter-owned detail-open entry point.
The read-only workflow on the current branch compiles Android production/test
sources and runs FinishingStateHostTest, SavedPresetCoverageHostTest and
PresetQrCodecHostTest; its result must be checked for the exact branch commit.
Unit-only CI excludes the unavailable private licensed audio gate. It does NOT
package an APK and does not bypass that gate in production builds.

## Existing full-suite failures reproduced on the base

1. `detail::tests::all_catalog_stock_profiles_are_rendered_or_explicitly_unsupported`:
   existing hard-coded count expects `(17, 5)` but the catalog renders `(22, 5)`.
2. `mobile::bw_mobile_tests::bw_upstream_neutral_database_is_used_and_manual_preferences_remain_effective`:
   `every print medium has upstream Double-X entry` assertion.
3. `streamed::omitted_medium_tests::all_four_media_combinations_match_full_cpu_pointwise_output`:
   sRGB, GPU requested, no-film/Portra-Endura sample 24: streamed `0.6933733`
   versus full `0.6933466320535902`.

These tests were not deleted, weakened or silently made green. This branch does
not claim that the complete baseline suite passes.

## Host microbenchmark only

`encode8::tests::benchmark_rgb24mp_quantization`, release build, 72 million
component conversions:

| Path | Elapsed | Checksum |
|---|---:|---:|
| Original transfer/rounding | 822 ms | 12662023542 |
| Boundary-table quantizer | 210 ms | 12662023542 |

This measures only linear-to-8-bit component conversion, NOT JPEG compression,
whole-image export, Android wall time, sustained thermals or phone GPU latency.
JPEG quality, 4:4:4 sampling, ICC and the encoder library remain unchanged.

## Before producing a release

Rebuild the Android native libraries from this source: active finishing requires
`finishingAbi=1`, and an old JNI binary is refused rather than ignoring sliders.
Use approved private audio; run on-device cold/warm previews, print edits,
finishing edits, HDR/SDR input regressions, cancellation, background export and
memory-pressure checks. No APK or main/dev merge is part of this branch task.
