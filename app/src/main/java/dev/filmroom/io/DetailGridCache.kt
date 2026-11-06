package dev.filmroom.io

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-local, PhotoSource-owned immutable grid. No source-file lease is retained.
 * The slot owns at most 1536² RGB floats on disk (27 MiB), never pixels in heap memory.
 * Replaced entries can survive only while existing readers still hold them.
 */
internal class DetailGridCache(
    private val enqueueCleanup:((()->Unit)->Unit)={action->cleanupScope.launch {action()};Unit},
) : AutoCloseable {
    companion object {
        private val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    }
    private val retirementLock=Any()
    private val retirements=mutableSetOf<CompletableDeferred<Unit>>()
    private fun retire(source:DetailSource) {
        val done=CompletableDeferred<Unit>()
        synchronized(retirementLock){retirements.add(done)}
        enqueueCleanup {
            try {runCatching{source.close()}} finally {
                synchronized(retirementLock){retirements.remove(done)}
                done.complete(Unit)
            }
        }
    }
    /** Wait for already-scheduled cleanup, after releasing readers and/or the cache owner.
     * Does not wait for outstanding reader leases or unfinished builders to be released.
     */
    internal suspend fun awaitRetirements() {
        while(true) {
            val pending=synchronized(retirementLock){retirements.toList()}
            if(pending.isEmpty())return
            pending.forEach{it.await()}
        }
    }
    internal class Built(val source:DetailSource,val valid:()->Boolean)
    private class Entry(val key:Any,val built:Built,private val retire:(DetailSource)->Unit) {
        private var references=1 // Builder ownership, transferred to the cache slot on publish.
        fun reader(cacheHit:Boolean):DetailSource=synchronized(this) {
            check(references>0)
            var closed=false
            val source=built.source
            val reader=DetailSource(source.width,source.height,source.sourceRevision,source.mappingRevision,source.relativePath,cacheHit) {
                val shouldRelease=synchronized(this){if(closed)false else {closed=true;true}}
                if(shouldRelease)release()
            }
            references++;reader
        }
        fun release() {
            val dispose=synchronized(this){check(references>0);--references==0}
            if(dispose)retire(built.source)
        }
    }
    internal class Acquisition(val source:DetailSource,private val abort:(Boolean)->Unit) {
        /** Roll back a new publication if its caller was cancelled during dispatcher return. */
        fun abandon()=abort(false)
        /** Source/grid changed after acquisition; remove this slot even on a hit. */
        fun invalidate()=abort(true)
    }
    private val mutex=Mutex()
    private val publicationLock=Any()
    private var closed=false
    private var cached:Entry?=null

    private fun remove(entry:Entry) {
        val removed=synchronized(publicationLock){if(cached===entry){cached=null;true}else false}
        if(removed)entry.release()
    }
    private fun acquisition(entry:Entry,reader:DetailSource,published:Boolean)=Acquisition(reader){invalidate->
        try{if(invalidate || published)remove(entry)}finally{reader.close()}
    }
    suspend fun acquire(key:Any,build:suspend ()->Built):Acquisition=mutex.withLock {
        currentCoroutineContext().ensureActive()
        val candidate=synchronized(publicationLock) {
            check(!closed){"Detail source cache closed"}
            cached?.let{it to it.reader(true)}
        }
        if(candidate!=null) {
            val (entry,reader)=candidate
            try {
                if(entry.key==key && entry.built.valid()) {
                    currentCoroutineContext().ensureActive()
                    return@withLock acquisition(entry,reader,false)
                }
            } catch(t:Throwable) {
                try{if(t !is CancellationException)remove(entry)}finally{reader.close()}
                throw t
            }
            try{remove(entry)}finally{reader.close()}
        }
        // Drain already-retired idle grids before another cold allocation. Live reader grids
        // are independent: their cleanup is not scheduled until those readers leave.
        awaitRetirements()
        currentCoroutineContext().ensureActive()
        synchronized(publicationLock){check(!closed){"Detail source cache closed"}}
        // Only a complete validated result may become the slot's owner.
        val built=build()
        var entry:Entry?=null
        var reader:DetailSource?=null
        var published=false
        try {
            currentCoroutineContext().ensureActive()
            require(built.source.width in 1..1536 && built.source.height in 1..1536)
            check(built.valid()){ "Detail grid changed before publication" }
            val made=Entry(key,built,::retire);entry=made
            val job=currentCoroutineContext()
            val leased=synchronized(publicationLock) {
                check(!closed){"Detail source cache closed"}
                job.ensureActive()
                made.reader(false).also{reader=it;cached=made;published=true}
            }
            acquisition(made,leased,true)
        } catch(t:Throwable) {
            val made=entry
            if(made==null)retire(built.source) else {
                try{if(published)remove(made) else made.release()}finally{reader?.close()}
            }
            throw t
        }
    }
    override fun close() {
        val old=synchronized(publicationLock){closed=true;cached.also{cached=null}}
        old?.release()
    }
}
