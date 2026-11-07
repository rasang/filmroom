# Composition straightening

The composition panel has a horizontal relative wheel with a signed angle, 0.1° precision, a ±15° limit, reset, keyboard/accessibility adjustment, and alignment guides. Angles rotate the photograph clockwise for positive values. Exact output keeps the frame and date layout upright. The pre-imaging gesture approximation below has a documented temporary stamp limitation.

## Coordinates and compatibility

`CompositionTransform.straightenDegrees` defaults to zero. `sourcePoint(u,v,canonicalWidth,canonicalHeight)` maps an aperture coordinate to the canonical source using the inverse rotation. `rect` remains the local, unrotated crop window; it must not be treated as the rotated source polygon. `sourceBounds` returns the conservative source bounding rectangle.

Automatic cover scaling is separate from user zoom, preventing accumulation when the angle changes or is reset. The rotated corner bounds constrain the crop center, so every point inside the aperture remains covered. Pan and pinch focal anchoring use the same inverse transform. Free-crop handles retain the angle, and unsupported handle extents are rejected without losing the last valid crop.

At zero degrees the old JSON version 1/2 output and axis-aligned sampling path are unchanged. Nonzero angles use composition JSON version 3, including `straightenDegrees`. Old versions with a nonzero angle are rejected rather than silently dropping it. Frozen crop/border queue snapshots retain the angle; older snapshots read as zero. QR import accepts this named finite numeric field and delegates range/version validation to the model. The companion compact-QR branch must retain this small whitelist change during integration.

## Rendering and memory

During a wheel gesture `PhotoView` draws the existing processed source with a Canvas transform. It publishes coordinates only, reuses the interactive frame, and does not rerun film science or allocate a new full image. The release/confirm path uses the canonical shared float renderer.

The zero-angle renderer continues to stream two source rows. Nonzero rotation uses a bounded 32×32 float32 source-tile LRU, with a dimension-dependent cache/admission allowance capped conservatively at 16 MiB (small previews use much less). The source is never quantized through an intermediate bitmap during export. Both bitmap-preview and float-file export consume the same inverse map and bilinear interpolation. Cancellation remains checked per output row and during tile loading. PNG/TIFF/JPEG encoders remain unchanged.

Local detail tiles rotate with the source and request the inverse-mapped visible bounds. The histogram counts every processed-source pixel whose center lies inside the rotated crop, using the exact luma weights from the histogram fix; it never uses a sparse sampling grid or includes frame/glyph pixels. At zero degrees the historical outward-rounded integer source window is preserved; a nonzero angle selects source pixel centers inside its polygon, so subpixel boundaries can differ discretely. This remains processed-source luma, not a histogram of the final resampled or encoded output. Film borders and imprints are composited after photo geometry.

## Integration with pre-imaging film borders

This branch retains the existing final-presentation path. A separate pre-imaging border branch can call `sourcePoint` to place the scene before the film engine. It must keep scene metering restricted to the scene and must not rotate the physical frame. Its no-overlay PhotoView dirty-state preservation changes are retained here. The explicit `preImagingCompositionPreview` flag lets PhotoView geometrically transform the already imaged frame inside the fixed aperture while dragging. Only pixels inside the previously imaged aperture are eligible; unavailable areas retain the adopted base. Film-frame lettering stays fixed. A date stamp already imaged into the photo can move transiently with that photo, and is never overlaid a second time. Gesture release requests exact full re-imaging, with generation gating and no export of the approximation. MainActivity/VM flag and matching source adoption are supplied by the separate pre-imaging branch.

## Validation

The targeted tests cover rotated corner bounds, landscape/portrait/free ratios, pan/pinch anchoring, non-accumulating reset, old/new serialization, preset/QR round trips, frozen export geometry, bitmap/float parity, retained HDR float math, wheel limits/cancellation/accessibility, and actual Activity cancel/confirm/undo behavior. Native Android Canvas host renders show the runtime panel and a synthetic landscape; they are not device screenshots or film-color acceptance.

No release, publication, signing, or version change is included.

### Candidate verification result

The standalone straighten candidate passed 80 focused host tests with no skips or assumptions: geometry/float/serialization tests, existing crop/135-frame/gesture regressions, histogram and saved-preset suites, direct wheel input, real Activity listeners, and the pre-imaged delta pixel-boundary test. The direct row adapter's omitted source height is checked against exact float file output.

The 20-file affected-source/ABI closure compiled against the verified code55 main baseline, and resource relinking preserved all 1,189 existing host resource IDs. A separate full 202-source compiler attempt exited with signal 9 without a diagnostic. Full Gradle aggregate gates, lint, native/app combined validation and APK packaging remain the integration task's responsibility; the focused result is not a full-build pass.
