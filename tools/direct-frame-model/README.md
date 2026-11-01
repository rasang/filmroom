# Reproducible direct-route model frame materials

These are authored application assets and bundled-profile numerical inputs, not original
film scans. No personal photographs or third-party reference photographs are included.
Runtime assets live only in `app/src/main/assets/fixed135`; this tools directory is not APK data.

## Method and limits
- Per-stock profile base density and minimum-normalized dye density define the light-table base.
- Channelwise maximum density defines a contrast-bound marking illustration. It is NOT a measured
  factory edge exposure; the channel maxima need not share an exposure coordinate.
- D65 spectral integration and standard sRGB encoding mirror `negative_preview.rs`; missing
  wavelengths follow its convention. Display gamut clipping is recorded, not hidden enhancement.
- Existing authored RGB is projected continuously to approximate soft marking coverage. It is not
  a recovered physical dye layer. Alpha, fixed135 aperture, hole geometry, custom25 and DX logic remain.
- C200/XTRA use manufacturer's red/green track categories with own-profile M+Y / C+Y dye endpoints.
  Numeric densities/RGB remain a model illustration. PRO400H line color remains unverified.
- PRO400H/50D/Verita actual-film-reference gaps remain; do not call this a full scan reproduction.
- Portra800 push1/push2 share the base physical frame. White/black holes are user presentation choices.

## Rebuild
Requires Python3, NumPy, Pillow with lossless WebP support, and NotoSansCJK-Regular.ttc for the
optional review contact sheets. In each `v12` / `v13` directory run `python3 build.py`.
`v12` generates14 diagnostic frames but **only the11 in integration-11-manifest.json are adopted**;
its three Fuji two-endpoint frames deliberately fail the colored-track requirement. Replace those
three with `v13` results. The reviewed manifests record final input/output hashes. Running generators
never rewrites the app registry or automatically grants approval. Different libwebp versions may
change compressed bytes; decoded straight RGBA must be identical before any new payload is adopted.
The actual released/candidate WebPs remain individually SHA-pinned in runtime contracts.

`profile-endpoints.py <catalog-film-id>` independently recomputes profile endpoint values using
this source tree's bundled profile and Rust spectral/color constants. It only writes a study JSON,
not an asset or runtime setting. Original reproducible palette inputs are frozen under v12/inputs.
Profile/source license is preserved in SPEKTRAFILM_LICENSE.txt.

## Compatibility
Original45 contracts are unchanged. New snapshots freeze appearance semantics and a unique material
version; old snapshots without appearance remain legacy even if their scientific route was direct.
Final border composition remains outside film science, metering, ROI input and checkpoints.
