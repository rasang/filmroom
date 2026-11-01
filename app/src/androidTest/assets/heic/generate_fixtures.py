import struct,pathlib
import tempfile, subprocess
from PIL import Image, ImageDraw
# Own generated artwork and fixture generator, CC0-1.0. No external photograph.
out = pathlib.Path(__file__).resolve().parent
p = pathlib.Path(tempfile.mkdtemp(prefix="filmroom-heic-fixtures-"))
im=Image.new("RGB", (96,64), (100,100,100)); draw=ImageDraw.Draw(im)
for rect,color in [((0,0,47,31),(240,20,20)),((48,0,95,31),(20,220,20)),((0,32,47,63),(20,40,240)),((48,32,95,63),(230,210,20))]:
    draw.rectangle(rect, fill=color)
draw.rectangle((42,20,53,43), fill=(245,245,245))
im.save(out / "source-corners.png")
def encode(depth, primaries="bt709"):
    target=p / ("frame.mp4" if depth==8 else "frame10.mp4")
    subprocess.run(["ffmpeg","-hide_banner","-loglevel","error","-i",str(out / "source-corners.png"),
        "-vf","scale=out_color_matrix=bt709:out_range=tv","-frames:v","1","-c:v","libx265",
        "-pix_fmt","yuv420p" if depth==8 else "yuv420p10le","-tag:v","hvc1",
        "-color_primaries",primaries,"-color_trc","iec61966-2-1","-colorspace","bt709","-color_range","tv",
        "-x265-params","pools=none:frame-threads=1:log-level=error" +
        (":colorprim=12:transfer=13:colormatrix=1" if primaries == "smpte432" else ""),"-crf","10","-y",str(target)],check=True)
    return target.read_bytes()

u32=lambda n:struct.pack('>I',n)
u16=lambda n:struct.pack('>H',n)
def box(t,b):return u32(len(b)+8)+t.encode()+b
def full(t,v,b):return box(t,bytes([v,0,0,0])+b)
def boxes(b):
 o=0
 while o<len(b):
  n=int.from_bytes(b[o:o+4],'big');assert n>=8
  yield b[o+4:o+8].decode(),b[o+8:o+n]
  o+=n
b=encode(8)
# hvcC is a known leaf in the video sample description; find its header.
i=b.index(b'hvcC');n=int.from_bytes(b[i-4:i],'big');hvcc=b[i+4:i-4+n]
mdat=next(body for t,body in boxes(b) if t=='mdat')
ftyp=box('ftyp',b'heic'+u32(0)+b'mif1heic')
def create(name,rotation=None,mirror=None,depth=8,primaries=1):
 props=[box('hvcC',hvcc),full('ispe',0,u32(96)+u32(64)),box('colr',b'nclx'+u16(primaries)+u16(13)+u16(1)+b'\x00'),full('pixi',0,bytes([3,depth,depth,depth]))]
 if rotation is not None:props+=[box('irot',bytes([rotation]))]
 if mirror is not None:props+=[box('imir',bytes([mirror]))]
 hdlr=full('hdlr',0,u32(0)+b'pict'+bytes(12)+b'Fixture\0')
 pitm=full('pitm',0,u16(1))
 iinf=full('iinf',0,u16(1)+full('infe',2,u16(1)+u16(0)+b'hvc1'+b'corners\0'))
 iprp=box('iprp',box('ipco',b''.join(props))+full('ipma',0,u32(1)+u16(1)+bytes([len(props)])+bytes(0x80+i for i in range(1,len(props)+1))))
 def meta(offset):
  iloc=full('iloc',0,b'\x44\x00'+u16(1)+u16(1)+u16(0)+u16(1)+u32(offset)+u32(len(mdat)))
  return full('meta',0,hdlr+pitm+iloc+iinf+iprp)
 m=meta(0);m=meta(len(ftyp)+len(m)+8)
 out.joinpath(name).write_bytes(ftyp+m+box('mdat',mdat))
create('corners-sdr8.heic')
create('corners-rotate90ccw.heic',rotation=1)
create('corners-mirror.heic',mirror=0)
print('hvcC bytes',len(hvcc),'sample bytes',len(mdat),'profile',hvcc[1]&31,'bitdepth',8+(hvcc[17]&7))
create('corners-rotate180.heic',rotation=2)
create('corners-rotate270ccw.heic',rotation=3)

# A real Main10 negative fixture: decoding is possible on host, admission must reject.
b=encode(10)
i=b.index(b"hvcC");n=int.from_bytes(b[i-4:i],"big");hvcc=b[i+4:i-4+n]
mdat=next(body for t,body in boxes(b) if t=="mdat")
create("corners-main10.heic",depth=10)

