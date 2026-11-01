#!/usr/bin/env python3
"""Lossless APK ZIP transport recompression, before zipalign and apksigner.
Usage: python3 tools/repack-apk-transport.py app-debug.apk transport-unsigned.apk
Then use build-tools zipalign -P 16 -f 4 and apksigner --v1-signing-enabled false
with your own signing key. This minSDK29 delivery verifies with APK signature v3;
the original Gradle comparison APK verifies with v2. The certificate is unchanged.
The APK signing block is intentionally rebuilt by apksigner; payload bytes and
ZIP methods are unchanged. This script accepts APKs without v1 signature files.
"""
import copy
import sys
import zipfile
from pathlib import Path
source, destination = map(Path, sys.argv[1:])
assert source.resolve() != destination.resolve()
with zipfile.ZipFile(source) as old:
    assert not any(n.upper() == 'META-INF/MANIFEST.MF' or
                   (n.startswith('META-INF/') and n.count('/') == 1 and n.upper().endswith(('.RSA', '.DSA', '.EC', '.SF')))
                   for n in old.namelist()), 'Remove/rebuild v1 signatures with an audited signing workflow first'
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as new:
        for item in old.infolist():
            info = copy.copy(item)
            info._compresslevel = 9
            new.writestr(info, old.read(item.filename))
with zipfile.ZipFile(source) as old, zipfile.ZipFile(destination) as new:
    assert old.namelist() == new.namelist()
    assert new.testzip() is None
    for name in old.namelist():
        assert old.read(name) == new.read(name)
        assert old.getinfo(name).compress_type == new.getinfo(name).compress_type
print(destination.stat().st_size)
