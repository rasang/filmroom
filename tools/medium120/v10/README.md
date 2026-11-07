# Kodachrome 64 Professional 120 reference model

This is authored approximate film-edge artwork for the existing `kodak_kodachrome_64` profile. It is not a calibrated film scan, a new scientific film profile, or a claim that every Kodachrome roll has identical markings. All five nominal formats (645, 6×6, 6×7, 6×9 and 6×17) crop the same finite 180 mm model without stretching the letters or restarting each crop.

## Product and visual evidence

- [Kodak's 1987 professional-film brochure](https://www.pacificrimcamera.com/rl/03708/03708.pdf), PDF pages 3, 8, 14 and 16, documents the 120-size ISO 64 daylight reversal product PKR, introduced in 1986. The brochure is historical Kodak material hosted by Pacific Rim Camera. It proves the product, not the modeled spacing.
- [Filmcolors](https://filmcolors.org/timeline-entry/40526/) independently records PKR 120 availability in 1986–1996 and K-14 processing, citing photographic-conservation literature.
- [Kodak edge-code table](https://www.jeffreysward.com/editorials/kodakedg.htm) distinguishes 120 **6033** from 135 **5033**.
- The inspected [Musicians reproduction](https://i.pinimg.com/originals/72/f0/97/72f0973059c836b350adf677215b37ff.jpg) shows both edges: a pale rectangular reverse-panel **KODAK PKR 6033**, and the opposite **PKR**, a hollow elongated right-pointing triangle, and **9**. Rotating the portrait reproduction clockwise makes both legends readable left-to-right. Original edge pixels are inspected only; none are packaged in the asset.
- Dylan Mulvin's *Proxies* (MIT Press, 2021), chapter 3, figure 3.6, identifies **Musicians (1993)** as a Kodak image, courtesy of David Myers. See the [author's open-access book](https://dylanmulvin.com/) and [indexed full text](https://www.researchgate.net/publication/353985137_Proxies_The_Cultural_Work_of_Standing_In). This is distinct from the other commonly reproduced 1995 multiracial Shirley card. The downloadable image is a derivative reproduction, not a calibrated raw transparency scan. The book page was visually inspected: it uses a cropped version without the rails. Publication context corroborates the photograph, not the integrity or metrology of the separately reproduced edges. A second independent continuous film scan was not found.

The source's camera-gate notches are not intrinsic film markings and are excluded. The online “だれでもハッセルPKR” software deliberately adds synthetic PKR borders to digital pictures and is **not used as physical evidence**. Black-and-white cross-processed Kodachrome scans are also excluded as normal K-14 color evidence.

## Independent counterexample and selected variant

A later inspected [1995 magazine reproduction](https://www.prisonlegalnews.org/media/publications/Prison_Life_September-October_1995.pdf), printed/PDF page 36, shows a **different visible PKR 6033 pattern**: brand-side independent 41/48 and opposite filled triangles with 6. `alternative-reference.json` records the source hash and limits. It is not a continuous same-roll extension and is not spliced into this model. The specific manufacturing-era/batch explanation is unverified.

Accordingly, v10 is the **Musicians-reference appearance variant**, not the uniquely correct appearance of every Kodachrome 120 roll. The independent source supports the need to retain uncertainty, rather than silently merging two inconsistent patterns. The known synthetic border-generator site is not a source for either model.

## Observed versus inferred

The source image contains only one complete **PKR triangle 9** group. Its next brand panel is clipped to its beginning. Approximate visible coordinates and all file hashes are recorded in `source-coordinates.json` and `source-hashes.json`.

A nominal 56 mm aperture maps the source to approximately **59.96 mm** of visible length. The complete-to-clipped brand start separation is roughly **1162 px / 51.64 mm**. This supports an approximate visual phase, not a measured factory pitch. Uniform repetition beyond the source, equal index/brand cadence and sequential neighboring indices **8 and 10** are explicit presentation assumptions. No other batch code, DX code or manufacturer lettering is invented.

The authored interval is **320–500 mm**, centered at **410 mm**. Long-format crops use the declared periodic extension. The remainder of the 820 mm storage atlas is inaccessible filler, not evidence of physically blank film. Runtime coverage checks reject crops beyond the finite authored interval.

Typeface, letter width, rail alignment, pale greenish legend color, neutral dark base, 2.5 mm rails and 3 mm end allowance are approximations. The image's color and density have not been physically calibrated. The two-digit inferred index is proportionally wider, rather than compressing both digits into the one-digit slot. The asset has no perforations. The existing reversal route keeps positive-display material and does not apply a color-negative orange mask.

## Centered date and exposure

The user's established layout request is preserved: date and parameters stay centered, while conflicting lower-edge marks move outward. The complete **PKR + hollow triangle + number** group moves as one unit without rescaling; the upper reverse-panel legend remains untouched. This is optional layout design, not an assertion of original factory positions.

The five clear widths are **25.6 / 32 / 39 / 45 / 53 mm**. `imprint-layouts.json` records the exact crop rectangles, moves and exclusion intervals. All moved pixels stay inside the crop and outside the complete imprint glow masks. Small outputs may report omitted metadata rather than overlap marks. Android test results are recorded separately; a lightweight geometric preview alone does not prove production rendering.

## Reproduction and versioning

Run `python tools/medium120/v10/generate.py --output <temporary-directory>` and `python tools/medium120/v10/test_generate.py -v`. The PNG is 8200×50 opaque RGBA at 10 px/mm, packing two 2.5 mm rails. Decoded storage is 1,640,000 bytes. The generator uses pinned-source coordinates and records font hashes, with byte-exact regeneration tested. The app includes only the authored PNG, not reference photographs or PDFs.

The v10 identity is appended to the registry. No existing immutable 120/135 assets, queue identities, engine data or scientific response parameters are replaced.
