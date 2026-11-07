# Acros II 120 reference model

The user approved the five-format visual model on 2026-11-06. This is authored approximate artwork based on the two user-supplied references, not a scan or measured factory template. All five nominal formats (645, 6×6, 6×7, 6×9 and 6×17) crop one finite 180 mm model without stretching or restarting the labels. Larger crops use an explicitly inferred periodic extension.

## Observed evidence

The left panel of the Squarespace comparison is Acros II. Rotated clockwise, its upper edge shows **4, 5 and narrow left-pointing wedges**. The lower edge shows **FUJI 100 ACROS II** and **4586**. The right panel is the original Acros and is excluded completely: its separate 170, 12, 100 ACROS and dot-matrix printing do not belong to this artwork.

The separate Ameba photograph shows the physical negative with **13**, a narrow left wedge, **FUJI 100 ACROS II**, **4586** and a small **A**. It is identity and shape corroboration, not a continuous same-roll extension of the first image. The small A's cadence and registration against the primary image are unknown, so the primary model does not fabricate an A on every repeated unit. The warm photographed negative is not evidence of an orange film mask.

The exact image URLs, byte hashes and inspected primary coordinates are in `source-hashes.json` and `source-coordinates.json`. Original photographs and their pixels are not packaged in the app. **4586 is only the observed code**; its meaning, universality and relation to batches or emulsion numbers are not asserted.

## What is inferred

Mapping the approximate 573-pixel cross-film aperture to the app's nominal 56 mm gives about **59.812 mm** of primary observed longitudinal content. The measured-on-image 4-to-5 start interval is approximately **425 px / 41.536 mm**. Index progression outside 4 and 5 is inferred. A **two-index / 83.072 mm brand-and-code period** is a presentation assumption: the primary image does not show two successive brand legends.

These rules produce a finite authored interval at atlas coordinates **320–500 mm**, centered at **410 mm**. Only the interval **380–439.812 mm** corresponds to the primary reference. Crops outside this approximately 60 mm region are explicitly modeled extension, not continuous source evidence. Runtime coverage checks reject crops beyond the authored 180 mm interval. The rest of the 820 mm atlas is inaccessible neutral filler, not a claim of blank physical film.

The terminal Roman II is drawn with the visible connected serifs rather than two plain sans-serif bars. Typeface, gray value, cap height, edge alignment, film width, camera gate and 3 mm longitudinal end allowances remain approximations. Neither the film's scientific response nor any existing immutable asset is changed. Acros II receives a new v9 identity and remains 120-only for frame artwork. The package contains no sprocket holes and uses the existing neutral monochrome negative presentation.

## Date and exposure metadata

After approving the plain-border model, the user requested the established centered-imprint design: **date and exposure data stay centered in the frame; conflicting lower-edge marks move outward**. The upper index/wedge rail has no conflict and remains unchanged. This supersedes the earlier source-aligned, slightly off-center proposal.

`imprint-layouts.json` records the five exact designs. Clear widths are 25.6, 30.5, 38, 41 and 53 mm for 645, 6×6, 6×7, 6×9 and 6×17. The visible brand/code pixels are copied without stretching, with at least 0.6 mm clearance after sampling guards. All relocated marks stay within the frame. The narrower 645 naturally clips the original brand at its crop edge; missing source text is not invented. Other formats retain the full visible legend.

The immutable v9 asset and plain-border presentation remain unchanged. This is an opt-in presentation layout, not a claim that the factory printed these per-format positions. Black-and-white negative regions vacated by moved marks use the same neutral gray as the rest of the strip, not an orange color-negative fill. Date-only, exposure-only, photo-date, edge-date, rotations and small previews must be checked using the production renderer.

The original source-aligned clear interval, 396.7–421.4 mm, remains recorded as reference evidence but is not the final placement policy. Android execution results belong in the validation report; this README does not imply unrun tests passed.

## Reproduce

`python tools/medium120/v9/generate.py --output <temporary-directory>` generates the PNG and evidence manifest. `python tools/medium120/v9/test_generate.py -v` verifies byte-exact regeneration, neutral opaque rails, source versus inferred inventory, finite crop coverage, clear metadata pixels and exclusion of photographic inputs from runtime assets.

The runtime PNG is 8200×50 RGBA at 10 px/mm, packing two separate 2.5 mm edge rails. Decoded storage is 1,640,000 bytes. New revisions must append a new identity rather than replace this version's bytes.
