package dev.filmroom.effects

import android.media.*
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.filmroom.diagnostics.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal object EffectSurfaceColorPolicy {
    fun needsConversion(transfer:Int,standard:Int)=transfer==6 || transfer==7 || standard==6
    fun acceptsOutput(requestedConversion:Boolean,transfer:Int,standard:Int)=!needsConversion(transfer,standard) && (!requestedConversion || (transfer==3 && standard in setOf(1,2,4)))
}
internal class EffectSurfaceFrameGate {
    var generation=0;private set
    var renderedGeneration=-1;private set
    var inFlight=false;private set
    fun reset():Int {generation++;return generation}
    fun offer(epoch:Int):Boolean {if(inFlight || epoch!=generation)return false;inFlight=true;renderedGeneration=epoch;return true}
    fun consumed(){inFlight=false}
}

internal class EffectSurfaceMotionDecoder(private val file:File,private val surface:Surface,
    private val onFailure:(String)->Unit={},private val onClosed:()->Unit={}):AutoCloseable {
    private val worker=ScheduledThreadPoolExecutor(1){r->Thread(r,"effect-surface-video").apply{isDaemon=true}}.apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false);removeOnCancelPolicy=true
    }
    private val lock=Any()
    private val gate=EffectSurfaceFrameGate()
    @Volatile private var closed=false
    @Volatile var width=0;private set
    @Volatile var height=0;private set
    @Volatile var rotationDegrees=0;private set
    @Volatile var durationUs=0L;private set
    @Volatile var isReady=false;private set
    @Volatile var isUnavailable=false;private set
    @Volatile var generation=0;private set
    @Volatile var renderedGeneration=-1;private set
    @Volatile var renderedPtsUs=Long.MIN_VALUE;private set
    @Volatile var codecName="";private set
    @Volatile var hardwareAccelerated=false;private set
    private var extractor:MediaExtractor?=null
    private var codec:MediaCodec?=null
    private val info=MediaCodec.BufferInfo()
    private var inputEnded=false
    private var outputEnded=false
    private var outputValidated=false
    private var requireSdrOutput=false
    private var heldIndex=-1
    private var heldPts=0L
    private var candidateIndex=-1
    private var candidatePts=0L
    private var lastPresented=Long.MIN_VALUE
    private var first=true
    private var scheduled=false
    private var resetWanted=false
    private var requestedTimeMs=0L
    private var requestedSlow=false
    private var requestedRevision=0L
    @Volatile private var codedWidth=0
    @Volatile private var codedHeight=0
    @Volatile private var decoderCandidates=0
    @Volatile private var hardwareCandidates=0
    @Volatile private var capabilityMatches=0
    @Volatile private var attemptedCandidates=0
    @Volatile private var failedCandidates=0
    @Volatile private var selection=MotionPreviewDecoderSelection.NOT_STARTED
    @Volatile private var failure=MotionPreviewFallback.NONE
    @Volatile private var sourceFrameRateMilli=0
    @Volatile private var sourceProfile:Int?=null
    @Volatile private var sourceLevel:Int?=null
    @Volatile private var sourceBitDepth:Int?=null
    @Volatile private var capabilityRateChecked=false
    @Volatile private var selectedRateSupported:Boolean?=null
    private var decodedFrames=0
    private var discardedFrames=0
    private var queuedFrames=0
    private var consumedFrames=0L
    private var previousSourcePts=Long.MIN_VALUE
    private var sourceIntervalCount=0L
    private var sourceIntervalTotalUs=0L
    private var sourceIntervalMaxUs=0L
    private var workerCount=0L
    private var workerTotalUs=0L
    private var workerMaxUs=0L
    private var surfaceWaitCount=0L
    private var surfaceWaitMaxUs=0L
    private var queuedAtNanos=0L
    init {worker.execute{try{open();schedule()}catch(e:Exception){fail("initialize",e)}catch(e:OutOfMemoryError){fail("initialize-memory",e)}}}
    fun snapshot():MotionPreviewDecoderSnapshot=synchronized(lock){
        MotionPreviewDecoderSnapshot(codedWidth,codedHeight,if(codecName.isEmpty())null else hardwareAccelerated,
            decoderCandidates,hardwareCandidates,capabilityMatches,attemptedCandidates,failedCandidates,selection,failure,
            decodedFrames.toLong(),discardedFrames.toLong(),queuedFrames.toLong(),consumedFrames,
            sourceIntervalCount,sourceIntervalTotalUs,sourceIntervalMaxUs,workerCount,workerTotalUs,workerMaxUs,
            surfaceWaitCount,maxOf(surfaceWaitMaxUs,if(gate.inFlight&&queuedAtNanos>0)(System.nanoTime()-queuedAtNanos)/1000 else 0),
            sourceFrameRateMilli,sourceProfile,sourceLevel,sourceBitDepth,capabilityRateChecked,selectedRateSupported)
    }
    fun request(timeMs:Long,slow:Boolean){synchronized(lock){if(closed||isUnavailable)return;requestedTimeMs=timeMs;requestedSlow=slow;requestedRevision++;schedule()}}
    fun reset(){synchronized(lock){if(closed)return;generation=gate.reset();isReady=false;resetWanted=true;requestedTimeMs=0;requestedSlow=false;requestedRevision++;schedule()}}
    fun notifyFrameConsumed(){
        synchronized(lock){if(gate.inFlight){consumedFrames++;surfaceWaitCount++;if(queuedAtNanos>0)surfaceWaitMaxUs=maxOf(surfaceWaitMaxUs,(System.nanoTime()-queuedAtNanos)/1000)};gate.consumed();queuedAtNanos=0}
        schedule()
    }
    private fun schedule(delayMs:Long=0){synchronized(lock){
        if(closed||isUnavailable||scheduled)return
        scheduled=true
        worker.schedule({val start=System.nanoTime();try{tick()}catch(e:Exception){fail("decode",e)}catch(e:OutOfMemoryError){fail("decode-memory",e)}finally{
            val us=(System.nanoTime()-start)/1000;synchronized(lock){workerCount++;workerTotalUs+=us;workerMaxUs=maxOf(workerMaxUs,us)}
        }},delayMs,TimeUnit.MILLISECONDS)
    }}
    private fun open(){
        val ext=MediaExtractor();extractor=ext;ext.setDataSource(file.absolutePath)
        val track=(0 until ext.trackCount).firstOrNull{ext.getTrackFormat(it).getString("mime")?.startsWith("video/")==true} ?: throw Rejected(MotionPreviewFallback.MISSING_VIDEO_TRACK)
        ext.selectTrack(track);val format=ext.getTrackFormat(track)
        width=format.getInteger("width");height=format.getInteger("height")
        synchronized(lock){codedWidth=width;codedHeight=height}
        accept(width>0&&height>0,MotionPreviewFallback.INVALID_CODED_SIZE)
        durationUs=if(format.containsKey("durationUs"))format.getLong("durationUs")else 0L
        val rate=(if(format.containsKey("frame-rate"))runCatching{format.getNumber("frame-rate")?.toDouble()}.getOrNull()else null)?.takeIf{it.isFinite()&&it>0&&it<=1000}
        synchronized(lock){sourceFrameRateMilli=rate?.let{(it*1000).roundToInt()}?:0
            sourceProfile=if(format.containsKey("profile"))format.getInteger("profile")else null
            sourceLevel=if(format.containsKey("level"))format.getInteger("level")else null
            sourceBitDepth=if(format.containsKey("bit-depth"))runCatching{format.getInteger("bit-depth")}.getOrNull()else null
            capabilityRateChecked=rate!=null}
        accept(EffectSurfaceDecoderPolicy.withinBudget(width,height),MotionPreviewFallback.CODED_SIZE_BUDGET)
        accept(durationUs>0,MotionPreviewFallback.MISSING_DURATION)
        rotationDegrees=if(format.containsKey("rotation-degrees"))format.getInteger("rotation-degrees")else 0
        format.setInteger("rotation-degrees",0)
        requireSdrOutput=needsSdrConversion(format)
        if(requireSdrOutput){accept(Build.VERSION.SDK_INT>=31,MotionPreviewFallback.HDR_CONVERSION_API);format.setInteger("color-transfer-request",3)}
        val mime=requireNotNull(format.getString("mime"))
        val available=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter{!it.isEncoder && !it.isAlias && it.supportedTypes.any{t->t.equals(mime,true)}}
        synchronized(lock){decoderCandidates=available.size;hardwareCandidates=available.count{it.isHardwareAccelerated}}
        if(available.isEmpty()){synchronized(lock){selection=MotionPreviewDecoderSelection.NO_CANDIDATE};throw Rejected(MotionPreviewFallback.NO_DECODER_CANDIDATE)}
        val query=MediaFormat.createVideoFormat(mime,width,height)
        sourceProfile?.let{query.setInteger("profile",it)};sourceLevel?.let{query.setInteger("level",it)}
        val candidates=available.mapIndexed { index, item ->
            var rateSupported:Boolean?=null
            val supported=runCatching {
                val caps=item.getCapabilitiesForType(mime);val video=caps.videoCapabilities
                if(video!=null && rate!=null)rateSupported=runCatching{video.areSizeAndRateSupported(width,height,rate)}.getOrNull()
                video!=null && video.isSizeSupported(width,height) && caps.isFormatSupported(query)
            }.getOrDefault(false)
            EffectSurfaceDecoderPolicy.Candidate(index,item.isHardwareAccelerated,supported,rateSupported)
        }
        synchronized(lock){capabilityMatches=candidates.count{it.supported}}
        val order=EffectSurfaceDecoderPolicy.candidates(width,height,candidates)
        if(order.isEmpty()){
            synchronized(lock){selection=MotionPreviewDecoderSelection.NO_CAPABILITY_MATCH}
            throw Rejected(if(width.toLong()*height>EffectSurfaceDecoderPolicy.SOFTWARE_PIXELS && hardwareCandidates==0)MotionPreviewFallback.NO_HARDWARE_FOR_LARGE_SOURCE else MotionPreviewFallback.NO_CAPABLE_DECODER)
        }
        var lastFailure=MotionPreviewFallback.DECODER_CONFIGURE
        var stage=MotionPreviewFallback.DECODER_CONFIGURE
        val opened=EffectSurfaceDecoderPolicy.openFirst(order,{closed},{index->stage=MotionPreviewFallback.DECODER_CONFIGURE;MediaCodec.createByCodecName(available[index].name)},
            {attempt,index->
                if(candidates[index].rateSupported==true && rate!=null)format.setFloat("frame-rate",rate.toFloat())else format.removeKey("frame-rate")
                attempt.configure(format,surface,null,0);stage=MotionPreviewFallback.DECODER_START;attempt.start()
            },{attempt->runCatching{attempt.stop()};attempt.release()},
            {synchronized(lock){attemptedCandidates++}},{_,_->synchronized(lock){failedCandidates++};lastFailure=stage})
        if(opened==null){if(closed)return;synchronized(lock){selection=MotionPreviewDecoderSelection.ALL_CANDIDATES_FAILED};throw Rejected(lastFailure)}
        val (index,dec)=opened;codec=dec
        synchronized(lock){codecName=dec.name;hardwareAccelerated=available[index].isHardwareAccelerated;selectedRateSupported=candidates[index].rateSupported
            selection=if(hardwareAccelerated)MotionPreviewDecoderSelection.HARDWARE_SUPPORTED else MotionPreviewDecoderSelection.SOFTWARE_SUPPORTED}
        outputValidated=false
        Log.i(TAG,"path=surface hardware=$hardwareAccelerated size=${width}x$height attempts=$attemptedCandidates matches=$capabilityMatches sdrRequest=$requireSdrOutput")
    }
    private fun tick(){
        val revision:Long;val epoch:Int;val time:Long;val slow:Boolean
        synchronized(lock){scheduled=false;if(closed||isUnavailable)return;revision=requestedRevision;epoch=generation;time=requestedTimeMs;slow=requestedSlow}
        val dec=codec?:return
        val reset=synchronized(lock){val r=resetWanted;resetWanted=false;r}
        if(reset){releaseHeld(dec);dec.flush();requireNotNull(extractor).seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);inputEnded=false;outputEnded=false;lastPresented=Long.MIN_VALUE;first=true;previousSourcePts=Long.MIN_VALUE}
        if(synchronized(lock){gate.inFlight})return
        val target=EffectMotionTiming.sampleUs(time,slow,durationUs)
        if(target<=lastPresented || (outputEnded && heldIndex<0 && candidateIndex<0))return
        if(heldIndex>=0){if(heldPts>target&&!first)return;candidateIndex=heldIndex;candidatePts=heldPts;heldIndex=-1}
        val deadline=SystemClock.elapsedRealtime()+8
        var waiting=false
        while(!closed && SystemClock.elapsedRealtime()<deadline && (candidateIndex<0 || (candidatePts<target&&!outputEnded))){
            EffectSurfaceDecoderPolicy.feedBatch{feed(dec)}
            val index=dec.dequeueOutputBuffer(info,0)
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){val format=dec.outputFormat;validateOutput(format);updateVisibleSize(format);outputValidated=true}
            else if(index<0){waiting=true;break}
            else if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)dec.releaseOutputBuffer(index,false)
            else {
                val eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if(info.size>0 || !eos){
                    synchronized(lock){decodedFrames++;if(previousSourcePts!=Long.MIN_VALUE&&info.presentationTimeUs>previousSourcePts){val interval=info.presentationTimeUs-previousSourcePts;sourceIntervalCount++;sourceIntervalTotalUs+=interval;sourceIntervalMaxUs=maxOf(sourceIntervalMaxUs,interval)};previousSourcePts=info.presentationTimeUs}
                    if(info.presentationTimeUs>target&&!first){heldIndex=index;heldPts=info.presentationTimeUs;if(eos)outputEnded=true;break}
                    if(candidateIndex>=0){dec.releaseOutputBuffer(candidateIndex,false);synchronized(lock){discardedFrames++}}
                    candidateIndex=index;candidatePts=info.presentationTimeUs
                }else dec.releaseOutputBuffer(index,false)
                if(eos){outputEnded=true;break}
            }
        }
        if(closed)return
        if(candidateIndex>=0){
            if(!outputValidated){val format=dec.outputFormat;validateOutput(format);updateVisibleSize(format);outputValidated=true}
            val offered=synchronized(lock){if(epoch!=generation||resetWanted)false else gate.offer(epoch).also{if(it){renderedGeneration=epoch;renderedPtsUs=candidatePts;queuedAtNanos=System.nanoTime()}}}
            if(offered){val index=candidateIndex;candidateIndex=-1
                try{dec.releaseOutputBuffer(index,true);lastPresented=candidatePts;first=false;synchronized(lock){if(epoch==generation&&!resetWanted&&!closed)isReady=true;queuedFrames++};return}
                catch(t:Throwable){synchronized(lock){gate.consumed()};throw t}
            }
            releaseHeld(dec)
        }
        val changed=synchronized(lock){requestedRevision!=revision||resetWanted}
        if(changed||(waiting&&!outputEnded&&heldIndex<0))schedule(2)
    }
    private fun feed(dec:MediaCodec):Boolean {
        if(inputEnded)return false
        val index=dec.dequeueInputBuffer(0);if(index<0)return false
        val buffer=checkNotNull(dec.getInputBuffer(index));buffer.clear();val ext=checkNotNull(extractor);val size=ext.readSampleData(buffer,0)
        if(size<0){inputEnded=true;dec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM)}else{dec.queueInputBuffer(index,0,size,ext.sampleTime,0);ext.advance()}
        return true
    }
    private fun updateVisibleSize(format:MediaFormat){
        if(!listOf("crop-left","crop-right","crop-top","crop-bottom").all{format.containsKey(it)})return
        val w=format.getInteger("crop-right")-format.getInteger("crop-left")+1;val h=format.getInteger("crop-bottom")-format.getInteger("crop-top")+1
        accept(EffectSurfaceDecoderPolicy.withinBudget(w,h),MotionPreviewFallback.OUTPUT_SIZE_BUDGET);width=w;height=h
    }
    private fun validateOutput(format:MediaFormat){accept(EffectSurfaceColorPolicy.acceptsOutput(requireSdrOutput,if(format.containsKey("color-transfer"))format.getInteger("color-transfer")else -1,if(format.containsKey("color-standard"))format.getInteger("color-standard")else -1),MotionPreviewFallback.HDR_CONVERSION_OUTPUT)}
    private fun needsSdrConversion(format:MediaFormat)=EffectSurfaceColorPolicy.needsConversion(if(format.containsKey("color-transfer"))format.getInteger("color-transfer")else 0,if(format.containsKey("color-standard"))format.getInteger("color-standard")else 0)
    private fun releaseHeld(dec:MediaCodec){if(heldIndex>=0){runCatching{dec.releaseOutputBuffer(heldIndex,false)};heldIndex=-1};if(candidateIndex>=0){runCatching{dec.releaseOutputBuffer(candidateIndex,false)};candidateIndex=-1}}
    private fun fail(stage:String,t:Throwable){
        if(closed)return
        synchronized(lock){failure=when(t){is Rejected->t.reason;is OutOfMemoryError->MotionPreviewFallback.OUT_OF_MEMORY;is IOException->MotionPreviewFallback.IO;else->if(stage.startsWith("initialize"))MotionPreviewFallback.DECODER_CONFIGURE else MotionPreviewFallback.DECODER_RUNTIME};isUnavailable=true;isReady=false}
        Log.w(TAG,"surface-fallback reason=${failure.name} queued=$queuedFrames");cleanup();runCatching{onFailure(failure.name)}
    }
    private fun cleanup(){codec?.let{releaseHeld(it);runCatching{it.stop()};runCatching{it.release()}};codec=null;runCatching{extractor?.release()};extractor=null}
    override fun close(){synchronized(lock){if(closed)return;closed=true;worker.execute{cleanup();Log.i(TAG,"surface-close hardware=$hardwareAccelerated decoded=$decodedFrames discarded=$discardedFrames queued=$queuedFrames");runCatching{onClosed()}};worker.shutdown()}}
    private class Rejected(val reason:MotionPreviewFallback):IllegalStateException(reason.name)
    private fun accept(condition:Boolean,reason:MotionPreviewFallback){if(!condition)throw Rejected(reason)}
    companion object {private const val TAG="FilmroomMotion"}
}
