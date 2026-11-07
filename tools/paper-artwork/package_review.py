from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
import json
R=Path(__file__).resolve().parents[2];renders=R/'tools/paper-artwork/renders';assets=R/'app/src/main/assets/print-media';assets.mkdir(exist_ok=True)
font='/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
def f(n):return ImageFont.truetype(font,n)
names={'fujifilm_crystal_archive_typeii':'Fujifilm Crystal Archive II','ilford_multigrade_iv_rc':'Ilford Multigrade IV RC','kodak_portra_endura':'Kodak Portra Endura','kodak_supra_endura':'Kodak Supra Endura','kodak_ultra_endura':'Kodak Ultra Endura','kodak_ektacolor_edge':'Kodak Ektacolor Edge','kodak_endura_premier':'Kodak Endura Premier','kodak_2383':'Kodak Vision 2383','kodak_2393':'Kodak Vision Premier 2393','kodak_2302':'Kodak B&W Print 2302'}
for item in json.loads((R/'tools/paper-artwork/models.json').read_text()):
 im=Image.open(renders/(item['id']+'.png')).convert('RGBA');im.resize((240,240),Image.Resampling.LANCZOS).save(assets/(item['id']+'.webp'),lossless=True)
for group,ids in [('paper',[i for i in names if i not in ['kodak_2383','kodak_2393','kodak_2302']]),('print-film',['kodak_2383','kodak_2393','kodak_2302'])]:
 rows=(len(ids)+3)//4;out=Image.new('RGB',(1400,140+rows*370),'#252724');d=ImageDraw.Draw(out)
 d.text((40,24),'Filmroom · 相纸模型审图' if group=='paper' else 'Filmroom · 电影拷贝片包装示意',font=f(30),fill='#eee9df')
 note='依据真实包装重新建模；文字与规格简化，不是实拍图。' if group=='paper' else '历史罐形示意；三款具体标签配色与排版尚未核实，非精确复刻。'
 d.text((40,75),note,font=f(22),fill='#beb7a8')
 for i,id in enumerate(ids):
  im=Image.open(renders/(id+'.png'));im.thumbnail((320,320));x=(i%4)*350+15;y=125+(i//4)*370;out.paste(im,(x,y),im)
  d.text((x+8,y+320),names[id],font=f(18),fill='#eee9df')
 out.save(R/f'docs/paper-carousel/{group}-model-review.png')
print('packaged 10 transparent 240px assets and 2 labelled review sheets')
