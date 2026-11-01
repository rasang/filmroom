package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class NativeFilmBridge {
  public native long nativeCreate(String dataDirectory);
  public native String nativeRender(long handle, ByteBuffer input, ByteBuffer output, int width, int height, String params);
  public native void nativeCancel(long handle);
  public native void nativeDestroy(long handle);
  public native String nativeExportFile(long handle, String inputPath, String outputPath, int width, int height, String params);
  public native String nativePlanExport(long handle, int width, int height, String params);
  public native String nativeGetProgress(long handle);
  static ByteBuffer direct(int n) { return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()); }
  static void require(boolean c, String m) { if(!c) throw new AssertionError(m); }
  static String code(String r) { return r; }
  public static void main(String[] args) throws Exception {
    System.load(args[0]); NativeFilmBridge b=new NativeFilmBridge(); List<String> checks=new ArrayList<>();
    require(b.nativeCreate("/does/not/exist")==0,"missing data must fail"); checks.add("missing_data");
    long h=b.nativeCreate(args[1]); require(h!=0,"create with bundled data");
    int n=16*16*3*4; ByteBuffer in=direct(n),out=direct(n);
    for(int y=0;y<16;y++) for(int x=0;x<16;x++) for(int c=0;c<3;c++) in.putFloat((y*16*3+x*3+c)*4,0.01f+0.99f*x/15f);
    require(b.nativeRender(h,in,out,0,16,"{}").contains("INVALID_DIMENSIONS"),"dimensions");checks.add("invalid_dimensions");
    require(b.nativeRender(h,in,out,769,768,"{}").contains("IMAGE_TOO_LARGE"),"pixel guard");checks.add("pixel_guard");
    require(b.nativeRender(h,in,out,16,16,"{\"x\":1}").contains("INVALID_PARAMS"),"unknown parameter");checks.add("unknown_parameter");
    require(b.nativeRender(h,in,out,16,16,"{\"filmStock\":\"../../etc/passwd\"}").contains("UNSUPPORTED_PROFILE"),"profile traversal");checks.add("profile_allowlist");
    require(b.nativeRender(h,ByteBuffer.allocate(n),out,16,16,"{}").contains("INVALID_BUFFER"),"heap buffer");checks.add("nondirect_buffer");
    require(b.nativeRender(h,null,out,16,16,"{}").contains("INVALID_BUFFER"),"null input");require(b.nativeRender(h,in,null,16,16,"{}").contains("INVALID_BUFFER"),"null output");checks.add("null_buffers");
    require(b.nativeRender(h,in,out,16,16,null).contains("INVALID_PARAMS"),"null parameters");checks.add("null_parameters");
    require(b.nativeRender(h,in,direct(n-4),16,16,"{}").contains("INVALID_BUFFER"),"short buffer");checks.add("short_buffer");
    require(b.nativeRender(h,in,in,16,16,"{}").contains("INVALID_BUFFER"),"overlap");checks.add("overlap_buffer");
    in.position(4);require(b.nativeRender(h,in,out,16,16,"{}").contains("INVALID_BUFFER"),"position");in.position(0);checks.add("position");
    in.order(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN);require(b.nativeRender(h,in,out,16,16,"{}").contains("INVALID_BUFFER"),"byte order");in.order(ByteOrder.nativeOrder());checks.add("byte_order");
    ByteBuffer unaligned=direct(n+1);unaligned.position(1);unaligned=unaligned.slice().order(ByteOrder.nativeOrder());require(b.nativeRender(h,unaligned,out,16,16,"{}").contains("INVALID_BUFFER"),"unaligned");checks.add("unaligned_buffer");
    require(b.nativeRender(h,in,out.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),16,16,"{}").contains("INVALID_BUFFER"),"readonly output");checks.add("readonly_output");
    float first=in.getFloat(0);in.putFloat(0,Float.NaN);require(b.nativeRender(h,in,out,16,16,"{}").contains("INVALID_PIXELS"),"NaN");in.putFloat(0,first);checks.add("nonfinite_input");
    b.nativeCancel(h);String result=b.nativeRender(h,in,out,16,16,"{\"grainAmount\":0,\"halationAmount\":0}");require(result.contains("\"ok\":true"),"real render after prior cancellation: "+result);checks.add("real_spectral_render");checks.add("old_cancel_does_not_poison_render");
    for(int k=0;k<n;k+=4)require(Float.isFinite(out.getFloat(k)),"nonfinite output");checks.add("finite_output");
    byte[] bytes=new byte[n];out.duplicate().get(bytes);Files.write(Path.of(args[2]),bytes);
    String fx=b.nativeRender(h,in,out,16,16,"{}");require(fx.contains("\"ok\":true"),"default physical effects render: "+fx);float[] seeded=new float[n/4];for(int k=0;k<seeded.length;k++)seeded[k]=out.getFloat(k*4);checks.add("default_grain_halation_render");
    String otherSeed=b.nativeRender(h,in,out,16,16,"{\"seed\":43}");require(otherSeed.contains("\"ok\":true"),"seeded effects render");boolean changed=false;for(int k=0;k<seeded.length;k++)changed|=seeded[k]!=out.getFloat(k*4);require(changed,"grain seed must affect physical output");checks.add("grain_seed_applied");
    b.nativeDestroy(h);require(b.nativeRender(h,in,out,16,16,"{}").contains("INVALID_HANDLE"),"destroyed handle");b.nativeDestroy(h);b.nativeCancel(h);checks.add("destroyed_handle");
    System.out.println("{\"status\":\"passed\",\"checks\":[\""+String.join("\",\"",checks)+"\"],\"render\":"+result+"}");
  }
}
