//! Narrow, validated all-on-device API. Physical stages are supplied by the GPL core.
use serde::{Deserialize, Serialize};
use crate::preview_stage_cache::{StageCache, Entry as PreviewStageEntry, FilmLog};
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline, profile::{Profile,load_profile_by_name}};
use spektrafilm_math::{image::ImageBuf, precision::from_f64};
use std::{collections::HashMap, path::{Path,PathBuf}, sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering}}, time::Instant};

// Hard pixel cap for the direct-buffer preview path. Must match the Kotlin
// `NativeMemoryPolicy.MAX_PREVIEW_PIXELS` and accommodate the largest REFINED
// preview the tier policy can request (long edge DETAIL_PREVIEW_EDGE=1280, so up
// to 1280x1280 for a square source). Original-size exports use the bounded-memory
// streamed path instead, so they are not limited by this.
pub mod refined;
mod pre_imaging;
pub use pre_imaging::PreImaging;
pub const MAX_PIXELS: usize = 1280 * 1280;
pub const MEMORY_FIXED_ESTIMATE_BYTES:u64=96*1024*1024;
pub const MEMORY_ESTIMATE_BYTES_PER_PIXEL:u64=512;
/// Cold f64 result + f32 FilmLog and corrected-density copies remain actively charged after passive eviction.
pub const PREVIEW_CANDIDATE_BYTES_PER_PIXEL:u64=48;
pub const PREVIEW_STAGE_CACHE_ABI:u32=3;
pub use crate::profile_catalog::{FILMS,PAPERS};

#[derive(Debug, Clone, Serialize)]
pub struct EngineError { pub code: &'static str, pub message: String }
impl EngineError { pub fn new(code: &'static str, message: impl Into<String>) -> Self { Self { code, message: message.into() } } }
impl std::fmt::Display for EngineError { fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f,"{}: {}",self.code,self.message) } }
impl std::error::Error for EngineError {}
pub type Result<T> = std::result::Result<T, EngineError>;

/// Immutable full-photo preview-only exposure. Not an export or ROI override.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub struct PreviewMetering {
    pub algorithm:String, pub source_revision:String, pub mapping_revision:String,
    pub data_revision:String, pub owner:String, pub method:String,
    pub width:u32, pub height:u32, pub ev:f32,
}
pub const PREVIEW_METERING_ALGORITHM:&str="full-photo-canonical256-core-ae-v1";
impl PreviewMetering {
    pub(crate) fn matches_detail_mapping(&self,mapping:&str)->bool {
        match (self.mapping_revision.split_once(":meter-grid:"),mapping.split_once(":bilinear-center-v1:")) {
            (Some((a,_)),Some((b,_)))=>a==b && a.ends_with(&format!(":snapshot={}",self.source_revision)),
            _=>false,
        }
    }
    fn validate(&self,params:&MobileParams)->Result<()> {
        if params.mode!="preview" || !params.auto_exposure || self.method!=params.metering
            || self.algorithm!=PREVIEW_METERING_ALGORITHM || !self.ev.is_finite()
            || !(2.0f32.powf(self.ev)).is_finite() || !2.0f32.powf(self.ev+params.film_exposure_ev as f32).is_finite() || !(1..=256).contains(&self.width)
            || !(1..=256).contains(&self.height)
            || [&self.source_revision,&self.mapping_revision,&self.data_revision,&self.owner].iter().any(|v|v.is_empty()||v.len()>1024) {
            return Err(EngineError::new("INVALID_PARAMS","Invalid shared preview metering identity/value"));
        }
        Ok(())
    }
}
pub fn measure_preview(input:&[f32],width:u32,height:u32,method:&str)->Result<f32> {
    if !(1..=256).contains(&width)||!(1..=256).contains(&height)
        || input.len()!=width as usize*height as usize*3 || input.iter().any(|v|!v.is_finite()) {
        return Err(EngineError::new("INVALID_PIXELS","Expected bounded finite full-photo linear RGB"));
    }
    if !["average","median","center_weighted","partial","matrix","multi_zone","highlight_weighted"].contains(&method) {
        return Err(EngineError::new("INVALID_PARAMS","Unknown meter"));
    }
    let image=ImageBuf::from_data(width,height,input.iter().map(|&v|from_f64(v as f64)).collect());
    let matrix=spektrafilm_core::stages::filming::input_colorspace_to_xyz("sRGB");
    let ev=spektrafilm_core::stages::filming::measure_autoexposure_ev(&image,&matrix,method);
    if !ev.is_finite() || !2.0f32.powf(ev).is_finite() {return Err(EngineError::new("INVALID_PIXELS","Nonfinite metered exposure"));}
    Ok(ev)
}

