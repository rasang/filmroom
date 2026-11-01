# Source and licenses

The adapter is GPL-3.0-or-later. Its integration follows the approved Spektrafilm Rust reference:
https://github.com/turbasvin/spektrafilm-rs/tree/9dd59b0380194b93686aaa230a8bb9680aa270a4
(in particular `crates/spektrafilm-cli/src/bin/decode_raw_gui.rs`). We retain RawDevelop and remove its SRgb transfer stage. We do not copy embedded previews or recreate camera RAW from JPEG.

`rawler = 0.7.2`, authored by Daniel Vogelbacher and Pedro Côrte-Real, is LGPL-2.1. Its complete unmodified published source is included at `dependency-sources/rawler-0.7.2.crate`; its license is `licenses/rawler-LGPL-2.1.txt`. Registry checksum: `04f4cc35c23969a4a834e0b117c7da41ace812eb9053b5effc3fc5c77d114677`. Rawler's individual source files can have additional SPDX markings. The archive preserves them. The PPG algorithm was developed by Chuan-kai Lin as credited in rawler.

This adapter calls the public RawDevelop API on bounded sensor strips, with all code necessary to rebuild/relink included in this directory and Cargo.lock. No rawler source is modified. Distribution must include these corresponding sources, full dependency notices and reproducible build instructions, and the containing application's complete required GPL source/build materials. A binary-only APK is not a sufficient source/relink offer. The final APK/source packaging owner must include this directory and registry dependency source retrieval/vendor materials. This file is not a claim that app-wide license compliance has already been completed.

Generated DNG fixtures and their generation script are newly authored synthetic data, dedicated to CC0; no user photographs, camera serial numbers, EXIF GPS or embedded previews are used.

## Output encoders

- `jpeg-encoder = 0.7.1`, Volker Ströbel: `(MIT OR Apache-2.0) AND IJG`. Complete license texts and published source are included. **This software is based in part on the work of the Independent JPEG Group.** Encoder uses baseline interleaved 4:4:4 rows; progressive/optimized-Huffman alternatives are deliberately disabled to avoid full-frame DCT storage.
- `tiff = 0.11.3`, The image-rs Developers: MIT. Source/license included; uncompressed RGB16 strips only in this adapter.
- `moxcms = 0.8.1`, source/license included: generates the standard sRGB ICC profile directly using `ColorProfile::new_srgb().encode()`. No external ICC files or personal metadata are used.

All dependency versions/checksums are locked by Cargo.lock. Source archives retain original per-file notices. The generated ICC profile declares Public Domain; the library's license remains applicable to its implementation.

## Public test photographs

Two optional non-shipping runtime fixtures are CC0 samples from raw.pixls.us: Google Pixel 2 XL (record 2206) and OnePlus ONEPLUS A3003 / 3T (record 1328). Their repository records, source links, declared CC0 license and verified SHA-256 values are preserved in `tests/public-fixtures/SOURCE.json`. They are only test inputs and are never bundled into the APK. Derived test preview images are not app runtime screenshots.
