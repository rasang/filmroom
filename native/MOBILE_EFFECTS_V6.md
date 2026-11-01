# Mobile physical effects — 0.1.1 integration

Host verification is not Android runtime verification. All processing is local. Original-size export never silently resizes. Optional controls default to prior neutral behavior; historical JSON with zero halation migrates scatter to zero.

## Implemented categories

- Bundled catalog: 20 shooting profiles and 8 print profiles. Negative print/direct routes; reversal requires direct scan.
- Camera and print diffusion independently: Glimmerglass, Black Pro-Mist, Pro-Mist, Cinebloom, strength and spatial scale. Both use the original normalized exponential-mixture PSF, global reflected edges and exact disk FFT overlap-save. No Gaussian approximation, reduced photo, or shortened kernel.
- Metering: seven original methods, optional global automatic exposure before manual exposure.
- Film format/physical pitch; camera lens blur; separate scatter and halation amounts/scales, multibounce decay/normalization; highlight boost/range/protection.
- Halation color presets: stock, red, amber, neutral. Nonstock choices explicitly redistribute the stock's total RGB halo strength. Creative presets, not claims about another physical stock.
- DIR: total amount, same-layer and inter-layer inhibition, diffusion distance.
- Grain: amount/size, independent RGB particle-area multipliers, uniformity, sublayer mode/count, particle blur, dye-cloud blur and physical microstructure spread/strength.
- Film gamma and fitted paper morphology: global, fast/slow and RGB gamma, developer exhaustion. Existing contrast multiplies paper global gamma. All 8 bundled papers have fitted models.
- Print exposure/light, normalization and exposure compensation, spectral Y/M shifts, automatic database neutral CMY or explicit manual CMY, linear preflash exposure and preflash Y/M.
- Scanner: globally generated glare, lens blur, unsharp before clipping, white/black correction/endpoints for supported routes.
- Output: linear sRGB, Display P3 or Adobe RGB (1998); preview converts back to display sRGB. Wide-gamut output requires gamut compression explicitly off; encoded ICC/transfer handled by codec layer.
- Optional Adreno pointwise density interpolation only. Spatial processing remains CPU exact. Capability, numerical and performance gates fall back to CPU. Actual device execution/speed is not verified by host tests.

## Streaming implementation

All spatial stages use full-image disk planes and global boundaries, not independently processed tiles. Print diffusion is after spectral print exposure and before paper development. Scanning uses unclipped planes for blur/sharpen. Glare and grain microstructure use globally consistent random fields. Bounded row chunks and overlap-save buffers avoid full-frame RAM growth. Temporary disk and elapsed time can be substantial; preflight reserves conservative worst permitted diffusion kernel. Owned scratch/incomplete output removed after error or cooperative cancellation.

Floating-point FFT order differs from whole-frame FFT. Synthetic comparisons require max absolute f32 output difference <= 2e-6. Preview/export grain not promised pixel-for-pixel equal at different grids.

## Explicit remaining constraints

- No authentic black-and-white profile files in supplied archive; grayscale not presented as physical B&W.
- Enlarger lens blur absent from original Rust algorithm, not selectable no-op.
- Direct-negative endpoint correction rejected; print-only controls inactive on direct scan.
- Internal per-filter core/halo/bloom shapes, full DIR per-channel coupling matrices and every grain layer coefficient remain fixed original internals; no claim all research parameters exposed.
- Android/Find X8 Ultra runtime and Adreno speed require real-device evidence.

## Evidence

Mobile effects suite: 13 tests cover all diffusion families/stages, combined effects, scratch reservation, across-chunk/global reference agreement, actual FFT cancellation and input preservation. Core catalog/morphology/GPU tests maintained separately.

Initial actual 3072x4096 DNG-derived linear source, camera diffusion+filters+preflash+sharpen with baseline grain/DIR/halation: 60.494 s, 38,800 KiB Linux peak RSS, 2,062,224,384 bytes peak logical scratch. Cloud CPU measurement, not phone performance nor bound for every combination. Expanded large runs recorded separately.

Final expanded validation: real3072×4096 source with camera+print diffusion,AE,highlight,glare,blur,microstructure,gamma/color:131.14s,38,828KiB processRSS,2,868,903,936bytes scratch. Synthetic8192×6144 same combination:774.20s,38,880KiB RSS,6,643,777,536bytes scratch (<6,710,886,400reserved). Both all finite[0,1], exact output dimensions, source SHA unchanged, scratch removed. Shared-cloud concurrent workload, NOT phone performance. Full-radius print Cinebloom FFT is expensive (436.62s of50MP run); no fast-export promise. Final mobile effects15/15, restored-test synthetic4/4 strict bitwise (not Python oracle), current default strictbitwise1/1, routes10/10, finalGPU9/9 focused tests passed (realVulkan test ignored).
