# V5 native contract: staged local-disk export

V4 remains a separate frozen experimental artifact. V5 preserves `dev.filmroom.engine.NativeFilmBridge` and its five existing methods, adding `nativePlanExport` and `nativeGetProgress`. The direct-buffer preview retains V4's checks, pixel guard and conservative memory admission budget. Only the original-size file export routes through the new staged implementation.

## Planning before allocating photo spools

`nativePlanExport(handle, width, height, paramsJson)` does not touch disk or start a render. The response has a `plan` object:

- `inputBytes = outputBytes = 12*N` for packed linear RGB float32 little-endian files.
- `estimatedScratchBytes = 128*N + 64MiB` is **extra** scratch, not inclusive of input/output.
- `estimatedTotalLocalBytes = 152*N + 64MiB` includes those two spools and scratch, but **excludes** the app's encoded export image and private copy of the encoded source. The app must add those and a safety reserve when checking free storage.
- `pointwiseChunkPixels` is an upper bound `max(width,32768)`, with a16MiB explicit spatial working-buffer budget.
- `estimatedNativeWorkingBytes = 112MiB + 256*max(width,32768)` is a conservative admission estimate, not measured current RAM, allocator cap or OS guarantee. Java/decoder/encoder/UI memory needs separate headroom.

At12MP the conservative total is1,891,108,864bytes; at50MP it is7,667,108,864bytes before encoded source/output and app reserve. Neither a successful plan nor a small synthetic test establishes50MP processing performance or phone compatibility. That requires the separately reported large-image and Android validation.

## Progress ownership

`nativeGetProgress(handle)` may run concurrently with rendering. It takes only a short progress mutex, not the render lock. Creation begins at `jobId:0`, `passId:0`, `status:idle`, `active:false`. A render/export that actually acquires the handle's render lock increments `jobId` and resets its progress. Planning does not change it. The caller should snapshot a baseline job ID before dispatch and ignore older states, while still enforcing its own UI request generation.

The response uses fixed strings and numeric counters only: `operation`, `stage`, `status`, `jobId`, `passId`, `done`, `total`, `elapsedMs`, `active`, `counterScope`. **`counterScope` is `current-pass`**, not the whole stage/image/export. A spatial stage may run multiple channels, Gaussian mixture components or separable passes. `passId` increases when the stage changes or its counter decreases. Do not show `done/total` as global completion. The final status is `completed`, `cancelled` or `failed`; it remains until a new job begins. A late previous-job completion cannot overwrite a newer job's progress.

Cancellation remains cooperative. The stream checks it between chunks, row/column passes and stage transitions. It does not forcibly kill a thread. A destroyed handle yields a structured error to new progress calls; existing native work retains safe ownership until it returns.

## Scratch confinement and cleanup

The trusted engine data directory remains `<app-private-root>/files/engine-data`. Input/output spool rules remain private `files`/`cache`, excluding bundled data; output must be new. Scratch is a new child of that app's canonical private cache:

`^film-native-v5-[0-9]{1,20}-[0-9]{1,39}-[0-9]{1,20}$`

The components are cancellation generation, epoch nanoseconds and a process-local counter. The JNI adapter chooses the path but does not precreate it. The streamed exporter uses create-new semantics and owns RAII cleanup on success, failure, cancellation and panic unwinding. App startup cleanup must occur only before starting any native jobs in that process, match the full pattern, verify its parent is the private cache and **never follow symlinks**. Do not delete arbitrary `film-*` paths or active jobs.

## Physical stages and approximation limits

The original-size path uses planar local intermediates and full-row/full-column separable spatial operators, rather than repeatedly rendering a whole physical chain inside finite halo tiles. The print/scanner spectral integrations use prepared33³ PCHIP tables, built once per export. Paper sensitometry, matrix order, color-reference remap and output gamut handling remain pointwise exact relative to the same existing33³ Rust path. Density-domain grain, halation and DIR stay separate physical stages; this is not a single baked RGB filter.

Prepared finish tests cover three film/paper combinations and three chunk dimensions, with exact element equality against the original uncached Rust print/scan stages. This does **not** establish bit-exactness against direct spectral Python, whose stochastic field and internal LUT settings differ. V5 grain uses absolute-coordinate sampling; any additional validation limits belong in the separate reference report. Unsupported scientific options must fail explicitly, not be silently ignored.

## Privacy and source packaging

Continue sharing only allowlisted diagnostic fields. Never share raw native JSON, `effectiveParams`, file paths, source URIs, image samples, EXIF/GPS, arbitrary exceptions, raw logcat or crash dumps. Error text is fixed by code; export failure stage comes from that job's last allowed stage where available. Native does not persist or upload diagnostic history.

The cloud `native-v5/data` directory is a symlink to the V4 reference data. A distributable source bundle must copy its **actual data directory contents**; copying that symlink and renaming the workspace to `native` would make it self-referential. Include the workspace Cargo manifest/lockfile, complete crates, real data, licenses/attribution, port manifest and relocatable build script. Do not bundle build caches as source.

## Aggregated export display progress (additive fields)

`displayStage`, `stageDone`, `stageTotal`, `stageCounterScope: "stage-work"` are read under the same mutex as the raw pass fields. The stable display stages are 1 preparation, 2 exposure, 3 optical effects, 4 film development, 5 grain, 6 printing/scanning. Kotlin owns 7 encoding/save and 8 final owned temporary-file cleanup; native scratch deletion is still part of 6. Optional stages are skipped without renumbering. Setup has unknown work (`stageTotal:0`).

Within stages 2–6, one work unit is one planned, executed full-image operation (e.g. a layer sampling, full Gaussian, or channel-aggregated diffusion). Gaussian horizontal and vertical passes share a unit using processed pixel counts. Grain sampling, dye clouds, microstructure and final grain blur across every channel/layer share a single stage denominator. One additional finalization unit completes only after ancillary combines, cleanup and validations for that stage have returned successfully. Each operation has 1,000,000 integer subunits. These are **stage-local work fractions, not elapsed-time estimates or whole-export percentages**; operation costs differ. Counters are emitted by execution boundaries, never derived by counting received polls. A zero total means no percentage may be displayed. Current-pass fields retain their diagnostic meaning.

`streamed::export_file_with_progress` exposes this coherent typed event; the legacy export entry points retain their three-argument pass callbacks. No algorithm, numerical output, execution order or cleanup ownership changes are involved.
