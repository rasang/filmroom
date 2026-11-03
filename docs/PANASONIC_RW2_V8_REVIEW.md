# RW2 final source and v8 / CF2 hardening review

## Final admitted-path review — 2026-11-02 03:02 UTC

**This section supersedes historical pending-v4/v6 notes below.** Final source contains the v4 independent logical-byte rotation mapping and v6 unsigned subtraction correction. Both are generic storage fixes matched to official LibRaw0.22.2, not per-model exceptions. Review inspected final source/diff and the runtime evidence produced by the implementation agent; the reviewer did not rerun builds or full decodes. No remaining confirmed blocker was found in the admitted paths within this reviewed scope.

Verified evidence:

- `results/final-tests.log`: **72 passed,0 failed,7 existing ignored** (45 library +14 DNG +6 lossless DNG +3 v4 boundary +4 v6 boundary tests). Ignored fixture-dependent tests are not counted as passed.
- `results/matrix.json`:9 real fixtures;7 admitted (GH4,GX8,S1,S1R,GH5S,GH5M2,S5) have exact **whole-sensor u16 equality to LibRaw0.22.2**, exact active sensor equality, finite whole-output RGB, zero differing RGB samples and max absolute difference0 against the stated hybrid oracle. GH6 and S5M2X v8 fixtures are explicitly rejected with `UNSUPPORTED_CAMERA_RAW_VARIANT` and no preview fallback.
- `results/rawpy/results.json`:7 supported samples each have `raw_u16_exact=true`,0 different samples,0 maximum integer difference. This closes both previously observed v4 active-image and v6 outside-crop discrepancies.
- `results/faults/results.json`:21 cases cover malformed dimensions/storage/type/orientation, truncation, content-not-extension routing, cancellation before/decode/emission, memory/disk admission, injected `posix_fallocate` ENOSPC, preview-last-IFD parsing, and exact orientation6 pixel rotation. Cancellation/resource-failure records retain equal before/after FD counts and no partial output/named temps.
- `results/three-bayer/real-regression/results.json`:23/23 existing NEF/CR2/ARW output hashes pass unchanged.
- Final production decode peak for the largest reviewed S1R case:182,024 KiB RSS (host process), below its conservative admission and256MiB request. This is not Android app PSS or phone validation.

Important evidence interpretation: `sensor_equal=false` for GH4/GX8/S1R compares against **old buggy Rawler**, not LibRaw (`run_matrix.py`, old-sensor hash comparison). The authoritative independent comparison is `sensor_equals_libraw=true`. Correcting an inherited decoding bug intentionally breaks old sensor-byte equivalence. RGB reference is **LibRaw sensor + pinned prior Rawler full-frame development**; it proves preservation of the selected development policy, not equivalence to LibRaw's color processing. Black-level/calibration metadata differs between libraries, so do not claim independent color certification.

Final resource/routing review: Kotlin recognizes only little-endian Panasonic85 in addition to existing TIFF42 for the bounded root-table resource hint. RW2 probes receive the32MiB shared metadata lease; default decodes use the same frozen256MiB value in JNI JSON and the global ledger. Existing DNG/HEIC/export128MiB policies are unchanged. Native content/profile validation remains authoritative. Missing legacy compression tag11 is permitted generically because a genuine GH5M2v6/12 fixture omits it; required RawFormat, bit depth, validated geometry, unique source IFD, payload extent and any present compression tag remain checked. The preview omission scope retains bounded+II55 gating, cursor restoration and all unrelated metadata limits.

**Explicit remaining limits:** v8/CF2 unsupported pending hardening below; v7 12-bit unsupported; sensor domains above52MP rejected; no all-model/all-mode coverage claim (including no real fixture proof for every admitted legacy variant); final Kotlin and Android ABI completion were not yet reviewed at this checkpoint; no device/runtime validation, no independent color-accuracy certification, and no release authorization. The host evidence is suitable for review of the independent prototype, not an automatic production release.

