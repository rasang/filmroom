//! Session-only JNI bridge. A stage seal is NOT a process-death proof.
//! Every successful stage returns after its Rust packet/file owners have dropped,
//! the context has no live runners and the GPU queue has completed. Any failure
//! retires the epoch; Android retains its lease until helper death is confirmed.
use jni::{JNIEnv, objects::{JObject,JString}, sys::{jlong,jstring}};
use serde::Deserialize;
use serde_json::{json,Value};
use std::{path::Path,panic::{catch_unwind,AssertUnwindSafe}};
use crate::{gpu_session as session,gpu_session_contract::StageTicket};
type R<T> = Result<T,String>;
#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Config {schema_version:u32,epoch:u64,prepaid_bytes:u64,fixed_bytes:u64,pipeline_limit:usize}
fn text(env:&mut JNIEnv<'_>,s:&JString<'_>)->R<String>{
 if s.is_null(){return Err("INVALID_ARGUMENT".into())}
 let n=env.call_method(s,"length","()I",&[]).and_then(|v|v.i()).map_err(|_|"INVALID_ARGUMENT")?;
 if !(0..=4096).contains(&n){return Err("INVALID_ARGUMENT".into())}
 let v:String=env.get_string(s).map_err(|_|"INVALID_ARGUMENT")?.into();
 if v.len()>4096{return Err("INVALID_ARGUMENT".into())} Ok(v)
}
fn reply(env:&mut JNIEnv<'_>,r:R<Value>)->jstring{
 let v=r.unwrap_or_else(|code|json!({"ok":false,"schemaVersion":1,"stageSealed":false,"code":code}));
 let s=v.to_string(); let s=if s.len()<=16384{s}else{"{\"ok\":false,\"stageSealed\":false,\"code\":\"RESULT_TOO_LARGE\"}".into()};
 env.new_string(s).map(|v|v.into_raw()).unwrap_or(std::ptr::null_mut())
}
fn positive(v:jlong)->R<u64>{u64::try_from(v).ok().filter(|v|*v>0).ok_or_else(||"INVALID_ARGUMENT".into())}
fn plan(operation:&str,dir:&Path,token:u64)->R<Value>{
 if crate::research_streaming_route::backend(operation).is_some() { return crate::research_streaming_route::plan(operation,dir,token); }
 match operation {
  "fullCameraDiffusion"|"fullPrintDiffusion" => serde_json::to_value(crate::research_diffusion::full_stage::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullFilmExposure" => serde_json::to_value(crate::research_exposure::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullFilmOptics" => serde_json::to_value(crate::research_spatial::full_stage::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullFilmDirDensity" => serde_json::to_value(crate::research_dir::full_stage::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullExposureLogDir" => serde_json::to_value(crate::research_log_dir::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullAutoExposure"|"fullHighlightBoost"|"fullExposureLog" => serde_json::to_value(crate::research_global::full_stage::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullFilmGrain" => serde_json::to_value(crate::research_grain::full_stage::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullPrintExposure"|"fullPrintScanLogXyz" => serde_json::to_value(crate::research_finish::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullScanTail" => serde_json::to_value(crate::research_finish_tail::full_stage::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  "fullFinishTail" => serde_json::to_value(crate::research_finish::fused::plan_stage(dir,token)?).map_err(|_|"INVALID_SESSION_PLAN".into()),
  _=>Err("INVALID_SCIENCE_OPERATION".into())
 }
}
fn run(operation:&str,dir:&Path,token:u64,budget:u64,sha:&str,epoch:u64)->R<Value>{
 if crate::research_streaming_route::backend(operation).is_some() { return crate::research_streaming_route::run(operation,dir,token,budget,sha,||session::is_cancelled(epoch)); }
 match operation {
  "fullCameraDiffusion"|"fullPrintDiffusion" => crate::research_diffusion::full_stage::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullFilmExposure" => crate::research_exposure::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullFilmOptics" => crate::research_spatial::full_stage::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullFilmDirDensity" => crate::research_dir::full_stage::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullExposureLogDir" => crate::research_log_dir::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullAutoExposure"|"fullHighlightBoost"|"fullExposureLog" => crate::research_global::full_stage::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullFilmGrain" => crate::research_grain::full_stage::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullPrintExposure"|"fullPrintScanLogXyz" => crate::research_finish::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullScanTail" => crate::research_finish_tail::full_stage::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  "fullFinishTail" => crate::research_finish::fused::run_stage(dir,token,budget,sha,||session::is_cancelled(epoch)),
  _=>Err("INVALID_SCIENCE_OPERATION".into())
 }
}
fn stage(ticket:StageTicket,operation:&str,dir:&Path,budget:u64,token:u64,sha:&str)->R<Value>{
 let p=plan(operation,dir,token)?;
 if p["operation"].as_str()!=Some(operation)||p["manifestSha256"].as_str()!=Some(sha)||p["token"].as_u64()!=Some(token){return Err("INVALID_SESSION_PLAN".into())}
 let required=p["combinedPeakBytes"].as_u64().ok_or("INVALID_SESSION_PLAN")?;
 if budget<required{return Err("INSUFFICIENT_GPU_STAGE_MEMORY".into())}
 let host=p["hostBytes"].as_u64().ok_or("INVALID_SESSION_PLAN")?;
 let helper=p["helperAllowanceBytes"].as_u64().ok_or("INVALID_SESSION_PLAN")?;
 session::validate_fixed_reserve(ticket.epoch,host.checked_add(helper).ok_or("INVALID_SESSION_PLAN")?)?;
 let driver=p["driverAllowanceBytes"].as_u64().ok_or("INVALID_SESSION_PLAN")?;
 session::begin_stage(ticket,driver)?;
 let summary=run(operation,dir,token,budget,sha,ticket.epoch)?;
 if summary["ok"].as_bool()!=Some(true){return Err("GPU_SESSION_STAGE_FAILED".into())}
 // The complete run invocation and its lexical owners have returned. Context
 // fencing additionally checks no RunnerContext survives before publishing seal.
 let stats=session::complete_stage(ticket)?;
 Ok(json!({"ok":true,"schemaVersion":1,"epoch":ticket.epoch,"generation":ticket.generation,"sequence":ticket.sequence,"token":token,"operation":operation,"stageSealed":true,"summary":summary,"session":stats}))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuSessionBridge_nativeOpenGpuSession(mut env:JNIEnv<'_>,_:JObject<'_>,config:JString<'_>)->jstring{
 let r=catch_unwind(AssertUnwindSafe(||{let c:Config=serde_json::from_str(&text(&mut env,&config)?).map_err(|_|"INVALID_SESSION_CONFIG")?;if c.schema_version!=1||c.epoch>i64::MAX as u64{return Err("INVALID_SESSION_CONFIG".into())}session::activate(c.epoch,c.prepaid_bytes,c.fixed_bytes,c.pipeline_limit)?;Ok(json!({"ok":true,"schemaVersion":1,"epoch":c.epoch,"state":"ready"}))})).unwrap_or_else(|_|Err("GPU_SESSION_PANIC".into()));if r.is_err(){session::retire_current();}reply(&mut env,r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuSessionBridge_nativeBeginGpuSessionFrame(mut env:JNIEnv<'_>,_:JObject<'_>,epoch:jlong,generation:jlong)->jstring{
 let r=catch_unwind(AssertUnwindSafe(||{let e=positive(epoch)?;let g=positive(generation)?;session::begin_frame(e,g)?;Ok(json!({"ok":true,"schemaVersion":1,"epoch":e,"generation":g,"state":"frame"}))})).unwrap_or_else(|_|Err("GPU_SESSION_PANIC".into()));if r.is_err()&&epoch>0{let _=session::request_stop(epoch as u64);}reply(&mut env,r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuSessionBridge_nativeRunGpuSessionStage(mut env:JNIEnv<'_>,_:JObject<'_>,epoch:jlong,generation:jlong,sequence:jlong,operation:JString<'_>,dir:JString<'_>,budget:jlong,token:jlong,sha:JString<'_>)->jstring{
 let r=catch_unwind(AssertUnwindSafe(||{let ticket=StageTicket{epoch:positive(epoch)?,generation:positive(generation)?,sequence:positive(sequence)?};let op=text(&mut env,&operation)?;let path=text(&mut env,&dir)?;let digest=text(&mut env,&sha)?;let code=match op.as_str(){"fullCameraDiffusion"=>1,"fullAutoExposure"=>2,"fullHighlightBoost"=>3,"fullFilmExposure"=>4,"fullFilmOptics"=>5,"fullFilmDirDensity"=>6,"fullExposureLog"=>7,"fullFilmGrain"=>8,"fullPrintExposure"=>9,"fullPrintDiffusion"=>10,"fullPrintScanLogXyz"=>11,"fullScanTail"=>12,"fullFinishTail"=>13,"fullExposureLogDir"=>14,_=>crate::research_streaming_route::stage_code(&op)};let mut telemetry=crate::gpu_phase_diagnostics::TaskScope::jni(&mut env,token,code);let r=stage(ticket,&op,Path::new(&path),positive(budget)?,positive(token)?,&digest);telemetry.finish(&r);r})).unwrap_or_else(|_|Err("GPU_SESSION_PANIC".into()));
 if r.is_err()&&epoch>0{let _=session::request_stop(epoch as u64);}reply(&mut env,r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuSessionBridge_nativeSealGpuSessionFrame(mut env:JNIEnv<'_>,_:JObject<'_>,epoch:jlong,generation:jlong)->jstring{
 let r=catch_unwind(AssertUnwindSafe(||{let e=positive(epoch)?;let g=positive(generation)?;let stats=session::finish_frame(e,g)?;Ok(json!({"ok":true,"schemaVersion":1,"epoch":e,"generation":g,"frameSealed":true,"session":stats}))})).unwrap_or_else(|_|Err("GPU_SESSION_PANIC".into()));if r.is_err()&&epoch>0{let _=session::request_stop(epoch as u64);}reply(&mut env,r)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_filmroom_engine_NativeGpuSessionBridge_nativeRetireGpuSession(_:JNIEnv<'_>,_:JObject<'_>,epoch:jlong){let _=catch_unwind(AssertUnwindSafe(||{if epoch>0{let _=session::request_stop(epoch as u64);}}));}
#[cfg(test)] mod tests{
 use super::*;
 #[test] fn config_rejects_unknown_fields_and_missing_accounting(){assert!(serde_json::from_str::<Config>(r#"{"schemaVersion":1,"epoch":1,"prepaidBytes":123,"fixedBytes":12,"pipelineLimit":4,"arenaPlanDigest":"fake"}"#).is_err());assert!(serde_json::from_str::<Config>(r#"{"schemaVersion":1,"epoch":1,"prepaidBytes":123,"pipelineLimit":4}"#).is_err());}
 #[test] fn signed_identifiers_reject_zero_and_negative(){assert!(positive(0).is_err());assert!(positive(-1).is_err());assert_eq!(positive(i64::MAX).unwrap(),i64::MAX as u64);}
 #[test] fn unknown_operation_never_touches_packet(){assert_eq!(plan("unknown",Path::new("/does/not/exist"),1).unwrap_err(),"INVALID_SCIENCE_OPERATION");}
}

#[cfg(test)]
pub(crate) fn run_stage_for_test(ticket:StageTicket,operation:&str,dir:&Path,budget:u64,token:u64,sha:&str)->R<Value>{
 let _probe=crate::gpu_session::software_probe_scope();
 let result=stage(ticket,operation,dir,budget,token,sha);
 if result.is_err(){let _=session::request_stop(ticket.epoch);}
 result
}
