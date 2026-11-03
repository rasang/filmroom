# RW2 v4 shuffle-boundary correction

The first prototype preserved the prior Rawler u16 and full RGB output exactly. An independent oracle showed this was insufficient: both implementations shared an inherited v4 bit-pump defect.

## Evidence before correction

Official rawpy0.28.0 / LibRaw0.22.2 (existing hash-pinned oracle, no new dependency install) differed from prior Rawler in4304 GX8 sensor samples and3091 GH4 samples.4264 and2968 respectively lay inside the active/default crop. These were not only masked sensor borders and could not be dismissed as color-renderer differences. `results/rawpy/diff-details.json` retains coordinates and integer values.

Existing approved official source `filmroom-canon-streaming-development/results/reference/libraw-decoders-dcraw-pinned.cpp`, `LibRaw::pana_data`, is verified against the saved LibRaw0.22.2 tree:

- Git blob SHA1: `d293c23d2096497a349a636e1a8568176312a106`
- File SHA256: `e17e3faac2e9d5de0d1c3155b5fe37165010a09eab9a80f63523a391237d1658`

LibRaw rotates each16KiB compressed block first, then reads the two-byte word. Rawler rotated only the low-byte address and used the following physical byte as the high byte. At the rotation seam this crosses into the next physical block instead of wrapping inside the same block. At the logical end of a block, LibRaw's high-byte lookahead remains zero.

## Correction

Compute the logical byte index once; independently map low and high bytes to the same block. Use zero only for the high byte beyond the logical block end. No extra source copy, whole RGB allocation, per-model condition, bit-depth substitution, or image resize is introduced.

A bounded7280-pixel GX8 trace reduced4 mismatches to0. Three direct tests include the actual production v4 source and check the rotation seam, logical end-of-block and ordinary words (`v4_boundary_tests.rs`). Final readiness requires the complete independent sensor/RGB matrix, not this small trace alone.

## Final oracle definition

For all admitted real fixtures, decode sensor integers using the existing official LibRaw oracle. Compare full sensor and active-crop integers against the application decoder. Feed those independent integers into the prior pinned Rawler metadata/full-frame development pipeline, and compare every output f32 bit against the application's bounded striped output. This isolates sensor decoding from the application and checks full-resolution development without treating LibRaw's different rendering pipeline as a bitexact color oracle.

The old pre-correction equality results are retained as `results/matrix-before-v4-correction.json`; they are historical evidence, not the final quality gate. S1R's71 original integer differences occurred exclusively outside the emitted crop. They were independently resolved by the v6 correction below; the final full-sensor gate requires exact equality for every sample, including masked margins.

## Companion v6 unsigned-underflow correction

Official LibRaw0.22.2 `decoders_libraw.cpp` git blob `96f36c87fa133ff6f7bb05c959cb22dff8172002` subtracts15 in the unsigned domain. Rawler used signed subtraction, so epixel0..14 incorrectly passed the signed range test and became large u16 values (e.g.65521). The one-line unsigned correction preserves the official zero branch; epixel>=15 behavior is unchanged. No camera-model conditional was added.

Four direct production-source tests cover12/14-bit zero packets and encoded first-sample0,14,15,16→0,0,0,1. All four failed before correction and passed after. Durable versions of both v4 and v6 tests are in `codecs/tests/rw2_v4_boundaries.rs` and `codecs/tests/rw2_v6_boundaries.rs`.
