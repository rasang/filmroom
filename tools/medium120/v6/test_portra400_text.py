#!/usr/bin/env python3
"""CPU-only positive/negative checks; no Gradle or application state required."""
import argparse, importlib.util, json, tempfile, unittest
from pathlib import Path
import numpy as np
from PIL import Image
SPEC=importlib.util.spec_from_file_location('compiler',Path(__file__).with_name('align_portra400_text.py'))
M=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(M)
GOLDEN='3c329d78f008485437704285ae4441d974b82addc4787cb406c5178578883fd5'
ASSET_ROOT=None
class TextWeightTests(unittest.TestCase):
 def test_reproduce_and_scope(self):
  with tempfile.TemporaryDirectory() as td:
   out=Path(td);r=M.generate(ASSET_ROOT,out,Path('/usr/share/fonts/truetype/dejavu'))
   self.assertEqual(r['sha256'],GOLDEN);self.assertEqual(r['changed_pixels'],9080)
   old=np.array(Image.open(ASSET_ROOT/'v5/portra400-rails.png'))
   new=np.array(Image.open(out/r['file']));diff=np.any(old!=new,axis=2)
   mask=np.array(Image.open(out/'portra400-text-change-mask.png'))!=0
   self.assertTrue(np.array_equal(diff,mask));self.assertTrue(np.array_equal(old[25:],new[25:]))
   allowed=np.zeros((50,8200),bool)
   for g in r['glyphs']:
    if g['allowed_change_box_px'] is not None:
     l,t,rr,b=g['allowed_change_box_px'];allowed[t:b,l:rr]=True
   self.assertFalse(np.any(diff&~allowed));self.assertTrue(np.array_equal(old[~allowed],new[~allowed]))
   self.assertEqual(len(r['marks']),5);self.assertEqual(len(r['glyphs']),61)
   self.assertTrue(np.all(new[:,:,3]==255))
   for longitudinal in [41.5,56,70,84,168]:
    half=(longitudinal+6)/2
    self.assertLessEqual(r['coverage_mm'][0],410-half)
    self.assertGreaterEqual(r['coverage_mm'][1],410+half)
 def test_corrupt_baseline_rejected(self):
  with tempfile.TemporaryDirectory() as td:
   p=Path(td);(p/'v5').mkdir();(p/'v5/portra400-rails.png').write_bytes(b'not the frozen baseline')
   with self.assertRaisesRegex(AssertionError,'baseline'):M.generate(p,p/'out',Path('/usr/share/fonts/truetype/dejavu'))
 def test_unpinned_font_rejected(self):
  with tempfile.TemporaryDirectory() as td:
   p=Path(td);(p/'DejaVuSans.ttf').write_bytes(b'unpinned font')
   with self.assertRaisesRegex(AssertionError,'font pin'):M.generate(ASSET_ROOT,p/'out',p)
if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('--asset-root',required=True,type=Path);args,rest=ap.parse_known_args();ASSET_ROOT=args.asset_root
 unittest.main(argv=['test_portra400_text.py']+rest)
