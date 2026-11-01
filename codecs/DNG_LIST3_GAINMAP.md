# Narrow DNG List3 gain-map support

This extends the existing bounded original-RAW decoder; embedded previews remain unused.

## Accepted new operations

- DNG opcode version1.3.0.0, mandatory flags0, List3 only.
- GainMap9: full encoded image rectangle, first plane0, exactly3 image/map planes, row/column pitch1. ActiveArea must equal the full encoded image. DefaultCrop and orientation still happen after development.
- At most4 supported maps and262,144 gain values total (1MiB values); grid dimensions/nonzero lengths checked before allocation. Gains must be finite and positive, at most64. Spacing is finite in[1e-12,1e12], origins finite with absolute value≤1e12. These are explicit bounded-support limits, not a claim that all other valid DNG maps are malformed.
- WarpRectilinear1:3 planes, exact identity polynomial(1,0,0,0,0,0) on each, finite normalized center. No tolerance-based acceptance of nonidentity distortions.
- Unknown or unsupported required operations still reject. Explicitly optional operations retain the prior skip-with-warning policy.

## Stage and coordinate contract

Black/white normalization and PPG demosaic precede List3; maps operate on camera RGB, before the pinned Rawler white balance / matrix calibration / highlight transform. BaselineExposure remains one later linear gain. No gain multiplication is applied to already converted output RGB.

Coordinate behavior was checked against Adobe SDK1.4 source hosted by AOSP:
https://android.googlesource.com/platform/external/dng_sdk/+/refs/heads/android14-prebuilt-test/source/dng_gain_map.cpp
Blob87d7e6f26ba3996eb1265339fe8d10406ac2d69f. The independently implemented interpolation uses normalized pixel centers(x+.5)/width,(y+.5)/height, edge replication, bilinear map values, and upper clipping to1 after each map. Operation order is preserved. This matches mathematical semantics; the SDK's incremental horizontal float arithmetic can differ by small roundoff from per-pixel evaluation, so no SDK bit-exact claim is made.

Rawler's existing no-map path remains unchanged. The new path splits development into demosaic and calibration without changing vendored Rawler. Full-image y coordinates are used even when processing strips/halos. Maps add their retained size to admission accounting; processing is in-place per RGB tile, with cancellation checked each row. No full-frame RGB allocation is added.

## Validation scope

Authored fixtures check an independent affine analytic interpolation oracle, edge replication, map order/clipping, identity warp, malformed/unsupported payload rejection, stage order under a non-diagonal matrix/non-unity white balance, exact one-time exposure, and explicit8/19/63/128-row stitching. All original synthetic successful RAW outputs are compared byte-for-byte against the production baseline; rejected variants keep error codes. The user's private input is used only in external validation and is not bundled.

This is not full Adobe camera-profile rendering. Existing warnings for omitted profile HueSatMap / tone curve remain. It is unrelated to HEIC/HDR auxiliary gain maps. Real phone execution and general geometric warp support are not claimed.
