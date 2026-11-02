import pathlib,subprocess,json,time,hashlib,sys,os,signal
R=pathlib.Path(__file__).resolve().parent;D=R/'results/first';D.mkdir(exist_ok=True);T=R/'target/debug';M=pathlib.Path('/workspace/shared/filmroom-raw-variants-development/measure.py');S=pathlib.Path('/workspace/shared/filmroom-camera-raw-samples')
tests=[p for p in (T/'deps').glob('film_codecs-*') if p.is_file() and os.access(p,os.X_OK) and '.' not in p.name];assert len(tests)==1,tests
records=[]
def run(name,cmd,env=None):
 t=time.monotonic();p=subprocess.Popen([sys.executable,str(M),str(D/(name+'.rss')),*map(str,cmd)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env,start_new_session=True)
 try:out,err=p.communicate(timeout=240)
 except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);out,err=p.communicate();err+=' TIMEOUT'
 r=dict(name=name,exit=p.returncode,seconds=time.monotonic()-t,stdout=out,stderr=err,rss=(D/(name+'.rss')).read_text());records.append(r);(D/'commands.json').write_text(json.dumps(records,indent=2));print(json.dumps(r),flush=True);assert p.returncode==0,r
 return r
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
for id in [6736,6929,8845]:
 inp=next(S.glob(str(id)+'_*.ARW'));env=dict(os.environ,SONY_BAYER_FIXTURE=str(inp),SONY_BAYER_SCRATCH=str(D),RAYON_NUM_THREADS='2');run(str(id)+'-integer',[tests[0],'sony_bayer_integer_oracle','--ignored','--nocapture'],env)
 out=D/(str(id)+'.linear');ref=D/(str(id)+'-reference.linear');out.unlink(missing_ok=True);ref.unlink(missing_ok=True)
 run(str(id)+'-stream',[T/'codec_probe','decode',inp,out]);env['SONY_BAYER_REFERENCE_LINEAR']=str(ref);run(str(id)+'-reference',[tests[0],'sony_bayer_reference_linear','--ignored','--nocapture'],env)
 a=sha(out);b=sha(ref);r=dict(id=id,bytes=out.stat().st_size,sha256=a,reference_sha256=b,bitexact=a==b);print(json.dumps(r),flush=True);(D/(str(id)+'-equality.json')).write_text(json.dumps(r,indent=2));assert a==b,r;ref.unlink()
