package dev.filmroom.effects

import android.content.Context
import android.net.Uri
import dev.filmroom.io.PhotoSource
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Reconstructed from exp6 APK. One current-photo cache with independently retained readers.
 * Create before dispatching extraction; acquire is worker-only. Close each decoder's lease only
 * when that decoder has actually stopped reading, not when its asynchronous close is requested.
 */
internal class HomeMotionInputOwner private constructor(
    private val sourceLease:AutoCloseable,
    private val extract:((()->Unit)->File?),
):AutoCloseable {
    constructor(context:Context,source:PhotoSource):this(source.retainDetailSource(),{ checkActive ->
        val app=context.applicationContext
        val original=source.originalUri
        val fromProvider=if(original!=null)EffectMotionInput.prepare(app,original,checkActive=checkActive) else null
        fromProvider ?: kotlin.run {
            checkActive()
            EffectMotionInput.prepare(app,Uri.fromFile(source.localFile).toString(),checkActive=checkActive)
        }
    })
    private val lock=java.lang.Object()
    private var references=1
    private var closed=false
    private var preparing=false
    private var prepared=false
    private var video:File?=null

    fun acquire(checkActive:()->Unit={}):Lease {
        synchronized(lock){check(!closed){"Motion input owner closed"};references++}
        var transferred=false
        try {
            prepare(checkActive)
            checkActive()
            return Lease(this,synchronized(lock){video}).also{transferred=true}
        }finally{if(!transferred)release()}
    }
    private fun prepare(checkActive:()->Unit){
        synchronized(lock){
            while(preparing){checkActive();lock.wait(25)}
            checkActive()
            if(prepared)return
            preparing=true
        }
        var output:File?=null
        var adopted=false
        try {
            output=extract(checkActive)
            checkActive()
            synchronized(lock){video=output;prepared=true;adopted=true}
        }finally{
            if(!adopted)output?.delete()
            synchronized(lock){preparing=false;lock.notifyAll()}
        }
    }
    private fun retain(file:File?):Lease=synchronized(lock){
        check(references>0){"Motion input already released"}
        references++
        Lease(this,file)
    }
    private fun release(){
        val cleanup=synchronized(lock){
            check(references>0)
            references--
            if(references==0)Pair(video,sourceLease).also{video=null} else null
        }
        cleanup?.let{try{it.first?.delete()}finally{it.second.close()}}
    }
    override fun close(){
        val shouldRelease=synchronized(lock){if(closed)false else {closed=true;true}}
        if(shouldRelease)release()
    }
    class Lease internal constructor(private val owner:HomeMotionInputOwner,val file:File?):AutoCloseable {
        private val closed=AtomicBoolean(false)
        @Synchronized fun retain():Lease {check(!closed.get()){ "Motion input lease closed" };return owner.retain(file)}
        @Synchronized override fun close(){if(closed.compareAndSet(false,true))owner.release()}
    }
    companion object {
        internal fun forTest(sourceLease:AutoCloseable=AutoCloseable{},extract:((()->Unit)->File?))=
            HomeMotionInputOwner(sourceLease,extract)
    }
}
