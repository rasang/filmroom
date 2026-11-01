//! Encoded SDR output contract. Inputs have ALREADY been converted by the
//! film engine into linear RGB of the selected primaries. Never relabel sRGB.
use crate::error::{Result, err};

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum OutputColorSpace {
    Srgb,
    DisplayP3,
    AdobeRgb,
}
impl OutputColorSpace {
    pub fn parse(name: &str) -> Result<Self> {
        match name {
            "sRGB" => Ok(Self::Srgb),
            "Display P3" => Ok(Self::DisplayP3),
            "Adobe RGB (1998)" => Ok(Self::AdobeRgb),
            _ => Err(err("UNSUPPORTED_COLOR_SPACE", "admission")),
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            Self::Srgb => "sRGB",
            Self::DisplayP3 => "Display P3",
            Self::AdobeRgb => "Adobe RGB (1998)",
        }
    }
    pub fn encode(self, value: f32) -> f32 {
        let v = value.clamp(0., 1.);
        match self {
            Self::AdobeRgb => v.powf(256. / 563.),
            _ => {
                if v <= 0.0031308 {
                    12.92 * v
                } else {
                    1.055 * v.powf(1. / 2.4) - 0.055
                }
            }
        }
    }
    pub fn profile(self) -> moxcms::ColorProfile {
        let mut p = match self {
            Self::Srgb => moxcms::ColorProfile::new_srgb(),
            Self::DisplayP3 => moxcms::ColorProfile::new_display_p3(),
            Self::AdobeRgb => moxcms::ColorProfile::new_adobe_rgb(),
        };
        // Use only the ICC matrix/TRCs. The bundled profile's optional video
        // CICP metadata includes YCbCr/range hints inappropriate to RGB files.
        p.cicp = None;
        // Reproducible profile bytes shared with the PNG assets.
        p.creation_date_time = moxcms::ColorDateTime {
            year: 2026,
            month: 10,
            day_of_the_month: 31,
            hours: 0,
            minutes: 0,
            seconds: 0,
        };
        p
    }
    pub fn icc(self) -> Result<Vec<u8>> {
        let mut bytes = self
            .profile()
            .encode()
            .map_err(|_| err("ENCODE_FAILED", "encode"))?;
        // moxcms 0.8.1 writer uses its own current header date, not the
        // profile field. Normalize only this non-color header; profile ID is 0.
        for (index, value) in [2026u16, 10, 31, 0, 0, 0].iter().enumerate() {
            bytes[24 + index * 2..26 + index * 2].copy_from_slice(&value.to_be_bytes());
        }
        Ok(bytes)
    }
}
pub fn default_output_color_space() -> String {
    "sRGB".into()
}
