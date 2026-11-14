#!/usr/bin/env python3
"""Regenerate compact coordinate fixtures using the actual Lensfun 0.3.4 C API.

Requires the separately archived validation wrapper lensfun_oracle.py and its
unmodified upstream xml_converter_upstream.py, numpy, lxml, the reference native
library, and the pinned Lensfun XML snapshot. Does not evaluate lens formulas.

Example:
  python lensfun_geometry_oracle.py --oracle-dir /path/to/oracle \
    --database /path/to/pinned/lensfun \
    --out fixtures/lensfun-0.3.4-points.json

The fixture includes the native library/wrapper/source hashes. `region(x,y,1,1)`
is intentional: full-row f32 coordinate stepping accumulates an error which
depends on strip boundaries and should not become a Rust implementation rule.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def calibration(path, model):
    node = next(x for x in ET.parse(path).getroot().findall('lens') if x.findtext('model') == model)
    center = node.find('center')
    aspect = node.findtext('aspect-ratio', '1.5')
    if ':' in aspect:
        a, b = map(float, aspect.split(':'))
        aspect = a / b
    points = []
    for d in node.findall('calibration/distortion'):
        kind = d.get('model')
        keys = {'ptlens': ['a', 'b', 'c'], 'poly3': ['k1'], 'poly5': ['k1', 'k2']}[kind]
        terms = [float(d.get(key, '0')) for key in keys]
        points.append({'focal_mm': float(d.get('focal')), 'model': kind, 'terms': terms})
    return {
        'crop_factor': float(node.findtext('cropfactor')),
        'aspect_ratio': float(aspect),
        'center': [float(center.get(k, '0')) if center is not None else 0.0 for k in ('x', 'y')],
        'points': sorted(points, key=lambda x: x['focal_mm']),
    }


def synthetic(path, model, samples, crop=1.0, aspect='3:2', center=(0, 0)):
    root = ET.Element('lensdatabase', version='2')
    mount = ET.SubElement(root, 'mount')
    ET.SubElement(mount, 'name').text = 'fixture'
    lens = ET.SubElement(root, 'lens')
    for k, v in [('maker', 'Filmroom oracle fixture'), ('model', model), ('mount', 'fixture'),
                 ('cropfactor', str(crop)), ('aspect-ratio', aspect)]:
        ET.SubElement(lens, k).text = v
    ET.SubElement(lens, 'center', x=str(center[0]), y=str(center[1]))
    cal = ET.SubElement(lens, 'calibration')
    for sample in samples:
        ET.SubElement(cal, 'distortion', {k: str(v) for k, v in sample.items()})
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--oracle-dir', type=Path, required=True)
    p.add_argument('--database', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    sys.path.insert(0, str(a.oracle_dir))
    from lensfun_oracle import OfficialModifier, DEFAULT_LIB
    if sha(DEFAULT_LIB) != 'b7d7d8b9d643e86a999b8de5b878758c36caa440c0a009bb6b64557df1e52044':
        raise RuntimeError('Reference native library differs from the verified 0.3.4 build')
    canon = a.database / 'mil-canon.xml'
    ricoh = a.database / 'compact-ricoh.xml'
    source_hashes = {
        'mil-canon.xml': '2de8e9467a6af6f83cc9da834d3d3b94d1aaf54519f60c68ac6bc02780a77516',
        'compact-ricoh.xml': '46df96ebc367d5170cb6b7dfc3742093f0ad946264518fc5488573ba052f29d7',
    }
    for name, expected in source_hashes.items():
        if sha(a.database / name) != expected:
            raise RuntimeError('Pinned XML snapshot differs: ' + name)
    cases = [
        ('rf16-full', canon, 'Canon RF 16mm F2.8 STM', 1.0, 16.0, 6720, 4480),
        ('rf16-portrait', canon, 'Canon RF 16mm F2.8 STM', 1.0, 16.0, 4480, 6720),
        ('rf16-crop-1.6', canon, 'Canon RF 16mm F2.8 STM', 1.6, 16.0, 6000, 4000),
        ('rf24-105-exact', canon, 'Canon RF 24-105mm F4L IS USM', 1.0, 24.0, 6000, 4000),
        ('rf24-105-interior', canon, 'Canon RF 24-105mm F4L IS USM', 1.0, 59.8, 6000, 4000),
        ('rf24-105-last-segment', canon, 'Canon RF 24-105mm F4L IS USM', 1.0, 100.0, 6000, 4000),
        ('rfs18-45-interpolated', canon, 'Canon RF-S 18-45mm F4.5-6.3 IS STM', 1.6, 22.5, 6000, 4000),
        ('gr3', ricoh, 'Ricoh GR III & compatibles + GW-4', 1.53, 18.3, 6000, 4000),
        ('gr3x', ricoh, 'Ricoh GR IIIx & compatibles', 1.53, 26.0, 6000, 4000),
    ]
    with tempfile.TemporaryDirectory(prefix='filmroom-lensfun-oracle-') as directory:
        directory = Path(directory)
        extra = [
            ('poly3', [{'model': 'poly3', 'focal': 35, 'k1': 0.018}], 1.0, '3:2', (0, 0), 1.0, 35, 601, 401),
            ('poly3-two-point-zoom', [{'model': 'poly3', 'focal': 20, 'k1': 0.01}, {'model': 'poly3', 'focal': 50, 'k1': 0.03}], 1.0, '3:2', (0, 0), 1.0, 35, 601, 401),
            ('poly5', [{'model': 'poly5', 'focal': 35, 'k1': 0.025, 'k2': -0.002}], 1.0, '3:2', (0, 0), 1.0, 35, 601, 401),
            ('poly5-four-point-zoom', [{'model': 'poly5', 'focal': f, 'k1': k1, 'k2': k2} for f, k1, k2 in [(20, 0.01, -0.001), (28, 0.02, -0.001), (50, 0.03, -0.002), (90, 0.018, -0.003)]], 1.0, '3:2', (0, 0), 1.0, 40, 601, 401),
            ('center-aspect-crop', [{'model': 'ptlens', 'focal': 20, 'a': 0.01, 'b': -0.03, 'c': 0.005}], 2.0, '4:3', (0.08, 0), 2.0, 20, 800, 600),
            ('without-real-focal', [{'model': 'ptlens', 'focal': 35, 'a': 0.01, 'b': -0.03, 'c': 0.005}], 1.0, '3:2', (0, 0), 1.0, 35, 601, 401),
            ('with-real-focal', [{'model': 'ptlens', 'focal': 35, 'real-focal': 31, 'a': 0.01, 'b': -0.03, 'c': 0.005}], 1.0, '3:2', (0, 0), 1.0, 35, 601, 401),
        ]
        for name, samples, cal_crop, aspect, center, crop, focal, width, height in extra:
            path = directory / (name + '.xml')
            synthetic(path, name, samples, cal_crop, aspect, center)
            cases.append((name, path, name, crop, focal, width, height))
        result = {
            'reference': 'Lensfun 0.3.4 via official native C API; distortion + scale only, flags=40',
            'coordinate_direction': 'corrected output -> uncorrected source',
            'library_sha256': sha(DEFAULT_LIB),
            'wrapper_sha256': sha(a.oracle_dir / 'lensfun_oracle.py'),
            'converter_sha256': sha(a.oracle_dir / 'xml_converter_upstream.py'),
            'source_xml_sha256': source_hashes,
            'source': 'https://github.com/lensfun/lensfun/tree/v0.3.4',
            'profile_data_license': 'Lensfun database CC BY-SA 3.0; synthetic test calibrations authored for Filmroom.',
            'sampling': 'Each point uses a separate native 1x1 region, avoiding accumulated float row stepping.',
            'cases': [],
        }
        for name, xml, model, crop, focal, width, height in cases:
            case = {
                'name': name, 'profile_model': model, 'calibration': calibration(xml, model),
                'camera_crop': crop, 'focal_mm': focal, 'width': width, 'height': height,
            }
            with OfficialModifier(xml, model, crop, focal, width, height) as modifier:
                case['auto_scale'] = modifier.info['official_auto_scale']
                case['points'] = []
                xs = sorted({0, 1, (width - 1) // 7, (width - 1) // 3, (width - 1) // 2, 2 * (width - 1) // 3, width - 2, width - 1})
                ys = sorted({0, 1, (height - 1) // 7, (height - 1) // 3, (height - 1) // 2, 2 * (height - 1) // 3, height - 2, height - 1})
                for y in ys:
                    for x in xs:
                        case['points'].append({'output': [x, y], 'source': modifier.region(x, y, 1, 1)[0, 0].tolist()})
            result['cases'].append(case)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'cases': len(result['cases']), 'points': sum(len(c['points']) for c in result['cases']), 'out': str(a.out)}))


if __name__ == '__main__':
    main()
