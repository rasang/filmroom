package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ExportProbe {
  public static void main(String[] args) throws Exception {
    System.load(args[0]);NativeFilmBridge b=new NativeFilmBridge();Path root=Path.of(args[1]);
    Path files=root.resolve("files"),cache=root.resolve("cache");Files.createDirectories(files);Files.createDirectories(cache);
    long handle=b.nativeCreate(files.resolve("engine-data").toString());NativeFilmBridge.require(handle!=0,"private bundled data");
    int width=1024,height=600,n=width*height*12;ByteBuffer input=ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    for(int i=0;i<n;i+=4)input.putFloat(i,.18f);
    Path in=cache.resolve("source.f32le"),out=files.resolve("rendered-"+System.nanoTime()+".f32le");Files.write(in,input.array());
    String params="{\"mode\":\"export\",\"grainAmount\":0,\"halationAmount\":0,\"saturation\":0,\"memoryBudgetMiB\":128}";
    List<String> checks=new ArrayList<>();
    Path existing=files.resolve("do-not-overwrite.bin");Files.writeString(existing,"ORIGINAL");String exists=b.nativeExportFile(handle,in.toString(),existing.toString(),width,height,params);NativeFilmBridge.require(exists.contains("INVALID_PATH")&&Files.readString(existing).equals("ORIGINAL"),"existing output preserved");checks.add("existing_output_preserved");
    NativeFilmBridge.require(b.nativeExportFile(handle,in.toString(),in.toString(),width,height,params).contains("INVALID_PATH"),"source overwrite rejected");checks.add("source_overwrite_rejected");
    NativeFilmBridge.require(b.nativeExportFile(handle,in.toString(),root.resolve("outside-files.f32le").toString(),width,height,params).contains("INVALID_PATH"),"foreign output parent rejected");checks.add("foreign_output_rejected");
    Path shortIn=cache.resolve("invalid-length.f32le");Files.write(shortIn,new byte[12]);NativeFilmBridge.require(b.nativeExportFile(handle,shortIn.toString(),out.toString(),width,height,params).contains("INVALID_INPUT_FILE")&&!Files.exists(out),"wrong input length creates no output");checks.add("wrong_length_rejected");
    String result=b.nativeExportFile(handle,in.toString(),out.toString(),width,height,params);NativeFilmBridge.require(result.contains("\"ok\":true"),"full-size export: "+result);NativeFilmBridge.require(Files.size(out)==n,"original dimensions byte length");
    ByteBuffer rendered=ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<n;i+=4)NativeFilmBridge.require(Float.isFinite(rendered.getFloat(i)),"finite original-size output");checks.add("above_preview_limit_exported_without_resizing");checks.add("all_output_finite");
    String completed=b.nativeGetProgress(handle);NativeFilmBridge.require(completed.contains("\"status\":\"completed\"")&&completed.contains("\"active\":false"),"completed progress snapshot");checks.add("completed_progress_snapshot");
    Path cancelled=files.resolve("cancelled-"+System.nanoTime()+".f32le");AtomicReference<String> cancelResult=new AtomicReference<>();CountDownLatch started=new CountDownLatch(1);
    Thread worker=new Thread(()->{started.countDown();cancelResult.set(b.nativeExportFile(handle,in.toString(),cancelled.toString(),width,height,params));});worker.start();started.await();Thread.sleep(100);String active=b.nativeGetProgress(handle);NativeFilmBridge.require(active.contains("\"status\":\"running\""),"concurrent progress polling");checks.add("concurrent_progress_polling");b.nativeCancel(handle);worker.join(120000);NativeFilmBridge.require(!worker.isAlive(),"cancel worker completed");NativeFilmBridge.require(cancelResult.get().contains("CANCELLED")&&!Files.exists(cancelled),"cancelled partial output removed: "+cancelResult.get());checks.add("cancelled_partial_removed");String terminal=b.nativeGetProgress(handle);NativeFilmBridge.require(terminal.contains("\"status\":\"cancelled\""),"cancelled progress snapshot");checks.add("cancelled_progress_snapshot");
    try(var entries=Files.list(cache)){NativeFilmBridge.require(entries.noneMatch(p->p.getFileName().toString().startsWith("film-native-v5-")),"native scratch cleanup");}checks.add("all_native_scratch_removed");
    b.nativeDestroy(handle);
    System.out.println("{\"status\":\"passed\",\"checks\":[\""+String.join("\",\"",checks)+"\"],\"width\":"+width+",\"height\":"+height+",\"bytes\":"+n+",\"result\":"+result+",\"cancelResult\":"+cancelResult.get()+"}");
  }
}
