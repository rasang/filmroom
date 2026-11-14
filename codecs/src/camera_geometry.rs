//! Embedded/database distortion uses one bounded camera-RGB geometry stage.
//! Identity/missing/unsupported metadata preserves the original strip path.
use super::*;
use crate::embedded_lens::LensStatus;

#[path = "camera_optics.rs"]
mod optics;

const GEOMETRY_SCRATCH: u64 = 10 * MIB;

pub(crate) fn qualify_optics(d: &mut Development) { optics::qualify(d); }

fn enabled(d: &Development) -> bool {
    d.lens_profile.as_ref().is_some_and(|p|p.has_distortion())
        || optics::tca_active(d) || match &d.lens {
        LensStatus::Ready(model) => !model.is_identity(),
        LensStatus::Profile(_) => true,
        LensStatus::Missing | LensStatus::Unsupported => false,
    }
}

/// Sensor decoding and resampling are separate phases. Callers release their
/// integer image/input mapping before `finish`, so the new peak is bounded.
pub(crate) fn admit_geometry(d: &Development, b: &Budget, mut a: Admission) -> Result<Admission> {
    if enabled(d) {
        // The sensor decoder retains its existing private-spool cap. Geometry
        // adds a separate active RGB backing, admitted against actual caller
        // free space; 50 MP correction can legitimately exceed 1 GiB in total.
        a.required_disk_bytes = a.required_disk_bytes
            .checked_add((d.active.w * d.active.h * 12) as u64)
            .ok_or_else(|| err("DISK_BUDGET", "admission"))?;
        a.estimated_peak_bytes = a.estimated_peak_bytes.max(BASE + GEOMETRY_SCRATCH);
        if a.required_disk_bytes > b.disk_budget_bytes {
            return Err(err("DISK_BUDGET", "admission"));
        }
        if a.estimated_peak_bytes > a.memory_budget_bytes {
            return Err(err("MEMORY_BUDGET", "admission"));
        }
    }
    Ok(a)
}

pub(crate) struct CameraRgbSink {
    camera: Option<File>,
}
impl CameraRgbSink {
    pub(crate) fn new(d: &Development, output: &Path) -> Result<Self> {
        let camera = if enabled(d) {
            let contains = |outer: Rect, inner: Rect| {
                inner.w >= 5 && inner.h >= 5 && inner.x >= outer.x && inner.y >= outer.y
                    && inner.x.checked_add(inner.w).zip(outer.x.checked_add(outer.w)).is_some_and(|(a,b)| a <= b)
                    && inner.y.checked_add(inner.h).zip(outer.y.checked_add(outer.h)).is_some_and(|(a,b)| a <= b)
            };
            if !contains(d.active, d.lens_reference) || !contains(d.lens_reference, d.crop) {
                return Err(err("INVALID_INPUT", "develop"));
            }
            if let LensStatus::Profile(model) = &d.lens {
                if model.dimensions() != [d.lens_reference.w, d.lens_reference.h]
                    || !d.gain_maps.is_empty()
                {
                    return Err(err("INVALID_INPUT", "develop"));
                }
            }
            if let Some(profile)=&d.lens_profile {
                if !contains(d.active,profile.reference()) || !contains(profile.reference(),d.crop)
                    || !d.gain_maps.is_empty() {return Err(err("INVALID_INPUT","develop"));}
            }
            Some(super::geometry::backing_file(output, (d.active.w * d.active.h * 12) as u64)?)
        } else {
            None
        };
        Ok(Self { camera })
    }

    pub(crate) fn region(&self, d: &Development) -> Rect {
        if self.camera.is_some() { d.active } else { d.crop }
    }

    pub(crate) fn develop(
        &self, d: &Development, y: usize, h: usize, data: Vec<f32>, cancel: &AtomicBool,
    ) -> Result<rawler::pixarray::Color2D<f32, 3>> {
        if cancel.load(Ordering::Relaxed) { return Err(err("CANCELLED", "develop")); }
        let source_vignette = optics::vignette_active(d);
        if self.camera.is_some() || source_vignette {
            let mut pixels = if d.cpp == 1 && d.cfa.len() == 36 {
                crate::camera_raw::fuji::xtrans::camera(d, y, h, data, cancel)?
            } else { camera_tile(d, y, h, data)? };
            optics::apply_source_vignette(d, y, &mut pixels, cancel)?;
            if self.camera.is_some() { Ok(pixels) }
            else { calibrate_camera_tile(d, pixels, cancel) }
        } else if d.cpp == 1 && d.cfa.len() == 36 {
            crate::camera_raw::fuji::xtrans::develop(d, y, h, data, cancel)
        } else { develop_tile_cancel(d, y, h, data, cancel) }
    }

