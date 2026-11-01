# Owned scientific detail API (0.1.9 integrated)

The owned `film_engine::detail` API is integrated in the 0.1.9 Android source and both matching ABI libraries, with `precision-f64`. Export jobs/checkpoints retain their separate path. See `OWNED_DETAIL_JNI.md` and the final release validation; host checks do not establish device performance.

## Contract

`DetailContext::preflight(&DetailConfig) -> Result<ResourcePlan, DetailError>` validates the explicit working grid (1..=1536 per axis), full mobile parameters, supported route with explicit CPU fallback, stage/spectrum and private-disk budgets. No original-size or 4608-grid auto-allocation/resizing is attempted.

`DetailContext::build(config, data_dir, source_f32le, private_parent, token) -> Result<Arc<DetailContext>, DetailError>` copies the caller's linear f32 source into a new private directory, validates byte shape/finite samples, freezes parameters and builds exact full-grid **pre-grain** f64 scientific planes. Input must not be concurrently modified while copying. Source/decode, oriented mapping/crop and immutable data/algorithm revisions are caller contracts, not inferred from a filename; callers must change revisions when content changes. Complete serialized parameters and virtual dimensions are also retained in identity. Pixel pitch derives from full geometry, never the requested tile.

`context.render_cell(cell_x, cell_y, token) -> Result<OwnedDetailTile, DetailError>` computes canonical 256-pixel cells anchored at the image origin. `core_rect` is clipped at the true edge; `sample_rect = expand(core_rect,1).clip(fullBounds)`. Scientific dependencies derive from sample_rect. Result owns RGB f32 **linear sRGB** samples, global rects and immutable identity. Draw sample pixels through the shared image transform clipped to core_rect. A generation/publish guard belongs to the UI, not the cache identity.

One worker may render per context. A busy context returns an explicit error; there is no implicit work queue. Worker must hold its Arc until it returns. Dropping the UI's reference or cancelling a token does not free a worker's context; only the final owner removes private planes. Completed returned tiles own their pixels independently. Context never caches final display images.

## Exactness and scientific caches

- Full-grid AE, spectral exposure, camera PSF, boost, lens/halation and DIR use the existing bounded streamed numerical operations, copied into a preview-private module. No export checkpoint/job lifecycle is called or reused.
- Private pre-grain planes stay f64. Local grain uses full finite support, absolute virtual pixel coordinates and clipped true edges; operations run in 64-pixel chunks plus support.
- Print irradiance is cached in canonical 256-pixel **fixed blocks**, evicted by byte-bounded LRU. No growing rectangle is retained/copied. A temporary dependency rectangle exists only for the current bounded output request.
- Valid-output FFT uses original finite PSF and true-image reflection. Spectrum reuse compares exact kernel row bits, scatter, radius and full virtual geometry, and counts key storage against its byte limit.
- “Exact” means the original mathematical finite/global operators at the same working geometry, not universal bitwise equality across different FFT layouts. Floating roundoff can differ; reference tolerance and deterministic rebuild are tested separately. No full-original equality is implied by a smaller working grid.

## Explicit unsupported modes

Scanner blur/sharpen/glare and unsupported profile/route combinations return `Unsupported`; they are not silently disabled. Active grain blurs requiring IIR/global influence or sequential RNG return `Unsupported`: implement an exact global-grain context stage before enabling them. GPU requests use explicit CPU fallback while retaining requested parameters identity; backend metadata states what ran. The16 negative stocks support print; the4 reversal stocks support their existing direct-scan route (grained density cache, no print PSF). Negative/direct and reversal/print remain unsupported. Unsupported requests must keep the low-resolution underlay, not publish approximate results as exact.

## Cancellation and resource boundary

Checks occur in source IO chunks, AE rows, global pointwise chunks, exact FIR/IIR work, camera FFT rows/transpose blocks, kernel rows, local grain chunks, print FFT source/output/transform rows and multiply chunks, and scan chunks. Cancelled partial blocks/spectra are not published. Existing Pipeline/calibration/LUT constructors have checks **before/after**, not internal preemption; file IO and one bounded FFT/grain chunk cannot be interrupted mid-call. No hard cancellation latency SLA is claimed.

ResourcePlan conservatively counts persistent stage+FFT caches (including kernel identity), active dependency/FFT/scratch and profile/LUT overhead. Private disk planning uses actual disk-FFT geometry, including panoramas whose square FFT footprint is much larger than image area. Logical disk and process RSS are separate; filesystem cache is OS-managed. Returned tiles retained by the caller, simultaneous contexts, RAW decoding, Android/Java/GPU allocations and exports require a **shared external reservation** in the future adapter. Preflight is not an allocator sandbox or Android admission approval. A working-grid/budget failure is explicit; the API does not quietly resize.

## Host commands

```sh
source /workspace/shared/toolchains/env.sh
export CARGO_TARGET_DIR=/workspace/shared/filmroom-roi-native-api-target
export RAYON_NUM_THREADS=4
cargo test --offline -p film-engine --features precision-f64 --lib -- --test-threads=1
cargo build --offline --release -p film-engine --features precision-f64 --bin detail_probe
# DATA INPUT WIDTH HEIGHT MOBILE_PARAMS_JSON OUTPUT_DIR PRIVATE_PARENT
detail_probe data input.f32le 1152 1536 params.json output private
```

Tests and benchmark evidence are recorded separately under `validation/`. The host CLI is not JNI glue; the separate experimental JNI contract has real JVM host tests. Next integration gates: externally owned shared reservation, source/EXIF/crop adapter, latest-generation publish gate, gutter Canvas comparison, and real-device cancellation/RSS/thermal/background-export testing. No emulator, Mac, phone, or APK validation is claimed.
