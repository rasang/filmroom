#!/usr/bin/env python3
"""Rebuild only the approved five A + five direct-model 135 border assets.
Never installs assets. Requires a new explicit output directory outside the repo.
The production WebP files are comparison targets, never generation inputs.
"""
import argparse
import hashlib
import importlib.metadata
import io
import json
from pathlib import Path
import platform
import resource
import sys
import time

HERE=Path(__file__).resolve().parent
STOCKS=('kodak_tri_x_400','kodak_tmax_100','ilford_hp5_plus_400','fujifilm_neopan_acros_100_ii','cinestill_800t')


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha_file(path):
    return sha_bytes(path.read_bytes())


def read_json(name):
    return json.loads((HERE/name).read_text())


def verify_files(root, hashes, label):
    for relative, expected in hashes.items():
        path=(root/relative).resolve()
        if not path.is_relative_to(root.resolve()):
            raise ValueError(f'{label}: path escapes root: {relative}')
        if sha_file(path)!=expected:
            raise ValueError(f'{label}: SHA-256 mismatch: {relative}')


def reserve_output(repo, output):
    if not output.is_absolute():
        raise ValueError('--out must be an explicit absolute path outside the repository')
    output=output.resolve()
    if output.is_relative_to(repo) or output.is_relative_to(HERE):
        raise ValueError('--out must not be inside the repository or source bundle')
    if repo.is_relative_to(output) or HERE.is_relative_to(output):
        raise ValueError('--out must not contain the repository or source bundle')
    if output.exists():
        raise ValueError('--out must not already exist; use a fresh independent directory')
    return output


