# Rebuild the five reviewed 135 borders

This package rebuilds exactly five approved positive **A** materials and five approved **direct-model** materials. It contains editable original code, geometry, an observed HP5 bar trace, material parameters and seeds, provenance, input/output SHA-256 locks, and licensed fonts. It contains no reference photos, screenshots, reference crops, material B, or old schematic negatives.

The reconstructed geometry is an approximation informed by individual observed specimens. It is not a factory scan or certified typography, punching tolerance or measured film density. Display frame numbers are fixed at **25 / 25A**. Specimen numbers and sources remain in `sources.json`. HP5 bars reproduce only legible observed runs, not a decoded or machine-readable barcode. Faint batch suffixes remain omitted. CineStill uses the declared conventional 36 × 24 mm application aperture instead of copying the photographed camera's exposed rail image.

## Build from the repository root

Use the versions in `environment-lock.json` and `requirements.txt`. The recorded Python/Pillow/NumPy/fontTools versions alone do not guarantee identical native rendering: FreeType and libwebp versions are checked too, and target WebP SHA-256 is the final criterion. Python, Pillow and NumPy must already be installed; the build does not install packages or access the network. fontTools is recorded for the original font preparation/audit; the raster-only stages do not import it.

Choose a **new** directory outside the repository. The generator refuses relative output paths, existing directories and directories inside or containing the repository/source bundle. It never installs output, modifies the app registry or writes to production assets. Run serially; no JVM, Gradle, Cargo or GPU work is involved.

```sh
python tools/reviewed135/build/generate.py \
  --repo-root "$PWD" --out /tmp/filmroom-reviewed135-rebuilt
python tools/reviewed135/build/verify_outputs.py \
  --repo-root "$PWD" --out /tmp/filmroom-reviewed135-rebuilt
```

Output is exactly 10 lossless straight-RGBA WebP, 10 corresponding RGBA PNG and `QA.json`. Transparent RGB is zero. No source reference image is read. No gray v2 intermediate is written: the clean geometry and its optional PNG checksum calculation exist only in memory. Raster QA covers lossless decoded equality, unchanged alpha, a clear aperture/holes and transparent RGB. The independent verifier compares the regenerated files against the installed production assets, read-only; production assets are never generator inputs.

`--encoding-check rgba` explicitly permits a different encoder only when the decoded pixels match the pinned RGBA hashes. That is pixel equivalence, **not** a claim of byte-identical WebP across different encoders/platforms. The default `bytes` mode requires the locked environment and exact target WebP bytes.

## Editable source, three stages

1. `stage_geometry.py` renders the clean authored v2 masks from `specs.json`, `hp5-observed-bar-trace.json` and bundled fonts. It preserves the original rasterization operations, text stamp composition, phase, rounded aperture and nominal holes. Editable Python/specs are the geometry source. There is deliberately no SVG output claiming equivalence with A microtexture.
2. `stage_material.py` applies **only A** parameters from `material-A.json`. The original PCG64/default_rng call sequence, smoothing, float32 intermediates, rounding and original per-stock seed formula are retained. No photographed texture is used.
3. `stage_direct.py` parses the released Rust `CMF_X/Y/Z_F64`, `ILLUMINANT_D65_F64` and `XYZ_TO_SRGB_F64` arrays and per-stock profile JSON relative to `--repo-root`. Every scientific file must match `scientific-inputs.lock.json`. Four B&W stocks use their own profiles; CineStill resolves `kodak_vision3_500t`. Channelwise density maxima define a contrast bound, not a measured factory marking exposure. The direct result projects clean v2 lettering coverage in linear sRGB between D65-derived endpoints. It uses A's alpha but **does not retain A microtexture**.

The source provenance file records the original script/parameter/QA hashes and the precise adaptations needed to make the pipeline portable and safe. `direct-model-baseline.json` preserves the original endpoint derivations and inherited data chain; its historical source commit/status does not describe current app adoption.

To explore geometry/material edits, edit source files in a separate working copy and run with `--mode edited` and a new output directory. Edited mode reports target differences without claiming approval. Scientific data and font hashes remain fixed. No command promotes edited output to production. Reviewed mode requires the source lock as well as scientific/font/output locks, so accidental edits cannot silently pass as the reviewed version.

## Static checks without rendering

```sh
python - <<'PY'
import ast, json
from pathlib import Path
root = Path('tools/reviewed135/build')
for p in root.glob('*.py'):
    ast.parse(p.read_text(), filename=str(p))
for p in root.rglob('*.json'):
    json.loads(p.read_text())
print('Python syntax and JSON valid; no generator imported or run')
PY
```

See `LICENSES.md` for code, font and reference restrictions. See the generated `QA.json` for actual environment versions, input hashes, raster checks and byte/pixel comparisons. These offline artifact checks do not replace Android runtime/GPU tests or establish physical metrology.
