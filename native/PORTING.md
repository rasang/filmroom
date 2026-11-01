# Local Android CPU engine — V5 stage-streamed source

## Origin and distribution

- Python reference: Andrea Volpato / `andreavolpato/spektrafilm`, revision
  `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`.
- Rust base: `turbasvin/spektrafilm-rs`, revision
  `9dd59b0380194b93686aaa230a8bb9680aa270a4`.
- This unofficial derivative is GPL-3.0; preserve LICENSE,
  SPEKTRAFILM_LICENSE.txt, modified-source notices and corresponding source.
  Bundled data retains Andrea Volpato's separate CC BY-SA 4.0 attribution.
- All development fixtures are synthetic or separately credited public-domain
  QA material. The shipped rendering pipeline is local; no photo upload, server
  inference, remote render or network fallback is part of this engine.

## Portable physical core

The desktop GUI, Python runtime, GPU drivers and platform BLAS are not built.
Portable Rust CPU matrices use bounded spectral scratch rather than an
image-sized wavelength array. Python half-sample reflect FIR edges and the
three-sensitometric-layer particle grain model were restored in the V4 baseline.
V5 retains those corrections and its GPL/data provenance.

Two 33³ internal spectral integration tables are prepared once. These accelerate
printing and scanning integrals; they are not a composite RGB look LUT. Film
sensitometry, paper curves, wavelength-derived color, full-image halation and
scatter, DIR coupling, Poisson/binomial grain and dye-cloud blur remain separate
physical stages. LUT interpolation has its own measured numerical tolerance;
do not describe the entire color path as exact unaccelerated spectral parity.

## What changes from V4

V4 finite-halo whole-pipeline tiling could reject 12–50 MP defaults or repeatedly
recalculate large overlapping halos. V5 instead streams each full-image physical
stage through local f64 planes. Separable filters see complete rows/columns and
the actual full-image boundary; there is no tile halo or multiscale downsample.
The original FIR / IIR arithmetic is retained, including the upstream IIR
approximation to a Gaussian. Pointwise work uses bounded full-row groups.
Details and scratch-file lifetime are in STREAMING_STORAGE.md.

Export grain uses absolute coordinates and physical Poisson/binomial
distributions, not NumPy's sequential random texture. It is deterministic for
the same image dimensions/seed and independent of pointwise chunk boundaries.
Preview's sequential random field and lower physical pixel sampling differ;
preview and original-size export need not match grain pixel-for-pixel.

## Public contract and operational limits

- Only validated MobileParams is the supported user-facing interface. Arbitrary
  upstream scientific RuntimeParams is not a universal compatibility API.
  Unsupported active hidden effects return errors rather than being ignored.
- Native input/output: exact width×height×12 bytes, packed RGB f32 little-endian,
  finite linear-sRGB/D65. Negative/HDR input is accepted. Caller applies decoder
  crop/orientation exactly once before rendering and output CCTF exactly once
  after it. Engine does not decode image formats or rotate/crop the spool.
- Direct-buffer preview is explicitly limited to 262,144 pixels. Original-size
  export is the separate file-backed path; it never silently shrinks the image.
- Hidden memoryBudgetMiB accepts integers128..512, default384. Streamed working
  buffers are independent of full-image area; budget figures are admission
  estimates, not Android process RSS guarantees. App/decoder memory is separate.
- Storage and low-memory failure are explicit. No effect is disabled to fit.
  Cancellation is cooperative between bounded chunks/rows/strips. Output is
  create-new and partial output is invalid/removed on error. The app must recover
  orphaned owned scratch after process death, which cannot execute destructors.
- Actual cloud CPU timing is not phone performance. Android cross-compilation
  and host JNI tests are not an on-device runtime measurement. No emulator is
  required or used for this V5 validation.

## Validation

`results/v5-disk-spatial-validation.json` checks the exact disk Gaussian against
the whole-image operator, including FIR/IIR boundaries and narrow8192/6144 axes.
`results/v5-streamed-reference/` contains small end-to-end f32 bitwise comparisons
with the same whole-image CPU pipeline and cancellation/cleanup tests.
`results/file_export_v5/` records actual original-dimension export, isolated-child
wait4 peak RSS, stage timings, logical scratch high-water mark, byte counts,
finite checks and input/output hashes. Only terminal reports with status passed
are completed tests. Final manifests list which source and binary each test used.

The pinned Python snapshot suite itself has 21 passes and3 failures; there is no
blanket all-upstream-tests-pass claim. Existing explicit Python/Rust reference
fixtures remain the baseline evidence. Small synthetic agreement does not prove
every scene, camera decoder, phone architecture or parameter combination.