def environment():
    from PIL import features
    return dict(python=platform.python_version(),packages={name:importlib.metadata.version(name) for name in ('Pillow','numpy','fonttools')},libwebp=features.version('webp'),freetype2=features.version('freetype2'),platform=platform.platform(),encoder_options=dict(format='WEBP',lossless=True,exact=True,quality=80,method=4))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo-root',required=True,type=Path,help='Repository root containing the pinned profile and native source files')
    parser.add_argument('--out',required=True,type=Path,help='New absolute output directory outside the repository, e.g. /tmp/reviewed135-unique')
    parser.add_argument('--mode',choices=('reviewed','edited'),default='reviewed',help='reviewed requires all source/input/output locks; edited permits local source/parameter changes and reports differences without an approval claim')
    parser.add_argument('--encoding-check',choices=('bytes','rgba'),default='bytes',help='bytes is the approved environment check; rgba checks only pinned decoded pixels for other encoders')
    args=parser.parse_args()
    repo=args.repo_root.resolve()
    if not (repo/'app/src/main/assets/engine-data/profiles').is_dir():
        raise ValueError('--repo-root is not a Filmroom source tree')
    out=reserve_output(repo,args.out)
    if args.mode=='reviewed':
        verify_files(HERE,read_json('source-inputs.lock.json'),'build source')
    science=read_json('scientific-inputs.lock.json')
    verify_files(repo,science,'scientific source')
    verify_files(HERE,read_json('fonts/font-inputs.lock.json'),'font')
    expected=read_json('expected-assets.json')
    current=environment()
    locked=read_json('environment-lock.json')
    if args.mode=='reviewed' and args.encoding_check=='bytes':
        for key in ('python','packages','libwebp','freetype2','encoder_options'):
            if current[key]!=locked[key]:
                raise ValueError(f'Pinned environment mismatch for {key}: {current[key]!r} vs {locked[key]!r}. Use the recorded environment for bytes; --encoding-check rgba can validate decoded equivalence only.')
    if args.mode=='reviewed' and args.encoding_check=='rgba' and any(not r['sha256_rgba'] for r in expected.values()):
        raise ValueError('Decoded RGBA baseline is not yet populated; cross-encoder validation unavailable')
    # Imports have no output side effects. Geometry is held in memory throughout.
    from PIL import Image
    import numpy as np
    import stage_geometry as geometry
    from stage_material import change
    from stage_direct import render_direct
    specs=read_json('specs.json')
    if tuple(s['id'] for s in specs)!=STOCKS:
        raise ValueError('This approved build supports exactly the five named stocks in fixed order')
    material=read_json('material-A.json')
    geometry.configure(HERE/'fonts',read_json('hp5-observed-bar-trace.json'))
    baseline={r['stock']:r for r in read_json('direct-model-baseline.json')['assets']}
    out.mkdir(parents=True,exist_ok=False)
    started=time.monotonic()
    records=[]; failures=[]
    for spec in specs:
        stock=spec['id']
        clean_image,holes,_,_=geometry.render_geometry(spec)
        v=np.array(clean_image)
        # The original clean PNG is checked in memory, never emitted as a gray draft.
        buffer=io.BytesIO();clean_image.save(buffer,format='PNG')
        clean_png_sha=sha_bytes(buffer.getvalue())
        clean_png_matches=clean_png_sha==baseline[stock]['marking_geometry_sha256']
        if args.mode=='reviewed' and args.encoding_check=='bytes' and not clean_png_matches:
            failures.append(stock+': clean v2 in-memory PNG differs from reviewed source')
        seed=int(hashlib.sha256((stock+'A'+'material-study').encode()).hexdigest()[:8],16)
        if seed!=material['seeds'][stock]:
            raise ValueError(f'Recorded seed differs from original deterministic formula for {stock}')
        a=change(v,stock,material['parameters'],seed)
        direct,model=render_direct(repo,stock,a,v,science)
        for tag,pixels in (('A',a),('direct-model',direct)):
            filename=stock+'-reviewed-'+tag+'-v1.webp'
            target=out/filename
            im=Image.fromarray(pixels)
            im.save(target,format='WEBP',lossless=True,exact=True,quality=80,method=4)
            png=target.with_suffix('.png');im.save(png,format='PNG')
            with Image.open(target) as decoded:
                exact=np.array_equal(np.asarray(decoded.convert('RGBA')),pixels)
            alpha_exact=np.array_equal(pixels[:,:,3],v[:,:,3])
            transparent_zero=bool(np.all(pixels[pixels[:,:,3]==0,:3]==0))
            aperture_zero=bool(np.all(pixels[240:1160,60:1460,3]==0))
            holes_clear=all(pixels[round(cy*40),round(cx*40),3]==0 for cx,cy in holes if 1<cx<37)
            if not all((exact,alpha_exact,transparent_zero,aperture_zero,holes_clear)):
                raise ValueError(f'Raster invariant failed: {filename}')
            webp_sha=sha_file(target);rgba_sha=sha_bytes(pixels.tobytes())
            webp_matches=webp_sha==expected[filename]['sha256_webp']
            rgba_matches=rgba_sha==expected[filename]['sha256_rgba']
            if args.mode=='reviewed' and not (webp_matches if args.encoding_check=='bytes' else rgba_matches):
                failures.append(filename+': '+args.encoding_check+' differs from approved baseline')
            records.append(dict(stock=stock,appearance=tag,file=filename,sha256_webp=webp_sha,sha256_png=sha_file(png),sha256_rgba=rgba_sha,approved_webp_bytes_exact=webp_matches,approved_rgba_exact=rgba_matches,clean_geometry_png_sha256=clean_png_sha,clean_geometry_png_matches_baseline=clean_png_matches,seed=seed if tag=='A' else None,model=model if tag=='direct-model' else None,qa=dict(webp_rgba_exact=exact,alpha_exact_v2=alpha_exact,transparent_rgb_zero=transparent_zero,aperture_alpha_zero=aperture_zero,holes_clear=bool(holes_clear))))
        del v,a,direct,clean_image
    report=dict(status='PASS' if not failures else 'FAIL',mode=args.mode,encoding_check=args.encoding_check,environment=current,scientific_input_sha256=science,source_input_sha256={p.name:sha_file(p) for p in HERE.iterdir() if p.is_file()},assets=records,failures=failures,elapsed_seconds=time.monotonic()-started,peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,limits=['No factory exposure or calibrated film density claim.','Direct endpoint projection deliberately drops A microtexture; clean authored coverage only.','PNG/WebP are the material outputs; editable code/specs do not imply vector material equivalence.','No source photograph, B material, old gray/orange schematic, app asset registration or production overwrite is output.','Edited mode makes no reviewed-asset equivalence or runtime approval claim.'])
    (out/'QA.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps(dict(status=report['status'],mode=args.mode,assets=len(records),failures=failures,out=str(out)),indent=2))
    if failures:
        return 1
    return 0


if __name__=='__main__':
    try:
        sys.exit(main())
    except (ValueError,FileNotFoundError) as exc:
        print(f'Build refused: {exc}',file=sys.stderr)
        sys.exit(2)
