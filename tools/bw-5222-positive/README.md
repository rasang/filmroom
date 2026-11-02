# Rebuild original sampled-color 5222 positive frame

Run `python3 tools/bw-5222-positive/generate.py` with NumPy and Pillow.
The script pins the existing original geometry hash, preserves alpha exactly,
and creates original neutral pixels and the already verified full edge text.
It does not read any reference/user image. `reference-color-samples.json` is the
independent material owner's ROI report; `core-samples.json` documents the additional
four-neighbour-eroded type-core measurement and intentional legibility adjustment.
Targets are sRGB8 body4 / letter112→88 (top→bottom, center near100), not physical optical-density estimates.
