package dev.filmroom.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.engine.EngineProgress
import dev.filmroom.engine.NativeExportPlan
import dev.filmroom.io.ExportFormat
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/** Real intake, no renderer: guards that ordinary/non-RAW jobs also capture a private source. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=Application::class, shadows=[FileSourceImageDecoderShadow::class])
class SourcePreviewQueueHostTest {
    private fun setup():Pair<Application,ExportQueue> {
        val app=ApplicationProvider.getApplicationContext<Application>()
        dev.filmroom.i18n.L10n.initialize(app)
        File(app.filesDir,"export-jobs").deleteRecursively()
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val processor=object:ExportProcessing {
            override suspend fun plan(width:Int,height:Int,parameters:EditorState):NativeExportPlan=error("No render in intake test")
            override suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit):File=error("No render in intake test")
            override fun cancel(){}
        }
        return app to ExportQueue(app,processor)
    }
    @Test fun jpegIntakePersistsSourceEvenWithoutThumbnail()=runBlocking {
        val (app,queue)=setup()
        val input=File(app.cacheDir,"queue-source.jpg")
        val bitmap=Bitmap.createBitmap(48,24,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            input.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it))}
        } finally {bitmap.recycle()}
        val source=PhotoSource(input,"photo","image/jpeg",48,24,input.length()).apply{rememberOriginalUri("content://test/jpeg")}
        val job=queue.enqueue(source,EditorState(),ExportFormat.JPEG,null,95)
        assertEquals("ENCODED",job.inputKind);assertTrue(job.inputReady)
        val cached=RawSourcePreviewStore.read(app,job,2560)!!
        try {assertEquals(48,cached.width);assertEquals(24,cached.height);assertTrue(Color.red(cached.getPixel(5,5))>220)}
        finally {cached.recycle()}
        assertTrue(input.isFile)
    }
    @Test fun linearNonRawHeicIntakeAlsoPersistsSource()=runBlocking {
        val (app,queue)=setup()
        val input=File(app.cacheDir,"queue-source.heic").apply{writeText("already decoded container")}
        val linear=File(app.cacheDir,"queue-source.rgb").apply{writeBytes(ByteArray(2*2*12))}
        val source=PhotoSource(input,"photo","image/heic",2,2,input.length(),linearFile=linear).apply{rememberOriginalUri("content://test/heic")}
        val job=queue.enqueue(source,EditorState(),ExportFormat.JPEG,null,95)
        val cached=RawSourcePreviewStore.read(app,job,2560)!!
        try {assertEquals(2,cached.width);assertColorIsBlack(cached.getPixel(0,0))}
        finally {cached.recycle()}
        assertTrue(linear.isFile)
    }
    private fun assertColorIsBlack(pixel:Int) {
        assertTrue(Color.red(pixel)<10);assertTrue(Color.green(pixel)<10);assertTrue(Color.blue(pixel)<10)
    }
    @Test fun optionalPreviewDecodeFailureDoesNotFailIntake()=runBlocking {
        val (app,queue)=setup()
        val input=File(app.cacheDir,"queue-bad-source.jpg").apply{writeText("invalid encoded preview")}
        val source=PhotoSource(input,"photo","image/jpeg",48,24,input.length()).apply{rememberOriginalUri("content://test/bad")}
        val job=queue.enqueue(source,EditorState(),ExportFormat.JPEG,null,95)
        assertTrue(job.inputReady)
        assertEquals(ExportJobState.PENDING,job.state)
        assertNull(RawSourcePreviewStore.read(app,job,2560))
        assertTrue(File(queue.store.directory(job.id),"source.image").isFile)
    }
}
