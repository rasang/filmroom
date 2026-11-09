package dev.filmroom.effects

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Main-thread audio playback using exactly the AAC asset embedded in exported MP4s. */
internal class EffectPreviewAudio(private val context:Context) {
    private var player:MediaPlayer?=null
    private val manager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus:AudioFocusRequest?=null
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val prepareEpoch=AtomicInteger()
    @Volatile private var preparedKey:String?=null
    @Volatile private var preparingKey:String?=null
    @Volatile private var mixedFile:File?=null
    companion object {
        private val audioWorker=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { runnable -> Thread(runnable,"effect-audio-prepare").apply { isDaemon=true } },
            ThreadPoolExecutor.DiscardOldestPolicy())
    }
    private fun key(kind:EffectKind,slow:Boolean,file:File?)="$kind/$slow/${file?.absolutePath}"
    fun prepare(kind:EffectKind,slow:Boolean,sourceMotionFile:File?) {
        val key=key(kind,slow,sourceMotionFile)
        if(preparedKey==key || preparingKey==key)return
        val epoch=prepareEpoch.incrementAndGet()
        preparedKey=null;preparingKey=key;mixedFile=null
        if(kind!=EffectKind.OPTICAL_LEAK || sourceMotionFile==null) {
            preparedKey=key;preparingKey=null;return
        }
        audioWorker.execute {
            val result=try {
                EffectMotionAudio.prepare(context,kind,slow,sourceMotionFile) {
                    if(prepareEpoch.get()!=epoch)throw CancellationException()
                }
            }catch(_:Exception){null}catch(_:OutOfMemoryError){null}
            if(prepareEpoch.get()==epoch) {
                mixedFile=result;preparedKey=key;preparingKey=null
            }
        }
    }
    fun isPrepared(kind:EffectKind,slow:Boolean,sourceMotionFile:File?)=
        preparedKey==key(kind,slow,sourceMotionFile)
    fun play(kind:EffectKind,slow:Boolean,beganAt:Long,sourceMotionFile:File?=null,usePreparedSource:Boolean=true) {
        stopPlayback()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if(change<0)stop()
            }.build()
        if(manager.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return
        focus=request
        val current=MediaPlayer();player=current
        try {
            current.setAudioAttributes(attributes)
            val mixed=if(usePreparedSource && isPrepared(kind,slow,sourceMotionFile))mixedFile else null
            if(mixed!=null && mixed.isFile)current.setDataSource(mixed.absolutePath)
            else context.assets.openFd(EffectAudioTrack.assetPath(kind,slow)).use {
                current.setDataSource(it.fileDescriptor,it.startOffset,it.length)
            }
            current.setOnPreparedListener {
                if(player!==current)return@setOnPreparedListener
                val offset=EffectPreviewAudioTiming.offset(SystemClock.elapsedRealtime(),beganAt)
                if(offset==null){stop();return@setOnPreparedListener}
                // Async preparation must not replay the beginning after the image has advanced.
                if(offset>0){
                    current.setOnSeekCompleteListener { if(player===current)current.start() }
                    current.seekTo(offset.toLong(),MediaPlayer.SEEK_CLOSEST)
                }else current.start()
            }
            current.setOnCompletionListener { if(player===current)stop() }
            current.setOnErrorListener { _,_,_->if(player===current)stop();true }
            current.prepareAsync()
        }catch(_:Exception){stop()}
    }
    fun stop(){
        prepareEpoch.incrementAndGet();preparedKey=null;preparingKey=null;mixedFile=null
        stopPlayback()
    }
    private fun stopPlayback(){
        val old=player;player=null
        old?.setOnPreparedListener(null);old?.setOnSeekCompleteListener(null)
        old?.setOnCompletionListener(null);old?.setOnErrorListener(null)
        runCatching{old?.release()}
        focus?.let{runCatching{manager.abandonAudioFocusRequest(it)}};focus=null
    }
}
internal object EffectPreviewAudioTiming {
    fun offset(now:Long,beganAt:Long):Int?=(now-beganAt).coerceAtLeast(0).let{if(it>=3000)null else it.toInt()}
}
