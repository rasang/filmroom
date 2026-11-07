# Independent film/paper omission: integration handoff

Implemented as two independent `none` stock selections, not a separate original-color mode. Existing IDs and serialized fields remain unchanged. Inactive medium-dependent controls retain saved values but are disabled; camera exposure/optics and applicable scanner operations remain usable.

## Numeric meaning

- Both actual media: original route, with the K75P correction below.
- Neither: linear RGB front-end and output processing, no hidden stock.
- Negative film without paper: actual film development followed by fixed profile-calibrated positive digital density readout, anchored to 18% gray; no frame/ROI-dependent min/max normalization.
- Reversal without paper: actual positive film spectral scan.
- Paper without film: explicit bounded RGB-to-paper exposure bridge and actual paper development/spectral scan. No film-density/RGB type confusion.

## Passed

- Complete production Kotlin compilation and 19 host tests. Includes actual none-card clicks for all four combinations across four locales, QR roundtrip/unknown ID rejection, paper layout/artwork, and no-film negative-preview diagnostics.
- Four combinations × sRGB/ProPhoto × CPU/GPU-requested streamed export: 16 cases. Host GPU execution asserted via `active` and batch counts; no silent CPU masquerading.
- Three core Vulkan tests: pointwise whole/banded/CPU comparisons; camera/paper diffusion global fields and band continuity; all ten papers without film and Kodachrome reversal without paper.
- Two direct Vulkan shader tests including black/HDR exposure bounds, preflash and distinct log-RGB/density readout.
- Earlier precision-f64 and precision-f64+adreno-gpu cargo checks passed. All shaders above ran on SwiftShader software Vulkan, not a phone GPU.

The added coverage found and fixed omitted-film non-sRGB streaming conversion, and an existing 2383/2393 GPU bug that substituted D50 for their K75P projector illuminant. The latter intentionally corrects their legacy GPU colors too. Actual small-fixture CPU/GPU maximum differences after correction are below 8e-6; this is not a universal image-quality claim.

## Integration requirements / remaining boundary

1. Merge this branch together with its accepted paper-carousel commits (already included), preserving current dev's Adreno eligibility protections. The branch contains the 8cf5e598 safety changes.
2. Apply `/workspace/shared/filmroom-backup-none-compat.patch` after integrating the backup feature. Backup stock validation must use shared `StockSelectionValidation`, accepting explicit `none` but never unknown IDs. Run the integrated backup tests; this branch does not contain the independent backup feature.
3. Unified Android build/lint/package and actual-phone testing remain for the packaging owner. No APK was built here; no release was published.
4. For host reproduction: single worker, explicit `--test`/`--lib`, installed rust-lld, and disk guard. Cargo integration tests otherwise auto-build the engine's unrelated `src/bin` tools; final host runs temporarily set `autobins=false`, `crate-type=["rlib"]`, restoring the original manifest afterward. This changes host link products, not rendering code.
5. Evidence is in `validation/`; complete logs/scripts are `/workspace/shared/filmroom-none-validation`. Canceled compile/link attempts are not counted as passes. Completed binary evidence compressed earlier is SHA-verified and restorable there.
