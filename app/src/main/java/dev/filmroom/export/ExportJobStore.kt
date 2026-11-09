package dev.filmroom.export

import android.util.AtomicFile
import dev.filmroom.i18n.UiMessage
import dev.filmroom.i18n.L10n
import dev.filmroom.R
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.crop.CropSnapshot
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ExportJobFailure { NONE, RECOVERY_INVALID }
enum class ExportJobState { PENDING, RUNNING, PAUSED, RETRY, COMPLETED, FAILED, CANCELLED }
data class ExportJob(
    val id:String, val createdAt:Long, val parameters:EditorState, val width:Int, val height:Int,
    val format:ExportFormat, val quality:Int, val state:ExportJobState=ExportJobState.PENDING,
    val progress:ExportDisplayProgress=ExportDisplayProgress(1), val mediaUri:String?=null,
    val message:String?=null, val inputReady:Boolean=true,
    val inputKind:String="LINEAR", val sourceWidth:Int=width, val sourceHeight:Int=height, val maxEdge:Int?=null, val requestGeneration:Long=0, val failure:ExportJobFailure=ExportJobFailure.NONE,
    val completionViewed:Boolean=false,
    val photoMetadata:String?=null, val exportMillis:Long?=null, val metadataWarning:String?=null,
    val border:BorderSnapshot?=null,
    val crop:CropSnapshot?=null,
    val messageUi:UiMessage?=null,
    val outputName:String?=null,
    val archivedHistory:Boolean=false,
    val archivedSourceState:String?=null,
    val originalUri:String?=null,
) {
    /** Render app-owned messages in the current language; legacy prose is diagnostics only. */
    val localizedMessage:String? get() {
        if(archivedHistory)return if(mediaUri==null)L10n.text(R.string.backup_history_no_photos)else null
        val primary=messageUi?.render() ?: message?.takeIf{it.isNotBlank()}?.let {
            L10n.text(when(state) {
                ExportJobState.PENDING,ExportJobState.RETRY -> R.string.text_8fb3a3261b43
                ExportJobState.RUNNING -> R.string.text_9aaf67962338
                ExportJobState.PAUSED -> if(inputReady)R.string.text_eb0c326b60ae else R.string.text_544c342feaf0
                ExportJobState.COMPLETED -> R.string.text_1bd91a7d0c53
                ExportJobState.FAILED -> if(failure==ExportJobFailure.RECOVERY_INVALID)R.string.text_801eccbf436d else R.string.text_6e4445df6957
                ExportJobState.CANCELLED -> R.string.text_a37778f17c5f
            })
        }
        return listOfNotNull(primary,metadataWarning?.takeIf{it.isNotBlank()}?.let{L10n.text(R.string.queue_metadata_warning)}).takeIf{it.isNotEmpty()}?.joinToString("\n")
    }
    /** Diagnostic prose is never reverse-translated or silently discarded on a locale switch. */
    val originalDiagnostics:String? get()=listOfNotNull(
        message?.takeIf{it.isNotBlank() && (messageUi?.render()==null || state==ExportJobState.FAILED)},
        metadataWarning?.takeIf{it.isNotBlank()}
    ).distinct().takeIf{it.isNotEmpty()}?.joinToString("\n")
    val outputWidth get()=border?.outputWidth ?: crop?.outputWidth ?: width
    val outputHeight get()=border?.outputHeight ?: crop?.outputHeight ?: height
    init {
        require(!parameters.imprint.enabled || border!=null || crop!=null){"Imprint requires frozen output geometry"}
        val frozen2302=border?.let{dev.filmroom.border.BorderCatalog.isFrozen2302Frame(it,parameters.filmStock,parameters.paperStock,parameters.outputRoute)}==true
        require(crop==null || (border==null && !parameters.filmBorder && crop.sourceWidth==width && crop.sourceHeight==height &&
            crop.canonicalWidth==sourceWidth && crop.canonicalHeight==sourceHeight && crop.settings==parameters.crop)) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_101df955ba5b) }
        require(border==null || (parameters.filmBorder && (border.stock==parameters.frameStock || frozen2302) && border.sourceWidth==width && border.sourceHeight==height))
        require(border==null || border.geometryVersion==1 ||
            (border.canonicalWidth==sourceWidth && border.canonicalHeight==sourceHeight &&
             border.composition==parameters.presentationTransformForFrame(sourceWidth,sourceHeight,if(frozen2302)border.stock else parameters.frameStock))) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_eca4546685f7) }
        require(border==null || (border.mediumFormat==parameters.effectiveMediumFormat &&
            border.mediumPortrait==parameters.effectiveMediumPortrait)) { "Queued medium-format frame differs from frozen parameters" }
        border?.appearance?.let {
            require(it==dev.filmroom.border.FrameAppearanceContext.forProfile(border.stock,parameters.outputRoute,parameters.negativeWhiteHoles)) { "Queued frame appearance differs from frozen parameters" }
        }
        require(!parameters.filmBorder || border!=null) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_02d5c30befc6) }
    }
    val canRetry get()=!archivedHistory && inputReady && failure!=ExportJobFailure.RECOVERY_INVALID
    val terminal get()=state in setOf(ExportJobState.COMPLETED,ExportJobState.CANCELLED)
}

