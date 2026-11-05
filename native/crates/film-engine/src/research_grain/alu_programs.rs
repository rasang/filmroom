//! Exact ALU programs and typed binding-8 ABI, generated from reviewed proofs.
//! Original software64/RNG/control bodies remain source-version gated.
use sha2::{Digest,Sha256};
type R<T> = Result<T,String>;
const ERROR:&str="GPU_GRAIN_ALU_VERSION";
pub const ABI_VERSION:u32=1;
pub const CODE_BYTES:u64=4800;
pub const FRAME_BYTES:u64=544;
pub const PREVIEW_CAPACITY:u32=256;
pub const EXPORT_CAPACITY:u32=16384;
pub const CODE_PREFIX:&[u8;4800]=include_bytes!("alu_programs/code_prefix.bin");
pub const CODE_PREFIX_SHA256:&str="174c583bdfff912bcbe1c8a7ea1ca178fce8abcad83300e4be8c6f0ff0fa6834";
const ABI_DESCRIPTOR_SHA256:&str="996c4fd00e7b53276d1afbc43acc52c24fca5ee2309c4f0d64deae1666955958";
pub const SUPPORT_SHADER:&str=include_str!("../shaders/research_grain_alu_support.wgsl");
const POISSON_SHADER:&str=include_str!("../shaders/research_grain_alu_poisson.wgsl");
const BINOMIAL_SHADER:&str=include_str!("../shaders/research_grain_alu_binomial.wgsl");
const PREPARE_BINOMIAL_SHADER:&str=include_str!("../shaders/research_grain_alu_prepare_binomial.wgsl");
const ORIGINAL_SAMPLER_SHA256:&str="60d84acf6a050fbc2129f012083007b72b39ef7379d9467cd3647599d413a5aa";
const ORIGINAL_SOFT64_SHA256:&str="7173a5998b5fc2d22434f12cd07b5b81091e25d4f5ac05c2923fe79947fd5ce1";
const SUPPORT_SHA256:&str="8ad35c753bebf8615df3aa3538f2a881f3adac78762e3c3accaac56085a5ef5b";
const POISSON_SHA256:&str="e6a85fef06b913aadeeee096b7eab8464077640237643d5a4a3387b169c98841";
const BINOMIAL_SHA256:&str="73b21289f6772fa9753a1afb3b2ae51594140c1838f5f5600cfc56bdaf51ab33";
const PREPARE_BINOMIAL_SHA256:&str="1b0e3892515b77461d5d61fd527f116b1e08f70c0ad7d569bb4cfabdd11473d6";
const POISSON_PROGRAMS:&[[u32;4]]=&[[0, 36, 1, 4], [36, 35, 127, 222], [71, 22, 1, 1]];
const BINOMIAL_PROGRAMS:&[[u32;4]]=&[[0, 16, 7, 10], [16, 17, 7, 18], [33, 32, 7, 59], [65, 142, 32767, 2052]];
fn fail<T>()->R<T>{Err(ERROR.into())}
fn digest(bytes:&[u8])->String{format!("{:x}",Sha256::digest(bytes))}
pub fn allocation_bytes(capacity:u32)->R<u64>{
 if capacity!=PREVIEW_CAPACITY&&capacity!=EXPORT_CAPACITY{return Err("GPU_GRAIN_ALU_CAPACITY".into());}
 CODE_BYTES.checked_add(FRAME_BYTES.checked_mul(capacity as u64).ok_or(ERROR)?).ok_or_else(||ERROR.into())
}
fn validate_code(bytes:&[u8],programs:&[[u32;4]])->R<()>{
 if bytes.len()%16!=0{return fail();}
 for &[start,length,mut initialized,outputs] in programs {
  let end=start.checked_add(length).ok_or(ERROR)? as usize;
  if length==0||end>bytes.len()/16{return fail();}
  for row in bytes[start as usize*16..end*16].chunks_exact(16){
   let read=|i:usize|u32::from_le_bytes(row[i*4..i*4+4].try_into().unwrap());
   let(op,dst,a,b)=(read(0),read(1),read(2),read(3));
   if op>6||dst>=32{return fail();}
   if op!=0{
    if a>=32||initialized&(1u32<<a)==0{return fail();}
    if op<=3{if b>=32||initialized&(1u32<<b)==0{return fail();}}else if b!=0{return fail();}
   }
   initialized|=1u32<<dst;
  }
  if initialized&outputs!=outputs{return fail();}
 }Ok(())
}
/// Exact identity is mandatory. Structural validation alone is not an identity proof.
pub fn validate_prefix(bytes:&[u8])->R<()>{
 if bytes.len()!=CODE_BYTES as usize||bytes!=CODE_PREFIX.as_slice()||digest(bytes)!=CODE_PREFIX_SHA256{return fail();}
 let mut descriptor=Vec::new();
 for n in [ABI_VERSION,CODE_BYTES as u32,FRAME_BYTES as u32,PREVIEW_CAPACITY,EXPORT_CAPACITY]{descriptor.extend_from_slice(&n.to_le_bytes());}
 for program in POISSON_PROGRAMS.iter().chain(BINOMIAL_PROGRAMS){for n in program{descriptor.extend_from_slice(&n.to_le_bytes());}}
 if digest(&descriptor)!=ABI_DESCRIPTOR_SHA256{return fail();}
 validate_code(&bytes[..1488],POISSON_PROGRAMS)?;validate_code(&bytes[1488..],BINOMIAL_PROGRAMS)?;
 if digest(SUPPORT_SHADER.as_bytes())!=SUPPORT_SHA256||digest(POISSON_SHADER.as_bytes())!=POISSON_SHA256||digest(BINOMIAL_SHADER.as_bytes())!=BINOMIAL_SHA256||digest(PREPARE_BINOMIAL_SHADER.as_bytes())!=PREPARE_BINOMIAL_SHA256{return fail();}Ok(())
}
pub fn validated_prefix()->R<&'static [u8]>{validate_prefix(CODE_PREFIX)?;Ok(CODE_PREFIX)}
fn body<'a>(source:&'a str,name:&str)->R<(usize,usize,&'a str)>{
 let token=format!("fn {name}(");if source.matches(&token).count()!=1{return fail();}
 let start=source.find(&token).ok_or(ERROR)?;let open=source[start..].find('{').ok_or(ERROR)?+start;
 let mut depth=0u32;
 for (i,b) in source.bytes().enumerate().skip(open){
  if b==b'{'{depth+=1;}else if b==b'}'{depth-=1;if depth==0{return Ok((start,i+1,&source[start..i+1]));}}
 }fail()
}
/// Refuse helper/body drift rather than silently reapply an older arithmetic tape.
pub fn compose(original_guarded_sampler:&str)->R<String>{
 validate_prefix(CODE_PREFIX)?;
 if digest(original_guarded_sampler.as_bytes())!=ORIGINAL_SAMPLER_SHA256||digest(include_str!("../shaders/research_grain_soft64.wgsl").as_bytes())!=ORIGINAL_SOFT64_SHA256{return fail();}
 let mut composed=original_guarded_sampler.to_owned();
 for (name,replacement,expected) in [
  ("sr_log_factorial",POISSON_SHADER,"92be60cc11e8cf211c5490d6e4bd8da19eb79a0d24add809f7d336a66bf231a4"),
  ("sr_log_factorial_error",POISSON_SHADER,"ebbb168dfb557b9d2c2b0aac603d0ebadf9fbb41569494908eec9833287af08a"),
  ("sr_poisson_prepared",POISSON_SHADER,"7f4edefa4492b5c7e85cae5d8feec3388334e1c3d9ed0cf02fad890b4d8d7ba0"),
  ("sr_btpe_prepared",BINOMIAL_SHADER,"52398514ff7d3e7cb9d9204d16af7202f5819198d11aa7931f588a744d12b638"),
  ("sr_binomial_prepared",BINOMIAL_SHADER,"2616953e59f55870dbd13b1e068248e68e1bde9faf0bf3ce0177ad3562a1e509"),
  ("sr_prepare_binomial",PREPARE_BINOMIAL_SHADER,"02ec78ba5bb1ae71905c4d9617e5f527b631932c9d07df825bd88816b5801226"),
 ]{
  let(start,end,old)=body(&composed,name)?;if digest(old.as_bytes())!=expected{return fail();}
  let(_,_,new)=body(replacement,name)?;composed.replace_range(start..end,new);
 }
 composed.push('\n');composed.push_str(SUPPORT_SHADER);Ok(composed)
}
#[cfg(test)]mod tests{
 use super::*;
 #[test]fn frozen_code_and_capacity_contract(){validate_prefix(CODE_PREFIX).unwrap();assert_eq!(validated_prefix().unwrap().len(),4800);assert_eq!(allocation_bytes(256).unwrap(),144064);assert_eq!(allocation_bytes(16384).unwrap(),8917696);for n in [0,1,255,257,16383,16385,u32::MAX]{assert!(allocation_bytes(n).is_err());}}
 #[test]fn altered_code_never_admitted(){for at in [0,15,1487,1488,4799]{let mut changed=CODE_PREFIX.to_vec();changed[at]^=1;assert!(validate_prefix(&changed).is_err());}assert!(validate_prefix(&CODE_PREFIX[..4799]).is_err());}
 #[test]fn original_sampler_and_helpers_are_version_gated(){let original=include_str!("../shaders/research_grain_guarded_sampler.wgsl");let composed=compose(original).unwrap();assert!(composed.contains("grain_scratch.frames[grain_alu_lane].alu_registers"));assert_eq!(composed.matches("@binding(8)").count(),1);assert!(compose(&(original.to_owned()+"\n")).is_err());}
 #[test]fn prepared_binomial_wrapper_is_exact_and_invertible(){
  let original=include_str!("../shaders/research_grain_guarded_sampler.wgsl");let composed=compose(original).unwrap();
  let(_,_,old)=body(original,"sr_prepare_binomial").unwrap();let(_,_,new)=body(&composed,"sr_prepare_binomial").unwrap();
  assert_eq!(new,PREPARE_BINOMIAL_SHADER.trim_end());assert_eq!(digest(new.as_bytes()),"8be747d3ee436d994d717b13f0de99080013b8ba69c04061f7a209d5f200b105");
  assert_eq!(new.matches("sr_prepare_inversion(").count(),1);assert_eq!(new.matches("sr_prepare_btpe(").count(),1);
  let restored=composed.replacen(new,old,1);assert_eq!(body(&restored,"sr_prepare_binomial").unwrap().2,old);
  for name in ["sr_prepare_inversion","sr_prepare_btpe","sr_prepare_poisson"]{assert_eq!(body(&composed,name).unwrap().2,body(original,name).unwrap().2);}
 }
 #[test]fn no_code_stores_or_prepared_writes_in_math(){for source in [SUPPORT_SHADER,POISSON_SHADER,BINOMIAL_SHADER,PREPARE_BINOMIAL_SHADER]{assert!(!source.contains(".prepared["));assert!(!source.contains("sr_palu.registers"));assert!(!source.contains("sr_alu.registers"));}assert!(SUPPORT_SHADER.contains("capacity!=256u&&capacity!=16384u"));}
}
