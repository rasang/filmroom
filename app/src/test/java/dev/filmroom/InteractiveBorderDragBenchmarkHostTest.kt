package dev.filmroom

import android.app.Application
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Explicit host native-graphics workload comparison, NOT device FPS or input-latency evidence.
 * Counts known output Bitmap allocations and raster samples; no fragile elapsed-time assertion.
 * Source is already processed1536×1024 authored pixels, never a personal photograph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractiveBorderDragBenchmarkHostTest {
    private class Counted(private val delegate:BorderRaster):BorderRaster by delegate {
        var samples=0L
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){samples++;delegate.sample(snapshot,x,y,rgb)}
    }
    @Test fun compareExactPerMoveWithCachedFrameAndCanvasAt1536(){
        assumeTrue(System.getenv("FILMROOM_BORDER_DRAG_BENCHMARK")=="1")
        val app=ApplicationProvider.getApplicationContext<Application>()
        val report=File(checkNotNull(System.getenv("FILMROOM_BORDER_DRAG_BENCHMARK_REPORT")))
        report.parentFile?.mkdirs()
        report.writeText("case,phase,iterations,elapsedNs,knownOutputBitmapAllocations,rasterSamples,sourceWidth,sourceHeight,outputWidth,outputHeight,checksum\n")
        val source=Bitmap.createBitmap(1536,1024,Bitmap.Config.ARGB_8888)
        val row=IntArray(source.width)
        for(y in 0 until source.height){for(x in row.indices)row[x]=Color.rgb((x*17+y*3)and 255,(x/8+y/4)and 255,(x+y*7)and 255);source.setPixels(row,0,row.size,0,y,row.size,1)}
        val authored=object:BorderRaster {
            override val stock="fixture_only";override val version="authored-drag-benchmark"
            override val railRatio=5.5/24;override val endRatio=1.0/24;override val residentBytes=12L;override val featherTotalAt3072=4.0
            override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb[0]=if((x.toInt()/16+y.toInt()/16)%2==0).15f else .55f;rgb[1]=.25f;rgb[2]=.1f}
        }
        val actual=CandidateBorderDefinitions.definitions.single{it.spec.stock=="kodak_portra_400"}
        try {
            for((name,unwrapped) in listOf("authored-pattern" to authored,"compiled-portra400-lossless-webp" to
                StraightAlphaBorderLoader.load(actual.spec,{app.assets.open(actual.assetPath)}))){
                val raster=Counted(unwrapped)
                val base=BorderSnapshot.fixed135(raster.stock,raster.version,source.width,source.height,featherTotalAt3072=raster.featherTotalAt3072)
                fun crop(i:Int)=CompositionTransform(.52+i*.001,.48-i*.001,1.3+i*.002).clamped(source.width,source.height)
                // One disclosed warmup per path is outside the recorded workload.
                BorderPreview.compose(source,base,raster).recycle()
                InteractiveBorderPreview.create(base,raster).recycle()
                raster.samples=0;var checksum=0L;var start=System.nanoTime()
                val exactIterations=4
                repeat(exactIterations){i->
                    val image=BorderPreview.compose(source,base.copy(composition=crop(i)),raster)
                    try {checksum+=image.getPixel(image.width/2,image.height/2).toLong()}finally{image.recycle()}
                }
                report.appendText("$name,exact-composite-per-move,$exactIterations,${System.nanoTime()-start},$exactIterations,${raster.samples},1536,1024,${base.outputWidth},${base.outputHeight},$checksum\n")
                assertTrue(raster.samples>0)
                raster.samples=0;start=System.nanoTime()
                val overlay=InteractiveBorderPreview.create(base,raster)
                report.appendText("$name,one-time-overlay-build,1,${System.nanoTime()-start},1,${raster.samples},1536,1024,${base.outputWidth},${base.outputHeight},0\n")
                val target=Bitmap.createBitmap(base.outputWidth,base.outputHeight,Bitmap.Config.ARGB_8888)
                try {
                    val canvas=Canvas(target);val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                    val destination=RectF();val aperture=RectF(base.apertureLeft.toFloat(),base.apertureTop.toFloat(),(base.apertureLeft+base.photoWidth).toFloat(),(base.apertureTop+base.photoHeight).toFloat())
                    val priorSamples=raster.samples;checksum=0;start=System.nanoTime();val moves=120
                    repeat(moves){i->
                        val rect=crop(i).rect(source.width,source.height)
                        destination.set((base.apertureLeft-rect.left*base.photoWidth/rect.width).toFloat(),
                            (base.apertureTop-rect.top*base.photoHeight/rect.height).toFloat(),
                            (base.apertureLeft+(1-rect.left)*base.photoWidth/rect.width).toFloat(),
                            (base.apertureTop+(1-rect.top)*base.photoHeight/rect.height).toFloat())
                        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                        canvas.save();canvas.clipRect(aperture);canvas.drawBitmap(source,null,destination,paint);canvas.restore()
                        canvas.drawBitmap(overlay,0f,0f,paint)
                        checksum+=target.getPixel(target.width/2,target.height/2).toLong()
                    }
                    val elapsed=System.nanoTime()-start
                    // Canvas/paint/target/overlay are all reused; this loop allocates ZERO Bitmaps
                    // and performs ZERO atlas samples. Framework/native internal allocations are
                    // not represented by this explicit application-output allocation count.
                    assertEquals(priorSamples,raster.samples)
                    report.appendText("$name,reused-canvas-drag,$moves,$elapsed,0,${raster.samples-priorSamples},1536,1024,${base.outputWidth},${base.outputHeight},$checksum\n")
                    report.appendText("$name,reused-canvas-target-setup,1,0,1,0,1536,1024,${base.outputWidth},${base.outputHeight},0\n")
                }finally{target.recycle();overlay.recycle()}
            }
        }finally{source.recycle()}
    }
}
