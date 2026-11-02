"""Private real-fixture mutations; bounded probe/decode, retain no image on failure."""
import json,pathlib,struct,subprocess,os
R=pathlib.Path(__file__).parent; D=R/'results/variant-faults';D.mkdir(exist_ok=True,parents=True)
S=pathlib.Path('/workspace/shared/filmroom-camera-raw-samples'); E=R/'target/debug/codec_probe'
def fixture(i): return bytearray(pathlib.Path(json.load(open(S/f'{i}.manifest.json'))['path']).read_bytes())
def entries(b,o):
 return {struct.unpack_from('<H',b,e)[0]:e for e in range(o+2,o+2+12*struct.unpack_from('<H',b,o)[0],12)}
def value(b,e):return struct.unpack_from('<I',b,e+8)[0]
cases=[]
def add(name,b):
 f=D/(name+('.ARW' if name.startswith('a100') else '.CR2'));f.write_bytes(b);cases.append((name,f))
a=fixture(2346); es=entries(a,struct.unpack_from('<I',a,4)[0]); private=value(a,es[50740]);raw=value(a,es[330]); assert a[private:private+4]==b'\0MRI'
end=private+8+struct.unpack_from('<I',a,private+4)[0];off=private+8;wbg=None
while off<end:
 n=struct.unpack_from('<I',a,off+4)[0]
 if a[off:off+4]==b'\0WBG':wbg=off
 off+=8+n
assert off==end and wbg is not None
for name,off,data in [('missing-wbg',wbg,b'\0XXX'),('cross-raw-mri',private+4,struct.pack('<I',raw-private)),('huge-wbg',wbg+4,b'\xff'*4),('zero-wb',wbg+12,b'\0\0'),('truncated-entropy',len(a)//2,None)]:
 b=a.copy()
 if data is None:b=b[:off]
 else:b[off:off+len(data)]=data
 add('a100-'+name,b)
del a
for i in (129,130):
 b=fixture(i);es=entries(b,struct.unpack_from('<I',b,12)[0]);j=value(b,es[273]);off=j+2
 while b[off:off+2]!=b'\xff\xda':
  assert b[off]==255; off+=2+struct.unpack_from('>H',b,off+2)[0]
 count=b[off+4];predictor=off+5+2*count;assert b[predictor]==1
 c=b.copy();c[predictor]=2;add(f'{i}-predictor2',c)
 c=b.copy();c[predictor+2]=1;add(f'{i}-point-transform1',c)
 c=b.copy();c[j+2:j+8]=b'\xff\xdd\x00\x04\x00\x01';add(f'{i}-restart-control',c)
 c=b.copy();c=c[:-64];add(f'{i}-truncated-tail',c)
 c=b.copy();c[off+2:off+4]=b'\x00\x02';add(f'{i}-bad-sos-size',c)
 coeff=struct.pack('<4H',*( (844,1170,1170,488) if i==129 else (848,1170,1170,490)))
 positions=[];start=0
 while (at:=b.find(coeff,start))>=0:positions.append(at);start=at+1
 assert len(positions)==1,positions
 root=entries(b,struct.unpack_from('<I',b,4)[0]);exif=entries(b,value(b,root[34665]));maker=entries(b,value(b,exif[37500]));color=value(b,maker[0x4001]);assert positions[0]==color+78*2
 c=b.copy();c[positions[0]:positions[0]+8]=bytes(8);add(f'{i}-zero-sraw-coeff',c)
results=[]
del b,c
for name,f in cases:
 out=D/(name+'.linear')
 p=subprocess.run([str(E),'decode',str(f),str(out)],capture_output=True,text=True,timeout=90)
 try:r=json.loads(p.stdout)
 except:r={'unexpected_stdout':p.stdout,'stderr':p.stderr}
 row={'case':name,'exit':p.returncode,'result':r,'partial_output_exists':out.exists()};results.append(row)
 print(json.dumps(row),flush=True);f.unlink();assert not r.get('ok',False) and not out.exists(),row
(D/'results.json').write_text(json.dumps(results,indent=2)+'\n')
