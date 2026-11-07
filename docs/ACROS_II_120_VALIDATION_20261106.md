# Acros II 120 model and centered imprint validation, 2026-11-06

## Scope and baseline

Independent branch `feat/acros-ii-120-source-reference`, based on the reviewed CineStill work through `d08773ec65371602c604d936a1f9efd5777a03f5`. The original beta/release source and build cache were not edited. No APK, release, remote push or scientific-film calibration is produced by this component task.

The user approved the five-format plain Acros II model, then explicitly requested the established centered-printing layout: date/exposure in the middle, conflicting lower marks outward. The immutable v9 PNG remains exactly the approved model:

- `mf120-v9-acrosii-5d8eb4aa9d8f`
- SHA-256 `5d8eb4aa9d8fd621fd8de674c9003202c43b324eb7fcc50f076f358c493013e6`
- 18,696 encoded bytes; 8200×50 RGBA; 1,640,000 decoded bytes.

The optional layout remaps existing lower-edge pixels without adding or completing glyphs. Upper indices and wedges remain unchanged. Black-and-white negative areas erased by relocation use neutral `.70/.70/.70`; color stocks retain the existing `.58/.29/.12` fill. Original assets and native/scientific source files remain unchanged.

## Reference versus inference

Primary evidence is the **left Acros II** panel of the user-supplied comparison: 4/5, narrow left-pointing wedges, FUJI 100 ACROS II and 4586. The right original-Acros panel is excluded. A separate physical-negative photo corroborates 13, wedges, the legend, 4586 and an isolated A; it is not spliced into continuous-roll evidence.

Only about 59.812 mm is observed in the primary reference. The finite 180 mm model uses an explicitly inferred 41.536 mm index pitch and 83.072 mm brand/code cycle for long formats. 4586 is only the observed code. The A's repeat phase is unknown and is not invented. Typography, gray value, nominal gate and pitch are approximations, not factory measurements. See `tools/medium120/v9/README.md` and the source/evidence manifests.

## Executed validation

The checked production delta was compiled using Kotlin 2.0.21 against the coordinator-approved build53 baseline classes and a private resource overlay containing the unchanged v8 plus new v9 PNG. This is a focused, real Android/Robolectric host check, **not a fresh full Gradle/release build**. Runtime classes, resource overlay and Robolectric extraction lived only in a private `/tmp` directory. Compilation and tests were serial under a resource guard.

All **14 selected classes / 75 tests passed**, with no selected-class failure or skipped/ignored test:

| Class | Passed |
|---|---:|
| AcrosII120ReferenceHostTest | 3 |
| AcrosII120ImprintHostTest | 6 |
| MediumFormatCurrentParityHostTest | 1 |
| Medium120OnlyActivityHostTest | 1 |
| FrameFormatCapabilitiesHostTest | 3 |
| Medium120EvidenceHostTest | 4 |
| CineStill120ReferenceHostTest | 2 |
| MediumFormatBorderHostTest | 10 |
| MediumStripVersioningHostTest | 6 |
| MediumImprintMarksHostTest | 2 |
| ImprintGeometryHostTest | 9 |
| ImprintContractHostTest | 21 |
| CineStill120ImprintHostTest | 6 |
| AcrosII120ReviewRenderHostTest (private reference fixture) | 1 |

Coverage includes:
- 320 Acros combinations across five formats, both orientations/materials, four date styles, both date placements and both size extremes. Complete padded masks stay in the centered gap or photo aperture.
- A separate 160-combination Acros check samples the underlying moved raster beneath every stamp, including the neutral-gray negative fill.
- Four photo corners, outputs down to 32 pixels, metadata omissions at tiny sizes, date-only/exposure-only and absent/partial/extreme EXIF values.
- Moved lower glyph pixels and ordering are preserved. Upper arrows/numbers and aperture-side samples are unchanged. No new glyph or source fragment is completed.
- **40 Acros imprinted preview/float-export cases, 9,797,384 exact matching pixels.** The inherited CineStill 40-case matrix was also rerun successfully after the monochrome fill change.
- **290 current 120 preview/float-export cases, 5,786,979 exact matching pixels**, including all supported stocks and aliases.
- Actual Activity controls, default 6×6 for 120-only profiles, all five formats, unavailable 135 rejection, frozen versions and old imprint contracts.
- v9 generator **5/5** checks pass: exact regeneration, opaque neutral rails, source/inference inventory, finite bounds, centered clear zones after remapping and no photographic inputs in packaged assets.

The source counts are now **14 physical 120 stocks / 16 profile IDs**, and **11 physical stocks with reviewed edge-imprint layouts**. The unchanged nullable-file compiler warning in ImprintGeometryHostTest remains; no new compiler failure occurred.

The guard observed a maximum test session RSS of 579,317,760 bytes, minimum MemAvailable of 1,461,116,928 bytes and minimum overlay availability of 3,183,874,048 bytes. Its 768 MiB / 2.4 GiB RSS / 2.25 GiB disk limits were never crossed. Reproducible runtime data was removed after terminal success; logs and source/resource fingerprints remain as evidence.

## Visually inspected outputs

Sixteen actual `BorderPreview.compose` renders cover the five landscape formats, two portrait examples and monochrome negative presentation, with and without optional printing. The source image uses only the left reference's photo interior at (61,41), 570×529, not any original film edge or first-generation Acros panel. It is a layout fixture, not a new scientific simulation. The negative illustration inverts that picture neutrally for review only.

The date and exposure labels deliberately use test data: 2026-11-06, ISO100, 1/125, F5.6, EV0 and 80mm. They are not claimed to be the source photo's EXIF. The final review board labels this explicitly.

Reviewed artifacts:
- `ACROS-II-120-centered-imprints.png`: five-format actual-render board.
- `ACROS-II-66-centered-detail.png`: same lower edge before and after centered printing.
- The actual 6×6 negative render, checked for neutral erased regions and no orange patches.

Private review artifacts are under `/workspace/shared/filmroom-acros120-review`; logs and binding/source fingerprints are under `/workspace/shared/filmroom-acros120-validation`. They are not runtime assets. The source photographs must not be packaged or redistributed with the application.