/// Snapshot identity comes from the owned PhotoSource; exact pixel bits are also
/// compared natively. No URI, path or buffer address is a cache key by itself.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all="camelCase", deny_unknown_fields)]
pub struct PreviewSourceIdentity {
    pub source_revision:String, pub mapping_revision:String, pub data_revision:String,
}
impl PreviewSourceIdentity {
    fn validate(&self)->Result<()> {
        if [&self.source_revision,&self.mapping_revision,&self.data_revision].iter().any(|s|s.is_empty() || s.len()>4096) {
            return Err(EngineError::new("INVALID_PARAMS","Invalid preview source identity"));
        }
        Ok(())
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all="camelCase", deny_unknown_fields)]
pub struct MobileParams {
    /// Explicit optional zoom grid. Never changes the settled 1536 preview contract.
    #[serde(skip_serializing_if="Option::is_none")]
    pub zoom_refinement_edge:Option<u32>,
    #[serde(skip_serializing_if="Option::is_none")]
    pub pre_imaging:Option<PreImaging>,
    #[serde(skip_serializing)]
    pub checkpoint_directory:Option<String>,
    #[serde(skip_serializing_if="Option::is_none")]
    pub preview_source:Option<PreviewSourceIdentity>,
    /// Queue-owned complete existing disk reservation, never a scientific setting.
    #[serde(skip_serializing)]
    pub export_reuse_reserve_bytes:Option<u64>,
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
    #[serde(skip_serializing_if="Option::is_none")]
    pub preview_metering:Option<PreviewMetering>,
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
    fn default() -> Self { Self { zoom_refinement_edge:None,pre_imaging:None,preview_source:None,export_reuse_reserve_bytes:None,preview_metering:None,checkpoint_directory:None,schema_version:1,film_stock:"kodak_portra_400".into(),paper_stock:"kodak_portra_endura".into(),output_route:"print".into(),film_exposure_ev:0.,brightness_ev:0.,contrast:1.,warmth:0.,saturation:1.,grain_amount:1.,grain_size:1.,halation_amount:1.,halation_size:1.,diffusion_filter:"off".into(),diffusion_strength:0.5,diffusion_size:1.,print_diffusion_filter:"off".into(),print_diffusion_strength:0.5,print_diffusion_size:1.,print_yellow:0.,print_magenta:0.,preflash:0.,scan_sharpen_amount:0.,scan_sharpen_radius:0.7,film_format_mm:35.,lens_blur_um:0.,scan_blur:0.,scatter_amount:1.,scatter_size:1.,halation_bounces:3,halation_decay:0.5,halation_normalize:true,dir_amount:1.,dir_diffusion_um:20.,dir_same_layer:1.,dir_inter_layer:1.,grain_sublayers:true,grain_layers:1,grain_blur:0.65,grain_dye_cloud:1.,grain_uniformity:1.,seed:42,mode:"preview".into(),memory_budget_mi_b:384,output_color_space:"sRGB".into(),output_gamut:true,print_exposure_ev:0.,print_illuminant:"TH-KG3".into(),print_compensation:true,normalize_print_exposure:true,preflash_yellow:0.,preflash_magenta:0.,scan_white_correction:false,scan_black_correction:false,scan_white_level:0.98,scan_black_level:0.01,auto_exposure:false,metering:"center_weighted".into(),highlight_boost_ev:0.,highlight_boost_range:0.3,highlight_protect_ev:4.,scan_glare_percent:0.,scan_glare_roughness:0.7,scan_glare_blur:0.5,grain_clump_size_um:0.2,grain_clump_strength_nm:30.,use_gpu:true,grain_red_scale:1.,grain_green_scale:1.,grain_blue_scale:1.,halation_color:"stock".into(),neutral_filters_from_database:true,neutral_cyan:0.,neutral_magenta:65.,neutral_yellow:55.,film_gamma:1.,paper_gamma:1.,paper_gamma_fast:1.,paper_gamma_slow:1.,paper_gamma_red:1.,paper_gamma_green:1.,paper_gamma_blue:1.,paper_developer_exhaustion:0.,film_development_time:0.,paper_development_time:0., } }
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
        if let Some(edge)=self.zoom_refinement_edge {
            if !matches!(edge,2048|3072) || self.mode!="preview" || !self.use_gpu {
                return Err(EngineError::new("INVALID_PARAMS","Zoom refinement requires an explicit 2048/3072 GPU preview grid"));
            }
        }
        if let Some(p)=&self.pre_imaging {p.validate(self)?;}
        if let Some(source)=&self.preview_source {source.validate()?;if self.mode!="preview"{return Err(EngineError::new("INVALID_PARAMS","Preview source identity cannot enter export"));}}
        if let Some(m)=&self.preview_metering {
            m.validate(self)?;
            if self.preview_source.as_ref().is_some_and(|s|s.source_revision!=m.source_revision) {
                return Err(EngineError::new("INVALID_PARAMS","Preview metering belongs to another source"));
            }
        }
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
        Ok((((self.memory_budget_mi_b as u64)*1024*1024-MEMORY_FIXED_ESTIMATE_BYTES)/(MEMORY_ESTIMATE_BYTES_PER_PIXEL+if self.film_stock=="none" && self.grain_amount>0. {48}else{0})).min(2_097_152))
    }
    pub fn check_preview_budget(&self,pixels:u64)->Result<()> {
        if pixels>self.expanded_tile_pixel_budget()? {return Err(EngineError::new("MEMORY_BUDGET_TOO_SMALL","Preview exceeds its conservative native working-memory estimate; use a smaller explicit preview"));}
        let per_pixel=MEMORY_ESTIMATE_BYTES_PER_PIXEL+if self.film_stock=="none" && self.grain_amount>0. {48}else{0}+if self.preview_source.is_some(){PREVIEW_CANDIDATE_BYTES_PER_PIXEL}else{0};
        if MEMORY_FIXED_ESTIMATE_BYTES+pixels*per_pixel>self.memory_budget_mi_b as u64*1024*1024 {
            return Err(EngineError::new("MEMORY_BUDGET_TOO_SMALL","Preview cache candidate exceeds its active working-memory estimate"));
        }
        Ok(())
    }
    /// Preview consumers share EV while request identity retains the user's compensation.
    /// Export calls runtime_for_film directly and rejects any preview contract in plan_export.
    pub(crate) fn preview_runtime_for_film(&self,film:&Profile)->RuntimeParams {
        self.apply_preview_metering(self.runtime_for_film(film))
    }
    fn apply_preview_metering(&self,mut runtime:RuntimeParams)->RuntimeParams {
        if let Some(m)=&self.preview_metering {
            runtime.camera.preview_metered_ev=Some(m.ev);
        }
        runtime
    }
    pub(crate) fn runtime(&self)->RuntimeParams {
        let mut p=RuntimeParams::default();
        if let Some(frame)=&self.pre_imaging {
            p.camera.pre_imaging_version=Some(frame.version);
            p.camera.pixel_size_um_override=Some((self.film_format_mm*1000./frame.photo_width.max(frame.photo_height) as f64) as f32);
        }
        // Preview and export use the same spectral LUT precision; only geometry differs.
        p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;
        p.camera.auto_exposure=self.auto_exposure;p.camera.auto_exposure_method=self.metering.clone();p.camera.film_format_mm=self.film_format_mm as f32;p.camera.lens_blur_um=self.lens_blur_um as f32;p.scanner.lens_blur=self.scan_blur as f32;
        p.camera.exposure_compensation_ev=self.film_exposure_ev as f32;
        p.io.scan_film=if self.paper_stock=="none" {true}else if self.film_stock=="none" {false}else{self.output_route=="direct"};p.io.input_color_space="sRGB".into(); p.io.output_color_space=self.output_color_space.clone();
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
        if (matches!(self.film_stock.as_str(),"kodak_doublex"|"kodak_trix"|"kodak_tri_x_400"|"kodak_tmax_100"|"fujifilm_neopan_acros_100_ii"|"ilford_hp5_plus_400") || matches!(self.paper_stock.as_str(),"kodak_2302"|"ilford_multigrade_iv_rc")) && self.print_illuminant!="TH-KG3" {
            p.settings.neutral_print_filters_from_database=false;
        }
        // BW is a single silver-density channel, not three independently coloured layers.
        // Preserve saved colour controls but do not reinterpret them as silver controls.
        if matches!(self.film_stock.as_str(),"kodak_doublex"|"kodak_trix"|"kodak_tri_x_400"|"kodak_tmax_100"|"fujifilm_neopan_acros_100_ii"|"ilford_hp5_plus_400") {
            p.film_render.dir_couplers.amount=self.dir_amount;
            p.film_render.grain.agx_particle_scale=RuntimeParams::default().film_render.grain.agx_particle_scale;
        }
        if matches!(self.paper_stock.as_str(),"kodak_2302"|"ilford_multigrade_iv_rc") {
            // B&W silver media expose physical filters and scalar contrast, not RGB dye morphs.
            // Use the supplied measured density table with scalar gamma, never a false fit.
            p.print_render.density_curves_morph.active=false;
            p.print_render.density_curve_gamma=(self.paper_gamma*self.contrast) as f32;
            p.enlarger.y_filter_shift=self.print_yellow as f32;
        }
        if self.film_stock=="none" {
            p.film_render.dir_couplers.active=false;
            p.film_render.halation.active=false;p.film_render.halation.boost_ev=0.;
            p.settings.neutral_print_filters_from_database=false;
            p.enlarger.c_filter_neutral=0.;p.enlarger.m_filter_neutral=0.;p.enlarger.y_filter_neutral=0.;
            p.enlarger.print_exposure_compensation=false;
        }
        if self.paper_stock=="none" {
            p.enlarger.diffusion_filter.active=false;p.print_render.glare.active=false;p.film_render.glare.active=false;
        }
        if self.film_stock=="none" || self.paper_stock=="none" {
            p.scanner.white_correction=false;p.scanner.black_correction=false;
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
        // Explicit product opt-in: do not reinterpret historical no-backing
        // profiles or saved edits for unrelated films.
        if film.info.stock.as_deref()==Some("cinestill_800t") {
            runtime.film_render.halation.back_reflection=true;
            runtime.film_render.halation.halation_strength=spektrafilm_math::halation::BACK_REFLECTION_STRENGTH;
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
    gpu_fallback_reason:Option<&'static str>,gpu_work:Option<crate::streamed_progress::GpuWorkProgress>,done:u64,total:u64,pass_id:u64,display_stage:u8,stage_done:u64,stage_total:u64,started:Option<Instant>,finished_ms:Option<f64>,
}
impl Default for ProgressState {fn default()->Self{Self{gpu_fallback_reason:None,gpu_work:None,job_id:0,operation:"none",stage:"idle",status:"idle",done:0,total:0,pass_id:0,display_stage:1,stage_done:0,stage_total:0,started:None,finished_ms:None}}}
fn safe_stage(stage:&'static str)->&'static str {match stage {
    "print_diffusion"|"auto_exposure"|"highlight_boost"|"scan_glare"|"camera_blur"|"scan_blur"|"camera_diffusion"|"scan_sharpen"|"idle"|"validate"|"setup"|"projection"|"halation"|"scatter"|"film_log"|"film_density"|"dir"|"grain"|"printing_scanning"|"output"|"flush"|"cleanup"|"complete"|"preview"|"film_exposure"|"film_scatter"|"film_halation"|"film_scatter_halation"|"film_density_dir"|"dir_diffusion"|"dir_develop"|"grain_blur"|"grain_cloud"|"grain_sampling"|"grain_microstructure"|"gpu_full_chain"|"gpu_tiled_chain"|"gpu_banded_chain"|"gpu_diffusion_prepass"=>stage,
    _=>"processing",
}}
pub fn progress_begin(engine:&Engine,operation:&'static str)->u64 {
    if let Ok(mut p)=engine.progress.lock(){p.job_id=p.job_id.wrapping_add(1);p.operation=operation;p.stage="setup";p.status="running";p.done=0;p.total=0;p.pass_id=1;p.display_stage=1;p.stage_done=0;p.stage_total=0;p.started=Some(Instant::now());p.finished_ms=None;p.gpu_fallback_reason=None;p.gpu_work=None;p.job_id}else{0}
}
pub fn progress_update(engine:&Engine,stage:&'static str,done:u64,total:u64) {
    if let Ok(mut p)=engine.progress.lock(){let stage=safe_stage(stage);let done=done.min(total);if p.stage!=stage||done<p.done {p.pass_id=p.pass_id.saturating_add(1);}p.stage=stage;p.done=done;p.total=total;}
}
/// The raw pass and aggregate stage are one snapshot, even under concurrent JNI polling.
pub fn progress_update_export(engine:&Engine,event:crate::streamed_progress::ExportProgress) {
    if let Ok(mut p)=engine.progress.lock(){
        if matches!(event.phase,"gpu_memory_budget_fallback"|"gpu_binding_limit_fallback"|"gpu_unavailable_fallback"|"gpu_execution_fallback"|"unsupported_adapter"|"cpu_checkpoint_resume") {
            p.gpu_fallback_reason=Some(event.phase);return;
        }
        if let Some(gpu)=event.gpu {p.gpu_work=Some(gpu);}
        let stage=safe_stage(event.phase);let done=event.done.min(event.total);
        if p.stage!=stage||done<p.done {p.pass_id=p.pass_id.saturating_add(1);}
        p.stage=stage;p.done=done;p.total=event.total;
        p.display_stage=event.display_stage;p.stage_done=event.stage_done;p.stage_total=event.stage_total;
    }
}
pub fn progress_finish(engine:&Engine,job_id:u64,code:Option<&str>)->Option<&'static str> {
    if let Ok(mut p)=engine.progress.lock(){if p.job_id!=job_id{return None;}p.finished_ms=Some(p.started.map_or(0.,|t|t.elapsed().as_secs_f64()*1000.));p.status=match code{None=>"completed",Some("CANCELLED")=>"cancelled",Some(_)=>"failed"};if code.is_none(){p.stage="complete";p.done=p.total;}if p.stage=="setup"{None}else{Some(p.stage)}}else{None}
}
pub fn progress_json(engine:&Engine)->Result<String> {
    let p=engine.progress.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Progress unavailable"))?;
    Ok(serde_json::json!({"ok":true,"schemaVersion":1,"preparedSpectral":engine.prepared_spectral_cache.stats(),"jobId":p.job_id,"operation":p.operation,"stage":p.stage,"status":p.status,"done":p.done,"total":p.total,"elapsedMs":p.finished_ms.unwrap_or_else(||p.started.map_or(0.,|t|t.elapsed().as_secs_f64()*1000.)),"passId":p.pass_id,"counterScope":"current-pass","displayStage":p.display_stage,"stageDone":p.stage_done,"stageTotal":p.stage_total,"stageCounterScope":"stage-work","active":p.status=="running","gpuFallbackReason":p.gpu_fallback_reason,
        "gpuPhase":p.gpu_work.and_then(|g|g.phase),"gpuTileIndex":p.gpu_work.map(|g|g.tile_index),"gpuTileTotal":p.gpu_work.map(|g|g.tile_total),
        "gpuBatchIndex":p.gpu_work.map(|g|g.batch_index),"gpuBatchElapsedMs":p.gpu_work.map(|g|g.batch_elapsed_ms),"gpuChainWaitMs":p.gpu_work.map(|g|g.chain_wait_ms),"gpuCompletedSubmits":p.gpu_work.map(|g|g.completed_submits),
        "gpuFailureKind":p.gpu_work.and_then(|g|g.failure_kind),"gpuSubmitMs":p.gpu_work.and_then(|g|g.submit_ms),"gpuPollMs":p.gpu_work.and_then(|g|g.poll_ms),"gpuMapWaitMs":p.gpu_work.and_then(|g|g.map_wait_ms),
        "gpuFailedPhase":p.gpu_work.and_then(|g|g.failed_phase),"gpuFailedElapsedMs":p.gpu_work.and_then(|g|g.failed_elapsed_ms)}).to_string())
}
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct ExportPlan {pub checkpoint_reserve_bytes:u64,pub width:u32,pub height:u32,pub pixels:u64,pub input_bytes:u64,pub output_bytes:u64,pub estimated_scratch_bytes:u64,pub estimated_total_local_bytes:u64,pub memory_budget_mi_b:u32,pub pointwise_chunk_pixels:u32,pub estimated_native_working_bytes:u64,pub background_preview_lease_bytes:u64,pub spatial_working_buffer_bytes:u64,pub estimate_policy:&'static str}
pub fn plan_export(width:i32,height:i32,params:&MobileParams)->Result<ExportPlan>{
    params.validate()?;
    if let Some(p)=&params.pre_imaging {p.check_frame(width as u32,height as u32)?;}
    if params.preview_source.is_some() || params.preview_metering.is_some() || params.zoom_refinement_edge.is_some(){return Err(EngineError::new("INVALID_PARAMS","Preview metering/source cannot be used for export"));}
    if width<=0||height<=0||width>40000||height>40000{return Err(EngineError::new("INVALID_DIMENSIONS","Export dimensions must be 1..40000"));}
    let pixels=(width as u64).checked_mul(height as u64).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Pixel count overflow"))?;
    let input_bytes=pixels.checked_mul(12).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Byte count overflow"))?;
    let estimated_scratch_bytes=crate::streamed::estimated_scratch_with_diffusion(width as u32,height as u32,(params.diffusion_filter!="off"&&params.diffusion_strength>0.)||(params.output_route=="print"&&params.print_diffusion_filter!="off"&&params.print_diffusion_strength>0.))?;
    let estimated_total_local_bytes=estimated_scratch_bytes.checked_add(input_bytes).and_then(|v|v.checked_add(input_bytes)).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Total byte count overflow"))?;
    #[cfg(feature="adreno-gpu")]
    let background_preview_lease_bytes=crate::early_negative_preview::active_reservation_bytes();
    #[cfg(not(feature="adreno-gpu"))]
    let background_preview_lease_bytes=0;
    // Higher-detail display sampling still fits the original 3 MiB allowance.
    let own_working=(115*1024*1024+256*(width as u64).max(32768)+crate::export_execution::EXPORT_STACK_RESERVE_BYTES).max(params.memory_budget_mi_b as u64*1024*1024);
    let estimated_native_working_bytes=own_working.checked_add(background_preview_lease_bytes).ok_or_else(||EngineError::new("INVALID_BUDGET","Memory reservation overflow"))?;
    Ok(ExportPlan{checkpoint_reserve_bytes:pixels*48+1024*1024,width:width as u32,height:height as u32,pixels,input_bytes,output_bytes:input_bytes,estimated_scratch_bytes,estimated_total_local_bytes,memory_budget_mi_b:params.memory_budget_mi_b,pointwise_chunk_pixels:(width as u32).max(32768),estimated_native_working_bytes,background_preview_lease_bytes,spatial_working_buffer_bytes:16*1024*1024,estimate_policy:"Conservative disk and native-work admission estimate; native work includes any prior process-level background preview lease once. Not measured RSS or a per-job hard memory cap."})
}

pub struct Engine {
    pub data_dir: PathBuf,
    pub cancel_generation: AtomicU64,
    pub prewarm_generation: AtomicU64,
    pub destroyed: AtomicBool,
    pub render_lock: Mutex<()>,
    pub progress:Mutex<ProgressState>,
    /// Session profile cache. Profiles are immutable on-disk data, so re-reading
    /// and re-parsing them on every preview frame is pure I/O overhead. Keyed by
    /// stock name; the bundled catalog is small and bounded.
    profile_cache:Mutex<HashMap<String,Arc<Profile>>>,
    cache_revision:Mutex<Option<String>>,
    /// Session pipeline cache. `Pipeline::new_with_spectral` rebuilds the spectral
    /// tc LUT and per-stock calibration on every call, which is the dominant
    /// per-frame CPU cost when scrubbing. The built pipeline is a pure function of
    /// (film stock, paper stock, RuntimeParams) for a fixed data_dir, so keying by
    /// that exact fingerprint keeps color bit-identical while letting a
    /// QUICK->REFINED pair (identical params) share one build. Resolution is NOT
    /// part of the key: `pixel_size_um` is derived per-stage from the image dims in
    /// `Pipeline::process`, so one pipeline serves every preview size.
    pipeline_cache:Mutex<Option<CachedPipeline>>,
    /// Only immutable front spectra, separately keyed from paper/calibration.
    prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache,
    pipeline_epoch:AtomicU64,
    preview_stage_cache:Mutex<StageCache<PreviewStageMetadata>>,
}
/// One most-recently-used pipeline plus the exact fingerprint that produced it.
struct CachedPipeline { key:String, pipeline:Arc<Pipeline> }
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
    registry.insert(id,Arc::new(Engine {data_dir:path,cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()}));
    Ok(id)
}
pub fn get(id:i64)->Result<Arc<Engine>> {
    engines().lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Handle registry unavailable"))?.get(&id).cloned().ok_or_else(||EngineError::new("INVALID_HANDLE","Engine handle is absent or already destroyed"))
}
pub fn cancel(id:i64) {if let Ok(engine)=get(id) {
    // Serialize cancellation with hit readers and publication, never render_lock.
    if let Ok(mut cache)=engine.preview_stage_cache.lock(){engine.cancel_generation.fetch_add(1,Ordering::AcqRel);cache.cancel_pending();}
    else {engine.cancel_generation.fetch_add(1,Ordering::AcqRel);}
    engine.prepared_spectral_cache.cancel_pending();
}}
/// Independent token: stopping speculative work must never cancel a newer
/// foreground render or change its scheduler generation.
pub fn invalidate_prewarm(id:i64, token:u64) {
    if let Ok(engine)=get(id) {
        engine.prewarm_generation.fetch_max(token,Ordering::AcqRel);
        engine.prepared_spectral_cache.cancel_pending();
    }
}
/// Caller owns the existing passive 8 MiB and an active 64 MiB + safety lease.
/// No Kotlin callback/retirement occurs with render_lock held. No image, GPU,
/// complete Pipeline, calibrated AE, or source/pixel identity is synthesized.
pub fn prewarm_front(engine:&Engine,token:u64,params:&MobileParams)->Result<String> {
    params.validate()?;
    if params.mode!="preview" {return Err(EngineError::new("INVALID_PARAMS","Prewarm is preview-only"));}
    let _guard=engine.render_lock.try_lock().map_err(|_|EngineError::new("ENGINE_BUSY","Foreground native work owns the handle"))?;
    let generation=engine.cancel_generation.load(Ordering::Acquire);
    let current=||engine.prewarm_generation.load(Ordering::Acquire)==token && check_cancellation(engine,generation).is_ok();
    if !current() {return Err(EngineError::new("CANCELLED","Stale prewarm token"));}
    if !engine.prepared_spectral_cache.stats().enabled {
        return Ok(serde_json::json!({"ok":true,"operation":"prewarmFront","outcome":"disabled","preparedSpectral":engine.prepared_spectral_cache.stats()}).to_string());
    }
    if params.film_stock == "none" {
        return Ok(serde_json::json!({"ok":true,"operation":"prewarmFront","outcome":"no_film","preparedSpectral":engine.prepared_spectral_cache.stats()}).to_string());
    }
    // The selected installed profile is bounded before parsing. It is local to
    // this active reservation, not a new persistent profile cache entry.
    let profile_path=engine.data_dir.join(format!("profiles/{}.json",params.film_stock));
    let before=std::fs::metadata(&profile_path).map_err(|_|EngineError::new("MISSING_DATA","Prewarm profile unavailable"))?;
    if before.len()>512*1024 {return Err(EngineError::new("INVALID_BUDGET","Prewarm profile exceeds bound"));}
    let film=load_profile_by_name(&engine.data_dir,&params.film_stock).map_err(|e|EngineError::new("PROFILE_ERROR",e.to_string()))?;
    let runtime=params.runtime_for_film(&film);
    let checkpoint=||current() && std::fs::metadata(&profile_path).ok().is_some_and(|after|after.len()==before.len() && after.modified().ok()==before.modified().ok());
    // Metadata at stage checkpoints is intentionally avoided in hot row loops.
    // Installed assets are immutable; revision is verified again before publish.
    if !checkpoint() {return Err(EngineError::new("CANCELLED","Prewarm profile changed"));}
    let outcome=engine.prepared_spectral_cache.prewarm(film,&runtime,&engine.data_dir,current)
        .map_err(|_|EngineError::new("CANCELLED","Prewarm cancelled, unavailable or outside speculative bound"))?;
    Ok(serde_json::json!({"ok":true,"operation":"prewarmFront","outcome":outcome,"preparedSpectral":engine.prepared_spectral_cache.stats()}).to_string())
}
/// The Java passive lease calls zero before releasing its byte charge. A hit holds
/// this mutex through finish; a cold render must match the epoch before publish.
pub fn set_preview_cache_budget(id:i64,bytes:u64)->Result<()> {
    let engine=get(id)?;
    let mut cache=engine.preview_stage_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Preview cache unavailable"))?;
    if engine.destroyed.load(Ordering::Acquire){return Err(EngineError::new("INVALID_HANDLE","Engine owner closed"));}
    cache.configure(bytes);Ok(())
}
pub fn destroy(id:i64) {
    if let Ok(mut registry)=engines().lock() {if let Some(engine)=registry.remove(&id) {engine.destroyed.store(true,Ordering::Release);engine.cancel_generation.fetch_add(1,Ordering::AcqRel);engine.prepared_spectral_cache.clear();if let Ok(mut cache)=engine.preview_stage_cache.lock(){cache.configure(0);}}}
}
pub const PREPARED_ASSET_BUDGET_BYTES:u64=8*1024*1024;
/// Must be called outside native render work: acquiring the same non-reentrant
/// render_lock recursively would deadlock. All production users of cached_pipeline
/// are bounded by the four JNI preview entrypoints' render_lock guard. RenderOutput
/// owns no Pipeline; export constructs separate, uncached pipelines.
/// A zero-budget success is therefore a synchronous physical retirement proof,
/// not merely an index clear. Kotlin keeps its passive charge until this returns.
pub fn set_prepared_spectral_cache_budget(engine:&Engine,bytes:u64)->Result<()> {
    if bytes!=0 && bytes!=PREPARED_ASSET_BUDGET_BYTES {return Err(EngineError::new("INVALID_BUDGET","Prepared assets require the exact bounded reservation"));}
    let _work=engine.render_lock.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Native work lock unavailable"))?;
    if bytes!=0 && engine.destroyed.load(Ordering::Acquire) {return Err(EngineError::new("INVALID_HANDLE","Engine is destroyed"));}
    engine.prepared_spectral_cache.set_enabled(bytes!=0);
    let admission=engine.prepared_spectral_cache.stats();
    if !admission.available || admission.enabled!=(bytes!=0) {return Err(EngineError::new("INTERNAL_ERROR","Prepared asset ledger unavailable; keep reservation"));}
    if bytes==0 {
        // No production render can retain a local Pipeline under this guard.
        *engine.pipeline_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Pipeline cache unavailable"))?=None;
        let stats=engine.prepared_spectral_cache.stats();
        if !stats.available || stats.live_assets!=0 || stats.live_bytes!=0 {
            return Err(EngineError::new("INTERNAL_ERROR","Prepared asset leases remain live; keep reservation"));
        }
    }
    Ok(())
}
pub fn prepared_spectral_cache_stats(engine:&Engine)->spektrafilm_core::prepared_spectral::PreparedSpectralStats {engine.prepared_spectral_cache.stats()}
pub fn required_bytes(width:i32,height:i32)->Result<usize> {
    if width<=0 || height<=0 {return Err(EngineError::new("INVALID_DIMENSIONS","Dimensions must be positive"));}
    let pixels=(width as usize).checked_mul(height as usize).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Dimensions overflow"))?;
    if pixels>MAX_PIXELS {return Err(EngineError::new("IMAGE_TOO_LARGE",format!("Direct-buffer previews support at most {MAX_PIXELS} pixels; use nativeExportFile for original-size bounded-memory export")));}
    pixels.checked_mul(12).ok_or_else(||EngineError::new("INVALID_DIMENSIONS","Byte count overflow"))
}
pub fn check_cancellation(engine:&Engine,generation:u64)->Result<()> {
    gpu_completion_guard()?;
    if engine.destroyed.load(Ordering::Acquire) || engine.cancel_generation.load(Ordering::Acquire)!=generation {Err(EngineError::new("CANCELLED","Result cancelled; output is not valid"))} else {Ok(())}
}
#[derive(Debug,Clone,Serialize,Default)]
#[serde(rename_all="camelCase")]
pub struct PreviewStageMetrics {
    pub hit:bool,pub retained_bytes:u64,pub prepare_ms:f64,pub science_ms:f64,pub finish_ms:f64,
    pub corrected_density_hit:bool,pub corrected_density_retained:bool,
    pub film_log_hit:bool,pub film_log_retained:bool,pub film_log_prefix_ms:f64,pub film_log_suffix_ms:f64,
}
#[derive(Clone)]
struct PreviewStageMetadata {metered_ev:Option<f32>,gpu:crate::adreno::GpuDiagnostics}
#[derive(Clone,Copy,Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct PreparedAssetFrameMetrics {
    pub pipeline_prepare_ms:f64,
    pub front_hits:u64,
    pub front_misses:u64,
    pub front_build_nanoseconds:u64,
    pub calibration_hits:u64,
    pub calibration_misses:u64,
    pub calibration_build_nanoseconds:u64,
    pub cache:spektrafilm_core::prepared_spectral::PreparedSpectralStats,
}
pub struct RenderOutput {pub refined_plan:Option<refined::PlanMetrics>,pub prepared_spectral:PreparedAssetFrameMetrics,pub preview_cache:PreviewStageMetrics,pub metered_ev:Option<f32>,pub gpu:crate::adreno::GpuDiagnostics,pub pixels:Vec<f32>,pub elapsed_ms:f64,pub warnings:Vec<&'static str>}

/// A submitted GPU operation with unconfirmed completion may retain driver memory.
/// Do not allocate another CPU/GPU image working set until the process restarts.
fn completion_guard_for(uncertain:bool)->Result<()> {
    if uncertain {Err(EngineError::new("GPU_COMPLETION_UNCERTAIN","GPU completion could not be confirmed; restart the app before rendering again"))} else {Ok(())}
}
pub(crate) fn gpu_completion_guard()->Result<()> {
    #[cfg(feature="adreno-gpu")]
    let uncertain=FULL_CHAIN_GPU.get().and_then(Option::as_ref).is_some_and(|gpu|gpu.completion_uncertain());
    #[cfg(not(feature="adreno-gpu"))]
    let uncertain=false;
    completion_guard_for(uncertain)
}
#[cfg(feature="adreno-gpu")]
static FULL_CHAIN_GPU: OnceLock<Option<spektrafilm_gpu::wgpu_backend::WgpuBackend>>=OnceLock::new();
#[cfg(feature="adreno-gpu")]
static FULL_CHAIN_UNSUPPORTED: std::sync::atomic::AtomicBool=std::sync::atomic::AtomicBool::new(false);
#[cfg(feature="adreno-gpu")]
pub(crate) fn full_chain_unsupported()->bool { FULL_CHAIN_UNSUPPORTED.load(Ordering::Acquire) }
#[cfg(feature="adreno-gpu")]
fn full_chain_unavailable_reason(unsupported:bool)->&'static str {
    if unsupported { "unsupported_adapter" } else { "gpu_unavailable_fallback" }
}
/// Lazily initialized process-wide backend. Unavailable before submission permits
/// ordinary CPU fallback; uncertain submitted work is blocked by the guard above.
#[cfg(feature = "adreno-gpu")]
pub(crate) fn full_chain_gpu() -> Option<&'static spektrafilm_gpu::wgpu_backend::WgpuBackend> {
    FULL_CHAIN_GPU.get_or_init(||std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        #[cfg(target_os="android")]
        { spektrafilm_gpu::wgpu_backend::WgpuBackend::new_with_adapter_filter(|info| {
            let eligible=crate::adreno::eligible_adapter(info.vendor,&info.name,info.backend==wgpu::Backend::Vulkan,info.device_type==wgpu::DeviceType::IntegratedGpu);
            if !eligible { FULL_CHAIN_UNSUPPORTED.store(true,Ordering::Release); }
            eligible
        }) }
        #[cfg(not(target_os="android"))]
        { spektrafilm_gpu::wgpu_backend::WgpuBackend::new() }
    })).ok().flatten()).as_ref()
}

