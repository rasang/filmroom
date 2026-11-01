import dev.filmroom.engine.NativeFilmBridge;
import java.nio.*;
public class HdrPreviewSmoke {
 static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[]args){
  System.load(args[0]);NativeFilmBridge bridge=new NativeFilmBridge();long h=bridge.nativeCreate(args[1]);require(h!=0,"handle");
  int w=65,height=49,n=w*height*3;ByteBuffer input=ByteBuffer.allocateDirect(n*4).order(ByteOrder.nativeOrder());
  for(int i=0;i<n;i++)input.putFloat(i%37==0?5.5f:i%113==0?-.002f:.02f+(i%97)*.003f);input.rewind();
  ByteBuffer ro=input.asReadOnlyBuffer().order(ByteOrder.nativeOrder());require(ro.isReadOnly()&&ro.isDirect(),"read-only direct input");
  ByteBuffer a=ByteBuffer.allocateDirect(n*4).order(ByteOrder.nativeOrder()),b=ByteBuffer.allocateDirect(n*4).order(ByteOrder.nativeOrder());
  String p="{\"useGpu\":false,\"grainAmount\":0,\"autoExposure\":true,\"metering\":\"partial\"}";
  String rw=bridge.nativeRender(h,input,a,w,height,p),rr=bridge.nativeRender(h,ro,b,w,height,p);
  require(rw.contains("\"ok\":true")&&rr.contains("\"ok\":true"),"successful JNI paths");
  require(rr.contains("\"meteredEv\":")&&!rr.contains("\"meteredEv\":null"),"measured EV diagnostic");
  for(int i=0;i<n;i++){require(a.getInt(i*4)==b.getInt(i*4),"read-only output bit parity");require(Float.isFinite(a.getFloat(i*4)),"finite output");}
  require(input.getFloat(0)==5.5f&&input.getFloat(113*4)==-.002f,"HDR and negative input unchanged");
  ByteBuffer invalid=b.asReadOnlyBuffer().order(ByteOrder.nativeOrder());int sentinel=b.getInt(0);
  String bad=bridge.nativeRender(h,ro,invalid,w,height,p);require(bad.contains("INVALID_BUFFER")&&b.getInt(0)==sentinel,"read-only output rejected before commit");
  String off=bridge.nativeRender(h,ro,a,w,height,"{\"useGpu\":false,\"grainAmount\":0,\"autoExposure\":false}");
  require(off.contains("\"ok\":true")&&off.contains("\"meteredEv\":null"),"AE off diagnostic");
  bridge.nativeDestroy(h);
  System.out.println("{\"ok\":true,\"suite\":\"HDR read-only host JNI\",\"readOnlyInputMatchesWritableExactly\":true,\"hdrNegativeInputUnchanged\":true,\"readOnlyOutputRejected\":true,\"meteredEvOnOffVerified\":true,\"androidRuntimeMeasured\":false}");
 }
}
