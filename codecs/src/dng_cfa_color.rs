//! Bounded three-channel DNG ColorMatrix-only calibration to XYZ D50.
//! Independently implemented from the DNG calibration equations. Adobe SDK is
//! an external numerical test oracle only; no SDK source or runtime is included.
//! This product includes DNG technology under license by Adobe.
//!
//! Supported envelope: standard single/dual illuminants, full camera
//! calibration, positive analog balance and neutral/xy white balance. The caller must
//! reject ForwardMatrix, ReductionMatrix, a third calibration and incompatible profile operations before selecting it.
//! This module does not demosaic, correct bad pixels, warp or render a profile.
use crate::dng_color_spec::{apply, d50_xyz, inverse, product, robertson_from_table,
    ColorError, ColorResult, Matrix, Vector, D50_XY};

#[derive(Clone, Copy, Debug)]
pub struct Calibration {
    /// Standard TIFF LightSource; input pair order is immaterial.
    pub illuminant: u16,
    pub color_matrix: Matrix,
    /// Full individual-camera calibration matrix.
    pub camera_calibration: Matrix,
}
#[derive(Clone, Copy, Debug)]
pub struct Input {
    pub calibrations: [Calibration; 2],
    pub analog_balance: Vector,
    pub as_shot_neutral: Vector,
    pub calibration_signatures_match: bool,
}
#[derive(Clone, Copy, Debug)]
pub struct Transform {
    pub camera_to_pcs: Matrix,
    pub camera_white: Vector,
    pub white_xy: [f64; 2],
    pub temperature_kelvin: f64,
    pub d50_weight: f64,
    pub iterations: usize,
}
#[track_caller]
fn invalid<T>() -> ColorResult<T> { Err(ColorError::InvalidMetadata.located()) }
fn positive(v: Vector) -> bool { v.iter().all(|x| x.is_finite() && *x > 0.) }
fn peak(v: Vector) -> f64 { v.into_iter().fold(f64::NEG_INFINITY, f64::max) }
fn round(v: f64, precision: f64) -> f64 { (v * precision + 0.5).floor() / precision }
fn diagonal(v: Vector) -> Matrix {
    std::array::from_fn(|r| std::array::from_fn(|c| if r == c { v[r] } else { 0. }))
}
fn xyz(xy: [f64; 2]) -> ColorResult<Vector> {
    if !xy.iter().all(|v| v.is_finite()) || xy[0] <= 0. || xy[1] <= 0. || xy[0] + xy[1] >= 1. { return invalid(); }
    Ok([xy[0] / xy[1], 1., (1. - xy[0] - xy[1]) / xy[1]])
}
fn mix(a: Matrix, d: Matrix, weight: f64) -> Matrix {
    if weight <= 0. { return a; }
    if weight >= 1. { return d; }
    std::array::from_fn(|r| std::array::from_fn(|c| (1. - weight) * a[r][c] + weight * d[r][c]))
}
fn prepare(cal: Calibration, signatures: bool) -> ColorResult<(Matrix, Matrix)> {
    if !cal.camera_calibration.iter().flatten().all(|v| v.is_finite()) || !cal.color_matrix.iter().flatten().all(|v| v.is_finite()) { return invalid(); }
    let p = peak(apply(cal.color_matrix, d50_xyz()));
    if !p.is_finite() || p <= 0. { return invalid(); }
    let scale = if (0.99..=1.01).contains(&p) { 1. } else { p };
    let cm = cal.color_matrix.map(|row| row.map(|x| round(x / scale, 1e4)));
    let cc = if signatures { cal.camera_calibration.map(|row| row.map(|x| round(x, 1e4))) } else { diagonal([1.;3]) };
    inverse(cc)?; inverse(cm)?;
    Ok((cm,cc))
}
/// Linear Bradford adaptation, with bounded cone gains for reference-compatible
/// DNG normalization. Out-of-envelope non-positive cone responses fail closed.
fn d50_to_white(white: [f64; 2]) -> ColorResult<Matrix> {
    let bradford = [[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]];
    let from = apply(bradford, d50_xyz());
    let to = apply(bradford, xyz(white)?);
    if !positive(from) || !positive(to) { return invalid(); }
    let gains = std::array::from_fn(|i| (to[i] / from[i]).clamp(0.1, 10.));
    Ok(product(product(inverse(bradford)?, diagonal(gains)), bradford))
}
/// Resolve once per image; applying the resulting matrix requires no iteration.
pub fn resolve(input: &Input) -> ColorResult<Transform> { resolve_internal(input,None) }
/// AsShotWhiteXY bypasses iterative neutral solving but uses the same matrices.
pub fn resolve_white_xy(input:&Input, xy:[f64;2])->ColorResult<Transform> {
    xyz(xy)?; resolve_internal(input,Some(xy))
}
fn resolve_internal(input: &Input, supplied_xy:Option<[f64;2]>) -> ColorResult<Transform> {
    if !positive(input.analog_balance) || !positive(input.as_shot_neutral) { return invalid(); }
    let [one, two] = input.calibrations;
    let t1=crate::dng_color_spec::illuminant_kelvin(one.illuminant)?;
    let t2=crate::dng_color_spec::illuminant_kelvin(two.illuminant)?;
    let (a,d,low,high)=if t1<=t2 {(one,two,t1,t2)}else{(two,one,t2,t1)};
    let ab_min = input.analog_balance.into_iter().fold(f64::INFINITY, f64::min);
    let neutral_max = peak(input.as_shot_neutral);
    let ab = input.analog_balance.map(|x| round(x / ab_min, 1e6));
    let neutral = input.as_shot_neutral.map(|x| {let n=x/neutral_max;let rounded=round(n,1e6);if rounded>0. {rounded}else{n}});
    if !positive(ab) || !positive(neutral) { return invalid(); }
    let (a, acc) = prepare(a, input.calibration_signatures_match)?;
    let (d, dcc) = prepare(d, input.calibration_signatures_match)?;
    let xyz_to_camera = |w| product(product(diagonal(ab), mix(acc,dcc,w)), mix(a,d,w));
    let weight = |xy| -> ColorResult<f64> {
        let t = robertson_from_table(xy, true)?;
        crate::dng_color_spec::calibration_weight(low,high,t)
    };
    let mut white_xy = supplied_xy.unwrap_or(D50_XY);
    let mut iterations = 0;
    let mut converged = supplied_xy.is_some();
    for count in 1..=if supplied_xy.is_some(){0}else{30} {
        let candidate = apply(inverse(xyz_to_camera(weight(white_xy)?))?, neutral);
        let sum = candidate.iter().sum::<f64>();
        if !sum.is_finite() || sum <= 0. { return invalid(); }
        let next = [candidate[0] / sum, candidate[1] / sum];
        xyz(next)?;
        let change = (next[0] - white_xy[0]).abs() + (next[1] - white_xy[1]).abs();
        white_xy = next;
        iterations = count;
        if change < 1e-7 { converged = true; break; }
    }
    // No silent approximate white point on oscillation/nonconvergence.
    if !converged { return Err(ColorError::NonConvergent.located()); }
    let temperature_kelvin = robertson_from_table(white_xy, true)?;
    let d50_weight = weight(white_xy)?;
    let cm = xyz_to_camera(d50_weight);
    let white = apply(cm, xyz(white_xy)?);
    if !positive(white) { return invalid(); }
    let camera_white = white.map(|x| (x / peak(white)).clamp(0.001, 1.));
    let pcs_to_camera = product(cm, d50_to_white(white_xy)?);
    let scale = peak(apply(pcs_to_camera, d50_xyz()));
    if !scale.is_finite() || scale <= 0. { return invalid(); }
    let camera_to_pcs = inverse(pcs_to_camera.map(|row| row.map(|x| x / scale)))?;
    Ok(Transform { camera_to_pcs, camera_white, white_xy, temperature_kelvin, d50_weight, iterations })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn actual_q2() -> Input {
        Input { calibrations: [
            Calibration { illuminant: 17, color_matrix: [[1.2587,-0.5232,-0.1496],[-0.3610,1.0841,0.0277],[-0.0911,0.1674,0.2070]], camera_calibration: diagonal([1.;3]) },
            Calibration { illuminant: 23, color_matrix: [[0.8389,-0.3198,-0.1019],[-0.3834,1.0222,0.0661],[-0.1122,0.1900,0.3415]], camera_calibration: diagonal([1.;3]) },
        ], analog_balance: [1.;3], as_shot_neutral: [1024./2090.,1.,1024./1524.], calibration_signatures_match: true }
    }
    #[test]
    fn actual_q2_sdk2768_metadata_oracle() {
        // Independent existing SDK executable on unmodified 3204-Q2.DNG;
        // evidence: q2-diagnostic/color-sdk2768.json. Not a rendered-image test.
        let t = resolve(&actual_q2()).unwrap();
        let expected = [[1.1018499978074343,0.34157230632756858,0.1233335118917408],[0.41134909838443856,0.85936683157660554,-0.09064845163421921],[0.082470578885470228,-0.2445363523091782,1.5317901787627384]];
        for r in 0..3 { for c in 0..3 { assert!((t.camera_to_pcs[r][c]-expected[r][c]).abs()<1e-12, "{r},{c}: {t:?}"); } }
        assert!((t.white_xy[0]-0.30267332132376035).abs()<1e-12);
        assert!((t.white_xy[1]-0.31636260563728646).abs()<1e-12);
        assert_eq!(t.d50_weight,1.);
        assert_eq!(t.iterations,2);
        for (actual, expected) in t.camera_white.into_iter().zip([0.489952,1.,0.671916]) { assert!((actual-expected).abs()<1e-12); }
        let mapped = apply(t.camera_to_pcs,t.camera_white);
        for i in 0..3 { assert!((mapped[i]-d50_xyz()[i]).abs()<1e-12); }
    }
    #[test]
    fn synthetic_warm_and_a_endpoint_sdk2768_oracles() {
        // Explicitly synthetic neutral inputs, keeping the actual Q2 profile.
        // Independent SDK outputs retained in color-sdk2768-synthetic-*.json.
        for (neutral,expected,xy) in [
([0.8, 1.0, 0.2],[[0.7751414260291782, 0.004369338594554235, 1.699065953503626], [0.2568064517631023, 0.7102306654642006, 0.4216208403999533], [0.47438449753002015, -1.7534258550686337, 10.995115319373795]],[0.43572754525380053, 0.4551758517780521]),
([1.2, 1.0, 0.2],[[0.6906444031429918, 0.017285172771648066, 1.5554786994741614], [0.2001786102793851, 0.8839024200985608, 0.3794172468012322], [0.3505831251056397, -1.4717996191252696, 10.206103604248824]],[0.45252101672244693, 0.40548233098783154])] {
            let mut input=actual_q2();input.as_shot_neutral=neutral;
            let t=resolve(&input).unwrap();
            for r in 0..3 {for c in 0..3 {assert!((t.camera_to_pcs[r][c]-expected[r][c]).abs()<1e-12);}}
            for i in 0..2 {assert!((t.white_xy[i]-xy[i]).abs()<1e-12);}
        }
        let mut input=actual_q2();input.as_shot_neutral=[0.8,1.,0.4];
        assert_eq!(resolve(&input).unwrap_err(),ColorError::NonConvergent);
    }
    #[test]
    fn order_signature_and_invalid_metadata() {
        let mut i=actual_q2(); let expected=resolve(&i).unwrap().camera_to_pcs;
        i.calibrations.swap(0,1); assert_eq!(resolve(&i).unwrap().camera_to_pcs,expected);
        i.calibrations[0].camera_calibration=diagonal([2.,1.,3.]);
        i.calibration_signatures_match=false;
        assert_eq!(resolve(&i).unwrap().camera_to_pcs,expected);
        i.calibration_signatures_match=true;
        assert_ne!(resolve(&i).unwrap().camera_to_pcs,expected);
        i.calibrations[0].illuminant=255;
        assert_eq!(resolve(&i).unwrap_err(),ColorError::UnsupportedCalibration);
        let mut i=actual_q2(); i.as_shot_neutral[0]=f64::NAN; assert!(resolve(&i).is_err());
        let mut i=actual_q2(); i.calibrations[0].color_matrix=[[0.;3];3]; assert!(resolve(&i).is_err());
        let mut i=actual_q2(); i.analog_balance[0]=0.; assert!(resolve(&i).is_err());
    }
}

