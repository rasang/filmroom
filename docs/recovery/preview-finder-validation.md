# Preview-only finder candidate

The independently verified patch is now integrated into the recovery repository. Final Gradle validation is recorded separately.

Original isolated patch and fixtures are retained at `beta27-gray-preview/` in the recovery workspace.

- Default renderer flag is false. Exporter untouched; original FRAGMENT retained byte-for-byte.
- All four Activity renderer constructions enable the optional preview finder.
- Keep result canvas and final-result sampling fixed. Only opening finder coordinates, marks/readout raster sizes, logical geometry and portrait split direction are remapped.
- No photo crop, no border-asset change, no animation timing change.

Validation completed:
- `git apply --check` against current recovery repo.
- 7 geometry JUnit tests compiled/run directly against candidate Geometry and current Timeline.
- 10 source/GLSL activation invariants (`verify.py`).
- Candidate Geometry + Renderer compiled with Kotlin 2.0.21 against current recovery debug classes and Android 35 API.
- Original and optional GLSL compile and link in host Mesa EGL GLES2.
- Offscreen fixture confirms top/bottom slack outside full finder, and final-result pixels identical between shaders.
- Inspected `preview-fixture.png` visually. This fixture is synthetic with transparent markings/readout, not Android screenshot proof.

Not performed: Android emulator or physical device playback; full Activity Gradle test after patch merge; real marks/readout Android raster visual verification; export byte identity of whole APK/video (shader source identity and final fixture render only).

Artifacts: before/ and after/ files, preview-finder.patch, geometry-test-result.txt,
shader-test-result.txt, compile-result.txt, old-fixture.png, preview-fixture.png.

## Review follow-up

- Added portrait 135 host fixture, verified left/right slack entirely outside the full finder; final output pixel-identical.
- Equal-aspect images bypass the optional program and geometry. Independent one-pixel preview rounding is tolerated via the cross-product uncertainty band; real film rails and even a 6-pixel height difference at 1536x1024 are not hidden.
- Nine geometry JUnit tests now pass (added rounding-band and real-border detection).
- Thirteen activation/shader invariants pass.
- Host landscape, portrait, square equal-aspect cases at focus=0.4 and 1 are pixel-identical; rounded-aspect landscape/portrait cases also pixel-identical.
- Portrait finder placed on a landscape canvas at focus=0.4 matches a portrait-reference inner finder to max 1 color level, mean below 0.01; this specifically verifies finderAspect-driven split direction.
- Inspected portrait-fixture.png and portrait-focus-wide-canvas.png. Mark/readout textures remain transparent in synthetic host fixtures, not full Android raster validation.
