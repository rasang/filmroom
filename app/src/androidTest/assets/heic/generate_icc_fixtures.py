"""Original CC0-1.0 ICC/HEIC test generator. No user photo/profile bytes are used.
Host-only dependencies: Pillow, liblcms2, FFmpeg/libx265. Never bundled into app.
"""
import ctypes as C
import hashlib, json, pathlib, struct, subprocess, tempfile
from PIL import Image, ImageDraw
out=pathlib.Path(__file__).resolve().parent
work=pathlib.Path(tempfile.mkdtemp(prefix='filmroom-heic-icc-'))
class xyY(C.Structure): _fields_=[('x',C.c_double),('y',C.c_double),('Y',C.c_double)]
class Triple(C.Structure): _fields_=[('Red',xyY),('Green',xyY),('Blue',xyY)]
lcms=C.CDLL('liblcms2.so.2')
lcms.cmsBuildParametricToneCurve.argtypes=[C.c_void_p,C.c_int,C.POINTER(C.c_double)];lcms.cmsBuildParametricToneCurve.restype=C.c_void_p
lcms.cmsCreateRGBProfile.argtypes=[C.POINTER(xyY),C.POINTER(Triple),C.POINTER(C.c_void_p)];lcms.cmsCreateRGBProfile.restype=C.c_void_p
lcms.cmsSaveProfileToMem.argtypes=[C.c_void_p,C.c_void_p,C.POINTER(C.c_uint32)];lcms.cmsSaveProfileToMem.restype=C.c_int
lcms.cmsWriteTag.argtypes=[C.c_void_p,C.c_uint32,C.c_void_p];lcms.cmsWriteTag.restype=C.c_int
lcms.cmsSetProfileVersion.argtypes=[C.c_void_p,C.c_double]
lcms.cmsFreeToneCurve.argtypes=[C.c_void_p]
lcms.cmsCloseProfile.argtypes=[C.c_void_p]
lcms.cmsCreate_sRGBProfile.restype=C.c_void_p
lcms.cmsCreateTransform.argtypes=[C.c_void_p,C.c_uint32,C.c_void_p,C.c_uint32,C.c_uint32,C.c_uint32];lcms.cmsCreateTransform.restype=C.c_void_p
lcms.cmsDoTransform.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p,C.c_uint32]
lcms.cmsDeleteTransform.argtypes=[C.c_void_p]
lcms.cmsGetEncodedCMMversion.restype=C.c_uint32
params=(C.c_double*5)(2.4,1/1.055,.055/1.055,1/12.92,.04045)
curve=lcms.cmsBuildParametricToneCurve(None,4,params);assert curve
curves=(C.c_void_p*3)(curve,curve,curve)
profile=lcms.cmsCreateRGBProfile(C.byref(xyY(.3127,.3290,1)),C.byref(Triple(xyY(.680,.320,1),xyY(.265,.690,1),xyY(.150,.060,1))),curves);assert profile
lcms.cmsFreeToneCurve(curve)
lcms.cmsSetProfileVersion(profile,4.3)
assert lcms.cmsWriteTag(profile,int.from_bytes(b"chrm","big"),None)
size=C.c_uint32();assert lcms.cmsSaveProfileToMem(profile,None,C.byref(size))
buffer=C.create_string_buffer(size.value);assert lcms.cmsSaveProfileToMem(profile,buffer,C.byref(size))
icc=bytearray(buffer.raw)
# Normalize only generation timestamp for deterministic original fixture bytes.
icc[24:36]=struct.pack('>6H',2026,10,31,0,0,0)
icc=bytes(icc);out.joinpath('original-display-p3.icc').write_bytes(icc)
# Moderate P3 values keep transform tests away from clipping/gamut-boundary ambiguity.
colors=[(170,100,100),(100,170,100),(100,100,170),(170,160,100)]
srgb=lcms.cmsCreate_sRGBProfile();assert srgb
TYPE_RGB_DBL=(1<<22)|(4<<16)|(3<<3)
transform=lcms.cmsCreateTransform(profile,TYPE_RGB_DBL,srgb,TYPE_RGB_DBL,1,0x100|0x40);assert transform
references=[]
for rgb in colors:
    source=(C.c_double*3)(*(c/255 for c in rgb));target=(C.c_double*3)()
    lcms.cmsDoTransform(transform,source,target,1)
    references.append({'p3_code8':list(rgb),'p3_encoded':[c/255 for c in rgb],
        'srgb_encoded':list(target),'srgb_code8':[round(max(0,min(1,c))*255) for c in target]})
