package dev.filmroom.io

import android.graphics.Bitmap
import dev.filmroom.engine.NativeMemoryPolicy
import dev.filmroom.engine.NativeWorkUnavailable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One immutable, explicitly bounded source for ordinary whole-photo refinement.
 * It is independent of the 768 import image and zoom-detail source/session cache. */
internal class RefinedPreviewInput(val bitmap:Bitmap?=null,val linear:LinearPreview?=null,private val allowedEdge:Int=NativeMemoryPolicy.REFINED_PREVIEW_EDGE) {
    init { require((bitmap==null)!=(linear==null)) }
    val width:Int get()=bitmap?.width ?: checkNotNull(linear).width
    val height:Int get()=bitmap?.height ?: checkNotNull(linear).height
    val bytes:Long get()=bitmap?.allocationByteCount?.toLong() ?: checkNotNull(linear).buffer().capacity().toLong()
    init { require(allowedEdge in 1..NativeMemoryPolicy.ZOOM_REFINED_MAX_EDGE && width in 1..allowedEdge && height in 1..allowedEdge) }
    fun dispose(){bitmap?.takeUnless{it.isRecycled}?.recycle()}
}

/** Caller already owns the active refinement reservation before decoding/acquiring.
 * A passive lease accounts for cached input between frames. Pressure only retires
 * cache ownership: an in-flight native reader pins the pixels until its borrow ends. */
internal class RefinedPreviewSourceOwner(
    private val reserve:(Long,()->Unit)->AutoCloseable,
    private val load:suspend (PhotoSource,Int)->RefinedPreviewInput = ::loadRefinedPreviewSource,
):AutoCloseable {
    private val mutex=Mutex()
    private val lock=Any()
    private class Entry(val photo:PhotoSource,val revision:String,val maxEdge:Int,val input:RefinedPreviewInput,val token:Long) {
        var readers=1
        var retired=false
        var lease:AutoCloseable?=null
        var disposed=false
    }
    class Borrow internal constructor(val input:RefinedPreviewInput,private val release:()->Unit):AutoCloseable {
        private var closed=false
        override fun close(){synchronized(this){if(!closed){closed=true;release()}}}
    }
    private var serial=0L
    private var current:Entry?=null
    private var closed=false
    private fun disposeIfUnused(entry:Entry){
        if(entry.retired && entry.readers==0 && !entry.disposed){
            entry.disposed=true
            try {entry.input.dispose()} finally {entry.lease?.close();entry.lease=null}
        }
    }
    private fun borrow(entry:Entry)=Borrow(entry.input){synchronized(lock){entry.readers--;check(entry.readers>=0);disposeIfUnused(entry)}}
    private fun retireLocked(){current?.let {current=null;it.retired=true;disposeIfUnused(it)}}
    private fun retire(token:Long){synchronized(lock){if(current?.token==token)retireLocked()}}
    suspend fun acquire(photo:PhotoSource,maxEdge:Int=NativeMemoryPolicy.REFINED_PREVIEW_EDGE):Borrow=mutex.withLock {
        require(maxEdge in 1..NativeMemoryPolicy.ZOOM_REFINED_MAX_EDGE)
        currentCoroutineContext().ensureActive()
        val revision=photo.previewSourceRevision()
        synchronized(lock){
            check(!closed){"Refined preview source owner closed"}
            current?.takeIf{it.photo===photo && it.revision==revision && it.maxEdge==maxEdge}?.let{it.readers++;return@withLock borrow(it)}
            retireLocked()
        }
        var input:RefinedPreviewInput?=null
        var handedOff=false
        try {
            val decoded=load(photo,maxEdge)
            input=decoded
            check(maxOf(decoded.width,decoded.height)<=maxEdge){"Refined input exceeds requested edge"}
            currentCoroutineContext().ensureActive()
            check(photo.previewSourceRevision()==revision){"Source changed during refinement preparation"}
            synchronized(lock){
                check(!closed){"Refined preview source owner closed"}
                val entry=Entry(photo,revision,maxEdge,decoded,++serial)
                val bytes=Math.addExact(decoded.bytes,64L*1024)
                // Optional cache admission must never force a smaller frame or
                // erase the already-held active working-memory reservation.
                val lease=try{reserve(bytes){retire(entry.token)}}catch(_:NativeWorkUnavailable){null}
                entry.lease=lease
                if(lease==null)entry.retired=true else current=entry
                handedOff=true
                borrow(entry)
            }
        } finally {if(!handedOff)input?.dispose()}
    }
    fun retire(){synchronized(lock){retireLocked()}}
    override fun close(){synchronized(lock){closed=true;retireLocked()}}
}

/** Capture a decoded source across the cancellable IO-to-render dispatcher handoff. */
private suspend fun loadRefinedPreviewSource(photo:PhotoSource,maxEdge:Int):RefinedPreviewInput {
    var produced:RefinedPreviewInput?=null
    var handedOff=false
    try {
        val result=withContext(Dispatchers.IO) {
            val job=currentCoroutineContext()
            val source=photo.retainDetailSource()
            try {
                job.ensureActive()
                (photo.linearFile?.let {RefinedPreviewInput(linear=LinearSpool.refinedPreview(it,photo.originalWidth,photo.originalHeight,job,maxEdge),allowedEdge=maxEdge)}
                    ?: RefinedPreviewInput(bitmap=decodeFile(photo.localFile,maxEdge,job),allowedEdge=maxEdge)).also{produced=it}
            } finally {source.close()}
        }
        handedOff=true
        return result
    } finally {if(!handedOff)produced?.dispose()}
}
