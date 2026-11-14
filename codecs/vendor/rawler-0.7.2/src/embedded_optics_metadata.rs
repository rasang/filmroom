//! Bounded handoff of optional manufacturer TCA and shading coefficients.
//!
//! These are numeric tables, not camera menu switches. Each correction keeps
//! its own availability, so a missing or malformed table cannot erase another
//! valid correction. Rendering and physical-domain checks belong to Filmroom.
use crate::formats::tiff::Value;
use crate::rawimage::EmbeddedLensDistortion;

#[derive(Clone, Debug, PartialEq, Default)]
pub enum EmbeddedOpticsState<T> {
  #[default]
  Missing,
  Unsupported,
  Ready(T),
}

#[derive(Clone, Debug, PartialEq, Default)]
pub struct EmbeddedLensCorrections {
  pub tca: EmbeddedOpticsState<EmbeddedTca>,
  pub vignette: EmbeddedOpticsState<EmbeddedVignette>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum EmbeddedTca {
  Sony {
    red: Vec<i16>,
    blue: Vec<i16>,
    /// The same source IFD's distortion table. Sony CA is specified at the
    /// pre-distortion radius; the renderer needs this to undo that radius map.
    distortion: Option<Vec<i16>>,
  },
  Fuji { knots: Vec<f64>, red: Vec<f64>, blue: Vec<f64>, crop_factor: f64 },
  Olympus { coefficients: [f64; 6] },
}

#[derive(Clone, Debug, PartialEq)]
pub enum EmbeddedVignette {
  Sony { samples: Vec<i16> },
  Fuji { knots: Vec<f64>, levels: Vec<f64>, crop_factor: f64 },
}

impl EmbeddedLensCorrections {
  pub(crate) fn sony(tca: Option<&Value>, vignette: Option<&Value>, distortion: &EmbeddedLensDistortion) -> Self {
    let tca = parse(tca, |value| {
      let values = signed_table(value, 32, true)?;
      let count = values.len() / 2;
      let distortion = match distortion {
        EmbeddedLensDistortion::Sony { samples } if (2..=16).contains(&samples.len()) => Some(samples.clone()),
        EmbeddedLensDistortion::Missing | EmbeddedLensDistortion::Unsupported => None,
        _ => return None,
      };
      Some(EmbeddedTca::Sony { red: values[..count].to_vec(), blue: values[count..].to_vec(), distortion })
    });
    let vignette = parse(vignette, |value| {
      Some(EmbeddedVignette::Sony { samples: signed_table(value, 16, false)?.to_vec() })
    });
    Self { tca, vignette }
  }

  pub(crate) fn fuji(tca: Option<&Value>, vignette: Option<&Value>, crop_mode: Option<&Value>, distortion: &EmbeddedLensDistortion) -> Self {
    let crop_factor = fuji_crop_factor(crop_mode);
    let tca = parse(tca, |value| {
      let crop_factor = crop_factor?;
      let numbers = finite_numbers(value, match value.count() { 29 => 29, 31 => 31, _ => return None })?;
      let (knots, red, blue) = if numbers.len() == 29 {
        // The last entry of this established layout is not a coefficient.
        (numbers[1..10].to_vec(), numbers[10..19].to_vec(), numbers[19..28].to_vec())
      } else {
        // The older layout omits the centre knot and its two zero CA values.
        let mut knots = vec![0.0];
        let mut red = vec![0.0];
        let mut blue = vec![0.0];
        knots.extend_from_slice(&numbers[1..11]);
        red.extend_from_slice(&numbers[11..21]);
        blue.extend_from_slice(&numbers[21..31]);
        (knots, red, blue)
      };
      if !fuji_same_domain(&knots, crop_factor, distortion) { return None; }
      Some(EmbeddedTca::Fuji { knots, red, blue, crop_factor })
    });
    let vignette = parse(vignette, |value| {
      let crop_factor = crop_factor?;
      let count = match value.count() { 19 => 9, 23 => 11, _ => return None };
      let numbers = finite_numbers(value, count * 2 + 1)?;
      let knots = numbers[1..=count].to_vec();
      if !fuji_same_domain(&knots, crop_factor, distortion) { return None; }
      Some(EmbeddedVignette::Fuji { knots, levels: numbers[count + 1..].to_vec(), crop_factor })
    });
    Self { tca, vignette }
  }

