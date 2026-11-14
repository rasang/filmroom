package dev.filmroom.io

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import dev.filmroom.io.importing.*
import dev.filmroom.codec.PhotoCodecs
import dev.filmroom.OperationTrace
import dev.filmroom.diagnostics.Operation
import dev.filmroom.diagnostics.Stage
import dev.filmroom.diagnostics.SafeInfo
import dev.filmroom.diagnostics.MetadataIssue
import dev.filmroom.diagnostics.MetadataField
import dev.filmroom.diagnostics.MetadataIssueReason
import dev.filmroom.diagnostics.MetadataIssuePhase
import dev.filmroom.diagnostics.Diagnostics
import dev.filmroom.diagnostics.ImportDiagnosticInfo
import dev.filmroom.diagnostics.ImportOrigin
import dev.filmroom.diagnostics.ImportPhase
import dev.filmroom.diagnostics.ImportRoute
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun deviceCodecs(context:Context):PhotoCodecs {
    val app=context.applicationContext
    return PhotoCodecs(app.cacheDir,exportJobsRoot=File(app.filesDir,"export-jobs"),memorySnapshot={dev.filmroom.engine.NativeWorkReservations.snapshot(app)},memoryBudgetMiB={
        val manager=app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory=android.app.ActivityManager.MemoryInfo();manager.getMemoryInfo(memory)
        runCatching { dev.filmroom.diagnostics.ImportObservation.current()?.budgetSnapshot=dev.filmroom.engine.NativeMemorySnapshot(memory.availMem,memory.threshold,memory.lowMemory) }
        dev.filmroom.engine.NativeMemoryPolicy.chooseBudgetMiB(memory.availMem,memory.threshold,memory.lowMemory) ?: 0
    },rawBatchPreference={dev.filmroom.model.RawBatchPreferences(app).mode})
}

/** An app-private copy: selection permissions are not needed for later decoding. */
class PhotoSource internal constructor(
    internal val localFile: File,
    val name: String,
    val mimeType: String,
    /** Display-oriented dimensions; EXIF/container transforms are applied once by the input route. */
    val originalWidth: Int,
    val originalHeight: Int,
    val encodedBytes: Long,
    /** Alpha-bearing sources are composited onto white before the RGB-only engine. */
    val alphaCompositedOnWhite: Boolean = false,
    internal val linearFile: File? = null,
    developmentWarning: String? = null,
    val photoMetadata: PhotoMetadata = PhotoMetadata.unavailable(),
    private val developmentMessages:List<dev.filmroom.i18n.UiMessage>? = null,
) {
    /** Original provider locator for future use only; not a persistent access grant. */
    var originalUri: String? = null
        private set
    internal fun rememberOriginalUri(value: String?) { originalUri = OriginalPhotoUri.sanitize(value) }
    private val legacyDevelopmentWarning=developmentWarning
    /** Source facts are retained; visible wording follows the current app language without re-decoding. */
    val developmentWarning:String? get()=developmentMessages?.mapNotNull{it.render()}?.takeIf{it.isNotEmpty()}?.joinToString("\n") ?: legacyDevelopmentWarning
    private val previewSnapshotGeneration=UUID.randomUUID().toString()
    /** Process-local owned snapshot, never a persistent/content-addressed/export identity. */
    internal fun previewSourceRevision():String {
        val file=linearFile ?: localFile
        return "$previewSnapshotGeneration:${file.length()}:${file.lastModified()}"
    }
    internal val detailGridCache=DetailGridCache()
    internal val previewMetering=dev.filmroom.engine.PreviewMeteringCache<dev.filmroom.engine.PreviewMeteringResult>()
    private val detailLeaseLock=Any()
    private var detailReaders=0
    private var discardRequested=false
    internal fun retainDetailSource():AutoCloseable=synchronized(detailLeaseLock){
        check(!discardRequested); detailReaders++
        var closed=false
        AutoCloseable { synchronized(detailLeaseLock){if(!closed){closed=true;detailReaders--;if(discardRequested && detailReaders==0)deleteSourceFiles()}} }
    }
    private fun deleteSourceFiles(){File(localFile.path+".imprint-time").delete();localFile.delete();linearFile?.delete()}
    internal fun discardWhenUnused(){try{detailGridCache.close()}finally{previewMetering.close();synchronized(detailLeaseLock){discardRequested=true;if(detailReaders==0)deleteSourceFiles()}}}
    private val previewMutex=Mutex()
    private val previewCacheLock=Any()
    private var previewCacheReleased=false
    @Volatile private var cachedLinearPreview:Pair<Int,LinearPreview>?=null
    @Volatile private var previousLinearPreview:Pair<Int,LinearPreview>?=null

    /** Source-local, parameter-independent two-level cache: at most 2 × 768² RGB floats, never a full-size source.
     * Building runs off Main and cancellation cannot publish a partial buffer. The cache
     * lives only as long as this source; reset/gamut/film changes keep the original linear source.
     */
    internal suspend fun linearPreview(maxEdge:Int):LinearPreview? {
        val raw=linearFile ?: return null
        require(maxEdge in 1..dev.filmroom.engine.NativeMemoryPolicy.DETAIL_PREVIEW_EDGE)
        return previewMutex.withLock {
            currentCoroutineContext().ensureActive()
            (cachedLinearPreview?.takeIf { it.first==maxEdge } ?: previousLinearPreview?.takeIf { it.first==maxEdge })?.second ?: withContext(Dispatchers.IO) {
                LinearSpool.linearPreview(raw,originalWidth,originalHeight,maxEdge,currentCoroutineContext())
            }.also {
                currentCoroutineContext().ensureActive()
                synchronized(previewCacheLock){if(!previewCacheReleased){previousLinearPreview=cachedLinearPreview;cachedLinearPreview=maxEdge to it}}
            }
        }
    }
    /** Drop cache ownership, not native memory: in-flight readers keep their own references. */
    internal fun clearLinearPreview(){try{detailGridCache.close()}finally{synchronized(previewCacheLock){previewCacheReleased=true;cachedLinearPreview=null;previousLinearPreview=null}}}

    /** Safe basename only; can be retained in private preferences for process restore. */
    val cacheKey: String get() = localFile.name
    val rawCacheKey: String? get() = linearFile?.name

    fun outputSize(maxEdge: Int = PhotoLimits.EXPORT_EDGE): PhotoLimits.Dimensions =
        PhotoLimits.fit(originalWidth, originalHeight, maxEdge)
}

/** Caller owns preview after a successful return and must not recycle it while displayed. */
data class LoadedPhoto(val source: PhotoSource, val preview: Bitmap)

