//! Narrow, validated all-on-device API. Physical stages are supplied by the GPL core.
use serde::{Deserialize, Serialize};
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::{Profile,load_profile_by_name}};
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use std::{collections::HashMap, path::{Path,PathBuf}, sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering}}, time::Instant};

#[path="preview_reuse.rs"]
mod preview_reuse;

pub const MAX_PIXELS: usize = 768 * 768;
pub const MEMORY_FIXED_ESTIMATE_BYTES:u64=96*1024*1024;
pub const MEMORY_ESTIMATE_BYTES_PER_PIXEL:u64=512;
pub use crate::profile_catalog::{FILMS,PAPERS};

#[derive(Debug, Clone, Serialize)]
pub struct EngineError { pub code: &'static str, pub message: String }
impl EngineError { pub fn new(code: &'static str, message: impl Into<String>) -> Self { Self { code, message: message.into() } } }
impl std::fmt::Display for EngineError { fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f,"{}: {}",self.code,self.message) } }
impl std::error::Error for EngineError {}
pub type Result<T> = std::result::Result<T, EngineError>;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all="camelCase", deny_unknown_fields)]
pub struct MobileParams {
    #[serde(skip_serializing)]
    pub checkpoint_directory:Option<String>,
    /// Diagnostic opt-in only; never part of science, persistence, or public diagnostics.
    #[serde(skip_serializing, deserialize_with="deserialize_gpu_probe_capture_token")]
    pub gpu_probe_capture_token:Option<u64>,
    #[serde(skip_serializing_if="is_false")]
    pub gpu_diffusion_experimental:bool,
    #[serde(skip_serializing_if="is_false")]
    pub gpu_scanner_experimental:bool,
    #[serde(skip_serializing_if="is_false")]
    pub gpu_optical_experimental:bool,
    #[serde(skip_serializing_if="is_false")]
    pub gpu_exposure_experimental:bool,
    #[serde(skip_serializing_if="is_false")]
    pub gpu_dir_experimental:bool,
    #[serde(skip_serializing_if="is_false")]
    pub gpu_science_experimental:bool,
    /// Test-only execution choices, excluded from photographic/checkpoint identity.
    #[serde(skip_serializing)]
    pub export_optimization_mask:u32,
    #[serde(skip_serializing)]
    pub gpu_resource_reuse:bool,
    #[serde(skip_serializing)]
    pub preview_brightness_reuse:bool,
    pub schema_version: u32, pub film_stock: String, pub paper_stock: String, pub output_route:String,
    pub film_exposure_ev: f64, pub brightness_ev: f64, pub contrast: f64,
    pub warmth: f64, pub saturation: f64, pub grain_amount: f64, pub grain_size: f64,
    pub halation_amount: f64, pub halation_size: f64,
    pub diffusion_filter:String,pub diffusion_strength:f64,pub diffusion_size:f64,
    pub print_diffusion_filter:String,pub print_diffusion_strength:f64,pub print_diffusion_size:f64,
    pub print_yellow:f64,pub print_magenta:f64,pub preflash:f64,
    pub scan_sharpen_amount:f64,pub scan_sharpen_radius:f64,
    pub film_format_mm:f64,pub lens_blur_um:f64,pub scan_blur:f64,
    pub scatter_amount:f64,pub scatter_size:f64,pub halation_bounces:u32,pub halation_decay:f64,pub halation_normalize:bool,
    pub dir_amount:f64,pub dir_diffusion_um:f64,pub dir_same_layer:f64,pub dir_inter_layer:f64,
    pub grain_sublayers:bool,pub grain_layers:u32,pub grain_blur:f64,pub grain_dye_cloud:f64,pub grain_uniformity:f64, pub seed: u32, pub mode: String,
    /// Hidden operational budget, not a photographic control or allocator limit.
    pub memory_budget_mi_b:u32,
    pub output_color_space:String,
    pub output_gamut:bool,
    pub print_exposure_ev:f64,
    pub print_illuminant:String,
    pub print_compensation:bool,
    pub normalize_print_exposure:bool,
    pub preflash_yellow:f64,
    pub preflash_magenta:f64,
    pub scan_white_correction:bool,
    pub scan_black_correction:bool,
    pub scan_white_level:f64,
    pub scan_black_level:f64,
    pub auto_exposure:bool,
    pub metering:String,
    pub highlight_boost_ev:f64,
    pub highlight_boost_range:f64,
    pub highlight_protect_ev:f64,
    pub scan_glare_percent:f64,
    pub scan_glare_roughness:f64,
    pub scan_glare_blur:f64,
    pub grain_clump_size_um:f64,
    pub grain_clump_strength_nm:f64,
    pub use_gpu:bool,
    pub grain_red_scale:f64,pub grain_green_scale:f64,pub grain_blue_scale:f64,pub halation_color:String,
    pub neutral_filters_from_database:bool,
    pub neutral_cyan:f64,
    pub neutral_magenta:f64,
    pub neutral_yellow:f64,
    pub film_gamma:f64,
    pub paper_gamma:f64,
    pub paper_gamma_fast:f64,
    pub paper_gamma_slow:f64,
    pub paper_gamma_red:f64,
    pub paper_gamma_green:f64,
    pub paper_gamma_blue:f64,
    pub paper_developer_exhaustion:f64,
    /// Zero selects upstream floor-middle development family; positive minutes select nearest.
    pub film_development_time:f64,pub paper_development_time:f64,


}
impl Default for MobileParams {
    fn default() -> Self { Self { preview_brightness_reuse:false,gpu_resource_reuse:false,export_optimization_mask:0,checkpoint_directory:None,gpu_probe_capture_token:None,gpu_diffusion_experimental:false,gpu_scanner_experimental:false,gpu_optical_experimental:false,gpu_exposure_experimental:false,gpu_dir_experimental:false,gpu_science_experimental:false,schema_version:1,film_stock:"kodak_portra_400".into(),paper_stock:"kodak_portra_endura".into(),output_route:"print".into(),film_exposure_ev:0.,brightness_ev:0.,contrast:1.,warmth:0.,saturation:1.,grain_amount:1.,grain_size:1.,halation_amount:1.,halation_size:1.,diffusion_filter:"off".into(),diffusion_strength:0.5,diffusion_size:1.,print_diffusion_filter:"off".into(),print_diffusion_strength:0.5,print_diffusion_size:1.,print_yellow:0.,print_magenta:0.,preflash:0.,scan_sharpen_amount:0.,scan_sharpen_radius:0.7,film_format_mm:35.,lens_blur_um:0.,scan_blur:0.,scatter_amount:1.,scatter_size:1.,halation_bounces:3,halation_decay:0.5,halation_normalize:true,dir_amount:1.,dir_diffusion_um:20.,dir_same_layer:1.,dir_inter_layer:1.,grain_sublayers:true,grain_layers:1,grain_blur:0.65,grain_dye_cloud:1.,grain_uniformity:1.,seed:42,mode:"preview".into(),memory_budget_mi_b:384,output_color_space:"sRGB".into(),output_gamut:true,print_exposure_ev:0.,print_illuminant:"TH-KG3".into(),print_compensation:true,normalize_print_exposure:true,preflash_yellow:0.,preflash_magenta:0.,scan_white_correction:false,scan_black_correction:false,scan_white_level:0.98,scan_black_level:0.01,auto_exposure:false,metering:"center_weighted".into(),highlight_boost_ev:0.,highlight_boost_range:0.3,highlight_protect_ev:4.,scan_glare_percent:0.,scan_glare_roughness:0.7,scan_glare_blur:0.5,grain_clump_size_um:0.2,grain_clump_strength_nm:30.,use_gpu:true,grain_red_scale:1.,grain_green_scale:1.,grain_blue_scale:1.,halation_color:"stock".into(),neutral_filters_from_database:true,neutral_cyan:0.,neutral_magenta:65.,neutral_yellow:55.,film_gamma:1.,paper_gamma:1.,paper_gamma_fast:1.,paper_gamma_slow:1.,paper_gamma_red:1.,paper_gamma_green:1.,paper_gamma_blue:1.,paper_developer_exhaustion:0.,film_development_time:0.,paper_development_time:0., } }
}
fn is_false(value:&bool)->bool {!*value}
// Malformed diagnostic tokens cannot invalidate an otherwise valid export.
// Zero is an internal invalid-request sentinel, never an admitted capture token.
fn deserialize_gpu_probe_capture_token<'de,D:serde::Deserializer<'de>>(deserializer:D)->std::result::Result<Option<u64>,D::Error> {
    let value=Option::<serde_json::Value>::deserialize(deserializer)?;
    Ok(value.map(|v|v.as_u64().filter(|v|*v>0&&*v<=i64::MAX as u64).unwrap_or(0)))
}
impl MobileParams {
    pub fn parse(json: &str) -> Result<Self> {
        if json.len()>16_384 { return Err(EngineError::new("INVALID_PARAMS","Parameter JSON exceeds 16 KiB")); }
        let mut p: Self=serde_json::from_str(json).map_err(|e|EngineError::new("INVALID_PARAMS",e.to_string()))?;
        // Migrate historical halo toggle: scatter previously switched off together.
        let fields:serde_json::Value=serde_json::from_str(json).map_err(|_|EngineError::new("INVALID_PARAMS","Invalid JSON"))?;
        if fields.get("scatterAmount").is_none() && p.halation_amount==0. {p.scatter_amount=0.;}
        p.validate()?; Ok(p)
    }
    pub fn validate(&self)->Result<()> {
        if crate::export_optimizations::ExportOptimizations::from_bits(self.export_optimization_mask).is_none() {return Err(EngineError::new("INVALID_PARAMS","Unknown export optimization bits"));}
        if !(128..=512).contains(&self.memory_budget_mi_b) {return Err(EngineError::new("INVALID_BUDGET","memoryBudgetMiB must be an integer in 128..512"));}
        if self.schema_version!=1 {return Err(EngineError::new("UNSUPPORTED_SCHEMA","Only schemaVersion 1 is supported"));}
        if !crate::profile_catalog::valid_route(&self.film_stock,&self.paper_stock,&self.output_route) {return Err(EngineError::new("UNSUPPORTED_PROFILE","Profile/route not in the bundled supported catalog"));}
        if !["preview","export"].contains(&self.mode.as_str()) {return Err(EngineError::new("INVALID_PARAMS","mode must be preview or export"));}
        if !["off","glimmerglass","black_pro_mist","pro_mist","cinebloom"].contains(&self.diffusion_filter.as_str()) || !["off","glimmerglass","black_pro_mist","pro_mist","cinebloom"].contains(&self.print_diffusion_filter.as_str()){return Err(EngineError::new("INVALID_PARAMS","Unknown diffusion filter"));}
        if !(1..=5).contains(&self.halation_bounces)||!(1..=4).contains(&self.grain_layers){return Err(EngineError::new("INVALID_PARAMS","Layer/bounce count outside safe range"));}
        if !["sRGB","Display P3","Adobe RGB (1998)"].contains(&self.output_color_space.as_str()) || !["TH-KG3","D50","D55","D65"].contains(&self.print_illuminant.as_str()) || !["average","median","center_weighted","partial","matrix","multi_zone","highlight_weighted"].contains(&self.metering.as_str()) {return Err(EngineError::new("INVALID_PARAMS","Unknown output space, print light or meter"));}
        if self.output_color_space!="sRGB" && self.output_gamut {return Err(EngineError::new("INVALID_PARAMS","Output gamut compression currently requires sRGB; disable explicitly for wide gamut"));}
        if self.scan_black_level>=self.scan_white_level || (self.output_route=="direct"&&!crate::profile_catalog::is_reversal(&self.film_stock)&&(self.scan_black_correction||self.scan_white_correction)){return Err(EngineError::new("INVALID_PARAMS","Invalid scanner correction or endpoints"));}
        if !["stock","red","amber","neutral"].contains(&self.halation_color.as_str()){return Err(EngineError::new("INVALID_PARAMS","Unknown halation color"));}
        for (name,v,min,max) in [("filmDevelopmentTime",self.film_development_time,0.,12.),("paperDevelopmentTime",self.paper_development_time,0.,9.),("grainRedScale",self.grain_red_scale,0.25,3.),("grainGreenScale",self.grain_green_scale,0.25,3.),("grainBlueScale",self.grain_blue_scale,0.25,3.),("neutralCyan",self.neutral_cyan,0.,200.),("neutralMagenta",self.neutral_magenta,0.,200.),("neutralYellow",self.neutral_yellow,0.,200.),("filmGamma",self.film_gamma,0.5,2.),("paperGamma",self.paper_gamma,0.5,2.),("paperGammaFast",self.paper_gamma_fast,0.5,2.),("paperGammaSlow",self.paper_gamma_slow,0.5,2.),("paperGammaRed",self.paper_gamma_red,0.5,2.),("paperGammaGreen",self.paper_gamma_green,0.5,2.),("paperGammaBlue",self.paper_gamma_blue,0.5,2.),("paperDeveloperExhaustion",self.paper_developer_exhaustion,0.,1.),("printDiffusionStrength",self.print_diffusion_strength,0.,2.),("printDiffusionSize",self.print_diffusion_size,0.25,2.),("printExposureEv",self.print_exposure_ev,-4.0,4.0),("preflashYellow",self.preflash_yellow,-30.0,30.0),("preflashMagenta",self.preflash_magenta,-30.0,30.0),("scanWhiteLevel",self.scan_white_level,0.5,1.0),("scanBlackLevel",self.scan_black_level,0.0,0.2),("highlightBoostEv",self.highlight_boost_ev,0.0,3.0),("highlightBoostRange",self.highlight_boost_range,0.0,1.0),("highlightProtectEv",self.highlight_protect_ev,0.0,4.0),("scanGlarePercent",self.scan_glare_percent,0.0,5.0),("scanGlareRoughness",self.scan_glare_roughness,0.0,1.0),("scanGlareBlur",self.scan_glare_blur,0.0,20.0),("grainClumpSizeUm",self.grain_clump_size_um,0.0,100.0),("grainClumpStrengthNm",self.grain_clump_strength_nm,0.0,1000.0),("filmFormatMm",self.film_format_mm,8.,120.),("lensBlurUm",self.lens_blur_um,0.,100.),("scanBlur",self.scan_blur,0.,3.),("scatterAmount",self.scatter_amount,0.,2.),("scatterSize",self.scatter_size,0.25,3.),("halationDecay",self.halation_decay,0.,1.),("dirAmount",self.dir_amount,0.,2.),("dirDiffusionUm",self.dir_diffusion_um,0.,100.),("dirSameLayer",self.dir_same_layer,0.,2.),("dirInterLayer",self.dir_inter_layer,0.,2.),("grainBlur",self.grain_blur,0.,2.),("grainDyeCloud",self.grain_dye_cloud,0.,3.),("grainUniformity",self.grain_uniformity,0.5,1.),("diffusionStrength",self.diffusion_strength,0.,2.),("diffusionSize",self.diffusion_size,0.25,2.),("printYellow",self.print_yellow,-30.,30.),("printMagenta",self.print_magenta,-30.,30.),("preflash",self.preflash,0.,0.2),("scanSharpenAmount",self.scan_sharpen_amount,0.,2.),("scanSharpenRadius",self.scan_sharpen_radius,0.2,3.),("filmExposureEv",self.film_exposure_ev,-4.,4.),("brightnessEv",self.brightness_ev,-2.,2.),("contrast",self.contrast,0.5,1.5),("warmth",self.warmth,-1.,1.),("saturation",self.saturation,0.,2.),("grainAmount",self.grain_amount,0.,2.),("grainSize",self.grain_size,0.25,3.),("halationAmount",self.halation_amount,0.,2.),("halationSize",self.halation_size,0.25,3.)] {
            if !v.is_finite() || v<min || v>max {return Err(EngineError::new("INVALID_PARAMS",format!("{name} must be finite in [{min}, {max}]")));}
        } Ok(())
    }
    pub fn expanded_tile_pixel_budget(&self)->Result<u64> {
        self.validate()?;
        Ok((((self.memory_budget_mi_b as u64)*1024*1024-MEMORY_FIXED_ESTIMATE_BYTES)/MEMORY_ESTIMATE_BYTES_PER_PIXEL).min(2_097_152))
    }
    pub fn check_preview_budget(&self,pixels:u64)->Result<()> {
        if pixels>self.expanded_tile_pixel_budget()? {return Err(EngineError::new("MEMORY_BUDGET_TOO_SMALL","Preview exceeds its conservative native working-memory estimate; use a smaller explicit preview"));}
        Ok(())
    }
    pub(crate) fn runtime(&self)->RuntimeParams {
        let mut p=RuntimeParams::default();
        // Preview and export use the same spectral LUT precision; only geometry differs.
        p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;
        p.camera.auto_exposure=self.auto_exposure;p.camera.auto_exposure_method=self.metering.clone();p.camera.film_format_mm=self.film_format_mm as f32;p.camera.lens_blur_um=self.lens_blur_um as f32;p.scanner.lens_blur=self.scan_blur as f32;
        p.camera.exposure_compensation_ev=self.film_exposure_ev as f32;
        p.io.scan_film=self.output_route=="direct";p.io.input_color_space="sRGB".into(); p.io.output_color_space=self.output_color_space.clone();
        p.io.output_gamut_compress.algorithm=if self.output_gamut{"cam16ucs"}else{"off"}.into();
        p.io.input_cctf_decoding=false; p.io.output_cctf_encoding=false;
        p.scanner.unsharp_mask=if self.scan_sharpen_amount>0. {[self.scan_sharpen_radius as f32,self.scan_sharpen_amount as f32]}else{[0.,0.]};
        p.camera.diffusion_filter.active=self.diffusion_filter!="off" && self.diffusion_strength>0.;
        p.camera.diffusion_filter.filter_family=self.diffusion_filter.clone();
        p.camera.diffusion_filter.strength=self.diffusion_strength as f32;
        p.camera.diffusion_filter.spatial_scale=self.diffusion_size as f32;
        p.enlarger.diffusion_filter.active=self.print_diffusion_filter!="off" && self.print_diffusion_strength>0.;p.enlarger.diffusion_filter.filter_family=self.print_diffusion_filter.clone();p.enlarger.diffusion_filter.strength=self.print_diffusion_strength as f32;p.enlarger.diffusion_filter.spatial_scale=self.print_diffusion_size as f32;
        p.enlarger.m_filter_shift=self.print_magenta as f32;
        p.enlarger.preflash_exposure=self.preflash as f32;
        p.enlarger.print_exposure=2f32.powf(self.print_exposure_ev as f32);p.enlarger.illuminant=self.print_illuminant.clone();p.enlarger.print_exposure_compensation=self.print_compensation;p.enlarger.normalize_print_exposure=self.normalize_print_exposure;
        p.enlarger.preflash_y_filter_shift=self.preflash_yellow as f32;p.enlarger.preflash_m_filter_shift=self.preflash_magenta as f32;
        p.scanner.white_correction=self.scan_white_correction;p.scanner.black_correction=self.scan_black_correction;p.scanner.white_level=self.scan_white_level as f32;p.scanner.black_level=self.scan_black_level as f32;
        p.film_render.halation.boost_ev=self.highlight_boost_ev as f32;p.film_render.halation.boost_range=self.highlight_boost_range as f32;p.film_render.halation.protect_ev=self.highlight_protect_ev as f32;
        p.film_render.grain.micro_structure=[self.grain_clump_size_um as f32,self.grain_clump_strength_nm as f32];
        p.print_render.glare.active=self.scan_glare_percent>0.;p.print_render.glare.percent=self.scan_glare_percent as f32;p.print_render.glare.roughness=self.scan_glare_roughness as f32;p.print_render.glare.blur=self.scan_glare_blur as f32;p.film_render.glare=p.print_render.glare.clone(); // avoid unrelated stochastic scan veil in the pilot
        p.settings.neutral_print_filters_from_database=self.neutral_filters_from_database;
        p.enlarger.c_filter_neutral=self.neutral_cyan as f32;p.enlarger.m_filter_neutral=self.neutral_magenta as f32;p.enlarger.y_filter_neutral=self.neutral_yellow as f32;
        p.film_render.density_curve_gamma=self.film_gamma as f32;
        p.print_render.density_curve_gamma=self.paper_gamma as f32;
        let morph=&mut p.print_render.density_curves_morph;
        morph.active=[self.contrast,self.paper_gamma,self.paper_gamma_fast,self.paper_gamma_slow,self.paper_gamma_red,self.paper_gamma_green,self.paper_gamma_blue].iter().any(|v|(*v-1.).abs()>f64::EPSILON)||self.paper_developer_exhaustion>0.;
        morph.gamma_factor=self.contrast*self.paper_gamma;morph.gamma_factor_fast=self.paper_gamma_fast;morph.gamma_factor_slow=self.paper_gamma_slow;morph.gamma_factor_red=self.paper_gamma_red;morph.gamma_factor_green=self.paper_gamma_green;morph.gamma_factor_blue=self.paper_gamma_blue;morph.developer_exhaustion=self.paper_developer_exhaustion;
        p.enlarger.y_filter_shift=(-self.warmth*15.+self.print_yellow) as f32;
        p.film_render.dir_couplers.amount=self.saturation*self.dir_amount;
        p.film_render.dir_couplers.diffusion_size_um=self.dir_diffusion_um;
        p.film_render.dir_couplers.inhibition_samelayer=self.dir_same_layer;p.film_render.dir_couplers.inhibition_interlayer=self.dir_inter_layer;
        p.film_render.grain.sublayers_active=self.grain_sublayers;p.film_render.grain.n_sub_layers=self.grain_layers;p.film_render.grain.blur=self.grain_blur as f32;p.film_render.grain.blur_dye_clouds_um=self.grain_dye_cloud as f32;
        for x in &mut p.film_render.grain.uniformity{*x*=self.grain_uniformity;}

        for (v,scale) in p.film_render.grain.agx_particle_scale.iter_mut().zip([self.grain_red_scale,self.grain_green_scale,self.grain_blue_scale]){*v*=scale;}
        p.film_render.grain.active=self.grain_amount>0.;
        p.film_render.grain.amount=self.grain_amount;
        p.film_render.grain.seed=self.seed as u64;
        p.film_render.grain.agx_particle_area_um2*=self.grain_size*self.grain_size;
        p.film_render.halation.active=self.halation_amount>0.||self.scatter_amount>0.;
        p.film_render.halation.scatter_amount=self.scatter_amount;p.film_render.halation.scatter_spatial_scale=self.scatter_size;
        p.film_render.halation.halation_n_bounces=self.halation_bounces;p.film_render.halation.halation_bounce_decay=self.halation_decay;p.film_render.halation.halation_renormalize=self.halation_normalize;
        p.film_render.halation.halation_amount=self.halation_amount;
        p.film_render.halation.halation_spatial_scale=self.halation_size;
        p.film_render.development_time=if self.film_development_time>0. {Some(self.film_development_time)}else{None};
        p.print_render.development_time=if self.paper_development_time>0. {Some(self.paper_development_time)}else{None};
        // Added upstream BW pair entries exist for TH-KG3 only. Preserve manual
        // preferences for other illuminants; never invent a calibration.
        if (matches!(self.film_stock.as_str(),"kodak_doublex"|"kodak_trix") || self.paper_stock=="kodak_2302") && self.print_illuminant!="TH-KG3" {
            p.settings.neutral_print_filters_from_database=false;
        }
        // BW is a single silver-density channel, not three independently coloured layers.
        // Preserve saved colour controls but do not reinterpret them as silver controls.
        if matches!(self.film_stock.as_str(),"kodak_doublex"|"kodak_trix") {
            p.film_render.dir_couplers.amount=self.dir_amount;
            p.film_render.grain.agx_particle_scale=RuntimeParams::default().film_render.grain.agx_particle_scale;
        }
        if self.paper_stock=="kodak_2302" {
            // 2302's fitted model includes skew alphas not supported by the colour morph.
            // Use the supplied measured density table with scalar gamma, never a false fit.
            p.print_render.density_curves_morph.active=false;
            p.print_render.density_curve_gamma=(self.paper_gamma*self.contrast) as f32;
            p.enlarger.y_filter_shift=self.print_yellow as f32;
        }
        p
    }
    pub(crate) fn runtime_for_film(&self,film:&Profile)->RuntimeParams {
        let mut runtime=self.runtime();
        // Stock-derived physical baseline, matching the pinned Python builder.
        if film.is_film() && ["still","cine"].contains(&film.info.usage.as_str()) {
            let strength=match film.info.antihalation.as_str() {"strong"=>Some([0.015,0.005,0.]),"weak"=>Some([0.08,0.02,0.]),"no"=>Some([0.30,0.10,0.015]),_=>None};
            if let Some(strength)=strength {runtime.film_render.halation.halation_strength=strength;runtime.film_render.halation.halation_first_sigma_um=[if film.info.usage=="cine" {50.} else {65.};3];}
        }
        if self.halation_color!="stock" && !film.is_bw() {
            let total: f64=runtime.film_render.halation.halation_strength.iter().sum();
            let weights=match self.halation_color.as_str(){"red"=>[1.,0.,0.],"amber"=>[0.8,0.2,0.],_=>[1./3.;3]};
            runtime.film_render.halation.halation_strength=weights.map(|v|v*total);
        }
        runtime
    }
}

