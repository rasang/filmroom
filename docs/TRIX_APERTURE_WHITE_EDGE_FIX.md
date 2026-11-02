# Tri-X 7266 aperture white-edge fix

## Observed defect and cause

A thin white fringe was reported along the main image aperture.

In the unchanged original Super8 overlay, Gaussian aperture feather texels at x247/248/249,
y700 have alpha251/228/168. The original classifier started photo ownership at x250, so
those partially transparent aperture texels incorrectly received the **white hole matte**.
This produced the visible thin line even with a uniformly dark photograph. It was not a
requested white perforation, scientific film effect, source-image border or crop stretching.

## Narrow correction

Only new Super8 sampling version `bw-v5-tri_x_7266-super8-fringe-1620504f73` extends image
ownership over the three-pixel left aperture feather. At the touching original rounded
perforation, nearest-boundary classification retains actual hole interiors and their fringe
on the matte side. Original gate/hole coordinates, aspect, crop, frame pitch, rotation,
photograph repetition and all asset bytes remain unchanged.

The new sampler is shared by settled preview, interactive overlay and float export. No
scientific profile, native engine, sharpening, paper logic, other film or thumbnail changes.

The existing v4 definition remains registered with its original sampling. New editor captures
and new exports use v5; already frozen v4 queue jobs still reproduce their saved contract.
Every old runtime-manifest field remains unchanged; the new version is appended separately.

## Validation — 2026-11-02

Base: frozen joint `ec585738`; isolated worktree/branch `filmroom-trix-white-edge` /
`fix/trix-white-edge`. No frozen joint files, current APK, build version, signing or release changed.

Focused Gradle gate succeeded: **19 discovered, 17 passed, 2 explicit external-corpus skips,
zero failures/errors**. Main/test Kotlin and resources compiled successfully. Classes:
`TriXWhiteEdgeHostTest` (3), `BwFrameGeometryHostTest` (6),
`FrameAppearanceContextHostTest` (5), `CandidateBorderCatalogHostTest` (5, 2 opt-in skips).
Log: `/workspace/shared/trix-white-edge-tests.log`.

The new tests reproduce the original white fringe, then verify its absence with a dark photo;
exercise three scales and both orientations; cover rounded-hole joins x247.5–249.5 and adjacent
frame fringes; retain pure-white perforation center and true rounded-hole interiors; preserve
new/legacy queue identities across restart; and compare actual float export and bitmap preview
pixel-for-pixel after output encoding. Existing BW tests also cover live gestures and repeated
current-photo slivers.

Four actual renderer before/after PNGs are in
`app/build/ui-host-renders/trix-white-edge/`. They use a synthetic dark test photograph, not
an altered user photograph or a film-science demonstration. Visual inspection confirms the
legacy line is reproduced and the v5 line is gone in both orientations while white holes remain.
Independent read-only static review found no blocker; independent visual review is recorded
in the task handoff. These are Linux Android native-graphics host checks, not phone-device QA.
