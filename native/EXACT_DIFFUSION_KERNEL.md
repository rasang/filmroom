# Opt-in exact diffusion kernel preparation

The `ExactDiffusionKernel::new_cancellable_with_prepared` constructor accepts an
explicit request-scoped `prepared` choice. Existing `new` and `new_cancellable`
entry points use `false`, preserving the original row generator. The prepared
choice is execution policy, not photographic state, and is excluded from the
scientific kernel descriptor. The streamed exporter selects it with its separate
opt-in optimization mask; it must not be silently enabled for other callers.

## Work removed

- Expand the core/halo/bloom lambda sequences and weights once per kernel.
- Precompute lambda-in-pixels and `2 * PI * lambda * lambda` once per component.
- During normalization, evaluate each halo exponential once and reuse its exact
  f64 value across RGB. Each channel still performs its original separate
  `weight * exponential / denominator` and ordered component additions.
- Evaluate the left half of each row, including the center, and copy its values
  to their horizontal mirror positions. Both positions have the same squared
  integer offset. Symmetry is only used where all coordinates are exactly
  representable as f64 integers.
- During `row(channel, y)`, evaluate only the requested channel, rather than
  constructing all three channel rows and discarding two.

For the current 2-core/3-halo/4-bloom families, the old generator evaluates 15
exponentials per position per pass. Preparation uses nine per half-row position
in both normalization and a requested channel row. There is no approximate
exponential, tail truncation, radius change, resampling, GPU fit, RNG change or
FFT change. This operation count is not a device speed guarantee.

## Exactness contract

The order and grouping of every contribution within a pixel are retained.
In particular, weights are not folded into denominators, and there is no fused
multiply-add. Group multiplication and `core + halo + bloom` retain the original
order. The normalization loop still visits every full row and every x position
in the original order for each channel. Mirrored values are not multiplied by a
symmetry weight; the normalization sum is not parallelized or reassociated.
Y symmetry is not used to reorder normalization. Normalized outputs still use
one division by the same per-channel sum for every coefficient.

The untouched dense `diffusion_filter_psf`, legacy `raw_rows`, and default
constructor path remain independent reference implementations. Descriptor bits
must match across execution choices, including normalization bits. Cancellation
is checked once before every normalization row, at the same boundaries as the
legacy path. Immutable prepared data preserves `Send + Sync` and permits
arbitrary row/channel access order.

## Memory contract

Preparation stores only the fixed component constants, fewer than 1 KiB with
current family shapes. It stores no full kernel, quadrant, radial lookup table,
row cache or file. Normalization holds three output rows of f64 values; a
requested channel holds one output row. No pixel-sized temporary vectors are
used inside the prepared radial evaluator.

For the representative Cinebloom input (4000 x 6000, strength 0.24, spatial scale
1, pixel pitch 5.833333492279053 micrometers), the unchanged radius is 1999 and
kernel side is 3999:

- Normalization row storage: 95,976 bytes.
- Requested channel row storage: 31,992 bytes.
- Constant storage: reported by the test; less than 1 KiB.

These bounds cover kernel preparation, not unrelated FFT/operator allocations.
The representative benchmark holds both legacy and prepared generators for
comparison, but never materializes either full kernel.

## Verification

`exact_diffusion_prepared_tests` includes:

- Every f64 output bit for 224 complete small kernels across all four families,
  four geometries, and fourteen parameter variants, comparing the prepared path
  with both the legacy streamed generator and the untouched dense reference.
- Spatial-scale clamping, warmth saturation, independently disabled intensity
  groups, group size changes, all-zero intensities, and combined controls.
- Negative, zero, NaN, and positive/negative infinite values in every public
  control across all families, preserving constructor outcomes and coefficient
  bits (including nonfinite outputs) against the independent references.
- Descriptor equality, no-op validation, identical early/mid-normalization
  cancellation boundaries, immutable concurrent row access and row storage
  bounds.
- An ignored radius-1999 kernel-only benchmark comparing all 47,976,003 RGB
  coefficient bits, with separate normalization and three-channel row timings.
  No FFT files, image decoding, disk scratch, image output or hash pass is used.
  Bit comparisons occur outside measured row generation.

From `native`, use the existing approved build target and disk guard:

```sh
cargo test --offline --locked --release -p spektrafilm-model \
  --features precision-f64 exact_diffusion_prepared_tests -- --nocapture
FILMROOM_DIFFUSION_BENCH_RUNS=3 cargo test --offline --locked --release \
  -p spektrafilm-model --features precision-f64 \
  benchmark_prepared_cinebloom_radius_1999 -- --ignored --nocapture
```

Results are not established merely by adding these tests. Record the actual
host test results separately, and retain Android parity/performance gates before
making a device speed or end-to-end export claim.

## Verified host result (2026-11-03)

Rust 1.88.0 release build, `precision-f64`, existing host target, two build jobs
and one test thread: all five focused tests passed. The representative ignored
benchmark also passed all coefficient-bit comparisons in each of three paired
runs. Kernel-only totals in milliseconds (legacy, prepared) were:

- Run 1: 6126.165, 2730.243.
- Run 2: 6356.345, 2826.527.
- Run 3: 6214.337, 2745.724.

Median total: 6214.337 ms to 2745.724 ms, a 2.263x kernel-only speedup (55.8%
less time). This includes normalization and generation of every RGB kernel row;
it excludes all FFT work and image IO, so it is not an end-to-end export result
or an Android speed claim. Constant storage was 336 bytes. Normalization/output
row bounds were exactly 95,976/31,992 bytes at radius 1999.

The guarded run exited zero; minimum free disk was 3,674,288,128 bytes, above the
protected 2 GiB floor. The validation owner retains the full raw trials and disk
receipt at `results/opt3/kernel-validation.log` and
`results/opt3/kernel-validation.receipt.json` in the validation workspace.
