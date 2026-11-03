# Exact diffusion FFT pass fusion (opt2 candidate)

Status: opt-in candidate, export optimization mask bit 16 (`fused_diffusion_fft`).
Default remains off. Camera and enlarger diffusion use the same candidate.
Host differential tests and the isolated optical benchmark passed on 2026-11-03.
Android compilation/device execution are separate validation steps.

## Scope and provenance

This is an independent change to Filmroom's existing disk FFT pipeline. The
Spektrafilm and ZHAOYE static research motivated avoiding repeated intermediate
storage and reusing bounded buffers. No third-party implementation was copied.
No GPU, reduced precision, alternative FFT, approximation, resized image,
truncated PSF, changed reflection or changed grain random stream is introduced.

The untouched `apply_plane_configured` implementation is retained as the
baseline. `apply_plane`, `apply_plane_with_transpose` and existing callers keep
that behavior. Only explicit `apply_plane_with_options(..., fused_fft=true, ...)`
selects the new implementation.

## Removed work

The original pipeline materializes intermediate results between adjacent row
operators even when the next operator consumes precisely the same complete row.
The candidate keeps those values in a bounded complex-f64 row buffer:

1. Generate kernel rows and execute their first forward FFT before writing.
   Transpose directly to the retained kernel spectrum file, then finish the
   second forward axis. This removes the kernel-spectrum copy.
2. Generate reflected image rows and execute their first forward FFT before
   writing.
3. After transpose, execute the second forward FFT, the original per-value
   complex multiplication, and the first inverse FFT consecutively per row.
4. After the inverse transpose, execute the complete final inverse FFT only for
   rows contributing to output. Read every coefficient in each such row, then
   apply the original crop, normalization and scatter blend directly to output.

Skipping unused final-axis rows is exact dead-work removal. Each row is
independent after the transpose. No needed row, coefficient or halo is omitted.

Let S be one `side × side × Complex<f64>` file, where one complex value is 16
bytes. Before output cropping, the baseline kernel setup reads 4S and writes 5S;
the candidate reads 2S and writes 3S. Per image tile, the baseline reads 8S and
writes 8S before its cropped output reads. The candidate reads 4S and writes 4S,
then reads `valid_output_rows × side × 16` bytes for the last inverse axis.
The baseline instead reads `valid_output_rows × valid_output_columns × 16`
bytes after it has transformed every final-axis row. These are logical FFT-file
I/O counts, not physical device traffic or measured phone speed.

## Exactness boundaries

- Same radius, `next_fast`, square FFT side, core geometry and overlap-save tile
  boundaries.
- Same kernel constructor and row generator, channel, reflection indices,
  complex-f64 values and rustfft algorithms.
- Same forward/inverse axis order and exact complex multiplication expression.
- Same normalization and `(1-scatter)*original + scatter*value*norm` expression.
- Separate direction-specific planners as in the baseline. Plans and FFT scratch
  are reused only within one plane call; no global mutable cache is introduced.
- Moving independent rows across operator boundaries changes scheduling, not the
  operations on any individual row. Disk serialization was lossless f64 LE, so
  removing it removes no rounding step.
- Checkpoint pixel identity, durable commits, fsync policy and restoration are
  unchanged. Execution options remain excluded from scientific identity.

## Resource and failure contract

Three complex scratch files and one f64 output plane remain the maximum logical
scratch, identical to baseline. No full image or full spectrum is cached in RAM.
Both FFT scratches, complex and byte row buffers, source buffers, and the larger
of transpose/crop/kernel-generation temporary buffers are accounted for in
`explicit_buffer_bytes`. The candidate rejects geometry above the bounded domain
and enforces a 16 MiB explicit-buffer limit. FFT plan tables, allocator metadata
and OS page cache are not process-RSS measurements and are not included in this
metric. Existing whole-export memory admission remains in force.

