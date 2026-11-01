# Complete local film support plan

Status: October 31, 2026. Internal engineering plan, not a shipped-capability claim.

## Confirmed scope and hard boundaries

The owner requested all omissions in the earlier audit and camera-editor-style controls. Latest GPU direction is Qualcomm Snapdragon / Adreno only, prioritizing Find X8 Ultra. All processing remains local, originals 12–50 MP must not silently downscale, and no emulator or user Mac testing. A compiled APK is not Android runtime validation. No user photo enters source packages.

First milestone already underway: compressed tiled DNG repair, responsive controls and actual-stage animation, camera diffusion, print yellow/magenta, preflash and scan sharpening. Defaults must reproduce prior neutral behavior; broad feature additions must retain history/undo/log serialization.

## Ownership and merge contract

Keep exactly one owner of native/crates/film-engine/src/mobile.rs and streamed.rs: integrate_film_effects. It accepts helper modules and reviewed patches from other workers. UI worker alone owns MainActivity, EditorState and Kotlin control serialization. Codec/build owner retains codecs/, JNI library assembly and final package. No parallel rewriting of these central files.

Suggested independent workers:

A. Profile and route worker: new profile_catalog.rs + catalog tests; core streamed finish / positive-film and direct-scan support patches after explicit handoff. Bundle catalog data only after parity tests. No edits to mobile.rs/streamed.rs without integrator agreement.
B. Streaming optics/metering worker: new streamed_metering.rs and helper functions in a separate module for camera blur/highlight boost, scan blur/glare and grain microstructure. Integrator inserts stages in original order. Exact full-frame semantics, bounded disk planes and cancellation.
C. Color export worker: output profile selection and native encoder metadata, linear-to-output transfer function consistency, Preview display conversion. Own codecs export only after DNG owner finishes it; no untagged wide-gamut files.
D. Qualcomm GPU worker: isolated adreno_backend.rs + capability report JNI additions coordinated with bridge owner; backend factory/counters rather than replacing CPU correctness reference. Details below.

## Coverage matrix

| Capability | Existing foundation | Required implementation and gate |
| --- | --- | --- |
| More negative/cinema/Push profiles | 16 negative shooting profiles, 6 papers, 2 motion print profiles in data | Single catalog of IDs/kind/default pairings; bundle all valid files; remove pilot allowlist through catalog; per-profile loading and render finite/color parity tests |
| Four reversal profiles | Positive curve branches exist in core | Film-type-aware density and grain behavior, direct-scan route, route-compatible calibration; current streamed exporter rejects positive films, so removing guard alone is forbidden |
| Direct film scan | prepare_streamed_finish already selects film profile and skips printing when scan_film | Expose route; validate positive/negative semantics and chromatic adaptation, bypass print-only controls; route parity with full CPU reference |
| Black-and-white | Core logic exists, no matching profile files in this archive | Blocked on verified compatible profile data; do not synthesize an authentic branded stock or pretend grayscale equals physical B&W |
| Automatic exposure | Seven metering methods in filming.rs | Global prepass before exposure/projection; exact average/weighted methods, bounded-memory median/percentile strategy; match original method results. Never meter each tile independently |
| Film format | Physical pitch formula already supported | Validated format choices/custom range, consistent preview/export physical pitch; expose without changing historical default 35 mm |
| Camera lens blur | Gaussian physical blur exists | Full-plane Gaussian after diffusion, before halation. Existing disk_spatial operator usable |
| Highlight boost/protection | Pointwise boost_highlights exists | Same order before diffusion; bounded point chunks, finite/range validation. Not lost-highlight recovery |
| Scatter/halation detailed | Global scatter/halation already in streamed path | Expose bounded existing parameters. Verify multi-bounce/tail and per-channel combinations, do not accidentally tie scatter enable to zero halation |
| Grain details | Streaming sublayer particle model already present | Expose color/size/uniformity/layer controls with bounds; implement currently-rejected microstructure as globally correlated field, coordinate seed consistency and no tile seams |
| DIR/color layer interaction | Streaming DIR model already present | Map same/inter-layer amounts and diffusion controls; preserve independent saturation compatibility; validate supported tail ranges |
| Density/development curves | Core film gamma + print morph present | Separate film/paper gamma and RGB/fast/slow controls; no fictitious development-time controls for profiles lacking time-family data |
| Print exposure/light | Core printing preparation uses these | Map validated illuminant IDs and exposure, compensation/normalization; fixed point LUT recalibration; route hides/disables when direct-scan |
| Print filter/preflash | Four-group milestone supports Y/M and strength | Add neutral CMY and preflash Y/M if complete advanced coverage desired; include meaningful neutral reference test |
| Diffusion families/stages | Exact camera FFT being implemented | Add Pro-Mist/Cinebloom after model validation; print-stage diffusion needs spatial print-raw intermediate and original operation order, not merely same camera toggle |
| Scan blur/sharpen | Pre-clip scan planes now exist for sharpen | Reuse global plane blur; separate blur then sharpening and final clipping in core order |
| Scan glare | Core stochastic glare has seeded spatial behavior | Bounded globally-consistent generation/blur then spectral scan; cover print-vs-film glare route and cancellation |
| Black/white endpoints | Prepared scanner/color-reference support exists | Map validated values with route capability constraints; no assertion it works for every negative direct-scan route |
| Output color/gamut | Core supports named spaces/gamut policies; JPEG/TIFF currently hardcoded sRGB ICC | End-to-end color descriptor: linear working RGB, transfer, ICC and file tags agree; implement sRGB first baseline then Display P3/Adobe RGB etc only with exact matrices/profile metadata; preview stays correctly displayed |
| Qualcomm GPU | WGPU backend exists, Android bridge explicitly uses CpuBackend | Adreno capability gate, real adapter/device creation, per-operation GPU execution evidence and CPU fallback; precision/tolerance checks, no CUDA. Device runtime unverified is explicit blocker to acceleration claims |

