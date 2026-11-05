//! Full grain research graph. The guarded candidate preserves original random
//! words and binary64 sampler inputs, with conservative experimental rejection
//! intervals. Spatial and microstructure output arithmetic remains guarded f32.
//! Calibration/table preparation stays on CPU; active legacy preview requires
//! the explicit guarded factory and must never use the coordinate-only prototype.
use crate::research_spatial::{self as spatial, FilmOpticsPlan, Filter, MIB};
use serde::{Deserialize, Serialize};
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline};
pub type R<T> = Result<T, String>;
pub const CHUNK_PIXELS: u32 = 65536;
pub const LOG_FACTORIAL_BASE: u32 = 262144;
pub const LOG_FACTORIAL_COUNT: u32 = 262144;
pub const TABLE_BASE: u32 = 524288;
pub const CHUNK_BASE: u32 = 786432;
pub const MAX_LAMBDA: f32 = 240000.;
pub const SAMPLER_SHADER: &str = include_str!("shaders/research_grain_sampler.wgsl");
pub const GRAPH_SHADER: &str = include_str!("shaders/research_grain.wgsl");
pub mod sampler;
pub mod preview_rng;
pub mod soft64;
pub mod guarded_sampler;
pub mod guarded_graph;
#[cfg(all(test,feature="precision-f64"))]
pub mod guarded_cpu;
pub use guarded_graph::RngMode;
pub mod full_stage;
#[cfg(feature = "gpu-diffusion-research")]
pub mod gpu;
#[cfg(feature = "gpu-diffusion-research")]
pub mod guarded_gpu;
pub fn shader() -> String {
    let needle=" if(op==0u || op==1u){";
    assert_eq!(spatial::SHADER.matches(needle).count(), 1);
    spatial::SHADER.replace(needle," if(op>=20u){grain_main(i);return;}\n if(op==0u || op==1u){") + SAMPLER_SHADER + GRAPH_SHADER
}
pub(crate) mod alu_programs;
mod bits {
    use serde::{Deserialize,Deserializer,Serialize,Serializer};
    pub fn serialize<S:Serializer>(v:&f64,s:S)->Result<S::Ok,S::Error>{format!("{:016x}",v.to_bits()).serialize(s)}
    pub fn deserialize<'de,D:Deserializer<'de>>(d:D)->Result<f64,D::Error>{
        let s=String::deserialize(d)?;
        if s.len()!=16 || !s.bytes().all(|c|c.is_ascii_digit()||(b'a'..=b'f').contains(&c)){return Err(serde::de::Error::custom("invalid grain bits"));}
        u64::from_str_radix(&s,16).map(f64::from_bits).map_err(serde::de::Error::custom)
    }
}
mod vec_bits {
    use serde::{Deserialize,Deserializer,Serialize,Serializer};
    pub fn serialize<S:Serializer>(v:&Vec<f64>,s:S)->Result<S::Ok,S::Error>{v.iter().map(|x|format!("{:016x}",x.to_bits())).collect::<Vec<_>>().serialize(s)}
    pub fn deserialize<'de,D:Deserializer<'de>>(d:D)->Result<Vec<f64>,D::Error>{Vec::<String>::deserialize(d)?.into_iter().map(|s|{
        if s.len()!=16 || !s.bytes().all(|c|c.is_ascii_digit()||(b'a'..=b'f').contains(&c)){return Err(serde::de::Error::custom("invalid grain bits"));}
        u64::from_str_radix(&s,16).map(f64::from_bits).map_err(serde::de::Error::custom)
    }).collect()}
}
#[derive(Clone,Debug,PartialEq,Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct Layer {
    pub seed:u64,
    #[serde(with="bits")] pub density_min:f64,
    #[serde(with="bits")] pub density_max:f64,
    #[serde(with="bits")] pub particles:f64,
    #[serde(with="bits")] pub uniformity:f64,
    #[serde(with="vec_bits")] pub axis:Vec<f64>,
    #[serde(with="vec_bits")] pub values:Vec<f64>,
    pub cloud_sigma:f32,
}
impl Layer {
    pub fn mapped_f64(&self,original:f64,positive:bool)->f64 {
        if !original.is_finite(){return f64::NAN;}
        if self.axis.is_empty(){return original+self.density_min;}
        let q=if positive{-original}else{original};let last=self.axis.len()-1;
        let value=if q<=self.axis[0]{self.values[0]}else if q>=self.axis[last]{self.values[last]}else{
            let hi=self.axis.partition_point(|&v|v<=q).min(last);let lo=hi-1;let dx=self.axis[hi]-self.axis[lo];
            let t=if dx!=0.{(q-self.axis[lo])/dx}else{0.};self.values[lo]+t*(self.values[hi]-self.values[lo])
        };value+self.density_min
    }
    pub fn mapped_f32(&self,original:f32,positive:bool)->f32 {
        if !original.is_finite(){return f32::NAN;}
        if self.axis.is_empty(){return original+self.density_min as f32;}
        let q=if positive{-original}else{original};let last=self.axis.len()-1;
        let value=if q<=self.axis[0] as f32{self.values[0] as f32}else if q>=self.axis[last] as f32{self.values[last] as f32}else{
            let hi=self.axis.partition_point(|&v|v as f32<=q).min(last);let lo=hi-1;let dx=self.axis[hi] as f32-self.axis[lo] as f32;
            let t=if dx!=0.{(q-self.axis[lo] as f32)/dx}else{0.};self.values[lo] as f32+t*(self.values[hi] as f32-self.values[lo] as f32)
        };value+self.density_min as f32
    }
}
#[derive(Clone,Debug,PartialEq,Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
pub struct GrainPlan {
    pub width:u32,pub height:u32,pub active:bool,pub sublayers:bool,pub positive:bool,pub monochrome:bool,
    pub seed:u64,pub origin:[u32;2],pub rng_mode:RngMode,pub layers:[Vec<Layer>;3],
    #[serde(with="bits")] pub amount:f64,
    #[serde(with="vec_bits")] pub density_min:Vec<f64>,
    pub final_sigma:f32,pub micro_active:bool,pub micro_spread:f32,
    #[serde(with="bits")] pub micro_sigma_log:f64,
    #[serde(with="bits")] pub micro_mu:f64,
}
impl GrainPlan {
    pub fn new(pipe:&Pipeline,pitch:f32,width:u32,height:u32)->R<Self> {Self::new_inner(pipe,pitch,width,height,false)}
    /// Explicit opt-in for the guarded sampler; never routes legacy MT to f32 coordinate sampling.
    pub fn new_guarded(pipe:&Pipeline,pitch:f32,width:u32,height:u32)->R<Self> {Self::new_inner(pipe,pitch,width,height,true)}
    fn new_inner(pipe:&Pipeline,pitch:f32,width:u32,height:u32,allow_legacy:bool)->R<Self> {
        if !pitch.is_finite() || pitch<=0. {return Err("GPU_GRAIN_UNSUPPORTED".into());}
        let g=&pipe.params.film_render.grain;
        let active=g.active && g.amount!=0.;
        let origin=match g.coordinate_origin {Some(origin)=>origin,None if !active||allow_legacy=>[0,0],None=>return Err("GPU_GRAIN_SEQUENTIAL_PENDING".into())};
        let rng_mode=if g.coordinate_origin.is_some(){RngMode::CoordinateSplitMix64}else{RngMode::SequentialMt19937};
        let norm=spektrafilm_model::density_curves::normalize_density_curves_f64(&pipe.film.density_curves_f64());
        if active && !(2..=2048).contains(&norm.len()){return Err("GPU_GRAIN_UNSUPPORTED_CURVES".into());}
        let n=if g.sublayers_active{3}else{g.n_sub_layers.max(1) as usize};
        if n>64 {return Err("GPU_GRAIN_LAYER_LIMIT".into());}
        let original=&pipe.film.data.density_curves_layers;
        let mut maxima=[[0f64;3];3];
        if active && g.sublayers_active {
            if original.len()!=norm.len() || original.iter().any(|r|r.len()<3||r.iter().take(3).any(|v|v.len()<3)){return Err("GPU_GRAIN_UNSUPPORTED_CURVES".into());}
            for row in original {for sl in 0..3 {for ch in 0..3 {maxima[sl][ch]=maxima[sl][ch].max(row[sl][ch]);}}}
        }
        let dmax=spektrafilm_model::density_curves::max_density_f64(&norm);
        let sign=if pipe.film.is_positive(){-1.}else{1.};
        let layers=std::array::from_fn(|ch|if !active{vec![]}else{(0..n).map(|sl|{
            let(dmin,maximum,particles)=if g.sublayers_active {
                let total:f64=(0..3).map(|i|maxima[i][ch]).sum();let f=maxima[sl][ch]/total;let dmin=f*g.density_min[ch];
                (dmin,maxima[sl][ch]+dmin,(pitch as f64).powi(2)*f/(g.agx_particle_area_um2*g.agx_particle_scale[ch]*g.agx_particle_scale_layers[sl]))
            }else{(g.density_min[ch],dmax[ch]+g.density_min[ch],(pitch as f64).powi(2)/(g.agx_particle_area_um2*g.agx_particle_scale[ch])/n as f64)};
            let cloud=(g.blur_dye_clouds_um as f64*(maximum/particles).sqrt()) as f32;
            Layer {seed:g.seed.wrapping_add(if g.monochrome{0}else{ch as u64}).wrapping_add(sl as u64*10),density_min:dmin,density_max:maximum,particles,uniformity:g.uniformity[ch],
                axis:if g.sublayers_active{norm.iter().map(|r|sign*r[ch]).collect()}else{vec![]},
                values:if g.sublayers_active{original.iter().map(|r|r[sl][ch]).collect()}else{vec![]},
                cloud_sigma:if g.sublayers_active && g.blur_dye_clouds_um>0.4 && cloud>0.1 {cloud}else{0.}}
        }).collect()});
        let sigma=g.micro_structure[1] as f64*0.001/pitch as f64;
        let micro_active=active && g.sublayers_active && sigma>0.05;
        let sigma_log=if micro_active{(1.+sigma*sigma).ln().sqrt()}else{0.};
        let result=Self {width,height,active,sublayers:g.sublayers_active,positive:pipe.film.is_positive(),monochrome:g.monochrome,seed:g.seed,origin,rng_mode,layers,amount:g.amount,density_min:g.density_min.to_vec(),
            final_sigma:if active && (if g.sublayers_active{g.blur>0.}else{g.blur>0.4}){g.blur}else{0.},
            micro_active,micro_spread:if micro_active && g.micro_structure[0]/pitch>0.4{g.micro_structure[0]/pitch}else{0.},micro_sigma_log:sigma_log,micro_mu:-0.5*sigma_log*sigma_log};
        result.validate()?;Ok(result)
    }
    pub fn spatial_plan(&self)->R<FilmOpticsPlan>{let mut p=RuntimeParams::default();p.film_render.halation.active=false;p.camera.lens_blur_um=0.;FilmOpticsPlan::new(&p,1.,self.width,self.height)}
    pub fn validate(&self)->R<()> {
        self.spatial_plan()?.validate()?;
        if self.rng_mode==RngMode::SequentialMt19937&&self.origin!=[0,0]{return Err("INVALID_GRAIN_RNG_MODE".into());}
        if !finite(self.amount) || self.density_min.len()!=3 || self.density_min.iter().any(|&x|!finite(x)) || !finite(self.micro_mu)||!finite(self.micro_sigma_log)||self.micro_sigma_log<0. {return Err("INVALID_GRAIN_PLAN".into());}
        if (!self.active&&(self.micro_active||self.final_sigma>0.))||(self.micro_active&&!self.sublayers){return Err("INVALID_GRAIN_PLAN".into());}
        let count=self.layers[0].len();
        if self.layers.iter().any(|v|v.len()!=count) || (self.active && (count==0 || count>64 || (self.sublayers && count!=3))) || (!self.active && count!=0) {return Err("INVALID_GRAIN_PLAN".into());}
        let mut scalars=0;
        for layer in self.layers.iter().flatten(){
            if !finite(layer.density_min)||!finite(layer.density_max)||layer.density_max<=0.||!finite(layer.particles)||layer.particles<=0.||!finite(layer.uniformity)||!(0. ..=1.).contains(&layer.uniformity) {return Err("INVALID_GRAIN_PLAN".into());}
            // Admit the necessary minimum rate here. Actual per-pixel lambda is
            // checked with original binary64 density on the device; a worst-case
            // p=1 profile bound wrongly rejects otherwise valid normal previews.
            if layer.particles>guarded_graph::MAX_RATE{return Err("GPU_GRAIN_COUNT_LIMIT".into());}
            if layer.axis.len()!=layer.values.len() || (!layer.axis.is_empty() && !(2..=2048).contains(&layer.axis.len())) || self.sublayers==layer.axis.is_empty() {return Err("INVALID_GRAIN_CURVE".into());}
            if layer.axis.iter().chain(&layer.values).any(|&x|!finite(x)) {return Err("GPU_GRAIN_UNSUPPORTED_CURVES".into());}
            // Preserve measured nonmonotonic toes/shoulders and duplicate knots.
            // The shader follows the original Rust partition_point walk; never sort.
            if !self.sublayers&&layer.cloud_sigma>0.{return Err("INVALID_GRAIN_PLAN".into());}
            scalars+=layer.axis.len()+layer.values.len();Filter::new(layer.cloud_sigma)?;
        }
        Filter::new(self.final_sigma)?;Filter::new(self.micro_spread)?;
        if TABLE_BASE as usize+scalars>CHUNK_BASE as usize || CHUNK_BASE as u64*4+CHUNK_PIXELS as u64*4>spatial::TRANSFER_BYTES{return Err("GPU_GRAIN_MEMORY_LIMIT".into());}
        Ok(())
    }
    pub fn sigmas(&self)->Vec<f32>{let mut v:Vec<f32>=self.layers.iter().flatten().filter_map(|l|(l.cloud_sigma>0.).then_some(l.cloud_sigma)).collect();if self.micro_active&&self.micro_spread>0.{v.push(self.micro_spread);}if self.final_sigma>0.{v.push(self.final_sigma);}v}
    pub fn pass_count(&self)->u64{self.layers.iter().flatten().filter(|l|l.cloud_sigma>0.).count() as u64+3*u64::from(self.micro_active&&self.micro_spread>0.)+3*u64::from(self.final_sigma>0.)}
    pub fn layer_count(&self)->u32{self.layers[0].len() as u32}
    pub fn chunk_pixels(&self)->u32{self.rng_mode.chunk_pixels(self.width as u64*self.height as u64)}
    pub fn chunk_count(&self)->u64{(self.width as u64*self.height as u64).div_ceil(self.chunk_pixels() as u64)}
    pub fn disk_bytes(&self)->u64{48*self.width as u64*self.height as u64+2*MIB}
    pub fn numerical_preflight(&self,c:&mut impl FnMut()->bool)->R<()>{self.validate()?;spatial::numerical_preflight_for_sigmas(self.width,self.height,self.sigmas(),c)}
    pub fn micro_seed(&self,ch:usize)->u64{self.seed.wrapping_add(0x51f15e+if self.monochrome{0}else{ch as u64})}
}
pub(crate) fn finite(x:f64)->bool{x.is_finite()&&(x as f32).is_finite()}
pub(crate) fn blur_mirror(values:&[f64],w:u32,h:u32,sigma:f32,c:&mut impl FnMut()->bool)->R<Vec<f64>>{
    spatial::channel_cpu_mirror(values,w,h,&spatial::ChannelPlan {lens_sigma:sigma,scatter:None,bounce_sigmas:vec![],bounce_weights:vec![],strength:0.,divisor:1.},c)
}
/// Whole-image host arithmetic mirror. Tests only; not a production CPU/GPU hybrid.
pub fn cpu_mirror(p:&GrainPlan,input:&[Vec<f64>;3],c:&mut impl FnMut()->bool)->R<[Vec<f64>;3]>{
    p.numerical_preflight(c)?;let n=p.width as usize*p.height as usize;
    if input.iter().any(|v|v.len()!=n||v.iter().any(|&x|!finite(x))){return Err("NONFINITE_GRAIN_INPUT".into());}
    if !p.active{return Ok(input.clone());}
    let table=sampler::factorial_table();let mut result=std::array::from_fn(|_|Vec::new());
    for ch in 0..3 {
        let mut accum=vec![0f32;n];
        for layer in &p.layers[ch]{
            let mut sampled=Vec::with_capacity(n);
            for (i,&v) in input[ch].iter().enumerate(){if i%256==0{spatial::check(c)?;}
                sampled.push(sampler::particle(layer.seed,p.origin[0].wrapping_add(i as u32%p.width),p.origin[1].wrapping_add(i as u32/p.width),layer.mapped_f32(v as f32,p.positive),layer.density_max as f32,layer.particles as f32,layer.uniformity as f32,&table)?.value as f64);
            }
            if layer.cloud_sigma>0.{sampled=blur_mirror(&sampled,p.width,p.height,layer.cloud_sigma,c)?;}
            for(a,b)in accum.iter_mut().zip(sampled){*a+=b as f32;}
        }
        if p.micro_active {
            let mut field:Vec<f64>=(0..n).map(|i|sampler::microstructure(p.micro_seed(ch),p.origin[0].wrapping_add(i as u32%p.width),p.origin[1].wrapping_add(i as u32/p.width),p.micro_sigma_log as f32,p.micro_mu as f32) as f64).collect();
            if p.micro_spread>0.{field=blur_mirror(&field,p.width,p.height,p.micro_spread,c)?;}
            for(a,b)in accum.iter_mut().zip(field){*a*=b as f32;}
        }
        let average=if p.sublayers{1.}else{1./p.layer_count() as f64} as f32;
        let mut output:Vec<f64>=accum.iter().map(|&v|(v*average-p.density_min[ch] as f32) as f64).collect();
        if p.final_sigma>0.{output=blur_mirror(&output,p.width,p.height,p.final_sigma,c)?;}
        if p.amount!=1.{for(i,v)in output.iter_mut().enumerate(){let original=input[ch][i] as f32;*v=(original+p.amount as f32*(*v as f32-original)) as f64;}}
        if output.iter().any(|v|!v.is_finite()){return Err("NONFINITE_GRAIN_OUTPUT".into());}result[ch]=output;
    }Ok(result)
}
#[cfg(test)] mod tests;
