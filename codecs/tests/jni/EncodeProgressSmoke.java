import dev.filmroom.codec.NativeCodecBridge;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public class EncodeProgressSmoke {
 static void require(boolean b){if(!b)throw new AssertionError("ENCODE_PROGRESS_ASSERT");}
 public static void main(String[] args)throws Exception {
  System.load(args[0]);Path root=Path.of(args[1]).toRealPath();
  Path input=root.resolve("progress-input.rgb");
  ByteBuffer bytes=ByteBuffer.allocate(33*35*12).order(ByteOrder.LITTLE_ENDIAN);
  for(int i=0;i<33*35*3;i++)bytes.putFloat(0.4f);Files.write(input,bytes.array());
  for(String format:new String[]{"JPEG","TIFF16"}){
   Path output=root.resolve("progress-output-"+format);Files.deleteIfExists(output);
   String params="{\"width\":33,\"height\":35,\"format\":\""+format+"\",\"quality\":95,\"memoryBudgetMiB\":128,\"diskBudgetBytes\":1073741824}";
   long h=NativeCodecBridge.nativeCreate(root.toString());
   List<int[]> events=new ArrayList<>();
   String result=NativeCodecBridge.nativeEncodeWithProgress(h,input.toString(),output.toString(),params,(d,t,f)->events.add(new int[]{d,t,f?1:0}));
   require(result.contains("\"ok\":true"));require(events.size()>2);
   int prev=-1,finishes=0; for(int[] e:events){require(e[0]>=prev&&e[0]<=35&&e[1]==35);prev=e[0];finishes+=e[2];}
   require(finishes==1&&events.get(events.size()-1)[2]==1);Files.delete(output);NativeCodecBridge.nativeDestroy(h);
   h=NativeCodecBridge.nativeCreate(root.toString()); final long ch=h;
   String cancelled=NativeCodecBridge.nativeEncodeWithProgress(h,input.toString(),output.toString(),params,(d,t,f)->{require(!f);if(d>0)NativeCodecBridge.nativeCancel(ch);});
   require(cancelled.contains("CANCELLED")&&!Files.exists(output));NativeCodecBridge.nativeDestroy(h);
   h=NativeCodecBridge.nativeCreate(root.toString());
   boolean thrown=false;
   try { NativeCodecBridge.nativeEncodeWithProgress(h,input.toString(),output.toString(),params,(d,t,f)->{if(d>0)throw new IllegalStateException("progress-stop");}); }
   catch(IllegalStateException e){thrown=e.getMessage().equals("progress-stop");}
   require(thrown&&!Files.exists(output));NativeCodecBridge.nativeDestroy(h);
  }
  Files.delete(input);System.out.println("JPEG/TIFF JNI progress and cancellation passed");
 }
}
