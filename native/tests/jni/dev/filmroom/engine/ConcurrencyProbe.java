package dev.filmroom.engine;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ConcurrencyProbe {
  public static void main(String[] args) throws Exception {
    System.load(args[0]);NativeFilmBridge b=new NativeFilmBridge();long handle=b.nativeCreate(args[1]);
    NativeFilmBridge.require(handle!=0,"create");
    int n=128*128*12;ByteBuffer input=NativeFilmBridge.direct(n),output=NativeFilmBridge.direct(n);
    for(int i=0;i<n;i+=4){input.putFloat(i,.18f);output.putFloat(i,-999f);}
    AtomicReference<String> result=new AtomicReference<>();CountDownLatch started=new CountDownLatch(1);
    Thread thread=new Thread(()->{started.countDown();result.set(b.nativeRender(handle,input,output,128,128,"{\"grainAmount\":0,\"halationAmount\":0}"));});
    thread.start();started.await();Thread.sleep(100);
    String busy=b.nativeRender(handle,NativeFilmBridge.direct(12),NativeFilmBridge.direct(12),1,1,"{}");
    NativeFilmBridge.require(busy.contains("ENGINE_BUSY"),"concurrent same-handle render must reject: "+busy);
    b.nativeCancel(handle);thread.join(30000);NativeFilmBridge.require(!thread.isAlive(),"cancel must finish by safe boundary");
    NativeFilmBridge.require(result.get().contains("CANCELLED"),"active cancellation: "+result.get());
    for(int i=0;i<n;i+=4)NativeFilmBridge.require(output.getFloat(i)==-999f,"cancelled output must not be committed");
    ByteBuffer small=NativeFilmBridge.direct(12),smallOut=NativeFilmBridge.direct(12);for(int i=0;i<12;i+=4)small.putFloat(i,.18f);
    String next=b.nativeRender(handle,small,smallOut,1,1,"{\"grainAmount\":0,\"halationAmount\":0}");
    NativeFilmBridge.require(next.contains("\"ok\":true"),"new generation after cancel: "+next);
    CountDownLatch secondStarted=new CountDownLatch(1);AtomicReference<String> second=new AtomicReference<>();
    Thread secondThread=new Thread(()->{secondStarted.countDown();second.set(b.nativeRender(handle,input,output,128,128,"{}"));});
    secondThread.start();secondStarted.await();Thread.sleep(100);b.nativeDestroy(handle);secondThread.join(30000);
    NativeFilmBridge.require(!secondThread.isAlive(),"destroyed render must finish safely");
    NativeFilmBridge.require(second.get().contains("CANCELLED"),"concurrent destroy retained Arc: "+second.get());
    NativeFilmBridge.require(b.nativeRender(handle,small,smallOut,1,1,"{}").contains("INVALID_HANDLE"),"destroyed handle absent");
    System.out.println("{\"status\":\"passed\",\"checks\":[\"concurrent_render_rejected\",\"active_cancel_observed\",\"cancelled_output_unchanged\",\"new_generation_recovers\",\"destroy_race_no_use_after_free\",\"destroyed_handle_rejected\"],\"cancelResult\":"+result.get()+",\"destroyResult\":"+second.get()+"}");
  }
}
