//! Bounded DNG 1.3 List3 geometry in linear camera RGB, before color mapping.
//! Original implementation; Adobe SDK is an external numerical oracle only.
//! Formula source: Adobe DNG Specification 1.7.1, pp. 105–110. The ordered
//! pipeline deliberately resamples/clips after every opcode, never just once
//! after composing coordinates. WarpRectilinear2 is not implemented.
use crate::error::{err, Result};

pub(crate) const MAX_WARPS: usize = 4;

#[derive(Clone, Debug)]
pub struct WarpPipeline {
    pub steps: Vec<Stage3Opcode>,
}
#[derive(Clone, Debug)]
pub enum Stage3Opcode {
    Warp(Warp),
    GainMap(crate::gainmap::GainMap),
}
impl WarpPipeline {
    /// One source backing for a single operation; two alternating backings for
    /// a chain. The final operation is sent directly to color mapping/output.
    pub fn backing_count(&self) -> u64 { self.steps.len().min(2) as u64 }
    pub fn storage_bytes(&self) -> u64 {
        (std::mem::size_of::<Self>() + self.steps.len() * std::mem::size_of::<Stage3Opcode>()) as u64
            + self.steps.iter().map(|step| match step {
                Stage3Opcode::Warp(Warp::Rectilinear(w)) => (w.coefficients.len() * 48) as u64,
                Stage3Opcode::Warp(Warp::Fisheye(w)) => (w.coefficients.len() * 32) as u64,
                Stage3Opcode::GainMap(m) => m.storage_bytes(),
            }).sum::<u64>()
    }
}

#[derive(Clone, Debug)]
pub enum Warp {
    Rectilinear(WarpRectilinear),
    Fisheye(WarpFisheye),
}
impl Warp {
    pub fn is_identity(&self) -> bool {
        match self {
            Self::Rectilinear(w) => w.is_identity(),
            // Even [1,0,0,0] performs atan(r)/r; it is not an identity.
            Self::Fisheye(_) => false,
        }
    }
    pub fn source_position(&self, width:usize, height:usize, x:usize, y:usize, plane:usize) -> Result<[f64;2]> {
        match self {
            Self::Rectilinear(w) => w.source_position(width,height,x,y,plane),
            Self::Fisheye(w) => w.source_position(width,height,x,y,plane),
        }
    }
    pub fn sample(&self, kernel:&CubicWeights, width:usize, height:usize, x:usize, y:usize, plane:usize,
        source:impl FnMut(usize,usize,usize)->Result<f32>) -> Result<f32> {
        match self {
            Self::Rectilinear(w) => w.sample(kernel,width,height,x,y,plane,source),
            Self::Fisheye(w) => sample_position(kernel,width,height,w.source_position(width,height,x,y,plane)?,plane,true,source),
        }
    }
}

