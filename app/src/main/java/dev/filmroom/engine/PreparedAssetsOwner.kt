package dev.filmroom.engine

/** Passive memory owner for one handle's immutable spectral fronts. The native
 * zero-budget call waits for active native work and releases the whole-pipeline
 * root plus cached fronts before returning success. A failed acknowledgement
 * keeps the ledger charge; clearing an index alone is never enough.
 *
 * No call is made from inside native work or with a limiter lock held. The owner
 * mutex may wait on native render_lock, but that render never acquires this mutex.
 */
internal class PreparedAssetsOwner(
    private val configureNative:(Long,Long)->Unit,
    private val reserve:(Long,()->Unit)->AutoCloseable,
) : AutoCloseable {
    private val lock=Any()
    private data class Owned(val handle:Long,val serial:Long,val lease:AutoCloseable)
    private var current:Owned?=null
    private var serial=0L
    private var closed=false

    fun prepare(handle:Long):Boolean=synchronized(lock) {
        check(!closed){"Prepared asset owner closed"}
        require(handle>0)
        if(current?.handle==handle)return true
        retireLocked()
        val token=++serial
        val lease=try {reserve(BYTES){retire(token)}} catch(_:NativeWorkUnavailable){return false}
        current=Owned(handle,token,lease)
        try {configureNative(handle,BYTES)} catch(t:Throwable) {
            try {retireLocked()} catch(retirement:Throwable) {t.addSuppressed(retirement)}
            throw t
        }
        true
    }
    private fun retire(token:Long)=synchronized(lock) {if(current?.serial==token)retireLocked()}
    private fun retireLocked() {
        val previous=current ?: return
        configureNative(previous.handle,0)
        current=null
        previous.lease.close()
    }
    fun retire()=synchronized(lock){retireLocked()}
    override fun close()=synchronized(lock){closed=true;retireLocked()}
    companion object { const val BYTES=8L*1024*1024 }
}
