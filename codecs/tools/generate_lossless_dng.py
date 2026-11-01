#!/usr/bin/env python3
"""CC0 synthetic DNG fixtures. No user photograph is embedded or copied.

Minimal independent lossless-JPEG encoder, using fixed canonical Huffman codes
and predictor 1. Packed two-component rows reproduce common mobile DNG tiles.
"""
import struct
from pathlib import Path
from generate_dng import generate


def lossless_jpeg(samples, width, height, components=2):
    assert width % components == 0
    def segment(marker, body):
        return b'\xff' + bytes([marker]) + struct.pack('>H', len(body) + 2) + body
    sof = struct.pack('>BHHB', 16, height, width // components, components)
    sof += b''.join(bytes([c, 0x11, 0]) for c in range(components))
    # Seventeen DC categories, canonical codes 00000 through 10000.
    dht = b'\x00' + bytes([0, 0, 0, 0, 17] + [0] * 11) + bytes(range(17))
    sos = bytes([components]) + b''.join(bytes([c, 0]) for c in range(components)) + bytes([1, 0, 0])
    entropy = bytearray()
    acc = count = 0
    def put(value, n):
        nonlocal acc, count
        acc = (acc << n) | value
        count += n
        while count >= 8:
            count -= 8
            byte = (acc >> count) & 255
            entropy.append(byte)
            if byte == 255:
                entropy.append(0)
        acc &= (1 << count) - 1
    for y in range(height):
        for x in range(width):
            p = 32768 if y == 0 and x < components else samples[(y - 1) * width + x] if x < components else samples[y * width + x - components]
            diff = samples[y * width + x] - p
            category = abs(diff).bit_length()
            assert category < 16 or diff == -32768
            put(category, 5)
            if category and category != 16:
                put(diff if diff >= 0 else diff + (1 << category) - 1, category)
    if count:
        put((1 << (8 - count)) - 1, 8 - count)
    return b'\xff\xd8' + segment(0xc3, sof) + segment(0xc4, dht) + segment(0xda, sos) + entropy + b'\xff\xd9'


def convert(source, destination, tile_width=32, tile_height=64, strips=False):
    data = Path(source).read_bytes()
    endian = '<' if data[:2] == b'II' else '>'
    sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8}
    start = struct.unpack_from(endian + 'I', data, 4)[0]
    count = struct.unpack_from(endian + 'H', data, start)[0]
    tags = {}
    for i in range(count):
        p = start + 2 + 12 * i
        tag, typ, n = struct.unpack_from(endian + 'HHI', data, p)
        length = sizes[typ] * n
        offset = p + 8 if length <= 4 else struct.unpack_from(endian + 'I', data, p + 8)[0]
        tags[tag] = (typ, n, data[offset:offset + length])
    def scalar(tag):
        typ, n, b = tags[tag]
        return struct.unpack(endian + ('H' if typ == 3 else 'I'), b)[0]
    w, h, offset = scalar(256), scalar(257), scalar(273)
    sensor = struct.unpack_from(endian + 'H' * (w * h), data, offset)
    tw, th = (w, tile_height) if strips else (tile_width, tile_height)
    payloads = []
    for top in range(0, h, th):
        for left in range(0, w, tw):
            actual_h = min(th, h - top) if strips else th
            values = [sensor[min(top + y, h - 1) * w + min(left + x, w - 1)] for y in range(actual_h) for x in range(tw)]
            payloads.append(lossless_jpeg(values, tw, actual_h))
    def add(tag, typ, values):
        tags[tag] = (typ, len(values), struct.pack(endian + ('H' if typ == 3 else 'I') * len(values), *values))
    for tag in [273, 278, 279]:
        del tags[tag]
    add(259, 3, [7])
    if 'metadata' in str(destination):
        points = [0., 0., 0.5, 0.4, 1., 1.]
        tags[50940] = (11, len(points), struct.pack(endian + 'f' * len(points), *points))
        add(50941, 4, [1]); add(51089, 4, [0, 0])
        tags[51111] = (1, 16, bytes(16))
    if strips:
        add(278, 4, [th]); offsets_tag, counts_tag = 273, 279
    else:
        add(322, 4, [tw]); add(323, 4, [th]); offsets_tag, counts_tag = 324, 325
    add(offsets_tag, 4, [0] * len(payloads))
    add(counts_tag, 4, [len(p) for p in payloads])
    def header():
        base = 8 + 2 + 12 * len(tags) + 4
        extra, entries = bytearray(), []
        for tag, (typ, n, b) in sorted(tags.items()):
            if len(b) <= 4:
                value = b.ljust(4, b'\0')
            else:
                if (base + len(extra)) % 2:
                    extra.append(0)
                value = struct.pack(endian + 'I', base + len(extra))
                extra.extend(b)
            entries.append(struct.pack(endian + 'HHI', tag, typ, n) + value)
        return data[:4] + struct.pack(endian + 'IH', 8, len(tags)) + b''.join(entries) + struct.pack(endian + 'I', 0) + extra
    head = header()
    offsets, position = [], len(head)
    for p in payloads:
        offsets.append(position); position += len(p)
    add(offsets_tag, 4, offsets)
    Path(destination).write_bytes(header() + b''.join(payloads))


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1] / 'tests' / 'fixtures'
    for name, kwargs, strips in [
        ('lossless-tiles', {}, False),
        ('lossless-edge', {'w': 70, 'h': 390}, False),
        ('lossless-crop', {'w': 70, 'h': 390, 'crop': True, 'orientation': 6}, False),
        ('lossless-be', {'endian': '>'}, False),
        ('lossless-strips', {}, True),
        ('lossless-category16', {}, False),
        ('lossless-metadata', {}, False),
        ('lossless-baseline', {'baseline_exposure': 2}, False),
    ]:
        source = root / (name + '-plain.dng')
        generate(source, **kwargs)
        if name == 'lossless-category16':
            b = bytearray(source.read_bytes())
            n = struct.unpack_from('<H', b, 8)[0]
            for i in range(n):
                p = 10 + 12 * i
                if struct.unpack_from('<H', b, p)[0] == 273:
                    offset = struct.unpack_from('<I', b, p + 8)[0]
                    b[offset:offset + 4] = bytes(4)
            source.write_bytes(b)
        convert(source, root / (name + '.dng'), strips=strips)
