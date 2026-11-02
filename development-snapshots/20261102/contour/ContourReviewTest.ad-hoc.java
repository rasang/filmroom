package dev.filmroom.ui;
import android.app.Application;
import android.graphics.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, manifest=Config.NONE, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContourReviewTest {
 static final String ROOT="/workspace/shared/filmroom-contour-review-20261102/";
 static Bitmap thumb(Bitmap src,int edge)throws Exception{double s=Math.min(1.0,edge/(double)Math.max(src.getWidth(),src.getHeight()));Bitmap b=Bitmap.createScaledBitmap(src,Math.max(1,(int)(src.getWidth()*s)),Math.max(1,(int)(src.getHeight()*s)),true);ByteArrayOutputStream o=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,85,o);byte[] a=o.toByteArray();Bitmap r=BitmapFactory.decodeByteArray(a,0,a.length);if(b!=src)b.recycle();return r;}
 static Bitmap candidate(Bitmap src){
  int w=src.getWidth(),h=src.getHeight(),n=w*h;int[] p=new int[n],gray=new int[n],smooth=new int[n];src.getPixels(p,0,w,0,0,w,h);
  for(int i=0;i<n;i++)gray[i]=(((p[i]>>16)&255)*77+((p[i]>>8)&255)*150+(p[i]&255)*29)/256;
  for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int i=y*w+x,sum=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)sum+=gray[i+dy*w+dx]*(dx==0?2:1)*(dy==0?2:1);smooth[i]=(gray[i]*3+sum/16)/4;}
  float[] mag=new float[n];byte[] dir=new byte[n];
  for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++){int i=y*w+x;int gx=-smooth[i-w-1]+smooth[i-w+1]-2*smooth[i-1]+2*smooth[i+1]-smooth[i+w-1]+smooth[i+w+1];int gy=-smooth[i-w-1]-2*smooth[i-w]-smooth[i-w+1]+smooth[i+w-1]+2*smooth[i+w]+smooth[i+w+1];mag[i]=(float)Math.hypot(gx,gy);float ax=Math.abs(gx),ay=Math.abs(gy);dir[i]=(byte)(ay<ax*.4142?0:ax<ay*.4142?1:gx*gy>0?2:3);}
  Arrays.fill(p,Color.BLACK);
  for(int y=3;y<h-3;y++)for(int x=3;x<w-3;x++){int i=y*w+x,d=dir[i]==0?1:dir[i]==1?w:dir[i]==2?w+1:w-1;float m=mag[i],peak=Math.max(mag[i-d],mag[i+d]);float narrow=m<peak*.92f?.16f:1f;int v=Math.max(0,Math.min(240,Math.round((m-26)*1.05f*narrow)));p[i]=Color.rgb(v,v,v);}
  return Bitmap.createBitmap(p,w,h,Bitmap.Config.ARGB_8888);
 }
 static Bitmap frame(Bitmap b,int w,int h){ExportPreviewView v=new ExportPreviewView(RuntimeEnvironment.getApplication());v.layout(0,0,w,h);v.bindJob("review");v.setForeground(false);v.show(b,"frame",false);Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(out));v.release();return out;}
 static void save(Bitmap b,String name)throws Exception{try(FileOutputStream f=new FileOutputStream(ROOT+"results/"+name+".png")){b.compress(Bitmap.CompressFormat.PNG,100,f);}}
 static void board(Bitmap before,Bitmap after,String name,String title)throws Exception{
  int fw=328,fh=176;Bitmap a=frame(before,fw,fh),b=frame(after,fw,fh);save(a,name+"-before-normal");save(b,name+"-after-normal");
  Bitmap board=Bitmap.createBitmap(704,490,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(board);c.drawColor(Color.rgb(16,19,17));Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);p.setTextSize(19);c.drawText(title,16,26,p);p.setTextSize(15);c.drawText("CURRENT",16,54,p);c.drawText("PROTOTYPE",360,54,p);c.drawBitmap(a,16,66,null);c.drawBitmap(b,360,66,null);p.setColor(Color.LTGRAY);c.drawText("2x detail - identical crop, no extra sharpening",16,272,p);
  Rect crop=name.equals("d800")?new Rect(8,80,172,168):new Rect(105,25,269,113);
  Paint nearest=new Paint();nearest.setFilterBitmap(false);c.drawBitmap(a,crop,new Rect(16,286,344,462),nearest);c.drawBitmap(b,crop,new Rect(360,286,688,462),nearest);p.setTextSize(12);c.drawText("UI-only prototype / real Canvas / public CC0 photograph / final export unchanged",16,482,p);save(board,name+"-comparison");
 }
 @Test public void realBeforeAfterAndBoundedNoise()throws Exception{
  String[] files={ROOT+"inputs/d800-embedded.jpg","/workspace/shared/filmroom-beta4-20261102/source/app/src/test/resources/queue/public-source.jpg"};
  for(int j=0;j<files.length;j++){
   BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(files[j],o);int sample=1;while(Math.max(o.outWidth,o.outHeight)/sample>1024)sample*=2;o.inJustDecodeBounds=false;o.inSampleSize=sample;Bitmap src=BitmapFactory.decodeFile(files[j],o);Assert.assertNotNull(src);
   Bitmap legacy=thumb(src,240),fresh=thumb(src,480);Bitmap before=ExportContourCache.INSTANCE.get("baseline-"+j,legacy),after=candidate(fresh);board(before,after,j==0?"d800":"city",j==0?"D800 - fine branches / deep shadows":"Pixel 2 XL - architecture / fine lines");save(candidate(legacy),(j==0?"d800":"city")+"-old240-candidate");
   Assert.assertTrue(after.getWidth()<=480&&after.getHeight()<=480);save(frame(before,656,352),(j==0?"d800":"city")+"-before-large");save(frame(after,656,352),(j==0?"d800":"city")+"-after-large");src.recycle();legacy.recycle();fresh.recycle();after.recycle();
  }
  Bitmap flat=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888);flat.eraseColor(Color.rgb(60,60,60));Bitmap out=candidate(flat);int[] px=new int[240*160];out.getPixels(px,0,240,0,0,240,160);for(int v:px)Assert.assertEquals(Color.BLACK,v);
  System.out.println("Actual baseline Kotlin bytecode + candidate Java Bitmap/Canvas: PASS. No export/native files written.");
 }
}
