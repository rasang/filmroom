package dev.filmroom.export

import dev.filmroom.engine.ExportDisplayProgress

/** Ordered work passes, not elapsed-time prediction. Each declared pass has equal weight;
 * its fraction comes only from completed rows/bytes. A pass is certified only after its
 * enclosing operation (including flush/rename) returns. The final public 100% belongs to cleanup.
 */
internal class MeasuredStageWork(private val stage:Int, private val steps:List<String>, private val nanoTime:()->Long=System::nanoTime, private val publish:(ExportDisplayProgress)->Unit) {
    private var index=0
    private var last=-1L
    private var publishedAt=0L
    init { require(stage==1 || stage==7); require(steps.isNotEmpty() && steps.distinct().size==steps.size) }
    private fun emit(value:Long,force:Boolean=false) {
        val bounded=value.coerceAtMost(steps.size*SCALE-1)
        val now=nanoTime()
        if(last<0 || ((force || now-publishedAt>=100_000_000L) && bounded*100/(steps.size*SCALE)>last*100/(steps.size*SCALE))) { last=bounded;publishedAt=now; publish(ExportDisplayProgress(stage,bounded,steps.size*SCALE,measured=true)) }
    }
    fun report(step:String,done:Long,total:Long) {
        require(done>=0 && total>=done)
        val target=steps.indexOf(step);require(target>=0)
        if(target<index || total==0L)return
        // Later work must not silently certify an unfinished earlier pass.
        check(target==index)
        val part=java.math.BigInteger.valueOf(done).multiply(java.math.BigInteger.valueOf(SCALE)).divide(java.math.BigInteger.valueOf(total)).toLong().coerceAtMost(SCALE-1)
        emit(index*SCALE+part,done==total)
    }
    fun finish(step:String) {
        val target=steps.indexOf(step);require(target>=0)
        if(target<index)return
        check(target==index); index++;emit(index*SCALE,true)
    }
    fun start(){emit(0)}
    companion object { private const val SCALE=10_000L }
}
