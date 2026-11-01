# Owned detail Android adapter (0.1.9)

**Integrated in the 0.1.9 production source.** This module binds the owned JNI API, not export checkpoints and not an enlarged quick-preview bitmap.

## UI contract

`OwnedDetailProvider(context).open(photo, parameters, maxEdge = 1536)` returns an `OwnedDetailSession`. Await off Main through the suspend API. The ordinary engine must have installed `files/engine-data` first. Source resampling and hashing, native planning/build, transfer and cleanup run on worker dispatchers. The actual `session.key.width/height` is the authoritative working grid: never upscaled, maximum edge 1536. Equality with original-size export is not claimed.

`session.tile(cellX, cellY)` accepts origin-anchored 256-core cell indices. A `DetailTile` owns its Bitmap, immutable key, core and sample rectangles. Sample = core + one clipped gutter. UI draws sample in global grid coordinates and clips to core; caller owns returned bitmaps. Never manually recycle a published/display-list-owned bitmap; only rejected, never-published exclusive results can be recycled immediately. UI must bound retained tile bytes separately and validate current generation/key before publication. Missing cells keep the ordinary preview underlay, including newly panned regions.

Call `session.close()` on invalidation/import/parameter changes/background. It retires immediately and queues destroy/cleanup off Main; it does **not** report worker completion. Returned bitmaps remain independent. It is safe to call repeatedly. A cancelled/failed `open` retires its context automatically. Every native operation allocates its token before blocking dispatch; never-invoked tokens are explicitly released. A watcher cancels existing tokens; blocking worker references/reservations remain until JNI actually returns.

Failures (`DetailUnavailable`, memory admission, IO, cancellation or absent ABI) must keep ordinary preview; never pretend unsupported effects ran. Automatic GPU preference is retained in parameters but native backend is explicitly CPU fallback. No GPU execution is claimed. No smaller grid retry is implicit.

## Sources and privacy

RAW and normalized HEIC borrow their original-oriented linear sRGB spool with a source-reader lease. JPEG/PNG use `OriginalSpool`'s existing full-original region/EXIF mapping, then `LinearSpool.resize`. All formats retain HDR/negative linear values where present. HEIC stays `image/heic`, not RAW, and receives no second orientation. Exact source bytes, normalized mapping/resampling algorithm/grid bytes, dataset manifest and complete serialized parameters participate in identity. Dataset must remain immutable during native build.

Only `cache/detail-preview/sources/grid-UUID` and native-owned `sessions` are used. Temporary original RGB spools never enter export paths. Source cleanup requires exact marker and exclusive OS lease, skips live/unknown entries, and deletes without following symlinks. Native create performs its independently leased session orphan cleanup. Opening a provider cleans stale owned sources; the lifecycle owner can call `cleanupOwnedOrphans` at startup (off Main). Original source/export files are never deleted by this adapter; repository discard is deferred until source-reader leases end.

## Shared admission

Resampling releases its 128 MiB active reservation before context admission. Native plan's full conservative peak plus 8 MiB output allowance is retained as passive bytes, with **zero worker slots**. Per-operation admission takes one worker slot and only the existing 16 MiB margin, not a duplicate native peak. The native stage/spectrum caches are fixed at 16/32 MiB. Fresh OS memory is rechecked each admission retry; this is conservative accounting, not an allocator cap.

On pressure, active import/preview/export admission requests eviction of passive detail owners outside the ledger lock. Session closure aborts a pending detail admission to avoid waiting on its own passive bytes. A running native worker retains those bytes until exit. Idle caches therefore do not occupy the two-worker limit or indefinitely prevent another operation. Failure to admit a new passive context is immediate, not a wait while holding a preparation lease.

## Verification

`OwnedDetailProviderTest` uses injected native API and real source spools to test ownership, cancellation-before-dispatch, cancellation/destroy while blocked, passive-pressure eviction, exact clipped gutters, byte-count rejection, sRGB transfer, HDR/negative normalized HEIC provenance, no upscale and scoped orphan cleanup. Existing EXIF mapping/IO/admission suites remain applicable.

`RealOwnedDetailHostTest` is opt-in, runs alone in a fresh Robolectric JVM, and uses the actual Kotlin adapter and real owned native `.so`. Set `FILMROOM_HOST_JNI_DIR` and `FILMROOM_DETAIL_DATA`, then use `tools/host-jni/real-queue.init.gradle`. It is not device HEVC/region-decoder/display/RSS validation. Enabled-candidate UI/refinement/compare gates and fresh-JVM JNI checks passed; source merge/package publication remain separately gated by parent approval. See `LOCAL_DETAIL_UI.md`.
