from pathlib import Path
import os
from PIL import Image,ImageDraw,ImageFont
import hashlib,json,shutil
P=Path(os.environ.get('ARTWORK_ROOT',Path(__file__).resolve().parents[1]))
A=Path(os.environ['FILMROOM_SOURCE'])/'app/src/main/assets/film-rolls';key='kodak_kodachrome_64'
im=Image.open(P/f'renders/{key}.png').convert('RGBA');im.resize((240,240),Image.Resampling.LANCZOS).save(P/f'thumbnails/{key}.webp','WEBP',lossless=True,method=6)
b=(P/f'thumbnails/{key}.webp').read_bytes();sha=hashlib.sha256(b).hexdigest();bbox=list(Image.open(P/f'thumbnails/{key}.webp').getchannel('A').getbbox());shutil.copyfile(P/f'thumbnails/{key}.webp',A/f'{key}.webp')
m=json.loads((A/'manifest.json').read_text())
for row in m['assets']:
 if row['physical_stock_id']==key:row.update(sha256=sha,bytes=len(b))
for row in m['profiles']:
 if row['profile_id']==key:row.update(illustrated_formats=['135','120'],grants_frame_support=False,source_120_url='https://www.pacificrimcamera.com/rl/03708/03708.pdf',source_120_page=8)
m['model_source_revision']=5
m['kodachrome_dual_source']={'model':'kodak_kodachrome_64.blend','model_sha256':hashlib.sha256((P/f'models/{key}.blend').read_bytes()).hexdigest(),'reference':'Kodak 1987 brochure, page 8, actual bare PKR 120 roll','old_135_texture_preserved':True,'other_24_thumbnails_unchanged':True,'geometry':'Existing approved 135 + bare 120 physical scale, camera and lights','artwork':'Original simplified typesetting; unseen circumference and tiny type are approximate'}
(A/'manifest.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
m=json.loads((A/'medium-format-manifest.json').read_text())
for row in m['entries']:
 if row['profile_id']==key:row.update(form='135 + 120',sha256=sha,alpha_bbox=bbox)
(A/'medium-format-manifest.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
report={'asset':key+'.webp','sha256':sha,'bytes':len(b),'alpha_bbox':bbox,'render_dimensions':list(im.size),'thumbnail_dimensions':[240,240],'reference_120':'https://www.pacificrimcamera.com/rl/03708/03708.pdf#page=8','preserved_before_sha256':hashlib.sha256((P/'evidence/before-single135.webp').read_bytes()).hexdigest()}
(P/'ASSET_REPORT.json').write_text(json.dumps(report,indent=2)+'\n')
# Review uses the actual generated asset and original thumbnail, not synthesized mock render.
board=Image.new('RGB',(1200,800),'#eeede8');d=ImageDraw.Draw(board)
f='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
def txt(x,y,s,size=22):d.text((x,y),s,fill='#202222',font=ImageFont.truetype(f,size))
txt(35,24,'Kodachrome 64: existing 135 + observed PKR 120',32)
txt(40,92,'Before: 135 only');txt(410,92,'New: 135 + 120, same camera / scale')
old=Image.open(P/'evidence/before-single135.webp').convert('RGBA').resize((360,360),Image.Resampling.LANCZOS);board.paste(old,(15,190),old)
new=im.resize((610,610),Image.Resampling.LANCZOS);board.paste(new,(370,105),new)
txt(975,130,'App size: 72px',18)
thumb=Image.open(P/f'thumbnails/{key}.webp').convert('RGBA').resize((72,72),Image.Resampling.LANCZOS);board.paste(thumb,(1000,175),thumb)
txt(40,702,'120 band: Kodak 1987 brochure, page 8, photographed bare roll.',21)
txt(40,736,'Label typography and unseen wrap simplified. Other 24 thumbnails unchanged.',21)
board.save(P/'KODACHROME-135-120-thumbnail-review.png')
print(json.dumps(report))
