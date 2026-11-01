package dev.filmroom.engine;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class ControlsProbe {
  public static void main(String[] args) throws Exception {
    System.load(args[0]); NativeFilmBridge b=new NativeFilmBridge(); long h=b.nativeCreate(args[1]);
    if(h==0)throw new AssertionError("data unavailable");
    Path dir=Path.of(args[2]);Files.createDirectories(dir);
    int w=32,hh=16,n=w*hh*12; ByteBuffer in=NativeFilmBridge.direct(n),out=NativeFilmBridge.direct(n);
    float[][] colors={{.50f,.03f,.02f},{.03f,.50f,.02f},{.02f,.03f,.50f},{.40f,.18f,.10f},{.03f,.35f,.40f},{.35f,.03f,.40f},{.50f,.40f,.02f},{.18f,.18f,.18f}};
    for(int y=0;y<hh;y++)for(int x=0;x<w;x++)for(int c=0;c<3;c++){float v=x<16?(float)Math.exp(Math.log(.02)+(Math.log(.80)-Math.log(.02))*x/15.):colors[(x-16)/2][c];in.putFloat(((y*w+x)*3+c)*4,v);}
    Map<String,String> cases=new LinkedHashMap<>();cases.put("baseline","");
    for(String k:new String[]{"saturation","warmth","contrast","brightnessEv"}){
      double[] values=switch(k){case "saturation"->new double[]{0,1,2};case "contrast"->new double[]{.5,1,1.5};default->new double[]{-1,0,1};};
      for(double v:values)cases.put(k+"_"+v,",\""+k+"\":"+v);
    }
    cases.put("gold200",",\"filmStock\":\"kodak_gold_200\"");
    cases.put("fujic200",",\"filmStock\":\"fujifilm_c200\",\"paperStock\":\"fujifilm_crystal_archive_typeii\"");
    List<String> results=new ArrayList<>();
    for(var entry:cases.entrySet()){
      String params="{\"grainAmount\":0,\"halationAmount\":0"+entry.getValue()+"}";
      String result=b.nativeRender(h,in,out,w,hh,params);if(!result.contains("\"ok\":true"))throw new AssertionError(entry.getKey()+": "+result);
      byte[] bytes=new byte[n];out.duplicate().get(bytes);Files.write(dir.resolve(entry.getKey()+".f32le"),bytes);
      results.add("{\"case\":\""+entry.getKey()+"\",\"result\":"+result+"}");
    }
    b.nativeDestroy(h);Files.writeString(dir.resolve("renders.json"),"["+String.join(",",results)+"]");
    System.out.println("rendered "+cases.size()+" actual host JNI cases");
  }
}
