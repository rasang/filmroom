package dev.filmroom.engine;
import java.nio.*;
/** Host JNI integration only; synthetic pixels, no Android/device claim. */
public final class SpektraLabProbe {
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
            for(String film:new String[]{"kodak_tri_x_400","kodak_tmax_100","fujifilm_neopan_acros_100_ii","ilford_hp5_plus_400","cinestill_800t"}) {
                String paper=film.equals("cinestill_800t")?"kodak_2383":"ilford_multigrade_iv_rc";
                for(String route:new String[]{"direct","print"}) {
                    String params="{\"filmStock\":\""+film+"\",\"paperStock\":\""+paper+"\",\"outputRoute\":\""+route+"\",\"useGpu\":false,\"grainAmount\":0,\"halationAmount\":0,\"scatterAmount\":0,\"dirAmount\":0}";
                    String result=bridge.nativeRender(handle,input,output,width,height,params);
                    if(!result.contains("\"ok\":true"))throw new AssertionError(film+"/"+route+": "+result);
                    float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
                    for(int i=0;i<bytes/4;i++){float x=output.getFloat(i*4);if(!Float.isFinite(x))throw new AssertionError("Nonfinite output");min=Math.min(min,x);max=Math.max(max,x);}
                    if(!(max>min && max>0 && min<1))throw new AssertionError("Flat/clipped output");
                    System.out.println("PASS "+film+" "+route+" finite="+(bytes/4)+" min="+min+" max="+max);
                    checks++;
                }
            }
        } finally { bridge.nativeDestroy(handle); }
        System.out.println("PASS host-JNI routes="+checks);
    }
}
