# Experimental Adreno density resource reuse

This is an opt-in optimization in the isolated v019 optimization branch. It does
not add a new rendering algorithm or broaden the devices/stages using GPU.
`gpuResourceReuse` defaults to false. The existing `useGpu` policy still governs
Adreno eligibility, startup CPU tolerance checks, measured transfer-inclusive
cost gate, and fallback. Other vendors, unsupported devices and host production
builds continue using CPU.

## What changes

The candidate reuses a fixed input/axis/curve/output/readback/uniform buffer set
and bind group for the existing density interpolation shader. Baseline uses the
original per-batch allocation route. Both paths use the same pipeline, shader,
input conversion, parameter bytes, dispatch dimensions and readback helper.
No shader arithmetic, f32/f64 policy, random generator, grain, FFT or spatial
algorithm changes are made.

On the first candidate dispatch and whenever input scalar count, f32 curve axis
or f32 curve values change, it executes both allocation routes on the actual
batch. Every output scalar must be finite and bit-identical, including signed
zero. A mismatch discards candidate output, returns the baseline GPU output,
and permanently disables reuse in that process. Existing sampled CPU numerical
validation still runs afterwards. This establishes equivalence to the shipped
GPU computation, NOT a new promise that the GPU equals the f64 CPU bit-for-bit.
Subsequent batches with the same size/table upload all inputs anew. No prior
image/output bytes are reused as content.

## Ownership, bounds, failures

A single existing bounded GPU worker owns all device resources. It processes
one request at a time, waits for full readback and unmaps before accepting the
next request. The fixed storage capacity is 32,768 RGB pixels and 4,096 curve
knots. There are no per-image persistent allocations or unbounded caches.

Fixed retained device-buffer payload: 1,245,200 bytes. Exact key data is at most
65,536 bytes. The caller must obtain a process-singleton 2 MiB passive native
memory admission lease before first enabling reuse and retain that lease for
the process lifetime, matching the worker cache. The existing operation GPU
allowance covers baseline transient buffers and driver-private overhead. The
native module exposes `GPU_RESOURCE_REUSE_RESERVE_BYTES`. If admission fails,
callers must disable the candidate flag. The reservation remains conservative
when unsupported hardware never allocates candidate buffers.

The original request timeout remains three seconds, including both verification
dispatches. Timeout, map, driver or CPU numerical failure falls back for the
whole interpolation call and disables the original shared GPU handle. A stuck
driver thread cannot be killed by this timeout, but no replacement workers or
parallel reuse submissions are created. Exact comparison failure alone keeps
the original GPU allocation route available.

## Diagnostics and measurement

Native diagnostics include `resourceReuseRequested`, `resourceReuseBatches`,
`resourceReuseExactChecks` and `resourceReuseReason`. Batch/check counts are
committed only when the entire interpolation call returns GPU output; a
subsequent failure does not count abandoned candidate output as successful.
General `gpuBatches`, actual adapter, backend and CPU fallback remain available.
A requested flag by itself does not mean this operation used GPU or reuse.

Compare the same image/settings using existing GPU baseline versus reuse, with
all unrelated optimization controls held fixed. First-use/table-change checks
run two GPU dispatches and are intentionally included in elapsed operation time.
Report cold and warm measurements separately, including transfer, synchronization
and readback. CPU reference comparisons must explicitly select CPU mode rather
than imply this experiment expands the existing numerical contract.

## Validation status

The real production key and exact-bit guard live in feature-independent
`adreno::reuse_contract`, so `--features precision-f64 --lib` tests them without
compiling host wgpu. Host-only tests cover exact-bit rejection, full-batch tail/channel differences,
key invalidation, fixed memory budget and default-off/no-false-usage diagnostics.
The ignored device test exercises baseline/candidate alternation, changed pixel
contents with unchanged keys, full/tiny/partial batches, shrinking/growing
readback ranges and changed tables. It requires a real Vulkan adapter.

No real Adreno device is available in this cloud. No GPU execution success or
speed improvement is claimed. Compile/test results will be appended after the
shared validation slot is available. The candidate stays disabled by default.

## opt2 calibration evidence (selection remains unchanged)

`gpu.densityCalibration` reports safe numeric startup evidence even when the
original gate rejects GPU. Independent numeric self-check state no longer has
to be inferred from the old aggregate `startupSelfTestPassed` field. Optional
`shaderF64Advertised` reports the wgpu adapter feature only; this implementation
requests no f64 feature and runs no f64 shader.

Original CPU/GPU medians use the existing 32,768-pixel,256-knot,3-sample fixture
and >15% elapsed saving criterion. If resource reuse is requested at FIRST
process initialization, the same worker also measures reuse as a diagnostic.
It checks the complete fixture against original-GPU output and reports maximum
absolute/RMS error and promoted-bit equality versus CPU. These error numbers
are for a synthetic density fixture, NOT a photograph, final RGB DeltaE, perceptual
quality result or proof that all future inputs are exact. Candidate timing is
warm conversion/upload/dispatch/readback/CPU-sample-check/output-conversion time.
First setup, baseline+CPU reference and full-equality/error checks are separately
included in `candidateValidationMs`; first real-image key verification still
runs and counts in the actual render duration. The additional per-sample full
GPU-output equality check is outside the warm timer.

Candidate medians/pass/error metrics NEVER override `originalGatePassed`.
Original gate failure still returns CPU even if the reuse candidate beats it.
This preserves existing CPU-versus-tolerant-GPU selection, pending the user's
quality/speed decision. Device/map/numerical failure still fails closed. The
optional measurement checks the existing1s calibration budget before each
candidate sample; any driver submission can still exceed that budget, while
the existing2s initializer deadline bounds the waiting caller. A complete set
of three samples is required before a candidate median is reported. Candidate
allocation remains covered by the process-singleton2MiB admission lease.

Initialization is process-cached, including failure. Enabling reuse AFTER first
initialization does not create another worker or rerun the calibration; the
report remains `not_requested_at_initialization` until process restart. This is
an honest missing-measurement state, not a measured rejection of reuse.

`candidateStatus` values are fixed:
- `not_requested_at_initialization`: reuse calibration was not requested then.
- `requested_not_measured`: requested but earlier setup/gates did not reach it.
- `budget_exhausted`: optional candidate could not finish within budget.
- `device_or_validation_failure`: candidate dispatch/validation failed closed.
- `exact_comparison_failed`: candidate differs from original GPU output; disabled.
- `measured_diagnostic_only`: all candidate samples complete; see pass/error fields.

Unknown booleans/numbers serialize as null, not false/zero. `samples` and
`candidateSamples` distinguish partial from complete measurement. Same-process
cached medians should not be counted as independent repeat benchmark samples.
No real Adreno result or speed claim is provided by host source/unit validation.
