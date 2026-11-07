# Restricted HEIF grid import candidate

Status: independent code54-based development branch, not a released package. The production constructor defaults to the existing route; host/instrumentation tests must opt into `gridEnabled = true`. Enablement requires the Android acceptance below. Host compilation/testing is tracked separately in the validation report. Android/OEM device decoding, measured peak PSS and complete original-size render/export acceptance remain required. Host fake-pixel tests are not device validation.

## User contract

Keep the original encoded file and its existing metadata path unchanged. Do not reduce output pixel dimensions, re-encode the source, substitute an upscaled preview, skip failed tiles, or loosen the current SDR/color restrictions. The importer produces the same full-size float32 RGB disk source consumed by existing preview, adjustment, recovery and export paths.

Smaller compressed-file bytes are not smaller decoded-pixel memory. The route below avoids a full-image platform Bitmap by decoding one independently coded image item at a time. It is not generic Android region decoding and does not claim that ImageDecoder target size/crop prevents a full HEVC working frame.

## Admission and fallback

The existing ordinary container validator and maximum source/decode pixel limits still run. A smaller estimate is available only for a validated primary `grid` whose direct, unique, ordered `dimg` children are independently coded `hvc1` images. It validates grid coverage, all equal coded tile dimensions, bounded file extents, real hvcC/VPS/SPS/PPS sizes, one-layer 8-bit 4:2:0 Main/Main Still, bounded DPB, and one independent IDR picture. It scans the complete bounded payload, without allocating a NAL-sized buffer. Full CABAC decoding remains the platform decoder's responsibility.

Current tile limits are one megapixel, 2048 pixels per edge, 8 MiB encoded payload, 128 KiB remux header, and 8 MiB accumulated remux headers. The estimate is 64 MiB plus the largest tile's 48 bytes per coded pixel, remux prefix and encoded bytes. The shared limiter still adds its normal 16 MiB charge, uses at most half of available memory after the OS threshold plus 64 MiB, and holds one lease across all serial tiles. Before every tile, current low-memory and headroom are checked again. The estimate is not an allocator cap or protection against arbitrary OEM codec allocations/OS process death.

A validated non-grid input retains the original route. Once the existing validator identifies the primary as grid, unknown structure, unsupported HEVC syntax, oversized tiles, inconsistent colors/geometry or failed validation are explicitly rejected. They do not fall back to whole-image decoding: the old estimate trusts declared ispe sizes, which are not proof of actual SPS dimensions or DPB. In particular, adding an unknown nonessential property must not bypass the stronger bitstream checks. There is no unbudgeted full-image retry after a tile fails, and no resolution fallback. Cancellation and I/O errors propagate instead of being disguised as unsupported syntax.

Explicitly unsupported by this smaller-budget route: non-grid full-frame images (unchanged ordinary path); nested grids; alpha/auxiliary or other dependency graphs; tile transforms; crop/mirror metadata; unknown properties; Main10/HDR/PQ/HLG; unverified extensions or non-IDR prediction. Odd effective grid width/height with nonzero grid rotation is also rejected in the opted-in grid route because the host prototype found unresolved pixel differences for those combinations. A full-file retry could expose unverified sibling/thumbnail bitstreams, so it is not a safe exception. No universal high-megapixel HEIF support is claimed.

## Color, geometry and source ownership

Each temporary view copies one original coded tile payload without re-encoding, its hvcC and validated properties. A tile must have the exact admitted canonical color descriptor, or inherit it from the primary grid. Conflicting descriptors are rejected. The remux view is re-inspected, and must retain the expected source profile, coded dimensions, no tile rotation and exact decode-pixel count. The existing Android backend continues to enforce source color, opaque ARGB8888 software output, sRGB conversion, no partial-image acceptance and the existing SDR-base policy. This does not add wide-gamut/HDR preservation.

Each tile is decoded completely. Right/bottom padding is cropped in encoded coordinates, then the primary grid rotation is applied exactly once while positioning the tile's float rows. One tile Bitmap, one oriented float tile and one integer pixel row are live at a time. Each decoder closes before the next tile begins. The destination remains original display width × original display height × 12 bytes. Source length/time and the selected payload SHA256 are checked against the plan, and the remux file is deleted on close/error.

The caller owns a new empty spool target. The full output geometry and disk space are checked; every tile must succeed and the total written coverage/length must match before completion. Errors, cancellation, memory pressure and storage failure remove the owned partial target. Existing targets are never overwritten. Temporary tile views use the already recognized private `heic-primary-*` orphan-cleanup family.

