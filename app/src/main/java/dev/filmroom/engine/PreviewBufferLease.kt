package dev.filmroom.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only private JNI scratch is reusable. Published Bitmaps and borrowed RAW buffers never enter here.
 * The byte cap includes BOTH idle and outstanding leases. The pool does not strongly own borrowers.
 * A caller must retain a lease until native work is known to have stopped, including failure paths.
 */
internal class PreviewBufferPool(private val maxBytes:Long=64L*1024*1024) : AutoCloseable {
    enum class Kind { LINEAR_INPUT, RGBA8_OUTPUT, LINEAR_OUTPUT }
    private data class Entry(val kind:Kind,val bytes:Int,val buffer:ByteBuffer)
    private val idle=ArrayDeque<Entry>()
    private var allocated=0L
    private var borrowed=0L
    private var closed=false
    init { require(maxBytes>0) }
    class Lease internal constructor(buffer:ByteBuffer,release:(Boolean)->Unit):AutoCloseable {
        private var owned:ByteBuffer?=buffer
        private var release:((Boolean)->Unit)?=release
        val buffer:ByteBuffer @Synchronized get()=checkNotNull(owned){"Buffer lease is closed"}
        private fun finish(reusable:Boolean){
            val action=synchronized(this){owned=null;release.also{release=null}}
            action?.invoke(reusable)
        }
        /** Discard damaged/partial buffers instead of returning them for reuse. */
        fun discard()=finish(false)
        override fun close()=finish(true)
    }
    /** null means no room. Do not silently allocate outside the byte budget. */
    @Synchronized fun acquire(kind:Kind,bytes:Int):Lease? {
        require(bytes>0)
        check(!closed)
        if(bytes.toLong()>maxBytes)return null
        val reusable=idle.firstOrNull{it.kind==kind && it.bytes==bytes}
        if(reusable!=null)idle.remove(reusable)
        else {
            while(allocated+bytes>maxBytes && idle.isNotEmpty())allocated-=idle.removeFirst().bytes
            if(allocated+bytes>maxBytes)return null
        }
        val entry=reusable ?: Entry(kind,bytes,ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()))
        if(reusable==null)allocated+=bytes
        borrowed+=bytes
        entry.buffer.clear();entry.buffer.order(ByteOrder.nativeOrder())
        return Lease(entry.buffer){reusableOnReturn->synchronized(this){
            borrowed-=entry.bytes
            if(closed || !reusableOnReturn)allocated-=entry.bytes else idle.addLast(entry)
            check(allocated>=borrowed && borrowed>=0)
        }}
    }
    /** Pressure drops only idle memory. In-flight JNI remains its caller's responsibility. */
    @Synchronized fun trim(){while(idle.isNotEmpty())allocated-=idle.removeFirst().bytes}
    @Synchronized override fun close(){closed=true;trim()}
    internal data class Usage(val allocatedBytes:Long,val borrowedBytes:Long,val idleCount:Int)
    @Synchronized internal fun usage()=Usage(allocated,borrowed,idle.size)
}
