#!/usr/bin/env python3
"""Independent Python/container checks against Kotlin-generated artifacts. Not a Gallery test."""
from __future__ import annotations
import argparse, hashlib, json, struct, subprocess, sys
from pathlib import Path
from xml.etree import ElementTree as ET
from PIL import Image

NS_I = '{http://ns.google.com/photos/1.0/container/item/}'
NS_C = '{http://ns.google.com/photos/1.0/container/}'
HEADER = b'http://ns.adobe.com/xap/1.0/\0'

def jpeg_info(data: bytes):
    if data[:2] != b'\xff\xd8': raise ValueError('JPEG SOI')
    p, packets, exif = 2, [], []
    while p < len(data):
        if data[p] != 255: raise ValueError('marker')
        while data[p] == 255: p += 1
        tag = data[p]; p += 1
        if tag == 217: return p, packets
        if tag == 1 or 208 <= tag <= 215: continue
        n = struct.unpack_from('>H', data, p)[0]
        if n < 2 or p + n > len(data): raise ValueError('segment')
        payload = data[p+2:p+n]
        if tag == 225 and payload.startswith(HEADER): packets.append(ET.fromstring(payload[len(HEADER):]))
        p += n
        if tag == 218:
            while True:
                p = data.index(b'\xff', p)
                begin = p
                while data[p] == 255: p += 1
                if data[p] == 0 or 208 <= data[p] <= 215: p += 1; continue
                p = begin; break
    raise ValueError('no EOI')

def sef(data: bytes):
    assert data[-4:] == b'SEFT'
    length = struct.unpack_from('<I', data, len(data)-8)[0]
    h = len(data)-8-length
    assert data[h:h+4] == b'SEFH'
    version, count = struct.unpack_from('<II', data, h+4)
    assert length == 12+count*12 and version in (106,107)
    main = None
    for i in range(count):
        kind, back, size = struct.unpack_from('<III', data, h+12+i*12)
        at = h-back
        assert 0 <= at < at+size <= h
        actual, name_len = struct.unpack_from('<II', data, at)
        assert actual == kind and 0 < name_len < 65
        name = data[at+8:at+8+name_len]
        if name == b'MotionPhoto_Data':
            assert kind == 0x0a300000 and main is None
            main = (at+8+name_len, size-8-name_len)
        elif name == b'MotionPhoto_Version':
            assert data[at+8+name_len:at+size] == b'mpv3'
        else: raise ValueError('unexpected SEF record')
    assert main
    return main

