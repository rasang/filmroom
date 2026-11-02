package dev.filmroom.engine;

import java.nio.*;
import java.nio.file.*;

/** Run against the freshly built host library, not an old bundled Android .so.
 * args: absolute library path, sandbox root containing files/engine-data and cache. */
public final class BwProfileProbe {
  public static void main(String[] args) throws Exception {
    System.load(args[0]);
    Path root=Path.of(args[1]).toAbsolutePath(), files=root.resolve("files"), cache=root.resolve("cache");
    Files.createDirectories(cache);
    NativeFilmBridge bridge=new NativeFilmBridge();
    long handle=bridge.nativeCreate(files.resolve("engine-data").toString());
    NativeFilmBridge.require(handle!=0,"BW data initialization");
    int w=31,h=23,n=w*h*12;
    ByteBuffer input=NativeFilmBridge.direct(n), preview=NativeFilmBridge.direct(n);
    for(int i=0;i<n/4;i++)input.putFloat(i*4,0.02f+(i%97)/110f);
    byte[] original=new byte[n];input.duplicate().get(original);
    Path source=cache.resolve("bw-probe-input.rgb");Files.write(source,original);
    int cases=0;
    try {
      for(String film:new String[]{"kodak_doublex","kodak_trix"})for(String route:new String[]{"direct","print"}) {
        if(film.equals("kodak_trix")&&route.equals("print"))continue;
        String params="{\"filmStock\":\""+film+"\",\"paperStock\":\"kodak_2302\",\"outputRoute\":\""+route+"\",\"filmDevelopmentTime\":9,\"paperDevelopmentTime\":7,\"filmFormatMm\":"+(film.equals("kodak_trix")?8:35)+",\"useGpu\":false}";
        String rendered=bridge.nativeRender(handle,input,preview,w,h,params);
        NativeFilmBridge.require(rendered.contains("\"ok\":true"),"BW preview "+rendered);
        for(int i=0;i<n;i+=4)NativeFilmBridge.require(Float.isFinite(preview.getFloat(i)),"finite BW preview");
        Path output=files.resolve("bw-probe-"+film+"-"+route+"-"+System.nanoTime()+".rgb");
        String result=bridge.nativeExportFile(handle,source.toString(),output.toString(),w,h,params.substring(0,params.length()-1)+",\"mode\":\"export\"}");
        NativeFilmBridge.require(result.contains("\"ok\":true")&&Files.size(output)==n,"complete BW export "+result);
        ByteBuffer pixels=ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<n;i+=4)NativeFilmBridge.require(Float.isFinite(pixels.getFloat(i)),"finite BW export");
        Files.delete(output);cases++;
      }
      NativeFilmBridge.require(bridge.nativeRender(handle,input,preview,w,h,"{\"filmStock\":\"kodak_2302\"}").contains("UNSUPPORTED_PROFILE"),"print stock rejected as camera");
      NativeFilmBridge.require(bridge.nativeRender(handle,input,preview,w,h,"{\"filmStock\":\"kodak_trix\",\"outputRoute\":\"print\"}").contains("UNSUPPORTED_PROFILE"),"reversal print rejected");
      NativeFilmBridge.require(java.util.Arrays.equals(original,Files.readAllBytes(source)),"source unchanged");
      System.out.println("{\"status\":\"passed\",\"bwRoutes\":"+cases+",\"completeOutputs\":true,\"invalidRolesRejected\":true}");
    } finally {bridge.nativeDestroy(handle);Files.deleteIfExists(source);}
  }
}
