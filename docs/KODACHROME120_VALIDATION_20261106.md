# Kodachrome 120 addition, 2026-11-06

## Scope and source boundary

Adds a separately pinned `kodak_kodachrome_64` 120 strip and five centered date/exposure layouts. Base is `7d0eeaec9d53cd5047874ef1ec87c71ddfdae29e`, including the final CineStill centered layout and Acros II work. Production changes are limited to the 120 stock map, new immutable asset definition and five imprint-layout rows. Existing 135 artwork, scientific film data, rendering engine, route semantics and earlier frozen assets remain unchanged.

Kodak's 1987 manufacturer brochure verifies PKR 120, ISO 64, K-14. The visible dual-edge reference is a high-resolution reproduction of Kodak's *Musicians* image: reverse-panel `KODAK PKR 6033`, opposite `PKR`, hollow right triangle and `9`. Mulvin's *Proxies*, printed page 95 / PDF page 108, corroborates the photograph's identity and 1993 attribution, but its cropped illustration does not independently verify the rails. No second continuous raw film scan was found.

Only about 59.96 mm is visible. Neighboring 8/10, uniform index cadence, periodic extension, font, color and gate dimensions are documented approximations. The source's partial next brand panel suggests about 51.64 mm spacing; this is not factory metrology. The finite model covers 320–500 mm, default center 410 mm, and supports 645/66/67/69/617 without stretching or repeating independently per frame. Camera-gate notches, 135 code 5033, DX marks and photograph pixels are excluded.

Evidence and reproducible generation are in `tools/medium120/v10/`. The authored PNG is 8200×50 RGBA, 36,386 bytes, SHA-256 `42ed192f4558ad7880b98cef6051858d8efb31e5374fd5ff19839704677e4902`, version `mf120-v10-kodachrome64-42ed192f4558`. Decoded storage is 1,640,000 bytes. Reversal output retains positive-display material, never an orange negative mask.

## Centered metadata design

Date and exposure parameters stay centered. Only the conflicting lower `PKR + triangle + number` group moves outward, retaining source pixels and spacing. The upper reverse-panel legend is unchanged. Clear widths for the five formats are 25.6/32/39/45/53 mm. This is an opt-in layout design, not original factory positioning. Complete padded masks, not just glyph cores, are checked; tiny output omissions are reported rather than overlapping ink.

## Verification

- Python generator and geometry checks: 4/4 passed. Byte-exact regeneration, opaque finite coverage, source/inference inventory and guarded centered gaps.
- Android incremental K2/Robolectric validation: **45/45 tests across 10 classes passed**, exit 0 with no guard stop. Four production files were compiled against the pinned code53 baseline: three current changes and the inherited ImprintPlan delta. This is not a fresh full application build.
- Production compositor review renders cover all five formats plus rotated67/645, with and without metadata. They use an analytic RGB fixture, not a scientifically processed photograph. The actual output was visually checked.
- **160** style/placement/size/format/orientation combinations passed complete-mask centering and collision checks, plus tiny-output, missing-data and extreme-value tests.
- **20** imprinted preview/float-export combinations matched all **4,898,692** pixels.
- The all-current120 matrix passed **300** combinations and **5,986,530** exact output pixels.
- All30 pre-existing120 PNG hashes remain unchanged. Prior Acros and paper-label worktrees remain clean.
- Runtime completed15:29UTC, was removed, and the heavy-work window was explicitly returned to the release owner. Guard peak session RSS370,995,200 bytes; minimum MemAvailable2,865,811,456 bytes; minimum overlay available2,531,627,008 bytes. No GPU/Cargo used.

The private validation overlay is not a distributable APK or a full fresh application build. Runtime, compiler output and temporary extraction are isolated under `/tmp`; source, source hashes and logs remain separate. No GPU or Cargo work is required.

## Alternative visible marking pattern

An independently inspected1995 printed photograph has brand-side41/48 and opposite filled-triangle/6, unlike the Musicians reproduction. This counterexample is recorded in `alternative-reference.json`; the two are not mixed. v10 is a reference appearance variant, not a universal Kodachrome120 template. It is held in an independent branch for review/integration, with no new APK or publication performed.
