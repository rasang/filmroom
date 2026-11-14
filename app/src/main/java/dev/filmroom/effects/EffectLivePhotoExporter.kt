package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.filmroom.io.PhotoMetadata
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
        val deviceUnsupported get() = selection.deviceExportBlockedReason != null
        val unsupported get() = deviceUnsupported || selection.selectedProfile == null || status == SupportStatus.UNSUPPORTED
    }
    private fun request(input: Uri, gallery: String? = null, profile: LivePhotoProfile? = null, maxLongEdge: Int = 1080) = VideoToLiveRequest(
        input = input, profile = profile, durationMs = 3000, coverOffsetMs = 2900, keepAudio = true,
        maxLongEdge = maxLongEdge, allowExperimentalProfiles = true, targetGalleryPackage = gallery)

    suspend fun preflight(context: Context, gallery: String? = null, correlationId:Long=EffectCorrelation.next(),parentCorrelationId:Long?=null): Preflight = withContext(Dispatchers.IO) {
        EffectExportLog.trace(EffectExportScope.PREFLIGHT,correlationId,parentCorrelationId) { log ->
            log.phase(EffectExportStep.LIVE_RESOLVE)
            Preflight(forDirectAttempt(NormalizedLivePhotoSdk(context).resolve(request(Uri.fromFile(File(context.cacheDir, "effect-live-preflight.mp4")), gallery)))).also {
                log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.PREFLIGHT_RESOLVED,
                    profileIndex=it.selection.selectedProfile?.ordinal,supportIndex=it.status.ordinal))
            }
        }
    }

    /** Retain negative evidence for UI styling but permit an intentional retry with a writable format. */
    internal fun forDirectAttempt(selection: ProfileSelection): ProfileSelection =
        if (Preflight(selection).deviceUnsupported || selection.selectedProfile != null) selection else selection.copy(
            selectedProfile = selection.candidates.firstOrNull { it.profile != LivePhotoProfile.VIVO_PAIR_LEGACY }?.profile,
            reason = SelectionReason.MANUAL)

    /** The export button is itself the attempt action; no compatibility claim is made. */
    suspend fun export(context: Context, kind: EffectKind, source: Bitmap, result: Bitmap,
        preflight: Preflight, slow: Boolean = false,
        cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {},
        quality: EffectExportQuality = EffectExportQuality.ORIGINAL,
        requestedSize: EffectExportSize? = null,
        onResolution: (EffectExportReport) -> Unit = {},
        readout: EffectCameraReadout = EffectCameraReadout(),
        motionSource: EffectMotionSource? = null, motionAudioFile: File? = null,
        photoMetadata: PhotoMetadata = PhotoMetadata.unavailable(), onCommitted:(ConversionResult)->Unit = {}, onPrepared:(List<Uri>,List<String>)->Unit = {_,_->}, correlationId:Long=EffectCorrelation.next(),parentCorrelationId:Long?=null): ConversionResult = EffectExportLog.trace(EffectExportScope.LIVE_PHOTO,correlationId,parentCorrelationId) { log ->
        require(!preflight.deviceUnsupported) { preflight.selection.deviceExportBlockedReason.orEmpty() }
        require(preflight.selection.selectedProfile != null) { "没有可生成的 Live Photo 格式" }
        log.gps(GpsDiagnosticInfo(phase=GpsDiagnosticPhase.EXPORT_SNAPSHOT,snapshot=photoMetadata.diagnosticGpsPresence))
        val captureDates=photoMetadata.livePhotoDates(System.currentTimeMillis())
        check(exportLock.tryLock()) { "Live Photo export already running" }
        val app = context.applicationContext
        val video = File(app.cacheDir, "effect-live-${UUID.randomUUID()}.mp4")
        var rendered = false
        var resolution: EffectExportReport? = null
        try {
            val sdk = NormalizedLivePhotoSdk(app)
            log.phase(EffectExportStep.LIVE_RECOVER)
            val recovery = sdk.recover()
            log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.RECOVERY_COMPLETE,count=recovery.failures.size))
            check(recovery.failures.isEmpty()) { "上次 Live Photo 保存尚未清理完成，请稍后重试" }
            val coroutine = currentCoroutineContext()
            var attemptSize=requestedSize
            var originalRequest:EffectExportSize?=null
            var coverRetried=false
            while(true) {
            withContext(Dispatchers.IO) {
                EffectVideoExporter.export(app, kind, source, result,
                    onProgress = { onProgress(it * .75f) },
                    cancelled = { cancelled() || !coroutine.isActive }, slow = slow, privateOutput = video, quality=quality, requestedSize=attemptSize, onResolution={resolution=it},readout=readout,motionSource=motionSource,motionAudioFile=motionAudioFile,correlationId=correlationId,parentCorrelationId=parentCorrelationId)
                rendered = true
            }
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Live Photo export cancelled")
            val actualRequest=request(Uri.fromFile(video), preflight.selection.environment?.galleryPackage,
                preflight.selection.selectedProfile, requireNotNull(resolution).actual.longEdge).copy(
                captureDates=captureDates,
                captureExif=photoMetadata.livePhotoCaptureExif(),
                captureTimeMillis=if(captureDates.original!=null)photoMetadata.mediaStoreCaptureTimeMillis() else null)
            // Evidence keys include dimensions. Resolve again for the actual encoded size, never
            // reuse the 1080 preflight key as proof of high-resolution gallery compatibility.
            log.phase(EffectExportStep.LIVE_RESOLVE)
            val actualSelection=sdk.resolve(actualRequest)
            log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.PREFLIGHT_RESOLVED,profileIndex=actualSelection.selectedProfile?.ordinal,supportIndex=actualSelection.status.ordinal))
            originalRequest=originalRequest ?: requireNotNull(resolution).requested
            try {
            log.phase(EffectExportStep.LIVE_PROBE)
            return@trace sdk.convert(actualRequest, actualSelection, cancelled, onPrepared=onPrepared) {
                // This callback runs after durable publication, before the IO -> caller handoff.
                // A cancellation at that handoff may discard the return value, but not the saved asset.
                if(it.stage==ConversionStage.COMPLETE)log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.LIVE_COMMITTED,
                    step=EffectExportStep.LIVE_PUBLISH,elapsedMs=log.elapsed()))
                log.phase(when(it.stage){
                    ConversionStage.PROBING -> EffectExportStep.LIVE_PROBE
                    ConversionStage.EXTRACTING_COVER -> EffectExportStep.COVER
                    ConversionStage.PACKAGING -> EffectExportStep.LIVE_PACKAGE
                    ConversionStage.VERIFYING -> EffectExportStep.LIVE_VERIFY
                    ConversionStage.PUBLISHING -> EffectExportStep.LIVE_PUBLISH
                    else -> log.step
                })
                onProgress(when(it.stage) {
                    ConversionStage.PROBING -> .76f
                    ConversionStage.EXTRACTING_COVER -> .80f
                    ConversionStage.PACKAGING -> .85f
                    ConversionStage.VERIFYING -> .90f
                    ConversionStage.PUBLISHING -> .95f
                    ConversionStage.COMPLETE -> 1f
                    else -> .75f
                })
            }.also { saved -> onCommitted(saved)
                // parts/publication order is image then companion video. Never inspect the MP4 as EXIF.
                saved.publishedUris.firstOrNull()?.let { imageUri ->
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) {
                            runCatching { log.gps(photoMetadata.gpsReadback { app.contentResolver.openInputStream(imageUri)?.use(PhotoMetadata::readCaptureMetadata) }) }
                        }
                    }
                }
                resolution?.let { report ->
                log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.LIVE_SAVED,
                    requestedWidth=requireNotNull(originalRequest).width,requestedHeight=requireNotNull(originalRequest).height,
                    width=report.actual.width,height=report.actual.height))
                runCatching { onResolution(report.copy(
                requested=requireNotNull(originalRequest),fallback=report.fallback || coverRetried)) } } }
            } catch(error:CoverDecodeException) {
                coroutine.ensureActive()
                if(cancelled())throw CancellationException("Live Photo export cancelled")
                val actual=requireNotNull(resolution).actual
                val next=nextCoverAttempt(actual)
                log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.COVER_RETRY,
                    if(next==null)EffectExportReason.COVER_RETRY_EXHAUSTED else EffectExportReason.COVER_DECODE_FAILED,
                    EffectExportStep.COVER,requestedWidth=actual.width,requestedHeight=actual.height,
                    width=next?.width,height=next?.height))
                if(next==null)throw error
                // SDK has not reached packaging/publication for this exception. Remove only our
                // private normalized video, then render the smaller scene and retry its decoder.
                check(video.delete() || !video.exists()) { "Cannot remove failed private Live Photo video" }
                rendered=false;attemptSize=next;coverRetried=true
            }
            }
            error("Unreachable Live export loop completion")
        } finally {
            // Keep the caller dispatcher on the outer NonCancellable handoff. Switching directly
            // to NonCancellable + IO can throw on return to an already-cancelled caller after save.
            try { withContext(NonCancellable) { withContext(Dispatchers.IO) {
                if (rendered && !video.delete() && video.exists()) log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.CLEANUP_FAILED))
            } } } finally { exportLock.unlock() }
        }
    }

    internal fun nextCoverAttempt(actual:EffectExportSize):EffectExportSize? =
        EffectExportResolution.candidates(actual,actual,actual.longEdge).firstOrNull{it.longEdge<actual.longEdge}

    suspend fun confirmGalleryResult(context: Context, result: ConversionResult, supported: Boolean) =
        NormalizedLivePhotoSdk(context).compatibility.recordGalleryResult(result,
            if (supported) SupportStatus.SUPPORTED else SupportStatus.UNSUPPORTED)
}
