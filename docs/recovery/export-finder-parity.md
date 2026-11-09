# Video/Live opening finder parity

The preview supplied `previewFinder=true` to `EffectGlRenderer`; `EffectVideoExporter`
left its default false. Thus the optical opening used two different geometries.
The exporter now explicitly enables the same contained finder mapping, and the
renderer default is true to prevent new callers accidentally retaining the old layout.
The historical parameter name is retained to avoid conflicts with parallel Activity work.

Only OPTICAL_LEAK with materially different source/result aspect ratios selects the
mapped shader. Source photograph sampling is contained, not cropped. The final-result
branch, encoded dimensions, light-table effect and static cover path are unchanged.

Validation performed:
- Host Mesa GLES2 shader compile/link and actual offscreen rendering.
- Landscape 135: gray slack above/below the complete black finder.
- Portrait 135: gray slack left/right outside the finder.
- Final result pixels identical between original and mapped shaders in both orientations.
- Equal/rounded aspects remain pixel-identical; portrait split-focus orientation preserved.
- Evidence in workspace `export-finder-validation/` (fixture marks are transparent;
  this does not validate real-device instrument readability or Android encoding).
- Added EffectExportFinderParityTest (2 methods), reusing 9 geometry methods.
  Independent Gradle was stopped during compilation to avoid concurrent resource/lock
  contention; integration must execute these tests. No claim of test-suite completion.
