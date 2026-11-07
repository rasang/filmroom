"""Original simplified package typography. No source photograph pixels in app assets."""
from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'tools/paper-artwork/labels';OUT.mkdir(exist_ok=True)
items=[('fujifilm_crystal_archive_typeii','FUJIFILM','CRYSTAL ARCHIVE','TYPE II','#b49464','#23553e','carton'),('kodak_ektacolor_edge','Kodak','EKTACOLOR','EDGE','#b6956d','#151515','carton'),('kodak_endura_premier','Kodak','ENDURA','PREMIER','#b6956d','#151515','carton'),('kodak_portra_endura','Kodak','PORTRA','ENDURA','#edb909','#252525','flat'),('kodak_supra_endura','Kodak','SUPRA','ENDURA','#edb909','#252525','flat'),('kodak_ultra_endura','Kodak','ULTRA','ENDURA','#b6956d','#252525','long'),('ilford_multigrade_iv_rc','ILFORD','MULTIGRADE IV','RC DELUXE','#ededeb','#202931','flat'),('kodak_2383','KODAK','VISION','2383','#e0ac1c','#252525','can'),('kodak_2393','KODAK','VISION PREMIER','2393','#e0ac1c','#252525','can'),('kodak_2302','KODAK','B&W PRINT FILM','2302','#e0ac1c','#252525','can')]
font='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
for id,brand,line1,line2,bg,ink,shape in items:
 im=Image.new('RGB',(768,768),bg);d=ImageDraw.Draw(im)
 def text(x,y,s,size,color=ink):d.text((x,y),s,font=ImageFont.truetype(font,size),fill=color)
 if brand=='FUJIFILM':
  text(60,220,brand,70);text(60,340,'FUJICOLOR PAPER',30);text(60,430,'CRYSTAL ARCHIVE',37);text(60,500,'TYPE II',56);text(60,610,'RA-4',26)
 elif shape in ['carton','long']:
  d.rectangle((46,280,722,706),fill='#f5eee0' if id!='kodak_ultra_endura' else '#df9e42')
  if brand=='Kodak' and id!='kodak_ektacolor_edge':d.rectangle((46,280,722,374),fill='#efba13')
  text(72,292,brand,55,'#b92924' if brand=='Kodak' else ink)
  text(72,410,line1,48);text(72,470,line2,55);text(72,575,'PHOTOGRAPHIC PAPER',25)
  text(72,626,'RA-4',25)
 elif brand=='ILFORD':
  text(45,52,brand,94);d.rectangle((44,294,720,684),fill='#28303b');d.rectangle((490,294,720,684),fill='#d294b3')
  text(66,334,'MULTIGRADE',42,'white');text(66,398,'IV RC',58,'white');text(66,480,'DELUXE',34,'white');text(66,594,'BLACK & WHITE',25,'white')
 elif shape=='can':
  text(90,150,brand,74,'#bd2523');text(90,280,line1,35);text(90,370,line2,105);text(90,550,'PRINT FILM',38)
 else:
  text(46,90,brand,75,'#c82e20');text(290,112,'Professional',39,'#6c674f')
  d.rectangle((60,510,706,688),fill='#dcc67c');d.rectangle((60,580,706,648),fill='#3e535e')
  text(76,520,line1,35);text(76,589,line2,38,'white');text(76,655,'COLOUR PAPER',20)
 im.save(OUT/(id+'.png'))
(ROOT/'tools/paper-artwork/models.json').write_text(json.dumps([dict(id=a,shape=g,color=e) for a,b,c,d,e,f,g in items],indent=2))
