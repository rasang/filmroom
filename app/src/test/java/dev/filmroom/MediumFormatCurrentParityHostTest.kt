package dev.filmroom

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors

/** Current reviewed revisions, unchanged stocks and push aliases.
 * 190 supported combinations; exhaustive preview-vs-float-export pixels.
 * No external master fixture, JNI codec, image files, or absolute paths required.
 * This is rendering parity, not real-film authenticity or final JPEG/TIFF validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediumFormatCurrentParityHostTest {
    private fun initialize(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        BorderCatalog.initialize(app.assets){dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        BorderCatalog.clearCache()
    }
    private fun resolve(s:BorderSnapshot):BorderRaster {
        val worker=Executors.newSingleThreadExecutor()
        return try {worker.submit<BorderRaster>{BorderCatalog.resolve(s)}.get()}finally{worker.shutdown()}
    }
    private val acceptedVersions=mapOf(
        "kodak_gold_200" to "mf120-v4-gold200-6fad2b869ee0",
        "kodak_ektar_100" to "mf120-v4-ektar100-91d648b6bb15",
        "kodak_ektachrome_100" to "mf120-v4-e100-12d17d86a868",
        "kodak_portra_400" to "mf120-v6-portra400-3c329d78f008",
        "fujifilm_provia_100f" to "mf120-v2-provia100f-a897d5f2ea77",
        "fujifilm_velvia_100" to "mf120-v2-velvia100-9836225fb731",
        "fujifilm_pro_400h" to "mf120-v2-pro400h-2d036c5cba8c",
        "kodak_portra_160" to "mf120-v5-portra160-a970a81fe0dc",
        "kodak_portra_800" to "mf120-v5-portra800-64b689c66093"
    )
    @Test fun allCurrentProfilesAndSupportedRoutesMatchFloatExport(){
        initialize()
        var completedCases=0
        var comparedPixels=0L
        assertEquals(11,MediumFormatStocks.profiles.size)
        assertEquals(5,MediumFormat.values().size)
        val width=181;val height=137
        val photo=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
        // Asymmetric, nonuniform RGB catches flipped axes, wrong crop/zoom, and channel swaps.
        val pixels=IntArray(width*height){i->val x=i%width;val y=i/width
            (0xff shl 24)or(((x*3+y*7+11)%256)shl 16)or(((x*11+y*2+37)%256)shl 8)or((x*x+y*13+71)%256)}
        photo.setPixels(pixels,0,width,0,0,width,height)
        val dir=java.nio.file.Files.createTempDirectory("medium120-parity").toFile()
        try {
            val bytes=java.nio.ByteBuffer.allocate(width*height*12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val linear=FloatArray(width*height*3)
            for(i in pixels.indices)for(c in 0..2){
                val value=dev.filmroom.io.LinearRgb.srgb8ToLinear[(pixels[i] ushr ((2-c)*8))and 255]
                linear[i*3+c]=value;bytes.putFloat(value)
            }
            val input=java.io.File(dir,"input.rgb");input.writeBytes(bytes.array())
            for(stock in MediumFormatStocks.profiles.keys)for(route in if(stock in listOf("kodak_ektachrome_100","fujifilm_provia_100f","fujifilm_velvia_100"))listOf("direct") else listOf("print","direct"))for(f in MediumFormat.values())for(p in listOf(false,true)){
                val composition=CompositionTransform(centerX=.63,centerY=.38,zoom=1.7)
                val s=BorderCatalog.snapshot(stock,width,height,width,height,composition,true,route,f,p)
                assertEquals("Current accepted asset for $stock",acceptedVersions[MediumFormatStocks.physicalStock(stock)],s.assetVersion)
                assertEquals(.63,s.composition.centerX,0.0);assertEquals(.38,s.composition.centerY,0.0)
                assertEquals(1.7,s.composition.zoom,0.0)
                val raster=resolve(s);val preview=BorderPreview.compose(photo,s,raster)
                try {
                    val output=java.io.File(dir,"output.rgb")
                    BorderCompositor.composite(input,output,s,raster,dev.filmroom.io.OutputColorSpace.SRGB)
                    val values=java.nio.ByteBuffer.wrap(output.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    // Independent expected crop extent and source interpolation, not BorderSampling.
                    val aspect=f.geometry(p).aspect;val sourceAspect=width.toDouble()/height
                    val cropWidth=minOf(1.0,aspect/sourceAspect)/1.7
                    val cropHeight=minOf(1.0,sourceAspect/aspect)/1.7
                    for(fx in listOf(.2,.5,.8))for(fy in listOf(.25,.55,.75)){
                        val x=(s.apertureLeft+fx*s.photoWidth).toInt();val y=(s.apertureTop+fy*s.photoHeight).toInt()
                        val sx=((.63-cropWidth/2+(x+.5-s.apertureLeft)/s.photoWidth*cropWidth)*width-.5).coerceIn(0.0,width-1.0)
                        val sy=((.38-cropHeight/2+(y+.5-s.apertureTop)/s.photoHeight*cropHeight)*height-.5).coerceIn(0.0,height-1.0)
                        val x0=kotlin.math.floor(sx).toInt();val y0=kotlin.math.floor(sy).toInt()
                        val x1=minOf(x0+1,width-1);val y1=minOf(y0+1,height-1);val dx=sx-x0;val dy=sy-y0
                        for(c in 0..2){
                            val expected=(linear[(y0*width+x0)*3+c]*(1-dx)+linear[(y0*width+x1)*3+c]*dx)*(1-dy)+
                                (linear[(y1*width+x0)*3+c]*(1-dx)+linear[(y1*width+x1)*3+c]*dx)*dy
                            assertEquals("Independent crop $f portrait=$p x=$x y=$y channel=$c",expected,values.get((y*s.outputWidth+x)*3+c).toDouble(),1e-6)
                        }
                    }
                    for(y in 0 until s.outputHeight)for(x in 0 until s.outputWidth){
                        val r=dev.filmroom.io.OutputColorSpace.SRGB.encode8(values.get())
                        val g=dev.filmroom.io.OutputColorSpace.SRGB.encode8(values.get())
                        val b=dev.filmroom.io.OutputColorSpace.SRGB.encode8(values.get())
                        assertEquals("Preview/export $stock $route $f portrait=$p x=$x y=$y",(0xff shl 24)or(r shl 16)or(g shl 8)or b,preview.getPixel(x,y))
                    }
                completedCases++;comparedPixels+=s.outputWidth.toLong()*s.outputHeight
                println("CURRENT120 PARITY PASS stock=$stock route=$route format=$f portrait=$p pixels=${s.outputWidth*s.outputHeight}")
                }finally{preview.recycle()}
            }
            assertEquals("All supported combinations exercised",190,completedCases)
            assertEquals("Every output pixel compared",3791469L,comparedPixels)
        }finally{photo.recycle();dir.deleteRecursively()}
    }

}
