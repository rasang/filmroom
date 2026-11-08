package dev.filmroom.effects

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.filmroom.livephoto.*
import dev.filmroom.livephoto.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.UUID

/** Native animation -> private AVC file -> existing LivePhoto SDK packager -> MediaStore. */
object EffectLivePhotoExporter {
    private val exportLock = Mutex()
    data class Preflight(val selection: ProfileSelection) {
        val status get() = selection.status
        val unsupported get() = selection.selectedProfile == null || status == SupportStatus.UNSUPPORTED
    }
    private fun request(input: Uri, gallery: String? = null, profile: LivePhotoProfile? = null, maxLongEdge: Int = 1080) = VideoToLiveRequest(
        input = input, profile = profile, durationMs = 3000, coverOffsetMs = 2900, keepAudio = false,
        maxLongEdge = maxLongEdge, allowExperimentalProfiles = true, targetGalleryPackage = gallery)

    suspend fun preflight(context: Context, gallery: String? = null): Preflight = withContext(Dispatchers.IO) {
        Preflight(forDirectAttempt(NormalizedLivePhotoSdk(context).resolve(request(Uri.fromFile(File(context.cacheDir, "effect-live-preflight.mp4")), gallery))))
    }

    /** Retain negative evidence for UI styling but permit an intentional retry with a writable format. */
    internal fun forDirectAttempt(selection: ProfileSelection): ProfileSelection =
        if (selection.selectedProfile != null) selection else selection.copy(
            selectedProfile = selection.candidates.firstOrNull { it.profile != LivePhotoProfile.VIVO_PAIR_LEGACY }?.profile,
            reason = SelectionReason.MANUAL)

    /** The export button is itself the attempt action; no compatibility claim is made. */
    suspend fun export(context: Context, kind: EffectKind, source: Bitmap, result: Bitmap,
        preflight: Preflight, slow: Boolean = false,
        cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {},
        quality: EffectExportQuality = EffectExportQuality.ORIGINAL,
        requestedSize: EffectExportSize? = null,
        onResolution: (EffectExportReport) -> Unit = {}): ConversionResult {
        require(preflight.selection.selectedProfile != null) { "没有可生成的 Live Photo 格式" }
        check(exportLock.tryLock()) { "Live Photo export already running" }
        val app = context.applicationContext
        val video = File(app.cacheDir, "effect-live-${UUID.randomUUID()}.mp4")
        var rendered = false
        var resolution: EffectExportReport? = null
        try {
            val sdk = NormalizedLivePhotoSdk(app)
            val recovery = sdk.recover()
            check(recovery.failures.isEmpty()) { "上次 Live Photo 保存尚未清理完成，请稍后重试" }
            val coroutine = currentCoroutineContext()
            var attemptSize=requestedSize
            var originalRequest:EffectExportSize?=null
            var coverRetried=false
            while(true) {
            withContext(Dispatchers.IO) {
                EffectVideoExporter.export(app, kind, source, result,
                    onProgress = { onProgress(it * .75f) },
                    cancelled = { cancelled() || !coroutine.isActive }, slow = slow, privateOutput = video, quality=quality, requestedSize=attemptSize, onResolution={resolution=it})
                rendered = true
            }
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Live Photo export cancelled")
            val actualRequest=request(Uri.fromFile(video), preflight.selection.environment?.galleryPackage,
                preflight.selection.selectedProfile, requireNotNull(resolution).actual.longEdge)
            // Evidence keys include dimensions. Resolve again for the actual encoded size, never
            // reuse the 1080 preflight key as proof of high-resolution gallery compatibility.
            val actualSelection=sdk.resolve(actualRequest)
            originalRequest=originalRequest ?: requireNotNull(resolution).requested
            try {
            return sdk.convert(actualRequest, actualSelection, cancelled) {
                onProgress(when(it.stage) {
                    ConversionStage.PROBING -> .76f
                    ConversionStage.EXTRACTING_COVER -> .80f
                    ConversionStage.PACKAGING -> .85f
                    ConversionStage.VERIFYING -> .90f
                    ConversionStage.PUBLISHING -> .95f
                    ConversionStage.COMPLETE -> 1f
                    else -> .75f
                })
            }.also { resolution?.let { report -> runCatching { onResolution(report.copy(
                requested=requireNotNull(originalRequest),fallback=report.fallback || coverRetried)) } } }
            } catch(error:CoverDecodeException) {
                coroutine.ensureActive()
                if(cancelled())throw CancellationException("Live Photo export cancelled")
                val next=nextCoverAttempt(requireNotNull(resolution).actual) ?: throw error
                // SDK has not reached packaging/publication for this exception. Remove only our
                // private normalized video, then render the smaller scene and retry its decoder.
                check(video.delete() || !video.exists()) { "Cannot remove failed private Live Photo video" }
                rendered=false;attemptSize=next;coverRetried=true
            }
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { if (rendered) video.delete() }; exportLock.unlock() }
    }

    internal fun nextCoverAttempt(actual:EffectExportSize):EffectExportSize? =
        EffectExportResolution.candidates(actual,actual,actual.longEdge).firstOrNull{it.longEdge<actual.longEdge}

    suspend fun confirmGalleryResult(context: Context, result: ConversionResult, supported: Boolean) =
        NormalizedLivePhotoSdk(context).compatibility.recordGalleryResult(result,
            if (supported) SupportStatus.SUPPORTED else SupportStatus.UNSUPPORTED)
}
