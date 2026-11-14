//! Bounded DNG linear camera -> XYZ D50 calibration.
//! Original matrix/iteration implementation of Adobe DNG 1.7.1 chapter 6.
//! No Adobe SDK code is included. This product includes DNG technology under
//! license by Adobe. This is the specification PATENT-license notice only;
//! no Adobe SDK code is shipped. SDK is an external test oracle only.
//! This is NOT a complete Adobe profile/render pipeline.
//! Supported contract: 3 channels, standard single/dual illuminants, forward
//! matrices, full camera-calibration matrices, positive analog balance, neutral/xy WB.
//! DNG calibration uses the reference-compatible 325-mired ordinate; the
//! standalone Robertson utility retains the independently corrected dataset.
//! It rejects nonconvergence instead of concealing an approximate white point.

pub type Matrix = [[f64; 3]; 3];
pub type Vector = [f64; 3];
pub type ColorResult<T> = Result<T, ColorError>;
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ColorErrorKind { InvalidMetadata, UnsupportedCalibration, NonConvergent }
#[derive(Debug, Clone, Copy)]
pub struct ColorError {
    kind: ColorErrorKind,
    site: Option<&'static std::panic::Location<'static>>,
}
// Equality remains about failure semantics, not instrumentation location.
impl PartialEq for ColorError { fn eq(&self, other: &Self) -> bool { self.kind == other.kind } }
impl Eq for ColorError {}
#[allow(non_upper_case_globals)]
impl ColorError {
    pub const InvalidMetadata: Self = Self { kind: ColorErrorKind::InvalidMetadata, site: None };
    pub const UnsupportedCalibration: Self = Self { kind: ColorErrorKind::UnsupportedCalibration, site: None };
    pub const NonConvergent: Self = Self { kind: ColorErrorKind::NonConvergent, site: None };
    #[track_caller]
    pub(crate) fn located(mut self) -> Self { self.site = Some(std::panic::Location::caller()); self }
    pub fn code(self) -> &'static str {
        match self.kind {
            ColorErrorKind::InvalidMetadata => "INVALID_COLOR_METADATA",
            ColorErrorKind::UnsupportedCalibration | ColorErrorKind::NonConvergent => "UNSUPPORTED_DNG_CALIBRATION",
        }
    }
    #[track_caller]
    pub fn into_codec(self) -> crate::error::CodecError {
        let mut error = crate::error::err(self.code(), "probe")
            .detail(crate::error::Reason::ColorTransform, None);
        if let Some(site) = self.site {
            error.source = crate::error::source(site.file());
            error.line = site.line();
            error.column = site.column();
        }
        error
    }
}
#[derive(Clone, Copy, Debug)]
pub struct Calibration {
    /// Standard TIFF LightSource. Pair order is immaterial.
    pub illuminant: u16,
    pub color_matrix: Matrix,
    pub forward_matrix: Matrix,
    /// Full individual-camera calibration matrix; ignored on signature mismatch.
    pub camera_calibration: Matrix,
}
#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub calibrations: [Calibration; 2],
    pub analog_balance: Vector,
    pub as_shot_neutral: Vector,
    /// Per DNG, a signature mismatch disables camera calibration, not CM/FM.
    pub calibration_signatures_match: bool,
}
#[derive(Clone, Copy, Debug)]
pub struct Transform {
    pub camera_to_pcs: Matrix,
    pub camera_white: Vector,
    pub white_xy: [f64; 2],
    pub temperature_kelvin: f64,
    pub d65_weight: f64,
    pub iterations: usize,
}
const ID: Matrix = [[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]];
pub const D50_XY: [f64;2] = [0.3457,0.3585];
#[track_caller]
fn bad<T>() -> ColorResult<T> { Err(ColorError::InvalidMetadata.located()) }
fn xyz(xy: [f64;2]) -> ColorResult<Vector> {
    if !xy.iter().all(|v|v.is_finite()) || xy[0]<=0. || xy[1]<=0. || xy[0]+xy[1]>=1. { return bad(); }
    Ok([xy[0]/xy[1],1.,(1.-xy[0]-xy[1])/xy[1]])
}
pub fn d50_xyz() -> Vector { [D50_XY[0]/D50_XY[1],1.,(1.-D50_XY[0]-D50_XY[1])/D50_XY[1]] }
pub fn apply(m: Matrix, v: Vector) -> Vector {
    std::array::from_fn(|r|m[r][0]*v[0]+m[r][1]*v[1]+m[r][2]*v[2])
}
pub(crate) fn product(a: Matrix,b: Matrix)->Matrix {
    std::array::from_fn(|r|std::array::from_fn(|c|a[r][0]*b[0][c]+a[r][1]*b[1][c]+a[r][2]*b[2][c]))
}
fn diagonal(v:Vector)->Matrix {std::array::from_fn(|r|std::array::from_fn(|c|if r==c {v[r]} else {0.}))}
fn mix(a:Matrix,b:Matrix,t:f64)->Matrix {
    if t<=0. {return a;} if t>=1. {return b;}
    std::array::from_fn(|r|std::array::from_fn(|c|(1.-t)*a[r][c]+t*b[r][c]))
}
pub(crate) fn inverse(m:Matrix)->ColorResult<Matrix> {
    if !m.iter().flatten().all(|x|x.is_finite()) {return bad();}
    // Pivoted Gauss-Jordan, with a relative pivot guard and residual check.
    let scale=m.iter().flatten().fold(0f64,|a,&b|a.max(b.abs()));
    if scale==0. {return bad();}
    let mut a=[[0.;6];3];
    for r in 0..3 {for c in 0..3 {a[r][c]=m[r][c];a[r][c+3]=ID[r][c];}}
    for c in 0..3 {
        let pivot=(c..3).max_by(|&r,&s|a[r][c].abs().total_cmp(&a[s][c].abs())).unwrap();
        if a[pivot][c].abs()<scale*1e-12 {return bad();}
        a.swap(c,pivot);let divisor=a[c][c];
        for x in &mut a[c] {*x/=divisor;}
        for r in 0..3 {if r!=c {let k=a[r][c];for j in 0..6 {a[r][j]-=k*a[c][j];}}}
    }
    let result=std::array::from_fn(|r|std::array::from_fn(|c|a[r][c+3]));
    let check=product(m,result);
    if !result.iter().flatten().all(|x|x.is_finite()) || (0..3).any(|r|(0..3).any(|c|(check[r][c]-ID[r][c]).abs()>1e-8)) {return bad();}
    Ok(result)
}
/// Nominal temperatures match the Adobe DNG reference model (not measured
/// lamp spectra). Unknown/custom illuminants require additional metadata.
/// Reference: android.googlesource.com/platform/external/dng_sdk/+/master/source/dng_camera_profile.cpp
pub(crate) fn illuminant_kelvin(light: u16) -> ColorResult<f64> {
    match light {3|17=>Ok(2850.),24=>Ok(3200.),1|4|9|18|20=>Ok(5500.),10|19|21=>Ok(6500.),11|22=>Ok(7500.),23=>Ok(5000.),
        12=>Ok(6400.),13=>Ok(5050.),2|14=>Ok(4150.),15=>Ok(3525.),16=>Ok(2925.),
        _=>Err(ColorError::UnsupportedCalibration.located())}
}
pub(crate) fn calibration_weight(low:f64,high:f64,t:f64)->ColorResult<f64>{
    if !t.is_finite()||t<=0. {return bad();}
    if low==high {return Ok(0.);}
    Ok(((1./low-1./t)/(1./low-1./high)).clamp(0.,1.))
}
fn positive(v:Vector)->bool {v.iter().all(|x|x.is_finite()&&*x>0.)}
fn round4(v:f64)->f64 {(v*10000.+0.5).floor()/10000.}
fn rounded(m:Matrix)->Matrix {m.map(|row|row.map(round4))}
fn profile_cm(m:Matrix)->ColorResult<Matrix> {
    if !m.iter().flatten().all(|x|x.is_finite()) {return bad();}
    let peak=apply(m,d50_xyz()).into_iter().fold(f64::NEG_INFINITY,f64::max);
    if peak<=0. {return bad();}
    let divisor=if !(0.99..=1.01).contains(&peak) {peak}else{1.};
    let result=rounded(m.map(|r|r.map(|x|x/divisor)));inverse(result)?;Ok(result)
}
fn profile_fm(m:Matrix)->ColorResult<Matrix> {
    if !m.iter().flatten().all(|x|x.is_finite()) {return bad();}
    let rounded=rounded(m);let white=apply(rounded,[1.;3]);
    if !positive(white) {return bad();}
    let pcs=d50_xyz();let result=std::array::from_fn(|r|rounded[r].map(|x|x*pcs[r]/white[r]));
    inverse(result)?;Ok(result)
}
#[derive(Clone,Copy)]
struct Prepared { cm:Matrix,fm:Matrix,cc:Matrix }
fn prepare(c:Calibration,ab:Vector,signatures_match:bool)->ColorResult<Prepared> {
    if !c.camera_calibration.iter().flatten().all(|v| v.is_finite()) {return bad();}
    let cc=if signatures_match {rounded(c.camera_calibration)}else{ID};
    inverse(cc)?;
    let cm=profile_cm(c.color_matrix)?;
    inverse(product(product(diagonal(ab), cc), cm))?;
    Ok(Prepared{cm,fm:profile_fm(c.forward_matrix)?,cc})
}
/// Validated production envelope. No per-pixel iteration or allocation.
pub fn resolve(input:&Input)->ColorResult<Transform> {
    resolve_internal(input, None, |xy|robertson_from_table(xy,true))
}
/// Resolve a standard AsShotWhiteXY directly, without a neutral-to-xy iteration.
pub fn resolve_white_xy(input:&Input, xy:[f64;2])->ColorResult<Transform> {
    xyz(xy)?;
    resolve_internal(input, Some(xy), |xy|robertson_from_table(xy,true))
}
/// Injectable CCT evaluator for independent matrix unit/oracle tests. Not a
/// fallback: production callers must use resolve until broader validation.
pub fn resolve_with_cct(input:&Input, cct:impl Fn([f64;2])->ColorResult<f64>)->ColorResult<Transform> {
    resolve_internal(input,None,cct)
}
fn resolve_internal(input:&Input, supplied_xy:Option<[f64;2]>, cct:impl Fn([f64;2])->ColorResult<f64>)->ColorResult<Transform> {
    if !positive(input.analog_balance)||!positive(input.as_shot_neutral) {return bad();}
    // Metadata normalization is explicit and mirrors the chosen numerical
    // convention: AB minimum=1, neutral maximum=1, six decimal places.
    let ab_min=input.analog_balance.into_iter().fold(f64::INFINITY,f64::min);
    let n_max=input.as_shot_neutral.into_iter().fold(0f64,f64::max);
    let round6=|x:f64|(x*1e6+0.5).floor()/1e6;
    let ab=input.analog_balance.map(|x|round6(x/ab_min));
    let neutral=input.as_shot_neutral.map(|x| {let n=x/n_max;let rounded=round6(n);if rounded>0. {rounded}else{n}});
    if !positive(ab)||!positive(neutral) {return bad();}
    let [one,two]=input.calibrations;
    let t1=illuminant_kelvin(one.illuminant)?;let t2=illuminant_kelvin(two.illuminant)?;
    let (a,d,low,high)=if t1<=t2 {(one,two,t1,t2)}else{(two,one,t2,t1)};
    let a=prepare(a,ab,input.calibration_signatures_match)?;
    let d=prepare(d,ab,input.calibration_signatures_match)?;
    let blend=|xy|->ColorResult<f64>{
        let t=cct(xy)?;if !t.is_finite()||t<=0. {return bad();}
        calibration_weight(low,high,t)
    };
    let mut xy=supplied_xy.unwrap_or(D50_XY);let mut iterations=0;let mut converged=supplied_xy.is_some();
    for count in 1..=if supplied_xy.is_some(){0}else{30} {
        let weight=blend(xy)?;
        let xyz_to_camera=product(product(diagonal(ab),mix(a.cc,d.cc,weight)),mix(a.cm,d.cm,weight));
        let v=apply(inverse(xyz_to_camera)?,neutral);
        let sum=v.iter().sum::<f64>();if !sum.is_finite()||sum<=0. {return bad();}
        let next=[v[0]/sum,v[1]/sum];xyz(next)?;
        iterations=count;
        let delta=(next[0]-xy[0]).abs()+(next[1]-xy[1]).abs();xy=next;
        if delta<1e-7 {converged=true;break;}
    }
    if !converged {return Err(ColorError::NonConvergent.located());}
    let temperature_kelvin=cct(xy)?;let weight=blend(xy)?;
    let cam=apply(product(product(diagonal(ab),mix(a.cc,d.cc,weight)),mix(a.cm,d.cm,weight)),xyz(xy)?);
    let peak=cam.into_iter().fold(f64::NEG_INFINITY,f64::max);
    if !positive(cam)||!peak.is_finite() {return bad();}
    let camera_white=cam.map(|x|(x/peak).clamp(0.001,1.));
    let cc=mix(a.cc,d.cc,weight);
    let individual_to_reference=inverse(product(diagonal(ab),cc))?;
    let reference_neutral=apply(individual_to_reference,camera_white);
    if !positive(reference_neutral) {return bad();}
    let camera_to_pcs=product(product(mix(a.fm,d.fm,weight),diagonal(reference_neutral.map(|x|1./x))),individual_to_reference);
    if !camera_to_pcs.iter().flatten().all(|x|x.is_finite()) {return bad();}
    let mapped=apply(camera_to_pcs,camera_white);
    if (0..3).any(|i|(mapped[i]-d50_xyz()[i]).abs()>1e-8) {return bad();}
    Ok(Transform{camera_to_pcs,camera_white,white_xy:xy,temperature_kelvin,d65_weight:weight,iterations})
}
/// Robertson CIE 1960 UCS isotherm interpolation, implemented independently
/// from the line-distance geometry. The data's BSD notice follows below.
pub fn robertson_cct(xy:[f64;2])->ColorResult<f64> {robertson_from_table(xy,false)}
pub(crate) fn robertson_from_table(xy:[f64;2],dng_reference:bool)->ColorResult<f64> {
    xyz(xy)?;let denom=-2.*xy[0]+12.*xy[1]+3.;
    let u=4.*xy[0]/denom;let v=6.*xy[1]/denom;
    // A single numerical compatibility datum, not imported SDK implementation:
    // official Adobe SDK1.7.1 build2768 source/dng_temperature.cpp uses .24702
    // at325 mired. The independent Colour dataset corrects it to .24792.
    // DNG dual-illuminant calibration must reproduce the reference convention
    // even during transient NeutralToXY iterations (actual S23 6697 enters it).
    let distance=|mut row:[f64;4]|{if dng_reference && row[0]==325. {row[1]=0.24702;}
        (v-row[2]-row[3]*(u-row[1]))/(1.+row[3]*row[3]).sqrt()};
    // Fail closed outside the finite tabulated/interpolable domain.
    let mut prev=distance(ROBERTSON[0]);
    for i in 1..ROBERTSON.len() {
        let curr=distance(ROBERTSON[i]);
        if curr<=0. {
            let (lo,hi)=(ROBERTSON[i-1][0],ROBERTSON[i][0]);
            let f=if prev<=0. {0.}else{prev/(prev-curr)};
            let reciprocal=lo+(hi-lo)*f;
            if reciprocal<=0. {return Err(ColorError::UnsupportedCalibration.located());}
            return Ok(1e6/reciprocal);
        }
        prev=curr;
    }
    Err(ColorError::UnsupportedCalibration.located())
}