/// Structurally select the bounded CFA A/D50 route. `None` leaves other routes
/// untouched. Once selected, incompatible or malformed metadata is an error.
/// The TIFF reader must retain ReductionMatrix1/2 (50725/50726), even if only
/// as descriptors, so their presence cannot be silently ignored.
pub fn parse(root: &crate::tiff::Ifd, raw: &crate::tiff::Ifd) -> crate::error::Result<Option<Transform>> {
    // Preserve the established route by default. Expanded color support is
    // selected explicitly by the caller when legacy processing is inadequate.
    let get=|tag|raw.get(&tag).or_else(||root.get(&tag));
    let light=|tag|get(tag).and_then(|v|v.uints().ok()).and_then(|v|v.first().copied());
    if !matches!((light(50778),light(50779)),(Some(17),Some(23))|(Some(23),Some(17))){return Ok(None);}
    if crate::tiff::uint(raw,262,Some(0))?!=32803{return Ok(None);}
    if get(50964).is_some()||get(50965).is_some(){return Ok(None);}
    if get(52525).is_some(){return Err(crate::error::err("UNSUPPORTED_DNG_CALIBRATION","probe"));}
    parse_complete(root,raw)
}
pub(crate) fn parse_complete(root: &crate::tiff::Ifd, raw: &crate::tiff::Ifd) -> crate::error::Result<Option<Transform>> {
    use crate::{error::{err, Reason}, tiff::Value};
    #[track_caller]
    fn bad() -> crate::error::CodecError { err("INVALID_COLOR_METADATA", "probe") }
    #[track_caller]
    fn unsupported() -> crate::error::CodecError { err("UNSUPPORTED_DNG_CALIBRATION", "probe") }
    let get = |tag| raw.get(&tag).or_else(|| root.get(&tag));
    let illuminant = |tag| -> crate::error::Result<Option<u16>> {
        let Some(v) = get(tag) else { return Ok(None); };
        let values = v.uints().map_err(|e| e.detail(Reason::TagValue, Some(tag)))?;
        if values.len()!=1 { return Ok(None); }
        Ok(values[0].try_into().ok())
    };
    let pair = (illuminant(50778)?, illuminant(50779)?);
    let single=get(50722).is_none() && get(50779).is_none();
    // A half-present second calibration is not a single-illuminant profile.
    if get(50722).is_some()!=get(50779).is_some() {return Err(bad().detail(Reason::MissingTag,Some(if get(50722).is_none(){50722}else{50779})));}
    let Some(first)=pair.0 else {return Ok(None)};
    if crate::dng_color_spec::illuminant_kelvin(first).is_err() || (!single && pair.1.is_none_or(|v|crate::dng_color_spec::illuminant_kelvin(v).is_err())) {return Ok(None);}
    if [50778,50779].iter().any(|&tag|(!single||tag==50778)&&get(tag).is_none_or(|v|v.typ!=3||v.count!=1)) {return Err(bad());}
    if get(50964).is_some()||get(50965).is_some(){return Ok(None);}
    if single && get(50724).is_some(){return Err(unsupported().detail(Reason::TagValue,Some(50724)));}
    // Forward/reduction matrices, third illuminant and unsupported profile
    // transforms are never silently omitted from this ColorMatrix-only route.
    if [50725,50726,50964,50965,50937,50938,50939,
        50981,50982,50983,52529,52530,52531,52532,52544,52547]
        .iter().any(|&tag| get(tag).is_some()) { return Err(unsupported()); }
    let matrix = |tag| -> crate::error::Result<Matrix> {
        let v = get(tag).ok_or_else(|| bad().detail(Reason::MissingTag, Some(tag)))?;
        if v.typ != 10 || v.count != 9 { return Err(bad().detail(Reason::MatrixShape, Some(tag))); }
        let a = v.nums().map_err(|e| e.detail(Reason::TagValue, Some(tag)))?;
        Ok(std::array::from_fn(|r| std::array::from_fn(|c| a[r*3+c])))
    };
    let camera_cc = |tag| -> crate::error::Result<Matrix> {
        if get(tag).is_none() { return Ok(diagonal([1.;3])); }
        matrix(tag)
    };
    let vector = |tag, default: Option<Vector>| -> crate::error::Result<Vector> {
        let Some(v) = get(tag) else { return default.ok_or_else(|| bad().detail(Reason::MissingTag, Some(tag))); };
        if v.typ != 5 || v.count != 3 { return Err(bad().detail(Reason::TagShape, Some(tag))); }
        v.nums().map_err(|e| e.detail(Reason::TagValue, Some(tag)))?.try_into().map_err(|_| bad())
    };
    // Signatures are IFD0 fields. Do not guess when incorrectly placed in a
    // distinct raw IFD, and compare their actual bytes rather than display text.
    if !std::ptr::eq(root,raw) && [50931,50932].iter().any(|tag| raw.contains_key(tag)) { return Err(unsupported()); }
    let signature = |tag| -> crate::error::Result<&[u8]> {
        match root.get(&tag) {
            None => Ok(&[]),
            Some(Value{typ,bytes,..}) => {
                if !matches!(typ,1|2) || bytes.len()>1024 || bytes.last()!=Some(&0) { return Err(bad()); }
                Ok(bytes.strip_suffix(&[0]).unwrap())
            }
        }
    };
    let (neutral, white_xy)=crate::profile_develop::white_balance(root,raw)?;
    let input = Input {
        calibrations: [
            Calibration { illuminant: pair.0.unwrap(), color_matrix: matrix(50721)?, camera_calibration: camera_cc(50723)? },
            Calibration { illuminant: pair.1.unwrap_or(first), color_matrix: matrix(if single{50721}else{50722})?, camera_calibration: camera_cc(if single{50723}else{50724})? },
        ],
        analog_balance: vector(50727,Some([1.;3]))?,
        as_shot_neutral: neutral,
        calibration_signatures_match: signature(50931)? == signature(50932)?,
    };
    match white_xy {Some(xy)=>resolve_white_xy(&input,xy),None=>resolve(&input)}.map(Some).map_err(|e| e.into_codec())
}

