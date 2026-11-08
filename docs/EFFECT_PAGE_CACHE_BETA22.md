# Effect paging and readout follow-up (0.1.22-beta22)

Base: dev `483e7839bebf37f2307248a666ffc04dd5a2152a`.

## Ownership and behaviour

- On a successful page change, the outgoing decoded source/result pair becomes an idle adjacent-cache entry rather than being recycled and decoded again. The saved composition and camera readout travel with the same record.
- On a cancelled swipe, the prepared candidate returns to the same cache.
- The cache owns at most two idle neighbours; the current page and an in-flight candidate own their resources separately. Moving beyond the adjacent window releases old entries. Record changes invalidate a cache hit, so changed photo links or composition cannot reuse stale pixels.
- A displayed GL view relinquishes its inputs before cache transfer: viewfinder views stop/detach first; the shared light-table renderer acknowledges replacement on its GL queue, then transfers ownership on the main thread. Pending callbacks have a final release path after view detachment.
- Backgrounding and low-memory callbacks clear the idle cache and invalidate delayed cache-return callbacks. Below 24 MiB available Java heap, outgoing resources are released rather than retained. Returning after those events may therefore legitimately reload.
- Speculative prefetch cannot overwrite an outgoing page that was already returned to the cache. All prefetch decode/cancellation limits remain in place.
- First entry still auto-plays once. Swiped and returned pages remain at the final frame until manual Replay.

## Gesture

Slow dragging retains the existing threshold `max(64 dp, 22% of page width)`. A short flick can also commit when displacement is at least 16 dp and release velocity is at least 600 dp/s. Velocity must point in the displacement direction and horizontal speed must exceed vertical speed by 1.5x. Multitouch/cancel and vertical rejection do not fling. The threshold is distance **or** qualifying velocity, not both long distance and velocity.

## Viewfinder readout

The shutter LCD layout now allocates explicit space between glyphs rather than letting seven-segment glyph width overrun their advance. Normal three-digit values have clearer gaps; long values fit the shutter window without colliding with the aperture readout. Portrait rendering uses the same corrected layout.

## Boundaries

This is a decoded-image cache fix, not a GL renderer architecture rewrite. Viewfinder page surfaces and their textures can still be initialized on return; the light-table renderer also still replaces its GL resources. Cache hits avoid decoding but do not promise zero upload/render latency. Real-device gesture feel, GPU surface-loss/recreation and video/Live playback require device validation; host ownership tests are not a substitute for that validation.

JNI libraries and effect audio are reused byte-for-byte from beta21; no native engine or audio-content changes are intended. Signing material stays outside the source tree.
