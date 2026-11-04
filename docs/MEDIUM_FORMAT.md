# 120 output frame contract

This feature changes the final crop and film-edge presentation only. It does not change the native scientific `filmFormatMm`, grain, halation or optical computation. The film-width scientific control remains independently adjustable. It is not a claim of complete camera/120 optical simulation.

## Available frames

Nine physical stocks support 120: Kodak Portra 160, 400, 800, Gold 200, Ektar 100, Ektachrome E100, Fujifilm Provia 100F, Velvia 100 and historical Pro 400H. Portra 800 +1/+2 are processing aliases of the same physical 800 artwork and edge strip. No 120 option is fabricated for the other profiles. Choosing another stock without a 120 version returns to its existing frame. The 135 path, existing monochrome frames and immutable historical queue records retain their earlier contracts.

The crop is selected under the film's frame options. A nullable medium-format setting represents the existing 135 output. In 120 mode, perforation-matte selection is disabled because 120 has no perforations. Landscape/portrait is explicit, including square 6×6, where orientation selects which pair of sides carries the long film edges.

Nominal design geometry is 61 mm strip width, 56 mm image width across the strip and longitudinal apertures of 41.5, 56, 70, 84 and 168 mm for 645, 6×6, 6×7, 6×9 and 6×17. Each end adds a 3 mm design margin. These are nominal model dimensions, not measurements of a particular camera gate; real camera apertures and scanned margins differ. 645 landscape rotates the strip so that its rails are left/right. Other landscape apertures use top/bottom rails. One uniform scale is used, without stretching text or reshuffling it per frame size.

## Evidence and unresolved detail

The edges are synthesized reference reconstructions, not sampled scans. Each physical stock uses one finite continuous master strip and all five apertures sample it at the same frozen strip center. This proves continuity of the authored artwork, not exact correspondence to a physical roll. Typography, absolute spacing, inter-edge phase and color remain approximate. Unknown markings are omitted, not invented or implied to be physically blank. No light leaks are added. No third-party photograph pixels are shipped.

- Portra 160: a photographed 120 roll shows 41–55 and the other side 1–12; 56 is not confirmed in that photograph. [Reference](https://www.photrio.com/forum/attachments/img_e4614-jpg.283384/).
- Portra 400: a real strip supports 49–51, the opposite 8/9 and intervening arrows. The complete modern two-sided sequence has not been established. [Reference](https://www.coastalfilmlab.com/cdn/shop/files/Cut120FilmArchivalAsset.jpg?v=1762191231).
- Portra 800: a State Film Lab contact sheet shows the 800-2 batch, 41–56 and opposite 1–12 with intervening arrows. The suffix is not assumed for every manufacturing batch. [Reference](https://statefilm.com/digital-contact-sheets/).
- Gold 200: the supplied reference confirms KODAK 200, 52, opposite 10 and an unnumbered arrow. No complete roll was verified.
- Ektar 100: the supplied images show 49, 50, 51 and opposite 7/8. Whether both photos are from the same roll is unknown; combined phase is illustrative, not evidence of one continuous original.
- E100: the supplied reference confirms KODAK E100 and opposite 8/arrows. The full sequence and spacing are unverified. E100 is E-6 reversal film, not a C-41 negative.
- Provia 100F: official 120 diagrams show both edges, FUJI RDPIII and production codes on one, RDPIII/arrows and 1–19 on the other. Production codes are omitted rather than invented. [Data sheet](https://asset.fujifilm.com/www/us/files/2025-06/76ccbf624f1b5db023c093c94917708f/films_provia-100f_datasheet_01.pdf).
- Velvia 100: official 120 diagram shows 1–19, RVP100, FUJI and production codes on one edge; the other is blank in that diagram. This is not a guarantee for every batch. [Data sheet](https://asset.fujifilm.com/master/emea/files/2020-10/2f3c7f90a0b0c6e605e84f98b7d489c2/films_velvia-100_datasheet_01.pdf).
- Pro 400H: official 120 diagram similarly shows one printed edge with 1–19, PRO400H, FUJI and production codes. The leaky reference image is not a color or texture model. [Data sheet](https://www.fujifilm.com.hk/products/professional_films/pdf/pro_400h_datasheet.pdf).

The included in-app reference notice discloses these limits. Positive/reversal edge colors are authored display colors. Any route-specific negative rendering is a synthetic presentation conversion, not a measured stock transmission model. Source URLs document the evidence; they do not grant permission to redistribute reference photos.

## Persistence and compatibility

The editor persists format and explicit orientation independently of science. Frame snapshots use geometry version 5, pin the physical asset version and strip center, and freeze their source/canonical sizes, crop and appearance. Queue container version 6 prevents older apps from interpreting a medium-format job as 135. Preview, final exports, interactive overlays and queue scan thumbnails must all use the same versioned geometry and raster. Changing format, stock, orientation or undo history must not alter an already-enqueued job.

## Artwork

The approved 135 + bare 120 model set replaces 13 physical cartridge thumbnails / 15 profiles. Four 135-only cartridges have the same common-scale reduction as the nine pairs. Push variants share the identical Portra 800 render, with processing badges only in UI text. Film cans and monochrome motion-picture products are unchanged. No carton is shown. See `app/src/main/assets/film-rolls/ASSET_PROVENANCE.md` and the pinned thumbnail manifest.

## Validation boundary

Host geometry, persistence, rendering and Android View tests are not physical-device validation. Release notes record the checks actually run and any blockers; this document alone does not assert a build or test pass.
