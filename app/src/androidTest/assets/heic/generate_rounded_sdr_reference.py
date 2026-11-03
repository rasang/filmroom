"""Independent synthetic ICC/TRC reference, CC0. No user photo or profile input.
Requires already-installed LittleCMS and numpy on the host, not in the app."""
import ctypes as C, json, pathlib, struct, hashlib, sys
import numpy as np
out=pathlib.Path(__file__).resolve().parent
# Original independently generated public fixture, NOT a profile from the user's image.
base=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else out/'original-display-p3.icc'
p=bytearray(base.read_bytes())
def u32(at):return int.from_bytes(p[at:at+4],'big')
tags={p[132+i*12:136+i*12].decode():(u32(136+i*12),u32(140+i*12)) for i in range(u32(128))}
for key in ['rTRC','gTRC','bTRC']:
 at,_=tags[key];assert p[at:at+4]==b'para'
 p[at+12:at+32]=struct.pack('>5i',157286,62128,3408,5046,2556)
p[84:100]=bytes(16) # no stale profile ID
icc=bytes(p); (out/'original-rounded-p3.icc').write_bytes(icc)
L=C.CDLL('liblcms2.so.2')
L.cmsOpenProfileFromMem.argtypes=[C.c_void_p,C.c_uint32];L.cmsOpenProfileFromMem.restype=C.c_void_p
L.cmsCreate_sRGBProfile.restype=C.c_void_p
L.cmsCreateTransform.argtypes=[C.c_void_p,C.c_uint32,C.c_void_p,C.c_uint32,C.c_uint32,C.c_uint32];L.cmsCreateTransform.restype=C.c_void_p
L.cmsDoTransform.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p,C.c_uint32]
L.cmsDeleteTransform.argtypes=[C.c_void_p];L.cmsCloseProfile.argtypes=[C.c_void_p]
L.cmsGetEncodedCMMversion.restype=C.c_uint32
buf=C.create_string_buffer(icc);src=L.cmsOpenProfileFromMem(buf,len(icc));dst=L.cmsCreate_sRGBProfile()
TYPE_RGB_DBL=(1<<22)|(4<<16)|(3<<3)
t=L.cmsCreateTransform(src,TYPE_RGB_DBL,dst,TYPE_RGB_DBL,1,0x100|0x40);assert t
g,a,b,c,d=[i/65536 for i in (157286,62128,3408,5046,2556)]
M=np.column_stack([np.array(struct.unpack('>3i',p[tags[k][0]+8:tags[k][0]+20]))/65536 for k in ['rXYZ','gXYZ','bXYZ']])
# Independent standard D50-adapted sRGB matrix; not production source constants.
S=np.array([[.4360747,.3850649,.1430804],[.2225045,.7168786,.0606169],[.0139322,.0971045,.7141733]])
def math_transform(rgb):
 v=np.asarray(rgb);linear=np.where(v<d,c*v,(a*v+b)**g)
 x=np.clip(np.linalg.solve(S,M@linear),0,1)
 return np.where(x<=.0031308,12.92*x,1.055*x**(1/2.4)-.055)
inputs=[[v/255]*3 for v in range(256)]
inputs += [[v]*3 for v in [d-1/65536,d,d+1/65536]]
inputs += [[r,g,b] for r in [0,.1,.5,.9,1] for g in [0,.1,.5,.9,1] for b in [0,.1,.5,.9,1]]
samples=[];maxerr=0
for rgb in inputs:
 x=(C.c_double*3)(*rgb);y=(C.c_double*3)();L.cmsDoTransform(t,x,y,1)
 actual=np.clip(np.array(y),0,1);expected=math_transform(rgb)
 err=float(np.max(np.abs(actual-expected)));maxerr=max(maxerr,err)
 samples.append({'source_encoded':rgb,'srgb_encoded':actual.tolist()})
assert maxerr<.002,maxerr
result={'provenance':'Independent synthetic ICC, exact rounded type3 coefficients; LittleCMS relative colorimetric, no optimization/BPC, float clipped to SDR [0,1]. No user image/profile bytes.',
 'lcms_version':L.cmsGetEncodedCMMversion(),'profile_sha256':hashlib.sha256(icc).hexdigest(),
 'float_tolerance':.002,'independent_matrix_math_max_abs_error':maxerr,'samples':samples}
(out/'rounded-p3-srgb-reference.json').write_text(json.dumps(result,indent=2)+'\n')
L.cmsDeleteTransform(t);L.cmsCloseProfile(src);L.cmsCloseProfile(dst)
print('samples',len(samples),'max independent math error',maxerr,'sha256',result['profile_sha256'])
