#!/usr/bin/env python3
"""Flatten only authored Portra arrows; freeze every pixel outside their boxes."""
from pathlib import Path
import argparse,json,hashlib,shutil
from PIL import Image
import numpy as np

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def visible_bbox(tile,bg,f=.45):
 a=np.array(tile).astype(float);contrast=np.max(np.abs(a[:,:,:3]-np.array(bg[:3])),axis=2);yy,xx=np.where(contrast>=contrast.max()*f)
 return [int(xx.min()),int(yy.min()),int(xx.max()+1),int(yy.max()+1)]
def full_bbox(tile,bg):
 mask=np.any(np.array(tile)!=np.array(bg),axis=2);yy,xx=np.where(mask);return [int(xx.min()),int(yy.min()),int(xx.max()+1),int(yy.max()+1)]
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--asset-root',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--plan',type=Path,default=Path(__file__).with_name('arrow-shape-plan.json'));args=ap.parse_args();args.output.mkdir(parents=True,exist_ok=True);rows=[]
 for s in json.loads(args.plan.read_text())['stocks']:
  src=args.asset_root/s['base_path'];assert sha(src)==s['sha256'],s['name'];im=Image.open(src).convert('RGBA');old=np.array(im);out=im.copy();bg=s['background_rgba'];allowed=np.zeros(old.shape[:2],bool);changes=[]
  if s['mode']=='vertical_arrow_only':
   for box in s['arrow_boxes']:
    x0,y0,x1,y1=box;assert y0==im.height//2 and y1==im.height
    tile=im.crop(box);aa=np.array(tile);assert np.all(aa[:,[0,-1]]==np.array(bg))
    before=visible_bbox(tile,bg);cy=(before[1]+before[3])/2;center=cy+s['resampling_phase_px'];k=s['vertical_scale'];assert 0<k<1
    # Identity x sampling, fixed source y-center; bilinear avoids new color extrema.
    new=tile.transform(tile.size,Image.Transform.AFFINE,(1,0,0,0,1/k,center*(1-1/k)),Image.Resampling.BILINEAR,fillcolor=tuple(bg))
    bb=np.array(new);assert np.all(bb>=aa.min((0,1))) and np.all(bb<=aa.max((0,1)))
    after=visible_bbox(new,bg);assert after[0]==before[0] and after[2]==before[2],(s['name'],'visible x extent changed')
    assert (after[1]+after[3])/2==cy,(s['name'],'visible y center changed')
    assert after[3]-after[1]<before[3]-before[1]
    out.paste(new,(x0,y0));allowed[y0:y1,x0:x1]=True
    def absolute(b):return [x0+b[0],y0+b[1],x0+b[2],y0+b[3]]
    changes.append({'allowed_box':box,'before_visible_bbox':absolute(before),'after_visible_bbox':absolute(after),'before_full_bbox':absolute(full_bbox(tile,bg)),'after_full_bbox':absolute(full_bbox(new,bg)),'vertical_scale':k,'x_translation_px':0,'visible_center_y_changed_px':0})
   newarr=np.array(out);diff=np.any(old!=newarr,axis=2);assert not np.any(diff&~allowed)
   assert np.array_equal(old[:im.height//2],newarr[:im.height//2]);assert out.getchannel('A').getextrema()==(255,255)
   out.save(args.output/(s['name']+'-rails.png'),compress_level=9)
  else:
   assert s['mode']=='preserve';shutil.copy2(src,args.output/(s['name']+'-rails.png'));diff=np.zeros(old.shape[:2],bool)
  p=args.output/(s['name']+'-rails.png');h=sha(p);r={k:s[k] for k in ['name','stock','width','height','pixelsPerMm','coverage_mm','defaultCenterMm']};r.update(file=p.name,sha256=h,byteCount=p.stat().st_size,version='mf120-v5-'+s['name']+'-'+h[:12] if changes else s['version'],before_version=s['version'],before_sha256=s['sha256'],before_path=s['base_path'],changed_pixels=int(diff.sum()),arrow_changes=changes,unchanged_outside_arrow_boxes=True,source_url=s.get('source_url'),limitations=s.get('limitation'))
  assert all(s['coverage_mm'][0]<=410-(l+6)/2 and 410+(l+6)/2<=s['coverage_mm'][1] for l in [41.5,56,70,84,168])
  rows.append(r)
 (args.output/'manifest.json').write_text(json.dumps({'schema':1,'generator':'tools/medium120/v5/flatten_portra_arrows.py','assets':rows},indent=2)+'\n')
 print(json.dumps([{k:r[k] for k in ['name','version','sha256','changed_pixels']} for r in rows],indent=2))
if __name__=='__main__':main()
