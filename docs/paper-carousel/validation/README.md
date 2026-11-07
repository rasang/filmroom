# Accepted paper models / carousel validation

User accepted both review sheets. Historical 2383/2393/2302 can labels remain illustrative, not independently verified replicas.

- Production Kotlin: all current main Kotlin sources, current generated R.jar and BuildConfig compiled using Kotlin 2.0.21 CLI, JVM 640MiB / metaspace192MiB / SerialGC / single CPU. Exit 0. Initial Gradle in-process attempts failed from daemon loss/heap pressure before success via isolated CLI.
- Host tests: 9 passed, 0 failed. PrintMediumActivityHostTest covers all 10 actual media across 4 locales (40 selections), selection identity, scientific category label and B&W hint, no dialog, repeat click scroll retention, scrolling without selecting, and direct-route disabled cards retaining selection.
- PrintMediumLabelsHostTest: 4 tests of profile/category and localization.
- FilmStockArtworkHostTest: 4 tests including 2302 as print-medium art (never camera-roll artwork), stored selection, and existing film carousel at portrait/landscape/large fonts.
- Host screenshot paper-zh.png was visually inspected. Native render pixels mocked in activity test; this is UI regression, NOT a native/rendering correctness assertion.
- Packaging static check: 10 immutable IDs, 240x240 RGBA WebP, 5 locale resource files, no picker dialog. Total assets ~280KiB.

## Integration requirements

1. Preserve latest dev 8cf5e598 Adreno protections. This branch changes no native source.
2. `none` cards are temporarily disabled (`film.id!="none"`, `id!="none"`) until the independent native/model none implementation is integrated. Remove those two guards ONLY with that implementation, then run four film/paper combinations and recipe/backups compatibility tests.
3. `film_none` is defined by this branch's paper_carousel.xml AND none owner's strings.xml. During integration keep the owner's strings.xml definition, remove film_none here; retain paper_none.
4. No APK built, no push/release. Current verification is focused, not complete suite.
5. Renderer reproducible scripts under tools/paper-artwork. Approved NoFilmArtwork copied from b1dfe380.
