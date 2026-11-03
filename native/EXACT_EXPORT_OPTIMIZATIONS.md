# Opt-in exact export candidates

All candidates default **off**. Existing entry points retain baseline behavior. This is a test branch, not a claim of a measured speedup.

`MobileParams.exportOptimizationMask` is an unsigned integer accepting only bits 0–3 (0–15). It is request-scoped and excluded from serialized photographic parameters and checkpoint identity. The Rust equivalent is `streamed::ExportOptimizations` passed to `export_file_checkpointed_with_optimizations`; the mobile path uses its managed variant with the existing export permit.

| Bit | Candidate | Baseline |
| --- | --- | --- |
| 1 | RustCrypto SHA-256, ARM64 runtime SHA2 dispatch | Existing portable SHA-256 |
| 2 | Fused exponential Gaussian file IO | Three Gaussian files and ordered accumulation |
| 4 | Parallel independent grain layer interpolation | Serial identical pointwise interpolation |
| 8 | 256×256 FFT transpose copy tiles | 128×128 tiles |

The separate `gpuResourceReuse` flag is carried in the same Rust request configuration but is not part of this four-bit mask. GPU work and its parity gate are described separately. No environment variables, process-wide execution switch or thread-local policy are used.

## Exactness and bounds

- SHA selection changes hashing implementation only, not input bytes, hex encoding, copy buffers, fsync/rename ordering or manifest contents. Saved jobs can change hash implementation on reopening.
- Gaussian fusion retains each branch's f64/FIR/IIR arithmetic and original ordered `a + amp * value` accumulation. It holds three horizontal files, skips repeated source reads and final branch materialization, and remains within the existing 16 MiB explicit buffer bound. Performance is workload/storage dependent; it must not be silently enabled.
- Grain interpolation uses the existing capped export pool, preserves indexed output order, and falls back to serial if there is no managed permit, pool creation fails, CPU capacity is low or interactive work is active. RNG and particle sampling are unchanged.
- FFT selection changes byte-copy tile geometry only; transform order, FFT side, normalization and kernel remain unchanged. Candidate tile scratch is 1 MiB plus one 4 KiB row, included in operator metrics. Existing conservative export admission remains unchanged.
- Stage metrics report execution mask, GPU reuse request, checkpoint identity/open time, checkpoint commit time and resumed stage. Compare cold outputs only when `checkpointResumeStage` is zero; reopening stage 6 is recovery, not a cold-render speed measurement.

## Verification

New tests cover hash padding/buffer boundaries, checkpoint backend switching and interrupted recovery, fused exact f64 bits and error/cleanup edge cases, transpose partial tiles/cancellation, capped-pool interpolation fallback, parameter bounds/identity exclusion, per-bit and combined full synthetic managed export byte identity, final-stage resume and cancellation after stage 3 followed by baseline recovery.

The existing checkpoint crash/fsync fault tests remain authoritative for durability. Device speed, driver behavior and original-image parity require the intended Android device matrix; host equality alone does not establish either speed or device correctness.

PSF symmetry and single-channel DIR math are not enabled: the DIR research patch excludes the current Adreno build, and adding another model execution policy requires separate validation rather than silently changing baseline output.

## Private package scope

This candidate is validated and packaged for **arm64-v8a only**, using
`-PfilmroomAbi=arm64-v8a`. The inherited x86_64 bundled library is not a rebuilt
optimization candidate and must not be included in this private test APK. A future
x86_64 package requires its own matching native rebuild and validation. The
frozen 0.1.19 codec remains unchanged; the independent Samsung JXL line is not
included in this branch.
