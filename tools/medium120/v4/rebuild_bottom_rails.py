#!/usr/bin/env python3
"""Compile authored mark sprites into a fresh lower rail using measured coordinate models.

No reference photograph pixels, screenshot inputs, or hand-applied displacement lists
are used. The immutable approved upper rail is copied exactly. Glyph style/color/AA
come from our own byte-pinned authored asset bank; one canonical arrow is instantiated
at all fitted anchors. Models and uncertainty are explicit in bottom-models.json.
"""
from pathlib import Path
import argparse,hashlib,json,shutil,math
from PIL import Image,ImageChops,ImageDraw
import numpy as np

def quantize(v):return int(math.floor(v+0.5))  # absolute coordinates, ties upward; no accumulated rounding
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def readable_box(tile,bg):
 a=np.array(tile).astype(int);contrast=np.max(np.abs(a[:,:,:3]-np.array(bg[:3])),axis=2)
 mask=contrast>=float(contrast.max())*.45
 ys,xs=np.where(mask)
 return [int(xs.min()),int(ys.min()),int(xs.max()+1),int(ys.max()+1)]
def pixel_box(tile,bg):
 a=np.array(tile);mask=np.any(a!=np.array(bg),axis=2);ys,xs=np.where(mask)
 return [int(xs.min()),int(ys.min()),int(xs.max()+1),int(ys.max()+1)]
