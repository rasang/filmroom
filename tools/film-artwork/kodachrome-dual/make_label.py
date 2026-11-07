"""Typeset a simplified PKR 120 band from the visible bare roll in Kodak's 1987 brochure.
Unseen circumference and tiny unreadable type are not asserted as factory artwork.
No photograph pixels, packaging box art or traced wordmarks are used.
"""
from pathlib import Path
import os
from PIL import Image,ImageDraw,ImageFont
P=Path(os.environ.get('ARTWORK_ROOT',Path(__file__).resolve().parents[1]))
im=Image.new('RGB',(1800,1600),'#efb624');d=ImageDraw.Draw(im)
red='#bb201d';ink='#151516';white='#eee5d6';yellow='#edb42d'
d.rectangle((0,545,1800,1520),fill=red)
# Backing-paper stripes are a nonfunctional visual approximation, not a barcode.
for y,h in [(100,8),(125,6),(150,7),(180,23),(218,6),(242,8),(268,7),(298,24),(344,17),(380,7),(407,7)]:
 d.rectangle((0,y,1800,y+h),fill=ink)
def text(txt,x,y,w,h,color=white,bold=False):
 f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans'+('-Bold' if bold else '')+'.ttf',220)
 bb=f.getbbox(txt);layer=Image.new('RGBA',(bb[2]-bb[0]+12,bb[3]-bb[1]+12));ImageDraw.Draw(layer).text((6-bb[0],6-bb[1]),txt,font=f,fill=color)
 layer=layer.resize((w,h),Image.Resampling.LANCZOS);im.paste(layer,(x-w//2,y-h//2),layer)
text('ISO',570,650,120,55);text('64',570,750,175,140)
text('KODACHROME',1020,680,570,65)
text('Professional',1020,760,520,61)
text('Daylight',1020,833,350,60)
text('REMOVE BAND',900,960,900,67,ink,True)
d.rectangle((0,1018,1800,1024),fill=yellow)
text('PKR',845,1150,440,170,yellow,True)
text('120',845,1340,440,190,yellow,True)
text('KODACHROME',900,1474,970,85,yellow)
im.save(P/'labels/kodak_kodachrome_64-120.png')
