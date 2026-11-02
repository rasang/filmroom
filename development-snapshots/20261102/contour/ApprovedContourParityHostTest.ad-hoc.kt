package dev.filmroom.ui

import android.app.Application
import android.graphics.*
import dev.filmroom.border.BorderMemoryAdmission
import dev.filmroom.engine.NativeMemorySnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedContourParityHostTest {
 private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
 @Test fun actualKotlinMatchesApprovedJavaPixelsOnBothPublicPhotos(){
  val files=listOf("/workspace/shared/filmroom-contour-review-20261102/inputs/d800-embedded.jpg","/workspace/shared/filmroom-beta4-20261102/source/app/src/test/resources/queue/public-source.jpg")
  files.forEachIndexed{j,path->
   val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path,o);var sample=1;while(maxOf(o.outWidth,o.outHeight)/sample>1024)sample*=2;o.inJustDecodeBounds=false;o.inSampleSize=sample
   val src=BitmapFactory.decodeFile(path,o);val tiny=ContourReviewTest.thumb(src,480);val before=pixels(tiny)
   val expected=ContourReviewTest.candidate(tiny);val actual=ExportContourCache.get("approved-$j",tiny)
   assertArrayEquals(pixels(expected),pixels(actual));assertArrayEquals(before,pixels(tiny))
   val frame=ContourReviewTest.frame(actual,328,176);val saved=BitmapFactory.decodeFile("/workspace/shared/filmroom-contour-review-20261102/results/${if(j==0)"d800" else "city"}-after-normal.png")
   assertArrayEquals("Approved displayed frame must remain exact",pixels(saved),pixels(frame))
   expected.recycle();src.recycle();tiny.recycle();saved.recycle();frame.recycle()
  }
 }
}
