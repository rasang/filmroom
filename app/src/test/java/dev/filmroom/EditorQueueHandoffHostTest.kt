package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.*
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class EditorQueueHandoffHostTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun productionDefaultStartsServiceAndPersistsIndependentSourceWithoutLegacyExport()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        val singleton=dev.filmroom.export.ExportQueue::class.java.getDeclaredField("instance").apply{isAccessible=true}
        val previous=singleton.get(null);singleton.set(null,null)
        var queue:dev.filmroom.export.ExportQueue?=null
        try {
            val vm=EditorViewModel(app)
            val mutable=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
            repeat(5){shadowOf(Looper.getMainLooper()).idle();delay(5)}
            withTimeout(10000){while(!mutable.value.startupReady || mutable.value.busy!=null){shadowOf(Looper.getMainLooper()).idle();delay(5)}}
            val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
            val file=File(app.cacheDir,"production-queue-source.png")
            file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            val source=PhotoSource(file,"test","image/png",2,2,file.length())
            val parameters=EditorState(grainRedScale=2f)
            mutable.value=EditorUiState(photo=source,original=bitmap,rendered=bitmap,parameters=parameters,renderedParameters=parameters)
            // Do not replace beginExportIntake or submitExport: this exercises the production default.
            vm.export(ExportFormat.PNG)
            vm.processingScreenLeft()
            assertFalse(vm.state.value.cancelling)
            withTimeout(10000){while(vm.state.value.busy!=null){shadowOf(Looper.getMainLooper()).idle();delay(5)}}
            assertTrue(vm.state.value.editable)
            val service=shadowOf(app).nextStartedService
            assertNotNull(service);assertEquals(dev.filmroom.export.ExportService::class.java.name,service.component!!.className)
            queue=withContext(Dispatchers.IO){dev.filmroom.export.ExportQueue.get(app)}
            val job=queue!!.jobs.value.last()
            assertTrue("${vm.state.value.message}; ${job.state}: ${job.message}",job.inputReady);assertEquals(dev.filmroom.export.ExportJobState.PENDING,job.state)
            assertEquals(parameters,job.parameters);assertNull("Default API export must retain original size",job.maxEdge)
            assertTrue(file.delete())
            assertTrue(File(queue!!.store.directory(job.id),"source.image").length()>0)
            mutable.value=mutable.value.copy(parameters=parameters.copy(grainRedScale=3f))
            assertEquals(2f,queue!!.store.get(job.id)!!.parameters.grainRedScale)
            withContext(Dispatchers.IO){queue!!.cancel(job.id)}
        } finally {singleton.set(null,previous)}
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun capturedParametersAreImmutableEditorUnlocksAndLeavingDoesNotCancel()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val vm=EditorViewModel(app)
        val mutable=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
        withTimeout(10000){while(!mutable.value.startupReady || mutable.value.busy!=null){shadowOf(Looper.getMainLooper()).idle();delay(5)}}
        // Drain startup cleanup continuation before installing a deterministic source.
        repeat(5){shadowOf(Looper.getMainLooper()).idle();delay(5)}
        val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        val source=PhotoSource(File(app.cacheDir,"source-test.jpg"),"test","image/jpeg",2,2,1)
        val params=EditorState(grainRedScale=2f)
        mutable.value=EditorUiState(photo=source,original=bitmap,rendered=bitmap,parameters=params,renderedParameters=params)
        var intakeStarted=false;var intakeClosed=false
        vm.beginExportIntake={intakeStarted=true;java.io.Closeable{intakeClosed=true}}
        val release=CompletableDeferred<Unit>();var calls=0;var captured:EditorState?=null
        vm.submitExport={s,p,_,_,_,thumb,onAccepted,onDurable->assertTrue(intakeStarted);assertSame(source,s);assertSame(bitmap,thumb);calls++;captured=p;onAccepted{};release.await();onDurable()}
        vm.exportOriginal(ExportFormat.PNG,outputColorSpace="Display P3")
        vm.exportOriginal(ExportFormat.PNG)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,calls);assertFalse(vm.state.value.editable)
        vm.processingScreenLeft();vm.cancelExportForSharing()
        assertFalse(vm.state.value.cancelling);assertFalse(intakeClosed)
        release.complete(Unit);shadowOf(Looper.getMainLooper()).idle()
        withTimeout(10000){while(vm.state.value.busy!=null){shadowOf(Looper.getMainLooper()).idle();delay(5)}}
        assertTrue(vm.state.value.editable);assertFalse(vm.state.value.exporting);assertTrue(intakeClosed)
        assertEquals("Display P3",captured!!.outputColorSpace);assertEquals(2f,captured!!.grainRedScale)
        assertEquals(params,vm.state.value.parameters)
        mutable.value=mutable.value.copy(parameters=params.copy(grainRedScale=3f))
        assertEquals(2f,captured!!.grainRedScale)
        vm.processingScreenLeft();assertTrue(vm.state.value.editable)
    }
}
