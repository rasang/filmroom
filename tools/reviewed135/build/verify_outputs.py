#!/usr/bin/env python3
"""Read-only comparison of generated outputs and the installed approved assets.
This verifier may decode production assets; the generator never reads them.
"""
import argparse
import hashlib
import json
from pathlib import Path


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root',required=True,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    parser.add_argument('--encoding-check',choices=('bytes','rgba'),default='bytes')
    args=parser.parse_args()
    from PIL import Image
    from PIL import features
    expected=json.loads((Path(__file__).resolve().parent/'expected-assets.json').read_text())
    actual={p.name for p in args.out.glob('*.webp')}
    if actual!=set(expected):
        raise ValueError(f'Unexpected WebP set: missing={sorted(set(expected)-actual)}, extra={sorted(actual-set(expected))}')
    actual_files={p.name for p in args.out.iterdir()}
    allowed=set(expected)|{Path(n).with_suffix('.png').name for n in expected}|{'QA.json'}
    if actual_files!=allowed:
        raise ValueError(f'Output directory must contain only 10 approved WebP, 10 PNG and QA.json: {sorted(actual_files^allowed)}')
    records=[]
    production=args.repo_root/'app/src/main/assets/fixed135'
    for filename,lock in expected.items():
        target=production/filename; rebuilt=args.out/filename
        source_sha=sha(target.read_bytes());output_sha=sha(rebuilt.read_bytes())
        if source_sha!=lock['sha256_webp']:
            raise ValueError(f'Installed target is not the pinned approved asset: {filename}')
        with Image.open(target) as im:
            source_pixels=im.convert('RGBA').tobytes();source_size=im.size
        with Image.open(rebuilt) as im:
            output_pixels=im.convert('RGBA').tobytes();output_size=im.size
        with Image.open(rebuilt.with_suffix('.png')) as im:
            png_pixels=im.convert('RGBA').tobytes();png_size=im.size
        if output_size!=source_size or source_size!=tuple(lock['dimensions']) or png_size!=source_size:
            raise ValueError(f'Unexpected dimensions: {filename}')
        if source_pixels!=output_pixels or png_pixels!=output_pixels:
            raise ValueError(f'RGBA mismatch: {filename}')
        rgba_sha=sha(output_pixels)
        if lock['sha256_rgba'] and rgba_sha!=lock['sha256_rgba']:
            raise ValueError(f'Pinned RGBA mismatch: {filename}')
        if args.encoding_check=='bytes' and output_sha!=source_sha:
            raise ValueError(f'WebP bytes differ: {filename}')
        records.append(dict(file=filename,webp_bytes_exact=source_sha==output_sha,rgba_exact=True,sha256_rgba=rgba_sha,sha256_webp=output_sha))
    print(json.dumps(dict(status='PASS',assets=records,libwebp=features.version('webp'),freetype2=features.version('freetype2')),indent=2))


if __name__=='__main__':
    main()
