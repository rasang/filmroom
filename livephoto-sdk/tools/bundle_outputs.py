#!/usr/bin/env python3
"""Package only successful CI outputs. No GitHub Release or production signing."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import zipfile
ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT.parent/'downloads/livephoto-sdk'
DEST.mkdir(parents=True,exist_ok=True)
VERSION='0.1.0-alpha02'
def copy_one(pattern,name):
    matches=list(ROOT.glob(pattern))
    if len(matches)!=1: raise RuntimeError(f'Expected one {pattern}; found {matches}')
    shutil.copyfile(matches[0],DEST/name)
copy_one('demo/build/outputs/apk/debug/demo-debug.apk',f'LivePhoto-SDK-demo-{VERSION}.apk')
copy_one('android/build/outputs/aar/*-release.aar',f'livephoto-android-{VERSION}.aar')
copy_one(f'core/build/libs/livephoto-core-{VERSION}.jar',f'livephoto-core-{VERSION}.jar')
run=ROOT/'core/build/selftest'
policy_path=ROOT/'core/build/compatibility-selftest/compatibility-results.json'
core=json.loads((run/'results.json').read_text())
media=json.loads((run/'independent-results.json').read_text())
policy=json.loads(policy_path.read_text())
if not core or not media or not policy or not all(r['passed'] for r in core+media+policy):
    raise RuntimeError('Refusing to package failed or missing validation results')
with zipfile.ZipFile(DEST/f'LivePhoto-SDK-source-{VERSION}.zip','w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for f in sorted(ROOT.rglob('*')):
        rel=f.relative_to(ROOT)
        if not f.is_file() or any(p in {'build','.gradle','.kotlin','.git','__pycache__'} for p in rel.parts): continue
        if f.suffix=='.log' or f.name in {'local.properties','apk-signature.txt','apk-badging.txt'}: continue
        z.write(f,'filmroom-livephoto-sdk/'+str(rel))
    for name in (f'livephoto-android-{VERSION}.aar',f'livephoto-core-{VERSION}.jar'):
        z.write(DEST/name,'filmroom-livephoto-sdk/artifacts/'+name)
assets=json.loads((run/'artifacts.json').read_text())
with zipfile.ZipFile(DEST/f'LivePhoto-SDK-samples-{VERSION}.zip','w',zipfile.ZIP_DEFLATED) as z:
    index=[]
    for a in assets:
        record={'profile':a['profile'],'synthetic':True,'gallery_confirmed':False,'files':[]}
        for k in ('image','companion'):
            if a.get(k):
                f=Path(a[k]); path=a['profile']+'/'+f.name
                z.write(f,path); record['files'].append(path)
        index.append(record)
    z.writestr('index.json',json.dumps(index,ensure_ascii=False,indent=2))
    z.writestr('README.txt','Synthetic SDK samples, NOT OEM camera originals or gallery certification.\n')
for path in [run/'results.json',run/'independent-results.json',run/'junit.xml',policy_path,ROOT/'apk-signature.txt',ROOT/'apk-badging.txt']:
    shutil.copyfile(path,DEST/path.name)
info={
    'source_commit':os.environ.get('GITHUB_SHA'),'workflow_run':os.environ.get('GITHUB_RUN_ID'),
    'version':VERSION,'application_id':'dev.filmroom.livephoto.demo',
    'signing':'debug; not Filmroom production signing',
    'android_build_executed':True,'android_instrumentation_compiled':True,
    'android_instrumentation_executed':False,'oem_gallery_tests_executed':False,
    'core_tests':{'passed':sum(r['passed'] for r in core),'total':len(core)},
    'compatibility_tests':{'passed':sum(r['passed'] for r in policy),'total':len(policy)},
    'media_checks':{'passed':sum(r['passed'] for r in media),'total':len(media)},'files':{}
}
for f in sorted(DEST.iterdir()):
    if f.is_file() and VERSION in f.name and f.suffix in {'.apk','.aar','.jar','.zip'}:
        info['files'][f.name]={'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
(DEST/'build-info.json').write_text(json.dumps(info,indent=2)+'\n')
(DEST/'SHA256SUMS').write_text(''.join(f"{v['sha256']}  {k}\n" for k,v in info['files'].items()))
(DEST/'README.md').write_text('# LivePhoto SDK alpha02 测试下载\n\n独立 debug App，不覆盖 Filmroom、不发正式 Release。\n\n'+
    f'- [测试 APK](LivePhoto-SDK-demo-{VERSION}.apk?raw=true)\n'+
    f'- [SDK 源码和编译库](LivePhoto-SDK-source-{VERSION}.zip?raw=true)\n'+
    f'- [九组封装样例](LivePhoto-SDK-samples-{VERSION}.zip?raw=true)\n\n'+
    '新增自动选择、三态、相册验收记忆。请用 SDR 视频测试，保存成功不等于相册支持。\n'+
    '新 debug 签名可能不同；无法覆盖旧验收 App 时先卸载旧版。\n\n'+
    '构建与摘要见 [build-info.json](build-info.json)、[SHA256SUMS](SHA256SUMS)。\n')
print(json.dumps(info,indent=2))
