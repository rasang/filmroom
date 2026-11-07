# Export preview clarity candidate

Base: beta3 / code55, 54a2bcea4db9fb7645f2633501089bcbdee47e65. Branch: fix/export-preview-clarity. This is a source candidate, not an APK or a released build.

## Scope and provenance

The reported blur is in the export card, including its negative and finished-photo thumbnails. The user confirmed that the saved photograph itself is sharp. This change therefore leaves the saved photograph and scientific rendering unchanged.

The ten preview-related baseline files compared between beta2/code54 (6938599d1f55ebe398d38a4abb05c98c95b448e2) and beta3/code55 are byte-identical. The early 128px simulated negative was already in GPU.3/code45, commit 5e55b502bc5bc7a673e280b1c6ded041c289f0fb (2026-11-05 17:17:27 UTC). It was introduced with GPU negative availability/sequence work, not by beta3. The supplied code55 log only contains import/session/cleanup events and does not establish the export's actual selected negative file.

Baseline card sources were early negative 128px, exact negative 256px, scan positive up to 256px (substantially smaller for large-file restoration), and finished thumbnail 240px/JPEG88. The screenshot's large preview is about 984px wide. The display enlarged those small sources; the old preferred-negative name also held on to an early preview after an exact preview became available.

## Candidate behavior

- Finished-photo card: a maximum 1024px/JPEG92 display derivative, decoded from the already-committed saved photograph. No upscale of a smaller original. Explicit sRGB display conversion. Atomic private-thumbnail publication. The original bytes are not rewritten.
- Finished-thumbnail memory admission: 16MiB estimate; if not admitted, decode/write the old 240px derivative with a 4MiB estimate. Failure cannot replace an existing valid thumbnail or invalidate an already-saved photograph. These are admission estimates, not codec RSS caps.
- Existing history: when the stored 240px thumbnail is displayed, read a larger display derivative from its saved URI off the UI thread. A revoked URI, decode failure or budget rejection retains the old pixels. No saved-media or history-file rewrite is needed. Attempt/generation/lifecycle checks reject and recycle late results.
- Early negative: prefer 256px without expanding the existing 32MiB worker workspace or one-low-priority-worker policy. Expensive diffusion/large kernels retain the 128px fallback; unsupported settings can skip this optional image. Full-resolution physical effects are unchanged. Source reads are capped at 48MiB.
- Exact negative: prefer 384px using only spare memory in the original 256px GPU tile plans; otherwise retain 256px. The science tile geometry does not shrink to fit the preview. Both split plans must admit the upgrade. Cache-corruption replay uses the same capture edge.
- Backend capture contract: 384² × 4 = 589,824 samples; optional capture still checks the actual device's buffer and dispatch limits. The maximum backend GPU capture estimate is 16,515,088 bytes, separately covered by the assembly's conservative reservation.
- Replacement: a displayed negative sets a quality floor, rather than permanently pinning the early file. Invalid upgrades preserve the displayed image, accepted exact images never downgrade, and a later file does not restart the phase clock.
- Positive scan: target 768px, with dimension- and 128MiB read-budget-based reduction for extreme images. Capture stores only one f64 accumulation row and the RGB8 thumbnail; PNG encoding streams 65,535-byte blocks. CPU capture reuses already-materialized chunks without rereading the output. GPU/checkpoint restoration reads whole source rows, not individual pixels. The conservative bound, including the maximum-width source row, deflate block and 128KiB slack, is 2,464,511 bytes. This fits the original 3MiB native preview allowance, so the original native export admission and scientific FFT formulas remain unchanged. Android scan decoding no longer downsamples a valid 768px source back to 384px. Display composition remains cropped/framed using frozen job settings.
- All display animation durations and original/contour processing remain unchanged. Fade snapshots can retain up to 1024px instead of destroying detail at 480px.

## Validation

Kotlin: seven modified/new production source units compile against the verified code55 debug classes and recorded dependency classpath. This is a targeted source overlay, not a full standard Gradle build. Thirteen host-test classes execute against the candidate overlay: 94 passed, zero failures, one optional screenshot-fixture assumption skipped. The skipped test is not counted as a pass.

Coverage includes real 1024px decode and atomic JPEG publication, source-byte preservation, small-image no-upscale, low-memory 240px fallback, preservation on complete budget failure, 768px scan handoff, frozen crop/frame/orientation, corrupt-negative fallback, exact-quality upgrade without phase restart, no later downgrade, cancelled/non-cooperative decode, retry-token changes, backgrounding, history refresh, compact completion presentation, and motion/emphasis/contour regressions.

