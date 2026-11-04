#!/usr/bin/env python3
"""New vector/font-authored 120 rail illustrations. Never reads photograph pixels."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import json, hashlib
ROOT=Path(__file__).resolve().parent
FONT='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
PPM=10; SS=8; BASE=(8,8,8,255)
CROPS={'645':47.5,'6x6':62,'6x7':76,'6x9':90,'6x17':174}

def text_metrics(text,height,tracking):
    font=ImageFont.truetype(FONT,760)
    bb=font.getbbox(text); scale=height/(bb[3]-bb[1])
    width=sum(font.getlength(c) for c in text)*scale+tracking*(len(text)-1)
    return width

def build_spec(stock,scale,span,source,url,ink,topnums,brands,bottomnums,arrows,height,tracking,top_y,bottom_y):
    mid=sum(span)/2
    def xpos(px): return 410+(px-mid)/scale
    marks=[]
    def mark(kind,text,rail,cx,sy,h,sw=None):
        track=tracking if kind=='brand' else 0.03
        w=text_metrics(text,h,track) if text else sw/scale
        marks.append({'kind':kind,'text':text,'number':int(text) if text and text.isdigit() else None,'rail':rail,'x_mm':round(xpos(cx)-w/2,6),'y_mm':round(1.25-h/2,6),'width_mm':round(w,6),'height_mm':h,'tracking_mm':track if text else 0,'source_landmark':{'coordinate_image':source,'center_x_px':cx,'center_y_px':sy,'interpretation':'estimated visual landmark, not measured film coordinate'},'source_url':url})
    for num,cx in topnums: mark('number',str(num),'top',cx,top_y,height)
    for cx in brands: mark('brand','KODAK 200' if stock=='gold200' else 'KODAK EKTAR 100','top',cx,top_y,height)
    for num,cx in bottomnums: mark('number',str(num),'bottom',cx,bottom_y,height*.86)
    for cx,associated in arrows:
        mark('triangle',None,'bottom',cx,bottom_y,height*.75,scale*height*1.35)
        marks[-1]['associated_number']=associated
        marks[-1]['direction']='right'
    return {'stock':stock,'version':'v2-observed-local','width':8200,'height':50,'pixelsPerMm':10,'railHeightMm':2.5,'baseRGBA':BASE,'inkRGBA':ink,'defaultCenterMm':410,'coverageStartMm':xpos(span[0]),'coverageEndMm':xpos(span[1]),'source_url':url,'source_coordinate_image':source,'source_span_px':span,'estimated_source_pixels_per_mm':scale,'calibration':'Nominal aperture-based illustration estimate, not a ruler measurement. No full-roll sequence is asserted. Unobserved atlas areas are blank and must not be exposed by position controls.','font':FONT,'font_note':'Uniformly scaled DejaVu Sans Bold outlines with explicit tracking; approximation, not a verified Kodak typeface. No anisotropic text scaling. Ink retained from prior illustrative stock asset, not source-calibrated.','marks':marks}

# Gold coordinate image is the retained brightness-only 3610x980 research crop.
# Five portrait 645 apertures are ~845px high (nominal56mm); use15px/mm.
# Their ~620px width /41.5mm cross-check is14.94px/mm.
gold=build_spec('gold200',15,(20,3540),'gold-contact-strip.jpg',
'https://www.photrio.com/forum/attachments/dscf7174-jpg.416154/',(223,208,193,255),
[(41,55),(42,808),(43,1553),(44,2298),(45,3038)],
[435,1180,1921,2664,3410],[(1,272),(2,1200),(3,2046),(4,3035)],
[(245,1),(674,None),(1165,2),(1595,None),(2012,3),(2518,None),(3002,4),(3440,None)],1.1,.45,42,920)
# Ektar coordinate image is clockwise-rotated third strip enlarged3x,2028x690.
# Three square apertures ~592px longitudinal;56mm nominal gate gives10.5714px/mm.
ektar=build_spec('ektar100',592/56,(56,1962),'eskdale-p5-000-strip.png',
'https://rdsgallery.co.nz/wp-content/uploads/2025/10/2025_Eskdale_catalogue_sm.pdf',(208,143,126,255),
[(48,70),(49,588),(50,1100),(51,1618)],[269,788,1300,1815],
[(7,408),(8,1078),(9,1715)],[(70,None),(375,7),(713,None),(1048,8),(1350,None),(1687,9)],1.25,.03,48,658)
gold['marks'][10]['source_landmark']['confidence_note']='First lower digit1 is dim; its presence is visible but precise glyph shape is not verified.'
for spec in [gold,ektar]:
    (ROOT/(spec['stock']+'.json')).write_text(json.dumps(spec,indent=2)+'\n')

def render(spec):
    im=Image.new('RGBA',(8200*SS,50*SS),BASE);d=ImageDraw.Draw(im)
    for m in spec['marks']:
        x=m['x_mm']*PPM*SS;y=(m['y_mm']+(2.5 if m['rail']=='bottom' else 0))*PPM*SS
        w=m['width_mm']*PPM*SS;h=m['height_mm']*PPM*SS
        if m['kind']=='triangle': d.polygon([(x,y),(x+w,y+h/2),(x,y+h)],fill=spec['inkRGBA'])
        else:
            font=ImageFont.truetype(FONT,760);box=font.getbbox(m['text']);ratio=h/(box[3]-box[1]);
            # One uniform raster scale after high-resolution font layout; no width fitting.
            tracking=m['tracking_mm']*PPM*SS/ratio
            canvas=Image.new('RGBA',(int(sum(font.getlength(c) for c in m['text'])+tracking*(len(m['text'])-1))+8,box[3]-box[1]+2))
            cd=ImageDraw.Draw(canvas);xx=0
            for c in m['text']:
                cd.text((xx,-box[1]),c,font=font,fill=spec['inkRGBA']);xx+=font.getlength(c)+tracking
            canvas=canvas.resize((round(canvas.width*ratio),round(canvas.height*ratio)),Image.Resampling.LANCZOS)
            im.alpha_composite(canvas,(round(x),round(y)))
    im=im.resize((8200,50),Image.Resampling.LANCZOS)
    im.save(ROOT/(spec['stock']+'-rails.png'),compress_level=9)
    # Review layout: actual174mm wide rails,56mm synthetic matte aperture,3mm sides.
    review=Image.new('RGBA',(1740,610),BASE)
    review.paste(im.crop((3230,0,4970,25)),(0,0));review.paste(im.crop((3230,25,4970,50)),(0,585))
    ImageDraw.Draw(review).rectangle((30,25,1709,584),fill=(55,59,62,255))
    review.save(ROOT/(spec['stock']+'-6x17-review.png'))
    return im

manifest=[]
for spec in [gold,ektar]:
    im=render(spec);assert im.mode=='RGBA' and im.size==(8200,50)
    assert im.getchannel('A').getextrema()==(255,255)
    for label,width in CROPS.items():
        assert spec['coverageStartMm']<=410-width/2 and 410+width/2<=spec['coverageEndMm'],label
    expected_top=range(41,46) if spec['stock']=='gold200' else range(48,52)
    expected_bottom=range(1,5) if spec['stock']=='gold200' else range(7,10)
    for rail,nums in [('top',expected_top),('bottom',expected_bottom)]:
        assert {m['number'] for m in spec['marks'] if m['rail']==rail and m['kind']=='number'}==set(nums)
    independent=[m for m in spec['marks'] if m['kind']=='triangle' and m['associated_number'] is None]
    assert len(independent)==(4 if spec['stock']=='gold200' else 3)
    for m in spec['marks']:
        assert 0<=m['y_mm'] and m['y_mm']+m['height_mm']<=2.5
        assert spec['coverageStartMm']<=m['x_mm'] and m['x_mm']+m['width_mm']<=spec['coverageEndMm']
        # All declared marks produce non-base pixels near their own location.
        x0=round(m['x_mm']*10);y0=round((m['y_mm']+(2.5 if m['rail']=='bottom' else 0))*10)
        crop=im.crop((x0,y0,x0+max(1,round(m['width_mm']*10)),y0+max(1,round(m['height_mm']*10))))
        assert any(p!=BASE for p in crop.getdata())
    fn=ROOT/(spec['stock']+'-rails.png');data=fn.read_bytes();sha=hashlib.sha256(data).hexdigest()
    manifest.append({'stock':spec['stock'],'file':fn.name,'version':'mf120-v2-'+spec['stock']+'-'+sha[:12],'sha256':sha,'byteCount':len(data),'coverageStartMm':spec['coverageStartMm'],'coverageEndMm':spec['coverageEndMm'],'defaultCenterMm':410,'tests':'PASS: dimensions, opaque alpha, five crop intervals, exact numeral sets, independent triangles, mark bounds and raster presence'})
(ROOT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest,indent=2))
