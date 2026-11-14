//! DNG stage-2 black subtraction and per-sample-plane normalization.
//! DNG 1.7.1, chapter 5: normalize by WhiteLevel minus the maximum computed
//! black level in that plane, not the local black level at each pixel.
use crate::{dng::Rect, error::{err, Reason, Result}, tiff::Ifd};

#[derive(Clone, Debug)]
pub struct BlackModel {
    rows: usize,
    cols: usize,
    planes: usize,
    active: Rect,
    pub base: Vec<f64>,
    pub white: Vec<f64>,
    delta_h: Vec<f64>,
    delta_v: Vec<f64>,
    maxima: Vec<f64>,
    uniform_legacy: bool,
}
fn invalid(tag:u16)->crate::error::CodecError {
    err("INVALID_COLOR_METADATA","probe").detail(Reason::BlackWhiteLevel,Some(tag))
}
impl BlackModel {
    pub fn parse(raw:&Ifd, active:Rect, planes:usize, bits:u8)->Result<Self> {
        let repeat=if let Some(v)=raw.get(&50713) {
            if !matches!(v.typ,3|4) || v.count!=2 {return Err(invalid(50713));}
            let a=v.uints()?; [a[0] as usize,a[1] as usize]
        } else {[1,1]};
        if repeat.contains(&0) {return Err(invalid(50713));}
        let count=repeat[0].checked_mul(repeat[1]).and_then(|n|n.checked_mul(planes))
            .ok_or_else(||invalid(50713))?;
        // Matches the existing bounded loaded BlackLevel payload; do not allocate
        // a declared repeat pattern before checking its product.
        if count>32768 {return Err(err("MEMORY_BUDGET","probe").detail(Reason::ResourceLimit,Some(50714)));}
        let base=if let Some(v)=raw.get(&50714) {
            if !matches!(v.typ,3|4|5) || !(v.count as usize==count || (repeat==[1,1]&&v.count==1)) {return Err(invalid(50714));}
            let a=v.nums()?;
            if a.len()==1&&count>1 {vec![a[0];count]} else {a}
        } else {vec![0.;count]};
        let delta=|tag:u16,n:usize|->Result<Vec<f64>> {
            if let Some(v)=raw.get(&tag) {
                if v.typ!=10 || v.count as usize!=n {return Err(invalid(tag));}
                let a=v.nums()?;
                if a.iter().any(|v|!v.is_finite()) {return Err(invalid(tag));}
                Ok(a)
            } else {Ok(Vec::new())}
        };
        let delta_h=delta(50715,active.w)?;
        let delta_v=delta(50716,active.h)?;
        let white=if let Some(v)=raw.get(&50717) {
            // Preserve the existing scalar LinearRAW compatibility, expanding to
            // each plane. The standard form is one integer value per sample plane.
            // Retain previously accepted rational white values as compatibility.
            if !matches!(v.typ,3|4|5) || !(v.count==1 || v.count as usize==planes) {return Err(invalid(50717));}
            let a=v.nums()?; if a.len()==1 {vec![a[0];planes]} else {a}
        } else {vec![((1u32<<bits)-1) as f64;planes]};
        Self::new(active,repeat,planes,base,delta_h,delta_v,white)
    }
    pub fn new(active:Rect, repeat:[usize;2], planes:usize, base:Vec<f64>, delta_h:Vec<f64>, delta_v:Vec<f64>, white:Vec<f64>)->Result<Self> {
        let [rows,cols]=repeat;
        if rows==0||cols==0||planes==0||active.w==0||active.h==0
            || rows.checked_mul(cols).and_then(|n|n.checked_mul(planes))!=Some(base.len())
            || white.len()!=planes || (!delta_h.is_empty()&&delta_h.len()!=active.w)
            || (!delta_v.is_empty()&&delta_v.len()!=active.h) {return Err(invalid(50714));}
        if base.iter().any(|v|!v.is_finite()||*v<0.) || delta_h.iter().chain(&delta_v).any(|v|!v.is_finite())
            || white.iter().any(|v|!v.is_finite()||*v<=0.||*v>65535.) {return Err(invalid(50717));}
        // Separable row/column deltas allow exact extrema in O(W+H+pattern),
        // instead of scanning every sensor pixel during probe.
        let mut hmax=vec![f64::NEG_INFINITY;cols.min(active.w)];
        let mut vmax=vec![f64::NEG_INFINITY;rows.min(active.h)];
        for x in 0..active.w {hmax[x%cols]=hmax[x%cols].max(delta_h.get(x).copied().unwrap_or(0.));}
        for y in 0..active.h {vmax[y%rows]=vmax[y%rows].max(delta_v.get(y).copied().unwrap_or(0.));}
        let mut maxima=vec![f64::NEG_INFINITY;planes];
        for (r,v) in vmax.iter().enumerate() {for (c,h) in hmax.iter().enumerate() {for p in 0..planes {
            maxima[p]=maxima[p].max(base[(r*cols+c)*planes+p]+h+v);
        }}}
        if maxima.iter().zip(&white).any(|(b,w)|!b.is_finite()||w<=b) {return Err(invalid(50717));}
        let uniform_legacy=delta_h.is_empty()&&delta_v.is_empty()
            && base.chunks_exact(planes).all(|v|v==&base[..planes])
            && base[..planes].iter().zip(&white).all(|(b,w)|(*w as f32)>(*b as f32));
        Ok(Self {rows,cols,planes,active,base,white,delta_h,delta_v,maxima,uniform_legacy})
    }
    pub fn normalize(&self, sample:f32, x:usize,y:usize,p:usize)->f32 {
        debug_assert!(x>=self.active.x&&x<self.active.x+self.active.w);
        debug_assert!(y>=self.active.y&&y<self.active.y+self.active.h&&p<self.planes);
        // Preserve byte-identical old results when spatial black is constant.
        // Only newly supported spatial normalization needs the f64 model.
        if self.uniform_legacy {
            let black=self.base[p] as f32;
            return (sample-black).max(0.)/(self.white[p] as f32-black);
        }
        let x=x-self.active.x; let y=y-self.active.y;
        let black=self.base[((y%self.rows)*self.cols+x%self.cols)*self.planes+p]
            +self.delta_h.get(x).copied().unwrap_or(0.)+self.delta_v.get(y).copied().unwrap_or(0.);
        // Retain the existing pipeline's nonnegative / over-white-headroom policy.
        ((sample as f64-black).max(0.)/(self.white[p]-self.maxima[p])) as f32
    }
    pub fn storage_bytes(&self)->u64 {
        ((self.base.capacity()+self.white.capacity()+self.delta_h.capacity()+self.delta_v.capacity()+self.maxima.capacity())*8)as u64
    }
}

