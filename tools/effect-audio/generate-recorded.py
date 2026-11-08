"""Build integrated effect tracks from licensed private recordings, not copied into the source tree.
Usage: python generate-recorded.py /path/to/f3.mp3 /path/to/switch.mp3
Original double focus tone is preserved. See assets/effect_audio/PROVENANCE.txt.
"""
import math,wave,struct,subprocess,pathlib,array,sys,hashlib,random
ROOT=pathlib.Path(__file__).absolute().parents[2]
OUT=ROOT/'app/src/main/assets/effect_audio';OUT.mkdir(parents=True,exist_ok=True)
SR=48000
assert len(sys.argv)==3, __doc__
def recording(path,start,end,peak):
    raw=array.array('f',subprocess.check_output(['ffmpeg','-v','error','-i',path,'-ac','1','-ar',str(SR),'-f','f32le','-']))
    clip=raw[round(start*SR):round(end*SR)]
    gain=peak/max(abs(v) for v in clip)
    fadein=round(.001*SR);fadeout=round(.030*SR)
    return [v*gain*min(1,i/fadein,(len(clip)-1-i)/fadeout) for i,v in enumerate(clip)]
shutter=recording(sys.argv[1],1.824,2.045,.68)
switch=recording(sys.argv[2],.036,.250,.55)
def room_tone():
    # Original deterministic soft room-air bed, not a recorded room or mains hum.
    # 80 Hz high-pass / 1800 Hz low-pass, -52 dBFS RMS before edge fades.
    rng=random.Random(20261108)
    lo=0.0; dc=0.0; bed=[]
    lp=1-math.exp(-2*math.pi*1800/SR)
    hp=1-math.exp(-2*math.pi*80/SR)
    # Warm up filters before the retained three-second segment.
    for i in range(3*SR+SR):
        lo+=lp*(rng.gauss(0,1)-lo)
        dc+=hp*(lo-dc)
        if i>=SR:bed.append(lo-dc)
    scale=10**(-52/20)/math.sqrt(sum(v*v for v in bed)/len(bed))
    fade=int(.040*SR)
    return [v*scale*min(1,i/fade,(len(bed)-1-i)/fade) for i,v in enumerate(bed)]
ROOM_TONE=room_tone()
def make(kind,slow=False):
    y=[0.0]*(3*SR)
    def tone(t,d,f,g):
        # Identical PCM synthesis to beta18 focus tone.
        for i in range(int(d*SR)):
            u=i/SR;env=min(1,u/.003)*min(1,(d-u)/.006)
            y[int(t*SR)+i]+=g*env*math.sin(2*math.pi*f*u)
    def place(clip,t):
        offset=round(t*SR)
        for i,v in enumerate(clip):y[offset+i]+=v
    if kind=='light_table':place(switch,1.0)
    else:
        end=.425 if slow else .850
        tone(end-.075,.025,2300,.13);tone(end-.035,.035,2300,.13)
        place(shutter,.500 if slow else 1.200)
    # Foreground synthesis, gain and trigger positions above remain unchanged.
    y=[v+room for v,room in zip(y,ROOM_TONE)]
    assert max(map(abs,y))<1
    name=kind+('_slow' if slow else '')
    wav=OUT/(name+'.wav')
    with wave.open(str(wav),'wb') as w:
        w.setparams((1,2,SR,0,'NONE','not compressed'));w.writeframes(b''.join(struct.pack('<h',round(v*32767)) for v in y))
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(wav),'-c:a','aac','-b:a','128k','-movflags','+faststart',str(OUT/(name+'.m4a'))],check=True)
    wav.unlink()
for args in [('light_table',False),('optical_leak',False),('optical_leak',True)]:make(*args)
