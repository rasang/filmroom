package dev.filmroom.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide queue activity, including encoded hits. This is not a heavy-work
 * admission: it takes no export mutex, worker slot or byte reservation. */
internal class ExportQueueActivity(private val preemptIdle:()->Unit) {
    private val count=AtomicInteger()
    val active:Boolean get()=count.get()>0
    suspend fun <T> run(work:suspend ()->T):T {
        count.incrementAndGet()
        try {
            // Publish busy before inspecting the idle lease. The limiter checks
            // this flag under its own lock before installing a new idle callback.
            preemptIdle()
            return work()
        } finally {check(count.decrementAndGet()>=0)}
    }
}

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
        NativeWorkReservations.preemptIdle()
        try { work() } finally { mutableActive.value=false }
    }
}
internal object ExportWorkAdmissions {
    private val admission=ExportWorkAdmission()
    private val queueActivity:ExportQueueActivity=ExportQueueActivity(NativeWorkReservations::preemptIdle)
    val active=admission.active
    // Do not broaden active: it drives interactive scientific refinement policy.
    val prewarmBlocked:Boolean get()=active.value || queueActivity.active
    suspend fun <T> withQueueActivity(work:suspend ()->T):T=queueActivity.run(work)
    suspend fun <T> run(work:suspend ()->T):T=admission.run(work)
}