  pub(crate) fn olympus(tca: Option<&Value>) -> Self {
    let tca = parse(tca, |value| {
      let values = finite_numbers(value, 6)?;
      Some(EmbeddedTca::Olympus { coefficients: values.try_into().ok()? })
    });
    // The known Olympus shading switches are not gain coefficients. Shading
    // can already be applied to the RAW. Neither referenced implementation
    // supplies an embedded Olympus vignetting model.
    Self { tca, vignette: EmbeddedOpticsState::Missing }
  }
}

fn parse<T>(value: Option<&Value>, parser: impl FnOnce(&Value) -> Option<T>) -> EmbeddedOpticsState<T> {
  match value {
    None => EmbeddedOpticsState::Missing,
    Some(value) => parser(value).map(EmbeddedOpticsState::Ready).unwrap_or(EmbeddedOpticsState::Unsupported),
  }
}

fn signed_table(value: &Value, maximum: usize, paired: bool) -> Option<&[i16]> {
  let Value::SShort(values) = value else { return None };
  let count = usize::try_from(*values.first()?).ok()?;
  let minimum = if paired { 4 } else { 2 };
  if !(minimum..=maximum).contains(&count) || (paired && count % 2 != 0)
    || (values.len() != count + 1 && values.len() != maximum + 1) {
    return None;
  }
  Some(&values[1..=count])
}

fn fuji_crop_factor(value: Option<&Value>) -> Option<f64> {
  match value {
    None => Some(1.0),
    Some(Value::Short(v)) if v.len() == 1 => match v[0] { 0 => Some(1.0), 2 | 4 => Some(1.25), _ => None },
    _ => None,
  }
}

fn fuji_same_domain(knots: &[f64], crop_factor: f64, distortion: &EmbeddedLensDistortion) -> bool {
  match distortion {
    EmbeddedLensDistortion::Fuji { knots: reference, crop_factor: reference_crop, .. } => knots == reference && crop_factor == *reference_crop,
    EmbeddedLensDistortion::Missing | EmbeddedLensDistortion::Unsupported => true,
    _ => false,
  }
}

fn finite_numbers(value: &Value, expected: usize) -> Option<Vec<f64>> {
  if expected > 31 || value.count() != expected { return None; }
  let values: Vec<f64> = match value {
    Value::Rational(v) => v.iter().map(|r| (r.d != 0).then(|| r.n as f64 / r.d as f64)).collect::<Option<_>>()?,
    Value::SRational(v) => v.iter().map(|r| (r.d != 0).then(|| r.n as f64 / r.d as f64)).collect::<Option<_>>()?,
    Value::Float(v) => v.iter().map(|v| *v as f64).collect(),
    Value::Double(v) => v.clone(),
    _ => return None,
  };
  values.iter().all(|v| v.is_finite()).then_some(values)
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::formats::tiff::SRational;

  #[test]
  fn sony_tables_are_independent_bounded_and_counted() {
    let distortion = EmbeddedLensDistortion::Sony { samples: vec![0; 11] };
    let tca = Value::SShort([vec![22], vec![128; 22], vec![999; 10]].concat());
    let vignette = Value::SShort([vec![16], vec![0; 16]].concat());
    let result = EmbeddedLensCorrections::sony(Some(&tca), Some(&vignette), &distortion);
    let EmbeddedOpticsState::Ready(EmbeddedTca::Sony { red, blue, .. }) = result.tca else { panic!("valid TCA") };
    assert_eq!(red, vec![128; 11]);
    assert_eq!(blue, vec![128; 11]);
    assert!(matches!(result.vignette, EmbeddedOpticsState::Ready(_)));
    let bad = Value::SShort(vec![5; 6]);
    let result = EmbeddedLensCorrections::sony(Some(&bad), Some(&vignette), &distortion);
    assert_eq!(result.tca, EmbeddedOpticsState::Unsupported);
    assert!(matches!(result.vignette, EmbeddedOpticsState::Ready(_)));
    for bad in [Value::SShort(vec![]), Value::SShort(vec![-2, 0, 0]), Value::SShort(vec![2, 0]),
      Value::SShort(vec![34; 35]), Value::SShort(vec![4; 6]), Value::Short(vec![4; 5])] {
      assert_eq!(EmbeddedLensCorrections::sony(Some(&bad), None, &distortion).tca, EmbeddedOpticsState::Unsupported);
    }
  }

  fn fuji_old_ca() -> Value {
    let mut values = vec![SRational::new(0, 1)];
    values.extend((1..=10).map(|i| SRational::new(i, 10)));
    values.extend((1..=10).map(|i| SRational::new(-i, 10000)));
    values.extend((1..=10).map(|i| SRational::new(i, 10000)));
    Value::SRational(values)
  }

  #[test]
  fn fuji_old_layout_restores_only_the_defined_centre_node() {
    let value = fuji_old_ca();
    let result = EmbeddedLensCorrections::fuji(Some(&value), None, Some(&Value::Short(vec![2])), &EmbeddedLensDistortion::Missing);
    let EmbeddedOpticsState::Ready(EmbeddedTca::Fuji { knots, red, blue, crop_factor }) = result.tca else { panic!("valid old Fuji TCA") };
    assert_eq!(knots, (0..=10).map(|i| i as f64 / 10.0).collect::<Vec<_>>());
    assert_eq!(red[0], 0.0);
    assert_eq!(blue[0], 0.0);
    assert_eq!(red[10], -0.001);
    assert_eq!(blue[10], 0.001);
    assert_eq!(crop_factor, 1.25);
    assert_eq!(result.vignette, EmbeddedOpticsState::Missing);
    for crop in [1, 3, 8, 65535] {
      assert_eq!(EmbeddedLensCorrections::fuji(Some(&value), None, Some(&Value::Short(vec![crop])), &EmbeddedLensDistortion::Missing).tca, EmbeddedOpticsState::Unsupported);
    }
    let inconsistent = EmbeddedLensDistortion::Fuji { knots: vec![0.0; 11], distortion: vec![0.0; 11], crop_factor: 1.0 };
    assert_eq!(EmbeddedLensCorrections::fuji(Some(&value), None, None, &inconsistent).tca, EmbeddedOpticsState::Unsupported);
  }

  #[test]
  fn fuji_new_layout_and_vignette_do_not_require_each_other() {
    let knots: Vec<f64> = (1..=9).map(|i| i as f64 / 10.0).collect();
    let tca = Value::Double([vec![1.0], knots.clone(), vec![-0.001; 9], vec![0.002; 9], vec![0.0]].concat());
    let vignette = Value::Double([vec![1.0], knots.clone(), vec![80.0; 9]].concat());
    let result = EmbeddedLensCorrections::fuji(Some(&tca), Some(&vignette), None, &EmbeddedLensDistortion::Missing);
    assert!(matches!(result.tca, EmbeddedOpticsState::Ready(_)));
    assert!(matches!(result.vignette, EmbeddedOpticsState::Ready(_)));
    let bad = Value::Double(vec![0.0; 30]);
    let result = EmbeddedLensCorrections::fuji(Some(&bad), Some(&vignette), None, &EmbeddedLensDistortion::Missing);
    assert_eq!(result.tca, EmbeddedOpticsState::Unsupported);
    assert!(matches!(result.vignette, EmbeddedOpticsState::Ready(_)));
  }

  #[test]
  fn olympus_numeric_coefficients_are_not_shading_switches() {
    let good = Value::Float(vec![0.0001; 6]);
    let result = EmbeddedLensCorrections::olympus(Some(&good));
    assert!(matches!(result.tca, EmbeddedOpticsState::Ready(_)));
    assert_eq!(result.vignette, EmbeddedOpticsState::Missing);
    for bad in [Value::Byte(vec![1]), Value::Float(vec![0.0; 5]), Value::Float(vec![f32::NAN; 6]),
      Value::SRational(vec![SRational::new(1, 0); 6]), Value::Double(vec![f64::INFINITY; 6])] {
      assert_eq!(EmbeddedLensCorrections::olympus(Some(&bad)).tca, EmbeddedOpticsState::Unsupported);
    }
  }
}
