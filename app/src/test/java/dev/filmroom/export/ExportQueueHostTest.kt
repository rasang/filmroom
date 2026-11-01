package dev.filmroom.export

import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.*
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import dev.filmroom.engine.*
import dev.filmroom.DownloadsExportHostTest
import android.provider.MediaStore
import org.robolectric.shadows.ShadowContentResolver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ExportQueueHostTest {
    private fun job(order:Long)=ExportJob(ExportJobStore.newId(),order,EditorState(),2,2,ExportFormat.PNG,95)
    private fun temp(block:(File)->Unit) {val dir=Files.createTempDirectory("queue-host").toFile();try{block(dir)}finally{dir.deleteRecursively()}}
    @Test fun fifoAndRetryOrderAreDurable()=temp {dir->
        val store=ExportJobStore(dir);val b=job(2);val a=job(1)
        store.update(b);store.update(a)
        assertEquals(a.id,store.next()!!.id)
        store.change(a.id){it.copy(state=ExportJobState.COMPLETED)}
        assertEquals(b.id,store.next()!!.id)
        val restarted=ExportJobStore(dir)
        assertEquals(ExportJobState.COMPLETED,restarted.get(a.id)!!.state)
        assertEquals(ExportJobState.PAUSED,restarted.get(b.id)!!.state)
        assertNull(restarted.next())
        restarted.change(b.id){it.copy(state=ExportJobState.RETRY)}
        assertEquals(b.id,restarted.next()!!.id)
    }
    @Test fun metadataAndExportInstantSurviveRestartAndLegacyJobsRemainReadable()=temp {dir->
        val store=ExportJobStore(dir)
        val metadata=PhotoMetadata.fromAttributes(mapOf("Make" to "Original Camera","LensModel" to "Original Lens","DateTimeOriginal" to "2001:02:03 04:05:06","OffsetTimeOriginal" to "+05:30")).toJson()
        val a=job(1).copy(photoMetadata=metadata,exportMillis=1780000000123L,metadataWarning="部分元信息未能保留")
        store.update(a)
        val b=job(2);store.update(b)
        val restart=ExportJobStore(dir)
        assertEquals(metadata,restart.get(a.id)!!.photoMetadata)
        assertEquals(1780000000123L,restart.get(a.id)!!.exportMillis)
        assertEquals(a.metadataWarning,restart.get(a.id)!!.metadataWarning)
        assertNull(restart.get(b.id)!!.photoMetadata)
        assertNull(restart.get(b.id)!!.exportMillis)
    }
    @Test fun processRestartNeverAutoRunsInterruptedJobs()=temp {dir->
        val store=ExportJobStore(dir)
        val a=job(1).copy(state=ExportJobState.RUNNING);store.update(a)
        File(store.directory(a.id),"checkpoint").mkdir()
        File(store.directory(a.id),"checkpoint/plane").writeText("durable")
        val restarted=ExportJobStore(dir)
        assertEquals(ExportJobState.PAUSED,restarted.get(a.id)!!.state)
        assertEquals("durable",File(restarted.directory(a.id),"checkpoint/plane").readText())
        assertNull(restarted.next())
    }
    @Test fun failedJournalWriteDoesNotPublishInMemoryState()=temp {dir->
        val store=ExportJobStore(dir);val a=job(1);store.update(a)
        val manifest=File(store.directory(a.id),"manifest.json");assertTrue(manifest.delete());assertTrue(manifest.mkdir())
        File(manifest,"block").writeText("block")
        assertThrows(Exception::class.java){store.update(a.copy(state=ExportJobState.COMPLETED))}
        assertEquals(ExportJobState.PENDING,store.get(a.id)!!.state)
    }
    @Test fun rawInputSnapshotOutlivesEditorDeletionAndParameters()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        File(context.filesDir,"export-jobs").deleteRecursively()
        ShadowStatFs.registerStats(context.filesDir.path,4096,1_000_000,1_000_000)
        val encoded=File(context.cacheDir,"capture-source.image").apply{writeText("source")}
        val raw=File(context.cacheDir,"capture-raw.rgb").apply{writeBytes(ByteArray(48){it.toByte()})}
        val source=PhotoSource(encoded,"private","image/dng",2,2,6,linearFile=raw)
        val queue=ExportQueue(context)
        val original=EditorState().copy(filmExposure=1.25f)
        val captured=queue.enqueue(source,original,ExportFormat.PNG,null,93)
        encoded.delete();raw.delete()
        assertArrayEquals(ByteArray(48){it.toByte()},File(queue.store.directory(captured.id),"input.rgb").readBytes())
        assertEquals(original,captured.parameters);assertEquals(93,captured.quality)
        queue.cancel(captured.id)
        assertEquals(ExportJobState.CANCELLED,queue.store.get(captured.id)!!.state)
        assertFalse(File(queue.store.directory(captured.id),"input.rgb").exists())
    }
    @Test fun foregroundTypeMatchesPlatformWithoutWorkManagerQuota() {
        listOf(29,30,31,32,33,34).forEach{assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,ExportService.foregroundType(it))}
        listOf(35,36,37).forEach{assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,ExportService.foregroundType(it))}
    }
    private class Processor(private val nonCooperative:Boolean=false,private val afterRender:(File)->Unit={}):ExportProcessing {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val order=mutableListOf<Float>();var active=0;var peak=0
        @Volatile var cancelCalls=0
        override fun cancel(){cancelCalls++;release.complete(Unit)}
        override suspend fun plan(width:Int,height:Int,parameters:EditorState)=NativeExportPlan(width,height,width.toLong()*height*12,width.toLong()*height*12,0,width.toLong()*height*24)
        override suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit):File {
            active++;peak=maxOf(peak,active);order+=parameters.filmExposure;started.complete(Unit)
            try {if(nonCooperative)withContext(NonCancellable){release.await()}else release.await();currentCoroutineContext().ensureActive();return File(workspace,"test-output.rgb").apply{input.copyTo(this,overwrite=true);afterRender(workspace)}}finally{active--}
        }
    }
    private fun preparedContext():Application {
        val app=ApplicationProvider.getApplicationContext<Application>();File(app.filesDir,"export-jobs").deleteRecursively()
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        val provider=DownloadsExportHostTest.Provider();provider.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY;exported=false})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,provider)
        app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
        return app
    }
    private fun source(app:Application):PhotoSource {
        val original=File(app.cacheDir,"queue-source.image").apply{writeText("source")}
        val raw=File(app.cacheDir,"queue-source.rgb").apply{writeBytes(ByteArray(48))}
        return PhotoSource(original,"private","image/dng",2,2,6,linearFile=raw)
    }
    @Test fun linearSourceMetadataSurvivesDeletionRestartAndRetry()=runBlocking {
        val app=preparedContext();val original=source(app)
        val metadata=PhotoMetadata.fromAttributes(mapOf("Make" to "RAW camera","LensModel" to "Lens","DateTimeOriginal" to "2001:02:03 04:05:06","OffsetTimeOriginal" to "+05:30","GPSLatitude" to "30/1,20/1,1/2"))
        val photo=PhotoSource(original.localFile,"private","image/dng",2,2,6,linearFile=original.linearFile,photoMetadata=metadata)
        val queue=ExportQueue(app,Processor());val item=queue.enqueue(photo,EditorState(),ExportFormat.PNG,null,95)
        photo.localFile.delete();photo.linearFile!!.delete()
        queue.store.change(item.id){it.copy(state=ExportJobState.FAILED,exportMillis=1780000000123L,metadataWarning="previous injection failed")}
        val processor=Processor().apply{release.complete(Unit)}
        val restarted=ExportQueue(app,processor)
        assertEquals(metadata.toJson(),restarted.store.get(item.id)!!.photoMetadata)
        val retryStart=System.currentTimeMillis()
        restarted.retry(item.id);restarted.runPending{}
        val done=restarted.store.get(item.id)!!
        assertEquals(ExportJobState.COMPLETED,done.state)
        assertEquals(metadata.toJson(),done.photoMetadata)
        assertTrue(checkNotNull(done.exportMillis) in retryStart..System.currentTimeMillis())
        assertNull(done.metadataWarning)
        val uri=done.mediaUri
        restarted.retry(item.id);restarted.runPending{}
        assertEquals(uri,restarted.store.get(item.id)!!.mediaUri)
        assertEquals(1,processor.order.size)
    }
    @Test fun metadataWarningAndProgressObserverFailureCannotAbortValidExport()=runBlocking {
        val app=preparedContext();val processor=Processor().apply{release.complete(Unit)}
        val queue=ExportQueue(app,processor);val item=queue.enqueue(source(app),EditorState(),ExportFormat.PNG,null,95)
        queue.runPending{throw IllegalStateException("synthetic observer failure")}
        val done=queue.store.get(item.id)!!
        assertEquals(ExportJobState.COMPLETED,done.state)
        assertNotNull(done.metadataWarning)
        assertTrue(done.message!!.contains(done.metadataWarning!!))
    }
    @Test fun addingWhileProcessingDoesNotBlockAndJobsRunExactlyOneAtATime()=runBlocking {
        val app=preparedContext();val processor=Processor();val queue=ExportQueue(app,processor);val source=source(app)
        val first=queue.enqueue(source,EditorState().copy(filmExposure=1f),ExportFormat.PNG,null,95)
        val running=launch(Dispatchers.Default){queue.runPending{}}
        withTimeout(5000){processor.started.await()}
        val second=withTimeout(5000){queue.enqueue(source,EditorState().copy(filmExposure=2f),ExportFormat.PNG,null,95)}
        assertEquals(ExportJobState.PENDING,queue.store.get(second.id)!!.state)
        processor.release.complete(Unit);withTimeout(5000){running.join()}
        assertEquals(listOf(1f,2f),processor.order);assertEquals(1,processor.peak)
        assertEquals(ExportJobState.COMPLETED,queue.store.get(first.id)!!.state)
        assertEquals(ExportJobState.COMPLETED,queue.store.get(second.id)!!.state)
        assertFalse(File(queue.store.directory(first.id),"input.rgb").exists())
    }
    @Test fun cancellingRunningJobWaitsForExitAndContinuesNextJob()=runBlocking {
        val app=preparedContext();val processor=Processor();val queue=ExportQueue(app,processor);val source=source(app)
        val first=queue.enqueue(source,EditorState().copy(filmExposure=1f),ExportFormat.PNG,null,95)
        val second=queue.enqueue(source,EditorState().copy(filmExposure=2f),ExportFormat.PNG,null,95)
        val running=launch(Dispatchers.Default){queue.runPending{}}
        withTimeout(5000){processor.started.await()}
        queue.cancel(first.id);withTimeout(5000){running.join()}
        assertEquals(ExportJobState.CANCELLED,queue.store.get(first.id)!!.state)
        assertEquals(ExportJobState.COMPLETED,queue.store.get(second.id)!!.state)
        assertEquals(1,processor.peak)
    }

    @Test fun cancelDuringCaptureStopsWriterBeforeCleanupAndNeverResurrects()=runBlocking {
        val app=preparedContext();val accepted=CompletableDeferred<ExportJob>()
        val queue=ExportQueue(app,Processor(),beforeCapture={accepted.complete(it);awaitCancellation()})
        val caller=launch{queue.enqueue(source(app),EditorState(),ExportFormat.PNG,null,95)}
        val draft=withTimeout(5000){accepted.await()}
        withTimeout(5000){queue.cancel(draft.id);caller.join()}
        assertEquals(ExportJobState.CANCELLED,queue.store.get(draft.id)!!.state)
        assertFalse(File(queue.store.directory(draft.id),"input.rgb").exists())
        assertNull(queue.store.next())
    }
    @Test fun destroyedEditorAwaitDoesNotCancelOwnedCaptureOrOpenedSource()=runBlocking {
        val app=preparedContext();val accepted=CompletableDeferred<ExportJob>();val release=CompletableDeferred<Unit>()
        val queue=ExportQueue(app,Processor(),beforeCapture={accepted.complete(it);release.await()})
        val photo=source(app)
        val caller=launch{queue.enqueue(photo,EditorState(),ExportFormat.PNG,null,95)}
        val draft=withTimeout(5000){accepted.await()};caller.cancelAndJoin()
        photo.localFile.delete();photo.linearFile!!.delete()
        release.complete(Unit)
        val ready=withTimeout(5000){queue.jobs.first{list->list.any{it.id==draft.id && it.inputReady}}}.first{it.id==draft.id}
        assertEquals(ExportJobState.PENDING,ready.state)
        assertArrayEquals(ByteArray(48),File(queue.store.directory(draft.id),"input.rgb").readBytes())
        queue.cancel(draft.id)
    }
    @Test fun pauseDuringCapturePublishesPausedAndUnrelatedRunCannotStartIt()=runBlocking {
        val app=preparedContext();val accepted=CompletableDeferred<ExportJob>();val release=CompletableDeferred<Unit>();val processor=Processor()
        val queue=ExportQueue(app,processor,beforeCapture={accepted.complete(it);release.await()})
        val caller=async{queue.enqueue(source(app),EditorState(),ExportFormat.PNG,null,95)}
        withTimeout(5000){accepted.await()}
        val request=queue.requestPause();queue.applyPause(request);release.complete(Unit)
        val ready=withTimeout(5000){caller.await()}
        assertEquals(ExportJobState.PAUSED,ready.state)
        queue.runPending{}
        assertTrue(processor.order.isEmpty())
        queue.cancel(ready.id)
    }
    @Test fun twoFailedJournalWritesLeaveRetryableMemoryStateNotRunningOrphan()=runBlocking {
        val app=preparedContext();var failOnce=true
        val processor=Processor{dir->if(failOnce){failOnce=false;File(dir,"manifest.json.new").mkdir();File(dir,"manifest.json.new/block").writeText("full")}}
        processor.release.complete(Unit)
        val queue=ExportQueue(app,processor);val item=queue.enqueue(source(app),EditorState(),ExportFormat.PNG,null,95)
        try{queue.runPending{};fail("journal failure expected")}catch(_:java.io.IOException){}catch(_:IllegalStateException){}
        assertEquals(ExportJobState.PAUSED,queue.store.get(item.id)!!.state)
        File(queue.store.directory(item.id),"manifest.json.new").deleteRecursively()
        queue.retry(item.id);queue.runPending{}
        assertEquals(ExportJobState.COMPLETED,queue.store.get(item.id)!!.state)
    }
    @Test fun cancellingServiceScopeSignalsNativeBeforeWaitingAndRetainsResumeInput()=runBlocking {
        val app=preparedContext();val processor=Processor(nonCooperative=true);val queue=ExportQueue(app,processor)
        val item=queue.enqueue(source(app),EditorState(),ExportFormat.PNG,null,95)
        val service=launch(Dispatchers.Default){queue.runPending{}}
        withTimeout(5000){processor.started.await()};withTimeout(5000){service.cancelAndJoin()}
        assertTrue(processor.cancelCalls>0)
        assertEquals(ExportJobState.PAUSED,queue.store.get(item.id)!!.state)
        assertTrue(File(queue.store.directory(item.id),"input.rgb").isFile)
        queue.cancel(item.id)
    }

    @Test fun restartAfterMediaPublicationNeverRendersOrInsertsAgain()=runBlocking {
        val app=preparedContext();val provider=DownloadsExportHostTest.Provider()
        provider.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY;exported=false})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,provider)
        val processor=Processor();val first=ExportQueue(app,processor)
        val item=first.enqueue(source(app),EditorState(),ExportFormat.PNG,null,95)
        val published=provider.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),android.content.ContentValues().apply{
            put(MediaStore.MediaColumns.IS_PENDING,0);put(MediaStore.MediaColumns.DISPLAY_NAME,"saved.png");put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
        })
        first.store.update(item.copy(state=ExportJobState.RUNNING,mediaUri=published.toString()))
        File(first.store.directory(item.id),"checkpoint").mkdir();File(first.store.directory(item.id),"checkpoint/plane").writeText("old")
        val restarted=ExportQueue(app,processor)
        assertEquals(ExportJobState.PAUSED,restarted.store.get(item.id)!!.state)
        restarted.retry(item.id);restarted.runPending{}
        assertTrue(processor.order.isEmpty());assertEquals(1,provider.rows.size);assertEquals(1,provider.next)
        assertEquals(ExportJobState.COMPLETED,restarted.store.get(item.id)!!.state)
        assertFalse(File(restarted.store.directory(item.id),"checkpoint").exists())
        assertFalse(File(restarted.store.directory(item.id),"input.rgb").exists())
    }
    @Test fun mismatchedManifestIdCannotAliasAnotherJobsDirectory()=temp {dir->
        val store=ExportJobStore(dir);val a=job(1);val b=job(2);store.update(a);store.update(b)
        val manifest=File(store.directory(b.id),"manifest.json");manifest.writeText(manifest.readText().replace(b.id,a.id))
        val restarted=ExportJobStore(dir)
        assertEquals(setOf(a.id,b.id),restarted.jobs.value.map{it.id}.toSet())
        assertFalse(restarted.get(b.id)!!.inputReady)
        assertEquals(ExportJobState.FAILED,restarted.get(b.id)!!.state)
    }

}