/// Read-only identity of an ALREADY initialized healthy device. Never creates a device,
/// submits GPU work, or substitutes a static capability claim for the quarantine state.
pub(crate) fn encoded_reuse_probe(use_gpu:bool)->Option<serde_json::Value> {
    if !use_gpu {return Some(serde_json::json!({"version":1,"backend":"cpu","algorithm":"streamed-cpu-v1-cinestill4g-v1","precision":if cfg!(feature="precision-f64"){"f64"}else{"f32"}}));}
    #[cfg(feature="adreno-gpu")]
    {
        let gpu=FULL_CHAIN_GPU.get()?.as_ref()?;
        if !gpu.is_healthy(){return None;}
        return Some(serde_json::json!({"version":1,"backend":"wgpu","algorithm":crate::gpu_export::ALGORITHM,
            "deviceOwner":format!("{:p}",gpu),"adapter":gpu.adapter_name(),"binding":gpu.max_storage_buffer_binding_size(),"precision":"gpu-f32-cpu-f64-boundaries-f32-io"}));
    }
    #[cfg(not(feature="adreno-gpu"))]
    None
}

/// Counts belong to this caller thread; the backend serializes each GPU submission.
/// A submitted command alone is not success: only validated completed output is active.
#[cfg(feature = "adreno-gpu")]
pub(crate) fn resident_diagnostics(gpu:&spektrafilm_gpu::wgpu_backend::WgpuBackend,batches:u64,stage:&'static str)->crate::adreno::GpuDiagnostics {
    crate::adreno::GpuDiagnostics {requested:true,active:batches>0,available:gpu.is_healthy(),adapter:gpu.adapter_name().to_owned(),backend:"wgpu",accelerated_stage:stage,gpu_batches:batches,cpu_fallbacks:0,reason:if stage=="tiled-chain"{"approximate_tiled_chain"}else if stage=="banded-chain"{"approximate_banded_chain"}else{"approximate_resident_chain"}.into(),startup_self_test_passed:false,numeric_guard_passed:true}
}
/// Metadata revisions are cheap to verify and prevent reusing cached scientific
/// assets after an installer has replaced the data snapshot on this handle.
fn refresh_cache_revision(engine:&Engine,params:&MobileParams)->Result<()> {
    // `none` is a synthetic omitted medium, not an installed profile file.
    // Keep role/selection identity so toggling either medium invalidates caches,
    // while still requiring all real profiles and shared scientific assets.
    let mut files=Vec::new();
    let mut revision=format!("film:{};paper:{};",params.film_stock,params.paper_stock);
    for stock in [&params.film_stock,&params.paper_stock] {
        if stock != "none" {files.push(format!("profiles/{stock}.json"));}
    }
    if params.film_stock=="none" && params.grain_amount>0. {files.push("profiles/kodak_portra_400.json".into());}
    files.push("luts/spectral_upsampling/irradiance_xy_tc.npy".into());
    files.push("filters/neutral_print_filters.json".into());
    for name in files {
        let meta=std::fs::metadata(engine.data_dir.join(&name)).map_err(|_|EngineError::new("MISSING_DATA","Bundled data revision is unavailable"))?;
        let changed=meta.modified().ok().and_then(|v|v.duration_since(std::time::UNIX_EPOCH).ok()).map(|v|v.as_nanos());
        use std::fmt::Write;write!(&mut revision,"{name}:{}:{changed:?};",meta.len()).map_err(|_|EngineError::new("INTERNAL_ERROR","Cache revision unavailable"))?;
    }
    // Installer commits this content-hash manifest last. It covers scientific
    // assets beyond the two selected profiles and the main spectral LUT. Keep
    // exact bytes, not only size/mtime, so same-size version changes invalidate.
    match std::fs::File::open(engine.data_dir.join(".prepared-v2")) {
        Ok(file)=>{
            use std::io::Read;
            let mut manifest=String::new();file.take(1024*1024+1).read_to_string(&mut manifest)
                .map_err(|_|EngineError::new("MISSING_DATA","Bundled data manifest unreadable"))?;
            if manifest.len()>1024*1024{return Err(EngineError::new("MISSING_DATA","Bundled data manifest too large"));}
            revision.push_str("manifest:");revision.push_str(&manifest);
        }
        Err(e) if e.kind()==std::io::ErrorKind::NotFound=>{}, // host scientific fixtures
        Err(_)=>return Err(EngineError::new("MISSING_DATA","Bundled data manifest unavailable")),
    }
    let mut prior=engine.cache_revision.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Cache revision unavailable"))?;
    if prior.as_ref()!=Some(&revision) {
        engine.preview_stage_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Preview cache unavailable"))?.invalidate();
        engine.profile_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Profile cache unavailable"))?.clear();
        *engine.pipeline_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Pipeline cache unavailable"))?=None;
        *prior=Some(revision);
    }
    Ok(())
}
/// Load a stock profile, serving repeats from the per-engine session cache so a
/// slider scrub does not re-read and re-parse the same JSON every frame.
fn cached_profile(engine:&Engine,stock:&str)->Result<Profile> {
    if let Ok(cache)=engine.profile_cache.lock(){if let Some(p)=cache.get(stock){return Ok((**p).clone());}}
    let loaded=load_profile_by_name(&engine.data_dir,stock).map_err(|e|EngineError::new("PROFILE_ERROR",e.to_string()))?;
    if let Ok(mut cache)=engine.profile_cache.lock(){
        // The bundled catalog is tiny; this bound only guards a pathological caller.
        if cache.len()>=16 {cache.clear();}
        cache.insert(stock.to_string(),Arc::new(loaded.clone()));
    }
    Ok(loaded)
}
/// Build (or reuse) the spectral pipeline for these exact params. The fingerprint
/// covers the film/paper stocks and the full resolved RuntimeParams, so a cache hit
/// is guaranteed to produce identical color to a fresh build.
fn cached_pipeline(engine:&Engine,params:&MobileParams,generation:u64)->Result<Arc<Pipeline>> {
    refresh_cache_revision(engine,params)?;
    let film=cached_profile(engine,&params.film_stock)?;
    let print=cached_profile(engine,&params.paper_stock)?;
    let runtime=params.preview_runtime_for_film(&film);
    // RuntimeParams serializes deterministically (serde field order + shortest
    // round-trip floats), so an identical slider state yields an identical key.
    let key=serde_json::to_string(&(params.film_stock.as_str(),params.paper_stock.as_str(),&runtime)).map_err(|_|EngineError::new("INVALID_PARAMS","Pipeline fingerprint serialization failed"))?;
    if let Ok(cache)=engine.pipeline_cache.lock(){if let Some(c)=cache.as_ref(){if c.key==key{return Ok(c.pipeline.clone());}}}
    let revision=spektrafilm_core::prepared_spectral::SpectralAssetRevision::read(&engine.data_dir,&runtime.settings.rgb_to_raw_method);
    let prepared_epoch=engine.prepared_spectral_cache.stats().epoch;
    let started=Instant::now();
    let prepared=Pipeline::new_with_spectral_cached_controlled(film,print,runtime,&engine.data_dir,
        &engine.prepared_spectral_cache,revision,||check_cancellation(engine,generation).is_ok());
    check_cancellation(engine,generation)?;
    let pipeline=Arc::new(prepared.map_err(|e|EngineError::new("PIPELINE_ERROR",e))?);
    // Immutable front/calibration hits still create a fresh downstream Pipeline.
    // Brightness results must not survive that rebuild or owner retirement.
    engine.pipeline_epoch.fetch_add(1,Ordering::AcqRel);
    let stats=engine.prepared_spectral_cache.stats();
    if std::env::var_os("SPEKTRAFILM_STAGE_TIMINGS").is_some() {
        eprintln!("stage pipeline_prepare: {:.3} ms front_hits={} front_misses={} front_retained_bytes={} front_live_bytes={} calibration_hits={} calibration_misses={} calibration_retained_bytes={}",
            started.elapsed().as_secs_f64()*1000.,stats.hits,stats.misses,stats.retained_bytes,stats.live_bytes,
            stats.calibration_hits,stats.calibration_misses,stats.calibration_retained_bytes);
    }
    // Lock order is pipeline -> prepared stats. Disabling changes the prepared
    // epoch without holding its mutex, then clears pipeline. This closes the
    // race where a late builder could re-pin a retired front in pipeline_cache.
    if let Ok(mut cache)=engine.pipeline_cache.lock(){
        if engine.prepared_spectral_cache.stats().epoch==prepared_epoch {
            *cache=Some(CachedPipeline{key,pipeline:pipeline.clone()});
        }
    }
    Ok(pipeline)
}
#[cfg(test)]
thread_local! { static PREVIEW_CANDIDATE_TEST_HOOK:std::cell::RefCell<Option<Box<dyn FnMut(bool)>>> = std::cell::RefCell::new(None); }
#[cfg(test)]
fn preview_candidate_test_checkpoint(allocated:bool) {PREVIEW_CANDIDATE_TEST_HOOK.with(|slot|{if let Some(hook)=slot.borrow_mut().as_mut(){hook(allocated);}});}
#[cfg(test)]
thread_local! { static FILM_LOG_CANDIDATE_TEST_HOOK:std::cell::RefCell<Option<Box<dyn FnMut()>>> = std::cell::RefCell::new(None); }
#[cfg(test)]
fn film_log_candidate_test_checkpoint() {FILM_LOG_CANDIDATE_TEST_HOOK.with(|slot|{if let Some(hook)=slot.borrow_mut().as_mut(){hook();}});}
#[cfg(test)]
thread_local! { static DENSITY_CANDIDATE_TEST_HOOK:std::cell::RefCell<Option<Box<dyn FnMut()>>> = std::cell::RefCell::new(None); }
#[cfg(test)]
fn density_candidate_test_checkpoint() {DENSITY_CANDIDATE_TEST_HOOK.with(|slot|{if let Some(hook)=slot.borrow_mut().as_mut(){hook();}});}
const PREVIEW_STAGE_ALGORITHM:&str="pre-brightness-linear-srgb-f64-v1";
fn preview_stage_key(engine:&Engine,params:&MobileParams,width:u32,height:u32,backend:&str)->Result<String> {
    let mut upstream=params.clone();upstream.brightness_ev=0.;
    serde_json::to_string(&(PREVIEW_STAGE_ALGORITHM,env!("CARGO_PKG_VERSION"),cfg!(feature="precision-f64"),
        spektrafilm_gpu::RESIDENT_APPROXIMATION_VERSION,engine.pipeline_epoch.load(Ordering::Acquire),
        width,height,backend,&upstream)).map_err(|_|EngineError::new("INVALID_PARAMS","Preview key unavailable"))
}
/// Only stable, verifiable actual routes are reusable. A fallback never seeds a
/// cache entry, so a transient resident failure cannot mask a later GPU retry.
fn preview_cache_backend(params:&MobileParams)->Option<String> {
    if !params.use_gpu {return Some("cpu".into());}
    #[cfg(feature="adreno-gpu")]
    if let Some(gpu)=full_chain_gpu().filter(|gpu|gpu.is_healthy()) {
        return Some(format!("wgpu:{}",gpu.adapter_name()));
    }
    None
}
/// Prefix identity is independent of pipeline_epoch: downstream changes MUST
/// rebuild calibration while retaining only the unchanged effective prefix.
fn preview_film_log_key(params:&MobileParams,pipe:&Pipeline,width:u32,height:u32,backend:&str)->Option<String> {
    if !params.use_gpu || params.mode!="preview" || !backend.starts_with("wgpu:") {return None;}
    let source=params.preview_source.as_ref()?;
    let prefix=pipe.gpu_film_log_prefix_key(&source.data_revision)?;
    let mut key=serde_json::to_string(&("whole-preview-filmlog-f32-v1",env!("CARGO_PKG_VERSION"),cfg!(feature="precision-f64"),
        spektrafilm_gpu::RESIDENT_APPROXIMATION_VERSION,backend,source,width,height,&params.film_stock,
        &params.preview_metering,prefix)).ok()?;
    key.shrink_to_fit();
    (key.capacity() as u64<=crate::preview_stage_cache::KEY_ALLOWANCE/4).then_some(key)
}
fn preview_corrected_density_key(params:&MobileParams,pipe:&Pipeline,width:u32,height:u32,backend:&str)->Option<String> {
    let log_key=preview_film_log_key(params,pipe,width,height,backend)?;
    let prefix=pipe.gpu_corrected_density_prefix_key(&params.preview_source.as_ref()?.data_revision)?;
    let mut hash=crate::checkpoint::Sha256::new();hash.update(prefix.as_bytes());
    let identity=serde_json::to_string(&("whole-preview-corrected-density-f32-v1",log_key,hash.finish())).ok()?;
    let mut digest=crate::checkpoint::Sha256::new();digest.update(identity.as_bytes());
    let mut key=digest.finish();key.shrink_to_fit();
    (key.capacity() as u64<=crate::preview_stage_cache::KEY_ALLOWANCE/4).then_some(key)
}
fn preview_warnings()->Vec<&'static str> {vec!["Preview size is explicitly bounded by the native memory budget. Use nativeExportFile for original-size export; never silently reduce the original.","GPU path uses v41 f32 spatial/grain and cap64 six-Gaussian diffusion approximations; CPU fallback is not pixel-identical.","The same seed is deterministic within each path, but preview and full-size export use different grain sampling layouts and need not match pixel-for-pixel.","Cancellation is cooperative before/after the pipeline, not immediate."]}
pub fn render(engine:&Engine,generation:u64,input:&[f32],width:u32,height:u32,params:&MobileParams)->Result<RenderOutput> {
    let bytes=required_bytes(width as i32,height as i32)?;
    params.validate()?;
    if params.zoom_refinement_edge.is_some(){return Err(EngineError::new("INVALID_PARAMS","Zoom refinement requires the refined preview entry point"));}
    if let Some(p)=&params.pre_imaging {p.check_frame(width,height)?;}
    params.check_preview_budget(width as u64*height as u64)?;
    if input.len()!=bytes/4 {return Err(EngineError::new("INVALID_BUFFER","Input length mismatch"));}
    if input.iter().any(|x|!x.is_finite()) {return Err(EngineError::new("INVALID_PIXELS","All input samples must be finite"));}
    check_cancellation(engine,generation)?;
    let _interactive=crate::export_execution::interactive_work();
    let start=Instant::now();
    let prepared_before=engine.prepared_spectral_cache.stats();
    let prepare_started=Instant::now();
    let pipe=cached_pipeline(engine,params,generation)?;
    let prepared_after=engine.prepared_spectral_cache.stats();
    let prepared_spectral=PreparedAssetFrameMetrics {
        pipeline_prepare_ms:prepare_started.elapsed().as_secs_f64()*1000.,
        front_hits:prepared_after.hits.saturating_sub(prepared_before.hits),
        front_misses:prepared_after.misses.saturating_sub(prepared_before.misses),
        front_build_nanoseconds:prepared_after.build_nanoseconds.saturating_sub(prepared_before.build_nanoseconds),
        calibration_hits:prepared_after.calibration_hits.saturating_sub(prepared_before.calibration_hits),
        calibration_misses:prepared_after.calibration_misses.saturating_sub(prepared_before.calibration_misses),
        calibration_build_nanoseconds:prepared_after.calibration_build_nanoseconds.saturating_sub(prepared_before.calibration_build_nanoseconds),
        cache:prepared_after,
    };
    check_cancellation(engine,generation)?;
    let backend=preview_cache_backend(params);
    let key=if params.mode=="preview" && params.preview_source.is_some() {
        backend.as_deref().map(|b|preview_stage_key(engine,params,width,height,b)).transpose()?
    }else{None};
    let epoch;
    let film_log_key=backend.as_deref().and_then(|b|preview_film_log_key(params,&pipe,width,height,b));
    #[allow(unused_mut)] let mut film_log_candidate=None;
    #[allow(unused_mut)] let mut film_log_hit=false;
    let density_key=backend.as_deref().and_then(|b|preview_corrected_density_key(params,&pipe,width,height,b));
    #[allow(unused_mut)] let mut density_candidate=None;
    #[allow(unused_mut)] let mut density_hit=false;
    let density_admitted;
    let stage_admitted;
    {
        let mut cache=engine.preview_stage_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Preview cache unavailable"))?;
        epoch=cache.epoch();
        if let Some(key)=key.as_deref() {
            if let Some(entry)=cache.find(key,input) {
                check_cancellation(engine,generation)?;
                let prepare_ms=start.elapsed().as_secs_f64()*1000.;let finish=Instant::now();
                let pixels=crate::preview_stage_cache::finish(&entry.linear_srgb,params.brightness_ev)
                    .ok_or_else(||EngineError::new("NONFINITE_OUTPUT","Rendering produced a nonfinite sample"))?;
                check_cancellation(engine,generation)?;
                let mut gpu=entry.metadata.gpu.clone();gpu.active=false;gpu.gpu_batches=0;gpu.cpu_fallbacks=0;
                gpu.reason="preview_stage_cache_hit".into();
                let metered_ev=entry.metadata.metered_ev;
                let film_log_retained=entry.film_log.is_some();
                let corrected_density_retained=entry.corrected_density.is_some();
                let preview_cache=PreviewStageMetrics{hit:true,retained_bytes:cache.bytes(),prepare_ms,science_ms:0.,finish_ms:finish.elapsed().as_secs_f64()*1000.,film_log_retained,corrected_density_retained,..PreviewStageMetrics::default()};
                return Ok(RenderOutput{refined_plan:None,prepared_spectral,preview_cache,metered_ev,gpu,pixels,elapsed_ms:start.elapsed().as_secs_f64()*1000.,warnings:preview_warnings()});
            }
        }else{cache.discard();}
        density_admitted=cache.corrected_density_fits(input.len());
        if density_admitted {
            if let Some(key)=density_key.as_deref() {
                density_candidate=cache.copy_corrected_density(key,input);
                density_hit=density_candidate.is_some();
            }
        }
        stage_admitted=cache.film_log_fits(input.len());
        if stage_admitted {
            if let Some(prefix_key)=film_log_key.as_deref() {
                film_log_candidate=cache.copy_film_log(prefix_key,input);
                film_log_hit=film_log_candidate.is_some();
            }
        }
    }
    let prepare_ms=start.elapsed().as_secs_f64()*1000.;let science=Instant::now();
    let image=ImageBuf::from_data(width,height,input.iter().map(|&x|from_f64(x as f64)).collect());
    let metered_ev=if let Some(m)=&params.preview_metering {Some(m.ev)}else if pipe.params.camera.auto_exposure {
        let matrix=spektrafilm_core::stages::filming::input_colorspace_to_xyz(&pipe.params.io.input_color_space);
        Some(spektrafilm_core::stages::filming::measure_autoexposure_ev(&image,&matrix,&pipe.params.camera.auto_exposure_method))
    }else{None};
    let mut gpu_note:Option<&'static str>=None;
    #[allow(unused_mut)] let mut film_log_prefix_ms=0.;
    #[allow(unused_mut)] let mut film_log_suffix_ms=0.;
    #[allow(unused_mut)] let mut accelerated:Option<(ImageBuf,crate::adreno::GpuDiagnostics)>=None;
    #[cfg(feature = "adreno-gpu")]
    if params.use_gpu {
        if let Some(gpu)=full_chain_gpu().filter(|gpu|gpu.is_healthy()) {
            let before=gpu.thread_submits();
            let attempt=std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                // ACTIVE candidate prepayment remains in force even if pressure
                // disables passive ownership while these allocations are alive.
                if stage_admitted && film_log_key.is_some() {
                    if film_log_candidate.is_none() {
                        let at=Instant::now();
                        if let Some(log)=pipe.tap_gpu_film_log_preview_controlled(&image,gpu,||check_cancellation(engine,generation).is_ok()) {
                            let values:Vec<f32>=log.data.iter().map(|v|*v as f32).collect();
                            if values.iter().all(|v|v.is_finite()) {
                                film_log_candidate=Some(FilmLog{key:film_log_key.as_ref().unwrap().clone(),values});
                            }
                        }
                        film_log_prefix_ms=at.elapsed().as_secs_f64()*1000.;
                    }
                    #[cfg(test)] film_log_candidate_test_checkpoint();
                    if let Some(log)=film_log_candidate.as_ref() {
                        let at=Instant::now();
                        let output=if density_admitted && density_key.is_some() {
                            if density_candidate.is_none() {
                                if let Some(density)=pipe.tap_gpu_corrected_density_preview_controlled(&log.values,width,height,gpu,||check_cancellation(engine,generation).is_ok()) {
                                    let values:Vec<f32>=density.data.iter().map(|v|*v as f32).collect();
                                    if values.iter().all(|v|v.is_finite()) {
                                        density_candidate=Some(FilmLog{key:density_key.as_ref().unwrap().clone(),values});
                                        #[cfg(test)] density_candidate_test_checkpoint();
                                    }
                                }
                            }
                            density_candidate.as_ref().and_then(|density|pipe.process_gpu_from_corrected_density_preview_controlled(&density.values,width,height,gpu,||check_cancellation(engine,generation).is_ok()))
                        }else{pipe.process_gpu_from_film_log_preview_controlled(&log.values,width,height,gpu,||check_cancellation(engine,generation).is_ok())};
                        film_log_suffix_ms=at.elapsed().as_secs_f64()*1000.;
                        if output.is_some(){return output;}
                    }
                    // A failed optional split never seeds retained data and must
                    // not make a later full GPU retry look like a cache hit.
                    film_log_candidate=None;film_log_hit=false;density_candidate=None;density_hit=false;
                    if check_cancellation(engine,generation).is_err(){return None;}
                }
                pipe.process_resident_borrowed_controlled(&image,gpu,||check_cancellation(engine,generation).is_ok())
            }));
            check_cancellation(engine,generation)?;
            match attempt {
                Ok(Some(output))=>accelerated=Some((output,resident_diagnostics(gpu,gpu.thread_submits().saturating_sub(before),"full-resident-chain"))),
                _=>gpu_note=Some("gpu_execution_fallback"),
            }
        }else {gpu_note=Some(full_chain_unavailable_reason(full_chain_unsupported()));}
    }
    let (output,gpu)=if let Some(done)=accelerated {done}else {
        let backend=crate::adreno::AdrenoDensityBackend::with_enabled(params.use_gpu&&gpu_note.is_none());
        let output=pipe.process(image,&backend);let mut diagnostics=backend.diagnostics();
        if let Some(reason)=gpu_note {diagnostics.requested=params.use_gpu;diagnostics.cpu_fallbacks+=1;diagnostics.reason=reason.into();}
        (output,diagnostics)
    };
    check_cancellation(engine,generation)?;
    if output.width != width || output.height != height || output.data.len() != bytes/4 {
        return Err(EngineError::new("INVALID_OUTPUT_SHAPE", "Physical pipeline returned dimensions or buffer length different from the requested image"));
    }

    let science_ms=science.elapsed().as_secs_f64()*1000.;let finish=Instant::now();
    let actual_backend_matches=match backend.as_deref(){
        Some("cpu")=>!gpu.requested && gpu.backend=="cpu" && !gpu.active,
        Some(b)=>gpu.active && gpu.backend=="wgpu" && gpu.cpu_fallbacks==0 && gpu.numeric_guard_passed
            && b==format!("wgpu:{}",gpu.adapter) && preview_cache_backend(params).as_deref()==Some(b),
        None=>false,
    };
    let retain_candidate=actual_backend_matches && key.is_some() && {
        let cache=engine.preview_stage_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Preview cache unavailable"))?;
        cache.enabled() && cache.epoch()==epoch
    };
    // The optional 24 B/pixel f64 result plus two 12 B/pixel FilmLog/corrected-density candidates
    // are prepaid by the ACTIVE preview
    // reservation. Pressure may retire the passive slot while this is allocating;
    // it cannot release that active charge. Disabled/fallback calls retain the
    // original streaming finish and never allocate a full f64 candidate.
    if !actual_backend_matches {film_log_candidate=None;film_log_hit=false;density_candidate=None;density_hit=false;}
    let (pixels,linear_srgb)=if retain_candidate {
        #[cfg(test)] preview_candidate_test_checkpoint(false);
        let mut linear=Vec::<f64>::with_capacity(input.len());
        for v in output.data.chunks_exact(3) {
            linear.extend(spektrafilm_core::output_color::to_linear_srgb([v[0]as f64,v[1]as f64,v[2]as f64],&params.output_color_space));
        }
        drop(output);
        #[cfg(test)] preview_candidate_test_checkpoint(true);
        let pixels=crate::preview_stage_cache::finish(&linear,params.brightness_ev)
            .ok_or_else(||EngineError::new("NONFINITE_OUTPUT","Rendering produced a nonfinite sample"))?;
        (pixels,Some(linear))
    }else{
        let exposure=2.0f64.powf(params.brightness_ev);
        let pixels:Vec<f32>=output.data.chunks_exact(3).flat_map(|v| {
            let rgb=spektrafilm_core::output_color::to_linear_srgb([v[0]as f64,v[1]as f64,v[2]as f64],&params.output_color_space);
            rgb.into_iter().map(|x|(x*exposure)as f32)
        }).collect();
        if pixels.iter().any(|v|!v.is_finite()){return Err(EngineError::new("NONFINITE_OUTPUT","Rendering produced a nonfinite sample"));}
        (pixels,None)
    };
    check_cancellation(engine,generation)?;
    let mut cache=engine.preview_stage_cache.lock().map_err(|_|EngineError::new("INTERNAL_ERROR","Preview cache unavailable"))?;
    check_cancellation(engine,generation)?;
    let mut film_log_retained=false;
    let mut corrected_density_retained=false;
    if cache.enabled() && cache.epoch()==epoch && actual_backend_matches && gpu.adapter.len()<1024 && gpu.reason.len()<1024 {
        if let (Some(key),Some(linear_srgb))=(key,linear_srgb) {
            let has_log=film_log_candidate.is_some();
            let has_density=density_candidate.is_some();
            film_log_retained=cache.publish(epoch,PreviewStageEntry{key,input:input.to_vec(),linear_srgb,metadata:PreviewStageMetadata{metered_ev,gpu:gpu.clone()},film_log:film_log_candidate,corrected_density:density_candidate});
            corrected_density_retained=film_log_retained && has_density;
            film_log_retained &= has_log;
        }
    }
    let preview_cache=PreviewStageMetrics{hit:false,retained_bytes:cache.bytes(),prepare_ms,science_ms,finish_ms:finish.elapsed().as_secs_f64()*1000.,film_log_hit,film_log_retained,film_log_prefix_ms,film_log_suffix_ms,corrected_density_hit:density_hit,corrected_density_retained};
    Ok(RenderOutput {refined_plan:None,prepared_spectral,preview_cache,metered_ev,gpu,pixels,elapsed_ms:start.elapsed().as_secs_f64()*1000.,warnings:preview_warnings()})
}

