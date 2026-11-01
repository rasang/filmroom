#!/usr/bin/env python3
"""Generate trusted frame film kinds from bundled scientific catalog (no name inference)."""
from pathlib import Path
import hashlib,json,re
root=Path(__file__).resolve().parents[1]
p=root/'app/src/main/assets/engine-data/profile_catalog.json'
rows=json.loads(p.read_text())['profiles'];selected=[r for r in rows if r['kind'] in ('negative','reversal')]
assert len({r['id'] for r in selected})==len(selected)
body='    // BEGIN GENERATED CATALOG\n    const val catalogSha256="'+hashlib.sha256(p.read_bytes()).hexdigest()+'"\n    val kinds:Map<String,FrameAppearanceContext.FilmKind> = mapOf(\n'
body+='\n'.join('        "'+r['id']+'" to FrameAppearanceContext.FilmKind.'+r['kind'].upper()+',' for r in selected)
body+='\n    )\n    // END GENERATED CATALOG'
target=root/'app/src/main/java/dev/filmroom/border/FrameAppearanceContext.kt'
s=target.read_text();s,count=re.subn(r'    // BEGIN GENERATED CATALOG.*?    // END GENERATED CATALOG',lambda _:body,s,flags=re.S);assert count==1
target.write_text(s)
