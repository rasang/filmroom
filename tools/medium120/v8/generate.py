#!/usr/bin/env python3
"""Draw the user-approved CineStill 800T 120 appearance, without source-photo pixels.

Five supplied square scans are registered approximately by visible edge indices.
This is NOT authenticated continuous-roll evidence: four short missing intervals
are explicitly reconstructed as blank. Only observed glyphs are drawn, including
clipped ones. No period is tiled and no 65mm motion KEYKODE/perforations are used.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
PPM,SS=10,8
BASE=(8,8,8,255)
INK=(225,160,48,255)  # Display approximation, not spectral/density calibration.
FONTS={
 'regular':(Path('/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed.ttf'),'6de96c09bf752e3774583eea6bc3fb0b60f1733c595f22a926c89219ba8ade43'),
 'bold':(Path('/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf'),'21f02ebcea75529d3ed9d26e089cd1b9c59a4b784119ba3a96e9bc83cf755ec7'),
}

def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def glyph_mask(text,width,height,bold):
    font=ImageFont.truetype(str(FONTS['bold' if bold else 'regular'][0]),192)
    box=font.getbbox(text)
    im=Image.new('L',(box[2]-box[0],box[3]-box[1]))
    ImageDraw.Draw(im).text((-box[0],-box[1]),text,font=font,fill=255)
    return im.resize((max(1,round(width*PPM*SS)),max(1,round(height*PPM*SS))),Image.Resampling.LANCZOS)

def compile_strip():
    source=json.loads((HERE/'source-coordinates.json').read_text())
    im=Image.new('RGBA',(8200*SS,50*SS),BASE)
    # Place the 6x6 crop near the supplied index11 frame. At source222mm, the
    # 6x17 [135,309] crop also shows the observed A without adding or moving marks.
    # Every format still crops exactly one coordinate system.
    length=source['frames'][-1]['global_covered_interval_mm'][1]
    offset=410.0-222.0
    scale=1/source['px_per_mm']
    marks=[]
    for frame in source['frames']:
        for m in frame['marks']:
            rail=0 if m['rail']=='top' else 1
            label=m['text'];x0,y0,x1,y1=m['bbox_px']
            lo,hi=m['global_x_interval_mm'];w=hi-lo;h=(y1-y0)*scale
            # Preserve photographed vertical phase relative to outer film edge;
            # normalize only the rail width to the app's nominal 2.5mm geometry.
            y=(y0-frame['bounds'][1])*scale if rail==0 else 2.5-(frame['bounds'][3]-y0)*scale
            # Source outermost ink clipped by the delivered crop stays clipped.
            py=round((rail*2.5+y)*PPM*SS);px=round((offset+lo)*PPM*SS)
            visible_w=max(1,round(w*PPM*SS));visible_h=max(1,round(h*PPM*SS))
            if 'wedge' in label:
                full_w=3.55
                full=Image.new('L',(round(full_w*PPM*SS),visible_h))
                d=ImageDraw.Draw(full);d.polygon([(0,visible_h//2),(full.width-1,0),(full.width-1,visible_h-1)],fill=255)
                # First left-edge fragment is the right-hand tail only. The 10
                # wedge is clipped at its right edge. Neither is completed.
                if label=='partial_wedge_unlabeled':
                    mask=full.crop((max(0,full.width-visible_w),0,full.width,visible_h))
                elif label=='wedge10_partial':mask=full.crop((0,0,visible_w,visible_h))
                else:mask=full.resize((visible_w,visible_h),Image.Resampling.LANCZOS)
                kind='observed_left_wedge'
            else:
                text=label.split('_')[0]
                if label=='7_partial':
                    mask=glyph_mask('7',1.15,h,True).crop((0,0,visible_w,visible_h))
                elif label=='4819_bottom_clipped':
                    mask=glyph_mask('4819',w,33*scale,True).crop((0,0,visible_w,visible_h))
                else:mask=glyph_mask(text,w,h,text not in ('CINESTILL 800T','A'))
                kind='observed_text'
            # Clip per rail before compositing. Never bleed a top glyph into the
            # packed bottom rail, including source pixels outside its film edge.
            layer=Image.new('RGBA',(mask.width,mask.height),INK)
            layer.putalpha(mask)
            visible=Image.new('RGBA',(im.width,25*SS),(0,0,0,0))
            visible.paste(layer,(px,py-rail*25*SS))
            im.alpha_composite(visible,(0,rail*25*SS))
            marks.append(dict(kind=kind,label=label,rail=rail,source_file=frame['file'],source_bbox_px=m['bbox_px'],
                x_mm=round(offset+lo,6),width_mm=w,rail_y_mm=y,height_mm=h,clipped='partial' in label or 'clipped' in label))
    im=im.resize((8200,50),Image.Resampling.LANCZOS)
    # Force all non-reference space to uniform base; source coverage is finite.
    start,end=offset,offset+length
    d=ImageDraw.Draw(im)
    d.rectangle((0,0,round(start*PPM)-1,49),fill=BASE)
    d.rectangle((round(end*PPM),0,8199,49),fill=BASE)
    # Explicit reconstruction, not source evidence. Keep filtering from painting
    # ghost ink into intervals not present in any supplied scan.
    for gap in source['gaps']:
        a,b=gap['interval_mm']
        d.rectangle((round((offset+a)*PPM),0,round((offset+b)*PPM)-1,49),fill=BASE)
    for length in (41.5,56,70,84,168):assert start<=410-(length+6)/2 and 410+(length+6)/2<=end
    assert im.getextrema()[3]==(255,255)
    evidence=dict(stock='cinestill_800t',basis='User-approved five-image visual reconstruction',
        same_roll_verified=False,physical_calibration=False,continuous_original=False,
        monochrome=False,perforated=False,defaultCenterMm=410.0,sourceCenterMm=222.0,coverageStartMm=start,coverageEndMm=end,
        source_coordinates_sha256=digest(HERE/'source-coordinates.json'),
        color=INK,base=BASE,marks=marks,
        reconstructed_blank_seams=[dict(start_mm=round(offset+g['interval_mm'][0],6),end_mm=round(offset+g['interval_mm'][1],6),length_mm=g['gap_mm']) for g in source['gaps']],
        limitations=[
            'The user approved this supplied appearance on 2026-11-06. Original author, exposure date and same-roll provenance remain unverified.',
            '56mm aperture and 850px index registration are approximate. Four unobserved seams are drawn blank; this is not evidence that the original seams were blank.',
            'Only visible marks are authored. Clipped 7, wedge fragments and bottom-clipped 4819 are not completed. No invisible A, brand or batch is added.',
            '4819 is the observed reference code, not an asserted ISO, date, universal batch or emulsion identifier.',
            'Typeface, glow-free color and phase are approximations. No user photo pixels or social watermark are packaged.',
            'This is unperforated retail-style 800T120 appearance, not 65mm5219 or 2012/2014 perforated CineStill prototype artwork.',
            'Optional imprints use reviewed centered layouts that translate existing lower marks toward the sides; original PNG bytes remain unchanged. The existing color-negative display transform is illustrative, not measured density.',
        ])
    return im,evidence

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);a=ap.parse_args()
    for path,sha in FONTS.values():assert digest(path)==sha
    a.output.mkdir(parents=True,exist_ok=True)
    im,row=compile_strip();path=a.output/'cinestill800t-rails.png';im.save(path,optimize=False,compress_level=9)
    sha=digest(path);row.update(path='medium120/v8/'+path.name,version='mf120-v8-cinestill800t-'+sha[:12],sha256=sha,bytes=path.stat().st_size,width=8200,height=50,ppm=10)
    (a.output/'authored-evidence.json').write_text(json.dumps(dict(generator='tools/medium120/v8/generate.py',fonts={p.name:s for p,s in FONTS.values()},asset=row),indent=2)+'\n')
    print(json.dumps({k:row[k] for k in ('stock','version','sha256','bytes','coverageStartMm','coverageEndMm')},indent=2))
if __name__=='__main__':main()
