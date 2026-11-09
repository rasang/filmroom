import pathlib,subprocess,json,array,math
p=pathlib.Path(__file__).absolute().parents[2]/'app/src/main/assets/effect_audio'
checks=0
for name,windows in [('light_table',[(1,1.214)]),('optical_leak',[(1.075,1.15),(1.5,1.721)]),('optical_leak_slow',[(.47,.545),(.62,.841)])]:
 f=p/(name+'.m4a')
 s=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(f)]))['streams'][0]
 assert s['codec_name']=='aac' and s['sample_rate']=='48000' and s['channels']==1
 assert abs(float(s['duration'])-3)<.001;checks+=2
 a=array.array('f',subprocess.check_output(['ffmpeg','-v','error','-i',str(f),'-f','f32le','-ac','1','-']));assert max(map(abs,a))<1;checks+=1
 for start,end in windows:
  seg=a[int(start*48000):int(end*48000)]
  assert math.sqrt(sum(v*v for v in seg)/len(seg))>.015;checks+=1
 # Away from foreground events, require a quiet audible room-air bed, no clipping.
 quiet=[v for i,v in enumerate(a) if not any(start-.04 <= i/48000 <= end+.04 for start,end in windows)]
 rms=math.sqrt(sum(v*v for v in quiet)/len(quiet))
 assert -61 < 20*math.log10(rms) < -55;checks+=1
 assert max(map(abs,quiet))<.025;checks+=1
 assert abs(sum(quiet)/len(quiet))<.0002;checks+=1
 assert max(map(abs,a[:48]))<.001 and max(map(abs,a[143952:144000]))<.001;checks+=1
 print(name,'PASS',s['duration'])
print(checks,'checks passed')
