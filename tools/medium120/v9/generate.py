#!/usr/bin/env python3
"""Acros II 120 appearance: inspected local marks plus explicit periodic extension.

No source-photo pixels. The brand/code period and non-observed indices are model
assumptions, not a fabricated claim of continuous physical evidence.
"""
from __future__ import annotations
import argparse,hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parent
PPM,SS=10,8
BASE,INK=(8,8,8,255),(185,185,185,255)
FONT=Path('/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed.ttf')
FONT_SHA='6de96c09bf752e3774583eea6bc3fb0b60f1733c595f22a926c89219ba8ade43'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def glyph(text,w,h):
 # Reference uses a serifed, connected Roman II, unlike the sans-serif legend.
 if text=='FUJI 100 ACROS II':
  m=Image.new('L',(round(w*PPM*SS),round(h*PPM*SS)))
  body=glyph('FUJI 100 ACROS',w-1.75,h);m.paste(body,(0,0))
  dr=ImageDraw.Draw(m);x=round((w-1.0)*PPM*SS);right=m.width-1
  stroke=max(1,round(.11*PPM*SS));stem=max(1,round(.12*PPM*SS))
  dr.rectangle((x,0,right,stroke-1),fill=255);dr.rectangle((x,m.height-stroke,right,m.height-1),fill=255)
  for sx in (x+round(.18*PPM*SS),right-round(.18*PPM*SS)-stem+1):dr.rectangle((sx,0,sx+stem-1,m.height-1),fill=255)
  return m
 f=ImageFont.truetype(str(FONT),192);box=f.getbbox(text)
 m=Image.new('L',(box[2]-box[0],box[3]-box[1]));ImageDraw.Draw(m).text((-box[0],-box[1]),text,font=f,fill=255)
 return m.resize((max(1,round(w*PPM*SS)),max(1,round(h*PPM*SS))),Image.Resampling.LANCZOS)
def compile_strip():
 s=json.loads((HERE/'source-coordinates.json').read_text());scale=1/s['px_per_mm'];model=s['model']
 offset=model['defaultCenterMm']-model['sourceCenterMm'];start=model['coverageStartMm'];end=model['coverageEndMm']
 im=Image.new('RGBA',(8200*SS,50*SS),BASE);marks=[]
 def put(label,rail,box,phase_px=0,observed=False):
  a,b,c,d=box;x=offset+(a+phase_px)*scale;w=(c-a)*scale;h=(d-b)*scale
  if x+w<=start or x>=end:return
  # Preserve relative measured vertical phase, within the app's nominal2.5mm rails.
  y=b*scale if rail==0 else 2.5-(638-b)*scale
  if label.startswith('wedge'):
   mask=Image.new('L',(round(w*PPM*SS),round(h*PPM*SS)));dr=ImageDraw.Draw(mask)
   dr.polygon([(0,mask.height//2),(mask.width-1,0),(mask.width-1,mask.height-1)],fill=255);kind='narrow_left_wedge'
  else:mask=glyph(label,w,h);kind='text'
  layer=Image.new('RGBA',mask.size,INK);layer.putalpha(mask)
  clip=Image.new('RGBA',(im.width,25*SS),(0,0,0,0));clip.paste(layer,(round(x*PPM*SS),round(y*PPM*SS)))
  im.alpha_composite(clip,(0,rail*25*SS))
  marks.append(dict(kind=kind,label=label,rail=rail,x_mm=x,width_mm=w,rail_y_mm=y,height_mm=h,source_bbox_px=box,phase_px=phase_px,evidence='primary_observed' if observed else 'periodic_approximation'))
 # Use both independently inspected primary indices. Further indices use the
 # number4 template and the single observed4->5 pitch, not invented source boxes.
 for n in range(1,20):
  observed=n in (4,5);rows=s['primary_marks'][0:2] if n!=5 else s['primary_marks'][2:4]
  phase=(n-4)*s['index_pitch_px'] if n!=5 else 0
  for row in rows:put(str(n) if row['text'][0].isdigit() else 'wedge'+str(n),0,row['bbox_px'],phase,observed)
 for k in range(-4,5):
  for row in s['primary_marks'][4:]:put(row['text'],1,row['bbox_px'],k*s['brand_period_px'],k==0)
 # Only this finite authored interval may be used. Unknown820mm atlas portions
 # are unavailable, and runtime coverage validation rejects shifted crops.
 im=im.resize((8200,50),Image.Resampling.LANCZOS);dr=ImageDraw.Draw(im)
 dr.rectangle((0,0,round(start*PPM)-1,49),fill=BASE);dr.rectangle((round(end*PPM),0,8199,49),fill=BASE)
 assert im.getextrema()[3]==(255,255)
 for length in (41.5,56,70,84,168):assert start<=410-(length+6)/2 and 410+(length+6)/2<=end
 evidence=dict(stock='fujifilm_neopan_acros_100_ii',monochrome=True,perforated=False,defaultCenterMm=410.0,coverageStartMm=start,coverageEndMm=end,sourceCenterMm=30.0,primaryObservedStartMm=offset,primaryObservedEndMm=offset+612*scale,physical_calibration=False,continuous_source=False,periodic_extension=True,indexPitchMm=s['index_pitch_px']*scale,inferredBrandPeriodMm=s['brand_period_px']*scale,source_coordinates_sha256=digest(HERE/'source-coordinates.json'),source_hashes_sha256=digest(HERE/'source-hashes.json'),base=BASE,ink=INK,marks=marks,limitations=[
 'Primary source is the LEFT AcrosII panel. The RIGHT originalAcros panel is excluded, including its170,12,100ACROS and dot-matrix marks.',
 '4,5,narrow left wedges,FUJI100ACROSII and4586 are visible.4586 is an observed code, not an asserted universal batch or emulsion identifier.',
 'Secondary source separately shows13 andA. It is not spliced into a same-roll panorama; A is not repeated because its cadence is unverified.',
 'Only about59.8mm is directly observed in the primary. The180mm authored model uses explicitly inferred sequential indices and a twice-index brand/code period for all five formats.',
 'Cap height,typeface,gray value,rail width,phase,56mm aperture and3mm end allowance are approximations, not factory metrology.',
 'No photograph pixels, first-generationAcros markings, perforations, orange mask or scientific response data are included or changed.',
 'Optional centered imprint layouts separately reposition copied lower-rail marks outward; the immutable source asset does not change. Empty authored space outside primary evidence is not evidence of physically blank film.'
 ])
 return im,evidence

def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 assert digest(FONT)==FONT_SHA
 im,row=compile_strip();path=a.output/'acrosii-rails.png';im.save(path,optimize=False,compress_level=9);sha=digest(path)
 row.update(path='medium120/v9/'+path.name,version='mf120-v9-acrosii-'+sha[:12],sha256=sha,bytes=path.stat().st_size,width=8200,height=50,ppm=PPM)
 (a.output/'authored-evidence.json').write_text(json.dumps(dict(generator='tools/medium120/v9/generate.py',font_sha256={FONT.name:FONT_SHA},asset=row),indent=2)+'\n')
 print(json.dumps({k:row[k] for k in ('stock','version','sha256','bytes','coverageStartMm','coverageEndMm')},indent=2))
if __name__=='__main__':main()