/// sRGB EOTF-encode one linear sample to an 8-bit channel, matching the Kotlin
/// preview compositor's `encode()` exactly (`coerceIn(0,1)` -> EOTF ->
/// `(x*255).roundToInt().coerceIn(0,255)`; `roundToInt` is `floor(x+0.5)`).
fn encode_srgb8(v:f32)->u8 {
    let x=(v as f64).clamp(0.0,1.0);
    let s=if x<=0.0031308 {x*12.92} else {1.055*x.powf(1.0/2.4)-0.055};
    ((s*255.0+0.5) as i32).clamp(0,255) as u8
}
/// sRGB-encode linear-sRGB preview pixels (already exposure-adjusted, in the
/// requested output color space) to packed RGBA8, matching the Kotlin preview
/// compositor's per-pixel `encode()` exactly. Doing this in native code lets
/// the Java side hand the buffer straight to `Bitmap.copyPixelsFromBuffer`
/// (a single JNI memcpy) instead of a per-pixel Kotlin `Double.pow` loop,
/// without requiring a Kotlin per-pixel loop. Timings are device-dependent.
pub fn linear_srgb_to_rgba8(pixels:&[f32])->Vec<u8> {
    let mut out=Vec::with_capacity(pixels.len()/3*4);
    for rgb in pixels.chunks_exact(3) {
        out.push(encode_srgb8(rgb[0]));
        out.push(encode_srgb8(rgb[1]));
        out.push(encode_srgb8(rgb[2]));
        out.push(0xff);
    }
    out
}
/// Strided variant of [`linear_srgb_to_rgba8`] that writes directly into a locked
/// `ANDROID_BITMAP_FORMAT_RGBA_8888` bitmap (R,G,B,A byte order, `out_stride`
/// bytes per row). Writing straight into the destination `Bitmap` eliminates the
/// per-frame direct output buffer and the `copyPixelsFromBuffer` full-image memcpy.
pub fn linear_srgb_to_rgba8_into(pixels:&[f32],out:&mut [u8],out_stride:usize,width:usize,height:usize) {
    debug_assert_eq!(pixels.len(),width*height*3);
    debug_assert!(out_stride>=width*4 && out.len()>=out_stride*(height-1)+width*4);
    for y in 0..height {
        let row=&mut out[y*out_stride..y*out_stride+width*4];
        for x in 0..width {
            let i=(y*width+x)*3;
            row[x*4]=encode_srgb8(pixels[i]);
            row[x*4+1]=encode_srgb8(pixels[i+1]);
            row[x*4+2]=encode_srgb8(pixels[i+2]);
            row[x*4+3]=0xff;
        }
    }
}
/// sRGB 8-bit -> linear-light, the continuous form of the Kotlin preview `linear[]`
/// LUT (`v=i/255`: `v<=0.04045 ? v/12.92 : ((v+0.055)/1.055)^2.4`). Computed in f64
/// to match the Kotlin `Double` math bit-for-bit on exact byte inputs.
fn srgb8_to_linear(v:f64)->f32 {
    let x=v.clamp(0.0,1.0);
    (if x<=0.04045 {x/12.92} else {((x+0.055)/1.055f64).powf(2.4)}) as f32
}
/// Read a locked `ANDROID_BITMAP_FORMAT_RGBA_8888` source bitmap, box-average
/// (area) downsample it to `dst_w*dst_h` in sRGB space, then linearize to RGB f32.
/// This intentionally uses area resampling in sRGB, which is not pixel-identical
/// to the former Skia filtered resize. It moves the conversion loop into
/// native code, eliminating the Kotlin `getPixels`/`putFloat` loop, the scaled
/// intermediate `Bitmap`, and the input direct buffer. Alpha is ignored (display
/// bitmaps are already alpha-composited); output is opaque linear RGB.
///
/// `src` is the locked bitmap memory, `src_stride` bytes per row. When
/// `src_w==dst_w && src_h==dst_h` this reduces to a pure per-pixel linearize,
/// bit-identical to the old `getPixels`+LUT path.
pub fn downsample_linearize_rgba8(src:&[u8],src_w:usize,src_h:usize,src_stride:usize,dst_w:usize,dst_h:usize)->Vec<f32> {
    assert!(src_w>0 && src_h>0 && dst_w>0 && dst_h>0 && src_stride>=src_w*4 && src.len()>=src_stride*(src_h-1)+src_w*4);
    let mut out=vec![0f32;dst_w*dst_h*3];
    // Fast path: no resampling — linearize each pixel directly (exact LUT match).
    if src_w==dst_w && src_h==dst_h {
        for y in 0..dst_h {
            let row=&src[y*src_stride..y*src_stride+src_w*4];
            for x in 0..dst_w {
                let p=&row[x*4..x*4+4];let o=(y*dst_w+x)*3;
                out[o]=srgb8_to_linear(p[0] as f64/255.0);
                out[o+1]=srgb8_to_linear(p[1] as f64/255.0);
                out[o+2]=srgb8_to_linear(p[2] as f64/255.0);
            }
        }
        return out;
    }
    // Area (box) downsample: each destination pixel averages the source rectangle
    // it covers, weighted by partial coverage at the edges.
    for dy in 0..dst_h {
        let sy0=dy as f64*src_h as f64/dst_h as f64;
        let sy1=(dy+1) as f64*src_h as f64/dst_h as f64;
        let iy0=sy0.floor() as usize;
        let iy1=(sy1.ceil() as usize).min(src_h);
        for dx in 0..dst_w {
            let sx0=dx as f64*src_w as f64/dst_w as f64;
            let sx1=(dx+1) as f64*src_w as f64/dst_w as f64;
            let ix0=sx0.floor() as usize;
            let ix1=(sx1.ceil() as usize).min(src_w);
            let mut acc=[0f64;3];let mut wsum=0f64;
            for iy in iy0..iy1 {
                let wy=(sy1.min((iy+1) as f64)-sy0.max(iy as f64)).max(0.0);
                if wy<=0.0 {continue;}
                let row=&src[iy*src_stride..iy*src_stride+src_w*4];
                for ix in ix0..ix1 {
                    let wx=(sx1.min((ix+1) as f64)-sx0.max(ix as f64)).max(0.0);
                    let w=wx*wy;
                    if w<=0.0 {continue;}
                    let p=&row[ix*4..ix*4+4];
                    acc[0]+=w*p[0] as f64;acc[1]+=w*p[1] as f64;acc[2]+=w*p[2] as f64;
                    wsum+=w;
                }
            }
            let o=(dy*dst_w+dx)*3;
            if wsum>0.0 {
                out[o]=srgb8_to_linear(acc[0]/wsum/255.0);
                out[o+1]=srgb8_to_linear(acc[1]/wsum/255.0);
                out[o+2]=srgb8_to_linear(acc[2]/wsum/255.0);
            }
        }
    }
    out
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
    "GPU_COMPLETION_UNCERTAIN"=>"GPU completion could not be confirmed; restart the app before rendering again",
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
    let stage=if error.code=="GPU_COMPLETION_UNCERTAIN"{"scientific_pipeline"}else{stage};
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
fn private_checkpoint_path(engine:&Engine,input:&Path,output:&Path,requested:&str,pre_imaging:Option<&PreImaging>)->Result<PathBuf> {
    let invalid=||EngineError::new("INVALID_PATH","Checkpoint must belong to this immutable private queue job");
    let requested=Path::new(requested);
    if requested.as_os_str().len()>4096 || !requested.is_absolute() || requested.file_name().and_then(|n|n.to_str())!=Some("checkpoint") {return Err(invalid());}
    let job=input.parent().ok_or_else(invalid)?;
    let jobs=engine.data_dir.parent().ok_or_else(invalid)?.join("export-jobs");
    let id=job.file_name().and_then(|s|s.to_str()).unwrap_or("");
    let valid_id=id.len()==36 && id.bytes().enumerate().all(|(i,b)|if [8,13,18,23].contains(&i){b==b'-'}else{b.is_ascii_hexdigit()});
    let requested_parent=requested.parent().ok_or_else(invalid)?.canonicalize().map_err(|_|invalid())?;
    let expected_input=if let Some(frame)=pre_imaging {
        // The only additional queue input is the owned composite. Preserve the
        // original same-job scene spool as a distinct, canonical regular file.
        if frame.version!=1 {return Err(invalid());}
        let meter=frame.metering_path.as_deref().filter(|p|Path::new(p).is_absolute()).ok_or_else(invalid)?;
        let meter=Path::new(meter).canonicalize().map_err(|_|invalid())?;
        if meter!=job.join("input.rgb") || !meter.is_file() {return Err(invalid());}
        "pre-imaging-input.rgb"
    }else{"input.rgb"};
    if job.parent()!=Some(jobs.as_path()) || !valid_id || output.parent()!=Some(job) || input.file_name().and_then(|n|n.to_str())!=Some(expected_input) || requested_parent!=job {return Err(invalid());}
    let checkpoint=job.join("checkpoint");
    // Never follow a checkpoint symlink, even when it points back inside the job.
    match std::fs::symlink_metadata(&checkpoint) {
        Ok(metadata) if !metadata.is_dir()=>return Err(invalid()),
        Err(error) if error.kind()!=std::io::ErrorKind::NotFound=>return Err(invalid()),
        _=>{}
    }
    Ok(checkpoint)
}

pub fn export(engine:&Engine,generation:u64,input:&str,output:&str,width:i32,height:i32,params:&MobileParams)->Result<crate::streamed::StreamedMetrics> {
    if params.preview_source.is_some() || params.preview_metering.is_some(){return Err(EngineError::new("INVALID_PARAMS","Preview metering/source cannot enter export"));}
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
        .map(|requested|private_checkpoint_path(engine,&input,&output,requested,params.pre_imaging.as_ref())).transpose()?;
    // Working scratch remains disposable; only committed stage snapshots persist.
    // RESOURCE_PROBE_EXPORT_BEGIN
    let export_work = || {
        if let Some(frame)=&params.pre_imaging {
            let requested=frame.metering_path.as_deref().ok_or_else(||EngineError::new("INVALID_PARAMS","Export requires a scene metering source"))?;
            let (meter,_)=private_spool_paths(engine,requested,output.to_str().ok_or_else(||EngineError::new("INVALID_PATH","Invalid output"))?)?;
            if meter==input {return Err(EngineError::new("INVALID_PARAMS","Scene metering source must differ from composite"));}
            runtime.camera.preview_metered_ev=frame.resolve_export_meter(&meter,params,||execution.checkpoint(||check_cancellation(engine,generation).is_err()))?;
        }
        let run=||crate::streamed::export_file_checkpointed_managed(&execution,&engine.data_dir,&params.film_stock,&params.paper_stock,runtime,&input,&output,&scratch,checkpoint.as_deref(),width as u32,height as u32,params.memory_budget_mi_b,params.brightness_ev,params.use_gpu,||execution.checkpoint(||check_cancellation(engine,generation).is_err()),|event|progress_update_export(engine,event));
        #[cfg(feature="adreno-gpu")]
        {crate::export_reuse::with_disk_reserve(params.export_reuse_reserve_bytes,run)}
        #[cfg(not(feature="adreno-gpu"))]
        {run()}
    };
    #[cfg(feature="resource-audit")]
    let mut result=crate::resource_probe::run_export(&execution,export_work)?;
    #[cfg(not(feature="resource-audit"))]
    let mut result=execution.run(export_work)?;
    // RESOURCE_PROBE_EXPORT_END
    result.export_execution=Some(execution.metrics());
    Ok(result)
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn export_reuse_admission_is_not_a_scientific_parameter_or_diagnostic() {
        let original=MobileParams::default();let mut admitted=original.clone();admitted.export_reuse_reserve_bytes=Some(123456789);
        assert_eq!(serde_json::to_string(&original).unwrap(),serde_json::to_string(&admitted).unwrap());
        let parsed=MobileParams::parse(r#"{"exportReuseReserveBytes":123456789}"#).unwrap();
        assert_eq!(parsed.export_reuse_reserve_bytes,Some(123456789));
    }
    #[test] fn checkpoint_path_is_not_in_serialized_diagnostics() {let mut p=MobileParams::default();p.checkpoint_directory=Some("/private/job/checkpoint".into());let serialized=serde_json::to_string(&p).unwrap();assert!(!serialized.contains("checkpoint"));assert!(!serialized.contains("/private"));assert_eq!(plan_export(4000,3000,&p).unwrap().checkpoint_reserve_bytes,576_000_000+1024*1024);}

    #[test] fn advanced_controls_map_and_defaults_preserve_baseline() {
        let p=MobileParams::default().runtime();assert!(!p.camera.diffusion_filter.active);assert_eq!(p.scanner.unsharp_mask,[0.,0.]);assert_eq!(p.enlarger.preflash_exposure,0.);assert_eq!(p.enlarger.m_filter_shift,0.);
        let p=MobileParams::parse(r#"{"diffusionFilter":"glimmerglass","diffusionStrength":0.75,"diffusionSize":1.5,"warmth":1,"printYellow":4,"printMagenta":-7,"preflash":0.1,"scanSharpenAmount":1.2,"scanSharpenRadius":2}"#).unwrap().runtime();
        assert!(p.camera.diffusion_filter.active);assert_eq!(p.camera.diffusion_filter.filter_family,"glimmerglass");assert_eq!(p.camera.diffusion_filter.strength,0.75);assert_eq!(p.camera.diffusion_filter.spatial_scale,1.5);assert_eq!(p.enlarger.y_filter_shift,-11.);assert_eq!(p.enlarger.m_filter_shift,-7.);assert_eq!(p.enlarger.preflash_exposure,0.1);assert_eq!(p.scanner.unsharp_mask,[2.,1.2]);
    }
    #[cfg(feature="adreno-gpu")]
    #[test] fn unsupported_adapter_has_explicit_fallback_reason() {
        assert_eq!(full_chain_unavailable_reason(true),"unsupported_adapter");
        assert_eq!(full_chain_unavailable_reason(false),"gpu_unavailable_fallback");
    }
    #[test] fn checked_dimensions() {assert_eq!(required_bytes(16,16).unwrap(),3072);assert!(required_bytes(0,1).is_err());assert!(required_bytes(i32::MAX,i32::MAX).is_err());assert_eq!(required_bytes(768,768).unwrap(),768*768*12);assert_eq!(required_bytes(1280,1280).unwrap(),1280*1280*12);assert!(required_bytes(1281,1280).is_err());}
    #[test] fn rgba8_encode_matches_kotlin_srgb8() {
        // Kotlin LinearRgb.toSrgb8: coerceIn(0,1) -> EOTF -> (x*255).roundToInt().coerceIn(0,255)
        // Double.roundToInt() == Math.round == floor(x+0.5) for non-negative x.
        fn kotlin(v:f32)->u8 {let l=(v as f64).clamp(0.0,1.0);let e=if l<=0.0031308 {12.92*l} else {1.055*l.powf(1.0/2.4)-0.055};((e*255.0)+0.5).floor().clamp(0.0,255.0) as u8}
        let px=[0.0f32,1.0,0.5, 0.0031308,0.0031309,0.04045, -0.25,2.0,f32::NAN, f32::INFINITY,f32::NEG_INFINITY,0.5];
        let out=linear_srgb_to_rgba8(&px);
        assert_eq!(out.len(),px.len()/3*4);
        for (i,v) in px.iter().enumerate() {assert_eq!(out[i/3*4+i%3],kotlin(*v),"sample {i}={v}");}
        for a in [3usize,7,11,15] {assert_eq!(out[a],0xff,"alpha byte {a}");}
        // Exact codes: 0->0, 1->255, 0.5->188, EOTF knee samples, clamps, NaN->0, +-inf
        assert_eq!(&out[0..4],&[0,255,188,0xff]);
        assert_eq!(&out[4..8],&[10,10,57,0xff]);
        assert_eq!(&out[8..12],&[0,255,0,0xff]);
        assert_eq!(&out[12..16],&[255,0,188,0xff]);
    }
    #[test] fn rgba8_into_matches_vec_and_respects_stride() {
        let w=3usize;let h=2usize;let stride=w*4+8; // padded stride
        let px:Vec<f32>=(0..w*h*3).map(|i|i as f32/ (w*h*3) as f32).collect();
        let mut out=vec![0xabu8;stride*h];
        linear_srgb_to_rgba8_into(&px,&mut out,stride,w,h);
        let vec=linear_srgb_to_rgba8(&px);
        for y in 0..h {assert_eq!(&out[y*stride..y*stride+w*4],&vec[y*w*4..(y+1)*w*4],"row {y}");}
        // Padding bytes between rows must be untouched.
        for y in 0..h {assert!(out[y*stride+w*4..(y+1)*stride].iter().all(|&b|b==0xab),"padding row {y}");}
    }
    #[test] fn downsample_identity_is_exact_lut() {
        // No resampling: each output pixel is the linearized source byte.
        let w=4usize;let h=3usize;let stride=w*4+4;
        let mut src=vec![0u8;stride*h];
        for y in 0..h {for x in 0..w {let p=y*stride+x*4;src[p]=(x*40) as u8;src[p+1]=(y*70) as u8;src[p+2]=200;src[p+3]=255;}}
        let out=downsample_linearize_rgba8(&src,w,h,stride,w,h);
        fn lut(b:u8)->f32 {let v=b as f64/255.0;(if v<=0.04045 {v/12.92} else {((v+0.055)/1.055f64).powf(2.4)}) as f32}
        for y in 0..h {for x in 0..w {let o=(y*w+x)*3;
            assert_eq!(out[o],lut((x*40) as u8),"r {x},{y}");
            assert_eq!(out[o+1],lut((y*70) as u8),"g {x},{y}");
            assert_eq!(out[o+2],lut(200),"b {x},{y}");}}
    }
    #[test] fn downsample_2x_box_averages() {
        // 2x2 -> 1x1: the single output is the average of the four source pixels.
        let src=[10u8,20,30,255, 20,30,40,255, 30,40,50,255, 40,50,60,255];
        let out=downsample_linearize_rgba8(&src,2,2,8,1,1);
        fn lut(avg:f64)->f32 {let v=avg/255.0;(if v<=0.04045 {v/12.92} else {((v+0.055)/1.055f64).powf(2.4)}) as f32}
        assert_eq!(out[0],lut((10.0+20.0+30.0+40.0)/4.0));
        assert_eq!(out[1],lut((20.0+30.0+40.0+50.0)/4.0));
        assert_eq!(out[2],lut((30.0+40.0+50.0+60.0)/4.0));
    }
    #[test] fn downsample_uniform_is_constant() {
        // A uniform field must stay uniform under any downsample ratio.
        let sw=8usize;let sh=6usize;let dw=3usize;let dh=2usize;
        let src=vec![128u8;sw*sh*4];
        let out=downsample_linearize_rgba8(&src,sw,sh,sw*4,dw,dh);
        let expect={let v=128.0f64/255.0;(if v<=0.04045 {v/12.92} else {((v+0.055)/1.055f64).powf(2.4)}) as f32};
        for (i,&v) in out.iter().enumerate() {assert!((v-expect).abs()<1e-6,"sample {i}: {v} != {expect}");}
    }
    #[test] fn rejects_invalid_parameters() {for j in [r#"{"unknown":1}"#,r#"{"filmStock":"../../secret"}"#,r#"{"grainAmount":-1}"#,r#"{"schemaVersion":2}"#,r#"{"contrast":2}"#] {assert!(MobileParams::parse(j).is_err(),"{j}");}}
    #[test] fn accepts_documented_defaults() {let p=MobileParams::parse("{}").unwrap();assert_eq!(p.seed,42);assert_eq!(p.film_stock,"kodak_portra_400");}
    #[test] fn cancelled_generation_does_not_poison_next_job() {let e=Engine {data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()};let old=e.cancel_generation.load(Ordering::Acquire);e.cancel_generation.fetch_add(1,Ordering::AcqRel);assert!(check_cancellation(&e,old).is_err());let new=e.cancel_generation.load(Ordering::Acquire);assert!(check_cancellation(&e,new).is_ok());}
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
        let e=Arc::new(Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()});
        progress_begin(&e,"exportFile");let writer=e.clone();
        let thread=std::thread::spawn(move||for i in 1..=5000 {
            let cloud=i%2==0;
            progress_update_export(&writer,crate::streamed_progress::ExportProgress{gpu:None,pass_updated:true,phase:if cloud{"grain_cloud"}else{"grain_sampling"},done:i,total:5000,display_stage:5,stage_done:i*7,stage_total:35000});
        });
        for _ in 0..5000 {let value:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
            if value["displayStage"]==5 {let done=value["done"].as_u64().unwrap();assert_eq!(value["stageDone"],done*7);assert_eq!(value["stageTotal"],35000);assert_eq!(value["stage"],if done%2==0{"grain_cloud"}else{"grain_sampling"});}
        }
        thread.join().unwrap();progress_begin(&e,"preview");let value:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert_eq!(value["displayStage"],1);assert_eq!(value["stageDone"],0);assert_eq!(value["stageTotal"],0);
    }
    #[test] fn gpu_batch_metadata_preserves_overall_progress_and_failed_stage() {
        let e=Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()};
        let job=progress_begin(&e,"exportFile");let mut reporter=crate::streamed_progress::Reporter::new(|event|progress_update_export(&e,event));
        use crate::streamed_progress::{ProgressSink,GpuWorkProgress};reporter.begin_stage(6,2,"gpu_tiled_chain");reporter.begin_work("gpu_tiled_chain");reporter.pass("gpu_tiled_chain",50,100);
        reporter.gpu_event(GpuWorkProgress{phase:Some("grain"),tile_index:2,tile_total:4,batch_index:6,batch_elapsed_ms:1.25,chain_wait_ms:4.5,completed_submits:10,..GpuWorkProgress::default()});
        let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert_eq!(json["done"],50);assert_eq!(json["total"],100);assert_eq!(json["gpuTileIndex"],2);assert_eq!(json["gpuTileTotal"],4);assert_eq!(json["gpuPhase"],"grain");assert_eq!(json["gpuCompletedSubmits"],10);
        reporter.gpu_failure("print",5000.);drop(reporter);let _=progress_finish(&e,job,Some("GPU_COMPLETION_UNCERTAIN"));let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert_eq!(json["gpuFailedPhase"],"print");assert_eq!(json["gpuFailedElapsedMs"],5000.);assert_eq!(json["gpuCompletedSubmits"],10);
        progress_begin(&e,"preview");let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert!(json["gpuPhase"].is_null());assert!(json["gpuFailedPhase"].is_null());
    }
    #[test] fn first_submit_failure_details_reset_before_cpu_or_unsupported_adapter_job() {
        let e=Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()};
        let job=progress_begin(&e,"exportFile");
        let mut reporter=crate::streamed_progress::Reporter::new(|v|progress_update_export(&e,v));
        reporter.gpu_failure("front",1.);
        reporter.gpu_failure_detail("submit_unwind",1.,0.,0.);
        drop(reporter);let _=progress_finish(&e,job,Some("GPU_COMPLETION_UNCERTAIN"));
        let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
        assert_eq!(json["gpuFailureKind"],"submit_unwind");assert_eq!(json["gpuSubmitMs"],1.);
        assert_eq!(json["gpuCompletedSubmits"],0);assert_eq!(json["gpuFailedPhase"],"front");
        for unsupported in [false,true] {
            progress_begin(&e,"exportFile");
            if unsupported {progress_update_export(&e,crate::streamed_progress::ExportProgress{gpu:None,pass_updated:false,phase:"unsupported_adapter",done:0,total:0,display_stage:1,stage_done:0,stage_total:0});}
            let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
            for key in ["gpuFailureKind","gpuSubmitMs","gpuPollMs","gpuMapWaitMs","gpuFailedPhase"] {assert!(json[key].is_null(),"stale {key}");}
            if unsupported {assert_eq!(json["gpuFallbackReason"],"unsupported_adapter");}
        }
    }
    #[test] fn fallback_reason_survives_cpu_progress_cancel_and_resets_next_job() {
        let e=Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()};
        let job=progress_begin(&e,"exportFile");
        progress_update_export(&e,crate::streamed_progress::ExportProgress{gpu:None,pass_updated:false,phase:"gpu_memory_budget_fallback",done:0,total:0,display_stage:1,stage_done:0,stage_total:0});
        progress_update(&e,"film_exposure",1,9);progress_update(&e,"camera_diffusion",3,9);
        let _=progress_finish(&e,job,Some("CANCELLED"));let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
        assert_eq!(json["gpuFallbackReason"],"gpu_memory_budget_fallback");assert_eq!(json["stage"],"camera_diffusion");assert_eq!(json["status"],"cancelled");
        progress_begin(&e,"preview");let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert!(json["gpuFallbackReason"].is_null());
    }
    #[test] fn unsupported_adapter_reason_survives_cpu_progress_cancel_and_resets_next_job() {
        let e=Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()};
        let job=progress_begin(&e,"exportFile");
        progress_update_export(&e,crate::streamed_progress::ExportProgress{gpu:None,pass_updated:false,phase:"unsupported_adapter",done:0,total:0,display_stage:1,stage_done:0,stage_total:0});
        progress_update(&e,"film_exposure",1,9);progress_update(&e,"camera_diffusion",3,9);
        let _=progress_finish(&e,job,Some("CANCELLED"));let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();
        assert_eq!(json["gpuFallbackReason"],"unsupported_adapter");assert_eq!(json["stage"],"camera_diffusion");assert_eq!(json["status"],"cancelled");
        progress_begin(&e,"preview");let json:serde_json::Value=serde_json::from_str(&progress_json(&e).unwrap()).unwrap();assert!(json["gpuFallbackReason"].is_null());
    }
    #[test] fn progress_is_safe_and_resets_only_when_next_job_begins() {
        let e=Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()};
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

