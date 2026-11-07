#!/usr/bin/env python3
"""Verify 15 opt-in printing layouts against the immutable v7 PNG pixels.

This is presentation-only standardization, not measured/authentic factory spacing.
Run from any directory. Emits JSON provenance to stdout; never modifies assets.
Groups include every non-base pixel, including faint Lanczos fringes. Adjacent
runs separated by <=10 pixels are grouped to keep legends and arrow/number pairs
intact. Crop-edge fragments stay fragments; source order must remain unchanged.
"""
import hashlib
import json
import math
import re
from itertools import groupby
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
FORMATS = {'MF645': (47.5, 3862, 476), 'MF66': (62.0, 3790, 620),
           'MF67': (76.0, 3720, 760), 'MF69': (90.0, 3650, 900),
           'MF617': (174.0, 3230, 1740)}
# Independent physical apertures from MediumFormat, not Layout.cropWidthPx.
APERTURES_MM = dict(MF645=41.5, MF66=56.0, MF67=70.0, MF69=84.0, MF617=168.0)
STOCKS = {'ilford_hp5_plus_400': 'hp5plus', 'kodak_tmax_100': 'tmax100',
          'kodak_tri_x_400': 'trix400'}

def inspect():
    code = (ROOT/'app/src/main/java/dev/filmroom/border/MediumImprintMarks.kt').read_text()
    rows = []
    for stock, name in STOCKS.items():
        path = ROOT/f'app/src/main/assets/medium120/v7/{name}-rails.png'
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        im = Image.open(path).convert('RGB')
        assert im.size == (8200, 50)
        for fmt, (width, start, count) in FORMATS.items():
            pattern = rf'Key\("{stock}", MediumFormat\.{fmt}\) to Layout\("([^"]+)", ([\d.]+), (\d+), (\d+), (\d+), ([\d.]+), ([\d.]+), listOf\((.*?)\), true\)'
            match = re.search(pattern, code)
            assert match, (stock, fmt, 'missing layout')
            version, w, st, n, ppm, clear_start, clear_end, moves_text = match.groups()
            assert version == f'mf120-v11-{name}-imprint-{sha[:12]}'
            assert (float(w), int(st), int(n), int(ppm)) == (width, start, count, 10)
            # Independently derive the closed physical window and every source
            # pixel center inside it. Odd 475-physical-pixel 645 spans 476 centers.
            physical_width = APERTURES_MM[fmt]+6.0
            original_lo = (410.0-physical_width/2)*10-.5
            original_hi = (410.0+physical_width/2)*10-.5
            first_center, last_center = math.ceil(original_lo), math.floor(original_hi)
            assert start == first_center and start+count-1 == last_center, (stock,fmt,'lost physical source pixel center')
            # Bilinear neighbors with centers OUTSIDE the window are explicitly
            # excluded by the existing integer crop convention, not invented ink.
            outside_neighbors = [x for x in range(math.floor(original_lo),math.ceil(original_hi)+1)
                                 if x < first_center or x > last_center]
            outside_ink = [x for x in outside_neighbors
                           if any(im.getpixel((x,y)) != (8,8,8) for y in range(25,50))]
            sx = width/count
            cs, ce = float(clear_start), float(clear_end)
            assert abs(cs+ce-width) < 1e-8, 'gap must be centered'
            moves = [(int(l), int(r), float(t)) for l,r,t in re.findall(r'Move\((\d+), (\d+), ([\d.]+)\)', moves_text)]
            crop = im.crop((start,25,start+count,50))
            xs = [x for x in range(count) if any(crop.getpixel((x,y)) != (8,8,8) for y in range(25))]
            groups = []
            for _, g in groupby(enumerate(xs), lambda z:z[1]-z[0]):
                run = [z[1] for z in g]
                if groups and run[0]-groups[-1][1] <= 10:
                    groups[-1][1] = run[-1]+1
                else:
                    groups.append([run[0],run[-1]+1])
            assert groups == [[l,r] for l,r,_ in moves], (stock, fmt, groups, moves)
            assert all(sum(l<=x<r for l,r,_ in moves)==1 for x in xs)
            # sourceX includes one pixel of background on either side. Check
            # those actual sampling bounds, not just the visible ink bounds.
            intervals = [(t+(max(0,l-1)-l)*sx, t+(min(count,r+1)-l)*sx) for l,r,t in moves]
            assert all(0 <= a < b <= width for a,b in intervals), (stock,fmt,intervals)
            assert all(b <= cs or a >= ce for a,b in intervals), (stock,fmt,intervals)
            assert all(intervals[i][1] < intervals[i+1][0] for i in range(len(intervals)-1))
            if fmt == 'MF645':
                # Actual original continuous sample near the right boundary.
                # Original px=4336.5 blends columns4336/4337; the old475 clamp
                # incorrectly reduced it to4336, losing HP5's visible fragment.
                original_px = original_lo+47.45*10
                source = original_px+.5-start
                mapped_px = start+min(max(source,.5),count-.5)-.5
                assert abs(mapped_px-original_px) < 1e-8
                def sample(px,y):
                    lo = math.floor(px); f = px-lo
                    return im.getpixel((lo,y))[0]*(1-f)+im.getpixel((lo+1,y))[0]*f
                assert abs(sample(mapped_px,39)-sample(original_px,39)) < 1e-8
                if name == 'hp5plus':
                    assert abs(sample(mapped_px,39)-183.5) < 1e-6
                    assert any(l <= math.floor(source) < r for l,r,_ in moves)
            rows.append(dict(stock=stock,format=fmt,asset_version=version,sha256=sha,
                crop_start_px=start,crop_width_px=count,source_pixels_per_mm=10,
                mm_per_layout_pixel=sx,physical_sample_px_interval=[original_lo,original_hi],
                preserved_source_centers_inclusive=[first_center,last_center],
                excluded_outside_bilinear_neighbors_with_ink=outside_ink,lower_rows=[25,50],clear_mm=[cs,ce],protect_photo_date=True,
                moves=[dict(left=l,right_exclusive=r,target_mm=t) for l,r,t in moves],
                non_base_source_pixels=sum(crop.getpixel((x,y))!=(8,8,8) for x in xs for y in range(25)),
                destination_sampling_intervals_mm=intervals))
    return dict(description='Opt-in centered printing design. Source PNGs, upper rails, scientific data, and legacy layouts are unchanged. Every visible lower mark and cropped fragment is translated whole and in source order; no inferred or newly drawn glyphs.',
                version_policy='v11 presentation revisions reuse immutable v7 PNGs; v7 snapshots deliberately retain no imprint layout.',
                source_evidence='tools/medium120/v7/authored-evidence.json',
                method='All lower-rail RGB values unequal to (8,8,8), grouping column runs across <=10 blank pixels. Half-open crop/source intervals. One-pixel sourceX guard included in clearance checks. 645 retains all 476 source pixel centers in its 47.5 mm closed physical window and uses 47.5/476 mm per layout pixel. Original sampling formula independently verifies pixel-center coverage and the HP5 right-edge interpolated sample. Existing crop convention excludes bilinear neighbors whose centers lie outside the physical window; non-base exceptions are listed explicitly.',
                layouts=rows)

if __name__ == '__main__':
    print(json.dumps(inspect(), indent=2))
