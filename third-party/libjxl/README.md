# Official JPEG XL decoder dependency

Filmroom vendors the **unmodified official libjxl 0.12.0 release**, not a handwritten
JPEG XL decoder. The archives are source archives, not precompiled binaries.
Builds are offline: all required codec dependencies are included and checked
against `SHA256SUMS` before compilation. Build outputs and extracted sources are
ignored; the pinned archives, script, manifest, and licenses belong in source
control.

## Source pins and security decision

- libjxl: tag `v0.12.0`, commit
  `a7a9c787341cf703dede03c2009fa460cae5e5df`, released 2026-07-01.
  <https://github.com/libjxl/libjxl/releases/tag/v0.12.0>
  Archive: <https://github.com/libjxl/libjxl/archive/refs/tags/v0.12.0.tar.gz>
- Brotli: `028fb5a23661f123017c060daa546b55cf4bde29` (1.2.0).
  <https://github.com/google/brotli/archive/028fb5a23661f123017c060daa546b55cf4bde29.tar.gz>
- Highway: `457c891775a7397bdb0376bb1031e6e027af1c48` (1.2.0).
  <https://github.com/google/highway/archive/457c891775a7397bdb0376bb1031e6e027af1c48.tar.gz>
- skcms: `96d9171c94b937a1b5f0293de7309ac16311b722` (2025-09-16).
  <https://github.com/google/skcms/archive/96d9171c94b937a1b5f0293de7309ac16311b722.tar.gz>

Dependency pins match the official release's `deps.sh`. The upstream README
explicitly recommends upgrading to 0.12 for security fixes. Release notes list
core-decoder, container and buffer hardening not present in 0.11.2. Therefore
0.11.2 is only suitable for comparison with the preinstalled host library, not
the shipping dependency. No downstream codec or numerical patches are applied.
This is a maintained reference implementation, but upstream does not guarantee
that any release is free of bugs. Keep the version under security review.

## Build

Requirements: Bash, GNU coreutils, tar, CMake 3.16+, Ninja, and a C++17 compiler.
The verification script additionally needs Python 3 and readelf.
Android uses NDK r27c (27.2.12479018) or newer. The verified build uses CMake
3.31.6, Ninja 1.11.1, GCC 14.2 for host, and NDK r27c for Android. Default Android
API is 26, matching the existing native compiler target and below app minSdk 29.
The default parallelism is two compiler jobs.

```sh
./third-party/libjxl/build.sh host
ANDROID_NDK_HOME=/path/to/ndk/27.2.12479018 ./third-party/libjxl/build.sh arm64-v8a
ANDROID_NDK_HOME=/path/to/ndk/27.2.12479018 ./third-party/libjxl/build.sh x86_64
```

The final stdout line is the absolute install prefix. The default is
`third-party/libjxl/.build/<target>/prefix`, containing `include/`, `lib/`,
`licenses/`, and `build-info.txt`. Build diagnostics go to stderr. Optional
environment variables are `CMAKE`, `NINJA`, `LIBJXL_BUILD_ROOT`, `LIBJXL_JOBS`,
`ANDROID_NDK_HOME` (or `ANDROID_NDK_ROOT`), and `ANDROID_API`.

If CMake or Ninja is absent, they can be installed outside the source tree from
the official PyPI packages, without changing system packages:

```sh
python3 -m pip install --target /path/to/build-tools cmake==3.31.6 ninja==1.11.1.4
export CMAKE=/path/to/build-tools/cmake/data/bin/cmake
export NINJA=/path/to/build-tools/bin/ninja
```

Archives alone are approximately 14 MiB. The extracted source and each
decoder-only build require modest additional space; no upstream test corpus,
image tools, examples, viewers, Java wrappers or image-format plugins are built.
The encoder is excluded by the explicit target list, rather than relying on
link-time stripping of a complete encoder/decoder library. JPEG reconstruction
is disabled; normal JPEG XL codestream and container decoding remain enabled.
The bundled default CMS is built separately, with skcms, for API consumers that
explicitly request it. Raw DNG integration must not request a color conversion.

## Link contract

Use the generated `prefix/include` headers with `JXL_STATIC_DEFINE`,
`JXL_THREADS_STATIC_DEFINE`, and `JXL_CMS_STATIC_DEFINE` defined. Link these
archives in this order, or in a linker group:

```text
jxl_dec jxl_threads jxl_cms hwy brotlidec brotlicommon
```

Host also needs the compiler's C++ runtime, pthreads and libm. Android also needs
`log` and libm, with the exact target NDK C++ runtime linked statically by the
native bridge. Do not link a host `.so`, use a system libjxl, or add the generic
NDK runtime directory to the library search path (it also contains `libc.a`).

All objects are position-independent. Android uses
`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`. Static `.a` files have no ELF load
segments, so the final native `.so` must still be linked with
`-Wl,-z,max-page-size=16384` and checked with `readelf -lW`. Its `PT_LOAD` segment
alignment must be at least `0x4000`. `verify-build.sh` links a smoke shared library
and checks this, but cannot replace checking the final APK's own libraries and
ZIP alignment. No Android runtime execution is implied by cross-link success.

## Ownership, memory and validation

The dependency build does not change DNG semantics or memory policy. The caller
must preserve integer sample values and bit depth, reject unsupported channel or
frame layouts, supply checked buffer sizes, and use the custom memory manager
and an explicit resource budget. In particular, a pixel callback does **not**
guarantee low-memory decoding: official modular decode can allocate full image
planes internally before it can drop them. Measure the actual Samsung fixtures
and compare every decoded sample against an independent DNG SDK oracle.

`verify-build.sh host` executes a minimal C API smoke test. Android checks are
cross-link/ELF checks only. Pixel correctness, malformed-input handling, actual
Android execution and end-to-end DNG processing are separate integration gates.

## Licenses

`licenses/` contains the upstream BSD-3-Clause libjxl license and patent grant,
Brotli's MIT license, Highway's Apache-2.0/BSD-3-Clause licenses, and skcms's BSD-3-Clause
license. Complete source archives retain all upstream notices and file headers.
Ship the applicable notices with distributions that include these libraries.
