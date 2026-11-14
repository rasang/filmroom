#!/usr/bin/env python3
"""Record native Lensfun 0.3.4 TCA + vignetting results; no optical formula copy.

Requires the archived official-library wrapper directory used by
lensfun_geometry_oracle.py. Each coordinate and color is a separate 1x1 native
call so accumulated float row stepping does not define the product algorithm.
"""
import argparse
import ctypes as C
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

import numpy as np


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


class Vignette(C.Structure):
    _fields_ = [('model', C.c_int), ('focal', C.c_float), ('aperture', C.c_float),
               ('distance', C.c_float), ('terms', C.c_float * 3)]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--oracle-dir', type=Path, required=True)
    p.add_argument('--xml', type=Path, default=Path(__file__).resolve().parents[1] / 'lensfun/approved.xml')
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    sys.path.insert(0, str(a.oracle_dir))
    from lensfun_oracle import OfficialModifier, DEFAULT_LIB
    if sha(DEFAULT_LIB) != 'b7d7d8b9d643e86a999b8de5b878758c36caa440c0a009bb6b64557df1e52044':
        raise RuntimeError('Reference native library changed')
    root = ET.parse(a.xml).getroot()
    lens = next(x for x in root.findall('lens') if x.findtext('model') == 'Canon RF 16mm F2.8 STM')
    tca = lens.find('calibration/tca')
    vignette = lens.findall('calibration/vignetting')
    result = {
        'reference': 'Unmodified native Lensfun 0.3.4 C API: subpixel geometry and color correction',
        'source': 'https://github.com/lensfun/lensfun/tree/v0.3.4',
        'library_sha256': sha(DEFAULT_LIB), 'xml_sha256': sha(a.xml),
        'wrapper_sha256': sha(a.oracle_dir / 'lensfun_oracle.py'),
        'script_sha256': sha(__file__), 'actual_flags': 43,
        'sampling': 'Independent 1x1 calls; native F64 color path avoids SIMD approximate reciprocal.',
        'tca_terms': [float(tca.get(k, '1' if k in ('vr', 'vb') else '0')) for k in ('vr', 'vb', 'cr', 'cb', 'br', 'bb')],
        'vignette_points': [{k: float(v.get(k)) for k in ('focal', 'aperture', 'distance', 'k1', 'k2', 'k3')} for v in vignette],
        'cases': [],
    }
    cases = [
        ('rf16-brick-focus-estimate', 6720, 4480, 2.8, 11.96),
        ('rf16-atrium-focus-estimate', 6720, 4480, 2.8, 2 / (1 / 1.5 + 1 / 1.99)),
        ('rf16-focus-near-1.5', 6720, 4480, 2.8, 1.5),
        ('rf16-focus-far-1.99', 6720, 4480, 2.8, 1.99),
        ('rf16-f4-calibration', 6720, 4480, 4.0, 10.0),
        ('rf16-f5.6-calibration', 6720, 4480, 5.6, 1000.0),
        ('rf16-f8-calibration', 6720, 4480, 8.0, 10.0),
        ('rf16-f22-calibration', 6720, 4480, 22.0, 1000.0),
        ('rf16-f3.2-interpolation', 6720, 4480, 3.2, 25.0),
        ('rf16-f11-interpolation', 6720, 4480, 11.0, 15.0),
        ('rf16-small-geometry', 601, 401, 2.8, 10.0),
        ('rf16-portrait-geometry', 4480, 6720, 2.8, 10.0),
    ]
    for name, width, height, aperture, distance in cases:
        with OfficialModifier(a.xml, 'Canon RF 16mm F2.8 STM', 1.0, 16.0, width, height,
                              aperture=aperture, distance=distance) as ref:
            lib = ref.lib
            pointer, f, i = C.c_void_p, C.c_float, C.c_int
            lib.lf_modifier_apply_subpixel_geometry_distortion.argtypes = [pointer, f, f, i, i, C.POINTER(f)]
            lib.lf_modifier_apply_subpixel_geometry_distortion.restype = i
            lib.lf_modifier_apply_color_modification.argtypes = [pointer, pointer, f, f, i, i, i, i]
            lib.lf_modifier_apply_color_modification.restype = i
            lib.lf_lens_interpolate_vignetting.argtypes = [pointer, f, f, f, C.POINTER(Vignette)]
            lib.lf_lens_interpolate_vignetting.restype = i
            native_lens = lib.lf_db_get_lenses(ref.db)[0]
            lib.lf_modifier_destroy(ref.mod)
            probe = lib.lf_modifier_new(native_lens, 1.0, width, height)
            lib.lf_modifier_initialize(probe, native_lens, 4, 16.0, aperture, distance, 1.0, 1, 11, 0)
            auto_scale = float(lib.lf_modifier_get_auto_scale(probe, 0))
            lib.lf_modifier_destroy(probe)
            ref.mod = lib.lf_modifier_new(native_lens, 1.0, width, height)
            flags = lib.lf_modifier_initialize(ref.mod, native_lens, 4, 16.0, aperture, distance, 0.0, 1, 43, 0)
            if flags != 43: raise RuntimeError(f'Unexpected flags {flags}')
            parameters = Vignette()
            if not lib.lf_lens_interpolate_vignetting(native_lens, 16.0, aperture, distance, C.byref(parameters)):
                raise RuntimeError('Native interpolation unavailable')
            case = {'name': name, 'width': width, 'height': height, 'aperture': aperture, 'distance_m': distance,
                    'auto_scale': auto_scale, 'vignetting_coefficients': list(parameters.terms), 'points': []}
            xs = sorted({0, 1, (width - 1) // 4, (width - 1) // 2, width - 2, width - 1})
            ys = sorted({0, 1, (height - 1) // 4, (height - 1) // 2, height - 2, height - 1})
            for y in ys:
                for x in xs:
                    coords = np.empty((1, 1, 6), dtype=np.float32)
                    if not lib.lf_modifier_apply_subpixel_geometry_distortion(ref.mod, x, y, 1, 1,
                                                                            coords.ctypes.data_as(C.POINTER(f))):
                        raise RuntimeError('No coordinates')
                    pixels = np.ones(3, dtype=np.float64)
                    if not lib.lf_modifier_apply_color_modification(ref.mod, pixels.ctypes.data, x, y, 1, 1,
                                                                  4 | (5 << 4) | (6 << 8), 24):
                        raise RuntimeError('No gain')
                    if not np.isfinite(coords).all() or not np.isfinite(pixels).all():
                        raise RuntimeError('Non-finite oracle result')
                    case['points'].append({'output': [x, y], 'source_rgb': coords.reshape(3, 2).tolist(),
                                           'source_gain': float(pixels[0])})
            result['cases'].append(case)
    a.out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'cases': len(result['cases']), 'points': sum(len(c['points']) for c in result['cases']),
                      'out': str(a.out), 'library_sha256': result['library_sha256']}))


if __name__ == '__main__':
    main()
