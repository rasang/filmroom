import dev.filmroom.codec.NativeCodecBridge;
import java.nio.file.*;
import java.util.concurrent.*;
/** Actual host JNI camera lifecycle; caller supplies a public fixture and isolated root. */
public class CameraRawSmoke {
 static final String B="{\"memoryBudgetMiB\":128,\"diskBudgetBytes\":1073741824}";
 static void require(boolean ok){if(!ok)throw new AssertionError("CAMERA_JNI_ASSERT");}
 public static void main(String[]args)throws Exception{
  System.load(args[0]);Path root=Path.of(args[1]).toRealPath();Path input=root.resolve("camera-input.raw");
  Files.copy(Path.of(args[2]),input,StandardCopyOption.REPLACE_EXISTING);
  Path output=root.resolve("camera-output.linear");Files.deleteIfExists(output);
  long h=NativeCodecBridge.nativeCreate(root.toString());require(h!=0);
  String probe=NativeCodecBridge.nativeProbe(h,input.toString(),B);require(probe.contains("\"ok\":true")&&probe.contains("\"format\":\"CR2\""));
  Files.writeString(output,"KEEP");String exists=NativeCodecBridge.nativeDecode(h,input.toString(),output.toString(),B);
  require(exists.contains("OUTPUT_EXISTS")&&Files.readString(output).equals("KEEP"));Files.delete(output);
  String disk=NativeCodecBridge.nativeDecode(h,input.toString(),output.toString(),"{\"memoryBudgetMiB\":128,\"diskBudgetBytes\":1}");
  require(disk.contains("DISK_BUDGET")&&!Files.exists(output));NativeCodecBridge.nativeDestroy(h);
  long workHandle=NativeCodecBridge.nativeCreate(root.toString());ExecutorService executor=Executors.newSingleThreadExecutor();
  Future<String> work=executor.submit(()->NativeCodecBridge.nativeDecode(workHandle,input.toString(),output.toString(),B));
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
  while(!Files.exists(output)&&!work.isDone()&&System.nanoTime()<deadline)Thread.sleep(1);
  require(Files.exists(output));require(NativeCodecBridge.nativeProbe(workHandle,input.toString(),B).contains("BUSY"));
  NativeCodecBridge.nativeCancel(workHandle);String cancelled=work.get(30,TimeUnit.SECONDS);
  require(cancelled.contains("CANCELLED")&&!Files.exists(output));
  require(NativeCodecBridge.nativeProbe(workHandle,input.toString(),B).contains("CANCELLED"));
  NativeCodecBridge.nativeDestroy(workHandle);executor.shutdown();Files.delete(input);
  Files.writeString(root.resolve("camera-jni-result.json"),"{\"ok\":true,\"cameraProbe\":true,\"preservesExisting\":true,\"diskAdmission\":true,\"busy\":true,\"stickyCancelAndCleanup\":true,\"androidRuntime\":false}");
  System.out.println("CAMERA_HOST_JNI_PASS");
 }
}
