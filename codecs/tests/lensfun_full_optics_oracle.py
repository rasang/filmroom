#!/usr/bin/env python3
"""Regenerate full-database optical interpolation fixtures with native 0.3.4.

Uses the pinned native library and unmodified schema converter archived beside
lensfun_oracle.py. There is no optical formula implementation in this script.
Distortion keeps the original projection; TCA is also queried independently of
distortion, and vignetting is evaluated at original source coordinates.
"""
import argparse
import copy
import ctypes as C
import hashlib
import json
from pathlib import Path
import sys

from lxml import etree as E


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class Lens(C.Structure):
    _fields_ = [('maker', C.c_void_p), ('model', C.c_void_p),
        ('min_focal', C.c_float), ('max_focal', C.c_float),
        ('min_aperture', C.c_float), ('max_aperture', C.c_float),
        ('mounts', C.c_void_p), ('center_x', C.c_float), ('center_y', C.c_float),
        ('crop', C.c_float), ('aspect', C.c_float), ('projection', C.c_int)]


class Tca(C.Structure):
    _fields_ = [('model', C.c_int), ('focal', C.c_float), ('terms', C.c_float * 6)]


class Vignette(C.Structure):
    _fields_ = [('model', C.c_int), ('focal', C.c_float), ('aperture', C.c_float),
        ('distance', C.c_float), ('terms', C.c_float * 3)]


def bind(lib):
    p, f, i = C.c_void_p, C.c_float, C.c_int
    declarations = {
        'lf_db_new': ([], p), 'lf_db_destroy': ([p], None),
        'lf_db_load_data': ([p, C.c_char_p, C.c_char_p, C.c_size_t], i),
        'lf_db_get_lenses': ([p], C.POINTER(p)),
        'lf_modifier_new': ([p, f, i, i], p), 'lf_modifier_destroy': ([p], None),
        'lf_modifier_initialize': ([p, p, i, f, f, f, f, i, i, i], i),
        'lf_modifier_get_auto_scale': ([p, i], f),
        'lf_modifier_apply_geometry_distortion': ([p, f, f, i, i, C.POINTER(f)], i),
        'lf_modifier_apply_subpixel_distortion': ([p, f, f, i, i, C.POINTER(f)], i),
        'lf_modifier_apply_subpixel_geometry_distortion': ([p, f, f, i, i, C.POINTER(f)], i),
        'lf_modifier_apply_color_modification': ([p, p, f, f, i, i, i, i], i),
        'lf_lens_interpolate_tca': ([p, f, C.POINTER(Tca)], i),
        'lf_lens_interpolate_vignetting': ([p, f, f, f, C.POINTER(Vignette)], i),
    }
    for name, (args, result) in declarations.items():
        function = getattr(lib, name)
        function.argtypes, function.restype = args, result


def profile_data(node, native):
    distortion = []
    for p in node.findall('calibration/distortion'):
        model = p.get('model')
        keys = {'ptlens': ['a', 'b', 'c'], 'poly3': ['k1'], 'poly5': ['k1', 'k2']}[model]
        distortion.append({'focal_mm': float(p.get('focal')), 'model': model,
                           'terms': [float(p.get(k, '0')) for k in keys]})
    tca = []
    for p in node.findall('calibration/tca'):
        if p.get('model') == 'linear':
            terms = [float(p.get(k, '1')) for k in ['kr', 'kb']] + [0.0] * 4
        else:
            terms = [float(p.get(k, '1' if k in ['vr', 'vb'] else '0'))
                     for k in ['vr', 'vb', 'cr', 'cb', 'br', 'bb']]
        tca.append({'focal_mm': float(p.get('focal')), 'terms': terms})
    vignette = [{'focal_mm': float(p.get('focal')), 'aperture': float(p.get('aperture')),
        'distance_m': float(p.get('distance')), 'terms': [float(p.get(k, '0')) for k in ['k1', 'k2', 'k3']]}
        for p in node.findall('calibration/vignetting')]
    return {'model': node.findtext('model'), 'projection': node.findtext('type', 'rectilinear'),
        'crop_factor': native.crop, 'aspect_ratio': native.aspect,
        'center': [native.center_x, native.center_y],
        'focal_range': [native.min_focal, native.max_focal],
        'distortion': distortion, 'tca': tca, 'vignetting': vignette}


