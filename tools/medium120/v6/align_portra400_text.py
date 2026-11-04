#!/usr/bin/env python3
"""Give the authored Portra400 stock legend a consistent weight, with fixed layout.

Reads only the published v5 PNG and pinned, locally installed DejaVu fonts.
No source photographs are used as output pixels. Runtime needs only the PNG.
"""
from __future__ import annotations
import argparse, hashlib, json, math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

BASE=(8,8,8,255)
INK=(211,150,112,255)
SS=8
PPM=10
EXPECTED_BASE='b6bc018bda16c372a8f162553514ab74dd4f435a1bacea7bd67b233627d90618'
FONTS={
 'DejaVuSans.ttf':'57f73e11f51999432bf7ab22ce55b6f945d5eca1bf824404cfa9ec2e3718c84e',
 'DejaVuSans-Bold.ttf':'a4c5bc453ca281d90ea079e596da7ae0dfeb5777497c29ec254e76d97ff6f890',
}
COVERAGE=(285.211538,500.865385)

def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def cap_font(path,height):
 rows=[]
 for size in range(max(1,int(height)),int(height*2)+4):
  f=ImageFont.truetype(str(path),size);b=f.getbbox('H')
  rows.append((abs(b[3]-b[1]-height),size,f))
 return min(rows,key=lambda x:(x[0],x[1]))[2]
def mm(x):return 410+(x-950)/(416/56)
def plans():
 return [dict(text='KODAK PORTRA 400',source_start_px=x,x_mm=mm(x),width_mm=136/(416/56),clipped=False) for x in [109,485,854,1221]]+[
  dict(text='KODAK',source_start_px=1594,x_mm=mm(1594),width_mm=(1625-1594)/(416/56),clipped=True)]

def render_text(fontdir,bold):
 regular=cap_font(fontdir/'DejaVuSans.ttf',1.08*PPM*SS)
 active=cap_font(fontdir/('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf'),1.08*PPM*SS)
 im=Image.new('RGBA',(8200*SS,25*SS),BASE)
 marks=[];glyphs=[];allowed=np.zeros((50,8200),bool)
 for mark in plans():
  text=mark['text'];layout_width=(50 if mark['clipped'] else 136)/(416/56)
  widths=[regular.getlength(c) for c in text]
  tracking=(layout_width*PPM*SS-sum(widths))/(len(text)-1)
  x=mark['x_mm']*PPM*SS;y=(2.5-1.08)/2*PPM*SS
  for c,w in zip(text,widths):
   if c!=' ':
    regular_box=regular.getbbox(c,anchor='lt');active_box=active.getbbox(c,anchor='lt')
    tile=Image.new('RGBA',(active_box[2]-active_box[0],active_box[3]-active_box[1]),BASE)
    ImageDraw.Draw(tile).text((-active_box[0],-active_box[1]),c,font=active,fill=INK,anchor='lt')
    target=(regular_box[2]-regular_box[0],regular_box[3]-regular_box[1])
    if tile.size!=target:tile=tile.resize(target,Image.Resampling.LANCZOS)
    if bold:tile=tile.filter(ImageFilter.MaxFilter(3))
    xx=round(x)+regular_box[0];yy=round(y)+regular_box[1]
    im.paste(tile,(xx,yy))
    # All original per-character anchors and design ink rectangles stay fixed.
    box=[xx,yy,xx+target[0],yy+target[1]]
    aa=[math.floor(xx/SS)-3,math.floor(yy/SS)-3,
        min(math.ceil(box[2]/SS)+3,math.ceil(COVERAGE[1]*PPM)+3),math.ceil(box[3]/SS)+3]
    if aa[0]<aa[2]:allowed[aa[1]:aa[3],aa[0]:aa[2]]=True
    glyphs.append(dict(character=c,anchor_ss=[round(x),round(y)],ink_rectangle_ss=box,allowed_change_box_px=aa if aa[0]<aa[2] else None))
   x+=w+tracking
  marks.append(dict(**mark,height_mm=1.08,tracking_ss=tracking))
 # The last legend is clipped at the same observed source boundary as v2/v5.
 ImageDraw.Draw(im).rectangle((round(COVERAGE[1]*PPM*SS),0,8200*SS,25*SS),fill=BASE)
 return im.resize((8200,25),Image.Resampling.LANCZOS),marks,glyphs,allowed

