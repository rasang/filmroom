# Finishing / reprint / GPU workspace / JPEG branch

Base: `dev` at `e89db876594845fe111a98646492d795e7e66274` (0.1.22.2-beta8).
No changes to the meaning of existing exposure, warmth, saturation, contrast,
film/paper profiles, diffusion, or output quality settings.

## 2 — independent finishing

Three new controls live in **成片微调 / Finishing**: exposure (-2…2 EV),
luminance contrast (0.5…1.5, pivot 0.18) and saturation (0…2). They operate on
linear RGB after the scientific film result and legacy output brightness, before
8-bit encoding. Contrast preserves chromatic ratios; saturation operates around
D65 linear-sRGB luminance. Wide-gamut exports are transformed to the same working
primaries and back. The defaults (0, 1, 1) are an exact bypass, including negative
and above-white samples. These controls are not RAW white balance or HDR output.

The common low-resolution native stage cache excludes finishing from its
scientific key. Finishing-only edits reuse the ungraded result when that cache is
admitted and valid. The refined/zoom/detail tiers use the same grade, but do NOT
claim the same cache hit. Geometry and imprint pixels stay part of pre-imaging
input identity; unrelated print/finishing controls no longer invalidate that
source identity. The native cache still checks exact source pixel bits.

Full-resolution export applies the grade in bounded row chunks to a new inode,
then atomically replaces the job-owned output. It never writes through a hard
link to an ungraded stage checkpoint. Thus resume/retry does not double-grade.
The extra temporary output is included in native disk planning. Post-composite
borders are composed after finishing; pre-imaging borders are part of the
scientific image. Preview/export follow that same ordering.

Editor JSON, frozen queue state, saved recipes and QR validation retain the new
fields. Missing fields import as identity. Active finishing requires native
`finishingAbi=1`, including the detail API: old JNI binaries must be rebuilt,
not silently used. Release APK/audio/native packaging rules are unchanged.

## 4 — adaptive post-grain reprint cache

The existing FilmLog and corrected-density caches remain. On a compatible
corrected-density cache hit, the second scientific slot can be promoted to
post-grain density. Later compatible print edits then skip density, DIR AND
grain. The first cold render keeps its original schedule.

Corrected-density and post-grain density are mutually exclusive in the second
slot. No passive/active per-pixel reservation is increased. Keys bind the actual
resolved upstream state, full grain parameters (including seed and optional
coordinate origin), layer curves, source bits, backend, shape and data revision.
Fresh calibrated suffix pipelines remain mandatory; dependencies are not guessed
from slider names. Omitted-film donor grain keeps the existing path. Any upstream
mismatch, cache pressure or failed optional split uses the original safe route.
Native metrics add `reprintDensityHit` and `reprintDensityRetained`.

## 7 — bounded, scoped resident output-buffer reuse

One exact-size/usage output buffer (maximum 32 MiB) can be reused between staged
preview calls or export tiles in the SAME admitted work scope. Buffers return
only after completed submissions and unmapping. Input direct-mapped upload stays
unchanged. The scope is thread-bound and drops on normal completion, cancellation
or unwinding, before its external memory reservation ends. There is no global
idle cache, no reuse of displayed Bitmaps, and no uncertain-completion retry.

This deliberately does not pool every allocation or promise cross-frame reuse.
A size/backend/usage mismatch discards the old slot. Trace counters expose hits,
misses and peak retained bytes for scoped experiments. The existing device
quarantine/completion guard is unchanged.

## 8 — exact JPEG transfer quantization

JPEG keeps the same encoder, quality, 4:4:4 sampling, ICC, row streaming and
metadata. `Sdr8Encoder` computes output-code boundaries using the original f32
transfer/rounding function, then uses a small coarse table and exact boundary
comparisons. Samples close to a boundary call the original function directly.
There is no coarse quantization of the input and no change to TIFF16.

Tests compare all 255 boundaries ±1024 float bit patterns for each output space,
a million random positive float patterns and a million ramp samples per space.
An ignored host microbenchmark compares 72 million component conversions and
reports a checksum. This is NOT an Android or end-to-end export benchmark.

## Validation

Run host Rust tests with the repository's locked dependencies and Rust 1.88+.
`codecs` requires the documented pinned libjxl host prefix:

```sh
cd native
cargo test --locked -p film-engine --features precision-f64 --lib finishing
cargo test --locked -p film-engine --features precision-f64 --lib preview_brightness_cache_tests
cargo test --locked -p film-engine --features precision-f64 --lib preview_stage_cache
cargo test --locked -p spektrafilm-core --features precision-f64 --lib preview_film_log_contract_tests
cargo test --locked -p spektrafilm-gpu --features precision-f64 --lib resident_reuse
FILMROOM_REQUIRE_GPU_SMOKE=1 cargo test --locked -p spektrafilm-core --features precision-f64,wgpu-backend --test gpu_reprint
cd ../codecs
cargo test --locked --lib
cargo test --locked --release --lib encode8::tests::benchmark_rgb24mp_quantization -- --ignored --nocapture
```

Android host tests include `FinishingStateHostTest` and the independent reflective
`SavedPresetCoverageHostTest`. CI-only unit tests may exclude the private licensed
audio verification task; they do not package an APK and do not validate audio.
A real release still requires approved audio and rebuilt Android native libraries.

Host/software-Vulkan validation does not establish phone latency, thermal
behaviour, memory-pressure behaviour or driver compatibility. Same-device tests
must separate cold render, warm print edits, finishing edits and repeat export;
compare with identical grain/diffusion/size settings. No end-to-end speedup is
claimed from the pixel-conversion microbenchmark.
