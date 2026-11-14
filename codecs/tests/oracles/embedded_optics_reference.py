#!/usr/bin/env python3
"""Independent metadata/oracle for manufacturer optics; no Filmroom imports.

Regenerate using public originals, without decoding sensor data:
  python tests/oracles/embedded_optics_reference.py --source-root /path/to/research
Or recompute numeric cases from committed parameter records alone:
  python tests/oracles/embedded_optics_reference.py

Fuji distortion inversion uses bisection, deliberately independent of Rust's
analytic segment inverse. Sony's combined result is evaluated directly in
output radius; it never uses the Rust Sony source-radius recovery algorithm.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import struct

OUTPUT = Path(__file__).resolve().parents[1] / 'public-fixtures/embedded-optics-numerical.json'
SOURCES = {
    'rawtherapee': 'https://github.com/RawTherapee/RawTherapee/blob/64c2d90a3248c7ad41842d9162368858b61aac6a/rtengine/lensmetadata.cc',
    'darktable': 'https://github.com/darktable-org/darktable/blob/78f66f802183c6d241656f82b03ce6c10c1724d8/src/iop/lens.cc',
    'darktable_metadata': 'https://github.com/darktable-org/darktable/blob/78f66f802183c6d241656f82b03ce6c10c1724d8/src/common/exif.cc',
}


class Reader:
    """Independent bounded TIFF tag reader, with absolute evidence offsets."""
    def __init__(self, path):
        self.file = path.open('rb')
        self.size = path.stat().st_size
        self.used = 0

    def read(self, at, size):
        assert 0 <= at <= self.size and 0 <= size <= 4096
        assert size <= self.size - at
        self.used += size
        assert self.used <= 32768
        self.file.seek(at)
        value = self.file.read(size)
        assert len(value) == size
        return value

    def header(self, base):
        header = self.read(base, 8)
        assert header[:2] in (b'II', b'MM')
        endian = '<' if header[:2] == b'II' else '>'
        assert struct.unpack_from(endian + 'H', header, 2)[0] in (42, 0x4f52)
        return endian, struct.unpack_from(endian + 'I', header, 4)[0]

    def ifd(self, at, endian):
        count, = struct.unpack(endian + 'H', self.read(at, 2))
        assert count <= 256
        table = self.read(at + 2, count * 12)
        result = {}
        for i in range(count):
            tag, typ, count, offset = struct.unpack_from(endian + 'HHII', table, i * 12)
            assert tag not in result
            result[tag] = {'tag': f'0x{tag:04x}', 'type': typ, 'count': count, 'offset': offset, 'entry': at + 2 + i * 12}
        return result

    def value(self, entry, base, endian):
        typ, count = entry['type'], entry['count']
        assert 0 < count <= 64
        size = {1: 1, 3: 2, 4: 4, 5: 8, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 13: 4}[typ]
        at = base + entry['offset'] if size * count > 4 else entry['entry'] + 8
        raw = self.read(at, size * count)
        if typ in (5, 10):
            fmt = 'II' if typ == 5 else 'ii'
            numbers = [list(struct.unpack_from(endian + fmt, raw, i * 8)) for i in range(count)]
            assert all(d != 0 for n, d in numbers)
            values = [n / d for n, d in numbers]
        else:
            fmt = {1: 'B', 3: 'H', 4: 'I', 7: 'B', 8: 'h', 9: 'i', 11: 'f', 12: 'd', 13: 'I'}[typ]
            numbers = list(struct.unpack(endian + str(count) + fmt, raw))
            values = numbers
        return {'tag': entry['tag'], 'type': typ, 'count': count, 'data_file_offset': at,
                'raw_numeric': numbers, 'values': values, 'data_hex': raw.hex()}


def read_parameters(path, brand):
    r = Reader(path)
    crop_mode = None
    if brand == 'fuji':
        header = r.read(0, 108)
        assert header.startswith(b'FUJIFILMCCD-RAW ')
        base, = struct.unpack_from('>I', header, 100)
        endian, root_offset = r.header(base)
        root = r.ifd(base + root_offset, endian)
        raw = r.ifd(base + root[0xf000]['offset'], endian)
        main_base = struct.unpack_from('>I', header, 84)[0] + 12
        main_endian, main_first = r.header(main_base)
        main = r.ifd(main_base + main_first, main_endian)
        exif = r.ifd(main_base + main[0x8769]['offset'], main_endian)
        maker_base = main_base + exif[0x927c]['offset']
        maker_header = r.read(maker_base, 12)
        assert maker_header[:8] == b'FUJIFILM'
        maker = r.ifd(maker_base + struct.unpack_from('<I', maker_header, 8)[0], '<')
        if 0x104d in maker:
            crop_mode = r.value(maker[0x104d], maker_base, '<')['values'][0]
        names = {'distortion': 0xf00b, 'tca': 0xf00f, 'vignette': 0xf010}
    else:
        base = 0
        endian, first = r.header(0)
        root = r.ifd(first, endian)
        if brand == 'sony':
            pointer = r.value(root[0x14a], 0, endian)['values']
            assert len(pointer) == 1
            raw = r.ifd(pointer[0], endian)
            names = {'distortion': 0x7037, 'tca': 0x7035, 'vignette': 0x7032}
        else:
            exif = r.ifd(root[0x8769]['offset'], endian)
            base = exif[0x927c]['offset']
            assert r.read(base, 12) == b'OLYMPUS\0II\x03\0'
            maker = r.ifd(base + 12, '<')
            raw = r.ifd(base + maker[0x2040]['offset'], '<')
            endian = '<'
            names = {'distortion': 0x150a, 'tca': 0x150c}
    tags = {name: r.value(raw[tag], base, endian) for name, tag in names.items() if tag in raw}
    r.file.close()
    return tags, crop_mode, r.used


def lerp(knots, values, x):
    if x <= knots[0]:
        return values[0]
    for i in range(1, len(knots)):
        if x <= knots[i]:
            u = (x - knots[i - 1]) / (knots[i] - knots[i - 1])
            return (1 - u) * values[i - 1] + u * values[i]
    return values[-1]


def models(row):
    tags, brand = row['tags'], row['brand']
    dist = tags['distortion']['values']
    ca = tags['tca']['values']
    if brand == 'sony':
        nd, nc = int(dist[0]), int(ca[0]) // 2
        dk = [(i + 0.5) / (nd - 1) for i in range(nd)]
        ck = [(i + 0.5) / (nc - 1) for i in range(nc)]
        d = [1 + v / 16384 for v in dist[1:nd + 1]]
        red = [1 + v / 2097152 for v in ca[1:nc + 1]]
        blue = [1 + v / 2097152 for v in ca[nc + 1:2 * nc + 1]]
        def source_radius(r):
            return r * lerp(dk, d, r)
        def ca_factor(r, source, channel):
            return 1 if channel == 1 else lerp(ck, red if channel == 0 else blue, r)
        vign = tags['vignette']['values']
        nv = int(vign[0])
        vk = [(i + 0.5) / (nv - 1) for i in range(nv)]
        gains = [2 ** (2 ** (v / 8192 - 1) - 0.5) for v in vign[1:nv + 1]]
        def gain(r):
            return lerp(vk, gains, r)
        metadata = {'tca': {'red': ca[1:nc + 1], 'blue': ca[nc + 1:2 * nc + 1], 'distortion': dist[1:nd + 1]},
                    'vignette': {'samples': vign[1:nv + 1]}}
    elif brand == 'fuji':
        count = (len(dist) - 1) // 2
        crop = 1.25 if row['crop_mode'] in (2, 4) else 1.0
        assert row['crop_mode'] in (None, 0, 2, 4)
        knots = dist[1:count + 1]
        d = [1 + v / 100 for v in dist[count + 1:]]
        if len(ca) == 31:
            ck, red, blue = [0.0] + ca[1:11], [0.0] + ca[11:21], [0.0] + ca[21:31]
        else:
            assert len(ca) == 29
            ck, red, blue = ca[1:10], ca[10:19], ca[19:28]
        assert ck == knots
        vign = tags['vignette']['values']
        assert vign[1:count + 1] == knots
        levels = vign[count + 1:]
        metadata = {'tca': {'knots': knots, 'red': red, 'blue': blue, 'crop_factor': crop},
                    'vignette': {'knots': knots, 'levels': levels, 'crop_factor': crop}}
        k = [crop * v for v in knots]
        gains = [100 / v for v in levels]
        red, blue = [1 + v for v in red], [1 + v for v in blue]
        if k[0] > 0:
            k, d, red, blue, gains = [0.] + k, [1.] + d, [1.] + red, [1.] + blue, [1.] + gains
        def source_radius(r):
            # Solve r = s / D(s), independently of the analytic Rust inverse.
            low, high = 0., 8.
            for _ in range(90):
                s = (low + high) / 2
                if s / lerp(k, d, s) < r:
                    low = s
                else:
                    high = s
            return (low + high) / 2
        def ca_factor(r, source, channel):
            return 1 if channel == 1 else lerp(k, red if channel == 0 else blue, source)
        def gain(r):
            return lerp(k, gains, r)
    else:
        def source_radius(r):
            k2, k4, k6, scale = dist
            t = (r * scale) ** 2
            return r * scale * (1 + k2 * t + k4 * t ** 2 + k6 * t ** 3)
        def ca_factor(r, source, channel):
            if channel == 1:
                return 1.
            c0, c2, c4 = ca[:3] if channel == 0 else ca[3:]
            return 1 + c0 + c2 * source ** 2 + c4 * source ** 4
        gain = None
        metadata = {'tca': {'coefficients': ca}, 'vignette': None}
    return source_radius, ca_factor, gain, metadata


def numeric_cases(row):
    source_radius, ca_factor, gain, metadata = models(row)
    width, height = row['reference_dimensions']
    cx, cy = width / 2, height / 2
    norm = math.hypot(cx, cy)
    cases = []
    for u, v in [(0.1, 0.1), (0.25, 0.87), (0.5, 0.5), (0.91, 0.66), (0.70, 0.23)]:
        x, y = u * width, v * height
        dx, dy = x - cx, y - cy
        r = math.hypot(dx, dy) / norm
        s = source_radius(r) if r else 0.
        scale = s / r if r else 1.
        source = [cx + dx * scale, cy + dy * scale]
        rgb = [[cx + dx * scale * ca_factor(r, s, c), cy + dy * scale * ca_factor(r, s, c)] for c in range(3)]
        cases.append({'output_position': [x, y], 'source_after_distortion': source, 'channel_sources': rgb})
    gains = [] if gain is None else [
        {'position': [x, y], 'gain': gain(math.hypot(x - cx, y - cy) / norm)}
        for x, y in [(cx, cy), (0., 0.), (width * .75, height * .5), (-24., -16.)]
    ]
    row['model_parameters'] = metadata
    row['tca_cases'] = cases
    row['vignette_cases'] = gains


def gather(source_root):
    old = json.loads((source_root / 'lens-compare-inputs/embedded-lens-SOURCES.json').read_text())['samples']
    rows = []
    for sample, brand, dimensions in zip(old, ['sony', 'fuji', 'olympus'], [[6192, 4128], [6000, 4000], [5184, 3888]]):
        rows.append((sample, brand, dimensions, source_root / 'lens-compare-inputs' / sample['filename']))
    wide = json.loads((source_root / 'lens-wide-inputs/wide-candidate-downloads.json').read_text())
    sample = next(x for x in wide if str(x['id']) == '3914')
    rows.append((dict(sample, source_url=sample['url']), 'fuji', [6240, 4160], source_root / 'lens-wide-inputs' / sample['filename']))
    sample = next(x for x in json.loads((source_root / 'filmroom-vignette-test-pack/manifest.json').read_text())['samples'] if 'sony' in x['stem'])['source']
    rows.append((sample, 'sony', [5472, 3648], source_root / 'filmroom-vignette-test-pack/raw' / sample['filename']))
    result = []
    for sample, brand, dimensions, path in rows:
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        assert digest == sample['sha256']
        tags, crop, read_bytes = read_parameters(path, brand)
        result.append({'filename': sample['filename'], 'brand': brand, 'source_url': sample['source_url'],
                       'license': sample['license'], 'sha256': digest, 'file_bytes': path.stat().st_size,
                       'reference_dimensions': dimensions, 'crop_mode': crop, 'tags': tags,
                       'metadata_bytes_read': read_bytes, 'metadata_read_budget': 32768})
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-root', type=Path)
    args = parser.parse_args()
    if args.source_root:
        samples = gather(args.source_root)
    else:
        samples = json.loads(OUTPUT.read_text())['samples']
    for row in samples:
        numeric_cases(row)
    report = {'schemaVersion': 1, 'purpose': 'Real public parameter records and independent continuous-model numeric oracle; no RAW pixels are distributed.',
              'sources': SOURCES, 'method': 'Direct Sony output-space product; independently bisected Fuji source radius; darktable Olympus source-radius polynomial; RawTherapee interpolation of source gains.',
              'limitations': ['Numerical model agreement is not a physical flat-field or fringe-removal measurement.',
                              'Camera crop databases and output image pipelines differ; no complete RawTherapee/darktable pixel parity is claimed.',
                              'Sony independently counted tables are retained even when vignette and distortion node counts differ; original count bytes are recorded.',
                              'Olympus supplies no supported vignette coefficients; its shading switch is never converted to gain.'],
              'samples': samples}
    OUTPUT.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    print(json.dumps({'output': str(OUTPUT), 'samples': len(samples), 'tca_coordinate_comparisons': sum(len(x['tca_cases']) * 3 * 2 for x in samples),
                      'vignette_gain_comparisons': sum(len(x['vignette_cases']) for x in samples)}))


if __name__ == '__main__':
    main()
