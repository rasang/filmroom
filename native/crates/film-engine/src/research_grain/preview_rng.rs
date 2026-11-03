//! Exact legacy preview MT19937 word stream. This is NOT a preview sampler or
//! a claim that preview grain has moved to the GPU. Sequential Poisson-all then
//! binomial-all and f64-equivalent guarded decisions remain integration gates.
//! A coordinate stream must never substitute for this global legacy stream.
pub const SHADER:&str=include_str!("../shaders/research_grain_preview_rng.wgsl");
pub const STATE_BASE:u32=80000;
pub const STATE_FLOATS:u32=1254;
#[derive(Clone)]
pub struct Mt19937 {state:[u32;624],index:usize,pub words:u64}
impl Mt19937 {
    pub fn new(seed:u64)->Self {
        let mut state=[0u32;624];state[0]=seed as u32;
        for i in 1..624 {state[i]=1812433253u32.wrapping_mul(state[i-1]^(state[i-1]>>30)).wrapping_add(i as u32);}
        Self{state,index:624,words:0}
    }
    pub fn next_u32(&mut self)->u32 {
        if self.index>=624 {
            for i in 0..624 {let y=(self.state[i]&0x80000000)|(self.state[(i+1)%624]&0x7fffffff);
                self.state[i]=self.state[(i+397)%624]^(y>>1)^if y&1!=0{0x9908b0df}else{0};}
            self.index=0;
        }
        let mut y=self.state[self.index];self.index+=1;self.words+=1;
        y^=y>>11;y^=(y<<7)&0x9d2c5680;y^=(y<<15)&0xefc60000;y^=y>>18;y
    }
    pub fn uniform_numerator(&mut self)->u64{((self.next_u32() as u64>>5)<<26)|(self.next_u32() as u64>>6)}
    pub fn packed(&self)->Vec<f32>{let mut v=Vec::with_capacity(STATE_FLOATS as usize);
        for &x in &self.state {v.push((x&65535)as f32);v.push((x>>16)as f32);}
        v.push(self.index as f32);v.push(0.);for i in 0..4{v.push(((self.words>>(i*16))&65535)as f32);}v
    }
    pub fn unpack(v:&[f32])->Result<Self,&'static str>{
        if v.len()!=STATE_FLOATS as usize||v.iter().any(|x|!x.is_finite()||*x<0.||*x>65535.||x.fract()!=0.)||v[1248]>624.||v[1249]!=0.{return Err("INVALID_PREVIEW_MT_STATE");}
        let state=std::array::from_fn(|i|v[2*i] as u32|((v[2*i+1] as u32)<<16));let words=(0..4).fold(0u64,|a,i|a|((v[1250+i] as u64)<<(i*16)));
        Ok(Self {state,index:v[1248] as usize,words})
    }
}
#[cfg(test)] mod tests {
    use super::*;
    #[test]fn original_mt_words_seed_truncation_and_twist_boundaries(){for seed in[0,42,u32::MAX as u64,u64::MAX,1u64<<32]{let oracle=spektrafilm_model::grain::legacy_random_words(seed,10000);let mut m=Mt19937::new(seed);assert_eq!((0..10000).map(|_|m.next_u32()).collect::<Vec<_>>(),oracle);}}
    #[test]fn snapshot_chunk_boundaries_do_not_restart_stream(){let mut full=Mt19937::new(42);let expected:Vec<u32>=(0..20000).map(|_|full.next_u32()).collect();let mut m=Mt19937::new(42);let mut actual=vec![];for count in[1,623,1,625,4096,14654]{for _ in 0..count{actual.push(m.next_u32());}m=Mt19937::unpack(&m.packed()).unwrap();}assert_eq!(actual,expected);assert_eq!(m.words,20000);}
    #[test]fn exact_original_uniform_consumption(){let words=spektrafilm_model::grain::legacy_random_words(42,10000);let mut m=Mt19937::new(42);for pair in words.chunks_exact(2){let expected=((pair[0] as u64>>5)<<26)|(pair[1] as u64>>6);assert_eq!(m.uniform_numerator(),expected);}assert_eq!(m.words,10000);}
    #[test]fn preview_rng_shader_uses_only_integer_words(){
        let mut s=crate::research_spatial::SHADER.replace(" if(op==0u || op==1u){"," if(op>=40u){preview_rng_main(i);return;}\n if(op==0u || op==1u){");s.push_str(SHADER);
        let m=naga::front::wgsl::parse_str(&s).unwrap_or_else(|e|panic!("{}",e.emit_to_string(&s)));naga::valid::Validator::new(naga::valid::ValidationFlags::all(),naga::valid::Capabilities::empty()).validate(&m).unwrap();
    }
}
