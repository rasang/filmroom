# Performance source review snapshot

This branch preserves experimental performance code. It does not update main or dev and is not a validated release.

## 2026-11-05 code checkpoint

This update captures the current GPU development source changes relative to the previous public snapshot: resident GPU session ownership and Android service transport; JNI lifetime and cancellation handling; bounded preview scheduling and display; streaming stage routing and contracts; grain ALU programs; diagnostics; source tests and synthetic protocol fixtures. Existing public documentation and privacy exclusions are retained.

The algorithmic code_prefix.bin is source data consumed by the grain ALU interpreter, not a user image or prebuilt native library.

## Validation boundary

This is a source-preservation checkpoint. No new compilation, Android build, physical-device test or performance benchmark was run for this publication. Source review and whitespace checks are not runtime validation. The complete scientific-chain quality gate remains unresolved; no final-image quality pass, 50MP success, Adreno success or speedup is claimed.

Separate exposure64 and optics64 frozen experiments, including the newer point-GPU owner experiment, have not been integrated into this checkpoint. Their scoped evidence must not be attributed to this source tree. In particular, the point-GPU owner still requires runtime validation.

## Excluded

User photos and RAW fixtures, local execution logs and reports, credentials, signing keys, build caches, APKs and prebuilt JNI libraries. Existing public assets and sanitized documentation remain inherited from the prior public branch. No release, main-branch merge or deployment is implied.

## Review entry points

- native/crates/film-engine/src/gpu_session*, preview_gpu.rs, preview_display.rs
- native/crates/film-engine/src/research_streaming*, research_grain/, research_global/
- app/src/main/java/dev/filmroom/engine/GpuScience*, GpuStreamingProtocol.kt, PreviewDisplay.kt
- app/src/test/ and native inline tests for session and cross-language contracts

Build native components from source before packaging. Do not reuse old prebuilt libraries with this checkpoint.