/// The render path only needs the resolved camera-to-PCS matrix. Keep the
/// calibration policy explicit rather than inventing A/D50 fields for A/D65.
#[derive(Clone, Debug)]
pub struct DevelopmentTransform {
    pub camera_to_pcs: Matrix,
    pub policy: &'static str,
}

pub fn parse_development(root: &crate::tiff::Ifd, raw: &crate::tiff::Ifd)
    -> crate::error::Result<Option<DevelopmentTransform>> {
    use crate::{error::{err, Reason}, profile_develop::{self, ColorRoute}};
    if let Some(t) = parse(root, raw)? {
        return Ok(Some(DevelopmentTransform { camera_to_pcs: t.camera_to_pcs,
            policy: "DNG_STD_V4; DNG A/D50 ColorMatrix-only calibration; required CFA/lens operations; linear sRGB, no camera tone curve" }));
    }
    let get = |tag| raw.get(&tag).or_else(|| root.get(&tag));
    let values = if let Some(ab) = get(50727) {
        if ab.typ != 5 || ab.count != 3 {
            return Err(err("INVALID_COLOR_METADATA", "probe").detail(Reason::TagShape, Some(50727)));
        }
        ab.nums().map_err(|e| e.detail(Reason::TagValue, Some(50727)))?
    } else { vec![1.;3] };
    if values.iter().any(|x| !x.is_finite() || *x <= 0.) {
        return Err(err("INVALID_COLOR_METADATA", "probe").detail(Reason::TagValue, Some(50727)));
    }
    // Preserve established unit-AB/diagonal-CC development. General channel
    // mixing requires the complete transform even when AnalogBalance is unit.
    let mixing = [50723,50724].into_iter().try_fold(false, |found,tag| -> crate::error::Result<bool> {
        let Some(v)=get(tag) else {return Ok(found)};
        let m=v.nums()?;
        if v.typ!=10 || m.len()!=9 {return Err(err("INVALID_COLOR_METADATA","probe").detail(Reason::MatrixShape,Some(tag)));}
        Ok(found || m.iter().enumerate().any(|(i,x)|i%4!=0 && x.abs()>1e-8))
    })?;
    let lights=[50778,50779].map(|tag|get(tag).and_then(|v|v.uints().ok()).and_then(|v|v.first().copied()));
    let legacy_d65=lights.contains(&Some(21));
    let neutral_needs_full=get(50728).map(|v|v.nums()).transpose()?.is_some_and(|n|n.iter().any(|x|*x>100.||*x<0.01));
    if legacy_d65 && values.iter().all(|x| (*x - 1.).abs() <= 1e-8) && !mixing && get(50729).is_none() && !neutral_needs_full { return Ok(None); }
    // This bounded path does not claim support for extra illuminants,
    // reduction matrices, or spatial profile gain tables on Bayer data.
    if [50725,50726,52525,52529,52530,52531,52532,52544,52547,52551]
        .iter().any(|&tag| get(tag).is_some()) {
        return Err(err("UNSUPPORTED_DNG_CALIBRATION", "probe"));
    }
    // A supported color route requires at least its first matrix/illuminant.
    if let Some(tag)=[50721,50778].into_iter().find(|&tag|get(tag).is_none()) {
        return Err(err("UNSUPPORTED_DNG_CALIBRATION","probe").detail(Reason::MissingTag,Some(tag)));
    }
    // Reuse the standard ColorMatrix/ForwardMatrix, AnalogBalance,
    // CameraCalibration and signature semantics already used by LinearRaw.
    // Demosaicing remains in camera space, followed by this matrix exactly once.
    let p = profile_develop::parse(root, raw, ColorRoute::NewSof3LinearPrecision)?
        .ok_or_else(|| err("UNSUPPORTED_DNG_CALIBRATION", "probe"))?;
    Ok(Some(DevelopmentTransform { camera_to_pcs: p.camera_to_pcs,
        policy: "DNG_STD_V4; standard single/dual illuminant CM/FM calibration with AnalogBalance; required CFA/lens operations; linear sRGB, no camera tone curve" }))
}

