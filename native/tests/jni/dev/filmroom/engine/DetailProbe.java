package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
public final class DetailProbe {
 static NativeDetailBridge b=new NativeDetailBridge();static List<String> checks=new ArrayList<>();
 static void require(boolean v,String m){if(!v)throw new AssertionError(m);}
 static void ok(String s){require(s!=null&&s.contains("\"ok\":true"),s);}
 static void code(String s,String c){require(s!=null&&s.contains("\"code\":\""+c+"\""),"expected "+c+": "+s);require(!s.contains("/workspace/")&&!s.contains("/tmp/")&&!s.contains("Exception"),"unsanitized status "+s);}
 static long id(String s,String key){ok(s);Matcher m=Pattern.compile("\\\""+key+"\\\":\\\"([0-9]+)\\\"").matcher(s);require(m.find(),"missing id "+s);return Long.parseLong(m.group(1));}
 static long create(Path root){return id(b.nativeCreate(root.toString()),"sessionId");}static long op(long h){return id(b.nativeBeginOperation(h),"operationId");}
 static ByteBuffer direct(int n){return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());}
 static String request(int w,int h,String params){return "{\"regionOutput\":true,\"schemaVersion\":1,\"width\":"+w+",\"height\":"+h+",\"parameters\":"+params+",\"sourceRevision\":\"fixture1\",\"mappingRevision\":\"oriented1\",\"dataRevision\":\"bundled1\",\"memoryBudgetBytes\":536870912,\"diskBudgetBytes\":1073741824,\"stageCacheBytes\":16777216,\"spectrumCacheBytes\":67108864}";}
 static byte[] source(int w,int h){ByteBuffer x=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<w*h*3;i++)x.putFloat(i*4,i<12?10f:.01f+(i*37%251)/250f);return x.array();}
 static String badTile(long h,ByteBuffer out){return b.nativeTile(h,op(h),0,0,out);}
 public static void main(String[] args)throws Exception{
  System.load(args[0]);Path root=Path.of(args[1]).toRealPath();Path preview=root.resolve("cache/detail-preview"),sources=preview.resolve("sources");Files.createDirectories(sources);
  String cap=b.nativeCapabilities();ok(cap);require(cap.contains("residentDetailOrExplicitCpuFallback")&&cap.contains("\"coreEdge\":256")&&cap.contains("\"gutter\":1"),cap);checks.add("cheap_capabilities");
  String req=request(32,24,"{\"paperStock\":\"kodak_2383\",\"useGpu\":true,\"autoExposure\":true}");ok(b.nativePlan(req));code(b.nativePlan(null),"INVALID_REQUEST");code(b.nativePlan("{}"),"INVALID_REQUEST");code(b.nativePlan(request(0,24,"{}")),"INVALID_REQUEST");code(b.nativePlan(request(32,24,"{\"scanBlur\":1}")),"UNSUPPORTED");code(b.nativePlan(request(32,24,"{\"filmStock\":\"fujifilm_provia_100f\"}")),"UNSUPPORTED");code(b.nativePlan(req.replace("\"diskBudgetBytes\":1073741824","\"diskBudgetBytes\":1")),"STORAGE_BUDGET");checks.add("plan_sanitized_and_unsupported");
  code(b.nativeCreate(root+"/../"+root.getFileName()),"INVALID_SCOPE");code(b.nativeCreate(null),"INVALID_REQUEST");checks.add("strict_private_root");
  byte[] original=source(32,24);Files.write(sources.resolve("input.f32le"),original);
  long h=create(root);for(int i=0;i<80;i++){long abandoned=op(h);ok(b.nativeCancel(h,abandoned));ok(b.nativeReleaseOperation(h,abandoned));}checks.add("abandoned_operations_released");long cancelled=op(h);ok(b.nativeCancel(h,cancelled));code(b.nativeBuild(h,cancelled,"sources/input.f32le",req),"CANCELLED");code(b.nativeBuild(h,cancelled,"sources/input.f32le",req),"INVALID_OPERATION");checks.add("precancel_build_not_lost_single_use");
  code(b.nativeBuild(h,op(h),"../source.f32le",req),"INVALID_SCOPE");code(b.nativeBuild(h,op(h),sources.resolve("input.f32le").toString(),req),"INVALID_SCOPE");
  Path outside=root.resolve("cache/outside.f32le");Files.write(outside,original);Files.createSymbolicLink(sources.resolve("linked.f32le"),outside);code(b.nativeBuild(h,op(h),"sources/linked.f32le",req),"INVALID_SCOPE");checks.add("source_traversal_absolute_symlink_rejected");
  String built=b.nativeBuild(h,op(h),"sources/input.f32le",req);ok(built);require(built.contains("\"actualBackend\":\"cpu\"")&&built.contains("\"cpuFallback\":true")&&built.contains("\"requestedGpu\":true"),built);checks.add("real_cpu_fallback_build");
  int bytes=32*24*12;code(badTile(h,null),"INVALID_BUFFER");code(badTile(h,ByteBuffer.allocate(bytes)),"INVALID_BUFFER");code(badTile(h,direct(bytes-4)),"INVALID_BUFFER");code(badTile(h,direct(bytes).asReadOnlyBuffer().order(ByteOrder.nativeOrder())),"INVALID_BUFFER");
  ByteBuffer position=direct(bytes);position.position(4);code(badTile(h,position),"INVALID_BUFFER");ByteBuffer limited=direct(bytes);limited.limit(bytes-4);code(badTile(h,limited),"INVALID_BUFFER");
  ByteBuffer endian=direct(bytes).order(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN);code(badTile(h,endian),"INVALID_BUFFER");ByteBuffer unaligned=direct(bytes+1);unaligned.position(1);code(badTile(h,unaligned.slice().order(ByteOrder.nativeOrder())),"INVALID_BUFFER");
  code(b.nativeTile(h,op(h),-1,0,direct(bytes)),"INVALID_REQUEST");code(b.nativeTile(h,op(h),Integer.MAX_VALUE,0,direct(bytes)),"INVALID_REQUEST");checks.add("malformed_direct_buffers_and_geometry");
  ByteBuffer out=direct(bytes);for(int i=0;i<bytes;i++)out.put(i,(byte)0x55);long pc=op(h);ok(b.nativeCancel(h,pc));code(b.nativeTile(h,pc,0,0,out),"CANCELLED");for(int i=0;i<bytes;i++)require(out.get(i)==(byte)0x55,"precancel modified output");checks.add("precancel_tile_does_not_write");
  long tileOp=op(h);String tile=b.nativeTile(h,tileOp,0,0,out);ok(tile);require(tile.contains("ExactSameWorkingGrid")&&tile.contains("\"outputValid\":true"),tile);for(int i=0;i<bytes;i+=4)require(Float.isFinite(out.getFloat(i)),"finite tile");code(b.nativeTile(h,tileOp,0,0,out),"INVALID_OPERATION");require(Arrays.equals(original,Files.readAllBytes(sources.resolve("input.f32le"))),"source mutated");checks.add("real_tile_owned_output_and_source_immutable");
  require(cap.contains("\"visibleRegion\":true"),cap);
  ByteBuffer region=direct(12*10*12);
  String regionReply=b.nativeRegion(h,op(h),5,6,10,8,region);ok(regionReply);
  for(int y=0;y<10;y++)for(int x=0;x<12;x++)for(int ch=0;ch<3;ch++)require(Math.abs(region.getFloat(((y*12+x)*3+ch)*4)-out.getFloat((((y+5)*32+x+4)*3+ch)*4))<1e-5,"region differs from full tile");
  code(b.nativeRegion(h,op(h),31,0,2,1,region),"INVALID_REQUEST");
  code(b.nativeRegion(h,op(h),0,0,0,1,region),"INVALID_REQUEST");
  code(b.nativeRegion(h,op(h),5,6,10,8,direct(4)),"INVALID_BUFFER");
  long cancelledRegion=op(h);ok(b.nativeCancel(h,cancelledRegion));
  for(int i=0;i<region.capacity();i++)region.put(i,(byte)0x66);
  code(b.nativeRegion(h,cancelledRegion,5,6,10,8,region),"CANCELLED");
  for(int i=0;i<region.capacity();i++)require(region.get(i)==(byte)0x66,"cancelled region wrote bytes");
  checks.add("visible_region_exact_shape_reference_invalid_and_cancel");
  // Reopening must never reap another live session's scientific context.
  long another=create(root);ok(b.nativeTile(h,op(h),0,0,out));ok(b.nativeDestroy(another));checks.add("active_session_not_orphan_cleaned");
  // Existing engine/export JNI remains callable while a detail context exists.
  NativeFilmBridge legacy=new NativeFilmBridge();long old=legacy.nativeCreate(root.resolve("files/engine-data").toString());require(old!=0,"legacy create");legacy.nativeCancel(h);legacy.nativeDestroy(h);code(b.nativeDestroy(old),"INVALID_HANDLE");ok(b.nativeTile(h,op(h),0,0,out));checks.add("cross_registry_ids_do_not_alias");Path oldIn=root.resolve("cache/legacy-detail-coexist.f32le"),oldOut=root.resolve("files/legacy-detail-coexist.f32le");Files.write(oldIn,source(8,8));String oldResult=legacy.nativeExportFile(old,oldIn.toString(),oldOut.toString(),8,8,"{\"useGpu\":false,\"grainAmount\":0}");ok(oldResult);require(Files.size(oldOut)==8*8*12,"legacy export size");legacy.nativeDestroy(old);checks.add("old_export_jni_coexistence");
  ok(b.nativeDestroy(h));code(b.nativeBeginOperation(h),"INVALID_HANDLE");code(b.nativeDestroy(h),"INVALID_HANDLE");checks.add("destroyed_session_rejected");
  long slide=create(root);String slideRequest=request(32,24,"{\"filmStock\":\"fujifilm_provia_100f\",\"outputRoute\":\"direct\",\"useGpu\":true}");ok(b.nativePlan(slideRequest));ok(b.nativeBuild(slide,op(slide),"sources/input.f32le",slideRequest));ok(b.nativeTile(slide,op(slide),0,0,out));ok(b.nativeRegion(slide,op(slide),5,6,10,8,direct(12*10*12)));ok(b.nativeDestroy(slide));checks.add("real_reversal_direct_route_not_forced_print");
  long legacyCells=create(root);String legacyReq=req.replace("\"regionOutput\":true,","");
  String legacyPlan=b.nativePlan(legacyReq);ok(legacyPlan);require(legacyPlan.contains("\"regionOutput\":false"),legacyPlan);
  ok(b.nativeBuild(legacyCells,op(legacyCells),"sources/input.f32le",legacyReq));ok(b.nativeTile(legacyCells,op(legacyCells),0,0,out));
  code(b.nativeRegion(legacyCells,op(legacyCells),5,6,10,8,direct(12*10*12)),"UNSUPPORTED");ok(b.nativeDestroy(legacyCells));checks.add("legacy_budget_context_remains_usable_without_region_upgrade");
  // Cold build cancellation: token exists before the worker enters JNI.
  Files.write(sources.resolve("big.f32le"),source(513,517));String bigReq=request(513,517,"{\"useGpu\":true,\"paperStock\":\"kodak_2383\",\"diffusionFilter\":\"black_pro_mist\",\"printDiffusionFilter\":\"black_pro_mist\"}");long cold=create(root),coldOp=op(cold);AtomicReference<String> result=new AtomicReference<>();CountDownLatch start=new CountDownLatch(1);Thread worker=new Thread(()->{start.countDown();result.set(b.nativeBuild(cold,coldOp,"sources/big.f32le",bigReq));});worker.start();start.await();Thread.sleep(25);ok(b.nativeDestroy(cold));worker.join(120000);require(!worker.isAlive(),"build worker stuck");require(result.get().contains("CANCELLED")||result.get().contains("INVALID_HANDLE"),result.get());checks.add("close_during_build_waits_for_worker_cleanup");
  long running=create(root);ok(b.nativeBuild(running,op(running),"sources/big.f32le",bigReq));long runOp=op(running);ByteBuffer large=direct(258*258*12);Thread tileWorker=new Thread(()->result.set(b.nativeTile(running,runOp,1,1,large)));tileWorker.start();Thread.sleep(20);ok(b.nativeDestroy(running));tileWorker.join(120000);require(!tileWorker.isAlive(),"tile worker stuck");require(result.get().contains("CANCELLED")||result.get().contains("INVALID_HANDLE"),result.get());checks.add("close_during_tile_arc_lifetime");
  Path sessions=preview.resolve("sessions");try(var entries=Files.list(sessions)){require(entries.findAny().isEmpty(),"live context files leaked");}
  Path orphan=sessions.resolve("owned-test-orphan"),unknown=sessions.resolve("owned-unmarked-sentinel");Files.createDirectory(orphan);Files.writeString(orphan.resolve("owner.marker"),"filmroom-owned-detail-session-v1\n");Files.write(orphan.resolve("owner.lock"),new byte[0]);Files.createDirectory(unknown);Files.writeString(unknown.resolve("keep"),"KEEP");long cleaned=create(root);require(!Files.exists(orphan)&&Files.readString(unknown.resolve("keep")).equals("KEEP"),"orphan cleanup scope");ok(b.nativeDestroy(cleaned));checks.add("marker_and_lease_orphan_cleanup");
  System.out.println("{\"status\":\"passed\",\"checks\":[\""+String.join("\",\"",checks)+"\"],\"build\":"+built+",\"tile\":"+tile+"}");
 }
}
