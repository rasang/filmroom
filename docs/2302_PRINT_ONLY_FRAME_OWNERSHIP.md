# 2302 affects print rendering, not frame selection

User correction: selecting 2302 must not replace the selected capture film's frame.
New editor previews and export captures always derive the frame stock from
`filmStock`, independent of paper and route. Selecting 2302 retains its complete
scientific print profile, tonal response, exposure, development and gamma controls;
this is not an RGB-only cosmetic substitution. No scientific/native code or data
changed. The previous removal of the 2302 selector thumbnail is unchanged.

- 5222 + 2302 uses the existing 5222 frame.
- Color capture film + 2302 retains that capture film's frame.
- Direct scan remains independent of paper; Super8 7266 keeps its own geometry.
- Border off still means no frame.
- White-hole capability follows the capture stock and route; 2302 no longer
  disables the capture negative's hole control.
- Changing paper does not change the frame's geometry, aspect, pan or zoom.
- Preview, export enqueue and output-size UI all share BorderCatalog.frameStock.
  Paper remains in scientific parameter/cache identity because it still changes
  the processed photograph. Final frame version remains part of frozen snapshots.

## Historical queue compatibility, not reinterpretation

The old 2302 frame asset, geometry and registry version remain byte-for-byte intact.
An existing frozen Beta2/3 frame is admitted only when it is specifically
`bw-v4-print_2302-white-d84db54b27`, geometry v4, stock 2302, PRINT appearance on
print route, with the 2302 paper and a known negative capture film. Its composition
is validated using its explicitly frozen 2302 geometry rather than the new editor
frame selection. All canonical dimensions, parameter/appearance matching, file
and registry validation remain in force. Changed paper/route, arbitrary version,
missing appearance and border-disabled mismatches are rejected.

Old jobs keep their original border and final dimensions; new jobs never select
2302 through the editor/queue frame resolver. This intentionally does not rewrite
past outputs or paused jobs. There is no native-data change to invalidate existing
science checkpoints in this patch. No legacy assets are deleted.

## Scope isolation

Base: Beta3 `237eb8b57c33ea0d354d2b1bd263c48ced911624`.
The pending translucent 5222 material/route experiment is NOT included. No app
version, bundled library, APK, signing or publication change is included.

## Validation plan / evidence

`PrintMediumFrameOwnershipHostTest` covers all catalog films and legal routes,
both orientations and hole preferences, 2302 scientific values in serialization,
new/old queue restart contracts and rejection cases, plus six actual capture-frame
preview/float export pixel comparisons. Existing BW state/artwork tests are updated
to assert the new frame ownership, while explicit historical 2302 raster/queue
coverage remains. Actual gate results are recorded in the final handoff; this
plan alone is not a pass claim.

### Focused result, 2026-11-02 08:00 UTC

Gradle `:app:testDebugUnitTest` selected seven classes: **25 passed, 0 skipped,
0 failures/errors**, BUILD SUCCESSFUL in 2m29s. Classes: new
PrintMediumFrameOwnershipHostTest (3), BwFrameGeometryHostTest (6),
BwProfileStateHostTest (3), FilmStockArtworkHostTest (4),
NegativeWhiteHolesAvailabilityHostTest (3), BwPersistedRenderContractHostTest (3),
TriXWhiteEdgeHostTest (3). External log: `/workspace/shared/2302-print-only-frame-tests.log`.
The integrator independently reviewed the three production touchpoints and found
no static blocker; final review binding is recorded in the handoff.

This is focused host validation, not a full application/ABI rebuild or a device
upgrade test. Output size ownership is verified at the immutable job/snapshot
boundary; preview/float export compositors are exercised, not a new end-to-end
encoded-media metadata run. Native rendering, profiles, frame bytes and existing
media encoder code are unchanged.
