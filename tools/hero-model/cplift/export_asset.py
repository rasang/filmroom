from pathlib import Path
from PIL import Image
import argparse
p=argparse.ArgumentParser();p.add_argument('render',type=Path);p.add_argument('asset',type=Path);a=p.parse_args()
im=Image.open(a.render).convert('RGBA');b=im.getbbox();size=round(max(b[2]-b[0],b[3]-b[1])*1.08);cx=(b[0]+b[2])/2;cy=(b[1]+b[3])/2;left=round(cx-size/2);top=round(cy-size/2)
a.asset.parent.mkdir(parents=True,exist_ok=True);im.crop((left,top,left+size,top+size)).save(a.asset,optimize=True)