def synthetic():
    lens = E.Element('lens')
    for key, value in [('maker', 'Filmroom oracle'), ('model', 'Synthetic 10-120mm'),
                       ('mount', 'fixture'), ('cropfactor', '1'), ('aspect-ratio', '3:2')]:
        E.SubElement(lens, key).text = value
    E.SubElement(lens, 'focal', min='10', max='120')
    cal = E.SubElement(lens, 'calibration')
    # Deliberately unsorted and duplicated. Last same-key calibration replaces
    # its original slot in the native database, including a changed model.
    for focal, model, terms in [(50, 'poly3', {'k1': '.01'}),
            (20, 'poly5', {'k1': '.025', 'k2': '-.002'}),
            (90, 'poly5', {'k1': '.012', 'k2': '-.001'}),
            (28, 'poly5', {'k1': '.02', 'k2': '-.001'}),
            (50, 'poly5', {'k1': '.03', 'k2': '-.002'})]:
        E.SubElement(cal, 'distortion', model=model, focal=str(focal), **terms)
    for focal, vr, vb, cr, cb, br, bb in [
            (50, 1, 1, 0, 0, 0, 0),
            (20, 1.0001, .9999, .00003, -.0001, -.00001, .00002),
            (90, .9997, 1.0004, -.0001, .00003, .00002, -.00001),
            (28, 1.0002, .9998, -.00002, .00005, .00001, .00003),
            (50, .9998, 1.0003, .0001, -.00004, -.00002, .00001)]:
        E.SubElement(cal, 'tca', model='poly3', focal=str(focal),
                     **dict(zip(['vr', 'vb', 'cr', 'cb', 'br', 'bb'], map(str, [vr, vb, cr, cb, br, bb]))))
    for focal in [20, 50, 90]:
        for aperture in [2.8, 5.6, 11]:
            for distance in [1, 10, 1000]:
                E.SubElement(cal, 'vignetting', model='pa', focal=str(focal), aperture=str(aperture),
                    distance=str(distance), k1=str(-.3 + focal * .001 + aperture * .003 + .01 / distance),
                    k2='.03', k3='-.01')
    E.SubElement(cal, 'vignetting', model='pa', focal='50', aperture='5.6', distance='10',
                 k1='-.25', k2='.04', k3='-.01')
    return lens


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--oracle-dir', type=Path, required=True)
    parser.add_argument('--database', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    sys.path.insert(0, str(args.oracle_dir))
    from xml_converter_upstream import From2To1
    library = args.oracle_dir / 'python-deps/lensfunpy.libs/liblensfun-0b0d9ca4.so.0.3.4'
    if sha(library) != 'b7d7d8b9d643e86a999b8de5b878758c36caa440c0a009bb6b64557df1e52044':
        raise RuntimeError('Unverified native library')
    lib = C.CDLL(str(library))
    bind(lib)
    specs = [
        ('canon-rf-zoom', 'mil-canon.xml', 'Canon RF 24-105mm F4L IS USM', 1.0, [(59.8, 5, 12.5), (100, 11, 35)]),
        ('canon-large-table', 'slr-canon.xml', 'Canon EF 35-105mm f/3.5-4.5', 1.005, [(64.7, 5.6, 17)]),
        ('nikon-440-vignettes', 'slr-nikon.xml', 'Nikon AF-S Nikkor 80-400mm f/4.5-5.6G ED VR', 1.0, [(177.3, 6.3, 20)]),
        ('fuji-301-distortions', 'compact-fujifilm.xml', 'FinePix 2800 ZOOM & compatibles (Standard)', 6.3333333, [(12.35, 8, 1000)]),
        ('tamron-mixed-model', 'slr-tamron.xml', 'Tamron 35-70mm f/3.5 CF Macro', 1.53, [(43.5, 8, 30)]),
        ('nikon-linear-tca', 'slr-nikon.xml', 'Nikon AF-S DX Nikkor 35mm f/1.8G', 1.528, [(35, 2.3, 18)]),
        ('samyang-fisheye', 'mil-samyang.xml', 'Samyang 7.5mm f/3.5 UMC Fish-eye MFT', 2.0, [(7.5, 5.6, 4)]),
        ('samyang-stereographic', 'mil-samyang.xml', 'Samyang 8mm f/2.8 UMC Fish-eye', 1.534, [(8, 4.5, 10)]),
        ('canon-equisolid', 'slr-canon.xml', 'Canon EF 8-15mm f/4L Fisheye USM', 1.0, [(11, 5.6, 20)]),
        ('olympus-no-distortion', 'mil-olympus.xml', 'OLYMPUS M.30mm F3.5 Macro', 2.0, [(30, 5.6, 4)]),
    ]
    profiles, sources = [], {}
    xml_parser = E.XMLParser(resolve_entities=False, no_network=True)
    for name, file, model, crop, captures in specs:
        path = args.database / file
        sources[file] = sha(path)
        rows = [node for node in E.parse(str(path), xml_parser).getroot().findall('lens')
                if node.findtext('model') == model and abs(float(node.findtext('cropfactor')) - crop) < 1e-5]
        if len(rows) != 1: raise RuntimeError(f'Ambiguous fixture {model}: {len(rows)}')
        profiles.append((name, rows[0], crop, captures))
    profiles.append(('synthetic-duplicates', synthetic(), 1.0,
                     [(40, 5.3, 13), (15, 4, 7), (100, 8, 50), (50, 5.6, 10)]))
    result = {'reference': 'Official native Lensfun 0.3.4 C API; original projection retained',
        'source': 'https://github.com/lensfun/lensfun/tree/v0.3.4',
        'library_sha256': sha(library), 'script_sha256': sha(__file__),
        'converter_sha256': sha(args.oracle_dir / 'xml_converter_upstream.py'),
        'source_xml_sha256': sources,
        'license': 'Lensfun profile data CC BY-SA 3.0; synthetic calibrations authored for Filmroom.',
        'sampling': 'Independent native 1x1 calls; F64 color path; no projection-conversion flag.',
        'profiles': [], 'cases': []}
    for name, node, crop, captures in profiles:
        root = E.Element('lensdatabase', version='2')
        root.append(copy.deepcopy(node))
        From2To1()(root.getroottree())
        xml = E.tostring(root, encoding='utf-8')
        db = lib.lf_db_new()
        try:
            if lib.lf_db_load_data(db, b'full-optics-fixture', xml, len(xml)):
                raise RuntimeError('Native database load failed')
            pointers = lib.lf_db_get_lenses(db)
            if not pointers or not pointers[0] or pointers[1]: raise RuntimeError('Need exactly one native lens')
            lens = pointers[0]
            native = C.cast(lens, C.POINTER(Lens)).contents
            profile = profile_data(node, native)
            profile['id'] = name
            result['profiles'].append(profile)
            for focal, aperture, distance in captures:
                width, height = (6001, 4001)
                modifiers = []
                try:
                    def modifier(flags, scale):
                        mod = lib.lf_modifier_new(lens, crop, width, height)
                        modifiers.append(mod)
                        actual = lib.lf_modifier_initialize(mod, lens, 4, focal, aperture, distance,
                                                            scale, native.projection, flags, 0)
                        return mod, actual
                    distortion, df = modifier(40, 0)
                    subpixel, tf = modifier(1, 1)
                    vignette, vf = modifier(2, 1)
                    combined, cf = modifier(41, 0)
                    distortion_probe, _ = modifier(8, 1)
                    combined_probe, _ = modifier(9, 1)
                    t, v = Tca(), Vignette()
                    tok = lib.lf_lens_interpolate_tca(lens, focal, C.byref(t))
                    vok = lib.lf_lens_interpolate_vignetting(lens, focal, aperture, distance, C.byref(v))
                    tterms = list(t.terms) if tok else None
                    if tok and t.model == 1: tterms[2:] = [0.] * 4
                    case = {'profile': name, 'focal_mm': focal, 'aperture': aperture, 'distance_m': distance,
                        'camera_crop': crop, 'width': width, 'height': height,
                        'distortion_auto_scale': float(lib.lf_modifier_get_auto_scale(distortion_probe, 0)),
                        'combined_auto_scale': float(lib.lf_modifier_get_auto_scale(combined_probe, 0)),
                        'native_flags': [df, tf, vf, cf], 'tca_coefficients': tterms,
                        'vignetting_coefficients': list(v.terms) if vok else None, 'points': []}
                    def coordinates(mod, function, x, y, size):
                        values = (C.c_float * size)()
                        if not getattr(lib, function)(mod, x, y, 1, 1, values): return None
                        return list(values)
                    for x, y in [(0, 0), (1, 1), (1500, 1000), (3000, 2000),
                                 (6000, 4000), (6000, 0), (0, 4000), (5999, 3999), (3000, 0)]:
                        color = (C.c_double * 3)(1, 1, 1)
                        color_ok = lib.lf_modifier_apply_color_modification(vignette, color, x, y, 1, 1,
                                                                         4 | (5 << 4) | (6 << 8), 24)
                        case['points'].append({'output': [x, y],
                            'distortion': coordinates(distortion, 'lf_modifier_apply_geometry_distortion', x, y, 2),
                            'tca_post': coordinates(subpixel, 'lf_modifier_apply_subpixel_distortion', x, y, 6),
                            'combined': coordinates(combined, 'lf_modifier_apply_subpixel_geometry_distortion', x, y, 6),
                            'source_gain': color[0] if color_ok else None})
                    result['cases'].append(case)
                finally:
                    for mod in modifiers: lib.lf_modifier_destroy(mod)
        finally:
            lib.lf_db_destroy(db)
    # Compact lines keep large source calibration tables reviewable.
    metadata = {key: value for key, value in result.items() if key not in ['profiles', 'cases']}
    payload = json.dumps(metadata, indent=2)[:-2] + ',\n  "profiles": [\n'
    payload += ',\n'.join('    ' + json.dumps(row) for row in result['profiles']) + '\n  ],\n  "cases": [\n'
    payload += ',\n'.join('    ' + json.dumps(row) for row in result['cases']) + '\n  ]\n}\n'
    args.out.write_text(payload)
    print(json.dumps({'profiles': len(result['profiles']), 'cases': len(result['cases']),
        'points': sum(len(c['points']) for c in result['cases']), 'out': str(args.out)}))


if __name__ == '__main__':
    main()
