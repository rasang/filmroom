# BW frame integration validation — 2026-11-02

## Result

Ready for joint Beta 2 integration, subject to its full regression/build gates. No APK,
release, signing, publication or native bundled-library change was performed by this work.
Base: `1a05595fa4fda0a9676a0a38466ab727cc5ed6b6`; companion upstream scientific integration
commit: `708abc217b98d44536ff4dbc5f76b6f17fac8f92`.

Final focused Gradle gate: **79 tests discovered, 73 passed, 6 opt-in skipped, zero failures/errors**.
Android main and test Kotlin compilation and resource processing succeeded. This is Linux
Robolectric/Android native-graphics host evidence, not physical-device acceptance or performance.

Log: `/workspace/shared/bw-frame-final-tests.log`.
Reproduction script in the shared integration workspace: `/workspace/shared/run-bw-frame-final.sh`.

The initial final run exposed one obsolete test expectation: the film-strip last card was
hard-coded to old Vision3 50D. Both artwork/density tests now derive the last shooting profile
from the catalog. The complete focused gate was rerun successfully, not merely the failing method.

## Executed coverage

* BW geometry: approved hashed lossless asset decode, image-window/perforation separation,
  correct matte, all stock layouts/orientations, geometry/JSON validation, crop center and zoom,
  adjacent current-photograph Super8 sampling at identical scale, shared float export/preview
  exact encoded-pixel parity, six queue contracts across restart.
* Actual PhotoView: all six appearance variants in both orientations, geometry readiness,
  live pan, gesture commit/discard and 24 generated PNGs (12 exact settled, 12 live Canvas).
* UI state/control integration: genuine BW capability graying, development-time controls,
  frame/print role, stock/route switches, preserved parameter preferences, BW→color explicit
  orientation normalization and unchanged legacy implicit color crop.
* Current/legacy registry inventories, route contexts, white-hole state/history, original
  73 contracts and source bytes, artwork inventory/transparent 240px loading, actual film strip
  selection and large-font layouts, film controls/preferences and bounded diagnostics.

Included test classes: `BwFrameGeometryHostTest`, `BwProfileStateHostTest`,
`FrameAppearanceContextHostTest`, `CandidateBorderCatalogHostTest`, `DirectRouteBorderHostTest`,
`Complete18BorderCorpusHostTest`, `WhiteHoleMatteHostTest`, `FilmStockArtworkHostTest`,
`InteractiveOverlayContractHostTest`, `FilmEffectsControlsHostTest`,
`FilmSelectionPreferenceHostTest`, and `diagnostics.*`.

## Independent review

Read-only reviewer found no blocker in geometry, queue identity, effective crop normalization,
last-stage alpha/color handling, portrait repeat mapping, original asset provenance, or legacy
contract retention. Independently verified all seven new payload hashes/dimensions and exact
5222 visible-RGB inversion with identical alpha.

Reviewer inspected 12 representative actual runtime settled/live PNGs: correct 5222 whole-film
polarity and independent white/black holes/full fixed-sample edge row; coherent Super8 rounded
gate, adjacent image slivers and portrait/live crop; 2302 black surround, light clear rails and
blank unverified lettering. No visible stretching, baked sample, obvious seam or clipping.
Integer photo height is within 0.5 output pixel of the single uniform atlas scale.

Evidence directory: `app/build/ui-host-renders/bw-runtime/` (24 PNGs). The checker photograph
is a synthetic geometry-test fixture, **not** a claim about the scientific look of any film.
Neither these review renders nor third-party references enter the APK.

## Remaining joint gates

The six intentional skips require the external original PNG corpus, an explicit fresh atlas
rendering JVM (`FILMROOM_BORDER_CORPUS=1`), or real codec JNI libraries. Specifically:
Complete18 corpus; Candidate all45 decode and PNG parity; DirectRoute all matte rendering;
WhiteHole all-stock rendering and real-codec output. Joint integrator owns these full regression
gates, updated native host/JNI probes, both Android ABIs, aggregate lint/tests and final build.
`FilmStripDensityHostTest` count/last-card expectations were updated but this expensive matrix
was not run in this focused gate. No skipped stage is represented as passed.
