# Owned detail JNI v1 — 0.1.9 integrated ABI

Class: `dev.filmroom.engine.NativeDetailBridge`. Separate from the unchanged `NativeFilmBridge`. Built only with `precision-f64`. All methods return JSON strings; no raw pointers cross JNI. JNI status never contains native paths or raw exceptions. The corresponding ARM64 and x86_64 Android libraries are included in 0.1.9 source; the delivered APK is ARM64 only. No physical-device runtime success is implied.

## Java declarations

```java
String nativeCapabilities();
String nativePlan(String requestJson);
String nativeCreate(String canonicalPrivateAppRoot);
String nativeBeginOperation(long sessionId);
String nativeBuild(long sessionId, long operationId, String sourceRelative, String requestJson);
String nativeTile(long sessionId, long operationId, int cellX, int cellY, ByteBuffer output);
String nativeCancel(long sessionId, long operationId);
String nativeReleaseOperation(long sessionId, long operationId);
String nativeDestroy(long sessionId);
```

All statuses include `schemaVersion:1`, `operation`, `ok`, `code`, `outputValid`. Opaque positive session/operation IDs use a separate tagged namespace from legacy engine handles and are **decimal strings in JSON**, passed back as Java long. IDs never encode native addresses. `nativeCreate` returns `sessionId`; `nativeBeginOperation` returns `operationId`.

A session holds one immutable built context. After any failed/cancelled build, the adapter should retire that session and create a fresh one: cancellation can race completion/installation, so an unsuccessful publish is not permission to reuse the session as unbuilt. A changed source/geometry/parameters identity requires a new session. All scientific operations are synchronous; the adapter must use its worker thread. One active build/tile per session. At most64 pending operation tokens.

## Request JSON

```json
{
  "schemaVersion":1,
  "width":1152,"height":1536,
  "parameters":{"filmStock":"kodak_portra_400","paperStock":"kodak_2383","useGpu":true},
  "sourceRevision":"source-and-decode-revision",
  "mappingRevision":"orientation-crop-working-grid-revision",
  "dataRevision":"bundled-data-manifest-revision",
  "memoryBudgetBytes":536870912,
  "diskBudgetBytes":1073741824,
  "stageCacheBytes":16777216,
  "spectrumCacheBytes":67108864
}
```

Nested parameters follow the existing full MobileParams schema/defaults; unknown fields fail. Request strings are bounded before conversion; JSON is limited32KiB, revision strings1024bytes. `nativePlan` validates metadata/geometry/config and returns `plan`; it does not load profiles, create context files or render. `nativeCapabilities` is similarly lightweight. Plans are conservative claims to integrate into the **future shared reservation**, not allocator enforcement.

`useGpu=true` explicitly selects **CPU fallback** for this API; requested identity is unchanged. Build and tile metadata include `backend: {actualBackend:"cpu",requestedGpu:true,cpuFallback:true}`. False requests report CPU with false fallback. This API never claims GPU execution.

## Private file scope

The root must come from the trusted app Context/private-directory bootstrap, not arbitrary user file input. It must be absolute, canonical, real and non-symlink. Existing directories:

```
root/files/engine-data/                  trusted bundled dataset (no symlink subtree)
root/cache/detail-preview/sources/      caller-owned working-grid f32le spools
root/cache/detail-preview/sessions/     bridge-created private owned contexts
```

`sourceRelative` must be a normalized path beneath `sources/` (for example `sources/<safe-file-name>`) (not an absolute path, traversal, alternate separator form, symlink or hardlink). Input is RGB linear sRGB float32 little-endian at the requested working geometry. Caller must not modify input while copying, bundled data, or scope directories concurrently. Root/session owner may not race filesystem renames with native work. Source data is copied into preview-private ownership; original source is never written. No export job/checkpoint paths are accepted or reused.

Creation cleans only exact ownership-marker directories whose exclusive OS lease can be acquired. Live sessions/workers in this or another process keep their lease. Unmarked directories and unrelated files are preserved. Linux/Android use `flock`; other platforms are explicitly unsupported for this private storage layer.

## Cancellation / lifetime

1. Call `nativeBeginOperation` **before** dispatching blocking work. Record its returned ID with the UI generation.
2. `nativeCancel` marks that existing token. A later build/tile using it returns CANCELLED; cancel is not reset when work begins.
3. Each build/tile attempt consumes its operation once, including failures/BUSY. Retrying requires a new token. A duplicate invocation cannot start another worker.
4. If the dispatcher decides an allocated operation will **never be invoked**, call `nativeReleaseOperation` to abandon it. Do not merely accumulate cancelled pending tokens. Release also cancels an already-running operation, but its worker lease remains until exit.
5. `nativeDestroy` removes the registry ID and cancels outstanding operations. Existing workers/results retain Arc and directory ownership through native return. This is not a promise that CPU work is already stopped when destroy returns. Shared memory reservation must remain held until the worker actually exits.

No synchronous waiting is performed in destroy. Generation/publish checks remain required in the adapter. Existing calibration/LUT calls are checked before/after; cancellation is cooperative inside long numerical work, not a hard latency SLA.

## Tile output

`cellX/cellY` are canonical origin-anchored256-cell indices, not arbitrary viewport rectangles. Successful response includes:

- `coreRect: {x,y,w,h}` clipped at true bounds.
- `sampleRect: {x,y,w,h}` = core expanded1px, clipped to full bounds.
- `bytesWritten = sampleRect.w * sampleRect.h * 3 * 4`.
- `identity` (full revisions, requested parameters, virtual width/height, algorithm).
- `backend` and `quality:"ExactSameWorkingGrid"`.
- `outputValid:true`.

Caller provides a **writable, aligned, direct, native-byte-order** ByteBuffer with position0 and limit/capacity sufficient for the sample rectangle. A simple max-size buffer is258×258×12bytes; edge tiles use the returned byte count. Native writes only after successful complete rendering and cancellation check. Caller keeps the buffer and all aliases exclusively inaccessible to other threads until native return. No JNI global buffer references are retained.

A cancellation racing the final bounded copy can make bytes changed while `outputValid:false`; discard them. An output buffer is not a display bitmap or a shared cache entry until the adapter validates status/identity/generation. Draw sample through the shared image transform and clip to core.

## Sanitized codes

`OK`, `INVALID_REQUEST`, `INVALID_BUFFER`, `INVALID_HANDLE`, `INVALID_OPERATION`, `INVALID_SCOPE`, `BUSY`, `CANCELLED`, `UNSUPPORTED`, `BUDGET_EXCEEDED`, `PROCESSING_FAILED`, `INTERNAL_ERROR`.

Capabilities enumerate actual supported routes/profile combinations. Scanner blur/sharpen/glare, nonlocal IIR/sequential grain are explicit unsupported modes; retain ordinary preview rather than substituting a wrong route or approximation. The16 negative stocks support print; Provia100F, Velvia100, Ektachrome100 and Kodachrome64 support their original direct route. Negative/direct and reversal/print are unsupported. Four-stock direct parity (corrections off/on, HDR/grain/gutters/rebuild) is separately verified.

Host verification entry point: `run-detail-host-jni.sh`. It builds a separate host library into the independent target directory and executes real JVM→JNI tests. Existing shipping `.so` files and existing JNI implementation are not modified.
