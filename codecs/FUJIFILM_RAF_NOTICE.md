# Fujifilm RAF source modifications

The local Filmroom adapter and `src/camera_raw/fuji/` developer are part of this GPL-3.0-or-later application. The conservative six-by-six interpolation code is new application code, not copied from Fujifilm or an advanced proprietary demosaicer.

The pinned LGPL-2.1 Rawler 0.7.2 vendor is modified. For this feature the modified upstream files are `src/decoders/mod.rs`, `src/decoders/raf.rs`, and `src/decoders/raf/fuji_decompressor.rs`. Changes add opt-in cancellation, metadata-budget charging and sensor-size bounds, prevent a metadata-only probe from copying padded sensor bytes, distinguish the logical sensor block length from lookahead, and reject entropy consumption outside a declared block. Existing copyright, SPDX and LibRaw/RawSpeed attribution in these files remain unchanged.

Use the complete checked-in modified vendor source and its updated `.cargo-checksum.json`, not the original registry archive or an older cached rlib, when rebuilding/relinking. The registry archive's original checksum remains provenance for the published upstream source, not a claim that the local source is unmodified. Other pre-existing vendor modifications retain their notices.

Public RAF validation fixtures are separately cataloged in `tests/public-fixtures/fuji-SOURCES.json`. Their original photographs are not bundled. Fujifilm camera/model names identify compatibility targets and do not imply affiliation, endorsement, manufacturer color matching or all-model/all-mode support.

This candidate and notice do not authorize publication and do not certify application-wide distribution compliance. Corresponding source, full dependency notices, relink materials and actual validation must accompany any later binary release under the application's established release process.
