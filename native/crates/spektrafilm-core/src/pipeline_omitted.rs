//! Digital transport for omitted media; optional Portra grain is a residual, not a stock colour substitution.
use super::*;
use spektrafilm_math::precision::from_f64;
use crate::neutral_media::{PositiveDensityBridge, REFERENCE_GRAY};

#[derive(Clone)]
pub(super) struct Calibration {
    pub(super) negative: Option<PositiveDensityBridge>,
    pub(super) paper_gray: Option<f64>,
    pub(super) paper_gain: [f64;3],
    pub(super) paper_log_bounds: [f64;2],
}
impl Pipeline {
    pub fn disable_absent_medium_controls(film:&Profile, paper:&Profile, p:&mut RuntimeParams) {
        if film.is_omitted() || paper.is_omitted() {
            p.io.scan_film=paper.is_omitted();
            // Physical black/white reference correction requires both media.
            // The digital bridge has its own fixed profile references instead.
            p.scanner.white_correction=false;p.scanner.black_correction=false;
        }
        if film.is_omitted() {
            p.film_render.halation.active=false;
            p.film_render.halation.boost_ev=0.;
            p.film_render.dir_couplers.active=false;
            // No film/paper pairing exists to look up in the calibration DB.
            p.settings.neutral_print_filters_from_database=false;
            p.enlarger.c_filter_neutral=0.;p.enlarger.m_filter_neutral=0.;p.enlarger.y_filter_neutral=0.;
            p.enlarger.print_exposure_compensation=false;
        }
        if paper.is_omitted() {
            p.enlarger.diffusion_filter.active=false;
            p.print_render.glare.active=false;
            p.film_render.glare.active=false;
        }
    }
    pub(super) fn new_omitted(film:Profile, paper:Profile, mut params:RuntimeParams, data:&Path)->Result<Self,String> {
        Self::disable_absent_medium_controls(&film,&paper,&mut params);
        let film=crate::profile::resolve_for_render(film,params.film_render.development_time);
        let paper=crate::profile::resolve_for_render(paper,params.print_render.development_time);
        let mut pipe=Self::new(film,paper,params);
        if pipe.film.is_omitted() && pipe.params.film_render.grain.active && pipe.params.film_render.grain.amount>0. {
            pipe.donor_grain=Some(Arc::new(crate::donor_grain::DonorGrain::load(data)?));
        }
        if pipe.film.is_omitted(){pipe.spectral=Arc::new(crate::prepared_spectral::PreparedSpectral::omitted_rgb());}
        if !pipe.film.is_omitted() {
            apply_film_specific_params(&pipe.film,&mut pipe.params);
            pipe.spectral=Arc::new(crate::prepared_spectral::PreparedSpectral::build(&pipe.film,&pipe.params,data,None)?);
        }
        let backend=spektrafilm_gpu::cpu_backend::CpuBackend;
        let negative=if !pipe.film.is_omitted() && !pipe.film.is_positive() && pipe.print.is_omitted() {
            let curves=pipe.film.density_curves_f64();
            let mut lo=[f64::INFINITY;3];let mut hi=[f64::NEG_INFINITY;3];
            for row in &curves {for c in 0..3 {if row[c].is_finite(){lo[c]=lo[c].min(row[c]);hi[c]=hi[c].max(row[c]);}}}
            let range=std::array::from_fn(|c|hi[c]-lo[c]);
            let mut p=pipe.params.clone();p.camera.auto_exposure=false;p.camera.exposure_compensation_ev=0.;p.camera.preview_metered_ev=None;
            p.camera.diffusion_filter.active=false;p.camera.lens_blur_um=0.;
            p.film_render.halation.active=false;p.film_render.halation.boost_ev=0.;
            p.film_render.grain.active=false;p.film_render.dir_couplers.active=false;
            p.film_render.density_curve_gamma=1.;
            let log=stages::filming::expose(&ImageBuf::from_data(1,1,vec![from_f64(REFERENCE_GRAY);3]),&pipe.film,&p,&backend,pipe.spectral.tc_lut.as_ref(),pipe.spectral.mallett_core.as_ref(),select_illuminant(&pipe.spectral.front_illuminant),1.);
            let mid=stages::filming::develop(&log,&pipe.film,&p,&backend);
            Some(PositiveDensityBridge::calibrated([0.;3],range,[mid.data[0] as f64,mid.data[1] as f64,mid.data[2] as f64]).map_err(str::to_owned)?)
        }else{None};
        let paper_gray=if pipe.film.is_omitted() && !pipe.print.is_omitted() {
            let mut p=pipe.params.clone();p.io.scan_film=false;p.io.output_color_space="sRGB".into();p.io.output_cctf_encoding=false;
            p.settings.use_scanner_lut=false;p.scanner.lens_blur=0.;p.scanner.unsharp_mask=[0.,0.];
            p.print_render.glare.active=false;p.scanner.white_correction=false;p.scanner.black_correction=false;
            let refs=crate::color_reference::ColorReference::identity();
            let gamut=crate::gamut_compression::OutputGamutCompress::build(&p.io.output_gamut_compress,&p.io.output_color_space);
            let exposure=pipe.print.log_exposure_f64();
            let mut low=*exposure.first().ok_or("Paper has no exposure axis")?;
            let mut high=*exposure.last().ok_or("Paper has no exposure axis")?;
            if !low.is_finite()||!high.is_finite()||low>=high{return Err("Invalid paper exposure axis".into());}
            let luminance=|log_exposure:f64| {
                let density=stages::printing::develop(&ImageBuf::from_data(1,1,vec![from_f64(log_exposure);3]),&pipe.print,&p,&backend);
                let rgb=stages::scanning::process(&density,&pipe.print,&p,&backend,&refs,&gamut);
                rgb.data[0] as f64*0.2126+rgb.data[1] as f64*0.7152+rgb.data[2] as f64*0.0722
            };
            let (light,dark)=(luminance(low),luminance(high));
            if !light.is_finite() || !dark.is_finite() || light<REFERENCE_GRAY || dark>REFERENCE_GRAY {
                return Err("Paper response does not bracket the fixed gray reference".into());
            }
            for _ in 0..40 {
                let mid=(low+high)*0.5;
                let y=luminance(mid);
                if !y.is_finite(){return Err("Nonfinite paper gray calibration".into());}
                if y>REFERENCE_GRAY {low=mid;} else {high=mid;}
            }
            Some((low+high)*0.5)
        }else{None};
        let mut paper_gain=[1.;3];
        if paper_gray.is_some() {
            let base=crate::enlarger::enlarger_filtered_illuminant_f64(&pipe.params.enlarger.illuminant,0.,0.,0.);
            let sensitivity=pipe.print.log_sensitivity_f64();
            for c in 0..3 {
                let mut numerator=0.;let mut denominator=0.;
                for (i,row) in sensitivity.iter().enumerate() {
                    let sensitivity=10f64.powf(row[c]);
                    if sensitivity.is_finite() {numerator+=sensitivity*pipe.print_illuminant[i];denominator+=sensitivity*base[i];}
                }
                if denominator<=0.||!numerator.is_finite(){return Err("Invalid paper exposure sensitivity".into());}
                paper_gain[c]=numerator/denominator;
            }
        }
        if paper_gray.is_some() {
            pipe.preflash_raw=std::array::from_fn(|channel|pipe.params.enlarger.preflash_exposure as f64 * 10f64.powf(-match channel {1=>pipe.params.enlarger.preflash_m_filter_shift as f64,2=>pipe.params.enlarger.preflash_y_filter_shift as f64,_=>0.}/100.));
        }
        let axis=pipe.print.log_exposure_f64();
        let paper_log_bounds=if paper_gray.is_some(){[*axis.first().ok_or("Missing paper axis")?,*axis.last().ok_or("Missing paper axis")?]}else{[-1.,1.]};
        pipe.omitted_calibration=Some(Arc::new(Calibration{negative,paper_gray,paper_gain,paper_log_bounds}));
        Ok(pipe)
    }
    pub(super) fn process_omitted(&self,image:ImageBuf,backend:&dyn ComputeBackend)->ImageBuf {
        if backend.is_gpu() {
            if let Some(out)=self.try_gpu_resident(spektrafilm_gpu::ChainImage::F64(&image),backend,&crate::color_reference::ColorReference::identity(),false,None,spektrafilm_gpu::ChainStop::Full,None) {
                return if backend.resident_chain_applies_post_scan(){out}else{self.apply_post_scan(out)};
            }
        }
        let log=stages::filming::expose(&image,&self.film,&self.params,backend,self.spectral.tc_lut.as_ref(),self.spectral.mallett_core.as_ref(),select_illuminant(&self.spectral.front_illuminant),1.);
        let log=if self.film.is_omitted(){self.apply_donor_grain(log,backend)}else{log};
        if self.film.is_omitted() && !self.print.is_omitted() {
            let mut raw=self.expose_omitted_paper_raw(&log);
            let df=&self.params.enlarger.diffusion_filter;
            if df.active {
                let pitch=self.params.camera.pixel_size_um_override.unwrap_or_else(||stages::filming::pixel_size_um(self.params.camera.film_format_mm,raw.width,raw.height));
                raw=if backend.is_gpu() {spektrafilm_model::diffusion::apply_diffusion_filter_blur(&raw,&df.to_model(),pitch as f64,backend)}
                    else {spektrafilm_model::diffusion::apply_diffusion_filter_um(&raw,&df.to_model(),pitch as f64)};
            }
            let density=self.develop_omitted_paper_raw(&raw,backend);
            let mut p=self.params.clone();p.io.scan_film=false;
            return stages::scanning::process(&density,&self.print,&p,backend,&crate::color_reference::ColorReference::identity(),&self.output_gamut);
        }
        let density=if self.film.is_omitted(){log}else{stages::filming::develop(&log,&self.film,&self.params,backend)};
        if self.film.is_positive() {
            let mut p=self.params.clone();p.io.scan_film=true;
            return stages::scanning::process(&density,&self.film,&p,backend,&crate::color_reference::ColorReference::identity(),&self.output_gamut);
        }
        self.omitted_density_to_rgb(&density,backend,true)
    }
    fn apply_donor_grain(&self,mut log:ImageBuf,backend:&dyn ComputeBackend)->ImageBuf {
        let g=&self.params.film_render.grain;
        if !g.active || g.amount==0. {return log;}
        let donor=self.donor_grain.as_ref().expect("enabled no-film grain must have validated donor");
        let mut reference=ImageBuf::new(log.width,log.height);
        for px in reference.data.chunks_exact_mut(3){for c in 0..3{px[c]=from_f64(donor.density[c]);}}
        let mut p=self.params.clone();p.film_render.grain.amount=1.;
        let noisy=stages::filming::grain_density(reference,&donor.profile,&p,backend);
        let baseline=donor.baseline(g);
        for (i,(out,noise)) in log.data.iter_mut().zip(noisy.data).enumerate(){*out=from_f64(crate::donor_grain::restore(*out as f64,noise as f64,baseline[i%3],g.amount));}
        log
    }
    pub(super) fn expose_omitted_paper_raw(&self,log_rgb:&ImageBuf)->ImageBuf {
        let c=self.omitted_calibration.as_ref().expect("calibrated omitted pipeline");
        let gray=c.paper_gray.expect("paper calibration");
        let mut raw=log_rgb.clone();
        for px in raw.data.chunks_exact_mut(3) {
            for channel in 0..3 {
                let light=10f64.powf((gray+REFERENCE_GRAY.log10()-px[channel] as f64).clamp(c.paper_log_bounds[0],c.paper_log_bounds[1]))
                    *self.params.enlarger.print_exposure as f64*c.paper_gain[channel];
                let flash=self.params.enlarger.preflash_exposure as f64 * 10f64.powf(-match channel {1=>self.params.enlarger.preflash_m_filter_shift as f64,2=>self.params.enlarger.preflash_y_filter_shift as f64,_=>0.}/100.);
                px[channel]=from_f64(light+flash);
            }
        }
        raw
    }
    pub(super) fn develop_omitted_paper_raw(&self,raw:&ImageBuf,backend:&dyn ComputeBackend)->ImageBuf {
        let mut log=raw.clone();for x in &mut log.data{*x=from_f64(((*x as f64).max(0.)+1e-10).log10());}
        stages::printing::develop(&log,&self.print,&self.params,backend)
    }
    pub(super) fn omitted_density_to_rgb(&self,density:&ImageBuf,backend:&dyn ComputeBackend,clip:bool)->ImageBuf {
        let mut rgb=density.clone();
        if self.film.is_omitted() {for x in &mut rgb.data{*x=from_f64((10f64.powf(*x as f64)-1e-10).max(0.));}}
        else {
            let bridge=self.omitted_calibration.as_ref().and_then(|c|c.negative).expect("negative readout calibration");
            for px in rgb.data.chunks_exact_mut(3) {
                let out=bridge.positive([px[0] as f64,px[1] as f64,px[2] as f64]);
                for c in 0..3{px[c]=from_f64(out[c]);}
            }
        }
        self.finish_omitted_rgb(rgb,backend,clip)
    }
    fn finish_omitted_rgb(&self,mut rgb:ImageBuf,backend:&dyn ComputeBackend,clip:bool)->ImageBuf {
        for px in rgb.data.chunks_exact_mut(3) {
            let source=[px[0] as f64,px[1] as f64,px[2] as f64];
            let output=match self.params.io.output_color_space.as_str() {
                "Display P3" => crate::output_color::mul(crate::output_color::XYZ_TO_P3,crate::output_color::mul(spektrafilm_math::colorspace::SRGB_TO_XYZ_F64,source)),
                "Adobe RGB (1998)" => crate::output_color::mul(crate::output_color::XYZ_TO_ADOBE,crate::output_color::mul(spektrafilm_math::colorspace::SRGB_TO_XYZ_F64,source)),
                _ => source,
            };
            let out=self.output_gamut.compress(output);
            for c in 0..3 {px[c]=from_f64(out[c]);}
        }
        if self.params.scanner.lens_blur>0. {rgb=backend.gaussian_blur(&rgb,self.params.scanner.lens_blur);}
        let [radius,amount]=self.params.scanner.unsharp_mask;
        if radius>0.&&amount>0. {rgb=spektrafilm_model::diffusion::apply_unsharp_mask(&rgb,radius,amount,backend);}
        if clip {self.apply_post_scan(rgb)}else{rgb}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn root()->std::path::PathBuf {Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
    fn params()->RuntimeParams {
        let mut p=RuntimeParams::default();p.camera.auto_exposure=false;
        p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;
        p.io.output_gamut_compress.algorithm="off".into();
        p.scanner.unsharp_mask=[0.,0.];p.scanner.lens_blur=0.;
        p.print_render.glare.active=false;p.film_render.glare.active=false;p.film_render.grain.active=false;
        p
    }
    #[test]
    fn neither_medium_has_no_hidden_stock_response() {
        let pipe=Pipeline::new_with_spectral(Profile::omitted(),Profile::omitted(),params(),&root()).unwrap();
        let input=vec![0.,0.01,0.1,0.18,0.3,0.7,0.9,1.,0.2];
        let output=pipe.process(ImageBuf::from_data(3,1,input.iter().copied().map(from_f64).collect()),&spektrafilm_gpu::cpu_backend::CpuBackend);
        for (a,b) in input.iter().zip(output.data) {assert!((a-b as f64).abs()<1e-6);}
        assert!(!pipe.params.film_render.grain.active);
        assert!(!pipe.params.film_render.halation.active);
        assert!(!pipe.params.film_render.dir_couplers.active);
    }
    #[test]
    fn omission_marker_never_identifies_as_a_real_stock() {
        let p=Profile::omitted();assert!(p.is_omitted());assert_eq!(p.info.stock.as_deref(),Some("none"));
        assert!(p.data.density_curves.is_empty());
        assert!(p.data.channel_density.is_empty());
    }
}
