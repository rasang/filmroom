import pathlib,json,subprocess,resource,time,hashlib,sys
R=pathlib.Path(__file__).parent; E=R/'target/debug'; D=R/'results/storage'; D.mkdir(parents=True,exist_ok=True)
old={x['id']:x for x in json.load(open('/workspace/shared/filmroom-raw-variants-development/results/phase2/matrix.json'))}
rows=[]
def run(args,log):
 t=time.monotonic()
 p=subprocess.run([sys.executable,'/workspace/shared/filmroom-raw-variants-development/measure.py',str(log)+'.rss',*map(str,args)],capture_output=True,text=True)
 pathlib.Path(str(log)+'.stdout').write_text(p.stdout);pathlib.Path(str(log)+'.stderr').write_text(p.stderr)
 return {'exit':p.returncode,'seconds':time.monotonic()-t,'stdout':p.stdout,'stderr':p.stderr[-2000:],'rss':pathlib.Path(str(log)+'.rss').read_text()}
for id in (129,130,2085,2976):
 base=pathlib.Path('/workspace/shared/filmroom-camera-raw-samples' if id<1000 else '/workspace/shared/filmroom-raw-variants-development/samples')
 manifests=list(base.glob(f'{id}*.manifest.json'));m=json.load(open(manifests[0]));row={'id':id,'source_sha256':m['download_sha256']}
 row['u16_compare']=run([E/'canon_stream_compare',m['path'],D/f'{id}.rawtmp'],D/f'{id}-compare')
 if row['u16_compare']['exit']: rows.append(row);(D/'matrix.json').write_text(json.dumps(rows,indent=2));sys.exit(1)
 output=D/f'{id}.linear';output.unlink(missing_ok=True)
 row['stream_decode']=run([E/'codec_probe','decode',m['path'],output,128],D/f'{id}-decode')
 info=json.loads(row['stream_decode']['stdout']); row['decode_result']=info
 if not info.get('ok'):rows.append(row);(D/'matrix.json').write_text(json.dumps(rows,indent=2));sys.exit(1)
 h=hashlib.sha256()
 with output.open('rb') as f:
  while b:=f.read(1024*1024):h.update(b)
 row['output_sha256']=h.hexdigest();row['bytes']=output.stat().st_size
 if id in old:
  row['float_exact_to_phase2']=row['output_sha256']==old[id]['output_sha256']
  assert row['float_exact_to_phase2'],row
 print(json.dumps(row),flush=True);rows.append(row);(D/'matrix.json').write_text(json.dumps(rows,indent=2))
