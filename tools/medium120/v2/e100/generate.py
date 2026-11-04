#!/usr/bin/env python3
"""New procedural E100 rails from same-strip semantic landmarks, never photo pixels."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json,hashlib
ROOT=Path(__file__).resolve().parent
FONT='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
BASE=(8,8,8,255);INK=(220,216,207,255);PPM=10;SS=8
source=json.loads((ROOT/'source-calibration.json').read_text())
knots=source['calibration_knots']
def physical(x):
    for (x0,y0),(x1,y1) in zip(knots,knots[1:]):
        if x<=x1:return y0+(x-x0)*(y1-y0)/(x1-x0)
    x0,y0=knots[-2];x1,y1=knots[-1]
    return y1+(x-x1)*(y1-y0)/(x1-x0)
lo,hi=source['usable_native_interval'];mid=(physical(lo)+physical(hi))/2
mm=lambda x:410+physical(x)-mid
font=ImageFont.truetype(FONT,760)
def metrics(t,h,tr):
    b=font.getbbox(t);r=h/(b[3]-b[1]);return sum(font.getlength(c) for c in t)*r+(len(t)-1)*tr
marks=[]
arrow_association={107.5:None,267:7,446:None,620:8,779:None,959.5:9}
for landmark in source['marks']:
    text=landmark['text'];arrow=text=='arrow';rail='top' if landmark['rail']=='brand' else 'bottom'
    h=.95 if arrow else 1.20;tracking=.18 if text=='KODAK E100' else .03
    w=1.85 if arrow else metrics(text,h,tracking)
    m=dict(kind='solid_right_triangle' if arrow else ('stock_text' if text=='KODAK E100' else 'number'),text=None if arrow else text,number=int(text) if text.isdigit() else None,rail=rail,x_mm=mm(landmark['x_native'])-w/2,y_mm=(2.5-h)/2,width_mm=w,height_mm=h,tracking_mm=0 if arrow else tracking,source_landmark=dict(coordinate_image='Native clockwise-rotated crop(560,82,907,1117),1035x347',center_x_px=landmark['x_native'],center_y_px=landmark['y_native']),source_url=source['source'])
    if arrow:m.update(direction='right',associated_number=arrow_association[landmark['x_native']])
    marks.append(m)
spec=dict(stock='kodak_ektachrome_100',semantic_mode='full_observed_segment',width=8200,height=50,pixelsPerMm=10,railHeightMm=2.5,defaultCenterMm=410,coverageStartMm=mm(lo),coverageEndMm=mm(hi),baseRGBA=BASE,inkRGBA=INK,source_url=source['source'],source_native_interval=[lo,hi],calibration_knots=knots,calibration='Piecewise nominal56mm aperture calibration; whole safe span centered410. Photo-derived approximate positions, not factory measurement. ~1mm landmark uncertainty.',conservative_single_scale_length_mm=(hi-lo)/(306/56),font=FONT,font_sha256=hashlib.sha256(Path(FONT).read_bytes()).hexdigest(),font_note='Uniform DejaVu Sans Bold glyph scaling plus explicit tracking. Approximate typeface/height/spacing. Prior illustrative E100 RGB retained; no source photo RGB sampled.',limitations=['Only this one continuous48–51/7–9 strip reconstructed. No marks borrowed from neighboring strips or other rolls.','No full-roll numbering or unseen terminal label inferred. Atlas outside observed coverage is blank and unavailable for crops.','y_mm is local to its2.5mm rail; top rows0–24,bottom25–49.'],marks=marks)
(ROOT/'e100.json').write_text(json.dumps(spec,indent=2)+'\n')
im=Image.new('RGBA',(8200*SS,50*SS),BASE);d=ImageDraw.Draw(im)
for m in marks:
 x=m['x_mm']*PPM*SS;y=(m['y_mm']+(2.5 if m['rail']=='bottom' else 0))*PPM*SS
 w=m['width_mm']*PPM*SS;h=m['height_mm']*PPM*SS
 if m['kind']=='solid_right_triangle':d.polygon([(x,y),(x+w,y+h/2),(x,y+h)],fill=INK)
 else:
  box=font.getbbox(m['text']);ratio=h/(box[3]-box[1]);tracking=m['tracking_mm']*PPM*SS/ratio
  canvas=Image.new('RGBA',(int(sum(font.getlength(c) for c in m['text'])+tracking*(len(m['text'])-1))+8,box[3]-box[1]+2));cd=ImageDraw.Draw(canvas);xx=0
  for c in m['text']:cd.text((xx,-box[1]),c,font=font,fill=INK);xx+=font.getlength(c)+tracking
  canvas=canvas.resize((round(canvas.width*ratio),round(canvas.height*ratio)),Image.Resampling.LANCZOS)
  im.alpha_composite(canvas,(round(x),round(y)))
im=im.resize((8200,50),Image.Resampling.LANCZOS);im.save(ROOT/'e100-rails.png',compress_level=9)
review=Image.new('RGBA',(1740,610),BASE);review.paste(im.crop((3230,0,4970,25)),(0,0));review.paste(im.crop((3230,25,4970,50)),(0,585));ImageDraw.Draw(review).rectangle((30,25,1709,584),fill=(55,59,62,255));review.save(ROOT/'e100-6x17-review.png')
# Full observed strip as two rail lines, uniformly doubled, not stretched in one axis.
im.crop((round(spec['coverageStartMm']*10),0,round(spec['coverageEndMm']*10),50)).resize((round((round(spec['coverageEndMm']*10)-round(spec['coverageStartMm']*10))*2),100),Image.Resampling.NEAREST).save(ROOT/'e100-full-segment-review.png')
assert im.mode=='RGBA' and im.size==(8200,50) and im.getchannel('A').getextrema()==(255,255)
intervals={}
for label,w in [('645',47.5),('6x6',62),('6x7',76),('6x9',90),('6x17',174)]:
 a,b=410-w/2,410+w/2;assert spec['coverageStartMm']<=a and b<=spec['coverageEndMm'];intervals[label]=[a,b]
assert {m['number'] for m in marks if m['rail']=='top' and m['kind']=='number'}=={48,49,50,51}
assert {m['number'] for m in marks if m['rail']=='bottom' and m['kind']=='number'}=={7,8,9}
assert sum(m['kind']=='stock_text' for m in marks)==3
assert sum(m['kind']=='solid_right_triangle' and m['associated_number'] is None for m in marks)==3
assert sum(m['kind']=='solid_right_triangle' for m in marks)==6
for m in marks:
 assert spec['coverageStartMm']<=m['x_mm'] and m['x_mm']+m['width_mm']<=spec['coverageEndMm']
 assert 0<=m['y_mm'] and m['y_mm']+m['height_mm']<=2.5
 x0=round(m['x_mm']*10);y0=round((m['y_mm']+(2.5 if m['rail']=='bottom' else 0))*10)
 region=im.crop((x0,y0,x0+max(1,round(m['width_mm']*10)),y0+max(1,round(m['height_mm']*10))))
 assert any(p!=BASE for p in region.getdata())
f=ROOT/'e100-rails.png';data=f.read_bytes();sha=hashlib.sha256(data).hexdigest()
manifest=dict(stock=spec['stock'],file=f.name,version='mf120-v2-e100-'+sha[:12],sha256=sha,byteCount=len(data),width=8200,height=50,pixelsPerMm=10,coverageStartMm=spec['coverageStartMm'],coverageEndMm=spec['coverageEndMm'],defaultCenterMm=410,semantic_mode=spec['semantic_mode'],tests='PASS: dimensions,opaque alpha,five crop intervals,exact numbers,three brands,six arrows/three independent,all mark boundaries and ink presence',crop_intervals_mm=intervals)
(ROOT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest,indent=2))