/// Per-handle, privacy-safe progress. Counters are local to the named stage.
pub struct ProgressState {
    job_id:u64,operation:&'static str,stage:&'static str,status:&'static str,
    done:u64,total:u64,pass_id:u64,update_serial:u64,display_stage:u8,stage_done:u64,stage_total:u64,started:Option<Instant>,finished_ms:Option<f64>,
}
impl Default for ProgressState {fn default()->Self{Self{job_id:0,operation:"none",stage:"idle",status:"idle",done:0,total:0,pass_id:0,update_serial:0,display_stage:1,stage_done:0,stage_total:0,started:None,finished_ms:None}}}
fn safe_stage(stage:&'static str)->&'static str {match stage {
    "print_diffusion"|"auto_exposure"|"highlight_boost"|"scan_glare"|"camera_blur"|"scan_blur"|"camera_diffusion"|"scan_sharpen"|"idle"|"validate"|"setup"|"projection"|"halation"|"scatter"|"film_log"|"film_density"|"dir"|"grain"|"printing_scanning"|"output"|"flush"|"cleanup"|"complete"|"preview"|"film_exposure"|"film_scatter"|"film_halation"|"film_scatter_halation"|"film_density_dir"|"dir_diffusion"|"dir_develop"|"grain_blur"|"grain_cloud"|"grain_sampling"|"grain_microstructure"=>stage,
    _=>"processing",
}}
pub fn progress_begin(engine:&Engine,operation:&'static str)->u64 {
    if let Ok(mut p)=engine.progress.lock(){p.job_id=p.job_id.wrapping_add(1);p.operation=operation;p.stage="setup";p.status="running";p.done=0;p.total=0;p.pass_id=1;p.update_serial=1;p.display_stage=1;p.stage_done=0;p.stage_total=0;p.started=Some(Instant::now());p.finished_ms=None;p.job_id}else{0}
}
pub fn progress_update(engine:&Engine,stage:&'static str,done:u64,total:u64) {
    if let Ok(mut p)=engine.progress.lock(){let stage=safe_stage(stage);let done=done.min(total);if p.stage!=stage||done<p.done {p.pass_id=p.pass_id.saturating_add(1);}p.stage=stage;p.done=done;p.total=total;p.update_serial=p.update_serial.saturating_add(1);}
}
/// The raw pass and aggregate stage are one snapshot, even under concurrent JNI polling.
pub fn progress_update_export(engine:&Engine,event:crate::streamed_progress::ExportProgress) {
    if let Ok(mut p)=engine.progress.lock(){
        let stage=safe_stage(event.phase);let done=event.done.min(event.total);
        if p.stage!=stage||done<p.done {p.pass_id=p.pass_id.saturating_add(1);}
        p.stage=stage;p.done=done;p.total=event.total;p.update_serial=p.update_serial.saturating_add(1);
        p.display_stage=event.display_stage;p.stage_done=event.stage_done;p.stage_total=event.stage_total;
    }
}
pub fn progress_finish(engine:&Engine,job_id:u64,code:Option<&str>)->Option<&'static str> {
    if let Ok(mut p)=engine.progress.lock(){if p.job_id!=job_id{return None;}p.finished_ms=Some(p.started.map_or(0.,|t|t.elapsed().as_secs_f64()*1000.));p.status=match code{None=>"completed",Some("CANCELLED")=>"cancelled",Some(_)=>"failed"};if code.is_none(){p.stage="complete";p.done=p.total;}if p.stage=="setup"{None}else{Some(p.stage)}}else{None}
}
pub fn progress_json(engine:&Engine)->Result<String> {
    let p=engine.progress.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Progress unavailable"))?;
    Ok(serde_json::json!({"ok":true,"schemaVersion":1,"jobId":p.job_id,"operation":p.operation,"stage":p.stage,"status":p.status,"done":p.done,"total":p.total,"elapsedMs":p.finished_ms.unwrap_or_else(||p.started.map_or(0.,|t|t.elapsed().as_secs_f64()*1000.)),"passId":p.pass_id,"updateSerial":p.update_serial,"counterScope":"current-pass","displayStage":p.display_stage,"stageDone":p.stage_done,"stageTotal":p.stage_total,"stageCounterScope":"stage-work","active":p.status=="running"}).to_string())
}
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct ExportPlan {pub checkpoint_reserve_bytes:u64,pub width:u32,pub height:u32,pub pixels:u64,pub input_bytes:u64,pub output_bytes:u64,pub estimated_scratch_bytes:u64,pub estimated_total_local_bytes:u64,pub memory_budget_mi_b:u32,pub pointwise_chunk_pixels:u32,pub estimated_native_working_bytes:u64,pub spatial_working_buffer_bytes:u64,pub estimate_policy:&'static str}
pub fn plan_export(width:i32,height:i32,params:&MobileParams)->Result<ExportPlan>{
    params.validate()?;
    if width<=0||height<=0||width>40000||height>40000{return Err(EngineError::new("INVALID_DIMENSIONS","Export dimensions must be 1..40000"));}
    let pixels=(width as u64).checked_mul(height as u64).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Pixel count overflow"))?;
    let input_bytes=pixels.checked_mul(12).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Byte count overflow"))?;
    let estimated_scratch_bytes=crate::streamed::estimated_scratch_with_diffusion(width as u32,height as u32,(params.diffusion_filter!="off"&&params.diffusion_strength>0.)||(params.output_route=="print"&&params.print_diffusion_filter!="off"&&params.print_diffusion_strength>0.))?;
    let estimated_total_local_bytes=estimated_scratch_bytes.checked_add(input_bytes).and_then(|v|v.checked_add(input_bytes)).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Total byte count overflow"))?;
    Ok(ExportPlan{checkpoint_reserve_bytes:pixels*48+1024*1024,width:width as u32,height:height as u32,pixels,input_bytes,output_bytes:input_bytes,estimated_scratch_bytes,estimated_total_local_bytes,memory_budget_mi_b:params.memory_budget_mi_b,pointwise_chunk_pixels:(width as u32).max(32768),estimated_native_working_bytes:115*1024*1024+256*(width as u64).max(32768)+crate::export_execution::EXPORT_STACK_RESERVE_BYTES,spatial_working_buffer_bytes:16*1024*1024,estimate_policy:"Conservative disk reservation: max of 128 bytes/pixel plus64MiB and exact active-diffusion FFT workspace; total includes linear input and output, excludes encoded image and source private copy. Persistent queue jobs additionally reserve checkpointReserveBytes (48 bytes/pixel plus1MiB). Not measured actual usage or a whole-process RAM guarantee."})
}

pub struct Engine {
    pub data_dir: PathBuf,
    pub cancel_generation: AtomicU64,
    pub destroyed: AtomicBool,
    pub render_lock: Mutex<()>,
    pub progress:Mutex<ProgressState>,
    preview_cache:Mutex<preview_reuse::Cache>,
}
static ENGINES: OnceLock<Mutex<HashMap<i64, Arc<Engine>>>>=OnceLock::new();
static NEXT_HANDLE: AtomicI64=AtomicI64::new(1);
fn engines()->&'static Mutex<HashMap<i64,Arc<Engine>>> { ENGINES.get_or_init(||Mutex::new(HashMap::new())) }
pub fn create(data_dir: &str)->Result<i64> {
    let path=PathBuf::from(data_dir).canonicalize().map_err(|e|EngineError::new("MISSING_DATA",e.to_string()))?;
    if !path.is_dir() {return Err(EngineError::new("MISSING_DATA","Data path is not a directory"));}
    for p in ["luts/spectral_upsampling/irradiance_xy_tc.npy","filters/neutral_print_filters.json","profiles/kodak_portra_400.json","profiles/kodak_portra_endura.json"] {
        if !path.join(p).is_file() {return Err(EngineError::new("MISSING_DATA",format!("Required bundled data missing: {p}")));}
    }
    let id=NEXT_HANDLE.fetch_add(1,Ordering::Relaxed);
    if id<=0 {return Err(EngineError::new("INTERNAL_ERROR","Handle space exhausted"));}
    let mut registry=engines().lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Handle registry unavailable"))?;
    registry.insert(id,Arc::new(Engine {data_dir:path,cancel_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),preview_cache:Mutex::new(preview_reuse::Cache::default())}));
    Ok(id)
}
pub fn get(id:i64)->Result<Arc<Engine>> {
    engines().lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Handle registry unavailable"))?.get(&id).cloned().ok_or_else(||EngineError::new("INVALID_HANDLE","Engine handle is absent or already destroyed"))
}
pub fn cancel(id:i64) {if let Ok(engine)=get(id) {engine.cancel_generation.fetch_add(1,Ordering::AcqRel);}}
pub fn destroy(id:i64) {
    if let Ok(mut registry)=engines().lock() {if let Some(engine)=registry.remove(&id) {engine.destroyed.store(true,Ordering::Release);engine.cancel_generation.fetch_add(1,Ordering::AcqRel);}}
}
pub fn required_bytes(width:i32,height:i32)->Result<usize> {
    if width<=0 || height<=0 {return Err(EngineError::new("INVALID_DIMENSIONS","Dimensions must be positive"));}
    let pixels=(width as usize).checked_mul(height as usize).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Dimensions overflow"))?;
    if pixels>MAX_PIXELS {return Err(EngineError::new("IMAGE_TOO_LARGE",format!("Direct-buffer previews support at most {MAX_PIXELS} pixels; use nativeExportFile for original-size bounded-memory export")));}
    pixels.checked_mul(12).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Byte count overflow"))
}
pub fn check_cancellation(engine:&Engine,generation:u64)->Result<()> {
    if engine.destroyed.load(Ordering::Acquire) || engine.cancel_generation.load(Ordering::Acquire)!=generation {Err(EngineError::new("CANCELLED","Result cancelled; output is not valid"))} else {Ok(())}
}
pub struct RenderOutput {pub preview_reuse_hit:bool,pub preview_cache_bytes:usize,pub metered_ev:Option<f32>,pub gpu:crate::adreno::GpuDiagnostics,pub pixels:Vec<f32>,pub elapsed_ms:f64,pub warnings:Vec<&'static str>}
pub fn render(engine:&Engine,generation:u64,input:&[f32],width:u32,height:u32,params:&MobileParams)->Result<RenderOutput> {
    let bytes=required_bytes(width as i32,height as i32)?;
    params.validate()?;
    params.check_preview_budget(width as u64*height as u64)?;
    if input.len()!=bytes/4 {return Err(EngineError::new("INVALID_BUFFER","Input length mismatch"));}
    if input.iter().any(|x|!x.is_finite()) {return Err(EngineError::new("INVALID_PIXELS","All input samples must be finite"));}
    check_cancellation(engine,generation)?;
    let _interactive=crate::export_execution::interactive_work();
    let start=Instant::now();
    let reuse=params.preview_brightness_reuse && !params.use_gpu;
    let dependency=if reuse {Some(preview_reuse::dependencies(engine,generation)?)}else{None};
    let identity=if let Some(dependency)=&dependency {
        let mut science=params.clone();science.brightness_ev=0.;
        format!("{}:{}:{}:{}",crate::RUST_REFERENCE,crate::PYTHON_REFERENCE,dependency,serde_json::to_string(&science).map_err(|_|EngineError::new("INVALID_PARAMS","Cannot identify scientific parameters"))?)
    }else{String::new()};
    let mut cache=engine.preview_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Preview cache unavailable"))?;
    if !reuse {cache.clear();}
    let cached=if reuse {cache.find(&identity,input,width,height)}else{None};
    let preview_reuse_hit=cached.is_some();
    let backend=crate::adreno::AdrenoDensityBackend::with_options(params.use_gpu,params.gpu_resource_reuse);
    let (output,metered_ev)=if let Some(cached)=cached {cached}else {
        let film=load_profile_by_name(&engine.data_dir,&params.film_stock).map_err(|e|EngineError::new("PROFILE_ERROR",e.to_string()))?;
        let print=load_profile_by_name(&engine.data_dir,&params.paper_stock).map_err(|e|EngineError::new("PROFILE_ERROR",e.to_string()))?;
        let runtime=params.runtime_for_film(&film);
        let pipe=Pipeline::new_with_spectral(film,print,runtime,&engine.data_dir).map_err(|e|EngineError::new("PIPELINE_ERROR",e))?;
        check_cancellation(engine,generation)?;
        let image=ImageBuf::from_data(width,height,input.iter().map(|&x|from_f64(x as f64)).collect());
        let metered_ev=if pipe.params.camera.auto_exposure {
            let matrix=spektrafilm_core::stages::filming::input_colorspace_to_xyz(&pipe.params.io.input_color_space);
            Some(spektrafilm_core::stages::filming::measure_autoexposure_ev(&image,&matrix,&pipe.params.camera.auto_exposure_method))
        }else{None};
        let output=pipe.process(image,&backend);
        check_cancellation(engine,generation)?;
        (output,metered_ev)
    };
    check_cancellation(engine,generation)?;
    if output.width != width || output.height != height || output.data.len() != bytes/4 {
        return Err(EngineError::new("INVALID_OUTPUT_SHAPE", "Physical pipeline returned dimensions or buffer length different from the requested image"));
    }
    let exposure=2.0f64.powf(params.brightness_ev);
    let pixels:Vec<f32>=output.data.chunks_exact(3).flat_map(|v| {
        let rgb=spektrafilm_core::output_color::to_linear_srgb([v[0]as f64,v[1]as f64,v[2]as f64],&params.output_color_space);
        rgb.into_iter().map(|x|(x*exposure)as f32)
    }).collect();
    if pixels.iter().any(|x|!x.is_finite()) {return Err(EngineError::new("NONFINITE_OUTPUT","Rendering produced a nonfinite sample"));}
    if reuse && !preview_reuse_hit {
        if dependency.as_ref()==Some(&preview_reuse::dependencies(engine,generation)?) {
            check_cancellation(engine,generation)?;
            cache.insert(identity,input,output,metered_ev);
        } else {cache.clear();}
    }
    let preview_cache_bytes=cache.bytes();
    Ok(RenderOutput {preview_reuse_hit,preview_cache_bytes,metered_ev,gpu:backend.diagnostics(),pixels,elapsed_ms:start.elapsed().as_secs_f64()*1000.,warnings:vec!["Direct-buffer preview: maximum 589824 pixels. Use nativeExportFile for original-size export; never silently reduce the original.","Float32 input/output ABI is not a bit-exact float64 Python reference; see precision for internal arithmetic.","The same seed is deterministic within each path, but preview and full-size export use different grain sampling layouts and need not match pixel-for-pixel.","Cancellation is cooperative before/after the pipeline, not immediate."]})
}
/// Never expose arbitrary upstream errors: they can contain paths or input text.
pub fn public_error_message(code:&str)->&'static str {match code {
    "INVALID_PARAMS"|"INVALID_PARAMETERS"=>"Parameters are invalid or outside supported ranges",
    "UNSUPPORTED_SCHEMA"=>"Parameter schema is not supported",
    "UNSUPPORTED_PROFILE"=>"Selected stock is not bundled",
    "INVALID_DIMENSIONS"=>"Image dimensions are invalid",
    "IMAGE_TOO_LARGE"=>"Preview exceeds the direct-buffer limit; use original-size file export",
    "INVALID_BUFFER"=>"Input or output buffer does not meet the native contract",
    "INVALID_PIXELS"=>"Input contains nonfinite samples",
    "INVALID_OUTPUT_SHAPE"=>"Rendered dimensions do not match the request",
    "NONFINITE_OUTPUT"=>"Rendering produced a nonfinite sample",
    "INVALID_HANDLE"=>"Engine handle is absent or destroyed",
    "ENGINE_BUSY"=>"One render or export is already active on this handle",
    "CANCELLED"=>"Operation cancelled; output is invalid",
    "INVALID_PATH"|"EXPORT_PATH_POLICY"=>"Spool path violates the private app file policy",
    "INVALID_INPUT_FILE"=>"Input spool is missing or has an invalid size",
    "MISSING_DATA"|"PROFILE_ERROR"=>"Required bundled engine data is unavailable or invalid",
    "INVALID_BUDGET"=>"Tile memory budget is invalid",
    "MEMORY_BUDGET_TOO_SMALL"=>"Physical effect support exceeds the tile memory budget; no resizing performed",
    "UNSUPPORTED_EXPORT_OPTIONS"=>"Requested options are not supported by original-size export",
    "IO_ERROR"=>"Local spool read or write failed",
    "PIPELINE_ERROR"=>"Physical pipeline initialization failed",
    "JNI_ERROR"=>"Native interface validation failed",
    "ENGINE_PANIC"=>"Native operation failed safely; output is invalid",
    _=>"Native engine operation failed",
}}
pub fn error_json(error:EngineError,operation:&'static str,stage:&'static str,elapsed_ms:f64)->String {
    serde_json::json!({"ok":false,"schemaVersion":1,"code":error.code,"message":public_error_message(error.code),"outputValid":false,"operation":operation,"stage":stage,"elapsedMs":elapsed_ms}).to_string()
}