/** 每个任务独立原子日志。中断任务只暂停；重新打开应用不擅自恢复计算。 */
class ExportJobStore(val root:File) {
    private val mutable=MutableStateFlow<List<ExportJob>>(emptyList())
    val jobs=mutable.asStateFlow()
    private val archive=ArchivedExportHistory(File(root,".backup-history.json"))
    private var archived=emptyList<ExportJob>()
    private var archiveReadFailure:Exception?=null
    private val historyReservations=mutableMapOf<String,String>()
    private val historyJournal=ExportHistoryJournal(File(root,".history-operations"))
    private val historyStates=setOf(ExportJobState.COMPLETED,ExportJobState.FAILED,ExportJobState.CANCELLED)
    private fun fingerprint(job:ExportJob)=java.security.MessageDigest.getInstance("SHA-256").digest(encode(job.copy(completionViewed=false)).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    @Synchronized fun historySnapshot(ids:Set<String>):List<ExportHistorySnapshot> = mutable.value.filter{it.id in ids && it.state in historyStates}.map{ExportHistorySnapshot(it.id,fingerprint(it),it.state,it.mediaUri,it.exportMillis,it.outputName)}
    @Synchronized fun historyReserved(id:String)=id in historyReservations
    @Synchronized fun historyMatches(snapshot:ExportHistorySnapshot):Boolean=get(snapshot.id)?.let{it.state in historyStates && fingerprint(it)==snapshot.fingerprint}==true
    @Synchronized fun beginHistory(targets:List<ExportHistorySnapshot>,deletePhotos:Boolean):ExportHistoryOperation {
        val eligible=targets.distinctBy{it.id}.filter{!historyReserved(it.id) && historyMatches(it)}
        val op=ExportHistoryOperation(UUID.randomUUID().toString(),deletePhotos,eligible)
        historyJournal.write(op) // Never reserve/publish an intent which was not durably verified.
        eligible.forEach{historyReservations[it.id]=op.id};return op
    }
    @Synchronized fun readHistoryOperation(id:String)=historyJournal.read(id)
    @Synchronized fun updateHistoryOperation(op:ExportHistoryOperation){historyJournal.write(op)}
    /** Only invoked for an ActivityResult belonging to the exact saved operation. No startup replay. */
    @Synchronized fun restoreHistoryReservation(op:ExportHistoryOperation):Boolean {
        if(op.status!="awaiting-consent")return false
        val remaining=op.remaining
        if(remaining.any{!historyMatches(it) || (historyReservations[it.id]!=null && historyReservations[it.id]!=op.id)})return false
        remaining.forEach{historyReservations[it.id]=op.id};return true
    }
    @Synchronized fun removeHistoryRecord(op:ExportHistoryOperation,snapshot:ExportHistorySnapshot):Boolean {
        if(historyReservations[snapshot.id]!=op.id || !historyMatches(snapshot))return false
        if(op.deletePhotos && get(snapshot.id)?.archivedHistory!=true && (op.status!="photo-deleted:${snapshot.id}" || historyJournal.read(op.id)?.status!=op.status))return false
        if(get(snapshot.id)?.archivedHistory==true) {
            val remaining=archived.filterNot{it.id==snapshot.id}
            archive.write(remaining);archived=remaining
            mutable.value=mutable.value.filterNot{it.id==snapshot.id};historyReservations.remove(snapshot.id)
            return true
        }
        val from=directory(snapshot.id);val trash=File(root,".history-trash-${snapshot.id}-${op.id}")
        // Same-filesystem atomic rename hides the entire record. An interrupted purge cannot resurrect FAILED.
        java.nio.file.Files.move(from.toPath(),trash.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        check(!from.exists() && trash.isDirectory)
        mutable.value=mutable.value.filterNot{it.id==snapshot.id};historyReservations.remove(snapshot.id)
        // Only the exact app-owned job directory; never mediaUri or any source/gallery path.
        runCatching{trash.deleteRecursively()}
        return true
    }
    @Synchronized fun releaseHistory(op:ExportHistoryOperation){historyReservations.entries.removeAll{it.value==op.id}}

    init {
        check(root.mkdirs()||root.isDirectory)
        val loaded=root.listFiles()?.filter{it.isDirectory && validId(it.name)}?.mapNotNull { dir ->
            runCatching { decode(AtomicFile(File(dir,"manifest.json")).openRead().bufferedReader().use{it.readText()}).also{require(it.id==dir.name)} }.getOrElse { ExportJob(dir.name,dir.lastModified(),EditorState(),1,1,ExportFormat.PNG,95,ExportJobState.FAILED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_31d5184a31ac),messageUi=UiMessage.of(R.string.text_31d5184a31ac),inputReady=false) }
        } ?: error(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b4351becfe47))
        archived=try{archive.read().filter{row->loaded.none{it.id==row.id}}}catch(error:Exception){archiveReadFailure=error;emptyList()}
        mutable.value=(loaded+archived).sortedWith(compareBy<ExportJob>{it.createdAt}.thenBy{it.id})
        loaded.filter{!it.inputReady && !it.terminal || it.state in setOf(ExportJobState.RUNNING,ExportJobState.PENDING,ExportJobState.RETRY)}.forEach {
            val recovered=if(it.inputReady)it.copy(state=ExportJobState.PAUSED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9652b13c1fd3),messageUi=UiMessage.of(R.string.text_9652b13c1fd3))else it.copy(state=ExportJobState.FAILED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e56de4bd4f96),messageUi=UiMessage.of(R.string.text_e56de4bd4f96))
            runCatching{update(recovered)}.onFailure{mutable.value=mutable.value.map{old->if(old.id==recovered.id)recovered else old}}
        }
    }
    /** All list rows, including active rows, are backed up only as non-executable metadata. */
    @Synchronized internal fun exportBackupHistory():org.json.JSONArray {
        check(archiveReadFailure==null){"Stored backup history is unreadable; existing data was preserved"}
        return ExportHistoryBackup.exportRows(mutable.value)
    }
    @Synchronized internal fun mergeBackupHistory(rows:org.json.JSONArray, validateOnly:Boolean=false):Int {
        check(archiveReadFailure==null){"Stored backup history is unreadable; existing data was preserved"}
        val incoming=ExportHistoryBackup.validateRows(rows)
        val additions=incoming.filter{row->get(row.id)==null}
        val upgrades=archived.mapNotNull { old ->
            incoming.firstOrNull { it.id==old.id && it.createdAt==old.createdAt && it.outputWidth==old.outputWidth && it.outputHeight==old.outputHeight && it.state==old.state }?.let { newer ->
                val upgradeGeometry=old.crop==null && old.border==null && (newer.crop!=null || newer.border!=null)
                old.copy(parameters=if(upgradeGeometry)newer.parameters else old.parameters,
                    width=if(upgradeGeometry)newer.width else old.width,height=if(upgradeGeometry)newer.height else old.height,
                    sourceWidth=if(upgradeGeometry)newer.sourceWidth else old.sourceWidth,sourceHeight=if(upgradeGeometry)newer.sourceHeight else old.sourceHeight,
                    mediaUri=old.mediaUri ?: newer.mediaUri,outputName=old.outputName ?: newer.outputName,
                    originalUri=old.originalUri ?: newer.originalUri,crop=old.crop ?: newer.crop,border=old.border ?: newer.border,
                    photoMetadata=old.photoMetadata ?: newer.photoMetadata).takeUnless { it==old }
            }
        }.associateBy { it.id }
        if(additions.isEmpty() && upgrades.isEmpty())return 0
        val merged=archived.map{upgrades[it.id] ?: it}+additions
        archive.validate(merged)
        if(validateOnly)return additions.size
        archive.write(merged);archived=merged
        mutable.value=(mutable.value.map{upgrades[it.id] ?: it}+additions).sortedWith(compareBy<ExportJob>{it.createdAt}.thenBy{it.id})
        return additions.size
    }
    /** Only an explicit foreground picker-confirmation may replace an archive link. Never requeues a job. */
    @Synchronized internal fun relinkArchivedPhoto(id:String, expectedUri:String?, selectedUri:String,selectedName:String):Boolean {
        val old=get(id)?.takeIf{it.archivedHistory && it.mediaUri==expectedUri && !historyReserved(id)} ?: return false
        require(BackupMediaLink.validOutput(selectedUri))
        require(selectedName.isNotBlank() && selectedName.length<=255 && !selectedName.contains('/') && !selectedName.contains('\\'))
        require(old.outputName==null || old.outputName==selectedName)
        val updated=old.copy(mediaUri=selectedUri,outputName=selectedName)
        val merged=archived.map{if(it.id==id)updated else it}
        archive.write(merged);archived=merged
        mutable.value=mutable.value.map{if(it.id==id)updated else it}
        return true
    }
    /** Explicit foreground reselection for effects. CAS prevents stale screens overwriting newer links. */
    @Synchronized internal fun relinkEffectPhoto(id:String, original:Boolean, expectedUri:String?, selectedUri:String, selectedName:String?=null):Boolean {
        val old=get(id)?.takeIf{it.state==ExportJobState.COMPLETED && !historyReserved(id)} ?: return false
        val actual=if(original)old.originalUri else old.mediaUri
        if(actual!=expectedUri)return false
        val updated=if(original) {
            require(dev.filmroom.io.OriginalPhotoUri.sanitize(selectedUri)!=null)
            old.copy(originalUri=selectedUri)
        } else {
            require(BackupMediaLink.validOutput(selectedUri))
            require(!selectedName.isNullOrBlank() && selectedName.length<=255 && !selectedName.contains('/') && !selectedName.contains('\\'))
            require(old.outputName==null || old.outputName==selectedName)
            old.copy(mediaUri=selectedUri,outputName=selectedName)
        }
        if(old.archivedHistory) {
            check(archiveReadFailure==null){"Stored backup history is unreadable"}
            val merged=archived.map{if(it.id==id)updated else it}
            archive.write(merged);archived=merged
            mutable.value=mutable.value.map{if(it.id==id)updated else it}
        } else update(updated)
        return true
    }
    @Synchronized internal fun archiveSnapshot():org.json.JSONArray {
        check(archiveReadFailure==null){"Stored backup history is unreadable"}
        return ExportHistoryBackup.exportRows(archived)
    }
    @Synchronized internal fun restoreArchiveSnapshot(rows:org.json.JSONArray) {
        check(archiveReadFailure==null){"Stored backup history is unreadable"}
        val restored=ExportHistoryBackup.validateRows(rows)
        archive.write(restored);archived=restored
        mutable.value=(mutable.value.filterNot{it.archivedHistory}+restored).distinctBy{it.id}
            .sortedWith(compareBy<ExportJob>{it.createdAt}.thenBy{it.id})
    }
    fun directory(id:String):File {require(validId(id));return File(root,id)}
    @Synchronized fun get(id:String)=mutable.value.firstOrNull{it.id==id}
    @Synchronized fun update(incoming:ExportJob) {
        require(!incoming.archivedHistory && get(incoming.id)?.archivedHistory!=true){"Archived history is not an executable export"}
        // UI acknowledgments and worker snapshots share this lock. A stale worker copy
        // must not clear a durable read, while a genuinely new completion is unread.
        val previous=get(incoming.id)
        val job=incoming.copy(completionViewed=when {
            incoming.state!=ExportJobState.COMPLETED -> false
            previous!=null && previous.state!=ExportJobState.COMPLETED -> false
            else -> incoming.completionViewed || previous?.completionViewed==true
        })
        val dir=directory(job.id);check(dir.mkdirs()||dir.isDirectory)
        val target=AtomicFile(File(dir,"manifest.json"));val stream=target.startWrite()
        try {
            val bytes=encode(job).toByteArray(Charsets.UTF_8)
            stream.write(bytes);stream.fd.sync();target.finishWrite(stream)
            // Android AtomicFile.finishWrite logs rename/fsync failures instead of throwing.
            // Never publish a new state unless reading the committed file verifies it.
            check(target.openRead().use{it.readBytes()}.contentEquals(bytes)){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f5923a5ffaa3)}
        }
        catch(t:Throwable){target.failWrite(stream);throw t}
        mutable.value=(mutable.value.filterNot{it.id==job.id}+job).sortedWith(compareBy<ExportJob>{it.createdAt}.thenBy{it.id})
    }
    /** Only acknowledge snapshots that were displayed as completed, never IDs of running cards. */
    @Synchronized fun markCompletionsViewed(displayed:List<ExportJob>) {
        var failure:Exception?=null
        displayed.filter{it.state==ExportJobState.COMPLETED}.forEach { seen ->
            val current=get(seen.id) ?: return@forEach
            if(current.state==ExportJobState.COMPLETED && !current.completionViewed &&
                current.createdAt==seen.createdAt && current.mediaUri==seen.mediaUri) {
                try {update(current.copy(completionViewed=true))}catch(e:Exception){if(failure==null)failure=e}
            }
        }
        failure?.let{throw it}
    }
    @Synchronized internal fun completeInput(ready:ExportJob) {
        val current=get(ready.id) ?: throw java.io.IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_70d544a20512))
        check(current.state==ExportJobState.PAUSED && !current.inputReady){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_704cb65572bc)}
        update(ready)
    }
    @Synchronized fun change(id:String,transform:(ExportJob)->ExportJob):ExportJob?=get(id)?.let{if(historyReserved(id))it else transform(it).also(::update)}
    @Synchronized internal fun cancelInMemory(id:String) {
        mutable.value=mutable.value.map{if(it.id==id && it.state!=ExportJobState.COMPLETED)it.copy(state=ExportJobState.CANCELLED,message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0b58e0113da6),messageUi=UiMessage.of(R.string.text_0b58e0113da6))else it}
    }
    /** 磁盘满时无法写失败日志；只修复当前进程 UI，磁盘上的 RUNNING 下次启动仍会暂停。 */
    internal fun interruptedInMemory(id:String) = interruptedInMemory(id,L10n.text(R.string.text_b050569cce69),UiMessage.of(R.string.text_b050569cce69))
    @Synchronized internal fun interruptedInMemory(id:String,message:String,messageUi:UiMessage?=null) {
        mutable.value=mutable.value.map{if(it.id==id && it.state in setOf(ExportJobState.RUNNING,ExportJobState.PENDING,ExportJobState.RETRY))it.copy(state=ExportJobState.PAUSED,message=message,messageUi=messageUi)else it}
    }
    @Synchronized fun next():ExportJob?=mutable.value.firstOrNull{it.state==ExportJobState.PENDING||it.state==ExportJobState.RETRY}
    companion object {
        fun newId()=UUID.randomUUID().toString()
        private fun validId(id:String)=runCatching{UUID.fromString(id).toString()==id}.getOrDefault(false)
        private fun encode(j:ExportJob)=JSONObject().apply {
            put("originalUri",j.originalUri);put("outputName",j.outputName);put("border",j.border?.json());put("crop",j.crop?.json());put("photoMetadata",j.photoMetadata);put("exportMillis",j.exportMillis);put("metadataWarning",j.metadataWarning);
            put("completionViewed",j.completionViewed);put("version",if(j.parameters.imprint.enabled)7 else if(j.border?.geometryVersion==5)6 else if(j.border?.appearance!=null)5 else if(j.crop!=null || j.border?.geometryVersion==3)4 else when(j.border?.geometryVersion){null->1;1->2;else->3});put("id",j.id);put("createdAt",j.createdAt);put("parameters",j.parameters.json("export"));put("width",j.width);put("height",j.height)
            put("format",j.format.name);put("quality",j.quality);put("state",j.state.name);put("stage",j.progress.stage);put("done",j.progress.done);put("total",j.progress.total);put("gpuProgress",j.progress.gpu?.toJson());put("visualProgress",j.progress.visual?.let{JSONObject().put("stage",it.stage).put("percent",it.percent)})
            put("mediaUri",j.mediaUri);put("message",j.message);put("messageUi",j.messageUi?.toJson());put("inputReady",j.inputReady);put("inputKind",j.inputKind);put("sourceWidth",j.sourceWidth);put("sourceHeight",j.sourceHeight);put("maxEdge",j.maxEdge);put("requestGeneration",j.requestGeneration);put("failure",j.failure.name)
        }.toString()
        private fun decodeVisual(value:JSONObject?):dev.filmroom.engine.ExportVisualProgress? {
            if(value==null)return null
            val stage=value.optInt("stage",0)
            val percent=if(value.has("percent") && !value.isNull("percent"))value.optInt("percent",-1)else null
            return if(stage in 1..6 && (percent==null || percent in 0..100))dev.filmroom.engine.ExportVisualProgress(stage,percent)else null
        }
        private fun decode(text:String)=JSONObject(text).let { j ->
            val version=j.getInt("version")
            require(version in 1..7)
            // A legacy app must reject framed jobs rather than silently export without their border.
            val border=j.optString("border").takeIf{it.isNotEmpty()}?.let(BorderSnapshot::fromJson)
            val crop=j.optString("crop").takeIf{it.isNotEmpty()}?.let(CropSnapshot::fromJson)
            require(version>=5 || border?.appearance==null)
            val parameters=EditorState.fromJson(j.getString("parameters"))
            require((version==7)==parameters.imprint.enabled){"Imprint queue version mismatch"}
            if(version==7){
                require((border!=null) xor (crop!=null))
            }else if(version==6){
                require(border?.appearance!=null && border.geometryVersion==5 && border.mediumFormat!=null && crop==null)
            }else if(version==5){
                require(border?.appearance!=null && border.geometryVersion in 2..4 && crop==null)
            }else if(version<4){
                require((version>=2)==j.has("border") && !j.has("crop"))
                require(border==null || border.geometryVersion==version-1)
            }else require((border?.geometryVersion==3 && crop==null) || (border==null && crop!=null)) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_83b39ce89c4f) }
            require(validId(j.getString("id")));require(j.getInt("width") in 1..30000 && j.getInt("height") in 1..30000 && j.getInt("width").toLong()*j.getInt("height")<=150_000_000L);require(j.getInt("quality") in 1..100)
            ExportJob(j.getString("id"),j.getLong("createdAt"),parameters,j.getInt("width"),j.getInt("height"),ExportFormat.valueOf(j.getString("format")),j.getInt("quality"),ExportJobState.valueOf(j.getString("state")),ExportDisplayProgress(j.getInt("stage"),j.getLong("done"),j.getLong("total"),dev.filmroom.engine.GpuWorkProgress.fromJson(j.optJSONObject("gpuProgress")),decodeVisual(j.optJSONObject("visualProgress"))),j.optString("mediaUri").takeIf{it.isNotEmpty()},j.optString("message").takeIf{it.isNotEmpty()},j.optBoolean("inputReady",true),j.optString("inputKind","LINEAR"),j.optInt("sourceWidth",j.getInt("width")),j.optInt("sourceHeight",j.getInt("height")),if(j.has("maxEdge"))j.getInt("maxEdge")else null,j.optLong("requestGeneration",0),ExportJobFailure.valueOf(j.optString("failure","NONE")),j.optBoolean("completionViewed",false),
                j.optString("photoMetadata").takeIf{it.isNotEmpty()}?.let{dev.filmroom.io.PhotoMetadata.fromJson(it).toJson()},
                if(j.has("exportMillis"))j.getLong("exportMillis").takeIf{it>0}else null,
                j.optString("metadataWarning").takeIf{it.isNotEmpty()}?.take(256),
                border,crop,UiMessage.fromJson(j.optJSONObject("messageUi")),j.optString("outputName").takeIf{it.isNotEmpty()},originalUri=dev.filmroom.io.OriginalPhotoUri.sanitize(j.optString("originalUri").takeIf{it.isNotEmpty()}))
        }
    }
}
