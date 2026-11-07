#!/usr/bin/env python3
"""Generate fresh synthetic CI inputs, never OEM samples or personal media."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
from PIL import Image
ROOT = Path(__file__).resolve().parents[1]
FIX = ROOT / 'core/src/test/resources/fixtures'
FIX.mkdir(parents=True, exist_ok=True)
video = FIX / 'clip_sdr.mp4'
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y',
    '-f','lavfi','-i','testsrc2=size=160x96:rate=30:duration=1',
    '-f','lavfi','-i','sine=frequency=660:sample_rate=48000:duration=1',
    '-map','0:v:0','-map','1:a:0','-t','1','-c:v','libx264','-preset','medium',
    '-crf','18','-pix_fmt','yuv420p','-bf','0','-c:a','aac','-b:a','96k',
    '-movflags','+faststart',str(video)],check=True)
probe = json.loads(subprocess.check_output(['ffprobe','-v','error','-count_frames',
    '-show_streams','-of','json',str(video)]))
v = [s for s in probe['streams'] if s['codec_type']=='video']
assert len(v)==1 and v[0]['codec_name']=='h264' and int(v[0]['nb_read_frames'])==30
assert any(s['codec_type']=='audio' and s['codec_name']=='aac' for s in probe['streams'])
raw = subprocess.check_output(['ffmpeg','-v','error','-i',str(video),'-frames:v','1',
    '-f','rawvideo','-pix_fmt','rgb24','-'])
Image.frombytes('RGB',(160,96),raw).save(FIX/'cover.jpg',quality=95)
assets=ROOT/'android/src/androidTest/assets'
assets.mkdir(parents=True,exist_ok=True)
shutil.copyfile(video,assets/'clip_sdr.mp4')
manifest={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (video,FIX/'cover.jpg')}
(FIX/'fixture-identity.json').write_text(json.dumps({'synthetic':True,'files':manifest},indent=2)+'\n')
print(json.dumps(manifest,indent=2))