def modeled_anchors(spec):
 model=spec['model'];kind=model['type'];count=len(spec['placements'])
 if kind=='three_arrow_cycle':
  assert len(model['offsets_mm'])==3 and model['offsets_mm'][0]==0
  steps=[model['offsets_mm'][1],model['offsets_mm'][2]-model['offsets_mm'][1],model['cycle_mm']-model['offsets_mm'][2]]
  assert len(model['cycle_steps_mm'])==3,(spec['name'],'cycle step count must be3')
  assert all(abs(a-b)<1e-7 for a,b in zip(steps,model['cycle_steps_mm'])),(spec['name'],'cycle step drift')
  anchors=[model['first_anchor_mm']+(i//3)*model['cycle_mm']+model['offsets_mm'][i%3] for i in range(count)]
 elif kind=='finite_measured_motif':
  assert model['repeat'] is False and len(model['offsets_mm'])==count
  anchors=[model['first_anchor_mm']+x for x in model['offsets_mm']]
 elif kind=='authored_midpoint_lattice':
  assert count==model['arrow_count']
  anchors=[model['first_anchor_mm']+i*model['step_mm'] for i in range(count)]
 else:raise AssertionError(('Unrecognized finite coordinate model',kind))
 assert all(abs(a-float(p['arrow_center_mm']))<1e-7 for a,p in zip(anchors,spec['placements'])),(spec['name'],'model/placement drift')
 assert all(a<b for a,b in zip(anchors,anchors[1:])),(spec['name'],'nonmonotonic anchors')
 numbers=[p['number'] for p in spec['placements'] if p.get('number') is not None]
 assert len(numbers)==len(set(numbers)),(spec['name'],'duplicate number')
 assert {str(p['number']) for p in spec['placements'] if p.get('number') is not None}=={g['id'].removeprefix('number_') for g in spec['glyphs'] if g['id'].startswith('number_')},(spec['name'],'number vocabulary drift')
 # Preserve frozen decimal serialization after the independent formula guard.
 return [float(p['arrow_center_mm']) for p in spec['placements']]

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--asset-root',required=True,type=Path);ap.add_argument('--output',required=True,type=Path);ap.add_argument('--models',type=Path,default=Path(__file__).with_name('bottom-models.json'));args=ap.parse_args()
 data=json.loads(args.models.read_text());out=args.output;out.mkdir(parents=True,exist_ok=True);bank=out/'glyph-bank';bank.mkdir(exist_ok=True)
 rows=[]
 for spec in data['stocks']:
  name=spec['name'];path=args.asset_root/spec['base_path'];assert sha(path)==spec['base_sha256'],name
  original=Image.open(path).convert('RGBA');ppm=spec['pixels_per_mm'];rw,rh=original.size;seam=rh//2;bg=tuple(spec['background_rgba']);assert original.getpixel((0,seam))==bg
  result=original.copy();events=[];tiles={}
  if spec['mode']=='recompose':
   # Build a reusable transparent-free authored motif bank. Each tile includes full
   # antialias fringes and preserves its original local vertical registration.
   for g in spec['glyphs']:
    b=g['source_box'];assert b[1]==seam and b[3]==rh
    tile=original.crop(b);assert np.all(np.array(tile)[:,[0,-1],:]==np.array(bg)),g
    vb=readable_box(tile,bg);pb=pixel_box(tile,bg);tiles[g['id']]=(tile,vb,pb)
    tile.save(bank/f'{name}-{g["id"]}.png',compress_level=9)
   result.paste(bg,(0,seam,rw,rh));occupied=np.zeros((rh-seam,rw),dtype=bool)
   def paste(gid,x,kind,anchor,number=None):
    tile,vb,pb=tiles[gid];x=int(x);assert 0<=x and x+tile.width<=rw,(name,gid,x)
    pix=np.array(tile);mask=np.any(pix!=np.array(bg),axis=2)
    assert not np.any(occupied[:,x:x+tile.width]&mask),(name,gid,'collision')
    # Mask only foreground so neighboring antialias halos cannot erase each other.
    alpha=Image.fromarray((mask*255).astype('uint8'),'L');result.paste(tile,(x,seam),alpha)
    occupied[:,x:x+tile.width]|=mask
    e=dict(kind=kind,glyph=gid,number=number,anchor_mm=anchor,paste_x_px=x,readable_bbox=[x+vb[0],seam+vb[1],x+vb[2],seam+vb[3]],full_bbox=[x+pb[0],seam+pb[1],x+pb[2],seam+pb[3]])
    events.append(e);return e
   arrow,vb,_=tiles['arrow'];ac=(vb[0]+vb[2]-1)/2
   for i,anchor in enumerate(modeled_anchors(spec)):
    mark=spec['placements'][i];a=paste('arrow',quantize(anchor*ppm-ac),'arrow',anchor,mark.get('number'))
    if mark.get('number') is not None:
     gid='number_'+str(mark['number']);_,nb,_=tiles[gid];gap=quantize(float(spec['number_gap_mm'])*ppm)
     nx=a['readable_bbox'][2]+gap-nb[0]
     n=paste(gid,nx,'number',anchor,mark['number']);assert n['readable_bbox'][0]-a['readable_bbox'][2]==gap
  else:
   assert spec['mode']=='preserve'
  assert result.crop((0,0,rw,seam)).tobytes()==original.crop((0,0,rw,seam)).tobytes()
  assert result.getchannel('A').getextrema()==(255,255)
  lo,hi=spec['coverage_mm'];assert all(lo<=410-(l+6)/2 and 410+(l+6)/2<=hi for l in [41.5,56,70,84,168])
  if spec['mode']=='recompose':
   assert all(e['full_bbox'][0]/ppm>=lo and e['full_bbox'][2]/ppm<=hi for e in events),(name,'coverage',events)
   result.save(out/f'{name}-rails.png',compress_level=9)
  else:shutil.copy2(path,out/f'{name}-rails.png')
  dst=out/f'{name}-rails.png';changed=int(np.any(np.array(original)!=np.array(result),axis=2).sum())
  row={k:spec[k] for k in ['name','stock','base_path','base_sha256','base_version','mode','coverage_mm','source_urls','model','confidence','limitations']}
  row.update(file=dst.name,sha256=sha(dst),version=('mf120-v4-'+name+'-'+sha(dst)[:12]) if changed else spec['base_version'],byteCount=dst.stat().st_size,width=rw,height=rh,pixelsPerMm=ppm,defaultCenterMm=410,changed_pixels=changed,upper_pixels_changed=0,events=events)
  if spec['mode']=='recompose':
   anchors=[e['readable_bbox'] for e in events if e['kind']=='arrow'];centers=[(b[0]+b[2]-1)/2/ppm for b in anchors];row['actual_arrow_centers_mm']=centers;row['actual_arrow_intervals_mm']=[round(b-a,6) for a,b in zip(centers,centers[1:])];row['actual_number_gap_mm']=round(spec['number_gap_mm']*ppm)/ppm
  rows.append(row)
 (out/'manifest.json').write_text(json.dumps({'schema':1,'generator':'tools/medium120/v4/rebuild_bottom_rails.py','assets':rows},indent=2,ensure_ascii=False)+'\n')
 print(json.dumps([{k:r[k] for k in ['name','version','sha256','changed_pixels','upper_pixels_changed']} for r in rows],indent=2))
if __name__=='__main__':main()
