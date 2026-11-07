# CineStill 800T 120 reference and imprint validation

## Scope

This independent change starts at `5a11522c4ae37d1989c4df76912f6e581d40f272`. The reference asset was introduced by `db7ff6429b050871325a72dac201ffb9d1c0360d`; the following imprint change preserves its bytes. The user's five approved photos provide an unperforated appearance, not authenticated continuous-roll, manufacturing-date, density or camera-gate measurements. The source photos are not packaged. See `tools/medium120/v8/README.md` for reconstruction limits and source hashes.

The change is not part of official 21.1 or the frozen code53 beta. No APK was built, signed or published for this change.

## Placement and preservation

The first imprint version kept the lower marks in place and used an off-center original gap. At 14:05 UTC the user explicitly requested the existing strategy instead: center date/parameters and move the original markings toward the sides. The current implementation follows that latest instruction; the previous review is retained only for comparison.

- Each format has a centered clear interval: 645 13.75–33.75mm; 6×6 15–47; 6×7 18.5–57.5; 6×9 22.5–67.5; 6×17 60.5–113.5. All are centered at half the physical frame width.
- Existing lower-rail legend/code/A pixels move toward the sides with the existing `Standardized` raster mechanism. Every existing visible lower ink column is included; source order and glyph shapes are retained. Full copy windows have at least 0.5mm separation from the clear interval. Already cropped 645/69 code fragments remain partial, not completed. The 645 integer crop follows the previous 476-pixel convention, with the existing 0.21% sampling adjustment.
- The 645 full legend cannot fit beside a centered 26.25mm reserve without new clipping or altered glyphs. Its reserve is therefore 20mm; the date/parameter type fits that width. Larger formats have 32/39/45/53mm centered reserves.
- Upper-rail numbers/arrows and the aperture are outside the modified region and are unchanged. Turning printing off returns the unchanged source-reference appearance. These translations are explicitly presentation layout, not physical film evidence.
- Complete padded stamp bounds, including glow, fit in the central interval. Photo dates retain the new aperture-padding guard (`protectPhotoDate`) at small output sizes. The prior off-center `keepOriginalMarks` path has been removed.
- Legacy 45 reviewed layouts retain their original placement/remapping arithmetic. All 28 earlier 120 PNGs and the v8 PNG are byte-identical. Native/scientific processing is unchanged.
- Extremely small outputs may omit fields if their minimum padded masks cannot fit. Missing metadata does not create invented fields. Unknown asset versions or nondefault strip centers do not receive this layout.

## Executed verification (2026-11-06)

Resource-constrained execution reused read-only compiled base classes and Android resources from the exact baseline. Four changed production Kotlin files and nine test source files were compiled with Kotlin 2.0.21 as independent overlays; the private resource APK adds only the v8 PNG. SDK35 native-graphics Robolectric ran one JVM at a time. Each JVM used an exclusive disk temporary directory, removed at process exit. The release tree/cache was not edited.

This is focused incremental validation, not a new whole-tree Gradle build, lint run, device run, signed APK or release gate.

Initial source-aligned run 13:57–14:00 UTC: 12 classes, 67 tests passed. After the centering revision, 14:10–14:13 UTC: all seven directly affected render/geometry/parity/imprint classes were rerun, 43 tests passed. The unchanged Activity/capability/versioning tests retain their earlier results; this is not a claim they were rerun after the revision.

| Class | Initial run | Centered rerun |
| --- | ---: | ---: |
| CineStill120ImprintHostTest | 6 | 6 |
| CineStill120ReferenceHostTest | 2 | 2 |
| MediumFormatCurrentParityHostTest | 1 | 1 |
| CineStill120ReviewRenderHostTest (private user-photo fixture) | 2 | 2 |
| Medium120OnlyActivityHostTest | 1 | not rerun |
| FrameFormatCapabilitiesHostTest | 3 | not rerun |
| Medium120EvidenceHostTest | 4 | not rerun |
| MediumFormatBorderHostTest | 10 | not rerun |
| MediumStripVersioningHostTest | 6 | not rerun |
| MediumImprintMarksHostTest | 2 | 2 |
| ImprintGeometryHostTest | 9 | 9 |
| ImprintContractHostTest | 21 | 21 |
| **Total** | **67** | **43** |

All executed classes exited 0. No selected class has ignored/assumption-skipped cases. Production and test delta compilation passed. The existing nullable-file compiler warning in ImprintGeometryHostTest is unchanged.

Coverage includes:
- 320 combinations of five formats × two orientations × two materials × four date styles × photo/edge placement × two size extremes, checking full padded stamps against the centered gap or photo aperture, and checking the entire edge group is centered to within one output pixel.
- Every moved source pixel and blank center was checked through the actual raster for all five formats, both orientations and both materials; top rail/aperture samples are unchanged. An independent numerical audit also checked 10,001 blank interval samples per format and all copy windows.
- Four photo corners and outputs down to 32 pixels; date-only, exposure-only/date-disabled, absent/partial metadata, extreme numeric EXIF values, and invalid-version/nondefault-center rejection.
- **40 imprinted preview/float-export combinations and 9,797,384 exact matching pixels**, covering five formats, both orientations, both materials and both date placements.
- **270 current 120 preview/float-export combinations and 5,387,877 exact matching pixels**, including older stocks and aliases.
- Actual Activity navigation for 120-only stocks, unavailable 135 rejection, frozen version restoration, old geometry and imprint contracts.
- v8 generator **3/3** and prior v7 generator **2/2** passed; asset SHA remains `a041a70e696fd36216498ad68524d1e7c96499789ace8a14a08afe442194743f`.

## Review artifacts

The private review uses the interior of user photo 1000036446 only, cropped at (76,270), 1158×1158. It is an already-developed image used to inspect presentation, not a fresh scientific film simulation. No original border or watermark is reused.

Eight plain-border renders and seven imprinted renders were produced by actual `BorderPreview.compose`. The imprinted examples deliberately use test metadata: 2026-11-06 12:34:56, ISO800, 1/125, F5.6, EV0 and 80mm. These values are not claimed to be the photo's EXIF. The boards explicitly label them as examples. `800T-120-imprint-centered-comparison.png` compares the prior offset placement with the current centered strategy; the full five-format board uses the latest centered renders. All five formats, a portrait example and a photo-date example were visually inspected.

Local evidence and artifacts remain under `/workspace/shared/filmroom-cinestill120-validation` and `/workspace/shared/filmroom-cinestill120-review`; they are not runtime assets. The first directory contains compiler/JUnit logs, classpath/resource binding hashes and source fingerprints. The second includes `800T-120-imprint-positions.png`, individual renders and provenance. Do not redistribute the source photo as part of the repository or application.