#[cfg(test)] mod gpu_completion_contract_tests {
    #[test] fn gpu_uncertain_blocks_subsequent_work_without_gpu_request() {
        assert!(super::completion_guard_for(false).is_ok());
        for _use_gpu in [true,false,false,true] {
            let error=super::completion_guard_for(true).unwrap_err();
            assert_eq!(error.code,"GPU_COMPLETION_UNCERTAIN");
            let json:serde_json::Value=serde_json::from_str(&super::error_json(error,"preview","validate_request",0.)).unwrap();
            assert_eq!(json["stage"],"scientific_pipeline");assert_eq!(json["outputValid"],false);
        }
    }
}

#[cfg(test)] mod shared_preview_metering_tests {
    use super::*;
    fn params()->MobileParams {
        let mut p=MobileParams::default();p.auto_exposure=true;p.film_exposure_ev=1.25;
        p.preview_metering=Some(PreviewMetering{algorithm:PREVIEW_METERING_ALGORITHM.into(),source_revision:"owned-snapshot".into(),mapping_revision:"oriented-original-v1:snapshot=owned-snapshot:meter-grid:linear-bilinear-center-v1".into(),data_revision:"data-v1".into(),owner:"owner-generation".into(),method:p.metering.clone(),width:256,height:192,ev:-2.5});p
    }
    #[test] fn preview_ev_does_not_become_user_or_print_compensation() {
        let p=params();p.validate().unwrap();let base=p.runtime();let resolved=p.apply_preview_metering(p.runtime());
        assert!(resolved.camera.auto_exposure);assert_eq!(resolved.camera.preview_metered_ev,Some(-2.5));
        assert_eq!(resolved.camera.exposure_compensation_ev,base.camera.exposure_compensation_ev);
        assert_eq!(resolved.enlarger.print_exposure_compensation,base.enlarger.print_exposure_compensation);
        assert_eq!(p.film_exposure_ev,1.25);assert_eq!(base.camera.preview_metered_ev,None);
    }
    #[test] fn invalid_contracts_and_export_are_rejected() {
        let p=params();assert!(plan_export(32,32,&p).is_err());
        let mut q=p.clone();q.mode="export".into();assert!(q.validate().is_err());
        let mut q=p.clone();q.auto_exposure=false;assert!(q.validate().is_err());
        for ev in [f32::NAN,f32::INFINITY,128.0] {let mut q=p.clone();q.preview_metering.as_mut().unwrap().ev=ev;assert!(q.validate().is_err());}
        let mut q=p.clone();q.preview_metering.as_mut().unwrap().method="average".into();assert!(q.validate().is_err());
        let mut q=p.clone();q.preview_metering.as_mut().unwrap().algorithm="old".into();assert!(q.validate().is_err());
        let mut q=p.clone();q.preview_metering.as_mut().unwrap().owner.clear();assert!(q.validate().is_err());
    }
    #[test] fn meter_preserves_hdr_and_rejects_nonfinite_or_oversized_grid() {
        let a=measure_preview(&[0.25;12],2,2,"average").unwrap();
        let b=measure_preview(&[4.0;12],2,2,"average").unwrap();assert!((a-b-4.0).abs()<0.00001);
        assert!(measure_preview(&[f32::NAN;3],1,1,"average").is_err());
        assert!(measure_preview(&[1.;3],1,1,"unknown").is_err());
        assert!(measure_preview(&[1.;3],257,1,"average").is_err());
    }
    #[test] fn detail_mapping_rejects_other_owner_orientation_and_missing_markers() {
        let p=params();let m=p.preview_metering.unwrap();
        assert!(m.matches_detail_mapping("oriented-original-v1:snapshot=owned-snapshot:bilinear-center-v1:1536x1152:grid=hash"));
        assert!(!m.matches_detail_mapping("oriented-original-v1:snapshot=other:bilinear-center-v1:1536x1152:grid=hash"));
        assert!(!m.matches_detail_mapping("rotated:snapshot=owned-snapshot:bilinear-center-v1:1536x1152:grid=hash"));
        assert!(!m.matches_detail_mapping(&m.mapping_revision));
    }
    #[test] fn direct_export_rejects_override_before_touching_files() {
        let e=Engine{data_dir:PathBuf::new(),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()};
        match export(&e,0,"nonexistent-input","nonexistent-output",32,32,&params()) {
            Err(e)=>assert_eq!(e.code,"INVALID_PARAMS"),Ok(_)=>panic!("Preview override reached export"),
        }
    }
    #[test] fn absent_contract_keeps_export_and_legacy_runtime_unchanged() {
        let p=MobileParams::default();assert!(!serde_json::to_string(&p).unwrap().contains("previewMetering"));
        assert!(plan_export(32,32,&p).is_ok());assert_eq!(p.runtime().camera.preview_metered_ev,None);
    }
    fn fixture_data()->PathBuf {Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
    struct TestEngine(i64);
    impl TestEngine {
        fn new()->Self {Self(create(fixture_data().to_str().unwrap()).unwrap())}
        fn engine(&self)->Arc<Engine>{get(self.0).unwrap()}
    }
    impl Drop for TestEngine {fn drop(&mut self){destroy(self.0);}}
    fn image_values(w:u32,h:u32)->Vec<f32> {
        (0..w*h*3).map(|i|0.0125+((i*37+(i/3/w)*17)%211)as f32/1600.).collect()
    }
    fn max_image_error(a:&[f32],b:&[f32])->f32 {
        assert_eq!(a.len(),b.len());assert!(a.iter().chain(b).all(|v|v.is_finite()));
        a.iter().zip(b).map(|(a,b)|(a-b).abs()).fold(0.,f32::max)
    }
    fn image_equivalence_cases(gpu:bool) {
        let owner=TestEngine::new();let engine=owner.engine();let(w,h)=(32,24);
        let input=image_values(w,h);let scaled:Vec<f32>=input.iter().map(|v|v*2.).collect();
        // EV=+1 has exactly representable f32 scaling. This avoids conflating
        // exposure ownership with arbitrary powf rounding; no crop/rescale/encoding
        // occurs before filming. Grain/glare are off so stochastic output is not an oracle.
        for (index,(normalize,print_comp,manual)) in [(true,true,-0.75),(true,false,0.5),(false,true,0.5),(false,false,-0.75)].into_iter().enumerate() {
            let mut p=params();p.use_gpu=gpu;p.grain_amount=0.;p.scan_glare_percent=0.;
            p.normalize_print_exposure=normalize;p.print_compensation=print_comp;p.film_exposure_ev=manual;
            p.preview_metering.as_mut().unwrap().ev=1.;
            assert!((measure_preview(&input,w,h,&p.metering).unwrap()-1.).abs()>0.1,"fixture must distinguish remetering");
            let actual=render(&engine,0,&input,w,h,&p).unwrap();
            assert_eq!(actual.metered_ev,Some(1.));
            let mut reference=p.clone();reference.auto_exposure=false;reference.preview_metering=None;
            assert_eq!(reference.film_exposure_ev,p.film_exposure_ev);
            let expected=render(&engine,0,&scaled,w,h,&reference).unwrap();assert_eq!(expected.metered_ev,None);
            for result in [&actual,&expected] {
                if gpu {
                    // No adapter skip and no CPU fallback success. Run only in the
                    // designated Vulkan window, matching GPU.4's real-route contract.
                    assert!(result.gpu.active,"real GPU required: {}",result.gpu.reason);
                    assert_eq!(result.gpu.backend,"wgpu");assert!(result.gpu.gpu_batches>0);
                    assert_eq!(result.gpu.cpu_fallbacks,0);assert!(result.gpu.numeric_guard_passed);
                }else {assert!(!result.gpu.active);assert!(!result.gpu.requested);}
            }
            let error=max_image_error(&actual.pixels,&expected.pixels);
            assert!(error<if gpu{2e-4}else{5e-6},"gpu={gpu} normalize={normalize} print_comp={print_comp} manual={manual}: {error}");
            if index==0 {
                let twice:Vec<f32>=input.iter().map(|v|v*4.).collect();
                let wrong=render(&engine,0,&twice,w,h,&reference).unwrap();
                if gpu {assert!(wrong.gpu.active,"negative control must also use real GPU");}
                assert!(max_image_error(&actual.pixels,&wrong.pixels)>1e-4,"fixture failed to distinguish double AE application");
                let mut changed=p.clone();changed.preview_metering.as_mut().unwrap().ev=2.;
                let refreshed=render(&engine,0,&input,w,h,&changed).unwrap();assert_eq!(refreshed.metered_ev,Some(2.));
                if gpu {assert!(refreshed.gpu.active);assert_eq!(refreshed.gpu.cpu_fallbacks,0);}
                assert!(max_image_error(&refreshed.pixels,&wrong.pixels)<if gpu{2e-4}else{5e-6},"pipeline cache ignored changed shared EV");
            }
            eprintln!("SHARED_AE_IMAGE_PASS gpu={gpu} normalize={normalize} print_comp={print_comp} manual={manual} max_abs={error}");
        }
    }
    #[test] fn shared_ev_cpu_image_matches_single_input_scale_with_print_controls() {
        image_equivalence_cases(false);
    }
    #[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
    #[test] fn shared_ev_real_gpu_image_matches_single_input_scale_with_print_controls() {
        image_equivalence_cases(true);
    }
    #[test] fn none_preserves_legacy_cpu_image_export_and_runtime_serialization() {
        use spektrafilm_gpu::cpu_backend::CpuBackend;
        let owner=TestEngine::new();let engine=owner.engine();let(w,h)=(29,21);let input=image_values(w,h);
        let mut p=MobileParams::default();p.use_gpu=false;p.auto_exposure=true;p.grain_amount=0.;p.film_exposure_ev=-0.5;
        let data=fixture_data();let film=load_profile_by_name(&data,&p.film_stock).unwrap();let paper=load_profile_by_name(&data,&p.paper_stock).unwrap();
        let legacy=p.runtime_for_film(&film);
        let legacy_json=serde_json::to_string(&legacy).unwrap();
        assert!(!legacy_json.contains("preview_metered_ev"));
        assert_eq!(legacy_json,serde_json::to_string(&p.preview_runtime_for_film(&film)).unwrap());
        let roundtrip:RuntimeParams=serde_json::from_str(&legacy_json).unwrap();assert_eq!(roundtrip.camera.preview_metered_ev,None);
        // Independent original runtime route, not the mobile cached preview helper.
        let pipe=Pipeline::new_with_spectral(film,paper,legacy.clone(),&data).unwrap();
        let full=pipe.process(ImageBuf::from_data(w,h,input.iter().map(|v|from_f64(*v as f64)).collect()),&CpuBackend);
        let expected:Vec<f32>=full.data.iter().map(|v|*v as f32).collect();
        let actual=render(&engine,0,&input,w,h,&p).unwrap();
        assert_eq!(actual.metered_ev,Some(measure_preview(&input,w,h,&p.metering).unwrap()));
        assert!(max_image_error(&actual.pixels,&expected)<5e-6);
        static NEXT:AtomicU64=AtomicU64::new(0);
        struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
        let root=Temp(std::env::temp_dir().join(format!("shared-ae-none-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed))));std::fs::create_dir(&root.0).unwrap();
        let source=root.0.join("input.f32le");let output=root.0.join("output.f32le");let scratch=root.0.join("scratch");
        std::fs::write(&source,input.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
        crate::streamed::export_file(&data,&p.film_stock,&p.paper_stock,legacy,&source,&output,&scratch,w,h,384,0.,||false,|_,_,_|{}).unwrap();
        let exported:Vec<f32>=std::fs::read(&output).unwrap().chunks_exact(4).map(|v|f32::from_le_bytes(v.try_into().unwrap())).collect();
        assert!(max_image_error(&exported,&expected)<5e-6);assert!(!scratch.exists());
    }
}

#[cfg(test)]
mod encoded_reuse_probe_tests {
    #[test]
    fn cinestill_optical_revision_invalidates_encoded_payload_reuse() {
        assert_eq!(super::encoded_reuse_probe(false).unwrap()["algorithm"],"streamed-cpu-v1-cinestill4g-v1");
        #[cfg(feature="adreno-gpu")]
        assert!(crate::gpu_export::ALGORITHM.ends_with("-cinestill4g-v1-donor-portra-v1"));
    }
    #[test]
    fn cpu_probe_is_versioned_and_never_claims_gpu() {
        let probe=super::encoded_reuse_probe(false).unwrap();
        assert_eq!(probe["version"],1);assert_eq!(probe["backend"],"cpu");
        assert!(probe["deviceOwner"].is_null());assert!(probe["precision"].is_string());
    }
    #[cfg(feature="adreno-gpu")]
    #[test]
    fn gpu_probe_never_initializes_or_submits_and_obeys_health() {
        let before=super::FULL_CHAIN_GPU.get().is_some();
        let probe=super::encoded_reuse_probe(true);
        assert_eq!(before,super::FULL_CHAIN_GPU.get().is_some());
        let device=super::FULL_CHAIN_GPU.get().and_then(|v|v.as_ref());
        assert_eq!(probe.is_some(),device.is_some_and(|g|g.is_healthy()));
        if let Some(probe)=probe {
            assert_eq!(probe["backend"],"wgpu");assert_eq!(probe["algorithm"],crate::gpu_export::ALGORITHM);
            assert!(!probe["deviceOwner"].as_str().unwrap().is_empty());
            assert_eq!(probe["binding"].as_u64(),device.map(|g|g.max_storage_buffer_binding_size()));
        }
    }
    #[cfg(not(feature="adreno-gpu"))]
    #[test]
    fn non_gpu_build_cannot_issue_a_gpu_receipt(){assert!(super::encoded_reuse_probe(true).is_none());}
}

#[cfg(test)] mod preview_brightness_cache_tests {
    use super::*;
    fn source()->PreviewSourceIdentity {PreviewSourceIdentity{source_revision:"owned-photo-a".into(),mapping_revision:"oriented-32x24-linear-v1".into(),data_revision:"fixture-data".into()}}
    fn params()->MobileParams {let mut p=MobileParams::default();p.use_gpu=false;p.preview_source=Some(source());p}
    struct Owner(i64);impl Owner {fn new()->Self{Self(create(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data").to_str().unwrap()).unwrap())}}
    impl Drop for Owner {fn drop(&mut self){destroy(self.0);}}
    fn input(w:u32,h:u32)->Vec<f32>{(0..w*h*3).map(|i|0.005+((i*31+7)%257)as f32/800.).collect()}
    fn assert_pixels(a:&[f32],b:&[f32]){assert_eq!(a.iter().map(|v|v.to_bits()).collect::<Vec<_>>(),b.iter().map(|v|v.to_bits()).collect::<Vec<_>>());assert_eq!(linear_srgb_to_rgba8(a),linear_srgb_to_rgba8(b));}
    #[test] fn omitted_profiles_survive_mobile_cache_revision_and_render() {
        let owner=Owner::new();let e=get(owner.0).unwrap();
        assert!(!e.data_dir.join("profiles/none.json").exists());
        let input=input(8,8);
        for use_gpu in [false,true] { for film in ["none","kodak_portra_400"] { for paper in ["none","kodak_portra_endura"] {
            let mut p=params();p.use_gpu=use_gpu;p.film_stock=film.into();p.paper_stock=paper.into();p.grain_amount=0.;
            p.validate().unwrap();
            refresh_cache_revision(&e,&p).unwrap();
            let first=render(&e,0,&input,8,8,&p).unwrap();
            assert_eq!(first.pixels.len(),input.len());assert!(first.pixels.iter().all(|v|v.is_finite()));
            let repeated=render(&e,0,&input,8,8,&p).unwrap();assert_pixels(&first.pixels,&repeated.pixels);
        }}}
        let mut invalid=params();invalid.film_stock="missing_real_profile".into();
        assert!(refresh_cache_revision(&e,&invalid).is_err(),"real missing profiles must still fail");
    }
    #[test] fn key_excludes_only_brightness_and_covers_every_upstream_field() {
        let owner=Owner::new();let e=get(owner.0).unwrap();let p=params();let key=preview_stage_key(&e,&p,32,24,"cpu").unwrap();
        let mut q=p.clone();q.brightness_ev=2.;assert_eq!(key,preview_stage_key(&e,&q,32,24,"cpu").unwrap());
        let all=serde_json::to_value(&p).unwrap();
        for (name,value) in all.as_object().unwrap() {
            if name=="brightnessEv"{continue;}
            let mut changed=all.clone();changed[name]=match value {
                serde_json::Value::Bool(v)=>serde_json::json!(!v),
                serde_json::Value::Number(v)=>if v.is_f64(){serde_json::json!(v.as_f64().unwrap()+0.125)}else{serde_json::json!(v.as_u64().unwrap()+1)},
                serde_json::Value::String(v)=>serde_json::json!(format!("{v}-changed")),
                serde_json::Value::Object(_)=>{let mut v=value.clone();v["sourceRevision"]=serde_json::json!("other-source");v},
                _=>panic!("Extend cache key test for new parameter {name}"),
            };
            let changed:MobileParams=serde_json::from_value(changed).unwrap();
            assert_ne!(key,preview_stage_key(&e,&changed,32,24,"cpu").unwrap(),"ignored {name}");
        }
        assert_ne!(key,preview_stage_key(&e,&p,24,32,"cpu").unwrap());
        assert_ne!(key,preview_stage_key(&e,&p,32,24,"wgpu:other").unwrap());
        e.pipeline_epoch.fetch_add(1,Ordering::AcqRel);assert_ne!(key,preview_stage_key(&e,&p,32,24,"cpu").unwrap());
    }
    #[test] fn cpu_science_hit_matches_cold_brightness_gamut_profiles_and_optics() {
        let owner=Owner::new();let e=get(owner.0).unwrap();let input=input(32,24);
        for case in 0..6 {
            let mut p=params();
            match case {
                1=>{p.output_color_space="Display P3".into();p.output_gamut=false;},
                2=>{p.film_stock="kodak_doublex".into();p.paper_stock="kodak_2302".into();},
                3=>{p.output_route="direct".into();},
                4=>{p.diffusion_filter="black_pro_mist".into();p.print_diffusion_filter="black_pro_mist".into();p.scan_blur=0.4;p.scan_sharpen_amount=0.4;},
                5=>{p.auto_exposure=true;p.preview_metering=Some(PreviewMetering{algorithm:PREVIEW_METERING_ALGORITHM.into(),source_revision:"owned-photo-a".into(),mapping_revision:"oriented-original-v1:snapshot=owned-photo-a:meter-grid:linear-bilinear-center-v1".into(),data_revision:"fixture-data".into(),owner:"ae-owner".into(),method:p.metering.clone(),width:32,height:24,ev:0.25});},
                _=>{},
            }
            p.validate().unwrap();
            set_preview_cache_budget(owner.0,128*1024).unwrap();let first=render(&e,0,&input,32,24,&p).unwrap();assert!(!first.preview_cache.hit);
            for ev in [-2.,0.,2.] {
                p.brightness_ev=ev;let hit=render(&e,0,&input,32,24,&p).unwrap();assert!(hit.preview_cache.hit,"case {case}");assert_eq!(hit.preview_cache.science_ms,0.);
                set_preview_cache_budget(owner.0,0).unwrap();let cold=render(&e,0,&input,32,24,&p).unwrap();assert!(!cold.preview_cache.hit);assert_pixels(&hit.pixels,&cold.pixels);
                set_preview_cache_budget(owner.0,128*1024).unwrap();render(&e,0,&input,32,24,&p).unwrap();
            }
        }
    }
    #[test] fn source_size_generation_cancel_pressure_and_destroy() {
        let owner=Owner::new();let e=get(owner.0).unwrap();let mut p=params();let input=input(32,24);
        set_preview_cache_budget(owner.0,128*1024).unwrap();render(&e,0,&input,32,24,&p).unwrap();
        cancel(owner.0);assert!(render(&e,0,&input,32,24,&p).is_err());
        let generation=e.cancel_generation.load(Ordering::Acquire);assert!(render(&e,generation,&input,32,24,&p).unwrap().preview_cache.hit);
        p.preview_source.as_mut().unwrap().source_revision="source-b".into();assert!(!render(&e,generation,&input,32,24,&p).unwrap().preview_cache.hit);
        assert!(!render(&e,generation,&input,24,32,&p).unwrap().preview_cache.hit);
        set_preview_cache_budget(owner.0,0).unwrap();assert_eq!(e.preview_stage_cache.lock().unwrap().bytes(),0);
        assert!(!render(&e,generation,&input,24,32,&p).unwrap().preview_cache.hit);
        destroy(owner.0);assert!(render(&e,generation,&input,24,32,&p).is_err());assert_eq!(e.preview_stage_cache.lock().unwrap().bytes(),0);
    }
    #[test] fn concurrent_pressure_during_cold_candidate_never_publishes_after_retirement() {
        use std::sync::mpsc;
        for allocated in [false,true] {
            let owner=Owner::new();let e=get(owner.0).unwrap();set_preview_cache_budget(owner.0,128*1024).unwrap();
            let (ready,seen)=mpsc::channel();let (release,resume)=mpsc::channel();
            let work=e.clone();let thread=std::thread::spawn(move||{
                PREVIEW_CANDIDATE_TEST_HOOK.with(|slot|*slot.borrow_mut()=Some(Box::new(move|is_allocated|{
                    if is_allocated==allocated {ready.send(()).unwrap();resume.recv().unwrap();}
                })));
                let result=render(&work,0,&input(32,24),32,24,&params()).unwrap();
                PREVIEW_CANDIDATE_TEST_HOOK.with(|slot|*slot.borrow_mut()=None);result
            });
            seen.recv_timeout(std::time::Duration::from_secs(120)).unwrap();
            // No render_lock wait: retire while the cold writer is still alive.
            set_preview_cache_budget(owner.0,0).unwrap();assert_eq!(e.preview_stage_cache.lock().unwrap().bytes(),0);
            release.send(()).unwrap();let result=thread.join().unwrap();
            assert!(!result.preview_cache.hit);assert_eq!(result.preview_cache.retained_bytes,0);
            let cold=render(&e,0,&input(32,24),32,24,&params()).unwrap();assert_pixels(&result.pixels,&cold.pixels);
        }
    }
    #[cfg(all(feature="adreno-gpu",feature="precision-f64"))]
    #[test] fn real_resident_gpu_hit_reports_no_new_submissions_and_matches_cold() {
        let owner=Owner::new();let e=get(owner.0).unwrap();let mut p=params();p.use_gpu=true;let input=input(32,24);
        set_preview_cache_budget(owner.0,128*1024).unwrap();let first=render(&e,0,&input,32,24,&p).unwrap();
        assert!(first.gpu.active && first.gpu.backend=="wgpu" && first.gpu.cpu_fallbacks==0,"real GPU required");
        p.brightness_ev=2.;let hit=render(&e,0,&input,32,24,&p).unwrap();assert!(hit.preview_cache.hit);assert!(!hit.gpu.active);assert_eq!(hit.gpu.gpu_batches,0);
        set_preview_cache_budget(owner.0,0).unwrap();let cold=render(&e,0,&input,32,24,&p).unwrap();assert!(cold.gpu.active);assert_pixels(&hit.pixels,&cold.pixels);
        p.use_gpu=false;assert!(!render(&e,0,&input,32,24,&p).unwrap().preview_cache.hit);
    }
}

