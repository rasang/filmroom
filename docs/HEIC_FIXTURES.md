# HEIC fixtures — provenance and verification

## Provenance / license

The nine `.heic` files and `source-corners.png` in
`app/src/androidTest/assets/heic/` are newly generated test artwork, not user
photos and not downloaded third-party photographs. The corner artwork and
`generate_fixtures.py` are dedicated to **CC0-1.0**. The encoder programs are
host tooling only; no FFmpeg, x265, libheif or libde265 binary/library is added
to the Android application by these fixtures.

The source is a 96 × 64 RGB PNG: red top-left, green top-right, blue bottom-left,
yellow bottom-right and a white center rectangle. No lossy JPEG intermediate is
used. The fixture bitstreams themselves use lossy HEVC; exact decoded values are
therefore not expected to equal the input PNG byte-for-byte.

`generate_fixtures.py` reproduces the files from its original artwork using
Pillow and FFmpeg 7.1.5 + libx265, 4:2:0, CRF 10. It encodes one frame in MP4,
extracts the HEVC configuration and sole coded sample, and places these in a
minimal HEIC still-image container. This fixture builder is **not** production
container parsing or a general converter. Container metadata consists of
`ftyp` (heic/mif1), `meta` with `hdlr`, `pitm`, `iloc`, `iinf/infe`, and `iprp`
(`hvcC`, `ispe`, `colr`, `pixi`, optional `irot`/`imir`, and `ipma`), plus `mdat`. The grid fixture additionally has `iref/dimg`: primary grid
item 1 references four individually coded 48 × 32 hvc1 tile items 2–5. Tile
items have the hidden-item flag and do not form an animation or additional
top-level photographs; the derived primary image is 96 × 64. Tile codec
configurations are compared before sharing a single hvcC property.
`corners-grid-thumbnail.heic` additionally contains an ordinary thumbnail item 6
(48 × 32 hvc1) with `thmb` pointing from item 6 to primary item 1, its own hvcC
and ispe properties, and the same baseline nclx color property. The thumbnail is
a red-biased reuse of the upper-left tile's coded sample: this makes accidental
thumbnail substitution visibly wrong and dimensionally wrong. A thumbnail
reference is not an auxC alpha/depth/gainmap property, nor an image sequence.
The files contain no EXIF, auxiliary alpha, gainmap, animation or camera private
metadata. Except for the Display P3 negative fixture, `colr` is nclx primaries 1,
transfer 13, matrix 1, limited range; FFmpeg RGB → YUV conversion uses BT.709.
The original baseline fixtures' HEVC VUI leaves primaries/transfer unspecified
(value 2), with matrix 1: their explicit baseline color declaration is in nclx.
The Display P3 fixture has **both** HEVC SPS VUI and nclx primaries 12, transfer
13, matrix 1, limited range. Its encoder explicitly sets x265 `colorprim=12`,
`transfer=13`, `colormatrix=1`, because FFmpeg metadata flags alone did not set
these VUI fields with the installed toolchain. Raw HEVC `trace_headers` verified
the final values 12/13/1. It has no ICC profile. Marker RGB code values are
interpreted as P3, not colorimetrically converted from the source PNG; it is a
negative admission test, not a reference P3→sRGB conversion test.

Run with `python app/src/androidTest/assets/heic/generate_fixtures.py`.
The script requires existing host Pillow and FFmpeg/libx265. Regeneration with
different codec versions may produce different bytes/hashes. No network is used.

## Expected orientation and admission

Corner order below is **top-left, top-right, bottom-left, bottom-right**. Inspect
at `(8,8)`, `(width−9,8)`, `(8,height−9)`, `(width−9,height−9)` to avoid chroma
subsampling boundaries. Assertions should use dominant-color classes/tolerances,
not exact RGB values.

