package dev.filmroom.livephoto

import android.content.Context
import android.net.Uri
import dev.filmroom.livephoto.core.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Filmroom adapter for its own already-encoded SDR AVC animation; no second lossy transcode. */
class NormalizedLivePhotoSdk(context: Context) {
    private val app = context.applicationContext
    val compatibility = LivePhotoCompatibility(app)
    private val assetRoot = File(app.noBackupFilesDir, "livephoto-sdk/assets")
    private val publisher = PublicationCoordinator(File(app.noBackupFilesDir, "livephoto-sdk/publications"), AndroidMediaSink(app))

    suspend fun resolve(request: VideoToLiveRequest) = compatibility.resolve(request)
    suspend fun recover() = withContext(Dispatchers.IO) { publisher.recoverInterrupted() }

    suspend fun convert(request: VideoToLiveRequest, accepted: ProfileSelection, cancelled: () -> Boolean = { false }, onProgress: (ConversionProgress) -> Unit = {}): ConversionResult = CommittedResultHandoff.run { committed ->
        require(request.startMs == 0L && request.durationMs == 3000L && request.keepAudio && request.saveToGallery)
        require(request.input.scheme == "file")
        val file = File(requireNotNull(request.input.path))
        require(file.canonicalFile.parentFile == app.cacheDir.canonicalFile && file.name.startsWith("effect-live-")) { "Only app-rendered private videos are accepted" }
        val current = resolve(request)
        require(current.environment?.key == accepted.environment?.key && current.selectedProfile == accepted.selectedProfile && current.status == accepted.status) { "相册环境已变化，请重新检查后导出。" }
        val profile = requireNotNull(current.selectedProfile) { "没有可用的 Live Photo 格式" }
        // Explicit button-triggered retries may target a previously unsupported gallery profile.
        require(!request.requireConfirmedSupport || current.status == SupportStatus.SUPPORTED)
        val coroutine = currentCoroutineContext()
        fun check() { coroutine.ensureActive(); if (cancelled()) throw CancellationException("Live Photo export cancelled") }
        fun emit(stage: ConversionStage) { try { onProgress(ConversionProgress(stage)) } catch (_: RuntimeException) {} }
        val scratch = File(app.cacheDir, "live-cover-${UUID.randomUUID()}")
        val target = File(assetRoot, UUID.randomUUID().toString())
        var packaged = false
        var scratchCreated = false
        try {
            check(); emit(ConversionStage.PROBING)
            val info = MediaProbe.normalized(file)
            NormalizedEffectContract.validate(info.durationUs, info.displayWidth, info.displayHeight, info.rotation, info.hasAudio, request.maxLongEdge)
            val cover = MediaProbe.coverInfo(file, request.coverOffsetMs * 1000, ::check)
            require(scratch.mkdir())
            scratchCreated = true
            val jpeg = File(scratch, "cover.jpg")
            emit(ConversionStage.EXTRACTING_COVER)
            MediaProbe.writeCover(file, cover, jpeg, request.jpegQuality, request.maxLongEdge, ::check)
            emit(ConversionStage.PACKAGING)
            val asset = LivePhotoPackager.packageTo(PackageRequest(jpeg, file, profile, cover, request.allowExperimentalProfiles, captureDates=request.captureDates), target, ::check)
            packaged = true
            emit(ConversionStage.VERIFYING); LivePhotoVerifier.verify(asset, ::check)
            emit(ConversionStage.PUBLISHING)
            val uris = PublicationCoordinator(File(app.noBackupFilesDir, "livephoto-sdk/publications"), AndroidMediaSink(app, request.captureTimeMillis)).publish(asset, request.relativePath, ::check).ids.map(Uri::parse)
            // Mark only after the journaled publisher returns successfully. The handoff must not
            // turn an already-saved asset into a cancelled result, nor publish it again.
            val result=ConversionResult(asset, uris, selection = current)
            committed(result)
            emit(ConversionStage.COMPLETE)
            result
        } finally {
            if (scratchCreated) scratch.deleteRecursively()
            // Published URIs remain; immutable hashes in the result still allow explicit gallery feedback.
            if (packaged) target.deleteRecursively()
        }
    }
}