lcms.cmsDeleteTransform(transform);lcms.cmsCloseProfile(profile);lcms.cmsCloseProfile(srgb)
out.joinpath('icc-p3-srgb-reference.json').write_text(json.dumps({
    'provenance':'Original synthetic Display P3 profile; LittleCMS relative colorimetric, no optimization, no BPC',
    'lcms_encoded_version':lcms.cmsGetEncodedCMMversion(),'profile_sha256':hashlib.sha256(icc).hexdigest(),
    'intent':'relative_colorimetric','float_tolerance':0.002,'notes':'Reference maps original P3 code values, not lossy HEVC-decoded values. Android color connector comparison should use these input values directly.',
    'samples':references},indent=2)+'\n')
im=Image.new('RGB',(96,64));draw=ImageDraw.Draw(im)
for rect,color in zip(((0,0,47,31),(48,0,95,31),(0,32,47,63),(48,32,95,63)),colors):draw.rectangle(rect,fill=color)
im.save(out/'source-icc-p3-corners.png',icc_profile=icc)
u32=lambda n:struct.pack('>I',n)
u16=lambda n:struct.pack('>H',n)
def box(t,b):return u32(len(b)+8)+t.encode()+b
def full(t,v,b):return box(t,bytes([v,0,0,0])+b)
def boxes(b):
    at=0
    while at<len(b):
        size=int.from_bytes(b[at:at+4],'big');assert size>=8
        yield b[at+4:at+8],b[at+8:at+size]
        at+=size

def encode(image,name):
    png=work/(name+'.png');image.save(png)
    mp4=work/(name+'.mp4')
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',str(png),
      '-vf','scale=out_color_matrix=bt709:out_range=tv','-frames:v','1','-c:v','libx265',
      '-pix_fmt','yuv420p','-tag:v','hvc1','-color_primaries','smpte432','-color_trc','iec61966-2-1',
      '-colorspace','bt709','-color_range','tv','-x265-params',
      'pools=none:frame-threads=1:log-level=error:colorprim=12:transfer=13:colormatrix=1',
      '-crf','10','-y',str(mp4)],check=True)
    data=mp4.read_bytes();i=data.index(b'hvcC');n=int.from_bytes(data[i-4:i],'big')
    return data[i+4:i-4+n],next(body for t,body in boxes(data) if t==b'mdat')
ftyp=box('ftyp',b'heic'+u32(0)+b'mif1heic')
hdlr=full('hdlr',0,u32(0)+b'pict'+bytes(12)+b'Original ICC fixture\0')
def write(name,items,props,associations,payloads,refs=b''):
    iinf=full('iinf',0,u16(len(items))+b''.join(box('infe',bytes([2,0,0,int(hidden)])+u16(i)+u16(0)+kind.encode()+b'original\0') for i,(kind,hidden) in enumerate(items,1)))
    ipma=full('ipma',0,u32(len(items))+b''.join(u16(i)+bytes([len(ids)])+bytes(0x80+x for x in ids) for i,ids in enumerate(associations,1)))
    iprp=box('iprp',box('ipco',b''.join(props))+ipma)
    def meta(offset):
        locations=b'\x44\x00'+u16(len(items))
        for i,payload in enumerate(payloads,1):
            locations+=u16(i)+u16(0)+u16(1)+u32(offset)+u32(len(payload));offset+=len(payload)
        return full('meta',0,hdlr+full('pitm',0,u16(1))+full('iloc',0,locations)+iinf+refs+iprp)
    metadata=meta(0);metadata=meta(len(ftyp)+len(metadata)+8)
    out.joinpath(name).write_bytes(ftyp+metadata+box('mdat',b''.join(payloads)))
hvcc,sample=encode(im,'single')
props=[box('hvcC',hvcc),full('ispe',0,u32(96)+u32(64)),box('colr',b'prof'+icc),full('pixi',0,bytes([3,8,8,8]))]
write('corners-icc-p3.heic',[('hvc1',False)],props,[[1,2,3,4]],[sample])
tiles=[encode(im.crop(rect),'tile'+str(i)) for i,rect in enumerate(((0,0,48,32),(48,0,96,32),(0,32,48,64),(48,32,96,64)))]
assert all(t[0]==tiles[0][0] for t in tiles)
props=[full('ispe',0,u32(96)+u32(64)),box('hvcC',tiles[0][0]),full('ispe',0,u32(48)+u32(32)),box('colr',b'prof'+icc),full('pixi',0,bytes([3,8,8,8]))]
refs=full('iref',0,box('dimg',u16(1)+u16(4)+b''.join(u16(i) for i in range(2,6))))
write('corners-grid-icc-p3-tiles.heic',[('grid',False)]+[('hvc1',True)]*4,
      props,[[1]]+[[2,3,4,5]]*4,[bytes([0,0,1,1])+u16(96)+u16(64)]+[t[1] for t in tiles],refs)
print('ICC bytes',len(icc),'profile sha256',hashlib.sha256(icc).hexdigest())
print(json.dumps(references,indent=2))
