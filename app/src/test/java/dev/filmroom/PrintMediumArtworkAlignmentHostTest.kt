package dev.filmroom

import android.app.Application
import android.graphics.*
import dev.filmroom.ui.PrintMediumArtwork
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrintMediumArtworkAlignmentHostTest {
 @Test fun everyArtworkUsesCenteredVisibleBoundsAndEqualVisualHeight() {
  val app=RuntimeEnvironment.getApplication()
  val ids=listOf("none")+PrintMediumArtwork.profileIds
  val sheet=Bitmap.createBitmap(240*ids.size,300,Bitmap.Config.ARGB_8888)
  val c=Canvas(sheet);c.drawColor(0xff222222.toInt())
  for((i,id) in ids.withIndex()) {
   val b=Bitmap.createBitmap(216,216,Bitmap.Config.ARGB_8888)
   val art=PrintMediumArtwork.drawable(app,id);art.setBounds(0,0,216,216);art.draw(Canvas(b))
   var l=216;var t=216;var r=-1;var d=-1
   for(y in 0 until 216)for(x in 0 until 216)if(Color.alpha(b.getPixel(x,y))>8){l=minOf(l,x);r=maxOf(r,x);t=minOf(t,y);d=maxOf(d,y)}
   assertTrue(id,r>=l)
   assertEquals("$id horizontal center",108f,(l+r+1)/2f,2f)
   assertEquals("$id vertical center",108f,(t+d+1)/2f,2f)
   assertEquals("$id longest visible side",144f,maxOf(r-l+1,d-t+1).toFloat(),8f)
   c.drawBitmap(b,i*240f+12f,12f,null)
   c.drawText(id.replace("_"," ").take(22),i*240f+8f,260f,Paint().apply{color=Color.WHITE;textSize=14f})
  }
  System.getProperty("paper.align.render")?.let{File(it).outputStream().use{out->sheet.compress(Bitmap.CompressFormat.PNG,100,out)}}
 }
}
