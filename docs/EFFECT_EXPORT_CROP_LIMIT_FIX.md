# beta33 effect export crop admission fix

The user's beta32 diagnostics (versionCode 84, 2026-11-09 11:46:47–57 UTC)
contained six INPUT_DECODE/INVALID_ARGUMENT failures, with decode budgets of
2165–2300 pixels. Video encoding had not started for these attempts. One Live
Photo attempt with a smaller 1873-pixel budget passed INPUT_DECODE and entered
encoding, before cancellation.

EffectExportImages sent its memory-budgeted source into EffectSourceComposition.apply,
which used CropPreview and FinalImageRows.bitmap. The latter incorrectly enforced
a preview-only 2048-pixel input cap even for export. Non-identity composition above
that size therefore threw IllegalArgumentException. This is not exclusive to Live
input: any effect source with an actual crop/straighten and the same dimensions can
hit it. Preview is decoded at 1600 and does not hit the boundary.

Fix: keep the 2048-pixel guard in CropPreview. BorderPreview already has its own
2048-pixel admission guard. The shared row/tile traversal is size-neutral and now
validates mapping dimensions directly. Effect export uses a separate
CropBitmapCompositor entry through applyForExport. Input/output bitmap ownership,
cancellation and failure recycling remain unchanged. The existing source/result,
quality and heap-derived decode budgets remain unchanged; no 2048 downscale is
added. Identity composition still returns the input without allocation.

Regression tests cover diagnostic budgets, >2048 output preservation, rotated
landscape/portrait input, pixel parity with preview, the retained preview guard,
export cancellation, identity ownership and the unchanged memory budget.
Host tests do not establish Android hardware encoding or gallery playback success.
