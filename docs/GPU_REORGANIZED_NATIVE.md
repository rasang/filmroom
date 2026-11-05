# Reorganized resident GPU native implementation

Algorithm identity: `resident-f32-psf6-cap64-fir256-grain-rms-v1`.
This is an explicitly approximate path. It does not promise CPU pixel identity, identical random samples, measured speedup, or a measured peak-memory guarantee.

## Ported v41 native families

- Core pipeline: borrowed preview resident entry, direct f32 upload, one-band processing, whole-image pitch, AE supplied once by the engine, absolute band noise coordinates, global camera/enlarger diffusion field taps, CPU f64 output-gamut boundary, sublayer curve/particle data, finite spatial halos.
- GPU interface: ChainImage, ChainStop, diffusion scattered fields, sublayer data, offset-aware grain/glare. Optional CUDA explicitly declines newly unsupported semantics instead of silently running a different chain.
- WGPU: resident pointwise and spectral stages, per-stage point operations, compiled shader cache, storage limit queries, direct map when supported, staged readback otherwise, early release of owned transient buffers after map. Driver references may outlive Rust handles.
- Model: cap64 six-Gaussian exponential fit, partial-cell downsample accumulation, whole-frame scattered fields. Exact CPU PSF code remains available and is not replaced.
- WGSL families: color conversion/encode, density/LUT/table operations, spectral front/print/scan, highlight reduction, FIR blur, halation/scatter, DIR, simple and sublayer grain, glare, gamut, sharpening, downsample/upsample and field mixing. All 35 shaders parse and validate with Naga without creating a GPU device.

## Safety and correctness changes relative to v41

- All WGPU maps use nonblocking Poll with a 5-second completion deadline; late callbacks tolerate receiver disposal. A failed map retires the cached device. Every GPU submission is serialized with a 250-ms lock deadline; contention can cause an explicit CPU fallback. Device initialization futures have a 6-second deadline.
- Controlled preview/whole/band entry points check real cancellation or the export permit before and after the resident submission, CPU gamut, scanner blur, unsharp, and final output conversion. Callbacks execute outside GPU ownership. A cancelled job no longer continues into two further scanner submissions. Old APIs use always-true wrappers for compatibility.
- Small previews retain one resident submission. Larger controlled exports use bounded stage submissions and 4-byte completion fences while keeping image intermediates on the GPU. DIR Gaussian horizontal and vertical work have separate completion boundaries. Scanner blur/unsharp can additionally submit after the resident chain. Confirmed fences release the GPU lock before cancellation/cooperative scheduling callbacks; the 5-second map deadline is not a bound on CPU preparation or blocking driver calls.
- All 29 one-dimensional point shaders use 256 invocations, matching one checked backend dispatch constant. The five 16×16 spatial kernels and the max-reduction kernel's 256 threads/2048 input values are unchanged. Adapters below the required limits are rejected before pipeline creation. Scalar highlight dispatches and u32 bounds are checked separately from pixel-buffer limits.
- Submitted work is not described as immediately cancelled. Drivers may block inside synchronous initialization, pipeline creation, Poll, or resource destruction. This branch does not add a separate GPU process and cannot guarantee recovery from a wedged driver. One cached device is retained; callers must not recreate retired devices repeatedly.
- A submission-lifetime guard sets sticky `completion_uncertain` after any unproven completion (timeout, map/disconnect error, or unwind after submission). The engine must reject fallback and later preview/export in that process until restart, rather than overlap unknown GPU work with CPU allocations. Successful mapped completion clears only the per-submission guard; the sticky error is never reset.
- The small-image lifetime reserve includes accumulator/output/single-Gaussian host copies, GPU storage and readback. It is conservatively 192 bytes per small pixel; accumulator sums are also explicitly released before Gaussian dispatch, without relying on that release to reduce the advertised budget.
- Per-thread actual-submit counters prevent attributing a concurrent preview's submissions to an export. Submission counts alone do not prove a valid output.
- Production global prepasses check cancellation/cooperative scheduling before every read band and Gaussian component. Read errors return failure; no `expect` on spool reads. Small Gaussian components are accumulated one at a time and released, avoiding all-component peak residency.
- Bands never round up to the diffusion downsample factor. Partial cells can span short bands. Both global fields stay in the working-set estimate, including the small camera-field copy needed by the enlarger prepass.
- `gpu_diffusion_working_bytes` reserves 192 bytes per small pixel per active plan, in addition to the engine's band and fixed reserve. This is conservative planned host/device lifetime accounting, not measured RSS.
- Highlight boost now gets one whole-image front-pass maximum, not a separate maximum for each band. The same maximum feeds camera/enlarger taps and main bands.
- CPU f64 gamut executes before scanner blur/sharpen. Scanner optics then use GPU FIR and the final clamp/encode occurs last. Scan output is never clipped early merely because gamut compression is off.
- Grain honors the user seed and uses distinct deterministic preview/export streams. Malformed requested sublayers reject the resident attempt instead of silently switching to the simple model. Dye-cloud spread is a disclosed RMS aggregate-blur approximation; active microstructure uses a spatially blurred, coordinate-stable multiplicative field. Both controls remain effective. These are not identical to CPU per-sublayer dye-cloud convolutions or NumPy RNG.
- Original image geometry and linear float spool precision remain caller contracts. Field downsampling approximates the diffusion field; it does not resize the delivered image.

