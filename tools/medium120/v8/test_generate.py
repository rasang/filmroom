#!/usr/bin/env python3
"""Determinism, literal source inventory and honest reconstruction limits."""
import hashlib,json,subprocess,sys,tempfile,unittest
from pathlib import Path
from PIL import Image
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
class CineStill120Test(unittest.TestCase):
 def test_exact_regeneration_and_pinned_asset(self):
  saved=json.loads((HERE/'authored-evidence.json').read_text())
  with tempfile.TemporaryDirectory() as tmp:
   subprocess.run([sys.executable,str(HERE/'generate.py'),'--output',tmp],check=True,stdout=subprocess.DEVNULL)
   self.assertEqual(saved,json.loads((Path(tmp)/'authored-evidence.json').read_text()))
   row=saved['asset'];p=ROOT/'app/src/main/assets'/row['path']
   self.assertEqual(p.read_bytes(),(Path(tmp)/p.name).read_bytes())
   self.assertEqual(row['sha256'],hashlib.sha256(p.read_bytes()).hexdigest())
   self.assertEqual(row['bytes'],p.stat().st_size)
   im=Image.open(p);self.assertEqual((8200,50),im.size);self.assertEqual((255,255),im.getextrema()[3])
   self.assertLess(im.width*im.height*4,2*1024*1024)
   self.assertNotEqual(im.getchannel('R').tobytes(),im.getchannel('G').tobytes())
   self.assertNotEqual(im.getchannel('G').tobytes(),im.getchannel('B').tobytes())
 def test_only_observed_inventory_and_explicit_unknown_seams(self):
  row=json.loads((HERE/'authored-evidence.json').read_text())['asset'];m=row['marks']
  self.assertFalse(row['same_roll_verified']);self.assertFalse(row['physical_calibration']);self.assertFalse(row['continuous_original'])
  self.assertFalse(row['perforated']);self.assertFalse(row['monochrome'])
  self.assertEqual(27,len(m))
  self.assertEqual(['6.','7_partial','8','9.','10','11','12','13'],[x['label'] for x in m if x['rail']==0 and x['kind']=='observed_text'])
  self.assertEqual(5,sum(x['label']=='CINESTILL 800T' for x in m))
  self.assertEqual(2,sum(x['label']=='A' for x in m))
  self.assertEqual(4,sum(x['label'].startswith('4819') for x in m))
  self.assertEqual(4,len(row['reconstructed_blank_seams']))
  im=Image.open(ROOT/'app/src/main/assets'/row['path'])
  for gap in row['reconstructed_blank_seams']:
   # Leave a two-pixel antialias margin at the photographed crop edges.
   lo=int(gap['start_mm']*10)+2;hi=int(gap['end_mm']*10)-2
   if hi>lo:self.assertEqual(((8,8),(8,8),(8,8),(255,255)),im.crop((lo,0,hi,50)).getextrema())
  for box in [(0,0,1800,50),(5100,0,8200,50)]:self.assertEqual(((8,8),(8,8),(8,8),(255,255)),im.crop(box).getextrema())
  for length in [41.5,56,70,84,168]:
   self.assertGreaterEqual(410-(length+6)/2,row['coverageStartMm']);self.assertLessEqual(410+(length+6)/2,row['coverageEndMm'])
 def test_reference_hashes_and_no_source_photos_packaged(self):
  rows=json.loads((HERE/'source-hashes.json').read_text())['files']
  self.assertEqual(5,len(rows));self.assertEqual(5,len({r['sha256'] for r in rows}))
  self.assertTrue(all(r['dimensions']==[1320,1758] for r in rows))
  self.assertEqual(['cinestill800t-rails.png'],[p.name for p in (ROOT/'app/src/main/assets/medium120/v8').iterdir()])
if __name__=='__main__':unittest.main()
