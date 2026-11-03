"""Original 5222 positive-display artwork using sampled colour targets only.
No photograph is read, embedded, used as texture or included in the output.
Latest reference body patches median 0..5; letter stroke median32..51 depending
on antialias exclusion. Body target4; lettering112-to-88 centered near100 intentionally brighter under
user instruction. These are sRGB display colours, not physical density claims.
"""
from pathlib import Path
import hashlib,json
import numpy as np
from PIL import Image,ImageDraw,ImageFont,ImageFilter
ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'app/src/main/assets/fixed135'
base=ASSETS/'bw-double_x_5222-positive-display.webp'
assert hashlib.sha256(base.read_bytes()).hexdigest()=='2ccf5d7c749763ed7690f31d8feb73c1ee72114fc6719e4cb0fef933c6241447'
a=np.array(Image.open(base).convert('RGBA'));h,w=a.shape[:2];g=a[:,:,3]/255
rng=np.random.default_rng(522206)
# Near-black photographic surround, not cloudy spray texture or shiny plastic.
tone=np.clip(4+rng.normal(0,.55,(h,w)),2,6)
y,x=np.mgrid[:h,:w]
# Keep existing narrow long-cut/perforation material-side shading subtle on black.
# No highlight bevel, hole contraction, inner-hole fill, or image-window outline.
edge=np.minimum(y,h-1-y);tone-=1.4*np.exp(-((edge-.7)/1.35)**2)
holefield=1-g;holefield[200:1200,:]=0
blur=np.asarray(Image.fromarray((holefield*255).round().astype('uint8')).filter(ImageFilter.GaussianBlur(1.6)))/255
rim=np.clip((blur-holefield)*2.2,0,1);tone-=1.2*rim
ink=Image.new('L',(w,h));d=ImageDraw.Draw(ink)
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf',47)
label='EASTMAN 5222 511   00106 23 173 2020';width=d.textlength(label,font=font);left=(w-width)/2
# Retain known full fixed sample. No invented KEYKODE/barcode or new edge codes.
d.text((left,1359),label,font=font,anchor='lt',fill=255)
for xx in [left-104,left+width+78]:d.line((xx,1378,xx+26,1378),fill=255,width=4)
# Reconstruct the original Beta3 source exactly to prove font/layout/AA parity.
original_tone=177+np.random.default_rng(5222).normal(0,.62,(h,w))+1.1*np.sin(x/w*np.pi)
original_rgb=np.repeat(np.uint8(np.clip(original_tone,0,255))[:,:,None],3,2)
original=Image.fromarray(original_rgb).convert('RGBA');original.putalpha(Image.fromarray(a[:,:,3]))
od=ImageDraw.Draw(original);od.text((left,1359),label,font=font,anchor='lt',fill=(41,41,41,255),stroke_width=0)
for xx in [left-104,left+width+78]:od.line((xx,1378,xx+26,1378),fill=(41,41,41,255),width=4)
reconstructed=np.array(original);reconstructed[:,:,:3]=255-reconstructed[:,:,:3];reconstructed[reconstructed[:,:,3]==0,:3]=0
assert np.array_equal(reconstructed,a), 'Original glyph/font/layout reconstruction must be byte exact'
# Rebuild the published v6 material before changing its text placement.
k0=np.asarray(ink)/255
old_ink_tone=112-24*np.clip((y-1359)/35,0,1)
old_rgb=np.repeat(np.round(tone*(1-k0)+old_ink_tone*k0).clip(0,255).astype('uint8')[:,:,None],3,2)
old=np.dstack([old_rgb,a[:,:,3]]);old[a[:,:,3]==0,:3]=0
published=ASSETS/'bw-double_x_5222-positive-sampled.webp'
assert hashlib.sha256(published.read_bytes()).hexdigest()=='608779f0b0c65d838d966e4f1a57da86ddd30dee58c0a2a265ca77b20432528f'
assert np.array_equal(old,np.array(Image.open(published).convert('RGBA')))
# Shift the existing mask, never redraw/rescale characters: right tail is clipped
# by the film canvas, not by a fake overlay. EASTMAN 5222 remains fully in frame.
SHIFT_X=320
shifted=np.zeros_like(np.asarray(ink));shifted[:,SHIFT_X:]=np.asarray(ink)[:,:-SHIFT_X]
k=shifted/255
# User-requested restrained top-to-bottom ink-density gradient; no bevel/shadow.
ink_tone=112-24*np.clip((y-1359)/35,0,1)
flat_tone=tone*(1-k)+100*k
tone=tone*(1-k)+ink_tone*k
assert np.array_equal(tone[k==0],flat_tone[k==0]), "Non-ink body must remain exact"
rgb=np.repeat(np.round(tone).clip(0,255).astype('uint8')[:,:,None],3,2)
out=np.dstack([rgb,a[:,:,3]]);out[a[:,:,3]==0,:3]=0
path=ASSETS/'bw-double_x_5222-positive-asymmetric.webp'
Image.fromarray(out).save(path,lossless=True,exact=True)
assert np.array_equal(out,np.array(Image.open(path).convert('RGBA')))
assert np.array_equal(out[:,:,3],a[:,:,3])
print(json.dumps({'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size,'body_srgb8':[4,4,4],'text_srgb8_top_bottom':[112,88],'alpha_exact_baseline':True,'text_shift_x':SHIFT_X,'glyph_mask_translation_exact':True}))
