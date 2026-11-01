"""Read-only physical-profile endpoint study. NOT a validated factory edge palette."""
import ast,hashlib,json,math,re,sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
stock=sys.argv[1] if len(sys.argv)>1 else 'kodak_portra_160'
assert re.fullmatch('[a-z0-9_]+',stock)
p=root/('app/src/main/assets/engine-data/profiles/'+stock+'.json')
s=root/'native/crates/spektrafilm-math/src/spectral.rs'
c=root/'native/crates/spektrafilm-math/src/colorspace.rs'
def array(path,name):
 text=path.read_text();m=re.search(r'pub const '+name+r'\s*:[^=]+?=\s*(\[.*?\]);',text,re.S)
 assert m,name
 return ast.literal_eval(m.group(1))
cmf=[array(s,'CMF_'+axis+'_F64') for axis in 'XYZ'];ill=array(s,'ILLUMINANT_D65_F64');matrix=array(c,'XYZ_TO_SRGB_F64')
d=json.loads(p.read_text())['data'];curves=d['density_curves'];minimum=[min(row[ch] for row in curves if row[ch] is not None and math.isfinite(row[ch])) for ch in range(3)]
maximum=[max(row[ch]-minimum[ch] for row in curves if row[ch] is not None and math.isfinite(row[ch])) for ch in range(3)]
valid=[i for i,(base,dye) in enumerate(zip(d['base_density'],d['channel_density'])) if base is not None and math.isfinite(base) and all(x is not None and math.isfinite(x) for x in dye[:3])]
norm=sum(a*b for a,b in zip(ill,cmf[1]))
def color(density):
 xyz=[sum(10**(-(d['base_density'][i]+sum(density[k]*d['channel_density'][i][k] for k in range(3))))*ill[i]*cmf[ch][i]/norm for i in valid) for ch in range(3)]
 linear=[sum(a*b for a,b in zip(row,xyz)) for row in matrix]
 def encode(v):
  v=max(0,min(1,v));return math.floor(255*(12.92*v if v<=.0031308 else 1.055*v**(1/2.4)-.055)+.5)
 return dict(normalized_cmy_density=density,xyz=xyz,linear_srgb=linear,srgb8=[encode(v) for v in linear])
report=dict(stock=stock,status='model_endpoint_study_not_factory_edge_measurement',normalization='Subtract finite per-channel density-curve minima, matching normalize_density_curves_f64; zero normalized dye is the minimum endpoint, not zero raw spectral density.',curve_minima=minimum,valid_wavelength_count=len(valid),missing_wavelength_rule='Skip nonfinite base/dye; keep full D65 Y normalization, matching negative_preview.rs.',base_minimum=color([0.,0.,0.]),maximum_density_bound=color(maximum),limitations=['No measured factory lettering exposure or spectrum in profile. Maximum-density endpoint is only a contrast bound, not an established lettering color.','D65 fixed light-table model; not identical to configurable final scanner, viewing illuminant, or real calibrated scan.','No new frame assets or runtime contracts approved by this output.'],input_sha256={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in [p,s,c]})
out=Path(__file__).parent/(stock+'-endpoints.json');out.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
