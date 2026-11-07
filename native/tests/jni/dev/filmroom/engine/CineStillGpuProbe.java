package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
/** Host Vulkan fixture. Checks actual GPU use, deterministic repetition, finite full-size export.
 * Reports preview/export differences separately; these paths have different finish boundaries. */
public final class CineStillGpuProbe {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void gpu(String s){check(s.contains("\"ok\":true"),s);check(s.contains("\"backend\":\"wgpu\"")&&s.contains("\"active\":true"),"not GPU: "+s);}
 static byte[] bytes(ByteBuffer b){byte[] a=new byte[b.capacity()];b.duplicate().get(a);return a;}
 public static void main(String[] args)throws Exception {
  System.load(args[0]);Path root=Path.of(args[1]),logs=Path.of(args[2]);NativeFilmBridge bridge=new NativeFilmBridge();
  check(bridge.nativeEncodedReuseProbe("{\"useGpu\":false}").contains("cinestill4g-v1"),"old CPU reuse identity");
  long handle=bridge.nativeCreate(root.resolve("files/engine-data").toString());check(handle!=0,"create");
  int w=256,h=128,n=w*h*12;ByteBuffer input=ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN),output=ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int c=0;c<3;c++)input.putFloat(((y*w+x)*3+c)*4,x>=120&&x<136&&y>=32&&y<96?1f:.002f);
  Path in=root.resolve("cache/source.rgb");Files.write(in,bytes(input));
  try {for(String stock:new String[]{"cinestill_800t","kodak_vision3_500t"})for(String route:new String[]{"print","direct"})for(double boost:new double[]{0,.75}){
   String id=stock+"-"+route+"-boost"+boost;
   String base="{\"filmStock\":\""+stock+"\",\"paperStock\":\"kodak_2383\",\"outputRoute\":\""+route+"\",\"grainAmount\":0,\"scatterAmount\":0,\"dirAmount\":0,\"filmFormatMm\":35,\"useGpu\":true,\"memoryBudgetMiB\":128,\"highlightBoostEv\":"+boost;
   String p=base+"}";String result=bridge.nativeRender(handle,input,output,w,h,p);gpu(result);byte[] first=bytes(output);Files.writeString(logs.resolve(id+"-preview.json"),result);
   result=bridge.nativeRender(handle,input,output,w,h,p);check(result.contains("\"ok\":true"),result);check(Arrays.equals(first,bytes(output)),"repeated preview changed");Files.writeString(logs.resolve(id+"-repeat.json"),result);
   Path out=root.resolve("files/"+id+".rgb");result=bridge.nativeExportFile(handle,in.toString(),out.toString(),w,h,base+",\"mode\":\"export\"}");gpu(result);check(result.contains("cinestill4g-v1"),"old GPU reuse identity");Files.writeString(logs.resolve(id+"-export.json"),result);
   byte[] raw=Files.readAllBytes(out);check(raw.length==n,"size");ByteBuffer a=ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN),b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);double max=0;
   for(int i=0;i<n;i+=4){float v=b.getFloat(i);check(Float.isFinite(v),"nonfinite");max=Math.max(max,Math.abs(v-a.getFloat(i)));}
   Files.write(logs.resolve(id+"-preview.rgb"),first);Files.write(logs.resolve(id+"-export.rgb"),raw);System.out.println("PASS "+id+" GPU preview/repeat/export max_error="+max);
  }}finally{bridge.nativeDestroy(handle);}
 }
}
