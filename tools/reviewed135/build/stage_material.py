"""Approved positive A material; no B parameter or output path.
The original smooth_field/change numerical operations are kept unchanged.
"""
from PIL import Image, ImageFilter
import numpy as np
W,H=1520,1400

def smooth_field(rng,amplitude):
    # Nonperiodic, very low-amplitude original variation, not mottling/cloud texture.
    small=rng.uniform(0,255,(30,34)).astype('uint8')
    im=Image.fromarray(small).resize((W,H),Image.Resampling.BICUBIC).filter(ImageFilter.GaussianBlur(14))
    v=np.asarray(im).astype('float32');v-=v.mean();v/=max(float(v.std()),1)
    return np.clip(v,-1.5,1.5)*(amplitude/1.5)

def change(a,id,p,seed):
    cine=id=='cinestill_800t';oldbody=23 if cine else 24;oldink=220 if cine else 159
    k=np.clip((a[:,:,0].astype('float32')-oldbody)/(oldink-oldbody),0,1)
    rng=np.random.default_rng(seed)
    variation=smooth_field(rng,p['smooth_amplitude'])+rng.normal(0,p['fine_sigma'],(H,W)).astype('float32')
    inkvar=rng.normal(0,p['ink_sigma'],(H,W)).astype('float32')
    body=p['cine_body'] if cine else [p['bw_body']]*3
    ink=p['cine_ink'] if cine else [p['bw_ink']]*3
    out=np.empty_like(a)
    for c in range(3):
        out[:,:,c]=np.clip(np.rint((body[c]+variation)*(1-k)+(ink[c]+inkvar)*k),0,255).astype('uint8')
    out[:,:,3]=a[:,:,3];out[out[:,:,3]==0,:3]=0
    assert np.array_equal(out[:,:,3],a[:,:,3])
    # No grain or colour is ever inserted into the transparent photo/window or holes.
    assert np.all(out[out[:,:,3]==0,:3]==0)
    return out
