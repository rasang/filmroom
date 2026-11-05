# Capture imprints: reviewed lettering and edge layout

Presentation-only feature. Imprint preferences are excluded from the scientific/native film parameters. Existing queues without imprints retain their original rendering. Enabling an imprint records settings schema 3 and layout version 1; queued jobs use container version 7 and freeze the original metadata snapshot.

## Reviewed material contract

The user selected the unified existing-marker candidate in `Filmroom_06_standardized_markers.pdf`, SHA-256 `f53b3b8957515bcdf62cad30899ecde1a385481dc53a5bc92da207aca55b9e00`, following five full stock/date-layout review PDFs. The material renderer used original procedural glyphs guided by CONTAX T2, Yashica T4 Zoom, Canon Data Back A/LUNA XL and Pentax MZ-S references. These are reference-informed original drawings, not licensed manufacturer font files or an optical calibration.

The later user change replaces AEB with actual focal length and explicitly adds source ISO. No AF.S or AEB field is emitted. ISO and focal length on each frame are **design extensions**, not a claim about original MZ-S film recording (whose ISO belonged to the film leader). Current selected fields are ISO, shutter, aperture, exposure compensation and actual focal length. Metering symbols were removed at the user’s request because they were unclear. Valid fields form a centered group.

`ImprintGlyphs` mirrors the reviewed advances, matrix radius and continuous/segment shapes. The approved stronger glow uses relative stroke additions .19/.14/.10/.06/.025, opacities .045/.08/.15/.22/.45, and an opaque core. Equal-color layers are accumulated into three bounded masks. Rendering is deterministic, with no random flicker. The neutral/dark appearance follows black-and-white or negative-transmission frames. The original image and source frame art are not overwritten.

## Capture metadata and explicit date override

`PhotoRepository.loadPrivate` reads `PhotoMetadata` from the byte-for-byte imported file before its RAW/HEIC/raster adapter decodes a working image. Imprint content uses DateTimeOriginal first. If it is missing or invalid, the presentation-only fallback uses the original EXIF modification date, original provider modification time, then digitization/creation date. Original content-URI dates are frozen before decoding and persist with the private source; the new cache file clock, gallery DATE_ADDED, export clock and current clock are never substitutes. EXIF wall dates remain unshifted; provider epoch timestamps use the import device zone once. The selected fallback source is labeled. An optional, explicitly edited date is a presentation override, visibly marked as manual. It never changes original EXIF or exposure data, is frozen for a queued job and same-photo session, and clears on the next import. Pre-release v1 date-only overrides are not migrated because their time was not recorded. The four date styles print only year/month/day. The editor offers only a date picker; legacy internal date-time overrides remain compatible without exposing time controls.

ExposureTime/FNumber and independently recorded valid APEX values are supported. ISO uses PhotographicSensitivity when usable; SHORT 65535 is an overflow sentinel, and extended ISOSpeed/REI/SOS values are used only when SensitivityType declares them. FocalLengthIn35mmFilm never substitutes for actual FocalLength. Integral values through UInt32 maximum stay exact. Fractional values have bounded display rounding; the original metadata snapshot is unchanged. Reciprocal shutter values are only displayed when the recorded exposure supports that reciprocal, not rounded to a preferred camera series.

## Layout and memory

135 data uses the central five inter-perforation positions. All fields share one fitted glyph height instead of shrinking each value independently. Missing fields remain symmetric around the frame center; even counts leave the central gap empty to keep text off perforations. Double-X's authored atlas uses the equivalent coordinate scale. Tri-X 7266/Super8 and 2302 are not misrepresented as 135 exposure-data layouts. Date glyphs remain inside the native aperture and rotate with the frame; unframed dates are placed inside the final crop. 120 optionally places dates on the opposite edge alongside parameters.

For 120, `MediumImprintMarks` freezes 45 stock/format layouts from the reviewed candidate. Its sampling wrapper moves 98 existing groups and preserves 13 fixed groups. It preserves brand text, original colors/shapes/vertical placement and clipped fragments, leaves Velvia/Pro400H's current empty opposite rail empty, and retains Provia's RDPIII exceptions. Exact version and default strip-center gating prevents reuse against another asset; unknown assets are preserved rather than erased. Portra 800 push profiles share their physical stock. Original PNGs are unchanged. Source hashes are in `imprint-review/standardized-120-provenance.json`; frozen crop geometry and pixel hashes are in `reviewed-atoms.json` and `reviewed-positions.json`. The generator takes an explicit output directory and revalidates repository assets.

