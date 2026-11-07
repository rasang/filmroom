package dev.filmroom.ui

/** Per-card elapsed-time presentation clock, independent of durable export state.
 * Visible phase time includes the fade. Hidden phases age only after genuine
 * source/negative/positive artifacts are available, without invisible fades.
 */
internal class ExportPreviewSequence {
    companion object { const val MIN_STAGE_MS=500L }
    var active=false; private set
    var started=false; private set
    var negativeName:String?=null; private set
    // The selector only supplies same-or-higher quality from this attempt. Remember
    // a successful upgrade without changing phase/deadline or replaying the ritual.
    fun rememberNegative(name:String){negativeName=name}
    var phase=0; private set // 0 not loaded, 1 contour, 2 negative, 3 positive
    private var visible=false
    private var deadline:Long?=null
    private var originalReadyAt:Long?=null
    private var negativeReadyAt:Long?=null
    private var positiveReadyAt:Long?=null
    private var skipMissingNegative=false
    private var hiddenThrough:Long?=null
    val needsTimeline get()=active && (!visible || (hiddenThrough!=null && phase<3))
    fun start() {if(!started){started=true;active=true}}
    fun stop() {active=false;deadline=null}
    fun reset(){active=false;started=false;phase=0;negativeName=null;deadline=null;visible=false;originalReadyAt=null;negativeReadyAt=null;positiveReadyAt=null;skipMissingNegative=false;hiddenThrough=null}
    /** Times use the caller's monotonic clock, converted from current-attempt artifact timestamps. */
    fun timeline(original:Long?,negative:Long?,positive:Long?,maySkipNegative:Boolean=false) {
        originalReadyAt=original;negativeReadyAt=negative;positiveReadyAt=positive;skipMissingNegative=maySkipNegative
        // Lifecycle resume can precede the latest durable job emission. A newly
        // discovered artifact still receives the background time it actually had.
        hiddenThrough?.let(::advanceHidden)
    }
    fun visibility(value:Boolean,now:Long){advance(now);visible=value}
    private fun advance(now:Long) {
        if(!active || visible || (phase==0 && originalReadyAt==null && negativeReadyAt==null && (!skipMissingNegative || positiveReadyAt==null)))return
        hiddenThrough=now;advanceHidden(now)
    }
    private fun advanceHidden(now:Long) {
        if(!active)return
        if(phase==0) {
            val first=if(originalReadyAt!=null)1 else if(negativeReadyAt!=null)2 else if(skipMissingNegative)3 else return
            val ready=when(first){1->originalReadyAt;2->negativeReadyAt;else->positiveReadyAt} ?: return
            if(ready>now)return
            phase=first;deadline=ready+MIN_STAGE_MS
        }
        while(phase in 1..2) {
            val previousEnd=deadline ?: break
            if(now<previousEnd)break
            val next=if(phase==1 && negativeReadyAt!=null)2 else if(phase==2 || skipMissingNegative)3 else break
            val ready=(if(next==2)negativeReadyAt else positiveReadyAt) ?: break
            if(ready>now)break
            phase=next;deadline=maxOf(previousEnd,ready)+MIN_STAGE_MS
        }
    }
    fun remaining(now:Long):Long {advance(now);return deadline?.let{(it-now).coerceAtLeast(0)} ?: 0}
    fun shown(next:Int,now:Long) {
        advance(now)
        if(!active || next<phase)return
        if(next!=phase){phase=next;deadline=now+MIN_STAGE_MS}
        // Reloading the current phase after rotation/reentry never restarts its clock.
    }
    fun finish(now:Long):Boolean {
        advance(now)
        if(active && phase==3 && remaining(now)==0L){active=false;return true}
        return false
    }
    /** Clamp only the visible rail/text, never durable/native progress. A later
     * phase cannot certify 99% for the earlier image that is still visible. */
    fun constrain(stage:Int,percent:Int?):Pair<Int,Int?> {
        if(!active)return stage to percent
        val limit=when(phase){0->1;1->4;2->5;else->6}
        return when {stage>limit->limit to null;stage==limit->stage to percent?.coerceAtMost(99);else->stage to percent}
    }
}