| File | Stored dimensions | Display dimensions | Display corners | Expected initial app admission |
| --- | --- | --- | --- | --- |
| `corners-grid-thumbnail.heic` | 4 × 48 × 32 tiles + 48 × 32 thumbnail | 96 × 64 | red, green, blue, yellow | accept primary; ignore ordinary thumbnail pixels |
| `corners-grid2x2.heic` | 4 × 48 × 32 tiles | 96 × 64 | red, green, blue, yellow | accept candidate; derived grid primary |
| `corners-sdr8.heic` | 96 × 64 | 96 × 64 | red, green, blue, yellow | accept candidate |
| `corners-rotate90ccw.heic` | 96 × 64 | 64 × 96 | green, yellow, red, blue | accept candidate; irot=1 |
| `corners-rotate180.heic` | 96 × 64 | 96 × 64 | yellow, blue, green, red | accept candidate; irot=2 |
| `corners-rotate270ccw.heic` | 96 × 64 | 64 × 96 | blue, red, yellow, green | accept candidate; irot=3 |
| `corners-mirror.heic` | 96 × 64 | 96 × 64 | blue, yellow, red, green | explicit unsupported mirror; imir=0 |
| `corners-display-p3.heic` | 96 × 64 | 96 × 64 | marker colors, interpreted as P3 | explicit unsupported NCLX-only wide gamut |
| `corners-main10.heic` | 96 × 64 | 96 × 64 | red, green, blue, yellow | explicit unsupported Main10 / 10-bit |

The Main10 image is 10-bit **SDR**, not proof of an HDR image; rejecting it is an
initial precision/profile policy, not detection of HDR by bit depth. Every other
fixture is Main profile, 8-bit, opaque. The P3 fixture is also SDR; wide gamut
and HDR are different properties. A candidate is not a promise the target
Android firmware can decode it.

## Verified here, 2026-10-31

All nine HEIC files were decoded successfully on the **Linux host** using
ImageMagick 7.1.1-43 backed by runtime libheif 1.23.4 / libde265. All display
sizes and baseline/rotation/grid corner permutations in the table were checked.
P3 host decoding was checked only for successful 96 × 64 reconstruction; no
claim is made that the host color transform is a reference implementation. For the base image,
representative decoded RGB corners were `(239,20,20)`, `(20,221,19)`,
`(20,40,241)`, `(230,211,19)`. The 90° fixture was also visually inspected.
The generator was rerun and produced identical hashes with this host toolchain.

**No Android ImageDecoder, device, emulator or OPPO/Snapdragon execution occurred.**
Host decoder success validates these test inputs and expected transformations;
it does not verify Android color, orientation, memory usage or production IO.
`HeicImportInstrumentedTest` exercises the actual Android backend through
`HeicImport` with real ActivityManager memory admission, full-size canonical spool
length/preview pixels, all supported rotations/grid, SHA-256 source immutability,
and explicit negative-parser checks. Missing HEIF MIME support is marked skipped.
These device tests have been authored but **not run here**; compilation alone
is not runtime decoder verification.

## SHA-256

```text
69c9054ef627f3b70775e340269fd33d66025559112de98e3ed0bdb2f04ba47d  corners-grid-thumbnail.heic
c66be65e05fb2eefdbe9b49f6e666b9959992f3bd81756146a949abb285992ac  corners-grid2x2.heic
bfaf8cd1c1808f05bd0c3880e266205d5d6437ba82535b210cb77472eff2fa79  corners-display-p3.heic
9c65cf368d5779fb709929a16397028772c2b59bedf02cf67f300295e710e432  corners-sdr8.heic
36202b2933d15bf417edafcda092efefb8a96fad3020feda413830ed186e9074  corners-rotate90ccw.heic
830549b7226b47c5effa733c91c5ed00cb0da7e073570bb49b26c328e66c306e  corners-rotate180.heic
b10e28ff9d6ebb16bdb8c84b9111109dfa90f3695c4dae8c2f59ee74dfbba7aa  corners-rotate270ccw.heic
e3614156f9b024267005e805edf8b0212faddbc17f08dd5a58e8e2bbc496a0a1  corners-mirror.heic
74c771638bb76e469bc7ed2705d093fce1223cb8d7caff4e3e2c0b3badb760d7  corners-main10.heic
2c3fad7b99fbb0e324c11a716590a7ffd9d6b56960a175251dae5e7386806d42  source-corners.png
```

## Independently generated ICC Display P3 fixtures — 2026-10-31

Two additional original fixtures extend the initial nine-file set:

- `corners-icc-p3.heic`: one 96 × 64 8-bit Main image with `colr/prof` containing
  the independently generated Display P3 matrix/TRC ICC, no NCLX.
