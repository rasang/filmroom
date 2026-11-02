#!/usr/bin/env python3
"""Invalidate only rawler when its vendored bytes change; never trust registry mtimes.
Call immediately before cargo under the same serialized build slot/frozen source.
The marker records successful invalidation, NOT a successful build or validation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def fingerprint(vendor):
    digest = hashlib.sha256()
    for path in sorted(vendor.rglob('*')):
        if path.is_symlink():
            raise RuntimeError('Vendor symlinks are not accepted')
        if path.is_file():
            name = path.relative_to(vendor).as_posix().encode()
            data = path.read_bytes()
            digest.update(len(name).to_bytes(8, 'big'))
            digest.update(name)
            digest.update(len(data).to_bytes(8, 'big'))
            digest.update(data)
    return digest.hexdigest()


def ensure(root, target_dir, target, profile, check=False):
    vendor = root / 'vendor/rawler-0.7.2'
    if not (vendor / 'Cargo.toml').is_file():
        raise RuntimeError('Expected checked-in rawler vendor directory missing')
    current = fingerprint(vendor)
    stamp = target_dir / 'filmroom-vendor-stamps' / target / profile / 'rawler.json'
    try:
        previous = json.loads(stamp.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        previous = {}
    changed = previous.get('sha256') != current
    report = dict(package='rawler', target=target, profile=profile,
                  sha256=current, invalidated=changed, marker=str(stamp))
    if not changed:
        print(json.dumps(report))
        return 0
    command = ['cargo', 'clean', '--manifest-path', str(root / 'Cargo.toml'), '-p', 'rawler']
    if target != 'host':
        command += ['--target', target]
    if profile == 'release':
        command += ['--release']
    report['command'] = command
    if check:
        report['status'] = 'stale-or-unrecorded: clean required'
        print(json.dumps(report))
        return 2
    # Explicit target/profile: plain cargo clean -p can leave release artifacts.
    subprocess.run(command, check=True, env={**os.environ, 'CARGO_TARGET_DIR': str(target_dir)})
    stamp.parent.mkdir(parents=True, exist_ok=True)
    pending = stamp.with_suffix('.pending')
    pending.write_text(json.dumps(dict(sha256=current, package='rawler', target=target,
                                       profile=profile, source=str(vendor),
                                       meaning='cache invalidated; build outcome not asserted'), indent=2) + '\n')
    pending.replace(stamp)
    print(json.dumps(report))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', default='host')
    parser.add_argument('--profile', choices=['dev', 'release'], required=True)
    parser.add_argument('--target-dir', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.target != 'host' and not all(c.isalnum() or c in '_-' for c in args.target):
        parser.error('invalid target name')
    root = Path(__file__).resolve().parents[1]
    target_dir = (args.target_dir or Path(os.environ.get('CARGO_TARGET_DIR', root / 'target'))).resolve()
    return ensure(root, target_dir, args.target, args.profile, args.check)


if __name__ == '__main__':
    sys.exit(main())
