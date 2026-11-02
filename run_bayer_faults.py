import pathlib,struct,json,subprocess
R=pathlib.Path(__file__).parent;D=R/'results/bayer-faults';D.mkdir(parents=True,exist_ok=True);E=R/'target/debug';S=pathlib.Path('/workspace/shared/filmroom-camera-raw-samples');results=[]
def entries(b,o):return {struct.unpack_from('<H',b,e)[0]:e for e in range(o+2,o+2+12*struct.unpack_from('<H',b,o)[0],12)}
def value(b,e):return struct.unpack_from('<I',b,e+8)[0]
for i in [1625,983]:
 m=json.load(open(S/f'{i}.manifest.json'));b=bytearray(pathlib.Path(m['path']).read_bytes());es=entries(b,struct.unpack_from('<I',b,12)[0]);j=value(b,es[273]);count=value(b,es[279]);stripe=value(b,es[50752]);p=j+2
 while b[p:p+2]!=b'\xff\xda':p+=2+int.from_bytes(b[p+2:p+4],'big')
 predictor=p+5+2*b[p+4]
 for case in ['predictor0','predictor8','pt1','dri','tail-truncated','missing-eoi','slice-count','slice-zero','slice-sum']:
  c=b.copy()
  if case.startswith('predictor'):c[predictor]=int(case[-1])
  elif case=='pt1':c[predictor+2]=1
  elif case=='dri':
   c[j+2:j+2]=b'\xff\xdd\x00\x04\x00\x01';struct.pack_into('<I',c,es[279]+8,count+6)
  elif case=='tail-truncated':c=c[:-32]
  elif case=='missing-eoi':c[j+count-2:j+count]=b'\xff\x00'
  elif case=='slice-count':struct.pack_into('<H',c,stripe,65535)
  elif case=='slice-zero':struct.pack_into('<HH',c,stripe,1,0)
  elif case=='slice-sum':struct.pack_into('<HHH',c,stripe,1,3,5)
  f=D/f'{i}-{case}.CR2';out=D/f'{i}-{case}.linear';f.write_bytes(c);del c
  p1=subprocess.run([str(E/'codec_probe'),'decode',str(f),str(out)],capture_output=True,text=True,timeout=120);r=json.loads(p1.stdout)
  row={'id':i,'case':case,'result':r,'exit':p1.returncode,'partial_output':out.exists(),'named_temps':[p.name for p in D.glob('.canon-*')]};results.append(row);print(json.dumps(row),flush=True)
  f.unlink();assert not r['ok'] and not row['partial_output'] and not row['named_temps'],row
 del b
m=json.load(open(S/'983.manifest.json'))
for name,memory,disk,expected in [('memory-small',32,1024**3,'MEMORY_BUDGET'),('memory-no-raise',129,1024**3,'MEMORY_BUDGET'),('disk-small',128,1,'DISK_BUDGET')]:
 out=D/f'{name}.linear';p=subprocess.run([str(E/'raw_variant_fault'),m['path'],str(out),'none',str(memory),str(disk)],capture_output=True,text=True,timeout=30);r=json.loads(p.stdout);assert r['error']['code']==expected and not r['partial_output'] and not r['named_temps'] and r['fd_before']==r['fd_after'];results.append({'case':name,'result':r})
out=D/'existing.linear';out.write_bytes(b'KEEP EXISTING OUTPUT');p=subprocess.run([str(E/'codec_probe'),'decode',m['path'],str(out)],capture_output=True,text=True,timeout=30);r=json.loads(p.stdout);assert r['error']['code']=='OUTPUT_EXISTS' and out.read_bytes()==b'KEEP EXISTING OUTPUT';out.unlink();results.append({'case':'existing-output-untouched','result':r})
(D/'results.json').write_text(json.dumps(results,indent=2)+'\n')
