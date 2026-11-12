# GPU spatial export beta5 validation

Baseline: dev `72298199` (0.1.22.1-beta4). This is an experimental pre-release, not a verified device-performance claim.

## Changes

* Final output and restored checkpoint files acquire cleanup ownership only after exclusive creation; a collision cannot delete another writer's artifact.
* Exact sampled negative PNG encoding is synchronous within the export permit, reserved memory and cancellation lifetime. Early unsupported previews are not reported as rendering failures.
* Optional preview/cache reservations cannot worsen main RGB tile work. Staged physical capacity must not shrink for optional work.
* Physical PSF H/V computes the requested channel only, retaining the same f32 kernels, FIR support, reflection/clamp rules, signed component order and final clamp.
* Split physical prefixes now spool camera/scatter raw exposure, convolve scalar planes with single-axis support, and resume at raw-to-FilmLog. No second RGB decode or exposure is applied. FilmLog proof capture occurs after this final prefix stage.
* Directional dispatches have a 64-million-tap ceiling. This bounds a batch's arithmetic without changing the convolution kernel or increasing the existing five-second deadline. It is not proof that every mobile driver completes within the deadline.
* The existing conservative 272 B/expanded-pixel + 96 MiB joint budget remains in force. Fields, preview and kernels remain prepaid. The scalar backend separately checks 48 B/pixel plus kernel/upload costs. These are admission estimates, not measured RSS.
* New checkpoint/cache algorithm ID prevents reuse of old intermediate products.
* Logs carry core/expanded geometry, halo, work ratio and reservations. Timeout metrics are captured before panic/unwind, including poll count, longest poll/sleep, component/channel/radius/geometry. Unknown GPU completion still quarantines the backend; cancellation is not converted into CPU retry.

## Validation environment

Linux x86_64, Rust 1.88.0, precision-f64 + adreno-gpu. Actual Vulkan tests use Debian Mesa 25.0.7 llvmpipe (software Vulkan). This is not an Android handset, Mali/Immortalis/Adreno validation, or phone wall-clock comparison.

## Verified checks

* Single-channel vs original RGB GPU H/V: four input fixtures and twelve sigma cases, bit-for-bit. Packed scalar H/V also bit-for-bit. Signed component accumulation and poisoned unused channels pass.
* GPU crate unit tests: 22 passed, four device/resource tests not run by the default command. WGSL parse/validation passed.
* Directional spool: odd 31 x 23 image, forced small tiles, reflect and clamp regimes; actual Vulkan tiled output bit-for-bit equals whole-image axis output. Host tests cover signed accumulation, cancellation, invalid output, and preservation of foreign files.
* Geometric planning for 2000 x 3000, 3072 x 4096, 4000 x 6000 and 6000 x 8000 with the dispatch cap retains complete-axis support and an expanded/input ratio of 1.0 per physical direction. This is planning validation, NOT four completed megapixel exports.
* Negative-preview and related safety tests: 23 passed, one explicit manual test not run.
* 257 x 263 full GPU vs split staged GPU with CineStill and camera/paper diffusion: max absolute output difference 0; cancellation and collision cleanup passed. Further high-radius and cache checks are recorded in the release handoff, not assumed from this check.

## Remaining costs and coverage limits

* Physical scalar staging performs substantial logical file IO. When directional axes do not overlap, it reads 276 B/pixel and writes 192 B/pixel (24 MP: 6.624 GB read, 4.608 GB write). OS page-cache traffic and actual storage writes are not measured. Disk preflight reserves a conservative additional 52 B/pixel plus 64 MiB; subsequent IO failures remain terminal and owned intermediates are cleaned.
* The PrintLinear global prepass for **paper-side diffusion** still uses the previous chain. It can remain expensive; this patch does not claim to eliminate every repeated halo in all configurations.
* Large 6/12/24/48 MP final-pixel, peak memory, thermal, and total-time handset validation is outstanding. OPPO 24 MP and vivo 12 MP reported cases need device retesting, including Android encoding and gallery save.
* A baseline full host test attempt was interrupted at the long catalog test. Existing geometry/scratch expectations and several unrelated tests already failed on untouched beta4. Focused tests are not a full-suite pass; the release handoff lists exact final results.

## Final focused results

* High-radius CineStill test fixes physical pitch at `35000/6000` micrometres while rendering 257 x 263 pixels. Full-resident vs staged max absolute error: **0.0000012516975**. This is inside the existing `0.00002` whole-vs-split regression bound, which was not loosened. It is not a bit-identical full-pipeline claim (whole/global-field reduction and CPU/GPU scalar accumulation have different floating-point operation boundaries). Cancellation and foreign-file preservation also passed.
* Explicit FilmLog reuse integration passed: cold/hit, suffix changes, corrupt-cache replay and cancellation, across Portra print/direct and Tri-X print. This is a small-fixture correctness result, not a phone caching-speed claim.
* Final directional host tests: 4 passed. Explicit actual Vulkan directional spool test: 1 passed. Core endpoint domain/shape/nonfinite/cancellation test: 1 passed.
* Android focused Robolectric tests: 13 passed, zero skipped (GpuRuntimeInfo 7, GpuWorkProgress 6). Actual device logging remains untested.
* GPU export module run: 20 passed, 2 failed, 3 explicitly ignored. The two failures are unchanged baseline assertions `released_prepass_workspace_expands_device_main_tiles_without_cutting_halo` and `reported_step6_no_diffusion_geometry_explains_submit_count`; both also failed in the untouched beta4 baseline attempt. They encode previous geometry/count expectations and were not hidden or redefined as successful tests. The separately ignored FilmLog integration was then explicitly executed and passed.
* The existing mobile scratch-budget fixture was brought into agreement with the already-existing 152 B/pixel formula (not a new lower reservation); its focused test passes.
