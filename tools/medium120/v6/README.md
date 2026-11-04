# Portra400 legend weight alignment (v6)

This revision changes only the strokes of the five observed upper-rail brand legends in Portra400. The four complete `KODAK PORTRA 400` legends and the same clipped terminal `KODAK` legend retain their character anchors, advances, design rectangles, height, film coordinates, coverage and clipping boundary. Top edge indices, all bottom numbers/arrows, all other pixels, and other stocks remain unchanged.

The preceding regular font had an isolated K stem equivalent width around0.159mm. Portra160 and Portra800 use a heavier treatment, around0.310mm and0.305mm, but have larger letters. Keeping Portra400's1.10mm rendered cap height, the new equivalent stem width is about0.260mm. Stem/height ratios are0.236 for the revised400 and0.232/0.235 for160/800. These are measurements of the authored display assets, not measurements of factory lettering.

The compiler uses the same DejaVu Sans family in bold. Each glyph is fitted back into the exact preceding regular-font design rectangle and anchor. A one-supersample-pixel bounded dilation aligns the normalized stroke weight; it never leaves that rectangle. The original character advances and tracking are retained. The underlying ink `(211,150,112)` and background `(8,8,8)` paint colors are unchanged; antialias samples change with the strokes. Optical ink edges can change within the fixed character rectangles as the strokes become heavier.

Before writing, the compiler reconstructs the original regular text and requires pixel-exact agreement with the publishedv5 text over every allowed-change area. It also requires the publishedv5 PNG and both fonts to match their SHA256 pins. Output has9,080 changed pixels, all within the declared brand glyph boxes; all other pixels are byte-for-byte identical. The manifest describes all61 laid-out glyphs, including the clipped end; a null change box means the glyph is wholly outside the retained boundary. An exact difference mask is emitted for verification only and must not be packaged as a rail asset.

The reference remains the same continuous Portra400 strip in [FLOS Stories3, PDF page38](https://www.light11.de/download/Flos_Stories_Ausgabe_3.pdf#page=38), with an additional actual-film view at [this photographer's article](https://note.com/sykmelo/n/na4b58d3349a9). Photograph resolution, scan bloom and exposure do not support an exact factory stroke-width claim. No photographic pixels are used in the output. Brand layout and finite coverage remain the existing source-guided approximation; this revision only makes display weight consistent across the Portra family.

Reproduce using Pillow, NumPy and the pinned installed DejaVu fonts:

    python3 tools/medium120/v6/align_portra400_text.py --asset-root app/src/main/assets/medium120 --output /tmp/filmroom120-v6
    python3 tools/medium120/v6/test_portra400_text.py --asset-root app/src/main/assets/medium120 -v

Only `app/src/main/assets/medium120/v6/portra400-rails.png` is a new runtime asset. Append the exactv6 definition and retain everyv1/v2/v4/v5 definition and file so frozen queues continue to resolve their original bytes. Do not include measurement images, reference photographs or test output in the application assets.