#[cfg(test)]
mod tests {
 use super::*;
 #[test]
 fn analytical_spatial_black_oracle_and_active_origin() {
  let active=Rect{x:4,y:2,w:7,h:5};
  let base=vec![8.,12.,17.,6.,3.,11.];
  let h=vec![-2.,4.,1.,-5.,7.,3.,0.];let v=vec![3.,-4.,6.,0.,2.];
  let m=BlackModel::new(active,[2,3],1,base.clone(),h.clone(),v.clone(),vec![1023.]).unwrap();
  let local=|x:usize,y:usize|base[(y%2)*3+x%3]+h[x]+v[y];
  let mut maximum=f64::NEG_INFINITY;
  for y in 0..5 {for x in 0..7 {maximum=maximum.max(local(x,y));}}
  assert_eq!(m.maxima,[maximum]);
  for y in 0..5 {for x in 0..7 {let expected=((512.-local(x,y))/(1023.-maximum))as f32;
    assert_eq!(m.normalize(512.,x+4,y+2,0),expected);
  }}
 }
 #[test]
 fn cfa_uses_one_plane_max_not_four_phase_denominators() {
  let m=BlackModel::new(Rect{x:0,y:0,w:8,h:8},[2,2],1,vec![10.,20.,30.,40.],vec![],vec![],vec![1000.]).unwrap();
  assert_eq!(m.normalize(520.,0,0,0),(510f64/960.)as f32);
  assert_eq!(m.normalize(520.,1,1,0),0.5);
 }
 #[test]
 fn rgb_planes_have_independent_maxima() {
  let m=BlackModel::new(Rect{x:2,y:2,w:3,h:2},[1,2],3,vec![10.,20.,30.,40.,50.,60.],vec![1.,2.,3.],vec![4.,5.],vec![1000.,2000.,3000.]).unwrap();
  assert_eq!(m.maxima,[47.,57.,67.]);
  for p in 0..3 {assert_eq!(m.normalize(500.,2,2,p),((500.-[15.,25.,35.][p])/([1000.,2000.,3000.][p]-[47.,57.,67.][p]))as f32);}
 }
 #[test]
 fn combined_black_over_white_is_rejected() {
  assert!(BlackModel::new(Rect{x:0,y:0,w:1,h:1},[1,1],1,vec![5.],vec![6.],vec![0.],vec![10.]).is_err());
 }
 #[test]
 fn malformed_delta_shape_and_zero_denominator_rejected() {
  use crate::tiff::{Value,Endian};
  let active=Rect{x:0,y:0,w:8,h:8};
  for (typ,count,bytes) in [(10,7,vec![0;56]),(5,8,vec![0;64]),(10,8,vec![0;64])] {
   let mut raw=Ifd::new();raw.insert(50715,Value{typ,count,bytes,endian:Endian::Le});
   assert!(BlackModel::parse(&raw,active,1,16).is_err());
  }
 }
 #[test]
 fn uniform_legacy_arithmetic_is_byte_exact() {
  let m=BlackModel::new(Rect{x:0,y:0,w:8,h:8},[2,2],1,vec![15.3;4],vec![],vec![],vec![4095.7]).unwrap();
  for sample in [0f32,15.,100.,1024.,4095.,65535.] {
   let expected=(sample-15.3f32).max(0.)/(4095.7f32-15.3f32);
   assert_eq!(m.normalize(sample,3,4,0).to_bits(),expected.to_bits());
  }
 }

}
