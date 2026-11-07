package dev.filmroom.engine;
import java.nio.*;
import java.util.Locale;
/** Frozen host JNI + shipped Android data. Synthetic test, not a device-render claim. */
public final class PrintMediumProbe {
    public static void main(String[] args) {
        System.load(args[0]);
        NativeFilmBridge bridge = new NativeFilmBridge();
        long handle = bridge.nativeCreate(args[1]);
        if (handle == 0) throw new AssertionError("Bundled profile data did not open");
        int width=19,height=13,bytes=width*height*3*4;
        ByteBuffer input=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        ByteBuffer output=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        for(int i=0;i<bytes/4;i++)input.putFloat(i*4,0.015f+(i%37)/45.0f);
        int checks=0;
        try {
            for(String film:new String[]{"kodak_portra_400","cinestill_800t"}) {
                for(String paper:new String[]{"ilford_multigrade_iv_rc","kodak_portra_endura","kodak_2302"}) {
                    String params="{\"filmStock\":\""+film+"\",\"paperStock\":\""+paper+"\",\"outputRoute\":\"print\",\"useGpu\":false,\"grainAmount\":0,\"halationAmount\":0,\"scatterAmount\":0,\"dirAmount\":0}";
                    String result=bridge.nativeRender(handle,input,output,width,height,params);
                    if(!result.contains("\"ok\":true"))throw new AssertionError(film+"/"+paper+": "+result);
                    float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY,chroma=0;
                    for(int i=0;i<bytes/4;i++){float x=output.getFloat(i*4);if(!Float.isFinite(x))throw new AssertionError("Nonfinite output");min=Math.min(min,x);max=Math.max(max,x);}
                    for(int i=0;i<width*height;i++){float r=output.getFloat(i*12),g=output.getFloat(i*12+4),b=output.getFloat(i*12+8);chroma=Math.max(chroma,Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)));}
                    if(!(max>min && max>0 && min<1))throw new AssertionError("Flat/clipped output");
                    boolean bw=!paper.equals("kodak_portra_endura");
                    if(bw && chroma>.015f || !bw && chroma<.03f)throw new AssertionError("Wrong colour response "+film+"/"+paper+" chroma="+chroma);
                    System.out.printf(Locale.ROOT,"PASS film=%s paper=%s max_rgb_spread=%.8f min=%.8f max=%.8f%n",film,paper,chroma,min,max);
                    checks++;
                }
            }
        } finally { bridge.nativeDestroy(handle); }
        System.out.println("PASS host-JNI print-medium checks="+checks);
    }
}
