# SpektraLab Android integration

Five additional camera profiles are catalog-driven: Kodak Tri-X 400 (400TX), Kodak T-Max 100, Fujifilm Neopan 100 Acros II, Ilford HP5 Plus 400 and CineStill 800T. The existing `kodak_trix` is still the separate Tri-X 7266 Super 8 reversal profile; it retains its direct route and 8 mm default.

The four new B&W negatives default to Ilford Multigrade IV RC. This paper is a necessary dependency of their source defaults, not a camera stock or frame. At TH-KG3, database mode supplies the fitted grade-2 pack C=0, M=0, Y=68; source print-exposure multiplier 1 corresponds to app EV 0. CineStill inherits Vision3 500T's Kodak 2383 default print medium. This is profile/default integration, not a claim of output parity with SpektraLab's renderer.

- Fixed-development profiles do not expose the Double-X/2302 development-family sliders.
- B&W negatives disable saturation, inter-layer DIR, per-channel grain scale and colored halation controls. Multigrade disables color-paper channel morph controls but retains scalar paper gamma, spectral enlarger filters, preflash filters and exposure. Its three physical emulsions remain native-engine data, not RGB dyes.
- Film changes preserve scientific values, manual filter values/database preference, explicit route preference, and non-default paper selections. Reset restores the selected stock's default paper/route and database filters.
- No new physical edge artwork or roll posters are invented. New stocks show the existing neutral film icon. When neither 135 nor reviewed 120 artwork exists, selecting the stock turns an existing frame off rather than borrowing another stock's markings. For a stock with reviewed 120 artwork but no 135 artwork, enabling a frame chooses 6×6 unless a valid 120 format is already selected; the composition picker does not offer unavailable 135. Existing 135/120 stocks and frozen historical queues keep their behavior. New 120 assets are integrated separately.
- Source attribution and source notes are accessible in the license dialog in all four application languages; legal/source text remains in its original language.

## Host checks

`SpektraLabProfilesHostTest` checks catalog/defaults, the fitted filter pack, diagnostics allowlists, control capabilities, parameter/JSON round trips, route and paper selection, and honest artwork fallback. Existing catalog, neutral-filter, B&W state, stock-card and film-strip tests are updated to cover 27 camera profiles and 10 print media without pretending every new stock has artwork.

### 2026-11-06 verification status

- Static data checks passed: 27 camera profiles, 10 print media, all five new identities/defaults, four fitted grade-2 filter packs and generated catalog SHA parity.
- Initial Gradle production and focused-test compilation passed. An early focused run identified the missing CineStill + Kodak 2302 + TH-KG3 allowlist entry, which was corrected before the final tests below. The calibrated B&W-involved pair count is 43.
- The integrated profile + reviewed-120 tree compiled all 181 production Kotlin source files with the same K2 2.0.21 compiler in a single JVM. The 22 focused test/helper source files also compiled. This was a full production compile, not an overlay of old classes. Resource APK, assets and test configuration came from that integrated tree.
- All 41 tests across 11 profile/UI classes passed: `SpektraLabProfilesHostTest` (3), `BwNeutralFiltersHostTest` (2), `BwProfileStateHostTest` (3), `FrameAppearanceContextHostTest` (5), `FilmStockArtworkHostTest` (4), `CandidateBorderCatalogHostTest` (5), `FrameFormatCapabilitiesHostTest` (3), `NegativeWhiteHolesAvailabilityHostTest` (3), `PrintMediumFrameOwnershipHostTest` (3) `ImprintGeometryHostTest` (9) and `FilmStripDensityHostTest` (1 test covering all 16 layouts).
- The integrated real `MainActivity` test also passed for all three separately reviewed 120-only stocks (HP5 Plus, T-Max 100 and 400TX): enable frame at 6×6, choose 6×17, offer the five supported 120 formats without an unavailable 135 option, disable sprocket-hole controls and retain 6×17 after off/on. The 120 asset evidence and exhaustive pixel-parity report are maintained by that integration.
- The complete film-strip density matrix passed in one default invocation: all 16 layouts, every original assertion and all 32 screenshots, in 16 seconds. No case selector was needed for the final result. The test additionally supports `-Dfilmroom.filmstrip.case=<width>x<height>-<dpi>dpi-font<scale>` for isolated diagnosis, while the default still covers sizes 320×780, 393×820, 600×960 and 780×393, each at font scales 1 and 2 and densities 160 and 440 dpi. Screenshot and fixture bitmaps are recycled and expanded dialogs dismissed.

Early Gradle/test attempts exhausted available memory or were terminated by the host. Investigation found 15 stale Robolectric native-runtime extraction directories occupying 2.885 GiB of RAM-backed temporary storage. After reclaiming only those inactive runtime copies, the previously interrupted tests passed without dropping assertions or layouts. Final checks used full single-JVM compilation and sequential direct JUnit execution with real Robolectric native graphics; temporary native runtime extraction was moved to a disk-backed per-process directory and cleaned after exit.

Final integrated source: `a3701087335d39cdaf1ce79b31d34d48f21ec04e` on the separately reviewed 120 branch, incorporating the profile production patches and film-strip cleanup. Logs are retained in `filmroom-120-validation`; native and data audit results are retained in `filmroom-spektralab-validation`. No full Android test-suite or lint pass is claimed.

The precompiled Android native libraries are still the old build. A new APK must rebuild the native engine before these profiles can render. No APK or device test was performed; host UI checks do not establish Android device/GPU visual parity or physical-film appearance.