#[derive(Clone, Debug)]
pub struct WarpRectilinear {
    pub coefficients: Vec<[f64;6]>,
    pub center: [f64;2],
}
impl WarpRectilinear {
    pub fn parse(bytes:&[u8])->Result<Self> {
        if bytes.len()<4 {return Err(err("INVALID_INPUT","probe"));}
        let planes=u32::from_be_bytes(bytes[..4].try_into().unwrap()) as usize;
        if planes!=1 && planes!=3 {return Err(err("UNSUPPORTED_DNG_OPCODE","probe"));}
        if bytes.len()!=20+48*planes {return Err(err("INVALID_INPUT","probe"));}
        let mut values=bytes[4..].chunks_exact(8).map(|b|f64::from_be_bytes(b.try_into().unwrap()));
        let mut coefficients=Vec::with_capacity(planes);
        for _ in 0..planes {
            let c=std::array::from_fn(|_|values.next().unwrap());
            if c.iter().any(|v:&f64|!v.is_finite()) {return Err(err("INVALID_INPUT","probe"));}
            // Bounded supported numerical envelope, not arbitrary polynomial
            // acceptance. Unknown/extreme metadata remains fail-closed.
            if c.iter().any(|v|v.abs()>16.) || c[0]<=0. {return Err(err("UNSUPPORTED_DNG_OPCODE","probe"));}
            coefficients.push(c);
        }
        let center=[values.next().unwrap(),values.next().unwrap()];
        if center.iter().any(|v|!v.is_finite()||!(0.0..=1.0).contains(v)) {
            return Err(err("INVALID_INPUT","probe"));
        }
        Ok(Self{coefficients,center})
    }
    pub fn is_identity(&self)->bool {
        self.coefficients.iter().all(|c|*c==[1.,0.,0.,0.,0.,0.])
    }
    /// Destination-to-source transform uses integer sample coordinates, not
    /// an extra half-pixel offset. Bounds are the uncropped stage-three image.
    pub fn source_position(&self,width:usize,height:usize,x:usize,y:usize,plane:usize)->Result<[f64;2]> {
        if width<2||height<2||width>32768||height>32768||x>=width||y>=height||plane>=3 {
            return Err(err("INVALID_INPUT","develop"));
        }
        let c=self.coefficients[if self.coefficients.len()==1 {0}else{plane}];
        let cx=self.center[0]*width as f64;
        let cy=self.center[1]*height as f64;
        let rx=cx.max(width as f64-cx);let ry=cy.max(height as f64-cy);
        let radius=(rx*rx+ry*ry).sqrt();let inverse_radius=1./radius;
        let dx=x as f64-cx;let dy=y as f64-cy;
        let nx=dx*inverse_radius;let ny=dy*inverse_radius;let r2=(nx*nx+ny*ny).min(1.);
        let radius_normalized=r2.sqrt();
        let mut ratio=0.;
        for i in (0..14).rev() { let coefficient=if i<=6&&i%2==0 {c[i/2]}else{0.};ratio=ratio*radius_normalized+coefficient; }
        let radial_only=self.coefficients.iter().all(|p|p[4]==0.&&p[5]==0.);
        let tangential_only=self.coefficients.iter().all(|p|p[..4]==[1.,0.,0.,0.]);
        let (sx,sy)=if radial_only {(cx+dx*ratio,cy+dy*ratio)}else{
            let tx=c[5]*(r2+2.*nx*nx)+2.*c[4]*nx*ny;
            let ty=c[4]*(r2+2.*ny*ny)+2.*c[5]*nx*ny;
            if tangential_only {(cx+(dx+radius*tx),cy+(dy+radius*ty))}
            else {(cx+radius*(nx*ratio+tx),cy+radius*(ny*ratio+ty))}
        };
        if !sx.is_finite()||!sy.is_finite(){return Err(err("INVALID_INPUT","develop"));}
        Ok([sx.clamp(0.,(width-1)as f64),sy.clamp(0.,(height-1)as f64)])
    }
    pub fn sample(&self,kernel:&CubicWeights,width:usize,height:usize,x:usize,y:usize,plane:usize,
        mut source:impl FnMut(usize,usize,usize)->Result<f32>)->Result<f32> {
        let position=self.source_position(width,height,x,y,plane)?;
        if self.is_identity() { return source(x,y,plane); }
        sample_position(kernel,width,height,position,plane,true,source)
    }
}

