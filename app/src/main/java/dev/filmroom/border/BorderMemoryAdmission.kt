package dev.filmroom.border

import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.engine.NativeWorkUnavailable
import dev.filmroom.io.PhotoException

/** Admission estimate, not an allocator cap. Border work shares the native memory ledger but
 * takes no heavyweight worker slot. The lease covers the synchronous work and always closes.
 * Heap accounting includes existing borrowed/cache atlases via actual Runtime headroom.
 */
object BorderMemoryAdmission {
    const val DECODE_ESTIMATE_BYTES=32L*1024*1024
    const val HEAP_MARGIN_BYTES=16L*1024*1024
    private fun heapHeadroom():Long=Runtime.getRuntime().let{it.maxMemory()-it.totalMemory()+it.freeMemory()}
    private fun unavailable():Nothing=throw PhotoException(PhotoException.Reason.TOO_LARGE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_022fe563baa4))
    fun <T> withBudget(estimate:Long,memory:()->NativeMemorySnapshot,heapAvailable:()->Long=::heapHeadroom,work:()->T):T {
        require(estimate in 1..(2L*1024*1024*1024))
        if(heapAvailable()<Math.addExact(estimate,HEAP_MARGIN_BYTES))unavailable()
        val lease=try {NativeWorkReservations.retain(estimate,memory)}catch(_:NativeWorkUnavailable){unavailable()}
        try {return work()}finally{lease.close()}
    }
}
