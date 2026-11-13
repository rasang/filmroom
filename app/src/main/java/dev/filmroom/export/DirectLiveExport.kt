package dev.filmroom.export

import android.content.Context
import android.net.Uri
import dev.filmroom.effects.*
import dev.filmroom.io.*
import dev.filmroom.livephoto.ConversionResult
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

internal interface DirectLiveProcessing {
    suspend fun recover(context:Context)
    suspend fun saveClean(context:Context,dir:File,job:ExportJob,linear:File,onProgress:(Long,Long)->Unit)
    suspend fun publish(context:Context,job:ExportJob,onProgress:(Float)->Unit,onPrepared:(List<Uri>,List<String>)->Unit,onCommitted:(ConversionResult)->Unit)
}

/** The clean render is durable before any gallery publication. Only the Live SDK publishes media. */
internal object DirectLiveExport:DirectLiveProcessing {
    override suspend fun recover(context:Context) {
        val recovery=dev.filmroom.livephoto.NormalizedLivePhotoSdk(context).recover()
        check(recovery.failures.isEmpty()) { "Previous Live export cleanup is incomplete" }
    }
    override suspend fun saveClean(context:Context, dir:File, job:ExportJob, linear:File, onProgress:(Long,Long)->Unit) {
        val coroutine=currentCoroutineContext()
        val encoded=File(dir,"clean-encode.part")
        try {
            val metadata=job.photoMetadata?.let(PhotoMetadata::fromJson) ?: PhotoMetadata.unavailable()
            val color=OutputColorSpace.parse(job.parameters.outputColorSpace)
            val profile=if(color==OutputColorSpace.SRGB)null else context.assets.open("color-profiles/${color.iccAsset}").use{it.readBytes()}
            FileOutputStream(encoded).use { out ->
                linear.inputStream().buffered(256*1024).use { input ->
                    LinearRgbPng.encode(input,job.outputWidth,job.outputHeight,out,
                        checkActive={coroutine.ensureActive()},onRowEncoded={onProgress(it.toLong(),job.outputHeight.toLong())},
                        colorSpace=color,iccProfile=profile,
                        exifMetadata=metadata.exifBytes(job.outputWidth,job.outputHeight,job.parameters.outputColorSpace,job.exportMillis ?: System.currentTimeMillis()),
                        exportMillis=job.exportMillis)
                }
                out.fd.sync()
            }
            CleanResultStore.write(dir,job,encoded,coroutine)
        } finally { encoded.delete() }
    }

    override suspend fun publish(context:Context, job:ExportJob, onProgress:(Float)->Unit, onPrepared:(List<Uri>,List<String>)->Unit, onCommitted:(ConversionResult)->Unit) {
        val kind=EffectKind.valueOf(requireNotNull(job.liveEffect))
        val coroutine=currentCoroutineContext()
        val preflight=EffectLivePhotoExporter.preflight(context)
        check(!preflight.unsupported){"Live Photo export is unavailable on this device"}
        val clean=requireNotNull(CleanResultStore.resolve(context,job)){"Clean processed photo is unavailable"}
        val composition=EffectSourceComposition.from(job)
        val images=EffectExportImages()
        var motionFile:File?=null
        var motion:EffectMotionSource?=null
        try {
            images.load(context,kind,job.originalUri,Uri.fromFile(clean).toString(),EffectExportQuality.ORIGINAL,
                live=true,composition=composition,sourceRecord=job){!coroutine.isActive}
            if(kind==EffectKind.OPTICAL_LEAK) {
                motionFile=EffectMotionInput.prepare(context,job.originalUri){coroutine.ensureActive()}
                val snapshot=File(File(File(context.filesDir,"export-jobs"),job.id),"source.image")
                val snapshotUri=if(job.inputKind=="ENCODED" && snapshot.isFile)Uri.fromFile(snapshot).toString() else null
                if(motionFile==null && snapshotUri!=null)motionFile=EffectMotionInput.prepare(context,snapshotUri){coroutine.ensureActive()}
                check(motionFile!=null || (!EffectMotionInput.needsPair(job.originalUri) && !EffectMotionInput.needsPair(snapshotUri))){"Original Live companion video is unavailable"}
                motion=motionFile?.let{EffectMotionSource(it,composition)}
            }
            EffectLivePhotoExporter.export(context,kind,images.source,images.result,preflight,
                cancelled={!coroutine.isActive},onProgress=onProgress,quality=EffectExportQuality.ORIGINAL,
                requestedSize=images.requested,readout=EffectCameraReadout.fromSnapshot(job.photoMetadata),
                motionSource=motion,motionAudioFile=EffectOriginalAudioPreference.source(kind,EffectOriginalAudioPreference.read(context),motionFile),
                photoMetadata=job.photoMetadata?.let(PhotoMetadata::fromJson) ?: PhotoMetadata.unavailable(),
                onCommitted=onCommitted,onPrepared=onPrepared)
        } finally {
            withContext(NonCancellable){motion?.close();motionFile?.delete();images.close()}
        }
    }
}
