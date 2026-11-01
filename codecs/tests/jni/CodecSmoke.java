import dev.filmroom.codec.NativeCodecBridge;
import java.nio.*;
import java.nio.file.*;
import java.io.RandomAccessFile;
import java.util.concurrent.*;
public class CodecSmoke {
 static final String B="{\"memoryBudgetMiB\":128,\"diskBudgetBytes\":1073741824}";
 static void require(boolean b){if(!b)throw new AssertionError("CODEC_JNI_ASSERT");}
 static void safe(String s){require(!s.contains("/workspace/")&&!s.contains("SYNTHETIC_PRIVATE")&&!s.contains("GPS")&&!s.contains(".dng")&&!s.contains("file://"));}
 static String params(String format){return "{\"width\":374,\"height\":56,\"format\":\""+format+"\",\"quality\":95,\"memoryBudgetMiB\":128,\"diskBudgetBytes\":1073741824}";}
 public static void main(String[]args)throws Exception{
  System.load(args[0]);Path root=Path.of(args[1]).toRealPath();long h=NativeCodecBridge.nativeCreate(root.toString());require(h!=0);
  String in=root.resolve("tests/fixtures/crop.dng").toString();String p=NativeCodecBridge.nativeProbe(h,in,B);safe(p);Files.writeString(root.resolve("results/jni-probe-success.json"),p);require(p.contains("\"ok\":true")&&p.contains("\"outputOrientation\":1")&&p.contains("\"width\":374"));
  Path out=root.resolve("results/jni-output.f32le");Files.deleteIfExists(out);String d=NativeCodecBridge.nativeDecode(h,in,out.toString(),B);safe(d);Files.writeString(root.resolve("results/jni-decode-success.json"),d);require(d.contains("\"ok\":true"));require(Files.size(out)==56L*374*12);
  for(String format:new String[]{"JPEG","TIFF16"}){Path encoded=root.resolve("results/jni-encoded-"+format);Files.deleteIfExists(encoded);String s=NativeCodecBridge.nativeEncode(h,out.toString(),encoded.toString(),params(format));safe(s);Files.writeString(root.resolve("results/jni-encode-"+format+"-success.json"),s);require(s.contains("\"ok\":true")&&Files.size(encoded)>100);Files.delete(encoded);}
  Files.delete(out);
  String bad=NativeCodecBridge.nativeProbe(h,"/etc/passwd",B);safe(bad);Files.writeString(root.resolve("results/jni-error.json"),bad);require(bad.contains("INVALID_PATH"));
  String mem=NativeCodecBridge.nativeProbe(h,in,"{\"memoryBudgetMiB\":1,\"diskBudgetBytes\":1073741824}");safe(mem);require(mem.contains("MEMORY_BUDGET"));
  String disk=NativeCodecBridge.nativeDecode(h,in,out.toString(),"{\"memoryBudgetMiB\":128,\"diskBudgetBytes\":1}");safe(disk);require(disk.contains("DISK_BUDGET")&&!Files.exists(out));
  Files.writeString(out,"KEEP");String existing=NativeCodecBridge.nativeDecode(h,in,out.toString(),B);safe(existing);require(existing.contains("OUTPUT_EXISTS")&&Files.readString(out).equals("KEEP"));Files.delete(out);
  String metadata="{\"memoryBudgetMiB\":1,\"diskBudgetBytes\":1,\"metadataOnly\":true}";
  String mp=NativeCodecBridge.nativeProbe(h,in,metadata);safe(mp);require(mp.contains("\"ok\":true")&&mp.contains("\"admission\":null"));
  require(NativeCodecBridge.nativeDecode(h,in,out.toString(),metadata).contains("INVALID_INPUT")&&!Files.exists(out));
  require(NativeCodecBridge.nativeProbe(h,in,"{\"memoryBudgetMiB\":1,\"diskBudgetBytes\":1,\"metadataOnly\":\"true\"}").contains("INVALID_INPUT"));
  NativeCodecBridge.nativeCancel(h);
  require(NativeCodecBridge.nativeProbe(h,in,B).contains("CANCELLED"));
  require(NativeCodecBridge.nativeDecode(h,in,out.toString(),B).contains("CANCELLED")&&!Files.exists(out));
  require(NativeCodecBridge.nativeEncode(h,in,out.toString(),params("JPEG")).contains("CANCELLED")&&!Files.exists(out));
  NativeCodecBridge.nativeDestroy(h);NativeCodecBridge.nativeDestroy(h);
  String gone=NativeCodecBridge.nativeProbe(h,in,B);safe(gone);require(gone.contains("INVALID_HANDLE"));
  // Sparse synthetic sensor plane: cancellation sees 50MP dimensions but does not
  // allocate a full sensor plane or require a giant committed test fixture.
  byte[] seed=Files.readAllBytes(root.resolve("tests/fixtures/RGGB.dng"));ByteBuffer buf=ByteBuffer.wrap(seed).order(ByteOrder.LITTLE_ENDIAN);int count=Short.toUnsignedInt(buf.getShort(8));int dataOffset=0;
  for(int i=0;i<count;i++){int pos=10+i*12;int tag=Short.toUnsignedInt(buf.getShort(pos));if(tag==256)buf.putInt(pos+8,8192);if(tag==257||tag==278)buf.putInt(pos+8,6144);if(tag==279)buf.putInt(pos+8,8192*6144*2);if(tag==273)dataOffset=buf.getInt(pos+8);}
  Path big=root.resolve("results/jni-cancel-source.dng"),partial=root.resolve("results/jni-cancel-partial.f32le");Files.deleteIfExists(big);Files.deleteIfExists(partial);Files.write(big,seed);try(RandomAccessFile f=new RandomAccessFile(big.toFile(),"rw")){f.setLength((long)dataOffset+8192L*6144*2);}
  long ch=NativeCodecBridge.nativeCreate(root.toString());ExecutorService ex=Executors.newSingleThreadExecutor();Future<String> future=ex.submit(()->NativeCodecBridge.nativeDecode(ch,big.toString(),partial.toString(),B));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!Files.exists(partial)&&!future.isDone()&&System.nanoTime()<deadline)Thread.sleep(1);require(Files.exists(partial));require(NativeCodecBridge.nativeProbe(ch,in,B).contains("BUSY"));NativeCodecBridge.nativeCancel(ch);String cancelled=future.get(20,TimeUnit.SECONDS);safe(cancelled);require(cancelled.contains("CANCELLED")&&!Files.exists(partial));NativeCodecBridge.nativeDestroy(ch);ex.shutdown();Files.delete(big);
  System.out.println("{\"ok\":true,\"suite\":\"host-jni\",\"checks\":19,\"stickyCancel\":true,\"busyAndInflightCleanup\":true,\"androidDeviceRuntimeTest\":false}");
 }
}
