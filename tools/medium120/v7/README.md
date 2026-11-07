# Three source-guided B&W 120 strips

This is the same authored, approximate reference workflow as earlier 120 revisions. It does not package photographs, scan pixels, or a fictitious 135 border stretched into 120. HP5 PLUS, T-MAX 100 and TRI-X 400 (400TX) are newly enabled. The full audit, including verified but still unsupported 120 films, is in `docs/120_FILM_AUDIT_20261106.md`.

HP5 uses the third unbroken strip of the second photograph in the Photrio source. All upper indices 10–14, left triangles, the lower `ILFORD HP5 PLUS` legends, source batch `4274`, and the small `A` are visible. The terminal legend is clipped to the visible `ILFORD HP5`. A candidate based on the third photograph was rejected because its coordinate notes were inaccurate and glare hides an index. No positions were combined across photographs. Nominal coverage is 184.159 mm.

T-MAX uses one unbroken panoramic print on PDF page 76 (printed page 63) of the University of Victoria dissertation. Both edges are visible: upper 46–49 with `KODAK 100TMX`, lower 5–7 and independent right triangles. The right-clipped legend contains only `KODAK 1`. Nominal coverage is 174.715 mm, only 0.715 mm longer than the 174 mm 6×17 model window. This is a borderline nominal model, not a physically measured guarantee. It may need a new version after a calibrated scan becomes available.

TRI-X 400 uses the right-hand continuous four-frame 645 strip in the July 2024 fotoPhilosophy photograph. Both edges are readable: upper 45–48 and KODAK 400TX, lower 4–6 with seven independent right triangles. Nominal coverage is 195.454 mm. This resolves the earlier short-schematic evidence gap without repeating or extrapolating that schematic.

All typography, gray values, cap heights, source positions, and scaling are estimates. The model uses 56 mm for the photographed image aperture. It is not metrology, exact factory artwork, universal batch coding, or proof that other variants/eras have the same printing. Negative display is neutral gray without an orange mask. Density is illustrative. Existing color assets and all frozen versions are untouched.

The finite bounds are enforced by runtime snapshot verification. Unknown space outside them is unavailable, not evidence of blank physical film. The strips are never tiled. Formats and orientations crop the same physical coordinates; they do not stretch or restart the labels. Maximum encoded file is 23,307 bytes; decoded storage is 1,640,000 bytes per strip. No holes or transparency.

## Reproduce

    python3 tools/medium120/v7/generate.py --output /tmp/filmroom120-v7
    python3 tools/medium120/v7/test_generate.py -v

Only the three `*-rails.png` files belong in runtime assets. `authored-evidence.json` records exact PNG hashes, version IDs, source URLs, all observed marks and finite bounds. `generate.py` pins its DejaVu font. Review PNGs are inspection outputs, not film assets.

Frozen v7 snapshots deliberately have no exposure-metadata standardization. They retain their original date-on-photo fallback and omit edge exposure metadata, including after an app upgrade.

The separately versioned v11 presentation adds 15 centered date/parameter layouts for these same three source PNGs. Existing lower marks and crop-edge fragments move outward in source order; no glyphs, rail pixels or physical evidence are added. Upper rails and the original PNG bytes remain unchanged. HP5's long lower legends require narrower centered clear intervals than T-MAX/TRI-X. New snapshots use v11; the registry still resolves v7 independently. The shared renderer fits all fields at a common size and reports omissions if a tiny output cannot fit them. See `imprint-layout-provenance.json` and run `python3 tools/medium120/v7/verify_imprint_layouts.py` for the independent source pixel-center and guarded-placement checks.

The imported scientific film response data retains its upstream format and fixed-development metadata. Adding a physical 120 presentation does not create a separately measured 120 emulsion/base-density calibration, and no scientific response arrays are changed by this patch.
