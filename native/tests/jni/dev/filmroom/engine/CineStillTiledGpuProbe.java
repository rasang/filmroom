package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
/** Actual multi-tile export and explicitly enabled preview-cache contracts. */
public final class CineStillTiledGpuProbe {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static void gpu(String r){check(r.contains("\"ok\":true")&&r.contains("\"backend\":\"wgpu\"")&&r.contains("\"active\":true")&&r.contains("\"cpuFallbacks\":0"),r);}
 static byte[] bytes(ByteBuffer b){byte[] v=new byte[b.capacity()];b.duplicate().get(v);return v;}
 static long number(String json,String key){Matcher m=Pattern.compile("\\\""+key+"\\\":([0-9]+)").matcher(json);check(m.find(),"missing "+key);return Long.parseLong(m.group(1));}
 static double difference(byte[] a,byte[] b){check(a.length==b.length,"shape changed");ByteBuffer x=ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN),y=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);double max=0;for(int i=0;i<a.length;i+=4){float v=x.getFloat(i),w=y.getFloat(i);check(Float.isFinite(v)&&Float.isFinite(w),"nonfinite pixels");max=Math.max(max,Math.abs(v-w));}return max;}
 public static void main(String[] args)throws Exception {
  System.load(args[0]);Path root=Path.of(args[1]),logs=Path.of(args[2]);NativeFilmBridge b=new NativeFilmBridge();
  int w=768,h=512,n=w*h*12;ByteBuffer input=ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN),output=ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int c=0;c<3;c++)input.putFloat(((y*w+x)*3+c)*4,x>=376&&x<392&&y>=192&&y<320?1f:.002f);
  Path source=root.resolve("cache/tiled-source.rgb");Files.write(source,bytes(input));
  for(String route:new String[]{"print","direct"}){
   long handle=b.nativeCreate(root.resolve("files/engine-data").toString());check(handle!=0,"create");
   try {
    String common="{\"filmStock\":\"cinestill_800t\",\"paperStock\":\"kodak_2383\",\"outputRoute\":\""+route+"\",\"grainAmount\":0,\"scatterAmount\":0,\"dirAmount\":0,\"filmFormatMm\":35,\"useGpu\":true";
    String preview=common+",\"memoryBudgetMiB\":384,\"previewSource\":{\"sourceRevision\":\"cinestill-lamp-v1\",\"mappingRevision\":\"768x512-linear-v1\",\"dataRevision\":\"bundled-5a11522\"}";
    b.nativeSetPreviewCacheBudget(handle,64L*1024*1024);
    String r=b.nativeRender(handle,input,output,w,h,preview+"}");gpu(r);byte[] whole=bytes(output);Files.writeString(logs.resolve(route+"-whole.json"),r);
    r=b.nativeRender(handle,input,output,w,h,preview+"}");check(r.contains("\"ok\":true")&&r.contains("\"reason\":\"preview_stage_cache_hit\"")&&r.contains("\"hit\":true"),"No actual stage-cache hit: "+r);check(Arrays.equals(whole,bytes(output)),"Stage cache changed pixels");Files.writeString(logs.resolve(route+"-cache-hit.json"),r);
    r=b.nativeRender(handle,input,output,w,h,preview+",\"contrast\":1.1}");gpu(r);check(r.contains("\"filmLogHit\":true")||r.contains("\"correctedDensityHit\":true"),"No prefix cache hit: "+r);byte[] prefixHit=bytes(output);Files.writeString(logs.resolve(route+"-prefix-hit.json"),r);
    b.nativeSetPreviewCacheBudget(handle,0);
    r=b.nativeRender(handle,input,output,w,h,preview+",\"contrast\":1.1}");gpu(r);double prefixError=difference(prefixHit,bytes(output));check(prefixError<0.00002,"Prefix reuse differs from cold "+prefixError);Files.writeString(logs.resolve(route+"-prefix-cold.json"),r);
    Path out=root.resolve("files/"+route+"-tiled.rgb");r=b.nativeExportFile(handle,source.toString(),out.toString(),w,h,common+",\"memoryBudgetMiB\":128,\"mode\":\"export\"}");gpu(r);Files.writeString(logs.resolve(route+"-tiled.json"),r);
    check(r.contains("\"stage\":\"gpu_tiled_chain\"")&&r.contains("\"stage\":\"gpu_diffusion_prepass\""),"Expected non-split tiled headroom prepass: "+r);
    check(!r.contains("\"stage\":\"gpu_film_log\"")&&!r.contains("\"stage\":\"gpu_from_film_log\""),"Unexpected split export");
    check(number(r,"coreWidth")<w||number(r,"coreHeight")<h,"Not tiled");check(number(r,"gpuBatches")>1,"No multi-submit work");
    check(number(r,"spatialPeakExplicitBufferBytes")<=128L*1024*1024,"Over budget");check(r.contains("cinestill4g-v1"),"Old optical identity");
    byte[] rendered=Files.readAllBytes(out);check(rendered.length==n,"Export shape");double error=difference(whole,rendered);check(error<0.0001,"Tiled export vs whole preview changed "+error);
    System.out.println("PASS route="+route+" shape=768x512 budgetMiB=128 nonSplitTiled=true fullStageCacheHit=true prefixCacheHit=true prefixColdError="+prefixError+" wholePreviewExportError="+error+" gpuBatches="+number(r,"gpuBatches"));
   }finally{b.nativeDestroy(handle);}
  }
 }
}