## Verification at implementation handoff

- Rust 1.88 host `cargo check --release --offline --locked` passed for GPU/core/model with wgpu and f64 features (two passes). Guarded run min free disk 4.017 GB; maximum observed command-group RSS 770 MB. Existing warnings remain.
- Independent Naga parse+validate: 35 shaders, zero failures. This is static shader validation, not Vulkan execution or a device correctness/performance test.
- Added host tests cover partial downsample cells/short bands, cancellation between Gaussian components, user seed and preview/export separation, read-failure cancellation, global highlight maximum reuse, and shader validation. Their runtime execution is tracked separately in the integrated validation log.
- No emulator, physical Android GPU, photo-quality comparison, thermal run, cancellation latency measurement, or full preview/export end-to-end result is claimed here.

## Controlled-entry and mobile workgroup follow-up

The earlier check receipts predate this small follow-up. The integrated owner will rerun checks/runtime suites on the combined final sources. Added four mock-backend cancellation/numeric compatibility tests and three dispatch/adapter/shader-layout tests. Independent Naga validation after the 256 change remains a static check, not a hardware test.

## Rectangular export follow-up (source implementation, validation pending)

Production export now sizes both tile axes against the supplied 128–512 MiB budget and adapter storage limit. Each tile carries its original full-frame dimensions and x/y origin, reads only a halo-expanded rectangle from the float spool with an explicit row stride, and writes core rows by seeking to their full-resolution positions. The output is never resized. Cancellation and ExportPermit boundaries remain before/after controlled submissions and between output rows. Whole-frame inputs still use the whole resident path when admitted.

Global highlight maximum and camera/enlarger scattered fields are prepared once with rectangular taps. Their conservative 192 B/small-pixel working estimates remain reserved throughout all tile work; a field or minimum halo tile that cannot fit still produces an explicit CPU fallback. This implementation does not claim disk-backed diffusion fields, universal GPU admission, measured peak RSS, or faster execution. Retained fields are small-resolution global fields, not resized delivered images.

Diagnostics distinguish `tiled-chain` / `approximate_tiled_chain` and `gpu_tiled_chain`. `gpuTiles` reports core/expanded width/height, while `gpuBatches` counts actual thread-local submissions. Spatial I/O counters describe prepass/main render spool reads and output writes, not identity hashing, AE, checkpoint copies, or thumbnail reads. The separate `gpu-v41-approx-tiles-v2` checkpoint namespace restores only complete final outputs, never partial tiles.

