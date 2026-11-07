#!/usr/bin/env python3
"""Pinned AcrosII asset, source honesty, monochrome and collision-free common gap."""
import hashlib,json,subprocess,sys,tempfile,unittest
from pathlib import Path
from PIL import Image
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
class AcrosII120Test(unittest.TestCase):
 def setUp(self):self.row=json.loads((HERE/'authored-evidence.json').read_text())['asset']
 def test_regeneration_is_exact_and_monochrome(self):
  saved=json.loads((HERE/'authored-evidence.json').read_text());r=self.row;p=ROOT/'app/src/main/assets'/r['path']
  with tempfile.TemporaryDirectory() as tmp:
   subprocess.run([sys.executable,str(HERE/'generate.py'),'--output',tmp],check=True,stdout=subprocess.DEVNULL)
   self.assertEqual(saved,json.loads((Path(tmp)/'authored-evidence.json').read_text()));self.assertEqual(p.read_bytes(),(Path(tmp)/p.name).read_bytes())
  self.assertEqual(r['sha256'],hashlib.sha256(p.read_bytes()).hexdigest());self.assertEqual(r['bytes'],p.stat().st_size)
  im=Image.open(p);self.assertEqual((8200,50),im.size);self.assertEqual((255,255),im.getextrema()[3])
  self.assertEqual(im.getchannel('R').tobytes(),im.getchannel('G').tobytes());self.assertEqual(im.getchannel('R').tobytes(),im.getchannel('B').tobytes())
  self.assertLess(im.width*im.height*4,2*1024*1024)
 def test_observed_and_inferred_inventory_remain_distinct(self):
  r=self.row;self.assertFalse(r['physical_calibration']);self.assertFalse(r['continuous_source']);self.assertTrue(r['periodic_extension']);self.assertFalse(r['perforated'])
  primary=[m['label'] for m in r['marks'] if m['evidence']=='primary_observed']
  self.assertEqual(['4','wedge4','5','wedge5','FUJI 100 ACROS II','4586'],primary)
  self.assertTrue(any(m['evidence']=='periodic_approximation' for m in r['marks']))
  self.assertFalse(any(m['label'] in ['170','12','A','100 ACROS','ARACED'] for m in r['marks']))
  self.assertAlmostEqual(59.8115183246,r['primaryObservedEndMm']-r['primaryObservedStartMm'])
  self.assertEqual(180,r['coverageEndMm']-r['coverageStartMm'])
  for length in [41.5,56,70,84,168]:self.assertLessEqual(r['coverageStartMm'],410-(length+6)/2);self.assertGreaterEqual(r['coverageEndMm'],410+(length+6)/2)
 def test_original_gap_is_recorded_separately_from_final_centered_layout(self):
  r=self.row;lo,hi=396.7,421.4
  self.assertGreater(lo,r['primaryObservedStartMm']);self.assertLess(hi,r['primaryObservedEndMm'])
  for m in r['marks']:
   if m['rail']==1:self.assertTrue(m['x_mm']+m['width_mm']+.5<=lo or m['x_mm']-.5>=hi)
  im=Image.open(ROOT/'app/src/main/assets'/r['path']);self.assertEqual(((8,8),(8,8),(8,8),(255,255)),im.crop((3967,25,4214,50)).getextrema())
  for box in [(0,0,3200,50),(5000,0,8200,50)]:self.assertEqual(((8,8),(8,8),(8,8),(255,255)),im.crop(box).getextrema())
 def test_only_authored_png_is_packaged(self):
  self.assertEqual(['acrosii-rails.png'],[p.name for p in (ROOT/'app/src/main/assets/medium120/v9').iterdir()])
  sources=json.loads((HERE/'source-hashes.json').read_text());self.assertEqual(2,len(sources));self.assertEqual(2,len({x['sha256'] for x in sources}))
 def test_centered_layouts_leave_guarded_blank_space(self):
  layout=json.loads((HERE/'imprint-layouts.json').read_text());r=self.row
  self.assertEqual(r['version'],layout['asset_version'])
  im=Image.open(ROOT/'app/src/main/assets'/r['path'])
  self.assertEqual(5,len(layout['layouts']))
  for q in layout['layouts']:
   self.assertAlmostEqual(q['widthMm']/2,(q['clearStartMm']+q['clearEndMm'])/2)
   ranges=[]
   for m in q['marks']:
    lo,hi=m['sourcePx'];moving=next((x for x in q['moves'] if x['left']==lo and x['right']==hi),None)
    left=(moving['targetMm']-.1) if moving else lo/10
    right=(moving['targetMm']+(hi-lo)/10+.1) if moving else hi/10
    self.assertGreaterEqual(left,0);self.assertLessEqual(right,q['widthMm'])
    self.assertTrue(right<=q['clearStartMm']-.5 or left>=q['clearEndMm']+.5,(q['format'],m))
    ranges.append((left,right))
   for (a,b),(c,d) in zip(sorted(ranges),sorted(ranges)[1:]):self.assertLess(b,c)
   # Mirror the legacy sourceX remap only for validating the authored blank gap.
   def sx(local):
    for m in reversed(q['moves']):
     lo=max(0,m['left']-1);hi=min(q['cropWidthPx'],m['right']+1)
     if m['targetMm']+(lo-m['left'])/10<=local<m['targetMm']+(hi-m['left'])/10:return m['left']+(local-m['targetMm'])*10
    for m in q['moves']:
     if max(0,m['left']-1)/10<=local<min(q['cropWidthPx'],m['right']+1)/10:return None
    return local*10
   for step in range(round(q['clearStartMm']*40),round(q['clearEndMm']*40)+1):
    x=sx(step/40)
    if x is None:continue
    px=q['cropStartPx']+min(max(.5,x),q['cropWidthPx']-.5)-.5
    for ix in [int(px),int(px)+1]:
     for y in range(25,50):self.assertEqual(8,im.getpixel((ix,y))[0],(q['format'],step/40,ix))

if __name__=='__main__':unittest.main()
