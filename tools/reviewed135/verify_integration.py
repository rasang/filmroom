#!/usr/bin/env python3
"""Read-only source/asset gates for the reviewed positive A integration.
Run from any cwd. Writes JSON only when an explicit output path is supplied.
This is not a substitute for Android decoder, UI or rendering host tests.
"""
from pathlib import Path
import hashlib,json,subprocess,sys
from PIL import Image
import numpy as np
ROOT=Path(__file__).resolve().parents[2]
BASE='5a11522c4ae37d1989c4df76912f6e581d40f272'
APPROVED_NATIVE_COMMIT='7e43e641fbc20945af6b8504138e0e133ffbaf3e'
APPROVED_THUMBNAIL_COMMIT='72c4105b834a2c29831ef37cf91c8d5167837708'
THUMBNAIL_EXCEPTIONS={
 'app/src/main/assets/film-rolls/kodak_kodachrome_64.webp',
 'app/src/main/assets/film-rolls/manifest.json',
 'app/src/main/assets/film-rolls/medium-format-manifest.json',
}
APPROVED=Path('/workspace/shared/filmroom-five-135-material-study/candidates')
V2=Path('/workspace/shared/filmroom-five-135-review-v2/candidates')
def git(*args):return subprocess.check_output(['git','-C',str(ROOT),*args])
def sha(data):return hashlib.sha256(data).hexdigest()
native_allowed=set(git('diff','--name-only',BASE,APPROVED_NATIVE_COMMIT,'--','native').decode().splitlines())
assert len(native_allowed)==29
native_approved=[]
for name in sorted(native_allowed):
    approved=git('show',f'{APPROVED_NATIVE_COMMIT}:{name}');current=(ROOT/name).read_bytes()
    assert current==approved,name
    native_approved.append(dict(path=name,sha256=sha(current),approved_commit=APPROVED_NATIVE_COMMIT))
actual_native=set(git('diff','--name-only',BASE,'--','native').decode().splitlines())
assert actual_native==native_allowed|{'native/tests/jni/dev/filmroom/engine/PrintMediumProbe.java'}
files=git('ls-tree','-r','--name-only',BASE).decode().splitlines()
checked={}; changed=[]; documentation_changes=[]; authorized_thumbnail_changes=[]
for name in files:
    # All pre-existing assets, complete production native source/data/lock, and
    # Android scientific engine implementation remain byte-for-byte unchanged.
    if name.startswith('app/src/main/assets/') or (name.startswith('native/') and not name.startswith('native/tests/')) or name.startswith('app/src/main/java/dev/filmroom/engine/'):
        old=git('show',f'{BASE}:{name}');current=(ROOT/name).read_bytes()
        checked[name]=dict(baseline=sha(old),current=sha(current),equal=old==current)
        if old!=current:
            if name in native_allowed:pass # Checked against the exact authorized blob above.
            elif name in THUMBNAIL_EXCEPTIONS:
                approved=git('show',f'{APPROVED_THUMBNAIL_COMMIT}:{name}');assert current==approved,name
                authorized_thumbnail_changes.append(dict(path=name,baseline=sha(old),current=sha(current),approved_commit=APPROVED_THUMBNAIL_COMMIT))
            elif name=='app/src/main/assets/medium120/README.txt':documentation_changes.append(dict(path=name,baseline=sha(old),current=sha(current)))
            else:changed.append(name)
assert not changed,changed
assets=[]
for source in sorted(APPROVED.glob('*-A.webp')):
    stock=source.stem.removesuffix('-A');target=ROOT/'app/src/main/assets/fixed135'/f'{stock}-reviewed-A-v1.webp'
    original=source.read_bytes();actual=target.read_bytes();assert actual==original,stock
    a=np.array(Image.open(target).convert('RGBA'));png=np.array(Image.open(source.with_suffix('.png')).convert('RGBA'))
    v2=np.array(Image.open(V2/f'{stock}-positive.png').convert('RGBA'))
    assert a.shape==(1400,1520,4) and np.array_equal(a,png)
    assert np.array_equal(a[:,:,3],v2[:,:,3]) and np.all(a[a[:,:,3]==0,:3]==0)
    count=int(np.count_nonzero(a[220:1180,40:1480,3]));assert count==2423
    assets.append(dict(stock=stock,path=str(target.relative_to(ROOT)),sha256=sha(actual),bytes=len(actual),alpha_exact_v2=True,rgba_exact_approved_png=True,aperture_nonzero_alpha_pixels=count))
