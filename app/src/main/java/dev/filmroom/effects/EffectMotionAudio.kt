package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.floor
import kotlin.math.roundToInt

/** Restored from beta27 DEX: bounded local source-audio decode, mix and AAC cache. */
internal object EffectMotionAudio {
    private const val FRAMES=144000
    private const val CHANNELS=2
    private val noAudio=LinkedHashSet<String>()
    private val prepareLock=ReentrantLock()
    private fun key(kind:EffectKind,slow:Boolean,file:File):String {
        val value="v1-half-bed-$kind-$slow-${file.absolutePath}-${file.length()}-${file.lastModified()}"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    }
    fun prepare(context:Context,kind:EffectKind,slow:Boolean,file:File?,correlationId:Long=EffectCorrelation.next(),parentCorrelationId:Long?=null,checkActive:()->Unit={}):File? = EffectExportLog.trace(EffectExportScope.AUDIO_PREPARE,correlationId,parentCorrelationId) { log ->
        checkActive()
        log.phase(EffectExportStep.AUDIO_LOCK)
        while(!prepareLock.tryLock(20,TimeUnit.MILLISECONDS))checkActive()
        try {checkActive();return@trace prepareLocked(context,kind,slow,file,checkActive,log)}
        finally {prepareLock.unlock()}
    }
    private fun prepareLocked(context:Context,kind:EffectKind,slow:Boolean,file:File?,checkActive:()->Unit,log:EffectExportLog):File? {
        if(kind!=EffectKind.OPTICAL_LEAK || file==null || !file.isFile){log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.AUDIO_ABSENT,EffectExportReason.AUDIO_SOURCE_MISSING));return null}
        val key=key(kind,slow,file)
        if(key in noAudio){log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.AUDIO_ABSENT,EffectExportReason.AUDIO_TRACK_ABSENT));return null}
        val dir=File(context.cacheDir,"effect-audio-mix").apply{mkdirs()}
        val output=File(dir,"$key.m4a")
        if(output.isFile && output.length()>0){output.setLastModified(System.currentTimeMillis());log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.AUDIO_CACHE_HIT,bytes=output.length()));return output}
        val partial=File(dir,"$key.part")
        try {
            checkActive()
            log.phase(EffectExportStep.AUDIO_DECODE)
            val source=decode({it.setDataSource(file.absolutePath)},1_635_000L,checkActive)
            if(source==null){log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.AUDIO_ABSENT,EffectExportReason.AUDIO_TRACK_ABSENT));noAudio.add(key);while(noAudio.size>8)noAudio.remove(noAudio.first());return null}
            val endUs=EffectMotionAudioEnvelope.endUs(slow,source.durationUs)
            val base=decode({ extractor ->
                context.assets.openFd(EffectAudioTrack.assetPath(kind,slow)).use {
                    extractor.setDataSource(it.fileDescriptor,it.startOffset,it.length)
                }
            },3_000_000L,checkActive)?:run {log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.AUDIO_FALLBACK,EffectExportReason.AUDIO_BASE_UNAVAILABLE));return null}
            log.phase(EffectExportStep.AUDIO_MIX)
            for(frame in 0 until FRAMES) {
                val timeUs=frame*1_000_000L/EffectMotionAudioEnvelope.SAMPLE_RATE
                for(channel in 0 until CHANNELS) {
                    val i=frame*CHANNELS+channel
                    base.samples[i]=EffectMotionAudioEnvelope.mix(base.samples[i],source.samples[i],timeUs,endUs,source.startUs)
                }
            }
            log.phase(EffectExportStep.AUDIO_ENCODE)
            checkActive();encode(base.samples,partial,checkActive);checkActive()
            check(partial.renameTo(output)){"Cannot finalize local motion audio"}
            dir.listFiles()?.filter{it.extension=="m4a" && it!=output}
                ?.sortedByDescending{it.lastModified()}?.drop(3)?.forEach{it.delete()}
            log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.AUDIO_READY,bytes=output.length()))
            return output
        }catch(e:CancellationException){throw e}
        catch(e:Exception){log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.AUDIO_FALLBACK,EffectExportLog.reason(e),log.step));return null}
        catch(_:OutOfMemoryError){log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.AUDIO_FALLBACK,EffectExportReason.OUT_OF_MEMORY,log.step));return null}
        finally {partial.delete()}
    }
    private data class Pcm(val samples:FloatArray,val durationUs:Long,val startUs:Long)
    private fun decode(open:(MediaExtractor)->Unit,endUs:Long,checkActive:()->Unit):Pcm? {
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        try {
            open(extractor)
            val track=(0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
            }?:return null
            val format=extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val video=(0 until extractor.trackCount).map{extractor.getTrackFormat(it)}.firstOrNull {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true && it.containsKey(MediaFormat.KEY_DURATION)
            }
            val clipDuration=minOf(video?.getLong(MediaFormat.KEY_DURATION)?:endUs,endUs)
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=AudioFormat.ENCODING_PCM_16BIT
            require(rate in 8000..192000 && channels in 1..2)
            val decoder=MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec=decoder
            format.setInteger(MediaFormat.KEY_PCM_ENCODING,encoding)
            decoder.configure(format,null,null,0);decoder.start()
            val samples=FloatArray(FRAMES*CHANNELS)
            val info=MediaCodec.BufferInfo()
            val deadline=SystemClock.elapsedRealtime()+6000
            var inputDone=false;var done=false;var latestEnd=0L;var start=Long.MAX_VALUE
            while(!done) {
                checkActive();check(SystemClock.elapsedRealtime()<deadline){"Audio preparation deadline"}
                if(!inputDone) {
                    val index=decoder.dequeueInputBuffer(0)
                    if(index>=0) {
                        val buffer=requireNotNull(decoder.getInputBuffer(index));buffer.clear()
                        val pts=extractor.sampleTime
                        val size=extractor.readSampleData(buffer,0)
                        if(size<0 || pts>endUs+200_000L) {
                            decoder.queueInputBuffer(index,0,0,endUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true
                        }else {
                            decoder.queueInputBuffer(index,0,size,pts,0);extractor.advance()
                        }
                    }
                }
                val index=decoder.dequeueOutputBuffer(info,5000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual=decoder.outputFormat
                    rate=actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding=if(actual.containsKey(MediaFormat.KEY_PCM_ENCODING))actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(rate in 8000..192000 && channels in 1..2 && encoding in setOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT))
                }else if(index>=0) {
                    try {
                        val buffer=decoder.getOutputBuffer(index)
                        if(buffer!=null && info.size>0) {
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val bytes=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)4 else 2
                            val count=info.size/(channels*bytes)
                            val bufferEnd=info.presentationTimeUs+count*1_000_000L/rate
                            latestEnd=maxOf(latestEnd,bufferEnd);start=minOf(start,info.presentationTimeUs.coerceAtLeast(0))
                            val from=maxOf(0L,(info.presentationTimeUs*48000+999999)/1_000_000).toInt()
                            val until=minOf(FRAMES.toLong(),(bufferEnd*48000+999999)/1_000_000,endUs*48000/1_000_000).toInt()
                            fun sample(frame:Int,channel:Int):Float {
                                val offset=info.offset+(frame.coerceIn(0,count-1)*channels+minOf(channel,channels-1))*bytes
                                return if(bytes==4)buffer.getFloat(offset).let{if(it.isFinite())it.coerceIn(-1f,1f) else 0f}
                                    else buffer.getShort(offset)/32768f
                            }
                            if(count>0)for(frame in from until until) {
                                val position=(frame/48000.0-info.presentationTimeUs/1_000_000.0)*rate
                                val low=floor(position).toInt();val alpha=(position-low).toFloat()
                                for(channel in 0 until CHANNELS)samples[frame*CHANNELS+channel]=sample(low,channel)*(1-alpha)+sample(low+1,channel)*alpha
                            }
                            if(info.presentationTimeUs>=endUs)done=true
                        }
                        if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)done=true
                    }finally{decoder.releaseOutputBuffer(index,false)}
                }
            }
            return Pcm(samples,minOf(clipDuration,latestEnd).coerceAtLeast(0),if(start==Long.MAX_VALUE)0 else start)
        }finally {
            runCatching{codec?.stop()};runCatching{codec?.release()};extractor.release()
        }
    }
    private fun encode(pcm:FloatArray,file:File,checkActive:()->Unit) {
        var codec:MediaCodec?=null;var muxer:MediaMuxer?=null;var started=false
        try {
            val format=MediaFormat.createAudioFormat("audio/mp4a-latm",48000,CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,256000);setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
            }
            val encoder=MediaCodec.createEncoderByType("audio/mp4a-latm");codec=encoder
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            val writer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=writer
            val info=MediaCodec.BufferInfo();val deadline=SystemClock.elapsedRealtime()+6000
            var frame=0;var inputDone=false;var done=false;var track=-1;var written=0
            while(!done) {
                checkActive();check(SystemClock.elapsedRealtime()<deadline){"Audio encode deadline"}
                if(!inputDone) {
                    val index=encoder.dequeueInputBuffer(0)
                    if(index>=0) {
                        val buffer=requireNotNull(encoder.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN);buffer.clear()
                        val count=minOf(buffer.remaining()/4,FRAMES-frame)
                        for(i in frame until frame+count)for(channel in 0 until CHANNELS)
                            buffer.putShort((pcm[i*CHANNELS+channel].coerceIn(-1f,1f)*32767).roundToInt().toShort())
                        encoder.queueInputBuffer(index,0,count*4,frame*1_000_000L/48000,if(count==0)MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frame+=count;if(count==0)inputDone=true
                    }
                }
                val index=encoder.dequeueOutputBuffer(info,5000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!started);track=writer.addTrack(encoder.outputFormat);writer.start();started=true
                }else if(index>=0) {
                    try {
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0 && info.presentationTimeUs in 0 until 3_000_000L) {
                            check(started);val buffer=requireNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            writer.writeSampleData(track,buffer,info);written++
                        }
                        if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)done=true
                    }finally {encoder.releaseOutputBuffer(index,false)}
                }
            }
            check(written>0);info.set(0,0,3_000_000L,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            writer.writeSampleData(track,ByteBuffer.allocate(0),info);writer.stop();started=false
        }finally {
            if(started)runCatching{muxer?.stop()};runCatching{muxer?.release()}
            runCatching{codec?.stop()};runCatching{codec?.release()}
        }
    }
}

internal object EffectMotionAudioEnvelope {
    const val SAMPLE_RATE=48000
    const val CHANNELS=2
    const val SOURCE_GAIN=0.3f
    const val FADE_IN_US=80000L
    const val FADE_OUT_US=120000L
    fun gain(timeUs:Long,endUs:Long,startUs:Long=0):Float {
        if(timeUs<startUs || timeUs>=endUs || endUs<=startUs)return 0f
        val length=endUs-startUs
        return minOf(1f,(timeUs-startUs).toFloat()/minOf(FADE_IN_US,length),(endUs-timeUs).toFloat()/minOf(FADE_OUT_US,length))*SOURCE_GAIN
    }
    fun endUs(slow:Boolean,clipDurationUs:Long):Long=minOf(clipDurationUs.coerceAtLeast(0),
        (EffectTimeline.opticalHoldMs(slow)+EffectTimeline.opticalTransitionMs(slow)*135/225)*1000L)
    fun mix(base:Float,source:Float,timeUs:Long,endUs:Long,startUs:Long=0):Float=
        (base+source*gain(timeUs,endUs,startUs)).coerceIn(-1f,1f)
}