## GPU worker specification

1. Enable WGPU backend behind an Android-compatible Cargo feature, without activating desktop GUI or CUDA dependencies. Isolate dependency changes.
2. Probe actual Vulkan/WGPU adapter vendor/device/backend and supported storage/compute limits. Qualcomm/Adreno identification is a capability filter, not a guarantee that every Snapdragon chip works. Missing features or allocation/device loss use CPU.
3. Start with bounded preview and/or pointwise row-chunk spectral/LUT work. Keep spatial export on exact disk CPU path unless separately ported. Avoid full 50 MP GPU textures or buffers.
4. Add structured report: requested mode, actual backend, supported operations, fallback reason, device identity without private photo/path, elapsed timings. UI must display actual backend, never a permanently decorative GPU switch.
5. WGPU uses f32 paths whereas CPU export is f64. Existing GPU camera diffusion uses Gaussian approximation, not the CPU exact FFT. Do not silently choose this approximation for an export advertised as parity. Preserve exact CPU spatial path and document measured pointwise tolerances.
6. Tests: forced CPU/no adapter/non-Qualcomm/unsupported limits/device failure; CPU-vs-GPU fixtures on any available valid backend as algorithm test only, not Adreno proof. Cross-compile both Android ABIs if applicable. Actual Find X8 Ultra operation and speed remain unverified until real-device evidence, which cannot be replaced by cloud tests.

## Integrator contract

- Publish one versioned parameter/capability manifest used by Kotlin and native. Keep old fields compatible and defaults unchanged; range validation identical on both ends. Catalog does not expose files merely because they exist.
- A capability is selectable only when import/preview/export have a supported route. Otherwise show a clear unavailable reason, not a fake successful no-op.
- Each helper accepts cancellation and real progress callbacks. Its disk bound contributes to admission preflight, and failure leaves no owned temporary files/output.
- Freeze each module interface before main-file integration. Re-run prior tests after all changes, not just focused tests during development.

## Verification and delivery gates

1. Load every new profile and route, reject invalid combinations; reference vs streamed small fixtures including crossing row boundaries.
2. Every exposed parameter must change a deliberately constructed test image when non-neutral, while defaults remain unchanged.
3. Test extreme valid values, nonfinite/unknown JSON, cancellation during every added stage, disk rejection/cleanup and repeated operations.
4. Real 12 MP and 50 MP original-size execution for new worst-memory/worst-scratch spatial combinations, with recorded dimensions/RSS/scratch/time. No downsample workaround.
5. UI: 320 dp, large font, landscape, scrollable selector, one visible control panel, raw filename in information, no truncation, and cancel/error/repeated export modal lifecycle. No emulator means host layout evidence only.
6. Build, static checks, native ABI hashes/ZIP alignment/signature/permissions. Old signing key missing: cannot silently claim same-signature overwrite. Explain reinstall and private-cache/session data effects.
7. Complete exact supported matrix and remaining blockers. Black-white missing profiles and actual Adreno runtime are separate unresolved conditions, not completed by CPU build success.

## Implemented A handoff (2026-10-31)

- Bundled and explicitly catalogued 20 shooting / 8 print profiles; original Portra defaults preserved by central mapper. All default routes and all print media pass whole CPU vs streamed parity.
- Reversal direct routing, positive DIR and grain semantics integrated by central owner, tested for all four reversal profiles. Negative direct remains a negative, not an invented inversion.
- Prepared scan accepts globally seeded glare field before gamut; tests match original scanner bit-for-bit, including K75P cinema print viewing.
- Corrected prior silent daylight fallback for tungsten `T` and projector `K75P`. Added exact 162 aligned normalized samples independently executed from pinned Python function and official colour-science 0.4.6 data; no claim of independent full-image Python parity.
- Display P3 / Adobe RGB linear transforms, D65 whites, preview-to-sRGB helper and explicit selected-space scan implemented. Codec/ICC/TRC handoff is separate owner; non-sRGB gamut compressor remains unavailable rather than silently pretending success.
- All eight print profiles contain fitted models; every exposed paper curve control changes finite model curves. Nonneutral film gamma + combined paper morph passes CPU/streamed parity. Core original precedence requires paper gamma in morph.gamma_factor, not print_render.density_curve_gamma.
- Tests and integration notes: native/tests are in crates/film-engine/tests/profile_routes.rs; details native/PROFILE_ROUTE_SUPPORT.md. Android runtime, Adreno speed/accuracy and final full-resolution resource gates remain integration-owner work, not inferred from cloud CPU results.
