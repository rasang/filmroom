# Separate second change: correct Canon 420 chroma coordinates

Applied as the second independent change after storage-only old-output u16 gate passed at commit629eed4edebce31ff7ad9bc9fac33c1e29dad701. See CANON_STREAMING_DESIGN.md for final evidence and remaining release conditions.

Independent local reference sources supplied by variants owner:
- RawSpeed reference-research/rawspeed-sraw.cpp interpolate_420_row: observed Cb/Cr at even/even; missing odd row/column values interpolated from surrounding MCUs; last MCU column and last MCU row duplicate neighboring chroma. RawSpeed averages all four contributors directly with floor and signs chroma before interpolation.
- LibRaw reference-research/decoders_dcraw.cpp canon_sraw_load_raw lines707-735: iterate every physical raster row; on odd rows first fill even-column chroma from upper/lower anchor with (+1)>>1, last row copy above. Then fill odd-column chroma with (+1)>>1, last column copy left.

Choose LibRaw's vertical-then-horizontal rounded integer sequence. Difference vs RawSpeed can be1LSB from signed/floor/direct-four-point rounding; explicitly intentional, not an optimization tolerance. This correction does not touch Y, WB, black/white, matrices, or any Bayer path. 422 interpolation remains byte-for-byte original.

Whole-image corrected oracle:
1. For odd rows, compute even-column chroma from adjacent even rows (last odd row copies previous even row). Even anchors are never changed.
2. For all rows, interpolate odd-column chroma from horizontal neighbors; copy previous at right edge.
3. Existing RGB integer formula, coefficient/hue and clamp unchanged.

Streaming sequence:
- After unslice spool contains untouched expanded YCbCr.
- Iterate even row bases0,2,4… . Read current even+odd pair and following even anchor (at most3 rows). Input anchor at base+2 remains UNMODIFIED in file.
- In local buffer, fill odd row from upper/lower even anchors first. Then horizontal interpolation for only emitted first2 rows.
- Convert only first2 rows with RGB formula helper that does not interpolate again. Write exactly first2 rows back into same spool.
- At last pair, duplicate current even chroma into odd row before horizontal interpolation. Never read transformed preceding rows as predictors.
- To support pathological small heights mathematically, final single even row only needs horizontal interpolation; SOF420 production admission still requires even physical height.

Needed tests:
- Width/height0,1,2,3,4,5,6 synthetic grids for pure helper, documented zero-dimension no-op/error; production layout validation remains stricter.
- Constant chroma, horizontal/vertical affine ramps (known bilinear weights), impulse/parity patterns; Y unchanged; even/even anchors unchanged; corners/edges exact.
- Independent reference function per pixel directly selects nearest even rows/columns and applies vertical rounding then horizontal rounding; no call to production helper.
- Full-image vs2-row output chunks exact for varied heights and synthetic data; last anchor overwrite detector.
- Canon130 real420 old/new texture/periodic-row difference QA and comparison to independent mathematical reference. Canon129/2085/2976 real422 outputs unchanged; Bayer131 ordinary regression unchanged.

Known wider inherited issue: original no-Cr2StripeWidths branch skipped YUV conversion. Current outer adapter requires the stripe tag, so no production expansion requested by this memory change. Keep explicit rather than asserting every theoretical CR2 variant works.

## Immutable external reference verification

LibRaw source fetched through official GitHub connector at commit
`2caab57e269cc7fce17c3189e4d4d7e3e5861875`, path
`src/decoders/decoders_dcraw.cpp`, SHA256
`e17e3faac2e9d5de0d1c3155b5fe37165010a09eab9a80f63523a391237d1658`.
It is byte-identical to the variants owner's local reference. Permalink:
https://github.com/LibRaw/LibRaw/blob/2caab57e269cc7fce17c3189e4d4d7e3e5861875/src/decoders/decoders_dcraw.cpp#L707-L735

RawSpeed's corroborating reference is pinned to
`c48d5a6227a1369b633b86bc6707cb0e11d2150b`, path
`src/librawspeed/interpolators/Cr2sRawInterpolator.cpp`.

No new runtime library dependency. New Rust interpolation is independently
structured from physical coordinates and keeps existing rawler LGPL-2.1
licensing. LibRaw reference permits LGPL-2.1 or CDDL-1.0; LGPL is the compatible
reference choice, not a claim of permissive-only provenance. No new license
block identified; existing RAW/GPL/LGPL distribution obligations still apply.
