package dev.filmroom.effects

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import dev.filmroom.io.PhotoLimits
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class EffectMotionFrame internal constructor(val sharp:Bitmap,val blurred:Bitmap?):AutoCloseable {
    override fun close(){if(!sharp.isRecycled)sharp.recycle();blurred?.takeUnless{it.isRecycled}?.recycle()}
}
interface EffectMotionFrameSource:AutoCloseable {
    fun reset()
    fun frameAt(timeMs:Long,slow:Boolean):EffectMotionFrame?
    fun isUnavailable():Boolean
}
object EffectMotionTiming {
    fun sampleUs(timeMs:Long,slow:Boolean,durationUs:Long):Long {
        val end=EffectTimeline.opticalHoldMs(slow)+EffectTimeline.opticalTransitionMs(slow)*135/225
        val t=timeMs.coerceIn(0,end)
        val us=if(t==end)end*1000 else ((t*30+500)/1000)*1000000/30
        return us.coerceAtMost((durationUs-1).coerceAtLeast(0))
    }
}
class EffectMotionSource internal constructor(file:File,private val composition:EffectSourceComposition?=null,maxEdge:Int=PhotoLimits.PREVIEW_EDGE,private val preview:Boolean=false):EffectMotionFrameSource {
    private var decoder:MediaMetadataRetriever?=MediaMetadataRetriever()
    private val inputFile=file
    private var sequential:EffectSequentialMotionDecoder?=null
    private val durationUs:Long
    private val width:Int
    private val height:Int
    private var lastUs=Long.MIN_VALUE
    private var failed=false
    init {
        try {
            val d=requireNotNull(decoder);d.setDataSource(file.absolutePath)
            durationUs=(d.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0)*1000
            require(durationUs>0){"Motion video has no duration"}
            val w=d.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0
            val h=d.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0
            require(w>0&&h>0)
            val scale=minOf(1.0,maxEdge.coerceIn(240,PhotoLimits.PREVIEW_EDGE).toDouble()/maxOf(w,h))
            width=(w*scale).roundToInt().coerceAtLeast(1);height=(h*scale).roundToInt().coerceAtLeast(1)
            if(preview){d.release();decoder=null;sequential=runCatching{EffectSequentialMotionDecoder(file,maxEdge)}.getOrNull();if(sequential==null)openRetriever()}
        }catch(t:Throwable){decoder?.release();sequential?.close();throw t}
    }
    override fun isUnavailable()=failed
    private fun openRetriever(){val d=MediaMetadataRetriever();try{d.setDataSource(inputFile.absolutePath);decoder=d}catch(t:Throwable){d.release();throw t}}
    @Synchronized override fun reset(){lastUs=Long.MIN_VALUE;try{sequential?.reset()}catch(_:Exception){sequential?.close();sequential=null;try{openRetriever()}catch(_:Exception){failed=true}catch(_:OutOfMemoryError){failed=true}}}
    @Synchronized override fun frameAt(timeMs:Long,slow:Boolean):EffectMotionFrame? {
        if(failed)return null
        val sample=EffectMotionTiming.sampleUs(timeMs,slow,durationUs);if(sample==lastUs)return null
        var raw:Bitmap?=null;var sharp:Bitmap?=null
        try {
            raw=if(sequential!=null)try{sequential!!.frameAt(sample)}catch(_:Exception){sequential?.close();sequential=null;openRetriever();decoder!!.getScaledFrameAtTime(sample,MediaMetadataRetriever.OPTION_CLOSEST,width,height)}
                else decoder!!.getScaledFrameAtTime(sample,MediaMetadataRetriever.OPTION_CLOSEST,width,height)
            val frame=raw ?: return null
            sharp=composition?.apply(frame)?:frame
            if(sharp!==frame)frame.recycle()
            val blurred=if(preview)null else EffectTextures.defocused(sharp)
            lastUs=sample
            return EffectMotionFrame(sharp,blurred).also{raw=null;sharp=null}
        }catch(_:Exception){failed=true;return null}catch(_:OutOfMemoryError){failed=true;return null}
        finally{raw?.takeUnless{it.isRecycled}?.recycle();sharp?.takeUnless{it.isRecycled}?.recycle()}
    }
    @Synchronized override fun close(){failed=true;decoder?.release();decoder=null;sequential?.close();sequential=null}
}
internal class EffectMotionPreview(file:File,composition:EffectSourceComposition?=null,maxEdge:Int=720,sourceFactory:(()->EffectMotionFrameSource)?=null):AutoCloseable {
    private var source:EffectMotionFrameSource?=null
    private val createSource=sourceFactory?:{EffectMotionSource(file,composition,maxEdge,true)}
    private var decoderGeneration=-1
    private var unavailable=false
    @Volatile private var ready=false
    @Volatile private var staticFallback=false
    private val executor=Executors.newSingleThreadExecutor{Thread(it,"effect-motion").apply{isDaemon=true}}
    private var wanted:Pair<Long,Boolean>?=null
    private var pending:EffectMotionFrame?=null
    private var running=false
    private var closed=false
    private var generation=0
    init{request(0,false)}
    fun isReady()=ready
    fun isUnavailable()=staticFallback
    @Synchronized fun request(timeMs:Long,slow:Boolean){
        if(closed)return;wanted=timeMs to slow;if(running)return;running=true
        executor.execute{
            while(true){
                val request=synchronized(this){val next=wanted;if(closed||next==null){running=false;null}else{wanted=null;Triple(next.first,next.second,generation)}}?:break
                if(source==null&&!unavailable)try{source=createSource()}catch(_:Exception){unavailable=true}catch(_:OutOfMemoryError){unavailable=true}
                val frame=try{if(decoderGeneration!=request.third){source?.reset();decoderGeneration=request.third};source?.frameAt(request.first,request.second)}
                    catch(_:Exception){unavailable=true;runCatching{source?.close()};source=null;null}
                    catch(_:OutOfMemoryError){unavailable=true;runCatching{source?.close()};source=null;null}
                synchronized(this){if(!closed&&generation==request.third){staticFallback=unavailable||source?.isUnavailable()==true;ready=ready||frame!=null||staticFallback;if(frame!=null){pending?.close();pending=frame}}else frame?.close()}
            }
        }
    }
    @Synchronized fun poll():EffectMotionFrame?=pending.also{pending=null}
    @Synchronized fun reset(){if(closed)return;generation++;wanted=null;pending?.close();pending=null;ready=false;staticFallback=false;request(0,false)}
    @Synchronized override fun close(){if(closed)return;closed=true;wanted=null;pending?.close();pending=null;executor.execute{runCatching{source?.close()};source=null};executor.shutdown()}
}
