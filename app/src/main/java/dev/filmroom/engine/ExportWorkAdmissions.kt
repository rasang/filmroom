package dev.filmroom.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Queue BEFORE the shared heavy-memory/worker reservation. A waiting export
 * must not occupy the second heavy slot needed by interactive preview/ROI.
 * Cancellation while waiting acquires no native reservation; exceptions and
 * cancellation of an owner release this admission through withLock's finally.
 */
internal class ExportWorkAdmission {
    private val mutex=Mutex()
    suspend fun <T> run(work:suspend ()->T):T=mutex.withLock { work() }
}
internal object ExportWorkAdmissions {
    private val admission=ExportWorkAdmission()
    suspend fun <T> run(work:suspend ()->T):T=admission.run(work)
}
