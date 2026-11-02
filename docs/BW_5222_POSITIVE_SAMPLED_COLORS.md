# 5222 positive-display: sampled near-black surround, clearer gray lettering

The original display artwork uses approved near-black and gray design targets.

Runtime body target is **sRGB(4,4,4)** with barely visible original fine variation;
letter core target is a restrained vertical **sRGB112 (top) → 88 (bottom)** gradient, centered near100, intentionally raised for readability,
not claimed as an exact measured letter colour or physical film density. No cloudy
spray texture, pure-white type, luminous bevel, new barcode or invented KEYKODE.
Only the known fixed reference row remains: EASTMAN 5222 511 00106 23 173 2020.

## Route and geometry

Only new 5222 print-route POSITIVE_DISPLAY contracts change to v6 sampled material.
Direct negative-transmission stays byte-identical. This is a reference-informed
positive display frame, not a claim that every optical printing setup has this
exact border appearance. The white/black hole choice is independent and preserved.
The full baseline geometric alpha is byte-identical, keeping crop, zoom, orientation
and photo coverage unchanged. Narrow outer-cut/perforation material-side shading
is retained at only 1–2 sRGB levels on the black body, not transplanted as a bright
negative-style rim. No new aperture contour or hole contraction is introduced.

Old v4 positive-display payloads/contracts remain for frozen queues. Registry cache
keys contain stock and asset version. No shared compositor, scientific profile,
engine, native binary, UI layout or app version changes. 7266 and 2302 remain
unchanged. The independent 2302 frame-ownership fix is NOT included in this patch.

## Reproducibility and privacy

Run `python3 tools/bw-5222-positive/generate.py` (Pillow/NumPy). Generator reads only
original baseline geometry, uses sampled numeric targets and original type drawing;
no user/reference photograph is used as an asset texture or bundled. Native-size
RGBA roundtrip is lossless. The optional host review test uses a private user
screenshot only to show actual BorderPreview before/after with an identical photo
core; that screenshot is supplied by environment path and never committed.

The old `filmroom-5222-translucent-PENDING-ROUTE.patch` is SUPERSEDED and must not
be merged. Its candidate remains separately archived for history only.

## Previous flat-ink focused host result, 2026-11-02 08:39 UTC (original 47px glyph layout)

23 tests total: **21 passed / 2 optional external-corpus skipped / 0 failed**.
New DoubleXPositiveSampledHostTest: all six tests ran and passed, including the
private same-photo runtime fixture (not skipped). Existing BW geometry, TriX v5,
frozen queue and asset catalog gates passed. External log:
`/workspace/shared/5222-positive-sampled-final-tests.log`.

Verified: every geometry alpha byte; near-black body/gray letter bounds; no photo
coverage on rails; both hole mattes; both orientations/crop/zoom; every float-export
pixel versus bitmap preview; 480/3072 output-width perforation samples; neutral
frame color management in sRGB/Display-P3/AdobeRGB; old/new queue version retention;
direct-negative original hash; and identical same-photo core in before/after.
The final asset retains Beta3's original47-native-pixel glyph size, location,
strokes and short marks. Generator reconstructs the original Beta3 full RGBA
byte-exact before recoloring, proving the original font/layout. Foreground
bounds, neutral RGB and top/bottom ink-luminosity differences are also checked in host tests. No new text/code is added.

Actual `BorderPreview` images are in
`app/build/ui-host-renders/5222-positive-sampled/actual-runtime-{before,after}.png`.
Both are 930×857; same screenshot-derived processed photo, same crop, no new film
processing or tonal adjustment. These are host renderer evidence, not screenshots
of a newly installed APK. Reference/photo inputs remain outside the repository.
No full Android aggregate gate, new native ABI build, device validation, APK or
release is claimed for this isolated material patch.

## User-requested ink-gradient increment

Only the existing glyph ink changes from uniform100 to a smooth top-to-bottom112→88.
This is an intentional restrained display treatment, not a measured reference-density
fit. Body pixels, geometric alpha, original47px glyph mask and known lettering remain
unchanged. No offset shadow, bevel, glow or rainbow tint is added. Final 08:53 UTC host gate: 23 total, 21 passed, 2 existing optional external-corpus
skips, 0 failures; all six new material tests ran. Log:
`/workspace/shared/5222-positive-gradient-tests.log`. Normal runtime view and
close-up show restrained but discernible vertical ink variation.
