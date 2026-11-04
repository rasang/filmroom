#!/usr/bin/env python3
"""Portable static guard: fitted formula/placements must not silently diverge."""
import copy,json,unittest
from pathlib import Path
from rebuild_bottom_rails import modeled_anchors
ROOT=Path(__file__).resolve().parent
class BottomCoordinateModelTest(unittest.TestCase):
 def test_all_five_formulas_match_and_reject_drift(self):
  specs=json.loads((ROOT/'bottom-models.json').read_text())['stocks'];count=0
  for spec in specs:
   if spec['mode']!='recompose':continue
   with self.subTest(stock=spec['name']):
    modeled_anchors(spec);altered=copy.deepcopy(spec)
    altered['placements'][0]['arrow_center_mm']+=.001
    with self.assertRaises(AssertionError):modeled_anchors(altered)
   count+=1
  self.assertEqual(count,5)
 def test_three_cycle_step_summaries_match(self):
  specs=json.loads((ROOT/'bottom-models.json').read_text())['stocks'];count=0
  for spec in specs:
   if spec['model']['type']!='three_arrow_cycle':continue
   with self.subTest(stock=spec['name']):
    altered=copy.deepcopy(spec);altered['model']['cycle_steps_mm'][0]+=.001
    with self.assertRaises(AssertionError):modeled_anchors(altered)
    for bad_steps in [[],spec['model']['cycle_steps_mm'][:2],spec['model']['cycle_steps_mm']+[1.0]]:
     altered=copy.deepcopy(spec);altered['model']['cycle_steps_mm']=bad_steps
     with self.assertRaises(AssertionError):modeled_anchors(altered)
   count+=1
  self.assertEqual(count,3)
if __name__=='__main__':unittest.main()
