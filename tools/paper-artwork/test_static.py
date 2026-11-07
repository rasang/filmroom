"""Lightweight pre-render contract checks. Does not replace Android UI tests."""
import json,re,xml.etree.ElementTree as ET
from pathlib import Path
R=Path(__file__).resolve().parents[2]
cat=json.loads((R/'app/src/main/assets/engine-data/profile_catalog.json').read_text())['profiles']
expected={p['id'] for p in cat if p['kind'] in ['paper','cine_print']}
models=json.loads((R/'tools/paper-artwork/models.json').read_text())
assert len(expected)==10 and {x['id'] for x in models}==expected
assert len({x['id'] for x in models})==len(models)
from PIL import Image
for x in models:
 im=Image.open(R/'app/src/main/assets/print-media'/f"{x['id']}.webp");assert im.size==(240,240) and im.mode=='RGBA'
for x in models:assert (R/'tools/paper-artwork/labels'/f"{x['id']}.png").exists()
source=(R/'app/src/main/java/dev/filmroom/ui/PrintMediumArtwork.kt').read_text()
for id in expected:assert '"'+id+'"' in source
activity=(R/'app/src/main/java/dev/filmroom/MainActivity.kt').read_text()
paper=activity[activity.index('    private fun paperChoices'):activity.index('    private val imprintPreviewSession')]
assert 'AlertDialog' not in paper and 'retainStrip' in paper
assert 'current.parameters.paperStock!=id' in paper
assert 'current.parameters.outputRoute=="print"' in paper
assert 'PrintMediumLabels.label' in paper
for language in ['values','values-en','values-zh','values-ja','values-ko']:
 strings={e.attrib['name'] for e in ET.parse(R/f'app/src/main/res/{language}/paper_carousel.xml').getroot()}
 assert strings=={'paper_none','film_none'}
print('PASS: 10 profile identities, labels, nonmodal selection contract, 5 language resource files; Android not run')
