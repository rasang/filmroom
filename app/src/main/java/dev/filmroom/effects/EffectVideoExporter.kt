package dev.filmroom.effects

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

/** Native, silent H.264 export. Invoke on a worker thread; bitmaps remain owned by the caller. */
object EffectVideoExporter {
    private const val FPS = 30
    private const val MIME = "video/avc"

    fun export(
        context: Context, kind: EffectKind, source: Bitmap, result: Bitmap,
        onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false },
        slow: Boolean = false, privateOutput: java.io.File? = null,
        quality: EffectExportQuality = EffectExportQuality.ORIGINAL,
        requestedSize: EffectExportSize? = null,
        onResolution: (EffectExportReport) -> Unit = {}
    ): Uri {
        val requested = requestedSize ?: EffectExportResolution.desired(kind,source.width,source.height,result.width,result.height,quality)
        val available = EffectExportResolution.desired(kind,source.width,source.height,result.width,result.height,quality)
        var failure: Exception? = null
        var attempts = 0
        val tried=mutableSetOf<String>()
        for(size in EffectExportResolution.candidates(requested,available,EffectExportResolution.budgetEdge(Runtime.getRuntime().maxMemory()))) {
            for(choice in encoders(size.width,size.height)) {
                if(!tried.add("${choice.name}:${choice.width}x${choice.height}"))continue
                if(cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Video export cancelled")
                try {
                    val uri = attempt(context,kind,source,result,onProgress,cancelled,slow,privateOutput,choice)
                    runCatching { onResolution(EffectExportReport(requested,EffectExportSize(choice.width,choice.height),
                        (available.longEdge < requested.longEdge), (attempts > 0))) }
                    return uri
                } catch(e: CancellationException) { throw e }
                  catch(e: SecurityException) { throw e }
                  catch(e: OutOfMemoryError) { failure=IOException("Export memory budget exceeded",e); attempts++ }
                  catch(e: Exception) { failure=e; attempts++ }
            }
        }
        throw IOException("No usable H.264 export size on this device",failure)
    }

    private data class EncoderChoice(val name:String,val width:Int,val height:Int,val bitrate:Int)
    private fun attempt(context: Context, kind: EffectKind, source: Bitmap, result: Bitmap,
        onProgress: (Float) -> Unit, cancelled: () -> Boolean, slow: Boolean,
        privateOutput: java.io.File?, choice:EncoderChoice): Uri {
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
        var renderSource: Bitmap? = null
        var renderResult: Bitmap? = null
        try {
            val encoder = MediaCodec.createByCodecName(choice.name).also { codec = it }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = encoder.createInputSurface()
            egl = EffectEncoderEgl(input!!)
            egl.makeCurrent()
            val limits = IntArray(1)
            android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_TEXTURE_SIZE,limits,0)
            val viewportLimits = IntArray(2)
            android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_VIEWPORT_DIMS,viewportLimits,0)
            // Reject this size and retry lower rather than stretch a silently downscaled texture.
            if (width>viewportLimits[0] || height>viewportLimits[1] || maxOf(width,height)>limits[0]) {
                throw IOException("GL texture or viewport size unsupported")
            }
            fun fitted(bitmap: Bitmap): Bitmap {
                val edge=maxOf(width,height)
                val scale=minOf(1.0,edge.toDouble()/maxOf(bitmap.width,bitmap.height))
                return if(scale>=1.0)bitmap else Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)
            }
            renderSource=fitted(source)
            renderResult=if(source===result)renderSource else fitted(result)
            checkCancelled()
            renderer = EffectGlRenderer(kind, renderSource!!, renderResult!!)
            renderer.initialize()
            encoder.start()
            codecStarted = true
            checkCancelled()
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
                }) ?: throw IOException("Cannot create video")
                descriptor = resolver.openFileDescriptor(output!!, "w") ?: throw IOException("Cannot open video")
                MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }.also { muxer = it }
            var track = -1
            var eosSeen = false
            var samples = 0
            val info = MediaCodec.BufferInfo()
            fun drain(waitForEnd: Boolean) {
                val deadline = SystemClock.elapsedRealtime() + if (waitForEnd) 15_000 else 2_000
                while (true) {
                    checkCancelled()
                    if (SystemClock.elapsedRealtime() > deadline) throw IOException("Video encoder timed out")
                    val index = encoder.dequeueOutputBuffer(info, if (waitForEnd) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!waitForEnd) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) throw IOException("Video format changed twice")
                            val actualFormat=encoder.outputFormat
                            if(actualFormat.getInteger(MediaFormat.KEY_WIDTH)!=width || actualFormat.getInteger(MediaFormat.KEY_HEIGHT)!=height)
                                throw IOException("Encoder changed the requested resolution")
                            track = writer.addTrack(actualFormat)
                            writer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            try {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                                if (info.size > 0) {
                                    if (!muxerStarted) throw IOException("Video format missing")
                                    val buffer = encoder.getOutputBuffer(index) ?: throw IOException("Video buffer missing")
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
                renderer.draw(width, height, timelineTimeMs(frame), slow)
                egl.present(presentationTimeNanos(frame))
                drain(false)
                onProgress((frame + 1).toFloat() / frameCount * .95f)
            }
            encoder.signalEndOfInputStream()
            drain(true)
            if (!eosSeen || samples == 0 || !muxerStarted) throw IOException("Video is incomplete")
            writer.stop()
            muxerStarted = false
            writer.release()
            muxer = null
            descriptor?.close()
            descriptor = null
            checkCancelled()
            if (privateOutput == null && resolver.update(output!!, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) != 1) {
                throw IOException("Cannot publish video")
            }
            committed = true
            // Observer failures must not turn a published video into an apparent failed export.
            runCatching { onProgress(1f) }
            return privateOutput?.let(Uri::fromFile) ?: output!!
        } finally {
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

    private fun encoders(width: Int, height: Int): List<EncoderChoice> {
        val result = mutableListOf<EncoderChoice>()
        for (codec in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!codec.isEncoder || !codec.supportedTypes.any { it.equals(MIME, true) }) continue
            val caps = runCatching { codec.getCapabilitiesForType(MIME) }.getOrNull() ?: continue
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue
            val video = caps.videoCapabilities ?: continue
            val aw = max(2, video.widthAlignment); val ah = max(2, video.heightAlignment)
            val w = width / aw * aw; val h = height / ah * ah
            if (w<2 || h<2) continue
            if (runCatching{video.areSizeAndRateSupported(w,h,FPS.toDouble())}.getOrDefault(false)) {
                val bitrate=(w.toLong()*h*8).coerceIn(2_000_000L,100_000_000L).toInt()
                    .coerceIn(video.bitrateRange.lower,video.bitrateRange.upper)
                result += EncoderChoice(codec.name,w,h,bitrate)
            }
        }
        return result
    }
}