    pub(crate) fn emit_strip(
        &self, d: &Development, out: &File, p: &rawler::pixarray::Color2D<f32, 3>,
        y: usize, first: usize, last: usize, cancel: &AtomicBool,
    ) -> Result<()> {
        if let Some(camera) = &self.camera {
            super::geometry::write_region(camera, d.active, p,
                Rect { x: d.active.x, y, w: d.active.w, h: p.height }, first..last, cancel)
        } else {
            emit(d, out, p, y, first, last, cancel)
        }
    }

    /// External DNG optics keeps DNG's established calibration after the
    /// source-domain optics, instead of substituting the vendor RAW matrix.
    pub(crate) fn develop_dng(&self,dng:&Dng,d:&Development,y:usize,h:usize,data:Vec<f32>,cancel:&AtomicBool)
        ->Result<rawler::pixarray::Color2D<f32,3>> {
        if dng.cpp!=1 || dng.profile.is_some() || dng.warp.is_some()
            || !dng.gain_maps.is_empty() || !dng.cfa_gain_maps.is_empty() || !dng.stage1_opcodes.is_empty() {
            return Err(err("INVALID_INPUT","lens_geometry"));
        }
        if cancel.load(Ordering::Relaxed) {return Err(err("CANCELLED","develop"));}
        let mut pixels=camera_tile(d,y,h,data)?;
        optics::apply_source_vignette(d,y,&mut pixels,cancel)?;
        if self.camera.is_some() {Ok(pixels)} else {calibrate_camera(dng,pixels,cancel)}
    }

    pub(crate) fn emit_region(
        &self, d: &Development, out: &File, p: &rawler::pixarray::Color2D<f32, 3>,
        tile: Rect, cancel: &AtomicBool,
    ) -> Result<()> {
        if let Some(camera) = &self.camera {
            super::geometry::write_region(camera, d.active, p, tile, tile.y..tile.y + tile.h, cancel)
        } else {
            super::emit_region(d, out, p, tile, cancel)
        }
    }

    pub(crate) fn finish(self, d: &Development, out: &File, cancel: &AtomicBool) -> Result<()> {
        self.finish_calibrated(d,out,cancel,|p|calibrate_camera_tile(d,p,cancel))
    }

    pub(crate) fn finish_dng(self,dng:&Dng,d:&Development,out:&File,cancel:&AtomicBool)->Result<()> {
        self.finish_calibrated(d,out,cancel,|p|calibrate_camera(dng,p,cancel))
    }

    fn finish_calibrated(self,d:&Development,out:&File,cancel:&AtomicBool,
        calibrate:impl Fn(rawler::pixarray::Color2D<f32,3>)->Result<rawler::pixarray::Color2D<f32,3>>)->Result<()> {
        let Some(camera) = self.camera else { return Ok(()); };
        if !enabled(d) { return Err(err("INVALID_INPUT", "develop")); }
        // Sony supplies an explicit visible RAW image size. RAF's encoded
        // padding varies with compression; Olympus also stores a visible crop.
        // Keep demosaic halos in the full active image but normalize geometry
        // to the adapter's chosen visible reference before final orientation.
        let reference = d.lens_reference;
        let output_reference=if d.lens_profile.as_ref().is_some_and(|p|p.has_distortion() || p.has_tca()) {
            d.crop
        } else {reference};
        let destination = Rect {
            x: output_reference.x.checked_sub(d.active.x).ok_or_else(|| err("INVALID_INPUT", "develop"))?,
            y: output_reference.y.checked_sub(d.active.y).ok_or_else(|| err("INVALID_INPUT", "develop"))?,
            w: output_reference.w, h: output_reference.h };
        super::geometry::resample_region(&camera, d.active.w, d.active.h, destination,
            |x, y, channel| {
                let rx=(x+d.active.x).checked_sub(reference.x).ok_or_else(||err("INVALID_INPUT","lens_geometry"))?;
                let ry=(y+d.active.y).checked_sub(reference.y).ok_or_else(||err("INVALID_INPUT","lens_geometry"))?;
                let [sx, sy] = optics::source_position(d, rx, ry, channel)?;
                Ok([sx + (reference.x-d.active.x) as f64, sy + (reference.y-d.active.y) as f64])
            },
            false, false, cancel, |tile, p| {
                let p = calibrate(p)?;
                super::emit_region(d, out, &p,
                    Rect { x: tile.x + d.active.x, y: tile.y + d.active.y, ..tile }, cancel)
            })
    }
}

#[cfg(test)]
#[path="camera_geometry_tests.rs"]
mod tests;