#[cfg(test)]
mod parser_tests {
    use super::*;
    use crate::tiff::{Ifd,Value,Endian};
    fn short(n:u16)->Value { Value{typ:3,count:1,bytes:n.to_le_bytes().to_vec(),endian:Endian::Le} }
    fn rational(a:&[i32],denominator:i32,signed:bool)->Value {
        let mut bytes=Vec::new();
        for &n in a { bytes.extend(n.to_le_bytes()); bytes.extend(denominator.to_le_bytes()); }
        Value{typ:if signed{10}else{5},count:a.len() as u32,bytes,endian:Endian::Le}
    }
    fn fixture()->Ifd {
        let mut i=Ifd::new();
        i.insert(262,short(32803));i.insert(50778,short(17));i.insert(50779,short(23));
        i.insert(50721,rational(&[12587,-5232,-1496,-3610,10841,277,-911,1674,2070],10000,true));
        i.insert(50722,rational(&[8389,-3198,-1019,-3834,10222,661,-1122,1900,3415],10000,true));
        i.insert(50728,rational(&[489952,1000000,671916],1000000,false));
        i
    }
    #[test] fn single_and_standard_pair_cm_parser(){
        let mut i=fixture();i.remove(&50722);i.remove(&50779);
        let single=parse_complete(&i,&i).unwrap().unwrap();assert_eq!(single.d50_weight,0.);
        i.insert(50722,i[&50721].clone());i.insert(50779,short(17));
        assert_eq!(parse_complete(&i,&i).unwrap().unwrap().camera_to_pcs,single.camera_to_pcs);
        for a in [17,20,21,22,23] {for b in [17,20,21,22,23] {
          i.insert(50778,short(a));i.insert(50779,short(b));
          let t=parse_complete(&i,&i).unwrap().unwrap();let white=crate::dng_color_spec::apply(t.camera_to_pcs,t.camera_white);
          for c in 0..3 {assert!((white[c]-crate::dng_color_spec::d50_xyz()[c]).abs()<1e-8);}
        }}
        i.remove(&50722);assert!(parse_complete(&i,&i).is_err());
    }
    #[test]
    fn parse_selects_metadata_not_camera_name() {
        let i=fixture(); let result=parse(&i,&i).unwrap().unwrap();
        assert!((result.white_xy[0]-0.30267332132376035).abs()<1e-12);
        let mut raw=Ifd::new(); raw.insert(262,short(32803));
        assert_eq!(parse(&i,&raw).unwrap().unwrap().camera_to_pcs,result.camera_to_pcs);
        let mut i=fixture();i.insert(50779,short(255));assert!(parse(&i,&i).unwrap().is_none());
        let mut i=fixture();i.insert(262,short(34892));assert!(parse(&i,&i).unwrap().is_none());
    }
    #[test]
    fn parse_white_xy_and_full_cc_are_structural_not_brand_specific() {
        let mut i=fixture();i.remove(&50728);
        i.insert(50729,rational(&[3457,3585],10000,false));
        i.insert(50723,rational(&[100,5,0,0,100,5,0,0,100],100,true));
        i.insert(50724,rational(&[100,10,0,0,100,10,0,0,100],100,true));
        let t=parse(&i,&i).unwrap().unwrap();
        assert_eq!(t.iterations,0);assert_eq!(t.white_xy,D50_XY);
        let mapped=apply(t.camera_to_pcs,t.camera_white);
        for c in 0..3 {assert!((mapped[c]-d50_xyz()[c]).abs()<1e-10);}
        i.insert(50728,rational(&[1,1,1],1,false));
        assert!(parse(&i,&i).is_err());
    }
    #[test]
    fn parse_does_not_drop_unsupported_or_malformed_metadata() {
        for tag in [50725,50726,52529,52531,52525,50938] {
            let mut i=fixture();i.insert(tag,short(1));assert!(parse(&i,&i).is_err(),"tag {tag}");
        }
        for tag in [50964,50965] {
            let mut i=fixture();i.insert(tag,short(1));
            assert!(parse(&i,&i).unwrap().is_none());
            assert!(parse_development(&i,&i).is_err());
        }
        for tag in [50721,50722,50728] {
            let mut i=fixture();i.remove(&tag);assert!(parse(&i,&i).is_err(),"missing {tag}");
        }
        let mut i=fixture();i.insert(50723,rational(&[1,0,0,0,0,0,0,0,1],1,true));assert!(parse(&i,&i).is_err());
        let mut i=fixture();i.get_mut(&50721).unwrap().typ=5;assert!(parse(&i,&i).is_err());
        let mut i=fixture();i.get_mut(&50728).unwrap().bytes.clear();assert!(parse(&i,&i).is_err());
        let mut i=fixture();i.insert(50931,Value{typ:2,count:3,bytes:b"bad".to_vec(),endian:Endian::Le});assert!(parse(&i,&i).is_err());
    }
}