#[derive(Clone, Debug)]
pub struct WarpFisheye {
    pub coefficients: Vec<[f64;4]>,
    pub center: [f64;2],
}
impl WarpFisheye {
    pub fn parse(bytes:&[u8]) -> Result<Self> {
        if bytes.len()<4 { return Err(err("INVALID_INPUT","probe")); }
        let planes=u32::from_be_bytes(bytes[..4].try_into().unwrap()) as usize;
        if planes!=1 && planes!=3 { return Err(err("UNSUPPORTED_DNG_OPCODE","probe")); }
        if bytes.len()!=20+32*planes { return Err(err("INVALID_INPUT","probe")); }
        let mut values=bytes[4..].chunks_exact(8).map(|b|f64::from_be_bytes(b.try_into().unwrap()));
        let mut coefficients=Vec::with_capacity(planes);
        for _ in 0..planes {
            let c=std::array::from_fn(|_|values.next().unwrap());
            if c.iter().any(|v:&f64|!v.is_finite()) { return Err(err("INVALID_INPUT","probe")); }
            if c.iter().any(|v|v.abs()>16.) || !fisheye_increasing(c) {
                return Err(err("UNSUPPORTED_DNG_OPCODE","probe"));
            }
            coefficients.push(c);
        }
        let center=[values.next().unwrap(),values.next().unwrap()];
        if center.iter().any(|v|!v.is_finite() || !(0.0..=1.0).contains(v)) {
            return Err(err("INVALID_INPUT","probe"));
        }
        Ok(Self{coefficients,center})
    }
    pub fn source_position(&self,width:usize,height:usize,x:usize,y:usize,plane:usize) -> Result<[f64;2]> {
        if width<2||height<2||width>32768||height>32768||x>=width||y>=height||plane>=3 {
            return Err(err("INVALID_INPUT","develop"));
        }
        let c=self.coefficients[if self.coefficients.len()==1 {0}else{plane}];
        // Same Adobe SDK image-extent convention as WarpRectilinear.
        let cx=self.center[0]*width as f64; let cy=self.center[1]*height as f64;
        let rx=cx.max(width as f64-cx); let ry=cy.max(height as f64-cy);
        let radius=(rx*rx+ry*ry).sqrt();
        let dx=x as f64-cx; let dy=y as f64-cy;
        let nx=dx/radius; let ny=dy/radius;
        let r=(nx*nx+ny*ny).min(1.).sqrt();
        let theta=r.atan(); let theta2=theta*theta;
        let ratio=if r==0. { c[0] } else {
            theta * (((c[3]*theta2+c[2])*theta2+c[1])*theta2+c[0]) / r
        };
        let sx=cx+dx*ratio; let sy=cy+dy*ratio;
        if !sx.is_finite()||!sy.is_finite() { return Err(err("INVALID_INPUT","develop")); }
        Ok([sx.clamp(0.,(width-1)as f64),sy.clamp(0.,(height-1)as f64)])
    }
}

/// The specification requires w'(r)>0 on [0,1]. With t=atan(r)^2 its sign is
/// the sign of this cubic. Check both endpoints and every interior extremum,
/// rather than accepting a few sampled radii that could miss a fold.
fn fisheye_increasing(c:[f64;4]) -> bool {
    let end=std::f64::consts::FRAC_PI_4.powi(2);
    let polynomial=|t:f64| ((7.*c[3]*t+5.*c[2])*t+3.*c[1])*t+c[0];
    if polynomial(0.)<=0. || polynomial(end)<=0. { return false; }
    let a=21.*c[3]; let b=10.*c[2]; let d=3.*c[1];
    let acceptable=|t:f64| !(0.0..end).contains(&t) || polynomial(t)>0.;
    if a==0. { return b==0. || acceptable(-d/b); }
    let discriminant=b*b-4.*a*d;
    if discriminant<0. { return true; }
    let root=discriminant.sqrt();
    // Stable quadratic roots, including a zero constant term.
    let q=-0.5*(b+root.copysign(b));
    if q==0. { acceptable(-b/(2.*a)) } else { acceptable(q/a) && acceptable(d/q) }
}

