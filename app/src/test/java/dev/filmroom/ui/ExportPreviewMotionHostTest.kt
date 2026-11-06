package dev.filmroom.ui

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.*
import dev.filmroom.ExportPreviewSelection
import dev.filmroom.export.ExportJobState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportPreviewMotionHostTest {
    private fun view()=ExportPreviewView(RuntimeEnvironment.getApplication()).apply{layout(0,0,320,200);bindJob("a");setForeground(true)}
    private fun bitmap(color:Int)=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
    private fun field(v:ExportPreviewView,name:String)=ExportPreviewView::class.java.getDeclaredField(name).apply{isAccessible=true}.get(v)
    private fun frame(v:ExportPreviewView,fraction:Float):Bitmap {
        (field(v,"fade") as? ValueAnimator)?.setCurrentFraction(fraction)
        return Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}
    }
    @Test fun allTransitionsBlendWithoutClearingAndRepeatedProgressDoesNotRestart() {
        val v=view();v.show(bitmap(Color.WHITE),"contour",true)
        assertEquals(Color.BLACK,frame(v,0f).getPixel(100,100))
        val middle=frame(v,.5f).getPixel(100,100);assertTrue(Color.red(middle) in 1..254)
        frame(v,1f);v.setBreathing(true)
        v.show(bitmap(Color.RED),"negative");frame(v,.4f)
        val before=frame(v,.4f).getPixel(100,100)
        v.show(bitmap(Color.BLUE),"final")
        assertEquals(before,frame(v,0f).getPixel(100,100))
        v.show(bitmap(Color.GREEN),"final");assertEquals(3,v.transitionCount)
        v.setBreathing(false);frame(v,1f);assertEquals(Color.BLUE,frame(v,1f).getPixel(100,100));v.release()
    }
    @Test fun fadesFitInside500MillisecondsAndRespectRecoveredRemainder() {
        val v=view();v.show(bitmap(Color.WHITE),"contour",true)
        assertEquals(250L,(field(v,"fade") as ValueAnimator).duration)
        assertTrue((field(v,"fade") as ValueAnimator).duration<ExportPreviewSequence.MIN_STAGE_MS)
        frame(v,1f);v.show(bitmap(Color.RED),"negative",maxFadeMs=120L)
        assertEquals(120L,(field(v,"fade") as ValueAnimator).duration)
        frame(v,1f);v.show(bitmap(Color.BLUE),"positive",maxFadeMs=0L)
        assertNull(field(v,"fade"));assertFalse(v.animating)
        assertEquals(Color.BLUE,frame(v,0f).getPixel(100,100));v.release()
    }
    @Test fun backgroundNeverReplaysAnInterruptedFadeAfterTimeHasCounted() {
        val v=view();v.show(bitmap(Color.WHITE),"contour",true)
        frame(v,.2f);v.setForeground(false)
        assertFalse(v.animating);assertNull(field(v,"fade"))
        v.setForeground(true)
        assertFalse(v.animating);assertEquals(1,v.transitionCount)
        assertEquals(Color.WHITE,frame(v,0f).getPixel(100,100));v.release()
    }
    @Test fun breathingPeakTransitionsWithoutFirstFrameDiscontinuity() {
        val v=view();v.show(bitmap(Color.rgb(50,50,50)),"outline");v.setBreathing(true)
        (field(v,"breathing") as ValueAnimator).setCurrentFraction(1f)
        val before=frame(v,1f).getPixel(100,100)
        v.setBreathing(false)
        assertEquals(before,frame(v,1f).getPixel(100,100))
        v.show(bitmap(Color.RED),"negative")
        assertEquals(before,frame(v,0f).getPixel(100,100))
        (field(v,"breathing") as ValueAnimator).setCurrentFraction(1f)
        frame(v,1f);v.release()
    }
    @Test fun pendingPhotoCanFadeThroughBlackIntoFirstOutline() {
        val v=view();v.show(bitmap(Color.RED),"pending");v.show(bitmap(Color.WHITE),"outline",true)
        assertEquals(Color.RED,frame(v,0f).getPixel(100,100))
        assertEquals(Color.BLACK,frame(v,.5f).getPixel(100,100))
        assertEquals(Color.WHITE,frame(v,1f).getPixel(100,100));v.release()
    }
    @Test fun visibilityPauseCancelReuseAndReducedMotionAreStatic() {
        val v=view();v.show(bitmap(Color.WHITE),"contour",true);v.setBreathing(true)
        v.setForeground(false);assertFalse(v.animating)
        v.setBreathing(false);assertFalse(v.animating)
        v.bindJob("b");assertNull(field(v,"current"));assertNull(field(v,"previous"))
        val scale=ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}
        scale.invoke(null,0f)
        try{v.setForeground(true);v.setBreathing(true);v.show(bitmap(Color.RED),"restored",true);assertFalse(v.animating);assertEquals(Color.RED,frame(v,1f).getPixel(100,100))}
        finally{scale.invoke(null,1f);v.release()}
    }
    @Test fun restoreDoesNotReplayBlackAndContoursAreCachedAndDenoised() {
        val v=view();v.show(bitmap(Color.RED),"restored");assertEquals(Color.RED,frame(v,0f).getPixel(100,100));v.release()
        ExportContourCache.clear();val source=bitmap(Color.BLACK)
        Canvas(source).drawRect(100f,30f,220f,170f,Paint().apply{color=Color.WHITE})
        val n=ExportContourCache.computations;val a=ExportContourCache.get("source1",source);val b=ExportContourCache.get("source1",source)
        assertSame(a,b);assertEquals(n+1,ExportContourCache.computations)
        assertEquals(Color.BLACK,a.getPixel(160,100));assertTrue(Color.red(a.getPixel(100,100))>100)
        assertEquals(Color.BLACK,a.getPixel(20,100));assertTrue(a.width<=320)
    }
    @Test fun realNegativeOnlyAfterStageFourAndFinalWins() {
        val dir=File(RuntimeEnvironment.getApplication().cacheDir,"stage-preview").apply{mkdirs()}
        try {
            listOf("original-preview.jpg","negative-preview.png","final-preview.jpg").forEach{File(dir,it).writeText("test")}
            for(stage in 1..4)assertEquals("original-preview.jpg",ExportPreviewSelection.select(dir,ExportJobState.RUNNING,stage).file!!.name)
            assertEquals("negative-preview.png",ExportPreviewSelection.select(dir,ExportJobState.RUNNING,4,true).file!!.name)
            assertEquals("negative-preview.png",ExportPreviewSelection.select(dir,ExportJobState.RUNNING,5).file!!.name)
            assertEquals("final-preview.jpg",ExportPreviewSelection.select(dir,ExportJobState.COMPLETED,8).file!!.name)
            File(dir,"negative-preview.png").delete()
            assertEquals("original-preview.jpg",ExportPreviewSelection.select(dir,ExportJobState.RUNNING,5).file!!.name)
        }finally{dir.deleteRecursively()}
    }
    @Test fun activityPendingToRunningIntroAndPauseAreBoundToJobNotImageLoad() {
        val id=java.util.UUID.randomUUID().toString()
        val pending=dev.filmroom.export.ExportJob(id,0,dev.filmroom.model.EditorState(),320,200,dev.filmroom.io.ExportFormat.JPEG,95,
            ExportJobState.PENDING,dev.filmroom.engine.ExportDisplayProgress(1))
        fun render(a:dev.filmroom.ExportQueueActivity,j:dev.filmroom.export.ExportJob){
            dev.filmroom.ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(a,listOf(j))
        }
        fun card(a:dev.filmroom.ExportQueueActivity):Any=(dev.filmroom.ExportQueueActivity::class.java.getDeclaredField("cards").apply{isAccessible=true}.get(a) as Map<*,*>)[id]!!
        fun intro(c:Any)=c.javaClass.getDeclaredField("introPending").apply{isAccessible=true}.getBoolean(c)
        org.robolectric.Robolectric.buildActivity(dev.filmroom.ExportQueueActivity::class.java).setup().use { controller ->
            val a=controller.get();render(a,pending);assertFalse(intro(card(a)))
            render(a,pending.copy(state=ExportJobState.RUNNING));assertTrue(intro(card(a)))
            val c=card(a);val v=c.javaClass.getDeclaredField("image").apply{isAccessible=true}.get(c) as ExportPreviewView
            v.setForeground(true);v.show(bitmap(Color.WHITE),"test");v.setBreathing(true)
            controller.pause();assertFalse(v.animating)
        }
        org.robolectric.Robolectric.buildActivity(dev.filmroom.ExportQueueActivity::class.java).setup().use { controller ->
            render(controller.get(),pending.copy(state=ExportJobState.RUNNING));assertFalse(intro(card(controller.get())))
        }
    }
    @Test fun absentThumbnailNeverStartsEmptyBreathingLoop() {
        val v=view();v.setBreathing(true);assertFalse(v.animating)
        v.show(bitmap(Color.WHITE),"ready");assertTrue(v.animating);v.release();assertFalse(v.animating)
    }
    @Test fun writeExistingNativeSyntheticFixtureFramesWithoutRerendering() {
        val dir=File("/workspace/shared/filmroom-export-integration/validation/interactive-final/files/export-jobs/00000000-0000-0000-0000-000000000001")
        org.junit.Assume.assumeTrue(File(dir,"negative-preview.png").isFile)
        val output=File("/workspace/shared/export-preview-motion-evidence").apply{mkdirs()}
        fun raw(name:String,linear:Boolean):Bitmap {
            val bytes=java.nio.ByteBuffer.wrap(File(dir,name).readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            fun channel(v:Float):Int {val x=v.coerceIn(0f,1f);return ((if(linear){if(x<=.0031308f)12.92f*x else 1.055f*Math.pow(x.toDouble(),1.0/2.4).toFloat()-.055f}else x)*255).toInt().coerceIn(0,255)}
            val pixels=IntArray(256*256){i->val p=((i/256)*4*1024+(i%256)*4)*3;Color.rgb(channel(bytes[p]),channel(bytes[p+1]),channel(bytes[p+2]))}
            return Bitmap.createBitmap(pixels,256,256,Bitmap.Config.ARGB_8888)
        }
        val original=raw("input.rgb",true)
        val result=raw("film-result-00000000-0000-0000-0000-000000000002.rgb",false)
        val negative=BitmapFactory.decodeFile(File(dir,"negative-preview.png").path)
        val v=view()
        fun save(name:String,f:Float){File(output,"native-$name.png").outputStream().use{frame(v,f).compress(Bitmap.CompressFormat.PNG,100,it)}}
        v.show(ExportContourCache.get("native-existing-fixture",original),"outline",true)
        save("01-black",0f);save("02-outline-half",.5f);save("03-outline",1f)
        v.setBreathing(true);(field(v,"breathing") as ValueAnimator).setCurrentFraction(.8f);save("04-breathe",1f)
        v.setBreathing(false);v.show(negative,"actual-native-negative");save("05-test-color-half",.5f);save("06-test-color",1f)
        (field(v,"breathing") as? ValueAnimator)?.setCurrentFraction(1f)
        v.show(result,"actual-native-result");save("07-reference-half",.5f);save("08-reference",1f);v.release()
        val motion=view();var index=0
        fun movie(f:Float){File(output,"motion-${(index++).toString().padStart(3,'0')}.png").outputStream().use{frame(motion,f).compress(Bitmap.CompressFormat.PNG,100,it)}}
        motion.show(ExportContourCache.get("native-existing-fixture",original),"contour",true)
        repeat(20){movie(it/19f)}
        motion.setBreathing(true)
        repeat(56){(field(motion,"breathing") as ValueAnimator).setCurrentFraction(it/55f*2f);movie(1f)}
        motion.setBreathing(false);motion.show(negative,"negative")
        repeat(9){(field(motion,"breathing") as? ValueAnimator)?.setCurrentFraction(it/8f);movie(it/8f)}
        motion.show(result,"final");repeat(9){movie(it/8f)};motion.release()
    }
    @Test fun writeControlledCanvasFrames() {
        val dir=File("/workspace/shared/export-preview-motion-evidence").apply{mkdirs()}
        val v=view();val original=javaClass.getResourceAsStream("/queue/public-source.jpg").use{BitmapFactory.decodeStream(it)}
        val contour=ExportContourCache.get("demo",original)
        fun save(name:String,bitmap:Bitmap){File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        v.show(contour,"contour",true);save("01-black",frame(v,0f));save("02-outline-half",frame(v,.5f));save("03-outline",frame(v,1f))
        v.setBreathing(true);(field(v,"breathing") as? ValueAnimator)?.setCurrentFraction(.8f);save("04-breathe",frame(v,1f))
        v.setBreathing(false);v.show(bitmap(Color.rgb(110,70,35)),"negative");save("05-test-color-half",frame(v,.5f));save("06-test-color",frame(v,1f))
        v.show(original,"final");save("07-reference-half",frame(v,.5f));save("08-reference",frame(v,1f));v.release()
    }
}