#[cfg(test)]
mod analog_development_tests {
    use super::*;
    use crate::tiff::{Ifd, Value, Endian};
    fn rational(values: &[i32], denominator: i32, signed: bool) -> Value {
        let bytes = values.iter().flat_map(|v| v.to_le_bytes().into_iter().chain(denominator.to_le_bytes())).collect();
        Value { typ: if signed {10} else {5}, count: values.len() as u32, bytes, endian: Endian::Le }
    }
    fn short(v:u16) -> Value { Value {typ:3,count:1,bytes:v.to_le_bytes().to_vec(),endian:Endian::Le} }
    fn fixture() -> Ifd {
        let mut i = Ifd::new();
        i.insert(262,short(32803)); i.insert(50778,short(21)); i.insert(50779,short(17));
        i.insert(50721,rational(&[134,-47,-12,-12,123,15,8,22,49],128,true));
        i.insert(50722,rational(&[196,-108,12,-26,135,60,5,11,107],128,true));
        for t in [50723,50724] { i.insert(t,rational(&[101,0,0,0,128,0,0,0,134],128,true)); }
        for t in [50964,50965] { i.insert(t,rational(&[70,53,0,10,148,-30,-11,-52,169],128,true)); }
        i.insert(50727,rational(&[244976,100000,149271],100000,false));
        i.insert(50728,rational(&[1,1,1],1,false)); i
    }
    #[test]
    fn phone_forward_route_resolves_neutral_and_preserves_legacy_unit_route() {
        let mut i=fixture();
        let t=parse_development(&i,&i).unwrap().unwrap();
        let white=apply(t.camera_to_pcs,[1.;3]);
        for c in 0..3 { assert!((white[c]-d50_xyz()[c]).abs()<1e-7); }
        // Independent numerical calculation: equal FM endpoints and a unit
        // neutral reduce to row-normalized FM, regardless of AB/CC. Golden
        // numbers computed separately with NumPy from the rational metadata.
        let expected=[[0.5487755519660047,0.4155201244635629,0.0],
            [0.0781,1.1563,-0.2344],[-0.08557894875108493,-0.4046818275051303,1.3153653787666755]];
        for r in 0..3 { for c in 0..3 { assert!((t.camera_to_pcs[r][c]-expected[r][c]).abs()<1e-6); } }
        assert!(t.policy.contains("AnalogBalance"));
        i.insert(50727,rational(&[1,1,1],1,false));
        assert!(parse_development(&i,&i).unwrap().is_none());
        i.remove(&50727); assert!(parse_development(&i,&i).unwrap().is_none());
    }
    #[test]
    fn analog_changes_forward_interpolation_not_stored_pixel_gain() {
        let mut i=fixture();
        // Different (valid) warm FM makes white point/CCT interpolation visible.
        i.insert(50965,rational(&[502,211,275,68,841,116,-49,-883,1777],1024,true));
        let original=parse_development(&i,&i).unwrap().unwrap();
        i.insert(50727,rational(&[180000,100000,120000],100000,false));
        let other=parse_development(&i,&i).unwrap().unwrap();
        let diff=original.camera_to_pcs.iter().flatten().zip(other.camera_to_pcs.iter().flatten())
            .map(|(a,b)|(a-b).abs()).fold(0f64,f64::max);
        assert!(diff>1e-4,"AnalogBalance must affect interpolated calibration: {diff}");
        for t in [original,other] { let white=apply(t.camera_to_pcs,[1.;3]);
            for c in 0..3 { assert!((white[c]-d50_xyz()[c]).abs()<1e-7); }
        }
    }
    #[test]
    fn invalid_or_unsupported_calibration_is_not_silently_ignored() {
        for tag in [50964,50965,50721,50722,50728] {
            let mut i=fixture();i.remove(&tag);assert!(parse_development(&i,&i).is_err(),"missing {tag}");
        }
        for values in [[0,1,1]] {
            let mut i=fixture();i.insert(50727,rational(&values,1,false));assert!(parse_development(&i,&i).is_err());
        }
        for tag in [50725,50726,52525,50938,52551] {
            let mut i=fixture();i.insert(tag,short(1));assert!(parse_development(&i,&i).is_err(),"unsupported {tag}");
        }
        let mut i=fixture();i.get_mut(&50727).unwrap().typ=10;assert!(parse_development(&i,&i).is_err());
        let mut i=fixture();i.insert(50779,short(255));assert!(parse_development(&i,&i).is_err());
    }
}