def vivo(data: bytes):
    assert data[:5] == b'vivo{'
    # JSON itself is UTF-8; binary words after it are not decoded as JSON text.
    text=data[4:].decode('utf-8',errors='surrogateescape')
    pairs=[]
    obj,end=json.JSONDecoder(object_pairs_hook=lambda xs: pairs.extend(xs) or dict(xs)).raw_decode(text)
    assert len(pairs)==len(obj)
    j=len(text[:end].encode('utf-8'))
    assert struct.unpack_from('>I',data,4+j)[0]==j
    p=4+j+4
    assert data[p:p+11]==b'cameralbum!'
    p+=11
    rec=struct.unpack_from('>I',data,p)[0]
    assert rec==len(data)-p
    ident=data[p+4:-15].decode('ascii')
    assert data[-15:-11]==b'\xff'*4 and data[-11:]==bytes.fromhex('1b2a39485766758493a2b3')
    assert obj['com.android.camera.livephoto']==ident
    assert len(ident)==28 and all(c in '0123456789abcdef' for c in ident)
    return obj

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('run',type=Path); ap.add_argument('fixtures',type=Path); args=ap.parse_args()
    run=args.run.resolve(); original=(args.fixtures/'clip_sdr.mp4').read_bytes()
    fixture=Image.open(args.fixtures/'cover.jpg');fixture.load(); pixels=fixture.convert('RGB').tobytes()
    results=[]
    def check(name,fn):
        try: fn(); results.append(dict(name=name,passed=True)); print('PASS',name)
        except Exception as exc: results.append(dict(name=name,passed=False,error=repr(exc))); print('FAIL',name,repr(exc))
    target=run/'independent-extracted';target.mkdir(exist_ok=True)
    artifacts=json.loads((run/'artifacts.json').read_text())
    for a in artifacts:
        profile=a['profile']; data=Path(a['image']).read_bytes()
        def structure(a=a,data=data,profile=profile):
            j,packets=jpeg_info(data)
            assert j==a['jpegBytes']
            if a['companion']:
                image_meta=vivo(data[j:]); video=Path(a['companion']).read_bytes()
                # MP4 prefix is known from independent fixture identity, not Kotlin offsets.
                assert video[:len(original)]==original
                box=video[len(original):]
                assert struct.unpack_from('>I',box)[0]==len(box) and box[4:8]==b'uuid' and box[8:24]==b'vivoMediaExtInfo'
                video_meta=vivo(box[24:])
                assert image_meta['com.android.camera.livephoto']==video_meta['com.android.camera.livephoto']
                assert image_meta['version']==(2104 if profile.endswith('2104') else 2200)
                assert video_meta['version']==(2104 if profile.endswith('2104') else 2016)
                raw=video[:len(original)]
            elif profile=='HONOR_LEGACY':
                assert data[-60:-54].rstrip()==b'v6_f0'
                n=int(data[-20:].decode().strip()[5:])-20
                assert n==len(original)
                raw=data[len(data)-60-n:-60]
            else:
                assert len(packets)==1
                items=packets[0].findall('.//'+NS_C+'Item')
                assert len(items)==2
                extent=int(items[1].get(NS_I+'Length'))
                offset=len(data)-extent
                padding=int(items[0].get(NS_I+'Padding'))
                assert offset==j+padding
                if profile.startswith('SAMSUNG'):
                    at,n=sef(data);assert at==offset
                    raw=data[at:at+n]
                    assert extent==n+(75 if profile.endswith('107') else 32)
                else: raw=data[offset:]
            assert raw==original
            (target/(profile+'.mp4')).write_bytes(raw)
        check(profile+': independent container/identity',structure)
        def photo(a=a):
            image=Image.open(a['image']);image.load()
            assert image.size==fixture.size and image.convert('RGB').tobytes()==pixels
            assert image.getexif().get(274)==1
        check(profile+': Pillow full pixels and upright EXIF',photo)
        def decode(profile=profile):
            f=target/(profile+'.mp4')
            result=subprocess.run(['ffprobe','-v','error','-count_frames','-show_streams','-of','json',str(f)],capture_output=True,text=True,check=True,timeout=20)
            streams=json.loads(result.stdout)['streams'];v=[s for s in streams if s['codec_type']=='video'];au=[s for s in streams if s['codec_type']=='audio']
            assert len(v)==1 and v[0]['codec_name']=='h264' and int(v[0]['nb_read_frames'])==30
            assert len(au)==1 and au[0]['codec_name']=='aac'
            subprocess.run(['ffmpeg','-hide_banner','-v','error','-xerror','-i',str(f),'-f','null','-'],capture_output=True,check=True,timeout=20)
        check(profile+': strict H264/AAC decode, 30 frames',decode)
        if a['companion']:
            def full_pair(a=a):
                subprocess.run(['ffmpeg','-hide_banner','-v','error','-xerror','-i',a['companion'],'-f','null','-'],capture_output=True,check=True,timeout=20)
            check(profile+': full MP4 with appended UUID decodes',full_pair)
    (run/'independent-results.json').write_text(json.dumps(results,indent=2,ensure_ascii=False)+'\n')
    failures=sum(not r['passed'] for r in results)
    print(f'RESULT {len(results)-failures}/{len(results)}')
    return bool(failures)
if __name__=='__main__':sys.exit(main())
