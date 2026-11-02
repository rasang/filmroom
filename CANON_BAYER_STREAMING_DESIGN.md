# Canon ordinary Bayer bounded streaming — independent gate passed

Base561398530e694af6e61b3d0cf1fbf043cb866e3e includes final Canon RGB/420 and ARW6 integration. Independent workspace only. Authorized serial host-debug gate completed2026-11-01 17:56:14UTC; no publication authorized.

## Real header facts

-1625 EOS6DMarkII:14bit,4components,SOF3192x2112 (expanded12768x2112),predictor1,PT0,Cr2StripeWidths(0,6384,6384). Double-line hint implies final6384x4224. A zero slice-count means retain flat decoded order, not field decomposition.
-983 EOS5DMarkIV:14bit,4components,SOF3440x2272 (expanded13760x2272),predictor1,PT0,slices(1,3440,3440),final6880x4544 after double-line reshape.
-131 EOS7D regression:14bit4components,SOF1340x3516,slices(2,1728,1904),predictor1/PT0.

## Semantics and bounds

Full-width lossless JPEG non-subsampled predictor1..7 needs current and previous u16 rows. First sample uses precision base; subsequent row-first samples use previous row-first; first-row interior uses left regardless predictor. All others retain original A/B/C predictor formula and u16 cast boundaries. Same HuffTable/BitPumpJPEG, preceded by unchanged strict full-entropy validator. NonzeroPT/restart/unadmittedcontrols remain rejected.

After camera's width/height reshape, slice inverse is source s: find field i with a_i*H<=s<a_(i+1)*H; q=s-a_i*H; destination=(q/f_i)*W+a_i+q%f_i. Positive widths sum exactlyW; checkedWH and cumulative sums guarantee bijection. Original JPEG raster row boundaries do not affect final mapping. Writes coalesce adjacent destination samples; no full Vec/visited image.

Linearization, if profiled, happens in original decoded sample order BEFORE unslice and carries original dither seed across row callbacks; separate row copy protects predictor history. For masked black fallback, metadata-only black placeholders cannot be trusted: sample the final FD in original area,row,column order, f32 accumulation and CFA phase exactly as RawImage::calc_black_levels, then construct the same2x2 BlackLevel. Profile/file black overrides retain original precedence.

Reuse authenticated anonymous FD lifecycle and RGB route; Bayer size2P rather than6P. Peak disk=2P+12cropP, capped existing1GiB. Input map dropped before same halo-strip normalization/RawDevelop/emit; black/white phase uses absolute sensor row/column. Existing row scratch max(12*expandedJPEGwidth,96*finalWidth) also bounds two predictor rows + optional linearization + write staging.128MiB ceiling/52MP unchanged. ARW6 code is not changed.

## Gates pending

- Synthetic row tests all7predictors x4component counts xwidth1/2/17 xheight1/2/9 x12/14bit: independent old whole-decode and encoder-input equality.
- Slice inverse literal old-loop comparison, uneven fields, reshape boundaries; malformed/overflow fields.
- Masked-black floating-order test and actual metadata equality passed; fallback helper covers overlapping areas and f32 rounding. Real large samples use their actual profile/file black precedence.
- Real1625/983 full integer oracle + streaming full float and RSS;131 normal hash and RGB/420 hashes must remain unchanged. Truncation/predictor/control rejection, cancellation/cleanup, budgets/no-overwrite.
- Host debug evidence is not release/Android memory certification; no all-suffix completion or publication claim.

## Verified gate

Rust1.88 dev build passed from an explicitly invalidated rawler package; initial
new target cold rebuild took68seconds. Eight focused tests passed:504 combinations
of predictor1..7/components1..4/width1,2,17/height1,2,9/12,14bits; explicit negative
odd predictor5/6 differences and u16wrap; slice inverse vs literal old copy;
masked-black accumulation vs original RawImage::calc_black_levels including
overlapping areas; dither seed/output exact across arbitrary row boundaries.
A ninth metadata-only test admits26.97/31.26/52MP without whole-image allocation,
keeps128MiB cap, requires exactly2P+12cropP disk, and rejects129MiB/insufficient disk.

Private real fixtures:

| ID | Sensor | Output crop | Debug production RSS | Estimated peak | Temp disk admission |
|---|---|---|---|---|---|
|1625|6384x4224|6240x4160|63120KiB (61.6MiB)|120147968B|365432832B|
|983|6880x4544|6720x4480|65596KiB (64.1MiB)|126783488B|423792640B|
|131|5360x3516|5184x3456|57728KiB|105466880B|252682368B|

All three Bayer fixtures compare every JPEG and final sensor u16 and tested
metadata (black,white,WB,matrix,orientation,crop,active,sourceIFD) exactly against
the original full-image rawler decoder.1625/983 additionally compare every final
float-output byte to the original full integer→normalized_tile→same halo
RawDevelop/emit path. The offline reference is deliberately high-memory and is
NOT a production fallback or evidence for production peak memory.

Final hashes:
-1625 dea9e3d2de34baa70cfe4a063080897f305ebfed4f1058a2f1f78498c41ad1ac
-983 b25f39cc0c59f71909abcae01aeb1bd31fc81c1fe4adc8ec3de387a627211202
-131 c46fb88c1a10af499367c91a3968140f498463bd04422e24963d7cb19fe6eae4 (old ordinary baseline unchanged, despite strip110→128)
-129 b92f0e90fb051d7079e88ea2027ba9cd4a9cb886ac38202c2195917db63f4fa2 (422 unchanged)
-13043544b1de1d9af93c14a6798ff38fbddb6ef15a9da68ef811b70e7a4f4da30d5 (corrected420 unchanged)

Twenty-two malformed/budget/existing-output cases pass: invalid predictor0/8,
nonzeroPT, injected DRI, truncated tail, missingEOI, excess/zero/inconsistent
slices for both large files, low-memory, forbidden129MiB, low-disk, pre-existing
output preserved. Geometry rejects during metadata admission before spool work.
Four timer cancellations plus cancellation triggered after observable developed
output pass; no named raw temp/partial output, and developed-stage FD count4→4.

Evidence:results/unit-tests.log,admission-test.log,bayer/matrix.json,
bayer-faults/results.json,bayer/cancel-*.stdout. Private source/derived images
remain outside version control. Source/test diffs pass git diff --check.

## Handoff conditions

Do not call this full.CR2 suffix or all-hint certification. Predictor/mapping
math is broader than the three real ordinary samples; linearization and masked
black fallback also have direct helper oracles, not every legacy-camera fixture.
Real52MP and release/Android RSS remain unmeasured. Input mapping is still charged
fully against admission, so unusually large encoded inputs can still exceed the
128MiB envelope. No quality reduction, JPEG preview, or budget increase is used.
Full integrated App/Android regression and source/binary packaging remain with
the joint owner. ARW6 implementation is unchanged; shared BayerFD normalization
may be consolidated later only with exact regression preservation.
