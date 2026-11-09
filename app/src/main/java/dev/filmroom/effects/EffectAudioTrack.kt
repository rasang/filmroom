package dev.filmroom.effects

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodec
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/** Pre-encoded original effects. The same audio track is embedded in video and Live output. */
internal class EffectAudioTrack(context:Context,kind:EffectKind,slow:Boolean,sourceMotionFile:File?=null,checkActive:()->Unit={}):Closeable {
    companion object {
        fun assetPath(kind:EffectKind,slow:Boolean)= "effect_audio/"+when(kind){
            EffectKind.LIGHT_TABLE->"light_table"
            EffectKind.OPTICAL_LEAK->if(slow)"optical_leak_slow" else "optical_leak"
        }+".m4a"
    }
    private val extractor=MediaExtractor()
    val format:MediaFormat
    init {
        try {
            val mixed=EffectMotionAudio.prepare(context,kind,slow,sourceMotionFile,checkActive)
            if(mixed!=null) extractor.setDataSource(mixed.absolutePath)
            else context.assets.openFd(assetPath(kind,slow)).use {
                extractor.setDataSource(it.fileDescriptor,it.startOffset,it.length)
            }
            val track=(0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm"
            } ?: error("Effect AAC track missing")
            format=extractor.getTrackFormat(track)
            extractor.selectTrack(track)
        } catch(t:Throwable) { extractor.release();throw t }
    }
    fun writeTo(writer:MediaMuxer,track:Int,checkCancelled:()->Unit) {
        val buffer=ByteBuffer.allocateDirect(64*1024)
        val info=MediaCodec.BufferInfo()
        var count=0
        while(true) {
            checkCancelled()
            buffer.clear()
            val size=extractor.readSampleData(buffer,0)
            if(size<0) break
            require(size<=buffer.capacity()) { "Effect audio sample too large" }
            val pts=extractor.sampleTime
            if(pts>=0 && pts<3_000_000L) {
                val sampleFlags=extractor.sampleFlags
                val codecFlags=(if(sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) or
                    (if(sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME!=0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0)
                info.set(0,size,pts,codecFlags)
                buffer.position(0);buffer.limit(size)
                writer.writeSampleData(track,buffer,info);count++
            }
            if(!extractor.advance())break
        }
        check(count>0) { "Effect audio is empty" }
        info.set(0,0,3_000_000L,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        writer.writeSampleData(track,ByteBuffer.allocate(0),info)
    }
    override fun close() { extractor.release() }
}
