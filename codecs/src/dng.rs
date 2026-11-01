use crate::{
    error::{Result, err, io_error},
    tiff::{Endian, Ifd, Tiff, nums, uint},
};
use serde::Serialize;
use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
};
#[derive(Clone, Copy, Debug, Serialize)]
pub struct Rect {
    pub x: usize,
    pub y: usize,
    pub w: usize,
    pub h: usize,
}
#[derive(Clone, Debug, Serialize)]
pub struct Warning {
    pub code: &'static str,
    pub message: &'static str,
}
#[derive(Clone, Debug)]
pub struct Dng {
    pub width: usize,
    pub height: usize,
    pub cpp: usize,
    pub orientation: u8,
    pub active: Rect,
    pub crop: Rect,
    pub endian: Endian,
    pub offsets: Vec<u32>,
    pub counts: Vec<u32>,
    pub rows_per_strip: usize,
    pub compression: u32,
    pub tile_width: usize,
    pub tile_height: usize,
    pub cfa: String,
    pub black: Vec<f32>,
    pub white: Vec<f32>,
    pub wb: [f32; 4],
    pub matrix: Vec<f32>,
    pub input_bytes: u64,
    pub warnings: Vec<Warning>,
    pub has_forward_matrix: bool,
    pub baseline_exposure_ev: f32,
    pub gain_maps: Vec<crate::gainmap::GainMap>,
    pub cfa_gain_maps: Vec<crate::cfa_gainmap::CfaGainMap>,
}
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Probe {
    pub format: &'static str,
    pub variant: &'static str,
    pub encoded_width: u32,
    pub encoded_height: u32,
    pub width: u32,
    pub height: u32,
    pub source_orientation: u8,
    pub orientation_applied: bool,
    pub active_area: Rect,
    pub default_crop: Rect,
    pub output_orientation: u8,
    pub input_bytes: u64,
    pub output_bytes: u64,
    pub max_pixels: u64,
    pub warnings: Vec<Warning>,
    pub has_alternate_forward_matrix: bool,
    pub development_policy: &'static str,
    pub baseline_exposure_applied_ev: f32,
}
fn color_err() -> crate::error::CodecError {
    err("INVALID_COLOR_METADATA", "probe")
}
fn arr<const N: usize>(v: Option<Vec<f64>>, default: [f64; N]) -> Result<[f64; N]> {
    let v = v.unwrap_or_else(|| default.to_vec());
    v.try_into()
        .map_err(|_| err("UNSUPPORTED_DNG_VARIANT", "probe"))
}
fn inherited<'a>(root: &'a Ifd, raw: &'a Ifd, tag: u16) -> Option<&'a crate::tiff::Value> {
    raw.get(&tag).or_else(|| root.get(&tag))
}
impl Dng {
    pub fn read(f: &mut File) -> Result<Self> {
        let t = Tiff::read(f)?;
        let root = &t.ifds[0];
        let version_tag = root
            .get(&50706)
            .ok_or_else(|| err("UNSUPPORTED_FORMAT", "probe"))?;
        if version_tag.typ != 1 {
            return Err(err("INVALID_INPUT", "probe"));
        }
        let ver = version_tag.uints()?;
        if let Some(backward) = root.get(&50707) {
            let minimum = backward.uints()?;
            if backward.typ != 1
                || minimum.len() != 4
                || minimum[0] != 1
                || minimum.as_slice() > [1, 4, 0, 0].as_slice()
            {
                return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
            }
        }
        if ver.len() != 4 || ver[0] != 1 || ver[1] > 7 {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        let mut candidates = Vec::new();
        for i in &t.ifds {
            let p = uint(i, 262, Some(0))?;
            if (p == 32803 || p == 34892) && uint(i, 254, Some(0))? & 1 == 0 {
                candidates.push(i)
            }
        }
        if candidates.len() != 1 {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        let raw = candidates[0];
        let compression = uint(raw, 259, Some(1))?;
        if compression != 1 && compression != 7 {
            return Err(err("UNSUPPORTED_COMPRESSION", "probe"));
        }
        // Legacy 1.0 lossless-JPEG category-16 compatibility is not verified.
        if compression == 7 && ver.as_slice() < [1, 1, 0, 0].as_slice() {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        let width = uint(raw, 256, None)? as usize;
        let height = uint(raw, 257, None)? as usize;
        if width < 8
            || height < 8
            || width > 32768
            || height > 32768
            || (width as u64) * (height as u64) > crate::MAX_PIXELS
        {
            return Err(err("PIXEL_LIMIT", "probe"));
        }
        let cpp = uint(raw, 277, Some(1))? as usize;
        let cfa_mode = uint(raw, 262, None)? == 32803;
        if cfa_mode && (width % 2 != 0 || height % 2 != 0) {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        if (cfa_mode && cpp != 1) || (!cfa_mode && cpp != 3) {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        let bits = raw
            .get(&258)
            .ok_or_else(|| err("INVALID_INPUT", "probe"))?
            .uints()?;
        if !(bits.len() == 1 || bits.len() == cpp) || bits.iter().any(|&v| v != 16) {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        if uint(raw, 284, Some(1))? != 1
            || uint(raw, 317, Some(1))? != 1
            || uint(raw, 339, Some(1))? != 1
        {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        crate::compat::unsupported_processing(root, raw)?;
        crate::compat::camera_calibration(root, raw)?;
        let has_forward_matrix = crate::compat::forward_matrices(root, raw)?;
        let mut warnings = Vec::new();
        let baseline_exposure_ev = crate::compat::baseline_exposure(root, raw)?;
        crate::compat::profile_metadata(root, raw, &mut warnings)?;
        let (gain_maps, cfa_gain_maps) = crate::compat::lens_opcodes(root, raw, width, height, cfa_mode, &mut warnings)?;
        crate::compat::unit_geometry(root, raw)?;
        let a = arr(nums(raw, 50829)?, [0., 0., height as f64, width as f64])?;
        if a.iter().any(|x| x.fract() != 0. || *x < 0.)
            || a[0] >= a[2]
            || a[1] >= a[3]
            || a[2] > height as f64
            || a[3] > width as f64
        {
            return Err(err("INVALID_INPUT", "probe"));
        }
        let active = Rect {
            x: a[1] as usize,
            y: a[0] as usize,
            w: (a[3] - a[1]) as usize,
            h: (a[2] - a[0]) as usize,
        };
        if (!gain_maps.is_empty() || !cfa_gain_maps.is_empty())
            && (active.x != 0 || active.y != 0 || active.w != width || active.h != height)
        {
            return Err(err("UNSUPPORTED_DNG_OPCODE", "probe"));
        }
        if cfa_mode && (active.w < 8 || active.h < 8 || active.x % 2 != 0 || active.y % 2 != 0) {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        let origin = arr(nums(raw, 50719)?, [0., 0.])?;
        let size = arr(nums(raw, 50720)?, [active.w as f64, active.h as f64])?;
        if origin
            .iter()
            .chain(size.iter())
            .any(|x| x.fract() != 0. || *x < 0.)
            || size[0] < 1.
            || size[1] < 1.
            || origin[0] + size[0] > active.w as f64
            || origin[1] + size[1] > active.h as f64
        {
            return Err(err("INVALID_INPUT", "probe"));
        }
        let crop = Rect {
            x: active.x + origin[0] as usize,
            y: active.y + origin[1] as usize,
            w: size[0] as usize,
            h: size[1] as usize,
        };
        let cfa = if cfa_mode {
            if arr(nums(raw, 33421)?, [0., 0.])? != [2., 2.] || uint(raw, 50711, Some(1))? != 1 {
                return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
            }
            if let Some(v) = nums(raw, 50710)? {
                if v != vec![0., 1., 2.] {
                    return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
                }
            }
            let v = raw
                .get(&33422)
                .ok_or_else(|| err("INVALID_INPUT", "probe"))?
                .uints()?;
            match v.as_slice() {
                [0, 1, 1, 2] => "RGGB",
                [2, 1, 1, 0] => "BGGR",
                [1, 0, 2, 1] => "GRBG",
                [1, 2, 0, 1] => "GBRG",
                _ => return Err(err("UNSUPPORTED_DNG_VARIANT", "probe")),
            }
            .to_string()
        } else {
            String::new()
        };
        let br = arr(nums(raw, 50713)?, [1., 1.])?;
        let black = nums(raw, 50714)?.unwrap_or_else(|| vec![0.; cpp]);
        let black = if cfa_mode {
            if br == [1., 1.] && black.len() == 1 {
                vec![black[0]; 4]
            } else if br == [2., 2.] && black.len() == 4 {
                black
            } else {
                return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
            }
        } else {
            if br != [1., 1.] {
                return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
            }
            if black.len() == 1 {
                vec![black[0]; 3]
            } else if black.len() == 3 {
                black
            } else {
                return Err(color_err());
            }
        };
        let white = nums(raw, 50717)?.unwrap_or_else(|| vec![65535.]);
        let white = if white.len() == 1 {
            vec![white[0]; black.len()]
        } else if !cfa_mode && white.len() == 3 {
            white
        } else {
            return Err(color_err());
        };
        if black
            .iter()
            .zip(&white)
            .any(|(&b, &w)| !b.is_finite() || !w.is_finite() || b < 0. || w <= b || w > 65535.)
        {
            return Err(color_err());
        }
        let neutral = inherited(root, raw, 50728).ok_or_else(color_err)?.nums()?;
        if neutral.len() != 3
            || neutral
                .iter()
                .any(|&x| !x.is_finite() || x <= 0. || x > 100.)
        {
            return Err(color_err());
        }
        let wb = [
            (1. / neutral[0]) as f32,
            (1. / neutral[1]) as f32,
            (1. / neutral[2]) as f32,
            1.,
        ];
        if wb.iter().any(|&x| x > 100.) {
            return Err(color_err());
        }
        let mut matrix = None;
        for (mt, il) in [(50721, 50778), (50722, 50779)] {
            if let (Some(m), Some(l)) = (inherited(root, raw, mt), inherited(root, raw, il)) {
                if l.uints()? == vec![21] {
                    let mut values = m.nums()?;
                    if values.len() != 9 {
                        return Err(color_err());
                    }
                    let calibration_tag = if mt == 50721 { 50723 } else { 50724 };
                    if let Some(calibration) = inherited(root, raw, calibration_tag) {
                        let diagonal = calibration.nums()?;
                        for row in 0..3 {
                            for col in 0..3 {
                                values[row * 3 + col] *= diagonal[row * 4];
                            }
                        }
                    }
                    matrix = Some(values);
                    break;
                }
            }
        }
        let matrix = matrix.ok_or_else(color_err)?;
        if matrix.len() != 9 || matrix.iter().any(|x| !x.is_finite() || x.abs() > 10.) {
            return Err(color_err());
        }
        let m = &matrix;
        let det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6])
            + m[2] * (m[3] * m[7] - m[4] * m[6]);
        if det.abs() < 1e-8 {
            return Err(color_err());
        }
        let orientation = uint(root, 274, Some(1))?;
        if !(1..=8).contains(&orientation) {
            return Err(err("INVALID_INPUT", "probe"));
        }
        let tiled = raw.contains_key(&322)
            || raw.contains_key(&323)
            || raw.contains_key(&324)
            || raw.contains_key(&325);
        if tiled && (compression != 7 || raw.contains_key(&273) || raw.contains_key(&279)) {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        let (tile_width, tile_height, rows_per_strip, offset_tag, count_tag) = if tiled {
            (
                uint(raw, 322, None)? as usize,
                uint(raw, 323, None)? as usize,
                0,
                324,
                325,
            )
        } else {
            let rows = uint(raw, 278, Some(height as u32))? as usize;
            (width, rows.min(height), rows, 273, 279)
        };
        if tile_width == 0 || tile_height == 0 || (!tiled && rows_per_strip == 0) {
            return Err(err("INVALID_INPUT", "probe"));
        }
        if tile_width > 32768 || tile_height > 32768 {
            return Err(err("UNSUPPORTED_DNG_VARIANT", "probe"));
        }
        let offsets = raw
            .get(&offset_tag)
            .ok_or_else(|| err("INVALID_INPUT", "probe"))?
            .uints()?;
        let counts = raw
            .get(&count_tag)
            .ok_or_else(|| err("INVALID_INPUT", "probe"))?
            .uints()?;
        let across = if tiled { width.div_ceil(tile_width) } else { 1 };
        let down = if tiled {
            height.div_ceil(tile_height)
        } else {
            height.div_ceil(rows_per_strip)
        };
        if offsets.len() != across * down || counts.len() != offsets.len() {
            return Err(err("INVALID_INPUT", "probe"));
        }
        for (i, (&o, &n)) in offsets.iter().zip(&counts).enumerate() {
            let block_h = if tiled {
                tile_height
            } else {
                (height - i * rows_per_strip).min(rows_per_strip)
            };
            let samples = tile_width
                .checked_mul(block_h)
                .and_then(|v| v.checked_mul(cpp))
                .ok_or_else(|| err("PIXEL_LIMIT", "probe"))?;
            if n == 0 || o as u64 + n as u64 > t.size {
                return Err(err("INVALID_INPUT", "probe"));
            }
            if compression == 1 && n as usize != samples * 2 {
                return Err(err("INVALID_INPUT", "probe"));
            }
            if compression == 7
                && (samples > crate::lossless::MAX_SAMPLES
                    || n as usize > crate::lossless::MAX_ENCODED)
            {
                return Err(err("MEMORY_BUDGET", "probe"));
            }
        }
        Ok(Self {
            width,
            height,
            cpp,
            orientation: orientation as u8,
            active,
            crop,
            endian: t.endian,
            offsets,
            counts,
            rows_per_strip,
            compression,
            tile_width,
            tile_height,
            cfa,
            black: black.into_iter().map(|x| x as f32).collect(),
            white: white.into_iter().map(|x| x as f32).collect(),
            wb,
            matrix: matrix.into_iter().map(|x| x as f32).collect(),
            input_bytes: t.size,
            warnings,
            has_forward_matrix,
            baseline_exposure_ev,
            gain_maps,
            cfa_gain_maps,
        })
    }
    pub fn probe(&self) -> Probe {
        let (w, h) = self.oriented_dim();
        Probe {
            format: "DNG",
            variant: if self.compression == 7 {
                match (self.cpp, self.rows_per_strip == 0) {
                    (1, true) => "DNG_BAYER_U16_LOSSLESS_JPEG_TILES",
                    (1, false) => "DNG_BAYER_U16_LOSSLESS_JPEG_STRIPS",
                    (_, true) => "DNG_LINEAR_RGB_U16_LOSSLESS_JPEG_TILES",
                    (_, false) => "DNG_LINEAR_RGB_U16_LOSSLESS_JPEG_STRIPS",
                }
            } else if self.cpp == 1 {
                "DNG_BAYER_U16_UNCOMPRESSED_STRIPS"
            } else {
                "DNG_LINEAR_RGB_U16_UNCOMPRESSED_STRIPS"
            },
            encoded_width: self.width as u32,
            encoded_height: self.height as u32,
            width: w as u32,
            height: h as u32,
            source_orientation: self.orientation,
            orientation_applied: true,
            active_area: self.active,
            default_crop: self.crop,
            output_orientation: 1,
            input_bytes: self.input_bytes,
            output_bytes: (w * h * 12) as u64,
            max_pixels: crate::MAX_PIXELS,
            warnings: self.warnings.clone(),
            has_alternate_forward_matrix: self.has_forward_matrix,
            development_policy: "rawler0.7.2 ColorMatrix(D65)+AsShotNeutral; not full Adobe/Camera2 processing",
            baseline_exposure_applied_ev: self.baseline_exposure_ev,
        }
    }
    pub fn oriented_dim(&self) -> (usize, usize) {
        if self.orientation >= 5 {
            (self.crop.h, self.crop.w)
        } else {
            (self.crop.w, self.crop.h)
        }
    }
    fn read_compressed(
        &self,
        file: &mut File,
        y: usize,
        h: usize,
        cancel: &std::sync::atomic::AtomicBool,
    ) -> Result<Vec<f32>> {
        use std::sync::atomic::Ordering;
        let mut out = vec![0.; self.active.w * h * self.cpp];
        let tiled = self.rows_per_strip == 0;
        let across = if tiled {
            self.width.div_ceil(self.tile_width)
        } else {
            1
        };
        let first_y = y / self.tile_height;
        let last_y = (y + h - 1) / self.tile_height;
        let first_x = self.active.x / self.tile_width;
        let last_x = (self.active.x + self.active.w - 1) / self.tile_width;
        for by in first_y..=last_y {
            for bx in first_x..=last_x {
                if cancel.load(Ordering::Relaxed) {
                    return Err(err("CANCELLED", "decode"));
                }
                let index = by * across + bx;
                let block_h = if tiled {
                    self.tile_height
                } else {
                    (self.height - by * self.tile_height).min(self.tile_height)
                };
                let mut encoded = vec![0; self.counts[index] as usize];
                file.seek(SeekFrom::Start(self.offsets[index] as u64))
                    .map_err(|e| io_error(e, "decode"))?;
                file.read_exact(&mut encoded)
                    .map_err(|e| io_error(e, "decode"))?;
                let decoded =
                    crate::lossless::decode(&encoded, self.tile_width * self.cpp, block_h, cancel)?;
                let x0 = (bx * self.tile_width).max(self.active.x);
                let x1 = ((bx + 1) * self.tile_width).min(self.active.x + self.active.w);
                let y0 = (by * self.tile_height).max(y);
                let y1 = (by * self.tile_height + block_h).min(y + h);
                for sy in y0..y1 {
                    for sx in x0..x1 {
                        for c in 0..self.cpp {
                            let sample = decoded[((sy - by * self.tile_height) * self.tile_width
                                + (sx - bx * self.tile_width))
                                * self.cpp
                                + c] as f32;
                            let ch = if self.cpp == 1 {
                                (sy % 2) * 2 + sx % 2
                            } else {
                                c
                            };
                            out[((sy - y) * self.active.w + sx - self.active.x) * self.cpp + c] =
                                (sample - self.black[ch]).max(0.)
                                    / (self.white[ch] - self.black[ch]);
                        }
                    }
                }
            }
        }
        Ok(out)
    }
    pub fn decode_scratch_bytes(&self) -> u64 {
        let map_bytes: u64 = self.gain_maps.iter().map(|m| m.storage_bytes()).sum::<u64>() + self.cfa_gain_maps.iter().map(|m| m.storage_bytes()).sum::<u64>();
        if self.compression != 7 {
            return map_bytes;
        }
        map_bytes
            + self.counts.iter().copied().max().unwrap_or(0) as u64
            + (self.tile_width * self.tile_height * self.cpp * 2) as u64
            + crate::lossless::OVERHEAD
    }
    pub fn read_tile(&self, file: &mut File, y: usize, h: usize) -> Result<Vec<f32>> {
        self.read_tile_cancellable(file, y, h, &std::sync::atomic::AtomicBool::new(false))
    }
    pub fn read_tile_cancellable(
        &self,
        file: &mut File,
        y: usize,
        h: usize,
        cancel: &std::sync::atomic::AtomicBool,
    ) -> Result<Vec<f32>> {
        if h == 0
            || y < self.active.y
            || y.checked_add(h)
                .filter(|v| *v <= self.active.y + self.active.h)
                .is_none()
        {
            return Err(err("INVALID_INPUT", "decode"));
        }
        if self.compression == 7 {
            let mut out = self.read_compressed(file, y, h, cancel)?;
            crate::cfa_gainmap::apply(&self.cfa_gain_maps, &mut out, self.width, self.height, y, cancel)?;
            return Ok(out);
        }
        let w = self.active.w;
        let mut out = vec![0.; w * h * self.cpp];
        let mut row = vec![0; w * self.cpp * 2];
        for r in 0..h {
            let sy = y + r;
            let strip = sy / self.rows_per_strip;
            let start = self.offsets[strip] as u64
                + ((sy % self.rows_per_strip) * self.width + self.active.x) as u64
                    * self.cpp as u64
                    * 2;
            file.seek(SeekFrom::Start(start))
                .map_err(|e| io_error(e, "decode"))?;
            file.read_exact(&mut row)
                .map_err(|e| io_error(e, "decode"))?;
            for (i, b) in row.chunks_exact(2).enumerate() {
                let ch = if self.cpp == 1 {
                    (sy % 2) * 2 + (self.active.x + i) % 2
                } else {
                    i % 3
                };
                let sample = self.endian.u16(b) as f32;
                out[r * w * self.cpp + i] =
                    (sample - self.black[ch]).max(0.) / (self.white[ch] - self.black[ch]);
            }
        }
        crate::cfa_gainmap::apply(&self.cfa_gain_maps, &mut out, self.width, self.height, y, cancel)?;
        Ok(out)
    }
}