/// Restrict spool paths to the private app roots inferred from files/engine-data.
/// nativeCreate's dataDirectory is supplied by the trusted app, not a file picker.
pub fn private_spool_paths(engine:&Engine,input:&str,output:&str)->Result<(PathBuf,PathBuf)> {
    if input.len()>4096||output.len()>4096||!Path::new(input).is_absolute()||!Path::new(output).is_absolute() {
        return Err(EngineError::new("INVALID_PATH","Spool paths must be absolute local paths under private files/cache"));
    }
    let files=engine.data_dir.parent().filter(|p|p.file_name().and_then(|n|n.to_str())==Some("files"))
        .ok_or_else(||EngineError::new("EXPORT_PATH_POLICY","Export requires bundled data at the app's private files/engine-data directory"))?;
    if engine.data_dir.file_name().and_then(|n|n.to_str())!=Some("engine-data") {return Err(EngineError::new("EXPORT_PATH_POLICY","Expected private files/engine-data"));}
    let app=files.parent().ok_or_else(||EngineError::new("EXPORT_PATH_POLICY","Private app root unavailable"))?;
    let cache=app.join("cache").canonicalize().ok().filter(|p|p.parent()==Some(app));
    let allowed=|p:&Path| (p.starts_with(files)||cache.as_ref().is_some_and(|c|p.starts_with(c))) && !p.starts_with(&engine.data_dir);
    let input=PathBuf::from(input).canonicalize().map_err(|e|EngineError::new("INVALID_INPUT_FILE",e.to_string()))?;
    if !allowed(&input)||!input.is_file(){return Err(EngineError::new("INVALID_PATH","Input must be a regular spool inside private files/cache, outside bundled data"));}
    let requested=Path::new(output);
    let name=requested.file_name().ok_or_else(||EngineError::new("INVALID_PATH","Output filename missing"))?;
    let parent=requested.parent().ok_or_else(||EngineError::new("INVALID_PATH","Output parent missing"))?.canonicalize().map_err(|e|EngineError::new("INVALID_PATH",e.to_string()))?;
    let output=parent.join(name);
    if !allowed(&output)||input==output||std::fs::symlink_metadata(&output).is_ok(){return Err(EngineError::new("INVALID_PATH","Output must be a new private spool; existing paths and bundled-data directories are forbidden"));}
    Ok((input,output))
}