/*
Numerical data only from Colour v0.4.7, commit
a3bfe349685f528100672e5c8ca2dfeeef64a273:
https://github.com/colour-science/colour/blob/a3bfe349685f528100672e5c8ca2dfeeef64a273/colour/temperature/robertson1968.py
Robertson (1968); Wyszecki & Stiles, Color Science, Table 1(3.11), p.228.
Includes independent Lindbloom correction: at 325 mired u=0.24792.
No Colour implementation code is used. Retain this notice in source and
include it in binary distribution notices before integrating this module.

Copyright 2013 Colour Developers

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS “AS IS” AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE

*/
const ROBERTSON:[[f64;4];31]=[
    [0.0,0.18006,0.26352,-0.24341],
    [10.0,0.18066,0.26589,-0.25479],
    [20.0,0.18133,0.26846,-0.26876],
    [30.0,0.18208,0.27119,-0.28539],
    [40.0,0.18293,0.27407,-0.3047],
    [50.0,0.18388,0.27709,-0.32675],
    [60.0,0.18494,0.28021,-0.35156],
    [70.0,0.18611,0.28342,-0.37915],
    [80.0,0.1874,0.28668,-0.40955],
    [90.0,0.1888,0.28997,-0.44278],
    [100.0,0.19032,0.29326,-0.47888],
    [125.0,0.19462,0.30141,-0.58204],
    [150.0,0.19962,0.30921,-0.70471],
    [175.0,0.20525,0.31647,-0.84901],
    [200.0,0.21142,0.32312,-1.0182],
    [225.0,0.21807,0.32909,-1.2168],
    [250.0,0.22511,0.33439,-1.4512],
    [275.0,0.23247,0.33904,-1.7298],
    [300.0,0.2401,0.34308,-2.0637],
    [325.0,0.24792,0.34655,-2.4681],
    [350.0,0.25591,0.34951,-2.9641],
    [375.0,0.264,0.352,-3.5814],
    [400.0,0.27218,0.35407,-4.3633],
    [425.0,0.28039,0.35577,-5.3762],
    [450.0,0.28863,0.35714,-6.7262],
    [475.0,0.29685,0.35823,-8.5955],
    [500.0,0.30505,0.35907,-11.324],
    [525.0,0.3132,0.35968,-15.628],
    [550.0,0.32129,0.36011,-23.325],
    [575.0,0.32931,0.36038,-40.77],
    [600.0,0.33724,0.36051,-116.45],
];

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn diagnostic_origin_survives_color_error_conversion() {
        let mut invalid_neutral = expert();
        invalid_neutral.as_shot_neutral[0] = 0.;
        let first = resolve(&invalid_neutral).unwrap_err().into_codec();
        let mut invalid_matrix = expert();
        invalid_matrix.calibrations[0].color_matrix[0][0] = f64::NAN;
        let second = resolve(&invalid_matrix).unwrap_err().into_codec();
        assert_eq!(first.code, second.code);
        assert_eq!(first.source, "DNG_COLOR_SPEC");
        assert_eq!(second.source, "DNG_COLOR_SPEC");
        assert_ne!((first.line, first.column), (second.line, second.column));
        let serialized = serde_json::to_string(&first).unwrap();
        assert!(!serialized.contains('/'));
        assert!(!serialized.contains("NaN"));
    }
    fn expert()->Input {
        let cal=diagonal([1.0029296875,1.,1.0087890625]);
        Input{analog_balance:[1.;3],as_shot_neutral:[493./1024.,1.,179./256.],calibration_signatures_match:true,
            calibrations:[
                Calibration{illuminant:21,camera_calibration:cal,
                    color_matrix:[[687./1024.,-130./1024.,-101./1024.],[-528./1024.,1455./1024.,65./1024.],[-182./1024.,381./1024.,502./1024.]],
                    forward_matrix:[[704./1024.,112./1024.,172./1024.],[257./1024.,773./1024.,-5./1024.],[43./1024.,-410./1024.,1212./1024.]]},
                Calibration{illuminant:17,camera_calibration:cal,
                    color_matrix:[[1461./1024.,-803./1024.,-139./1024.],[-291./1024.,1368./1024.,-68./1024.],[-24./1024.,241./1024.,553./1024.]],
                    forward_matrix:[[502./1024.,211./1024.,275./1024.],[68./1024.,841./1024.,116./1024.],[-49./1024.,-883./1024.,1777./1024.]]},
            ]}
    }
    #[test] fn independent_sdk_expert_matrix() {
        // Parent's separately executed pinned SDK metadata oracle, not this
        // implementation's own expected values. Retained expert-sdk-color.json.
        let a=resolve(&expert()).unwrap();
        println!("transform={a:?}");
        let expected=[[1.3452423516761325,0.12864659355511143,0.26885552291752329],[0.44426781169162721,0.76740381813248359,0.026752231438015472],[0.04991038825051701,-0.4926619761148448,1.8502607676546821]];
        for r in 0..3 {for c in 0..3 {assert!((a.camera_to_pcs[r][c]-expected[r][c]).abs()<1e-10,"{r},{c}");}}
        assert!((a.white_xy[0]-0.33980397608457868).abs()<1e-10);
        assert!((a.white_xy[1]-0.33889595463086292).abs()<1e-10);
    }
    #[test] fn matrix_inverse_and_neutral() {
        let m=[[1.,2.,3.],[0.,4.,5.],[1.,0.,6.]];
        let p=product(m,inverse(m).unwrap());
        for r in 0..3 {for c in 0..3 {assert!((p[r][c]-ID[r][c]).abs()<1e-12);}}
        let t=resolve(&expert()).unwrap();let w=apply(t.camera_to_pcs,t.camera_white);
        for i in 0..3 {assert!((w[i]-d50_xyz()[i]).abs()<1e-12);}
    }
    #[test] fn reject_unproven_or_invalid_metadata() {
        let mut i=expert();i.calibrations[0].illuminant=255;
        assert_eq!(resolve(&i).unwrap_err(),ColorError::UnsupportedCalibration);
        let mut i=expert();i.analog_balance[0]=f64::NAN;assert!(resolve(&i).is_err());
        let mut i=expert();i.calibrations[0].forward_matrix=[[0.;3];3];assert!(resolve(&i).is_err());
        assert!(robertson_cct([f64::NAN,0.3]).is_err());
    }
    #[test] fn injected_temperature_and_calibration_signature() {
        let i=expert();let a=resolve_with_cct(&i,|_|Ok(6500.)).unwrap();assert_eq!(a.d65_weight,1.);
        let mut j=i;j.calibration_signatures_match=false;
        let b=resolve_with_cct(&j,|_|Ok(6500.)).unwrap();
        assert_ne!(a.white_xy,b.white_xy);
        let mut k=i;k.calibrations.swap(0,1);
        assert_eq!(resolve(&i).unwrap().camera_to_pcs,resolve(&k).unwrap().camera_to_pcs);
    }
    #[test] fn warm_domain_reference_converges() {
        let t=robertson_cct([0.4476,0.4074]).unwrap();assert!(t<3500.);
        let mut i=expert();let c=prepare(i.calibrations[1],[1.;3],true).unwrap();
        i.as_shot_neutral=apply(product(c.cc,c.cm),xyz([0.4476,0.4074]).unwrap());
        assert!(resolve(&i).is_ok());
    }
}

