# Preview brightness histogram

The editor has a 100 × 44 dp, non-interactive histogram in the upper-left preview corner. Its dark panel is 52% opaque; the pale graph also uses alpha, so light and dark photos remain visible underneath. The original-photo label sits below it and the detail spinner stays at the opposite corner. It never takes photo gestures or changes the photo viewport.

## What the graph means

- 128 brightness bins, black on the left and white on the right, scaled to the largest bin.
- Encoded sRGB preview values use exact decimal Rec.709 weights: Y′ = 0.2126R′ + 0.7152G′ + 0.0722B′, rounded half up to 8 bits and grouped into 128 two-level bins. Every pixel in the selected source rectangle is counted, eliminating sparse-grid sampling error. This is a photographic luma histogram, **not** sensor/RAW clipping, scene-linear exposure metering, RGB channel clipping, or final export statistics.
- Uses the adopted processed photo and its adopted crop rectangle, excluding film-frame artwork, perforations and date stamps. The underlying published photo pixels are never modified or recycled.
- The crop is the outward-rounded rectangle in the pre-composition source. It does not count the later linear-light crop resampling, frame feathering, date glyphs, screen scaling, or local-detail tiles. Fractional crops and fine textures can therefore differ from a histogram of the final displayed bitmap, even though the chosen source rectangle is counted exactly.
- Original comparison switches to the original preview. Viewer zoom/pan retains whole-photo statistics rather than changing to the visible viewport. No image, import/loading, and crop editing hide the graph; hiding during crop editing leaves all crop handles visible. After a crop commit/cancel/undo, it stays hidden until the adopted presentation catches up, rather than showing the previous crop’s statistics over a transient live crop.
- A new rendered preview updates the graph; pending slider values do not change it before their actual preview arrives. No extra film-engine renders, RAW reads, export reads, film-engine JNI calls or persistent settings are introduced.

## Performance and ownership

Counting runs on `Dispatchers.Default`, not the UI thread, over every pixel of the already bounded preview/crop, with one preview-width row buffer. A 1536-pixel-wide source needs a 6 KiB row buffer. No full-image copy is allocated. It checks cancellation per row. An 80 ms throttle limits work without indefinitely postponing updates during continuous changes. New input cancels the prior request, and only a still-current request can publish on the main dispatcher. Bitmap identity, generation and crop bounds form the request key.

Stop/detach cancels jobs and releases the histogram's bitmap references. Foreground entry binds the current state again. The drawing path reuses paints and paths and rebuilds its geometry only when data or size changes.

## Verification scope

The source includes native-graphics Robolectric tests for brightness endpoints/channel weights, full-pixel counts and aliasing regressions, thin images, source/compare/crop selection, frame exclusion, no source mutation, cancellation/recycled input, rapid updates, crop-edit hiding, stop/start/destroy, opacity, touch-through, both orientations, large fonts, original-label separation and existing control clearance.

Host tests and synthetic renders are not physical Android-device performance, battery, GPU or accessibility-service testing. The histogram's Kotlin/UI-only patch does not change native engine or export source. It requires the ordinary full Android build/lint/release gates when integrated into a distributable APK.
