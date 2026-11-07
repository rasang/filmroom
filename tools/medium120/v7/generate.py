#!/usr/bin/env python3
"""Author finite B&W 120 rails from inspected references, never photograph pixels.

The positions below are manual observations. The nominal 56 mm aperture sets an
approximate scale, not a physical measurement. Never repeat these finite strips.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

PPM, SS = 10, 8
BASE, INK = (8, 8, 8, 255), (210, 210, 210, 255)
FONT = Path('/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf')
FONT_SHA = '21f02ebcea75529d3ed9d26e089cd1b9c59a4b784119ba3a96e9bc83cf755ec7'

def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def text_mark(im, text, x, rail, width, height):
    target = height * PPM * SS
    fonts = [(abs((f := ImageFont.truetype(str(FONT), n)).getbbox('H')[3] -
                  f.getbbox('H')[1] - target), n, f)
             for n in range(max(1, int(target)), int(target*2)+4)]
    font = min(fonts, key=lambda r: (r[0], r[1]))[2]
    widths = [font.getlength(c) for c in text]
    tracking = (width*PPM*SS-sum(widths))/max(1, len(text)-1)
    assert len(text) == 1 or tracking >= -SS*.15, (text, tracking)
    draw = ImageDraw.Draw(im)
    px = x*PPM*SS
    py = (rail*2.5+(2.5-height)/2)*PPM*SS
    for c, w in zip(text, widths):
        draw.text((round(px), round(py)), c, font=font, fill=INK, anchor='lt')
        px += w+tracking

def triangle(im, x, rail, width, height, left=False):
    y = rail*2.5+(2.5-height)/2
    points = [(x+width, y), (x, y+height/2), (x+width, y+height)] if left else [
        (x, y), (x+width, y+height/2), (x, y+height)]
    ImageDraw.Draw(im).polygon([(round(a*PPM*SS), round(b*PPM*SS)) for a,b in points], fill=INK)

def compile_strip(name):
    im = Image.new('RGBA', (8200*SS, 50*SS), BASE)
    marks = []
    if name == 'hp5plus':
        # Photograph 2, third unbroken strip. Photograph 3 was NOT used: glare
        # obscures 13 and its candidate coordinate notes were inaccurate.
        scale, center, bounds = 56/201, 362.5, (32, 693)
        source = 'https://www.photrio.com/forum/attachments/2016-03-01-23-53-50-jpg.151747/'
        stock = 'ilford_hp5_plus_400'
        identity = 'ILFORD HP5 PLUS; observed batch 4274; upper 10–14; lower A.'
        top = [(str(n), x, 2.1, 1.12) for n,x in zip(range(10,15), [85,230,374,521,673])]
        bottom = [('4274',60,4.7,1.12), ('ILFORD HP5 PLUS',160,18.0,1.12),
                  ('4274',304,4.7,1.12), ('ILFORD HP5 PLUS',405,18.0,1.12),
                  ('A',505,.9,1.0), ('4274',553,4.7,1.12),
                  ('ILFORD HP5',656,13.0,1.12)]
        arrows = [(x,0,3.34,.84,True) for x in [97,241,386,532,682]]
        calibration = 'Mean photographed square aperture width approximately 201 px mapped to nominal 56 mm. Perspective and sleeve curvature are not metrology.'
        limits = ['The partial terminal legend is only ILFORD HP5, clipped at the observed boundary; PLUS is not inferred.',
                  '4274 is a source-roll batch, not ISO and not a universal HP5 code.',
                  'The source discusses edge misalignment. Vertical placement is normalized inside nominal rails and is not a claim about all manufactured rolls.']
    elif name == 'tmax100':
        scale, center, bounds = 56/417, 862.5, (212,1513)
        source = 'https://dspace.library.uvic.ca/bitstream/handle/1828/3215/Smith_FinalDISS_small.pdf#page=76'
        stock = 'kodak_tmax_100'
        identity = 'KODAK 100TMX; upper 46–49; lower 5–7 and independent right triangles.'
        top = [(str(n), x, 2.45, 1.28) for n,x in zip(range(46,50), [223,589,953,1319])]
        top += [('KODAK 100TMX', x, 15.4, 1.20) for x in [346,712,1078]]
        top += [('KODAK 1',1443,9.3,1.20)]
        bottom = [(str(n),x,1.25,1.14) for n,x in [(5,311),(6,764),(7,1193)]]
        arrows = [(x,1,1.9,1.0,False) for x in [289,522,742,953,1170,1406]]
        calibration = 'Photographed panoramic image aperture approximately 417 px mapped to nominal 56 mm. The resulting 174.715 mm coverage is only 0.715 mm longer than the 6x17 model crop; there is no physical calibration.'
        limits = ['Right-clipped stock legend is only KODAK 1; unseen trailing letters are not invented.',
                  'This finite panoramic print supports a nominal approximate crop, not measured factory pitch or a full roll.',
                  'A new physical scan could revise the apparent scale. Such a correction must append a new immutable asset version.']
    else:
        # Right-hand actual strip in the original 2268x4032 photograph. Inspection
        # coordinates below rotate it clockwise and uniformly resize to 2400x1350.
        # Four contiguous 645 images, not a collage and not a repeated schematic.
        scale, center, bounds = 56/410, 1166.5, (451,1882)
        source = 'https://fotophilosophy.wordpress.com/wp-content/uploads/2024/07/img_1469-1.jpg'
        stock = 'kodak_tri_x_400'
        identity = 'KODAK 400TX; upper 45–48; lower 4–6 and independent right triangles. Still-negative 400TX, not reversal 7266.'
        top = [(str(n),x,2.45,1.20) for n,x in zip(range(45,49),[727,1090,1444,1798])]
        top += [('KODAK 400TX',x,13.9,1.20) for x in [504,862,1220,1576]]
        bottom = [(str(n),x,1.25,1.14) for n,x in [(4,743),(5,1190),(6,1633)]]
        arrows = [(x,1,1.9,1.0,False) for x in [489,723,934,1171,1399,1615,1820]]
        calibration = 'Right strip, rotated clockwise then uniformly scaled to 2400x1350 for inspection. Approximately 410 px cross-film aperture mapped to nominal 56 mm. Source is a photographed expired 120 roll, not a calibrated scan.'
        limits = ['Four observed contiguous 645 frames provide 195.454 mm nominal coverage. No source period is extrapolated.',
                  'The independent Kodak 2003 catalog describes two-inch legends and dual numbering, but its non-metric short schematic is not tiled.',
                  'No batch/emulsion code is visible in the selected strip and none is invented. Exposure/fog and vertical strip slope are not reproduced.']
    mm = lambda px: 410+(px-center)*scale
    for rail, rows in enumerate([top,bottom]):
        for label, px, w, h in rows:
            text_mark(im,label,mm(px),rail,w,h)
            marks.append(dict(kind='text',rail=rail,text=label,source_x_px=px,x_mm=mm(px),width_mm=w,height_mm=h))
    for px,rail,w,h,left in arrows:
        triangle(im,mm(px),rail,w,h,left)
        marks.append(dict(kind='solid_left_triangle' if left else 'solid_right_triangle',rail=rail,source_x_px=px,x_mm=mm(px),width_mm=w,height_mm=h))
    start,end = map(mm,bounds)
    d = ImageDraw.Draw(im)
    d.rectangle((0,0,round(start*PPM*SS)-1,50*SS), fill=BASE)
    d.rectangle((round(end*PPM*SS),0,8200*SS,50*SS), fill=BASE)
    im = im.resize((8200,50),Image.Resampling.LANCZOS)
    assert im.getextrema()[3] == (255,255)
    for length in [41.5,56,70,84,168]:
        assert 410-(length+6)/2 >= start and 410+(length+6)/2 <= end
    evidence = dict(stock=stock,source_url=source,identity=identity,calibration=calibration,
                    source_bounds_px=bounds,reference_center_px=center,mm_per_source_px=scale,
                    coverageStartMm=start,coverageEndMm=end,defaultCenterMm=410.0,
                    monochrome=True,physical_calibration=False,
                    limitations=limits+['Typography, cap height, pitch, phase and display gray are approximations.',
                    'No third-party photograph pixels are packaged. Blank atlas outside finite coverage means unknown and is unavailable to cropping.'],marks=marks)
    return im,evidence

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--output',type=Path,required=True)
    args=ap.parse_args()
    # Version-pinned typography, including future regenerations.
    assert digest(FONT)==FONT_SHA, (digest(FONT), 'Unexpected DejaVu font')
    args.output.mkdir(parents=True,exist_ok=True)
    rows=[]
    for name in ['hp5plus','tmax100','trix400']:
        im,row=compile_strip(name)
        path=args.output/f'{name}-rails.png'
        im.save(path,optimize=False,compress_level=9)
        h=digest(path)
        row.update(path=f'medium120/v7/{path.name}',version=f'mf120-v7-{name}-{h[:12]}',
                   width=8200,height=50,ppm=10,bytes=path.stat().st_size,sha256=h)
        im.crop((3230,0,4970,50)).save(args.output/f'{name}-review.png')
        rows.append(row)
    (args.output/'authored-evidence.json').write_text(json.dumps(dict(generator='tools/medium120/v7/generate.py',
        font_sha256={FONT.name:FONT_SHA},assets=rows),indent=2)+'\n')
    print(json.dumps([{k:r[k] for k in ['stock','path','version','bytes','sha256','coverageStartMm','coverageEndMm']} for r in rows],indent=2))

if __name__=='__main__':
    main()