/// Resolve the existing job parent, not the checkpoint itself: it does not exist
/// on a new job. Android may supply an alias of the app root while nativeCreate
/// and private_spool_paths have already canonicalized their paths.
fn private_checkpoint_path(engine:&Engine,input:&Path,output:&Path,requested:&str)->Result<PathBuf> {
    let invalid=||EngineError::new("INVALID_PATH","Checkpoint must belong to this immutable private queue job");
    let requested=Path::new(requested);
    if requested.as_os_str().len()>4096 || !requested.is_absolute() || requested.file_name().and_then(|n|n.to_str())!=Some("checkpoint") {return Err(invalid());}
    let job=input.parent().ok_or_else(invalid)?;
    let jobs=engine.data_dir.parent().ok_or_else(invalid)?.join("export-jobs");
    let id=job.file_name().and_then(|s|s.to_str()).unwrap_or("");
    let valid_id=id.len()==36 && id.bytes().enumerate().all(|(i,b)|if [8,13,18,23].contains(&i){b==b'-'}else{b.is_ascii_hexdigit()});
    let requested_parent=requested.parent().ok_or_else(invalid)?.canonicalize().map_err(|_|invalid())?;
    if job.parent()!=Some(jobs.as_path()) || !valid_id || output.parent()!=Some(job) || input.file_name().and_then(|n|n.to_str())!=Some("input.rgb") || requested_parent!=job {return Err(invalid());}
    let checkpoint=job.join("checkpoint");
    // Never follow a checkpoint symlink, even when it points back inside the job.
    match std::fs::symlink_metadata(&checkpoint) {
        Ok(metadata) if !metadata.is_dir()=>return Err(invalid()),
        Err(error) if error.kind()!=std::io::ErrorKind::NotFound=>return Err(invalid()),
        _=>{}
    }
    Ok(checkpoint)
}

