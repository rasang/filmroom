package dev.filmroom.engine

import android.content.Context

/** Fixed admission is prepaid, never inferred from current device allocations. */
internal data class GpuScienceSessionBudget(val prepaidBytes: Long, val fixedBytes: Long, val pipelineLimit: Int) {
    /** A replacement epoch may resize its fixed reserve, never its prepaid cap. */
    fun forValidatedStage(plan: GpuStagePlan): GpuScienceSessionBudget? {
        validate()
        val required = runCatching { Math.addExact(plan.hostBytes, plan.helperAllowanceBytes) }.getOrNull() ?: return null
        if (required <= 0L) return null
        val minimum = runCatching {
            val mib = GpuRenderProtocol.MIB
            maxOf(128L * mib, Math.multiplyExact(Math.addExact(required, mib - 1L) / mib, mib))
        }.getOrNull() ?: return null
        if (plan.combinedPeakBytes <= 0L) return null
        fun fits(fixed: Long): Boolean = runCatching {
            Math.addExact(fixed, plan.combinedPeakBytes) <= prepaidBytes
        }.getOrDefault(false)
        val expanded = maxOf(fixedBytes, minimum)
        // Keep an already usable epoch. Only a fresh-fit failure may justify
        // lowering its successor's fixed reserve, never lowering this lease.
        val fixed = when {
            fits(expanded) -> expanded
            minimum < fixedBytes && fits(minimum) -> minimum
            else -> return null
        }
        return copy(fixedBytes = fixed).also { it.validate() }
    }

    fun validate(hardCapBytes: Long = GpuRenderProtocol.HARD_CAP) {
        require(hardCapBytes in 1..(2L * 1024 * 1024 * 1024))
        require(prepaidBytes in 1..hardCapBytes && fixedBytes in 1..prepaidBytes && pipelineLimit in 1..64)
    }
}

/** No AutoCloseable: a frame finally or cache clear must not accidentally refund this lease. */
internal class GpuScienceSessionLease private constructor(
    val budget: GpuScienceSessionBudget,
    private val life: GpuScienceSessionLifetime,
    private val retained: RetainedNativeWork,
) {
    private var released = false
    @Synchronized fun quarantine() { if (!released) retained.quarantine() }
    @Synchronized fun releaseAfterStopProof(): Boolean {
        if (released) return false
        check(life.canReleaseSessionLease()) { "GPU session is not proven stopped" }
        retained.close()
        released = true
        return true
    }
    @Synchronized fun isReleased(): Boolean = released
    companion object {
        fun retain(context: Context, budget: GpuScienceSessionBudget, life: GpuScienceSessionLifetime, onPressure: () -> Unit): GpuScienceSessionLease {
            budget.validate()
            val reservation = NativeWorkReservations.retainForDetail(budget.prepaidBytes, { NativeWorkReservations.snapshot(context) }, onPressure)
            return GpuScienceSessionLease(budget, life, reservation)
        }
        internal fun forTesting(budget: GpuScienceSessionBudget, life: GpuScienceSessionLifetime, retained: RetainedNativeWork): GpuScienceSessionLease {
            budget.validate()
            return GpuScienceSessionLease(budget, life, retained)
        }
    }
}