## Original-size export boundary

`HeicInputAdapter` continues to bind the unchanged original file and original display dimensions to this full-resolution linear source. Existing `FilmEngine.exportSpool` passes that source and its dimensions to the native file pipeline. The current native storage design uses bounded row groups/local planes rather than a full-image Bitmap; codecs JPEG export reads linear rows, uses baseline interleaved encoding, and disables progressive/optimized-Huffman paths that retain all coefficients. This candidate does not modify those algorithms or claim new end-to-end measurements.

Disk cost remains significant. A 6144 × 8192 source needs 603,979,776 bytes (576 MiB) for its float RGB source alone. Existing native filters and final encoding require additional output and multi-plane scratch/checkpoint space; use the actual export plan, not the import estimate, for admission. Reducing decoder RAM does not eliminate multi-gigabyte export disk requirements, long render time, GPU memory limits, or subsequent independent failures.

## Required acceptance before broad enablement

- Compile and run container/planner/HEVC/geometry/failure host suites, keeping mock and platform evidence separate.
- Run instrumentation against the existing whole-image backend on the original small SDR/P3/grid/thumbnail fixtures, including permitted crop/rotation combinations and byte-level normalized spool comparison.
- Measure Android Java/native/PSS peak on supported actual OEM/API combinations, using real large HEIF camera samples, plus cancellation/storage/memory-pressure checks.
- Complete original-resolution render and JPEG/TIFF export, verify output dimensions, colors, EXIF orientation, preview/export exposure agreement, queue recovery and partial-file cleanup.
- The user's earlier 50MP original is unavailable. Repeated-tile 50MP synthetic evidence can test bounded geometry, but cannot substitute for that original or a complex real-camera device measurement.

## Current validation snapshot (2026-11-06 UTC)

Five production Kotlin files compiled against the existing code53 host class/dependency baseline. Seven host test classes passed 125 tests, including 20 HEVC header/bitstream tests, 25 new grid planning/orchestration tests and 80 existing container/import/primary-remux/ICC regressions. The original two-argument/default-backend constructor descriptors were verified with javap. A 384 MiB-heap combined compile was killed by the host; the successful run split stages with 256 MiB heaps, with the largest observed compiler RSS 370,856 KiB. This is compiler memory, not photo-decoder memory.

The current strict candidate rejects the three available real iPhone/Vivo primary-view samples and the repeated-iPhone-tile synthetic 50MP input. Their hvcC summaries differ from the authored small fixtures, so compatibility is under investigation. Passing authored-fixture tests is not evidence that real high-pixel camera files are already fixed. The candidate remains opt-in and is not ready for enabling in a release.

Three Android instrumentation tests have been authored but not run: actual platform whole-image versus tile paths for SDR grid/thumbnail, tile-only ICC P3 and four cropped rotations. No connected adb entry is available in this environment. No APK, real-device PSS run or complete large original-resolution export was produced by this task.

## Narrow camera compatibility follow-up

After that initial snapshot, two metadata-only compatibility rules were added and independently tested. hvcC's temporal summary may be 0 (unknown) or 1, without requiring its summary nested flag; actual VPS/SPS and every NAL must still prove the same nested single layer. SPS may declare either zero RPS sets or exactly one explicitly empty index-zero set (zero negative and zero positive references). Multiple/nonempty/long-term reference sets remain rejected. Complete unique parameter sets, actual coded size, bit depth, chroma, DPB and IDR-only restrictions remain unchanged. Scaling lists and WPP are still unsupported.

The updated 127-test host suite passes. Both available Vivo primary views now pass the actual production planner: 3072×4080 and 4096×3072, each with 48 complete 512×512 coded tiles and Display P3 input. A container-only 6144×8192 input made from 192 separate items repeating one unchanged Vivo tile also passes, at 50,331,648 pixels. Its estimated working bytes are 79,748,844 (about 76.1 MiB), before the shared limiter's 16 MiB charge. This number is the formula result, not measured decoder RSS/PSS. The synthetic file is not a real 50MP capture and no production Android decode or large disk spool/export was run.

The iPhone sample and repeated-iPhone-tile synthetic input remain explicitly rejected because their SPS scaling lists and PPS WPP syntax are outside this narrow policy. Three Android instrumentation tests now also compile against cached AndroidX dependencies, but remain unexecuted. Device testing must check pixel/color equality and seams at real tile boundaries, along with memory and final export, before changing the default-disabled gate.