The final single native binary passes 39 default focused tests and two explicitly invoked GPU tests. The default invocation reports four ignored tests; two are then invoked explicitly, while the two manual fixture/benchmark tests are not counted as engine-test passes. The actual 512² software-Vulkan run captures a 384² exact negative (589,824 samples) and verifies saved output bytes and tile geometry are identical to its no-capture comparison. The scan GPU test exercises 768px output across negative-to-print, negative-to-scan and reversal-to-scan routes in sRGB, Display P3 and Adobe RGB. Its stage-6 checkpoint restore preserves both final RGB and thumbnail PNG bytes at brightness EV +0.5. A separate extracted, byte-bound backend capture contract test passes the new maximum and maximum-plus-one rejection. These do not constitute Android/Adreno testing.

An independent final-source probe also checks stored-deflate boundaries using CRC32, zlib/Adler and Pillow, checks pixels against a half-exposure oracle, and confirms cancellation before rename preserves the previous publication. No source photo or output spool is mutated by these preview checks.

## Comparisons

The three PNG comparisons are synthetic test charts, not reconstructed user photos and not generative images:

- final-thumbnail-before-after.png: the old ImageDecoder 240px + JPEG88 path versus the candidate ImageDecoder 1024px + JPEG92 path; both actual JPEGs decoded and displayed at the same 984px width.
- negative-native-before-after.png: actual native physical preview results at 128px and 256px, displayed at the same width.
- scan-native-before-after.png: actual native scan previews from a 6144 × 4096 synthetic linear output file, displayed at the same width.

The final comparison test's printed elapsed time includes fixture comparison encoding/writing and is not a decoder or export benchmark.

## Release limitations

No APK, version code, signing artifact, installed app, published package, or upstream branch is changed. No Android hardware timing, mobile peak RSS, device-specific HEIC decode-memory guarantee, or full app/native standard build claim is made. Preview work is optional and bounded, but extra resolution has nonzero decode/IO/encode cost. Larger display sources do not manufacture detail that the saved source lacks.

The scan-only final CPU suite has 16 passing tests and one explicitly ignored manual benchmark. The benchmark was run separately on real complete files. It includes chunk partition/missing/repeated input, independent non-neutral color-space/exposure oracles, full-frame accumulator equivalence, PNG stored-deflate block boundaries, cancellation, checkpoint restore and three scientific CPU routes.

The initial 32MiB scan-reservation implementation is superseded. It would have raised the whole-job low-memory admission threshold, so it was not accepted as the final design. The final streaming implementation restores the original threshold and is byte-compared against the previous 768px image oracle. The final same-binary native runs and source binding are recorded in `FINAL_SOURCE_BINDING.json`. Earlier SIGKILL build attempts, the stale-backend CPU harness failure and the pre-fix reused-output test-fixture failure remain in the evidence directory and are not represented as successful runs.

## Host measurements

Early negative, actual native pipeline on a deterministic 6144 × 8192 test spool: 96 × 128 to 192 × 256, about 58ms to 192ms, 18MiB to 36MiB of source-row reads. It is a single low-priority optional worker; these are host results, not Android UI/export timings.

Streaming scan, production publication code on full-size real files with hot filesystem cache (single runs; not a statistical performance claim):

| Input | Old restored source | Candidate source | Old/new time | Candidate maximum source reads |
|---|---:|---:|---:|---:|
| 6144 × 4096 | 113 × 75 | 768 × 512 | 3.8 / 63.0ms | 72MiB |
| 6144 × 8192 | 84 × 113 | 576 × 768 | 3.6 / 70.2ms | 108MiB |
| 8192 × 6144 | 85 × 63 | 768 × 576 | 2.6 / 76.3ms | 108MiB |
| 16000 × 1000 | 43 × 2 | 768 × 48 | 1.0 / 7.2ms | 17.6MiB |

Extreme sources still degrade within the fixed limits: e.g. 16000 × 12000 -> 466 × 349, 40000² -> 139². No maximum-detail promise is made for these cases.

## Evidence roots

- Kotlin compile: `filmroom-export-preview-clarity-validation/main-02/inputs.json` and `result.json`. Tests: `tests-05/` and `run-04/`; aggregate: `selected-summary.json`.
- Native negative/software Vulkan: `filmroom-preview-native-validation/FINAL_SOURCE_BINDING.json`, `final-default-tests-r2.log`, `final-gpu-sharper-capture-r2.log`, `final-gpu-scan-preview-r2.log`, `gpu-capture-contract.log` and `scan-independent-png-validation.json`; old failed-cap and superseded builds are retained separately.
- Final scan benchmark: `filmroom-preview-scan-streaming-benchmark/metrics.jsonl` and `source-receipt.json`. Only the ignored GPU test fixture changed afterward; the production-section binding is recorded in the final native receipt. The earlier `filmroom-preview-scan-benchmark/` is superseded.
- Comparison images: `filmroom-export-preview-clarity-validation/comparisons/`.
- Released beta3 signed APK remains SHA-256 `8bb238735159759d2dec09acd9b4dc345823c7e6ffe3319f62b5259e66f35918`, matching its existing signing receipt. Integration/beta3 remains at the base commit with a clean worktree.
