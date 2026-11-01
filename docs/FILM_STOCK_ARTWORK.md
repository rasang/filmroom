# Original film-stock selector artwork

The native Android selector maps immutable engine profile IDs to 18 original physical-stock renders for all 20 shooting-film profiles. Portra 800, +1 PUSH and +2 PUSH deliberately share the same physical canister image; their selectable labels and accessible names retain distinct processing variants. No mapping uses list position or display-name matching. Unknown/missing/undecodable art uses the neutral film-outline fallback, never another stock's image.

## UI
- Original RGBA artwork replaces the repeated outline on stock cards. Portrait image bounds are 72 dp; compact landscape uses 56 dp alongside the label so the existing controls fit without clipping.
- Full brand/model labels remain text, not baked into thumbnails. No ellipsis or fixed-height text cards. Minimum touch size remains 48 dp.
- Existing strip anchor/selection/state retention is unchanged; cards remain native Buttons tagged with profile IDs.
- Larger system fonts keep the existing expanded-controls dialog, rather than squeeze the photo or truncate names.

## Packaging and reproducibility
`app/src/main/assets/film-rolls/` contains 18 lossless WebP RGBA images at 240×240, plus checksummed `manifest.json` and provenance. The images total 511,552 bytes. No downloaded reference photos, font binaries, Blender meshes or scripts are included in the APK. Original editable models and label scripts are a separate source deliverable. The source-only `tools/prepare_film_artwork.py` rebuilds thumbnails from that pack with Pillow. Existing simulation profile licensing is unchanged.

## Host validation
`FilmStockArtworkHostTest` verifies exact coverage against the packaged 20-profile catalog, 18 physical images, decoded dimensions/alpha, push variant mapping, and neutral fallback for unknown IDs. Native Skia host rendering exercises 440 dpi portrait and landscape at normal and 2× font scale, selecting the final stock and checking visible selection/touch targets/untruncated labels. Normal editor pan offset is checked exactly; an expanded dialog may reflow its text/scroll gutters, but must retain the final selected stock visibly and never reset to the first.

`FilmCarouselPositionHostTest` additionally exercises async update, category round trip, saved-state recreation and width changes. `ResponsiveLayoutHostTest` and `CameraLayoutRenderHostTest` cover existing editor layout behavior. These are cloud-host Android View/Skia tests, not emulator/device screenshots or physical-phone validation.

The 135 leader geometry uses the approved corrected asymmetric, curved narrow tip (model-source revision 4). Its perforations are actual transparent mesh openings; the five cinema-can images are unchanged.
