//! Bounded three-channel DNG ColorMatrix-only calibration to XYZ D50.
//! Independently implemented from the DNG calibration equations. Adobe SDK is
//! an external numerical test oracle only; no SDK source or runtime is included.
//! This product includes DNG technology under license by Adobe.
//!
//! Supported envelope: A/D50 dual illuminants, positive diagonal camera
//! calibration, positive analog balance and AsShotNeutral. The caller must
//! reject ForwardMatrix, ReductionMatrix, a third calibration, non-diagonal
//! camera calibration and incompatible profile operations before selecting it.
//! This module does not demosaic, correct bad pixels, warp or render a profile.
use crate::dng_color_spec::{apply, d50_xyz, inverse, product, robertson_from_table,
    ColorError, ColorResult, Matrix, Vector, D50_XY};

#[derive(Clone, Copy, Debug)]
pub struct Calibration {
    /// TIFF LightSource 17=A or 23=D50; input pair order is immaterial.
    pub illuminant: u16,
    pub color_matrix: Matrix,
    /// Caller validates the source matrix is exactly diagonal.
    pub camera_calibration: Vector,
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
fn invalid<T>() -> ColorResult<T> { Err(ColorError::InvalidMetadata) }
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
fn prepare(cal: Calibration, ab: Vector, signatures: bool) -> ColorResult<Matrix> {
    if !positive(cal.camera_calibration) || !cal.color_matrix.iter().flatten().all(|v| v.is_finite()) { return invalid(); }
    let p = peak(apply(cal.color_matrix, d50_xyz()));
    if !p.is_finite() || p <= 0. { return invalid(); }
    let scale = if (0.99..=1.01).contains(&p) { 1. } else { p };
    let cm = cal.color_matrix.map(|row| row.map(|x| round(x / scale, 1e4)));
    let cc = if signatures { cal.camera_calibration.map(|x| round(x, 1e4)) } else { [1.; 3] };
    if !positive(cc) { return invalid(); }
    let result = product(diagonal(std::array::from_fn(|i| ab[i] * cc[i])), cm);
    inverse(result)?;
    Ok(result)
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
pub fn resolve(input: &Input) -> ColorResult<Transform> {
    if !positive(input.analog_balance) || !positive(input.as_shot_neutral) { return invalid(); }
    let [one, two] = input.calibrations;
    let (a, d) = match (one.illuminant, two.illuminant) {
        (17, 23) => (one, two), (23, 17) => (two, one),
        _ => return Err(ColorError::UnsupportedCalibration),
    };
    let ab_min = input.analog_balance.into_iter().fold(f64::INFINITY, f64::min);
    let neutral_max = peak(input.as_shot_neutral);
    let ab = input.analog_balance.map(|x| round(x / ab_min, 1e6));
    let neutral = input.as_shot_neutral.map(|x| round(x / neutral_max, 1e6));
    if !positive(ab) || !positive(neutral) { return invalid(); }
    let a = prepare(a, ab, input.calibration_signatures_match)?;
    let d = prepare(d, ab, input.calibration_signatures_match)?;
    let weight = |xy| -> ColorResult<f64> {
        let t = robertson_from_table(xy, true)?;
        Ok(((1. / 2850. - 1. / t) / (1. / 2850. - 1. / 5000.)).clamp(0., 1.))
    };
    let mut white_xy = D50_XY;
    let mut iterations = 0;
    let mut converged = false;
    for count in 1..=30 {
        let candidate = apply(inverse(mix(a, d, weight(white_xy)?))?, neutral);
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
    if !converged { return Err(ColorError::NonConvergent); }
    let temperature_kelvin = robertson_from_table(white_xy, true)?;
    let d50_weight = weight(white_xy)?;
    let cm = mix(a, d, d50_weight);
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
            Calibration { illuminant: 17, color_matrix: [[1.2587,-0.5232,-0.1496],[-0.3610,1.0841,0.0277],[-0.0911,0.1674,0.2070]], camera_calibration: [1.;3] },
            Calibration { illuminant: 23, color_matrix: [[0.8389,-0.3198,-0.1019],[-0.3834,1.0222,0.0661],[-0.1122,0.1900,0.3415]], camera_calibration: [1.;3] },
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
        i.calibrations[0].camera_calibration=[2.,1.,3.];
        i.calibration_signatures_match=false;
        assert_eq!(resolve(&i).unwrap().camera_to_pcs,expected);
        i.calibration_signatures_match=true;
        assert_ne!(resolve(&i).unwrap().camera_to_pcs,expected);
        i.calibrations[0].illuminant=21;
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
    use crate::{error::err, tiff::Value};
    let bad = || err("INVALID_COLOR_METADATA", "probe");
    let unsupported = || err("UNSUPPORTED_DNG_CALIBRATION", "probe");
    let get = |tag| raw.get(&tag).or_else(|| root.get(&tag));
    if crate::tiff::uint(raw, 262, Some(0))? != 32803 { return Ok(None); }
    let illuminant = |tag| -> crate::error::Result<Option<u16>> {
        let Some(v) = get(tag) else { return Ok(None); };
        let values = v.uints()?;
        if values.len()!=1 { return Ok(None); }
        Ok(values[0].try_into().ok())
    };
    let pair = (illuminant(50778)?, illuminant(50779)?);
    if !matches!(pair, (Some(17), Some(23)) | (Some(23), Some(17))) { return Ok(None); }
    if [50778,50779].iter().any(|&tag|get(tag).is_none_or(|v|v.typ!=3||v.count!=1)) {return Err(bad());}
    // Forward/reduction matrices, third illuminant and unsupported profile
    // transforms are never silently omitted from this ColorMatrix-only route.
    if [50725,50726,50729,50964,50965,50937,50938,50939,50940,50941,50942,
        50981,50982,50983,52525,52529,52530,52531,52532,52544,52547,52548,52549,52550]
        .iter().any(|&tag| get(tag).is_some()) { return Err(unsupported()); }
    let matrix = |tag| -> crate::error::Result<Matrix> {
        let v = get(tag).ok_or_else(bad)?;
        if v.typ != 10 || v.count != 9 { return Err(bad()); }
        let a = v.nums()?;
        Ok(std::array::from_fn(|r| std::array::from_fn(|c| a[r*3+c])))
    };
    let diagonal_cc = |tag| -> crate::error::Result<Vector> {
        if get(tag).is_none() { return Ok([1.;3]); }
        let m = matrix(tag)?;
        if m.iter().enumerate().any(|(r,row)| row.iter().enumerate().any(|(c,&x)| r != c && x != 0.)) { return Err(unsupported()); }
        Ok([m[0][0],m[1][1],m[2][2]])
    };
    let vector = |tag, default: Option<Vector>| -> crate::error::Result<Vector> {
        let Some(v) = get(tag) else { return default.ok_or_else(bad); };
        if v.typ != 5 || v.count != 3 { return Err(bad()); }
        v.nums()?.try_into().map_err(|_| bad())
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
    let input = Input {
        calibrations: [
            Calibration { illuminant: pair.0.unwrap(), color_matrix: matrix(50721)?, camera_calibration: diagonal_cc(50723)? },
            Calibration { illuminant: pair.1.unwrap(), color_matrix: matrix(50722)?, camera_calibration: diagonal_cc(50724)? },
        ],
        analog_balance: vector(50727,Some([1.;3]))?,
        as_shot_neutral: vector(50728,None)?,
        calibration_signatures_match: signature(50931)? == signature(50932)?,
    };
    resolve(&input).map(Some).map_err(|e| err(e.code(),"probe"))
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
    #[test]
    fn parse_selects_metadata_not_camera_name() {
        let i=fixture(); let result=parse(&i,&i).unwrap().unwrap();
        assert!((result.white_xy[0]-0.30267332132376035).abs()<1e-12);
        let mut raw=Ifd::new(); raw.insert(262,short(32803));
        assert_eq!(parse(&i,&raw).unwrap().unwrap().camera_to_pcs,result.camera_to_pcs);
        let mut i=fixture();i.insert(50779,short(21));assert!(parse(&i,&i).unwrap().is_none());
        let mut i=fixture();i.insert(262,short(34892));assert!(parse(&i,&i).unwrap().is_none());
    }
    #[test]
    fn parse_does_not_drop_unsupported_or_malformed_metadata() {
        for tag in [50725,50726,50729,50964,50965,52529,52531,52525,50938] {
            let mut i=fixture();i.insert(tag,short(1));assert!(parse(&i,&i).is_err(),"tag {tag}");
        }
        for tag in [50721,50722,50728] {
            let mut i=fixture();i.remove(&tag);assert!(parse(&i,&i).is_err(),"missing {tag}");
        }
        let mut i=fixture();i.insert(50723,rational(&[1,1,0,0,1,0,0,0,1],1,true));assert!(parse(&i,&i).is_err());
        let mut i=fixture();i.get_mut(&50721).unwrap().typ=5;assert!(parse(&i,&i).is_err());
        let mut i=fixture();i.get_mut(&50728).unwrap().bytes.clear();assert!(parse(&i,&i).is_err());
        let mut i=fixture();i.insert(50931,Value{typ:2,count:3,bytes:b"bad".to_vec(),endian:Endian::Le});assert!(parse(&i,&i).is_err());
    }
}
