package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.max

/** Native H.264 + AAC effect export. Invoke on a worker thread; bitmaps remain owned by the caller. */
object EffectVideoExporter {
    private const val FPS = 30
    private const val MIME = "video/avc"

    fun export(
        context: Context, kind: EffectKind, source: Bitmap, result: Bitmap,
        onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false },
        slow: Boolean = false, privateOutput: java.io.File? = null,
        quality: EffectExportQuality = EffectExportQuality.ORIGINAL,
        requestedSize: EffectExportSize? = null,
        onResolution: (EffectExportReport) -> Unit = {},
        readout: EffectCameraReadout = EffectCameraReadout(),
        motionSource: EffectMotionSource? = null, motionAudioFile: java.io.File? = null
    ): Uri = EffectExportLog.trace(EffectExportScope.VIDEO) { log ->
        val requested = requestedSize ?: EffectExportResolution.desired(kind,source.width,source.height,result.width,result.height,quality)
        val available = EffectExportResolution.desired(kind,source.width,source.height,result.width,result.height,quality)
        var failure: Exception? = null
        var attempts = 0
        val tried=mutableSetOf<String>()
        val budget=EffectExportResolution.budgetEdge(Runtime.getRuntime().maxMemory())
        log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.START,
            if(available.longEdge<requested.longEdge)EffectExportReason.INPUT_RESOLUTION_LIMIT else EffectExportReason.NONE,
            requestedWidth=requested.width,requestedHeight=requested.height,
            availableWidth=available.width,availableHeight=available.height,budgetEdge=budget))
        for(size in EffectExportResolution.candidates(requested,available,budget)) {
            log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.CANDIDATE,
                if(budget<minOf(requested.longEdge,available.longEdge))EffectExportReason.HEAP_EDGE_CAP else EffectExportReason.NONE,
                requestedWidth=requested.width,requestedHeight=requested.height,width=size.width,height=size.height,budgetEdge=budget))
            val choices=encoders(size.width,size.height,log)
            if(choices.isEmpty())log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.CODEC_REJECTED,
                EffectExportReason.NO_ENCODER_FOR_SIZE,width=size.width,height=size.height))
            for(choice in choices) {
                if(!tried.add("${choice.name}:${choice.width}x${choice.height}"))continue
                if(cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Video export cancelled")
                log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.ATTEMPT_START,
                    requestedWidth=requested.width,requestedHeight=requested.height,width=choice.width,height=choice.height,
                    codecIndex=choice.index,attempt=attempts+1))
                log.failureReason=EffectExportReason.NONE
                try {
                    val uri = attempt(context,kind,source,result,onProgress,cancelled,slow,privateOutput,choice,log,readout,motionSource,motionAudioFile)
                    log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.VIDEO_SAVED,
                        requestedWidth=requested.width,requestedHeight=requested.height,width=choice.width,height=choice.height,
                        availableWidth=available.width,availableHeight=available.height,codecIndex=choice.index,attempt=attempts+1))
                    runCatching { onResolution(EffectExportReport(requested,EffectExportSize(choice.width,choice.height),
                        (available.longEdge < requested.longEdge), (attempts > 0))) }
                    return@trace uri
                } catch(e: CancellationException) { throw e }
                  catch(e: SecurityException) { throw e }
                  catch(e: EffectMotionExportException) { log.failureReason=EffectExportReason.MOTION_DECODE_FAILED; logFailure(log,e,choice,attempts+1); throw e }
                  catch(e: OutOfMemoryError) { logFailure(log,e,choice,attempts+1); failure=IOException("Export memory budget exceeded",e); attempts++ }
                  catch(e: Exception) { logFailure(log,e,choice,attempts+1); failure=e; attempts++ }
            }
        }
        throw IOException("No usable H.264 export size on this device",failure)
    }

    private fun logFailure(log:EffectExportLog,error:Throwable,choice:EncoderChoice,attempt:Int) {
        log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.ATTEMPT_FAILED,
            if(log.failureReason!=EffectExportReason.NONE)log.failureReason else EffectExportLog.reason(error),log.step,
            width=choice.width,height=choice.height,codecIndex=choice.index,attempt=attempt,
            codecErrorCode=(error as? MediaCodec.CodecException)?.errorCode))
    }
    private data class EncoderChoice(val name:String,val width:Int,val height:Int,val bitrate:Int,val index:Int)
    private fun attempt(context: Context, kind: EffectKind, source: Bitmap, result: Bitmap,
        onProgress: (Float) -> Unit, cancelled: () -> Boolean, slow: Boolean,
        privateOutput: java.io.File?, choice:EncoderChoice, log:EffectExportLog, readout:EffectCameraReadout,
        motionSource:EffectMotionSource?,motionAudioFile:java.io.File?): Uri {
        fun failed(reason:EffectExportReason,message:String):Nothing {
            log.failureReason=reason
            throw IOException(message)
        }
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("Video export cancelled")
        }
        require(!source.isRecycled && !result.isRecycled) { "Photo is no longer available" }
        checkCancelled()
        val width = choice.width
        val height = choice.height
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, choice.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val resolver = context.contentResolver
        var output: Uri? = null
        var committed = false
        var privateCreated = false
        var codec: MediaCodec? = null
        var codecStarted = false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var descriptor: android.os.ParcelFileDescriptor? = null
        var input: android.view.Surface? = null
        var egl: EffectEncoderEgl? = null
        var renderer: EffectGlRenderer? = null
        var audio: EffectAudioTrack? = null
        var audioTrack = -1
        var renderSource: Bitmap? = null
        var renderResult: Bitmap? = null
        try {
            log.step=EffectExportStep.CREATE_CODEC
            val encoder = MediaCodec.createByCodecName(choice.name).also { codec = it }
            log.step=EffectExportStep.CONFIGURE_CODEC
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            log.step=EffectExportStep.CREATE_SURFACE
            input = encoder.createInputSurface()
            log.step=EffectExportStep.CREATE_EGL
            egl = EffectEncoderEgl(input!!)
            egl.makeCurrent()
            log.step=EffectExportStep.QUERY_GL_LIMITS
            val limits = IntArray(1)
            android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_TEXTURE_SIZE,limits,0)
            val viewportLimits = IntArray(2)
            android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_VIEWPORT_DIMS,viewportLimits,0)
            val glUnsupported=width>viewportLimits[0] || height>viewportLimits[1] || maxOf(width,height)>limits[0]
            log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.GL_LIMITS,
                if(glUnsupported)EffectExportReason.GL_LIMIT else EffectExportReason.NONE,log.step,
                width=width,height=height,codecIndex=choice.index,textureLimit=limits[0],
                viewportWidth=viewportLimits[0],viewportHeight=viewportLimits[1]))
            // Reject this size and retry lower rather than stretch a silently downscaled texture.
            if (width>viewportLimits[0] || height>viewportLimits[1] || maxOf(width,height)>limits[0]) {
                failed(EffectExportReason.GL_LIMIT,"GL texture or viewport size unsupported")
            }
            fun fitted(bitmap: Bitmap): Bitmap {
                val edge=maxOf(width,height)
                val scale=minOf(1.0,edge.toDouble()/maxOf(bitmap.width,bitmap.height))
                return if(scale>=1.0)bitmap else Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)
            }
            log.step=EffectExportStep.PREPARE_TEXTURES
            renderSource=fitted(source)
            renderResult=if(source===result)renderSource else fitted(result)
            checkCancelled()
            renderer = EffectGlRenderer(kind, renderSource!!, renderResult!!,readout)
            log.step=EffectExportStep.INITIALIZE_RENDERER
            renderer.initialize()
            motionSource?.reset()
            log.step=EffectExportStep.START_CODEC
            encoder.start()
            codecStarted = true
            checkCancelled()
            log.step=EffectExportStep.OPEN_OUTPUT
            val writer = if (privateOutput != null) {
                require(privateOutput.parentFile?.canonicalFile == context.cacheDir.canonicalFile) { "Private video must be in app cache" }
                check(privateOutput.createNewFile()) { "Private video output already exists" }
                privateCreated = true
                MediaMuxer(privateOutput.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                output = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "Filmroom_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Filmroom")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }) ?: failed(EffectExportReason.OUTPUT_CREATE_FAILED,"Cannot create video")
                descriptor = resolver.openFileDescriptor(output!!, "w") ?: failed(EffectExportReason.OUTPUT_OPEN_FAILED,"Cannot open video")
                MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }.also { muxer = it }
            audio = EffectAudioTrack(context,kind,slow,motionAudioFile,::checkCancelled)
            var track = -1
            var eosSeen = false
            var samples = 0
            val info = MediaCodec.BufferInfo()
            fun drain(waitForEnd: Boolean) {
            log.step=EffectExportStep.DRAIN
                val deadline = SystemClock.elapsedRealtime() + if (waitForEnd) 15_000 else 2_000
                while (true) {
                    checkCancelled()
                    if (SystemClock.elapsedRealtime() > deadline) failed(EffectExportReason.ENCODER_TIMEOUT,"Video encoder timed out")
                    val index = encoder.dequeueOutputBuffer(info, if (waitForEnd) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!waitForEnd) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) failed(EffectExportReason.ENCODER_FORMAT_CHANGED,"Video format changed twice")
                            val actualFormat=encoder.outputFormat
                            if(actualFormat.getInteger(MediaFormat.KEY_WIDTH)!=width || actualFormat.getInteger(MediaFormat.KEY_HEIGHT)!=height)
                                failed(EffectExportReason.ENCODER_SIZE_CHANGED,"Encoder changed the requested resolution")
                            track = writer.addTrack(actualFormat)
                            audioTrack = writer.addTrack(audio!!.format)
                            writer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            try {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                                if (info.size > 0) {
                                    if (!muxerStarted) failed(EffectExportReason.ENCODER_FORMAT_MISSING,"Video format missing")
                                    val buffer = encoder.getOutputBuffer(index) ?: failed(EffectExportReason.ENCODER_BUFFER_MISSING,"Video buffer missing")
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    writer.writeSampleData(track, buffer, info)
                                    samples++
                                }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
                            } finally { encoder.releaseOutputBuffer(index, false) }
                            if (eosSeen) return
                        }
                    }
                }
            }
            val duration = EffectTimeline.durationMs(kind, slow)
            val frameCount = frameCount(duration)
            for (frame in 0 until frameCount) {
                checkCancelled()
                drain(false)
                // Sample the same wall-clock time as preview; the final 2966 ms frame is already settled.
                log.step=EffectExportStep.DRAW
                if(kind==EffectKind.OPTICAL_LEAK && motionSource!=null) {
                    val motionFrame=motionSource.frameAt(timelineTimeMs(frame),slow)
                    EffectMotionExportGuard.requireFrame(frame,motionFrame!=null,motionSource.isUnavailable())
                    motionFrame?.use { renderer.updateMotionFrame(it) }
                }
                renderer.draw(width, height, timelineTimeMs(frame), slow)
                log.step=EffectExportStep.PRESENT
                egl.present(presentationTimeNanos(frame))
                drain(false)
                onProgress((frame + 1).toFloat() / frameCount * .95f)
            }
            log.step=EffectExportStep.SIGNAL_EOS
            encoder.signalEndOfInputStream()
            drain(true)
            if (!eosSeen || samples == 0 || !muxerStarted) failed(EffectExportReason.INCOMPLETE_VIDEO,"Video is incomplete")
            log.step=EffectExportStep.FINALIZE
            audio!!.writeTo(writer,audioTrack,::checkCancelled)
            audio!!.close()
            audio = null
            log.step=EffectExportStep.FINALIZE
            writer.stop()
            muxerStarted = false
            writer.release()
            muxer = null
            descriptor?.close()
            descriptor = null
            checkCancelled()
            log.step=EffectExportStep.PUBLISH
            if (privateOutput == null && resolver.update(output!!, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) != 1) {
                failed(EffectExportReason.OUTPUT_PUBLISH_FAILED,"Cannot publish video")
            }
            committed = true
            // Observer failures must not turn a published video into an apparent failed export.
            runCatching { onProgress(1f) }
            return privateOutput?.let(Uri::fromFile) ?: output!!
        } finally {
            runCatching { audio?.close() }
            runCatching { renderer?.release() }
            if(renderSource!==source)renderSource?.recycle()
            if(renderResult!==result && renderResult!==renderSource)renderResult?.recycle()
            runCatching { egl?.close() }
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { input?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { descriptor?.close() }
            if (!committed && privateCreated) privateOutput?.delete()
            if (!committed) output?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    internal fun frameCount(durationMs: Long): Int = max(2, ceil(durationMs * FPS / 1000.0).toInt())
    internal fun presentationTimeNanos(frame: Int): Long = frame.toLong() * 1_000_000_000L / FPS
    internal fun timelineTimeMs(frame: Int): Long = presentationTimeNanos(frame) / 1_000_000L

    /** Optical housing follows the source; light-table proportions remain fixed. */
    internal fun sceneDimensions(kind: EffectKind, sourceAspect: Float): Pair<Int, Int> {
        val aspect = EffectGeometry.sceneAspect(kind, sourceAspect).toDouble()
        fun even(value: Double) = max(2, value.toInt() / 2 * 2)
        return if (aspect >= 1.0) 1080 to even(1080.0 / aspect)
               else even(1080.0 * aspect) to 1080
    }

    private fun encoders(width: Int, height: Int,log:EffectExportLog): List<EncoderChoice> {
        val result = mutableListOf<EncoderChoice>()
        for ((codecIndex,codec) in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.withIndex()) {
            if (!codec.isEncoder || !codec.supportedTypes.any { it.equals(MIME, true) }) continue
            fun rejected(reason:EffectExportReason,w:Int=width,h:Int=height){log.record(EffectExportDiagnosticInfo(
                log.scope,EffectExportEvent.CODEC_REJECTED,reason,width=w,height=h,codecIndex=codecIndex))}
            val caps = runCatching { codec.getCapabilitiesForType(MIME) }.getOrNull()
            if(caps==null){rejected(EffectExportReason.CODEC_CAPABILITIES_UNAVAILABLE);continue}
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats){rejected(EffectExportReason.CODEC_NO_SURFACE);continue}
            val video = caps.videoCapabilities
            if(video==null){rejected(EffectExportReason.CODEC_NO_VIDEO_CAPABILITIES);continue}
            val aw = max(2, video.widthAlignment); val ah = max(2, video.heightAlignment)
            val w = width / aw * aw; val h = height / ah * ah
            if (w<2 || h<2){rejected(EffectExportReason.CODEC_ALIGNMENT,w,h);continue}
            val supported=runCatching{video.areSizeAndRateSupported(w,h,FPS.toDouble())}
            if(supported.getOrDefault(false)) {
                val bitrate=(w.toLong()*h*8).coerceIn(2_000_000L,100_000_000L).toInt()
                    .coerceIn(video.bitrateRange.lower,video.bitrateRange.upper)
                result += EncoderChoice(codec.name,w,h,bitrate,codecIndex)
                log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.CODEC_ACCEPTED,
                    if(w!=width || h!=height)EffectExportReason.CODEC_ALIGNMENT else EffectExportReason.NONE,
                    requestedWidth=width,requestedHeight=height,width=w,height=h,codecIndex=codecIndex))
            } else rejected(if(supported.isFailure)EffectExportReason.CODEC_CAPABILITY_QUERY_FAILED else EffectExportReason.CODEC_SIZE_RATE_UNSUPPORTED,w,h)
        }
        return result
    }
}
