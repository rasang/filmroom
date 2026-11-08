package dev.filmroom.effects

/** UI-thread owned, at most two idle neighbours. take transfers ownership to the renderer. */
internal class EffectAdjacentCache<T:Any>(private val release:(T)->Unit) {
    private val entries=linkedMapOf<String,T>()
    val size get()=entries.size
    fun contains(id:String)=entries.containsKey(id)
    fun take(id:String,accept:(T)->Boolean={true}):T? {
        val value=entries.remove(id) ?: return null
        if(accept(value))return value
        release(value);return null
    }
    fun put(id:String,value:T){
        entries.remove(id)?.let{if(it!==value)release(it)}
        entries[id]=value
        while(entries.size>2)entries.remove(entries.keys.first())?.let(release)
    }
    fun retain(ids:Set<String>){entries.keys.toList().filter{it !in ids}.forEach{entries.remove(it)?.let(release)}}
    fun clear(){val old=entries.values.toList();entries.clear();old.forEach(release)}
}
