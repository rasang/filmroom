import pathlib,json,subprocess,sys,time,hashlib,os,signal
R=pathlib.Path(__file__).resolve().parent;D=R/'results/regression';D.mkdir(exist_ok=True,parents=True);V=pathlib.Path('/workspace/shared/filmroom-raw-variants-development');E=R/'target/debug/codec_probe';cases=[]
for r in json.load(open('/workspace/shared/filmroom-camera-raw-development/results/matrix.json')):
 if r.get('decode',{}).get('ok'):cases.append((r['id'],r['path'],r['decode']['output_sha256']))
merged=json.load(open(V/'arw6-integration/results/merged/results.json'))
for i in [130,129,2085,2976,6931,2346,8846]:
 base=V/'samples' if i in [2085,2976,8846] else pathlib.Path('/workspace/shared/filmroom-camera-raw-samples');m=json.load(open(base/f'{i}.manifest.json'));r=next(r for r in merged if r['id']==i);cases.append((i,m['path'],r['sha256']))
rows=[]
for i,path,gold in cases:
 out=D/f'{i}.linear';out.unlink(missing_ok=True);rss=D/f'{i}.rss';t=time.monotonic();p=subprocess.Popen([sys.executable,str(V/'measure.py'),str(rss),str(E),'decode',path,str(out)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,start_new_session=True)
 try:stdout,stderr=p.communicate(timeout=240)
 except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);stdout,stderr=p.communicate();stderr+='TIMEOUT'
 row={'id':i,'exit':p.returncode,'seconds':time.monotonic()-t,'stdout':stdout,'stderr':stderr,'rss':rss.read_text() if rss.exists() else None,'expected_sha256':gold}
 if p.returncode==0:
  h=hashlib.sha256()
  with out.open('rb') as f:
   for b in iter(lambda:f.read(1048576),b''):h.update(b)
  row.update(sha256=h.hexdigest(),bitexact=h.hexdigest()==gold);out.unlink()
 rows.append(row);print(json.dumps(row),flush=True);(D/'results.json').write_text(json.dumps(rows,indent=2));assert row.get('bitexact'),row
