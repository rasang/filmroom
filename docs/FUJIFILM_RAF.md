# Fujifilm RAF candidate

This is a separate source candidate based on 6938599d (code 54). It is not a release or a claim that all RAF files work. The host checks below passed on this source candidate; Android packaging and device execution remain unverified.

## Contract

- Identify `FUJIFILMCCD-RAW ` from content, not the filename or provider MIME. Validate the RAF header's JPEG/EXIF, proprietary tag and FujiIFD ranges before invoking the camera database. Embedded JPEG pixels are never used as the source.
- Modern 2×2 Bayer or 6×6 RGB X-Trans sensors only. Require an on-file sensor size, crop, black levels and white balance, plus a supported offline D65 camera matrix.
- Uncompressed 12-bit little-endian packed, 14-bit words and the validated JPEG32 packing are separate layouts. Uncompressed rows are read on demand without a whole-sensor allocation. Compressed files must pass the Fuji lossless/lossy header and every declared block range. Compressed integers are retained within admission; full-size RGB is never retained.
- X-Trans uses its actual six-by-six pattern with sensor-global strip phase. Measured channels are preserved; missing channels use bounded, distance-weighted same-color interpolation. This conservative first developer can be softer and show more color aliasing than advanced X-Trans demosaicers. It must not be presented as Fuji camera JPEG rendering, manufacturer film simulations or independent color certification.
- White balance, per-position black normalization and D65 camera-matrix conversion produce linear sRGB. No sRGB transfer is applied before the film engine. On-file crop and orientation are applied once. Fixed camera-database active margins are not reused for a differently sized compressed sensor.
- No full preview, silent downsize, white-balance substitution, JPEG reconstruction or lossy-to-lossless relabeling. Unsupported/unknown models, legacy SuperCCD/EXR/dual layouts and unrecognized packing are rejected.
- Keep the existing 52,000,000 encoded-sensor-pixel, 32768-edge, 256 MiB camera working budget and 1 GiB private output budget. The tested GFX 50S file encodes 57,231,360 sensor samples despite a roughly 51 MP picture crop; it is rejected by the unchanged sensor limit. The GFX100 II fixture is also over limit. No GFX-wide support claim.
- Compressed RAF decoding checks cancellation between six-row entropy groups and cannot consume the padding after a declared block as image data. Failure/cancellation removes partial output; existing output and the original RAF are not overwritten.
- Android's metadata path continues reading EXIF from the original retained RAF. Output metadata follows the existing application policy, rather than copying a camera maker-note blob or preview.

## Public evidence

`codecs/tests/public-fixtures/fuji-SOURCES.json` records the catalog URL, CC0 license, size and verified SHA-256 of 12 originals. Pixels are not distributed in the repository. Coverage includes X-A1, X-A3, X-Pro1, X-T2, X-T20, X-T5 and X100VI, plus GFX 50S/GFX100 II size rejection and FinePix S3Pro legacy-layout rejection. A sample is evidence for its recorded mode only.

## Reproduction

Set `FILMROOM_RAF_FIXTURES` to the directory containing `<id>.RAF` originals and use the checked-in vendor source/checksums and Cargo.lock. Run native unit and fixture tests with one build job; do not reuse an old Rawler rlib after vendor changes. Set `FILM_JXL_PREFIX` to the existing host libjxl prefix required by the codec workspace. Android tests include `RawMetadataAdmissionTest`, `RawMetadataWarningHostTest` and `FujifilmRafMetadataHostTest`.

## Verification status

- Offline Rust 1.94.0 build completed with one Cargo job. The library run passed 109 tests, with 8 pre-existing external-format reference tests ignored. RAF fixtures were enabled; separate oracle invocations cover all nine positive samples.
- Full encoded-sensor `u16` arrays match an independent rawpy 0.28.0 / LibRaw 0.22.2 read exactly for every pixel in all nine positive originals. All seven X-Trans on-file CFA patterns also match LibRaw. This validates unpacking/decompression and CFA phase, not RGB color equivalence.
- All nine full-resolution float32 RGB developments completed, with the declared crop/orientation and only finite output values. Linear highlights above 1 are allowed and are not silently clipped. Representative Bayer portrait, X-T2 color chart, X-T5 architecture and X100VI portrait-orientation outputs received visual sanity checks; this is not color certification.
- The 52 MP sensor gate rejects GFX 50S (1435) and GFX100 II (7175) as `PIXEL_LIMIT`. FinePix S3Pro (1964) is rejected as `UNSUPPORTED_CAMERA_RAW_VARIANT`. No Bayer-compressed or 16-bit positive sample has been validated in this candidate.
- Unit/fixture tests cover duplicate and out-of-range directories, compressed header/block ranges, five malformed file variants, cancellation before and during decoding, memory/disk admission, existing-output protection and all-six-phase strip equality. Original SHA-256 and size remain unchanged for all 12 files after testing.
- Focused Kotlin 2.0.21 compilation passed for the changed metadata/header classes and current PhotoMetadata. Robolectric API 35 passed 17/17 tests, including the nine RAF originals' make/model, capture date, exposure, ISO, focal length and metadata snapshot round trip. This used cached app dependencies, not a full aggregate Gradle build.
- Host decoding took 3.8–16.3 seconds per image with measured peak child RSS of 55.4–191.4 MiB. The application package used optimization level 2 while dependencies retained the development profile; the output spool was on tmpfs. These are bounds observed in this harness, not Android speed or memory guarantees. Admission remains at 256 MiB and predicted all samples within that budget.
- The first multi-image analysis process was terminated by the environment at its fifth image. The harness retained a prior mmap view. Switching the analysis to bounded row reads and cleaning only regenerable build scratch allowed the remaining cases to pass. A first Android test launch also failed before test execution because of its default read-only home; all 17 passed after using the existing writable test home. Neither interrupted attempt is counted as a pass.
- ARM cross-compilation, a complete app/Gradle build, device import/export, lifecycle pressure and final APK validation are still pending. No release or distribution claim is made.

Machine-readable results, dimensions, hashes, oracle versions and measured resource use are in `codecs/tests/public-fixtures/fuji-VALIDATION.json`.

### Positive fixture modes

| Catalog ID | Camera | Validated layout | Oriented output |
| --- | --- | --- | --- |
| 1214 | X-A1 | 12-bit packed Bayer | 4896 × 3264 |
| 1371 | X-A3 | 14-bit JPEG32 Bayer | 4000 × 6000 |
| 1191 | X-Pro1 | 12-bit packed X-Trans | 4896 × 3264 |
| 864 | X-T2 | 14-bit uncompressed X-Trans | 6000 × 4000 |
| 865 | X-T2 | 14-bit lossless X-Trans | 6000 × 4000 |
| 1178 | X-T20 | 14-bit lossless X-Trans | 6000 × 4000 |
| 6124 | X-T5 | 14-bit uncompressed X-Trans | 7728 × 5152 |
| 7301 | X100VI | 14-bit lossless X-Trans | 5152 × 7728 |
| 7302 | X100VI | 14-bit lossy X-Trans | 5152 × 7728 |

For an independent sensor check, write rawpy's `raw_image` as little-endian `u16` without crop, scaling or demosaic. Set `FILMROOM_RAF_ORACLE_ID` and `FILMROOM_RAF_ORACLE` to the corresponding ID and dump path, then run `camera_raw::fuji::tests::public_sensor_matches_independent_libraw`. The test compares every sample. The dump is temporary and is not a redistributed fixture.