/// The caller may request only a token. Derive its fresh destination from the
/// trusted app root, and reject links/missing roots without failing the export.
fn private_gpu_probe_request(app:&Path,token:u64)->crate::streamed::GpuProbeCaptureRequest {
    use crate::streamed::GpuProbeCaptureRequest;
    if token==0||token>i64::MAX as u64 {return GpuProbeCaptureRequest::Invalid;}
    let cache=app.join("cache");let root=cache.join("gpu-probes");
    for dir in [&cache,&root] {
        if !std::fs::symlink_metadata(dir).is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink())
            || dir.canonicalize().ok().as_ref()!=Some(dir) {return GpuProbeCaptureRequest::Invalid;}
    }
    let snapshot_dir=root.join(format!("probe-{token}"));
    if !matches!(std::fs::symlink_metadata(&snapshot_dir),Err(e) if e.kind()==std::io::ErrorKind::NotFound) {return GpuProbeCaptureRequest::Invalid;}
    GpuProbeCaptureRequest::Capture{token,snapshot_dir}
}

/// One shared process-wide monotonic token allocator for preview and export.
/// Exhaustion fails closed (token 0), never wraps into a previously owned job.
pub(crate) fn gpu_stage_requests(cache:&Path,parent_handle:i64,params:&MobileParams,delegates:crate::streamed::GpuStageDelegates)->crate::streamed::GpuStageRequests {
    let request=|enabled:bool,delegate:Option<Box<dyn crate::streamed::GpuDiffusionDelegate>>| {
        if !enabled{return None;}
        static NEXT_GPU_TOKEN:AtomicU64=AtomicU64::new(0);
        let clock=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).ok().map(|t|t.as_nanos().min((i64::MAX-1)as u128)as u64).unwrap_or(1);
        let previous=NEXT_GPU_TOKEN.fetch_update(Ordering::Relaxed,Ordering::Relaxed,|last|last.checked_add(1).filter(|v|*v<=i64::MAX as u64).map(|v|v.max(clock)));
        let token=previous.map(|last|(last+1).max(clock)).unwrap_or(0);let root=cache.join("gpu-render");
        let stage_dir=if token>0&&std::fs::symlink_metadata(&root).is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink())&&root.canonicalize().ok().as_ref()==Some(&root){Some(root.join(format!("gpu-stage-{token}")))}else{None};
        Some(crate::streamed::GpuDiffusionRequest{parent_handle,token,stage_dir,delegate})
    };
    let mut science_delegates=delegates.science;
    crate::streamed::GpuStageRequests {
        route:crate::streamed::ScienceRoute::Export,
        diffusion:request(params.gpu_diffusion_experimental,delegates.diffusion),scanner:request(params.gpu_scanner_experimental,delegates.scanner),optical:request(params.gpu_optical_experimental,delegates.optical),exposure:request(params.gpu_exposure_experimental,delegates.exposure),dir:request(params.gpu_dir_experimental,delegates.dir),
        science:crate::streamed::ScienceKind::ALL.into_iter().filter_map(|kind|{let delegate=science_delegates.remove(&kind);request(params.gpu_science_experimental,delegate).map(|r|(kind,r))}).collect(),
    }
}
pub(crate) fn clear_preview_cache(engine:&Engine)->Result<()> {
    engine.preview_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Preview cache unavailable"))?.clear();Ok(())
}

