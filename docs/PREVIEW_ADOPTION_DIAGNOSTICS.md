# Preview adoption diagnostics

This is diagnostic instrumentation, not a colour-algorithm fix. It does not reset settings, disable GPU, alter profile data, invalidate caches or change RAW/native libraries.

`PREVIEW_RENDER/START` now carries a process-local source counter, requested generation, frame kind and a fingerprint of **allowlisted processing settings only**. Existing backend/GPU details remain attached to that render operation ID. No filename, URI, EXIF, pixel value, image digest or persistent image identity is included in the new fields.

A separate `PREVIEW_PRESENTATION` record distinguishes:
- `ADOPTED`: completed science/presentation accepted into the ViewModel state.
- `BOUND`: MainActivity assigned the corresponding Bitmap reference to PhotoView. This does not prove GPU/display scanout. The callback captures the exact state context that was bound, not a later current generation.
- `CACHE_ONLY`: scientific result retained, but the current gesture/changed presentation did not accept that composed frame.
- `CANCELLED`, `FAILED`, `STALE_SOURCE`, `STALE_GENERATION`: attempt did not adopt a new frame.

`QUICK`, `REFINED` and `CACHED` distinguish source paths. `previewNativeCompleted` means the ViewModel's renderPreview call returned; it is false for a cache-only compositor and omitted on a BOUND marker. Native operation completion remains separate and can occur before a cancelled UI attempt. The complete correlation tuple is `(run, sourceToken, generation, kind, scienceFingerprint)`; cached frames additionally retain `originGeneration` and `originKind` to identify the native quick/refined render that produced their pixels. This is not a globally stable photograph identifier.

The source reference used for token assignment is weak. Fingerprinting is over small typed settings, never a full-image pass. Diagnostic writer failures are ignored after ownership cleanup and do not revoke an adopted frame. The UI/render path only performs a nonblocking offer to a bounded 16-item queue; a single background writer does disk IO. Overflow is counted rather than blocking. Terminal presentation diagnostics are limited to eight per second; suppressed count is included on the next retained record, with the existing diagnostic store size/retention cap unchanged. Consequently, missing events do not prove an action never happened, and asynchronous persistence order must not be used as frame-order evidence.

Scope: ordinary responsive/refined/cached preview and its PhotoView reference binding. The independent viewport ROI renderer is not newly instrumented by this patch. No new build is claimed phone-tested; an Android host fixture can test adoption/cleanup but cannot reproduce an Adreno driver or physical display issue.

Validation results and exact patch/source identity are maintained in the separate investigation handoff. The isolated tests use controlled PNG imports and a fake renderer for deterministic cancellation/ownership; the native prefix replay uses a synthetic prior picture and the public D800750 source, not an assumed copy of the user's earlier photograph.

`previewObservedElapsedMs` is captured at the terminal call site before queuing, not at disk write time. Millisecond ties have unknown order. The settings fingerprint is a 53-bit digest for JSON-exact correlation, not a cryptographic claim about photos.
