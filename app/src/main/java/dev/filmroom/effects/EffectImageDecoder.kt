package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import dev.filmroom.export.ExportJob
import dev.filmroom.export.RawSourcePreviewStore
import dev.filmroom.io.PhotoRepository
import dev.filmroom.io.PhotoSource
import dev.filmroom.io.LoadedPhoto
import dev.filmroom.io.formats.PhotoFormat
import dev.filmroom.io.formats.PhotoHeaders
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.IOException

/** Operation-local ownership. Export reuses the native spool between bounds and pixels;
 * callers own returned bitmaps and must close this decoder only after their reads finish.
 * A successful platform decode is not proof that a RAW's full-resolution image was used.
 * RAW source reads explicitly prefer our normalized source preview/native decoder. */
internal class EffectImageDecoder(
    context: Context,
    private val correlationId:Long = EffectCorrelation.next(),
    private val cancelled:()->Boolean = { false },
    private val systemRead: ((String, Int, (String) -> Unit, (EffectExportSize) -> Unit) -> Bitmap)? = null,
    private val importRaw: (suspend (Uri, Int) -> LoadedPhoto)? = null,
    private val decodeRaw: (suspend (PhotoSource, Int) -> Bitmap)? = null,
    private val discardRaw: ((PhotoSource) -> Unit)? = null,
    private val containerCheck: (suspend (String) -> Boolean)? = null,
    private val sourceRecord: ExportJob? = null,
    private val preferNativeRaw: Boolean = false,
    private val sourcePreviewRead: ((ExportJob, Int, (Int, Int) -> Unit) -> Bitmap?)? = null,
) : AutoCloseable {
    private val app = context.applicationContext
    private val repository by lazy { PhotoRepository(app) }
    private val nativeSources = mutableMapOf<String, PhotoSource>()
    private val previewSources = mutableSetOf<String>()
    internal fun usesPersistentPreview(uri:String)=uri in previewSources

    suspend fun bounds(uri: String, allowRawFallback: Boolean = true,
                       report: (String) -> Unit = {}): EffectExportSize {
        nativeSources[uri]?.let { return EffectExportSize(it.originalWidth, it.originalHeight) }
        var size: EffectExportSize? = null
        val bitmap = read(uri, 1, allowRawFallback, report) { size = it }
        bitmap.recycle()
        return requireNotNull(size)
    }

    suspend fun read(uri: String, maxEdge: Int, allowRawFallback: Boolean = true,
                     report: (String) -> Unit = {}, onSize: (EffectExportSize) -> Unit = {}): Bitmap {
        require(maxEdge in 1..6144)
        currentCoroutineContext().ensureActive()
        nativeSources[uri]?.let { return withExportCancellation { readNative(it, maxEdge, onSize) } }
        if (allowRawFallback) {
            sourceRecord?.takeIf { it.originalUri == uri }?.let { record ->
                val size: (Int, Int) -> Unit = { w, h -> onSize(EffectExportSize(w, h)) }
                val cached = if (sourcePreviewRead != null) sourcePreviewRead.invoke(record, maxEdge, size)
                    else RawSourcePreviewStore.read(app, record, maxEdge, size)
                if (cached != null) {
                    try {
                        if (cancelled()) throw CancellationException("Image decode cancelled")
                        currentCoroutineContext().ensureActive()
                        previewSources.add(uri)
                        report("image/jpeg")
                        return cached
                    } catch (error: Throwable) { cached.recycle(); throw error }
                }
            }
            // Old records/missing previews must not accept a platform RAW thumbnail whose
            // outer container orientation may have been ignored by the system codec.
            if (preferNativeRaw) {
                val format = containerCheck?.invoke(uri)?.let { if(it)PhotoFormat.TIFF else PhotoFormat.UNKNOWN }
                    ?: containerFormat(uri)
                if (isRawContainer(format)) {
                    try { return importNative(uri, maxEdge, report, onSize) }
                    catch (error: dev.filmroom.io.PhotoException) {
                        // TIFF is only a routing hint. A content-validated NOT-RAW result may
                        // still be a regular TIFF supported by ImageDecoder. Never hide
                        // RAW camera/variant, resource, permission or cancellation failures.
                        if (format != PhotoFormat.TIFF || error.codecCode != CodecErrorCode.UNSUPPORTED_FORMAT ||
                            error.cleanupUncertain || !canRetry(error)) throw error
                        val bitmap = systemRead?.invoke(uri,maxEdge,report,onSize) ?: readPlatform(uri,maxEdge,report,onSize)
                        try { currentCoroutineContext().ensureActive(); return bitmap }
                        catch (t:Throwable) { bitmap.recycle(); throw t }
                    }
                }
            }
        }
        try {
            val bitmap = systemRead?.invoke(uri,maxEdge,report,onSize) ?: readPlatform(uri,maxEdge,report,onSize)
            try { currentCoroutineContext().ensureActive(); return bitmap }
            catch (error: Throwable) { bitmap.recycle(); throw error }
        } catch (error: IOException) {
            // Unsupported formats can fail during createSource/decode setup as plain IOException.
            // Do not inspect exception text or use provider MIME/filename as format evidence.
            if (!allowRawFallback || !canRetry(error)) throw error
            currentCoroutineContext().ensureActive()
            if (!(containerCheck?.invoke(uri) ?: hasRawContainer(uri))) throw error
            return importNative(uri, maxEdge, report, onSize)
        }
    }

    private suspend fun importNative(uri:String, maxEdge:Int, report:(String)->Unit,
                                     onSize:(EffectExportSize)->Unit):Bitmap {
        // Adjacent-page preloads must not start several full native RAW imports together.
        return withExportCancellation { EffectExportLog.trace(EffectExportScope.INPUT_DECODE,correlationId) { log ->
                log.phase(EffectExportStep.DECODE_SOURCE)
                log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.RAW_FALLBACK))
                nativeImportLock.withLock {
                currentCoroutineContext().ensureActive()
                val nativeEdge=maxEdge.coerceAtMost(dev.filmroom.io.PhotoLimits.MAX_DECODE_EDGE)
                val loaded = importRaw?.invoke(Uri.parse(uri), nativeEdge) ?: repository.import(Uri.parse(uri), nativeEdge)
                var handedOff = false
                try {
                    currentCoroutineContext().ensureActive()
                    onSize(EffectExportSize(loaded.source.originalWidth, loaded.source.originalHeight))
                    report(loaded.source.mimeType)
                    log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.INPUT_DECODED,
                        sourceWidth=loaded.source.originalWidth,sourceHeight=loaded.source.originalHeight,
                        inputFormat=EffectInputFormat.fromDecodedHeader(loaded.source.mimeType)))
                    nativeSources[uri] = loaded.source
                    handedOff = true
                    loaded.preview
                } finally {
                    if (!handedOff) { loaded.preview.recycle(); discard(loaded.source) }
                }
                }
            } }
    }

    private suspend fun withExportCancellation(block:suspend ()->Bitmap):Bitmap {
        var produced:Bitmap?=null
        var delivered=false
        try {
            val result=coroutineScope {
                val scope=this
                val watcher=launch {
                    while(isActive) {
                        if(cancelled()) { scope.cancel(CancellationException("Image decode cancelled")); break }
                        delay(50)
                    }
                }
                try {
                    if(cancelled())throw CancellationException("Image decode cancelled")
                    currentCoroutineContext().ensureActive()
                    block().also { produced=it }
                } finally { watcher.cancel() }
            }
            delivered=true
            return result
        } finally {
            if(!delivered)produced?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    private fun readPlatform(uri:String, maxEdge:Int, report:(String)->Unit, onSize:(EffectExportSize)->Unit):Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
            report(info.mimeType)
            onSize(EffectExportSize(info.size.width, info.size.height))
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }

    private fun discard(source:PhotoSource) { if(discardRaw!=null)discardRaw.invoke(source) else repository.discard(source) }

    private suspend fun readNative(source: PhotoSource, edge: Int, onSize: (EffectExportSize) -> Unit): Bitmap {
        onSize(EffectExportSize(source.originalWidth, source.originalHeight))
        val nativeEdge=edge.coerceAtMost(dev.filmroom.io.PhotoLimits.MAX_DECODE_EDGE)
        return decodeRaw?.invoke(source, nativeEdge) ?: repository.decode(source, nativeEdge)
    }

    private suspend fun hasRawContainer(uri:String)=isRawContainer(containerFormat(uri))

    private suspend fun containerFormat(uri: String): PhotoFormat {
        val prefix = ByteArray(PhotoHeaders.MAX_PREFIX_BYTES)
        var count = 0
        app.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            while (count < prefix.size) {
                currentCoroutineContext().ensureActive()
                val n = input.read(prefix, count, prefix.size - count)
                if (n < 0) break
                if (n == 0) break
                count += n
            }
        } ?: throw FileNotFoundException("Photo provider returned no stream")
        return PhotoHeaders.sniff(prefix, count).format
    }

    override fun close() {
        nativeSources.values.forEach { discard(it) }
        nativeSources.clear()
        previewSources.clear()
    }

    companion object {
        private val nativeImportLock = Mutex()
        internal fun isRawContainer(format: PhotoFormat) =
            format == PhotoFormat.CAMERA_RAW || format == PhotoFormat.DNG || format == PhotoFormat.TIFF
        internal fun canRetry(error: IOException): Boolean {
            if (error is FileNotFoundException) return false
            if (error is ImageDecoder.DecodeException && error.error == ImageDecoder.DecodeException.SOURCE_EXCEPTION) return false
            // Preserve cancellation, access and memory failures even when wrapped by a provider.
            var cause: Throwable? = error.cause
            repeat(8) {
                if (cause is SecurityException || cause is FileNotFoundException ||
                    cause is java.util.concurrent.CancellationException || cause is OutOfMemoryError) return false
                cause = cause?.cause
            }
            return true
        }
    }
}
