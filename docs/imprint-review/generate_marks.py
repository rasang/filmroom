import json,re,hashlib
from pathlib import Path
from PIL import Image
import argparse
parser=argparse.ArgumentParser(description="Verify frozen reviewed crops and regenerate Kotlin layout rows")
parser.add_argument("--repo",type=Path,default=Path(__file__).resolve().parents[2])
parser.add_argument("--output",type=Path,required=True,help="Separate generated output directory")
args=parser.parse_args()
R=Path(__file__).parent
REPO=args.repo
OUT=args.output
OUT.mkdir(parents=True,exist_ok=True)
atoms=json.loads((R/'reviewed-atoms.json').read_text()); plan=json.loads((R/'reviewed-positions.json').read_text())
s=(REPO/'app/src/main/java/dev/filmroom/border/MediumStripDefinitions.kt').read_text()
defs={}
for stock,path,version,w,h,ppm,n,sha in re.findall(r'Definition\("([^"]+)","([^"]+)","([^"]+)",(\d+),(\d+),(\d+),(\d+),"([^"]+)"',s):defs[stock]=(path,version,int(w),int(h),int(ppm),sha)
rows=[];provenance={};moves=stationary=0
for stock,formats in atoms.items():
 path,version,w,h,ppm,sha=defs[stock]; asset=REPO/'app/src/main/assets'/path
 assert hashlib.sha256(asset.read_bytes()).hexdigest()==sha
 im=Image.open(asset).convert('RGB'); provenance[stock]={'path':path,'version':version,'sha256':sha}
 for fmt,a in formats.items():
  W=a['display_width_mm'];pw,ph=a['size_px'];sx=W/pw;start=round((410-W/2)*ppm)
  source=im.crop((start,h//2,start+pw,h))
  assert hashlib.sha256(source.tobytes()).hexdigest()==a['crop_pixel_sha256']
  bg=tuple(a['background_rgb']); ms=[];occ=[]
  for atom,t in zip(a['movable'],plan[stock][fmt]['targets_mm']):
   l,_,r,_=atom['full_height_source_clip_px'];lo=max(0,l-1);hi=min(pw,r+1)
   for x in list(range(lo,l))+list(range(r,hi)):
    assert all(source.getpixel((x,y))==bg for y in range(ph))
   ms.append(f'Move({l}, {r}, {float(t)})');occ.append((t+(lo-l)*sx-.5,t+(hi-l)*sx+.5));moves+=1
  for atom in a['stationary']:
   l,_,r,_=atom['full_height_source_clip_px'];occ.append((l*sx-.5,r*sx+.5));stationary+=1
  lo=3.;hi=W-3
  for l,r in occ:
   if r<=W/2:lo=max(lo,r)
   elif l>=W/2:hi=min(hi,l)
   else:raise AssertionError('center blocked')
  assert hi>lo
  radius=min(W/2-lo,hi-W/2);lo=W/2-radius;hi=W/2+radius
  rows.append(f'        Key("{stock}", MediumFormat.MF{fmt}) to Layout("{version}", {float(W)}, {start}, {pw}, {ppm}, {lo}, {hi}, listOf({", ".join(ms)})),')
assert len(rows)==45 and moves==98 and stationary==13
(OUT/'generated_rows.txt').write_text('\n'.join(rows))
provenance['review']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in R.iterdir() if p.is_file()}
(OUT/'provenance.json').write_text(json.dumps(provenance,indent=2))
print('Verified 45 source crops, 98 moves, 13 stationary groups, source hashes and one-pixel background margins.')
