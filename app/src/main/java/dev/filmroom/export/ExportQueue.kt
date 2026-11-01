package dev.filmroom.export

import dev.filmroom.border.*
import dev.filmroom.i18n.UiMessage
import dev.filmroom.R
import dev.filmroom.crop.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import dev.filmroom.FilmroomApplication
import dev.filmroom.engine.*
import dev.filmroom.io.*
import dev.filmroom.lifecycle.StartupRecoveryGate
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.coroutines.sync.withLock

class ExportIntakeGeneration(val generation:Long):kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key:kotlin.coroutines.CoroutineContext.Key<ExportIntakeGeneration>
}

/** 进程级队列：编辑器只负责捕获输入，服务独占顺序执行器。 */
internal interface ExportProcessing {
    suspend fun plan(width:Int,height:Int,parameters:EditorState):NativeExportPlan
    suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit):File
    fun cancel()
}
private class NativeExportProcessing(context:Context):ExportProcessing {
    private val engine=FilmEngine(context)
    override suspend fun plan(width:Int,height:Int,parameters:EditorState)=engine.planExport(width,height,parameters)
    override suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit)=engine.exportSpool(input,width,height,parameters,workspace=workspace,progressOnMain=false,onProgress=onProgress)
    override fun cancel()=engine.cancel()
}
enum class ExportRunOutcome { DRAINED, BLOCKED_STARTUP_RECOVERY }
class ExportQueue internal constructor(private val context:Context,private val processing:ExportProcessing=NativeExportProcessing(context),private val startupGate:StartupRecoveryGate?=(context.applicationContext as? FilmroomApplication)?.recoveryGate,private val beforeCapture:suspend (ExportJob)->Unit={},private val borderProvider:BorderProvider=BorderCatalog) {
    val store=ExportJobStore(File(context.filesDir,"export-jobs"))
    val jobs=store.jobs
    val startupRecoveryReady get()=startupGate==null || startupGate.state.value is StartupRecoveryGate.State.Ready
    val hasPendingWorkOrIntakes get()=intakes.get()>0 || jobs.value.any{it.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY)}
    init {val app=context.applicationContext;BorderCatalog.initialize(app.assets){NativeWorkReservations.snapshot(app)};jobs.value.filter{it.terminal}.forEach{cleanup(it.id)}}
    private val repository=PhotoRepository(context)
    private val activeLock=Any()
    @Volatile private var activeId:String?=null
    @Volatile private var activeTask:Job?=null
    private val storageLock=kotlinx.coroutines.sync.Mutex()
    private val reservedBytes=java.util.concurrent.atomic.AtomicLong(0)
    private val runLock=kotlinx.coroutines.sync.Mutex()

    private val captures=java.util.concurrent.ConcurrentHashMap<String,Deferred<ExportJob>>()
    private val captureScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    suspend fun enqueue(source:PhotoSource,parameters:EditorState,format:ExportFormat,maxEdge:Int?,quality:Int,thumbnail:Bitmap?=null,onAccepted:(ExportJob)->Unit={},onEnqueued:(ExportJob)->Unit={}):ExportJob=withContext(Dispatchers.IO) {
        require(quality in 1..100)
        val size=if(maxEdge==null)PhotoLimits.Dimensions(source.originalWidth,source.originalHeight)else source.outputSize(maxEdge)
        val border=if(parameters.filmBorder)borderProvider.snapshot(parameters.filmStock,size.width,size.height,source.originalWidth,source.originalHeight,parameters.composition,parameters.negativeWhiteHoles,parameters.outputRoute)else null
        border?.let{BorderExportPolicy.validate(it,format)}
        val crop=if(border==null)CropSnapshot.create(size.width,size.height,source.originalWidth,source.originalHeight,parameters.crop)else null
        val id=ExportJobStore.newId()
        val kind=if(source.linearFile!=null){if(maxEdge==null)"LINEAR"else "LINEAR_SOURCE"}else "ENCODED"
        val sourceFile=source.linearFile ?: source.localFile
        // 先持有文件描述符；即使后续导入删除源缓存，回退复制仍有自己的读端。
        val opened=sourceFile.inputStream()
        val preview=runCatching{thumbnail?.copy(Bitmap.Config.ARGB_8888,false)}.getOrNull()
        val generation=currentCoroutineContext()[ExportIntakeGeneration]?.generation ?: pauseGeneration.get()
        intakes.incrementAndGet()
        val task=captureScope.async(start=CoroutineStart.LAZY) {storageLock.withLock {
            val dir=store.directory(id);check(dir.mkdirs())
            val snapshot=File(dir,when(kind){"LINEAR"->"input.rgb";"LINEAR_SOURCE"->"source.rgb";else->"source.image"})
            var durable=false;var accepted=false
            val draft=ExportJob(id,maxOf(System.currentTimeMillis(),(jobs.value.maxOfOrNull{it.createdAt} ?: 0L)+1),parameters.copy(),size.width,size.height,format,quality,state=ExportJobState.PAUSED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_544c342feaf0),messageUi=UiMessage.of(R.string.text_544c342feaf0),inputReady=false,inputKind=kind,sourceWidth=source.originalWidth,sourceHeight=source.originalHeight,maxEdge=maxEdge,requestGeneration=generation,photoMetadata=source.photoMetadata.toJson(),metadataWarning=source.photoMetadata.warning,border=border,crop=crop)
            try {
                store.update(draft);accepted=true;runCatching{onAccepted(draft)};beforeCapture(draft)
                val bytes=opened.channel.size()
                if(android.os.StatFs(dir.path).availableBytes<bytes+64L*1024*1024+reservedBytes.get())throw EngineException(EngineFailure.STORAGE)
                val linked=runCatching{Files.createLink(snapshot.toPath(),sourceFile.toPath());true}.getOrDefault(false)
                if(!linked)FileOutputStream(snapshot).use{out->val buffer=ByteArray(256*1024);while(true){currentCoroutineContext().ensureActive();val n=opened.read(buffer);if(n<0)break;out.write(buffer,0,n)};out.fd.sync()}
                currentCoroutineContext().ensureActive();check(snapshot.length()==bytes);if(kind!="ENCODED")check(bytes==source.originalWidth.toLong()*source.originalHeight*12)
                FileOutputStream(snapshot,true).use{it.fd.sync()}
                preview?.let { bitmap ->
                    val scale=minOf(1.0,240.0/maxOf(bitmap.width,bitmap.height));val small=Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)
                    try {FileOutputStream(File(dir,"original-preview.jpg")).use{check(small.compress(Bitmap.CompressFormat.JPEG,85,it));it.fd.sync()}}finally{if(small!==bitmap)small.recycle()}
                }
                val startupBlocked=startupGate?.state?.value is StartupRecoveryGate.State.Failed
                val paused=generation!=pauseGeneration.get() || startupBlocked
                draft.copy(state=if(paused)ExportJobState.PAUSED else ExportJobState.PENDING,message=if(startupBlocked)STARTUP_BLOCKED_MESSAGE else if(paused)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bb72ee12ba8b)else null,messageUi=if(startupBlocked)UiMessage.of(R.string.text_31912f0eff0f)else if(paused)UiMessage.of(R.string.text_bb72ee12ba8b)else null,inputReady=true).also{ready->store.completeInput(ready);durable=true;runCatching{onEnqueued(ready)}}
            } catch(t:Throwable) {
                if(!durable)withContext(NonCancellable) {
                    if(accepted){snapshot.delete();runCatching{store.change(id){old->if(old.terminal)old else old.copy(state=ExportJobState.FAILED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e56de4bd4f96),messageUi=UiMessage.of(R.string.text_e56de4bd4f96))}}}
                    else dir.deleteRecursively()
                }
                throw t
            }
        }}
        captures[id]=task
        task.invokeOnCompletion{try{runCatching{opened.close()};preview?.recycle()}finally{intakes.decrementAndGet();captures.remove(id)}}
        task.start()
        task.await()
    }
    fun start(context:Context) {context.startForegroundService(Intent(context,ExportService::class.java))}
    fun retry(id:String,generation:Long=pauseGeneration.get()) {
        val startupBlocked=startupGate?.state?.value is StartupRecoveryGate.State.Failed
        store.change(id){if(it.canRetry && it.state in setOf(ExportJobState.PAUSED,ExportJobState.FAILED))it.copy(
            state=if(!startupBlocked && generation==pauseGeneration.get())ExportJobState.RETRY else ExportJobState.PAUSED,
            message=if(startupBlocked)STARTUP_BLOCKED_MESSAGE else if(generation==pauseGeneration.get())null else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bb72ee12ba8b),messageUi=if(startupBlocked)UiMessage.of(R.string.text_31912f0eff0f)else if(generation==pauseGeneration.get())null else UiMessage.of(R.string.text_bb72ee12ba8b),requestGeneration=generation)else it}
    }
    suspend fun cancel(id:String) {
        captures[id]?.cancelAndJoin()
        storageLock.withLock {
        val task=synchronized(activeLock) {
            if(store.get(id)?.state==ExportJobState.COMPLETED)return@synchronized null
            if(activeId==id) {
                runCatching{store.change(id){it.copy(state=ExportJobState.CANCELLED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0b58e0113da6),messageUi=UiMessage.of(R.string.text_0b58e0113da6))}}.onFailure{store.cancelInMemory(id)}
                activeTask?.also{it.cancel();processing.cancel()}
            } else {
                runCatching{store.change(id){if(it.state==ExportJobState.COMPLETED)it else it.copy(state=ExportJobState.CANCELLED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a37778f17c5f),messageUi=UiMessage.of(R.string.text_a37778f17c5f))}}.onFailure{store.cancelInMemory(id)}
                null
            }
        }
        if(task!=null){task.join();if(store.get(id)?.state==ExportJobState.CANCELLED)cleanup(id)}
        else if(store.get(id)?.state==ExportJobState.CANCELLED){
            val clean=cleanup(id)
            store.change(id){it.copy(message=if(clean)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a37778f17c5f)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ff3d626e1865),messageUi=UiMessage.of(if(clean)R.string.text_a37778f17c5f else R.string.text_ff3d626e1865))}
        }
    }
    }
    fun requestCancel(id:String) {captureScope.launch{runCatching{cancel(id)}}}
    data class PauseRequest(val generation:Long,val ids:Set<String>)
    /** 主线程只发布意图；磁盘转换另行在 IO 执行。 */
    fun requestPause():PauseRequest {
        val generation=announcePause()
        return PauseRequest(generation,jobs.value.filter{it.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY)}.map{it.id}.toSet())
    }
    fun applyPause(request:PauseRequest) {
        request.ids.forEach{id->store.change(id){if(it.requestGeneration<request.generation && it.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY))it.copy(state=ExportJobState.PAUSED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bb72ee12ba8b),messageUi=UiMessage.of(R.string.text_bb72ee12ba8b))else it}}
    }
    fun pauseWaiting()=applyPause(requestPause())
    /** 服务返回前等待 native 退出；不会只取消 Kotlin 等待而留下后台写盘线程。 */
    suspend fun runPending(onChanged:(ExportJob)->Unit):ExportRunOutcome {
        runLock.lock()
        try {
            if(startupGate!=null && startupGate.awaitAttempt() !is StartupRecoveryGate.State.Ready) {
                // 清理失败是终止本次服务尝试的阻塞状态，不是“队列已排空”。
                // 同时使仍在接收的旧请求保持暂停，禁止服务因 intake 尚在而自旋。
                val pause=requestPause()
                val message=STARTUP_BLOCKED_MESSAGE
                pause.ids.forEach{id->
                    runCatching{store.change(id){if(it.requestGeneration<pause.generation && it.state in setOf(ExportJobState.PENDING,ExportJobState.RETRY))it.copy(state=ExportJobState.PAUSED,message=message,messageUi=UiMessage.of(R.string.text_31912f0eff0f))else it}}
                        .onFailure{store.interruptedInMemory(id,message,UiMessage.of(R.string.text_31912f0eff0f))}
                }
                return ExportRunOutcome.BLOCKED_STARTUP_RECOVERY
            }
            repository.cleanupPendingExports()
            while(currentCoroutineContext().isActive) {
                val job=store.next()
                if(job==null){if(intakes.get()>0){delay(100);continue}else break}
                coroutineScope {
                    val task=launch(start=CoroutineStart.LAZY) {runOne(job,onChanged)}
                    synchronized(activeLock){activeId=job.id;activeTask=task}
                    val cancellationWatch=launch(start=CoroutineStart.UNDISPATCHED) {
                        try {awaitCancellation()}finally{withContext(NonCancellable+Dispatchers.IO){synchronized(activeLock){if(activeTask===task)processing.cancel()}}}
                    }
                    task.start()
                    try{task.join()}finally{withContext(NonCancellable){
                        cancellationWatch.cancelAndJoin();task.join()
                        synchronized(activeLock){activeTask=null;activeId=null}
                    }}
                }
            }
            return ExportRunOutcome.DRAINED
        } finally {runLock.unlock()}
    }
    private suspend fun runOne(initial:ExportJob,onChanged:(ExportJob)->Unit) {
        val id=initial.id;val dir=store.directory(id)
        fun update(transform:(ExportJob)->ExportJob){store.change(id,transform)?.let{changed->runCatching{onChanged(changed)}}}
        var output:File?=null
        var framedOutput:File?=null
        try {
            val accepted=synchronized(activeLock){
                if(store.get(id)?.state !in setOf(ExportJobState.PENDING,ExportJobState.RETRY))false
                else if(initial.requestGeneration!=pauseGeneration.get()) {update{it.copy(state=ExportJobState.PAUSED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bb72ee12ba8b),messageUi=UiMessage.of(R.string.text_bb72ee12ba8b))};false}
                else {update{it.copy(state=ExportJobState.RUNNING,message=null,messageUi=null)};true}
            }
            if(!accepted)return
            val existing=initial.mediaUri?.let{repository.committedExport(it,initial.outputWidth,initial.outputHeight,initial.format)}
            if(existing==null) {
                val input=File(dir,"input.rgb")
                // No framed result is ever a resume checkpoint or a source for another border.
                for(name in listOf("border-output.rgb","border-output.rgb.part")) {
                    val stale=File(dir,name);check(!stale.exists() || stale.delete())
                }
                initial.border?.let{BorderExportPolicy.validate(it,initial.format);borderProvider.verifySnapshot(it)}
                dir.listFiles()?.filter{it.isFile && it.name.matches(Regex("film-result-[0-9a-f-]{36}\\.rgb(\\.part)?"))}?.forEach{check(it.delete()){ dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_29367795aabb) }}
                storageLock.withLock {
                    val plan=processing.plan(initial.width,initial.height,initial.parameters)
                    val required=(if(input.length()==initial.width.toLong()*initial.height*12)ExportStoragePolicy.afterInput(plan)else ExportStoragePolicy.beforeInput(plan))+plan.checkpointReserveBytes+(initial.border?.let{BorderExportPolicy.additionalDiskBytes(it)} ?: initial.crop?.let{CropCompositor.additionalDiskBytes(it)} ?: 0L)
                    if(!ExportStoragePolicy.hasSpace(android.os.StatFs(dir.path).availableBytes,required))throw EngineException(EngineFailure.STORAGE)
                    reservedBytes.set(required)
                }
                if(input.length()!=initial.width.toLong()*initial.height*12) {
                    if(initial.inputKind=="LINEAR")throw EngineException(EngineFailure.INCOMPLETE)
                    input.delete()
                    val sourceFile=File(dir,if(initial.inputKind in setOf("RAW","LINEAR_SOURCE"))"source.rgb"else "source.image")
                    if(!sourceFile.isFile)throw EngineException(EngineFailure.INCOMPLETE)
                    val source=PhotoSource(sourceFile,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_25d728831d11),"application/octet-stream",initial.sourceWidth,initial.sourceHeight,sourceFile.length(),linearFile=sourceFile.takeIf{initial.inputKind in setOf("RAW","LINEAR_SOURCE")})
                    val prepared=File(dir,"prepared-input.part")
                    check(!prepared.exists()||prepared.delete()){ dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba513cd4be11) }
                    try {
                        if(initial.maxEdge==null)repository.spoolOriginal(source,prepared)else repository.spoolSized(source,prepared,initial.maxEdge)
                        check(prepared.length()==initial.width.toLong()*initial.height*12)
                        FileOutputStream(prepared,true).use{it.fd.sync()}
                        Files.move(prepared.toPath(),input.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    }finally{prepared.delete()}
                }
                if(input.length()!=initial.width.toLong()*initial.height*12)throw EngineException(EngineFailure.INCOMPLETE)
                output=processing.render(input,initial.width,initial.height,initial.parameters,dir){p->update{it.copy(progress=p.display)}}
                update{it.copy(progress=ExportDisplayProgress(7))}
                // One persisted instant per encode attempt. A committed row is reused above;
                // an uncommitted retry gets its actual new export time, not the failed attempt time.
                val captureMetadata=store.get(id)?.photoMetadata?.let(PhotoMetadata::fromJson) ?: PhotoMetadata.unavailable()
                update{it.copy(exportMillis=System.currentTimeMillis(),metadataWarning=captureMetadata.warning)}
                val exportRecord=checkNotNull(store.get(id))
                val encodingInput=initial.border?.let{border->
                    // Decode only after scientific rendering, never pin an atlas through the film pipeline.
                    val borderContext=currentCoroutineContext()
                    val asset=withContext(Dispatchers.IO){borderProvider.resolve(border){borderContext.ensureActive()}}
                    val memory=android.app.ActivityManager.MemoryInfo().also{(context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)}
                    if(memory.lowMemory || memory.availMem-memory.threshold<BorderCompositor.workingBytes(border,asset)*4)throw OutOfMemoryError("border admission")
                    if(android.os.StatFs(dir.path).availableBytes<BorderExportPolicy.additionalDiskBytes(border)+64L*1024*1024)throw EngineException(EngineFailure.STORAGE)
                    File(dir,"film-result-${java.util.UUID.randomUUID()}.rgb").also{target->
                        framedOutput=target
                        val coroutine=currentCoroutineContext()
                        BorderMemoryAdmission.withBudget(BorderCompositor.workingBytes(border,asset),{NativeWorkReservations.snapshot(context)}) {
                            BorderCompositor.composite(checkNotNull(output),target,border,asset,OutputColorSpace.parse(initial.parameters.outputColorSpace)){coroutine.ensureActive()}
                        }
                    }
                } ?: initial.crop?.takeUnless{it.isIdentity}?.let{crop->
                    if(android.os.StatFs(dir.path).availableBytes<CropCompositor.additionalDiskBytes(crop)+64L*1024*1024)throw EngineException(EngineFailure.STORAGE)
                    File(dir,"film-result-${java.util.UUID.randomUUID()}.rgb").also{target->
                        framedOutput=target
                        val coroutine=currentCoroutineContext()
                        BorderMemoryAdmission.withBudget(CropCompositor.workingBytes(crop),{NativeWorkReservations.snapshot(context)}){
                            CropCompositor.composite(checkNotNull(output),target,crop){coroutine.ensureActive()}
                        }
                    }
                } ?: checkNotNull(output)
                repository.exportLinear(encodingInput,initial.outputWidth,initial.outputHeight,initial.format,initial.quality,
                    onPrepared={result->update{it.copy(mediaUri=result.uri.toString())}},
                    onCommitted={result->update{it.copy(mediaUri=result.uri.toString(),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e0cc395f6b84),messageUi=UiMessage.of(R.string.text_e0cc395f6b84))}},
                    outputColorSpace=initial.parameters.outputColorSpace,
                    photoMetadata=captureMetadata,
                    exportMillis=checkNotNull(exportRecord.exportMillis),
                    onMetadataWarning={warning->update{it.copy(metadataWarning=warning)}})
            }
            store.get(id)?.mediaUri?.let { uri->runCatching{saveFinalThumbnail(uri,dir)} }
            update{it.copy(progress=ExportDisplayProgress(8))}
            output?.delete();output=null
            val clean=cleanup(id)
            update{it.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,if(clean)1 else 0,1),message=listOfNotNull(if(clean)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1bd91a7d0c53)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_598870cac16e),it.metadataWarning).joinToString("；"),messageUi=UiMessage.of(if(clean)R.string.text_1bd91a7d0c53 else R.string.text_598870cac16e))}
        } catch(c:CancellationException) {
            withContext(NonCancellable+Dispatchers.IO) {
                val current=store.get(id)!!
                val committed=current.mediaUri?.let{runCatching{repository.committedExport(it,current.outputWidth,current.outputHeight,current.format)}.getOrNull()}
                if(committed!=null){cleanup(id);update{it.copy(state=ExportJobState.COMPLETED,message=listOfNotNull(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1bd91a7d0c53),it.metadataWarning).joinToString("；"),messageUi=UiMessage.of(R.string.text_1bd91a7d0c53))}}
                else if(current.state==ExportJobState.CANCELLED){cleanup(id);update{it.copy(message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a37778f17c5f),messageUi=UiMessage.of(R.string.text_a37778f17c5f))}}
                else update{it.copy(state=ExportJobState.PAUSED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3df4e91da12d),messageUi=UiMessage.of(R.string.text_3df4e91da12d))}
            }
            throw c
        } catch(t:Exception) {update{it.copy(state=if((t as? EngineException)?.failure==EngineFailure.STORAGE)ExportJobState.PAUSED else ExportJobState.FAILED,failure=if((t as? EngineException)?.failure==EngineFailure.INCOMPLETE)ExportJobFailure.RECOVERY_INVALID else ExportJobFailure.NONE,message=if((t as? EngineException)?.failure==EngineFailure.INCOMPLETE)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_801eccbf436d)else if((t as? EngineException)?.failure==EngineFailure.STORAGE)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a3290bb56b7f)else (t as? EngineException)?.message ?: (t as? PhotoException)?.message ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c42a34760d85),messageUi=when((t as? EngineException)?.failure){
            EngineFailure.INCOMPLETE->UiMessage.of(R.string.text_801eccbf436d)
            EngineFailure.STORAGE->UiMessage.of(R.string.text_a3290bb56b7f)
            else->(t as? EngineException)?.uiMessage ?: (t as? PhotoException)?.uiMessage ?: UiMessage.of(R.string.text_c42a34760d85)
        })}}
        catch(t:OutOfMemoryError){update{it.copy(state=ExportJobState.FAILED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_84d1e185592a),messageUi=UiMessage.of(R.string.text_84d1e185592a))}}
        finally {reservedBytes.set(0);store.interruptedInMemory(id);withContext(NonCancellable+Dispatchers.IO){output?.delete();framedOutput?.delete()}}
    }
    private fun saveFinalThumbnail(uri:String,dir:File) {
        val bitmap=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver,android.net.Uri.parse(uri))) {decoder,info,_->
            val scale=minOf(1.0,240.0/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
            decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
        try {FileOutputStream(File(dir,"final-preview.jpg")).use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,88,it));it.fd.sync()}}finally{bitmap.recycle()}
    }
    private fun cleanup(id:String):Boolean {
        val dir=store.directory(id)
        return dir.listFiles()?.filter{it.name !in setOf("manifest.json","manifest.json.bak","original-preview.jpg","negative-preview.jpg","negative-preview.png","final-preview.jpg")}
            ?.map{if(it.isDirectory)it.deleteRecursively()else it.delete()}?.all{it} ?: false
    }
    class IntakeLease internal constructor(val generation:Long,private val release:()->Unit):java.io.Closeable {
        override fun close()=release()
    }
    companion object {
        private val STARTUP_BLOCKED_MESSAGE get()=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_31912f0eff0f)
        fun currentGeneration():Long=pauseGeneration.get()
        fun announcePause():Long=pauseGeneration.incrementAndGet()
        private val pauseGeneration=java.util.concurrent.atomic.AtomicLong(0)
        private val intakes=java.util.concurrent.atomic.AtomicInteger(0)
        /** 必须在用户确认的主线程、第一次挂起之前调用。 */
        fun beginIntake(context:Context):IntakeLease {
            intakes.incrementAndGet()
            val closed=java.util.concurrent.atomic.AtomicBoolean(false)
            val lease=IntakeLease(pauseGeneration.get()){if(closed.compareAndSet(false,true))intakes.decrementAndGet()}
            try {context.startForegroundService(Intent(context,ExportService::class.java));return lease}
            catch(t:Throwable){lease.close();throw t}
        }
        @Volatile private var instance:ExportQueue?=null
        fun get(context:Context):ExportQueue=instance ?: synchronized(this){instance ?: ExportQueue(context.applicationContext).also{instance=it}}

    }
}
