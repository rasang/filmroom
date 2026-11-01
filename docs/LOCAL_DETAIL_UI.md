# Editor-local detail integration (0.1.9)

Integrated in the production source for the 0.1.9 release. Host validation is not device validation.

## Activation and ownership
`EditorViewModel.localDetailEnabled` is enabled in this release.
The ordinary quick512 + settled768 preview is preserved universally, including fit view
and unsupported/unavailable local-detail routes. `previewRefining` keeps local context
creation waiting until ordinary rendering is idle; quick-frame export remains available.
Explicit disabled-path tests opt out per ViewModel, with no static/global flag.
The disabled path retains the existing preview-bitmap aspect, centered zoom and raw-pixel pan offsets; source-oriented aspect/focus pinch apply only when a detail controller is configured.
The UI never substitutes tile pixels for a `PhotoSource` or export thumbnail/input.

A zoom >= 1.15 and 180 ms gesture settle starts `OwnedDetailProvider.open` once.
A source-object/parameter/eligibility generation is distinct from scheduler viewport
revisions. Panning updates visible priority demand without rebuilding native context
or clearing valid same-key cache. Newly exposed cells may remain blurry until ready.
The 256-grid uses global 1 px valid-image sample gutters. Full sample bitmaps draw
under clipped core rectangles with one shared transform and explicit destination
rectangles (density independent). Logical fit aspect uses oriented source dimensions,
not rounded preview dimensions.

RenderKey contains provider source/parameter/data/mapping identities, virtual grid
width/height, algorithm/grid/cap revision and sampling revision. Every result must
exactly match key, core/sample geometry, ARGB_8888 dimensions and allocation bytes.
Malformed, stale, retired, or never-published output is rejected/recycled. Published
bitmaps are never manually recycled because Canvas/display lists can retain them.

PhotoView owns the controller. MainActivity binds eligibility, retires detail on
background/destroy/busy/parameter update/export and memory trim, and restores
existing transform state across recreation. Compare hides overlays and pauses starting
new cells while retaining valid same-key completed tiles/context. Returning to the same
source/parameters reuses them; source/parameter changes still invalidate while comparing.
ViewModel synchronously invalidates detail before import/parameter/history/cancel/export
dispatch, so conflated
StateFlow updates cannot leave an idle context blocking foreground admission.
Provider retirement listeners drop the UI cache after native memory-pressure eviction.
Session close runs off-main; the provider retains source/native reservation ownership
until actual JNI worker exit and destruction. No global native cancel is used by ROI.

## Truthful bounds and fallback
Native API currently caps virtual detail grids at 1536. This is NOT full-original-size
rendering, not proof of original-resolution sharpness, and not a replacement for export.
Unsupported effects/routes/ABI, insufficient resources, malformed responses and native
failures keep ordinary preview; UI does not remove effects to force detail success.
Failures/pressure suppress immediate retry for that generation. A later source/parameter
or eligibility change may attempt a new session.

The 8 MiB tile cache bounds retained bitmap ownership only, not total process RSS,
underlay, in-flight native buffers, GPU/display-list retention or delayed GC.
Prototype fractional software-Canvas evidence shows no extra tested seam error versus
an independent single-crop control, but not universal bit-identical interpolation vs
full-image draws. See original prototype artifacts/RESULTS.md. Android hardware display,
physical-device GPU/RSS/thermal/gesture latency and native258-target performance remain
separate unverified gates. Host tests are not device results.

## Checks
See validation/ui for preserved test reports and aggregate logs once checks complete.
Tests include imported scheduler geometry/cache/stale-buffer regression coverage,
non-cooperative fake open/tile races, missing-ABI fallback, real MainActivity gesture/compare/parameter/source/
rotation/background/export/import paths, and opt-in PhotoView -> real Kotlin provider
-> actual owned JNI scientific render -> native host software Canvas.

## Independent-review corrections
- Missing native ABI/object initialization failures now normalize/fall back, including
  direct LinkageError from injected providers; missing-symbol tests cannot crash or loop.
- A newer viewport during active context build retains that build, while only the
  initial settle timer is restarted. Job-identity checks prevent a canceled old timer
  from clearing ownership of a newer open job.
- Wrapper-construction failures close the newly returned session off-main; tile
  wrapper failures recycle only the still-unpublished exclusive bitmap.
- Activity synchronous invalidation avoids StateFlow conflation retaining a context
  across very fast import/export/parameter operations; compare instead pauses without retirement.
- Camera layout fixtures now await async startup before injecting synthetic state;
  all original layout assertions remain. Experimental enablement is per ViewModel,
  never a static flag/System property, and is asserted false by baseline layout tests.

## Historical enabled-candidate acceptance revision
Root identified two UX regressions in the first disabled integration and required these
corrections before enabling the isolated candidate:
1. Restored ordinary settled768 preview universally (not only fit view or fallback).
2. Compare is no longer a generation invalidation: overlay drawing is hidden and tile
   starts paused, but unchanged exact cache/context survives the round trip.
New tests assert baseline512->768 with default enabled and explicit disabled opt-out,
no local context before ordinary refinement exits, actual Activity missing-symbol
fallback retaining768, compare no duplicate cell rendering, and parameter/source
changes invalidating retained compare cache. See `validation/ui/enabled/` for checks.

## 0.1.10 trusted-root alias hotfix
The Context-supplied trusted root alias and its canonical equivalent are accepted; checked relative descendants are rebased beneath the canonical root. Traversal, nested symlinks and hard-linked source leaves remain rejected. Fixed image-area status exposes allowlisted preparing/refining/ready/fallback only; ready identifies actual grid, CPU and non-original resolution. No source paths or raw exceptions are exposed. This fixes a host-reproduced trigger defect, not a confirmed sole explanation of the user device blur. The 1536 cap, source/export paths and effect semantics remain unchanged.