#[cfg(test)] mod additional_actual_oracles {
use super::*;
#[test] fn s23_6697_3403k_matches_sdk2768() {
let input=Input { calibrations:[Calibration{illuminant:21,color_matrix:[[0.8115234375,-0.18359375,-0.123046875],[-0.5947265625,1.501953125,0.05859375],[-0.162109375,0.3330078125,0.43359375]],forward_matrix:[[0.6552734375,0.1298828125,0.1787109375],[0.259765625,0.7236328125,0.0166015625],[0.0322265625,-0.380859375,1.173828125]],camera_calibration:diagonal([0.986328125,1.0,1.02734375])},Calibration{illuminant:17,color_matrix:[[1.7353515625,-0.9775390625,-0.1767578125],[-0.404296875,1.484375,-0.1123046875],[-0.0078125,0.19921875,0.47265625]],forward_matrix:[[0.4814453125,0.197265625,0.28515625],[0.0986328125,0.7568359375,0.14453125],[-0.115234375,-0.7919921875,1.7314453125]],camera_calibration:diagonal([0.986328125,1.0,1.02734375])},],analog_balance:[1.;3],as_shot_neutral:[0.708984375, 1.0, 0.431640625],calibration_signatures_match:true};
let t=resolve(&input).unwrap();assert!(t.temperature_kelvin>3333.333 && t.temperature_kelvin<3500.);
let expected=[[0.750284960320298,0.17786932576964934,0.5895786713137943],[0.20487591071192846,0.7472466422201414,0.24904867150320717],[-0.10245139158578376,-0.6736575926762108,3.640522088700886]];
for r in 0..3{for c in 0..3{assert!((t.camera_to_pcs[r][c]-expected[r][c]).abs()<1e-12);}}
}
}

