# Five reviewed 135 borders

These exact 1520 × 1400 lossless WebPs use one uniform 38 × 35 atlas with a
36 × 24 still-photo aperture. Perforations, lettering, and fixed 25/25A are not
stretched or borrowed from 7266/5219. HP5's observed bars are not decoded or
certified machine-readable. The raster assets contain no reference photographs.

Positive A was approved after the v2 geometry review. Its near-black material
is a presentation proposal, not measured emulsion density. The separately
accepted direct variants follow the existing D65 / profile-base / channelwise
Dmax display-model rule. Four B&W variants use their own silver-density data;
CineStill follows its existing 500T data inheritance. No scientific input data changed.
The subsequent authorized halation commits e11eccc1, 90f29132 and 7e43e641 do
change native processing code. They are checked against their exact approved
final blobs, separately from this UI integration; do not call all native code unchanged.
The copied original direct review manifest deliberately retains its historical
`runtime_approved: false` review-stage label; subsequent acceptance authorizes
this integration, not a claim of photographed negative colour. Direct uses
clean v2 lettering coverage rather than the positive A's microscopic texture.

The loader matches exact stock + asset version before selecting the new raster.
Rounded aperture alpha is sampled in linear light, with a separate photograph
coverage channel. Legacy fixed-135 donor sampling, geometry versions, compositor,
old assets, and old frozen queue versions remain unchanged. Both white/black
perforation mattes have separate pinned versions.

135 capture parameters retain the existing TOP inter-perforation placement,
shared type-height fitting and odd/even gap selection. New asset versions only
adapt gap centers to their actual perforation phase, with one final output-pixel
rounding on that axis. The original 2.35 mm full-mask fit remains unchanged. The
1.981 mm wide holes leave 2.675 mm of opaque gap after alpha feathering. Date text remains inside
the photo with its existing corner/size/glow controls; edge dates remain 120-only.
All original atlas pixels and 25/25A remain immutable, including when imprinting.

The earlier lower-edge 135 relocation and HP5 A/B proposals were withdrawn on
2026-11-06 after the user corrected the location. Their sources and render logs
are retained as historical evidence outside the active test suite. The mistake
was interpreting the original `nativeHeight - base - coreHeight` Y coordinate
as a lower-edge position. Those earlier layout passes are not final validation.

`verify_integration.py` verifies approved A/direct bytes, alpha, 25/25A,
unchanged old assets except the explicitly accepted Kodachrome dual-format thumbnail
and its two manifests (120 README documentation is listed separately), unchanged
production native code except the 29 specifically approved halation paths,
unchanged Android engine implementation and scientific data, and all 25 baseline film-roll hashes. Exactly one WebP may differ, pinned to
accepted commit 72c4105b; the other 24 stay identical. The only FilmStockArtwork change is the authorized
Acros card-label line break: “Fujifilm Neopan” / “100 Acros II”. It retains all
name tokens, card width, type size, artwork and selector behavior. It reads the frozen review inputs from the shared
review workspace and can emit a complete hash manifest to an explicit path.
Android host tests independently pin A hashes and exercise actual decoding,
rounding, sampler math, mattes, snapshots, UI, and all-pixel preview/export parity.

No new APK, signing operation, publication, or release is part of this change.

## Portable editable reconstruction

The complete three-stage source, pinned input/seed/environment records and
licensed fonts now live in `build/`. Its explicit-output CLI never installs
assets or changes registry entries. In the recorded environment, all ten
regenerated WebPs matched the approved production files byte for byte and
decoded RGBA for RGBA on the first run. See `reproduction/` for the actual
receipts; other encoders may claim only verified RGBA equivalence. No reference
photographs, unapproved B material or old gray/orange drafts are included.
