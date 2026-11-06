package dev.filmroom.engine

/** Passive byte ownership for one native source plus FilmLog and pre-brightness stages. The native clear
 * call is synchronous with borrowed readers, and disables late publication before
 * the ledger charge is released. Never owns a Bitmap or borrowed direct buffer.
 */
internal class PreviewStageCacheOwner(
    private val configureNative:(Long,Long)->Unit,
    private val reserve:(Long,()->Unit)->AutoCloseable,
) : AutoCloseable {
    private val lock=Any()
    private data class Owned(val handle:Long,val identity:String,val bytes:Long,val serial:Long,val lease:AutoCloseable)
    private var current:Owned?=null
    private var serial=0L
    private var closed=false
    fun prepare(handle:Long,identity:String,width:Int,height:Int):Boolean=synchronized(lock) {
        check(!closed){"Preview cache owner closed"}
        val bytes=bytesFor(width,height)
        current?.takeIf{it.handle==handle && it.identity==identity && it.bytes==bytes}?.let{return true}
        retireLocked()
        val token=++serial
        val lease=try {reserve(bytes){retire(token)}} catch(_:NativeWorkUnavailable){return false}
        val owned=Owned(handle,identity,bytes,token,lease)
        current=owned
        try {configureNative(handle,bytes)} catch(t:Throwable) {
            // Configuration failed: clear must succeed before the charge may go.
            retireLocked();throw t
        }
        true
    }
    private fun retire(token:Long)=synchronized(lock){if(current?.serial==token)retireLocked()}
    private fun retireLocked() {
        val previous=current ?: return
        // If native retirement fails, retain the ledger charge. Never announce
        // free bytes while the physical owner may still be alive.
        configureNative(previous.handle,0)
        current=null
        previous.lease.close()
    }
    fun retire()=synchronized(lock){retireLocked()}
    override fun close()=synchronized(lock){closed=true;retireLocked()}
    companion object {
        const val MAX_BYTES=64L*1024*1024
        fun candidateBytes(width:Int,height:Int)=Math.multiplyExact(Math.multiplyExact(width.toLong(),height.toLong()),48L)
        fun candidateFits(width:Int,height:Int,budgetMiB:Int):Boolean =
            runCatching { bytesFor(width,height) }.isSuccess && 96L*1024*1024+width.toLong()*height*512+candidateBytes(width,height)<=budgetMiB.toLong()*1024*1024
        fun bytesFor(width:Int,height:Int):Long {
            require(width>0 && height>0)
            val pixels=Math.multiplyExact(width.toLong(),height.toLong())
            val density=Math.addExact(Math.multiplyExact(pixels,60L),64L*1024)
            val combined=Math.addExact(Math.multiplyExact(pixels,48L),64L*1024)
            // Preserve the existing brightness cache at the largest preview size.
            // Native admits FilmLog only if the combined entry fits this lease.
            val bytes=if(density<=MAX_BYTES)density else if(combined<=MAX_BYTES)combined else Math.addExact(Math.multiplyExact(pixels,36L),64L*1024)
            require(bytes<=MAX_BYTES)
            return bytes
        }
    }
}