pub fn export(engine:&Engine,generation:u64,input:&str,output:&str,width:i32,height:i32,params:&MobileParams)->Result<crate::streamed::StreamedMetrics> {
    export_with_gpu_delegate(engine,generation,0,input,output,width,height,params,None)
}
pub(crate) fn export_with_gpu_delegate(engine:&Engine,generation:u64,parent_handle:i64,input:&str,output:&str,width:i32,height:i32,params:&MobileParams,delegate:Option<Box<dyn crate::streamed::GpuDiffusionDelegate>>)->Result<crate::streamed::StreamedMetrics> {
    export_with_gpu_delegates(engine,generation,parent_handle,input,output,width,height,params,crate::streamed::GpuStageDelegates{diffusion:delegate,..crate::streamed::GpuStageDelegates::default()})
}
pub(crate) fn export_with_gpu_delegates(engine:&Engine,generation:u64,parent_handle:i64,input:&str,output:&str,width:i32,height:i32,params:&MobileParams,delegates:crate::streamed::GpuStageDelegates)->Result<crate::streamed::StreamedMetrics> {
    let _plan=plan_export(width,height,params)?;
    let (input,output)=private_spool_paths(engine,input,output)?;
    check_cancellation(engine,generation)?;
    let execution=crate::export_execution::ExportPermit::acquire(||check_cancellation(engine,generation).is_err()).ok_or_else(||EngineError::new("CANCELLED","Export cancelled while waiting for CPU admission"))?;
    let film=load_profile_by_name(&engine.data_dir,&params.film_stock).map_err(|e|EngineError::new("PROFILE_ERROR",e.to_string()))?;
    let mut runtime=params.runtime_for_film(&film);
    runtime.settings.use_enlarger_lut=true;runtime.settings.use_scanner_lut=true;runtime.settings.lut_resolution=33;
    let app=engine.data_dir.parent().and_then(|p|p.parent()).ok_or_else(||EngineError::new("EXPORT_PATH_POLICY","Private app root unavailable"))?;
    let cache=app.join("cache").canonicalize().map_err(|_|EngineError::new("EXPORT_PATH_POLICY","Private cache unavailable"))?;
    if cache.parent()!=Some(app) {return Err(EngineError::new("EXPORT_PATH_POLICY","Cache cannot escape private app root"));}
    static NEXT_SCRATCH:AtomicU64=AtomicU64::new(1);
    let nonce=NEXT_SCRATCH.fetch_add(1,Ordering::Relaxed);
    let epoch=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|_|EngineError::new("INTERNAL_ERROR","Scratch identity unavailable"))?.as_nanos();
    let scratch=cache.join(format!("film-native-v5-{generation}-{epoch}-{nonce}"));
    let checkpoint=params.checkpoint_directory.as_deref()
        .map(|requested|private_checkpoint_path(engine,&input,&output,requested)).transpose()?;
    let gpu_probe_capture=if params.gpu_diffusion_experimental {None}else{params.gpu_probe_capture_token.map(|token|private_gpu_probe_request(app,token))};
    let gpu_stages=gpu_stage_requests(&cache,parent_handle,params,delegates);
    // Working scratch remains disposable; only committed stage snapshots persist.
    let mut result=execution.run(||crate::streamed::export_file_checkpointed_managed_with_gpu_delegates(&execution,&engine.data_dir,&params.film_stock,&params.paper_stock,runtime,&input,&output,&scratch,checkpoint.as_deref(),width as u32,height as u32,params.memory_budget_mi_b,params.brightness_ev,params.use_gpu,crate::export_optimizations::ExportOptimizations {gpu_resource_reuse:params.gpu_resource_reuse,..crate::export_optimizations::ExportOptimizations::from_bits(params.export_optimization_mask).expect("validated mask")},gpu_probe_capture,gpu_stages,||execution.checkpoint(||check_cancellation(engine,generation).is_err()),|event|progress_update_export(engine,event)))?;
    result.export_execution=Some(execution.metrics());
    Ok(result)
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn gpu_experimental_policy_is_false_by_default_and_only_serializes_true() {
        let baseline=MobileParams::default();assert!(!baseline.gpu_diffusion_experimental);
        let normal=serde_json::to_value(&baseline).unwrap();assert!(normal.get("gpuDiffusionExperimental").is_none());
        let enabled=MobileParams::parse(r#"{"gpuDiffusionExperimental":true}"#).unwrap();assert!(enabled.gpu_diffusion_experimental);
        assert_eq!(serde_json::to_value(&enabled).unwrap()["gpuDiffusionExperimental"],true);
        // Numeric photographic parameters are unchanged; the stage's requested
        // execution policy is added independently to checkpoint identity.
        assert_eq!(serde_json::to_value(baseline.runtime()).unwrap(),serde_json::to_value(enabled.runtime()).unwrap());
    }
    #[test] fn gpu_scanner_policy_is_independent_explicit_and_false_by_default() {
        let baseline=MobileParams::default();assert!(!baseline.gpu_scanner_experimental);
        assert!(serde_json::to_value(&baseline).unwrap().get("gpuScannerExperimental").is_none());
        for diffusion in [false,true] {
            let p=MobileParams::parse(&format!(r#"{{"gpuScannerExperimental":true,"gpuDiffusionExperimental":{diffusion}}}"#)).unwrap();
            assert!(p.gpu_scanner_experimental);assert_eq!(p.gpu_diffusion_experimental,diffusion);
            assert_eq!(serde_json::to_value(&p).unwrap()["gpuScannerExperimental"],true);
            assert_eq!(serde_json::to_value(p.runtime()).unwrap(),serde_json::to_value(baseline.runtime()).unwrap());
        }
        assert!(MobileParams::parse(r#"{"gpuScannerExperimental":"true"}"#).is_err());
        assert!(MobileParams::parse(r#"{"gpuScannerExperimental":1}"#).is_err());
    }
    #[test] fn gpu_optical_policy_is_independent_explicit_and_false_by_default() {
        let baseline=MobileParams::default();assert!(!baseline.gpu_optical_experimental);
        assert!(serde_json::to_value(&baseline).unwrap().get("gpuOpticalExperimental").is_none());
        let p=MobileParams::parse(r#"{"gpuOpticalExperimental":true}"#).unwrap();
        assert!(p.gpu_optical_experimental&&!p.gpu_scanner_experimental&&!p.gpu_diffusion_experimental);
        assert_eq!(serde_json::to_value(&p).unwrap()["gpuOpticalExperimental"],true);
        assert_eq!(serde_json::to_value(p.runtime()).unwrap(),serde_json::to_value(baseline.runtime()).unwrap());
        assert!(MobileParams::parse(r#"{"gpuOpticalExperimental":"true"}"#).is_err());
    }
    #[test] fn gpu_exposure_policy_is_independent_explicit_and_false_by_default() {
        let baseline=MobileParams::default();assert!(!baseline.gpu_exposure_experimental);
        assert!(serde_json::to_value(&baseline).unwrap().get("gpuExposureExperimental").is_none());
        let p=MobileParams::parse(r#"{"gpuExposureExperimental":true}"#).unwrap();
        assert!(p.gpu_exposure_experimental&&!p.gpu_scanner_experimental&&!p.gpu_diffusion_experimental&&!p.gpu_optical_experimental);
        assert_eq!(serde_json::to_value(&p).unwrap()["gpuExposureExperimental"],true);
        assert_eq!(serde_json::to_value(p.runtime()).unwrap(),serde_json::to_value(baseline.runtime()).unwrap());
        assert!(MobileParams::parse(r#"{"gpuExposureExperimental":"true"}"#).is_err());
    }
    #[test] fn gpu_dir_policy_is_independent_explicit_and_false_by_default() {
        let baseline=MobileParams::default();assert!(!baseline.gpu_dir_experimental);
        assert!(serde_json::to_value(&baseline).unwrap().get("gpuDirExperimental").is_none());
        let p=MobileParams::parse(r#"{"gpuDirExperimental":true}"#).unwrap();assert!(p.gpu_dir_experimental);
        assert!(!p.gpu_exposure_experimental&&!p.gpu_scanner_experimental&&!p.gpu_diffusion_experimental&&!p.gpu_optical_experimental);
        assert_eq!(serde_json::to_value(&p).unwrap()["gpuDirExperimental"],true);
        assert_eq!(serde_json::to_value(p.runtime()).unwrap(),serde_json::to_value(baseline.runtime()).unwrap());
        assert!(MobileParams::parse(r#"{"gpuDirExperimental":"true"}"#).is_err());
    }
    #[test] fn gpu_probe_capture_token_never_changes_science_or_serialized_parameters() {
        let baseline=MobileParams::default();let serialized=serde_json::to_string(&baseline).unwrap();let runtime=serde_json::to_string(&baseline.runtime()).unwrap();
        for token in [1,i64::MAX as u64] {
            let p=MobileParams::parse(&format!(r#"{{"gpuProbeCaptureToken":{token}}}"#)).unwrap();assert_eq!(p.gpu_probe_capture_token,Some(token));
            assert_eq!(serde_json::to_string(&p).unwrap(),serialized);assert_eq!(serde_json::to_string(&p.runtime()).unwrap(),runtime);
        }
        for value in ["0","-1","9223372036854775808","1.5",r#""123""#,"true","{}","[]"] {
            let p=MobileParams::parse(&format!(r#"{{"gpuProbeCaptureToken":{value}}}"#)).unwrap();assert_eq!(p.gpu_probe_capture_token,Some(0));
            assert_eq!(serde_json::to_string(&p).unwrap(),serialized);
        }
        assert_eq!(MobileParams::parse(r#"{"gpuProbeCaptureToken":null}"#).unwrap().gpu_probe_capture_token,None);
    }
    #[test] fn gpu_probe_capture_requires_existing_real_private_root_and_fresh_token() {
        use crate::streamed::GpuProbeCaptureRequest;
        let root=std::env::temp_dir().join(format!("gpu-probe-paths-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&root).unwrap();let root=root.canonicalize().unwrap();
        struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}let _guard=Temp(root.clone());
        assert!(matches!(private_gpu_probe_request(&root,7),GpuProbeCaptureRequest::Invalid));
        let probes=root.join("cache/gpu-probes");std::fs::create_dir_all(&probes).unwrap();
        match private_gpu_probe_request(&root,7) {GpuProbeCaptureRequest::Capture{token,snapshot_dir}=>{assert_eq!(token,7);assert_eq!(snapshot_dir,probes.join("probe-7"));},_=>panic!("valid root rejected")}
        for token in [0,i64::MAX as u64+1] {assert!(matches!(private_gpu_probe_request(&root,token),GpuProbeCaptureRequest::Invalid));}
        std::fs::create_dir(probes.join("probe-7")).unwrap();assert!(matches!(private_gpu_probe_request(&root,7),GpuProbeCaptureRequest::Invalid));
        #[cfg(unix)] {
            std::fs::rename(&probes,root.join("cache/real-probes")).unwrap();std::os::unix::fs::symlink(root.join("cache/real-probes"),&probes).unwrap();
            assert!(matches!(private_gpu_probe_request(&root,8),GpuProbeCaptureRequest::Invalid));
            std::fs::remove_file(&probes).unwrap();std::fs::rename(root.join("cache/real-probes"),&probes).unwrap();
            std::fs::rename(root.join("cache"),root.join("real-cache")).unwrap();std::os::unix::fs::symlink(root.join("real-cache"),root.join("cache")).unwrap();
            assert!(matches!(private_gpu_probe_request(&root,8),GpuProbeCaptureRequest::Invalid));
        }
    }
    #[test] fn checkpoint_path_is_not_in_serialized_diagnostics() {let mut p=MobileParams::default();p.checkpoint_directory=Some("/private/job/checkpoint".into());let serialized=serde_json::to_string(&p).unwrap();assert!(!serialized.contains("checkpoint"));assert!(!serialized.contains("/private"));assert_eq!(plan_export(4000,3000,&p).unwrap().checkpoint_reserve_bytes,576_000_000+1024*1024);}

    #[test] fn advanced_controls_map_and_defaults_preserve_baseline() {
        let p=MobileParams::default().runtime();assert!(!p.camera.diffusion_filter.active);assert_eq!(p.scanner.unsharp_mask,[0.,0.]);assert_eq!(p.enlarger.preflash_exposure,0.);assert_eq!(p.enlarger.m_filter_shift,0.);
        let p=MobileParams::parse(r#"{"diffusionFilter":"glimmerglass","diffusionStrength":0.75,"diffusionSize":1.5,"warmth":1,"printYellow":4,"printMagenta":-7,"preflash":0.1,"scanSharpenAmount":1.2,"scanSharpenRadius":2}"#).unwrap().runtime();
        assert!(p.camera.diffusion_filter.active);assert_eq!(p.camera.diffusion_filter.filter_family,"glimmerglass");assert_eq!(p.camera.diffusion_filter.strength,0.75);assert_eq!(p.camera.diffusion_filter.spatial_scale,1.5);assert_eq!(p.enlarger.y_filter_shift,-11.);assert_eq!(p.enlarger.m_filter_shift,-7.);assert_eq!(p.enlarger.preflash_exposure,0.1);assert_eq!(p.scanner.unsharp_mask,[2.,1.2]);
    }
    #[test] fn checked_dimensions() {assert_eq!(required_bytes(16,16).unwrap(),3072);assert!(required_bytes(0,1).is_err());assert!(required_bytes(i32::MAX,i32::MAX).is_err());assert_eq!(required_bytes(768,768).unwrap(),768*768*12);assert!(required_bytes(769,768).is_err());}
    #[test] fn rejects_invalid_parameters() {for j in [r#"{"unknown":1}"#,r#"{"filmStock":"../../secret"}"#,r#"{"grainAmount":-1}"#,r#"{"schemaVersion":2}"#,r#"{"contrast":2}"#] {assert!(MobileParams::parse(j).is_err(),"{j}");}}
    #[test] fn accepts_documented_defaults() {let p=MobileParams::parse("{}").unwrap();assert_eq!(p.seed,42);assert_eq!(p.film_stock,"kodak_portra_400");}
    #[test] fn cancelled_generation_does_not_poison_next_job() {let e=Engine {data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),preview_cache:Mutex::new(preview_reuse::Cache::default())};let old=e.cancel_generation.load(Ordering::Acquire);e.cancel_generation.fetch_add(1,Ordering::AcqRel);assert!(check_cancellation(&e,old).is_err());let new=e.cancel_generation.load(Ordering::Acquire);assert!(check_cancellation(&e,new).is_ok());}
    #[test] fn missing_data_cannot_create_engine() {assert!(create("/does/not/exist/film-engine-data").is_err());assert!(get(-1).is_err());destroy(-1);cancel(-1);}
    #[test] fn public_errors_do_not_leak_upstream_text() {
        let result=error_json(EngineError::new("INVALID_PARAMS","private-GPS-path-photo-sample"),"preview","validate_request",1.5);
        assert!(!result.contains("private-GPS-path-photo-sample"));
        let json:serde_json::Value=serde_json::from_str(&result).unwrap();
        assert_eq!(json["stage"],"validate_request");assert_eq!(json["elapsedMs"],1.5);assert_eq!(json["outputValid"],false);
    }
    #[test] fn validates_and_enforces_hidden_memory_budget() {
        for invalid in [r#"{"memoryBudgetMiB":127}"#,r#"{"memoryBudgetMiB":513}"#,r#"{"memoryBudgetMiB":128.5}"#,r#"{"memoryBudgetMiB":-1}"#] {assert!(MobileParams::parse(invalid).is_err());}
        let low=MobileParams::parse(r#"{"memoryBudgetMiB":128}"#).unwrap();assert_eq!(low.expanded_tile_pixel_budget().unwrap(),65536);
        assert!(low.check_preview_budget(65536).is_ok());assert_eq!(low.check_preview_budget(65537).unwrap_err().code,"MEMORY_BUDGET_TOO_SMALL");
        assert_eq!(MobileParams::default().expanded_tile_pixel_budget().unwrap(),589824);
        assert!(MobileParams::parse(r#"{"memoryBudgetMiB":416}"#).unwrap().check_preview_budget(768*768).is_ok());
        assert!(low.check_preview_budget(768*768).is_err());
    }
    #[test] fn large_export_plan_is_checked_and_distinguishes_extra_scratch() {
        let p=MobileParams::default();let plan=plan_export(4000,3000,&p).unwrap();
        assert_eq!(plan.pixels,12_000_000);assert_eq!(plan.input_bytes,144_000_000);assert_eq!(plan.estimated_scratch_bytes,1_603_108_864);assert_eq!(plan.estimated_total_local_bytes,1_891_108_864);
        let large=plan_export(10000,5000,&p).unwrap();assert_eq!(large.estimated_scratch_bytes,6_467_108_864);assert_eq!(large.estimated_total_local_bytes,7_667_108_864);
        assert!(plan_export(i32::MAX,2,&p).is_err());assert!(plan_export(0,3000,&p).is_err());
    }
    #[test] fn export_progress_snapshot_is_coherent_under_concurrent_polling() {
        let e=Arc::new(Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),preview_cache:Mutex::new(preview_reuse::Cache::default())});
        progress_begin(&e,"exportFile");let writer=e.clone();
        let thread=std::thread::spawn(move||for i in 1..=5000 {
            let cloud=i%2==0;
            progress_update_export(&writer,crate::streamed_progress::ExportProgress{pass_updated:true,phase:if cloud{"grain_cloud"}else{"grain_sampling"},done:i,total:5000,display_stage:5,stage_done:i*7,stage_total:35000});
        });
        for _ in 0..5000 {let value:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
            if value["displayStage"]==5 {let done=value["done"].as_u64().unwrap();assert_eq!(value["stageDone"],done*7);assert_eq!(value["stageTotal"],35000);assert_eq!(value["stage"],if done%2==0{"grain_cloud"}else{"grain_sampling"});}
        }
        thread.join().unwrap();progress_begin(&e,"preview");let value:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert_eq!(value["displayStage"],1);assert_eq!(value["stageDone"],0);assert_eq!(value["stageTotal"],0);
    }
    #[test] fn progress_serial_changes_only_on_native_updates_even_if_counts_are_equal() {
        let e=Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),preview_cache:Mutex::new(preview_reuse::Cache::default())};
        progress_begin(&e,"exportFile");
        let event=crate::streamed_progress::ExportProgress{pass_updated:true,phase:"camera_diffusion",done:0,total:72_000_000,display_stage:3,stage_done:0,stage_total:2_000_000};
        progress_update_export(&e,event);
        let first:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
        let polled:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
        assert_eq!(polled["updateSerial"],first["updateSerial"]);
        progress_update_export(&e,event);
        let heartbeat:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
        assert_eq!(heartbeat["updateSerial"].as_u64().unwrap(),first["updateSerial"].as_u64().unwrap()+1);
        assert_eq!(heartbeat["done"],first["done"]);assert_eq!(heartbeat["stageDone"],first["stageDone"]);
        assert_eq!(heartbeat["passId"],first["passId"]);
    }
    #[test] fn progress_is_safe_and_resets_only_when_next_job_begins() {
        let e=Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),preview_cache:Mutex::new(preview_reuse::Cache::default())};
        let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert_eq!(json["jobId"],0);assert_eq!(json["status"],"idle");
        let job=progress_begin(&e,"exportFile");progress_update(&e,"private-path-secret",9,10);let j=progress_json(&e).unwrap();assert!(!j.contains("private-path-secret"));
        let before:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();progress_update(&e,"private-path-secret",1,10);let reset:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert!(reset["passId"].as_u64().unwrap()>before["passId"].as_u64().unwrap());let _=progress_finish(&e,job,Some("CANCELLED"));let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert_eq!(json["status"],"cancelled");assert_eq!(json["active"],false);assert_eq!(json["jobId"],1);
        progress_begin(&e,"preview");let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert_eq!(json["status"],"running");assert_eq!(json["done"],0);assert_eq!(json["total"],0);assert_eq!(json["jobId"],2);let _=progress_finish(&e,job,None);let stale:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert_eq!(stale["status"],"running");
    }

}

#[cfg(test)]
mod color_control_tests {
 use super::*;
 use spektrafilm_gpu::cpu_backend::CpuBackend;
 static NEXT:AtomicU64=AtomicU64::new(0);
 struct Temp(PathBuf);impl Drop for Temp {fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
 fn data()->PathBuf {Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
 fn check(p:&MobileParams)->Vec<f32> {
  p.validate().unwrap();let film=load_profile_by_name(&data(),&p.film_stock).unwrap();let paper=load_profile_by_name(&data(),&p.paper_stock).unwrap();
  let(w,h)=(137,129);let values:Vec<f32>=(0..w*h*3).map(|i|if (i/3)%17==0{1.0}else{((i*37)%251)as f32/500.}).collect();
  let mut runtime=p.runtime_for_film(&film);runtime.camera.pixel_size_um_override=Some(runtime.camera.film_format_mm*1000./w.max(h)as f32);runtime.film_render.grain.coordinate_origin=Some([0,0]);runtime.settings.use_enlarger_lut=true;runtime.settings.use_scanner_lut=true;runtime.settings.lut_resolution=33;
  let pipe=Pipeline::new_with_spectral(film,paper,runtime.clone(),&data()).unwrap();let full=pipe.process(ImageBuf::from_data(w,h,values.iter().map(|v|from_f64(*v as f64)).collect()),&CpuBackend);
  let root=std::env::temp_dir().join(format!("film-color-controls-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));std::fs::create_dir(&root).unwrap();let _guard=Temp(root.clone());let input=root.join("input");let output=root.join("output");let scratch=root.join("scratch");std::fs::write(&input,values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  crate::streamed::export_file(&data(),&p.film_stock,&p.paper_stock,runtime,&input,&output,&scratch,w,h,384,0.,||false,|_,_,_|{}).unwrap();
  let actual:Vec<f32>=std::fs::read(output).unwrap().chunks_exact(4).map(|v|f32::from_le_bytes(v.try_into().unwrap())).collect();
  let error=actual.iter().zip(&full.data).map(|(a,b)|(*a-*b as f32).abs()).fold(0f32,f32::max);assert!(error<=2e-6,"whole/export error {error}");assert!(!scratch.exists());actual
 }
 #[test]fn new_color_defaults_and_bounds() {
  let p=MobileParams::parse("{}").unwrap();assert!(p.neutral_filters_from_database);assert_eq!([p.grain_red_scale,p.grain_green_scale,p.grain_blue_scale],[1.;3]);assert_eq!(p.halation_color,"stock");
  let film=load_profile_by_name(&data(),"kodak_portra_400").unwrap();let runtime=p.runtime_for_film(&film);assert_eq!(runtime.film_render.grain.agx_particle_scale,RuntimeParams::default().film_render.grain.agx_particle_scale);
  assert_eq!(runtime.film_render.halation.halation_strength,[0.015,0.005,0.]);
  for bad in [r#"{"grainRedScale":0.24}"#,r#"{"grainGreenScale":3.01}"#,r#"{"grainBlueScale":0}"#,r#"{"neutralCyan":-1}"#,r#"{"neutralMagenta":201}"#,r#"{"neutralYellow":201}"#,r#"{"halationColor":"blue"}"#]{assert!(MobileParams::parse(bad).is_err(),"{bad}");}
 }
 #[test]fn coarse_halation_color_presets_preserve_total_and_match_streamed() {
  let mut p=MobileParams::default();p.film_format_mm=8.;p.grain_amount=0.;p.halation_amount=2.;let film=load_profile_by_name(&data(),&p.film_stock).unwrap();let base=p.runtime_for_film(&film);let total: f64=base.film_render.halation.halation_strength.iter().sum();let stock=check(&p);
  for (preset,weights) in [("red",[1.,0.,0.]),("amber",[0.8,0.2,0.]),("neutral",[1./3.;3])] {
   p.halation_color=preset.into();let runtime=p.runtime_for_film(&film);let strength=runtime.film_render.halation.halation_strength;assert!((strength.iter().sum::<f64>()-total).abs()<1e-15);assert_eq!(strength,weights.map(|v|v*total));
   let changed=check(&p);assert!(changed.iter().zip(&stock).any(|(a,b)|(a-b).abs()>1e-6),"{preset} inactive");
  }
 }
 #[test]fn custom_neutral_filters_and_rgb_grain_scales_match_streamed() {
  let mut p=MobileParams::default();p.film_format_mm=8.;let baseline=check(&p);
  p.neutral_filters_from_database=false;p.neutral_cyan=10.;p.neutral_magenta=40.;p.neutral_yellow=75.;let runtime=p.runtime();assert!(!runtime.settings.neutral_print_filters_from_database);assert_eq!([runtime.enlarger.c_filter_neutral,runtime.enlarger.m_filter_neutral,runtime.enlarger.y_filter_neutral],[10.,40.,75.]);let neutral=check(&p);assert!(neutral.iter().zip(&baseline).any(|(a,b)|(a-b).abs()>1e-4));
  p.grain_red_scale=2.5;p.grain_green_scale=0.5;p.grain_blue_scale=1.5;let scaled=p.runtime().film_render.grain.agx_particle_scale;let default=RuntimeParams::default().film_render.grain.agx_particle_scale;assert_eq!(scaled,[default[0]*2.5,default[1]*0.5,default[2]*1.5]);let grain=check(&p);assert!(grain.iter().zip(&neutral).any(|(a,b)|(a-b).abs()>1e-6));
 }
}

#[cfg(test)]
mod bw_mobile_tests {
    use super::*;
    fn data()->PathBuf {Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
    #[test]
    fn bw_mobile_controls_preserve_saved_values_but_use_supported_physics() {
        let p=MobileParams::parse(r#"{"filmStock":"kodak_doublex","paperStock":"kodak_2302","filmDevelopmentTime":9,"paperDevelopmentTime":7,"saturation":2,"grainRedScale":3,"paperGammaRed":2,"paperGammaFast":2,"paperDeveloperExhaustion":1,"contrast":1.2,"paperGamma":1.25,"warmth":1,"printYellow":4,"halationColor":"red"}"#).unwrap();
        let film=load_profile_by_name(&data(),&p.film_stock).unwrap();let r=p.runtime_for_film(&film);
        assert_eq!(p.saturation,2.);assert_eq!(p.grain_red_scale,3.);assert_eq!(p.paper_developer_exhaustion,1.);
        assert_eq!(r.film_render.development_time,Some(9.));assert_eq!(r.print_render.development_time,Some(7.));
        assert_eq!(r.film_render.dir_couplers.amount,p.dir_amount);
        assert!(r.settings.neutral_print_filters_from_database);assert!(!r.print_render.density_curves_morph.active);assert_eq!(r.print_render.density_curve_gamma,1.5);
        assert_eq!(r.enlarger.y_filter_shift,4.);assert_eq!(r.film_render.halation.halation_strength,[0.015,0.005,0.]);
        for invalid in [r#"{"filmDevelopmentTime":-1}"#,r#"{"paperDevelopmentTime":10}"#,r#"{"filmStock":"kodak_2302"}"#,r#"{"filmStock":"kodak_trix","outputRoute":"print"}"#] {assert!(MobileParams::parse(invalid).is_err());}
    }
    #[test]
    fn bw_upstream_neutral_database_is_used_and_manual_preferences_remain_effective() {
        let db=spektrafilm_core::neutral_filters::NeutralFilters::load(&data());
        let id=create(data().to_str().unwrap()).unwrap();let engine=get(id).unwrap();
        let input=vec![0.18f32;9*7*3];
        for &paper in crate::profile_catalog::PAPERS {
            let mut p=MobileParams::default();p.film_stock="kodak_doublex".into();p.paper_stock=paper.into();p.use_gpu=false;
            p.grain_amount=0.;p.halation_amount=0.;p.scatter_amount=0.;p.dir_amount=0.;
            let values=db.lookup(paper,"TH-KG3","kodak_doublex").expect("every print medium has upstream Double-X entry");
            assert!(p.runtime().settings.neutral_print_filters_from_database);
            let auto=render(&engine,0,&input,9,7,&p).unwrap();
            p.neutral_filters_from_database=false;p.neutral_cyan=values[0];p.neutral_magenta=values[1];p.neutral_yellow=values[2];
            assert!(!p.runtime().settings.neutral_print_filters_from_database);
            let manual=render(&engine,0,&input,9,7,&p).unwrap();
            for(a,b)in auto.pixels.iter().zip(manual.pixels){assert!(a.is_finite()&&b.is_finite());assert!((*a-b).abs()<2e-5,"{paper}: {a} vs {b}");}
            p.neutral_filters_from_database=true;p.print_illuminant="D65".into();
            assert!(!p.runtime().settings.neutral_print_filters_from_database);
            assert_eq!(p.runtime().enlarger.m_filter_neutral,values[1] as f32);
            assert!(db.lookup(paper,"D65","kodak_doublex").is_none());
        }
        let exact=db.lookup("fujifilm_crystal_archive_typeii","TH-KG3","kodak_doublex").unwrap();
        assert_eq!(exact,[0.,94.3863254258598,115.91640660609802]);
        destroy(id);
    }
    #[test]
    fn bw_default_mobile_export_matches_whole_with_all_stock_effects() {
        for (film,direct) in [("kodak_doublex",false),("kodak_doublex",true),("kodak_trix",true)] {
            let mut p=MobileParams::default();p.film_stock=film.into();p.paper_stock="kodak_2302".into();p.use_gpu=false;
            if direct {p.output_route="direct".into();}if film=="kodak_trix" {p.film_format_mm=8.;}
            p.validate().unwrap();let film_profile=load_profile_by_name(&data(),film).unwrap();
            let mut runtime=p.runtime_for_film(&film_profile);runtime.film_render.grain.coordinate_origin=Some([0,0]);
            let (w,h)=(17,13);let input:Vec<f32>=(0..w*h*3).map(|i|0.02+(i%97)as f32/110.).collect();
            let pipe=Pipeline::new_with_spectral(film_profile,load_profile_by_name(&data(),"kodak_2302").unwrap(),runtime.clone(),&data()).unwrap();
            let expected=pipe.process(ImageBuf::from_data(w,h,input.iter().map(|&x|from_f64(x as f64)).collect()),&spektrafilm_gpu::cpu_backend::CpuBackend);
            let root=std::env::temp_dir().join(format!("bw-default-mobile-{}-{film}-{direct}",std::process::id()));std::fs::create_dir(&root).unwrap();
            let inp=root.join("input");let out=root.join("output");std::fs::write(&inp,input.iter().flat_map(|x|x.to_le_bytes()).collect::<Vec<_>>()).unwrap();
            let metrics=crate::streamed::export_file(&data(),film,"kodak_2302",runtime,&inp,&out,&root.join("scratch"),w,h,384,0.,||false,|_,_,_|{}).unwrap();
            assert_eq!(metrics.pixel_count,(w*h)as u64);
            let actual=std::fs::read(&out).unwrap();assert_eq!(actual.len(),input.len()*4);
            for (a,b) in actual.chunks_exact(4).zip(expected.data) {let a=f32::from_le_bytes(a.try_into().unwrap());assert!(a.is_finite()&&b.is_finite());assert!((a as f64-b as f32 as f64).abs()<2e-5);}
            std::fs::remove_dir_all(root).unwrap();
        }
    }
    #[test]
    fn bw_default_mobile_preview_and_measured_development_are_finite_and_effective() {
        let id=create(data().to_str().unwrap()).unwrap();let engine=get(id).unwrap();
        let input:Vec<f32>=(0..17*13*3).map(|i|0.02+(i%97)as f32/110.).collect();
        for film in ["kodak_doublex","kodak_trix"] {
            let mut p=MobileParams::default();p.film_stock=film.into();p.paper_stock="kodak_2302".into();p.use_gpu=false;
            if film=="kodak_trix" {p.output_route="direct".into();p.film_format_mm=8.;}
            let first=render(&engine,0,&input,17,13,&p).unwrap();assert!(first.pixels.iter().all(|x|x.is_finite()));
            if film=="kodak_doublex" {p.film_development_time=12.;p.paper_development_time=9.;let changed=render(&engine,0,&input,17,13,&p).unwrap();assert!(first.pixels.iter().zip(&changed.pixels).any(|(a,b)|(a-b).abs()>1e-5));}
        }
        destroy(id);
    }
}

#[cfg(test)] mod optimization_parameter_tests {
 use super::*;
 #[test] fn execution_choices_do_not_change_photographic_identity() {
  let base=MobileParams::default();let before=serde_json::to_string(&base).unwrap();
  for bits in 0..128 {let p=MobileParams::parse(&format!(r#"{{"exportOptimizationMask":{bits},"gpuResourceReuse":true}}"#)).unwrap();assert_eq!(p.export_optimization_mask,bits);assert!(p.gpu_resource_reuse);assert_eq!(before,serde_json::to_string(&p).unwrap());}
  for value in ["128","4294967295","-1","1.5","true"] {assert!(MobileParams::parse(&format!(r#"{{"exportOptimizationMask":{value}}}"#)).is_err());}
 }
}

#[cfg(test)]
mod preview_brightness_reuse_tests {
    use super::*;
    #[test]
    fn brightness_cache_matches_fresh_bits_and_invalidates_science_source_cancel() {
        let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let id=create(data.to_str().unwrap()).unwrap();let engine=get(id).unwrap();
        let mut p=MobileParams::default();p.use_gpu=false;p.preview_brightness_reuse=true;
        let mut input:Vec<f32>=(0..9*7*3).map(|i|((i*37)%251)as f32/300.).collect();
        let cold=render(&engine,0,&input,9,7,&p).unwrap();assert!(!cold.preview_reuse_hit);
        for ev in [-1.5,0.,1.25] {
            p.brightness_ev=ev;let warm=render(&engine,0,&input,9,7,&p).unwrap();assert!(warm.preview_reuse_hit);
            let baseline_id=create(data.to_str().unwrap()).unwrap();let baseline_engine=get(baseline_id).unwrap();
            let mut baseline=p.clone();baseline.preview_brightness_reuse=false;
            let fresh=render(&baseline_engine,0,&input,9,7,&baseline).unwrap();destroy(baseline_id);
            assert!(warm.pixels.iter().zip(fresh.pixels).all(|(a,b)|a.to_bits()==b.to_bits()));
            eprintln!("PREVIEW_REUSE ev={ev} cold_ms={} warm_ms={} baseline_ms={} bytes={}",cold.elapsed_ms,warm.elapsed_ms,fresh.elapsed_ms,warm.preview_cache_bytes);
        }
        p.seed+=1;assert!(!render(&engine,0,&input,9,7,&p).unwrap().preview_reuse_hit);
        input[0]=0.8;assert!(!render(&engine,0,&input,9,7,&p).unwrap().preview_reuse_hit);
        p.film_stock="kodak_doublex".into();assert!(!render(&engine,0,&input,9,7,&p).unwrap().preview_reuse_hit);
        cancel(id);assert_eq!(render(&engine,0,&input,9,7,&p).err().unwrap().code,"CANCELLED");
        p.preview_brightness_reuse=false;let baseline=render(&engine,1,&input,9,7,&p).unwrap();assert_eq!(baseline.preview_cache_bytes,0);
        destroy(id);assert!(get(id).is_err());
    }
}
