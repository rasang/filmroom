# Adobe DNG SDK (vendored subset)

**This product includes DNG technology under license by Adobe.**

Filmroom decodes every DNG file with the official Adobe DNG SDK. This directory
contains the unmodified source subset needed for reading DNGs; nothing here is a
precompiled binary.

## Provenance

- Upstream: Adobe DNG SDK 1.7.1, build 2802 (2026-11-10)
  <https://helpx.adobe.com/camera-raw/digital-negative.html> →
  <https://download.adobe.com/pub/adobe/dng/dng_sdk_1_7_1_2802_20261110.zip>
- Upstream zip SHA-256:
  `e9bb04e956da3605d8ef4f821bdb979a250c22546b0332b7f81a93b11da7cceb`
- `sdk/` mirrors the paths below `dng_sdk_1_7_1/` in that zip. It was produced
  by `python3 vendor.py <zip>`, which verifies the upstream checksum, copies only
  the files listed in `vendor.py` and regenerates `SHA256SUMS`.

| Path | Contents | License |
|---|---|---|
| `sdk/LICENSE.txt` | Adobe DNG SDK License | — |
| `sdk/dng_sdk/source/` | complete DNG SDK source | Adobe DNG SDK License |
| `sdk/xmp/toolkit/` | XMPCore reader/serializer subset + public headers | Adobe (accompanying DNG SDK License; also published as BSD-3-Clause) |
| `sdk/xmp/toolkit/XMPCore/third-party/expat/public/lib/` | Expat 2.2.9 library sources | MIT |
| `sdk/xmp/toolkit/third-party/zuid/` | RSA MD5 implementation used by XMPCore | RSA MD5 notice |
| `sdk/libjpeg/` | IJG libjpeg 9c library sources | IJG |

The SDK's bundled libjxl copy, XMPFiles, zlib, sample files, PDFs and project
files are **not** vendored. JPEG XL tiles are decoded by the pinned libjxl 0.12.0
in `../libjxl/`; zlib (Deflate DNGs) comes from the platform (`libz`).

Verify the tree: `sha256sum --check SHA256SUMS` (run in this directory).

## Local changes

None to upstream files. All Filmroom-specific code lives outside `sdk/`:

- `codecs/cpp/dng_sdk/bridge.cpp` — C ABI bridge: parse → stage 1/2/3 →
  `dng_render` (linear sRGB, float, identity tone curve, no shadow ramp, profile
  tone curves removed) → oriented float32 RGB output; budget-aware allocator that
  spills large pixel buffers to pre-allocated, unlinked cache files; two-thread
  area tasks; cancellation via `dng_abort_sniffer`.
- `codecs/cpp/dng_sdk/jxl_encoder_unavailable.cpp` — fail-closed definitions of
  the libjxl *encoder* entry points referenced by the SDK's DNG writer, because
  Filmroom links the decoder-only libjxl. Reading never calls them.
- `codecs/build.rs` (`build_dng_sdk`) — compiles everything here with the target
  C++ toolchain (`HE_CXX`/`HE_AR`), defines `qDNGUseXMP=1 qDNGXMPFiles=0
  qDNGXMPDocOps=0 qDNGUseLibJPEG=1 qDNGThreadSafe=1 qDNGValidateTarget=0`, and
  generates two include shims (`jconfig.h` from `libjpeg/jconfig.txt`, and the
  `third-party/expat/lib/expat.h` path expected by the public XMP source).

## License conditions honoured

From the Adobe DNG SDK License (`sdk/LICENSE.txt`):

- Adobe copyright notices in the source are retained unchanged.
- The license text accompanies the source (`sdk/LICENSE.txt`) and the app
  (`app/src/main/assets/licenses/ADOBE_DNG_SDK_NOTICE.txt`, shown in the in-app
  licenses list).
- The notice "This product includes DNG technology under license by Adobe." is
  included in the source (this file, the bridge sources) and the documentation
  (`README.md`, `README.en.md`, the in-app notice).
- The DNG logo is not used.

`NOTICE.txt` collects the Adobe, XMP/MD5, Expat and IJG notices; the same text
ships in the APK.

## Security notes

The SDK is maintained by Adobe; build 2802 is the latest at vendoring time and
includes upstream security fixes. The Expat copy bundled with the XMP Toolkit
is 2.2.9 (2019). XMP packets inside DNG files are untrusted input; updating the
XML parser to a current Expat release is a recommended follow-up.

## Updating

1. Download the new SDK zip from Adobe, update `UPSTREAM_SHA256` in `vendor.py`.
2. `python3 vendor.py <zip>` and review `git diff --stat`.
3. Rebuild and run `cargo test` in `codecs/` plus the Android build.
