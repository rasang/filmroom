//! Isolated, bounded resident scanner suffix. The original scanner equations,
//! gamut tables, full-line FIR/IIR, and branch-specific encoding order are kept.
//! A supplied glare field is the original sequential RNG's *raw percentage*.
//! Supplying that field does not claim that its stochastic generation ran on GPU.
use crate::research_spatial::{self, FilmOpticsPlan, MIB, R, check};
use serde::{Deserialize, Serialize};
use spektrafilm_core::{params::RuntimeParams, pipeline::GpuScanTailDescriptor};
pub mod full_stage;
mod gamut;
pub(crate) mod glare;
#[cfg(feature = "gpu-diffusion-research")]
pub(crate) mod gpu;
pub const SHADER_APPEND: &str = include_str!("shaders/research_finish_tail.wgsl");
pub(crate) const SHADER_GUARD: &str = include_str!("shaders/research_finish_tail_guard.wgsl");
pub const TABLE_BASE: u32 = 262144;
pub const CHUNK_BASE: u32 = 524288;
pub const CHUNK_PIXELS: usize = 32768;
pub const POINT_TOLERANCE: f64 = 1e-3;
pub fn shader() -> String {
    shader_checked(research_spatial::SHADER).expect("GPU_TAIL_SHADER_VERSION")
}
pub(crate) fn shader_checked(source: &str) -> R<String> {
    let marker = " if(op==0u || op==1u){";
    if source.matches(marker).count() != 1 {
        return Err("GPU_TAIL_SHADER_VERSION".into());
    }
    let source=source.replace(marker," if(op>=30u){glare_main(i);return;}\n if(op>=20u){tail_main(i);return;}\n if(op==0u || op==1u){");
    Ok(guarded_spatial_shader(&source)? + SHADER_APPEND + glare::SHADER)
}
/// Reuse requires the seven-buffer Runner and rejection of any nonzero
/// read_error_flag before candidate publication. The flag is never reset.
pub(crate) fn guarded_spatial_shader(source: &str) -> R<String> {
    // Exact replacement counts make shared-shader drift a closed admission
    // failure, never a silent loss of full-line arithmetic guards.
    let replacements = [
        (
            "sum=sum+get_value(p.u[1].z,index)*coefficient;",
            "sum=tadd(sum,tmul(get_value(p.u[1].z,index),coefficient));",
            1,
        ),
        (
            "let v=a*get_value(p.u[1].z,index)+b1*s1+b2*s2+b3*s3;",
            "let v=tadd(tadd(tadd(tmul(a,get_value(p.u[1].z,index)),tmul(b1,s1)),tmul(b2,s2)),tmul(b3,s3));",
            1,
        ),
        (
            "let v=a*get_value(p.u[1].w,index)+b1*s1+b2*s2+b3*s3;",
            "let v=tadd(tadd(tadd(tmul(a,get_value(p.u[1].w,index)),tmul(b1,s1)),tmul(b2,s2)),tmul(b3,s3));",
            1,
        ),
    ];
    let mut result = source.to_owned();
    for (old, new, count) in replacements {
        if result.matches(old).count() != count {
            return Err("GPU_TAIL_SHADER_VERSION".into());
        }
        result = result.replace(old, new);
    }
    Ok(result + SHADER_GUARD)
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GlarePlan {
    pub percent: f32,
    pub roughness: f32,
    pub blur: f32,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TailOptions {
    pub glare: Option<GlarePlan>,
    pub lens_sigma: f32,
    pub sharp_radius: f32,
    pub sharp_amount: f32,
    pub brightness_ev: f64,
    pub finalize_cctf: bool,
    pub preview_output_space: Option<String>,
}
impl TailOptions {
    /// The mobile route performs its output-space conversion after scanner clip,
    /// before brightness. Full-size export must continue to use from_params.
    pub fn for_preview(p: &RuntimeParams, brightness_ev: f64) -> Self {
        let mut options = Self::from_params(p, brightness_ev);
        options.preview_output_space = Some(p.io.output_color_space.clone());
        options
    }
    fn preview_matrix(&self) -> Option<[[f64; 3]; 3]> {
        match self.preview_output_space.as_deref() {
            Some("Display P3") => Some(spektrafilm_core::output_color::P3_TO_XYZ),
            Some("Adobe RGB (1998)") => Some(spektrafilm_core::output_color::ADOBE_TO_XYZ),
            _ => None,
        }
    }
    pub fn from_params(p: &RuntimeParams, brightness_ev: f64) -> Self {
        let g = if p.io.scan_film {
            &p.film_render.glare
        } else {
            &p.print_render.glare
        };
        let glare = (g.active && g.percent > 0.).then(|| GlarePlan {
            percent: g.percent,
            roughness: g.roughness,
            blur: g.blur,
        });
        let [sharp_radius, sharp_amount] = p.scanner.unsharp_mask;
        let spatial = p.scanner.lens_blur > 0. || (sharp_radius > 0. && sharp_amount > 0.);
        Self {
            finalize_cctf: !spatial
                && glare.is_none()
                && (p.io.scan_film || !p.enlarger.diffusion_filter.active),
            glare,
            lens_sigma: p.scanner.lens_blur,
            sharp_radius,
            sharp_amount,
            brightness_ev,
            preview_output_space: None,
        }
    }
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Gamut {
    pub mode: u32,
    pub knee: [f32; 3],
    pub lightness: Option<[f32; 3]>,
    pub l_white: f32,
    pub l_min: f32,
    pub l_max: f32,
    pub n_l: u32,
    pub n_h: u32,
    pub cmax: Vec<f64>,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TailPlan {
    pub width: u32,
    pub height: u32,
    pub adapt: [[f64; 3]; 3],
    pub xyz_to_rgb: [[f64; 3]; 3],
    pub xyz_remap: Option<(f64, f64)>,
    pub glare_rgb_offset: [f64; 3],
    pub gamut: Option<Gamut>,
    pub rgb_identity: [[f64; 3]; 3],
    pub encode: bool,
    pub options: TailOptions,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
fn finite(v: f64) -> bool {
    v.is_finite() && (v as f32).is_finite()
}
impl TailPlan {
    pub fn new(
        d: &GpuScanTailDescriptor<'_>,
        width: u32,
        height: u32,
        options: TailOptions,
    ) -> R<Self> {
        // mobile::runtime_for_film requires linear output. Do not silently use
        // the export encoding branch if an unsupported preview caller appears.
        if options.preview_output_space.is_some() && d.output_cctf_encoding {
            return Err("GPU_TAIL_UNSUPPORTED".into());
        }
        let s = FilmOpticsPlan::new(&RuntimeParams::default(), 1., width, height)?;
        let gamut = d.gamut.as_ref().map(|g| Gamut {
            mode: g.mode,
            knee: g.knee,
            lightness: g.lightness,
            l_white: g.l_white,
            l_min: g.l_min,
            l_max: g.l_max,
            n_l: g.n_l,
            n_h: g.n_h,
            cmax: g.cmax.to_vec(),
        });
        // Full guarded FIR/IIR/glare shaders have a larger compiler working set.
        // This is a conservative admission allowance, not a driver hard bound.
        let driver = if options.glare.is_some()
            || options.lens_sigma > 0.
            || (options.sharp_radius > 0. && options.sharp_amount > 0.)
        {
            320 * MIB
        } else {
            64 * MIB
        };
        let result = Self {
            width,
            height,
            adapt: d.adapt,
            xyz_to_rgb: d.xyz_to_rgb,
            xyz_remap: d.xyz_remap,
            glare_rgb_offset: d.glare_rgb_offset,
            gamut,
            rgb_identity: d.rgb_identity,
            encode: d.output_cctf_encoding && options.finalize_cctf,
            options,
            device_bytes: s.device_bytes,
            host_bytes: 24 * MIB,
            driver_allowance_bytes: driver,
            helper_allowance_bytes: 64 * MIB,
            combined_peak_bytes: s.device_bytes + 88 * MIB + driver,
            // source snapshot + raw glare snapshot + output + conservative manifest allowance.
            disk_bytes: width as u64 * height as u64 * 44 + 2 * MIB,
        };
        result.validate()?;
        Ok(result)
    }
    pub fn expected_driver_allowance(&self) -> u64 {
        if self.options.glare.is_some()
            || self.options.lens_sigma > 0.
            || (self.options.sharp_radius > 0. && self.options.sharp_amount > 0.)
        {
            320 * MIB
        } else {
            64 * MIB
        }
    }
    pub fn pass_count(&self) -> u64 {
        u64::from(self.options.glare.as_ref().is_some_and(|g| g.blur > 0.))
            + 3 * u64::from(self.options.lens_sigma > 0.)
            + 3 * u64::from(self.options.sharp_radius > 0. && self.options.sharp_amount > 0.)
    }
    pub fn sample_count(&self) -> u64 {
        let n = self.width as u64 * self.height as u64;
        let chunks = n.div_ceil(CHUNK_PIXELS as u64);
        let last = n - (chunks - 1) * CHUNK_PIXELS as u64;
        self.pass_count() * (self.width as u64 + self.height as u64)
            + 3 * (8 * (chunks - 1) + last.min(8))
    }
    pub fn spatial_plan(&self) -> R<FilmOpticsPlan> {
        let mut p = FilmOpticsPlan::new(&RuntimeParams::default(), 1., self.width, self.height)?;
        for ch in &mut p.channels {
            ch.lens_sigma = 0.;
            ch.scatter = None;
            ch.bounce_sigmas.clear();
            ch.bounce_weights.clear();
            ch.strength = 0.;
            ch.divisor = 1.;
        }
        Ok(p)
    }
    pub fn validate(&self) -> R<()> {
        let s = self.spatial_plan()?;
        let o = &self.options;
        if self.device_bytes != s.device_bytes
            || self.host_bytes != 24 * MIB
            || self.driver_allowance_bytes != self.expected_driver_allowance()
            || self.helper_allowance_bytes != 64 * MIB
            || self.combined_peak_bytes
                != self.device_bytes + 88 * MIB + self.expected_driver_allowance()
            || self.combined_peak_bytes > research_spatial::MEMORY_CAP
            || self.disk_bytes != self.width as u64 * self.height as u64 * 44 + 2 * MIB
        {
            return Err("GPU_TAIL_MEMORY_LIMIT".into());
        }
        if self
            .adapt
            .iter()
            .flatten()
            .chain(self.xyz_to_rgb.iter().flatten())
            .chain(self.rgb_identity.iter().flatten())
            .chain(self.glare_rgb_offset.iter())
            .any(|&v| !finite(v))
            || self
                .xyz_remap
                .is_some_and(|(a, b)| !finite(a) || !finite(b))
            || ![o.lens_sigma, o.sharp_radius, o.sharp_amount]
                .iter()
                .all(|x| x.is_finite() && *x >= 0.)
            || !finite(o.brightness_ev)
            || !finite(2f64.powf(o.brightness_ev))
            || o.preview_output_space
                .as_ref()
                .is_some_and(|s| s.len() > 64)
            || (self.encode && o.preview_output_space.is_some())
            || (self.encode && !o.finalize_cctf)
            || (o.finalize_cctf
                && (o.glare.is_some()
                    || o.lens_sigma > 0.
                    || (o.sharp_radius > 0. && o.sharp_amount > 0.)))
        {
            return Err("INVALID_TAIL_PLAN".into());
        }
        research_spatial::Filter::new(o.lens_sigma)?;
        research_spatial::Filter::new(o.sharp_radius)?;
        if let Some(g) = &o.glare {
            if ![g.percent, g.roughness, g.blur]
                .iter()
                .all(|v| v.is_finite() && *v >= 0.)
                || g.percent <= 0.
            {
                return Err("INVALID_TAIL_GLARE".into());
            }
            research_spatial::Filter::new(g.blur)?;
        }
        if let Some(g) = &self.gamut {
            if g.mode > 3
                || g.knee.iter().any(|&x| !x.is_finite())
                || g.knee[1] <= g.knee[0]
                || g.knee[2] <= 0.
                || g.lightness.is_some_and(|v| {
                    v.iter().any(|&x| !x.is_finite()) || v[1] <= v[0] || v[2] <= 0.
                })
                || ![g.l_white, g.l_min, g.l_max].iter().all(|v| v.is_finite())
                || g.l_white <= 0.
                || g.l_max <= g.l_min
                || g.n_l != 64
                || g.n_h != 720
                || (g.mode == 0 && !g.cmax.is_empty())
                || (g.mode != 0 && g.cmax.len() != 64 * 720)
                || g.cmax.iter().any(|&x| !finite(x) || x < 0.)
            {
                return Err("INVALID_TAIL_GAMUT".into());
            }
        }
        Ok(())
    }
    pub fn numerical_preflight(&self, c: &mut impl FnMut() -> bool) -> R<()> {
        self.validate()?;
        research_spatial::numerical_preflight_for_sigmas(
            self.width,
            self.height,
            [
                self.options.lens_sigma,
                self.options.sharp_radius,
                self.options.glare.as_ref().map_or(0., |g| g.blur),
            ],
            c,
        )
    }
    pub(crate) fn table32(&self) -> Vec<f32> {
        let mut v = vec![0.; 64];
        for (offset, m) in [
            (0, &self.adapt),
            (9, &self.xyz_to_rgb),
            (18, &self.rgb_identity),
        ] {
            for (i, &x) in m.iter().flatten().enumerate() {
                v[offset + i] = x as f32;
            }
        }
        if let Some((a, b)) = self.xyz_remap {
            v[27] = 1.;
            v[28] = a as f32;
            v[29] = b as f32;
        }
        v[30..33].copy_from_slice(&self.glare_rgb_offset.map(|x| x as f32));
        v[33] = if self.encode { 1. } else { 0. };
        v[34] = 2f64.powf(self.options.brightness_ev) as f32;
        if let Some(matrix) = self.options.preview_matrix() {
            v[49] = 1.;
            for (i, &x) in matrix.iter().flatten().enumerate() {
                v[50 + i] = x as f32;
            }
        }

        if let Some(g) = &self.gamut {
            v[35] = 1.;
            v[36] = g.mode as f32;
            v[37..40].copy_from_slice(&g.knee);
            if let Some(l) = g.lightness {
                v[40] = 1.;
                v[41..44].copy_from_slice(&l);
            }
            v[44] = g.l_white;
            v[45] = g.l_min;
            v[46] = g.l_max;
            v[47] = g.n_l as f32;
            v[48] = g.n_h as f32;
            v.extend(g.cmax.iter().map(|&x| x as f32));
        }
        v
    }
}
fn mat(m: &[[f64; 3]; 3], v: [f32; 3]) -> [f32; 3] {
    std::array::from_fn(|i| m[i][0] as f32 * v[0] + m[i][1] as f32 * v[1] + m[i][2] as f32 * v[2])
}
/// Test arithmetic mirror, not a CPU production fallback and never GPU evidence.
pub fn color_mirror(p: &TailPlan, log_xyz: [f32; 3], glare_percent: f32) -> R<[f32; 3]> {
    if log_xyz.iter().any(|v| !v.is_finite()) || !glare_percent.is_finite() || glare_percent < 0. {
        return Err("NONFINITE_TAIL_INPUT".into());
    }
    let mut xyz = log_xyz.map(|x| 10f32.powf(x));
    if xyz.iter().any(|x| !x.is_finite()) {
        return Err("NONFINITE_TAIL_OUTPUT".into());
    }
    if let Some((m, q)) = p.xyz_remap {
        let scale = (m as f32 * xyz[1] + q as f32).clamp(0., 1.) / (xyz[1] + 1e-10);
        for v in &mut xyz {
            *v *= scale;
        }
    }
    let mut rgb = mat(&p.xyz_to_rgb, mat(&p.adapt, xyz));
    if p.options.glare.is_some() {
        let g = glare_percent * 0.01;
        for (i, v) in rgb.iter_mut().enumerate() {
            *v += g * p.glare_rgb_offset[i] as f32;
        }
    }
    if let Some(g) = &p.gamut {
        rgb = gamut::compress(g, rgb);
    }
    if rgb.iter().any(|v| !v.is_finite()) {
        return Err("NONFINITE_TAIL_OUTPUT".into());
    }
    if p.encode {
        rgb = mat(&p.rgb_identity, rgb);
        if rgb.iter().any(|v| !v.is_finite()) {
            return Err("NONFINITE_TAIL_OUTPUT".into());
        }
        rgb = rgb.map(|x| {
            let x = x.clamp(0., 1.);
            if x <= 0.0031308 {
                x * 12.92
            } else {
                1.055 * x.powf(1. / 2.4) - 0.055
            }
        });
    }
    Ok(rgb)
}
#[cfg(test)]
fn blur_mirror(
    v: &[f32],
    w: usize,
    h: usize,
    sigma: f32,
    c: &mut impl FnMut() -> bool,
) -> R<Vec<f32>> {
    let f = research_spatial::Filter::new(sigma)?;
    let mut x = vec![0.; v.len()];
    for y in 0..h {
        check(c)?;
        x[y * w..(y + 1) * w].copy_from_slice(&f.line_f32(&v[y * w..(y + 1) * w]));
    }
    let mut out = vec![0.; v.len()];
    for col in 0..w {
        check(c)?;
        let line: Vec<_> = (0..h).map(|y| x[y * w + col]).collect();
        let b = f.line_f32(&line);
        for y in 0..h {
            out[y * w + col] = b[y];
        }
    }
    Ok(out)
}
#[cfg(test)]
pub(crate) fn image_mirror(
    p: &TailPlan,
    log_xyz: &[[f32; 3]],
    glare: Option<&[f32]>,
    c: &mut impl FnMut() -> bool,
) -> R<Vec<[f32; 3]>> {
    p.validate()?;
    check(c)?;
    let n = p.width as usize * p.height as usize;
    if log_xyz.len() != n
        || glare.is_some() != p.options.glare.is_some()
        || glare.is_some_and(|g| g.len() != n)
    {
        return Err("INVALID_TAIL_INPUT".into());
    }
    let glare = if let Some(g) = glare {
        Some(blur_mirror(
            g,
            p.width as usize,
            p.height as usize,
            p.options.glare.as_ref().unwrap().blur,
            c,
        )?)
    } else {
        None
    };
    let mut rgb = log_xyz
        .iter()
        .enumerate()
        .map(|(i, &x)| color_mirror(p, x, glare.as_ref().map_or(0., |g| g[i])))
        .collect::<R<Vec<_>>>()?;
    for ch in 0..3 {
        check(c)?;
        let mut source: Vec<_> = rgb.iter().map(|x| x[ch]).collect();
        if p.options.lens_sigma > 0. {
            source = blur_mirror(
                &source,
                p.width as usize,
                p.height as usize,
                p.options.lens_sigma,
                c,
            )?;
        }
        if p.options.sharp_radius > 0. && p.options.sharp_amount > 0. {
            let blurred = blur_mirror(
                &source,
                p.width as usize,
                p.height as usize,
                p.options.sharp_radius,
                c,
            )?;
            for (i, v) in source.iter_mut().enumerate() {
                *v += p.options.sharp_amount * (*v - blurred[i]);
            }
        }
        for (i, &v) in source.iter().enumerate() {
            if !v.is_finite() {
                return Err("NONFINITE_TAIL_OUTPUT".into());
            }
            rgb[i][ch] = v.clamp(0., 1.);
            if !rgb[i][ch].is_finite() {
                return Err("NONFINITE_TAIL_OUTPUT".into());
            }
        }
    }
    for value in &mut rgb {
        if let Some(matrix) = p.options.preview_matrix() {
            *value = mat(
                &spektrafilm_math::colorspace::XYZ_TO_SRGB_F64,
                mat(&matrix, *value),
            );
        }
        *value = value.map(|x| x * 2f64.powf(p.options.brightness_ev) as f32);
        if value.iter().any(|v| !v.is_finite()) {
            return Err("NONFINITE_TAIL_OUTPUT".into());
        }
    }
    Ok(rgb)
}
#[cfg(test)]
mod tests;
pub use full_stage::{
    StagePlan, estimated_disk_bytes, plan_stage, prepare_stage, run_stage, validate_output,
};
