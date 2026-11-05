// Generated exact ALU ABI v1. Code arrays are immutable after host upload.
// The graph owns source/dispatch geometry and must return padded lanes before begin.
struct GrainFrame {prepared:array<f32,72>,alu_registers:array<vec2<u32>,32>,}
struct GrainScratch {poisson_code:array<vec4<u32>,93>,binomial_code:array<vec4<u32>,207>,frames:array<GrainFrame>,}
@group(0) @binding(8) var<storage,read_write> grain_scratch:GrainScratch;
var<private> grain_alu_lane:u32;
var<private> grain_alu_ready:bool;
fn grain_alu_reject()->bool{sf_failed=1u;gg_fail();return false;}
fn grain_alu_begin(lane:u32,active_lanes:u32)->bool{
 if(grain_alu_ready||active_lanes==0u||lane>=active_lanes){return grain_alu_reject();}
 let capacity=arrayLength(&grain_scratch.frames);
 if((capacity!=256u&&capacity!=16384u)||active_lanes>capacity){return grain_alu_reject();}
 for(var program=0u;program<3u;program=program+1u){if(!sr_palu_program_valid(program)){return grain_alu_reject();}}
 for(var program=0u;program<4u;program=program+1u){if(!sr_alu_program_valid(program)){return grain_alu_reject();}}
 grain_alu_lane=lane;grain_alu_ready=true;return true;
}
struct SrPaluSpec {start:u32,length:u32,initialized:u32,outputs:u32,a:u32,b:u32,c:u32,d:u32,e:u32,f:u32,}
struct SrPaluResult {a:vec2<u32>,b:vec2<u32>,c:vec2<u32>,d:vec2<u32>,e:vec2<u32>,f:vec2<u32>,}
fn sr_palu_spec(program:u32)->SrPaluSpec{
 switch program{
  case 0u:{return SrPaluSpec(0u,36u,1u,4u,2u,2u,2u,2u,2u,2u);}
  case 1u:{return SrPaluSpec(36u,35u,127u,222u,7u,6u,4u,1u,2u,3u);}
  case 2u:{return SrPaluSpec(71u,22u,1u,1u,0u,0u,0u,0u,0u,0u);}
  default:{return SrPaluSpec(0u,0u,0u,0u,0u,0u,0u,0u,0u,0u);}
 }
}
fn sr_palu_reject()->bool{sf_failed=1u;gg_fail();return false;}
fn sr_palu_program_valid(program:u32)->bool{
 if(program>=3u){return false;}let spec=sr_palu_spec(program);
 if(spec.length==0u||spec.length>36u||spec.start>=93u){return false;}
 if(spec.length>93u-spec.start){return false;}
 var initialized=spec.initialized;
 for(var pc=0u;pc<36u;pc=pc+1u){
  if(pc>=spec.length){break;}let instruction=grain_scratch.poisson_code[spec.start+pc];
  if(instruction.x>6u||instruction.y>=32u){return false;}
  if(instruction.x!=0u){
   if(instruction.z>=32u){return false;}
   if((initialized&(1u<<instruction.z))==0u){return false;}
   if(instruction.x<=3u){
    if(instruction.w>=32u){return false;}
    if((initialized&(1u<<instruction.w))==0u){return false;}
   }else if(instruction.w!=0u){return false;}
  }
  initialized=initialized|(1u<<instruction.y);
 }
 return (initialized&spec.outputs)==spec.outputs;
}
fn sr_palu_run(program:u32)->SrPaluResult{
 if(!grain_alu_ready||program>=3u){sf_failed=1u;gg_fail();return SrPaluResult(SF_NAN,SF_NAN,SF_NAN,SF_NAN,SF_NAN,SF_NAN);}
 let spec=sr_palu_spec(program);
 for(var pc=0u;pc<36u;pc=pc+1u){
  if(pc>=spec.length){break;}let instruction=grain_scratch.poisson_code[spec.start+pc];
  switch instruction.x{
   case 0u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=vec2<u32>(instruction.z,instruction.w);}
   case 1u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_add(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z],grain_scratch.frames[grain_alu_lane].alu_registers[instruction.w]);}
   case 2u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_mul(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z],grain_scratch.frames[grain_alu_lane].alu_registers[instruction.w]);}
   case 3u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_div(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z],grain_scratch.frames[grain_alu_lane].alu_registers[instruction.w]);}
   case 4u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_log(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z]);}
   case 5u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_abs(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z]);}
   case 6u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_neg(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z]);}
   default:{sf_failed=1u;gg_fail();return SrPaluResult(SF_NAN,SF_NAN,SF_NAN,SF_NAN,SF_NAN,SF_NAN);}
  }
 }
 return SrPaluResult(grain_scratch.frames[grain_alu_lane].alu_registers[spec.a],grain_scratch.frames[grain_alu_lane].alu_registers[spec.b],grain_scratch.frames[grain_alu_lane].alu_registers[spec.c],grain_scratch.frames[grain_alu_lane].alu_registers[spec.d],grain_scratch.frames[grain_alu_lane].alu_registers[spec.e],grain_scratch.frames[grain_alu_lane].alu_registers[spec.f]);
}
struct SrAluSpec {start:u32,length:u32,initialized:u32,outputs:u32,a:u32,b:u32,c:u32,d:u32,e:u32,}
struct SrAluResult {a:vec2<u32>,b:vec2<u32>,c:vec2<u32>,d:vec2<u32>,e:vec2<u32>,}
fn sr_alu_spec(program:u32)->SrAluSpec{
 switch program{
  case 0u:{return SrAluSpec(0u,16u,7u,10u,3u,1u,3u,3u,3u);}
  case 1u:{return SrAluSpec(16u,17u,7u,18u,4u,1u,4u,4u,4u);}
  case 2u:{return SrAluSpec(33u,32u,7u,59u,4u,0u,1u,3u,5u);}
  case 3u:{return SrAluSpec(65u,142u,32767u,2052u,11u,2u,11u,11u,11u);}
  default:{return SrAluSpec(0u,0u,0u,0u,0u,0u,0u,0u,0u);}
 }
}
fn sr_alu_reject()->bool{sf_failed=1u;gg_fail();return false;}
fn sr_alu_program_valid(program:u32)->bool{
 if(program>=4u){return false;}let spec=sr_alu_spec(program);
 if(spec.length==0u||spec.length>142u||spec.start>=207u){return false;}
 if(spec.length>207u-spec.start){return false;}
 var initialized=spec.initialized;
 for(var pc=0u;pc<142u;pc=pc+1u){
  if(pc>=spec.length){break;}let instruction=grain_scratch.binomial_code[spec.start+pc];
  if(instruction.x>6u||instruction.y>=32u){return false;}
  if(instruction.x!=0u){
   if(instruction.z>=32u){return false;}
   if((initialized&(1u<<instruction.z))==0u){return false;}
   if(instruction.x<=3u){
    if(instruction.w>=32u){return false;}
    if((initialized&(1u<<instruction.w))==0u){return false;}
   }else if(instruction.w!=0u){return false;}
  }
  initialized=initialized|(1u<<instruction.y);
 }
 return (initialized&spec.outputs)==spec.outputs;
}
fn sr_alu_run(program:u32)->SrAluResult{
 if(!grain_alu_ready||program>=4u){sf_failed=1u;gg_fail();return SrAluResult(SF_NAN,SF_NAN,SF_NAN,SF_NAN,SF_NAN);}
 let spec=sr_alu_spec(program);
 for(var pc=0u;pc<142u;pc=pc+1u){
  if(pc>=spec.length){break;}let instruction=grain_scratch.binomial_code[spec.start+pc];
  switch instruction.x{
   case 0u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=vec2<u32>(instruction.z,instruction.w);}
   case 1u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_add(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z],grain_scratch.frames[grain_alu_lane].alu_registers[instruction.w]);}
   case 2u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_mul(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z],grain_scratch.frames[grain_alu_lane].alu_registers[instruction.w]);}
   case 3u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_div(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z],grain_scratch.frames[grain_alu_lane].alu_registers[instruction.w]);}
   case 4u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_log(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z]);}
   case 5u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_abs(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z]);}
   case 6u:{grain_scratch.frames[grain_alu_lane].alu_registers[instruction.y]=sf_neg(grain_scratch.frames[grain_alu_lane].alu_registers[instruction.z]);}
   default:{sf_failed=1u;gg_fail();return SrAluResult(SF_NAN,SF_NAN,SF_NAN,SF_NAN,SF_NAN);}
  }
 }
 return SrAluResult(grain_scratch.frames[grain_alu_lane].alu_registers[spec.a],grain_scratch.frames[grain_alu_lane].alu_registers[spec.b],grain_scratch.frames[grain_alu_lane].alu_registers[spec.c],grain_scratch.frames[grain_alu_lane].alu_registers[spec.d],grain_scratch.frames[grain_alu_lane].alu_registers[spec.e]);
}
