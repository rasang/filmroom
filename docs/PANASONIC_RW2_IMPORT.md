> Follow-on: the newer CF2/v8 scope is documented in [PANASONIC_RW2_CF2_IMPORT.md](PANASONIC_RW2_CF2_IMPORT.md). Older v8 exclusions below describe the original ordinary-mode milestone; v7/12 and other stated gaps remain explicit.

# Panasonic RW2 independent import prototype

Baseline: clean Filmroom `1a05595fa4fda0a9676a0a38466ab727cc5ed6b6` (0.1.19-beta.1/code20). Only the independent `source/` checkout is edited. No Beta replacement, publishing, signing, or release is authorized.

## Integration shape

- Keep the existing `Kotlin NativeCodecBridge → RAW facade → camera_raw → format-neutral Development` flow. The Kotlin prefix recognizes the distinct `II 55 00` RW2 container; native make/container/metadata validate identity. Neither filename nor provider MIME selects a decoder. `RawFormat.RW2` preserves MIME/format identity in cached photo sources.
- `camera_raw/panasonic.rs` validates one sensor IFD, dimensions, bit depth, RawFormat and checked payload geometry. It does not switch on camera model. Pinned Rawler camera profiles continue supplying model-specific calibration as appropriate.
- Optional legacy Compression tag11 is checked when present; real GH5M2 omits it. RawFormat and payload geometry remain mandatory. Do not substitute a model-name exemption.
- Pinned Rawler borrows the encoded sensor bytes instead of copying/padding the whole remaining file. v4 maps both bytes of each word independently in its shuffled16KiB block, correcting an inherited rotation-seam defect against official LibRaw semantics; v6 preserves unsigned underflow to zero rather than wrapping low samples to large u16 values. Whole u16 sensor remains; linear RGB is generated in bounded halo strips and spooled to disk, never allocated whole in the import implementation.
- Bounded sensor-only RW2 decoder construction omits embedded JPEG tag0x2e, avoiding unnecessary preview copies and false metadata budget failures. A thread-local RAII scope requires both the existing bounded admission context and II55 content. Unbounded preview/test callers and unrelated RAW formats are unchanged. Other metadata limits are not raised.
- Generic integer probe and decode hold the same input descriptor and check its identity, preventing path replacement between these stages. Existing streaming adapters retain their own identities.

## Budgets and quality contract

Camera RAW stays capped at256MiB per operation under the existing global dynamic reservation. DNG/HEIC/export128MiB limits and output color development are unchanged. Admission charges source mapping + u16 sensor +32MiB baseline versus a separately computed sensor + development strip/halo peak. File spool bytes are charged as disk, not described as RAM. S1R admission estimate is about218.5MiB, not its smaller observed decode RSS; there is no256MiB edge gamble.

WB, CFA phase, black/white normalization, active/default crop, D65 matrix, orientation and linear-sRGB output contract remain the pinned Rawler pipeline. Runtime evidence must establish full sensor equality to the existing official LibRaw oracle and full RGB equality to LibRaw sensor integers passed through pinned full-frame Rawler development, not merely equality to the old decoder or thumbnail similarity. Such equality is not a camera-color accuracy certification or a phone performance claim.

## Explicit unsupported / unverified boundaries

- v8/CF2 (e.g. real GH6 and S5M2X fixtures) remains unsupported, without JPEG fallback. See `PANASONIC_RW2_V8_REVIEW.md`: unsafe overlapping parallel writes, unvalidated strip vector geometry and entropy metadata need a separate hardened decoder plan before admission.
- v7 with12-bit metadata remains unsupported because the pinned vendor decoder unconditionally extracts14 bits. No silent conversion.
- >52MP sensor domains/multishot remain outside the existing limit, with no resize fallback.
- Old `.RAW`, Leica-branded RW2, unknown camera profiles and untested camera modes are not promised by this Panasonic `.RW2` prototype. v3/v5-12 code paths have checked geometry but no real fixture coverage in the current matrix.

## Review and integration gate

Review the final patch with the fixture matrix, unit/JNI/Kotlin tests, corruption/cancel/storage cleanup evidence, full-output hashes, and ABI ELF checks. Preserve the exact vendor checksums and run `codecs/tools/ensure-vendor-fresh.py` before rebuilding every target; Cargo does not automatically invalidate vendored registry artifacts. Do not reuse a stale native library in the app.

The APK consumes `app/src/main/jniLibs`. This prototype deliberately leaves those tracked Beta native binaries unchanged. After source review and explicit integration authorization, rebuild both codec ABIs from the final vendor fingerprint, inspect `results/android-native/elf-summary.json`, and replace the corresponding `app/src/main/jniLibs/{arm64-v8a,x86_64}/libfilm_codecs.so` in the authorized integration checkout. Carry the Kotlin resource hint, RW2 enum and content adapter together with the matching native libraries. Never ship only the Kotlin half of this patch.

After review, integration into the joint checkout requires the parent/user's explicit next-step approval. Build/install/device validation and release are distinct approvals/results. This task does not authorize release.
