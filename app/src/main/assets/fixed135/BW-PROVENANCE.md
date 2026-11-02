# BW frame artwork / presentation contract (Beta 2)

These are original, user-reviewed display illustrations, not manufacturer-certified gates,
measured film density, scanned frame edges, or substitutes for scientific profiles.
Profiles remain independently licensed upstream data. No reference-photo/PDF pixels,
review cards, sample photographs, Blender files or font binaries are bundled in the APK.

## Provenance

Original artwork was reviewed for application use. Runtime originals are copied byte-for-byte. SHA-256 and encoded lengths are compiled
in `BwBorderDefinitions.kt`; thumbnail hashes are in `BW_FRAME_ASSETS.json`.

* 5222: reference-informed original 35 mm / 8-perf still adaptation, 36×24 opening.
  Full verified fixed-sample human-readable edge row: `EASTMAN 5222 511 00106 23 173 2020`.
  These sample identifiers are not universal stock codes. Source: https://jonoshields.com/post/double-x/ ,
  reviewed during asset preparation. Type is an approximation, no fabricated barcode.
* 7266: original Super 8 reconstruction informed by the processed-film image in
  https://www.photrio.com/forum/threads/hows-my-reversing-tri-x-reversal-7266-in-d19.101170/ .
  Rounded aperture, single perf and adjacent-frame slivers are deliberately not 135.
  Geometry/crop phase is illustrative. No unverified edge lettering is invented.
* 2302: original 35 mm / 4-perf print illustration. Opaque black picture surround/frame
  gaps and semitransparent outer rails remain separate; clear-rail alpha 28 is a display
  choice, not measured optical density. Blank edge lettering is an evidence gap, NOT a
  claim that factory stock lacks edgecode. No invented soundtrack.

Thumbnails are original procedural Blender models with re-typeset factual names, using
existing Filmroom camera, three area lights, world, AgX Medium High Contrast and assembly
transform from its original Vision3 500T scene. Camera orthographic scale 3.85. New renders:
32 samples/512px, delivered as lossless transparent 240px WebP. No copied logos/meshes.
5222 can, Super8 7266 cartridge and 2302 roll-on-core are schematic original interpretations;
2302 card is Filmroom identification, not an authentic factory package label. Trademark
names identify stock and imply no endorsement. Original artwork is distributed under the
project GPL-3.0 license. Source review package retains original procedural scene sources.

2302 is presented as a normal text-only print-medium option. Its reviewed thumbnail
is retained only in the source review package, not bundled or displayed. The 5222 and
7266 thumbnails and all three frame overlays remain unchanged.

## Frozen runtime semantics

* Geometry v1–v3 and every pre-existing color asset/version retain their previous behavior.
* New v4 freezes stock/asset hash identity, actual layout coordinates, crop transform,
  route and matte. `frame=reviewed-reference`, never fabricated `25/25A`.
* 5222 direct scan uses the negative material; its positive-display variant inverts all
  visible film RGB once, not just lettering. The scientific image is NOT inverted by the
  border stage. White perforations are an independent presentation option.
* 7266 is reversal/direct with fixed white perforation matte. 2302 is selected by PRINT
  medium on the print route, not offered as a camera negative. Its clear rail backing is white.
* Super8 slivers sample the current processed photograph with identical crop and scale at
  ±796 atlas-pixel pitch. They contain no baked sample or stretched repeated miniature.
* One uniform atlas scale and optional 90° frame rotation preserve artwork proportions. The
  integer photograph height rounds the matching aperture extent within half an output pixel. Rounded
  aperture alpha belongs to the reviewed asset; hole alpha never samples the photograph.
* Preview and export share linear-light alpha and float photograph sampling. Interactive
  Canvas drag is transient, followed by that exact compositor; it repeats slivers too.
* No border touches exposure, film input, negative/paper development, scan or checkpoints.

5222 positive v6 uses original procedural RGB with sampled near-black body4 and
user-authorized brighter gray lettering112→88 top-to-bottom (center near100, sRGB8), retaining exact original
geometry alpha. 
No reference or user-photo pixels are bundled. Not measured optical density.
Direct negative and frozen positive v4 materials remain unchanged.
