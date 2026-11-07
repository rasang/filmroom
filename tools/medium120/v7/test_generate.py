#!/usr/bin/env python3
"""Reproduction, metadata and finite-source checks, independent of Android."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from PIL import Image

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent

class Authored120Test(unittest.TestCase):
    def test_reproduces_pinned_runtime_assets_and_metadata(self):
        saved=json.loads((HERE/'authored-evidence.json').read_text())
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([sys.executable,str(HERE/'generate.py'),'--output',tmp],check=True,stdout=subprocess.DEVNULL)
            fresh=json.loads((Path(tmp)/'authored-evidence.json').read_text())
            self.assertEqual(saved,fresh)
            for row in saved['assets']:
                original=ROOT/'app/src/main/assets'/row['path']
                generated=Path(tmp)/original.name
                self.assertEqual(original.read_bytes(),generated.read_bytes())
                self.assertEqual(row['sha256'],hashlib.sha256(original.read_bytes()).hexdigest())
                self.assertEqual(row['bytes'],original.stat().st_size)
                im=Image.open(original)
                self.assertEqual((8200,50),im.size)
                self.assertEqual((255,255),im.getextrema()[3])
                self.assertLess(im.width*im.height*4,2*1024*1024)
                self.assertEqual(im.getchannel('R').tobytes(),im.getchannel('G').tobytes())
                self.assertEqual(im.getchannel('G').tobytes(),im.getchannel('B').tobytes())
                self.assertFalse(row['physical_calibration'])
                self.assertTrue(row['monochrome'])
                for length in [41.5,56,70,84,168]:
                    self.assertGreaterEqual(410-(length+6)/2,row['coverageStartMm'])
                    self.assertLessEqual(410+(length+6)/2,row['coverageEndMm'])
                self.assertGreater(row['coverageStartMm'],300)
                self.assertLess(row['coverageEndMm'],510)
                # Large unknown atlas regions contain no extrapolated marks.
                for box in [(0,0,3000,50),(5100,0,8200,50)]:
                    self.assertEqual(((8,8),(8,8),(8,8),(255,255)),im.crop(box).getextrema())

    def test_marks_are_explicit_and_never_tiled(self):
        rows=json.loads((HERE/'authored-evidence.json').read_text())['assets']
        hp5,tmax,trix=rows
        self.assertEqual(['10','11','12','13','14'],[m['text'] for m in hp5['marks'] if m['rail']==0 and m['kind']=='text'])
        self.assertEqual(5,sum(m['kind']=='solid_left_triangle' for m in hp5['marks']))
        self.assertEqual(6,sum(m['kind']=='solid_right_triangle' for m in tmax['marks']))
        self.assertEqual(['5','6','7'],[m['text'] for m in tmax['marks'] if m['rail']==1 and m['kind']=='text'])
        self.assertFalse(any(m.get('text')=='400' for m in hp5['marks']))
        self.assertTrue(any(m.get('text')=='A' for m in hp5['marks']))
        self.assertLess(tmax['coverageEndMm']-tmax['coverageStartMm']-174,1)
        self.assertEqual(7,sum(m['kind']=='solid_right_triangle' for m in trix['marks']))
        self.assertEqual(['4','5','6'],[m['text'] for m in trix['marks'] if m['rail']==1 and m['kind']=='text'])
        self.assertGreater(trix['coverageEndMm']-trix['coverageStartMm'],195)

if __name__=='__main__': unittest.main()
