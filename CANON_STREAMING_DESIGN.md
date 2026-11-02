# Canon bounded RGB streaming candidate — storage gate passed, not release-ready

Baseline: phase2 2c13da758275b722260e98dd95a276ce9d623228, copied using git archive. Independent tree; no owner tree or target changed. No publishing authorized.

## Pixel identity

- Vendor Canon 422 MCU is Y1,Y2,Cb,Cr; Canon 420 is Y1,Y2,Y3,Y4,Cb,Cr (not Sony's predictor).
- First MCU prediction is 1 << (precision-point_transform-1). First MCU of subsequent MCU rows uses previous MCU row's first Y,Cb,Cr; interior MCUs use last chained Y,Cb,Cr. Casts to u16 at the same boundaries as original. Thus two current rows and three first-MCU values suffice.
- Keep full existing entropy validation before padded BitPumpJPEG. Predictor !=1 for subsampling, nonzero transform, restart controls, invalid Huffman codes, missing EOI, truncated entropy remain rejected. No restart support claimed.
- Stage1 deliberately retained original three-row interpolation to prove storage exactness. Stage2 fixes its confirmed420 coordinate bug using independently checked LibRaw vertical-then-horizontal rounding; stream reads3 rows and converts/writes only2, leaving the next even anchor untouched.422, WB, firmware, model, invert_sraw_wb/sraw_new hints, integer RGB algebra and clamping remain unchanged.
- Metadata remains decode_dummy result, including normal WB, black=0, file white corrected x4, crop/orientation/matrix. Output strip normalization and develop/emit are unchanged.

## Storage mapping proof

Let W = final row sample count (3*pixel width), H = final rows, L = expanded JPEG row sample count, f_i = final slice sample widths, a_i = sum of widths before i.

422 destination->source: s = a_i*H + r*f_i + local.
Inverse: find i whose [a_i*H, a_(i+1)*H) contains s; q=s-a_i*H; destination=(q/f_i)*W+a_i+q%f_i.

420 destination->source: t=a_i*(H/2)+(r/2)*f_i+local; s=(t/L)*2L+t%L+(r%2)*L.
Inverse: t=(s/2L)*L+s%L; parity=(s/L)%2. Locate field in t using a_i*(H/2); q=t-a_i*(H/2); destination=(2*(q/f_i)+parity)*W+a_i+q%f_i.

Widths positive/summed exactly, equal 420 field widths (same restriction as phase2), H even, and decoded JPEG height even with WH=L*JPEGheight guarantee every index maps exactly once. No image-sized visited bitmap. Coalesce adjacent destination samples into pwrite runs; only slice-row/MCU-row boundaries split runs.

## Memory/disk/lifecycle

- Stage A input mmap + BASE32MiB + max(12*L,96*final_pixel_width) scratch. 12*L includes two MCU rows plus u16 write staging clone and byte staging. Three-row conversion <=54*final_pixel_width.
- Stage B input mapping dropped; BASE + active_width*(strip_rows+16)*96 covers same established develop scratch; max128 strip rows, min8. Raw spool read is one active-width row.
- Anonymous raw spool is exactly6*Wpixels*H bytes. Output exactly12*crop_pixels bytes. Required disk reports sum, not merely final output; <=1GiB. Output fallocate uses final size, not summed budget. No whole-image Vec.
- Temp file opened O_EXCL/O_NOFOLLOW/O_CLOEXEC in output private directory, immediately unlinked before fallocate. RAII owns FD across validation/decode/develop/cancellation/errors. Output existing-file guard and removal preserved.
- 52MP is physical pixels. JPEG expanded RGB samples may reach156M but non-subsampled sample limits stay52M. Neither pixel count nor128MiB memory ceiling is increased.

## Pending gates

Unit tests: MCU bytes/u16 vs original both samplings, cancellation callback, inverse maps vs phase2 unslice. Need real129/130/2085/2976 pre-RGB and final u16 comparison, full float output and seam comparison, RSS accounting, truncated/predictor/control regressions, cancellation and disk cleanup. Authorized storage-only heavy slot completed2026-11-01 16:58:36UTC; subsequent builds require a new slot. Existing full decoder remains golden reference, not a production fallback.

## Storage-only frozen gate (2026-11-01)

Cargo1.88 dev build and six tests passed. Four private CC0 fixtures129/130/2085/2976 compare every decoded JPEG u16 and every final RGB u16 against old full-image decoder exactly; tested key metadata (dimensions,cpp,black,white,WB,sourceIFD) matches.129/130 final float output SHA matches phase2.2085/2976 are both4320x2880=12.44MP,15bit422; these were budget-rejected before. They are NOT28MP fixtures.

Debug functional RSS (not release peak certification):12947.2MiB,13056.4MiB,208559.2MiB,297659.4MiB. Estimated budgets66.2/83.3/89.0/89.0MiB respectively.128MiB ceiling unchanged. Raw spool+output disk80,621,568 /181,398,528 /223,948,800 /223,948,800B. No named raw temp remains.

Local full evidence:results/storage/matrix.json (not committed with private fixture paths). Build/test logs:results/storage-build.log,storage-row-tests.log,storage-layout-tests.log. This proves storage equivalence, not inherited420 color correctness. Separate420 fix gate remains required. Generic regressions, release RSS, injected midstream cancellation and malformed input pipeline gates remain pending.

## Final second-stage result (2026-11-01 17:10UTC)

- Real130 3888x2592 fullYCbCr interpolation matches independent per-destination coordinate oracle for every sample. Three synthetic tests cover zero/singleton/odd/even sizes, observed anchors/Y invariance, affine/constant/extreme patterns and future-anchor preservation. Three MCU tests and three prior layout tests pass.
- Corrected full-image and streamed JPEG/RGB u16 results match exactly for129/130/2085/2976. All tested key metadata, now also active/crop/orientation/color matrices, agrees.129/2085/2976 float output hashes are unchanged.130 intentionally changes to43544b1de1d9af93c14a6798ff38fbddb6ef15a9da68ef811b70e7a4f4da30d5.
- Ordinary Bayer131 output remains c46fb88c1a10af499367c91a3968140f498463bd04422e24963d7cb19fe6eae4.17 real-fixture corruptions reject with no partial output; four real cancellations at0/150/2500/7000ms leave no output or named raw temp.
- Reviewer independently checked all four outputs, then full scanned corrected130: zero nonfinite values; sparse6-row phase statistics and bottle/leaf detail crops agree with the expected coordinate correction, without new structural seams. Visual evidence is supplemental, not the algorithm oracle.
- Actual camera/profile coverage:7D422+420 and5DS/5DSR422, including the invert_sraw_wb+sraw_new profiles. This is NOT proof of every camera/hint combination, restart support, absolute color accuracy, or complete.CR2 suffix coverage. The two5DS samples are12.44MP; genuine28MPmRAW remains a desirable larger fixture.
- Observed debug functional peak RSS47.3/56.3/59.2/59.5MiB; estimates66.2/83.3/89.0/89.0MiB, ceiling128MiB unchanged. Release/Android peak memory, final integrated regression suite and source/binary packaging remain required before release. No publication authorized.

Evidence files (local, excluded from source to keep private images private):
`results/chroma/matrix.json`, `results/chroma/bayer-131.json`,
`results/chroma-tests.log`, `results/chroma-real-oracle.log`,
`results/variant-faults/results.json`, `results/chroma/cancel-*.stdout`,
`results/chroma/independent-phase-sparse.json`,
`results/chroma/independent-crops.png`, `results/chroma/independent-130-final.json`.

The first second-stage attempt correctly failed an expected-output-change
assertion because Cargo reused an old registry-vendor rlib. It was invalidated
with `cargo clean -p rawler`; all above results are from the rebuilt artifact.
See `results/chroma-cache-retry.txt`. Never infer updated vendor code from a
bridge-only incremental rebuild.