#[cfg(test)]
mod full_color_tests {
 use super::*;
 #[test]
 fn explicit_d50_has_no_iterative_white_balance_and_identity_transform() {
  let id=diagonal([1.;3]);
  let a=Calibration{illuminant:17,color_matrix:id,camera_calibration:id};
  let i=Input{calibrations:[a,Calibration{illuminant:23,..a}],analog_balance:[1.;3],as_shot_neutral:[1.;3],calibration_signatures_match:true};
  let t=resolve_white_xy(&i,D50_XY).unwrap();
  assert_eq!(t.iterations,0);
  for r in 0..3 {for c in 0..3 {assert!((t.camera_to_pcs[r][c]-id[r][c]).abs()<1e-12);}}
 }
 #[test]
 fn full_camera_matrix_is_not_silently_diagonalized() {
  let cc=[[1.,0.1,0.],[0.,1.,0.2],[0.,0.,1.]];
  let a=Calibration{illuminant:17,color_matrix:diagonal([1.;3]),camera_calibration:cc};
  let i=Input{calibrations:[a,Calibration{illuminant:23,..a}],analog_balance:[1.;3],as_shot_neutral:[1.;3],calibration_signatures_match:true};
  let t=resolve_white_xy(&i,D50_XY).unwrap();
  // With D50 adaptation identity, only normalization and inverse CC remain.
  let white=apply(cc,d50_xyz());let scale=peak(white);
  let expected=[[1.,-0.1,0.02],[0.,1.,-0.2],[0.,0.,1.]];
  for r in 0..3 {for c in 0..3 {assert!((t.camera_to_pcs[r][c]-scale*expected[r][c]).abs()<1e-12);}}
 }
}
