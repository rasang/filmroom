# Reviewed five-stock 135 source reproduction

- Status: PASS, first complete run, no failed generation attempt or retry.
- Read-only production source: `bc0f6ff1bc6ced4a1384337445460ebce31b4d41`.
- Prepared source bundle: `build/`, destined for `tools/reviewed135/build/`.
- Generator and independent verifier both exited 0.
- All 10 WebP files exactly match the approved production bytes and decoded RGBA.
- All 10 generated PNGs have identical decoded RGBA to their corresponding WebP.
- All five clean v2 intermediate PNG hashes match the historical geometry baseline; intermediates existed only in memory.
- Alpha, clear aperture, hole centers and zero-transparent-RGB checks passed.
- Production-target, existing-directory, relative-output and repository-ancestor output refusal tests passed without writing to those destinations.
- No production file, registration, native scientific input or integration-tree file changed; the repository remained clean.
- No JVM, Gradle, Cargo or GPU was started. All owned font-preparation/generation/verification processes exited; the temporary evidence remains intact for parent review.

## Environment and resource evidence

Python 3.12.14; Pillow 13.0.0; NumPy 2.3.5; fontTools 4.61.1; libwebp 1.6.0; FreeType 2.14.3. Encoding uses lossless=True, exact=True, quality=80, method=4.

The guarded wrapper set OPENBLAS_NUM_THREADS=1, OMP_NUM_THREADS=1 and MKL_NUM_THREADS=1. Generator elapsed time was 8.454 seconds. Process-reported peak RSS was 334,640 KiB (326.80 MiB); the 1-second resource guard sampled peak session RSS of 257,323,008 bytes. Both are below 2.4 GiB. Minimum sampled MemAvailable was 2,979,991,552 bytes; minimum overlay available was 2,753,908,736 bytes. No threshold was crossed.

See `RESOURCE_GUARD.json`, `REBUILD_QA.json`, `VERIFICATION.json` and `SAFETY_CHECKS.json` for exact evidence. `BUILD_FILES_SHA256.json` and `BUILD_FILES_SHA256SUMS.txt` enumerate every delivered source/font file. The locked expected-asset manifest includes all 10 production WebP and decoded RGBA hashes. Compression bytes are guaranteed only by the demonstrated recorded-environment comparison; cross-encoder mode separately checks pinned decoded RGBA.

The retained output directory is `/tmp/filmroom-reviewed135-rebuild-20261106-163258`, with exactly 10 approved WebP, 10 PNG and QA.json (2,731,975 bytes total). Do not remove it before parent confirmation. No output is a reference photograph.
