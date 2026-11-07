# Kodachrome 64: 135 + bare PKR 120 selector artwork

Only `film-rolls/kodak_kodachrome_64.webp` is replaced. The other 24 thumbnails are byte-identical. The runtime asset path and Kotlin artwork mapping are unchanged. The medium-format manifest now depicts both formats after the separate reviewed Kodachrome 120 border implementation; artwork itself does not grant capabilities.

## Observed evidence and limits

Kodak's 1987 brochure, page 8, shows an actual bare PKR 120 roll beside its box: https://www.pacificrimcamera.com/rl/03708/03708.pdf . The visible roll has black plastic flanges, yellow paper with black horizontal stripes, a red lower label, pale professional/daylight lettering, a removal-band line, and yellow PKR / 120 / KODACHROME lettering. This render follows the roll, not the carton. Font shapes, tiny text and unseen circumference are simplified original typesetting, not an exact factory label. No photograph pixels or traced logo are used.

The previously packaged 135 label/texture is preserved. Its physical mesh is translated -18 mm in x only. Five 120 meshes are taken unchanged from the approved pair template. Camera, lights and physical scale are retained (135 47.5 × 25.1 mm; 120 65.75 × 25.15 mm; orthographic frame 105 mm). Render: Cycles CPU, two threads, 64 samples, 1024² transparent RGBA; downsample to 240² lossless WebP. The WebP alpha bounds [47,39,197,208] match the existing paired thumbnails.

## Reproduction

The separate `Filmroom-Kodachrome-dual-thumbnail-source.zip` contains original model inputs, the new model with packed textures, label, render, old thumbnail evidence and executable source. Extract it, set ARTWORK_ROOT to its root and FILMROOM_SOURCE to the intended independent Filmroom checkout. Run make_label.py, then Blender background build.py, then package.py. The build asserts preservation of 135 vertices apart from translation, unchanged 120 meshes, camera/lights and original packed 135 texture bytes. Do not run Blender while another build owns the resource window.

`python tools/film-artwork/kodachrome-dual/test_asset.py` verifies all manifest hashes, unchanged 24 assets, dimensions/transparency/bounds, pair form and provenance. Four checks passed. Blender's guarded render exited 0 in 33.56 seconds, peak actual process-tree RSS 295,190,528 B; no resource threshold crossed. The 1024 render, review board and 72 px selector-size rendering were visually inspected.

`MediumFormatArtworkHostTest` was updated from 11 pairs / 4 singles to 12 / 3. That Android test has not been executed in this asset-only branch; run it during final integration with the already accepted Kodachrome border commit. No new APK or full build is claimed here.
