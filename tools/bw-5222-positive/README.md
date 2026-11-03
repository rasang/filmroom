# Rebuild original sampled-color 5222 positive frame

Run `python3 tools/bw-5222-positive/generate.py` with NumPy and Pillow.
The script pins the existing original geometry hash, preserves alpha exactly,
and creates original neutral pixels and the already verified full edge text.
It does not read any reference/user image. `reference-color-samples.json` is the
independent material owner's ROI report; `core-samples.json` documents the additional
four-neighbour-eroded type-core measurement and intentional legibility adjustment.
Targets are sRGB8 body4 / letter112→88 (top→bottom, center near100), not physical optical-density estimates.

Post-Beta4 user revision outputs `bw-double_x_5222-positive-asymmetric.webp` (v7).
Both original `positive-display.webp` and published `positive-sampled.webp` are
required baselines; both are hash-pinned and reconstructed exactly before the
original ink mask is translated +320px and clipped by the canvas. The published
v6 file is retained untouched for frozen queues. RGB antialias-edge values are
composited against stationary body pixels, so those RGB thresholds are not a
literal translated copy even though the glyph coverage mask is exactly translated.
