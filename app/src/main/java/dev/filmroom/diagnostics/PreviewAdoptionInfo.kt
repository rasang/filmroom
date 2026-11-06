package dev.filmroom.diagnostics

import dev.filmroom.model.EditorState
import dev.filmroom.safeInfo
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local counters and allowlisted SETTINGS only. Never hash photo bytes, names or URIs. */
enum class PreviewFrameKind { QUICK, REFINED, CACHED }
enum class PreviewDisposition { BOUND, ADOPTED, CACHE_ONLY, CANCELLED, FAILED, STALE_SOURCE, STALE_GENERATION }
class PreviewDiagnosticContext(val sourceToken:Long,val generation:Long,val scienceFingerprint:Long,val kind:PreviewFrameKind,val originGeneration:Long=generation,val originKind:PreviewFrameKind=kind):AbstractCoroutineContextElement(Key) {
    var timing:PreviewTiming?=null
    companion object Key:CoroutineContext.Key<PreviewDiagnosticContext> {
        private val tokens=AtomicLong()
        fun nextSourceToken()=tokens.incrementAndGet()
        fun fingerprint(parameters:EditorState):Long {
            val settings=parameters.safeInfo().fields().toSortedMap().entries.joinToString("\n"){"${it.key}=${it.value}"}
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(settings.toByteArray(Charsets.UTF_8))).long and 0x001fffffffffffffL
        }
    }
    fun enrich(info:SafeInfo)=info.copy(previewSourceToken=sourceToken,previewGeneration=generation,previewOriginGeneration=originGeneration,previewOriginKind=originKind,previewScienceFingerprint=scienceFingerprint,previewKind=kind)
}
data class PreviewAdoptionRecord(val context:PreviewDiagnosticContext,val disposition:PreviewDisposition,val nativeCompleted:Boolean,val observedElapsedMs:Long=android.os.SystemClock.elapsedRealtime(),val timing:PreviewTimingSnapshot?=null,val failureInfo:SafeInfo?=null) {
    fun safeInfo()=context.enrich((failureInfo ?: SafeInfo()).copy(previewTiming=timing,previewDisposition=disposition,previewObservedElapsedMs=observedElapsedMs,previewNativeCompleted=if(disposition==PreviewDisposition.BOUND)null else nativeCompleted))
}
/** At most eight terminal presentation records/second; store has its existing hard capacity.
 * Native render traces still retain correlation fields when a presentation record is rate limited. */
internal class PreviewDiagnosticRateGate {
    private var window=Long.MIN_VALUE
    private var count=0
    private var suppressed=0L
    @Synchronized fun accept(elapsedMs:Long):Long? {
        val now=elapsedMs/1000
        if(now!=window){window=now;count=0}
        if(count>=8){suppressed=(suppressed+1).coerceAtMost(1_000_000);return null}
        count++
        return suppressed.also{suppressed=0}
    }
}
internal object PreviewAdoptionDiagnostics {
    private val rate=PreviewDiagnosticRateGate()
    private val timingRate=PreviewDiagnosticRateGate()
    private val dropped=AtomicLong()
    private val queue=Channel<PreviewAdoptionRecord>(16)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    init { scope.launch {
        for(record in queue) runCatching {
            val suppressed=(if(record.timing!=null)timingRate else rate).accept(android.os.SystemClock.elapsedRealtime()) ?: return@runCatching
            val info=record.safeInfo().copy(previewSuppressed=(suppressed+dropped.getAndSet(0)).coerceAtMost(1_000_000))
            val id=Diagnostics.begin(Operation.PREVIEW_PRESENTATION,info)
            Diagnostics.end(id,if(record.disposition==PreviewDisposition.CANCELLED)Outcome.CANCELLED else if(record.disposition==PreviewDisposition.FAILED)Outcome.FAILED else Outcome.SUCCESS)
        }
    } }
    /** Nonblocking and bounded: never await diagnostic IO on the render/adoption path. */
    fun record(record:PreviewAdoptionRecord) {
        if(!queue.trySend(record).isSuccess)dropped.updateAndGet{(it+1).coerceAtMost(1_000_000)}
    }
}