The progress snapshot retains a whitelisted `gpuFallbackReason` through subsequent CPU stages and cancellation/failure. It resets only when the next job begins. This avoids losing the decision between native progress polls. It does not reinterpret CPU elapsed time as GPU execution.

Added tests cover exact device-reported op71/op86 parameter admission (50.3 MP Black Pro Mist 0.5 and 12.6 MP Black Pro Mist 1.600000023841858), rectangular float spool strides and short x/y tails, actual budget-selected 2D GPU export seams without grain, same-path determinism, and cancellation cleanup. The large production export test is opt-in with `FILMROOM_LARGE_GPU_SMOKE=1` and an explicitly disk-backed `FILMROOM_GPU_TEST_SCRATCH` directory, and must run under a resource guard; without that flag it explicitly prints `LARGE_GPU_NOT_RUN`. Source tests are not execution receipts.

## Bounded rectangular export follow-up (working branch)

The additive `TileContext` API carries full image dimensions and global x/y
origin independently of the local input/output tile. It pins full-image pitch,
requires globally folded auto-exposure, checks geometry and finite data, and
returns the input tile's exact dimensions. The engine writes only its core.
The composed existing FIR halo applies on all four sides, clipped only at the
actual image boundary. Grain, microstructure and glare now use
`pixel_offset + local_y * full_width + local_x`, with local width retained for
buffer addressing. Legacy full-width bands retain their coordinate identity.

The rectangular global prepass keeps a whole-image highlight maximum and then
processes camera and enlarger diffusion in that order. Rectangular cores cover
each original pixel exactly once, even where short tiles split downsample cells
in both axes. The enlarger tap includes the camera field and spatial halo.
All original Gaussian components remain in the small-field accumulation.

Memory admission still reserves 192 bytes per global small pixel per active
filter plus the engine's fixed reserve and full-resolution tile working set.
During a prepass the accumulator, current tap and retained camera field coexist;
the temporary camera clone drops before small-image Gaussian work, accumulator
sums drop before the first Gaussian, and only one Gaussian result exists at a
time. Main rendering retains final host small fields, but WGPU uploads only
the tile's global bilinear footprint using borrowed row slices, with no second
host crop allocation. `DiffusionScattered.crop` also permits a caller-supplied
small-field crop; missing bilinear neighbours, malformed shape and nonfinite
values fail before submission. Small dimensions and sample coordinates always
refer to the global field, never a locally renormalized tile.

This is not a disk-backed field implementation or a guarantee every budget will
fit. The conservative full-field reserve remains mandatory. Tile overlap and
repeated submissions can increase runtime substantially, especially when the
halo is large relative to the core. Static shader validation and later runtime
receipts must be distinguished; no mobile performance claim follows from this
architecture alone. Pending-submission quarantine, cancellation outside the GPU
lock, 256-thread point shaders and the five-second map wait remain unchanged.

Rectangular core verification (2026-11-05, host Vulkan): production core release
check passed; integrated engine/core test typecheck passed; all 35 WGSL shaders
passed Naga parse/validation. `gpu_rectangular` ran on the required GPU backend
and passed in 1.94 seconds: 12 rectangular cores with short right/bottom tails,
same-path exact repeats, stochastic coordinates without diffusion and global
camera+enlarger fields with cropped uploads. Both modes had zero maximum/RMS
error against a whole-image invocation using the same precomputed fields;
thread submission counters were 75 and 294. This isolates tile coordinate and
sampling consistency, not CPU identity or a separately computed whole-frame
field's arithmetic. Large image/device measurements are separate receipts.

### Completed-batch export scheduling

The first real 3072×4096 Black Pro Mist export exposed a separate execution
limit: its first 956×957 expanded tile exceeded the existing five-second map
wait as one fused submission. The prepass completed; quarantine correctly
prevented CPU fallback. This prompted bounded submissions, not a longer wait.

