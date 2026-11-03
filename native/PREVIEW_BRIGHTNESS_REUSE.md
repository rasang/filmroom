# Private brightness-only preview experiment

`previewBrightnessReuse` defaults off and only activates when the request's effective
`useGpu` is false. This is not a general film-density or arbitrary-slider cache.
The candidate reuses the unchanged `Pipeline.process` result before the existing
brightness multiply / output-color conversion. No arithmetic order, seed, size,
precision or scientific processing changes. Baseline runs the original process.

Identity includes every serialized native parameter except `brightnessEv` (which
is applied only after this result), pinned engine references, and SHA-256 over
sorted full relative paths and full bytes of the bundled dependency tree. Input
samples are also compared by exact f32 bits, not just a hash. Dimensions are exact.
Different science/dependencies clear both slots; different input at an existing
size clears both. Android additionally scopes native handle ownership to the same
PhotoSource/Bitmap object, so a source replacement cannot keep other-size entries.
Files are app-owned bundled dependencies, validated by the existing asset installer;
a cold compute fingerprints again before publication. Symlink dependencies reject.

Two entries allow the existing responsive and settled sizes to coexist. Payload
capacity is 31 MiB, with 1 MiB of the admitted 32 MiB reserved for bounded keys,
container metadata and allocation overhead. Scientific identity strings over64KiB
are not retained. Kotlin obtains the passive lease BEFORE creating/using a cache
owner; normal per-operation admission remains in place for transient buffers.
Admission refusal falls back to uncached processing. If later active admission
fails with a cache held, it destroys the owner/releases its lease and retries
uncached once. These are estimates and accounting limits, not a process RSS cap.

Only one render runs per FilmEngine and native handle. JNI cancellation/error
retires the native owner once the operation returns; no stale cache is published.
A no-op cancellation between completed frames preserves valid entries. Settings
changes, source changes, pressure and ViewModel close retire cache ownership;
in-flight JNI retains its lease until it returns. Settings are snapshotted once
per operation, and export's authoritative plan and execution use the same snapshot.

The private Settings panel exposes all-off baseline, the independent export mask
bits, CPU comparison, brightness-only reuse, and GPU resource reuse. Preview
`previewReuseHit` and retained payload bytes distinguish cold and warm runs.
Latest preview/export native and wall timings contain no source paths or samples.
Existing PhotoSource linear buffers and detail stage/spectrum caches are unchanged
and are not claimed as new optimizations. General film-density stage reuse remains
unimplemented. Native cache tests and a small-image fresh-vs-warm bitwise test are
included; run and real-device validation status must be reported separately.