120 fields use 1.8 mm gaps and a 2 mm date-to-parameter gap, within a measured clear central interval. Large ISO, shutter or focal values scale uniformly to fit; digits are never truncated. If even the minimum raster cannot fit at a very small output resolution, that imprint is omitted rather than overlapping marks. Parameters are not moved into the brand rail. The complete halo bounds participate in fitting and validation. The preview UI shows source content even where a very small thumbnail cannot resolve every glyph.

Masks are bounded to 16 MiB per raster and 32 MiB per plan; rendering uses existing row buffers, not a photograph-sized overlay. Preview, queued thumbnails and export use the same plan and linear-light row compositor. sRGB, Display P3 and Adobe RGB palettes are converted into output primaries. Native film checkpoints remain unimprinted.

The existing drag/zoom interaction uses transient Canvas display, including straight-alpha imprint previews; it is not a pixel-equality reference. After a gesture settles, the authoritative preview is regenerated through the same linear-row path as export. Local-detail cells are clipped away from imprint rectangles so they do not erase glyphs. Export/settled-preview equality is tested separately from transient UI behavior.

## Interaction

The film tool selector adds Date & data immediately after Composition. The existing wide-screen layout presents the same controls as the next independent section after Composition. Two independent switches control date and exposure printing. A date preview opens a draft-only dialog with four actual rendered style samples, then editable strict local date/time, source restore, placement and appearance options. Missing source dates stay empty. Cancel/back cannot commit edits; draft restoration is scoped to the same photo and a new import dismisses stale drafts.

## Verification status

Validated on 2026-11-05 on the same branch as the linearized-DNG/large-strip RAW fix:

- Android aggregate host suite: 220 classes, 1,165 tests, zero failures/errors and 59 conditional tests skipped. The 46 new imprint tests all ran and passed. The earlier focused run included those tests plus existing 7266 and Canon CR3 metadata regressions: 56 tests, zero failures/skips.
- Geometry coverage includes 1,088 stock/format/orientation/style/placement combinations, plus large ISO/shutter/400–2,000 mm and UInt32 focal-length cases, missing fields, tiny outputs and full-halo collision checks. The 45 standardized 120 layouts retain existing-only markers; their generator and source/pixel hashes were checked independently.
- A separate opt-in run enabled the actual Linux RAW JNI and two original DNG samples: one test ran with zero skips and verified import, JPEG export, capture metadata, orientation and unchanged source hashes. This covers one of the aggregate suite's conditional skips, not all skipped device, large-image or external-corpus tests.
- Main and test compilation, Android lint and debug assembly passed. Independent code review found no blocking issue. The compiled debug package is a validation artifact, not a release package or new version assignment.
- Actual Android View/Canvas host renders were inspected in portrait, landscape and narrow large-font layouts. The four date styles use the same glyph rasterizer as output. Preview/export equality is checked on the settled linear-row rendering path, separately from transient gestures.

No Android hardware, device-performance, peak-memory stress or optical reconstruction acceptance is claimed. One real PNG-import Activity test runs on host SDK 29 because the SDK 35 Robolectric native ImageDecoder rejects that fixture; the other imprint Activity tests use SDK 35. Production decoding was not changed to work around the host limitation. The existing versioned 7266 fringe correction and Canon CR3 metadata paths remain intact and their regressions passed. A previous aggregate attempt was interrupted before completion; only the final successful run is counted here.

## 2026-11-05 date and parameter UI revision

Removed metering from edge data, fitted all 135 parameters at one common height, and centered the remaining group on real perforation gaps in both orientations. Edge placement remains a saved preference but falls back inside the photo when a supported 120 frame is not active.

The date control now displays the shared raster lettering instead of ordinary text. The editor is date-only, uses an adjacent restore icon, and shows a 120 edge switch only with a 120 frame; enabling it disables the in-photo corner control without losing its saved value. During size/glow dragging the other dialog content fades out and a presentation-only photo preview updates; cancel and dismissal restore the settled result. A generation guard ignores callbacks from an obsolete editor. Dialogs share rounded backgrounds.

Final focused host verification: 15 classes, 105 tests, zero failures, errors, or skipped tests. Includes imprint geometry/raster/content/snapshot/UI, metadata fallback and privacy, Canon CR3 metadata, preview pixels, gesture restoration, and shared dialog corners. Production and test Kotlin compilation completed successfully before running the frozen compiled test set. This is host evidence, not Android hardware/performance acceptance.
