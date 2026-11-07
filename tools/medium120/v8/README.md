# CineStill 800T 120: user-approved reference reconstruction

## Scope and authority

On 2026-11-06 the user supplied five photographed night scenes with visible CineStill 800T edges, then explicitly approved modeling this appearance. The original JPEGs are **not** included in the repository or application. `source-hashes.json` identifies the exact inputs; `source-coordinates.json` records inspected marks. This approval selects a visual reference, not a claim of authenticated manufacturer geometry or permission to redistribute the photos.

The files are all 1320×1758. Their approximately 1268×1265 black film regions and approximately 1166-pixel square apertures show an unperforated 6×6-style presentation. The user reference is distinct from Kodak 65mm 5219, CineStill's 2012/2014 perforated prototypes, and third-party IMAX respools. None of those products supplies this asset's markings.

## What is observed and what is reconstructed

Candidate order is 1000036443 → 1000036442 → 1000036446 → 1000036445 → 1000036444. Upper indices are 6./7, 8, 9./10, 11, 12/13 with left-pointing wedges. Lower rails show five CINESTILL 800T legends, four readable 4819 codes, and two A marks. **4819 is only the observed code**, not an inferred ISO, manufacturing date, universal code or known emulsion number.

Registration uses an approximate 56mm/1166px square-aperture scale and 850px index pitch. This yields a finite **310.209mm authored span**, with source coordinate 222mm placed at atlas coordinate 410mm (the default crop is near the supplied index11 frame). It is not 310mm of authenticated continuous physical evidence. Same-roll provenance, exposure date, camera gate and original photographer remain unverified.

Four unobserved intervals measure approximately 1.441, 1.825, 2.402 and 0.096mm in this registration. They are explicitly rendered as **reconstructed blank seams**, not observed blank film. The first may hide part of the wedge following the clipped 7; another follows the clipped 10 wedge. Clipped source glyphs remain clipped. Missing marks are not completed, duplicated or invented. The default 6×17 crop includes reconstructed seams. The renderer's nominal 61mm width, 56mm cross-film aperture and 3mm longitudinal end allowances remain presentation geometry, not actual camera measurements.

## Asset and renderer contract

- New immutable v8 RGBA atlas: 8200×50, 10px/mm, upper and lower 2.5mm rails packed separately. All pixels opaque; no perforations.
- Only the finite authored interval is available to crops; there is no arbitrary tiling.
- All five formats and both orientations crop the same coordinates, never rescale individual text to match a frame.
- Typography, mark color, no-glow styling and vertical phase are approximations. Two pinned DejaVu fonts draw all marks. No input photograph pixels or social-platform watermark are used.
- Positive display uses illustrative amber. Negative display retains the existing synthetic orange-base transformation. This is not a measured CineStill transmission/density curve, and the scientific film response is unchanged.
- Optional date and exposure printing follows the existing centered-print strategy, revised at the user's request. Existing lower-rail labels/codes are translated toward the sides, with no new glyphs or newly clipped destinations. The centered clear widths are 20 / 32 / 39 / 45 / 53mm for 645 / 6×6 / 6×7 / 6×9 / 6×17. The full padded masks, including glow, fit inside that space. Upper numbers/arrows and the photograph remain untouched. Disabling printing restores the unchanged reference asset. This is presentation layout, not real film spacing.
- The 645 crop cannot fit the complete 12.4mm legend beside a centered 26.25mm gap, so it uses a 20mm gap and fits the date/parameter font accordingly. Its existing 4819 fragment remains partial; no missing glyphs are completed. The old standardized integer-crop convention gives 645 a 0.21% sampling-scale adjustment; other formats retain 10px/mm mapping. Photo-date padded masks remain inside the aperture. Tiny outputs can record omitted fields rather than overlap original marks.
- CineStill 800T becomes 120-only for frames. Its 135 frame remains unavailable; Kodak 500T and Acros II are not enabled by this change. Current totals become 13 physical 120 stocks / 15 profile IDs including two Portra aliases.
- All earlier PNGs and version definitions remain unchanged for frozen snapshots.

## Reproduce and test

`python tools/medium120/v8/generate.py --output <temporary-directory>` produces the atlas and evidence manifest. `python tools/medium120/v8/test_generate.py` checks exact reproducibility, hash/size, opaque colored rails, finite crop bounds, the literal 27-mark inventory, four declared seams and absence of source photos in packaged assets.

Android coverage is in CineStill120ReferenceHostTest, Medium120OnlyActivityHostTest, FrameFormatCapabilitiesHostTest and MediumFormatCurrentParityHostTest. Test execution status belongs in the dated validation report; this README does not imply unrun checks passed.
