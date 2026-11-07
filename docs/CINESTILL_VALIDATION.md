# CineStill validation record

Host validation, 2026-11-06 UTC. These are synthetic rendering tests, not Android
device screenshots or a measured comparison against a developed film frame.

## Provenance

- Branch base: `5a11522c4ae37d1989c4df76912f6e581d40f272`.
- Shipped APK SHA-256: `19019bf883bdffd434039ab6be26290d2bf2f618658bff4996531609cac3fcdc`.
- Frozen baseline host CPU JNI SHA-256: `e3493c628271b8f45e2151f452f39417fcbaf6c45b4ee19738ac9b16e0599fbe`.
- New tested CPU JNI SHA-256: `5f91680d211c0e00d20fef3dfa8fda9b2be3137405854b3fcc4b61a57052afbf`.
- No shipped APK, ARM library, film-edge assets or source release was changed.

## CPU tests passed

All commands use Rust 1.88.0, `--release` with optimization disabled to bound
compiler memory, `--features precision-f64`, one build job and one test thread.

- `film-engine --lib cinestill`: 3 passed (product scope, full-frame headroom, detail pregrain).
- `spektrafilm-model --lib cinestill`: 2 passed (PSF tail/constant field, old wrapper/hue).
- `film-engine --test cinestill_halation`: 3 passed (35 mm appearance, whole/streamed agreement, unsupported diagnostic tiler rejection).
- `film-engine --lib spektralab_stock_tests`: 3 passed, one overlaps the first filter.
- `film-engine --test spektralab_profiles`: 8 passed, including historical arrays, silver profiles and whole/streamed direct/print routes.
- Two dependency-free shared math tests were freshly rerun and passed 2/2
  against reviewed source `7e43e641`; the standalone run receipt is retained.

The final CPU sequence took 96.25 seconds with peak owned-process RSS 803.3 MiB.
The resource guard limited the process group to 1 GiB RSS, and required at least
768 MiB system available memory and 768 MiB free temporary space. Earlier
failed attempts were retained: an optimized compiler process received SIGKILL;
a test initially made an invalid raw-RGB ratio assumption; the corrected test
then exposed a real -3.87645e-6 IIR far-tail undershoot. The latter was fixed
with the documented completed-halo non-negativity guard before the passing run.

## JNI before/after checks

Both host libraries rendered the same 24 cases each using the shipped data,
512 × 128 pixels, 35 mm format, a 16 × 80 white lamp on linear 0.002 background,
lamp input 1 or 8, direct-negative or print route, six option combinations.
No grain, scatter or DIR was used to isolate the optical change. Default
`kodak_2383` print medium was used. PNGs were sRGB encoded only for inspection.

- Every case completed with finite output.
- All eight full-output SHA-256 comparisons were identical before/after:
  four 500T cases with default halation enabled, and four 800T cases with
  halation disabled. This is full RGB-f32-buffer equality.
- Ordinary level-1 lamp, print route, stock defaults: contiguous pixels beyond
  the lamp with positive linear red increment > 0.01 expanded from 1 to 15.
- On the middle row, pixels 272–295 (9–32 pixels beyond the right lamp edge)
  have red increment sum 0.275421 and green sum 0.015353 after the fix. Both
  sums were zero at baseline after positive-part clipping.
- At x=272, output RGB changed from (0.000236, 0.000331, 0.000291) to
  (0.069765, 0.003409, 0.000701). The displayed new edge is warm/red and broad;
  the original is almost invisible at the same display scale.
- Level-8 input expands the >0.01 red support from 2 to 24 pixels.

These values describe this fixture, not universal halo width or a promise
that a dark/no-highlight source will exhibit the same effect. The direct
route is a scanned negative and is not presented as a positive print.

JNI work ran serially, with 192 MiB JVM heap and one active processor. The
48-case comparison plus six unrelated frozen-library print-medium checks
took 49.09 seconds; peak owned-process RSS was 198.6 MiB.

## GPU validation

Host Vulkan using the installed SwiftShader device, required rather than silently skipped:

