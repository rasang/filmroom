# Source and licenses

The adapter is GPL-3.0-or-later. Its integration follows the approved Spektrafilm Rust reference:
https://github.com/turbasvin/spektrafilm-rs/tree/9dd59b0380194b93686aaa230a8bb9680aa270a4
(in particular `crates/spektrafilm-cli/src/bin/decode_raw_gui.rs`). We retain RawDevelop and remove its SRgb transfer stage. We do not copy embedded previews or recreate camera RAW from JPEG.

`rawler = 0.7.2`, authored by Daniel Vogelbacher and Pedro Côrte-Real, is LGPL-2.1. Its complete unmodified published source is included at `dependency-sources/rawler-0.7.2.crate`; its license is `licenses/rawler-LGPL-2.1.txt`. Registry checksum: `04f4cc35c23969a4a834e0b117c7da41ace812eb9053b5effc3fc5c77d114677`. Rawler's individual source files can have additional SPDX markings. The archive preserves them. The PPG algorithm was developed by Chuan-kai Lin as credited in rawler.

This adapter calls the public RawDevelop API on bounded sensor strips, with all code necessary to rebuild/relink included in this directory and Cargo.lock. The compiled `vendor/rawler-0.7.2` source is Filmroom-patched (including metadata budgeting, camera-variant handling, bounded Canon row/spool decoding, and corrected Canon 4:2:0 chroma interpolation); it is not byte-identical to the unmodified published archive. Ship the complete patched vendor tree and its checksums as corresponding source, alongside the upstream archive and license. Distribution must include these corresponding sources, full dependency notices and reproducible build instructions, and the containing application's complete required GPL source/build materials. A binary-only APK is not a sufficient source/relink offer. The final APK/source packaging owner must include this directory and registry dependency source retrieval/vendor materials. This file is not a claim that app-wide license compliance has already been completed.

Generated DNG fixtures and their generation script are newly authored synthetic data, dedicated to CC0; no user photographs, camera serial numbers, EXIF GPS or embedded previews are used.

## Output encoders

- `jpeg-encoder = 0.7.1`, Volker Ströbel: `(MIT OR Apache-2.0) AND IJG`. Complete license texts and published source are included. **This software is based in part on the work of the Independent JPEG Group.** Encoder uses baseline interleaved 4:4:4 rows; progressive/optimized-Huffman alternatives are deliberately disabled to avoid full-frame DCT storage.
- `tiff = 0.11.3`, The image-rs Developers: MIT. Source/license included; uncompressed RGB16 strips only in this adapter.
- `moxcms = 0.8.1`, source/license included: generates the standard sRGB ICC profile directly using `ColorProfile::new_srgb().encode()`. No external ICC files or personal metadata are used.

All dependency versions/checksums are locked by Cargo.lock. Source archives retain original per-file notices. The generated ICC profile declares Public Domain; the library's license remains applicable to its implementation.

## Public test photographs

Two optional non-shipping runtime fixtures are CC0 samples from raw.pixls.us: Google Pixel 2 XL (record 2206) and OnePlus ONEPLUS A3003 / 3T (record 1328). Their repository records, source links, declared CC0 license and verified SHA-256 values are preserved in `tests/public-fixtures/SOURCE.json`. They are only test inputs and are never bundled into the APK. Derived test preview images are not app runtime screenshots.

## Private candidate: bounded Sony ARW6 backport

`arw6-bounded-prototype` is a local LGPL-2.1 derivative of dnglab ARW6 code at commit `41cc1e012222d2bebbaf7623bf771ae5f0c356a4`, Copyright2026 Nikolay Amiantov <ab@fmap.me>. Original per-file SPDX/copyright notices and4096-entry delinearization LUT are preserved; fixed original modules and retrieval hashes are included in `dependency-sources/arw6-41cc1e0/`. Complete modified source is `arw6-bounded/`, license `arw6-bounded/LICENSE`. Modifications replace full-image planes with explicitly budgeted anonymous-FD row I/O, checked geometry/arithmetic, sequential reconstruction and cancellation. Full-image reference code is not callable in the application dependency (test-only small IDWT helpers remain). The matching ILCE-7M5 calibration profile is pinned to the same source commit, not a model-specific codec bypass.

Rawler0.7.2 vendor source is locally patched for metadata-only ARW6 (compression32766) and checked raw-IFD black/white ×2. Existing ordinary-camera/linear-RAW changes are also local; the unmodified registry archive is a provenance artifact, not the exact built source. Builds must use the checked-in modified `vendor/rawler-0.7.2`, local `arw6-bounded`, Cargo.lock and updated vendor checksums. These candidate sources/notices are not a publication authorization or a claim of completed app-wide distribution compliance. No private sample pixels or derived photos are bundled.

## Canon bounded streaming and 4:2:0 correction

The added vendor streaming/chroma modules retain LGPL-2.1 SPDX notices. The chroma
algorithm follows the independently checked physical even-row/even-column anchor
coordinates and LibRaw's vertical-then-horizontal rounded averaging. Reference:
https://github.com/LibRaw/LibRaw/blob/2caab57e269cc7fce17c3189e4d4d7e3e5861875/src/decoders/decoders_dcraw.cpp#L707-L735
LibRaw offers LGPL-2.1 or CDDL-1.0; LGPL-2.1 is the compatible reference option.
No new runtime dependency was added. See `../CANON_420_CORRECTION_PLAN.md` for
immutable reference hashes and the RawSpeed comparison. Rebuild patched registry
source from a clean target (or explicitly `cargo clean -p rawler`) because Cargo
may otherwise reuse an earlier vendored registry artifact.

The additional Canon Bayer patch includes two-row SOF3 predictor1..7 decoding,
checked inverse vertical-slice mapping, original-order dither and masked-black
statistics, and2-byte/pixel anonymous integer storage. These new vendor modules
retain LGPL-2.1; no new dependency or external reference implementation was
added. The original pinned rawler full decoder is the offline integer oracle.
See `../CANON_BAYER_STREAMING_DESIGN.md` for exactness tests and remaining release
conditions.


## Robertson CCT numerical dataset (new DNG profile calibration)

Numerical data only from Colour v0.4.7, commit
a3bfe349685f528100672e5c8ca2dfeeef64a273:
https://github.com/colour-science/colour/blob/a3bfe349685f528100672e5c8ca2dfeeef64a273/colour/temperature/robertson1968.py
Robertson (1968); Wyszecki & Stiles, Color Science, Table 1(3.11), p.228.
Includes independent Lindbloom correction: at 325 mired u=0.24792.
No Colour implementation code is used. Retain this notice in source and
include it in binary distribution notices before integrating this module.

Copyright 2013 Colour Developers

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS “AS IS” AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE
