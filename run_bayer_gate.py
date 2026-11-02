import pathlib,json,subprocess,time,hashlib,sys,os
R=pathlib.Path(__file__).parent;E=R/'target/debug';D=R/'results/bayer';D.mkdir(parents=True,exist_ok=True)
S=pathlib.Path('/workspace/shared/filmroom-camera-raw-samples')
old={131:'c46fb88c1a10af499367c91a3968140f498463bd04422e24963d7cb19fe6eae4',129:'b92f0e90fb051d7079e88ea2027ba9cd4a9cb886ac38202c2195917db63f4fa2',130:'43544b1de1d9af93c14a6798ff38fbddb6ef15a9da68ef811b70e7a4f4da30d5'}
def run(args,name,env=None,cwd=None):
 t=time.monotonic();p=subprocess.run([sys.executable,'/workspace/shared/filmroom-raw-variants-development/measure.py',str(D/name)+'.rss',*map(str,args)],capture_output=True,text=True,env=env,cwd=cwd)
 (D/(name+'.stdout')).write_text(p.stdout);(D/(name+'.stderr')).write_text(p.stderr)
 r={'exit':p.returncode,'seconds':time.monotonic()-t,'stdout':p.stdout,'stderr':p.stderr[-2000:],'rss':pathlib.Path(str(D/name)+'.rss').read_text()}
 assert p.returncode==0,r;return r
rows=[]
for i in [1625,983,131,129,130]:
 m=json.load(open(S/f'{i}.manifest.json'));row={'id':i,'source_sha256':m['download_sha256']}
 row['integer_compare']=run([E/'canon_stream_compare',m['path'],D/f'{i}.rawtmp'],f'{i}-compare')
 p=D/f'{i}.linear';p.unlink(missing_ok=True)
 row['decode']=run([E/'codec_probe','decode',m['path'],p,128],f'{i}-decode');assert json.loads(row['decode']['stdout'])['ok']
 h=hashlib.sha256()
 with p.open('rb') as f:
  while b:=f.read(1024*1024):h.update(b)
 row['sha256']=h.hexdigest()
 if i in old:row['old_sha_exact']=row['sha256']==old[i];assert row['old_sha_exact'],row
 if i in [1625,983]:
  env=os.environ.copy();env.update(CANON_BAYER_FIXTURE=m['path'],CANON_STREAM_OUTPUT=str(p),CANON_REFERENCE_OUTPUT=str(D/f'{i}.reference'))
  row['full_integer_float_oracle']=run([env['CARGO_HOME']+'/bin/cargo','+1.88.0','test','--offline','-j2','--lib','canon_bayer_stream_reference','--','--ignored'],f'{i}-float-reference',env,R/'codecs')
 rows.append(row);(D/'matrix.json').write_text(json.dumps(rows,indent=2));print(json.dumps(row),flush=True)
for delay in [0,150,2500,7000]:
 p=D/f'cancel-{delay}.linear';p.unlink(missing_ok=True);m=json.load(open(S/'983.manifest.json'))
 print(run([E/'canon_stream_cancel',m['path'],p,delay],f'cancel-{delay}'),flush=True)
# Existing condition-triggered oracle cancels AFTER observable developed output.
p=D/'cancel-develop.linear';p.unlink(missing_ok=True)
r=run([E/'raw_variant_fault',m['path'],p,'output'], 'cancel-develop');info=json.loads(r['stdout']);assert info['error']['code']=='CANCELLED' and info['cancel_triggered'] and not info['partial_output'] and not info['named_temps'] and info['fd_before']==info['fd_after'];print(r,flush=True)
assert not list(D.glob('*.rawtmp')) and not list(D.glob('.canon-*'))