---

## Historical static review and hardening record


2026-11-02. Reviewed pinned `codecs/vendor/rawler-0.7.2/src/decoders/rw2/v8decompressor.rs` and the independent RW2 prototype. No v8 implementation or heavy tests performed by this review. v8 remains explicitly unsupported; this is not a release or phone-validation approval.

## Why not enable the existing decoder directly

- `decode_panasonic_v8`, lines 379–392, shares an entire mutable `PixU16` through `SharedPix2D::inner_mut()` with parallel strip tasks. Metadata does not prove disjoint rectangles, so overlapping strips can race; merely catching panics cannot make aliasing safe. The safest first implementation is sequential strips with an ordinary exclusive mutable image reference. A later parallel version must hand out genuinely disjoint slices, not overlapping whole-image references.
- Lines 374–385 divide by unvalidated `num_of_strips_v`, multiply two u16 strip counts before widening, and index strip arrays without cardinality checks. Existing summed-width assertion is not proof of exact, nonoverlapping sensor coverage.
- `CF2Params::new` trusts count-prefixed byte arrays and `ByteStream` reads. Require exact tag types, payload lengths, bounded counts and agreement among offsets, line offsets, bit sizes, widths and heights. Bound strip count independently of the general TIFF metadata budget.
- `HuffmanDecoder::new` assumes 17 entries, lengths suitable for shifts, and usable codewords. `State::new` and `calc_gamma` assume six gamma points/slopes; shift-down metadata is only asserted during decode. Validate all these before allocating pixels or entering the decode loop.
- `alloc_image_ok!` DOES early-return during dummy decoding. Consequently current dummy probing skips CF2 metadata validation, rather than proving that decode will be safe. A validated CF2 plan must be built before this early return and reused/checked for real decode.
- Reverse-bit bitstream exhaustion and invalid Huffman symbols currently panic. Each strip needs a bounded reader with declared bit-length accounting and explicit `Result` failures; zero/truncated data must not silently become sensor pixels. Preserve the format's required lookahead precisely rather than accepting arbitrary extra bytes.
- Odd/zero strip dimensions and offsets require rejection. The two-row / two-column reconstruction assumes positive even geometry and exact in-sensor rectangles.

## Feasible bounded design

A full-u16-sensor implementation need not rewrite the CF2 prediction algorithm. Validate an immutable `Cf2Plan`, borrow compressed slices from the existing source mapping, decode strips serially, and retain the existing striped RGB development. Charge full source mapping + full u16 sensor + validated metadata + one strip's line/Huffman/gamma working set + allocator/stack baseline. Compute peak separately from the development sensor + halo/tile phase. Never allocate a whole RGB image. If this exceeds 256 MiB, fail admission or introduce a separate integer spool; do not gamble on observed RSS below the edge.

The baseline remains 256 MiB per camera RAW operation, globally leased; DNG/HEIC/export policies do not change. CF2's gamma table is sensor decoding, not display gamma, so removing it would change output. Preserve current WB, CFA, black/white, crop and camera-matrix semantics, then compare independently against the pinned decoder / existing trusted oracle.

## Required evidence before admitting v8

1. Actual ordinary v8 16-bit fixture (GH6/G9M2), 14-bit fixture (S5M2X), and genuine 12-bit fixture (S1RM2 7723 has tag10=12; repository descriptions alone are unreliable). Include >40MP, multiple horizontal/vertical strips, orientation variants, and mode/profile gaps. Only supported <=52MP sensor domains are admissible.
2. Full sensor u16 byte-for-byte oracle and full linear RGB finite/hash/numeric comparison, with WB/crop/CFA/black/white/matrix recorded. A preview-only comparison is insufficient. Compare nontrivial gamma table and nonzero shift-down modes only when supported by real fixtures; otherwise fail closed explicitly.
3. Synthetic faults: zero/overflow strip count; unequal vectors; overlapping, missing, duplicate or out-of-bounds rectangles; odd sizes; offset/bit-count overflow; malformed gamma/Huffman lengths, codes and shift values; every boundary truncation and invalid-symbol case.
4. Cancel before allocation, mid-strip and during development; disk-full/output-exists failure; descriptor/temp cleanup; retry after cancellation. Avoid a full-image decode being an uninterruptible cancellation interval.
5. Isolated stage peak RSS/time and accounted peak for each representative sensor size; same code on intended 64-bit Android ABIs, with host correctness kept distinct from device validation.

