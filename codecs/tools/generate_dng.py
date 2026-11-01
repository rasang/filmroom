#!/usr/bin/env python3
"""CC0 synthetic sensor samples; no real photos or embedded previews."""
import argparse,struct,os

def generate(path,w=64,h=384,linear=False,orientation=1,pattern='RGGB',crop=False,endian='<',phone=None,baseline_exposure=None):
 def pack(code,*v):return struct.pack(endian+code,*v)
 tags={}
 def add(t,typ,values):
  if typ in (1,2,7): data=bytes(values); n=len(data)
  elif typ in (3,4): data=pack(('H' if typ==3 else 'I')*len(values),*values); n=len(values)
  elif typ in (5,10): data=b''.join(pack('ii' if typ==10 else 'II',int(a),int(b))for a,b in values);n=len(values)
  tags[t]=(typ,n,data)
 if baseline_exposure is not None:add(50730,10,[(round(baseline_exposure*1000),1000)])
 cpp=3 if linear else 1
 add(254,4,[0]);add(256,4,[w]);add(257,4,[h]);add(258,3,[16]*cpp);add(259,3,[1]);add(262,3,[34892 if linear else 32803]);add(273,4,[0]);add(274,3,[orientation]);add(277,3,[cpp]);add(278,4,[h]);add(279,4,[w*h*cpp*2]);add(284,3,[1]);add(50706,1,[1,4,0,0]);add(50707,1,[1,3,0,0]);add(50728,5,[(1,2),(1,1),(2,3)]);add(50778,3,[21])
 matrix=[3.2404542,-1.5371385,-.4985314,-.969266,1.8760108,.041556,.0556434,-.2040259,1.0572252]
 add(50721,10,[(round(x*1000000),1000000)for x in matrix]);add(50717,4,[4095]*cpp)
 if linear:add(50713,3,[1,1]);add(50714,3,[64,64,64])
 else:add(33421,3,[2,2]);add(33422,1,[{'R':0,'G':1,'B':2}[x]for x in pattern]);add(50710,1,[0,1,2]);add(50711,3,[1]);add(50713,3,[2,2]);add(50714,3,[64,80,96,112])
 if crop:
  add(50829,4,[2,2,h-2,w-2]);add(50719,4,[1,3]);add(50720,4,[w-8,h-10])
 if phone:
  ident=[(1 if i%4==0 else 0,1)for i in range(9)]
  if phone=='calibration':ident[1]=(1,10)
  if phone=='diagonal':ident[0]=(63,64);ident[8]=(129,128)
  add(50723,10,ident);add(50724,10,ident)
  add(50964,10,[(9642,10000),(0,1),(0,1),(0,1),(1,1),(0,1),(0,1),(0,1),(8249,10000)])
  add(50965,10,[(9642,10000),(0,1),(0,1),(0,1),(1,1),(0,1),(0,1),(0,1),(8249,10000)])
  payload=struct.pack('>10I4dI4f',0,0,h,w,0,1,2,2,2,2,1.,1.,0.,0.,1,1.,1.1,1.2,1.3)
  op=struct.pack('>5I',1,9,0x01030000,0 if phone=='mandatory' else 1,len(payload))+payload
  if phone=='malformed':op=op[:-7]
  add(51009,7,op)
 add(270,2,b'SYNTHETIC_PRIVATE_PATH_GPS_NOT_FOR_LOGS\0')
 def header():
  offset=8+2+12*len(tags)+4;entries=[];extra=bytearray()
  for tag,(typ,n,data) in sorted(tags.items()):
   if len(data)<=4:v=data.ljust(4,b'\0')
   else:
    if (offset+len(extra))%2:extra.append(0)
    v=pack('I',offset+len(extra));extra.extend(data)
   entries.append(pack('HHI',tag,typ,n)+v)
  return (b'II' if endian=='<' else b'MM')+pack('HIH',42,8,len(tags))+b''.join(entries)+pack('I',0)+extra
 hdr=header();add(273,4,[len(hdr)]);hdr=header()
 with open(path,'wb')as f:
  f.write(hdr)
  for y in range(h):
   row=[]
   for x in range(w):
    vals=[.12+(x%19)*.008,.22+(y%17)*.006,.32+((x+y)%13)*.004]
    if linear: row.extend(round(64+v*(4095-64))for v in vals)
    else:
     ch={'R':0,'G':1,'B':2}[pattern[(y%2)*2+x%2]];black=[64,80,96,112][(y%2)*2+x%2];row.append(round(black+vals[ch]*(4095-black)))
   f.write(pack('H'*len(row),*row))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('path');p.add_argument('--width',type=int,default=64);p.add_argument('--height',type=int,default=384);p.add_argument('--linear',action='store_true');p.add_argument('--orientation',type=int,default=1);p.add_argument('--pattern',default='RGGB');p.add_argument('--crop',action='store_true');p.add_argument('--big-endian',action='store_true');a=p.parse_args();generate(a.path,a.width,a.height,a.linear,a.orientation,a.pattern,a.crop,'>'if a.big_endian else '<')
