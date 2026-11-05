package dev.filmroom.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Queue BEFORE the shared heavy-memory/worker reservation. A waiting export
 * must not occupy the second heavy slot needed by interactive preview/ROI.
 * Cancellation while waiting acquires no native reservation; exceptions and
 * cancellation of an owner release this admission through withLock's finally.
 */
internal class ExportWorkAdmission {
    private val mutex=Mutex()
    private val mutableActive=MutableStateFlow(false)
    val active=mutableActive.asStateFlow()
    suspend fun <T> run(work:suspend ()->T):T=mutex.withLock {
        mutableActive.value=true
        try { work() } finally { mutableActive.value=false }
    }
}
internal object ExportWorkAdmissions {
    private val admission=ExportWorkAdmission()
    val active=admission.active
    suspend fun <T> run(work:suspend ()->T):T=admission.run(work)
}
