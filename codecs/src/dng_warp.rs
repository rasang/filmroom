//! Bounded DNG 1.3 WarpRectilinear in linear camera RGB, before color mapping.
//! Original implementation; Adobe SDK is an external numerical oracle only.
use crate::error::{err, Result};

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
        let [sx,sy]=self.source_position(width,height,x,y,plane)?;
        if self.is_identity() { return source(x,y,plane); }
        if width<5||height<5 {return Err(err("UNSUPPORTED_DNG_OPCODE","develop"));}
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
        // Stage-three geometric filtering has a unit-range output contract.
        Ok(sum.clamp(0.,1.))
    }
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
}
