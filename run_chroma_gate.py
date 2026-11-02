import pathlib,json,subprocess,time,hashlib,sys,os
R=pathlib.Path(__file__).parent;E=R/'target/debug';D=R/'results/chroma';D.mkdir(parents=True,exist_ok=True)
old={x['id']:x for x in json.load(open(R/'results/storage/matrix.json'))};rows=[]
def manifest(i):
 b=pathlib.Path('/workspace/shared/filmroom-camera-raw-samples' if i<1000 else '/workspace/shared/filmroom-raw-variants-development/samples');return json.load(open(next(b.glob(f'{i}*.manifest.json'))))
def run(args,name,env=None):
 t=time.monotonic();p=subprocess.run([sys.executable,'/workspace/shared/filmroom-raw-variants-development/measure.py',str(D/name)+'.rss',*map(str,args)],capture_output=True,text=True,env=env)
 (D/(name+'.stdout')).write_text(p.stdout);(D/(name+'.stderr')).write_text(p.stderr)
 r={'exit':p.returncode,'seconds':time.monotonic()-t,'stdout':p.stdout,'stderr':p.stderr[-2000:],'rss':pathlib.Path(str(D/name)+'.rss').read_text()}
 assert p.returncode==0,r;return r
for i in [130,129,2085,2976]:
 m=manifest(i);row={'id':i,'source_sha256':m['download_sha256']};env=os.environ.copy()
 if i==130:
  (D/'130.yuv').unlink(missing_ok=True);env['CANON_QA_YUV_SAVE']=str(D/'130.yuv')
 row['u16_compare']=run([E/'canon_stream_compare',m['path'],D/f'{i}.rawtmp'],f'{i}-compare',env)
 output=D/f'{i}.linear';output.unlink(missing_ok=True)
 row['decode']=run([E/'codec_probe','decode',m['path'],output,128],f'{i}-decode');assert json.loads(row['decode']['stdout'])['ok']
 h=hashlib.sha256()
 with output.open('rb') as f:
  while b:=f.read(1024*1024):h.update(b)
 row['sha256']=h.hexdigest();row['matches_storage_phase']=row['sha256']==old[i]['output_sha256']
 assert row['matches_storage_phase']==(i!=130),row
 rows.append(row);(D/'matrix.json').write_text(json.dumps(rows,indent=2));print(json.dumps(row),flush=True)
for delay in [0,150,2500,7000]:
 output=D/f'cancel-{delay}.linear';output.unlink(missing_ok=True)
 r=run([E/'canon_stream_cancel',manifest(130)['path'],output,delay],f'cancel-{delay}');print(r,flush=True)
assert not list(D.glob('*.rawtmp')) and not list(D.glob('.canon-*'))
output=D/'131.linear';output.unlink(missing_ok=True)
r=run([E/'codec_probe','decode',manifest(131)['path'],output,128],'131-bayer-decode');assert json.loads(r['stdout'])['ok']
h=hashlib.sha256()
with output.open('rb') as f:
 while b:=f.read(1024*1024):h.update(b)
expected='c46fb88c1a10af499367c91a3968140f498463bd04422e24963d7cb19fe6eae4'
assert h.hexdigest()==expected
(D/'bayer-131.json').write_text(json.dumps({'decode':r,'sha256':h.hexdigest(),'exact_to_ordinary_stage':True},indent=2))