/// Shared bounded bicubic sampling. DNG opcodes clip each result to [0,1];
/// camera-maker geometry may retain finite highlight values until calibration.
pub(crate) fn sample_position(kernel:&CubicWeights,width:usize,height:usize,position:[f64;2],plane:usize,
    clip_unit:bool,mut source:impl FnMut(usize,usize,usize)->Result<f32>)->Result<f32> {
        if width<5||height<5 {return Err(err("UNSUPPORTED_DNG_OPCODE","develop"));}
        if width>32768||height>32768||plane>=3||position.iter().any(|v|!v.is_finite()) {
            return Err(err("INVALID_INPUT","develop"));
        }
        let sx=position[0].clamp(0.,(width-1)as f64); let sy=position[1].clamp(0.,(height-1)as f64);
        let ix=sx.floor() as isize;let iy=sy.floor() as isize;
        let mut fx=((sx-ix as f64)*32.) as usize;let mut fy=((sy-iy as f64)*32.) as usize;
        let mut ax=ix-1;let mut ay=iy-1;
        // The reference restricts the complete cubic footprint rather than
        // replicating individual taps. A clipped anchor resets its phase.
        let bx=ax.clamp(0,width as isize-5);let by=ay.clamp(0,height as isize-5);
        if ax!=bx {fx=0;ax=bx;} if ay!=by {fy=0;ay=by;}
        let weights=&kernel.0[fy*32+fx];let mut sum=0f32;
        for row in 0..4 {for col in 0..4 {
            let xx=(ax+col as isize) as usize;
            let yy=(ay+row as isize) as usize;
            let value=source(xx,yy,plane)?;
            if !value.is_finite(){return Err(err("INVALID_COLOR_METADATA","develop"));}
            sum+=weights[row*4+col]*value;
        }}
        if !sum.is_finite(){return Err(err("INVALID_COLOR_METADATA","develop"));}
        Ok(if clip_unit {sum.clamp(0.,1.)} else {sum})
}