# Common phone-style derived image: a 2x2 grid of four independently coded tiles.
# Use fresh tile bitstreams, not cropped display metadata on one large frame.
configs=[]; samples=[]
for index,rect in enumerate(((0,0,48,32),(48,0,96,32),(0,32,48,64),(48,32,96,64))):
    tile=p / ("tile%d.png" % index); im.crop(rect).save(tile)
    mp4=p / ("tile%d.mp4" % index)
    subprocess.run(["ffmpeg","-hide_banner","-loglevel","error","-i",str(tile),
        "-vf","scale=out_color_matrix=bt709:out_range=tv","-frames:v","1","-c:v","libx265",
        "-pix_fmt","yuv420p","-tag:v","hvc1","-color_primaries","bt709",
        "-color_trc","iec61966-2-1","-colorspace","bt709","-color_range","tv",
        "-x265-params","pools=none:frame-threads=1:log-level=error","-crf","10","-y",str(mp4)],check=True)
    data=mp4.read_bytes(); at=data.index(b"hvcC"); length=int.from_bytes(data[at-4:at],"big")
    configs.append(data[at+4:at-4+length]); samples.append(next(body for t,body in boxes(data) if t=="mdat"))
assert all(config==configs[0] for config in configs), "Tile configs differ; do not falsely share hvcC"
props=[box("hvcC",configs[0]),full("ispe",0,u32(48)+u32(32)),
       box("colr",b"nclx"+u16(1)+u16(13)+u16(1)+b"\x00"),
       full("pixi",0,bytes([3,8,8,8])),full("ispe",0,u32(96)+u32(64))]
associations=u16(1)+bytes([3,0x85,0x83,0x84])
for item in range(2,6): associations+=u16(item)+bytes([4,0x81,0x82,0x83,0x84])
iprp=box("iprp",box("ipco",b"".join(props))+full("ipma",0,u32(5)+associations))
iinf=full("iinf",0,u16(5)+full("infe",2,u16(1)+u16(0)+b"grid"+b"corners-grid\0")+
    b"".join(box("infe",bytes([2,0,0,1])+u16(item)+u16(0)+b"hvc1"+b"tile\0") for item in range(2,6)))
iref=full("iref",0,box("dimg",u16(1)+u16(4)+b"".join(u16(item) for item in range(2,6))))
payloads=[bytes([0,0,1,1])+u16(96)+u16(64)]+samples

def grid_meta(offset):
    locations=b"\x44\x00"+u16(5)
    for item,payload in enumerate(payloads,1):
        locations+=u16(item)+u16(0)+u16(1)+u32(offset)+u32(len(payload)); offset+=len(payload)
    return full("meta",0,full("hdlr",0,u32(0)+b"pict"+bytes(12)+b"Fixture\0")+
        full("pitm",0,u16(1))+full("iloc",0,locations)+iinf+iref+iprp)
gmeta=grid_meta(0);gmeta=grid_meta(len(ftyp)+len(gmeta)+8)
out.joinpath("corners-grid2x2.heic").write_bytes(ftyp+gmeta+box("mdat",b"".join(payloads)))

# Real NCLX-only Display P3: both HEVC VUI and HEIF colr carry primaries 12.
# Marker code values are reinterpreted as P3, not converted from sRGB; negative test only.
b=encode(8,"smpte432")
i=b.index(b"hvcC");n=int.from_bytes(b[i-4:i],"big");hvcc=b[i+4:i-4+n]
mdat=next(body for t,body in boxes(b) if t=="mdat")
create("corners-display-p3.heic",primaries=12)

# Ordinary thumbnail attachment is not an auxiliary alpha/depth/HDR image.
# Reuse tile 0's real 48x32 Main8 sample as a deliberately red-biased thumbnail;
# the primary must still decode to the four-color full 96x64 grid.
associations=u16(1)+bytes([3,0x85,0x83,0x84])
for item in range(2,6): associations+=u16(item)+bytes([4,0x81,0x82,0x83,0x84])
associations+=u16(6)+bytes([4,0x86,0x87,0x83,0x84])
thumbnail_props=props+[box("hvcC",configs[0]),full("ispe",0,u32(48)+u32(32))]
iprp=box("iprp",box("ipco",b"".join(thumbnail_props))+full("ipma",0,u32(6)+associations))
iinf=full("iinf",0,u16(6)+full("infe",2,u16(1)+u16(0)+b"grid"+b"corners-grid\0")+
    b"".join(box("infe",bytes([2,0,0,1])+u16(item)+u16(0)+b"hvc1"+b"tile\0") for item in range(2,6))+
    full("infe",2,u16(6)+u16(0)+b"hvc1"+b"thumbnail\0"))
iref=full("iref",0,box("dimg",u16(1)+u16(4)+b"".join(u16(item) for item in range(2,6)))+
    box("thmb",u16(6)+u16(1)+u16(1)))
payloads=[bytes([0,0,1,1])+u16(96)+u16(64)]+samples+[samples[0]]

def thumbnail_grid_meta(offset):
    locations=b"\x44\x00"+u16(6)
    for item,payload in enumerate(payloads,1):
        locations+=u16(item)+u16(0)+u16(1)+u32(offset)+u32(len(payload));offset+=len(payload)
    return full("meta",0,full("hdlr",0,u32(0)+b"pict"+bytes(12)+b"Fixture\0")+
        full("pitm",0,u16(1))+full("iloc",0,locations)+iinf+iref+iprp)
tmeta=thumbnail_grid_meta(0);tmeta=thumbnail_grid_meta(len(ftyp)+len(tmeta)+8)
out.joinpath("corners-grid-thumbnail.heic").write_bytes(ftyp+tmeta+box("mdat",b"".join(payloads)))
