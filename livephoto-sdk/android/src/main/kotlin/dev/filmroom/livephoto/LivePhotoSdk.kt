package dev.filmroom.livephoto

import android.content.Context
import android.net.Uri
import dev.filmroom.livephoto.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID

/** Native Android, local-only, no vendor secrets, root, server or FFmpeg dependency. Android 10+. */
class LivePhotoSdk(context:Context) {
    private val app=context.applicationContext
    private val assetRoot=File(app.noBackupFilesDir,"livephoto-sdk/assets")
    private val publisher=PublicationCoordinator(File(app.noBackupFilesDir,"livephoto-sdk/publications"),AndroidMediaSink(app))
    private val normalizer=Media3Normalizer(app)
    companion object { private val hardwareSlot=Semaphore(1) }

    /** Progress callbacks can arrive from any dispatcher. UI callers must dispatch to the main thread. */
    suspend fun create(request:VideoToLiveRequest,onProgress:(ConversionProgress)->Unit={}):PackagedAsset = hardwareSlot.withPermit {
        if(request.profile!=LivePhotoProfile.GOOGLE && !request.allowExperimentalProfiles)
            throw LivePhotoException(ErrorCode.UNSUPPORTED_PROFILE,"请明确选择并允许实验性厂商格式；不会按手机型号自动承诺兼容。")
        val context=currentCoroutineContext()
        fun check() { context.ensureActive() }
        fun emit(stage:ConversionStage,percent:Int?=null) { try { onProgress(ConversionProgress(stage,percent)) } catch(_:RuntimeException) { /* Observers cannot corrupt export state. */ } }
        val nonce=UUID.randomUUID().toString()
        val temp=File(app.cacheDir,"livephoto-$nonce")
        val target=File(assetRoot,nonce)
        try {
            withContext(Dispatchers.IO) {
                if(!temp.mkdirs())throw LivePhotoException(ErrorCode.IO,"Cannot create export scratch directory")
            }
            emit(ConversionStage.PROBING)
            val inputInfo=withContext(Dispatchers.IO) { check(); MediaProbe.input(app,request.input) }
            emit(ConversionStage.TRANSCODING,0)
            val video=File(temp,"normalized.mp4")
            normalizer.export(request,inputInfo,video) { emit(ConversionStage.TRANSCODING,it) }
            emit(ConversionStage.EXTRACTING_COVER)
            val asset=withContext(Dispatchers.IO) {
                check()
                val outputInfo=MediaProbe.normalized(video)
                if(maxOf(outputInfo.displayWidth,outputInfo.displayHeight)>request.maxLongEdge+16)
                    throw LivePhotoException(ErrorCode.TRANSCODE_FAILED,"Output exceeds requested dimensions")
                if(request.keepAudio && inputInfo.hasAudio && !outputInfo.hasAudio)throw LivePhotoException(ErrorCode.TRANSCODE_FAILED,"The source audio was lost during export")
                if(!request.keepAudio && outputInfo.hasAudio)throw LivePhotoException(ErrorCode.TRANSCODE_FAILED,"Mute request was not respected")
                val cover=MediaProbe.coverInfo(video,request.coverOffsetMs*1000,::check)
                val jpeg=File(temp,"cover.jpg")
                MediaProbe.writeCover(video,cover,jpeg,request.jpegQuality,request.maxLongEdge,::check)
                emit(ConversionStage.PACKAGING)
                LivePhotoPackager.packageTo(PackageRequest(jpeg,video,request.profile,cover,request.allowExperimentalProfiles),target,::check)
            }
            emit(ConversionStage.VERIFYING)
            withContext(Dispatchers.IO) { LivePhotoVerifier.verify(asset,::check) }
            check()
            asset
        } catch(t:Throwable) {
            withContext(NonCancellable+Dispatchers.IO) { target.deleteRecursively() }
            throw t
        } finally {
            withContext(NonCancellable+Dispatchers.IO) { temp.deleteRecursively() }
        }
    }
    suspend fun publish(asset:PackagedAsset,relativePath:String="DCIM/Filmroom/"):List<Uri> = withContext(Dispatchers.IO) {
        val context=currentCoroutineContext()
        publisher.publish(asset,relativePath) { context.ensureActive() }.ids.map(Uri::parse)
    }
    suspend fun convert(request:VideoToLiveRequest,onProgress:(ConversionProgress)->Unit={}):ConversionResult {
        val asset=create(request,onProgress)
        val uris=if(request.saveToGallery) {
            try { onProgress(ConversionProgress(ConversionStage.PUBLISHING)) } catch(_:RuntimeException) { }
            // create()/publish() lets a host retain and retry an artifact. This convenience method
            // instead removes its own private artifact on failure and reports the actual error.
            try { publish(asset,request.relativePath) } catch(t:Throwable) {
                withContext(NonCancellable+Dispatchers.IO) { deleteLocalAsset(asset) }
                throw t
            }
        } else emptyList()
        try { onProgress(ConversionProgress(ConversionStage.COMPLETE,100)) } catch(_:RuntimeException) { }
        return ConversionResult(asset,uris)
    }
    suspend fun recoverInterruptedPublications():RecoveryReport = withContext(Dispatchers.IO) { publisher.recoverInterrupted() }
    /** Deletes SDK-owned private files only, never the input video or published MediaStore rows. */
    fun deleteLocalAsset(asset:PackagedAsset):Boolean {
        val parent=asset.image.file.parentFile ?: return false
        val root=assetRoot.canonicalFile
        if(parent.canonicalFile.parentFile!=root)throw SecurityException("Not an SDK-owned asset directory")
        return parent.deleteRecursively()
    }
}
