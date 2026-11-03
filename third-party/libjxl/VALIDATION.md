# Dependency validation, 2026-11-03

Validated in the isolated Samsung JPEG XL development worktree. This record
covers the dependency only, not end-to-end DNG correctness or a release APK.

- All four archive SHA-256 checks passed.
- All 1,585 regular upstream source files and the upstream symlinks were compared
  with the pinned archives. They match; no codec source patches were applied.
- Host decoder-only static build passed with GCC 14.2.0, CMake 3.31.6 and Ninja
  1.11.1. `verify-build.sh host` executed successfully: version 12000, decoder
  allocation, thread runner, default CMS and NEED_MORE_INPUT behavior.
- Android `arm64-v8a` and `x86_64` static builds passed with NDK 27.2.12479018,
  Clang 18.0.3, API 26, PIC and flexible page-size support enabled.
- Both Android verification scripts linked the dependency into a shared library
  with `--no-undefined`; every PT_LOAD segment has `0x4000` alignment. Their only
  dynamic dependencies are `liblog.so`, `libm.so`, `libdl.so`, and `libc.so`.
  Neither needs a dynamic libjxl nor `libc++_shared.so`.
- `bash -n build.sh verify-build.sh` passed.
- The explicit shipping build target list does not build the encoder. A separate
  host-only full `jxl` target was additionally compiled for the private Adobe DNG
  SDK reference oracle; it is not copied into any shipping prefix.

Reproduction commands and link order are in `README.md`. Generated evidence is
under `.build/<target>/check/{program-headers.txt,dynamic.txt}`. Generated prefixes
are `.build/{host,arm64-v8a,x86_64}/prefix`.

Limits: Android libraries were cross-linked, not executed on a device in this
dependency check. The final Filmroom native library and APK still need their own
ELF/ZIP alignment, runtime, RAW sample equivalence, malformed-input and memory
admission gates. The dependency alone cannot enforce the DNG memory budget.
