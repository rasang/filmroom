# Performance source review snapshot

This independent branch is for code analysis. It does not update main or dev.

## Included

- CPU exact-computation optimizations, caching and native rendering changes.
- GPU stage implementations, WGSL shaders, JNI bindings, isolated Android helper services, memory admission, cancellation and CPU fallback logic through the opt7.1 source checkpoint.
- A frozen 2026-11-03 14:47 UTC work-in-progress patch: bounded grain submissions, software64 parameter prepass, preview progress and memory-budget changes.
- Source tests, synthetic protocol fixtures, dependency manifests and license notices.

## Validation boundary

The snapshot's ARM check passed. Host and final Android tests for the work-in-progress patch were pending at capture time; no physical-device success or speedup is asserted. Later comprehensive instrumentation is not included because it was still being edited. This branch is not a release or a claim that the preview issue is fixed.

## Excluded

User photos, private RAW fixtures, execution logs/reports, credentials, signing keys, build caches, APKs and prebuilt JNI binaries. Existing public assets and sanitized documentation are inherited from the stable public tree. The separately released Samsung JPEG XL work is not part of this performance branch.

## Suggested review entry points

- `native/crates/film-engine/src/research_grain/`, `preview_gpu.rs`, `preview_reuse.rs`
- `native/crates/film-engine/src/research_*`, `shaders/`, `science_jni.rs`
- `app/src/main/java/dev/filmroom/engine/`: routing, helper processes, budgets and lifetime handling
- `native/GRAIN_PREVIEW_BATCHING.md` and native numerical-domain contracts

Build native components from source using the checked-in scripts and dependency lockfiles. Do not use old prebuilt libraries with this snapshot.