enum class ExportFormat(val mimeType: String, val extension: String, val compression: Bitmap.CompressFormat?) {
    JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG),
    PNG("image/png", "png", Bitmap.CompressFormat.PNG),
    TIFF16("image/tiff", "tif", null),
}

data class ExportResult(
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

class PhotoException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
    val cleanupUncertain: Boolean = false,
    val codecCode: dev.filmroom.diagnostics.CodecErrorCode? = null,
    val codecStage: dev.filmroom.diagnostics.CodecStage? = null,
    val rawTechnical: dev.filmroom.diagnostics.RawTechnicalInfo? = null,
    val uiMessage:dev.filmroom.i18n.UiMessage? = null,
    val importDiagnostic: ImportDiagnosticInfo? = null,
    val codecDetail: dev.filmroom.diagnostics.CodecDiagnosticDetail? = null,
) : IOException(message, cause) {
    constructor(reason:Reason,message:dev.filmroom.i18n.UiMessage,cause:Throwable?=null,cleanupUncertain:Boolean=false,
        codecCode:dev.filmroom.diagnostics.CodecErrorCode?=null,codecStage:dev.filmroom.diagnostics.CodecStage?=null,
        rawTechnical:dev.filmroom.diagnostics.RawTechnicalInfo?=null,
        codecDetail:dev.filmroom.diagnostics.CodecDiagnosticDetail?=null):this(reason,
            message.render() ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ae173945fb80),
            cause,cleanupUncertain,codecCode,codecStage,rawTechnical,message,codecDetail=codecDetail)

    enum class Reason { UNSUPPORTED, TOO_LARGE, UNREADABLE, STORAGE, EXPORT }
}

class ExportCancellationException(cause: CancellationException) : CancellationException(
    dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b778fe908dac),
) {
    val cleanupUncertain: Boolean = true
    init { initCause(cause) }
}

data class PendingCleanupReport(val removed: Int, val preservedFinalized: Int, val unresolved: Int)

/**
 * No network APIs or source rewrites. Existing capture metadata, including authorized GPS, is retained.
 * All methods that perform IO are suspend functions on Dispatchers.IO.
 * Cancel during an uninterruptible native decode discards its eventual bitmap.
 */
class PhotoRepository internal constructor(context: Context, private val codecs:PhotoCodecs, heicImport:HeicImport?) {
    constructor(context:Context, codecs:PhotoCodecs = deviceCodecs(context)):this(context,codecs,null)
    val availableExportFormats: List<ExportFormat> get() = if(codecs.available) ExportFormat.entries else listOf(ExportFormat.PNG)
    private val importMemorySnapshot = { dev.filmroom.engine.NativeWorkReservations.snapshot(context.applicationContext) }
    private val heicImporter = heicImport ?: HeicImport({ dev.filmroom.engine.NativeWorkReservations.snapshot(context.applicationContext) })
    private val inputAdapters = PhotoInputAdapters(listOf(
        RasterInputAdapter(RasterPreviewDecoder { file, maxEdge, job ->
            var width=0;var height=0;var alpha=false
            val bitmap=decodeFile(file,maxEdge,job,onAlphaComposite={alpha=it}){w,h->width=w;height=h}
            RasterImportPreview(bitmap,width,height,alpha)
        }),
        HeicInputAdapter(heicImporter),
        RawInputAdapter(NativeRawSourceDecoder(codecs)),
    ))
    private val outputAssets = context.applicationContext.assets
    private val importContext = context.applicationContext
    private val resolver = importContext.contentResolver
    private val sourceDirectory = File(context.applicationContext.cacheDir, "photo-sources")
    private val privateRoots = arrayOf(context.applicationContext.cacheDir.canonicalFile, context.applicationContext.filesDir.canonicalFile)
    private val engineDataDirectory = File(context.applicationContext.filesDir, "engine-data").canonicalFile
    private val packageName = context.applicationContext.packageName
    private val pendingPreferences = context.applicationContext.getSharedPreferences("filmroom-pending-exports", Context.MODE_PRIVATE)

    private companion object {
        const val PENDING_KEY = "uris"
        val journalLock = Any()
        // Guards cleanup against another repository instance exporting in this process.
        val activePending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    /**
     * Startup recovery for exact app-owned URIs journaled before writing output.
     * Never scans or deletes the user's gallery. Finalized rows are only removed
     * from the journal; they are never deleted, even after a commit-time crash.
     * Unresolved entries remain journaled so a later startup can retry them.
     */
    suspend fun cleanupPendingExports(): PendingCleanupReport = withContext(Dispatchers.IO) {
        var removed = 0
        var finalized = 0
        var unresolved = 0
        val entries = runCatching {
            synchronized(journalLock) {
                pendingPreferences.getStringSet(PENDING_KEY, emptySet()).orEmpty().toSet()
            }
        }.getOrElse { return@withContext PendingCleanupReport(0, 0, 1) }
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (entry in activePending) continue
            when (cleanupPendingUri(Uri.parse(entry))) {
                CleanupOutcome.REMOVED, CleanupOutcome.MISSING -> {
                    if (forgetPending(entry)) removed++ else unresolved++
                }
                CleanupOutcome.FINALIZED -> {
                    if (forgetPending(entry)) finalized++ else unresolved++
                }
                CleanupOutcome.UNRESOLVED -> unresolved++
            }
        }
        PendingCleanupReport(removed, finalized, unresolved)
    }