#[cfg(test)]
mod general_calibration_tests {
    use super::*;
    #[test]
    fn non_diagonal_calibration_uses_full_inverse_not_diagonal_approximation() {
        // An analytically invertible upper-triangular matrix. Both calibrations
        // differ, so this also exercises CC interpolation separately from CM.
        let a=Calibration{illuminant:17,color_matrix:ID,forward_matrix:ID,
            camera_calibration:[[1.,0.1,0.],[0.,1.,0.1],[0.,0.,1.]]};
        let d=Calibration{illuminant:21,color_matrix:ID,forward_matrix:ID,
            camera_calibration:[[1.,0.3,0.],[0.,1.,0.3],[0.,0.,1.]]};
        let input=Input{calibrations:[a,d],analog_balance:[1.,2.,3.],
            as_shot_neutral:[0.5,1.,0.75],calibration_signatures_match:true};
        let midpoint=2./(1./2850.+1./6500.);
        let t=resolve_with_cct(&input, |_|Ok(midpoint)).unwrap();
        assert!((t.d65_weight-0.5).abs()<1e-12);
        // Inverse(diag(1,2,3) * [[1,.2,0],[0,1,.2],[0,0,1]])
        let inv=[[1.,-0.1,0.04/3.],[0.,0.5,-0.2/3.],[0.,0.,1./3.]];
        let reference:[f64;3]=std::array::from_fn(|r|(0..3).map(|c|inv[r][c]*t.camera_white[c]).sum());
        for r in 0..3 {for c in 0..3 {
            let expected=d50_xyz()[r]/reference[r]*inv[r][c];
            assert!((t.camera_to_pcs[r][c]-expected).abs()<1e-12);
        }}
        assert!(t.camera_to_pcs[0][1].abs()>0.01);
        let mut ignored=input; ignored.calibration_signatures_match=false;
        let t=resolve_with_cct(&ignored, |_|Ok(midpoint)).unwrap();
        assert!(t.camera_to_pcs[0][1].abs()<1e-12);
    }
    #[test]
    fn general_calibration_still_rejects_singular_and_nonfinite() {
        let base=Calibration{illuminant:17,color_matrix:ID,forward_matrix:ID,camera_calibration:ID};
        let mut input=Input{calibrations:[base,Calibration{illuminant:21,..base}],analog_balance:[1.;3],as_shot_neutral:[0.5,1.,0.7],calibration_signatures_match:true};
        input.calibrations[0].camera_calibration=[[1.,1.,0.],[1.,1.,0.],[0.,0.,1.]];
        assert!(resolve(&input).is_err());
        input.calibrations[0].camera_calibration=ID;
        input.calibrations[0].camera_calibration[0][1]=f64::NAN;
        assert!(resolve(&input).is_err());
    }
}

