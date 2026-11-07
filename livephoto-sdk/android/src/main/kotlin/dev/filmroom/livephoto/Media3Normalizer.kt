package dev.filmroom.livephoto

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import dev.filmroom.livephoto.core.ErrorCode
import dev.filmroom.livephoto.core.LivePhotoException
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class Media3Normalizer(private val context:Context) {
    suspend fun export(request:VideoToLiveRequest,info:MediaProbe.Info,output:File,progress:(Int?)->Unit) {
        if(info.hdr)throw LivePhotoException(ErrorCode.UNSUPPORTED_INPUT,"首版仅处理 SDR 视频；HDR 输入请先转换为 SDR，避免颜色错误。")
        val startUs=request.startMs*1000
        if(startUs>=info.durationUs)throw LivePhotoException(ErrorCode.INVALID_REQUEST,"Clip start is outside the video")
        val endMs=minOf(request.startMs+request.durationMs,info.durationUs/1000)
        if(endMs-request.startMs<100 || request.coverOffsetMs>=endMs-request.startMs)throw LivePhotoException(ErrorCode.INVALID_REQUEST,"Clip too short or cover beyond end")
        val scale=minOf(1.0,request.maxLongEdge.toDouble()/maxOf(info.displayWidth,info.displayHeight))
        val width=((info.displayWidth*scale).roundToInt()/2*2).coerceAtLeast(2)
        val height=((info.displayHeight*scale).roundToInt()/2*2).coerceAtLeast(2)
        val item=MediaItem.Builder().setUri(request.input).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(request.startMs).setEndPositionMs(endMs).build()
        ).build()
        val edited=EditedMediaItem.Builder(item).setRemoveAudio(!request.keepAudio).setEffects(
            Effects(emptyList(),listOf(Presentation.createForWidthAndHeight(width,height,Presentation.LAYOUT_SCALE_TO_FIT)))
        ).build()
        withTimeout(request.exportTimeoutMs) {
            withContext(Dispatchers.Main.immediate) {
                val handler=Handler(Looper.getMainLooper())
                var transformer:Transformer?=null
                var poll:Runnable?=null
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val encoder=DefaultEncoderFactory.Builder(context).setEnableFallback(true).build()
                        // Force a fresh clip: no hidden preroll/edit-list copy of frames the user trimmed out.
                        val forcedEncoder=object:Codec.EncoderFactory by encoder {
                            override fun videoNeedsEncoding()=true
                            override fun audioNeedsEncoding()=true
                        }
                        val t=Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(forcedEncoder)
                            .addListener(object:Transformer.Listener {
                                override fun onCompleted(composition:Composition,exportResult:ExportResult) {
                                    if(continuation.isActive)continuation.resume(Unit)
                                }
                                override fun onError(composition:Composition,exportResult:ExportResult,exportException:ExportException) {
                                    if(continuation.isActive)continuation.resumeWithException(LivePhotoException(ErrorCode.TRANSCODE_FAILED,"Media3 export failed: ${exportException.errorCode}",exportException))
                                }
                            }).build()
                        transformer=t
                        continuation.invokeOnCancellation { handler.post { t.cancel() } }
                        val ph=ProgressHolder()
                        val ticker=object:Runnable {
                            override fun run() {
                                if(!continuation.isActive)return
                                val state=t.getProgress(ph)
                                progress(if(state==Transformer.PROGRESS_STATE_AVAILABLE)ph.progress else null)
                                handler.postDelayed(this,250)
                            }
                        }
                        poll=ticker
                        try { t.start(edited,output.absolutePath); handler.post(ticker) }
                        catch(e:Exception) { if(continuation.isActive)continuation.resumeWithException(e) }
                    }
                } finally {
                    poll?.let { handler.removeCallbacks(it) }
                    // Cancellation handlers may originate off-thread. Await release on the owning thread
                    // before a caller deletes the temporary output or starts another hardware export.
                    withContext(NonCancellable+Dispatchers.Main.immediate) { transformer?.cancel() }
                }
            }
        }
    }
}