Large rectangular inputs (more than 65,536 pixels with global tile context)
now retain image buffers on the GPU while fencing stage groups. Halation and
DIR Gaussian pairs are fenced separately; other groups cover front, camera
optics, density, grain, printing, scanning and glare. Each fence maps four
bytes, uses its own submission-lifetime guard and the unchanged five-second
wait. Only after proven completion is the execution mutex released and the
controlled callback invoked. Cancellation returns without a partial image;
unknown completion still quarantines the process. Small preview and small
resident inputs keep the single-submission fast path. Global Front taps do
not add an unnecessary intermediate fence before their final readback.

The app's existing process-wide NativeWorkReservations ledger retains the
entire export reservation across this yield. A preview requires its own
separate byte reservation before JNI. Direct native callers must provide
corresponding concurrent-job admission; a cooperative scheduling callback by
itself is not a shared memory budget.

Thread-local progress resets before a new controlled chain's first callback.
Only completed fences publish a fixed stage identifier, completed batch count,
individual submission wait and cumulative chain GPU wait. A separate failure
snapshot records stage and elapsed time on an unconfirmed submission. These
are observed completion/timing facts, not an inferred percentage of total work.
The production Rust check for this follow-up passed; runtime verification is
tracked in the integrated validation receipts and supersedes earlier one-batch
submission counts.

The batched follow-up's required-GPU small suite subsequently passed: both core
rectangular tests (2.67 s), including nine completed batches with zero pixel
delta versus the unfenced result, a real GPU operation from the checkpoint
callback (proving the execution mutex was released), cancellation after two
completed batches without quarantine, and healthy reuse. Engine GPU routes
passed 5/5; the small preview control cases each remained one submission.
This does not substitute for the ongoing original-size export validation.

The staged export snapshot reports a current 1-based tile index/total and the last completed GPU phase, batch elapsed time, cumulative chain wait, and confirmed submissions. It does not reset overall output-pixel progress for each internal phase. Failed-phase timing is separate and never counted as confirmed work. The Android process-wide reservation ledger retains the complete export lease across yields and separately charges an admitted preview; stage callbacks do not release the export memory reservation.

The focused op71 probe can rebuild the actual synthetic 50 MP input's global diffusion field and render only the previously failing interior rectangle twice. This isolates the workload and same-path reproducibility, not complete-image acceptance. Full-image validation selects cases with `FILMROOM_LARGE_GPU_CASE=71`, `86`, or `both`; an optional `FILMROOM_GPU_TEST_CANCEL` marker is checked at controlled cancellation boundaries.

### DIR-axis and whole-export follow-up

The subsequent 50 MP run isolated an unconfirmed DIR Gaussian-pair batch on
1179×1180 tile 17. Its horizontal and vertical FIR axes are now fenced
separately, preserving all coefficients, radii, intermediate precision and
effect ordering. The small preview still queues the same passes as one batch.
Whole CPU-gamut export inputs above 65,536 pixels now use the same bounded
scheduling as rectangular export. Their internal camera/enlarger diffusion
mixtures fence each Gaussian component, so fitting RAM does not imply putting
the complete optical chain in a single five-second map wait.

Required-GPU tests passed 3/3 on this source: DIR-enabled fenced versus
identical unfenced prepared parameters had zero pixel difference, completed
callbacks allowed real GPU reentry, cancellation kept the device healthy, and
whole dual-diffusion output was exactly equal with 119 versus 3 submissions
(including outer scanner operations). Small preview remained one submission.
The exact previously failing tile was also run cold and warm using the real
50 MP global prepass and original photographic parameters. Both completed;
the warm case used 21 resident submissions, took 16.955 seconds, and its
largest completed DIR batch was 2273.606 ms. Receipt:
`tiled-dir-axis-short-runtime-1`. These are software-Vulkan host observations,
not Android speed claims or proof of the pending full 50 MP output.