- `spektrafilm-gpu --lib cinestill`: 1 passed, including the actual completed-halo clamp shader with a negative source that must remain negative.
- `spektrafilm-core --lib cinestill`: 5 passed. Two execute actual GPU work; three check global maxima, invalid inputs, cache suffix boundaries and wide support.
- All resident WGSL files parse and validate: 1 passed, including all five new shaders.
- Small-sigma CPU/GPU FilmLog error stays below 0.003. Resident/shared-maximum results are identical; tiled-core seams stay below 1e-5.
- A real small-kernel mismatch was found and fixed: CPU FIR rounds 3σ half up, while the first GPU implementation always used ceiling. New-model kernels below sigma 3 now match CPU support; wide kernels retain full ceiling support. The acceptance threshold was not relaxed.

At 512 × 256 and 35 mm (68.359375 µm/pixel), the required halo is 53 pixels:

- Center lamp: maximum linear error / CPU peak 0.00201997; RMS / peak 0.00009754; red halo energy difference 1.9663%.
- Lamp at left image edge: maximum / peak 0.00192048; RMS / peak 0.00007839; red halo energy difference 3.7870%.
- The hard peak-relative limit was 1% before measurement. Both tile seam checks passed 1e-5.
- Deep-shadow log10 maximum errors are 0.62247 and 0.69687 (99th percentile 0.22392 and 0.12006). The IIR/FIR tails are not pixel-identical; these values are reported rather than hidden behind a relaxed log threshold.

The official GPU-enabled JNI library compiled successfully. Eight combinations
(800T/500T × direct/print × boost 0/0.75) each exercised actual GPU preview,
repeat preview and full-size file export. Repeated preview buffers were exactly
identical. This initial probe used 256 × 128 and a single tile, with no
cache-hit assertion; it does not establish multi-tile or cache reuse success.
Exports had the expected dimensions, finite samples and no CPU fallback. Preview/export differences were measured separately: 800T maximum
4.40e-5 to 6.20e-5, versus 500T 3.35e-5 to 6.10e-5. These are different finish
boundaries, not a same-path tile seam assertion. An initial 2e-5 comparison was
an unverified harness assumption and failed; the characterization run did not
claim to pass that threshold. Core same-path seam limits were retained.

The complete GPU-enabled film-engine unit-test binary could not be produced:
its compiler was killed by the system in both 16- and 256-codegen-unit attempts.
Those full-harness unit tests are not reported as passed. Exact extracted
pure reservation functions and test bodies were subsequently tested separately,
as recorded below; that does not replace execution of the full harness. Actual production GPU library compilation and JNI execution replaced
the oversized harness for end-to-end checks. The new detail reservation test
is also available in the CPU-only harness. An earlier GNU link was killed;
the already installed Rust LLD with one thread linked the core test successfully.
No guard limit was raised, and all attempts were serial.

No physical Adreno test, Android APK rebuild or device-performance claim is
included in this change. Before release, test a real night/highlight photograph
at device preview and full-export sizes; the twelve fitted blur components
are more work than the old shared-width model.

Final source follow-through: the CPU-only `cinestill` filter passed 5/5 (including
detail kernel reservation and encoded reuse revision), the corrected 35 mm alias
smoke passed 1/1, and encoded reuse probe checks passed 3/3 (one overlapping).
The production GPU JNI was rebuilt and all eight preview/repeat/export cases
passed again, now also checking the new CPU/GPU cache revision marker. Initial implementation
uncompressed GPU JNI SHA-256 (superseded by the reviewed build below): `573bbf7f73fa653454bf598cc111773b2e42bce98762ee9a3d95e06a6951ca89`.

## Android state controls

The changed EditorState and SpektraLabProfilesHostTest compiled with Kotlin 2.0.21
against frozen code53 host classes. All 4 Robolectric tests passed, including
800T disabling legacy bounce/decay controls, preserving their saved values,
and re-enabling them on another stock. The same existing capability gate is
used by the actual UI. No Android resources or film-border paths were changed.

## Storage and completion safeguards

After the shared overlay fell below 2.25 GiB free, all heavy work was stopped
until the coordinated cleanup completed. The validation guard now checks that
floor as well as temporary-space and memory floors. Unpublished validation
libraries were gzip-archived only after full decompression SHA-256 verification;
the original content checksums and restoration command are retained. Published
artifacts and other workers' caches were not deleted by this task.


## Integration-review corrections

Independent review found two gaps after the first commit:

1. An existing AE ownership test assumed the resident approximation was the final
   export-ID suffix. Its test-only correction now strictly checks the AE-owned v2
   prefix, the exact complete resident ID, and the CineStill optical revision.
   Production IDs are unchanged by this correction.
