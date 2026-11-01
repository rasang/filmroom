package dev.filmroom.export

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import dev.filmroom.lifecycle.StartupRecoveryGate
import dev.filmroom.lifecycle.PrivateWorkRecovery
import dev.filmroom.io.*
import dev.filmroom.model.EditorState
import dev.filmroom.engine.*
import dev.filmroom.DownloadsExportHostTest
import android.provider.MediaStore
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ExportServiceLifecycleHostTest {
    private fun scope(service:ExportService)=ExportService::class.java.getDeclaredField("scope").apply{isAccessible=true}.get(service) as CoroutineScope
    @Test fun explicitStartAfterPauseBeforeDestroyGetsFreshLiveSession() {
        val controller=Robolectric.buildService(ExportService::class.java).create()
        val service=controller.get();val original=scope(service)
        try {
            service.onStartCommand(Intent(service,ExportService::class.java).setAction(ExportService.ACTION_PAUSE),0,1)
            assertFalse(original.isActive)
            service.onStartCommand(Intent(service,ExportService::class.java),0,2)
            val renewed=scope(service)
            assertNotSame(original,renewed);assertTrue(renewed.isActive)
            assertEquals(2,ExportService::class.java.getDeclaredField("latestStartId").apply{isAccessible=true}.getInt(service))
        } finally {controller.destroy();shadowOf(Looper.getMainLooper()).idle()}
    }
    @Test fun intakeIsWorkEvenBeforeReadyJobAppears() {
        val app=ApplicationProvider.getApplicationContext<Application>();val queue=ExportQueue(app)
        val lease=ExportQueue.beginIntake(app)
        try {assertTrue(queue.hasPendingWorkOrIntakes)}finally{lease.close()}
    }
    @Test fun failedStartupGateStopsServiceOnceDespiteIntakeThenOnlyExplicitReadyStartRuns()=runBlocking { verifyFailedGateRecovery(false) }
    @Test fun readyExplicitStartBeforeOldBlockedFinallyIsNotStoppedByOldRequest()=runBlocking { verifyFailedGateRecovery(true) }
    private suspend fun verifyFailedGateRecovery(restartBeforeOldFinally:Boolean) {
        val app=ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir,"export-jobs").deleteRecursively()
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        val provider=DownloadsExportHostTest.Provider().apply{attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY;exported=false})}
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,provider)
        app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
        val gateScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val cleanupAttempts=AtomicInteger(0)
        val gate=StartupRecoveryGate(gateScope){PrivateWorkRecovery.Report(0,if(cleanupAttempts.incrementAndGet()==1)1 else 0)}
        assertTrue(gate.awaitAttempt() is StartupRecoveryGate.State.Failed)
        val renderCalls=AtomicInteger(0)
        val processor=object:ExportProcessing {
            override suspend fun plan(width:Int,height:Int,parameters:EditorState)=NativeExportPlan(width,height,width.toLong()*height*12,width.toLong()*height*12,0,width.toLong()*height*24)
            override suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit):File {
                renderCalls.incrementAndGet();return File(workspace,"test-result.rgb").apply{input.copyTo(this,overwrite=true)}
            }
            override fun cancel(){}
        }
        val queue=ExportQueue(app,processor,startupGate=gate)
        val original=File(app.cacheDir,"gate-source.image").apply{writeText("source")}
        val raw=File(app.cacheDir,"gate-source.rgb").apply{writeBytes(ByteArray(48))}
        val item=queue.enqueue(PhotoSource(original,"private","image/dng",2,2,6,linearFile=raw),EditorState(),ExportFormat.PNG,null,95)
        // Model a durable pre-existing pending row when startup cleanup subsequently failed.
        queue.store.update(item.copy(state=ExportJobState.PENDING))
        val controller=Robolectric.buildService(ExportService::class.java).create()
        val service=controller.get();val runs=AtomicInteger(0)
        service.queueFactory={runs.incrementAndGet();queue}
        val lease=ExportQueue.beginIntake(app)
        suspend fun awaitWorkerStopped()=withTimeout(10000){
            while(true){
                shadowOf(Looper.getMainLooper()).idle()
                val worker=ExportService::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(service) as? Job
                if(worker!=null && worker.isCompleted)break
                delay(10)
            }
        }
        try {
            service.onStartCommand(Intent(service,ExportService::class.java),0,1)
            if(restartBeforeOldFinally) {
                // Do not idle Main: hold the old blocked worker in its Main finally.
                withTimeout(10000){while(queue.store.get(item.id)?.state!=ExportJobState.PAUSED)delay(10)}
                assertEquals(1,runs.get());assertEquals(0,renderCalls.get())
                lease.close();assertTrue(gate.retry());assertTrue(gate.awaitAttempt() is StartupRecoveryGate.State.Ready)
                queue.retry(item.id)
                service.onStartCommand(Intent(service,ExportService::class.java),0,2)
                awaitWorkerStopped()
                assertEquals(2,runs.get());assertEquals(1,renderCalls.get())
                assertEquals(ExportJobState.COMPLETED,queue.store.get(item.id)!!.state)
                return
            }
            awaitWorkerStopped()
            // Keep intake live: this was the old drained=true infinite restart trigger.
            repeat(5){shadowOf(Looper.getMainLooper()).idle();delay(10)}
            assertEquals(1,runs.get());assertEquals(0,renderCalls.get());assertEquals(1,cleanupAttempts.get())
            val paused=queue.store.get(item.id)!!
            assertEquals(ExportJobState.PAUSED,paused.state);assertTrue(paused.message!!.contains("重试清理"))
            assertTrue(File(queue.store.directory(item.id),"input.rgb").isFile)
            queue.retry(item.id)
            assertEquals(ExportJobState.PAUSED,queue.store.get(item.id)!!.state)
            lease.close()
            assertTrue(gate.retry());assertTrue(gate.awaitAttempt() is StartupRecoveryGate.State.Ready)
            shadowOf(Looper.getMainLooper()).idle();assertEquals(1,runs.get());assertEquals(0,renderCalls.get())
            queue.retry(item.id)
            service.onStartCommand(Intent(service,ExportService::class.java),0,2)
            awaitWorkerStopped()
            assertEquals(2,runs.get());assertEquals(1,renderCalls.get())
            assertEquals(ExportJobState.COMPLETED,queue.store.get(item.id)!!.state)
        } finally {lease.close();controller.destroy();gateScope.cancel();shadowOf(Looper.getMainLooper()).idle()}
    }

}
