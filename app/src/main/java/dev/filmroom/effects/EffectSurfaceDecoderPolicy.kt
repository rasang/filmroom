package dev.filmroom.effects

/** Recovered from beta27 DEX; retains the original bounded selection and retry policy. */
internal object EffectSurfaceDecoderPolicy {
    const val MAX_EDGE=4096
    const val MAX_PIXELS=12582912L
    const val SOFTWARE_PIXELS=2073600L
    const val INPUT_BATCH=4
    const val MAX_ATTEMPTS=4
    data class Candidate(val index:Int,val hardware:Boolean,val supported:Boolean,val rateSupported:Boolean?=null)
    fun withinBudget(width:Int,height:Int)=width>0 && height>0 && maxOf(width,height)<=MAX_EDGE && width.toLong()*height<=MAX_PIXELS
    fun candidates(width:Int,height:Int,available:List<Candidate>):List<Int> {
        if(!withinBudget(width,height))return emptyList()
        return available.filter { it.supported && (it.hardware || width.toLong()*height<=SOFTWARE_PIXELS) }
            .sortedWith(compareBy<Candidate>{if(it.hardware)0 else 1}.thenBy{when(it.rateSupported){true->0;null->1;false->2}}).map{it.index}
    }
    fun feedBatch(feedOne:()->Boolean):Int {var n=0;while(n<INPUT_BATCH && feedOne())n++;return n}
    fun <T:Any> openFirst(order:List<Int>,cancelled:()->Boolean,create:(Int)->T,initialize:(T,Int)->Unit,
                         release:(T)->Unit,onAttempt:(Int)->Unit={},onFailure:(Int,Throwable)->Unit={_,_->}):Pair<Int,T>? {
        for(i in order.take(MAX_ATTEMPTS)) {
            if(cancelled())return null
            onAttempt(i)
            var attempt:T?=null
            try {
                val created=create(i);attempt=created
                if(cancelled()){runCatching{release(created)};return null}
                initialize(created,i)
                if(cancelled()){runCatching{release(created)};return null}
                return i to created
            }catch(e:Exception){attempt?.let{runCatching{release(it)}};onFailure(i,e)}
            catch(e:OutOfMemoryError){attempt?.let{runCatching{release(it)}};onFailure(i,e);throw e}
        }
        return null
    }
}
