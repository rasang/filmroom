# Canon CR3, Olympus/OM ORF/ORI, Pentax PEF

Development line started on dev 9dea32489f23db8208ca1d1b1c38dbfb4d44543d and was fast-forwarded without conflicts to official 0.1.20.2, aff09aad5d1d9913b4f1d34a96adadcf07f65b79. The HEIF fix is retained.
This document describes implementation boundaries, not a camera/device certification.
The public sample matrix and recorded test results are the evidence of actual coverage.

## Identification and authorization

- The filename and provider MIME type never select a decoder.
- CR3 needs the Canon `crx ` BMFF brand and validated box/track/sample structure.
- ORF and ORI share Olympus TIFF signatures. An ORI extension is not an independent decoder or permission to use a preview.
- PEF uses TIFF structure and the decoder's actual sensor layout. DNGVersion always takes the generic DNG route, including Ricoh and Pentax cameras that can save either DNG or PEF.
- There is no added model whitelist. Camera color profiles remain required when the file does not carry a self-describing DNG calibration. Unknown profiles fail explicitly rather than inventing a color matrix.

## Sensor decoding

The adapters call an explicit bounded sensor API in the existing vendored rawler 0.7.2 dependency. They do not call the upstream full-image decoder or embedded-JPEG extraction in production.

- CR3: one unambiguous primary still RAW track. Lossless predictive RAW and wavelet C-RAW use sequential plane-row iterators. Four plane rows are converted to two CFA rows, preserving global tile position. Per-tile quantization buffers are released between tiles. Additional dual-pixel tracks are not substituted for the primary image, and Canon's proprietary focus/ghosting adjustments are not implemented.
- ORF: contiguous sensor strips with validated precision and the existing Olympus packed/unpacked/compressed encoding. Compressed decoding holds only two predictor rows. Large MakerNotes are inspected through a bounded signature and their required typed IFD fields, not copied as preview-bearing blobs.
- PEF: packed 12-bit, unpacked integer, and Pentax Huffman sensor rows. Huffman lookahead cannot consume bytes beyond the declared strip. Predictor ranges are validated before integer conversion.

CR3 crop metadata must be internally consistent. The verified R5 II cropped case has contradictory IAD1 active margins; only an exact independently typed CMT3 SensorInfo match for encoded dimensions and crop permits using the already-declared crop as active area. Unknown contradictions are rejected.

Quantization uses wide unsigned parameter arithmetic and checked signed coefficients; the final four-plane transform uses i64 before its specified clipping. The rawler package keeps arithmetic overflow checks enabled in release as well as debug, while codec-defined wrapping remains explicit. Unwind failures are caught by the RAW boundary.

Every accepted sample is written to an anonymous little-endian u16 sensor file. Segment coverage is checked. The existing camera developer subsequently reads the global active area with its normal halo, so tile boundaries are not independent demosaic boundaries. No full sensor or RGB image is held in memory.

## Bounds and lifecycle

- Existing 52,000,000 sensor-pixel limit remains. A marketed 50 MP mode can pass; 80 MP does not receive a special override.
- Existing app native memory ledger applies: 32 MiB metadata lease, up to 256 MiB camera decoding. Source mapping, explicit decoder scratch allowance, row state and development tiles are admitted before sensor decode.
- Sensor temporary bytes and final RGB bytes are included in the disk estimate, capped at 1 GiB, with actual anonymous file reservation.
- Source identity is checked before/after decoding; output is exclusive/no-follow and removed on failure. Cancellation is checked during sensor rows, CRX quantization rows, and development.
- Multi-sample bursts/movies, ambiguous raw tracks, multi-frame Pentax pixel-shift/HDR and non-Bayer monochrome are rejected until their own complete interpretation is implemented and validated. Taking only the first frame is not support.

## Capture metadata

Android content routing recognizes ORF/ORI and CR3 independently of HEIF. CR3 capture metadata is read from CMT1/CMT2/CMT4 through a separate bounded parser and the existing capture-field allowlist. No source geometry, offsets, thumbnail, MakerNote or opaque camera block is exported. Existing export code regenerates output geometry/orientation/color information.

## License and verification

No additional runtime library is introduced. The existing rawler dependency is LGPL-2.1; its CRX decoder retains its original LGPL notices, and BMFF parsing retains its MIT notices. Filmroom's existing GPL-3.0-or-later distribution and vendored-source obligations continue to apply.

Public originals are CC0 samples obtained from raw.pixls.us, stored outside Git. Source URLs, catalog descriptions and exact SHA256 values accompany the test matrix. Catalog labels are not evidence of actual bit depth or codec variant. Upstream support is not a successful Filmroom test. Host tests do not establish Android device performance or visual equivalence to a manufacturer's proprietary rendering.

## Reproducible tests

- `cargo test --offline --locked --no-fail-fast` runs generated fixtures and the existing public-fixture regressions without downloading new originals.
- `tests/public-fixtures/additional-raw-SOURCES.json` pins a compact set of CC0 originals by source and SHA256. Keep these outside Git; redundant models are not repeated as full-pixel tests.
- `additional_raw_audit --bounded` is an offline sensor oracle adapter, never an application fallback.
- `RealAdditionalRawCodecHostTest` opts into actual Linux JNI with `FILMROOM_HOST_JNI_DIR` and one `FILMROOM_CR3_SAMPLE`, `FILMROOM_ORF_SAMPLE`, `FILMROOM_PEF_SAMPLE`. It tests misleading filename, true decode, JPEG export, capture fields and regenerated orientation. This is not Android-device execution.
- `crx_integer_boundaries` checks extreme parameter products, signed overflow rejection and wide color conversion in both encodings.

At the final host checkpoint the complete native all-targets suite passed 182 tests (8 explicit legacy opt-in ignores). Release integer tests passed 2 tests and actual rawler compiler flags include overflow checks. Five CR3 representatives, including EOS R dual-pixel crop, exactly match 103,271,424 LibRaw sensor samples after the arithmetic hardening. Three legacy NEF/CR2/ARW representatives retain identical developed RGB hashes. Cancellation and sink failure stop at the second segment; twelve production cancellation/resource-rejection cases retain no partial outputs, named temporaries or leaked descriptors.

After integrating official 0.1.20.2, Android host tests report 1118 tests: 1060 passed, 58 explicit skips, no failures/errors; release lint passed. The separate opt-in Linux JNI import/export test also passes all three formats against the final release library. Neither Robolectric nor Linux JNI is Android device execution. Large-file Linux child-process peak RSS was 75,744 KiB (ORF 50 MP), 77,528 KiB (PEF 645D), and 55,016 KiB (cropped CR3), measured separately with getrusage; these are not Android memory measurements. The official NDK 27.2 ARM64 cross-build passes with rawler overflow checks enabled; its AArch64 ELF has four 16 KiB-aligned LOAD segments and only libdl/libm/libc dynamic dependencies. This is not APK packaging or Android-device validation.
