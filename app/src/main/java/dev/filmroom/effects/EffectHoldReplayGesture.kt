package dev.filmroom.effects

/** One-finger hold replay; dragging before the deadline remains navigation. */
internal class EffectHoldReplayGesture(private val slop:Float,
    private val schedule:(Runnable,Long)->Unit, private val remove:(Runnable)->Unit,
    private val canReplay:()->Boolean, private val replay:()->Unit, private val cover:()->Unit) {
    private var pending:Runnable?=null
    private var downX=0f; private var downY=0f
    var held=false
        private set
    fun down(x:Float,y:Float) {
        cancel();downX=x;downY=y
        if(canReplay()) {
            lateinit var task:Runnable
            task=Runnable { if(pending===task) { pending=null;if(canReplay()){held=true;replay()} } }
            pending=task;schedule(task,HOLD_MS)
        }
    }
    fun move(x:Float,y:Float) { if(!held && (kotlin.math.abs(x-downX)>slop || kotlin.math.abs(y-downY)>slop))cancelPending() }
    fun finish():Boolean { val consumed=held;cancel();return consumed }
    fun cancel() { cancelPending();if(held){held=false;cover()} }
    private fun cancelPending(){pending?.let(remove);pending=null}
    companion object { const val HOLD_MS=350L }
}
