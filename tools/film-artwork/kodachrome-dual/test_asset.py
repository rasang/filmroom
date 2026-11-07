import unittest,json,hashlib
from pathlib import Path
from PIL import Image
P=Path(__file__).resolve().parent
A=P.parents[2]/'app/src/main/assets/film-rolls'
KEY='kodak_kodachrome_64'
class KodachromeDualArtworkTest(unittest.TestCase):
 def test_other_24_assets_are_identical(self):
  before=json.loads((P/'before-all25-hashes.json').read_text());assert len(before)==25
  changed=[n for n,h in before.items() if hashlib.sha256((A/n).read_bytes()).hexdigest()!=h]
  self.assertEqual(changed,[KEY+'.webp'])
 def test_all_asset_manifests_match_bytes(self):
  rows=json.loads((A/'manifest.json').read_text())['assets'];self.assertEqual(len(rows),25)
  for r in rows:
   b=(A/r['path']).read_bytes();self.assertEqual(r['bytes'],len(b));self.assertEqual(r['sha256'],hashlib.sha256(b).hexdigest())
 def test_dual_asset_contract(self):
  im=Image.open(A/(KEY+'.webp'));self.assertEqual(im.size,(240,240));self.assertEqual(im.mode,'RGBA');self.assertEqual(im.getchannel('A').getbbox(),(47,39,197,208))
  rows=json.loads((A/'medium-format-manifest.json').read_text())['entries'];r=next(r for r in rows if r['profile_id']==KEY);self.assertEqual(r['form'],'135 + 120');self.assertEqual(r['sha256'],hashlib.sha256((A/(KEY+'.webp')).read_bytes()).hexdigest());self.assertEqual(sum(r['form']=='135 + 120' for r in rows),12)
 def test_observation_and_geometry_evidence(self):
  rows=json.loads((A/'manifest.json').read_text())['profiles'];r=next(r for r in rows if r['profile_id']==KEY);self.assertEqual(r['illustrated_formats'],['135','120']);self.assertFalse(r['grants_frame_support']);self.assertEqual(r['source_120_page'],8)
  qa=json.loads((P/'MODEL_QA.json').read_text());self.assertTrue(qa['pass']);self.assertEqual(qa['original135_geometry_unchanged_except_x_translation_mm'],-18);self.assertEqual(len(qa['120_meshes_identical_to_approved_pair_template']),5);self.assertFalse(qa['reference_photograph_pixels_used'])
if __name__=='__main__':unittest.main()
