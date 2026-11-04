# Gold200 / Ektar100 v2 local evidence reconstructions

These are newly authored illustrative rails, not photographic cutouts. The generator does not load any source photograph. No application source or old asset was modified. RGBA PNGs are8200×50, opaque, at10px/mm; top rail rows0–24, bottom rows25–49, baseRGB8,8,8. Font is an approximate DejaVu Sans Bold, uniformly scaled with tracking. Stock ink colors retain the prior illustrative palette and are not claimed to be measurements of the photos.

## Independently inspected sources

- Gold: photographer's physical darkroom contact print, https://www.photrio.com/forum/attachments/dscf7174-jpg.416154/ . Context https://www.photrio.com/forum/threads/lucky-c200-vs-kodak-gold-200-in-120-side-by-side-comparison.217701/ identifies Kodak Gold200, Mamiya645Pro and first five exposures. Inspected full4000×3055 original gold-contact.jpg and3610×980 brightness-only crop gold-contact-strip.jpg. Own upper marks41–45 with KODAK200, lower1–4 with independent and numbered right triangles. Lower1 is dim and its precise glyph shape is not verified. No Lucky rail, adjacent cropped Gold rail, or catalogue KODAK200 segment was borrowed.
- Ektar: https://rdsgallery.co.nz/wp-content/uploads/2025/10/2025_Eskdale_catalogue_sm.pdf page5, embedded902×676 original eskdale-p5-000.png and third strip rotated clockwise/enlarged3× as2028×690 eskdale-p5-000-strip.png. Inspected both. Own upper48–51 / KODAK EKTAR100; lower7–9 with three independent right triangles and three numbered right triangles. No extension to1–56 or inference from another stock.

Research photographs are not included in application deliverables; source URLs above identify the originals. Semantic source landmarks use the named inspection image's pixel coordinate system; this avoids claiming a spurious millimetre measurement or a coordinate in the wrong orientation.

## Calibration and limits

Gold uses about845px across the nominal56mm image height, rounded to15px/mm. Portrait645 image widths around620px divided by nominal41.5mm give14.94px/mm as a cross-check. Continuous visible x20–3540 gives estimated234.6667mm coverage, centered410mm. Perspective, crop shape and source resolution limit precision.

Ektar uses approximately592px longitudinal aperture width at nominal56mm, giving592/56px/mm. Two-rail usable span x56–1962 gives estimated180.2973mm. This is enough for the174mm designed6×17 interval323–497 with about3.15mm margin at either end, but it is not a certified ruler measurement or200mm span. Both sides and the three photographic gates are present in one continuous source strip. No enlargement of the nominal physical scale was used to force the fit.

All marks inside the observed segment are represented, including marks outside the current default crop. Content clipping at an arbitrary crop boundary is expected for a continuous rail. Unobserved atlas regions are deliberately blank. Any future position control must constrain the *entire requested crop* to coverageStartMm/coverageEndMm. Current defaultCenterMm410 is supported for all five formats.

JSON y_mm is local to its2.5mm rail, not physical y across61mm film. Each mark's width/height reflects the procedural illustration geometry; source landmarks identify approximate centers, not certified bounding boxes. Text dimensions and spacing are estimates chosen against each source independently.

## Build and verification

Run `python generate.py` with Python/Pillow and the stated font installed. It writes both JSON semantic maps, packed rail PNGs,6×17 review PNGs, manifest, and reports tests. Review centers are synthetic neutral matte, never source photographs. The JSON maps specify semantic marks, source links/landmarks, typography, coverage and default center. The manifest contains exact SHA256,byteCount and integration versions.

Tests assert opaque alpha,8200×50 size, all five full crop intervals(47.5,62,76,90,174mm) inside coverage, exact observed numeral sets, independent triangle counts, all mark bounds, and non-base pixels at every mark. A second generation was checked for unchanged rail hashes. Both review PNGs were visually inspected: single uniform glyph geometry, both rails visible, independent triangles present. No endcaps or guessed marks are included.