    suspend fun import(uri: Uri, previewMaxEdge: Int = PhotoLimits.PREVIEW_EDGE,
        origin: ImportOrigin = ImportOrigin.UNKNOWN,
        onStage: suspend (ImportDiagnosticInfo) -> Unit = {}): LoadedPhoto {
        require(previewMaxEdge in 1..PhotoLimits.MAX_DECODE_EDGE)
        var produced: LoadedPhoto? = null
        var handedOff = false
        var phase = ImportPhase.PREPARE
        var copiedBytes = 0L
        var sourceBytes:Long?=null
        var sizeHintBytes:Long?=null
        var suffix:dev.filmroom.diagnostics.ImportSuffix?=dev.filmroom.diagnostics.ImportSuffix.UNKNOWN
        val observation=dev.filmroom.diagnostics.ImportObservation()
        var format: dev.filmroom.io.formats.PhotoFormat? = null
        var route: ImportRoute? = null
        fun facts(failure: Throwable? = null) = ImportDiagnosticInfo(origin, phase, copiedBytes,
            format, route, failure?.let(::classifyImportCause),suffix,sourceBytes,sizeHintBytes,
            observation.width,observation.height,observation.memoryStage,
            if(failure?.let(::classifyImportCause)==dev.filmroom.diagnostics.ImportFailure.MEMORY_ALLOCATION && observation.memory?.failure==null)
                (observation.memory?:dev.filmroom.diagnostics.ImportMemoryInfo()).copy(failure=dev.filmroom.diagnostics.ImportMemoryFailure.JVM_ALLOCATION) else observation.memory,observation.rawFormat)
        fun enrich(error: PhotoException):PhotoException = try {
            // Best-effort terminal observation, separately named from decision-time facts.
            runCatching { importMemorySnapshot() }.getOrNull()?.let { snapshot ->
                observation.memory=(observation.memory?:dev.filmroom.diagnostics.ImportMemoryInfo()).copy(
                    failureAvailableBytes=snapshot.availableBytes,failureThresholdBytes=snapshot.thresholdBytes,failureLowMemory=snapshot.lowMemory)
            }
            PhotoException(error.reason, error.message.orEmpty(), error,
                error.cleanupUncertain, error.codecCode, error.codecStage, error.rawTechnical,
                error.uiMessage, error.importDiagnostic ?: facts(error), error.codecDetail)
        } catch(_:Throwable) { error }
        suspend fun emitStage(){
            try { onStage(facts()) } catch(c:CancellationException){throw c} catch(_:Throwable){}
        }
        try {
            val rawBatch=codecs.snapshotRawBatch()
            val result = withContext(Dispatchers.IO+rawBatch+observation) {
                val job = currentCoroutineContext()
                job.ensureActive()
                if (uri.scheme != "content") {
                    throw PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_075e5b2e2761))
                }
                if (!sourceDirectory.isDirectory && !sourceDirectory.mkdirs()) {
                    throw PhotoException(PhotoException.Reason.STORAGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_f145e6465120))
                }
                val privateCopy = File.createTempFile("source-", ".image", sourceDirectory)
                var preview: Bitmap? = null
                var rawSpool: File? = null
                var retained = false
                try {
                    val name = queryName(uri)
                    suffix=if(name.length>=160)dev.filmroom.diagnostics.ImportSuffix.UNKNOWN else dev.filmroom.diagnostics.ImportSuffix.fromName(name)
                    val sizeHint = querySizeHint(uri)
                    sizeHintBytes=sizeHint
                    val sourceModified = SourcePhotoTime.modified(resolver, uri)
                    if (!PhotoLimits.hasImportSpace(sourceDirectory.usableSpace, sizeHint)) {
                        throw PhotoException(PhotoException.Reason.STORAGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_9392b8f7c99e))
                    }
                    var remainingCopyWindow = PhotoLimits.copyWindowBytes(sourceDirectory.usableSpace)
                    observation.memoryStage=dev.filmroom.diagnostics.ImportMemoryStage.SOURCE_COPY
                    phase = ImportPhase.SOURCE_OPEN
                    emitStage()
                    PhotoLocationAccess.open(importContext, uri)?.use { input ->
                        phase = ImportPhase.PRIVATE_OPEN
                        privateCopy.outputStream().buffered().use { output ->
                            phase = ImportPhase.SOURCE_READ
                            emitStage()
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                job.ensureActive()
                                phase = ImportPhase.SOURCE_READ
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                phase = ImportPhase.COPY_ADMISSION
                                if (!PhotoLimits.acceptsCopiedBytes(copiedBytes, count)) {
                                    throw PhotoException(PhotoException.Reason.TOO_LARGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_2673ef9ba447))
                                }
                                if (count.toLong() > remainingCopyWindow) remainingCopyWindow = PhotoLimits.copyWindowBytes(sourceDirectory.usableSpace)
                                if (count.toLong() > remainingCopyWindow) {
                                    throw PhotoException(PhotoException.Reason.STORAGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_276ad5585012))
                                }
                                phase = ImportPhase.PRIVATE_WRITE
                                output.write(buffer, 0, count)
                                copiedBytes += count
                                remainingCopyWindow -= count
                            }
                            phase = ImportPhase.PRIVATE_CLOSE
                        }
                        phase = ImportPhase.SOURCE_CLOSE
                    } ?: throw FileNotFoundException("Photo provider returned no stream")
                    job.ensureActive()
                    sourceBytes=copiedBytes
                    sourceModified?.let { File(privateCopy.path+".imprint-time").writeText(it) }
                    observation.memoryStage=dev.filmroom.diagnostics.ImportMemoryStage.INPUT_METADATA
                    phase = ImportPhase.INPUT_PROBE
                    emitStage()
                    val loaded = loadPrivate(privateCopy, name, previewMaxEdge, job, null,
                        onInput = { header, selectedRoute ->
                            format = header.identified.format
                            route = selectedRoute
                            phase = if (selectedRoute == null) ImportPhase.INPUT_PROBE else ImportPhase.INPUT_PROCESS
                            emitStage()
                        }) { rawSpool = it }
                    loaded.source.rememberOriginalUri(uri.toString())
                    preview = loaded.preview
                    observation.dimensions(loaded.source.originalWidth,loaded.source.originalHeight)
                    observation.memoryStage=dev.filmroom.diagnostics.ImportMemoryStage.COMPLETE
                    phase = ImportPhase.COMPLETE
                    emitStage()
                    produced = loaded
                    retained = true
                    loaded
                } finally {
                    if (!retained) {
                        preview?.recycle()
                        File(privateCopy.path+".imprint-time").delete()
                        privateCopy.delete()
                        rawSpool?.delete()
                    }
                }
            }
            handedOff = true
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoException) {
            throw enrich(e)
        } catch (e: OutOfMemoryError) {
            throw enrich(PhotoException(PhotoException.Reason.TOO_LARGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_5c2432fd4e78), e))
        } catch (e: SecurityException) {
            throw enrich(PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_a064cfbab736), e))
        } catch (e: Exception) {
            throw enrich(PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_a428b047ad8e), e))
        } finally {
            // withContext can be cancelled after the worker produced a result but
            // before the main dispatcher delivers it. Do not leak that result.
            if (!handedOff) produced?.let { it.preview.recycle(); discard(it.source) }
        }
    }

    /** Decode the app-owned original at a stated bound, never secretly upsample it. */
    suspend fun decode(source: PhotoSource, maxEdge: Int): Bitmap {
        require(maxEdge in 1..PhotoLimits.MAX_DECODE_EDGE)
        var produced: Bitmap? = null
        var handedOff = false
        try {
            val result = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                if (!source.localFile.isFile) {
                    throw PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_b769fd8412bb))
                }
                (source.linearFile?.let { LinearSpool.preview(it,source.originalWidth,source.originalHeight,maxEdge,currentCoroutineContext()) } ?: decodeFile(source.localFile, maxEdge, currentCoroutineContext())).also { produced = it }
            }
            handedOff = true
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PhotoException(PhotoException.Reason.TOO_LARGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_660bbeffe8d6), e)
        } catch (e: Exception) {
            throw PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_9c508b3acf89), e)
        } finally {
            if (!handedOff) produced?.recycle()
        }
    }

    /**
     * Reopens only a previously copied file in this app's cache directory.
     * Cache eviction is normal: the UI must ask for reimport if this fails.
     * A failed/cancelled restore never deletes the prior source.
     */
    suspend fun reopen(
        cacheKey: String,
        name: String = dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b50017ae47e),
        previewMaxEdge: Int = PhotoLimits.PREVIEW_EDGE,
        rawCacheKey: String? = null,
        originalUri: String? = null,
    ): LoadedPhoto {
        require(previewMaxEdge in 1..PhotoLimits.MAX_DECODE_EDGE)
        var produced: LoadedPhoto? = null
        var handedOff = false
        try {
            val result = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                job.ensureActive()
                if (!cacheKey.matches(Regex("source-[A-Za-z0-9]+\\.image"))) {
                    throw PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_d408527bac5d))
                }
                val file = File(sourceDirectory, cacheKey)
                if (file.canonicalFile.parentFile != sourceDirectory.canonicalFile ||
                    !file.isFile || file.length() !in 1..PhotoLimits.MAX_COMPRESSED_BYTES) {
                    throw PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_b769fd8412bb))
                }
                val rawFile = rawCacheKey?.takeIf { it.matches(Regex("(?:raw|heic)-source-[0-9a-fA-F-]{36}\\.rgb")) }?.let { File(sourceDirectory,it) }
                loadPrivate(file, PhotoLimits.safeDisplayName(name), previewMaxEdge, job, rawFile, allowDecode=false).also { it.source.rememberOriginalUri(originalUri); produced = it }

            }
            handedOff = true
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PhotoException(PhotoException.Reason.TOO_LARGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_5e9174903275), e)
        } catch (e: Exception) {
            throw PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_63b74410c9d6), e)
        } finally {
            if (!handedOff) produced?.preview?.recycle()
        }
    }

    private suspend fun loadPrivate(file:File,name:String,maxEdge:Int,job:CoroutineContext,existingRaw:File?,allowDecode:Boolean=true,onInput:suspend (PhotoInputHeader,ImportRoute?)->Unit={_,_->},onNewRaw:(File)->Unit={}):LoadedPhoto {
        val header=ByteArray(dev.filmroom.io.formats.PhotoHeaders.MAX_PREFIX_BYTES)
        val count=file.inputStream().use { input ->
            var n=0
            while(n<header.size){job.ensureActive();val read=input.read(header,n,header.size-n);if(read<0)break;if(read==0)continue;n+=read}
            n
        }
        val inputHeader=PhotoInputHeader(header,count)
        onInput(inputHeader,null)
        // Read the original encoded file before HEIC/RAW linearization loses its metadata.
        val sourceTime=File(file.path+".imprint-time").takeIf{it.isFile && it.length() in 1..64}?.let{runCatching{it.readText()}.getOrNull()}
        val captureMetadata=PhotoMetadata.read(file).withImprintFallback(sourceTime,"modified")
        val adapter=inputAdapters.select(inputHeader)
        val route=when(adapter){is RasterInputAdapter->ImportRoute.RASTER;is RawInputAdapter->ImportRoute.RAW;is HeicInputAdapter->ImportRoute.HEIC;else->ImportRoute.OTHER}
        onInput(inputHeader,route)
        return adapter.load(PhotoImportRequest(file,name,maxEdge,job,existingRaw,allowDecode,onNewRaw,sourceDirectory,captureMetadata),inputHeader)
    }

    /** Returns a NEW owned spool for standard output. Original linear-source callers may borrow linearFile separately. */
    suspend fun spoolSized(source:PhotoSource,target:File,maxEdge:Int,onProgress:(Long,Long)->Unit={_,_->}):PhotoLimits.Dimensions {
        val size=source.outputSize(maxEdge);var completed=false
        try{
            withContext(Dispatchers.IO){
                val job=currentCoroutineContext();job.ensureActive()
                privateFile(target);require(!target.exists())
                if(source.linearFile!=null)LinearSpool.resize(source.linearFile,source.originalWidth,source.originalHeight,target,size,job){done,total->onProgress(done.toLong(),total.toLong())}
                else {val bitmap=decodeFile(source.localFile,maxEdge,job);try{LinearSpool.fromBitmap(bitmap,target,job){done,total->onProgress(done.toLong(),total.toLong())}}finally{bitmap.recycle()}}
            }
            completed=true;return size
        }finally{if(!completed)target.delete()}
    }

    suspend fun exportLinear(file:File,width:Int,height:Int,format:ExportFormat,quality:Int=95,onCommitted:(ExportResult)->Unit={},onCleanupUncertain:()->Unit={},outputColorSpace:String="sRGB",onPrepared:(ExportResult)->Unit={},photoMetadata:PhotoMetadata=PhotoMetadata.unavailable(),exportMillis:Long=System.currentTimeMillis(),onEncodedPayload:suspend (File)->Unit={},pngPixelPayload:OutputStream?=null,onMetadataWarning:(String)->Unit={},onWork:(String,Long,Long,Boolean)->Unit={_,_,_,_->}):ExportResult = OperationTrace.run(Operation.EXPORT_ENCODE,SafeInfo(width=width,height=height,encodedPayloadCacheHit=false,metadataIssues=photoMetadata.issues,metadataPhase=MetadataIssuePhase.READ_VALIDATION,captureTimeBasis=photoMetadata.diagnosticDateBasis)) { trace ->
        OutputColorSpace.parse(outputColorSpace)
        val committed:(ExportResult)->Unit={result->trace.committed();onCommitted(result)}
        trace.mark(Stage.ENCODE_START)
        val metadataIssue:(MetadataIssuePhase,MetadataIssue)->Unit={phase,issue->Diagnostics.event(trace.id,Stage.ENCODE_PROGRESS,SafeInfo(metadataIssues=listOf(issue),metadataPhase=phase))}
        if(format==ExportFormat.PNG)return@run exportLinearRgbPng(file,width,height,committed,onCleanupUncertain,outputColorSpace=outputColorSpace,onPrepared=onPrepared,photoMetadata=photoMetadata,exportMillis=exportMillis,onMetadataWarning=onMetadataWarning,pixelPayload=pngPixelPayload,onMetadataIssue=metadataIssue,onWork=onWork)
        val input=privateFile(file)
        require(input.length()==LinearRgb.byteCount(width,height))
        val encoded=File(input.parentFile,"codec-output-${UUID.randomUUID()}.${format.extension}")
        try{
            trace.mark(Stage.NATIVE_ENTER)
            // Preserve lexical input for symlink rejection; native receives canonical paths after scoped validation.
            val report=codecs.encode(file,encoded,width,height,if(format==ExportFormat.TIFF16)"TIFF16" else "JPEG",quality.coerceIn(1,100),outputColorSpace,dev.filmroom.codec.CodecEncodeProgress { done,total,finished -> onWork("encode",done.toLong(),total.toLong(),finished) })
            onWork("encode",1,1,true)
            trace.mark(Stage.NATIVE_EXIT)
            // This is the only cache boundary: complete encoder output, before any capture/GPS/time EXIF.
            currentCoroutineContext().ensureActive()
            // A legacy/fake API may only return {ok:true}; it retains cold behavior, never
            // silently certifies a cache payload. Real native success proves all pixels finite,
            // complete encode, fsync, exact dimensions/ICC/bit depth and normalized orientation.
            if(dev.filmroom.export.EncodedPayloadProof.valid(report,encoded,width,height,format,outputColorSpace))
                onEncodedPayload(encoded)
            currentCoroutineContext().ensureActive()
            applyMetadata(encoded,format,photoMetadata,width,height,outputColorSpace,exportMillis,onMetadataWarning,metadataIssue)
            return@run exportEncoded(width,height,format,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8f8660561ea5),committed,onCleanupUncertain,onPrepared,exportMillis,photoMetadata.mediaStoreCaptureTimeMillis()){out,job->
                encoded.inputStream().buffered().use{stream->val bytes=ByteArray(64*1024);var written=0L;val total=encoded.length();onWork("copy",0,total,false);while(true){job.ensureActive();val n=stream.read(bytes);if(n<0)break;out.write(bytes,0,n);written+=n;onWork("copy",written,total,false)}};true
            }
        }finally{encoded.delete()}
    }

    /** Consumes a fresh job-private COPY, never the cache artifact. Always reapply this job's
     * full metadata, then use the same pending journal, fresh row and commit point as cold export. */
    internal suspend fun exportEncodedPayload(file:File,width:Int,height:Int,format:ExportFormat,
        outputColorSpace:String,photoMetadata:PhotoMetadata,exportMillis:Long,
        onPrepared:(ExportResult)->Unit={},onCommitted:(ExportResult)->Unit={},cacheSourceBackend:dev.filmroom.diagnostics.GpuBackend?=null,onMetadataWarning:(String)->Unit={},onWork:(String,Long,Long,Boolean)->Unit={_,_,_,_->}):ExportResult {
        require(format in setOf(ExportFormat.JPEG,ExportFormat.TIFF16,ExportFormat.PNG))
        val encoded=privateFile(file)
        return OperationTrace.run(Operation.EXPORT_ENCODE,SafeInfo(width=width,height=height,encodedPayloadCacheHit=true,encodedPayloadCacheBytes=encoded.length(),encodedPayloadSourceBackend=cacheSourceBackend)) { trace ->
            currentCoroutineContext().ensureActive()
            if(format==ExportFormat.PNG) {
                val colorSpace=OutputColorSpace.parse(outputColorSpace)
                val profile=if(colorSpace==OutputColorSpace.SRGB)null else outputAssets.open("color-profiles/${colorSpace.iccAsset}").use{it.readBytes()}
                val exif=pngMetadata(photoMetadata,width,height,outputColorSpace,exportMillis,onMetadataWarning){phase,issue->Diagnostics.event(trace.id,Stage.ENCODE_PROGRESS,SafeInfo(metadataIssues=listOf(issue),metadataPhase=phase))}
                return@run exportEncoded(width,height,format,"暗房_原尺寸",{result->trace.committed();onCommitted(result)},
                    {},onPrepared,exportMillis,photoMetadata.mediaStoreCaptureTimeMillis()){out,job->
                    encoded.inputStream().buffered().use { rawInput ->
                        val total=encoded.length();var readBytes=0L
                        val input=object:java.io.FilterInputStream(rawInput) {
                            override fun read():Int {val n=super.read();if(n>=0){readBytes++;onWork("copy",readBytes,total,false)};return n}
                            override fun read(b:ByteArray,off:Int,len:Int):Int {val n=rawInput.read(b,off,len);if(n>0){readBytes+=n;onWork("copy",readBytes,total,false)};return n}
                        }
                        LinearRgbPng.replayPixelPayload(input,out,width,height,colorSpace,profile,exif,exportMillis){job.ensureActive()}
                    };true
                }
            }
            applyMetadata(encoded,format,photoMetadata,width,height,outputColorSpace,exportMillis,onMetadataWarning){phase,issue->Diagnostics.event(trace.id,Stage.ENCODE_PROGRESS,SafeInfo(metadataIssues=listOf(issue),metadataPhase=phase))}
            currentCoroutineContext().ensureActive()
            exportEncoded(width,height,format,dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8f8660561ea5),
                {result->trace.committed();onCommitted(result)},{},onPrepared,exportMillis,photoMetadata.mediaStoreCaptureTimeMillis()){out,job->
                encoded.inputStream().buffered().use{input->val bytes=ByteArray(64*1024);var written=0L;val total=encoded.length();onWork("copy",0,total,false);while(true){job.ensureActive();val n=input.read(bytes);if(n<0)break;out.write(bytes,0,n);written+=n;onWork("copy",written,total,false)}};true
            }
        }
    }

    /** Call only when no worker still reads this source. Does not recycle any bitmap. */
    fun discard(source: PhotoSource) {
        try{source.clearLinearPreview()}finally{source.discardWhenUnused()}
    }

    /**
     * Saves a NEW item under Pictures/暗房; never overwrites the source.
     * Cancellation/failure before commit removes IS_PENDING media. A successful
     * MediaStore update is the commit point. onCommitted runs on the IO worker
     * immediately after that point, even if cancellation wins result delivery.
     * Use that callback to record durable success; never call a committed export
     * "cancelled" merely because its awaiting UI coroutine was later cancelled.
     * onCleanupUncertain also runs on IO, so an awaiting cancellation cannot lose
     * a cleanup warning. Retain it before displaying any "nothing left behind" claim.
     * The caller owns [bitmap] and must keep it alive until this function ends.
     */
    suspend fun export(
        bitmap: Bitmap,
        format: ExportFormat,
        nameBase: String = dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8f8660561ea5),
        quality: Int = 95,
        onCommitted: (ExportResult) -> Unit = {},
        onCleanupUncertain: () -> Unit = {},
    ): ExportResult {
        check(!bitmap.isRecycled) { "Cannot export a recycled bitmap" }
        require(bitmap.width <= PhotoLimits.MAX_DECODE_EDGE && bitmap.height <= PhotoLimits.MAX_DECODE_EDGE) {
            "Bitmap export is capped at 4096 pixels per edge; use original-size streaming PNG"
        }
        return exportEncoded(bitmap.width, bitmap.height, format, nameBase, onCommitted, onCleanupUncertain) { output, _ ->
            bitmap.compress(requireNotNull(format.compression), if (format == ExportFormat.JPEG) quality.coerceIn(1,100) else 100, output)
        }
    }

    /**
     * Writes the original, display-oriented dimensions as linear-sRGB RGB float32
     * little-endian. Destination must be a NEW path in private cache/files (not
     * engine-data). Source and previous spools are never overwritten. A cancelled
     * result handoff deletes this new spool, including the dispatcher-race case.
     */
    suspend fun spoolOriginal(source: PhotoSource, destination: File,onProgress:(Long,Long)->Unit={_,_->}): PhotoLimits.Dimensions {
        val target = privateFile(destination)
        require(!target.exists()) { "Original spool destination must not exist" }
        require(target != source.localFile.canonicalFile) { "Source cannot be overwritten" }
        var completed = false
        var installed = false
        var temporary: File? = null
        try {
            val dimensions = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                job.ensureActive()
                val parent = target.parentFile ?: throw IOException("Missing spool directory")
                if (!parent.isDirectory) throw IOException("Create the private spool directory first")
                val expected = LinearRgb.byteCount(source.originalWidth, source.originalHeight)
                if (parent.usableSpace < expected + 16L * 1024 * 1024) {
                    throw PhotoException(PhotoException.Reason.STORAGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_01b0b11fd332))
                }
                temporary = File.createTempFile("original-spool-", ".part", parent)
                val size = if(source.linearFile!=null){
                    // HEIC and RAW already have canonical display-oriented linear data.
                    require(source.linearFile.length()==expected)
                    source.linearFile.inputStream().use { input ->
                        java.io.FileOutputStream(checkNotNull(temporary)).use { file ->
                            file.buffered().use { output ->
                                val bytes=ByteArray(256*1024);var written=0L;onProgress(0,expected)
                                while(true){job.ensureActive();val n=input.read(bytes);if(n<0)break;output.write(bytes,0,n);written+=n;onProgress(written,expected)}
                                output.flush();file.fd.sync()
                            }
                        }
                    }
                    if(checkNotNull(temporary).length()!=expected)throw IOException("Incomplete linear original spool")
                    PhotoLimits.Dimensions(source.originalWidth,source.originalHeight)
                }else OriginalSpool.write(source.localFile, source.originalWidth, source.originalHeight, checkNotNull(temporary), job){done,total->onProgress(done.toLong(),total.toLong())}
                job.ensureActive()
                java.nio.file.Files.move(checkNotNull(temporary).toPath(), target.toPath())
                installed = true
                size
            }
            completed = true
            return dimensions
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PhotoException(PhotoException.Reason.TOO_LARGE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_5780b5b34a55), e)
        } catch (e: Exception) {
            throw PhotoException(PhotoException.Reason.UNREADABLE, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_ee57ab3f6e70), e)
        } finally {
            temporary?.delete()
            if (!completed && installed) target.delete()
        }
    }

    /** Full-dimension RGB8/sRGB PNG, encoded by rows without a full-size Bitmap. */
    suspend fun exportLinearRgbPng(
        file: File,
        width: Int,
        height: Int,
        onCommitted: (ExportResult) -> Unit = {},
        onCleanupUncertain: () -> Unit = {},
        onProgress: (completedRows: Int, totalRows: Int) -> Unit = { _, _ -> },
        outputColorSpace: String = "sRGB",
        onPrepared: (ExportResult) -> Unit = {},
        photoMetadata: PhotoMetadata = PhotoMetadata.unavailable(),
        exportMillis: Long = System.currentTimeMillis(),
        onMetadataWarning: (String) -> Unit = {},
        pixelPayload: OutputStream? = null,
        onMetadataIssue: (MetadataIssuePhase,MetadataIssue) -> Unit = {_,_->},
        onWork:(String,Long,Long,Boolean)->Unit={_,_,_,_->},
    ): ExportResult {
        val colorSpace=OutputColorSpace.parse(outputColorSpace)
        val profile=if(colorSpace==OutputColorSpace.SRGB)null else outputAssets.open("color-profiles/${colorSpace.iccAsset}").use{it.readBytes()}
        val input = privateFile(file)
        val expected = LinearRgb.byteCount(width, height)
        if (!input.isFile || input.length() != expected) {
            throw PhotoException(PhotoException.Reason.EXPORT, dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_0c0a9b25a114))
        }
        val exif=pngMetadata(photoMetadata,width,height,outputColorSpace,exportMillis,onMetadataWarning,onMetadataIssue)
        return exportEncoded(width,height,ExportFormat.PNG,"暗房_原尺寸",onCommitted,onCleanupUncertain,onPrepared,exportMillis,photoMetadata.mediaStoreCaptureTimeMillis()) { output,job ->
            input.inputStream().buffered(256*1024).use { source ->
                LinearRgbPng.encode(source,width,height,output,checkActive={job.ensureActive()},onRowEncoded={onProgress(it,height);onWork("encode",it.toLong(),height.toLong(),false)},colorSpace=colorSpace,iccProfile=profile,exifMetadata=exif,exportMillis=exportMillis,pixelPayload=pixelPayload)
            };onWork("encode",1,1,true);true
        }
    }

    /** The cold and payload-replay routes share metadata resolution and failure semantics. */
    private fun pngMetadata(photoMetadata:PhotoMetadata,width:Int,height:Int,outputColorSpace:String,exportMillis:Long,
        onMetadataWarning:(String)->Unit,onMetadataIssue:(MetadataIssuePhase,MetadataIssue)->Unit):ByteArray? {
        photoMetadata.warning?.let(onMetadataWarning)
        return try {photoMetadata.exifBytes(width,height,outputColorSpace,exportMillis)} catch(_:Exception) {
            onMetadataIssue(MetadataIssuePhase.WRITE_VALIDATE,MetadataIssue(MetadataField.METADATA,MetadataIssueReason.OUTPUT_VALIDATION_FAILED))
            onMetadataWarning(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_23fcd93377e5));null
        }
    }

    private fun applyMetadata(file:File,format:ExportFormat,metadata:PhotoMetadata,width:Int,height:Int,colorSpace:String,exportMillis:Long,warn:(String)->Unit,issue:(MetadataIssuePhase,MetadataIssue)->Unit) {
        metadata.warning?.let(warn)
        val bytes=try {metadata.exifBytes(width,height,colorSpace,exportMillis)}catch(_:Exception) {
            issue(MetadataIssuePhase.WRITE_VALIDATE,MetadataIssue(MetadataField.METADATA,MetadataIssueReason.OUTPUT_VALIDATION_FAILED))
            warn(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_23fcd93377e5));return
        }
        try { PhotoMetadataContainer.inject(file,format,bytes) }
        catch(_:Exception) {
            // Transactional replacement keeps the valid image; no exception text is recorded.
            issue(MetadataIssuePhase.WRITE_EXCEPTION,MetadataIssue(MetadataField.METADATA,MetadataIssueReason.CONTAINER_WRITE_FAILED))
            warn(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_23fcd93377e5))
        }
    }

    private fun privateFile(file: File): File {
        val canonical = file.canonicalFile
        val path = canonical.path
        require(privateRoots.any { path.startsWith(it.path + File.separator) }) { "Only app-private cache/files are permitted" }
        require(path != engineDataDirectory.path && !path.startsWith(engineDataDirectory.path + File.separator)) { "Engine data cannot be overwritten" }
        return canonical
    }

    private suspend fun exportEncoded(
        width: Int,
        height: Int,
        format: ExportFormat,
        nameBase: String,
        onCommitted: (ExportResult) -> Unit,
        onCleanupUncertain: () -> Unit,
        onPrepared: (ExportResult) -> Unit = {},
        exportMillis: Long = System.currentTimeMillis(),
        captureTimeMillis: Long? = null,
        writeEncoded: (OutputStream, CoroutineContext) -> Boolean,
    ): ExportResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        job.ensureActive()
        val safeBase = PhotoLimits.safeDisplayName(nameBase, dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8f8660561ea5))
            .substringBeforeLast('.').take(64)
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "_").ifBlank { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8f8660561ea5) }
        val filename = "${safeBase}_${exportMillis}_${UUID.randomUUID().toString().take(6)}.${format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${if(format==ExportFormat.TIFF16) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES}/暗房")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.WIDTH, width)
            put(MediaStore.Images.Media.HEIGHT, height)
            // DATE_TAKEN is milliseconds, not seconds; provider may re-index from EXIF.
            captureTimeMillis?.let { put(MediaStore.Images.Media.DATE_TAKEN,it) }
            // DATE_ADDED / DATE_MODIFIED are provider-owned seconds. Do not override them.
        }
        var uri: Uri? = null
        var committed = false
        var finalizeAttempted = false
        var result: ExportResult? = null
        var failure: Throwable? = null
        var cleanupUncertain = false
        try {
            uri = resolver.insert(if(format==ExportFormat.TIFF16) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("MediaStore did not create an output")
            activePending.add(uri.toString())
            // Synchronous journal write before encoding: stop if it cannot be made durable.
            if (!rememberPending(uri.toString())) throw IOException("Could not record pending export")
            resolver.openOutputStream(uri, "w")?.use { raw ->
                val output = CancellationOutputStream(raw, job)
                val encoded = writeEncoded(output, job)
                job.ensureActive()
                if (!encoded || output.bytesWritten == 0L) throw IOException("Image encoder failed")
                output.flush()
            } ?: throw IOException("MediaStore did not open output")
            job.ensureActive()
            result = ExportResult(uri, filename, width, height, format.mimeType)
            // Persist the task→row link before publishing: process death cannot duplicate a saved image.
            onPrepared(checkNotNull(result))
            finalizeAttempted = true
            val updated = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            if (updated != 1) throw IOException("MediaStore did not finalize output")
            committed = true
            // A UI callback failure must not relabel an already saved photo as an IO failure.
            runCatching { onCommitted(checkNotNull(result)) }
            // Commit first, then forget. If forgetting fails, startup sees finalized
            // and preserves the saved photo. No rollback after the commit point.
            forgetPending(uri.toString())
        } catch (t: Throwable) {
            failure = t
        } finally {
            uri?.let { pending ->
                if (!committed) {
                    when (cleanupPendingUri(pending)) {
                        CleanupOutcome.REMOVED, CleanupOutcome.MISSING -> forgetPending(pending.toString())
                        CleanupOutcome.FINALIZED -> {
                            // Binder/update errors can be ambiguous: query established
                            // the row is already saved, so preserve it and report success.
                            if (finalizeAttempted) {
                                committed = true
                                result = ExportResult(pending, filename, width, height, format.mimeType)
                                runCatching { onCommitted(checkNotNull(result)) }
                            } else {
                                cleanupUncertain = true
                                runCatching { onCleanupUncertain() }
                            }
                            forgetPending(pending.toString())
                        }
                        CleanupOutcome.UNRESOLVED -> {
                            cleanupUncertain = true
                            // Keep/retry journaling, including a failure of the first write.
                            rememberPending(pending.toString())
                            runCatching { onCleanupUncertain() }
                        }
                    }
                }
                activePending.remove(pending.toString())
            }
        }
        if (committed) return@withContext checkNotNull(result)
        when (val error = failure) {
            is CancellationException -> if (cleanupUncertain) throw ExportCancellationException(error) else throw error
            is OutOfMemoryError -> throw PhotoException(
                PhotoException.Reason.TOO_LARGE,
                if (cleanupUncertain) dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_b3a7814059d4)
                else dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_4bc6c17bca33),
                error, cleanupUncertain,
            )
            is Exception -> throw PhotoException(
                PhotoException.Reason.EXPORT,
                if (cleanupUncertain) dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_67f346d97344)
                else dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_a1c12237b4d9),
                error, cleanupUncertain,
                codecCode = (error as? PhotoException)?.codecCode,
                codecStage = (error as? PhotoException)?.codecStage,
                rawTechnical = (error as? PhotoException)?.rawTechnical,
                codecDetail = (error as? PhotoException)?.codecDetail,
            )
            null -> throw IOException("Export ended without a result")
            else -> throw error
        }
    }

    /** 队列恢复只认已发布的本应用导出行；查询失败必须停止，不能猜测后重复保存。 */
    suspend fun committedExport(uriText:String,width:Int,height:Int,format:ExportFormat):ExportResult? = withContext(Dispatchers.IO) {
        val uri=Uri.parse(uriText)
        require(uri.scheme=="content" && uri.authority==MediaStore.AUTHORITY)
        resolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.OWNER_PACKAGE_NAME),null,null,null)?.use { cursor ->
            if(!cursor.moveToFirst())return@withContext null
            require(cursor.getString(2)==packageName) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b447e7ccf3ec) }
            if(cursor.getInt(0)!=0)return@withContext null
            ExportResult(uri,cursor.getString(1),width,height,format.mimeType)
        } ?: throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5dc658ae62f8))
    }

    private enum class CleanupOutcome { REMOVED, MISSING, FINALIZED, UNRESOLVED }

    private fun rememberPending(entry: String): Boolean = runCatching {
        synchronized(journalLock) {
            val entries = pendingPreferences.getStringSet(PENDING_KEY, emptySet()).orEmpty().toMutableSet()
            entries.add(entry)
            pendingPreferences.edit().putStringSet(PENDING_KEY, entries).commit()
        }
    }.getOrDefault(false)

    private fun forgetPending(entry: String): Boolean = runCatching {
        synchronized(journalLock) {
            val entries = pendingPreferences.getStringSet(PENDING_KEY, emptySet()).orEmpty().toMutableSet()
            entries.remove(entry)
            pendingPreferences.edit().putStringSet(PENDING_KEY, entries).commit()
        }
    }.getOrDefault(false)

    /** Selection rechecks ownership and pending state atomically with deletion. */
    private fun cleanupPendingUri(uri: Uri): CleanupOutcome {
        val parts = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY ||
            uri.query != null || uri.fragment != null ||
            !((parts.size==4 && parts[0]==MediaStore.VOLUME_EXTERNAL_PRIMARY && parts[1]=="images" && parts[2]=="media" && parts[3].toLongOrNull()?.let{it>0}==true) ||
              (parts.size==3 && parts[0]==MediaStore.VOLUME_EXTERNAL_PRIMARY && parts[1]=="downloads" && parts[2].toLongOrNull()?.let{it>0}==true))) {
            return CleanupOutcome.UNRESOLVED
        }
        return try {
            fun inspect(): CleanupOutcome = resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.OWNER_PACKAGE_NAME),
                null, null, null,
            )?.use { cursor ->
                when {
                    !cursor.moveToFirst() -> CleanupOutcome.MISSING
                    cursor.getString(1) != packageName -> CleanupOutcome.UNRESOLVED
                    cursor.getInt(0) == 0 -> CleanupOutcome.FINALIZED
                    cursor.getInt(0) == 1 -> CleanupOutcome.REMOVED // candidate, not yet removed
                    else -> CleanupOutcome.UNRESOLVED
                }
            } ?: CleanupOutcome.UNRESOLVED
            val state = inspect()
            if (state != CleanupOutcome.REMOVED) state
            else {
                val deleted = resolver.delete(
                    uri,
                    "${MediaStore.Images.Media.IS_PENDING}=1 AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=?",
                    arrayOf(packageName),
                )
                if (deleted == 1) CleanupOutcome.REMOVED
                else when (val rechecked = inspect()) {
                    CleanupOutcome.REMOVED -> CleanupOutcome.UNRESOLVED
                    else -> rechecked
                }
            }
        } catch (_: Exception) {
            CleanupOutcome.UNRESOLVED
        }
    }

    private fun queryName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) PhotoLimits.safeDisplayName(cursor.getString(0)) else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b50017ae47e)
        } ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b50017ae47e)
    }.getOrDefault(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b50017ae47e))

    private fun querySizeHint(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
        }
    }.getOrNull()

    private class CancellationOutputStream(output: OutputStream, private val job: CoroutineContext) : FilterOutputStream(output) {
        var bytesWritten: Long = 0
            private set

        override fun write(value: Int) {
            job.ensureActive()
            out.write(value)
            bytesWritten++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            job.ensureActive()
            out.write(bytes, offset, length)
            bytesWritten += length
        }
    }
}

