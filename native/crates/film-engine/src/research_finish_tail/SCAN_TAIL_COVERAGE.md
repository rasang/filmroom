# Resident scanner suffix coverage

This module is an experimental f32 implementation of the actual `streamed.rs` stage 6 suffix. It does not establish that the entire application or pipeline is GPU-only, or that an export finishes in ten seconds.

## Original order preserved

1. Optional glare: the original seed-42 ChaCha12 / ZIGNOR sequential normal stream, LogNormal transform, original full-image Gaussian, then multiplication by 0.01 when adding its RGB offset. A supplied external raw-percentage field is an explicit alternative input, reported as `glareGenerationGpu=false`; it is not represented as GPU stochastic generation.
2. Scanner logXYZ → pow(10, logXYZ).
3. Optional original luminance remap `clamp(m * Y + q, 0, 1) / (Y + 1e-10)` applied to all XYZ channels.
4. Separate original CAT and XYZ→output RGB matrices. The matrices are not precombined.
5. Original glare offset addition.
6. Original output gamut algorithm and full 64×720 Cmax table: off, ACES RGC, OKLCH, OKLRAB, or CAM16-UCS. Unsupported algorithms/spaces already disabled by the original compressor remain disabled by its original descriptor; this module does not substitute a different compressor.
7. Original branch-specific CCTF behavior: the streamed path only finalizes its non-spatial/no-glare/no-print-diffusion branch. Mobile currently mandates linear output. A preview request with CCTF enabled is rejected rather than moving its encoding before spatial processing.
8. Original scanner lens Gaussian, then Gaussian unsharp mask, then [0,1] clipping. These use the shared complete-line FIR/IIR implementation, not tile-local halos or a different blur approximation.
9. Preview only: the original `output_color::to_linear_srgb` behavior. Display P3 and Adobe RGB use separate RGB→XYZ then XYZ→sRGB matrices after clipping. Other original no-op spaces stay no-ops. Export does not add this conversion.
10. Original brightness multiplication after final clipping/output-space conversion. Preview conversion may produce values outside [0,1]; there is no extra post-conversion clip.

The suffix retains three full RGB planes plus two spatial scratch planes on the GPU. Channel swaps preserve all original channels while blur/unsharp use the same bounded spatial implementation. The first full RGB readback occurs after the suffix; only small validation witnesses/control counters return earlier.

## Bounds and failure behavior

- At most 5 full-resolution f32 planes, two 4 MiB transfer/gather buffers, and fixed metadata allowance. The existing spatial limit caps a single plane at 128 MiB; the combined tail admission limit is 768 MiB.
- Host allowance 24 MiB, driver/helper allowances 64 MiB each. Manifest Cmax data and bounded file chunks are covered separately from device buffers.
- Glare random pools contain at most 65,536 u64 words. Candidate work is parallel, with finite outer/tail iteration limits. The selector emits at most 16,384 pixels per dispatch and returns only control counts.
- Nonfinite inputs, unsafe numeric domains/ranges, invalid metadata, insufficient budgets, uncertain rejection predicates, bounded-iteration exhaustion, GPU error, timeout, cancellation, or invalid candidate hashes reject the whole candidate. Numeric rejection uses pre-operation guards and a separate sticky integer atomic, not a NaN sentinel. Source snapshots are immutable. The caller must confirm isolated helper death before adopting the complete candidate or running the original whole stage on the CPU.
- CPU work remaining within this module: file IO, source/manifest/hash validation, constant calibration/table preparation, control counters and small numeric validation witnesses. These do not generate the normal output image. The optional external-glare input is explicitly reported.

## Verification and limitations

The first nine scanner-tail host tests passed, including all active gamut modes, six output-space descriptors, B&W correction, composed WGSL validation, FIR/IIR tiny edges, cancellation, budgets and atomic candidate validation. The standalone glare prototype additionally matched all 131,073 tested original ChaCha12 words, all 200,000 selected normal variates and all 204,329 consumed words across three window sizes; its composed shader passed Naga validation. These are host arithmetic/source checks, not an Adreno run.

`FILM_GLARE_SEQUENCE_SAMPLES=32000000` enables a constant-memory complete-prefix census covering the maximum admitted frame size. Ignored Vulkan tests compare complete generated glare and suffix outputs against the original oracle. These tests must be run on an available device; compilation and host checks do not establish Android driver behavior or performance.

See `GLARE_INTEGER_ERROR_BOUND.md` for the conservative integer rejection-predicate error bounds. Normal and final image arithmetic remain f32, not bit-identical f64 outputs.

See `NUMERIC_DOMAIN_GUARD.md` for the later finite-math portability correction. Its source tests and device tests are separate from the baseline results above; a previous Naga or ARM pass does not automatically validate this revision.
