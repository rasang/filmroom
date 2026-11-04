#!/usr/bin/env python3
"""Reference-guided authored rails. No third-party photographic pixels are used.

Run with --asset-root app/src/main/assets/medium120. Pillow and DejaVu Sans are
required for regeneration only; shipped runtime assets have no font dependency.
Reference positions are image-relative estimates, not film measurements.
"""
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

PPM, SS = 10, 8
BASE = (8, 8, 8, 255)
FONT = Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
BOLD = Path('/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf')
EXPECTED = {
 'portra400': '320ec685f5e9d88edf48651b723459c83ab59590a0c9d43021b23aa449326b3c',
 'provia100f': 'b42a9f00398587ddab5d0ce23145302bcf8989e40a001ba29c1cd310e40b3339',
 'velvia100': 'cac0f6246306d65351d360a2b725078eef9d235710e1827460aea627ed40bd13',
 'pro400h': '111c05d1353e9ee9aaa2fc5f8c06871038c12b2eb19efc6d282d193985831191',
}
STOCK = {'portra400':'kodak_portra_400', 'provia100f':'fujifilm_provia_100f',
         'velvia100':'fujifilm_velvia_100', 'pro400h':'fujifilm_pro_400h'}

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def font_for_cap_height(path, height_px):
    candidates = []
    for size in range(max(1, int(height_px)), int(height_px * 2) + 4):
        f = ImageFont.truetype(str(path), size)
        box = f.getbbox('H')
        candidates.append((abs(box[3] - box[1] - height_px), size, f))
    return min(candidates, key=lambda v:(v[0],v[1]))[2]

def text_mark(image, text, x_mm, rail, width_mm, height_mm, ink, bold=False):
    """Uniform glyph scale plus letter spacing, never anisotropic glyph stretch."""
    f = font_for_cap_height(BOLD if bold else FONT, height_mm * PPM * SS)
    widths = [f.getlength(c) for c in text]
    target = width_mm * PPM * SS
    spacing = (target-sum(widths))/(len(text)-1) if len(text)>1 else 0
    assert spacing >= -SS * .15, (text,spacing)
    x = x_mm * PPM * SS
    y = (rail*2.5 + (2.5-height_mm)/2) * PPM * SS
    d=ImageDraw.Draw(image)
    for c,w in zip(text,widths):
        d.text((round(x),round(y)),c,font=f,fill=ink,anchor='lt')
        x+=w+spacing