def generate(assetroot,output,fontdir):
 src=assetroot/'v5/portra400-rails.png'
 assert sha(src)==EXPECTED_BASE,'Published v5 baseline does not match'
 for filename,h in FONTS.items():assert sha(fontdir/filename)==h,filename+' font pin mismatch'
 before=Image.open(src).convert('RGBA');assert before.size==(8200,50)
 old=np.array(before);regular,_,_,_=render_text(fontdir,False);bold,marks,glyphs,allowed=render_text(fontdir,True)
 reg=np.array(regular);new_top=np.array(bold)
 assert np.array_equal(reg[allowed[:25]],old[:25][allowed[:25]]),'Regular text reconstruction differs from published layout'
 after=before.copy();brand_boxes=[]
 for mark in marks:
  box=[int(mark['x_mm']*PPM)-3,0,min(5012,int((mark['x_mm']+mark['width_mm'])*PPM)+5),25]
  brand_boxes.append(box);after.paste(bold.crop(box),(box[0],0))
 new=np.array(after);diff=np.any(new!=old,axis=2)
 assert not np.any(diff&~allowed),'A non-glyph pixel changed'
 assert np.array_equal(old[25:],new[25:]),'Bottom rail changed'
 assert after.getchannel('A').getextrema()==(255,255)
 assert len(marks)==5 and len(glyphs)==61
 output.mkdir(parents=True,exist_ok=True)
 p=output/'portra400-rails.png';after.save(p,compress_level=9)
 h=sha(p)
 # Store an exact pixel change mask for independent delta verification, not APK.
 Image.fromarray((diff*255).astype('uint8')).save(output/'portra400-text-change-mask.png')
 row=dict(name='portra400',stock='kodak_portra_400',file=p.name,path='medium120/v6/'+p.name,
  version='mf120-v6-portra400-'+h[:12],sha256=h,byteCount=p.stat().st_size,width=8200,height=50,
  pixelsPerMm=10,defaultCenterMm=410.0,coverage_mm=list(COVERAGE),
  before_version='mf120-v5-portra400-b6bc018bda16',before_sha256=EXPECTED_BASE,before_path='v5/portra400-rails.png',
  changed_pixels=int(diff.sum()),bottom_rail_unchanged=True,unchanged_outside_glyph_boxes=True,
  font_sha256=FONTS,background_rgba=BASE,ink_rgba=INK,marks=marks,glyphs=glyphs,brand_boxes=brand_boxes,
  source_url='https://www.light11.de/download/Flos_Stories_Ausgabe_3.pdf#page=38',
  limitations=['Display typography is authored, not a factory font or metrology claim.',
    'Only legend stroke weight is aligned with the existing Portra160/800 presentation.',
    'Per-glyph design rectangles, character anchors, advances and all film coordinates are unchanged.',
    'Antialias samples change with new strokes; the ink and background paint colors are unchanged.'])
 manifest=dict(schema=1,generator='tools/medium120/v6/align_portra400_text.py',assets=[row])
 (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 return row

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--asset-root',type=Path,required=True);ap.add_argument('--output',type=Path,required=True)
 ap.add_argument('--font-dir',type=Path,default=Path('/usr/share/fonts/truetype/dejavu'))
 a=ap.parse_args();r=generate(a.asset_root,a.output,a.font_dir)
 print(json.dumps({k:r[k] for k in ['name','version','sha256','byteCount','changed_pixels']},indent=2))
if __name__=='__main__':main()