- `corners-grid-icc-p3-tiles.heic`: a 96 × 64 grid primary associated **only with
  its ispe**; four hidden 48 × 32 hvc1 tiles each associate hvcC, ispe, the ICC
  `colr/prof`, and pixi. The grid itself has no color property. This tests ordinary
  tile-to-derived-image color inheritance without declaring all generic derived
  image formats supported.

`generate_icc_fixtures.py` is original CC0 generator code, separate from the old
fixture generator so old fixtures and their hashes remain unchanged. It uses
host liblcms2, Pillow, FFmpeg/libx265; no runtime dependency is added to Android.
**Neither user photograph bytes nor the user's ICC profile bytes are copied,
modified, embedded or used as the source of these fixtures.**

The original `original-display-p3.icc` is made using LittleCMS
`cmsCreateRGBProfile` from independently specified standard primaries:
R=(0.680,0.320), G=(0.265,0.690), B=(0.150,0.060), D65 white=(0.3127,0.3290).
The sRGB transfer uses gamma=2.4, a=1/1.055, b=0.055/1.055, c=1/12.92,
d=0.04045; LCMS parametric type 4 emits ICC parametric function 3. LCMS creates
the D50-adapted XYZ matrix/chad; the profile is v4.3, mntr/RGB/XYZ, 536 bytes.
The optional `chrm` tag is omitted; tags are desc, cprt, wtpt, chad, rXYZ, bXYZ,
gXYZ, rTRC, gTRC, bTRC. Only its generation timestamp is normalized to a fixed
value for reproducibility. The synthetic file is not an extracted device profile.

HEVC SPS VUI declares primaries 12, transfer 13, matrix 1, limited range, using
explicit x265 parameters. The HEIF color declaration is ICC **only**, not NCLX.
The older `corners-display-p3.heic` remains an independent **NCLX-only negative**
fixture; adding these ICC fixtures does not change that negative expectation.

### Independent numerical color reference

The new source marker is deliberately moderate and non-clipping, not the old
high-saturation R/G/B/Y marker. The four source P3 encoded values, in TL/TR/BL/BR
order, and independent LCMS relative-colorimetric sRGB results are:

| P3 code values | LCMS sRGB code values |
| --- | --- |
| 170,100,100 | 181,96,98 |
| 100,170,100 | 72,172,92 |
| 100,100,170 | 100,100,175 |
| 170,160,100 | 172,160,91 |

`icc-p3-srgb-reference.json` contains the exact normalized inputs and floating
LCMS outputs. It was generated using relative colorimetric intent, no black-point
compensation, and no transform optimizer/cache. Compare Android
`ColorSpace.connect(DISPLAY_P3, SRGB)` to the JSON **original encoded inputs** with
0.002 floating tolerance; do not compare original inputs to a lossy codec result
and misattribute codec rounding to color conversion. The green patch provides a
clear relabeling detector: red moves from 100 to approximately 72, not 100.

### Host verification and limits

Both HEIC files decode successfully on the existing host ImageMagick/libheif
route to one 96 × 64 primary. The ICC extracted through decoding is byte-for-byte
identical to `original-display-p3.icc` for **both** the direct and tile-only-grid
cases. Host decoded P3 corner code values at quarter positions are
(172,101,101), (100,171,100), (100,100,171), (172,161,102). Applying LCMS to those
actual lossy decoded values gives sRGB (183,96,99), (72,173,91), (100,100,176),
(174,161,93), for both fixtures. This establishes actual color conversion rather
than mere relabeling, while keeping codec and color-transform assertions separate.

This remains **host evidence**, not Android codec/device verification. No device,
emulator, Gradle invocation, or user photo was used by the fixture-generation task.

```text
52e63d10fde4e5b003974779bf6b21378a4286bb24f8bd060000db07109adb58  corners-icc-p3.heic
332ebd59751242fa96475e567bb7d2ed54d66ec6cfe76bb547c53ad28c4d6bd9  corners-grid-icc-p3-tiles.heic
c0d30e1d21efe193f7e2d8751576386bbfabfcc26d82b4cfd27eadc80200f268  original-display-p3.icc
e5afdc300930a322b24de423ec09e4f289b351dd7fa26c2006e84936c05e05bf  icc-p3-srgb-reference.json
0fce26c598b3eddd4dabc117e54cb20068d3687b3b21a0eb470e23f98060565d  source-icc-p3-corners.png
```
