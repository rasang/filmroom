package dev.filmroom.effects

import android.content.Context
import android.net.Uri
import dev.filmroom.diagnostics.Diagnostics
import dev.filmroom.diagnostics.MotionInputDiagnosticInfo
import dev.filmroom.diagnostics.MotionInputStatus
import dev.filmroom.diagnostics.Operation
import dev.filmroom.diagnostics.Outcome
import dev.filmroom.diagnostics.SafeInfo
import dev.filmroom.livephoto.importer.MotionPhotoExtractor
import java.io.File
import java.util.concurrent.CancellationException

/** Bounded original JPEG motion input; typed diagnostics never contain source identifiers. */
internal object EffectMotionInput {
    private const val MAX_INPUT=134217728L
    fun prepare(context:Context,uri:String?,checkActive:()->Unit={}):File? =
        prepareWithDiagnostics(context,uri,checkActive) { report ->
            val info=SafeInfo(motionInput=report)
            val outcome=when(report.status) {
                MotionInputStatus.SUCCESS -> Outcome.SUCCESS
                MotionInputStatus.CANCELLED -> Outcome.CANCELLED
                else -> Outcome.FAILED
            }
            Diagnostics.end(Diagnostics.begin(Operation.IMPORT,info),outcome,info)
        }

    /** Injection is only for tests; all production reports go through the persistent typed store. */
    internal fun prepareWithDiagnostics(context:Context,uri:String?,checkActive:()->Unit={},report:(MotionInputDiagnosticInfo)->Unit):File? {
        val progress=Progress()
        return try {
            prepareChecked(context,uri,checkActive,progress)
        } catch(e:CancellationException) { progress.status=MotionInputStatus.CANCELLED;throw e }
          catch(_:Exception) { null }
          catch(_:OutOfMemoryError) { progress.status=MotionInputStatus.MEMORY_LIMIT;null }
          finally { runCatching { report(MotionInputDiagnosticInfo(progress.status,progress.copied,progress.videoBytes)) } }
    }
    private class Progress {
        var status=MotionInputStatus.READ_FAILED
        var copied=0L
        var videoBytes:Long?=null
    }
    private fun prepareChecked(context:Context,uri:String?,checkActive:()->Unit,progress:Progress):File? {
        if(uri==null) { progress.status=MotionInputStatus.SOURCE_MISSING;return null }
        val directory=File(context.cacheDir,"effect-motion").apply{mkdirs()}
        val input=File.createTempFile("input-",".jpg",directory)
        var video:File?=null
        var retained=false
        try {
            checkActive()
            val stream=context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            stream.use { src -> input.outputStream().use { dst ->
                if(src.read()!=255 || src.read()!=216) { progress.status=MotionInputStatus.NON_JPEG;return null }
                dst.write(byteArrayOf(-1,-40));progress.copied=2
                val buffer=ByteArray(65536);var total=2L
                while(true){checkActive();val count=src.read(buffer);if(count<0)break
                    total+=count
                    if(total>MAX_INPUT) { progress.status=MotionInputStatus.INPUT_TOO_LARGE;return null }
                    dst.write(buffer,0,count);progress.copied=total}
            }}
            checkActive()
            progress.status=MotionInputStatus.NOT_RECOGNIZED
            val inspected=MotionPhotoExtractor.inspect(input,checkActive=checkActive) ?: return null
            progress.status=MotionInputStatus.EXTRACTION_FAILED
            val target=File.createTempFile("video-",".mp4",directory);video=target
            check(target.delete())
            MotionPhotoExtractor.extract(inspected,target,checkActive)
            checkActive();retained=true
            progress.videoBytes=target.length();progress.status=MotionInputStatus.SUCCESS
            return target
        } finally {input.delete();if(!retained)video?.delete()}
    }
}
