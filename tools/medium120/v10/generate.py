#!/usr/bin/env python3
"""Authored PKR6033 120 appearance; no photograph pixels or135 artwork."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import argparse,hashlib,json
HERE=Path(__file__).resolve().parent
PPM,SS=10,8
BASE,INK=(8,8,8,255),(171,199,177,255)
FONTS={'regular':Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'),'bold':Path('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf')}
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def glyph(text,w,h,bold=False):
 f=ImageFont.truetype(str(FONTS['bold' if bold else 'regular']),192);box=f.getbbox(text)
 m=Image.new('L',(box[2]-box[0],box[3]-box[1]));ImageDraw.Draw(m).text((-box[0],-box[1]),text,font=f,fill=255)
 return m.resize((round(w*PPM*SS),round(h*PPM*SS)),Image.Resampling.LANCZOS)
def compile_strip():
 s=json.loads((HERE/'source-coordinates.json').read_text());scale=1/s['px_per_mm'];model=s['model']
 offset=410-s['source_center_px']*scale;start,end=model['coverageStartMm'],model['coverageEndMm']
 im=Image.new('RGBA',(8200*SS,50*SS),BASE);marks=[]
 for k in range(-3,4):
  for row in s['primary_marks']:
   a,b,c,d=row['bbox_px'];rail=row['rail'];label=str(9+k) if row['text']=='9' else row['text']
   phase=k*(s['brand_period_px'] if rail==0 else s['index_period_px']);x=offset+(a+phase)*scale;w=(c-a)*scale;h=(d-b)*scale
   if x+w<start or x>end:continue
   if row['text']=='9' and len(label)>1:w*=1.75
   y=b*scale if rail==0 else 2.5-(1400-b)*scale
   kind=row['kind'];sz=(round(w*PPM*SS),round(h*PPM*SS));layer=Image.new('RGBA',sz,(0,0,0,0))
   if kind=='reverse_panel':
    layer=Image.new('RGBA',sz,INK);m=glyph(label,w-.6,h-.26);ink=Image.new('RGBA',m.size,BASE);ink.putalpha(m);layer.alpha_composite(ink,(round(.3*PPM*SS),round(.13*PPM*SS)))
   elif kind=='triangle':
    mask=Image.new('L',sz);dr=ImageDraw.Draw(mask);sw=max(1,round(.13*PPM*SS));points=[(sw//2,sw//2),(sz[0]-1-sw//2,sz[1]//2),(sw//2,sz[1]-1-sw//2),(sw//2,sw//2)]
    dr.line(points,fill=255,width=sw,joint='curve');layer=Image.new('RGBA',sz,INK);layer.putalpha(mask)
   else:
    mask=glyph(label,w,h,True);layer=Image.new('RGBA',sz,INK);layer.putalpha(mask)
   clip=Image.new('RGBA',(im.width,25*SS),(0,0,0,0));clip.paste(layer,(round(x*PPM*SS),round(y*PPM*SS)));im.alpha_composite(clip,(0,rail*25*SS))
   marks.append(dict(label=label,kind=kind,rail=rail,x_mm=x,width_mm=w,rail_y_mm=y,height_mm=h,source_bbox_px=row['bbox_px'],phase_px=phase,evidence='primary_observed' if k==0 else 'periodic_approximation'))
 im=im.resize((8200,50),Image.Resampling.LANCZOS);dr=ImageDraw.Draw(im);dr.rectangle((0,0,3199,49),fill=BASE);dr.rectangle((5000,0,8199,49),fill=BASE)
 row=dict(stock='kodak_kodachrome_64',monochrome=False,film_kind='reversal',perforated=False,defaultCenterMm=410.0,coverageStartMm=start,coverageEndMm=end,primaryObservedStartMm=offset,primaryObservedEndMm=offset+1349*scale,physical_calibration=False,continuous_source=False,periodic_extension=True,inferredIndexPitchMm=s['index_period_px']*scale,approximateBrandPitchMm=s['brand_period_px']*scale,source_coordinates_sha256=digest(HERE/'source-coordinates.json'),source_hashes_sha256=digest(HERE/'source-hashes.json'),base=BASE,ink=INK,marks=marks,limitations=[
 'Kodak1987 brochure proves PKR120 ISO64 K-14.6033 is the120 product code, not135 code5033.',
 'The Musicians reproduction visibly shows reverse-panel KODAK PKR6033 and opposite PKR,outline right triangle,9. Its exact capture date and scan calibration are not established by the image itself.',
 'Only9 is observed. Other indices and uniform cadence use an explicit presentation assumption; the partial next KO panel suggests about51.36mm brand repetition but is not factory metrology.',
 'Only about59.62mm is visible. All5 nominal formats crop the finite180mm model; longer crops require periodic approximation.',
 'Typeface,nominal56mm aperture,2.5mm rails,neutral dark base,greenish legend color and3mm end margins are illustrative. No measured optical density or spectral response is claimed.',
 'Hasselblad camera-gate notches are excluded. No photograph pixels,sprocket holes,135DX bars,orange mask or scientific profile changes.',
 'Optional centered imprint moves copy complete lower-rail groups outward. These are layout designs,not original factory positions.'
 ])
 return im,row

def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 assert digest(FONTS['regular'])=='57f73e11f51999432bf7ab22ce55b6f945d5eca1bf824404cfa9ec2e3718c84e' and digest(FONTS['bold'])=='a4c5bc453ca281d90ea079e596da7ae0dfeb5777497c29ec254e76d97ff6f890'
 im,row=compile_strip();out=a.output/'kodachrome64-rails.png';im.save(out,optimize=False,compress_level=9);sha=digest(out)
 row.update(path='medium120/v10/'+out.name,version='mf120-v10-kodachrome64-'+sha[:12],sha256=sha,bytes=out.stat().st_size,width=8200,height=50,ppm=PPM)
 (a.output/'authored-evidence.json').write_text(json.dumps(dict(generator='tools/medium120/v10/generate.py',font_sha256={p.name:digest(p) for p in FONTS.values()},asset=row),indent=2)+'\n');print(json.dumps({k:row[k] for k in ['version','sha256','bytes']}))
if __name__=='__main__':main()
