package dev.filmroom.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
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

/** Actual PhotoView -> controller -> Kotlin provider -> JNI scientific renderer, host software Canvas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealLocalDetailPhotoViewHostTest {
    private fun await(test:()->Boolean){repeat(1500){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(test())return;Thread.sleep(10)};error("Timed out")}
    @Test fun actualViewDrawsRealOwnedCellsAboveLowResolutionUnderlayAndRetires(){
        assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        val data=System.getenv("FILMROOM_DETAIL_DATA") ?: error("data required")
        val root=Files.createTempDirectory("real-detail-view").toFile().canonicalFile
        val aliasParent=Files.createTempDirectory("real-detail-root-alias").toFile()
        val trustedAlias=File(aliasParent,"user-zero")
        Files.createSymbolicLink(trustedAlias.toPath(),root.toPath())
        val view=PhotoView(RuntimeEnvironment.getApplication())
        try {
            File(data).copyRecursively(File(root,"files/engine-data"))
            val original=File(root,"original.dng").apply{writeText("original marker")}
            val linear=File(root,"source.f32le")
            val w=513;val h=257
            val values=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(w*h){i->values.putFloat(.1f+(i%w)/1024f);values.putFloat(.3f);values.putFloat(.2f)}
            linear.writeBytes(values.array())
            val source=PhotoSource(File(trustedAlias,"original.dng"),"source.dng","image/x-adobe-dng",w,h,original.length(),linearFile=File(trustedAlias,"source.f32le"))
            val provider=OwnedDetailProvider(trustedAlias,NativeDetailBridge,{NativeMemorySnapshot(8L*1024*1024*1024,0,false)})
            view.configureDetail(RealEditorDetailProvider(provider));view.layout(0,0,400,300)
            val underlay=Bitmap.createBitmap(128,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xffff00ff.toInt())}
            view.bitmap=underlay
            val params=EditorState(grain=0f,halation=0f,scatterAmount=0f,dirAmount=0f,grainBlur=0f,grainDyeCloud=0f)
            view.bindDetail(source,params,true);view.pinch(3.0,200.0,150.0)
            await{view.detailCacheBytes>0}
            assertEquals(w,view.detailKey!!.virtualFullWidth);assertEquals(h,view.detailKey!!.virtualFullHeight)
            val rendered=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);view.draw(Canvas(rendered))
            assertNotEquals(0xffff00ff.toInt(),rendered.getPixel(200,150));assertSame(underlay,view.bitmap)
            File("build/ui-host-renders/local-detail-real-jni.png").apply{parentFile?.mkdirs()}.outputStream().use{rendered.compress(Bitmap.CompressFormat.PNG,100,it)}
            val key=view.detailKey;view.pan(-30.0,0.0);assertEquals(key,view.detailKey)
            view.setForeground(false);assertNull(view.detailKey)
            await{File(root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty()}
            assertTrue(original.exists());assertTrue(linear.exists());assertFalse(underlay.isRecycled)
        }finally{view.close();root.deleteRecursively();Files.deleteIfExists(trustedAlias.toPath());aliasParent.delete()}
    }
}