/** Shared bounded, oriented sRGB decoder for import, fit and canonical metering. */
internal fun decodeFile(
    file: File,
    maxEdge: Int,
    job: CoroutineContext,
    onAlphaComposite: (Boolean) -> Unit = {},
    onDimensions: (Int, Int) -> Unit = { _, _ -> },
): Bitmap {
    job.ensureActive()
    var decoded: Bitmap? = null
    try {
        job[dev.filmroom.diagnostics.ImportObservation]?.memoryStage=dev.filmroom.diagnostics.ImportMemoryStage.RASTER_PLATFORM
        decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            job.ensureActive()
            val width = info.size.width
            val height = info.size.height
            job[dev.filmroom.diagnostics.ImportObservation]?.dimensions(width,height)
            PhotoLimits.sourceFailure(width.toLong(), height.toLong())?.let { failure ->
                val invalid = failure == PhotoLimits.SourceFailure.INVALID
                val resource = when(failure) {
                    PhotoLimits.SourceFailure.INVALID -> dev.filmroom.R.string.text_199d9706e7a1
                    PhotoLimits.SourceFailure.EDGE -> dev.filmroom.R.string.image_edge_limit_30000
                    PhotoLimits.SourceFailure.PIXELS -> dev.filmroom.R.string.text_d591741a5a6e
                }
                throw PhotoException(if(invalid) PhotoException.Reason.UNREADABLE else PhotoException.Reason.TOO_LARGE, dev.filmroom.i18n.UiMessage.of(resource))
            }
            onDimensions(width, height)
            val size = PhotoLimits.fit(width, height, maxEdge)
            if(info.isAnimated)throw PhotoException(PhotoException.Reason.UNSUPPORTED,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_3bf690c56856))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(size.width, size.height)
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.isMutableRequired = false
            // Do not accept truncated/corrupt partial decodes as valid imports.
            decoder.setOnPartialImageListener { false }
        }
        job.ensureActive()
        if (decoded.config != Bitmap.Config.ARGB_8888) {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("Could not allocate sRGB image")
            decoded.recycle()
            decoded = copy
        }
        job.ensureActive()
        val hasAlpha = decoded.hasAlpha()
        if (hasAlpha) {
            // RGB engine cannot represent alpha. Composite; never simply drop
            // alpha (which would expose hidden RGB or black transparent pixels).
            var opaque: Bitmap? = null
            try {
                opaque = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded, 0f, 0f, null)
                }
                opaque.setHasAlpha(false)
                decoded.recycle()
                decoded = opaque
                opaque = null
            } finally { opaque?.recycle() }
        }
        onAlphaComposite(hasAlpha)
        job.ensureActive()
        return decoded
    } catch (t: Throwable) {
        decoded?.recycle()
        throw t
    }
}
