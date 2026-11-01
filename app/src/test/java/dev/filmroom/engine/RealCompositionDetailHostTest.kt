package dev.filmroom.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import dev.filmroom.border.*
import dev.filmroom.crop.*
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.*
import dev.filmroom.roi.RealEditorDetailProvider
import dev.filmroom.ui.PhotoView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in actual JNI; execute in its own JVM, never claim a device/GPU or authentic-asset gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealCompositionDetailHostTest {
    private fun await(done:()->Boolean){repeat(2000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(10)};error("Real composition ROI timed out")}
    @Test fun repeatedCompositionAndModeChangesReuseRealScienceContext(){
        assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        val data=System.getenv("FILMROOM_DETAIL_DATA") ?: error("data required")
        val root=Files.createTempDirectory("real-composition-detail").toFile().canonicalFile
        val view=PhotoView(RuntimeEnvironment.getApplication())
        val creates=AtomicInteger();val builds=AtomicInteger();val destroys=AtomicInteger()
        val api=object:DetailNativeApi by NativeDetailBridge {
            override fun nativeCreate(canonicalPrivateAppRoot:String):String {creates.incrementAndGet();return NativeDetailBridge.nativeCreate(canonicalPrivateAppRoot)}
            override fun nativeBuild(sessionId:Long,operationId:Long,sourceRelative:String,requestJson:String):String {builds.incrementAndGet();return NativeDetailBridge.nativeBuild(sessionId,operationId,sourceRelative,requestJson)}
            override fun nativeDestroy(sessionId:Long):String {val result=NativeDetailBridge.nativeDestroy(sessionId);destroys.incrementAndGet();return result}
        }
        val ownedBitmaps=mutableListOf<Bitmap>()
        try {
            File(data).copyRecursively(File(root,"files/engine-data"))
            val original=File(root,"original.dng").apply{writeText("immutable source marker")}
            val w=513;val h=257
            val linear=File(root,"source.f32le")
            val values=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(w*h){i->values.putFloat(.1f+(i%w)/1024f);values.putFloat(.3f);values.putFloat(.2f)}
            linear.writeBytes(values.array())
            val source=PhotoSource(original,"source.dng","image/x-adobe-dng",w,h,original.length(),linearFile=linear)
            view.configureDetail(RealEditorDetailProvider(OwnedDetailProvider(root,api,{NativeMemorySnapshot(8L*1024*1024*1024,0,false)})))
            view.layout(0,0,400,300)
            val processed=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xffff00ff.toInt())}.also{ownedBitmaps+=it}
            val raster=object:BorderRaster {
                override val stock="fixture_only";override val version="real-roi-authored-1"
                override val railRatio=.2;override val endRatio=.02;override val residentBytes=3L;override val featherTotalAt3072=4.0
                override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb[0]=.1f;rgb[1]=.1f;rgb[2]=.1f}
            }
            var crop=CompositionTransform(zoom=4.0)
            var params=EditorState(filmBorder=true,composition=crop,grain=0f,halation=0f,scatterAmount=0f,dirAmount=0f,grainBlur=0f,grainDyeCloud=0f)
            fun present(editing:Boolean){
                val snapshot=BorderSnapshot.fixed135(raster.stock,raster.version,processed.width,processed.height,w,h,crop)
                view.bitmap=BorderPreview.compose(processed,snapshot,raster).also{ownedBitmaps+=it}
                view.bindDetail(source,params.copy(composition=crop),true,presentedBorder=true)
                val overlay=InteractiveBorderPreview.create(snapshot,raster).also{ownedBitmaps+=it}
                view.bindComposition(snapshot,processed,crop,editing,overlay){next,_->crop=next}
            }
            present(true);await{view.detailCacheBytes>0}
            val firstKey=view.detailKey
            assertEquals(1,creates.get());assertEquals(1,builds.get());assertEquals(0,destroys.get())
            repeat(10){i->
                params=params.copy(negativeWhiteHoles=i%2==0)
                view.pan(if(i%2==0)4.0 else -4.0,1.0);view.pinch(if(i%2==0)1.03 else 1/1.03,200.0,150.0)
                view.finishCompositionGesture();present(true);present(false);present(true)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            }
            await{view.detailCacheBytes>0}
            assertEquals(firstKey,view.detailKey)
            assertEquals("Pan/pinch/mode/white-hole preference must keep JNI context",1,creates.get());assertEquals(1,builds.get());assertEquals(0,destroys.get())
            // Full-source FREE editor temporarily has effective zoom1. Keep the real same-science
            // native owner through confirm/cancel, then explicit135 direction changes.
            var plain=CropSettings(CropMode.SQUARE,CompositionTransform(zoom=4.0))
            fun presentPlain(settings:CropSettings,editing:Boolean){
                plain=settings
                val snapshot=CropSnapshot.create(processed.width,processed.height,w,h,settings)
                view.bitmap=CropPreview.compose(processed,snapshot).also{ownedBitmaps+=it}
                view.bindDetail(source,params.copy(filmBorder=false,crop=settings),true,presentedBorder=true)
                view.bindCrop(snapshot,processed,settings,editing){next,_->plain=plain.copy(transform=next)}
            }
            repeat(4){i->
                val locked=CropSettings(CropMode.SQUARE,CompositionTransform(zoom=4.0))
                presentPlain(locked,false);await{view.detailCacheBytes>0}
                presentPlain(CropSettings(CropMode.FREE,locked.effectiveTransform(w,h)),true)
                assertEquals(1.0,view.compositionDetailZoom(),.0001)
                view.pan(5.0,0.0)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
                assertEquals(firstKey,view.detailKey)
                assertEquals("FREE editor must retain JNI ownership",1,creates.get());assertEquals(1,builds.get());assertEquals(0,destroys.get())
                if(i%2==0){view.finishCompositionGesture();presentPlain(plain,false)}
                else{view.discardCompositionGesture();presentPlain(locked,false)}
                await{view.detailCacheBytes>0}
                crop=CompositionTransform(zoom=4.0,targetAspect=if(i%2==0)1.5 else 2.0/3)
                present(true);await{view.detailCacheBytes>0}
                assertEquals(firstKey,view.detailKey);assertEquals(1,creates.get());assertEquals(1,builds.get());assertEquals(0,destroys.get())
            }
            val drawn=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).also{ownedBitmaps+=it};view.draw(Canvas(drawn))
            assertNotEquals("Actual native detail covers the magenta authored underlay",0xffff00ff.toInt(),drawn.getPixel(200,150))
            params=params.copy(brightness=.1f);present(true)
            await{creates.get()==2 && builds.get()==2 && destroys.get()==1 && view.detailCacheBytes>0}
            assertNotEquals(firstKey,view.detailKey)
            params=params.copy(filmStock="kodak_portra_160");present(true)
            await{creates.get()==3 && builds.get()==3 && destroys.get()==2 && view.detailCacheBytes>0}
            assertEquals(3,creates.get());assertEquals(3,builds.get());assertEquals(2,destroys.get())
            view.close();await{destroys.get()==3 && File(root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty()}
            assertTrue(original.exists());assertEquals("immutable source marker",original.readText());assertEquals(w*h*12L,linear.length())
        }finally{view.close();await{destroys.get()==creates.get()};ownedBitmaps.distinct().forEach{if(!it.isRecycled)it.recycle()};root.deleteRecursively()}
    }
}
