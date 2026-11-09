package dev.filmroom.effects

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import dev.filmroom.io.PhotoLimits
import java.io.File

internal class EffectSequentialMotionDecoder(file:File,maxEdge:Int=PhotoLimits.PREVIEW_EDGE):AutoCloseable {
    private val extractor=MediaExtractor()
    private var codec:MediaCodec?=null
    private val info=MediaCodec.BufferInfo()
    private val edge=maxEdge.coerceIn(240,PhotoLimits.PREVIEW_EDGE)
    private var rotation=0
    private var inputEnded=false
    private var outputEnded=false
    private var closed=false
    private var lastPts=Long.MIN_VALUE
    private var outputFormat:MediaFormat?=null
    private var first=true
    private var heldIndex=-1
    private var heldPts=0L
    private var converter:EffectMotionYuvConverter?=null
    init {
        try {
            extractor.setDataSource(file.absolutePath)
            val track=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString("mime")?.startsWith("video/")==true} ?: error("No motion video track")
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            val w=format.getInteger("width");val h=format.getInteger("height")
            require(w>0 && h>0 && w.toLong()*h<=EffectSurfaceDecoderPolicy.SOFTWARE_PIXELS){"Motion coded size exceeds bounded sequential preview budget"}
            rotation=if(format.containsKey("rotation-degrees"))format.getInteger("rotation-degrees")else 0
            converter=EffectMotionYuvConverter(edge,rotation)
            format.setInteger("rotation-degrees",0)
            format.setInteger("color-format",2135033992)
            requireSdr(format)
            val dec=MediaCodec.createDecoderByType(requireNotNull(format.getString("mime")))
            codec=dec;dec.configure(format,null,null,0);dec.start();outputFormat=format
        }catch(t:Throwable){close();throw t}
    }
    fun frameAt(timeUs:Long):Bitmap? {
        check(!closed)
        val target=timeUs.coerceAtLeast(0)
        if((outputEnded && heldIndex<0)||target<=lastPts)return null
        if(heldIndex>=0 && heldPts>target && !first)return null
        val dec=requireNotNull(codec)
        val deadline=SystemClock.elapsedRealtime()+if(first)1000L else 45L
        var index=heldIndex;var pts=heldPts;heldIndex=-1
        try {
            while(index<0 || (pts<target && !outputEnded)) {
                feed(dec)
                val next=dec.dequeueOutputBuffer(info,1000)
                if(next==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){outputFormat=dec.outputFormat;requireSdr(requireNotNull(outputFormat))}
                else if(next>=0) {
                    val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if(info.size>0){
                        if(info.presentationTimeUs>target && !first){heldIndex=next;heldPts=info.presentationTimeUs;if(eos)outputEnded=true;break}
                        if(index>=0)dec.releaseOutputBuffer(index,false)
                        index=next;pts=info.presentationTimeUs
                    }else dec.releaseOutputBuffer(next,false)
                    if(eos)outputEnded=true
                    if((index>=0 && (pts>=target||eos))||eos)break
                }
                if(SystemClock.elapsedRealtime()>=deadline)break
            }
            if(index<0)return null
            val image=dec.getOutputImage(index) ?: error("Decoder provides no YUV image")
            val bitmap=image.use { requireNotNull(converter).convert(it,requireNotNull(outputFormat)) }
            lastPts=pts;first=false
            return bitmap
        }finally{if(index>=0)dec.releaseOutputBuffer(index,false)}
    }
    private fun feed(decoder:MediaCodec){
        if(inputEnded)return
        val index=decoder.dequeueInputBuffer(0);if(index<0)return
        val buffer=requireNotNull(decoder.getInputBuffer(index));buffer.clear()
        val size=extractor.readSampleData(buffer,0)
        if(size<0){inputEnded=true;decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM)}
        else{decoder.queueInputBuffer(index,0,size,extractor.sampleTime,0);extractor.advance()}
    }
    fun reset(){
        check(!closed)
        if(heldIndex>=0){requireNotNull(codec).releaseOutputBuffer(heldIndex,false);heldIndex=-1}
        extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);requireNotNull(codec).flush()
        inputEnded=false;outputEnded=false;lastPts=Long.MIN_VALUE;first=true
    }
    private fun requireSdr(format:MediaFormat){
        require(!format.containsKey("color-standard")||format.getInteger("color-standard")!=6){"Wide-gamut motion requires platform color conversion"}
        if(format.containsKey("color-transfer")){val t=format.getInteger("color-transfer");require(t!=6&&t!=7){"HDR motion requires platform color conversion"}}
    }
    override fun close(){if(closed)return;closed=true;converter=null;codec?.let{runCatching{it.stop()};runCatching{it.release()}};codec=null;runCatching{extractor.release()}}
}