assert len(assets)==5
direct=[]
manifest=json.loads(Path('/workspace/shared/filmroom-five-135-direct-review/source/REVIEW_MANIFEST.json').read_text())
for row in manifest['assets']:
    stock=row['stock'];source=Path(row['webp']);target=ROOT/'app/src/main/assets/fixed135'/f'{stock}-reviewed-direct-model-v1.webp'
    actual=target.read_bytes();assert actual==source.read_bytes() and sha(actual)==row['webp_sha256']
    a=np.array(Image.open(target).convert('RGBA'));positive=np.array(Image.open(APPROVED/f'{stock}-A.webp').convert('RGBA'))
    assert np.array_equal(a[:,:,3],positive[:,:,3])
    assert np.all(a[a[:,:,3]==0,:3]==0)
    direct.append(dict(stock=stock,path=str(target.relative_to(ROOT)),sha256=sha(actual),alpha_exact_A=True,
        model='Existing D65 / minimum-normalized base / channelwise Dmax contrast bound; not measured negative scan',
        inherited_data_chain=row['inherited_data_chain']))
assert len(direct)==5
specs=json.loads(Path('/workspace/shared/filmroom-five-135-review-v2/source/specs.json').read_text())
assert {s['id'] for s in specs}=={s['stock'] for s in assets}
for s in specs:
    labels=[t[0] for t in s['texts']]
    assert s['sample']=='25 / 25A' and '25' in labels and '25A' in labels
# Preserve all 25 baseline hashes. Only the expressly approved Kodachrome image changes.
art='app/src/main/java/dev/filmroom/ui/FilmStockArtwork.kt'
original_art=git('show',f'{BASE}:{art}').decode()
approved_art=original_art.replace('        else -> displayName.replaceFirst',
    '        "fujifilm_neopan_acros_100_ii" -> "Fujifilm Neopan\\n100 Acros II"\n        else -> displayName.replaceFirst')
assert approved_art.encode()==(ROOT/art).read_bytes()
thumbnails={name:checked[name] for name in checked if name.startswith('app/src/main/assets/film-rolls/') and name.endswith('.webp')}
assert len(thumbnails)==25 and sum(row['equal'] for row in thumbnails.values())==24
assert len(authorized_thumbnail_changes)==3
assert len(list((ROOT/'app/src/main/assets/film-rolls').glob('*.webp')))==25
recorded=json.loads(git('show',f'{APPROVED_THUMBNAIL_COMMIT}:tools/film-artwork/kodachrome-dual/before-all25-hashes.json'))
assert recorded=={Path(name).name:row['baseline'] for name,row in thumbnails.items()}
result=dict(status='PASS',baseline=BASE,source_head=git('rev-parse','HEAD').decode().strip(),new_assets=assets,direct_model_assets=direct,checked_files=checked,asset_documentation_changes=documentation_changes,authorized_native_changes=native_approved,authorized_thumbnail_changes=authorized_thumbnail_changes,baseline_25_thumbnail_hashes={Path(name).name:row["baseline"] for name,row in thumbnails.items()},unchanged_thumbnail_count=24,
    changed_native_tests=git('diff','--name-only',BASE,'--','native/tests').decode().splitlines(),
    historical_135_geometry_unchanged=all(git('show',f'{BASE}:{p}')==(ROOT/p).read_bytes() for p in [
    'app/src/main/java/dev/filmroom/border/BorderGeometry.kt','app/src/main/java/dev/filmroom/border/BwFrameGeometry.kt',
    'app/src/main/java/dev/filmroom/border/BorderCompositor.kt','app/src/main/java/dev/filmroom/border/StraightAlphaFixed135Raster.kt',
    'app/src/main/java/dev/filmroom/border/AuthoredBwBorderRaster.kt']),runtime_validation=False)
assert result['historical_135_geometry_unchanged']
text=json.dumps(result,indent=2)+'\n'
if len(sys.argv)>1:Path(sys.argv[1]).write_text(text)
print(json.dumps(dict(status='PASS',approved_A_assets=len(assets),frozen_files=len(checked),native_test_changes=result['changed_native_tests'],runtime_validation=False)))