def portra400():
    # Pablo Di Prima's same continuous strip, FLOS Stories 3, PDF page 38.
    # Original extracted panel crop rotated 90deg: 1650 x 490 px.
    # Observed cross-film image aperture ~416px; nominal model aperture 56mm.
    ppm_ref=416/56
    center_px=950
    mm=lambda x: 410+(x-center_px)/ppm_ref
    image=Image.new('RGBA',(8200*SS,50*SS),BASE)
    ink=(211,150,112,255)  # Preserve illustrative prior color, not sampled scan RGB.
    marks=[]
    for number,x in zip(range(48,53),[34,412,781,1152,1520]):
        width=2.48; start=mm(x)-width/2
        text_mark(image,str(number),start,0,width,1.40,ink,bold=True)
        marks.append(dict(kind='number',rail=0,text=str(number),source_center_px=x,
                          x_mm=start,width_mm=width,height_mm=1.40))
    for x in [109,485,854,1221]:
        width=136/ppm_ref
        text_mark(image,'KODAK PORTRA 400',mm(x),0,width,1.08,ink)
        marks.append(dict(kind='stock_text',rail=0,text='KODAK PORTRA 400',
                          source_start_px=x,x_mm=mm(x),width_mm=width,height_mm=1.08))
    # The photograph ends during the final stock legend. Only KODAK is visible;
    # clip that observed partial legend at the shorter two-edge coverage boundary.
    x=1594; observed_width=50/ppm_ref; clipped_width=(1625-x)/ppm_ref
    text_mark(image,'KODAK',mm(x),0,observed_width,1.08,ink)
    marks.append(dict(kind='partial_stock_text',rail=0,text='KODAK',
                      source_start_px=x,source_clip_end_px=1625,x_mm=mm(x),
                      width_mm=clipped_width,height_mm=1.08,clipped=True))
    for x,num,num_x in [(34,None,None),(254,7,273),(500,None,None),
                        (740,8,760),(959,None,None),(1201,9,1220),(1445,None,None)]:
        w,h=1.70,1.10; start=mm(x)-w/2; y=2.5+(2.5-h)/2
        d=ImageDraw.Draw(image)
        d.polygon([(round(start*PPM*SS),round(y*PPM*SS)),
                   (round((start+w)*PPM*SS),round((y+h/2)*PPM*SS)),
                   (round(start*PPM*SS),round((y+h)*PPM*SS))],fill=ink)
        marks.append(dict(kind='solid_right_triangle',rail=1,source_center_px=x,
                          x_mm=start,width_mm=w,height_mm=h))
        if num is not None:
            width=1.28; start=mm(num_x)-width/2
            text_mark(image,str(num),start,1,width,1.40,ink,bold=True)
            marks.append(dict(kind='number',rail=1,text=str(num),source_center_px=num_x,
                              x_mm=start,width_mm=width,height_mm=1.40))
    # Unknown atlas outside the documented continuous two-edge segment stays blank.
    d=ImageDraw.Draw(image)
    d.rectangle((0,0,round(mm(23)*PPM*SS)-1,50*SS),fill=BASE)
    d.rectangle((round(mm(1625)*PPM*SS),0,8200*SS,50*SS),fill=BASE)
    result=image.resize((8200,50),Image.Resampling.LANCZOS)
    evidence=dict(source_url='https://www.light11.de/download/Flos_Stories_Ausgabe_3.pdf#page=38',
       corroborating_source='https://note.com/sykmelo/n/na4b58d3349a9',
       reference_pixels_per_mm_estimate=ppm_ref,reference_center_px=center_px,
       calibration='416px photographed cross aperture mapped to nominal 56mm; no ruler measurement',
       coverageStartMm=round(mm(23),6),coverageEndMm=round(mm(1625),6),
       semantic_coverage='One continuous three-frame strip: upper 48–52; lower 7–9 and interleaved independent solid right triangles.',
       limitations=['Typography, pitch, phase and display RGB remain approximations.',
                    'No 56 inferred; no third-party photograph pixels shipped.',
                    'Unknown portions outside coverage are not available for new crops.'],marks=marks)
    return result,evidence

def provia(original):
    result=original.copy(); marks=[]
    # Existing local master coordinates retained; replace the documented shape only.
    # Lower RDPIII+arrow+number groups occur every 417px in the original.
    for n in range(19):
        old_x=222+n*417
        left=old_x-5; top=31
        patch=Image.new('RGBA',(18*SS,15*SS),BASE);d=ImageDraw.Draw(patch)
        points=[(1,5),(6,5),(6,2),(16,7.5),(6,13),(6,10),(1,10),(1,5)]
        d.line([(round(x*SS),round(y*SS)) for x,y in points],fill=(218,145,53,255),width=SS)
        result.paste(patch.resize((18,15),Image.Resampling.LANCZOS),(left,top))
        marks.append(dict(kind='outlined_right_arrow_with_shaft',rail=1,
                          number=n+1,x_mm=left/PPM,width_mm=1.8,height_mm=1.5))
    return result,dict(source_url='https://asset.fujifilm.com/www/us/files/2025-06/76ccbf624f1b5db023c093c94917708f/films_provia-100f_datasheet_01.pdf#page=4',
      coverageStartMm=0,coverageEndMm=820,
      semantic_coverage='Official 120 diagram: two printed edges; numbered 1–19, outlined right arrow with rectangular shaft.',
      limitations=['Only arrow geometry corrected; existing illustrative spacing and color retained.',
                   'Coverage is authored geometry, not a physically measured full roll.',
                   'Unknown production codes remain omitted.'],marks=marks)

