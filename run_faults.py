"""Real Sony lossless Bayer mutations; private fixtures stay uncommitted."""
import pathlib,json,struct,subprocess,os,signal,time
R=pathlib.Path(__file__).resolve().parent;D=R/'results/faults';D.mkdir(exist_ok=True,parents=True)
source=pathlib.Path('/workspace/shared/filmroom-camera-raw-samples/6736_ILCE-6700.ARW');original=source.read_bytes();E=R/'target/debug/raw_variant_fault'
u16=lambda b,o:struct.unpack_from('<H',b,o)[0];u32=lambda b,o:struct.unpack_from('<I',b,o)[0]
def entries(b,o):return {u16(b,e):e for e in range(o+2,o+2+12*u16(b,o),12)}
def values(b,e):
 t=u16(b,e+2);n=u32(b,e+4);s={1:1,3:2,4:4}[t];o=e+8 if n*s<=4 else u32(b,e+8)
 return [int.from_bytes(b[o+i*s:o+(i+1)*s],'little') for i in range(n)]
todo=[u32(original,4)];seen=set();raw=None
while todo:
 o=todo.pop()
 if not o or o in seen:continue
 seen.add(o);e=entries(original,o);todo.append(u32(original,o+2+12*u16(original,o)))
 if 330 in e:todo+=values(original,e[330])
 if 259 in e and values(original,e[259])==[7] and 324 in e:assert raw is None;raw=e
assert raw
first=values(original,raw[324])[0];size=values(original,raw[325])[0]
offsets=u32(original,raw[324]+8);counts=u32(original,raw[325]+8)
mutations=[('tile-count-short',raw[324]+4,struct.pack('<I',u32(original,raw[324]+4)-1)),
 ('tile-offset-eof',offsets,struct.pack('<I',len(original)-1)),
 ('tile-size-half',counts,struct.pack('<I',size//2)),
 ('tile-width-not512',raw[322]+8,struct.pack('<I',256)),
 ('first-soi-corrupt',first,b'00')]
results=[]
def run(name,path,mode='none',memory=128,disk=1073741824):
 out=D/(name+'.linear');out.unlink(missing_ok=True);t=time.monotonic();p=subprocess.Popen([str(E),str(path),str(out),mode,str(memory),str(disk)],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
 try:stdout,stderr=p.communicate(timeout=180)
 except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);stdout,stderr=p.communicate();stderr+=' TIMEOUT'
 try:v=json.loads(stdout)
 except:v={'unparsed':stdout,'stderr':stderr}
 row={'case':name,'exit':p.returncode,'seconds':time.monotonic()-t,'result':v};results.append(row);(D/'results.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(row),flush=True)
 assert p.returncode==0 and not v.get('ok',True) and v.get('error') and not v['partial_output'] and not v['named_temps'] and v['fd_before']==v['fd_after'],row
 if mode!='none':assert v['cancel_triggered'] and v['error']['code']=='CANCELLED',row
for name,o,v in mutations:
 b=bytearray(original);b[o:o+len(v)]=v;path=D/(name+'.ARW');path.write_bytes(b);del b
 try:run(name,path)
 finally:path.unlink()
path=D/'truncated-last-tile.ARW';path.write_bytes(original[:-4096])
try:run('truncated-last-tile',path)
finally:path.unlink()
run('disk-budget',source,disk=100*1024*1024);run('memory-budget',source,memory=40);run('cannot-raise-budget',source,memory=129)
run('cancel-before',source,'0');run('cancel-integer',source,'1000');run('cancel-developed-output',source,'output')
