package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Production JNI queue paths, including Android-style aliases of the private app root. */
public class QueueCheckpointProbe {
  static void require(boolean b,String message){NativeFilmBridge.require(b,message);}
  static String params(Path checkpoint){return "{\"mode\":\"export\",\"useGpu\":false,\"paperStock\":\"kodak_2383\",\"brightnessEv\":0.8599998950958252,\"autoExposure\":true,\"metering\":\"partial\",\"diffusionFilter\":\"black_pro_mist\",\"printDiffusionFilter\":\"black_pro_mist\",\"memoryBudgetMiB\":512,\"checkpointDirectory\":\""+checkpoint+"\"}";}
  public static void main(String[] args)throws Exception {
    System.load(args[0]);var bridge=new NativeFilmBridge();Path root=Path.of(args[1]).toRealPath();
    Path alias=root.resolveSibling("queue-alias-"+System.nanoTime());Files.createSymbolicLink(alias,root);
    Path job=root.resolve("files/export-jobs/"+UUID.randomUUID());Files.createDirectories(job);
    Path aliasJob=alias.resolve("files/export-jobs/"+job.getFileName());
    int width=args.length>2?Integer.parseInt(args[3]):32,height=args.length>2?Integer.parseInt(args[4]):24;
    Path input=job.resolve("input.rgb");
    if(args.length>2)Files.copy(Path.of(args[2]),input);else{var data=ByteBuffer.allocate(width*height*12).order(ByteOrder.LITTLE_ENDIAN);while(data.hasRemaining())data.putFloat(.18f);Files.write(input,data.array());}
    long handle=bridge.nativeCreate(alias.resolve("files/engine-data").toString());require(handle!=0,"private data alias accepted");
    List<String> checks=new ArrayList<>();Path out=aliasJob.resolve("result.rgb");
    String result=bridge.nativeExportFile(handle,aliasJob.resolve("input.rgb").toString(),out.toString(),width,height,params(aliasJob.resolve("checkpoint")));
    require(result.contains("\"ok\":true"),"alias checkpoint export: "+result);checks.add("private_root_alias_export");
    require(Files.size(out)==(long)width*height*12,"full output shape");checks.add("unmodified_output_dimensions");
    try(var channel=java.nio.channels.FileChannel.open(out,StandardOpenOption.READ)) {
      ByteBuffer chunk=ByteBuffer.allocate(64*1024).order(ByteOrder.LITTLE_ENDIAN);
      while(channel.read(chunk)!=-1){chunk.flip();while(chunk.remaining()>=4)require(Float.isFinite(chunk.getFloat()),"finite output");chunk.compact();}
      require(chunk.position()==0,"whole float samples");
    }
    checks.add("all_output_samples_finite");
    // Resume through a new engine handle and canonical root, using only persisted state.
    bridge.nativeDestroy(handle);handle=bridge.nativeCreate(root.resolve("files/engine-data").toString());require(handle!=0,"fresh resume handle");
    Path resumed=job.resolve("resumed.rgb");String again=bridge.nativeExportFile(handle,input.toString(),resumed.toString(),width,height,params(job.resolve("checkpoint")));
    require(again.contains("\"ok\":true"),"canonical resume: "+again);
    require(Files.mismatch(out,resumed)==-1,"resume bit parity");checks.add("alias_to_canonical_checkpoint_resume_bit_exact");
    Path other=root.resolve("files/export-jobs/"+UUID.randomUUID());Files.createDirectories(other);
    Path outside=Files.createTempDirectory(root,"checkpoint-outside-");
    Path checkpoint=job.resolve("checkpoint"),saved=job.resolve("saved-checkpoint");Files.move(checkpoint,saved);
    for(Path target:List.of(outside,other,saved,outside.resolve("missing"))) {
      Files.createSymbolicLink(checkpoint,target);
      String bad=bridge.nativeExportFile(handle,input.toString(),job.resolve("bad.rgb").toString(),width,height,params(aliasJob.resolve("checkpoint")));
      require(bad.contains("INVALID_PATH")&&!Files.exists(job.resolve("bad.rgb")),"checkpoint symlink rejected: "+bad);Files.delete(checkpoint);
    }
    checks.add("checkpoint_symlinks_outside_sibling_inside_and_dangling_rejected");Files.move(saved,checkpoint);
    String[][] cases={
      {"sibling_checkpoint",input.toString(),job.resolve("bad.rgb").toString(),other.resolve("checkpoint").toString()},
      {"sibling_output",input.toString(),other.resolve("bad.rgb").toString(),checkpoint.toString()},
      {"relative_checkpoint",input.toString(),job.resolve("bad.rgb").toString(),"checkpoint"},
      {"wrong_checkpoint_name",input.toString(),job.resolve("bad.rgb").toString(),job.resolve("not-checkpoint").toString()}
    };
    for(String[] c:cases){String bad=bridge.nativeExportFile(handle,c[1],c[2],width,height,params(Path.of(c[3])));require(bad.contains("INVALID_PATH"),c[0]+": "+bad);checks.add(c[0]);}
    Path renamed=job.resolve("source.rgb");Files.createLink(renamed,input);
    String wrongInput=bridge.nativeExportFile(handle,renamed.toString(),job.resolve("bad.rgb").toString(),width,height,params(checkpoint));
    require(wrongInput.contains("INVALID_PATH"),"wrong input basename rejected");Files.delete(renamed);checks.add("wrong_input_basename");
    for(Path badJob:List.of(root.resolve("files/export-jobs/123456781234-1234-1234-1234-56789abc"),root.resolve("files/"+UUID.randomUUID()))) {
      Files.createDirectories(badJob);Path badInput=badJob.resolve("input.rgb");Files.createLink(badInput,input);
      String bad=bridge.nativeExportFile(handle,badInput.toString(),badJob.resolve("bad.rgb").toString(),width,height,params(badJob.resolve("checkpoint")));
      require(bad.contains("INVALID_PATH")&&!Files.exists(badJob.resolve("bad.rgb")),"invalid job identity/root rejected: "+bad);
      Files.delete(badInput);Files.delete(badJob);
    }
    checks.add("invalid_uuid_and_nonqueue_root_rejected");
    require(Files.mismatch(out,resumed)==-1,"invalid paths preserve completed output");checks.add("invalid_requests_preserve_outputs");
    bridge.nativeDestroy(handle);Files.delete(alias);Files.delete(other);Files.delete(outside);
    System.out.println("{\"ok\":true,\"androidRuntimeMeasured\":false,\"width\":"+width+",\"height\":"+height+",\"checks\":[\""+String.join("\",\"",checks)+"\"]}");
  }
}