## Current v3–v7 prototype review notes

- **Superseded by the independent-oracle finding below:** the initial v4 borrowing plus virtual zero high-byte lookahead preserved the historical Rawler source's EOF zero-padding behavior, but preserved an inherited rotation-boundary decoding bug. The storage plan requires complete shuffled 16 KiB blocks; truncation into a required block is rejected before decode. Only one lookahead byte is synthesized; the low byte must exist. Full-sensor equivalence remains a required runtime check.
- v5 must reject partial final pixel packets: pixel count divisible by 9 for 14-bit or 10 for 12-bit. Otherwise the upstream `chunks_exact_mut` silently leaves trailing pixels zero. Parent has added this validation and a regression test following review.
- v7 12-bit remains correctly excluded because this pinned decoder always extracts 14 bits per pixel despite its 12-bit packet-size branch.
- On supported 64-bit ABIs, inherited rawler TIFF budget charges payload before allocation, retains a sticky 4 MiB aggregate / 64-IFD failure, and dummy pixel macros do not allocate the sensor. RW2's borrowed payload removes the former whole compressed-input clone. The shared 32 MiB baseline and full-source charge are conservative but still require runtime stage verification.
- WB normalization, camera profile selection, CFA override, active/default crop, black/white level construction and D65 matrix are unchanged by this prototype. Existing generic normalization and development match the pinned Rawler domain; this is not an independent color-accuracy certification. Profile-derived white levels and any metadata quirks remain the pinned decoder's behavior.

No remaining confirmed P0/P1 defect was identified in the v3–v7 changes during this static pass. One initial zero-IFD crash suspicion was retracted after checking the parser's existing nonempty-IFD guard; the parent retained an additional first-offset check as defense in depth. Runtime tests were not reviewed here.

## Additional embedded-preview omission review (02:24 UTC)

Real S1R/GH5S/GH5M2 metadata exposed a large unused embedded JPEG exceeding the inherited per-entry copy budget. The new bounded+II55 `PanasonicPreviewScope` omits only tag0x002e during decoder construction, retains all other metadata limits, and restores thread-local state via RAII. Unbounded callers retain the original payload. This is sensor import, not preview fallback.

Review found an early-return cursor bug: omitted `Entry::parse` initially did not restore `pos+12`; if this was the final IFD entry the JPEG offset could be mistaken for the next-IFD pointer. Parent added `reader.goto(pos+12)?`; verified statically in the current file. Add last-entry, bounded/unbounded, non-RW2 and scope-restoration regressions. No remaining confirmed blocker in this narrow patch after that fix; runtime gates remain pending.


## Independent sensor-oracle findings superseding legacy equivalence (02:44 UTC)

### v4 rotation boundary: fixed in current prototype, full runtime gate pending

LibRaw comparison found thousands of active-image differences for GH4/GX8 despite exact equivalence to old Rawler. Existing official LibRaw 0.22.2 `src/decoders/decoders_dcraw.cpp` (`pana_data`) rotates a 16 KiB block first, then reads adjacent **logical** bytes. Rawler formerly rotated only the low-byte index then read the physically adjacent high byte. At the rotation seam that high byte incorrectly came from the next physical block; at logical block end it incorrectly came from inside the rotated block rather than the zero lookahead.

