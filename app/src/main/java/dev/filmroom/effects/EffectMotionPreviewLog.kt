package dev.filmroom.effects

import android.os.SystemClock
import dev.filmroom.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

internal class EffectMotionPreviewLog(
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val sink: (MotionPreviewDiagnosticInfo, Outcome) -> Unit = { info,outcome -> MotionPreviewDiagnostics.record(info,outcome) },
) {
    private val created=clockMs()
    private var began:Long?=null
    private var firstFrameAt:Long?=null
    private var lastFrameAt:Long?=null
    private var ended=false
    private var selected=MotionPreviewPath.STATIC
    private var fallback=MotionPreviewFallback.NONE
    private var hardware=MotionPreviewHardware.UNKNOWN
    private var width:Int?=null
    private var height:Int?=null
    private var decoder:MotionPreviewDecoderSnapshot?=null
    private var count=0L
    private var lagCount=0L
    private var lagSum=0L
    private var lagMax=0L
    private var submitSum=0L
    private var submitMax=0L
    @Synchronized fun path(path:MotionPreviewPath,reason:MotionPreviewFallback=MotionPreviewFallback.NONE,width:Int?=null,height:Int?=null,hardware:Boolean?=null) {
        if(ended)return
        selected=path
        if(reason!=MotionPreviewFallback.NONE)fallback=reason
        width?.takeIf{it in 1..30000}?.let{this.width=it}
        height?.takeIf{it in 1..30000}?.let{this.height=it}
        this.hardware=when(hardware){true->MotionPreviewHardware.HARDWARE;false->MotionPreviewHardware.SOFTWARE;null->MotionPreviewHardware.UNKNOWN}
    }
    @Synchronized fun decoder(snapshot:MotionPreviewDecoderSnapshot){if(!ended)decoder=snapshot}
    @Synchronized fun started(){if(!ended&&began==null)began=clockMs()}
    @Synchronized fun frame(sourcePtsUs:Long?,timelineMs:Long,submitCpuNs:Long){
        if(ended||count>=10000||(sourcePtsUs!=null&&sourcePtsUs<0)||timelineMs !in 0L..60000L||submitCpuNs<0)return
        val now=clockMs();if(firstFrameAt==null)firstFrameAt=now;lastFrameAt=now
        sourcePtsUs?.let{val lag=(timelineMs*1000-it).coerceIn(0,60000000);lagCount++;lagSum+=lag;lagMax=maxOf(lagMax,lag)}
        val submit=(submitCpuNs/1000).coerceAtMost(60000000);count++;submitSum+=submit;submitMax=maxOf(submitMax,submit)
    }
    @Synchronized internal fun snapshot()=MotionPreviewDiagnosticInfo(
        path=selected,fallback=fallback,hardware=hardware,sourceWidth=width,sourceHeight=height,submittedFrames=count,
        prepareMs=began?.let{(it-created).coerceIn(0,60000)},activeMs=began?.let{(clockMs()-it).coerceIn(0,60000)},
        sampleSpanMs=firstFrameAt?.let{(requireNotNull(lastFrameAt)-it).coerceIn(0,60000)},
        ptsLagMeanUs=if(lagCount>0)lagSum/lagCount else null,ptsLagMaxUs=if(lagCount>0)lagMax else null,
        glSubmitCpuMeanUs=if(count>0)submitSum/count else null,glSubmitCpuMaxUs=if(count>0)submitMax else null,decoder=decoder)
    @Synchronized fun end(outcome:Outcome=Outcome.SUCCESS){if(ended)return;val info=snapshot();ended=true;sink(info,outcome)}
}

private object MotionPreviewDiagnostics {
    private data class Report(val info:MotionPreviewDiagnosticInfo,val outcome:Outcome)
    private val dropped=AtomicLong()
    private val queue=Channel<Report>(16)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    init { scope.launch { for(report in queue) runCatching {
        val info=SafeInfo(motionPreview=report.info.copy(suppressedReports=dropped.getAndSet(0)))
        Diagnostics.end(Diagnostics.begin(Operation.PREVIEW_PRESENTATION,info),report.outcome,info)
    } } }
    fun record(info:MotionPreviewDiagnosticInfo,outcome:Outcome){if(queue.trySend(Report(info,outcome)).isFailure)dropped.updateAndGet{(it+1).coerceAtMost(1000000)}}
}
