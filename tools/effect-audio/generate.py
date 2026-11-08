"""Original synthesized effects, no third-party recordings. Python + ffmpeg."""
import math, random, wave, struct, subprocess, pathlib
ROOT=pathlib.Path(__file__).absolute().parents[2]
OUT=ROOT/'app/src/main/assets/effect_audio'; OUT.mkdir(parents=True,exist_ok=True)
SR=48000

def make(kind,slow=False):
    y=[0.0]*(3*SR); rng=random.Random(817)
    def tone(t,d,f,g):
        for i in range(int(d*SR)):
            u=i/SR; env=min(1,u/.003)*min(1,(d-u)/.006)
            y[int(t*SR)+i]+=g*env*math.sin(2*math.pi*f*u)
    def click(t,d,g,body):
        prev=0.0
        for i in range(int(d*SR)):
            u=i/SR; noise=rng.uniform(-1,1); hp=noise-prev;prev=noise
            attack=min(1,u/.0008)
            v=(.35*hp*math.exp(-u/ .006)+.55*math.sin(2*math.pi*body*u)*math.exp(-u/.022)+.15*math.sin(2*math.pi*body*2.31*u)*math.exp(-u/.011))
            y[int(t*SR)+i]+=g*attack*v*min(1,(d-u)/.004)
    if kind=='light_table':
        click(1.0,.07,.55,1700);click(1.027,.045,.21,950)
    else:
        # Two short confirmation tones finish at focus completion.
        end=.425 if slow else .850
        tone(end-.075,.025,2300,.13);tone(end-.035,.035,2300,.13)
        t=.500 if slow else 1.200
        click(t,.085,.66,430);click(t+.032,.065,.29,1050);click(t+.095,.06,.37,660)
    peak=max(map(abs,y)); assert peak<1,peak
    name=kind+('_slow' if slow else '')
    wav=OUT/(name+'.wav')
    with wave.open(str(wav),'wb') as w:
        w.setparams((1,2,SR,0,'NONE','not compressed'));w.writeframes(b''.join(struct.pack('<h',round(v*32767)) for v in y))
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(wav),'-c:a','aac','-b:a','128k','-movflags','+faststart',str(OUT/(name+'.m4a'))],check=True)
    wav.unlink()
for args in [('light_table',False),('optical_leak',False),('optical_leak',True)]:make(*args)
