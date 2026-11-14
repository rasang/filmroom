#!/usr/bin/env python3
"""Check all compiled lens parameter ranges against official Lensfun 0.3.4.

Requires the same independently archived native library and official schema
converter as lensfun_geometry_oracle.py. Neither is an Android dependency.
Each original lens record is loaded separately, including duplicate models
with different calibration sensors. The native C API computes the four range
fields; this script does not implement its GuessParameters algorithm.
"""
import argparse
import copy
import ctypes as C
import hashlib
import importlib.util
import json
from pathlib import Path

from lxml import etree

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def module_at(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LensPrefix(C.Structure):
    # Public lfLens 0.3.4 ABI, preceding the mount and calibration pointers.
    _fields_ = [('maker', C.c_void_p), ('model', C.c_void_p),
        ('min_focal', C.c_float), ('max_focal', C.c_float),
        ('min_aperture', C.c_float), ('max_aperture', C.c_float)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--library', required=True, type=Path)
    parser.add_argument('--converter', required=True, type=Path)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'tests/fixtures/lensfun-database-0.3.4-ranges.json')
    args = parser.parse_args()
    converter = module_at('official_xml_converter', args.converter)
    generator = module_at('filmroom_lensfun_generator', ROOT / 'tools/generate-lensfun-profiles.py')
    lib = C.CDLL(str(args.library.resolve()))
    declarations = {
        'lf_db_new': ([], C.c_void_p),
        'lf_db_destroy': ([C.c_void_p], None),
        'lf_db_load_data': ([C.c_void_p, C.c_char_p, C.c_char_p, C.c_size_t], C.c_int),
        'lf_db_get_lenses': ([C.c_void_p], C.POINTER(C.c_void_p)),
    }
    for name, (arguments, result) in declarations.items():
        function = getattr(lib, name)
        function.argtypes, function.restype = arguments, result
    rows = []
    parser = etree.XMLParser(resolve_entities=False, no_network=True)
    for path in sorted((ROOT / 'lensfun/database').glob('*.xml')):
        tree = etree.fromstring(path.read_bytes(), parser)
        for index, original in enumerate(tree.findall('lens')):
            root = etree.Element('lensdatabase', version='2')
            root.append(copy.deepcopy(original))
            converted = root.getroottree()
            converter.From2To1()(converted)
            payload = etree.tostring(root, encoding='utf-8')
            database = lib.lf_db_new()
            try:
                error = lib.lf_db_load_data(database, b'full-database-range-oracle', payload, len(payload))
                pointers = lib.lf_db_get_lenses(database)
                if error or not pointers or not pointers[0] or pointers[1]:
                    raise RuntimeError(f'Expected exactly one native lens: {path.name} record {index}, error {error}')
                native = C.cast(pointers[0], C.POINTER(LensPrefix)).contents
                expected = [native.min_focal, native.max_focal, native.min_aperture, native.max_aperture]
                focal, aperture = generator.guessed_ranges(original)
                if focal + aperture != expected:
                    raise RuntimeError(f'Native range mismatch for {path.name} record {index}: {focal + aperture} != {expected}')
                rows.append({'id': f'lf-{path.stem}-{index}', 'ranges': expected})
            finally:
                lib.lf_db_destroy(database)
    if len(rows) != 1580:
        raise RuntimeError(f'Expected all 1580 pinned lens records, got {len(rows)}')
    result = {
        'reference': 'Unmodified official native Lensfun 0.3.4 lfLens GuessParameters',
        'library_sha256': sha(args.library),
        'converter_sha256': sha(args.converter),
        'source_archive_sha256': json.loads((ROOT / 'lensfun/PROVENANCE.json').read_text())['source_archive_sha256'],
        'fields': ['min_focal_mm', 'max_focal_mm', 'min_aperture', 'max_aperture'],
        'records': len(rows),
        'rows': rows,
    }
    # One small row per lens keeps this complete oracle readable and diffable.
    prefix = json.dumps({k: v for k, v in result.items() if k != 'rows'}, indent=2)
    payload = prefix[:-2] + ',\n  "rows": [\n' + ',\n'.join('    ' + json.dumps(row) for row in rows) + '\n  ]\n}\n'
    if args.check:
        if not args.output.is_file() or json.loads(args.output.read_text()) != result:
            raise RuntimeError('Native database range fixture is stale')
    else:
        args.output.write_text(payload)
    print('Official Lensfun 0.3.4 ranges: 1580 / 1580 records match exactly; 6320 scalars checked')


if __name__ == '__main__':
    main()
