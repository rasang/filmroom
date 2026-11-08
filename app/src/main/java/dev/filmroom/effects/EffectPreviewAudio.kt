package dev.filmroom.effects

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock

/** Main-thread audio playback using exactly the AAC asset embedded in exported MP4s. */
internal class EffectPreviewAudio(private val context:Context) {
    private var player:MediaPlayer?=null
    private val manager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus:AudioFocusRequest?=null
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    fun play(kind:EffectKind,slow:Boolean,beganAt:Long) {
        stop()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if(change<0)stop()
            }.build()
        if(manager.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return
        focus=request
        val current=MediaPlayer();player=current
        try {
            current.setAudioAttributes(attributes)
            context.assets.openFd(EffectAudioTrack.assetPath(kind,slow)).use {
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
