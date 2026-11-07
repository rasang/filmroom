# 120 expansion implementation and validation

Historical v7 report. Later v11 revisions reuse the same three PNGs and add centered edge dates/parameters; frozen v7 queues keep the original behavior described below. See `THREE_BW120_IMPRINT_VALIDATION_20261107.md`.

The source audit is `120_FILM_AUDIT_20261106.md`. It covers all 11 previously unsupported camera films plus the five imported SpektraLab films, keeping exact manufacturer products, conversion products and unverified histories distinct. The runtime change enables only HP5 PLUS, T-MAX 100 and TRI-X 400 (400TX). Historical Superia X-TRA 400 and Kodachrome 64 Professional existence is confirmed, but neither is assigned invented artwork. ACROS II and CineStill 800T also remain without reviewed edges.

## Implementation

- Three independently versioned finite strips, with inspected source provenance and a reproducible pinned-font generator. No photo pixels, tiling, 135 stretching, guessed numbering or generic brand aliases are shipped.
- Twelve physical 120 strips / fourteen profile IDs, including the existing Portra push aliases. All 25 earlier PNGs and their frozen revisions are unchanged; the total is now 28.
- The new three are 120-only presentation capabilities. The generic format policy selects 6×6 on first enable, offers only the five supported formats, and preserves a valid 120 choice across frame toggles.
- The new negative display has no orange mask. Its gray mapping is illustrative, not a measured density or a new 120 scientific calibration. Existing color mappings and upstream science arrays are unchanged by this patch.
- No unreviewed edge-exposure metadata layout is registered. Date marking falls back to the photo with the B&W palette. The previously reviewed 45 medium-format metadata layouts stay intact.

T-MAX's nominal 174.715 mm source span narrowly covers the 174 mm 6×17 model. Perspective, image-aperture calibration and typography remain approximate. A future calibrated reference should create a new immutable revision, not overwrite these assets.

## Validation method

The normal Gradle build completed resources but its production Kotlin daemon was terminated by the constrained host. A single-JVM Kotlin 2.0.21 compiler then successfully compiled all 181 production Kotlin files and all 22 selected test/helper files from this integration tree. BuildConfig was compiled with JDK 17. No old production-class overlay was substituted. The later FilmStripDensity test-only isolation change was compiled separately against those exact classes.

Android resource APK, assets and Robolectric test configuration were generated from this tree. JUnit ran on the same production classes with the generated resources. This is host source/runtime validation, not a successful APK build, physical-device validation, or final JPEG/TIFF codec certification. Existing Android native libraries have not been rebuilt for the imported scientific profiles and must not be represented as a release-ready APK.

Several initial test processes were killed with exit 137. Investigation found roughly 2.9 GiB of abandoned Robolectric native-runtime extraction directories in the RAM-backed `/tmp`, retained after earlier kills. After owner coordination, only those regenerable directories were cleared. Subsequent runners use their own disk-backed temporary directory per JVM and remove it after that process terminates. SDK caches, source files, screenshots and evidence are preserved. The 16-layout film-strip test retains every original assertion and its full default matrix; its optional case selector permits isolated execution without reducing coverage.

## Executed results

All **20 Android host test classes / 71 test methods passed**, plus **2 generator tests**. Final runs included:

- Reproducible asset generator: 2/2 tests, exact PNG hashes, finite bounds, grayscale/opacity and observed-mark inventories.
- Current 120 preview/float-export parity: 250 combinations and 4,988,775 output pixels, exact equality. Covers all 14 profiles, five formats, both orientations and each supported route.
- New 120 evidence/runtime tests: 4/4, including unsupported profiles failing closed, finite crop rejection, B&W negative display, legacy orange-mask preservation and metadata fallback.
- Real MainActivity entry for all three 120-only stocks: frame enable defaults to 6×6, selection reaches 6×17, unavailable 135 is absent, perforation controls stay disabled, and toggling the frame preserves the valid format.

The final FilmStripDensity run executed its default **16-layout matrix in one fresh JVM**, with 16 explicit case-pass records and exit 0. The old medium-format Activity class likewise passed both methods together. Isolation was available as a diagnostic aid, not used to reduce final coverage. No interrupted or pending process is counted as a pass.

| Host test class | Passed methods |
|---|---:|
| SpektraLabProfilesHostTest | 3 |
| BwNeutralFiltersHostTest | 2 |
| FrameFormatCapabilitiesHostTest | 3 |
| Medium120OnlyActivityHostTest | 1 |
| BwProfileStateHostTest | 3 |
| FrameAppearanceContextHostTest | 5 |
| FilmStockArtworkHostTest | 4 |
| FilmStripDensityHostTest | 1 |
| CandidateBorderCatalogHostTest | 5 |
| NegativeWhiteHolesAvailabilityHostTest | 3 |
| PrintMediumFrameOwnershipHostTest | 3 |
| imprint.ImprintGeometryHostTest | 9 |
| MediumFormatBorderHostTest | 10 |
| MediumFormatCurrentParityHostTest | 1 |
| MediumFormatStateHostTest | 3 |
| MediumStripVersioningHostTest | 6 |
| MediumFormatActivityHostTest | 2 |
| MediumFormatArtworkHostTest | 1 |
| border.MediumImprintMarksHostTest | 2 |
| border.Medium120EvidenceHostTest | 4 |

Validation logs, reproducible local compiler/runner scripts, exact class log hashes and the compiled-source manifest are retained in the sibling `filmroom-120-validation` directory. The final summary is `final-results.json`. Normal Gradle/Android packaging and the new native scientific libraries still require integration validation before any distribution. No APK was built or published by this task.
