package dev.filmroom.ui

/** Per-card presentation clock. Only time actually in the foreground counts.
 * A phase is acknowledged only after genuine pixels have been accepted by the view.
 * Dwell starts after the existing fade; this clock never drives export or saved state.
 */
internal class ExportPreviewSequence {
    var active=false; private set
    var started=false; private set
    var negativeName:String?=null; private set
    fun rememberNegative(name:String){if(negativeName==null)negativeName=name}
    var phase=0; private set // 0 not loaded, 1 contour, 2 negative, 3 positive
    private var visible=false
    private var tick=0L
    private var remaining=0L
    fun start() {if(!started){started=true;active=true}}
    fun stop() {active=false;remaining=0}
    fun reset(){active=false;started=false;phase=0;negativeName=null;remaining=0;visible=false}
    fun visibility(value:Boolean,now:Long){advance(now);visible=value;tick=now}
    private fun advance(now:Long){
        if(visible && active)remaining=(remaining-(now-tick).coerceAtLeast(0)).coerceAtLeast(0)
        tick=now
    }
    fun remaining(now:Long):Long {advance(now);return remaining}
    fun shown(next:Int,fadeMs:Long,now:Long) {
        advance(now)
        if(!active || next<phase)return
        if(next!=phase){phase=next;remaining=1500+fadeMs}
        else remaining+=fadeMs // A released/recreated view must finish its new fade first.
    }
    fun finish(now:Long):Boolean {
        advance(now)
        if(active && phase==3 && remaining==0L){active=false;return true}
        return false
    }
    /** Clamp only the visible rail/text, never the durable/native progress. */
    fun constrain(stage:Int,percent:Int?):Pair<Int,Int?> {
        if(!active)return stage to percent
        val limit=when(phase){0->1;1->4;2->5;else->6}
        return when {stage>limit->limit to 99;stage==limit->stage to percent?.coerceAtMost(99);else->stage to percent}
    }
}