/// 32x32 phase bins, sixteen f32 taps. Includes f32 coefficient rounding and
/// normalization; the table costs 64 KiB and is shared across all pixels.
pub struct CubicWeights(Vec<[f32;16]>);
impl CubicWeights {
    pub fn new()->Self {
        let cubic=|x:f64| {let x=x.abs();if x>=2.{0.}else if x>=1.{((-0.75*x+3.75)*x-6.)*x+3.}else{(1.25*x-2.25)*x*x+1.}};
        let mut table=Vec::with_capacity(1024);
        for y in 0..32 {for x in 0..32 {
            let mut weights=[0f32;16];let mut total=0f64;
            for row in 0..4 {for col in 0..4 {
                let w=cubic(col as f64-1.-x as f64/32.) as f32*cubic(row as f64-1.-y as f64/32.) as f32;
                weights[row*4+col]=w;total+=w as f64;
            }}
            let scale=(1./total) as f32;for w in &mut weights {*w*=scale;}
            table.push(weights);
        }}
        Self(table)
    }
}
#[cfg(test)]mod tests {
    use super::*;
    #[test]fn identity_and_constant_are_preserved(){
        let w=WarpRectilinear{coefficients:vec![[1.,0.,0.,0.,0.,0.]],center:[0.5,0.5]};
        let k=CubicWeights::new();
        for y in 0..8{for x in 0..10{assert_eq!(w.source_position(10,8,x,y,2).unwrap(),[x as f64,y as f64]);
            assert_eq!(w.sample(&k,10,8,x,y,2,|_,_,_|Ok(0.25)).unwrap(),0.25);
        }}
    }
    #[test]fn per_plane_coefficients_and_tangential_terms(){
        let w=WarpRectilinear{coefficients:vec![[1.,0.,0.,0.,0.,0.],[0.9,0.,0.,0.,0.,0.],[1.,0.,0.,0.,0.1,0.2]],center:[0.5,0.5]};
        assert_eq!(w.source_position(100,100,20,30,0).unwrap(),[20.,30.]);
        assert_eq!(w.source_position(100,100,20,30,1).unwrap(),[23.,32.]);
        let p=w.source_position(100,100,20,30,2).unwrap();assert!(p[0]>20.&&p[1]>30.);
    }
    #[test]fn malformed_payloads_rejected(){
        assert!(WarpRectilinear::parse(&[0;4]).is_err());
        let mut b=1u32.to_be_bytes().to_vec();for f in [1f64,0.,0.,0.,0.,0.,0.5,0.5]{b.extend(f.to_be_bytes());}
        assert!(WarpRectilinear::parse(&b).unwrap().is_identity());
        b[4..12].copy_from_slice(&f64::NAN.to_be_bytes());assert!(WarpRectilinear::parse(&b).is_err());
    }
    fn fisheye_payload(coefficients:&[[f64;4]],center:[f64;2])->Vec<u8> {
        let mut bytes=(coefficients.len() as u32).to_be_bytes().to_vec();
        bytes.extend(coefficients.iter().flatten().chain(center.iter()).flat_map(|v|v.to_be_bytes()));
        bytes
    }
    #[test]fn fisheye_analytic_projection_and_per_plane_coefficients(){
        let w=WarpFisheye::parse(&fisheye_payload(&[[1.,0.,0.,0.],[0.8,0.,0.,0.],[1.,0.1,0.,0.]],[0.5,0.5])).unwrap();
        // At the farthest corner r=1 and theta=pi/4. These closed-form
        // expectations exercise the DNG fisheye projection, not rectilinear.
        let theta=std::f64::consts::FRAC_PI_4;
        for (plane,radial) in [theta,0.8*theta,theta+0.1*theta.powi(3)].into_iter().enumerate() {
            let position=w.source_position(100,100,0,0,plane).unwrap();
            assert!((position[0]-(50.-50.*radial)).abs()<2e-13);
            assert!((position[1]-(50.-50.*radial)).abs()<2e-13);
            assert_eq!(w.source_position(100,100,50,50,plane).unwrap(),[50.,50.]);
        }
        assert!(!Warp::Fisheye(w).is_identity());
        let off_center=WarpFisheye::parse(&fisheye_payload(&[[1.,0.,0.,0.]],[0.25,0.75])).unwrap();
        assert_eq!(off_center.source_position(100,80,25,60,2).unwrap(),[25.,60.]);
    }
    #[test]fn fisheye_validates_payload_and_continuous_monotonicity(){
        let valid=fisheye_payload(&[[1.,0.,0.,0.]],[0.5,0.5]);
        for end in 0..valid.len() { assert!(WarpFisheye::parse(&valid[..end]).is_err()); }
        let mut trailing=valid.clone();trailing.push(0);assert!(WarpFisheye::parse(&trailing).is_err());
        for c in [[f64::NAN,0.,0.,0.],[f64::INFINITY,0.,0.,0.],[-1.,0.,0.,0.],[17.,0.,0.,0.],
            // w'(r) has positive endpoints, but a negative interior minimum.
            [0.03,-0.4/3.,0.2,0.]] {
            assert!(WarpFisheye::parse(&fisheye_payload(&[c],[0.5,0.5])).is_err());
        }
        assert!(WarpFisheye::parse(&fisheye_payload(&[[0.05,-0.4/3.,0.2,0.]],[0.5,0.5])).is_ok());
        assert!(WarpFisheye::parse(&fisheye_payload(&[[1.,0.,0.,0.];2],[0.5,0.5])).is_err());
        for center in [[-0.1,0.5],[0.5,1.1],[0.5,f64::NAN]] {
            assert!(WarpFisheye::parse(&fisheye_payload(&[[1.,0.,0.,0.]],center)).is_err());
        }
    }
    #[test]fn shared_sampling_preserves_dng_borders_and_optional_highlight_clipping(){
        let kernel=CubicWeights::new();
        for position in [[0.,0.],[3.,4.],[9.,7.],[4.25,3.5]] {
            assert_eq!(sample_position(&kernel,10,8,position,1,true,|_,_,_|Ok(2.)).unwrap(),1.);
            let unclipped=sample_position(&kernel,10,8,position,1,false,|_,_,_|Ok(2.)).unwrap();
            assert!((unclipped-2.).abs()<2e-6);
        }
        // The existing SDK border policy clamps the whole footprint and resets
        // its phase, so the first/last samples select (1,1) and (6,4).
        assert_eq!(sample_position(&kernel,10,8,[0.,0.],0,false,|x,y,_|Ok((x+10*y)as f32)).unwrap(),11.);
        assert_eq!(sample_position(&kernel,10,8,[9.,7.],0,false,|x,y,_|Ok((x+10*y)as f32)).unwrap(),46.);
        assert_eq!(sample_position(&kernel,10,8,[f64::NAN,0.],0,true,|_,_,_|panic!("invalid coordinate was sampled")).unwrap_err().code,"INVALID_INPUT");
    }
}
