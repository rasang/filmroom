# Three B&W 120 imprint completion

Base: Filmroom 0.1.22-beta2, code 54, source `6938599d1f55ebe398d38a4abb05c98c95b448e2`.

## Finding and implementation

HP5 Plus 400, T-Max 100 and Tri-X 400 already had independent 135/120 borders, but their v7 120 revisions deliberately had no reviewed date/parameter layout. The UI still offered edge dates and exposure parameters. The renderer consequently omitted exposure parameters and placed the date inside the photograph, even when the edge option was selected.

This change completes fifteen centered layouts (three films × 645, 6×6, 6×7, 6×9 and 6×17). It moves only existing lower marks and visible crop fragments outward in source order. The upper rail, source PNG bytes, film data, print calibration and native engine are unchanged. No glyphs or physical evidence are invented. HP5's long legends require narrower centered gaps; the shared common-height fitting algorithm remains unchanged.

New snapshots use separately registered v11 presentation revisions pointing to exactly the same v7 PNGs. Frozen v7 queue snapshots still resolve their original version and retain their original no-exposure/date-on-photo behavior. The imprint dialog derives capability from the same metadata-only current border snapshot and predicate as the renderer, rather than merely testing whether any border is enabled. Hidden or unsupported preferences remain saved.

Independent review found that a 475-column 645 crop would omit the final visible HP5 source column. All three new 645 layouts now use the established 476-column convention. Tests independently derive columns 3862–4337 from physical geometry, retain HP5's final ink column, and prove column 4338 does not leak into the crop. Existing integer crop conventions exclude bilinear neighbors whose centers fall outside the physical window; the provenance lists any ink in those neighbors instead of claiming exact full-support equality.

## Executed validation

- Kotlin 2.0.21 incremental production compile: all three changed production sources passed. Four changed/new test sources compiled in the same bounded setup.
- Final host run: **12 classes / 62 tests passed**, zero failures, assumptions or ignored tests.
- Three new full imprint contracts: **21 tests**; **960** geometry cases covering all formats, orientations, direct/print, date styles, photo/edge placement and minimum/maximum size. Includes tiny/extreme/missing metadata, unchanged upper rails, common centered bounds, exact moved-source pixels and 645 footprint checks.
- **120** new three-stock preview versus linear-float export cases: all **29,392,152** pixels match exactly after output encoding. Both materials, orientations and date placements are included.
- Current whole-catalog 120 border regression: **300** combinations, **5,986,530** matching pixels.
- Existing Acros II, CineStill 800T, original lower-rail layouts, five-film 135 imprint, and date dialog controls/placement suites passed again.
- Independent Python verifier passed all 15 new layouts, checking asset hashes, physical pixel-center coverage, all non-base lower pixels/fragments, source order, guarded destination bounds, centered clearance and non-overlap.
- Independent second review confirmed the 645 correction and inspected all 15 actual-render lower rails; no remaining blocking or high-severity issue was found.

The first new UI-test attempt had two test-fixture failures (queued main-loop callback and unavailable capture date); the corrected fixture and final complete run passed. Those initial receipts are retained separately.

## Evidence and limits

Local validation receipts, source/class hashes, actual JUnit events and 36 actual renderer PNGs are in `filmroom-three120-imprint-validation`. Two review boards show the 15 combinations and magnified lower rails. The asymmetric color input and EXIF values are synthetic test fixtures, not a film science or physical-camera demonstration.

The bounded runner overlays this change on the immutable beta2 feature classes, whose unchanged source was previously bound to the release. It is not a full fresh Gradle build, native rebuild, signed APK, final JPEG/TIFF-codec certification, physical Android test, or Adreno check. No release version, original APK, native library or scientific profile has been changed or published.