Cancellation is checked before setup, between row operations, between transpose
blocks, between tiles and before success. Files are created exclusively and
cleaned by existing ownership guards on error, cancellation or unwind. Existing
caller output/work paths are not overwritten. Independent calls share no mutable
state and do not create an additional thread pool.

## Validation matrix

- Direct f64 byte comparison against the untouched baseline: four diffusion
  families, eight geometries including 1-pixel axes and forced overlap-save
  seams, two deterministic sources, all three channels, and both transpose sizes.
- Nonfinite and malformed input, cancellation at multiple phases, existing
  caller-file preservation, concurrent isolated calls and maximum-geometry
  explicit-buffer admission.
- Full f32 output against frozen `test_baseline_streamed16`: three dimensions
  including a 257×129 image crossing the pointwise chunk boundary, two seeds,
  Portra/Fujifilm Pro 400H, camera and print diffusion, normal
  grain/halation/DIR, and masks
  0, 16, 32, 48 and 63. Double-X is also checked at all three sizes against
  current mask 0. The frozen oracle explicitly rejects every B&W profile; its
  historical route restriction is unchanged. Each log identifies its oracle.
  SHA-256 and stage timings are logged. Earlier fixture
  attempts used Tri-X with print routing; the bundled Tri-X is 7266 reversal,
  which both baseline implementations deliberately reject. These failed logs
  are retained and the fixture was corrected to Double-X 5222 negative against
  the current mask-0 oracle. No production route validation was relaxed.
- Expanded cross-option checkpoint restoration and cancellation tests.
- Ignored RGB optical-stage benchmark: alternating baseline/candidate order,
  three rounds, 257×193 and 768×512, complete kernel construction and all three
  channel transforms. Recorded wall time also includes identical output read/hash
  verification and removal of that run's temporary output. `processing_ms`
  separately times kernel creation and channel processing only. Output hashes must
  agree. No cache-hit shortcut.

Synthetic fixtures exercise the engine contract. No user's original source
photo or Android device was available to this worker. Host timing is not a
prediction of phone timing, thermal behavior or storage performance.

## Recorded host results (2026-11-03)

Release build, precision-f64, two Rayon workers, exclusive heavy-work slot:

| RGB stage size | Baseline median | Fused median | Time reduction | Speed ratio |
| --- | ---: | ---: | ---: | ---: |
| 257×193 | 92.033 ms | 68.731 ms | 25.32% | 1.339× |
| 768×512 | 671.543 ms | 514.501 ms | 23.39% | 1.305× |

These are `processing_ms` medians of three complete recomputations per mode,
including kernel generation and all three channel transforms. At 768×512,
baseline range was 664.492–713.133 ms and candidate range 501.866–523.527 ms.
OS page cache was not flushed. This is not a whole-phone-export speed estimate.

The complete RGB f64 output SHA-256 matched in all six trials for each size:

- 257×193: `da4f2e027518e3c63c9d85b896cae5a4cd9a8f13d366ac6d16371805846c3bb0`
- 768×512: `44cd50b1576512d3db320058c2d0ecc26e57b1b48322f636c30fb1df2f126723`

The final library run passed 143 tests, failed none, and ignored two explicit
benchmarks. The optical benchmark was then explicitly run and passed. The new
f64 matrix made 384 candidate/baseline comparisons, with four additional
concurrent comparisons. The full-export matrix covered 45 unique comparisons
and ran twice (90 comparisons), all byte-identical with zero changed finite f32
samples. Color fixtures used the independent frozen pipeline; Double-X used
current mask 0, as described above. Cross-option checkpoint and cancellation
tests also passed.

Raw log: `opt2-optical-verified.log`.
Log SHA-256: `0bbd39b42bd47d008c571f664a9823f8168ff91bae99a6060f776e8fda4f2659`.
Parsed trials, every output hash, stage timings and guard receipt:
`opt2-optical-results.json`. These are in the development task's results folder.
The guard recorded minimum free disk 3,440,566,272 bytes, above the protected
2 GiB floor. No quality relaxation or production-route change was made.
