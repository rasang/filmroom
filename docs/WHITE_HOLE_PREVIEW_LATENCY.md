# White-hole preview response: one shared atlas traversal

## Symptom and traced path

The negative white-hole switch calls `EditorViewModel.parameters` with only
`negativeWhiteHoles` changed. That field is excluded from `previewScience`, so
an already completed, matching film result is reused. The switch does not
normally rerun film processing. An in-flight scientific result still completes
before its latest presentation is adopted; this scheduling policy is unchanged.

For a new hole appearance, the presentation key changes. Beta2 generated the
entire interactive overlay first, then traversed the same atlas again for the
exact linear composite. The user saw neither result until both were finished.
The cached bitmap can be 512/768, or 1536 after admitted GPU refinement.
Version-keyed atlas reloading is an additional measured cost and remains
unchanged in this small fix.

## Change

`BorderPreview.composeWithOverlay` fills the caller-owned overlay during the
existing exact compositor traversal. It records the same sRGB sample and
rounded 8-bit coverage before output-primary conversion, then retains the
original linear photo/border blending and imprint calculation. The exact
image is never reconstructed from the quantized overlay.

- Existing overlay cache hits still use the normal exact-only path.
- New overlays are private until successful latest-state adoption. Existing
  displayed bitmaps are never mutated or recycled by the new pass.
- Failure, cancellation and discarded output retain existing ownership rules.
- Retained bitmap/atlas counts do not increase. Additional scratch space is one
  output-width integer row, covered by the conservative existing row budget.
- No shader, native engine, stock data, border geometry, persisted contracts,
  version number or release artifact is changed.

## Validation scope

- Kotlin 2.0.21 production-delta and test-delta compilation against the frozen
  Beta2-equivalent host class/resource baseline passed. This is a focused
  compile, not a complete application build.
- 94 focused regression methods passed across 11 classes, with no failures,
  skips or ignored methods. They include current 135/authored BW pixel parity
  in both orientations and routes, both hole preferences, 120 imprint on/off,
  single-traversal sample counts, cancellation, failed presentation/retry,
  latest-state adoption, science-cache reuse, preview scheduling/refinement,
  export coordination, shared memory admission, compositor/export contracts,
  white-hole availability and imprint contracts.
- The timing diagnostic additionally checks 512/768/1536 outputs and overlays
  for Portra 400, CineStill 800T and Double-X. Its exact output hashes are also
  compared with an unmodified Beta2 bytecode run, covering 18 unique
  stock/size/hole cases. No timing thresholds gate correctness.
- Full `compileDebugKotlin lintDebug` was attempted twice, the second attempt
  alone, with one worker, 2 CPUs, 512 MiB heap and 384 MiB metaspace. Both lost
  the Gradle daemon during `compileDebugKotlin`, without a source diagnostic.
  The cause was not established. Lint was not reached. One overlapping host
  run was also killed with exit 137; all its targeted classes subsequently
  passed serially. Interrupted runs are not counted as passing checks.
- Independent code review found no blocking correctness, ownership, memory or
  concurrency issue. `git diff --check` passed.

This remains a source candidate, not a signed APK or a release-ready build.
Full build/lint and physical Android testing remain open. The timing results
are host stage diagnostics only, not handset latency or end-to-end toggle
measurements. Decoder reload and waiting for an in-flight scientific result
remain potential latency contributions outside this fix.

## Indicative 1536 host stage times

With the same cached photo and actual packaged atlases, median overlay + exact
composition versus the fused pass (milliseconds, 3 paired samples after one
excluded sample, both include bitmap allocation):

- Portra 400: 354.5 -> 264.0.
- CineStill 800T: 504.4 -> 349.3.
- Double-X: 670.2 -> 430.8.

These are small, same-process diagnostic samples. The separate operation runs
before the fused operation, so they are not an unbiased performance benchmark.
They exclude scheduler waiting, atlas resolution and film processing. The
stronger, deterministic result is that coverage/color are sampled exactly once
and all tested output pixels remain unchanged.
