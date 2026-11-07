"""Existing D65 endpoint projection with clean v2 coverage, not A microtexture.
Native tables/profile data are read only after the entrypoint verifies pinned hashes.
CineStill resolves the existing 500T data chain without altering scientific inputs.
"""
import ast
import json
import re
import numpy as np


def array(path, name):
    match = re.search(r'pub const ' + name + r'\s*:[^=]+?=\s*(\[.*?\]);', path.read_text(), re.S)
    if match is None:
        raise ValueError(f'Missing native array {name} in {path}')
    return np.asarray(ast.literal_eval(match.group(1)), dtype=np.float64)


def lin(x):
    x=np.asarray(x,dtype=np.float32)/255
    return np.where(x<=.04045,x/12.92,((x+.055)/1.055)**2.4)


def enc(x):
    x=np.asarray(x).clip(0,1)
    return np.uint8(np.rint(np.where(x<=.0031308,12.92*x,1.055*x**(1/2.4)-.055)*255))


def render_direct(repo, stock, positive_a, clean_v2, approved_inputs):
    """Numerical operation order and dtypes preserved from the reviewed generator."""
    sp=repo/'native/crates/spektrafilm-math/src/spectral.rs'
    co=repo/'native/crates/spektrafilm-math/src/colorspace.rs'
    cmf=np.asarray([array(sp,'CMF_'+x+'_F64') for x in 'XYZ'])
    ill=array(sp,'ILLUMINANT_D65_F64')
    mat=array(co,'XYZ_TO_SRGB_F64')
    p=repo/('app/src/main/assets/engine-data/profiles/'+stock+'.json')
    inputs=[p,sp,co]
    if p.relative_to(repo).as_posix() not in approved_inputs:
        raise ValueError(f'Unpinned profile {p}')
    raw=json.loads(p.read_text())
    chain=[]
    while not raw.get('data'):
        parent=raw['derived_from']
        if parent in chain or not re.fullmatch(r'[a-z0-9_]+',parent):
            raise ValueError(f'Invalid profile inheritance: {parent}')
        chain.append(parent)
        p=p.parent/(parent+'.json')
        if p.relative_to(repo).as_posix() not in approved_inputs:
            raise ValueError(f'Unpinned inherited profile {p}')
        inputs.append(p)
        raw=json.loads(p.read_text())
    d=raw['data']
    curves=np.array(d['density_curves'],float)
    base=np.array(d['base_density'],float)
    dyes=np.array(d['channel_density'],float)[:,:3]
    mx=np.nanmax(curves,axis=0)-np.nanmin(curves,axis=0)
    valid=np.isfinite(base)&np.isfinite(dyes).all(1)
    def color(density):
        t=10**(-(base+dyes@density))
        xyz=(cmf[:,valid]*ill[valid]*t[valid]).sum(1)/(ill*cmf[1]).sum()
        rgb=mat@xyz
        return dict(normalized_density=list(density),linear_srgb=rgb.tolist(),srgb8=enc(rgb).tolist(),display_clipped_channels=np.where(rgb!=rgb.clip(0,1))[0].tolist())
    lo=color(np.zeros(3));hi=color(mx)
    a=positive_a;v=clean_v2
    if not np.array_equal(a[:,:,3],v[:,:,3]):
        raise ValueError('A alpha differs from clean geometry')
    oldbody=[23,21,25] if stock=='cinestill_800t' else [24]*3
    oldink=[220,189,111] if stock=='cinestill_800t' else [159]*3
    vec=lin(oldink)-lin(oldbody)
    cov=np.clip(np.sum((lin(v[:,:,:3])-lin(oldbody))*vec,axis=2)/np.dot(vec,vec),0,1)
    b=a.copy()
    b[:,:,:3]=enc(np.array(lo['linear_srgb']).clip(0,1)*(1-cov[:,:,None])+np.array(hi['linear_srgb']).clip(0,1)*cov[:,:,None])
    b[b[:,:,3]==0,:3]=0
    return b,dict(model='D65 / minimum-normalized profile base / channelwise Dmax contrast bound',inherited_data_chain=chain,base_endpoint=lo,marking_bound_endpoint=hi,scientific_inputs=[p.relative_to(repo).as_posix() for p in inputs],retains_A_microtexture=False,calibrated_film_measurement=False)
