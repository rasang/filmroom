//! Filmroom's bounded metadata handoff, not a renderer.
//!
//! The decoder already owns the validated TIFF/MakerNote directories. Retain
//! only the distortion fields needed by Filmroom; no CA or vignette processing
//! is implied by the presence of this metadata.
use crate::formats::tiff::Value;

#[derive(Clone, Debug, PartialEq, Default)]
pub enum EmbeddedLensDistortion {
  #[default]
  Missing,
  /// A distortion tag exists, but its shape/type or crop mode is unsupported.
  Unsupported,
  Sony {
    samples: Vec<i16>,
  },
  Fuji {
    knots: Vec<f64>,
    distortion: Vec<f64>,
    crop_factor: f64,
  },
  Olympus {
    coefficients: [f64; 4],
  },
}

impl EmbeddedLensDistortion {
  pub(crate) fn sony(value: Option<&Value>) -> Self {
    let Some(value) = value else { return Self::Missing };
    // The TIFF field is a signed-short count followed by at most 16 samples.
    // Some cameras store all 16 slots even when count is smaller (for example,
    // the ILCE-6700 uses 11 samples and repeats its last value in five slots).
    let Value::SShort(values) = value else { return Self::Unsupported };
    let Some(&count) = values.first() else { return Self::Unsupported };
    if !(2..=16).contains(&count) || (values.len() != count as usize + 1 && values.len() != 17) {
      return Self::Unsupported;
    }
    Self::Sony { samples: values[1..=count as usize].to_vec() }
  }

  pub(crate) fn fuji(value: Option<&Value>, crop_mode: Option<&Value>) -> Self {
    let Some(value) = value else { return Self::Missing };
    let count = value.count();
    // The established 9- and 11-knot layouts. Other RAF generations remain
    // distinguishable from missing metadata, rather than guessed into this model.
    let knots = match count {
      19 => 9,
      23 => 11,
      _ => return Self::Unsupported,
    };
    let Some(values) = finite_numbers(value, count) else { return Self::Unsupported };
    let crop_factor = match crop_mode {
      None => 1.0,
      Some(Value::Short(v)) if v.len() == 1 => match v[0] {
        0 => 1.0,
        2 | 4 => 1.25,
        // GFX full-frame and digital teleconverter use a different domain.
        // A new crop model requires evidence rather than assuming 1x.
        _ => return Self::Unsupported,
      },
      _ => return Self::Unsupported,
    };
    Self::Fuji {
      knots: values[1..=knots].to_vec(),
      distortion: values[knots + 1..].to_vec(),
      crop_factor,
    }
  }

  pub(crate) fn olympus(value: Option<&Value>) -> Self {
    let Some(value) = value else { return Self::Missing };
    let Some(values) = finite_numbers(value, 4) else { return Self::Unsupported };
    Self::Olympus { coefficients: [values[0], values[1], values[2], values[3]] }
  }
}

/// Read supported numeric TIFF encodings without force_* conversions, panics,
/// nonfinite values, division by zero, or allocation based on an unchecked count.
fn finite_numbers(value: &Value, expected: usize) -> Option<Vec<f64>> {
  if expected > 23 || value.count() != expected {
    return None;
  }
  let values: Vec<f64> = match value {
    Value::Rational(v) => v.iter().map(|r| {
      (r.d != 0).then(|| r.n as f64 / r.d as f64)
    }).collect::<Option<_>>()?,
    Value::SRational(v) => v.iter().map(|r| {
      (r.d != 0).then(|| r.n as f64 / r.d as f64)
    }).collect::<Option<_>>()?,
    Value::Float(v) => v.iter().map(|v| *v as f64).collect(),
    Value::Double(v) => v.clone(),
    _ => return None,
  };
  values.iter().all(|v| v.is_finite()).then_some(values)
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::formats::tiff::{Rational, SRational};

  #[test]
  fn sony_requires_the_complete_signed_counted_table() {
    assert_eq!(EmbeddedLensDistortion::sony(None), EmbeddedLensDistortion::Missing);
    assert_eq!(
      EmbeddedLensDistortion::sony(Some(&Value::SShort(vec![3, -100, 0, 100]))),
      EmbeddedLensDistortion::Sony { samples: vec![-100, 0, 100] }
    );
    let padded = Value::SShort(vec![11, 0, -29, -109, -245, -424, -640, -881, -1135,
      -1390, -1631, -1829, -1829, -1829, -1829, -1829, -1829]);
    assert_eq!(EmbeddedLensDistortion::sony(Some(&padded)), EmbeddedLensDistortion::Sony {
      samples: vec![0, -29, -109, -245, -424, -640, -881, -1135, -1390, -1631, -1829],
    });
    for value in [
      Value::SShort(vec![]), Value::SShort(vec![1, 0]),
      Value::SShort(vec![2, 1]), Value::SShort(vec![2, 1, 2, 3]),
      Value::SShort(vec![17; 18]), Value::Short(vec![2, 0, 0]),
    ] {
      assert_eq!(EmbeddedLensDistortion::sony(Some(&value)), EmbeddedLensDistortion::Unsupported);
    }
  }

  #[test]
  fn fuji_preserves_negative_rationals_and_supported_crop_modes() {
    let mut values = vec![SRational::new(0, 1)];
    values.extend((1..=9).map(|v| SRational::new(v, 10)));
    values.extend((0..9).map(|v| SRational::new(-v, 2)));
    let value = Value::SRational(values);
    let EmbeddedLensDistortion::Fuji { knots, distortion, crop_factor } =
      EmbeddedLensDistortion::fuji(Some(&value), Some(&Value::Short(vec![2]))) else {
        panic!("valid Fuji metadata must be retained");
      };
    assert_eq!(knots, vec![0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]);
    assert_eq!(distortion, vec![0.0, -0.5, -1.0, -1.5, -2.0, -2.5, -3.0, -3.5, -4.0]);
    assert_eq!(crop_factor, 1.25);
    for mode in [1, 3, 8, 65535] {
      assert_eq!(EmbeddedLensDistortion::fuji(Some(&value), Some(&Value::Short(vec![mode]))),
        EmbeddedLensDistortion::Unsupported);
    }
    assert_eq!(EmbeddedLensDistortion::fuji(None, None), EmbeddedLensDistortion::Missing);
    assert_eq!(EmbeddedLensDistortion::fuji(Some(&Value::Double(vec![0.; 35])), None),
      EmbeddedLensDistortion::Unsupported);
  }

  #[test]
  fn malformed_numeric_values_are_not_silently_missing_or_infinite() {
    let bad = [
      Value::Double(vec![0., 0., 0., f64::NAN]),
      Value::Float(vec![0., 0., 0., f32::INFINITY]),
      Value::Rational(vec![Rational::new(1, 0); 4]),
      Value::SRational(vec![SRational::new(1, 0); 4]),
      Value::Double(vec![0., 0., 1.]),
      Value::Long(vec![0, 0, 0, 1]),
    ];
    for value in bad {
      assert_eq!(EmbeddedLensDistortion::olympus(Some(&value)), EmbeddedLensDistortion::Unsupported);
    }
    assert_eq!(EmbeddedLensDistortion::olympus(Some(&Value::Double(vec![0.1, -0.02, 0.003, 0.95]))),
      EmbeddedLensDistortion::Olympus { coefficients: [0.1, -0.02, 0.003, 0.95] });
  }
}
