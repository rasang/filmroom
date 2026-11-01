#!/usr/bin/env python3
"""Build APK thumbnails from the separately delivered original Blender-render source pack.
Usage: python3 tools/prepare_film_artwork.py /path/to/full20
Requires Pillow (lossless WebP support). Does not download any artwork.
"""
import hashlib
import json
from pathlib import Path
import sys
from PIL import Image

source = Path(sys.argv[1])
target = Path(__file__).resolve().parents[1] / 'app/src/main/assets/film-rolls'
target.mkdir(parents=True, exist_ok=True)
catalog = json.loads((source / 'catalog.json').read_text())
stocks = catalog['stocks']
assets = []
for stock_id in sorted({s['physical_stock_id'] for s in stocks}):
    image = Image.open(source / 'renders' / f'{stock_id}.png').convert('RGBA')
    image = image.resize((240, 240), Image.Resampling.LANCZOS)
    path = target / f'{stock_id}.webp'
    image.save(path, lossless=True, method=6)
    assets.append(dict(physical_stock_id=stock_id, path=path.name,
                       sha256=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size))
manifest = dict(revision=1, model_source_revision=catalog['revision'], dimensions=[240, 240], encoding='Lossless WebP RGBA', original_artwork=True,
                profiles=[dict(profile_id=s['profile_id'], physical_stock_id=s['physical_stock_id'],
                               ui_badge=s['ui_badge'], source_url=s['source_url']) for s in stocks], assets=assets)
(target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
provenance = (source / 'ASSET_PROVENANCE.md').read_text().replace('`references.json` and `catalog.json`',
    'the separately delivered model-source pack (`references.json` / `catalog.json`); this APK also retains per-stock URLs in `manifest.json`')
(target / 'ASSET_PROVENANCE.md').write_text(provenance)
print(f'{len(stocks)} profiles / {len(assets)} physical-stock images / {sum(a["bytes"] for a in assets):,} bytes')
