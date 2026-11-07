"""Authored v2 geometry. No reference image reads, filesystem writes or negative palette.
Extracted from v2 generator with positive-only palette and explicit bundled inputs.
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import numpy as np
S=40; W,H=1520,1400

def configure(font_root, trace):
    global FONT, BOLD, MONO, BAR_RECTS
    FONT=str(font_root/'DejaVuSansCondensed.ttf')
    BOLD=str(font_root/'DejaVuSansCondensed-Bold.ttf')
    MONO=str(font_root/'FilmroomReviewMono-Bold.ttf')
    left,right=trace['source_x'];span=right-left;BAR_RECTS=[]
    for row,(ym,hm) in zip(trace['rows'],[(32.90,.75),(33.65,.75)]):
        for x0,x1 in row['visible_dark_runs_x']:
            BAR_RECTS.append([(x0-left)/span*38,ym,(x1-x0)/span*38,hm])

def fontpath(spec): return MONO if spec['style']=='mono' else BOLD if spec['style']=='bold' else FONT

def textmask(text,w,h,path):
    f=ImageFont.truetype(path,180); box=f.getbbox(text); iw=box[2]-box[0]; ih=box[3]-box[1]
    a=Image.new('L',(iw+8,ih+8)); ImageDraw.Draw(a).text((4-box[0],4-box[1]),text,font=f,fill=255)
    return a.crop((4,4,iw+4,ih+4)).resize((round(w*S),round(h*S)),Image.Resampling.LANCZOS)


def render_geometry(spec):
    colour=(24,24,24)
    ink=(159,159,159)
    if spec['id']=='cinestill_800t':
        colour=(23,21,25); ink=(220,189,111)
    a=Image.new('L',(W,H),255); d=ImageDraw.Draw(a)
    # Same physical atlas convention as existing 135 still-border pipeline.
    d.rounded_rectangle((40,220,1480,1180),radius=6,fill=0)
    holes=[]
    for i in range(-1,10):
        x=spec['phase']+i*4.75
        if x < -1.0 or x>39.0:continue
        for y in (3.45,31.55):
            holes.append([x,y]);d.rounded_rectangle(((x-.9905)*S,(y-1.397)*S,(x+.9905)*S,(y+1.397)*S),radius=.30*S,fill=0)
    a=a.filter(ImageFilter.GaussianBlur(.35))
    m=Image.new('L',(W,H));md=ImageDraw.Draw(m)
    if spec['id']=='ilford_hp5_plus_400':
        for x,y,w,h in BAR_RECTS:md.rectangle((round(x*S),round(y*S),round((x+w)*S)-1,round((y+h)*S)-1),fill=255)
    for text,x,y,w,h in spec['texts']:
        path=BOLD if spec['id']=='ilford_hp5_plus_400' and text[0].isdigit() else fontpath(spec)
        stamp=textmask(text,w,h,path);m.paste(stamp,(round(x*S),round(y*S)),stamp)
    typ,x,y,w,h=spec['arrow'];x*=S;y*=S;w*=S;h*=S
    if typ=='triangle':md.polygon([(x,y),(x+w,y+h/2),(x,y+h)],fill=255)
    else:
        md.rectangle((x,y+h*.38,x+w*.78,y+h*.62),fill=255)
        md.polygon([(x+w*.72,y),(x+w,y+h/2),(x+w*.72,y+h)],fill=255)
    im=Image.composite(Image.new('RGB',(W,H),ink),Image.new('RGB',(W,H),colour),m).convert('RGBA');im.putalpha(a)
    pix=np.array(im);pix[pix[:,:,3]==0,:3]=0;im=Image.fromarray(pix)
    return im,holes,colour,ink