#[cfg(test)]
mod prepared_asset_admission_tests {
    use super::*;
    fn owner()->Arc<Engine> {
        Arc::new(Engine {data_dir:Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data"),cancel_generation:AtomicU64::new(0),prewarm_generation:AtomicU64::new(0),destroyed:AtomicBool::new(false),
            render_lock:Mutex::new(()),progress:Mutex::new(ProgressState::default()),profile_cache:Mutex::new(HashMap::new()),cache_revision:Mutex::new(None),pipeline_cache:Mutex::new(None),pipeline_epoch:AtomicU64::new(0),preview_stage_cache:Mutex::new(StageCache::default()),
            prepared_spectral_cache:spektrafilm_core::prepared_spectral::PreparedSpectralCache::disabled()})
    }
    fn install_pipeline(engine:&Engine)->Arc<Pipeline> {
        let film=load_profile_by_name(&engine.data_dir,"kodak_portra_400").unwrap();let paper=load_profile_by_name(&engine.data_dir,"kodak_portra_endura").unwrap();
        let mut p=RuntimeParams::default();p.settings.rgb_to_raw_method="mallett2019".into();
        let revision=spektrafilm_core::prepared_spectral::SpectralAssetRevision::read(&engine.data_dir,"mallett2019");
        let pipeline=Arc::new(Pipeline::new_with_spectral_cached_controlled(film,paper,p,&engine.data_dir,&engine.prepared_spectral_cache,revision,||true).unwrap());
        *engine.pipeline_cache.lock().unwrap()=Some(CachedPipeline{key:"fixture".into(),pipeline:pipeline.clone()});pipeline
    }
    #[test]
    fn prepared_assets_require_exact_admission_and_are_disabled_initially() {
        let engine=owner();assert!(!prepared_spectral_cache_stats(&engine).enabled);
        assert!(set_prepared_spectral_cache_budget(&engine,1).is_err());
        set_prepared_spectral_cache_budget(&engine,PREPARED_ASSET_BUDGET_BYTES).unwrap();
        assert!(prepared_spectral_cache_stats(&engine).enabled);
        drop(install_pipeline(&engine));assert_eq!(prepared_spectral_cache_stats(&engine).live_assets,1);
        assert!(prepared_spectral_cache_stats(&engine).calibration_retained_bytes>0);
        set_prepared_spectral_cache_budget(&engine,0).unwrap();assert_eq!(prepared_spectral_cache_stats(&engine).live_assets,0);
        assert_eq!(prepared_spectral_cache_stats(&engine).calibration_retained_bytes,0);
        assert_eq!(prepared_spectral_cache_stats(&engine).live_bytes,0);
        assert!(engine.pipeline_cache.lock().unwrap().is_none());
    }
    #[test]
    fn zero_budget_waits_for_native_render_then_releases_every_arc() {
        let engine=owner();set_prepared_spectral_cache_budget(&engine,PREPARED_ASSET_BUDGET_BYTES).unwrap();
        let guard=engine.render_lock.lock().unwrap();let pipeline=install_pipeline(&engine);
        let(started,started_rx)=std::sync::mpsc::channel();let(done,done_rx)=std::sync::mpsc::channel();
        let worker_engine=engine.clone();let worker=std::thread::spawn(move||{started.send(()).unwrap();done.send(set_prepared_spectral_cache_budget(&worker_engine,0).map(|_|())).unwrap();});
        started_rx.recv().unwrap();assert!(done_rx.try_recv().is_err());
        assert_eq!(prepared_spectral_cache_stats(&engine).live_assets,1);
        drop(pipeline);drop(guard);
        done_rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap().unwrap();worker.join().unwrap();
        assert_eq!(prepared_spectral_cache_stats(&engine).live_assets,0);assert!(engine.pipeline_cache.lock().unwrap().is_none());
        assert_eq!(prepared_spectral_cache_stats(&engine).calibration_retained_bytes,0);
        assert_eq!(prepared_spectral_cache_stats(&engine).live_bytes,0);
    }
    #[test]
    fn unexpected_external_arc_refuses_retirement_ack_until_it_drops() {
        let engine=owner();set_prepared_spectral_cache_budget(&engine,PREPARED_ASSET_BUDGET_BYTES).unwrap();let unexpected=install_pipeline(&engine);
        assert!(set_prepared_spectral_cache_budget(&engine,0).is_err());assert_eq!(prepared_spectral_cache_stats(&engine).live_assets,1);
        drop(unexpected);set_prepared_spectral_cache_budget(&engine,0).unwrap();assert_eq!(prepared_spectral_cache_stats(&engine).live_assets,0);
    }
}