def add_fuji(original,name):
    result=original.copy()
    if name=='velvia100':
        box=(517,5,558,20); dest_x=1341; number=4
        url='https://asset.fujifilm.com/master/emea/files/2020-10/2f3c7f90a0b0c6e605e84f98b7d489c2/films_velvia-100_datasheet_01.pdf#page=6'
    else:
        box=(513,5,554,20); dest_x=7449; number=19
        url='https://www.fujifilm.com.hk/products/professional_films/pdf/pro_400h_datasheet.pdf#page=6'
    # Reuse only our own authored FUJI lettering, not external film pixels.
    result.paste(original.crop(box),(dest_x,box[1]))
    extra_marks=[]
    if name=='pro400h':
        # Safelight Berlin's actual same120 strip clearly shows FUJI before6 and8.
        # Add only those observed instances, not an inferred even-number cadence.
        actual_url='https://safelightberlin.com/cdn/shop/products/120-color-film-developing-116907.jpg?v=1646324668'
        for n,x in [(6,2145),(8,2961)]:
            result.paste(original.crop(box),(x,box[1]))
            extra_marks.append(dict(kind='stock_text',rail=0,text='FUJI',before_number=n,
                x_mm=x/10,width_mm=4.1,height_mm=1.5,source_url=actual_url))
        dot=Image.new('RGBA',(5*SS,5*SS),BASE)
        ImageDraw.Draw(dot).ellipse((SS,SS,3*SS,3*SS),fill=(218,145,53,255))
        result.paste(dot.resize((5,5),Image.Resampling.LANCZOS),(2201,14))
        extra_marks.append(dict(kind='index_disambiguation_dot',rail=0,after_number=6,
            x_mm=220.1,y_mm=1.4,width_mm=.5,height_mm=.5,source_url=actual_url))
    # Official diagrams explicitly show the last index as 19., including the dot.
    dot_left = 7587 if name=='velvia100' else 7515
    dot=Image.new('RGBA',(5*SS,5*SS),BASE)
    ImageDraw.Draw(dot).ellipse((SS,SS,3*SS,3*SS),fill=(218,145,53,255))
    result.paste(dot.resize((5,5),Image.Resampling.LANCZOS),(dot_left,14))
    return result,dict(source_url=url,coverageStartMm=0,coverageEndMm=820,
      semantic_coverage=f'Additional explicitly depicted FUJI before {number}; existing FUJI before2 preserved; terminal19. dot restored.' + (' Actual Safelight120 photo independently confirms FUJI6./8, added locally without full-period inference.' if name=='pro400h' else ''),
      limitations=['No every-even-number FUJI cadence inferred.',
                   'Remaining unknown manufacturing marks/phase are not claimed physically blank.',
                   'Coverage remains authored geometry, not a physically measured full roll.'],
      marks=[dict(kind='stock_text',rail=0,text='FUJI',before_number=number,
                  x_mm=dest_x/10,width_mm=(box[2]-box[0])/10,height_mm=(box[3]-box[1])/10),
             dict(kind='terminal_index_dot',rail=0,after_number=19,
                  x_mm=dot_left/10,y_mm=1.4,width_mm=.5,height_mm=.5)] + extra_marks)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--asset-root',type=Path,required=True)
    ap.add_argument('--output',type=Path);args=ap.parse_args()
    out=args.output or args.asset_root/'v2';out.mkdir(parents=True,exist_ok=True)
    rows=[]
    for name,sha in EXPECTED.items():
        source=args.asset_root/f'{name}-rails.png'; assert digest(source)==sha,name
        if name=='portra400': im,evidence=portra400()
        elif name=='provia100f': im,evidence=provia(Image.open(source).convert('RGBA'))
        else: im,evidence=add_fuji(Image.open(source).convert('RGBA'),name)
        path=out/f'{name}-rails.png';im.save(path,optimize=False,compress_level=9)
        h=digest(path)
        row=dict(stock=STOCK[name],path=f'medium120/v2/{path.name}',width=im.width,height=im.height,
          ppm=PPM,bytes=path.stat().st_size,sha256=h,version=f'mf120-v2-{name}-{h[:12]}',
          defaultCenterMm=410.0,legacy_sha256=sha,semantic_scope='full_observed_segment' if name=='portra400' else 'delta_from_legacy',**evidence)
        assert im.size==(8200,50) and im.getextrema()[3]==(255,255)
        for length in [41.5,56,70,84,168]:
            assert 410-(length+6)/2>=row['coverageStartMm']
            assert 410+(length+6)/2<=row['coverageEndMm']
        im.crop((3230,0,4970,50)).save(out/f'{name}-6x17-review.png')
        rows.append(row)
    (out/'authored-evidence.json').write_text(json.dumps(dict(
       generator='tools/medium120/v2/observed/generate.py',
       font_sha256={p.name:digest(p) for p in [FONT,BOLD]},
       physical_calibration=False,assets=rows),indent=2,ensure_ascii=False)+'\n')
    print(json.dumps([{k:r[k] for k in ['stock','path','version','width','height','ppm','bytes','sha256','defaultCenterMm','coverageStartMm','coverageEndMm']} for r in rows],indent=2))

if __name__=='__main__': main()
