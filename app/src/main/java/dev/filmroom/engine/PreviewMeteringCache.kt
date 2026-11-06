package dev.filmroom.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Single-flight per source, with close invalidation even if native completes late.
 * Entries contain scalars/identity only; no retained grid, source file or native allocation.
 */
internal class PreviewMeteringCache<T> {
    private val mutex=Mutex()
    private val publicationLock=Any()
    private var closed=false
    private val owner=UUID.randomUUID().toString()
    private var revision:String?=null
    private val entries=mutableMapOf<String,T>()
    fun close()=synchronized(publicationLock){closed=true;entries.clear()}
    suspend fun get(key:String,method:String,build:suspend (String)->T):T=mutex.withLock {
        currentCoroutineContext().ensureActive()
        synchronized(publicationLock) {
            check(!closed){"Metering source closed"}
            if(revision!=key){entries.clear();revision=key}
            entries[method]
        }?.let{return@withLock it}
        val result=build(owner)
        currentCoroutineContext().ensureActive()
        synchronized(publicationLock){check(!closed){"Metering source closed"};entries[method]=result}
        result
    }
}
