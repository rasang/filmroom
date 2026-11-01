# Profile, route and output-color extension

This source extension bundles 20 shooting profiles (16 negative, including cinema and push variants, plus 4 reversal), 6 paper and 2 cinema-print profiles. There are no supplied monochrome profiles. `data/profile_catalog.json` is mirrored to Android assets; native `profile_catalog` supplies the same explicit ID allowlist.

## Routes

- `outputRoute=print` uses film → print medium → scan.
- `outputRoute=direct` skips the print stage. For a negative, this is a negative scan without an invented inversion. Reversal profiles require direct scan.
- Default remains Portra 400 / Portra Endura / print.
- Positive DIR correction and grain layer axes must use positive-film semantics; the streaming owner supplies these changes, tested against whole CPU.
- Black/white endpoint correction is valid on positive direct scan and supported print paths. The reference deliberately does not correct direct negative scans; the mobile interface must not present those controls as effective in that combination.

## Accurate cinema illuminants

Some new profiles use reference `T` and viewing `K75P`; the prior Rust selectors silently used daylight defaults. `extra_illuminants.rs` replaces that fallback with exact aligned, mean-normalized spectral samples from `colour-science 0.4.6` Incandescent / Kinoton 75P, matching the unchanged `standard_illuminant` function at Python revision `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`.

The Python function was executed independently for both names, and all 162 samples matched `data/illuminants_reference.json` exactly. `data/generate_extra_illuminants.py` records regeneration. This validates spectra, not a claim that all new profiles have full independent Python image parity.

## Color

Output buffers are linear RGB in the chosen space: `sRGB`, `Display P3`, or `Adobe RGB (1998)`. P3 and Adobe use their standard primary chromaticities and D65 white. Independent `colour-science` normalized-primary-matrix comparison differs by at most 2.22e-16. Codec owner applies matching transfer functions and ICC metadata; no gamma is applied in the native pipeline ABI. Preview converts selected RGB back to linear sRGB, then Android's existing sRGB encoding/display path.

The default sRGB preview transform is an exact no-op. Existing gamut-compression implementation only supports sRGB, so non-sRGB output must not silently pretend to use that compressor.

## Global scanner glare

`PreparedScanner::new_streamed` permits externally generated global glare, while ordinary `new` keeps its safety rejection. `process_unclipped_with_glare` accepts exactly one finite nonnegative amount per pixel. It adds the same illuminant-derived RGB offset as normal scanning, before gamut compression and global scan blur/sharpen. `PreparedStreamedFinish` propagates the method. Tile-local random fields are forbidden.

## Tests

`tests/profile_routes.rs` covers all shooting/default-route pairs and all print media against whole CPU, four reversal films with DIR/grain/endpoint correction, direct negative route, output spaces, scanner glare exactness, each exposed paper curve control and nonneutral film/paper curve combinations, plus pinned illuminant fixtures. Final native-wide validation must be rerun after all concurrent integrations. These are cloud CPU tests, not Android real-device verification.