#[cfg(test)]
mod idle_prewarm_owner_tests {
    use super::*;
    fn owner()->(i64,Arc<Engine>){let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let id=create(root.to_str().unwrap()).unwrap();(id,get(id).unwrap())}
    #[test] fn omitted_film_prewarm_skips_without_reading_a_virtual_profile(){
        let(id,engine)=owner();set_prepared_spectral_cache_budget(&engine,PREPARED_ASSET_BUDGET_BYTES).unwrap();
        let mut p=MobileParams::default();p.film_stock="none".into();
        let packet:serde_json::Value=serde_json::from_str(&prewarm_front(&engine,0,&p).unwrap()).unwrap();
        assert_eq!(packet["outcome"],"no_film");assert_eq!(engine.prepared_spectral_cache.stats().publications,0);
        assert!(engine.profile_cache.lock().unwrap().is_empty());assert!(engine.pipeline_cache.lock().unwrap().is_none());destroy(id);
    }
    #[test] fn prewarm_generation_does_not_cancel_interactive_generation(){
        let(id,engine)=owner();let foreground=engine.cancel_generation.load(Ordering::Acquire);
        invalidate_prewarm(id,5);assert_eq!(engine.cancel_generation.load(Ordering::Acquire),foreground);
        assert_eq!(prewarm_front(&engine,4,&MobileParams::default()).unwrap_err().code,"CANCELLED");
        assert_eq!(engine.prepared_spectral_cache.stats().publications,0);destroy(id);
    }
    #[test] fn prewarm_never_waits_for_active_native_work(){
        let(id,engine)=owner();let guard=engine.render_lock.lock().unwrap();
        assert_eq!(prewarm_front(&engine,0,&MobileParams::default()).unwrap_err().code,"ENGINE_BUSY");
        drop(guard);destroy(id);
    }
    #[test] fn prewarm_disabled_or_closed_owner_cannot_publish_or_install_a_pipeline(){
        let(id,engine)=owner();let packet:serde_json::Value=serde_json::from_str(&prewarm_front(&engine,0,&MobileParams::default()).unwrap()).unwrap();
        assert_eq!(packet["outcome"],"disabled");assert!(engine.pipeline_cache.lock().unwrap().is_none());assert!(engine.profile_cache.lock().unwrap().is_empty());
        destroy(id);assert_eq!(prewarm_front(&engine,0,&MobileParams::default()).unwrap_err().code,"CANCELLED");
        assert_eq!(engine.prepared_spectral_cache.stats().publications,0);
    }
}

