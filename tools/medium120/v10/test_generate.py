#!/usr/bin/env python3
from pathlib import Path
from PIL import Image
import unittest,tempfile,subprocess,sys,json,hashlib
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
class Kodachrome120Test(unittest.TestCase):
 def setUp(self):self.data=json.loads((HERE/'authored-evidence.json').read_text());self.row=self.data['asset'];self.im=Image.open(ROOT/'app/src/main/assets'/self.row['path'])
 def tearDown(self):self.im.close()
 def test_regeneration_is_exact_opaque_and_small(self):
  r=self.row;p=ROOT/'app/src/main/assets'/r['path']
  with tempfile.TemporaryDirectory() as tmp:
   subprocess.run([sys.executable,str(HERE/'generate.py'),'--output',tmp],check=True,stdout=subprocess.DEVNULL)
   self.assertEqual(p.read_bytes(),(Path(tmp)/p.name).read_bytes());self.assertEqual(self.data,json.loads((Path(tmp)/'authored-evidence.json').read_text()))
  self.assertEqual(r['sha256'],hashlib.sha256(p.read_bytes()).hexdigest());self.assertEqual(r['bytes'],p.stat().st_size)
  self.assertEqual((8200,50),self.im.size);self.assertEqual((255,255),self.im.getextrema()[3]);self.assertLess(8200*50*4,2*1024**2)
 def test_only_observed_inventory_is_claimed(self):
  r=self.row;self.assertFalse(r['monochrome']);self.assertFalse(r['perforated']);self.assertFalse(r['continuous_source']);self.assertFalse(r['physical_calibration']);self.assertTrue(r['periodic_extension'])
  obs=[x['label'] for x in r['marks'] if x['evidence']=='primary_observed'];self.assertEqual(['KODAK PKR 6033','PKR','outline_right_triangle','9'],obs)
  self.assertFalse(any('5033' in x['label'] for x in r['marks']));self.assertEqual('reversal',r['film_kind'])
  self.assertAlmostEqual(1349/22.5,r['primaryObservedEndMm']-r['primaryObservedStartMm']);self.assertEqual(180,r['coverageEndMm']-r['coverageStartMm'])
  self.assertEqual(['kodachrome64-rails.png'],[p.name for p in (ROOT/'app/src/main/assets/medium120/v10').iterdir()])
 def test_all_formats_are_covered_without_unlimited_roll(self):
  for length in [41.5,56,70,84,168]:self.assertLessEqual(320,410-(length+6)/2);self.assertGreaterEqual(500,410+(length+6)/2)
  for box in [(0,0,3200,50),(5000,0,8200,50)]:self.assertEqual(((8,8),(8,8),(8,8),(255,255)),self.im.crop(box).getextrema())
 def test_centered_clearances_preserve_complete_lower_groups(self):
  layouts=json.loads((HERE/'imprint-layouts.json').read_text());self.assertEqual(self.row['version'],layouts['asset_version']);self.assertEqual(5,len(layouts['layouts']))
  for q in layouts['layouts']:
   self.assertAlmostEqual(q['widthMm']/2,(q['clearStartMm']+q['clearEndMm'])/2);self.assertEqual(1,len(q['moves']))
   m=q['moves'][0];self.assertGreaterEqual(m['targetMm'],q['clearEndMm']+.6);self.assertLess(m['targetMm']+(m['right']-m['left']+1)/10,q['widthMm'])
   # Interpolated sampling guards are represented in legacy sourceX.
   for step in range(round(q['clearStartMm']*40),round(q['clearEndMm']*40)+1):
    local=step/40
    if (m['left']-1)/10<=local<(m['right']+1)/10:continue
    px=q['cropStartPx']+local*10-.5
    for ix in [int(px),int(px)+1]:
     for y in range(25,50):self.assertEqual((8,8,8,255),self.im.getpixel((ix,y)),(q['format'],local))
   for a,b in zip(q['marks'],q['marks'][1:]):self.assertLess(a['targetMm']+(a['sourcePx'][1]-a['sourcePx'][0])/10,b['targetMm'])
if __name__=='__main__':unittest.main()
