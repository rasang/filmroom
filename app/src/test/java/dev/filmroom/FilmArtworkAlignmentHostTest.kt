package dev.filmroom
import android.app.Application
import android.graphics.*
import dev.filmroom.ui.FilmStockArtwork
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilmArtworkAlignmentHostTest {
 @Test fun visibleFilmModelsAlignAtCompactAndHighDensitySizes() {
  val ids=listOf("none")+FilmStockArtwork.physicalStockByProfile.keys
  for(edge in listOf(56,168)) {
   val strip=Bitmap.createBitmap(edge*ids.size,edge,Bitmap.Config.ARGB_8888)
   for((index,id) in ids.withIndex()) {
    val bitmap=Bitmap.createBitmap(edge,edge,Bitmap.Config.ARGB_8888)
    val art=FilmStockArtwork.drawable(RuntimeEnvironment.getApplication(),id);art.setBounds(0,0,edge,edge);art.draw(Canvas(bitmap))
    var l=edge;var t=edge;var r=-1;var b=-1
    for(y in 0 until edge)for(x in 0 until edge)if(Color.alpha(bitmap.getPixel(x,y))>8){l=minOf(l,x);t=minOf(t,y);r=maxOf(r,x);b=maxOf(b,y)}
    assertTrue(id,r>l && b>t)
    assertEquals("$id x",edge/2.0,(l+r+1)/2.0,edge*.025)
    assertEquals("$id y",edge/2.0,(t+b+1)/2.0,edge*.025)
    assertEquals("$id size",edge*.75,maxOf(r-l+1,b-t+1).toDouble(),edge*.04)
    Canvas(strip).drawBitmap(bitmap,(index*edge).toFloat(),0f,null);bitmap.recycle()
   }
   File(System.getProperty("filmroom.noneReviewDir"),"artwork-$edge.png").outputStream().use{strip.compress(Bitmap.CompressFormat.PNG,100,it)};strip.recycle()
  }
 }
}
