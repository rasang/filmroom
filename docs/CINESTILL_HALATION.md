# CineStill 800T back-reflection correction

## Problem and scope

The published 0.1.22-beta1/profile data was correct: CineStill inherits 500T's
measured arrays and overrides its identity, still-camera usage and no-backing
tag. All four copies (upstream, native data, Android source assets, signed APK)
matched. The missing part was the product-specific optical model, not a missing
profile file or disabled slider.

The old three-Gaussian baseline used one 65 um radius in all channels and
strengths 0.30/0.10/0.015. A 35 mm, 512-pixel synthetic lamp fixture reproduces
a very narrow warm border. SpektraLab's own CineStill model instead has a broad
red tail, a shorter/weaker green return, and separate source-energy headroom.

Only the exact `cinestill_800t` product ID opts in. Other old stocks and their
saved parameters keep their old numerical paths, even when tagged no-backing.
This change does not affect film-edge art, profile measurements or APK delivery.

## Source and model

Source commit: `JamesQiu2005/SpektraLab@02d82b56cf65f0f8e802a708612edc4e9a4790af`.
Relevant files: `engine/src/core/halation_psf.hpp`, `params.hpp`, `params.cpp`,
and `engine/src/pipeline/pipeline.cpp`. The new shared math helper credits the
source and preserves the fit's signed weights, per-channel sigmas and strengths.

- Base thickness: 120 um; four signed Gaussian components per sensitive layer.
- Return strengths: red 0.30, green 0.03, blue 0.003.
- Amount scales light and area; radius uses sqrt(amount). Size scales radius.
- Legacy user highlight boost runs first. Then 4 EV headroom is gated by both
  proximity to the full-frame maximum (-1 to -0.25 EV) and brightness above
  0.184 middle gray (+1.5 to +2.5 EV). One gain multiplies all three channels.
- Halation off disables headroom. Dark frames do not gain synthetic energy.
- CineStill uses the fixed four-component fit, not legacy bounce/decay knobs.
  Those two UI controls are unavailable for this stock, with saved values
  preserved for switching back. Amount, size, color and normalization remain active.

This is a fitted model with assumptions, not a newly measured film calibration
or a guarantee that a clipped JPEG recovers its original scene radiance.

## Execution paths and safety

CPU whole-frame, bounded streamed export and global detail preparation share
the same fit/headroom math. Streaming uses a whole-frame reduction over private
planes; it does not normalize each chunk independently. Existing checkpoints
include the full runtime parameters, so changed model state cannot reuse an old
scientific checkpoint. Preview pipeline identities likewise include the runtime. CPU encoded-payload
probe and GPU export/reuse algorithm IDs also carry `cinestill4g-v1`, so an
upgrade cannot reuse a pre-correction encoded output under identical UI options.

The wgpu resident path evaluates per-channel PSF components and uses a global
highlight maximum. Banded/tiled export, refined preview and detail prepasses
reuse that maximum. Their halo support includes the complete new-model FIR
support rather than inheriting the legacy 256-pixel cap. The kernel memory is
included in admission. Impossible kernels return a controlled fallback. CUDA
does not silently use the old model; it declines the new path for CPU fallback.
An early thumbnail without the required whole-frame maximum is withheld rather
than producing a differently normalized negative.

CPU uses its established FIR/IIR Gaussian operator; GPU uses a finite 3-sigma
FIR (round-half-up support below sigma 3, matching CPU FIR; ceiling support
for wider kernels). Low-energy tails can differ, so tests must report linear-exposure error and
halo energy as well as log-space differences. Whole/tile GPU consistency is a
separate requirement. This work does not establish Android-device performance.

## Validation targets

- Unit-mass PSF, amount/size scaling, dark/near-peak headroom gates.
- Exact product opt-in; old stocks and halation-off output stay unchanged.
- Valid 35 mm lamp fixture with measurable red return beyond the old narrow edge.
- CPU whole-frame versus streamed print/direct and global detail preparation,
  including legacy boost before headroom, cancellation and scratch cleanup.
- GPU shader validation, full-frame maximum contracts, support beyond the old
  cap, CPU/GPU numerical characterization, and GPU whole/tile seam checks.
- Host JNI before/after comparisons; physical Android testing remains separate.

Executed results belong in the validation report, not inferred from test source.
Before shipping a new APK, rebuild the native library and update the release's
engine/build identity. Do not reuse the already shipped code53 binary.

### Numerical non-negativity safeguard

The four signed Gaussian contributions are summed before clamping the completed halo to zero, before applying its strength and adding the source exposure. This safeguard is restricted to the new CineStill model; individual negative fit coefficients and source values are not clamped. A 513-pixel point-source test at 10 µm/pixel found a -3.87645e-6 red far-tail value with the existing CPU IIR Gaussian approximation. The guard prevents a nonphysical dark ring, with a small possible energy increase in nonuniform fields. Constant-field normalization is unchanged. GPU FIR receives the same final-halo guard for path consistency and rounding safety. This is not a claim of bit-identical upstream numerical behavior.