2. A non-split export with retained global fields dropped the physical-kernel
   reserve when replacing prepass construction bytes with retained bytes.
   The full main pass now adds kernels for `Some(fields)`; `None` retains the
   already-inclusive construction allowance. Split prefixes still add kernels
   once, and suffixes never add them. Production-reachable 768 × 512 / 128 MiB
   and Some/None × split/full cases have regression tests.

These corrections were completed in `90f29132` (test identity only) and
`7e43e641` (production budget and new probes). No film/paper profile arrays or
bundled data changed. A fresh production GPU library was built from the clean
`7e43e641fbc20945af6b8504138e0e133ffbaf3e` source, then validated as follows.

### Reviewed final-source checks

All runs were serial under the original 1 GiB owned-process RSS limit,
768 MiB available-memory and temporary-space floors, and 2.25 GiB overlay
free-space floor. The optimized-production/performance profile was not used.

- Fresh GPU-enabled host JNI build passed, 136.8 seconds; sampled peak RSS
  894,070,784 bytes. Uncompressed library SHA-256:
  `6033b2bb6469bdb8e22c1fe29c30535ce43bfdc9c9cd08313007764353969840`.
- Shared math passed 2/2. A separate pure Rust harness using exact extracted
  production reservation functions, test bodies and identity constants passed
  5/5: the same two math tests plus two reservation tests and the strict
  three-component AE/resident/CineStill identity contract. This is not the
  complete film-engine harness, and does not execute its pipeline-factory test.
- Actual SwiftShader JNI: both print and direct routes at 768 × 512, 35 mm,
  preview budget 384 MiB, export budget 128 MiB, preview cache budget 64 MiB.
  Each export ran a global diffusion prepass and a non-split tiled chain with
  24 tiles, core 96 × 171, expanded 326 × 401. Reported budget was
  134,141,288 bytes, within 134,217,728 bytes. This includes the 12,000-byte
  physical kernels and 136 bytes retained field metadata, rather than losing
  the kernel reserve after the prepass.
- Actual preview-stage cache hits were asserted and output was exact. Changing
  contrast exercised FilmLog and corrected-density prefix hits with zero
  prefix work; a cold render with cache disabled matched exactly. Export
  disk-cache replay, corruption and cancellation remain NOT_RUN.
- Whole-preview versus tiled-export maximum difference was 4.46439e-5 for
  print and 6.19888e-5 for direct, within the 1e-4 bound fixed before this run.
  These compare distinct finishing boundaries, not same-path seams. The
  earlier core seam threshold remains 1e-5. Both routes used the GPU, with
  finite output and no CPU fallback. The JNI phase took 46.14 seconds,
  sampled peak RSS 404,434,944 bytes.
- Full `spektrafilm-core --lib --features precision-f64`, without its GPU
  backend feature: **99 passed, 2 failed, 0 ignored, 0 filtered; not all green**.
  The protected beta53 baseline had 96 passed and the same two failures:
  `pipeline::resident_contract_tests::film_log_range_validation_and_split_halos_preserve_contracts`
  (`suffix.is_some()` assertion), and
  `pipeline::resident_contract_tests::film_log_suffix_capture_fallback_and_validation_clear_stale_results`
  (first suffix `Option::unwrap(None)`). Names and assertion semantics match;
  no new failures. The caught poison-fixture panic is an expected passing test.
- Full `spektrafilm-model --lib --features precision-f64`: **12/12 passed**,
  no ignored or filtered tests.

Receipts are retained in the investigation's `validation/` directory:
`reviewed-native-artifact.json`, `reviewed-tiled-jni.log`,
`reviewed-tiled-summary.json`, `reviewed-small-contracts.log`,
`reviewed-core-cpu.log`, `reviewed-core-baseline-comparison.json`, and
`reviewed-model-cpu.log`. `GPU_VALIDATION_LEDGER.md` keeps prior failed attempts,
precise coverage boundaries and recovery commands for the unexecuted full GPU
harness. Per-render cache and tile JSON receipts are in
`gpu-tiled-results-reviewed/`.

The reviewed JNI library was gzip-archived with full decompression SHA-256
verification; the private temporary build targets were then removed. No ARM
build, Adreno/device test, APK rebuild or physical-film calibration is implied.
