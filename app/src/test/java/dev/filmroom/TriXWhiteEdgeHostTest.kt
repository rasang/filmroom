package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import dev.filmroom.export.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TriXWhiteEdgeHostTest {
    private val current get()=BwBorderDefinitions.definitions.single{it.spec.stock=="kodak_trix"}
    private val legacy get()=BwBorderDefinitions.legacyDefinitions.single{it.spec.stock=="kodak_trix"}
    private fun raster(d:LazyBorderRegistry.Definition)=StraightAlphaBorderLoader.load(d.spec,{RuntimeEnvironment.getApplication().assets.open(d.assetPath)})
    private fun snapshot(d:LazyBorderRegistry.Definition,w:Int,h:Int,portrait:Boolean)=BorderSnapshot.authored(d.spec.stock,d.spec.version,w,h,composition=CompositionTransform(targetAspect=BorderCatalog.frameAspect(d.spec.stock,portrait)),featherTotalAt3072=d.spec.featherTotalAt3072)
    private fun point(s:BorderSnapshot,x:Double,y:Double):Pair<Double,Double> =
        if(s.portrait)s.frameOriginX+(926-y)*s.pixelsPerMm to s.frameOriginY+x*s.pixelsPerMm
        else s.frameOriginX+x*s.pixelsPerMm to s.frameOriginY+y*s.pixelsPerMm
    private fun blackComposite(r:BorderRaster,s:BorderSnapshot,x:Double,y:Double):Double {
        val p=point(s,x,y);val rgb=FloatArray(3);r.sample(s,p.first,p.second,rgb);BorderColor.convert(rgb,OutputColorSpace.SRGB)
        return rgb[0]*(1-r.photoCoverage(s,p.first,p.second)!!)
    }
    @Test fun reproducesOldWhiteFringeAndFixesItWithoutRemovingWhitePerforation(){
        assertEquals(legacy.spec.sha256,current.spec.sha256);assertEquals(legacy.assetPath,current.assetPath)
        val old=raster(legacy);val fixed=raster(current)
        for(portrait in listOf(false,true))for(short in listOf(128,766,879)){
            val w=if(portrait)short else (short*1046.0/766).toInt();val h=if(portrait)(short*1046.0/766).toInt()else short
            val before=snapshot(legacy,w,h,portrait);val after=snapshot(current,w,h,portrait)
            for(y in listOf(.5,200.5,300.5,600.5,700.5,800.5,925.5)){
                assertTrue("Old reproduction $portrait/$short/$y",blackComposite(old,before,249.5,y)>.02)
                assertTrue("No white matte on image feather $portrait/$short/$y",blackComposite(fixed,after,249.5,y)<.012)
            }
            val g=after.layout!!
            for(x in listOf(247.5,248.5,249.5)){
                for(y in listOf(378.5,450.5,537.5))assertFalse("True hole body must remain matte",g.imageWindow(x,y,true))
                for(y in listOf(355.5,561.5))assertTrue("Window feather outside rounded hole",g.imageWindow(x,y,true))
            }
            val hole=point(after,166.5,450.5);val rgb=FloatArray(3);fixed.sample(after,hole.first,hole.second,rgb)
            assertEquals(0.0,fixed.photoCoverage(after,hole.first,hole.second)!!,0.0)
            rgb.forEach{assertEquals(1f,it,1e-6f)}
            assertEquals(blackComposite(old,before,200.5,700.5),blackComposite(fixed,after,200.5,700.5),0.0)
        }
    }
    @Test fun legacyQueueVersionRemainsValidWhileNewCaptureChoosesFixedVersion(){
        val app=RuntimeEnvironment.getApplication();BorderCatalog.initialize(app.assets)
        val root=Files.createTempDirectory("trix-edge-queues").toFile()
        try{
            val p=EditorState(filmStock="kodak_trix",outputRoute="direct",filmBorder=true)
            val now=BorderCatalog.snapshot(p.frameStock,600,400,600,400,p.composition,true,"direct")
            assertEquals(BwBorderDefinitions.SUPER8_EDGE_VERSION,now.assetVersion)
            val old=now.copy(assetVersion=legacy.spec.version)
            for(s in listOf(old,now)){
                BorderCatalog.verifySnapshot(s)
                ExportJobStore(root).update(ExportJob(ExportJobStore.newId(),if(s===old)1 else 2,p,600,400,dev.filmroom.io.ExportFormat.PNG,95,state=ExportJobState.PAUSED,border=s))
            }
            assertEquals(setOf(old.assetVersion,now.assetVersion),ExportJobStore(root).jobs.value.map{it.border!!.assetVersion}.toSet())
        }finally{root.deleteRecursively()}
    }
    @Test fun darkImageExportAndPreviewAreIdenticalAndProduceBeforeAfterEvidence(){
        val dir=File("build/ui-host-renders/trix-white-edge").apply{mkdirs()}
        for(portrait in listOf(false,true)){
            val w=if(portrait)400 else 600;val h=if(portrait)600 else 400
            val photo=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff080808.toInt())}
            for(d in listOf(legacy,current)){
                val s=snapshot(d,w,h,portrait);val r=raster(d);val preview=BorderPreview.compose(photo,s,r)
                val input=File(dir,"input.rgb");val output=File(dir,"output.rgb")
                val value=dev.filmroom.io.LinearRgb.srgb8ToLinear[8]
                val bytes=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN);repeat(w*h*3){bytes.putFloat(value)};input.writeBytes(bytes.array())
                BorderCompositor.composite(input,output,s,r,OutputColorSpace.SRGB)
                val floats=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for(y in 0 until preview.height)for(x in 0 until preview.width){val pixel=preview.getPixel(x,y);for(c in 0..2)assertEquals((pixel ushr(16-c*8))and 255,OutputColorSpace.SRGB.encode8(floats.get()))}
                File(dir,"${if(d===legacy)"legacy" else d.spec.version}-${if(portrait)"portrait" else "landscape"}.png").outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)}
                preview.recycle();input.delete();output.delete()
            }
            photo.recycle()
        }
    }
}