Current production code computes the logical index, maps low/high separately within the same block, and returns zero only for logical high index0x4000. Static verification agrees with the official algorithm. A reviewer bounded trace over7,280 GX8 pixels had4 differences with the old pump and0 with corrected mapping, including all four first reported pixels. Parent's three direct-production-source boundary tests cover rotation seam, logical block end (split and unsplit), and ordinary words. No full sensor/corrected-RGB result is asserted by this static review; parent is running that gate.

Source provenance: existing `results/reference/libraw-decoders-dcraw-pinned.cpp` in the Canon streaming checkout has SHA256 `e17e3faac2e9d5de0d1c3155b5fe37165010a09eab9a80f63523a391237d1658`; its git blob `d293c23d2096497a349a636e1a8568176312a106` matches the saved official LibRaw0.22.2 tree. No new external code was installed or executed.

### v6 signed-underflow discrepancy: diagnosed, no reviewer source edit

The retained S1R comparison has71 differences, all outside crop, Rawler values65521–65531 versus LibRaw0. Official LibRaw0.22.2 `panasonicC6_load_raw` uses unsigned `spix=epixel-15`; encoded values0–14 underflow unsigned and reach the branch yielding0. Rawler instead computes signed i32 `spix`; negative values pass `<=65535` and become65521–65535 after masking. This is an exact source-level explanation, not a camera-specific heuristic.

A minimal generic correction is unsigned wrapping subtraction `(epixel as u32).wrapping_sub(0xf)` with unsigned comparison; it leaves epixel>=15 behavior unchanged. Recommended tests:0,1,14,15,16, both12/14-bit output masks, then the full representative sensor oracle and unchanged cropped RGB. If deferred, explicitly retain this sensor-domain gap; the current fixture shows no cropped-image effect, not proof that other images cannot. Reviewer has not changed decoder code or run the full oracle.

The local official `LibRaw-0.22.2-decoders_libraw.cpp` SHA256 is `6563ff7b2fcfe451a29e8ff6606da5f957953b5443a3881e41192cd35f35e2b2`; git blob `96f36c87fa133ff6f7bb05c959cb22dff8172002` matches the saved0.22.2 tree.

## Final terminal checkpoint — 2026-11-02 03:08 UTC

Read-only reconciliation closes the earlier pending Kotlin/ABI review items:

- `results/kotlin-summary.json`:28 tests,0 failures,0 errors,0 skipped across RawMetadataAdmission, PhotoHeaders, RealRw2CodecHostTest, CameraRawMemoryBudget and PhotoInputAdapters. The2 real-RW2 tests exercise production Kotlin with real Linux JNI; these are host/Robolectric checks, not Android device execution.
- `results/jni.log`: `RW2_HOST_JNI_PASS`.
- `results/android-native/elf-summary.json`:arm64-v8a and x86_64 compiled codec artifacts each have all6 required JNI symbols, no added exports, and all LOAD alignments0x4000 (16 KiB). Dependencies are the expected system `libdl.so`, `libm.so`, `libc.so`. ELF verification establishes artifact compatibility properties, not runtime/phone behavior.
- Host and both Android vendor-cache stamps agree on final Rawler fingerprint `bd351b87496ce7e1c7143e7c28e5d2a037bb658040ca749ddc1f4a6bc6e1f1fa`. The stamps explicitly mean cache invalidation, not independent proof of build completion; the terminal tests and ELF results above supply the outcome evidence.
- Matrix legacy comparison key is now `sensor_equals_prior_rawler`; authoritative LibRaw comparisons remain true for all7 admitted fixtures. This removes the ambiguous old `sensor_equal` label without changing results.

**Final review status:** no remaining confirmed blocker found in the reviewed admitted paths. All reported host tests and compile/ELF checks are terminal successful. v8/CF2, v7 12-bit, >52MP sensor domains, untested models/modes, actual device validation and independent color certification remain explicit gaps. No publication, signing, integration into Beta, release authorization or universal Panasonic support is implied.
