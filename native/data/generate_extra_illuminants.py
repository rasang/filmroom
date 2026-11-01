from pathlib import Path
import colour,numpy as np,json
r=Path(__file__).resolve().parent.parent
shape=colour.SpectralShape(380,780,5)
out='''//! Generated from the pinned Python illuminant definition using colour-science 0.4.6.
//! Source: https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/model/illuminants.py
//! align(380,780,5), then divide by mean, exactly as standard_illuminant.
'''
fixtures={}
for name,key in [('T','Incandescent'),('K75P','Kinoton 75P')]:
 a=colour.SDS_LIGHT_SOURCES[key].copy().align(shape).values;a=a/(np.sum(a)/len(a));fixtures[name]=a.tolist()
 out+=f'pub const {name}_F64:[f64;81]=['+','.join(repr(float(x))for x in a)+'];\n'
 out+=f'pub const {name}_F32:[f32;81]=['+','.join(repr(float(np.float32(x)))for x in a)+'];\n'
out+='''#[cfg(test)]mod tests{use super::*;
 #[test]fn normalized_finite_non_daylight(){for a in [T_F64,K75P_F64]{assert!(a.iter().all(|v|v.is_finite()&&*v>=0.));assert!((a.iter().sum::<f64>()/81.-1.).abs()<1e-12);}assert!((T_F64[40]-K75P_F64[40]).abs()>0.01);}
}
'''
(r/'crates/spektrafilm-core/src/extra_illuminants.rs').write_text(out)
(r/'data/illuminants_reference.json').write_text(json.dumps({'pythonRevision':'3bb2c2d2801ff68b92019cf1dbcbb133d60832bc','colourVersion':colour.__version__,'spectralShape':[380,780,5],'spectra':fixtures},indent=2))
p=r/'crates/spektrafilm-core/src/lib.rs';s=p.read_text();
if 'pub mod extra_illuminants;' not in s:p.write_text(s+'\npub mod extra_illuminants;\n')