#[cfg(test)]
mod white_xy_tests {
 use super::*;
 #[test]
 fn direct_d50_identity_profile_is_identity_and_no_iteration() {
  let a=Calibration{illuminant:17,color_matrix:ID,forward_matrix:ID,camera_calibration:ID};
  let i=Input{calibrations:[a,Calibration{illuminant:21,..a}],analog_balance:[1.;3],as_shot_neutral:[1.;3],calibration_signatures_match:true};
  let t=resolve_white_xy(&i,D50_XY).unwrap();
  assert_eq!(t.iterations,0);assert_eq!(t.white_xy,D50_XY);
  for r in 0..3 {for c in 0..3 {assert!((t.camera_to_pcs[r][c]-ID[r][c]).abs()<1e-12);}}
  for xy in [[0.,0.3],[0.5,0.5],[f64::NAN,0.3]] {assert!(resolve_white_xy(&i,xy).is_err());}
 }
}

#[cfg(test)] mod generalized_calibration_tests {
 use super::*;
 #[test] fn nominal_temperatures_and_reciprocal_interpolation(){
  for (code,k) in [(17,2850.),(20,5500.),(21,6500.),(22,7500.),(23,5000.)]{assert_eq!(illuminant_kelvin(code).unwrap(),k);}
  assert!(illuminant_kelvin(255).is_err());
  assert_eq!(calibration_weight(5000.,7500.,4000.).unwrap(),0.);
  assert_eq!(calibration_weight(5000.,7500.,9000.).unwrap(),1.);
  assert!((calibration_weight(5000.,7500.,6000.).unwrap()-0.5).abs()<1e-12);
 }
 #[test] fn generalized_pair_preserves_reference_interpolant(){
  let a=Calibration{illuminant:17,color_matrix:ID,forward_matrix:[[0.8,0.1,0.1],[0.2,0.7,0.1],[0.05,0.2,0.75]],camera_calibration:ID};
  let b=Calibration{illuminant:21,color_matrix:[[1.,0.1,0.],[0.,1.,0.1],[0.1,0.,1.]],forward_matrix:[[0.7,0.2,0.1],[0.1,0.8,0.1],[0.2,0.1,0.7]],camera_calibration:ID};
  let input=Input{calibrations:[a,b],analog_balance:[1.;3],as_shot_neutral:[0.7,1.,0.8],calibration_signatures_match:true};
  let old_mid=2./(1./2850.+1./6500.);
  let reference=resolve_with_cct(&input, |_|Ok(old_mid)).unwrap();
  let mut expanded=input;expanded.calibrations[0].illuminant=23;expanded.calibrations[1].illuminant=22;
  let actual=resolve_with_cct(&expanded, |_|Ok(6000.)).unwrap();
  for r in 0..3 {for c in 0..3 {assert!((actual.camera_to_pcs[r][c]-reference.camera_to_pcs[r][c]).abs()<1e-12);}}
 }
 #[test] fn all_standard_pairs_order_and_neutral_scale(){
  for a in [17,20,21,22,23] {for b in [17,20,21,22,23] {
   let base=Calibration{illuminant:a,color_matrix:ID,forward_matrix:ID,camera_calibration:ID};
   let mut input=Input{calibrations:[base,Calibration{illuminant:b,..base}],analog_balance:[1.;3],as_shot_neutral:[0.9,1.,0.8],calibration_signatures_match:true};
   let expected=resolve(&input).unwrap();
   input.calibrations.swap(0,1);assert_eq!(resolve(&input).unwrap().camera_to_pcs,expected.camera_to_pcs);
   input.as_shot_neutral=[900.,1000.,800.];assert_eq!(resolve(&input).unwrap().camera_to_pcs,expected.camera_to_pcs);
   let actual=apply(expected.camera_to_pcs,expected.camera_white);
   for c in 0..3 {assert!((actual[c]-d50_xyz()[c]).abs()<1e-10);}
  }}
 }
}