#[cfg(test)]
#[path = "preview_film_log_tests.rs"]
mod preview_film_log_tests;

#[cfg(test)]
mod spektralab_stock_tests {
    use super::*;
    fn profile(id:&str)->Profile {load_profile_by_name(&Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data"),id).unwrap()}
    #[test]
    fn alias_800t_uses_backing_and_still_frame_halo_not_500t_baseline() {
        let p=MobileParams::default();
        let a=p.runtime_for_film(&profile("cinestill_800t"));
        let b=p.runtime_for_film(&profile("kodak_vision3_500t"));
        assert_eq!(a.film_render.halation.halation_strength,[0.30,0.03,0.003]);
        assert!(a.film_render.halation.back_reflection);
        assert!(a.film_render.halation.headroom_active());
        assert!(!b.film_render.halation.back_reflection);
        assert_eq!(a.film_render.halation.halation_first_sigma_um,[65.;3]);
        assert_eq!(b.film_render.halation.halation_strength,[0.015,0.005,0.]);
        assert_eq!(b.film_render.halation.halation_first_sigma_um,[50.;3]);
        assert_eq!(a.camera.exposure_compensation_ev,b.camera.exposure_compensation_ev);
    }
    #[test]
    fn new_silver_profiles_ignore_dye_controls_without_erasing_stored_values() {
        for id in ["kodak_tri_x_400","kodak_tmax_100","fujifilm_neopan_acros_100_ii","ilford_hp5_plus_400"] {
            let mut p=MobileParams::default();p.film_stock=id.into();p.paper_stock="ilford_multigrade_iv_rc".into();
            p.saturation=1.8;p.dir_amount=0.75;p.grain_red_scale=2.;p.grain_green_scale=0.4;p.warmth=0.8;p.print_yellow=13.;p.paper_gamma_red=1.5;
            let runtime=p.runtime_for_film(&profile(id));
            assert_eq!(runtime.film_render.dir_couplers.amount,0.75);
            assert_eq!(runtime.film_render.grain.agx_particle_scale,RuntimeParams::default().film_render.grain.agx_particle_scale);
            assert!(!runtime.print_render.density_curves_morph.active);
            assert_eq!(runtime.enlarger.y_filter_shift,13.);
            assert_eq!(p.saturation,1.8);assert_eq!(p.warmth,0.8);assert_eq!(p.paper_gamma_red,1.5);
            p.print_illuminant="D65".into();assert!(!p.runtime().settings.neutral_print_filters_from_database);
        }
    }

    #[test]
    fn cinestill_model_is_an_explicit_product_opt_in_not_a_metadata_migration() {
        let p=MobileParams::default();
        let mut other=profile("cinestill_800t");
        other.info.stock=Some("historical_no_backing_film".into());
        let r=p.runtime_for_film(&other);
        assert!(!r.film_render.halation.back_reflection);
        assert!(!r.film_render.halation.headroom_active());
        assert_eq!(r.film_render.halation.halation_strength,[0.30,0.10,0.015]);
        for id in FILMS.iter().filter(|id| **id!="cinestill_800t") {
            assert!(!p.runtime_for_film(&profile(id)).film_render.halation.back_reflection,"{id}");
        }
        let mut off=p.clone();off.halation_amount=0.;off.scatter_amount=1.;
        assert!(!off.runtime_for_film(&profile("cinestill_800t")).film_render.halation.headroom_active());
    }
}

#[cfg(test)]
mod spektralab_halation_render_tests {
    use super::*;
    #[test]
    fn removed_backing_changes_a_highlight_render_and_not_measured_exposure() {
        let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let mut p=MobileParams::default();p.grain_amount=0.;p.scatter_amount=0.;p.dir_amount=0.;p.film_format_mm=35.;
        p.output_route="direct".into();p.use_gpu=false;
        let (w,h)=(32,24);let mut input=vec![from_f64(0.01);(w*h*3)as usize];
        for y in 10..14 {for x in 14..18 {for c in 0..3 {input[((y*w+x)*3+c)as usize]=from_f64(5.);}}}
        let render=|id:&str,halo:f64| {
            let film=load_profile_by_name(&root,id).unwrap();let paper=load_profile_by_name(&root,"kodak_2383").unwrap();
            let mut p=p.clone();p.halation_amount=halo;
            let pipe=Pipeline::new_with_spectral(film.clone(),paper,p.runtime_for_film(&film),&root).unwrap();
            pipe.process(ImageBuf::from_data(w,h,input.clone()),&spektrafilm_gpu::cpu_backend::CpuBackend)
        };
        assert_eq!(render("cinestill_800t",0.).data,render("kodak_vision3_500t",0.).data);
        let a=render("cinestill_800t",1.);let b=render("kodak_vision3_500t",1.);
        assert!(a.data.iter().zip(&b.data).all(|(x,y)|x.is_finite()&&y.is_finite()));
        assert!(a.data.iter().zip(&b.data).any(|(x,y)|(x-y).abs()>1e-5));
    }
}
