# V6 Android CPU + optional Adreno dependency source bundle

Contains exact installed sources for all 126 registry crates in the compiled Android `film-engine` normal/build dependency graph with `precision-f64,adreno-gpu`, including procedural macros. Both aarch64-linux-android and x86_64-linux-android resolve the same registry package set. Existing V5 source files are retained. A small number of additional already-installed lock packages is included.

This is **not a complete Cargo offline vendor tree**: the lockfile also resolves uncompiled optional CUDA/desktop/Web packages. No offline build guarantee is made. Do not configure this partial directory as a complete crates.io replacement; use the locked build recipe and registry access. The source manifest lists unavailable uncompiled packages explicitly.

Full source licenses and supplemental exact-commit license provenance are under `native/notices`. Existing V5 notices are preserved; GPU additions and current dependency coverage are in `GPU_V6_DEPENDENCY_NOTICE_MANIFEST.json` and `THIRD_PARTY_RUST_GPU_V6_NOTICES.txt`. Where a dual MIT/Apache package omitted both license files from its crate, the supplemental Apache-2.0 option is supplied from its exact upstream commit. Rust standard-library notices are separately included. Production source and Cargo.lock were not altered by this source-bundle operation.
