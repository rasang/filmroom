#!/usr/bin/env python3
"""Run the actual Filmroom reader against separately built Adobe SDK List1.
Only caller-selected original files are used. Temporary pixel outputs are removed.
Usage: script ADAPTER SDK_ORACLE SAMPLE_DNG RESULT_JSON
"""
import json, pathlib, subprocess, sys, hashlib
import numpy as np
adapter, oracle, sample, result = map(pathlib.Path, sys.argv[1:])
actual_path = result.with_suffix('.actual.f32')
expected_path = result.with_suffix('.sdk.u16')
if actual_path.exists() or expected_path.exists():
    raise RuntimeError('Refusing to overwrite an existing pixel artifact')
report = {'source_sha256': hashlib.file_digest(open(sample, 'rb'), 'sha256').hexdigest(),
          'oracle': 'Adobe DNG SDK 1.7.1 build 2768, actual Stage1 after List1'}
try:
    with expected_path.open('xb') as output:
        sdk = subprocess.run([str(oracle), str(sample)], stdout=output, stderr=subprocess.PIPE, text=True)
    report['sdk_exit'] = sdk.returncode
    report['sdk_stderr'] = sdk.stderr
    if sdk.returncode:
        raise RuntimeError('SDK failed')
    run = subprocess.run([str(adapter), str(sample), str(actual_path)], capture_output=True, text=True)
    report['adapter_exit'] = run.returncode
    if run.returncode:
        report['adapter_stderr'] = run.stderr
        raise RuntimeError('Adapter failed')
    meta = json.loads(run.stdout)
    report['adapter'] = meta
    a = meta['probe']['activeArea']
    h,w = meta['probe']['encodedHeight'],meta['probe']['encodedWidth']
    if expected_path.stat().st_size != h*w*2:
        raise RuntimeError('SDK emitted an incorrect size')
    expected = np.memmap(expected_path, dtype='<u2', mode='r', shape=(h,w))
    actual = np.memmap(actual_path, dtype='<f4', mode='r', shape=(a['h'],a['w']))
    mismatch = count = 0
    max_abs = total_abs = 0.
    for row in range(0,a['h'],64):
        sy = a['y']+row
        n = min(64,a['h']-row)
        ref = expected[sy:sy+n,a['x']:a['x']+a['w']].astype(np.float32)
        for py in range(2):
            for px in range(2):
                black = np.float32(meta['black'][py*2+px])
                white = np.float32(meta['white'][py*2+px])
                plane = ref[(py-sy)%2::2,(px-a['x'])%2::2]
                np.subtract(plane,black,out=plane);np.maximum(plane,0,out=plane);np.divide(plane,white-black,out=plane)
        delta = np.abs(actual[row:row+n]-ref)
        mismatch += int(np.count_nonzero(delta))
        count += delta.size
        max_abs = max(max_abs,float(delta.max()))
        total_abs += float(delta.astype(np.float64).sum())
    report['normalized_sensor_after_list1'] = {'samples':count,'mismatches':mismatch,'exact':mismatch==0,'max_abs':max_abs,'mean_abs':total_abs/count}
    del expected, actual
finally:
    result.write_text(json.dumps(report,indent=2))
    actual_path.unlink(missing_ok=True)
    expected_path.unlink(missing_ok=True)
print(json.dumps(report['normalized_sensor_after_list1']))
