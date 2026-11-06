# Bounded whole-photo refined preview

The optional ABI 1 entry points `nativeRenderRefinedBitmap` and
`nativeRenderRefinedLinearBitmap` accept at most 1536 on each axis. They retain
ordinary preview JSON and sRGB RGBA8 output. Existing preview caps, budget
checks, 512 quick frames, export/detail APIs and 33-cube calibration are unchanged.

The high-resolution route requires healthy GPU execution and, when enabled,
shared whole-photo AE. Admission or execution failure returns an error and leaves
the lower-resolution preview to the caller. There is no high-resolution CPU
fallback. Cancellation generations and process GPU quarantine are checked before
allocations, between batches, and before/after output commit.

## Live allocation accounting

Within the unchanged 128–512 MiB budget, `gpu_export::tile_plan` charges:

- 96 MiB fixed pipeline/driver allowance.
- 256 bytes per expanded tile pixel (both halo axes).
- Full global-field peak from `gpu_diffusion_working_bytes`, plus field metadata.
  Actual retained field capacities are checked before main rendering.
- 60 bytes per full-frame pixel. Native f32 input and output use 24 bytes.
  Preview GPU scanner blur and unsharp add 12 bytes each per expanded tile
  pixel, conservatively charged against the full frame. The remaining 12 bytes
  per full-frame pixel provide conversion/allocation lifecycle slack.
- Actual external source bitmap stride × height, or direct-buffer capacity,
  plus destination bitmap stride × height. These remain live during JNI and
  are charged in addition to the 60-byte reservation.

The preflight rejects impossible fixed/full-frame reservations before JNI copies
input. Main tile/global-field planning happens before field or tile allocation.
No full-frame f64 ImageBuf, film-log, density or finish-cache candidate is created.
A previous low-resolution stage-cache entry remains separately charged and is
never borrowed or republished. Prepared spectral assets also retain their existing
separate cache accounting.

The tiled method uses GPU gamut and ordinary preview post-scan order, unlike the
CPU-gamut export/detail method. BandContext enables phase-local GPU scratch
retirement without changing the shader order. Full-grid pitch, absolute grain
coordinates, composed halos, global highlight maximum and global diffusion fields
are maintained. No per-tile exposure measurement occurs.

## Verification targets

- `film-engine --lib mobile::refined::tests`: dimensions, old cap preservation,
  external-buffer/overflow accounting, budget/halo admission and AE guards.
- `spektrafilm-core --lib preview_tile_keeps_gpu_gamut_and_rejects_missing_ae_or_cancel`:
  verifies the GPU-gamut flag and pre-dispatch cancellation/shared-AE rejection.
- `spektrafilm-core --test gpu_rectangular refined_preview_tiles_match_ordinary_whole_gpu_preview`
  with `precision-f64,wgpu-backend` and `FILMROOM_REQUIRE_GPU_SMOKE=1`:
  compares ordinary same-grid whole preview to tiled and full-tile GPU gamut;
  square, landscape, portrait/direct and reversal/diffusion cases include scanner,
  highlight, DIR, shared AE and cancellation checks.

Source presence is not evidence these tests passed. Validation results must be
recorded separately for the exact checkout/build.
