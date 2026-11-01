package dev.filmroom

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.*
import dev.filmroom.i18n.L10n
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.*
import dev.filmroom.ui.PhotoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real PNG bytes -> production repository import/decode. Science is stubbed; queued RUNNING state is synthetic, not a native export. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=FilmroomApplication::class,qualifiers="en-rUS-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocaleImportRetentionHostTest {
    @org.junit.After fun restoreChineseFixture(){L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
    @org.junit.Before fun isolateFactory(){resetAndroidViewModelFactoryForHostTest()}
    private fun await(condition:()->Boolean){
        val deadline=System.nanoTime()+10_000_000_000L
        do {shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));if(condition())return;Thread.sleep(5)}while(System.nanoTime()<deadline)
        fail("Timed out waiting for import/presentation fixture")
    }
    private fun layout(a:MainActivity){val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0);root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)}
    private fun photo(a:MainActivity)=MainActivity::class.java.getDeclaredField("photo").apply{isAccessible=true}.get(a) as PhotoView
    @Test fun importedPngCropZoomGestureAndQueueSurviveLanguageChange(){
        val app=RuntimeEnvironment.getApplication() as FilmroomApplication
        app.getSharedPreferences("local-session",0).edit().clear().commit();L10n.choose(app,"zh")
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
        await{app.recoveryGate.state.value !is dev.filmroom.lifecycle.StartupRecoveryGate.State.Running}
        val input=File(app.cacheDir,"locale-import.png")
        Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff587b86.toInt());input.outputStream().use{compress(Bitmap.CompressFormat.PNG,100,it)};recycle()}
        val uri=Uri.parse("content://locale-import-test/photo.png")
        shadowOf(app.contentResolver).registerInputStream(uri,input.inputStream())
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val a=controller.get();val vm=ViewModelProvider(a)[EditorViewModel::class.java]
            assertSame(app,vm.getApplication<android.app.Application>())
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{vm.state.value.startupReady && (operation.get(vm) as? Job)?.isCompleted==true}
            vm.localDetailEnabled=false;photo(a).disableDetail()
            vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            vm.renderPreview={b,_,_,_->b.copy(Bitmap.Config.ARGB_8888,false)};vm.detailPreviewEdge={96}
            vm.importPhoto(uri)
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            val imported=assertNotNullSource(vm)
            assertEquals(96,imported.originalWidth);assertEquals(64,imported.originalHeight)
            assertTrue(imported.localFile.isFile);assertArrayEquals(input.readBytes(),imported.localFile.readBytes())
            @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
            val original=checkNotNull(flow.value.original)
            // JNI science is not part of this UI lifecycle test; admission and real crop composition remain active.
            flow.value=flow.value.copy(engineUnavailable=null)
            vm.parameters(flow.value.parameters.copy(brightness=1.25f,crop=CropSettings(CropMode.SQUARE,CompositionTransform(.45,.5,1.3))))
            await{flow.value.canExport};layout(a)
            photo(a).restoreViewerState(floatArrayOf(2f,.45f,.55f));val viewer=photo(a).viewerState()
            val compose=MainActivity::class.java.getDeclaredField("compositionButton").apply{isAccessible=true}.get(a) as android.widget.Button
            assertTrue(compose.isEnabled);compose.performClick();await{flow.value.compositionEditing};shadowOf(Looper.getMainLooper()).idle();layout(a)
            val view=photo(a);val time=android.os.SystemClock.uptimeMillis()
            fun touch(action:Int,x:Float){val e=MotionEvent.obtain(time,time+16,action,x,view.height/2f,0);view.onTouchEvent(e);e.recycle()}
            touch(MotionEvent.ACTION_DOWN,view.width/2f);touch(MotionEvent.ACTION_MOVE,view.width/2f+16f)
            val active=EditorViewModel::class.java.getDeclaredField("compositionGestureActive").apply{isAccessible=true}
            assertTrue("Fixture must start a real PhotoView drag",active.getBoolean(vm))
            controller.pause() // Leaving for Settings must commit the unfinished pointer gesture.
            assertFalse(active.getBoolean(vm));val parameters=flow.value.parameters
            val queue=ExportQueue.get(app)
            val job=ExportJob(ExportJobStore.newId(),1,EditorState(),96,64,ExportFormat.PNG,95,state=ExportJobState.RUNNING,progress=ExportDisplayProgress(5,3,10))
            queue.store.update(job)
            assertNull(shadowOf(app).nextStartedService)
            L10n.choose(app,"en");controller.recreate();layout(controller.get())
            assertSame(vm,ViewModelProvider(controller.get())[EditorViewModel::class.java])
            assertSame(imported,vm.state.value.photo);assertSame(original,vm.state.value.original)
            assertFalse(original.isRecycled);assertEquals(0xff587b86.toInt(),original.getPixel(0,0))
            assertEquals(parameters,vm.state.value.parameters);assertFalse(active.getBoolean(vm))
            assertArrayEquals(viewer,photo(controller.get()).viewerState(),.001f)
            assertSame(queue,ExportQueue.get(app));assertEquals(job,queue.store.get(job.id));assertNull(shadowOf(app).nextStartedService)
            assertEquals("en",controller.get().resources.configuration.locales[0].language)
            vm.cancelComposition()
        }
    }
    private fun assertNotNullSource(vm:EditorViewModel):dev.filmroom.io.PhotoSource {
        assertNotNull("Import failed: ${vm.state.value.message}",vm.state.value.photo)
        return vm.state.value.photo!!
    }
}
